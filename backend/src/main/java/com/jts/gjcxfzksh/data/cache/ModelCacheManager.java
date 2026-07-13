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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
public class ModelCacheManager {

    private static final String MANAGER_CACHE_VERSION = "model-cache-v2";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Resource
    private MatsimConfig matsimConfig;

    @Value("${matsim.cache-build-threads:0}")
    private int cacheBuildThreads;

    @Value("${matsim.processing-threads:0}")
    private int processingThreads;

    private ExecutorService executor;
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    private final Map<String, ModelCacheStatus> statuses = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        ModelProcessingPool.configure(processingThreads);
        int threads = resolveCacheBuildThreads();
        executor = Executors.newFixedThreadPool(threads, r -> {
            Thread thread = new Thread(r, "matsim-cache-builder");
            thread.setDaemon(true);
            return thread;
        });
        log.info("模型缓存后台线程数: {}", threads);
        enqueueAllMissing();
    }

    @PreDestroy
    public void destroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Scheduled(initialDelay = 120_000, fixedDelay = 300_000)
    public void enqueueAllMissing() {
        matsimConfig.getSchemes().values().forEach(this::enqueueIfMissing);
    }

    public ModelCacheStatus status(Scheme scheme) {
        ModelCacheStatus inMemory = statuses.get(scheme.getName());
        if (inMemory != null) {
            refreshTiming(inMemory);
            return inMemory.copy();
        }
        if (isReady(scheme)) {
            ModelCacheStatus ready = ModelCacheStatus.missing(scheme.getCache());
            ready.setStatus("ready");
            ready.setMessage("缓存已生成");
            ready.setGeneratedAt(readGeneratedAt(scheme));
            ready.setProgressPercent(100);
            ready.setProgressMessage("缓存已生成");
            ready.setEtaSeconds(0);
            return ready;
        }
        return ModelCacheStatus.missing(scheme.getCache());
    }

    public void enqueueIfMissing(Scheme scheme) {
        if (scheme == null || isReady(scheme)) {
            return;
        }
        enqueue(scheme);
    }

    public void enqueue(Scheme scheme) {
        if (executor == null) {
            return;
        }
        String name = scheme.getName();
        if (!queued.add(name)) {
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
        status.setProgressMessage("等待后台缓存任务");
        status.setEtaSeconds(-1);
        status.setQueuedAt(System.currentTimeMillis());
        executor.submit(() -> build(scheme));
    }

    private int resolveCacheBuildThreads() {
        if (cacheBuildThreads > 0) {
            return cacheBuildThreads;
        }
        int cpus = Math.max(1, Runtime.getRuntime().availableProcessors());
        long maxHeap = Runtime.getRuntime().maxMemory();
        int memoryBound = (int) Math.max(1, maxHeap / (3L * 1024 * 1024 * 1024));
        return Math.max(1, Math.min(Math.min(4, Math.max(1, cpus / 8)), memoryBound));
    }

    private void build(Scheme scheme) {
        String name = scheme.getName();
        boolean loadedBefore = Datasource.loadStatus(name);
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
            updateProgress(status, 8, "正在加载模型基础数据");
            ensureModelLoaded(scheme);
            updateProgress(status, 25, "正在生成可视化缓存");
            Datasource.buildCaches(name, (time, currentVehicleCount) -> updateTrajectoryProgress(status, time, currentVehicleCount));
            updateProgress(status, 98, "正在写入缓存索引");
            writeManifest(scheme, true, "缓存已生成");
            status.setStatus("ready");
            status.setMessage("缓存已生成");
            status.setProgressPercent(100);
            status.setProgressMessage("缓存已生成");
            status.setEtaSeconds(0);
            status.setGeneratedAt(System.currentTimeMillis());
            status.setFinishedAt(System.currentTimeMillis());
            log.info("模型缓存生成完成: model={}, cache={}", name, scheme.getCache());
        } catch (Throwable e) {
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
            if (!loadedBefore && Datasource.loadStatus(name) && !Datasource.retainLoadedRequested(name)) {
                Datasource.remove(name);
            }
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
            status.setEtaSeconds(Math.max(1, Math.round(elapsed * (100.0 - percent) / percent)));
        }
    }

    private String formatEventTime(int seconds) {
        int safe = Math.max(0, seconds);
        int hour = safe / 3600;
        int minute = (safe % 3600) / 60;
        int second = safe % 60;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hour, minute, second);
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

    private boolean isReady(Scheme scheme) {
        Path manifestPath = MatsimCachePaths.manifestPath(scheme);
        if (!Files.exists(manifestPath)) {
            return false;
        }
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath.toFile(), MAP_TYPE);
            return "ready".equals(manifest.get("status"))
                    && MANAGER_CACHE_VERSION.equals(manifest.get("cacheVersion"))
                    && sameSourceFingerprint(sourceFingerprint(scheme), manifest.get("sources"))
                    && componentCachesReady(scheme);
        } catch (Exception e) {
            log.warn("模型缓存 manifest 读取失败: {}", manifestPath, e);
            return false;
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
                    && MatsimRoutePanelCache.isReady(data)
                    && MatsimStationPanelCache.isReady(data);
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
            if (!sameNumber(expectedItem.get("size"), actualItem.get("size"))
                    || !sameNumber(expectedItem.get("modified"), actualItem.get("modified"))) {
                return false;
            }
        }
        return true;
    }

    private boolean sameNumber(Object expected, Object actual) {
        return expected instanceof Number expectedNumber
                && actual instanceof Number actualNumber
                && expectedNumber.longValue() == actualNumber.longValue();
    }

    private long readGeneratedAt(Scheme scheme) {
        try {
            Map<String, Object> manifest = JSON.readValue(MatsimCachePaths.manifestPath(scheme).toFile(), MAP_TYPE);
            Object generatedAt = manifest.get("generatedAt");
            if (generatedAt instanceof Number number) {
                return number.longValue();
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private void writeManifest(Scheme scheme, boolean ready, String message) throws Exception {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("status", ready ? "ready" : "failed");
        manifest.put("cacheVersion", MANAGER_CACHE_VERSION);
        manifest.put("message", message);
        manifest.put("model", scheme.getName());
        manifest.put("largeModel", scheme.isLargeModel());
        manifest.put("generatedAt", System.currentTimeMillis());
        manifest.put("sources", sourceFingerprint(scheme));
        manifest.put("visualCacheVersion", MatsimPrecomputedCache.VISUAL_CACHE_VERSION);
        manifest.put("trajectoryCacheVersion", MatsimAnalysisCache.TRAJECTORY_CACHE_VERSION);

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
        if (files == null) {
            return result;
        }
        for (File file : files) {
            String lower = file.getName().toLowerCase(Locale.ROOT);
            if (isImportantSource(lower)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("size", file.length());
                item.put("modified", file.lastModified());
                result.put(file.getName(), item);
            }
        }
        return result;
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
}
