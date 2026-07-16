package com.jts.gjcxfzksh.data.cache;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 走廊缓存纯单测（不依赖 Spring/数据盘/MATSim scenario）：无向节点对合并（同线双向只计一次）、
 * 多线计数、路名首非空、PCRD 编码（系数升序/哨兵列/长度精确）、制式判定与 base 解析。
 * 街道归属沿用 MatsimPopulationCacheTest 已覆盖的 StreetIndex，此处传 null 走哨兵路径。
 */
class MatsimCorridorCacheTest {

    private static MatsimCorridorCache.TraversedLink t(String from, String to,
                                                       double fx, double fy, double tx, double ty, String name) {
        return new MatsimCorridorCache.TraversedLink(from, to, fx, fy, tx, ty, name);
    }

    /** 按 PCRD 布局回读 links.bin。 */
    private record Decoded(int version, int count, int[] x1, int[] y1, int[] x2, int[] y2,
                           int[] coeff, int[] name, int[] street, long[] flow) {
    }

    private static Decoded decode(byte[] bin) {
        ByteBuffer buffer = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        buffer.get(magic);
        assertArrayEquals(new byte[]{'P', 'C', 'R', 'D'}, magic, "magic 必须为 ASCII PCRD");
        int version = Short.toUnsignedInt(buffer.getShort());
        int count = buffer.getInt();
        int[] x1 = new int[count];
        int[] y1 = new int[count];
        int[] x2 = new int[count];
        int[] y2 = new int[count];
        int[] coeff = new int[count];
        int[] name = new int[count];
        int[] street = new int[count];
        long[] flow = new long[count];
        for (int k = 0; k < count; k++) {
            x1[k] = buffer.getInt();
            y1[k] = buffer.getInt();
            x2[k] = buffer.getInt();
            y2[k] = buffer.getInt();
            coeff[k] = Short.toUnsignedInt(buffer.getShort());
            name[k] = Short.toUnsignedInt(buffer.getShort());
            street[k] = Short.toUnsignedInt(buffer.getShort());
            flow[k] = Integer.toUnsignedLong(buffer.getInt());
        }
        assertEquals(0, buffer.remaining(), "bin 长度应与 count 精确匹配");
        return new Decoded(version, count, x1, y1, x2, y2, coeff, name, street, flow);
    }

    /** 合成 track（断面客流乘车段用）。 */
    private static com.jts.gjcxfzksh.data.entry.PTPersonTrack track(
            String person, String line, String route, String vehicle, String facility, Boolean enter, double time) {
        com.jts.gjcxfzksh.data.entry.PTPersonTrack track = new com.jts.gjcxfzksh.data.entry.PTPersonTrack();
        track.setPersonId(person == null ? null : com.jts.gjcxfzksh.data.id.PersonId.create(person));
        track.setLineId(line == null ? null : com.jts.gjcxfzksh.data.id.LineId.create(line));
        track.setRouteId(route == null ? null : com.jts.gjcxfzksh.data.id.RouteId.create(route));
        track.setVehicleId(vehicle == null ? null : com.jts.gjcxfzksh.data.id.VehicleId.create(vehicle));
        track.setFacilityId(facility == null ? null : com.jts.gjcxfzksh.data.id.StopFacilityId.create(facility));
        track.setEnter(enter);
        track.setTime(time);
        return track;
    }

    @Test
    void 同线双向走同一路段只计一次_两线同段计二() {
        // 线 L1 上行 A→B、下行 B→A（双向路网两条对向 link）→ 同一无向段，系数 1；
        // 线 L2 也经过 A-B → 系数 2；L2 另有 B-C 段系数 1
        Map<String, List<MatsimCorridorCache.TraversedLink>> byLine = Map.of(
                "L1", List.of(
                        t("A", "B", 0, 0, 100, 0, null),
                        t("B", "A", 100, 0, 0, 0, null)),
                "L2", List.of(
                        t("A", "B", 0, 0, 100, 0, null),
                        t("B", "C", 100, 0, 200, 0, null)));
        MatsimCorridorCache.Computation computation = MatsimCorridorCache.aggregateTraversals(byLine);
        assertEquals(2, computation.segments.size());
        MatsimCorridorCache.SegmentAgg ab = computation.segments.get("A|B");
        assertEquals(2, ab.coefficient(), "A-B 被 L1/L2 两条线经过");
        assertTrue(ab.lines.contains("L1") && ab.lines.contains("L2"));
        assertEquals(1, computation.segments.get("B|C").coefficient());
        // 无向几何规范化：以节点序小端为 (x1,y1)
        assertEquals(0.0, ab.x1);
        assertEquals(100.0, ab.x2);
    }

    @Test
    void 同线同路段重复经过不叠加_路名取首个非空() {
        Map<String, List<MatsimCorridorCache.TraversedLink>> byLine = Map.of(
                "L1", List.of(
                        t("A", "B", 0, 0, 100, 0, null),
                        t("A", "B", 0, 0, 100, 0, "进港大道"),
                        t("A", "B", 0, 0, 100, 0, "别名路")));
        MatsimCorridorCache.Computation computation = MatsimCorridorCache.aggregateTraversals(byLine);
        MatsimCorridorCache.SegmentAgg ab = computation.segments.get("A|B");
        assertEquals(1, ab.coefficient(), "同线重复经过不改变系数");
        assertEquals("进港大道", ab.name, "路名取首个非空");
    }

    @Test
    void PCRD编码_系数升序_哨兵列_取整() {
        Map<String, List<MatsimCorridorCache.TraversedLink>> byLine = Map.of(
                "L1", List.of(t("A", "B", 0.4, 0, 100.6, 0, "进港大道"), t("B", "C", 100.6, 0, 200, 0, null)),
                "L2", List.of(t("A", "B", 0.4, 0, 100.6, 0, "进港大道")));
        MatsimCorridorCache.Computation computation = MatsimCorridorCache.aggregateTraversals(byLine);
        MatsimCorridorCache.Artifacts artifacts = MatsimCorridorCache.assemble(computation, null);
        Decoded decoded = decode(artifacts.linksBin);
        assertEquals(2, decoded.version());
        assertEquals(2, decoded.count());
        // 系数升序：B-C(1) 在前，A-B(2) 在后
        assertEquals(1, decoded.coeff()[0]);
        assertEquals(2, decoded.coeff()[1]);
        assertEquals(0xFFFF, decoded.name()[0], "无名段名称列写哨兵");
        assertEquals(0, decoded.name()[1], "首个（唯一）路名字典索引为 0");
        assertEquals(0xFFFF, decoded.street()[0], "无街道索引时街道列写哨兵");
        // 坐标四舍五入取整
        assertEquals(0, decoded.x1()[1]);
        assertEquals(101, decoded.x2()[1]);
        // summary 与 names 工件口径
        assertEquals(2, artifacts.summary.get("segments"));
        assertEquals(1L, artifacts.summary.get("namedSegments"));
        assertEquals(2, artifacts.summary.get("maxCoeff"));
        assertEquals(2, artifacts.summary.get("busLines"));
        assertEquals(List.of("进港大道"), artifacts.names.get("names"));
    }

    @Test
    void 断面客流_站序前缀和分摊_双向叠加_区间归属() {
        // 路线 R1（L1）：link 序列 [L0,L1,L2]，停站 A@L0、B@L1、C@L2；
        // 物理段键与 link 一一对应（s0/s1/s2 用节点对表示）
        Map<String, List<MatsimCorridorCache.TraversedLink>> byLine = Map.of(
                "L1", List.of(
                        t("n0", "n1", 0, 0, 100, 0, null),     // L0 → 段 n0|n1
                        t("n1", "n2", 100, 0, 200, 0, null),   // L1 → 段 n1|n2
                        t("n2", "n3", 200, 0, 300, 0, null))); // L2 → 段 n2|n3
        MatsimCorridorCache.Computation computation = MatsimCorridorCache.aggregateTraversals(byLine);

        MatsimCorridorCache.RouteCtxRegistry contexts = new MatsimCorridorCache.RouteCtxRegistry();
        MatsimCorridorCache.RouteFlowCtx ctx = new MatsimCorridorCache.RouteFlowCtx(
                new String[]{"n0|n1", "n1|n2", "n2|n3"},
                new int[]{0, 1, 2},
                Map.of("A", new int[]{0}, "B", new int[]{1}, "C", new int[]{2}));
        contexts.register("L1", "R1", ctx);

        MatsimCorridorCache.accumulateSegmentFlows(List.of(
                // p1: A→C（跨两个站间）；p2: A→B；p3: B→C
                track("p1", "L1", "R1", "v1", "A", true, 100),
                track("p1", "L1", "R1", "v1", "C", false, 700),
                track("p2", "L1", "R1", "v2", "A", true, 200),
                track("p2", "L1", "R1", "v2", "B", false, 500),
                track("p3", "L1", "R1", "v3", "B", true, 300),
                track("p3", "L1", "R1", "v3", "C", false, 900),
                // p4: 下车站不在站序中 → dropped
                track("p4", "L1", "R1", "v4", "A", true, 100),
                track("p4", "L1", "R1", "v4", "X", false, 700),
                // p5: 非登记路线（如地铁）→ 静默不计
                track("p5", "M1", "MR1", "v5", "A", true, 100),
                track("p5", "M1", "MR1", "v5", "C", false, 700)
        ), contexts, computation);

        assertEquals(3, computation.flowRides);
        assertEquals(1, computation.flowDroppedRides);
        // 站间 A→B 载客 = p1+p2 = 2 → 分摊到 (L0, L1] 即段 n1|n2；
        // 站间 B→C 载客 = p1+p3 = 2 → 段 n2|n3；上车站所在 link 段 n0|n1 不计
        assertEquals(0, computation.segments.get("n0|n1").flow);
        assertEquals(2, computation.segments.get("n1|n2").flow);
        assertEquals(2, computation.segments.get("n2|n3").flow);

        MatsimCorridorCache.Artifacts artifacts = MatsimCorridorCache.assemble(computation, null);
        Decoded decoded = decode(artifacts.linksBin);
        long maxFlow = 0;
        for (long f : decoded.flow()) maxFlow = Math.max(maxFlow, f);
        assertEquals(2, maxFlow);
        assertEquals(2L, ((Number) artifacts.summary.get("maxFlow")).longValue());
        assertEquals(3L, artifacts.summary.get("flowRides"));
        assertEquals(1L, artifacts.summary.get("flowDroppedRides"));
    }

    @Test
    void 断面客流_环线重复停站取上车位之后最近下车位() {
        // 环线：停站 A@L0、B@L1、A@L2（A 出现两次）；乘车 B→A 应落在第二个 A（位 2），
        // 站间 B→A2 载客 1 → 段 n2|n0r
        Map<String, List<MatsimCorridorCache.TraversedLink>> byLine = Map.of(
                "L1", List.of(
                        t("n0", "n1", 0, 0, 100, 0, null),
                        t("n1", "n2", 100, 0, 200, 0, null),
                        t("n2", "n0r", 200, 0, 300, 0, null)));
        MatsimCorridorCache.Computation computation = MatsimCorridorCache.aggregateTraversals(byLine);
        MatsimCorridorCache.RouteCtxRegistry contexts = new MatsimCorridorCache.RouteCtxRegistry();
        contexts.register("L1", "R1", new MatsimCorridorCache.RouteFlowCtx(
                new String[]{"n0|n1", "n1|n2", "n0r|n2"},
                new int[]{0, 1, 2},
                Map.of("A", new int[]{0, 2}, "B", new int[]{1})));
        MatsimCorridorCache.accumulateSegmentFlows(List.of(
                track("p1", "L1", "R1", "v1", "B", true, 100),
                track("p1", "L1", "R1", "v1", "A", false, 700)
        ), contexts, computation);
        assertEquals(1, computation.flowRides);
        assertEquals(0, computation.segments.get("n0|n1").flow);
        assertEquals(0, computation.segments.get("n1|n2").flow);
        assertEquals(1, computation.segments.get("n0r|n2").flow);
    }

    @Test
    void 制式判定_bus之外不计入() {
        assertTrue(MatsimCorridorCache.isBusTransportMode("bus"));
        assertTrue(MatsimCorridorCache.isBusTransportMode(null), "transportMode 缺失按 bus（复刻 transfer 口径）");
        assertFalse(MatsimCorridorCache.isBusTransportMode("subway"));
        assertFalse(MatsimCorridorCache.isBusTransportMode("tram"));
        assertFalse(MatsimCorridorCache.isBusTransportMode("rail"));
    }

    @Test
    void linkBase解析_road前缀取数字段() {
        assertEquals(101330L, MatsimCorridorCache.linkBaseId("road_101330_0_e2s"));
        assertEquals(46L, MatsimCorridorCache.linkBaseId("road_46_12_s2e"));
        assertNull(MatsimCorridorCache.linkBaseId("siwei_12_0_0_s2e"), "非 road_ 前缀不解析");
        assertNull(MatsimCorridorCache.linkBaseId("road_abc_0_e2s"));
        assertNull(MatsimCorridorCache.linkBaseId(null));
    }
}
