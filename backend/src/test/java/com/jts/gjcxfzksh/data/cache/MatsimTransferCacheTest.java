package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import com.jts.gjcxfzksh.data.id.LineId;
import com.jts.gjcxfzksh.data.id.PersonId;
import com.jts.gjcxfzksh.data.id.RouteId;
import com.jts.gjcxfzksh.data.id.StopFacilityId;
import com.jts.gjcxfzksh.data.id.VehicleId;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 换乘缓存纯单测（不依赖 Spring/数据盘）：§3 识别口径（时间窗/距离/制式/tram 隔断/边界值）、
 * 枢纽质心聚类防链式传播、bin 列式布局逐列回读、summary/dict（含契约补充 boardings/alightings）、
 * Mercator cos(lat) 距离修正精度。
 */
class MatsimTransferCacheTest {

    private static final double EARTH_RADIUS = 6378137.0;

    // ---------------------------------------------------------------- 构造工具

    private static PTPersonTrack track(String person, String line, String route, String vehicle,
                                       String facility, Boolean enter, double time) {
        PTPersonTrack track = new PTPersonTrack();
        track.setPersonId(person == null ? null : PersonId.create(person));
        track.setLineId(line == null ? null : LineId.create(line));
        track.setRouteId(route == null ? null : RouteId.create(route));
        track.setVehicleId(vehicle == null ? null : VehicleId.create(vehicle));
        track.setFacilityId(facility == null ? null : StopFacilityId.create(facility));
        track.setEnter(enter);
        track.setTime(time);
        return track;
    }

    /** key = lineId::routeId → 制式；返回 (lineId, mode)，未登记返回 null（未知制式）。 */
    private static BiFunction<String, String, MatsimTransferCache.RouteRef> resolver(Map<String, String> modes) {
        return (lineId, routeId) -> {
            String mode = modes.get(lineId + "::" + routeId);
            return mode == null ? null : new MatsimTransferCache.RouteRef(lineId, mode);
        };
    }

    /** 赤道附近坐标：cos(lat)=1，平面距离即地面距离，便于口算距离阈值。 */
    private static Map<String, double[]> equatorCoords(Object... pairs) {
        Map<String, double[]> coords = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            coords.put((String) pairs[i], new double[]{((Number) pairs[i + 1]).doubleValue(), 0.0});
        }
        return coords;
    }

    private static List<PTPersonTrack> shuffled(List<PTPersonTrack> tracks) {
        List<PTPersonTrack> copy = new ArrayList<>(tracks);
        Collections.shuffle(copy, new Random(42)); // 固定种子：乱序但可复现
        return copy;
    }

    private static double[] mercator(double lon, double lat) {
        double x = EARTH_RADIUS * Math.toRadians(lon);
        double y = EARTH_RADIUS * Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2));
        return new double[]{x, y};
    }

    private static double haversine(double lon1, double lat1, double lon2, double lat2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        return 2 * EARTH_RADIUS * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** 按 §11.2 布局逐列回读 bin。 */
    private record Decoded(int version, int count, int[] person, long[] tBoard, int[] sec, int[] dir,
                           int[] busLine, int[] busRoute, int[] busStop, int[] metroLine, int[] metroStop,
                           int[] hub) {
    }

    private static Decoded decode(byte[] bin) {
        ByteBuffer buffer = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        buffer.get(magic);
        assertArrayEquals(new byte[]{'T', 'F', 'E', 'V'}, magic, "magic 必须为 ASCII TFEV");
        int version = Short.toUnsignedInt(buffer.getShort());
        int count = buffer.getInt();
        int[] person = new int[count];
        long[] tBoard = new long[count];
        int[] sec = new int[count];
        int[] dir = new int[count];
        int[] busLine = new int[count];
        int[] busRoute = new int[count];
        int[] busStop = new int[count];
        int[] metroLine = new int[count];
        int[] metroStop = new int[count];
        int[] hub = new int[count];
        for (int i = 0; i < count; i++) person[i] = buffer.getInt();
        for (int i = 0; i < count; i++) tBoard[i] = Integer.toUnsignedLong(buffer.getInt());
        for (int i = 0; i < count; i++) sec[i] = Short.toUnsignedInt(buffer.getShort());
        for (int i = 0; i < count; i++) dir[i] = Byte.toUnsignedInt(buffer.get());
        for (int i = 0; i < count; i++) busLine[i] = Short.toUnsignedInt(buffer.getShort());
        for (int i = 0; i < count; i++) busRoute[i] = Short.toUnsignedInt(buffer.getShort());
        for (int i = 0; i < count; i++) busStop[i] = Short.toUnsignedInt(buffer.getShort());
        for (int i = 0; i < count; i++) metroLine[i] = Short.toUnsignedInt(buffer.getShort());
        for (int i = 0; i < count; i++) metroStop[i] = Short.toUnsignedInt(buffer.getShort());
        for (int i = 0; i < count; i++) hub[i] = Short.toUnsignedInt(buffer.getShort());
        assertEquals(0, buffer.remaining(), "bin 不得有多余字节（无对齐填充）");
        return new Decoded(version, count, person, tBoard, sec, dir, busLine, busRoute, busStop,
                metroLine, metroStop, hub);
    }

    // ---------------------------------------------------------------- 综合固定样本

    private static final Map<String, String> FIXTURE_MODES = Map.of(
            "busL_1::R0", MatsimTransferCache.MODE_BUS,
            "busL_1::R1", MatsimTransferCache.MODE_BUS,
            "busL_2::R2", MatsimTransferCache.MODE_BUS,
            "metroL_1::MR1", MatsimTransferCache.MODE_SUBWAY
    );

    private static Map<String, double[]> fixtureCoords() {
        return equatorCoords(
                "bus_A", -5000, "bus_B", 0, "bus_C", 10300, "bus_D", 20000,
                "metro_1", 500, "metro_2", 10000, "metro_3", 700);
    }

    private static Map<String, String> fixtureNames() {
        Map<String, String> names = new HashMap<>();
        names.put("bus_A", "A站");
        names.put("bus_B", "B站");
        names.put("bus_C", "C站");
        names.put("bus_D", "D站");
        names.put("metro_1", "Zhan A");
        names.put("metro_2", "Zhan B");
        names.put("metro_3", "Zhan A");
        return names;
    }

    /**
     * 4 个事件：p1 公→地(600s) + 地→公(450s)、p3 公→地(300s, R0 方向)、p2 跨零点地→公(200s, tBoard=90000)。
     */
    private static List<PTPersonTrack> fixtureTracks() {
        List<PTPersonTrack> tracks = new ArrayList<>();
        // p1: bus(busL_1/R1) → metro → bus(busL_2/R2)，一人贡献两次换乘
        tracks.add(track("p1", "busL_1", "R1", "vb1", "bus_A", true, 1000));
        tracks.add(track("p1", "busL_1", "R1", "vb1", "bus_B", false, 2000));
        tracks.add(track("p1", "metroL_1", "MR1", "vm1", "metro_1", true, 2600));
        tracks.add(track("p1", "metroL_1", "MR1", "vm1", "metro_2", false, 3000));
        tracks.add(track("p1", "busL_2", "R2", "vb2", "bus_C", true, 3450));
        tracks.add(track("p1", "busL_2", "R2", "vb2", "bus_D", false, 5000));
        // p3: bus(busL_1/R0) → metro，验证 busRoute 线内局部索引
        tracks.add(track("p3", "busL_1", "R0", "vb5", "bus_A", true, 6000));
        tracks.add(track("p3", "busL_1", "R0", "vb5", "bus_B", false, 6500));
        tracks.add(track("p3", "metroL_1", "MR1", "vm5", "metro_1", true, 6800));
        tracks.add(track("p3", "metroL_1", "MR1", "vm5", "metro_2", false, 7000));
        // p2: 跨零点 metro → bus，tBoard=90000（25:00）验证小时夹逼
        tracks.add(track("p2", "metroL_1", "MR1", "vm3", "metro_2", true, 88000));
        tracks.add(track("p2", "metroL_1", "MR1", "vm3", "metro_1", false, 89800));
        tracks.add(track("p2", "busL_1", "R1", "vb3", "bus_B", true, 90000));
        tracks.add(track("p2", "busL_1", "R1", "vb3", "bus_A", false, 91000));
        return tracks;
    }

    private static MatsimTransferCache.Artifacts fixtureArtifacts() {
        Map<String, double[]> coords = fixtureCoords();
        Map<String, String> names = fixtureNames();
        MatsimTransferCache.HubClusters hubs = MatsimTransferCache.clusterHubs(
                List.of("metro_1", "metro_2", "metro_3"), coords, names);
        MatsimTransferCache.TransferComputation computation = MatsimTransferCache.computeTransfers(
                shuffled(fixtureTracks()), resolver(FIXTURE_MODES), coords);
        Map<String, String> lineNames = Map.of("busL_1", "1路", "busL_2", "2路", "metroL_1", "1号线");
        Map<String, String> routeNames = Map.of("busL_1::R0", "上行", "busL_1::R1", "下行");
        return MatsimTransferCache.assemble(computation, hubs, lineNames, routeNames, names, coords, 0.1);
    }

    // ---------------------------------------------------------------- 事件识别

    @Test
    void identifiesCrossModeTransfersFromShuffledTracks() {
        MatsimTransferCache.TransferComputation computation = MatsimTransferCache.computeTransfers(
                shuffled(fixtureTracks()), resolver(FIXTURE_MODES), fixtureCoords());
        assertEquals(4, computation.events.size());
        assertEquals(0, computation.droppedTracks);
        long busToMetro = computation.events.stream()
                .filter(e -> e.dir() == MatsimTransferCache.DIR_BUS_TO_METRO).count();
        assertEquals(2, busToMetro);
        // p1 的公→地事件：tBoard=后序（地铁）上车时刻，transferSec=600，站点取公交下车站/轨道上车站
        MatsimTransferCache.RawEvent first = computation.events.stream()
                .filter(e -> e.tBoard() == 2600).findFirst().orElseThrow();
        assertEquals(600, first.transferSec());
        assertEquals(MatsimTransferCache.DIR_BUS_TO_METRO, first.dir());
        assertEquals("bus_B", first.busStopId());
        assertEquals("metro_1", first.metroStopId());
        assertEquals("busL_1", first.busLineId());
        assertEquals("R1", first.busRouteId());
        assertEquals("metroL_1", first.metroLineId());
        // p2 的地→公事件：busStop=公交上车站，metroStop=轨道下车站
        MatsimTransferCache.RawEvent last = computation.events.stream()
                .filter(e -> e.tBoard() == 90000).findFirst().orElseThrow();
        assertEquals(MatsimTransferCache.DIR_METRO_TO_BUS, last.dir());
        assertEquals(200, last.transferSec());
        assertEquals("bus_B", last.busStopId());
        assertEquals("metro_1", last.metroStopId());
    }

    @Test
    void sameModeAndTramNeverProduceEventsAndTramBreaksAdjacency() {
        Map<String, String> modes = new HashMap<>(FIXTURE_MODES);
        modes.put("tramL::TR1", MatsimTransferCache.MODE_TRAM);
        Map<String, double[]> coords = equatorCoords(
                "bus_B", 0, "tram_1", 100, "tram_2", 200, "metro_1", 300, "metro_9", 5000, "bus_A", -3000);
        List<PTPersonTrack> tracks = new ArrayList<>();
        // p1: bus → tram → metro，全部满足时窗与距离，但 tram 段两头都不算且隔断相邻性 → 0 事件
        tracks.add(track("p1", "busL_1", "R1", "v1", "bus_A", true, 100));
        tracks.add(track("p1", "busL_1", "R1", "v1", "bus_B", false, 1000));
        tracks.add(track("p1", "tramL", "TR1", "v2", "tram_1", true, 1200));
        tracks.add(track("p1", "tramL", "TR1", "v2", "tram_2", false, 1500));
        tracks.add(track("p1", "metroL_1", "MR1", "v3", "metro_1", true, 1700));
        tracks.add(track("p1", "metroL_1", "MR1", "v3", "metro_9", false, 2500));
        // p2: 同制式 metro → metro → 0 事件
        tracks.add(track("p2", "metroL_1", "MR1", "v4", "metro_1", true, 100));
        tracks.add(track("p2", "metroL_1", "MR1", "v4", "metro_9", false, 900));
        tracks.add(track("p2", "metroL_1", "MR1", "v5", "metro_9", true, 1000));
        tracks.add(track("p2", "metroL_1", "MR1", "v5", "metro_1", false, 1900));
        MatsimTransferCache.TransferComputation computation =
                MatsimTransferCache.computeTransfers(tracks, resolver(modes), coords);
        assertEquals(0, computation.events.size());
        assertEquals(0, computation.droppedTracks);
    }

    @Test
    void windowAndDistanceBoundariesAreInclusive() {
        Map<String, double[]> coords = equatorCoords(
                "bus_A", -3000, "bus_B", 0, "metro_800", 800, "metro_801", 801, "metro_far", 9000);
        Map<String, String> names = new HashMap<>();
        // p1: 时间窗边界 1800s（含）+ 距离边界 800m（含）→ 事件成立
        List<PTPersonTrack> tracks = new ArrayList<>(List.of(
                track("p1", "busL_1", "R1", "v1", "bus_A", true, 100),
                track("p1", "busL_1", "R1", "v1", "bus_B", false, 1000),
                track("p1", "metroL_1", "MR1", "v2", "metro_800", true, 2800),
                track("p1", "metroL_1", "MR1", "v2", "metro_far", false, 3000),
                // p2: 超时窗 1801s → 无事件
                track("p2", "busL_1", "R1", "v3", "bus_A", true, 100),
                track("p2", "busL_1", "R1", "v3", "bus_B", false, 1000),
                track("p2", "metroL_1", "MR1", "v4", "metro_800", true, 2801),
                track("p2", "metroL_1", "MR1", "v4", "metro_far", false, 3000),
                // p3: 超距离 801m → 无事件
                track("p3", "busL_1", "R1", "v5", "bus_A", true, 100),
                track("p3", "busL_1", "R1", "v5", "bus_B", false, 1000),
                track("p3", "metroL_1", "MR1", "v6", "metro_801", true, 1200),
                track("p3", "metroL_1", "MR1", "v6", "metro_far", false, 3000)
        ));
        MatsimTransferCache.TransferComputation computation =
                MatsimTransferCache.computeTransfers(tracks, resolver(FIXTURE_MODES), coords);
        assertEquals(1, computation.events.size());
        assertEquals(1800, computation.events.get(0).transferSec());

        // 1800s 边界值计入直方图最后一桶（桶 29），30 桶无溢出桶
        MatsimTransferCache.HubClusters hubs =
                MatsimTransferCache.clusterHubs(List.of("metro_800", "metro_801", "metro_far"), coords, names);
        MatsimTransferCache.Artifacts artifacts = MatsimTransferCache.assemble(
                computation, hubs, Map.of(), Map.of(), names, coords, 1.0);
        int[] histogram = (int[]) artifacts.summary.get("histogramMin");
        assertEquals(30, histogram.length);
        assertEquals(1, histogram[29]);
        assertEquals(1, java.util.Arrays.stream(histogram).sum());
    }

    @Test
    void unpairedTracksAreDroppedAndCounted() {
        Map<String, double[]> coords = equatorCoords("f1", 0, "f2", 100, "f3", 200);
        List<PTPersonTrack> tracks = new ArrayList<>(List.of(
                // p1: 连续两条上车（首条缺下车）→ 丢 1，后一条正常成段
                track("p1", "busL_1", "R1", "v1", "f1", true, 100),
                track("p1", "busL_1", "R1", "v1", "f2", true, 200),
                track("p1", "busL_1", "R1", "v1", "f3", false, 300),
                // p2: 孤儿下车 → 丢 1
                track("p2", "busL_1", "R1", "v2", "f1", false, 100),
                // p3: 收尾未闭合上车 → 丢 1
                track("p3", "busL_1", "R1", "v3", "f1", true, 100),
                // p4: 上下车车辆不一致 → 丢 2
                track("p4", "busL_1", "R1", "v4", "f1", true, 100),
                track("p4", "busL_1", "R1", "v9", "f2", false, 200),
                // p5: enter 标记缺失 → 丢 1
                track("p5", "busL_1", "R1", "v5", "f1", null, 100)
        ));
        MatsimTransferCache.TransferComputation computation =
                MatsimTransferCache.computeTransfers(tracks, resolver(FIXTURE_MODES), coords);
        assertEquals(0, computation.events.size());
        assertEquals(6, computation.droppedTracks);
    }

    // ---------------------------------------------------------------- bin 布局与字典

    @Test
    void binLayoutIsColumnarLittleEndianAndRoundTrips() {
        MatsimTransferCache.Artifacts artifacts = fixtureArtifacts();
        // 字节数 = 头 10 + 23 × 事件数
        assertEquals(10 + 23 * 4, artifacts.eventsBin.length);
        Decoded decoded = decode(artifacts.eventsBin);
        assertEquals(1, decoded.version());
        assertEquals(4, decoded.count());
        // 事件按 tBoard 升序写入
        assertArrayEquals(new long[]{2600, 3450, 6800, 90000}, decoded.tBoard());
        // personIndex 按首次出现顺序 0 起自增：p1→0，p3→1，p2→2
        assertArrayEquals(new int[]{0, 0, 1, 2}, decoded.person());
        assertArrayEquals(new int[]{600, 450, 300, 200}, decoded.sec());
        assertArrayEquals(new int[]{0, 1, 0, 1}, decoded.dir());
        // busLines 按 lineId 字典序：busL_1→0，busL_2→1
        assertArrayEquals(new int[]{0, 1, 0, 0}, decoded.busLine());
        // busRoute 为线内局部索引：busL_1 的 routes=[R0,R1]→R1=1、R0=0；busL_2 的 R2=0
        assertArrayEquals(new int[]{1, 0, 0, 1}, decoded.busRoute());
        // busStops=[bus_B,bus_C]；metroStops=[metro_1,metro_2,metro_3]（含未被事件引用的枢纽成员 metro_3）
        assertArrayEquals(new int[]{0, 1, 0, 0}, decoded.busStop());
        assertArrayEquals(new int[]{0, 0, 0, 0}, decoded.metroLine());
        assertArrayEquals(new int[]{0, 1, 0, 0}, decoded.metroStop());
        assertArrayEquals(new int[]{0, 1, 0, 0}, decoded.hub());
    }

    @Test
    @SuppressWarnings("unchecked")
    void dictContainsOnlyReferencedObjectsWithStableIdsAndLineFlows() {
        MatsimTransferCache.Artifacts artifacts = fixtureArtifacts();
        Map<String, Object> dict = artifacts.dict;
        assertEquals("transfer-v1", dict.get("version"));
        assertEquals(0.1, (Double) dict.get("scale"), 1e-9);
        Map<String, Object> params = (Map<String, Object>) dict.get("params");
        assertEquals(1800, params.get("windowSec"));
        assertEquals(800, params.get("maxDistM"));
        assertEquals(Boolean.FALSE, params.get("tramAsRail"));

        List<Map<String, Object>> busLines = (List<Map<String, Object>>) dict.get("busLines");
        assertEquals(2, busLines.size());
        Map<String, Object> busLine1 = busLines.get(0);
        assertEquals("busL_1", busLine1.get("lineId"));
        assertEquals("1路", busLine1.get("name"));
        // 契约补充：全线全日上/下车人次（抽样口径不扩样，仅 bus 制式）
        assertEquals(3L, ((Number) busLine1.get("boardings")).longValue());
        assertEquals(3L, ((Number) busLine1.get("alightings")).longValue());
        List<Map<String, Object>> routes1 = (List<Map<String, Object>>) busLine1.get("routes");
        assertEquals(List.of("R0", "R1"), routes1.stream().map(r -> r.get("routeId")).toList());
        assertEquals(List.of("上行", "下行"), routes1.stream().map(r -> r.get("name")).toList());
        Map<String, Object> busLine2 = busLines.get(1);
        assertEquals(1L, ((Number) busLine2.get("boardings")).longValue());
        assertEquals(1L, ((Number) busLine2.get("alightings")).longValue());
        // route 显示名缺 description 时退回 routeId
        List<Map<String, Object>> routes2 = (List<Map<String, Object>>) busLine2.get("routes");
        assertEquals("R2", routes2.get(0).get("name"));

        List<Map<String, Object>> metroLines = (List<Map<String, Object>>) dict.get("metroLines");
        assertEquals(1, metroLines.size());
        assertEquals("metroL_1", metroLines.get(0).get("lineId"));
        assertEquals("1号线", metroLines.get(0).get("name"));

        // busStops 只含事件引用站（bus_A/bus_D 不下发）
        List<Map<String, Object>> busStops = (List<Map<String, Object>>) dict.get("busStops");
        assertEquals(List.of("bus_B", "bus_C"), busStops.stream().map(s -> s.get("facilityId")).toList());

        // metroStops = 事件引用站 + 被引用枢纽全部成员（metro_3 未被事件引用但随枢纽成员并入）
        List<Map<String, Object>> metroStops = (List<Map<String, Object>>) dict.get("metroStops");
        assertEquals(List.of("metro_1", "metro_2", "metro_3"),
                metroStops.stream().map(s -> s.get("facilityId")).toList());
        assertEquals(0, metroStops.get(2).get("hub")); // metro_3 归属 zhana|metro_1

        List<Map<String, Object>> hubs = (List<Map<String, Object>>) dict.get("hubs");
        assertEquals(2, hubs.size());
        Map<String, Object> hub0 = hubs.get(0);
        // hubKey = 清洗站名（去空白+小写）+ "|" + 字典序最小成员 facilityId
        assertEquals("zhana|metro_1", hub0.get("hubKey"));
        assertEquals("Zhan A", hub0.get("name"));
        assertEquals(600.0, ((Number) hub0.get("x")).doubleValue(), 1e-6); // 成员坐标均值
        assertEquals(List.of(0, 2), hub0.get("members")); // metroStops 索引
        assertEquals(List.of(0), hub0.get("metroLines"));
        assertEquals("zhanb|metro_2", hubs.get(1).get("hubKey"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void summaryTotalsHourlyClampAndTopsAreExact() {
        MatsimTransferCache.Artifacts artifacts = fixtureArtifacts();
        Map<String, Object> summary = artifacts.summary;
        assertEquals("transfer-v1", summary.get("version"));
        assertEquals(0L, ((Number) summary.get("droppedTracks")).longValue());
        assertEquals(0.1, (Double) summary.get("scale"), 1e-9);

        Map<String, Object> totals = (Map<String, Object>) summary.get("totals");
        assertEquals(4, totals.get("events"));
        assertEquals(3, totals.get("persons")); // p1/p2/p3 去重
        assertEquals(2L, ((Number) totals.get("busToMetro")).longValue());
        assertEquals(2L, ((Number) totals.get("metroToBus")).longValue());
        assertEquals(388, totals.get("avgSec")); // round((600+450+300+200)/4)
        assertEquals(300, totals.get("p50Sec")); // 最近秩：ceil(0.5×4)=2 → 排序第2个
        assertEquals(600, totals.get("p90Sec")); // ceil(0.9×4)=4 → 第4个

        Map<String, Object> hourly = (Map<String, Object>) summary.get("hourly");
        int[] busToMetro = (int[]) hourly.get("busToMetro");
        int[] metroToBus = (int[]) hourly.get("metroToBus");
        assertEquals(24, busToMetro.length);
        assertEquals(1, busToMetro[0]);  // tBoard=2600
        assertEquals(1, busToMetro[1]);  // tBoard=6800
        assertEquals(1, metroToBus[0]);  // tBoard=3450
        assertEquals(1, metroToBus[23]); // tBoard=90000（25:00）夹逼进 23 时桶
        assertEquals(2, java.util.Arrays.stream(busToMetro).sum());
        assertEquals(2, java.util.Arrays.stream(metroToBus).sum());

        int[] histogram = (int[]) summary.get("histogramMin");
        assertEquals(30, histogram.length);
        assertEquals(1, histogram[3]);  // 200s
        assertEquals(1, histogram[5]);  // 300s
        assertEquals(1, histogram[7]);  // 450s
        assertEquals(1, histogram[10]); // 600s
        assertEquals(4, java.util.Arrays.stream(histogram).sum());

        List<Map<String, Object>> topHubs = (List<Map<String, Object>>) summary.get("topHubs");
        assertEquals(2, topHubs.size());
        assertEquals(0, topHubs.get(0).get("hub")); // zhana|metro_1：3 次
        assertEquals(3L, ((Number) topHubs.get(0).get("flow")).longValue());
        assertEquals(367, topHubs.get(0).get("avgSec")); // round((600+300+200)/3)
        assertEquals(600, topHubs.get(0).get("p90Sec"));
        assertEquals(1L, ((Number) topHubs.get(1).get("flow")).longValue());

        List<Map<String, Object>> topPairs = (List<Map<String, Object>>) summary.get("topPairs");
        assertEquals(2, topPairs.size());
        assertEquals(0, topPairs.get(0).get("busLine"));
        assertEquals(0, topPairs.get(0).get("metroLine"));
        assertEquals(3L, ((Number) topPairs.get(0).get("flow")).longValue());
        assertEquals(367, topPairs.get(0).get("avgSec"));
    }

    @Test
    void emptyModelProducesAllZeroArtifactsWithoutError() {
        MatsimTransferCache.HubClusters hubs =
                MatsimTransferCache.clusterHubs(List.of(), Map.of(), Map.of());
        MatsimTransferCache.TransferComputation computation =
                MatsimTransferCache.computeTransfers(List.of(), resolver(Map.of()), Map.of());
        MatsimTransferCache.Artifacts artifacts =
                MatsimTransferCache.assemble(computation, hubs, Map.of(), Map.of(), Map.of(), Map.of(), 1.0);
        assertEquals(10, artifacts.eventsBin.length); // 仅头部
        Decoded decoded = decode(artifacts.eventsBin);
        assertEquals(0, decoded.count());
        Map<String, Object> totals = (Map<String, Object>) artifacts.summary.get("totals");
        assertEquals(0, totals.get("events"));
        assertEquals(0, totals.get("avgSec"));
        assertEquals(List.of(), artifacts.dict.get("hubs"));
        assertEquals(List.of(), artifacts.dict.get("busLines"));
    }

    // ---------------------------------------------------------------- 距离修正与聚类

    @Test
    void groundDistanceMatchesHaversineWithinOnePercentAtGuangzhouLatitude() {
        // 东西向约 1km（广州纬度 23.1°N）
        double[] a = mercator(113.30, 23.10);
        double[] b = mercator(113.31, 23.10);
        double corrected = MatsimTransferCache.groundDistanceMeters(a[0], a[1], b[0], b[1]);
        double truth = haversine(113.30, 23.10, 113.31, 23.10);
        assertTrue(Math.abs(corrected - truth) / truth < 0.01,
                "修正后误差应 <1%，实际 " + Math.abs(corrected - truth) / truth);
        // 未修正的平面欧氏在该纬度高估约 8.6%——证明修正确实生效
        double planar = Math.hypot(a[0] - b[0], a[1] - b[1]);
        assertTrue((planar - truth) / truth > 0.05);

        // 南北向约 1.1km
        double[] c = mercator(113.30, 23.11);
        double correctedNs = MatsimTransferCache.groundDistanceMeters(a[0], a[1], c[0], c[1]);
        double truthNs = haversine(113.30, 23.10, 113.30, 23.11);
        assertTrue(Math.abs(correctedNs - truthNs) / truthNs < 0.01);
    }

    @Test
    void hubClusteringMergesByCentroidAndBlocksChainPropagation() {
        // 同名三站间距 400m：f1+f2 合并（f2 距质心 400），f3 距新质心(200) 600m > 500 被拦——
        // 若按“到任一成员距离”会链式并成一个簇
        Map<String, double[]> coords = new HashMap<>(equatorCoords("f1", 0, "f2", 400, "f3", 800));
        Map<String, String> names = new HashMap<>(Map.of("f1", "Zhan A", "f2", "Zhan A", "f3", "Zhan A"));
        // 异名同点不合并
        coords.put("g1", new double[]{0, 0});
        names.put("g1", "Zhan B");
        MatsimTransferCache.HubClusters clusters = MatsimTransferCache.clusterHubs(
                List.of("f3", "g1", "f1", "f2"), coords, names); // 乱序输入，内部按 facilityId 定序
        assertEquals("zhana|f1", clusters.hubKeyOf("f1"));
        assertEquals("zhana|f1", clusters.hubKeyOf("f2"));
        assertEquals("zhana|f3", clusters.hubKeyOf("f3"));
        assertEquals("zhanb|g1", clusters.hubKeyOf("g1"));
        MatsimTransferCache.Hub merged = clusters.hub("zhana|f1");
        assertEquals(List.of("f1", "f2"), merged.members);
        assertEquals(200.0, merged.x, 1e-9); // 质心 = 成员均值
        assertEquals("Zhan A", merged.name);
        assertNotEquals(clusters.hubKeyOf("f1"), clusters.hubKeyOf("g1"));
        // 聚类外 facility 懒建单站枢纽（键只依赖自身，与处理顺序无关）
        assertEquals("f9|f9", clusters.hubKeyOf("f9"));
    }

    @Test
    void modeClassificationFollowsTransportModeWithTramCarveOut() {
        assertEquals(MatsimTransferCache.MODE_SUBWAY, MatsimTransferCache.classifyTransportMode("subway"));
        assertEquals(MatsimTransferCache.MODE_SUBWAY, MatsimTransferCache.classifyTransportMode("Rail"));
        assertEquals(MatsimTransferCache.MODE_SUBWAY, MatsimTransferCache.classifyTransportMode("地铁"));
        assertEquals(MatsimTransferCache.MODE_TRAM, MatsimTransferCache.classifyTransportMode("tram"));
        assertEquals(MatsimTransferCache.MODE_TRAM, MatsimTransferCache.classifyTransportMode("有轨电车"));
        assertEquals(MatsimTransferCache.MODE_BUS, MatsimTransferCache.classifyTransportMode("bus"));
        assertEquals(MatsimTransferCache.MODE_BUS, MatsimTransferCache.classifyTransportMode(null));
        // tramAsRail=false：tram 保持独立制式，不归轨道
        assertEquals(MatsimTransferCache.MODE_TRAM,
                MatsimTransferCache.effectiveMode(MatsimTransferCache.MODE_TRAM));
    }

    @Test
    void percentileNearestRankHandlesEdges() {
        assertEquals(0, MatsimTransferCache.percentileNearestRank(new int[]{}, 0.9));
        assertEquals(7, MatsimTransferCache.percentileNearestRank(new int[]{7}, 0.5));
        int[] sorted = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        assertEquals(5, MatsimTransferCache.percentileNearestRank(sorted, 0.5));  // ceil(5)=5 → 第5个
        assertEquals(9, MatsimTransferCache.percentileNearestRank(sorted, 0.9));  // ceil(9)=9 → 第9个
    }
}
