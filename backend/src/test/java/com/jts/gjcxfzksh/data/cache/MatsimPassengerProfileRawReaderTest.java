package com.jts.gjcxfzksh.data.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MatsimPassengerProfileRawReaderTest {

    @Test
    void scansSelectedPtPlansWithoutGrowingMatsimIdCache(@TempDir Path tempDir) throws Exception {
        String routeKey = "line-1::route-1";
        MatsimPassengerProfileCache.Aggregation aggregation = MatsimPassengerProfileCache.newAggregation(
                new MatsimPassengerProfileCache.Context(
                        Map.of("stop-a", "站A", "stop-b", "站B"),
                        Map.of(routeKey, Set.of("bus::line-1"))));
        Path plans = tempDir.resolve("output_plans.xml");
        Files.writeString(plans, """
                <?xml version="1.0" encoding="utf-8"?>
                <!DOCTYPE population SYSTEM "http://www.matsim.org/files/dtd/population_v6.dtd">
                <population>
                  <person id="raw-person-1">
                    <attributes>
                      <attribute name="age" class="java.lang.Integer">65</attribute>
                      <attribute name="subpopulation" class="java.lang.String">worker</attribute>
                    </attributes>
                    <plan selected="yes">
                      <activity type="home" />
                      <leg mode="bus"><route type="default_pt">{"transitRouteId":"route-1","transitLineId":"line-1","accessFacilityId":"stop-a","egressFacilityId":"stop-b"}</route></leg>
                      <activity type="pt interaction" />
                      <leg mode="subway"><route type="default_pt">{"transitRouteId":"route-1","transitLineId":"line-1","accessFacilityId":"stop-b","egressFacilityId":"stop-a"}</route></leg>
                      <activity type="work" />
                    </plan>
                    <plan selected="no">
                      <activity type="home" />
                      <leg mode="bus"><route type="default_pt">{"transitRouteId":"ignored","transitLineId":"line-2"}</route></leg>
                      <activity type="shopping" />
                    </plan>
                  </person>
                  <person id="raw-person-no-pt">
                    <plan selected="yes"><activity type="home" /><leg mode="car" /><activity type="work" /></plan>
                  </person>
                </population>
                """, StandardCharsets.UTF_8);
        int idsBefore = Id.getNumberOfIds(Person.class);

        long persons = MatsimPassengerProfileRawReader.read(plans, aggregation, null);
        Map<String, Object> profile = section(aggregation.toPayload(), "routes", routeKey);

        assertEquals(2L, persons);
        assertEquals(idsBefore, Id.getNumberOfIds(Person.class), "原生 reader 不得创建/缓存 person Id");
        assertEquals(1L, profile.get("riderCount"));
        assertEquals(100.0, profile.get("elderly"));
        assertEquals(100.0, profile.get("commuter"));
        @SuppressWarnings("unchecked")
        var activities = (java.util.List<Map<String, Object>>) profile.get("activityTypes");
        assertEquals(2L, activities.stream().mapToLong(row -> ((Number) row.get("count")).longValue()).sum());
        assertEquals("work", activities.getFirst().get("type"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> payload, String section, String key) {
        return (Map<String, Object>) ((Map<String, Object>) payload.get(section)).get(key);
    }
}
