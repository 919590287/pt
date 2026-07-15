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
                           int[] coeff, int[] name, int[] street) {
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
        for (int k = 0; k < count; k++) {
            x1[k] = buffer.getInt();
            y1[k] = buffer.getInt();
            x2[k] = buffer.getInt();
            y2[k] = buffer.getInt();
            coeff[k] = Short.toUnsignedInt(buffer.getShort());
            name[k] = Short.toUnsignedInt(buffer.getShort());
            street[k] = Short.toUnsignedInt(buffer.getShort());
        }
        assertEquals(0, buffer.remaining(), "bin 长度应与 count 精确匹配");
        return new Decoded(version, count, x1, y1, x2, y2, coeff, name, street);
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
        assertEquals(1, decoded.version());
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
