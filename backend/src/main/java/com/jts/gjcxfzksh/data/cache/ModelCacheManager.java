package com.jts.gjcxfzksh.data.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jts.gjcxfzksh.config.MatsimConfig;
import com.jts.gjcxfzksh.data.Datasource;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.ModelProcessingPool;
import com.jts.gjcxfzksh.data.entry.Scheme;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Comparator;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class ModelCacheManager {

    private static final String MANAGER_CACHE_VERSION = "model-cache-v5";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Resource
    private MatsimConfig matsimConfig;

    @Value("${matsim.cache-build-threads:0}")
    private int cacheBuildThreads;

    @Value("${matsim.processing-threads:0}")
    private int processingThreads;

    @Value("${matsim.cache-status-probe-ttl-ms:10000}")
    private long cacheStatusProbeTtlMs;

    @Value("${matsim.cache-prebuild-on-startup:false}")
    private boolean prebuildOnStartup;

    @Value("${matsim.cache-min-free-bytes:21474836480}")
    private long minFreeBytes;

    @Value("${matsim.cache-clean-old-versions:true}")
    private boolean cleanOldVersions;

    private ThreadPoolExecutor executor;
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    private final Map<String, CacheBuildTask> queuedTasks = new ConcurrentHashMap<>();
    private final Map<String, ModelCacheStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, ReadinessProbe> readinessProbes = new ConcurrentHashMap<>();
    private final AtomicLong taskSequence = new AtomicLong();

    @PostConstruct
    public void init() {
        ModelProcessingPool.configure(processingThreads);
        int threads = resolveCacheBuildThreads();
        executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new PriorityBlockingQueue<>(), r -> {
            Thread thread = new Thread(r, "matsim-cache-builder");
            thread.setDaemon(true);
            return thread;
        });
        log.info("模型缓存后台线程数: {}", threads);
        if (prebuildOnStartup) {
            matsimConfig.getSchemes().values().forEach(scheme -> enqueueIfMissing(scheme, TaskPriority.BACKGROUND));
        } else {
            log.info("启动时大模型缓存预构建已关闭；仅在用户选择模型后按需构建");
        }
    }

    @PreDestroy
    public void destroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public ModelCacheStatus status(Scheme scheme) {
        ModelCacheStatus inMemory = statuses.get(scheme.getName());
        if (inMemory != null) {
            // 某些工件（如大模型公交子路网）可能由模型加载线程与缓存队列并发补齐。
            // 之前一次构建的 failed 内存状态不能永久遮蔽已经完整的磁盘工件。
            if ("failed".equals(inMemory.getStatus()) && readiness(scheme).ready()) {
                statuses.remove(scheme.getName(), inMemory);
                inMemory = null;
            }
        }
        if (inMemory != null) {
            refreshTiming(inMemory);
            return inMemory.copy();
        }
        ReadinessProbe probe = readiness(scheme);
        if (probe.ready()) {
            ModelCacheStatus ready = ModelCacheStatus.missing(scheme.getCache());
            ready.setStatus("ready");
            ready.setMessage("缓存已生成");
            ready.setGeneratedAt(probe.generatedAt());
            ready.setProgressPercent(100);
            ready.setProgressMessage("缓存已生成");
            ready.setEtaSeconds(0);
            return ready;
        }
        return ModelCacheStatus.missing(scheme.getCache());
    }

    public void enqueueIfMissing(Scheme scheme) {
        enqueueIfMissing(scheme, TaskPriority.USER);
    }

    private void enqueueIfMissing(Scheme scheme, TaskPriority priority) {
        if (scheme == null || readiness(scheme).ready()) {
            return;
        }
        enqueue(scheme, priority);
    }

    public void enqueue(Scheme scheme) {
        enqueue(scheme, TaskPriority.USER);
    }

    private void enqueue(Scheme scheme, TaskPriority priority) {
        if (executor == null) {
            return;
        }
        String name = scheme.getName();
        readinessProbes.remove(name);
        if (!queued.add(name)) {
            CacheBuildTask existing = queuedTasks.get(name);
            if (existing != null && existing.promote(priority) && executor.getQueue().remove(existing)) {
                executor.execute(existing);
                ModelCacheStatus current = statuses.get(name);
                if (current != null && "queued".equals(current.getStatus())) {
                    current.setProgressMessage("用户请求已提升为高优先级");
                }
            }
            return;
        }
        ModelCacheStatus status = statuses.computeIfAbsent(name, ignored -> ModelCacheStatus.missing(scheme.getCache()));
        status.setStatus("queued");
        status.setMessage("缓存生成已进入后台队列");
        status.setStartedAt(0);
        status.setFinishedAt(0);
        status.setGeneratedAt(0);
        status.setElapsedSeconds(0);
        status.setProgressPercent(1);
        status.setProgressMessage(priority == TaskPriority.USER ? "等待用户优先缓存任务" : "等待后台缓存任务");
        status.setEtaSeconds(-1);
        status.setQueuedAt(System.currentTimeMillis());
        CacheBuildTask task = new CacheBuildTask(scheme, priority, taskSequence.incrementAndGet());
        queuedTasks.put(name, task);
        executor.execute(task);
    }

    private int resolveCacheBuildThreads() {
        if (cacheBuildThreads > 0) {
            return cacheBuildThreads;
        }
        // 大模型构建同时占用 events/plans 解压、临时完整路网和外置盘吞吐；默认严格串行。
        // 管理员完成压测后仍可通过 MATSIM_CACHE_BUILD_THREADS 显式提高。
        return 1;
    }

    private void build(Scheme scheme) {
        String name = scheme.getName();
        readinessProbes.remove(name);
        ModelCacheStatus status = statuses.computeIfAbsent(name, ignored -> ModelCacheStatus.missing(scheme.getCache()));
        status.setStatus("building");
        status.setMessage("正在生成模型缓存");
        status.setStartedAt(System.currentTimeMillis());
        status.setFinishedAt(0);
        status.setGeneratedAt(0);
        status.setElapsedSeconds(0);
        status.setProgressPercent(0);
        status.setEtaSeconds(-1);
        updateProgress(status, 3, "准备缓存目录");
        try {
            Files.createDirectories(MatsimCachePaths.modelDir(scheme));
            if (cleanOldVersions) {
                // 重建前先移除历史版本；当前版本由各组件在发布时原位替换。
                cleanupOldCacheVersions(scheme);
            }
            checkDiskCapacity(scheme);
            updateProgress(status, 8, "正在加载模型基础数据");
            ensureModelLoaded(scheme);
            updateProgress(status, 10, "开始分阶段生成派生缓存");
            Datasource.buildCachesWithProgress(name, (percent, message) -> updateProgress(status, percent, message));
            updateProgress(status, 98, "正在写入缓存索引");
            writeManifest(scheme, true, "缓存已生成");
            if (cleanOldVersions) {
                cleanupOldCacheVersions(scheme);
            }
            status.setStatus("ready");
            status.setMessage("缓存已生成");
            status.setProgressPercent(100);
            status.setProgressMessage("缓存已生成");
            status.setEtaSeconds(0);
            status.setGeneratedAt(System.currentTimeMillis());
            status.setFinishedAt(System.currentTimeMillis());
            log.info("模型缓存生成完成: model={}, cache={}", name, scheme.getCache());
        } catch (Throwable e) {
            readinessProbes.remove(name);
            status.setStatus("failed");
            status.setMessage(e.getMessage() == null ? "缓存生成失败" : e.getMessage());
            status.setProgressMessage(status.getMessage());
            status.setEtaSeconds(-1);
            status.setFinishedAt(System.currentTimeMillis());
            try {
                writeManifest(scheme, false, status.getMessage());
            } catch (Exception ignored) {
            }
            log.error("模型缓存生成失败: model={}, error={}", name, e.getMessage(), e);
        } finally {
            queued.remove(name);
            queuedTasks.remove(name);
        }
    }

    private void updateTrajectoryProgress(ModelCacheStatus status, int eventTime, int currentVehicleCount) {
        int timePercent = (int) Math.round(Math.min(1.0, Math.max(0.0, eventTime / 86_400.0)) * 60.0);
        int percent = Math.min(95, Math.max(35, 35 + timePercent));
        updateProgress(status, percent, "正在流式解析 events，已处理到 " + formatEventTime(eventTime) + "，车辆约 " + currentVehicleCount + " 台");
    }

    private void updateProgress(ModelCacheStatus status, int percent, String message) {
        int next = Math.max(0, Math.min(100, percent));
        status.setProgressPercent(Math.max(status.getProgressPercent(), next));
        status.setProgressMessage(message);
        refreshTiming(status);
    }

    private void refreshTiming(ModelCacheStatus status) {
        if (status.getStartedAt() <= 0) {
            status.setElapsedSeconds(0);
            if (status.getProgressPercent() <= 0 || "ready".equals(status.getStatus())) {
                status.setEtaSeconds("ready".equals(status.getStatus()) ? 0 : -1);
            }
            return;
        }
        long now = status.getFinishedAt() > 0 ? status.getFinishedAt() : System.currentTimeMillis();
        long elapsed = Math.max(0, (now - status.getStartedAt()) / 1000);
        status.setElapsedSeconds(elapsed);
        int percent = status.getProgressPercent();
        if ("ready".equals(status.getStatus())) {
            status.setEtaSeconds(0);
        } else if (!"building".equals(status.getStatus()) || percent <= 0 || percent >= 100) {
            status.setEtaSeconds(-1);
        } else {
            // events 时间戳、plans 人数和瓦片数量不是同一线性工作量；无历史阶段样本时不伪造 ETA。
            status.setEtaSeconds(-1);
        }
    }

    private String formatEventTime(int seconds) {
        int safe = Math.max(0, seconds);
        int hour = safe / 3600;
        int minute = (safe % 3600) / 60;
        int second = safe % 60;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hour, minute, second);
    }

    private void checkDiskCapacity(Scheme scheme) throws Exception {
        Path cacheDir = MatsimCachePaths.modelDir(scheme);
        long usable = Files.getFileStore(cacheDir).getUsableSpace();
        long sourceBytes = Math.max(0L, scheme.getOutputBytes());
        long estimated = scheme.isLargeModel()
                ? saturatedAdd(saturatedMultiply(sourceBytes, 2L), 8L * 1024 * 1024 * 1024)
                : Math.max(2L * 1024 * 1024 * 1024, sourceBytes);
        long required = saturatedAdd(Math.max(0L, minFreeBytes), estimated);
        if (usable < required) {
            throw new IllegalStateException("缓存磁盘空间不足：可用 " + humanBytes(usable)
                    + "，本次构建预计至少需要 " + humanBytes(estimated)
                    + "，并需保留 " + humanBytes(Math.max(0L, minFreeBytes)));
        }
    }

    private static long saturatedMultiply(long value, long factor) {
        if (value <= 0 || factor <= 0) return 0L;
        return value > Long.MAX_VALUE / factor ? Long.MAX_VALUE : value * factor;
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static String humanBytes(long bytes) {
        double gib = bytes / (1024.0 * 1024 * 1024);
        return String.format(Locale.ROOT, "%.1fGiB", gib);
    }

    private void cleanupOldCacheVersions(Scheme scheme) {
        Path root = MatsimCachePaths.modelDir(scheme);
        Set<String> active = new HashSet<>(Set.of(
                MatsimPrecomputedCache.VISUAL_CACHE_VERSION,
                MatsimAnalysisCache.TRAJECTORY_CACHE_VERSION,
                MatsimAnalysisCache.PERSON_TRACK_CACHE_VERSION,
                MatsimPersonTrackStore.PARTITION_CACHE_VERSION,
                MatsimRoutePanelCache.ROUTE_PANEL_CACHE_VERSION,
                MatsimStationPanelCache.STATION_PANEL_CACHE_VERSION,
                MatsimTransferCache.TRANSFER_CACHE_VERSION,
                MatsimPopulationCache.POPULATION_CACHE_VERSION,
                MatsimTripEndsCache.TRIPENDS_CACHE_VERSION,
                MatsimTripEndsCache.TRIP_DISTRIBUTION_CACHE_VERSION,
                MatsimPassengerProfileCache.PROFILE_CACHE_VERSION,
                MatsimCorridorCache.CORRIDOR_CACHE_VERSION,
                MatsimLinkSpeedCache.LINK_SPEED_CACHE_VERSION,
                MatsimLargeModelNetworkCache.CACHE_VERSION
        ));
        try (var children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .filter(path -> isVersionedCacheDir(path.getFileName().toString()))
                    .filter(path -> !active.contains(path.getFileName().toString()))
                    .forEach(path -> {
                        deleteTree(path);
                        log.info("已清理旧缓存版本: model={}, dir={}", scheme.getName(), path.getFileName());
                    });
        } catch (Exception e) {
            // 清理失败不回滚已经 ready 的新缓存。
            log.warn("清理旧缓存版本失败: model={}", scheme.getName(), e);
        }
        cleanupStaleTemporaryArtifacts(scheme, root);
    }

    /** 进程被杀时可能遗留 building/tmp；只清理超过 24h 且名称明确的临时工件。 */
    private void cleanupStaleTemporaryArtifacts(Scheme scheme, Path root) {
        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24);
        try (var paths = Files.walk(root, 4)) {
            List<Path> stale = paths
                    .filter(path -> !path.equals(root))
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        if (!(name.contains(".building-") || name.endsWith(".tmp"))) return false;
                        try {
                            return Files.getLastModifiedTime(path).toMillis() < cutoff;
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path path : stale) {
                if (Files.isDirectory(path)) deleteTree(path);
                else Files.deleteIfExists(path);
            }
            if (!stale.isEmpty()) {
                log.info("已清理超过24小时的缓存临时工件: model={}, count={}", scheme.getName(), stale.size());
            }
        } catch (Exception e) {
            log.warn("清理缓存临时工件失败: model={}", scheme.getName(), e);
        }
    }

    private static boolean isVersionedCacheDir(String name) {
        return name != null && name.matches("(?:visual|trajectory|pt-events|person-track-partitions|route-panel|station-panel|transfer|population|tripends|trip-distribution|passenger-profile|corridor|link-speed|large-network)-v\\d+");
    }

    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("删除旧缓存失败: " + root, e);
        }
    }

    private void ensureModelLoaded(Scheme scheme) throws InterruptedException {
        if (Datasource.loadStatus(scheme.getName())) {
            return;
        }
        if (Datasource.loadingStatus(scheme.getName())) {
            while (Datasource.loadingStatus(scheme.getName())) {
                Thread.sleep(1000);
            }
            if (Datasource.loadStatus(scheme.getName())) {
                return;
            }
        }
        Datasource.loadForCache(scheme);
    }

    private ReadinessProbe readiness(Scheme scheme) {
        long now = System.currentTimeMillis();
        ReadinessProbe cached = readinessProbes.get(scheme.getName());
        if (cached != null && cached.expiresAt() > now) {
            return cached;
        }
        return readinessProbes.compute(scheme.getName(), (name, existing) -> {
            long probeNow = System.currentTimeMillis();
            if (existing != null && existing.expiresAt() > probeNow) {
                return existing;
            }
            return inspectReadiness(scheme, probeNow);
        });
    }

    private ReadinessProbe inspectReadiness(Scheme scheme, long now) {
        long expiresAt = now + Math.max(250L, cacheStatusProbeTtlMs);
        Path manifestPath = MatsimCachePaths.manifestPath(scheme);
        // 各组件都有自己的版本与源指纹，manager manifest 只是汇总索引。上次进程即使在最后
        // 一步 OOM/被终止，只要组件完整，就应原地修复索引，不能重新加载整个 V6。
        boolean componentsReady = componentCachesReady(scheme);
        if (!Files.exists(manifestPath)) {
            return componentsReady
                    ? repairManagerManifest(scheme, expiresAt)
                    : new ReadinessProbe(false, 0L, expiresAt);
        }
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath.toFile(), MAP_TYPE);
            boolean sourceMatches = sameSourceFingerprint(sourceFingerprint(scheme), manifest.get("sources"));
            boolean fingerprintSchemaMatches = MatsimSourceFingerprint.SCHEMA.equals(manifest.get("fingerprintSchema"));
            boolean ready = "ready".equals(manifest.get("status"))
                    && MANAGER_CACHE_VERSION.equals(manifest.get("cacheVersion"))
                    && fingerprintSchemaMatches
                    && sourceMatches
                    && componentsReady;
            Object generatedAt = manifest.get("generatedAt");
            long generated = generatedAt instanceof Number number ? number.longValue() : 0L;
            // manager manifest 只是组件索引。组件自身的版本与依赖指纹均有效时，无论索引版本、
            // 指纹算法或模型总指纹是否变化，都只修复索引，绝不能连带清空其他组件。
            if (!ready && componentsReady) {
                return repairManagerManifest(scheme, expiresAt);
            }
            return new ReadinessProbe(ready, generated, expiresAt);
        } catch (Exception e) {
            log.warn("模型缓存 manifest 读取失败: {}", manifestPath, e);
            return componentsReady
                    ? repairManagerManifest(scheme, expiresAt)
                    : new ReadinessProbe(false, 0L, expiresAt);
        }
    }

    private ReadinessProbe repairManagerManifest(Scheme scheme, long expiresAt) {
        try {
            writeManifest(scheme, true, "组件缓存完整，汇总索引已自动修复");
            long generatedAt = System.currentTimeMillis();
            log.info("模型缓存汇总索引自动修复完成，无需重新加载模型: model={}", scheme.getName());
            return new ReadinessProbe(true, generatedAt, expiresAt);
        } catch (Exception e) {
            log.warn("模型缓存汇总索引自动修复失败: model={}", scheme.getName(), e);
            return new ReadinessProbe(false, 0L, expiresAt);
        }
    }

    private boolean componentCachesReady(Scheme scheme) {
        try {
            MatsimData data = new MatsimData(
                    scheme.getName(),
                    scheme.getOutput(),
                    scheme.getCache(),
                    scheme.isLargeModel()
            );
            if (scheme.getDesc() != null) {
                data.setArea(scheme.getDesc().getArea());
                data.setScale(scheme.getDesc().getScale());
            }
            return MatsimPrecomputedCache.isVisualCacheReady(data)
                    && (!data.isLargeModel() || MatsimLargeModelNetworkCache.isReady(data))
                    && MatsimAnalysisCache.readReadyTrajectoryLightManifest(data) != null
                    && MatsimAnalysisCache.isPersonTrackStoreReady(data)
                    && MatsimRoutePanelCache.isReady(data)
                    && MatsimStationPanelCache.isReady(data)
                    && MatsimTransferCache.isReady(data)
                    && MatsimPopulationCache.isReady(data)
                    && MatsimTripEndsCache.isReady(data)
                    && MatsimPassengerProfileCache.isReady(data)
                    && MatsimCorridorCache.isReady(data)
                    && MatsimLinkSpeedCache.isReady(data);
        } catch (Exception e) {
            log.warn("模型组件缓存状态读取失败: model={}", scheme == null ? "" : scheme.getName(), e);
            return false;
        }
    }

    private boolean sameSourceFingerprint(Map<String, Object> expected, Object actualValue) {
        if (!(actualValue instanceof Map<?, ?> actual) || expected.size() != actual.size()) {
            return false;
        }
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            Object expectedItemValue = entry.getValue();
            Object actualItemValue = actual.get(entry.getKey());
            if (!(expectedItemValue instanceof Map<?, ?> expectedItem) || !(actualItemValue instanceof Map<?, ?> actualItem)) {
                return false;
            }
            if (!MatsimSourceFingerprint.sameSourceItem(expectedItem, actualItem)) {
                return false;
            }
        }
        return true;
    }

    private void writeManifest(Scheme scheme, boolean ready, String message) throws Exception {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("status", ready ? "ready" : "failed");
        manifest.put("cacheVersion", MANAGER_CACHE_VERSION);
        manifest.put("fingerprintSchema", MatsimSourceFingerprint.SCHEMA);
        manifest.put("message", message);
        manifest.put("model", scheme.getName());
        manifest.put("largeModel", scheme.isLargeModel());
        manifest.put("generatedAt", System.currentTimeMillis());
        manifest.put("sources", sourceFingerprint(scheme));
        manifest.put("visualCacheVersion", MatsimPrecomputedCache.VISUAL_CACHE_VERSION);
        manifest.put("trajectoryCacheVersion", MatsimAnalysisCache.TRAJECTORY_CACHE_VERSION);
        manifest.put("transferCacheVersion", MatsimTransferCache.TRANSFER_CACHE_VERSION);
        manifest.put("populationCacheVersion", MatsimPopulationCache.POPULATION_CACHE_VERSION);
        manifest.put("tripEndsCacheVersion", MatsimTripEndsCache.TRIPENDS_CACHE_VERSION);
        manifest.put("tripDistributionCacheVersion", MatsimTripEndsCache.TRIP_DISTRIBUTION_CACHE_VERSION);
        manifest.put("passengerProfileCacheVersion", MatsimPassengerProfileCache.PROFILE_CACHE_VERSION);
        manifest.put("corridorCacheVersion", MatsimCorridorCache.CORRIDOR_CACHE_VERSION);
        manifest.put("linkSpeedCacheVersion", MatsimLinkSpeedCache.LINK_SPEED_CACHE_VERSION);

        Path path = MatsimCachePaths.manifestPath(scheme);
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        JSON.writeValue(tmp.toFile(), manifest);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Map<String, Object> sourceFingerprint(Scheme scheme) {
        Map<String, Object> result = new LinkedHashMap<>();
        File output = new File(scheme.getOutput());
        File[] files = output.listFiles(file -> file.isFile() && !file.getName().startsWith(".") && !file.getName().startsWith("._"));
        if (files != null) {
            for (File file : files) {
                String lower = file.getName().toLowerCase(Locale.ROOT);
                if (isImportantSource(lower)) {
                    putSourceFingerprint(result, file.getName(), file);
                }
            }
        }
        // 面积等评价口径来自模型根目录 desc.json，它变化也必须使指标缓存失效。
        if (scheme.getFolder() != null && !scheme.getFolder().isBlank()) {
            File desc = new File(scheme.getFolder(), "desc.json");
            if (desc.isFile()) {
                putSourceFingerprint(result, "../desc.json", desc);
            }
        }
        return result;
    }

    private void putSourceFingerprint(Map<String, Object> result, String key, File file) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("size", file.length());
        item.put("modified", file.lastModified());
        item.put("signature", MatsimSourceFingerprint.signature(file.toPath()));
        result.put(key, item);
    }

    private boolean isImportantSource(String name) {
        return name.contains("events")
                || name.contains("network")
                || name.contains("plans")
                || name.contains("transitschedule")
                || name.contains("transitvehicles")
                || name.contains("facilities")
                || name.contains("linkstats")
                || name.contains("link_stats")
                || name.endsWith("links.csv")
                || name.endsWith("links.csv.gz")
                || name.contains("config");
    }

    private record ReadinessProbe(boolean ready, long generatedAt, long expiresAt) {
    }

    private enum TaskPriority {
        USER(0), BACKGROUND(10);

        private final int order;

        TaskPriority(int order) {
            this.order = order;
        }
    }

    private final class CacheBuildTask implements Runnable, Comparable<CacheBuildTask> {
        private final Scheme scheme;
        private final long sequence;
        private volatile TaskPriority priority;

        private CacheBuildTask(Scheme scheme, TaskPriority priority, long sequence) {
            this.scheme = scheme;
            this.priority = priority;
            this.sequence = sequence;
        }

        private boolean promote(TaskPriority requested) {
            if (requested.order >= priority.order) return false;
            priority = requested;
            return true;
        }

        @Override
        public void run() {
            build(scheme);
        }

        @Override
        public int compareTo(CacheBuildTask other) {
            int byPriority = Integer.compare(priority.order, other.priority.order);
            return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
        }
    }
}
