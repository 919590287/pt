package com.jts.gjcxfzksh.data.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jts.gjcxfzksh.api.common.Constant;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.TreeMap;

/**
 * 公交出行监测 · 出行分布监测缓存家族（人口分布监测的同级子模块，原「起终点分布监测」）。
 * <p>
 * 模型加载时做两遍独立扫描：①从 MATSim plans 抽取每次「活动出行」（含 pt leg 的 trip）的
 * 起点/终点活动坐标（端点工件）；②从 {@code PTPersonTrack} 上下车流水识别整段公交出行
 * （journey）并聚合站点级公交出行 OD（OD 工件，供公交OD监测子模块）。
 * 产出五个工件（全部为加载模型的原始量，不做任何数量缩放）：
 * <ul>
 *   <li>{@code tripends-summary.json}：总量指标 + 口径参数（右侧首屏直出）；</li>
 *   <li>{@code tripends-grid.bin}：100 米栅格二进制表——布局与 population-grid.bin 完全同契约
 *       （PGRD 头 + 18B/cell，见 {@link MatsimPopulationCache#encodeGrid}），列语义映射为
 *       home 列=起点人次、work 列=终点人次，前端复用同一解析器；</li>
 *   <li>{@code tripends-streets.json}：176 街道全量统计（origin/destination，含 0 值）+ totals；</li>
 *   <li>{@code tripends-od-streets.json}：街道级 OD 对（有向，o/d 为街道要素索引 = 资源文件序，
 *       与 tripends-streets.json 行序一致；pairs 按人次降序）+ totals；</li>
 *   <li>{@code tripends-od-grid.bin}：100 米栅格级 OD 对二进制表（PGOD 契约，见
 *       {@link #encodeOdGrid}；人次降序写入并截断 {@link #MAX_GRID_OD_PAIRS}，前端可按前缀取 Top-K）。</li>
 * </ul>
 * 口径契约（任何改动必须 bump {@link #TRIPENDS_CACHE_VERSION}）：
 * <ul>
 *   <li>端点（出行分布监测，v4 起活动口径）：明确 selectedPlan（照
 *       MatsimPopulationCache.acceptPerson）按「非 interaction 活动」切分 trip，trip 内含
 *       {@code mode=pt} 的 leg（判定与 TransitMetrics 同源 {@link Constant#ROUTE_MODE_PT}）
 *       即计一次公交出行——起点 = 出行前置活动坐标、终点 = 出行后置活动坐标（EPSG:3857，
 *       非大模型内存 population 已由 Datasource 统一转换，大模型流式读 plans 懒解析转换器）；
 *       活动缺坐标/转换失败的端点跳过（journeys 照计，originPoints/destPoints 不计）；</li>
 *   <li>乘车段 = 同人 enter/leave 流水按时间排序后的配对（排序与配对口径复刻
 *       MatsimTransferCache.collectPersonEvents，不成对记录计入 droppedTracks）；</li>
 *   <li>整段出行（仅 OD 工件使用）= 相邻乘车段满足「0 ≤ 上车时刻−前段下车时刻 ≤ 1800s 且
 *       前站与后站地面距离 ≤ 800m」时链为同一 journey（数值与换乘分析 §3 一致，仅数值一致、
 *       独立版本化互不引用）；全部公交制式（bus/subway/tram）一视同仁参与链接；
 *       缺坐标/缺站点无法校验时保守断链；</li>
 *   <li>栅格与街道归属：与人口分布同口径——mercCellSize=100/cos(centerLat) 分箱 +
 *       内嵌街道面点面归属（复用 {@link MatsimPopulationCache#streetIndex()}，未命中计 unassigned*）；</li>
 *   <li>OD 口径（公交OD监测，维持站点口径不随端点改动）：一段整段出行计一对
 *       (O,D)=（首上车站, 末下车站），坐标取 transitSchedule（EPSG:3857），有向、含同街道/同格自环；
 *       任一端缺坐标的出行不计入 OD（odSkipped）；栅格 OD 的街道归属列按“格中心”点面归属
 *       （仅用于前端行政区过滤/提示，与端点级街道统计允许极少数跨界格差异）。</li>
 * </ul>
 */
@Slf4j
public final class MatsimTripEndsCache {

    // v1: 首版口径：enter/leave 配对 + 30min/800m 链接（全制式）+ 首上车/末下车端点；
    //     栅格/街道归属同 population-v1；grid.bin 复用 PGRD 契约（home=起点、work=终点）。
    // v2: 增加公交出行 OD 工件（街道 OD json + 栅格 OD PGOD bin，人次降序 + 20 万对截断）；
    //     端点/栅格口径不变。
    // v3: grid.bin 随 PGRD v2 增加格中心街道列（前端行政区过滤隐藏区外栅格），口径不变。
    // v4: 模块改名「出行分布监测」，端点口径从「journey 首上车站/末下车站」改为「本次活动出行的
    //     起终点」（plans 中含 pt leg 的 trip 两端非 interaction 活动坐标）；journeys/riders 随之
    //     改为 plans 口径；OD 工件维持 events 站点口径不变；源指纹新增 plans。
    // v5: 原模型数量直出，取消 desc.scale 扩样，并支持缺 plans 的显式 unsupported 状态。
    public static final String TRIPENDS_CACHE_VERSION = "tripends-v6";
    /**
     * 大模型出行端点独立工件：v2 将 TransitPassengerRoute 以及
     * pt/bus/subway/rail/tram/ferry 全制式纳入，修复 V6 仅识别 mode=pt 导致的空分布。
     */
    public static final String TRIP_DISTRIBUTION_CACHE_VERSION = "trip-distribution-v4";

    // ===== 口径常量（改动必须 bump 版本）=====
    /** 出行链识别时间窗（秒）。与 MatsimTransferCache.TRANSFER_WINDOW_SECONDS 仅数值一致，互不引用。 */
    static final int JOURNEY_WINDOW_SECONDS = 1800;
    /** 前后站地面距离阈值（米）。与 MatsimTransferCache.TRANSFER_MAX_DIST_M 仅数值一致，互不引用。 */
    static final double JOURNEY_MAX_DIST_M = 800.0;

    // ===== tripends-od-grid.bin 布局常量（前后端二进制契约，禁止偏离；小端）=====
    static final byte[] OD_BIN_MAGIC = {'P', 'G', 'O', 'D'};
    static final int OD_BIN_VERSION = 1;
    /** 头部字节数：magic(4) + version u16(2) + count u32(4) + mercCellSize f64(8)。 */
    static final int OD_BIN_HEADER_BYTES = 18;
    /** 每对字节数：iO i32 + jO i32 + iD i32 + jD i32 + count u32 + oStreet u16 + dStreet u16。 */
    static final int OD_BIN_BYTES_PER_PAIR = 24;
    /** 栅格 OD 截断上限（人次降序保留；约 24B×20万=4.8MB，超出部分计入 droppedPairs/droppedFlow）。 */
    static final int MAX_GRID_OD_PAIRS = 200_000;
    /** oStreet/dStreet 列的“街道未命中”哨兵值（u16 全 1）。 */
    static final int OD_STREET_UNASSIGNED = 0xFFFF;

    private static final String SUMMARY_FILE = "tripends-summary.json";
    private static final String STREETS_FILE = "tripends-streets.json";
    private static final String GRID_FILE = "tripends-grid.bin";
    private static final String OD_STREETS_FILE = "tripends-od-streets.json";
    private static final String OD_GRID_FILE = "tripends-od-grid.bin";
    private static final String MANIFEST_FILE = "manifest.json";
    private static final String ENDPOINT_MANIFEST_FILE = "manifest.json";

    // 项目统一投影 epsg:3857，纬度反算公式与 MatsimTransferCache.groundDistanceMeters 同源。
    private static final double EARTH_RADIUS = 6378137.0;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final BackendMemoryCache<String, Map<String, Object>> MEMORY_CACHE =
            new BackendMemoryCache<>("tripends-json", 64L * 1024 * 1024, BackendMemoryCache::estimate);

    private MatsimTripEndsCache() {
    }

    // ===================================================================================
    // 对外入口（模式照 MatsimPopulationCache）
    // ===================================================================================

    public static void prepareOnModelLoad(MatsimData data) {
        MatsimPlansDerivedCache.prepareTripEndsOnModelLoad(data);
    }

    public static boolean isReady(MatsimData data) {
        return isBaseReady(data) && endpointReady(data);
    }

    private static boolean isBaseReady(MatsimData data) {
        if (!Files.exists(manifestPath(data)) || !Files.exists(summaryPath(data))
                || !Files.exists(streetsPath(data)) || !Files.exists(gridPath(data))
                || !Files.exists(odStreetsPath(data)) || !Files.exists(odGridPath(data))) {
            return false;
        }
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath(data).toFile(), MAP_TYPE);
            return "ready".equals(manifest.get("status"))
                    && TRIPENDS_CACHE_VERSION.equals(manifest.get("cacheVersion"))
                    && sameSources(data, manifest);
        } catch (Exception e) {
            throw new IllegalStateException("出行分布缓存状态读取失败: " + manifestPath(data), e);
        }
    }

    /** 小模型直接复用 tripends-v4；大模型要求全制式端点覆盖工件就绪。 */
    private static boolean endpointReady(MatsimData data) {
        if (data == null || !data.isLargeModel()) {
            return data != null && isBaseReady(data);
        }
        if (!Files.isRegularFile(endpointManifestPath(data))) {
            return false;
        }
        try {
            Map<String, Object> manifest = JSON.readValue(endpointManifestPath(data).toFile(), MAP_TYPE);
            if (!TRIP_DISTRIBUTION_CACHE_VERSION.equals(manifest.get("cacheVersion"))
                    || !sameEndpointSources(data, manifest)) {
                return false;
            }
            if ("unsupported".equals(manifest.get("status"))) {
                return true;
            }
            return "ready".equals(manifest.get("status"))
                    && Files.isRegularFile(endpointSummaryPath(data))
                    && Files.isRegularFile(endpointStreetsPath(data))
                    && Files.isRegularFile(endpointGridPath(data));
        } catch (Exception e) {
            throw new IllegalStateException("大模型出行分布端点缓存状态读取失败: "
                    + endpointManifestPath(data), e);
        }
    }

    /** 总量指标 + 口径参数（POST /pt/tripends/summary）。未就绪返回 generating 态。 */
    public static Map<String, Object> readTripEndsSummary(MatsimData data) {
        Map<String, Object> unsupported = endpointUnsupportedPayload(data);
        if (unsupported != null) return unsupported;
        if (!endpointReady(data)) {
            return generatingPayload();
        }
        try {
            return loadCachedJson(endpointSummaryPath(data));
        } catch (Exception e) {
            throw new IllegalStateException("读取出行分布汇总缓存失败: model=" + data.getName()
                    + ", path=" + summaryPath(data), e);
        }
    }

    /** 176 街道全量统计（POST /pt/tripends/streets）。未就绪返回 generating 态。 */
    public static Map<String, Object> readTripEndsStreets(MatsimData data) {
        Map<String, Object> unsupported = endpointUnsupportedPayload(data);
        if (unsupported != null) return unsupported;
        if (!endpointReady(data)) {
            return generatingPayload();
        }
        try {
            return loadCachedJson(endpointStreetsPath(data));
        } catch (Exception e) {
            throw new IllegalStateException("读取出行分布街道缓存失败: model=" + data.getName()
                    + ", path=" + streetsPath(data), e);
        }
    }

    /** 栅格二进制表字节（GET /pt/tripends/grid.bin）。未就绪返回 null（Controller 侧 404）。 */
    public static byte[] readGridBytes(MatsimData data) {
        if (endpointUnsupportedPayload(data) != null || !endpointReady(data)) {
            return null;
        }
        try {
            return Files.readAllBytes(endpointGridPath(data));
        } catch (Exception e) {
            throw new IllegalStateException("读取出行分布栅格表失败: model=" + data.getName()
                    + ", path=" + gridPath(data), e);
        }
    }

    /** 街道级 OD 对（POST /pt/tripends/od/streets）。未就绪返回 generating 态。 */
    public static Map<String, Object> readOdStreets(MatsimData data) {
        if (!isBaseReady(data)) {
            return generatingPayload();
        }
        try {
            return loadCachedJson(odStreetsPath(data));
        } catch (Exception e) {
            throw new IllegalStateException("读取公交OD街道对缓存失败: model=" + data.getName()
                    + ", path=" + odStreetsPath(data), e);
        }
    }

    /** 栅格级 OD 对二进制表字节（GET /pt/tripends/od/grid.bin）。未就绪返回 null（Controller 侧 404）。 */
    public static byte[] readOdGridBytes(MatsimData data) {
        if (!isBaseReady(data)) {
            return null;
        }
        try {
            return Files.readAllBytes(odGridPath(data));
        } catch (Exception e) {
            throw new IllegalStateException("读取公交OD栅格对表失败: model=" + data.getName()
                    + ", path=" + odGridPath(data), e);
        }
    }

    /**
     * grid.bin 的强校验 ETag 内容：manifest 的 sourceFingerprint + cacheVersion 哈希
     * （照 MatsimPopulationCache.gridBinTag，街道资源键一并纳入）。未就绪返回 null。
     */
    public static String gridBinTag(MatsimData data) {
        if (endpointUnsupportedPayload(data) != null || !endpointReady(data)) {
            return null;
        }
        try {
            Map<String, Object> manifest = JSON.readValue(endpointManifestPath(data).toFile(), MAP_TYPE);
            StringBuilder content = new StringBuilder(data.isLargeModel()
                    ? TRIP_DISTRIBUTION_CACHE_VERSION : TRIPENDS_CACHE_VERSION);
            new TreeMap<>(manifest).forEach((key, value) -> {
                if (key.endsWith("File") || key.endsWith("Modified") || key.endsWith("Size")
                        || key.startsWith("streets")) {
                    content.append('|').append(key).append('=').append(value);
                }
            });
            return sha256Hex(content.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("出行分布栅格表 ETag 计算失败: "
                    + endpointManifestPath(data), e);
        }
    }

    /** OD 工件仍以 events+schedule 的 tripends-v4 源指纹生成 ETag。 */
    public static String odGridBinTag(MatsimData data) {
        if (!isBaseReady(data)) {
            return null;
        }
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath(data).toFile(), MAP_TYPE);
            StringBuilder content = new StringBuilder(TRIPENDS_CACHE_VERSION);
            new TreeMap<>(manifest).forEach((key, value) -> {
                if (key.endsWith("File") || key.endsWith("Modified") || key.endsWith("Size")
                        || key.startsWith("streets")) {
                    content.append('|').append(key).append('=').append(value);
                }
            });
            return sha256Hex(content.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("公交OD栅格表 ETag 计算失败: " + manifestPath(data), e);
        }
    }

    private static Map<String, Object> generatingPayload() {
        return Map.of(
                "status", "generating",
                "cacheVersion", TRIP_DISTRIBUTION_CACHE_VERSION,
                "message", "出行分布缓存正在后台生成"
        );
    }

    static void writeUnsupportedEndpointManifest(MatsimData data, String message) {
        if (data == null || !data.isLargeModel()) return;
        try {
            MatsimCachePaths.recreateVersionDir(data, TRIP_DISTRIBUTION_CACHE_VERSION);
            writeJsonAtomic(endpointManifestPath(data), endpointManifest(data, "unsupported", message));
            MatsimCachePaths.deleteOtherVersions(
                    data, "trip-distribution-v", TRIP_DISTRIBUTION_CACHE_VERSION);
            MEMORY_CACHE.remove(cacheKey(endpointSummaryPath(data)));
            MEMORY_CACHE.remove(cacheKey(endpointStreetsPath(data)));
        } catch (Exception e) {
            throw new RuntimeException("写入出行分布 unsupported 状态失败", e);
        }
    }

    private static Map<String, Object> endpointUnsupportedPayload(MatsimData data) {
        if (data == null || !data.isLargeModel() || !Files.isRegularFile(endpointManifestPath(data))) return null;
        try {
            Map<String, Object> manifest = JSON.readValue(endpointManifestPath(data).toFile(), MAP_TYPE);
            if (!"unsupported".equals(manifest.get("status"))
                    || !TRIP_DISTRIBUTION_CACHE_VERSION.equals(manifest.get("cacheVersion"))
                    || !sameEndpointSources(data, manifest)) {
                return null;
            }
            String message = String.valueOf(manifest.getOrDefault("message", "缺少 plans 数据"));
            return Map.of(
                    "status", "unsupported",
                    "cacheVersion", TRIP_DISTRIBUTION_CACHE_VERSION,
                    "reason", message,
                    "message", message
            );
        } catch (Exception e) {
            throw new IllegalStateException("读取出行分布 unsupported 状态失败: "
                    + endpointManifestPath(data), e);
        }
    }

    // ===================================================================================
    // 构建编排
    // ===================================================================================

    static void storeBuiltAggregation(MatsimData data, Aggregation aggregation,
                                      MatsimPopulationCache.StreetIndex streets, long startedAt) {
        boolean largeModel = data.isLargeModel();
        if (largeModel && isBaseReady(data)) {
            storeLargeModelEndpoints(data, aggregation, streets, startedAt);
            return;
        }
        try {
            if (aggregation.transformFailures > 0) {
                log.warn("出行分布缓存坐标转换失败端点已跳过: model={}, count={}",
                        data.getName(), aggregation.transformFailures);
            }
            Map<String, double[]> coordByFacility = facilityCoords(
                    data.getScenario() == null ? null : data.getSchedule());
            if (largeModel && (data.getPersonTracks() == null || data.getPersonTracks().isEmpty())) {
                aggregateJourneys(data, coordByFacility, aggregation);
            } else {
                aggregateJourneys(data.getPersonTracks(), coordByFacility, aggregation);
            }
            Artifacts artifacts = assemble(aggregation, streets, MatsimPopulationCache.effectiveSampleRate(data));
            MatsimCachePaths.recreateVersionDir(data, TRIPENDS_CACHE_VERSION);
            // 工件先落盘、manifest 最后写：manifest=ready 即五工件必然齐备
            writeBytesAtomic(gridPath(data), artifacts.gridBin);
            writeBytesAtomic(odGridPath(data), artifacts.odGridBin);
            writeJsonAtomic(streetsPath(data), artifacts.streets);
            writeJsonAtomic(odStreetsPath(data), artifacts.odStreets);
            writeJsonAtomic(summaryPath(data), artifacts.summary);
            writeJsonAtomic(manifestPath(data), manifest(data, true));
            MatsimCachePaths.deleteOtherVersions(data, "tripends-v", TRIPENDS_CACHE_VERSION);
            MEMORY_CACHE.remove(cacheKey(summaryPath(data)));
            MEMORY_CACHE.remove(cacheKey(streetsPath(data)));
            MEMORY_CACHE.remove(cacheKey(odStreetsPath(data)));
            log.info("出行分布缓存生成完成: model={}, journeys={}, riders={}, origin={}, dest={}, "
                            + "unassigned={}/{}, droppedTracks={}, gridCells={}, bin={}B, "
                            + "odStreetPairs={}, odGridPairs={}(dropped {}), odBin={}B, 耗时={}ms",
                    data.getName(), artifacts.summary.get("journeys"), artifacts.summary.get("riders"),
                    artifacts.summary.get("originPoints"), artifacts.summary.get("destPoints"),
                    artifacts.summary.get("unassignedOrigin"), artifacts.summary.get("unassignedDest"),
                    artifacts.summary.get("droppedTracks"), artifacts.summary.get("gridCells"),
                    artifacts.gridBin.length, artifacts.summary.get("odStreetPairs"),
                    artifacts.summary.get("odGridPairs"), artifacts.summary.get("odGridDroppedPairs"),
                    artifacts.odGridBin.length, System.currentTimeMillis() - startedAt);
            // 新建的大模型可能尚无 tripends-v4。先用当前已物化的小规模轨迹建立
            // 完整 OD 工件，再从同一次 plans 聚合写入全公交制式的端点覆盖工件。
            if (largeModel) {
                storeLargeModelEndpoints(data, aggregation, streets, startedAt);
            }
        } catch (Exception e) {
            writeFailedManifest(data);
            throw new RuntimeException("出行分布缓存生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 大模型只重建 plans 端点工件，不重扫 15GB events，也不用空 personTracks
     * 覆盖已验证的 OD 工件。OD 指标从原 tripends-v4 summary 原样继承。
     */
    private static void storeLargeModelEndpoints(MatsimData data, Aggregation aggregation,
                                                 MatsimPopulationCache.StreetIndex streets,
                                                 long startedAt) {
        if (!isBaseReady(data)) {
            throw new IllegalStateException("大模型基础 tripends OD 工件未就绪，拒绝用空轨迹覆盖");
        }
        try {
            byte[] grid = MatsimPopulationCache.encodeGrid(
                    aggregation.originCells, aggregation.destCells, aggregation.mercCellSize, streets);
            int gridCells = (grid.length - MatsimPopulationCache.BIN_HEADER_BYTES)
                    / MatsimPopulationCache.BIN_BYTES_PER_CELL;
            Map<String, Object> summary = new LinkedHashMap<>(loadCachedJson(summaryPath(data)));
            summary.put("status", "ready");
            summary.put("cacheVersion", TRIP_DISTRIBUTION_CACHE_VERSION);
            summary.put("generatedAt", System.currentTimeMillis());
            summary.put("cellSizeMeters", (int) MatsimPopulationCache.CELL_SIZE_METERS);
            summary.put("mercCellSize", aggregation.mercCellSize);
            summary.put("gridCells", gridCells);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("windowSec", JOURNEY_WINDOW_SECONDS);
            params.put("maxDistM", (int) JOURNEY_MAX_DIST_M);
            params.put("modes", "all-transit");
            params.put("endpoints", "activity");
            params.put("source", "streaming-selected-plans");
            summary.put("params", params);
            summary.put("journeys", aggregation.journeys);
            summary.put("riders", aggregation.riders);
            summary.put("originPoints", aggregation.originPoints);
            summary.put("destPoints", aggregation.destPoints);
            summary.put("unassignedOrigin", aggregation.unassignedOrigin);
            summary.put("unassignedDest", aggregation.unassignedDest);

            MatsimCachePaths.recreateVersionDir(data, TRIP_DISTRIBUTION_CACHE_VERSION);
            writeBytesAtomic(endpointGridPath(data), grid);
            writeJsonAtomic(endpointStreetsPath(data), buildStreets(aggregation, streets));
            writeJsonAtomic(endpointSummaryPath(data), summary);
            writeJsonAtomic(endpointManifestPath(data), endpointManifest(data, true));
            MatsimCachePaths.deleteOtherVersions(
                    data, "trip-distribution-v", TRIP_DISTRIBUTION_CACHE_VERSION);
            MEMORY_CACHE.remove(cacheKey(endpointSummaryPath(data)));
            MEMORY_CACHE.remove(cacheKey(endpointStreetsPath(data)));
            log.info("大模型出行分布端点缓存生成完成: model={}, journeys={}, riders={}, "
                            + "origin={}, dest={}, cells={}, bin={}B, elapsedMs={}",
                    data.getName(), aggregation.journeys, aggregation.riders,
                    aggregation.originPoints, aggregation.destPoints, gridCells, grid.length,
                    System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            writeEndpointFailedManifest(data);
            throw new RuntimeException("大模型出行分布端点缓存生成失败: " + e.getMessage(), e);
        }
    }

    static void writeFailedManifest(MatsimData data) {
        if (data != null && data.isLargeModel()) {
            writeEndpointFailedManifest(data);
            return;
        }
        try {
            Files.createDirectories(cacheDir(data));
            writeJsonAtomic(manifestPath(data), manifest(data, false));
        } catch (Exception ignored) {
        }
    }

    private static void writeEndpointFailedManifest(MatsimData data) {
        try {
            Files.createDirectories(endpointCacheDir(data));
            writeJsonAtomic(endpointManifestPath(data), endpointManifest(data, false));
        } catch (Exception ignored) {
        }
    }

    /** schedule 全部 stopFacility 坐标（EPSG:3857，Datasource 加载时已统一转换）。 */
    static Map<String, double[]> facilityCoords(TransitSchedule schedule) {
        Map<String, double[]> coords = new HashMap<>();
        if (schedule == null) {
            return coords;
        }
        for (Map.Entry<Id<TransitStopFacility>, TransitStopFacility> entry : schedule.getFacilities().entrySet()) {
            TransitStopFacility facility = entry.getValue();
            if (facility.getCoord() != null) {
                coords.put(entry.getKey().toString(),
                        new double[]{facility.getCoord().getX(), facility.getCoord().getY()});
            }
        }
        return coords;
    }

    // ===================================================================================
    // 口径工具（复刻来源见各注释；不得反向修改被复刻方）
    // ===================================================================================

    /**
     * epsg:3857 平面两点的地面距离（米）：平面欧氏 × cos(纬度)。
     * 复刻自 MatsimTransferCache.groundDistanceMeters（口径独立版本化，勿反向依赖）。
     */
    static double groundDistanceMeters(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double planar = Math.sqrt(dx * dx + dy * dy);
        double lat = Math.atan(Math.sinh(((y1 + y2) / 2.0) / EARTH_RADIUS));
        return planar * Math.cos(lat);
    }

    /**
     * 同人 track 时间排序：复刻自 MatsimTransferCache.TRACK_TIME_ORDER
     * （同一秒先下车后上车，末键按车辆 ID 定序保证可复现）。
     */
    private static final Comparator<PTPersonTrack> TRACK_TIME_ORDER =
            Comparator.comparingDouble(MatsimTripEndsCache::safeTime)
                    .thenComparingInt(track -> Boolean.TRUE.equals(track.getEnter()) ? 1 : 0)
                    .thenComparing(track -> String.valueOf(track.getVehicleId()));

    // ===================================================================================
    // 整段出行识别与聚合
    // ===================================================================================

    /** 乘车段：enter/leave 配对结果（OD 识别只关心站点与时刻，不关心线路/制式）。 */
    private record RideSegment(String boardFacility, double boardTime,
                               String alightFacility, double alightTime) {
    }

    /**
     * 单遍聚合器（端点/OD 两套口径共用一份栅格与街道上下文，不留存点集）：
     * 端点侧每识别出一次活动出行立即完成两端活动坐标的栅格分箱 + 街道归属；
     * OD 侧每识别出一段整段出行立即完成站点格 OD 配对累加。
     * cells 的 key/编码与 MatsimPopulationCache 完全同源，origin↔home 列、destination↔work 列。
     */
    static final class Aggregation {
        final double mercCellSize;
        private final MatsimPopulationCache.StreetIndex streets;
        private final Map<String, double[]> coordByFacility;
        private final MatsimPopulationCache.StreetLocator streetLocator;
        // ===== 端点（plans 活动出行口径，v4）=====
        final Long2IntOpenHashMap originCells = new Long2IntOpenHashMap();
        final Long2IntOpenHashMap destCells = new Long2IntOpenHashMap();
        final int[] streetOrigin;
        final int[] streetDest;
        /** plans 扫描人数（仅进度日志，不进 summary）。 */
        long persons;
        /** 公交出行数（plans 口径：含 pt leg 的 trip）。 */
        long journeys;
        /** 有 ≥1 次公交出行的人数（plans 口径）。 */
        long riders;
        long originPoints;
        long destPoints;
        long unassignedOrigin;
        long unassignedDest;
        /** 活动坐标转换失败数（与坐标缺失同待遇跳过，日志披露）。 */
        long transformFailures;
        // ===== OD（events 整段出行站点口径）=====
        /** 栅格 OD：O 格键 → (D 格键 → 模型原始人次)。 */
        final Long2ObjectOpenHashMap<Long2IntOpenHashMap> gridOd = new Long2ObjectOpenHashMap<>();
        /** 街道 OD 矩阵（行主序 [o*size+d]，有向，含 o==d 自环）。 */
        final int[] streetOd;
        long droppedTracks;
        /** 两端坐标齐全、计入栅格 OD 的整段出行数。 */
        long odJourneys;
        /** 任一端缺坐标、无法计入任何 OD 的整段出行数。 */
        long odSkipped;
        /** 计入栅格 OD 但任一端街道未命中、不计入街道 OD 的整段出行数。 */
        long odStreetUnassigned;

        Aggregation(double mercCellSize, MatsimPopulationCache.StreetIndex streets,
                    Map<String, double[]> coordByFacility) {
            this(mercCellSize, streets, coordByFacility, streets);
        }

        Aggregation(double mercCellSize, MatsimPopulationCache.StreetIndex streets,
                    Map<String, double[]> coordByFacility,
                    MatsimPopulationCache.StreetLocator streetLocator) {
            this.mercCellSize = mercCellSize;
            this.streets = streets;
            this.coordByFacility = coordByFacility;
            this.streetLocator = streetLocator;
            int size = streets == null ? 0 : streets.size();
            this.streetOrigin = new int[size];
            this.streetDest = new int[size];
            this.streetOd = new int[size * size];
        }

        /**
         * 端点抽取一个 person（必须存在明确 selectedPlan，照 MatsimPopulationCache.acceptPerson）：
         * 非 interaction 活动切分 trip，trip 内出现 TransitPassengerRoute 或
         * pt/bus/subway/rail/tram/ferry 等公交制式 leg 即计一次公交出行。
         */
        void acceptPerson(Person person, CoordinateTransformation ctf) {
            persons++;
            Plan plan = person.getSelectedPlan();
            if (plan == null) {
                throw new IllegalStateException("出行数据缺少 selectedPlan: person=" + person.getId());
            }
            boolean rode = false;
            Activity previous = null;
            boolean tripHasPt = false;
            for (PlanElement element : plan.getPlanElements()) {
                if (element instanceof Leg leg) {
                    if (isTransitLeg(leg)) {
                        tripHasPt = true;
                    }
                    continue;
                }
                if (!(element instanceof Activity act)) {
                    continue;
                }
                String type = act.getType();
                if (type != null && type.toLowerCase(Locale.ROOT).contains("interaction")) {
                    continue; // pt interaction 等中转活动不是 trip 边界（与人口分布同语义）
                }
                if (previous != null && tripHasPt) {
                    acceptPtTrip(transformedCoord(previous.getCoord(), ctf), transformedCoord(act.getCoord(), ctf));
                    rode = true;
                }
                previous = act;
                tripHasPt = false;
            }
            if (rode) {
                riders++;
            }
        }

        private static boolean isTransitLeg(Leg leg) {
            if (leg == null) return false;
            if (leg.getRoute() instanceof org.matsim.pt.routes.TransitPassengerRoute) return true;
            String mode = leg.getMode() == null ? "" : leg.getMode().toLowerCase(Locale.ROOT);
            return switch (mode) {
                case Constant.ROUTE_MODE_PT, "bus", "subway", "metro", "rail", "train", "tram", "ferry" -> true;
                default -> false;
            };
        }

        /** 一次公交出行：两端各自独立计入端点统计（缺坐标的端点跳过，journeys 照计）。 */
        void acceptPtTrip(Coord origin, Coord destination) {
            journeys++;
            addEndpoint(origin, true);
            addEndpoint(destination, false);
        }

        /** 坐标为 null / 转换失败的端点跳过（与人口分布 transformedCoord 同待遇）。 */
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
                throw new IllegalStateException("出行端点坐标转换失败: " + coord, e);
            }
        }

        /** 缺坐标的端点跳过；街道未命中计入 unassigned*（与人口分布同语义）。 */
        private void addEndpoint(Coord coord, boolean isOrigin) {
            if (coord == null) {
                return;
            }
            if (isOrigin) {
                originPoints++;
            } else {
                destPoints++;
            }
            (isOrigin ? originCells : destCells)
                    .addTo(MatsimPopulationCache.cellKey(coord.getX(), coord.getY(), mercCellSize), 1);
            int streetIdx = streetLocator == null ? -1 : streetLocator.locate(coord.getX(), coord.getY());
            if (streetIdx >= 0) {
                (isOrigin ? streetOrigin : streetDest)[streetIdx]++;
            } else if (isOrigin) {
                unassignedOrigin++;
            } else {
                unassignedDest++;
            }
        }

        /** OD 侧：一段整段出行计一对（首上车站, 末下车站）；任一端缺站坐标整体跳过（odSkipped）。 */
        void acceptJourney(RideSegment first, RideSegment last) {
            double[] oCoord = endpointCoord(first.boardFacility());
            double[] dCoord = endpointCoord(last.alightFacility());
            if (oCoord == null || dCoord == null) {
                odSkipped++;
                return;
            }
            odJourneys++;
            long oCell = MatsimPopulationCache.cellKey(oCoord[0], oCoord[1], mercCellSize);
            long dCell = MatsimPopulationCache.cellKey(dCoord[0], dCoord[1], mercCellSize);
            gridOd.computeIfAbsent(oCell, ignored -> new Long2IntOpenHashMap()).addTo(dCell, 1);
            int oStreet = streetLocator == null ? -1 : streetLocator.locate(oCoord[0], oCoord[1]);
            int dStreet = streetLocator == null ? -1 : streetLocator.locate(dCoord[0], dCoord[1]);
            if (oStreet >= 0 && dStreet >= 0) {
                streetOd[oStreet * streets.size() + dStreet]++;
            } else {
                odStreetUnassigned++;
            }
        }

        private double[] endpointCoord(String facilityId) {
            return facilityId == null ? null : coordByFacility.get(facilityId);
        }

        /** worker 私有聚合结果的确定性合并。 */
        void mergeFrom(Aggregation other) {
            if (other == null) {
                return;
            }
            mergeCounts(originCells, other.originCells);
            mergeCounts(destCells, other.destCells);
            for (int i = 0; i < streetOrigin.length; i++) {
                streetOrigin[i] += other.streetOrigin[i];
                streetDest[i] += other.streetDest[i];
            }
            for (Long2ObjectOpenHashMap.Entry<Long2IntOpenHashMap> entry : other.gridOd.long2ObjectEntrySet()) {
                Long2IntOpenHashMap target = gridOd.computeIfAbsent(
                        entry.getLongKey(), ignored -> new Long2IntOpenHashMap());
                mergeCounts(target, entry.getValue());
            }
            for (int i = 0; i < streetOd.length; i++) {
                streetOd[i] += other.streetOd[i];
            }
            persons += other.persons;
            journeys += other.journeys;
            riders += other.riders;
            originPoints += other.originPoints;
            destPoints += other.destPoints;
            unassignedOrigin += other.unassignedOrigin;
            unassignedDest += other.unassignedDest;
            transformFailures += other.transformFailures;
            droppedTracks += other.droppedTracks;
            odJourneys += other.odJourneys;
            odSkipped += other.odSkipped;
            odStreetUnassigned += other.odStreetUnassigned;
        }

        private static void mergeCounts(Long2IntOpenHashMap target, Long2IntOpenHashMap source) {
            for (Long2IntOpenHashMap.Entry entry : source.long2IntEntrySet()) {
                target.addTo(entry.getLongKey(), entry.getIntValue());
            }
        }
    }

    /**
     * OD 主流程：按 person 分组 → 时间排序 → enter/leave 交替配对成乘车段（配对与 dropped 口径复刻
     * MatsimTransferCache.collectPersonEvents）→ 按 30min/800m 链成整段出行 → 站点格 OD 聚合。
     */
    static void aggregateJourneys(
            Collection<PTPersonTrack> tracks,
            Map<String, double[]> coordByFacility,
            Aggregation out
    ) {
        if (tracks == null || tracks.isEmpty()) {
            return;
        }
        Map<String, List<PTPersonTrack>> byPerson = new HashMap<>();
        for (PTPersonTrack track : tracks) {
            String personId = idString(track.getPersonId());
            if (personId == null) {
                out.droppedTracks++; // 无 person 无法配对
                continue;
            }
            byPerson.computeIfAbsent(personId, ignored -> new ArrayList<>()).add(track);
        }
        for (List<PTPersonTrack> personTracks : byPerson.values()) {
            collectPersonJourneys(personTracks, coordByFacility, out);
        }
    }

    /** 大模型磁盘态 OD：按 person 分区逐组配对，不物化全量乘客轨迹。 */
    private static void aggregateJourneys(
            MatsimData data,
            Map<String, double[]> coordByFacility,
            Aggregation out
    ) {
        MatsimPersonTrackStore.forEachPerson(data, (personId, personTracks) -> {
            if (personId == null || personId.isBlank()) {
                out.droppedTracks += personTracks.size();
                return;
            }
            collectPersonJourneys(personTracks, coordByFacility, out);
        });
    }

    private static void collectPersonJourneys(
            List<PTPersonTrack> personTracks,
            Map<String, double[]> coordByFacility,
            Aggregation out
    ) {
        personTracks.sort(TRACK_TIME_ORDER);
        List<RideSegment> segments = new ArrayList<>();
        PTPersonTrack open = null;
        for (PTPersonTrack track : personTracks) {
            if (track.getEnter() == null) {
                out.droppedTracks++; // 上/下车标记缺失的坏记录
                continue;
            }
            if (track.getEnter()) {
                if (open != null) {
                    out.droppedTracks++; // 连续两条上车：前一次乘坐缺下车（模拟截断），无法闭合
                }
                open = track;
                continue;
            }
            if (open == null) {
                out.droppedTracks++; // 孤儿下车
                continue;
            }
            if (!Objects.equals(idString(open.getVehicleId()), idString(track.getVehicleId()))) {
                // 上下车车辆对不上：说明中间各丢了一条记录，两条都不可信
                out.droppedTracks += 2;
                open = null;
                continue;
            }
            segments.add(new RideSegment(
                    idString(open.getFacilityId()), safeTime(open),
                    idString(track.getFacilityId()), safeTime(track)));
            open = null;
        }
        if (open != null) {
            out.droppedTracks++; // 收尾未闭合的上车
        }
        if (segments.isEmpty()) {
            return;
        }

        RideSegment journeyFirst = segments.get(0);
        RideSegment journeyLast = segments.get(0);
        for (int i = 1; i < segments.size(); i++) {
            RideSegment next = segments.get(i);
            if (sameJourney(journeyLast, next, coordByFacility)) {
                journeyLast = next; // 链入当前整段出行
                continue;
            }
            out.acceptJourney(journeyFirst, journeyLast);
            journeyFirst = next;
            journeyLast = next;
        }
        out.acceptJourney(journeyFirst, journeyLast);
    }

    /**
     * 相邻乘车段是否属于同一整段出行：0 ≤ 上车−前段下车 ≤ 1800s 且前后站地面距离 ≤ 800m
     * （边界值含）。缺站点/缺坐标无法校验时保守断链（宁可拆成两段出行也不误并）。
     */
    static boolean sameJourney(RideSegment prev, RideSegment next, Map<String, double[]> coordByFacility) {
        double gap = next.boardTime() - prev.alightTime();
        if (gap < 0 || gap > JOURNEY_WINDOW_SECONDS) {
            return false;
        }
        if (prev.alightFacility() == null || next.boardFacility() == null) {
            return false;
        }
        double[] from = coordByFacility.get(prev.alightFacility());
        double[] to = coordByFacility.get(next.boardFacility());
        if (from == null || to == null) {
            return false;
        }
        return groundDistanceMeters(from[0], from[1], to[0], to[1]) <= JOURNEY_MAX_DIST_M;
    }

    // ===================================================================================
    // 组装：grid.bin / streets.json / od-streets.json / od-grid.bin / summary.json
    // ===================================================================================

    /** 五工件组装结果。 */
    static final class Artifacts {
        final byte[] gridBin;
        final byte[] odGridBin;
        final Map<String, Object> summary;
        final Map<String, Object> streets;
        final Map<String, Object> odStreets;

        private Artifacts(byte[] gridBin, byte[] odGridBin, Map<String, Object> summary,
                          Map<String, Object> streets, Map<String, Object> odStreets) {
            this.gridBin = gridBin;
            this.odGridBin = odGridBin;
            this.summary = summary;
            this.streets = streets;
            this.odStreets = odStreets;
        }
    }

    static Artifacts assemble(Aggregation aggregation, MatsimPopulationCache.StreetIndex streets, double scale) {
        // grid.bin 直接复用 population 的 PGRD 编码：home 列=起点、work 列=终点（前端同一解析器）
        byte[] gridBin = MatsimPopulationCache.encodeGrid(
                aggregation.originCells, aggregation.destCells, aggregation.mercCellSize, streets);
        int gridCells = (gridBin.length - MatsimPopulationCache.BIN_HEADER_BYTES)
                / MatsimPopulationCache.BIN_BYTES_PER_CELL;
        OdGridEncoded odGrid = encodeOdGrid(aggregation.gridOd, aggregation.mercCellSize, streets, MAX_GRID_OD_PAIRS);
        Map<String, Object> odStreets = buildOdStreets(aggregation, streets);
        int odStreetPairs = ((List<?>) odStreets.get("pairs")).size();
        return new Artifacts(gridBin, odGrid.bin,
                buildSummary(aggregation, gridCells, odGrid, odStreetPairs, scale),
                buildStreets(aggregation, streets), odStreets);
    }

    /** encodeOdGrid 结果：bin + 截断披露。 */
    record OdGridEncoded(byte[] bin, int writtenPairs, long droppedPairs, long droppedFlow) {
    }

    /**
     * tripends-od-grid.bin（小端）：
     * header = magic "PGOD" + version u16(=1) + count u32 + mercCellSize f64（共 18B）；
     * record × count（24B/对）= iO i32, jO i32, iD i32, jD i32, count u32, oStreet u16, dStreet u16。
     * 按人次降序写入（平序按 O/D 格键升序，可复现），前端可按前缀取 Top-K；
     * 超出 maxPairs 的低量对截断并计入 droppedPairs/droppedFlow（summary 披露）。
     * oStreet/dStreet 为“格中心”点面归属的街道要素索引（资源文件序），未命中写 {@link #OD_STREET_UNASSIGNED}——
     * 仅供前端行政区过滤/提示使用。
     */
    static OdGridEncoded encodeOdGrid(Long2ObjectOpenHashMap<Long2IntOpenHashMap> gridOd,
                                      double mercCellSize, MatsimPopulationCache.StreetIndex streets, int maxPairs) {
        // 小顶堆保留 Top-maxPairs：全序 = (count, oKey 取反, dKey 取反) 升序 → 堆顶是“最该被挤掉”的对。
        // 全序完备（同 count 下键唯一），Top 集合与哈希迭代顺序无关，构建可复现。
        Comparator<long[]> keepOrder = Comparator.<long[]>comparingLong(pair -> pair[2])
                .thenComparingLong(pair -> -pair[0])
                .thenComparingLong(pair -> -pair[1]);
        PriorityQueue<long[]> top = new PriorityQueue<>(Math.max(1, Math.min(maxPairs, 1024)), keepOrder);
        long totalPairs = 0;
        long totalFlow = 0;
        for (Long2ObjectOpenHashMap.Entry<Long2IntOpenHashMap> oEntry : gridOd.long2ObjectEntrySet()) {
            long oKey = oEntry.getLongKey();
            for (Long2IntOpenHashMap.Entry dEntry : oEntry.getValue().long2IntEntrySet()) {
                totalPairs++;
                totalFlow += dEntry.getIntValue();
                long[] pair = {oKey, dEntry.getLongKey(), dEntry.getIntValue()};
                if (top.size() < maxPairs) {
                    top.add(pair);
                } else if (keepOrder.compare(pair, top.peek()) > 0) {
                    top.poll();
                    top.add(pair);
                }
            }
        }
        List<long[]> pairs = new ArrayList<>(top);
        // 写入序：人次降序，平序 O/D 格键升序
        pairs.sort(Comparator.<long[]>comparingLong(pair -> -pair[2])
                .thenComparingLong(pair -> pair[0])
                .thenComparingLong(pair -> pair[1]));
        long keptFlow = 0;
        ByteBuffer buffer = ByteBuffer.allocate(OD_BIN_HEADER_BYTES + OD_BIN_BYTES_PER_PAIR * pairs.size())
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(OD_BIN_MAGIC);
        buffer.putShort((short) OD_BIN_VERSION);
        buffer.putInt(pairs.size());
        buffer.putDouble(mercCellSize);
        Long2IntOpenHashMap cellStreetCache = new Long2IntOpenHashMap();
        cellStreetCache.defaultReturnValue(Integer.MIN_VALUE);
        for (long[] pair : pairs) {
            keptFlow += pair[2];
            buffer.putInt(MatsimPopulationCache.cellI(pair[0]));
            buffer.putInt(MatsimPopulationCache.cellJ(pair[0]));
            buffer.putInt(MatsimPopulationCache.cellI(pair[1]));
            buffer.putInt(MatsimPopulationCache.cellJ(pair[1]));
            buffer.putInt((int) pair[2]);
            buffer.putShort((short) cellStreet(pair[0], mercCellSize, streets, cellStreetCache));
            buffer.putShort((short) cellStreet(pair[1], mercCellSize, streets, cellStreetCache));
        }
        return new OdGridEncoded(buffer.array(), pairs.size(), totalPairs - pairs.size(), totalFlow - keptFlow);
    }

    /** 格中心点面归属（memo）：命中返回街道要素索引，未命中/无街道索引返回 {@link #OD_STREET_UNASSIGNED}。 */
    private static int cellStreet(long cellKey, double mercCellSize,
                                  MatsimPopulationCache.StreetIndex streets, Long2IntOpenHashMap cache) {
        int cached = cache.get(cellKey);
        if (cached != Integer.MIN_VALUE) {
            return cached;
        }
        int result = OD_STREET_UNASSIGNED;
        if (streets != null) {
            double centerX = (MatsimPopulationCache.cellI(cellKey) + 0.5) * mercCellSize;
            double centerY = (MatsimPopulationCache.cellJ(cellKey) + 0.5) * mercCellSize;
            int idx = streets.locate(centerX, centerY);
            if (idx >= 0) {
                result = idx;
            }
        }
        cache.put(cellKey, result);
        return result;
    }

    /**
     * tripends-od-streets.json：街道级 OD 对（有向，含 o==d 自环）。
     * pairs = [[o, d, n], ...]，o/d 为街道要素索引（资源文件序，与 tripends-streets.json 行序一致），
     * n 为模型原始人次；按 n 降序（平序 o、d 升序）。totals 供前端对账：
     * sum(pairs.n) + odStreetUnassigned == odJourneys。
     */
    static Map<String, Object> buildOdStreets(Aggregation aggregation, MatsimPopulationCache.StreetIndex streets) {
        int size = streets == null ? 0 : streets.size();
        List<int[]> raw = new ArrayList<>();
        long pairFlow = 0;
        for (int o = 0; o < size; o++) {
            for (int d = 0; d < size; d++) {
                int n = aggregation.streetOd[o * size + d];
                if (n > 0) {
                    raw.add(new int[]{o, d, n});
                    pairFlow += n;
                }
            }
        }
        raw.sort(Comparator.<int[]>comparingInt(pair -> -pair[2])
                .thenComparingInt(pair -> pair[0])
                .thenComparingInt(pair -> pair[1]));
        List<List<Integer>> pairs = new ArrayList<>(raw.size());
        for (int[] pair : raw) {
            pairs.add(List.of(pair[0], pair[1], pair[2]));
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("flow", pairFlow);
        totals.put("odJourneys", aggregation.odJourneys);
        totals.put("odStreetUnassigned", aggregation.odStreetUnassigned);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pairs", pairs);
        payload.put("totals", totals);
        return payload;
    }

    /** tripends-summary.json（数量严格为模型文件原始值）。 */
    private static Map<String, Object> buildSummary(Aggregation aggregation, int gridCells,
                                                    OdGridEncoded odGrid, int odStreetPairs, double scale) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("windowSec", JOURNEY_WINDOW_SECONDS);
        params.put("maxDistM", (int) JOURNEY_MAX_DIST_M);
        params.put("modes", "all-transit"); // bus/subway/tram 全部计入整段出行（OD 链接口径）
        params.put("endpoints", "activity"); // v4：端点=活动出行起终点（plans）；OD 仍为站点口径

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "ready");
        summary.put("cacheVersion", TRIPENDS_CACHE_VERSION);
        summary.put("generatedAt", System.currentTimeMillis());
        summary.put("scale", 1.0);
        summary.put("quantityPolicy", "model-original");
        summary.put("cellSizeMeters", (int) MatsimPopulationCache.CELL_SIZE_METERS);
        summary.put("mercCellSize", aggregation.mercCellSize);
        summary.put("gridCells", gridCells);
        summary.put("params", params);
        // 出行分布监测（plans 活动出行口径）
        summary.put("journeys", aggregation.journeys);
        summary.put("riders", aggregation.riders);
        summary.put("originPoints", aggregation.originPoints);
        summary.put("destPoints", aggregation.destPoints);
        summary.put("unassignedOrigin", aggregation.unassignedOrigin);
        summary.put("unassignedDest", aggregation.unassignedDest);
        summary.put("droppedTracks", aggregation.droppedTracks);
        // 公交OD监测（events 站点口径）：口径与截断披露；odJourneys+odSkipped=events 整段出行数，
        // 与 journeys（plans 口径）允许少量口径差异（模拟截断/stuck 等）
        summary.put("odJourneys", aggregation.odJourneys);
        summary.put("odSkipped", aggregation.odSkipped);
        summary.put("odStreetUnassigned", aggregation.odStreetUnassigned);
        summary.put("odStreetPairs", odStreetPairs);
        summary.put("odGridPairs", odGrid.writtenPairs());
        summary.put("odGridDroppedPairs", odGrid.droppedPairs());
        summary.put("odGridDroppedFlow", odGrid.droppedFlow());
        return summary;
    }

    /**
     * tripends-streets.json：176 街道全量（含 0 值，顺序 = 资源文件序）+ totals。
     * 对账恒等式：sum(streets.origin) + unassignedOrigin == originPoints（destination 同理）。
     */
    private static Map<String, Object> buildStreets(Aggregation aggregation,
                                                    MatsimPopulationCache.StreetIndex streets) {
        List<Map<String, Object>> rows = new ArrayList<>(streets.size());
        long totalOrigin = 0;
        long totalDest = 0;
        for (int i = 0; i < streets.size(); i++) {
            MatsimPopulationCache.StreetRef street = streets.street(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", street.code());
            row.put("name", street.name());
            row.put("district", street.district());
            row.put("areaKm2", street.areaKm2());
            row.put("origin", aggregation.streetOrigin[i]);
            row.put("destination", aggregation.streetDest[i]);
            rows.add(row);
            totalOrigin += aggregation.streetOrigin[i];
            totalDest += aggregation.streetDest[i];
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("origin", totalOrigin);
        totals.put("destination", totalDest);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("streets", rows);
        payload.put("totals", totals);
        return payload;
    }

    // ===================================================================================
    // manifest 与文件读写（模式照 MatsimPopulationCache）
    // ===================================================================================

    private static Map<String, Object> manifest(MatsimData data, boolean ready) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", ready ? "ready" : "failed");
        result.put("cacheVersion", TRIPENDS_CACHE_VERSION);
        result.put("generatedAt", System.currentTimeMillis());
        sourceFingerprint(data, result);
        return result;
    }

    private static Map<String, Object> endpointManifest(MatsimData data, boolean ready) {
        return endpointManifest(data, ready ? "ready" : "failed", null);
    }

    private static Map<String, Object> endpointManifest(MatsimData data, String status, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("cacheVersion", TRIP_DISTRIBUTION_CACHE_VERSION);
        result.put("generatedAt", System.currentTimeMillis());
        if (message != null && !message.isBlank()) result.put("message", message);
        endpointSourceFingerprint(data, result);
        return result;
    }

    private static void endpointSourceFingerprint(MatsimData data, Map<String, Object> result) {
        putFileFingerprint(result, "plans", data.getOutfile() == null ? null : data.getOutfile().getPlans());
        result.put("streetsResource", MatsimPopulationCache.STREETS_RESOURCE);
        result.put("streetsSha256", MatsimPopulationCache.streetsGeojsonTag());
        result.put("transitModes", "route-or-pt-bus-subway-metro-rail-train-tram-ferry");
    }

    private static boolean sameEndpointSources(MatsimData data, Map<String, Object> manifest) {
        Map<String, Object> current = new LinkedHashMap<>();
        endpointSourceFingerprint(data, current);
        return MatsimSourceFingerprint.sameFlatFingerprint(current, manifest);
    }

    /**
     * 源指纹：plans（端点数据源，v4 起）+ events（OD 乘车流水数据源）
     * + transitSchedule（OD 站点坐标输入）+ 街道资源标识（路径 + 内容 sha256，资源升级即失效重建）。
     */
    private static void sourceFingerprint(MatsimData data, Map<String, Object> result) {
        putFileFingerprint(result, "plans", data.getOutfile() == null ? null : data.getOutfile().getPlans());
        putFileFingerprint(result, "events", data.getOutfile() == null ? null : data.getOutfile().getEvents());
        putFileFingerprint(result, "schedule",
                data.getOutfile() == null ? null : data.getOutfile().getTransitSchedule());
        result.put("streetsResource", MatsimPopulationCache.STREETS_RESOURCE);
        result.put("streetsSha256", MatsimPopulationCache.streetsGeojsonTag());
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
        return MatsimCachePaths.versionDir(data, TRIPENDS_CACHE_VERSION);
    }

    private static Path endpointCacheDir(MatsimData data) {
        return data.isLargeModel()
                ? MatsimCachePaths.versionDir(data, TRIP_DISTRIBUTION_CACHE_VERSION)
                : cacheDir(data);
    }

    private static Path endpointManifestPath(MatsimData data) {
        return endpointCacheDir(data).resolve(ENDPOINT_MANIFEST_FILE);
    }

    private static Path endpointSummaryPath(MatsimData data) {
        return endpointCacheDir(data).resolve(SUMMARY_FILE);
    }

    private static Path endpointStreetsPath(MatsimData data) {
        return endpointCacheDir(data).resolve(STREETS_FILE);
    }

    private static Path endpointGridPath(MatsimData data) {
        return endpointCacheDir(data).resolve(GRID_FILE);
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

    private static Path odStreetsPath(MatsimData data) {
        return cacheDir(data).resolve(OD_STREETS_FILE);
    }

    private static Path odGridPath(MatsimData data) {
        return cacheDir(data).resolve(OD_GRID_FILE);
    }

    private static long lastModified(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return 0L;
        }
        try {
            Path path = Path.of(filePath);
            return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0L;
        } catch (Exception e) {
            throw new IllegalStateException("读取源文件修改时间失败: " + filePath, e);
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
            throw new IllegalStateException("读取源文件大小失败: " + filePath, e);
        }
    }

    private static double safeTime(PTPersonTrack track) {
        Double time = track.getTime();
        if (time == null || Double.isNaN(time) || Double.isInfinite(time)) {
            return 0.0;
        }
        return time;
    }

    private static String idString(Object value) {
        return value == null ? null : value.toString();
    }
}
