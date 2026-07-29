package com.jts.gjcxfzksh.data.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jts.gjcxfzksh.api.common.Constant;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.optimization.util.GeoUtil;
import com.jts.gjcxfzksh.utils.TransitMetrics;
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
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

/**
 * 公交出行监测 · 人口分布监测缓存家族（设计文档《公交出行监测人口分布模块设计方案》§1/§2/§3）。
 * <p>
 * 模型加载时从 MATSim plans 抽取每人的居住点 / 就业点，产出三个工件
 * （全部为加载模型的原始量，平台不做任何抽样、扩大或缩小）：
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
    // v2: grid.bin 增加“格中心街道要素索引”列（16B→18B/cell，BIN_VERSION=2），
    //     供前端行政区过滤隐藏区外栅格；抽取/统计口径不变。
    // v3: 数量严格采用模型原始值（scale 恒为 1），并补充 plans 派生指标与显式 unsupported 语义。
    // v5: 混合公交/轨道 trip 的主方式固定为轨道优先，消除 leg 顺序依赖；
    //     覆盖率继续使用可投影的首个 home 分母并披露缺失/转换失败计数。
    // v6: 新增公共交通机动化出行分担率，分子为公交/轨道/轮渡主方式出行，
    //     分母排除步行、自行车及接驳步行。
    // v7: 历史公共交通总口径（已由 v8 的公交主方式完整出行口径替代）
    //     不再因 legacy pt 无法细分公交/轨道而错误标记 unsupported。
    // v8: 人均日出行次数改为公交主方式完整出行/homePersons，明确排除地铁、铁路与轮渡；
    //     新增 residentBusJourneys 和 residentUnresolvedLegacyPtJourneys 供严格口径审计。
    // v9: 新增高峰小汽车运行速度、公交平均换乘次数、公交—轨道接驳比例的完整 OD 分母；
    //     平均候车继续按公交上车样本加权，供大小模型共用同一缓存结果。
    public static final String POPULATION_CACHE_VERSION = "population-v9";

    /** 栅格边长（地面米，§1）。栅格实际投影边长 mercCellSize 随模型中心纬度修正。 */
    static final double CELL_SIZE_METERS = 100.0;

    // ===== population-grid.bin 布局常量（§3 + v2 增列，前后端二进制契约，禁止偏离；小端）=====
    static final byte[] BIN_MAGIC = {'P', 'G', 'R', 'D'};
    static final int BIN_VERSION = 2;
    /** 头部字节数：magic(4) + version u16(2) + count u32(4) + mercCellSize f64(8)。 */
    static final int BIN_HEADER_BYTES = 18;
    /** 每 cell 字节数（行式）：i i32 + j i32 + home u32 + work u32 + street u16（格中心归属）。 */
    static final int BIN_BYTES_PER_CELL = 18;
    /** street 列的“未命中街道”哨兵值（u16 全 1）。 */
    static final int STREET_SENTINEL = 0xFFFF;

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
        MatsimPlansDerivedCache.preparePopulationOnModelLoad(data);
    }

    public static boolean isReady(MatsimData data) {
        if (!Files.exists(manifestPath(data))) {
            return false;
        }
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath(data).toFile(), MAP_TYPE);
            if (!POPULATION_CACHE_VERSION.equals(manifest.get("cacheVersion"))
                    || !sameSources(data, manifest)) {
                return false;
            }
            if ("unsupported".equals(manifest.get("status"))) {
                return true; // 终态：源数据明确缺失，不能反复排队重建
            }
            return "ready".equals(manifest.get("status"))
                    && Files.exists(summaryPath(data))
                    && Files.exists(streetsPath(data))
                    && Files.exists(gridPath(data));
        } catch (Exception e) {
            log.warn("人口分布缓存状态读取失败: {}", manifestPath(data), e);
            return false;
        }
    }

    /** 总量指标 + 活动类型集合（POST /pt/population/summary）。未就绪返回 generating 态。 */
    public static Map<String, Object> readPopulationSummary(MatsimData data) {
        Map<String, Object> unsupported = unsupportedPayloadIfPresent(data);
        if (unsupported != null) return unsupported;
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
        Map<String, Object> unsupported = unsupportedPayloadIfPresent(data);
        if (unsupported != null) return unsupported;
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
        if (unsupportedPayloadIfPresent(data) != null || !isReady(data)) {
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
        if (unsupportedPayloadIfPresent(data) != null || !isReady(data)) {
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

    static void writeUnsupportedManifest(MatsimData data, String message) {
        try {
            MatsimCachePaths.recreateVersionDir(data, POPULATION_CACHE_VERSION);
            writeJsonAtomic(manifestPath(data), manifest(data, "unsupported", message));
            MatsimCachePaths.deleteOtherVersions(data, "population-v", POPULATION_CACHE_VERSION);
            MEMORY_CACHE.remove(cacheKey(summaryPath(data)));
            MEMORY_CACHE.remove(cacheKey(streetsPath(data)));
        } catch (Exception e) {
            throw new RuntimeException("写入人口分布 unsupported 状态失败", e);
        }
    }

    private static Map<String, Object> unsupportedPayloadIfPresent(MatsimData data) {
        if (!Files.isRegularFile(manifestPath(data))) return null;
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath(data).toFile(), MAP_TYPE);
            if (!"unsupported".equals(manifest.get("status"))
                    || !POPULATION_CACHE_VERSION.equals(manifest.get("cacheVersion"))
                    || !sameSources(data, manifest)) {
                return null;
            }
            return Map.of(
                    "status", "unsupported",
                    "cacheVersion", POPULATION_CACHE_VERSION,
                    "reason", String.valueOf(manifest.getOrDefault("message", "缺少 plans 数据")),
                    "message", String.valueOf(manifest.getOrDefault("message", "缺少 plans 数据"))
            );
        } catch (Exception e) {
            return null;
        }
    }

    // ===================================================================================
    // 构建编排
    // ===================================================================================

    static void storeBuiltAggregation(MatsimData data, Aggregation aggregation,
                                      StreetIndex streets, long startedAt) {
        try {
            if (aggregation.transformFailures > 0) {
                log.warn("人口分布缓存坐标转换失败点已跳过: model={}, count={}",
                        data.getName(), aggregation.transformFailures);
            }
            Artifacts artifacts = assemble(aggregation, streets, effectiveSampleRate(data));
            // 统计完成后再原位替换目录：不累加旧分片，也尽量缩短重建窗口。
            MatsimCachePaths.recreateVersionDir(data, POPULATION_CACHE_VERSION);
            // 工件先落盘、manifest 最后写：manifest=ready 即三工件必然齐备
            writeBytesAtomic(gridPath(data), artifacts.gridBin);
            writeJsonAtomic(streetsPath(data), artifacts.streets);
            writeJsonAtomic(summaryPath(data), artifacts.summary);
            writeJsonAtomic(manifestPath(data), manifest(data, true));
            MatsimCachePaths.deleteOtherVersions(data, "population-v", POPULATION_CACHE_VERSION);
            MEMORY_CACHE.remove(cacheKey(summaryPath(data)));
            MEMORY_CACHE.remove(cacheKey(streetsPath(data)));
            log.info("人口分布缓存生成完成: model={}, persons={}, home={}, work={}, unassigned={}/{}, "
                            + "gridCells={}, bin={}B, 耗时={}ms",
                    data.getName(), artifacts.summary.get("persons"), artifacts.summary.get("homePersons"),
                    artifacts.summary.get("workPersons"), artifacts.summary.get("unassignedHome"),
                    artifacts.summary.get("unassignedWork"), artifacts.summary.get("gridCells"),
                    artifacts.gridBin.length, System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            writeFailedManifest(data);
            throw new RuntimeException("人口分布缓存生成失败: " + e.getMessage(), e);
        }
    }

    static void writeFailedManifest(MatsimData data) {
        try {
            Files.createDirectories(cacheDir(data));
            writeJsonAtomic(manifestPath(data), manifest(data, false));
        } catch (Exception ignored) {
        }
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

    /** 数量严格按模型原始值计算；历史 scale 不参与任何数值。 */
    static double effectiveSampleRate(MatsimData data) {
        return 1.0;
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
        /** 实际点定位器；共享扫描时为有界坐标缓存，其他路径直连 StreetIndex。 */
        private final StreetLocator streetLocator;
        private final CoverageIndex coverageIndex;
        private final TransitMetrics.RoadTransitContext roadTransit;
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
        long coveredPersons;
        long journeys;
        long busJourneys;
        long transitJourneys;
        long residentBusJourneys;
        long residentTransitJourneys;
        long motorizedJourneys;
        long unresolvedLegacyPtJourneys;
        long residentUnresolvedLegacyPtJourneys;
        long busServiceJourneys;
        long busServiceTransitBoardings;
        long busServiceTransfers;
        long busRailJourneys;
        long unresolvedBusServiceJourneys;
        final Map<String, Long> tripModeCounts = new TreeMap<>();
        double ptTravelSeconds;
        double ptDistanceMeters;
        double carTravelSeconds;
        double carDistanceMeters;
        double awaitSeconds;
        long awaitSamples;
        double busAwaitSeconds;
        long busAwaitSamples;
        double peakCarDistanceMeters;
        double peakCarTravelSeconds;
        long peakCarSamples;

        Aggregation(double mercCellSize, StreetIndex streets) {
            this(mercCellSize, streets, streets, null, (TransitMetrics.RoadTransitContext) null);
        }

        Aggregation(double mercCellSize, StreetIndex streets, StreetLocator streetLocator) {
            this(mercCellSize, streets, streetLocator, null, (TransitMetrics.RoadTransitContext) null);
        }

        Aggregation(double mercCellSize, StreetIndex streets, StreetLocator streetLocator,
                    CoverageIndex coverageIndex) {
            this(mercCellSize, streets, streetLocator, coverageIndex,
                    (TransitMetrics.RoadTransitContext) null);
        }

        Aggregation(double mercCellSize, StreetIndex streets, StreetLocator streetLocator,
                    CoverageIndex coverageIndex,
                    TransitMetrics.RoadTransitContext roadTransit) {
            this.mercCellSize = mercCellSize;
            this.streetLocator = streetLocator;
            this.coverageIndex = coverageIndex;
            this.roadTransit = roadTransit;
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
                if (org.matsim.core.router.TripStructureUtils.isStageActivityType(type)) {
                    continue; // pt interaction 等中转活动（§1）
                }
                Coord coord = transformedCoord(act.getCoord(), ctf);
                if ("home".equals(lower) || lower.startsWith("home_")) {
                    homeTypes.add(type);
                    if (home == null) home = coord;
                } else if (lower.startsWith("work")) {
                    workTypes.add(type);
                    if (work == null) work = coord;
                }
            }
            if (home != null) {
                homePersons++;
                if (coverageIndex != null && coverageIndex.covers(home)) coveredPersons++;
                addPoint(home, true);
            }
            accumulatePlanMetrics(plan, home != null);
            if (work != null) {
                workPersons++;
                addPoint(work, false);
            }
        }

        private void accumulatePlanMetrics(Plan plan, boolean resident) {
            // 直接按“两个非 interaction 活动之间的 legs”分 trip。
            // TripStructureUtils 对首尾恰为 stage activity 的历史/合成 plans 会抛异常，
            // 缓存构建不应因单个非标准 person 中断整个模型。
            List<Leg> tripLegs = new ArrayList<>();
            boolean originSeen = false;
            for (PlanElement element : plan.getPlanElements()) {
                if (element instanceof Activity activity) {
                    String type = activity.getType();
                    if (type != null && type.toLowerCase(Locale.ROOT).contains("interaction")) {
                        continue;
                    }
                    if (originSeen && !tripLegs.isEmpty()) {
                        String mode = tripMainMode(tripLegs);
                        tripModeCounts.merge(mode, 1L, Long::sum);
                        journeys++;
                        if (TransitMetrics.isMotorizedMode(mode)) motorizedJourneys++;
                        if (TransitMetrics.isTransitMode(mode)) {
                            transitJourneys++;
                            if (resident) residentTransitJourneys++;
                        }
                        if (TransitMetrics.isExplicitRoadPublicTransportMode(mode)) {
                            busJourneys++;
                            if (resident) residentBusJourneys++;
                        }
                        if (Constant.ROUTE_MODE_PT.equals(mode)) {
                            unresolvedLegacyPtJourneys++;
                            if (resident) residentUnresolvedLegacyPtJourneys++;
                        }
                        TransitMetrics.BusServiceJourneyObservation serviceJourney =
                                TransitMetrics.busServiceJourneyObservation(tripLegs, roadTransit);
                        if (serviceJourney.unresolvedLegacyPt()) {
                            unresolvedBusServiceJourneys++;
                        }
                        if (serviceJourney.busJourney()) {
                            busServiceJourneys++;
                            busServiceTransitBoardings += serviceJourney.transitBoardings();
                            busServiceTransfers += serviceJourney.transfers();
                            if (serviceJourney.busRailJourney()) busRailJourneys++;
                        }
                    }
                    originSeen = true;
                    tripLegs.clear();
                } else if (element instanceof Leg leg && originSeen) {
                    tripLegs.add(leg);
                }
            }
            List<PlanElement> elements = plan.getPlanElements();
            for (int i = 0; i < elements.size(); i++) {
                if (!(elements.get(i) instanceof Leg leg)) continue;
                if (leg.getRoute() != null && Double.isFinite(leg.getRoute().getDistance())
                        && leg.getRoute().getDistance() > 0) {
                    if (TransitMetrics.isResolvedRoadPublicTransportLeg(leg, roadTransit)) {
                        Double inVehicleSeconds = TransitMetrics.inVehicleTravelSeconds(leg, roadTransit);
                        if (inVehicleSeconds != null) {
                            ptTravelSeconds += inVehicleSeconds;
                            ptDistanceMeters += leg.getRoute().getDistance();
                        }
                    } else if (Constant.ROUTE_MODE_CAR.equals(leg.getMode())
                            && leg.getTravelTime().isDefined()
                            && Double.isFinite(leg.getTravelTime().seconds())
                            && leg.getTravelTime().seconds() > 0) {
                        carTravelSeconds += leg.getTravelTime().seconds();
                        carDistanceMeters += leg.getRoute().getDistance();
                    }
                }
                TransitMetrics.WaitSample wait = TransitMetrics.waitSample(elements, i);
                if (wait != null) {
                    awaitSeconds += wait.waitSeconds();
                    awaitSamples++;
                }
                if (TransitMetrics.isResolvedRoadPublicTransportLeg(leg, roadTransit)) {
                    Double strictBusWait = TransitMetrics.boardingWaitSeconds(leg);
                    if (strictBusWait != null) {
                        busAwaitSeconds += strictBusWait;
                        busAwaitSamples++;
                    }
                }
                TransitMetrics.PeakOperatingSpeedStats peakCar =
                        TransitMetrics.peakCarLegSpeedSample(leg);
                if (peakCar != null) {
                    peakCarDistanceMeters += peakCar.distanceMeters();
                    peakCarTravelSeconds += peakCar.travelSeconds();
                    peakCarSamples += peakCar.samples();
                }
            }
        }

        private String tripMainMode(List<Leg> legs) {
            String best = "walk";
            Leg bestLeg = null;
            int bestRank = Integer.MIN_VALUE;
            for (Leg leg : legs) {
                String mode = leg.getMode();
                int rank = TransitMetrics.resolvedMainModeRank(leg, roadTransit);
                if (rank > bestRank) {
                    bestRank = rank;
                    best = mode == null || mode.isBlank() ? "other" : mode;
                    bestLeg = leg;
                }
            }
            if (bestLeg == null) return best;
            String resolved = TransitMetrics.resolvedTransitMode(bestLeg, roadTransit);
            return resolved == null || resolved.isBlank() ? best : resolved;
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
            int streetIdx = streetLocator == null ? -1 : streetLocator.locate(coord.getX(), coord.getY());
            if (streetIdx >= 0) {
                (isHome ? streetHome : streetWork)[streetIdx]++;
            } else if (isHome) {
                unassignedHome++;
            } else {
                unassignedWork++;
            }
        }

        /** worker 私有聚合结果的确定性合并。 */
        void mergeFrom(Aggregation other) {
            if (other == null) {
                return;
            }
            for (Long2IntOpenHashMap.Entry entry : other.homeCells.long2IntEntrySet()) {
                homeCells.addTo(entry.getLongKey(), entry.getIntValue());
            }
            for (Long2IntOpenHashMap.Entry entry : other.workCells.long2IntEntrySet()) {
                workCells.addTo(entry.getLongKey(), entry.getIntValue());
            }
            for (int i = 0; i < streetHome.length; i++) {
                streetHome[i] += other.streetHome[i];
                streetWork[i] += other.streetWork[i];
            }
            homeTypes.addAll(other.homeTypes);
            workTypes.addAll(other.workTypes);
            persons += other.persons;
            homePersons += other.homePersons;
            workPersons += other.workPersons;
            unassignedHome += other.unassignedHome;
            unassignedWork += other.unassignedWork;
            transformFailures += other.transformFailures;
            coveredPersons += other.coveredPersons;
            journeys += other.journeys;
            busJourneys += other.busJourneys;
            transitJourneys += other.transitJourneys;
            residentBusJourneys += other.residentBusJourneys;
            residentTransitJourneys += other.residentTransitJourneys;
            motorizedJourneys += other.motorizedJourneys;
            unresolvedLegacyPtJourneys += other.unresolvedLegacyPtJourneys;
            residentUnresolvedLegacyPtJourneys += other.residentUnresolvedLegacyPtJourneys;
            busServiceJourneys += other.busServiceJourneys;
            busServiceTransitBoardings += other.busServiceTransitBoardings;
            busServiceTransfers += other.busServiceTransfers;
            busRailJourneys += other.busRailJourneys;
            unresolvedBusServiceJourneys += other.unresolvedBusServiceJourneys;
            other.tripModeCounts.forEach((mode, count) -> tripModeCounts.merge(mode, count, Long::sum));
            ptTravelSeconds += other.ptTravelSeconds;
            ptDistanceMeters += other.ptDistanceMeters;
            carTravelSeconds += other.carTravelSeconds;
            carDistanceMeters += other.carDistanceMeters;
            awaitSeconds += other.awaitSeconds;
            awaitSamples += other.awaitSamples;
            busAwaitSeconds += other.busAwaitSeconds;
            busAwaitSamples += other.busAwaitSamples;
            peakCarDistanceMeters += other.peakCarDistanceMeters;
            peakCarTravelSeconds += other.peakCarTravelSeconds;
            peakCarSamples += other.peakCarSamples;
        }
    }

    /** 300m 覆盖精确索引；STRtree build 后只读，可安全供 plans worker 共享。 */
    static final class CoverageIndex {
        private static final double GROUND_RADIUS_METERS = 300.0;
        private final STRtree tree = new STRtree();
        private final boolean coordinatesSupported;
        private int stops;

        private record Stop(Coord coord, double projectedRadius) {
        }

        private CoverageIndex(Set<Coord> stopCoords,
                              TransitMetrics.MetricCoordinateContext coordinates) {
            this.coordinatesSupported = coordinates != null && coordinates.isSupported();
            if (!coordinatesSupported) {
                tree.build();
                return;
            }
            if (stopCoords != null) {
                for (Coord coord : stopCoords) {
                    coord = coordinates.toWebMercator(coord);
                    if (coord == null) continue;
                    double radius = TransitMetrics.webMercatorRadiusForGroundMeters(
                            coord.getY(), GROUND_RADIUS_METERS);
                    if (!Double.isFinite(radius) || radius <= 0) continue;
                    tree.insert(new Envelope(coord.getX() - radius, coord.getX() + radius,
                            coord.getY() - radius, coord.getY() + radius), new Stop(coord, radius));
                    stops++;
                }
            }
            tree.build();
        }

        boolean covers(Coord point) {
            if (point == null || stops == 0) return false;
            @SuppressWarnings("unchecked")
            List<Stop> candidates = tree.query(new Envelope(point.getX(), point.getX(), point.getY(), point.getY()));
            for (Stop stop : candidates) {
                double dx = point.getX() - stop.coord().getX();
                double dy = point.getY() - stop.coord().getY();
                if (dx * dx + dy * dy <= stop.projectedRadius() * stop.projectedRadius()) return true;
            }
            return false;
        }

        boolean available() {
            return stops > 0;
        }

        boolean coordinatesSupported() {
            return coordinatesSupported;
        }
    }

    static CoverageIndex coverageIndex(MatsimData data) {
        if (data == null || data.getSchedule() == null) {
            return new CoverageIndex(Set.of(), TransitMetrics.MetricCoordinateContext.unsupported());
        }
        Object crs = data.getSchedule().getAttributes().getAttribute("coordinateReferenceSystem");
        return coverageIndexForRoadTransit(
                TransitMetrics.RoadTransitContext.from(data.getSchedule()),
                TransitMetrics.MetricCoordinateContext.fromCrs(crs == null ? null : String.valueOf(crs)));
    }

    static CoverageIndex coverageIndexForRoadTransit(
            TransitMetrics.RoadTransitContext roadTransit,
            TransitMetrics.MetricCoordinateContext coordinates) {
        if (roadTransit != null && roadTransit.coordinateTransformFailures() > 0) {
            return new CoverageIndex(Set.of(), TransitMetrics.MetricCoordinateContext.unsupported());
        }
        return new CoverageIndex(roadTransit == null ? Set.of() : roadTransit.stopCoords(), coordinates);
    }

    // ===================================================================================
    // 街道面索引（模型无关，进程级共享）
    // ===================================================================================

    /** 街道静态属性（照资源 properties，areaKm2 直接采信资源值，不重算）。 */
    record StreetRef(String code, String name, String district, double areaKm2) {
    }

    @FunctionalInterface
    interface StreetLocator {
        int locate(double x, double y);
    }

    /**
     * 有界、原始类型、精确坐标键的街道归属缓存。
     *
     * <p>4 路组相联只用于提高命中率；每次命中都同时校验 x/y 的 IEEE-754 位模式。
     * 哈希冲突或容量满只会淘汰旧槽并重算 JTS，不会返回错误街道。</p>
     */
    static final class CoordinateStreetCache implements StreetLocator {
        private static final int WAYS = 4;
        private static final int EMPTY = Integer.MIN_VALUE;

        private final StreetLocator delegate;
        private final long[] xBits;
        private final long[] yBits;
        private final int[] street;
        private final int setMask;
        private long hits;
        private long misses;

        CoordinateStreetCache(StreetLocator delegate, int requestedEntries) {
            this.delegate = delegate;
            int requestedSets = Math.max(1, requestedEntries / WAYS);
            int sets = 1;
            while (sets < requestedSets && sets < (1 << 28)) {
                sets <<= 1;
            }
            int entries = sets * WAYS;
            this.xBits = new long[entries];
            this.yBits = new long[entries];
            this.street = new int[entries];
            Arrays.fill(this.street, EMPTY);
            this.setMask = sets - 1;
        }

        @Override
        public int locate(double x, double y) {
            long xb = normalizedBits(x);
            long yb = normalizedBits(y);
            int base = ((int) mix64(xb ^ Long.rotateLeft(yb, 29)) & setMask) * WAYS;
            int emptySlot = -1;
            for (int way = 0; way < WAYS; way++) {
                int slot = base + way;
                if (street[slot] == EMPTY) {
                    if (emptySlot < 0) {
                        emptySlot = slot;
                    }
                } else if (xBits[slot] == xb && yBits[slot] == yb) {
                    hits++;
                    return street[slot];
                }
            }
            int result = delegate.locate(x, y);
            int slot = emptySlot >= 0 ? emptySlot : base + (int) (misses & (WAYS - 1));
            xBits[slot] = xb;
            yBits[slot] = yb;
            street[slot] = result;
            misses++;
            return result;
        }

        long hits() {
            return hits;
        }

        long misses() {
            return misses;
        }

        private static long normalizedBits(double value) {
            return value == 0.0d ? 0L : Double.doubleToLongBits(value);
        }

        private static long mix64(long value) {
            value = (value ^ (value >>> 33)) * 0xff51afd7ed558ccdl;
            value = (value ^ (value >>> 33)) * 0xc4ceb9fe1a85ec53l;
            return value ^ (value >>> 33);
        }
    }

    /** 街道点面归属索引：STRtree 粗筛 + PreparedGeometry 精判；要素顺序 = 资源文件序。 */
    static final class StreetIndex implements StreetLocator {
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
        @Override
        public int locate(double x, double y) {
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

    /** 供真实数据适配器复用与仿真缓存完全相同的街道点面归属索引。 */
    public static int locateStreet(double x, double y) {
        return streetIndex().locate(x, y);
    }

    /** 要素顺序与 {@link #locateStreet(double, double)} 返回索引一致，可直接作为前端 district 字典。 */
    public static List<String> streetDistricts() {
        StreetIndex index = streetIndex();
        List<String> districts = new ArrayList<>(index.size());
        for (int i = 0; i < index.size(); i++) districts.add(index.street(i).district());
        return List.copyOf(districts);
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

    static Artifacts assemble(Aggregation aggregation, StreetIndex streets, double ignoredLegacyScale) {
        byte[] gridBin = encodeGrid(aggregation.homeCells, aggregation.workCells, aggregation.mercCellSize, streets);
        int gridCells = (gridBin.length - BIN_HEADER_BYTES) / BIN_BYTES_PER_CELL;
        return new Artifacts(gridBin, buildSummary(aggregation, gridCells),
                buildStreets(aggregation, streets));
    }

    /**
     * population-grid.bin（§3 + v2 增列，小端）：
     * header = magic "PGRD" + version u16(=2) + count u32 + mercCellSize f64（共 18B）；
     * record × count（18B/cell，行式）= i i32, j i32, home u32, work u32（模型原始人数）,
     * street u16（格中心点面归属的街道要素索引，{@link #STREET_SENTINEL}=未命中；仅供前端行政区过滤，
     * 与端点级街道统计允许极少数跨界格差异）。
     * cell 写入序按打包键升序（i 升序，同 i 内 j 按无符号序）——仅为构建可复现，契约不约束顺序。
     */
    static byte[] encodeGrid(Long2IntOpenHashMap homeCells, Long2IntOpenHashMap workCells,
                             double mercCellSize, StreetIndex streets) {
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
            int street = STREET_SENTINEL;
            if (streets != null) {
                int idx = streets.locate((cellI(key) + 0.5) * mercCellSize, (cellJ(key) + 0.5) * mercCellSize);
                if (idx >= 0) {
                    street = idx;
                }
            }
            buffer.putShort((short) street);
        }
        return buffer.array();
    }

    /** population-summary.json（§2 表；所有数量均为模型文件中的原始值）。 */
    private static Map<String, Object> buildSummary(Aggregation aggregation, int gridCells) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "ready");
        summary.put("cacheVersion", POPULATION_CACHE_VERSION);
        summary.put("generatedAt", System.currentTimeMillis());
        summary.put("scale", 1.0);
        summary.put("quantityPolicy", "model-original");
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
        boolean roadRoutesComplete = aggregation.roadTransit == null || aggregation.roadTransit.isComplete();
        boolean coordinatesSupported = aggregation.coverageIndex != null
                && aggregation.coverageIndex.coordinatesSupported();
        boolean coverageAvailable = roadRoutesComplete && coordinatesSupported
                && aggregation.coverageIndex != null
                && aggregation.coverageIndex.available() && aggregation.homePersons > 0;
        summary.put("coverageValidHomePersons", aggregation.homePersons);
        summary.put("coverageMissingHomePersons", Math.max(0L, aggregation.persons - aggregation.homePersons));
        summary.put("coordinateTransformFailures", aggregation.transformFailures);
        summary.put("coveredPersons300m", coverageAvailable ? aggregation.coveredPersons : null);
        summary.put("coverage300Percent", coverageAvailable
                ? Math.round(aggregation.coveredPersons * 10_000.0 / aggregation.homePersons) / 100.0 : null);
        summary.put("coverage300Status", !roadRoutesComplete || !coordinatesSupported ? "unsupported"
                : coverageAvailable ? "ready" : "nodata");
        summary.put("unresolvedRoadTransitRoutes", aggregation.roadTransit == null
                ? 0 : aggregation.roadTransit.unresolvedRoutes());
        summary.put("journeys", aggregation.journeys);
        Map<String, Double> modeShare = new LinkedHashMap<>();
        if (aggregation.journeys > 0) {
            aggregation.tripModeCounts.forEach((mode, count) ->
                    modeShare.put(mode, Math.round(count * 10_000.0 / aggregation.journeys) / 100.0));
        }
        summary.put("tripModeSharePercent", modeShare);
        summary.put("busJourneys", aggregation.busJourneys);
        summary.put("transitJourneys", aggregation.transitJourneys);
        summary.put("residentBusJourneys", aggregation.residentBusJourneys);
        summary.put("residentTransitJourneys", aggregation.residentTransitJourneys);
        summary.put("motorizedJourneys", aggregation.motorizedJourneys);
        summary.put("unresolvedLegacyPtJourneys", aggregation.unresolvedLegacyPtJourneys);
        summary.put("residentUnresolvedLegacyPtJourneys",
                aggregation.residentUnresolvedLegacyPtJourneys);
        summary.put("busDailyTripsStatus",
                aggregation.homePersons == 0 ? "nodata"
                        : aggregation.residentUnresolvedLegacyPtJourneys > 0
                        ? "unsupported" : "ready");
        summary.put("busSharePercent", roadRoutesComplete && aggregation.journeys > 0
                ? Math.round(aggregation.busJourneys * 10_000.0 / aggregation.journeys) / 100.0 : null);
        summary.put("ptSharePercent", aggregation.journeys > 0
                ? Math.round(aggregation.transitJourneys * 10_000.0 / aggregation.journeys) / 100.0 : null);
        summary.put("publicTransportMotorizedSharePercent", aggregation.motorizedJourneys > 0
                ? Math.round(aggregation.transitJourneys * 10_000.0 / aggregation.motorizedJourneys) / 100.0
                : null);
        summary.put("publicTransportShareStatus", aggregation.motorizedJourneys == 0 ? "nodata" : "ready");
        summary.put("busShareStatus", !roadRoutesComplete || aggregation.unresolvedLegacyPtJourneys > 0
                ? "unsupported" : aggregation.journeys == 0 ? "nodata" : "ready");
        boolean serviceJourneySupported = roadRoutesComplete
                && aggregation.unresolvedBusServiceJourneys == 0;
        summary.put("busServiceJourneyStatus", !serviceJourneySupported
                ? "unsupported" : aggregation.busServiceJourneys == 0 ? "nodata" : "ready");
        summary.put("busServiceJourneys", aggregation.busServiceJourneys);
        summary.put("busServiceTransitBoardings", aggregation.busServiceTransitBoardings);
        summary.put("busServiceTransfers", aggregation.busServiceTransfers);
        summary.put("busRailJourneys", aggregation.busRailJourneys);
        summary.put("unresolvedBusServiceJourneys", aggregation.unresolvedBusServiceJourneys);
        summary.put("averageBusTransfers", serviceJourneySupported && aggregation.busServiceJourneys > 0
                ? Math.round(aggregation.busServiceTransfers * 10_000.0
                        / aggregation.busServiceJourneys) / 10_000.0 : null);
        summary.put("busRailFeederPercent", serviceJourneySupported && aggregation.busServiceJourneys > 0
                ? Math.round(aggregation.busRailJourneys * 10_000.0
                        / aggregation.busServiceJourneys) / 100.0 : null);
        Map<String, Object> speeds = new LinkedHashMap<>();
        // 公交高峰运营速度由 visual 缓存按 schedule 班次汇总后写入；此处只缓存 plans
        // 的高峰小汽车空间平均速度，避免以乘客 leg 速度冒充公交车辆运营速度。
        speeds.put("ptAvg", null);
        speeds.put("busAvg", null);
        speeds.put("carAvg", aggregation.peakCarTravelSeconds > 0
                ? Math.round(aggregation.peakCarDistanceMeters
                        / aggregation.peakCarTravelSeconds * 360.0) / 100.0 : null);
        summary.put("speedKmh", speeds);
        summary.put("peakCarDistanceMeters", aggregation.peakCarDistanceMeters);
        summary.put("peakCarTravelSeconds", aggregation.peakCarTravelSeconds);
        summary.put("peakCarSamples", aggregation.peakCarSamples);
        summary.put("speedPeriodPolicy", "peak-0700-0900-and-1700-1900");
        summary.put("carSpeedSpatialScope", "all-model-urban-roads");
        summary.put("averageWaitMinutes", aggregation.awaitSamples > 0
                ? Math.round(aggregation.awaitSeconds / aggregation.awaitSamples / 60.0 * 100.0) / 100.0 : null);
        summary.put("waitSamples", aggregation.awaitSamples);
        summary.put("averageBusWaitMinutes", roadRoutesComplete && aggregation.busAwaitSamples > 0
                ? Math.round(aggregation.busAwaitSeconds / aggregation.busAwaitSamples / 60.0 * 100.0) / 100.0 : null);
        summary.put("busWaitSamples", aggregation.busAwaitSamples);
        summary.put("busWaitPolicy", TransitMetrics.BUS_WAIT_TIME_POLICY);
        return summary;
    }

    /**
     * population-streets.json（§2 表）：176 街道全量（含 0 值，顺序 = 资源文件序）+ totals。
     * 对账恒等式（§6）：sum(streets.home) + unassignedHome == homePersons（work 同理）。
     */
    private static Map<String, Object> buildStreets(Aggregation aggregation, StreetIndex streets) {
        if (streets == null) {
            return Map.of(
                    "streets", List.of(),
                    "totals", Map.of("home", 0L, "work", 0L)
            );
        }
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
        return manifest(data, ready ? "ready" : "failed", null);
    }

    private static Map<String, Object> manifest(MatsimData data, String status, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("cacheVersion", POPULATION_CACHE_VERSION);
        result.put("generatedAt", System.currentTimeMillis());
        if (message != null && !message.isBlank()) result.put("message", message);
        sourceFingerprint(data, result);
        return result;
    }

    /**
     * 源指纹：plans（居住/就业点、方式、速度与等待）+ schedule（300m 覆盖）
     * + 街道资源标识（路径 + 内容 sha256，资源升级即失效重建）。
     */
    private static void sourceFingerprint(MatsimData data, Map<String, Object> result) {
        putFileFingerprint(result, "plans", data.getOutfile() == null ? null : data.getOutfile().getPlans());
        putFileFingerprint(result, "schedule",
                data.getOutfile() == null ? null : data.getOutfile().getTransitSchedule());
        result.put("streetsResource", STREETS_RESOURCE);
        result.put("streetsSha256", streetsGeojsonTag());
    }

    private static void putFileFingerprint(Map<String, Object> result, String key, String filePath) {
        result.put(key + "File", filePath);
        result.put(key + "Modified", lastModified(filePath));
        result.put(key + "Size", fileSize(filePath));
        result.put(key + "Signature", MatsimSourceFingerprint.signature(filePath));
    }

    private static boolean sameSources(MatsimData data, Map<String, Object> manifest) {
        Map<String, Object> current = new LinkedHashMap<>();
        sourceFingerprint(data, current);
        return MatsimSourceFingerprint.sameFlatFingerprint(current, manifest);
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
