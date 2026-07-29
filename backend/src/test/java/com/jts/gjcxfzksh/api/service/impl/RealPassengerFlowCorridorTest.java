package com.jts.gjcxfzksh.api.service.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealPassengerFlowCorridorTest {

    @Test
    void countsDistinctLinesOnUndirectedGeometrySegmentsLikeSimulationMode() {
        double[] p0 = {113.0, 22.0};
        double[] p1 = {113.1, 22.1};
        double[] p2 = {113.2, 22.2};

        RealPassengerFlowServiceImpl.CorridorNetwork network =
                RealPassengerFlowServiceImpl.aggregateCorridorRouteSegments(List.of(
                        new RealPassengerFlowServiceImpl.CorridorRouteGeometry("line-a", List.of(p0, p1, p2)),
                        new RealPassengerFlowServiceImpl.CorridorRouteGeometry("line-a", List.of(p2, p1, p0)),
                        new RealPassengerFlowServiceImpl.CorridorRouteGeometry("line-b", List.of(p0, p1, p2)),
                        new RealPassengerFlowServiceImpl.CorridorRouteGeometry("line-c", List.of(p1, p2))));

        RealPassengerFlowServiceImpl.CorridorBaseSegment first = network.segments().get(
                RealPassengerFlowServiceImpl.corridorSegmentKey(p0, p1));
        RealPassengerFlowServiceImpl.CorridorBaseSegment second = network.segments().get(
                RealPassengerFlowServiceImpl.corridorSegmentKey(p1, p2));

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(2, first.coefficient());
        assertEquals(3, second.coefficient());
        assertEquals(3, network.busLines());
    }

    @Test
    void doesNotSnapNearbyButDifferentRouteGeometryTogether() {
        double[] p0 = {113.0, 22.0};
        double[] p1 = {113.1, 22.1};
        double[] nearby0 = {113.0, 22.000000001};
        double[] nearby1 = {113.1, 22.100000001};

        RealPassengerFlowServiceImpl.CorridorNetwork network =
                RealPassengerFlowServiceImpl.aggregateCorridorRouteSegments(List.of(
                        new RealPassengerFlowServiceImpl.CorridorRouteGeometry("line-a", List.of(p0, p1)),
                        new RealPassengerFlowServiceImpl.CorridorRouteGeometry("line-b", List.of(nearby0, nearby1))));

        assertEquals(2, network.segments().size());
        assertEquals(1, network.segments().get(
                RealPassengerFlowServiceImpl.corridorSegmentKey(p0, p1)).coefficient());
        assertEquals(1, network.segments().get(
                RealPassengerFlowServiceImpl.corridorSegmentKey(nearby0, nearby1)).coefficient());
    }

    @Test
    void derivesNetworkDensityNumeratorRepetitionAndNonLinearCoefficientFromSameGeometry() {
        double[] p0 = {113.0, 22.0};
        double[] p1 = {113.01, 22.0};
        double[] p2 = {113.02, 22.0};
        RealPassengerFlowServiceImpl.CorridorNetwork network =
                RealPassengerFlowServiceImpl.aggregateCorridorRouteSegments(List.of(
                        new RealPassengerFlowServiceImpl.CorridorRouteGeometry(
                                "line-a", List.of(p0, p1, p2)),
                        new RealPassengerFlowServiceImpl.CorridorRouteGeometry(
                                "line-b", List.of(p0, p1, p2))));

        RealPassengerFlowServiceImpl.CorridorMetricStats stats =
                RealPassengerFlowServiceImpl.corridorMetricStats(network, "全市");

        assertEquals(2.0, stats.repetitionCoefficient(), 1e-9);
        assertEquals(1.0, network.averageNonLinearCoefficient(), 1e-6);
        assertTrue(stats.scopedNetworkLengthMeters() > 0);
    }
}
