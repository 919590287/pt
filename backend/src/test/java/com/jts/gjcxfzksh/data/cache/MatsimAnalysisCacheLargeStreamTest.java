package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.data.Datasource;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import com.jts.gjcxfzksh.data.id.DepartureId;
import com.jts.gjcxfzksh.data.id.LineId;
import com.jts.gjcxfzksh.data.id.PersonId;
import com.jts.gjcxfzksh.data.id.RouteId;
import com.jts.gjcxfzksh.data.id.StopFacilityId;
import com.jts.gjcxfzksh.data.id.VehicleId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatsimAnalysisCacheLargeStreamTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearProperties() {
        System.clearProperty("gjcxfzksh.events.workers");
        System.clearProperty("gjcxfzksh.events.pigz.enabled");
    }

    @Test
    void largeModelTrajectoryCacheStreamsChunksOutsideOutput() throws Exception {
        System.setProperty("gjcxfzksh.events.workers", "4");
        System.setProperty("gjcxfzksh.events.pigz.enabled", "false");
        Path output = tempDir.resolve("model").resolve("output");
        Path cache = tempDir.resolve("pt_cache").resolve("area").resolve("public").resolve("model");
        Files.createDirectories(output);
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());
        writeEvents(output.resolve("output_events.xml.gz"));

        MatsimData data = new MatsimData("area/public/model", output.toString(), cache.toString(), true);
        data.setScenario(buildScenario());

        Map<String, Object> manifest = MatsimAnalysisCache.ensureTrajectoryCache(data);
        byte[] chunk = MatsimAnalysisCache.readTrajectoryBinaryChunk(data, 0);

        assertEquals("ready", manifest.get("status"));
        assertNotNull(chunk);
        assertEquals(64 + 3 * 8 * Float.BYTES, chunk.length);
        Map<?, ?> summary = (Map<?, ?>) manifest.get("summary");
        assertEquals(3, ((Number) summary.get("totalVehicles")).intValue());
        assertEquals(1L, ((Number) summary.get("totalPassengerBoardings")).longValue());
        Map<?, ?> routeBoardings = (Map<?, ?>) summary.get("routeBoardings");
        assertEquals(1, ((Number) routeBoardings.get("route1")).intValue());
        assertPassengerSeriesContainsBoarding(manifest);
        assertPersonTracksContain(cache, "passenger&1");
        Path trajectoryCache = cache.resolve(MatsimAnalysisCache.TRAJECTORY_CACHE_VERSION);
        Path fullManifest = trajectoryCache.resolve("manifest.json");
        Path lightManifest = trajectoryCache.resolve("manifest-lite.json");
        assertTrue(Files.exists(fullManifest));
        assertTrue(Files.exists(lightManifest));
        assertTrue(Files.size(lightManifest) < Files.size(fullManifest));
        Map<String, Object> light = MatsimAnalysisCache.readReadyTrajectoryLightManifest(data);
        assertEquals("ready", light.get("status"));
        assertEquals(2, ((Number) light.get("lightManifestVersion")).intValue());
        assertTrue((Boolean) light.get("lightweight"));
        assertEquals(List.of(), light.get("vehicles"));
        Map<?, ?> lightSummary = (Map<?, ?>) light.get("summary");
        assertEquals(3, ((Number) lightSummary.get("totalVehicles")).intValue());
        Map<?, ?> lightMeta = (Map<?, ?>) light.get("meta");
        assertTrue((Boolean) lightMeta.get("vehicleDetailsDeferred"));
        assertTrue((Boolean) lightMeta.get("routeDetailsDeferred"));
        assertEquals(List.of(), lightMeta.get("vehicles"));
        assertEquals(Map.of(), lightMeta.get("routes"));
        try (Stream<Path> paths = Files.list(cache.resolve(MatsimAnalysisCache.TRAJECTORY_CACHE_VERSION))) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
        assertFalse(Files.exists(output.resolve(".gjcxfzksh-cache")));
    }

    @Test
    void largeModelCacheBuildUsesStreamedPassengerTracksForPanels() throws Exception {
        System.setProperty("gjcxfzksh.events.workers", "2");
        System.setProperty("gjcxfzksh.events.pigz.enabled", "false");
        Path output = tempDir.resolve("model").resolve("output");
        Path cache = tempDir.resolve("pt_cache").resolve("area").resolve("public").resolve("model");
        Files.createDirectories(output);
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());
        writeEvents(output.resolve("output_events.xml.gz"));

        MatsimData data = new MatsimData("area/public/model", output.toString(), cache.toString(), true);
        data.setArea(1);
        data.setScenario(buildScenario());

        invokeDatasourceLoadEvent(data);

        assertEquals(2, data.getPersonTracks().size());

        Map<String, Object> routePanel = MatsimRoutePanelCache.readRoutePanel(data);
        Map<?, ?> routeSummary = (Map<?, ?>) routePanel.get("summary");
        assertEquals(1L, ((Number) routeSummary.get("totalBoardings")).longValue());
        assertEquals(1L, ((Number) routeSummary.get("totalAlightings")).longValue());
        Map<?, ?> routes = (Map<?, ?>) routePanel.get("routes");
        Map<?, ?> route1 = (Map<?, ?>) routes.get("route1");
        Map<?, ?> routeMetrics = (Map<?, ?>) route1.get("metrics");
        assertEquals(1L, ((Number) routeMetrics.get("passenger")).longValue());

        Map<String, Object> stationPanel = MatsimStationPanelCache.readStationPanel(data);
        Map<?, ?> stationSummary = (Map<?, ?>) stationPanel.get("summary");
        assertEquals(1L, ((Number) stationSummary.get("totalBoardings")).longValue());
        assertEquals(1L, ((Number) stationSummary.get("totalAlightings")).longValue());

        Map<String, Object> info = MatsimPrecomputedCache.readInfo(data);
        assertNotNull(info);
        assertEquals(1, ((Number) info.get("rcxcs")).intValue());
    }

    @Test
    void routePanelSeparatesDuplicateRouteIdsAndClassifiesChineseMetro() throws Exception {
        Path output = tempDir.resolve("duplicate-route-id").resolve("output");
        Path cache = tempDir.resolve("duplicate-route-id-cache");
        Files.createDirectories(output);
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());

        MatsimData data = new MatsimData("area/public/duplicate-route-id", output.toString(), cache.toString(), false);
        data.setScenario(buildDuplicateRouteIdScenario());
        data.setPersonTracks(new LinkedHashSet<>(Set.of(
                track("person-bus", "bus-line", "shared", "bus1", "bus-dep", "bus-stop-1", true, 8.0),
                track("person-metro", "metro-line", "shared", "metro1", "metro-dep", "metro-stop-1", true, 9.0),
                track("person-metro-north", "metro-line-north", "north", "metro2", "metro-north-dep", "metro-north-stop-1", true, 10.0),
                track("person-foshan", "foshan-metro-line", "foshan-shared", "foshan1", "foshan-dep", "foshan-stop-1", true, 11.0)
        )));

        MatsimRoutePanelCache.prepareOnModelLoad(data);

        Map<String, Object> panel = MatsimRoutePanelCache.readRoutePanel(data);
        Map<?, ?> routes = (Map<?, ?>) panel.get("routes");
        Map<?, ?> busRoute = (Map<?, ?>) routes.get("bus-line::shared");
        Map<?, ?> numberedBusRoute = (Map<?, ?>) routes.get("busgtfs_ROUTE1494");
        Map<?, ?> metroRoute = (Map<?, ?>) routes.get("metro-line::shared");
        assertNotNull(busRoute);
        assertNotNull(numberedBusRoute);
        assertNotNull(metroRoute);
        assertEquals("bus", busRoute.get("mode"));
        assertEquals("bus", numberedBusRoute.get("mode"));
        assertEquals("subway", metroRoute.get("mode"));
        assertEquals(1L, ((Number) ((Map<?, ?>) busRoute.get("metrics")).get("passenger")).longValue());
        assertEquals(1L, ((Number) ((Map<?, ?>) metroRoute.get("metrics")).get("passenger")).longValue());

        Map<String, Object> metroDetail = MatsimRoutePanelCache.readRoutePanelDetail(data, "metro-line", "shared");
        assertEquals("subway", metroDetail.get("mode"));
        assertEquals(1L, ((Number) ((Map<?, ?>) metroDetail.get("metrics")).get("passenger")).longValue());

        Map<?, ?> lineGroups = (Map<?, ?>) panel.get("lineGroups");
        // 数字编号的公交线（“3号线”）不得被当成地铁分组
        assertFalse(lineGroups.containsKey("metro::3号线"));
        // 地铁1号线 + 1号线北延段 合并为一条线（同线分段）
        Map<?, ?> metroGroup = (Map<?, ?>) lineGroups.get("metro::地铁1号线");
        assertNotNull(metroGroup);
        assertEquals("地铁1号线", metroGroup.get("lineName"));
        assertEquals(2L, ((Number) ((Map<?, ?>) metroGroup.get("metrics")).get("passenger")).longValue());
        // 佛山1号线虽与广州1号线同号，但属不同系统，必须保持独立、不被并入地铁1号线
        Map<?, ?> foshanGroup = (Map<?, ?>) lineGroups.get("metro::佛山1号线");
        assertNotNull(foshanGroup);
        assertEquals("佛山1号线", foshanGroup.get("lineName"));
        assertEquals(1L, ((Number) ((Map<?, ?>) foshanGroup.get("metrics")).get("passenger")).longValue());

        Map<?, ?> summary = (Map<?, ?>) panel.get("summary");
        Map<?, ?> leaderboard = (Map<?, ?>) summary.get("leaderboard");
        List<?> subway = (List<?>) leaderboard.get("subway");
        // 排行榜首位是合并后的地铁1号线(=2)，而非被错误并入佛山1号线的(=3)
        assertEquals("metro::地铁1号线", ((Map<?, ?>) subway.getFirst()).get("lineId"));
        assertEquals(2L, ((Number) ((Map<?, ?>) subway.getFirst()).get("passengerFlow")).longValue());
        // 佛山1号线作为独立线路出现在排行榜中
        Map<String, Object> foshanRow = subway.stream()
                .map(item -> (Map<String, Object>) item)
                .filter(item -> "metro::佛山1号线".equals(item.get("lineId")))
                .findFirst()
                .orElse(null);
        assertNotNull(foshanRow);
        assertEquals("佛山1号线", foshanRow.get("lineName"));
        assertEquals(1L, ((Number) foshanRow.get("passengerFlow")).longValue());
    }

    private void writeEvents(Path path) throws Exception {
        try (OutputStreamWriter writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(path)), StandardCharsets.UTF_8)) {
            writer.write("<events version=\"1.0\">\n");
            writer.write("<event time=\"0.0\" type=\"" + LinkEnterEvent.EVENT_TYPE + "\" vehicle=\"veh1\" link=\"l1\" />\n");
            writer.write("<event time=\"60.0\" type=\"" + LinkLeaveEvent.EVENT_TYPE + "\" vehicle=\"veh1\" link=\"l1\" />\n");
            writer.write("<event time=\"5.0\" type=\"" + LinkEnterEvent.EVENT_TYPE + "\" vehicle=\"veh2\" link=\"l1\" />\n");
            writer.write("<event time=\"65.0\" type=\"" + LinkLeaveEvent.EVENT_TYPE + "\" vehicle=\"veh2\" link=\"l1\" />\n");
            writer.write("<event time=\"8.0\" type=\"" + VehicleArrivesAtFacilityEvent.EVENT_TYPE + "\" vehicle=\"bus1\" facility=\"stop1\" delay=\"0.0\" />\n");
            writer.write("<event time=\"9.0\" type=\"" + PersonEntersVehicleEvent.EVENT_TYPE + "\" person=\"passenger&amp;1\" vehicle=\"bus1\" />\n");
            writer.write("<event time=\"10.0\" type=\"" + VehicleDepartsAtFacilityEvent.EVENT_TYPE + "\" vehicle=\"bus1\" facility=\"stop1\" delay=\"0.0\" />\n");
            writer.write("<event time=\"12.0\" type=\"" + LinkEnterEvent.EVENT_TYPE + "\" vehicle=\"bus1\" link=\"l1\" />\n");
            writer.write("<event time=\"72.0\" type=\"" + LinkLeaveEvent.EVENT_TYPE + "\" vehicle=\"bus1\" link=\"l1\" />\n");
            writer.write("<event time=\"80.0\" type=\"" + VehicleArrivesAtFacilityEvent.EVENT_TYPE + "\" vehicle=\"bus1\" facility=\"stop2\" delay=\"0.0\" />\n");
            writer.write("<event time=\"82.0\" type=\"" + PersonLeavesVehicleEvent.EVENT_TYPE + "\" person=\"passenger&amp;1\" vehicle=\"bus1\" />\n");
            writer.write("</events>\n");
        }
    }

    private MutableScenario buildScenario() {
        MutableScenario scenario = (MutableScenario) ScenarioUtils.createScenario(ConfigUtils.createConfig());
        Network network = scenario.getNetwork();
        NetworkFactory factory = network.getFactory();
        Node from = factory.createNode(Id.createNodeId("n1"), new Coord(0, 0));
        Node to = factory.createNode(Id.createNodeId("n2"), new Coord(100, 0));
        network.addNode(from);
        network.addNode(to);
        Link link = factory.createLink(Id.createLinkId("l1"), from, to);
        network.addLink(link);
        buildTransitSchedule(scenario);
        return scenario;
    }

    private MutableScenario buildDuplicateRouteIdScenario() {
        MutableScenario scenario = (MutableScenario) ScenarioUtils.createScenario(ConfigUtils.createConfig());
        Network network = scenario.getNetwork();
        NetworkFactory networkFactory = network.getFactory();
        Node from = networkFactory.createNode(Id.createNodeId("dup-n1"), new Coord(0, 0));
        Node to = networkFactory.createNode(Id.createNodeId("dup-n2"), new Coord(100, 0));
        network.addNode(from);
        network.addNode(to);
        Link link = networkFactory.createLink(Id.createLinkId("dup-l1"), from, to);
        link.setLength(100);
        network.addLink(link);

        TransitSchedule schedule = scenario.getTransitSchedule();
        TransitScheduleFactory scheduleFactory = schedule.getFactory();
        TransitStopFacility busStop1 = scheduleFactory.createTransitStopFacility(Id.create("bus-stop-1", TransitStopFacility.class), new Coord(0, 0), false);
        TransitStopFacility busStop2 = scheduleFactory.createTransitStopFacility(Id.create("bus-stop-2", TransitStopFacility.class), new Coord(100, 0), false);
        TransitStopFacility metroStop1 = scheduleFactory.createTransitStopFacility(Id.create("metro-stop-1", TransitStopFacility.class), new Coord(0, 10), false);
        TransitStopFacility metroStop2 = scheduleFactory.createTransitStopFacility(Id.create("metro-stop-2", TransitStopFacility.class), new Coord(100, 10), false);
        TransitStopFacility metroNorthStop1 = scheduleFactory.createTransitStopFacility(Id.create("metro-north-stop-1", TransitStopFacility.class), new Coord(100, 10), false);
        TransitStopFacility metroNorthStop2 = scheduleFactory.createTransitStopFacility(Id.create("metro-north-stop-2", TransitStopFacility.class), new Coord(200, 10), false);
        schedule.addStopFacility(busStop1);
        schedule.addStopFacility(busStop2);
        schedule.addStopFacility(metroStop1);
        schedule.addStopFacility(metroStop2);
        schedule.addStopFacility(metroNorthStop1);
        schedule.addStopFacility(metroNorthStop2);

        TransitLine busLine = scheduleFactory.createTransitLine(Id.create("bus-line", TransitLine.class));
        busLine.setName("公交快线");
        TransitRoute busRoute = routeWithDeparture(scheduleFactory, "shared", "bus", "bus1", "bus-dep", busStop1, busStop2);
        busRoute.setDescription("嘉禾望岗地铁站(B出口) - 空港大道");
        busLine.addRoute(busRoute);
        schedule.addTransitLine(busLine);

        TransitLine numberedBusLine = scheduleFactory.createTransitLine(Id.create("numbered-bus-line", TransitLine.class));
        numberedBusLine.setName("3号线");
        TransitRoute numberedBusRoute = routeWithDeparture(scheduleFactory, "busgtfs_ROUTE1494", "pt", "bus3", "bus3-dep", busStop1, busStop2);
        numberedBusRoute.setDescription("嘉禾望岗地铁站(B出口) - 科甲水站");
        numberedBusLine.addRoute(numberedBusRoute);
        schedule.addTransitLine(numberedBusLine);

        TransitLine metroLine = scheduleFactory.createTransitLine(Id.create("metro-line", TransitLine.class));
        metroLine.setName("地铁1号线");
        metroLine.addRoute(routeWithDeparture(scheduleFactory, "shared", "pt", "metro1", "metro-dep", metroStop1, metroStop2));
        schedule.addTransitLine(metroLine);

        TransitLine metroNorthLine = scheduleFactory.createTransitLine(Id.create("metro-line-north", TransitLine.class));
        metroNorthLine.setName("地铁1号线北延段");
        metroNorthLine.addRoute(routeWithDeparture(scheduleFactory, "north", "pt", "metro2", "metro-north-dep", metroNorthStop1, metroNorthStop2));
        schedule.addTransitLine(metroNorthLine);

        // 佛山1号线与广州地铁1号线同为“1号线”但属不同系统，必须各自独立，绝不能被合并。
        TransitStopFacility foshanStop1 = scheduleFactory.createTransitStopFacility(Id.create("foshan-stop-1", TransitStopFacility.class), new Coord(0, 20), false);
        TransitStopFacility foshanStop2 = scheduleFactory.createTransitStopFacility(Id.create("foshan-stop-2", TransitStopFacility.class), new Coord(100, 20), false);
        schedule.addStopFacility(foshanStop1);
        schedule.addStopFacility(foshanStop2);
        TransitLine foshanLine = scheduleFactory.createTransitLine(Id.create("foshan-metro-line", TransitLine.class));
        foshanLine.setName("佛山1号线");
        foshanLine.addRoute(routeWithDeparture(scheduleFactory, "foshan-shared", "pt", "foshan1", "foshan-dep", foshanStop1, foshanStop2));
        schedule.addTransitLine(foshanLine);

        VehicleType vehicleType = VehicleUtils.createVehicleType(Id.create("dup-vehicle-type", VehicleType.class));
        vehicleType.getCapacity().setSeats(40);
        vehicleType.getCapacity().setStandingRoom(60);
        scenario.getTransitVehicles().addVehicleType(vehicleType);
        scenario.getTransitVehicles().addVehicle(VehicleUtils.createVehicle(Id.create("bus1", Vehicle.class), vehicleType));
        scenario.getTransitVehicles().addVehicle(VehicleUtils.createVehicle(Id.create("bus3", Vehicle.class), vehicleType));
        scenario.getTransitVehicles().addVehicle(VehicleUtils.createVehicle(Id.create("metro1", Vehicle.class), vehicleType));
        scenario.getTransitVehicles().addVehicle(VehicleUtils.createVehicle(Id.create("metro2", Vehicle.class), vehicleType));
        scenario.getTransitVehicles().addVehicle(VehicleUtils.createVehicle(Id.create("foshan1", Vehicle.class), vehicleType));
        return scenario;
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
                RouteUtils.createLinkNetworkRouteImpl(Id.createLinkId("dup-l1"), Id.createLinkId("dup-l1")),
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

    private void buildTransitSchedule(MutableScenario scenario) {
        TransitSchedule schedule = scenario.getTransitSchedule();
        TransitScheduleFactory factory = schedule.getFactory();
        TransitStopFacility stop1 = factory.createTransitStopFacility(
                Id.create("stop1", TransitStopFacility.class),
                new Coord(0, 0),
                false
        );
        TransitStopFacility stop2 = factory.createTransitStopFacility(
                Id.create("stop2", TransitStopFacility.class),
                new Coord(100, 0),
                false
        );
        schedule.addStopFacility(stop1);
        schedule.addStopFacility(stop2);

        TransitRouteStop routeStop1 = factory.createTransitRouteStop(stop1, 0.0, 0.0);
        TransitRouteStop routeStop2 = factory.createTransitRouteStop(stop2, 60.0, 60.0);
        Departure departure = factory.createDeparture(Id.create("dep1", Departure.class), 0.0);
        departure.setVehicleId(Id.create("bus1", Vehicle.class));
        TransitRoute route = factory.createTransitRoute(
                Id.create("route1", TransitRoute.class),
                RouteUtils.createLinkNetworkRouteImpl(Id.createLinkId("l1"), Id.createLinkId("l1")),
                List.of(routeStop1, routeStop2),
                "bus"
        );
        route.addDeparture(departure);

        TransitLine line = factory.createTransitLine(Id.create("line1", TransitLine.class));
        line.addRoute(route);
        schedule.addTransitLine(line);

        VehicleType busType = VehicleUtils.createVehicleType(Id.create("bus", VehicleType.class));
        busType.getCapacity().setSeats(40);
        busType.getCapacity().setStandingRoom(30);
        scenario.getTransitVehicles().addVehicleType(busType);
        scenario.getTransitVehicles().addVehicle(VehicleUtils.createVehicle(Id.create("bus1", Vehicle.class), busType));
    }

    private void invokeDatasourceLoadEvent(MatsimData data) throws Exception {
        Method loadEvent = Datasource.class.getDeclaredMethod(
                "loadEvent",
                MatsimData.class,
                MatsimAnalysisCache.BuildProgress.class
        );
        loadEvent.setAccessible(true);
        loadEvent.invoke(null, data, null);
    }

    private void assertPassengerSeriesContainsBoarding(Map<String, Object> manifest) {
        List<?> rows = (List<?>) manifest.get("passengerSeries");
        assertTrue(rows.stream().anyMatch(row -> {
            List<?> values = (List<?>) row;
            return ((Number) values.get(0)).intValue() == 9
                    && ((Number) values.get(1)).longValue() == 1L
                    && ((Number) values.get(4)).longValue() == 1L;
        }));
    }

    private void assertPersonTracksContain(Path cache, String value) throws Exception {
        Path tracks = cache.resolve("pt-events-v2").resolve("person-tracks.tsv.gz");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(tracks)), StandardCharsets.UTF_8))) {
            assertTrue(reader.lines().anyMatch(line -> line.contains(value)));
        }
    }
}
