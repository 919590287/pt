package com.jts.gjcxfzksh.utils;

import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import com.jts.gjcxfzksh.data.id.DepartureId;
import com.jts.gjcxfzksh.data.id.LineId;
import com.jts.gjcxfzksh.data.id.RouteId;
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
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitScheduleFactory;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标口径层单测：覆盖审计报告问题2（覆盖率语义反转）、问题3（候车时间覆盖赋值）、
 * 问题4/5（满载率应还原峰值在车人数）的回归护栏。
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
    void residentPopulationCountsOnlyPersonsWithValidHomePosition() {
        Population population = emptyPopulation();
        personWithActivity(population, "resident", new Coord(100, 200));
        personWithActivity(population, "invalid-home", new Coord(Double.NaN, 200));

        PopulationFactory factory = population.getFactory();
        Person worker = factory.createPerson(Id.createPersonId("worker"));
        Plan workOnly = factory.createPlan();
        workOnly.addActivity(factory.createActivityFromCoord("work", new Coord(300, 400)));
        worker.addPlan(workOnly);
        worker.setSelectedPlan(workOnly);
        population.addPerson(worker);

        assertEquals(1L, TransitMetrics.residentHomePersonCount(population));
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

    @Test
    void tripModeShareCanonicalizesBusAliasToPt() {
        Population population = emptyPopulation();
        addSimpleTrip(population, "bus-person", "BUS");
        addSimpleTrip(population, "subway-person", "subway");
        addSimpleTrip(population, "car-person", "car");

        Map<String, Double> shares = TransitMetrics.tripModeSharePercent(population);

        assertEquals(66.67, shares.get("pt"), 1e-9,
                "bus/metro 等模式别名必须归一到公交分担率的 pt 键");
        assertEquals(33.33, shares.get("car"), 1e-9);
    }

    @Test
    void mixedBusRailTripUsesRailAsMainModeIndependentOfLegOrder() {
        Population population = emptyPopulation();
        addMixedTransitTrip(population, "bus-then-rail", "bus", "subway");
        addMixedTransitTrip(population, "rail-then-bus", "subway", "bus");

        TransitMetrics.BusTripShareStats stats = TransitMetrics.busTripShareStats(
                population, TransitMetrics.RoadTransitContext.from(null));

        assertEquals(2, stats.journeys());
        assertEquals(2, stats.transitJourneys());
        assertEquals(0, stats.busJourneys(),
                "公交与轨道混合出行应稳定归为轨道主方式，不能由 leg 顺序决定公交分子");
        assertEquals(0.0, stats.busPercent(), 1e-9);
    }

    @Test
    void publicTransportMotorizedShareExcludesWalkAndBikeFromDenominator() {
        Population population = emptyPopulation();
        addSimpleTrip(population, "bus-person", "bus");
        addSimpleTrip(population, "rail-person", "subway");
        addSimpleTrip(population, "car-person", "car");
        addSimpleTrip(population, "walk-person", "walk");
        addSimpleTrip(population, "bike-person", "bike");

        TransitMetrics.BusTripShareStats stats = TransitMetrics.busTripShareStats(
                population, TransitMetrics.RoadTransitContext.from(null));

        assertEquals(5, stats.journeys());
        assertEquals(3, stats.motorizedJourneys());
        assertEquals(2, stats.transitJourneys());
        assertEquals(66.6667, stats.publicTransportMotorizedPercent(), 0.0001);
        assertEquals(0.25, TransitMetrics.busTripsPerResident(stats, 4L), 1e-9,
                "公交人均日出行次数只能使用道路公交主方式完整出行数/常住人口，必须排除轨道");
    }

    @Test
    void transitAliasesAreUsedForWaitButRailIsExcludedFromRoadBusSpeed() {
        Population population = emptyPopulation();
        addTransitTraveller(population, "bus", "bus", 7 * 3600, 300, 120);
        addTransitTraveller(population, "subway", "subway", 7 * 3600, 300, 240);

        assertEquals(180.0, TransitMetrics.avgAwaitTimeByHour(population)[7], 1e-9);
        assertEquals(3.0, TransitMetrics.averageAwaitMinutes(population), 1e-9);
        assertTrue(TransitMetrics.isRoadPublicTransportMode("BUS"));
        assertTrue(TransitMetrics.isTransitMode("subway"));
        assertTrue(!TransitMetrics.isRoadPublicTransportMode("subway"),
                "公共汽电车速度比不得混入轨道速度");
    }

    @Test
    void roadContextResolvesLegacyPtOnlyFromReliableScheduleMetadata() {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        TransitScheduleFactory factory = scenario.getTransitSchedule().getFactory();

        TransitLine legacyBusLine = factory.createTransitLine(Id.create("legacy-line", TransitLine.class));
        legacyBusLine.getAttributes().putAttribute("transportMode", "bus");
        TransitRoute legacyBus = factory.createTransitRoute(Id.create("legacy-route", TransitRoute.class),
                null, List.of(), "pt");
        legacyBusLine.addRoute(legacyBus);
        scenario.getTransitSchedule().addTransitLine(legacyBusLine);

        TransitLine unknownLine = factory.createTransitLine(Id.create("42", TransitLine.class));
        TransitRoute unknown = factory.createTransitRoute(Id.create("99", TransitRoute.class),
                null, List.of(), "pt");
        unknownLine.addRoute(unknown);
        scenario.getTransitSchedule().addTransitLine(unknownLine);

        TransitLine railLine = factory.createTransitLine(Id.create("metro-line", TransitLine.class));
        TransitRoute rail = factory.createTransitRoute(Id.create("metro-route", TransitRoute.class),
                null, List.of(), "subway");
        railLine.addRoute(rail);
        scenario.getTransitSchedule().addTransitLine(railLine);

        TransitMetrics.RoadTransitContext context =
                TransitMetrics.RoadTransitContext.from(scenario.getTransitSchedule());
        assertTrue(context.isRoadRoute(legacyBusLine, legacyBus));
        assertTrue(!context.isRoadRoute(unknownLine, unknown));
        assertTrue(!context.isRoadRoute(railLine, rail));
        assertEquals(1, context.unresolvedRoutes(), "无制式元数据的 legacy pt 必须显式 unresolved");

        Leg leg = scenario.getPopulation().getFactory().createLeg("pt");
        leg.setRoute(new DefaultTransitPassengerRoute(
                Id.createLinkId("a"), Id.createLinkId("b"),
                Id.create("s1", TransitStopFacility.class), Id.create("s2", TransitStopFacility.class),
                legacyBusLine.getId(), legacyBus.getId()));
        assertTrue(TransitMetrics.isResolvedRoadPublicTransportLeg(leg, context));

        Leg unresolvedLeg = scenario.getPopulation().getFactory().createLeg("pt");
        assertTrue(!TransitMetrics.isResolvedRoadPublicTransportLeg(unresolvedLeg, context),
                "缺 TransitPassengerRoute 的 pt 不得默认按公交");
    }

    @Test
    void roadBusWaitUsesBoardingTimeMinusDepartureAndExcludesSubway() {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        TransitScheduleFactory factory = scenario.getTransitSchedule().getFactory();
        TransitLine busLine = factory.createTransitLine(Id.create("bus-line", TransitLine.class));
        TransitRoute busRoute = factory.createTransitRoute(Id.create("bus-route", TransitRoute.class),
                null, List.of(), "bus");
        busLine.addRoute(busRoute);
        scenario.getTransitSchedule().addTransitLine(busLine);
        TransitLine railLine = factory.createTransitLine(Id.create("rail-line", TransitLine.class));
        TransitRoute railRoute = factory.createTransitRoute(Id.create("rail-route", TransitRoute.class),
                null, List.of(), "subway");
        railLine.addRoute(railRoute);
        scenario.getTransitSchedule().addTransitLine(railLine);

        addRoutedTransitTraveller(scenario.getPopulation(), "bus", "pt", busLine, busRoute, 1_000, 1_120);
        addRoutedTransitTraveller(scenario.getPopulation(), "rail", "pt", railLine, railRoute, 1_000, 1_600);

        assertEquals(2.0, TransitMetrics.averageRoadBusAwaitMinutes(
                scenario.getPopulation(), TransitMetrics.RoadTransitContext.from(scenario.getTransitSchedule())), 1e-9);
    }

    @Test
    void busServiceJourneysUseAllBusOdTripsAsDenominator() {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        TransitScheduleFactory sf = scenario.getTransitSchedule().getFactory();
        TransitLine busLine = sf.createTransitLine(Id.create("bus-line", TransitLine.class));
        TransitRoute busRoute = sf.createTransitRoute(
                Id.create("bus-route", TransitRoute.class), null, List.of(), "bus");
        busLine.addRoute(busRoute);
        scenario.getTransitSchedule().addTransitLine(busLine);
        TransitLine railLine = sf.createTransitLine(Id.create("rail-line", TransitLine.class));
        TransitRoute railRoute = sf.createTransitRoute(
                Id.create("rail-route", TransitRoute.class), null, List.of(), "subway");
        railLine.addRoute(railRoute);
        scenario.getTransitSchedule().addTransitLine(railLine);

        addTransitJourney(scenario.getPopulation(), "direct", busLine, busRoute);
        addTransitJourney(scenario.getPopulation(), "bus-bus",
                busLine, busRoute, busLine, busRoute);
        addTransitJourney(scenario.getPopulation(), "bus-rail",
                busLine, busRoute, railLine, railRoute);

        TransitMetrics.BusServiceJourneyStats stats = TransitMetrics.busServiceJourneyStats(
                scenario.getPopulation(),
                TransitMetrics.RoadTransitContext.from(scenario.getTransitSchedule()));

        assertEquals(3, stats.busJourneys());
        assertEquals(5, stats.transitBoardings());
        assertEquals(2, stats.transfers());
        assertEquals(1, stats.busRailJourneys());
        assertEquals(2.0 / 3.0, stats.averageTransfers(), 1e-9,
                "平均换乘次数分母必须包括直达公交出行");
        assertEquals(100.0 / 3.0, stats.busRailRatioPercent(), 1e-9,
                "接驳比例分母必须是全部含公交乘坐段的 OD 出行");
    }

    @Test
    void peakOperatingSpeedUsesPeakBusDeparturesAndPeakCarLegsOnly() {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var network = scenario.getNetwork();
        var nf = network.getFactory();
        var from = nf.createNode(Id.createNodeId("from"), new Coord(0, 0));
        var to = nf.createNode(Id.createNodeId("to"), new Coord(3_000, 0));
        network.addNode(from);
        network.addNode(to);
        var link = nf.createLink(Id.createLinkId("road"), from, to);
        link.setLength(3_000);
        network.addLink(link);
        TransitScheduleFactory sf = scenario.getTransitSchedule().getFactory();
        TransitStopFacility first = addStop(scenario, sf, "first", from.getCoord());
        TransitStopFacility last = addStop(scenario, sf, "last", to.getCoord());
        TransitLine line = sf.createTransitLine(Id.create("line", TransitLine.class));
        TransitRoute route = sf.createTransitRoute(
                Id.create("route", TransitRoute.class),
                RouteUtils.createLinkNetworkRouteImpl(link.getId(), link.getId()),
                List.of(sf.createTransitRouteStop(first, 0, 0),
                        sf.createTransitRouteStop(last, 600, 600)), "bus");
        route.addDeparture(sf.createDeparture(Id.create("am", org.matsim.pt.transitSchedule.api.Departure.class),
                8 * 3_600));
        route.addDeparture(sf.createDeparture(Id.create("midday", org.matsim.pt.transitSchedule.api.Departure.class),
                12 * 3_600));
        route.addDeparture(sf.createDeparture(Id.create("pm", org.matsim.pt.transitSchedule.api.Departure.class),
                18 * 3_600));
        line.addRoute(route);
        scenario.getTransitSchedule().addTransitLine(line);

        TransitMetrics.PeakOperatingSpeedStats bus = TransitMetrics.roadBusPeakOperatingSpeedStats(
                scenario.getTransitSchedule(), network,
                TransitMetrics.RoadTransitContext.from(scenario.getTransitSchedule()));
        assertEquals(18.0, bus.kmh(), 1e-9);
        assertEquals(2, bus.samples());

        Leg car = scenario.getPopulation().getFactory().createLeg("car");
        car.setDepartureTime(8 * 3_600);
        car.setTravelTime(1_000);
        var carRoute = RouteUtils.createGenericRouteImpl(Id.createLinkId("a"), Id.createLinkId("b"));
        carRoute.setDistance(10_000);
        car.setRoute(carRoute);
        assertEquals(36.0, TransitMetrics.peakCarLegSpeedSample(car).kmh(), 1e-9);
        car.setDepartureTime(12 * 3_600);
        assertNull(TransitMetrics.peakCarLegSpeedSample(car));
    }

    private static void addTransitJourney(Population population, String id, Object... lineRoutePairs) {
        PopulationFactory factory = population.getFactory();
        Person person = factory.createPerson(Id.createPersonId(id));
        Plan plan = factory.createPlan();
        plan.addActivity(factory.createActivityFromCoord("home", new Coord(0, 0)));
        for (int i = 0; i < lineRoutePairs.length; i += 2) {
            TransitLine line = (TransitLine) lineRoutePairs[i];
            TransitRoute route = (TransitRoute) lineRoutePairs[i + 1];
            Leg leg = factory.createLeg("pt");
            leg.setRoute(new DefaultTransitPassengerRoute(
                    Id.createLinkId("a"), Id.createLinkId("b"),
                    Id.create("s1", TransitStopFacility.class),
                    Id.create("s2", TransitStopFacility.class),
                    line.getId(), route.getId()));
            plan.addLeg(leg);
            if (i + 2 < lineRoutePairs.length) {
                plan.addActivity(factory.createActivityFromCoord(
                        "pt interaction", new Coord(100 + i, 0)));
            }
        }
        plan.addActivity(factory.createActivityFromCoord("work", new Coord(1_000, 0)));
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        population.addPerson(person);
    }

    private static void addRoutedTransitTraveller(Population population, String id, String mode,
                                                    TransitLine line, TransitRoute route,
                                                    double departure, double boarding) {
        PopulationFactory factory = population.getFactory();
        Person person = factory.createPerson(Id.createPersonId(id));
        Plan plan = factory.createPlan();
        plan.addActivity(factory.createActivityFromCoord("home", new Coord(0, 0)));
        Leg leg = factory.createLeg(mode);
        leg.setDepartureTime(departure);
        leg.setTravelTime(900);
        DefaultTransitPassengerRoute passengerRoute = new DefaultTransitPassengerRoute(
                Id.createLinkId("a"), Id.createLinkId("b"),
                Id.create("s1", TransitStopFacility.class), Id.create("s2", TransitStopFacility.class),
                line.getId(), route.getId());
        passengerRoute.setBoardingTime(boarding);
        passengerRoute.setDistance(5_000);
        leg.setRoute(passengerRoute);
        plan.addLeg(leg);
        plan.addActivity(factory.createActivityFromCoord("work", new Coord(1000, 0)));
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        population.addPerson(person);
    }

    private static void addSimpleTrip(Population population, String id, String mode) {
        PopulationFactory factory = population.getFactory();
        Person person = factory.createPerson(Id.createPersonId(id));
        Plan plan = factory.createPlan();
        plan.addActivity(factory.createActivityFromCoord("home", new Coord(0, 0)));
        plan.addLeg(factory.createLeg(mode));
        plan.addActivity(factory.createActivityFromCoord("work", new Coord(1000, 0)));
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        population.addPerson(person);
    }

    private static void addMixedTransitTrip(Population population, String id, String firstMode, String secondMode) {
        PopulationFactory factory = population.getFactory();
        Person person = factory.createPerson(Id.createPersonId(id));
        Plan plan = factory.createPlan();
        plan.addActivity(factory.createActivityFromCoord("home", new Coord(0, 0)));
        plan.addLeg(factory.createLeg(firstMode));
        plan.addActivity(factory.createActivityFromCoord("pt interaction", new Coord(500, 0)));
        plan.addLeg(factory.createLeg(secondMode));
        plan.addActivity(factory.createActivityFromCoord("work", new Coord(1000, 0)));
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        population.addPerson(person);
    }

    /**
     * 计划结构: [家, 步行 leg(出发 walkDeparture, 耗时 walkTravel), pt-interaction, pt leg(出发=到站+await), 单位]
     */
    private static void addPtTraveller(Population population, String id,
                                       double walkDeparture, double walkTravel, double await) {
        addTransitTraveller(population, id, "pt", walkDeparture, walkTravel, await);
    }

    private static void addTransitTraveller(Population population, String id, String mode,
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
        Leg pt = factory.createLeg(mode);
        double arrivalAtStop = walkDeparture + walkTravel;
        pt.setDepartureTime(arrivalAtStop);
        DefaultTransitPassengerRoute passengerRoute = new DefaultTransitPassengerRoute(
                Id.createLinkId("a"), Id.createLinkId("b"),
                Id.create("s1", TransitStopFacility.class),
                Id.create("s2", TransitStopFacility.class),
                Id.create("line-" + id, TransitLine.class),
                Id.create("route-" + id, TransitRoute.class));
        passengerRoute.setBoardingTime(arrivalAtStop + await);
        pt.setRoute(passengerRoute);
        plan.addLeg(pt);
        plan.addActivity(factory.createActivityFromCoord("work", new Coord(500, 500)));
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        population.addPerson(person);
    }

    @Test
    void fullLoadRateUsesPeakOnboardInsteadOfDailyBoardings() {
        Vehicles vehicles = VehicleUtils.createVehiclesContainer();
        VehicleType type = VehicleUtils.createVehicleType(Id.create("bus-type", VehicleType.class));
        type.getCapacity().setSeats(40);
        type.getCapacity().setStandingRoom(60);
        vehicles.addVehicleType(type);
        vehicles.addVehicle(VehicleUtils.createVehicle(Id.create("v1", Vehicle.class), type));
        Map<Id<Vehicle>, Vehicle> vehicleMap = (Map<Id<Vehicle>, Vehicle>) vehicles.getVehicles();

        VehicleId v1 = VehicleId.create("v1");
        // 全天有 3 次上车，但峰值在车只有 2 人；不能再用日累计上车人次作分子。
        Map<VehicleId, List<PTPersonTrack>> tracksByVehicle = new LinkedHashMap<>();
        tracksByVehicle.put(v1, List.of(
                track(v1, true, 10), track(v1, true, 20),
                track(v1, false, 30), track(v1, true, 40),
                track(v1, false, 50), track(v1, false, 60)
        ));

        double rate = TransitMetrics.fullLoadRate(tracksByVehicle, vehicleMap);
        assertEquals(2.0 / 100.0, rate, 1e-9);
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
    void fullLoadRateIgnoresLegacySampleMetadata() {
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

        assertEquals(3.0 / 100.0,
                TransitMetrics.fullLoadRate(tracksByVehicle, vehicleMap, 0.1), 1e-9);
        assertEquals(3.0 / 100.0,
                TransitMetrics.fullLoadRate(tracksByVehicle, vehicleMap, 10.0), 1e-9,
                "scale 不论写法如何都不应改变模型原始乘客量");
    }

    @Test
    void fullLoadRateMergesSameTimestampBoardAndAlight() {
        Vehicles vehicles = VehicleUtils.createVehiclesContainer();
        VehicleType type = VehicleUtils.createVehicleType(Id.create("bus-type", VehicleType.class));
        type.getCapacity().setSeats(10);
        vehicles.addVehicleType(type);
        vehicles.addVehicle(VehicleUtils.createVehicle(Id.create("v1", Vehicle.class), type));

        VehicleId v1 = VehicleId.create("v1");
        Map<VehicleId, List<PTPersonTrack>> tracks = Map.of(v1, List.of(
                track(v1, true, 10),
                track(v1, true, 20), track(v1, false, 20),
                track(v1, false, 30)
        ));

        assertEquals(1.0 / 10.0,
                TransitMetrics.fullLoadRate(tracks, (Map<Id<Vehicle>, Vehicle>) vehicles.getVehicles()), 1e-9,
                "同一站同一时刻的上下车应按净变化结算，不得制造瞬时虚假峰值");
    }

    @Test
    void fullLoadRateTakesHighestVehiclePeakInsteadOfSumOrAverage() {
        Vehicles vehicles = VehicleUtils.createVehiclesContainer();
        VehicleType tenSeats = VehicleUtils.createVehicleType(Id.create("ten-seats", VehicleType.class));
        tenSeats.getCapacity().setSeats(10);
        VehicleType fourSeats = VehicleUtils.createVehicleType(Id.create("four-seats", VehicleType.class));
        fourSeats.getCapacity().setSeats(4);
        vehicles.addVehicleType(tenSeats);
        vehicles.addVehicleType(fourSeats);
        vehicles.addVehicle(VehicleUtils.createVehicle(Id.create("v1", Vehicle.class), tenSeats));
        vehicles.addVehicle(VehicleUtils.createVehicle(Id.create("v2", Vehicle.class), fourSeats));

        VehicleId v1 = VehicleId.create("v1");
        VehicleId v2 = VehicleId.create("v2");
        Map<VehicleId, List<PTPersonTrack>> tracks = Map.of(
                v1, List.of(track(v1, true, 10), track(v1, true, 20)), // 2/10 = 20%
                v2, List.of(track(v2, true, 10))                       // 1/4 = 25%
        );

        assertEquals(0.25,
                TransitMetrics.fullLoadRate(tracks, (Map<Id<Vehicle>, Vehicle>) vehicles.getVehicles()), 1e-9,
                "高峰满载率应取指定车辆集合中最高单车峰值，不对车辆求和或平均");
    }

    @Test
    void fullLoadRateIsZeroWithoutVehicles() {
        assertTrue(TransitMetrics.fullLoadRate(new LinkedHashMap<>(), Map.of()) == 0.0);
    }

    @Test
    void officialPeakAverageUsesEachPeakDepartureMaximumSegmentRateAndIncludesEmptyTrips() {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var network = scenario.getNetwork();
        var networkFactory = network.getFactory();
        var from = networkFactory.createNode(Id.createNodeId("from"), new Coord(0, 0));
        var to = networkFactory.createNode(Id.createNodeId("to"), new Coord(10_000, 0));
        network.addNode(from);
        network.addNode(to);
        var link = networkFactory.createLink(Id.createLinkId("bus-link"), from, to);
        link.setLength(10_000);
        network.addLink(link);

        TransitScheduleFactory factory = scenario.getTransitSchedule().getFactory();
        TransitLine line = factory.createTransitLine(Id.create("line", TransitLine.class));
        TransitRoute route = factory.createTransitRoute(
                Id.create("route", TransitRoute.class),
                RouteUtils.createLinkNetworkRouteImpl(link.getId(), link.getId()),
                List.of(), "bus");

        VehicleType ten = VehicleUtils.createVehicleType(Id.create("ten", VehicleType.class));
        ten.getCapacity().setSeats(10);
        VehicleType twenty = VehicleUtils.createVehicleType(Id.create("twenty", VehicleType.class));
        twenty.getCapacity().setSeats(20);
        scenario.getTransitVehicles().addVehicleType(ten);
        scenario.getTransitVehicles().addVehicleType(twenty);
        scenario.getTransitVehicles().addVehicle(
                VehicleUtils.createVehicle(Id.create("v10", Vehicle.class), ten));
        scenario.getTransitVehicles().addVehicle(
                VehicleUtils.createVehicle(Id.create("v20", Vehicle.class), twenty));

        addDeparture(factory, route, "morning", 8 * 3600, "v10");
        addDeparture(factory, route, "evening", 17 * 3600, "v20");
        addDeparture(factory, route, "empty-evening", 18 * 3600, "v20");
        addDeparture(factory, route, "off-peak", 12 * 3600, "v20");
        line.addRoute(route);
        scenario.getTransitSchedule().addTransitLine(line);

        TransitMetrics.PeakAverageLoadAccumulator accumulator =
                TransitMetrics.PeakAverageLoadAccumulator.roadBus(
                        scenario.getTransitSchedule(), scenario.getTransitVehicles(),
                        TransitMetrics.RoadTransitContext.from(scenario.getTransitSchedule()), true);
        // morning 班次最大站段 5/10=50%；evening 班次 4/20=20%；
        // empty-evening 为 0%；off-peak 即使有事件也不得进入高峰班次均值。
        for (int i = 0; i < 5; i++) accumulator.accept(departureTrack("morning", 8 * 3600 + i));
        for (int i = 0; i < 4; i++) accumulator.accept(departureTrack("evening", 17 * 3600 + i));
        for (int i = 0; i < 20; i++) accumulator.accept(departureTrack("off-peak", 12 * 3600 + i));

        TransitMetrics.PeakAverageLoadStats stats = accumulator.finish();

        assertEquals((50.0 + 20.0 + 0.0) / 3.0, stats.percent(), 1e-9);
        assertEquals(3, stats.scheduledPeakDepartures());
        assertEquals(3, stats.validCapacityDepartures());
        assertEquals(0, stats.missingCapacityDepartures());

        TransitMetrics.PeakAverageLoadAccumulator orderedAccumulator =
                TransitMetrics.PeakAverageLoadAccumulator.roadBus(
                        scenario.getTransitSchedule(), scenario.getTransitVehicles(),
                        TransitMetrics.RoadTransitContext.from(scenario.getTransitSchedule()), false);
        orderedAccumulator.accept(departureTrack("morning", true, 8 * 3600));
        orderedAccumulator.accept(departureTrack("morning", false, 8 * 3600));
        assertEquals(0.0, orderedAccumulator.finish().percent(), 1e-9,
                "同站同秒上下车必须先按净变化结算，不能因事件排列制造虚假站段峰值");
    }

    @Test
    void passengerStrengthUsesScheduledVehicleKilometers() {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var network = scenario.getNetwork();
        var networkFactory = network.getFactory();
        var from = networkFactory.createNode(Id.createNodeId("from"), new Coord(0, 0));
        var to = networkFactory.createNode(Id.createNodeId("to"), new Coord(10_000, 0));
        network.addNode(from);
        network.addNode(to);
        var link = networkFactory.createLink(Id.createLinkId("bus-link"), from, to);
        link.setLength(10_000);
        network.addLink(link);

        TransitScheduleFactory factory = scenario.getTransitSchedule().getFactory();
        TransitLine line = factory.createTransitLine(Id.create("line", TransitLine.class));
        TransitRoute route = factory.createTransitRoute(
                Id.create("route", TransitRoute.class),
                RouteUtils.createLinkNetworkRouteImpl(link.getId(), link.getId()),
                List.of(), "bus");
        for (int i = 0; i < 4; i++) {
            addDeparture(factory, route, "d" + i, (8 + i) * 3600.0, null);
        }
        line.addRoute(route);
        scenario.getTransitSchedule().addTransitLine(line);

        TransitMetrics.RoadOperatingDistanceStats distance =
                TransitMetrics.roadOperatingDistanceStats(
                        scenario.getTransitSchedule(), network,
                        TransitMetrics.RoadTransitContext.from(scenario.getTransitSchedule()));

        assertEquals(40.0, distance.vehicleKilometers(), 1e-9,
                "10km 路径×4个计划班次应为40运营车公里");
        assertEquals(2.0, TransitMetrics.busPassengerStrength(80, distance), 1e-9,
                "线路客流强度应为80人次/40车公里，不得除以静态线路长度");
    }

    private static void addDeparture(
            TransitScheduleFactory factory, TransitRoute route,
            String departureId, double time, String vehicleId) {
        var departure = factory.createDeparture(
                Id.create(departureId, org.matsim.pt.transitSchedule.api.Departure.class), time);
        if (vehicleId != null) departure.setVehicleId(Id.create(vehicleId, Vehicle.class));
        route.addDeparture(departure);
    }

    private static PTPersonTrack departureTrack(String departureId, double time) {
        return departureTrack(departureId, true, time);
    }

    private static PTPersonTrack departureTrack(String departureId, boolean enter, double time) {
        PTPersonTrack track = new PTPersonTrack();
        track.setLineId(LineId.create("line"));
        track.setRouteId(RouteId.create("route"));
        track.setDepartureId(DepartureId.create(departureId));
        track.setEnter(enter);
        track.setTime(time);
        return track;
    }

    @Test
    void networkLengthUsesPhysicalRoadsAndExcludesRailForBusDensity() {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var network = scenario.getNetwork();
        var factory = network.getFactory();
        var a = factory.createNode(Id.createNodeId("a"), new Coord(0, 0));
        var b = factory.createNode(Id.createNodeId("b"), new Coord(100, 0));
        var c = factory.createNode(Id.createNodeId("c"), new Coord(300, 0));
        network.addNode(a);
        network.addNode(b);
        network.addNode(c);
        var ab = factory.createLink(Id.createLinkId("ab"), a, b);
        var ba = factory.createLink(Id.createLinkId("ba"), b, a);
        var bc = factory.createLink(Id.createLinkId("bc"), b, c);
        ab.setLength(100);
        ba.setLength(100);
        bc.setLength(200);
        network.addLink(ab);
        network.addLink(ba);
        network.addLink(bc);

        TransitScheduleFactory scheduleFactory = scenario.getTransitSchedule().getFactory();
        TransitLine busLine = scheduleFactory.createTransitLine(Id.create("bus-line", TransitLine.class));
        busLine.addRoute(scheduleFactory.createTransitRoute(
                Id.create("out", TransitRoute.class),
                RouteUtils.createLinkNetworkRouteImpl(ab.getId(), ab.getId()), List.of(), "bus"));
        busLine.addRoute(scheduleFactory.createTransitRoute(
                Id.create("back", TransitRoute.class),
                RouteUtils.createLinkNetworkRouteImpl(ba.getId(), ba.getId()), List.of(), "bus"));
        scenario.getTransitSchedule().addTransitLine(busLine);
        TransitLine railLine = scheduleFactory.createTransitLine(Id.create("rail-line", TransitLine.class));
        railLine.addRoute(scheduleFactory.createTransitRoute(
                Id.create("metro", TransitRoute.class),
                RouteUtils.createLinkNetworkRouteImpl(bc.getId(), bc.getId()), List.of(), "metro"));
        scenario.getTransitSchedule().addTransitLine(railLine);

        assertEquals(100.0,
                TransitMetrics.networkLengthMeters(scenario.getTransitSchedule(), network, true), 1e-9,
                "公交线网密度分子应对双向 link 去重且排除轨道");
        assertEquals(300.0,
                TransitMetrics.networkLengthMeters(scenario.getTransitSchedule(), network, false), 1e-9);
        assertEquals(0.05,
                TransitMetrics.busNetworkDensityKmPerKm2(100.0, 2.0), 1e-9,
                "100米去重公交道路中心线/2平方公里行政区总面积=0.05 km/km²");
        assertNull(TransitMetrics.busNetworkDensityKmPerKm2(100.0, 0.0));
    }

    @Test
    void metricCoordinateContextTransformsWgs84ExactlyOnce() {
        TransitMetrics.MetricCoordinateContext coordinates =
                TransitMetrics.MetricCoordinateContext.fromCrs("EPSG:4326");
        assertTrue(coordinates.isSupported());
        double distance = coordinates.groundDistance(
                new Coord(113.2644, 23.1291), new Coord(113.2673, 23.1291));
        assertEquals(296.7, distance, 4.0,
                "WGS84 应先投影到3857再做地面尺度校正，不能把经纬度或已投影坐标二次转换");
        assertThrows(IllegalArgumentException.class,
                () -> TransitMetrics.MetricCoordinateContext.fromCrs("unknown-crs"));
    }

    @Test
    void roadFleetIsNoDataWhenAnyBusRouteLacksDuration() {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        TransitScheduleFactory factory = scenario.getTransitSchedule().getFactory();
        TransitStopFacility stop = factory.createTransitStopFacility(
                Id.create("stop", TransitStopFacility.class), new Coord(0, 0), false);
        scenario.getTransitSchedule().addStopFacility(stop);
        TransitLine line = factory.createTransitLine(Id.create("bus-line", TransitLine.class));
        TransitRoute route = factory.createTransitRoute(Id.create("route", TransitRoute.class), null,
                List.of(factory.createTransitRouteStop(stop, 0, 0)), "bus");
        route.addDeparture(factory.createDeparture(
                Id.create("departure", org.matsim.pt.transitSchedule.api.Departure.class), 8 * 3600));
        line.addRoute(route);
        scenario.getTransitSchedule().addTransitLine(line);

        TransitMetrics.RoadFleetStats stats = TransitMetrics.roadFleetStats(scenario.getTransitSchedule());
        assertNull(stats.peakVehicles());
        assertEquals(1, stats.missingDurationRoutes());
    }

    @Test
    void roadFleetInventoryDeduplicatesVehiclesAndConvertsOfficialStandardUnits() {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        TransitScheduleFactory factory = scenario.getTransitSchedule().getFactory();
        TransitLine line = factory.createTransitLine(Id.create("bus-line", TransitLine.class));
        TransitRoute route = factory.createTransitRoute(Id.create("route", TransitRoute.class),
                null, List.of(), "bus");
        var first = factory.createDeparture(
                Id.create("d1", org.matsim.pt.transitSchedule.api.Departure.class), 0);
        first.setVehicleId(Id.create("v1", Vehicle.class));
        var second = factory.createDeparture(
                Id.create("d2", org.matsim.pt.transitSchedule.api.Departure.class), 3600);
        second.setVehicleId(Id.create("v1", Vehicle.class));
        var third = factory.createDeparture(
                Id.create("d3", org.matsim.pt.transitSchedule.api.Departure.class), 7200);
        third.setVehicleId(Id.create("v2", Vehicle.class));
        route.addDeparture(first);
        route.addDeparture(second);
        route.addDeparture(third);
        line.addRoute(route);
        scenario.getTransitSchedule().addTransitLine(line);

        VehicleType shortBus = VehicleUtils.createVehicleType(Id.create("short", VehicleType.class));
        shortBus.setLength(9.0);
        VehicleType longBus = VehicleUtils.createVehicleType(Id.create("long", VehicleType.class));
        longBus.setLength(12.0);
        scenario.getTransitVehicles().addVehicleType(shortBus);
        scenario.getTransitVehicles().addVehicleType(longBus);
        scenario.getTransitVehicles().addVehicle(
                VehicleUtils.createVehicle(Id.create("v1", Vehicle.class), shortBus));
        scenario.getTransitVehicles().addVehicle(
                VehicleUtils.createVehicle(Id.create("v2", Vehicle.class), longBus));

        TransitMetrics.RoadFleetInventoryStats stats = TransitMetrics.roadFleetInventory(
                scenario.getTransitSchedule(), scenario.getTransitVehicles());

        assertEquals(2L, stats.operatingVehicles());
        assertEquals(2.3, stats.standardVehicles(), 1e-9);
        assertTrue(stats.hasOfficialStandardVehicles());
    }

    @Test
    void operatingEfficiencyUsesSameVehicleAndDepartureDenominators() {
        TransitMetrics.BusOperatingEfficiency efficiency =
                TransitMetrics.busOperatingEfficiency(240, 2, 12);

        assertEquals(120.0, efficiency.perVehicleDaily(), 1e-9);
        assertEquals(20.0, efficiency.perDeparture(), 1e-9);
    }

    @Test
    void repetitionCoefficientUsesLineDirectionAverageAndDeduplicatesDirectionVariants() {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var network = scenario.getNetwork();
        var nf = network.getFactory();
        var a = nf.createNode(Id.createNodeId("a"), new Coord(0, 0));
        var b = nf.createNode(Id.createNodeId("b"), new Coord(100, 0));
        var c = nf.createNode(Id.createNodeId("c"), new Coord(1_000, 0));
        var d = nf.createNode(Id.createNodeId("d"), new Coord(1_050, 0));
        network.addNode(a);
        network.addNode(b);
        network.addNode(c);
        network.addNode(d);
        var outboundLink = nf.createLink(Id.createLinkId("outbound"), a, b);
        var inboundLink = nf.createLink(Id.createLinkId("inbound"), b, a);
        var singleDirectionLink = nf.createLink(Id.createLinkId("single"), c, d);
        outboundLink.setLength(100);
        inboundLink.setLength(200);
        singleDirectionLink.setLength(50);
        network.addLink(outboundLink);
        network.addLink(inboundLink);
        network.addLink(singleDirectionLink);

        TransitScheduleFactory sf = scenario.getTransitSchedule().getFactory();
        TransitStopFacility stopA = addStop(scenario, sf, "stop-a", a.getCoord());
        TransitStopFacility stopB = addStop(scenario, sf, "stop-b", b.getCoord());
        TransitStopFacility stopC = addStop(scenario, sf, "stop-c", c.getCoord());
        TransitStopFacility stopD = addStop(scenario, sf, "stop-d", d.getCoord());

        TransitLine bidirectional = sf.createTransitLine(Id.create("bidirectional", TransitLine.class));
        TransitRoute outboundAm = route(sf, "outbound-am", outboundLink.getId(), stopA, stopB);
        TransitRoute outboundPm = route(sf, "outbound-pm", outboundLink.getId(), stopA, stopB);
        // 即使分时 profile 的方向属性使用不同字段/值，完全相同的有向几何仍只能计一个方向。
        outboundAm.getAttributes().putAttribute("direction_id", "0");
        outboundPm.getAttributes().putAttribute("direction", "outbound");
        bidirectional.addRoute(outboundAm);
        bidirectional.addRoute(outboundPm);
        bidirectional.addRoute(route(sf, "inbound", inboundLink.getId(), stopB, stopA));
        scenario.getTransitSchedule().addTransitLine(bidirectional);

        TransitLine singleDirection = sf.createTransitLine(Id.create("single-direction", TransitLine.class));
        singleDirection.addRoute(route(
                sf, "single", singleDirectionLink.getId(), stopC, stopD));
        scenario.getTransitSchedule().addTransitLine(singleDirection);

        TransitMetrics.RouteShapeStats shape = TransitMetrics.roadRouteShapeStats(
                scenario.getTransitSchedule(), network,
                TransitMetrics.RoadTransitContext.from(scenario.getTransitSchedule()),
                TransitMetrics.MetricCoordinateContext.webMercator());

        // 双向线路长度=(100+200)/2=150；单向线路长度=50；物理线网=max(100,200)+50=250。
        assertEquals(0.8, shape.repetitionCoefficient(), 1e-9);
        // totalRouteLengthMeters 仍服务于客流强度：两个同向 profile 的 route-km 均保留。
        assertEquals(450.0, shape.totalRouteLengthMeters(), 1e-9);
        assertEquals(0, shape.missingGeometryRoutes());
    }

    @Test
    void routeShapeExcludesNearLoopAndRejectsMissingLinks() {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var network = scenario.getNetwork();
        var nf = network.getFactory();
        var n1 = nf.createNode(Id.createNodeId("n1"), new Coord(0, 0));
        var n2 = nf.createNode(Id.createNodeId("n2"), new Coord(1, 0));
        var n3 = nf.createNode(Id.createNodeId("n3"), new Coord(2, 0));
        network.addNode(n1);
        network.addNode(n2);
        network.addNode(n3);
        var firstLink = nf.createLink(Id.createLinkId("first"), n1, n2);
        var lastLink = nf.createLink(Id.createLinkId("last"), n2, n3);
        firstLink.setLength(3_000);
        lastLink.setLength(3_000);
        network.addLink(firstLink);
        network.addLink(lastLink);

        TransitScheduleFactory sf = scenario.getTransitSchedule().getFactory();
        TransitStopFacility firstStop = sf.createTransitStopFacility(
                Id.create("first-stop", TransitStopFacility.class), new Coord(0, 0), false);
        TransitStopFacility lastStop = sf.createTransitStopFacility(
                Id.create("last-stop", TransitStopFacility.class), new Coord(50, 0), false);
        scenario.getTransitSchedule().addStopFacility(firstStop);
        scenario.getTransitSchedule().addStopFacility(lastStop);
        TransitLine line = sf.createTransitLine(Id.create("bus-line", TransitLine.class));
        TransitRoute nearLoop = sf.createTransitRoute(Id.create("near-loop", TransitRoute.class),
                RouteUtils.createLinkNetworkRouteImpl(firstLink.getId(), lastLink.getId()),
                List.of(sf.createTransitRouteStop(firstStop, 0, 0),
                        sf.createTransitRouteStop(lastStop, 600, 600)), "bus");
        line.addRoute(nearLoop);
        scenario.getTransitSchedule().addTransitLine(line);

        TransitMetrics.RoadTransitContext road =
                TransitMetrics.RoadTransitContext.from(scenario.getTransitSchedule());
        TransitMetrics.RouteShapeStats shape = TransitMetrics.roadRouteShapeStats(
                scenario.getTransitSchedule(), network, road,
                TransitMetrics.MetricCoordinateContext.webMercator());
        assertNull(shape.averageNonLinearCoefficient());
        assertEquals(1, shape.excludedLoopRoutes());
        assertEquals(0, shape.missingGeometryRoutes());
        assertEquals(1.0, shape.repetitionCoefficient(), 1e-9);

        TransitRoute broken = sf.createTransitRoute(Id.create("broken", TransitRoute.class),
                RouteUtils.createLinkNetworkRouteImpl(firstLink.getId(), Id.createLinkId("missing")),
                List.of(sf.createTransitRouteStop(firstStop, 0, 0),
                        sf.createTransitRouteStop(lastStop, 600, 600)), "bus");
        line.addRoute(broken);
        TransitMetrics.RoadNetworkStats strict = TransitMetrics.roadNetworkStats(
                scenario.getTransitSchedule(), network,
                TransitMetrics.RoadTransitContext.from(scenario.getTransitSchedule()));
        assertNull(strict.lengthMeters(), "任一公交 route/link 缺失时不得返回剩余线路的部分里程");
        assertEquals(1, strict.missingGeometryRoutes());
    }

    @Test
    void routeShapeAveragesLinesInsteadOfTransitRouteProfiles() {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var network = scenario.getNetwork();
        var nf = network.getFactory();
        var a = nf.createNode(Id.createNodeId("a"), new Coord(0, 0));
        var b = nf.createNode(Id.createNodeId("b"), new Coord(1_000, 0));
        var c = nf.createNode(Id.createNodeId("c"), new Coord(0, 2_000));
        var d = nf.createNode(Id.createNodeId("d"), new Coord(1_000, 2_000));
        network.addNode(a);
        network.addNode(b);
        network.addNode(c);
        network.addNode(d);
        var detour = nf.createLink(Id.createLinkId("detour"), a, b);
        detour.setLength(2_000);
        network.addLink(detour);
        var direct = nf.createLink(Id.createLinkId("direct"), c, d);
        direct.setLength(1_000);
        network.addLink(direct);

        TransitScheduleFactory sf = scenario.getTransitSchedule().getFactory();
        TransitStopFacility aStop = addStop(scenario, sf, "a-stop", a.getCoord());
        TransitStopFacility bStop = addStop(scenario, sf, "b-stop", b.getCoord());
        TransitStopFacility cStop = addStop(scenario, sf, "c-stop", c.getCoord());
        TransitStopFacility dStop = addStop(scenario, sf, "d-stop", d.getCoord());
        TransitLine profileHeavy = sf.createTransitLine(Id.create("profile-heavy", TransitLine.class));
        for (int i = 0; i < 10; i++) {
            profileHeavy.addRoute(route(sf, "profile-" + i, detour.getId(), aStop, bStop));
        }
        scenario.getTransitSchedule().addTransitLine(profileHeavy);
        TransitLine directLine = sf.createTransitLine(Id.create("direct-line", TransitLine.class));
        directLine.addRoute(route(sf, "direct-route", direct.getId(), cStop, dStop));
        scenario.getTransitSchedule().addTransitLine(directLine);

        TransitMetrics.RouteShapeStats shape = TransitMetrics.roadRouteShapeStats(
                scenario.getTransitSchedule(), network,
                TransitMetrics.RoadTransitContext.from(scenario.getTransitSchedule()),
                TransitMetrics.MetricCoordinateContext.webMercator());

        assertEquals(1.5, shape.averageNonLinearCoefficient(), 1e-6);
        assertEquals(2, shape.validRoutes(), "兼容字段 validRoutes 现在表示有效公交线路数");
        assertEquals(2.0, shape.maxNonLinearCoefficient(), 1e-6);
        assertEquals(0, shape.abnormalNonLinearLines());
    }

    private static TransitStopFacility addStop(
            Scenario scenario, TransitScheduleFactory factory, String id, Coord coord) {
        TransitStopFacility stop = factory.createTransitStopFacility(
                Id.create(id, TransitStopFacility.class), coord, false);
        scenario.getTransitSchedule().addStopFacility(stop);
        return stop;
    }

    private static TransitRoute route(
            TransitScheduleFactory factory, String id,
            Id<org.matsim.api.core.v01.network.Link> linkId,
            TransitStopFacility first, TransitStopFacility last) {
        return factory.createTransitRoute(
                Id.create(id, TransitRoute.class),
                RouteUtils.createLinkNetworkRouteImpl(linkId, linkId),
                List.of(
                        factory.createTransitRouteStop(first, 0, 0),
                        factory.createTransitRouteStop(last, 60, 60)),
                "bus");
    }

    private static PTPersonTrack track(VehicleId vehicleId, boolean enter) {
        return track(vehicleId, enter, 0);
    }

    private static PTPersonTrack track(VehicleId vehicleId, boolean enter, double time) {
        PTPersonTrack track = new PTPersonTrack();
        track.setVehicleId(vehicleId);
        track.setEnter(enter);
        track.setTime(time);
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
