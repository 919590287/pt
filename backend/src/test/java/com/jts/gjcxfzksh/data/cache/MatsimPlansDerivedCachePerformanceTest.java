package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.ModelProcessingPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.matsim.api.core.v01.Coord;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实 plans 可复现性基准，默认不进常规回归。
 *
 * <pre>
 * mvn test -Dtest=MatsimPlansDerivedCachePerformanceTest -Dplans.performance=true \
 *   -Dplans.performance.output=/path/to/output -Dplans.performance.workers=8
 * </pre>
 *
 * <p>为使结果偏保守，优化路径先跑；改造前基线的两次串行扫描后跑，可以受益于
 * 已预热的 OS 文件页和 JIT。测量只包含 plans 解压/解析/活动聚合，不包含后续工件写盘和 OD 聚合。</p>
 */
class MatsimPlansDerivedCachePerformanceTest {

    private static final String DEFAULT_OUTPUT =
            "/Volumes/USB DISK/pt_data/广州市/仿真数据/public/广州市抽样模型2/output";

    @Test
    @EnabledIfSystemProperty(named = "plans.performance", matches = "true")
    void comparesTwoSerialScansWithSharedParallelScan() {
        Path output = Path.of(System.getProperty("plans.performance.output", DEFAULT_OUTPUT));
        assumeTrue(Files.isDirectory(output), "真实 output 不存在，跳过基准");

        MatsimData data = new MatsimData("plans-performance", output.toString(),
                Path.of(System.getProperty("java.io.tmpdir"), "gjcxfzksh-cache", "plans-performance").toString(),
                true);
        assumeTrue(data.getOutfile().getPlans() != null, "output 缺 plans 文件，跳过基准");
        // 广州中心纬度只影响栅格尺寸，不影响前后路径的相对耗时。
        data.setCenter(new Coord(12_615_000, 2_640_000));

        int workers = Integer.getInteger("plans.performance.workers", ModelProcessingPool.parallelism());
        MatsimPopulationCache.StreetIndex streets = MatsimPopulationCache.streetIndex();

        // 优化路径先跑：一次读取 + N worker + 坐标缓存。
        MatsimPlansDerivedCache.ScanResult optimized = MatsimPlansDerivedCache.scan(
                data, streets, true, true, workers, 1 << 18);

        // 改造前基线：population/tripends 各自串行读一次，不缓存街道归属。
        MatsimPlansDerivedCache.ScanResult baselinePopulation = MatsimPlansDerivedCache.scan(
                data, streets, true, false, 1, 0);
        MatsimPlansDerivedCache.ScanResult baselineTripEnds = MatsimPlansDerivedCache.scan(
                data, streets, false, true, 1, 0);
        long baselineMs = baselinePopulation.stats().elapsedMs() + baselineTripEnds.stats().elapsedMs();
        long optimizedMs = optimized.stats().elapsedMs();

        assertEquivalent(baselinePopulation.population(), optimized.population(), streets);
        assertEquivalent(baselineTripEnds.tripEnds(), optimized.tripEnds(), streets);
        assertTrue(optimizedMs > 0);
        assertTrue(baselineMs > 0);

        double speedup = baselineMs / (double) optimizedMs;
        double reduction = (1.0 - optimizedMs / (double) baselineMs) * 100.0;
        long plansBytes;
        try {
            plansBytes = Files.size(Path.of(data.getOutfile().getPlans()));
        } catch (Exception ignored) {
            plansBytes = -1;
        }
        System.out.printf(
                "[plans-performance] file=%s, compressed=%.1fMB, persons=%d, workers=%d | "
                        + "baseline(two serial scans)=%dms, optimized(one shared scan)=%dms, "
                        + "speedup=%.2fx, reduction=%.1f%%, streetCache=%d/%d(%d%%)%n",
                data.getOutfile().getPlans(), plansBytes / 1024.0 / 1024.0,
                optimized.stats().persons(), optimized.stats().workers(), baselineMs, optimizedMs,
                speedup, reduction, optimized.stats().streetCacheHits(),
                optimized.stats().streetCacheLookups(), optimized.stats().streetCacheHitPercent());
    }

    private static void assertEquivalent(MatsimPopulationCache.Aggregation expected,
                                         MatsimPopulationCache.Aggregation actual,
                                         MatsimPopulationCache.StreetIndex streets) {
        assertEquals(expected.persons, actual.persons);
        assertEquals(expected.homePersons, actual.homePersons);
        assertEquals(expected.workPersons, actual.workPersons);
        assertEquals(expected.unassignedHome, actual.unassignedHome);
        assertEquals(expected.unassignedWork, actual.unassignedWork);
        assertEquals(expected.homeTypes, actual.homeTypes);
        assertEquals(expected.workTypes, actual.workTypes);
        assertArrayEquals(expected.streetHome, actual.streetHome);
        assertArrayEquals(expected.streetWork, actual.streetWork);
        assertArrayEquals(
                MatsimPopulationCache.encodeGrid(expected.homeCells, expected.workCells,
                        expected.mercCellSize, streets),
                MatsimPopulationCache.encodeGrid(actual.homeCells, actual.workCells,
                        actual.mercCellSize, streets));
    }

    private static void assertEquivalent(MatsimTripEndsCache.Aggregation expected,
                                         MatsimTripEndsCache.Aggregation actual,
                                         MatsimPopulationCache.StreetIndex streets) {
        assertEquals(expected.persons, actual.persons);
        assertEquals(expected.journeys, actual.journeys);
        assertEquals(expected.riders, actual.riders);
        assertEquals(expected.originPoints, actual.originPoints);
        assertEquals(expected.destPoints, actual.destPoints);
        assertEquals(expected.unassignedOrigin, actual.unassignedOrigin);
        assertEquals(expected.unassignedDest, actual.unassignedDest);
        assertArrayEquals(expected.streetOrigin, actual.streetOrigin);
        assertArrayEquals(expected.streetDest, actual.streetDest);
        assertArrayEquals(
                MatsimPopulationCache.encodeGrid(expected.originCells, expected.destCells,
                        expected.mercCellSize, streets),
                MatsimPopulationCache.encodeGrid(actual.originCells, actual.destCells,
                        actual.mercCellSize, streets));
    }
}
