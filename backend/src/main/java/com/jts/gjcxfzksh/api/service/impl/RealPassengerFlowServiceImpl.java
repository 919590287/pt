package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.exception.BusinessException;
import com.jts.gjcxfzksh.api.model.params.RealPassengerFlowParam;
import com.jts.gjcxfzksh.api.service.RealPassengerFlowService;
import com.jts.gjcxfzksh.config.MatsimConfig;
import com.jts.gjcxfzksh.data.cache.MatsimPopulationCache;
import com.jts.gjcxfzksh.data.cache.RealPopulationCache;
import com.jts.gjcxfzksh.utils.TransitMetrics;
import jakarta.annotation.Resource;
import lombok.extern.log4j.Log4j2;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 把“客流数据”目录中的真实刷卡/车辆聚合 CSV 适配为仿真监测组件已经使用的面板契约。
 * 这里不复制线路和站点几何：几何仍由 RealDataService 从当前 SHP/站序读取，因而线路或站点
 * 修改后地图始终以真实数据为准；本服务只提供客流与运营统计。
 */
@Log4j2
@Service
public class RealPassengerFlowServiceImpl implements RealPassengerFlowService {

    private static final String DEFAULT_AREA = "广州市";
    private static final String PASSENGER_FOLDER = "客流数据";
    private static final String TRANSIT_FOLDER = "公交线路站点";
    private static final String BUS_DEPOT_FOLDER = "公交场站";
    private static final String STOP_SEQUENCE = "站点/line_stop_sequence.csv";
    private static final String ROUTE_SHP = "线路/routes.shp";
    private static final String ROUTE_DEPARTURES = "线路/routes_departures.csv";
    private static final String REAL_EVALUATION_FORMULA_VERSION = "evaluation-v13-real-v2";
    private static final int HOURS = 24;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private MatsimConfig matsimConfig;

    private final Map<String, CachedDataset> cache = new ConcurrentHashMap<>();
    private final Map<String, CachedCorridorNetwork> corridorNetworkCache = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    @Override
    public Map<String, Object> capabilities(String areaName) {
        String area = safeArea(areaName);
        Path file = passengerRoot(area).resolve("模块可用性说明.csv");
        List<Map<String, Object>> modules = new ArrayList<>();
        Set<String> available = new LinkedHashSet<>();
        if (Files.isRegularFile(file)) {
            readCsv(file, row -> {
                Map<String, Object> module = new LinkedHashMap<>();
                String platform = text(row, "platform_module");
                String panel = text(row, "left_panel_module");
                String status = text(row, "availability").toLowerCase(Locale.ROOT);
                module.put("platformModule", platform);
                module.put("leftPanelModule", panel);
                module.put("availability", status);
                module.put("primaryData", text(row, "primary_data"));
                module.put("availableStatistics", text(row, "available_statistics"));
                module.put("knownLimitations", text(row, "known_limitations"));
                module.put("available", !"unavailable".equals(status));
                modules.add(module);
                if (!"unavailable".equals(status)) available.add(platform + "::" + panel);
            });
        }
        Path populationFile = RealPopulationCache.sourcePath(matsimConfig.realDataPath(area));
        if (RealPopulationCache.isAvailable(populationFile)) {
            String platform = "运行监测";
            String panel = "公交出行监测-人口分布监测";
            Map<String, Object> module = modules.stream()
                    .filter(item -> platform.equals(item.get("platformModule"))
                            && panel.equals(item.get("leftPanelModule")))
                    .findFirst()
                    .orElseGet(() -> {
                        Map<String, Object> added = new LinkedHashMap<>();
                        added.put("platformModule", platform);
                        added.put("leftPanelModule", panel);
                        modules.add(added);
                        return added;
                    });
            module.put("availability", "available");
            module.put("primaryData", RealPopulationCache.POPULATION_FOLDER + "/"
                    + RealPopulationCache.POPULATION_FILE);
            module.put("availableStatistics", "通勤居住人口、通勤就业人口、常住人口及100米网格空间分布");
            module.put("knownLimitations", "坐标为100米网格中心点，不代表个人精确住址或就业地址");
            module.put("available", true);
            available.add(platform + "::" + panel);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", Files.isDirectory(passengerRoot(area)) ? "ready" : "missing");
        result.put("areaName", area);
        result.put("modules", modules);
        result.put("availableKeys", available);
        List<String> serviceDates = serviceDates(passengerRoot(area).resolve("总体小时客流.csv"));
        result.put("serviceDates", serviceDates);
        result.put("serviceDayCount", serviceDates.size());
        result.put("sourceFolder", PASSENGER_FOLDER);
        return result;
    }

    @Override
    public Map<String, Object> overallFlow(String areaName, String serviceDate) {
        Dataset data = dataset(areaName, serviceDate);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ready");
        result.put("source", "real");
        result.put("selectedServiceDate", data.selectedDate);
        result.put("serviceDays", data.serviceDays);
        result.put("hourlyByMode", Map.of("bus", averageList(data.overall, data.serviceDays)));
        result.put("busOperation", operationSummary(data));
        result.put("operatorOperations", operatorOperations(data));
        result.put("dailyFlow", dailyFlow(data.dailyOverall));
        return result;
    }

    @Override
    public Map<String, Object> routePanel(String areaName, String serviceDate) {
        Dataset data = dataset(areaName, serviceDate);
        Map<String, Object> routes = new LinkedHashMap<>();
        for (RouteAcc route : data.routes.values()) {
            if (route.totalBoarding() <= 0 && route.totalAlighting() <= 0) continue;
            Map<String, Object> summary = routePanel(data, route, true);
            routes.put(route.routeKey(), summary);
        }
        Map<String, Object> groups = new LinkedHashMap<>();
        for (LineGroup group : data.lineGroups.values()) {
            groups.put(group.key(), groupPanel(data, group, true));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ready");
        result.put("source", "real");
        result.put("selectedServiceDate", data.selectedDate);
        result.put("payloadKind", "index");
        result.put("serviceDays", data.serviceDays);
        result.put("routes", routes);
        result.put("lineGroups", groups);
        result.put("summary", routeLeaderboard(data));
        return result;
    }

    @Override
    public Map<String, Object> routePanelDetail(RealPassengerFlowParam param) {
        Dataset data = dataset(param.getAreaName(), param.getServiceDate());
        String routeId = safe(param.getRouteId());
        if (routeId.startsWith("bus::real-line::")) {
            LineGroup group = data.lineGroups.get(routeId.substring("bus::".length()));
            return group == null ? Map.of() : groupPanel(data, group, false);
        }
        RouteAcc route = data.routes.get(routeId);
        if (route == null && !safe(param.getLineId()).isBlank()) {
            route = data.routes.values().stream()
                    .filter(item -> item.groupId().equals(param.getLineId()) && item.authorityId.equals(routeId))
                    .findFirst().orElse(null);
        }
        return route == null ? Map.of() : routePanel(data, route, false);
    }

    @Override
    public Map<String, Object> stationPanel(String areaName, String serviceDate) {
        Dataset data = dataset(areaName, serviceDate);
        Map<String, Object> stations = new LinkedHashMap<>();
        for (StationAcc station : data.stations.values()) {
            stations.put(station.name, stationPanel(data, station, true));
        }
        List<Map<String, Object>> leaderboard = data.stations.values().stream()
                .sorted(Comparator.comparingDouble(StationAcc::totalFlow).reversed())
                .limit(50)
                .map(station -> Map.<String, Object>of(
                        "stationName", station.name,
                        "passengerFlow", average(station.totalFlow(), data.serviceDays)))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ready");
        result.put("source", "real");
        result.put("selectedServiceDate", data.selectedDate);
        result.put("payloadKind", "index");
        result.put("serviceDays", data.serviceDays);
        result.put("stations", stations);
        result.put("summary", Map.of("leaderboard", Map.of("bus", leaderboard, "subway", List.of())));
        return result;
    }

    @Override
    public Map<String, Object> stationPanelDetail(RealPassengerFlowParam param) {
        Dataset data = dataset(param.getAreaName(), param.getServiceDate());
        StationAcc station = findStation(data, param.getStationName(), param.getFacilityId());
        return station == null ? Map.of() : stationPanel(data, station, false);
    }

    @Override
    public Map<String, Object> evaluation(String areaName, String serviceDate, String district) {
        Dataset data = dataset(areaName, serviceDate);
        String scope = district == null || district.isBlank() ? "全市" : district.trim();
        double passengers = Arrays.stream(data.overall).sum() / data.serviceDays;
        double departures = data.routes.values().stream().mapToDouble(item -> item.departures).sum() / data.serviceDays;
        double operatedKm = data.routes.values().stream().mapToDouble(item -> item.mileageKm).sum() / data.serviceDays;
        double transfers = data.routes.values().stream()
                .flatMap(item -> item.transfers.values().stream())
                .mapToDouble(values -> Arrays.stream(values).sum()).sum() / data.serviceDays;
        double completeBusTrips = data.routes.values().stream()
                .flatMap(item -> item.ods.values().stream())
                .mapToDouble(item -> item.count).sum() / data.serviceDays;
        long operatingVehicles = data.vehicleIds.size();
        OperationRatios operation = operationRatios(
                passengers, operatingVehicles, departures, operatedKm);

        List<double[]> stopLngLat = data.stopsById.values().stream()
                .map(stop -> new double[]{stop.lon, stop.lat})
                .filter(point -> Double.isFinite(point[0]) && Double.isFinite(point[1]))
                .toList();
        Path populationFile = RealPopulationCache.sourcePath(
                matsimConfig.realDataPath(data.area));
        RealPopulationCache.EvaluationPopulationStats cityPopulation =
                RealPopulationCache.evaluationStats(populationFile, "全市", stopLngLat);
        RealPopulationCache.EvaluationPopulationStats scopedPopulation =
                "全市".equals(scope) ? cityPopulation
                        : RealPopulationCache.evaluationStats(populationFile, scope, stopLngLat);

        CorridorNetwork network = corridorNetwork(data.area);
        CorridorMetricStats corridorMetrics = corridorMetricStats(network, scope);
        Double depotLandArea = depotLandAreaSquareMeters(
                matsimConfig.realDataPath(data.area).resolve(BUS_DEPOT_FOLDER));

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("czrkmd", scopedPopulation == null || scopedPopulation.density() == null
                ? null : Math.round(scopedPopulation.density()));
        values.put("gjxwmd", scopedPopulation == null || scopedPopulation.areaKm2() <= 0
                || corridorMetrics.scopedNetworkLengthMeters() <= 0
                ? null : round(corridorMetrics.scopedNetworkLengthMeters()
                / 1000.0 / scopedPopulation.areaKm2(), 2));
        values.put("fgl300", cityPopulation == null
                ? null : nullableRound(cityPopulation.coveragePercent(), 2));
        // 真实车辆文件没有车长/车型，不能按官方车长系数折算标台。
        values.put("wrbyl", null);
        // 真实刷卡数据没有小汽车等全部机动化出行分母。
        values.put("cxfdl", null);
        values.put("cjrzkl", operatingVehicles > 0 ? round(operation.perVehicle(), 2) : null);
        values.put("dbczkl", departures > 0 ? round(operation.perTrip(), 2) : null);
        values.put("rcxcs", cityPopulation == null || cityPopulation.residentPersons() <= 0
                || completeBusTrips <= 0 ? null
                : round(completeBusTrips / cityPopulation.residentPersons(), 3));
        values.put("xlfzxxs", nullableRound(network.averageNonLinearCoefficient(), 2));
        values.put("xlcfxs", nullableRound(corridorMetrics.repetitionCoefficient(), 2));
        // 缺少逐班车辆额定容量，不能计算各高峰班次最大站段满载率。
        values.put("xlmzl", null);
        values.put("xlklqd", operatedKm > 0 ? round(operation.intensity(), 2) : null);
        // 缺少同一高峰窗的小汽车运行里程与时间。
        values.put("yxsdb", null);
        // 刷卡上车时间不是乘客到站时间，不能推算等待时间。
        values.put("pjhcsj", null);
        values.put("pjhccs", completeBusTrips > 0
                ? round(transfers / completeBusTrips, 4) : null);
        // 当前真实行程明细没有完整轨道乘坐链。
        values.put("gjjbbl", null);
        values.put("cjczmj", nullableRound(
                depotAreaPerVehicle(depotLandArea, operatingVehicles), 2));
        values.put("khl", Math.round(passengers));
        values.put("pcs", operatingVehicles > 0 ? operatingVehicles : null);
        values.put("yylc", corridorMetrics.scopedNetworkLengthMeters() > 0
                ? round(corridorMetrics.scopedNetworkLengthMeters() / 1000.0, 2) : null);
        values.put("xlls", network.busLines());

        Map<String, Object> availability = new LinkedHashMap<>();
        markMissingEvaluationValues(values, availability, Map.ofEntries(
                Map.entry("czrkmd", "缺少职住人口常住人口数量或行政区面积"),
                Map.entry("gjxwmd", "缺少真实公交线网几何或行政区面积"),
                Map.entry("fgl300", "缺少职住人口常住人口网格或公交站点坐标"),
                Map.entry("wrbyl", "真实车辆数据缺少车长/车型，不能按官方车长系数折算标台"),
                Map.entry("cxfdl", "缺少全部机动化方式完整出行分母"),
                Map.entry("cjrzkl", "缺少可识别的运营车辆车牌"),
                Map.entry("dbczkl", "缺少真实日发车班次"),
                Map.entry("rcxcs", "缺少完整公交出行或职住常住人口分母"),
                Map.entry("xlfzxxs", "缺少有效非环公交线路几何"),
                Map.entry("xlcfxs", "缺少可无向去重的公交线路几何"),
                Map.entry("xlmzl", "缺少逐班车辆额定容量"),
                Map.entry("xlklqd", "缺少真实公交上车人次或运营车公里"),
                Map.entry("yxsdb", "缺少同一高峰窗小汽车运行里程与时间"),
                Map.entry("pjhcsj", "缺少乘客到站时间"),
                Map.entry("pjhccs", "缺少完整公交出行分母"),
                Map.entry("gjjbbl", "缺少完整公交与轨道乘坐链"),
                Map.entry("cjczmj", "缺少公交场站用地面积或运营车辆分母")
        ));

        Map<String, Object> formulaMetadata = new LinkedHashMap<>();
        formulaMetadata.put("evaluationDistrict", scope);
        formulaMetadata.put("residentPopulationSource",
                RealPopulationCache.POPULATION_FOLDER + "/" + RealPopulationCache.POPULATION_FILE + "::常住人口数量");
        formulaMetadata.put("residentPersons",
                cityPopulation == null ? null : cityPopulation.residentPersons());
        formulaMetadata.put("scopedResidentPersons",
                scopedPopulation == null ? null : scopedPopulation.residentPersons());
        formulaMetadata.put("scopedAreaKm2",
                scopedPopulation == null ? null : scopedPopulation.areaKm2());
        formulaMetadata.put("coveredResidentPersons",
                cityPopulation == null ? null : cityPopulation.coveredResidentPersons());
        formulaMetadata.put("busNetworkLengthMeters",
                corridorMetrics.scopedNetworkLengthMeters());
        formulaMetadata.put("operatingVehicles", operatingVehicles);
        formulaMetadata.put("dailyBoardings", passengers);
        formulaMetadata.put("dailyDepartures", departures);
        formulaMetadata.put("dailyOperatingVehicleKm", operatedKm);
        formulaMetadata.put("completeBusTrips", completeBusTrips);
        formulaMetadata.put("transferCount", transfers);
        formulaMetadata.put("depotAreaSource", BUS_DEPOT_FOLDER + "::F004(用地面积㎡)");
        formulaMetadata.put("depotLandAreaSquareMeters", depotLandArea);
        formulaMetadata.put("formulas", evaluationFormulaDescriptions());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ready");
        result.put("source", "real");
        result.put("formulaVersion", REAL_EVALUATION_FORMULA_VERSION);
        result.put("selectedServiceDate", data.selectedDate);
        result.put("serviceDays", data.serviceDays);
        result.put("values", values);
        result.put("availability", availability);
        result.put("formulaMetadata", formulaMetadata);
        result.put("limitations", "严格使用仿真规范公式；缺少规范分子或分母的指标返回 null，不使用替代公式");
        return result;
    }

    private static Double nullableRound(Double value, int scale) {
        return value == null || !Double.isFinite(value) ? null : round(value, scale);
    }

    static Double depotAreaPerVehicle(Double depotLandAreaSquareMeters,
                                      long operatingVehicles) {
        return depotLandAreaSquareMeters != null
                && depotLandAreaSquareMeters > 0
                && operatingVehicles > 0
                ? depotLandAreaSquareMeters / operatingVehicles
                : null;
    }

    private static void markMissingEvaluationValues(
            Map<String, Object> values,
            Map<String, Object> availability,
            Map<String, String> reasons) {
        reasons.forEach((key, reason) -> {
            if (values.get(key) == null) {
                availability.put(key, Map.of("status", "unsupported", "reason", reason));
            }
        });
    }

    private static Map<String, String> evaluationFormulaDescriptions() {
        return Map.ofEntries(
                Map.entry("czrkmd", "职住常住人口数量÷行政区面积(km²)"),
                Map.entry("gjxwmd", "无向去重公交物理线网长度(km)÷行政区面积(km²)"),
                Map.entry("fgl300", "距公交站300m内职住常住人口÷职住常住人口×100%"),
                Map.entry("wrbyl", "按官方车长系数折算标台数÷常住人口×10000"),
                Map.entry("cxfdl", "公共交通主方式完整OD出行数÷机动化主方式完整OD出行数×100%"),
                Map.entry("cjrzkl", "公交日上车人次÷去重运营车辆"),
                Map.entry("dbczkl", "公交日上车人次÷日发车班次"),
                Map.entry("rcxcs", "公交主方式完整OD出行数÷常住人口"),
                Map.entry("xlfzxxs", "线路各方向平均里程÷首末站地面直线距离，再对非环线路等权平均"),
                Map.entry("xlcfxs", "各公交线路方向平均长度之和÷无向去重公交物理线网长度"),
                Map.entry("xlmzl", "各早晚高峰班次最大站段在车人数÷额定载客量，再对班次等权平均×100%"),
                Map.entry("xlklqd", "公交日上车人次÷日运营车公里"),
                Map.entry("yxsdb", "高峰公交Σ运营里程/Σ运行时间÷高峰小汽车Σ距离/Σ时间"),
                Map.entry("pjhcsj", "平均(上车时间-乘客到站时间)"),
                Map.entry("pjhccs", "公交出行换乘总次数÷完整公交出行数"),
                Map.entry("gjjbbl", "同时含公交与轨道的完整OD出行数÷全部含公交的完整OD出行数×100%"),
                Map.entry("cjczmj", "公交场站用地面积总和÷去重运营车辆")
        );
    }

    static Double depotLandAreaSquareMeters(Path folder) {
        if (!Files.isDirectory(folder)) return null;
        Path shapefile;
        try (Stream<Path> files = Files.list(folder)) {
            shapefile = files
                    .filter(path -> !path.getFileName().toString().startsWith("._"))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".shp"))
                    .findFirst().orElse(null);
        } catch (IOException error) {
            throw new BusinessException("读取公交场站目录失败: " + folder.getFileName());
        }
        if (shapefile == null) return null;
        ShapefileDataStore store = null;
        try {
            store = new ShapefileDataStore(shapefile.toUri().toURL());
            store.setCharset(StandardCharsets.UTF_8);
            double total = 0;
            try (SimpleFeatureIterator iterator = store.getFeatureSource().getFeatures().features()) {
                while (iterator.hasNext()) {
                    double area = parseNumber(featureText(iterator.next(), "F004"));
                    if (Double.isFinite(area) && area > 0) total += area;
                }
            }
            return total > 0 ? total : null;
        } catch (IOException error) {
            throw new BusinessException("读取公交场站用地面积失败: " + shapefile.getFileName());
        } finally {
            if (store != null) store.dispose();
        }
    }

    @Override
    public Map<String, Object> center(String areaName, String serviceDate) {
        Dataset data = dataset(areaName, serviceDate);
        if (!Double.isFinite(data.centerLon) || !Double.isFinite(data.centerLat)) return Map.of();
        double[] point = webMercator(data.centerLon, data.centerLat);
        return Map.of("x", point[0], "y", point[1], "lon", data.centerLon, "lat", data.centerLat);
    }

    @Override
    public Map<String, Object> tripEnds(String areaName, String serviceDate) {
        Dataset data = dataset(areaName, serviceDate);
        Map<String, SpatialCell> cellsByKey = new LinkedHashMap<>();
        Map<String, String> stopCells = new HashMap<>();
        for (StationAcc station : data.stations.values()) {
            StopMeta stop = station.ids.stream().map(data.stopsById::get).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            if (stop == null || !Double.isFinite(stop.lon) || !Double.isFinite(stop.lat)) continue;
            double[] point = webMercator(stop.lon, stop.lat);
            int i = (int) Math.floor(point[0] / 100.0);
            int j = (int) Math.floor(point[1] / 100.0);
            String key = i + ":" + j;
            SpatialCell cell = cellsByKey.computeIfAbsent(key, ignored -> new SpatialCell(i, j, station.name));
            cell.origin += Arrays.stream(station.boarding).sum() / data.serviceDays;
            cell.destination += Arrays.stream(station.alighting).sum() / data.serviceDays;
            station.ids.forEach(id -> stopCells.put(id, key));
        }
        List<SpatialCell> cells = new ArrayList<>(cellsByKey.values());
        Map<String, Integer> cellIndexes = new HashMap<>();
        for (int index = 0; index < cells.size(); index++) cellIndexes.put(cells.get(index).key(), index);
        Map<Long, Double> pairs = new HashMap<>();
        for (RouteAcc route : data.routes.values()) {
            for (OdAcc od : route.ods.values()) {
                Integer origin = cellIndexes.get(stopCells.get(od.fromId));
                Integer destination = cellIndexes.get(stopCells.get(od.toId));
                if (origin == null || destination == null) continue;
                long key = ((long) origin << 32) | (destination & 0xffffffffL);
                pairs.merge(key, od.count / data.serviceDays, Double::sum);
            }
        }
        List<List<Object>> cellRows = cells.stream().map(cell -> List.<Object>of(
                cell.i, cell.j, Math.round(cell.origin), Math.round(cell.destination), cell.name)).toList();
        List<List<Integer>> odRows = pairs.entrySet().stream()
                .map(entry -> List.of((int) (entry.getKey() >> 32), (int) (long) entry.getKey(), (int) Math.round(entry.getValue())))
                .filter(row -> row.get(2) > 0)
                .sorted(Comparator.comparingInt((List<Integer> row) -> row.get(2)).reversed())
                .toList();
        return Map.of(
                "status", "ready",
                "source", "real",
                "selectedServiceDate", data.selectedDate,
                "cellSizeMeters", 100,
                "serviceDays", data.serviceDays,
                "cells", cellRows,
                "pairs", odRows
        );
    }

    @Override
    public Map<String, Object> corridor(String areaName, String serviceDate) {
        Dataset data = dataset(areaName, serviceDate);
        CorridorNetwork network = corridorNetwork(data.area);
        Map<CorridorSegmentKey, CorridorAcc> overlays = new HashMap<>();

        // 绘图底表直接使用完整现行 routes.shp 线网。真实客流只负责补充名称与断面客流，
        // 不再决定某条现行线路是否显示；无客流路段仍参与重复系数绘制。
        for (RouteAcc route : data.routes.values()) {
            for (SegmentAcc segment : route.segments.values()) {
                StopMeta from = data.stopsById.get(segment.fromId);
                StopMeta to = data.stopsById.get(segment.toId);
                if (from == null || to == null) continue;
                String segmentName = segment.fromName + "—" + segment.toName;
                List<double[]> path = route.pathBetween(from, to);
                double dailyFlow = Arrays.stream(segment.flow).sum() / data.serviceDays;
                Set<CorridorSegmentKey> visited = new LinkedHashSet<>();
                for (int index = 1; index < path.size(); index++) {
                    double[] pathFrom = path.get(index - 1);
                    double[] pathTo = path.get(index);
                    CorridorSegmentKey key = corridorSegmentKey(pathFrom, pathTo);
                    if (key == null || !visited.add(key)) continue;
                    CorridorBaseSegment base = network.segments.get(key);
                    if (base == null) continue;
                    CorridorAcc item = overlays.computeIfAbsent(
                            key, ignored -> new CorridorAcc(base.from, base.to));
                    item.name = segmentName;
                    item.flow += dailyFlow;
                }
            }
        }
        List<String> names = overlays.values().stream()
                .map(item -> item.name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList();
        Map<String, Integer> nameIndexes = new HashMap<>();
        for (int index = 0; index < names.size(); index++) nameIndexes.put(names.get(index), index);
        List<String> districts = MatsimPopulationCache.streetDistricts();
        List<List<Number>> segments = network.segments.entrySet().stream().map(entry -> {
            CorridorBaseSegment base = entry.getValue();
            CorridorAcc overlay = overlays.get(entry.getKey());
            double[] from = webMercator(base.from[0], base.from[1]);
            double[] to = webMercator(base.to[0], base.to[1]);
            int street = MatsimPopulationCache.locateStreet(
                    (from[0] + to[0]) / 2.0, (from[1] + to[1]) / 2.0);
            Integer nameIndex = overlay == null ? null : nameIndexes.get(overlay.name);
            return List.<Number>of(
                    Math.round(from[0]), Math.round(from[1]), Math.round(to[0]), Math.round(to[1]),
                    base.coefficient, nameIndex == null ? 0xFFFF : nameIndex,
                    street < 0 ? 0xFFFF : street, overlay == null ? 0L : Math.round(overlay.flow));
        }).sorted(Comparator.comparingInt(row -> row.get(4).intValue())).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ready");
        result.put("source", "real");
        result.put("cacheVersion", "real-corridor-undirected-route-segment-v3");
        result.put("selectedServiceDate", data.selectedDate);
        result.put("serviceDays", data.serviceDays);
        result.put("names", names);
        result.put("districts", districts);
        result.put("segments", segments);
        result.put("busLines", network.busLines);
        result.put("maxCoeff", segments.stream().mapToInt(row -> row.get(4).intValue()).max().orElse(0));
        result.put("params", Map.of(
                "modes", "bus",
                "dedup", "undirected-route-geometry-segment",
                "lineScope", "all-current-routes",
                "geometry", "original-route-shp"));
        return result;
    }

    @Override
    public Map<String, Object> vehicle(String areaName, String serviceDate) {
        Dataset data = dataset(areaName, serviceDate);
        List<List<Number>> globalStats = new ArrayList<>();
        List<List<Number>> passengerSeries = new ArrayList<>();
        for (int hour = 0; hour < HOURS; hour++) {
            double vehicles = data.vehicleActive[hour] / data.serviceDays;
            double speed = data.vehicleSpeedWeight[hour] > 0
                    ? data.vehicleSpeedSum[hour] / data.vehicleSpeedWeight[hour] : 0;
            globalStats.add(List.of(hour * 3600, Math.round(vehicles), 0, 0,
                    round(speed * vehicles, 3), 0, 0, Math.round(vehicles), 0, 0));
            double passenger = data.overall[hour] / data.serviceDays;
            passengerSeries.add(List.of(hour * 3600, Math.round(passenger), 0, 0, Math.round(passenger)));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalVehicles", data.vehicleIds.size());
        summary.put("realAggregate", true);
        summary.put("chunks", List.of(Map.of("globalStats", globalStats)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ready");
        result.put("source", "real");
        result.put("selectedServiceDate", data.selectedDate);
        result.put("realAggregate", true);
        result.put("cacheVersion", "real-vehicle-hourly-v1");
        result.put("chunkSeconds", 3600);
        result.put("timeRange", Map.of("min", 0, "max", 86399));
        result.put("summary", summary);
        result.put("passengerSeries", passengerSeries);
        result.put("passengerEvents", List.of());
        result.put("meta", Map.of());
        result.put("representativeServiceDate", data.vehicleServiceDate);
        result.put("vehicleEvents", data.vehicleEvents);
        return result;
    }

    private Dataset dataset(String areaName, String serviceDate) {
        String area = safeArea(areaName);
        String selectedDate = normalizeServiceDate(serviceDate);
        Path root = passengerRoot(area);
        if (!Files.isDirectory(root)) throw new BusinessException("真实客流数据目录不存在: " + root);
        String signature = signature(root, transitRoot(area));
        String cacheKey = area + "::" + (selectedDate.isBlank() ? "average" : selectedDate);
        CachedDataset cached = cache.get(cacheKey);
        if (cached != null && cached.signature.equals(signature)) return cached.data;
        synchronized (locks.computeIfAbsent(cacheKey, ignored -> new Object())) {
            cached = cache.get(cacheKey);
            if (cached != null && cached.signature.equals(signature)) return cached.data;
            long started = System.currentTimeMillis();
            Dataset loaded = load(area, root, selectedDate);
            cache.put(cacheKey, new CachedDataset(signature, loaded));
            log.info("真实客流面板加载完成 area={} selectedDate={} days={} routes={} stations={} elapsed={}ms",
                    area, selectedDate.isBlank() ? "average" : selectedDate, loaded.serviceDays,
                    loaded.routes.size(), loaded.stations.size(), System.currentTimeMillis() - started);
            return loaded;
        }
    }

    private Dataset load(String area, Path root, String selectedDate) {
        Dataset data = new Dataset(area, selectedDate);
        loadStopSequence(data, transitRoot(area).resolve(STOP_SEQUENCE));
        loadOverall(data, root.resolve("总体小时客流.csv"));
        loadLineHours(data, root.resolve("线路小时客流.csv"));
        loadStationHours(data, root.resolve("站点小时客流.csv"));
        loadSegments(data, root.resolve("断面小时客流.csv"));
        loadOd(data, root.resolve("线路OD日统计.csv"));
        loadDemographics(data, root.resolve("客群小时统计.csv"));
        loadTransfers(data, root.resolve("换乘明细.csv"));
        loadOperations(data, root.resolve("线路日运营统计.csv"));
        loadSegmentDistances(data, root.resolve("区间运行时间统计.csv"));
        loadRouteMetadata(data, transitRoot(area).resolve(ROUTE_SHP));
        loadRouteDepartures(data, transitRoot(area).resolve(ROUTE_DEPARTURES));
        loadVehicleEvents(data, root.resolve("车辆到离站明细.csv"));
        data.finish();
        return data;
    }

    private static String normalizeServiceDate(String value) {
        String date = safe(value);
        if (date.isBlank() || "average".equalsIgnoreCase(date)) return "";
        if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) throw new BusinessException("运营日期格式不正确: " + date);
        return date;
    }

    private List<String> serviceDates(Path file) {
        Set<String> dates = new LinkedHashSet<>();
        readCsv(file, row -> {
            String date = text(row, "service_date");
            if (!date.isBlank()) dates.add(date);
        });
        return dates.stream().sorted().toList();
    }

    private void loadStopSequence(Dataset data, Path file) {
        readCsv(file, row -> {
            String routeId = text(row, "line_id");
            String stopId = text(row, "stop_id");
            if (routeId.isBlank() || stopId.isBlank()) return;
            StopMeta stop = new StopMeta(stopId, text(row, "stop_name"), integer(row, "seq"),
                    number(row, "lon"), number(row, "lat"));
            data.stopsByRoute.computeIfAbsent(routeId, ignored -> new ArrayList<>()).add(stop);
            data.stopsById.putIfAbsent(stopId, stop);
            if (Double.isFinite(stop.lon) && Double.isFinite(stop.lat)) {
                data.minLon = Math.min(data.minLon, stop.lon);
                data.minLat = Math.min(data.minLat, stop.lat);
                data.maxLon = Math.max(data.maxLon, stop.lon);
                data.maxLat = Math.max(data.maxLat, stop.lat);
            }
        });
        data.stopsByRoute.values().forEach(stops -> stops.sort(Comparator.comparingInt(item -> item.seq)));
    }

    private void loadOverall(Dataset data, Path file) {
        readCsv(file, row -> {
            String serviceDate = text(row, "service_date");
            data.dates.add(serviceDate);
            if (!data.accepts(serviceDate)) return;
            int hour = hour(row);
            double count = number(row, "all_swipe_count");
            if (hour >= 0) data.overall[hour] += count;
            if (!serviceDate.isBlank()) data.dailyOverall.merge(serviceDate, count, Double::sum);
        });
        data.serviceDays = data.selectedDate.isBlank() ? Math.max(1, data.dates.size()) : 1;
    }

    private void loadLineHours(Dataset data, Path file) {
        readCsv(file, row -> {
            if (!data.accepts(text(row, "service_date"))) return;
            RouteAcc route = data.route(text(row, "authority_line_id"), text(row, "authority_route_name"));
            int hour = hour(row);
            if (route == null || hour < 0) return;
            route.boarding[hour] += number(row, "boarding_count");
            route.alighting[hour] += number(row, "alighting_count");
            route.trips[hour] += number(row, "trip_count");
            String serviceDate = text(row, "service_date");
            if (!serviceDate.isBlank()) route.dailyBoarding.merge(serviceDate, number(row, "boarding_count"), Double::sum);
        });
    }

    private void loadStationHours(Dataset data, Path file) {
        readCsv(file, row -> {
            if (!data.accepts(text(row, "service_date"))) return;
            String routeId = text(row, "authority_line_id");
            String routeName = text(row, "authority_route_name");
            String stopId = text(row, "stop_id");
            String stopName = text(row, "stop_name");
            int hour = hour(row);
            RouteAcc route = data.route(routeId, routeName);
            if (route == null || hour < 0 || stopId.isBlank()) return;
            double board = number(row, "boarding_count");
            double alight = number(row, "alighting_count");
            StationFlow flow = route.stationFlows.computeIfAbsent(stopId, ignored -> new StationFlow(stopId, stopName));
            flow.boarding[hour] += board;
            flow.alighting[hour] += alight;
            StationAcc station = data.station(stopId, stopName);
            station.boarding[hour] += board;
            station.alighting[hour] += alight;
            station.routes.put(routeId, route);
            StationSideAcc side = station.sides.computeIfAbsent(stopId, ignored -> new StationSideAcc(stopId));
            side.boarding[hour] += board;
            side.alighting[hour] += alight;
            side.routes.put(routeId, route);
        });
    }

    private void loadSegments(Dataset data, Path file) {
        readCsv(file, row -> {
            if (!data.accepts(text(row, "service_date"))) return;
            RouteAcc route = data.route(text(row, "authority_line_id"), text(row, "authority_route_name"));
            int hour = hour(row);
            if (route == null || hour < 0) return;
            String fromId = text(row, "from_stop_id");
            String toId = text(row, "to_stop_id");
            String key = fromId + "::" + toId;
            SegmentAcc segment = route.segments.computeIfAbsent(key, ignored -> new SegmentAcc(
                    fromId, text(row, "from_stop_name"), toId, text(row, "to_stop_name"), integer(row, "from_seq")));
            segment.flow[hour] += number(row, "passenger_count");
        });
    }

    private void loadOd(Dataset data, Path file) {
        readCsv(file, row -> {
            if (!data.accepts(text(row, "service_date"))) return;
            RouteAcc route = data.route(text(row, "authority_line_id"), text(row, "authority_route_name"));
            if (route == null) return;
            String fromId = text(row, "board_stop_id");
            String fromName = text(row, "board_stop_name");
            String toId = text(row, "alight_stop_id");
            String toName = text(row, "alight_stop_name");
            double count = number(row, "trip_count");
            String key = fromId + "::" + toId;
            route.ods.computeIfAbsent(key, ignored -> new OdAcc(fromId, fromName, toId, toName)).count += count;
            StationAcc origin = data.station(fromId, fromName);
            StationAcc destination = data.station(toId, toName);
            origin.addOd(route, "out", fromId, toId, toName, count);
            destination.addOd(route, "in", toId, fromId, fromName, count);
        });
    }

    private void loadDemographics(Dataset data, Path file) {
        readCsv(file, row -> {
            if (!data.accepts(text(row, "service_date"))) return;
            String type = text(row, "dimension_type");
            String id = text(row, "dimension_id");
            String group = text(row, "passenger_group");
            double count = number(row, "boarding_count");
            if ("line".equals(type)) {
                RouteAcc route = data.routes.get(id);
                if (route != null) route.demographics.merge(group, count, Double::sum);
            } else if ("station".equals(type)) {
                StationAcc station = data.stationById.get(id);
                if (station != null) station.demographics.merge(group, count, Double::sum);
            }
        });
    }

    private void loadTransfers(Dataset data, Path file) {
        readCsv(file, row -> {
            if (!data.accepts(text(row, "service_date"))) return;
            RouteAcc route = data.routes.get(text(row, "from_line_id"));
            String to = text(row, "to_line_id");
            if (route == null || to.isBlank()) return;
            int hour = dateTimeHour(text(row, "next_board_time"));
            double[] values = route.transfers.computeIfAbsent(to, ignored -> new double[HOURS]);
            values[Math.max(0, hour)] += 1;
        });
    }

    private void loadOperations(Dataset data, Path file) {
        readCsv(file, row -> {
            if (!data.accepts(text(row, "service_date"))) return;
            RouteAcc route = data.route(text(row, "authority_line_id"), text(row, "authority_route_name"));
            if (route == null) return;
            route.vehicles += number(row, "vehicle_count");
            route.departures += number(row, "trip_start_count");
            route.mileageKm += number(row, "mileage_km");
            route.runTimeMinutes += number(row, "run_time_min");
            route.speedWeighted += number(row, "avg_speed_kmh") * Math.max(1, number(row, "run_time_min"));
            int first = dateTimeSeconds(text(row, "first_event_time"));
            int last = dateTimeSeconds(text(row, "last_event_time"));
            if (first >= 0) route.firstTime = Math.min(route.firstTime, first);
            if (last >= 0) route.lastTime = Math.max(route.lastTime, last);
            if (first >= 0 && last >= first) {
                int firstHour = Math.max(0, Math.min(23, first / 3600));
                int lastHour = Math.max(firstHour, Math.min(23, last / 3600));
                double vehicles = number(row, "vehicle_count");
                double speed = number(row, "avg_speed_kmh");
                for (int hour = firstHour; hour <= lastHour; hour++) {
                    data.vehicleActive[hour] += vehicles;
                    data.vehicleSpeedSum[hour] += speed * vehicles;
                    data.vehicleSpeedWeight[hour] += vehicles;
                }
            }
        });
    }

    private void loadSegmentDistances(Dataset data, Path file) {
        readCsv(file, row -> {
            RouteAcc route = data.routes.get(text(row, "authority_line_id"));
            if (route == null) return;
            String key = text(row, "from_stop_id") + "::" + text(row, "to_stop_id");
            double distance = number(row, "avg_mileage_m");
            route.routeDistanceMeters += distance;
            SegmentAcc segment = route.segments.get(key);
            if (segment != null) {
                segment.distanceMeters = distance;
                segment.runTimeMinutes = number(row, "avg_run_time_min");
            }
        });
    }

    /** 企业、首末班及标称间隔始终取现行线路 SHP；只回填已有真实客流的线路。 */
    private void loadRouteMetadata(Dataset data, Path file) {
        if (!Files.isRegularFile(file)) return;
        ShapefileDataStore store = null;
        try {
            store = new ShapefileDataStore(file.toUri().toURL());
            store.setCharset(StandardCharsets.UTF_8);
            try (SimpleFeatureIterator iterator = store.getFeatureSource().getFeatures().features()) {
                while (iterator.hasNext()) {
                    SimpleFeature feature = iterator.next();
                    String routeId = featureText(feature, "line_id");
                    RouteAcc route = data.routes.get(routeId);
                    if (route == null) continue;
                    route.company = featureText(feature, "company");
                    route.scheduledIntervalMin = parseNumber(featureText(feature, "interval"));
                    route.scheduledFirstTime = clockSeconds(featureText(feature, "first"));
                    route.scheduledLastTime = clockSeconds(featureText(feature, "last"));
                    Object shape = feature.getDefaultGeometry();
                    if (shape instanceof Geometry geometry) {
                        route.geometry = Arrays.stream(geometry.getCoordinates())
                                .map(coordinate -> new double[]{coordinate.x, coordinate.y})
                                .filter(point -> Double.isFinite(point[0]) && Double.isFinite(point[1]))
                                .toList();
                    }
                }
            }
        } catch (IOException error) {
            throw new BusinessException("读取真实线路企业字段失败: " + file.getFileName());
        } finally {
            if (store != null) store.dispose();
        }
    }

    /**
     * 真实线路重复系数的线网底表。仿真模式从 transitSchedule 全量线路构建，本方法对应地从
     * 现行 routes.shp 全量公交线路构建；不以刷卡数据中是否出现该线路作为筛选条件。
     */
    private CorridorNetwork corridorNetwork(String area) {
        Path file = transitRoot(area).resolve(ROUTE_SHP);
        if (!Files.isRegularFile(file)) return new CorridorNetwork(Map.of(), 0);
        String signature;
        try {
            signature = Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException error) {
            throw new BusinessException("读取真实线路文件状态失败: " + file.getFileName());
        }
        String cacheKey = file.toAbsolutePath().normalize().toString();
        CachedCorridorNetwork cached = corridorNetworkCache.get(cacheKey);
        if (cached != null && cached.signature.equals(signature)) return cached.network;
        synchronized (locks.computeIfAbsent("corridor-network::" + cacheKey, ignored -> new Object())) {
            cached = corridorNetworkCache.get(cacheKey);
            if (cached != null && cached.signature.equals(signature)) return cached.network;
            CorridorNetwork loaded = loadCorridorNetwork(file);
            corridorNetworkCache.put(cacheKey, new CachedCorridorNetwork(signature, loaded));
            log.info("真实线路重复系数线网加载完成 area={} busLines={} physicalSegments={}",
                    area, loaded.busLines, loaded.segments.size());
            return loaded;
        }
    }

    private CorridorNetwork loadCorridorNetwork(Path file) {
        List<CorridorRouteGeometry> routes = new ArrayList<>();
        ShapefileDataStore store = null;
        try {
            store = new ShapefileDataStore(file.toUri().toURL());
            store.setCharset(StandardCharsets.UTF_8);
            try (SimpleFeatureIterator iterator = store.getFeatureSource().getFeatures().features()) {
                while (iterator.hasNext()) {
                    SimpleFeature feature = iterator.next();
                    if (!"bus".equalsIgnoreCase(featureText(feature, "mode"))) continue;
                    Object shape = feature.getDefaultGeometry();
                    if (!(shape instanceof Geometry geometry)) continue;
                    List<double[]> points = Arrays.stream(geometry.getCoordinates())
                            .map(coordinate -> new double[]{coordinate.x, coordinate.y})
                            .filter(point -> Double.isFinite(point[0]) && Double.isFinite(point[1]))
                            .toList();
                    if (points.size() < 2) continue;
                    String routeName = featureText(feature, "name");
                    String lineId = lineGroupId(routeName.isBlank() ? featureText(feature, "line_id") : routeName);
                    routes.add(new CorridorRouteGeometry(lineId, points));
                }
            }
        } catch (IOException error) {
            throw new BusinessException("读取真实线路重复系数线网失败: " + file.getFileName());
        } finally {
            if (store != null) store.dispose();
        }
        return aggregateCorridorRouteSegments(routes);
    }

    /**
     * 对齐仿真 aggregateTraversals：同一无向几何段聚合不同线路 ID；同线路上下行及重复经过
     * 通过 Set 只计一次。坐标只用于精确判定相同 SHP 段，不做取整、缓冲或道路吸附。
     */
    static CorridorNetwork aggregateCorridorRouteSegments(List<CorridorRouteGeometry> routes) {
        Map<CorridorSegmentKey, CorridorLineAcc> aggregated = new LinkedHashMap<>();
        Set<String> busLines = new LinkedHashSet<>();
        for (CorridorRouteGeometry route : routes) {
            if (route == null || route.lineId == null || route.lineId.isBlank() || route.geometry == null) continue;
            busLines.add(route.lineId);
            for (int index = 1; index < route.geometry.size(); index++) {
                double[] from = route.geometry.get(index - 1);
                double[] to = route.geometry.get(index);
                CorridorSegmentKey key = corridorSegmentKey(from, to);
                if (key == null) continue;
                CorridorLineAcc item = aggregated.computeIfAbsent(key, ignored -> new CorridorLineAcc(from, to));
                item.lines.add(route.lineId);
            }
        }
        Map<CorridorSegmentKey, CorridorBaseSegment> segments = new LinkedHashMap<>(aggregated.size());
        aggregated.forEach((key, item) -> segments.put(key,
                new CorridorBaseSegment(item.from, item.to, item.lines.size())));
        return new CorridorNetwork(
                Map.copyOf(segments),
                busLines.size(),
                averageNonLinearCoefficient(routes));
    }

    /**
     * 与仿真线路非直线系数一致：先按线路合并运行方向，再以平均线路里程除以平均
     * 首末点地面直线距离，排除首末接近的环线，最后线路等权平均。
     */
    static Double averageNonLinearCoefficient(List<CorridorRouteGeometry> routes) {
        Map<String, RouteShapeAcc> byLine = new LinkedHashMap<>();
        for (CorridorRouteGeometry route : routes) {
            if (route == null || route.lineId == null || route.lineId.isBlank()
                    || route.geometry == null || route.geometry.size() < 2) continue;
            double length = polylineLengthMeters(route.geometry);
            double direct = geographicDistanceMeters(
                    route.geometry.getFirst(), route.geometry.getLast());
            if (!(length > 0) || !(direct > 0) || direct <= length * 0.1) continue;
            RouteShapeAcc item = byLine.computeIfAbsent(route.lineId, ignored -> new RouteShapeAcc());
            item.routeLengthMeters += length;
            item.directDistanceMeters += direct;
            item.directions += 1;
        }
        double total = 0;
        int lines = 0;
        for (RouteShapeAcc item : byLine.values()) {
            if (item.directions <= 0 || !(item.directDistanceMeters > 0)) continue;
            total += item.routeLengthMeters / item.directDistanceMeters;
            lines += 1;
        }
        return lines > 0 ? total / lines : null;
    }

    /**
     * 线网密度与重复系数使用同一份无向物理段：密度按所选行政区过滤物理长度；
     * 重复系数以各段长度×经过线路数之和除以无向去重长度。
     */
    static CorridorMetricStats corridorMetricStats(CorridorNetwork network, String district) {
        if (network == null || network.segments == null || network.segments.isEmpty()) {
            return new CorridorMetricStats(0, null);
        }
        String scope = district == null || district.isBlank() ? "全市" : district.trim();
        boolean all = "全市".equals(scope);
        List<String> streetDistricts = MatsimPopulationCache.streetDistricts();
        double uniqueLength = 0;
        double weightedLength = 0;
        double scopedLength = 0;
        for (CorridorBaseSegment segment : network.segments.values()) {
            double length = geographicDistanceMeters(segment.from, segment.to);
            if (!(length > 0)) continue;
            uniqueLength += length;
            weightedLength += length * segment.coefficient;
            if (all) {
                scopedLength += length;
                continue;
            }
            double[] from = webMercator(segment.from[0], segment.from[1]);
            double[] to = webMercator(segment.to[0], segment.to[1]);
            int street = MatsimPopulationCache.locateStreet(
                    (from[0] + to[0]) / 2.0, (from[1] + to[1]) / 2.0);
            if (street >= 0 && street < streetDistricts.size()
                    && scope.equals(streetDistricts.get(street))) {
                scopedLength += length;
            }
        }
        return new CorridorMetricStats(
                scopedLength,
                uniqueLength > 0 ? weightedLength / uniqueLength : null);
    }

    private static double polylineLengthMeters(List<double[]> geometry) {
        double total = 0;
        for (int index = 1; index < geometry.size(); index++) {
            total += geographicDistanceMeters(geometry.get(index - 1), geometry.get(index));
        }
        return total;
    }

    private static double geographicDistanceMeters(double[] from, double[] to) {
        if (from == null || to == null || from.length < 2 || to.length < 2) return 0;
        double lat1 = Math.toRadians(from[1]);
        double lat2 = Math.toRadians(to[1]);
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(to[0] - from[0]);
        double sinLat = Math.sin(deltaLat / 2.0);
        double sinLon = Math.sin(deltaLon / 2.0);
        double a = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        return 2.0 * 6_378_137.0 * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    static CorridorSegmentKey corridorSegmentKey(double[] from, double[] to) {
        if (from == null || to == null || from.length < 2 || to.length < 2
                || !Double.isFinite(from[0]) || !Double.isFinite(from[1])
                || !Double.isFinite(to[0]) || !Double.isFinite(to[1])) return null;
        int order = Double.compare(from[0], to[0]);
        if (order == 0) order = Double.compare(from[1], to[1]);
        if (order == 0) return null;
        double[] first = order < 0 ? from : to;
        double[] second = order < 0 ? to : from;
        return new CorridorSegmentKey(
                Double.doubleToLongBits(first[0]), Double.doubleToLongBits(first[1]),
                Double.doubleToLongBits(second[0]), Double.doubleToLongBits(second[1]));
    }

    /** 完整方向级发车序列用于计算高峰/平峰平均发车间隔。 */
    private void loadRouteDepartures(Dataset data, Path file) {
        readCsv(file, row -> {
            RouteAcc route = data.routes.get(text(row, "line_id"));
            if (route == null) return;
            List<Integer> departures = Arrays.stream(text(row, "departures").split(";"))
                    .map(String::trim)
                    .map(RealPassengerFlowServiceImpl::clockSeconds)
                    .filter(value -> value >= 0)
                    .sorted()
                    .toList();
            route.peakHeadwayMin = scheduledHeadway(departures, true, route.scheduledIntervalMin);
            route.offPeakHeadwayMin = scheduledHeadway(departures, false, route.scheduledIntervalMin);
            if (!departures.isEmpty()) {
                route.scheduledFirstTime = departures.getFirst();
                route.scheduledLastTime = departures.getLast();
            }
        });
    }

    private static String featureText(SimpleFeature feature, String field) {
        Object value = feature == null ? null : feature.getAttribute(field);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int clockSeconds(String value) {
        String text = safe(value);
        if (text.isBlank()) return -1;
        String[] parts = text.split(":");
        if (parts.length < 2) return -1;
        try {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            int second = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            if (hour < 0 || hour > 24 || minute < 0 || minute > 59 || second < 0 || second > 59) return -1;
            return hour * 3600 + minute * 60 + second;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static double scheduledHeadway(List<Integer> departures, boolean peak, double fallback) {
        double total = 0;
        int count = 0;
        for (int index = 1; index < departures.size(); index++) {
            int from = departures.get(index - 1);
            int to = departures.get(index);
            double minutes = (to - from) / 60.0;
            if (minutes <= 0 || minutes > 120) continue;
            int midpointHour = ((from + to) / 2 / 3600) % 24;
            boolean isPeak = TransitMetrics.isPeakHour(midpointHour);
            if (isPeak != peak) continue;
            total += minutes;
            count++;
        }
        return count > 0 ? round(total / count, 2) : (Double.isFinite(fallback) && fallback > 0 ? round(fallback, 2) : 0);
    }

    /** 日期模式回放所选服务日；平均值模式用首个服务日作为代表日。文件已按日期排序。 */
    private void loadVehicleEvents(Dataset data, Path file) {
        if (!Files.isRegularFile(file)) return;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) return;
            List<String> headers = parseCsv(headerLine);
            if (!headers.isEmpty()) headers.set(0, headers.getFirst().replace("\uFEFF", ""));
            Map<String, Integer> indexes = new HashMap<>();
            for (int index = 0; index < headers.size(); index++) indexes.put(headers.get(index), index);
            String selectedDate = "";
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> values = parseCsv(line);
                String serviceDate = csvValue(values, indexes, "service_date");
                if (selectedDate.isBlank()) selectedDate = data.selectedDate.isBlank() ? serviceDate : data.selectedDate;
                int order = serviceDate.compareTo(selectedDate);
                if (order < 0) continue;
                if (order > 0) {
                    if (!data.vehicleEvents.isEmpty()) break;
                    continue;
                }
                int seconds = dateTimeSeconds(csvValue(values, indexes, "arrival_time"));
                double lon = parseNumber(csvValue(values, indexes, "lon"));
                double lat = parseNumber(csvValue(values, indexes, "lat"));
                if (seconds < 0 || !Double.isFinite(lon) || !Double.isFinite(lat) || lon == 0 || lat == 0) continue;
                double speed = parseNumber(csvValue(values, indexes, "avg_speed_kmh"));
                String plateNumber = csvValue(values, indexes, "plate_number");
                String routeId = csvValue(values, indexes, "authority_line_id");
                if (!plateNumber.isBlank()) {
                    data.vehicleIds.add(plateNumber);
                    RouteAcc route = data.routes.get(routeId);
                    if (route != null) route.vehicleIds.add(plateNumber);
                }
                data.vehicleEvents.add(List.of(
                        seconds,
                        plateNumber,
                        routeId,
                        lon,
                        lat,
                        Double.isFinite(speed) ? speed : 0,
                        csvValue(values, indexes, "stop_name")
                ));
            }
            data.vehicleServiceDate = selectedDate;
            data.vehicleEvents.sort(Comparator.comparingInt(row -> ((Number) row.getFirst()).intValue()));
        } catch (IOException error) {
            log.warn("读取真实车辆回放明细失败: {}", file, error);
        }
    }

    private Map<String, Object> routePanel(Dataset data, RouteAcc route, boolean summary) {
        Map<String, Object> panel = new LinkedHashMap<>();
        panel.put("_summary", summary);
        panel.put("routeKey", route.routeKey());
        panel.put("lineId", route.groupId());
        panel.put("lineName", route.baseName());
        panel.put("routeId", route.authorityId);
        panel.put("routeName", route.routeName);
        panel.put("mode", "bus");
        panel.put("operator", safe(route.company).isBlank() ? "未知企业" : route.company);
        panel.put("desc", route.description(data));
        panel.put("hours", hours());
        panel.put("hourlyFlow", averageList(route.boarding, data.serviceDays));
        panel.put("boardingByHour", averageList(route.boarding, data.serviceDays));
        panel.put("alightingByHour", averageList(route.alighting, data.serviceDays));
        panel.put("capacityByHour", zeros());
        panel.put("dailyFlow", dailyFlow(route.dailyBoarding));
        panel.put("segments", segmentPanels(data, route.segments.values()));
        panel.put("metrics", routeMetrics(data, route));
        if (!summary) {
            panel.put("stationFlows", stationFlowPanels(data, route));
            panel.put("stationOd", routeOdPanels(data, route));
            panel.put("transfers", transferPanels(data, route));
            panel.put("demographics", demographics(route.demographics, data.serviceDays));
        }
        return panel;
    }

    private Map<String, Object> groupPanel(Dataset data, LineGroup group, boolean summary) {
        Map<String, Object> panel = new LinkedHashMap<>();
        panel.put("_summary", summary);
        panel.put("routeKey", group.key());
        panel.put("routeId", group.key());
        panel.put("lineId", group.groupId);
        panel.put("lineName", group.lineName);
        panel.put("routeName", group.lineName);
        panel.put("mode", "bus");
        panel.put("operator", group.operator());
        panel.put("lineGroup", true);
        panel.put("routeKeys", group.routes.stream().map(RouteAcc::routeKey).toList());
        double[] boarding = sumArrays(group.routes.stream().map(route -> route.boarding).toList());
        double[] alighting = sumArrays(group.routes.stream().map(route -> route.alighting).toList());
        panel.put("hours", hours());
        panel.put("hourlyFlow", averageList(boarding, data.serviceDays));
        panel.put("boardingByHour", averageList(boarding, data.serviceDays));
        panel.put("alightingByHour", averageList(alighting, data.serviceDays));
        panel.put("dailyFlow", dailyFlow(mergeDailyFlow(group.routes)));
        panel.put("segments", segmentPanels(data, group.routes.stream().flatMap(route -> route.segments.values().stream()).toList()));
        panel.put("metrics", groupMetrics(data, group));
        if (!summary) {
            Map<String, Double> demo = new HashMap<>();
            group.routes.forEach(route -> route.demographics.forEach((key, value) -> demo.merge(key, value, Double::sum)));
            panel.put("demographics", demographics(demo, data.serviceDays));
            panel.put("transfers", mergedTransferPanels(data, group.routes));
        }
        return panel;
    }

    private Map<String, Object> stationPanel(Dataset data, StationAcc station, boolean summary) {
        Map<String, Object> panel = new LinkedHashMap<>();
        panel.put("_summary", summary);
        panel.put("stationName", station.name);
        panel.put("facilityIds", station.ids);
        panel.put("mode", "bus");
        panel.put("desc", station.routes.size() + " 条线路经过");
        panel.put("hours", hours());
        panel.put("hourlyFlow", averageList(sumArrays(List.of(station.boarding, station.alighting)), data.serviceDays));
        panel.put("boardingByHour", averageList(station.boarding, data.serviceDays));
        panel.put("alightingByHour", averageList(station.alighting, data.serviceDays));
        panel.put("routes", station.routes.values().stream().map(this::stationRouteInfo).toList());
        panel.put("metrics", stationMetrics(data, station));
        if (!summary) {
            panel.put("facilityPanels", stationFacilityPanels(data, station));
            panel.put("od", stationOdPanels(data, station));
            panel.put("reachability", Map.of(
                    "direct", 0, "transfer1", 0, "transfer2", 0,
                    "directStations", List.of(), "transfer1Stations", List.of(), "transfer2Stations", List.of()));
            panel.put("demographics", demographics(station.demographics, data.serviceDays));
        }
        return panel;
    }

    private Map<String, Object> stationRouteInfo(RouteAcc route) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("lineId", route.groupId());
        item.put("lineName", route.baseName());
        item.put("routeId", route.authorityId);
        item.put("routeName", route.routeName);
        item.put("desc", route.routeName);
        item.put("mode", "bus");
        item.put("operator", safe(route.company).isBlank() ? "未知企业" : route.company);
        return item;
    }

    private Map<String, Object> stationFacilityPanels(Dataset data, StationAcc station) {
        Map<String, Object> panels = new LinkedHashMap<>();
        for (StationSideAcc side : station.sides.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("facilityId", side.id);
            item.put("facilityIds", List.of(side.id));
            item.put("stationName", station.name);
            item.put("mode", "bus");
            item.put("hours", hours());
            item.put("hourlyFlow", averageList(sumArrays(List.of(side.boarding, side.alighting)), data.serviceDays));
            item.put("boardingByHour", averageList(side.boarding, data.serviceDays));
            item.put("alightingByHour", averageList(side.alighting, data.serviceDays));
            item.put("routes", side.routes.values().stream().map(this::stationRouteInfo).toList());
            item.put("metrics", stationMetrics(data, side.boarding, side.alighting, side.routes.size(), 1));
            panels.put(side.id, item);
        }
        return panels;
    }

    private List<Map<String, Object>> stationFlowPanels(Dataset data, RouteAcc route) {
        List<StationFlow> values = new ArrayList<>(route.stationFlows.values());
        Map<String, Integer> seq = new HashMap<>();
        for (StopMeta stop : data.stopsByRoute.getOrDefault(route.authorityId, List.of())) seq.put(stop.id, stop.seq);
        values.sort(Comparator.comparingInt(item -> seq.getOrDefault(item.id, Integer.MAX_VALUE)));
        return values.stream().map(flow -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("facilityId", flow.id);
            item.put("facilityName", flow.name);
            item.put("boardingByHour", averageList(flow.boarding, data.serviceDays));
            item.put("alightingByHour", averageList(flow.alighting, data.serviceDays));
            return item;
        }).toList();
    }

    private List<Map<String, Object>> segmentPanels(Dataset data, Collection<SegmentAcc> segments) {
        return segments.stream().sorted(Comparator.comparingInt(item -> item.seq)).map(segment -> {
            List<Integer> hourly = averageList(segment.flow, data.serviceDays);
            int total = hourly.stream().mapToInt(Integer::intValue).sum();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", segment.fromName + " - " + segment.toName);
            item.put("fromFacilityId", segment.fromId);
            item.put("fromName", segment.fromName);
            item.put("toFacilityId", segment.toId);
            item.put("toName", segment.toName);
            item.put("stationNames", List.of(segment.fromName, segment.toName));
            item.put("flowByHour", hourly);
            item.put("totalFlow", total);
            item.put("flow", total);
            item.put("loadRateByHour", zeros());
            item.put("peakLoadRate", 0);
            item.put("distanceMeters", round(segment.distanceMeters, 1));
            item.put("runTimeMinutes", round(segment.runTimeMinutes, 2));
            return item;
        }).toList();
    }

    private List<Map<String, Object>> routeOdPanels(Dataset data, RouteAcc route) {
        return route.ods.values().stream().sorted(Comparator.comparingDouble((OdAcc item) -> item.count).reversed()).map(od -> {
            StopMeta from = data.stopsById.get(od.fromId);
            StopMeta to = data.stopsById.get(od.toId);
            List<Integer> hourly = distribute(od.count / data.serviceDays, route.stationFlows.get(od.fromId));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("fromFacilityId", od.fromId);
            item.put("fromName", od.fromName);
            putCoord(item, "from", from);
            item.put("toFacilityId", od.toId);
            item.put("toName", od.toName);
            putCoord(item, "to", to);
            item.put("flowByHour", hourly);
            item.put("flow", hourly.stream().mapToInt(Integer::intValue).sum());
            return item;
        }).toList();
    }

    private List<Map<String, Object>> stationOdPanels(Dataset data, StationAcc station) {
        double total = station.ods.values().stream().mapToDouble(item -> item.count).sum();
        return station.ods.values().stream().sorted(Comparator.comparingDouble((StationOdAcc item) -> item.count).reversed()).map(od -> {
            StopMeta counterpart = data.stopsById.get(od.counterpartId);
            List<Integer> hourly = distribute(od.count / data.serviceDays, od.direction.equals("out")
                    ? new StationFlow("", "", station.boarding, new double[HOURS])
                    : new StationFlow("", "", station.alighting, new double[HOURS]));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("origin", od.direction.equals("out") ? station.name : od.counterpartName);
            item.put("destination", od.direction.equals("out") ? od.counterpartName : station.name);
            item.put("counterpart", od.counterpartName);
            item.put("routeId", od.route.authorityId);
            item.put("lineId", od.route.groupId());
            item.put("lineName", od.route.baseName());
            item.put("routeName", od.route.routeName);
            item.put("direction", od.direction);
            item.put("flowByHour", hourly);
            item.put("flow", hourly.stream().mapToInt(Integer::intValue).sum());
            item.put("ratio", total > 0 ? round(od.count * 100 / total, 2) : 0);
            if (counterpart != null) {
                double[] point = webMercator(counterpart.lon, counterpart.lat);
                if (od.direction.equals("out")) {
                    item.put("destinationX", point[0]);
                    item.put("destinationY", point[1]);
                } else {
                    item.put("originX", point[0]);
                    item.put("originY", point[1]);
                }
            }
            return item;
        }).toList();
    }

    private List<Map<String, Object>> transferPanels(Dataset data, RouteAcc route) {
        double total = route.transfers.values().stream().mapToDouble(values -> Arrays.stream(values).sum()).sum();
        return route.transfers.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<String, double[]> item) -> Arrays.stream(item.getValue()).sum()).reversed())
                .map(entry -> transferPanel(data, entry.getKey(), entry.getValue(), total)).toList();
    }

    private List<Map<String, Object>> mergedTransferPanels(Dataset data, List<RouteAcc> routes) {
        Map<String, double[]> merged = new HashMap<>();
        routes.forEach(route -> route.transfers.forEach((key, values) -> {
            double[] target = merged.computeIfAbsent(key, ignored -> new double[HOURS]);
            for (int hour = 0; hour < HOURS; hour++) target[hour] += values[hour];
        }));
        double total = merged.values().stream().mapToDouble(values -> Arrays.stream(values).sum()).sum();
        return merged.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<String, double[]> item) -> Arrays.stream(item.getValue()).sum()).reversed())
                .map(entry -> transferPanel(data, entry.getKey(), entry.getValue(), total)).toList();
    }

    private Map<String, Object> transferPanel(Dataset data, String targetId, double[] values, double total) {
        RouteAcc target = data.routes.get(targetId);
        List<Integer> hourly = averageList(values, data.serviceDays);
        int flow = hourly.stream().mapToInt(Integer::intValue).sum();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("lineId", target == null ? targetId : target.groupId());
        item.put("routeId", targetId);
        item.put("lineName", target == null ? targetId : target.baseName());
        item.put("routeName", target == null ? targetId : target.routeName);
        item.put("flowByHour", hourly);
        item.put("flow", flow);
        item.put("ratio", total > 0 ? round(Arrays.stream(values).sum() * 100 / total, 2) : 0);
        return item;
    }

    private Map<String, Object> routeMetrics(Dataset data, RouteAcc route) {
        double passenger = route.totalBoarding() / data.serviceDays;
        double vehicles = route.vehicleIds.isEmpty()
                ? route.vehicles / data.serviceDays : route.vehicleIds.size();
        double departures = route.departures / data.serviceDays;
        double operatedKm = route.mileageKm / data.serviceDays;
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("routeDist", round(route.routeDistanceMeters, 1));
        metrics.put("firstTime", route.scheduledFirstTime >= 0 ? route.scheduledFirstTime : route.firstTime == Integer.MAX_VALUE ? 0 : route.firstTime);
        metrics.put("lastTime", route.scheduledLastTime >= 0 ? route.scheduledLastTime : route.lastTime);
        metrics.put("facNum", data.stopsByRoute.getOrDefault(route.authorityId, List.of()).size());
        metrics.put("facDist", route.stopDistance(data));
        metrics.put("lc", 0);
        metrics.put("passenger", Math.round(passenger));
        metrics.put("loadRate", null);
        // 真实数据当前没有“方向×小时×断面”的投入运力，不能用全天峰值冒充平均高峰满载率。
        metrics.put("peakAverageLoadRate", null);
        metrics.put("peakPassengerOnSegments", null);
        metrics.put("peakCapacityOnSegments", null);
        metrics.put("passengerStrength", operatedKm > 0 ? round(passenger / operatedKm, 3) : 0);
        metrics.put("operatingVehicleKm", round(operatedKm, 2));
        metrics.put("departures", round(departures, 2));
        metrics.put("vehicles", round(vehicles, 2));
        metrics.put("vehicleIds", new ArrayList<>(route.vehicleIds));
        metrics.put("perTripFlow", departures > 0 ? round(passenger / departures, 2) : 0);
        metrics.put("perVehicleFlow", vehicles > 0 ? round(passenger / vehicles, 2) : 0);
        metrics.put("peakHeadwayMin", route.peakHeadwayMin);
        metrics.put("offPeakHeadwayMin", route.offPeakHeadwayMin);
        metrics.put("operatedKm", round(operatedKm, 2));
        metrics.put("company", safe(route.company).isBlank() ? "未知企业" : route.company);
        metrics.put("avgSpeedKmh", route.runTimeMinutes > 0 ? round(route.speedWeighted / route.runTimeMinutes, 2) : 0);
        return metrics;
    }

    private Map<String, Object> groupMetrics(Dataset data, LineGroup group) {
        double passenger = group.routes.stream().mapToDouble(RouteAcc::totalBoarding).sum() / data.serviceDays;
        Set<String> vehicleIds = new LinkedHashSet<>();
        double fallbackVehicles = 0;
        for (RouteAcc route : group.routes) {
            if (route.vehicleIds.isEmpty()) fallbackVehicles += route.vehicles / data.serviceDays;
            else vehicleIds.addAll(route.vehicleIds);
        }
        double vehicles = vehicleIds.size() + fallbackVehicles;
        double totalDepartures = group.routes.stream().mapToDouble(route -> route.departures).sum();
        double departures = totalDepartures / data.serviceDays;
        double operatedKm = group.routes.stream().mapToDouble(route -> route.mileageKm).sum() / data.serviceDays;
        // 与仿真 lineGroup 同口径：多个运营路径按实际发车班次加权，避免最长路径或路径长度简单相加
        // 与整条线路总客流的统计范围不一致。
        double weightedRouteDist = group.routes.stream()
                .mapToDouble(route -> route.routeDistanceMeters * route.departures)
                .sum();
        double routeDist = totalDepartures > 0 ? weightedRouteDist / totalDepartures : 0.0;
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("routeDist", round(routeDist, 1));
        metrics.put("passenger", Math.round(passenger));
        metrics.put("passengerStrength", operatedKm > 0 ? round(passenger / operatedKm, 3) : 0);
        metrics.put("operatingVehicleKm", round(operatedKm, 2));
        metrics.put("peakAverageLoadRate", null);
        metrics.put("peakPassengerOnSegments", null);
        metrics.put("peakCapacityOnSegments", null);
        metrics.put("departures", round(departures, 2));
        metrics.put("vehicles", round(vehicles, 2));
        metrics.put("vehicleIds", new ArrayList<>(vehicleIds));
        metrics.put("perTripFlow", departures > 0 ? round(passenger / departures, 2) : 0);
        metrics.put("perVehicleFlow", vehicles > 0 ? round(passenger / vehicles, 2) : 0);
        return metrics;
    }

    private Map<String, Object> stationMetrics(Dataset data, StationAcc station) {
        return stationMetrics(data, station.boarding, station.alighting, station.routes.size(), station.ids.size());
    }

    private Map<String, Object> stationMetrics(Dataset data, double[] boarding, double[] alighting, int routeCount, int facilityCount) {
        List<Integer> hourly = averageList(sumArrays(List.of(boarding, alighting)), data.serviceDays);
        return Map.of(
                "passenger", hourly.stream().mapToInt(Integer::intValue).sum(),
                "boarding", average(Arrays.stream(boarding).sum(), data.serviceDays),
                "alighting", average(Arrays.stream(alighting).sum(), data.serviceDays),
                "peakFlow", hourly.stream().mapToInt(Integer::intValue).max().orElse(0),
                "population", 0,
                "transferScore", 0,
                "routeCount", routeCount,
                "facilityCount", facilityCount
        );
    }

    private Map<String, Object> demographics(Map<String, Double> counts, int days) {
        double total = counts.values().stream().mapToDouble(Double::doubleValue).sum() / days;
        double student = counts.getOrDefault("student", 0.0) / days;
        double elderly = counts.getOrDefault("elderly", 0.0) / days;
        double concession = counts.getOrDefault("disability_or_concession", 0.0) / days;
        double general = counts.getOrDefault("general_or_unknown", 0.0) / days;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("riderCount", Math.round(total));
        result.put("student", total > 0 ? round(student * 100 / total, 2) : 0);
        result.put("elderly", total > 0 ? round(elderly * 100 / total, 2) : 0);
        result.put("disabilityOrConcession", total > 0 ? round(concession * 100 / total, 2) : 0);
        result.put("generalOrUnknown", total > 0 ? round(general * 100 / total, 2) : 0);
        result.put("passengerGroups", List.of(
                passengerGroup("student", "学生票卡", student, total),
                passengerGroup("elderly", "老年票卡", elderly, total),
                passengerGroup("disability_or_concession", "优抚/残疾票卡", concession, total),
                passengerGroup("general_or_unknown", "一般/未知票卡", general, total)
        ));
        result.put("activitySource", "card-type-only");
        result.put("source", "real-card-type");
        result.put("profileSource", "刷卡记录 CARD_TYPE（票卡类型）");
        result.put("profileNote", "按上车刷卡人次归类，不代表唯一乘客，也不推断性别、精确年龄、职业或出行目的");
        return result;
    }

    private Map<String, Object> passengerGroup(String key, String label, double count, double total) {
        return Map.of(
                "key", key,
                "label", label,
                "count", Math.round(count),
                "ratio", total > 0 ? round(count * 100 / total, 2) : 0
        );
    }

    private Map<String, Object> operationSummary(Dataset data) {
        Set<String> vehicleIds = new LinkedHashSet<>();
        double fallbackVehicles = 0;
        for (RouteAcc route : data.routes.values()) {
            if (route.vehicleIds.isEmpty()) fallbackVehicles += route.vehicles / data.serviceDays;
            else vehicleIds.addAll(route.vehicleIds);
        }
        double vehicles = vehicleIds.size() + fallbackVehicles;
        double departures = data.routes.values().stream().mapToDouble(route -> route.departures).sum() / data.serviceDays;
        double operatedKm = data.routes.values().stream().mapToDouble(route -> route.mileageKm).sum() / data.serviceDays;
        return Map.of("vehicles", round(vehicles, 2), "departures", round(departures, 2), "operatedKm", round(operatedKm, 2));
    }

    private List<Map<String, Object>> operatorOperations(Dataset data) {
        Map<String, OperatorAcc> operators = new LinkedHashMap<>();
        for (RouteAcc route : data.routes.values()) {
            String name = safe(route.company).isBlank() ? "未知企业" : route.company;
            OperatorAcc item = operators.computeIfAbsent(name, OperatorAcc::new);
            item.passenger += route.totalBoarding();
            if (route.vehicleIds.isEmpty()) item.fallbackVehicles += route.vehicles;
            else item.vehicleIds.addAll(route.vehicleIds);
            item.departures += route.departures;
            item.operatedKm += route.mileageKm;
        }
        return operators.values().stream()
                .map(item -> item.toMap(data.serviceDays))
                .sorted(Comparator.comparingDouble((Map<String, Object> item) -> ((Number) item.get("passenger")).doubleValue()).reversed())
                .toList();
    }

    /** 与仿真 TransitMetrics 同口径的三项公交运营效率公式。 */
    static OperationRatios operationRatios(double dailyPassenger, double dailyVehicles,
                                            double dailyDepartures, double dailyOperatedKm) {
        return new OperationRatios(
                dailyVehicles > 0 ? dailyPassenger / dailyVehicles : 0,
                dailyDepartures > 0 ? dailyPassenger / dailyDepartures : 0,
                dailyOperatedKm > 0 ? dailyPassenger / dailyOperatedKm : 0
        );
    }

    record OperationRatios(double perVehicle, double perTrip, double intensity) { }

    private static List<Map<String, Object>> dailyFlow(Map<String, Double> values) {
        return values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.<String, Object>of("date", entry.getKey(), "flow", Math.round(entry.getValue())))
                .toList();
    }

    private static Map<String, Double> mergeDailyFlow(List<RouteAcc> routes) {
        Map<String, Double> values = new LinkedHashMap<>();
        routes.forEach(route -> route.dailyBoarding.forEach((date, count) -> values.merge(date, count, Double::sum)));
        return values;
    }

    private Map<String, Object> routeLeaderboard(Dataset data) {
        List<Map<String, Object>> bus = data.lineGroups.values().stream()
                .sorted(Comparator.comparingDouble((LineGroup group) -> group.routes.stream().mapToDouble(RouteAcc::totalBoarding).sum()).reversed())
                .limit(50)
                .map(group -> Map.<String, Object>of(
                        "lineId", group.groupId,
                        "lineName", group.lineName,
                        "passengerFlow", average(group.routes.stream().mapToDouble(RouteAcc::totalBoarding).sum(), data.serviceDays),
                        "desc", group.routes.isEmpty() ? "" : group.routes.getFirst().routeName))
                .toList();
        return Map.of("leaderboard", Map.of("bus", bus, "subway", List.of()));
    }

    private StationAcc findStation(Dataset data, String stationName, String facilityId) {
        String id = safe(facilityId);
        if (!id.isBlank() && data.stationById.containsKey(id)) return data.stationById.get(id);
        String name = safe(stationName);
        if (data.stations.containsKey(name)) return data.stations.get(name);
        String normalized = normalizeStation(name);
        return data.stations.values().stream().filter(item -> normalizeStation(item.name).equals(normalized)).findFirst().orElse(null);
    }

    private Path passengerRoot(String area) {
        return matsimConfig.realDataPath(area).resolve(PASSENGER_FOLDER);
    }

    private Path transitRoot(String area) {
        return matsimConfig.realDataPath(area).resolve(TRANSIT_FOLDER);
    }

    private String signature(Path root, Path transitRoot) {
        StringBuilder value = new StringBuilder();
        for (String file : List.of("总体小时客流.csv", "线路小时客流.csv", "站点小时客流.csv", "断面小时客流.csv",
                "线路OD日统计.csv", "客群小时统计.csv", "换乘明细.csv", "线路日运营统计.csv", "区间运行时间统计.csv",
                "车辆到离站明细.csv")) {
            appendSignature(value, root.resolve(file));
        }
        appendSignature(value, transitRoot.resolve(STOP_SEQUENCE));
        appendSignature(value, transitRoot.resolve(ROUTE_SHP));
        appendSignature(value, transitRoot.resolve("线路/routes.dbf"));
        appendSignature(value, transitRoot.resolve(ROUTE_DEPARTURES));
        return Integer.toHexString(value.toString().hashCode()) + ":" + value;
    }

    private void appendSignature(StringBuilder value, Path file) {
        try {
            BasicFileAttributes attr = Files.readAttributes(file, BasicFileAttributes.class);
            value.append(file.getFileName()).append(':').append(attr.size()).append(':').append(attr.lastModifiedTime().toMillis()).append('|');
        } catch (IOException error) {
            value.append(file.getFileName()).append(":missing|");
        }
    }

    private void readCsv(Path file, Consumer<Map<String, String>> consumer) {
        if (!Files.isRegularFile(file)) return;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) return;
            List<String> headers = parseCsv(headerLine);
            if (!headers.isEmpty()) headers.set(0, headers.getFirst().replace("\uFEFF", ""));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> values = parseCsv(line);
                Map<String, String> row = new HashMap<>(headers.size() * 2);
                for (int index = 0; index < headers.size(); index++) {
                    row.put(headers.get(index), index < values.size() ? values.get(index) : "");
                }
                consumer.accept(row);
            }
        } catch (IOException error) {
            throw new BusinessException("读取真实客流文件失败: " + file.getFileName());
        }
    }

    static List<String> parseCsv(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    value.append('"');
                    index += 1;
                } else quoted = !quoted;
            } else if (current == ',' && !quoted) {
                values.add(value.toString());
                value.setLength(0);
            } else value.append(current);
        }
        values.add(value.toString());
        return values;
    }

    private static String text(Map<String, String> row, String key) {
        return safe(row.get(key));
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeArea(String areaName) {
        return safe(areaName).isBlank() ? DEFAULT_AREA : safe(areaName);
    }

    private static double number(Map<String, String> row, String key) {
        try { return Double.parseDouble(text(row, key)); } catch (RuntimeException ignored) { return 0; }
    }

    private static double parseNumber(String value) {
        try { return Double.parseDouble(safe(value)); } catch (RuntimeException ignored) { return Double.NaN; }
    }

    private static String csvValue(List<String> values, Map<String, Integer> indexes, String key) {
        Integer index = indexes.get(key);
        return index == null || index < 0 || index >= values.size() ? "" : safe(values.get(index));
    }

    private static int integer(Map<String, String> row, String key) {
        return (int) Math.round(number(row, key));
    }

    private static int hour(Map<String, String> row) {
        int value = integer(row, "hour");
        return value >= 0 && value < HOURS ? value : -1;
    }

    private static int dateTimeHour(String value) {
        try { return LocalDateTime.parse(value, DATE_TIME).getHour(); } catch (RuntimeException ignored) { return 0; }
    }

    private static int dateTimeSeconds(String value) {
        try {
            LocalTime time = LocalDateTime.parse(value, DATE_TIME).toLocalTime();
            return time.toSecondOfDay();
        } catch (RuntimeException ignored) { return -1; }
    }

    private static String baseLineName(String value) {
        String text = safe(value);
        return text.replaceFirst("[（(][^（）()]*[）)]\\s*$", "").trim();
    }

    private static String lineGroupId(String routeName) {
        return "real-line::" + baseLineName(routeName);
    }

    private static String normalizeStation(String value) {
        return safe(value).replaceFirst("(公交)?(总站|站)$", "").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static List<Integer> hours() {
        List<Integer> values = new ArrayList<>(HOURS);
        for (int hour = 0; hour < HOURS; hour++) values.add(hour);
        return values;
    }

    private static List<Integer> zeros() {
        return new ArrayList<>(java.util.Collections.nCopies(HOURS, 0));
    }

    private static List<Integer> averageList(double[] values, int days) {
        List<Integer> result = new ArrayList<>(HOURS);
        for (int hour = 0; hour < HOURS; hour++) result.add((int) Math.round(values[hour] / Math.max(1, days)));
        return result;
    }

    private static int average(double value, int days) {
        return (int) Math.round(value / Math.max(1, days));
    }

    private static double[] sumArrays(List<double[]> values) {
        double[] result = new double[HOURS];
        for (double[] source : values) for (int hour = 0; hour < HOURS; hour++) result[hour] += source[hour];
        return result;
    }

    private static List<Integer> distribute(double total, StationFlow basis) {
        List<Integer> result = zeros();
        if (total <= 0) return result;
        double[] weights = basis == null ? new double[HOURS] : basis.boarding;
        double weightTotal = Arrays.stream(weights).sum();
        if (weightTotal <= 0) {
            result.set(8, (int) Math.round(total));
            return result;
        }
        int target = (int) Math.round(total);
        int assigned = 0;
        for (int hour = 0; hour < HOURS; hour++) {
            int value = (int) Math.round(total * weights[hour] / weightTotal);
            result.set(hour, value);
            assigned += value;
        }
        if (assigned != target) {
            int peak = 0;
            for (int hour = 1; hour < HOURS; hour++) if (weights[hour] > weights[peak]) peak = hour;
            result.set(peak, Math.max(0, result.get(peak) + target - assigned));
        }
        return result;
    }

    private static double round(double value, int digits) {
        if (!Double.isFinite(value)) return 0;
        double scale = Math.pow(10, digits);
        return Math.round(value * scale) / scale;
    }

    private static double[] webMercator(double lon, double lat) {
        double x = lon * 20037508.34 / 180.0;
        double boundedLat = Math.max(-85.05112878, Math.min(85.05112878, lat));
        double y = Math.log(Math.tan((90.0 + boundedLat) * Math.PI / 360.0)) / (Math.PI / 180.0);
        y *= 20037508.34 / 180.0;
        return new double[]{x, y};
    }

    private static void putCoord(Map<String, Object> target, String prefix, StopMeta stop) {
        if (stop == null || !Double.isFinite(stop.lon) || !Double.isFinite(stop.lat)) return;
        double[] point = webMercator(stop.lon, stop.lat);
        target.put(prefix + "X", point[0]);
        target.put(prefix + "Y", point[1]);
    }

    private record CachedDataset(String signature, Dataset data) { }

    private static final class Dataset {
        final String area;
        final String selectedDate;
        final Set<String> dates = new LinkedHashSet<>();
        final Map<String, Double> dailyOverall = new LinkedHashMap<>();
        final double[] overall = new double[HOURS];
        final double[] vehicleActive = new double[HOURS];
        final double[] vehicleSpeedSum = new double[HOURS];
        final double[] vehicleSpeedWeight = new double[HOURS];
        final List<List<Object>> vehicleEvents = new ArrayList<>();
        final Set<String> vehicleIds = new LinkedHashSet<>();
        String vehicleServiceDate = "";
        final Map<String, RouteAcc> routes = new LinkedHashMap<>();
        final Map<String, LineGroup> lineGroups = new LinkedHashMap<>();
        final Map<String, StationAcc> stations = new LinkedHashMap<>();
        final Map<String, StationAcc> stationById = new HashMap<>();
        final Map<String, StopMeta> stopsById = new HashMap<>();
        final Map<String, List<StopMeta>> stopsByRoute = new HashMap<>();
        int serviceDays = 1;
        double minLon = Double.POSITIVE_INFINITY;
        double minLat = Double.POSITIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        double centerLon = Double.NaN;
        double centerLat = Double.NaN;

        Dataset(String area, String selectedDate) {
            this.area = area;
            this.selectedDate = safe(selectedDate);
        }

        boolean accepts(String serviceDate) {
            return selectedDate.isBlank() || selectedDate.equals(safe(serviceDate));
        }

        RouteAcc route(String authorityId, String routeName) {
            if (authorityId == null || authorityId.isBlank()) return null;
            RouteAcc route = routes.computeIfAbsent(authorityId, ignored -> new RouteAcc(authorityId, routeName));
            if ((route.routeName == null || route.routeName.isBlank()) && routeName != null) route.routeName = routeName;
            return route;
        }

        StationAcc station(String id, String name) {
            String actualName = safe(name).isBlank() ? id : safe(name);
            StationAcc station = stations.computeIfAbsent(actualName, StationAcc::new);
            if (id != null && !id.isBlank()) {
                station.ids.add(id);
                stationById.put(id, station);
            }
            return station;
        }

        void finish() {
            for (RouteAcc route : routes.values()) {
                String groupId = route.groupId();
                lineGroups.computeIfAbsent(groupId, ignored -> new LineGroup(groupId, route.baseName())).routes.add(route);
                if (route.routeDistanceMeters <= 0) route.routeDistanceMeters = route.geometricDistance(this);
            }
            if (Double.isFinite(minLon)) {
                centerLon = (minLon + maxLon) / 2.0;
                centerLat = (minLat + maxLat) / 2.0;
            }
        }
    }

    private static final class RouteAcc {
        final String authorityId;
        String routeName;
        String company = "";
        final Map<String, Double> dailyBoarding = new LinkedHashMap<>();
        final double[] boarding = new double[HOURS];
        final double[] alighting = new double[HOURS];
        final double[] trips = new double[HOURS];
        final Map<String, StationFlow> stationFlows = new LinkedHashMap<>();
        final Map<String, SegmentAcc> segments = new LinkedHashMap<>();
        final Map<String, OdAcc> ods = new LinkedHashMap<>();
        final Map<String, double[]> transfers = new HashMap<>();
        final Map<String, Double> demographics = new HashMap<>();
        final Set<String> vehicleIds = new LinkedHashSet<>();
        double vehicles;
        double departures;
        double mileageKm;
        double runTimeMinutes;
        double speedWeighted;
        double routeDistanceMeters;
        int firstTime = Integer.MAX_VALUE;
        int lastTime;
        int scheduledFirstTime = -1;
        int scheduledLastTime = -1;
        double scheduledIntervalMin;
        double peakHeadwayMin;
        double offPeakHeadwayMin;
        List<double[]> geometry = List.of();

        RouteAcc(String authorityId, String routeName) { this.authorityId = authorityId; this.routeName = routeName; }
        String baseName() { return baseLineName(routeName); }
        String groupId() { return lineGroupId(routeName); }
        String routeKey() { return groupId() + "::" + authorityId; }
        double totalBoarding() { return Arrays.stream(boarding).sum(); }
        double totalAlighting() { return Arrays.stream(alighting).sum(); }

        String description(Dataset data) {
            List<StopMeta> stops = data.stopsByRoute.getOrDefault(authorityId, List.of());
            return stops.size() > 1 ? stops.getFirst().name + "—" + stops.getLast().name : safe(routeName);
        }

        double geometricDistance(Dataset data) {
            List<StopMeta> stops = data.stopsByRoute.getOrDefault(authorityId, List.of());
            double distance = 0;
            for (int index = 1; index < stops.size(); index++) {
                double[] from = webMercator(stops.get(index - 1).lon, stops.get(index - 1).lat);
                double[] to = webMercator(stops.get(index).lon, stops.get(index).lat);
                distance += Math.hypot(to[0] - from[0], to[1] - from[1]);
            }
            return distance;
        }

        double stopDistance(Dataset data) {
            int count = data.stopsByRoute.getOrDefault(authorityId, List.of()).size();
            return count > 1 ? round(routeDistanceMeters / (count - 1), 1) : 0;
        }

        /** 从权威线路 SHP 中截取两站之间的真实折线路径；端点使用站点坐标确保贴合站位。 */
        List<double[]> pathBetween(StopMeta from, StopMeta to) {
            if (from == null || to == null) return List.of();
            if (geometry.size() < 2) return List.of(
                    new double[]{from.lon, from.lat}, new double[]{to.lon, to.lat});
            int fromIndex = nearestGeometryIndex(from.lon, from.lat);
            int toIndex = nearestGeometryIndex(to.lon, to.lat);
            List<double[]> path = new ArrayList<>();
            path.add(new double[]{from.lon, from.lat});
            if (fromIndex <= toIndex) {
                for (int index = fromIndex; index <= toIndex; index++) path.add(geometry.get(index));
            } else {
                for (int index = fromIndex; index >= toIndex; index--) path.add(geometry.get(index));
            }
            path.add(new double[]{to.lon, to.lat});
            List<double[]> deduplicated = new ArrayList<>(path.size());
            for (double[] point : path) {
                if (point == null || point.length < 2) continue;
                double[] previous = deduplicated.isEmpty() ? null : deduplicated.getLast();
                if (previous == null || Math.abs(previous[0] - point[0]) > 1e-8 || Math.abs(previous[1] - point[1]) > 1e-8) {
                    deduplicated.add(new double[]{point[0], point[1]});
                }
            }
            return deduplicated;
        }

        private int nearestGeometryIndex(double lon, double lat) {
            int best = 0;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (int index = 0; index < geometry.size(); index++) {
                double[] point = geometry.get(index);
                double dx = point[0] - lon;
                double dy = point[1] - lat;
                double distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = index;
                }
            }
            return best;
        }
    }

    private static final class LineGroup {
        final String groupId;
        final String lineName;
        final List<RouteAcc> routes = new ArrayList<>();
        LineGroup(String groupId, String lineName) { this.groupId = groupId; this.lineName = lineName; }
        String key() { return "bus::" + groupId; }
        String operator() {
            return routes.stream().map(route -> safe(route.company)).filter(value -> !value.isBlank()).distinct()
                    .reduce((left, right) -> left + " / " + right).orElse("未知企业");
        }
    }

    private static final class StationAcc {
        final String name;
        final Set<String> ids = new LinkedHashSet<>();
        final double[] boarding = new double[HOURS];
        final double[] alighting = new double[HOURS];
        final Map<String, RouteAcc> routes = new LinkedHashMap<>();
        final Map<String, StationSideAcc> sides = new LinkedHashMap<>();
        final Map<String, StationOdAcc> ods = new HashMap<>();
        final Map<String, Double> demographics = new HashMap<>();
        StationAcc(String name) { this.name = name; }
        double totalFlow() { return Arrays.stream(boarding).sum() + Arrays.stream(alighting).sum(); }

        void addOd(RouteAcc route, String direction, String stationId, String counterpartId, String counterpartName, double count) {
            String key = route.authorityId + "::" + direction + "::" + stationId + "::" + counterpartId;
            ods.computeIfAbsent(key, ignored -> new StationOdAcc(route, direction, stationId, counterpartId, counterpartName)).count += count;
        }
    }

    private static final class StationSideAcc {
        final String id;
        final double[] boarding = new double[HOURS];
        final double[] alighting = new double[HOURS];
        final Map<String, RouteAcc> routes = new LinkedHashMap<>();
        StationSideAcc(String id) { this.id = id; }
    }

    private static final class OperatorAcc {
        final String name;
        double passenger;
        final Set<String> vehicleIds = new LinkedHashSet<>();
        double fallbackVehicles;
        double departures;
        double operatedKm;
        OperatorAcc(String name) { this.name = name; }
        Map<String, Object> toMap(int days) {
            double divisor = Math.max(1, days);
            double dailyPassenger = passenger / divisor;
            double dailyVehicles = vehicleIds.size() + fallbackVehicles / divisor;
            double dailyDepartures = departures / divisor;
            double dailyKm = operatedKm / divisor;
            OperationRatios ratios = operationRatios(
                    dailyPassenger, dailyVehicles, dailyDepartures, dailyKm);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name);
            result.put("passenger", Math.round(dailyPassenger));
            result.put("vehicles", round(dailyVehicles, 2));
            result.put("departures", round(dailyDepartures, 2));
            result.put("operatedKm", round(dailyKm, 2));
            result.put("perVehicle", round(ratios.perVehicle(), 2));
            result.put("perTrip", round(ratios.perTrip(), 2));
            result.put("intensity", round(ratios.intensity(), 2));
            return result;
        }
    }

    private static final class StationFlow {
        final String id;
        final String name;
        final double[] boarding;
        final double[] alighting;
        StationFlow(String id, String name) { this(id, name, new double[HOURS], new double[HOURS]); }
        StationFlow(String id, String name, double[] boarding, double[] alighting) {
            this.id = id; this.name = name; this.boarding = boarding; this.alighting = alighting;
        }
    }

    private static final class SegmentAcc {
        final String fromId;
        final String fromName;
        final String toId;
        final String toName;
        final int seq;
        final double[] flow = new double[HOURS];
        double distanceMeters;
        double runTimeMinutes;
        SegmentAcc(String fromId, String fromName, String toId, String toName, int seq) {
            this.fromId = fromId; this.fromName = fromName; this.toId = toId; this.toName = toName; this.seq = seq;
        }
    }

    private static final class OdAcc {
        final String fromId;
        final String fromName;
        final String toId;
        final String toName;
        double count;
        OdAcc(String fromId, String fromName, String toId, String toName) {
            this.fromId = fromId; this.fromName = fromName; this.toId = toId; this.toName = toName;
        }
    }

    private static final class StationOdAcc {
        final RouteAcc route;
        final String direction;
        final String stationId;
        final String counterpartId;
        final String counterpartName;
        double count;
        StationOdAcc(RouteAcc route, String direction, String stationId, String counterpartId, String counterpartName) {
            this.route = route; this.direction = direction; this.stationId = stationId;
            this.counterpartId = counterpartId; this.counterpartName = counterpartName;
        }
    }

    private record StopMeta(String id, String name, int seq, double lon, double lat) { }

    private static final class SpatialCell {
        final int i;
        final int j;
        final String name;
        double origin;
        double destination;
        SpatialCell(int i, int j, String name) { this.i = i; this.j = j; this.name = name; }
        String key() { return i + ":" + j; }
    }

    static final class CorridorAcc {
        final double[] from;
        final double[] to;
        String name;
        double flow;
        CorridorAcc(double[] from, double[] to) {
            this.from = new double[]{from[0], from[1]};
            this.to = new double[]{to[0], to[1]};
        }
    }

    static final class CorridorLineAcc {
        final double[] from;
        final double[] to;
        final Set<String> lines = new LinkedHashSet<>();
        CorridorLineAcc(double[] from, double[] to) {
            this.from = new double[]{from[0], from[1]};
            this.to = new double[]{to[0], to[1]};
        }
    }

    static record CorridorSegmentKey(long x1, long y1, long x2, long y2) { }
    static record CorridorRouteGeometry(String lineId, List<double[]> geometry) { }
    static record CorridorBaseSegment(double[] from, double[] to, int coefficient) { }
    static record CorridorNetwork(Map<CorridorSegmentKey, CorridorBaseSegment> segments,
                                  int busLines,
                                  Double averageNonLinearCoefficient) {
        CorridorNetwork(Map<CorridorSegmentKey, CorridorBaseSegment> segments, int busLines) {
            this(segments, busLines, null);
        }
    }
    static record CorridorMetricStats(double scopedNetworkLengthMeters,
                                      Double repetitionCoefficient) { }

    private static final class RouteShapeAcc {
        double routeLengthMeters;
        double directDistanceMeters;
        int directions;
    }
    private record CachedCorridorNetwork(String signature, CorridorNetwork network) { }
}
