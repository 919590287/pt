package com.jts.gjcxfzksh.data;

import com.jts.gjcxfzksh.data.entry.Database;
import com.jts.gjcxfzksh.data.entry.Scheme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.facilities.ActivityFacility;
import org.matsim.facilities.FacilitiesWriter;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeTierTest {

    @TempDir
    Path tempDir;

    private final List<String> names = new ArrayList<>();

    @AfterEach
    void cleanup() throws Exception {
        for (String name : names) {
            Datasource.remove(name);
            map("loadingStatusMap").remove(name);
            map("runtimeSchemes").remove(name);
        }
    }

    @Test
    void visualTierReleasesComputeOnlyObjectsButKeepsDisplayScenario() throws Exception {
        MatsimData data = populatedData("runtime-demote", 1L);
        MutableScenario original = data.getScenario();

        long released = data.demoteToVisual();

        assertTrue(released >= 2L);
        assertEquals(MatsimData.RuntimeTier.VISUAL, data.getRuntimeTier());
        assertEquals(0, data.getPopulation().getPersons().size());
        assertEquals(0, data.getAfs().getFacilities().size());
        assertEquals(original.getNetwork(), data.getNetwork());
        assertEquals(0L, data.demoteToVisual(), "重复降级必须幂等");
    }

    @Test
    void tierGovernorKeepsActiveComputeOneVisualAndCatalogsOldest() throws Exception {
        MatsimData oldest = populatedData("runtime-oldest", 1L);
        MatsimData recent = populatedData("runtime-recent", 2L);
        MatsimData active = populatedData("runtime-active", 3L);
        map("dataMap").put(oldest.getName(), new Database(oldest));
        map("dataMap").put(recent.getName(), new Database(recent));
        map("dataMap").put(active.getName(), new Database(active));

        Datasource.enforceRuntimeTiers(active.getName(), 1, 1);

        assertNull(Datasource.peek(oldest.getName()));
        assertEquals(MatsimData.RuntimeTier.VISUAL,
                Datasource.peek(recent.getName()).matsim_data().getRuntimeTier());
        assertEquals(MatsimData.RuntimeTier.COMPUTE,
                Datasource.peek(active.getName()).matsim_data().getRuntimeTier());
    }

    @Test
    void cacheHitVisualLoadSkipsPlansAndFacilitiesButKeepsNetwork() throws Exception {
        String name = "runtime-visual-load";
        names.add(name);
        Path output = Files.createDirectories(tempDir.resolve(name).resolve("output"));
        Path cache = Files.createDirectories(tempDir.resolve(name).resolve("cache"));
        Config config = ConfigUtils.createConfig();
        config.global().setCoordinateSystem("EPSG:3857");
        config.transit().setUseTransit(true);
        new ConfigWriter(config).write(output.resolve("output_config.xml").toString());

        MutableScenario source = (MutableScenario) ScenarioUtils.createScenario(config);
        var networkFactory = source.getNetwork().getFactory();
        var from = networkFactory.createNode(Id.createNodeId("from"), new Coord(0, 0));
        var to = networkFactory.createNode(Id.createNodeId("to"), new Coord(100, 0));
        source.getNetwork().addNode(from);
        source.getNetwork().addNode(to);
        source.getNetwork().addLink(networkFactory.createLink(Id.createLinkId("link"), from, to));
        new NetworkWriter(source.getNetwork()).write(output.resolve("output_network.xml.gz").toString());
        var stop = source.getTransitSchedule().getFactory().createTransitStopFacility(
                Id.create("visual-stop", org.matsim.pt.transitSchedule.api.TransitStopFacility.class),
                new Coord(0, 0), false);
        stop.setLinkId(Id.createLinkId("link"));
        source.getTransitSchedule().addStopFacility(stop);
        new TransitScheduleWriter(source.getTransitSchedule())
                .writeFile(output.resolve("output_transitSchedule.xml.gz").toString());

        Person person = source.getPopulation().getFactory().createPerson(Id.createPersonId("visual-person"));
        source.getPopulation().addPerson(person);
        new PopulationWriter(source.getPopulation())
                .write(output.resolve("output_plans.xml.gz").toString());
        ActivityFacility facility = source.getActivityFacilities().getFactory()
                .createActivityFacility(Id.create("visual-facility", ActivityFacility.class), new Coord(1, 2));
        source.getActivityFacilities().addActivityFacility(facility);
        new FacilitiesWriter(source.getActivityFacilities())
                .write(output.resolve("output_facilities.xml.gz").toString());

        Scheme scheme = new Scheme();
        scheme.setName(name);
        scheme.setOutput(output.toString());
        scheme.setCache(cache.toString());
        scheme.setDesc(new Scheme.Desc());
        assertTrue(Datasource.loadVisualAsync(scheme));

        long deadline = System.currentTimeMillis() + 10_000L;
        while (!Datasource.loadStatus(name) && System.currentTimeMillis() < deadline) {
            Thread.sleep(25L);
        }
        Database loadedDatabase = Datasource.peek(name);
        assertNotNull(loadedDatabase);
        MatsimData loaded = loadedDatabase.matsim_data();
        assertEquals(MatsimData.RuntimeTier.VISUAL, loaded.getRuntimeTier());
        assertEquals(1, loaded.getNetwork().getLinks().size());
        assertEquals(0, loaded.getPopulation().getPersons().size());
        assertEquals(0, loaded.getAfs().getFacilities().size());
    }

    private MatsimData populatedData(String name, long lastRequestTime) throws Exception {
        names.add(name);
        Path output = Files.createDirectories(tempDir.resolve(name).resolve("output"));
        Path cache = Files.createDirectories(tempDir.resolve(name).resolve("cache"));
        Config config = ConfigUtils.createConfig();
        new ConfigWriter(config).write(output.resolve("output_config.xml").toString());
        MatsimData data = new MatsimData(name, output.toString(), cache.toString(), false);
        MutableScenario scenario = (MutableScenario) ScenarioUtils.createScenario(config);
        Person person = scenario.getPopulation().getFactory().createPerson(Id.createPersonId("p-" + name));
        scenario.getPopulation().addPerson(person);
        ActivityFacility facility = scenario.getActivityFacilities().getFactory()
                .createActivityFacility(Id.create("f-" + name, ActivityFacility.class), new Coord(1, 2));
        scenario.getActivityFacilities().addActivityFacility(facility);
        data.setConfig(config);
        data.setScenario(scenario);
        data.setLastRequestTime(lastRequestTime);
        return data;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Map map(String fieldName) throws Exception {
        Field field = Datasource.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map) field.get(null);
    }
}
