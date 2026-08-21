package com.jts.gjcxfzksh.api.service.impl;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RealRouteFleetBackfillServiceTest {

    @Test
    void parsesOperatingTimeRanges() {
        assertEquals(60, RealRouteFleetBackfillService.parseDuration("60"));
        assertEquals(72.5, RealRouteFleetBackfillService.parseDuration("70-75"));
        assertEquals(70, RealRouteFleetBackfillService.parseDuration("65~75"));
        assertNull(RealRouteFleetBackfillService.parseDuration(""));
    }

    @Test
    void nansha65ScheduleRequiresEighteenVehicles() {
        List<Integer> up = RealRouteFleetBackfillService.parseDepartures(
                "06:10;06:25;06:40;06:55;07:00;07:10;07:20;07:30;07:40;07:50;08:00;08:10;08:20;08:30;08:40;08:50;09:00;09:15;09:30;09:45;10:00;10:15;10:30;10:45;11:00;11:15;11:30;11:45;12:00;12:15;12:30;12:45;13:00;13:15;13:30;13:45;14:00;14:15;14:30;14:45;15:00;15:15;15:30;15:45;16:00;16:15;16:30;16:45;17:00;17:10;17:20;17:30;17:40;17:50;18:00;18:10;18:20;18:30;18:40;18:50;19:00;19:15;19:30;19:45;20:00;20:15;20:30;20:45;21:00;21:15;21:30;21:45;22:00;22:15;22:30;22:45;23:00");
        List<Integer> down = RealRouteFleetBackfillService.parseDepartures(
                "05:00;05:15;05:30;05:45;06:00;06:15;06:30;06:45;07:00;07:10;07:20;07:30;07:40;07:50;08:00;08:10;08:20;08:30;08:40;08:50;09:00;09:15;09:30;09:45;10:00;10:15;10:30;10:45;11:00;11:15;11:30;11:45;12:00;12:15;12:30;12:45;13:00;13:15;13:30;13:45;14:00;14:15;14:30;14:45;15:00;15:15;15:30;15:45;16:00;16:15;16:30;16:45;17:00;17:10;17:20;17:30;17:40;17:50;18:00;18:10;18:20;18:30;18:40;18:50;19:00;19:15;19:30;19:45;20:00;20:15;20:30;20:45;21:00;21:15;21:30;21:45;21:55");

        assertEquals(18, RealRouteFleetBackfillService.pairedFleet(up, down, 60, 60));
    }

    @Test
    void copiesOnePhysicalLineFleetToBothDirectionsOnlyWhenBlank() {
        Map<String, List<RealRouteFleetBackfillService.RouteRecord>> groups = new LinkedHashMap<>();
        groups.put("南沙测试路", List.of(
                new RealRouteFleetBackfillService.RouteRecord("up", "南沙测试路", 60.0, List.of(360, 390, 420), 0),
                new RealRouteFleetBackfillService.RouteRecord("down", "南沙测试路", 60.0, List.of(375, 405, 435), 0)
        ));
        Map<String, Integer> updates = RealRouteFleetBackfillService.calculateUpdates(groups);

        assertEquals(updates.get("up"), updates.get("down"));
        assertEquals(6, updates.get("up"));
    }
}
