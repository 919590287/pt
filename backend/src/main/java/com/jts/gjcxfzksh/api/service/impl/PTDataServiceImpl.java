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
import com.jts.gjcxfzksh.data.cache.MatsimSourceFingerprint;
import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import com.jts.gjcxfzksh.data.id.RouteId;
import com.jts.gjcxfzksh.data.id.VehicleId;
import com.jts.gjcxfzksh.exception.BusinessException;
import com.jts.gjcxfzksh.utils.DistanceUtil;
import com.jts.gjcxfzksh.utils.TransitMetrics;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.facilities.Facility;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.springframework.stereotype.Service;

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
    /** 任何指标公式/单位/缺失值政策变更都必须升级，避免 JVM 内旧评价值继续命中。 */
    static final String EVALUATION_FORMULA_VERSION = "evaluation-v13";
    static final String BUS_NETWORK_LENGTH_POLICY = TransitMetrics.BUS_NETWORK_LENGTH_POLICY;
    static final String BUS_NETWORK_AREA_POLICY = TransitMetrics.BUS_NETWORK_AREA_POLICY;

    private final ConcurrentMap<String, TrajectoryBuildState> trajectoryStates = new ConcurrentHashMap<>();

    /**
     * 轨迹缓存构建并发数：默认 1（保持磁盘 I/O 友好），多模型服务器可调大避免构建串行排队。
     */
    @org.springframework.beans.factory.annotation.Value("${matsim.trajectory-build-threads:1}")
    private int trajectoryBuildThreads;

    private ExecutorService trajectoryExecutor;
    private final java.util.function.Consumer<MatsimData> cacheWarmupHook = this::prepareEvaluationOnModelLoad;

    @jakarta.annotation.PostConstruct
    void initTrajectoryExecutor() {
        java.util.concurrent.atomic.AtomicInteger index = new java.util.concurrent.atomic.AtomicInteger();
        trajectoryExecutor = Executors.newFixedThreadPool(Math.max(1, trajectoryBuildThreads), r -> {
            Thread thread = new Thread(r, "trajectory-cache-builder-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        // 模型缓存预热完成后立刻在后台算好体检评估指标，前端进页面即可直接命中缓存
        com.jts.gjcxfzksh.data.Datasource.registerCacheWarmupHook(cacheWarmupHook);
    }

    @jakarta.annotation.PreDestroy
    void destroyTrajectoryExecutor() {
        com.jts.gjcxfzksh.data.Datasource.unregisterCacheWarmupHook(cacheWarmupHook);
        if (trajectoryExecutor != null) {
            trajectoryExecutor.shutdownNow();
        }
    }

    /**
     * 缓存预热阶段预计算体检评估指标（与 evaluation() 共用缓存键）。
     * 小模型和大模型都只组合已落盘的 visual/population 指标，不要求明细常驻内存，
     * 也不在体检评估请求到达时即时扫描 plans/events。
     */
    void prepareEvaluationOnModelLoad(MatsimData data) {
        if (data == null) return;
        String cacheKey = evaluationCacheKey(data, "全市");
        long start = System.currentTimeMillis();
        evictStaleEvaluationEntries(data.getName(), cacheKey);
        Map<String, Object> info = MatsimPrecomputedCache.readInfo(data);
        if (info == null || info.isEmpty()) return;
        evaluationCache.computeIfAbsent(cacheKey,
                ignored -> buildLargeEvaluationFromCaches(data, info, "全市"));
        log.info("体检评估指标预热完成: model={}, 耗时={}ms", data.getName(), System.currentTimeMillis() - start);
    }

    String evaluationCacheKey(MatsimData data) {
        return evaluationCacheKey(data, "全市");
    }

    String evaluationCacheKey(MatsimData data, String district) {
        return data.getName()
                + "#v" + com.jts.gjcxfzksh.data.Datasource.currentLoadVersion(data.getName())
                + "#" + EVALUATION_FORMULA_VERSION
                + "#district=" + normalizeDistrict(district)
                + "#visual=" + MatsimPrecomputedCache.visualCacheTag(data)
                + "#revision=" + MatsimSourceFingerprint.modelRevision(data);
    }

    /**
     * 同一模型重载后版本号递增，旧加载版本条目不再命中。
     * 同一加载版本下保留“全市 + 各行政区”缓存，避免行政区切换时重复组合结果。
     */
    private void evictStaleEvaluationEntries(String modelName, String currentKey) {
        String modelPrefix = modelName + "#v";
        int loadVersionEnd = currentKey.indexOf('#', modelPrefix.length());
        String currentLoadPrefix = loadVersionEnd < 0
                ? currentKey : currentKey.substring(0, loadVersionEnd + 1);
        evaluationCache.keySet().removeIf(key ->
                key.startsWith(modelPrefix) && !key.startsWith(currentLoadPrefix));
    }

    @Override
    public Map<String, Object> info(DatasourceParam param) {
        MatsimData matsim_data = matsim_data(param);
        Map<String, Object> cached = MatsimPrecomputedCache.readInfo(matsim_data);
        if (cached != null) {
            return cached;
        }
        // 大小模型一律只消费落盘缓存。即使 manifest 异常地显示 ready 但 info.json
        // 缺失/损坏，也不能退回请求线程扫描 plans/events，应由后台缓存链重建。
        if (cached == null) {
            return Map.of(
                    "status", "generating",
                    "message", "模型总览缓存正在后台生成，暂不在请求线程中扫描 plans/events",
                    "largeModel", matsim_data.isLargeModel()
            );
        }
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> availability = new LinkedHashMap<>();
        TransitMetrics.RoadTransitContext roadTransit =
                TransitMetrics.RoadTransitContext.from(matsim_data.getSchedule());
        Object runtimeCrs = matsim_data.getSchedule() == null ? null
                : matsim_data.getSchedule().getAttributes().getAttribute("coordinateReferenceSystem");
        TransitMetrics.MetricCoordinateContext coordinates =
                TransitMetrics.MetricCoordinateContext.fromCrs(
                        runtimeCrs == null ? null : String.valueOf(runtimeCrs));
        TransitMetrics.RoadNetworkStats roadNetwork = TransitMetrics.roadNetworkStats(
                matsim_data.getSchedule(), matsim_data.getNetwork(), roadTransit);
        // 总体水平
        // 常住人口密度：只用 desc.json 明确面积，缺失时返回 nodata，不用站点凸包猜测。
        Set<Coord> coords = roadTransit.stopCoords();
        Double areaKm2 = effectiveAreaKm2(matsim_data, coords);
        Long personCount = matsim_data.getPopulation() == null
                ? null : (long) matsim_data.getPopulation().getPersons().size();
        Long residentCount = residentHomePersonCount(matsim_data);
        boolean hasPlanDetails = matsim_data.getPopulation() != null
                && !matsim_data.getPopulation().getPersons().isEmpty();
        result.put("czrkmd", areaKm2 == null || residentCount == null
                ? null : (int) Math.round(residentCount / areaKm2));
        result.put("residentHomePersons", residentCount);
        if (areaKm2 == null) {
            markUnavailable(availability, "czrkmd", "nodata", "desc.json 未提供有效面积");
            markUnavailable(availability, "gjxwmd", "nodata", "desc.json 未提供有效面积");
        } else if (residentCount == null) {
            markUnavailable(availability, "czrkmd", "unsupported", "缺少 plans 常住人口 home 位置计数");
        }
        // 公交线网密度 km/km2：双向 link 去重、剔除轨道线路
        Double length = roadNetwork.lengthMeters();
        result.put("busNetworkLengthMeters", length);
        Double busNetworkDensity = TransitMetrics.busNetworkDensityKmPerKm2(length, areaKm2);
        result.put("gjxwmd", busNetworkDensity == null ? null
                : NumberUtil.round(busNetworkDensity, 2).doubleValue());
        result.put("busNetworkAreaKm2", areaKm2);
        result.put("busNetworkLengthPolicy", BUS_NETWORK_LENGTH_POLICY);
        result.put("busNetworkAreaPolicy", BUS_NETWORK_AREA_POLICY);
        if (length == null) {
            markUnavailable(availability, "gjxwmd", "nodata",
                    roadNetwork.missingGeometryRoutes() > 0
                            ? "公交线路引用了缺失或无有效长度的 route/link"
                            : "没有有效的公交线路物理路段");
        }
        // 车站300m覆盖率    %
        TransitMetrics.CoverageStats coverageStats = hasPlanDetails
                && roadTransit.coordinateTransformFailures() == 0
                ? TransitMetrics.coverage300Stats(coords, matsim_data.getPopulation(), coordinates) : null;
        result.put("fgl_300", TransitMetrics.coverageResult(
                coverageStats == null ? null : coverageStats.percent()));
        result.put("coverageValidHomePersons", coverageStats == null
                ? null : coverageStats.validHomePersons());
        result.put("coverageTotalPersons", personCount);
        result.put("coverageMissingHomePersons", coverageStats == null || personCount == null
                || !coordinates.isSupported() ? null
                : Math.max(0L, personCount - coverageStats.validHomePersons()));
        Object populationCoordinateFailures = matsim_data.getPopulation() == null ? null
                : matsim_data.getPopulation().getAttributes().getAttribute("coordinateTransformFailures");
        result.put("coordinateTransformFailures", populationCoordinateFailures instanceof Number number
                ? Math.max(0L, number.longValue()) : null);
        result.put("coverageStatus", !hasPlanDetails || !coordinates.isSupported() ? "unsupported"
                : roadTransit.coordinateTransformFailures() > 0 ? "unsupported"
                : coverageStats == null || coverageStats.percent() == null ? "nodata" : "ready");
        result.put("coverageDenominatorPolicy", "valid-first-home");
        result.put("scheduleCoordinateTransformFailures", roadTransit.coordinateTransformFailures());
        // 万人公共交通车辆保有量：全网运营车辆 ID 去重后按车长折算标台，再除以常住人口。
        TransitMetrics.RoadFleetInventoryStats fleetStats =
                TransitMetrics.roadFleetInventory(matsim_data.getSchedule(), matsim_data.getTv());
        Long fleetSize = fleetStats.operatingVehicles();
        Double standardVehicles = fleetStats.standardVehicles();
        result.put("operatingVehicles", fleetSize);
        result.put("standardVehicles", standardVehicles);
        result.put("wrbyl", personCount == null || personCount <= 0
                || standardVehicles == null || standardVehicles <= 0 ? null
                : NumberUtil.round(standardVehicles / (personCount / 10000.0), 2).doubleValue());
        // 公共交通机动化出行分担率：公共交通主方式出行 ÷ 机动化主方式出行。
        TransitMetrics.BusTripShareStats busTrips = hasPlanDetails
                ? TransitMetrics.busTripShareStats(matsim_data.getPopulation(), roadTransit) : null;
        result.put("fxfdl", busTrips == null || busTrips.publicTransportMotorizedPercent() == null ? null
                : Map.of("pt", round2(busTrips.publicTransportMotorizedPercent()),
                "bus", round2(busTrips.busPercent())));
        if (!hasPlanDetails) {
            markUnavailable(availability, "fgl_300", "unsupported", "未保留 plans 坐标明细");
            markUnavailable(availability, "fxfdl", "unsupported", "未保留 plans 出行链明细");
        } else if (!coordinates.isSupported()) {
            result.put("fgl_300", null);
            markUnavailable(availability, "fgl_300", "unsupported", "模型未声明可识别的坐标系，不能计算300m地面距离");
        } else if (roadTransit.coordinateTransformFailures() > 0) {
            result.put("fgl_300", null);
            markUnavailable(availability, "fgl_300", "unsupported",
                    "部分公交站坐标转换失败，不能用剩余站点计算部分覆盖率");
        }
        // 车均日载客量 = 日客运总量(上车人次) / 全网去重运营车辆数。
        long boardings = roadTransit.boardingCount(matsim_data.getPersonTracks());
        long departureTotal = roadTransit.departureCount();
        TransitMetrics.BusOperatingEfficiency efficiency = TransitMetrics.busOperatingEfficiency(
                boardings, fleetSize == null ? 0 : fleetSize, departureTotal);
        result.put("boardings", boardings);
        result.put("allTransitBoardings", matsim_data.getPersonTracks().stream()
                .filter(track -> Boolean.TRUE.equals(track.getEnter())).count());
        result.put("cjrzkl", efficiency.perVehicleDaily() == null ? null
                : NumberUtil.round(efficiency.perVehicleDaily(), 2).doubleValue());
        if (!fleetStats.hasOfficialStandardVehicles()) {
            markUnavailable(availability, "wrbyl", "unsupported",
                    "公交车辆或车型车长不完整，无法按官方车长系数折算标台");
        }
        if (fleetSize == null) {
            markUnavailable(availability, "cjrzkl", "nodata", "时刻表没有引用有效的公交车辆ID");
        }
        if (personCount == null) {
            markUnavailable(availability, "wrbyl", "unsupported", "当前加载模式没有可用的人口计数");
        }
        // 单班次载客量   人次/班 = 日客运总量(上车) / 日发班次总数
        result.put("dbczkl", efficiency.perDeparture() == null ? null
                : NumberUtil.round(efficiency.perDeparture(), 2).doubleValue());
        // 需求强度
        // 公交人均日出行次数：公交主方式完整 OD 出行数 / 常住人口，明确排除地铁。
        Double rcxcs = TransitMetrics.busTripsPerResident(busTrips, residentCount);
        result.put("rcxcs", rcxcs == null ? null : NumberUtil.round(rcxcs, 3).doubleValue());
        if (rcxcs == null) {
            markUnavailable(availability, "rcxcs", "unsupported",
                    "缺少公交主方式完整出行次数、常住人口分母，或存在无法判定制式的居民 legacy pt 出行");
        }
        // 依赖客流比例：原实现为硬编码 50 的占位值，已移除；业务确认口径后再实现
        // 线路效益
        // 线路非直线系数
        TransitMetrics.RouteShapeStats routeShape = TransitMetrics.roadRouteShapeStats(
                matsim_data.getSchedule(), matsim_data.getNetwork(), roadTransit, coordinates);
        Double xlfzxxs = routeShape.averageNonLinearCoefficient() == null ? null
                : NumberUtil.round(routeShape.averageNonLinearCoefficient(), 2).doubleValue();
        result.put("xlfzxxs", xlfzxxs);
        // 线路重复系数
        Double xlcfxs = routeShape.repetitionCoefficient() == null ? null
                : NumberUtil.round(routeShape.repetitionCoefficient(), 2).doubleValue();
        result.put("xlcfxs", xlcfxs);
        result.put("validRoutes", routeShape.validRoutes());
        result.put("excludedLoopRoutes", routeShape.excludedLoopRoutes());
        result.put("missingGeometryRoutes", routeShape.missingGeometryRoutes());
        if (xlfzxxs == null) {
            markUnavailable(availability, "xlfzxxs", coordinates.isSupported() ? "nodata" : "unsupported",
                    coordinates.isSupported()
                            ? routeShape.missingGeometryRoutes() > 0
                                    ? "部分公交线路缺 route/link 或首末站坐标，未下发部分真值"
                                    : "没有有效的非环形公交线路"
                            : "模型未声明可识别的坐标系，不能计算首末站地面直线距离");
        }
        if (xlcfxs == null) {
            markUnavailable(availability, "xlcfxs", "nodata",
                    routeShape.missingGeometryRoutes() > 0
                            ? "部分公交线路缺 route/link，未下发部分真值"
                            : "没有有效的公交线路或物理路段");
        }
        // 线路平均高峰满载率：每班先取最大站段满载率，再对全部早晚高峰班次等权平均。
        TransitMetrics.PeakAverageLoadAccumulator peakLoadAccumulator =
                TransitMetrics.PeakAverageLoadAccumulator.roadBus(
                        matsim_data.getSchedule(), matsim_data.getTv(), roadTransit, true);
        matsim_data.getPersonTracks().forEach(peakLoadAccumulator::accept);
        TransitMetrics.PeakAverageLoadStats peakLoad = peakLoadAccumulator.finish();
        result.put("xlmzl", peakLoad.percent() == null ? null
                : NumberUtil.round(peakLoad.percent(), 2).doubleValue());
        result.put("peakScheduledDepartures", peakLoad.scheduledPeakDepartures());
        result.put("peakValidCapacityDepartures", peakLoad.validCapacityDepartures());
        result.put("peakMissingCapacityDepartures", peakLoad.missingCapacityDepartures());
        if (peakLoad.percent() == null) {
            markUnavailable(availability, "xlmzl",
                    peakLoad.missingCapacityDepartures() > 0 ? "unsupported" : "nodata",
                    peakLoad.missingCapacityDepartures() > 0
                            ? "高峰公交班次存在缺失车辆或额定载客量，不能下发部分真值"
                            : "早晚高峰时段没有有效公交班次");
        }
        // 线路客流强度：日公交上车人次/计划运营车公里。
        Map<String, Double> xlklqd = routePersonStrength(matsim_data);
        result.put("xlklqd", xlklqd.entrySet().stream()
                .limit(5)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        v -> NumberUtil.round(v.getValue(), 2).doubleValue(),
                        (e1, e2) -> NumberUtil.round(e1, 2).doubleValue(),
                        LinkedHashMap::new
                )));
        TransitMetrics.RoadOperatingDistanceStats operatingDistance =
                TransitMetrics.roadOperatingDistanceStats(
                        matsim_data.getSchedule(), matsim_data.getNetwork(), roadTransit);
        Double passengerStrength = TransitMetrics.busPassengerStrength(boardings, operatingDistance);
        Double xlklqdTotal = passengerStrength == null ? null
                : NumberUtil.round(passengerStrength, 2).doubleValue();
        result.put("xlklqd_total", xlklqdTotal);
        result.put("busPassengerBoardingsPerVehicleKm", xlklqdTotal);
        result.put("busOperatingVehicleKilometers", operatingDistance.vehicleKilometers());
        result.put("xlklqd_sum", xlklqdTotal);
        if (xlklqdTotal == null) {
            markUnavailable(availability, "xlklqd",
                    operatingDistance.missingGeometryRoutes() > 0 ? "unsupported" : "nodata",
                    operatingDistance.missingGeometryRoutes() > 0
                            ? "有发班的公交路径缺少有效线路里程，不能下发部分真值"
                            : "缺少公交上车人次或计划运营车公里");
        }
        // 车公里运营成本  元*乘客*km
        // 车单位人次运营成本    元*人次
        // 运营服务
        // 公共汽电车与小汽车运行速度比
        result.put("yxsdb", hasPlanDetails ? runSpeed(matsim_data.getPopulation(), roadTransit) : null);
        // 平均候车时间   min
        Double awaitMinutes = TransitMetrics.averageRoadBusAwaitMinutes(
                matsim_data.getPopulation(), roadTransit);
        result.put("pjhcsj", awaitMinutes == null ? null : NumberUtil.round(awaitMinutes, 2).doubleValue());
        if (awaitMinutes == null) {
            markUnavailable(availability, "pjhcsj", "nodata", "plans 中无可用的公交候车时间样本");
        }
        result.put("peakAverageLoadRatePercent", result.get("xlmzl"));
        if (busTrips != null && busTrips.unresolvedLegacyPtJourneys() > 0) {
            String reason = "plans 中存在无法通过 TransitPassengerRoute 与 schedule 解析制式的 legacy pt 出行";
            for (String metric : List.of("yxsdb", "pjhcsj")) {
                result.put(metric, null);
                markUnavailable(availability, metric, "unsupported", reason);
            }
        }
        if (!roadTransit.isComplete()) {
            markRoadMetricsUnsupported(result, availability, roadTransit);
        }
        // 场站设施
        // 车均场站面积   m2*标台
        ensureNullAvailability(result, availability);
        result.put("availability", availability);
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
        String district = normalizeDistrict(param.getDistrict());
        // 预热阶段（缓存构建钩子）已算好则直接命中，大模型也一样
        String cacheKey = evaluationCacheKey(data, district);
        Map<String, Object> cached = evaluationCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        // 大小模型统一组合已落盘的 visual-v25 / population-v9 工件。体检页不再即时扫描
        // plans 或解压 personTracks，从而保证同一模型、不同加载模式的公式与结果完全一致。
        Map<String, Object> info = MatsimPrecomputedCache.readInfo(data);
        if (info == null || info.isEmpty()) {
            return Map.of("status", "generating", "message", "模型评估缓存正在后台生成");
        }
        evictStaleEvaluationEntries(data.getName(), cacheKey);
        return evaluationCache.computeIfAbsent(cacheKey,
                ignored -> buildLargeEvaluationFromCaches(data, info, district));
    }

    private Map<String, Object> buildLargeEvaluationFromCaches(
            MatsimData data, Map<String, Object> info, String district) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> availability = copyAvailability(info.get("availability"));
        Map<?, ?> density = densityForDistrict(info, district);
        TransitMetrics.RoadTransitContext roadTransit =
                TransitMetrics.RoadTransitContext.from(data.getSchedule());
        TransitMetrics.RoadNetworkStats roadNetwork = TransitMetrics.roadNetworkStats(
                data.getSchedule(), data.getNetwork(), roadTransit);
        values.put("czrkmd", densityValue(density, "czrkmd", info.get("czrkmd")));
        values.put("gjxwmd", densityValue(density, "gjxwmd", info.get("gjxwmd")));
        if (values.get("czrkmd") != null) availability.remove("czrkmd");
        if (values.get("gjxwmd") != null) availability.remove("gjxwmd");
        values.put("fgl300", coveragePercent(info.get("fgl_300")));
        values.put("wrbyl", info.get("wrbyl"));

        Number boardings = info.get("boardings") instanceof Number number ? number : null;
        values.put("khl", boardings == null ? null : boardings.longValue());
        TransitMetrics.RoadFleetInventoryStats fleetStats =
                TransitMetrics.roadFleetInventory(data.getSchedule(), data.getTv());
        Long fleet = fleetStats.operatingVehicles();
        values.put("pcs", fleet != null && fleet > 0 ? fleet : null);
        if (fleet == null) {
            markUnavailable(availability, "pcs", "nodata", "时刻表没有引用有效的公交车辆ID");
        }
        Number cachedNetworkMeters = info.get("busNetworkLengthMeters") instanceof Number number
                ? number : null;
        Double networkMeters = cachedNetworkMeters == null
                ? roadNetwork.lengthMeters() : cachedNetworkMeters.doubleValue();
        values.put("yylc", networkMeters == null ? null : round2(networkMeters / 1000.0));
        if (networkMeters == null) {
            markUnavailable(availability, "yylc", "nodata",
                    roadNetwork.missingGeometryRoutes() > 0
                            ? "公交线路引用了缺失或无有效长度的 route/link"
                            : "没有有效的公交线路物理路段");
        }
        values.put("xlls", roadTransit.lineCount());
        values.put("cxfdl", modeShare(info.get("fxfdl"), Constant.ROUTE_MODE_PT));
        values.put("cjrzkl", info.get("cjrzkl"));
        values.put("dbczkl", info.get("dbczkl"));
        values.put("rcxcs", info.get("rcxcs"));

        values.put("xlfzxxs", info.get("xlfzxxs"));
        values.put("xlcfxs", info.get("xlcfxs"));
        values.put("xlmzl", info.get("xlmzl"));
        values.put("xlklqd", info.get("xlklqd_total"));
        values.put("yxsdb", speedRatio(info.get("yxsdb")));
        values.put("pjhcsj", info.get("pjhcsj"));
        values.put("pjhccs", info.get("pjhccs"));
        values.put("gjjbbl", info.get("gjjbbl"));
        values.put("cjczmj", null);
        markUnavailable(availability, "cjczmj", "unsupported", "模型未提供场站面积与对应标台分母");
        if (!roadTransit.isComplete()) {
            markRoadMetricsUnsupported(values, availability, roadTransit);
        }
        ensureNullAvailability(values, availability);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ready");
        result.put("source", "disk-cache");
        result.put("formulaVersion", EVALUATION_FORMULA_VERSION);
        result.put("cacheRevision", MatsimPrecomputedCache.visualCacheTag(data));
        Map<String, Object> formulaMetadata = new LinkedHashMap<>();
        formulaMetadata.put("validRoutes", info.get("validRoutes"));
        formulaMetadata.put("excludedLoopRoutes", info.get("excludedLoopRoutes"));
        formulaMetadata.put("missingGeometryRoutes", info.get("missingGeometryRoutes"));
        formulaMetadata.put("maxNonLinearCoefficient", info.get("maxNonLinearCoefficient"));
        formulaMetadata.put("abnormalNonLinearLines", info.get("abnormalNonLinearLines"));
        formulaMetadata.put("maxNonLinearLineId", info.get("maxNonLinearLineId"));
        formulaMetadata.put("abnormalNonLinearLineIds", info.get("abnormalNonLinearLineIds"));
        formulaMetadata.put("coverageTotalPersons", info.get("coverageTotalPersons"));
        formulaMetadata.put("coverageValidHomePersons", info.get("coverageValidHomePersons"));
        formulaMetadata.put("coverageMissingHomePersons", info.get("coverageMissingHomePersons"));
        formulaMetadata.put("evaluationDistrict", district);
        formulaMetadata.put("residentHomePersons",
                densityValue(density, "residentHomePersons", info.get("residentHomePersons")));
        formulaMetadata.put("busNetworkLengthMeters",
                densityValue(density, "busNetworkLengthMeters", info.get("busNetworkLengthMeters")));
        formulaMetadata.put("busNetworkAreaKm2",
                densityValue(density, "areaKm2", info.get("busNetworkAreaKm2")));
        formulaMetadata.put("densityPolicy", info.get("densityPolicy"));
        formulaMetadata.put("densityBoundarySignature", info.get("densityBoundarySignature"));
        formulaMetadata.put("busNetworkLengthPolicy", BUS_NETWORK_LENGTH_POLICY);
        formulaMetadata.put("busNetworkAreaPolicy", BUS_NETWORK_AREA_POLICY);
        formulaMetadata.put("coordinateTransformFailures", info.get("coordinateTransformFailures"));
        formulaMetadata.put("scheduleCoordinateTransformFailures",
                info.get("scheduleCoordinateTransformFailures"));
        formulaMetadata.put("coverageStatus", info.get("coverageStatus"));
        formulaMetadata.put("coverageDenominatorPolicy", "valid-first-home");
        formulaMetadata.put("quantityPolicy", "model-original-no-sampling-no-scaling");
        formulaMetadata.put("roadTransitScope", "bus,trolleybus,brt");
        formulaMetadata.put("tripMainModePolicy", "rail-and-nonroad-transit-over-road-bus");
        formulaMetadata.put("publicTransportSharePolicy", TransitMetrics.PUBLIC_TRANSPORT_SHARE_POLICY);
        formulaMetadata.put("busDailyTripsPolicy", TransitMetrics.BUS_DAILY_TRIPS_POLICY);
        formulaMetadata.put("peakAverageLoadRatePolicy",
                TransitMetrics.PEAK_AVERAGE_LOAD_RATE_POLICY);
        formulaMetadata.put("busPassengerStrengthPolicy",
                TransitMetrics.BUS_PASSENGER_STRENGTH_POLICY);
        formulaMetadata.put("busCarSpeedRatioPolicy", TransitMetrics.BUS_CAR_SPEED_RATIO_POLICY);
        formulaMetadata.put("speedPeriodPolicy", info.get("speedPeriodPolicy"));
        Object cachedCarSpeedScope = info.get("carSpeedSpatialScope");
        formulaMetadata.put("carSpeedSpatialScope",
                (cachedCarSpeedScope instanceof String scope && !scope.isBlank()
                        ? scope : "all-model-urban-roads")
                        + "; proxy because model has no should-have-bus-lane flag");
        formulaMetadata.put("peakBusOperatingDistanceMeters",
                info.get("peakBusOperatingDistanceMeters"));
        formulaMetadata.put("peakBusOperatingTravelSeconds",
                info.get("peakBusOperatingTravelSeconds"));
        formulaMetadata.put("peakBusOperatingDepartures",
                info.get("peakBusOperatingDepartures"));
        formulaMetadata.put("peakCarDistanceMeters", info.get("peakCarDistanceMeters"));
        formulaMetadata.put("peakCarTravelSeconds", info.get("peakCarTravelSeconds"));
        formulaMetadata.put("peakCarSamples", info.get("peakCarSamples"));
        formulaMetadata.put("busWaitTimePolicy", TransitMetrics.BUS_WAIT_TIME_POLICY);
        formulaMetadata.put("busWaitSamples", info.get("busWaitSamples"));
        formulaMetadata.put("busAverageTransfersPolicy",
                TransitMetrics.BUS_AVERAGE_TRANSFERS_POLICY);
        formulaMetadata.put("busRailFeederPolicy", TransitMetrics.BUS_RAIL_FEEDER_POLICY);
        formulaMetadata.put("busNonLinearPolicy", TransitMetrics.BUS_NON_LINEAR_POLICY);
        formulaMetadata.put("busServiceJourneys", info.get("busServiceJourneys"));
        formulaMetadata.put("busServiceTransfers", info.get("busServiceTransfers"));
        formulaMetadata.put("busRailJourneys", info.get("busRailJourneys"));
        formulaMetadata.put("fleetDenominatorPolicy", TransitMetrics.BUS_FLEET_POLICY);
        formulaMetadata.put("standardVehiclePolicy", TransitMetrics.BUS_STANDARD_VEHICLE_POLICY);
        formulaMetadata.put("departureDenominatorPolicy", "scheduled-road-transit-departures");
        result.put("formulaMetadata", formulaMetadata);
        result.put("values", values);
        result.put("availability", availability);
        return result;
    }

    private static String normalizeDistrict(String district) {
        String value = district == null ? "" : district.trim();
        return value.isEmpty() ? "全市" : value;
    }

    private static Map<?, ?> densityForDistrict(Map<String, Object> info, String district) {
        if (info.get("densityByDistrict") instanceof Map<?, ?> byDistrict) {
            String normalized = normalizeDistrict(district);
            Object exact = byDistrict.get(normalized);
            if (exact instanceof Map<?, ?> row) return row;
            if (!"全市".equals(normalized)) {
                throw new BusinessException("行政区密度指标不存在: " + normalized);
            }
        }
        return Map.of();
    }

    private static Object densityValue(Map<?, ?> density, String key, Object fallback) {
        Object value = density.get(key);
        return value == null ? fallback : value;
    }

    private static Object coveragePercent(Object value) {
        if (!(value instanceof Map<?, ?> coverage) || Boolean.TRUE.equals(coverage.get("nodata"))) return null;
        return coverage.get("cover");
    }

    static Object modeShare(Object value, String key) {
        if (value instanceof Map<?, ?> shares) {
            if (Constant.ROUTE_MODE_PT.equals(key)) {
                Object publicTransportMotorizedShare = shares.get(Constant.ROUTE_MODE_PT);
                return publicTransportMotorizedShare instanceof Number number
                        ? round2(number.doubleValue()) : null;
            }
            return shares.get(key);
        }
        return value instanceof Number ? value : null;
    }

    private static Object speedRatio(Object value) {
        if (value instanceof Number) return value;
        if (!(value instanceof Map<?, ?> speeds)) return null;
        double pt = numeric(speeds.get("ptAvg"));
        double car = numeric(speeds.get("carAvg"));
        return pt > 0 && car > 0 ? round2(pt / car) : null;
    }

    private static double numeric(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private static Map<String, Object> copyAvailability(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> source) {
            source.forEach((key, item) -> {
                if (key == null) return;
                String metric = switch (String.valueOf(key)) {
                    case "fgl_300" -> "fgl300";
                    case "fxfdl" -> "cxfdl";
                    default -> String.valueOf(key);
                };
                result.put(metric, item);
            });
        }
        return result;
    }

    private static void markUnavailable(Map<String, Object> availability,
                                        String metric, String status, String reason) {
        availability.put(metric, Map.of("status", status, "reason", reason));
    }

    private static void markRoadMetricsUnsupported(Map<String, Object> values,
                                                   Map<String, Object> availability,
                                                   TransitMetrics.RoadTransitContext roadTransit) {
        String reason = "时刻表中有 " + roadTransit.unresolvedRoutes()
                + " 条 legacy pt 线路无法可靠判定是否为公共汽电车";
        for (String metric : List.of(
                "gjxwmd", "fgl_300", "fgl300", "wrbyl",
                "boardings", "khl", "pcs", "yylc", "xlls", "cjrzkl", "dbczkl",
                "rcxcs", "xlfzxxs", "xlcfxs", "xlmzl", "xlklqd_total", "xlklqd",
                "yxsdb", "pjhcsj", "pjhccs", "gjjbbl")) {
            if (!values.containsKey(metric)) continue;
            values.put(metric, null);
            markUnavailable(availability, metric, "unsupported", reason);
        }
    }

    private static void ensureNullAvailability(Map<String, Object> values, Map<String, Object> availability) {
        values.forEach((metric, value) -> {
            if (value == null) {
                availability.putIfAbsent(metric, Map.of(
                        "status", "nodata",
                        "reason", "源数据中缺少计算该指标所需的有效字段"
                ));
            }
        });
    }

    private Map<String, Object> buildEvaluation(MatsimData data) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> availability = new LinkedHashMap<>();
        TransitMetrics.RoadTransitContext roadTransit =
                TransitMetrics.RoadTransitContext.from(data.getSchedule());
        Object runtimeCrs = data.getSchedule() == null ? null
                : data.getSchedule().getAttributes().getAttribute("coordinateReferenceSystem");
        TransitMetrics.MetricCoordinateContext coordinates =
                TransitMetrics.MetricCoordinateContext.fromCrs(
                        runtimeCrs == null ? null : String.valueOf(runtimeCrs));
        TransitMetrics.RoadNetworkStats roadNetwork = TransitMetrics.roadNetworkStats(
                data.getSchedule(), data.getNetwork(), roadTransit);
        // 总人口仍用于万人保有量、日出行次数等既有分母；常住人口密度单独取有效 home 人数。
        Integer modelPersons = data.getPopulation() == null
                ? null : data.getPopulation().getPersons().size();
        Long residentPersons = residentHomePersonCount(data);
        long modelBoardings = roadTransit.boardingCount(data.getPersonTracks());
        // 数量固定为模型文件原始值：desc.scale 只是元数据，不改变人口、客流或任何评价指标。
        Double populationCount = modelPersons == null ? null : modelPersons.doubleValue();
        double boardings = modelBoardings;
        Set<Coord> stopCoords = roadTransit.stopCoords();
        Double areaKm2 = effectiveAreaKm2(data, stopCoords);

        // ===== 总体水平 =====
        // 公交线网密度只算公共汽电车线路经过的道路里程：双向 link 去重、剔除轨道线路
        Double busNetKm = roadNetwork.lengthMeters() == null
                ? null : roadNetwork.lengthMeters() / 1000.0;
        values.put("czrkmd", areaKm2 == null || residentPersons == null
                ? null : (int) Math.round(residentPersons / areaKm2));
        Double busNetworkDensity = TransitMetrics.busNetworkDensityKmPerKm2(
                roadNetwork.lengthMeters(), areaKm2);
        values.put("gjxwmd", busNetworkDensity == null ? null : round2(busNetworkDensity));
        if (areaKm2 == null) {
            markUnavailable(availability, "czrkmd", "nodata", "desc.json 未提供有效面积");
            markUnavailable(availability, "gjxwmd", "nodata", "desc.json 未提供有效面积");
        } else if (residentPersons == null) {
            markUnavailable(availability, "czrkmd", "unsupported", "缺少 plans 常住人口 home 位置计数");
        }
        if (busNetKm == null) {
            markUnavailable(availability, "gjxwmd", "nodata",
                    roadNetwork.missingGeometryRoutes() > 0
                            ? "公交线路引用了缺失或无有效长度的 route/link"
                            : "没有有效的公交线路物理路段");
            markUnavailable(availability, "yylc", "nodata",
                    roadNetwork.missingGeometryRoutes() > 0
                            ? "公交线路引用了缺失或无有效长度的 route/link"
                            : "没有有效的公交线路物理路段");
        }
        TransitMetrics.CoverageStats coverageStats = roadTransit.coordinateTransformFailures() == 0
                ? TransitMetrics.coverage300Stats(stopCoords, data.getPopulation(), coordinates)
                : new TransitMetrics.CoverageStats(0, 0, null);
        Double coverage = coverageStats.percent();
        values.put("fgl300", coverage == null ? null : round2(coverage));
        if (coverage == null) {
            boolean unsupportedCoverage = data.getPopulation() == null || !coordinates.isSupported()
                    || roadTransit.coordinateTransformFailures() > 0;
            markUnavailable(availability, "fgl300", unsupportedCoverage ? "unsupported" : "nodata",
                    data.getPopulation() == null
                            ? "当前加载模式未保留 plans 坐标明细"
                            : roadTransit.coordinateTransformFailures() > 0
                            ? "部分公交站坐标转换失败，不能用剩余站点计算部分覆盖率"
                            : coordinates.isSupported()
                            ? "缺少站点或 plans 活动坐标"
                            : "模型未声明可识别的坐标系，不能计算300m地面距离");
        }
        TransitMetrics.RoadFleetInventoryStats fleetStats =
                TransitMetrics.roadFleetInventory(data.getSchedule(), data.getTv());
        Long fleet = fleetStats.operatingVehicles();
        Double standardVehicles = fleetStats.standardVehicles();
        values.put("wrbyl", populationCount == null || populationCount <= 0
                || standardVehicles == null || standardVehicles <= 0
                ? null : round2(standardVehicles / (populationCount / 10000.0)));
        // ===== 总量（优化评估双模型对比：客流量 / 配车数 / 线网运营规模；均不依赖 plans，稳健）=====
        values.put("khl", Math.round(boardings));                        // 客流量：日客运总量（模型原始上车人次）
        values.put("pcs", fleet == null || fleet == 0 ? null : fleet);    // 配车数：全网去重运营车辆数
        values.put("yylc", busNetKm == null ? null : round2(busNetKm)); // 公共汽电车线网运营里程（km）
        values.put("xlls", roadTransit.lineCount());                     // 公共汽电车线路条数
        // 大模型不加载 plans，population 为 null 时基于出行计划的指标输出 null（前端显示"暂无数据"）
        // 公共交通机动化出行分担率：按 trip 主方式统计，不是 leg 数占比。
        TransitMetrics.BusTripShareStats busTrips = data.getPopulation() == null ? null
                : TransitMetrics.busTripShareStats(data.getPopulation(), roadTransit);
        values.put("cxfdl", busTrips == null || busTrips.publicTransportMotorizedPercent() == null
                ? null : round2(busTrips.publicTransportMotorizedPercent()));
        Map<VehicleId, List<PTPersonTrack>> tracksByVehicle = data.getPersonTracks().stream()
                .filter(roadTransit::isRoadTrack)
                .collect(Collectors.groupingBy(PTPersonTrack::getVehicleId));
        long departureCount = roadTransit.departureCount();
        TransitMetrics.BusOperatingEfficiency efficiency = TransitMetrics.busOperatingEfficiency(
                boardings, fleet == null ? 0 : fleet, departureCount);
        // 车均日载客量 = 日客运总量 / 全网去重运营车辆数。
        values.put("cjrzkl", efficiency.perVehicleDaily() == null
                ? null : round2(efficiency.perVehicleDaily()));
        if (fleet == null) {
            for (String metric : List.of("pcs", "cjrzkl")) {
                markUnavailable(availability, metric, "nodata", "时刻表没有引用有效的公交车辆ID");
            }
        }
        if (!fleetStats.hasOfficialStandardVehicles()) {
            markUnavailable(availability, "wrbyl", "unsupported",
                    "公交车辆或车型车长不完整，无法按官方车长系数折算标台");
        }
        if (populationCount == null) {
            markUnavailable(availability, "wrbyl", "unsupported", "当前加载模式没有可用的人口计数");
        }
        values.put("dbczkl", efficiency.perDeparture() == null
                ? null : round2(efficiency.perDeparture()));

        // ===== 需求强度 =====
        Double dailyTrips = TransitMetrics.busTripsPerResident(busTrips, residentPersons);
        values.put("rcxcs", dailyTrips == null ? null : NumberUtil.round(dailyTrips, 3).doubleValue());
        if (dailyTrips == null) {
            markUnavailable(availability, "rcxcs", "unsupported",
                    "缺少公交主方式完整出行次数、常住人口分母，或存在无法判定制式的居民 legacy pt 出行");
        }

        // ===== 线路效益（线路级指标取全线路平均/全网口径）=====
        TransitMetrics.RouteShapeStats routeShape = TransitMetrics.roadRouteShapeStats(
                data.getSchedule(), data.getNetwork(), roadTransit, coordinates);
        values.put("xlfzxxs", routeShape.averageNonLinearCoefficient() == null
                ? null : round2(routeShape.averageNonLinearCoefficient()));
        values.put("xlcfxs", routeShape.repetitionCoefficient() == null
                ? null : round2(routeShape.repetitionCoefficient()));
        if (values.get("xlfzxxs") == null) {
            markUnavailable(availability, "xlfzxxs", coordinates.isSupported() ? "nodata" : "unsupported",
                    coordinates.isSupported()
                            ? routeShape.missingGeometryRoutes() > 0
                                    ? "部分公交线路缺 route/link 或首末站坐标，未下发部分真值"
                                    : "没有有效的非环形公交线路"
                            : "模型未声明可识别的坐标系，不能计算首末站地面直线距离");
        }
        if (values.get("xlcfxs") == null) {
            markUnavailable(availability, "xlcfxs", "nodata",
                    routeShape.missingGeometryRoutes() > 0
                            ? "部分公交线路缺 route/link，未下发部分真值"
                            : "没有有效的公交线路或物理路段");
        }
        TransitMetrics.PeakAverageLoadAccumulator peakLoadAccumulator =
                TransitMetrics.PeakAverageLoadAccumulator.roadBus(
                        data.getSchedule(), data.getTv(), roadTransit, true);
        data.getPersonTracks().forEach(peakLoadAccumulator::accept);
        TransitMetrics.PeakAverageLoadStats peakLoad = peakLoadAccumulator.finish();
        values.put("xlmzl", peakLoad.percent() == null ? null : round2(peakLoad.percent()));
        if (peakLoad.percent() == null) {
            markUnavailable(availability, "xlmzl",
                    peakLoad.missingCapacityDepartures() > 0 ? "unsupported" : "nodata",
                    peakLoad.missingCapacityDepartures() > 0
                            ? "高峰公交班次存在缺失车辆或额定载客量，不能下发部分真值"
                            : "早晚高峰时段没有有效公交班次");
        }
        TransitMetrics.RoadOperatingDistanceStats operatingDistance =
                TransitMetrics.roadOperatingDistanceStats(
                        data.getSchedule(), data.getNetwork(), roadTransit);
        Double passengerStrength = TransitMetrics.busPassengerStrength(boardings, operatingDistance);
        values.put("xlklqd", passengerStrength == null ? null : round2(passengerStrength));
        if (passengerStrength == null) {
            markUnavailable(availability, "xlklqd",
                    operatingDistance.missingGeometryRoutes() > 0 ? "unsupported" : "nodata",
                    operatingDistance.missingGeometryRoutes() > 0
                            ? "有发班的公交路径缺少有效线路里程，不能下发部分真值"
                            : "缺少公交上车人次或计划运营车公里");
        }

        // ===== 运营服务 =====
        Map<String, Double> speeds = data.getPopulation() == null ? Map.of()
                : runSpeed(data.getPopulation(), roadTransit);
        Double ptAvg = speeds.get("ptAvg");
        Double carAvg = speeds.get("carAvg");
        values.put("yxsdb", ptAvg == null || carAvg == null || carAvg <= 0 ? null : round2(ptAvg / carAvg));
        Double awaitMinutes = TransitMetrics.averageRoadBusAwaitMinutes(data.getPopulation(), roadTransit);
        values.put("pjhcsj", awaitMinutes == null ? null : round2(awaitMinutes));
        values.put("pjhccs", null);
        values.put("gjjbbl", null);
        values.put("cjczmj", null);
        markUnavailable(availability, "pjhccs", "unsupported", "缺少全部公交出行链分母");
        markUnavailable(availability, "gjjbbl", "unsupported", "缺少全部公交出行链分母");
        markUnavailable(availability, "cjczmj", "unsupported", "模型未提供场站面积与对应标台分母");
        if (busTrips != null && busTrips.unresolvedLegacyPtJourneys() > 0) {
            String reason = "plans 中存在无法通过 TransitPassengerRoute 与 schedule 解析制式的 legacy pt 出行";
            for (String metric : List.of("yxsdb", "pjhcsj")) {
                values.put(metric, null);
                markUnavailable(availability, metric, "unsupported", reason);
            }
        }
        if (!roadTransit.isComplete()) {
            markRoadMetricsUnsupported(values, availability, roadTransit);
        }

        // 场站设施（车均场站面积）：模型无场站数据，暂无法统计
        ensureNullAvailability(values, availability);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ready");
        result.put("formulaVersion", EVALUATION_FORMULA_VERSION);
        result.put("cacheRevision", MatsimPrecomputedCache.visualCacheTag(data));
        Map<String, Object> formulaMetadata = new LinkedHashMap<>();
        formulaMetadata.put("validRoutes", routeShape.validRoutes());
        formulaMetadata.put("excludedLoopRoutes", routeShape.excludedLoopRoutes());
        formulaMetadata.put("missingGeometryRoutes", routeShape.missingGeometryRoutes());
        formulaMetadata.put("coverageTotalPersons", modelPersons);
        formulaMetadata.put("coverageValidHomePersons", coverageStats.validHomePersons());
        formulaMetadata.put("coverageMissingHomePersons", modelPersons == null || !coordinates.isSupported()
                ? null : Math.max(0L, (long) modelPersons - coverageStats.validHomePersons()));
        formulaMetadata.put("residentHomePersons", residentPersons);
        formulaMetadata.put("busNetworkLengthMeters", roadNetwork.lengthMeters());
        formulaMetadata.put("busNetworkAreaKm2", areaKm2);
        formulaMetadata.put("busNetworkLengthPolicy", BUS_NETWORK_LENGTH_POLICY);
        formulaMetadata.put("busNetworkAreaPolicy", BUS_NETWORK_AREA_POLICY);
        Object populationCoordinateFailures = data.getPopulation() == null ? null
                : data.getPopulation().getAttributes().getAttribute("coordinateTransformFailures");
        formulaMetadata.put("coordinateTransformFailures", populationCoordinateFailures instanceof Number number
                ? Math.max(0L, number.longValue()) : null);
        formulaMetadata.put("scheduleCoordinateTransformFailures",
                roadTransit.coordinateTransformFailures());
        formulaMetadata.put("coverageStatus", data.getPopulation() == null || !coordinates.isSupported()
                || roadTransit.coordinateTransformFailures() > 0
                ? "unsupported" : coverageStats.percent() == null ? "nodata" : "ready");
        formulaMetadata.put("coverageDenominatorPolicy", "valid-first-home");
        formulaMetadata.put("quantityPolicy", "model-original-no-sampling-no-scaling");
        formulaMetadata.put("roadTransitScope", "bus,trolleybus,brt");
        formulaMetadata.put("tripMainModePolicy", "rail-and-nonroad-transit-over-road-bus");
        formulaMetadata.put("publicTransportSharePolicy", TransitMetrics.PUBLIC_TRANSPORT_SHARE_POLICY);
        formulaMetadata.put("busDailyTripsPolicy", TransitMetrics.BUS_DAILY_TRIPS_POLICY);
        formulaMetadata.put("peakAverageLoadRatePolicy",
                TransitMetrics.PEAK_AVERAGE_LOAD_RATE_POLICY);
        formulaMetadata.put("busPassengerStrengthPolicy",
                TransitMetrics.BUS_PASSENGER_STRENGTH_POLICY);
        formulaMetadata.put("fleetDenominatorPolicy", TransitMetrics.BUS_FLEET_POLICY);
        formulaMetadata.put("standardVehiclePolicy", TransitMetrics.BUS_STANDARD_VEHICLE_POLICY);
        formulaMetadata.put("departureDenominatorPolicy", "scheduled-road-transit-departures");
        result.put("formulaMetadata", formulaMetadata);
        result.put("values", values);
        result.put("availability", availability);
        return result;
    }

    /**
     * 密度类指标面积（km²）：按当前业务口径，暂将 desc.json.area 视为行政区总面积。
     * stopCoords 参数仅为旧调用签名兼容，不再用于估算面积。
     */
    static Double effectiveAreaKm2(MatsimData data, Set<Coord> stopCoords) {
        double configured = data.getArea();
        if (Double.isFinite(configured) && configured > 0.0) {
            return configured;
        }
        return null;
    }

    /**
     * 优先从内存 plans 直接数有效 home；大模型不常驻 Population 时读取同一套 plans
     * 流式生成的 population-summary.json。
     */
    static Long residentHomePersonCount(MatsimData data) {
        Population population = data.getPopulation();
        if (population != null && !population.getPersons().isEmpty()) {
            return TransitMetrics.residentHomePersonCount(population);
        }
        Map<String, Object> summary = com.jts.gjcxfzksh.data.cache.MatsimPopulationCache
                .readPopulationSummary(data);
        if ("ready".equals(summary.get("status"))
                && summary.get("homePersons") instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        return null;
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
        if (data.isLargeModel() && !MatsimAnalysisCache.isTrajectoryRepairRequired(data)) {
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
    public byte[] trajectoryViewportBinary(
            DatasourceParam param,
            int start,
            int windowSeconds,
            String visibilityMode,
            Double minX,
            Double minY,
            Double maxX,
            Double maxY
    ) {
        MatsimData data = matsim_data(param);
        byte[] chunk = MatsimAnalysisCache.readTrajectoryBinaryViewport(
                data, start, windowSeconds, visibilityMode, minX, minY, maxX, maxY
        );
        if (chunk == null) trajectory(param);
        return chunk;
    }

    @Override
    public byte[] trajectoryFrameBinary(
            DatasourceParam param,
            int time,
            int bucketSeconds,
            String visibilityMode,
            Double minX,
            Double minY,
            Double maxX,
            Double maxY
    ) {
        MatsimData data = matsim_data(param);
        byte[] frame = MatsimAnalysisCache.readTrajectoryBinaryFrame(
                data,
                time,
                bucketSeconds,
                visibilityMode,
                minX,
                minY,
                maxX,
                maxY
        );
        if (frame == null) {
            trajectory(param);
        }
        return frame;
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

    @Override
    public String trajectoryViewportTag(
            DatasourceParam param,
            int start,
            int windowSeconds,
            String visibilityMode,
            Double minX,
            Double minY,
            Double maxX,
            Double maxY
    ) {
        MatsimData data = matsim_data(param);
        return MatsimAnalysisCache.trajectoryViewportETag(
                data, start, windowSeconds, visibilityMode, minX, minY, maxX, maxY
        );
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
                throw new IllegalStateException("读取源文件修改时间失败: " + filePath, e);
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
                throw new IllegalStateException("读取源文件大小失败: " + filePath, e);
            }
        }
    }

    public Map<String, Double> runSpeed(Population population,
                                        TransitMetrics.RoadTransitContext roadTransit) {
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
                    if (TransitMetrics.isResolvedRoadPublicTransportLeg(leg, roadTransit)) {
                        if (leg.getRoute() != null && Double.isFinite(leg.getRoute().getDistance())
                                && leg.getRoute().getDistance() > 0) {
                            Double inVehicleSeconds = TransitMetrics.inVehicleTravelSeconds(leg, roadTransit);
                            if (inVehicleSeconds != null) {
                                ptTime += inVehicleSeconds;
                                ptDist += leg.getRoute().getDistance();
                            }
                        }
                    } else if (Constant.ROUTE_MODE_CAR.equals(leg.getMode())) {
                        if (leg.getTravelTime().isDefined() && leg.getRoute() != null
                                && Double.isFinite(leg.getTravelTime().seconds())
                                && leg.getTravelTime().seconds() > 0
                                && Double.isFinite(leg.getRoute().getDistance())
                                && leg.getRoute().getDistance() > 0) {
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

    /**
     * 运行路径客流强度（人次/车公里，按客流降序）。
     * TransitRoute ID 在 MATSim 中只在所属 TransitLine 内唯一，跨线路可重复，
     * 因此上车记录按 lineId+routeId 复合键分组；输出键在 routeId 全局唯一时用裸
     * routeId，重复时用 "lineId::routeId" 消歧，避免同键互相覆盖。
     * 返回值不做四舍五入，由调用方在展示层统一 round，保证排序基于原始值。
     */
    private Map<String, Double> routePersonStrength(MatsimData matsim_data) {
        TransitMetrics.RoadTransitContext roadTransit =
                TransitMetrics.RoadTransitContext.from(matsim_data.getSchedule());
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
            for (TransitRoute route : transitLine.getRoutes().values()) {
                if (roadTransit.isRoadRoute(transitLine, route)) {
                    routeIdCounts.merge(route.getId().toString(), 1, Integer::sum);
                }
            }
        }

        Map<String, Double> result = new HashMap<>();
        for (Map.Entry<Id<TransitLine>, TransitLine> line : transitLines.entrySet()) {
            TransitLine transitLine = line.getValue();
            for (Map.Entry<Id<TransitRoute>, TransitRoute> route : transitLine.getRoutes().entrySet()) {
                if (!roadTransit.isRoadRoute(transitLine, route.getValue())) continue;
                TransitRoute transitRoute = route.getValue();
                NetworkRoute networkRoute = transitRoute.getRoute();
                double distance = DistanceUtil.distance(networkRoute, matsim_data.getNetwork());
                String routeId = route.getKey().toString();
                String lineRouteKey = line.getKey() + "::" + routeId;
                double p = boardingsByLineRoute.getOrDefault(lineRouteKey, 0L);
                double vehicleKm = distance > 0
                        ? distance / 1000.0 * transitRoute.getDepartures().size() : 0.0;
                String outputKey = routeIdCounts.getOrDefault(routeId, 0) > 1 ? lineRouteKey : routeId;
                result.put(outputKey, vehicleKm == 0 ? 0.0 : p / vehicleKm);
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

    // legType / legTypeRant（按 leg 数统计方式占比）已移除：一次公交出行在 output_plans 里会展开成多条 leg，
    // 接驳步行 leg 进分母、换乘的 pt leg 重复进分子，得到的不是分担率。
    // 替代实现见 TransitMetrics.tripModeSharePercent（按 trip 主方式统计）。

}
