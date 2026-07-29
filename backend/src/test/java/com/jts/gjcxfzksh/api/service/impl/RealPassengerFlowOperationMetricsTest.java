package com.jts.gjcxfzksh.api.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
