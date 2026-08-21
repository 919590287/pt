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
import com.jts.gjcxfzksh.api.model.params.RouteChartParam;
import com.jts.gjcxfzksh.api.model.params.RouteInfoParam;
import com.jts.gjcxfzksh.api.service.impl.RouteServiceImpl;
import com.jts.gjcxfzksh.exception.BusinessException;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatsimRoutePanelCacheDepartureTest {

    @TempDir
    Path tempDir;

    @Test
    void timetableAndPanelStayScopedToOneDeparture() throws Exception {
        MatsimData data = buildData();
        // 班次数据与线路/站点面板一致，先走模型加载预热契约；运行期读取不得再次扫描乘客明细。
        MatsimRoutePanelCache.prepareOnModelLoad(data);

        Map<String, Object> bundle = MatsimRoutePanelCache.readDepartureBundle(data);
        assertEquals("ready", bundle.get("status"));
        assertTrue(((Map<?, ?>) bundle.get("routes")).containsKey("line-a::route-a"));

        Map<String, Object> timetable = MatsimRoutePanelCache.readDepartureTimetable(data, "line-a", "route-a");
        List<?> departures = (List<?>) timetable.get("departures");
        assertEquals(List.of("dep-early", "dep-late", "dep-empty"), departures.stream()
                .map(item -> String.valueOf(((Map<?, ?>) item).get("id")))
                .toList());

        Map<String, Object> panel = MatsimRoutePanelCache.readDeparturePanel(
                data, "line-a", "route-a", "dep-early");
        Map<?, ?> metrics = (Map<?, ?>) panel.get("metrics");
        assertEquals(2, ((Number) metrics.get("passenger")).intValue());
        assertEquals(2, ((Number) metrics.get("maxOnboard")).intValue());
        assertEquals(50, ((Number) metrics.get("capacity")).intValue());
        assertEquals(4.0, ((Number) metrics.get("loadRate")).doubleValue(), 1e-9);
        assertEquals(3.0, ((Number) metrics.get("averageLoadRate")).doubleValue(), 1e-9);

        List<?> stationFlows = (List<?>) panel.get("stationFlows");
        assertStation(stationFlows, 0, "起点", 1, 0);
        assertStation(stationFlows, 1, "中途站", 1, 0);
        assertStation(stationFlows, 2, "终点", 0, 2);

        List<?> segments = (List<?>) panel.get("segments");
        assertEquals(1, ((Number) ((Map<?, ?>) segments.get(0)).get("flow")).intValue());
        assertEquals(2, ((Number) ((Map<?, ?>) segments.get(1)).get("flow")).intValue());

        List<?> stationOd = (List<?>) panel.get("stationOd");
        assertEquals(2, stationOd.size());
        assertEquals(List.of("起点→终点", "中途站→终点"), stationOd.stream()
                .map(item -> {
                    Map<?, ?> row = (Map<?, ?>) item;
                    assertEquals(1, ((Number) row.get("flow")).intValue());
                    return row.get("fromName") + "→" + row.get("toName");
                })
                .toList());

        List<?> transfers = (List<?>) panel.get("transfers");
        assertEquals(1, transfers.size());
        Map<?, ?> transfer = (Map<?, ?>) transfers.getFirst();
        assertEquals("line-b", transfer.get("lineId"));
        assertEquals("接驳2路", transfer.get("lineName"));
        assertEquals("终点", transfer.get("stationName"));
        assertEquals(1, ((Number) transfer.get("flow")).intValue());

        Map<String, Object> latePanel = MatsimRoutePanelCache.readDeparturePanel(
                data, "line-a", "route-a", "dep-late");
        assertEquals(1, ((Number) ((Map<?, ?>) latePanel.get("metrics")).get("passenger")).intValue());
        Map<String, Object> emptyPanel = MatsimRoutePanelCache.readDeparturePanel(
                data, "line-a", "route-a", "dep-empty");
        assertEquals(0, ((Number) ((Map<?, ?>) emptyPanel.get("metrics")).get("passenger")).intValue());
        assertEquals(3, ((List<?>) emptyPanel.get("stationFlows")).size());
        assertTrue(MatsimRoutePanelCache.readDeparturePanel(
                data, "line-a", "route-a", "missing").isEmpty());
    }

    @Test
    void serviceRejectsRealDatasourceBeforeLoadingMatsimData() {
        RouteServiceImpl service = new RouteServiceImpl();
        RouteInfoParam timetable = new RouteInfoParam();
        timetable.setDatasource("real::nansha");
        RouteChartParam panel = new RouteChartParam();
        panel.setDatasource("real::nansha");

        assertThrows(BusinessException.class, () -> service.departureTimetable(timetable));
        assertThrows(BusinessException.class, () -> service.departurePanel(panel));
    }

    private MatsimData buildData() throws Exception {
        Path output = Files.createDirectories(tempDir.resolve("output"));
        Path cache = Files.createDirectories(tempDir.resolve("cache"));
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("config.xml").toString());
        MatsimData data = new MatsimData("departure-panel-test", output.toString(), cache.toString(), false);
        MutableScenario scenario = (MutableScenario) ScenarioUtils.createScenario(ConfigUtils.createConfig());
        data.setScenario(scenario);

        buildNetwork(scenario.getNetwork());
        TransitSchedule schedule = scenario.getTransitSchedule();
        TransitScheduleFactory factory = schedule.getFactory();
        TransitStopFacility start = addStop(schedule, factory, "stop-1", "起点", 0);
        TransitStopFacility middle = addStop(schedule, factory, "stop-2", "中途站", 100);
        TransitStopFacility terminal = addStop(schedule, factory, "stop-3", "终点", 200);
        TransitStopFacility transferEnd = addStop(schedule, factory, "stop-4", "接驳终点", 300);

        TransitRoute routeA = factory.createTransitRoute(
                Id.create("route-a", TransitRoute.class),
                RouteUtils.createLinkNetworkRouteImpl(Id.createLinkId("link-1"), Id.createLinkId("link-2")),
                List.of(
                        factory.createTransitRouteStop(start, 0, 0),
                        factory.createTransitRouteStop(middle, 600, 600),
                        factory.createTransitRouteStop(terminal, 1200, 1200)),
                "bus");
        routeA.setDescription("测试线路上行");
        routeA.addDeparture(departure(factory, "dep-late", 9 * 3600, "bus-late"));
        routeA.addDeparture(departure(factory, "dep-early", 8 * 3600, "bus-early"));
        routeA.addDeparture(departure(factory, "dep-empty", 10 * 3600, "bus-empty"));
        TransitLine lineA = factory.createTransitLine(Id.create("line-a", TransitLine.class));
        lineA.setName("测试1路");
        lineA.addRoute(routeA);
        schedule.addTransitLine(lineA);

        TransitRoute routeB = factory.createTransitRoute(
                Id.create("route-b", TransitRoute.class),
                RouteUtils.createLinkNetworkRouteImpl(Id.createLinkId("link-2"), Id.createLinkId("link-3")),
                List.of(
                        factory.createTransitRouteStop(terminal, 0, 0),
                        factory.createTransitRouteStop(transferEnd, 600, 600)),
                "bus");
        routeB.addDeparture(departure(factory, "dep-b", 8 * 3600 + 1500, "bus-transfer"));
        TransitLine lineB = factory.createTransitLine(Id.create("line-b", TransitLine.class));
        lineB.setName("接驳2路");
        lineB.addRoute(routeB);
        schedule.addTransitLine(lineB);

        addVehicleTypeAndVehicles(scenario, "bus-early", "bus-late", "bus-empty", "bus-transfer");
        data.getPersonTracks().add(track("p1", "line-a", "route-a", "bus-early", "dep-early", "stop-1", true, 8 * 3600));
        data.getPersonTracks().add(track("p1", "line-a", "route-a", "bus-early", "dep-early", "stop-3", false, 8 * 3600 + 1200));
        data.getPersonTracks().add(track("p1", "line-b", "route-b", "bus-transfer", "dep-b", "stop-3", true, 8 * 3600 + 1500));
        data.getPersonTracks().add(track("p1", "line-b", "route-b", "bus-transfer", "dep-b", "stop-4", false, 8 * 3600 + 2100));
        data.getPersonTracks().add(track("p2", "line-a", "route-a", "bus-late", "dep-late", "stop-1", true, 9 * 3600));
        data.getPersonTracks().add(track("p2", "line-a", "route-a", "bus-late", "dep-late", "stop-2", false, 9 * 3600 + 600));
        data.getPersonTracks().add(track("p3", "line-a", "route-a", "bus-early", "dep-early", "stop-2", true, 8 * 3600 + 600));
        data.getPersonTracks().add(track("p3", "line-a", "route-a", "bus-early", "dep-early", "stop-3", false, 8 * 3600 + 1200));
        return data;
    }

    private static void buildNetwork(Network network) {
        NetworkFactory factory = network.getFactory();
        Node previous = factory.createNode(Id.createNodeId("node-1"), new Coord(0, 0));
        network.addNode(previous);
        for (int i = 1; i <= 3; i++) {
            Node next = factory.createNode(Id.createNodeId("node-" + (i + 1)), new Coord(i * 100, 0));
            network.addNode(next);
            Link link = factory.createLink(Id.createLinkId("link-" + i), previous, next);
            link.setLength(100);
            network.addLink(link);
            previous = next;
        }
    }

    private static TransitStopFacility addStop(
            TransitSchedule schedule, TransitScheduleFactory factory, String id, String name, double x) {
        TransitStopFacility stop = factory.createTransitStopFacility(
                Id.create(id, TransitStopFacility.class), new Coord(x, 0), false);
        stop.setName(name);
        schedule.addStopFacility(stop);
        return stop;
    }

    private static Departure departure(TransitScheduleFactory factory, String id, double time, String vehicleId) {
        Departure departure = factory.createDeparture(Id.create(id, Departure.class), time);
        departure.setVehicleId(Id.create(vehicleId, Vehicle.class));
        return departure;
    }

    private static void addVehicleTypeAndVehicles(MutableScenario scenario, String... vehicleIds) {
        VehicleType type = VehicleUtils.createVehicleType(Id.create("bus-type", VehicleType.class));
        type.getCapacity().setSeats(30);
        type.getCapacity().setStandingRoom(20);
        scenario.getTransitVehicles().addVehicleType(type);
        for (String vehicleId : vehicleIds) {
            scenario.getTransitVehicles().addVehicle(
                    VehicleUtils.createVehicle(Id.create(vehicleId, Vehicle.class), type));
        }
    }

    private static PTPersonTrack track(
            String personId, String lineId, String routeId, String vehicleId,
            String departureId, String facilityId, boolean enter, double time) {
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

    private static void assertStation(List<?> stations, int index, String name, int boarding, int alighting) {
        Map<?, ?> station = (Map<?, ?>) stations.get(index);
        assertEquals(name, station.get("facilityName"));
        assertEquals(boarding, ((Number) station.get("boarding")).intValue());
        assertEquals(alighting, ((Number) station.get("alighting")).intValue());
    }
}
