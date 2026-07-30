package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.api.model.params.RouteChartParam;
import com.jts.gjcxfzksh.api.model.vo.FacilityFlowVO;
import com.jts.gjcxfzksh.api.model.vo.RouteDetailVO;
import com.jts.gjcxfzksh.api.model.vo.RoutePickVO;
import com.jts.gjcxfzksh.api.service.impl.RouteServiceImpl;
import com.jts.gjcxfzksh.data.Datasource;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.entry.Database;
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
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PopulationFactory;
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
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        writeNetwork(output.resolve("output_network.xml.gz"), false);

        MatsimData data = new MatsimData("area/public/model", output.toString(), cache.toString(), true);
        data.setScenario(buildScenario());

        Map<String, Object> manifest = MatsimAnalysisCache.ensureTrajectoryCache(data);
        byte[] chunk = MatsimAnalysisCache.readTrajectoryBinaryChunk(data, 0);
        byte[] publicFrame = MatsimAnalysisCache.readTrajectoryBinaryFrame(
                data, 30, 1, "public", -10.0, -10.0, 110.0, 10.0
        );
        byte[] privateFrame = MatsimAnalysisCache.readTrajectoryBinaryFrame(
                data, 30, 1, "private", -10.0, -10.0, 110.0, 10.0
        );
        byte[] outsideFrame = MatsimAnalysisCache.readTrajectoryBinaryFrame(
                data, 30, 1, "all", 1000.0, 1000.0, 1100.0, 1100.0
        );

        assertEquals("ready", manifest.get("status"));
        assertNotNull(chunk);
        assertEquals(64 + 3 * 9 * Float.BYTES, chunk.length);
        assertEquals(1, binarySegmentCount(publicFrame));
        assertEquals(2, binarySegmentCount(privateFrame));
        assertEquals(0, binarySegmentCount(outsideFrame));
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
        Path spatialContainer = trajectoryCache.resolve("spatial-000000.bin");
        Path spatialIndex = trajectoryCache.resolve("spatial-000000.idx");
        assertTrue(Files.exists(fullManifest));
        assertTrue(Files.exists(lightManifest));
        assertTrue(Files.exists(spatialContainer));
        assertTrue(Files.exists(spatialIndex));
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
        writeNetwork(output.resolve("output_network.xml.gz"), false);

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
        Map<?, ?> routeDemographics = (Map<?, ?>) route1.get("demographics");
        assertEquals(1, ((Number) routeDemographics.get("riderCount")).intValue());
        Set<String> routeActivities = activityKeys((List<?>) routeDemographics.get("activityTypes"));
        assertEquals(Set.of(), routeActivities,
                "缺 TransitPassengerRoute 时不得拿乘客全部活动冒充本次出行目的");
        assertEquals("trip-purpose", routeDemographics.get("activitySource"));

        Map<String, Object> stationPanel = MatsimStationPanelCache.readStationPanel(data);
        Map<?, ?> stationSummary = (Map<?, ?>) stationPanel.get("summary");
        assertEquals(1L, ((Number) stationSummary.get("totalBoardings")).longValue());
        assertEquals(1L, ((Number) stationSummary.get("totalAlightings")).longValue());
        Map<?, ?> stations = (Map<?, ?>) stationPanel.get("stations");
        Map<?, ?> stop1 = (Map<?, ?>) stations.get("stop1");
        Map<?, ?> stationDemographics = (Map<?, ?>) stop1.get("demographics");
        Set<String> stationActivities = activityKeys((List<?>) stationDemographics.get("activityTypes"));
        assertEquals(Set.of(), stationActivities,
                "缺明确的 access stop 与目的活动映射时不得回退到全活动画像");
        assertEquals("trip-purpose", stationDemographics.get("activitySource"));

        Map<String, Object> info = MatsimPrecomputedCache.readInfo(data);
        assertNotNull(info);
        assertEquals(1, ((Number) info.get("boardings")).intValue());
        assertNull(info.get("rcxcs"), "日出行次数缺少 plans journeys/人口分母时不得用上车人次冒充");
        Map<?, ?> availability = (Map<?, ?>) info.get("availability");
        assertEquals("unsupported", ((Map<?, ?>) availability.get("rcxcs")).get("status"));
    }

    @Test
    void smallModelBuildCreatesPassengerAndTrajectoryCachesTogether() throws Exception {
        Path output = tempDir.resolve("small-model").resolve("output");
        Path cache = tempDir.resolve("pt_cache").resolve("area").resolve("public").resolve("small-model");
        Files.createDirectories(output);
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());
        writeEvents(output.resolve("output_events.xml.gz"));

        MatsimData data = new MatsimData("area/public/small-model", output.toString(), cache.toString(), false);
        data.setArea(1);
        data.setScenario(buildScenario());

        MatsimAnalysisCache.prepareAllOnModelLoad(data, null);

        assertEquals(2, data.getPersonTracks().size());
        assertNotNull(MatsimAnalysisCache.readReadyTrajectoryManifest(data));
        assertTrue(MatsimAnalysisCache.preloadPersonTracksIfReady(data));
        try (Stream<Path> paths = Files.list(cache.resolve(MatsimAnalysisCache.TRAJECTORY_CACHE_VERSION))) {
            assertTrue(paths.anyMatch(path -> path.getFileName().toString().endsWith(".bin")));
        }
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
        MatsimPrecomputedCache.prepareOnModelLoad(data);

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
        Map<?, ?> metroGroupDemographics = (Map<?, ?>) metroGroup.get("demographics");
        assertEquals(2, ((Number) metroGroupDemographics.get("riderCount")).intValue());
        Set<String> metroGroupActivities = activityKeys((List<?>) metroGroupDemographics.get("activityTypes"));
        assertEquals(Set.of(), metroGroupActivities,
                "缺显式 TransitPassengerRoute 时不得从全活动列表猜测线路出行目的");
        assertEquals("trip-purpose", metroGroupDemographics.get("activitySource"));
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

        RouteDetailVO busDetail = MatsimPrecomputedCache.readRouteDetail(data, "bus-line", "shared");
        RouteDetailVO metroDetailFromCache = MatsimPrecomputedCache.readRouteDetail(data, "metro-line", "shared");
        assertNotNull(busDetail);
        assertNotNull(metroDetailFromCache);
        assertEquals("bus", busDetail.getTransportMode());
        assertEquals("pt", metroDetailFromCache.getTransportMode());
        assertEquals("bus-stop-1", busDetail.getFacilities().getFirst().getFacilityId());
        assertEquals("metro-stop-1", metroDetailFromCache.getFacilities().getFirst().getFacilityId());

        List<RoutePickVO> candidates = MatsimRouteSpatialIndex.query(data, 50.0, 0.0, 80.0, 10);
        long sharedCandidates = candidates.stream()
                .filter(candidate -> "shared".equals(candidate.getRouteId()))
                .count();
        assertEquals(2L, sharedCandidates);
        assertTrue(candidates.stream().anyMatch(candidate ->
                "bus-line".equals(candidate.getLineId()) && "shared".equals(candidate.getRouteId())));
        assertTrue(candidates.stream().anyMatch(candidate ->
                "metro-line".equals(candidate.getLineId()) && "shared".equals(candidate.getRouteId())));
    }

    @Test
    void stationPanelCachesInMemoryAndKeepsDuplicateRouteIdsSeparate() throws Exception {
        Path output = tempDir.resolve("station-duplicate-route-id").resolve("output");
        Path cache = tempDir.resolve("station-duplicate-route-id-cache");
        Files.createDirectories(output);
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());

        MatsimData data = new MatsimData("area/public/station-duplicate-route-id", output.toString(), cache.toString(), false);
        data.setScenario(buildDuplicateRouteIdScenario());
        data.setPersonTracks(new LinkedHashSet<>(Set.of(
                track("person-bus", "bus-line", "shared", "bus1", "bus-dep", "bus-stop-1", true, 8.0),
                track("person-bus", "bus-line", "shared", "bus1", "bus-dep", "bus-stop-2", false, 68.0),
                track("person-metro", "metro-line", "shared", "metro1", "metro-dep", "metro-stop-1", true, 9.0)
        )));

        MatsimStationPanelCache.prepareOnModelLoad(data);
        Map<String, Object> firstRead = MatsimStationPanelCache.readStationPanel(data);
        Map<?, ?> stations = (Map<?, ?>) firstRead.get("stations");
        Map<?, ?> busStop = (Map<?, ?>) stations.get("bus-stop-1");
        List<?> routes = (List<?>) busStop.get("routes");
        assertTrue(routes.stream().anyMatch(route ->
                route instanceof Map<?, ?> item
                        && "bus-line".equals(item.get("lineId"))
                        && "shared".equals(item.get("routeId"))));

        // od 必须按 lineId::routeId 解析线路：routeId "shared" 同时属于公交与地铁，裸 routeId 查找会得到空线路信息
        List<?> od = (List<?>) busStop.get("od");
        assertTrue(od.stream().anyMatch(entry ->
                entry instanceof Map<?, ?> item
                        && "bus-stop-1".equals(item.get("origin"))
                        && "bus-stop-2".equals(item.get("destination"))
                        && "shared".equals(item.get("routeId"))
                        && "公交快线".equals(item.get("lineName"))));

        Path panelPath = cache.resolve(MatsimStationPanelCache.STATION_PANEL_CACHE_VERSION).resolve("station-panel.json.gz");
        Files.writeString(panelPath, "not gzip", StandardCharsets.UTF_8);
        Map<String, Object> secondRead = MatsimStationPanelCache.readStationPanel(data);
        assertEquals("ready", secondRead.get("status"));
        assertEquals(firstRead, secondRead);
    }

    @Test
    void routeFlowTreatsNullSingleAsFalseAndUsesLineScopedRoute() throws Exception {
        String datasource = "area/public/route-flow-null-single";
        Path output = tempDir.resolve("route-flow-null-single").resolve("output");
        Path cache = tempDir.resolve("route-flow-null-single-cache");
        Files.createDirectories(output);
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());

        MatsimData data = new MatsimData(datasource, output.toString(), cache.toString(), false);
        data.setScenario(buildDuplicateRouteIdScenario());
        data.setPersonTracks(new LinkedHashSet<>(Set.of(
                track("person-bus", "bus-line", "shared", "bus1", "bus-dep", "bus-stop-1", true, 8.0),
                track("person-metro", "metro-line", "shared", "metro1", "metro-dep", "metro-stop-1", true, 9.0)
        )));
        registerDatasource(datasource, data);
        try {
            RouteChartParam param = new RouteChartParam();
            param.setDatasource(datasource);
            param.setLineId("metro-line");
            param.setRouteId("shared");
            param.setSingle(null);

            List<FacilityFlowVO> flow = new RouteServiceImpl().routeFlow(param);

            assertEquals(2, flow.size());
            assertEquals("metro-stop-1", flow.getFirst().getId());
            assertEquals(1L, flow.getFirst().getUp());
        } finally {
            Datasource.remove(datasource);
        }
    }

    @Test
    void largeModelRouteFlowReadsHourlyPanelInsteadOfScanningTrackStore() throws Exception {
        String datasource = "area/public/large-route-flow";
        Path output = tempDir.resolve("large-route-flow").resolve("output");
        Path cache = tempDir.resolve("large-route-flow-cache");
        Files.createDirectories(output);
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());

        MatsimData data = new MatsimData(datasource, output.toString(), cache.toString(), true);
        data.setScenario(buildDuplicateRouteIdScenario());
        data.setPersonTracks(new LinkedHashSet<>(Set.of(
                track("person-bus", "bus-line", "shared", "bus1", "bus-dep", "bus-stop-1", true, 8.0),
                track("person-metro", "metro-line", "shared", "metro1", "metro-dep", "metro-stop-1", true, 9.0)
        )));
        MatsimRoutePanelCache.prepareOnModelLoad(data);
        // 模拟 V6 运行时：面板建好后全量明细不常驻堆内。
        data.setPersonTracks(new LinkedHashSet<>());
        registerDatasource(datasource, data);
        try {
            RouteChartParam param = new RouteChartParam();
            param.setDatasource(datasource);
            param.setLineId("metro-line");
            param.setRouteId("shared");
            param.setBeginSecond(0);
            param.setEndSecond(3600);

            List<FacilityFlowVO> flow = new RouteServiceImpl().routeFlow(param);

            assertEquals(2, flow.size());
            assertEquals("metro-stop-1", flow.getFirst().getId());
            assertEquals(1L, flow.getFirst().getUp());
        } finally {
            Datasource.remove(datasource);
        }
    }

    @Test
    void largeTrajectoryUsesCompleteSourceNetworkForPrivateCarLinksOutsideTransitSubnetwork() throws Exception {
        System.setProperty("gjcxfzksh.events.workers", "2");
        System.setProperty("gjcxfzksh.events.pigz.enabled", "false");
        Path output = tempDir.resolve("full-network-car").resolve("output");
        Path cache = tempDir.resolve("full-network-car-cache");
        Files.createDirectories(output);
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());
        writeNetwork(output.resolve("output_network.xml.gz"), true);
        writeEvents(output.resolve("output_events.xml.gz"), List.of(
                "<event time=\"0.0\" type=\"entered link\" vehicle=\"bus1\" link=\"l1\" />",
                "<event time=\"30.0\" type=\"left link\" vehicle=\"bus1\" link=\"l1\" />",
                "<event time=\"0.0\" type=\"entered link\" vehicle=\"car1\" link=\"car-link\" />",
                "<event time=\"30.0\" type=\"left link\" vehicle=\"car1\" link=\"car-link\" />"
        ));

        MatsimData data = new MatsimData("area/public/full-network-car", output.toString(), cache.toString(), true);
        // 运行态只注入公交子网 l1；car-link 只存在于原始完整 network。
        data.setScenario(buildScenario());

        Map<String, Object> manifest = MatsimAnalysisCache.ensureTrajectoryCache(data);
        byte[] privateFrame = MatsimAnalysisCache.readTrajectoryBinaryFrame(
                data, 15, 1, "private", 900.0, -10.0, 1210.0, 10.0
        );

        Map<?, ?> summary = (Map<?, ?>) manifest.get("summary");
        Map<?, ?> counts = (Map<?, ?>) summary.get("vehicleCountByMode");
        Map<?, ?> distance = (Map<?, ?>) summary.get("distanceKmByMode");
        Map<?, ?> quality = (Map<?, ?>) manifest.get("quality");
        assertEquals(1L, ((Number) counts.get("car")).longValue());
        assertEquals(0.2, ((Number) distance.get("car")).doubleValue(), 1e-9);
        assertEquals(1, binarySegmentCount(privateFrame));
        assertEquals(0L, ((Number) quality.get("missingLinkEvents")).longValue());
        assertEquals(2, ((Number) quality.get("fullNetworkLinks")).intValue());
        double[] endpoints = binaryFirstSegmentAbsoluteEndpoints(privateFrame);
        assertEquals(1000.0, endpoints[0], 0.1);
        assertEquals(1200.0, endpoints[2], 0.1);

        Map<?, ?> sources = (Map<?, ?>) manifest.get("sources");
        assertEquals(Set.of("events", "network", "schedule", "transitVehicles", "config", "plans"), sources.keySet());
        assertFalse(String.valueOf(manifest.get("cacheGeneration")).isBlank());

        // events 未变但完整 network 变化时，旧轨迹不得继续被判定为可用。
        writeNetwork(output.resolve("output_network.xml.gz"), false);
        assertNull(MatsimAnalysisCache.readReadyTrajectoryLightManifest(data));
    }

    @Test
    void spatialViewportCropsSeparatedCarsButKeepsCitywideStatsAndTwoArtifactsPerStorageChunk() throws Exception {
        System.setProperty("gjcxfzksh.events.workers", "2");
        System.setProperty("gjcxfzksh.events.pigz.enabled", "false");
        Path output = tempDir.resolve("spatial-cars").resolve("output");
        Path cache = tempDir.resolve("spatial-cars-cache");
        Files.createDirectories(output);
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());
        writeSeparatedPrivateCarNetwork(output.resolve("output_network.xml.gz"));
        writeEvents(output.resolve("output_events.xml.gz"), List.of(
                "<event time=\"0.0\" type=\"entered link\" vehicle=\"near-car\" link=\"near-link\" />",
                "<event time=\"30.0\" type=\"left link\" vehicle=\"near-car\" link=\"near-link\" />",
                "<event time=\"0.0\" type=\"entered link\" vehicle=\"far-car\" link=\"far-link\" />",
                "<event time=\"30.0\" type=\"left link\" vehicle=\"far-car\" link=\"far-link\" />"
        ));

        MatsimData data = new MatsimData("area/public/spatial-cars", output.toString(), cache.toString(), true);
        data.setScenario(buildScenario());

        Map<String, Object> manifest = MatsimAnalysisCache.ensureTrajectoryCache(data);
        byte[] firstWindow = MatsimAnalysisCache.readTrajectoryBinaryViewport(
                data, 0, 10, "private", 900.0, -100.0, 1300.0, 100.0
        );
        byte[] secondWindow = MatsimAnalysisCache.readTrajectoryBinaryViewport(
                data, 10, 10, "private", 900.0, -100.0, 1300.0, 100.0
        );

        assertEquals(1, binarySegmentCount(firstWindow), "视口不得携带 9km 外的另一辆车");
        assertEquals(1, binarySegmentCount(secondWindow));
        assertEquals(List.of(0.0, 30.0, 1000.0, 1200.0), binaryTimeAndX(firstWindow));
        assertEquals(binaryTimeAndX(firstWindow), binaryTimeAndX(secondWindow),
                "跨 10s 播放窗应复用完整原始段，保证边界插值连续");
        assertEquals(List.of(0, 9, 10), binaryWindowHeader(firstWindow));
        assertEquals(List.of(10, 19, 10), binaryWindowHeader(secondWindow));

        Map<?, ?> summary = (Map<?, ?>) manifest.get("summary");
        Map<?, ?> firstChunk = (Map<?, ?>) ((List<?>) summary.get("chunks")).getFirst();
        assertEquals(2, ((Number) firstChunk.get("tileCount")).intValue());
        assertEquals(2, ((Number) firstChunk.get("artifactFiles")).intValue());
        List<?> globalSecond15 = (List<?>) ((List<?>) firstChunk.get("globalStats")).get(15);
        assertEquals(15, ((Number) globalSecond15.get(0)).intValue());
        assertEquals(2, ((Number) globalSecond15.get(3)).intValue(),
                "视口裁剪不能把全市活跃小汽车数裁成 1");

        Path trajectoryDir = cache.resolve(MatsimAnalysisCache.TRAJECTORY_CACHE_VERSION);
        Set<String> storageArtifacts;
        try (Stream<Path> paths = Files.list(trajectoryDir)) {
            storageArtifacts = paths
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("spatial-000000"))
                    .collect(java.util.stream.Collectors.toSet());
        }
        assertEquals(Set.of("spatial-000000.bin", "spatial-000000.idx"), storageArtifacts,
                "每个 30s 存储块只允许一个容器和一个偏移索引，禁止生成 tile 小文件");

        String firstGeneration = String.valueOf(manifest.get("cacheGeneration"));
        Path spatialIndex = trajectoryDir.resolve("spatial-000000.idx");
        Files.delete(spatialIndex);
        assertNull(MatsimAnalysisCache.readTrajectoryBinaryViewport(
                data, 0, 10, "private", 900.0, -100.0, 1300.0, 100.0
        ));
        assertTrue(MatsimAnalysisCache.isTrajectoryRepairRequired(data));
        assertNull(MatsimAnalysisCache.readReadyTrajectoryLightManifest(data),
                "缺失声明工件后当前 generation 不得继续保持 ready");

        ExecutorService repairPool = Executors.newFixedThreadPool(4);
        Set<String> repairedGenerations = new LinkedHashSet<>();
        try {
            List<Callable<Map<String, Object>>> repairs = List.of(
                    () -> MatsimAnalysisCache.ensureTrajectoryCache(data),
                    () -> MatsimAnalysisCache.ensureTrajectoryCache(data),
                    () -> MatsimAnalysisCache.ensureTrajectoryCache(data),
                    () -> MatsimAnalysisCache.ensureTrajectoryCache(data)
            );
            for (Future<Map<String, Object>> repair : repairPool.invokeAll(repairs)) {
                repairedGenerations.add(String.valueOf(repair.get().get("cacheGeneration")));
            }
        } finally {
            repairPool.shutdownNow();
        }
        assertEquals(1, repairedGenerations.size(), "并发请求只能发布一个修复 generation");
        String repairedGeneration = repairedGenerations.iterator().next();
        assertNotEquals(firstGeneration, repairedGeneration);
        assertFalse(MatsimAnalysisCache.isTrajectoryRepairRequired(data));
        assertEquals(1, binarySegmentCount(MatsimAnalysisCache.readTrajectoryBinaryViewport(
                data, 0, 10, "private", 900.0, -100.0, 1300.0, 100.0
        )));

        Path spatialContainer = trajectoryDir.resolve("spatial-000000.bin");
        Files.write(spatialContainer, new byte[]{'B', 'A', 'D'}, StandardOpenOption.TRUNCATE_EXISTING);
        assertThrows(IllegalStateException.class, () -> MatsimAnalysisCache.readTrajectoryBinaryViewport(
                data, 0, 10, "private", 900.0, -100.0, 1300.0, 100.0));
        assertTrue(MatsimAnalysisCache.isTrajectoryRepairRequired(data));
        Map<String, Object> repairedCorruption = MatsimAnalysisCache.ensureTrajectoryCache(data);
        assertNotEquals(repairedGeneration, String.valueOf(repairedCorruption.get("cacheGeneration")));
        assertFalse(MatsimAnalysisCache.isTrajectoryRepairRequired(data));
        assertEquals(1, binarySegmentCount(MatsimAnalysisCache.readTrajectoryBinaryViewport(
                data, 0, 10, "private", 900.0, -100.0, 1300.0, 100.0
        )));
    }

    @Test
    void perTileEnvelopePreventsOneLongLinkFromPullingUnrelatedTilesIntoViewportRead() throws Exception {
        System.setProperty("gjcxfzksh.events.workers", "2");
        System.setProperty("gjcxfzksh.events.pigz.enabled", "false");
        Path output = tempDir.resolve("long-link-envelope").resolve("output");
        Path cache = tempDir.resolve("long-link-envelope-cache");
        Files.createDirectories(output);
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());
        writeLongAndFarPrivateCarNetwork(output.resolve("output_network.xml.gz"));
        writeEvents(output.resolve("output_events.xml.gz"), List.of(
                "<event time=\"0.0\" type=\"entered link\" vehicle=\"long-car\" link=\"long-link\" />",
                "<event time=\"30.0\" type=\"left link\" vehicle=\"long-car\" link=\"long-link\" />",
                "<event time=\"0.0\" type=\"entered link\" vehicle=\"far-car\" link=\"far-short-link\" />",
                "<event time=\"30.0\" type=\"left link\" vehicle=\"far-car\" link=\"far-short-link\" />"
        ));
        MatsimData data = new MatsimData("area/public/long-link-envelope", output.toString(), cache.toString(), true);
        data.setScenario(buildScenario());

        Map<String, Object> manifest = MatsimAnalysisCache.ensureTrajectoryCache(data);
        byte[] local = MatsimAnalysisCache.readTrajectoryBinaryViewport(
                data, 0, 10, "private", -10.0, -10.0, 100.0, 10.0
        );

        assertEquals(1, MatsimAnalysisCache.trajectorySpatialCandidateCount(
                data, 0, -10.0, -10.0, 100.0, 10.0
        ), "超长段的 envelope 可命中视口，但不得把相邻远处短段 tile 一并读入");
        assertEquals(1, binarySegmentCount(local));
        assertEquals(List.of(0.0, 30.0, 0.0, 20000.0), binaryTimeAndX(local));
        Map<?, ?> firstChunk = (Map<?, ?>) ((List<?>) ((Map<?, ?>) manifest.get("summary")).get("chunks")).getFirst();
        assertEquals(2, ((Number) firstChunk.get("tileCount")).intValue());
        List<?> globalSecond15 = (List<?>) ((List<?>) firstChunk.get("globalStats")).get(15);
        assertEquals(2, ((Number) globalSecond15.get(3)).intValue());
        Map<?, ?> spatial = (Map<?, ?>) manifest.get("spatial");
        assertEquals(2, ((Number) spatial.get("indexVersion")).intValue());
        assertEquals(32, ((Number) spatial.get("indexEntryBytes")).intValue());
    }

    @Test
    void completeNetworkGeometryIndexTransformsDeclaredSourceCrsToWebMercator() throws Exception {
        Path output = tempDir.resolve("geometry-crs").resolve("output");
        Path cache = tempDir.resolve("geometry-crs-cache");
        Files.createDirectories(output);
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());
        writeWgs84Network(output.resolve("output_network.xml.gz"));

        MatsimData data = new MatsimData("area/public/geometry-crs", output.toString(), cache.toString(), true);
        data.setScenario(buildScenario());
        MatsimLinkGeometryIndex index = MatsimLinkGeometryIndex.load(data);
        int link = index.find("geo-link");

        assertTrue(link >= 0);
        assertEquals("EPSG:4326", index.sourceCrs());
        assertEquals(111.319, index.toX(link) - index.fromX(link), 0.5);
        assertEquals(0.0, index.toY(link) - index.fromY(link), 0.5);
    }

    @Test
    void largeTrajectoryFailsInsteadOfPublishingWhenEventsReferenceUnknownLink() throws Exception {
        System.setProperty("gjcxfzksh.events.workers", "1");
        System.setProperty("gjcxfzksh.events.pigz.enabled", "false");
        Path output = tempDir.resolve("unknown-link").resolve("output");
        Path cache = tempDir.resolve("unknown-link-cache");
        Files.createDirectories(output);
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());
        writeNetwork(output.resolve("output_network.xml.gz"), false);
        writeEvents(output.resolve("output_events.xml.gz"), List.of(
                "<event time=\"0.0\" type=\"entered link\" vehicle=\"car1\" link=\"missing-link\" />",
                "<event time=\"10.0\" type=\"left link\" vehicle=\"car1\" link=\"missing-link\" />"
        ));
        MatsimData data = new MatsimData("area/public/unknown-link", output.toString(), cache.toString(), true);
        data.setScenario(buildScenario());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> MatsimAnalysisCache.ensureTrajectoryCache(data)
        );
        assertTrue(error.getMessage().contains("不存在的 link"));
        assertFalse(Files.exists(cache.resolve(MatsimAnalysisCache.TRAJECTORY_CACHE_VERSION).resolve("manifest.json")));
        assertFalse(Files.exists(cache.resolve(MatsimAnalysisCache.TRAJECTORY_CACHE_VERSION).resolve("manifest-lite.json")));
    }

    @Test
    void smallTrajectoryAlsoFailsInsteadOfPublishingPartialCacheForUnknownLinks() throws Exception {
        Path output = tempDir.resolve("small-unknown-link").resolve("output");
        Path cache = tempDir.resolve("small-unknown-link-cache");
        Files.createDirectories(output);
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());
        writeEvents(output.resolve("output_events.xml.gz"), List.of(
                "<event time=\"0.0\" type=\"entered link\" vehicle=\"car1\" link=\"missing-link\" />",
                "<event time=\"10.0\" type=\"left link\" vehicle=\"car1\" link=\"missing-link\" />"
        ));
        MatsimData data = new MatsimData("area/public/small-unknown-link", output.toString(), cache.toString(), false);
        data.setScenario(buildScenario());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> MatsimAnalysisCache.ensureTrajectoryCache(data)
        );

        assertTrue(error.getMessage().contains("不存在的 link"));
        assertFalse(Files.exists(cache.resolve(MatsimAnalysisCache.TRAJECTORY_CACHE_VERSION).resolve("manifest.json")));
        assertFalse(Files.exists(cache.resolve(MatsimAnalysisCache.TRAJECTORY_CACHE_VERSION).resolve("manifest-lite.json")));
    }

    @Test
    void thirtySecondChunksClipCrossBoundarySegmentsAndPublishNewGenerationInPlace() throws Exception {
        System.setProperty("gjcxfzksh.events.workers", "1");
        System.setProperty("gjcxfzksh.events.pigz.enabled", "false");
        Path output = tempDir.resolve("chunk-clipping").resolve("output");
        Path cache = tempDir.resolve("chunk-clipping-cache");
        Files.createDirectories(output);
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());
        writeNetwork(output.resolve("output_network.xml.gz"), false);
        writeEvents(output.resolve("output_events.xml.gz"), List.of(
                "<event time=\"0.0\" type=\"entered link\" vehicle=\"car1\" link=\"l1\" />",
                "<event time=\"60.0\" type=\"left link\" vehicle=\"car1\" link=\"l1\" />"
        ));
        Path oldVersion = cache.resolve("trajectory-v9");
        Files.createDirectories(oldVersion);
        Files.writeString(oldVersion.resolve("sentinel"), "old", StandardCharsets.UTF_8);
        Path oldPersonTrackVersion = cache.resolve("pt-events-v2");
        Files.createDirectories(oldPersonTrackVersion);
        Files.writeString(oldPersonTrackVersion.resolve("sentinel"), "old", StandardCharsets.UTF_8);

        MatsimData data = new MatsimData("area/public/chunk-clipping", output.toString(), cache.toString(), true);
        data.setScenario(buildScenario());
        Map<String, Object> firstManifest = MatsimAnalysisCache.ensureTrajectoryCache(data);
        Map<String, Object> firstCachedManifest = MatsimAnalysisCache.readReadyTrajectoryLightManifest(data);
        assertSame(firstCachedManifest, MatsimAnalysisCache.readReadyTrajectoryLightManifest(data),
                "连续播放的 ETag/body 读取必须复用同一已解析 manifest");
        byte[] first = MatsimAnalysisCache.readTrajectoryBinaryChunk(data, 0);
        byte[] second = MatsimAnalysisCache.readTrajectoryBinaryChunk(data, 30);
        byte[] after = MatsimAnalysisCache.readTrajectoryBinaryChunk(data, 60);
        byte[] deliveryBeforeBoundary = MatsimAnalysisCache.readTrajectoryBinaryViewport(
                data, 0, 10, "all", -10.0, -10.0, 110.0, 10.0
        );
        byte[] deliveryAfterBoundary = MatsimAnalysisCache.readTrajectoryBinaryViewport(
                data, 10, 10, "all", -10.0, -10.0, 110.0, 10.0
        );
        byte[] arbitraryViewport = MatsimAnalysisCache.readTrajectoryBinaryViewport(
                data, 28, 7, "all", -10.0, -10.0, 110.0, 10.0
        );
        byte[] arbitraryFrameBeforeBoundary = MatsimAnalysisCache.readTrajectoryBinaryFrame(
                data, 29, 7, "all", -10.0, -10.0, 110.0, 10.0
        );
        byte[] arbitraryFrameAfterBoundary = MatsimAnalysisCache.readTrajectoryBinaryFrame(
                data, 30, 7, "all", -10.0, -10.0, 110.0, 10.0
        );

        assertEquals(10, ((Number) firstManifest.get("chunkSeconds")).intValue());
        assertEquals(30, ((Number) firstManifest.get("storageChunkSeconds")).intValue());
        assertEquals(1, binarySegmentCount(first));
        assertEquals(1, binarySegmentCount(second));
        assertEquals(0, binarySegmentCount(after));
        assertEquals(List.of(0.0, 30.0, 0.0, 50.0), binaryTimeAndX(first));
        assertEquals(List.of(30.0, 60.0, 50.0, 100.0), binaryTimeAndX(second));
        assertEquals(1, binarySegmentCount(deliveryBeforeBoundary));
        assertEquals(1, binarySegmentCount(deliveryAfterBoundary));
        assertEquals(List.of(0.0, 30.0, 0.0, 50.0), binaryTimeAndX(deliveryBeforeBoundary));
        assertEquals(List.of(0.0, 30.0, 0.0, 50.0), binaryTimeAndX(deliveryAfterBoundary));
        assertEquals(10, ByteBuffer.wrap(deliveryAfterBoundary).order(ByteOrder.LITTLE_ENDIAN).getInt(48));
        assertEquals(1, binarySegmentCount(arbitraryViewport));
        assertEquals(List.of(20, 29, 10), binaryWindowHeader(arbitraryViewport),
                "任意 viewport 参数必须规范化为不会跨 30s 容器的固定 10s 窗");
        assertEquals(1, binarySegmentCount(arbitraryFrameBeforeBoundary));
        assertEquals(1, binarySegmentCount(arbitraryFrameAfterBoundary));
        assertEquals(List.of(28, 29, 2), binaryWindowHeader(arbitraryFrameBeforeBoundary));
        assertEquals(List.of(30, 31, 2), binaryWindowHeader(arbitraryFrameAfterBoundary));
        assertEquals(
                MatsimAnalysisCache.trajectoryViewportETag(
                        data, 20, 10, "all", -10.0, -10.0, 110.0, 10.0
                ),
                MatsimAnalysisCache.trajectoryViewportETag(
                        data, 28, 7, "all", -10.0, -10.0, 110.0, 10.0
                ),
                "ETag 必须按实际规范化后的窗口与可见模式生成"
        );
        assertThrows(IllegalArgumentException.class, () ->
                MatsimAnalysisCache.trajectoryViewportETag(
                        data, 28, 7, "unexpected", -10.0, -10.0, 110.0, 10.0));
        Map<?, ?> firstChunk = (Map<?, ?>) ((List<?>) ((Map<?, ?>) firstManifest.get("summary")).get("chunks")).get(0);
        assertEquals(2, ((Number) firstChunk.get("artifactFiles")).intValue());
        assertEquals(30, ((List<?>) firstChunk.get("globalStats")).size());
        assertFalse(Files.exists(oldVersion));
        assertFalse(Files.exists(oldPersonTrackVersion));

        String firstGeneration = String.valueOf(firstManifest.get("cacheGeneration"));
        String firstEtag = MatsimAnalysisCache.trajectoryChunkETag(data, 0);
        Path trajectoryDir = cache.resolve(MatsimAnalysisCache.TRAJECTORY_CACHE_VERSION);
        Path staleArtifact = trajectoryDir.resolve("stale-from-previous-generation.bin");
        Files.writeString(staleArtifact, "stale", StandardCharsets.UTF_8);
        Files.delete(trajectoryDir.resolve("manifest.json"));
        Files.delete(trajectoryDir.resolve("manifest-lite.json"));
        Map<String, Object> secondManifest = MatsimAnalysisCache.ensureTrajectoryCache(data);
        Map<String, Object> secondCachedManifest = MatsimAnalysisCache.readReadyTrajectoryLightManifest(data);
        String secondGeneration = String.valueOf(secondManifest.get("cacheGeneration"));
        String secondEtag = MatsimAnalysisCache.trajectoryChunkETag(data, 0);
        assertNotSame(firstCachedManifest, secondCachedManifest, "原位重建后不得复用上一代 manifest 对象");
        assertEquals(secondGeneration, secondCachedManifest.get("cacheGeneration"));
        assertSame(secondCachedManifest, MatsimAnalysisCache.readReadyTrajectoryLightManifest(data));
        assertNotEquals(firstGeneration, secondGeneration);
        assertNotEquals(firstEtag, secondEtag);
        assertFalse(Files.exists(staleArtifact));
        try (Stream<Path> paths = Files.list(trajectoryDir)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
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

    private void writeEvents(Path path, List<String> events) throws Exception {
        try (OutputStreamWriter writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(path)), StandardCharsets.UTF_8)) {
            writer.write("<events version=\"1.0\">\n");
            for (String event : events) {
                writer.write(event);
                writer.write('\n');
            }
            writer.write("</events>\n");
        }
    }

    private void writeNetwork(Path path, boolean includePrivateCarLink) throws Exception {
        String privateNodes = includePrivateCarLink
                ? "<node id=\"car-from\" x=\"1000\" y=\"0\"/><node id=\"car-to\" x=\"1200\" y=\"0\"/>"
                : "";
        String privateLink = includePrivateCarLink
                ? "<link id=\"car-link\" from=\"car-from\" to=\"car-to\" length=\"200\" freespeed=\"20\" capacity=\"1000\" permlanes=\"1\" modes=\"car\"/>"
                : "";
        try (OutputStreamWriter writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(path)), StandardCharsets.UTF_8)) {
            writer.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE network SYSTEM "http://www.matsim.org/files/dtd/network_v2.dtd">
                    <network>
                      <attributes><attribute name="coordinateReferenceSystem" class="java.lang.String">EPSG:3857</attribute></attributes>
                      <nodes><node id="n1" x="0" y="0"/><node id="n2" x="100" y="0"/>%s</nodes>
                      <links capperiod="01:00:00">
                        <link id="l1" from="n1" to="n2" length="100" freespeed="10" capacity="1000" permlanes="1" modes="car,bus"/>%s
                      </links>
                    </network>
                    """.formatted(privateNodes, privateLink));
        }
    }

    private void writeSeparatedPrivateCarNetwork(Path path) throws Exception {
        try (OutputStreamWriter writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(path)), StandardCharsets.UTF_8)) {
            writer.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE network SYSTEM "http://www.matsim.org/files/dtd/network_v2.dtd">
                    <network>
                      <attributes><attribute name="coordinateReferenceSystem" class="java.lang.String">EPSG:3857</attribute></attributes>
                      <nodes>
                        <node id="n1" x="0" y="0"/><node id="n2" x="100" y="0"/>
                        <node id="near-from" x="1000" y="0"/><node id="near-to" x="1200" y="0"/>
                        <node id="far-from" x="10000" y="0"/><node id="far-to" x="10200" y="0"/>
                      </nodes>
                      <links capperiod="01:00:00">
                        <link id="l1" from="n1" to="n2" length="100" freespeed="10" capacity="1000" permlanes="1" modes="car,bus"/>
                        <link id="near-link" from="near-from" to="near-to" length="200" freespeed="20" capacity="1000" permlanes="1" modes="car"/>
                        <link id="far-link" from="far-from" to="far-to" length="200" freespeed="20" capacity="1000" permlanes="1" modes="car"/>
                      </links>
                    </network>
                    """);
        }
    }

    private void writeLongAndFarPrivateCarNetwork(Path path) throws Exception {
        try (OutputStreamWriter writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(path)), StandardCharsets.UTF_8)) {
            writer.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE network SYSTEM "http://www.matsim.org/files/dtd/network_v2.dtd">
                    <network>
                      <attributes><attribute name="coordinateReferenceSystem" class="java.lang.String">EPSG:3857</attribute></attributes>
                      <nodes>
                        <node id="n1" x="0" y="0"/><node id="n2" x="100" y="0"/>
                        <node id="long-from" x="0" y="0"/><node id="long-to" x="20000" y="0"/>
                        <node id="far-from" x="15000" y="0"/><node id="far-to" x="15100" y="0"/>
                      </nodes>
                      <links capperiod="01:00:00">
                        <link id="l1" from="n1" to="n2" length="100" freespeed="10" capacity="1000" permlanes="1" modes="car,bus"/>
                        <link id="long-link" from="long-from" to="long-to" length="20000" freespeed="30" capacity="1000" permlanes="1" modes="car"/>
                        <link id="far-short-link" from="far-from" to="far-to" length="100" freespeed="20" capacity="1000" permlanes="1" modes="car"/>
                      </links>
                    </network>
                    """);
        }
    }

    private void writeWgs84Network(Path path) throws Exception {
        try (OutputStreamWriter writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(path)), StandardCharsets.UTF_8)) {
            writer.write("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE network SYSTEM "http://www.matsim.org/files/dtd/network_v2.dtd">
                    <network>
                      <attributes><attribute name="coordinateReferenceSystem" class="java.lang.String">EPSG:4326</attribute></attributes>
                      <nodes><node id="from" x="121.0" y="31.0"/><node id="to" x="121.001" y="31.0"/></nodes>
                      <links capperiod="01:00:00">
                        <link id="geo-link" from="from" to="to" length="100" freespeed="10" capacity="1000" permlanes="1" modes="car"/>
                      </links>
                    </network>
                    """);
        }
    }

    private int binarySegmentCount(byte[] bytes) {
        assertNotNull(bytes);
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(16);
    }

    private double[] binaryFirstSegmentAbsoluteEndpoints(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        double originX = buffer.getDouble(32);
        double originY = buffer.getDouble(40);
        int headerBytes = Short.toUnsignedInt(buffer.getShort(6));
        return new double[]{
                originX + buffer.getFloat(headerBytes + 2 * Float.BYTES),
                originY + buffer.getFloat(headerBytes + 3 * Float.BYTES),
                originX + buffer.getFloat(headerBytes + 4 * Float.BYTES),
                originY + buffer.getFloat(headerBytes + 5 * Float.BYTES)
        };
    }

    private List<Double> binaryTimeAndX(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        double originX = buffer.getDouble(32);
        int headerBytes = Short.toUnsignedInt(buffer.getShort(6));
        return List.of(
                (double) buffer.getFloat(headerBytes),
                (double) buffer.getFloat(headerBytes + Float.BYTES),
                originX + buffer.getFloat(headerBytes + 2 * Float.BYTES),
                originX + buffer.getFloat(headerBytes + 4 * Float.BYTES)
        );
    }

    private List<Integer> binaryWindowHeader(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        return List.of(buffer.getInt(8), buffer.getInt(12), buffer.getInt(48));
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
        addPersonWithActivities(scenario, "passenger&1", List.of("home", "gym"), List.of("work"));
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
        addPersonWithActivities(scenario, "person-bus", List.of("home", "work"), List.of());
        addPersonWithActivities(scenario, "person-metro", List.of("home", "airport"), List.of());
        addPersonWithActivities(scenario, "person-metro-north", List.of("home", "airport"), List.of("shop"));
        addPersonWithActivities(scenario, "person-foshan", List.of("home", "school"), List.of());
        return scenario;
    }

    private void addPersonWithActivities(MutableScenario scenario, String personId, List<String> selectedActivities, List<String> alternateActivities) {
        PopulationFactory factory = scenario.getPopulation().getFactory();
        Person person = factory.createPerson(Id.createPersonId(personId));
        Plan selectedPlan = activityPlan(factory, selectedActivities);
        person.addPlan(selectedPlan);
        person.setSelectedPlan(selectedPlan);
        if (!alternateActivities.isEmpty()) {
            person.addPlan(activityPlan(factory, alternateActivities));
        }
        scenario.getPopulation().addPerson(person);
    }

    private Plan activityPlan(PopulationFactory factory, List<String> activities) {
        Plan plan = factory.createPlan();
        List<String> source = activities.isEmpty() ? List.of("home") : activities;
        for (int index = 0; index < source.size(); index++) {
            plan.addActivity(factory.createActivityFromCoord(source.get(index), new Coord(index * 100, 0)));
            if (index + 1 < source.size()) {
                plan.addLeg(factory.createLeg("pt"));
            }
        }
        return plan;
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

    @SuppressWarnings("unchecked")
    private void registerDatasource(String name, MatsimData data) throws Exception {
        Field dataMapField = Datasource.class.getDeclaredField("dataMap");
        dataMapField.setAccessible(true);
        ((Map<String, Database>) dataMapField.get(null)).put(name, new Database(data));
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
        Path tracks = cache.resolve("pt-events-v3").resolve("person-tracks.tsv.gz");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(tracks)), StandardCharsets.UTF_8))) {
            assertTrue(reader.lines().anyMatch(line -> line.contains(value)));
        }
    }

    private Set<String> activityKeys(List<?> activities) {
        Set<String> keys = new LinkedHashSet<>();
        if (activities == null) {
            return keys;
        }
        for (Object item : activities) {
            if (item instanceof Map<?, ?> map && map.get("key") != null) {
                keys.add(map.get("key").toString());
            }
        }
        return keys;
    }
}
