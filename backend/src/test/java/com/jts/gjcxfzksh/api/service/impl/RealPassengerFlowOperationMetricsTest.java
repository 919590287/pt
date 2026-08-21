package com.jts.gjcxfzksh.api.service.impl;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealPassengerFlowOperationMetricsTest {

    @Test
    void usesSameDailyOperationFormulasAsSimulationMode() {
        RealPassengerFlowServiceImpl.OperationRatios ratios =
                RealPassengerFlowServiceImpl.operationRatios(1200, 20, 80, 600);

        assertEquals(60, ratios.perVehicle());
        assertEquals(15, ratios.perTrip());
        assertEquals(2, ratios.intensity());
    }

    @Test
    void missingDenominatorsDoNotProduceInvalidNumbers() {
        RealPassengerFlowServiceImpl.OperationRatios ratios =
                RealPassengerFlowServiceImpl.operationRatios(1200, 0, 0, 0);

        assertEquals(0, ratios.perVehicle());
        assertEquals(0, ratios.perTrip());
        assertEquals(0, ratios.intensity());
    }

    @Test
    void depotAreaUsesLandAreaDividedByObservedOperatingVehicles() {
        assertEquals(150.0,
                RealPassengerFlowServiceImpl.depotAreaPerVehicle(30_000.0, 200));
        assertNull(RealPassengerFlowServiceImpl.depotAreaPerVehicle(null, 200));
        assertNull(RealPassengerFlowServiceImpl.depotAreaPerVehicle(30_000.0, 0));
    }

    @Test
    void realLineGroupingNormalizesNanshaAliasesAndNestedEndpointParentheses() {
        assertEquals("南沙10路", RealPassengerFlowServiceImpl.baseLineName(
                "南10路(新兴村委总站--地铁万顷沙站)"));
        assertEquals("南沙14路", RealPassengerFlowServiceImpl.baseLineName(
                "南14路(香港科技大学(广州)站--横沥地铁站公交总站)"));
        assertEquals("南沙65路(大站快线)", RealPassengerFlowServiceImpl.baseLineName(
                "南沙65路(大站快线)(大岗公交总站--市桥汽车站西门站)"));
        assertEquals("南沙40路", RealPassengerFlowServiceImpl.baseLineName(
                "40路/南40路(大岗公交总站--新兴村委总站)"));
    }

    @Test
    void blankOptionalSpeedIsNoDataButMalformedSpeedStillFails() {
        assertTrue(Double.isNaN(RealPassengerFlowServiceImpl.optionalNumber(
                Map.of("avg_speed_kmh", ""), "avg_speed_kmh")));
        assertEquals(26.5, RealPassengerFlowServiceImpl.optionalNumber(
                Map.of("avg_speed_kmh", "26.5"), "avg_speed_kmh"));
        assertThrows(IllegalArgumentException.class, () ->
                RealPassengerFlowServiceImpl.optionalNumber(
                        Map.of("avg_speed_kmh", "not-a-number"), "avg_speed_kmh"));
    }

    @Test
    void timetableAllowsGtfsTimesAfterMidnight() {
        assertEquals(26 * 3600 + 10 * 60,
                RealPassengerFlowServiceImpl.clockSeconds("26:10:00"));
        assertThrows(RuntimeException.class,
                () -> RealPassengerFlowServiceImpl.clockSeconds("48:00:00"));
    }

    @Test
    void routeShapefilePeakHeadwaysUseBothPeakPeriods() {
        assertEquals(12.5, RealPassengerFlowServiceImpl.averagePositive(10, 15));
        assertEquals(10, RealPassengerFlowServiceImpl.averagePositive(10, 0));
        assertEquals(15, RealPassengerFlowServiceImpl.averagePositive(0, 15));
    }

    @Test
    void routeShapefileCapacityUsesTotalPassengerCapacity() {
        assertEquals(90, RealPassengerFlowServiceImpl.firstCapacity("90/26"));
        assertEquals(70, RealPassengerFlowServiceImpl.firstCapacity("70,24"));
        assertEquals(0, RealPassengerFlowServiceImpl.firstCapacity(""));
    }

}
