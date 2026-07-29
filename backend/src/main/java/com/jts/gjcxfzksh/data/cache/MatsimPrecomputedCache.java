package com.jts.gjcxfzksh.data.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jts.gjcxfzksh.api.common.Constant;
import com.jts.gjcxfzksh.api.model.pt.PTCoord;
import com.jts.gjcxfzksh.api.model.pt.PTLink;
import com.jts.gjcxfzksh.api.model.vo.FacilityVO;
import com.jts.gjcxfzksh.api.model.vo.LineVO;
import com.jts.gjcxfzksh.api.model.vo.RouteDetailVO;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import com.jts.gjcxfzksh.data.entry.TileNetwork;
import com.jts.gjcxfzksh.data.id.RouteId;
import com.jts.gjcxfzksh.data.id.VehicleId;
import com.jts.gjcxfzksh.utils.DistanceUtil;
import com.jts.gjcxfzksh.utils.TransitMetrics;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Slf4j
public final class MatsimPrecomputedCache {

    // v9: 统计口径修复（TransitMetrics 统一实现）——车站300m覆盖率语义反转修复、
    //     车均日载客量只计上车、占位指标(ylklbl/dbczkl)移除、满载率统一口径，需重算缓存
    // v10: 常住人口密度改为全体 agent 口径；新增 万人保有量(wrbyl)、真实口径的单班次载客量(dbczkl)，需重算缓存
    // v11: 密度类指标面积回退用站点凸包估算（desc.json 缺失时原为除以 1）；
    //      保有量/车均日载客量分母改用"高峰同时在营车辆数"车队估算，需重算缓存
    // v12: 线路摘要(lines.json)新增抽稀后的真实路网走向 geometry，
    //      前端全网线路图层按 network.xml 几何绘制（原为站点直线连接），需重算缓存
    // v13: 统计口径修复批次（需重算缓存）：
    //      ①lines.json/route-details 补齐 lc(非直线系数)/takeRate(满载率)/passenger(日客流)——
    //        原缓存构建只调构造器，三指标恒为 0，缓存命中时前端显示 0%/0；
    //      ②大模型（population 为空）时人口类指标（czrkmd/fxfdl/yxsdb/pjhcsj）输出 null，
    //        不再把 0 值当真值固化进 info.json；
    //      ③指纹补充 transitVehicles 与 desc.json 面积，容量/面积变更后旧值不再静默下发；
    //      ④linkstats 流量列剔除 HRS0-24avg 全跨度汇总列（原与逐时列一起累加，flow≈真值×2）；
    //      ⑤线路客流强度(xlklqd)分组与键改用 lineId+routeId 复合键，跨线路同名 routeId 不再混计；
    //      ⑥公交分担率(fxfdl)精度提升到 0.01%（原 1% 步进）。
    // v14: 数量固定为模型原始值（不扩样）；面积缺失不再用站点凸包猜测；
    //      gjxwmd 改为汽电车线路经过的双向去重物理道路；fxfdl 改为 trip 主方式口径；
    //      xlmzl 改为峰值在车人数/容量；pjhcsj 统一为全天均值分钟；
    //      历史 rcxcs/xlklqd 口径（已由 v23 的公交居民出行/运营车公里口径替代）。
    // v15: V6 不常驻 Population 明细时，从 population-v3 流式派生摘要读取
    //      coverage300Percent/tripModeSharePercent/speedKmh/averageWaitMinutes，
    //      不再把已有正确派生值的指标误报为 unsupported。
    // v16: 评价指标全面收紧为 bus-only；覆盖/直线距离显式依赖运行时 CRS；线路里程严格使用
    //      link.length，近闭合环线排除，缺 route/link 不再静默下发部分真值；车队缺时长不再猜值。
    // v17: 依赖 population-v5 的稳定混合换乘主方式，并透传覆盖率分母完整性元数据。
    // v18: xlcfxs 按 JT/T 1457—2023 改为各 TransitLine 方向平均长度之和/无向去重物理线网长度，
    //      同方向 TransitRoute 变体不再重复放大分子。
    // v19: 常住人口密度分子改为 plans 中有有效首个 home 位置的人数，不再使用全部 agent 数。
    // v20: 公交线网密度显式固化为“无向去重道路中心线长度/行政区总面积（暂行）”，
    //      并输出分子、分母及口径元数据供体检评估对账。
    // v23: 线路平均高峰满载率按“班次最大站段满载率的班次均值”计算；
    //      线路客流强度改为公交上车人次/计划运营车公里；人均日出行次数改为公交主方式且排除地铁。
    // v24: 运营服务指标统一读取 population-v9：公交/小汽车速度比改为早晚高峰总里程/总时间，
    //      候车为 bus-only 上车样本，新增完整 OD 分母的公交换乘次数与公交—轨道接驳比例；
    //      非直线系数改为线路等权并输出异常线路诊断。
    // v25: 密度指标改用真实数据行政区边界，预计算全市及各区人口、面积和裁切线网长度。
    public static final String VISUAL_CACHE_VERSION = "visual-v25";
    private static final int VISUAL_TILE_ZOOM = 12;
    private static final int MIN_VISUAL_TILE_ZOOM = 8;
    private static final int ROUTE_DETAIL_SHARD_COUNT = 32;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, RouteDetailVO>> ROUTE_DETAIL_MAP_TYPE = new TypeReference<>() {};
    private static final String INFO_FILE = "info.json";
    private static final String LINES_FILE = "lines.json.gz";
    private static final String STATIONS_FILE = "stations.json.gz";
    private static final String ROUTE_INDEX_FILE = "route-index.json";
    private static final String NETWORK_TILES_DIR = "network-tiles";
    private static final String ROUTE_TILES_DIR = "route-tiles";
    private static final String ROUTE_DETAILS_DIR = "route-details";

    // —— 读路径内存缓存 ——
    // routeDetail 原来每次请求都读盘：解析 route-index.json + 解压解析整个分片
    // （含全模型 1/32 线路的完整 links；模型数据常在外置盘），选线（正向+反向并发）
    // 单次可达数百毫秒。索引/分片解析结果按绝对路径（含缓存版本目录）做小容量 LRU，
    // manifest 就绪校验结果按 cacheDir 记忆化；同 JVM 内重建缓存时统一失效（见 invalidateMemoryCache）。
    private static final int ROUTE_INDEX_MEMORY_LIMIT = 4;
    private static final int ROUTE_SHARD_MEMORY_LIMIT = 8;
    private static final Map<String, Map<String, String>> ROUTE_INDEX_MEMORY =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, String>> eldest) {
                    return size() > ROUTE_INDEX_MEMORY_LIMIT;
                }
            });
    private static final Map<String, Map<String, RouteDetailVO>> ROUTE_SHARD_MEMORY =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, RouteDetailVO>> eldest) {
                    return size() > ROUTE_SHARD_MEMORY_LIMIT;
                }
            });
    private static final Set<String> READY_CACHE_DIRS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void invalidateMemoryCache(MatsimData data) {
        String cacheDirPrefix = cacheDir(data).toString();
        READY_CACHE_DIRS.removeIf(key -> key.startsWith(cacheDirPrefix + "::"));
        ROUTE_INDEX_MEMORY.remove(routeIndexPath(data).toString());
        synchronized (ROUTE_SHARD_MEMORY) {
            ROUTE_SHARD_MEMORY.keySet().removeIf(key -> key.startsWith(cacheDirPrefix));
        }
    }

    private MatsimPrecomputedCache() {
    }

    public static void prepareOnModelLoad(MatsimData data) {
        try {
            ensureVisualCache(data);
        } catch (Exception e) {
            log.error("模型预计算缓存生成失败: model={}, error={}", data.getName(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public static Map<String, Object> readInfo(MatsimData data) {
        if (!isVisualCacheReady(data)) {
            return null;
        }
        try {
            return JSON.readValue(infoPath(data).toFile(), MAP_TYPE);
        } catch (Exception e) {
            log.warn("读取数据总览预计算失败: {}", infoPath(data), e);
            return null;
        }
    }

    public static List<Object> readLines(MatsimData data) {
        if (!isVisualCacheReady(data)) {
            return null;
        }
        try {
            return readGzipJson(linesPath(data), LIST_TYPE);
        } catch (Exception e) {
            log.warn("读取线路预计算失败: {}", linesPath(data), e);
            return null;
        }
    }

    public static List<Object> readStations(MatsimData data) {
        if (!isVisualCacheReady(data)) {
            return null;
        }
        try {
            return readGzipJson(stationsPath(data), LIST_TYPE);
        } catch (Exception e) {
            log.warn("读取站点预计算失败: {}", stationsPath(data), e);
            return null;
        }
    }

    public static List<Object> readNetworkTile(MatsimData data, int z, int tileX, int tileY) {
        if (!isVisualCacheReady(data)) {
            return null;
        }
        return readTile(data, NETWORK_TILES_DIR, z, tileX, tileY);
    }

    public static List<Object> readRouteTile(MatsimData data, int z, int tileX, int tileY) {
        if (!isVisualCacheReady(data)) {
            return null;
        }
        return readTile(data, ROUTE_TILES_DIR, z, tileX, tileY);
    }

    public static RouteDetailVO readRouteDetail(MatsimData data, String routeId) {
        return readRouteDetail(data, null, routeId);
    }

    public static RouteDetailVO readRouteDetail(MatsimData data, String lineId, String routeId) {
        if (!isVisualCacheReady(data)) {
            return null;
        }
        if (routeId == null || routeId.isBlank()) {
            return null;
        }
        try {
            Path indexPath = routeIndexPath(data);
            Map<String, String> index = ROUTE_INDEX_MEMORY.get(indexPath.toString());
            if (index == null) {
                index = JSON.readValue(indexPath.toFile(), STRING_MAP_TYPE);
                ROUTE_INDEX_MEMORY.put(indexPath.toString(), index);
            }
            String key = lineId == null || lineId.isBlank() ? routeId : routeKey(lineId, routeId);
            String file = index.get(key);
            if (file == null || file.isBlank()) {
                return null;
            }
            Path shardPath = routeDetailsDir(data).resolve(file);
            Map<String, RouteDetailVO> shard = ROUTE_SHARD_MEMORY.get(shardPath.toString());
            if (shard == null) {
                shard = readGzipJson(shardPath, ROUTE_DETAIL_MAP_TYPE);
                ROUTE_SHARD_MEMORY.put(shardPath.toString(), shard);
            }
            return shard.get(key);
        } catch (Exception e) {
            log.warn("读取线路详情预计算失败: model={}, lineId={}, routeId={}", data.getName(), lineId, routeId, e);
            return null;
        }
    }

    private static void ensureVisualCache(MatsimData data) {
        // per-model 锁：模型 A 构建期间不阻塞模型 B（原为类级 synchronized 全局锁）
        synchronized (ModelBuildLocks.lockFor("visual", data)) {
            ensureVisualCacheLocked(data);
        }
    }

    private static void ensureVisualCacheLocked(MatsimData data) {
        if (isVisualCacheReady(data)) {
            return;
        }
        try {
            invalidateMemoryCache(data);
            deleteDirectory(cacheDir(data));
            Files.createDirectories(cacheDir(data));
            PassengerStats passengerStats = passengerStats(data);
            Map<String, Object> info = buildInfo(data, passengerStats);
            List<LineVO> routeDetails = buildLines(data, passengerStats);
            List<LineVO> lines = buildLineSummaries(routeDetails);
            List<FacilityVO> stations = buildStations(data);

            writeJsonAtomic(infoPath(data), info);
            writeGzipJson(linesPath(data), lines);
            writeGzipJson(stationsPath(data), stations);
            // 大模型常驻的是公交精简网；道路底图必须临时读取原始完整 network，不能把精简网
            // 固化成“完整路网”缓存。写盘后立即释放 map，降低与线路瓦片的峰值叠加。
            Network visualNetwork = data.isLargeModel() ? loadFullNetworkForVisual(data) : data.getNetwork();
            Map<String, List<PTLink>> networkTiles = buildNetworkTiles(data, visualNetwork);
            writeTileDirectory(data, NETWORK_TILES_DIR, VISUAL_TILE_ZOOM, networkTiles);
            int networkTileCount = networkTiles.size();
            networkTiles.clear();
            visualNetwork = null;
            Map<String, List<PTLink>> routeTiles = buildRouteTiles(data);
            writeTileDirectory(data, ROUTE_TILES_DIR, VISUAL_TILE_ZOOM, routeTiles);
            writeJsonAtomic(routeIndexPath(data), writeRouteDetails(data, routeDetails));
            writeJsonAtomic(manifestPath(data), manifest(data, true));
            MatsimCachePaths.deleteOtherVersions(data, "visual-v", VISUAL_CACHE_VERSION);
            // 重建窗口期并发读者可能把删除前的旧文件解析结果写回内存，完成后再失效一次兜底
            invalidateMemoryCache(data);

            log.info("模型可视化预计算完成: model={}, lines={}, stations={}, networkTiles={}, routeTiles={}",
                    data.getName(), lines.size(), stations.size(), networkTileCount, routeTiles.size());
        } catch (Exception e) {
            try {
                writeJsonAtomic(manifestPath(data), manifest(data, false));
            } catch (Exception ignored) {
            }
            throw new RuntimeException(e);
        }
    }

    public static boolean isVisualCacheReady(MatsimData data) {
        // 就绪校验（8 次 stat + manifest 解析）在每次瓦片/详情读取时都会执行，外置盘上开销可观；
        // 校验通过后按 cacheDir 记忆化（缓存只在 ensureVisualCacheLocked 内重建，重建时失效）
        Path manifestPath = manifestPath(data);
        if (!Files.exists(manifestPath)
                || !Files.exists(infoPath(data))
                || !Files.exists(linesPath(data))
                || !Files.exists(stationsPath(data))
                || !Files.exists(routeIndexPath(data))
                || !Files.isDirectory(tileDir(data, NETWORK_TILES_DIR, VISUAL_TILE_ZOOM))
                || !Files.isDirectory(tileDir(data, ROUTE_TILES_DIR, VISUAL_TILE_ZOOM))
                || !Files.isDirectory(routeDetailsDir(data))) {
            return false;
        }
        // 缓存工件根本不存在时先快速返回，避免冷模型列表请求为 15GB
        // events/plans 做不必要的内容取样。只对已存在完整工件的候选缓存校验源版本。
        // visualCacheTag 除源文件 revision 外还包含面积和 visual 公式版本。
        // 只用 modelRevision 会在用户补填面积后仍命中进程内旧 info.json。
        String memoKey = cacheDir(data) + "::" + visualCacheTag(data);
        if (READY_CACHE_DIRS.contains(memoKey)) {
            return true;
        }
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath.toFile(), MAP_TYPE);
            boolean ready = "ready".equals(manifest.get("status"))
                    && VISUAL_CACHE_VERSION.equals(manifest.get("cacheVersion"))
                    && sameSources(data, manifest);
            if (ready) {
                READY_CACHE_DIRS.add(memoKey);
            }
            return ready;
        } catch (Exception e) {
            log.warn("可视化缓存状态读取失败: {}", manifestPath, e);
            return false;
        }
    }

    private static Map<String, Object> buildInfo(MatsimData data, PassengerStats passengerStats) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> availability = new LinkedHashMap<>();
        TransitMetrics.RoadTransitContext roadTransit =
                TransitMetrics.RoadTransitContext.from(data.getSchedule());
        Object runtimeCrs = data.getSchedule() == null ? null
                : data.getSchedule().getAttributes().getAttribute("coordinateReferenceSystem");
        TransitMetrics.MetricCoordinateContext coordinates =
                TransitMetrics.MetricCoordinateContext.fromCrs(
                        runtimeCrs == null ? null : String.valueOf(runtimeCrs));
        TransitMetrics.RoadNetworkStats roadNetwork = TransitMetrics.roadNetworkStats(
                data.getSchedule(), data.getNetwork(), roadTransit);
        Set<Coord> coords = roadTransit.stopCoords();
        Map<String, Object> populationSummary = MatsimPopulationCache.readPopulationSummary(data);
        Map<String, Object> derivedPopulationMetrics = populationDerivedMetrics(populationSummary);
        boolean hasDerivedPopulationMetrics = !derivedPopulationMetrics.isEmpty();
        Long personCount = populationCount(data, populationSummary);
        Long residentCount = residentHomePersonCount(data, populationSummary);
        Map<String, Object> densityByDistrict = MatsimAdministrativeDensityMetrics.compute(
                data, roadTransit, roadNetwork, residentCount);
        Map<?, ?> cityDensity = densityByDistrict.get(
                MatsimAdministrativeDensityMetrics.ALL_CITY) instanceof Map<?, ?> row ? row : Map.of();
        double configuredArea = data.getArea();
        Double areaKm2 = null;
        if (cityDensity.get("areaKm2") instanceof Number number) {
            areaKm2 = number.doubleValue();
        } else if (Double.isFinite(configuredArea) && configuredArea > 0) {
            areaKm2 = configuredArea;
        }
        if (cityDensity.get("residentHomePersons") instanceof Number number) {
            residentCount = Math.max(0L, number.longValue());
        }
        result.put("densityByDistrict", densityByDistrict);
        result.put("densityPolicy", MatsimAdministrativeDensityMetrics.POLICY);
        result.put("densityBoundarySignature",
                MatsimAdministrativeDensityMetrics.boundaryFingerprint(data));
        Population population = data.getPopulation();
        boolean hasPlanDetails = population != null && !population.getPersons().isEmpty();
        result.put("czrkmd", areaKm2 == null || residentCount == null ? null
                : (int) Math.round(residentCount / areaKm2));
        result.put("residentHomePersons", residentCount);
        if (areaKm2 == null) {
            unavailable(availability, "czrkmd", "nodata",
                    "真实数据行政区边界与 desc.json 均未提供有效面积");
            unavailable(availability, "gjxwmd", "nodata",
                    "真实数据行政区边界与 desc.json 均未提供有效面积");
        } else if (residentCount == null) {
            unavailable(availability, "czrkmd", "unsupported", "缺少 plans 常住人口 home 位置计数工件");
        }

        Double networkLength = cityDensity.get("busNetworkLengthMeters") instanceof Number number
                ? number.doubleValue() : roadNetwork.lengthMeters();
        result.put("busNetworkLengthMeters", networkLength);
        Double busNetworkDensity = TransitMetrics.busNetworkDensityKmPerKm2(networkLength, areaKm2);
        result.put("gjxwmd", busNetworkDensity == null ? null : round2(busNetworkDensity));
        result.put("busNetworkAreaKm2", areaKm2);
        result.put("busNetworkLengthPolicy", TransitMetrics.BUS_NETWORK_LENGTH_POLICY);
        result.put("busNetworkAreaPolicy", TransitMetrics.BUS_NETWORK_AREA_POLICY);
        if (networkLength == null) {
            unavailable(availability, "gjxwmd", "nodata",
                    roadNetwork.missingGeometryRoutes() > 0
                            ? "公交线路引用了缺失或无有效长度的 route/link"
                            : "没有有效的公交线路物理路段");
        }

        TransitMetrics.CoverageStats directCoverage = hasPlanDetails
                && roadTransit.coordinateTransformFailures() == 0
                ? TransitMetrics.coverage300Stats(coords, population, coordinates) : null;
        Object coverageValue = hasDerivedPopulationMetrics
                ? derivedPopulationMetrics.get("fgl_300")
                : TransitMetrics.coverageResult(directCoverage == null ? null : directCoverage.percent());
        Object modeShareValue = hasDerivedPopulationMetrics
                ? derivedPopulationMetrics.get("fxfdl")
                : hasPlanDetails ? busModeShare(TransitMetrics.busTripShareStats(population, roadTransit)) : null;
        result.put("fgl_300", coverageValue);
        result.put("coverageValidHomePersons", hasDerivedPopulationMetrics
                ? derivedPopulationMetrics.get("coverageValidHomePersons")
                : directCoverage == null ? null : directCoverage.validHomePersons());
        result.put("coverageTotalPersons", hasDerivedPopulationMetrics
                ? derivedPopulationMetrics.get("coverageTotalPersons") : personCount);
        result.put("coverageMissingHomePersons", hasDerivedPopulationMetrics
                ? derivedPopulationMetrics.get("coverageMissingHomePersons")
                : directCoverage == null || personCount == null || !coordinates.isSupported()
                ? null : Math.max(0L, personCount - directCoverage.validHomePersons()));
        result.put("coordinateTransformFailures", hasDerivedPopulationMetrics
                ? derivedPopulationMetrics.get("coordinateTransformFailures") : null);
        result.put("coverageStatus", hasDerivedPopulationMetrics
                ? derivedPopulationMetrics.get("coverageStatus")
                : !hasPlanDetails || !coordinates.isSupported()
                || roadTransit.coordinateTransformFailures() > 0 ? "unsupported"
                : directCoverage == null || directCoverage.percent() == null ? "nodata" : "ready");
        result.put("coverageDenominatorPolicy", "valid-first-home");
        result.put("scheduleCoordinateTransformFailures", roadTransit.coordinateTransformFailures());
        result.put("fxfdl", modeShareValue);
        if (hasDerivedPopulationMetrics) {
            if (Boolean.TRUE.equals(((Map<?, ?>) coverageValue).get("nodata"))) {
                unavailable(availability, "fgl_300", "nodata", "population-v9 无可用的站点300m覆盖样本");
            }
            if (!(modeShareValue instanceof Map<?, ?> shares) || shares.isEmpty()) {
                unavailable(availability, "fxfdl", "nodata", "population-v9 中无可用的机动化出行 trip");
            }
        } else if (!hasPlanDetails) {
            unavailable(availability, "fgl_300", "unsupported", "当前加载模式仅保留人口计数，未保留 plans 坐标明细");
            unavailable(availability, "fxfdl", "unsupported", "当前加载模式未保留 plans 出行链明细");
        } else if (!coordinates.isSupported()) {
            result.put("fgl_300", null);
            unavailable(availability, "fgl_300", "unsupported", "模型未声明可识别的坐标系，不能计算300m地面距离");
        } else if (roadTransit.coordinateTransformFailures() > 0) {
            result.put("fgl_300", null);
            unavailable(availability, "fgl_300", "unsupported",
                    "部分公交站坐标转换失败，不能用剩余站点计算部分覆盖率");
        }

        long boardings = passengerStats.roadBoardings;
        result.put("boardings", boardings);
        result.put("allTransitBoardings", passengerStats.totalBoardings);
        // 万人公共交通车辆保有量：全网运营车辆 ID 去重后按车长折算标台。
        TransitMetrics.RoadFleetInventoryStats fleetStats =
                TransitMetrics.roadFleetInventory(data.getSchedule(), data.getTv());
        Long fleetSize = fleetStats.operatingVehicles();
        Double standardVehicles = fleetStats.standardVehicles();
        result.put("operatingVehicles", fleetSize);
        result.put("standardVehicles", standardVehicles);
        result.put("wrbyl", personCount == null || personCount <= 0
                || standardVehicles == null || standardVehicles <= 0
                ? null : round2(standardVehicles / (personCount / 10000.0)));
        if (personCount == null) {
            unavailable(availability, "wrbyl", "unsupported", "缺少可用的人口计数工件");
        }
        long departureTotal = roadTransit.departureCount();
        TransitMetrics.BusOperatingEfficiency efficiency = TransitMetrics.busOperatingEfficiency(
                boardings, fleetSize == null ? 0 : fleetSize, departureTotal);
        // 车均日载客量 = 日客运总量(上车) / 全网去重运营车辆数。
        result.put("cjrzkl", efficiency.perVehicleDaily() == null
                ? null : round2(efficiency.perVehicleDaily()));
        if (!fleetStats.hasOfficialStandardVehicles()) {
            unavailable(availability, "wrbyl", "unsupported",
                    "公交车辆或车型车长不完整，无法按官方车长系数折算标台");
        }
        if (fleetSize == null) {
            unavailable(availability, "cjrzkl", "nodata", "时刻表没有引用有效的公交车辆ID");
        }
        // 单班次载客量   人次/班 = 日客运总量(上车) / 日发班次总数
        result.put("dbczkl", efficiency.perDeparture() == null
                ? null : round2(efficiency.perDeparture()));
        // ylklbl(依赖客流比例)为占位实现，已移除，接入真实口径前不下发
        Double dailyTripsPerPerson = dailyTripsPerPerson(data, residentCount);
        result.put("rcxcs", dailyTripsPerPerson == null ? null : round3(dailyTripsPerPerson));
        if (dailyTripsPerPerson == null) {
            unavailable(availability, "rcxcs", "unsupported",
                    "缺少公交主方式完整出行次数、常住人口分母，或存在无法判定制式的居民 legacy pt 出行");
        }
        TransitMetrics.RouteShapeStats routeShape = TransitMetrics.roadRouteShapeStats(
                data.getSchedule(), data.getNetwork(), roadTransit, coordinates);
        Double nonLinearCoefficient = routeShape.averageNonLinearCoefficient() == null
                ? null : round2(routeShape.averageNonLinearCoefficient());
        Double repetitionCoefficient = routeShape.repetitionCoefficient() == null
                ? null : round2(routeShape.repetitionCoefficient());
        result.put("xlfzxxs", nonLinearCoefficient);
        result.put("xlcfxs", repetitionCoefficient);
        result.put("validRoutes", routeShape.validRoutes());
        result.put("excludedLoopRoutes", routeShape.excludedLoopRoutes());
        result.put("missingGeometryRoutes", routeShape.missingGeometryRoutes());
        result.put("maxNonLinearCoefficient", routeShape.maxNonLinearCoefficient() == null
                ? null : round2(routeShape.maxNonLinearCoefficient()));
        result.put("abnormalNonLinearLines", routeShape.abnormalNonLinearLines());
        result.put("maxNonLinearLineId", routeShape.maxNonLinearLineId());
        result.put("abnormalNonLinearLineIds", routeShape.abnormalNonLinearLineIds());
        if (nonLinearCoefficient == null) {
            unavailable(availability, "xlfzxxs", coordinates.isSupported() ? "nodata" : "unsupported",
                    coordinates.isSupported()
                            ? routeShape.missingGeometryRoutes() > 0
                                    ? "部分公交线路缺 route/link 或首末站坐标，未下发部分真值"
                                    : "没有有效的非环形公交线路"
                            : "模型未声明可识别的坐标系，不能计算首末站地面直线距离");
        }
        if (repetitionCoefficient == null) {
            unavailable(availability, "xlcfxs", "nodata",
                    routeShape.missingGeometryRoutes() > 0
                            ? "部分公交线路缺 route/link，未下发部分真值"
                            : "没有有效的公交线路或物理路段");
        }
        TransitMetrics.PeakAverageLoadStats peakLoad = passengerStats.peakLoad.finish();
        Double peakAverageLoadRate = peakLoad.percent() == null ? null : round2(peakLoad.percent());
        result.put("xlmzl", peakAverageLoadRate);
        result.put("peakAverageLoadRatePercent", peakAverageLoadRate);
        result.put("peakScheduledDepartures", peakLoad.scheduledPeakDepartures());
        result.put("peakValidCapacityDepartures", peakLoad.validCapacityDepartures());
        result.put("peakMissingCapacityDepartures", peakLoad.missingCapacityDepartures());
        if (peakAverageLoadRate == null) {
            unavailable(availability, "xlmzl",
                    peakLoad.missingCapacityDepartures() > 0 ? "unsupported" : "nodata",
                    peakLoad.missingCapacityDepartures() > 0
                            ? "高峰公交班次存在缺失车辆或额定载客量，不能下发部分真值"
                            : "早晚高峰时段没有有效公交班次");
        }

        Map<String, Double> xlklqd = routePersonStrength(data, passengerStats);
        result.put("xlklqd", xlklqd.entrySet().stream()
                .limit(5)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> round2(entry.getValue()),
                        (oldValue, newValue) -> oldValue,
                        LinkedHashMap::new
                )));
        TransitMetrics.RoadOperatingDistanceStats operatingDistance =
                TransitMetrics.roadOperatingDistanceStats(
                        data.getSchedule(), data.getNetwork(), roadTransit);
        Double passengerStrength = TransitMetrics.busPassengerStrength(boardings, operatingDistance);
        Double networkPassengerStrength = passengerStrength == null ? null : round2(passengerStrength);
        result.put("xlklqd_total", networkPassengerStrength);
        result.put("busPassengerBoardingsPerVehicleKm", networkPassengerStrength);
        result.put("busOperatingVehicleKilometers", operatingDistance.vehicleKilometers());
        result.put("xlklqd_sum", networkPassengerStrength);
        if (networkPassengerStrength == null) {
            unavailable(availability, "xlklqd", operatingDistance.missingGeometryRoutes() > 0
                            ? "unsupported" : "nodata",
                    operatingDistance.missingGeometryRoutes() > 0
                            ? "有发班的公交路径缺少有效线路里程，不能下发部分真值"
                            : "缺少公交上车人次或计划运营车公里");
        }
        TransitMetrics.PeakOperatingSpeedStats peakBusSpeed =
                TransitMetrics.roadBusPeakOperatingSpeedStats(
                        data.getSchedule(), data.getNetwork(), roadTransit);
        result.put("peakBusOperatingDistanceMeters", peakBusSpeed.distanceMeters());
        result.put("peakBusOperatingTravelSeconds", peakBusSpeed.travelSeconds());
        result.put("peakBusOperatingDepartures", peakBusSpeed.samples());
        if (hasDerivedPopulationMetrics) {
            Map<String, Object> speeds = new LinkedHashMap<>();
            Object cachedSpeeds = derivedPopulationMetrics.get("yxsdb");
            if (cachedSpeeds instanceof Map<?, ?> source) {
                source.forEach((key, value) -> {
                    if (key != null) speeds.put(String.valueOf(key), value);
                });
            }
            Double busKmh = peakBusSpeed.kmh();
            speeds.put("ptAvg", busKmh == null ? null : round2(busKmh));
            speeds.put("busAvg", busKmh == null ? null : round2(busKmh));
            Object awaitMinutes = derivedPopulationMetrics.get("pjhcsj");
            result.put("yxsdb", speeds);
            result.put("pjhcsj", awaitMinutes);
            result.put("pjhccs", derivedPopulationMetrics.get("pjhccs"));
            result.put("gjjbbl", derivedPopulationMetrics.get("gjjbbl"));
            result.put("busServiceJourneys", derivedPopulationMetrics.get("busServiceJourneys"));
            result.put("busServiceTransfers", derivedPopulationMetrics.get("busServiceTransfers"));
            result.put("busRailJourneys", derivedPopulationMetrics.get("busRailJourneys"));
            result.put("peakCarDistanceMeters", derivedPopulationMetrics.get("peakCarDistanceMeters"));
            result.put("peakCarTravelSeconds", derivedPopulationMetrics.get("peakCarTravelSeconds"));
            result.put("peakCarSamples", derivedPopulationMetrics.get("peakCarSamples"));
            result.put("speedPeriodPolicy", derivedPopulationMetrics.get("speedPeriodPolicy"));
            result.put("carSpeedSpatialScope", derivedPopulationMetrics.get("carSpeedSpatialScope"));
            result.put("busWaitSamples", derivedPopulationMetrics.get("busWaitSamples"));
            if (!hasBothSpeeds(speeds)) {
                unavailable(availability, "yxsdb",
                        peakBusSpeed.missingGeometryRoutes() > 0 ? "unsupported" : "nodata",
                        peakBusSpeed.missingGeometryRoutes() > 0
                                ? "高峰公交班次存在缺失线路里程或行程时间，不能下发部分真值"
                                : "population-v9 缺少高峰公交或小汽车有效速度样本");
            }
            if (!(awaitMinutes instanceof Number)) {
                unavailable(availability, "pjhcsj", "nodata", "population-v9 中无可用的公交候车时间样本");
            }
            if (!(result.get("pjhccs") instanceof Number)) {
                unavailable(availability, "pjhccs",
                        "unsupported".equals(populationSummary.get("busServiceJourneyStatus"))
                                ? "unsupported" : "nodata",
                        "unsupported".equals(populationSummary.get("busServiceJourneyStatus"))
                                ? "plans 中存在无法可靠判定制式的公共交通出行"
                                : "population-v9 中无含公交乘坐段的完整 OD 出行");
            }
            if (!(result.get("gjjbbl") instanceof Number)) {
                unavailable(availability, "gjjbbl",
                        "unsupported".equals(populationSummary.get("busServiceJourneyStatus"))
                                ? "unsupported" : "nodata",
                        "unsupported".equals(populationSummary.get("busServiceJourneyStatus"))
                                ? "plans 中存在无法可靠判定制式的公共交通出行"
                                : "population-v9 中无含公交乘坐段的完整 OD 出行");
            }
        } else if (hasPlanDetails) {
            Map<String, Double> speeds = runSpeed(
                    population, data.getSchedule(), data.getNetwork(), roadTransit);
            result.put("yxsdb", speeds);
            Double awaitMinutes = TransitMetrics.averageRoadBusAwaitMinutes(population, roadTransit);
            result.put("pjhcsj", awaitMinutes == null ? null : round2(awaitMinutes));
            TransitMetrics.BusServiceJourneyStats serviceJourneys =
                    TransitMetrics.busServiceJourneyStats(population, roadTransit);
            result.put("pjhccs", serviceJourneys.unresolvedLegacyPtJourneys() > 0
                    ? null : round4(serviceJourneys.averageTransfers()));
            result.put("gjjbbl", serviceJourneys.unresolvedLegacyPtJourneys() > 0
                    ? null : round2(serviceJourneys.busRailRatioPercent()));
            if (!hasBothSpeeds(speeds)) {
                unavailable(availability, "yxsdb", "nodata", "plans 缺少公交或小汽车有效速度样本");
            }
            if (awaitMinutes == null) {
                unavailable(availability, "pjhcsj", "nodata", "plans 中无可用的公交候车时间样本");
            }
        } else {
            result.put("yxsdb", null);
            result.put("pjhcsj", null);
            result.put("pjhccs", null);
            result.put("gjjbbl", null);
            unavailable(availability, "yxsdb", "unsupported", "当前加载模式未保留 plans 行程明细");
            unavailable(availability, "pjhcsj", "unsupported", "当前加载模式未保留 plans 时间明细");
            unavailable(availability, "pjhccs", "unsupported", "当前加载模式未保留 plans 完整 OD 出行明细");
            unavailable(availability, "gjjbbl", "unsupported", "当前加载模式未保留 plans 完整 OD 出行明细");
        }
        if ("unsupported".equals(populationSummary.get("coverage300Status"))) {
            result.put("fgl_300", null);
            unavailable(availability, "fgl_300", "unsupported", "population-v9 无法完整判定公共汽电车站点或坐标系");
        }
        if ("unsupported".equals(populationSummary.get("busShareStatus"))) {
            for (String metric : List.of("yxsdb", "pjhcsj", "pjhccs", "gjjbbl")) {
                result.put(metric, null);
                unavailable(availability, metric, "unsupported",
                        "plans 中存在无法通过 TransitPassengerRoute 与 schedule 解析制式的 legacy pt 出行");
            }
        }
        if (!roadTransit.isComplete()) {
            String reason = "时刻表中有 " + roadTransit.unresolvedRoutes()
                    + " 条 legacy pt 线路无法可靠判定是否为公共汽电车";
            for (String metric : List.of("gjxwmd", "fgl_300", "wrbyl", "cjrzkl", "dbczkl",
                    "rcxcs", "xlfzxxs", "xlcfxs", "xlmzl", "xlklqd_total",
                    "yxsdb", "pjhcsj", "pjhccs", "gjjbbl")) {
                result.put(metric, null);
                unavailable(availability, metric, "unsupported", reason);
            }
            result.put("boardings", null);
            unavailable(availability, "boardings", "unsupported", reason);
        }
        result.forEach((metric, value) -> {
            if (value == null) {
                availability.putIfAbsent(metric, Map.of(
                        "status", "nodata",
                        "reason", "源数据中缺少计算该指标所需的有效字段"
                ));
            }
        });
        result.put("availability", availability);
        return result;
    }

    private static List<LineVO> buildLines(MatsimData data, PassengerStats passengerStats) {
        List<LineVO> lineList = new ArrayList<>();
        Network network = data.getNetwork();
        // 客流/平均高峰满载率所需索引一次预建：缓存构建发生在 personTracks 就绪之后
        // （Datasource.loadEvent 顺序保证）。
        // 原实现只调 RouteDetailVO 构造器，lc/takeRate/passenger 恒为 0 被落盘，
        // routeDetail/lineAll 命中缓存时（默认常态）前端满载率/日客流永远显示 0。
        for (Map.Entry<Id<TransitLine>, TransitLine> line : data.getSchedule().getTransitLines().entrySet()) {
            TransitLine transitLine = line.getValue();
            LineVO vo = new LineVO();
            vo.setLineName(transitLine.getName());
            vo.setLineId(transitLine.getId().toString());
            List<RouteDetailVO> routes = new ArrayList<>();
            for (TransitRoute route : transitLine.getRoutes().values()) {
                RouteDetailVO detail = new RouteDetailVO(route, network);
                fillRouteStatistics(detail, transitLine.getId().toString(), route, data, passengerStats);
                routes.add(detail);
            }
            vo.setRoutes(routes);
            vo.setMode(lineMode(routes));
            lineList.add(vo);
        }
        return lineList;
    }

    /**
     * 填充实时路径（RouteServiceImpl.routeDetail）同口径的三个统计指标：
     * lc=非直线系数（线路长度/首末站直线距离）、takeRate=该路径各高峰班次
     * 最大站段满载率的班次均值（小数）、
     * passenger=日客流（该线路上车人次，lineId+routeId 复合键）。
     */
    private static void fillRouteStatistics(
            RouteDetailVO detail,
            String lineId,
            TransitRoute route,
            MatsimData data,
            PassengerStats passengerStats
    ) {
        detail.getInfo().setLc(routeDirectness(route, data.getNetwork()));
        Double peakPercent = passengerStats.routePeakLoad(lineId, route.getId().toString()).percent();
        if (peakPercent != null) {
            detail.getInfo().setTakeRate(peakPercent / 100.0);
        }
        detail.getInfo().setPassenger(passengerStats.boardingsByLineRoute.getOrDefault(lineId + "::" + route.getId(), 0L));
    }

    /** 单条 route 的非直线系数：线路长度 / 首末站直线距离；环线（直线距离 0）返回 0。 */
    private static double routeDirectness(TransitRoute route, Network network) {
        if (route.getStops().size() < 2) {
            return 0.0;
        }
        double distance = DistanceUtil.distance(route.getRoute(), network);
        TransitRouteStop first = route.getStops().getFirst();
        TransitRouteStop last = route.getStops().getLast();
        double straight = NetworkUtils.getEuclideanDistance(
                first.getStopFacility().getCoord(), last.getStopFacility().getCoord());
        return straight <= 0 ? 0.0 : round2(distance / straight);
    }

    private static List<LineVO> buildLineSummaries(List<LineVO> lines) {
        List<LineVO> result = new ArrayList<>(lines.size());
        for (LineVO sourceLine : lines) {
            LineVO line = new LineVO();
            line.setLineId(sourceLine.getLineId());
            line.setLineName(sourceLine.getLineName());
            line.setMode(sourceLine.getMode());
            List<RouteDetailVO> routes = new ArrayList<>();
            if (sourceLine.getRoutes() != null) {
                for (RouteDetailVO sourceRoute : sourceLine.getRoutes()) {
                    RouteDetailVO route = new RouteDetailVO();
                    route.setRouteId(sourceRoute.getRouteId());
                    route.setRouteName(sourceRoute.getRouteName());
                    route.setTransportMode(sourceRoute.getTransportMode());
                    route.setMode(sourceRoute.getMode());
                    route.setInfo(sourceRoute.getInfo());
                    route.setFacilities(sourceRoute.getFacilities());
                    route.setDepartures(List.of());
                    route.setLinks(List.of());
                    // 全量 links 体积过大不进摘要，但要保留抽稀后的真实路网走向，
                    // 否则前端全网线路图层只能用站点坐标直线连接
                    route.setGeometry(simplifiedRouteGeometry(sourceRoute.getLinks()));
                    routes.add(route);
                }
            }
            line.setRoutes(routes);
            result.add(line);
        }
        return result;
    }

    /**
     * 抽稀容差（米，Web Mercator 平面近似）。8m 在城市路网尺度下肉眼无差别，
     * 可把每条线路几百个 link 端点压到百点以内，控制 lines.json 体积。
     */
    private static final double ROUTE_GEOMETRY_TOLERANCE_METERS = 8.0;

    /**
     * 由 link 序列生成抽稀后的线路走向折线（[x, y] 序列）。
     */
    private static List<double[]> simplifiedRouteGeometry(List<PTLink> links) {
        if (links == null || links.isEmpty()) {
            return List.of();
        }
        List<double[]> points = new ArrayList<>(links.size() + 1);
        PTCoord first = links.getFirst().getFrom();
        if (first != null) {
            points.add(new double[]{first.getX(), first.getY()});
        }
        for (PTLink link : links) {
            PTCoord to = link.getTo();
            if (to == null) {
                continue;
            }
            double[] point = new double[]{to.getX(), to.getY()};
            double[] last = points.isEmpty() ? null : points.getLast();
            if (last == null || Math.abs(last[0] - point[0]) > 0.01 || Math.abs(last[1] - point[1]) > 0.01) {
                points.add(point);
            }
        }
        return douglasPeucker(points, ROUTE_GEOMETRY_TOLERANCE_METERS);
    }

    /**
     * Douglas-Peucker 抽稀（迭代实现，避免长线路递归过深）。
     */
    private static List<double[]> douglasPeucker(List<double[]> points, double tolerance) {
        int count = points.size();
        if (count <= 2) {
            return points;
        }
        boolean[] keep = new boolean[count];
        keep[0] = true;
        keep[count - 1] = true;
        double toleranceSq = tolerance * tolerance;
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        stack.push(new int[]{0, count - 1});
        while (!stack.isEmpty()) {
            int[] range = stack.pop();
            int start = range[0];
            int end = range[1];
            if (end - start < 2) {
                continue;
            }
            double maxDistSq = -1;
            int maxIndex = -1;
            double[] a = points.get(start);
            double[] b = points.get(end);
            for (int i = start + 1; i < end; i++) {
                double distSq = pointSegmentDistanceSq(points.get(i), a, b);
                if (distSq > maxDistSq) {
                    maxDistSq = distSq;
                    maxIndex = i;
                }
            }
            if (maxDistSq > toleranceSq && maxIndex > 0) {
                keep[maxIndex] = true;
                stack.push(new int[]{start, maxIndex});
                stack.push(new int[]{maxIndex, end});
            }
        }
        List<double[]> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (keep[i]) {
                result.add(points.get(i));
            }
        }
        return result;
    }

    private static double pointSegmentDistanceSq(double[] p, double[] a, double[] b) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double lengthSq = dx * dx + dy * dy;
        double t = lengthSq <= 0 ? 0 : ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / lengthSq;
        t = Math.max(0, Math.min(1, t));
        double px = a[0] + t * dx - p[0];
        double py = a[1] + t * dy - p[1];
        return px * px + py * py;
    }

    private static String lineMode(List<RouteDetailVO> routes) {
        if (routes == null || routes.isEmpty()) {
            return "";
        }
        if (routes.stream().anyMatch(route -> "subway".equals(route.getMode()))) {
            return "subway";
        }
        if (routes.stream().anyMatch(route -> "bus".equals(route.getMode()))) {
            return "bus";
        }
        return routes.getFirst().getMode();
    }

    private static List<FacilityVO> buildStations(MatsimData data) {
        List<FacilityVO> result = new ArrayList<>();
        data.getSchedule().getFacilities().forEach((facilityId, facility) -> {
            FacilityVO vo = new FacilityVO();
            vo.setFacilityName(facility.getName());
            vo.setFacilityId(facilityId.toString());
            vo.setCoord(new PTCoord(facility.getCoord()));
            result.add(vo);
        });
        return result;
    }

    /**
     * 单次顺序扫描即可得到所有可视化统计需要的有界索引。
     * 满载率只保留“班次→同秒净上下车变化”的有界状态，不复制数千万条乘客事件。
     */
    private static PassengerStats passengerStats(MatsimData data) {
        boolean unorderedInMemory = data.getPersonTracks() != null && !data.getPersonTracks().isEmpty();
        TransitMetrics.RoadTransitContext roadTransit =
                TransitMetrics.RoadTransitContext.from(data.getSchedule());
        PassengerStats stats = new PassengerStats(data, roadTransit, unorderedInMemory);
        MatsimPersonTrackStore.forEachTrack(data, track -> {
            stats.acceptPassengerEvent(track);
            if (Boolean.TRUE.equals(track.getEnter())) {
                stats.totalBoardings++;
                if (track.getRouteId() != null) {
                    stats.boardingsByLineRoute.merge(track.getLineId() + "::" + track.getRouteId(), 1L, Long::sum);
                }
            }
            if (roadTransit.isRoadTrack(track)) {
                if (track.getVehicleId() != null) stats.roadVehicleIds.add(track.getVehicleId());
                if (Boolean.TRUE.equals(track.getEnter())) stats.roadBoardings++;
            }
        });
        return stats;
    }

    /** 人口“计数”与 plans 对象明细分离：V6 从流式人口工件取总数。 */
    private static Long populationCount(MatsimData data, Map<String, Object> summary) {
        Population population = data.getPopulation();
        if (population != null && !population.getPersons().isEmpty()) {
            return (long) population.getPersons().size();
        }
        if ("ready".equals(summary.get("status")) && summary.get("persons") instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        return null;
    }

    /** 常住人口只统计 plans 中具有有效首个 home 位置的人。 */
    static Long residentHomePersonCount(MatsimData data, Map<String, Object> summary) {
        Population population = data == null ? null : data.getPopulation();
        if (population != null && !population.getPersons().isEmpty()) {
            return TransitMetrics.residentHomePersonCount(population);
        }
        if (summary != null && "ready".equals(summary.get("status"))
                && summary.get("homePersons") instanceof Number number) {
            return Math.max(0L, number.longValue());
        }
        return null;
    }

    /**
     * population-v9 中与评价体系直接对应的派生值。
     * 输出键与 info.json 一致，便于大小模型共用同一下游路径。
     */
    static Map<String, Object> populationDerivedMetrics(Map<String, Object> summary) {
        if (summary == null || !"ready".equals(summary.get("status"))) {
            return Map.of();
        }
        Double coverage = summary.get("coverage300Percent") instanceof Number number
                ? number.doubleValue() : null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fgl_300", TransitMetrics.coverageResult(coverage));
        Object busShare = summary.get("publicTransportMotorizedSharePercent");
        Map<String, Double> publicTransportShares = new LinkedHashMap<>();
        if ("ready".equals(summary.get("publicTransportShareStatus"))
                && busShare instanceof Number number) {
            publicTransportShares.put("pt", number.doubleValue());
            if ("ready".equals(summary.get("busShareStatus"))
                    && summary.get("busSharePercent") instanceof Number bus) {
                publicTransportShares.put("bus", bus.doubleValue());
            }
        }
        result.put("fxfdl", publicTransportShares);
        boolean busPlansSupported = !"unsupported".equals(summary.get("busShareStatus"));
        result.put("yxsdb", busPlansSupported && summary.get("speedKmh") instanceof Map<?, ?>
                ? summary.get("speedKmh") : null);
        result.put("pjhcsj", busPlansSupported && summary.get("averageBusWaitMinutes") instanceof Number number
                ? number.doubleValue() : null);
        boolean serviceJourneysSupported =
                "ready".equals(summary.get("busServiceJourneyStatus"));
        result.put("pjhccs", serviceJourneysSupported
                && summary.get("averageBusTransfers") instanceof Number number
                ? number.doubleValue() : null);
        result.put("gjjbbl", serviceJourneysSupported
                && summary.get("busRailFeederPercent") instanceof Number number
                ? number.doubleValue() : null);
        result.put("busServiceJourneys", summary.get("busServiceJourneys") instanceof Number number
                ? Math.max(0L, number.longValue()) : null);
        result.put("busServiceTransfers", summary.get("busServiceTransfers") instanceof Number number
                ? Math.max(0L, number.longValue()) : null);
        result.put("busRailJourneys", summary.get("busRailJourneys") instanceof Number number
                ? Math.max(0L, number.longValue()) : null);
        result.put("peakCarDistanceMeters", summary.get("peakCarDistanceMeters") instanceof Number number
                ? Math.max(0.0, number.doubleValue()) : null);
        result.put("peakCarTravelSeconds", summary.get("peakCarTravelSeconds") instanceof Number number
                ? Math.max(0.0, number.doubleValue()) : null);
        result.put("peakCarSamples", summary.get("peakCarSamples") instanceof Number number
                ? Math.max(0L, number.longValue()) : null);
        result.put("speedPeriodPolicy", summary.get("speedPeriodPolicy"));
        result.put("carSpeedSpatialScope", summary.get("carSpeedSpatialScope"));
        result.put("busWaitSamples", summary.get("busWaitSamples") instanceof Number number
                ? Math.max(0L, number.longValue()) : null);
        Object validHomes = summary.get("coverageValidHomePersons");
        if (!(validHomes instanceof Number)) validHomes = summary.get("homePersons");
        result.put("coverageValidHomePersons", validHomes instanceof Number number
                ? Math.max(0L, number.longValue()) : null);
        result.put("coverageTotalPersons", summary.get("persons") instanceof Number number
                ? Math.max(0L, number.longValue()) : null);
        result.put("coverageMissingHomePersons", summary.get("coverageMissingHomePersons") instanceof Number number
                ? Math.max(0L, number.longValue()) : null);
        result.put("coordinateTransformFailures", summary.get("coordinateTransformFailures") instanceof Number number
                ? Math.max(0L, number.longValue()) : null);
        result.put("coverageStatus", summary.get("coverage300Status"));
        result.put("coverageDenominatorPolicy", "valid-first-home");
        return result;
    }

    private static Map<String, Double> busModeShare(TransitMetrics.BusTripShareStats stats) {
        if (stats == null || stats.publicTransportMotorizedPercent() == null) {
            return Map.of();
        }
        Map<String, Double> shares = new LinkedHashMap<>();
        shares.put("pt", round2(stats.publicTransportMotorizedPercent()));
        if (stats.unresolvedLegacyPtJourneys() == 0) {
            shares.put("bus", round2(stats.busPercent()));
        }
        return shares;
    }

    private static boolean hasBothSpeeds(Object value) {
        if (!(value instanceof Map<?, ?> speeds)) return false;
        return speeds.get("ptAvg") instanceof Number pt && pt.doubleValue() > 0
                && speeds.get("carAvg") instanceof Number car && car.doubleValue() > 0;
    }

    /** 公交人均日出行次数 = 公交主方式完整 OD 出行数 ÷ 常住人口，明确排除地铁。 */
    static Double dailyTripsPerPerson(MatsimData data, Long residentHomePersons) {
        return dailyTripsPerPerson(MatsimPopulationCache.readPopulationSummary(data), residentHomePersons);
    }

    static Double dailyTripsPerPerson(Map<String, Object> summary, Long residentHomePersons) {
        if (summary == null || residentHomePersons == null || residentHomePersons <= 0) return null;
        if (!"ready".equals(summary.get("status"))
                || !"ready".equals(summary.get("busDailyTripsStatus"))
                || !(summary.get("residentBusJourneys") instanceof Number journeys)) {
            return null;
        }
        return Math.max(0L, journeys.longValue()) / (double) residentHomePersons;
    }

    private static double totalRouteLengthKm(MatsimData data) {
        double meters = 0.0;
        for (TransitLine line : data.getSchedule().getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                if (route.getRoute() != null) {
                    meters += DistanceUtil.distance(route.getRoute(), data.getNetwork());
                }
            }
        }
        return meters / 1000.0;
    }

    private static void unavailable(Map<String, Object> availability, String metric, String status, String reason) {
        availability.put(metric, Map.of("status", status, "reason", reason));
    }

    /**
     * 仅在大模型可视化缓存首次生成时临时读取完整道路网络；返回对象不会写回 MatsimData，
     * 因而业务常驻内存仍保持公交精简网络。
     */
    private static Network loadFullNetworkForVisual(MatsimData data) {
        String file = data.getOutfile() == null ? null : data.getOutfile().getNetwork();
        if (file == null || file.isBlank()) {
            // 合成单测可直接注入内存 scenario；真实加载路径在此之前已要求 network 输入存在。
            if (data.getNetwork() != null && !data.getNetwork().getLinks().isEmpty()) {
                log.warn("合成大模型未配置原始 network，测试路径回退到内存网络: model={}", data.getName());
                return data.getNetwork();
            }
            throw new IllegalStateException("完整道路网络文件不存在，无法生成大模型路网瓦片");
        }
        if (!Files.isRegularFile(Path.of(file))) {
            throw new IllegalStateException("完整道路网络文件不存在，无法生成大模型路网瓦片: " + file);
        }
        Network network = NetworkUtils.createNetwork();
        new MatsimNetworkReader(network).readFile(file);

        String globalCrs = data.getConfig() == null ? null : data.getConfig().global().getCoordinateSystem();
        String inputCrs = data.getConfig() == null ? null : data.getConfig().network().getInputCRS();
        String networkCrs = (String) network.getAttributes().getAttribute("coordinateReferenceSystem");
        CoordinateTransformation transformation = coordinateTransformation(globalCrs, inputCrs, networkCrs);
        if (transformation != null) {
            network.getNodes().values().forEach(node -> node.setCoord(transformation.transform(node.getCoord())));
        }
        log.info("大模型完整道路网络临时加载完成: model={}, links={}, nodes={}",
                data.getName(), network.getLinks().size(), network.getNodes().size());
        return network;
    }

    private static CoordinateTransformation coordinateTransformation(String globalCrs, String inputCrs, String fileCrs) {
        String source = fileCrs != null && !fileCrs.isBlank() ? fileCrs
                : inputCrs != null && !inputCrs.isBlank() ? inputCrs : globalCrs;
        if (source == null || source.isBlank() || source.equalsIgnoreCase("epsg:3857")) return null;
        return TransformationFactory.getCoordinateTransformation(source, "epsg:3857");
    }

    private static final class PassengerStats {
        private final TransitMetrics.PeakAverageLoadAccumulator peakLoad;
        private final Map<String, TransitMetrics.PeakAverageLoadAccumulator> peakLoadByLineRoute =
                new HashMap<>();
        private long totalBoardings;
        private long roadBoardings;
        private final Map<String, Long> boardingsByLineRoute = new HashMap<>();
        private final Set<VehicleId> roadVehicleIds = new HashSet<>();

        private PassengerStats(
                MatsimData data,
                TransitMetrics.RoadTransitContext roadTransit,
                boolean unorderedInMemory) {
            this.peakLoad = TransitMetrics.PeakAverageLoadAccumulator.roadBus(
                    data.getSchedule(), data.getTv(), roadTransit, unorderedInMemory);
            for (Map.Entry<Id<TransitLine>, TransitLine> line
                    : data.getSchedule().getTransitLines().entrySet()) {
                for (TransitRoute route : line.getValue().getRoutes().values()) {
                    peakLoadByLineRoute.put(line.getKey() + "::" + route.getId(),
                            TransitMetrics.PeakAverageLoadAccumulator.route(
                                    line.getKey(), route, data.getTv(), unorderedInMemory));
                }
            }
        }

        private void acceptPassengerEvent(PTPersonTrack track) {
            peakLoad.accept(track);
            if (track != null && track.getLineId() != null && track.getRouteId() != null) {
                TransitMetrics.PeakAverageLoadAccumulator routePeak =
                        peakLoadByLineRoute.get(track.getLineId() + "::" + track.getRouteId());
                if (routePeak != null) routePeak.accept(track);
            }
        }

        private TransitMetrics.PeakAverageLoadStats routePeakLoad(String lineId, String routeId) {
            TransitMetrics.PeakAverageLoadAccumulator routePeak =
                    peakLoadByLineRoute.get(lineId + "::" + routeId);
            return routePeak == null
                    ? new TransitMetrics.PeakAverageLoadStats(null, 0, 0, 0)
                    : routePeak.finish();
        }
    }

    private static Map<String, List<PTLink>> buildNetworkTiles(MatsimData data, Network network) {
        Map<String, Double> flows = readLinkFlows(data.getOutfile().getLinkstats());
        Map<String, List<PTLink>> result = new LinkedHashMap<>();
        network.getLinks().forEach((linkId, link) -> {
            addLinkToCoveredTiles(result, link, PTLink.base(link, flows.getOrDefault(linkId.toString(), 0D)));
        });
        return result;
    }

    private static Map<String, List<PTLink>> buildRouteTiles(MatsimData data) {
        Map<String, PTLink> uniqueLinks = new LinkedHashMap<>();
        Network network = data.getNetwork();
        data.getSchedule().getTransitLines().values().forEach(line -> line.getRoutes().values().forEach(route -> {
            NetworkRoute networkRoute = route.getRoute();
            addRouteLink(uniqueLinks, network.getLinks().get(networkRoute.getStartLinkId()));
            for (Id<Link> linkId : networkRoute.getLinkIds()) {
                addRouteLink(uniqueLinks, network.getLinks().get(linkId));
            }
            addRouteLink(uniqueLinks, network.getLinks().get(networkRoute.getEndLinkId()));
        }));

        Map<String, List<PTLink>> result = new LinkedHashMap<>();
        for (PTLink link : uniqueLinks.values()) {
            Link networkLink = network.getLinks().get(Id.createLinkId(link.getLinkId()));
            if (networkLink == null) {
                continue;
            }
            addLinkToCoveredTiles(result, networkLink, link);
        }
        return result;
    }

    private static void addLinkToCoveredTiles(Map<String, List<PTLink>> tiles, Link networkLink, PTLink payload) {
        if (networkLink == null || payload == null) {
            return;
        }
        List<Coord> coords = List.of(
                networkLink.getFromNode().getCoord(),
                networkLink.getToNode().getCoord(),
                networkLink.getCoord()
        );
        int maxTile = (1 << VISUAL_TILE_ZOOM) - 1;
        int minX = maxTile;
        int minY = maxTile;
        int maxX = 0;
        int maxY = 0;
        for (Coord coord : coords) {
            int[] tile = coordInTile(coord, VISUAL_TILE_ZOOM);
            minX = Math.min(minX, tile[0]);
            minY = Math.min(minY, tile[1]);
            maxX = Math.max(maxX, tile[0]);
            maxY = Math.max(maxY, tile[1]);
        }

        minX = Math.max(0, minX);
        minY = Math.max(0, minY);
        maxX = Math.min(maxTile, maxX);
        maxY = Math.min(maxTile, maxY);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                tiles.computeIfAbsent(tileKey(x, y), key -> new ArrayList<>()).add(payload);
            }
        }
    }

    private static void addRouteLink(Map<String, PTLink> result, Link link) {
        if (link != null) {
            result.putIfAbsent(link.getId().toString(), PTLink.base(link));
        }
    }

    private static Map<String, Double> runSpeed(
            Population population,
            org.matsim.pt.transitSchedule.api.TransitSchedule schedule,
            org.matsim.api.core.v01.network.Network network,
            TransitMetrics.RoadTransitContext roadTransit) {
        double carTime = 0.0;
        double carDist = 0.0;
        for (Person person : population.getPersons().values()) {
            for (PlanElement element : person.getSelectedPlan().getPlanElements()) {
                if (!(element instanceof Leg leg)) continue;
                TransitMetrics.PeakOperatingSpeedStats sample =
                        TransitMetrics.peakCarLegSpeedSample(leg);
                if (sample == null) continue;
                carTime += sample.travelSeconds();
                carDist += sample.distanceMeters();
            }
        }
        TransitMetrics.PeakOperatingSpeedStats bus =
                TransitMetrics.roadBusPeakOperatingSpeedStats(schedule, network, roadTransit);
        Map<String, Double> result = new LinkedHashMap<>();
        result.put("ptAvg", bus.kmh() == null ? null : round2(bus.kmh()));
        result.put("busAvg", bus.kmh() == null ? null : round2(bus.kmh()));
        result.put("carAvg", carTime > 0 ? round2(carDist / carTime * 3.6) : null);
        return result;
    }

    /**
     * 运行路径客流强度（人次/车公里，未四舍五入，按值降序）。
     * 分母为路径长度×该路径日发班数，不再用静态线路长度冒充运营车公里。
     */
    private static Map<String, Double> routePersonStrength(MatsimData data, PassengerStats passengerStats) {
        TransitMetrics.RoadTransitContext roadTransit =
                TransitMetrics.RoadTransitContext.from(data.getSchedule());
        Map<String, Integer> routeIdCounts = new HashMap<>();
        for (TransitLine transitLine : data.getSchedule().getTransitLines().values()) {
            for (TransitRoute route : transitLine.getRoutes().values()) {
                if (roadTransit.isRoadRoute(transitLine, route)) {
                    routeIdCounts.merge(route.getId().toString(), 1, Integer::sum);
                }
            }
        }
        Map<String, Double> result = new HashMap<>();
        for (Map.Entry<Id<TransitLine>, TransitLine> line : data.getSchedule().getTransitLines().entrySet()) {
            for (Map.Entry<Id<TransitRoute>, TransitRoute> route : line.getValue().getRoutes().entrySet()) {
                if (!roadTransit.isRoadRoute(line.getValue(), route.getValue())) continue;
                double distance = DistanceUtil.distance(route.getValue().getRoute(), data.getNetwork());
                String routeId = route.getKey().toString();
                String lineRouteKey = line.getKey() + "::" + routeId;
                double passenger = passengerStats.boardingsByLineRoute.getOrDefault(lineRouteKey, 0L);
                double vehicleKm = distance > 0
                        ? distance / 1000.0 * route.getValue().getDepartures().size() : 0.0;
                String outputKey = routeIdCounts.getOrDefault(routeId, 0) > 1 ? lineRouteKey : routeId;
                result.put(outputKey, vehicleKm == 0 ? 0 : passenger / vehicleKm);
            }
        }
        return result.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (oldValue, newValue) -> oldValue,
                        LinkedHashMap::new
                ));
    }

    private static Map<String, Double> readLinkFlows(String linkstatsPath) {
        Map<String, Double> flows = new HashMap<>();
        if (linkstatsPath == null || linkstatsPath.isBlank()) {
            return flows;
        }
        Path path = Path.of(linkstatsPath);
        if (!Files.isRegularFile(path)) {
            return flows;
        }
        try (BufferedReader reader = openReader(path)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return flows;
            }
            char delimiter = detectDelimiter(headerLine);
            String[] headers = split(headerLine, delimiter);
            int linkIndex = findLinkIndex(headers);
            List<Integer> flowIndices = findFlowIndices(headers);
            if (linkIndex < 0 || flowIndices.isEmpty()) {
                return flows;
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] values = split(line, delimiter);
                if (linkIndex >= values.length) {
                    continue;
                }
                String linkId = clean(values[linkIndex]);
                if (linkId.isBlank()) {
                    continue;
                }
                double flow = 0.0;
                boolean hasFlow = false;
                for (Integer flowIndex : flowIndices) {
                    if (flowIndex < values.length) {
                        Double value = parseDouble(values[flowIndex]);
                        if (value != null) {
                            flow += value;
                            hasFlow = true;
                        }
                    }
                }
                if (hasFlow) {
                    flows.put(linkId, flow);
                }
            }
        } catch (Exception e) {
            log.warn("读取 linkstats 失败: {}", linkstatsPath, e);
        }
        return flows;
    }

    private static BufferedReader openReader(Path path) throws Exception {
        InputStream input = Files.newInputStream(path);
        if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz")) {
            input = new GZIPInputStream(input);
        }
        return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    private static char detectDelimiter(String headerLine) {
        if (headerLine.indexOf('\t') >= 0) return '\t';
        if (headerLine.indexOf(';') >= 0) return ';';
        return ',';
    }

    private static String[] split(String line, char delimiter) {
        return line.split(Pattern.quote(String.valueOf(delimiter)), -1);
    }

    private static int findLinkIndex(String[] headers) {
        for (int i = 0; i < headers.length; i++) {
            String header = normalizeHeader(headers[i]);
            if (header.equals("link") || header.equals("link_id") || header.equals("linkid") || header.contains("link_id")) {
                return i;
            }
        }
        return -1;
    }

    private static List<Integer> findFlowIndices(String[] headers) {
        List<Integer> exact = new ArrayList<>();
        List<Integer> hourly = new ArrayList<>();
        List<Integer> fullSpan = new ArrayList<>();
        for (int i = 0; i < headers.length; i++) {
            String header = normalizeHeader(headers[i]);
            if (header.equals("simulated_traffic_volume") || header.equals("traffic_volume") || header.equals("simulated_volume") || header.equals("flow") || header.equals("volume")) {
                exact.add(i);
            } else if (isFullSpanFlowHeader(header)) {
                fullSpan.add(i);
            } else if (isFlowSeriesHeader(header)) {
                hourly.add(i);
            }
        }
        if (!exact.isEmpty()) {
            return exact;
        }
        // MATSim CalcLinkStats 同时输出 HRS0-1avg…HRS23-24avg 逐时列和 HRS0-24avg 日汇总列，
        // 两类一起累加会得到日总量×2。有逐时列时只累加逐时列，否则用汇总列。
        return hourly.isEmpty() ? fullSpan : hourly;
    }

    /** HRS0-24avg 之类的全跨度日汇总列（跨度 ≥24 小时）。 */
    private static boolean isFullSpanFlowHeader(String header) {
        java.util.regex.Matcher matcher = Pattern.compile("^hrs(\\d+)_(\\d+)avg$").matcher(header);
        if (!matcher.matches()) {
            return false;
        }
        try {
            return Integer.parseInt(matcher.group(2)) - Integer.parseInt(matcher.group(1)) >= 24;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static boolean isFlowSeriesHeader(String header) {
        if (header.contains("capacity") || header.contains("lane") || header.contains("speed") || header.contains("length") || header.contains("coord")) {
            return false;
        }
        return header.startsWith("vol") || header.contains("_vol") || header.contains("volume") || header.contains("traffic") || header.matches("hrs\\d+_\\d+avg");
    }

    private static String normalizeHeader(String value) {
        return clean(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static String clean(String value) {
        if (value == null) return "";
        String result = value.replace("\uFEFF", "").trim();
        if (result.length() >= 2 && ((result.startsWith("\"") && result.endsWith("\"")) || (result.startsWith("'") && result.endsWith("'")))) {
            return result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    private static Double parseDouble(String value) {
        String text = clean(value).replace(",", "");
        if (text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, Object> manifest(MatsimData data, boolean ready) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("status", ready ? "ready" : "failed");
        manifest.put("cacheVersion", VISUAL_CACHE_VERSION);
        manifest.put("generatedAt", System.currentTimeMillis());
        sourceFingerprint(data, manifest);
        return manifest;
    }

    /**
     * 基于缓存版本 + 源文件指纹（size/mtime，仅 stat 不读内容）的强校验标签，
     * 供 tile.bin / full.bin 的 HTTP ETag 使用：源文件或口径版本变化 → 标签变化 → 浏览器缓存自动失效。
     */
    public static String visualCacheTag(MatsimData data) {
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("cacheVersion", VISUAL_CACHE_VERSION);
        sourceFingerprint(data, fingerprint);
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(fingerprint.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return Integer.toHexString(fingerprint.toString().hashCode());
        }
    }

    private static void sourceFingerprint(MatsimData data, Map<String, Object> result) {
        putFileFingerprint(result, "events", data.getOutfile().getEvents());
        putFileFingerprint(result, "network", data.getOutfile().getNetwork());
        putFileFingerprint(result, "schedule", data.getOutfile().getTransitSchedule());
        putFileFingerprint(result, "plans", data.getOutfile().getPlans());
        putFileFingerprint(result, "linkstats", data.getOutfile().getLinkstats());
        // 车辆容量进 xlmzl/takeRate 分母、面积进密度类指标分母：这两个输入变化必须触发重建，
        // 否则用户补填真实面积/换车辆文件后旧统计继续下发
        putFileFingerprint(result, "transitVehicles", data.getOutfile().getTransitVehicles());
        result.put("areaKm2", String.valueOf(data.getArea()));
        result.put("adminBoundarySignature",
                MatsimAdministrativeDensityMetrics.boundaryFingerprint(data));
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

    private static void writeJsonAtomic(Path path, Object payload) throws Exception {
        Files.createDirectories(path.getParent());
        Path tmpPath = path.resolveSibling(path.getFileName() + ".tmp");
        JSON.writeValue(tmpPath.toFile(), payload);
        try {
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeGzipJson(Path path, Object payload) throws Exception {
        Files.createDirectories(path.getParent());
        Path tmpPath = path.resolveSibling(path.getFileName() + ".tmp");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmpPath))) {
            JSON.writeValue(out, payload);
        }
        try {
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static <T> T readGzipJson(Path path, TypeReference<T> type) throws Exception {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(path))) {
            return JSON.readValue(in, type);
        }
    }

    private static List<Object> readTile(MatsimData data, String tileDir, int z, int tileX, int tileY) {
        int zoom = normalizeTileZoom(z);
        if (tileX == 0 && tileY == 0 && data.getCenter() != null) {
            int[] centerTile = coordInTile(data.getCenter(), zoom);
            tileX = centerTile[0];
            tileY = centerTile[1];
        }
        if (zoom < VISUAL_TILE_ZOOM) {
            return readAggregatedTile(data, tileDir, zoom, tileX, tileY);
        }
        Path path = tilePath(data, tileDir, zoom, tileX, tileY);
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            return readGzipJson(path, LIST_TYPE);
        } catch (Exception e) {
            log.warn("读取瓦片预计算失败: {}", path, e);
            return null;
        }
    }

    private static List<Object> readAggregatedTile(MatsimData data, String tileDir, int z, int tileX, int tileY) {
        int factor = 1 << (VISUAL_TILE_ZOOM - z);
        int minX = tileX * factor;
        int minY = tileY * factor;
        int maxX = minX + factor - 1;
        int maxY = minY + factor - 1;
        Map<String, Object> deduped = new LinkedHashMap<>();
        List<Object> anonymous = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                Path childPath = tilePath(data, tileDir, VISUAL_TILE_ZOOM, x, y);
                if (!Files.exists(childPath)) {
                    continue;
                }
                try {
                    List<Object> child = readGzipJson(childPath, LIST_TYPE);
                    for (Object item : child) {
                        String key = linkKey(item);
                        if (key == null) {
                            anonymous.add(item);
                        } else {
                            deduped.putIfAbsent(key, item);
                        }
                    }
                } catch (Exception e) {
                    log.warn("聚合瓦片读取失败: {}", childPath, e);
                    return null;
                }
            }
        }
        if (deduped.isEmpty()) {
            return anonymous.isEmpty() ? List.of() : anonymous;
        }
        List<Object> result = new ArrayList<>(deduped.size() + anonymous.size());
        result.addAll(deduped.values());
        result.addAll(anonymous);
        return result;
    }

    private static String linkKey(Object item) {
        if (item instanceof Map<?, ?> map) {
            Object linkId = map.get("linkId");
            return linkId == null ? null : linkId.toString();
        }
        if (item instanceof PTLink link && link.getLinkId() != null) {
            return link.getLinkId();
        }
        return null;
    }

    private static void writeTileDirectory(MatsimData data, String tileDir, int z, Map<String, List<PTLink>> tiles) throws Exception {
        Path dir = tileDir(data, tileDir, z);
        deleteDirectory(dir);
        Files.createDirectories(dir);
        for (Map.Entry<String, List<PTLink>> entry : tiles.entrySet()) {
            String[] xy = entry.getKey().split(",", 2);
            if (xy.length != 2) {
                continue;
            }
            Path path = dir.resolve(xy[0] + "_" + xy[1] + ".json.gz");
            writeGzipJson(path, entry.getValue());
        }
    }

    private static Map<String, String> writeRouteDetails(MatsimData data, List<LineVO> lines) throws Exception {
        Path dir = routeDetailsDir(data);
        deleteDirectory(dir);
        Files.createDirectories(dir);
        Map<String, String> index = new LinkedHashMap<>();
        List<Map<String, RouteDetailVO>> shards = new ArrayList<>();
        for (int i = 0; i < ROUTE_DETAIL_SHARD_COUNT; i++) {
            shards.add(new LinkedHashMap<>());
        }
        Map<String, Integer> routeIdCounts = new HashMap<>();
        for (LineVO line : lines) {
            if (line.getRoutes() == null) {
                continue;
            }
            for (RouteDetailVO route : line.getRoutes()) {
                if (route.getRouteId() != null && !route.getRouteId().isBlank()) {
                    routeIdCounts.merge(route.getRouteId(), 1, Integer::sum);
                }
            }
        }
        for (LineVO line : lines) {
            if (line.getRoutes() == null) {
                continue;
            }
            for (RouteDetailVO route : line.getRoutes()) {
                if (route.getRouteId() == null || route.getRouteId().isBlank()) {
                    continue;
                }
                String key = routeKey(line.getLineId(), route.getRouteId());
                int shardIndex = Math.floorMod(key.hashCode(), ROUTE_DETAIL_SHARD_COUNT);
                String fileName = String.format(Locale.ROOT, "shard-%02d.json.gz", shardIndex);
                shards.get(shardIndex).put(key, route);
                index.put(key, fileName);
                if (routeIdCounts.getOrDefault(route.getRouteId(), 0) == 1) {
                    shards.get(shardIndex).put(route.getRouteId(), route);
                    index.put(route.getRouteId(), fileName);
                }
            }
        }
        for (int i = 0; i < shards.size(); i++) {
            writeGzipJson(dir.resolve(String.format(Locale.ROOT, "shard-%02d.json.gz", i)), shards.get(i));
        }
        return index;
    }

    private static void deleteDirectory(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path item : paths) {
                Files.deleteIfExists(item);
            }
        }
    }

    private static Path cacheDir(MatsimData data) {
        return MatsimCachePaths.versionDir(data, VISUAL_CACHE_VERSION);
    }

    private static Path manifestPath(MatsimData data) {
        return cacheDir(data).resolve("manifest.json");
    }

    private static Path infoPath(MatsimData data) {
        return cacheDir(data).resolve(INFO_FILE);
    }

    private static Path linesPath(MatsimData data) {
        return cacheDir(data).resolve(LINES_FILE);
    }

    private static Path stationsPath(MatsimData data) {
        return cacheDir(data).resolve(STATIONS_FILE);
    }

    private static Path routeIndexPath(MatsimData data) {
        return cacheDir(data).resolve(ROUTE_INDEX_FILE);
    }

    private static Path routeDetailsDir(MatsimData data) {
        return cacheDir(data).resolve(ROUTE_DETAILS_DIR);
    }

    private static Path tileDir(MatsimData data, String tileDir, int z) {
        return cacheDir(data).resolve(tileDir).resolve("z" + normalizeTileZoom(z));
    }

    private static Path tilePath(MatsimData data, String tileDir, int z, int tileX, int tileY) {
        return tileDir(data, tileDir, z).resolve(tileX + "_" + tileY + ".json.gz");
    }

    private static int normalizeTileZoom(int z) {
        if (z <= 0) {
            return VISUAL_TILE_ZOOM;
        }
        return Math.max(MIN_VISUAL_TILE_ZOOM, Math.min(VISUAL_TILE_ZOOM, z));
    }

    private static int[] coordInTile(Coord coord, int z) {
        int zoom = normalizeTileZoom(z);
        int col = (int) Math.floor(((TileNetwork.EARTH_RADIUS + coord.getX()) * Math.pow(2, zoom)) / (TileNetwork.EARTH_RADIUS * 2));
        int row = (int) Math.floor(((TileNetwork.EARTH_RADIUS - coord.getY()) * Math.pow(2, zoom)) / (TileNetwork.EARTH_RADIUS * 2));
        return new int[]{col, row};
    }

    private static String tileKey(int tileX, int tileY) {
        return tileX + "," + tileY;
    }

    private static String routeKey(String lineId, String routeId) {
        return nonBlank(lineId, "") + "::" + nonBlank(routeId, "");
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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

    private static double round2(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 100.0) / 100.0;
    }

    private static double round3(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static Double round4(Double value) {
        if (value == null || Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
