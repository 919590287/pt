package com.jts.gjcxfzksh.data.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jts.gjcxfzksh.data.MatsimData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatsimPanelReadCacheTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void buildsSlimIndexAndReadsExactDetailFromShard() throws Exception {
        MatsimData data = data("panel-read");
        Path panel = tempDir.resolve("route-panel.json.gz");

        Map<String, Object> route = new LinkedHashMap<>();
        route.put("lineId", "L1");
        route.put("lineName", "一号线");
        route.put("routeId", "R1");
        route.put("mode", "bus");
        route.put("hourlyFlow", List.of(3, 7));
        route.put("metrics", Map.of("passenger", 10));
        route.put("segments", List.of(Map.of(
                "flowByHour", List.of(2, 5, 3),
                "loadRateByHour", List.of(10, 88, 32),
                "stationNames", List.of("A", "B")
        )));
        route.put("stationOd", List.of(Map.of("origin", "A", "destination", "B", "flow", 9)));
        route.put("demographics", Map.of("riderCount", 10));

        Map<String, Object> group = new LinkedHashMap<>(route);
        group.put("routeIds", List.of("R1"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ready");
        payload.put("cacheVersion", "route-panel-test");
        payload.put("summary", Map.of("routeCount", 1));
        payload.put("routes", Map.of("L1::R1", route));
        payload.put("lineGroups", Map.of("bus::L1", group));
        writeGzip(panel, payload);

        Map<String, Object> index = MatsimPanelReadCache.readRouteIndex(data, panel);
        assertEquals("index", index.get("payloadKind"));
        Map<?, ?> routes = (Map<?, ?>) index.get("routes");
        Map<?, ?> summary = (Map<?, ?>) routes.get("L1::R1");
        assertEquals(Boolean.TRUE, summary.get("_summary"));
        assertFalse(summary.containsKey("stationOd"));
        assertFalse(summary.containsKey("demographics"));
        Map<?, ?> slimSegment = (Map<?, ?>) ((List<?>) summary.get("segments")).getFirst();
        assertEquals(10.0, ((Number) slimSegment.get("totalFlow")).doubleValue());
        assertFalse(slimSegment.containsKey("peakLoadRate"),
                "轻量索引不应继续派生全天断面最大满载率");

        Map<String, Object> detail = MatsimPanelReadCache.readDetail(
                data, panel, "route", "routes", "L1::R1");
        assertEquals(Map.of("riderCount", 10), detail.get("demographics"));
        assertNotNull(detail.get("stationOd"));

        Map<String, Object> groupDetail = MatsimPanelReadCache.readDetail(
                data, panel, "route", "lineGroups", "bus::L1");
        assertEquals(List.of("R1"), groupDetail.get("routeIds"));

        Path derived;
        try (var children = Files.list(tempDir)) {
            derived = children
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().equals("panel-read-v2-route"))
                    .findFirst()
                    .orElseThrow();
        }
        Files.delete(derived.resolve("index.json.gz"));
        Map<String, Object> rebuilt = MatsimPanelReadCache.readRouteIndex(data, panel);
        assertEquals("index", rebuilt.get("payloadKind"));
        assertTrue(Files.isRegularFile(derived.resolve("index.json.gz")));

        Map<String, Object> changedRoute = new LinkedHashMap<>(route);
        changedRoute.put("lineName", "一号线新名");
        Map<String, Object> changedPayload = new LinkedHashMap<>(payload);
        changedPayload.put("routes", Map.of("L1::R1", changedRoute));
        writeGzip(panel, changedPayload);

        Map<String, Object> replaced = MatsimPanelReadCache.readRouteIndex(data, panel);
        Map<?, ?> replacedRoutes = (Map<?, ?>) replaced.get("routes");
        assertEquals("一号线新名", ((Map<?, ?>) replacedRoutes.get("L1::R1")).get("lineName"));
        try (var children = Files.list(tempDir)) {
            assertEquals(1L, children
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("panel-read-v2-route"))
                    .count());
        }
    }

    @Test
    void stationIndexKeepsHeatmapFieldsAndDefersHeavyDetail() throws Exception {
        MatsimData data = data("station-read");
        Path panel = tempDir.resolve("station-panel.json.gz");
        Map<String, Object> station = new LinkedHashMap<>();
        station.put("stationName", "人民广场");
        station.put("mode", "subway");
        station.put("hourlyFlow", List.of(11, 22));
        station.put("facilityIds", List.of("S1", "S2"));
        station.put("od", List.of(Map.of("destination", "火车站", "flow", 8)));
        station.put("facilityPanels", Map.of("S1", Map.of("boardingByHour", List.of(4, 5))));
        writeGzip(panel, Map.of(
                "status", "ready",
                "summary", Map.of("stationCount", 1),
                "stations", Map.of("人民广场", station)
        ));

        Map<String, Object> index = MatsimPanelReadCache.readStationIndex(data, panel);
        Map<?, ?> stations = (Map<?, ?>) index.get("stations");
        Map<?, ?> summary = (Map<?, ?>) stations.get("人民广场");
        assertEquals("subway", summary.get("mode"));
        assertEquals(List.of(11, 22), summary.get("hourlyFlow"));
        assertEquals(List.of("S1", "S2"), summary.get("facilityIds"));
        assertFalse(summary.containsKey("od"));
        assertFalse(summary.containsKey("facilityPanels"));

        Map<String, Object> detail = MatsimPanelReadCache.readDetail(
                data, panel, "station", "stations", "人民广场");
        assertTrue(detail.containsKey("od"));
        assertTrue(detail.containsKey("facilityPanels"));
    }

    private MatsimData data(String name) throws Exception {
        Path output = tempDir.resolve(name).resolve("output");
        Path cache = tempDir.resolve(name).resolve("cache");
        Files.createDirectories(output);
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());
        return new MatsimData(name, output.toString(), cache.toString(), true);
    }

    private static void writeGzip(Path path, Map<String, Object> value) throws Exception {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(path))) {
            JSON.writeValue(out, value);
        }
    }
}
