package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import com.jts.gjcxfzksh.data.id.DepartureId;
import com.jts.gjcxfzksh.data.id.LineId;
import com.jts.gjcxfzksh.data.id.PersonId;
import com.jts.gjcxfzksh.data.id.RouteId;
import com.jts.gjcxfzksh.data.id.StopFacilityId;
import com.jts.gjcxfzksh.data.id.VehicleId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * route-panel-v15 / station-panel-v15 契约：
 * 任务A 线路站间 OD（stationOd）、任务B 出行目的活动画像（过滤 interaction）、
 * 任务C 换乘判定（同 facility，或同站名且 ≤500m；v12 的 200m 邻近异名站台规则已移除）、
 * 任务D 公交 lineGroups（bus::lineId）。
 */
class PanelStationOdAndTripPurposeTest {

    private static final double H8 = 8 * 3600.0;
    private static final double H9 = 9 * 3600.0;
    private static final double H10 = 10 * 3600.0;
    private static final double H11 = 11 * 3600.0;
    private static final double H12 = 12 * 3600.0;
    // mercatorToWgs84(5000, 0) 的经度：toDegrees(5000 / 6378137)。
    private static final double LON_5000 = Math.toDegrees(5000.0 / 6378137.0);

    @TempDir
    Path tempDir;

    @Test
    void routePanelBuildsStationOdWithLonLatAndTripPurposeDemographics() throws Exception {
        MatsimData data = buildData("area/public/od-route");
        MatsimRoutePanelCache.prepareOnModelLoad(data);
        Map<String, Object> panel = MatsimRoutePanelCache.readRoutePanel(data);
        Map<?, ?> routes = (Map<?, ?>) panel.get("routes");

        // 任务A：route "up" 的站间 OD——p1/p3/p4 都从甲站坐到乙站，合并为一条 flow=3。
        Map<?, ?> up = (Map<?, ?>) routes.get("up");
        assertNotNull(up);
        // v15 契约：route 级 metrics 含发车间隔字段（fixture 单班次 → 无间隔=0）
        Map<?, ?> upMetrics = (Map<?, ?>) up.get("metrics");
        assertEquals(0.0, ((Number) upMetrics.get("peakHeadwayMin")).doubleValue(), 1e-9);
        assertEquals(0.0, ((Number) upMetrics.get("offPeakHeadwayMin")).doubleValue(), 1e-9);
        List<?> stationOd = (List<?>) up.get("stationOd");
        assertEquals(1, stationOd.size());
        Map<?, ?> od = (Map<?, ?>) stationOd.getFirst();
        assertEquals("fa1", od.get("fromFacilityId"));
        assertEquals("甲站", od.get("fromName"));
        assertEquals("fa2", od.get("toFacilityId"));
        assertEquals("乙站", od.get("toName"));
        assertEquals(3, ((Number) od.get("flow")).intValue());
        // v16 契约：flowByHour 按上车时刻分桶（p1 8点、p3 10点、p4 11点），合计=flow。
        List<?> odFlowByHour = (List<?>) od.get("flowByHour");
        assertEquals(24, odFlowByHour.size());
        assertEquals(1, ((Number) odFlowByHour.get(8)).intValue());
        assertEquals(1, ((Number) odFlowByHour.get(10)).intValue());
        assertEquals(1, ((Number) odFlowByHour.get(11)).intValue());
        assertEquals(3, odFlowByHour.stream().mapToInt(v -> ((Number) v).intValue()).sum());
        assertEquals(0.0, ((Number) od.get("fromX")).doubleValue(), 1e-9);
        assertEquals(0.0, ((Number) od.get("fromY")).doubleValue(), 1e-9);
        assertEquals(LON_5000, ((Number) od.get("toX")).doubleValue(), 1e-6);
        assertEquals(0.0, ((Number) od.get("toY")).doubleValue(), 1e-9);

        // 反向 route "down"：乙站→甲站 flow=1。
        Map<?, ?> down = (Map<?, ?>) routes.get("down");
        List<?> downOd = (List<?>) down.get("stationOd");
        assertEquals(1, downOd.size());
        assertEquals("fa2", ((Map<?, ?>) downOd.getFirst()).get("fromFacilityId"));
        assertEquals("fa1", ((Map<?, ?>) downOd.getFirst()).get("toFacilityId"));
        assertEquals(1, ((Number) ((Map<?, ?>) downOd.getFirst()).get("flow")).intValue());
        // p2 9 点上车 → flowByHour[9]=1。
        List<?> downFlowByHour = (List<?>) ((Map<?, ?>) downOd.getFirst()).get("flowByHour");
        assertEquals(1, ((Number) downFlowByHour.get(9)).intValue());
        assertEquals(1, downFlowByHour.stream().mapToInt(v -> ((Number) v).intValue()).sum());

        // 任务B：route "up" 画像 = 乘坐者本次出行的出行目的活动（p1→work，p3→shopping），合计 100%。
        Map<?, ?> demographics = (Map<?, ?>) up.get("demographics");
        assertEquals(3, ((Number) demographics.get("riderCount")).intValue());
        assertEquals("trip-purpose", demographics.get("activitySource"));
        Map<String, Map<?, ?>> activities = activityByKey((List<?>) demographics.get("activityTypes"));
        assertEquals(Set.of("work", "shopping"), activities.keySet());
        assertEquals(1, ((Number) activities.get("work").get("count")).intValue());
        assertEquals(50.0, ((Number) activities.get("work").get("ratio")).doubleValue(), 1e-9);
        assertEquals(50.0, ((Number) activities.get("shopping").get("ratio")).doubleValue(), 1e-9);
        assertFalse(activities.containsKey("home"));
        assertFalse(activities.containsKey("pt interaction"));
        Map<?, ?> ratios = (Map<?, ?>) demographics.get("activityTypeRatios");
        assertEquals(50.0, ((Number) ratios.get("work")).doubleValue(), 1e-9);
        assertEquals(50.0, ((Number) ratios.get("shopping")).doubleValue(), 1e-9);

        // 任务B 兜底：route "down"（p2 的 plan 无 TransitPassengerRoute）退回全活动统计，但 interaction 仍被过滤。
        Map<?, ?> downDemographics = (Map<?, ?>) down.get("demographics");
        assertEquals(1, ((Number) downDemographics.get("riderCount")).intValue());
        assertEquals("all-activities-fallback", downDemographics.get("activitySource"));
        Map<String, Map<?, ?>> downActivities = activityByKey((List<?>) downDemographics.get("activityTypes"));
        assertEquals(Set.of("home", "gym"), downActivities.keySet());
        assertEquals(50.0, ((Number) downActivities.get("home").get("ratio")).doubleValue(), 1e-9);
        assertEquals(50.0, ((Number) downActivities.get("gym").get("ratio")).doubleValue(), 1e-9);
    }

    @Test
    void transfersRequireSameFacilityOrSameNamedNearbyStop() throws Exception {
        MatsimData data = buildData("area/public/od-transfer");
        MatsimRoutePanelCache.prepareOnModelLoad(data);
        Map<String, Object> panel = MatsimRoutePanelCache.readRoutePanel(data);
        Map<?, ?> routes = (Map<?, ?>) panel.get("routes");
        Map<?, ?> up = (Map<?, ?>) routes.get("up");

        // v13/v14 契约：跨线换乘要求同 facility，或同站名且坐标 ≤500m。
        // p3 在乙站下车、100m 外【不同名】的“地铁对面站台”上车 → 不算换乘（v12 的 200m 邻近规则已移除）；
        // p4 在乙站下车、300m 外不同名的“远站台”上车 → 不算换乘。
        List<?> transfers = (List<?>) up.get("transfers");
        assertTrue(transfers.stream().noneMatch(item -> item instanceof Map<?, ?> transfer
                && "metro-line".equals(transfer.get("lineId"))));
        assertTrue(transfers.stream().noneMatch(item -> item instanceof Map<?, ?> transfer
                && "far-line".equals(transfer.get("lineId"))));

        // p7 在“地铁终点”下地铁后于同一 facility（m2）换乘 far-line → 计为换乘（正例）。
        Map<?, ?> metroUp = (Map<?, ?>) routes.get("m-up");
        List<?> metroTransfers = (List<?>) metroUp.get("transfers");
        assertTrue(metroTransfers.stream().anyMatch(item -> item instanceof Map<?, ?> transfer
                && "far-line".equals(transfer.get("lineId"))
                && ((Number) transfer.get("flow")).intValue() == 1));
    }

    @Test
    void busRoutesAggregateIntoLineGroupsLikeMetro() throws Exception {
        MatsimData data = buildData("area/public/od-group");
        MatsimRoutePanelCache.prepareOnModelLoad(data);
        Map<String, Object> panel = MatsimRoutePanelCache.readRoutePanel(data);
        Map<?, ?> lineGroups = (Map<?, ?>) panel.get("lineGroups");

        // 任务D：公交上下行合并为 bus::lineId 组，payload 与地铁组同构。
        Map<?, ?> busGroup = (Map<?, ?>) lineGroups.get("bus::bus-line");
        assertNotNull(busGroup);
        assertEquals(Boolean.TRUE, busGroup.get("lineGroup"));
        assertEquals("5路", busGroup.get("lineName"));
        assertEquals("bus", busGroup.get("mode"));
        assertTrue(((List<?>) busGroup.get("routeIds")).containsAll(List.of("up", "down")));
        List<?> hourlyFlow = (List<?>) busGroup.get("hourlyFlow");
        assertEquals(1, ((Number) hourlyFlow.get(8)).intValue());
        assertEquals(1, ((Number) hourlyFlow.get(9)).intValue());
        assertEquals(1, ((Number) hourlyFlow.get(10)).intValue());
        assertEquals(1, ((Number) hourlyFlow.get(11)).intValue());
        Map<?, ?> groupMetrics = (Map<?, ?>) busGroup.get("metrics");
        assertEquals(4L, ((Number) groupMetrics.get("passenger")).longValue());
        // v15 契约：组级发车间隔字段随代表方向输出（fixture 每方向仅 1 班 → 无间隔=0）
        assertEquals(0.0, ((Number) groupMetrics.get("peakHeadwayMin")).doubleValue(), 1e-9);
        assertEquals(0.0, ((Number) groupMetrics.get("offPeakHeadwayMin")).doubleValue(), 1e-9);
        assertNotNull(busGroup.get("boardingByHour"));
        assertNotNull(busGroup.get("alightingByHour"));
        assertNotNull(busGroup.get("capacityByHour"));

        // stationOd 合并：上行 甲站→乙站 flow=3 在前，下行 乙站→甲站 flow=1 在后。
        List<?> groupOd = (List<?>) busGroup.get("stationOd");
        assertEquals(2, groupOd.size());
        Map<?, ?> first = (Map<?, ?>) groupOd.get(0);
        assertEquals("fa1", first.get("fromFacilityId"));
        assertEquals(3, ((Number) first.get("flow")).intValue());
        Map<?, ?> second = (Map<?, ?>) groupOd.get(1);
        assertEquals("fa2", second.get("fromFacilityId"));
        assertEquals(1, ((Number) second.get("flow")).intValue());
        // v16 契约：lineGroup 合并时 flowByHour 同步累加（上行 8/10/11 点各 1，下行 9 点 1）。
        List<?> groupFirstFlowByHour = (List<?>) first.get("flowByHour");
        assertEquals(1, ((Number) groupFirstFlowByHour.get(8)).intValue());
        assertEquals(1, ((Number) groupFirstFlowByHour.get(10)).intValue());
        assertEquals(1, ((Number) groupFirstFlowByHour.get(11)).intValue());
        List<?> groupSecondFlowByHour = (List<?>) second.get("flowByHour");
        assertEquals(1, ((Number) groupSecondFlowByHour.get(9)).intValue());

        // transfers 聚合（组外线路）：v13/v14 收紧后本 fixture 的公交组无跨线换乘（p3 的
        // 100m 不同名站台不再算换乘）；组外换乘聚合改由地铁组验证（p7 同设施换乘 far-line）。
        List<?> groupTransfers = (List<?>) busGroup.get("transfers");
        assertTrue(groupTransfers.isEmpty());
        Map<?, ?> metroGroupForTransfers = (Map<?, ?>) lineGroups.get("metro::地铁1号线");
        assertNotNull(metroGroupForTransfers);
        List<?> metroGroupTransfers = (List<?>) metroGroupForTransfers.get("transfers");
        assertTrue(metroGroupTransfers.stream().anyMatch(item -> item instanceof Map<?, ?> transfer
                && "far-line".equals(transfer.get("lineId"))));
        Map<?, ?> groupDemographics = (Map<?, ?>) busGroup.get("demographics");
        assertEquals(4, ((Number) groupDemographics.get("riderCount")).intValue());
        Map<String, Map<?, ?>> groupActivities = activityByKey((List<?>) groupDemographics.get("activityTypes"));
        assertEquals(Set.of("work", "shopping"), groupActivities.keySet());

        // 只有一个 route 的公交线路同样生成组。
        Map<?, ?> farGroup = (Map<?, ?>) lineGroups.get("bus::far-line");
        assertNotNull(farGroup);
        assertEquals(List.of("far-up"), List.copyOf((List<?>) farGroup.get("routeIds")));

        // 地铁组既有聚合键与行为不变，且同样带 stationOd。
        Map<?, ?> metroGroup = (Map<?, ?>) lineGroups.get("metro::地铁1号线");
        assertNotNull(metroGroup);
        List<?> metroOd = (List<?>) metroGroup.get("stationOd");
        assertEquals(1, metroOd.size());
        assertEquals("m1", ((Map<?, ?>) metroOd.getFirst()).get("fromFacilityId"));
    }

    @Test
    void stationPanelOdCarriesLonLatAndAccessStopTripPurposeProfile() throws Exception {
        MatsimData data = buildData("area/public/od-station");
        MatsimStationPanelCache.prepareOnModelLoad(data);
        Map<String, Object> panel = MatsimStationPanelCache.readStationPanel(data);
        Map<?, ?> stations = (Map<?, ?>) panel.get("stations");

        // 收尾：od 数组带起讫点经纬度。
        Map<?, ?> origin = (Map<?, ?>) stations.get("甲站");
        assertNotNull(origin);
        List<?> odList = (List<?>) origin.get("od");
        Map<?, ?> od = odList.stream()
                .map(item -> (Map<?, ?>) item)
                .filter(item -> "甲站".equals(item.get("origin")) && "乙站".equals(item.get("destination")))
                .findFirst()
                .orElse(null);
        assertNotNull(od);
        assertEquals(3, ((Number) od.get("flow")).intValue());
        assertEquals(0.0, ((Number) od.get("originX")).doubleValue(), 1e-9);
        assertEquals(0.0, ((Number) od.get("originY")).doubleValue(), 1e-9);
        assertEquals(LON_5000, ((Number) od.get("destinationX")).doubleValue(), 1e-6);
        assertEquals(0.0, ((Number) od.get("destinationY")).doubleValue(), 1e-9);

        // 任务B：站点画像 = 在该站上车者本次出行目的（甲站：p1→work、p3→shopping），合计 100%。
        Map<?, ?> demographics = (Map<?, ?>) origin.get("demographics");
        assertEquals("trip-purpose", demographics.get("activitySource"));
        Map<String, Map<?, ?>> activities = activityByKey((List<?>) demographics.get("activityTypes"));
        assertEquals(Set.of("work", "shopping"), activities.keySet());
        assertEquals(50.0, ((Number) activities.get("work").get("ratio")).doubleValue(), 1e-9);
        assertFalse(activities.containsKey("pt interaction"));

        // 地铁站台：p3 换乘后前往 shopping → 100%。
        Map<?, ?> metroStop = (Map<?, ?>) stations.get("地铁对面站台");
        Map<?, ?> metroDemographics = (Map<?, ?>) metroStop.get("demographics");
        Map<String, Map<?, ?>> metroActivities = activityByKey((List<?>) metroDemographics.get("activityTypes"));
        assertEquals(Set.of("shopping"), metroActivities.keySet());
        assertEquals(100.0, ((Number) metroActivities.get("shopping").get("ratio")).doubleValue(), 1e-9);

        // 兜底：乙站没有任何“出行目的”上车记录 → 全活动统计，interaction 被过滤。
        Map<?, ?> other = (Map<?, ?>) stations.get("乙站");
        Map<?, ?> otherDemographics = (Map<?, ?>) other.get("demographics");
        assertEquals("all-activities-fallback", otherDemographics.get("activitySource"));
        Map<String, Map<?, ?>> otherActivities = activityByKey((List<?>) otherDemographics.get("activityTypes"));
        assertFalse(otherActivities.containsKey("pt interaction"));
        assertNull(((Map<?, ?>) otherDemographics.get("activityTypeRatios")).get("pt interaction"));
    }

    private MatsimData buildData(String datasource) throws Exception {
        Path output = tempDir.resolve(datasource.replace('/', '-')).resolve("output");
        Path cache = tempDir.resolve(datasource.replace('/', '-') + "-cache");
        Files.createDirectories(output);
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());

        MatsimData data = new MatsimData(datasource, output.toString(), cache.toString(), false);
        data.setScenario(buildScenario());
        data.setPersonTracks(new LinkedHashSet<>(List.of(
                // p1：8 点乘 up 甲站→乙站。
                track("p1", "bus-line", "up", "bus-up-1", "dep-up", "fa1", true, H8),
                track("p1", "bus-line", "up", "bus-up-1", "dep-up", "fa2", false, H8 + 600),
                // p2：9 点乘 down 乙站→甲站。
                track("p2", "bus-line", "down", "bus-down-1", "dep-down", "fa2", true, H9),
                track("p2", "bus-line", "down", "bus-down-1", "dep-down", "fa1", false, H9 + 600),
                // p3：10 点乘 up 后在 100m 外不同名的地铁站台换乘（任务C 正例）。
                track("p3", "bus-line", "up", "bus-up-1", "dep-up", "fa1", true, H10),
                track("p3", "bus-line", "up", "bus-up-1", "dep-up", "fa2", false, H10 + 600),
                track("p3", "metro-line", "m-up", "metro-1", "dep-metro", "m1", true, H10 + 1200),
                track("p3", "metro-line", "m-up", "metro-1", "dep-metro", "m2", false, H10 + 1800),
                // p4：11 点乘 up 后在 300m 外的远站台上车（任务C 反例，不算换乘）。
                track("p4", "bus-line", "up", "bus-up-1", "dep-up", "fa1", true, H11),
                track("p4", "bus-line", "up", "bus-up-1", "dep-up", "fa2", false, H11 + 600),
                track("p4", "far-line", "far-up", "far-1", "dep-far", "mfar", true, H11 + 1200),
                track("p4", "far-line", "far-up", "far-1", "dep-far", "fa1", false, H11 + 1800),
                // p7：12 点在地铁终点（同一 facility m2）下地铁、换乘 far-line（v13/v14 同设施换乘正例）。
                track("p7", "metro-line", "m-up", "metro-1", "dep-metro", "m2", false, H12),
                track("p7", "far-line", "far-up", "far-1", "dep-far", "m2", true, H12 + 300),
                track("p7", "far-line", "far-up", "far-1", "dep-far", "fa1", false, H12 + 900)
        )));
        return data;
    }

    private MutableScenario buildScenario() {
        MutableScenario scenario = (MutableScenario) ScenarioUtils.createScenario(ConfigUtils.createConfig());
        Network network = scenario.getNetwork();
        NetworkFactory networkFactory = network.getFactory();
        Node from = networkFactory.createNode(Id.createNodeId("n1"), new Coord(0, 0));
        Node to = networkFactory.createNode(Id.createNodeId("n2"), new Coord(5000, 0));
        network.addNode(from);
        network.addNode(to);
        Link link = networkFactory.createLink(Id.createLinkId("l1"), from, to);
        link.setLength(5000);
        network.addLink(link);

        TransitSchedule schedule = scenario.getTransitSchedule();
        TransitScheduleFactory factory = schedule.getFactory();
        TransitStopFacility fa1 = stop(factory, schedule, "fa1", "甲站", 0, 0);
        TransitStopFacility fa2 = stop(factory, schedule, "fa2", "乙站", 5000, 0);
        TransitStopFacility m1 = stop(factory, schedule, "m1", "地铁对面站台", 5000, 100);
        TransitStopFacility m2 = stop(factory, schedule, "m2", "地铁终点", 8000, 100);
        TransitStopFacility far = stop(factory, schedule, "mfar", "远站台", 5300, 0);

        TransitLine busLine = factory.createTransitLine(Id.create("bus-line", TransitLine.class));
        busLine.setName("5路");
        TransitRoute up = routeWithDeparture(factory, "up", "bus", "bus-up-1", "dep-up", fa1, fa2);
        TransitRoute down = routeWithDeparture(factory, "down", "bus", "bus-down-1", "dep-down", fa2, fa1);
        busLine.addRoute(up);
        busLine.addRoute(down);
        schedule.addTransitLine(busLine);

        TransitLine metroLine = factory.createTransitLine(Id.create("metro-line", TransitLine.class));
        metroLine.setName("地铁1号线");
        TransitRoute metroUp = routeWithDeparture(factory, "m-up", "pt", "metro-1", "dep-metro", m1, m2);
        metroLine.addRoute(metroUp);
        schedule.addTransitLine(metroLine);

        TransitLine farLine = factory.createTransitLine(Id.create("far-line", TransitLine.class));
        farLine.setName("6路");
        farLine.addRoute(routeWithDeparture(factory, "far-up", "bus", "far-1", "dep-far", far, fa1));
        schedule.addTransitLine(farLine);

        VehicleType vehicleType = VehicleUtils.createVehicleType(Id.create("test-vehicle-type", VehicleType.class));
        vehicleType.getCapacity().setSeats(40);
        vehicleType.getCapacity().setStandingRoom(60);
        scenario.getTransitVehicles().addVehicleType(vehicleType);
        for (String vehicleId : List.of("bus-up-1", "bus-down-1", "metro-1", "far-1")) {
            scenario.getTransitVehicles().addVehicle(VehicleUtils.createVehicle(Id.create(vehicleId, Vehicle.class), vehicleType));
        }

        PopulationFactory populationFactory = scenario.getPopulation().getFactory();
        // p1：home → [pt: up 甲站→乙站] → pt interaction → work。出行目的 = work。
        Person p1 = populationFactory.createPerson(Id.createPersonId("p1"));
        Plan plan1 = populationFactory.createPlan();
        plan1.addActivity(populationFactory.createActivityFromCoord("home", new Coord(0, 0)));
        plan1.addLeg(transitLeg(populationFactory, fa1, busLine, up, fa2));
        plan1.addActivity(populationFactory.createActivityFromCoord("pt interaction", new Coord(5000, 0)));
        plan1.addLeg(populationFactory.createLeg("walk"));
        plan1.addActivity(populationFactory.createActivityFromCoord("work", new Coord(5100, 0)));
        p1.addPlan(plan1);
        p1.setSelectedPlan(plan1);
        scenario.getPopulation().addPerson(p1);

        // p2：无 TransitPassengerRoute 的 plan（触发兜底），含 pt interaction（必须被过滤）。
        Person p2 = populationFactory.createPerson(Id.createPersonId("p2"));
        Plan plan2 = populationFactory.createPlan();
        plan2.addActivity(populationFactory.createActivityFromCoord("home", new Coord(5000, 0)));
        plan2.addLeg(populationFactory.createLeg("pt"));
        plan2.addActivity(populationFactory.createActivityFromCoord("pt interaction", new Coord(2500, 0)));
        plan2.addLeg(populationFactory.createLeg("pt"));
        plan2.addActivity(populationFactory.createActivityFromCoord("gym", new Coord(0, 0)));
        p2.addPlan(plan2);
        p2.setSelectedPlan(plan2);
        scenario.getPopulation().addPerson(p2);

        // p3：home → [pt: up] → pt interaction → [pt: metro] → shopping。两段 leg 的出行目的都是 shopping。
        Person p3 = populationFactory.createPerson(Id.createPersonId("p3"));
        Plan plan3 = populationFactory.createPlan();
        plan3.addActivity(populationFactory.createActivityFromCoord("home", new Coord(0, 0)));
        plan3.addLeg(transitLeg(populationFactory, fa1, busLine, up, fa2));
        plan3.addActivity(populationFactory.createActivityFromCoord("pt interaction", new Coord(5000, 0)));
        plan3.addLeg(transitLeg(populationFactory, m1, metroLine, metroUp, m2));
        plan3.addActivity(populationFactory.createActivityFromCoord("shopping", new Coord(8000, 100)));
        p3.addPlan(plan3);
        p3.setSelectedPlan(plan3);
        scenario.getPopulation().addPerson(p3);

        // p4：无 TransitPassengerRoute 的 plan，仅供 riderCount 统计。
        Person p4 = populationFactory.createPerson(Id.createPersonId("p4"));
        Plan plan4 = populationFactory.createPlan();
        plan4.addActivity(populationFactory.createActivityFromCoord("home", new Coord(0, 0)));
        plan4.addLeg(populationFactory.createLeg("pt"));
        plan4.addActivity(populationFactory.createActivityFromCoord("park", new Coord(300, 0)));
        p4.addPlan(plan4);
        p4.setSelectedPlan(plan4);
        scenario.getPopulation().addPerson(p4);

        return scenario;
    }

    private Leg transitLeg(
            PopulationFactory factory,
            TransitStopFacility access,
            TransitLine line,
            TransitRoute route,
            TransitStopFacility egress
    ) {
        Leg leg = factory.createLeg("pt");
        leg.setRoute(new DefaultTransitPassengerRoute(access, line, route, egress));
        return leg;
    }

    private TransitStopFacility stop(
            TransitScheduleFactory factory,
            TransitSchedule schedule,
            String facilityId,
            String name,
            double x,
            double y
    ) {
        TransitStopFacility facility = factory.createTransitStopFacility(
                Id.create(facilityId, TransitStopFacility.class), new Coord(x, y), false);
        facility.setName(name);
        schedule.addStopFacility(facility);
        return facility;
    }

    private TransitRoute routeWithDeparture(
            TransitScheduleFactory factory,
            String routeId,
            String mode,
            String vehicleId,
            String departureId,
            TransitStopFacility stop1,
            TransitStopFacility stop2
    ) {
        TransitRoute route = factory.createTransitRoute(
                Id.create(routeId, TransitRoute.class),
                RouteUtils.createLinkNetworkRouteImpl(Id.createLinkId("l1"), Id.createLinkId("l1")),
                List.of(
                        factory.createTransitRouteStop(stop1, 0.0, 0.0),
                        factory.createTransitRouteStop(stop2, 60.0, 60.0)
                ),
                mode
        );
        Departure departure = factory.createDeparture(Id.create(departureId, Departure.class), 0.0);
        departure.setVehicleId(Id.create(vehicleId, Vehicle.class));
        route.addDeparture(departure);
        return route;
    }

    private PTPersonTrack track(
            String personId,
            String lineId,
            String routeId,
            String vehicleId,
            String departureId,
            String facilityId,
            boolean enter,
            double time
    ) {
        PTPersonTrack track = new PTPersonTrack();
        track.setPersonId(PersonId.create(personId));
        track.setLineId(LineId.create(lineId));
        track.setRouteId(RouteId.create(routeId));
        track.setVehicleId(VehicleId.create(vehicleId));
        track.setDepartureId(DepartureId.create(departureId));
        track.setFacilityId(StopFacilityId.create(facilityId));
        track.setEnter(enter);
        track.setTime(time);
        return track;
    }

    private Map<String, Map<?, ?>> activityByKey(List<?> activityTypes) {
        Map<String, Map<?, ?>> result = new java.util.LinkedHashMap<>();
        if (activityTypes == null) {
            return result;
        }
        for (Object item : activityTypes) {
            if (item instanceof Map<?, ?> map && map.get("key") != null) {
                result.put(map.get("key").toString(), map);
            }
        }
        return result;
    }
}
