package com.jts.gjcxfzksh.api.service.impl;

import cn.hutool.core.util.NumberUtil;
import com.jts.gjcxfzksh.api.common.Constant;
import com.jts.gjcxfzksh.api.common.DatasourceService;
import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.model.pt.PTCoord;
import com.jts.gjcxfzksh.api.service.PTDataService;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.cache.MatsimAnalysisCache;
import com.jts.gjcxfzksh.data.cache.MatsimPrecomputedCache;
import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import com.jts.gjcxfzksh.data.id.RouteId;
import com.jts.gjcxfzksh.data.id.VehicleId;
import com.jts.gjcxfzksh.utils.DistanceUtil;
import com.jts.gjcxfzksh.utils.TransitMetrics;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.facilities.Facility;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PTDataServiceImpl extends DatasourceService implements PTDataService {

    private static final String TRAJECTORY_CACHE_VERSION = MatsimAnalysisCache.TRAJECTORY_CACHE_VERSION;

    final BigDecimal _100 = new BigDecimal("100");
    private final ConcurrentMap<String, TrajectoryBuildState> trajectoryStates = new ConcurrentHashMap<>();

    /**
     * 轨迹缓存构建并发数：默认 1（保持磁盘 I/O 友好），多模型服务器可调大避免构建串行排队。
     */
    @org.springframework.beans.factory.annotation.Value("${matsim.trajectory-build-threads:1}")
    private int trajectoryBuildThreads;

    private ExecutorService trajectoryExecutor;

    @jakarta.annotation.PostConstruct
    void initTrajectoryExecutor() {
        java.util.concurrent.atomic.AtomicInteger index = new java.util.concurrent.atomic.AtomicInteger();
        trajectoryExecutor = Executors.newFixedThreadPool(Math.max(1, trajectoryBuildThreads), r -> {
            Thread thread = new Thread(r, "trajectory-cache-builder-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        // 模型缓存预热完成后立刻在后台算好体检评估指标，前端进页面即可直接命中缓存
        com.jts.gjcxfzksh.data.Datasource.registerCacheWarmupHook(this::prepareEvaluationOnModelLoad);
    }

    /**
     * 缓存预热阶段预计算体检评估指标（与 evaluation() 共用缓存键）。
     * personTracks 为空说明客流数据尚未就绪，跳过以免把 0 值记忆化。
     */
    void prepareEvaluationOnModelLoad(MatsimData data) {
        if (data == null || data.getPersonTracks() == null || data.getPersonTracks().isEmpty()) {
            return;
        }
        String cacheKey = evaluationCacheKey(data);
        long start = System.currentTimeMillis();
        evictStaleEvaluationEntries(data.getName(), cacheKey);
        evaluationCache.computeIfAbsent(cacheKey, ignored -> buildEvaluation(data));
        log.info("体检评估指标预热完成: model={}, 耗时={}ms", data.getName(), System.currentTimeMillis() - start);
    }

    private String evaluationCacheKey(MatsimData data) {
        return data.getName() + "#v" + com.jts.gjcxfzksh.data.Datasource.currentLoadVersion(data.getName());
    }

    /** 同一模型重载后版本号递增，旧版本条目不再命中，及时逐出避免缓存无限增长。 */
    private void evictStaleEvaluationEntries(String modelName, String currentKey) {
        String prefix = modelName + "#v";
        evaluationCache.keySet().removeIf(key -> key.startsWith(prefix) && !key.equals(currentKey));
    }

    @Override
    public Map<String, Object> info(DatasourceParam param) {
        MatsimData matsim_data = matsim_data(param);
        Map<String, Object> cached = MatsimPrecomputedCache.readInfo(matsim_data);
        if (cached != null) {
            return cached;
        }
        if (matsim_data.isLargeModel()) {
            return Map.of(
                    "status", "generating",
                    "message", "大模型总览缓存正在后台生成，暂不在请求线程中扫描 plans/events",
                    "largeModel", true
            );
        }
        Map<String, Object> result = new HashMap<>();
        // 总体水平
        // 常驻人口密度   人*km2（口径修正：常住人口取全体 agent 数，原实现只数了公交乘客；
        // 面积在 desc.json 未提供时用站点凸包估算，原实现退化为除以 1）
        Set<Coord> coords = schedule(param).getFacilities().values().stream().map(Facility::getCoord).collect(Collectors.toSet());
        Double areaKm2 = effectiveAreaKm2(matsim_data, coords);
        int personCount = matsim_data.getPopulation() == null ? 0 : matsim_data.getPopulation().getPersons().size();
        result.put("czrkmd", areaKm2 == null ? null : (int) Math.round(personCount / areaKm2));
        // 公交线网密度 km/km2
        double length = ptNetworkLength(matsim_data.getSchedule(), matsim_data.getNetwork());
        result.put("gjxwmd", areaKm2 == null ? null
                : NumberUtil.round((length / 1000) / areaKm2, 2).doubleValue());
        // 车站300m覆盖率    %
        result.put("fgl_300", TransitMetrics.coverageResult(
                TransitMetrics.coverage300Percent(coords, matsim_data.getPopulation())));
        // 万人保有量    标台/万人 = 高峰同时在营车辆数(车队规模估算) / (常住人口/10000)
        long fleetSize = TransitMetrics.peakConcurrentVehicles(matsim_data.getSchedule());
        result.put("wrbyl", personCount == 0 || fleetSize == 0 ? null
                : NumberUtil.round(fleetSize / (personCount / 10000.0), 2).doubleValue());
        // 出行分担率    % // pt出行方式占比
        Map<String, Double> fxfdl = legTypeRant(matsim_data.getPopulation());
        result.put("fxfdl", fxfdl);
        // 车均日载客量   人次/d = 日客运总量(上车) / 保有量(车队峰值估算)
        long boardings = matsim_data.getPersonTracks().stream()
                .filter(PTPersonTrack::getEnter).count();
        result.put("cjrzkl", fleetSize == 0 ? 0
                : NumberUtil.round((double) boardings / fleetSize, 2).doubleValue());
        // 单班次载客量   人次/班 = 日客运总量(上车) / 日发班次总数
        long departureTotal = countDepartures(matsim_data.getSchedule());
        result.put("dbczkl", departureTotal == 0 ? null
                : NumberUtil.round((double) boardings / departureTotal, 2).doubleValue());
        // 需求强度
        // 公交日出行次数    次*人
        long rcxcs = boardings;
        result.put("rcxcs", rcxcs);
        // 依赖客流比例：原实现为硬编码 50 的占位值，已移除；业务确认口径后再实现
        // 线路效益
        // 线路非直线系数
        double xlfzxxs = routeNoLC(matsim_data);
        result.put("xlfzxxs", xlfzxxs);
        // 线路重复系数
        double xlcfxs = routeRC(matsim_data); // 线路长度 / 非重复路段长度
        result.put("xlcfxs", xlcfxs);
        // 线路满载率    %（统一口径层：上车人次/静态容量，此处转百分数供展示）
        Map<VehicleId, List<PTPersonTrack>> tracksByVehicle = matsim_data.getPersonTracks().stream()
                .collect(Collectors.groupingBy(PTPersonTrack::getVehicleId));
        double xlmzl = NumberUtil.round(
                TransitMetrics.fullLoadRate(tracksByVehicle, matsim_data.getTv().getVehicles()) * 100.0, 2).doubleValue();
        result.put("xlmzl", xlmzl);
        // 线路客流强度   人次*km
        Map<String, Double> xlklqd = routePersonStrength(matsim_data);
        result.put("xlklqd", xlklqd.entrySet().stream()
                .limit(5)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        v -> NumberUtil.round(v.getValue(), 2).doubleValue(),
                        (e1, e2) -> NumberUtil.round(e1, 2).doubleValue(),
                        LinkedHashMap::new
                )));
        result.put("xlklqd_sum", NumberUtil.round(
                xlklqd.values().stream().mapToDouble(value -> value).sum(), 2).doubleValue());
        // 车公里运营成本  元*乘客*km
        // 车单位人次运营成本    元*人次
        // 运营服务
        // 公共汽电车与小汽车运行速度比
        Map<String, Double> yxsdb = runSpeed(matsim_data.getPopulation());
        result.put("yxsdb", yxsdb);
        // 平均候车时间   min
        result.put("pjhcsj", roundHourly(TransitMetrics.avgAwaitTimeByHour(matsim_data.getPopulation())));
        // 场站设施
        // 车均场站面积   m2*标台
        return result;
    }

    @Override
    public PTCoord center(DatasourceParam param) {
        MatsimData matsim_data = matsim_data(param);
        return new PTCoord(matsim_data.getCenter());
    }

    private final ConcurrentMap<String, Map<String, Object>> evaluationCache = new ConcurrentHashMap<>();

    /**
     * 体检评估指标（全市口径）。key 与前端 evaluationStandards.js 的 modelKey 对齐；
     * 无法统计的指标返回 null（前端显示"暂无数据"）。按模型+加载版本记忆化。
     */
    @Override
    public Map<String, Object> evaluation(DatasourceParam param) {
        MatsimData data = matsim_data(param);
        // 预热阶段（缓存构建钩子）已算好则直接命中，大模型也一样
        String cacheKey = evaluationCacheKey(data);
        Map<String, Object> cached = evaluationCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        if (data.isLargeModel()) {
            return Map.of(
                    "status", "generating",
                    "message", "体检指标正在随模型缓存后台生成"
            );
        }
        // 模型加载中（events/personTracks 尚未解析完）时不计算也不缓存，
        // 否则客流类指标会以 0 值被记忆化直到下次重载
        if (data.getPersonTracks() == null || data.getPersonTracks().isEmpty()) {
            return Map.of(
                    "status", "generating",
                    "message", "模型客流数据仍在加载，请稍后重试"
            );
        }
        evictStaleEvaluationEntries(data.getName(), cacheKey);
        return evaluationCache.computeIfAbsent(cacheKey, ignored -> buildEvaluation(data));
    }

    private Map<String, Object> buildEvaluation(MatsimData data) {
        Map<String, Object> values = new LinkedHashMap<>();
        // 常住人口取全体 agent 数（而非仅公交乘客数），符合"常住人口密度"定义
        int populationCount = data.getPopulation() == null ? 0 : data.getPopulation().getPersons().size();
        long boardings = data.getPersonTracks().stream().filter(PTPersonTrack::getEnter).count();
        Set<Coord> stopCoords = data.getSchedule().getFacilities().values().stream()
                .map(Facility::getCoord).collect(Collectors.toSet());
        // desc.json 未提供面积（占位 1.0）时用站点凸包估算，否则密度类指标失真几个数量级
        Double areaKm2 = effectiveAreaKm2(data, stopCoords);

        // ===== 总体水平 =====
        double netKm = ptNetworkLength(data.getSchedule(), data.getNetwork()) / 1000.0;
        values.put("czrkmd", areaKm2 == null ? null : (int) Math.round(populationCount / areaKm2));
        values.put("gjxwmd", areaKm2 == null ? null : round2(netKm / areaKm2));
        Double coverage = TransitMetrics.coverage300Percent(stopCoords, data.getPopulation());
        values.put("fgl300", coverage == null ? null : round2(coverage));
        // 标台数用"高峰同时在营车辆数"估算（GTFS 转换模型每班次一辆车，直接数车辆会放大一个数量级）
        long fleet = TransitMetrics.peakConcurrentVehicles(data.getSchedule());
        values.put("wrbyl", populationCount == 0 || fleet == 0 ? null : round2(fleet / (populationCount / 10000.0)));
        // ===== 总量（优化评估双模型对比：客流量 / 配车数 / 线网运营规模；均不依赖 plans，稳健）=====
        values.put("khl", boardings);                                    // 客流量：日客运总量（上车人次）
        values.put("pcs", fleet == 0 ? null : fleet);                    // 配车数：高峰同时在营车辆（标台）
        values.put("yylc", round2(netKm));                               // 线网运营里程（km）
        values.put("xlls", data.getSchedule().getTransitLines().size()); // 线路条数
        // 大模型不加载 plans，population 为 null 时基于出行计划的指标输出 null（前端显示"暂无数据"）
        Map<String, Double> legShare = data.getPopulation() == null ? Map.of() : legTypeRant(data.getPopulation());
        values.put("cxfdl", legShare.get(Constant.ROUTE_MODE_PT));
        Map<VehicleId, List<PTPersonTrack>> tracksByVehicle = data.getPersonTracks().stream()
                .collect(Collectors.groupingBy(PTPersonTrack::getVehicleId));
        // 平均日载客量 = 日客运总量 / 保有量（分母同上用车队峰值估算）
        values.put("cjrzkl", fleet == 0 ? null : round2((double) boardings / fleet));
        long departureCount = countDepartures(data.getSchedule());
        values.put("dbczkl", departureCount == 0 ? null : round2((double) boardings / departureCount));

        // ===== 需求强度 =====
        values.put("rcxcs", populationCount == 0 ? null
                : NumberUtil.round((double) boardings / populationCount, 3).doubleValue());

        // ===== 线路效益（线路级指标取全线路平均/全网口径）=====
        values.put("xlfzxxs", routeNoLC(data));
        values.put("xlcfxs", routeRC(data));
        values.put("xlmzl", round2(TransitMetrics.fullLoadRate(tracksByVehicle, data.getTv().getVehicles()) * 100.0));
        double totalRouteKm = totalRouteLengthKm(data);
        values.put("xlklqd", totalRouteKm <= 0 ? null : round2(boardings / totalRouteKm));

        // ===== 运营服务 =====
        Map<String, Double> speeds = data.getPopulation() == null ? Map.of() : runSpeed(data.getPopulation());
        Double ptAvg = speeds.get("ptAvg");
        Double carAvg = speeds.get("carAvg");
        values.put("yxsdb", ptAvg == null || carAvg == null || carAvg <= 0 ? null : round2(ptAvg / carAvg));
        Double awaitMinutes = TransitMetrics.averageAwaitMinutes(data.getPopulation());
        values.put("pjhcsj", awaitMinutes == null ? null : round2(awaitMinutes));
        Map<Object, String> routeModes = routeModeIndex(data.getSchedule());
        TransitMetrics.TransferStats transferStats = TransitMetrics.transferStats(
                data.getPersonTracks(), routeModes::get, 1800);
        Double avgTransfers = transferStats.averageTransfers();
        values.put("pjhccs", avgTransfers == null ? null : round2(avgTransfers));
        Double busRail = transferStats.busRailRatioPercent();
        values.put("gjjbbl", busRail == null ? null : round2(busRail));

        // 场站设施（车均场站面积）：模型无场站数据，暂无法统计

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ready");
        result.put("values", values);
        return result;
    }

    /**
     * 密度类指标的有效面积（km²）：desc.json 显式提供(>1)时用配置值，否则用站点凸包估算。
     */
    static Double effectiveAreaKm2(MatsimData data, Set<Coord> stopCoords) {
        double configured = data.getArea();
        if (configured > 1.0) {
            return configured;
        }
        return TransitMetrics.serviceAreaKm2(stopCoords);
    }

    private static long countDepartures(TransitSchedule schedule) {
        long count = 0;
        for (TransitLine line : schedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                count += route.getDepartures().size();
            }
        }
        return count;
    }

    private double totalRouteLengthKm(MatsimData data) {
        double meters = 0;
        for (TransitLine line : data.getSchedule().getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                meters += DistanceUtil.distance(route.getRoute(), data.getNetwork());
            }
        }
        return meters / 1000.0;
    }

    /** routeId → 规范化交通方式（subway/bus），供换乘链的公交-轨道接驳判定 */
    private static Map<Object, String> routeModeIndex(TransitSchedule schedule) {
        Map<Object, String> result = new HashMap<>();
        for (TransitLine line : schedule.getTransitLines().values()) {
            for (Map.Entry<Id<TransitRoute>, TransitRoute> entry : line.getRoutes().entrySet()) {
                String transportMode = entry.getValue().getTransportMode();
                String normalized = transportMode != null && transportMode.toLowerCase()
                        .matches(".*(subway|metro|rail|train|轨道|地铁).*") ? "subway" : "bus";
                result.put(RouteId.create(entry.getKey()), normalized);
            }
        }
        return result;
    }

    private static Double round2(double value) {
        return NumberUtil.round(value, 2).doubleValue();
    }

    @Override
    public Map<String, Object> trajectory(DatasourceParam param) {
        MatsimData data = matsim_data(param);
        Map<String, Object> manifest = MatsimAnalysisCache.readReadyTrajectoryLightManifest(data);
        if (manifest != null) {
            return manifest;
        }
        if (data.isLargeModel()) {
            return Map.of(
                    "status", "generating",
                    "cacheVersion", TRAJECTORY_CACHE_VERSION,
                    "message", "大模型轨迹缓存正在后台流式生成",
                    "summary", Map.of(
                            "totalVehicles", 0,
                            "vehicleCountByMode", emptyLongModeMap(),
                            "pointCount", 0,
                            "chunks", List.of()
                    ),
                    "timeRange", Map.of("min", 0, "max", 86400),
                    "vehicles", List.of(),
                    "passengerSeries", List.of()
            );
        }

        String cacheKey = MatsimAnalysisCache.trajectoryCacheKey(data);
        TrajectoryBuildState state = trajectoryStates.computeIfAbsent(cacheKey, key -> new TrajectoryBuildState(data));
        if (state.isFailed()) {
            trajectoryStates.remove(cacheKey, state);
            state = trajectoryStates.computeIfAbsent(cacheKey, key -> new TrajectoryBuildState(data));
        }
        if (state.start()) {
            TrajectoryBuildState buildState = state;
            trajectoryExecutor.submit(() -> {
                try {
                    Map<String, Object> readyManifest = MatsimAnalysisCache.ensureTrajectoryCache(data, buildState::markPoint);
                    buildState.ready(MatsimAnalysisCache.lightweightTrajectoryManifest(readyManifest));
                    trajectoryStates.remove(cacheKey, buildState);
                } catch (Throwable e) {
                    buildState.fail(e);
                    log.error("轨迹缓存生成失败: {}", e.getMessage(), e);
                }
            });
        }
        return state.toPayload();
    }

    @Override
    public Map<String, Object> trajectoryChunk(DatasourceParam param, int start) {
        MatsimData data = matsim_data(param);
        Map<String, Object> chunk = MatsimAnalysisCache.readTrajectoryChunk(data, start);
        if (chunk == null) {
            Map<String, Object> status = trajectory(param);
            status.put("vehicles", List.of());
            status.put("chunk", MatsimAnalysisCache.chunkInfo(MatsimAnalysisCache.normalizeChunkStart(start), 0, 0));
            return status;
        }
        return chunk;
    }

    @Override
    public byte[] trajectoryChunkBinary(DatasourceParam param, int start) {
        MatsimData data = matsim_data(param);
        byte[] chunk = MatsimAnalysisCache.readTrajectoryBinaryChunk(data, start);
        if (chunk == null) {
            trajectory(param);
        }
        return chunk;
    }

    @Override
    public Path trajectoryChunkBinaryPath(DatasourceParam param, int start) {
        MatsimData data = matsim_data(param);
        return MatsimAnalysisCache.trajectoryBinaryChunkPath(data, start);
    }

    @Override
    public String trajectoryChunkTag(DatasourceParam param, int start) {
        // 仅基于 events 身份与分块起点生成强校验 ETag，不读分块文件（廉价）；
        // events 变化→cacheKey 变化→ETag 变化→浏览器缓存自动失效。
        MatsimData data = matsim_data(param);
        return MatsimAnalysisCache.trajectoryChunkETag(data, start);
    }

    private static Map<String, Long> emptyLongModeMap() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("bus", 0L);
        result.put("subway", 0L);
        result.put("car", 0L);
        return result;
    }

    private static class TrajectoryBuildState {
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final String modelName;
        private final String eventsFile;
        private final long eventsModified;
        private final long eventsSize;
        private volatile String status = "generating";
        private volatile String message = "轨迹缓存生成中";
        private volatile long startedAt = System.currentTimeMillis();
        private volatile long parsedPoints = 0;
        private volatile int vehicleCount = 0;
        private volatile int minTime = Integer.MAX_VALUE;
        private volatile int maxTime = Integer.MIN_VALUE;
        private volatile Map<String, Object> readyManifest;

        private TrajectoryBuildState(MatsimData data) {
            this.modelName = data.getName();
            this.eventsFile = data.getOutfile().getEvents();
            this.eventsModified = lastModifiedStatic(eventsFile);
            this.eventsSize = fileSizeStatic(eventsFile);
        }

        private boolean start() {
            return started.compareAndSet(false, true);
        }

        private boolean isFailed() {
            return "failed".equals(status);
        }

        private void markPoint(int time, int currentVehicleCount) {
            parsedPoints++;
            vehicleCount = currentVehicleCount;
            minTime = Math.min(minTime, time);
            maxTime = Math.max(maxTime, time);
        }

        private void ready(Map<String, Object> manifest) {
            readyManifest = manifest;
            status = "ready";
            message = "轨迹缓存已生成";
        }

        private void fail(Throwable e) {
            status = "failed";
            message = e.getMessage() == null ? "轨迹缓存生成失败" : e.getMessage();
        }

        private Map<String, Object> toPayload() {
            if (readyManifest != null) {
                return readyManifest;
            }
            Map<String, Object> progress = new LinkedHashMap<>();
            progress.put("pointCount", parsedPoints);
            progress.put("vehicleCount", vehicleCount);
            progress.put("elapsedMs", System.currentTimeMillis() - startedAt);
            progress.put("minTime", minTime == Integer.MAX_VALUE ? 0 : minTime);
            progress.put("maxTime", maxTime == Integer.MIN_VALUE ? 0 : maxTime);

            Map<String, Object> timeRange = new LinkedHashMap<>();
            timeRange.put("min", minTime == Integer.MAX_VALUE ? 0 : minTime);
            timeRange.put("max", maxTime == Integer.MIN_VALUE ? 86400 : maxTime);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", status);
            result.put("cacheVersion", TRAJECTORY_CACHE_VERSION);
            result.put("message", message);
            result.put("model", modelName);
            result.put("eventsFile", eventsFile);
            result.put("eventsModified", eventsModified);
            result.put("eventsSize", eventsSize);
            result.put("progress", progress);
            result.put("timeRange", timeRange);
            result.put("summary", Map.of(
                    "totalVehicles", vehicleCount,
                    "vehicleCountByMode", emptyLongModeMap(),
                    "pointCount", parsedPoints,
                    "chunks", List.of()
            ));
            result.put("passengerSeries", List.of());
            result.put("vehicles", List.of());
            return result;
        }

        private static long lastModifiedStatic(String filePath) {
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

        private static long fileSizeStatic(String filePath) {
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
    }

    public double ptNetworkLength(TransitSchedule schedule, Network network) {
        double length = 0;
        Set<Id<Link>> links = new HashSet<>();
        schedule.getTransitLines().forEach((transitLineId, transitLine) -> {
            transitLine.getRoutes().forEach((transitRouteId, transitRoute) -> {
                NetworkRoute route = transitRoute.getRoute();
                links.add(route.getStartLinkId());
                links.addAll(route.getLinkIds());
                links.add(route.getEndLinkId());
            });
        });
        for (Id<Link> linkId : links) {
            length += network.getLinks().get(linkId).getLength();
        }
        return length;
    }

    public Map<String, Double> runSpeed(Population population) {
        double ptTime = 0., ptDist = 0.;
        double carTime = 0., carDist = 0.;
        Map<Id<Person>, ? extends Person> persons = population.getPersons();
        for (Map.Entry<Id<Person>, ? extends Person> entry : persons.entrySet()) {
            Person person = entry.getValue();
            List<PlanElement> elements = person.getSelectedPlan().getPlanElements();
            for (PlanElement element : elements) {
                if (element instanceof Leg leg) {
                    // Route.getDistance() 可能为 NaN（距离未定义），累加前必须过滤，
                    // 否则整个均值被污染为 NaN，NumberUtil.round(NaN) 直接抛异常导致接口 500。
                    if (Constant.ROUTE_MODE_PT.equals(leg.getMode())) {
                        if (leg.getTravelTime().isDefined() && leg.getRoute() != null
                                && !Double.isNaN(leg.getRoute().getDistance())) {
                            ptTime += leg.getTravelTime().seconds();
                            ptDist += leg.getRoute().getDistance();
                        }
                    } else if (Constant.ROUTE_MODE_CAR.equals(leg.getMode())) {
                        if (leg.getTravelTime().isDefined() && leg.getRoute() != null
                                && !Double.isNaN(leg.getRoute().getDistance())) {
                            carTime += leg.getTravelTime().seconds();
                            carDist += leg.getRoute().getDistance();
                        }
                    }
                }
            }
        }
        double ptAvg = ptTime == 0 ? 0 : ptDist / ptTime * 3.6; // m/s -> km/h
        double carAvg = carTime == 0 ? 0 : carDist / carTime * 3.6;
        Map<String, Double> result = new HashMap<>();
        result.put("ptAvg", NumberUtil.round(Double.isNaN(ptAvg) ? 0 : ptAvg, 2).doubleValue());
        result.put("carAvg", NumberUtil.round(Double.isNaN(carAvg) ? 0 : carAvg, 2).doubleValue());
        return result; // 公交平均速度/小汽车平均速度
    }

    public double routeRC(MatsimData matsim_data) {
        // 线路总长度
        double length = 0.;
        // 非重复路段
        Set<Id<Link>> links = new HashSet<>();
        Map<Id<TransitLine>, TransitLine> transitLines = matsim_data.getSchedule().getTransitLines();
        for (Map.Entry<Id<TransitLine>, TransitLine> line : transitLines.entrySet()) {
            TransitLine transitLine = line.getValue();
            Map<Id<TransitRoute>, TransitRoute> transitRoutes = transitLine.getRoutes();
            for (Map.Entry<Id<TransitRoute>, TransitRoute> route : transitRoutes.entrySet()) {
                NetworkRoute networkRoute = route.getValue().getRoute();
                // 距离
                double distance = DistanceUtil.distance(networkRoute, matsim_data.getNetwork());
                length += distance;
                links.add(networkRoute.getStartLinkId());
                links.addAll(networkRoute.getLinkIds());
                links.add(networkRoute.getEndLinkId());
            }
        }

        // 非重复路段长度
        double rc = 0.;
        Map<Id<Link>, ? extends Link> linkMap = matsim_data.getNetwork().getLinks();
        for (Id<Link> linkId : links) {
            Link link = linkMap.get(linkId);
            if (link != null) {
                rc += NetworkUtils.getEuclideanDistance(link.getFromNode().getCoord(), link.getToNode().getCoord());
            }
        }

        return rc == 0 ? 0.0 : NumberUtil.round(length / rc, 2).doubleValue();
    }

    // 线路非直线系数
    public double routeNoLC(MatsimData matsim_data) {
        int routeCount = 0;
        double lc = 0.;
        Map<Id<TransitLine>, TransitLine> transitLines = matsim_data.getSchedule().getTransitLines();
        for (Map.Entry<Id<TransitLine>, TransitLine> line : transitLines.entrySet()) {
            TransitLine transitLine = line.getValue();
            Map<Id<TransitRoute>, TransitRoute> transitRoutes = transitLine.getRoutes();
            for (Map.Entry<Id<TransitRoute>, TransitRoute> route : transitRoutes.entrySet()) {
                NetworkRoute networkRoute = route.getValue().getRoute();
                // 距离
                double distance = DistanceUtil.distance(networkRoute, matsim_data.getNetwork());
                if (route.getValue().getStops().isEmpty()) {
                    continue;
                }
                TransitRouteStop first = route.getValue().getStops().getFirst();
                TransitRouteStop last = route.getValue().getStops().getLast();
                // 直线距离
                double lcDistance = NetworkUtils.getEuclideanDistance(first.getStopFacility().getCoord(), last.getStopFacility().getCoord());
                if (lcDistance > 0) { // 环线距离 == 0, 不计算
                    lc += (distance / lcDistance);
                    routeCount++;
                }
            }
        }
        return routeCount == 0 ? 0.0 : NumberUtil.round(lc / routeCount, 2).doubleValue(); // 平均值
    }

    /**
     * 线路客流强度（人次/km，按客流降序）。
     * TransitRoute ID 在 MATSim 中只在所属 TransitLine 内唯一，跨线路可重复，
     * 因此上车记录按 lineId+routeId 复合键分组；输出键在 routeId 全局唯一时用裸
     * routeId，重复时用 "lineId::routeId" 消歧，避免同键互相覆盖。
     * 返回值不做四舍五入，由调用方在展示层统一 round，保证排序基于原始值。
     */
    private Map<String, Double> routePersonStrength(MatsimData matsim_data) {
        Map<String, Long> boardingsByLineRoute = new HashMap<>();
        for (PTPersonTrack track : matsim_data.getPersonTracks()) {
            if (!Boolean.TRUE.equals(track.getEnter()) || track.getRouteId() == null) {
                continue;
            }
            String key = track.getLineId() + "::" + track.getRouteId();
            boardingsByLineRoute.merge(key, 1L, Long::sum);
        }

        Map<String, Integer> routeIdCounts = new HashMap<>();
        Map<Id<TransitLine>, TransitLine> transitLines = matsim_data.getSchedule().getTransitLines();
        for (TransitLine transitLine : transitLines.values()) {
            for (Id<TransitRoute> routeId : transitLine.getRoutes().keySet()) {
                routeIdCounts.merge(routeId.toString(), 1, Integer::sum);
            }
        }

        Map<String, Double> result = new HashMap<>();
        for (Map.Entry<Id<TransitLine>, TransitLine> line : transitLines.entrySet()) {
            TransitLine transitLine = line.getValue();
            for (Map.Entry<Id<TransitRoute>, TransitRoute> route : transitLine.getRoutes().entrySet()) {
                TransitRoute transitRoute = route.getValue();
                NetworkRoute networkRoute = transitRoute.getRoute();
                double distance = DistanceUtil.distance(networkRoute, matsim_data.getNetwork());
                String routeId = route.getKey().toString();
                String lineRouteKey = line.getKey() + "::" + routeId;
                double p = boardingsByLineRoute.getOrDefault(lineRouteKey, 0L);
                String outputKey = routeIdCounts.getOrDefault(routeId, 0) > 1 ? lineRouteKey : routeId;
                result.put(outputKey, distance == 0 ? 0.0 : p / (distance / 1000));
            }
        }
        return result.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));
    }

    private static double[] roundHourly(double[] values) {
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = NumberUtil.round(values[i], 2).doubleValue();
        }
        return result;
    }

    private Map<String, Integer> legType(Population population) {
        Map<String, Integer> types = new HashMap<>();
        population.getPersons().values().forEach(person -> {
            person.getSelectedPlan().getPlanElements().forEach(element -> {
                if (element instanceof Leg leg) {
                    types.merge(leg.getMode(), 1, Integer::sum);
                }
            });
        });
        return types;
    }

    private Map<String, Double> legTypeRant(Population population) {
        Map<String, Integer> types = legType(population);
        int count = 0;
        for (Map.Entry<String, Integer> entry : types.entrySet()) {
            count += entry.getValue();
        }
        Map<String, Double> typesRant = new LinkedHashMap<>();
        if (count == 0) {
            return typesRant;
        }
        BigDecimal c = BigDecimal.valueOf(count);
        for (Map.Entry<String, Integer> entry : types.entrySet()) {
            BigDecimal b = new BigDecimal(entry.getValue());
            // 比例保留 4 位小数再转百分数 → 百分比精确到 0.01%；
            // 原实现只保留 2 位（1% 步进），0.4% 的分担率会被抹成 0%。
            BigDecimal v = b.divide(c, 4, RoundingMode.HALF_UP);
            double d = v.multiply(_100).doubleValue(); // %
            typesRant.put(entry.getKey(), d);
        }
        return typesRant;
    }

}
