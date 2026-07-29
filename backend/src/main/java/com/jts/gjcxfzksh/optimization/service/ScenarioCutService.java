package com.jts.gjcxfzksh.optimization.service;

import com.jts.gjcxfzksh.data.entry.MatsimOutFile;
import com.jts.gjcxfzksh.data.entry.Scheme;
import com.jts.gjcxfzksh.exception.BusinessException;
import com.jts.gjcxfzksh.optimization.model.AreaSpec;
import com.jts.gjcxfzksh.optimization.model.CutResult;
import com.jts.gjcxfzksh.optimization.util.ConfigGroups;
import com.jts.gjcxfzksh.optimization.util.GeoUtil;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.MultimodalNetworkCleaner;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.MinimalTransferTimes;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.utils.objectattributes.attributable.AttributesUtils;
import org.matsim.vehicles.MatsimVehicleReader;
import org.matsim.vehicles.MatsimVehicleWriter;
import org.matsim.vehicles.PersonVehicles;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 场景切分引擎（模式B·几何硬切 + 边界方式锁定）。设计文档 §8。
 *
 * 从母本模型的 output 文件（原始坐标系）出发：
 *  1. 公交线路按区域截断（±1 锚站，时刻表按原经过时刻重建）
 *  2. plans 流式分类（全内/跨界/穿越/无关），跨界与穿越出行在过界点折叠为
 *     outside 虚拟活动（锚定原过界时刻），并划入 s__bLock 锁定子人群
 *     —— 边界处不允许改变出行方式（用户硬性要求 2026-07-03）
 *  3. 路网按 区域∪缓冲带 裁剪 + 保留线路走廊，分模式连通性清理
 *  4. config 派生：锁定子人群仅 ChangeExpBeta+ReRoute，克隆原子人群 scoring；
 *     全部 trip 还原为单主模式 leg 并清空路由，基线与方案统一重路由
 */
@Slf4j
@Service
public class ScenarioCutService {

    public interface Progress {
        void update(int percent, String message);
    }

    private static final GeometryFactory GF = new GeometryFactory();
    private static final String OUTSIDE = ConfigGroups.OUTSIDE_ACT_TYPE;

    public CutResult cut(Scheme parent, AreaSpec area, int iterations, Path outDir, Progress progress) {
        if (parent == null || parent.isLargeModel() || !parent.isCuttable()) {
            throw new BusinessException(parent != null && parent.isLargeModel()
                    ? "大模型仅加载公交精简网络，暂不能作为线网优化母本"
                    : "母本模型缺少可切分的完整 output_plans");
        }
        long start = System.currentTimeMillis();
        CutResult result = new CutResult();
        try {
            Files.createDirectories(outDir);
        } catch (Exception e) {
            throw new BusinessException("创建切分输出目录失败", e);
        }

        progress.update(2, "解析母本模型输出文件");
        MatsimOutFile outfile = MatsimOutFile.reload(parent.getOutput(), parent.getCache());
        if (outfile.getPlans() == null) {
            throw new BusinessException("母本模型缺少 output_plans，无法切分");
        }
        Config parentConfig = ConfigUtils.loadConfig(resolveFullConfig(parent, outfile));

        progress.update(5, "读取母本路网");
        Network network = NetworkUtils.createNetwork();
        new MatsimNetworkReader(network).readFile(outfile.getNetwork());

        progress.update(12, "读取母本公交线网");
        Scenario scheduleScenario = newScenario();
        if (outfile.getTransitSchedule() != null) {
            new TransitScheduleReader(scheduleScenario).readFile(outfile.getTransitSchedule());
        }
        TransitSchedule schedule = scheduleScenario.getTransitSchedule();
        Vehicles transitVehicles = VehicleUtils.createVehiclesContainer();
        if (outfile.getTransitVehicles() != null) {
            new MatsimVehicleReader(transitVehicles).readFile(outfile.getTransitVehicles());
        }

        CrsCtx crs = new CrsCtx(parentConfig, network, schedule, area);

        progress.update(16, "截断公交线路");
        TransitCut transitCut = truncateTransit(schedule, network, crs, result);

        progress.update(20, "流式切分人口出行（可能需要较长时间）");
        Population population = streamPlans(outfile.getPlans(), parentConfig, network, schedule, crs, transitCut, result, progress);

        progress.update(72, "裁剪路网");
        Network cutNetwork = clipNetwork(network, crs, transitCut.keptRouteLinkIds, parentConfig, result);

        progress.update(84, "筛选公交车辆");
        Vehicles cutTransitVehicles = filterTransitVehicles(transitVehicles, transitCut.schedule, result);

        progress.update(88, "写出模型输入文件");
        boolean wrotePersonalVehicles = writePersonalVehicles(outfile, parentConfig, transitCut.personalVehicleIds, outDir);

        new NetworkWriter(cutNetwork).write(outDir.resolve("network.xml.gz").toString());
        new TransitScheduleWriter(transitCut.schedule).writeFile(outDir.resolve("transitSchedule.xml.gz").toString());
        new MatsimVehicleWriter(cutTransitVehicles).writeFile(outDir.resolve("transitVehicles.xml.gz").toString());
        new PopulationWriter(population).write(outDir.resolve("plans.xml.gz").toString());

        progress.update(95, "派生 config");
        deriveConfig(parentConfig, iterations, wrotePersonalVehicles, result);
        new ConfigWriter(parentConfig).write(outDir.resolve("config.xml").toString());

        result.setElapsedMs(System.currentTimeMillis() - start);
        progress.update(100, "切分完成");
        log.info("切分完成: persons internal={}, crossing={}, through={}, dropped={}; lines kept={}, truncated={}, dropped={}; links {} -> {}",
                result.getPersonsInternal(), result.getPersonsCrossing(), result.getPersonsThrough(), result.getPersonsDropped(),
                result.getLinesKept(), result.getLinesTruncated(), result.getLinesDropped(),
                network.getLinks().size(), result.getLinksKept());
        return result;
    }

    private Scenario newScenario() {
        Config cfg = ConfigUtils.createConfig();
        cfg.transit().setUseTransit(true);
        // 关闭读入时的坐标自动转换（保持原始坐标写出）
        cfg.global().setCoordinateSystem(null);
        return ScenarioUtils.createScenario(cfg);
    }

    /**
     * 切分派生必须用完整版 output_config.xml（含全部参数），
     * 而非平台展示用的 output_config_reduced.xml：精简版省略了"恰好等于默认值"的参数，
     * 但保留了参数集本身（如 bike 的 teleportedModeParameters），加载时会把默认值覆盖为空，
     * 导致派生模型无法运行（no teleported mode speed defined）。
     */
    private String resolveFullConfig(Scheme parent, MatsimOutFile outfile) {
        java.io.File dir = new java.io.File(parent.getOutput());
        java.io.File[] files = dir.listFiles(f -> f.isFile() && !f.getName().startsWith(".")
                && f.getName().endsWith(".xml")
                && f.getName().toLowerCase().contains("config")
                && !f.getName().toLowerCase().contains("reduced"));
        if (files != null) {
            java.util.Arrays.sort(files, java.util.Comparator.comparing(java.io.File::getName));
            for (java.io.File f : files) {
                try {
                    ConfigUtils.loadConfig(f.getAbsolutePath());
                    return f.getAbsolutePath();
                } catch (Exception e) {
                    try { // 老版本 config：走平台既有的版本转换
                        String converted = MatsimOutFile.config15to2024(f.getAbsolutePath(), parent.getCache());
                        ConfigUtils.loadConfig(converted);
                        return converted;
                    } catch (Exception ignored) {
                        log.warn("完整 config 加载失败，尝试下一候选: {}", f.getName());
                    }
                }
            }
        }
        log.warn("未找到可用的完整 config，回退精简版: {}", outfile.getConfig());
        // MatsimOutFile 的目录扫描现在延迟解析；真正使用精简配置前仍需触发旧版本兼容转换。
        outfile.loadConfig();
        return outfile.getConfig();
    }

    // ==================== 坐标系上下文 ====================

    private class CrsCtx {
        final String plansCrs;
        final String networkCrs;
        final String scheduleCrs;
        final Polygon polyPlans;          // 不含缓冲（核心区域，plans 坐标系）
        final Geometry zonePlansGeom;     // 含缓冲
        final PreparedGeometry zonePlans;
        final PreparedGeometry zoneNetwork;
        final PreparedGeometry zoneSchedule;
        final CoordinateTransformation networkToPlans;
        final CoordinateTransformation scheduleToPlans;
        final double centerLat;

        CrsCtx(Config config, Network network, TransitSchedule schedule, AreaSpec area) {
            String global = config.global().getCoordinateSystem();
            this.networkCrs = firstNonBlank(
                    (String) network.getAttributes().getAttribute("coordinateReferenceSystem"),
                    config.network().getInputCRS(), global);
            this.scheduleCrs = firstNonBlank(
                    (String) schedule.getAttributes().getAttribute("coordinateReferenceSystem"),
                    config.transit().getInputScheduleCRS(), global);
            this.plansCrs = firstNonBlank(config.plans().getInputCRS(), global);
            if (plansCrs == null || networkCrs == null) {
                throw new BusinessException("母本模型坐标系未知（config.global 未定义），无法切分");
            }
            this.centerLat = RegionStatsService.centroidLat(area);
            this.polyPlans = polygonIn(plansCrs, area, 0);
            Polygon buffered = polygonIn(plansCrs, area, area.getBufferM());
            this.zonePlansGeom = buffered;
            this.zonePlans = GeoUtil.prepare(buffered);
            this.zoneNetwork = GeoUtil.prepare(polygonIn(networkCrs, area, area.getBufferM()));
            this.zoneSchedule = GeoUtil.prepare(polygonIn(scheduleCrs, area, area.getBufferM()));
            this.networkToPlans = transform(networkCrs, plansCrs);
            this.scheduleToPlans = transform(scheduleCrs, plansCrs);
        }

        private Polygon polygonIn(String targetCrs, AreaSpec area, double bufferM) {
            CoordinateTransformation ctf = GeoUtil.wgs84To(targetCrs);
            Polygon polygon = GeoUtil.toPolygon(area.getPolygon(), ctf, false);
            if (bufferM <= 0) {
                return polygon;
            }
            double units = GeoUtil.bufferInCrsUnits(targetCrs, centerLat, bufferM);
            Geometry buffered = polygon.buffer(units);
            return buffered instanceof Polygon p ? p : (Polygon) buffered.convexHull();
        }

        private CoordinateTransformation transform(String from, String to) {
            if (from == null || to == null || from.equalsIgnoreCase(to)) {
                return null;
            }
            try {
                return TransformationFactory.getCoordinateTransformation(from, to);
            } catch (Exception e) {
                log.warn("坐标系转换器创建失败 {} -> {}，按同坐标系处理", from, to);
                return null;
            }
        }

        Coord networkToPlans(Coord c) {
            return networkToPlans == null ? c : networkToPlans.transform(c);
        }

        Coord scheduleToPlans(Coord c) {
            return scheduleToPlans == null ? c : scheduleToPlans.transform(c);
        }

        boolean inPlansZone(Coord c) {
            return c != null && GeoUtil.contains(zonePlans, c.getX(), c.getY());
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    // ==================== 公交截断 ====================

    private static class TransitCut {
        TransitSchedule schedule;
        Set<Id<Link>> keptRouteLinkIds = new HashSet<>();
        Set<Id<Vehicle>> personalVehicleIds = new HashSet<>();
        /** 原线路id/routeId -> 截断后新首站在原 profile 中的索引（用于 pt 过界时间换算） */
        Map<String, Integer> truncateBaseIndex = new HashMap<>();
    }

    private TransitCut truncateTransit(TransitSchedule origin, Network network, CrsCtx crs, CutResult result) {
        TransitCut cut = new TransitCut();
        TransitScheduleFactory factory = origin.getFactory();
        TransitSchedule newSchedule = factory.createTransitSchedule();
        AttributesUtils.copyAttributesFromTo(origin, newSchedule);
        Set<Id<TransitStopFacility>> keptStops = new LinkedHashSet<>();

        for (TransitLine line : origin.getTransitLines().values()) {
            TransitLine newLine = null;
            for (TransitRoute route : line.getRoutes().values()) {
                List<TransitRouteStop> stops = route.getStops();
                int firstIn = -1;
                int lastIn = -1;
                for (int i = 0; i < stops.size(); i++) {
                    Coord c = stops.get(i).getStopFacility().getCoord();
                    if (GeoUtil.contains(crs.zoneSchedule, c.getX(), c.getY())) {
                        if (firstIn < 0) {
                            firstIn = i;
                        }
                        lastIn = i;
                    }
                }
                TransitRoute newRoute = null;
                if (firstIn < 0) {
                    // 无站在区域内：若走向穿过区域则整条保留（无站可截），否则丢弃
                    if (routePassesZone(route, network, crs)) {
                        newRoute = route;
                    }
                } else {
                    int from = Math.max(0, firstIn - 1);
                    int to = Math.min(stops.size() - 1, lastIn + 1);
                    if (from == 0 && to == stops.size() - 1) {
                        newRoute = route; // 全线保留
                    } else {
                        newRoute = truncateRoute(factory, route, from, to, network);
                        if (newRoute != null) {
                            cut.truncateBaseIndex.put(key(line.getId(), route.getId()), from);
                            result.setLinesTruncated(result.getLinesTruncated() + 1);
                        } else {
                            newRoute = route; // 截断失败（数据不一致）→ 整条保留，保守处理
                        }
                    }
                }
                if (newRoute == null) {
                    continue;
                }
                if (newLine == null) {
                    newLine = factory.createTransitLine(line.getId());
                    newLine.setName(line.getName());
                    AttributesUtils.copyAttributesFromTo(line, newLine);
                }
                newLine.addRoute(newRoute);
                result.setRoutesKept(result.getRoutesKept() + 1);
                collectRouteLinks(newRoute, cut.keptRouteLinkIds);
                for (TransitRouteStop s : newRoute.getStops()) {
                    keptStops.add(s.getStopFacility().getId());
                }
            }
            if (newLine != null && !newLine.getRoutes().isEmpty()) {
                newSchedule.addTransitLine(newLine);
                result.setLinesKept(result.getLinesKept() + 1);
            } else {
                result.setLinesDropped(result.getLinesDropped() + 1);
            }
        }

        for (Id<TransitStopFacility> stopId : keptStops) {
            TransitStopFacility facility = origin.getFacilities().get(stopId);
            if (facility != null && newSchedule.getFacilities().get(stopId) == null) {
                newSchedule.addStopFacility(facility);
            }
        }
        copyMinimalTransferTimes(origin, newSchedule, keptStops);
        result.setStopsKept(keptStops.size());
        cut.schedule = newSchedule;
        return cut;
    }

    private boolean routePassesZone(TransitRoute route, Network network, CrsCtx crs) {
        NetworkRoute nr = route.getRoute();
        if (nr == null) {
            return false;
        }
        List<Id<Link>> ids = allRouteLinks(nr);
        for (Id<Link> id : ids) {
            Link link = network.getLinks().get(id);
            if (link == null) {
                continue;
            }
            Coord f = link.getFromNode().getCoord();
            Coord t = link.getToNode().getCoord();
            if (GeoUtil.contains(crs.zoneNetwork, f.getX(), f.getY())
                    || GeoUtil.contains(crs.zoneNetwork, t.getX(), t.getY())) {
                return true;
            }
        }
        return false;
    }

    private TransitRoute truncateRoute(TransitScheduleFactory factory, TransitRoute route, int from, int to, Network network) {
        try {
            List<TransitRouteStop> stops = route.getStops();
            TransitRouteStop firstStop = stops.get(from);
            double base = firstStop.getArrivalOffset()
                    .orElse(firstStop.getDepartureOffset().orElse(0));
            // 不能用 getSubRoute（按 link 首次出现匹配，环线/重复过链会截错）：
            // 按站序推进游标定位每个站的 link 下标，再取闭区间子序列
            List<Id<Link>> full = allRouteLinks(route.getRoute());
            int[] stopLinkIdx = new int[stops.size()];
            int cursor = 0;
            for (int i = 0; i < stops.size(); i++) {
                Id<Link> linkId = stops.get(i).getStopFacility().getLinkId();
                int idx = -1;
                for (int j = cursor; j < full.size(); j++) {
                    if (full.get(j).equals(linkId)) {
                        idx = j;
                        break;
                    }
                }
                if (idx < 0) {
                    log.warn("线路截断失败（站点 link 不在走向序列上），整条保留: route={}, stop={}",
                            route.getId(), stops.get(i).getStopFacility().getId());
                    return null;
                }
                stopLinkIdx[i] = idx;
                cursor = idx;
            }
            List<Id<Link>> subLinks = new ArrayList<>(full.subList(stopLinkIdx[from], stopLinkIdx[to] + 1));
            NetworkRoute sub = org.matsim.core.population.routes.RouteUtils.createNetworkRoute(subLinks);
            List<TransitRouteStop> newStops = new ArrayList<>();
            for (int i = from; i <= to; i++) {
                TransitRouteStop s = stops.get(i);
                double arr = s.getArrivalOffset().orElse(s.getDepartureOffset().orElse(base)) - base;
                double dep = s.getDepartureOffset().orElse(s.getArrivalOffset().orElse(base)) - base;
                TransitRouteStop ns = factory.createTransitRouteStop(s.getStopFacility(), Math.max(0, arr), Math.max(0, dep));
                ns.setAwaitDepartureTime(s.isAwaitDepartureTime());
                newStops.add(ns);
            }
            TransitRoute newRoute = factory.createTransitRoute(route.getId(), sub, newStops, route.getTransportMode());
            newRoute.setDescription(route.getDescription());
            AttributesUtils.copyAttributesFromTo(route, newRoute);
            for (Departure d : route.getDepartures().values()) {
                Departure nd = factory.createDeparture(d.getId(), d.getDepartureTime() + base);
                nd.setVehicleId(d.getVehicleId());
                AttributesUtils.copyAttributesFromTo(d, nd);
                newRoute.addDeparture(nd);
            }
            return newRoute;
        } catch (Exception e) {
            log.warn("线路截断失败，整条保留: route={}, error={}", route.getId(), e.getMessage());
            return null;
        }
    }

    private void collectRouteLinks(TransitRoute route, Set<Id<Link>> target) {
        NetworkRoute nr = route.getRoute();
        if (nr != null) {
            target.addAll(allRouteLinks(nr));
        }
        for (TransitRouteStop stop : route.getStops()) {
            if (stop.getStopFacility().getLinkId() != null) {
                target.add(stop.getStopFacility().getLinkId());
            }
        }
    }

    private static List<Id<Link>> allRouteLinks(NetworkRoute nr) {
        List<Id<Link>> ids = new ArrayList<>(nr.getLinkIds().size() + 2);
        ids.add(nr.getStartLinkId());
        ids.addAll(nr.getLinkIds());
        ids.add(nr.getEndLinkId());
        return ids;
    }

    private void copyMinimalTransferTimes(TransitSchedule origin, TransitSchedule target, Set<Id<TransitStopFacility>> keptStops) {
        try {
            MinimalTransferTimes.MinimalTransferTimesIterator it = origin.getMinimalTransferTimes().iterator();
            while (it.hasNext()) {
                it.next();
                if (keptStops.contains(it.getFromStopId()) && keptStops.contains(it.getToStopId())) {
                    target.getMinimalTransferTimes().set(it.getFromStopId(), it.getToStopId(), it.getSeconds());
                }
            }
        } catch (Exception e) {
            log.warn("最小换乘时间拷贝失败（忽略）: {}", e.getMessage());
        }
    }

    private static String key(Id<TransitLine> lineId, Id<TransitRoute> routeId) {
        return lineId + "||" + routeId;
    }

    // ==================== 人口切分与折叠 ====================

    private Population streamPlans(String plansFile, Config parentConfig, Network network, TransitSchedule schedule,
                                   CrsCtx crs, TransitCut transitCut, CutResult result, Progress progress) {
        Population output = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        PopulationFactory factory = output.getFactory();

        Scenario readScenario = newScenario();
        StreamingPopulationReader reader = new StreamingPopulationReader(readScenario);
        int[] count = {0};
        reader.addAlgorithm(person -> {
            count[0]++;
            if (count[0] % 200_000 == 0) {
                int pct = 20 + (int) Math.min(50, count[0] / 200_000 * 2);
                progress.update(Math.min(70, pct), "已处理 " + count[0] + " 个出行者，保留 "
                        + (result.getPersonsInternal() + result.getPersonsCrossing() + result.getPersonsThrough()));
            }
            try {
                processPerson(person, factory, output, network, schedule, crs, result);
            } catch (Exception e) {
                result.setPersonsDropped(result.getPersonsDropped() + 1);
                if (result.getPersonsDropped() < 20) {
                    log.warn("出行者切分失败已跳过: person={}, error={}", person.getId(), e.getMessage());
                }
            }
        });
        reader.readFile(plansFile);
        result.setPersonsTotal(count[0]);

        // 汇总保留人群引用的私家车
        for (Person p : output.getPersons().values()) {
            Object v = p.getAttributes().getAttribute("vehicles");
            if (v instanceof PersonVehicles pv) {
                transitCut.personalVehicleIds.addAll(pv.getModeVehicles().values());
            }
        }
        // 拷贝 population 级属性（坐标系声明等）
        try {
            AttributesUtils.copyAttributesFromTo(readScenario.getPopulation(), output);
        } catch (Exception ignored) {
        }
        return output;
    }

    private void processPerson(Person person, PopulationFactory factory, Population output,
                               Network network, TransitSchedule schedule, CrsCtx crs, CutResult result) {
        Plan plan = person.getSelectedPlan();
        if (plan == null && !person.getPlans().isEmpty()) {
            plan = person.getPlans().get(0);
        }
        if (plan == null) {
            result.setPersonsDropped(result.getPersonsDropped() + 1);
            return;
        }
        List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(plan);
        List<Activity> acts = realActivities(plan, trips);
        if (acts.isEmpty()) {
            result.setPersonsDropped(result.getPersonsDropped() + 1);
            return;
        }
        boolean[] in = new boolean[acts.size()];
        boolean anyIn = false;
        boolean allIn = true;
        for (int i = 0; i < acts.size(); i++) {
            in[i] = crs.inPlansZone(activityCoord(acts.get(i), network, crs));
            anyIn |= in[i];
            allIn &= in[i];
        }

        Person newPerson;
        if (allIn) {
            newPerson = buildInternalPerson(person, plan, acts, trips, factory, network, crs);
            if (newPerson == null) {
                result.setPersonsDropped(result.getPersonsDropped() + 1);
                return;
            }
            result.setPersonsInternal(result.getPersonsInternal() + 1);
        } else if (anyIn) {
            newPerson = buildCrossingPerson(person, acts, in, trips, factory, network, schedule, crs);
            if (newPerson == null) {
                result.setPersonsDropped(result.getPersonsDropped() + 1);
                return;
            }
            lockPerson(person, newPerson, result);
            result.setPersonsCrossing(result.getPersonsCrossing() + 1);
        } else {
            newPerson = buildThroughPerson(person, acts, trips, factory, network, schedule, crs);
            if (newPerson == null) {
                result.setPersonsDropped(result.getPersonsDropped() + 1);
                return;
            }
            lockPerson(person, newPerson, result);
            result.setPersonsThrough(result.getPersonsThrough() + 1);
        }
        output.addPerson(newPerson);
    }

    private List<Activity> realActivities(Plan plan, List<TripStructureUtils.Trip> trips) {
        List<Activity> acts = new ArrayList<>();
        if (trips.isEmpty()) {
            for (PlanElement el : plan.getPlanElements()) {
                if (el instanceof Activity a) {
                    acts.add(a);
                    break;
                }
            }
            return acts;
        }
        acts.add(trips.get(0).getOriginActivity());
        for (TripStructureUtils.Trip trip : trips) {
            acts.add(trip.getDestinationActivity());
        }
        return acts;
    }

    private Coord activityCoord(Activity act, Network network, CrsCtx crs) {
        if (act.getCoord() != null) {
            return act.getCoord();
        }
        if (act.getLinkId() != null) {
            Link link = network.getLinks().get(act.getLinkId());
            if (link != null) {
                return crs.networkToPlans(link.getToNode().getCoord());
            }
        }
        return null;
    }

    private Person buildInternalPerson(Person origin, Plan plan, List<Activity> acts,
                                       List<TripStructureUtils.Trip> trips, PopulationFactory factory,
                                       Network network, CrsCtx crs) {
        Person newPerson = factory.createPerson(origin.getId());
        AttributesUtils.copyAttributesFromTo(origin, newPerson);
        // 子人群显式化：MATSim 按属性值精确匹配评分/策略，null 不会映射到 default 集
        String subpop = PopulationUtils.getSubpopulation(newPerson);
        if (subpop == null || subpop.isBlank()) {
            newPerson.getAttributes().putAttribute("subpopulation", ConfigGroups.DEFAULT_SUBPOP);
        }
        Plan newPlan = factory.createPlan();
        for (int i = 0; i < acts.size(); i++) {
            Activity cleaned = cleanActivity(acts.get(i), factory, network, crs);
            if (cleaned == null) {
                return null;
            }
            newPlan.addActivity(cleaned);
            if (i < trips.size()) {
                newPlan.addLeg(factory.createLeg(mainMode(trips.get(i))));
            }
        }
        ensureMonotonicTimes(newPlan);
        newPerson.addPlan(newPlan);
        newPerson.setSelectedPlan(newPlan);
        return newPerson;
    }

    private Person buildCrossingPerson(Person origin, List<Activity> acts, boolean[] in,
                                       List<TripStructureUtils.Trip> trips, PopulationFactory factory,
                                       Network network, TransitSchedule schedule, CrsCtx crs) {
        Person newPerson = factory.createPerson(origin.getId());
        AttributesUtils.copyAttributesFromTo(origin, newPerson);
        Plan newPlan = factory.createPlan();
        int n = acts.size();
        int i = 0;
        boolean first = true;
        while (i < n) {
            if (in[i]) {
                Activity cleaned = cleanActivity(acts.get(i), factory, network, crs);
                if (cleaned == null) {
                    return null;
                }
                if (!first) {
                    // 与上一个已输出活动之间的 leg：使用连接 trip 的主模式
                    // （已在输出上一个元素时补 leg，这里不重复）
                }
                newPlan.addActivity(cleaned);
                if (i < trips.size()) {
                    newPlan.addLeg(factory.createLeg(mainMode(trips.get(i))));
                }
                first = false;
                i++;
                continue;
            }
            // 找到连续 out 段 [i..j]
            int j = i;
            while (j + 1 < n && !in[j + 1]) {
                j++;
            }
            Activity outsideAct;
            String legModeAfter = null;
            if (j < n - 1) {
                // 后接区域内活动：以再入 trip 的过界锚点/时刻折叠
                TripStructureUtils.Trip entering = trips.get(j);
                Crossing cr = computeCrossing(entering, acts.get(j), acts.get(j + 1), network, schedule, crs);
                Coord anchor = cr != null && cr.entryAnchor != null ? cr.entryAnchor
                        : fallbackAnchor(acts.get(j), acts.get(j + 1), network, crs);
                Double endTime = cr != null && cr.entryTime != null ? cr.entryTime
                        : activityEndTime(acts.get(j));
                outsideAct = factory.createActivityFromCoord(OUTSIDE, anchor);
                if (endTime != null) {
                    outsideAct.setEndTime(endTime);
                }
                legModeAfter = mainMode(entering);
            } else {
                // 计划尾段在区域外：以离开 trip 的过界锚点折叠（无 endTime）
                TripStructureUtils.Trip exiting = trips.get(i - 1);
                Crossing cr = computeCrossing(exiting, acts.get(i - 1), acts.get(i), network, schedule, crs);
                Coord anchor = cr != null && cr.exitAnchor != null ? cr.exitAnchor
                        : fallbackAnchor(acts.get(i), acts.get(i - 1), network, crs);
                outsideAct = factory.createActivityFromCoord(OUTSIDE, anchor);
            }
            newPlan.addActivity(outsideAct);
            if (legModeAfter != null) {
                newPlan.addLeg(factory.createLeg(legModeAfter));
            }
            first = false;
            i = j + 1;
        }
        pruneTrailingLeg(newPlan);
        ensureMonotonicTimes(newPlan);
        if (countActivities(newPlan) < 2) {
            return null;
        }
        newPerson.addPlan(newPlan);
        newPerson.setSelectedPlan(newPlan);
        return newPerson;
    }

    private Person buildThroughPerson(Person origin, List<Activity> acts, List<TripStructureUtils.Trip> trips,
                                      PopulationFactory factory, Network network, TransitSchedule schedule, CrsCtx crs) {
        // 仅保留第一段穿越（多次穿越的过境出行占比极低，P0 简化）
        for (int t = 0; t < trips.size(); t++) {
            TripStructureUtils.Trip trip = trips.get(t);
            Crossing cr = computeCrossing(trip, acts.get(t), acts.get(t + 1), network, schedule, crs);
            if (cr == null || !cr.passes || cr.entryAnchor == null || cr.exitAnchor == null || cr.entryTime == null) {
                continue;
            }
            Person newPerson = factory.createPerson(origin.getId());
            AttributesUtils.copyAttributesFromTo(origin, newPerson);
            Plan newPlan = factory.createPlan();
            Activity from = factory.createActivityFromCoord(OUTSIDE, cr.entryAnchor);
            from.setEndTime(cr.entryTime);
            newPlan.addActivity(from);
            newPlan.addLeg(factory.createLeg(mainMode(trip)));
            newPlan.addActivity(factory.createActivityFromCoord(OUTSIDE, cr.exitAnchor));
            ensureMonotonicTimes(newPlan);
            newPerson.addPlan(newPlan);
            newPerson.setSelectedPlan(newPlan);
            return newPerson;
        }
        return null;
    }

    private void lockPerson(Person origin, Person newPerson, CutResult result) {
        String orig = PopulationUtils.getSubpopulation(origin);
        String lock = ConfigGroups.lockName(orig);
        newPerson.getAttributes().putAttribute("subpopulation", lock);
        if (orig != null && !orig.isBlank()) {
            newPerson.getAttributes().putAttribute("origSubpopulation", orig);
        }
        result.getLockSubpopulations().putIfAbsent(orig == null ? "" : orig, lock);
    }

    private Activity cleanActivity(Activity act, PopulationFactory factory, Network network, CrsCtx crs) {
        Coord coord = act.getCoord();
        if (coord == null && act.getLinkId() != null) {
            Link link = network.getLinks().get(act.getLinkId());
            if (link != null) {
                coord = crs.networkToPlans(link.getToNode().getCoord());
            }
        }
        if (coord == null) {
            return null;
        }
        Activity cleaned = factory.createActivityFromCoord(act.getType(), coord);
        if (act.getEndTime().isDefined()) {
            cleaned.setEndTime(act.getEndTime().seconds());
        } else if (act.getMaximumDuration().isDefined()) {
            cleaned.setMaximumDuration(act.getMaximumDuration().seconds());
        }
        return cleaned;
    }

    private Double activityEndTime(Activity act) {
        return act.getEndTime().isDefined() ? act.getEndTime().seconds() : null;
    }

    private String mainMode(TripStructureUtils.Trip trip) {
        for (Leg leg : trip.getLegsOnly()) {
            String routingMode = TripStructureUtils.getRoutingMode(leg);
            if (routingMode != null && !routingMode.isBlank()) {
                return routingMode;
            }
        }
        try {
            String m = TripStructureUtils.identifyMainMode(trip.getTripElements());
            if (m != null) {
                return m;
            }
        } catch (Exception ignored) {
        }
        List<Leg> legs = trip.getLegsOnly();
        return legs.isEmpty() ? "walk" : legs.get(legs.size() - 1).getMode();
    }

    private void pruneTrailingLeg(Plan plan) {
        List<PlanElement> els = plan.getPlanElements();
        while (!els.isEmpty() && els.get(els.size() - 1) instanceof Leg) {
            els.remove(els.size() - 1);
        }
    }

    private int countActivities(Plan plan) {
        int c = 0;
        for (PlanElement el : plan.getPlanElements()) {
            if (el instanceof Activity) {
                c++;
            }
        }
        return c;
    }

    private void ensureMonotonicTimes(Plan plan) {
        double last = -1;
        for (PlanElement el : plan.getPlanElements()) {
            if (el instanceof Activity act && act.getEndTime().isDefined()) {
                double end = act.getEndTime().seconds();
                if (end <= last + 60) {
                    end = last + 60;
                    act.setEndTime(end);
                }
                last = end;
            }
        }
    }

    private Coord fallbackAnchor(Activity outsideAct, Activity insideAct, Network network, CrsCtx crs) {
        Coord out = activityCoord(outsideAct, network, crs);
        Coord in = activityCoord(insideAct, network, crs);
        if (out != null && in != null) {
            Coord inter = GeoUtil.firstIntersection(crs.zonePlansGeom, out, in);
            if (inter != null) {
                return inter;
            }
        }
        Coord ref = out != null ? out : in;
        if (ref == null) {
            Coordinate c = crs.zonePlansGeom.getCentroid().getCoordinate();
            return new Coord(c.x, c.y);
        }
        Coordinate[] nearest = DistanceOp.nearestPoints(crs.zonePlansGeom.getBoundary(),
                GF.createPoint(new Coordinate(ref.getX(), ref.getY())));
        return new Coord(nearest[0].x, nearest[0].y);
    }

    // ==================== 过界点/过界时刻 ====================

    private static class Crossing {
        boolean passes;
        Coord entryAnchor;
        Double entryTime;
        Coord exitAnchor;
        Double exitTime;
    }

    private Crossing computeCrossing(TripStructureUtils.Trip trip, Activity fromAct, Activity toAct,
                                     Network network, TransitSchedule schedule, CrsCtx crs) {
        try {
            List<double[]> steps = new ArrayList<>(); // [x, y, time]
            Coord cursor = activityCoord(fromAct, network, crs);
            double time = fromAct.getEndTime().orElse(0);
            if (cursor != null) {
                steps.add(new double[]{cursor.getX(), cursor.getY(), time});
            }
            List<PlanElement> elements = trip.getTripElements();
            for (int i = 0; i < elements.size(); i++) {
                PlanElement el = elements.get(i);
                if (el instanceof Activity stage) {
                    Coord c = activityCoord(stage, network, crs);
                    if (c != null) {
                        cursor = c;
                    }
                    continue;
                }
                Leg leg = (Leg) el;
                double dep = leg.getDepartureTime().orElse(time);
                double travel = leg.getTravelTime()
                        .orElse(leg.getRoute() != null ? leg.getRoute().getTravelTime().orElse(0) : 0);
                Coord next = nextPosition(elements, i, toAct, network, crs);
                if (leg.getRoute() instanceof NetworkRoute nr) {
                    expandNetworkLeg(steps, nr, network, crs, dep, travel);
                } else if (leg.getRoute() instanceof TransitPassengerRoute pr) {
                    expandPtLeg(steps, pr, leg, schedule, crs, dep, travel);
                } else {
                    if (cursor != null && next != null) {
                        steps.add(new double[]{cursor.getX(), cursor.getY(), dep});
                        steps.add(new double[]{next.getX(), next.getY(), dep + travel});
                    }
                }
                time = dep + travel;
                if (next != null) {
                    cursor = next;
                }
            }
            Coord destCoord = activityCoord(toAct, network, crs);
            if (destCoord != null) {
                steps.add(new double[]{destCoord.getX(), destCoord.getY(), time});
            }
            if (steps.size() < 2) {
                return null;
            }
            Crossing crossing = new Crossing();
            int firstIn = -1;
            int lastIn = -1;
            for (int i = 0; i < steps.size(); i++) {
                if (GeoUtil.contains(crs.zonePlans, steps.get(i)[0], steps.get(i)[1])) {
                    if (firstIn < 0) {
                        firstIn = i;
                    }
                    lastIn = i;
                }
            }
            if (firstIn < 0) {
                // 步进点都不在区域内，但线段可能横穿：用相邻步进点连线检测
                for (int i = 1; i < steps.size(); i++) {
                    Coord a = new Coord(steps.get(i - 1)[0], steps.get(i - 1)[1]);
                    Coord b = new Coord(steps.get(i)[0], steps.get(i)[1]);
                    if (GeoUtil.segmentIntersects(crs.zonePlans, a, b)) {
                        crossing.passes = true;
                        Coord inter = GeoUtil.firstIntersection(crs.zonePlansGeom, a, b);
                        crossing.entryAnchor = inter != null ? inter : a;
                        crossing.entryTime = steps.get(i - 1)[2];
                        Coord inter2 = GeoUtil.firstIntersection(crs.zonePlansGeom, b, a);
                        crossing.exitAnchor = inter2 != null ? inter2 : b;
                        crossing.exitTime = steps.get(i)[2];
                        return crossing;
                    }
                }
                return crossing; // passes=false
            }
            crossing.passes = true;
            if (firstIn > 0) {
                crossing.entryAnchor = new Coord(steps.get(firstIn - 1)[0], steps.get(firstIn - 1)[1]);
                crossing.entryTime = steps.get(firstIn - 1)[2];
            }
            if (lastIn < steps.size() - 1) {
                crossing.exitAnchor = new Coord(steps.get(lastIn + 1)[0], steps.get(lastIn + 1)[1]);
                crossing.exitTime = steps.get(lastIn + 1)[2];
            }
            return crossing;
        } catch (Exception e) {
            return null;
        }
    }

    private Coord nextPosition(List<PlanElement> elements, int legIndex, Activity dest, Network network, CrsCtx crs) {
        for (int i = legIndex + 1; i < elements.size(); i++) {
            if (elements.get(i) instanceof Activity a) {
                Coord c = activityCoord(a, network, crs);
                if (c != null) {
                    return c;
                }
            }
        }
        return activityCoord(dest, network, crs);
    }

    private void expandNetworkLeg(List<double[]> steps, NetworkRoute nr, Network network, CrsCtx crs,
                                  double dep, double travel) {
        List<Id<Link>> ids = allRouteLinks(nr);
        List<Link> links = new ArrayList<>(ids.size());
        double freespeedTotal = 0;
        for (Id<Link> id : ids) {
            Link link = network.getLinks().get(id);
            if (link != null) {
                links.add(link);
                freespeedTotal += link.getLength() / Math.max(1, link.getFreespeed());
            }
        }
        if (links.isEmpty()) {
            return;
        }
        double scale = travel > 0 && freespeedTotal > 0 ? travel / freespeedTotal : 1;
        Coord start = crs.networkToPlans(links.get(0).getFromNode().getCoord());
        steps.add(new double[]{start.getX(), start.getY(), dep});
        double t = dep;
        for (Link link : links) {
            t += link.getLength() / Math.max(1, link.getFreespeed()) * scale;
            Coord c = crs.networkToPlans(link.getToNode().getCoord());
            steps.add(new double[]{c.getX(), c.getY(), t});
        }
    }

    private void expandPtLeg(List<double[]> steps, TransitPassengerRoute pr, Leg leg,
                             TransitSchedule schedule, CrsCtx crs, double dep, double travel) {
        TransitLine line = schedule.getTransitLines().get(pr.getLineId());
        TransitRoute route = line == null ? null : line.getRoutes().get(pr.getRouteId());
        if (route == null) {
            return;
        }
        List<TransitRouteStop> stops = route.getStops();
        int accessIdx = -1;
        int egressIdx = -1;
        for (int i = 0; i < stops.size(); i++) {
            Id<TransitStopFacility> fid = stops.get(i).getStopFacility().getId();
            if (accessIdx < 0 && fid.equals(pr.getAccessStopId())) {
                accessIdx = i;
            } else if (accessIdx >= 0 && fid.equals(pr.getEgressStopId())) {
                egressIdx = i;
                break;
            }
        }
        if (accessIdx < 0 || egressIdx < 0) {
            return;
        }
        double board = pr.getBoardingTime().orElse(dep);
        double accessOffset = offsetOf(stops.get(accessIdx));
        double span = Math.max(1, offsetOf(stops.get(egressIdx)) - accessOffset);
        for (int i = accessIdx; i <= egressIdx; i++) {
            double off = offsetOf(stops.get(i)) - accessOffset;
            double t = board + (Double.isNaN(off) ? travel * (i - accessIdx) / Math.max(1, egressIdx - accessIdx) : off);
            Coord c = crs.scheduleToPlans(stops.get(i).getStopFacility().getCoord());
            steps.add(new double[]{c.getX(), c.getY(), t});
        }
    }

    private double offsetOf(TransitRouteStop stop) {
        return stop.getDepartureOffset().orElse(stop.getArrivalOffset().orElse(Double.NaN));
    }

    // ==================== 路网裁剪 ====================

    private Network clipNetwork(Network origin, CrsCtx crs, Set<Id<Link>> keptRouteLinkIds,
                                Config parentConfig, CutResult result) {
        Network target = NetworkUtils.createNetwork();
        AttributesUtils.copyAttributesFromTo(origin, target);
        target.setCapacityPeriod(origin.getCapacityPeriod());
        target.setEffectiveLaneWidth(origin.getEffectiveLaneWidth());
        try {
            target.setEffectiveCellSize(origin.getEffectiveCellSize());
        } catch (Exception ignored) {
        }

        for (Link link : origin.getLinks().values()) {
            Coord f = link.getFromNode().getCoord();
            Coord t = link.getToNode().getCoord();
            boolean keep = keptRouteLinkIds.contains(link.getId())
                    || GeoUtil.contains(crs.zoneNetwork, f.getX(), f.getY())
                    || GeoUtil.contains(crs.zoneNetwork, t.getX(), t.getY())
                    || GeoUtil.segmentIntersects(crs.zoneNetwork, f, t);
            if (!keep) {
                continue;
            }
            Node from = target.getNodes().get(link.getFromNode().getId());
            if (from == null) {
                from = target.getFactory().createNode(link.getFromNode().getId(), link.getFromNode().getCoord());
                AttributesUtils.copyAttributesFromTo(link.getFromNode(), from);
                target.addNode(from);
            }
            Node to = target.getNodes().get(link.getToNode().getId());
            if (to == null) {
                to = target.getFactory().createNode(link.getToNode().getId(), link.getToNode().getCoord());
                AttributesUtils.copyAttributesFromTo(link.getToNode(), to);
                target.addNode(to);
            }
            Link newLink = target.getFactory().createLink(link.getId(), from, to);
            newLink.setLength(link.getLength());
            newLink.setFreespeed(link.getFreespeed());
            newLink.setCapacity(link.getCapacity());
            newLink.setNumberOfLanes(link.getNumberOfLanes());
            Set<String> modes = new HashSet<>(link.getAllowedModes());
            if (keptRouteLinkIds.contains(link.getId())) {
                modes.add("pt"); // 保证连通性清理不会删除公交走廊
            }
            newLink.setAllowedModes(modes);
            AttributesUtils.copyAttributesFromTo(link, newLink);
            target.addLink(newLink);
        }

        // 分模式连通性清理（保持 qsim 主模式路由可行）
        Set<String> mainModes = new HashSet<>();
        try {
            mainModes.addAll(parentConfig.qsim().getMainModes());
        } catch (Exception ignored) {
        }
        mainModes.add("car");
        MultimodalNetworkCleaner cleaner = new MultimodalNetworkCleaner(target);
        for (String mode : mainModes) {
            boolean present = target.getLinks().values().stream().anyMatch(l -> l.getAllowedModes().contains(mode));
            if (present) {
                try {
                    cleaner.run(Set.of(mode));
                } catch (Exception e) {
                    log.warn("路网连通性清理失败 mode={}: {}", mode, e.getMessage());
                }
            }
        }
        cleaner.removeNodesWithoutLinks();

        int missing = 0;
        for (Id<Link> id : keptRouteLinkIds) {
            if (!target.getLinks().containsKey(id)) {
                missing++;
            }
        }
        if (missing > 0) {
            throw new BusinessException("路网裁剪后丢失 " + missing + " 条公交走廊路段，切分中止（请反馈该区域）");
        }
        result.setLinksKept(target.getLinks().size());
        result.setLinksDropped(origin.getLinks().size() - target.getLinks().size());
        result.setNodesKept(target.getNodes().size());
        return target;
    }

    // ==================== 车辆 ====================

    private Vehicles filterTransitVehicles(Vehicles origin, TransitSchedule schedule, CutResult result) {
        Vehicles target = VehicleUtils.createVehiclesContainer();
        Set<Id<Vehicle>> needed = new LinkedHashSet<>();
        for (TransitLine line : schedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                for (Departure d : route.getDepartures().values()) {
                    if (d.getVehicleId() != null) {
                        needed.add(d.getVehicleId());
                    }
                }
            }
        }
        Map<Id<VehicleType>, VehicleType> typesAdded = new TreeMap<>();
        for (Id<Vehicle> vid : needed) {
            Vehicle v = origin.getVehicles().get(vid);
            if (v == null) {
                continue;
            }
            VehicleType type = v.getType();
            if (!typesAdded.containsKey(type.getId())) {
                target.addVehicleType(type);
                typesAdded.put(type.getId(), type);
            }
            target.addVehicle(v);
        }
        result.setTransitVehiclesKept(target.getVehicles().size());
        return target;
    }

    private boolean writePersonalVehicles(MatsimOutFile outfile, Config parentConfig,
                                          Set<Id<Vehicle>> keptIds, Path outDir) {
        try {
            QSimConfigGroup.VehiclesSource source = parentConfig.qsim().getVehiclesSource();
            if (source == QSimConfigGroup.VehiclesSource.defaultVehicle || outfile.getVehicles() == null) {
                return false;
            }
            Vehicles all = VehicleUtils.createVehiclesContainer();
            new MatsimVehicleReader(all).readFile(outfile.getVehicles());
            Vehicles target;
            if (keptIds.isEmpty()) {
                target = all; // 无 person->vehicle 映射信息时整份拷贝（保守）
            } else {
                target = VehicleUtils.createVehiclesContainer();
                Set<Id<VehicleType>> types = new HashSet<>();
                for (Id<Vehicle> id : keptIds) {
                    Vehicle v = all.getVehicles().get(id);
                    if (v == null) {
                        continue;
                    }
                    if (types.add(v.getType().getId())) {
                        target.addVehicleType(v.getType());
                    }
                    target.addVehicle(v);
                }
                if (target.getVehicles().isEmpty()) {
                    target = all;
                }
            }
            new MatsimVehicleWriter(target).writeFile(outDir.resolve("vehicles.xml.gz").toString());
            return true;
        } catch (Exception e) {
            log.warn("私家车辆文件筛选失败，跳过: {}", e.getMessage());
            return false;
        }
    }

    // ==================== config 派生 ====================

    private void deriveConfig(Config config, int iterations, boolean hasPersonalVehicles, CutResult result) {
        // 剔除运行时不存在对应 contrib 的自定义配置组（如 simwrapper、uam），
        // 否则 Controler 会以 "Unmaterialized config group" 中止
        java.util.Set<String> coreModules = ConfigUtils.createConfig().getModules().keySet();
        for (String name : new java.util.ArrayList<>(config.getModules().keySet())) {
            if (!coreModules.contains(name)) {
                log.info("移除派生 config 中的非核心配置组: {}", name);
                config.removeModule(name);
            }
        }

        config.network().setInputFile("network.xml.gz");
        config.plans().setInputFile("plans.xml.gz");
        config.transit().setTransitScheduleFile("transitSchedule.xml.gz");
        config.transit().setVehiclesFile("transitVehicles.xml.gz");
        config.facilities().setInputFile(null);
        try {
            config.counts().setInputFile(null);
        } catch (Exception ignored) {
        }
        try {
            config.households().setInputFile(null);
        } catch (Exception ignored) {
        }
        try {
            config.network().setChangeEventsInputFile(null);
        } catch (Exception ignored) {
        }
        if (hasPersonalVehicles) {
            config.vehicles().setVehiclesFile("vehicles.xml.gz");
        } else {
            config.vehicles().setVehiclesFile(null);
        }

        config.controller().setOutputDirectory("./output");
        config.controller().setLastIteration(iterations);
        config.controller().setRunId(null); // 平台按 output_*.xml.gz 文件名解析结果
        config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
        config.controller().setWriteEventsInterval(Math.max(1, iterations));
        config.controller().setWritePlansInterval(Math.max(1, iterations));

        try {
            config.replanning().setFractionOfIterationsToDisableInnovation(0.8);
        } catch (Exception ignored) {
        }

        // 修复平台 config15to2024 全文替换的副作用：策略名 "TimeAllocationMutator"（大写开头）
        // 会被误改成模块名 "timeAllocationMutator"（平台只读展示从不运行，因此未暴露）
        for (org.matsim.core.config.groups.ReplanningConfigGroup.StrategySettings s
                : config.replanning().getStrategySettings()) {
            String n = s.getStrategyName();
            if (n != null && n.startsWith("timeAllocationMutator")) {
                s.setStrategyName("TimeAllocationMutator" + n.substring("timeAllocationMutator".length()));
            }
        }

        // 边界方式锁定子人群：克隆 scoring + 仅 ChangeExpBeta/ReRoute
        for (Map.Entry<String, String> entry : result.getLockSubpopulations().entrySet()) {
            String orig = entry.getKey().isBlank() ? null : entry.getKey();
            ConfigGroups.addLockSubpopulation(config, orig);
        }
        // 子人群显式化（scoring 默认集与原有策略统一命名 default，与 plans 侧属性对应）
        ConfigGroups.normalizeDefaultSubpopulation(config);
        ConfigGroups.ensureOutsideActivityParams(config);
    }
}
