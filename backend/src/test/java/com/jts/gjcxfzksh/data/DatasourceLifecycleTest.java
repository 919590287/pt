package com.jts.gjcxfzksh.data;

import com.jts.gjcxfzksh.data.entry.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasourceLifecycleTest {

    private String registeredName;

    @AfterEach
    void cleanup() throws Exception {
        if (registeredName == null) return;
        Datasource.remove(registeredName);
        map("loadingStatusMap").remove(registeredName);
        collection("retainLoadedRequests").remove(registeredName);
    }

    @Test
    void unloadKeepsInFlightMarkerUntilParserExits() throws Exception {
        registeredName = "lifecycle-canceling";
        map("loadingStatusMap").put(registeredName, true);

        Datasource.unload(registeredName);

        assertTrue(Datasource.loadingStatus(registeredName));
        assertEquals("canceling", Datasource.loadStatusDetail(registeredName).getStage());
        assertFalse(Datasource.loadStatus(registeredName));
    }

    @Test
    void realReadKeepsLoadedModelAndLargeRoadUseFailsExplicitly(@TempDir Path tempDir) throws Exception {
        registeredName = "lifecycle-retain";
        Path output = Files.createDirectories(tempDir.resolve("output"));
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());
        MatsimData data = new MatsimData(registeredName, output.toString(), tempDir.resolve("cache").toString(), true);
        map("dataMap").put(registeredName, new Database(data));
        map("loadStatusMap").put(registeredName, true);

        Datasource.data(registeredName);

        assertTrue(Datasource.loadStatus(registeredName));
        assertTrue(map("dataMap").containsKey(registeredName));
        assertTrue(Datasource.retainLoadedRequested(registeredName));
        assertThrows(IllegalStateException.class, data::requireFullRoadNetwork);
    }

    @Test
    void failedPlanCoordinateTransformIsClearedAndDisclosed() {
        var population = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getPopulation();
        var factory = population.getFactory();
        Person person = factory.createPerson(Id.createPersonId("p1"));
        Plan plan = factory.createPlan();
        var home = factory.createActivityFromCoord("home", new Coord(1, 2));
        var work = factory.createActivityFromCoord("work", new Coord(3, 4));
        plan.addActivity(home);
        plan.addLeg(factory.createLeg("walk"));
        plan.addActivity(work);
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        population.addPerson(person);

        long failures = Datasource.transformPopulationCoordinates(population, coord -> {
            if (coord.getX() == 1) throw new IllegalArgumentException("bad coordinate");
            return new Coord(coord.getX() + 100, coord.getY() + 200);
        });

        assertEquals(1L, failures);
        assertEquals(1L, population.getAttributes().getAttribute("coordinateTransformFailures"));
        assertNull(home.getCoord(), "转换失败点不得在全局标记EPSG:3857后保留原始坐标");
        assertEquals(103.0, work.getCoord().getX(), 1e-9);
        assertEquals(204.0, work.getCoord().getY(), 1e-9);
    }

    @Test
    void failedScheduleCoordinateTransformIsClearedAndDisclosed() {
        var schedule = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getTransitSchedule();
        var factory = schedule.getFactory();
        TransitStopFacility bad = factory.createTransitStopFacility(
                Id.create("bad", TransitStopFacility.class), new Coord(1, 2), false);
        TransitStopFacility good = factory.createTransitStopFacility(
                Id.create("good", TransitStopFacility.class), new Coord(3, 4), false);
        schedule.addStopFacility(bad);
        schedule.addStopFacility(good);

        long failures = Datasource.transformScheduleCoordinates(schedule, coord -> {
            if (coord.getX() == 1) throw new IllegalArgumentException("bad coordinate");
            return new Coord(coord.getX() + 100, coord.getY() + 200);
        });

        assertEquals(1L, failures);
        assertEquals(1L, schedule.getAttributes().getAttribute("coordinateTransformFailures"));
        assertNull(bad.getCoord());
        assertEquals(103.0, good.getCoord().getX(), 1e-9);
    }

    @Test
    void networkBoundsSupportsAllNegativeCoordinatesAndRejectsEmptyNetwork() {
        var network = ScenarioUtils.createScenario(ConfigUtils.createConfig()).getNetwork();
        var factory = network.getFactory();
        network.addNode(factory.createNode(Id.createNodeId("a"), new Coord(-12_000, -8_000)));
        network.addNode(factory.createNode(Id.createNodeId("b"), new Coord(-4_000, -2_000)));

        Datasource.NetworkBounds bounds = Datasource.networkBounds(network.getNodes().values());

        assertEquals(-12_000, bounds.minX(), 1e-9);
        assertEquals(-8_000, bounds.minY(), 1e-9);
        assertEquals(-4_000, bounds.maxX(), 1e-9);
        assertEquals(-2_000, bounds.maxY(), 1e-9);
        assertThrows(IllegalStateException.class, () -> Datasource.networkBounds(java.util.List.of()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map map(String fieldName) throws Exception {
        Field field = Datasource.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map) field.get(null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Collection collection(String fieldName) throws Exception {
        Field field = Datasource.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Collection) field.get(null);
    }
}
