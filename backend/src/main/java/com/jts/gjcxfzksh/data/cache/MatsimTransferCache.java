package com.jts.gjcxfzksh.data.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 公交—地铁换乘分析缓存家族（设计文档《公交地铁换乘分析模块设计方案》v2 §3/§9/§11）。
 * <p>
 * 模型加载时从 {@code PTPersonTrack} 上下车流水单遍识别跨制式换乘事件，产出三个工件：
 * <ul>
 *   <li>{@code transfer-summary.json}：全网指标 + Top 榜（右侧首屏直出模型原始量）；</li>
 *   <li>{@code transfer-events.bin}：紧凑列式事件表（27B/事件，前后端二进制契约，见 §11.2）；</li>
 *   <li>{@code transfer-dict.json.gz}：字典（枢纽/线路/站点 + 稳定原始 ID，见 §11.3）。</li>
 * </ul>
 * 口径契约（§3，任何改动必须 bump {@link #TRANSFER_CACHE_VERSION}）：
 * 识别窗口 30min + 地面距离 800m（epsg:3857 平面距离按 cos(lat) 尺度修正）+ bus↔subway
 * （tram 默认排除，既不算公交也不算轨道）；枢纽 = subway stopFacility 按
 * “清洗站名 + 质心 500m（地面）”聚类；事件时段归属取 tBoard（后序交通工具上车时刻）。
 * 换乘事件为时间—空间规则推定，非实测换乘记录（§3.1），页面与导出需披露口径。
 */
@Slf4j
public final class MatsimTransferCache {

    // v1: 首版口径：30min 时间窗 + 800m 地面距离 + bus↔subway（tramAsRail=false，tram 段两头都不算）；
    //     枢纽=subway stopFacility 清洗站名+质心500m(地面)聚类；hour 桶按 min(floor(tBoard/3600),23) 夹逼；
    //     直方图 30 个分钟桶（识别窗口封顶，无溢出桶，1800s 计入桶 29）。
    // v2: 原模型数量直出，取消所有 desc.scale 扩样。
    // v3: 事件表增加公交整段上车站 busOriginStop，供地铁枢纽详情还原公交来向。
    // v4: 事件表再增加公交整段下车站 busDestinationStop，使公→地、地→公都能还原
    //     “公交整段端点—公交换乘站—地铁换乘站”的完整链路。
    public static final String TRANSFER_CACHE_VERSION = "transfer-v4";

    // ===== §3 统一口径常量（改动必须 bump 版本）=====
    /** 换乘识别时间窗（秒），与 TransitMetrics.transferStats 的 1800s 窗口一致（仅数值一致，互不引用）。 */
    static final int TRANSFER_WINDOW_SECONDS = 1800;
    /** 前后站地面距离阈值（米）。阈值语义是地面距离，比较前先做 Mercator cos(lat) 修正（§9.1）。 */
    static final double TRANSFER_MAX_DIST_M = 800.0;
    /** 枢纽聚类质心半径（米，地面距离）。注意与 StationPanelCache 的 300（投影单位）口径不同。 */
    static final double HUB_CLUSTER_RADIUS_M = 500.0;
    /** tram/APM/有轨默认排除（§3.3）：置 true 时 tram 归轨道，且必须 bump 版本并同步 params。 */
    static final boolean TRAM_AS_RAIL = false;

    /** 制式常量。tram 单列：默认既不算公交也不算轨道，夹在 bus/subway 之间还会隔断相邻性。 */
    static final String MODE_BUS = "bus";
    static final String MODE_SUBWAY = "subway";
    static final String MODE_TRAM = "tram";

    /** 事件方向编码（bin 的 dir 列，u8）。 */
    static final int DIR_BUS_TO_METRO = 0;
    static final int DIR_METRO_TO_BUS = 1;

    // ===== transfer-events.bin 布局常量（§11.2，前后端二进制契约，禁止偏离）=====
    static final byte[] BIN_MAGIC = {'T', 'F', 'E', 'V'};
    static final int BIN_VERSION = 3;
    /** 头部字节数：magic(4) + version u16(2) + count u32(4)。 */
    static final int BIN_HEADER_BYTES = 10;
    /** 每事件字节数：personIndex u32 + tBoard u32 + transferSec u16 + dir u8 + 8×u16 字典索引。 */
    static final int BIN_BYTES_PER_EVENT = 27;

    private static final String SUMMARY_FILE = "transfer-summary.json";
    private static final String DICT_FILE = "transfer-dict.json.gz";
    private static final String EVENTS_FILE = "transfer-events.bin";
    private static final String MANIFEST_FILE = "manifest.json";

    private static final int HOURS = 24;
    private static final int HISTOGRAM_MINUTES = 30;
    private static final int TOP_LIMIT = 20;
    /** 字典索引写入 u16 列，超上限直接报错（当前量级 bus 线 1868/站 24023/metro 站 2926，远未触及）。 */
    private static final int U16_MAX = 0xFFFF;
    // 项目统一投影 epsg:3857（见 Datasource.ctf），纬度反算与 StationPanelCache.mercatorToWgs84 同源。
    private static final double EARTH_RADIUS = 6378137.0;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    /** summary/dict 读取记忆化：2 模型 × 2 工件。 */
    private static final Map<String, Map<String, Object>> MEMORY_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                    return size() > 4;
                }
            }
    );

    private MatsimTransferCache() {
    }

    // ===================================================================================
    // 对外入口（模式照 MatsimStationPanelCache）
    // ===================================================================================

    public static void prepareOnModelLoad(MatsimData data) {
        // per-model 锁：模型 A 构建期间不阻塞模型 B；同一模型串行保证幂等
        synchronized (ModelBuildLocks.lockFor("transfer", data)) {
            ensureTransferCacheLocked(data);
        }
    }

    public static boolean isReady(MatsimData data) {
        if (!Files.exists(manifestPath(data)) || !Files.exists(summaryPath(data))
                || !Files.exists(dictPath(data)) || !Files.exists(eventsPath(data))) {
            return false;
        }
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath(data).toFile(), MAP_TYPE);
            return "ready".equals(manifest.get("status"))
                    && TRANSFER_CACHE_VERSION.equals(manifest.get("cacheVersion"))
                    && sameSources(data, manifest);
        } catch (Exception e) {
            log.warn("换乘分析缓存状态读取失败: {}", manifestPath(data), e);
            return false;
        }
    }

    /** 全网指标 + Top 榜（POST /pt/transfer/summary）。未就绪返回 generating 态。 */
    public static Map<String, Object> readTransferSummary(MatsimData data) {
        if (!isReady(data)) {
            return generatingPayload();
        }
        try {
            return loadCachedJson(summaryPath(data), false);
        } catch (Exception e) {
            log.warn("读取换乘汇总缓存失败: model={}, path={}", data.getName(), summaryPath(data), e);
            return Map.of();
        }
    }

    /** 字典（POST /pt/transfer/dict）。未就绪返回 generating 态。 */
    public static Map<String, Object> readTransferDict(MatsimData data) {
        if (!isReady(data)) {
            return generatingPayload();
        }
        try {
            return loadCachedJson(dictPath(data), true);
        } catch (Exception e) {
            log.warn("读取换乘字典缓存失败: model={}, path={}", data.getName(), dictPath(data), e);
            return Map.of();
        }
    }

    /** 列式事件表字节（GET /pt/transfer/events.bin）。未就绪返回 null（Controller 侧 404）。 */
    public static byte[] readEventsBytes(MatsimData data) {
        if (!isReady(data)) {
            return null;
        }
        try {
            return Files.readAllBytes(eventsPath(data));
        } catch (Exception e) {
            log.warn("读取换乘事件表失败: model={}, path={}", data.getName(), eventsPath(data), e);
            return null;
        }
    }

    /**
     * events.bin 的强校验 ETag 内容：manifest 的 sourceFingerprint + cacheVersion 哈希（§9.2）。
     * 未就绪返回 null。generatedAt 不参与哈希——同源同版本重建不应打穿客户端缓存。
     */
    public static String eventsBinTag(MatsimData data) {
        if (!isReady(data)) {
            return null;
        }
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath(data).toFile(), MAP_TYPE);
            StringBuilder content = new StringBuilder(TRANSFER_CACHE_VERSION);
            new TreeMap<>(manifest).forEach((key, value) -> {
                if (key.endsWith("File") || key.endsWith("Modified") || key.endsWith("Size")) {
                    content.append('|').append(key).append('=').append(value);
                }
            });
            return sha256Hex(content.toString()).substring(0, 16);
        } catch (Exception e) {
            log.warn("换乘事件表 ETag 计算失败: {}", manifestPath(data), e);
            return null;
        }
    }

    private static Map<String, Object> generatingPayload() {
        return Map.of(
                "status", "generating",
                "cacheVersion", TRANSFER_CACHE_VERSION,
                "message", "换乘分析缓存正在后台生成"
        );
    }

    // ===================================================================================
    // 构建编排
    // ===================================================================================

    private static void ensureTransferCacheLocked(MatsimData data) {
        if (isReady(data)) {
            return; // 幂等：已就绪直接跳过
        }
        try {
            Artifacts artifacts = buildArtifacts(data);
            MatsimCachePaths.recreateVersionDir(data, TRANSFER_CACHE_VERSION);
            // 工件先落盘、manifest 最后写：manifest=ready 即三工件必然齐备
            writeBytesAtomic(eventsPath(data), artifacts.eventsBin);
            writeGzipJson(dictPath(data), artifacts.dict);
            writeJsonAtomic(summaryPath(data), artifacts.summary);
            writeJsonAtomic(manifestPath(data), manifest(data, true));
            MatsimCachePaths.deleteOtherVersions(data, "transfer-v", TRANSFER_CACHE_VERSION);
            MEMORY_CACHE.remove(cacheKey(summaryPath(data)));
            MEMORY_CACHE.remove(cacheKey(dictPath(data)));
            Object totals = artifacts.summary.get("totals");
            log.info("换乘分析缓存生成完成: model={}, totals={}, droppedTracks={}, bin={}B",
                    data.getName(), totals, artifacts.summary.get("droppedTracks"), artifacts.eventsBin.length);
        } catch (Exception e) {
            try {
                Files.createDirectories(cacheDir(data));
                writeJsonAtomic(manifestPath(data), manifest(data, false));
            } catch (Exception ignored) {
            }
            throw new RuntimeException("换乘分析缓存生成失败: " + e.getMessage(), e);
        }
    }

    /** 从已加载 scenario 抽取制式/名称/坐标索引，跑识别 + 组装。空轨道网/零事件模型产出全零工件。 */
    private static Artifacts buildArtifacts(MatsimData data) {
        TransitSchedule schedule = data.getSchedule();

        Map<String, RouteRef> byLineRoute = new HashMap<>();
        Map<String, RouteRef> byRouteOnly = new HashMap<>();
        Set<String> conflictedRouteIds = new java.util.HashSet<>();
        Map<String, String> lineNames = new HashMap<>();
        Map<String, String> routeNames = new HashMap<>();
        // TreeSet：聚类按 facilityId 字典序处理，结果可复现
        TreeSet<String> railFacilityIds = new TreeSet<>();

        for (Map.Entry<Id<TransitLine>, TransitLine> lineEntry : schedule.getTransitLines().entrySet()) {
            String lineId = lineEntry.getKey().toString();
            TransitLine line = lineEntry.getValue();
            // line 显示名取 transitLine name，空则退回 id（§11.3）
            lineNames.put(lineId, nonBlank(line.getName(), lineId));
            for (Map.Entry<Id<TransitRoute>, TransitRoute> routeEntry : line.getRoutes().entrySet()) {
                String routeId = routeEntry.getKey().toString();
                TransitRoute route = routeEntry.getValue();
                RouteRef ref = new RouteRef(lineId, effectiveMode(classifyTransportMode(route.getTransportMode())));
                byLineRoute.put(routeKey(lineId, routeId), ref);
                // routeId 不保证全局唯一（PTHandler/RoutePanelCache v11 同注）：
                // 冲突的 routeId 在 lineId 缺失时不可归属，登记后按未知制式处理
                RouteRef previous = byRouteOnly.putIfAbsent(routeId, ref);
                if (previous != null && !previous.equals(ref)) {
                    conflictedRouteIds.add(routeId);
                }
                // route 显示名沿用 RoutePanelCache 习惯：description 优先，空则 routeId
                routeNames.put(routeKey(lineId, routeId), nonBlank(route.getDescription(), routeId));
                if (MODE_SUBWAY.equals(ref.mode())) {
                    for (TransitRouteStop stop : route.getStops()) {
                        railFacilityIds.add(stop.getStopFacility().getId().toString());
                    }
                }
            }
        }

        Map<String, double[]> coordByFacility = new HashMap<>();
        Map<String, String> nameByFacility = new HashMap<>();
        for (Map.Entry<Id<TransitStopFacility>, TransitStopFacility> entry : schedule.getFacilities().entrySet()) {
            String facilityId = entry.getKey().toString();
            TransitStopFacility facility = entry.getValue();
            nameByFacility.put(facilityId, nonBlank(facility.getName(), facilityId));
            if (facility.getCoord() != null) {
                coordByFacility.put(facilityId, new double[]{facility.getCoord().getX(), facility.getCoord().getY()});
            }
        }

        HubClusters hubs = clusterHubs(railFacilityIds, coordByFacility, nameByFacility);
        BiFunction<String, String, RouteRef> resolver = (lineId, routeId) -> {
            if (routeId == null) {
                return null;
            }
            if (lineId != null) {
                RouteRef ref = byLineRoute.get(routeKey(lineId, routeId));
                if (ref != null) {
                    return ref;
                }
            }
            // lineId 缺失（vlMap 未命中）时按 routeId 兜底，跨线冲突则视为未知制式
            return conflictedRouteIds.contains(routeId) ? null : byRouteOnly.get(routeId);
        };
        TransferComputation computation = computeTransfers(data, resolver, coordByFacility);
        return assemble(computation, hubs, lineNames, routeNames, nameByFacility, coordByFacility,
                effectiveSampleRate(data));
    }

    // ===================================================================================
    // 口径工具（复刻来源见各注释；不得反向修改被复刻方）
    // ===================================================================================

    /**
     * 制式判定：transportMode 优先。正则口径复刻自 PTDataServiceImpl.routeModeIndex
     * （api.service.impl 包内 static，缓存层不可达，不改其可见性与行为）。
     * 与 routeModeIndex 的差异仅在 tram：本模块把 tram/APM/有轨单独识别为 {@link #MODE_TRAM}。
     * 体检评估 gjjbbl 已改由 population-v9 的完整 OD 出行链及统一 schedule 制式解析计算，
     * 不再依赖本换乘事件子集或这里的兜底分类。
     * transportMode 缺失时与 routeModeIndex 一致按 bus 处理（广州模型 transportMode 全覆盖）。
     */
    static String classifyTransportMode(String transportMode) {
        String text = transportMode == null ? "" : transportMode.toLowerCase(Locale.ROOT);
        if (text.contains("tram") || text.contains("有轨") || text.contains("apm")) {
            return MODE_TRAM;
        }
        if (text.matches(".*(subway|metro|rail|train|轨道|地铁).*")) {
            return MODE_SUBWAY;
        }
        return MODE_BUS;
    }

    /** tramAsRail 开关生效点：true 时 tram 归轨道（v1 固定 false，翻转必须 bump 版本）。 */
    static String effectiveMode(String mode) {
        return TRAM_AS_RAIL && MODE_TRAM.equals(mode) ? MODE_SUBWAY : mode;
    }

    /** 数量严格按模型原始值计算；历史 scale 不参与任何数值。 */
    static double effectiveSampleRate(MatsimData data) {
        return 1.0;
    }

    /**
     * epsg:3857 平面两点的地面距离（米）：平面欧氏 × cos(纬度)。
     * Web Mercator 在广州纬度（约 23°N）系统性高估地面距离约 8.6%，不修正等效把 800m
     * 阈值收紧到约 737m（§9.1）。纬度取两点中点反算：lat = atan(sinh(y/R))。
     */
    static double groundDistanceMeters(double x1, double y1, double x2, double y2) {
        double dx = x1 - x2;
        double dy = y1 - y2;
        double planar = Math.sqrt(dx * dx + dy * dy);
        double lat = Math.atan(Math.sinh(((y1 + y2) / 2.0) / EARTH_RADIUS));
        return planar * Math.cos(lat);
    }

    /** 站名清洗：参照 MatsimStationPanelCache.normalizeStationName（去空白 + 小写）。 */
    static String cleanStationName(String name) {
        return name == null ? "" : name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    // ===================================================================================
    // 枢纽聚类（§3.2：清洗站名分组 + 质心 500m 地面距离，质心合并防链式传播）
    // ===================================================================================

    /**
     * subway 制式 route 途经的全部 stopFacility 聚类为枢纽。
     * 质心合并：新成员并入前校验其到当前质心的地面距离 ≤500m（而非到任一成员），
     * 防止链式传播把聚类首尾拉超阈值；多个簇同时命中时并入最近的簇。
     * 输入按 facilityId 字典序迭代，聚类结果与 hubKey 可复现。
     */
    static HubClusters clusterHubs(
            Collection<String> railFacilityIds,
            Map<String, double[]> coordByFacility,
            Map<String, String> nameByFacility
    ) {
        HubClusters clusters = new HubClusters(coordByFacility, nameByFacility);
        TreeMap<String, List<String>> byCleanName = new TreeMap<>();
        for (String facilityId : new TreeSet<>(railFacilityIds)) {
            byCleanName.computeIfAbsent(clusterGroupKey(facilityId, nameByFacility), ignored -> new ArrayList<>())
                    .add(facilityId);
        }
        for (Map.Entry<String, List<String>> entry : byCleanName.entrySet()) {
            List<MutableHub> group = new ArrayList<>();
            for (String facilityId : entry.getValue()) { // 组内已按 facilityId 升序
                double[] coord = coordByFacility.get(facilityId);
                MutableHub best = null;
                double bestDistance = Double.MAX_VALUE;
                if (coord != null) {
                    for (MutableHub hub : group) {
                        if (hub.coordCount == 0) {
                            continue; // 无坐标簇无法做质心校验
                        }
                        double distance = groundDistanceMeters(
                                coord[0], coord[1], hub.centroidX(), hub.centroidY());
                        if (distance <= HUB_CLUSTER_RADIUS_M && distance < bestDistance) {
                            best = hub;
                            bestDistance = distance;
                        }
                    }
                }
                if (best == null) {
                    best = new MutableHub(entry.getKey());
                    group.add(best);
                }
                best.add(facilityId, coord);
            }
            group.forEach(clusters::register);
        }
        return clusters;
    }

    /** 聚类分组键：清洗站名；站名为空退回清洗后的 facilityId，避免空键把无名站全并成一组。 */
    private static String clusterGroupKey(String facilityId, Map<String, String> nameByFacility) {
        String cleaned = cleanStationName(nameByFacility.get(facilityId));
        return cleaned.isBlank() ? cleanStationName(facilityId) : cleaned;
    }

    /** 聚类累加器：成员按加入顺序（=facilityId 升序）保存，首个成员即字典序最小成员。 */
    private static final class MutableHub {
        private final String cleanedName;
        private final List<String> members = new ArrayList<>();
        private double sumX;
        private double sumY;
        private int coordCount;

        private MutableHub(String cleanedName) {
            this.cleanedName = cleanedName;
        }

        private void add(String facilityId, double[] coord) {
            members.add(facilityId);
            if (coord != null) {
                sumX += coord[0];
                sumY += coord[1];
                coordCount++;
            }
        }

        private double centroidX() {
            return sumX / coordCount;
        }

        private double centroidY() {
            return sumY / coordCount;
        }
    }

    /** 枢纽定稿结构：hubKey = 清洗站名 + "|" + 字典序最小成员 facilityId（跨方案稳定键，§3.2）。 */
    static final class Hub {
        final String hubKey;
        final String name;
        final double x;
        final double y;
        final List<String> members; // facilityId 升序

        private Hub(String hubKey, String name, double x, double y, List<String> members) {
            this.hubKey = hubKey;
            this.name = name;
            this.x = x;
            this.y = y;
            this.members = members;
        }
    }

    /** 聚类结果索引：facilityId → hubKey；事件引用了聚类外的轨道 facility 时懒建单站枢纽兜底。 */
    static final class HubClusters {
        private final Map<String, double[]> coordByFacility;
        private final Map<String, String> nameByFacility;
        private final Map<String, Hub> hubsByKey = new HashMap<>();
        private final Map<String, String> keyByFacility = new HashMap<>();

        private HubClusters(Map<String, double[]> coordByFacility, Map<String, String> nameByFacility) {
            this.coordByFacility = coordByFacility;
            this.nameByFacility = nameByFacility;
        }

        private void register(MutableHub mutable) {
            String minMember = mutable.members.get(0);
            String hubKey = mutable.cleanedName + "|" + minMember;
            String displayName = nonBlank(nameByFacility.get(minMember), mutable.cleanedName);
            double x = mutable.coordCount > 0 ? mutable.sumX / mutable.coordCount : 0.0;
            double y = mutable.coordCount > 0 ? mutable.sumY / mutable.coordCount : 0.0;
            Hub hub = new Hub(hubKey, displayName, x, y, List.copyOf(mutable.members));
            hubsByKey.put(hubKey, hub);
            mutable.members.forEach(member -> keyByFacility.put(member, hubKey));
        }

        /**
         * 事件轨道侧 facility → 枢纽键。枢纽归属精确、不做 800m 就近猜测（§3.2）。
         * 理论上事件 facility 必在 subway 路线站集内；万一不在（events 与 schedule 不配套），
         * 懒建单站枢纽保证事件不丢——单站键与内容只依赖 facility 自身，结果与处理顺序无关。
         */
        String hubKeyOf(String facilityId) {
            String existing = keyByFacility.get(facilityId);
            if (existing != null) {
                return existing;
            }
            MutableHub singleton = new MutableHub(clusterGroupKey(facilityId, nameByFacility));
            singleton.add(facilityId, coordByFacility.get(facilityId));
            register(singleton);
            return keyByFacility.get(facilityId);
        }

        Hub hub(String hubKey) {
            return hubsByKey.get(hubKey);
        }
    }

    // ===================================================================================
    // 换乘事件识别（§3.1）
    // ===================================================================================

    /** (lineId, mode)：track 的线路归属与制式。 */
    record RouteRef(String lineId, String mode) {
    }

    /** 识别期事件（保留原始 ID，落盘前经字典编码；原始 personId 不落任何工件）。 */
    record RawEvent(String personId, long tBoard, int transferSec, int dir,
                    String busLineId, String busRouteId, String busStopId,
                    String busOriginStopId, String busDestinationStopId,
                    String metroLineId, String metroStopId) {
    }

    /** 识别产物：事件 + 配对失败计数 + 公交线全线上/下车人次（dict 契约补充字段用）。 */
    static final class TransferComputation {
        final List<RawEvent> events = new ArrayList<>();
        long droppedTracks;
        /** lineId → [boardings, alightings]，bus 制式全线全日模型原始量（口径同 routePanel totalBoardings/totalAlightings）。 */
        final Map<String, long[]> busLineFlows = new HashMap<>();
    }

    /** 乘车段：enter/leave 配对结果，归属取上车记录（与 pt-events-v3 上车归属口径一致）。 */
    private record RideSegment(String lineId, String routeId, String mode,
                               String boardFacility, double boardTime,
                               String alightFacility, double alightTime) {
    }

    /**
     * 同人 track 时间排序（口径复刻自 MatsimStationPanelCache.TRACK_TIME_ORDER）：
     * 同一秒“先下车后上车”（零等待换乘的自然顺序），末键按车辆 ID 定序——
     * tracks 源是无序 HashSet，无次键时同秒事件顺序不可复现，配对结果会随构建漂移。
     */
    private static final Comparator<PTPersonTrack> TRACK_TIME_ORDER =
            Comparator.comparingDouble(MatsimTransferCache::safeTime)
                    .thenComparingInt(track -> Boolean.TRUE.equals(track.getEnter()) ? 1 : 0)
                    .thenComparing(track -> String.valueOf(track.getVehicleId()));

    /**
     * 换乘事件识别主流程：按 person 分组 → 时间排序 → enter/leave 交替配对成乘车段 →
     * 相邻乘车段按（时间窗 + 跨制式 + 地面 800m）三条件判定事件（§3.1）。
     * 不成对 track（缺上车/缺下车/车辆对不上/enter 标记缺失）丢弃并计入 droppedTracks。
     *
     * @param routeResolver (lineId 可空, routeId) → 线路归属与制式；null 表示未知（该段不参与任何事件）
     */
    static TransferComputation computeTransfers(
            Collection<PTPersonTrack> tracks,
            BiFunction<String, String, RouteRef> routeResolver,
            Map<String, double[]> coordByFacility
    ) {
        TransferComputation result = new TransferComputation();
        if (tracks == null || tracks.isEmpty()) {
            return result;
        }
        Map<String, List<PTPersonTrack>> byPerson = new HashMap<>();
        for (PTPersonTrack track : tracks) {
            // dict 契约补充：bus 制式全线全日上/下车人次（含未能配对的 track，口径与 routePanel 原始计数一致）
            RouteRef ref = resolveTrack(routeResolver, track);
            if (ref != null && ref.lineId() != null && MODE_BUS.equals(ref.mode()) && track.getEnter() != null) {
                result.busLineFlows.computeIfAbsent(ref.lineId(), ignored -> new long[2])
                        [Boolean.TRUE.equals(track.getEnter()) ? 0 : 1]++;
            }
            String personId = idString(track.getPersonId());
            if (personId == null) {
                result.droppedTracks++; // 无 person 无法配对
                continue;
            }
            byPerson.computeIfAbsent(personId, ignored -> new ArrayList<>()).add(track);
        }
        for (Map.Entry<String, List<PTPersonTrack>> entry : byPerson.entrySet()) {
            collectPersonEvents(entry.getKey(), entry.getValue(), routeResolver, coordByFacility, result);
        }
        return result;
    }

    /** 大模型磁盘态入口：逐 person 分区处理，不把全量 tracks 或全量 byPerson 留在堆中。 */
    private static TransferComputation computeTransfers(
            MatsimData data,
            BiFunction<String, String, RouteRef> routeResolver,
            Map<String, double[]> coordByFacility
    ) {
        if (data.getPersonTracks() != null && !data.getPersonTracks().isEmpty()) {
            return computeTransfers(data.getPersonTracks(), routeResolver, coordByFacility);
        }
        TransferComputation result = new TransferComputation();
        MatsimPersonTrackStore.forEachPerson(data, (personId, tracks) -> {
            for (PTPersonTrack track : tracks) {
                RouteRef ref = resolveTrack(routeResolver, track);
                if (ref != null && ref.lineId() != null && MODE_BUS.equals(ref.mode()) && track.getEnter() != null) {
                    result.busLineFlows.computeIfAbsent(ref.lineId(), ignored -> new long[2])
                            [Boolean.TRUE.equals(track.getEnter()) ? 0 : 1]++;
                }
            }
            if (personId == null || personId.isBlank()) {
                result.droppedTracks += tracks.size();
                return;
            }
            collectPersonEvents(personId, tracks, routeResolver, coordByFacility, result);
        });
        return result;
    }

    private static void collectPersonEvents(
            String personId,
            List<PTPersonTrack> personTracks,
            BiFunction<String, String, RouteRef> routeResolver,
            Map<String, double[]> coordByFacility,
            TransferComputation out
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
            RouteRef ref = resolveTrack(routeResolver, open); // 归属按上车记录
            segments.add(new RideSegment(
                    ref == null ? null : ref.lineId(),
                    idString(open.getRouteId()),
                    ref == null ? null : ref.mode(),
                    idString(open.getFacilityId()), safeTime(open),
                    idString(track.getFacilityId()), safeTime(track)));
            open = null;
        }
        if (open != null) {
            out.droppedTracks++; // 收尾未闭合的上车
        }

        for (int i = 1; i < segments.size(); i++) {
            RideSegment prev = segments.get(i - 1);
            RideSegment next = segments.get(i);
            double gap = next.boardTime() - prev.alightTime();
            // 时间窗：0 ≤ tBoard−tAlight ≤ 1800s（边界 1800 含）
            if (gap < 0 || gap > TRANSFER_WINDOW_SECONDS) {
                continue;
            }
            // 跨制式组合。tram/未知制式段两头都不构成事件，且天然隔断 bus 与 subway 的相邻性
            int dir;
            RideSegment busSeg;
            RideSegment metroSeg;
            String busStop;
            String metroStop;
            if (MODE_BUS.equals(prev.mode()) && MODE_SUBWAY.equals(next.mode())) {
                dir = DIR_BUS_TO_METRO;
                busSeg = prev;
                metroSeg = next;
                busStop = prev.alightFacility();  // 公交下车站
                metroStop = next.boardFacility(); // 轨道侧实际上车站
            } else if (MODE_SUBWAY.equals(prev.mode()) && MODE_BUS.equals(next.mode())) {
                dir = DIR_METRO_TO_BUS;
                busSeg = next;
                metroSeg = prev;
                busStop = next.boardFacility();    // 公交上车站
                metroStop = prev.alightFacility(); // 轨道侧实际下车站
            } else {
                continue;
            }
            String busOriginStop = busSeg.boardFacility();
            String busDestinationStop = busSeg.alightFacility();
            if (busSeg.lineId() == null || metroSeg.lineId() == null
                    || busStop == null || busOriginStop == null || busDestinationStop == null || metroStop == null) {
                continue; // 无法字典编码的残缺段不成事件
            }
            // 空间校验：前后站地面距离 ≤800m（cos(lat) 修正后比较）
            double[] busCoord = coordByFacility.get(busStop);
            double[] metroCoord = coordByFacility.get(metroStop);
            if (busCoord == null || metroCoord == null) {
                continue; // 缺坐标无法校验（schedule 均带坐标，理论不发生）
            }
            if (groundDistanceMeters(busCoord[0], busCoord[1], metroCoord[0], metroCoord[1]) > TRANSFER_MAX_DIST_M) {
                continue;
            }
            out.events.add(new RawEvent(
                    personId,
                    Math.max(0L, Math.round(next.boardTime())), // 归属时刻 = 后序交通工具上车时刻（§3.5）
                    (int) Math.round(gap),
                    dir,
                    busSeg.lineId(), busSeg.routeId(), busStop, busOriginStop, busDestinationStop,
                    metroSeg.lineId(), metroStop));
        }
    }

    private static RouteRef resolveTrack(BiFunction<String, String, RouteRef> resolver, PTPersonTrack track) {
        return resolver == null ? null : resolver.apply(idString(track.getLineId()), idString(track.getRouteId()));
    }

    // ===================================================================================
    // 组装：排序 → personIndex → 字典编码 → bin/dict/summary（§11）
    // ===================================================================================

    /** 三工件组装结果。 */
    static final class Artifacts {
        final byte[] eventsBin;
        final Map<String, Object> dict;
        final Map<String, Object> summary;

        private Artifacts(byte[] eventsBin, Map<String, Object> dict, Map<String, Object> summary) {
            this.eventsBin = eventsBin;
            this.dict = dict;
            this.summary = summary;
        }
    }

    /**
     * 事件写入序（§11.2 要求 tBoard 升序）；后续键仅为构建可复现（源 HashMap 分组无序）。
     * personId 参与排序但不落盘。
     */
    private static final Comparator<RawEvent> EVENT_ORDER =
            Comparator.comparingLong(RawEvent::tBoard)
                    .thenComparing(RawEvent::personId)
                    .thenComparingInt(RawEvent::transferSec)
                    .thenComparingInt(RawEvent::dir)
                    .thenComparing(RawEvent::busOriginStopId)
                    .thenComparing(RawEvent::busDestinationStopId)
                    .thenComparing(RawEvent::busStopId)
                    .thenComparing(RawEvent::metroStopId);

    static Artifacts assemble(
            TransferComputation computation,
            HubClusters hubs,
            Map<String, String> lineNames,
            Map<String, String> routeNames,
            Map<String, String> nameByFacility,
            Map<String, double[]> coordByFacility,
            double scale
    ) {
        List<RawEvent> events = new ArrayList<>(computation.events);
        events.sort(EVENT_ORDER);
        int count = events.size();

        // ---- 引用集合（字典只含被事件引用的对象，§11.3）----
        TreeMap<String, TreeSet<String>> busRoutesByLine = new TreeMap<>();
        TreeSet<String> metroLineIds = new TreeSet<>();
        TreeSet<String> busStopIds = new TreeSet<>();
        TreeSet<String> metroStopIds = new TreeSet<>();
        TreeSet<String> hubKeys = new TreeSet<>();
        String[] eventHubKeys = new String[count];
        for (int i = 0; i < count; i++) {
            RawEvent event = events.get(i);
            busRoutesByLine.computeIfAbsent(event.busLineId(), ignored -> new TreeSet<>()).add(event.busRouteId());
            metroLineIds.add(event.metroLineId());
            busStopIds.add(event.busStopId());
            busStopIds.add(event.busOriginStopId());
            busStopIds.add(event.busDestinationStopId());
            metroStopIds.add(event.metroStopId());
            String hubKey = hubs.hubKeyOf(event.metroStopId()); // 枢纽归属=轨道侧 facility 所属聚类（§3.2）
            eventHubKeys[i] = hubKey;
            hubKeys.add(hubKey);
        }
        // 被引用枢纽的全部成员站并入 metroStops（跨方案对齐兜底，§11.3）
        for (String hubKey : hubKeys) {
            metroStopIds.addAll(hubs.hub(hubKey).members);
        }

        // ---- 字典索引（各集合按稳定 ID 字典序编号，跨构建可复现）----
        Map<String, Integer> busLineIdx = indexOf(busRoutesByLine.keySet(), "busLines");
        Map<String, Map<String, Integer>> busRouteIdx = new HashMap<>();
        for (Map.Entry<String, TreeSet<String>> entry : busRoutesByLine.entrySet()) {
            // busRoute 是线内局部索引（dict.busLines[busLine].routes 数组下标，§11.2）
            busRouteIdx.put(entry.getKey(), indexOf(entry.getValue(), "busLines[" + entry.getKey() + "].routes"));
        }
        Map<String, Integer> metroLineIdx = indexOf(metroLineIds, "metroLines");
        Map<String, Integer> busStopIdx = indexOf(busStopIds, "busStops");
        Map<String, Integer> metroStopIdx = indexOf(metroStopIds, "metroStops");
        Map<String, Integer> hubIdx = indexOf(hubKeys, "hubs");

        // ---- 终态单遍：编码列 + 全部汇总 ----
        // personIndex 按排序后事件表的 person 首次出现顺序 0 起自增（§11.2，不落原始 personId）
        Map<String, Integer> personIndexById = new LinkedHashMap<>();
        ByteBuffer buffer = ByteBuffer.allocate(BIN_HEADER_BYTES + BIN_BYTES_PER_EVENT * count)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(BIN_MAGIC);
        buffer.putShort((short) BIN_VERSION);
        buffer.putInt(count);

        int[][] hourly = new int[2][HOURS];
        int[] histogramMin = new int[HISTOGRAM_MINUTES];
        long[] dirCounts = new long[2];
        long sumSec = 0;
        int[] allSecs = new int[count];
        Map<Integer, FlowAgg> hubAggs = new TreeMap<>();
        Map<Long, FlowAgg> pairAggs = new TreeMap<>();

        int[] personCol = new int[count];
        long[] tBoardCol = new long[count];
        int[] busLineCol = new int[count];
        int[] busRouteCol = new int[count];
        int[] busStopCol = new int[count];
        int[] busOriginStopCol = new int[count];
        int[] busDestinationStopCol = new int[count];
        int[] metroLineCol = new int[count];
        int[] metroStopCol = new int[count];
        int[] hubCol = new int[count];
        for (int i = 0; i < count; i++) {
            RawEvent event = events.get(i);
            personCol[i] = personIndexById.computeIfAbsent(event.personId(), ignored -> personIndexById.size());
            tBoardCol[i] = event.tBoard();
            busLineCol[i] = busLineIdx.get(event.busLineId());
            busRouteCol[i] = busRouteIdx.get(event.busLineId()).get(event.busRouteId());
            busStopCol[i] = busStopIdx.get(event.busStopId());
            busOriginStopCol[i] = busStopIdx.get(event.busOriginStopId());
            busDestinationStopCol[i] = busStopIdx.get(event.busDestinationStopId());
            metroLineCol[i] = metroLineIdx.get(event.metroLineId());
            metroStopCol[i] = metroStopIdx.get(event.metroStopId());
            hubCol[i] = hubIdx.get(eventHubKeys[i]);

            // hour = min(floor(tBoard/3600), 23) 夹逼（与前端约定一致；跨零点班次全部记入 23 时桶）
            int hour = (int) Math.min(event.tBoard() / 3600L, HOURS - 1);
            hourly[event.dir()][hour]++;
            // 分钟桶 = min(floor(sec/60), 29)：识别窗口封顶无溢出桶，1800s 边界值计入桶 29（§3.4）
            histogramMin[Math.min(event.transferSec() / 60, HISTOGRAM_MINUTES - 1)]++;
            dirCounts[event.dir()]++;
            sumSec += event.transferSec();
            allSecs[i] = event.transferSec();

            FlowAgg hubAgg = hubAggs.computeIfAbsent(hubCol[i], ignored -> new FlowAgg());
            hubAgg.add(event.transferSec());
            hubAgg.metroLines.add(metroLineCol[i]);
            long pairKey = ((long) busLineCol[i] << 32) | metroLineCol[i];
            pairAggs.computeIfAbsent(pairKey, ignored -> new FlowAgg()).add(event.transferSec());
        }
        // 列式落盘，列顺序固定（§11.2）：personIndex → tBoard → transferSec → dir → busLine →
        // busRoute → busStop → busOriginStop → busDestinationStop → metroLine → metroStop → hub
        for (int i = 0; i < count; i++) buffer.putInt(personCol[i]);
        for (int i = 0; i < count; i++) buffer.putInt((int) tBoardCol[i]);
        for (int i = 0; i < count; i++) buffer.putShort((short) events.get(i).transferSec());
        for (int i = 0; i < count; i++) buffer.put((byte) events.get(i).dir());
        for (int i = 0; i < count; i++) buffer.putShort((short) busLineCol[i]);
        for (int i = 0; i < count; i++) buffer.putShort((short) busRouteCol[i]);
        for (int i = 0; i < count; i++) buffer.putShort((short) busStopCol[i]);
        for (int i = 0; i < count; i++) buffer.putShort((short) busOriginStopCol[i]);
        for (int i = 0; i < count; i++) buffer.putShort((short) busDestinationStopCol[i]);
        for (int i = 0; i < count; i++) buffer.putShort((short) metroLineCol[i]);
        for (int i = 0; i < count; i++) buffer.putShort((short) metroStopCol[i]);
        for (int i = 0; i < count; i++) buffer.putShort((short) hubCol[i]);

        Map<String, Object> dict = buildDict(hubs, hubKeys, hubAggs, hubIdx, busRoutesByLine, metroLineIds,
                busStopIds, metroStopIds, lineNames, routeNames, nameByFacility, coordByFacility,
                computation.busLineFlows, scale);
        Map<String, Object> summary = buildSummary(computation.droppedTracks, count, personIndexById.size(),
                dirCounts, sumSec, allSecs, hourly, histogramMin, hubAggs, pairAggs, scale);
        return new Artifacts(buffer.array(), dict, summary);
    }

    /** 流量聚合器（枢纽榜/关系榜共用）。 */
    private static final class FlowAgg {
        long flow;
        long sumSec;
        final IntArrayList secs = new IntArrayList();
        final TreeSet<Integer> metroLines = new TreeSet<>(); // 仅枢纽榜使用（事件观察口径）

        private void add(int transferSec) {
            flow++;
            sumSec += transferSec;
            secs.add(transferSec);
        }

        private int avgSec() {
            return flow == 0 ? 0 : (int) Math.round((double) sumSec / flow);
        }

        private int p90Sec() {
            int[] sorted = secs.toIntArray();
            java.util.Arrays.sort(sorted);
            return percentileNearestRank(sorted, 0.9);
        }
    }

    /** transfer-dict.json 结构（§11.3 + 契约补充 boardings/alightings）。 */
    private static Map<String, Object> buildDict(
            HubClusters hubs,
            TreeSet<String> hubKeys,
            Map<Integer, FlowAgg> hubAggs,
            Map<String, Integer> hubIdx,
            TreeMap<String, TreeSet<String>> busRoutesByLine,
            TreeSet<String> metroLineIds,
            TreeSet<String> busStopIds,
            TreeSet<String> metroStopIds,
            Map<String, String> lineNames,
            Map<String, String> routeNames,
            Map<String, String> nameByFacility,
            Map<String, double[]> coordByFacility,
            Map<String, long[]> busLineFlows,
            double scale
    ) {
        List<Map<String, Object>> hubPayloads = new ArrayList<>(hubKeys.size());
        for (String hubKey : hubKeys) {
            Hub hub = hubs.hub(hubKey);
            FlowAgg agg = hubAggs.get(hubIdx.get(hubKey));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("hubKey", hub.hubKey);
            payload.put("name", hub.name);
            payload.put("x", round2(hub.x));
            payload.put("y", round2(hub.y));
            // 事件观察口径：该枢纽发生过换乘的地铁线（metroLines 字典索引）
            payload.put("metroLines", agg == null ? List.of() : new ArrayList<>(agg.metroLines));
            payload.put("members", hub.members.stream()
                    .map(memberId -> Objects.requireNonNull(metroStopIdxOf(memberId, metroStopIds)))
                    .toList());
            hubPayloads.add(payload);
        }

        List<Map<String, Object>> busLinePayloads = new ArrayList<>(busRoutesByLine.size());
        for (Map.Entry<String, TreeSet<String>> entry : busRoutesByLine.entrySet()) {
            String lineId = entry.getKey();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("lineId", lineId);
            payload.put("name", nonBlank(lineNames.get(lineId), lineId));
            // 契约补充：全线全日上/下车人次（模型原始量，仅 bus 制式；§6.3 接驳率分母）
            long[] flows = busLineFlows.getOrDefault(lineId, new long[2]);
            payload.put("boardings", flows[0]);
            payload.put("alightings", flows[1]);
            payload.put("routes", entry.getValue().stream().map(routeId -> {
                Map<String, Object> route = new LinkedHashMap<String, Object>();
                route.put("routeId", routeId);
                route.put("name", nonBlank(routeNames.get(routeKey(lineId, routeId)), routeId));
                return route;
            }).toList());
            busLinePayloads.add(payload);
        }

        List<Map<String, Object>> metroLinePayloads = metroLineIds.stream().map(lineId -> {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            payload.put("lineId", lineId);
            payload.put("name", nonBlank(lineNames.get(lineId), lineId));
            return payload;
        }).toList();

        List<Map<String, Object>> busStopPayloads = busStopIds.stream().map(facilityId ->
                stopPayload(facilityId, nameByFacility, coordByFacility, null)).toList();
        List<Map<String, Object>> metroStopPayloads = metroStopIds.stream().map(facilityId ->
                stopPayload(facilityId, nameByFacility, coordByFacility,
                        hubIdx.get(hubs.hubKeyOf(facilityId)))).toList();

        Map<String, Object> dict = new LinkedHashMap<>();
        dict.put("version", TRANSFER_CACHE_VERSION);
        dict.put("params", paramsPayload());
        dict.put("scale", 1.0);
        dict.put("quantityPolicy", "model-original");
        dict.put("hubs", hubPayloads);
        dict.put("busLines", busLinePayloads);
        dict.put("metroLines", metroLinePayloads);
        dict.put("busStops", busStopPayloads);
        dict.put("metroStops", metroStopPayloads);
        return dict;
    }

    /** metroStops 为 TreeSet：索引 = headSet 大小；仅 buildDict 内部用（成员站必在集合内）。 */
    private static Integer metroStopIdxOf(String facilityId, TreeSet<String> metroStopIds) {
        return metroStopIds.contains(facilityId) ? metroStopIds.headSet(facilityId).size() : null;
    }

    private static Map<String, Object> stopPayload(
            String facilityId,
            Map<String, String> nameByFacility,
            Map<String, double[]> coordByFacility,
            Integer hubIndex
    ) {
        double[] coord = coordByFacility.get(facilityId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("facilityId", facilityId);
        payload.put("name", nonBlank(nameByFacility.get(facilityId), facilityId));
        payload.put("x", coord == null ? 0.0 : round2(coord[0]));
        payload.put("y", coord == null ? 0.0 : round2(coord[1]));
        if (hubIndex != null) {
            payload.put("hub", hubIndex);
        }
        return payload;
    }

    /** transfer-summary.json 结构（§11.1 v2 版；数量为模型原始值）。 */
    private static Map<String, Object> buildSummary(
            long droppedTracks,
            int count,
            int persons,
            long[] dirCounts,
            long sumSec,
            int[] allSecs,
            int[][] hourly,
            int[] histogramMin,
            Map<Integer, FlowAgg> hubAggs,
            Map<Long, FlowAgg> pairAggs,
            double scale
    ) {
        int[] sortedSecs = allSecs.clone();
        java.util.Arrays.sort(sortedSecs);

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("events", count);
        totals.put("persons", persons); // 全网去重；筛选态人数由前端 Worker 按 personIndex 重算
        totals.put("busToMetro", dirCounts[DIR_BUS_TO_METRO]);
        totals.put("metroToBus", dirCounts[DIR_METRO_TO_BUS]);
        totals.put("avgSec", count == 0 ? 0 : (int) Math.round((double) sumSec / count));
        totals.put("p50Sec", percentileNearestRank(sortedSecs, 0.5));
        totals.put("p90Sec", percentileNearestRank(sortedSecs, 0.9));

        // Top 20 枢纽：flow 降序，平序按字典索引升序（可复现）
        List<Map<String, Object>> topHubs = hubAggs.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Integer, FlowAgg>>comparingLong(e -> -e.getValue().flow)
                        .thenComparingInt(Map.Entry::getKey))
                .limit(TOP_LIMIT)
                .map(e -> {
                    Map<String, Object> payload = new LinkedHashMap<String, Object>();
                    payload.put("hub", e.getKey());
                    payload.put("flow", e.getValue().flow);
                    payload.put("avgSec", e.getValue().avgSec());
                    payload.put("p90Sec", e.getValue().p90Sec());
                    return payload;
                }).toList();

        List<Map<String, Object>> topPairs = pairAggs.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Long, FlowAgg>>comparingLong(e -> -e.getValue().flow)
                        .thenComparingLong(Map.Entry::getKey))
                .limit(TOP_LIMIT)
                .map(e -> {
                    Map<String, Object> payload = new LinkedHashMap<String, Object>();
                    payload.put("busLine", (int) (e.getKey() >> 32));
                    payload.put("metroLine", (int) (e.getKey() & 0xFFFFFFFFL));
                    payload.put("flow", e.getValue().flow);
                    payload.put("avgSec", e.getValue().avgSec());
                    return payload;
                }).toList();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "ready");
        summary.put("version", TRANSFER_CACHE_VERSION);
        summary.put("params", paramsPayload());
        summary.put("scale", 1.0);
        summary.put("quantityPolicy", "model-original");
        summary.put("droppedTracks", droppedTracks);
        summary.put("totals", totals);
        Map<String, Object> hourlyPayload = new LinkedHashMap<>();
        hourlyPayload.put("busToMetro", hourly[DIR_BUS_TO_METRO]);
        hourlyPayload.put("metroToBus", hourly[DIR_METRO_TO_BUS]);
        summary.put("hourly", hourlyPayload);
        summary.put("histogramMin", histogramMin);
        summary.put("topHubs", topHubs);
        summary.put("topPairs", topPairs);
        summary.put("generatedAt", System.currentTimeMillis());
        return summary;
    }

    /** 生成参数披露（summary 与 dict 共用；识别口径推定声明见 §3.1/§13.7）。 */
    private static Map<String, Object> paramsPayload() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("windowSec", TRANSFER_WINDOW_SECONDS);
        params.put("maxDistM", (int) TRANSFER_MAX_DIST_M);
        params.put("tramAsRail", TRAM_AS_RAIL);
        return params;
    }

    /** 稳定 ID 集合 → 字典索引（迭代序=字典序），并做 u16 上限守卫（§11.2）。 */
    private static Map<String, Integer> indexOf(Collection<String> sortedIds, String dictName) {
        if (sortedIds.size() > U16_MAX) {
            throw new IllegalStateException(
                    "换乘字典 " + dictName + " 条目数 " + sortedIds.size() + " 超出 u16 上限 " + U16_MAX);
        }
        Map<String, Integer> index = new LinkedHashMap<>(sortedIds.size() * 2);
        for (String id : sortedIds) {
            index.put(id, index.size());
        }
        return index;
    }

    /** 最近秩法分位数：rank = ceil(q·n)（1-based），n=0 返回 0；输入须升序。 */
    static int percentileNearestRank(int[] sortedAsc, double q) {
        if (sortedAsc.length == 0) {
            return 0;
        }
        int rank = (int) Math.ceil(q * sortedAsc.length);
        return sortedAsc[Math.max(0, Math.min(sortedAsc.length, rank) - 1)];
    }

    // ===================================================================================
    // manifest 与文件读写（模式照 MatsimStationPanelCache）
    // ===================================================================================

    private static Map<String, Object> manifest(MatsimData data, boolean ready) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", ready ? "ready" : "failed");
        result.put("cacheVersion", TRANSFER_CACHE_VERSION);
        result.put("generatedAt", System.currentTimeMillis());
        sourceFingerprint(data, result);
        return result;
    }

    /**
     * 源指纹：events（换乘事件的唯一数据源）+ transitSchedule（制式/坐标/聚类输入）。
     * schedule 变更会改变制式判定与枢纽聚类，仅指纹 events 不足以失效缓存。
     */
    private static void sourceFingerprint(MatsimData data, Map<String, Object> result) {
        putFileFingerprint(result, "events", data.getOutfile().getEvents());
        putFileFingerprint(result, "schedule", data.getOutfile().getTransitSchedule());
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

    private static Map<String, Object> loadCachedJson(Path path, boolean gzip) {
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
                cached = gzip ? readGzipJson(path) : JSON.readValue(path.toFile(), MAP_TYPE);
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

    private static void writeGzipJson(Path path, Map<String, Object> payload) throws Exception {
        Files.createDirectories(path.getParent());
        Path tmpPath = path.resolveSibling(path.getFileName() + ".tmp");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmpPath))) {
            JSON.writeValue(out, payload);
        }
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

    private static Map<String, Object> readGzipJson(Path path) throws Exception {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(path))) {
            return JSON.readValue(in, MAP_TYPE);
        }
    }

    private static String sha256Hex(String content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private static Path cacheDir(MatsimData data) {
        return MatsimCachePaths.versionDir(data, TRANSFER_CACHE_VERSION);
    }

    private static Path manifestPath(MatsimData data) {
        return cacheDir(data).resolve(MANIFEST_FILE);
    }

    private static Path summaryPath(MatsimData data) {
        return cacheDir(data).resolve(SUMMARY_FILE);
    }

    private static Path dictPath(MatsimData data) {
        return cacheDir(data).resolve(DICT_FILE);
    }

    private static Path eventsPath(MatsimData data) {
        return cacheDir(data).resolve(EVENTS_FILE);
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

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static String routeKey(String lineId, String routeId) {
        return nonBlank(lineId, "") + "::" + nonBlank(routeId, "");
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
