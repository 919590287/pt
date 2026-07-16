package com.jts.gjcxfzksh.data.cache;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 链路车速缓存纯单测（不依赖 Spring/数据盘/MATSim scenario）：
 * 净行驶速度（扣站点停靠）、freespeed 封顶、非公交车辆过滤、首链/中断穿越不计、
 * 跨日折桶、±1 桶平滑合并、PLSP 编码（哨兵列/矩阵布局/长度精确）。
 */
class MatsimLinkSpeedCacheTest {

    /** 100m、freespeed 10m/s（36km/h）的东西向测试链路。 */
    private static final MatsimLinkSpeedCache.LinkMeta LINK_A =
            new MatsimLinkSpeedCache.LinkMeta(0, 0, 100, 0, 100, 10, "测试路");
    private static final MatsimLinkSpeedCache.LinkMeta LINK_B =
            new MatsimLinkSpeedCache.LinkMeta(100, 0, 300, 0, 200, 10, null);

    private static MatsimLinkSpeedCache.SpeedAggregator aggregator() {
        return new MatsimLinkSpeedCache.SpeedAggregator(linkId -> switch (linkId) {
            case "A" -> LINK_A;
            case "B" -> LINK_B;
            default -> null;
        });
    }

    /** bucket 0 的时刻基准（避免 ±1 平滑窗吃到相邻桶时算错预期）。 */
    private static double bucketStart(int bucket) {
        return (double) bucket * MatsimLinkSpeedCache.BUCKET_SECONDS;
    }

    @Test
    void netTravelSpeedExcludesDwellAndUsesSpaceMean() {
        MatsimLinkSpeedCache.SpeedAggregator agg = aggregator();
        agg.registerBusVehicle("bus1");
        double t0 = bucketStart(40); // 10:00
        // 穿越 1：100m 走 20s（净 20s，含 10s 停站被扣除）→ 5m/s
        agg.linkEnter("bus1", "A", t0);
        agg.arrivesAtFacility("bus1", t0 + 5);
        agg.departsAtFacility("bus1", t0 + 15);
        agg.linkLeave("bus1", "A", t0 + 30);
        // 穿越 2：100m 走 30s → 3.33m/s；空间平均 = 200m/50s = 4m/s = 14.4km/h → round 14
        agg.linkEnter("bus1", "A", t0 + 60);
        agg.linkLeave("bus1", "A", t0 + 90);
        assertEquals(2, agg.traversals);
        MatsimLinkSpeedCache.LinkAcc acc = agg.byLink.get("A");
        assertEquals(14, MatsimLinkSpeedCache.smoothedSpeedKmh(acc, 40));
        assertEquals(2, MatsimLinkSpeedCache.smoothedSamples(acc, 40));
        // ±1 平滑：相邻桶 39/41 也能看到同一批样本
        assertEquals(14, MatsimLinkSpeedCache.smoothedSpeedKmh(acc, 41));
        assertEquals(0, MatsimLinkSpeedCache.smoothedSpeedKmh(acc, 43), "窗外桶无样本应为 0");
    }

    @Test
    void speedIsCappedAtFreespeedForShortTraversalTimes() {
        MatsimLinkSpeedCache.SpeedAggregator agg = aggregator();
        agg.registerBusVehicle("bus1");
        double t0 = bucketStart(40);
        // 100m 走 1s = 100m/s：QSim 秒级取整噪声，应封顶 freespeed 10m/s → 36km/h
        agg.linkEnter("bus1", "A", t0);
        agg.linkLeave("bus1", "A", t0 + 1);
        assertEquals(36, MatsimLinkSpeedCache.smoothedSpeedKmh(agg.byLink.get("A"), 40));
    }

    @Test
    void nonBusVehiclesAndIncompleteTraversalsAreIgnored() {
        MatsimLinkSpeedCache.SpeedAggregator agg = aggregator();
        agg.registerBusVehicle("bus1");
        double t0 = bucketStart(40);
        // 社会车辆：未登记，不进状态机
        agg.linkEnter("car1", "A", t0);
        agg.linkLeave("car1", "A", t0 + 10);
        // 首链：只有 linkLeave（vehicleEntersTraffic 进入），无状态不计
        agg.linkLeave("bus1", "A", t0 + 20);
        // 运营段中断：进行中穿越作废
        agg.linkEnter("bus1", "B", t0 + 30);
        agg.leavesTraffic("bus1");
        agg.linkLeave("bus1", "B", t0 + 60);
        assertEquals(0, agg.traversals);
        assertTrue(agg.byLink.isEmpty());
        // 链路对不上（事件序异常）：作废并计 dropped
        agg.linkEnter("bus1", "A", t0 + 100);
        agg.linkLeave("bus1", "B", t0 + 120);
        assertEquals(1, agg.droppedTraversals);
    }

    @Test
    void overDayTimesFoldIntoDayBuckets() {
        assertEquals(0, MatsimLinkSpeedCache.bucketOf(0));
        assertEquals(40, MatsimLinkSpeedCache.bucketOf(bucketStart(40) + 1));
        // 24:30 → 0:30 所在桶（跨日折回，与 hourOf 口径一致）
        assertEquals(2, MatsimLinkSpeedCache.bucketOf(24.5 * 3600));
        assertEquals(-1, MatsimLinkSpeedCache.bucketOf(-1));
        assertEquals(95, MatsimLinkSpeedCache.bucketOf(86400 - 1));
    }

    @Test
    void encodeMatrixLayoutSentinelsAndRoundTrip() {
        MatsimLinkSpeedCache.SpeedAggregator agg = aggregator();
        agg.registerBusVehicle("bus1");
        double t0 = bucketStart(0); // 桶 0：平滑窗只有 0/1 两桶
        agg.linkEnter("bus1", "A", t0);
        agg.linkLeave("bus1", "A", t0 + 10); // 10m/s = 36km/h
        agg.linkEnter("bus1", "B", t0 + 10);
        agg.linkLeave("bus1", "B", t0 + 50); // 200m/40s = 5m/s = 18km/h

        List<Map.Entry<String, MatsimLinkSpeedCache.LinkAcc>> ordered =
                new ArrayList<>(agg.byLink.entrySet());
        ordered.sort(Map.Entry.comparingByKey()); // A(测试路), B(无名)
        Map<String, Integer> nameIdx = Map.of("测试路", 0);
        byte[] bin = MatsimLinkSpeedCache.encodeMatrix(ordered, nameIdx, null);

        int buckets = MatsimLinkSpeedCache.BUCKET_COUNT;
        assertEquals(MatsimLinkSpeedCache.BIN_HEADER_BYTES
                + 2 * MatsimLinkSpeedCache.BIN_BYTES_PER_LINK + 2 * 2 * buckets, bin.length);
        ByteBuffer buffer = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        buffer.get(magic);
        assertArrayEquals(new byte[]{'P', 'L', 'S', 'P'}, magic);
        assertEquals(MatsimLinkSpeedCache.BIN_VERSION, Short.toUnsignedInt(buffer.getShort()));
        assertEquals(2, buffer.getInt());
        assertEquals(buckets, Short.toUnsignedInt(buffer.getShort()));
        assertEquals(MatsimLinkSpeedCache.BUCKET_SECONDS, Short.toUnsignedInt(buffer.getShort()));
        // link A 记录：几何 + nameIdx=0 + street 哨兵（streets 传 null）
        assertEquals(0, buffer.getInt());
        assertEquals(0, buffer.getInt());
        assertEquals(100, buffer.getInt());
        assertEquals(0, buffer.getInt());
        assertEquals(0, Short.toUnsignedInt(buffer.getShort()));
        assertEquals(MatsimLinkSpeedCache.U16_SENTINEL, Short.toUnsignedInt(buffer.getShort()));
        // link B 记录：无名哨兵
        assertEquals(100, buffer.getInt());
        assertEquals(0, buffer.getInt());
        assertEquals(300, buffer.getInt());
        assertEquals(0, buffer.getInt());
        assertEquals(MatsimLinkSpeedCache.U16_SENTINEL, Short.toUnsignedInt(buffer.getShort()));
        assertEquals(MatsimLinkSpeedCache.U16_SENTINEL, Short.toUnsignedInt(buffer.getShort()));
        // 速度矩阵（链路主序）：A 桶0/1 = 36（±1 平滑），其余 0
        byte[] speedsA = new byte[buckets];
        buffer.get(speedsA);
        assertEquals(36, speedsA[0]);
        assertEquals(36, speedsA[1], "±1 平滑应让相邻桶可见");
        assertEquals(0, speedsA[2]);
        byte[] speedsB = new byte[buckets];
        buffer.get(speedsB);
        assertEquals(18, speedsB[0]);
        // 样本矩阵
        byte[] samplesA = new byte[buckets];
        buffer.get(samplesA);
        assertEquals(1, samplesA[0]);
        assertEquals(1, samplesA[1]);
        assertEquals(0, samplesA[2]);
        byte[] samplesB = new byte[buckets];
        buffer.get(samplesB);
        assertEquals(1, samplesB[0]);
        assertEquals(0, buffer.remaining(), "bin 长度应与布局精确匹配");
    }

    @Test
    void crawlSpeedRoundsUpToOneNotZero() {
        // 0.3km/h 爬行不能四舍五入成 0（0 是无数据哨兵）
        MatsimLinkSpeedCache.SpeedAggregator agg = aggregator();
        agg.registerBusVehicle("bus1");
        double t0 = bucketStart(40);
        agg.linkEnter("bus1", "A", t0);
        agg.linkLeave("bus1", "A", t0 + 1200); // 100m/1200s = 0.3km/h
        assertEquals(1, MatsimLinkSpeedCache.smoothedSpeedKmh(agg.byLink.get("A"), 40));
    }
}
