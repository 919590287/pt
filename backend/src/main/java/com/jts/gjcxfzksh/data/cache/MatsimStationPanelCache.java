package com.jts.gjcxfzksh.data.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Slf4j
public final class MatsimStationPanelCache {

    // v7: 可达性按“物理同点”识别换乘点（同名站按坐标聚类拆分），修复同名异地站点（如“东区市场”相距数十公里）
    //     被当成同一换乘点导致的跨城“假可达”问题，需重算缓存。
    // v8: 客流画像对齐线路面板，按“出行目的/出行者属性”两个维度互斥统计，各维度由前端补足到 100%。
    // v9: 交通方式优先使用 transportMode，避免“地铁站”类站名把公交误判为地铁。
    // v10: 客流画像活动类型优先读取 selected plan，避免把未采用的备选计划算入当前客流。
    // v11: routeId 不再假设全局唯一，站点面板内部线路索引改用 lineId::routeId；读取增加受限内存 LRU。
    // v12: 增加 facility 级小时上下车统计，用于前端区分道路两侧同名站点。
    public static final String STATION_PANEL_CACHE_VERSION = "station-panel-v12";

    // 同名站点按邻近度聚类的半径（投影单位，约 0.92×米；广州为 Web Mercator）。
    // 真实同站台一般 <150m，可合并；同名异地站点相距上千米，会被拆成不同换乘点。
    private static final double STOP_CLUSTER_RADIUS = 300.0;

    private static final String PANEL_FILE = "station-panel.json.gz";
    private static final String MANIFEST_FILE = "manifest.json";
    private static final int HOURS = 24;
    private static final int LEADERBOARD_LIMIT = 50;
    private static final int OD_LIMIT = 12;
    private static final int REACHABILITY_STATION_LIMIT = 80;
    private static final Pattern CHINESE_METRO_LINE_NUMBER_PATTERN = Pattern.compile(
            "(?i)(?:地铁|轨道|线路)?\\s*([0-9]{1,2}|[一二三四五六七八九十]{1,4})\\s*(?:号线|线)"
    );
    private static final Pattern ENGLISH_METRO_LINE_NUMBER_PATTERN = Pattern.compile(
            "(?i)(?:metro|subway|mtr)(?:[-_\\s]*line)?[-_\\s]*([0-9]{1,2})\\b|\\bline[-_\\s]*([0-9]{1,2})\\b"
    );
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Map<String, Map<String, Object>> MEMORY_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                    return size() > 2;
                }
            }
    );

    private MatsimStationPanelCache() {
    }

    public static void prepareOnModelLoad(MatsimData data) {
        ensureStationPanelCache(data);
        loadPanel(data);
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
            return loadPanel(data);
        } catch (Exception e) {
            log.warn("读取站点客流面板缓存失败: model={}, path={}", data.getName(), panelPath(data), e);
            return Map.of();
        }
    }

    private static Map<String, Object> loadPanel(MatsimData data) {
        String cacheKey = panelPath(data).toAbsolutePath().normalize().toString();
        Map<String, Object> cached = MEMORY_CACHE.get(cacheKey);
        if (cached != null) return cached;
        synchronized (MEMORY_CACHE) {
            cached = MEMORY_CACHE.get(cacheKey);
            if (cached != null) return cached;
            try {
                cached = readGzipJson(panelPath(data));
                MEMORY_CACHE.put(cacheKey, cached);
                return cached;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
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
            MEMORY_CACHE.remove(panelPath(data).toAbsolutePath().normalize().toString());
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
        stations.values().forEach(station -> station.finish(data.getPopulation()));

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
            if (facility.getCoord() != null) {
                index.facilityToCoord.put(facilityId, new double[]{facility.getCoord().getX(), facility.getCoord().getY()});
            }
        }

        // 同名站点按邻近度聚类成“物理换乘点”，避免同名异地站点被当成同一换乘点（跨城假可达的根因）。
        index.buildStopNodes(STOP_CLUSTER_RADIUS);

        for (Map.Entry<Id<TransitLine>, TransitLine> lineEntry : data.getSchedule().getTransitLines().entrySet()) {
            String lineId = lineEntry.getKey().toString();
            TransitLine line = lineEntry.getValue();
            String lineName = nonBlank(line.getName(), lineId);
            for (Map.Entry<Id<TransitRoute>, TransitRoute> routeEntry : line.getRoutes().entrySet()) {
                String routeId = routeEntry.getKey().toString();
                TransitRoute route = routeEntry.getValue();
                RouteMeta routeMeta = new RouteMeta(lineId, lineName, routeId, route);
                index.routes.put(routeMeta.key(), routeMeta);
                // 可达性图按“物理换乘点”(stop node)而非站名构建，避免同名异地站点产生假换乘。
                Set<String> routeNodes = new LinkedHashSet<>();
                for (TransitRouteStop stop : route.getStops()) {
                    routeNodes.add(index.stopNode(stop.getStopFacility().getId().toString()));
                }
                index.routeToNodes.put(routeMeta.key(), routeNodes);
                for (String node : routeNodes) {
                    index.nodeToRoutes.computeIfAbsent(node, ignored -> new LinkedHashSet<>()).add(routeMeta.key());
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
                    RouteMeta route = index.routes.get(idString(openBoarding.getRouteId()));
                    stations.computeIfAbsent(origin, StationPanelAccumulator::new).addOd(origin, destination, route, hour);
                    stations.computeIfAbsent(destination, StationPanelAccumulator::new).addOd(origin, destination, route, hour);
                }
                openBoarding = null;
            }
        }
    }

    private static void indexReachability(Map<String, StationPanelAccumulator> stations, StationNetworkIndex index) {
        for (StationPanelAccumulator station : stations.values()) {
            // 起点用本站各物理站台对应的换乘点（按设施坐标聚类得到），而非站名。
            Set<String> originNodes = index.nodesOf(station.facilityIds, station.stationName);
            Set<String> seenRoutes = new LinkedHashSet<>();
            Set<String> seenNodes = new LinkedHashSet<>(originNodes);

            Set<String> directRoutes = routesByNodes(originNodes, index);
            seenRoutes.addAll(directRoutes);
            Set<String> directNodes = newNodesByRoutes(directRoutes, seenNodes, index);
            seenNodes.addAll(directNodes);

            Set<String> transfer1Routes = nextTransferRoutes(directRoutes, seenRoutes, index);
            seenRoutes.addAll(transfer1Routes);
            Set<String> transfer1Nodes = newNodesByRoutes(transfer1Routes, seenNodes, index);
            seenNodes.addAll(transfer1Nodes);

            Set<String> transfer2Routes = nextTransferRoutes(transfer1Routes, seenRoutes, index);
            Set<String> transfer2Nodes = newNodesByRoutes(transfer2Routes, seenNodes, index);

            // 对外仍以站名汇报可达站点（同一物理点可能含多名站台，按名去重）。
            station.setReachability(index.namesOf(directNodes), index.namesOf(transfer1Nodes), index.namesOf(transfer2Nodes));
        }
    }

    private static Set<String> routesByNodes(Set<String> nodes, StationNetworkIndex index) {
        Set<String> result = new LinkedHashSet<>();
        for (String node : nodes) {
            result.addAll(index.nodeToRoutes.getOrDefault(node, Set.of()));
        }
        return result;
    }

    private static Set<String> nextTransferRoutes(Set<String> frontierRoutes, Set<String> seenRoutes, StationNetworkIndex index) {
        Set<String> result = new LinkedHashSet<>();
        for (String node : nodesByRoutes(frontierRoutes, index)) {
            for (String routeId : index.nodeToRoutes.getOrDefault(node, Set.of())) {
                if (!seenRoutes.contains(routeId)) {
                    result.add(routeId);
                }
            }
        }
        return result;
    }

    private static Set<String> newNodesByRoutes(Set<String> routeIds, Set<String> seenNodes, StationNetworkIndex index) {
        Set<String> result = nodesByRoutes(routeIds, index);
        result.removeAll(seenNodes);
        return result;
    }

    private static Set<String> nodesByRoutes(Set<String> routeIds, StationNetworkIndex index) {
        Set<String> result = new LinkedHashSet<>();
        for (String routeId : routeIds) {
            result.addAll(index.routeToNodes.getOrDefault(routeId, Set.of()));
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

    private static String routeKey(String lineId, String routeId) {
        return nonBlank(lineId, "") + "::" + nonBlank(routeId, "");
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

    private static double percent(double numerator, double denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return round2(Math.min(100.0, numerator * 100.0 / denominator));
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
        if (!canonicalMetroLineNumber(lineText).isBlank()
                || containsMetroModeKeyword(lineText)
                || !canonicalMetroLineNumber(routeText).isBlank()
                || containsRouteIdMetroKeyword(routeId)) {
            return "subway";
        }
        return "bus";
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
            String number = chineseLineNumber(matcher.group(1));
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

    private static String firstText(Person person, String... keys) {
        if (person == null || person.getAttributes() == null) {
            return "";
        }
        for (String key : keys) {
            Object value = person.getAttributes().getAttribute(key);
            if (value != null) {
                return value.toString();
            }
        }
        return "";
    }

    private static String allAttributeText(Person person) {
        if (person == null || person.getAttributes() == null || person.getAttributes().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        person.getAttributes().getAsMap().forEach((key, value) -> {
            builder.append(key).append('=');
            if (value != null) {
                builder.append(value);
            }
            builder.append(';');
        });
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private static Set<String> activityTypes(Person person) {
        Set<String> result = new LinkedHashSet<>();
        if (person == null) {
            return result;
        }
        if (person.getSelectedPlan() != null) {
            collectActivityTypes(person.getSelectedPlan().getPlanElements(), result);
            return result;
        }
        person.getPlans().forEach(plan -> collectActivityTypes(plan.getPlanElements(), result));
        return result;
    }

    private static void collectActivityTypes(List<PlanElement> elements, Set<String> result) {
        for (PlanElement element : elements) {
            if (element instanceof Activity activity && activity.getType() != null) {
                result.add(activity.getType().toLowerCase(Locale.ROOT));
            }
        }
    }

    private static Integer age(Person person) {
        String raw = firstText(person, "age", "Age", "AGE", "年龄");
        if (raw.isBlank()) {
            return null;
        }
        try {
            return (int) Math.floor(Double.parseDouble(raw.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean hasToken(String text, String... tokens) {
        for (String token : tokens) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasActivity(Set<String> types, String... tokens) {
        for (String type : types) {
            for (String token : tokens) {
                if (type.contains(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> limitedStationNames(Set<String> stationNames) {
        return sortedStationNames(stationNames).stream()
                .limit(REACHABILITY_STATION_LIMIT)
                .toList();
    }

    private static List<String> sortedStationNames(Set<String> stationNames) {
        return stationNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .sorted(String::compareToIgnoreCase)
                .toList();
    }

    private static final class StationNetworkIndex {
        private final Map<String, String> facilityToName = new HashMap<>();
        private final Map<String, double[]> facilityToCoord = new HashMap<>();
        private final Map<String, RouteMeta> routes = new LinkedHashMap<>();
        // 可达性图以“物理换乘点”(stop node) 为节点；同名站点按坐标聚类后可能拆成多个节点。
        private final Map<String, Set<String>> nodeToRoutes = new HashMap<>();
        private final Map<String, Set<String>> routeToNodes = new HashMap<>();
        private final Map<String, String> facilityToNode = new HashMap<>();
        private final Map<String, String> nodeToName = new HashMap<>();
        private final Map<String, Set<String>> nameToNodes = new HashMap<>();

        private String stationName(String facilityId) {
            if (facilityId == null || facilityId.isBlank()) {
                return "unknown";
            }
            return nonBlank(facilityToName.get(facilityId), facilityId);
        }

        // 同名设施按邻近度单链聚类成换乘点：相距 ≤ radius 视为同一物理点，远离则拆成 name#0 / name#1 …
        private void buildStopNodes(double radius) {
            double r2 = radius * radius;
            Map<String, List<String>> facilitiesByName = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : facilityToName.entrySet()) {
                facilitiesByName.computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>()).add(entry.getKey());
            }
            for (Map.Entry<String, List<String>> entry : facilitiesByName.entrySet()) {
                String name = entry.getKey();
                List<List<String>> clusters = new ArrayList<>();
                for (String facilityId : entry.getValue()) {
                    double[] coord = facilityToCoord.get(facilityId);
                    List<String> hit = null;
                    if (coord != null) {
                        for (List<String> cluster : clusters) {
                            for (String member : cluster) {
                                double[] mc = facilityToCoord.get(member);
                                if (mc == null) {
                                    continue;
                                }
                                double dx = coord[0] - mc[0];
                                double dy = coord[1] - mc[1];
                                if (dx * dx + dy * dy <= r2) {
                                    hit = cluster;
                                    break;
                                }
                            }
                            if (hit != null) {
                                break;
                            }
                        }
                    }
                    if (hit == null) {
                        hit = new ArrayList<>();
                        clusters.add(hit);
                    }
                    hit.add(facilityId);
                }
                boolean split = clusters.size() > 1;
                for (int k = 0; k < clusters.size(); k++) {
                    String nodeId = split ? name + "#" + k : name;
                    nodeToName.put(nodeId, name);
                    nameToNodes.computeIfAbsent(name, ignored -> new LinkedHashSet<>()).add(nodeId);
                    for (String facilityId : clusters.get(k)) {
                        facilityToNode.put(facilityId, nodeId);
                    }
                }
            }
        }

        private String stopNode(String facilityId) {
            String node = facilityToNode.get(facilityId);
            return node != null ? node : stationName(facilityId);
        }

        private Set<String> nodesOf(Set<String> facilityIds, String fallbackName) {
            Set<String> nodes = new LinkedHashSet<>();
            for (String facilityId : facilityIds) {
                String node = facilityToNode.get(facilityId);
                if (node != null) {
                    nodes.add(node);
                }
            }
            if (nodes.isEmpty()) {
                nodes.addAll(nameToNodes.getOrDefault(fallbackName, Set.of()));
            }
            return nodes;
        }

        private Set<String> namesOf(Set<String> nodes) {
            Set<String> names = new LinkedHashSet<>();
            for (String node : nodes) {
                names.add(nonBlank(nodeToName.get(node), node));
            }
            return names;
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
            this.mode = inferTransitMode(lineName, lineId, routeName, routeId, route.getTransportMode());
            for (TransitRouteStop stop : route.getStops()) {
                TransitStopFacility facility = stop.getStopFacility();
                String facilityId = facility.getId().toString();
                stationNames.add(nonBlank(facility.getName(), facilityId));
            }
            this.desc = routeDesc(stationNames);
        }

        private String key() {
            return routeKey(lineId, routeId);
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
        private final Map<String, FacilityPanelAccumulator> facilityPanels = new LinkedHashMap<>();
        private final Map<String, RouteMeta> routes = new LinkedHashMap<>();
        private final Set<String> riderIds = new LinkedHashSet<>();
        private final int[] boardingByHour = new int[HOURS];
        private final int[] alightingByHour = new int[HOURS];
        private final Map<String, OdAccumulator> od = new HashMap<>();
        private Set<String> directReachableStations = Set.of();
        private Set<String> transfer1ReachableStations = Set.of();
        private Set<String> transfer2ReachableStations = Set.of();
        private Map<String, Object> demographics = Map.of(
                "commuter", 0,
                "student", 0,
                "elderly", 0,
                "shopping", 0,
                "leisure", 0,
                "other", 0
        );
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
                facilityPanels.computeIfAbsent(facilityId, ignored -> new FacilityPanelAccumulator(stationName, facilityId));
            }
        }

        private void addRoute(RouteMeta route) {
            routes.putIfAbsent(route.key(), route);
        }

        private void addTrack(PTPersonTrack track) {
            int hour = hourOf(safeTime(track));
            String facilityId = idString(track.getFacilityId());
            FacilityPanelAccumulator facilityPanel = null;
            if (facilityId != null && !facilityId.isBlank()) {
                addFacility(facilityId);
                facilityPanel = facilityPanels.get(facilityId);
            }
            if (Boolean.TRUE.equals(track.getEnter())) {
                boardingByHour[hour]++;
                totalBoardings++;
                if (facilityPanel != null) {
                    facilityPanel.addBoarding(hour);
                }
            } else {
                alightingByHour[hour]++;
                totalAlightings++;
                if (facilityPanel != null) {
                    facilityPanel.addAlighting(hour);
                }
            }
            String personId = idString(track.getPersonId());
            if (personId != null) {
                riderIds.add(personId);
            }
        }

        private void addOd(String origin, String destination, RouteMeta route, int hour) {
            String routeKey = route == null ? "unknown" : route.key();
            String key = routeKey + "::" + origin + "::" + destination;
            od.computeIfAbsent(key, ignored -> new OdAccumulator(origin, destination, route)).flowByHour[hour]++;
        }

        private void setReachability(Set<String> direct, Set<String> transfer1, Set<String> transfer2) {
            this.directReachableStations = new LinkedHashSet<>(direct);
            this.transfer1ReachableStations = new LinkedHashSet<>(transfer1);
            this.transfer2ReachableStations = new LinkedHashSet<>(transfer2);
            this.directReachable = direct.size();
            this.transfer1Reachable = transfer1.size();
            this.transfer2Reachable = transfer2.size();
        }

        private void finish(Population population) {
            buildDemographics(population);
        }

        private void buildDemographics(Population population) {
            if (population == null || riderIds.isEmpty()) {
                demographics = demographicsPayload(0, 0, 0, 0, 0, 0, 0);
                return;
            }
            int total = 0;
            int commuter = 0;
            int student = 0;
            int elderly = 0;
            int shopping = 0;
            int leisure = 0;
            int other = 0;
            Map<String, Integer> activityCounts = new LinkedHashMap<>();
            for (String riderId : riderIds) {
                Person person = population.getPersons().get(Id.create(riderId, Person.class));
                if (person == null) {
                    continue;
                }
                total++;
                Set<String> activities = activityTypes(person);
                for (String activity : activities) {
                    if (activity != null && !activity.isBlank()) {
                        activityCounts.merge(activity, 1, Integer::sum);
                    }
                }
                String attributes = allAttributeText(person);
                Integer personAge = age(person);
                boolean isCommuter = hasActivity(activities, "home") && hasActivity(activities, "work")
                        || hasToken(attributes, "worker", "employee", "employed", "commuter", "通勤", "工作");
                boolean isStudent = hasActivity(activities, "school", "educ", "university", "college", "小学", "中学", "学校", "教育")
                        || hasToken(attributes, "student", "school", "university", "学生");
                boolean isElderly = personAge != null && personAge >= 60
                        || hasToken(attributes, "elderly", "retired", "senior", "老人", "退休");
                boolean isShopping = hasActivity(activities, "shop", "mall", "market", "购物", "买")
                        || hasToken(attributes, "shopping", "购物");
                boolean isLeisure = hasActivity(activities, "leisure", "recreation", "social", "sport", "entertain", "eat", "dining", "休闲", "娱乐", "餐", "运动", "社交")
                        || hasToken(attributes, "leisure", "休闲", "娱乐");
                if (isElderly) {
                    elderly++;
                } else if (isStudent) {
                    student++;
                }
                if (isCommuter) {
                    commuter++;
                } else if (isShopping) {
                    shopping++;
                } else if (isLeisure) {
                    leisure++;
                } else {
                    other++;
                }
            }
            demographics = demographicsPayload(total, commuter, student, elderly, shopping, leisure, other);
            demographics.put("activityTypes", activityPayloads(activityCounts, total));
        }

        private Map<String, Object> demographicsPayload(int total, int commuter, int student, int elderly,
                int shopping, int leisure, int other) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("riderCount", total);
            payload.put("commuter", percent(commuter, total));
            payload.put("student", percent(student, total));
            payload.put("elderly", percent(elderly, total));
            payload.put("shopping", percent(shopping, total));
            payload.put("leisure", percent(leisure, total));
            payload.put("other", percent(other, total));
            payload.put("source", "population-attributes-and-activities");
            return payload;
        }

        private List<Map<String, Object>> activityPayloads(Map<String, Integer> activityCounts, int total) {
            return activityCounts.entrySet().stream()
                    .sorted((left, right) -> {
                        int countCompare = Integer.compare(right.getValue(), left.getValue());
                        return countCompare != 0 ? countCompare : left.getKey().compareToIgnoreCase(right.getKey());
                    })
                    .map(entry -> {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("key", entry.getKey());
                        payload.put("label", entry.getKey());
                        payload.put("count", entry.getValue());
                        payload.put("ratio", percent(entry.getValue(), total));
                        return payload;
                    })
                    .toList();
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
            Map<String, Object> facilityPayloads = new LinkedHashMap<>();
            facilityPanels.values().stream()
                    .sorted(Comparator.comparing(panel -> panel.facilityId, String::compareToIgnoreCase))
                    .forEach(panel -> facilityPayloads.put(panel.facilityId, panel.toPayload()));
            payload.put("facilityPanels", facilityPayloads);
            payload.put("routes", routes.values().stream()
                    .sorted(Comparator.comparing(route -> route.lineName))
                    .map(RouteMeta::toPayload)
                    .toList());
            payload.put("od", odPayloads());

            Map<String, Object> reachability = new LinkedHashMap<>();
            reachability.put("direct", directReachable);
            reachability.put("transfer1", transfer1Reachable);
            reachability.put("transfer2", transfer2Reachable);
            reachability.put("directStations", limitedStationNames(directReachableStations));
            reachability.put("transfer1Stations", limitedStationNames(transfer1ReachableStations));
            reachability.put("transfer2Stations", limitedStationNames(transfer2ReachableStations));
            reachability.put("stationListLimit", REACHABILITY_STATION_LIMIT);
            payload.put("reachability", reachability);
            payload.put("demographics", demographics);

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

    private static final class FacilityPanelAccumulator {
        private final String stationName;
        private final String facilityId;
        private final int[] boardingByHour = new int[HOURS];
        private final int[] alightingByHour = new int[HOURS];
        private long totalBoardings = 0;
        private long totalAlightings = 0;

        private FacilityPanelAccumulator(String stationName, String facilityId) {
            this.stationName = stationName;
            this.facilityId = facilityId;
        }

        private void addBoarding(int hour) {
            boardingByHour[hour]++;
            totalBoardings++;
        }

        private void addAlighting(int hour) {
            alightingByHour[hour]++;
            totalAlightings++;
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

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("stationName", stationName);
            payload.put("facilityId", facilityId);
            payload.put("facilityIds", List.of(facilityId));
            payload.put("hours", List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23));
            payload.put("hourlyFlow", hourlyFlow());
            payload.put("boardingByHour", boardingByHour);
            payload.put("alightingByHour", alightingByHour);

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("passenger", passengerFlow());
            metrics.put("boarding", totalBoardings);
            metrics.put("alighting", totalAlightings);
            metrics.put("peakFlow", peakFlow());
            metrics.put("facilityCount", 1);
            payload.put("metrics", metrics);
            return payload;
        }
    }

    private static final class OdAccumulator {
        private final String origin;
        private final String destination;
        private final String routeId;
        private final String lineName;
        private final String routeName;
        private final String routeDesc;
        private final int[] flowByHour = new int[HOURS];

        private OdAccumulator(String origin, String destination, RouteMeta route) {
            this.origin = origin;
            this.destination = destination;
            this.routeId = route == null ? "" : route.routeId;
            this.lineName = route == null ? "" : route.lineName;
            this.routeName = route == null ? "" : route.routeName;
            this.routeDesc = route == null ? "" : route.desc;
        }

        private int flow() {
            return intSum(flowByHour);
        }

        private Map<String, Object> toPayload(String stationName, int totalFlow) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("origin", origin);
            payload.put("destination", destination);
            payload.put("counterpart", stationName.equals(origin) ? destination : origin);
            payload.put("routeId", routeId);
            payload.put("lineName", lineName);
            payload.put("routeName", routeName);
            payload.put("routeDesc", routeDesc);
            payload.put("direction", routeDesc);
            payload.put("flowByHour", flowByHour);
            payload.put("flow", flow());
            payload.put("ratio", totalFlow == 0 ? 0.0 : round2(flow() * 100.0 / totalFlow));
            return payload;
        }
    }
}
