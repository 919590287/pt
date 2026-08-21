package com.jts.gjcxfzksh.data.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealPopulationCacheEvaluationTest {

    @TempDir
    Path tempDir;

    @Test
    void evaluationUsesResidentPopulationColumnAndGridCoverage() throws Exception {
        Path source = tempDir.resolve(RealPopulationCache.POPULATION_FILE);
        Files.writeString(source, """
                百米网格坐标（WGS-84）,通勤居住人口数量,通勤就业人口数量,常住人口数量
                113.521882;22.803381,100,200,10
                113.521882;22.803381,300,400,20
                """);

        RealPopulationCache.EvaluationPopulationStats stats =
                RealPopulationCache.evaluationStats(
                        source, "全市", List.of(new double[]{113.521882, 22.803381}));

        assertNotNull(stats);
        assertEquals(30, stats.residentPersons());
        assertEquals(30, stats.coveredResidentPersons());
        assertEquals(100.0, stats.coveragePercent());
        assertTrue(stats.areaKm2() > 0);
        assertEquals(30.0 / stats.areaKm2(), stats.density());
    }

    @Test
    void stationPopulationCoverageSeparates300And500MeterResidents() throws Exception {
        Path source = tempDir.resolve(RealPopulationCache.POPULATION_FILE);
        Files.writeString(source, """
                百米网格坐标（WGS-84）,通勤居住人口数量,通勤就业人口数量,常住人口数量
                113.521882;22.803381,1000,2000,10
                113.526082;22.803381,3000,4000,20
                """);

        RealPopulationCache.StationPopulationCoverage coverage =
                RealPopulationCache.stationPopulationCoverage(
                        source, List.of(new double[]{113.521882, 22.803381}));

        assertNotNull(coverage);
        assertEquals(30, coverage.city().residentPersons());
        assertEquals(10, coverage.city().coveredResidents300m());
        assertEquals(30, coverage.city().coveredResidents500m());
        assertEquals(100.0 / 3.0, coverage.city().coverage300Percent(), 1e-9);
        assertEquals(100.0, coverage.city().coverage500Percent(), 1e-9);
    }
}
