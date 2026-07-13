package com.jts.gjcxfzksh.data.cache;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
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
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

@Slf4j
public final class MatsimAnalysisCache {

    // v9: ①大模型车辆去重键由 hashCode%16M 改为顺序自增索引（消除生日碰撞导致的少计/占用曲线错并）；
    //     ②接入 TransitDriverStarts 事件：车辆跨班次复用时按当前班次归属 line/route/departure，
    //       并显式排除司机的上下车事件（不再依赖事件顺序侥幸过滤）。需重算缓存。
    public static final String TRAJECTORY_CACHE_VERSION = "trajectory-v9";
    public static final int TRAJECTORY_CHUNK_SECONDS = 300;

    // pt-events-v3: PTHandler/大模型流式路径接入 TransitDriverStarts 动态映射 + 司机显式过滤，
    // personTracks 的 line/route/departure 归属语义变更。该缓存是 route/station 面板的输入，
    // 联动 bump 见 MatsimRoutePanelCache / MatsimStationPanelCache 版本注释。
    private static final String PERSON_TRACK_CACHE_VERSION = "pt-events-v3";
    private static final String PERSON_TRACK_FILE = "person-tracks.tsv.gz";
    private static final byte[] TRAJECTORY_BINARY_MAGIC = new byte[]{'G', 'J', 'T', 'B'};
    private static final int TRAJECTORY_BINARY_VERSION = 1;
    private static final int TRAJECTORY_BINARY_HEADER_BYTES = 64;
    private static final int TRAJECTORY_BINARY_STRIDE = 8;
    private static final int TRAJECTORY_IO_BUFFER_BYTES = 4 * 1024 * 1024;
    private static final int TRAJECTORY_QUEUE_DEFAULT_SIZE = 65_536;
    private static final int TRAJECTORY_MAX_OPEN_CHUNKS_DEFAULT = 48;
    private static final String TRAJECTORY_LIGHT_MANIFEST_FILE = "manifest-lite.json";
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
        if (data.isLargeModel()) {
            ensureLargeTrajectoryCache(data, progress);
            loadPersonTracksFromCache(data);
            return;
        }

        boolean tracksReady = loadPersonTracksFromCache(data);
        Map<String, Object> trajectoryManifest = readReadyTrajectoryManifest(data);
        if (tracksReady && trajectoryManifest != null) {
            return;
        }

        String cacheKey = trajectoryCacheKey(data);
        Object lock = BUILD_LOCKS.computeIfAbsent(cacheKey, key -> new Object());
        synchronized (lock) {
            tracksReady = data.getPersonTracks() != null && !data.getPersonTracks().isEmpty()
                    || loadPersonTracksFromCache(data);
            trajectoryManifest = readReadyTrajectoryManifest(data);
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
            boolean personTracksReady = loadPersonTracksFromCache(data);
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
        if (data.isLargeModel()) {
            return ensureLargeTrajectoryCache(data, progress);
        }
        Map<String, Object> manifest = readReadyTrajectoryManifest(data);
        if (manifest != null) {
            return manifest;
        }

        String cacheKey = trajectoryCacheKey(data);
        Object lock = BUILD_LOCKS.computeIfAbsent(cacheKey, key -> new Object());
        synchronized (lock) {
            manifest = readReadyTrajectoryManifest(data);
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
        Map<String, Object> manifest = readReadyTrajectoryManifest(data);
        if (manifest != null && isPersonTracksCacheReady(data)) {
            return manifest;
        }

        String cacheKey = trajectoryCacheKey(data);
        Object lock = BUILD_LOCKS.computeIfAbsent(cacheKey, key -> new Object());
        synchronized (lock) {
            manifest = readReadyTrajectoryManifest(data);
            if (manifest != null && isPersonTracksCacheReady(data)) {
                return manifest;
            }
            ACTIVE_TRAJECTORY_BUILDS.incrementAndGet();
            try {
                Files.createDirectories(trajectoryCacheDir(data));
                // 先失效 manifest 再删分块：否则重建/崩溃期间旧 manifest 仍标 ready，
                // 读取端命中 manifest 却读不到分块文件，形成"摘要在、明细永久缺失"的空洞。
                Files.deleteIfExists(trajectoryManifestPath(data));
                Files.deleteIfExists(trajectoryLightManifestPath(data));
                clearTrajectoryChunks(data);
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
            if (!sameEvents(data, manifest)) {
                return null;
            }
            return manifest;
        } catch (Exception e) {
            log.warn("轨迹缓存状态读取失败: {}", manifestPath, e);
            return null;
        }
    }

    public static Map<String, Object> readReadyTrajectoryLightManifest(MatsimData data) {
        Path lightPath = trajectoryLightManifestPath(data);
        if (Files.exists(lightPath)) {
            try {
                Map<String, Object> manifest = JSON.readValue(lightPath.toFile(), MAP_TYPE);
                if (isReadyTrajectoryManifest(data, manifest) && isCompatibleLightManifest(manifest)) {
                    return manifest;
                }
            } catch (Exception e) {
                log.warn("轻量轨迹缓存状态读取失败: {}", lightPath, e);
            }
        }

        Map<String, Object> manifest = readTrajectoryLightManifestFromFull(data);
        if (manifest == null) {
            return null;
        }
        try {
            writeJsonAtomic(lightPath, manifest);
        } catch (Exception e) {
            log.warn("轻量轨迹缓存状态写入失败: {}", lightPath, e);
        }
        return manifest;
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
                && "ready".equals(manifest.get("status"))
                && TRAJECTORY_CACHE_VERSION.equals(manifest.get("cacheVersion"))
                && sameEvents(data, manifest);
    }

    private static Map<String, Object> readTrajectoryLightManifestFromFull(MatsimData data) {
        Path manifestPath = trajectoryManifestPath(data);
        if (!Files.exists(manifestPath)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(manifestPath);
             JsonParser parser = JSON.getFactory().createParser(in)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return null;
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
            log.warn("轨迹缓存轻量状态读取失败: {}", manifestPath, e);
            return null;
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
        manifest.put("eventsFile", data.getOutfile().getEvents());
        manifest.put("eventsModified", lastModified(data.getOutfile().getEvents()));
        manifest.put("eventsSize", fileSize(data.getOutfile().getEvents()));
        manifest.put("chunkSeconds", TRAJECTORY_CHUNK_SECONDS);
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
            Map<String, Object> result = new LinkedHashMap<>(manifest);
            result.put("vehicles", List.of());
            result.put("chunk", chunkInfo(chunkStart, 0, 0));
            return result;
        }

        try {
            Map<String, Object> result = readGzipJson(chunkPath);
            result.put("status", "ready");
            result.put("cacheVersion", TRAJECTORY_CACHE_VERSION);
            result.put("timeRange", manifest.get("timeRange"));
            result.put("summary", manifest.get("summary"));
            return result;
        } catch (Exception e) {
            log.warn("轨迹分块读取失败: {}", chunkPath, e);
            Map<String, Object> result = new LinkedHashMap<>(manifest);
            result.put("vehicles", List.of());
            result.put("chunk", chunkInfo(chunkStart, 0, 0));
            result.put("status", "failed");
            result.put("message", "轨迹分块读取失败");
            return result;
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
                log.warn("空轨迹二进制分块生成失败: start={}", chunkStart, e);
                return null;
            }
        }

        Path chunkPath = trajectoryCacheDir(data).resolve(chunkBinaryFileName(chunkStart));
        if (!Files.exists(chunkPath)) {
            return null;
        }

        try {
            return Files.readAllBytes(chunkPath);
        } catch (Exception e) {
            log.warn("轨迹二进制分块读取失败: {}", chunkPath, e);
            return null;
        }
    }

    /**
     * 从磁盘分块中流式抽取“目标时间桶 + 视口”的精确轨迹段。
     *
     * <p>时间轴随机跳转不再需要先把整个 300 秒全市分块下载到浏览器。服务端顺序扫描本地二进制文件，
     * 只返回目标时间桶内、且与当前视口相交的原始段记录；记录格式与普通分块完全一致，因此前端继续
     * 使用同一套 Worker/GPU 插值和完整 GLB 实例，不产生视觉降级。</p>
     */
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
        Map<String, Object> manifest = readReadyTrajectoryLightManifest(data);
        if (manifest == null) {
            return null;
        }
        int windowSeconds = Math.max(1, Math.min(TRAJECTORY_CHUNK_SECONDS, bucketSeconds));
        int frameStart = Math.floorDiv(Math.max(0, time), windowSeconds) * windowSeconds;
        int frameEndExclusive = frameStart + windowSeconds;
        int chunkStart = normalizeChunkStart(frameStart);
        if (!manifestHasChunk(manifest, chunkStart)) {
            try {
                return createTrajectoryBinaryBytes(frameStart, frameEndExclusive - 1, 0, 0, 0.0, 0.0, List.of());
            } catch (IOException e) {
                return null;
            }
        }

        Path chunkPath = trajectoryCacheDir(data).resolve(chunkBinaryFileName(chunkStart));
        if (!Files.exists(chunkPath)) {
            return null;
        }

        boolean hasBounds = requestedMinX != null && requestedMinY != null
                && requestedMaxX != null && requestedMaxY != null
                && Double.isFinite(requestedMinX) && Double.isFinite(requestedMinY)
                && Double.isFinite(requestedMaxX) && Double.isFinite(requestedMaxY)
                && requestedMaxX > requestedMinX && requestedMaxY > requestedMinY;
        String normalizedVisibility = visibilityMode == null ? "all" : visibilityMode.toLowerCase(Locale.ROOT);
        byte[] header = new byte[TRAJECTORY_BINARY_HEADER_BYTES];
        byte[] row = new byte[TRAJECTORY_BINARY_STRIDE * Float.BYTES];
        IntOpenHashSet vehicles = new IntOpenHashSet();
        ByteArrayOutputStream selectedRows = new ByteArrayOutputStream(1024 * 1024);

        try (InputStream in = new BufferedInputStream(Files.newInputStream(chunkPath), TRAJECTORY_IO_BUFFER_BYTES)) {
            if (!readFully(in, header)) return null;
            ByteBuffer headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            if (headerBuffer.get() != 'G' || headerBuffer.get() != 'J'
                    || headerBuffer.get() != 'T' || headerBuffer.get() != 'B') {
                return null;
            }
            int version = Short.toUnsignedInt(headerBuffer.getShort());
            int headerBytes = Short.toUnsignedInt(headerBuffer.getShort());
            headerBuffer.getInt(); // source chunk start
            headerBuffer.getInt(); // source chunk end
            int segmentCount = headerBuffer.getInt();
            headerBuffer.getInt(); // source vehicle count
            headerBuffer.getInt(); // source point count
            int stride = headerBuffer.getInt();
            double originX = headerBuffer.getDouble();
            double originY = headerBuffer.getDouble();
            if (version != TRAJECTORY_BINARY_VERSION
                    || headerBytes != TRAJECTORY_BINARY_HEADER_BYTES
                    || stride != TRAJECTORY_BINARY_STRIDE
                    || segmentCount < 0) {
                return null;
            }

            int selectedCount = 0;
            ByteBuffer record = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN);
            for (int index = 0; index < segmentCount; index++) {
                if (!readFully(in, row)) {
                    throw new EOFException("轨迹二进制分块记录数与头部不一致");
                }
                record.clear();
                float startTime = record.getFloat();
                float endTime = record.getFloat();
                float startX = record.getFloat();
                float startY = record.getFloat();
                float endX = record.getFloat();
                float endY = record.getFloat();
                int modeCode = Math.round(record.getFloat());
                int vehicleIndex = Math.round(record.getFloat());
                if (!(startTime < frameEndExclusive && endTime > frameStart)) continue;
                if ("public".equals(normalizedVisibility) && modeCode == 2) continue;
                if ("private".equals(normalizedVisibility) && modeCode != 2) continue;
                if (hasBounds) {
                    double segmentMinX = originX + Math.min(startX, endX);
                    double segmentMaxX = originX + Math.max(startX, endX);
                    double segmentMinY = originY + Math.min(startY, endY);
                    double segmentMaxY = originY + Math.max(startY, endY);
                    if (segmentMaxX < requestedMinX || segmentMinX > requestedMaxX
                            || segmentMaxY < requestedMinY || segmentMinY > requestedMaxY) {
                        continue;
                    }
                }
                selectedRows.write(row);
                selectedCount++;
                vehicles.add(vehicleIndex);
            }

            ByteArrayOutputStream result = new ByteArrayOutputStream(
                    TRAJECTORY_BINARY_HEADER_BYTES + selectedRows.size()
            );
            writeTrajectoryBinaryHeader(
                    result,
                    frameStart,
                    frameEndExclusive - 1,
                    selectedCount,
                    vehicles.size(),
                    selectedCount * 2,
                    originX,
                    originY
            );
            selectedRows.writeTo(result);
            return result.toByteArray();
        } catch (Exception e) {
            log.warn("轨迹视口快照读取失败: path={}, time={}", chunkPath, time, e);
            return null;
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

        Path chunkPath = trajectoryCacheDir(data).resolve(chunkBinaryFileName(chunkStart));
        return Files.exists(chunkPath) ? chunkPath : null;
    }

    public static Map<String, Object> chunkInfo(int start, int vehicleCount, int pointCount) {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("start", normalizeChunkStart(start));
        chunk.put("end", normalizeChunkStart(start) + TRAJECTORY_CHUNK_SECONDS - 1);
        chunk.put("vehicleCount", vehicleCount);
        chunk.put("pointCount", pointCount);
        chunk.put("file", chunkFileName(start));
        chunk.put("binaryFile", chunkBinaryFileName(start));
        return chunk;
    }

    public static int normalizeChunkStart(int start) {
        return Math.floorDiv(Math.max(0, start), TRAJECTORY_CHUNK_SECONDS) * TRAJECTORY_CHUNK_SECONDS;
    }

    public static String trajectoryCacheKey(MatsimData data) {
        String events = data.getOutfile().getEvents();
        return data.getName() + "::" + events + "::" + lastModified(events) + "::" + fileSize(events) + "::" + TRAJECTORY_CACHE_VERSION;
    }

    /**
     * 轨迹二进制分块的强校验 ETag（已带引号）。仅依赖 events 身份与分块起点，不读分块文件。
     * events 变化时 cacheKey 变化 → ETag 变化 → 浏览器/SW 缓存自动失效。
     */
    public static String trajectoryChunkETag(MatsimData data, int start) {
        String events = data.getOutfile().getEvents();
        String cacheKey = trajectoryCacheKey(data);
        return "\"traj-"
                + Integer.toHexString(cacheKey.hashCode())
                + "-" + Long.toHexString(lastModified(events))
                + "-" + Long.toHexString(fileSize(events))
                + "-" + normalizeChunkStart(start)
                + "\"";
    }

    /**
     * 磁盘轻量缓存已就绪时把 personTracks 装入内存（不解析 events，缓存缺失直接返回 false）。
     * 供模型加载路径使用：磁盘缓存齐全时 ModelCacheManager 不会再跑 buildCaches，
     * 内存态 personTracks 需要在这里补齐，否则依赖它的接口（如体检评估）会一直"生成中"。
     */
    public static boolean preloadPersonTracksIfReady(MatsimData data) {
        if (data.getPersonTracks() != null && !data.getPersonTracks().isEmpty()) {
            return true;
        }
        return loadPersonTracksFromCache(data);
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
            log.warn("乘客上下车缓存读取失败: {}", tracksPath, e);
            return false;
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
            log.warn("乘客上下车缓存状态读取失败: {}", manifestPath, e);
            return false;
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
        manifest.put("trackCount", tracks.size());
        writeJsonAtomic(personTrackManifestPath(data), manifest);
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
        Files.createDirectories(trajectoryCacheDir(data));
        clearTrajectoryChunks(data);

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
            distanceByMode.merge(trace.mode, round2(trace.distance), Double::sum);
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
        manifest.put("chunkSeconds", TRAJECTORY_CHUNK_SECONDS);
        manifest.put("generatedAt", System.currentTimeMillis());
        manifest.put("eventsFile", data.getOutfile().getEvents());
        manifest.put("eventsModified", lastModified(data.getOutfile().getEvents()));
        manifest.put("eventsSize", fileSize(data.getOutfile().getEvents()));
        manifest.put("timeRange", timeRange);
        manifest.put("summary", summary);
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
        int lastStart = normalizeChunkStart(maxTime);
        int[] cursors = new int[traceList.size()];
        for (int chunkStart = firstStart; chunkStart <= lastStart; chunkStart += TRAJECTORY_CHUNK_SECONDS) {
            int chunkEnd = chunkStart + TRAJECTORY_CHUNK_SECONDS - 1;
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
                while (cursor < traceSegments.size() && traceSegments.get(cursor).endTime < chunkStart) {
                    cursor++;
                }
                cursors[traceIndex] = cursor;

                int modeCode = modeCode(trace.mode);
                int vehicleIndex = traceIndex;
                boolean hasChunkSegment = false;
                for (int segmentIndex = cursor; segmentIndex < traceSegments.size(); segmentIndex++) {
                    VehicleSegment segment = traceSegments.get(segmentIndex);
                    if (segment.startTime > chunkEnd) {
                        break;
                    }
                    if (segment.endTime < chunkStart) {
                        continue;
                    }

                    if (!hasChunkSegment) {
                        vehicleCount++;
                        hasChunkSegment = true;
                    }

                    pointCount += 2;
                    double startX = segment.fromX;
                    double startY = segment.fromY;
                    double endX = segment.toX;
                    double endY = segment.toY;
                    minX = Math.min(minX, Math.min(startX, endX));
                    minY = Math.min(minY, Math.min(startY, endY));
                    maxX = Math.max(maxX, Math.max(startX, endX));
                    maxY = Math.max(maxY, Math.max(startY, endY));
                    binarySegments.add(new BinaryTrajectorySegment(
                            (float) segment.startTime,
                            (float) segment.endTime,
                            startX,
                            startY,
                            endX,
                            endY,
                            modeCode,
                            vehicleIndex
                    ));
                }
            }

            Map<String, Object> chunk = chunkInfo(chunkStart, vehicleCount, pointCount);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ready");
            payload.put("cacheVersion", TRAJECTORY_CACHE_VERSION);
            payload.put("chunk", chunk);
            payload.put("vehicles", List.of());
            writeGzipJson(trajectoryCacheDir(data).resolve(chunkFileName(chunkStart)), payload);
            double originX = binarySegments.isEmpty() ? 0.0 : (minX + maxX) / 2.0;
            double originY = binarySegments.isEmpty() ? 0.0 : (minY + maxY) / 2.0;
            writeTrajectoryBinaryChunk(
                    trajectoryCacheDir(data).resolve(chunkBinaryFileName(chunkStart)),
                    chunkStart,
                    chunkEnd,
                    vehicleCount,
                    pointCount,
                    originX,
                    originY,
                    binarySegments
            );
            chunks.add(chunk);
        }
        return chunks;
    }

    private static void clearTrajectoryChunks(MatsimData data) throws Exception {
        Path dir = trajectoryCacheDir(data);
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path path : paths.filter(path -> path.getFileName().toString().startsWith("chunk-")
                    && (path.getFileName().toString().endsWith(".json.gz")
                    || path.getFileName().toString().endsWith(".json.gz.tmp")
                    || path.getFileName().toString().endsWith(".bin")
                    || path.getFileName().toString().endsWith(".bin.tmp")
                    || path.getFileName().toString().endsWith(".raw.tmp"))).toList()) {
                Files.deleteIfExists(path);
            }
        }
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
                        float vehicleIndex = input.getFloat();

                        output.clear();
                        output.putFloat(startTime);
                        output.putFloat(endTime);
                        output.putFloat((float) (startX - originX));
                        output.putFloat((float) (startY - originY));
                        output.putFloat((float) (endX - originX));
                        output.putFloat((float) (endY - originY));
                        output.putFloat(modeCode);
                        output.putFloat(vehicleIndex);
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
        writeIntLE(out, TRAJECTORY_CHUNK_SECONDS);
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
            buffer.putFloat(segment.vehicleIndex);
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
        writeJsonAtomic(trajectoryManifestPath(data), manifest);
        writeJsonAtomic(trajectoryLightManifestPath(data), lightweightTrajectoryManifest(manifest));
    }

    private static boolean sameEvents(MatsimData data, Map<String, Object> manifest) {
        long eventsModified = ((Number) manifest.getOrDefault("eventsModified", -1)).longValue();
        long eventsSize = ((Number) manifest.getOrDefault("eventsSize", -1)).longValue();
        return eventsModified == lastModified(data.getOutfile().getEvents())
                && eventsSize == fileSize(data.getOutfile().getEvents());
    }

    private static boolean manifestHasChunk(Map<String, Object> manifest, int chunkStart) {
        Object summaryObject = manifest.get("summary");
        if (!(summaryObject instanceof Map<?, ?> summary)) {
            return false;
        }
        Object chunksObject = summary.get("chunks");
        if (!(chunksObject instanceof List<?> chunks)) {
            return false;
        }
        for (Object item : chunks) {
            if (!(item instanceof Map<?, ?> chunk)) {
                continue;
            }
            Object startObject = chunk.get("start");
            if (startObject instanceof Number start && start.intValue() == chunkStart) {
                return true;
            }
        }
        return false;
    }

    private static Path personTrackCacheDir(MatsimData data) {
        return MatsimCachePaths.versionDir(data, PERSON_TRACK_CACHE_VERSION);
    }

    private static Path personTrackManifestPath(MatsimData data) {
        return personTrackCacheDir(data).resolve("manifest.json");
    }

    private static Path personTracksPath(MatsimData data) {
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

    private static String chunkFileName(int start) {
        return String.format(Locale.ROOT, "chunk-%06d.json.gz", normalizeChunkStart(start));
    }

    private static String chunkBinaryFileName(int start) {
        return String.format(Locale.ROOT, "chunk-%06d.bin", normalizeChunkStart(start));
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
            return 0L;
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
            return 0L;
        }
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
            return 0.0;
        }
    }

    private static int roundTime(Double time) {
        if (time == null || Double.isNaN(time) || Double.isInfinite(time)) {
            return 0;
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
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (Exception e) {
            return fallback;
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

        private BinaryTrajectorySegment(
                float startTime,
                float endTime,
                double startX,
                double startY,
                double endX,
                double endY,
                int modeCode,
                int vehicleIndex
        ) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.startX = startX;
            this.startY = startY;
            this.endX = endX;
            this.endY = endY;
            this.modeCode = modeCode;
            this.vehicleIndex = vehicleIndex;
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

            Map<String, Link> linksById = new HashMap<>(Math.max(16, data.getNetwork().getLinks().size() * 2));
            data.getNetwork().getLinks().forEach((id, link) -> linksById.put(id.toString(), link));
            Map<String, String> departureByVehicle = departureByVehicle(data.getSchedule());
            // 全局顺序车辆索引：原实现用 hashCode%16M 作去重键，车辆规模大时生日碰撞导致
            // totalVehicles/vehicleCountByMode 少计、不同车辆的占用曲线被错误合并。
            // 顺序自增保证唯一，且远小于 2^24，float 编码仍无损。
            ConcurrentMap<String, Integer> vehicleIndexById = new ConcurrentHashMap<>();
            java.util.concurrent.atomic.AtomicInteger vehicleIndexCounter = new java.util.concurrent.atomic.AtomicInteger();

            int workerCount = largeTrajectoryParallelism();
            int queueSize = trajectoryQueueSize();
            log.info("大模型events并行解析: model={}, workers={}, queueSize={}", data.getName(), workerCount, queueSize);
            for (int i = 0; i < workerCount; i++) {
                BlockingQueue<RawEventTask> queue = new ArrayBlockingQueue<>(queueSize);
                LargeTrajectoryWorker worker = new LargeTrajectoryWorker(
                        i,
                        data,
                        trajectoryMeta,
                        linksById,
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
            writeMergedPersonTracks();

            IntOpenHashSet globalVehicles = new IntOpenHashSet();
            IntOpenHashSet globalTransitVehicleMeta = new IntOpenHashSet();
            Map<Integer, CombinedChunkAccumulator> combinedChunks = new TreeMap<>();
            Map<String, Long> vehicleCountByMode = emptyLongModeMap();
            Map<String, Double> distanceByMode = emptyDoubleModeMap();
            Map<Integer, long[]> passengerBins = new TreeMap<>();
            Map<String, Integer> routeBoardings = new HashMap<>();
            List<Map<String, Object>> vehicleMetaPayloads = new ArrayList<>();
            long passengerBoardings = 0;
            long pointCount = 0;
            int minTime = Integer.MAX_VALUE;
            int maxTime = Integer.MIN_VALUE;

            for (LargeTrajectoryWorker worker : workers) {
                worker.seenVehicleModes.forEach((vehicleIndex, mode) -> {
                    if (globalVehicles.add(vehicleIndex)) {
                        vehicleCountByMode.merge(mode, 1L, Long::sum);
                    }
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

            List<Map<String, Object>> chunkPayloads = new ArrayList<>();
            for (CombinedChunkAccumulator chunk : combinedChunks.values()) {
                chunkPayloads.add(chunk.finish(data));
            }

            if (minTime == Integer.MAX_VALUE || maxTime == Integer.MIN_VALUE) {
                minTime = 0;
                maxTime = 86400;
            }
            distanceByMode.replaceAll((mode, distance) -> round2(distance / 1000.0));
            progress.finish(maxTime, globalVehicles.size());

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("totalVehicles", globalVehicles.size());
            summary.put("vehicleCountByMode", vehicleCountByMode);
            summary.put("totalPassengerBoardings", passengerBoardings);
            summary.put("distanceKmByMode", distanceByMode);
            summary.put("pointCount", pointCount);
            summary.put("routeBoardings", topRouteBoardings(routeBoardings));
            summary.put("chunks", chunkPayloads);

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
            manifest.put("chunkSeconds", TRAJECTORY_CHUNK_SECONDS);
            manifest.put("generatedAt", System.currentTimeMillis());
            manifest.put("eventsFile", data.getOutfile().getEvents());
            manifest.put("eventsModified", lastModified(data.getOutfile().getEvents()));
            manifest.put("eventsSize", fileSize(data.getOutfile().getEvents()));
            manifest.put("timeRange", timeRange);
            manifest.put("summary", summary);
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
            manifest.put("trackCount", trackCount);
            manifest.put("streamed", true);
            manifest.put("parallelism", workers.size());
            writeJsonAtomic(personTrackManifestPath(data), manifest);
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
        private final TrajectoryMeta trajectoryMeta;
        private final Map<String, Link> linksById;
        private final Map<String, String> departureByVehicle;
        // 跨 worker 共享的车辆顺序索引（唯一、float 安全）
        private final ConcurrentMap<String, Integer> vehicleIndexById;
        private final java.util.concurrent.atomic.AtomicInteger vehicleIndexCounter;
        private final ProgressThrottle progress;
        private final BlockingQueue<RawEventTask> queue;
        private final AtomicReference<Throwable> failure;
        private final Map<String, ActiveLink> activeLinks = new HashMap<>();
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
        private final Map<Integer, String> seenVehicleModes = new HashMap<>();
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

        private LargeTrajectoryWorker(
                int partition,
                MatsimData data,
                TrajectoryMeta trajectoryMeta,
                Map<String, Link> linksById,
                Map<String, String> departureByVehicle,
                ConcurrentMap<String, Integer> vehicleIndexById,
                java.util.concurrent.atomic.AtomicInteger vehicleIndexCounter,
                ProgressThrottle progress,
                BlockingQueue<RawEventTask> queue,
                AtomicReference<Throwable> failure
        ) throws Exception {
            this.partition = partition;
            this.data = data;
            this.trajectoryMeta = trajectoryMeta;
            this.linksById = linksById;
            this.departureByVehicle = departureByVehicle;
            this.vehicleIndexById = vehicleIndexById;
            this.vehicleIndexCounter = vehicleIndexCounter;
            this.progress = progress;
            this.queue = queue;
            this.failure = failure;
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
            Link link = linksById.get(linkId);
            if (link == null) {
                return;
            }
            ActiveLink active = activeLinks.get(vehicleId);
            int time = Math.max(0, (int) Math.round(rawTime));
            if (active != null && !active.linkId.equals(linkId)) {
                writeSegment(vehicleId, active, time, networkMode, active.link);
            }
            activeLinks.put(vehicleId, new ActiveLink(linkId, time, link));
        }

        private void finishLink(String vehicleId, String linkId, double rawTime, String networkMode) {
            Link link = linksById.get(linkId);
            if (link == null) {
                return;
            }
            int time = Math.max(0, (int) Math.round(rawTime));
            ActiveLink active = activeLinks.get(vehicleId);
            if (active == null || !active.linkId.equals(linkId)) {
                active = new ActiveLink(linkId, time, link);
            }
            writeSegment(vehicleId, active, time, networkMode, link);
            activeLinks.remove(vehicleId);
        }

        private void writeSegment(String vehicleId, ActiveLink active, int endTime, String networkMode, Link fallbackLink) {
            if (active == null || endTime <= active.startTime) {
                return;
            }
            Link link = active.link == null ? fallbackLink : active.link;
            if (link == null) {
                return;
            }
            Coord from = link.getFromNode().getCoord();
            Coord to = link.getToNode().getCoord();
            VehicleSegment segment = new VehicleSegment(
                    active.startTime,
                    endTime,
                    roundCoord(from.getX()),
                    roundCoord(from.getY()),
                    roundCoord(to.getX()),
                    roundCoord(to.getY())
            );
            if (segment.distance < 0.01) {
                return;
            }

            String mode = vehicleMode(vehicleId, networkMode);
            int vehicleIndex = vehicleIndex(vehicleId);
            if (!seenVehicleModes.containsKey(vehicleIndex)) {
                seenVehicleModes.put(vehicleIndex, mode);
                addTransitVehicleMeta(vehicleId, vehicleIndex);
            }
            distanceByMode.merge(mode, segment.distance, Double::sum);
            minTime = Math.min(minTime, segment.startTime);
            maxTime = Math.max(maxTime, segment.endTime);
            pointCount += 2;

            int firstChunk = normalizeChunkStart(segment.startTime);
            int lastChunk = normalizeChunkStart(segment.endTime);
            for (int chunkStart = firstChunk; chunkStart <= lastChunk; chunkStart += TRAJECTORY_CHUNK_SECONDS) {
                chunk(chunkStart).add(segment, modeCode(mode), vehicleIndex);
            }
            pruneOpenChunks();
            progress.markPoint(segment.endTime, seenVehicleModes.size());
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
                    return new ChunkAccumulator(trajectoryCacheDir(data), start, ".part-" + partition);
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
        private final byte[] row = new byte[TRAJECTORY_BINARY_STRIDE * Float.BYTES];
        private final ByteBuffer rowBuffer = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN);
        private final IntOpenHashSet vehicles = new IntOpenHashSet();
        private OutputStream rawOut;
        private long lastUsedAt = 0L;
        private int segmentCount = 0;
        private int pointCount = 0;
        private double minX = Double.POSITIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY;
        private double maxY = Double.NEGATIVE_INFINITY;

        private ChunkAccumulator(Path dir, int start) throws Exception {
            this(dir, start, "");
        }

        private ChunkAccumulator(Path dir, int start, String suffix) throws Exception {
            this.start = normalizeChunkStart(start);
            this.end = this.start + TRAJECTORY_CHUNK_SECONDS - 1;
            Files.createDirectories(dir);
            this.rawPath = dir.resolve(String.format(Locale.ROOT, "chunk-%06d%s.raw.tmp", this.start, suffix));
        }

        private void add(VehicleSegment segment, int modeCode, int vehicleIndex) {
            try {
                ensureOpen();
                rowBuffer.clear();
                rowBuffer.putFloat((float) segment.startTime);
                rowBuffer.putFloat((float) segment.endTime);
                rowBuffer.putFloat((float) segment.fromX);
                rowBuffer.putFloat((float) segment.fromY);
                rowBuffer.putFloat((float) segment.toX);
                rowBuffer.putFloat((float) segment.toY);
                rowBuffer.putFloat((float) modeCode);
                rowBuffer.putFloat((float) vehicleIndex);
                rawOut.write(row);
                lastUsedAt = System.nanoTime();
            } catch (IOException e) {
                throw new RuntimeException("写入轨迹原始分块失败", e);
            }
            vehicles.add(vehicleIndex);
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
                    TRAJECTORY_IO_BUFFER_BYTES
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

        private Map<String, Object> finish(MatsimData data) throws Exception {
            close();
            Map<String, Object> chunk = chunkInfo(start, vehicles.size(), pointCount);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ready");
            payload.put("cacheVersion", TRAJECTORY_CACHE_VERSION);
            payload.put("chunk", chunk);
            payload.put("vehicles", List.of());
            writeGzipJson(trajectoryCacheDir(data).resolve(chunkFileName(start)), payload);

            double originX = segmentCount == 0 ? 0.0 : (minX + maxX) / 2.0;
            double originY = segmentCount == 0 ? 0.0 : (minY + maxY) / 2.0;
            writeTrajectoryBinaryChunkStreaming(
                    trajectoryCacheDir(data).resolve(chunkBinaryFileName(start)),
                    start,
                    end,
                    segmentCount,
                    vehicles.size(),
                    pointCount,
                    originX,
                    originY,
                    rawPath
            );
            Files.deleteIfExists(rawPath);
            return chunk;
        }
    }

    private static class CombinedChunkAccumulator {
        private final int start;
        private final int end;
        private final List<Path> rawPaths = new ArrayList<>();
        private final IntOpenHashSet vehicles = new IntOpenHashSet();
        private int segmentCount = 0;
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
            vehicles.addAll(chunk.vehicles);
            segmentCount += chunk.segmentCount;
            pointCount += chunk.pointCount;
            minX = Math.min(minX, chunk.minX);
            minY = Math.min(minY, chunk.minY);
            maxX = Math.max(maxX, chunk.maxX);
            maxY = Math.max(maxY, chunk.maxY);
        }

        private Map<String, Object> finish(MatsimData data) throws Exception {
            Map<String, Object> chunk = chunkInfo(start, vehicles.size(), pointCount);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ready");
            payload.put("cacheVersion", TRAJECTORY_CACHE_VERSION);
            payload.put("chunk", chunk);
            payload.put("vehicles", List.of());
            writeGzipJson(trajectoryCacheDir(data).resolve(chunkFileName(start)), payload);

            double originX = segmentCount == 0 ? 0.0 : (minX + maxX) / 2.0;
            double originY = segmentCount == 0 ? 0.0 : (minY + maxY) / 2.0;
            writeTrajectoryBinaryChunkStreaming(
                    trajectoryCacheDir(data).resolve(chunkBinaryFileName(start)),
                    start,
                    end,
                    segmentCount,
                    vehicles.size(),
                    pointCount,
                    originX,
                    originY,
                    rawPaths
            );
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
        private final BuildProgress progress;
        private final Map<String, VehicleTrace> traces = new LinkedHashMap<>();
        private int minTime = Integer.MAX_VALUE;
        private int maxTime = Integer.MIN_VALUE;

        private VehicleTrajectoryHandler(Network network, Map<String, TransitVehicleMeta> transitMeta, BuildProgress progress) {
            this.network = network;
            this.transitMeta = transitMeta;
            this.progress = progress;
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
            Link link = network.getLinks().get(linkId);
            if (link == null) {
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
                if (progress != null) {
                    progress.markPoint(segment.endTime, traces.size());
                }
            }
        }

        private void finishLink(Id<Vehicle> vehicleId, Id<Link> linkId, double rawTime, String networkMode) {
            if (vehicleId == null || linkId == null) {
                return;
            }
            Link link = network.getLinks().get(linkId);
            if (link == null) {
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
                if (progress != null) {
                    progress.markPoint(segment.endTime, traces.size());
                }
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
                    roundCoord(to.getY())
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

    private static class VehicleSegment {
        private final int startTime;
        private final int endTime;
        private final double fromX;
        private final double fromY;
        private final double toX;
        private final double toY;
        private final double distance;

        private VehicleSegment(int startTime, int endTime, double fromX, double fromY, double toX, double toY) {
            this.startTime = startTime;
            this.endTime = endTime;
            this.fromX = fromX;
            this.fromY = fromY;
            this.toX = toX;
            this.toY = toY;
            double dx = toX - fromX;
            double dy = toY - fromY;
            this.distance = Math.sqrt(dx * dx + dy * dy);
        }

        private double[] toPayload() {
            return new double[]{startTime, endTime, fromX, fromY, toX, toY};
        }
    }
}
