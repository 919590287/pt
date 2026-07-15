package com.jts.gjcxfzksh.data.cache;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 面板派生读取单测：overallFlow 服务端聚合（问题6）与 stationPanelDetail 单站明细。
 */
class PanelDerivedReadTest {

    @Test
    void overallFlowAggregatesByModeLikeFrontend() {
        Map<String, Object> busRoute = new LinkedHashMap<>();
        busRoute.put("mode", "bus");
        busRoute.put("lineName", "5路");
        busRoute.put("hourlyFlow", hourly(10));
        busRoute.put("metrics", Map.of("vehicles", 12, "departures", 100, "routeDist", 10000.0));

        Map<String, Object> metroRoute = new LinkedHashMap<>();
        metroRoute.put("mode", "subway");
        metroRoute.put("lineName", "地铁1号线");
        metroRoute.put("hourlyFlow", hourly(7));
        // 轨道的车辆/班次与公交不可比：busOperation 聚合必须跳过
        metroRoute.put("metrics", Map.of("vehicles", 5, "departures", 50, "routeDist", 30000.0));

        // 名称含“地铁”但 mode 缺失：必须按前端 routeModeKey 口径归入 metro
        Map<String, Object> metroByName = new LinkedHashMap<>();
        metroByName.put("lineName", "地铁2号线");
        metroByName.put("hourlyFlow", hourly(3));

        Map<String, Object> routes = new LinkedHashMap<>();
        routes.put("L1::R1", busRoute);
        routes.put("L2::R2", metroRoute);
        routes.put("L3::R3", metroByName);

        Map<String, Object> panel = new LinkedHashMap<>();
        panel.put("status", "ready");
        panel.put("routes", routes);

        Map<String, Object> result = MatsimRoutePanelCache.overallFlowFromPanel(panel);
        assertEquals("ready", result.get("status"));

        @SuppressWarnings("unchecked")
        Map<String, List<Double>> hourlyByMode = (Map<String, List<Double>>) result.get("hourlyByMode");
        assertEquals(24, hourlyByMode.get("bus").size());
        assertEquals(10.0, hourlyByMode.get("bus").get(0));
        assertEquals(10.0, hourlyByMode.get("bus").get(23));
        assertEquals(7.0 + 3.0, hourlyByMode.get("metro").get(5));

        // 公交运营效率分母：仅常规公交计入，日运营车公里 = Σ班次 × 线长(km)
        @SuppressWarnings("unchecked")
        Map<String, Object> busOperation = (Map<String, Object>) result.get("busOperation");
        assertEquals(12L, busOperation.get("vehicles"));
        assertEquals(100L, busOperation.get("departures"));
        assertEquals(100 * 10.0, busOperation.get("operatedKm"));
    }

    @Test
    void overallFlowPassesThroughGeneratingStatus() {
        Map<String, Object> generating = Map.of("status", "generating", "message", "生成中");
        Map<String, Object> result = MatsimRoutePanelCache.overallFlowFromPanel(new LinkedHashMap<>(generating));
        assertEquals("generating", result.get("status"));
        assertTrue(result.get("hourlyByMode") == null);
    }

    @Test
    void stationDetailFindsExactAndNormalizedName() {
        Map<String, Object> station = Map.of("name", "火车站", "totalBoardings", 42);
        Map<String, Object> panel = Map.of(
                "status", "ready",
                "stations", Map.of("火车站", station)
        );

        Map<String, Object> exact = MatsimStationPanelCache.stationDetailFromPanel(panel, "火车站");
        assertEquals(42, exact.get("totalBoardings"));

        Map<String, Object> normalized = MatsimStationPanelCache.stationDetailFromPanel(panel, " 火车站 ");
        assertEquals(42, normalized.get("totalBoardings"));

        Map<String, Object> missing = MatsimStationPanelCache.stationDetailFromPanel(panel, "不存在的站");
        assertTrue(missing.isEmpty());

        Map<String, Object> blank = MatsimStationPanelCache.stationDetailFromPanel(panel, " ");
        assertTrue(blank.isEmpty());
    }

    @Test
    void stationDetailPassesThroughGeneratingStatus() {
        Map<String, Object> generating = Map.of("status", "generating");
        Map<String, Object> result = MatsimStationPanelCache.stationDetailFromPanel(generating, "火车站");
        assertEquals("generating", result.get("status"));
    }

    private static List<Double> hourly(double value) {
        Double[] values = new Double[24];
        for (int i = 0; i < 24; i++) {
            values[i] = value;
        }
        return List.of(values);
    }
}
