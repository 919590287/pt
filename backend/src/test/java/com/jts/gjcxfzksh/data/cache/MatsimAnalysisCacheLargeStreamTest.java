package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.data.Datasource;
import com.jts.gjcxfzksh.data.MatsimData;
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
import java.util.Map;
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
        Path tracks = cache.resolve("pt-events-v1").resolve("person-tracks.tsv.gz");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(tracks)), StandardCharsets.UTF_8))) {
            assertTrue(reader.lines().anyMatch(line -> line.contains(value)));
        }
    }
}
