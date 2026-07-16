package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.data.MatsimData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实小母本冒烟（线网优化"裁剪测试"模型，plans 原生 EPSG:3857 坐标）。
 * 默认不参与常规测试：仅在显式传 -Dpopulation.smoke=true 且数据盘已插入时运行——
 * <pre>mvn test -Dtest=MatsimPopulationCacheSmokeTest -Dpopulation.smoke=true</pre>
 * 同一份 plans 跑两遍：非大模型内存路径 vs 大模型流式路径，断言三工件字节/内容全等
 * （两条数据通路的口径一致性即冒烟的核心断言）。产物写入 java.io.tmpdir 缓存目录，不触碰数据盘。
 */
class MatsimPopulationCacheSmokeTest {

    /** USB 盘路径为项目有意保留的默认路径，勿改。 */
    private static final String OUTPUT_DIR =
            "/Volumes/USB DISK/pt_data/广州市/仿真数据/public/裁剪测试/output";

    @Test
    @EnabledIfSystemProperty(named = "population.smoke", matches = "true")
    void buildsIdenticalPopulationCacheViaInMemoryAndStreamingPaths() throws Exception {
        Path output = Path.of(OUTPUT_DIR);
        assumeTrue(Files.isDirectory(output), "数据盘未插入，跳过冒烟");

        // 1. 内存路径：读 plans 到 scenario（关闭坐标自动转换，模型原生 EPSG:3857 无需再投影）
        Config cfg = ConfigUtils.createConfig();
        cfg.global().setCoordinateSystem(null);
        MutableScenario scenario = (MutableScenario) ScenarioUtils.createScenario(cfg);
        MatsimData memData = new MatsimData("population-smoke-内存", output.toString());
        assumeTrue(memData.getOutfile().getPlans() != null, "output 缺 plans 文件，跳过冒烟");
        long readStart = System.currentTimeMillis();
        new PopulationReader(scenario).readFile(memData.getOutfile().getPlans());
        long readElapsed = System.currentTimeMillis() - readStart;

        // 模型中心：活动坐标 bbox 中心（生产由路网 bbox 提供，冒烟以同一 center 喂两条路径保证可比）
        Coord center = activityBboxCenter(scenario);
        assertNotNull(center, "plans 中没有任何带坐标的活动");

        memData.setScenario(scenario);
        memData.setCenter(center);
        memData.setScale(1.0);
        deleteRecursively(MatsimCachePaths.versionDir(memData, MatsimPopulationCache.POPULATION_CACHE_VERSION));
        long memStart = System.currentTimeMillis();
        MatsimPopulationCache.prepareOnModelLoad(memData);
        long memElapsed = System.currentTimeMillis() - memStart;
        assertTrue(MatsimPopulationCache.isReady(memData));

        // 2. 流式路径：largeModel=true 的裸 MatsimData（无 scenario/config），走 StreamingPopulationReader
        MatsimData streamData = new MatsimData("population-smoke-流式", output.toString(),
                Path.of(System.getProperty("java.io.tmpdir"), "gjcxfzksh-cache", "population-smoke-stream").toString(),
                true);
        streamData.setCenter(center);
        streamData.setScale(1.0);
        deleteRecursively(MatsimCachePaths.versionDir(streamData, MatsimPopulationCache.POPULATION_CACHE_VERSION));
        long streamStart = System.currentTimeMillis();
        MatsimPopulationCache.prepareOnModelLoad(streamData);
        long streamElapsed = System.currentTimeMillis() - streamStart;
        assertTrue(MatsimPopulationCache.isReady(streamData));

        // 3. 双路径工件全等
        Map<String, Object> memSummary = MatsimPopulationCache.readPopulationSummary(memData);
        Map<String, Object> streamSummary = MatsimPopulationCache.readPopulationSummary(streamData);
        for (String key : List.of("persons", "homePersons", "workPersons", "unassignedHome", "unassignedWork",
                "gridCells", "mercCellSize", "homeTypes", "workTypes", "scale", "cellSizeMeters")) {
            assertEquals(memSummary.get(key), streamSummary.get(key), "summary 字段不一致: " + key);
        }
        byte[] memBin = MatsimPopulationCache.readGridBytes(memData);
        byte[] streamBin = MatsimPopulationCache.readGridBytes(streamData);
        assertNotNull(memBin);
        assertArrayEquals(memBin, streamBin, "grid.bin 双路径必须逐字节一致");
        assertEquals(MatsimPopulationCache.readPopulationStreets(memData),
                MatsimPopulationCache.readPopulationStreets(streamData), "streets.json 双路径必须一致");

        // 4. 内部口径自洽：grid/streets 与 summary 对账
        long persons = ((Number) memSummary.get("persons")).longValue();
        long homePersons = ((Number) memSummary.get("homePersons")).longValue();
        long workPersons = ((Number) memSummary.get("workPersons")).longValue();
        int gridCells = ((Number) memSummary.get("gridCells")).intValue();
        assertEquals(18 + 18L * gridCells, memBin.length);
        ByteBuffer buffer = ByteBuffer.wrap(memBin).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(6);
        assertEquals(gridCells, buffer.getInt());
        buffer.position(18);
        long gridHome = 0;
        long gridWork = 0;
        for (int i = 0; i < gridCells; i++) {
            buffer.getInt();
            buffer.getInt();
            gridHome += Integer.toUnsignedLong(buffer.getInt());
            gridWork += Integer.toUnsignedLong(buffer.getInt());
            buffer.getShort(); // population-v2 格中心街道索引
        }
        assertEquals(homePersons, gridHome, "grid home 总和必须等于 homePersons");
        assertEquals(workPersons, gridWork, "grid work 总和必须等于 workPersons");

        @SuppressWarnings("unchecked")
        Map<String, Object> streets = MatsimPopulationCache.readPopulationStreets(memData);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) streets.get("streets");
        assertEquals(176, rows.size());
        long streetHome = rows.stream().mapToLong(r -> ((Number) r.get("home")).longValue()).sum();
        long streetWork = rows.stream().mapToLong(r -> ((Number) r.get("work")).longValue()).sum();
        assertEquals(homePersons, streetHome + ((Number) memSummary.get("unassignedHome")).longValue());
        assertEquals(workPersons, streetWork + ((Number) memSummary.get("unassignedWork")).longValue());
        assertNotNull(MatsimPopulationCache.gridBinTag(memData));

        System.out.printf(
                "[population-smoke] persons=%d, home=%d, work=%d, unassigned=%s/%s, gridCells=%d, bin=%dB | "
                        + "plans读取=%dms, 内存构建=%dms, 流式构建=%dms%n",
                persons, homePersons, workPersons, memSummary.get("unassignedHome"),
                memSummary.get("unassignedWork"), gridCells, memBin.length,
                readElapsed, memElapsed, streamElapsed);
    }

    /** 全活动坐标 bbox 中心（selectedPlan 空回退首 plan，与缓存提取同一回退规则）。 */
    private static Coord activityBboxCenter(MutableScenario scenario) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        boolean seen = false;
        for (Person person : scenario.getPopulation().getPersons().values()) {
            Plan plan = person.getSelectedPlan();
            if (plan == null && !person.getPlans().isEmpty()) {
                plan = person.getPlans().get(0);
            }
            if (plan == null) {
                continue;
            }
            for (PlanElement element : plan.getPlanElements()) {
                if (element instanceof Activity act && act.getCoord() != null) {
                    seen = true;
                    minX = Math.min(minX, act.getCoord().getX());
                    minY = Math.min(minY, act.getCoord().getY());
                    maxX = Math.max(maxX, act.getCoord().getX());
                    maxY = Math.max(maxY, act.getCoord().getY());
                }
            }
        }
        return seen ? new Coord((minX + maxX) / 2, (minY + maxY) / 2) : null;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
