package com.jts.gjcxfzksh.api.service.impl;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class RealPassengerFlowDepartureMatchingTest {

    @Test
    void matchesEachActualDepartureAtMostOnce() {
        int[] result = RealPassengerFlowServiceImpl.matchScheduledToActual(
                List.of(8 * 3600, 8 * 3600 + 10 * 60),
                List.of(8 * 3600 + 5 * 60),
                15 * 60);

        long matched = Arrays.stream(result).filter(value -> value >= 0).count();
        assertArrayEquals(new int[]{-1, 0}, result);
        org.junit.jupiter.api.Assertions.assertEquals(1, matched);
    }

    @Test
    void maximizesCoverageBeforeMinimizingTimingError() {
        int[] result = RealPassengerFlowServiceImpl.matchScheduledToActual(
                List.of(100, 200), List.of(175, 250), 80);

        assertArrayEquals(new int[]{0, 1}, result);
    }

    @Test
    void leavesDeparturesOutsideToleranceUnmatched() {
        int[] result = RealPassengerFlowServiceImpl.matchScheduledToActual(
                List.of(8 * 3600), List.of(8 * 3600 + 16 * 60), 15 * 60);

        assertArrayEquals(new int[]{-1}, result);
    }
}
