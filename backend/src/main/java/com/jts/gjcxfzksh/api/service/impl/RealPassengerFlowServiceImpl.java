package com.jts.gjcxfzksh.api.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jts.gjcxfzksh.exception.BusinessException;
import com.jts.gjcxfzksh.api.model.params.RealPassengerFlowParam;
import com.jts.gjcxfzksh.api.service.RealPassengerFlowService;
import com.jts.gjcxfzksh.config.MatsimConfig;
import com.jts.gjcxfzksh.data.cache.MatsimPopulationCache;
import com.jts.gjcxfzksh.data.cache.RealPopulationCache;
import com.jts.gjcxfzksh.data.cache.BackendMemoryCache;
import com.jts.gjcxfzksh.utils.TransitMetrics;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.log4j.Log4j2;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.locationtech.jts.geom.Geometry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.function.BiConsumer;
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
    private static final String ACTUAL_DEPARTURES = RealPassengerAggregateBuilder.DEPARTURE_OUTPUT;
    private static final String UNLOCATED_LINE_GROUP_FLOW = "线路组未定位小时客流.csv";
    private static final String REAL_EVALUATION_FORMULA_VERSION = "evaluation-v14-approved-c-load-num";
    private static final String PANEL_ARTIFACT_VERSION = "panel-bundle-v5-approved-c-load-num";
    private static final String PANEL_ARTIFACT_FILE = "panel-bundle.json.gz";
    private static final String GPS_FOLDER = "GPS数据";
    private static final int GPS_MIN_POINT_INTERVAL_SECONDS = 5;
    private static final int MAX_DATED_DATASETS_IN_MEMORY = 4;
    private static final int MAX_PANEL_BUNDLES_IN_MEMORY = 4;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final List<String> DATED_PASSENGER_FILES = List.of(
            "总体小时客流.csv", "线路小时客流.csv", UNLOCATED_LINE_GROUP_FLOW,
            "站点小时客流.csv", "断面小时客流.csv", "线路OD日统计.csv",
            "客群小时统计.csv", "换乘明细.csv", "线路日运营统计.csv", "车辆日运营统计.csv",
            ACTUAL_DEPARTURES);
    private static final int HOURS = 24;
    static final int ACTUAL_DEPARTURE_MATCH_TOLERANCE_SECONDS = 15 * 60;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private MatsimConfig matsimConfig;

    @Resource
    private RealPassengerAggregateBuilder aggregateBuilder;

    @Value("${matsim.real-passenger-cache-prebuild-on-startup:true}")
    private boolean prebuildOnStartup;

    private final BackendMemoryCache<String, CachedDataset> cache =
            new BackendMemoryCache<>("real-passenger-datasets", 192L * 1024 * 1024,
                    cached -> BackendMemoryCache.estimate(cached.data.routes)
                            + BackendMemoryCache.estimate(cached.data.stations)
                            + BackendMemoryCache.estimate(cached.data.departureBundles)
                            + BackendMemoryCache.estimate(cached.data.vehicleIds));
    private final BackendMemoryCache<String, CachedVehicleEvents> vehicleEventCache =
            new BackendMemoryCache<>("real-passenger-vehicle-events", 96L * 1024 * 1024,
                    cached -> BackendMemoryCache.estimate(cached.data));
    private final BackendMemoryCache<String, CachedCorridorNetwork> corridorNetworkCache =
            new BackendMemoryCache<>("real-passenger-corridor", 64L * 1024 * 1024,
                    cached -> BackendMemoryCache.estimate(cached.network));
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final BackendMemoryCache<String, CachedPanelBundle> panelBundleCache =
            new BackendMemoryCache<>("real-passenger-panel-bundles", 128L * 1024 * 1024,
                    cached -> BackendMemoryCache.estimate(cached.bundle));
    private final Set<String> detailWarmups = ConcurrentHashMap.newKeySet();
    private final ExecutorService detailWarmupExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "real-passenger-detail-warmup");
        thread.setDaemon(true);
        return thread;
    });

    @PreDestroy
    public void stopDetailWarmup() {
        detailWarmupExecutor.shutdownNow();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void scheduleStartupPrebuild() {
        if (!prebuildOnStartup) return;
        Thread.ofPlatform()
                .daemon(true)
                .name("real-passenger-cache-builder")
                .start(() -> {
                    try {
                        prepareAllCaches();
                    } catch (RuntimeException error) {
                        log.error("真实客流后台缓存预生成失败: {}", error.getMessage(), error);
                    }
                });
    }

    @Override
    public void prepareAllCaches() {
        for (String area : matsimConfig.areaNames()) {
            try {
                aggregateBuilder.ensureBuilt(area);
                Path root = passengerRoot(area);
                if (!Files.isDirectory(root)) continue;
                List<String> dates = serviceDates(root);
                if (dates.isEmpty()) continue;
                String sourceSignature = signature(root, transitRoot(area));
                Path partitions = ensureDatePartitions(area, root, sourceSignature, dates);
                ensurePanelArtifacts(area, sourceSignature, dates, partitions);
            } catch (RuntimeException error) {
                // 某个区域的真实数据损坏不能反向把无关的仿真模型缓存标记为失败；
                // 真实模式自身的请求仍会返回明确错误，日志保留完整根因。
                log.error("真实客流缓存预生成失败 area={} error={}", area, error.getMessage(), error);
            }
        }
    }

    @Override
    public Map<String, Object> capabilities(String areaName) {
        String area = safeArea(areaName);
        RealPassengerAggregateBuilder.BuildStatus aggregateStatus = aggregateBuilder.startIfNeeded(area);
        if ("pending".equals(aggregateStatus.status()) || "building".equals(aggregateStatus.status())
                || "failed".equals(aggregateStatus.status())) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", aggregateStatus.status());
            result.put("areaName", area);
            result.put("modules", List.of());
            result.put("availableKeys", Set.of());
            result.put("serviceDates", List.of());
            result.put("serviceDayCount", 0);
            result.put("sourceSignature", "");
            result.put("panelCacheStatus", "failed".equals(aggregateStatus.status()) ? "failed" : "building");
            result.put("partitionCacheStatus", "building");
            result.put("panelCacheTotalDates", 0);
            result.put("panelCacheReadyDates", 0);
            result.put("sourceFolder", PASSENGER_FOLDER + "/" + RealPassengerAggregateBuilder.AGGREGATE_FOLDER);
            result.putAll(aggregateBuilder.statusPayload(area));
            return result;
        }
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
        // 真实班次监测复用方向级计划时刻表和已持久化的刷卡/断面日缓存。
        // 能力在服务层补齐，兼容已经生成、尚未重新写出模块说明 CSV 的历史聚合目录。
        if (Files.isRegularFile(passengerRoot(area).resolve(ACTUAL_DEPARTURES))) {
            for (String panel : List.of(
                    "班次客流监测", "班次客流监测-断面客流",
                    "班次客流监测-站点乘降", "班次客流监测-客流画像")) {
                String capabilityKey = "运行监测::" + panel;
                if (available.contains(capabilityKey)) continue;
                Map<String, Object> module = new LinkedHashMap<>();
                module.put("platformModule", "运行监测");
                module.put("leftPanelModule", panel);
                module.put("availability", "partial");
                module.put("primaryData", "班次客流明细.csv");
                module.put("availableStatistics", "实际发车班次、班次客流、站点乘降、断面客流和票卡客群");
                module.put("knownLimitations", "仅统计可由车牌、线路方向和发车窗口可靠匹配的刷卡记录");
                module.put("available", true);
                modules.add(module);
                available.add(capabilityKey);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", Files.isDirectory(passengerRoot(area)) ? "ready" : "missing");
        result.put("areaName", area);
        result.put("modules", modules);
        result.put("availableKeys", available);
        List<String> serviceDates = serviceDates(passengerRoot(area));
        result.put("serviceDates", serviceDates);
        result.put("serviceDayCount", serviceDates.size());
        String sourceSignature = serviceDates.isEmpty() ? "" : signature(passengerRoot(area), transitRoot(area));
        Path partitionRoot = sourceSignature.isBlank() ? null : datePartitionRoot(area, sourceSignature);
        boolean panelReady = partitionRoot != null
                && panelArtifactsReady(panelArtifactMarker(partitionRoot), sourceSignature, serviceDates,
                panelArtifactRoot(partitionRoot));
        result.put("panelCacheStatus", panelReady ? "ready" : "building");
        // 前端门槛按“后端状态位”判定就绪，与仿真模型的 cacheStatus 对等；下面几项让构建期
        // 能显示真实进度，并给浏览器持久缓存提供失效键。
        result.put("sourceSignature", cacheVersion(sourceSignature));
        result.put("panelCacheTotalDates", serviceDates.size());
        result.put("panelCacheReadyDates", panelReady ? serviceDates.size()
                : partitionRoot == null ? 0 : builtPanelDateCount(panelArtifactRoot(partitionRoot), serviceDates));
        result.put("partitionCacheStatus", partitionRoot != null
                && partitionReady(partitionRoot.resolve(".ready"), sourceSignature, serviceDates, partitionRoot)
                ? "ready" : "building");
        result.put("sourceFolder", PASSENGER_FOLDER);
        result.putAll(aggregateBuilder.statusPayload(area));
        return result;
    }

    /**
     * 首次进入真实模式时一次完成所有服务日的解析和核心面板序列化。前端把返回结果直接写入
     * 日期级缓存，之后切换日期不再触发大 CSV 扫描，也不会在组件挂载后出现黑屏等待。
     */
    @Override
    public Map<String, Object> preload(String areaName, String serviceDate) {
        String area = safeArea(areaName);
        Path root = passengerRoot(area);
        List<String> dates = serviceDates(root);
        if (dates.isEmpty()) throw new BusinessException("真实客流数据中没有可用运营日期: " + root);
        String selectedDate = normalizeServiceDate(serviceDate);
        if (selectedDate.isBlank() || !dates.contains(selectedDate)) selectedDate = dates.getLast();
        String sourceSignature = signature(root, transitRoot(area));
        Path partitions = ensureDatePartitions(area, root, sourceSignature, dates);
        ensurePanelArtifacts(area, sourceSignature, dates, partitions);
        Map<String, Object> bundle = panelBundle(area, selectedDate, sourceSignature, partitions);
        // 面板工件可以立即返回；同时单线程暖载当前日期的详情 Dataset。
        // 用户随后点线路/站点时直接命中内存，不再在 HTTP 请求线程同步扫描十余个 CSV。
        warmDetailDataset(area, selectedDate, sourceSignature);
        Map<String, Object> byDate = new LinkedHashMap<>();
        byDate.put(selectedDate, bundle);
        return Map.of(
                "status", "ready",
                "source", "real",
                "selectedServiceDate", selectedDate,
                "serviceDates", dates,
                "dates", byDate);
    }

    private void warmDetailDataset(String area, String selectedDate, String sourceSignature) {
        String key = area + "::" + selectedDate;
        CachedDataset cached = cache.get(key);
        if ((cached != null && cached.signature.equals(sourceSignature)) || !detailWarmups.add(key)) return;
        try {
            detailWarmupExecutor.execute(() -> {
                try {
                    dataset(area, selectedDate);
                } catch (RuntimeException error) {
                    log.warn("真实客流详情数据异步暖载失败 area={} date={} error={}",
                            area, selectedDate, error.getMessage());
                } finally {
                    detailWarmups.remove(key);
                }
            });
        } catch (RejectedExecutionException ignored) {
            detailWarmups.remove(key);
        }
    }

    @Override
    public Map<String, Object> overallFlow(String areaName, String serviceDate) {
        Map<String, Object> bundle = readyPanelBundle(areaName, serviceDate);
        if (bundle != null) return childMap(bundle, "overallFlow");
        return overallFlow(dataset(areaName, serviceDate));
    }

    private Map<String, Object> overallFlow(Dataset data) {
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
        Map<String, Object> bundle = readyPanelBundle(areaName, serviceDate);
        if (bundle != null) return childMap(bundle, "routePanel");
        return routePanel(dataset(areaName, serviceDate));
    }

    private Map<String, Object> routePanel(Dataset data) {
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
    public Map<String, Object> departureTimetable(RealPassengerFlowParam param) {
        Dataset data = dataset(param.getAreaName(), param.getServiceDate());
        RouteAcc route = departureRoute(data, param);
        if (route == null) return emptyDepartureBundle(param);
        return data.departureBundles.computeIfAbsent(route.authorityId,
                ignored -> departureBundle(data, route));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> departurePanel(RealPassengerFlowParam param) {
        Map<String, Object> bundle = departureTimetable(param);
        Object panelsValue = bundle.get("panels");
        if (!(panelsValue instanceof Map<?, ?> panels)) return Map.of();
        Object panel = panels.get(safe(param.getDepartureId()));
        return panel instanceof Map<?, ?> value ? (Map<String, Object>) value : Map.of();
    }

    private RouteAcc departureRoute(Dataset data, RealPassengerFlowParam param) {
        String routeId = safe(param.getRouteId());
        RouteAcc route = data.routes.get(routeId);
        if (route == null && !safe(param.getLineId()).isBlank()) {
            route = data.routes.values().stream()
                    .filter(item -> item.groupId().equals(param.getLineId()) && item.authorityId.equals(routeId))
                    .findFirst().orElse(null);
        }
        return route;
    }

    private Map<String, Object> emptyDepartureBundle(RealPassengerFlowParam param) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ready");
        result.put("source", "real");
        result.put("lineId", safe(param.getLineId()));
        result.put("routeId", safe(param.getRouteId()));
        result.put("departures", List.of());
        result.put("panels", Map.of());
        return result;
    }

    /** SHP 计划时刻表是班次选择器主表；实际发车只作为所选运营日的客流证据。 */
    private Map<String, Object> departureBundle(Dataset data, RouteAcc route) {
        List<RealDepartureAcc> actual = route.actualDepartures.stream()
                .sorted(Comparator.comparingInt(item -> item.departureTime)).toList();
        List<Integer> planned = route.scheduledDepartures.stream().sorted().toList();
        int[] actualByPlanned = matchScheduledToActual(
                planned,
                actual.stream().map(item -> item.departureTime).toList(),
                ACTUAL_DEPARTURE_MATCH_TOLERANCE_SECONDS);
        List<Map<String, Object>> departures = new ArrayList<>(planned.size());
        Map<String, Object> panels = new LinkedHashMap<>();
        List<Integer> timingErrors = new ArrayList<>();
        int matchedCount = 0;
        for (int index = 0; index < planned.size(); index++) {
            int plannedTime = planned.get(index);
            String plannedId = plannedDepartureId(route.authorityId, plannedTime);
            RealDepartureAcc matched = actualByPlanned[index] >= 0 ? actual.get(actualByPlanned[index]) : null;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", plannedId);
            item.put("departureTime", plannedTime);
            item.put("capacity", route.declaredCapacity > 0 ? Math.round(route.declaredCapacity) : null);
            item.put("planned", true);
            item.put("matchedActual", matched != null);
            if (matched != null) {
                int error = matched.departureTime - plannedTime;
                matchedCount++;
                timingErrors.add(Math.abs(error));
                item.put("actualDepartureId", matched.id);
                item.put("actualDepartureTime", matched.departureTime);
                item.put("timingDifferenceSeconds", error);
                item.put("vehicleId", matched.vehicleId);
                panels.put(plannedId, realDeparturePanel(data, route, matched, plannedId, plannedTime));
            } else {
                item.put("vehicleId", "");
            }
            departures.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ready");
        result.put("source", "real");
        result.put("selectedServiceDate", data.selectedDate);
        result.put("lineId", route.groupId());
        result.put("lineName", route.baseName());
        result.put("routeId", route.authorityId);
        result.put("routeName", route.routeName);
        result.put("stationCount", data.stopsByRoute.getOrDefault(route.authorityId, List.of()).size());
        result.put("departures", departures);
        result.put("panels", panels);
        if (!planned.isEmpty()) result.put("emptyPanel", emptyDeparturePanel(data, route));
        result.put("scheduleSource", "shp");
        result.put("actualDepartures", false);
        result.put("actualEvidenceAvailable", !actual.isEmpty());
        result.put("matching", departureMatchingMetrics(
                planned.size(), actual.size(), matchedCount, timingErrors,
                ACTUAL_DEPARTURE_MATCH_TOLERANCE_SECONDS));
        return result;
    }

    private Map<String, Object> realDeparturePanel(Dataset data, RouteAcc route, RealDepartureAcc departure) {
        return realDeparturePanel(data, route, departure, departure.id, departure.departureTime);
    }

    private Map<String, Object> realDeparturePanel(
            Dataset data, RouteAcc route, RealDepartureAcc departure,
            String plannedId, int plannedTime) {
        int passenger = departure.boardingCount;
        int alighting = departure.alightings.values().stream().mapToInt(Integer::intValue).sum();
        List<Map<String, Object>> stationFlows = new ArrayList<>();
        for (StopMeta stop : data.stopsByRoute.getOrDefault(route.authorityId, List.of())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("facilityId", stop.id);
            row.put("facilityName", stop.name);
            row.put("seq", stop.seq);
            row.put("boarding", departure.boardings.getOrDefault(stop.seq, 0));
            row.put("alighting", departure.alightings.getOrDefault(stop.seq, 0));
            stationFlows.add(row);
        }
        List<Map<String, Object>> segments = new ArrayList<>();
        int maxOnboard = 0;
        double loadRateSum = 0;
        int loadRateSamples = 0;
        double capacity = route.declaredCapacity;
        List<StopMeta> stops = data.stopsByRoute.getOrDefault(route.authorityId, List.of());
        for (int index = 0; index + 1 < stops.size(); index++) {
            StopMeta from = stops.get(index);
            StopMeta to = stops.get(index + 1);
            int flow = departure.segmentFlows.getOrDefault(from.seq, 0);
            Double loadRate = capacity > 0 ? round(flow * 100.0 / capacity, 2) : null;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fromFacilityId", from.id);
            row.put("toFacilityId", to.id);
            row.put("fromName", from.name);
            row.put("toName", to.name);
            row.put("name", from.name + " → " + to.name);
            row.put("flow", flow);
            row.put("loadRate", loadRate);
            segments.add(row);
            maxOnboard = Math.max(maxOnboard, flow);
            if (loadRate != null) {
                loadRateSum += loadRate;
                loadRateSamples++;
            }
        }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("passenger", passenger);
        metrics.put("boarding", passenger);
        metrics.put("alighting", alighting);
        metrics.put("capacity", capacity > 0 ? Math.round(capacity) : null);
        metrics.put("maxOnboard", maxOnboard);
        metrics.put("loadRate", capacity > 0 ? round(maxOnboard * 100.0 / capacity, 2) : null);
        metrics.put("averageLoadRate", loadRateSamples > 0 ? round(loadRateSum / loadRateSamples, 2) : null);
        metrics.put("departureTime", plannedTime);
        metrics.put("actualDepartureTime", departure.departureTime);
        metrics.put("timingDifferenceSeconds", departure.departureTime - plannedTime);
        metrics.put("vehicleId", departure.vehicleId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "real-actual-departure");
        result.put("lineId", route.groupId());
        result.put("lineName", route.baseName());
        result.put("routeId", route.authorityId);
        result.put("routeName", route.routeName);
        result.put("departureId", plannedId);
        result.put("departureTime", plannedTime);
        result.put("actualDepartureId", departure.id);
        result.put("actualDepartureTime", departure.departureTime);
        result.put("metrics", metrics);
        result.put("stationFlows", stationFlows);
        result.put("segments", segments);
        result.put("stationOd", List.of());
        result.put("demographics", demographics(departure.demographics, 1));
        result.put("transfers", List.of());
        List<Integer> hourlyFlow = new ArrayList<>(java.util.Collections.nCopies(HOURS, 0));
        int hour = Math.max(0, Math.min(23, plannedTime / 3600));
        hourlyFlow.set(hour, passenger);
        result.put("hourlyFlow", hourlyFlow);
        result.put("actualDeparture", true);
        result.put("matchedActual", true);
        return result;
    }

    private Map<String, Object> emptyDeparturePanel(Dataset data, RouteAcc route) {
        RealDepartureAcc empty = new RealDepartureAcc(
                "", 0, "", 0, Map.of(), Map.of(), Map.of(), Map.of());
        Map<String, Object> panel = realDeparturePanel(data, route, empty, "", 0);
        panel.put("source", "real-planned-departure");
        panel.put("actualDeparture", false);
        panel.put("matchedActual", false);
        panel.remove("actualDepartureId");
        panel.remove("actualDepartureTime");
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) panel.get("metrics");
        metrics.remove("actualDepartureTime");
        metrics.remove("timingDifferenceSeconds");
        return panel;
    }

    private static String plannedDepartureId(String authorityId, int departureTime) {
        return "planned::" + authorityId + "::" + departureTime;
    }

    private static Map<String, Object> departureMatchingMetrics(
            int plannedCount, int actualCount, int matchedCount,
            List<Integer> absoluteErrors, int toleranceSeconds) {
        List<Integer> sorted = absoluteErrors.stream().sorted().toList();
        double mean = sorted.stream().mapToInt(Integer::intValue).average().orElse(0);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("plannedCount", plannedCount);
        metrics.put("actualCount", actualCount);
        metrics.put("matchedCount", matchedCount);
        metrics.put("unmatchedPlannedCount", Math.max(0, plannedCount - matchedCount));
        metrics.put("unmatchedActualCount", Math.max(0, actualCount - matchedCount));
        metrics.put("matchRate", plannedCount > 0 ? round(matchedCount * 100.0 / plannedCount, 2) : 0);
        metrics.put("timingMaeSeconds", round(mean, 2));
        metrics.put("timingMedianSeconds", percentile(sorted, 0.5));
        metrics.put("timingP95Seconds", percentile(sorted, 0.95));
        metrics.put("toleranceSeconds", toleranceSeconds);
        return metrics;
    }

    private static int percentile(List<Integer> sorted, double percentile) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    /** Maximum-cardinality, minimum-total-error ordered matching. */
    static int[] matchScheduledToActual(List<Integer> planned, List<Integer> actual, int toleranceSeconds) {
        int rows = planned.size();
        int columns = actual.size();
        int[][] matches = new int[rows + 1][columns + 1];
        long[][] errors = new long[rows + 1][columns + 1];
        byte[][] action = new byte[rows + 1][columns + 1];
        for (int row = 1; row <= rows; row++) action[row][0] = 1;
        for (int column = 1; column <= columns; column++) action[0][column] = 2;
        for (int row = 1; row <= rows; row++) {
            for (int column = 1; column <= columns; column++) {
                int bestMatches = matches[row - 1][column];
                long bestError = errors[row - 1][column];
                byte bestAction = 1;
                if (better(matches[row][column - 1], errors[row][column - 1], bestMatches, bestError)) {
                    bestMatches = matches[row][column - 1];
                    bestError = errors[row][column - 1];
                    bestAction = 2;
                }
                int difference = Math.abs(planned.get(row - 1) - actual.get(column - 1));
                if (difference <= toleranceSeconds) {
                    int candidateMatches = matches[row - 1][column - 1] + 1;
                    long candidateError = errors[row - 1][column - 1] + difference;
                    if (candidateMatches > bestMatches
                            || (candidateMatches == bestMatches && candidateError <= bestError)) {
                        bestMatches = candidateMatches;
                        bestError = candidateError;
                        bestAction = 3;
                    }
                }
                matches[row][column] = bestMatches;
                errors[row][column] = bestError;
                action[row][column] = bestAction;
            }
        }
        int[] result = new int[rows];
        Arrays.fill(result, -1);
        int row = rows;
        int column = columns;
        while (row > 0 || column > 0) {
            byte current = action[row][column];
            if (current == 3) {
                result[row - 1] = column - 1;
                row--;
                column--;
            } else if (current == 2 && column > 0) {
                column--;
            } else if (row > 0) {
                row--;
            } else {
                column--;
            }
        }
        return result;
    }

    private static boolean better(int matches, long error, int bestMatches, long bestError) {
        return matches > bestMatches || (matches == bestMatches && error < bestError);
    }

    @Override
    public Map<String, Object> stationPanel(String areaName, String serviceDate) {
        Map<String, Object> bundle = readyPanelBundle(areaName, serviceDate);
        if (bundle != null) return childMap(bundle, "stationPanel");
        return stationPanel(dataset(areaName, serviceDate));
    }

    private Map<String, Object> stationPanel(Dataset data) {
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
        String scope = district == null || district.isBlank() ? "全市" : district.trim();
        if ("全市".equals(scope)) {
            Map<String, Object> bundle = readyPanelBundle(areaName, serviceDate);
            if (bundle != null) return childMap(bundle, "evaluation");
        }
        return evaluation(dataset(areaName, serviceDate), scope);
    }

    private Map<String, Object> evaluation(Dataset data, String scope) {
        double passengers = Arrays.stream(data.overall).sum() / data.serviceDays;
        double departures = data.routes.values().stream()
                .mapToDouble(item -> effectiveDepartures(item, 1)).sum() / data.serviceDays;
        double operatedKm = data.routes.values().stream().mapToDouble(item -> item.mileageKm).sum() / data.serviceDays;
        double transfers = data.routes.values().stream()
                .flatMap(item -> item.transfers.values().stream())
                .mapToDouble(values -> Arrays.stream(values).sum()).sum() / data.serviceDays;
        double completeBusTrips = data.routes.values().stream()
                .flatMap(item -> item.ods.values().stream())
                .mapToDouble(item -> item.count).sum() / data.serviceDays;
        double declaredVehicleTotal = data.lineGroups.values().stream()
                .filter(RealPassengerFlowServiceImpl::groupHasDeclaredVehicles)
                .mapToDouble(RealPassengerFlowServiceImpl::declaredVehiclesForGroup)
                .sum();
        double vehicleAveragePassenger = data.lineGroups.values().stream()
                .filter(RealPassengerFlowServiceImpl::groupHasDeclaredVehicles)
                .mapToDouble(LineGroup::totalBoarding)
                .sum() / data.serviceDays;
        long operatingVehicles = Math.round(declaredVehicleTotal);
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
        values.put("cjrzkl", operatingVehicles > 0
                ? round(vehicleAveragePassenger / operatingVehicles, 2) : null);
        values.put("dbczkl", departures > 0 ? round(operation.perTrip(), 2) : null);
        values.put("rcxcs", cityPopulation == null || cityPopulation.residentPersons() <= 0
                || completeBusTrips <= 0 ? null
                : round(completeBusTrips / cityPopulation.residentPersons(), 3));
        values.put("xlfzxxs", nullableRound(network.averageNonLinearCoefficient(), 2));
        values.put("xlcfxs", nullableRound(corridorMetrics.repetitionCoefficient(), 2));
        values.put("xlmzl", averagePeakLoadRate(data));
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
                Map.entry("cjrzkl", "缺少线路 SHP 的 load_num 配车数"),
                Map.entry("dbczkl", "缺少真实日发车班次"),
                Map.entry("rcxcs", "缺少完整公交出行或职住常住人口分母"),
                Map.entry("xlfzxxs", "缺少有效非环公交线路几何"),
                Map.entry("xlcfxs", "缺少可无向去重的公交线路几何"),
                Map.entry("xlmzl", "缺少线路 SHP 的 approved_c 核定载客数"),
                Map.entry("xlklqd", "缺少真实公交上车人次或运营车公里"),
                Map.entry("yxsdb", "缺少同一高峰窗小汽车运行里程与时间"),
                Map.entry("pjhcsj", "缺少乘客到站时间"),
                Map.entry("pjhccs", "缺少完整公交出行分母"),
                Map.entry("gjjbbl", "缺少完整公交与轨道乘坐链"),
                Map.entry("cjczmj", "缺少公交场站用地面积或线路 SHP 的 load_num 配车数")
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
        formulaMetadata.put("operatingVehicles", operatingVehicles > 0 ? operatingVehicles : null);
        formulaMetadata.put("vehicleAveragePassenger", vehicleAveragePassenger);
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
                    double area = parseOptionalMetricNumber(featureText(iterator.next(), "F004"));
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
        VehicleEvents vehicleEvents = vehicleEvents(data.area, data.selectedDate);
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
        long trajectoryVehicles = vehicleEvents.events.stream()
                .map(row -> safe(String.valueOf(row.get(1))))
                .filter(value -> !value.isBlank())
                .distinct()
                .count();
        summary.put("totalVehicles", trajectoryVehicles > 0 ? trajectoryVehicles : data.vehicleIds.size());
        summary.put("pointCount", vehicleEvents.events.size());
        summary.put("realAggregate", true);
        summary.put("chunks", List.of(Map.of("globalStats", globalStats)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ready");
        result.put("source", "real");
        result.put("selectedServiceDate", data.selectedDate);
        result.put("realAggregate", true);
        result.put("cacheVersion", "real-vehicle-gps-v1:" + vehicleEvents.signature);
        result.put("chunkSeconds", 3600);
        result.put("timeRange", Map.of("min", 0, "max", 86399));
        result.put("summary", summary);
        result.put("passengerSeries", passengerSeries);
        result.put("passengerEvents", List.of());
        result.put("meta", vehicleEvents.meta);
        result.put("trajectorySource", vehicleEvents.source);
        result.put("representativeServiceDate", vehicleEvents.serviceDate);
        result.put("vehicleEvents", vehicleEvents.events);
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
        if (!selectedDate.isBlank()) {
            List<String> dates = serviceDates(root);
            if (!dates.contains(selectedDate)) throw new BusinessException("真实客流数据中不存在运营日期: " + selectedDate);
            Path partitionRoot = ensureDatePartitions(area, root, signature, dates).resolve(selectedDate);
            synchronized (locks.computeIfAbsent(cacheKey, ignored -> new Object())) {
                cached = cache.get(cacheKey);
                if (cached != null && cached.signature.equals(signature)) return cached.data;
                long started = System.currentTimeMillis();
                Dataset loaded = load(area, partitionRoot, selectedDate);
                cache.put(cacheKey, new CachedDataset(signature, loaded));
                evictDatedDatasets(area, cacheKey);
                log.info("真实客流日期分片加载完成 area={} selectedDate={} routes={} stations={} elapsed={}ms",
                        area, selectedDate, loaded.routes.size(), loaded.stations.size(),
                        System.currentTimeMillis() - started);
                return loaded;
            }
        }
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
        loadUnlocatedLineGroupHours(data, root.resolve(UNLOCATED_LINE_GROUP_FLOW));
        loadStationHours(data, root.resolve("站点小时客流.csv"));
        loadSegments(data, root.resolve("断面小时客流.csv"));
        loadOd(data, root.resolve("线路OD日统计.csv"));
        loadDemographics(data, root.resolve("客群小时统计.csv"));
        loadTransfers(data, root.resolve("换乘明细.csv"));
        loadOperations(data, root.resolve("线路日运营统计.csv"));
        loadVehicleIdentifiers(data, root.resolve("车辆日运营统计.csv"));
        Path segmentDistances = root.resolve("区间运行时间统计.csv");
        if (!Files.isRegularFile(segmentDistances)) {
            segmentDistances = passengerRoot(area).resolve("区间运行时间统计.csv");
        }
        loadSegmentDistances(data, segmentDistances);
        loadRouteMetadata(data, transitRoot(area).resolve(ROUTE_SHP));
        loadRouteDepartures(data, transitRoot(area).resolve(ROUTE_DEPARTURES));
        loadActualDepartures(data, root.resolve(ACTUAL_DEPARTURES));
        data.finish();
        return data;
    }

    private void evictDatedDatasets(String area, String keepKey) {
        String prefix = area + "::";
        List<String> datedKeys = cache.keys().stream()
                .filter(key -> key.startsWith(prefix) && !key.endsWith("::average"))
                .toList();
        int excess = datedKeys.size() - MAX_DATED_DATASETS_IN_MEMORY;
        for (String key : datedKeys) {
            if (excess <= 0) break;
            if (key.equals(keepKey)) continue;
            cache.remove(key);
            excess--;
        }
    }

    /**
     * 返回已经持久化的最终面板工件。日期为空时保留旧的聚合口径，不走日期工件。
     */
    private Map<String, Object> readyPanelBundle(String areaName, String serviceDate) {
        String selectedDate = normalizeServiceDate(serviceDate);
        if (selectedDate.isBlank()) return null;
        String area = safeArea(areaName);
        Path root = passengerRoot(area);
        if (!Files.isDirectory(root)) return null;
        List<String> dates = serviceDates(root);
        if (!dates.contains(selectedDate)) return null;
        String sourceSignature = signature(root, transitRoot(area));
        Path partitions = ensureDatePartitions(area, root, sourceSignature, dates);
        ensurePanelArtifacts(area, sourceSignature, dates, partitions);
        return panelBundle(area, selectedDate, sourceSignature, partitions);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> childMap(Map<String, Object> bundle, String key) {
        Object value = bundle.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private Path datePartitionRoot(String area, String signature) {
        return matsimConfig.cacheRootPath()
                .resolve("real-passenger-flow")
                .resolve(area)
                .resolve(cacheVersion(signature));
    }

    /** 指纹的短哈希段，既是磁盘缓存目录名，也作为浏览器持久缓存的失效键下发给前端。 */
    static String cacheVersion(String signature) {
        int separator = signature == null ? -1 : signature.indexOf(':');
        return separator > 0 ? signature.substring(0, separator) + "-" + PANEL_ARTIFACT_VERSION : "";
    }

    /** 构建期已落盘的日期工件数量，供门槛显示真实进度而非估算值。 */
    static int builtPanelDateCount(Path target, List<String> dates) {
        int built = 0;
        for (String date : dates) {
            if (Files.isRegularFile(target.resolve(date).resolve(PANEL_ARTIFACT_FILE))) built += 1;
        }
        return built;
    }

    private Path panelArtifactRoot(Path partitionRoot) {
        return partitionRoot.resolve(PANEL_ARTIFACT_VERSION);
    }

    private Path panelArtifactMarker(Path partitionRoot) {
        return panelArtifactRoot(partitionRoot).resolve(".ready");
    }

    /**
     * 把 CSV 分片进一步编译成前端可直接消费的最终面板 JSON。该阶段与模型派生缓存一样
     * 只在源文件指纹或缓存版本变化时运行；服务重启、容器重建都直接复用磁盘工件。
     */
    private Path ensurePanelArtifacts(String area, String signature, List<String> dates, Path partitionRoot) {
        Path target = panelArtifactRoot(partitionRoot);
        Path marker = panelArtifactMarker(partitionRoot);
        if (panelArtifactsReady(marker, signature, dates, target)) return target;
        String lockKey = "panel-artifacts::" + area;
        synchronized (locks.computeIfAbsent(lockKey, ignored -> new Object())) {
            if (panelArtifactsReady(marker, signature, dates, target)) return target;
            long started = System.currentTimeMillis();
            try {
                Files.createDirectories(target);
                for (int index = 0; index < dates.size(); index++) {
                    String date = dates.get(index);
                    long dateStarted = System.currentTimeMillis();
                    Dataset data = load(area, partitionRoot.resolve(date), date);
                    Map<String, Object> bundle = Map.of(
                            "overallFlow", overallFlow(data),
                            "routePanel", routePanel(data),
                            "stationPanel", stationPanel(data),
                            "evaluation", evaluation(data, "全市"));
                    writePanelBundle(target.resolve(date).resolve(PANEL_ARTIFACT_FILE), bundle);
                    synchronized (panelBundleCache) {
                        panelBundleCache.remove(area + "::" + date);
                    }
                    log.info("真实客流最终面板缓存生成进度 area={} date={} progress={}/{} elapsed={}ms",
                            area, date, index + 1, dates.size(), System.currentTimeMillis() - dateStarted);
                }
                Files.writeString(marker, signature, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException error) {
                throw new BusinessException("生成真实客流最终面板缓存失败: " + target, error);
            } finally {
                cache.removeIf(key -> key.startsWith(area + "::") && !key.endsWith("::average"));
            }
            log.info("真实客流最终面板缓存准备完成 area={} dates={} elapsed={}ms path={}",
                    area, dates.size(), System.currentTimeMillis() - started, target);
            return target;
        }
    }

    private boolean panelArtifactsReady(Path marker, String signature, List<String> dates, Path target) {
        try {
            if (!Files.isRegularFile(marker) || !signature.equals(Files.readString(marker, StandardCharsets.UTF_8))) {
                return false;
            }
            for (String date : dates) {
                if (!Files.isRegularFile(target.resolve(date).resolve(PANEL_ARTIFACT_FILE))) return false;
            }
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    private void writePanelBundle(Path destination, Map<String, Object> bundle) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(temporary,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            JSON.writeValue(output, bundle);
        }
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Map<String, Object> panelBundle(String area, String date, String signature, Path partitionRoot) {
        String key = area + "::" + date;
        synchronized (panelBundleCache) {
            CachedPanelBundle cached = panelBundleCache.get(key);
            if (cached != null && cached.signature.equals(signature)) return cached.bundle;
        }
        Path file = panelArtifactRoot(partitionRoot).resolve(date).resolve(PANEL_ARTIFACT_FILE);
        long started = System.currentTimeMillis();
        try (InputStream input = new GZIPInputStream(Files.newInputStream(file))) {
            Map<String, Object> loaded = JSON.readValue(input, MAP_TYPE);
            synchronized (panelBundleCache) {
                panelBundleCache.put(key, new CachedPanelBundle(signature, loaded));
            }
            log.info("真实客流最终面板缓存读取完成 area={} date={} bytes={} elapsed={}ms",
                    area, date, Files.size(file), System.currentTimeMillis() - started);
            return loaded;
        } catch (IOException error) {
            throw new BusinessException("读取真实客流最终面板缓存失败: " + file, error);
        }
    }

    /**
     * 低内存的首次准备：每个大 CSV 只顺序扫描一次并按 service_date 写入持久化分片。
     * 后续日期读取只处理约 1/32 的数据，不需要在堆中同时保留全部日期对象。
     */
    private Path ensureDatePartitions(String area, Path sourceRoot, String signature, List<String> dates) {
        Path target = datePartitionRoot(area, signature);
        Path marker = target.resolve(".ready");
        if (partitionReady(marker, signature, dates, target)) return target;
        String lockKey = "date-partitions::" + area;
        synchronized (locks.computeIfAbsent(lockKey, ignored -> new Object())) {
            if (partitionReady(marker, signature, dates, target)) return target;
            long started = System.currentTimeMillis();
            try {
                Files.createDirectories(target);
                for (String date : dates) Files.createDirectories(target.resolve(date));
                for (String fileName : DATED_PASSENGER_FILES) {
                    partitionCsvByDate(sourceRoot.resolve(fileName), target, dates);
                }
                Files.writeString(marker, signature, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException error) {
                throw new BusinessException("生成真实客流日期分片失败: " + target, error);
            }
            log.info("真实客流日期分片准备完成 area={} dates={} files={} elapsed={}ms path={}",
                    area, dates.size(), DATED_PASSENGER_FILES.size(),
                    System.currentTimeMillis() - started, target);
            return target;
        }
    }

    private boolean partitionReady(Path marker, String signature, List<String> dates, Path target) {
        try {
            if (!Files.isRegularFile(marker) || !signature.equals(Files.readString(marker, StandardCharsets.UTF_8))) {
                return false;
            }
            for (String date : dates) {
                for (String fileName : DATED_PASSENGER_FILES) {
                    if (!Files.isRegularFile(target.resolve(date).resolve(fileName))) return false;
                }
            }
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    private void partitionCsvByDate(Path source, Path target, List<String> dates) throws IOException {
        if (!Files.isRegularFile(source)) return;
        Map<String, BufferedWriter> writers = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) return;
            List<String> headers = parseCsv(headerLine);
            if (!headers.isEmpty()) headers.set(0, headers.getFirst().replace("\uFEFF", ""));
            int dateIndex = headers.indexOf("service_date");
            if (dateIndex < 0) throw new BusinessException("真实客流文件缺少 service_date: " + source.getFileName());
            for (String date : dates) {
                Path output = target.resolve(date).resolve(source.getFileName().toString());
                BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                writer.write(headerLine);
                writer.newLine();
                writers.put(date, writer);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> values = parseCsv(line);
                String date = dateIndex < values.size() ? safe(values.get(dateIndex)) : "";
                BufferedWriter writer = writers.get(date);
                if (writer == null) continue;
                writer.write(line);
                writer.newLine();
            }
        } finally {
            IOException closeError = null;
            for (BufferedWriter writer : writers.values()) {
                try {
                    writer.close();
                } catch (IOException error) {
                    closeError = error;
                }
            }
            if (closeError != null) throw closeError;
        }
    }

    private void ensureDatedDatasets(String area, Path root, String signature, List<String> dates) {
        if (!dates.isEmpty() && dates.stream().allMatch(date -> {
            CachedDataset item = cache.get(area + "::" + date);
            return item != null && item.signature.equals(signature);
        })) return;
        String lockKey = "dated-batch::" + area;
        synchronized (locks.computeIfAbsent(lockKey, ignored -> new Object())) {
            if (!dates.isEmpty() && dates.stream().allMatch(date -> {
                CachedDataset item = cache.get(area + "::" + date);
                return item != null && item.signature.equals(signature);
            })) return;
            long started = System.currentTimeMillis();
            Map<String, Dataset> loaded = loadDatedBatch(area, root, dates);
            cache.removeIf(key -> key.startsWith(area + "::") && !key.endsWith("::average"));
            loaded.forEach((date, data) -> cache.put(area + "::" + date, new CachedDataset(signature, data)));
            log.info("真实客流全部日期缓存加载完成 area={} dates={} routes={} stations={} elapsed={}ms",
                    area, loaded.size(), loaded.values().stream().mapToInt(item -> item.routes.size()).sum(),
                    loaded.values().stream().mapToInt(item -> item.stations.size()).sum(),
                    System.currentTimeMillis() - started);
        }
    }

    private Map<String, Dataset> loadDatedBatch(String area, Path root, List<String> dates) {
        Map<String, Dataset> datasets = new LinkedHashMap<>();
        dates.forEach(date -> datasets.put(date, new Dataset(area, date)));
        Collection<Dataset> values = datasets.values();
        loadStopSequence(values, transitRoot(area).resolve(STOP_SEQUENCE));
        loadDatedRows(datasets, root.resolve("总体小时客流.csv"), this::applyOverall);
        loadDatedRows(datasets, root.resolve("线路小时客流.csv"), this::applyLineHours);
        loadDatedRows(datasets, root.resolve(UNLOCATED_LINE_GROUP_FLOW), this::applyUnlocatedLineGroupHours);
        loadDatedRows(datasets, root.resolve("站点小时客流.csv"), this::applyStationHours);
        loadDatedRows(datasets, root.resolve("断面小时客流.csv"), this::applySegments);
        loadDatedRows(datasets, root.resolve("线路OD日统计.csv"), this::applyOd);
        loadDatedRows(datasets, root.resolve("客群小时统计.csv"), this::applyDemographics);
        loadDatedRows(datasets, root.resolve("换乘明细.csv"), this::applyTransfers);
        loadDatedRows(datasets, root.resolve("线路日运营统计.csv"), this::applyOperations);
        loadDatedRows(datasets, root.resolve("车辆日运营统计.csv"), this::applyVehicleIdentifiers);
        loadSegmentDistances(values, root.resolve("区间运行时间统计.csv"));
        loadRouteMetadata(values, transitRoot(area).resolve(ROUTE_SHP));
        loadRouteDepartures(values, transitRoot(area).resolve(ROUTE_DEPARTURES));
        loadDatedRows(datasets, root.resolve(ACTUAL_DEPARTURES), this::applyActualDeparture);
        values.forEach(data -> {
            data.dates.add(data.selectedDate);
            data.serviceDays = 1;
            data.finish();
        });
        return datasets;
    }

    private void loadDatedRows(Map<String, Dataset> datasets, Path file,
                               BiConsumer<Dataset, Map<String, String>> consumer) {
        readCsv(file, row -> {
            Dataset data = datasets.get(text(row, "service_date"));
            if (data != null) consumer.accept(data, row);
        });
    }

    private static String normalizeServiceDate(String value) {
        String date = safe(value);
        if (date.isBlank() || "average".equalsIgnoreCase(date)) return "";
        if (!date.matches("\\d{4}-\\d{2}-\\d{2}")) throw new BusinessException("运营日期格式不正确: " + date);
        return date;
    }

    private List<String> serviceDates(Path root) {
        Set<String> dates = new LinkedHashSet<>();
        readCsv(root.resolve("总体小时客流.csv"), row -> {
            String date = text(row, "service_date");
            if (!date.isBlank()) dates.add(date);
        });
        return dates.stream().sorted().toList();
    }

    private void loadStopSequence(Dataset data, Path file) {
        loadStopSequence(List.of(data), file);
    }

    private void loadStopSequence(Collection<Dataset> datasets, Path file) {
        readCsv(file, row -> datasets.forEach(data -> applyStopSequence(data, row)));
        datasets.forEach(data -> data.stopsByRoute.values()
                .forEach(stops -> stops.sort(Comparator.comparingInt(item -> item.seq))));
    }

    private void applyStopSequence(Dataset data, Map<String, String> row) {
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
    }

    private void loadOverall(Dataset data, Path file) {
        readCsv(file, row -> applyOverall(data, row));
        data.serviceDays = data.selectedDate.isBlank() ? Math.max(1, data.dates.size()) : 1;
    }

    private void applyOverall(Dataset data, Map<String, String> row) {
        String serviceDate = text(row, "service_date");
        data.dates.add(serviceDate);
        if (!data.accepts(serviceDate)) return;
        int hour = hour(row);
        double count = number(row, "all_swipe_count");
        if (hour >= 0) data.overall[hour] += count;
        if (!serviceDate.isBlank()) data.dailyOverall.merge(serviceDate, count, Double::sum);
    }

    private void loadLineHours(Dataset data, Path file) {
        readCsv(file, row -> applyLineHours(data, row));
    }

    private void applyLineHours(Dataset data, Map<String, String> row) {
        if (!data.accepts(text(row, "service_date"))) return;
        RouteAcc route = data.route(text(row, "authority_line_id"), text(row, "authority_route_name"));
        int hour = hour(row);
        if (route == null || hour < 0) return;
        route.boarding[hour] += number(row, "boarding_count");
        route.alighting[hour] += number(row, "alighting_count");
        route.trips[hour] += number(row, "trip_count");
        String serviceDate = text(row, "service_date");
        if (!serviceDate.isBlank()) route.dailyBoarding.merge(serviceDate, number(row, "boarding_count"), Double::sum);
    }

    /**
     * 车辆 ID 以体量较小的车辆日统计为准。到离站明细用于轨迹回放，文件接近 1GB，
     * 且历史数据并非严格按日期排序，不能依赖其首个日期分块来推断整条线路的车辆集合。
     */
    private void loadVehicleIdentifiers(Dataset data, Path file) {
        readCsv(file, row -> applyVehicleIdentifiers(data, row));
    }

    private void applyVehicleIdentifiers(Dataset data, Map<String, String> row) {
        if (!data.accepts(text(row, "service_date"))) return;
        String plateNumber = text(row, "plate_number");
        if (plateNumber.isBlank()) return;
        data.vehicleIds.add(plateNumber);
        RouteAcc route = data.routes.get(text(row, "authority_line_id"));
        if (route != null) route.vehicleIds.add(plateNumber);
    }

    private void loadUnlocatedLineGroupHours(Dataset data, Path file) {
        readCsv(file, row -> applyUnlocatedLineGroupHours(data, row));
    }

    private void applyUnlocatedLineGroupHours(Dataset data, Map<String, String> row) {
        String serviceDate = text(row, "service_date");
        if (!data.accepts(serviceDate)) return;
        String lineName = baseLineName(text(row, "authority_line_group_name"));
        int hour = hour(row);
        double count = number(row, "boarding_count");
        if (lineName.isBlank() || hour < 0 || count <= 0) return;
        String groupId = lineGroupId(lineName);
        UnlocatedLineGroupAcc group = data.unlocatedLineGroups.computeIfAbsent(
                groupId, ignored -> new UnlocatedLineGroupAcc(groupId, lineName));
        group.boarding[hour] += count;
        if (!serviceDate.isBlank()) group.dailyBoarding.merge(serviceDate, count, Double::sum);
        String company = text(row, "company_raw");
        group.companies.merge(company.isBlank() ? "未知企业" : company, count, Double::sum);
    }

    private void loadStationHours(Dataset data, Path file) {
        readCsv(file, row -> applyStationHours(data, row));
    }

    private void applyStationHours(Dataset data, Map<String, String> row) {
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
    }

    private void loadSegments(Dataset data, Path file) {
        readCsv(file, row -> applySegments(data, row));
    }

    private void applySegments(Dataset data, Map<String, String> row) {
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
    }

    private void loadOd(Dataset data, Path file) {
        readCsv(file, row -> applyOd(data, row));
    }

    private void applyOd(Dataset data, Map<String, String> row) {
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
    }

    private void loadDemographics(Dataset data, Path file) {
        readCsv(file, row -> applyDemographics(data, row));
    }

    private void applyDemographics(Dataset data, Map<String, String> row) {
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
    }

    private void loadTransfers(Dataset data, Path file) {
        readCsv(file, row -> applyTransfers(data, row));
    }

    private void applyTransfers(Dataset data, Map<String, String> row) {
        if (!data.accepts(text(row, "service_date"))) return;
        RouteAcc route = data.routes.get(text(row, "from_line_id"));
        String to = text(row, "to_line_id");
        if (route == null || to.isBlank()) return;
        int hour = dateTimeHour(text(row, "next_board_time"));
        double[] values = route.transfers.computeIfAbsent(to, ignored -> new double[HOURS]);
        values[Math.max(0, hour)] += 1;
    }

    private void loadOperations(Dataset data, Path file) {
        readCsv(file, row -> applyOperations(data, row));
    }

    private void applyOperations(Dataset data, Map<String, String> row) {
        if (!data.accepts(text(row, "service_date"))) return;
        RouteAcc route = data.route(text(row, "authority_line_id"), text(row, "authority_route_name"));
        if (route == null) return;
        route.vehicles += number(row, "vehicle_count");
        route.departures += number(row, "trip_start_count");
        route.mileageKm += number(row, "mileage_km");
        double runTimeMinutes = number(row, "run_time_min");
        route.runTimeMinutes += runTimeMinutes;
        // 无有效里程/运行时间时，生成器会有意把平均速度留空。这代表“无速度样本”，
        // 不是整行数据损坏；车辆、班次、客流等其余真实指标仍应正常读取。
        double speed = optionalNumber(row, "avg_speed_kmh");
        if (Double.isFinite(speed)) {
            double speedWeightMinutes = Math.max(1, runTimeMinutes);
            route.speedWeighted += speed * speedWeightMinutes;
            route.speedWeightMinutes += speedWeightMinutes;
        }
        int first = dateTimeSeconds(text(row, "first_event_time"));
        int last = dateTimeSeconds(text(row, "last_event_time"));
        if (first >= 0) route.firstTime = Math.min(route.firstTime, first);
        if (last >= 0) route.lastTime = Math.max(route.lastTime, last);
        if (first >= 0 && last >= first) {
            int firstHour = Math.max(0, Math.min(23, first / 3600));
            int lastHour = Math.max(firstHour, Math.min(23, last / 3600));
            double vehicles = number(row, "vehicle_count");
            for (int hour = firstHour; hour <= lastHour; hour++) {
                data.vehicleActive[hour] += vehicles;
                if (Double.isFinite(speed)) {
                    data.vehicleSpeedSum[hour] += speed * vehicles;
                    data.vehicleSpeedWeight[hour] += vehicles;
                }
            }
        }
    }

    private void loadSegmentDistances(Dataset data, Path file) {
        loadSegmentDistances(List.of(data), file);
    }

    private void loadSegmentDistances(Collection<Dataset> datasets, Path file) {
        readCsv(file, row -> datasets.forEach(data -> applySegmentDistances(data, row)));
    }

    private void applySegmentDistances(Dataset data, Map<String, String> row) {
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
    }

    /** 企业、首末班及标称间隔始终取现行线路 SHP；只回填已有真实客流的线路。 */
    private void loadRouteMetadata(Dataset data, Path file) {
        loadRouteMetadata(List.of(data), file);
    }

    private void loadRouteMetadata(Collection<Dataset> datasets, Path file) {
        if (!Files.isRegularFile(file)) return;
        ShapefileDataStore store = null;
        try {
            store = new ShapefileDataStore(file.toUri().toURL());
            store.setCharset(StandardCharsets.UTF_8);
            try (SimpleFeatureIterator iterator = store.getFeatureSource().getFeatures().features()) {
                while (iterator.hasNext()) {
                    SimpleFeature feature = iterator.next();
                    String routeId = featureText(feature, "line_id");
                    String routeName = featureText(feature, "name");
                    String company = featureText(feature, "company");
                    int firstTime = clockSeconds(featureText(feature, "first"));
                    int lastTime = clockSeconds(featureText(feature, "last"));
                    double declaredDepartures = featureNumber(feature, "dep_count");
                    double declaredVehicles = featureNumber(feature, "load_num");
                    double amHeadway = featureNumber(feature, "am_gap");
                    double pmHeadway = featureNumber(feature, "pm_gap");
                    double offPeakHeadway = featureNumber(feature, "off_gap");
                    // approved_c 是从最新未停运运营计划回填的真实核定载客数。
                    // 旧 capacity 字段是历史占位字符串，不能再作为真实满载率容量来源。
                    double capacity = featureNumber(feature, "approved_c");
                    Object shape = feature.getDefaultGeometry();
                    if (shape instanceof Geometry geometry) {
                        List<double[]> points = Arrays.stream(geometry.getCoordinates())
                                .map(coordinate -> new double[]{coordinate.x, coordinate.y})
                                .filter(point -> Double.isFinite(point[0]) && Double.isFinite(point[1]))
                                .toList();
                        datasets.forEach(data -> {
                            RouteAcc route = data.route(routeId, routeName);
                            if (route != null) route.geometry = points;
                        });
                    }
                    datasets.forEach(data -> {
                        RouteAcc route = data.route(routeId, routeName);
                        if (route == null) return;
                        route.company = company;
                        route.scheduledFirstTime = firstTime;
                        route.scheduledLastTime = lastTime;
                        // 线路 SHP 是真实线路运营参数的权威来源。CSV 运营统计缺少这些字段时，
                        // 直接使用方向级计划班次、配车数和早晚高峰/平峰间隔。
                        if (declaredDepartures > 0) route.declaredDepartures = declaredDepartures;
                        if (declaredVehicles > 0) route.declaredVehicles = declaredVehicles;
                        if (capacity > 0) route.declaredCapacity = capacity;
                        route.peakHeadwayMin = averagePositive(amHeadway, pmHeadway);
                        if (offPeakHeadway > 0) route.offPeakHeadwayMin = offPeakHeadway;
                    });
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
        loadRouteDepartures(List.of(data), file);
    }

    private void loadRouteDepartures(Collection<Dataset> datasets, Path file) {
        readCsv(file, row -> {
            String routeId = text(row, "line_id");
            List<Integer> departures = Arrays.stream(text(row, "departures").split(";"))
                    .map(String::trim)
                    .map(RealPassengerFlowServiceImpl::clockSeconds)
                    .filter(value -> value >= 0)
                    .sorted()
                    .toList();
            Double peakHeadway = scheduledHeadway(departures, true);
            Double offPeakHeadway = scheduledHeadway(departures, false);
            datasets.forEach(data -> {
                RouteAcc route = data.routes.get(routeId);
                if (route == null) return;
                // SHP 的 am_gap/pm_gap/off_gap 是线路运营参数的权威值；时刻表计算只负责
                // 补齐旧数据缺失字段，不能把例如 15 分钟覆盖成 14.86 分钟。
                if (route.peakHeadwayMin == null || route.peakHeadwayMin <= 0) {
                    route.peakHeadwayMin = peakHeadway;
                }
                if (route.offPeakHeadwayMin == null || route.offPeakHeadwayMin <= 0) {
                    route.offPeakHeadwayMin = offPeakHeadway;
                }
                if (!departures.isEmpty()) {
                    route.scheduledDepartures = departures;
                    route.scheduledFirstTime = departures.getFirst();
                    route.scheduledLastTime = departures.getLast();
                }
            });
        });
    }

    private void loadActualDepartures(Dataset data, Path file) {
        // “全样本日平均”没有唯一的实际班次序列；真实班次只在明确运营日下提供。
        if (data.selectedDate.isBlank()) return;
        readCsv(file, row -> applyActualDeparture(data, row));
        data.routes.values().forEach(route -> route.actualDepartures.sort(
                Comparator.comparingInt(item -> item.departureTime)));
    }

    private void applyActualDeparture(Dataset data, Map<String, String> row) {
        if (!data.accepts(text(row, "service_date"))) return;
        String routeId = text(row, "authority_line_id");
        String id = text(row, "departure_id");
        int departureTime = dateTimeSeconds(text(row, "departure_time"));
        if (routeId.isBlank() || id.isBlank() || departureTime < 0) return;
        RouteAcc route = data.routes.get(routeId);
        if (route == null) return;
        route.actualDepartures.add(new RealDepartureAcc(
                id,
                departureTime,
                text(row, "plate_number"),
                integer(row, "boarding_count"),
                countMap(text(row, "boardings_by_seq")),
                countMap(text(row, "alightings_by_seq")),
                countMap(text(row, "segment_flows_by_seq")),
                decimalCountMap(text(row, "passenger_groups"))));
    }

    private static Map<Integer, Integer> countMap(String value) {
        if (value.isBlank()) return Map.of();
        try {
            Map<String, Object> source = JSON.readValue(value, MAP_TYPE);
            Map<Integer, Integer> result = new LinkedHashMap<>();
            source.forEach((key, count) -> {
                try {
                    int seq = Integer.parseInt(key);
                    int number = count instanceof Number item ? item.intValue() : Integer.parseInt(String.valueOf(count));
                    if (number > 0) result.put(seq, number);
                } catch (NumberFormatException ignored) {
                    // 单个损坏键不影响该班次其余站点。
                }
            });
            return result;
        } catch (IOException error) {
            throw new BusinessException("真实班次缓存计数字段损坏", error);
        }
    }

    private static Map<String, Double> decimalCountMap(String value) {
        if (value.isBlank()) return Map.of();
        try {
            Map<String, Object> source = JSON.readValue(value, MAP_TYPE);
            Map<String, Double> result = new LinkedHashMap<>();
            source.forEach((key, count) -> {
                double number = count instanceof Number item ? item.doubleValue() : 0;
                if (number > 0) result.put(key, number);
            });
            return result;
        } catch (IOException error) {
            throw new BusinessException("真实班次缓存客群字段损坏", error);
        }
    }

    private static String featureText(SimpleFeature feature, String field) {
        Object value = feature == null ? null : feature.getAttribute(field);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static double featureNumber(SimpleFeature feature, String field) {
        Object value = feature == null ? null : feature.getAttribute(field);
        if (value == null || String.valueOf(value).trim().isBlank()) return 0;
        try {
            double number = value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value).trim());
            return Double.isFinite(number) && number > 0 ? number : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static double averagePositive(double first, double second) {
        if (first > 0 && second > 0) return (first + second) / 2.0;
        return first > 0 ? first : second > 0 ? second : 0;
    }

    static double firstCapacity(String value) {
        String text = safe(value);
        if (text.isBlank()) return 0;
        String first = text.split("[/,，;；\\s-]", 2)[0].trim();
        try {
            double number = Double.parseDouble(first);
            return Double.isFinite(number) && number > 0 ? number : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static int clockSeconds(String value) {
        String text = safe(value);
        if (text.isBlank()) return -1;
        String[] parts = text.split(":");
        if (parts.length < 2 || parts.length > 3) {
            throw new BusinessException("计划时刻格式非法: " + value);
        }
        try {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            int second = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            // GTFS/公交时刻允许用 24:00 之后的小时表达跨午夜班次，例如 26:10:00。
            if (hour < 0 || hour > 47 || minute < 0 || minute > 59 || second < 0 || second > 59) {
                throw new BusinessException("计划时刻超出范围: " + value);
            }
            return hour * 3600 + minute * 60 + second;
        } catch (NumberFormatException error) {
            throw new BusinessException("计划时刻格式非法: " + value, error);
        }
    }

    private static Double scheduledHeadway(List<Integer> departures, boolean peak) {
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
        return count > 0 ? round(total / count, 2) : null;
    }

    private VehicleEvents vehicleEvents(String area, String serviceDate) {
        Path gps = gpsFile(area);
        Path file = Files.isRegularFile(gps)
                ? gps
                : passengerRoot(area).resolve("车辆到离站明细.csv");
        String selectedDate = normalizeServiceDate(serviceDate);
        String cacheKey = area + "::" + (selectedDate.isBlank() ? "representative" : selectedDate);
        String signature = fileSignature(file);
        CachedVehicleEvents cached = vehicleEventCache.get(cacheKey);
        if (cached != null && cached.signature.equals(signature)) return cached.data;
        synchronized (locks.computeIfAbsent("vehicle-events::" + cacheKey, ignored -> new Object())) {
            cached = vehicleEventCache.get(cacheKey);
            if (cached != null && cached.signature.equals(signature)) return cached.data;
            VehicleEvents loaded = Files.isRegularFile(gps)
                    ? loadGpsVehicleEvents(selectedDate, file, signature)
                    : loadVehicleEvents(selectedDate, file, signature);
            vehicleEventCache.put(cacheKey, new CachedVehicleEvents(signature, loaded));
            return loaded;
        }
    }

    /**
     * 车辆回放明细接近 1GB，必须与线路/站点客流缓存分离；仅进入车辆回放时读取所选服务日。
     * 历史明细存在日期回跳，必须扫描完整文件，不能在遇到下一日期时提前结束。
     */
    private VehicleEvents loadVehicleEvents(String requestedDate, Path file, String signature) {
        if (!Files.isRegularFile(file)) return new VehicleEvents("", List.of(), Map.of(), "run", signature);
        List<List<Object>> events = new ArrayList<>();
        String representativeDate = safe(requestedDate);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) return new VehicleEvents("", List.of(), Map.of(), "run", signature);
            List<String> headers = parseCsv(headerLine);
            if (!headers.isEmpty()) headers.set(0, headers.getFirst().replace("\uFEFF", ""));
            Map<String, Integer> indexes = new HashMap<>();
            for (int index = 0; index < headers.size(); index++) indexes.put(headers.get(index), index);
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> values = parseCsv(line);
                String serviceDate = csvValue(values, indexes, "service_date");
                if (representativeDate.isBlank()) representativeDate = serviceDate;
                if (!representativeDate.equals(serviceDate)) continue;
                int seconds = dateTimeSeconds(csvValue(values, indexes, "arrival_time"));
                double lon = parseNumber(csvValue(values, indexes, "lon"));
                double lat = parseNumber(csvValue(values, indexes, "lat"));
                if (seconds < 0 || !Double.isFinite(lon) || !Double.isFinite(lat) || lon == 0 || lat == 0) continue;
                double speed = parseNumber(csvValue(values, indexes, "avg_speed_kmh"));
                String plateNumber = csvValue(values, indexes, "plate_number");
                String routeId = csvValue(values, indexes, "authority_line_id");
                events.add(List.of(
                        seconds,
                        plateNumber,
                        routeId,
                        lon,
                        lat,
                        Double.isFinite(speed) ? speed : 0,
                        csvValue(values, indexes, "stop_name")
                ));
            }
            events.sort(Comparator.comparingInt(row -> ((Number) row.getFirst()).intValue()));
        } catch (IOException error) {
            log.warn("读取真实车辆回放明细失败: {}", file, error);
        }
        return new VehicleEvents(representativeDate, events, Map.of(), "run", signature);
    }

    /**
     * Real vehicle playback is sourced from the raw GPS stream whenever it is present.
     * The source is intentionally parsed here, instead of the passenger aggregate builder,
     * so replacing a GPS file immediately changes the trajectory without rebuilding all
     * passenger artifacts. Five-second de-duplication removes duplicate device reports while
     * preserving the existing client-side interpolation and viewport/Worker optimizations.
     */
    VehicleEvents loadGpsVehicleEvents(String requestedDate, Path file, String signature) {
        if (!Files.isRegularFile(file)) return new VehicleEvents("", List.of(), Map.of(), "gps", signature);
        List<List<Object>> events = new ArrayList<>();
        Map<String, Map<String, Object>> metaByVehicle = new LinkedHashMap<>();
        List<List<Object>> fallbackEvents = new ArrayList<>();
        Map<String, Map<String, Object>> fallbackMetaByVehicle = new LinkedHashMap<>();
        String representativeDate = safe(requestedDate);
        String fallbackDate = "";
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) return new VehicleEvents("", List.of(), Map.of(), "gps", signature);
            List<String> headers = parseCsv(headerLine);
            if (!headers.isEmpty()) headers.set(0, headers.getFirst().replace("\uFEFF", ""));
            Map<String, Integer> indexes = new HashMap<>();
            for (int index = 0; index < headers.size(); index++) indexes.put(headers.get(index), index);
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> values = parseCsv(line);
                String timestamp = csvValue(values, indexes, "LOCATION_TIME");
                String rowDate = timestamp.length() >= 10 ? timestamp.substring(0, 10) : "";
                if (rowDate.isBlank()) continue;
                if (fallbackDate.isBlank()) fallbackDate = rowDate;
                boolean requestedRow = !requestedDate.isBlank() && requestedDate.equals(rowDate);
                boolean fallbackRow = fallbackDate.equals(rowDate);
                if (!requestedRow && !fallbackRow) continue;
                if (representativeDate.isBlank()) representativeDate = fallbackDate;
                List<List<Object>> eventTarget = requestedRow || requestedDate.isBlank()
                        ? events : fallbackEvents;
                Map<String, Map<String, Object>> metaTarget = requestedRow || requestedDate.isBlank()
                        ? metaByVehicle : fallbackMetaByVehicle;
                String plate = csvValue(values, indexes, "PLATE_NUMBER").trim();
                double lon = parseNumber(csvValue(values, indexes, "R_LONGITUDE"));
                double lat = parseNumber(csvValue(values, indexes, "R_LATITUDE"));
                if (!Double.isFinite(lon) || !Double.isFinite(lat) || lon < 110 || lon > 120 || lat < 15 || lat > 30) {
                    lon = parseNumber(csvValue(values, indexes, "LONGITUDE"));
                    lat = parseNumber(csvValue(values, indexes, "LATITUDE"));
                }
                if (plate.isBlank() || !Double.isFinite(lon) || !Double.isFinite(lat)
                        || lon < 110 || lon > 120 || lat < 15 || lat > 30) continue;
                int seconds;
                try {
                    seconds = dateTimeSeconds(timestamp);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (seconds < 0) continue;
                String routeName = csvValue(values, indexes, "ROUTE_CODE").trim();
                String stationName = csvValue(values, indexes, "STATION_NAME").trim();
                double speed = parseNumber(csvValue(values, indexes, "SPEED"));
                if (!Double.isFinite(speed)) speed = parseNumber(csvValue(values, indexes, "LOCATION_SPEED"));
                eventTarget.add(List.of(seconds, plate, routeName, lon, lat,
                        Double.isFinite(speed) ? speed : 0, stationName));
                Map<String, Object> meta = metaTarget.computeIfAbsent(plate, id -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", id);
                    value.put("mode", "bus");
                    value.put("lineId", routeName);
                    value.put("lineName", routeName.isBlank() ? "公交车" : routeName);
                    return value;
                });
                if (safe(String.valueOf(meta.get("lineId"))).isBlank() && !routeName.isBlank()) {
                    meta.put("lineId", routeName);
                    meta.put("lineName", routeName);
                }
            }
        } catch (IOException error) {
            log.warn("读取真实 GPS 车辆轨迹失败: {}", file, error);
        }
        if (!requestedDate.isBlank() && events.isEmpty()) {
            // A passenger aggregate can represent a different service day than the available
            // GPS snapshot. Keep real playback useful by exposing that snapshot explicitly.
            events = fallbackEvents;
            metaByVehicle = fallbackMetaByVehicle;
            representativeDate = fallbackDate;
        }
        events.sort(Comparator.comparingInt(row -> ((Number) row.getFirst()).intValue()));
        Map<String, Integer> lastTimeByVehicle = new HashMap<>();
        List<List<Object>> filteredEvents = new ArrayList<>(events.size());
        for (List<Object> row : events) {
            String vehicleId = String.valueOf(row.get(1));
            int seconds = ((Number) row.getFirst()).intValue();
            Integer last = lastTimeByVehicle.get(vehicleId);
            if (last != null && seconds < last + GPS_MIN_POINT_INTERVAL_SECONDS) continue;
            lastTimeByVehicle.put(vehicleId, seconds);
            filteredEvents.add(row);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("vehicles", new ArrayList<>(metaByVehicle.values()));
        meta.put("routes", Map.of());
        log.info("真实车辆 GPS 轨迹已加载 file={} date={} vehicles={} points={}",
                file.getFileName(), representativeDate, metaByVehicle.size(), filteredEvents.size());
        return new VehicleEvents(representativeDate, filteredEvents, meta, "gps", signature);
    }

    private Path gpsFile(String area) {
        Path folder = matsimConfig.realDataPath(area).resolve(PASSENGER_FOLDER).resolve(GPS_FOLDER);
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                    .filter(path -> !path.getFileName().toString().startsWith("._"))
                    .sorted().findFirst().orElse(folder.resolve("GPS.csv"));
        } catch (IOException ignored) {
            return folder.resolve("GPS.csv");
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
        panel.put("dailyFlow", dailyFlow(route.dailyBoarding));
        panel.put("source", "real");
        panel.put("segments", segmentPanels(data, route.segments.values(), route.declaredCapacity));
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
        panel.put("source", "real");
        panel.put("operator", group.operator());
        panel.put("lineGroup", true);
        panel.put("routeKeys", group.routes.stream().map(RouteAcc::routeKey).toList());
        double[] boarding = sumArrays(group.routes.stream().map(route -> route.boarding).toList());
        addInto(boarding, group.unlocatedBoarding);
        double[] alighting = sumArrays(group.routes.stream().map(route -> route.alighting).toList());
        panel.put("hours", hours());
        panel.put("hourlyFlow", averageList(boarding, data.serviceDays));
        panel.put("boardingByHour", averageList(boarding, data.serviceDays));
        panel.put("alightingByHour", averageList(alighting, data.serviceDays));
        panel.put("dailyFlow", dailyFlow(mergeDailyFlow(group)));
        panel.put("segments", group.routes.stream()
                .flatMap(route -> segmentPanels(data, route.segments.values(), route.declaredCapacity).stream())
                .toList());
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

    private List<Map<String, Object>> segmentPanels(
            Dataset data, Collection<SegmentAcc> segments, double capacity) {
        return segments.stream().sorted(Comparator.comparingInt(item -> item.seq)).map(segment -> {
            List<Integer> hourly = averageList(segment.flow, data.serviceDays);
            int total = hourly.stream().mapToInt(Integer::intValue).sum();
            List<Double> loadRateByHour = capacity > 0
                    ? hourly.stream().map(value -> round(value * 100.0 / capacity, 2)).toList()
                    : null;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", segment.fromName + " - " + segment.toName);
            item.put("fromFacilityId", segment.fromId);
            item.put("fromName", segment.fromName);
            item.put("toFacilityId", segment.toId);
            item.put("toName", segment.toName);
            item.put("stationNames", List.of(segment.fromName, segment.toName));
            item.put("flowByHour", hourly);
            item.put("loadRateByHour", loadRateByHour);
            item.put("totalFlow", total);
            item.put("flow", total);
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
                if (od.direction.equals("out")) {
                    item.put("destinationX", counterpart.lon);
                    item.put("destinationY", counterpart.lat);
                } else {
                    item.put("originX", counterpart.lon);
                    item.put("originY", counterpart.lat);
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
        double vehicles = effectiveVehicles(route);
        double departures = effectiveDepartures(route, data.serviceDays);
        double operatedKm = route.mileageKm > 0
                ? route.mileageKm / data.serviceDays
                : route.routeDistanceMeters > 0 ? route.routeDistanceMeters / 1000.0 * departures : 0;
        PeakLoadEstimate peakLoad = peakLoadEstimate(route);
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("routeDist", round(route.routeDistanceMeters, 1));
        metrics.put("firstTime", route.scheduledFirstTime >= 0 ? route.scheduledFirstTime : route.firstTime == Integer.MAX_VALUE ? 0 : route.firstTime);
        metrics.put("lastTime", route.scheduledLastTime >= 0 ? route.scheduledLastTime : route.lastTime);
        metrics.put("facNum", data.stopsByRoute.getOrDefault(route.authorityId, List.of()).size());
        metrics.put("facDist", route.stopDistance(data));
        metrics.put("lc", 0);
        metrics.put("passenger", Math.round(passenger));
        metrics.put("loadRate", peakLoad.percent());
        metrics.put("passengerStrength", operatedKm > 0 ? round(passenger / operatedKm, 3) : 0);
        metrics.put("operatingVehicleKm", round(operatedKm, 2));
        metrics.put("departures", round(departures, 2));
        metrics.put("vehicles", vehicles > 0 ? round(vehicles, 2) : null);
        metrics.put("vehicleIds", new ArrayList<>(route.vehicleIds));
        metrics.put("perTripFlow", departures > 0 ? round(passenger / departures, 2) : 0);
        metrics.put("perVehicleFlow", vehicles > 0 ? round(passenger / vehicles, 2) : null);
        metrics.put("peakHeadwayMin", nullableMetric(route.peakHeadwayMin));
        metrics.put("offPeakHeadwayMin", nullableMetric(route.offPeakHeadwayMin));
        metrics.put("peakAverageLoadRate", peakLoad.percent());
        metrics.put("peakDepartureSamples", peakLoad.samples());
        metrics.put("peakMissingCapacityDepartures", peakLoad.missingCapacityDepartures());
        metrics.put("operatedKm", round(operatedKm, 2));
        metrics.put("company", safe(route.company).isBlank() ? "未知企业" : route.company);
        metrics.put("avgSpeedKmh", route.speedWeightMinutes > 0
                ? round(route.speedWeighted / route.speedWeightMinutes, 2) : 0);
        return metrics;
    }

    private Map<String, Object> groupMetrics(Dataset data, LineGroup group) {
        double passenger = group.totalBoarding() / data.serviceDays;
        Set<String> vehicleIds = new LinkedHashSet<>();
        for (RouteAcc route : group.routes) vehicleIds.addAll(route.vehicleIds);
        // load_num is a physical-line fleet count copied to each direction;
        // use one value for the group instead of doubling it across directions.
        double vehicles = groupHasDeclaredVehicles(group) ? declaredVehiclesForGroup(group) : 0;
        double totalDepartures = group.routes.stream().mapToDouble(route ->
                effectiveDepartures(route, 1)).sum();
        double departures = totalDepartures / data.serviceDays;
        double operatedKm = group.routes.stream().mapToDouble(route -> route.mileageKm).sum() / data.serviceDays;
        if (operatedKm <= 0) {
            operatedKm = group.routes.stream().mapToDouble(route ->
                    route.routeDistanceMeters / 1000.0 * effectiveDepartures(route, 1)).sum() / data.serviceDays;
        }
        // 与仿真 lineGroup 同口径：多个运营路径按实际发车班次加权，避免最长路径或路径长度简单相加
        // 与整条线路总客流的统计范围不一致。
        double weightedRouteDist = group.routes.stream()
                .mapToDouble(route -> route.routeDistanceMeters * effectiveDepartures(route, 1))
                .sum();
        double routeDist = totalDepartures > 0 ? weightedRouteDist / totalDepartures : 0.0;
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("routeDist", round(routeDist, 1));
        metrics.put("passenger", Math.round(passenger));
        metrics.put("passengerStrength", operatedKm > 0 ? round(passenger / operatedKm, 3) : 0);
        metrics.put("operatingVehicleKm", round(operatedKm, 2));
        metrics.put("departures", round(departures, 2));
        metrics.put("vehicles", vehicles > 0 ? round(vehicles, 2) : null);
        metrics.put("vehicleIds", new ArrayList<>(vehicleIds));
        metrics.put("perTripFlow", departures > 0 ? round(passenger / departures, 2) : 0);
        metrics.put("perVehicleFlow", vehicles > 0 ? round(passenger / vehicles, 2) : null);
        metrics.put("peakHeadwayMin", weightedRouteMetric(group, true));
        metrics.put("offPeakHeadwayMin", weightedRouteMetric(group, false));
        double peakRateWeighted = 0;
        int peakSamples = 0;
        int peakMissing = groupHasApprovedCapacity(group) ? 0 : 1;
        if (groupHasApprovedCapacity(group)) {
            for (RouteAcc route : group.routes) {
                PeakLoadEstimate peakLoad = peakLoadEstimate(route);
                if (peakLoad.percent() != null) {
                    peakRateWeighted += peakLoad.percent() * peakLoad.samples();
                    peakSamples += peakLoad.samples();
                }
            }
        }
        metrics.put("peakAverageLoadRate", peakSamples > 0 ? round(peakRateWeighted / peakSamples, 2) : null);
        metrics.put("peakDepartureSamples", peakSamples);
        metrics.put("peakMissingCapacityDepartures", peakMissing);
        return metrics;
    }

    private static Object nullableMetric(Double value) {
        return value != null && Double.isFinite(value) && value > 0 ? round(value, 2) : null;
    }

    private static Object weightedRouteMetric(LineGroup group, boolean peak) {
        double weighted = 0;
        double weight = 0;
        for (RouteAcc route : group.routes) {
            Double value = peak ? route.peakHeadwayMin : route.offPeakHeadwayMin;
            double departures = route.declaredDepartures > 0 ? route.declaredDepartures : route.departures;
            if (value != null && value > 0 && departures > 0) {
                weighted += value * departures;
                weight += departures;
            }
        }
        return weight > 0 ? round(weighted / weight, 2) : null;
    }

    /**
     * 真实客流没有逐班次载客轨迹时，按高峰小时最大断面客流与 SHP 计划班次折算。
     * 每个有效高峰小时等权，结果仍保持“最大断面/额定容量”的满载率语义。
     */
    private static PeakLoadEstimate peakLoadEstimate(RouteAcc route) {
        if (route.declaredCapacity <= 0 || route.peakHeadwayMin == null || route.peakHeadwayMin <= 0) {
            return new PeakLoadEstimate(null, 0, route.peakHeadwayMin == null ? 0 : 1);
        }
        double rateSum = 0;
        int samples = 0;
        for (int hour : new int[]{7, 8, 17, 18}) {
            double maxSegment = 0;
            for (SegmentAcc segment : route.segments.values()) maxSegment = Math.max(maxSegment, segment.flow[hour]);
            if (maxSegment <= 0) maxSegment = route.boarding[hour];
            double departures = 60.0 / route.peakHeadwayMin;
            if (departures <= 0) continue;
            rateSum += maxSegment / departures / route.declaredCapacity * 100.0;
            samples++;
        }
        return new PeakLoadEstimate(samples > 0 ? round(rateSum / samples, 2) : null, samples, 0);
    }

    private record PeakLoadEstimate(Double percent, int samples, int missingCapacityDepartures) { }

    private static boolean groupHasApprovedCapacity(LineGroup group) {
        return group != null && !group.routes.isEmpty()
                && group.routes.stream().allMatch(route -> route.declaredCapacity > 0);
    }

    private static boolean groupHasDeclaredVehicles(LineGroup group) {
        return group != null && !group.routes.isEmpty()
                && group.routes.stream().allMatch(route -> route.declaredVehicles > 0);
    }

    private static double declaredVehiclesForGroup(LineGroup group) {
        return group.routes.stream().mapToDouble(route -> route.declaredVehicles).max().orElse(0);
    }

    private static Double averagePeakLoadRate(Dataset data) {
        double weightedRate = 0;
        int samples = 0;
        for (LineGroup group : data.lineGroups.values()) {
            if (!groupHasApprovedCapacity(group)) continue;
            for (RouteAcc route : group.routes) {
                PeakLoadEstimate peakLoad = peakLoadEstimate(route);
                if (peakLoad.percent() == null) continue;
                weightedRate += peakLoad.percent() * peakLoad.samples();
                samples += peakLoad.samples();
            }
        }
        return samples > 0 ? round(weightedRate / samples, 2) : null;
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
        double declaredVehicleTotal = data.lineGroups.values().stream()
                .filter(RealPassengerFlowServiceImpl::groupHasDeclaredVehicles)
                .mapToDouble(RealPassengerFlowServiceImpl::declaredVehiclesForGroup)
                .sum();
        double vehicleAveragePassenger = data.lineGroups.values().stream()
                .filter(RealPassengerFlowServiceImpl::groupHasDeclaredVehicles)
                .mapToDouble(LineGroup::totalBoarding)
                .sum() / data.serviceDays;
        double departures = data.routes.values().stream()
                .mapToDouble(route -> effectiveDepartures(route, 1)).sum() / data.serviceDays;
        double operatedKm = data.routes.values().stream().mapToDouble(route -> route.mileageKm).sum() / data.serviceDays;
        if (operatedKm <= 0) {
            operatedKm = data.routes.values().stream()
                    .mapToDouble(route -> route.routeDistanceMeters / 1000.0 * effectiveDepartures(route, 1)).sum()
                    / data.serviceDays;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("vehicles", declaredVehicleTotal > 0 ? round(declaredVehicleTotal, 2) : null);
        result.put("vehicleAveragePassenger", round(vehicleAveragePassenger, 2));
        result.put("departures", round(departures, 2));
        result.put("operatedKm", round(operatedKm, 2));
        return result;
    }

    private List<Map<String, Object>> operatorOperations(Dataset data) {
        Map<String, OperatorAcc> operators = new LinkedHashMap<>();
        for (LineGroup group : data.lineGroups.values()) {
            RouteAcc representative = group.routes.stream().findFirst().orElse(null);
            if (representative == null) continue;
            String name = safe(representative.company).isBlank() ? "未知企业" : representative.company;
            OperatorAcc item = operators.computeIfAbsent(name, OperatorAcc::new);
            group.routes.forEach(route -> {
                item.passenger += route.totalBoarding();
                item.vehicleIds.addAll(route.vehicleIds);
                item.departures += effectiveDepartures(route, 1);
                item.operatedKm += route.mileageKm;
            });
            if (groupHasDeclaredVehicles(group)) {
                item.vehicleAveragePassenger += group.totalBoarding();
                item.declaredVehicles += declaredVehiclesForGroup(group);
            }
        }
        for (LineGroup group : data.lineGroups.values()) {
            group.unlocatedCompanies.forEach((name, count) ->
                    operators.computeIfAbsent(name, OperatorAcc::new).passenger += count);
        }
        return operators.values().stream()
                .map(item -> item.toMap(data.serviceDays))
                .sorted(Comparator.comparingDouble((Map<String, Object> item) -> ((Number) item.get("passenger")).doubleValue()).reversed())
                .toList();
    }

    private static void requireVehicleIds(RouteAcc route) {
        if (route.vehicleIds.isEmpty() && route.vehicles > 0 && route.declaredVehicles <= 0) {
            throw new BusinessException("线路存在车辆计数但缺少车辆 ID: " + route.authorityId);
        }
    }

    private static double effectiveVehicles(RouteAcc route) {
        return route.declaredVehicles;
    }

    private static double effectiveDepartures(RouteAcc route, int days) {
        if (route.declaredDepartures > 0) return route.declaredDepartures;
        return route.departures / Math.max(1, days);
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

    private static Map<String, Double> mergeDailyFlow(LineGroup group) {
        Map<String, Double> values = new LinkedHashMap<>();
        group.routes.forEach(route -> route.dailyBoarding.forEach(
                (date, count) -> values.merge(date, count, Double::sum)));
        group.unlocatedDailyBoarding.forEach(
                (date, count) -> values.merge(date, count, Double::sum));
        return values;
    }

    private Map<String, Object> routeLeaderboard(Dataset data) {
        List<Map<String, Object>> bus = data.lineGroups.values().stream()
                .sorted(Comparator.comparingDouble(LineGroup::totalBoarding).reversed())
                .limit(50)
                .map(group -> Map.<String, Object>of(
                        "lineId", group.groupId,
                        "lineName", group.lineName,
                        "passengerFlow", average(group.totalBoarding(), data.serviceDays),
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
        return aggregateBuilder.activeRoot(area);
    }

    private Path transitRoot(String area) {
        return matsimConfig.realDataPath(area).resolve(TRANSIT_FOLDER);
    }

    private String signature(Path root, Path transitRoot) {
        StringBuilder value = new StringBuilder();
        for (String file : List.of("总体小时客流.csv", "线路小时客流.csv", UNLOCATED_LINE_GROUP_FLOW, "站点小时客流.csv", "断面小时客流.csv",
                "线路OD日统计.csv", "客群小时统计.csv", "换乘明细.csv", "线路日运营统计.csv", "车辆日运营统计.csv",
                "区间运行时间统计.csv", ACTUAL_DEPARTURES)) {
            appendSignature(value, root.resolve(file));
        }
        appendSignature(value, transitRoot.resolve(STOP_SEQUENCE));
        appendSignature(value, transitRoot.resolve(ROUTE_SHP));
        appendSignature(value, transitRoot.resolve("线路/routes.dbf"));
        appendSignature(value, transitRoot.resolve(ROUTE_DEPARTURES));
        return Integer.toHexString(value.toString().hashCode()) + ":" + value;
    }

    private String fileSignature(Path file) {
        StringBuilder value = new StringBuilder();
        appendSignature(value, file);
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
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                List<String> values = parseCsv(line);
                Map<String, String> row = new HashMap<>(headers.size() * 2);
                for (int index = 0; index < headers.size(); index++) {
                    row.put(headers.get(index), index < values.size() ? values.get(index) : "");
                }
                try {
                    consumer.accept(row);
                } catch (RuntimeException error) {
                    throw new BusinessException("真实客流文件数据无效: " + file.getFileName()
                            + " 第 " + lineNumber + " 行", error);
                }
            }
        } catch (IOException error) {
            throw new BusinessException("读取真实客流文件失败: " + file.getFileName(), error);
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
        String value = text(row, key);
        if (value.isBlank()) {
            throw new IllegalArgumentException("必需数值字段为空: " + key);
        }
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) throw new NumberFormatException("非有限数");
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("数值字段格式错误: " + key + "=" + value, error);
        }
    }

    static double optionalNumber(Map<String, String> row, String key) {
        return parseNumber(text(row, key));
    }

    private static double parseNumber(String value) {
        String text = safe(value);
        if (text.isBlank()) return Double.NaN;
        try {
            double parsed = Double.parseDouble(text);
            if (!Double.isFinite(parsed)) throw new NumberFormatException("非有限数");
            return parsed;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("数值格式错误: " + text, error);
        }
    }

    private static double parseOptionalMetricNumber(String value) {
        String text = safe(value);
        if (text.isBlank() || "/".equals(text) || "-".equals(text) || "--".equals(text)
                || "null".equalsIgnoreCase(text) || "n/a".equalsIgnoreCase(text)) {
            return Double.NaN;
        }
        return parseNumber(text);
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
        if (value < 0 || value >= HOURS) {
            throw new IllegalArgumentException("小时字段超出范围: " + value);
        }
        return value;
    }

    private static int dateTimeHour(String value) {
        String text = safe(value);
        if (text.isBlank()) throw new IllegalArgumentException("日期时间字段为空");
        try {
            return LocalDateTime.parse(text, DATE_TIME).getHour();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("日期时间格式错误: " + text, error);
        }
    }

    private static int dateTimeSeconds(String value) {
        String text = safe(value);
        if (text.isBlank()) return -1;
        try {
            LocalTime time = LocalDateTime.parse(text, DATE_TIME).toLocalTime();
            return time.toSecondOfDay();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("日期时间格式错误: " + text, error);
        }
    }

    static String baseLineName(String value) {
        String text = safe(value);
        int depth = 0;
        int outerStart = -1;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '(' || current == '（') {
                if (depth == 0) outerStart = index;
                depth++;
            } else if ((current == ')' || current == '）') && depth > 0) {
                depth--;
                if (depth == 0 && outerStart >= 0) {
                    String content = text.substring(outerStart + 1, index);
                    if (content.contains("--") || content.contains("—")
                            || content.contains("－") || content.contains("→")
                            || content.contains("至")) {
                        return normalizeNanshaLinePrefix(text.substring(0, outerStart).trim());
                    }
                }
            }
        }
        return normalizeNanshaLinePrefix(text.trim());
    }

    private static String normalizeNanshaLinePrefix(String value) {
        if (value.matches("^(\\d+)路?/南(?:沙)?\\1路?$")) {
            return "南沙" + value.replaceFirst("路?/.*$", "") + "路";
        }
        if (value.startsWith("南沙")) return value;
        if (value.matches("^南(?=\\d|[GKWT夜学旅游]).*")) return "南沙" + value.substring(1);
        return value;
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
        for (double[] source : values) addInto(result, source);
        return result;
    }

    private static void addInto(double[] target, double[] source) {
        for (int hour = 0; hour < HOURS; hour++) target[hour] += source[hour];
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

    private record CachedPanelBundle(String signature, Map<String, Object> bundle) { }

    private record CachedVehicleEvents(String signature, VehicleEvents data) { }

    record VehicleEvents(
            String serviceDate,
            List<List<Object>> events,
            Map<String, Object> meta,
            String source,
            String signature
    ) { }

    private static final class Dataset {
        final String area;
        final String selectedDate;
        final Set<String> dates = new LinkedHashSet<>();
        final Map<String, Double> dailyOverall = new LinkedHashMap<>();
        final double[] overall = new double[HOURS];
        final double[] vehicleActive = new double[HOURS];
        final double[] vehicleSpeedSum = new double[HOURS];
        final double[] vehicleSpeedWeight = new double[HOURS];
        final Set<String> vehicleIds = new LinkedHashSet<>();
        final Map<String, RouteAcc> routes = new LinkedHashMap<>();
        final Map<String, LineGroup> lineGroups = new LinkedHashMap<>();
        final Map<String, UnlocatedLineGroupAcc> unlocatedLineGroups = new LinkedHashMap<>();
        final Map<String, StationAcc> stations = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> departureBundles = new ConcurrentHashMap<>();
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
            for (UnlocatedLineGroupAcc pending : unlocatedLineGroups.values()) {
                LineGroup group = lineGroups.computeIfAbsent(
                        pending.groupId, ignored -> new LineGroup(pending.groupId, pending.lineName));
                addInto(group.unlocatedBoarding, pending.boarding);
                pending.dailyBoarding.forEach(
                        (date, count) -> group.unlocatedDailyBoarding.merge(date, count, Double::sum));
                pending.companies.forEach(
                        (name, count) -> group.unlocatedCompanies.merge(name, count, Double::sum));
            }
            if (Double.isFinite(minLon)) {
                centerLon = (minLon + maxLon) / 2.0;
                centerLat = (minLat + maxLat) / 2.0;
            }
        }
    }

    private static final class RealDepartureAcc {
        final String id;
        final int departureTime;
        final String vehicleId;
        final int boardingCount;
        final Map<Integer, Integer> boardings;
        final Map<Integer, Integer> alightings;
        final Map<Integer, Integer> segmentFlows;
        final Map<String, Double> demographics;

        RealDepartureAcc(
                String id, int departureTime, String vehicleId, int boardingCount,
                Map<Integer, Integer> boardings, Map<Integer, Integer> alightings,
                Map<Integer, Integer> segmentFlows, Map<String, Double> demographics) {
            this.id = id;
            this.departureTime = departureTime;
            this.vehicleId = vehicleId;
            this.boardingCount = Math.max(0, boardingCount);
            this.boardings = boardings;
            this.alightings = alightings;
            this.segmentFlows = segmentFlows;
            this.demographics = demographics;
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
        final List<RealDepartureAcc> actualDepartures = new ArrayList<>();
        final Set<String> vehicleIds = new LinkedHashSet<>();
        double vehicles;
        double departures;
        double declaredVehicles;
        double declaredDepartures;
        double declaredCapacity;
        double mileageKm;
        double runTimeMinutes;
        double speedWeighted;
        double speedWeightMinutes;
        double routeDistanceMeters;
        int firstTime = Integer.MAX_VALUE;
        int lastTime;
        int scheduledFirstTime = -1;
        int scheduledLastTime = -1;
        List<Integer> scheduledDepartures = List.of();
        Double peakHeadwayMin;
        Double offPeakHeadwayMin;
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
        final double[] unlocatedBoarding = new double[HOURS];
        final Map<String, Double> unlocatedDailyBoarding = new LinkedHashMap<>();
        final Map<String, Double> unlocatedCompanies = new LinkedHashMap<>();
        LineGroup(String groupId, String lineName) { this.groupId = groupId; this.lineName = lineName; }
        String key() { return "bus::" + groupId; }
        double totalBoarding() {
            return routes.stream().mapToDouble(RouteAcc::totalBoarding).sum()
                    + Arrays.stream(unlocatedBoarding).sum();
        }
        String operator() {
            Stream<String> located = routes.stream().map(route -> safe(route.company));
            Stream<String> unlocated = unlocatedCompanies.keySet().stream();
            return Stream.concat(located, unlocated).filter(value -> !value.isBlank()).distinct()
                    .reduce((left, right) -> left + " / " + right).orElse("未知企业");
        }
    }

    private static final class UnlocatedLineGroupAcc {
        final String groupId;
        final String lineName;
        final double[] boarding = new double[HOURS];
        final Map<String, Double> dailyBoarding = new LinkedHashMap<>();
        final Map<String, Double> companies = new LinkedHashMap<>();
        UnlocatedLineGroupAcc(String groupId, String lineName) {
            this.groupId = groupId;
            this.lineName = lineName;
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
        double vehicleAveragePassenger;
        final Set<String> vehicleIds = new LinkedHashSet<>();
        double declaredVehicles;
        double departures;
        double operatedKm;
        OperatorAcc(String name) { this.name = name; }
        Map<String, Object> toMap(int days) {
            double divisor = Math.max(1, days);
            double dailyPassenger = passenger / divisor;
            double dailyVehicleAveragePassenger = vehicleAveragePassenger / divisor;
            double dailyVehicles = declaredVehicles;
            double dailyDepartures = departures / divisor;
            double dailyKm = operatedKm / divisor;
            OperationRatios ratios = operationRatios(
                    dailyPassenger, dailyVehicles, dailyDepartures, dailyKm);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name);
            result.put("passenger", Math.round(dailyPassenger));
            result.put("vehicleAveragePassenger", round(dailyVehicleAveragePassenger, 2));
            result.put("vehicles", dailyVehicles > 0 ? round(dailyVehicles, 2) : null);
            result.put("departures", round(dailyDepartures, 2));
            result.put("operatedKm", round(dailyKm, 2));
            result.put("perVehicle", dailyVehicles > 0
                    ? round(dailyVehicleAveragePassenger / dailyVehicles, 2) : null);
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
