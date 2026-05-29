package com.jts.gjcxfzksh.data.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import com.jts.gjcxfzksh.data.handler.PTHandler;
import com.jts.gjcxfzksh.data.id.DepartureId;
import com.jts.gjcxfzksh.data.id.LineId;
import com.jts.gjcxfzksh.data.id.PersonId;
import com.jts.gjcxfzksh.data.id.RouteId;
import com.jts.gjcxfzksh.data.id.StopFacilityId;
import com.jts.gjcxfzksh.data.id.VehicleId;
import com.jts.gjcxfzksh.data.read.EventReader;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
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
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;

@Slf4j
public final class MatsimAnalysisCache {

    public static final String TRAJECTORY_CACHE_VERSION = "trajectory-v6";
    public static final int TRAJECTORY_CHUNK_SECONDS = 300;

    private static final String PERSON_TRACK_CACHE_VERSION = "pt-events-v1";
    private static final String PERSON_TRACK_FILE = "person-tracks.tsv.gz";
    private static final byte[] TRAJECTORY_BINARY_MAGIC = new byte[]{'G', 'J', 'T', 'B'};
    private static final int TRAJECTORY_BINARY_VERSION = 1;
    private static final int TRAJECTORY_BINARY_HEADER_BYTES = 64;
    private static final int TRAJECTORY_BINARY_STRIDE = 8;
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

    public static void prepareOnModelLoad(MatsimData data) {
        try {
            if (data.isLargeModel()) {
                log.info("模型[{}]为大模型，跳过内存型乘客上下车缓存加载，等待离线流式缓存任务", data.getName());
                return;
            }
            boolean personTracksReady = loadPersonTracksFromCache(data);
            if (personTracksReady) {
                log.info("读取模型乘客上下车轻量缓存: model={}, personTracks={}", data.getName(), true);
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
                Files.createDirectories(trajectoryCacheDir(data));
                clearTrajectoryChunks(data);
                TrajectoryMeta trajectoryMeta = buildTrajectoryMeta(data);
                LargeTrajectoryStreamHandler handler = new LargeTrajectoryStreamHandler(data, trajectoryMeta, progress);
                try {
                    new EventReader(handler).read(data.getOutfile().getEvents());
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
        Map<String, Object> manifest = readReadyTrajectoryManifest(data);
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
        Map<String, Object> manifest = readReadyTrajectoryManifest(data);
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

    private static boolean loadPersonTracksFromCache(MatsimData data) {
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
            Set<PTPersonTrack> tracks = readPersonTracks(tracksPath);
            data.setPersonTracks(tracks);
            log.info("读取乘客上下车轻量缓存: model={}, tracks={}", data.getName(), tracks.size());
            return true;
        } catch (Exception e) {
            log.warn("乘客上下车缓存读取失败: {}", tracksPath, e);
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
        writeJsonAtomic(trajectoryManifestPath(data), manifest);
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
                    || path.getFileName().toString().endsWith(".bin.tmp"))).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static TrajectoryMeta buildTrajectoryMeta(MatsimData data) {
        Map<String, TransitVehicleMeta> transitVehicles = new HashMap<>();
        Map<String, RouteMeta> routes = new LinkedHashMap<>();
        TransitSchedule schedule = data.getSchedule();
        schedule.getTransitLines().forEach((lineId, line) -> {
            String lineIdText = lineId.toString();
            String lineName = nonBlank(line.getName(), lineIdText);
            line.getRoutes().forEach((routeId, route) -> {
                String routeIdText = routeId.toString();
                String mode = normalizeVehicleMode(route.getTransportMode(), true);
                routes.putIfAbsent(routeIdText, new RouteMeta(
                        mode,
                        lineIdText,
                        lineName,
                        routeIdText,
                        routeIdText,
                        routeStops(route),
                        firstDepartureTime(route),
                        lastDepartureTime(route)
                ));
                route.getDepartures().forEach((departureId, departure) -> {
                    if (departure.getVehicleId() == null) {
                        return;
                    }
                    transitVehicles.put(
                            departure.getVehicleId().toString(),
                            new TransitVehicleMeta(mode, lineIdText, routeIdText)
                    );
                });
            });
        });
        return new TrajectoryMeta(transitVehicles, routes, buildCarTripMeta(data.getPopulation()));
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
        Path tmpPath = path.resolveSibling(path.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmpPath);
             InputStream in = Files.newInputStream(rawPath)) {
            writeTrajectoryBinaryHeader(out, chunkStart, chunkEnd, segmentCount, vehicleCount, pointCount, originX, originY);
            byte[] row = new byte[TRAJECTORY_BINARY_STRIDE * Float.BYTES];
            while (readFully(in, row)) {
                ByteBuffer input = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN);
                float startTime = input.getFloat();
                float endTime = input.getFloat();
                float startX = input.getFloat();
                float startY = input.getFloat();
                float endX = input.getFloat();
                float endY = input.getFloat();
                float modeCode = input.getFloat();
                float vehicleIndex = input.getFloat();

                writeFloatLE(out, startTime);
                writeFloatLE(out, endTime);
                writeFloatLE(out, (float) (startX - originX));
                writeFloatLE(out, (float) (startY - originY));
                writeFloatLE(out, (float) (endX - originX));
                writeFloatLE(out, (float) (endY - originY));
                writeFloatLE(out, modeCode);
                writeFloatLE(out, vehicleIndex);
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
                return offset == 0 ? false : offset == bytes.length;
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
        Path tmpPath = path.resolveSibling(path.getFileName() + ".tmp");
        JSON.writeValue(tmpPath.toFile(), payload);
        try {
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING);
        }
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
        if (text.contains("subway") || text.contains("metro") || text.contains("rail") || text.contains("train")) {
            return "subway";
        }
        if (transitVehicle || text.contains("bus") || text.contains("pt")) {
            return "bus";
        }
        return "car";
    }

    private static int modeCode(String mode) {
        return switch (normalizeVehicleMode(mode, false)) {
            case "bus" -> 0;
            case "subway" -> 1;
            default -> 2;
        };
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

        private TrajectoryMeta(
                Map<String, TransitVehicleMeta> transitVehicles,
                Map<String, RouteMeta> routes,
                Map<String, CarTripMeta> carTrips
        ) {
            this.transitVehicles = transitVehicles;
            this.routes = routes;
            this.carTrips = carTrips;
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

    private static class LargeTrajectoryStreamHandler implements
            LinkEnterEventHandler,
            LinkLeaveEventHandler,
            VehicleEntersTrafficEventHandler,
            VehicleLeavesTrafficEventHandler,
            VehicleArrivesAtFacilityEventHandler,
            VehicleDepartsAtFacilityEventHandler,
            PersonEntersVehicleEventHandler,
            PersonLeavesVehicleEventHandler {

        private static final int FLOAT_SAFE_HASH_MOD = 16_000_000;

        private final MatsimData data;
        private final Network network;
        private final TrajectoryMeta trajectoryMeta;
        private final BuildProgress progress;
        private final Map<String, ActiveLink> activeLinks = new HashMap<>();
        private final Map<String, String> currentFacilityByVehicle = new HashMap<>();
        private final Map<String, String> departureByVehicle = new HashMap<>();
        private final Map<Integer, ChunkAccumulator> chunks = new LinkedHashMap<>();
        private final Map<Integer, long[]> passengerBins = new java.util.TreeMap<>();
        private final Map<String, Integer> routeBoardings = new HashMap<>();
        private final Map<String, Long> vehicleCountByMode = emptyLongModeMap();
        private final Map<String, Double> distanceByMode = emptyDoubleModeMap();
        private final IntOpenHashSet seenVehicles = new IntOpenHashSet();
        private final IntOpenHashSet seenTransitVehicleMeta = new IntOpenHashSet();
        private final List<Map<String, Object>> vehicleMetaPayloads = new ArrayList<>();
        private final BufferedWriter personTrackWriter;
        private long passengerBoardings = 0;
        private long trackCount = 0;
        private long pointCount = 0;
        private int minTime = Integer.MAX_VALUE;
        private int maxTime = Integer.MIN_VALUE;

        private LargeTrajectoryStreamHandler(MatsimData data, TrajectoryMeta trajectoryMeta, BuildProgress progress) throws Exception {
            this.data = data;
            this.network = data.getNetwork();
            this.trajectoryMeta = trajectoryMeta;
            this.progress = progress;
            buildDepartureIndex(data.getSchedule());
            Files.createDirectories(personTrackCacheDir(data));
            this.personTrackWriter = new BufferedWriter(new OutputStreamWriter(
                    new GZIPOutputStream(Files.newOutputStream(personTracksPath(data).resolveSibling(PERSON_TRACK_FILE + ".tmp"))),
                    StandardCharsets.UTF_8
            ));
            personTrackWriter.write("time\tenter\tpersonId\tlineId\trouteId\tvehicleId\tdepartureId\tfacilityId");
            personTrackWriter.newLine();
        }

        @Override
        public void handleEvent(VehicleEntersTrafficEvent event) {
            startLink(event.getVehicleId(), event.getLinkId(), event.getTime(), event.getNetworkMode());
        }

        @Override
        public void handleEvent(LinkEnterEvent event) {
            startLink(event.getVehicleId(), event.getLinkId(), event.getTime(), null);
        }

        @Override
        public void handleEvent(LinkLeaveEvent event) {
            finishLink(event.getVehicleId(), event.getLinkId(), event.getTime(), null);
        }

        @Override
        public void handleEvent(VehicleLeavesTrafficEvent event) {
            if (event.getVehicleId() != null) {
                finishLink(event.getVehicleId(), event.getLinkId(), event.getTime(), event.getNetworkMode());
            }
        }

        @Override
        public void handleEvent(VehicleArrivesAtFacilityEvent event) {
            currentFacilityByVehicle.put(event.getVehicleId().toString(), event.getFacilityId().toString());
        }

        @Override
        public void handleEvent(VehicleDepartsAtFacilityEvent event) {
            currentFacilityByVehicle.remove(event.getVehicleId().toString());
        }

        @Override
        public void handleEvent(PersonEntersVehicleEvent event) {
            writePassengerTrack(event.getTime(), true, event.getPersonId().toString(), event.getVehicleId().toString());
        }

        @Override
        public void handleEvent(PersonLeavesVehicleEvent event) {
            writePassengerTrack(event.getTime(), false, event.getPersonId().toString(), event.getVehicleId().toString());
        }

        private void buildDepartureIndex(TransitSchedule schedule) {
            schedule.getTransitLines().forEach((lineId, line) -> line.getRoutes().forEach((routeId, route) -> {
                route.getDepartures().forEach((departureId, departure) -> {
                    if (departure.getVehicleId() != null) {
                        departureByVehicle.put(departure.getVehicleId().toString(), departureId.toString());
                    }
                });
            }));
        }

        private void startLink(Id<Vehicle> vehicleId, Id<Link> linkId, double rawTime, String networkMode) {
            if (vehicleId == null || linkId == null) {
                return;
            }
            Link link = network.getLinks().get(linkId);
            if (link == null) {
                return;
            }
            String vehicle = vehicleId.toString();
            ActiveLink active = activeLinks.get(vehicle);
            int time = Math.max(0, (int) Math.round(rawTime));
            if (active != null && !active.linkId.equals(linkId.toString())) {
                writeSegment(vehicle, active, time, networkMode, active.link);
            }
            activeLinks.put(vehicle, new ActiveLink(linkId.toString(), time, link));
        }

        private void finishLink(Id<Vehicle> vehicleId, Id<Link> linkId, double rawTime, String networkMode) {
            if (vehicleId == null || linkId == null) {
                return;
            }
            Link link = network.getLinks().get(linkId);
            if (link == null) {
                return;
            }
            String vehicle = vehicleId.toString();
            int time = Math.max(0, (int) Math.round(rawTime));
            ActiveLink active = activeLinks.get(vehicle);
            if (active == null || !active.linkId.equals(linkId.toString())) {
                active = new ActiveLink(linkId.toString(), time, link);
            }
            writeSegment(vehicle, active, time, networkMode, link);
            activeLinks.remove(vehicle);
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
            if (seenVehicles.add(vehicleIndex)) {
                vehicleCountByMode.merge(mode, 1L, Long::sum);
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
            if (progress != null) {
                progress.markPoint(segment.endTime, seenVehicles.size());
            }
        }

        private void writePassengerTrack(double rawTime, boolean enter, String personId, String vehicleId) {
            TransitVehicleMeta meta = trajectoryMeta.transitVehicles.get(vehicleId);
            String facilityId = currentFacilityByVehicle.get(vehicleId);
            if (meta == null || facilityId == null) {
                return;
            }
            int time = roundTime(rawTime);
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
                personTrackWriter.write(tsv(departureByVehicle.get(vehicleId)));
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
                    return new ChunkAccumulator(trajectoryCacheDir(data), start);
                } catch (Exception e) {
                    throw new RuntimeException("创建轨迹分块缓存失败", e);
                }
            });
        }

        private String vehicleMode(String vehicleId, String networkMode) {
            TransitVehicleMeta meta = trajectoryMeta.transitVehicles.get(vehicleId);
            return meta == null ? normalizeVehicleMode(networkMode, false) : meta.mode;
        }

        private int vehicleIndex(String vehicleId) {
            return Math.floorMod(vehicleId == null ? 0 : vehicleId.hashCode(), FLOAT_SAFE_HASH_MOD);
        }

        private void addTransitVehicleMeta(String vehicleId, int vehicleIndex) {
            TransitVehicleMeta meta = trajectoryMeta.transitVehicles.get(vehicleId);
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

        private Map<String, Object> finish() throws Exception {
            personTrackWriter.close();
            for (ChunkAccumulator chunk : chunks.values()) {
                chunk.close();
            }
            writePersonTracksManifest();

            List<Map<String, Object>> chunkPayloads = new ArrayList<>();
            for (ChunkAccumulator chunk : chunks.values()) {
                chunkPayloads.add(chunk.finish(data));
            }

            if (minTime == Integer.MAX_VALUE || maxTime == Integer.MIN_VALUE) {
                minTime = 0;
                maxTime = 86400;
            }
            distanceByMode.replaceAll((mode, distance) -> round2(distance / 1000.0));

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("totalVehicles", seenVehicles.size());
            summary.put("vehicleCountByMode", vehicleCountByMode);
            summary.put("totalPassengerBoardings", passengerBoardings);
            summary.put("distanceKmByMode", distanceByMode);
            summary.put("pointCount", pointCount);
            summary.put("routeBoardings", topRouteBoardings());
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
            manifest.put("passengerSeries", passengerSeriesPayload());
            manifest.put("meta", metaPayload);
            manifest.put("vehicles", List.of());
            writeJsonAtomic(trajectoryManifestPath(data), manifest);
            return manifest;
        }

        private void writePersonTracksManifest() throws Exception {
            Path tmpPath = personTracksPath(data).resolveSibling(PERSON_TRACK_FILE + ".tmp");
            try {
                Files.move(tmpPath, personTracksPath(data), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(tmpPath, personTracksPath(data), StandardCopyOption.REPLACE_EXISTING);
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
            writeJsonAtomic(personTrackManifestPath(data), manifest);
        }

        private List<List<Object>> passengerSeriesPayload() {
            List<List<Object>> result = new ArrayList<>(passengerBins.size());
            passengerBins.forEach((time, counts) -> result.add(List.of(time, counts[0], counts[1], counts[2], counts[3])));
            return result;
        }

        private Map<String, Integer> topRouteBoardings() {
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

        private void abort() {
            try {
                personTrackWriter.close();
            } catch (Exception ignored) {
            }
            for (ChunkAccumulator chunk : chunks.values()) {
                try {
                    chunk.close();
                    Files.deleteIfExists(chunk.rawPath);
                } catch (Exception ignored) {
                }
            }
            try {
                Files.deleteIfExists(personTracksPath(data).resolveSibling(PERSON_TRACK_FILE + ".tmp"));
            } catch (Exception ignored) {
            }
        }
    }

    private static class ChunkAccumulator {
        private final int start;
        private final int end;
        private final Path rawPath;
        private final OutputStream rawOut;
        private final IntOpenHashSet vehicles = new IntOpenHashSet();
        private int segmentCount = 0;
        private int pointCount = 0;
        private double minX = Double.POSITIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY;
        private double maxY = Double.NEGATIVE_INFINITY;

        private ChunkAccumulator(Path dir, int start) throws Exception {
            this.start = normalizeChunkStart(start);
            this.end = this.start + TRAJECTORY_CHUNK_SECONDS - 1;
            Files.createDirectories(dir);
            this.rawPath = dir.resolve(String.format(Locale.ROOT, "chunk-%06d.raw.tmp", this.start));
            this.rawOut = Files.newOutputStream(rawPath);
        }

        private void add(VehicleSegment segment, int modeCode, int vehicleIndex) {
            try {
                writeFloatLE(rawOut, (float) segment.startTime);
                writeFloatLE(rawOut, (float) segment.endTime);
                writeFloatLE(rawOut, (float) segment.fromX);
                writeFloatLE(rawOut, (float) segment.fromY);
                writeFloatLE(rawOut, (float) segment.toX);
                writeFloatLE(rawOut, (float) segment.toY);
                writeFloatLE(rawOut, (float) modeCode);
                writeFloatLE(rawOut, (float) vehicleIndex);
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

        private void close() throws IOException {
            rawOut.close();
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
                    rawPath
            );
            Files.deleteIfExists(rawPath);
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
