package com.jts.gjcxfzksh.data.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Slf4j
public final class MatsimStationPanelCache {

    public static final String STATION_PANEL_CACHE_VERSION = "station-panel-v3";

    private static final String PANEL_FILE = "station-panel.json.gz";
    private static final String MANIFEST_FILE = "manifest.json";
    private static final int HOURS = 24;
    private static final int LEADERBOARD_LIMIT = 50;
    private static final int OD_LIMIT = 12;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private MatsimStationPanelCache() {
    }

    public static void prepareOnModelLoad(MatsimData data) {
        ensureStationPanelCache(data);
    }

    public static Map<String, Object> readStationPanel(MatsimData data) {
        if (!isReady(data)) {
            return Map.of(
                    "status", "generating",
                    "cacheVersion", STATION_PANEL_CACHE_VERSION,
                    "message", "站点客流缓存正在后台生成"
            );
        }
        try {
            return readGzipJson(panelPath(data));
        } catch (Exception e) {
            log.warn("读取站点客流面板缓存失败: model={}, path={}", data.getName(), panelPath(data), e);
            return Map.of();
        }
    }

    private static synchronized void ensureStationPanelCache(MatsimData data) {
        if (isReady(data)) {
            return;
        }
        try {
            Files.createDirectories(cacheDir(data));
            Map<String, Object> payload = buildPanel(data);
            writeGzipJson(panelPath(data), payload);
            writeJsonAtomic(manifestPath(data), manifest(data, true));
            log.info("站点客流面板缓存生成完成: model={}, stations={}",
                    data.getName(), ((Map<?, ?>) payload.getOrDefault("stations", Map.of())).size());
        } catch (Exception e) {
            try {
                Files.createDirectories(cacheDir(data));
                writeJsonAtomic(manifestPath(data), manifest(data, false));
            } catch (Exception ignored) {
            }
            throw new RuntimeException("站点客流面板缓存生成失败: " + e.getMessage(), e);
        }
    }

    public static boolean isReady(MatsimData data) {
        if (!Files.exists(manifestPath(data)) || !Files.exists(panelPath(data))) {
            return false;
        }
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath(data).toFile(), MAP_TYPE);
            return "ready".equals(manifest.get("status"))
                    && STATION_PANEL_CACHE_VERSION.equals(manifest.get("cacheVersion"))
                    && sameSources(data, manifest);
        } catch (Exception e) {
            log.warn("站点客流面板缓存状态读取失败: {}", manifestPath(data), e);
            return false;
        }
    }

    private static Map<String, Object> buildPanel(MatsimData data) {
        StationNetworkIndex index = buildStationNetworkIndex(data);
        Map<String, StationPanelAccumulator> stations = buildStationAccumulators(data, index);

        indexPassengerTracks(data.getPersonTracks(), stations, index);
        indexStationOd(data.getPersonTracks(), stations, index);
        indexReachability(stations, index);

        Map<String, Object> stationPayloads = new LinkedHashMap<>();
        stations.values().stream()
                .sorted(Comparator.comparing(station -> station.stationName, String::compareToIgnoreCase))
                .forEach(station -> stationPayloads.put(station.stationName, station.toPayload()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ready");
        result.put("cacheVersion", STATION_PANEL_CACHE_VERSION);
        result.put("generatedAt", System.currentTimeMillis());
        result.put("summary", buildSummary(stations.values()));
        result.put("stations", stationPayloads);
        return result;
    }

    private static StationNetworkIndex buildStationNetworkIndex(MatsimData data) {
        StationNetworkIndex index = new StationNetworkIndex();

        for (Map.Entry<Id<TransitStopFacility>, TransitStopFacility> entry : data.getSchedule().getFacilities().entrySet()) {
            String facilityId = entry.getKey().toString();
            TransitStopFacility facility = entry.getValue();
            index.facilityToName.put(facilityId, nonBlank(facility.getName(), facilityId));
        }

        for (Map.Entry<Id<TransitLine>, TransitLine> lineEntry : data.getSchedule().getTransitLines().entrySet()) {
            String lineId = lineEntry.getKey().toString();
            TransitLine line = lineEntry.getValue();
            String lineName = nonBlank(line.getName(), lineId);
            for (Map.Entry<Id<TransitRoute>, TransitRoute> routeEntry : line.getRoutes().entrySet()) {
                String routeId = routeEntry.getKey().toString();
                TransitRoute route = routeEntry.getValue();
                RouteMeta routeMeta = new RouteMeta(lineId, lineName, routeId, route);
                index.routes.put(routeId, routeMeta);
                index.routeToStations.put(routeId, routeMeta.stationNames);
                for (String stationName : routeMeta.stationNames) {
                    index.stationToRoutes.computeIfAbsent(stationName, ignored -> new LinkedHashSet<>()).add(routeId);
                }
            }
        }

        return index;
    }

    private static Map<String, StationPanelAccumulator> buildStationAccumulators(
            MatsimData data,
            StationNetworkIndex index
    ) {
        Map<String, StationPanelAccumulator> stations = new LinkedHashMap<>();
        for (Map.Entry<Id<TransitStopFacility>, TransitStopFacility> entry : data.getSchedule().getFacilities().entrySet()) {
            String facilityId = entry.getKey().toString();
            String stationName = index.stationName(facilityId);
            stations.computeIfAbsent(stationName, StationPanelAccumulator::new).addFacility(facilityId);
        }

        for (RouteMeta route : index.routes.values()) {
            for (String stationName : route.stationNames) {
                stations.computeIfAbsent(stationName, StationPanelAccumulator::new).addRoute(route);
            }
        }
        return stations;
    }

    private static void indexPassengerTracks(
            Collection<PTPersonTrack> tracks,
            Map<String, StationPanelAccumulator> stations,
            StationNetworkIndex index
    ) {
        if (tracks == null || tracks.isEmpty()) {
            return;
        }
        for (PTPersonTrack track : tracks) {
            String stationName = index.stationName(idString(track.getFacilityId()));
            StationPanelAccumulator station = stations.computeIfAbsent(stationName, StationPanelAccumulator::new);
            station.addTrack(track);
        }
    }

    private static void indexStationOd(
            Collection<PTPersonTrack> tracks,
            Map<String, StationPanelAccumulator> stations,
            StationNetworkIndex index
    ) {
        if (tracks == null || tracks.isEmpty()) {
            return;
        }

        Map<String, List<PTPersonTrack>> byPerson = new HashMap<>();
        for (PTPersonTrack track : tracks) {
            String personId = idString(track.getPersonId());
            if (personId != null) {
                byPerson.computeIfAbsent(personId, ignored -> new ArrayList<>()).add(track);
            }
        }

        for (List<PTPersonTrack> personTracks : byPerson.values()) {
            personTracks.sort(Comparator.comparingDouble(MatsimStationPanelCache::safeTime));
            PTPersonTrack openBoarding = null;
            for (PTPersonTrack track : personTracks) {
                if (Boolean.TRUE.equals(track.getEnter())) {
                    openBoarding = track;
                    continue;
                }
                if (openBoarding == null) {
                    continue;
                }
                String origin = index.stationName(idString(openBoarding.getFacilityId()));
                String destination = index.stationName(idString(track.getFacilityId()));
                if (!origin.equals(destination)) {
                    int hour = hourOf(safeTime(openBoarding));
                    stations.computeIfAbsent(origin, StationPanelAccumulator::new).addOd(origin, destination, hour);
                    stations.computeIfAbsent(destination, StationPanelAccumulator::new).addOd(origin, destination, hour);
                }
                openBoarding = null;
            }
        }
    }

    private static void indexReachability(Map<String, StationPanelAccumulator> stations, StationNetworkIndex index) {
        for (StationPanelAccumulator station : stations.values()) {
            Set<String> seenRoutes = new LinkedHashSet<>();
            Set<String> seenStations = new LinkedHashSet<>();
            seenStations.add(station.stationName);

            Set<String> directRoutes = new LinkedHashSet<>(index.stationToRoutes.getOrDefault(station.stationName, Set.of()));
            seenRoutes.addAll(directRoutes);
            Set<String> direct = newStationsByRoutes(directRoutes, seenStations, index);
            seenStations.addAll(direct);

            Set<String> transfer1Routes = nextSameStationTransferRoutes(directRoutes, seenRoutes, index);
            seenRoutes.addAll(transfer1Routes);
            Set<String> transfer1 = newStationsByRoutes(transfer1Routes, seenStations, index);
            seenStations.addAll(transfer1);

            Set<String> transfer2Routes = nextSameStationTransferRoutes(transfer1Routes, seenRoutes, index);
            Set<String> transfer2 = newStationsByRoutes(transfer2Routes, seenStations, index);

            station.setReachability(direct.size(), transfer1.size(), transfer2.size());
        }
    }

    private static Set<String> nextSameStationTransferRoutes(Set<String> frontierRoutes, Set<String> seenRoutes, StationNetworkIndex index) {
        Set<String> result = new LinkedHashSet<>();
        for (String stationName : stationsByRoutes(frontierRoutes, index)) {
            for (String routeId : index.stationToRoutes.getOrDefault(stationName, Set.of())) {
                if (!seenRoutes.contains(routeId)) {
                    result.add(routeId);
                }
            }
        }
        return result;
    }

    private static Set<String> newStationsByRoutes(Set<String> routeIds, Set<String> seenStations, StationNetworkIndex index) {
        Set<String> result = stationsByRoutes(routeIds, index);
        result.removeAll(seenStations);
        return result;
    }

    private static Set<String> stationsByRoutes(Set<String> routeIds, StationNetworkIndex index) {
        Set<String> result = new LinkedHashSet<>();
        for (String routeId : routeIds) {
            result.addAll(index.routeToStations.getOrDefault(routeId, Set.of()));
        }
        return result;
    }

    private static Map<String, Object> buildSummary(Collection<StationPanelAccumulator> stations) {
        Map<String, Object> leaderboard = new LinkedHashMap<>();
        leaderboard.put("bus", leaderboard(stations, "bus"));
        leaderboard.put("subway", leaderboard(stations, "subway"));

        long totalBoardings = 0;
        long totalAlightings = 0;
        for (StationPanelAccumulator station : stations) {
            totalBoardings += station.totalBoardings;
            totalAlightings += station.totalAlightings;
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("stationCount", stations.size());
        summary.put("totalBoardings", totalBoardings);
        summary.put("totalAlightings", totalAlightings);
        summary.put("leaderboard", leaderboard);
        return summary;
    }

    private static List<Map<String, Object>> leaderboard(Collection<StationPanelAccumulator> stations, String mode) {
        return stations.stream()
                .filter(station -> mode.equals(station.mode()))
                .sorted(Comparator.comparingLong(StationPanelAccumulator::passengerFlow).reversed())
                .limit(LEADERBOARD_LIMIT)
                .map(StationPanelAccumulator::toLeaderboardPayload)
                .toList();
    }

    private static Map<String, Object> manifest(MatsimData data, boolean ready) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", ready ? "ready" : "failed");
        result.put("cacheVersion", STATION_PANEL_CACHE_VERSION);
        result.put("generatedAt", System.currentTimeMillis());
        sourceFingerprint(data, result);
        return result;
    }

    private static void sourceFingerprint(MatsimData data, Map<String, Object> result) {
        putFileFingerprint(result, "events", data.getOutfile().getEvents());
        putFileFingerprint(result, "network", data.getOutfile().getNetwork());
        putFileFingerprint(result, "schedule", data.getOutfile().getTransitSchedule());
        putFileFingerprint(result, "vehicles", data.getOutfile().getTransitVehicles());
        putFileFingerprint(result, "plans", data.getOutfile().getPlans());
    }

    private static void putFileFingerprint(Map<String, Object> result, String key, String filePath) {
        result.put(key + "File", filePath);
        result.put(key + "Modified", lastModified(filePath));
        result.put(key + "Size", fileSize(filePath));
    }

    private static boolean sameSources(MatsimData data, Map<String, Object> manifest) {
        Map<String, Object> current = new LinkedHashMap<>();
        sourceFingerprint(data, current);
        for (Map.Entry<String, Object> entry : current.entrySet()) {
            Object oldValue = manifest.get(entry.getKey());
            if (entry.getValue() instanceof Number number) {
                if (!(oldValue instanceof Number oldNumber) || oldNumber.longValue() != number.longValue()) {
                    return false;
                }
            } else if (!String.valueOf(entry.getValue()).equals(String.valueOf(oldValue))) {
                return false;
            }
        }
        return true;
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

    private static void writeGzipJson(Path path, Map<String, Object> payload) throws Exception {
        Files.createDirectories(path.getParent());
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

    private static Map<String, Object> readGzipJson(Path path) throws Exception {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(path))) {
            return JSON.readValue(in, MAP_TYPE);
        }
    }

    private static Path cacheDir(MatsimData data) {
        return MatsimCachePaths.versionDir(data, STATION_PANEL_CACHE_VERSION);
    }

    private static Path manifestPath(MatsimData data) {
        return cacheDir(data).resolve(MANIFEST_FILE);
    }

    private static Path panelPath(MatsimData data) {
        return cacheDir(data).resolve(PANEL_FILE);
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

    private static int hourOf(double seconds) {
        if (Double.isNaN(seconds) || Double.isInfinite(seconds)) {
            return 0;
        }
        return Math.max(0, Math.min(HOURS - 1, (int) Math.floor(Math.max(0, seconds) / 3600.0)));
    }

    private static double safeTime(PTPersonTrack track) {
        Double time = track.getTime();
        if (time == null || Double.isNaN(time) || Double.isInfinite(time)) {
            return 0.0;
        }
        return time;
    }

    private static String idString(Object value) {
        return value == null ? null : value.toString();
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int intSum(int[] values) {
        int result = 0;
        for (int value : values) {
            result += value;
        }
        return result;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String normalizeVehicleMode(String rawText) {
        String text = rawText == null ? "" : rawText.toLowerCase(Locale.ROOT);
        if (text.contains("subway") || text.contains("metro") || text.contains("rail")
                || text.contains("train") || text.contains("地铁") || text.contains("轨道")) {
            return "subway";
        }
        return "bus";
    }

    private static final class StationNetworkIndex {
        private final Map<String, String> facilityToName = new HashMap<>();
        private final Map<String, RouteMeta> routes = new LinkedHashMap<>();
        private final Map<String, Set<String>> stationToRoutes = new HashMap<>();
        private final Map<String, Set<String>> routeToStations = new HashMap<>();

        private String stationName(String facilityId) {
            if (facilityId == null || facilityId.isBlank()) {
                return "unknown";
            }
            return nonBlank(facilityToName.get(facilityId), facilityId);
        }
    }

    private static final class RouteMeta {
        private final String lineId;
        private final String lineName;
        private final String routeId;
        private final String routeName;
        private final String mode;
        private final String desc;
        private final Set<String> stationNames = new LinkedHashSet<>();

        private RouteMeta(String lineId, String lineName, String routeId, TransitRoute route) {
            this.lineId = lineId;
            this.lineName = lineName;
            this.routeId = routeId;
            this.routeName = nonBlank(route.getDescription(), routeId);
            this.mode = normalizeVehicleMode(lineName + " " + routeId + " " + routeName + " " + route.getTransportMode());
            for (TransitRouteStop stop : route.getStops()) {
                TransitStopFacility facility = stop.getStopFacility();
                String facilityId = facility.getId().toString();
                stationNames.add(nonBlank(facility.getName(), facilityId));
            }
            this.desc = routeDesc(stationNames);
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("lineId", lineId);
            payload.put("lineName", lineName);
            payload.put("routeId", routeId);
            payload.put("routeName", routeName);
            payload.put("mode", mode);
            payload.put("desc", desc);
            return payload;
        }

        private static String routeDesc(Set<String> stationNames) {
            if (stationNames.size() < 2) {
                return "";
            }
            List<String> names = new ArrayList<>(stationNames);
            return names.getFirst() + " - " + names.getLast();
        }
    }

    private static final class StationPanelAccumulator {
        private final String stationName;
        private final Set<String> facilityIds = new LinkedHashSet<>();
        private final Map<String, RouteMeta> routes = new LinkedHashMap<>();
        private final Set<String> riderIds = new LinkedHashSet<>();
        private final int[] boardingByHour = new int[HOURS];
        private final int[] alightingByHour = new int[HOURS];
        private final Map<String, OdAccumulator> od = new HashMap<>();
        private int directReachable = 0;
        private int transfer1Reachable = 0;
        private int transfer2Reachable = 0;
        private long totalBoardings = 0;
        private long totalAlightings = 0;

        private StationPanelAccumulator(String stationName) {
            this.stationName = stationName;
        }

        private void addFacility(String facilityId) {
            if (facilityId != null && !facilityId.isBlank()) {
                facilityIds.add(facilityId);
            }
        }

        private void addRoute(RouteMeta route) {
            routes.putIfAbsent(route.routeId, route);
        }

        private void addTrack(PTPersonTrack track) {
            int hour = hourOf(safeTime(track));
            if (Boolean.TRUE.equals(track.getEnter())) {
                boardingByHour[hour]++;
                totalBoardings++;
            } else {
                alightingByHour[hour]++;
                totalAlightings++;
            }
            String personId = idString(track.getPersonId());
            if (personId != null) {
                riderIds.add(personId);
            }
        }

        private void addOd(String origin, String destination, int hour) {
            String key = origin + "::" + destination;
            od.computeIfAbsent(key, ignored -> new OdAccumulator(origin, destination)).flowByHour[hour]++;
        }

        private void setReachability(int direct, int transfer1, int transfer2) {
            this.directReachable = direct;
            this.transfer1Reachable = transfer1;
            this.transfer2Reachable = transfer2;
        }

        private String mode() {
            return routes.values().stream().anyMatch(route -> "subway".equals(route.mode)) ? "subway" : "bus";
        }

        private long passengerFlow() {
            return totalBoardings + totalAlightings;
        }

        private int[] hourlyFlow() {
            int[] result = new int[HOURS];
            for (int i = 0; i < HOURS; i++) {
                result[i] = boardingByHour[i] + alightingByHour[i];
            }
            return result;
        }

        private int peakFlow() {
            int peak = 0;
            for (int value : hourlyFlow()) {
                peak = Math.max(peak, value);
            }
            return peak;
        }

        private double transferScore() {
            double score = 4.0
                    + Math.min(3.0, routes.size() * 0.45)
                    + Math.min(2.0, directReachable / 40.0)
                    + Math.min(1.0, transfer1Reachable / 180.0);
            return round1(Math.min(10.0, score));
        }

        private String desc() {
            List<String> lineNames = routes.values().stream()
                    .map(route -> route.lineName)
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .limit(3)
                    .toList();
            if (lineNames.isEmpty()) {
                return "";
            }
            String prefix = "subway".equals(mode()) ? "地铁 " : "公交 ";
            String suffix = routes.size() > lineNames.size() ? "等途经" : "途经";
            return prefix + String.join("/", lineNames) + suffix;
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("stationName", stationName);
            payload.put("facilityIds", facilityIds);
            payload.put("mode", mode());
            payload.put("desc", desc());
            payload.put("hours", List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23));
            payload.put("hourlyFlow", hourlyFlow());
            payload.put("boardingByHour", boardingByHour);
            payload.put("alightingByHour", alightingByHour);
            payload.put("routes", routes.values().stream()
                    .sorted(Comparator.comparing(route -> route.lineName))
                    .map(RouteMeta::toPayload)
                    .toList());
            payload.put("od", odPayloads());

            Map<String, Object> reachability = new LinkedHashMap<>();
            reachability.put("direct", directReachable);
            reachability.put("transfer1", transfer1Reachable);
            reachability.put("transfer2", transfer2Reachable);
            payload.put("reachability", reachability);

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("passenger", passengerFlow());
            metrics.put("boarding", totalBoardings);
            metrics.put("alighting", totalAlightings);
            metrics.put("peakFlow", peakFlow());
            metrics.put("population", riderIds.size());
            metrics.put("transferScore", transferScore());
            metrics.put("routeCount", routes.size());
            metrics.put("facilityCount", facilityIds.size());
            payload.put("metrics", metrics);

            return payload;
        }

        private Map<String, Object> toLeaderboardPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("stationName", stationName);
            payload.put("facilityIds", facilityIds);
            payload.put("mode", mode());
            payload.put("desc", desc());
            payload.put("passengerFlow", passengerFlow());
            return payload;
        }

        private List<Map<String, Object>> odPayloads() {
            int totalFlow = od.values().stream().mapToInt(OdAccumulator::flow).sum();
            return od.values().stream()
                    .sorted(Comparator.comparingInt(OdAccumulator::flow).reversed())
                    .limit(OD_LIMIT)
                    .map(item -> item.toPayload(stationName, totalFlow))
                    .toList();
        }
    }

    private static final class OdAccumulator {
        private final String origin;
        private final String destination;
        private final int[] flowByHour = new int[HOURS];

        private OdAccumulator(String origin, String destination) {
            this.origin = origin;
            this.destination = destination;
        }

        private int flow() {
            return intSum(flowByHour);
        }

        private Map<String, Object> toPayload(String stationName, int totalFlow) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("origin", origin);
            payload.put("destination", destination);
            payload.put("counterpart", stationName.equals(origin) ? destination : origin);
            payload.put("flowByHour", flowByHour);
            payload.put("flow", flow());
            payload.put("ratio", totalFlow == 0 ? 0.0 : round2(flow() * 100.0 / totalFlow));
            return payload;
        }
    }
}
