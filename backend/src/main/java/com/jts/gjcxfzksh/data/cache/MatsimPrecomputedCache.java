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
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
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
    public static final String VISUAL_CACHE_VERSION = "visual-v13";
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
        READY_CACHE_DIRS.remove(cacheDirPrefix);
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
            Map<String, Object> info = buildInfo(data);
            List<LineVO> routeDetails = buildLines(data);
            List<LineVO> lines = buildLineSummaries(routeDetails);
            List<FacilityVO> stations = buildStations(data);
            Map<String, List<PTLink>> networkTiles = buildNetworkTiles(data);
            Map<String, List<PTLink>> routeTiles = buildRouteTiles(data);

            writeJsonAtomic(infoPath(data), info);
            writeGzipJson(linesPath(data), lines);
            writeGzipJson(stationsPath(data), stations);
            writeTileDirectory(data, NETWORK_TILES_DIR, VISUAL_TILE_ZOOM, networkTiles);
            writeTileDirectory(data, ROUTE_TILES_DIR, VISUAL_TILE_ZOOM, routeTiles);
            writeJsonAtomic(routeIndexPath(data), writeRouteDetails(data, routeDetails));
            writeJsonAtomic(manifestPath(data), manifest(data, true));
            // 重建窗口期并发读者可能把删除前的旧文件解析结果写回内存，完成后再失效一次兜底
            invalidateMemoryCache(data);

            log.info("模型可视化预计算完成: model={}, lines={}, stations={}, networkTiles={}, routeTiles={}",
                    data.getName(), lines.size(), stations.size(), networkTiles.size(), routeTiles.size());
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
        String memoKey = cacheDir(data).toString();
        if (READY_CACHE_DIRS.contains(memoKey)) {
            return true;
        }
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

    private static Map<String, Object> buildInfo(MatsimData data) {
        Map<String, Object> result = new LinkedHashMap<>();
        Set<Coord> coords = data.getSchedule().getFacilities().values().stream()
                .map(item -> (Coord) item.getCoord())
                .collect(Collectors.toSet());
        // 口径修正：常住人口取全体 agent 数（原实现只数公交乘客）；
        // 面积在 desc.json 未提供时用站点凸包估算（原实现退化为除以 1）
        double configuredArea = data.getArea();
        // 两分支必须同为 Double：三元表达式混用 double/Double 时结果按 double 拆箱，
        // serviceAreaKm2 返回 null（站点<3 个）会直接 NPE
        Double areaKm2 = configuredArea > 1.0 ? Double.valueOf(configuredArea) : TransitMetrics.serviceAreaKm2(coords);
        int personCount = data.getPopulation() == null ? 0 : data.getPopulation().getPersons().size();
        // 大模型不加载 plans，population 是空对象而非 null——人口/出行计划类指标必须输出 null
        // （前端显示"暂无数据"），否则 0 值会被当真值固化进 info.json 永久下发。
        // 实时路径对大模型返回 "generating"，buildEvaluation 对同类指标输出 null，此处保持同一口径。
        boolean hasPopulation = personCount > 0;
        result.put("czrkmd", areaKm2 == null || !hasPopulation ? null : (int) Math.round(personCount / areaKm2));

        double networkLength = ptNetworkLength(data.getSchedule(), data.getNetwork());
        result.put("gjxwmd", areaKm2 == null ? null : round2((networkLength / 1000.0) / areaKm2));

        result.put("fgl_300", TransitMetrics.coverageResult(
                TransitMetrics.coverage300Percent(coords, data.getPopulation())));
        result.put("fxfdl", hasPopulation ? legTypeRate(data.getPopulation()) : null);

        Map<VehicleId, List<PTPersonTrack>> tracksByVehicle = data.getPersonTracks().stream()
                .collect(Collectors.groupingBy(PTPersonTrack::getVehicleId));
        int boardings = (int) data.getPersonTracks().stream().filter(PTPersonTrack::getEnter).count();
        // 万人保有量：标台数用"高峰同时在营车辆数"估算（GTFS 转换模型每班次一辆车，直接数车辆会放大一个数量级）
        long fleetSize = TransitMetrics.peakConcurrentVehicles(data.getSchedule());
        result.put("wrbyl", personCount == 0 || fleetSize == 0 ? null : round2(fleetSize / (personCount / 10000.0)));
        // 车均日载客量 = 日客运总量(上车) / 保有量(车队峰值估算)
        result.put("cjrzkl", fleetSize == 0 ? 0 : round2((double) boardings / fleetSize));
        // 单班次载客量   人次/班 = 日客运总量(上车) / 日发班次总数
        long departureTotal = 0;
        for (TransitLine line : data.getSchedule().getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                departureTotal += route.getDepartures().size();
            }
        }
        result.put("dbczkl", departureTotal == 0 ? null : round2((double) boardings / departureTotal));
        // ylklbl(依赖客流比例)为占位实现，已移除，接入真实口径前不下发
        result.put("rcxcs", boardings);
        result.put("xlfzxxs", routeNoLC(data));
        result.put("xlcfxs", routeRC(data));
        result.put("xlmzl", round2(TransitMetrics.fullLoadRate(
                tracksByVehicle, data.getTv().getVehicles(), data.getScale()) * 100.0));

        Map<String, Double> xlklqd = routePersonStrength(data);
        result.put("xlklqd", xlklqd.entrySet().stream()
                .limit(5)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> round2(entry.getValue()),
                        (oldValue, newValue) -> oldValue,
                        LinkedHashMap::new
                )));
        result.put("xlklqd_sum", round2(xlklqd.values().stream().mapToDouble(Double::doubleValue).sum()));
        if (hasPopulation) {
            result.put("yxsdb", runSpeed(data.getPopulation()));
            double[] awaitByHour = TransitMetrics.avgAwaitTimeByHour(data.getPopulation());
            double[] pjhcsj = new double[awaitByHour.length];
            for (int i = 0; i < awaitByHour.length; i++) {
                pjhcsj[i] = round2(awaitByHour[i]);
            }
            result.put("pjhcsj", pjhcsj);
        } else {
            result.put("yxsdb", null);
            result.put("pjhcsj", null);
        }
        return result;
    }

    private static List<LineVO> buildLines(MatsimData data) {
        List<LineVO> lineList = new ArrayList<>();
        Network network = data.getNetwork();
        // 客流/满载率所需索引一次预建：缓存构建发生在 personTracks 就绪之后（Datasource.loadEvent 顺序保证）。
        // 原实现只调 RouteDetailVO 构造器，lc/takeRate/passenger 恒为 0 被落盘，
        // routeDetail/lineAll 命中缓存时（默认常态）前端满载率/日客流永远显示 0。
        Map<VehicleId, List<PTPersonTrack>> tracksByVehicle = data.getPersonTracks().stream()
                .collect(Collectors.groupingBy(PTPersonTrack::getVehicleId));
        Map<String, Long> boardingsByLineRoute = new HashMap<>();
        for (PTPersonTrack track : data.getPersonTracks()) {
            if (Boolean.TRUE.equals(track.getEnter()) && track.getRouteId() != null) {
                boardingsByLineRoute.merge(track.getLineId() + "::" + track.getRouteId(), 1L, Long::sum);
            }
        }
        for (Map.Entry<Id<TransitLine>, TransitLine> line : data.getSchedule().getTransitLines().entrySet()) {
            TransitLine transitLine = line.getValue();
            LineVO vo = new LineVO();
            vo.setLineName(transitLine.getName());
            vo.setLineId(transitLine.getId().toString());
            List<RouteDetailVO> routes = new ArrayList<>();
            for (TransitRoute route : transitLine.getRoutes().values()) {
                RouteDetailVO detail = new RouteDetailVO(route, network);
                fillRouteStatistics(detail, transitLine.getId().toString(), route, data, tracksByVehicle, boardingsByLineRoute);
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
     * lc=非直线系数（线路长度/首末站直线距离）、takeRate=满载率（日周转系数口径，小数）、
     * passenger=日客流（该线路上车人次，lineId+routeId 复合键）。
     */
    private static void fillRouteStatistics(
            RouteDetailVO detail,
            String lineId,
            TransitRoute route,
            MatsimData data,
            Map<VehicleId, List<PTPersonTrack>> tracksByVehicle,
            Map<String, Long> boardingsByLineRoute
    ) {
        detail.getInfo().setLc(routeDirectness(route, data.getNetwork()));
        List<VehicleId> vehicleIds = new ArrayList<>();
        route.getDepartures().values().forEach(departure -> {
            if (departure.getVehicleId() != null) {
                vehicleIds.add(VehicleId.create(departure.getVehicleId()));
            }
        });
        detail.getInfo().setTakeRate(TransitMetrics.fullLoadRate(
                vehicleIds, tracksByVehicle, data.getTv().getVehicles(), data.getScale()));
        detail.getInfo().setPassenger(boardingsByLineRoute.getOrDefault(lineId + "::" + route.getId(), 0L));
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

    private static Map<String, List<PTLink>> buildNetworkTiles(MatsimData data) {
        Map<String, Double> flows = readLinkFlows(data.getOutfile().getLinkstats());
        Map<String, List<PTLink>> result = new LinkedHashMap<>();
        data.getNetwork().getLinks().forEach((linkId, link) -> {
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

    /**
     * @deprecated 未修正口径的旧实现：双向 link 各算一次（里程翻倍）、未剔除轨道线路。
     * 只服务于 info.json（/pt/data/info），该接口前端未调用；体检评估走的是
     * PTDataServiceImpl.buildEvaluation → TransitMetrics.networkLengthMeters。
     * 若将来要下发 info.json 的 gjxwmd，请改用 TransitMetrics 并 bump VISUAL_CACHE_VERSION。
     */
    @Deprecated
    private static double ptNetworkLength(TransitSchedule schedule, Network network) {
        double length = 0;
        Set<Id<Link>> links = new HashSet<>();
        schedule.getTransitLines().forEach((lineId, line) -> line.getRoutes().forEach((routeId, route) -> {
            NetworkRoute networkRoute = route.getRoute();
            links.add(networkRoute.getStartLinkId());
            links.addAll(networkRoute.getLinkIds());
            links.add(networkRoute.getEndLinkId());
        }));
        for (Id<Link> linkId : links) {
            Link link = network.getLinks().get(linkId);
            if (link != null) {
                length += link.getLength();
            }
        }
        return length;
    }

    private static Map<String, Double> runSpeed(Population population) {
        double ptTime = 0.0;
        double ptDist = 0.0;
        double carTime = 0.0;
        double carDist = 0.0;
        for (Person person : population.getPersons().values()) {
            for (PlanElement element : person.getSelectedPlan().getPlanElements()) {
                // Route.getDistance() 可为 NaN，累加前过滤，否则均值被污染为 NaN
                if (element instanceof Leg leg && leg.getTravelTime().isDefined() && leg.getRoute() != null
                        && !Double.isNaN(leg.getRoute().getDistance())) {
                    if (Constant.ROUTE_MODE_PT.equals(leg.getMode())) {
                        ptTime += leg.getTravelTime().seconds();
                        ptDist += leg.getRoute().getDistance();
                    } else if (Constant.ROUTE_MODE_CAR.equals(leg.getMode())) {
                        carTime += leg.getTravelTime().seconds();
                        carDist += leg.getRoute().getDistance();
                    }
                }
            }
        }
        Map<String, Double> result = new LinkedHashMap<>();
        result.put("ptAvg", round2(ptTime == 0 ? 0 : ptDist / ptTime * 3.6));
        result.put("carAvg", round2(carTime == 0 ? 0 : carDist / carTime * 3.6));
        return result;
    }

    private static double routeRC(MatsimData data) {
        double length = 0.0;
        Set<Id<Link>> links = new HashSet<>();
        for (TransitLine transitLine : data.getSchedule().getTransitLines().values()) {
            for (TransitRoute transitRoute : transitLine.getRoutes().values()) {
                NetworkRoute networkRoute = transitRoute.getRoute();
                length += DistanceUtil.distance(networkRoute, data.getNetwork());
                links.add(networkRoute.getStartLinkId());
                links.addAll(networkRoute.getLinkIds());
                links.add(networkRoute.getEndLinkId());
            }
        }
        double uniqueLength = 0.0;
        for (Id<Link> linkId : links) {
            Link link = data.getNetwork().getLinks().get(linkId);
            if (link != null) {
                uniqueLength += NetworkUtils.getEuclideanDistance(link.getFromNode().getCoord(), link.getToNode().getCoord());
            }
        }
        return uniqueLength == 0 ? 0 : round2(length / uniqueLength);
    }

    private static double routeNoLC(MatsimData data) {
        int routeCount = 0;
        double value = 0.0;
        for (TransitLine transitLine : data.getSchedule().getTransitLines().values()) {
            for (TransitRoute transitRoute : transitLine.getRoutes().values()) {
                NetworkRoute networkRoute = transitRoute.getRoute();
                double distance = DistanceUtil.distance(networkRoute, data.getNetwork());
                if (transitRoute.getStops().isEmpty()) {
                    continue;
                }
                TransitRouteStop first = transitRoute.getStops().getFirst();
                TransitRouteStop last = transitRoute.getStops().getLast();
                double straight = NetworkUtils.getEuclideanDistance(first.getStopFacility().getCoord(), last.getStopFacility().getCoord());
                if (straight > 0) {
                    value += distance / straight;
                    routeCount++;
                }
            }
        }
        return routeCount == 0 ? 0 : round2(value / routeCount);
    }

    /**
     * 线路客流强度（未四舍五入，按值降序）。与 PTDataServiceImpl.routePersonStrength 同口径：
     * TransitRoute ID 只在线路内唯一，上车记录按 lineId+routeId 复合键分组；
     * 输出键在 routeId 全局唯一时用裸 routeId，重复时用 "lineId::routeId" 消歧。
     */
    private static Map<String, Double> routePersonStrength(MatsimData data) {
        Map<String, Long> boardingsByLineRoute = new HashMap<>();
        for (PTPersonTrack track : data.getPersonTracks()) {
            if (Boolean.TRUE.equals(track.getEnter()) && track.getRouteId() != null) {
                boardingsByLineRoute.merge(track.getLineId() + "::" + track.getRouteId(), 1L, Long::sum);
            }
        }
        Map<String, Integer> routeIdCounts = new HashMap<>();
        for (TransitLine transitLine : data.getSchedule().getTransitLines().values()) {
            for (Id<TransitRoute> routeId : transitLine.getRoutes().keySet()) {
                routeIdCounts.merge(routeId.toString(), 1, Integer::sum);
            }
        }
        Map<String, Double> result = new HashMap<>();
        for (Map.Entry<Id<TransitLine>, TransitLine> line : data.getSchedule().getTransitLines().entrySet()) {
            for (Map.Entry<Id<TransitRoute>, TransitRoute> route : line.getValue().getRoutes().entrySet()) {
                double distance = DistanceUtil.distance(route.getValue().getRoute(), data.getNetwork());
                String routeId = route.getKey().toString();
                String lineRouteKey = line.getKey() + "::" + routeId;
                double passenger = boardingsByLineRoute.getOrDefault(lineRouteKey, 0L);
                String outputKey = routeIdCounts.getOrDefault(routeId, 0) > 1 ? lineRouteKey : routeId;
                result.put(outputKey, distance == 0 ? 0 : passenger / (distance / 1000.0));
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

    private static Map<String, Integer> legType(Population population) {
        Map<String, Integer> types = new HashMap<>();
        population.getPersons().values().forEach(person -> person.getSelectedPlan().getPlanElements().forEach(element -> {
            if (element instanceof Leg leg) {
                types.merge(leg.getMode(), 1, Integer::sum);
            }
        }));
        return types;
    }

    private static Map<String, Double> legTypeRate(Population population) {
        Map<String, Integer> types = legType(population);
        int count = types.values().stream().mapToInt(Integer::intValue).sum();
        Map<String, Double> result = new LinkedHashMap<>();
        if (count == 0) {
            return result;
        }
        BigDecimal total = BigDecimal.valueOf(count);
        // 比例保留 4 位小数再转百分数 → 精确到 0.01%（与 PTDataServiceImpl.legTypeRant 同口径）
        types.forEach((mode, value) -> result.put(mode, new BigDecimal(value).divide(total, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).doubleValue()));
        return result;
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
}
