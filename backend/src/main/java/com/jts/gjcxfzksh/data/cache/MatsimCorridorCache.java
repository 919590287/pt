package com.jts.gjcxfzksh.data.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

/**
 * 客流走廊监测缓存家族（子模块：线路重复系数 / 公交客流走廊）。
 * <p>
 * 模型加载时从 transitSchedule 的公交（bus 制式）路线遍历路网 link，把双向路网按
 * 「无向节点对」合并为物理路段（断面），统计每段：
 * ①被多少条**不同公交线路**经过（线路重复系数）；②全部线路的**断面客流叠加**
 * （乘车段沿线路站序做载客量前缀和，分摊到站间 link，双向合计，人次为模型原始值）。
 * 产出三个工件：
 * <ul>
 *   <li>{@code corridor-summary.json}：总量指标 + 口径参数（右侧首屏直出）；</li>
 *   <li>{@code corridor-links.bin}：路段二进制表（PCRD 契约，见 {@link #encodeLinks}，
 *       按系数升序写入——重复系数子模块按写入序绘制即可让高系数走廊压在细线之上；
 *       客流子模块前端自行按 flow 排序绘制）；</li>
 *   <li>{@code corridor-names.json}：路名字典（nameIdx → 名称）+ 街道 district 数组
 *       （streetIdx → district，行政区过滤用，行序 = 街道资源文件序）。</li>
 * </ul>
 * 口径契约（任何改动必须 bump {@link #CORRIDOR_CACHE_VERSION}）：
 * <ul>
 *   <li>只统计 bus 制式路线（制式判定复刻 MatsimTransferCache.classifyTransportMode，
 *       独立版本化互不引用）；subway/tram 及未知制式路线不参与（乘车段亦不计入断面客流）；</li>
 *   <li>物理路段 = link 的 (fromNode, toNode) 无向对：双向路网的两条对向 link 合并为一段，
 *       同一条线路上下行走同一路段重复系数只计一次（Set 语义天然去重）；断面客流为两方向叠加；</li>
 *   <li>断面客流：乘车段（enter/leave 配对，复刻 MatsimTripEndsCache 口径）按上车记录归属路线，
 *       上/下车站映射到路线站序（重复停站顺序向后匹配），差分前缀和得站间载客量，
 *       分摊到两站之间的 link（区间 (上站 link, 下站 link]）；无法定位站序的乘车段计 dropped；</li>
 *   <li>路名解析链：link 属性（siwei_path_name → siwei_base_name → name → osm:way:name）→
 *       内嵌边车表 {@code geo/gz_road_link_names.csv.gz}（road_{base} → 路名，由源路网
 *       shp 离线几何锚定生成，见 scripts/build_road_name_sidecar.py）→ 无名；</li>
 *   <li>街道归属：路段中点点面归属（复用 {@link MatsimPopulationCache#streetIndex()}），
 *       仅用于前端行政区过滤，未命中写哨兵。</li>
 * </ul>
 * 线路重复系数数线路不数人；断面客流为模型原始人次，不做数量缩放。
 */
@Slf4j
public final class MatsimCorridorCache {

    // v1: 首版口径：bus 制式 + 无向节点对合并 + 属性→边车表路名链 + 中点街道归属。
    // v2: 增加断面客流（乘车段站序前缀和分摊，双向叠加；PCRD 22B→26B 增 flow u32，BIN_VERSION=2）。
    public static final String CORRIDOR_CACHE_VERSION = "corridor-v2";

    // ===== corridor-links.bin 布局常量（前后端二进制契约，禁止偏离；小端）=====
    static final byte[] BIN_MAGIC = {'P', 'C', 'R', 'D'};
    static final int BIN_VERSION = 2;
    /** 头部字节数：magic(4) + version u16(2) + count u32(4)。 */
    static final int BIN_HEADER_BYTES = 10;
    /**
     * 每段字节数：x1 i32 + y1 i32 + x2 i32 + y2 i32（EPSG:3857 取整）
     * + coeff u16 + nameIdx u16 + street u16 + flow u32（断面客流，模型原始人次，clamp u32）。
     */
    static final int BIN_BYTES_PER_SEGMENT = 26;
    /** nameIdx / street 列的“无名 / 未命中街道”哨兵（u16 全 1）。 */
    static final int U16_SENTINEL = 0xFFFF;

    /** 内嵌路名边车表：road_{base} → 路名（源路网 shp PathName，离线几何锚定，覆盖率≈78%公交路段）。 */
    static final String ROAD_NAMES_RESOURCE = "/geo/gz_road_link_names.csv.gz";

    /** 制式常量（判定口径复刻 MatsimTransferCache，独立版本化）。 */
    static final String MODE_BUS = "bus";

    private static final String SUMMARY_FILE = "corridor-summary.json";
    private static final String NAMES_FILE = "corridor-names.json";
    private static final String LINKS_FILE = "corridor-links.bin";
    private static final String MANIFEST_FILE = "manifest.json";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final BackendMemoryCache<String, Map<String, Object>> MEMORY_CACHE =
            new BackendMemoryCache<>("corridor-json", 64L * 1024 * 1024, BackendMemoryCache::estimate);

    /** 边车表进程级单例（模型无关，全模型共享）。 */
    private static volatile Long2ObjectOpenHashMap<String> roadNamesSingleton;
    private static volatile String roadNamesTagSingleton;

    private MatsimCorridorCache() {
    }

    // ===================================================================================
    // 对外入口（模式照 MatsimPopulationCache）
    // ===================================================================================

    public static void prepareOnModelLoad(MatsimData data) {
        synchronized (ModelBuildLocks.lockFor("corridor", data)) {
            ensureCorridorCacheLocked(data);
        }
    }

    public static boolean isReady(MatsimData data) {
        if (!Files.exists(manifestPath(data)) || !Files.exists(summaryPath(data))
                || !Files.exists(namesPath(data)) || !Files.exists(linksPath(data))) {
            return false;
        }
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath(data).toFile(), MAP_TYPE);
            return "ready".equals(manifest.get("status"))
                    && CORRIDOR_CACHE_VERSION.equals(manifest.get("cacheVersion"))
                    && sameSources(data, manifest);
        } catch (Exception e) {
            throw new IllegalStateException("走廊缓存状态读取失败: " + manifestPath(data), e);
        }
    }

    /** 总量指标 + 口径参数（POST /pt/corridor/summary）。未就绪返回 generating 态。 */
    public static Map<String, Object> readCorridorSummary(MatsimData data) {
        if (!isReady(data)) {
            return generatingPayload();
        }
        try {
            return loadCachedJson(summaryPath(data));
        } catch (Exception e) {
            throw new IllegalStateException("读取走廊汇总缓存失败: model=" + data.getName()
                    + ", path=" + summaryPath(data), e);
        }
    }

    /** 路名字典 + 街道 district 数组（POST /pt/corridor/names）。未就绪返回 generating 态。 */
    public static Map<String, Object> readCorridorNames(MatsimData data) {
        if (!isReady(data)) {
            return generatingPayload();
        }
        try {
            return loadCachedJson(namesPath(data));
        } catch (Exception e) {
            throw new IllegalStateException("读取走廊路名缓存失败: model=" + data.getName()
                    + ", path=" + namesPath(data), e);
        }
    }

    /** 路段二进制表字节（GET /pt/corridor/links.bin）。未就绪返回 null（Controller 侧 404）。 */
    public static byte[] readLinksBytes(MatsimData data) {
        if (!isReady(data)) {
            return null;
        }
        try {
            return Files.readAllBytes(linksPath(data));
        } catch (Exception e) {
            throw new IllegalStateException("读取走廊路段表失败: model=" + data.getName()
                    + ", path=" + linksPath(data), e);
        }
    }

    /** links.bin 的强校验 ETag（照 MatsimPopulationCache.gridBinTag；资源键一并纳入）。 */
    public static String linksBinTag(MatsimData data) {
        if (!isReady(data)) {
            return null;
        }
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath(data).toFile(), MAP_TYPE);
            StringBuilder content = new StringBuilder(CORRIDOR_CACHE_VERSION);
            new TreeMap<>(manifest).forEach((key, value) -> {
                if (key.endsWith("File") || key.endsWith("Modified") || key.endsWith("Size")
                        || key.startsWith("streets") || key.startsWith("roadNames")) {
                    content.append('|').append(key).append('=').append(value);
                }
            });
            return sha256Hex(content.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("走廊路段表 ETag 计算失败: " + manifestPath(data), e);
        }
    }

    private static Map<String, Object> generatingPayload() {
        return Map.of(
                "status", "generating",
                "cacheVersion", CORRIDOR_CACHE_VERSION,
                "message", "走廊分析缓存正在后台生成"
        );
    }

    // ===================================================================================
    // 构建编排
    // ===================================================================================

    private static void ensureCorridorCacheLocked(MatsimData data) {
        if (isReady(data)) {
            return;
        }
        try {
            long start = System.currentTimeMillis();
            Artifacts artifacts = buildArtifacts(data);
            MatsimCachePaths.recreateVersionDir(data, CORRIDOR_CACHE_VERSION);
            writeBytesAtomic(linksPath(data), artifacts.linksBin);
            writeJsonAtomic(namesPath(data), artifacts.names);
            writeJsonAtomic(summaryPath(data), artifacts.summary);
            writeJsonAtomic(manifestPath(data), manifest(data, true));
            MatsimCachePaths.deleteOtherVersions(data, "corridor-v", CORRIDOR_CACHE_VERSION);
            MEMORY_CACHE.remove(cacheKey(summaryPath(data)));
            MEMORY_CACHE.remove(cacheKey(namesPath(data)));
            log.info("走廊缓存生成完成: model={}, busLines={}, segments={}, named={}, maxCoeff={}, "
                            + "missingLinks={}, bin={}B, 耗时={}ms",
                    data.getName(), artifacts.summary.get("busLines"), artifacts.summary.get("segments"),
                    artifacts.summary.get("namedSegments"), artifacts.summary.get("maxCoeff"),
                    artifacts.summary.get("missingLinks"), artifacts.linksBin.length,
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            try {
                Files.createDirectories(cacheDir(data));
                writeJsonAtomic(manifestPath(data), manifest(data, false));
            } catch (Exception ignored) {
            }
            throw new RuntimeException("走廊缓存生成失败: " + e.getMessage(), e);
        }
    }

    /** 从 schedule + network 抽取公交遍历并聚合，再按乘车段累加断面客流。零轨道/零线路模型产出空工件。 */
    private static Artifacts buildArtifacts(MatsimData data) {
        Extraction extraction = extractBusLineTraversals(data.getSchedule(), data.getNetwork(), roadNames());
        Computation computation = aggregateTraversals(extraction.byLine());
        computation.missingLinks = extraction.missingLinks();
        computation.routeStopMismatch = extraction.routeStopMismatch();
        accumulateSegmentFlows(data, extraction.contexts(), computation);
        return assemble(computation, MatsimPopulationCache.streetIndex());
    }

    // ===================================================================================
    // 口径工具（复刻来源见各注释；不得反向修改被复刻方）
    // ===================================================================================

    /**
     * 制式判定：复刻自 MatsimTransferCache.classifyTransportMode 的 bus 侧结论。
     * 缺失或无法识别的 transportMode 不再猜测为公交。
     */
    static boolean isBusTransportMode(String transportMode) {
        return MatsimTransferCache.MODE_BUS.equals(
                MatsimTransferCache.classifyTransportMode(transportMode));
    }

    /** link id 形如 road_{base}_{seg}_{dir} 时返回 base，否则 null（边车表键）。 */
    static Long linkBaseId(String linkId) {
        if (linkId == null || !linkId.startsWith("road_")) {
            return null;
        }
        int start = 5;
        int end = linkId.indexOf('_', start);
        if (end <= start) {
            return null;
        }
        try {
            return Long.parseLong(linkId.substring(start, end));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("路段 ID 不符合 road_<数字>_* 约定: " + linkId, e);
        }
    }

    /**
     * 路名解析链：link 属性优先（新版建网脚本已内嵌 siwei_path_name），退回边车表，最终无名。
     * 空白一律视为无名。
     */
    static String resolveLinkName(Link link, Long2ObjectOpenHashMap<String> sidecar) {
        for (String attrKey : new String[]{"siwei_path_name", "siwei_base_name", "name", "osm:way:name"}) {
            Object value = link.getAttributes().getAttribute(attrKey);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        if (sidecar != null) {
            Long base = linkBaseId(link.getId().toString());
            if (base != null) {
                String name = sidecar.get(base.longValue());
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        }
        return null;
    }

    // ===================================================================================
    // 遍历抽取与聚合
    // ===================================================================================

    /** 公交路线经过的一条 link（几何/名称已解析，聚合内核与 MATSim 对象解耦以便单测）。 */
    record TraversedLink(String fromNode, String toNode,
                         double fromX, double fromY, double toX, double toY, String name) {
    }

    /**
     * 路线断面客流上下文：站序 → link 区间的映射 + 乘车段差分数组。
     * segKeyByLinkPos 与路线 link 序列对齐（缺失 link 为 null）；stopLinkPos 为各停靠位置的
     * link 序号（顺序向后匹配，天然支持环线/重复停站）；positionsByFacility 为站点→停靠位置（升序）。
     */
    static final class RouteFlowCtx {
        final String[] segKeyByLinkPos;
        final int[] stopLinkPos;
        final Map<String, int[]> positionsByFacility;
        final long[] boardDiff; // 差分：上车 +1（按停靠位置），下车 -1；前缀和 = 站间载客量

        RouteFlowCtx(String[] segKeyByLinkPos, int[] stopLinkPos, Map<String, int[]> positionsByFacility) {
            this.segKeyByLinkPos = segKeyByLinkPos;
            this.stopLinkPos = stopLinkPos;
            this.positionsByFacility = positionsByFacility;
            this.boardDiff = new long[stopLinkPos.length];
        }
    }

    /** 路线上下文注册表：只接受 lineId::routeId 精确键。 */
    static final class RouteCtxRegistry {
        final Map<String, RouteFlowCtx> byLineRoute = new HashMap<>();

        void register(String lineId, String routeId, RouteFlowCtx ctx) {
            byLineRoute.put(lineId + "::" + routeId, ctx);
        }

        RouteFlowCtx resolve(String lineId, String routeId) {
            if (lineId == null || routeId == null) {
                return null;
            }
            return byLineRoute.get(lineId + "::" + routeId);
        }

        Collection<RouteFlowCtx> all() {
            return byLineRoute.values();
        }
    }

    /** 抽取产物：lineId → 遍历序列 + 路线客流上下文 + 缺失 link / 站序失配计数。 */
    record Extraction(Map<String, List<TraversedLink>> byLine, RouteCtxRegistry contexts,
                      long missingLinks, long routeStopMismatch) {
    }

    /** 无向节点对规范键（与 {@link #aggregateTraversals} 的 key 同构）。 */
    static String segKey(String fromNode, String toNode) {
        return fromNode.compareTo(toNode) <= 0 ? fromNode + "|" + toNode : toNode + "|" + fromNode;
    }

    /** lineId → 该线全部 bus 路线经过的 link 序列（含 NetworkRoute 首末 link）+ 路线客流上下文。 */
    private static Extraction extractBusLineTraversals(
            TransitSchedule schedule, Network network, Long2ObjectOpenHashMap<String> sidecar) {
        Map<String, List<TraversedLink>> byLine = new LinkedHashMap<>();
        RouteCtxRegistry contexts = new RouteCtxRegistry();
        if (schedule == null || network == null) {
            return new Extraction(byLine, contexts, 0, 0);
        }
        long missing = 0;
        long stopMismatch = 0;
        for (Map.Entry<Id<TransitLine>, TransitLine> lineEntry : schedule.getTransitLines().entrySet()) {
            String lineId = lineEntry.getKey().toString();
            List<TraversedLink> traversals = null;
            for (Map.Entry<Id<TransitRoute>, TransitRoute> routeEntry : lineEntry.getValue().getRoutes().entrySet()) {
                TransitRoute route = routeEntry.getValue();
                if (!isBusTransportMode(route.getTransportMode())) {
                    continue;
                }
                NetworkRoute networkRoute = route.getRoute();
                if (networkRoute == null) {
                    continue;
                }
                List<Id<Link>> linkIds = new ArrayList<>();
                linkIds.add(networkRoute.getStartLinkId());
                linkIds.addAll(networkRoute.getLinkIds());
                linkIds.add(networkRoute.getEndLinkId());
                String[] segKeys = new String[linkIds.size()];
                for (int li = 0; li < linkIds.size(); li++) {
                    Id<Link> linkId = linkIds.get(li);
                    if (linkId == null) {
                        continue;
                    }
                    Link link = network.getLinks().get(linkId);
                    if (link == null || link.getFromNode() == null || link.getToNode() == null) {
                        missing++;
                        continue;
                    }
                    if (traversals == null) {
                        traversals = byLine.computeIfAbsent(lineId, ignored -> new ArrayList<>());
                    }
                    traversals.add(new TraversedLink(
                            link.getFromNode().getId().toString(), link.getToNode().getId().toString(),
                            link.getFromNode().getCoord().getX(), link.getFromNode().getCoord().getY(),
                            link.getToNode().getCoord().getX(), link.getToNode().getCoord().getY(),
                            resolveLinkName(link, sidecar)));
                    segKeys[li] = segKey(link.getFromNode().getId().toString(), link.getToNode().getId().toString());
                }
                // 断面客流上下文：停靠位置 → link 序号（顺序向后匹配，环线/重复停站不回退）
                RouteFlowCtx ctx = buildRouteFlowCtx(route, linkIds, segKeys);
                if (ctx == null) {
                    stopMismatch++;
                } else {
                    contexts.register(lineId, routeEntry.getKey().toString(), ctx);
                }
            }
        }
        if (missing > 0) {
            log.warn("走廊缓存路线引用了 {} 条网络中不存在的 link，已跳过", missing);
        }
        if (stopMismatch > 0) {
            log.warn("走廊缓存有 {} 条路线的停站无法映射到 link 序列，其断面客流已跳过", stopMismatch);
        }
        return new Extraction(byLine, contexts, missing, stopMismatch);
    }

    /** 停站→link 序号映射失败（schedule 与 network 不配套）返回 null，该路线不计断面客流。 */
    private static RouteFlowCtx buildRouteFlowCtx(TransitRoute route, List<Id<Link>> linkIds, String[] segKeys) {
        List<TransitRouteStop> stops = route.getStops();
        if (stops == null || stops.size() < 2) {
            return null;
        }
        int[] stopLinkPos = new int[stops.size()];
        Map<String, IntArrayList> positions = new HashMap<>();
        int cursor = 0;
        for (int s = 0; s < stops.size(); s++) {
            TransitRouteStop stop = stops.get(s);
            if (stop.getStopFacility() == null) {
                return null;
            }
            Id<Link> stopLink = stop.getStopFacility().getLinkId();
            if (stopLink == null) {
                return null;
            }
            int found = -1;
            for (int li = cursor; li < linkIds.size(); li++) {
                if (stopLink.equals(linkIds.get(li))) {
                    found = li;
                    break;
                }
            }
            if (found < 0) {
                return null;
            }
            stopLinkPos[s] = found;
            cursor = found;
            positions.computeIfAbsent(stop.getStopFacility().getId().toString(), ignored -> new IntArrayList())
                    .add(s);
        }
        Map<String, int[]> positionsByFacility = new HashMap<>(positions.size() * 2);
        for (Map.Entry<String, IntArrayList> entry : positions.entrySet()) {
            positionsByFacility.put(entry.getKey(), entry.getValue().toIntArray());
        }
        return new RouteFlowCtx(segKeys, stopLinkPos, positionsByFacility);
    }

    /** 物理路段聚合器：无向节点对 → 线路集合 + 断面客流叠加 + 规范化几何 + 首个非空路名。 */
    static final class SegmentAgg {
        final double x1;
        final double y1;
        final double x2;
        final double y2;
        final TreeSet<String> lines = new TreeSet<>();
        long flow; // 断面客流（两方向全部线路叠加，模型原始人次）
        String name;

        SegmentAgg(double x1, double y1, double x2, double y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
        }

        int coefficient() {
            return lines.size();
        }
    }

    /** 聚合产物。 */
    static final class Computation {
        final Map<String, SegmentAgg> segments = new HashMap<>();
        long missingLinks;
        long routeStopMismatch;
        long flowRides;
        long flowDroppedRides;
    }

    /**
     * 无向合并：key = {@link #segKey}（节点 id 字典序对）；几何按同一规范方向存
     * （节点序小的一端为 (x1,y1)），同一线路双向经过同一路段只计一次（TreeSet 去重）；
     * 路名取首个非空。
     */
    static Computation aggregateTraversals(Map<String, List<TraversedLink>> byLine) {
        Computation computation = new Computation();
        for (Map.Entry<String, List<TraversedLink>> entry : byLine.entrySet()) {
            String lineId = entry.getKey();
            for (TraversedLink traversal : entry.getValue()) {
                boolean forward = traversal.fromNode().compareTo(traversal.toNode()) <= 0;
                String key = segKey(traversal.fromNode(), traversal.toNode());
                SegmentAgg agg = computation.segments.computeIfAbsent(key, ignored -> forward
                        ? new SegmentAgg(traversal.fromX(), traversal.fromY(), traversal.toX(), traversal.toY())
                        : new SegmentAgg(traversal.toX(), traversal.toY(), traversal.fromX(), traversal.fromY()));
                agg.lines.add(lineId);
                if (agg.name == null && traversal.name() != null && !traversal.name().isBlank()) {
                    agg.name = traversal.name();
                }
            }
        }
        return computation;
    }

    // ===================================================================================
    // 断面客流：乘车段配对 → 站序差分 → 前缀和分摊到站间 link
    // ===================================================================================

    /**
     * 同人 track 时间排序：复刻自 MatsimTripEndsCache.TRACK_TIME_ORDER
     * （同一秒先下车后上车，末键按车辆 ID 定序保证可复现）。
     */
    private static final Comparator<PTPersonTrack> TRACK_TIME_ORDER =
            Comparator.comparingDouble(MatsimCorridorCache::safeTime)
                    .thenComparingInt(track -> Boolean.TRUE.equals(track.getEnter()) ? 1 : 0)
                    .thenComparing(track -> String.valueOf(track.getVehicleId()));

    /**
     * 乘车段（enter/leave 配对，配对口径复刻 MatsimTripEndsCache.collectPersonJourneys）按上车记录
     * 归属路线：上/下车站映射到该路线站序（重复停站取上车位之后最近的下车位），写入差分数组；
     * 全部乘车段落账后按路线做前缀和，把站间载客量分摊到 (上站 link, 下站 link] 区间的物理路段。
     * 非 bus 路线的乘车段不计（contexts 只登记 bus 路线）；无法定位站序的计 flowDroppedRides。
     */
    static void accumulateSegmentFlows(Collection<PTPersonTrack> tracks, RouteCtxRegistry contexts,
                                       Computation computation) {
        if (tracks != null && !tracks.isEmpty()) {
            Map<String, List<PTPersonTrack>> byPerson = new HashMap<>();
            for (PTPersonTrack track : tracks) {
                String personId = idString(track.getPersonId());
                if (personId == null) {
                    continue; // 无 person 无法配对（口径与 tripends 一致，此处不重复计数）
                }
                byPerson.computeIfAbsent(personId, ignored -> new ArrayList<>()).add(track);
            }
            for (List<PTPersonTrack> personTracks : byPerson.values()) {
                collectPersonRides(personTracks, contexts, computation);
            }
        }
        applySegmentFlows(contexts, computation);
    }

    /** 磁盘态大模型入口，按 person 分区逐组配对。 */
    private static void accumulateSegmentFlows(MatsimData data, RouteCtxRegistry contexts,
                                               Computation computation) {
        if (data.getPersonTracks() != null && !data.getPersonTracks().isEmpty()) {
            accumulateSegmentFlows(data.getPersonTracks(), contexts, computation);
            return;
        }
        MatsimPersonTrackStore.forEachPerson(data, (personId, tracks) -> {
            if (personId != null && !personId.isBlank()) {
                collectPersonRides(tracks, contexts, computation);
            }
        });
        applySegmentFlows(contexts, computation);
    }

    private static void applySegmentFlows(RouteCtxRegistry contexts, Computation computation) {
        // 前缀和分摊：站间载客量加到 (上站 link, 下站 link] 的物理路段
        for (RouteFlowCtx ctx : contexts.all()) {
            long load = 0;
            for (int s = 0; s < ctx.stopLinkPos.length - 1; s++) {
                load += ctx.boardDiff[s];
                if (load <= 0) {
                    continue;
                }
                for (int li = ctx.stopLinkPos[s] + 1; li <= ctx.stopLinkPos[s + 1]; li++) {
                    String key = ctx.segKeyByLinkPos[li];
                    if (key == null) {
                        continue; // 缺失 link（网络不含），与重复系数口径一致跳过
                    }
                    SegmentAgg agg = computation.segments.get(key);
                    if (agg != null) {
                        agg.flow += load;
                    }
                }
            }
        }
    }

    private static void collectPersonRides(List<PTPersonTrack> personTracks, RouteCtxRegistry contexts,
                                           Computation out) {
        personTracks.sort(TRACK_TIME_ORDER);
        PTPersonTrack open = null;
        for (PTPersonTrack track : personTracks) {
            if (track.getEnter() == null) {
                continue;
            }
            if (track.getEnter()) {
                open = track; // 连续两条上车：以后一条为准（坏记录不计入断面）
                continue;
            }
            if (open == null) {
                continue; // 孤儿下车
            }
            if (!Objects.equals(idString(open.getVehicleId()), idString(track.getVehicleId()))) {
                open = null; // 车辆对不上，两条都不可信
                continue;
            }
            registerRide(open, track, contexts, out);
            open = null;
        }
    }

    private static void registerRide(PTPersonTrack board, PTPersonTrack alight, RouteCtxRegistry contexts,
                                     Computation out) {
        RouteFlowCtx ctx = contexts.resolve(idString(board.getLineId()), idString(board.getRouteId()));
        if (ctx == null) {
            return; // 非 bus 路线 / 未登记路线：不计入断面客流
        }
        int[] boardPositions = ctx.positionsByFacility.get(idString(board.getFacilityId()));
        int[] alightPositions = ctx.positionsByFacility.get(idString(alight.getFacilityId()));
        if (boardPositions == null || alightPositions == null) {
            out.flowDroppedRides++;
            return;
        }
        int boardPos = boardPositions[0];
        int alightPos = -1;
        for (int candidate : alightPositions) {
            if (candidate > boardPos) {
                alightPos = candidate;
                break;
            }
        }
        if (alightPos < 0) {
            out.flowDroppedRides++;
            return;
        }
        ctx.boardDiff[boardPos]++;
        ctx.boardDiff[alightPos]--;
        out.flowRides++;
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

    // ===================================================================================
    // 组装：links.bin / names.json / summary.json
    // ===================================================================================

    /** 三工件组装结果。 */
    static final class Artifacts {
        final byte[] linksBin;
        final Map<String, Object> summary;
        final Map<String, Object> names;

        private Artifacts(byte[] linksBin, Map<String, Object> summary, Map<String, Object> names) {
            this.linksBin = linksBin;
            this.summary = summary;
            this.names = names;
        }
    }

    static Artifacts assemble(Computation computation, MatsimPopulationCache.StreetIndex streets) {
        // 路名字典：仅收录被路段引用的名称，字典序编号（跨构建可复现）
        TreeSet<String> usedNames = new TreeSet<>();
        for (SegmentAgg agg : computation.segments.values()) {
            if (agg.name != null) {
                usedNames.add(agg.name);
            }
        }
        if (usedNames.size() >= U16_SENTINEL) {
            throw new IllegalStateException("走廊路名字典条目数 " + usedNames.size() + " 超出 u16 上限");
        }
        Map<String, Integer> nameIdx = new LinkedHashMap<>(usedNames.size() * 2);
        for (String name : usedNames) {
            nameIdx.put(name, nameIdx.size());
        }

        byte[] linksBin = encodeLinks(computation.segments.values(), nameIdx, streets);

        List<String> districts = new ArrayList<>(streets == null ? 0 : streets.size());
        for (int i = 0; streets != null && i < streets.size(); i++) {
            districts.add(streets.street(i).district());
        }
        Map<String, Object> names = new LinkedHashMap<>();
        names.put("status", "ready");
        names.put("cacheVersion", CORRIDOR_CACHE_VERSION);
        names.put("names", new ArrayList<>(usedNames));
        names.put("districts", districts);

        int maxCoeff = 0;
        long maxFlow = 0;
        long named = 0;
        for (SegmentAgg agg : computation.segments.values()) {
            maxCoeff = Math.max(maxCoeff, agg.coefficient());
            maxFlow = Math.max(maxFlow, agg.flow);
            if (agg.name != null) {
                named++;
            }
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("modes", "bus"); // 仅公交线路计入重复系数/断面客流
        params.put("dedup", "undirected-node-pair"); // 双向合并：系数同线双向只计一次，客流两方向叠加
        params.put("nameSource", "attributes>embedded-sidecar");
        params.put("flow", "ride-prefix-sum"); // 乘车段站序前缀和分摊，人次为模型原始值

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "ready");
        summary.put("cacheVersion", CORRIDOR_CACHE_VERSION);
        summary.put("generatedAt", System.currentTimeMillis());
        summary.put("params", params);
        summary.put("segments", computation.segments.size());
        summary.put("namedSegments", named);
        summary.put("names", usedNames.size());
        summary.put("maxCoeff", maxCoeff);
        summary.put("maxFlow", maxFlow);
        summary.put("flowRides", computation.flowRides);
        summary.put("flowDroppedRides", computation.flowDroppedRides);
        summary.put("routeStopMismatch", computation.routeStopMismatch);
        summary.put("busLines", countBusLines(computation));
        summary.put("missingLinks", computation.missingLinks);
        return new Artifacts(linksBin, summary, names);
    }

    /** 被计入的公交线路数（全部路段线路集合的并集）。 */
    private static int countBusLines(Computation computation) {
        TreeSet<String> lines = new TreeSet<>();
        for (SegmentAgg agg : computation.segments.values()) {
            lines.addAll(agg.lines);
        }
        return lines.size();
    }

    /**
     * corridor-links.bin（小端）：
     * header = magic "PCRD" + version u16(=2) + count u32（共 10B）；
     * record × count（26B/段）= x1 i32, y1 i32, x2 i32, y2 i32（EPSG:3857 四舍五入取整，米级精度）,
     * coeff u16（clamp 65535）, nameIdx u16（{@link #U16_SENTINEL}=无名）,
     * street u16（中点点面归属的街道要素索引，{@link #U16_SENTINEL}=未命中）,
     * flow u32（断面客流，双向叠加模型原始人次，clamp u32）。
     * 写入序 = 系数升序（平序按几何坐标升序，可复现）：重复系数子模块按写入序绘制即可压顶；
     * 客流子模块前端自行按 flow 升序重排绘制。
     */
    static byte[] encodeLinks(Collection<SegmentAgg> segments,
                              Map<String, Integer> nameIdx, MatsimPopulationCache.StreetIndex streets) {
        List<SegmentAgg> ordered = new ArrayList<>(segments);
        ordered.sort(Comparator.<SegmentAgg>comparingInt(SegmentAgg::coefficient)
                .thenComparingDouble(agg -> agg.x1)
                .thenComparingDouble(agg -> agg.y1)
                .thenComparingDouble(agg -> agg.x2)
                .thenComparingDouble(agg -> agg.y2));
        ByteBuffer buffer = ByteBuffer.allocate(BIN_HEADER_BYTES + BIN_BYTES_PER_SEGMENT * ordered.size())
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(BIN_MAGIC);
        buffer.putShort((short) BIN_VERSION);
        buffer.putInt(ordered.size());
        for (SegmentAgg agg : ordered) {
            buffer.putInt((int) Math.round(agg.x1));
            buffer.putInt((int) Math.round(agg.y1));
            buffer.putInt((int) Math.round(agg.x2));
            buffer.putInt((int) Math.round(agg.y2));
            buffer.putShort((short) Math.min(agg.coefficient(), 0xFFFF));
            Integer name = agg.name == null ? null : nameIdx.get(agg.name);
            buffer.putShort((short) (name == null ? U16_SENTINEL : name.intValue()));
            int street = U16_SENTINEL;
            if (streets != null) {
                int idx = streets.locate((agg.x1 + agg.x2) / 2.0, (agg.y1 + agg.y2) / 2.0);
                if (idx >= 0) {
                    street = idx;
                }
            }
            buffer.putShort((short) street);
            buffer.putInt((int) Math.min(agg.flow, 0xFFFFFFFFL));
        }
        return buffer.array();
    }

    // ===================================================================================
    // 边车表（进程级单例）
    // ===================================================================================

    /** road_{base} → 路名。资源缺失时返回空表并告警（路名退化为属性链，不阻断构建）。 */
    static Long2ObjectOpenHashMap<String> roadNames() {
        Long2ObjectOpenHashMap<String> local = roadNamesSingleton;
        if (local != null) {
            return local;
        }
        synchronized (MatsimCorridorCache.class) {
            if (roadNamesSingleton == null) {
                Long2ObjectOpenHashMap<String> names = new Long2ObjectOpenHashMap<>();
                String tag = "missing";
                try (InputStream in = MatsimCorridorCache.class.getResourceAsStream(ROAD_NAMES_RESOURCE)) {
                    if (in == null) {
                        log.warn("路名边车表资源缺失: {}", ROAD_NAMES_RESOURCE);
                    } else {
                        byte[] bytes = in.readAllBytes();
                        tag = sha256Hex(bytes).substring(0, 16);
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                                new GZIPInputStream(new java.io.ByteArrayInputStream(bytes)), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                int comma = line.indexOf(',');
                                if (comma <= 0) {
                                    continue;
                                }
                                try {
                                    names.put(Long.parseLong(line.substring(0, comma)), line.substring(comma + 1));
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                        log.info("路名边车表加载完成: entries={}", names.size());
                    }
                } catch (Exception e) {
                    log.warn("路名边车表读取失败: {}", ROAD_NAMES_RESOURCE, e);
                }
                roadNamesTagSingleton = tag;
                roadNamesSingleton = names;
            }
            return roadNamesSingleton;
        }
    }

    static String roadNamesTag() {
        String local = roadNamesTagSingleton;
        if (local != null) {
            return local;
        }
        synchronized (MatsimCorridorCache.class) {
            if (roadNamesTagSingleton == null) {
                String tag = "missing";
                try (InputStream in = MatsimCorridorCache.class.getResourceAsStream(ROAD_NAMES_RESOURCE)) {
                    if (in == null) {
                        log.warn("路名边车表资源缺失: {}", ROAD_NAMES_RESOURCE);
                    } else {
                        // 就绪探测只需要内容指纹；不要为了计算哈希解压并常驻 14 万条路名。
                        tag = sha256Hex(in.readAllBytes()).substring(0, 16);
                    }
                } catch (Exception e) {
                    log.warn("路名边车表指纹读取失败: {}", ROAD_NAMES_RESOURCE, e);
                }
                roadNamesTagSingleton = tag;
            }
            return roadNamesTagSingleton;
        }
    }

    // ===================================================================================
    // manifest 与文件读写（模式照 MatsimPopulationCache）
    // ===================================================================================

    private static Map<String, Object> manifest(MatsimData data, boolean ready) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", ready ? "ready" : "failed");
        result.put("cacheVersion", CORRIDOR_CACHE_VERSION);
        result.put("generatedAt", System.currentTimeMillis());
        sourceFingerprint(data, result);
        return result;
    }

    /**
     * 源指纹：transitSchedule（线路遍历来源）+ network（节点/坐标/属性）
     * + 街道资源 + 路名边车表（任一资源升级即失效重建）。
     */
    private static void sourceFingerprint(MatsimData data, Map<String, Object> result) {
        putFileFingerprint(result, "schedule",
                data.getOutfile() == null ? null : data.getOutfile().getTransitSchedule());
        putFileFingerprint(result, "network",
                data.getOutfile() == null ? null : data.getOutfile().getNetwork());
        result.put("streetsResource", MatsimPopulationCache.STREETS_RESOURCE);
        result.put("streetsSha256", MatsimPopulationCache.streetsGeojsonTag());
        result.put("roadNamesResource", ROAD_NAMES_RESOURCE);
        result.put("roadNamesSha256", roadNamesTag());
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
        return MatsimCachePaths.versionDir(data, CORRIDOR_CACHE_VERSION);
    }

    private static Path manifestPath(MatsimData data) {
        return cacheDir(data).resolve(MANIFEST_FILE);
    }

    private static Path summaryPath(MatsimData data) {
        return cacheDir(data).resolve(SUMMARY_FILE);
    }

    private static Path namesPath(MatsimData data) {
        return cacheDir(data).resolve(NAMES_FILE);
    }

    private static Path linksPath(MatsimData data) {
        return cacheDir(data).resolve(LINKS_FILE);
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
}
