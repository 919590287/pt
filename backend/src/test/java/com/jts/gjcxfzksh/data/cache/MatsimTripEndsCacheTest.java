package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import com.jts.gjcxfzksh.data.id.PersonId;
import com.jts.gjcxfzksh.data.id.StopFacilityId;
import com.jts.gjcxfzksh.data.id.VehicleId;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 出行分布缓存纯单测（不依赖 Spring/数据盘）。
 * 端点侧（plans 活动出行口径，v4）：非 interaction 活动切分 trip、mode=pt leg 判定、
 * 端点=trip 两端活动坐标、缺坐标端点跳过、selectedPlan 回退、journeys/riders 计数。
 * OD 侧（events 站点口径，不随 v4 改动）：enter/leave 配对与 dropped 口径、
 * 30min/800m 链接（含边界值）、缺坐标保守断链、OD 配对聚合与 PGOD 编码
 * （人次降序 / Top-K 截断 / 自环 / 街道哨兵列）。
 * 街道归属沿用 MatsimPopulationCacheTest 已覆盖的 StreetIndex，此处传 null 走 unassigned 路径。
 */
class MatsimTripEndsCacheTest {

    /** 测试栅格边长：直接取 100（赤道 cos(lat)=1，格键即坐标 ÷100 取整）。 */
    private static final double CELL = 100.0;

    // ---------------------------------------------------------------- 构造工具（OD / events 侧）

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

    // ---------------------------------------------------------------- 构造工具（端点 / plans 侧）

    private static Population population() {
        return PopulationUtils.createPopulation(ConfigUtils.createConfig());
    }

    /** 活动元素：x=null 表示缺坐标（挂 link）。测试固定 y=0（赤道语义同 OD 侧）。 */
    private static Object[] act(String type, Double x) {
        return new Object[]{type, x};
    }

    /**
     * 构造 person：elements 依序传入活动（{@link #act}）与 leg mode 字符串，
     * 交替顺序由用例自行保证（与 plans 文件的 Activity/Leg 序列同构）。
     */
    private static Person person(Population population, String id, Object... elements) {
        PopulationFactory factory = population.getFactory();
        Person person = factory.createPerson(Id.createPersonId(id));
        Plan plan = factory.createPlan();
        for (Object element : elements) {
            if (element instanceof String mode) {
                plan.addLeg(factory.createLeg(mode));
                continue;
            }
            Object[] spec = (Object[]) element;
            String type = (String) spec[0];
            Double x = (Double) spec[1];
            plan.addActivity(x == null
                    ? factory.createActivityFromLinkId(type, Id.createLinkId("l1"))
                    : factory.createActivityFromCoord(type, new Coord(x, 0.0)));
        }
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        population.addPerson(person);
        return person;
    }

    private static MatsimTripEndsCache.Aggregation aggregatePlans(Population population) {
        MatsimTripEndsCache.Aggregation aggregation =
                new MatsimTripEndsCache.Aggregation(CELL, null, Map.of());
        for (Person person : population.getPersons().values()) {
            aggregation.acceptPerson(person, null);
        }
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

    // ---------------------------------------------------------------- 端点用例（plans 活动出行口径）

    @Test
    void 含pt的trip计一次出行_端点为活动坐标_interaction不是边界() {
        Population population = population();
        // 标准 pt trip 展开链：home → walk → pt interaction → pt → pt interaction → walk → work
        person(population, "p1",
                act("home", 0.0), "walk",
                act("pt interaction", 4900.0), "pt",
                act("pt interaction", 4950.0), "walk",
                act("work", 5000.0));
        MatsimTripEndsCache.Aggregation agg = aggregatePlans(population);
        assertEquals(1, agg.journeys);
        assertEquals(1, agg.riders);
        assertEquals(1, agg.originPoints);
        assertEquals(1, agg.destPoints);
        assertEquals(1, cellCount(agg.originCells, 0, 0), "起点应落 home 活动所在格");
        assertEquals(1, cellCount(agg.destCells, 5000, 0), "终点应落 work 活动所在格");
        assertEquals(0, cellCount(agg.originCells, 4900, 0), "interaction 活动不是端点");
        assertEquals(0, cellCount(agg.destCells, 4950, 0), "interaction 活动不是端点");
        // 本测试未挂街道索引：全部端点计入 unassigned（与人口分布同语义）
        assertEquals(1, agg.unassignedOrigin);
        assertEquals(1, agg.unassignedDest);
    }

    @Test
    void 非公交trip不计入出行() {
        Population population = population();
        person(population, "p1", act("home", 0.0), "car", act("work", 5000.0));
        person(population, "p2", act("home", 0.0), "walk", act("shop", 300.0));
        MatsimTripEndsCache.Aggregation agg = aggregatePlans(population);
        assertEquals(0, agg.journeys);
        assertEquals(0, agg.riders);
        assertEquals(0, agg.originPoints);
        assertEquals(0, agg.destPoints);
    }

    @Test
    void V6实际公交制式名也计入出行_transitWalk不计() {
        Population population = population();
        person(population, "subway-rider", act("home", 0.0), "subway", act("work", 5000.0));
        person(population, "bus-rider", act("home", 100.0), "bus", act("shop", 6000.0));
        person(population, "access-only", act("home", 200.0), "transit_walk", act("work", 7000.0));

        MatsimTripEndsCache.Aggregation agg = aggregatePlans(population);
        assertEquals(2, agg.journeys, "V6 plans 的 subway/bus leg 必须按公交出行统计");
        assertEquals(2, agg.riders);
        assertEquals(2, agg.originPoints);
        assertEquals(2, agg.destPoints);
    }

    @Test
    void 多trip分别计数_中间活动为下一段起点_riders按人去重() {
        Population population = population();
        // p1：home →pt→ work →car→ shop →pt→ home（第 2 段为 car 不计）
        person(population, "p1",
                act("home", 0.0), "pt",
                act("work", 5000.0), "car",
                act("shop", 30000.0), "pt",
                act("home", 0.0));
        // p2：纯小汽车出行者
        person(population, "p2", act("home", 40000.0), "car", act("work", 5000.0));
        MatsimTripEndsCache.Aggregation agg = aggregatePlans(population);
        assertEquals(2, agg.journeys, "p1 两次公交出行，car 段不计");
        assertEquals(1, agg.riders, "riders 按人去重，纯 car 者不计");
        assertEquals(2, agg.originPoints);
        assertEquals(2, agg.destPoints);
        assertEquals(1, cellCount(agg.originCells, 0, 0), "第一段起点=home");
        assertEquals(1, cellCount(agg.originCells, 30000, 0), "第二段起点=shop 活动位置");
        assertEquals(1, cellCount(agg.destCells, 5000, 0), "第一段终点=work");
        assertEquals(1, cellCount(agg.destCells, 0, 0), "第二段终点=home");
        assertEquals(0, cellCount(agg.originCells, 5000, 0), "work 不是任何公交出行的起点（第 2 段是 car）");
    }

    @Test
    void 活动缺坐标端点跳过_journeys照计() {
        Population population = population();
        person(population, "p1", act("home", null), "pt", act("work", 5000.0));
        MatsimTripEndsCache.Aggregation agg = aggregatePlans(population);
        assertEquals(1, agg.journeys, "缺坐标不影响出行计数");
        assertEquals(1, agg.riders);
        assertEquals(0, agg.originPoints, "无坐标起点跳过");
        assertEquals(1, agg.destPoints);
        assertEquals(1, cellCount(agg.destCells, 5000, 0));
    }

    @Test
    void selectedPlan为空回退首plan() {
        Population population = population();
        Person person = person(population, "p1", act("home", 0.0), "pt", act("work", 5000.0));
        person.setSelectedPlan(null);
        MatsimTripEndsCache.Aggregation agg = aggregatePlans(population);
        assertEquals(1, agg.journeys);
        assertEquals(1, cellCount(agg.originCells, 0, 0));
    }

    @Test
    void 端点统计不受events轨迹影响_OD统计不受plans影响() {
        // 同一 Aggregation 上先 plans 后 events：两套口径互不串写
        Map<String, double[]> coords = equatorCoords("A", 10000, "B", 20000);
        Population population = population();
        person(population, "p1", act("home", 0.0), "pt", act("work", 5000.0));
        MatsimTripEndsCache.Aggregation agg =
                new MatsimTripEndsCache.Aggregation(CELL, null, coords);
        for (Person person : population.getPersons().values()) {
            agg.acceptPerson(person, null);
        }
        MatsimTripEndsCache.aggregateJourneys(List.of(
                track("p1", "v1", "A", true, 100),
                track("p1", "v1", "B", false, 700)
        ), coords, agg);
        assertEquals(1, agg.journeys, "journeys 只来自 plans");
        assertEquals(1, agg.originPoints, "端点只来自 plans 活动坐标");
        assertEquals(0, cellCount(agg.originCells, 10000, 0), "上车站不再计入端点");
        assertEquals(1, agg.odJourneys, "OD 只来自 events");
        assertEquals(1, odPairCount(agg, 10000, 20000), "OD 仍为上下车站格对");
        assertEquals(0, odPairCount(agg, 0, 5000), "活动端不产生 OD");
    }

    // ---------------------------------------------------------------- OD 用例（events 站点口径）

    @Test
    void 单段乘车即一段整段出行_OD为上下车站格对() {
        Map<String, double[]> coords = equatorCoords("A", 0, "B", 5000);
        MatsimTripEndsCache.Aggregation agg = aggregate(List.of(
                track("p1", "v1", "A", true, 100),
                track("p1", "v1", "B", false, 700)
        ), coords);
        assertEquals(1, agg.odJourneys);
        assertEquals(0, agg.odSkipped);
        assertEquals(0, agg.droppedTracks);
        assertEquals(1, odPairCount(agg, 0, 5000));
        assertEquals(0, agg.journeys, "events 不再产生端点侧出行计数");
        assertEquals(0, agg.originPoints, "events 不再产生端点统计");
    }

    @Test
    void 时间窗与距离双满足则链为同一出行_边界值含() {
        // B 下车 → C 上车：间隔恰 1800s、距离恰 800m，应链接；OD=(A, D)
        Map<String, double[]> coords = equatorCoords("A", 0, "B", 5000, "C", 5800, "D", 20000);
        MatsimTripEndsCache.Aggregation agg = aggregate(List.of(
                track("p1", "v1", "A", true, 100),
                track("p1", "v1", "B", false, 700),
                track("p1", "v2", "C", true, 700 + 1800),
                track("p1", "v2", "D", false, 4000)
        ), coords);
        assertEquals(1, agg.odJourneys, "1800s/800m 边界值应视为同一出行");
        assertEquals(1, odPairCount(agg, 0, 20000));
        assertEquals(0, odPairCount(agg, 0, 5000), "换乘中间站不是 OD 端点");
        assertEquals(0, odPairCount(agg, 5800, 20000), "换乘上车点不是 OD 起点");
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
        assertEquals(2, late.odJourneys, "超 1800s 应断链");
        assertEquals(1, odPairCount(late, 0, 5000));
        assertEquals(1, odPairCount(late, 5000, 20000));
        // 超距：B(5000)→C(5801) 相距 801m
        MatsimTripEndsCache.Aggregation far = aggregate(List.of(
                track("p1", "v1", "A", true, 100),
                track("p1", "v1", "B", false, 700),
                track("p1", "v2", "C", true, 900),
                track("p1", "v2", "D", false, 9000)
        ), coords);
        assertEquals(2, far.odJourneys, "超 800m 应断链");
        assertEquals(1, odPairCount(far, 0, 5000));
        assertEquals(1, odPairCount(far, 5801, 20000), "断链后第二段的上车点是新 OD 起点");
    }

    @Test
    void 缺坐标无法校验距离时保守断链_缺坐标OD跳过() {
        // X 无坐标：B→X 距离不可判 → 断链；第二段起点 X 无坐标 → OD 整体跳过
        Map<String, double[]> coords = equatorCoords("A", 0, "B", 5000, "D", 20000);
        MatsimTripEndsCache.Aggregation agg = aggregate(List.of(
                track("p1", "v1", "A", true, 100),
                track("p1", "v1", "B", false, 700),
                track("p1", "v2", "X", true, 800),
                track("p1", "v2", "D", false, 9000)
        ), coords);
        assertEquals(1, agg.odJourneys, "坐标齐全的第一段照计");
        assertEquals(1, agg.odSkipped, "起点缺坐标的第二段跳过");
        assertEquals(1, odPairCount(agg, 0, 5000));
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
        assertEquals(0, agg.odJourneys);
        assertEquals(0, agg.odSkipped);
        assertEquals(6, agg.droppedTracks);
    }

    @Test
    void OD配对聚合_同格对累加_自环保留_缺坐标跳过() {
        // p1: A(0)→B(5000)；p2: A'(50)→B'(5050) 与 p1 同一格对 → count 2；
        // p3: C(30000)→C'(30050) 同格自环；p4: 起点 X 无坐标 → odSkipped
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
}
