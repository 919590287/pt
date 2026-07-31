package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.optimization.util.GeoUtil;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 人口分布缓存纯单测（不依赖 Spring/数据盘）：§1 提取口径（home/work 前缀、interaction 排除、
 * 首点规则、null 坐标跳过、selectedPlan 缺失即失败）、栅格分箱（含负坐标 floor 语义）、
 * grid.bin 布局逐字节回读（§3 契约）、街道资源完整性（176/南沙9）与点面归属自反性、
 * streets 总和 + unassigned 对账恒等式、ctf 复刻语义、端到端落盘幂等。
 */
class MatsimPopulationCacheTest {

    private static final double EARTH_RADIUS = 6378137.0;
    /** 广州近似中心（23.1°N）的 3857 y。 */
    private static final double GZ_CENTER_Y = mercatorY(23.1);

    // ---------------------------------------------------------------- 构造工具

    private static double mercatorY(double lat) {
        return EARTH_RADIUS * Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2));
    }

    private static Population newPopulation() {
        return PopulationUtils.createPopulation(ConfigUtils.createConfig());
    }

    /** 追加 person：acts 里 Coord 为活动坐标（null 表示 linkId 活动无坐标），String 为活动类型。 */
    private static Person person(Population population, String id, Object... typeCoordPairs) {
        PopulationFactory factory = population.getFactory();
        Person person = factory.createPerson(Id.createPersonId(id));
        Plan plan = factory.createPlan();
        for (int i = 0; i < typeCoordPairs.length; i += 2) {
            String type = (String) typeCoordPairs[i];
            Coord coord = (Coord) typeCoordPairs[i + 1];
            Activity act = coord == null
                    ? factory.createActivityFromLinkId(type, Id.createLinkId("l1"))
                    : factory.createActivityFromCoord(type, coord);
            plan.addActivity(act);
            if (i + 2 < typeCoordPairs.length) {
                plan.addLeg(factory.createLeg("walk"));
            }
        }
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        population.addPerson(person);
        return person;
    }

    /** 按 §3 布局逐字段回读 grid.bin。 */
    private record DecodedGrid(int version, int count, double mercCellSize,
                               int[] i, int[] j, long[] home, long[] work, long[] resident, int[] street) {
    }

    private static DecodedGrid decode(byte[] bin) {
        ByteBuffer buffer = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        buffer.get(magic);
        assertArrayEquals(new byte[]{'P', 'G', 'R', 'D'}, magic, "magic 必须为 ASCII PGRD");
        int version = Short.toUnsignedInt(buffer.getShort());
        int recordBytes = version == 3 ? 22 : 18;
        assertEquals(0, (bin.length - 18) % recordBytes, "记录区必须是单行字节数的整数倍");
        int count = buffer.getInt();
        double mercCellSize = buffer.getDouble();
        int[] i = new int[count];
        int[] j = new int[count];
        long[] home = new long[count];
        long[] work = new long[count];
        long[] resident = new long[count];
        int[] street = new int[count];
        for (int r = 0; r < count; r++) {
            i[r] = buffer.getInt();
            j[r] = buffer.getInt();
            home[r] = Integer.toUnsignedLong(buffer.getInt());
            work[r] = Integer.toUnsignedLong(buffer.getInt());
            resident[r] = version == 3 ? Integer.toUnsignedLong(buffer.getInt()) : home[r];
            street[r] = Short.toUnsignedInt(buffer.getShort());
        }
        assertEquals(0, buffer.remaining(), "bin 不得有多余字节（无对齐填充）");
        return new DecodedGrid(version, count, mercCellSize, i, j, home, work, resident, street);
    }

    // ---------------------------------------------------------------- 提取口径（§1）

    @Test
    void extractionFollowsHomeWorkFirstMatchRules() {
        Population population = newPopulation();
        // p1: interaction 前缀陷阱 + 首 home/work + 第二 home 忽略
        person(population, "p1",
                "home interaction", new Coord(9000.0, 9000.0), // 含 interaction 必须跳过（即使 home 前缀）
                "home", new Coord(10.0, 10.0),
                "work", new Coord(210.0, 210.0),
                "home", new Coord(9100.0, 9100.0));            // 第二个 home 不覆盖首点
        // p2: 只有 home，无 work
        person(population, "p2", "home", new Coord(10.0, 10.0));
        // p3: 大小写混合前缀
        person(population, "p3",
                "Home_night", new Coord(-50.0, -50.0),
                "Work_shift2", new Coord(310.0, 310.0));
        // p4: 无 home/work 活动
        person(population, "p4", "school", new Coord(10.0, 10.0));
        // p5: 首 home 无坐标（linkId 活动）→ 跳过该点，取第二个 home
        person(population, "p5",
                "home", null,
                "home", new Coord(510.0, 510.0));
        MatsimPopulationCache.Aggregation aggregation =
                new MatsimPopulationCache.Aggregation(100.0, null);
        for (Person person : population.getPersons().values()) {
            aggregation.acceptPerson(person, null);
        }

        assertEquals(5, aggregation.persons);
        assertEquals(4, aggregation.homePersons);  // p1/p2/p3/p5
        assertEquals(2, aggregation.commuterHomePersons); // 仅 p1/p3 同时有 home/work
        assertEquals(2, aggregation.workPersons);  // p1/p3
        // 无街道索引：全部点计入 unassigned（对账口径 home/work 分开）
        assertEquals(4, aggregation.unassignedHome);
        assertEquals(2, aggregation.unassignedWork);
        // 类型集合：收集全部匹配前缀的原始 type；interaction 类型绝不入集
        assertEquals(List.of("Home_night", "home"), List.copyOf(aggregation.homeTypes));
        assertEquals(List.of("Work_shift2", "work"), List.copyOf(aggregation.workTypes));

        // 栅格归属：cell(0,0) 有 p1/p2 的 home；(9000,9000)/(9100,9100) 的陷阱点不存在
        assertEquals(2, aggregation.homeCells.get(MatsimPopulationCache.packCell(0, 0)));
        assertEquals(1, aggregation.homeCells.get(MatsimPopulationCache.packCell(-1, -1))); // p3 (-50,-50)
        assertEquals(1, aggregation.homeCells.get(MatsimPopulationCache.packCell(5, 5)));   // p5 (510,510)
        assertEquals(0, aggregation.homeCells.get(MatsimPopulationCache.packCell(90, 90)));
        assertEquals(0, aggregation.homeCells.get(MatsimPopulationCache.packCell(91, 91)));
        assertEquals(1, aggregation.workCells.get(MatsimPopulationCache.packCell(2, 2)));   // p1 (210,210)
        assertEquals(1, aggregation.workCells.get(MatsimPopulationCache.packCell(3, 3)));   // p3 (310,310)
        assertEquals(1, aggregation.commuterHomeCells.get(MatsimPopulationCache.packCell(0, 0))); // p1
        assertEquals(1, aggregation.commuterHomeCells.get(MatsimPopulationCache.packCell(-1, -1))); // p3
        assertEquals(3, aggregation.homeCells.size());
        assertEquals(2, aggregation.commuterHomeCells.size());
        assertEquals(2, aggregation.workCells.size());
    }

    @Test
    void extractionRejectsMissingSelectedPlan() {
        Population population = newPopulation();
        Person invalid = person(population, "missing-selected", "home", new Coord(10.0, 10.0));
        invalid.setSelectedPlan(null);
        MatsimPopulationCache.Aggregation aggregation =
                new MatsimPopulationCache.Aggregation(100.0, null);

        assertThrows(IllegalStateException.class, () -> aggregation.acceptPerson(invalid, null));
    }

    // ---------------------------------------------------------------- 栅格分箱

    @Test
    void cellKeyUsesFloorSemanticsAndSurvivesNegativePackRoundTrip() {
        // floor 语义：负坐标向负无穷取整
        assertEquals(MatsimPopulationCache.packCell(0, 0), MatsimPopulationCache.cellKey(0.0, 0.0, 100.0));
        assertEquals(MatsimPopulationCache.packCell(0, 0), MatsimPopulationCache.cellKey(99.999, 0.0, 100.0));
        assertEquals(MatsimPopulationCache.packCell(1, -1), MatsimPopulationCache.cellKey(100.0, -100.0, 100.0));
        assertEquals(MatsimPopulationCache.packCell(-1, -1), MatsimPopulationCache.cellKey(-0.5, -0.5, 100.0));
        assertEquals(MatsimPopulationCache.packCell(-2, -1), MatsimPopulationCache.cellKey(-100.001, -99.999, 100.0));
        // pack/unpack 负数回环（j 低 32 位补码不串位）
        int[][] cases = {{0, 0}, {-1, -1}, {-1, 5}, {5, -1}, {Integer.MIN_VALUE, Integer.MAX_VALUE},
                {Integer.MAX_VALUE, Integer.MIN_VALUE}, {123456, -654321}};
        for (int[] c : cases) {
            long key = MatsimPopulationCache.packCell(c[0], c[1]);
            assertEquals(c[0], MatsimPopulationCache.cellI(key), "i 回环失败: " + c[0] + "," + c[1]);
            assertEquals(c[1], MatsimPopulationCache.cellJ(key), "j 回环失败: " + c[0] + "," + c[1]);
        }
    }

    @Test
    void mercCellSizeFollowsCenterLatitude() {
        // 广州 23.1°N：100 / cos(23.1°)
        double expected = 100.0 / Math.cos(Math.toRadians(23.1));
        assertEquals(expected, MatsimPopulationCache.mercCellSize(new Coord(1.26e7, GZ_CENTER_Y)), 1e-9);
        // center 缺失按赤道处理
        assertEquals(100.0, MatsimPopulationCache.mercCellSize(null), 1e-12);
    }

    @Test
    void populationV10PersistsPeakSpeedAndCompleteBusJourneyDenominators() {
        MatsimPopulationCache.Aggregation aggregation =
                new MatsimPopulationCache.Aggregation(100.0, null);
        aggregation.busServiceJourneys = 4;
        aggregation.busServiceTransitBoardings = 7;
        aggregation.busServiceTransfers = 3;
        aggregation.busRailJourneys = 1;
        aggregation.peakCarDistanceMeters = 36_000;
        aggregation.peakCarTravelSeconds = 3_600;

        Map<String, Object> summary =
                MatsimPopulationCache.assemble(aggregation, null, 1.0).summary;

        assertEquals("population-v11", summary.get("cacheVersion"));
        assertEquals("ready", summary.get("busServiceJourneyStatus"));
        assertEquals(0.75, ((Number) summary.get("averageBusTransfers")).doubleValue(), 1e-9);
        assertEquals(25.0, ((Number) summary.get("busRailFeederPercent")).doubleValue(), 1e-9);
        assertEquals(36.0,
                ((Number) ((Map<?, ?>) summary.get("speedKmh")).get("carAvg")).doubleValue(), 1e-9);
        assertNull(((Map<?, ?>) summary.get("speedKmh")).get("ptAvg"),
                "公交高峰运营速度必须由班次缓存计算，不能写入乘客 leg 加权速度");
    }

    @Test
    void coverageUsesAllPersonsWithoutSamplingAndReportsNoDataExplicitly(@TempDir Path tempDir) throws Exception {
        MutableScenario scenario = (MutableScenario) ScenarioUtils.createScenario(ConfigUtils.createConfig());
        scenario.getTransitSchedule().getAttributes().putAttribute(
                "coordinateReferenceSystem", "EPSG:3857");
        TransitStopFacility stop = scenario.getTransitSchedule().getFactory().createTransitStopFacility(
                Id.create("stop-1", TransitStopFacility.class), new Coord(1_000.0, 1_000.0), false);
        scenario.getTransitSchedule().addStopFacility(stop);
        TransitLine busLine = scenario.getTransitSchedule().getFactory().createTransitLine(
                Id.create("bus-line", TransitLine.class));
        busLine.addRoute(scenario.getTransitSchedule().getFactory().createTransitRoute(
                Id.create("bus-route", TransitRoute.class),
                RouteUtils.createLinkNetworkRouteImpl(Id.createLinkId("l1"), Id.createLinkId("l1")),
                List.of(scenario.getTransitSchedule().getFactory().createTransitRouteStop(stop, 0, 0)),
                "bus"));
        scenario.getTransitSchedule().addTransitLine(busLine);
        person(scenario.getPopulation(), "near", "home", new Coord(1_250.0, 1_000.0));
        person(scenario.getPopulation(), "far", "home", new Coord(2_000.0, 1_000.0));

        Path output = tempDir.resolve("output");
        java.nio.file.Files.createDirectories(output);
        new org.matsim.core.config.ConfigWriter(ConfigUtils.createConfig())
                .write(output.resolve("output_config.xml").toString());
        MatsimData data = new MatsimData("coverage-unit", output.toString());
        data.setScenario(scenario);
        MatsimPopulationCache.CoverageIndex coverage = MatsimPopulationCache.coverageIndex(data);
        MatsimPopulationCache.Aggregation aggregation =
                new MatsimPopulationCache.Aggregation(100.0, null, null, coverage);
        scenario.getPopulation().getPersons().values().forEach(person -> aggregation.acceptPerson(person, null));

        Map<String, Object> summary = MatsimPopulationCache.assemble(aggregation, null, 0.01).summary;
        assertEquals(2L, ((Number) summary.get("persons")).longValue());
        assertEquals(1L, ((Number) summary.get("coveredPersons300m")).longValue());
        assertEquals(50.0, ((Number) summary.get("coverage300Percent")).doubleValue(), 1e-9);
        assertEquals("ready", summary.get("coverage300Status"));
        assertEquals(1.0, ((Number) summary.get("scale")).doubleValue(), 0.0,
                "传入的历史抽样值不得改变统计");

        MatsimPopulationCache.Aggregation noSchedule =
                new MatsimPopulationCache.Aggregation(100.0, null, null,
                        MatsimPopulationCache.coverageIndex(null));
        scenario.getPopulation().getPersons().values().forEach(person -> noSchedule.acceptPerson(person, null));
        Map<String, Object> noData = MatsimPopulationCache.assemble(noSchedule, null, 1.0).summary;
        assertNull(noData.get("coveredPersons300m"));
        assertNull(noData.get("coverage300Percent"));
        assertEquals("unsupported", noData.get("coverage300Status"),
                "未声明坐标系时不能把300m地面距离伪装成普通无数据");
    }

    // ---------------------------------------------------------------- grid.bin 契约（§3）

    @Test
    void gridBinLayoutRoundTripsHeaderAndRecordsByteExactly() {
        Long2IntOpenHashMap home = new Long2IntOpenHashMap();
        Long2IntOpenHashMap work = new Long2IntOpenHashMap();
        home.put(MatsimPopulationCache.packCell(-1, -1), 2);
        home.put(MatsimPopulationCache.packCell(0, 0), 1);
        work.put(MatsimPopulationCache.packCell(-1, -1), 1);
        work.put(MatsimPopulationCache.packCell(5, -3), 4); // 只有 work 的 cell（home=0）
        double mercCellSize = 108.712345;

        byte[] bin = MatsimPopulationCache.encodeGrid(home, work, mercCellSize, null);
        assertEquals(18 + 18 * 3, bin.length, "总长 = 头 18 + 18 × cell 数");

        // 头部逐字节：magic "PGRD" + version u16=2（小端）+ count u32=3（小端）
        assertEquals('P', bin[0]);
        assertEquals('G', bin[1]);
        assertEquals('R', bin[2]);
        assertEquals('D', bin[3]);
        assertEquals(2, bin[4]);
        assertEquals(0, bin[5]);
        assertEquals(3, bin[6]);
        assertEquals(0, bin[7]);
        assertEquals(0, bin[8]);
        assertEquals(0, bin[9]);

        DecodedGrid decoded = decode(bin);
        assertEquals(2, decoded.version());
        assertEquals(3, decoded.count());
        assertEquals(mercCellSize, decoded.mercCellSize(), 0.0); // f64 精确回读
        // 写入序按打包键升序：(-1,-1) < (0,0) < (5,-3)（同 i 内 j 无符号序）
        assertArrayEquals(new int[]{-1, 0, 5}, decoded.i());
        assertArrayEquals(new int[]{-1, 0, -3}, decoded.j());
        assertArrayEquals(new long[]{2, 1, 0}, decoded.home());
        assertArrayEquals(new long[]{1, 0, 4}, decoded.work());
        // 无街道索引：street 列一律哨兵 0xFFFF
        assertArrayEquals(new int[]{0xFFFF, 0xFFFF, 0xFFFF}, decoded.street());
    }

    @Test
    void emptyGridProducesHeaderOnlyBin() {
        byte[] bin = MatsimPopulationCache.encodeGrid(
                new Long2IntOpenHashMap(), new Long2IntOpenHashMap(), 100.0, null);
        assertEquals(18, bin.length);
        DecodedGrid decoded = decode(bin);
        assertEquals(0, decoded.count());
        assertEquals(100.0, decoded.mercCellSize(), 0.0);
    }

    // ---------------------------------------------------------------- 街道资源与点面归属

    @Test
    void streetIndexLoads176FeaturesAndLocatesInteriorPointsBackToSelf() {
        // 冷加载计时：绕过进程级单例，直接走 gz 解析 + 索引构建（真实首模型构建成本）
        long start = System.currentTimeMillis();
        MatsimPopulationCache.StreetIndex cold =
                MatsimPopulationCache.loadStreetIndex(MatsimPopulationCache.streetsGeojsonGzBytes());
        long elapsed = System.currentTimeMillis() - start;
        assertEquals(176, cold.size(), "街道要素必须 176 全量");
        MatsimPopulationCache.StreetIndex index = MatsimPopulationCache.streetIndex();
        assertEquals(176, index.size(), "街道要素必须 176 全量");

        Map<String, Integer> byDistrict = new HashMap<>();
        Map<String, Integer> byCode = new HashMap<>();
        for (int i = 0; i < index.size(); i++) {
            MatsimPopulationCache.StreetRef street = index.street(i);
            byDistrict.merge(street.district(), 1, Integer::sum);
            assertNull(byCode.put(street.code(), i), "street code 必须唯一: " + street.code());
            assertTrue(street.areaKm2() > 0, "areaKm2 必须为正: " + street.name());
        }
        assertEquals(9, byDistrict.get("南沙区"), "南沙区街道数必须为 9");
        assertEquals(11, byDistrict.size(), "行政区数必须为 11");

        // 每个街道的 JTS interiorPoint 反查归属回自身（含 buffer(0) 修复的无效面）
        for (int i = 0; i < index.size(); i++) {
            Point interior = index.geometry(i).getInteriorPoint();
            assertEquals(i, index.locate(interior.getX(), interior.getY()),
                    "interiorPoint 反查失败: " + index.street(i).name());
        }
        // 远离广州的点（3857 原点=几内亚湾）不归属任何街道
        assertEquals(-1, index.locate(0.0, 0.0));
        System.out.printf("[population-test] 街道索引冷加载耗时=%dms, features=%d%n", elapsed, cold.size());
    }

    // ---------------------------------------------------------------- 对账恒等式（§6）

    @Test
    @SuppressWarnings("unchecked")
    void streetTotalsPlusUnassignedReconcileWithPersonCounts() {
        MatsimPopulationCache.StreetIndex index = MatsimPopulationCache.streetIndex();
        Point s0 = index.geometry(0).getInteriorPoint();
        Point s1 = index.geometry(1).getInteriorPoint();
        Point s2 = index.geometry(2).getInteriorPoint();

        Population population = newPopulation();
        // 街内 home ×3（s0×1、s1×2），街外 home ×1；街内 work ×1（s2），街外 work ×1
        person(population, "p1", "home", new Coord(s0.getX(), s0.getY()),
                "work", new Coord(s2.getX(), s2.getY()));
        person(population, "p2", "home", new Coord(s1.getX(), s1.getY()));
        person(population, "p3", "home", new Coord(s1.getX(), s1.getY()),
                "work", new Coord(0.0, 0.0)); // 街外 work
        person(population, "p4", "home", new Coord(0.0, 0.0)); // 街外 home

        MatsimPopulationCache.Aggregation aggregation = new MatsimPopulationCache.Aggregation(
                MatsimPopulationCache.mercCellSize(new Coord(s0.getX(), GZ_CENTER_Y)), index);
        for (Person person : population.getPersons().values()) {
            aggregation.acceptPerson(person, null);
        }
        MatsimPopulationCache.Artifacts artifacts =
                MatsimPopulationCache.assemble(aggregation, index, 0.1);

        Map<String, Object> streetsPayload = artifacts.streets;
        List<Map<String, Object>> rows = (List<Map<String, Object>>) streetsPayload.get("streets");
        assertEquals(176, rows.size(), "街道统计必须 176 全量（含 0 值）");
        assertEquals(index.street(0).code(), rows.get(0).get("code"));
        assertEquals(index.street(0).name(), rows.get(0).get("name"));
        assertEquals(1, rows.get(0).get("home"));
        assertEquals(1, rows.get(1).get("home"));
        assertEquals(1, rows.get(0).get("resident"));
        assertEquals(2, rows.get(1).get("resident"));
        assertEquals(1, rows.get(2).get("work"));

        long sumHome = rows.stream().mapToLong(r -> ((Number) r.get("home")).longValue()).sum();
        long sumWork = rows.stream().mapToLong(r -> ((Number) r.get("work")).longValue()).sum();
        long sumResident = rows.stream().mapToLong(r -> ((Number) r.get("resident")).longValue()).sum();
        Map<String, Object> totals = (Map<String, Object>) streetsPayload.get("totals");
        assertEquals(sumHome, ((Number) totals.get("home")).longValue());
        assertEquals(sumWork, ((Number) totals.get("work")).longValue());
        assertEquals(sumResident, ((Number) totals.get("resident")).longValue());

        Map<String, Object> summary = artifacts.summary;
        // §6 对账恒等式：streets 总和 + unassigned == homePersons/workPersons
        assertEquals(((Number) summary.get("commuterHomePersons")).longValue(),
                sumHome + ((Number) summary.get("unassignedCommuterHome")).longValue());
        assertEquals(((Number) summary.get("homePersons")).longValue(),
                sumResident + ((Number) summary.get("unassignedHome")).longValue());
        assertEquals(((Number) summary.get("workPersons")).longValue(),
                sumWork + ((Number) summary.get("unassignedWork")).longValue());
        assertEquals(4L, ((Number) summary.get("persons")).longValue());
        assertEquals(4L, ((Number) summary.get("homePersons")).longValue());
        assertEquals(2L, ((Number) summary.get("workPersons")).longValue());
        assertEquals(1L, ((Number) summary.get("unassignedHome")).longValue());
        assertEquals(1L, ((Number) summary.get("unassignedWork")).longValue());

        // grid 与 summary 跨工件对账：home/work/resident 分别对应通勤居住地/就业地/常住人口。
        DecodedGrid grid = decode(artifacts.gridBin);
        assertEquals(((Number) summary.get("gridCells")).intValue(), grid.count());
        long gridHome = 0;
        long gridWork = 0;
        long gridResident = 0;
        for (int r = 0; r < grid.count(); r++) {
            gridHome += grid.home()[r];
            gridWork += grid.work()[r];
            gridResident += grid.resident()[r];
        }
        assertEquals(2, gridHome);
        assertEquals(2, gridWork);
        assertEquals(4, gridResident);
        assertEquals(1.0, (Double) summary.get("scale"), 1e-9);
        assertEquals(100, summary.get("cellSizeMeters"));
        assertEquals(aggregation.mercCellSize, (Double) summary.get("mercCellSize"), 0.0);
    }

    // ---------------------------------------------------------------- ctf 复刻语义

    @Test
    void ctfReplicatesDatasourceSelectionSemantics() {
        // 已是 3857（大小写不敏感）→ 不转换
        assertNull(MatsimPopulationCache.ctf(null, null, "epsg:3857"));
        assertNull(MatsimPopulationCache.ctf(null, null, "EPSG:3857"));
        assertNull(MatsimPopulationCache.ctf("epsg:3857", null, null));
        assertNull(MatsimPopulationCache.ctf(null, "EPSG:3857", null));
        assertNull(MatsimPopulationCache.ctf(null, null, null));
        // 优先级：文件属性 CRS > inputCRS > globalCRS（属性已是 3857 时 inputCRS 不生效）
        assertNull(MatsimPopulationCache.ctf(null, "EPSG:4326", "epsg:3857"));
        // WGS84 → 3857 的实际转换与 GeoUtil.lngLatToMercator 同源可互验
        CoordinateTransformation ctf = MatsimPopulationCache.ctf(null, null, "EPSG:4326");
        assertNotNull(ctf);
        Coord transformed = ctf.transform(new Coord(113.3, 23.1));
        double[] expected = GeoUtil.lngLatToMercator(113.3, 23.1);
        assertEquals(expected[0], transformed.getX(), Math.abs(expected[0]) * 1e-6);
        assertEquals(expected[1], transformed.getY(), Math.abs(expected[1]) * 1e-6);
    }

    // ---------------------------------------------------------------- 端到端落盘

    @Test
    @SuppressWarnings("unchecked")
    void prepareOnModelLoadWritesArtifactsAtomicallyAndIsIdempotent(@TempDir Path tempDir) throws Exception {
        Path outputDir = tempDir.resolve("output");
        Path cacheDir = tempDir.resolve("cache");
        java.nio.file.Files.createDirectories(outputDir);
        java.nio.file.Files.createDirectories(cacheDir);
        // MatsimOutFile.reload 要求 output 目录至少有 config.xml
        new org.matsim.core.config.ConfigWriter(ConfigUtils.createConfig())
                .write(outputDir.resolve("output_config.xml").toString());

        MatsimPopulationCache.StreetIndex index = MatsimPopulationCache.streetIndex();
        Point s0 = index.geometry(0).getInteriorPoint();

        MatsimData data = new MatsimData("population-unit", outputDir.toString(), cacheDir.toString(), false);
        MutableScenario scenario = (MutableScenario) ScenarioUtils.createScenario(ConfigUtils.createConfig());
        person(scenario.getPopulation(), "p1", "home", new Coord(s0.getX(), s0.getY()),
                "work", new Coord(s0.getX() + 50.0, s0.getY() + 50.0));
        person(scenario.getPopulation(), "p2", "home", new Coord(s0.getX() + 10.0, s0.getY()));
        data.setScenario(scenario);
        data.setCenter(new Coord(s0.getX(), s0.getY()));
        data.setScale(0.1);

        // 未构建：全接口按未就绪语义
        assertFalse(MatsimPopulationCache.isReady(data));
        assertNull(MatsimPopulationCache.readGridBytes(data));
        assertNull(MatsimPopulationCache.gridBinTag(data));
        assertEquals("generating", MatsimPopulationCache.readPopulationSummary(data).get("status"));
        assertEquals("generating", MatsimPopulationCache.readPopulationStreets(data).get("status"));

        MatsimPopulationCache.prepareOnModelLoad(data);

        assertTrue(MatsimPopulationCache.isReady(data));
        Map<String, Object> summary = MatsimPopulationCache.readPopulationSummary(data);
        assertEquals("ready", summary.get("status"));
        assertEquals(MatsimPopulationCache.POPULATION_CACHE_VERSION, summary.get("cacheVersion"));
        assertEquals(2, ((Number) summary.get("persons")).intValue());
        assertEquals(2, ((Number) summary.get("homePersons")).intValue());
        assertEquals(1, ((Number) summary.get("workPersons")).intValue());
        assertEquals(1.0, (Double) summary.get("scale"), 1e-9);
        assertEquals(List.of("home"), summary.get("homeTypes"));
        assertEquals(List.of("work"), summary.get("workTypes"));

        Map<String, Object> streets = MatsimPopulationCache.readPopulationStreets(data);
        assertEquals(176, ((List<Object>) streets.get("streets")).size());

        byte[] bin = MatsimPopulationCache.readGridBytes(data);
        assertNotNull(bin);
        DecodedGrid grid = decode(bin);
        assertEquals(3, grid.version());
        assertEquals(MatsimPopulationCache.mercCellSize(data.getCenter()), grid.mercCellSize(), 0.0);

        String tag = MatsimPopulationCache.gridBinTag(data);
        assertNotNull(tag);
        assertEquals(16, tag.length());

        // 幂等：二次调用不重建（generatedAt 不变），ETag 稳定
        long generatedAt = ((Number) summary.get("generatedAt")).longValue();
        MatsimPopulationCache.prepareOnModelLoad(data);
        assertEquals(generatedAt,
                ((Number) MatsimPopulationCache.readPopulationSummary(data).get("generatedAt")).longValue());
        assertEquals(tag, MatsimPopulationCache.gridBinTag(data));
        assertArrayEquals(bin, MatsimPopulationCache.readGridBytes(data));
    }

    // ---------------------------------------------------------------- streets.geojson 资源直出

    @Test
    void streetsGeojsonBytesAreStableGzipWithContentTag() throws Exception {
        byte[] first = MatsimPopulationCache.streetsGeojsonGzBytes();
        byte[] second = MatsimPopulationCache.streetsGeojsonGzBytes();
        assertArrayEquals(first, second);
        assertTrue(first.length > 0);
        // gzip magic
        assertEquals(0x1f, first[0] & 0xff);
        assertEquals(0x8b, first[1] & 0xff);
        String tag = MatsimPopulationCache.streetsGeojsonTag();
        assertNotNull(tag);
        assertEquals(16, tag.length());
        // 返回的是拷贝：调用方污染不影响内部单例
        first[0] = 0;
        assertEquals(0x1f, MatsimPopulationCache.streetsGeojsonGzBytes()[0] & 0xff);
    }
}
