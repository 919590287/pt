package com.jts.gjcxfzksh.data.cache;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.luben.zstd.Zstd;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.ModelProcessingPool;
import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import com.jts.gjcxfzksh.data.handler.PTHandler;
import com.jts.gjcxfzksh.data.id.DepartureId;
import com.jts.gjcxfzksh.data.id.LineId;
import com.jts.gjcxfzksh.data.id.PersonId;
import com.jts.gjcxfzksh.data.id.RouteId;
import com.jts.gjcxfzksh.data.id.StopFacilityId;
import com.jts.gjcxfzksh.data.id.VehicleId;
import com.jts.gjcxfzksh.data.read.EventReader;
import com.jts.gjcxfzksh.data.read.FastEventReader;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.LinkLeaveEventHandler;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.PersonLeavesVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleEntersTrafficEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleLeavesTrafficEventHandler;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.events.handler.EventHandler;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.api.experimental.events.handler.VehicleArrivesAtFacilityEventHandler;
import org.matsim.core.api.experimental.events.handler.VehicleDepartsAtFacilityEventHandler;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

@Slf4j
public final class MatsimAnalysisCache {

    // v15 保持 v14 的 30s/10s/1~2s/4096m 查询协议与前端二进制响应完全不变，
    // 仅把每个空间 tile 的记录独立编码成 Zstd frame。索引保存压缩偏移、压缩长度和
    // 原始记录数；视口查询只读取并解压命中的 tile，避免为随机访问解压整个 30s 容器。
    public static final String TRAJECTORY_CACHE_VERSION = "trajectory-v15";
    public static final int TRAJECTORY_CHUNK_SECONDS = 30;
    public static final int TRAJECTORY_PLAYBACK_WINDOW_SECONDS = 10;
    public static final int TRAJECTORY_SPATIAL_TILE_METERS = 4096;
    /**
     * events 压缩文件达到该规模后，即使模型整体未被标记为“大模型”，
     * 轨迹也必须使用边解析边落盘的构建路径。经验放大率远高于压缩文件大小：
     * 512 MiB events 已可能在对象化后占用数 GiB Java heap。
     */
    static final long TRAJECTORY_STREAMING_EVENTS_THRESHOLD_BYTES = 512L * 1024L * 1024L;

    // pt-events-v3: PTHandler/大模型流式路径接入 TransitDriverStarts 动态映射 + 司机显式过滤，
    // personTracks 的 line/route/departure 归属语义变更。该缓存是 route/station 面板的输入，
    // 联动 bump 见 MatsimRoutePanelCache / MatsimStationPanelCache 版本注释。
    public static final String PERSON_TRACK_CACHE_VERSION = "pt-events-v3";
    private static final String PERSON_TRACK_FILE = "person-tracks.tsv.gz";
    private static final byte[] TRAJECTORY_BINARY_MAGIC = new byte[]{'G', 'J', 'T', 'B'};
    private static final byte[] TRAJECTORY_ZSTD_MAGIC = new byte[]{'G', 'J', 'T', 'Z'};
    private static final byte[] TRAJECTORY_SPATIAL_INDEX_MAGIC = new byte[]{'G', 'J', 'T', 'I'};
    private static final int TRAJECTORY_BINARY_VERSION = 2;
    private static final int TRAJECTORY_SPATIAL_INDEX_VERSION = 3;
    private static final int TRAJECTORY_LEGACY_SPATIAL_INDEX_VERSION = 2;
    private static final int TRAJECTORY_BINARY_HEADER_BYTES = 64;
    private static final int TRAJECTORY_SPATIAL_INDEX_HEADER_BYTES = 64;
    // v3: tileX/tileY(int32) + compressedOffset(int64) + compressedBytes/count(int32)
    // + tile 内全部 segment 的 minX/minY/maxX/maxY(float32, 相对 origin)
    private static final int TRAJECTORY_SPATIAL_INDEX_ENTRY_BYTES = 40;
    private static final int TRAJECTORY_LEGACY_SPATIAL_INDEX_ENTRY_BYTES = 32;
    // start/end/x1/y1/x2/y2/mode(float32), vehicleId(int32), distanceMeters(float32)
    private static final int TRAJECTORY_BINARY_STRIDE = 9;
    // 大模型容器内部记录: originalStart/originalEnd/vehicleIndex/linkIndex (int32)。
    private static final int TRAJECTORY_COMPACT_STRIDE = 4;
    private static final String TRAJECTORY_LINK_DICTIONARY_FILE = "link-geometry.dict.zst";
    private static final String TRAJECTORY_VEHICLE_DICTIONARY_FILE = "vehicle-metadata.dict.zst";
    private static final byte[] TRAJECTORY_LINK_DICTIONARY_MAGIC = new byte[]{'G', 'J', 'L', 'D'};
    private static final byte[] TRAJECTORY_VEHICLE_DICTIONARY_MAGIC = new byte[]{'G', 'J', 'V', 'D'};
    private static final int TRAJECTORY_IO_BUFFER_BYTES = 4 * 1024 * 1024;
    private static final int TRAJECTORY_ZSTD_LEVEL = 1;
    private static final int TRAJECTORY_RAW_CHUNK_BUFFER_BYTES = 64 * 1024;
    private static final long TRAJECTORY_LEGACY_FULL_CHUNK_MAX_BYTES = 32L * 1024 * 1024;
    private static final int TRAJECTORY_QUEUE_DEFAULT_SIZE = 65_536;
    private static final int TRAJECTORY_MAX_OPEN_CHUNKS_DEFAULT = 4;
    private static final String TRAJECTORY_LIGHT_MANIFEST_FILE = "manifest-lite.json";
    private static final String TRAJECTORY_REPAIR_MARKER_FILE = "repair-required.json";
    private static final int TRAJECTORY_LIGHT_MANIFEST_VERSION = 2;
    // 裸“N线”必须带“地铁/轨道”前缀才算地铁线号，“N号线”单独成立——
    // 否则 B1线/K1线 等公交快线命名会被误判为地铁（与 MatsimRoutePanelCache 同步维护）。
    private static final Pattern CHINESE_METRO_LINE_NUMBER_PATTERN = Pattern.compile(
            "(?i)(?:地铁|轨道)\\s*([0-9]{1,2}|[一二三四五六七八九十]{1,4})\\s*(?:号线|线)"
                    + "|([0-9]{1,2}|[一二三四五六七八九十]{1,4})\\s*号线"
    );
    private static final Pattern ENGLISH_METRO_LINE_NUMBER_PATTERN = Pattern.compile(
            "(?i)(?:metro|subway|mtr)(?:[-_\\s]*line)?[-_\\s]*([0-9]{1,2})\\b|\\bline[-_\\s]*([0-9]{1,2})\\b"
    );
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final ConcurrentMap<String, Object> BUILD_LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Boolean> TRAJECTORY_REPAIR_REQUESTS = new ConcurrentHashMap<>();
    // manifest-lite 带有全天逐秒全市统计，V6 可达数 MB。连续播放一次请求会先算 ETag、
    // 再读取 body，不能重复反序列化；以原子发布文件的 mtime/size/fileKey 做线程安全失效。
    // 广州 V6 的 8.7 MiB JSON 对象化后会超过 32 MiB；分区若过小，每次 put 都会
    // 遍历整份清单做估算后拒绝缓存，使一个轨迹请求重复两次这个慢路径。
    private static final BackendMemoryCache<String, CachedTrajectoryLightManifest> TRAJECTORY_LIGHT_MANIFESTS =
            new BackendMemoryCache<>("trajectory-light-manifest", 64L * 1024 * 1024,
                    cached -> BackendMemoryCache.estimate(cached.manifest));
    private static final BackendMemoryCache<String, CachedTrajectoryDictionaries> TRAJECTORY_DICTIONARIES =
            new BackendMemoryCache<>("trajectory-dictionaries", 128L * 1024 * 1024,
                    cached -> cached.value.estimatedBytes());
    // 前端播放窗是 10s，磁盘容器是 30s。同一视口的三个连续请求共用一份
    // 已解压、字典展开并完成空间/模式筛选的 30s GJTB 记录。
    private static final BackendMemoryCache<TrajectorySpatialSelectionKey, CachedTrajectorySpatialSelection>
            TRAJECTORY_SPATIAL_SELECTIONS = new BackendMemoryCache<>(
                    "trajectory-spatial-selection",
                    128L * 1024 * 1024,
                    CachedTrajectorySpatialSelection::estimatedBytes
            );
    private static final ConcurrentMap<TrajectorySpatialSelectionKey,
            CompletableFuture<CachedTrajectorySpatialSelection>> TRAJECTORY_SPATIAL_SELECTION_LOADS =
            new ConcurrentHashMap<>();
    private static final AtomicLong TRAJECTORY_SPATIAL_SELECTION_LOAD_COUNT = new AtomicLong();
    private static final AtomicInteger ACTIVE_TRAJECTORY_BUILDS = new AtomicInteger();

    private MatsimAnalysisCache() {
    }

    public interface BuildProgress {
        void markPoint(int time, int currentVehicleCount);
    }

    public static boolean isTrajectoryBuildActive() {
        return ACTIVE_TRAJECTORY_BUILDS.get() > 0;
    }

    /**
     * 一次模型缓存任务内同时准备乘客上下车与车辆轨迹。
     *
     * <p>旧流程在小模型首次生成缓存时先为 personTracks 扫一遍 events，随后又为轨迹扫一遍。
     * 两个 handler 都是流式消费，合并到同一个 EventReader 后可直接省掉一次解压、XML 解析和磁盘读取，
     * 峰值内存不会因此增加（两份结果原本也会同时驻留）。已有任一缓存时仍只补缺失部分。</p>
     */
    public static void prepareAllOnModelLoad(MatsimData data, BuildProgress progress) throws Exception {
        if (shouldStreamTrajectoryBuild(data)) {
            ensureLargeTrajectoryCache(data, progress);
            // 大模型的 person-tracks.tsv.gz 是“冷数据仓”。只有确认能放进当前堆预算时才对象化；
            // V6 的 2064 万条记录会膨胀为数 GB Java 对象，必须保持磁盘态。
            preloadPersonTracksIfReady(data);
            return;
        }

        boolean tracksReady = loadPersonTracksFromCache(data);
        // 完整 manifest 可能包含数百万车辆/线路明细（百 MB 级）。模型加载阶段只需要
        // 判断缓存是否就绪，读取轻量 manifest 即可，避免每次后续加载都反序列化整份明细。
        Map<String, Object> trajectoryManifest = readReadyTrajectoryLightManifest(data);
        if (tracksReady && trajectoryManifest != null) {
            return;
        }

        String cacheKey = trajectoryCacheKey(data);
        Object lock = BUILD_LOCKS.computeIfAbsent(cacheKey, key -> new Object());
        synchronized (lock) {
            tracksReady = data.getPersonTracks() != null && !data.getPersonTracks().isEmpty()
                    || loadPersonTracksFromCache(data);
            trajectoryManifest = readReadyTrajectoryLightManifest(data);
            if (tracksReady && trajectoryManifest != null) {
                return;
            }

            if (!tracksReady && trajectoryManifest == null) {
                ACTIVE_TRAJECTORY_BUILDS.incrementAndGet();
                try {
                    PTHandler ptHandler = new PTHandler(data.getSchedule());
                    TrajectoryMeta trajectoryMeta = buildTrajectoryMeta(data);
                    VehicleTrajectoryHandler trajectoryHandler = new VehicleTrajectoryHandler(
                            data.getNetwork(), trajectoryMeta.transitVehicles, progress);
                    log.info("单次解析events同时生成乘客与轨迹缓存: model={}", data.getName());
                    new EventReader(ptHandler, trajectoryHandler).read(data.getOutfile().getEvents());

                    Set<PTPersonTrack> tracks = new LinkedHashSet<>(ptHandler.getPersonTracks());
                    data.setPersonTracks(tracks);
                    writePersonTracksCache(data, tracks);
                    writeTrajectoryCache(data, trajectoryHandler, trajectoryMeta);
                } finally {
                    ACTIVE_TRAJECTORY_BUILDS.decrementAndGet();
                }
                return;
            }

            if (!tracksReady) {
                prepareOnModelLoad(data);
            }
            if (trajectoryManifest == null) {
                ensureTrajectoryCache(data, progress);
            }
        }
    }

    public static void prepareOnModelLoad(MatsimData data) {
        try {
            boolean personTracksReady = data.isLargeModel()
                    ? preloadPersonTracksIfReady(data)
                    : loadPersonTracksFromCache(data);
            if (personTracksReady) {
                log.info("读取模型乘客上下车轻量缓存: model={}, personTracks={}", data.getName(), true);
                return;
            }
            if (data.isLargeModel()) {
                log.info("模型[{}]为大模型，乘客上下车轻量缓存尚未生成，等待离线流式缓存任务", data.getName());
                return;
            }

            List<EventHandler> handlers = new ArrayList<>(2);
            PTHandler ptHandler = null;
            ptHandler = new PTHandler(data.getSchedule());
            handlers.add(ptHandler);

            if (handlers.isEmpty()) {
                return;
            }

            log.info("生成模型乘客上下车轻量缓存: model={}, personTracksReady={}",
                    data.getName(), personTracksReady);
            new EventReader(handlers.toArray(new EventHandler[0])).read(data.getOutfile().getEvents());

            if (ptHandler != null) {
                Set<PTPersonTrack> tracks = new LinkedHashSet<>(ptHandler.getPersonTracks());
                data.setPersonTracks(tracks);
                writePersonTracksCache(data, tracks);
            }
        } catch (Exception e) {
            log.error("模型轻量分析产物生成失败: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public static Map<String, Object> ensureTrajectoryCache(MatsimData data) throws Exception {
        return ensureTrajectoryCache(data, null);
    }

    public static Map<String, Object> ensureTrajectoryCache(MatsimData data, BuildProgress progress) throws Exception {
        if (shouldStreamTrajectoryBuild(data)) {
            return ensureLargeTrajectoryCache(data, progress);
        }
        Map<String, Object> manifest = readReadyTrajectoryLightManifest(data);
        if (manifest != null) {
            return manifest;
        }

        String cacheKey = trajectoryCacheKey(data);
        Object lock = BUILD_LOCKS.computeIfAbsent(cacheKey, key -> new Object());
        synchronized (lock) {
            manifest = readReadyTrajectoryLightManifest(data);
            if (manifest != null) {
                return manifest;
            }
            ACTIVE_TRAJECTORY_BUILDS.incrementAndGet();
            try {
                TrajectoryMeta trajectoryMeta = buildTrajectoryMeta(data);
                VehicleTrajectoryHandler handler = new VehicleTrajectoryHandler(data.getNetwork(), trajectoryMeta.transitVehicles, progress);
                new EventReader(handler).read(data.getOutfile().getEvents());
                return writeTrajectoryCache(data, handler, trajectoryMeta);
            } finally {
                ACTIVE_TRAJECTORY_BUILDS.decrementAndGet();
            }
        }
    }

    private static Map<String, Object> ensureLargeTrajectoryCache(MatsimData data, BuildProgress progress) throws Exception {
        Map<String, Object> manifest = readReadyTrajectoryLightManifest(data);
        if (manifest != null && isPersonTracksCacheReady(data)) {
            return manifest;
        }

        String cacheKey = trajectoryCacheKey(data);
        Object lock = BUILD_LOCKS.computeIfAbsent(cacheKey, key -> new Object());
        synchronized (lock) {
            manifest = readReadyTrajectoryLightManifest(data);
            if (manifest != null && isPersonTracksCacheReady(data)) {
                return manifest;
            }
            ACTIVE_TRAJECTORY_BUILDS.incrementAndGet();
            try {
                // 大模型边解析边落分块，因此开始前必须将当前版本目录整体重建：
                // 旧 manifest、旧分块和异常遗留的 tmp 都不得与新 generation 并存。
                invalidateTrajectoryLightManifestCache(data);
                MatsimCachePaths.recreateVersionDir(data, TRAJECTORY_CACHE_VERSION);
                TrajectoryMeta trajectoryMeta = buildTrajectoryMeta(data);
                ParallelLargeTrajectoryStreamHandler handler = new ParallelLargeTrajectoryStreamHandler(data, trajectoryMeta, progress);
                try {
                    FastEventReader.read(data.getOutfile().getEvents(), handler);
                    return handler.finish();
                } catch (Throwable e) {
                    handler.abort();
                    throw e;
                }
            } finally {
                ACTIVE_TRAJECTORY_BUILDS.decrementAndGet();
            }
        }
    }

    public static Map<String, Object> readReadyTrajectoryManifest(MatsimData data) {
        Path manifestPath = trajectoryManifestPath(data);
        if (!Files.exists(manifestPath)) {
            return null;
        }
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath.toFile(), MAP_TYPE);
            if (!"ready".equals(manifest.get("status"))) {
                return null;
            }
            if (!TRAJECTORY_CACHE_VERSION.equals(manifest.get("cacheVersion"))) {
                return null;
            }
            if (!sameTrajectorySources(data, manifest)) {
                return null;
            }
            return manifest;
        } catch (Exception e) {
            throw new IllegalStateException("轨迹缓存状态读取失败: " + manifestPath, e);
        }
    }

    public static Map<String, Object> readReadyTrajectoryLightManifest(MatsimData data) {
        Path lightPath = trajectoryLightManifestPath(data);
        if (Files.exists(lightPath)) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(lightPath, BasicFileAttributes.class);
                String key = trajectoryLightManifestCacheKey(lightPath);
                CachedTrajectoryLightManifest cached = TRAJECTORY_LIGHT_MANIFESTS.get(key);
                if (cached != null && cached.matches(attributes)
                        && isReadyTrajectoryManifest(data, cached.manifest)) {
                    return cached.manifest;
                }
                Map<String, Object> manifest = Collections.unmodifiableMap(JSON.readValue(lightPath.toFile(), MAP_TYPE));
                if (isReadyTrajectoryManifest(data, manifest) && isCompatibleLightManifest(manifest)) {
                    TRAJECTORY_LIGHT_MANIFESTS.put(
                            key,
                            CachedTrajectoryLightManifest.of(attributes, manifest)
                    );
                    return manifest;
                }
                TRAJECTORY_LIGHT_MANIFESTS.remove(key);
            } catch (Exception e) {
                TRAJECTORY_LIGHT_MANIFESTS.remove(trajectoryLightManifestCacheKey(lightPath));
                log.warn("轻量轨迹缓存状态读取失败: {}", lightPath, e);
            }
        } else {
            TRAJECTORY_LIGHT_MANIFESTS.remove(trajectoryLightManifestCacheKey(lightPath));
        }

        Map<String, Object> manifest = readTrajectoryLightManifestFromFull(data);
        if (manifest == null) {
            return null;
        }
        try {
            writeJsonAtomic(lightPath, manifest);
            cacheTrajectoryLightManifest(lightPath, manifest);
        } catch (Exception e) {
            log.warn("轻量轨迹缓存状态写入失败: {}", lightPath, e);
        }
        return manifest;
    }

    private static String trajectoryLightManifestCacheKey(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static void invalidateTrajectoryLightManifestCache(MatsimData data) {
        TRAJECTORY_LIGHT_MANIFESTS.remove(
                trajectoryLightManifestCacheKey(trajectoryLightManifestPath(data))
        );
    }

    public static boolean isTrajectoryRepairRequired(MatsimData data) {
        return TRAJECTORY_REPAIR_REQUESTS.containsKey(trajectoryRepairKey(data))
                || Files.exists(trajectoryRepairMarkerPath(data));
    }

    private static void markTrajectoryGenerationBroken(
            MatsimData data,
            Map<String, Object> detectedManifest,
            String reason
    ) {
        String detectedGeneration = String.valueOf(
                detectedManifest == null ? "missing" : detectedManifest.getOrDefault("cacheGeneration", "missing")
        );
        String cacheKey = trajectoryCacheKey(data);
        Object lock = BUILD_LOCKS.computeIfAbsent(cacheKey, ignored -> new Object());
        synchronized (lock) {
            Map<String, Object> current = readReadyTrajectoryLightManifest(data);
            if (current == null || !detectedGeneration.equals(String.valueOf(current.get("cacheGeneration")))) {
                return;
            }
            Map<String, Object> marker = new LinkedHashMap<>();
            marker.put("cacheVersion", TRAJECTORY_CACHE_VERSION);
            marker.put("cacheGeneration", detectedGeneration);
            marker.put("detectedAt", System.currentTimeMillis());
            marker.put("reason", reason == null ? "轨迹空间工件损坏" : reason);
            try {
                writeJsonAtomic(trajectoryRepairMarkerPath(data), marker);
            } catch (Exception e) {
                log.warn("轨迹修复标记写入失败: model={}, generation={}", data.getName(), detectedGeneration, e);
            }
            TRAJECTORY_REPAIR_REQUESTS.put(trajectoryRepairKey(data), Boolean.TRUE);
            invalidateTrajectoryLightManifestCache(data);
            log.error("轨迹缓存 generation 已失效并等待单飞重建: model={}, generation={}, reason={}",
                    data.getName(), detectedGeneration, reason);
        }
    }

    private static void cacheTrajectoryLightManifest(Path path, Map<String, Object> manifest) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        Map<String, Object> stable = Collections.unmodifiableMap(manifest);
        TRAJECTORY_LIGHT_MANIFESTS.put(
                trajectoryLightManifestCacheKey(path),
                CachedTrajectoryLightManifest.of(attributes, stable)
        );
    }

    public static Map<String, Object> lightweightTrajectoryManifest(Map<String, Object> manifest) {
        if (manifest == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : manifest.entrySet()) {
            if ("meta".equals(entry.getKey())) {
                result.put("meta", lightweightTrajectoryMeta(entry.getValue()));
            } else {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        result.put("vehicles", List.of());
        result.put("lightweight", true);
        result.put("lightManifestVersion", TRAJECTORY_LIGHT_MANIFEST_VERSION);
        return result;
    }

    private static boolean isReadyTrajectoryManifest(MatsimData data, Map<String, Object> manifest) {
        return manifest != null
                && !Files.exists(trajectoryRepairMarkerPath(data))
                && !TRAJECTORY_REPAIR_REQUESTS.containsKey(trajectoryRepairKey(data))
                && "ready".equals(manifest.get("status"))
                && TRAJECTORY_CACHE_VERSION.equals(manifest.get("cacheVersion"))
                && sameTrajectorySources(data, manifest);
    }

    private static Map<String, Object> readTrajectoryLightManifestFromFull(MatsimData data) {
        Path manifestPath = trajectoryManifestPath(data);
        if (!Files.exists(manifestPath)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(manifestPath);
            JsonParser parser = JSON.getFactory().createParser(in)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("轨迹缓存 manifest 根节点不是对象: " + manifestPath);
            }
            Map<String, Object> manifest = new LinkedHashMap<>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = parser.currentName();
                JsonToken token = parser.nextToken();
                if ("meta".equals(fieldName) && token == JsonToken.START_OBJECT) {
                    manifest.put("meta", readLightTrajectoryMeta(parser));
                } else {
                    manifest.put(fieldName, JSON.readValue(parser, Object.class));
                }
            }
            manifest = lightweightTrajectoryManifest(manifest);
            return isReadyTrajectoryManifest(data, manifest) ? manifest : null;
        } catch (Exception e) {
            throw new IllegalStateException("轨迹缓存轻量状态读取失败: " + manifestPath, e);
        }
    }

    private static Map<String, Object> readLightTrajectoryMeta(JsonParser parser) throws IOException {
        Map<String, Object> meta = new LinkedHashMap<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String fieldName = parser.currentName();
            JsonToken token = parser.nextToken();
                if ("vehicles".equals(fieldName)) {
                parser.skipChildren();
                meta.put("vehicles", List.of());
                meta.put("vehicleDetailsDeferred", true);
            } else if ("routes".equals(fieldName)) {
                parser.skipChildren();
                meta.put("routes", Map.of());
                meta.put("routeDetailsDeferred", true);
            } else {
                meta.put(fieldName, JSON.readValue(parser, Object.class));
            }
        }
        meta.putIfAbsent("vehicles", List.of());
        meta.putIfAbsent("routes", Map.of());
        meta.put("vehicleDetailsDeferred", true);
        meta.put("routeDetailsDeferred", true);
        return meta;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> lightweightTrajectoryMeta(Object metaObject) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (metaObject instanceof Map<?, ?> input) {
            for (Map.Entry<?, ?> entry : input.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (!"vehicles".equals(key) && !"routes".equals(key)) {
                    meta.put(key, entry.getValue());
                }
            }
        }
        meta.put("vehicles", List.of());
        meta.put("routes", Map.of());
        meta.put("vehicleDetailsDeferred", true);
        meta.put("routeDetailsDeferred", true);
        return meta;
    }

    private static boolean isCompatibleLightManifest(Map<String, Object> manifest) {
        Object version = manifest.get("lightManifestVersion");
        if (!(version instanceof Number number) || number.intValue() != TRAJECTORY_LIGHT_MANIFEST_VERSION) {
            return false;
        }
        Object metaObject = manifest.get("meta");
        if (!(metaObject instanceof Map<?, ?> meta)) {
            return false;
        }
        return Boolean.TRUE.equals(meta.get("vehicleDetailsDeferred"))
                && Boolean.TRUE.equals(meta.get("routeDetailsDeferred"));
    }

    private static Map<String, Object> deferredTrajectoryManifest(MatsimData data, String message) {
        Map<String, Object> timeRange = new LinkedHashMap<>();
        timeRange.put("min", 0);
        timeRange.put("max", 86400);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalVehicles", 0);
        summary.put("vehicleCountByMode", emptyLongModeMap());
        summary.put("pointCount", 0);
        summary.put("chunks", List.of());

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("status", "deferred");
        manifest.put("cacheVersion", TRAJECTORY_CACHE_VERSION);
        manifest.put("message", message);
        putTrajectoryIdentity(manifest, data);
        manifest.put("chunkSeconds", TRAJECTORY_PLAYBACK_WINDOW_SECONDS);
        manifest.put("storageChunkSeconds", TRAJECTORY_CHUNK_SECONDS);
        manifest.put("spatial", trajectorySpatialInfo());
        manifest.put("timeRange", timeRange);
        manifest.put("summary", summary);
        manifest.put("passengerSeries", List.of());
        manifest.put("vehicles", List.of());
        return manifest;
    }

    public static Map<String, Object> readTrajectoryChunk(MatsimData data, int start) {
        Map<String, Object> manifest = readReadyTrajectoryLightManifest(data);
        if (manifest == null) {
            return null;
        }

        int chunkStart = normalizeChunkStart(start);
        if (!manifestHasChunk(manifest, chunkStart)) {
            Map<String, Object> result = new LinkedHashMap<>(manifest);
            result.put("vehicles", List.of());
            result.put("chunk", chunkInfo(chunkStart, 0, 0));
            return result;
        }

        Path chunkPath = trajectoryCacheDir(data).resolve(chunkFileName(chunkStart));
        if (!Files.exists(chunkPath)) {
            markTrajectoryGenerationBroken(data, manifest, "轨迹分块缺失: " + chunkPath.getFileName());
            throw new IllegalStateException("轨迹 manifest 声明的分块不存在: " + chunkPath);
        }

        try {
            Map<String, Object> result = readGzipJson(chunkPath);
            result.put("status", "ready");
            result.put("cacheVersion", TRAJECTORY_CACHE_VERSION);
            result.put("timeRange", manifest.get("timeRange"));
            result.put("summary", manifest.get("summary"));
            return result;
        } catch (Exception e) {
            markTrajectoryGenerationBroken(data, manifest, "轨迹分块读取失败: " + e.getMessage());
            throw new IllegalStateException("轨迹分块读取失败: " + chunkPath, e);
        }
    }

    public static byte[] readTrajectoryBinaryChunk(MatsimData data, int start) {
        Map<String, Object> manifest = readReadyTrajectoryLightManifest(data);
        if (manifest == null) {
            return null;
        }

        int chunkStart = normalizeChunkStart(start);
        if (!manifestHasChunk(manifest, chunkStart)) {
            try {
                return createTrajectoryBinaryBytes(
                        chunkStart,
                        chunkStart + TRAJECTORY_CHUNK_SECONDS - 1,
                        0,
                        0,
                        0.0,
                        0.0,
                        List.of()
                );
            } catch (IOException e) {
                throw new IllegalStateException("空轨迹二进制分块生成失败: start=" + chunkStart, e);
            }
        }

        Path indexPath = trajectorySpatialIndexPath(data, chunkStart);
        try {
            SpatialContainerIndex index = readSpatialTrajectoryIndex(indexPath, chunkStart);
            Path chunkPath = index.version == TRAJECTORY_SPATIAL_INDEX_VERSION
                    ? trajectorySpatialContainerPath(data, chunkStart)
                    : trajectoryLegacySpatialContainerPath(data, chunkStart);
            if (!Files.exists(chunkPath)) {
                throw new IOException("轨迹二进制容器缺失: " + chunkPath.getFileName());
            }
            long rawRowsBytes = index.entries.stream().mapToLong(entry ->
                    (long) entry.count * TRAJECTORY_BINARY_STRIDE * Float.BYTES).sum();
            if (TRAJECTORY_BINARY_HEADER_BYTES + rawRowsBytes > TRAJECTORY_LEGACY_FULL_CHUNK_MAX_BYTES) {
                throw new IllegalStateException("轨迹整块超过接口上限，请使用视口分块接口: " + chunkPath);
            }
            if (index.version == TRAJECTORY_LEGACY_SPATIAL_INDEX_VERSION) {
                return Files.readAllBytes(chunkPath);
            }
            ByteArrayOutputStream result = new ByteArrayOutputStream(
                    Math.toIntExact(TRAJECTORY_BINARY_HEADER_BYTES + rawRowsBytes));
            int segmentCount = index.entries.stream().mapToInt(entry -> entry.count).sum();
            writeTrajectoryBinaryHeader(
                    result, chunkStart, chunkStart + TRAJECTORY_CHUNK_SECONDS - 1,
                    segmentCount, 0, segmentCount * 2, index.originX, index.originY);
            TrajectoryDictionaries dictionaries = index.recordStride == TRAJECTORY_COMPACT_STRIDE
                    ? loadTrajectoryDictionaries(data) : null;
            try (FileChannel channel = FileChannel.open(chunkPath, StandardOpenOption.READ)) {
                validateSpatialContainerHeader(channel, chunkStart, index);
                for (SpatialIndexEntry entry : index.entries) {
                    ByteBuffer encoded = ByteBuffer.allocate(entry.compressedBytes);
                    readFully(channel, encoded, entry.compressedOffset);
                    byte[] raw = Zstd.decompress(
                            encoded.array(), entry.count * index.recordStride * Integer.BYTES);
                    if (index.recordStride == TRAJECTORY_COMPACT_STRIDE) {
                        raw = expandCompactTrajectoryRows(
                                dictionaries, raw, entry.count, chunkStart, index.originX, index.originY);
                    }
                    result.write(raw);
                }
            }
            return result.toByteArray();
        } catch (Exception e) {
            markTrajectoryGenerationBroken(data, manifest, "轨迹二进制分块读取失败: " + e.getMessage());
            throw new IllegalStateException("轨迹二进制分块读取失败: " + indexPath, e);
        }
    }

    /** 连续播放的主路径：固定对齐到 10s 视口块，不触碰全市块。 */
    public static byte[] readTrajectoryBinaryViewport(
            MatsimData data,
            int start,
            int windowSeconds,
            String visibilityMode,
            Double requestedMinX,
            Double requestedMinY,
            Double requestedMaxX,
            Double requestedMaxY
    ) {
        // 外部即使传入 7/17 等任意值也必须落到 manifest 声明的固定 10s 协议；
        // 30 可被 10 整除，因而一个响应永远不会跨两个存储容器而漏段。
        int window = TRAJECTORY_PLAYBACK_WINDOW_SECONDS;
        int selectionStart = normalizePlaybackWindowStart(start);
        return readTrajectoryBinarySpatialSelection(
                data,
                selectionStart,
                selectionStart + window,
                visibilityMode,
                requestedMinX,
                requestedMinY,
                requestedMaxX,
                requestedMaxY
        );
    }

    /** 拖动/快进的低延迟路径：复用同一空间索引，额外限定 1–2s 时间桶。 */
    public static byte[] readTrajectoryBinaryFrame(
            MatsimData data,
            int time,
            int bucketSeconds,
            String visibilityMode,
            Double requestedMinX,
            Double requestedMinY,
            Double requestedMaxX,
            Double requestedMaxY
    ) {
        // 快照协议只允许 1s/2s。二者均整除 30s 存储块，不存在跨容器窗口；
        // 大于 2 的任意输入可靠降为 2s，而不是按请求值形成 28..35 之类的漏段窗口。
        int windowSeconds = normalizeFrameBucketSeconds(bucketSeconds);
        int frameStart = Math.floorDiv(Math.max(0, time), windowSeconds) * windowSeconds;
        return readTrajectoryBinarySpatialSelection(
                data,
                frameStart,
                frameStart + windowSeconds,
                visibilityMode,
                requestedMinX,
                requestedMinY,
                requestedMaxX,
                requestedMaxY
        );
    }

    private static byte[] readTrajectoryBinarySpatialSelection(
            MatsimData data,
            int selectionStart,
            int selectionEndExclusive,
            String visibilityMode,
            Double requestedMinX,
            Double requestedMinY,
            Double requestedMaxX,
            Double requestedMaxY
    ) {
        Map<String, Object> manifest = readReadyTrajectoryLightManifest(data);
        if (manifest == null) return null;
        int chunkStart = normalizeChunkStart(selectionStart);
        Map<?, ?> chunk = manifestChunk(manifest, chunkStart);
        if (chunk == null) {
            return emptyTrajectorySelection(selectionStart, selectionEndExclusive);
        }

        boolean hasBounds = requestedMinX != null && requestedMinY != null
                && requestedMaxX != null && requestedMaxY != null
                && Double.isFinite(requestedMinX) && Double.isFinite(requestedMinY)
                && Double.isFinite(requestedMaxX) && Double.isFinite(requestedMaxY)
                && requestedMaxX > requestedMinX && requestedMaxY > requestedMinY;
        Path indexPath = trajectorySpatialIndexPath(data, chunkStart);
        Path compressedContainerPath = trajectorySpatialContainerPath(data, chunkStart);
        Path legacyContainerPath = trajectoryLegacySpatialContainerPath(data, chunkStart);
        if (!Files.exists(indexPath)
                || (!Files.exists(compressedContainerPath) && !Files.exists(legacyContainerPath))) {
            markTrajectoryGenerationBroken(data, manifest,
                    "轨迹空间工件缺失: " + (!Files.exists(indexPath)
                            ? indexPath.getFileName() : compressedContainerPath.getFileName()));
            return null;
        }

        String normalizedVisibility = normalizeTrajectoryVisibility(visibilityMode);
        try {
            Path containerPath = Files.exists(compressedContainerPath)
                    ? compressedContainerPath : legacyContainerPath;
            TrajectorySpatialSelectionKey cacheKey = new TrajectorySpatialSelectionKey(
                    trajectoryRepairKey(data),
                    String.valueOf(manifest.getOrDefault("cacheGeneration", "missing")),
                    chunkStart,
                    normalizedVisibility,
                    hasBounds,
                    hasBounds ? normalizedDoubleBits(requestedMinX) : 0L,
                    hasBounds ? normalizedDoubleBits(requestedMinY) : 0L,
                    hasBounds ? normalizedDoubleBits(requestedMaxX) : 0L,
                    hasBounds ? normalizedDoubleBits(requestedMaxY) : 0L,
                    trajectoryArtifactFingerprint(indexPath),
                    trajectoryArtifactFingerprint(containerPath)
            );
            CachedTrajectorySpatialSelection spatialSelection = trajectorySpatialSelection(
                    cacheKey,
                    data,
                    chunkStart,
                    normalizedVisibility,
                    hasBounds,
                    requestedMinX,
                    requestedMinY,
                    requestedMaxX,
                    requestedMaxY,
                    indexPath,
                    containerPath
            );
            return sliceTrajectorySpatialSelection(
                    spatialSelection, selectionStart, selectionEndExclusive);
        } catch (Exception e) {
            markTrajectoryGenerationBroken(data, manifest, "轨迹空间工件校验失败: " + e.getMessage());
            throw new IllegalStateException("轨迹空间块读取失败: model=" + data.getName()
                    + ", chunk=" + chunkStart + ", bounds=[" + requestedMinX + "," + requestedMinY
                    + "," + requestedMaxX + "," + requestedMaxY + "]", e);
        }
    }

    private static CachedTrajectorySpatialSelection trajectorySpatialSelection(
            TrajectorySpatialSelectionKey cacheKey,
            MatsimData data,
            int chunkStart,
            String normalizedVisibility,
            boolean hasBounds,
            Double requestedMinX,
            Double requestedMinY,
            Double requestedMaxX,
            Double requestedMaxY,
            Path indexPath,
            Path containerPath
    ) throws IOException {
        CachedTrajectorySpatialSelection cached = TRAJECTORY_SPATIAL_SELECTIONS.get(cacheKey);
        if (cached != null) return cached;

        CompletableFuture<CachedTrajectorySpatialSelection> loading = new CompletableFuture<>();
        CompletableFuture<CachedTrajectorySpatialSelection> existing =
                TRAJECTORY_SPATIAL_SELECTION_LOADS.putIfAbsent(cacheKey, loading);
        if (existing != null) return awaitTrajectorySpatialSelection(existing);

        try {
            TRAJECTORY_SPATIAL_SELECTION_LOAD_COUNT.incrementAndGet();
            CachedTrajectorySpatialSelection loaded = loadTrajectorySpatialSelection(
                    data,
                    chunkStart,
                    normalizedVisibility,
                    hasBounds,
                    requestedMinX,
                    requestedMinY,
                    requestedMaxX,
                    requestedMaxY,
                    indexPath,
                    containerPath
            );
            TRAJECTORY_SPATIAL_SELECTIONS.put(cacheKey, loaded);
            loading.complete(loaded);
            return loaded;
        } catch (Throwable error) {
            loading.completeExceptionally(error);
            return rethrowTrajectorySpatialSelectionFailure(error);
        } finally {
            TRAJECTORY_SPATIAL_SELECTION_LOADS.remove(cacheKey, loading);
        }
    }

    private static CachedTrajectorySpatialSelection loadTrajectorySpatialSelection(
            MatsimData data,
            int chunkStart,
            String normalizedVisibility,
            boolean hasBounds,
            Double requestedMinX,
            Double requestedMinY,
            Double requestedMaxX,
            Double requestedMaxY,
            Path indexPath,
            Path containerPath
    ) throws IOException {
        SpatialContainerIndex spatialIndex = readSpatialTrajectoryIndex(indexPath, chunkStart);
        List<SpatialIndexEntry> candidates = spatialCandidates(
                spatialIndex,
                hasBounds,
                hasBounds ? requestedMinX : 0.0,
                hasBounds ? requestedMinY : 0.0,
                hasBounds ? requestedMaxX : 0.0,
                hasBounds ? requestedMaxY : 0.0
        );
        TrajectoryDictionaries dictionaries = spatialIndex.recordStride == TRAJECTORY_COMPACT_STRIDE
                ? loadTrajectoryDictionaries(data) : null;
        TrajectorySpatialSelectionAccumulator accumulator =
                new TrajectorySpatialSelectionAccumulator(
                        chunkStart, spatialIndex.originX, spatialIndex.originY);

        try (FileChannel channel = FileChannel.open(containerPath, StandardOpenOption.READ)) {
            validateSpatialContainerHeader(channel, chunkStart, spatialIndex);
            int rowsPerBatch = Math.max(1, (1024 * 1024) / (TRAJECTORY_BINARY_STRIDE * Float.BYTES));
            ByteBuffer tileRows = ByteBuffer.allocate(
                    rowsPerBatch * TRAJECTORY_BINARY_STRIDE * Float.BYTES
            ).order(ByteOrder.LITTLE_ENDIAN);
            for (SpatialIndexEntry entry : candidates) {
                if (spatialIndex.version == TRAJECTORY_SPATIAL_INDEX_VERSION) {
                    ByteBuffer encoded = ByteBuffer.allocate(entry.compressedBytes);
                    readFully(channel, encoded, entry.compressedOffset);
                    byte[] raw = Zstd.decompress(
                            encoded.array(), entry.count * spatialIndex.recordStride * Integer.BYTES);
                    if (spatialIndex.recordStride == TRAJECTORY_COMPACT_STRIDE) {
                        raw = expandCompactTrajectoryRows(
                                dictionaries, raw, entry.count, chunkStart,
                                spatialIndex.originX, spatialIndex.originY);
                    }
                    accumulator.append(
                            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN), entry.count,
                            normalizedVisibility,
                            hasBounds, requestedMinX, requestedMinY, requestedMaxX, requestedMaxY,
                            spatialIndex.originX, spatialIndex.originY);
                    continue;
                }
                long byteOffset = TRAJECTORY_BINARY_HEADER_BYTES
                        + (long) entry.offset * TRAJECTORY_BINARY_STRIDE * Float.BYTES;
                int remaining = entry.count;
                while (remaining > 0) {
                    int batchRows = Math.min(rowsPerBatch, remaining);
                    tileRows.clear();
                    tileRows.limit(batchRows * TRAJECTORY_BINARY_STRIDE * Float.BYTES);
                    readFully(channel, tileRows, byteOffset);
                    tileRows.flip();
                    accumulator.append(
                            tileRows, batchRows,
                            normalizedVisibility, hasBounds,
                            requestedMinX, requestedMinY, requestedMaxX, requestedMaxY,
                            spatialIndex.originX, spatialIndex.originY);
                    int batchBytes = batchRows * TRAJECTORY_BINARY_STRIDE * Float.BYTES;
                    byteOffset += batchBytes;
                    remaining -= batchRows;
                }
            }
        }
        return accumulator.finish();
    }

    private static byte[] sliceTrajectorySpatialSelection(
            CachedTrajectorySpatialSelection spatialSelection,
            int selectionStart,
            int selectionEndExclusive
    ) throws IOException {
        int duration = selectionEndExclusive - selectionStart;
        int relativeStart = selectionStart - spatialSelection.chunkStart;
        int playbackIndex = Math.floorDiv(relativeStart, TRAJECTORY_PLAYBACK_WINDOW_SECONDS);
        if (duration == TRAJECTORY_PLAYBACK_WINDOW_SECONDS
                && relativeStart >= 0
                && relativeStart % TRAJECTORY_PLAYBACK_WINDOW_SECONDS == 0
                && playbackIndex >= 0
                && playbackIndex < spatialSelection.playbackWindows.length) {
            return spatialSelection.playbackWindows[playbackIndex];
        }
        if (playbackIndex < 0 || playbackIndex >= spatialSelection.playbackWindows.length) {
            return emptyTrajectorySelection(selectionStart, selectionEndExclusive);
        }

        byte[] playbackWindow = spatialSelection.playbackWindows[playbackIndex];
        IntOpenHashSet vehicles = new IntOpenHashSet();
        ByteArrayOutputStream selectedRows = new ByteArrayOutputStream(
                Math.min(playbackWindow.length - TRAJECTORY_BINARY_HEADER_BYTES, 1024 * 1024));
        int selectedCount = appendSelectedTrajectoryRows(
                ByteBuffer.wrap(
                        playbackWindow,
                        TRAJECTORY_BINARY_HEADER_BYTES,
                        playbackWindow.length - TRAJECTORY_BINARY_HEADER_BYTES
                ).order(ByteOrder.LITTLE_ENDIAN),
                spatialSelection.playbackRowCounts[playbackIndex],
                selectionStart,
                selectionEndExclusive,
                "all",
                false,
                null,
                null,
                null,
                null,
                spatialSelection.originX,
                spatialSelection.originY,
                selectedRows,
                vehicles
        );
        ByteArrayOutputStream result = new ByteArrayOutputStream(
                TRAJECTORY_BINARY_HEADER_BYTES + selectedRows.size());
        writeTrajectoryBinaryHeader(
                result,
                selectionStart,
                selectionEndExclusive - 1,
                selectedCount,
                vehicles.size(),
                selectedCount * 2,
                spatialSelection.originX,
                spatialSelection.originY,
                selectionEndExclusive - selectionStart
        );
        selectedRows.writeTo(result);
        return result.toByteArray();
    }

    private static CachedTrajectorySpatialSelection awaitTrajectorySpatialSelection(
            CompletableFuture<CachedTrajectorySpatialSelection> loading
    ) throws IOException {
        try {
            return loading.join();
        } catch (CompletionException error) {
            return rethrowTrajectorySpatialSelectionFailure(error.getCause());
        }
    }

    private static CachedTrajectorySpatialSelection rethrowTrajectorySpatialSelectionFailure(
            Throwable error
    ) throws IOException {
        if (error instanceof IOException io) throw io;
        if (error instanceof RuntimeException runtime) throw runtime;
        if (error instanceof Error fatal) throw fatal;
        throw new IOException("轨迹空间块加载失败", error);
    }

    private static long normalizedDoubleBits(double value) {
        return Double.doubleToLongBits(value == 0.0 ? 0.0 : value);
    }

    private static TrajectoryArtifactFingerprint trajectoryArtifactFingerprint(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        return new TrajectoryArtifactFingerprint(
                path.toAbsolutePath().normalize().toString(),
                attributes.size(),
                attributes.lastModifiedTime().toMillis(),
                String.valueOf(attributes.fileKey())
        );
    }

    static long trajectorySpatialSelectionLoadCount() {
        return TRAJECTORY_SPATIAL_SELECTION_LOAD_COUNT.get();
    }

    private static int appendSelectedTrajectoryRows(
            ByteBuffer rows,
            int rowCount,
            int selectionStart,
            int selectionEndExclusive,
            String normalizedVisibility,
            boolean hasBounds,
            Double requestedMinX,
            Double requestedMinY,
            Double requestedMaxX,
            Double requestedMaxY,
            double originX,
            double originY,
            ByteArrayOutputStream selectedRows,
            IntOpenHashSet vehicles
    ) throws IOException {
        byte[] row = new byte[TRAJECTORY_BINARY_STRIDE * Float.BYTES];
        ByteBuffer record = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN);
        int selected = 0;
        for (int index = 0; index < rowCount; index++) {
            rows.get(row);
            record.clear();
            float startTime = record.getFloat();
            float endTime = record.getFloat();
            float startX = record.getFloat();
            float startY = record.getFloat();
            float endX = record.getFloat();
            float endY = record.getFloat();
            int modeCode = Math.round(record.getFloat());
            int vehicleIndex = record.getInt();
            record.getFloat();
            if (!(startTime < selectionEndExclusive && endTime > selectionStart)) continue;
            if ("public".equals(normalizedVisibility) && modeCode == 2) continue;
            if ("private".equals(normalizedVisibility) && modeCode != 2) continue;
            if (hasBounds) {
                double segmentMinX = originX + Math.min(startX, endX);
                double segmentMaxX = originX + Math.max(startX, endX);
                double segmentMinY = originY + Math.min(startY, endY);
                double segmentMaxY = originY + Math.max(startY, endY);
                if (segmentMaxX < requestedMinX || segmentMinX > requestedMaxX
                        || segmentMaxY < requestedMinY || segmentMinY > requestedMaxY) continue;
            }
            selectedRows.write(row);
            selected++;
            vehicles.add(vehicleIndex);
        }
        return selected;
    }

    private static byte[] expandCompactTrajectoryRows(
            TrajectoryDictionaries dictionaries,
            byte[] compactRows,
            int rowCount,
            int chunkStart,
            double responseOriginX,
            double responseOriginY
    ) throws IOException {
        int expectedBytes = Math.multiplyExact(rowCount, TRAJECTORY_COMPACT_STRIDE * Integer.BYTES);
        if (compactRows.length != expectedBytes) {
            throw new IOException("紧凑轨迹块解压长度不一致");
        }
        if (dictionaries == null) throw new IOException("紧凑轨迹块缺少字典");
        ByteBuffer input = ByteBuffer.wrap(compactRows).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer output = ByteBuffer.allocate(
                Math.multiplyExact(rowCount, TRAJECTORY_BINARY_STRIDE * Float.BYTES)
        ).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < rowCount; i++) {
            int originalStart = input.getInt();
            int originalEnd = input.getInt();
            int vehicleIndex = input.getInt();
            int linkIndex = input.getInt();
            VehicleSegment segment = dictionaries.compactSegment(
                    linkIndex, originalStart, originalEnd, chunkStart);
            int modeCode = dictionaries.mode(vehicleIndex);
            if (segment == null || modeCode < 0) {
                throw new IOException("紧凑轨迹记录引用了无效字典项");
            }
            output.putFloat(segment.startTime);
            output.putFloat(segment.endTime);
            output.putFloat((float) (segment.fromX - responseOriginX));
            output.putFloat((float) (segment.fromY - responseOriginY));
            output.putFloat((float) (segment.toX - responseOriginX));
            output.putFloat((float) (segment.toY - responseOriginY));
            output.putFloat(modeCode);
            output.putInt(vehicleIndex);
            output.putFloat((float) segment.distance);
        }
        return output.array();
    }

    private static VehicleSegment compactSegment(
            MatsimLinkGeometryIndex dictionary,
            int linkIndex,
            int originalStart,
            int originalEnd,
            int chunkStart
    ) {
        if (linkIndex < 0 || linkIndex >= dictionary.size() || originalEnd <= originalStart) return null;
        return new VehicleSegment(
                originalStart,
                originalEnd,
                roundCoord(dictionary.fromX(linkIndex)),
                roundCoord(dictionary.fromY(linkIndex)),
                roundCoord(dictionary.toX(linkIndex)),
                roundCoord(dictionary.toY(linkIndex)),
                dictionary.lengthMeters(linkIndex)
        ).clip(chunkStart, chunkStart + TRAJECTORY_CHUNK_SECONDS);
    }

    private static List<SpatialIndexEntry> spatialCandidates(
            SpatialContainerIndex index,
            boolean hasBounds,
            double minX,
            double minY,
            double maxX,
            double maxY
    ) {
        if (!hasBounds) return index.entries;
        List<SpatialIndexEntry> candidates = new ArrayList<>();
        for (SpatialIndexEntry entry : index.entries) {
            double entryMinX = index.originX + entry.minX;
            double entryMinY = index.originY + entry.minY;
            double entryMaxX = index.originX + entry.maxX;
            double entryMaxY = index.originY + entry.maxY;
            if (entryMaxX >= minX && entryMinX <= maxX
                    && entryMaxY >= minY && entryMinY <= maxY) {
                candidates.add(entry);
            }
        }
        return candidates;
    }

    static int trajectorySpatialCandidateCount(
            MatsimData data,
            int start,
            double minX,
            double minY,
            double maxX,
            double maxY
    ) throws IOException {
        int chunkStart = normalizeChunkStart(start);
        SpatialContainerIndex index = readSpatialTrajectoryIndex(
                trajectorySpatialIndexPath(data, chunkStart), chunkStart
        );
        return spatialCandidates(index, true, minX, minY, maxX, maxY).size();
    }

    private static byte[] emptyTrajectorySelection(int start, int endExclusive) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(TRAJECTORY_BINARY_HEADER_BYTES);
            writeTrajectoryBinaryHeader(
                    out, start, endExclusive - 1, 0, 0, 0, 0.0, 0.0, endExclusive - start
            );
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("构造空轨迹二进制响应失败", e);
        }
    }

    private static int spatialTileCoordinate(double coordinate) {
        return (int) Math.floor(coordinate / TRAJECTORY_SPATIAL_TILE_METERS);
    }

    private static int normalizePlaybackWindowStart(int start) {
        return Math.floorDiv(Math.max(0, start), TRAJECTORY_PLAYBACK_WINDOW_SECONDS)
                * TRAJECTORY_PLAYBACK_WINDOW_SECONDS;
    }

    private static int normalizeFrameBucketSeconds(int requested) {
        return requested <= 1 ? 1 : 2;
    }

    private static String normalizeTrajectoryVisibility(String visibilityMode) {
        if (visibilityMode == null || visibilityMode.isBlank()) return "all";
        String normalized = visibilityMode.toLowerCase(Locale.ROOT);
        if ("all".equals(normalized) || "public".equals(normalized) || "private".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("未知轨迹可见性模式: " + visibilityMode);
    }

    private static SpatialContainerIndex readSpatialTrajectoryIndex(Path path, int expectedChunkStart) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length < TRAJECTORY_SPATIAL_INDEX_HEADER_BYTES) {
            throw new EOFException("轨迹空间索引头部不完整");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (byte expected : TRAJECTORY_SPATIAL_INDEX_MAGIC) {
            if (buffer.get() != expected) throw new IOException("轨迹空间索引 magic 不匹配");
        }
        int version = Short.toUnsignedInt(buffer.getShort());
        int headerBytes = Short.toUnsignedInt(buffer.getShort());
        int chunkStart = buffer.getInt();
        int tileSize = buffer.getInt();
        int tileCount = buffer.getInt();
        int stride = buffer.getInt();
        int totalSegments = buffer.getInt();
        buffer.getInt();
        double originX = buffer.getDouble();
        double originY = buffer.getDouble();
        buffer.getDouble(); // v1 全局 maxSegmentSpan 保留在 header 占位；v2 查询只使用逐 tile envelope。
        int entryBytes = buffer.getInt();
        buffer.getInt();
        long expectedBytes = (long) headerBytes + (long) tileCount * entryBytes;
        boolean legacy = version == TRAJECTORY_LEGACY_SPATIAL_INDEX_VERSION;
        boolean compressed = version == TRAJECTORY_SPATIAL_INDEX_VERSION;
        if ((!legacy && !compressed)
                || headerBytes != TRAJECTORY_SPATIAL_INDEX_HEADER_BYTES
                || chunkStart != normalizeChunkStart(expectedChunkStart)
                || tileSize != TRAJECTORY_SPATIAL_TILE_METERS
                || (legacy && stride != TRAJECTORY_BINARY_STRIDE)
                || (compressed && stride != TRAJECTORY_BINARY_STRIDE
                    && stride != TRAJECTORY_COMPACT_STRIDE)
                || entryBytes != (legacy
                    ? TRAJECTORY_LEGACY_SPATIAL_INDEX_ENTRY_BYTES
                    : TRAJECTORY_SPATIAL_INDEX_ENTRY_BYTES)
                || tileCount < 0 || totalSegments < 0 || expectedBytes != bytes.length) {
            throw new IOException("轨迹空间索引格式不兼容");
        }
        List<SpatialIndexEntry> entries = new ArrayList<>(tileCount);
        int covered = 0;
        long expectedCompressedOffset = TRAJECTORY_BINARY_HEADER_BYTES;
        for (int i = 0; i < tileCount; i++) {
            SpatialTileKey key = new SpatialTileKey(buffer.getInt(), buffer.getInt());
            SpatialIndexEntry entry;
            if (legacy) {
                entry = new SpatialIndexEntry(
                        key, buffer.getInt(), buffer.getInt(),
                        buffer.getFloat(), buffer.getFloat(), buffer.getFloat(), buffer.getFloat());
            } else {
                entry = new SpatialIndexEntry(
                        key, buffer.getLong(), buffer.getInt(), buffer.getInt(),
                        buffer.getFloat(), buffer.getFloat(), buffer.getFloat(), buffer.getFloat());
            }
            boolean invalidLocation = legacy
                    ? entry.offset != covered
                    : entry.compressedOffset != expectedCompressedOffset || entry.compressedBytes <= 0;
            if (invalidLocation || entry.count <= 0
                    || !Float.isFinite(entry.minX) || !Float.isFinite(entry.minY)
                    || !Float.isFinite(entry.maxX) || !Float.isFinite(entry.maxY)
                    || entry.maxX < entry.minX || entry.maxY < entry.minY) {
                throw new IOException("轨迹空间索引偏移不连续");
            }
            covered += entry.count;
            if (!legacy) expectedCompressedOffset += entry.compressedBytes;
            entries.add(entry);
        }
        if (covered != totalSegments) throw new IOException("轨迹空间索引记录数不一致");
        return new SpatialContainerIndex(version, stride, originX, originY, entries);
    }

    private static void validateSpatialContainerHeader(
            FileChannel channel,
            int expectedChunkStart,
            SpatialContainerIndex index
    ) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(TRAJECTORY_BINARY_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        readFully(channel, header, 0L);
        header.flip();
        byte[] expectedMagic = index.version == TRAJECTORY_SPATIAL_INDEX_VERSION
                ? TRAJECTORY_ZSTD_MAGIC : TRAJECTORY_BINARY_MAGIC;
        for (byte expected : expectedMagic) {
            if (header.get() != expected) throw new IOException("轨迹空间容器 magic 不匹配");
        }
        int version = Short.toUnsignedInt(header.getShort());
        int headerBytes = Short.toUnsignedInt(header.getShort());
        int chunkStart = header.getInt();
        header.getInt();
        int segmentCount = header.getInt();
        header.getInt();
        header.getInt();
        int stride = header.getInt();
        double originX = header.getDouble();
        double originY = header.getDouble();
        int indexedSegments = index.entries.stream().mapToInt(entry -> entry.count).sum();
        long expectedSize;
        if (index.version == TRAJECTORY_SPATIAL_INDEX_VERSION) {
            SpatialIndexEntry last = index.entries.isEmpty() ? null : index.entries.getLast();
            expectedSize = last == null ? TRAJECTORY_BINARY_HEADER_BYTES
                    : last.compressedOffset + last.compressedBytes;
        } else {
            expectedSize = TRAJECTORY_BINARY_HEADER_BYTES
                    + (long) segmentCount * TRAJECTORY_BINARY_STRIDE * Float.BYTES;
        }
        if (version != TRAJECTORY_BINARY_VERSION
                || headerBytes != TRAJECTORY_BINARY_HEADER_BYTES
                || chunkStart != normalizeChunkStart(expectedChunkStart)
                || stride != index.recordStride
                || segmentCount != indexedSegments
                || Double.compare(originX, index.originX) != 0
                || Double.compare(originY, index.originY) != 0
                || channel.size() != expectedSize) {
            throw new IOException("轨迹空间容器与索引不一致");
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer target, long position) throws IOException {
        while (target.hasRemaining()) {
            int read = channel.read(target, position);
            if (read < 0) throw new EOFException("轨迹空间容器记录不完整");
            if (read == 0) continue;
            position += read;
        }
    }

    public static Path trajectoryBinaryChunkPath(MatsimData data, int start) {
        Map<String, Object> manifest = readReadyTrajectoryLightManifest(data);
        if (manifest == null) {
            return null;
        }

        int chunkStart = normalizeChunkStart(start);
        if (!manifestHasChunk(manifest, chunkStart)) {
            return null;
        }

        try {
            SpatialContainerIndex index = readSpatialTrajectoryIndex(
                    trajectorySpatialIndexPath(data, chunkStart), chunkStart);
            // v15 空间容器是内部 Zstd 工件，不能直接 sendfile 给仍使用 GJTB 的前端。
            if (index.version == TRAJECTORY_SPATIAL_INDEX_VERSION) return null;
            Path chunkPath = trajectoryLegacySpatialContainerPath(data, chunkStart);
            return Files.exists(chunkPath) && Files.size(chunkPath) <= TRAJECTORY_LEGACY_FULL_CHUNK_MAX_BYTES
                    ? chunkPath : null;
        } catch (IOException e) {
            throw new IllegalStateException("读取轨迹分块工件失败: "
                    + trajectorySpatialIndexPath(data, chunkStart), e);
        }
    }

    public static Map<String, Object> chunkInfo(int start, int vehicleCount, int pointCount) {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("start", normalizeChunkStart(start));
        chunk.put("end", normalizeChunkStart(start) + TRAJECTORY_CHUNK_SECONDS - 1);
        chunk.put("vehicleCount", vehicleCount);
        chunk.put("pointCount", pointCount);
        chunk.put("file", chunkFileName(start));
        chunk.put("binaryLayout", "spatial-grid");
        chunk.put("tileSizeMeters", TRAJECTORY_SPATIAL_TILE_METERS);
        return chunk;
    }

    private static Map<String, Object> trajectorySpatialInfo() {
        Map<String, Object> spatial = new LinkedHashMap<>();
        spatial.put("layout", "indexed-zstd-spatial-blocks-v3");
        spatial.put("indexVersion", TRAJECTORY_SPATIAL_INDEX_VERSION);
        spatial.put("indexEntryBytes", TRAJECTORY_SPATIAL_INDEX_ENTRY_BYTES);
        spatial.put("tileSizeMeters", TRAJECTORY_SPATIAL_TILE_METERS);
        spatial.put("playbackWindowSeconds", TRAJECTORY_PLAYBACK_WINDOW_SECONDS);
        spatial.put("storageChunkSeconds", TRAJECTORY_CHUNK_SECONDS);
        spatial.put("crs", "EPSG:3857");
        spatial.put("endpoint", "/pt/data/trajectory/viewport.bin");
        spatial.put("assignment", "segment-midpoint");
        spatial.put("exactBoundsFilter", true);
        spatial.put("fullCityChunk", false);
        spatial.put("compression", "zstd");
        spatial.put("independentBlocks", true);
        spatial.put("filesPerStorageChunk", 2);
        return spatial;
    }

    public static int normalizeChunkStart(int start) {
        return Math.floorDiv(Math.max(0, start), TRAJECTORY_CHUNK_SECONDS) * TRAJECTORY_CHUNK_SECONDS;
    }

    public static String trajectoryCacheKey(MatsimData data) {
        return data.getName() + "::" + trajectorySources(data) + "::" + TRAJECTORY_CACHE_VERSION;
    }

    /**
     * 轨迹二进制分块的强校验 ETag（已带引号）。就绪缓存使用发布 generation，
     * 因此即使同一批源文件被手动重建，浏览器/SW 也不会复用上一代分块。
     */
    public static String trajectoryChunkETag(MatsimData data, int start) {
        Map<String, Object> manifest = readReadyTrajectoryLightManifest(data);
        String generation = manifest == null ? Integer.toHexString(trajectoryCacheKey(data).hashCode())
                : String.valueOf(manifest.getOrDefault("cacheGeneration", "missing"));
        return "\"traj-"
                + generation
                + "-" + normalizeChunkStart(start)
                + "\"";
    }

    public static String trajectoryViewportETag(
            MatsimData data,
            int start,
            int windowSeconds,
            String visibilityMode,
            Double minX,
            Double minY,
            Double maxX,
            Double maxY
    ) {
        Map<String, Object> manifest = readReadyTrajectoryLightManifest(data);
        String generation = manifest == null ? Integer.toHexString(trajectoryCacheKey(data).hashCode())
                : String.valueOf(manifest.getOrDefault("cacheGeneration", "missing"));
        String identity = generation + ":" + normalizePlaybackWindowStart(start)
                + ":" + TRAJECTORY_PLAYBACK_WINDOW_SECONDS + ":"
                + normalizeTrajectoryVisibility(visibilityMode) + ":"
                + String.valueOf(minX) + ":" + String.valueOf(minY) + ":"
                + String.valueOf(maxX) + ":" + String.valueOf(maxY);
        return "\"traj-view-" + Integer.toUnsignedString(identity.hashCode(), 16) + "\"";
    }

    public static String trajectoryFrameETag(
            MatsimData data,
            int time,
            int bucketSeconds,
            String visibilityMode,
            Double minX,
            Double minY,
            Double maxX,
            Double maxY
    ) {
        Map<String, Object> manifest = readReadyTrajectoryLightManifest(data);
        String generation = manifest == null ? Integer.toHexString(trajectoryCacheKey(data).hashCode())
                : String.valueOf(manifest.getOrDefault("cacheGeneration", "missing"));
        int window = normalizeFrameBucketSeconds(bucketSeconds);
        int frameStart = Math.floorDiv(Math.max(0, time), window) * window;
        String identity = generation + ":" + frameStart + ":" + window + ":"
                + normalizeTrajectoryVisibility(visibilityMode) + ":"
                + String.valueOf(minX) + ":" + String.valueOf(minY) + ":"
                + String.valueOf(maxX) + ":" + String.valueOf(maxY);
        return "\"traj-frame-" + Integer.toUnsignedString(identity.hashCode(), 16) + "\"";
    }

    /**
     * 磁盘轻量缓存已就绪时把 personTracks 装入内存（不解析 events，缓存缺失直接返回 false）。
     * 供模型加载路径使用：磁盘缓存齐全时 ModelCacheManager 不会再跑 buildCaches，
     * 内存态 personTracks 需要在这里补齐，否则依赖它的接口（如体检评估）会一直"生成中"。
     */
    public static boolean preloadPersonTracksIfReady(MatsimData data) {
        if (data.getPersonTracks() != null && !data.getPersonTracks().isEmpty()) {
            long limit = maxMaterializedPersonTracks();
            if (data.getPersonTracks().size() <= limit && personTracksFitHeapBudget(data)) {
                return true;
            }
            int count = data.getPersonTracks().size();
            data.setPersonTracks(new it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<>());
            log.warn("乘客明细超过堆预算，释放内存副本并切换磁盘态: model={}, tracks={}, limit={}",
                    data.getName(), count, limit);
        }
        if (!personTracksFitHeapBudget(data)) {
            log.info("乘客明细保持磁盘态: model={}, tracks={}, maxHeap={}MB",
                    data.getName(), personTrackCount(data), Runtime.getRuntime().maxMemory() / 1024 / 1024);
            return false;
        }
        return loadPersonTracksFromCache(data);
    }

    /** 磁盘乘客明细是否与当前 events 指纹一致，可用于不加载模型的缓存就绪探测。 */
    public static boolean isPersonTrackStoreReady(MatsimData data) {
        return isPersonTracksCacheReady(data) || MatsimPersonTrackStore.isPartitionStoreReady(data);
    }

    static boolean isPersonTrackSourceReady(MatsimData data) {
        return isPersonTracksCacheReady(data);
    }

    /** manifest 中的记录数；未知返回 -1。该方法只读几 KB JSON，不触碰 300MB+ gzip。 */
    public static long personTrackCount(MatsimData data) {
        if (!isPersonTracksCacheReady(data)) return MatsimPersonTrackStore.trackCount(data);
        try {
            Map<String, Object> manifest = JSON.readValue(personTrackManifestPath(data).toFile(), MAP_TYPE);
            Object value = manifest.get("trackCount");
            return value instanceof Number number ? number.longValue() : -1L;
        } catch (Exception e) {
            throw new IllegalStateException("读取乘客明细数量失败: "
                    + personTrackManifestPath(data), e);
        }
    }

    private static boolean personTracksFitHeapBudget(MatsimData data) {
        long count = personTrackCount(data);
        if (count < 0) return false;
        long configuredMax = maxMaterializedPersonTracks();
        // 一个 PTPersonTrack 连同 6 个 ID 包装、String/HashSet 桶的实测量级远高于 TSV；
        // 用 384B/条做保守门槛，并且最多占 max heap 的 25%。
        long estimatedBytes = saturatedMultiply(count, 384L);
        long heapBudget = Math.max(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4);
        return count <= Math.max(0L, configuredMax) && estimatedBytes <= heapBudget;
    }

    static long maxMaterializedPersonTracks() {
        return Math.max(0L, Long.getLong("gjcxfzksh.person-tracks.max-materialized", 1_500_000L));
    }

    private static long saturatedMultiply(long left, long right) {
        if (left <= 0 || right <= 0) return 0L;
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    /** 仅供按需读取路径在计算完成后释放明细，缓存生成主流程不调用。 */
    public static void releaseOnDemandPersonTracks(MatsimData data) {
        if (data == null || data.getPersonTracks() == null || data.getPersonTracks().isEmpty()) {
            return;
        }
        int count = data.getPersonTracks().size();
        data.setPersonTracks(new it.unimi.dsi.fastutil.objects.ObjectOpenHashSet<>());
        log.info("释放按需装载的乘客明细: model={}, tracks={}", data.getName(), count);
    }

    private static boolean loadPersonTracksFromCache(MatsimData data) {
        if (!isPersonTracksCacheReady(data)) {
            return false;
        }
        Path tracksPath = personTracksPath(data);
        try {
            Set<PTPersonTrack> tracks = readPersonTracks(tracksPath);
            data.setPersonTracks(tracks);
            log.info("读取乘客上下车轻量缓存: model={}, tracks={}", data.getName(), tracks.size());
            return true;
        } catch (Exception e) {
            throw new IllegalStateException("乘客上下车缓存读取失败: " + tracksPath, e);
        }
    }

    private static boolean isPersonTracksCacheReady(MatsimData data) {
        Path manifestPath = personTrackManifestPath(data);
        Path tracksPath = personTracksPath(data);
        if (!Files.exists(manifestPath) || !Files.exists(tracksPath)) {
            return false;
        }
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath.toFile(), MAP_TYPE);
            if (!"ready".equals(manifest.get("status"))) {
                return false;
            }
            if (!PERSON_TRACK_CACHE_VERSION.equals(manifest.get("cacheVersion"))) {
                return false;
            }
            if (!sameEvents(data, manifest)) {
                return false;
            }
            return true;
        } catch (Exception e) {
            throw new IllegalStateException("乘客上下车缓存状态读取失败: " + manifestPath, e);
        }
    }

    private static void writePersonTracksCache(MatsimData data, Collection<PTPersonTrack> tracks) throws Exception {
        Files.createDirectories(personTrackCacheDir(data));
        Path tracksPath = personTracksPath(data);
        Path tmpPath = tracksPath.resolveSibling(PERSON_TRACK_FILE + ".tmp");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmpPath))) {
            writePersonTracks(out, tracks);
        }
        try {
            Files.move(tmpPath, tracksPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tmpPath, tracksPath, StandardCopyOption.REPLACE_EXISTING);
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("status", "ready");
        manifest.put("cacheVersion", PERSON_TRACK_CACHE_VERSION);
        manifest.put("generatedAt", System.currentTimeMillis());
        manifest.put("eventsFile", data.getOutfile().getEvents());
        manifest.put("eventsModified", lastModified(data.getOutfile().getEvents()));
        manifest.put("eventsSize", fileSize(data.getOutfile().getEvents()));
        manifest.put("eventsSignature", MatsimSourceFingerprint.signature(data.getOutfile().getEvents()));
        manifest.put("trackCount", tracks.size());
        writeJsonAtomic(personTrackManifestPath(data), manifest);
        MatsimCachePaths.deleteOtherVersions(data, "pt-events-v", PERSON_TRACK_CACHE_VERSION);
    }

    private static void writePersonTracks(OutputStream out, Collection<PTPersonTrack> tracks) throws Exception {
        try (BufferedWriter writer = new BufferedWriter(new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            writer.write("time\tenter\tpersonId\tlineId\trouteId\tvehicleId\tdepartureId\tfacilityId");
            writer.newLine();
            for (PTPersonTrack track : tracks) {
                writer.write(String.valueOf(track.getTime() == null ? 0.0 : track.getTime()));
                writer.write('\t');
                writer.write(String.valueOf(Boolean.TRUE.equals(track.getEnter())));
                writer.write('\t');
                writer.write(tsv(track.getPersonId()));
                writer.write('\t');
                writer.write(tsv(track.getLineId()));
                writer.write('\t');
                writer.write(tsv(track.getRouteId()));
                writer.write('\t');
                writer.write(tsv(track.getVehicleId()));
                writer.write('\t');
                writer.write(tsv(track.getDepartureId()));
                writer.write('\t');
                writer.write(tsv(track.getFacilityId()));
                writer.newLine();
            }
        }
    }

    private static Set<PTPersonTrack> readPersonTracks(Path path) throws Exception {
        Set<PTPersonTrack> tracks = new LinkedHashSet<>();
        try (InputStream in = new GZIPInputStream(Files.newInputStream(path));
             BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length < 8) {
                    continue;
                }
                PTPersonTrack track = new PTPersonTrack();
                track.setTime(parseDouble(parts[0]));
                track.setEnter(Boolean.parseBoolean(parts[1]));
                track.setPersonId(parts[2].isBlank() ? null : PersonId.create(parts[2]));
                track.setLineId(parts[3].isBlank() ? null : LineId.create(parts[3]));
                track.setRouteId(parts[4].isBlank() ? null : RouteId.create(parts[4]));
                track.setVehicleId(parts[5].isBlank() ? null : VehicleId.create(parts[5]));
                track.setDepartureId(parts[6].isBlank() ? null : DepartureId.create(parts[6]));
                track.setFacilityId(parts[7].isBlank() ? null : StopFacilityId.create(parts[7]));
                tracks.add(track);
            }
        }
        return tracks;
    }

    private static Map<String, Object> writeTrajectoryCache(
            MatsimData data,
            VehicleTrajectoryHandler handler,
            TrajectoryMeta trajectoryMeta
    ) throws Exception {
        handler.finishProgress();
        handler.assertCompleteNetworkCoverage();
        invalidateTrajectoryLightManifestCache(data);
        MatsimCachePaths.recreateVersionDir(data, TRAJECTORY_CACHE_VERSION);

        List<List<Object>> passengerSeries = toPassengerSeries(data.getPersonTracks(), trajectoryMeta.transitVehicles);
        Map<String, Integer> routeBoardings = routeBoardings(data.getPersonTracks());

        int minTime = handler.getMinTime();
        int maxTime = handler.getMaxTime();
        for (List<Object> row : passengerSeries) {
            int time = ((Number) row.get(0)).intValue();
            minTime = Math.min(minTime, time);
            maxTime = Math.max(maxTime, time);
        }
        if (minTime == Integer.MAX_VALUE || maxTime == Integer.MIN_VALUE) {
            minTime = 0;
            maxTime = 86400;
        }

        List<VehicleTrace> traceList = handler.getTraces().stream()
                .filter(trace -> !trace.segments.isEmpty())
                .toList();

        Map<String, Long> vehicleCountByMode = emptyLongModeMap();
        Map<String, Double> distanceByMode = emptyDoubleModeMap();
        for (VehicleTrace trace : traceList) {
            vehicleCountByMode.merge(trace.mode, 1L, Long::sum);
            distanceByMode.merge(trace.mode, trace.distance, Double::sum);
        }
        distanceByMode.replaceAll((mode, distance) -> round2(distance / 1000.0));

        Map<String, Object> summary = new LinkedHashMap<>();
        long totalVehicles = vehicleCountByMode.values().stream().mapToLong(Long::longValue).sum();
        summary.put("totalVehicles", totalVehicles);
        summary.put("vehicleCountByMode", vehicleCountByMode);
        summary.put("totalPassengerBoardings", passengerSeries.stream().mapToLong(row -> ((Number) row.get(4)).longValue()).sum());
        summary.put("distanceKmByMode", distanceByMode);
        summary.put("pointCount", handler.pointCount());
        summary.put("routeBoardings", routeBoardings);

        Map<String, Object> timeRange = new LinkedHashMap<>();
        timeRange.put("min", minTime);
        timeRange.put("max", maxTime);

        List<Map<String, Object>> chunks = writeTrajectoryChunks(data, traceList, minTime, maxTime);
        summary.put("chunks", chunks);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("status", "ready");
        manifest.put("cacheVersion", TRAJECTORY_CACHE_VERSION);
        manifest.put("chunkSeconds", TRAJECTORY_PLAYBACK_WINDOW_SECONDS);
        manifest.put("storageChunkSeconds", TRAJECTORY_CHUNK_SECONDS);
        manifest.put("spatial", trajectorySpatialInfo());
        manifest.put("generatedAt", System.currentTimeMillis());
        putTrajectoryIdentity(manifest, data);
        manifest.put("timeRange", timeRange);
        manifest.put("summary", summary);
        manifest.put("quality", Map.of(
                "complete", true,
                "linkEvents", handler.linkEvents(),
                "missingLinkEvents", 0
        ));
        manifest.put("passengerSeries", passengerSeries);
        manifest.put("meta", buildTrajectoryMetaPayload(data, traceList, trajectoryMeta));
        manifest.put("vehicles", List.of());
        writeTrajectoryManifest(data, manifest);
        return manifest;
    }

    private static List<Map<String, Object>> writeTrajectoryChunks(
            MatsimData data,
            List<VehicleTrace> traceList,
            int minTime,
            int maxTime
    ) throws Exception {
        List<Map<String, Object>> chunks = new ArrayList<>();
        if (traceList.isEmpty()) {
            return chunks;
        }
        int firstStart = normalizeChunkStart(minTime);
        int lastStart = normalizeChunkStart(Math.max(minTime, maxTime - 1));
        int[] cursors = new int[traceList.size()];
        for (int chunkStart = firstStart; chunkStart <= lastStart; chunkStart += TRAJECTORY_CHUNK_SECONDS) {
            int chunkEnd = chunkStart + TRAJECTORY_CHUNK_SECONDS - 1;
            int chunkEndExclusive = chunkStart + TRAJECTORY_CHUNK_SECONDS;
            List<BinaryTrajectorySegment> binarySegments = new ArrayList<>();
            int vehicleCount = 0;
            int pointCount = 0;
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (int traceIndex = 0; traceIndex < traceList.size(); traceIndex++) {
                VehicleTrace trace = traceList.get(traceIndex);
                List<VehicleSegment> traceSegments = trace.segments;
                int cursor = cursors[traceIndex];
                while (cursor < traceSegments.size() && traceSegments.get(cursor).endTime <= chunkStart) {
                    cursor++;
                }
                cursors[traceIndex] = cursor;

                int modeCode = modeCode(trace.mode);
                int vehicleIndex = traceIndex;
                boolean hasChunkSegment = false;
                for (int segmentIndex = cursor; segmentIndex < traceSegments.size(); segmentIndex++) {
                    VehicleSegment segment = traceSegments.get(segmentIndex);
                    if (segment.startTime >= chunkEndExclusive) {
                        break;
                    }
                    if (segment.endTime <= chunkStart) {
                        continue;
                    }
                    VehicleSegment clipped = segment.clip(chunkStart, chunkEndExclusive);
                    if (clipped == null) continue;

                    if (!hasChunkSegment) {
                        vehicleCount++;
                        hasChunkSegment = true;
                    }

                    pointCount += 2;
                    double startX = clipped.fromX;
                    double startY = clipped.fromY;
                    double endX = clipped.toX;
                    double endY = clipped.toY;
                    minX = Math.min(minX, Math.min(startX, endX));
                    minY = Math.min(minY, Math.min(startY, endY));
                    maxX = Math.max(maxX, Math.max(startX, endX));
                    maxY = Math.max(maxY, Math.max(startY, endY));
                    binarySegments.add(new BinaryTrajectorySegment(
                            (float) clipped.startTime,
                            (float) clipped.endTime,
                            startX,
                            startY,
                            endX,
                            endY,
                            modeCode,
                            vehicleIndex,
                            (float) clipped.distance
                    ));
                }
            }

            if (binarySegments.isEmpty()) {
                continue;
            }

            Map<String, Object> chunk = chunkInfo(chunkStart, vehicleCount, pointCount);
            chunk.put("segmentCount", binarySegments.size());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ready");
            payload.put("cacheVersion", TRAJECTORY_CACHE_VERSION);
            payload.put("chunk", chunk);
            payload.put("vehicles", List.of());
            writeGzipJson(trajectoryCacheDir(data).resolve(chunkFileName(chunkStart)), payload);
            double originX = binarySegments.isEmpty() ? 0.0 : (minX + maxX) / 2.0;
            double originY = binarySegments.isEmpty() ? 0.0 : (minY + maxY) / 2.0;
            chunk.putAll(writeSpatialTrajectoryTiles(
                    data,
                    chunkStart,
                    chunkEnd,
                    originX,
                    originY,
                    binarySegments
            ));
            long fullChunkBytes = TRAJECTORY_BINARY_HEADER_BYTES
                    + (long) binarySegments.size() * TRAJECTORY_BINARY_STRIDE * Float.BYTES;
            if (fullChunkBytes <= TRAJECTORY_LEGACY_FULL_CHUNK_MAX_BYTES) {
                chunk.put("binaryAssembly", "on-demand-from-zstd-spatial-blocks");
                chunk.put("fullChunkAvailable", true);
            } else {
                chunk.put("fullChunkAvailable", false);
            }
            chunks.add(chunk);
        }
        return chunks;
    }

    private static TrajectoryMeta buildTrajectoryMeta(MatsimData data) {
        Map<String, TransitVehicleMeta> transitVehicles = new HashMap<>();
        Map<String, RouteMeta> routes = new LinkedHashMap<>();
        Map<String, TransitVehicleMeta> byLineRoute = new HashMap<>();
        TransitSchedule schedule = data.getSchedule();
        schedule.getTransitLines().forEach((lineId, line) -> {
            String lineIdText = lineId.toString();
            String lineName = nonBlank(line.getName(), lineIdText);
            line.getRoutes().forEach((routeId, route) -> {
                String routeIdText = routeId.toString();
                String routeName = nonBlank(route.getDescription(), routeIdText);
                String mode = inferTransitMode(lineName, lineIdText, routeName, routeIdText, route.getTransportMode());
                routes.putIfAbsent(routeIdText, new RouteMeta(
                        mode,
                        lineIdText,
                        lineName,
                        routeIdText,
                        routeName,
                        routeStops(route),
                        firstDepartureTime(route),
                        lastDepartureTime(route)
                ));
                TransitVehicleMeta meta = new TransitVehicleMeta(mode, lineIdText, routeIdText);
                byLineRoute.put(lineIdText + "::" + routeIdText, meta);
                route.getDepartures().forEach((departureId, departure) -> {
                    if (departure.getVehicleId() == null) {
                        return;
                    }
                    // 静态兜底：车辆服务多个班次时保留最后注册的班次；
                    // 事件解析时 TransitDriverStarts 会按当前班次动态覆盖。
                    transitVehicles.put(departure.getVehicleId().toString(), meta);
                });
            });
        });
        return new TrajectoryMeta(transitVehicles, routes, buildCarTripMeta(data.getPopulation()), byLineRoute);
    }

    private static List<Map<String, Object>> routeStops(TransitRoute route) {
        List<Map<String, Object>> stops = new ArrayList<>();
        int index = 1;
        for (TransitRouteStop stop : route.getStops()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", index++);
            item.put("id", stop.getStopFacility().getId().toString());
            item.put("name", nonBlank(stop.getStopFacility().getName(), stop.getStopFacility().getId().toString()));
            Coord coord = stop.getStopFacility().getCoord();
            if (coord != null) {
                item.put("x", roundCoord(coord.getX()));
                item.put("y", roundCoord(coord.getY()));
            }
            stops.add(item);
        }
        return stops;
    }

    private static double firstDepartureTime(TransitRoute route) {
        return route.getDepartures().values().stream()
                .mapToDouble(Departure::getDepartureTime)
                .min()
                .orElse(0.0);
    }

    private static double lastDepartureTime(TransitRoute route) {
        return route.getDepartures().values().stream()
                .mapToDouble(Departure::getDepartureTime)
                .max()
                .orElse(0.0);
    }

    private static Map<String, Object> buildTrajectoryMetaPayload(
            MatsimData data,
            List<VehicleTrace> traces,
            TrajectoryMeta trajectoryMeta
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> routes = new LinkedHashMap<>();
        trajectoryMeta.routes.forEach((routeId, route) -> routes.put(routeId, route.toPayload()));
        payload.put("routes", routes);

        Map<String, List<List<Object>>> passengerEvents = passengerEventsByVehicle(data.getPersonTracks());
        List<Map<String, Object>> vehicles = new ArrayList<>(traces.size());
        for (int index = 0; index < traces.size(); index++) {
            VehicleTrace trace = traces.get(index);
            Map<String, Object> item = new LinkedHashMap<>();
            RouteMeta route = trajectoryMeta.routes.get(trace.routeId);
            boolean transit = route != null || trajectoryMeta.transitVehicles.containsKey(trace.id);
            CarTripMeta carTrip = firstCarTrip(trace, trajectoryMeta);

            item.put("index", index);
            item.put("id", trace.id);
            item.put("mode", normalizeVehicleMode(trace.mode, transit));
            item.put("lineId", trace.lineId);
            item.put("routeId", trace.routeId);
            item.put("lineName", route == null ? trace.lineId : route.lineName);
            item.put("routeName", route == null ? trace.routeId : route.routeName);
            item.put("capacity", transit ? vehicleCapacity(data, trace.id) : 0);
            item.put("distance", round2(trace.distance));
            item.put("passengerEvents", passengerEvents.getOrDefault(trace.id, List.of()));
            item.put("personIds", new ArrayList<>(trace.personIds));
            item.put("personCount", transit ? 0 : Math.max(1, trace.personIds.size()));
            if (route != null) {
                item.put("firstTime", route.firstTime);
                item.put("lastTime", route.lastTime);
            }
            if (carTrip != null) {
                item.put("origin", carTrip.origin);
                item.put("destination", carTrip.destination);
                item.put("purpose", carTrip.purpose);
            }
            vehicles.add(item);
        }
        payload.put("vehicles", vehicles);
        return payload;
    }

    private static Map<String, List<List<Object>>> passengerEventsByVehicle(Collection<PTPersonTrack> tracks) {
        Map<String, Map<Integer, Integer>> byVehicle = new HashMap<>();
        for (PTPersonTrack track : tracks) {
            String vehicleId = idString(track.getVehicleId());
            if (vehicleId == null) {
                continue;
            }
            int time = roundTime(track.getTime());
            int delta = Boolean.TRUE.equals(track.getEnter()) ? 1 : -1;
            byVehicle.computeIfAbsent(vehicleId, key -> new java.util.TreeMap<>())
                    .merge(time, delta, Integer::sum);
        }
        Map<String, List<List<Object>>> result = new HashMap<>();
        byVehicle.forEach((vehicleId, bins) -> {
            List<List<Object>> rows = new ArrayList<>();
            bins.forEach((time, delta) -> {
                if (delta != 0) {
                    rows.add(List.of(time, delta));
                }
            });
            result.put(vehicleId, rows);
        });
        return result;
    }

    private static int vehicleCapacity(MatsimData data, String vehicleId) {
        if (vehicleId == null || vehicleId.isBlank()) {
            return 0;
        }
        Id<Vehicle> id = Id.create(vehicleId, Vehicle.class);
        Vehicle vehicle = data.getTv().getVehicles().get(id);
        if (vehicle == null && data.getScenario() != null && data.getScenario().getVehicles() != null) {
            vehicle = data.getScenario().getVehicles().getVehicles().get(id);
        }
        VehicleType type = vehicle == null ? null : vehicle.getType();
        if (type == null || type.getCapacity() == null) {
            return 0;
        }
        double seats = type.getCapacity().getSeats() == null ? 0.0 : type.getCapacity().getSeats();
        double standingRoom = type.getCapacity().getStandingRoom() == null ? 0.0 : type.getCapacity().getStandingRoom();
        return (int) Math.round(seats + standingRoom);
    }

    private static Map<String, CarTripMeta> buildCarTripMeta(Population population) {
        Map<String, CarTripMeta> result = new HashMap<>();
        if (population == null) {
            return result;
        }
        for (Person person : population.getPersons().values()) {
            if (person.getSelectedPlan() == null) {
                continue;
            }
            List<? extends PlanElement> elements = person.getSelectedPlan().getPlanElements();
            for (int i = 1; i < elements.size() - 1; i++) {
                if (!(elements.get(i) instanceof Leg leg)) {
                    continue;
                }
                if (!"car".equals(normalizeVehicleMode(leg.getMode(), false))) {
                    continue;
                }
                Activity origin = previousActivity(elements, i);
                Activity destination = nextActivity(elements, i);
                if (origin == null && destination == null) {
                    continue;
                }
                result.putIfAbsent(
                        person.getId().toString(),
                        new CarTripMeta(activityPayload(origin), activityPayload(destination), destination == null ? "" : destination.getType())
                );
            }
        }
        return result;
    }

    private static Activity previousActivity(List<? extends PlanElement> elements, int index) {
        for (int i = index - 1; i >= 0; i--) {
            if (elements.get(i) instanceof Activity activity) {
                return activity;
            }
        }
        return null;
    }

    private static Activity nextActivity(List<? extends PlanElement> elements, int index) {
        for (int i = index + 1; i < elements.size(); i++) {
            if (elements.get(i) instanceof Activity activity) {
                return activity;
            }
        }
        return null;
    }

    private static Map<String, Object> activityPayload(Activity activity) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (activity == null) {
            return result;
        }
        result.put("type", nonBlank(activity.getType(), "未知"));
        result.put("facilityId", activity.getFacilityId() == null ? "" : activity.getFacilityId().toString());
        Coord coord = activity.getCoord();
        if (coord != null) {
            result.put("x", roundCoord(coord.getX()));
            result.put("y", roundCoord(coord.getY()));
        }
        String facilityId = activity.getFacilityId() == null ? "" : activity.getFacilityId().toString();
        result.put("label", facilityId.isBlank() ? nonBlank(activity.getType(), "未知地点") : facilityId);
        return result;
    }

    private static CarTripMeta firstCarTrip(VehicleTrace trace, TrajectoryMeta trajectoryMeta) {
        for (String personId : trace.personIds) {
            CarTripMeta meta = trajectoryMeta.carTrips.get(personId);
            if (meta != null) {
                return meta;
            }
        }
        return trajectoryMeta.carTrips.get(trace.id);
    }

    private static List<List<Object>> toPassengerSeries(Collection<PTPersonTrack> personTracks, Map<String, TransitVehicleMeta> transitMeta) {
        Map<Integer, long[]> bins = new LinkedHashMap<>();
        personTracks.stream()
                .filter(track -> Boolean.TRUE.equals(track.getEnter()))
                .sorted(Comparator.comparingDouble(track -> track.getTime() == null ? 0.0 : track.getTime()))
                .forEach(track -> {
                    String vehicleId = idString(track.getVehicleId());
                    TransitVehicleMeta meta = transitMeta.get(vehicleId);
                    String mode = meta == null ? "bus" : meta.mode;
                    int time = roundTime(track.getTime());
                    long[] counts = bins.computeIfAbsent(time, value -> new long[4]);
                    switch (normalizeVehicleMode(mode, false)) {
                        case "bus" -> counts[0]++;
                        case "subway" -> counts[1]++;
                        default -> counts[2]++;
                    }
                    counts[3]++;
                });

        List<List<Object>> result = new ArrayList<>(bins.size());
        bins.forEach((time, counts) -> result.add(List.of(time, counts[0], counts[1], counts[2], counts[3])));
        return result;
    }

    private static Map<String, Integer> routeBoardings(Collection<PTPersonTrack> personTracks) {
        Map<String, Integer> counts = new HashMap<>();
        personTracks.stream()
                .filter(track -> Boolean.TRUE.equals(track.getEnter()))
                .forEach(track -> {
                    String routeId = idString(track.getRouteId());
                    if (routeId != null) {
                        counts.merge(routeId, 1, Integer::sum);
                    }
                });
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(8)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (oldValue, newValue) -> oldValue,
                        LinkedHashMap::new
                ));
    }

    private static void writeGzipJson(Path path, Map<String, Object> payload) throws Exception {
        Path tmpPath = path.resolveSibling(path.getFileName() + ".tmp");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmpPath))) {
            JSON.writeValue(out, payload);
        }
        try {
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeTrajectoryDictionaries(
            MatsimData data,
            MatsimLinkGeometryIndex links,
            ConcurrentMap<String, Integer> vehicleIndexById,
            int vehicleCount,
            Int2IntOpenHashMap vehicleModes
    ) throws Exception {
        ByteBuffer linkRaw = ByteBuffer.allocate(Math.addExact(32, Math.multiplyExact(links.size(), 20)))
                .order(ByteOrder.LITTLE_ENDIAN);
        linkRaw.put(TRAJECTORY_LINK_DICTIONARY_MAGIC);
        linkRaw.putShort((short) 1);
        linkRaw.putShort((short) 32);
        linkRaw.putInt(links.size());
        linkRaw.putInt(20);
        linkRaw.putDouble(links.originX());
        linkRaw.putDouble(links.originY());
        for (int index = 0; index < links.size(); index++) {
            linkRaw.putFloat(links.relativeFromX(index));
            linkRaw.putFloat(links.relativeFromY(index));
            linkRaw.putFloat(links.relativeToX(index));
            linkRaw.putFloat(links.relativeToY(index));
            linkRaw.putFloat((float) links.lengthMeters(index));
        }
        writeZstdDictionaryArtifact(trajectoryLinkDictionaryPath(data), linkRaw.array());

        String[] vehicleIds = new String[Math.max(0, vehicleCount)];
        vehicleIndexById.forEach((id, index) -> {
            if (index != null && index >= 0 && index < vehicleIds.length) vehicleIds[index] = id;
        });
        ByteArrayOutputStream vehicleRaw = new ByteArrayOutputStream(16 + vehicleCount * 12);
        vehicleRaw.write(TRAJECTORY_VEHICLE_DICTIONARY_MAGIC);
        writeShortLE(vehicleRaw, 1);
        writeShortLE(vehicleRaw, 16);
        writeIntLE(vehicleRaw, vehicleIds.length);
        writeIntLE(vehicleRaw, 0);
        for (int index = 0; index < vehicleIds.length; index++) {
            int mode = vehicleModes.get(index);
            vehicleRaw.write(mode < 0 ? 255 : mode);
            writeZeroBytes(vehicleRaw, 3);
            byte[] id = (vehicleIds[index] == null ? "" : vehicleIds[index]).getBytes(StandardCharsets.UTF_8);
            writeIntLE(vehicleRaw, id.length);
            vehicleRaw.write(id);
        }
        writeZstdDictionaryArtifact(trajectoryVehicleDictionaryPath(data), vehicleRaw.toByteArray());
        TRAJECTORY_DICTIONARIES.remove(trajectoryRepairKey(data));
    }

    private static void writeZstdDictionaryArtifact(Path path, byte[] raw) throws Exception {
        byte[] encoded = Zstd.compress(raw, TRAJECTORY_ZSTD_LEVEL);
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(temp, encoded);
        moveAtomically(temp, path);
    }

    private static TrajectoryDictionaries loadTrajectoryDictionaries(MatsimData data) throws IOException {
        Path linksPath = trajectoryLinkDictionaryPath(data);
        Path vehiclesPath = trajectoryVehicleDictionaryPath(data);
        String signature = Files.size(linksPath) + ":" + Files.getLastModifiedTime(linksPath).toMillis()
                + ":" + Files.size(vehiclesPath) + ":" + Files.getLastModifiedTime(vehiclesPath).toMillis();
        String cacheKey = trajectoryRepairKey(data);
        CachedTrajectoryDictionaries cached = TRAJECTORY_DICTIONARIES.get(cacheKey);
        if (cached != null && cached.signature.equals(signature)) return cached.value;

        byte[] linkRaw = decompressZstdArtifact(linksPath);
        ByteBuffer links = ByteBuffer.wrap(linkRaw).order(ByteOrder.LITTLE_ENDIAN);
        requireMagic(links, TRAJECTORY_LINK_DICTIONARY_MAGIC, "link geometry");
        int linkVersion = Short.toUnsignedInt(links.getShort());
        int linkHeader = Short.toUnsignedInt(links.getShort());
        int linkCount = links.getInt();
        int linkRecordBytes = links.getInt();
        double originX = links.getDouble();
        double originY = links.getDouble();
        if (linkVersion != 1 || linkHeader != 32 || linkRecordBytes != 20 || linkCount < 0
                || linkRaw.length != linkHeader + linkCount * linkRecordBytes) {
            throw new IOException("轨迹 link 几何字典格式不兼容");
        }
        float[] fromX = new float[linkCount];
        float[] fromY = new float[linkCount];
        float[] toX = new float[linkCount];
        float[] toY = new float[linkCount];
        float[] lengths = new float[linkCount];
        for (int i = 0; i < linkCount; i++) {
            fromX[i] = links.getFloat();
            fromY[i] = links.getFloat();
            toX[i] = links.getFloat();
            toY[i] = links.getFloat();
            lengths[i] = links.getFloat();
        }

        byte[] vehicleRaw = decompressZstdArtifact(vehiclesPath);
        ByteBuffer vehicles = ByteBuffer.wrap(vehicleRaw).order(ByteOrder.LITTLE_ENDIAN);
        requireMagic(vehicles, TRAJECTORY_VEHICLE_DICTIONARY_MAGIC, "vehicle metadata");
        int vehicleVersion = Short.toUnsignedInt(vehicles.getShort());
        int vehicleHeader = Short.toUnsignedInt(vehicles.getShort());
        int vehicleCount = vehicles.getInt();
        vehicles.getInt();
        if (vehicleVersion != 1 || vehicleHeader != 16 || vehicleCount < 0) {
            throw new IOException("轨迹车辆元数据字典格式不兼容");
        }
        byte[] modes = new byte[vehicleCount];
        for (int i = 0; i < vehicleCount; i++) {
            if (vehicles.remaining() < 8) throw new EOFException("车辆元数据字典不完整");
            modes[i] = vehicles.get();
            vehicles.position(vehicles.position() + 3);
            int idBytes = vehicles.getInt();
            if (idBytes < 0 || idBytes > vehicles.remaining()) {
                throw new EOFException("车辆元数据字典 id 不完整");
            }
            vehicles.position(vehicles.position() + idBytes);
        }
        if (vehicles.hasRemaining()) throw new IOException("车辆元数据字典存在尾部脏数据");
        TrajectoryDictionaries result = new TrajectoryDictionaries(
                originX, originY, fromX, fromY, toX, toY, lengths, modes);
        TRAJECTORY_DICTIONARIES.put(cacheKey, new CachedTrajectoryDictionaries(signature, result));
        return result;
    }

    private static byte[] decompressZstdArtifact(Path path) throws IOException {
        byte[] encoded = Files.readAllBytes(path);
        long rawBytes = Zstd.decompressedSize(encoded);
        if (rawBytes <= 0 || rawBytes > Integer.MAX_VALUE) {
            throw new IOException("Zstd 字典缺少有效的原始长度: " + path);
        }
        return Zstd.decompress(encoded, Math.toIntExact(rawBytes));
    }

    private static void requireMagic(ByteBuffer buffer, byte[] expected, String name) throws IOException {
        if (buffer.remaining() < expected.length) throw new EOFException(name + " dictionary header incomplete");
        for (byte value : expected) {
            if (buffer.get() != value) throw new IOException(name + " dictionary magic mismatch");
        }
    }

    private static void writeTrajectoryBinaryChunk(
            Path path,
            int chunkStart,
            int chunkEnd,
            int vehicleCount,
            int pointCount,
            double originX,
            double originY,
            List<BinaryTrajectorySegment> segments
    ) throws Exception {
        Path tmpPath = path.resolveSibling(path.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmpPath)) {
            writeTrajectoryBinaryPayload(out, chunkStart, chunkEnd, vehicleCount, pointCount, originX, originY, segments);
        }
        try {
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Map<String, Object> writeSpatialTrajectoryTiles(
            MatsimData data,
            int chunkStart,
            int chunkEnd,
            double originX,
            double originY,
            List<BinaryTrajectorySegment> segments
    ) throws Exception {
        Map<SpatialTileKey, List<BinaryTrajectorySegment>> byTile = new TreeMap<>();
        TrajectoryGlobalStatsAccumulator globalStats = new TrajectoryGlobalStatsAccumulator(chunkStart);
        double maxSegmentSpan = 0.0;
        for (BinaryTrajectorySegment segment : segments) {
            globalStats.add(
                    segment.startTime,
                    segment.endTime,
                    segment.modeCode,
                    segment.distanceMeters
            );
            SpatialTileKey key = spatialTileForMidpoint(
                    (segment.startX + segment.endX) / 2.0,
                    (segment.startY + segment.endY) / 2.0
            );
            byTile.computeIfAbsent(key, ignored -> new ArrayList<>()).add(segment);
            maxSegmentSpan = Math.max(maxSegmentSpan, Math.max(
                    Math.abs(segment.endX - segment.startX),
                    Math.abs(segment.endY - segment.startY)
            ));
        }

        List<SpatialIndexEntry> entries = new ArrayList<>(byTile.size());
        int offset = 0;
        for (Map.Entry<SpatialTileKey, List<BinaryTrajectorySegment>> entry : byTile.entrySet()) {
            SpatialTileAggregate envelope = new SpatialTileAggregate();
            for (BinaryTrajectorySegment segment : entry.getValue()) {
                envelope.add(
                        (float) (segment.startX - originX),
                        (float) (segment.startY - originY),
                        (float) (segment.endX - originX),
                        (float) (segment.endY - originY)
                );
            }
            entries.add(envelope.toIndexEntry(entry.getKey(), offset));
            offset += entry.getValue().size();
        }
        writeZstdSpatialContainer(
                trajectorySpatialContainerPath(data, chunkStart),
                chunkStart,
                chunkEnd,
                originX,
                originY,
                byTile,
                entries,
                segments.size()
        );
        writeSpatialTrajectoryIndex(
                data, chunkStart, originX, originY, maxSegmentSpan,
                entries, segments.size(), TRAJECTORY_BINARY_STRIDE);
        Map<String, Object> payload = spatialChunkPayload(byTile.keySet(), maxSegmentSpan);
        putSpatialContainerFiles(payload, chunkStart);
        payload.put("globalStats", globalStats.payload());
        return payload;
    }

    /**
     * 每个 4096m 空间块单独生成一个 Zstd frame。块内仍是原有 36-byte GJTB row，
     * 因此解压后可以零转换进入既有筛选/返回路径，不改变前端协议和浮点精度。
     */
    private static void writeZstdSpatialContainer(
            Path container,
            int chunkStart,
            int chunkEnd,
            double originX,
            double originY,
            Map<SpatialTileKey, List<BinaryTrajectorySegment>> byTile,
            List<SpatialIndexEntry> entries,
            int totalSegments
    ) throws Exception {
        Path temp = container.resolveSibling(container.getFileName() + ".tmp");
        try (OutputStream out = new BufferedOutputStream(
                Files.newOutputStream(temp), TRAJECTORY_IO_BUFFER_BYTES)) {
            writeZstdSpatialHeader(
                    out, chunkStart, chunkEnd, totalSegments, originX, originY,
                    TRAJECTORY_BINARY_STRIDE);
            long compressedOffset = TRAJECTORY_BINARY_HEADER_BYTES;
            for (SpatialIndexEntry entry : entries) {
                List<BinaryTrajectorySegment> tile = byTile.get(entry.key);
                if (tile == null || tile.size() != entry.count) {
                    throw new IOException("轨迹空间块记录数不一致: " + entry.key.x + "," + entry.key.y);
                }
                ByteBuffer rows = ByteBuffer.allocate(Math.multiplyExact(
                        entry.count, TRAJECTORY_BINARY_STRIDE * Float.BYTES
                )).order(ByteOrder.LITTLE_ENDIAN);
                for (BinaryTrajectorySegment segment : tile) {
                    rows.putFloat(segment.startTime);
                    rows.putFloat(segment.endTime);
                    rows.putFloat((float) (segment.startX - originX));
                    rows.putFloat((float) (segment.startY - originY));
                    rows.putFloat((float) (segment.endX - originX));
                    rows.putFloat((float) (segment.endY - originY));
                    rows.putFloat(segment.modeCode);
                    rows.putInt(segment.vehicleIndex);
                    rows.putFloat(segment.distanceMeters);
                }
                byte[] encoded = Zstd.compress(rows.array(), TRAJECTORY_ZSTD_LEVEL);
                if (encoded.length == 0) throw new IOException("Zstd 轨迹空间块压缩结果为空");
                entry.compressedOffset = compressedOffset;
                entry.compressedBytes = encoded.length;
                out.write(encoded);
                compressedOffset += encoded.length;
            }
        } catch (Exception e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        moveAtomically(temp, container);
    }

    private static void writeZstdSpatialContainerFromSortedRaw(
            Path sortedRaw,
            Path container,
            int chunkStart,
            int chunkEnd,
            double originX,
            double originY,
            List<SpatialIndexEntry> entries,
            int totalSegments
    ) throws Exception {
        Path temp = container.resolveSibling(container.getFileName() + ".tmp");
        try (FileChannel input = FileChannel.open(sortedRaw, StandardOpenOption.READ);
             OutputStream out = new BufferedOutputStream(
                     Files.newOutputStream(temp), TRAJECTORY_IO_BUFFER_BYTES)) {
            long expectedRawBytes = TRAJECTORY_BINARY_HEADER_BYTES
                    + (long) totalSegments * TRAJECTORY_COMPACT_STRIDE * Integer.BYTES;
            if (input.size() != expectedRawBytes) {
                throw new IOException("轨迹排序临时文件长度不一致");
            }
            writeZstdSpatialHeader(
                    out, chunkStart, chunkEnd, totalSegments, originX, originY,
                    TRAJECTORY_COMPACT_STRIDE);
            long compressedOffset = TRAJECTORY_BINARY_HEADER_BYTES;
            for (SpatialIndexEntry entry : entries) {
                int rawBytes = Math.multiplyExact(
                        entry.count, TRAJECTORY_COMPACT_STRIDE * Integer.BYTES);
                ByteBuffer rows = ByteBuffer.allocate(rawBytes);
                readFully(input, rows, TRAJECTORY_BINARY_HEADER_BYTES
                        + (long) entry.offset * TRAJECTORY_COMPACT_STRIDE * Integer.BYTES);
                byte[] encoded = Zstd.compress(rows.array(), TRAJECTORY_ZSTD_LEVEL);
                if (encoded.length == 0) throw new IOException("Zstd 轨迹空间块压缩结果为空");
                entry.compressedOffset = compressedOffset;
                entry.compressedBytes = encoded.length;
                out.write(encoded);
                compressedOffset += encoded.length;
            }
        } catch (Exception e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        moveAtomically(temp, container);
    }

    private static void writeZstdSpatialHeader(
            OutputStream out,
            int chunkStart,
            int chunkEnd,
            int segmentCount,
            double originX,
            double originY,
            int recordStride
    ) throws IOException {
        out.write(TRAJECTORY_ZSTD_MAGIC);
        writeShortLE(out, TRAJECTORY_BINARY_VERSION);
        writeShortLE(out, TRAJECTORY_BINARY_HEADER_BYTES);
        writeIntLE(out, chunkStart);
        writeIntLE(out, chunkEnd);
        writeIntLE(out, segmentCount);
        writeIntLE(out, 0);
        writeIntLE(out, segmentCount * 2);
        writeIntLE(out, recordStride);
        writeDoubleLE(out, originX);
        writeDoubleLE(out, originY);
        writeIntLE(out, TRAJECTORY_CHUNK_SECONDS);
        writeZeroBytes(out, TRAJECTORY_BINARY_HEADER_BYTES - 52);
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeSpatialTrajectoryIndex(
            MatsimData data,
            int chunkStart,
            double originX,
            double originY,
            double maxSegmentSpan,
            List<SpatialIndexEntry> entries,
            int totalSegments,
            int recordStride
    ) throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(
                TRAJECTORY_SPATIAL_INDEX_HEADER_BYTES
                        + entries.size() * TRAJECTORY_SPATIAL_INDEX_ENTRY_BYTES
        ).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(TRAJECTORY_SPATIAL_INDEX_MAGIC);
        buffer.putShort((short) TRAJECTORY_SPATIAL_INDEX_VERSION);
        buffer.putShort((short) TRAJECTORY_SPATIAL_INDEX_HEADER_BYTES);
        buffer.putInt(normalizeChunkStart(chunkStart));
        buffer.putInt(TRAJECTORY_SPATIAL_TILE_METERS);
        buffer.putInt(entries.size());
        buffer.putInt(recordStride);
        buffer.putInt(totalSegments);
        buffer.putInt(0);
        buffer.putDouble(originX);
        buffer.putDouble(originY);
        buffer.putDouble(maxSegmentSpan);
        buffer.putInt(TRAJECTORY_SPATIAL_INDEX_ENTRY_BYTES);
        buffer.putInt(0);
        for (SpatialIndexEntry entry : entries) {
            buffer.putInt(entry.key.x);
            buffer.putInt(entry.key.y);
            buffer.putLong(entry.compressedOffset);
            buffer.putInt(entry.compressedBytes);
            buffer.putInt(entry.count);
            buffer.putFloat(entry.minX);
            buffer.putFloat(entry.minY);
            buffer.putFloat(entry.maxX);
            buffer.putFloat(entry.maxY);
        }
        Path path = trajectorySpatialIndexPath(data, chunkStart);
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(temp, buffer.array());
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void putSpatialContainerFiles(Map<String, Object> payload, int chunkStart) {
        int normalized = normalizeChunkStart(chunkStart);
        payload.put("containerFile", String.format(Locale.ROOT, "spatial-%06d.zst", normalized));
        payload.put("spatialIndexFile", String.format(Locale.ROOT, "spatial-%06d.idx", normalized));
        payload.put("containerCompression", "zstd-independent-spatial-blocks");
        payload.put("artifactFiles", 2);
    }

    private static Map<String, Object> spatialChunkPayload(Collection<SpatialTileKey> tiles, double maxSegmentSpan) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tileCount", tiles.size());
        payload.put("maxSegmentSpanMeters", Math.ceil(Math.max(0.0, maxSegmentSpan)));
        if (!tiles.isEmpty()) {
            payload.put("tileMinX", tiles.stream().mapToInt(key -> key.x).min().orElse(0));
            payload.put("tileMaxX", tiles.stream().mapToInt(key -> key.x).max().orElse(0));
            payload.put("tileMinY", tiles.stream().mapToInt(key -> key.y).min().orElse(0));
            payload.put("tileMaxY", tiles.stream().mapToInt(key -> key.y).max().orElse(0));
        }
        return payload;
    }

    private static Map<String, Object> writeSpatialTrajectoryTilesFromRaw(
            MatsimData data,
            int chunkStart,
            int chunkEnd,
            double originX,
            double originY,
            List<Path> rawPaths,
            MatsimLinkGeometryIndex linkGeometry,
            Int2IntOpenHashMap vehicleModes
    ) throws Exception {
        Map<SpatialTileKey, SpatialTileAggregate> aggregates = new TreeMap<>();
        TrajectoryGlobalStatsAccumulator globalStats = new TrajectoryGlobalStatsAccumulator(chunkStart);
        byte[] row = new byte[TRAJECTORY_COMPACT_STRIDE * Integer.BYTES];
        ByteBuffer record = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN);
        double maxSegmentSpan = 0.0;
        for (Path rawPath : rawPaths) {
            try (InputStream in = new BufferedInputStream(Files.newInputStream(rawPath), TRAJECTORY_IO_BUFFER_BYTES)) {
                while (readFully(in, row)) {
                    record.clear();
                    int originalStart = record.getInt();
                    int originalEnd = record.getInt();
                    int vehicleIndex = record.getInt();
                    int linkIndex = record.getInt();
                    VehicleSegment segment = compactSegment(
                            linkGeometry, linkIndex, originalStart, originalEnd, chunkStart);
                    int modeCode = vehicleModes.get(vehicleIndex);
                    if (segment == null || modeCode < 0) {
                        throw new IOException("紧凑轨迹记录引用了无效字典项");
                    }
                    float startX = (float) (segment.fromX - originX);
                    float startY = (float) (segment.fromY - originY);
                    float endX = (float) (segment.toX - originX);
                    float endY = (float) (segment.toY - originY);
                    SpatialTileKey key = spatialTileForMidpoint(
                            (segment.fromX + segment.toX) / 2.0,
                            (segment.fromY + segment.toY) / 2.0
                    );
                    aggregates.computeIfAbsent(key, ignored -> new SpatialTileAggregate())
                            .add(startX, startY, endX, endY);
                    globalStats.add(
                            segment.startTime,
                            segment.endTime,
                            modeCode,
                            (float) segment.distance
                    );
                    maxSegmentSpan = Math.max(maxSegmentSpan, Math.max(
                            Math.abs(endX - startX),
                            Math.abs(endY - startY)
                    ));
                }
            }
        }

        List<SpatialIndexEntry> entries = new ArrayList<>(aggregates.size());
        int totalSegments = 0;
        for (Map.Entry<SpatialTileKey, SpatialTileAggregate> entry : aggregates.entrySet()) {
            SpatialIndexEntry indexEntry = entry.getValue().toIndexEntry(entry.getKey(), totalSegments);
            entries.add(indexEntry);
            totalSegments += indexEntry.count;
        }
        long containerBytes = TRAJECTORY_BINARY_HEADER_BYTES
                + (long) totalSegments * TRAJECTORY_COMPACT_STRIDE * Integer.BYTES;
        if (containerBytes > Integer.MAX_VALUE) {
            throw new IOException("单个 30s 轨迹容器超过 2GB，无法安全内存映射: " + containerBytes);
        }
        Path container = trajectorySpatialContainerPath(data, chunkStart);
        Path temp = container.resolveSibling(container.getFileName() + ".raw-sort.tmp");
        Map<SpatialTileKey, SpatialIndexEntry> byKey = entries.stream().collect(Collectors.toMap(
                entry -> entry.key,
                entry -> entry,
                (left, right) -> left,
                LinkedHashMap::new
        ));
        try (FileChannel channel = FileChannel.open(
                temp,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
        )) {
            if (containerBytes > 0) {
                channel.position(containerBytes - 1);
                channel.write(ByteBuffer.wrap(new byte[]{0}));
            }
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_WRITE, 0, containerBytes);
            mapped.order(ByteOrder.LITTLE_ENDIAN);
            mapped.put(TRAJECTORY_BINARY_MAGIC);
            mapped.putShort((short) TRAJECTORY_BINARY_VERSION);
            mapped.putShort((short) TRAJECTORY_BINARY_HEADER_BYTES);
            mapped.putInt(chunkStart);
            mapped.putInt(chunkEnd);
            mapped.putInt(totalSegments);
            mapped.putInt(0);
            mapped.putInt(totalSegments * 2);
            mapped.putInt(TRAJECTORY_COMPACT_STRIDE);
            mapped.putDouble(originX);
            mapped.putDouble(originY);
            mapped.putInt(TRAJECTORY_CHUNK_SECONDS);
            mapped.position(TRAJECTORY_BINARY_HEADER_BYTES);

            for (Path rawPath : rawPaths) {
                try (InputStream in = new BufferedInputStream(Files.newInputStream(rawPath), TRAJECTORY_IO_BUFFER_BYTES)) {
                    while (readFully(in, row)) {
                        record.clear();
                        int originalStart = record.getInt();
                        int originalEnd = record.getInt();
                        record.getInt();
                        int linkIndex = record.getInt();
                        VehicleSegment segment = compactSegment(
                                linkGeometry, linkIndex, originalStart, originalEnd, chunkStart);
                        if (segment == null) throw new IOException("紧凑轨迹记录时间范围无效");
                        SpatialTileKey key = spatialTileForMidpoint(
                                (segment.fromX + segment.toX) / 2.0,
                                (segment.fromY + segment.toY) / 2.0
                        );
                        SpatialIndexEntry target = byKey.get(key);
                        int recordIndex = target.offset + target.written++;
                        mapped.position(TRAJECTORY_BINARY_HEADER_BYTES
                                + recordIndex * TRAJECTORY_COMPACT_STRIDE * Integer.BYTES);
                        mapped.put(row);
                    }
                }
            }
            mapped.force();
        }
        writeZstdSpatialContainerFromSortedRaw(
                temp, container, chunkStart, chunkEnd, originX, originY, entries, totalSegments);
        Files.deleteIfExists(temp);
        writeSpatialTrajectoryIndex(
                data, chunkStart, originX, originY, maxSegmentSpan,
                entries, totalSegments, TRAJECTORY_COMPACT_STRIDE);
        Map<String, Object> payload = spatialChunkPayload(aggregates.keySet(), maxSegmentSpan);
        putSpatialContainerFiles(payload, chunkStart);
        payload.put("globalStats", globalStats.payload());
        return payload;
    }

    private static void writeTrajectoryBinaryChunkStreaming(
            Path path,
            int chunkStart,
            int chunkEnd,
            int segmentCount,
            int vehicleCount,
            int pointCount,
            double originX,
            double originY,
            Path rawPath
    ) throws Exception {
        writeTrajectoryBinaryChunkStreaming(
                path,
                chunkStart,
                chunkEnd,
                segmentCount,
                vehicleCount,
                pointCount,
                originX,
                originY,
                List.of(rawPath)
        );
    }

    private static void writeTrajectoryBinaryChunkStreaming(
            Path path,
            int chunkStart,
            int chunkEnd,
            int segmentCount,
            int vehicleCount,
            int pointCount,
            double originX,
            double originY,
            List<Path> rawPaths
    ) throws Exception {
        Path tmpPath = path.resolveSibling(path.getFileName() + ".tmp");
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmpPath), TRAJECTORY_IO_BUFFER_BYTES)) {
            writeTrajectoryBinaryHeader(out, chunkStart, chunkEnd, segmentCount, vehicleCount, pointCount, originX, originY);
            byte[] row = new byte[TRAJECTORY_BINARY_STRIDE * Float.BYTES];
            ByteBuffer input = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN);
            byte[] outputRow = new byte[TRAJECTORY_BINARY_STRIDE * Float.BYTES];
            ByteBuffer output = ByteBuffer.wrap(outputRow).order(ByteOrder.LITTLE_ENDIAN);
            for (Path rawPath : rawPaths) {
                try (InputStream in = new BufferedInputStream(Files.newInputStream(rawPath), TRAJECTORY_IO_BUFFER_BYTES)) {
                    while (readFully(in, row)) {
                        input.clear();
                        float startTime = input.getFloat();
                        float endTime = input.getFloat();
                        float startX = input.getFloat();
                        float startY = input.getFloat();
                        float endX = input.getFloat();
                        float endY = input.getFloat();
                        float modeCode = input.getFloat();
                        int vehicleIndex = input.getInt();
                        float distanceMeters = input.getFloat();

                        output.clear();
                        output.putFloat(startTime);
                        output.putFloat(endTime);
                        // 大模型 raw 块已是相对于 header origin 的 float，直接复制；
                        // 不可再减一次原点。
                        output.putFloat(startX);
                        output.putFloat(startY);
                        output.putFloat(endX);
                        output.putFloat(endY);
                        output.putFloat(modeCode);
                        output.putInt(vehicleIndex);
                        output.putFloat(distanceMeters);
                        out.write(outputRow);
                    }
                }
            }
        }
        try {
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeTrajectoryBinaryHeader(
            OutputStream out,
            int chunkStart,
            int chunkEnd,
            int segmentCount,
            int vehicleCount,
            int pointCount,
            double originX,
            double originY
    ) throws IOException {
        writeTrajectoryBinaryHeader(
                out, chunkStart, chunkEnd, segmentCount, vehicleCount, pointCount,
                originX, originY, TRAJECTORY_CHUNK_SECONDS
        );
    }

    private static void writeTrajectoryBinaryHeader(
            OutputStream out,
            int chunkStart,
            int chunkEnd,
            int segmentCount,
            int vehicleCount,
            int pointCount,
            double originX,
            double originY,
            int declaredChunkSeconds
    ) throws IOException {
        out.write(TRAJECTORY_BINARY_MAGIC);
        writeShortLE(out, TRAJECTORY_BINARY_VERSION);
        writeShortLE(out, TRAJECTORY_BINARY_HEADER_BYTES);
        writeIntLE(out, chunkStart);
        writeIntLE(out, chunkEnd);
        writeIntLE(out, segmentCount);
        writeIntLE(out, vehicleCount);
        writeIntLE(out, pointCount);
        writeIntLE(out, TRAJECTORY_BINARY_STRIDE);
        writeDoubleLE(out, originX);
        writeDoubleLE(out, originY);
        writeIntLE(out, Math.max(1, declaredChunkSeconds));
        writeZeroBytes(out, TRAJECTORY_BINARY_HEADER_BYTES - 52);
    }

    private static boolean readFully(InputStream in, byte[] bytes) throws IOException {
        int offset = 0;
        while (offset < bytes.length) {
            int read = in.read(bytes, offset, bytes.length - offset);
            if (read < 0) {
                if (offset == 0) {
                    return false;
                }
                throw new EOFException("轨迹二进制原始分块记录不完整");
            }
            offset += read;
        }
        return true;
    }

    private static byte[] createTrajectoryBinaryBytes(
            int chunkStart,
            int chunkEnd,
            int vehicleCount,
            int pointCount,
            double originX,
            double originY,
            List<BinaryTrajectorySegment> segments
    ) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                TRAJECTORY_BINARY_HEADER_BYTES + segments.size() * TRAJECTORY_BINARY_STRIDE * Float.BYTES
        );
        writeTrajectoryBinaryPayload(out, chunkStart, chunkEnd, vehicleCount, pointCount, originX, originY, segments);
        return out.toByteArray();
    }

    private static void writeTrajectoryBinaryPayload(
            OutputStream out,
            int chunkStart,
            int chunkEnd,
            int vehicleCount,
            int pointCount,
            double originX,
            double originY,
            List<BinaryTrajectorySegment> segments
    ) throws IOException {
        ByteBuffer buffer = ByteBuffer
                .allocate(TRAJECTORY_BINARY_HEADER_BYTES + segments.size() * TRAJECTORY_BINARY_STRIDE * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(TRAJECTORY_BINARY_MAGIC);
        buffer.putShort((short) TRAJECTORY_BINARY_VERSION);
        buffer.putShort((short) TRAJECTORY_BINARY_HEADER_BYTES);
        buffer.putInt(chunkStart);
        buffer.putInt(chunkEnd);
        buffer.putInt(segments.size());
        buffer.putInt(vehicleCount);
        buffer.putInt(pointCount);
        buffer.putInt(TRAJECTORY_BINARY_STRIDE);
        buffer.putDouble(originX);
        buffer.putDouble(originY);
        buffer.putInt(TRAJECTORY_CHUNK_SECONDS);
        buffer.position(TRAJECTORY_BINARY_HEADER_BYTES);

        for (BinaryTrajectorySegment segment : segments) {
            buffer.putFloat(segment.startTime);
            buffer.putFloat(segment.endTime);
            buffer.putFloat((float) (segment.startX - originX));
            buffer.putFloat((float) (segment.startY - originY));
            buffer.putFloat((float) (segment.endX - originX));
            buffer.putFloat((float) (segment.endY - originY));
            buffer.putFloat(segment.modeCode);
            buffer.putInt(segment.vehicleIndex);
            buffer.putFloat(segment.distanceMeters);
        }
        out.write(buffer.array());
    }

    private static void writeShortLE(OutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void writeIntLE(OutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }

    private static void writeFloatLE(OutputStream out, float value) throws IOException {
        writeIntLE(out, Float.floatToIntBits(value));
    }

    private static void writeDoubleLE(OutputStream out, double value) throws IOException {
        long bits = Double.doubleToLongBits(value);
        out.write((int) (bits & 0xff));
        out.write((int) ((bits >>> 8) & 0xff));
        out.write((int) ((bits >>> 16) & 0xff));
        out.write((int) ((bits >>> 24) & 0xff));
        out.write((int) ((bits >>> 32) & 0xff));
        out.write((int) ((bits >>> 40) & 0xff));
        out.write((int) ((bits >>> 48) & 0xff));
        out.write((int) ((bits >>> 56) & 0xff));
    }

    private static void writeZeroBytes(OutputStream out, int count) throws IOException {
        for (int i = 0; i < count; i++) {
            out.write(0);
        }
    }

    private static Map<String, Object> readGzipJson(Path path) throws Exception {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(path))) {
            return JSON.readValue(in, MAP_TYPE);
        }
    }

    private static void writeJsonAtomic(Path path, Map<String, Object> payload) throws Exception {
        Files.createDirectories(path.getParent());
        Path tmpPath = Files.createTempFile(path.getParent(), path.getFileName().toString() + ".", ".tmp");
        try {
            JSON.writeValue(tmpPath.toFile(), payload);
            try {
                Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmpPath);
        }
    }

    private static void writeTrajectoryManifest(MatsimData data, Map<String, Object> manifest) throws Exception {
        invalidateTrajectoryLightManifestCache(data);
        writeJsonAtomic(trajectoryManifestPath(data), manifest);
        Map<String, Object> lightManifest = Collections.unmodifiableMap(lightweightTrajectoryManifest(manifest));
        Path lightPath = trajectoryLightManifestPath(data);
        writeJsonAtomic(lightPath, lightManifest);
        cacheTrajectoryLightManifest(lightPath, lightManifest);
        Files.deleteIfExists(trajectoryRepairMarkerPath(data));
        TRAJECTORY_REPAIR_REQUESTS.remove(trajectoryRepairKey(data));
        MatsimCachePaths.deleteOtherVersions(data, "trajectory-v", TRAJECTORY_CACHE_VERSION);
    }

    private static void putTrajectoryIdentity(Map<String, Object> manifest, MatsimData data) {
        String events = data.getOutfile().getEvents();
        manifest.put("eventsFile", events);
        manifest.put("eventsModified", lastModified(events));
        manifest.put("eventsSize", fileSize(events));
        manifest.put("eventsSignature", MatsimSourceFingerprint.signature(events));
        manifest.put("sourceFingerprintSchema", MatsimSourceFingerprint.SCHEMA);
        manifest.put("sources", trajectorySources(data));
        manifest.put("cacheGeneration", UUID.randomUUID().toString());
    }

    private static Map<String, Object> trajectorySources(MatsimData data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("events", trajectorySource(data.getOutfile().getEvents()));
        result.put("network", trajectorySource(data.getOutfile().getNetwork()));
        result.put("schedule", trajectorySource(data.getOutfile().getTransitSchedule()));
        result.put("transitVehicles", trajectorySource(data.getOutfile().getTransitVehicles()));
        result.put("config", trajectorySource(data.getOutfile().getSourceConfig()));
        // 小模型车辆 meta 中的起终点/目的来自 population(plans)；不纳入指纹会在
        // plans 单独更新后继续命中旧的小汽车业务元数据。缺失文件也以 missing 稳定入指纹。
        result.put("plans", trajectorySource(data.getOutfile().getPlans()));
        return result;
    }

    private static Map<String, Object> trajectorySource(String filePath) {
        Map<String, Object> item = new LinkedHashMap<>();
        String normalized = filePath == null || filePath.isBlank()
                ? "" : Path.of(filePath).toAbsolutePath().normalize().toString();
        item.put("file", normalized);
        item.put("size", fileSize(filePath));
        item.put("modified", lastModified(filePath));
        item.put("signature", MatsimSourceFingerprint.signature(filePath));
        return item;
    }

    private static boolean sameTrajectorySources(MatsimData data, Map<String, Object> manifest) {
        if (!MatsimSourceFingerprint.SCHEMA.equals(manifest.get("sourceFingerprintSchema"))) {
            return false;
        }
        Object actualSources = manifest.get("sources");
        if (!(actualSources instanceof Map<?, ?> actual)) {
            return false;
        }
        Map<String, Object> expected = trajectorySources(data);
        if (actual.size() != expected.size()) {
            return false;
        }
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> expectedItem)
                    || !(actual.get(entry.getKey()) instanceof Map<?, ?> actualItem)) {
                return false;
            }
            if (MatsimSourceFingerprint.sameSourceItem(entry.getKey(), expectedItem, actualItem)) {
                continue;
            }
            // trajectory-v14 早期版本在模型加载后把兼容转换 config 写入 manifest；
            // 目录巡检则使用原始 config，导致每次巡检都误判缓存过期。
            // 转换工件的 .version 仍与原文件匹配且工件本身未变时，允许平滑复用。
            if (!"config".equals(entry.getKey())
                    || !legacyConvertedConfigSourceMatches(data, actualItem)) {
                return false;
            }
        }
        return true;
    }

    private static boolean legacyConvertedConfigSourceMatches(MatsimData data, Map<?, ?> actualItem) {
        String actualPath = String.valueOf(actualItem.get("file"));
        return data.getOutfile().isSourceOrCompatibleConvertedConfig(actualPath)
                && trajectorySourceItemEquals(trajectorySource(actualPath), actualItem);
    }

    private static boolean trajectorySourceItemEquals(Map<?, ?> expected, Map<?, ?> actual) {
        return MatsimSourceFingerprint.sameSourceItem(expected, actual);
    }

    private static long numberValue(Object value) {
        return value instanceof Number number ? number.longValue() : Long.MIN_VALUE;
    }

    private static boolean sameEvents(MatsimData data, Map<String, Object> manifest) {
        long eventsModified = ((Number) manifest.getOrDefault("eventsModified", -1)).longValue();
        long eventsSize = ((Number) manifest.getOrDefault("eventsSize", -1)).longValue();
        Object oldSignature = manifest.get("eventsSignature");
        if (oldSignature != null) {
            Map<String, Object> current = trajectorySource(data.getOutfile().getEvents());
            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("size", eventsSize);
            stored.put("modified", eventsModified);
            stored.put("signature", oldSignature);
            return MatsimSourceFingerprint.sameSourceItem("events", current, stored);
        }
        return eventsModified == lastModified(data.getOutfile().getEvents())
                && eventsSize == fileSize(data.getOutfile().getEvents());
    }

    private static boolean manifestHasChunk(Map<String, Object> manifest, int chunkStart) {
        return manifestChunk(manifest, chunkStart) != null;
    }

    private static Map<?, ?> manifestChunk(Map<String, Object> manifest, int chunkStart) {
        Object summaryObject = manifest.get("summary");
        if (!(summaryObject instanceof Map<?, ?> summary)) {
            return null;
        }
        Object chunksObject = summary.get("chunks");
        if (!(chunksObject instanceof List<?> chunks)) {
            return null;
        }
        for (Object item : chunks) {
            if (!(item instanceof Map<?, ?> chunk)) {
                continue;
            }
            Object startObject = chunk.get("start");
            if (startObject instanceof Number start && start.intValue() == chunkStart) {
                return chunk;
            }
        }
        return null;
    }

    private static Path personTrackCacheDir(MatsimData data) {
        return MatsimCachePaths.versionDir(data, PERSON_TRACK_CACHE_VERSION);
    }

    private static Path personTrackManifestPath(MatsimData data) {
        return personTrackCacheDir(data).resolve("manifest.json");
    }

    /** 包级只读入口，供低内存 person 分区读取器复用同一磁盘工件。 */
    static Path personTracksPath(MatsimData data) {
        return personTrackCacheDir(data).resolve(PERSON_TRACK_FILE);
    }

    private static Path trajectoryCacheDir(MatsimData data) {
        return MatsimCachePaths.versionDir(data, TRAJECTORY_CACHE_VERSION);
    }

    private static Path trajectoryManifestPath(MatsimData data) {
        return trajectoryCacheDir(data).resolve("manifest.json");
    }

    private static Path trajectoryLightManifestPath(MatsimData data) {
        return trajectoryCacheDir(data).resolve(TRAJECTORY_LIGHT_MANIFEST_FILE);
    }

    private static Path trajectoryRepairMarkerPath(MatsimData data) {
        return trajectoryCacheDir(data).resolve(TRAJECTORY_REPAIR_MARKER_FILE);
    }

    private static String trajectoryRepairKey(MatsimData data) {
        return trajectoryCacheDir(data).toAbsolutePath().normalize().toString();
    }

    private static String chunkFileName(int start) {
        return String.format(Locale.ROOT, "chunk-%06d.json.gz", normalizeChunkStart(start));
    }

    private static String chunkBinaryFileName(int start) {
        return String.format(Locale.ROOT, "chunk-%06d.bin", normalizeChunkStart(start));
    }

    private static Path trajectorySpatialContainerPath(MatsimData data, int start) {
        return trajectoryCacheDir(data)
                .resolve(String.format(Locale.ROOT, "spatial-%06d.zst", normalizeChunkStart(start)));
    }

    private static Path trajectoryLegacySpatialContainerPath(MatsimData data, int start) {
        return trajectoryCacheDir(data)
                .resolve(String.format(Locale.ROOT, "spatial-%06d.bin", normalizeChunkStart(start)));
    }

    private static Path trajectorySpatialIndexPath(MatsimData data, int start) {
        return trajectoryCacheDir(data)
                .resolve(String.format(Locale.ROOT, "spatial-%06d.idx", normalizeChunkStart(start)));
    }

    private static Path trajectoryLinkDictionaryPath(MatsimData data) {
        return trajectoryCacheDir(data).resolve(TRAJECTORY_LINK_DICTIONARY_FILE);
    }

    private static Path trajectoryVehicleDictionaryPath(MatsimData data) {
        return trajectoryCacheDir(data).resolve(TRAJECTORY_VEHICLE_DICTIONARY_FILE);
    }

    private static SpatialTileKey spatialTileForMidpoint(double x, double y) {
        return new SpatialTileKey(
                (int) Math.floor(x / TRAJECTORY_SPATIAL_TILE_METERS),
                (int) Math.floor(y / TRAJECTORY_SPATIAL_TILE_METERS)
        );
    }

    private static long lastModified(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return 0L;
        }
        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                return 0L;
            }
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            throw new IllegalStateException("读取源文件修改时间失败: " + filePath, e);
        }
    }

    private static long fileSize(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return 0L;
        }
        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                return 0L;
            }
            return Files.size(path);
        } catch (Exception e) {
            throw new IllegalStateException("读取源文件大小失败: " + filePath, e);
        }
    }

    private static boolean shouldStreamTrajectoryBuild(MatsimData data) {
        return shouldStreamTrajectoryBuild(data.isLargeModel(), fileSize(data.getOutfile().getEvents()));
    }

    static boolean shouldStreamTrajectoryBuild(boolean largeModel, long compressedEventsBytes) {
        return largeModel || compressedEventsBytes >= TRAJECTORY_STREAMING_EVENTS_THRESHOLD_BYTES;
    }

    private static String idString(Object value) {
        return value == null ? null : value.toString();
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String tsv(Object value) {
        if (value == null) {
            return "";
        }
        return value.toString().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("乘客明细数值字段非法: " + value, e);
        }
    }

    private static int roundTime(Double time) {
        if (time == null || Double.isNaN(time) || Double.isInfinite(time)) {
            throw new IllegalArgumentException("轨迹事件时刻非法: " + time);
        }
        return Math.max(0, (int) Math.round(time));
    }

    private static double roundCoord(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static Map<String, Long> emptyLongModeMap() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("bus", 0L);
        result.put("subway", 0L);
        result.put("car", 0L);
        return result;
    }

    private static Map<String, Double> emptyDoubleModeMap() {
        Map<String, Double> result = new LinkedHashMap<>();
        result.put("bus", 0.0);
        result.put("subway", 0.0);
        result.put("car", 0.0);
        return result;
    }

    private static String normalizeVehicleMode(String rawMode, boolean transitVehicle) {
        String text = rawMode == null ? "" : rawMode.toLowerCase(Locale.ROOT);
        if (text.contains("subway") || text.contains("metro") || text.contains("rail")
                || text.contains("train") || text.contains("mtr") || text.contains("地铁")
                || text.contains("轨道") || text.contains("轻轨") || text.contains("有轨")) {
            return "subway";
        }
        if (transitVehicle || text.contains("bus") || text.contains("pt")) {
            return "bus";
        }
        return "car";
    }

    private static String inferTransitMode(String lineName, String lineId, String routeName, String routeId, String transportMode) {
        String mode = normalizeDeclaredTransportMode(transportMode);
        if ("subway".equals(mode) || "bus".equals(mode)) {
            return mode;
        }
        String lineText = nonBlank(lineName, "") + " " + nonBlank(lineId, "");
        String routeText = nonBlank(routeName, "") + " " + nonBlank(routeId, "");
        if (!containsMetroModeKeyword(lineText) && containsBusIdKeyword(lineId + " " + routeId)) {
            return "bus";
        }
        // “地铁接驳专线”“轨道巴士”等公交命名含地铁关键词，公交业务词优先判 bus
        if (containsBusServiceKeyword(lineText + " " + routeText)) {
            return "bus";
        }
        if (!canonicalMetroLineNumber(lineText).isBlank()
                || containsMetroModeKeyword(lineText)
                || !canonicalMetroLineNumber(routeText).isBlank()
                || containsRouteIdMetroKeyword(routeId)) {
            return "subway";
        }
        return "bus";
    }

    private static boolean containsBusServiceKeyword(String text) {
        String value = nonBlank(text, "").toLowerCase(Locale.ROOT);
        return value.contains("接驳") || value.contains("专线")
                || value.contains("巴士") || value.contains("公交") || value.contains("brt");
    }

    private static String normalizeDeclaredTransportMode(String rawMode) {
        String text = rawMode == null ? "" : rawMode.toLowerCase(Locale.ROOT);
        if (text.contains("subway") || text.contains("metro") || text.contains("rail")
                || text.contains("train") || text.contains("mtr") || text.contains("地铁")
                || text.contains("轨道") || text.contains("轻轨") || text.contains("有轨")) {
            return "subway";
        }
        if (text.contains("bus") || text.contains("公交")) {
            return "bus";
        }
        return "";
    }

    private static boolean containsMetroModeKeyword(String text) {
        String value = nonBlank(text, "").toLowerCase(Locale.ROOT);
        return value.contains("subway") || value.contains("metro") || value.contains("mtr")
                || value.contains("rail") || value.contains("train")
                || value.contains("地铁") || value.contains("轨道")
                || value.contains("轻轨") || value.contains("有轨");
    }

    private static boolean containsRouteIdMetroKeyword(String text) {
        String value = nonBlank(text, "").toLowerCase(Locale.ROOT);
        return value.contains("subway") || value.contains("metro") || value.contains("mtr");
    }

    private static boolean containsBusIdKeyword(String text) {
        String value = nonBlank(text, "").toLowerCase(Locale.ROOT);
        return value.contains("busgtfs") || value.contains("bus_gtfs")
                || value.startsWith("bus") || value.contains(" bus");
    }

    private static String canonicalMetroLineNumber(String text) {
        String value = nonBlank(text, "");
        Matcher matcher = CHINESE_METRO_LINE_NUMBER_PATTERN.matcher(value);
        while (matcher.find()) {
            String number = chineseLineNumber(
                    matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
            if (!number.isBlank()) {
                return number;
            }
        }
        matcher = ENGLISH_METRO_LINE_NUMBER_PATTERN.matcher(value);
        while (matcher.find()) {
            String number = chineseLineNumber(nonBlank(matcher.group(1), matcher.group(2)));
            if (!number.isBlank()) {
                return number;
            }
        }
        return "";
    }

    private static String chineseLineNumber(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        String value = token.trim();
        if (value.chars().allMatch(Character::isDigit)) {
            return String.valueOf(Integer.parseInt(value));
        }
        return switch (value) {
            case "一" -> "1";
            case "二" -> "2";
            case "三" -> "3";
            case "四" -> "4";
            case "五" -> "5";
            case "六" -> "6";
            case "七" -> "7";
            case "八" -> "8";
            case "九" -> "9";
            case "十" -> "10";
            case "十一" -> "11";
            case "十二" -> "12";
            case "十三" -> "13";
            case "十四" -> "14";
            case "十五" -> "15";
            case "十六" -> "16";
            case "十七" -> "17";
            case "十八" -> "18";
            case "十九" -> "19";
            case "二十" -> "20";
            default -> "";
        };
    }

    private static int modeCode(String mode) {
        return switch (normalizeVehicleMode(mode, false)) {
            case "bus" -> 0;
            case "subway" -> 1;
            default -> 2;
        };
    }

    private static String modeFromCode(int code) {
        return switch (code) {
            case 0 -> "bus";
            case 1 -> "subway";
            case 2 -> "car";
            default -> throw new IllegalArgumentException("未知轨迹模式编码: " + code);
        };
    }

    private static int largeTrajectoryParallelism() {
        int fallback = Math.max(1, Math.min(16, ModelProcessingPool.parallelism()));
        return positiveIntSetting("gjcxfzksh.events.workers", "GJCXFZKSH_EVENTS_WORKERS", fallback);
    }

    private static int trajectoryQueueSize() {
        return positiveIntSetting(
                "gjcxfzksh.events.worker.queue-size",
                "GJCXFZKSH_EVENTS_WORKER_QUEUE_SIZE",
                TRAJECTORY_QUEUE_DEFAULT_SIZE
        );
    }

    private static int trajectoryMaxOpenChunksPerWorker() {
        return positiveIntSetting(
                "gjcxfzksh.events.worker.max-open-chunks",
                "GJCXFZKSH_EVENTS_WORKER_MAX_OPEN_CHUNKS",
                TRAJECTORY_MAX_OPEN_CHUNKS_DEFAULT
        );
    }

    private static int positiveIntSetting(String property, String env, int fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(env);
        }
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed <= 0) throw new NumberFormatException("必须大于 0");
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(property + "/" + env + " 必须是正整数: " + value, e);
        }
    }

    private static class BinaryTrajectorySegment {
        private final float startTime;
        private final float endTime;
        private final double startX;
        private final double startY;
        private final double endX;
        private final double endY;
        private final int modeCode;
        private final int vehicleIndex;
        private final float distanceMeters;

        private BinaryTrajectorySegment(
                float startTime,
                float endTime,
                double startX,
                double startY,
                double endX,
                double endY,
                int modeCode,
                int vehicleIndex,
                float distanceMeters
        ) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.startX = startX;
            this.startY = startY;
            this.endX = endX;
            this.endY = endY;
            this.modeCode = modeCode;
            this.vehicleIndex = vehicleIndex;
            this.distanceMeters = distanceMeters;
        }
    }

    private static final class CachedTrajectoryLightManifest {
        private final long modified;
        private final long size;
        private final String fileKey;
        private final Map<String, Object> manifest;

        private CachedTrajectoryLightManifest(
                long modified,
                long size,
                String fileKey,
                Map<String, Object> manifest
        ) {
            this.modified = modified;
            this.size = size;
            this.fileKey = fileKey;
            this.manifest = manifest;
        }

        private static CachedTrajectoryLightManifest of(
                BasicFileAttributes attributes,
                Map<String, Object> manifest
        ) {
            return new CachedTrajectoryLightManifest(
                    attributes.lastModifiedTime().toMillis(),
                    attributes.size(),
                    String.valueOf(attributes.fileKey()),
                    manifest
            );
        }

        private boolean matches(BasicFileAttributes attributes) {
            return modified == attributes.lastModifiedTime().toMillis()
                    && size == attributes.size()
                    && fileKey.equals(String.valueOf(attributes.fileKey()));
        }
    }

    private static final class SpatialTileKey implements Comparable<SpatialTileKey> {
        private final int x;
        private final int y;

        private SpatialTileKey(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public int compareTo(SpatialTileKey other) {
            int byX = Integer.compare(x, other.x);
            return byX != 0 ? byX : Integer.compare(y, other.y);
        }

        @Override
        public boolean equals(Object value) {
            return value instanceof SpatialTileKey other && x == other.x && y == other.y;
        }

        @Override
        public int hashCode() {
            return 31 * x + y;
        }
    }

    private static final class SpatialIndexEntry {
        private final SpatialTileKey key;
        private final int offset;
        private long compressedOffset;
        private int compressedBytes;
        private final int count;
        private final float minX;
        private final float minY;
        private final float maxX;
        private final float maxY;
        private int written;

        private SpatialIndexEntry(
                SpatialTileKey key,
                int offset,
                int count,
                float minX,
                float minY,
                float maxX,
                float maxY
        ) {
            this.key = key;
            this.offset = offset;
            this.count = count;
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }

        private SpatialIndexEntry(
                SpatialTileKey key,
                long compressedOffset,
                int compressedBytes,
                int count,
                float minX,
                float minY,
                float maxX,
                float maxY
        ) {
            this(key, 0, count, minX, minY, maxX, maxY);
            this.compressedOffset = compressedOffset;
            this.compressedBytes = compressedBytes;
        }
    }

    private static final class SpatialTileAggregate {
        private int count;
        private float minX = Float.POSITIVE_INFINITY;
        private float minY = Float.POSITIVE_INFINITY;
        private float maxX = Float.NEGATIVE_INFINITY;
        private float maxY = Float.NEGATIVE_INFINITY;

        private void add(float startX, float startY, float endX, float endY) {
            count++;
            minX = Math.min(minX, Math.min(startX, endX));
            minY = Math.min(minY, Math.min(startY, endY));
            maxX = Math.max(maxX, Math.max(startX, endX));
            maxY = Math.max(maxY, Math.max(startY, endY));
        }

        private SpatialIndexEntry toIndexEntry(SpatialTileKey key, int offset) {
            return new SpatialIndexEntry(key, offset, count, minX, minY, maxX, maxY);
        }
    }

    private static final class SpatialContainerIndex {
        private final int version;
        private final int recordStride;
        private final double originX;
        private final double originY;
        private final List<SpatialIndexEntry> entries;

        private SpatialContainerIndex(
                int version,
                int recordStride,
                double originX,
                double originY,
                List<SpatialIndexEntry> entries
        ) {
            this.version = version;
            this.recordStride = recordStride;
            this.originX = originX;
            this.originY = originY;
            this.entries = entries;
        }
    }

    private record CachedTrajectoryDictionaries(String signature, TrajectoryDictionaries value) {
    }

    private record TrajectoryArtifactFingerprint(
            String path,
            long size,
            long modified,
            String fileKey
    ) {
    }

    private record TrajectorySpatialSelectionKey(
            String modelPath,
            String cacheGeneration,
            int chunkStart,
            String visibility,
            boolean hasBounds,
            long minX,
            long minY,
            long maxX,
            long maxY,
            TrajectoryArtifactFingerprint index,
            TrajectoryArtifactFingerprint container
    ) {
    }

    private record CachedTrajectorySpatialSelection(
            int chunkStart,
            byte[][] playbackWindows,
            int[] playbackRowCounts,
            double originX,
            double originY
    ) {
        private long estimatedBytes() {
            long bytes = 192L + playbackRowCounts.length * Integer.BYTES;
            for (byte[] window : playbackWindows) bytes += 16L + window.length;
            return bytes;
        }
    }

    private static final class TrajectorySpatialSelectionAccumulator {
        private static final int PLAYBACK_WINDOWS_PER_CHUNK =
                TRAJECTORY_CHUNK_SECONDS / TRAJECTORY_PLAYBACK_WINDOW_SECONDS;

        private final int chunkStart;
        private final double originX;
        private final double originY;
        private final ByteArrayOutputStream[] rows =
                new ByteArrayOutputStream[PLAYBACK_WINDOWS_PER_CHUNK];
        private final IntOpenHashSet[] vehicles = new IntOpenHashSet[PLAYBACK_WINDOWS_PER_CHUNK];
        private final int[] rowCounts = new int[PLAYBACK_WINDOWS_PER_CHUNK];

        private TrajectorySpatialSelectionAccumulator(int chunkStart, double originX, double originY) {
            this.chunkStart = chunkStart;
            this.originX = originX;
            this.originY = originY;
            for (int index = 0; index < PLAYBACK_WINDOWS_PER_CHUNK; index++) {
                rows[index] = new ByteArrayOutputStream(1024 * 1024);
                vehicles[index] = new IntOpenHashSet();
            }
        }

        private void append(
                ByteBuffer source,
                int sourceRowCount,
                String normalizedVisibility,
                boolean hasBounds,
                Double requestedMinX,
                Double requestedMinY,
                Double requestedMaxX,
                Double requestedMaxY,
                double responseOriginX,
                double responseOriginY
        ) throws IOException {
            byte[] row = new byte[TRAJECTORY_BINARY_STRIDE * Float.BYTES];
            ByteBuffer record = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN);
            for (int rowIndex = 0; rowIndex < sourceRowCount; rowIndex++) {
                source.get(row);
                record.clear();
                float startTime = record.getFloat();
                float endTime = record.getFloat();
                float startX = record.getFloat();
                float startY = record.getFloat();
                float endX = record.getFloat();
                float endY = record.getFloat();
                int modeCode = Math.round(record.getFloat());
                int vehicleIndex = record.getInt();
                record.getFloat();
                if ("public".equals(normalizedVisibility) && modeCode == 2) continue;
                if ("private".equals(normalizedVisibility) && modeCode != 2) continue;
                if (hasBounds) {
                    double segmentMinX = responseOriginX + Math.min(startX, endX);
                    double segmentMaxX = responseOriginX + Math.max(startX, endX);
                    double segmentMinY = responseOriginY + Math.min(startY, endY);
                    double segmentMaxY = responseOriginY + Math.max(startY, endY);
                    if (segmentMaxX < requestedMinX || segmentMinX > requestedMaxX
                            || segmentMaxY < requestedMinY || segmentMinY > requestedMaxY) continue;
                }
                for (int windowIndex = 0; windowIndex < PLAYBACK_WINDOWS_PER_CHUNK; windowIndex++) {
                    int windowStart = chunkStart
                            + windowIndex * TRAJECTORY_PLAYBACK_WINDOW_SECONDS;
                    int windowEnd = windowStart + TRAJECTORY_PLAYBACK_WINDOW_SECONDS;
                    if (!(startTime < windowEnd && endTime > windowStart)) continue;
                    rows[windowIndex].write(row);
                    rowCounts[windowIndex]++;
                    vehicles[windowIndex].add(vehicleIndex);
                }
            }
        }

        private CachedTrajectorySpatialSelection finish() throws IOException {
            byte[][] playbackWindows = new byte[PLAYBACK_WINDOWS_PER_CHUNK][];
            for (int index = 0; index < PLAYBACK_WINDOWS_PER_CHUNK; index++) {
                int selectionStart = chunkStart + index * TRAJECTORY_PLAYBACK_WINDOW_SECONDS;
                ByteArrayOutputStream response = new ByteArrayOutputStream(
                        TRAJECTORY_BINARY_HEADER_BYTES + rows[index].size());
                writeTrajectoryBinaryHeader(
                        response,
                        selectionStart,
                        selectionStart + TRAJECTORY_PLAYBACK_WINDOW_SECONDS - 1,
                        rowCounts[index],
                        vehicles[index].size(),
                        rowCounts[index] * 2,
                        originX,
                        originY,
                        TRAJECTORY_PLAYBACK_WINDOW_SECONDS
                );
                rows[index].writeTo(response);
                playbackWindows[index] = response.toByteArray();
            }
            return new CachedTrajectorySpatialSelection(
                    chunkStart,
                    playbackWindows,
                    rowCounts.clone(),
                    originX,
                    originY
            );
        }
    }

    private static final class TrajectoryDictionaries {
        private final double originX;
        private final double originY;
        private final float[] fromX;
        private final float[] fromY;
        private final float[] toX;
        private final float[] toY;
        private final float[] lengths;
        private final byte[] modes;

        private TrajectoryDictionaries(
                double originX,
                double originY,
                float[] fromX,
                float[] fromY,
                float[] toX,
                float[] toY,
                float[] lengths,
                byte[] modes
        ) {
            this.originX = originX;
            this.originY = originY;
            this.fromX = fromX;
            this.fromY = fromY;
            this.toX = toX;
            this.toY = toY;
            this.lengths = lengths;
            this.modes = modes;
        }

        private int mode(int vehicleIndex) {
            if (vehicleIndex < 0 || vehicleIndex >= modes.length) return -1;
            int value = Byte.toUnsignedInt(modes[vehicleIndex]);
            return value == 255 ? -1 : value;
        }

        private VehicleSegment compactSegment(
                int linkIndex,
                int originalStart,
                int originalEnd,
                int chunkStart
        ) {
            if (linkIndex < 0 || linkIndex >= fromX.length || originalEnd <= originalStart) return null;
            return new VehicleSegment(
                    originalStart,
                    originalEnd,
                    roundCoord(originX + fromX[linkIndex]),
                    roundCoord(originY + fromY[linkIndex]),
                    roundCoord(originX + toX[linkIndex]),
                    roundCoord(originY + toY[linkIndex]),
                    lengths[linkIndex]
            ).clip(chunkStart, chunkStart + TRAJECTORY_CHUNK_SECONDS);
        }

        private long estimatedBytes() {
            return 96L + modes.length
                    + (long) (fromX.length + fromY.length + toX.length + toY.length + lengths.length) * Float.BYTES;
        }
    }

    private static final class TrajectoryGlobalStatsAccumulator {
        private final int chunkStart;
        private final int[][] counts = new int[3][TRAJECTORY_CHUNK_SECONDS];
        private final double[][] speedSums = new double[3][TRAJECTORY_CHUNK_SECONDS];
        private final int[][] speedCounts = new int[3][TRAJECTORY_CHUNK_SECONDS];

        private TrajectoryGlobalStatsAccumulator(int chunkStart) {
            this.chunkStart = normalizeChunkStart(chunkStart);
        }

        private void add(float startTime, float endTime, int modeCode, float distanceMeters) {
            if (modeCode < 0 || modeCode >= counts.length || !(endTime > startTime)) return;
            double speed = distanceMeters / Math.max(0.001, endTime - startTime) * 3.6;
            int first = Math.max(chunkStart, (int) Math.ceil(startTime));
            int lastExclusive = Math.min(
                    chunkStart + TRAJECTORY_CHUNK_SECONDS,
                    (int) Math.ceil(endTime)
            );
            for (int second = first; second < lastExclusive; second++) {
                int index = second - chunkStart;
                counts[modeCode][index]++;
                if (Double.isFinite(speed) && speed > 0.0 && speed < 180.0) {
                    speedSums[modeCode][index] += speed;
                    speedCounts[modeCode][index]++;
                }
            }
        }

        private List<List<Object>> payload() {
            List<List<Object>> rows = new ArrayList<>(TRAJECTORY_CHUNK_SECONDS);
            for (int second = 0; second < TRAJECTORY_CHUNK_SECONDS; second++) {
                List<Object> row = new ArrayList<>(10);
                row.add(chunkStart + second);
                for (int mode = 0; mode < 3; mode++) row.add(counts[mode][second]);
                for (int mode = 0; mode < 3; mode++) row.add(round2(speedSums[mode][second]));
                for (int mode = 0; mode < 3; mode++) row.add(speedCounts[mode][second]);
                rows.add(row);
            }
            return rows;
        }
    }

    private static class TransitVehicleMeta {
        private final String mode;
        private final String lineId;
        private final String routeId;

        private TransitVehicleMeta(String mode, String lineId, String routeId) {
            this.mode = mode;
            this.lineId = lineId;
            this.routeId = routeId;
        }
    }

    private static class TrajectoryMeta {
        private final Map<String, TransitVehicleMeta> transitVehicles;
        private final Map<String, RouteMeta> routes;
        private final Map<String, CarTripMeta> carTrips;
        // "lineId::routeId" → meta。TransitDriverStarts 事件动态改派车辆班次时按复合键查找，
        // 避免 routeId 跨线路重复导致的归属错误。
        private final Map<String, TransitVehicleMeta> byLineRoute;

        private TrajectoryMeta(
                Map<String, TransitVehicleMeta> transitVehicles,
                Map<String, RouteMeta> routes,
                Map<String, CarTripMeta> carTrips,
                Map<String, TransitVehicleMeta> byLineRoute
        ) {
            this.transitVehicles = transitVehicles;
            this.routes = routes;
            this.carTrips = carTrips;
            this.byLineRoute = byLineRoute;
        }
    }

    private static class RouteMeta {
        private final String mode;
        private final String lineId;
        private final String lineName;
        private final String routeId;
        private final String routeName;
        private final List<Map<String, Object>> stops;
        private final double firstTime;
        private final double lastTime;

        private RouteMeta(
                String mode,
                String lineId,
                String lineName,
                String routeId,
                String routeName,
                List<Map<String, Object>> stops,
                double firstTime,
                double lastTime
        ) {
            this.mode = mode;
            this.lineId = lineId;
            this.lineName = lineName;
            this.routeId = routeId;
            this.routeName = routeName;
            this.stops = stops;
            this.firstTime = firstTime;
            this.lastTime = lastTime;
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("mode", mode);
            payload.put("lineId", lineId);
            payload.put("lineName", lineName);
            payload.put("routeId", routeId);
            payload.put("routeName", routeName);
            payload.put("firstTime", firstTime);
            payload.put("lastTime", lastTime);
            payload.put("stops", stops);
            return payload;
        }
    }

    private static class CarTripMeta {
        private final Map<String, Object> origin;
        private final Map<String, Object> destination;
        private final String purpose;

        private CarTripMeta(Map<String, Object> origin, Map<String, Object> destination, String purpose) {
            this.origin = origin;
            this.destination = destination;
            this.purpose = purpose;
        }
    }

    private enum RawEventKind {
        LINK_ENTER,
        LINK_LEAVE,
        VEHICLE_ENTERS_TRAFFIC,
        VEHICLE_LEAVES_TRAFFIC,
        VEHICLE_ARRIVES_AT_FACILITY,
        VEHICLE_DEPARTS_AT_FACILITY,
        PERSON_ENTERS_VEHICLE,
        PERSON_LEAVES_VEHICLE,
        TRANSIT_DRIVER_STARTS,
        STOP
    }

    private static class RawEventTask {
        private static final RawEventTask STOP = new RawEventTask(RawEventKind.STOP, 0.0, null, null, null, null, null);

        private final RawEventKind kind;
        private final double time;
        private final String vehicleId;
        private final String linkId;
        private final String networkMode;
        private final String personId;
        private final String facilityId;
        // TRANSIT_DRIVER_STARTS 专用：当前班次的线路/交路/班次
        private final String transitLineId;
        private final String transitRouteId;
        private final String departureId;

        private RawEventTask(
                RawEventKind kind,
                double time,
                String vehicleId,
                String linkId,
                String networkMode,
                String personId,
                String facilityId
        ) {
            this(kind, time, vehicleId, linkId, networkMode, personId, facilityId, null, null, null);
        }

        private RawEventTask(
                RawEventKind kind,
                double time,
                String vehicleId,
                String linkId,
                String networkMode,
                String personId,
                String facilityId,
                String transitLineId,
                String transitRouteId,
                String departureId
        ) {
            this.kind = kind;
            this.time = time;
            this.vehicleId = vehicleId;
            this.linkId = linkId;
            this.networkMode = networkMode;
            this.personId = personId;
            this.facilityId = facilityId;
            this.transitLineId = transitLineId;
            this.transitRouteId = transitRouteId;
            this.departureId = departureId;
        }
    }

    private static class ParallelLargeTrajectoryStreamHandler implements FastEventReader.Handler {
        private final MatsimData data;
        private final TrajectoryMeta trajectoryMeta;
        private final MatsimLinkGeometryIndex linkGeometry;
        private final ConcurrentMap<String, Integer> vehicleIndexById;
        private final java.util.concurrent.atomic.AtomicInteger vehicleIndexCounter;
        private final Path trajectorySpoolDir;
        private final ProgressThrottle progress;
        private final List<LargeTrajectoryWorker> workers = new ArrayList<>();
        private final List<BlockingQueue<RawEventTask>> queues = new ArrayList<>();
        private final List<Thread> threads = new ArrayList<>();
        private final AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        private final AtomicBoolean stopped = new AtomicBoolean(false);

        private ParallelLargeTrajectoryStreamHandler(MatsimData data, TrajectoryMeta trajectoryMeta, BuildProgress progress) throws Exception {
            this.data = data;
            this.trajectoryMeta = trajectoryMeta;
            this.progress = new ProgressThrottle(progress);

            // data.getNetwork() 在大模型下是公交子路网；轨迹必须以原始完整 network 为准，
            // 否则所有普通道路上的小汽车事件都会被静默丢弃。
            this.linkGeometry = MatsimLinkGeometryIndex.load(data);
            // 30s 原始分块是构建期短命工件，不应写到最终缓存盘。
            // ExFAT 常见 512KiB 分配块，数千个 worker 临时文件会额外占用数 GiB
            // 并显著拖慢 events 解析。在系统临时盘流式落盘，完成后只把
            // Zstd 规范工件发布到配置的缓存目录。
            this.trajectorySpoolDir = Files.createTempDirectory(
                    "gjcxfzksh-trajectory-" + Integer.toUnsignedString(data.getName().hashCode()) + "-");
            log.info("轨迹流式临时盘: model={}, path={}", data.getName(), trajectorySpoolDir);
            Map<String, String> departureByVehicle = departureByVehicle(data.getSchedule());
            // 全局顺序车辆索引：原实现用 hashCode%16M 作去重键，车辆规模大时生日碰撞导致
            // totalVehicles/vehicleCountByMode 少计、不同车辆的占用曲线被错误合并。
            // 顺序自增保证唯一，且远小于 2^24，float 编码仍无损。
            this.vehicleIndexById = new ConcurrentHashMap<>();
            this.vehicleIndexCounter = new java.util.concurrent.atomic.AtomicInteger();

            int workerCount = largeTrajectoryParallelism();
            int queueSize = trajectoryQueueSize();
            log.info("大模型events并行解析: model={}, workers={}, queueSize={}", data.getName(), workerCount, queueSize);
            for (int i = 0; i < workerCount; i++) {
                BlockingQueue<RawEventTask> queue = new ArrayBlockingQueue<>(queueSize);
                LargeTrajectoryWorker worker = new LargeTrajectoryWorker(
                        i,
                        data,
                        trajectorySpoolDir,
                        trajectoryMeta,
                        linkGeometry,
                        departureByVehicle,
                        vehicleIndexById,
                        vehicleIndexCounter,
                        this.progress,
                        queue,
                        workerFailure
                );
                Thread thread = new Thread(worker, "events-trajectory-worker-" + i);
                thread.setDaemon(true);
                queues.add(queue);
                workers.add(worker);
                threads.add(thread);
                thread.start();
            }
        }

        @Override
        public void handle(String eventType, double time, FastEventReader.Attributes attributes) throws Exception {
            throwIfWorkerFailed();
            RawEventTask task = task(eventType, time, attributes);
            if (task == null) {
                return;
            }
            BlockingQueue<RawEventTask> queue = queues.get(partition(task.vehicleId));
            while (!queue.offer(task, 100, TimeUnit.MILLISECONDS)) {
                throwIfWorkerFailed();
            }
        }

        private RawEventTask task(String eventType, double time, FastEventReader.Attributes attributes) {
            return switch (eventType) {
                case LinkEnterEvent.EVENT_TYPE -> {
                    String vehicleId = attributes.value(LinkEnterEvent.ATTRIBUTE_VEHICLE);
                    String linkId = attributes.value(LinkEnterEvent.ATTRIBUTE_LINK);
                    yield vehicleId == null || linkId == null
                            ? null
                            : new RawEventTask(RawEventKind.LINK_ENTER, time, vehicleId, linkId, null, null, null);
                }
                case LinkLeaveEvent.EVENT_TYPE -> {
                    String vehicleId = attributes.value(LinkLeaveEvent.ATTRIBUTE_VEHICLE);
                    String linkId = attributes.value(LinkLeaveEvent.ATTRIBUTE_LINK);
                    yield vehicleId == null || linkId == null
                            ? null
                            : new RawEventTask(RawEventKind.LINK_LEAVE, time, vehicleId, linkId, null, null, null);
                }
                case VehicleEntersTrafficEvent.EVENT_TYPE -> {
                    String vehicleId = attributes.value(VehicleEntersTrafficEvent.ATTRIBUTE_VEHICLE);
                    String linkId = attributes.value(VehicleEntersTrafficEvent.ATTRIBUTE_LINK);
                    yield vehicleId == null || linkId == null
                            ? null
                            : new RawEventTask(
                            RawEventKind.VEHICLE_ENTERS_TRAFFIC,
                            time,
                            vehicleId,
                            linkId,
                            attributes.value(VehicleEntersTrafficEvent.ATTRIBUTE_NETWORKMODE),
                            null,
                            null
                    );
                }
                case VehicleLeavesTrafficEvent.EVENT_TYPE -> {
                    String vehicleId = attributes.value(VehicleLeavesTrafficEvent.ATTRIBUTE_VEHICLE);
                    String linkId = attributes.value(VehicleLeavesTrafficEvent.ATTRIBUTE_LINK);
                    yield vehicleId == null || linkId == null
                            ? null
                            : new RawEventTask(
                            RawEventKind.VEHICLE_LEAVES_TRAFFIC,
                            time,
                            vehicleId,
                            linkId,
                            attributes.value(VehicleLeavesTrafficEvent.ATTRIBUTE_NETWORKMODE),
                            null,
                            null
                    );
                }
                case VehicleArrivesAtFacilityEvent.EVENT_TYPE -> {
                    String vehicleId = attributes.value(VehicleArrivesAtFacilityEvent.ATTRIBUTE_VEHICLE);
                    String facilityId = attributes.value(VehicleArrivesAtFacilityEvent.ATTRIBUTE_FACILITY);
                    yield vehicleId == null || facilityId == null
                            ? null
                            : new RawEventTask(RawEventKind.VEHICLE_ARRIVES_AT_FACILITY, time, vehicleId, null, null, null, facilityId);
                }
                case VehicleDepartsAtFacilityEvent.EVENT_TYPE -> {
                    String vehicleId = attributes.value(VehicleDepartsAtFacilityEvent.ATTRIBUTE_VEHICLE);
                    String facilityId = attributes.value(VehicleDepartsAtFacilityEvent.ATTRIBUTE_FACILITY);
                    yield vehicleId == null
                            ? null
                            : new RawEventTask(RawEventKind.VEHICLE_DEPARTS_AT_FACILITY, time, vehicleId, null, null, null, facilityId);
                }
                case PersonEntersVehicleEvent.EVENT_TYPE -> {
                    String personId = attributes.value(PersonEntersVehicleEvent.ATTRIBUTE_PERSON);
                    String vehicleId = attributes.value(PersonEntersVehicleEvent.ATTRIBUTE_VEHICLE);
                    yield vehicleId == null || personId == null
                            ? null
                            : new RawEventTask(RawEventKind.PERSON_ENTERS_VEHICLE, time, vehicleId, null, null, personId, null);
                }
                case PersonLeavesVehicleEvent.EVENT_TYPE -> {
                    String personId = attributes.value(PersonLeavesVehicleEvent.ATTRIBUTE_PERSON);
                    String vehicleId = attributes.value(PersonLeavesVehicleEvent.ATTRIBUTE_VEHICLE);
                    yield vehicleId == null || personId == null
                            ? null
                            : new RawEventTask(RawEventKind.PERSON_LEAVES_VEHICLE, time, vehicleId, null, null, personId, null);
                }
                case TransitDriverStartsEvent.EVENT_TYPE -> {
                    String driverId = attributes.value(TransitDriverStartsEvent.ATTRIBUTE_DRIVER_ID);
                    String vehicleId = attributes.value(TransitDriverStartsEvent.ATTRIBUTE_VEHICLE_ID);
                    yield vehicleId == null
                            ? null
                            : new RawEventTask(RawEventKind.TRANSIT_DRIVER_STARTS, time, vehicleId, null, null,
                            driverId, null,
                            attributes.value(TransitDriverStartsEvent.ATTRIBUTE_TRANSIT_LINE_ID),
                            attributes.value(TransitDriverStartsEvent.ATTRIBUTE_TRANSIT_ROUTE_ID),
                            attributes.value(TransitDriverStartsEvent.ATTRIBUTE_DEPARTURE_ID));
                }
                default -> null;
            };
        }

        private int partition(String vehicleId) {
            return Math.floorMod(vehicleId.hashCode(), queues.size());
        }

        private Map<String, Object> finish() throws Exception {
            stopWorkers(false);
            long missingLinkEvents = workers.stream().mapToLong(worker -> worker.missingLinkEvents).sum();
            if (missingLinkEvents > 0) {
                Set<String> samples = new LinkedHashSet<>();
                workers.forEach(worker -> samples.addAll(worker.missingLinkSamples));
                throw new IllegalStateException("events 引用了完整 network 中不存在的 link，已拒绝发布不完整轨迹: count="
                        + missingLinkEvents + ", samples=" + samples.stream().limit(12).toList());
            }
            writeMergedPersonTracks();

            IntOpenHashSet globalTransitVehicleMeta = new IntOpenHashSet();
            Map<Integer, CombinedChunkAccumulator> combinedChunks = new TreeMap<>();
            Map<String, Long> vehicleCountByMode = emptyLongModeMap();
            Int2IntOpenHashMap globalVehicleModes = new Int2IntOpenHashMap();
            globalVehicleModes.defaultReturnValue(-1);
            Map<String, Double> distanceByMode = emptyDoubleModeMap();
            Map<Integer, long[]> passengerBins = new TreeMap<>();
            Map<String, Integer> routeBoardings = new HashMap<>();
            List<Map<String, Object>> vehicleMetaPayloads = new ArrayList<>();
            long passengerBoardings = 0;
            long pointCount = 0;
            int minTime = Integer.MAX_VALUE;
            int maxTime = Integer.MIN_VALUE;

            for (LargeTrajectoryWorker worker : workers) {
                // 事件始终按 vehicleId 哈希到唯一 worker，各 worker 的车辆集合天然不相交，
                // 可直接求和，无需在 finish 阶段再构造一份全局大集合。
                worker.seenVehicleModes.forEach((vehicleIndex, encodedMode) -> {
                    globalVehicleModes.put(vehicleIndex.intValue(), encodedMode.intValue());
                    vehicleCountByMode.merge(modeFromCode(encodedMode), 1L, Long::sum);
                });
                worker.distanceByMode.forEach((mode, distance) -> distanceByMode.merge(mode, distance, Double::sum));
                worker.passengerBins.forEach((time, counts) -> {
                    long[] target = passengerBins.computeIfAbsent(time, ignored -> new long[4]);
                    target[0] += counts[0];
                    target[1] += counts[1];
                    target[2] += counts[2];
                    target[3] += counts[3];
                });
                worker.routeBoardings.forEach((routeId, count) -> routeBoardings.merge(routeId, count, Integer::sum));
                for (Map<String, Object> item : worker.vehicleMetaPayloads) {
                    Object index = item.get("index");
                    if (index instanceof Number number && globalTransitVehicleMeta.add(number.intValue())) {
                        // 该车上下车事件与其 meta 同属一个 worker（事件按 vehicleId 分区），直接附加按时刻的占用增量。
                        java.util.TreeMap<Integer, Integer> occupancy = worker.passengerEventsByVehicle.get(number.intValue());
                        if (occupancy != null && !occupancy.isEmpty()) {
                            List<List<Object>> rows = new ArrayList<>(occupancy.size());
                            occupancy.forEach((eventTime, delta) -> {
                                if (delta != 0) {
                                    rows.add(List.of(eventTime, delta));
                                }
                            });
                            if (!rows.isEmpty()) {
                                item.put("passengerEvents", rows);
                            }
                        }
                        vehicleMetaPayloads.add(item);
                    }
                }
                for (ChunkAccumulator chunk : worker.chunks.values()) {
                    combinedChunks.computeIfAbsent(chunk.start, CombinedChunkAccumulator::new).add(chunk);
                }
                passengerBoardings += worker.passengerBoardings;
                pointCount += worker.pointCount;
                minTime = Math.min(minTime, worker.minTime);
                maxTime = Math.max(maxTime, worker.maxTime);
            }

            writeTrajectoryDictionaries(
                    data, linkGeometry, vehicleIndexById, vehicleIndexCounter.get(), globalVehicleModes);
            List<Map<String, Object>> chunkPayloads = new ArrayList<>();
            for (CombinedChunkAccumulator chunk : combinedChunks.values()) {
                chunkPayloads.add(chunk.finish(data, linkGeometry, globalVehicleModes));
            }
            cleanupTrajectorySpool();

            if (minTime == Integer.MAX_VALUE || maxTime == Integer.MIN_VALUE) {
                minTime = 0;
                maxTime = 86400;
            }
            distanceByMode.replaceAll((mode, distance) -> round2(distance / 1000.0));
            long totalVehicles = vehicleCountByMode.values().stream().mapToLong(Long::longValue).sum();
            progress.finish(maxTime, (int) Math.min(Integer.MAX_VALUE, totalVehicles));

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("totalVehicles", totalVehicles);
            summary.put("vehicleCountByMode", vehicleCountByMode);
            summary.put("totalPassengerBoardings", passengerBoardings);
            summary.put("distanceKmByMode", distanceByMode);
            summary.put("pointCount", pointCount);
            summary.put("routeBoardings", topRouteBoardings(routeBoardings));
            summary.put("chunks", chunkPayloads);

            Map<String, Object> quality = new LinkedHashMap<>();
            quality.put("complete", true);
            quality.put("fullNetworkLinks", linkGeometry.size());
            quality.put("networkCrs", linkGeometry.sourceCrs());
            quality.put("linkEvents", workers.stream().mapToLong(worker -> worker.linkEvents).sum());
            quality.put("missingLinkEvents", 0);
            quality.put("compactRecordBytes", TRAJECTORY_COMPACT_STRIDE * Integer.BYTES);
            quality.put("linkGeometryDictionary", TRAJECTORY_LINK_DICTIONARY_FILE);
            quality.put("vehicleMetadataDictionary", TRAJECTORY_VEHICLE_DICTIONARY_FILE);

            Map<String, Object> timeRange = new LinkedHashMap<>();
            timeRange.put("min", minTime);
            timeRange.put("max", maxTime);

            Map<String, Object> metaPayload = new LinkedHashMap<>();
            Map<String, Object> routes = new LinkedHashMap<>();
            trajectoryMeta.routes.forEach((routeId, route) -> routes.put(routeId, route.toPayload()));
            metaPayload.put("routes", routes);
            metaPayload.put("vehicles", vehicleMetaPayloads);

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("status", "ready");
            manifest.put("cacheVersion", TRAJECTORY_CACHE_VERSION);
            manifest.put("chunkSeconds", TRAJECTORY_PLAYBACK_WINDOW_SECONDS);
            manifest.put("storageChunkSeconds", TRAJECTORY_CHUNK_SECONDS);
            manifest.put("spatial", trajectorySpatialInfo());
            manifest.put("generatedAt", System.currentTimeMillis());
            putTrajectoryIdentity(manifest, data);
            manifest.put("timeRange", timeRange);
            manifest.put("summary", summary);
            manifest.put("quality", quality);
            manifest.put("passengerSeries", passengerSeriesPayload(passengerBins));
            manifest.put("meta", metaPayload);
            manifest.put("vehicles", List.of());
            writeTrajectoryManifest(data, manifest);
            return manifest;
        }

        private void abort() {
            try {
                stopWorkers(true);
            } catch (Exception ignored) {
            }
            for (LargeTrajectoryWorker worker : workers) {
                worker.cleanupTempFiles();
            }
            cleanupTrajectorySpool();
            try {
                Files.deleteIfExists(personTracksPath(data).resolveSibling(PERSON_TRACK_FILE + ".tmp"));
            } catch (Exception ignored) {
            }
        }

        private void stopWorkers(boolean abort) throws Exception {
            boolean forceAbort = abort || workerFailure.get() != null;
            if (stopped.compareAndSet(false, true)) {
                for (BlockingQueue<RawEventTask> queue : queues) {
                    if (forceAbort) {
                        queue.clear();
                    }
                    while (!queue.offer(RawEventTask.STOP, 100, TimeUnit.MILLISECONDS)) {
                        if (forceAbort || workerFailure.get() != null) {
                            queue.clear();
                            forceAbort = true;
                        }
                    }
                }
            }
            for (Thread thread : threads) {
                thread.join();
            }
            throwIfWorkerFailed();
        }

        private void throwIfWorkerFailed() {
            Throwable failure = workerFailure.get();
            if (failure != null) {
                throw new RuntimeException("并行解析events失败", failure);
            }
        }

        private void cleanupTrajectorySpool() {
            if (trajectorySpoolDir == null || !Files.exists(trajectorySpoolDir)) {
                return;
            }
            try (Stream<Path> paths = Files.walk(trajectorySpoolDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (Exception e) {
                log.warn("清理轨迹流式临时目录失败: path={}, error={}",
                        trajectorySpoolDir, e.getMessage());
            }
        }

        private void writeMergedPersonTracks() throws Exception {
            Files.createDirectories(personTrackCacheDir(data));
            Path tracksPath = personTracksPath(data);
            Path tmpPath = tracksPath.resolveSibling(PERSON_TRACK_FILE + ".tmp");
            long trackCount = 0;
            try (OutputStream out = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(tmpPath), TRAJECTORY_IO_BUFFER_BYTES))) {
                out.write("time\tenter\tpersonId\tlineId\trouteId\tvehicleId\tdepartureId\tfacilityId\n".getBytes(StandardCharsets.UTF_8));
                for (LargeTrajectoryWorker worker : workers) {
                    trackCount += worker.trackCount;
                    if (Files.exists(worker.personTrackPartPath)) {
                        Files.copy(worker.personTrackPartPath, out);
                    }
                }
            }
            try {
                Files.move(tmpPath, tracksPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(tmpPath, tracksPath, StandardCopyOption.REPLACE_EXISTING);
            }
            for (LargeTrajectoryWorker worker : workers) {
                Files.deleteIfExists(worker.personTrackPartPath);
            }

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("status", "ready");
            manifest.put("cacheVersion", PERSON_TRACK_CACHE_VERSION);
            manifest.put("generatedAt", System.currentTimeMillis());
            manifest.put("eventsFile", data.getOutfile().getEvents());
            manifest.put("eventsModified", lastModified(data.getOutfile().getEvents()));
            manifest.put("eventsSize", fileSize(data.getOutfile().getEvents()));
            manifest.put("eventsSignature", MatsimSourceFingerprint.signature(data.getOutfile().getEvents()));
            manifest.put("trackCount", trackCount);
            manifest.put("streamed", true);
            manifest.put("parallelism", workers.size());
            writeJsonAtomic(personTrackManifestPath(data), manifest);
            MatsimCachePaths.deleteOtherVersions(data, "pt-events-v", PERSON_TRACK_CACHE_VERSION);
        }

        private static Map<String, String> departureByVehicle(TransitSchedule schedule) {
            Map<String, String> result = new HashMap<>();
            schedule.getTransitLines().forEach((lineId, line) -> line.getRoutes().forEach((routeId, route) -> {
                route.getDepartures().forEach((departureId, departure) -> {
                    if (departure.getVehicleId() != null) {
                        result.put(departure.getVehicleId().toString(), departureId.toString());
                    }
                });
            }));
            return result;
        }

        private static Map<String, Integer> topRouteBoardings(Map<String, Integer> routeBoardings) {
            return routeBoardings.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(8)
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (oldValue, newValue) -> oldValue,
                            LinkedHashMap::new
                    ));
        }

        private static List<List<Object>> passengerSeriesPayload(Map<Integer, long[]> passengerBins) {
            List<List<Object>> result = new ArrayList<>(passengerBins.size());
            passengerBins.forEach((time, counts) -> result.add(List.of(time, counts[0], counts[1], counts[2], counts[3])));
            return result;
        }
    }

    private static class ProgressThrottle {
        private final BuildProgress progress;
        private final AtomicInteger maxTime = new AtomicInteger(0);
        private final AtomicInteger vehicleCount = new AtomicInteger(0);
        private final AtomicLong lastUpdate = new AtomicLong(0L);

        private ProgressThrottle(BuildProgress progress) {
            this.progress = progress;
        }

        private void markPoint(int time, int count) {
            if (progress == null) {
                return;
            }
            maxTime.accumulateAndGet(time, Math::max);
            vehicleCount.accumulateAndGet(count, Math::max);
            long now = System.currentTimeMillis();
            long previous = lastUpdate.get();
            if (now - previous >= 1000 && lastUpdate.compareAndSet(previous, now)) {
                progress.markPoint(maxTime.get(), vehicleCount.get());
            }
        }

        private void finish(int time, int count) {
            if (progress != null) {
                progress.markPoint(time, count);
            }
        }
    }

    private static class LargeTrajectoryWorker implements Runnable {

        private final int partition;
        private final MatsimData data;
        private final Path trajectorySpoolDir;
        private final TrajectoryMeta trajectoryMeta;
        private final MatsimLinkGeometryIndex linkGeometry;
        private final Map<String, String> departureByVehicle;
        // 跨 worker 共享的车辆顺序索引（唯一、float 安全）
        private final ConcurrentMap<String, Integer> vehicleIndexById;
        private final java.util.concurrent.atomic.AtomicInteger vehicleIndexCounter;
        private final ProgressThrottle progress;
        private final BlockingQueue<RawEventTask> queue;
        private final AtomicReference<Throwable> failure;
        private final Map<String, IndexedActiveLink> activeLinks = new HashMap<>();
        private final Map<String, String> currentFacilityByVehicle = new HashMap<>();
        // TransitDriverStarts 动态改派：当前班次的 meta / departure（车辆复用多班次时保证归属正确）
        private final Map<String, TransitVehicleMeta> currentMetaByVehicle = new HashMap<>();
        private final Map<String, String> currentDepartureByVehicle = new HashMap<>();
        // 公交司机 personId：司机的上下车事件不是客流（事件按 vehicleId 分区，司机与其车辆同 worker）
        private final Set<String> driverIds = new LinkedHashSet<>();
        private final Map<Integer, ChunkAccumulator> chunks = new LinkedHashMap<>();
        private final Map<Integer, long[]> passengerBins = new TreeMap<>();
        // 每辆公共交通车的上下车事件（按车辆索引聚合），用于右侧面板按时刻还原车内人数/满载率。
        // 事件按 vehicleId 分区，同一辆车的所有上下车都落在同一 worker，无需跨 worker 合并。
        private final Map<Integer, java.util.TreeMap<Integer, Integer>> passengerEventsByVehicle = new HashMap<>();
        private final Map<String, Integer> routeBoardings = new HashMap<>();
        private final Map<String, Double> distanceByMode = emptyDoubleModeMap();
        // fastutil-core 不包含 Int2ByteOpenHashMap；模式码本身只有 0/1/2，使用其提供的
        // Int2IntOpenHashMap 仍是紧凑的 primitive map，也避免完整 fastutil 依赖。
        private final Int2IntOpenHashMap seenVehicleModes = new Int2IntOpenHashMap();
        // 每车只记录最后计数的时间块；取代“每个块一个车辆 HashSet”，避免 30s 分块
        // 将长时段车辆成员关系在堆中复制数十份。
        private final Int2IntOpenHashMap lastCountedChunkByVehicle = new Int2IntOpenHashMap();
        private final IntOpenHashSet seenTransitVehicleMeta = new IntOpenHashSet();
        private final List<Map<String, Object>> vehicleMetaPayloads = new ArrayList<>();
        private final Path personTrackPartPath;
        private final BufferedWriter personTrackWriter;
        private long passengerBoardings = 0;
        private long trackCount = 0;
        private long pointCount = 0;
        private int chunkPruneCounter = 0;
        private int minTime = Integer.MAX_VALUE;
        private int maxTime = Integer.MIN_VALUE;
        private long linkEvents = 0;
        private long missingLinkEvents = 0;
        private final Set<String> missingLinkSamples = new LinkedHashSet<>();

        private LargeTrajectoryWorker(
                int partition,
                MatsimData data,
                Path trajectorySpoolDir,
                TrajectoryMeta trajectoryMeta,
                MatsimLinkGeometryIndex linkGeometry,
                Map<String, String> departureByVehicle,
                ConcurrentMap<String, Integer> vehicleIndexById,
                java.util.concurrent.atomic.AtomicInteger vehicleIndexCounter,
                ProgressThrottle progress,
                BlockingQueue<RawEventTask> queue,
                AtomicReference<Throwable> failure
        ) throws Exception {
            this.partition = partition;
            this.data = data;
            this.trajectorySpoolDir = trajectorySpoolDir;
            this.trajectoryMeta = trajectoryMeta;
            this.linkGeometry = linkGeometry;
            this.departureByVehicle = departureByVehicle;
            this.vehicleIndexById = vehicleIndexById;
            this.vehicleIndexCounter = vehicleIndexCounter;
            this.progress = progress;
            this.queue = queue;
            this.failure = failure;
            this.lastCountedChunkByVehicle.defaultReturnValue(Integer.MIN_VALUE);
            Files.createDirectories(personTrackCacheDir(data));
            this.personTrackPartPath = personTracksPath(data).resolveSibling(PERSON_TRACK_FILE + ".part-" + partition + ".tmp");
            this.personTrackWriter = new BufferedWriter(new OutputStreamWriter(
                    new BufferedOutputStream(Files.newOutputStream(personTrackPartPath), TRAJECTORY_IO_BUFFER_BYTES),
                    StandardCharsets.UTF_8
            ), TRAJECTORY_IO_BUFFER_BYTES);
        }

        @Override
        public void run() {
            try {
                while (true) {
                    RawEventTask task = queue.take();
                    if (task.kind == RawEventKind.STOP) {
                        break;
                    }
                    process(task);
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            } finally {
                closeQuietly();
            }
        }

        private void process(RawEventTask task) {
            switch (task.kind) {
                case LINK_ENTER, VEHICLE_ENTERS_TRAFFIC ->
                        startLink(task.vehicleId, task.linkId, task.time, task.networkMode);
                case LINK_LEAVE, VEHICLE_LEAVES_TRAFFIC ->
                        finishLink(task.vehicleId, task.linkId, task.time, task.networkMode);
                case VEHICLE_ARRIVES_AT_FACILITY ->
                        currentFacilityByVehicle.put(task.vehicleId, task.facilityId);
                case VEHICLE_DEPARTS_AT_FACILITY ->
                        currentFacilityByVehicle.remove(task.vehicleId);
                case PERSON_ENTERS_VEHICLE ->
                        writePassengerTrack(task.time, true, task.personId, task.vehicleId);
                case PERSON_LEAVES_VEHICLE ->
                        writePassengerTrack(task.time, false, task.personId, task.vehicleId);
                case TRANSIT_DRIVER_STARTS -> startTransitService(task);
                case STOP -> {
                }
            }
        }

        private void startTransitService(RawEventTask task) {
            if (task.personId != null) {
                driverIds.add(task.personId);
            }
            if (task.departureId != null) {
                currentDepartureByVehicle.put(task.vehicleId, task.departureId);
            }
            if (task.transitLineId != null && task.transitRouteId != null) {
                TransitVehicleMeta meta = trajectoryMeta.byLineRoute.get(task.transitLineId + "::" + task.transitRouteId);
                if (meta != null) {
                    currentMetaByVehicle.put(task.vehicleId, meta);
                }
            }
        }

        private void startLink(String vehicleId, String linkId, double rawTime, String networkMode) {
            linkEvents++;
            int geometryIndex = linkGeometry.find(linkId);
            if (geometryIndex < 0) {
                recordMissingLink(linkId);
                activeLinks.remove(vehicleId);
                return;
            }
            IndexedActiveLink active = activeLinks.get(vehicleId);
            int time = Math.max(0, (int) Math.round(rawTime));
            if (active != null && active.linkId.equals(linkId)) {
                return;
            }
            if (active != null && !active.linkId.equals(linkId)) {
                writeSegment(vehicleId, active, time, networkMode, active.geometryIndex);
            }
            activeLinks.put(vehicleId, new IndexedActiveLink(linkId, time, geometryIndex));
        }

        private void finishLink(String vehicleId, String linkId, double rawTime, String networkMode) {
            linkEvents++;
            int geometryIndex = linkGeometry.find(linkId);
            if (geometryIndex < 0) {
                recordMissingLink(linkId);
                activeLinks.remove(vehicleId);
                return;
            }
            int time = Math.max(0, (int) Math.round(rawTime));
            IndexedActiveLink active = activeLinks.get(vehicleId);
            if (active == null || !active.linkId.equals(linkId)) {
                active = new IndexedActiveLink(linkId, time, geometryIndex);
            }
            writeSegment(vehicleId, active, time, networkMode, geometryIndex);
            activeLinks.remove(vehicleId);
        }

        private void writeSegment(String vehicleId, IndexedActiveLink active, int endTime, String networkMode, int fallbackGeometryIndex) {
            if (active == null || endTime <= active.startTime) {
                return;
            }
            int geometryIndex = active.geometryIndex >= 0 ? active.geometryIndex : fallbackGeometryIndex;
            if (geometryIndex < 0) {
                return;
            }
            VehicleSegment segment = new VehicleSegment(
                    active.startTime,
                    endTime,
                    roundCoord(linkGeometry.fromX(geometryIndex)),
                    roundCoord(linkGeometry.fromY(geometryIndex)),
                    roundCoord(linkGeometry.toX(geometryIndex)),
                    roundCoord(linkGeometry.toY(geometryIndex)),
                    linkGeometry.lengthMeters(geometryIndex)
            );
            if (segment.distance < 0.01) {
                return;
            }

            String mode = vehicleMode(vehicleId, networkMode);
            int vehicleIndex = vehicleIndex(vehicleId);
            int encodedMode = modeCode(mode);
            if (!seenVehicleModes.containsKey(vehicleIndex)) {
                seenVehicleModes.put(vehicleIndex, encodedMode);
                addTransitVehicleMeta(vehicleId, vehicleIndex);
            }
            distanceByMode.merge(mode, segment.distance, Double::sum);
            minTime = Math.min(minTime, segment.startTime);
            maxTime = Math.max(maxTime, segment.endTime);
            pointCount += 2;

            int firstChunk = normalizeChunkStart(segment.startTime);
            int lastChunk = normalizeChunkStart(Math.max(segment.startTime, segment.endTime - 1));
            for (int chunkStart = firstChunk; chunkStart <= lastChunk; chunkStart += TRAJECTORY_CHUNK_SECONDS) {
                VehicleSegment clipped = segment.clip(chunkStart, chunkStart + TRAJECTORY_CHUNK_SECONDS);
                if (clipped != null) {
                    boolean firstVehicleInChunk = lastCountedChunkByVehicle.put(vehicleIndex, chunkStart) != chunkStart;
                    // 容器内保存原始链路起止时刻；查询还原时再按 30s 容器边界裁剪，
                    // 对外结果与旧版 clipped row 一致。
                    chunk(chunkStart).add(segment, encodedMode, vehicleIndex, geometryIndex, firstVehicleInChunk);
                }
            }
            pruneOpenChunks();
            progress.markPoint(segment.endTime, seenVehicleModes.size());
        }

        private void recordMissingLink(String linkId) {
            missingLinkEvents++;
            if (missingLinkSamples.size() < 12) {
                missingLinkSamples.add(linkId == null ? "<null>" : linkId);
            }
        }

        private void writePassengerTrack(double rawTime, boolean enter, String personId, String vehicleId) {
            if (personId != null && driverIds.contains(personId)) {
                return; // 司机的上下车事件不是客流
            }
            // 优先取 TransitDriverStarts 的当前班次映射；无该事件时退回时刻表静态映射
            TransitVehicleMeta meta = currentMetaByVehicle.get(vehicleId);
            if (meta == null) {
                meta = trajectoryMeta.transitVehicles.get(vehicleId);
            }
            String facilityId = currentFacilityByVehicle.get(vehicleId);
            if (meta == null || facilityId == null) {
                return;
            }
            int time = roundTime(rawTime);
            // 累计该车的车内人数增量（上车 +1 / 下车 -1），供前端按时刻还原占用与满载率。
            passengerEventsByVehicle
                    .computeIfAbsent(vehicleIndex(vehicleId), ignored -> new java.util.TreeMap<>())
                    .merge(time, enter ? 1 : -1, Integer::sum);
            String departureId = currentDepartureByVehicle.get(vehicleId);
            if (departureId == null) {
                departureId = departureByVehicle.get(vehicleId);
            }
            try {
                personTrackWriter.write(String.valueOf(rawTime));
                personTrackWriter.write('\t');
                personTrackWriter.write(String.valueOf(enter));
                personTrackWriter.write('\t');
                personTrackWriter.write(tsv(personId));
                personTrackWriter.write('\t');
                personTrackWriter.write(tsv(meta.lineId));
                personTrackWriter.write('\t');
                personTrackWriter.write(tsv(meta.routeId));
                personTrackWriter.write('\t');
                personTrackWriter.write(tsv(vehicleId));
                personTrackWriter.write('\t');
                personTrackWriter.write(tsv(departureId));
                personTrackWriter.write('\t');
                personTrackWriter.write(tsv(facilityId));
                personTrackWriter.newLine();
                trackCount++;
            } catch (IOException e) {
                throw new RuntimeException("写入乘客上下车缓存失败", e);
            }

            minTime = Math.min(minTime, time);
            maxTime = Math.max(maxTime, time);
            if (enter) {
                passengerBoardings++;
                routeBoardings.merge(meta.routeId, 1, Integer::sum);
                long[] counts = passengerBins.computeIfAbsent(time, ignored -> new long[4]);
                switch (normalizeVehicleMode(meta.mode, false)) {
                    case "bus" -> counts[0]++;
                    case "subway" -> counts[1]++;
                    default -> counts[2]++;
                }
                counts[3]++;
            }
        }

        private ChunkAccumulator chunk(int chunkStart) {
            return chunks.computeIfAbsent(chunkStart, start -> {
                try {
                    return new ChunkAccumulator(
                            trajectorySpoolDir,
                            start,
                            ".part-" + partition,
                            linkGeometry.originX(),
                            linkGeometry.originY()
                    );
                } catch (Exception e) {
                    throw new RuntimeException("创建轨迹分块缓存失败", e);
                }
            });
        }

        private void pruneOpenChunks() {
            if ((++chunkPruneCounter & 0x3F) != 0) {
                return;
            }
            int maxOpen = trajectoryMaxOpenChunksPerWorker();
            int openCount = 0;
            for (ChunkAccumulator chunk : chunks.values()) {
                if (chunk.isOpen()) {
                    openCount++;
                }
            }
            while (openCount > maxOpen) {
                ChunkAccumulator oldest = null;
                for (ChunkAccumulator chunk : chunks.values()) {
                    if (chunk.isOpen() && (oldest == null || chunk.lastUsedAt < oldest.lastUsedAt)) {
                        oldest = chunk;
                    }
                }
                if (oldest == null) {
                    return;
                }
                oldest.closeQuietly();
                openCount--;
            }
        }

        private String vehicleMode(String vehicleId, String networkMode) {
            TransitVehicleMeta meta = currentMetaByVehicle.get(vehicleId);
            if (meta == null) {
                meta = trajectoryMeta.transitVehicles.get(vehicleId);
            }
            return meta == null ? normalizeVehicleMode(networkMode, false) : meta.mode;
        }

        private int vehicleIndex(String vehicleId) {
            // 顺序自增的全局索引：唯一（无哈希碰撞），且始终 < 2^24，float 编码无损。
            // 原 hashCode%16M 在大规模车辆下生日碰撞：车辆计数少计、不同车辆占用曲线被错误合并。
            return vehicleIndexById.computeIfAbsent(
                    vehicleId == null ? "" : vehicleId,
                    ignored -> vehicleIndexCounter.getAndIncrement());
        }

        private void addTransitVehicleMeta(String vehicleId, int vehicleIndex) {
            TransitVehicleMeta meta = currentMetaByVehicle.get(vehicleId);
            if (meta == null) {
                meta = trajectoryMeta.transitVehicles.get(vehicleId);
            }
            if (meta == null || !seenTransitVehicleMeta.add(vehicleIndex)) {
                return;
            }
            RouteMeta route = trajectoryMeta.routes.get(meta.routeId);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", vehicleIndex);
            item.put("id", vehicleId);
            item.put("mode", meta.mode);
            item.put("lineId", meta.lineId);
            item.put("routeId", meta.routeId);
            item.put("lineName", route == null ? meta.lineId : route.lineName);
            item.put("routeName", route == null ? meta.routeId : route.routeName);
            item.put("capacity", vehicleCapacity(data, vehicleId));
            if (route != null) {
                item.put("firstTime", route.firstTime);
                item.put("lastTime", route.lastTime);
            }
            vehicleMetaPayloads.add(item);
        }

        private void closeQuietly() {
            try {
                personTrackWriter.close();
            } catch (Exception ignored) {
            }
            for (ChunkAccumulator chunk : chunks.values()) {
                try {
                    chunk.close();
                } catch (Exception ignored) {
                }
            }
        }

        private void cleanupTempFiles() {
            try {
                Files.deleteIfExists(personTrackPartPath);
            } catch (Exception ignored) {
            }
            for (ChunkAccumulator chunk : chunks.values()) {
                try {
                    Files.deleteIfExists(chunk.rawPath);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static class ChunkAccumulator {
        private final int start;
        private final int end;
        private final Path rawPath;
        private final double rawOriginX;
        private final double rawOriginY;
        private final byte[] row = new byte[TRAJECTORY_COMPACT_STRIDE * Integer.BYTES];
        private final ByteBuffer rowBuffer = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN);
        private OutputStream rawOut;
        private long lastUsedAt = 0L;
        private int segmentCount = 0;
        private int vehicleCount = 0;
        private int pointCount = 0;
        private double minX = Double.POSITIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY;
        private double maxY = Double.NEGATIVE_INFINITY;

        private ChunkAccumulator(
                Path dir,
                int start,
                String suffix,
                double rawOriginX,
                double rawOriginY
        ) throws Exception {
            this.start = normalizeChunkStart(start);
            this.end = this.start + TRAJECTORY_CHUNK_SECONDS - 1;
            this.rawOriginX = rawOriginX;
            this.rawOriginY = rawOriginY;
            Files.createDirectories(dir);
            this.rawPath = dir.resolve(String.format(Locale.ROOT, "chunk-%06d%s.raw.tmp", this.start, suffix));
        }

        private void add(
                VehicleSegment segment,
                int modeCode,
                int vehicleIndex,
                int linkIndex,
                boolean firstVehicleInChunk
        ) {
            try {
                ensureOpen();
                rowBuffer.clear();
                rowBuffer.putInt(segment.startTime);
                rowBuffer.putInt(segment.endTime);
                rowBuffer.putInt(vehicleIndex);
                rowBuffer.putInt(linkIndex);
                rawOut.write(row);
                lastUsedAt = System.nanoTime();
            } catch (IOException e) {
                throw new RuntimeException("写入轨迹原始分块失败", e);
            }
            if (firstVehicleInChunk) vehicleCount++;
            segmentCount++;
            pointCount += 2;
            minX = Math.min(minX, Math.min(segment.fromX, segment.toX));
            minY = Math.min(minY, Math.min(segment.fromY, segment.toY));
            maxX = Math.max(maxX, Math.max(segment.fromX, segment.toX));
            maxY = Math.max(maxY, Math.max(segment.fromY, segment.toY));
        }

        private void ensureOpen() throws IOException {
            if (rawOut != null) {
                return;
            }
            rawOut = new BufferedOutputStream(
                    Files.newOutputStream(
                            rawPath,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND
                    ),
                    TRAJECTORY_RAW_CHUNK_BUFFER_BYTES
            );
        }

        private boolean isOpen() {
            return rawOut != null;
        }

        private void close() throws IOException {
            if (rawOut == null) {
                return;
            }
            rawOut.close();
            rawOut = null;
        }

        private void closeQuietly() {
            try {
                close();
            } catch (Exception ignored) {
            }
        }

    }

    private static class CombinedChunkAccumulator {
        private final int start;
        private final int end;
        private final List<Path> rawPaths = new ArrayList<>();
        private double rawOriginX = Double.NaN;
        private double rawOriginY = Double.NaN;
        private int segmentCount = 0;
        private int vehicleCount = 0;
        private int pointCount = 0;
        private double minX = Double.POSITIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY;
        private double maxY = Double.NEGATIVE_INFINITY;

        private CombinedChunkAccumulator(int start) {
            this.start = normalizeChunkStart(start);
            this.end = this.start + TRAJECTORY_CHUNK_SECONDS - 1;
        }

        private void add(ChunkAccumulator chunk) {
            rawPaths.add(chunk.rawPath);
            if (!Double.isFinite(rawOriginX)) {
                rawOriginX = chunk.rawOriginX;
                rawOriginY = chunk.rawOriginY;
            } else if (Double.compare(rawOriginX, chunk.rawOriginX) != 0
                    || Double.compare(rawOriginY, chunk.rawOriginY) != 0) {
                throw new IllegalStateException("轨迹 worker 的网络原点不一致");
            }
            // vehicleId 固定分区，不同 worker 的车辆不重复，可精确求和。
            vehicleCount += chunk.vehicleCount;
            segmentCount += chunk.segmentCount;
            pointCount += chunk.pointCount;
            minX = Math.min(minX, chunk.minX);
            minY = Math.min(minY, chunk.minY);
            maxX = Math.max(maxX, chunk.maxX);
            maxY = Math.max(maxY, chunk.maxY);
        }

        private Map<String, Object> finish(
                MatsimData data,
                MatsimLinkGeometryIndex linkGeometry,
                Int2IntOpenHashMap vehicleModes
        ) throws Exception {
            Map<String, Object> chunk = chunkInfo(start, vehicleCount, pointCount);
            chunk.put("segmentCount", segmentCount);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ready");
            payload.put("cacheVersion", TRAJECTORY_CACHE_VERSION);
            payload.put("chunk", chunk);
            payload.put("vehicles", List.of());
            writeGzipJson(trajectoryCacheDir(data).resolve(chunkFileName(start)), payload);

            double originX = Double.isFinite(rawOriginX) ? rawOriginX : 0.0;
            double originY = Double.isFinite(rawOriginY) ? rawOriginY : 0.0;
            chunk.putAll(writeSpatialTrajectoryTilesFromRaw(
                    data, start, end, originX, originY, rawPaths, linkGeometry, vehicleModes
            ));
            long expandedBytes = TRAJECTORY_BINARY_HEADER_BYTES
                    + (long) segmentCount * TRAJECTORY_BINARY_STRIDE * Float.BYTES;
            if (expandedBytes <= TRAJECTORY_LEGACY_FULL_CHUNK_MAX_BYTES) {
                chunk.put("binaryAssembly", "on-demand-from-zstd-spatial-blocks");
                chunk.put("fullChunkAvailable", true);
            } else {
                chunk.put("fullChunkAvailable", false);
            }
            for (Path rawPath : rawPaths) {
                Files.deleteIfExists(rawPath);
            }
            return chunk;
        }
    }

    private static class VehicleTrajectoryHandler implements
            LinkEnterEventHandler,
            LinkLeaveEventHandler,
            VehicleEntersTrafficEventHandler,
            VehicleLeavesTrafficEventHandler {

        private final Network network;
        private final Map<String, TransitVehicleMeta> transitMeta;
        private final ProgressThrottle progress;
        private final Map<String, VehicleTrace> traces = new LinkedHashMap<>();
        private final Set<String> missingLinkSamples = new LinkedHashSet<>();
        private long linkEvents;
        private long missingLinkEvents;
        private int minTime = Integer.MAX_VALUE;
        private int maxTime = Integer.MIN_VALUE;

        private VehicleTrajectoryHandler(Network network, Map<String, TransitVehicleMeta> transitMeta, BuildProgress progress) {
            this.network = network;
            this.transitMeta = transitMeta;
            // 独立 Builder 会把进度原子写入外置盘。逐 segment 写一次会把 events
            // 解析退化成每秒约千条 IOPS；与大模型路径一致，最多每秒发布一次进度。
            this.progress = new ProgressThrottle(progress);
        }

        @Override
        public void handleEvent(VehicleEntersTrafficEvent event) {
            startLink(event.getVehicleId(), event.getLinkId(), event.getTime(), event.getNetworkMode(), event.getPersonId());
        }

        @Override
        public void handleEvent(LinkEnterEvent event) {
            startLink(event.getVehicleId(), event.getLinkId(), event.getTime(), null, null);
        }

        @Override
        public void handleEvent(LinkLeaveEvent event) {
            finishLink(event.getVehicleId(), event.getLinkId(), event.getTime(), null);
        }

        @Override
        public void handleEvent(VehicleLeavesTrafficEvent event) {
            if (event.getVehicleId() == null) {
                return;
            }
            finishLink(event.getVehicleId(), event.getLinkId(), event.getTime(), event.getNetworkMode());
        }

        private void startLink(Id<Vehicle> vehicleId, Id<Link> linkId, double rawTime, String networkMode, Id<Person> personId) {
            if (vehicleId == null || linkId == null) {
                return;
            }
            linkEvents++;
            Link link = network.getLinks().get(linkId);
            if (link == null) {
                recordMissingLink(linkId.toString());
                return;
            }
            String id = vehicleId.toString();
            VehicleTrace trace = traces.computeIfAbsent(id, value -> createTrace(value, networkMode));
            if (personId != null) {
                trace.personIds.add(personId.toString());
            }
            if (networkMode != null && !transitMeta.containsKey(id)) {
                trace.mode = normalizeVehicleMode(networkMode, false);
            }
            int time = Math.max(0, (int) Math.round(rawTime));
            VehicleSegment segment = trace.startLink(linkId.toString(), time, link);
            if (segment != null) {
                minTime = Math.min(minTime, segment.startTime);
                maxTime = Math.max(maxTime, segment.endTime);
                progress.markPoint(segment.endTime, traces.size());
            }
        }

        private void finishLink(Id<Vehicle> vehicleId, Id<Link> linkId, double rawTime, String networkMode) {
            if (vehicleId == null || linkId == null) {
                return;
            }
            linkEvents++;
            Link link = network.getLinks().get(linkId);
            if (link == null) {
                recordMissingLink(linkId.toString());
                return;
            }
            String id = vehicleId.toString();
            VehicleTrace trace = traces.computeIfAbsent(id, value -> createTrace(value, networkMode));
            if (networkMode != null && !transitMeta.containsKey(id)) {
                trace.mode = normalizeVehicleMode(networkMode, false);
            }
            int time = Math.max(0, (int) Math.round(rawTime));
            VehicleSegment segment = trace.finishLink(linkId.toString(), time, link);
            if (segment != null) {
                minTime = Math.min(minTime, segment.startTime);
                maxTime = Math.max(maxTime, segment.endTime);
                progress.markPoint(segment.endTime, traces.size());
            }
        }

        private VehicleTrace createTrace(String vehicleId, String networkMode) {
            TransitVehicleMeta meta = transitMeta.get(vehicleId);
            if (meta != null) {
                return new VehicleTrace(vehicleId, meta.mode, meta.lineId, meta.routeId);
            }
            return new VehicleTrace(vehicleId, normalizeVehicleMode(networkMode, false), null, null);
        }

        private Collection<VehicleTrace> getTraces() {
            return traces.values();
        }

        private void finishProgress() {
            progress.finish(maxTime == Integer.MIN_VALUE ? 0 : maxTime, traces.size());
        }

        private void recordMissingLink(String linkId) {
            missingLinkEvents++;
            if (missingLinkSamples.size() < 12) missingLinkSamples.add(linkId);
        }

        private void assertCompleteNetworkCoverage() {
            if (missingLinkEvents > 0) {
                throw new IllegalStateException("events 引用了当前 network 中不存在的 link，已拒绝发布不完整轨迹: count="
                        + missingLinkEvents + ", samples=" + missingLinkSamples);
            }
        }

        private long linkEvents() {
            return linkEvents;
        }

        private int pointCount() {
            return traces.values().stream().mapToInt(trace -> trace.segments.size() * 2).sum();
        }

        private int getMinTime() {
            return minTime;
        }

        private int getMaxTime() {
            return maxTime;
        }
    }

    private static class VehicleTrace {
        private final String id;
        private String mode;
        private final String lineId;
        private final String routeId;
        private final Set<String> personIds = new LinkedHashSet<>();
        private final List<VehicleSegment> segments = new ArrayList<>();
        private double distance = 0.0;
        private ActiveLink activeLink = null;

        private VehicleTrace(String id, String mode, String lineId, String routeId) {
            this.id = id;
            this.mode = mode;
            this.lineId = lineId;
            this.routeId = routeId;
        }

        private VehicleSegment startLink(String linkId, int time, Link link) {
            VehicleSegment closed = null;
            if (activeLink != null && activeLink.linkId.equals(linkId)) {
                return null;
            }
            if (activeLink != null && !activeLink.linkId.equals(linkId)) {
                closed = closeActiveLink(time, activeLink.link);
            }
            activeLink = new ActiveLink(linkId, time, link);
            return closed;
        }

        private VehicleSegment finishLink(String linkId, int time, Link link) {
            if (activeLink == null || !activeLink.linkId.equals(linkId)) {
                activeLink = new ActiveLink(linkId, time, link);
            }
            VehicleSegment segment = closeActiveLink(time, link);
            activeLink = null;
            return segment;
        }

        private VehicleSegment closeActiveLink(int endTime, Link fallbackLink) {
            if (activeLink == null) {
                return null;
            }
            int startTime = activeLink.startTime;
            if (endTime <= startTime) {
                return null;
            }
            Link link = activeLink.link == null ? fallbackLink : activeLink.link;
            if (link == null) {
                return null;
            }
            Coord from = link.getFromNode().getCoord();
            Coord to = link.getToNode().getCoord();
            VehicleSegment segment = new VehicleSegment(
                    startTime,
                    endTime,
                    roundCoord(from.getX()),
                    roundCoord(from.getY()),
                    roundCoord(to.getX()),
                    roundCoord(to.getY()),
                    link.getLength()
            );
            if (segment.distance < 0.01) {
                return null;
            }
            segments.add(segment);
            distance += segment.distance;
            return segment;
        }

        private Map<String, Object> toChunkPayload(int start, int end) {
            if (segments.isEmpty()) {
                return null;
            }

            List<double[]> chunkSegments = new ArrayList<>();
            for (VehicleSegment segment : segments) {
                if (segment.endTime < start || segment.startTime > end) {
                    continue;
                }
                chunkSegments.add(segment.toPayload());
            }
            if (chunkSegments.isEmpty()) {
                return null;
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("mode", mode);
            result.put("lineId", lineId);
            result.put("routeId", routeId);
            result.put("distance", round2(distance));
            result.put("segments", chunkSegments);
            return result;
        }
    }

    private static class ActiveLink {
        private final String linkId;
        private final int startTime;
        private final Link link;

        private ActiveLink(String linkId, int startTime, Link link) {
            this.linkId = linkId;
            this.startTime = startTime;
            this.link = link;
        }
    }

    private static class IndexedActiveLink {
        private final String linkId;
        private final int startTime;
        private final int geometryIndex;

        private IndexedActiveLink(String linkId, int startTime, int geometryIndex) {
            this.linkId = linkId;
            this.startTime = startTime;
            this.geometryIndex = geometryIndex;
        }
    }

    private static class VehicleSegment {
        private final int startTime;
        private final int endTime;
        private final double fromX;
        private final double fromY;
        private final double toX;
        private final double toY;
        private final double distance;

        private VehicleSegment(
                int startTime,
                int endTime,
                double fromX,
                double fromY,
                double toX,
                double toY,
                double declaredDistance
        ) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.fromX = fromX;
            this.fromY = fromY;
            this.toX = toX;
            this.toY = toY;
            double dx = toX - fromX;
            double dy = toY - fromY;
            double geometricDistance = Math.sqrt(dx * dx + dy * dy);
            this.distance = Double.isFinite(declaredDistance) && declaredDistance > 0.0
                    ? declaredDistance : geometricDistance;
        }

        private VehicleSegment clip(int fromTime, int toTimeExclusive) {
            int clippedStart = Math.max(startTime, fromTime);
            int clippedEnd = Math.min(endTime, toTimeExclusive);
            if (clippedEnd <= clippedStart || endTime <= startTime) {
                return null;
            }
            double duration = endTime - startTime;
            double startRatio = (clippedStart - startTime) / duration;
            double endRatio = (clippedEnd - startTime) / duration;
            return new VehicleSegment(
                    clippedStart,
                    clippedEnd,
                    fromX + (toX - fromX) * startRatio,
                    fromY + (toY - fromY) * startRatio,
                    fromX + (toX - fromX) * endRatio,
                    fromY + (toY - fromY) * endRatio,
                    distance * (clippedEnd - clippedStart) / duration
            );
        }

        private double[] toPayload() {
            return new double[]{startTime, endTime, fromX, fromY, toX, toY};
        }
    }
}
