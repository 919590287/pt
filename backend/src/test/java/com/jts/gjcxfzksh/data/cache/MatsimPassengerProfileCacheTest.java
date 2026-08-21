package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.data.MatsimData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatsimPassengerProfileCacheTest {

    @Test
    void largeModelDetailsReceiveCachedRouteStationAndDepartureProfiles(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("output");
        Path cache = tempDir.resolve("cache");
        Files.createDirectories(output);
        Files.createDirectories(cache);
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());
        Path archivedPlans = Files.write(output.resolve("output_plans.xml.gz"), new byte[]{1, 2, 3});

        MatsimData data = new MatsimData("profile-apply-large", output.toString(), cache.toString(), true);
        String routeKey = "line-1::route-1";
        MatsimPassengerProfileCache.Aggregation aggregation = MatsimPassengerProfileCache.newAggregation(
                new MatsimPassengerProfileCache.Context(
                        Map.of("stop-a", "站A", "stop-b", "站B"),
                        Map.of(routeKey, Set.of("bus::line-1"))));
        Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        aggregation.acceptPerson(person(population, "p1", 65,
                List.of("home", "work"), 1));
        MatsimPassengerProfileCache.storeBuiltAggregation(data, aggregation, System.currentTimeMillis());

        Map<String, Object> route = MatsimPassengerProfileCache.applyRouteProfile(data,
                Map.of("lineId", "line-1", "routeId", "route-1"));
        Map<String, Object> station = MatsimPassengerProfileCache.applyStationProfile(data,
                Map.of("stationName", "站A"));
        Map<String, Object> departure = MatsimPassengerProfileCache.applyDepartureProfile(data,
                Map.of("lineId", "line-1", "routeId", "route-1", "departureId", "dep-1"));
        Map<String, Object> departureBundle = MatsimPassengerProfileCache.applyDepartureBundleProfile(data,
                Map.of("lineId", "line-1", "routeId", "route-1", "departures", List.of()));

        assertEquals(1L, ((Number) demographics(route).get("riderCount")).longValue());
        assertEquals(1L, ((Number) demographics(station).get("riderCount")).longValue());
        assertEquals(1L, ((Number) demographics(departure).get("riderCount")).longValue());
        assertEquals(1L, ((Number) demographics(departureBundle).get("riderCount")).longValue());
        assertEquals("route", demographics(departure).get("profileScope"));

        // plans/events 只用于首次派生。缓存 ready 后归档源大文件，画像工件仍必须
        // 自包含可读；恢复成不同内容时则应正常失效，不能误用旧画像。
        Files.delete(archivedPlans);
        MatsimSourceFingerprint.invalidateAll();
        assertTrue(MatsimPassengerProfileCache.isReady(data));
        assertEquals(1L, ((Number) demographics(
                MatsimPassengerProfileCache.applyRouteProfile(data,
                        Map.of("lineId", "line-1", "routeId", "route-1")))
                .get("riderCount")).longValue());
        Files.write(archivedPlans, new byte[]{9, 8, 7, 6});
        MatsimSourceFingerprint.invalidateAll();
        assertFalse(MatsimPassengerProfileCache.isReady(data));
    }

    @Test
    void selectedPlans流式聚合按人去重并保留出行目的计次() {
        String routeKey = "line-1::route-1";
        MatsimPassengerProfileCache.Context context = new MatsimPassengerProfileCache.Context(
                Map.of("stop-a", "站A", "stop-b", "站B"),
                Map.of(routeKey, Set.of("bus::line-1"))
        );
        MatsimPassengerProfileCache.Aggregation aggregation =
                MatsimPassengerProfileCache.newAggregation(context);

        Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        Person elderlyCommuter = person(population, "p1", 65,
                List.of("home", "work", "home"), 2);
        Person student = person(population, "p2", 20,
                List.of("home", "school"), 1);
        student.getAttributes().putAttribute("subpopulation", "student");

        aggregation.acceptPerson(elderlyCommuter);
        aggregation.acceptPerson(student);

        Map<String, Object> payload = aggregation.toPayload();
        Map<String, Object> route = section(payload, "routes", routeKey);
        Map<String, Object> group = section(payload, "lineGroups", "bus::line-1");
        Map<String, Object> stationA = section(payload, "stations", "站A");
        Map<String, Object> stationB = section(payload, "stations", "站B");

        assertEquals(2L, route.get("riderCount"), "p1 同 route 乘坐两次也只计一个乘客");
        assertEquals(2L, group.get("riderCount"), "线路组同样按人去重");
        assertEquals(2L, stationA.get("riderCount"));
        assertEquals(2L, stationB.get("riderCount"), "上下车站都应有画像");
        assertEquals(50.0, route.get("elderly"));
        assertEquals(50.0, route.get("student"));
        assertEquals(50.0, route.get("commuter"));
        assertEquals("streaming-selected-plans", route.get("source"));
        assertEquals("trip-purpose", route.get("activitySource"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> activities = (List<Map<String, Object>>) route.get("activityTypes");
        long activityTrips = activities.stream().mapToLong(item -> ((Number) item.get("count")).longValue()).sum();
        assertEquals(3L, activityTrips, "活动目的按 transit leg 计次，不按人去重");
        assertTrue(activities.stream().anyMatch(item -> "work".equals(item.get("type"))));
        assertTrue(activities.stream().anyMatch(item -> "school".equals(item.get("type"))));
    }

    private static Person person(Population population, String id, int age,
                                 List<String> activities, int transitLegs) {
        PopulationFactory factory = population.getFactory();
        Person person = factory.createPerson(Id.createPersonId(id));
        person.getAttributes().putAttribute("age", age);
        Plan plan = factory.createPlan();
        for (int i = 0; i < activities.size(); i++) {
            plan.addActivity(factory.createActivityFromCoord(activities.get(i), new Coord(i * 1000.0, 0.0)));
            if (i < transitLegs) {
                Leg leg = factory.createLeg(i % 2 == 0 ? "subway" : "bus");
                leg.setRoute(new DefaultTransitPassengerRoute(
                        Id.createLinkId("link-a"),
                        Id.createLinkId("link-b"),
                        Id.create("stop-a", TransitStopFacility.class),
                        Id.create("stop-b", TransitStopFacility.class),
                        Id.create("line-1", TransitLine.class),
                        Id.create("route-1", TransitRoute.class)
                ));
                plan.addLeg(leg);
            }
        }
        person.addPlan(plan);
        person.setSelectedPlan(plan);
        population.addPerson(person);
        return person;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> payload, String section, String key) {
        return (Map<String, Object>) ((Map<String, Object>) payload.get(section)).get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> demographics(Map<String, Object> detail) {
        return (Map<String, Object>) detail.get("demographics");
    }
}
