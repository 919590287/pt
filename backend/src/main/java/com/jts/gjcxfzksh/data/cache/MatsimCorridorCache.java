package com.jts.gjcxfzksh.data.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jts.gjcxfzksh.data.MatsimData;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

/**
 * 客流走廊监测 · 线路重复系数缓存家族（首个子模块）。
 * <p>
 * 模型加载时从 transitSchedule 的公交（bus 制式）路线遍历路网 link，把双向路网按
 * 「无向节点对」合并为物理路段，统计每段被多少条**不同公交线路**经过（线路重复系数），
 * 产出三个工件：
 * <ul>
 *   <li>{@code corridor-summary.json}：总量指标 + 口径参数（右侧首屏直出）；</li>
 *   <li>{@code corridor-links.bin}：路段二进制表（PCRD 契约，见 {@link #encodeLinks}，
 *       按系数升序写入——前端按写入序绘制即可让高系数走廊压在细线之上）；</li>
 *   <li>{@code corridor-names.json}：路名字典（nameIdx → 名称）+ 街道 district 数组
 *       （streetIdx → district，行政区过滤用，行序 = 街道资源文件序）。</li>
 * </ul>
 * 口径契约（任何改动必须 bump {@link #CORRIDOR_CACHE_VERSION}）：
 * <ul>
 *   <li>只统计 bus 制式路线（制式判定复刻 MatsimTransferCache.classifyTransportMode，
 *       独立版本化互不引用）；subway/tram 及未知制式路线不参与；</li>
 *   <li>物理路段 = link 的 (fromNode, toNode) 无向对：双向路网的两条对向 link 合并为一段，
 *       同一条线路上下行走同一路段只计一次（Set 语义天然去重）；</li>
 *   <li>路名解析链：link 属性（siwei_path_name → siwei_base_name → name → osm:way:name）→
 *       内嵌边车表 {@code geo/gz_road_link_names.csv.gz}（road_{base} → 路名，由源路网
 *       shp 离线几何锚定生成，见资源同名 README 注释）→ 无名；</li>
 *   <li>街道归属：路段中点点面归属（复用 {@link MatsimPopulationCache#streetIndex()}），
 *       仅用于前端行政区过滤，未命中写哨兵。</li>
 * </ul>
 * 线路重复系数与抽样比例无关（数线路不数人），无扩样语义。
 */
@Slf4j
public final class MatsimCorridorCache {

    // v1: 首版口径：bus 制式 + 无向节点对合并 + 属性→边车表路名链 + 中点街道归属。
    public static final String CORRIDOR_CACHE_VERSION = "corridor-v1";

    // ===== corridor-links.bin 布局常量（前后端二进制契约，禁止偏离；小端）=====
    static final byte[] BIN_MAGIC = {'P', 'C', 'R', 'D'};
    static final int BIN_VERSION = 1;
    /** 头部字节数：magic(4) + version u16(2) + count u32(4)。 */
    static final int BIN_HEADER_BYTES = 10;
    /** 每段字节数：x1 i32 + y1 i32 + x2 i32 + y2 i32（EPSG:3857 取整）+ coeff u16 + nameIdx u16 + street u16。 */
    static final int BIN_BYTES_PER_SEGMENT = 22;
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
    /** summary/names 读取记忆化（模式照 MatsimPopulationCache.MEMORY_CACHE）。 */
    private static final Map<String, Map<String, Object>> MEMORY_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                    return size() > 4;
                }
            }
    );

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
            log.warn("走廊缓存状态读取失败: {}", manifestPath(data), e);
            return false;
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
            log.warn("读取走廊汇总缓存失败: model={}, path={}", data.getName(), summaryPath(data), e);
            return Map.of();
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
            log.warn("读取走廊路名缓存失败: model={}, path={}", data.getName(), namesPath(data), e);
            return Map.of();
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
            log.warn("读取走廊路段表失败: model={}, path={}", data.getName(), linksPath(data), e);
            return null;
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
            log.warn("走廊路段表 ETag 计算失败: {}", manifestPath(data), e);
            return null;
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
            Files.createDirectories(cacheDir(data));
            long start = System.currentTimeMillis();
            Artifacts artifacts = buildArtifacts(data);
            writeBytesAtomic(linksPath(data), artifacts.linksBin);
            writeJsonAtomic(namesPath(data), artifacts.names);
            writeJsonAtomic(summaryPath(data), artifacts.summary);
            writeJsonAtomic(manifestPath(data), manifest(data, true));
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

    /** 从 schedule + network 抽取公交遍历并聚合。零轨道/零线路模型产出空工件。 */
    private static Artifacts buildArtifacts(MatsimData data) {
        Extraction extraction = extractBusLineTraversals(data.getSchedule(), data.getNetwork(), roadNames());
        Computation computation = aggregateTraversals(extraction.byLine());
        computation.missingLinks = extraction.missingLinks();
        return assemble(computation, MatsimPopulationCache.streetIndex());
    }

    // ===================================================================================
    // 口径工具（复刻来源见各注释；不得反向修改被复刻方）
    // ===================================================================================

    /**
     * 制式判定：复刻自 MatsimTransferCache.classifyTransportMode 的 bus 侧结论
     * （tram/APM/有轨与 subway/rail 系不算公交，transportMode 缺失按 bus）。独立版本化。
     */
    static boolean isBusTransportMode(String transportMode) {
        String text = transportMode == null ? "" : transportMode.toLowerCase(Locale.ROOT);
        if (text.contains("tram") || text.contains("有轨") || text.contains("apm")) {
            return false;
        }
        return !text.matches(".*(subway|metro|rail|train|轨道|地铁).*");
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
            return null;
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

    /** 抽取产物：lineId → 遍历序列 + 缺失 link 计数。 */
    record Extraction(Map<String, List<TraversedLink>> byLine, long missingLinks) {
    }

    /** lineId → 该线全部 bus 路线经过的 link 序列（含 NetworkRoute 首末 link；缺失 link 计数）。 */
    private static Extraction extractBusLineTraversals(
            TransitSchedule schedule, Network network, Long2ObjectOpenHashMap<String> sidecar) {
        Map<String, List<TraversedLink>> byLine = new LinkedHashMap<>();
        if (schedule == null || network == null) {
            return new Extraction(byLine, 0);
        }
        long missing = 0;
        for (Map.Entry<Id<TransitLine>, TransitLine> lineEntry : schedule.getTransitLines().entrySet()) {
            String lineId = lineEntry.getKey().toString();
            List<TraversedLink> traversals = null;
            for (TransitRoute route : lineEntry.getValue().getRoutes().values()) {
                if (!isBusTransportMode(route.getTransportMode())) {
                    continue;
                }
                NetworkRoute networkRoute = route.getRoute();
                if (networkRoute == null) {
                    continue;
                }
                List<Id<org.matsim.api.core.v01.network.Link>> linkIds = new ArrayList<>();
                linkIds.add(networkRoute.getStartLinkId());
                linkIds.addAll(networkRoute.getLinkIds());
                linkIds.add(networkRoute.getEndLinkId());
                for (Id<org.matsim.api.core.v01.network.Link> linkId : linkIds) {
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
                }
            }
        }
        if (missing > 0) {
            log.warn("走廊缓存路线引用了 {} 条网络中不存在的 link，已跳过", missing);
        }
        return new Extraction(byLine, missing);
    }

    /** 物理路段聚合器：无向节点对 → 线路集合 + 规范化几何 + 首个非空路名。 */
    static final class SegmentAgg {
        final double x1;
        final double y1;
        final double x2;
        final double y2;
        final TreeSet<String> lines = new TreeSet<>();
        String name;

        private SegmentAgg(double x1, double y1, double x2, double y2) {
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
    }

    /**
     * 无向合并：key = 节点 id 字典序对；几何按同一规范方向存（节点序小的一端为 (x1,y1)），
     * 同一线路双向经过同一路段只计一次（TreeSet 去重）；路名取首个非空。
     */
    static Computation aggregateTraversals(Map<String, List<TraversedLink>> byLine) {
        Computation computation = new Computation();
        for (Map.Entry<String, List<TraversedLink>> entry : byLine.entrySet()) {
            String lineId = entry.getKey();
            for (TraversedLink traversal : entry.getValue()) {
                boolean forward = traversal.fromNode().compareTo(traversal.toNode()) <= 0;
                String key = forward
                        ? traversal.fromNode() + "|" + traversal.toNode()
                        : traversal.toNode() + "|" + traversal.fromNode();
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
        long named = 0;
        for (SegmentAgg agg : computation.segments.values()) {
            maxCoeff = Math.max(maxCoeff, agg.coefficient());
            if (agg.name != null) {
                named++;
            }
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("modes", "bus"); // 仅公交线路计入重复系数
        params.put("dedup", "undirected-node-pair"); // 双向合并 + 同线双向只计一次
        params.put("nameSource", "attributes>embedded-sidecar");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "ready");
        summary.put("cacheVersion", CORRIDOR_CACHE_VERSION);
        summary.put("generatedAt", System.currentTimeMillis());
        summary.put("params", params);
        summary.put("segments", computation.segments.size());
        summary.put("namedSegments", named);
        summary.put("names", usedNames.size());
        summary.put("maxCoeff", maxCoeff);
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
     * header = magic "PCRD" + version u16(=1) + count u32（共 10B）；
     * record × count（22B/段）= x1 i32, y1 i32, x2 i32, y2 i32（EPSG:3857 四舍五入取整，米级精度）,
     * coeff u16（clamp 65535）, nameIdx u16（{@link #U16_SENTINEL}=无名）,
     * street u16（中点点面归属的街道要素索引，{@link #U16_SENTINEL}=未命中）。
     * 写入序 = 系数升序（平序按几何坐标升序，可复现）：前端按写入序绘制，高系数走廊后画压顶。
     */
    static byte[] encodeLinks(java.util.Collection<SegmentAgg> segments,
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
        roadNames();
        return roadNamesTagSingleton;
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
