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
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;
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
import org.matsim.vehicles.Vehicle;
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
    private final ExecutorService trajectoryExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "trajectory-cache-builder");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public Map<String, Object> info(DatasourceParam param) {
        MatsimData matsim_data = matsim_data(param);
        Map<String, Object> cached = MatsimPrecomputedCache.readInfo(matsim_data);
        if (cached != null) {
            return cached;
        }
        Map<String, Object> result = new HashMap<>();
        // 总体水平
        // 常驻人口密度   人*km2
        int personCount = matsim_data.getPersonTracks().stream().collect(Collectors.groupingBy(PTPersonTrack::getPersonId)).size();
        int czrkmd = personCount / (int) matsim_data.getArea();
        result.put("czrkmd", czrkmd);
        // 公交线网密度 km/km2
        double length = ptNetworkLength(matsim_data.getSchedule(), matsim_data.getNetwork());
        double gjxwmd = (length / 1000) / matsim_data.getArea();
        result.put("gjxwmd", NumberUtil.round(gjxwmd, 2).doubleValue());
        // 车站300m覆盖率    %
        Set<Coord> coords = schedule(param).getFacilities().values().stream().map(Facility::getCoord).collect(Collectors.toSet());
        Map<String, Double> fgl_300 = coverage_300(coords, matsim_data.getPopulation());
        result.put("fgl_300", fgl_300);
        // 万人保有量    标台*万人
        // todo
        // 出行分担率    % // pt出行方式占比
        Map<String, Double> fxfdl = legTypeRant(matsim_data.getPopulation());
        result.put("fxfdl", fxfdl);
        // 车均日载客量   人/次
        int vehNum = matsim_data.getPersonTracks().stream().collect(Collectors.groupingBy(PTPersonTrack::getVehicleId)).size();
        int cjrzkl = matsim_data.getPersonTracks().size() / vehNum;
        result.put("cjrzkl", cjrzkl);
        // 单班次载客量   人次*班
        int dbczkl = cjrzkl;
        result.put("dbczkl", dbczkl);
        // 需求强度
        // 公交日出行次数    次*人
        long rcxcs = matsim_data.getPersonTracks().stream()
                .filter(PTPersonTrack::getEnter).count();
        result.put("rcxcs", rcxcs);
        // 依赖客流比例   %
        double ylklbl = 50.;
        result.put("ylklbl", ylklbl);
        // 线路效益
        // 线路非直线系数
        double xlfzxxs = routeNoLC(matsim_data);
        result.put("xlfzxxs", xlfzxxs);
        // 线路重复系数
        double xlcfxs = routeRC(matsim_data); // 线路长度 / 非重复路段长度
        result.put("xlcfxs", xlcfxs);
        // 线路满载率    %
        double xlmzl = fullLoadRate(matsim_data);
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
        result.put("xlklqd_sum", xlklqd.values().stream().mapToDouble(value -> value).sum());
        // 车公里运营成本  元*乘客*km
        // 车单位人次运营成本    元*人次
        // 运营服务
        // 公共汽电车与小汽车运行速度比
        Map<String, Double> yxsdb = runSpeed(matsim_data.getPopulation());
        result.put("yxsdb", yxsdb);
        // 平均候车时间   min
        double[] pjhcsj = avgAwaitTime(matsim_data.getPopulation());
        result.put("pjhcsj", pjhcsj);
        // 场站设施
        // 车均场站面积   m2*标台
        return result;
    }

    @Override
    public PTCoord center(DatasourceParam param) {
        MatsimData matsim_data = matsim_data(param);
        return new PTCoord(matsim_data.getCenter());
    }

    @Override
    public Map<String, Object> trajectory(DatasourceParam param) {
        MatsimData data = matsim_data(param);
        Map<String, Object> manifest = MatsimAnalysisCache.readReadyTrajectoryManifest(data);
        if (manifest != null) {
            return manifest;
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
                    buildState.ready(readyManifest);
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
                    if (Constant.ROUTE_MODE_PT.equals(leg.getMode())) {
                        if (leg.getTravelTime().isDefined() && leg.getRoute() != null) {
                            ptTime += leg.getTravelTime().seconds();
                            ptDist += leg.getRoute().getDistance();
                        }
                    } else if (Constant.ROUTE_MODE_CAR.equals(leg.getMode())) {
                        if (leg.getTravelTime().isDefined() && leg.getRoute() != null) {
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
        result.put("ptAvg", NumberUtil.round(ptAvg, 2).doubleValue());
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
            rc += NetworkUtils.getEuclideanDistance(link.getFromNode().getCoord(), link.getToNode().getCoord());
        }

        return NumberUtil.round(length / rc, 2).doubleValue();
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
        return NumberUtil.round(lc / routeCount, 2).doubleValue(); // 平均值
    }

    // 线路客流强度
    private Map<String, Double> routePersonStrength(MatsimData matsim_data) {
//        double length = 0.;
//        double personCount = 0.;
        Map<String, Double> result = new HashMap<>();
        Map<RouteId, List<PTPersonTrack>> routeIdListMap = matsim_data.getPersonTracks().stream()
                .filter(PTPersonTrack::getEnter)
                .collect(Collectors.groupingBy(PTPersonTrack::getRouteId));

        Map<Id<TransitLine>, TransitLine> transitLines = matsim_data.getSchedule().getTransitLines();
        for (Map.Entry<Id<TransitLine>, TransitLine> line : transitLines.entrySet()) {
            TransitLine transitLine = line.getValue();
            Map<Id<TransitRoute>, TransitRoute> transitRoutes = transitLine.getRoutes();
            for (Map.Entry<Id<TransitRoute>, TransitRoute> route : transitRoutes.entrySet()) {
                TransitRoute transitRoute = route.getValue();
                NetworkRoute networkRoute = transitRoute.getRoute();
                double distance = DistanceUtil.distance(networkRoute, matsim_data.getNetwork());
                List<PTPersonTrack> tracks = routeIdListMap.get(RouteId.create(route.getKey()));
                double p = (tracks == null) ? 0.0 : tracks.size();
                result.put(route.getKey().toString(), p / (distance / 1000));
            }
        }
//        personCount = matsim_data.getPersonTracks().stream().filter(PTPersonTrack::getEnter).count();
//        return personCount / (length / 1000);
        // 只需要数值最多的前5
        return result.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        v -> NumberUtil.round(v.getValue(), 2).doubleValue(),
                        (e1, e2) -> NumberUtil.round(e1, 2).doubleValue(),
                        LinkedHashMap::new
                ));
//        return result;
    }

    private double fullLoadRate(MatsimData matsim_data) {
        Map<VehicleId, List<PTPersonTrack>> person = matsim_data.getPersonTracks().stream().collect(Collectors.groupingBy(PTPersonTrack::getVehicleId));
        Map<Id<Vehicle>, Vehicle> vehicleMap = matsim_data.getTv().getVehicles();
        double vehCount = 0.;
        double personCount = 0.;
        for (Map.Entry<VehicleId, List<PTPersonTrack>> entry : person.entrySet()) {
            Id<Vehicle> vehicleId = entry.getKey();
            List<PTPersonTrack> ptPersonTracks = entry.getValue();
            Vehicle vehicle = vehicleMap.get(vehicleId);
            vehCount += vehicle.getType().getCapacity().getSeats(); // 座位
            vehCount += vehicle.getType().getCapacity().getStandingRoom(); // 站位
            personCount += ptPersonTracks.stream().filter(PTPersonTrack::getEnter).count();
        }
        return NumberUtil.round(personCount / vehCount, 2).multiply(_100).doubleValue();
    }

    private double[] avgAwaitTime(Population population) {
//        double awaitTime = 0;
        double[][] at = new double[24][2];
        for (int i = 0; i < 24; i++) {
            for (int j = 0; j < 2; j++) {
                at[i][j] = 0;
            }
        }
        Map<Id<Person>, ? extends Person> persons = population.getPersons();
        for (Map.Entry<Id<Person>, ? extends Person> entry : persons.entrySet()) {
            List<PlanElement> elements = entry.getValue().getSelectedPlan().getPlanElements();
            for (int i = 0; i < elements.size(); i++) {
                PlanElement element = elements.get(i);
                if (element instanceof Leg leg) {
                    if (Constant.ROUTE_MODE_PT.equals(leg.getMode())) {
                        if (i < 2 || !leg.getDepartureTime().isDefined()) {
                            continue;
                        }
                        Leg l2 = (Leg) elements.get(i - 2);
                        if (!l2.getDepartureTime().isDefined() || !l2.getTravelTime().isDefined()) {
                            continue;
                        }
                        double st = l2.getDepartureTime().seconds() + l2.getTravelTime().seconds();
                        double awaitTime = leg.getDepartureTime().seconds() - st;
                        int ii = (int) (st / 3600);
                        if (ii < 24) {
                            at[ii][0] = awaitTime;
                            at[ii][1]++;
                        }
                    }
                }
            }
        }
        double[] result = new double[24];
        for (int i = 0; i < 24; i++) {
            double t = at[i][0] / at[i][1];
            result[i] = Double.isNaN(t) || Double.isInfinite(t) ? 0.0 : NumberUtil.round(t, 2).doubleValue();
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
        Map<String, Double> typesRant = new HashMap<>();
        BigDecimal c = BigDecimal.valueOf(count);
        for (Map.Entry<String, Integer> entry : types.entrySet()) {
            BigDecimal b = new BigDecimal(entry.getValue());
            BigDecimal v = b.divide(c, 2, RoundingMode.HALF_UP);
            double d = v.multiply(_100).doubleValue(); // %
            typesRant.put(entry.getKey(), d);
        }

//        return pt <= 0 || count <= 0 ? 0 : (double) pt / count;
        return typesRant;
    }

//    public double coverage_300(List<Coord> coords, double area) {
//        final double R = 300.0;
//        final double R_SQ = R * R;
//
//        // 只采样圆的边界框内的点
//        double totalArea = 0;
//        Set<Long> sampledPoints = new HashSet<>();
//        double step = 15.0; // 步长15米，平衡速度和精度
//
//        for (Coord c : coords) {
//            // 确定这个圆的覆盖范围
//            int minX = (int) Math.floor((c.getX() - R) / step);
//            int maxX = (int) Math.ceil((c.getX() + R) / step);
//            int minY = (int) Math.floor((c.getY() - R) / step);
//            int maxY = (int) Math.ceil((c.getY() + R) / step);
//
//            for (int ix = minX; ix <= maxX; ix++) {
//                for (int iy = minY; iy <= maxY; iy++) {
//                    long key = ((long) ix << 32) | (iy & 0xFFFFFFFFL);
//                    if (sampledPoints.add(key)) {
//                        double px = ix * step;
//                        double py = iy * step;
//                        // 检查是否被任何圆覆盖（使用空间索引快速判断）
//                        if (isCoveredByAnyCircle(px, py, coords, R_SQ)) {
//                            totalArea += step * step;
//                        }
//                    }
//                }
//            }
//        }
//
//        return Math.min(1.0, totalArea / area);
//    }

//    private boolean isCoveredByAnyCircle(double x, double y, List<Coord> coords, double rSq) {
//        for (Coord c : coords) {
//            double dx = c.getX() - x;
//            double dy = c.getY() - y;
//            if (dx * dx + dy * dy <= rSq) return true;
//        }
//        return false;
//    }


    public Map<String, Double> coverage_300(Set<Coord> coords, Population population) {
        Map<String, Double> coverage = new HashMap<>();
        coverage.put("cover", 50.);
        coverage.put("notcover", 50.);

        if (coords == null || coords.isEmpty()) return coverage;
        if (population == null) return coverage;

        // 1. 构建空间索引
        STRtree spatialIndex = new STRtree();
        for (Coord c : coords) {
            Envelope env = new Envelope(
                    c.getX() - 300, c.getX() + 300,
                    c.getY() - 300, c.getY() + 300
            );
            spatialIndex.insert(env, c);
        }
        spatialIndex.build();

        // 2. 并行计算
        long total = population.getPersons().size();
        long in = population.getPersons().entrySet().parallelStream()
                .filter(entry -> personInCoverage(entry.getValue(), spatialIndex))
                .count();

        // 3. 计算比例
        double coverRatio = NumberUtil.round((total - in) * 100.0 / total, 2).doubleValue();
        coverage.put("cover", coverRatio);
        coverage.put("notcover", 100.0 - coverRatio);
        return coverage;
    }

    private boolean personInCoverage(Person person, STRtree index) {
        List<PlanElement> elements = person.getSelectedPlan().getPlanElements();
        for (PlanElement element : elements) {
            if (element instanceof Activity act) {
                Coord acoord = act.getCoord();
                if (acoord != null && isWithin300(acoord, index)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isWithin300(Coord coord, STRtree index) {
        Envelope searchEnv = new Envelope(
                coord.getX() - 300, coord.getX() + 300,
                coord.getY() - 300, coord.getY() + 300
        );
        @SuppressWarnings("unchecked")
        List<Coord> candidates = index.query(searchEnv);
        for (Coord c : candidates) {
            if (NetworkUtils.getEuclideanDistance(c, coord) <= 300.0) {
                return true;
            }
        }
        return false;
    }

    // 构建空间索引
//    private STRtree buildSpatialIndex(Set<Coord> coords) {
//        STRtree tree = new STRtree();
//        for (Coord c : coords) {
//            Envelope env = new Envelope(
//                    c.getX() - 300, c.getX() + 300,
//                    c.getY() - 300, c.getY() + 300
//            );
//            tree.insert(env, c);
//        }
//        tree.build();
//        return tree;
//    }
}
