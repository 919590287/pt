package com.jts.gjcxfzksh.optimization.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jts.gjcxfzksh.exception.BusinessException;
import com.jts.gjcxfzksh.optimization.model.EditItem;
import com.jts.gjcxfzksh.optimization.model.ValidationIssue;
import com.jts.gjcxfzksh.optimization.util.GeoUtil;
import com.jts.gjcxfzksh.optimization.util.ScheduleTools;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.MultimodalNetworkCleaner;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.pt.transitSchedule.api.Departure;
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
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 修改应用引擎：把草稿修改项逐一应用到"切分后的基线输入"，产出方案模型输入。
 * 应用顺序固定（EditItem.APPLY_ORDER）：加路 → 站点 → 线路 → 运营 → 改路 → 删路。
 * plans / config 与基线保持逐字节一致（只有 network / transitSchedule / transitVehicles 不同）。
 */
@Slf4j
@Service
public class EditApplyService {

    @Data
    public static class ApplyOutcome {
        private List<ValidationIssue> issues = new ArrayList<>();
        private List<String> applied = new ArrayList<>();

        public boolean hasError() {
            return issues.stream().anyMatch(i -> ValidationIssue.ERROR.equals(i.getLevel()));
        }
    }

    private static class Ctx {
        Network network;
        TransitSchedule schedule;
        Vehicles transitVehicles;
        CoordinateTransformation toNetworkCrs;   // WGS84 -> 网络坐标系
        CoordinateTransformation toScheduleCrs;  // WGS84 -> 公交坐标系
        ApplyOutcome outcome;
    }

    public ApplyOutcome apply(Path baselineDir, Path variantDir, List<EditItem> edits) {
        copyBaseline(baselineDir, variantDir);

        Config config = ConfigUtils.loadConfig(variantDir.resolve("config.xml").toString());
        Ctx ctx = new Ctx();
        ctx.outcome = new ApplyOutcome();
        ctx.network = NetworkUtils.createNetwork();
        new MatsimNetworkReader(ctx.network).readFile(variantDir.resolve("network.xml.gz").toString());
        Scenario scheduleScenario = newScenario();
        new TransitScheduleReader(scheduleScenario).readFile(variantDir.resolve("transitSchedule.xml.gz").toString());
        ctx.schedule = scheduleScenario.getTransitSchedule();
        ctx.transitVehicles = VehicleUtils.createVehiclesContainer();
        new MatsimVehicleReader(ctx.transitVehicles).readFile(variantDir.resolve("transitVehicles.xml.gz").toString());

        String global = config.global().getCoordinateSystem();
        String networkCrs = firstNonBlank((String) ctx.network.getAttributes().getAttribute("coordinateReferenceSystem"),
                config.network().getInputCRS(), global);
        String scheduleCrs = firstNonBlank((String) ctx.schedule.getAttributes().getAttribute("coordinateReferenceSystem"),
                config.transit().getInputScheduleCRS(), global);
        ctx.toNetworkCrs = GeoUtil.wgs84To(networkCrs);
        ctx.toScheduleCrs = GeoUtil.wgs84To(scheduleCrs);

        List<EditItem> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt(EditItem::applyOrder));
        for (EditItem edit : ordered) {
            try {
                dispatch(ctx, edit);
            } catch (BusinessException e) {
                ctx.outcome.getIssues().add(ValidationIssue.error(edit.getId(),
                        "[" + editLabel(edit) + "] " + e.getMessage()));
                return ctx.outcome; // 阻断：一致性无法保证
            } catch (Exception e) {
                log.error("修改项应用失败: {}", edit.getId(), e);
                ctx.outcome.getIssues().add(ValidationIssue.error(edit.getId(),
                        "[" + editLabel(edit) + "] 应用失败: " + e.getMessage()));
                return ctx.outcome;
            }
        }

        postCleanNetwork(ctx);
        validateFinal(ctx);
        if (ctx.outcome.hasError()) {
            return ctx.outcome;
        }

        new NetworkWriter(ctx.network).write(variantDir.resolve("network.xml.gz").toString());
        new TransitScheduleWriter(ctx.schedule).writeFile(variantDir.resolve("transitSchedule.xml.gz").toString());
        new MatsimVehicleWriter(ctx.transitVehicles).writeFile(variantDir.resolve("transitVehicles.xml.gz").toString());
        return ctx.outcome;
    }

    private Scenario newScenario() {
        Config cfg = ConfigUtils.createConfig();
        cfg.transit().setUseTransit(true);
        cfg.global().setCoordinateSystem(null);
        return ScenarioUtils.createScenario(cfg);
    }

    private void copyBaseline(Path baselineDir, Path variantDir) {
        try {
            Files.createDirectories(variantDir);
            try (Stream<Path> files = Files.list(baselineDir)) {
                for (Path file : files.toList()) {
                    if (Files.isRegularFile(file)) {
                        Files.copy(file, variantDir.resolve(file.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (IOException e) {
            throw new BusinessException("复制基线输入失败", e);
        }
    }

    private void dispatch(Ctx ctx, EditItem edit) {
        switch (edit.getKind()) {
            case "link.add" -> applyLinkAdd(ctx, edit);
            case "link.modify" -> applyLinkModify(ctx, edit);
            case "link.delete" -> applyLinkDelete(ctx, edit);
            case "stop.add" -> applyStopAdd(ctx, edit);
            case "stop.move" -> applyStopMove(ctx, edit);
            case "stop.delete" -> applyStopDelete(ctx, edit);
            case "route.add" -> applyRouteAdd(ctx, edit);
            case "route.modify.alignment" -> applyRouteAlignment(ctx, edit);
            case "route.modify.stops" -> applyRouteStops(ctx, edit);
            case "route.delete" -> applyRouteDelete(ctx, edit);
            case "ops.headway", "ops.serviceHours" -> applyHeadway(ctx, edit);
            case "ops.vehicleType" -> applyVehicleType(ctx, edit);
            default -> throw new BusinessException("暂不支持的修改类型: " + edit.getKind());
        }
    }

    private String editLabel(EditItem edit) {
        return (edit.getName() == null || edit.getName().isBlank()) ? edit.getKind() : edit.getName();
    }

    // ==================== 路网类 ====================

    private void applyLinkAdd(Ctx ctx, EditItem edit) {
        JSONObject geometry = required(edit.getGeometry(), "geometry");
        JSONArray coords = geometry.getJSONArray("coords");
        if (coords == null || coords.size() < 2) {
            throw new BusinessException("新增路段至少需要2个坐标点");
        }
        JSONObject params = edit.getParams() == null ? new JSONObject() : edit.getParams();
        boolean bidirectional = !Boolean.FALSE.equals(params.getBoolean("bidirectional"));
        double lanes = dbl(params, "lanes", 2);
        double freespeed = dbl(params, "freespeedKmh", 40) / 3.6;
        double capacity = dbl(params, "capacityPerLane", 1200) * lanes;
        Set<String> modes = new HashSet<>();
        JSONArray modesArr = params.getJSONArray("modes");
        if (modesArr != null) {
            for (int i = 0; i < modesArr.size(); i++) {
                modes.add(modesArr.getString(i));
            }
        }
        if (modes.isEmpty()) {
            modes.add("car");
        }
        modes.add("pt");

        String fromNodeId = geometry.getString("fromNodeId");
        String toNodeId = geometry.getString("toNodeId");
        int segments = coords.size() - 1;
        int created = 0;
        Node prev = null;
        for (int i = 0; i < coords.size(); i++) {
            JSONArray pt = coords.getJSONArray(i);
            double lng = pt.getDoubleValue(0);
            double lat = pt.getDoubleValue(1);
            Node node;
            if (i == 0 && fromNodeId != null && !fromNodeId.isBlank()) {
                node = requireNode(ctx.network, fromNodeId);
            } else if (i == coords.size() - 1 && toNodeId != null && !toNodeId.isBlank()) {
                node = requireNode(ctx.network, toNodeId);
            } else {
                Id<Node> nid = Id.createNodeId("opt_n_" + edit.getId() + "_" + i);
                node = ctx.network.getNodes().get(nid);
                if (node == null) {
                    Coord c = transform(ctx.toNetworkCrs, lng, lat);
                    node = ctx.network.getFactory().createNode(nid, c);
                    ctx.network.addNode(node);
                }
            }
            if (prev != null) {
                JSONArray prevPt = coords.getJSONArray(i - 1);
                double lengthM = Math.max(10, GeoUtil.haversine(prevPt.getDoubleValue(1), prevPt.getDoubleValue(0), lat, lng));
                addLink(ctx.network, "opt_l_" + edit.getId() + "_" + (i - 1), prev, node, lengthM, freespeed, capacity, lanes, modes);
                created++;
                if (bidirectional) {
                    addLink(ctx.network, "opt_l_" + edit.getId() + "_" + (i - 1) + "_r", node, prev, lengthM, freespeed, capacity, lanes, modes);
                    created++;
                }
            }
            prev = node;
        }
        ctx.outcome.getApplied().add("新增路段 " + editLabel(edit) + "：" + segments + " 段（" + created + " 条有向 link）");
    }

    private void addLink(Network network, String id, Node from, Node to, double length,
                         double freespeed, double capacity, double lanes, Set<String> modes) {
        Id<Link> linkId = Id.createLinkId(id);
        if (network.getLinks().containsKey(linkId)) {
            throw new BusinessException("路段 id 冲突: " + id);
        }
        Link link = network.getFactory().createLink(linkId, from, to);
        link.setLength(length);
        link.setFreespeed(freespeed);
        link.setCapacity(capacity);
        link.setNumberOfLanes(lanes);
        link.setAllowedModes(modes);
        network.addLink(link);
    }

    private void applyLinkModify(Ctx ctx, EditItem edit) {
        List<Id<Link>> linkIds = targetLinkIds(edit);
        JSONObject params = required(edit.getParams(), "params");
        int changed = 0;
        for (Id<Link> id : linkIds) {
            Link link = ctx.network.getLinks().get(id);
            if (link == null) {
                ctx.outcome.getIssues().add(ValidationIssue.warning(edit.getId(), "路段不存在（可能不在切分范围内）: " + id));
                continue;
            }
            if (params.containsKey("freespeedKmh")) {
                link.setFreespeed(Math.max(1, dbl(params, "freespeedKmh", 40)) / 3.6);
            }
            if (params.containsKey("lanes")) {
                double lanes = Math.max(0.5, dbl(params, "lanes", link.getNumberOfLanes()));
                if (params.containsKey("capacityPerLane")) {
                    link.setCapacity(dbl(params, "capacityPerLane", 1200) * lanes);
                } else {
                    link.setCapacity(link.getCapacity() / Math.max(0.5, link.getNumberOfLanes()) * lanes);
                }
                link.setNumberOfLanes(lanes);
            } else if (params.containsKey("capacityPerLane")) {
                link.setCapacity(dbl(params, "capacityPerLane", 1200) * Math.max(1, link.getNumberOfLanes()));
            } else if (params.containsKey("capacityTotal")) {
                link.setCapacity(Math.max(100, dbl(params, "capacityTotal", link.getCapacity())));
            }
            JSONArray modesArr = params.getJSONArray("modes");
            if (modesArr != null && !modesArr.isEmpty()) {
                Set<String> modes = new HashSet<>();
                for (int i = 0; i < modesArr.size(); i++) {
                    modes.add(modesArr.getString(i));
                }
                if (usedByTransit(ctx.schedule, id)) {
                    modes.add("pt");
                }
                link.setAllowedModes(modes);
            }
            changed++;
        }
        ctx.outcome.getApplied().add("修改路段属性：" + changed + " 条");
    }

    private void applyLinkDelete(Ctx ctx, EditItem edit) {
        List<Id<Link>> linkIds = targetLinkIds(edit);
        for (Id<Link> id : linkIds) {
            List<String> users = transitUsers(ctx.schedule, id);
            if (!users.isEmpty()) {
                throw new BusinessException("路段 " + id + " 仍被线路使用：" + String.join("、", users.subList(0, Math.min(5, users.size())))
                        + "，请先调整这些线路的走向或删除线路");
            }
        }
        int removed = 0;
        for (Id<Link> id : linkIds) {
            if (ctx.network.removeLink(id) != null) {
                removed++;
            }
        }
        ctx.outcome.getApplied().add("删除路段：" + removed + " 条");
    }

    // ==================== 站点类 ====================

    private void applyStopAdd(Ctx ctx, EditItem edit) {
        JSONObject geometry = required(edit.getGeometry(), "geometry");
        JSONArray coord = geometry.getJSONArray("coord");
        String linkIdStr = geometry.getString("linkId");
        if (coord == null || coord.size() < 2 || linkIdStr == null) {
            throw new BusinessException("新增站点缺少坐标或吸附路段");
        }
        Id<Link> linkId = Id.createLinkId(linkIdStr);
        if (!ctx.network.getLinks().containsKey(linkId)) {
            throw new BusinessException("站点吸附路段不存在: " + linkIdStr);
        }
        Id<TransitStopFacility> stopId = Id.create(stopIdOf(edit.getId()), TransitStopFacility.class);
        if (ctx.schedule.getFacilities().containsKey(stopId)) {
            throw new BusinessException("站点 id 冲突: " + stopId);
        }
        Coord c = transform(ctx.toScheduleCrs, coord.getDoubleValue(0), coord.getDoubleValue(1));
        TransitStopFacility facility = ctx.schedule.getFactory().createTransitStopFacility(stopId, c, false);
        facility.setName(edit.getParams() == null ? null : edit.getParams().getString("name"));
        facility.setLinkId(linkId);
        ctx.schedule.addStopFacility(facility);
        ctx.outcome.getApplied().add("新增站点 " + editLabel(edit));
    }

    public static String stopIdOf(String editId) {
        return "opt_s_" + editId;
    }

    private void applyStopMove(Ctx ctx, EditItem edit) {
        String stopId = required(edit.getTarget(), "target").getString("stopId");
        TransitStopFacility facility = requireStop(ctx.schedule, stopId);
        JSONObject geometry = required(edit.getGeometry(), "geometry");
        JSONArray coord = geometry.getJSONArray("coord");
        if (coord != null && coord.size() >= 2) {
            facility.setCoord(transform(ctx.toScheduleCrs, coord.getDoubleValue(0), coord.getDoubleValue(1)));
        }
        String newLink = geometry.getString("linkId");
        if (newLink != null && !newLink.isBlank()) {
            Id<Link> linkId = Id.createLinkId(newLink);
            if (!ctx.network.getLinks().containsKey(linkId)) {
                throw new BusinessException("站点吸附路段不存在: " + newLink);
            }
            if (!linkId.equals(facility.getLinkId()) && usedByTransit(ctx.schedule, facility)) {
                ctx.outcome.getIssues().add(ValidationIssue.warning(edit.getId(),
                        "站点 " + display(facility) + " 移动跨越路段，经过线路的走向未变（仅停靠点位变化）"));
            }
            facility.setLinkId(linkId);
        }
        if (edit.getParams() != null && edit.getParams().getString("name") != null) {
            facility.setName(edit.getParams().getString("name"));
        }
        ctx.outcome.getApplied().add("修改站点 " + display(facility));
    }

    private void applyStopDelete(Ctx ctx, EditItem edit) {
        String stopId = required(edit.getTarget(), "target").getString("stopId");
        TransitStopFacility facility = requireStop(ctx.schedule, stopId);
        int affected = 0;
        for (TransitLine line : ctx.schedule.getTransitLines().values()) {
            for (TransitRoute route : new ArrayList<>(line.getRoutes().values())) {
                if (route.getStops().stream().noneMatch(s -> s.getStopFacility().getId().equals(facility.getId()))) {
                    continue;
                }
                List<TransitRouteStop> remaining = route.getStops().stream()
                        .filter(s -> !s.getStopFacility().getId().equals(facility.getId()))
                        .toList();
                if (remaining.size() < 2) {
                    throw new BusinessException("删除站点后线路 " + line.getId() + "/" + route.getId()
                            + " 停靠站不足2个，请先删除该线路");
                }
                replaceRoute(ctx, line, route, remaining, route.getRoute());
                affected++;
            }
        }
        ctx.schedule.removeStopFacility(facility);
        ctx.outcome.getApplied().add("删除站点 " + display(facility) + "（" + affected + " 条线路改为跳站）");
    }

    // ==================== 线路类 ====================

    private void applyRouteAdd(Ctx ctx, EditItem edit) {
        JSONObject geometry = required(edit.getGeometry(), "geometry");
        JSONArray directions = geometry.getJSONArray("directions");
        if (directions == null || directions.isEmpty()) {
            throw new BusinessException("新增线路缺少走向数据");
        }
        JSONObject params = required(edit.getParams(), "params");
        String name = params.getString("name");
        if (name == null || name.isBlank()) {
            throw new BusinessException("请填写线路名称");
        }
        String transportMode = firstNonBlank(params.getString("transportMode"), dominantTransportMode(ctx.schedule), "bus");
        double opSpeed = dbl(params, "opSpeedKmh", 20);
        double dwell = dbl(params, "dwellSec", 30);
        JSONArray slots = params.getJSONArray("slots");
        VehicleType vehicleType = ScheduleTools.resolveVehicleType(ctx.transitVehicles, params.getJSONObject("vehicleType"), edit.getId());

        TransitScheduleFactory factory = ctx.schedule.getFactory();
        Id<TransitLine> lineId = Id.create("opt_line_" + edit.getId(), TransitLine.class);
        if (ctx.schedule.getTransitLines().containsKey(lineId)) {
            throw new BusinessException("线路 id 冲突: " + lineId);
        }
        TransitLine line = factory.createTransitLine(lineId);
        line.setName(name);

        List<Double> departureTimes = ScheduleTools.expandDepartureTimes(slots);
        int totalDepartures = 0;
        for (int d = 0; d < directions.size(); d++) {
            JSONObject direction = directions.getJSONObject(d);
            List<Id<Link>> linkIds = toLinkIds(direction.getJSONArray("linkIds"));
            List<TransitStopFacility> stops = resolveStopsOnPath(ctx, edit, direction.getJSONArray("stops"), linkIds);
            if (stops.size() < 2) {
                throw new BusinessException("方向" + (d + 1) + "停靠站不足2个");
            }
            ensureTransitModes(ctx.network, linkIds, transportMode);
            TransitRoute route = buildRoute(ctx, factory,
                    Id.create("opt_r_" + edit.getId() + "_d" + d, TransitRoute.class),
                    transportMode, linkIds, stops, opSpeed, dwell);
            line.addRoute(route);
            totalDepartures += ScheduleTools.rebuildDepartures(ctx.schedule, ctx.transitVehicles, line, route,
                    departureTimes, vehicleType, edit.getId() + "_d" + d);
        }
        ctx.schedule.addTransitLine(line);
        ctx.outcome.getApplied().add("新增线路 " + name + "：" + directions.size() + " 个方向，共 " + totalDepartures + " 班次");
    }

    private void applyRouteAlignment(Ctx ctx, EditItem edit) {
        RouteRef ref = requireRoute(ctx, edit);
        JSONObject geometry = required(edit.getGeometry(), "geometry");
        List<Id<Link>> linkIds = toLinkIds(geometry.getJSONArray("linkIds"));
        List<TransitStopFacility> stops = resolveStopsOnPath(ctx, edit, geometry.getJSONArray("stops"), linkIds);
        if (stops.size() < 2) {
            throw new BusinessException("调整后停靠站不足2个");
        }
        JSONObject params = edit.getParams() == null ? new JSONObject() : edit.getParams();
        double opSpeed = dbl(params, "opSpeedKmh", 20);
        double dwell = dbl(params, "dwellSec", 30);
        ensureTransitModes(ctx.network, linkIds, ref.route.getTransportMode());

        TransitScheduleFactory factory = ctx.schedule.getFactory();
        int[] stopLinkIdx = stopLinkIndexes(linkIds, stops);
        double[][] offsets = ScheduleTools.computeOffsets(linkIds, stopLinkIdx, ctx.network, opSpeed, dwell);
        List<TransitRouteStop> profile = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            profile.add(factory.createTransitRouteStop(stops.get(i), offsets[i][0], offsets[i][1]));
        }
        replaceRoute(ctx, ref.line, ref.route, profile, RouteUtils.createNetworkRoute(linkIds));
        ctx.outcome.getApplied().add("调整线路走向 " + editLabel(edit) + "：" + stops.size() + " 站");
    }

    private void applyRouteStops(Ctx ctx, EditItem edit) {
        RouteRef ref = requireRoute(ctx, edit);
        JSONArray stopsArr = required(edit.getParams(), "params").getJSONArray("stops");
        if (stopsArr == null || stopsArr.size() < 2) {
            throw new BusinessException("调整后停靠站不足2个");
        }
        List<Id<Link>> routeLinks = allRouteLinks(ref.route.getRoute());
        List<TransitStopFacility> stops = resolveStopsOnPath(ctx, edit, stopsArr, routeLinks);

        // 原有站保留原时分；新增站按沿线位置在相邻保留站间插值
        Map<Id<TransitStopFacility>, TransitRouteStop> oldByFacility = new java.util.HashMap<>();
        for (TransitRouteStop s : ref.route.getStops()) {
            oldByFacility.putIfAbsent(s.getStopFacility().getId(), s);
        }
        int[] idx = stopLinkIndexes(routeLinks, stops);
        double[] arr = new double[stops.size()];
        double[] dep = new double[stops.size()];
        for (int i = 0; i < stops.size(); i++) {
            TransitRouteStop old = oldByFacility.get(stops.get(i).getId());
            if (old != null) {
                arr[i] = old.getArrivalOffset().orElse(old.getDepartureOffset().orElse(0));
                dep[i] = old.getDepartureOffset().orElse(arr[i]);
            } else {
                arr[i] = Double.NaN;
                dep[i] = Double.NaN;
            }
        }
        interpolateOffsets(arr, dep, idx);
        TransitScheduleFactory factory = ctx.schedule.getFactory();
        List<TransitRouteStop> profile = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            profile.add(factory.createTransitRouteStop(stops.get(i), arr[i], dep[i]));
        }
        replaceRoute(ctx, ref.line, ref.route, profile, ref.route.getRoute());
        ctx.outcome.getApplied().add("调整停靠站 " + editLabel(edit) + "：" + stops.size() + " 站");
    }

    private void applyRouteDelete(Ctx ctx, EditItem edit) {
        JSONObject target = required(edit.getTarget(), "target");
        String lineIdStr = target.getString("lineId");
        TransitLine line = ctx.schedule.getTransitLines().get(Id.create(lineIdStr, TransitLine.class));
        if (line == null) {
            ctx.outcome.getIssues().add(ValidationIssue.warning(edit.getId(), "线路不存在（可能不在切分范围内）: " + lineIdStr));
            return;
        }
        JSONArray routeIds = target.getJSONArray("routeIds");
        Set<Id<Vehicle>> candidates = new LinkedHashSet<>();
        if (routeIds == null || routeIds.isEmpty()) {
            for (TransitRoute route : line.getRoutes().values()) {
                collectVehicles(route, candidates);
            }
            ctx.schedule.removeTransitLine(line);
            ctx.outcome.getApplied().add("删除线路 " + display(line));
        } else {
            for (int i = 0; i < routeIds.size(); i++) {
                TransitRoute route = line.getRoutes().get(Id.create(routeIds.getString(i), TransitRoute.class));
                if (route != null) {
                    collectVehicles(route, candidates);
                    line.removeRoute(route);
                }
            }
            if (line.getRoutes().isEmpty()) {
                ctx.schedule.removeTransitLine(line);
            }
            ctx.outcome.getApplied().add("删除线路方向 " + display(line) + "：" + routeIds.size() + " 个方向");
        }
        ScheduleTools.removeUnreferencedVehicles(ctx.schedule, ctx.transitVehicles, candidates);
    }

    // ==================== 运营类 ====================

    private void applyHeadway(Ctx ctx, EditItem edit) {
        RouteRef ref = requireRoute(ctx, edit);
        JSONObject params = required(edit.getParams(), "params");
        List<Double> times = ScheduleTools.expandDepartureTimes(params.getJSONArray("slots"));
        VehicleType type = currentVehicleType(ctx, ref.route);
        if (params.getJSONObject("vehicleType") != null) {
            type = ScheduleTools.resolveVehicleType(ctx.transitVehicles, params.getJSONObject("vehicleType"), edit.getId());
        }
        int n = ScheduleTools.rebuildDepartures(ctx.schedule, ctx.transitVehicles, ref.line, ref.route, times, type, edit.getId());
        String what = "ops.serviceHours".equals(edit.getKind()) ? "运营时间" : "发车间隔";
        ctx.outcome.getApplied().add("调整" + what + " " + editLabel(edit) + "：重排为 " + n + " 班次");
    }

    private void applyVehicleType(Ctx ctx, EditItem edit) {
        RouteRef ref = requireRoute(ctx, edit);
        JSONObject params = required(edit.getParams(), "params");
        VehicleType type = ScheduleTools.resolveVehicleType(ctx.transitVehicles, params.getJSONObject("vehicleType"), edit.getId());
        Set<Id<Vehicle>> candidates = new LinkedHashSet<>();
        int n = 0;
        for (Departure d : ref.route.getDepartures().values()) {
            if (d.getVehicleId() != null) {
                candidates.add(d.getVehicleId());
            }
            Id<Vehicle> vid = Id.createVehicleId("opt_v_" + edit.getId() + "_" + n);
            if (!ctx.transitVehicles.getVehicles().containsKey(vid)) {
                ctx.transitVehicles.addVehicle(VehicleUtils.createVehicle(vid, type));
            }
            d.setVehicleId(vid);
            n++;
        }
        ScheduleTools.removeUnreferencedVehicles(ctx.schedule, ctx.transitVehicles, candidates);
        ctx.outcome.getApplied().add("更换车型 " + editLabel(edit) + "：" + n + " 班次 -> " + type.getId());
    }

    // ==================== 共用 ====================

    private record RouteRef(TransitLine line, TransitRoute route) {
    }

    private RouteRef requireRoute(Ctx ctx, EditItem edit) {
        JSONObject target = required(edit.getTarget(), "target");
        String lineIdStr = target.getString("lineId");
        String routeIdStr = target.getString("routeId");
        TransitLine line = ctx.schedule.getTransitLines().get(Id.create(lineIdStr, TransitLine.class));
        if (line == null) {
            throw new BusinessException("线路不存在（可能不在切分范围内）: " + lineIdStr);
        }
        if (routeIdStr == null || routeIdStr.isBlank()) {
            if (line.getRoutes().size() == 1) {
                TransitRoute only = line.getRoutes().values().iterator().next();
                return new RouteRef(line, only);
            }
            throw new BusinessException("线路 " + lineIdStr + " 有多个方向，请指定 routeId");
        }
        TransitRoute route = line.getRoutes().get(Id.create(routeIdStr, TransitRoute.class));
        if (route == null) {
            throw new BusinessException("线路方向不存在: " + lineIdStr + "/" + routeIdStr);
        }
        return new RouteRef(line, route);
    }

    private TransitRoute buildRoute(Ctx ctx, TransitScheduleFactory factory, Id<TransitRoute> routeId,
                                    String transportMode, List<Id<Link>> linkIds,
                                    List<TransitStopFacility> stops, double opSpeed, double dwell) {
        int[] idx = stopLinkIndexes(linkIds, stops);
        double[][] offsets = ScheduleTools.computeOffsets(linkIds, idx, ctx.network, opSpeed, dwell);
        List<TransitRouteStop> profile = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            profile.add(factory.createTransitRouteStop(stops.get(i), offsets[i][0], offsets[i][1]));
        }
        NetworkRoute networkRoute = RouteUtils.createNetworkRoute(linkIds);
        return factory.createTransitRoute(routeId, networkRoute, profile, transportMode);
    }

    /**
     * 解析停靠站引用；站点吸附 link 不在走向上时自动生成"同名分方向站"克隆。
     */
    private List<TransitStopFacility> resolveStopsOnPath(Ctx ctx, EditItem edit, JSONArray stopsArr, List<Id<Link>> linkIds) {
        if (stopsArr == null || stopsArr.isEmpty()) {
            throw new BusinessException("缺少停靠站序列");
        }
        Set<Id<Link>> linkSet = new HashSet<>(linkIds);
        List<TransitStopFacility> stops = new ArrayList<>();
        int cloneSeq = 0;
        for (int i = 0; i < stopsArr.size(); i++) {
            String sid = stopsArr.getString(i);
            TransitStopFacility facility = requireStop(ctx.schedule, sid);
            if (facility.getLinkId() == null || !linkSet.contains(facility.getLinkId())) {
                Id<Link> nearest = nearestLinkOnPath(ctx, facility, linkIds);
                Id<TransitStopFacility> cloneId = Id.create(facility.getId() + ".opt_" + edit.getId() + "_" + cloneSeq++, TransitStopFacility.class);
                TransitStopFacility clone = ctx.schedule.getFacilities().get(cloneId);
                if (clone == null) {
                    clone = ctx.schedule.getFactory().createTransitStopFacility(cloneId, facility.getCoord(), facility.getIsBlockingLane());
                    clone.setName(facility.getName());
                    clone.setLinkId(nearest);
                    ctx.schedule.addStopFacility(clone);
                }
                stops.add(clone);
            } else {
                stops.add(facility);
            }
        }
        return stops;
    }

    private Id<Link> nearestLinkOnPath(Ctx ctx, TransitStopFacility facility, List<Id<Link>> linkIds) {
        Coord c = facility.getCoord();
        // 站点与路网坐标系可能不同：均为模型原始坐标系（切分输出一致），直接比较
        double best = Double.MAX_VALUE;
        Id<Link> bestId = null;
        for (Id<Link> id : linkIds) {
            Link link = ctx.network.getLinks().get(id);
            if (link == null) {
                continue;
            }
            double d = distancePointToSegment(c, link.getFromNode().getCoord(), link.getToNode().getCoord());
            if (d < best) {
                best = d;
                bestId = id;
            }
        }
        if (bestId == null) {
            throw new BusinessException("站点 " + display(facility) + " 无法吸附到线路走向");
        }
        return bestId;
    }

    private double distancePointToSegment(Coord p, Coord a, Coord b) {
        double dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
        double len2 = dx * dx + dy * dy;
        double t = len2 <= 0 ? 0 : Math.max(0, Math.min(1, ((p.getX() - a.getX()) * dx + (p.getY() - a.getY()) * dy) / len2));
        return Math.hypot(p.getX() - (a.getX() + t * dx), p.getY() - (a.getY() + t * dy));
    }

    private int[] stopLinkIndexes(List<Id<Link>> linkIds, List<TransitStopFacility> stops) {
        int[] idx = new int[stops.size()];
        int lastIdx = -1;
        for (int i = 0; i < stops.size(); i++) {
            int found = -1;
            for (int j = Math.max(0, lastIdx); j < linkIds.size(); j++) {
                if (linkIds.get(j).equals(stops.get(i).getLinkId())) {
                    found = j;
                    break;
                }
            }
            if (found < 0) {
                throw new BusinessException("站点 " + display(stops.get(i)) + " 的吸附路段不在走向上或与站序矛盾");
            }
            idx[i] = found;
            lastIdx = found;
        }
        return idx;
    }

    private void interpolateOffsets(double[] arr, double[] dep, int[] linkIdx) {
        int n = arr.length;
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(arr[i])) {
                continue;
            }
            int prev = i - 1;
            while (prev >= 0 && Double.isNaN(arr[prev])) {
                prev--;
            }
            int next = i + 1;
            while (next < n && Double.isNaN(arr[next])) {
                next++;
            }
            double value;
            if (prev >= 0 && next < n) {
                double f = linkIdx[next] == linkIdx[prev] ? 0.5
                        : (double) (linkIdx[i] - linkIdx[prev]) / (linkIdx[next] - linkIdx[prev]);
                value = arr[prev] + f * (arr[next] - arr[prev]);
            } else if (prev >= 0) {
                value = arr[prev] + 120.0 * (i - prev);
            } else if (next < n) {
                value = Math.max(0, arr[next] - 120.0 * (next - i));
            } else {
                value = i * 120.0;
            }
            arr[i] = value;
            dep[i] = value + 30;
        }
        // 保证不回退
        double last = -1;
        for (int i = 0; i < n; i++) {
            if (arr[i] <= last) {
                arr[i] = last + 30;
            }
            if (dep[i] < arr[i]) {
                dep[i] = arr[i] + 30;
            }
            last = dep[i];
        }
    }

    private void replaceRoute(Ctx ctx, TransitLine line, TransitRoute oldRoute,
                              List<TransitRouteStop> newStops, NetworkRoute networkRoute) {
        TransitScheduleFactory factory = ctx.schedule.getFactory();
        TransitRoute newRoute = factory.createTransitRoute(oldRoute.getId(), networkRoute, newStops, oldRoute.getTransportMode());
        newRoute.setDescription(oldRoute.getDescription());
        AttributesUtils.copyAttributesFromTo(oldRoute, newRoute);
        for (Departure d : oldRoute.getDepartures().values()) {
            Departure nd = factory.createDeparture(d.getId(), d.getDepartureTime());
            nd.setVehicleId(d.getVehicleId());
            AttributesUtils.copyAttributesFromTo(d, nd);
            newRoute.addDeparture(nd);
        }
        line.removeRoute(oldRoute);
        line.addRoute(newRoute);
    }

    private void ensureTransitModes(Network network, List<Id<Link>> linkIds, String transportMode) {
        for (Id<Link> id : linkIds) {
            Link link = network.getLinks().get(id);
            if (link == null) {
                throw new BusinessException("走向引用了不存在的路段: " + id);
            }
            Set<String> modes = new HashSet<>(link.getAllowedModes());
            if (modes.add("pt") | modes.add(transportMode)) {
                link.setAllowedModes(modes);
            }
        }
    }

    private VehicleType currentVehicleType(Ctx ctx, TransitRoute route) {
        for (Departure d : route.getDepartures().values()) {
            if (d.getVehicleId() != null) {
                Vehicle v = ctx.transitVehicles.getVehicles().get(d.getVehicleId());
                if (v != null) {
                    return v.getType();
                }
            }
        }
        if (!ctx.transitVehicles.getVehicleTypes().isEmpty()) {
            return ctx.transitVehicles.getVehicleTypes().values().iterator().next();
        }
        throw new BusinessException("无法确定车型，请在参数中指定");
    }

    private void collectVehicles(TransitRoute route, Set<Id<Vehicle>> target) {
        for (Departure d : route.getDepartures().values()) {
            if (d.getVehicleId() != null) {
                target.add(d.getVehicleId());
            }
        }
    }

    private boolean usedByTransit(TransitSchedule schedule, Id<Link> linkId) {
        return !transitUsers(schedule, linkId).isEmpty();
    }

    private boolean usedByTransit(TransitSchedule schedule, TransitStopFacility facility) {
        for (TransitLine line : schedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                for (TransitRouteStop s : route.getStops()) {
                    if (s.getStopFacility().getId().equals(facility.getId())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private List<String> transitUsers(TransitSchedule schedule, Id<Link> linkId) {
        List<String> users = new ArrayList<>();
        for (TransitLine line : schedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                NetworkRoute nr = route.getRoute();
                if (nr == null) {
                    continue;
                }
                if (allRouteLinks(nr).contains(linkId)
                        || route.getStops().stream().anyMatch(s -> linkId.equals(s.getStopFacility().getLinkId()))) {
                    users.add(display(line) + "/" + route.getId());
                }
            }
        }
        return users;
    }

    private static List<Id<Link>> allRouteLinks(NetworkRoute nr) {
        List<Id<Link>> ids = new ArrayList<>(nr.getLinkIds().size() + 2);
        ids.add(nr.getStartLinkId());
        ids.addAll(nr.getLinkIds());
        ids.add(nr.getEndLinkId());
        return ids;
    }

    private void postCleanNetwork(Ctx ctx) {
        Set<Id<Link>> before = new HashSet<>(ctx.network.getLinks().keySet());
        MultimodalNetworkCleaner cleaner = new MultimodalNetworkCleaner(ctx.network);
        try {
            cleaner.run(Set.of("car"));
            cleaner.removeNodesWithoutLinks();
        } catch (Exception e) {
            log.warn("方案路网连通性清理失败: {}", e.getMessage());
            return;
        }
        int removed = before.size() - ctx.network.getLinks().size();
        if (removed > 0) {
            ctx.outcome.getIssues().add(ValidationIssue.warning(null,
                    "删除路段导致 " + removed + " 条关联路段失去连通性，已级联移除"));
        }
    }

    private void validateFinal(Ctx ctx) {
        for (TransitLine line : ctx.schedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                NetworkRoute nr = route.getRoute();
                if (nr == null) {
                    ctx.outcome.getIssues().add(ValidationIssue.error(null, "线路 " + display(line) + "/" + route.getId() + " 缺少走向"));
                    continue;
                }
                List<Id<Link>> ids = allRouteLinks(nr);
                Link prev = null;
                for (Id<Link> id : ids) {
                    Link link = ctx.network.getLinks().get(id);
                    if (link == null) {
                        ctx.outcome.getIssues().add(ValidationIssue.error(null,
                                "线路 " + display(line) + "/" + route.getId() + " 走向引用了已被移除的路段 " + id));
                        prev = null;
                        continue;
                    }
                    if (prev != null && !prev.getToNode().getId().equals(link.getFromNode().getId())) {
                        ctx.outcome.getIssues().add(ValidationIssue.error(null,
                                "线路 " + display(line) + "/" + route.getId() + " 走向在 " + prev.getId() + " -> " + id + " 处不连续"));
                    }
                    prev = link;
                }
                Set<Id<Link>> linkSet = new HashSet<>(ids);
                for (TransitRouteStop s : route.getStops()) {
                    if (s.getStopFacility().getLinkId() == null || !linkSet.contains(s.getStopFacility().getLinkId())) {
                        ctx.outcome.getIssues().add(ValidationIssue.error(null,
                                "线路 " + display(line) + "/" + route.getId() + " 的站点 " + display(s.getStopFacility()) + " 未挂接在走向上"));
                    }
                }
                if (route.getDepartures().isEmpty()) {
                    ctx.outcome.getIssues().add(ValidationIssue.warning(null,
                            "线路 " + display(line) + "/" + route.getId() + " 没有任何班次"));
                }
                for (Departure d : route.getDepartures().values()) {
                    if (d.getVehicleId() == null || !ctx.transitVehicles.getVehicles().containsKey(d.getVehicleId())) {
                        ctx.outcome.getIssues().add(ValidationIssue.error(null,
                                "班次 " + d.getId() + " 引用的车辆不存在"));
                        break;
                    }
                }
            }
        }
    }

    // ==================== 小工具 ====================

    private List<Id<Link>> targetLinkIds(EditItem edit) {
        JSONArray arr = required(edit.getTarget(), "target").getJSONArray("linkIds");
        if (arr == null || arr.isEmpty()) {
            throw new BusinessException("缺少目标路段");
        }
        List<Id<Link>> ids = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            ids.add(Id.createLinkId(arr.getString(i)));
        }
        return ids;
    }

    private List<Id<Link>> toLinkIds(JSONArray arr) {
        if (arr == null || arr.isEmpty()) {
            throw new BusinessException("缺少走向路段序列");
        }
        List<Id<Link>> ids = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            ids.add(Id.createLinkId(arr.getString(i)));
        }
        return ids;
    }

    private Node requireNode(Network network, String nodeId) {
        Node node = network.getNodes().get(Id.createNodeId(nodeId));
        if (node == null) {
            throw new BusinessException("端点节点不存在: " + nodeId);
        }
        return node;
    }

    private TransitStopFacility requireStop(TransitSchedule schedule, String stopId) {
        if (stopId == null || stopId.isBlank()) {
            throw new BusinessException("缺少站点标识");
        }
        TransitStopFacility facility = schedule.getFacilities().get(Id.create(stopId, TransitStopFacility.class));
        if (facility == null) {
            throw new BusinessException("站点不存在（可能不在切分范围内）: " + stopId);
        }
        return facility;
    }

    private String dominantTransportMode(TransitSchedule schedule) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (TransitLine line : schedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                counts.merge(route.getTransportMode(), 1, Integer::sum);
            }
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private Coord transform(CoordinateTransformation ctf, double lng, double lat) {
        Coord c = new Coord(lng, lat);
        return ctf == null ? c : ctf.transform(c);
    }

    private static JSONObject required(JSONObject obj, String what) {
        if (obj == null) {
            throw new BusinessException("缺少 " + what);
        }
        return obj;
    }

    private static double dbl(JSONObject obj, String key, double def) {
        if (obj == null) {
            return def;
        }
        Double v = obj.getDouble(key);
        return v == null ? def : v;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private String display(TransitLine line) {
        return line.getName() != null && !line.getName().isBlank() ? line.getName() : line.getId().toString();
    }

    private String display(TransitStopFacility facility) {
        return facility.getName() != null && !facility.getName().isBlank() ? facility.getName() : facility.getId().toString();
    }
}
