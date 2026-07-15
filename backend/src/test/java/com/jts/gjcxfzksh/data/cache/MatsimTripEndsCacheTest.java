package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import com.jts.gjcxfzksh.data.id.PersonId;
import com.jts.gjcxfzksh.data.id.StopFacilityId;
import com.jts.gjcxfzksh.data.id.VehicleId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 起终点分布缓存纯单测（不依赖 Spring/数据盘）：enter/leave 配对与 dropped 口径、
 * 30min/800m 链接（含边界值）、缺坐标保守断链、端点栅格分箱、journeys/riders 计数、
 * OD 配对聚合与 PGOD 编码（人次降序 / Top-K 截断 / 自环 / 街道哨兵列）。
 * 街道归属沿用 MatsimPopulationCacheTest 已覆盖的 StreetIndex，此处传 null 走 unassigned 路径。
 */
class MatsimTripEndsCacheTest {

    /** 测试栅格边长：直接取 100（赤道 cos(lat)=1，格键即坐标 ÷100 取整）。 */
    private static final double CELL = 100.0;

    // ---------------------------------------------------------------- 构造工具

    private static PTPersonTrack track(String person, String vehicle, String facility, Boolean enter, double time) {
        PTPersonTrack track = new PTPersonTrack();
        track.setPersonId(person == null ? null : PersonId.create(person));
        track.setVehicleId(vehicle == null ? null : VehicleId.create(vehicle));
        track.setFacilityId(facility == null ? null : StopFacilityId.create(facility));
        track.setEnter(enter);
        track.setTime(time);
        return track;
    }

    /** 赤道附近坐标：cos(lat)=1，平面距离即地面距离，便于口算 800m 阈值。 */
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

    private static MatsimTripEndsCache.Aggregation aggregate(List<PTPersonTrack> tracks,
                                                             Map<String, double[]> coords) {
        MatsimTripEndsCache.Aggregation aggregation =
                new MatsimTripEndsCache.Aggregation(CELL, null, coords);
        MatsimTripEndsCache.aggregateJourneys(shuffled(tracks), coords, aggregation);
        return aggregation;
    }

    private static int cellCount(it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap cells, double x, double y) {
        return cells.get(MatsimPopulationCache.cellKey(x, y, CELL));
    }

    /** 栅格 OD 对计数：O 格（xO 所在）→ D 格（xD 所在）。 */
    private static int odPairCount(MatsimTripEndsCache.Aggregation agg, double xO, double xD) {
        var inner = agg.gridOd.get(MatsimPopulationCache.cellKey(xO, 0, CELL));
        return inner == null ? 0 : inner.get(MatsimPopulationCache.cellKey(xD, 0, CELL));
    }

    /** 按 PGOD 布局回读 od-grid.bin。 */
    private record OdDecoded(int version, int count, double cellSize,
                             int[] iO, int[] jO, int[] iD, int[] jD, int[] n, int[] oStreet, int[] dStreet) {
    }

    private static OdDecoded decodeOd(byte[] bin) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bin).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        buffer.get(magic);
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new byte[]{'P', 'G', 'O', 'D'}, magic, "magic 必须为 ASCII PGOD");
        int version = Short.toUnsignedInt(buffer.getShort());
        int count = buffer.getInt();
        double cellSize = buffer.getDouble();
        int[] iO = new int[count];
        int[] jO = new int[count];
        int[] iD = new int[count];
        int[] jD = new int[count];
        int[] n = new int[count];
        int[] oStreet = new int[count];
        int[] dStreet = new int[count];
        for (int k = 0; k < count; k++) {
            iO[k] = buffer.getInt();
            jO[k] = buffer.getInt();
            iD[k] = buffer.getInt();
            jD[k] = buffer.getInt();
            n[k] = buffer.getInt();
            oStreet[k] = Short.toUnsignedInt(buffer.getShort());
            dStreet[k] = Short.toUnsignedInt(buffer.getShort());
        }
        assertEquals(0, buffer.remaining(), "bin 长度应与 count 精确匹配");
        return new OdDecoded(version, count, cellSize, iO, jO, iD, jD, n, oStreet, dStreet);
    }

    // ---------------------------------------------------------------- 用例

    @Test
    void 单段乘车即一段整段出行_起点上车站_终点下车站() {
        Map<String, double[]> coords = equatorCoords("A", 0, "B", 5000);
        MatsimTripEndsCache.Aggregation agg = aggregate(List.of(
                track("p1", "v1", "A", true, 100),
                track("p1", "v1", "B", false, 700)
        ), coords);
        assertEquals(1, agg.journeys);
        assertEquals(1, agg.riders);
        assertEquals(1, agg.originPoints);
        assertEquals(1, agg.destPoints);
        assertEquals(0, agg.droppedTracks);
        assertEquals(1, cellCount(agg.originCells, 0, 0), "起点应落 A 站所在格");
        assertEquals(1, cellCount(agg.destCells, 5000, 0), "终点应落 B 站所在格");
    }

    @Test
    void 时间窗与距离双满足则链为同一出行_边界值含() {
        // B 下车 → C 上车：间隔恰 1800s、距离恰 800m，应链接；起点=A、终点=D
        Map<String, double[]> coords = equatorCoords("A", 0, "B", 5000, "C", 5800, "D", 20000);
        MatsimTripEndsCache.Aggregation agg = aggregate(List.of(
                track("p1", "v1", "A", true, 100),
                track("p1", "v1", "B", false, 700),
                track("p1", "v2", "C", true, 700 + 1800),
                track("p1", "v2", "D", false, 4000)
        ), coords);
        assertEquals(1, agg.journeys, "1800s/800m 边界值应视为同一出行");
        assertEquals(1, cellCount(agg.originCells, 0, 0));
        assertEquals(1, cellCount(agg.destCells, 20000, 0));
        assertEquals(0, cellCount(agg.originCells, 5800, 0), "换乘上车点不是整段出行起点");
    }

    @Test
    void 超时或超距则断为两段出行() {
        Map<String, double[]> coords = equatorCoords("A", 0, "B", 5000, "C", 5801, "D", 20000);
        // 超时：间隔 1801s
        MatsimTripEndsCache.Aggregation late = aggregate(List.of(
                track("p1", "v1", "A", true, 100),
                track("p1", "v1", "B", false, 700),
                track("p1", "v2", "B", true, 700 + 1801),
                track("p1", "v2", "D", false, 9000)
        ), coords);
        assertEquals(2, late.journeys, "超 1800s 应断链");
        assertEquals(1, late.riders);
        // 超距：B(5000)→C(5801) 相距 801m
        MatsimTripEndsCache.Aggregation far = aggregate(List.of(
                track("p1", "v1", "A", true, 100),
                track("p1", "v1", "B", false, 700),
                track("p1", "v2", "C", true, 900),
                track("p1", "v2", "D", false, 9000)
        ), coords);
        assertEquals(2, far.journeys, "超 800m 应断链");
        assertEquals(1, cellCount(far.originCells, 0, 0));
        assertEquals(1, cellCount(far.originCells, 5801, 0), "断链后第二段的上车点是新起点");
        assertEquals(1, cellCount(far.destCells, 5000, 0));
        assertEquals(1, cellCount(far.destCells, 20000, 0));
    }

    @Test
    void 缺坐标无法校验距离时保守断链_缺坐标端点跳过() {
        // X 无坐标：B→X 距离不可判 → 断链；X 作为第二段起点无坐标 → originPoints 少 1
        Map<String, double[]> coords = equatorCoords("A", 0, "B", 5000, "D", 20000);
        MatsimTripEndsCache.Aggregation agg = aggregate(List.of(
                track("p1", "v1", "A", true, 100),
                track("p1", "v1", "B", false, 700),
                track("p1", "v2", "X", true, 800),
                track("p1", "v2", "D", false, 9000)
        ), coords);
        assertEquals(2, agg.journeys);
        assertEquals(1, agg.originPoints, "无坐标起点跳过");
        assertEquals(2, agg.destPoints);
    }

    @Test
    void 配对异常计入droppedTracks且不产生出行() {
        Map<String, double[]> coords = equatorCoords("A", 0, "B", 5000);
        MatsimTripEndsCache.Aggregation agg = aggregate(List.of(
                track("p1", "v1", "A", false, 100),          // 孤儿下车 +1
                track("p2", "v1", "A", true, 100),
                track("p2", "v2", "B", false, 700),          // 车辆对不上 +2
                track("p3", "v1", "A", true, 100),           // 收尾未闭合 +1
                track(null, "v1", "A", true, 100),           // 无 person +1
                track("p4", "v1", "A", null, 100)            // enter 标记缺失 +1
        ), coords);
        assertEquals(0, agg.journeys);
        assertEquals(0, agg.riders, "无成段乘车不算乘客");
        assertEquals(6, agg.droppedTracks);
    }

    @Test
    void OD配对聚合_同格对累加_自环保留_缺坐标跳过() {
        // p1: A(0)→B(5000)；p2: A'(50)→B'(5050) 与 p1 同一格对 → count 2；
        // p3: C(30000)→C'(30050) 同格自环；p4: 起点 X 无坐标 → odSkipped（终点照常计入端点统计）
        Map<String, double[]> coords = equatorCoords("A", 0, "A2", 50, "B", 5000, "B2", 5050,
                "C", 30000, "C2", 30050, "D", 40000);
        MatsimTripEndsCache.Aggregation agg = aggregate(List.of(
                track("p1", "v1", "A", true, 100),
                track("p1", "v1", "B", false, 700),
                track("p2", "v2", "A2", true, 100),
                track("p2", "v2", "B2", false, 700),
                track("p3", "v3", "C", true, 100),
                track("p3", "v3", "C2", false, 700),
                track("p4", "v4", "X", true, 100),
                track("p4", "v4", "D", false, 700)
        ), coords);
        assertEquals(4, agg.journeys);
        assertEquals(3, agg.odJourneys, "两端坐标齐全的出行才计入 OD");
        assertEquals(1, agg.odSkipped);
        assertEquals(2, odPairCount(agg, 0, 5000), "同一格对应累加");
        assertEquals(1, odPairCount(agg, 30000, 30000), "同格自环保留");
        assertEquals(0, odPairCount(agg, 0, 40000), "缺坐标出行不产生 OD 对");
        // 本测试未挂街道索引：计入栅格 OD 的出行全部进 odStreetUnassigned
        assertEquals(3, agg.odStreetUnassigned);
    }

    @Test
    void PGOD编码_人次降序_街道哨兵_长度精确() {
        Map<String, double[]> coords = equatorCoords("A", 0, "B", 5000, "C", 30000, "C2", 30050);
        MatsimTripEndsCache.Aggregation agg = aggregate(List.of(
                track("p1", "v1", "A", true, 100),
                track("p1", "v1", "B", false, 700),
                track("p2", "v2", "A", true, 100),
                track("p2", "v2", "B", false, 700),
                track("p3", "v3", "C", true, 100),
                track("p3", "v3", "C2", false, 700)
        ), coords);
        MatsimTripEndsCache.OdGridEncoded encoded =
                MatsimTripEndsCache.encodeOdGrid(agg.gridOd, CELL, null, 100);
        assertEquals(2, encoded.writtenPairs());
        assertEquals(0, encoded.droppedPairs());
        assertEquals(0, encoded.droppedFlow());
        OdDecoded decoded = decodeOd(encoded.bin());
        assertEquals(1, decoded.version());
        assertEquals(CELL, decoded.cellSize());
        // 人次降序：A→B(2) 在前，C 自环(1) 在后
        assertEquals(2, decoded.n()[0]);
        assertEquals(0, decoded.iO()[0]);
        assertEquals(50, decoded.iD()[0]);
        assertEquals(1, decoded.n()[1]);
        assertEquals(300, decoded.iO()[1]);
        assertEquals(300, decoded.iD()[1]);
        // 无街道索引：o/d 街道列一律哨兵 0xFFFF
        assertEquals(0xFFFF, decoded.oStreet()[0]);
        assertEquals(0xFFFF, decoded.dStreet()[1]);
    }

    @Test
    void PGOD截断_保留Top并披露dropped() {
        // 三个格对：n=3、n=2、n=1；maxPairs=2 → 保留 3/2，dropped 1 对、流量 1
        Map<String, double[]> coords = equatorCoords("A", 0, "B", 5000, "C", 30000, "D", 40000);
        List<PTPersonTrack> tracks = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            tracks.add(track("a" + k, "v" + k, "A", true, 100));
            tracks.add(track("a" + k, "v" + k, "B", false, 700));
        }
        for (int k = 0; k < 2; k++) {
            tracks.add(track("b" + k, "w" + k, "B", true, 100));
            tracks.add(track("b" + k, "w" + k, "C", false, 700));
        }
        tracks.add(track("c0", "u0", "C", true, 100));
        tracks.add(track("c0", "u0", "D", false, 700));
        MatsimTripEndsCache.Aggregation agg = aggregate(tracks, coords);
        MatsimTripEndsCache.OdGridEncoded encoded =
                MatsimTripEndsCache.encodeOdGrid(agg.gridOd, CELL, null, 2);
        assertEquals(2, encoded.writtenPairs());
        assertEquals(1, encoded.droppedPairs());
        assertEquals(1, encoded.droppedFlow());
        OdDecoded decoded = decodeOd(encoded.bin());
        assertEquals(3, decoded.n()[0]);
        assertEquals(2, decoded.n()[1]);
    }

    @Test
    void 街道OD_JSON_无街道索引时pairs为空且totals对账() {
        Map<String, double[]> coords = equatorCoords("A", 0, "B", 5000);
        MatsimTripEndsCache.Aggregation agg = aggregate(List.of(
                track("p1", "v1", "A", true, 100),
                track("p1", "v1", "B", false, 700)
        ), coords);
        Map<String, Object> payload = MatsimTripEndsCache.buildOdStreets(agg, null);
        assertEquals(List.of(), payload.get("pairs"));
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) payload.get("totals");
        // 对账恒等式：sum(pairs.n) + odStreetUnassigned == odJourneys
        assertEquals(0L, totals.get("flow"));
        assertEquals(1L, totals.get("odJourneys"));
        assertEquals(1L, totals.get("odStreetUnassigned"));
    }

    @Test
    void 多人多次出行分别计数_街道索引缺省计入unassigned() {
        Map<String, double[]> coords = equatorCoords("A", 0, "B", 5000, "C", 30000, "D", 40000);
        MatsimTripEndsCache.Aggregation agg = aggregate(List.of(
                // p1 早高峰去程 + 晚高峰返程（间隔远超时间窗 → 两段出行）
                track("p1", "v1", "A", true, 7 * 3600),
                track("p1", "v1", "B", false, 7 * 3600 + 900),
                track("p1", "v9", "B", true, 18 * 3600),
                track("p1", "v9", "A", false, 18 * 3600 + 900),
                // p2 单段
                track("p2", "v2", "C", true, 9 * 3600),
                track("p2", "v2", "D", false, 9 * 3600 + 600)
        ), coords);
        assertEquals(3, agg.journeys);
        assertEquals(2, agg.riders);
        assertEquals(3, agg.originPoints);
        assertEquals(3, agg.destPoints);
        // 本测试未挂街道索引：全部端点计入 unassigned（与人口分布同语义）
        assertEquals(3, agg.unassignedOrigin);
        assertEquals(3, agg.unassignedDest);
        assertEquals(1, cellCount(agg.originCells, 0, 0));
        assertEquals(1, cellCount(agg.originCells, 5000, 0));
        assertEquals(1, cellCount(agg.originCells, 30000, 0));
    }
}
