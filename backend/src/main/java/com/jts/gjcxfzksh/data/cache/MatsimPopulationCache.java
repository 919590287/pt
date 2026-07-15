package com.jts.gjcxfzksh.data.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.optimization.util.GeoUtil;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.index.strtree.STRtree;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

/**
 * 公交出行监测 · 人口分布监测缓存家族（设计文档《公交出行监测人口分布模块设计方案》§1/§2/§3）。
 * <p>
 * 模型加载时从 MATSim plans 抽取每人的居住点 / 就业点，产出三个工件
 * （全部为模型抽样量，前端直出不扩样；scale 仅作元信息下发）：
 * <ul>
 *   <li>{@code population-summary.json}：总量指标 + 活动类型集合（右侧首屏直出）；</li>
 *   <li>{@code population-grid.bin}：100 米栅格二进制表（§3 前后端二进制契约，行式 16B/cell）；</li>
 *   <li>{@code population-streets.json}：176 街道全量统计（含 0 值）+ totals。</li>
 * </ul>
 * 口径契约（§1，任何改动必须 bump {@link #POPULATION_CACHE_VERSION}）：
 * <ul>
 *   <li>居住点 = selectedPlan（空回退 getPlans().get(0)，照 ScenarioCutService.processPerson）中
 *       第一个 {@code type.toLowerCase().startsWith("home")} 且坐标非空的活动；就业点同理取 {@code work*}
 *       前缀（无 work 活动的人不计入就业人口）；type 含 {@code interaction} 的中转活动一律跳过；
 *       坐标为 null 的活动跳过该点（继续向后找同前缀活动）。</li>
 *   <li>栅格：EPSG:3857 平面上 {@code mercCellSize = 100 / cos(centerLat)}（centerLat 由
 *       {@code data.getCenter()} 反算，纬度公式与 MatsimTransferCache.groundDistanceMeters 同源），
 *       cell 索引 i=floor(x/cs)、j=floor(y/cs)。</li>
 *   <li>街道归属：内嵌资源 {@code geo/gz_streets_wgs84.geojson.gz}（广州 176 街道，WGS84），
 *       构建时经 GeoUtil.lngLatToMercator 转 3857 建 STRtree + PreparedGeometry 做点面归属；
 *       多候选按要素文件序取最小索引（结果可复现）；全部未命中计入 unassignedHome/unassignedWork。
 *       街道资源内容变更（sha256 指纹）同样使缓存失效。</li>
 * </ul>
 * 数据来源：非大模型直接读 {@code data.getPopulation()}（坐标已被 Datasource 统一转换到 EPSG:3857）；
 * 大模型用 StreamingPopulationReader 流式读 outfile.getPlans()，坐标转换复刻 Datasource.ctf 语义
 * （population 级 coordinateReferenceSystem 属性在首个 person 回调前已被解析，首回调时懒解析）。
 */
@Slf4j
public final class MatsimPopulationCache {

    // v1: 首版口径：home*/work* 前缀 + interaction 排除 + selectedPlan 回退首 plan；
    //     100m 栅格（mercCellSize 按模型中心纬度修正）；街道归属=点面 point-in-polygon（多候选取最小要素索引）。
    public static final String POPULATION_CACHE_VERSION = "population-v1";

    /** 栅格边长（地面米，§1）。栅格实际投影边长 mercCellSize 随模型中心纬度修正。 */
    static final double CELL_SIZE_METERS = 100.0;

    // ===== population-grid.bin 布局常量（§3，前后端二进制契约，禁止偏离；小端）=====
    static final byte[] BIN_MAGIC = {'P', 'G', 'R', 'D'};
    static final int BIN_VERSION = 1;
    /** 头部字节数：magic(4) + version u16(2) + count u32(4) + mercCellSize f64(8)。 */
    static final int BIN_HEADER_BYTES = 18;
    /** 每 cell 字节数（行式）：i i32 + j i32 + home u32 + work u32。 */
    static final int BIN_BYTES_PER_CELL = 16;

    /** 内嵌街道面资源（WGS84，176 街道，属性 code/name/district/areaKm2）。 */
    static final String STREETS_RESOURCE = "/geo/gz_streets_wgs84.geojson.gz";

    private static final String SUMMARY_FILE = "population-summary.json";
    private static final String STREETS_FILE = "population-streets.json";
    private static final String GRID_FILE = "population-grid.bin";
    private static final String MANIFEST_FILE = "manifest.json";

    // 项目统一投影 epsg:3857，纬度反算与 MatsimTransferCache.groundDistanceMeters 同源：lat = atan(sinh(y/R))。
    private static final double EARTH_RADIUS = 6378137.0;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    /** summary/streets 读取记忆化：2 模型 × 2 工件（模式照 MatsimTransferCache.MEMORY_CACHE）。 */
    private static final Map<String, Map<String, Object>> MEMORY_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                    return size() > 4;
                }
            }
    );

    private static final GeometryFactory GF = new GeometryFactory();
    /** 街道面索引与原始 gz 字节的进程级单例（模型无关，全模型共享；JTS 1.20 PreparedGeometry 读线程安全）。 */
    private static volatile StreetIndex streetIndexSingleton;
    private static volatile byte[] streetsGzBytesSingleton;
    private static volatile String streetsTagSingleton;

    private MatsimPopulationCache() {
    }

    // ===================================================================================
    // 对外入口（模式照 MatsimTransferCache）
    // ===================================================================================

    public static void prepareOnModelLoad(MatsimData data) {
        // per-model 锁：模型 A 构建期间不阻塞模型 B；同一模型串行保证幂等
        synchronized (ModelBuildLocks.lockFor("population", data)) {
            ensurePopulationCacheLocked(data);
        }
    }

    public static boolean isReady(MatsimData data) {
        if (!Files.exists(manifestPath(data)) || !Files.exists(summaryPath(data))
                || !Files.exists(streetsPath(data)) || !Files.exists(gridPath(data))) {
            return false;
        }
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath(data).toFile(), MAP_TYPE);
            return "ready".equals(manifest.get("status"))
                    && POPULATION_CACHE_VERSION.equals(manifest.get("cacheVersion"))
                    && sameSources(data, manifest);
        } catch (Exception e) {
            log.warn("人口分布缓存状态读取失败: {}", manifestPath(data), e);
            return false;
        }
    }

    /** 总量指标 + 活动类型集合（POST /pt/population/summary）。未就绪返回 generating 态。 */
    public static Map<String, Object> readPopulationSummary(MatsimData data) {
        if (!isReady(data)) {
            return generatingPayload();
        }
        try {
            return loadCachedJson(summaryPath(data));
        } catch (Exception e) {
            log.warn("读取人口分布汇总缓存失败: model={}, path={}", data.getName(), summaryPath(data), e);
            return Map.of();
        }
    }

    /** 176 街道全量统计（POST /pt/population/streets）。未就绪返回 generating 态。 */
    public static Map<String, Object> readPopulationStreets(MatsimData data) {
        if (!isReady(data)) {
            return generatingPayload();
        }
        try {
            return loadCachedJson(streetsPath(data));
        } catch (Exception e) {
            log.warn("读取人口分布街道缓存失败: model={}, path={}", data.getName(), streetsPath(data), e);
            return Map.of();
        }
    }

    /** 栅格二进制表字节（GET /pt/population/grid.bin）。未就绪返回 null（Controller 侧 404）。 */
    public static byte[] readGridBytes(MatsimData data) {
        if (!isReady(data)) {
            return null;
        }
        try {
            return Files.readAllBytes(gridPath(data));
        } catch (Exception e) {
            log.warn("读取人口分布栅格表失败: model={}, path={}", data.getName(), gridPath(data), e);
            return null;
        }
    }

    /**
     * grid.bin 的强校验 ETag 内容：manifest 的 sourceFingerprint + cacheVersion 哈希
     * （照 MatsimTransferCache.eventsBinTag）。未就绪返回 null。
     * generatedAt 不参与哈希——同源同版本重建不应打穿客户端缓存。
     * 与 transfer 的差异：本缓存的源指纹含街道资源键（streets 前缀），一并纳入哈希。
     */
    public static String gridBinTag(MatsimData data) {
        if (!isReady(data)) {
            return null;
        }
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath(data).toFile(), MAP_TYPE);
            StringBuilder content = new StringBuilder(POPULATION_CACHE_VERSION);
            new TreeMap<>(manifest).forEach((key, value) -> {
                if (key.endsWith("File") || key.endsWith("Modified") || key.endsWith("Size")
                        || key.startsWith("streets")) {
                    content.append('|').append(key).append('=').append(value);
                }
            });
            return sha256Hex(content.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        } catch (Exception e) {
            log.warn("人口分布栅格表 ETag 计算失败: {}", manifestPath(data), e);
            return null;
        }
    }

    /**
     * 内嵌街道面 gz 原始字节（GET /pt/population/streets.geojson，模型无关，预压缩直出）。
     * 返回共享缓存数组的拷贝，调用方可安全持有。
     */
    public static byte[] streetsGeojsonGzBytes() {
        return loadStreetsGzBytes().clone();
    }

    /** streets.geojson 的强校验 ETag 内容：资源字节 sha256 前 16 位（资源变更即失效）。 */
    public static String streetsGeojsonTag() {
        loadStreetsGzBytes();
        return streetsTagSingleton;
    }

    private static Map<String, Object> generatingPayload() {
        return Map.of(
                "status", "generating",
                "cacheVersion", POPULATION_CACHE_VERSION,
                "message", "人口分布缓存正在后台生成"
        );
    }

    // ===================================================================================
    // 构建编排
    // ===================================================================================

    private static void ensurePopulationCacheLocked(MatsimData data) {
        if (isReady(data)) {
            return; // 幂等：已就绪直接跳过
        }
        try {
            Files.createDirectories(cacheDir(data));
            long start = System.currentTimeMillis();
            Artifacts artifacts = buildArtifacts(data);
            // 工件先落盘、manifest 最后写：manifest=ready 即三工件必然齐备
            writeBytesAtomic(gridPath(data), artifacts.gridBin);
            writeJsonAtomic(streetsPath(data), artifacts.streets);
            writeJsonAtomic(summaryPath(data), artifacts.summary);
            writeJsonAtomic(manifestPath(data), manifest(data, true));
            MEMORY_CACHE.remove(cacheKey(summaryPath(data)));
            MEMORY_CACHE.remove(cacheKey(streetsPath(data)));
            log.info("人口分布缓存生成完成: model={}, persons={}, home={}, work={}, unassigned={}/{}, "
                            + "gridCells={}, bin={}B, 耗时={}ms",
                    data.getName(), artifacts.summary.get("persons"), artifacts.summary.get("homePersons"),
                    artifacts.summary.get("workPersons"), artifacts.summary.get("unassignedHome"),
                    artifacts.summary.get("unassignedWork"), artifacts.summary.get("gridCells"),
                    artifacts.gridBin.length, System.currentTimeMillis() - start);
        } catch (Exception e) {
            try {
                Files.createDirectories(cacheDir(data));
                writeJsonAtomic(manifestPath(data), manifest(data, false));
            } catch (Exception ignored) {
            }
            throw new RuntimeException("人口分布缓存生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从模型抽取居住/就业点并聚合。非大模型读内存 population（坐标已是 3857），
     * 大模型流式读 plans 文件。空人口模型产出全零工件（176 街道仍全量下发）。
     */
    private static Artifacts buildArtifacts(MatsimData data) {
        StreetIndex streets = streetIndex();
        Aggregation aggregation = new Aggregation(mercCellSize(data.getCenter()), streets);
        if (data.isLargeModel()) {
            streamPlans(data, aggregation);
        } else {
            Population population = data.getScenario() == null ? null : data.getPopulation();
            if (population != null) {
                for (Person person : population.getPersons().values()) {
                    // 非大模型坐标已被 Datasource.loadConfig 统一转换到 EPSG:3857，无需再转
                    aggregation.acceptPerson(person, null);
                }
            }
        }
        if (aggregation.transformFailures > 0) {
            log.warn("人口分布缓存坐标转换失败点已跳过: model={}, count={}", data.getName(), aggregation.transformFailures);
        }
        return assemble(aggregation, streets, effectiveSampleRate(data));
    }

    /**
     * 大模型流式抽取（streamPlans 写法照 ScenarioCutService.streamPlans）：
     * 读入端关闭坐标自动转换（global CRS 置 null，保持文件原始坐标），
     * 在首个 person 回调时按 Datasource.ctf 同源语义懒解析转换器——
     * population 级 coordinateReferenceSystem 属性位于 plans XML 头部，此时已可读。
     */
    private static void streamPlans(MatsimData data, Aggregation out) {
        String plansFile = data.getOutfile() == null ? null : data.getOutfile().getPlans();
        if (plansFile == null || plansFile.isBlank() || !Files.exists(Path.of(plansFile))) {
            log.warn("人口分布缓存未找到 plans 文件，按空人口处理: model={}, plans={}", data.getName(), plansFile);
            return;
        }
        Config readCfg = ConfigUtils.createConfig();
        // 关闭读入时的坐标自动转换（保持原始坐标，照 ScenarioCutService.newScenario；
        // 默认 config 的 global CRS 是 Atlantis，不清空会触发 MATSim 内部错误转换）
        readCfg.global().setCoordinateSystem(null);
        Scenario readScenario = ScenarioUtils.createScenario(readCfg);
        // 大模型 data.getConfig() 可能为 null（ModelCacheManager.componentCachesReady 的裸 MatsimData），
        // 此时退化为仅按 population 属性 CRS 判定——与 Datasource.ctf 的兜底链一致
        String globalCRS = data.getConfig() == null ? null : data.getConfig().global().getCoordinateSystem();
        String inputCRS = data.getConfig() == null ? null : data.getConfig().plans().getInputCRS();
        CoordinateTransformation[] lazyCtf = new CoordinateTransformation[1];
        boolean[] ctfResolved = new boolean[1];
        StreamingPopulationReader reader = new StreamingPopulationReader(readScenario);
        reader.addAlgorithm(person -> {
            if (!ctfResolved[0]) {
                String planCRS = (String) readScenario.getPopulation().getAttributes()
                        .getAttribute("coordinateReferenceSystem");
                lazyCtf[0] = ctf(globalCRS, inputCRS, planCRS);
                ctfResolved[0] = true;
                log.info("人口分布缓存流式读取 plans: model={}, planCRS={}, inputCRS={}, globalCRS={}, 转换={}",
                        data.getName(), planCRS, inputCRS, globalCRS, lazyCtf[0] != null);
            }
            out.acceptPerson(person, lazyCtf[0]);
            if (out.persons % 1_000_000 == 0) {
                log.info("人口分布缓存流式读取进度: model={}, persons={}", data.getName(), out.persons);
            }
        });
        reader.readFile(plansFile);
    }

    // ===================================================================================
    // 口径工具（复刻来源见各注释；不得反向修改被复刻方）
    // ===================================================================================

    /**
     * 坐标系转换器选择：口径复刻自 Datasource.ctf（private 不可达，勿改其可见性）。
     * 优先级：xml 文件属性 CRS → 模块 inputCRS → 全局 CRS；目标恒为 epsg:3857；
     * 已是 3857 或三者皆空返回 null（不转换）。
     */
    static CoordinateTransformation ctf(String globalCRS, String inputCRS, String crs) {
        String projectCrs = "epsg:3857";
        if (crs != null) { // xml 文件中的坐标系 coordinateReferenceSystem
            if (crs.equalsIgnoreCase(projectCrs)) {
                return null;
            }
            return TransformationFactory.getCoordinateTransformation(crs, projectCrs);
        }
        if (inputCRS != null) { // 当前模块坐标系
            if (inputCRS.equalsIgnoreCase(projectCrs)) {
                return null;
            }
            return TransformationFactory.getCoordinateTransformation(inputCRS, projectCrs);
        }
        if (globalCRS != null) { // 全局坐标系
            if (globalCRS.equalsIgnoreCase(projectCrs)) {
                return null;
            }
            return TransformationFactory.getCoordinateTransformation(globalCRS, projectCrs);
        }
        return null;
    }

    /**
     * 人口抽样比例：口径复刻自 PTDataServiceImpl.effectiveSampleRate（desc.json 的 scale，
     * 只接受 (0,1]，异常值按全样本 1.0 处理，宁可不扩样也不放大指标）。
     */
    static double effectiveSampleRate(MatsimData data) {
        double scale = data.getScale();
        return scale > 0 && scale <= 1.0 ? scale : 1.0;
    }

    /**
     * 栅格投影边长：{@code 100 / cos(centerLat)}（§1）。centerLat 由模型中心 3857 y 反算，
     * 公式与 MatsimTransferCache.groundDistanceMeters 同源：lat = atan(sinh(y/R))。
     * center 为空（理论上只在合成测试出现）按赤道处理（cs=100）。
     */
    static double mercCellSize(Coord center) {
        double y = center == null ? 0.0 : center.getY();
        double latRad = Math.atan(Math.sinh(y / EARTH_RADIUS));
        return CELL_SIZE_METERS / Math.cos(latRad);
    }

    /** cell 键：i=floor(x/cs)、j=floor(y/cs)（负坐标 floor 语义），打包进 long。 */
    static long cellKey(double x, double y, double cellSize) {
        return packCell((int) Math.floor(x / cellSize), (int) Math.floor(y / cellSize));
    }

    /** (i, j) → long：高 32 位 i、低 32 位 j（j 取补码位模式，负数不串位）。 */
    static long packCell(int i, int j) {
        return ((long) i << 32) | (j & 0xffffffffL);
    }

    static int cellI(long key) {
        return (int) (key >> 32);
    }

    static int cellJ(long key) {
        return (int) key;
    }

    // ===================================================================================
    // 居住/就业点抽取与聚合（§1 口径）
    // ===================================================================================

    /**
     * 单遍聚合器：每人抽取居住/就业点后立即完成栅格分箱 + 街道归属，不留存点集
     * （大模型千万级 person 流式处理的内存前提）。
     */
    static final class Aggregation {
        final double mercCellSize;
        /** 街道面索引；null 仅为纯栅格单测便利（所有点计入 unassigned）。 */
        private final StreetIndex streets;
        final Long2IntOpenHashMap homeCells = new Long2IntOpenHashMap();
        final Long2IntOpenHashMap workCells = new Long2IntOpenHashMap();
        final int[] streetHome;
        final int[] streetWork;
        final TreeSet<String> homeTypes = new TreeSet<>();
        final TreeSet<String> workTypes = new TreeSet<>();
        long persons;
        long homePersons;
        long workPersons;
        long unassignedHome;
        long unassignedWork;
        long transformFailures;

        Aggregation(double mercCellSize, StreetIndex streets) {
            this.mercCellSize = mercCellSize;
            this.streets = streets;
            this.streetHome = new int[streets == null ? 0 : streets.size()];
            this.streetWork = new int[streets == null ? 0 : streets.size()];
        }

        /**
         * 抽取一个 person：selectedPlan 空回退首 plan（照 ScenarioCutService.processPerson）；
         * 跳过 interaction 中转活动；home / work 前缀各取第一个坐标非空的活动；
         * 活动类型集合（homeTypes/workTypes）收集全部匹配前缀的原始 type。
         */
        void acceptPerson(Person person, CoordinateTransformation ctf) {
            persons++;
            Plan plan = person.getSelectedPlan();
            if (plan == null && !person.getPlans().isEmpty()) {
                plan = person.getPlans().get(0);
            }
            if (plan == null) {
                return;
            }
            Coord home = null;
            Coord work = null;
            for (PlanElement element : plan.getPlanElements()) {
                if (!(element instanceof Activity act)) {
                    continue;
                }
                String type = act.getType();
                if (type == null) {
                    continue;
                }
                String lower = type.toLowerCase(Locale.ROOT);
                if (lower.contains("interaction")) {
                    continue; // pt interaction 等中转活动（§1）
                }
                if (lower.startsWith("home")) {
                    homeTypes.add(type);
                    if (home == null) {
                        home = transformedCoord(act.getCoord(), ctf);
                    }
                } else if (lower.startsWith("work")) {
                    workTypes.add(type);
                    if (work == null) {
                        work = transformedCoord(act.getCoord(), ctf);
                    }
                }
            }
            if (home != null) {
                homePersons++;
                addPoint(home, true);
            }
            if (work != null) {
                workPersons++;
                addPoint(work, false);
            }
        }

        /** 坐标为 null 跳过该点（返回 null 让上层继续向后找）；转换失败同样跳过（与坐标缺失同待遇）。 */
        private Coord transformedCoord(Coord coord, CoordinateTransformation ctf) {
            if (coord == null) {
                return null;
            }
            if (ctf == null) {
                return coord;
            }
            try {
                return ctf.transform(coord);
            } catch (Exception e) {
                transformFailures++;
                return null;
            }
        }

        private void addPoint(Coord coord, boolean isHome) {
            (isHome ? homeCells : workCells).addTo(cellKey(coord.getX(), coord.getY(), mercCellSize), 1);
            int streetIdx = streets == null ? -1 : streets.locate(coord.getX(), coord.getY());
            if (streetIdx >= 0) {
                (isHome ? streetHome : streetWork)[streetIdx]++;
            } else if (isHome) {
                unassignedHome++;
            } else {
                unassignedWork++;
            }
        }
    }

    // ===================================================================================
    // 街道面索引（模型无关，进程级共享）
    // ===================================================================================

    /** 街道静态属性（照资源 properties，areaKm2 直接采信资源值，不重算）。 */
    record StreetRef(String code, String name, String district, double areaKm2) {
    }

    /** 街道点面归属索引：STRtree 粗筛 + PreparedGeometry 精判；要素顺序 = 资源文件序。 */
    static final class StreetIndex {
        private final List<StreetRef> streets;
        private final Geometry[] geometries;
        private final PreparedGeometry[] prepared;
        private final STRtree tree;

        private StreetIndex(List<StreetRef> streets, Geometry[] geometries) {
            this.streets = streets;
            this.geometries = geometries;
            this.prepared = new PreparedGeometry[geometries.length];
            this.tree = new STRtree();
            for (int i = 0; i < geometries.length; i++) {
                this.prepared[i] = GeoUtil.prepare(geometries[i]);
                this.tree.insert(geometries[i].getEnvelopeInternal(), i);
            }
            this.tree.build(); // 构建后 STRtree 只读查询线程安全
        }

        int size() {
            return streets.size();
        }

        StreetRef street(int index) {
            return streets.get(index);
        }

        /** 测试用：街道 3857 几何（interiorPoint 反查归属等）。 */
        Geometry geometry(int index) {
            return geometries[index];
        }

        /**
         * 点面归属（EPSG:3857）：返回街道要素索引，未命中返回 -1。
         * 边界点按 intersects（含边界）判定；多候选（共享边界/资源面重叠）取最小要素索引，结果可复现。
         */
        int locate(double x, double y) {
            @SuppressWarnings("unchecked")
            List<Integer> candidates = tree.query(new Envelope(x, x, y, y));
            if (candidates.isEmpty()) {
                return -1;
            }
            if (candidates.size() > 1) {
                candidates = new ArrayList<>(candidates);
                Collections.sort(candidates);
            }
            Point point = GF.createPoint(new Coordinate(x, y));
            for (int candidate : candidates) {
                if (prepared[candidate].intersects(point)) {
                    return candidate;
                }
            }
            return -1;
        }
    }

    /** 进程级街道索引单例（懒加载 + double-checked）。资源缺失/畸形直接抛错，构建 fail-fast。 */
    static StreetIndex streetIndex() {
        StreetIndex local = streetIndexSingleton;
        if (local != null) {
            return local;
        }
        synchronized (MatsimPopulationCache.class) {
            if (streetIndexSingleton == null) {
                long start = System.currentTimeMillis();
                streetIndexSingleton = loadStreetIndex(loadStreetsGzBytes());
                log.info("街道面索引加载完成: features={}, 耗时={}ms",
                        streetIndexSingleton.size(), System.currentTimeMillis() - start);
            }
            return streetIndexSingleton;
        }
    }

    /**
     * 解析街道 GeoJSON（gz 字节）并建 3857 索引：环坐标经 GeoUtil.lngLatToMercator 投影，
     * Polygon/MultiPolygon 均支持（含内环孔洞）；无效面（自相交等）用 buffer(0) 修复
     * （资源现状：嘉禾街 1 例无效）。
     */
    @SuppressWarnings("unchecked")
    static StreetIndex loadStreetIndex(byte[] gzBytes) {
        Map<String, Object> collection;
        try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(gzBytes))) {
            collection = JSON.readValue(in, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("街道面资源解析失败: " + STREETS_RESOURCE, e);
        }
        Object featuresValue = collection.get("features");
        if (!(featuresValue instanceof List<?> features) || features.isEmpty()) {
            throw new IllegalStateException("街道面资源不含 features: " + STREETS_RESOURCE);
        }
        List<StreetRef> streets = new ArrayList<>(features.size());
        Geometry[] geometries = new Geometry[features.size()];
        for (int i = 0; i < features.size(); i++) {
            Map<String, Object> feature = (Map<String, Object>) features.get(i);
            Map<String, Object> properties = (Map<String, Object>) feature.get("properties");
            Map<String, Object> geometry = (Map<String, Object>) feature.get("geometry");
            streets.add(new StreetRef(
                    String.valueOf(properties.get("code")),
                    String.valueOf(properties.get("name")),
                    String.valueOf(properties.get("district")),
                    ((Number) properties.get("areaKm2")).doubleValue()));
            String type = String.valueOf(geometry.get("type"));
            Object coordinates = geometry.get("coordinates");
            Geometry geom;
            if ("Polygon".equals(type)) {
                geom = polygon((List<Object>) coordinates);
            } else if ("MultiPolygon".equals(type)) {
                List<Object> polygons = (List<Object>) coordinates;
                Polygon[] parts = new Polygon[polygons.size()];
                for (int p = 0; p < polygons.size(); p++) {
                    parts[p] = polygon((List<Object>) polygons.get(p));
                }
                geom = GF.createMultiPolygon(parts);
            } else {
                throw new IllegalStateException("街道面资源含不支持的几何类型: " + type);
            }
            if (!geom.isValid()) {
                geom = geom.buffer(0); // 自相交等问题的标准修复（照 GeoUtil.toPolygon）
            }
            geometries[i] = geom;
        }
        return new StreetIndex(streets, geometries);
    }

    /** GeoJSON Polygon coordinates（[环][点][lng,lat]）→ 3857 JTS Polygon；环 0 外壳、其余孔洞。 */
    @SuppressWarnings("unchecked")
    private static Polygon polygon(List<Object> rings) {
        LinearRing shell = linearRing((List<Object>) rings.get(0));
        LinearRing[] holes = new LinearRing[rings.size() - 1];
        for (int r = 1; r < rings.size(); r++) {
            holes[r - 1] = linearRing((List<Object>) rings.get(r));
        }
        return GF.createPolygon(shell, holes);
    }

    @SuppressWarnings("unchecked")
    private static LinearRing linearRing(List<Object> ring) {
        List<Coordinate> coords = new ArrayList<>(ring.size() + 1);
        for (Object pointValue : ring) {
            List<Object> point = (List<Object>) pointValue;
            double[] merc = GeoUtil.lngLatToMercator(
                    ((Number) point.get(0)).doubleValue(), ((Number) point.get(1)).doubleValue());
            coords.add(new Coordinate(merc[0], merc[1]));
        }
        Coordinate first = coords.get(0);
        Coordinate last = coords.get(coords.size() - 1);
        if (first.x != last.x || first.y != last.y) {
            coords.add(new Coordinate(first.x, first.y)); // GeoJSON 环应闭合，防御性补闭合
        }
        return GF.createLinearRing(coords.toArray(new Coordinate[0]));
    }

    /** 内嵌 gz 资源字节 + sha256 标签的懒加载单例。 */
    private static byte[] loadStreetsGzBytes() {
        byte[] local = streetsGzBytesSingleton;
        if (local != null) {
            return local;
        }
        synchronized (MatsimPopulationCache.class) {
            if (streetsGzBytesSingleton == null) {
                try (InputStream in = MatsimPopulationCache.class.getResourceAsStream(STREETS_RESOURCE)) {
                    if (in == null) {
                        throw new IllegalStateException("街道面资源缺失: " + STREETS_RESOURCE);
                    }
                    byte[] bytes = in.readAllBytes();
                    streetsTagSingleton = sha256Hex(bytes).substring(0, 16);
                    streetsGzBytesSingleton = bytes;
                } catch (IllegalStateException e) {
                    throw e;
                } catch (Exception e) {
                    throw new IllegalStateException("街道面资源读取失败: " + STREETS_RESOURCE, e);
                }
            }
            return streetsGzBytesSingleton;
        }
    }

    // ===================================================================================
    // 组装：grid.bin / streets.json / summary.json（§2/§3）
    // ===================================================================================

    /** 三工件组装结果。 */
    static final class Artifacts {
        final byte[] gridBin;
        final Map<String, Object> summary;
        final Map<String, Object> streets;

        private Artifacts(byte[] gridBin, Map<String, Object> summary, Map<String, Object> streets) {
            this.gridBin = gridBin;
            this.summary = summary;
            this.streets = streets;
        }
    }

    static Artifacts assemble(Aggregation aggregation, StreetIndex streets, double scale) {
        byte[] gridBin = encodeGrid(aggregation.homeCells, aggregation.workCells, aggregation.mercCellSize);
        int gridCells = (gridBin.length - BIN_HEADER_BYTES) / BIN_BYTES_PER_CELL;
        return new Artifacts(gridBin, buildSummary(aggregation, gridCells, scale),
                buildStreets(aggregation, streets));
    }

    /**
     * population-grid.bin（§3，小端）：
     * header = magic "PGRD" + version u16(=1) + count u32 + mercCellSize f64（共 18B）；
     * record × count（16B/cell，行式）= i i32, j i32, home u32, work u32（抽样人数）。
     * cell 写入序按打包键升序（i 升序，同 i 内 j 按无符号序）——仅为构建可复现，契约不约束顺序。
     */
    static byte[] encodeGrid(Long2IntOpenHashMap homeCells, Long2IntOpenHashMap workCells, double mercCellSize) {
        LongOpenHashSet keySet = new LongOpenHashSet(homeCells.keySet());
        keySet.addAll(workCells.keySet());
        long[] keys = keySet.toLongArray();
        Arrays.sort(keys);
        ByteBuffer buffer = ByteBuffer.allocate(BIN_HEADER_BYTES + BIN_BYTES_PER_CELL * keys.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(BIN_MAGIC);
        buffer.putShort((short) BIN_VERSION);
        buffer.putInt(keys.length);
        buffer.putDouble(mercCellSize);
        for (long key : keys) {
            buffer.putInt(cellI(key));
            buffer.putInt(cellJ(key));
            buffer.putInt(homeCells.get(key)); // 缺省 0（fastutil 默认返回值）
            buffer.putInt(workCells.get(key));
        }
        return buffer.array();
    }

    /** population-summary.json（§2 表；量为模型抽样口径，前端直出不扩样；scale 仅元信息）。 */
    private static Map<String, Object> buildSummary(Aggregation aggregation, int gridCells, double scale) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "ready");
        summary.put("cacheVersion", POPULATION_CACHE_VERSION);
        summary.put("generatedAt", System.currentTimeMillis());
        summary.put("scale", scale);
        summary.put("cellSizeMeters", (int) CELL_SIZE_METERS);
        summary.put("mercCellSize", aggregation.mercCellSize);
        summary.put("gridCells", gridCells);
        summary.put("persons", aggregation.persons);
        summary.put("homePersons", aggregation.homePersons);
        summary.put("workPersons", aggregation.workPersons);
        summary.put("unassignedHome", aggregation.unassignedHome);
        summary.put("unassignedWork", aggregation.unassignedWork);
        summary.put("homeTypes", new ArrayList<>(aggregation.homeTypes));
        summary.put("workTypes", new ArrayList<>(aggregation.workTypes));
        return summary;
    }

    /**
     * population-streets.json（§2 表）：176 街道全量（含 0 值，顺序 = 资源文件序）+ totals。
     * 对账恒等式（§6）：sum(streets.home) + unassignedHome == homePersons（work 同理）。
     */
    private static Map<String, Object> buildStreets(Aggregation aggregation, StreetIndex streets) {
        List<Map<String, Object>> rows = new ArrayList<>(streets.size());
        long totalHome = 0;
        long totalWork = 0;
        for (int i = 0; i < streets.size(); i++) {
            StreetRef street = streets.street(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", street.code());
            row.put("name", street.name());
            row.put("district", street.district());
            row.put("areaKm2", street.areaKm2());
            row.put("home", aggregation.streetHome[i]);
            row.put("work", aggregation.streetWork[i]);
            rows.add(row);
            totalHome += aggregation.streetHome[i];
            totalWork += aggregation.streetWork[i];
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("home", totalHome);
        totals.put("work", totalWork);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("streets", rows);
        payload.put("totals", totals);
        return payload;
    }

    // ===================================================================================
    // manifest 与文件读写（模式照 MatsimTransferCache）
    // ===================================================================================

    private static Map<String, Object> manifest(MatsimData data, boolean ready) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", ready ? "ready" : "failed");
        result.put("cacheVersion", POPULATION_CACHE_VERSION);
        result.put("generatedAt", System.currentTimeMillis());
        sourceFingerprint(data, result);
        return result;
    }

    /**
     * 源指纹：plans（居住/就业点唯一数据源；非大模型的内存 population 亦源于此文件）
     * + 街道资源标识（路径 + 内容 sha256，资源升级即失效重建）。
     */
    private static void sourceFingerprint(MatsimData data, Map<String, Object> result) {
        putFileFingerprint(result, "plans", data.getOutfile() == null ? null : data.getOutfile().getPlans());
        result.put("streetsResource", STREETS_RESOURCE);
        result.put("streetsSha256", streetsGeojsonTag());
    }

    private static void putFileFingerprint(Map<String, Object> result, String key, String filePath) {
        result.put(key + "File", filePath);
        result.put(key + "Modified", lastModified(filePath));
        result.put(key + "Size", fileSize(filePath));
    }

    private static boolean sameSources(MatsimData data, Map<String, Object> manifest) {
        Map<String, Object> current = new LinkedHashMap<>();
        sourceFingerprint(data, current);
        for (Map.Entry<String, Object> entry : current.entrySet()) {
            Object oldValue = manifest.get(entry.getKey());
            if (entry.getValue() instanceof Number number) {
                if (!(oldValue instanceof Number oldNumber) || oldNumber.longValue() != number.longValue()) {
                    return false;
                }
            } else if (!String.valueOf(entry.getValue()).equals(String.valueOf(oldValue))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> loadCachedJson(Path path) {
        String cacheKey = cacheKey(path);
        Map<String, Object> cached = MEMORY_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        synchronized (MEMORY_CACHE) {
            cached = MEMORY_CACHE.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            try {
                cached = JSON.readValue(path.toFile(), MAP_TYPE);
                MEMORY_CACHE.put(cacheKey, cached);
                return cached;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static String cacheKey(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static void writeJsonAtomic(Path path, Map<String, Object> payload) throws Exception {
        Files.createDirectories(path.getParent());
        Path tmpPath = path.resolveSibling(path.getFileName() + ".tmp");
        JSON.writeValue(tmpPath.toFile(), payload);
        moveAtomic(tmpPath, path);
    }

    private static void writeBytesAtomic(Path path, byte[] bytes) throws Exception {
        Files.createDirectories(path.getParent());
        Path tmpPath = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(tmpPath, bytes);
        moveAtomic(tmpPath, path);
    }

    private static void moveAtomic(Path tmpPath, Path path) throws Exception {
        try {
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256Hex(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static Path cacheDir(MatsimData data) {
        return MatsimCachePaths.versionDir(data, POPULATION_CACHE_VERSION);
    }

    private static Path manifestPath(MatsimData data) {
        return cacheDir(data).resolve(MANIFEST_FILE);
    }

    private static Path summaryPath(MatsimData data) {
        return cacheDir(data).resolve(SUMMARY_FILE);
    }

    private static Path streetsPath(MatsimData data) {
        return cacheDir(data).resolve(STREETS_FILE);
    }

    private static Path gridPath(MatsimData data) {
        return cacheDir(data).resolve(GRID_FILE);
    }

    private static long lastModified(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return 0L;
        }
        try {
            Path path = Path.of(filePath);
            return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long fileSize(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return 0L;
        }
        try {
            Path path = Path.of(filePath);
            return Files.exists(path) ? Files.size(path) : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }
}
