package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import com.jts.gjcxfzksh.data.handler.PTHandler;
import com.jts.gjcxfzksh.data.id.RouteId;
import com.jts.gjcxfzksh.data.read.EventReader;
import com.jts.gjcxfzksh.utils.TransitMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实小母本冒烟（线网优化"裁剪测试"模型，events 约 119MB，EPSG:3857 原生坐标）。
 * 默认不参与常规测试：仅在显式传 -Dtransfer.smoke=true 且数据盘已插入时运行——
 * <pre>mvn test -Dtest=MatsimTransferCacheSmokeTest -Dtransfer.smoke=true</pre>
 * 产物写入 MatsimData 默认的 java.io.tmpdir 缓存目录，不触碰数据盘。
 */
class MatsimTransferCacheSmokeTest {

    /** USB 盘路径为项目有意保留的默认路径，勿改。 */
    private static final String OUTPUT_DIR =
            "/Volumes/USB DISK/pt_data/广州市/仿真数据/public/裁剪测试/output";

    @Test
    @EnabledIfSystemProperty(named = "transfer.smoke", matches = "true")
    void buildsTransferCacheOnCutTestModel() throws Exception {
        Path output = Path.of(OUTPUT_DIR);
        assumeTrue(Files.isDirectory(output), "数据盘未插入，跳过冒烟");

        // 1. 读 schedule（模型原生 EPSG:3857，无需再投影）
        MutableScenario scenario = (MutableScenario) ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new TransitScheduleReader(scenario)
                .readFile(output.resolve("output_transitSchedule.xml.gz").toString());

        // 2. 解析 events → PTPersonTrack（与 MatsimAnalysisCache 同一读取路径）
        PTHandler handler = new PTHandler(scenario.getTransitSchedule());
        new EventReader(handler).read(output.resolve("output_events.xml.gz").toString());

        // 3. 组装 MatsimData（缓存目录=默认 tmp），清掉历史冒烟产物保证真跑构建
        MatsimData data = new MatsimData("transfer-smoke-裁剪测试", output.toString());
        data.setScenario(scenario);
        data.setPersonTracks(new LinkedHashSet<>(handler.getPersonTracks()));
        data.setScale(1.0);
        deleteRecursively(MatsimCachePaths.versionDir(data, MatsimTransferCache.TRANSFER_CACHE_VERSION));

        long start = System.currentTimeMillis();
        MatsimTransferCache.prepareOnModelLoad(data);
        long elapsed = System.currentTimeMillis() - start;

        // 4. 三工件就绪 + 内部口径自洽
        assertTrue(MatsimTransferCache.isReady(data));
        Map<String, Object> summary = MatsimTransferCache.readTransferSummary(data);
        assertEquals("ready", summary.get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) summary.get("totals");
        int events = ((Number) totals.get("events")).intValue();
        long busToMetro = ((Number) totals.get("busToMetro")).longValue();
        long metroToBus = ((Number) totals.get("metroToBus")).longValue();
        assertEquals(events, busToMetro + metroToBus);

        byte[] bin = MatsimTransferCache.readEventsBytes(data);
        assertNotNull(bin);
        assertEquals(10 + 23L * events, bin.length);
        ByteBuffer buffer = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(6);
        assertEquals(events, buffer.getInt());

        Map<String, Object> dict = MatsimTransferCache.readTransferDict(data);
        assertEquals(MatsimTransferCache.TRANSFER_CACHE_VERSION, dict.get("version"));
        assertNotNull(MatsimTransferCache.eventsBinTag(data));

        // 5. 与既有 busRailChains 口径交叉对照（口径不同：链 vs 相邻事件+800m，只打印供人工核）
        Map<Object, String> routeModes = new HashMap<>();
        scenario.getTransitSchedule().getTransitLines().values().forEach(line ->
                line.getRoutes().forEach((routeId, route) -> routeModes.put(RouteId.create(routeId),
                        route.getTransportMode() != null && route.getTransportMode().toLowerCase()
                                .matches(".*(subway|metro|rail|train|轨道|地铁).*") ? "subway" : "bus")));
        TransitMetrics.TransferStats stats = TransitMetrics.transferStats(
                data.getPersonTracks(), routeModes::get, 1800);
        System.out.printf(
                "[transfer-smoke] tracks=%d, 构建耗时=%dms, events=%d (公→地 %d / 地→公 %d), persons=%s, "
                        + "droppedTracks=%s, bin=%dB | 参照 busRailChains=%d (口径不同, 仅对照)%n",
                data.getPersonTracks().size(), elapsed, events, busToMetro, metroToBus,
                totals.get("persons"), summary.get("droppedTracks"), bin.length, stats.busRailChains());
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
