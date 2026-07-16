package com.jts.gjcxfzksh.utils;

import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import com.jts.gjcxfzksh.data.id.VehicleId;
import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标口径层单测：覆盖审计报告问题2（覆盖率语义反转）、问题3（候车时间覆盖赋值）、
 * 问题4/5（满载率分子双计）的回归护栏。
 */
class TransitMetricsTest {

    private static Population emptyPopulation() {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        return scenario.getPopulation();
    }

    private static Person personWithActivity(Population population, String id, Coord coord) {
        PopulationFactory factory = population.getFactory();
        Person person = factory.createPerson(Id.createPersonId(id));
        Plan plan = factory.createPlan();
        Activity activity = factory.createActivityFromCoord("home", coord);
        plan.addActivity(activity);
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        population.addPerson(person);
        return person;
    }

    @Test
    void coverageIsCoveredRatioNotInverted() {
        Population population = emptyPopulation();
        // 站点在原点；p1 距站点约 141m（覆盖内），p2/p3 距站点约 1414m（覆盖外）
        personWithActivity(population, "p1", new Coord(100, 100));
        personWithActivity(population, "p2", new Coord(1000, 1000));
        personWithActivity(population, "p3", new Coord(-1000, 1000));

        Double percent = TransitMetrics.coverage300Percent(Set.of(new Coord(0, 0)), population);

        // 3 人中 1 人被覆盖 → 33.33%（旧实现语义反转会得到 66.67%）
        assertEquals(100.0 / 3, percent, 0.01);
    }

    @Test
    void coverageReturnsNullWhenNoData() {
        assertNull(TransitMetrics.coverage300Percent(Set.of(), emptyPopulation()));
        assertNull(TransitMetrics.coverage300Percent(Set.of(new Coord(0, 0)), emptyPopulation()));
        assertNull(TransitMetrics.coverage300Percent(null, null));
    }

    @Test
    void coverageResultMarksNoDataInsteadOfFiftyFifty() {
        Map<String, Object> nodata = TransitMetrics.coverageResult(null);
        assertEquals(Boolean.TRUE, nodata.get("nodata"));
        assertEquals(0.0, nodata.get("cover"));

        Map<String, Object> normal = TransitMetrics.coverageResult(33.333);
        assertEquals(33.33, normal.get("cover"));
        assertEquals(66.67, normal.get("notcover"));
        assertNull(normal.get("nodata"));
    }

    @Test
    void awaitTimeAccumulatesInsteadOfOverwriting() {
        Population population = emptyPopulation();
        addPtTraveller(population, "p1", 7 * 3600, 300, 120); // 候车 120s，候车开始于 7 点
        addPtTraveller(population, "p2", 7 * 3600, 300, 240); // 候车 240s，同一小时

        double[] byHour = TransitMetrics.avgAwaitTimeByHour(population);

        // 均值 = (120+240)/2 = 180；旧实现覆盖赋值会得到 240/2 = 120
        assertEquals(180.0, byHour[7], 0.001);
        assertEquals(0.0, byHour[8], 0.001);
    }

    /**
     * 计划结构: [家, 步行 leg(出发 walkDeparture, 耗时 walkTravel), pt-interaction, pt leg(出发=到站+await), 单位]
     */
    private static void addPtTraveller(Population population, String id,
                                       double walkDeparture, double walkTravel, double await) {
        PopulationFactory factory = population.getFactory();
        Person person = factory.createPerson(Id.createPersonId(id));
        Plan plan = factory.createPlan();
        plan.addActivity(factory.createActivityFromCoord("home", new Coord(0, 0)));
        Leg walk = factory.createLeg("walk");
        walk.setDepartureTime(walkDeparture);
        walk.setTravelTime(walkTravel);
        plan.addLeg(walk);
        plan.addActivity(factory.createActivityFromCoord("pt interaction", new Coord(10, 10)));
        Leg pt = factory.createLeg("pt");
        pt.setDepartureTime(walkDeparture + walkTravel + await);
        plan.addLeg(pt);
        plan.addActivity(factory.createActivityFromCoord("work", new Coord(500, 500)));
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        population.addPerson(person);
    }

    @Test
    void fullLoadRateCountsBoardingsOnlyAndReturnsDecimal() {
        Vehicles vehicles = VehicleUtils.createVehiclesContainer();
        VehicleType type = VehicleUtils.createVehicleType(Id.create("bus-type", VehicleType.class));
        type.getCapacity().setSeats(40);
        type.getCapacity().setStandingRoom(60);
        vehicles.addVehicleType(type);
        vehicles.addVehicle(VehicleUtils.createVehicle(Id.create("v1", Vehicle.class), type));
        Map<Id<Vehicle>, Vehicle> vehicleMap = (Map<Id<Vehicle>, Vehicle>) vehicles.getVehicles();

        VehicleId v1 = VehicleId.create("v1");
        // 3 人上车 + 3 人下车共 6 条记录：分子必须只算 3（旧实现双计成 6）
        Map<VehicleId, List<PTPersonTrack>> tracksByVehicle = new LinkedHashMap<>();
        tracksByVehicle.put(v1, List.of(
                track(v1, true), track(v1, false),
                track(v1, true), track(v1, false),
                track(v1, true), track(v1, false)
        ));

        double rate = TransitMetrics.fullLoadRate(tracksByVehicle, vehicleMap);
        assertEquals(3.0 / 100.0, rate, 1e-9);
    }

    @Test
    void fullLoadRateDeduplicatesVehicleIds() {
        Vehicles vehicles = VehicleUtils.createVehiclesContainer();
        VehicleType type = VehicleUtils.createVehicleType(Id.create("bus-type", VehicleType.class));
        type.getCapacity().setSeats(50);
        type.getCapacity().setStandingRoom(50);
        vehicles.addVehicleType(type);
        vehicles.addVehicle(VehicleUtils.createVehicle(Id.create("v1", Vehicle.class), type));
        Map<Id<Vehicle>, Vehicle> vehicleMap = (Map<Id<Vehicle>, Vehicle>) vehicles.getVehicles();

        VehicleId v1 = VehicleId.create("v1");
        Map<VehicleId, List<PTPersonTrack>> tracksByVehicle = Map.of(v1, List.of(track(v1, true)));

        // 同一车辆出现两次（如多个班次共用车辆）：容量与上车人次都只应计一次
        double rate = TransitMetrics.fullLoadRate(List.of(v1, v1), tracksByVehicle, vehicleMap);
        assertEquals(1.0 / 100.0, rate, 1e-9);
    }

    @Test
    void fullLoadRateExpandsSampledBoardings() {
        Vehicles vehicles = VehicleUtils.createVehiclesContainer();
        VehicleType type = VehicleUtils.createVehicleType(Id.create("bus-type", VehicleType.class));
        type.getCapacity().setSeats(100);
        vehicles.addVehicleType(type);
        vehicles.addVehicle(VehicleUtils.createVehicle(Id.create("v1", Vehicle.class), type));
        Map<Id<Vehicle>, Vehicle> vehicleMap = (Map<Id<Vehicle>, Vehicle>) vehicles.getVehicles();

        VehicleId v1 = VehicleId.create("v1");
        Map<VehicleId, List<PTPersonTrack>> tracksByVehicle = Map.of(v1, List.of(
                track(v1, true), track(v1, true), track(v1, true)
        ));

        assertEquals(30.0 / 100.0,
                TransitMetrics.fullLoadRate(tracksByVehicle, vehicleMap, 0.1), 1e-9);
        assertEquals(3.0 / 100.0,
                TransitMetrics.fullLoadRate(tracksByVehicle, vehicleMap, 10.0), 1e-9,
                "非法百分数写法不应把乘客量意外缩小");
    }

    @Test
    void fullLoadRateIsZeroWithoutVehicles() {
        assertTrue(TransitMetrics.fullLoadRate(new LinkedHashMap<>(), Map.of()) == 0.0);
    }

    private static PTPersonTrack track(VehicleId vehicleId, boolean enter) {
        PTPersonTrack track = new PTPersonTrack();
        track.setVehicleId(vehicleId);
        track.setEnter(enter);
        return track;
    }

    // ===== 高峰/平峰发车间隔（peakOffPeakHeadwayMinutes）：四类真实时刻表形态 + 边界 =====

    /** 生成 [start, end] 闭区间内按固定间隔（分钟）的发车时刻（秒）。 */
    private static java.util.stream.DoubleStream uniformDepartures(double startHour, double endHour, double headwayMin) {
        int count = (int) Math.floor((endHour - startHour) * 60 / headwayMin) + 1;
        return java.util.stream.IntStream.range(0, count)
                .mapToDouble(i -> startHour * 3600 + i * headwayMin * 60);
    }

    @Test
    void headwayUniformAllDayLineHasEqualPeakAndOffPeak() {
        // 番141路形态：07:00-22:00 全天 15 分均一
        double[] times = uniformDepartures(7, 22, 15).toArray();
        double[] result = TransitMetrics.peakOffPeakHeadwayMinutes(times);
        assertEquals(15.0, result[0], 1e-9);
        assertEquals(15.0, result[1], 1e-9);
    }

    @Test
    void headwayDenserPeakIsSeparatedFromOffPeak() {
        // 715路形态：早晚高峰 12 分、其余 15 分。
        // 用整两小时高峰块避免跨窗混段：7-9 时 12 分、9-17 时 15 分、17-19 时 12 分、19-21 时 15 分
        double[] times = java.util.stream.DoubleStream.concat(
                java.util.stream.DoubleStream.concat(
                        uniformDepartures(7, 9, 12),
                        uniformDepartures(9.25, 17, 15)),
                java.util.stream.DoubleStream.concat(
                        uniformDepartures(17.2, 19, 12),
                        uniformDepartures(19.25, 21, 15))
        ).sorted().toArray();
        double[] result = TransitMetrics.peakOffPeakHeadwayMinutes(times);
        assertTrue(result[0] < 13.0 && result[0] >= 12.0, "高峰间隔应≈12分，实际 " + result[0]);
        assertTrue(result[1] > 14.0 && result[1] <= 15.5, "平峰间隔应≈15分，实际 " + result[1]);
    }

    @Test
    void headwayPeakOnlyLineHasNoOffPeakValue() {
        // B6路快线形态：仅早晚高峰运营（7:15-9:00 与 17:00-19:00 各 15 分），午间 8 小时停开断档须剔除
        double[] times = java.util.stream.DoubleStream.concat(
                uniformDepartures(7.25, 9, 15),
                uniformDepartures(17, 19, 15)
        ).toArray();
        double[] result = TransitMetrics.peakOffPeakHeadwayMinutes(times);
        assertEquals(15.0, result[0], 1e-9);
        assertEquals(0.0, result[1], 1e-9, "午间断档不应折算成平峰间隔");
    }

    @Test
    void headwayNightLineExcludesCrossDayGapAndHasNoPeak() {
        // 夜102路形态：22:00-次日1:00 运营，按钟面排序会出现 21h 假间隔，须剔除；高峰窗无班次
        double[] times = {
                10 * 60, 30 * 60, 60 * 60, // 0:10 / 0:30 / 1:00
                22 * 3600, 22 * 3600 + 30 * 60, 23 * 3600, 23 * 3600 + 30 * 60, // 22:00-23:30 每 30 分
        };
        double[] result = TransitMetrics.peakOffPeakHeadwayMinutes(times);
        assertEquals(0.0, result[0], 1e-9, "夜班线高峰窗无班次应输出 0（前端显示暂无）");
        assertEquals(28.0, result[1], 1e-9, "平峰=夜间有效间隔均值 (20+30+30+30+30)/5");
    }

    @Test
    void headwaySparseRuralLineFallsBackWhenAllGapsExceedBreakThreshold() {
        // 郊区长间隔线：班距 180 分本身>2h 断档阈值，应退化为全量计入而非全 0
        double[] times = uniformDepartures(6, 18, 180).toArray();
        double[] result = TransitMetrics.peakOffPeakHeadwayMinutes(times);
        assertEquals(180.0, result[0], 1e-9);
        assertEquals(180.0, result[1], 1e-9);
    }

    @Test
    void headwayHandlesMatsimOverDayTimesAndDuplicates() {
        // MATSim 跨日时刻（>24h）按钟面归窗：24:30/25:00/25:30 的间隔属于凌晨平峰；
        // 同刻重复班次（大站快车双出）不构成间隔
        double[] times = {24.5 * 3600, 24.5 * 3600, 25 * 3600, 25.5 * 3600};
        double[] result = TransitMetrics.peakOffPeakHeadwayMinutes(times);
        assertEquals(0.0, result[0], 1e-9);
        assertEquals(30.0, result[1], 1e-9);
    }

    @Test
    void headwayEmptyOrSingleDepartureYieldsZero() {
        assertEquals(0.0, TransitMetrics.peakOffPeakHeadwayMinutes(new double[0])[0], 1e-9);
        assertEquals(0.0, TransitMetrics.peakOffPeakHeadwayMinutes(new double[0])[1], 1e-9);
        assertEquals(0.0, TransitMetrics.peakOffPeakHeadwayMinutes(new double[]{8 * 3600})[0], 1e-9);
        assertEquals(0.0, TransitMetrics.peakOffPeakHeadwayMinutes(null)[1], 1e-9);
    }
}
