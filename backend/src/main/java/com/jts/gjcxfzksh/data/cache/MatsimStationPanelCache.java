package com.jts.gjcxfzksh.data.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.pt.routes.TransitPassengerRoute;
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
    // v13: ①客流画像活动口径改为“在该站上车者本次出行的出行目的活动”（selected plan 中 TransitPassengerRoute
    //        的 accessStopId 属于本站 facilityIds，取 leg 之后第一个非 interaction 活动，占比合计≈100%；
    //        找不到时退回全活动统计但仍过滤 interaction）；
    //      ②od 数组上限 12→60，并新增 originX/originY/destinationX/destinationY（经纬度，Web Mercator 反算）。需重算缓存。
    // v14: od 截断口径由“按线路×OD记录取前60条”改为“按对端站点聚合取前60个站点、保留其全部线路明细”，
    //      避免低客流对端站被整站漏掉（前端 OD 图表/表格按对端站聚合展示）。需重算缓存。
    // v15: 统计口径修复批次（需重算缓存）：
    //      ①跨零点时刻（>86400s）折叠回当日小时，不再全部压进 23 时桶；
    //      ②OD 到站客流改按【下车时刻】分桶（原按上车时刻，跨小时行程使到站曲线整体左移）；
    //      ③track 排序补充次键（同秒先下后上、按车辆定序），配对结果可复现；
    //      ④连续两条上车记录（下车事件缺失）导致的 OD 丢段计数并打日志，不再完全静默；
    //      ⑤公交/地铁判定收紧（裸“N线”须带地铁/轨道前缀，接驳/巴士等公交词优先判 bus）；
    //      ⑥上下车归属统一 pt-events-v3 动态映射（TransitDriverStarts + 司机显式过滤）。
    public static final String STATION_PANEL_CACHE_VERSION = "station-panel-v15";

    // 同名站点按邻近度聚类的半径（投影单位，约 0.92×米；广州为 Web Mercator）。
    // 真实同站台一般 <150m，可合并；同名异地站点相距上千米，会被拆成不同换乘点。
    private static final double STOP_CLUSTER_RADIUS = 300.0;

    private static final String PANEL_FILE = "station-panel.json.gz";
    private static final String MANIFEST_FILE = "manifest.json";
    private static final int HOURS = 24;
    private static final int LEADERBOARD_LIMIT = 50;
    // v14: 表示保留的“对端站点”数量上限（而非 OD 记录条数），每个站点的多线路明细全部保留。
    private static final int OD_LIMIT = 60;
    private static final int REACHABILITY_STATION_LIMIT = 80;
    // 项目统一投影为 epsg:3857（见 Datasource.ctf），经纬度输出用 Web Mercator 反算。
    private static final double EARTH_RADIUS = 6378137.0;
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

    /**
     * 单站点明细：对齐 route 侧 routePanelDetail 模式，前端选中站点无需下载全城 stations 整包。
     */
    public static Map<String, Object> readStationPanelDetail(MatsimData data, String stationName) {
        return stationDetailFromPanel(readStationPanel(data), stationName);
    }

    static Map<String, Object> stationDetailFromPanel(Map<String, Object> panel, String stationName) {
        if (stationName == null || stationName.isBlank()) {
            return Map.of();
        }
        Object stationsValue = panel.get("stations");
        if (!(stationsValue instanceof Map<?, ?> stations)) {
            // generating / 读取失败：状态原样透传给前端
            return panel;
        }
        Object station = stations.get(stationName);
        if (station == null) {
            String target = normalizeStationName(stationName);
            for (Map.Entry<?, ?> entry : stations.entrySet()) {
                if (normalizeStationName(String.valueOf(entry.getKey())).equals(target)) {
                    station = entry.getValue();
                    break;
                }
            }
        }
        if (!(station instanceof Map<?, ?> stationMap)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        stationMap.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static String normalizeStationName(String name) {
        return name == null ? "" : name.replaceAll("\\s+", "").toLowerCase();
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

    private static void ensureStationPanelCache(MatsimData data) {
        // per-model 锁：模型 A 构建期间不阻塞模型 B（原为类级 synchronized 全局锁）
        synchronized (ModelBuildLocks.lockFor("station-panel", data)) {
            ensureStationPanelCacheLocked(data);
        }
    }

    private static void ensureStationPanelCacheLocked(MatsimData data) {
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
        // 任务B：按上车站（accessStopId）预统计“本次出行的出行目的活动”，一次遍历 population。
        Map<String, Map<String, Integer>> tripPurposeByAccessStop = buildTripPurposeByAccessStop(data.getPopulation());
        stations.values().forEach(station -> station.finish(data.getPopulation(), tripPurposeByAccessStop));

        // 站名 → 经纬度（同名 facility 坐标取质心后 Web Mercator 反算），供 od 数组输出起讫点坐标。
        Map<String, double[]> stationLonLat = buildStationLonLat(index);
        Map<String, Object> stationPayloads = new LinkedHashMap<>();
        stations.values().stream()
                .sorted(Comparator.comparing(station -> station.stationName, String::compareToIgnoreCase))
                .forEach(station -> stationPayloads.put(station.stationName, station.toPayload(stationLonLat)));

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

        long droppedOpenBoardings = 0;
        for (List<PTPersonTrack> personTracks : byPerson.values()) {
            personTracks.sort(TRACK_TIME_ORDER);
            PTPersonTrack openBoarding = null;
            for (PTPersonTrack track : personTracks) {
                if (Boolean.TRUE.equals(track.getEnter())) {
                    if (openBoarding != null) {
                        // 连续两条上车（下车事件缺失）：前一次乘坐无法闭合，OD 丢一段。
                        // 完全静默会让 Σod.flow 与上车总量的口径差无从解释，至少计数留痕。
                        droppedOpenBoardings++;
                    }
                    openBoarding = track;
                    continue;
                }
                if (openBoarding == null) {
                    continue;
                }
                String origin = index.stationName(idString(openBoarding.getFacilityId()));
                String destination = index.stationName(idString(track.getFacilityId()));
                if (!origin.equals(destination)) {
                    // 出发站按上车时刻分桶，到达站按下车时刻分桶——
                    // 跨小时行程的到站客流原被整体记早一个小时量级。
                    int boardHour = hourOf(safeTime(openBoarding));
                    int alightHour = hourOf(safeTime(track));
                    // v11 起 routes 以 lineId::routeId 为键，必须带 lineId 查找；
                    // 旧实现用裸 routeId 恒查空，导致 od 记录的线路信息全部丢失。
                    RouteMeta route = index.routeFor(idString(openBoarding.getLineId()), idString(openBoarding.getRouteId()));
                    stations.computeIfAbsent(origin, StationPanelAccumulator::new).addOd(origin, destination, route, boardHour);
                    stations.computeIfAbsent(destination, StationPanelAccumulator::new).addOd(origin, destination, route, alightHour);
                }
                openBoarding = null;
            }
        }
        if (droppedOpenBoardings > 0) {
            log.warn("站点客流面板: {} 条上车记录缺失对应下车事件，OD 段被弃计（Σod.flow 会小于上车总量）", droppedOpenBoardings);
        }
    }

    /**
     * 同人 track 的时间排序：同一秒内“先下车后上车”（同站零等待换乘的自然顺序），
     * 最后按车辆 ID 定序——tracks 源是无序 HashSet，无次键时同秒事件顺序不可复现，
     * OD 配对结果会随每次构建漂移。
     */
    private static final Comparator<PTPersonTrack> TRACK_TIME_ORDER =
            Comparator.comparingDouble(MatsimStationPanelCache::safeTime)
                    .thenComparingInt(track -> Boolean.TRUE.equals(track.getEnter()) ? 1 : 0)
                    .thenComparing(track -> String.valueOf(track.getVehicleId()));

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
        // MATSim 时刻可 >86400（跨零点班次），折叠回当日小时；
        // 原 min(23,…) 会把夜间事件全部压进 23 时桶，凌晨客流恒为 0。
        return ((int) Math.floor(Math.max(0, seconds) / 3600.0)) % HOURS;
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
                String type = activity.getType().toLowerCase(Locale.ROOT);
                // 任务B："pt interaction"/"car interaction" 等 interaction 类活动不算活动。
                if (!isInteractionActivity(type)) {
                    result.add(type);
                }
            }
        }
    }

    // "pt interaction" / "car interaction" 等 interaction 类活动不算出行活动。
    private static boolean isInteractionActivity(String lowerCaseType) {
        return lowerCaseType != null && lowerCaseType.contains("interaction");
    }

    /**
     * 任务B：一次遍历 population，统计每个上车站（accessStopId）的“出行目的活动”：
     * selected plan 中 PT leg（TransitPassengerRoute）之后第一个非 interaction 活动的类型，按 leg 计数一次。
     */
    private static Map<String, Map<String, Integer>> buildTripPurposeByAccessStop(Population population) {
        Map<String, Map<String, Integer>> result = new HashMap<>();
        if (population == null) {
            return result;
        }
        for (Person person : population.getPersons().values()) {
            Plan plan = person.getSelectedPlan();
            if (plan == null) {
                continue;
            }
            List<PlanElement> elements = plan.getPlanElements();
            for (int i = 0; i < elements.size(); i++) {
                if (!(elements.get(i) instanceof Leg leg) || !(leg.getRoute() instanceof TransitPassengerRoute ptRoute)) {
                    continue;
                }
                String accessStopId = ptRoute.getAccessStopId() == null ? null : ptRoute.getAccessStopId().toString();
                if (accessStopId == null) {
                    continue;
                }
                String purpose = nextTripPurpose(elements, i);
                if (purpose == null) {
                    continue;
                }
                result.computeIfAbsent(accessStopId, ignored -> new LinkedHashMap<>())
                        .merge(purpose, 1, Integer::sum);
            }
        }
        return result;
    }

    /** leg 之后第一个非 interaction 活动的类型（小写）；找不到返回 null。 */
    private static String nextTripPurpose(List<PlanElement> elements, int legIndex) {
        for (int i = legIndex + 1; i < elements.size(); i++) {
            if (elements.get(i) instanceof Activity activity && activity.getType() != null) {
                String type = activity.getType().toLowerCase(Locale.ROOT);
                if (!isInteractionActivity(type)) {
                    return type;
                }
            }
        }
        return null;
    }

    // 与 BuildingServiceImpl.mercatorToWgs84 同公式：项目统一投影 epsg:3857 → WGS84 经纬度。
    private static double[] mercatorToWgs84(double x, double y) {
        double lon = Math.toDegrees(x / EARTH_RADIUS);
        double lat = Math.toDegrees(2 * Math.atan(Math.exp(y / EARTH_RADIUS)) - Math.PI / 2);
        return new double[]{lon, lat};
    }

    /** 站名 → [lon, lat]：同名 facility 平面坐标取质心后反算经纬度。 */
    private static Map<String, double[]> buildStationLonLat(StationNetworkIndex index) {
        Map<String, double[]> sums = new HashMap<>();
        for (Map.Entry<String, String> entry : index.facilityToName.entrySet()) {
            double[] coord = index.facilityToCoord.get(entry.getKey());
            if (coord == null) {
                continue;
            }
            double[] sum = sums.computeIfAbsent(entry.getValue(), ignored -> new double[3]);
            sum[0] += coord[0];
            sum[1] += coord[1];
            sum[2]++;
        }
        Map<String, double[]> result = new HashMap<>();
        sums.forEach((name, sum) -> result.put(name, mercatorToWgs84(sum[0] / sum[2], sum[1] / sum[2])));
        return result;
    }

    /**
     * 任务B：客流画像（与 MatsimRoutePanelCache 同口径）。出行者属性/出行目的两个维度保持既有互斥单选逻辑；
     * 活动画像改为“在该站上车者本次出行的出行目的活动”计数（占比合计≈100%），
     * 无出行目的活动时退回按乘客全活动统计（仍过滤 interaction）。
     */
    private static Map<String, Object> buildDemographicsPayload(
            Population population,
            Set<String> riderIds,
            Map<String, Integer> tripPurposeCounts
    ) {
        if (population == null || riderIds.isEmpty()) {
            Map<String, Object> empty = demographicsPayload(0, 0, 0, 0, 0, 0, 0);
            putActivityProfile(empty, tripPurposeCounts == null ? Map.of() : tripPurposeCounts, Map.of());
            return empty;
        }
        int total = 0;
        int commuter = 0;
        int student = 0;
        int elderly = 0;
        int shopping = 0;
        int leisure = 0;
        int other = 0;
        Map<String, Integer> fallbackCounts = new LinkedHashMap<>();
        for (String riderId : riderIds) {
            Person person = population.getPersons().get(Id.create(riderId, Person.class));
            if (person == null) {
                continue;
            }
            total++;
            Set<String> activities = activityTypes(person);
            for (String activity : activities) {
                if (activity != null && !activity.isBlank()) {
                    fallbackCounts.merge(activity, 1, Integer::sum);
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
            // 两个维度互斥单选：出行者属性（老人 > 学生），出行目的（通勤 > 购物 > 休闲 > 其他）。
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
        Map<String, Object> payload = demographicsPayload(total, commuter, student, elderly, shopping, leisure, other);
        putActivityProfile(payload, tripPurposeCounts == null ? Map.of() : tripPurposeCounts, fallbackCounts);
        return payload;
    }

    private static void putActivityProfile(
            Map<String, Object> payload,
            Map<String, Integer> tripPurposeCounts,
            Map<String, Integer> fallbackCounts
    ) {
        boolean fallback = tripPurposeCounts.isEmpty();
        Map<String, Integer> activityCounts = fallback ? fallbackCounts : tripPurposeCounts;
        payload.put("activitySource", fallback ? "all-activities-fallback" : "trip-purpose");
        payload.put("activityTypes", activityPayloads(activityCounts));
        payload.put("activityTypeRatios", activityRatioPayload(activityCounts));
    }

    private static Map<String, Object> demographicsPayload(int total, int commuter, int student, int elderly,
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

    // 比例 = 类型计数 / 总计数 × 100（round2，总和≈100）。
    private static List<Map<String, Object>> activityPayloads(Map<String, Integer> activityCounts) {
        int total = activityCounts.values().stream().mapToInt(Integer::intValue).sum();
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

    private static Map<String, Object> activityRatioPayload(Map<String, Integer> activityCounts) {
        int total = activityCounts.values().stream().mapToInt(Integer::intValue).sum();
        Map<String, Object> result = new LinkedHashMap<>();
        activityCounts.entrySet().stream()
                .sorted((left, right) -> {
                    int countCompare = Integer.compare(right.getValue(), left.getValue());
                    return countCompare != 0 ? countCompare : left.getKey().compareToIgnoreCase(right.getKey());
                })
                .forEach(entry -> result.put(entry.getKey(), percent(entry.getValue(), total)));
        return result;
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

        /**
         * 按 lineId+routeId 查线路元数据；lineId 缺失时退化为全表扫 routeId（仅唯一匹配才返回，避免歧义）。
         */
        private RouteMeta routeFor(String lineId, String routeId) {
            if (routeId == null || routeId.isBlank()) {
                return null;
            }
            if (lineId != null && !lineId.isBlank()) {
                RouteMeta meta = routes.get(routeKey(lineId, routeId));
                if (meta != null) {
                    return meta;
                }
            }
            RouteMeta match = null;
            for (RouteMeta meta : routes.values()) {
                if (routeId.equals(meta.routeId)) {
                    if (match != null) {
                        return null;
                    }
                    match = meta;
                }
            }
            return match;
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

        private void finish(Population population, Map<String, Map<String, Integer>> tripPurposeByAccessStop) {
            // 任务B：站点画像=在该站上车的人本次出行的出行目的活动。合并本站各 facility（上车站）上的计数。
            Map<String, Integer> tripPurposeCounts = new LinkedHashMap<>();
            for (String facilityId : facilityIds) {
                Map<String, Integer> byStop = tripPurposeByAccessStop.get(facilityId);
                if (byStop != null) {
                    byStop.forEach((type, count) -> tripPurposeCounts.merge(type, count, Integer::sum));
                }
            }
            demographics = buildDemographicsPayload(population, riderIds, tripPurposeCounts);
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

        private Map<String, Object> toPayload(Map<String, double[]> stationLonLat) {
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
            payload.put("od", odPayloads(stationLonLat));

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

        private List<Map<String, Object>> odPayloads(Map<String, double[]> stationLonLat) {
            int totalFlow = od.values().stream().mapToInt(OdAccumulator::flow).sum();
            // 先按“对端站点”聚合客流，取客流最高的前 OD_LIMIT 个对端站点；
            // 再保留这些站点的全部（按线路）OD 明细，避免整站被“按记录”截断而漏掉（前端按站聚合展示）。
            Map<String, Integer> flowByCounterpart = new HashMap<>();
            for (OdAccumulator item : od.values()) {
                String counterpart = stationName.equals(item.origin) ? item.destination : item.origin;
                flowByCounterpart.merge(counterpart, item.flow(), Integer::sum);
            }
            List<String> ranked = new ArrayList<>(flowByCounterpart.keySet());
            ranked.sort((a, b) -> Integer.compare(flowByCounterpart.get(b), flowByCounterpart.get(a)));
            Set<String> kept = new LinkedHashSet<>(ranked.subList(0, Math.min(OD_LIMIT, ranked.size())));
            return od.values().stream()
                    .filter(item -> kept.contains(stationName.equals(item.origin) ? item.destination : item.origin))
                    .sorted(Comparator.comparingInt(OdAccumulator::flow).reversed())
                    .map(item -> item.toPayload(stationName, totalFlow, stationLonLat))
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

        private Map<String, Object> toPayload(String stationName, int totalFlow, Map<String, double[]> stationLonLat) {
            double[] originLonLat = stationLonLat.get(origin);
            double[] destinationLonLat = stationLonLat.get(destination);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("origin", origin);
            // v13: 起讫点经纬度（Web Mercator 反算，站名下多 facility 取质心）。
            payload.put("originX", originLonLat == null ? null : originLonLat[0]);
            payload.put("originY", originLonLat == null ? null : originLonLat[1]);
            payload.put("destination", destination);
            payload.put("destinationX", destinationLonLat == null ? null : destinationLonLat[0]);
            payload.put("destinationY", destinationLonLat == null ? null : destinationLonLat[1]);
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
