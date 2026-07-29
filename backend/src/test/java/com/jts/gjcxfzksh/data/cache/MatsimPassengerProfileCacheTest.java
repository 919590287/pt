package com.jts.gjcxfzksh.data.cache;

import org.junit.jupiter.api.Test;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.pt.routes.DefaultTransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatsimPassengerProfileCacheTest {

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
}
