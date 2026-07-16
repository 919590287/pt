package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.data.MatsimData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatsimPlansDerivedCacheTest {

    @Test
    void coordinateStreetCacheReusesExactCoordinatesWithoutChangingResults() {
        AtomicInteger delegateCalls = new AtomicInteger();
        MatsimPopulationCache.StreetLocator delegate = (x, y) -> {
            delegateCalls.incrementAndGet();
            return x < 0 ? -1 : (int) Math.floor(y);
        };
        MatsimPopulationCache.CoordinateStreetCache cache =
                new MatsimPopulationCache.CoordinateStreetCache(delegate, 32);

        for (int i = 0; i < 1_000; i++) {
            assertEquals(23, cache.locate(113.3, 23.9));
            assertEquals(-1, cache.locate(-1.0, 0.0));
        }

        assertEquals(2, delegateCalls.get(), "两个精确坐标应各调用一次实际点面判定");
        assertEquals(1_998, cache.hits());
        assertEquals(2, cache.misses());
    }

    @Test
    void parallelSharedScanMatchesSerialAggregationsAndUsesRequestedWorkers(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("output");
        Path cache = tempDir.resolve("cache");
        Files.createDirectories(output);
        Files.createDirectories(cache);
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());

        MatsimPopulationCache.StreetIndex streets = MatsimPopulationCache.streetIndex();
        Coord repeated = streets.geometry(0).getInteriorPoint().getCoordinate() == null
                ? new Coord(0, 0)
                : new Coord(streets.geometry(0).getInteriorPoint().getX(),
                streets.geometry(0).getInteriorPoint().getY());
        Coord repeatedWork = new Coord(repeated.getX() + 1.0, repeated.getY() + 1.0);

        MutableScenario scenario = (MutableScenario) ScenarioUtils.createScenario(ConfigUtils.createConfig());
        for (int i = 0; i < 5_000; i++) {
            addPtPerson(scenario.getPopulation(), "p" + i, repeated, repeatedWork);
        }
        MatsimData data = new MatsimData("plans-shared-unit", output.toString(), cache.toString(), false);
        data.setScenario(scenario);
        data.setCenter(repeated);

        double cellSize = MatsimPopulationCache.mercCellSize(repeated);
        MatsimPopulationCache.Aggregation serialPopulation =
                new MatsimPopulationCache.Aggregation(cellSize, streets);
        MatsimTripEndsCache.Aggregation serialTripEnds =
                new MatsimTripEndsCache.Aggregation(cellSize, streets, Map.of());
        for (Person person : scenario.getPopulation().getPersons().values()) {
            serialPopulation.acceptPerson(person, null);
            serialTripEnds.acceptPerson(person, null);
        }

        MatsimPlansDerivedCache.ScanResult parallel = MatsimPlansDerivedCache.scan(
                data, streets, true, true, 4, 4_096);

        assertEquals(4, parallel.stats().workers());
        assertEquals(5_000, parallel.stats().persons());
        assertTrue(parallel.stats().streetCacheHits() > 19_000,
                "population + tripends 共享重复活动坐标时应有大量缓存命中");
        assertPopulationEquals(serialPopulation, parallel.population(), streets);
        assertTripEndsEquals(serialTripEnds, parallel.tripEnds(), streets);

        // 生产编排入口：同一次 prepare 后两类 manifest/工件同时 ready。
        MatsimPlansDerivedCache.prepareAllOnModelLoad(data);
        assertTrue(MatsimPopulationCache.isReady(data));
        assertTrue(MatsimTripEndsCache.isReady(data));
        assertEquals(5_000, ((Number) MatsimPopulationCache.readPopulationSummary(data).get("persons")).intValue());
        assertEquals(5_000, ((Number) MatsimTripEndsCache.readTripEndsSummary(data).get("journeys")).intValue());
    }

    private static void addPtPerson(Population population, String id, Coord home, Coord work) {
        PopulationFactory factory = population.getFactory();
        Person person = factory.createPerson(Id.createPersonId(id));
        Plan plan = factory.createPlan();
        plan.addActivity(factory.createActivityFromCoord("home", home));
        plan.addLeg(factory.createLeg("pt"));
        plan.addActivity(factory.createActivityFromCoord("work", work));
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        population.addPerson(person);
    }

    private static void assertPopulationEquals(MatsimPopulationCache.Aggregation expected,
                                               MatsimPopulationCache.Aggregation actual,
                                               MatsimPopulationCache.StreetIndex streets) {
        assertEquals(expected.persons, actual.persons);
        assertEquals(expected.homePersons, actual.homePersons);
        assertEquals(expected.workPersons, actual.workPersons);
        assertEquals(expected.unassignedHome, actual.unassignedHome);
        assertEquals(expected.unassignedWork, actual.unassignedWork);
        assertEquals(expected.homeTypes, actual.homeTypes);
        assertEquals(expected.workTypes, actual.workTypes);
        assertArrayEquals(expected.streetHome, actual.streetHome);
        assertArrayEquals(expected.streetWork, actual.streetWork);
        assertArrayEquals(
                MatsimPopulationCache.encodeGrid(expected.homeCells, expected.workCells,
                        expected.mercCellSize, streets),
                MatsimPopulationCache.encodeGrid(actual.homeCells, actual.workCells,
                        actual.mercCellSize, streets));
    }

    private static void assertTripEndsEquals(MatsimTripEndsCache.Aggregation expected,
                                             MatsimTripEndsCache.Aggregation actual,
                                             MatsimPopulationCache.StreetIndex streets) {
        assertEquals(expected.persons, actual.persons);
        assertEquals(expected.journeys, actual.journeys);
        assertEquals(expected.riders, actual.riders);
        assertEquals(expected.originPoints, actual.originPoints);
        assertEquals(expected.destPoints, actual.destPoints);
        assertEquals(expected.unassignedOrigin, actual.unassignedOrigin);
        assertEquals(expected.unassignedDest, actual.unassignedDest);
        assertArrayEquals(expected.streetOrigin, actual.streetOrigin);
        assertArrayEquals(expected.streetDest, actual.streetDest);
        assertArrayEquals(
                MatsimPopulationCache.encodeGrid(expected.originCells, expected.destCells,
                        expected.mercCellSize, streets),
                MatsimPopulationCache.encodeGrid(actual.originCells, actual.destCells,
                        actual.mercCellSize, streets));
        assertTrue(Arrays.equals(expected.streetOd, actual.streetOd));
    }
}
