package com.jts.gjcxfzksh.data.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.ModelProcessingPool;
import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import com.jts.gjcxfzksh.utils.DistanceUtil;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Slf4j
public final class MatsimRoutePanelCache {

    // v4: 运营效益指标(班次/车辆/单班次/车日均) + 客流画像新增 购物/休闲/其他 类型，需重算缓存
    // v5: 客流画像按维度互斥单选(出行者属性: 老人/学生; 出行目的: 通勤/购物/休闲/其他)，各维度合计≤100%，需重算缓存
    // v6: routeId 不再假设全局唯一；地铁识别纳入线路名称、中文“地铁/轨道”等上下文，需重算缓存
    // v7: 地铁线路按规范化后的“地铁N号线”聚合（如 3号线 + 3号线北延段），需重算缓存
    // v8: 交通方式优先使用 transportMode，避免“地铁站”类站名把公交误判为地铁；地铁线路号提取改为严格语义匹配。
    // v9: 地铁聚合改为按“规范化线路名”而非裸线路号，避免跨系统同号线被错误合并
    //     （佛山2/3号线≠广州2/3号线、南海/黄埔/海珠有轨电车1号线≠地铁1号线），同时仍合并同线分段（北段/东段/西段/知识城线），需重算缓存
    // v10: 客流画像输出真实 output plans 活动类型，并让地铁聚合线路继承画像统计，需重算缓存
    // v11: 关联换乘线路不再只输出前 12 个，供前端完整展示全部可换乘线路与 0 值补全。
    // v12: ①新增线路站间 OD 字段 stationOd（按人配对“上车→下车”，经纬度坐标，flow 降序，上限 500）；
    //      ②客流画像活动口径改为“本次出行的出行目的活动”（selected plan 中 TransitPassengerRoute 匹配本 route，
    //        取 leg 之后第一个非 interaction 活动，占比合计≈100%；找不到时退回全活动统计但仍过滤 interaction）；
    //      ③换乘识别支持对向/邻近站台（两 facility 坐标相距 ≤200m 也视为同一换乘点）；
    //      ④lineGroups 新增公交聚合（key=bus::lineId，上下行合并，单 route 也生成组），并为所有组补齐
    //        transfers/stationOd 聚合；地铁组既有聚合键与合并行为不变。需重算缓存。
    // v13: 换乘判定收紧为“同站换乘”（同 facility 或同站名，站名天然合并上下行对站）；
    //      移除 v12 的 200m 邻近站台规则——它把步行可达的地铁/公交站都算成了直接换乘。需重算缓存。
    // v14: 统计口径修复批次（需重算缓存）：
    //      ①跨零点时刻（>86400s）折叠回当日小时，不再全部压进 23 时桶；
    //      ②断面客流改为“按乘次配对”口径：每次乘坐按上车时刻计入其途经的全部断面（stop 序号区间），
    //        修复跨小时乘客造成的桶间错位与环线同站双计；
    //      ③满载率/percent 不再封顶 100%，真实超载可见；
    //      ④换乘同名站增加 ≤500m 坐标距离校验，排除同名异地站误判；
    //      ⑤track 排序补充次键（同秒先下后上），配对结果可复现；
    //      ⑥lineGroup 的 lc 输出 null（原 0.0 占位会被当真值展示）、facDist 取代表方向、
    //        首末班仅统计有班次的成员；
    //      ⑦公交/地铁判定收紧（裸“N线”须带地铁/轨道前缀，接驳/巴士等公交词优先）；
    //      ⑧出行目的兜底键仅在 routeId 全局唯一时使用；上下车归属统一 pt-events-v3 动态映射。
    public static final String ROUTE_PANEL_CACHE_VERSION = "route-panel-v14";

    private static final String PANEL_FILE = "route-panel.json.gz";
    private static final String MANIFEST_FILE = "manifest.json";
    private static final int HOURS = 24;
    private static final int TRANSFER_WINDOW_SECONDS = 1800;
    private static final int LEADERBOARD_LIMIT = 50;
    // 任务A：单条 route 的站间 OD 输出上限。
    private static final int STATION_OD_LIMIT = 500;
    // 项目统一投影为 epsg:3857（见 Datasource.ctf），经纬度输出用 Web Mercator 反算。
    private static final double EARTH_RADIUS = 6378137.0;
    // 裸“N线”必须带“地铁/轨道”前缀才算地铁线号，“N号线”单独成立——
    // 否则 B1线/K1线 等公交快线命名会被误判为地铁（与 MatsimAnalysisCache 同步维护）。
    private static final Pattern CHINESE_METRO_LINE_NUMBER_PATTERN = Pattern.compile(
            "(?i)(?:地铁|轨道)\\s*([0-9]{1,2}|[一二三四五六七八九十]{1,4})\\s*(?:号线|线)"
                    + "|([0-9]{1,2}|[一二三四五六七八九十]{1,4})\\s*号线"
    );
    private static final Pattern ENGLISH_METRO_LINE_NUMBER_PATTERN = Pattern.compile(
            "(?i)(?:metro|subway|mtr)(?:[-_\\s]*line)?[-_\\s]*([0-9]{1,2})\\b|\\bline[-_\\s]*([0-9]{1,2})\\b"
    );
    // 同一条地铁线的分段/支线后缀：仅这些应被剥离后合并（如 3号线 + 3号线北段、12号线东段 + 12号线西段、14号线 + 14号线知识城线）。
    // 城市/制式前缀（佛山、南海、黄埔、海珠、有轨电车…）不在此列，确保跨系统同号线不被合并。
    private static final Pattern METRO_SEGMENT_SUFFIX_PATTERN = Pattern.compile(
            "(北延段|南延段|东延段|西延段|北延线|南延线|东延线|西延线|北段|南段|东段|西段|延长线|延长段|知识城支线|知识城线|支线|一期|二期|三期|四期|首期工程|首期|首通段|后通段)"
    );
    // 规范化后恰为“N号线”（阿拉伯或中文数字）才算“纯地铁线路号”，展示为“地铁N号线”。
    private static final Pattern PURE_METRO_LINE_PATTERN = Pattern.compile(
            "^(?:[0-9]{1,2}|[一二三四五六七八九十]{1,4})号线$"
    );
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    // 内存面板缓存条数：默认 4（原为 2，3 个模型轮流访问时每次都重新读 gz 解析数十 MB JSON）。
    // 可用 -Dgjcxfzksh.panel-memory-cache-entries=N 按内存预算调整。
    private static final int MEMORY_CACHE_MAX_ENTRIES =
            Math.max(1, Integer.getInteger("gjcxfzksh.panel-memory-cache-entries", 4));
    private static final Map<String, Map<String, Object>> MEMORY_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                    return size() > MEMORY_CACHE_MAX_ENTRIES;
                }
            }
    );

    private MatsimRoutePanelCache() {
    }

    public static void prepareOnModelLoad(MatsimData data) {
        ensureRoutePanelCache(data);
    }

    /** Load an existing panel into memory while the model starts, without generating an incomplete cache. */
    public static void preloadIfReady(MatsimData data) {
        if (isReady(data)) {
            try {
                loadPanel(data);
            } catch (RuntimeException e) {
                log.warn("预热线路客流面板缓存失败，将在首次请求时重试: model={}", data.getName(), e);
            }
        }
    }

    public static Map<String, Object> readRoutePanel(MatsimData data) {
        if (!isReady(data)) {
            return Map.of(
                    "status", "generating",
                    "cacheVersion", ROUTE_PANEL_CACHE_VERSION,
                    "message", "线路客流缓存正在后台生成"
            );
        }
        try {
            return loadPanel(data);
        } catch (Exception e) {
            log.warn("读取线路客流面板缓存失败: model={}, path={}", data.getName(), panelPath(data), e);
            return Map.of();
        }
    }

    // 与前端 index.vue 的 routeModeKey() 保持一致的地铁判定口径
    private static final Pattern OVERALL_METRO_TEXT_PATTERN =
            Pattern.compile("(metro|subway|rail|地铁|轨道)", Pattern.CASE_INSENSITIVE);

    /**
     * “总体客流变化”卡片只需要 24×2 个数字，服务端直接聚合返回，
     * 避免前端为 48 个数字下载并解析整个 routePanel 大 JSON。
     */
    public static Map<String, Object> readOverallFlow(MatsimData data) {
        return overallFlowFromPanel(readRoutePanel(data));
    }

    static Map<String, Object> overallFlowFromPanel(Map<String, Object> panel) {
        Object routesValue = panel.get("routes");
        if (!(routesValue instanceof Map<?, ?> routes)) {
            // generating / 读取失败：状态原样透传给前端
            return panel;
        }
        double[] bus = new double[HOURS];
        double[] metro = new double[HOURS];
        for (Object value : routes.values()) {
            if (!(value instanceof Map<?, ?> route)) {
                continue;
            }
            Object hourly = route.get("hourlyFlow");
            if (!(hourly instanceof List<?> values)) {
                continue;
            }
            double[] target = isMetroRouteText(route) ? metro : bus;
            int limit = Math.min(HOURS, values.size());
            for (int i = 0; i < limit; i++) {
                if (values.get(i) instanceof Number number) {
                    target[i] += number.doubleValue();
                }
            }
        }
        Map<String, Object> hourlyByMode = new LinkedHashMap<>();
        hourlyByMode.put("bus", toDoubleList(bus));
        hourlyByMode.put("metro", toDoubleList(metro));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ready");
        result.put("cacheVersion", ROUTE_PANEL_CACHE_VERSION);
        result.put("hourlyByMode", hourlyByMode);
        return result;
    }

    private static boolean isMetroRouteText(Map<?, ?> route) {
        // route payload 的 mode 已由 inferTransitMode 按 transportMode 优先算好，直接使用；
        // 原实现对 lineName/routeName 再做文本匹配，"地铁接驳专线"等公交线会被误计入 metro 曲线，
        // 与右侧线路面板的 mode 归类互相矛盾。仅当 mode 缺失（旧版缓存）时才退回文本判定。
        Object mode = route.get("mode");
        if (mode != null && !"null".equals(String.valueOf(mode)) && !String.valueOf(mode).isBlank()) {
            return "subway".equals(String.valueOf(mode));
        }
        String text = route.get("lineName") + " " + route.get("routeName") + " " + route.get("lineId");
        return OVERALL_METRO_TEXT_PATTERN.matcher(text).find();
    }

    private static List<Double> toDoubleList(double[] values) {
        List<Double> result = new ArrayList<>(values.length);
        for (double value : values) {
            result.add(value);
        }
        return result;
    }

    public static Map<String, Object> readRoutePanelDetail(MatsimData data, String lineId, String routeId) {
        if (routeId == null || routeId.isBlank()) return Map.of();
        Map<String, Object> panel = readRoutePanel(data);
        Object routesValue = panel.get("routes");
        if (!(routesValue instanceof Map<?, ?> routes)) return Map.of();
        Object routeValue = null;
        if (lineId != null && !lineId.isBlank()) {
            routeValue = routes.get(routeKey(lineId, routeId));
        }
        if (routeValue == null) {
            routeValue = routes.get(routeId);
            // 裸键命中时校验 lineId：请求带了错误 lineId 时不能把别的线路数据当详情返回
            if (routeValue instanceof Map<?, ?> bare && lineId != null && !lineId.isBlank()
                    && !lineId.equals(String.valueOf(bare.get("lineId")))) {
                routeValue = null;
            }
        }
        if (routeValue == null) {
            routeValue = findRoutePayload(routes, lineId, routeId);
        }
        if (!(routeValue instanceof Map<?, ?> route)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        route.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static Object findRoutePayload(Map<?, ?> routes, String lineId, String routeId) {
        Object match = null;
        for (Object value : routes.values()) {
            if (!(value instanceof Map<?, ?> route)) {
                continue;
            }
            if (!routeId.equals(String.valueOf(route.get("routeId")))) {
                continue;
            }
            if (lineId != null && !lineId.isBlank()
                    && !lineId.equals(String.valueOf(route.get("lineId")))) {
                continue;
            }
            if (match != null && (lineId == null || lineId.isBlank())) {
                return null;
            }
            match = value;
        }
        return match;
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

    private static void ensureRoutePanelCache(MatsimData data) {
        // per-model 锁：模型 A 构建期间不阻塞模型 B（原为类级 synchronized 全局锁）
        synchronized (ModelBuildLocks.lockFor("route-panel", data)) {
            ensureRoutePanelCacheLocked(data);
        }
    }

    private static void ensureRoutePanelCacheLocked(MatsimData data) {
        if (isReady(data)) {
            return;
        }
        try {
            Files.createDirectories(cacheDir(data));
            Map<String, Object> payload = buildPanel(data);
            writeGzipJson(panelPath(data), payload);
            writeJsonAtomic(manifestPath(data), manifest(data, true));
            // 源数据变更触发的重建必须踢掉旧内存条目，否则 loadPanel 继续命中重建前的旧统计
            MEMORY_CACHE.remove(panelPath(data).toAbsolutePath().normalize().toString());
            log.info("线路客流面板缓存生成完成: model={}, routes={}",
                    data.getName(), ((Map<?, ?>) payload.getOrDefault("routes", Map.of())).size());
        } catch (Exception e) {
            try {
                Files.createDirectories(cacheDir(data));
                writeJsonAtomic(manifestPath(data), manifest(data, false));
            } catch (Exception ignored) {
            }
            throw new RuntimeException("线路客流面板缓存生成失败: " + e.getMessage(), e);
        }
    }

    public static boolean isReady(MatsimData data) {
        if (!Files.exists(manifestPath(data)) || !Files.exists(panelPath(data))) {
            return false;
        }
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath(data).toFile(), MAP_TYPE);
            return "ready".equals(manifest.get("status"))
                    && ROUTE_PANEL_CACHE_VERSION.equals(manifest.get("cacheVersion"))
                    && sameSources(data, manifest);
        } catch (Exception e) {
            log.warn("线路客流面板缓存状态读取失败: {}", manifestPath(data), e);
            return false;
        }
    }

    private static Map<String, Object> buildPanel(MatsimData data) {
        Map<String, RoutePanelAccumulator> routes = buildRouteAccumulators(data);
        // 换乘/OD 都是“人×记录”级热路径：facility 坐标与名称统一预建 map，避免逐条回查 schedule。
        Map<String, FacilityGeo> facilityGeo = buildFacilityGeo(data);
        indexPassengerTracks(data.getPersonTracks(), routes);
        indexTransfers(data.getPersonTracks(), routes, facilityGeo);
        indexStationOd(data.getPersonTracks(), routes);
        Population population = data.getPopulation();
        Map<String, Map<String, Integer>> tripPurposeByRoute = buildTripPurposeByRoute(population);

        Map<String, Integer> routeIdCounts = routeIdCounts(routes.values());
        Map<String, Object> routePayloads = new LinkedHashMap<>();
        for (RoutePanelAccumulator route : routes.values()) {
            route.finish(population, tripPurposeByRoute, facilityGeo, routeIdCounts);
            routePayloads.put(route.payloadKey(routeIdCounts), route.toPayload());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ready");
        result.put("cacheVersion", ROUTE_PANEL_CACHE_VERSION);
        result.put("generatedAt", System.currentTimeMillis());
        result.put("summary", buildSummary(routes.values()));
        result.put("routes", routePayloads);
        result.put("lineGroups", buildLineGroups(routes.values(), population, facilityGeo));
        return result;
    }

    /** facilityId → 名称/平面坐标/经纬度（Web Mercator 反算），一次预建供换乘判定与 OD 输出复用。 */
    private static Map<String, FacilityGeo> buildFacilityGeo(MatsimData data) {
        Map<String, FacilityGeo> result = new HashMap<>();
        for (Map.Entry<Id<TransitStopFacility>, TransitStopFacility> entry : data.getSchedule().getFacilities().entrySet()) {
            String facilityId = entry.getKey().toString();
            TransitStopFacility facility = entry.getValue();
            Coord coord = facility.getCoord();
            Double lon = null;
            Double lat = null;
            if (coord != null) {
                double[] lonLat = mercatorToWgs84(coord.getX(), coord.getY());
                lon = lonLat[0];
                lat = lonLat[1];
            }
            result.put(facilityId, new FacilityGeo(nonBlank(facility.getName(), facilityId), coord, lon, lat));
        }
        return result;
    }

    // 与 BuildingServiceImpl.mercatorToWgs84 同公式：项目统一投影 epsg:3857 → WGS84 经纬度。
    private static double[] mercatorToWgs84(double x, double y) {
        double lon = Math.toDegrees(x / EARTH_RADIUS);
        double lat = Math.toDegrees(2 * Math.atan(Math.exp(y / EARTH_RADIUS)) - Math.PI / 2);
        return new double[]{lon, lat};
    }

    /**
     * 任务B：一次遍历 population，统计每条 route 的“出行目的活动”：
     * selected plan 中 PT leg（TransitPassengerRoute）之后第一个非 interaction 活动的类型，按 leg 计数一次。
     */
    private static Map<String, Map<String, Integer>> buildTripPurposeByRoute(Population population) {
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
                String routeId = ptRoute.getRouteId() == null ? null : ptRoute.getRouteId().toString();
                if (routeId == null) {
                    continue;
                }
                String purpose = nextTripPurpose(elements, i);
                if (purpose == null) {
                    continue;
                }
                String lineId = ptRoute.getLineId() == null ? null : ptRoute.getLineId().toString();
                result.computeIfAbsent(routeKey(lineId, routeId), ignored -> new LinkedHashMap<>())
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

    // "pt interaction" / "car interaction" 等 interaction 类活动不算出行活动。
    private static boolean isInteractionActivity(String lowerCaseType) {
        return lowerCaseType != null && lowerCaseType.contains("interaction");
    }

    private static Map<String, RoutePanelAccumulator> buildRouteAccumulators(MatsimData data) {
        Map<String, RoutePanelAccumulator> result = new LinkedHashMap<>();
        Network network = data.getNetwork();
        for (Map.Entry<Id<TransitLine>, TransitLine> lineEntry : data.getSchedule().getTransitLines().entrySet()) {
            String lineId = lineEntry.getKey().toString();
            TransitLine line = lineEntry.getValue();
            String lineName = nonBlank(line.getName(), lineId);
            for (Map.Entry<Id<TransitRoute>, TransitRoute> routeEntry : line.getRoutes().entrySet()) {
                String routeId = routeEntry.getKey().toString();
                TransitRoute route = routeEntry.getValue();
                result.put(routeKey(lineId, routeId), new RoutePanelAccumulator(data, network, lineId, lineName, routeId, route));
            }
        }
        return result;
    }

    private static Map<String, Integer> routeIdCounts(Collection<RoutePanelAccumulator> routes) {
        Map<String, Integer> result = new HashMap<>();
        for (RoutePanelAccumulator route : routes) {
            result.merge(route.routeId, 1, Integer::sum);
        }
        return result;
    }

    private static void indexPassengerTracks(Collection<PTPersonTrack> tracks, Map<String, RoutePanelAccumulator> routes) {
        if (tracks == null || tracks.isEmpty()) {
            return;
        }
        long dropped = 0;
        for (PTPersonTrack track : tracks) {
            RoutePanelAccumulator route = routeForTrack(routes, track);
            if (route != null) {
                route.addTrack(track);
            } else {
                dropped++;
            }
        }
        if (dropped > 0) {
            // 静默丢弃会让总客流无解释地偏低，至少要能在日志里对账
            log.warn("线路客流面板: {} 条上下车记录无法唯一定位到 schedule 线路（lineId/routeId 不匹配或跨线路歧义），已弃计", dropped);
        }
    }

    /**
     * 同人 track 的时间排序：同一秒内“先下车后上车”（换乘的自然顺序），
     * 最后按车辆 ID 定序——tracks 源是无序 HashSet，无次键时同秒事件顺序不可复现，
     * 换乘/OD 配对结果会随每次构建漂移。
     */
    private static final Comparator<PTPersonTrack> TRACK_TIME_ORDER =
            Comparator.comparingDouble(MatsimRoutePanelCache::safeTime)
                    .thenComparingInt(track -> Boolean.TRUE.equals(track.getEnter()) ? 1 : 0)
                    .thenComparing(track -> String.valueOf(track.getVehicleId()));

    private static void indexTransfers(
            Collection<PTPersonTrack> tracks,
            Map<String, RoutePanelAccumulator> routes,
            Map<String, FacilityGeo> facilityGeo
    ) {
        if (tracks == null || tracks.isEmpty()) {
            return;
        }
        Map<String, List<PTPersonTrack>> byPerson = groupTracksByPerson(tracks);

        ModelProcessingPool.forEach(byPerson.values(), personTracks -> {
            personTracks.sort(TRACK_TIME_ORDER);
            for (int i = 0; i + 1 < personTracks.size(); i++) {
                PTPersonTrack leave = personTracks.get(i);
                PTPersonTrack enter = personTracks.get(i + 1);
                if (Boolean.TRUE.equals(leave.getEnter()) || !Boolean.TRUE.equals(enter.getEnter())) {
                    continue;
                }
                double delta = safeTime(enter) - safeTime(leave);
                if (delta < 0 || delta > TRANSFER_WINDOW_SECONDS) {
                    continue;
                }
                RoutePanelAccumulator fromRoute = routeForTrack(routes, leave);
                RoutePanelAccumulator toRoute = routeForTrack(routes, enter);
                if (fromRoute == null || toRoute == null || fromRoute.lineId.equals(toRoute.lineId)) {
                    continue;
                }
                if (!sameTransferStation(fromRoute, leave, toRoute, enter, facilityGeo)) {
                    continue;
                }
                int hour = hourOf(safeTime(enter));
                fromRoute.addTransfer(toRoute, idString(leave.getFacilityId()), hour);
                toRoute.addTransfer(fromRoute, idString(enter.getFacilityId()), hour);
            }
        });
    }

    private static Map<String, List<PTPersonTrack>> groupTracksByPerson(Collection<PTPersonTrack> tracks) {
        Map<String, List<PTPersonTrack>> byPerson = new HashMap<>();
        for (PTPersonTrack track : tracks) {
            String personId = idString(track.getPersonId());
            if (personId != null) {
                byPerson.computeIfAbsent(personId, ignored -> new ArrayList<>()).add(track);
            }
        }
        return byPerson;
    }

    /**
     * 任务A：线路站间 OD。track 按人分组按时间排序，enter=true 记为“开口”，
     * 其后同 route（优先同 departureId）的 enter=false 记录闭合为一次乘坐 fromFacility→toFacility。
     */
    private static void indexStationOd(Collection<PTPersonTrack> tracks, Map<String, RoutePanelAccumulator> routes) {
        if (tracks == null || tracks.isEmpty()) {
            return;
        }
        Map<String, List<PTPersonTrack>> byPerson = groupTracksByPerson(tracks);
        ModelProcessingPool.forEach(byPerson.values(), personTracks -> {
            personTracks.sort(TRACK_TIME_ORDER);
            PTPersonTrack boarding = null;
            for (PTPersonTrack track : personTracks) {
                if (Boolean.TRUE.equals(track.getEnter())) {
                    // 新开口：上一个未闭合的上车记录（缺失下车事件）被覆盖。
                    boarding = track;
                    continue;
                }
                if (boarding == null) {
                    continue;
                }
                if (sameRide(boarding, track)) {
                    RoutePanelAccumulator route = routeForTrack(routes, boarding);
                    if (route != null) {
                        String fromFacilityId = idString(boarding.getFacilityId());
                        String toFacilityId = idString(track.getFacilityId());
                        route.addStationOd(fromFacilityId, toFacilityId);
                        // 断面客流：整次乘坐按上车时刻计入其途经的全部断面
                        route.addRideSegments(fromFacilityId, toFacilityId, hourOf(safeTime(boarding)));
                    }
                }
                boarding = null;
            }
        });
    }

    // 同一次乘坐：同 route（lineId 存在时须一致）；优先同 departureId，departure 缺失时退化为同 route 即配对。
    private static boolean sameRide(PTPersonTrack boarding, PTPersonTrack alighting) {
        String boardRoute = idString(boarding.getRouteId());
        String alightRoute = idString(alighting.getRouteId());
        if (boardRoute == null || alightRoute == null || !boardRoute.equals(alightRoute)) {
            return false;
        }
        String boardLine = idString(boarding.getLineId());
        String alightLine = idString(alighting.getLineId());
        if (boardLine != null && alightLine != null && !boardLine.equals(alightLine)) {
            return false;
        }
        String boardDeparture = idString(boarding.getDepartureId());
        String alightDeparture = idString(alighting.getDepartureId());
        return boardDeparture == null || alightDeparture == null || boardDeparture.equals(alightDeparture);
    }

    private static RoutePanelAccumulator routeForTrack(Map<String, RoutePanelAccumulator> routes, PTPersonTrack track) {
        String routeId = idString(track.getRouteId());
        if (routeId == null || routeId.isBlank()) {
            return null;
        }
        String lineId = idString(track.getLineId());
        if (lineId != null && !lineId.isBlank()) {
            RoutePanelAccumulator route = routes.get(routeKey(lineId, routeId));
            if (route != null) {
                return route;
            }
        }
        RoutePanelAccumulator match = null;
        for (RoutePanelAccumulator route : routes.values()) {
            if (!routeId.equals(route.routeId)) {
                continue;
            }
            // 回退扫描也要求 lineId 一致（track 带 lineId 但复合键未命中说明 schedule 有出入，
            // 不能落到别的线路头上）
            if (lineId != null && !lineId.isBlank() && !lineId.equals(route.lineId)) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = route;
        }
        return match;
    }

    /** 同名站视为同站的坐标距离上限（投影米）：排除跨区同名异地站（“东站”“广场”类重名）。 */
    private static final double SAME_NAME_TRANSFER_MAX_METERS = 500.0;

    private static boolean sameTransferStation(
            RoutePanelAccumulator fromRoute,
            PTPersonTrack leave,
            RoutePanelAccumulator toRoute,
            PTPersonTrack enter,
            Map<String, FacilityGeo> facilityGeo
    ) {
        String leaveFacilityId = idString(leave.getFacilityId());
        String enterFacilityId = idString(enter.getFacilityId());
        if (leaveFacilityId != null && leaveFacilityId.equals(enterFacilityId)) {
            return true;
        }
        // v13：仅“同站换乘”成立——同站名即可（站名合并上下行对站）；
        // 不再按 200m 邻近坐标判定，避免把步行到附近地铁/公交站的行为算成直接换乘。
        // v14：同名之上增加坐标校验——城市里大量跨区重名站，甲地“东站”下车、
        // 乙地“东站”上车不是换乘。坐标缺失时保持 v13 行为。
        String leaveStation = fromRoute.stationName(leaveFacilityId);
        String enterStation = toRoute.stationName(enterFacilityId);
        if (leaveStation.isBlank() || "--".equals(leaveStation) || !leaveStation.equals(enterStation)) {
            return false;
        }
        FacilityGeo from = facilityGeo.get(leaveFacilityId);
        FacilityGeo to = facilityGeo.get(enterFacilityId);
        if (from == null || to == null || from.coord() == null || to.coord() == null) {
            return true;
        }
        double dx = from.coord().getX() - to.coord().getX();
        double dy = from.coord().getY() - to.coord().getY();
        return dx * dx + dy * dy <= SAME_NAME_TRANSFER_MAX_METERS * SAME_NAME_TRANSFER_MAX_METERS;
    }

    private static Map<String, Object> buildSummary(Collection<RoutePanelAccumulator> routes) {
        Map<String, LineLeaderboardAccumulator> lineStats = new LinkedHashMap<>();
        long totalBoardings = 0;
        long totalAlightings = 0;
        for (RoutePanelAccumulator route : routes) {
            totalBoardings += route.totalBoardings;
            totalAlightings += route.totalAlightings;
            String lineKey = lineGroupKey(route);
            String lineName = lineGroupName(route);
            LineLeaderboardAccumulator line = lineStats.computeIfAbsent(lineKey,
                    ignored -> new LineLeaderboardAccumulator(lineKey, lineName, route.mode, route.desc));
            line.add(route);
        }

        Map<String, Object> leaderboard = new LinkedHashMap<>();
        leaderboard.put("bus", leaderboard(lineStats.values(), "bus"));
        leaderboard.put("subway", leaderboard(lineStats.values(), "subway"));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("routeCount", routes.size());
        summary.put("lineCount", lineStats.size());
        summary.put("totalBoardings", totalBoardings);
        summary.put("totalAlightings", totalAlightings);
        summary.put("leaderboard", leaderboard);
        return summary;
    }

    private static Map<String, Object> buildLineGroups(
            Collection<RoutePanelAccumulator> routes,
            Population population,
            Map<String, FacilityGeo> facilityGeo
    ) {
        Map<String, LineGroupAccumulator> groups = new LinkedHashMap<>();
        for (RoutePanelAccumulator route : routes) {
            // 任务D：地铁沿用既有聚合键（metro::规范化线路名，行为不变）；
            // 公交新增按 lineId 聚合上下行（key=bus::lineId），只有一个 route 的线路同样生成组，前端统一走 group 口径。
            boolean subway = "subway".equals(route.mode);
            String key = subway ? lineGroupKey(route) : "bus::" + route.lineId;
            String name = subway ? lineGroupName(route) : route.lineName;
            groups.computeIfAbsent(key, ignored -> new LineGroupAccumulator(key, name, route.mode))
                    .add(route);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        groups.forEach((key, group) -> {
            group.finish(population, facilityGeo);
            payload.put(key, group.toPayload());
        });
        return payload;
    }

    private static List<Map<String, Object>> leaderboard(Collection<LineLeaderboardAccumulator> lines, String mode) {
        return lines.stream()
                .filter(line -> mode.equals(line.mode))
                .sorted(Comparator.comparingLong(LineLeaderboardAccumulator::passengerFlow).reversed())
                .limit(LEADERBOARD_LIMIT)
                .map(LineLeaderboardAccumulator::toPayload)
                .toList();
    }

    private static Map<String, Object> manifest(MatsimData data, boolean ready) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", ready ? "ready" : "failed");
        result.put("cacheVersion", ROUTE_PANEL_CACHE_VERSION);
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
        return MatsimCachePaths.versionDir(data, ROUTE_PANEL_CACHE_VERSION);
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
        // MATSim 时刻可 >86400（如 25:30 发的夜班车），折叠回当日小时；
        // 原 min(23,…) 会把跨零点事件全部压进 23 时桶，凌晨客流恒为 0。
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

    // 地铁线路聚合键：按“规范化线路名”聚合，而非裸线路号。
    // 这样 3号线 + 3号线北段 仍合并，但 佛山3号线、广州3号线、黄埔有轨电车1号线 各自独立，不再因同号被错误合并。
    private static String lineGroupKey(RoutePanelAccumulator route) {
        if (!"subway".equals(route.mode)) {
            return route.lineId;
        }
        return "metro::" + metroLineCanonicalName(route.lineName, route.lineId);
    }

    private static String lineGroupName(RoutePanelAccumulator route) {
        if (!"subway".equals(route.mode)) {
            return route.lineName;
        }
        String canonical = metroLineCanonicalName(route.lineName, route.lineId);
        if (PURE_METRO_LINE_PATTERN.matcher(canonical).matches()) {
            return "地铁" + canonical;
        }
        return nonBlank(canonical, nonBlank(route.lineName, route.lineId));
    }

    // 规范化地铁线路名：去空白、去括号备注、剥离同线分段后缀（北段/东段/西段/知识城线…）。
    // 剥离后若为空则回退原名，避免“知识城线”这类无号线名被清空。
    private static String metroLineCanonicalName(String lineName, String lineId) {
        String base = nonBlank(lineName, nonBlank(lineId, ""))
                .replaceAll("\\s+", "")
                .replaceAll("[（(].*?[）)]", "");
        String stripped = METRO_SEGMENT_SUFFIX_PATTERN.matcher(base).replaceAll("");
        return stripped.isBlank() ? base : stripped;
    }

    private static String canonicalMetroLineNumber(String text) {
        String value = nonBlank(text, "");
        Matcher matcher = CHINESE_METRO_LINE_NUMBER_PATTERN.matcher(value);
        while (matcher.find()) {
            String token = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String number = chineseLineNumber(token);
            if (!number.isBlank()) {
                return number;
            }
        }
        matcher = ENGLISH_METRO_LINE_NUMBER_PATTERN.matcher(value);
        while (matcher.find()) {
            String token = nonBlank(matcher.group(1), matcher.group(2));
            String number = chineseLineNumber(token);
            if (!number.isBlank()) {
                return number;
            }
        }
        return "";
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

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double percent(double numerator, double denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        // 不封顶：满载率 >100% 是真实的超载信号，封顶会把“严重超载”抹成“正好满载”
        return round2(numerator * 100.0 / denominator);
    }

    private static int intSum(int[] values) {
        int result = 0;
        for (int value : values) {
            result += value;
        }
        return result;
    }

    private static int vehicleCapacity(MatsimData data, Id<Vehicle> vehicleId) {
        if (vehicleId == null) {
            return 0;
        }
        Vehicle vehicle = data.getTv().getVehicles().get(vehicleId);
        if (vehicle == null && data.getScenario() != null && data.getScenario().getVehicles() != null) {
            vehicle = data.getScenario().getVehicles().getVehicles().get(vehicleId);
        }
        VehicleType type = vehicle == null ? null : vehicle.getType();
        if (type == null || type.getCapacity() == null) {
            return 0;
        }
        double seats = type.getCapacity().getSeats() == null ? 0.0 : type.getCapacity().getSeats();
        double standingRoom = type.getCapacity().getStandingRoom() == null ? 0.0 : type.getCapacity().getStandingRoom();
        return Math.max(0, (int) Math.round(seats + standingRoom));
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

    /**
     * 任务B：客流画像。出行者属性/出行目的两个维度保持既有互斥单选逻辑；
     * 活动画像改为“本次出行的出行目的活动”计数（占比合计≈100%），
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
            // 客流画像分两个维度，各维度内互斥单选，保证每个维度的占比合计 ≤ 100%（前端用“其他”补足到 100%）。
            // —— 维度一·出行者属性：老人 > 学生 > 成年(其他)。老人/学生互斥，剩余成年人由前端归入“其他”。
            if (isElderly) {
                elderly++;
            } else if (isStudent) {
                student++;
            }
            // —— 维度二·出行目的（取主要目的）：通勤 > 购物 > 休闲 > 其他。四类互斥，合计恰为 100%。
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

    /** 任务A：stationOd payload——flow 降序、只输出 flow>0、上限 {@link #STATION_OD_LIMIT} 条，坐标为经纬度。 */
    private static List<Map<String, Object>> stationOdPayloads(
            Collection<StationOdAccumulator> odFlows,
            Map<String, FacilityGeo> facilityGeo
    ) {
        return odFlows.stream()
                .filter(od -> od.flow > 0)
                .sorted(Comparator.comparingInt((StationOdAccumulator od) -> od.flow).reversed()
                        .thenComparing(od -> od.fromFacilityId)
                        .thenComparing(od -> od.toFacilityId))
                .limit(STATION_OD_LIMIT)
                .map(od -> {
                    FacilityGeo from = facilityGeo.get(od.fromFacilityId);
                    FacilityGeo to = facilityGeo.get(od.toFacilityId);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("fromFacilityId", od.fromFacilityId);
                    payload.put("fromName", from == null ? od.fromFacilityId : from.name());
                    payload.put("fromX", from == null ? null : from.lon());
                    payload.put("fromY", from == null ? null : from.lat());
                    payload.put("toFacilityId", od.toFacilityId);
                    payload.put("toName", to == null ? od.toFacilityId : to.name());
                    payload.put("toX", to == null ? null : to.lon());
                    payload.put("toY", to == null ? null : to.lat());
                    payload.put("flow", od.flow);
                    return payload;
                })
                .toList();
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

    private static final class RoutePanelAccumulator {
        private final String lineId;
        private final String lineName;
        private final String routeId;
        private final String routeName;
        private final String mode;
        private final String desc;
        private final double routeDistance;
        private final double directness;
        private final double firstTime;
        private final double lastTime;
        private final List<StopMeta> stops = new ArrayList<>();
        // facilityId → 该设施在 stops 序列中的全部序号（环线可多次出现）
        private final Map<String, List<Integer>> facilityStopIndices = new HashMap<>();
        private final Map<String, StationFlowAccumulator> stationFlows = new LinkedHashMap<>();
        private final Map<String, TransferAccumulator> transfers = new HashMap<>();
        private final Set<String> riderIds = new LinkedHashSet<>();
        private final int[] hourlyBoardings = new int[HOURS];
        private final int[] hourlyAlightings = new int[HOURS];
        private final int[] capacityByHour = new int[HOURS];
        private final List<SegmentFlowAccumulator> segments = new ArrayList<>();
        // 任务A：本 route 的站间 OD（key=fromFacilityId + '\u0001' + toFacilityId）。
        private final Map<String, StationOdAccumulator> stationOd = new HashMap<>();
        private List<Map<String, Object>> stationOdPayload = List.of();
        // 任务B：本 route 的出行目的活动计数（供 lineGroup 聚合复用）。
        private Map<String, Integer> tripPurposeCounts = Map.of();
        private double capacityTotal = 0.0;
        private long totalBoardings = 0;
        private long totalAlightings = 0;
        private int departureCount = 0;
        private final Set<String> vehicleIds = new LinkedHashSet<>();
        private Map<String, Object> demographics = Map.of("commuter", 0, "student", 0, "elderly", 0);

        private RoutePanelAccumulator(
                MatsimData data,
                Network network,
                String lineId,
                String lineName,
                String routeId,
                TransitRoute route
        ) {
            this.lineId = lineId;
            this.lineName = lineName;
            this.routeId = routeId;
            this.routeName = nonBlank(route.getDescription(), routeId);
            this.mode = inferTransitMode(lineName, lineId, routeName, routeId, route.getTransportMode());
            this.routeDistance = routeDistance(route, network);
            this.directness = directness(route, routeDistance);
            this.firstTime = route.getDepartures().values().stream()
                    .mapToDouble(Departure::getDepartureTime)
                    .min()
                    .orElse(0.0);
            this.lastTime = route.getDepartures().values().stream()
                    .mapToDouble(Departure::getDepartureTime)
                    .max()
                    .orElse(0.0);
            int index = 0;
            for (TransitRouteStop stop : route.getStops()) {
                String facilityId = stop.getStopFacility().getId().toString();
                String facilityName = nonBlank(stop.getStopFacility().getName(), facilityId);
                stops.add(new StopMeta(index++, facilityId, facilityName));
                stationFlows.putIfAbsent(facilityId, new StationFlowAccumulator(facilityId, facilityName));
                // 环线/折返线路同一 facility 可出现多次，记录全部 stop 序号供乘次配对定位
                facilityStopIndices.computeIfAbsent(facilityId, ignored -> new ArrayList<>()).add(stops.size() - 1);
            }
            for (int i = 0; i + 1 < stops.size(); i++) {
                segments.add(new SegmentFlowAccumulator(stops.get(i), stops.get(i + 1)));
            }
            this.desc = routeDesc(stops);
            indexDepartures(data, route.getDepartures().values());
        }

        private void addTrack(PTPersonTrack track) {
            if (track.getEnter() == null) {
                return; // 上/下车标记缺失的坏记录不能默认按下车计，否则下车数虚增、与上车不守恒
            }
            int hour = hourOf(safeTime(track));
            String facilityId = idString(track.getFacilityId());
            StationFlowAccumulator station = stationFlows.computeIfAbsent(
                    nonBlank(facilityId, "unknown"),
                    id -> new StationFlowAccumulator(id, id)
            );
            if (Boolean.TRUE.equals(track.getEnter())) {
                hourlyBoardings[hour]++;
                station.boardingByHour[hour]++;
                totalBoardings++;
                String personId = idString(track.getPersonId());
                if (personId != null) {
                    riderIds.add(personId);
                }
            } else {
                hourlyAlightings[hour]++;
                station.alightingByHour[hour]++;
                totalAlightings++;
            }
        }

        private synchronized void addTransfer(RoutePanelAccumulator otherRoute, String facilityId, int hour) {
            String stationName = stationName(facilityId);
            String key = otherRoute.lineId + "::" + stationName;
            TransferAccumulator transfer = transfers.computeIfAbsent(key,
                    ignored -> new TransferAccumulator(otherRoute.lineId, otherRoute.lineName, otherRoute.routeId, otherRoute.routeName, stationName));
            transfer.flowByHour[hour]++;
        }

        // indexStationOd 按人并行配对，落到同一 route 时需要同步。
        private synchronized void addStationOd(String fromFacilityId, String toFacilityId) {
            if (fromFacilityId == null || toFacilityId == null || fromFacilityId.equals(toFacilityId)) {
                return;
            }
            String key = fromFacilityId + '\u0001' + toFacilityId;
            stationOd.computeIfAbsent(key, ignored -> new StationOdAccumulator(fromFacilityId, toFacilityId)).flow++;
        }

        /**
         * 断面客流按乘次配对累计：一次乘坐（上车站→下车站）在其途经的每个断面 +1，
         * 全程记入【上车时刻】所在小时桶。
         * 相比原“逐小时对上/下车增量求前缀和”的算法，修复了两类失真：
         * ①跨小时乘客的 +1/-1 落在不同小时桶，下游断面在上车小时被永久虚增；
         * ②环线同一 facility 出现两次时按 facilityId 查询计数被双计。
         * stop 序号取“上车站的首个序号”与“其后最近的下车站序号”，环线语义正确。
         */
        private synchronized void addRideSegments(String fromFacilityId, String toFacilityId, int hour) {
            if (segments.isEmpty() || fromFacilityId == null || toFacilityId == null) {
                return;
            }
            List<Integer> fromIndices = facilityStopIndices.get(fromFacilityId);
            List<Integer> toIndices = facilityStopIndices.get(toFacilityId);
            if (fromIndices == null || toIndices == null) {
                return; // events 与 schedule 不配套时无法定位，弃计该乘次
            }
            int fromIndex = fromIndices.get(0);
            int toIndex = -1;
            for (int candidate : toIndices) {
                if (candidate > fromIndex) {
                    toIndex = candidate;
                    break;
                }
            }
            if (toIndex < 0) {
                return; // 下车站不在上车站之后（数据异常），弃计
            }
            for (int i = fromIndex; i < toIndex && i < segments.size(); i++) {
                segments.get(i).flowByHour[hour]++;
            }
        }

        private void finish(
                Population population,
                Map<String, Map<String, Integer>> tripPurposeByRoute,
                Map<String, FacilityGeo> facilityGeo,
                Map<String, Integer> routeIdCounts
        ) {
            buildSegments();
            this.tripPurposeCounts = resolveTripPurposeCounts(tripPurposeByRoute, routeIdCounts);
            this.demographics = buildDemographicsPayload(population, riderIds, tripPurposeCounts);
            this.stationOdPayload = stationOdPayloads(stationOd.values(), facilityGeo);
        }

        private Map<String, Integer> resolveTripPurposeCounts(
                Map<String, Map<String, Integer>> tripPurposeByRoute,
                Map<String, Integer> routeIdCounts
        ) {
            Map<String, Integer> counts = tripPurposeByRoute.get(routeKey(lineId, routeId));
            if (counts == null && routeIdCounts.getOrDefault(routeId, 0) <= 1) {
                // plans 中 TransitPassengerRoute 缺失 lineId 时的兜底键——
                // 仅当 routeId 全局唯一才可用，否则同名 route 会各自领走同一份计数、组聚合后成倍虚增。
                counts = tripPurposeByRoute.get(routeKey(null, routeId));
            }
            return counts == null ? Map.of() : counts;
        }

        private void buildSegments() {
            // flowByHour 已在 addRideSegments 按乘次累计完成，这里补算满载率与全天合计
            for (SegmentFlowAccumulator segment : segments) {
                for (int hour = 0; hour < HOURS; hour++) {
                    segment.loadRateByHour[hour] = percent(segment.flowByHour[hour], capacityByHour[hour]);
                }
                segment.finish(capacityTotal);
            }
        }

        private String payloadKey(Map<String, Integer> routeIdCounts) {
            return routeIdCounts.getOrDefault(routeId, 0) > 1
                    ? routeKey(lineId, routeId)
                    : routeId;
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("routeKey", routeKey(lineId, routeId));
            payload.put("lineId", lineId);
            payload.put("lineName", lineName);
            payload.put("routeId", routeId);
            payload.put("routeName", routeName);
            payload.put("mode", mode);
            payload.put("desc", desc);
            payload.put("hours", Stream.iterate(0, i -> i + 1).limit(HOURS).toList());
            payload.put("hourlyFlow", hourlyBoardings);
            payload.put("boardingByHour", hourlyBoardings);
            payload.put("alightingByHour", hourlyAlightings);
            payload.put("capacityByHour", capacityByHour);
            payload.put("stationFlows", stationFlows.values().stream().map(StationFlowAccumulator::toPayload).toList());
            payload.put("segments", segments.stream().map(SegmentFlowAccumulator::toPayload).toList());
            payload.put("transfers", transferPayloads());
            payload.put("demographics", demographics);
            payload.put("stationOd", stationOdPayload);

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("routeDist", round2(routeDistance));
            metrics.put("firstTime", firstTime);
            metrics.put("lastTime", lastTime);
            metrics.put("facNum", stops.size());
            metrics.put("facDist", stops.size() > 1 ? round2(routeDistance / (stops.size() - 1)) : 0.0);
            metrics.put("lc", round2(directness));
            metrics.put("passenger", totalBoardings);
            metrics.put("loadRate", percent(totalBoardings, capacityTotal));
            metrics.put("passengerStrength", routeDistance <= 0 ? 0.0 : round2(totalBoardings / (routeDistance / 1000.0)));
            int vehicles = vehicleIds.size();
            metrics.put("departures", departureCount);
            metrics.put("vehicles", vehicles);
            metrics.put("perTripFlow", departureCount > 0 ? round2(totalBoardings / (double) departureCount) : 0.0);
            metrics.put("perVehicleFlow", vehicles > 0 ? round2(totalBoardings / (double) vehicles) : 0.0);
            payload.put("metrics", metrics);
            return payload;
        }

        private List<Map<String, Object>> transferPayloads() {
            int totalTransfer = transfers.values().stream().mapToInt(TransferAccumulator::flow).sum();
            return transfers.values().stream()
                    .sorted(Comparator.comparingInt(TransferAccumulator::flow).reversed())
                    .map(item -> item.toPayload(totalTransfer))
                    .toList();
        }

        private void indexDepartures(MatsimData data, Collection<Departure> departures) {
            for (Departure departure : departures) {
                departureCount++;
                if (departure.getVehicleId() != null) {
                    vehicleIds.add(departure.getVehicleId().toString());
                }
                int capacity = vehicleCapacity(data, departure.getVehicleId());
                capacityTotal += capacity;
                capacityByHour[hourOf(departure.getDepartureTime())] += capacity;
            }
        }

        private String stationName(String facilityId) {
            if (facilityId == null || facilityId.isBlank()) {
                return "--";
            }
            StationFlowAccumulator station = stationFlows.get(facilityId);
            return station == null ? facilityId : station.facilityName;
        }

        private static double routeDistance(TransitRoute route, Network network) {
            try {
                return DistanceUtil.distance(route.getRoute(), network);
            } catch (Exception e) {
                return 0.0;
            }
        }

        private static double directness(TransitRoute route, double routeDistance) {
            if (route.getStops().size() < 2) {
                return 0.0;
            }
            Coord first = route.getStops().getFirst().getStopFacility().getCoord();
            Coord last = route.getStops().getLast().getStopFacility().getCoord();
            if (first == null || last == null) {
                return 0.0;
            }
            double dx = first.getX() - last.getX();
            double dy = first.getY() - last.getY();
            double straight = Math.sqrt(dx * dx + dy * dy);
            return straight <= 0 ? 0.0 : routeDistance / straight;
        }

        private static String routeDesc(List<StopMeta> stops) {
            if (stops.size() < 2) {
                return "";
            }
            return stops.getFirst().facilityName + " - " + stops.getLast().facilityName;
        }
    }

    private record StopMeta(int index, String facilityId, String facilityName) {
    }

    private static final class LineGroupAccumulator {
        private final String lineId;
        private final String lineName;
        private final String mode;
        private final Set<String> sourceLineIds = new LinkedHashSet<>();
        private final Set<String> routeIds = new LinkedHashSet<>();
        private final List<String> routeKeys = new ArrayList<>();
        private final Set<String> facilityIds = new LinkedHashSet<>();
        private final Map<String, StationFlowAccumulator> stationFlows = new LinkedHashMap<>();
        private final Map<String, Double> routeDistanceByLine = new LinkedHashMap<>();
        private final int[] hourlyBoardings = new int[HOURS];
        private final int[] hourlyAlightings = new int[HOURS];
        private final int[] capacityByHour = new int[HOURS];
        private final List<Map<String, Object>> segments = new ArrayList<>();
        private final Set<String> vehicleIds = new LinkedHashSet<>();
        private final Set<String> riderIds = new LinkedHashSet<>();
        // 任务A/D：组内合并各子 route 的换乘、站间 OD 与出行目的活动计数。
        private final Map<String, TransferAccumulator> transfers = new LinkedHashMap<>();
        private final Map<String, StationOdAccumulator> stationOd = new LinkedHashMap<>();
        private final Map<String, Integer> tripPurposeCounts = new LinkedHashMap<>();
        private List<Map<String, Object>> stationOdPayload = List.of();
        private Map<String, Object> demographics = Map.of("riderCount", 0);
        private long totalBoardings = 0;
        private long totalAlightings = 0;
        private double capacityTotal = 0.0;
        private int departureCount = 0;
        private double firstTime = Double.MAX_VALUE;
        private double lastTime = 0.0;
        // 代表方向（组内最长的单向 route），平均站距按该方向计算——
        // 上下行 facility 并集会把对向站台算成两站，站距被低估约一半
        private double repDistance = 0.0;
        private int repStopCount = 0;

        private LineGroupAccumulator(String lineId, String lineName, String mode) {
            this.lineId = lineId;
            this.lineName = lineName;
            this.mode = mode;
        }

        private void add(RoutePanelAccumulator route) {
            sourceLineIds.add(route.lineId);
            routeIds.add(route.routeId);
            routeKeys.add(routeKey(route.lineId, route.routeId));
            totalBoardings += route.totalBoardings;
            totalAlightings += route.totalAlightings;
            capacityTotal += route.capacityTotal;
            departureCount += route.departureCount;
            if (route.departureCount > 0) {
                // 无班次的 route 首末班是 0.0 占位，不能参与 min/max（否则首班恒 00:00）
                firstTime = Math.min(firstTime, route.firstTime);
                lastTime = Math.max(lastTime, route.lastTime);
            }
            if (route.routeDistance > repDistance) {
                repDistance = route.routeDistance;
                repStopCount = route.stops.size();
            }
            vehicleIds.addAll(route.vehicleIds);
            riderIds.addAll(route.riderIds);
            routeDistanceByLine.merge(route.lineId, route.routeDistance, Math::max);
            for (StopMeta stop : route.stops) {
                facilityIds.add(stop.facilityId);
            }
            addIntArray(hourlyBoardings, route.hourlyBoardings);
            addIntArray(hourlyAlightings, route.hourlyAlightings);
            addIntArray(capacityByHour, route.capacityByHour);
            for (StationFlowAccumulator source : route.stationFlows.values()) {
                StationFlowAccumulator target = stationFlows.computeIfAbsent(source.facilityId,
                        ignored -> new StationFlowAccumulator(source.facilityId, source.facilityName));
                addIntArray(target.boardingByHour, source.boardingByHour);
                addIntArray(target.alightingByHour, source.alightingByHour);
            }
            for (SegmentFlowAccumulator segment : route.segments) {
                Map<String, Object> payload = segment.toPayload();
                payload.put("routeKey", routeKey(route.lineId, route.routeId));
                payload.put("lineId", route.lineId);
                payload.put("routeId", route.routeId);
                payload.put("routeName", route.routeName);
                payload.put("lineName", route.lineName);
                segments.add(payload);
            }
            // 换乘聚合：同 key（对方 lineId::站名）小时流量相加。
            for (Map.Entry<String, TransferAccumulator> entry : route.transfers.entrySet()) {
                TransferAccumulator source = entry.getValue();
                TransferAccumulator target = transfers.computeIfAbsent(entry.getKey(),
                        ignored -> new TransferAccumulator(source.lineId, source.lineName, source.routeId, source.routeName, source.station));
                addIntArray(target.flowByHour, source.flowByHour);
            }
            // 站间 OD 聚合：同站对流量相加。
            for (Map.Entry<String, StationOdAccumulator> entry : route.stationOd.entrySet()) {
                StationOdAccumulator source = entry.getValue();
                stationOd.computeIfAbsent(entry.getKey(),
                        ignored -> new StationOdAccumulator(source.fromFacilityId, source.toFacilityId)).flow += source.flow;
            }
            // 出行目的活动计数聚合（route.finish 已先于 buildLineGroups 执行）。
            route.tripPurposeCounts.forEach((type, count) -> tripPurposeCounts.merge(type, count, Integer::sum));
        }

        private void finish(Population population, Map<String, FacilityGeo> facilityGeo) {
            demographics = buildDemographicsPayload(population, riderIds, tripPurposeCounts);
            stationOdPayload = stationOdPayloads(stationOd.values(), facilityGeo);
        }

        private static void addIntArray(int[] target, int[] source) {
            for (int i = 0; i < Math.min(target.length, source.length); i++) {
                target[i] += source[i];
            }
        }

        private Map<String, Object> toPayload() {
            double routeDistance = routeDistanceByLine.values().stream().mapToDouble(Double::doubleValue).sum();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("lineGroup", true);
            payload.put("routeKey", lineId);
            payload.put("lineId", lineId);
            payload.put("lineName", lineName);
            payload.put("routeId", lineId);
            payload.put("routeName", lineName);
            payload.put("mode", mode);
            payload.put("sourceLineIds", sourceLineIds);
            payload.put("routeIds", routeIds);
            payload.put("routeKeys", routeKeys);
            payload.put("desc", sourceLineIds.size() > 1 ? String.join(" / ", sourceLineIds) : "");
            payload.put("hours", Stream.iterate(0, i -> i + 1).limit(HOURS).toList());
            payload.put("hourlyFlow", hourlyBoardings);
            payload.put("boardingByHour", hourlyBoardings);
            payload.put("alightingByHour", hourlyAlightings);
            payload.put("capacityByHour", capacityByHour);
            payload.put("stationFlows", stationFlows.values().stream().map(StationFlowAccumulator::toPayload).toList());
            payload.put("segments", segments);
            payload.put("transfers", transferPayloads());
            payload.put("demographics", demographics);
            payload.put("stationOd", stationOdPayload);

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("routeDist", round2(routeDistance));
            metrics.put("firstTime", firstTime == Double.MAX_VALUE ? 0.0 : firstTime);
            metrics.put("lastTime", lastTime);
            metrics.put("facNum", facilityIds.size());
            // 平均站距按代表方向（组内最长单向）计算，站间区间数 = 站数-1
            metrics.put("facDist", repStopCount > 1 ? round2(repDistance / (repStopCount - 1)) : 0.0);
            // 组级非直线系数无统一定义，输出 null 由前端显示“暂无数据”；原 0.0 占位会被当真值
            metrics.put("lc", null);
            metrics.put("passenger", totalBoardings);
            metrics.put("loadRate", percent(totalBoardings, capacityTotal));
            metrics.put("passengerStrength", routeDistance <= 0 ? 0.0 : round2(totalBoardings / (routeDistance / 1000.0)));
            int vehicles = vehicleIds.size();
            metrics.put("departures", departureCount);
            metrics.put("vehicles", vehicles);
            metrics.put("perTripFlow", departureCount > 0 ? round2(totalBoardings / (double) departureCount) : 0.0);
            metrics.put("perVehicleFlow", vehicles > 0 ? round2(totalBoardings / (double) vehicles) : 0.0);
            payload.put("metrics", metrics);
            return payload;
        }

        // 组视角的换乘：上下行/分段合并后，去往组内成员线路的记录属于组内乘续，不再算跨线换乘。
        private List<Map<String, Object>> transferPayloads() {
            List<TransferAccumulator> external = transfers.values().stream()
                    .filter(transfer -> !sourceLineIds.contains(transfer.lineId))
                    .toList();
            int totalTransfer = external.stream().mapToInt(TransferAccumulator::flow).sum();
            return external.stream()
                    .sorted(Comparator.comparingInt(TransferAccumulator::flow).reversed())
                    .map(item -> item.toPayload(totalTransfer))
                    .toList();
        }
    }

    /** facility 静态地理信息：名称、平面坐标（epsg:3857，供换乘距离判定）、经纬度（供 OD 输出）。 */
    private record FacilityGeo(String name, Coord coord, Double lon, Double lat) {
    }

    /** 任务A：一个“上车站→下车站”对的全天客流。 */
    private static final class StationOdAccumulator {
        private final String fromFacilityId;
        private final String toFacilityId;
        private int flow = 0;

        private StationOdAccumulator(String fromFacilityId, String toFacilityId) {
            this.fromFacilityId = fromFacilityId;
            this.toFacilityId = toFacilityId;
        }
    }

    private static final class StationFlowAccumulator {
        private final String facilityId;
        private final String facilityName;
        private final int[] boardingByHour = new int[HOURS];
        private final int[] alightingByHour = new int[HOURS];

        private StationFlowAccumulator(String facilityId, String facilityName) {
            this.facilityId = facilityId;
            this.facilityName = facilityName;
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("facilityId", facilityId);
            payload.put("facilityName", facilityName);
            payload.put("boardingByHour", boardingByHour);
            payload.put("alightingByHour", alightingByHour);
            payload.put("totalBoarding", intSum(boardingByHour));
            payload.put("totalAlighting", intSum(alightingByHour));
            return payload;
        }
    }

    private static final class SegmentFlowAccumulator {
        private final String name;
        private final String fromFacilityId;
        private final String toFacilityId;
        private final int[] flowByHour = new int[HOURS];
        private final double[] loadRateByHour = new double[HOURS];
        private int totalFlow = 0;
        private double loadRate = 0.0;

        private SegmentFlowAccumulator(StopMeta from, StopMeta to) {
            this.fromFacilityId = from.facilityId();
            this.toFacilityId = to.facilityId();
            this.name = from.facilityName() + " - " + to.facilityName();
        }

        private void finish(double capacityTotal) {
            totalFlow = intSum(flowByHour);
            loadRate = percent(totalFlow, capacityTotal);
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("name", name);
            payload.put("fromFacilityId", fromFacilityId);
            payload.put("toFacilityId", toFacilityId);
            payload.put("flowByHour", flowByHour);
            payload.put("loadRateByHour", loadRateByHour);
            payload.put("totalFlow", totalFlow);
            payload.put("loadRate", loadRate);
            return payload;
        }
    }

    private static final class TransferAccumulator {
        private final String lineId;
        private final String lineName;
        private final String routeId;
        private final String routeName;
        private final String station;
        private final int[] flowByHour = new int[HOURS];

        private TransferAccumulator(String lineId, String lineName, String routeId, String routeName, String station) {
            this.lineId = lineId;
            this.lineName = lineName;
            this.routeId = routeId;
            this.routeName = routeName;
            this.station = station;
        }

        private int flow() {
            return intSum(flowByHour);
        }

        private Map<String, Object> toPayload(int totalTransfer) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("lineId", lineId);
            payload.put("lineName", lineName);
            payload.put("routeId", routeId);
            payload.put("routeName", routeName);
            payload.put("station", station);
            payload.put("flowByHour", flowByHour);
            payload.put("flow", flow());
            payload.put("ratio", totalTransfer == 0 ? 0.0 : round2(flow() * 100.0 / totalTransfer));
            return payload;
        }
    }

    private static final class LineLeaderboardAccumulator {
        private final String lineId;
        private final String lineName;
        private String mode;
        private String desc;
        private long passengerFlow = 0;

        private LineLeaderboardAccumulator(String lineId, String lineName, String mode, String desc) {
            this.lineId = lineId;
            this.lineName = lineName;
            this.mode = mode;
            this.desc = desc;
        }

        private void add(RoutePanelAccumulator route) {
            passengerFlow += route.totalBoardings;
            if (desc == null || desc.isBlank()) {
                desc = route.desc;
            }
            if ("subway".equals(route.mode)) {
                mode = "subway";
            }
        }

        private long passengerFlow() {
            return passengerFlow;
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("lineId", lineId);
            payload.put("lineName", lineName);
            payload.put("desc", desc);
            payload.put("mode", mode);
            payload.put("passengerFlow", passengerFlow);
            return payload;
        }
    }
}
