package com.jts.gjcxfzksh.data.cache;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatsimPrecomputedPopulationMetricsTest {

    @Test
    void v6UsesPopulationV9DerivedMetricsWithoutInMemoryPopulation() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "ready");
        summary.put("coverage300Percent", 62.5);
        summary.put("coverage300Status", "ready");
        summary.put("busSharePercent", 31.25);
        summary.put("publicTransportMotorizedSharePercent", 41.25);
        summary.put("publicTransportShareStatus", "ready");
        summary.put("busShareStatus", "ready");
        summary.put("tripModeSharePercent", Map.of("bus", 31.25, "subway", 10.0, "car", 58.75));
        summary.put("speedKmh", Map.of("ptAvg", 18.4, "carAvg", 32.0));
        summary.put("averageBusWaitMinutes", 7.75);
        summary.put("busServiceJourneyStatus", "ready");
        summary.put("averageBusTransfers", 0.625);
        summary.put("busRailFeederPercent", 14.9);
        summary.put("busServiceJourneys", 80L);
        summary.put("busServiceTransfers", 50L);
        summary.put("busRailJourneys", 12L);
        summary.put("peakCarDistanceMeters", 12_000.0);
        summary.put("peakCarTravelSeconds", 1_500.0);
        summary.put("peakCarSamples", 25L);
        summary.put("speedPeriodPolicy", "peak-0700-0900-and-1700-1900");
        summary.put("carSpeedSpatialScope", "all-model-urban-roads");
        summary.put("busWaitSamples", 72L);
        summary.put("persons", 100L);
        summary.put("coverageValidHomePersons", 80L);
        summary.put("coverageMissingHomePersons", 20L);
        summary.put("coordinateTransformFailures", 3L);

        Map<String, Object> metrics = MatsimPrecomputedCache.populationDerivedMetrics(summary);

        Map<?, ?> coverage = (Map<?, ?>) metrics.get("fgl_300");
        assertEquals(62.5, ((Number) coverage.get("cover")).doubleValue(), 1e-9);
        assertEquals(41.25, ((Number) ((Map<?, ?>) metrics.get("fxfdl")).get("pt")).doubleValue(), 1e-9);
        assertEquals(31.25, ((Number) ((Map<?, ?>) metrics.get("fxfdl")).get("bus")).doubleValue(), 1e-9);
        assertEquals(18.4, ((Number) ((Map<?, ?>) metrics.get("yxsdb")).get("ptAvg")).doubleValue(), 1e-9);
        assertEquals(7.75, ((Number) metrics.get("pjhcsj")).doubleValue(), 1e-9,
                "population-v9 的候车值已是分钟，不得再除以60或按小时均值二次平均");
        assertEquals(0.625, ((Number) metrics.get("pjhccs")).doubleValue(), 1e-9);
        assertEquals(14.9, ((Number) metrics.get("gjjbbl")).doubleValue(), 1e-9);
        assertEquals(80L, metrics.get("busServiceJourneys"));
        assertEquals(50L, metrics.get("busServiceTransfers"));
        assertEquals(12L, metrics.get("busRailJourneys"));
        assertEquals(12_000.0, metrics.get("peakCarDistanceMeters"));
        assertEquals(1_500.0, metrics.get("peakCarTravelSeconds"));
        assertEquals(25L, metrics.get("peakCarSamples"));
        assertEquals("peak-0700-0900-and-1700-1900", metrics.get("speedPeriodPolicy"));
        assertEquals("all-model-urban-roads", metrics.get("carSpeedSpatialScope"));
        assertEquals(72L, metrics.get("busWaitSamples"));
        assertEquals(100L, metrics.get("coverageTotalPersons"));
        assertEquals(80L, metrics.get("coverageValidHomePersons"));
        assertEquals(20L, metrics.get("coverageMissingHomePersons"));
        assertEquals(3L, metrics.get("coordinateTransformFailures"));
        assertEquals("ready", metrics.get("coverageStatus"));
        assertEquals("valid-first-home", metrics.get("coverageDenominatorPolicy"));
    }

    @Test
    void populationV5CoverageKeepsExplicitNoData() {
        Map<String, Object> metrics = MatsimPrecomputedCache.populationDerivedMetrics(Map.of(
                "status", "ready",
                "coverage300Status", "nodata",
                "tripModeSharePercent", Map.of(),
                "speedKmh", new LinkedHashMap<>(Map.of()),
                "persons", 0
        ));

        Map<?, ?> coverage = (Map<?, ?>) metrics.get("fgl_300");
        assertEquals(Boolean.TRUE, coverage.get("nodata"));
        assertNull(metrics.get("pjhcsj"));
        assertTrue(MatsimPrecomputedCache.populationDerivedMetrics(Map.of("status", "unsupported")).isEmpty());
    }

    @Test
    void residentCountUsesPlansHomePersonsFromStreamingSummary() {
        Map<String, Object> summary = Map.of(
                "status", "ready",
                "persons", 120L,
                "homePersons", 96L
        );

        assertEquals(96L, MatsimPrecomputedCache.residentHomePersonCount(null, summary));
    }

    @Test
    void busDailyTripsUsesResidentBusJourneysAndExcludesMetro() {
        Map<String, Object> summary = Map.of(
                "status", "ready",
                "journeys", 240L,
                "busJourneys", 20L,
                "transitJourneys", 48L,
                "residentBusJourneys", 18L,
                "residentTransitJourneys", 44L,
                "residentUnresolvedLegacyPtJourneys", 0L,
                "busDailyTripsStatus", "ready",
                "homePersons", 96L
        );

        assertEquals(18.0 / 96.0, MatsimPrecomputedCache.dailyTripsPerPerson(summary, 96L), 1e-9,
                "必须统计常住人口道路公交主方式完整出行；不得混入轨道或全部公共交通出行");
    }
}
