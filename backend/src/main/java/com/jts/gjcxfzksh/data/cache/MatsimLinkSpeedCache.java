package com.jts.gjcxfzksh.data.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.read.FastEventReader;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.api.experimental.events.VehicleArrivesAtFacilityEvent;
import org.matsim.core.api.experimental.events.VehicleDepartsAtFacilityEvent;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 公交链路分时车速缓存（车辆运行监测 · 路段公交车速/拥堵图层）。
 * <p>
 * 模型加载时单遍流式扫 events（{@link FastEventReader}，pigz 并行解压），只统计**公交（bus 制式）
 * 运营车辆**的链路穿越：TransitDriverStarts 按 transitLineId::transitRouteId 查 schedule 制式登记
 * 公交车辆，linkEnter→linkLeave 配对得到穿越时长，扣除期间的站点停靠
 * （VehicleArrivesAtFacility→VehicleDepartsAtFacility），按**入链时刻**折算到当日
 * {@link #BUCKET_COUNT}×{@link #BUCKET_SECONDS} 的时间桶。产出两个工件：
 * <ul>
 *   <li>{@code link-speed-summary.json}：状态 + 口径参数 + 路名字典 + 街道 district 数组；</li>
 *   <li>{@code link-speed-matrix.bin}：有向链路几何/路名/街道 + 速度/样本矩阵（PLSP 契约，
 *       见 {@link #encodeMatrix}）。</li>
 * </ul>
 * 口径契约（任何改动必须 bump {@link #LINK_SPEED_CACHE_VERSION}）：
 * <ul>
 *   <li>净行驶速度：'(出链-入链-站点停靠)'，站点停靠不算拥堵；单次穿越按
 *       'max(净时长, 长度/freespeed)' 封顶到自由流（QSim 秒级取整会让短 link 速度虚高）；</li>
 *   <li>空间平均速度：桶内 Σ长度 ÷ Σ净时长（调和口径，等权每次穿越的时间占用），
 *       非各次速度的算术平均；</li>
 *   <li>抽样平滑：输出桶 = 本桶与前后各一桶合并（滑窗 3×15min=45min，步长 15min），
 *       10% 抽样下 15 分钟班距线路每桶约 3 个样本；合并后仍无样本写 0（前端不画）；</li>
 *   <li>方向保留：链路为**有向**（不做走廊式无向合并），早晚高峰方向性是拥堵核心信息；</li>
 *   <li>制式判定复用 {@link MatsimCorridorCache#isBusTransportMode}（tram/地铁/轨道不计）；
 *       非运营车辆（社会车辆）一律不计；</li>
 *   <li>跨日时刻按 mod 24h 折回当日桶（与各缓存 hourOf 口径一致）；首末链不完整穿越
 *       （vehicleEntersTraffic 进入的链、leavesTraffic 中断的链）不计；</li>
 *   <li>路名解析链与街道归属复用走廊缓存（属性→边车表→无名；中点点面归属）。</li>
 * </ul>
 * 速度为模型仿真口径（10% 抽样时交通负荷偏轻，速度整体偏乐观），展示侧需注明。
 */
@Slf4j
public final class MatsimLinkSpeedCache {

    // v1: 首版口径：bus 运营车辆 + 净行驶速度（扣站点停靠、freespeed 封顶）+ 96×15min 桶 ±1 桶平滑。
    public static final String LINK_SPEED_CACHE_VERSION = "link-speed-v1";

    /** 时间桶：96×900s；输出值为 ±1 桶滑窗合并（45min 窗、15min 步长）。 */
    public static final int BUCKET_COUNT = 96;
    public static final int BUCKET_SECONDS = 900;
    static final int SMOOTH_RADIUS_BUCKETS = 1;
    private static final int DAY_SECONDS = BUCKET_COUNT * BUCKET_SECONDS;

    // ===== link-speed-matrix.bin 布局常量（前后端二进制契约，禁止偏离；小端）=====
    static final byte[] BIN_MAGIC = {'P', 'L', 'S', 'P'};
    static final int BIN_VERSION = 1;
    /** 头部字节数：magic(4) + version u16(2) + linkCount u32(4) + bucketCount u16(2) + bucketSeconds u16(2)。 */
    static final int BIN_HEADER_BYTES = 14;
    /** 每链路记录字节数：x1 i32 + y1 i32 + x2 i32 + y2 i32（EPSG:3857 取整）+ nameIdx u16 + street u16。 */
    static final int BIN_BYTES_PER_LINK = 20;
    /** nameIdx / street 的“无名 / 未命中街道”哨兵（u16 全 1）。 */
    static final int U16_SENTINEL = 0xFFFF;

    private static final String SUMMARY_FILE = "link-speed-summary.json";
    private static final String MATRIX_FILE = "link-speed-matrix.bin";
    private static final String MANIFEST_FILE = "manifest.json";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Map<String, Map<String, Object>> MEMORY_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                    return size() > 4;
                }
            }
    );

    private MatsimLinkSpeedCache() {
    }

    // ===================================================================================
    // 对外入口（模式照 MatsimCorridorCache）
    // ===================================================================================

    public static void prepareOnModelLoad(MatsimData data) {
        synchronized (ModelBuildLocks.lockFor("link-speed", data)) {
            ensureLinkSpeedCacheLocked(data);
        }
    }

    public static boolean isReady(MatsimData data) {
        if (!Files.exists(manifestPath(data)) || !Files.exists(summaryPath(data))
                || !Files.exists(matrixPath(data))) {
            return false;
        }
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath(data).toFile(), MAP_TYPE);
            return "ready".equals(manifest.get("status"))
                    && LINK_SPEED_CACHE_VERSION.equals(manifest.get("cacheVersion"))
                    && sameSources(data, manifest);
        } catch (Exception e) {
            log.warn("链路车速缓存状态读取失败: {}", manifestPath(data), e);
            return false;
        }
    }

    /** 口径参数 + 路名字典 + 街道 district 数组（POST /pt/linkspeed/summary）。未就绪返回 generating 态。 */
    public static Map<String, Object> readSummary(MatsimData data) {
        if (!isReady(data)) {
            return Map.of(
                    "status", "generating",
                    "cacheVersion", LINK_SPEED_CACHE_VERSION,
                    "message", "链路车速缓存正在后台生成"
            );
        }
        try {
            return loadCachedJson(summaryPath(data));
        } catch (Exception e) {
            log.warn("读取链路车速汇总缓存失败: model={}, path={}", data.getName(), summaryPath(data), e);
            return Map.of();
        }
    }

    /** 矩阵二进制字节（GET /pt/linkspeed/matrix.bin）。未就绪返回 null（Controller 侧 404）。 */
    public static byte[] readMatrixBytes(MatsimData data) {
        if (!isReady(data)) {
            return null;
        }
        try {
            return Files.readAllBytes(matrixPath(data));
        } catch (Exception e) {
            log.warn("读取链路车速矩阵失败: model={}, path={}", data.getName(), matrixPath(data), e);
            return null;
        }
    }

    /** matrix.bin 的强校验 ETag（照 MatsimCorridorCache.linksBinTag）。 */
    public static String matrixBinTag(MatsimData data) {
        if (!isReady(data)) {
            return null;
        }
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath(data).toFile(), MAP_TYPE);
            StringBuilder content = new StringBuilder(LINK_SPEED_CACHE_VERSION);
            new TreeMap<>(manifest).forEach((key, value) -> {
                if (key.endsWith("File") || key.endsWith("Modified") || key.endsWith("Size")
                        || key.startsWith("streets") || key.startsWith("roadNames")) {
                    content.append('|').append(key).append('=').append(value);
                }
            });
            return sha256Hex(content.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        } catch (Exception e) {
            log.warn("链路车速矩阵 ETag 计算失败: {}", manifestPath(data), e);
            return null;
        }
    }

    // ===================================================================================
    // 构建编排
    // ===================================================================================

    private static void ensureLinkSpeedCacheLocked(MatsimData data) {
        if (isReady(data)) {
            return;
        }
        try {
            Files.createDirectories(cacheDir(data));
            long start = System.currentTimeMillis();
            SpeedAggregator aggregator = aggregateFromEvents(data);
            Artifacts artifacts = assemble(aggregator, MatsimPopulationCache.streetIndex());
            writeBytesAtomic(matrixPath(data), artifacts.matrixBin);
            writeJsonAtomic(summaryPath(data), artifacts.summary);
            writeJsonAtomic(manifestPath(data), manifest(data, true));
            MEMORY_CACHE.remove(cacheKey(summaryPath(data)));
            log.info("链路车速缓存生成完成: model={}, busVehicles={}, links={}, traversals={}, "
                            + "dropped={}, bin={}B, 耗时={}ms",
                    data.getName(), artifacts.summary.get("busVehicles"), artifacts.summary.get("links"),
                    artifacts.summary.get("traversals"), artifacts.summary.get("droppedTraversals"),
                    artifacts.matrixBin.length, System.currentTimeMillis() - start);
        } catch (Exception e) {
            try {
                Files.createDirectories(cacheDir(data));
                writeJsonAtomic(manifestPath(data), manifest(data, false));
            } catch (Exception ignored) {
            }
            throw new RuntimeException("链路车速缓存生成失败: " + e.getMessage(), e);
        }
    }

    /** schedule 制式表 + 单遍 events 流（独立解压一次；仅公交运营车辆事件进入状态机）。 */
    private static SpeedAggregator aggregateFromEvents(MatsimData data) throws Exception {
        Network network = data.getNetwork();
        SpeedAggregator aggregator = new SpeedAggregator(linkMetaResolver(network));
        Set<String> busRouteKeys = busRouteKeys(data.getSchedule());
        String eventsFile = data.getOutfile() == null ? null : data.getOutfile().getEvents();
        if (busRouteKeys.isEmpty() || eventsFile == null || eventsFile.isBlank()) {
            return aggregator; // 无公交线路/无 events 的模型产出空工件（合法态，前端不显示图层）
        }
        FastEventReader.read(eventsFile, (eventType, time, attributes) -> {
            switch (eventType) {
                case TransitDriverStartsEvent.EVENT_TYPE -> {
                    String lineId = attributes.value(TransitDriverStartsEvent.ATTRIBUTE_TRANSIT_LINE_ID);
                    String routeId = attributes.value(TransitDriverStartsEvent.ATTRIBUTE_TRANSIT_ROUTE_ID);
                    if (lineId != null && routeId != null && busRouteKeys.contains(lineId + "::" + routeId)) {
                        aggregator.registerBusVehicle(attributes.value(TransitDriverStartsEvent.ATTRIBUTE_VEHICLE_ID));
                    }
                }
                case LinkEnterEvent.EVENT_TYPE -> aggregator.linkEnter(
                        attributes.value(LinkEnterEvent.ATTRIBUTE_VEHICLE),
                        attributes.value(LinkEnterEvent.ATTRIBUTE_LINK),
                        time);
                case LinkLeaveEvent.EVENT_TYPE -> aggregator.linkLeave(
                        attributes.value(LinkLeaveEvent.ATTRIBUTE_VEHICLE),
                        attributes.value(LinkLeaveEvent.ATTRIBUTE_LINK),
                        time);
                case VehicleArrivesAtFacilityEvent.EVENT_TYPE -> aggregator.arrivesAtFacility(
                        attributes.value(VehicleArrivesAtFacilityEvent.ATTRIBUTE_VEHICLE), time);
                case VehicleDepartsAtFacilityEvent.EVENT_TYPE -> aggregator.departsAtFacility(
                        attributes.value(VehicleDepartsAtFacilityEvent.ATTRIBUTE_VEHICLE), time);
                case VehicleLeavesTrafficEvent.EVENT_TYPE -> aggregator.leavesTraffic(
                        attributes.value(VehicleLeavesTrafficEvent.ATTRIBUTE_VEHICLE));
                default -> {
                }
            }
        });
        return aggregator;
    }

    /** bus 制式路线键集合（lineId::routeId）；制式判定复用走廊缓存。 */
    static Set<String> busRouteKeys(TransitSchedule schedule) {
        Set<String> keys = new HashSet<>();
        if (schedule == null) {
            return keys;
        }
        for (Map.Entry<Id<TransitLine>, TransitLine> lineEntry : schedule.getTransitLines().entrySet()) {
            for (Map.Entry<Id<TransitRoute>, TransitRoute> routeEntry : lineEntry.getValue().getRoutes().entrySet()) {
                if (MatsimCorridorCache.isBusTransportMode(routeEntry.getValue().getTransportMode())) {
                    keys.add(lineEntry.getKey().toString() + "::" + routeEntry.getKey().toString());
                }
            }
        }
        return keys;
    }

    /** 网络 link 元数据解析器（几何/长度/freespeed/路名），network 缺 link 返回 null（计 dropped）。 */
    private static LinkMetaResolver linkMetaResolver(Network network) {
        var sidecar = MatsimCorridorCache.roadNames();
        return linkId -> {
            if (network == null || linkId == null) {
                return null;
            }
            Link link = network.getLinks().get(Id.createLinkId(linkId));
            if (link == null || link.getFromNode() == null || link.getToNode() == null) {
                return null;
            }
            double length = link.getLength();
            double freespeed = link.getFreespeed();
            return new LinkMeta(
                    link.getFromNode().getCoord().getX(), link.getFromNode().getCoord().getY(),
                    link.getToNode().getCoord().getX(), link.getToNode().getCoord().getY(),
                    length > 0 ? length : 1.0,
                    freespeed > 0 ? freespeed : 1.0,
                    MatsimCorridorCache.resolveLinkName(link, sidecar));
        };
    }

    // ===================================================================================
    // 聚合内核（与 MATSim/事件读取解耦，可直接单测）
    // ===================================================================================

    /** 链路静态元数据。坐标 EPSG:3857，length 米，freespeed m/s。 */
    record LinkMeta(double x1, double y1, double x2, double y2,
                    double length, double freespeed, String name) {
    }

    /** 链路元数据解析器（构建时来自 network，单测可注入假表）。 */
    @FunctionalInterface
    interface LinkMetaResolver {
        LinkMeta resolve(String linkId);
    }

    /** 进行中的链路穿越（每公交车辆至多一个）。 */
    private static final class Traversal {
        String linkId;
        double enterTime;
        double dwell;
        double arriveTime = Double.NaN;
    }

    /** 每链路聚合桶：Σ净时长 + 样本数（长度恒定，速度 = 长度×n ÷ Σ时长）。 */
    static final class LinkAcc {
        final LinkMeta meta;
        final float[] timeSum = new float[BUCKET_COUNT];
        final short[] samples = new short[BUCKET_COUNT];

        LinkAcc(LinkMeta meta) {
            this.meta = meta;
        }
    }

    /**
     * 事件状态机 + 分桶聚合。事件按文件序（时间序）单线程喂入：
     * linkEnter 开启穿越，arrives/departs 累计站点停靠，linkLeave 落账，
     * leavesTraffic/链路不匹配作废当前穿越。
     */
    static final class SpeedAggregator {
        private final LinkMetaResolver metaResolver;
        private final Set<String> busVehicles = new HashSet<>();
        private final Map<String, Traversal> openTraversals = new HashMap<>();
        final Map<String, LinkAcc> byLink = new HashMap<>();
        long traversals;
        long droppedTraversals;

        SpeedAggregator(LinkMetaResolver metaResolver) {
            this.metaResolver = metaResolver;
        }

        int busVehicleCount() {
            return busVehicles.size();
        }

        void registerBusVehicle(String vehicleId) {
            if (vehicleId != null) {
                busVehicles.add(vehicleId);
            }
        }

        private boolean isBus(String vehicleId) {
            return vehicleId != null && busVehicles.contains(vehicleId);
        }

        void linkEnter(String vehicleId, String linkId, double time) {
            if (!isBus(vehicleId) || linkId == null) {
                return;
            }
            // 上一段未闭合（异常序）：作废旧穿越，重开新穿越
            Traversal traversal = openTraversals.computeIfAbsent(vehicleId, ignored -> new Traversal());
            traversal.linkId = linkId;
            traversal.enterTime = time;
            traversal.dwell = 0;
            traversal.arriveTime = Double.NaN;
        }

        void arrivesAtFacility(String vehicleId, double time) {
            if (!isBus(vehicleId)) {
                return;
            }
            Traversal traversal = openTraversals.get(vehicleId);
            if (traversal != null) {
                traversal.arriveTime = time;
            }
        }

        void departsAtFacility(String vehicleId, double time) {
            if (!isBus(vehicleId)) {
                return;
            }
            Traversal traversal = openTraversals.get(vehicleId);
            if (traversal != null && !Double.isNaN(traversal.arriveTime)) {
                traversal.dwell += Math.max(0, time - traversal.arriveTime);
                traversal.arriveTime = Double.NaN;
            }
        }

        void leavesTraffic(String vehicleId) {
            if (vehicleId != null) {
                openTraversals.remove(vehicleId); // 运营段结束，进行中穿越不完整，不计
            }
        }

        void linkLeave(String vehicleId, String linkId, double time) {
            if (!isBus(vehicleId) || linkId == null) {
                return;
            }
            Traversal traversal = openTraversals.remove(vehicleId);
            if (traversal == null) {
                return; // 首链（entersTraffic 进入）或已作废：不完整穿越不计
            }
            // 链路对不上（事件序异常）或停靠未闭合（arrive 无 depart 却出链）：作废
            if (!linkId.equals(traversal.linkId) || !Double.isNaN(traversal.arriveTime)) {
                droppedTraversals++;
                return;
            }
            LinkMeta meta = byLink.containsKey(linkId)
                    ? byLink.get(linkId).meta
                    : metaResolver.resolve(linkId);
            if (meta == null) {
                droppedTraversals++; // network 不含该 link（events 与 network 不配套）
                return;
            }
            int bucket = bucketOf(traversal.enterTime);
            if (bucket < 0) {
                droppedTraversals++;
                return;
            }
            // 净行驶时长扣站点停靠；freespeed 封顶（QSim 秒级取整让短 link 速度虚高）
            double travel = Math.max(time - traversal.enterTime - traversal.dwell, meta.length() / meta.freespeed());
            LinkAcc acc = byLink.computeIfAbsent(linkId, ignored -> new LinkAcc(meta));
            acc.timeSum[bucket] += (float) travel;
            if (acc.samples[bucket] < Short.MAX_VALUE) {
                acc.samples[bucket]++;
            }
            traversals++;
        }
    }

    /** 入链时刻 → 当日时间桶（跨日 mod 24h 折回，与各缓存 hourOf 口径一致）；非法时刻 -1。 */
    static int bucketOf(double time) {
        if (Double.isNaN(time) || Double.isInfinite(time) || time < 0) {
            return -1;
        }
        return (int) (time % DAY_SECONDS) / BUCKET_SECONDS;
    }

    // ===================================================================================
    // 组装：matrix.bin / summary.json
    // ===================================================================================

    static final class Artifacts {
        final byte[] matrixBin;
        final Map<String, Object> summary;

        private Artifacts(byte[] matrixBin, Map<String, Object> summary) {
            this.matrixBin = matrixBin;
            this.summary = summary;
        }
    }

    static Artifacts assemble(SpeedAggregator aggregator, MatsimPopulationCache.StreetIndex streets) {
        // 确定性排序（几何升序 + linkId 终键），跨构建可复现
        List<Map.Entry<String, LinkAcc>> ordered = new ArrayList<>(aggregator.byLink.entrySet());
        ordered.sort(Comparator
                .<Map.Entry<String, LinkAcc>>comparingDouble(entry -> entry.getValue().meta.x1())
                .thenComparingDouble(entry -> entry.getValue().meta.y1())
                .thenComparingDouble(entry -> entry.getValue().meta.x2())
                .thenComparingDouble(entry -> entry.getValue().meta.y2())
                .thenComparing(Map.Entry::getKey));

        TreeSet<String> usedNames = new TreeSet<>();
        for (Map.Entry<String, LinkAcc> entry : ordered) {
            String name = entry.getValue().meta.name();
            if (name != null && !name.isBlank()) {
                usedNames.add(name);
            }
        }
        if (usedNames.size() >= U16_SENTINEL) {
            throw new IllegalStateException("链路车速路名字典条目数 " + usedNames.size() + " 超出 u16 上限");
        }
        Map<String, Integer> nameIdx = new LinkedHashMap<>(usedNames.size() * 2);
        for (String name : usedNames) {
            nameIdx.put(name, nameIdx.size());
        }

        byte[] matrixBin = encodeMatrix(ordered, nameIdx, streets);

        List<String> districts = new ArrayList<>(streets == null ? 0 : streets.size());
        for (int i = 0; streets != null && i < streets.size(); i++) {
            districts.add(streets.street(i).district());
        }

        int maxSpeed = 0;
        // 概览峰值：合并窗后各链路各桶最大速度（图例封顶展示用）
        for (Map.Entry<String, LinkAcc> entry : ordered) {
            LinkAcc acc = entry.getValue();
            for (int b = 0; b < BUCKET_COUNT; b++) {
                maxSpeed = Math.max(maxSpeed, smoothedSpeedKmh(acc, b));
            }
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("vehicles", "bus-transit-only"); // 仅公交运营车辆（TransitDriverStarts 登记）
        params.put("speed", "net-travel-dwell-excluded"); // 净行驶速度：扣站点停靠，freespeed 封顶
        params.put("mean", "space-mean"); // Σ长度/Σ时长（调和口径）
        params.put("window", "3x" + BUCKET_SECONDS + "s"); // ±1 桶滑窗合并
        params.put("direction", "directed"); // 有向链路，不做无向合并
        params.put("sampling", "model-sample-not-scaled"); // 模型抽样口径，交通负荷偏轻速度偏乐观

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", "ready");
        summary.put("cacheVersion", LINK_SPEED_CACHE_VERSION);
        summary.put("generatedAt", System.currentTimeMillis());
        summary.put("params", params);
        summary.put("links", ordered.size());
        summary.put("buckets", BUCKET_COUNT);
        summary.put("bucketSeconds", BUCKET_SECONDS);
        summary.put("busVehicles", aggregator.busVehicleCount());
        summary.put("traversals", aggregator.traversals);
        summary.put("droppedTraversals", aggregator.droppedTraversals);
        summary.put("maxSpeedKmh", maxSpeed);
        summary.put("names", new ArrayList<>(usedNames));
        summary.put("districts", districts);
        return new Artifacts(matrixBin, summary);
    }

    /** ±1 桶合并后的整数 km/h（[1,255]，无样本 0）。 */
    static int smoothedSpeedKmh(LinkAcc acc, int bucket) {
        double timeSum = 0;
        long sampleSum = 0;
        for (int b = Math.max(0, bucket - SMOOTH_RADIUS_BUCKETS);
             b <= Math.min(BUCKET_COUNT - 1, bucket + SMOOTH_RADIUS_BUCKETS); b++) {
            timeSum += acc.timeSum[b];
            sampleSum += acc.samples[b];
        }
        if (sampleSum <= 0 || timeSum <= 0) {
            return 0;
        }
        double speedKmh = acc.meta.length() * sampleSum / timeSum * 3.6;
        return (int) Math.max(1, Math.min(255, Math.round(speedKmh)));
    }

    /** ±1 桶合并后的样本数（clamp 255）。 */
    static int smoothedSamples(LinkAcc acc, int bucket) {
        long sampleSum = 0;
        for (int b = Math.max(0, bucket - SMOOTH_RADIUS_BUCKETS);
             b <= Math.min(BUCKET_COUNT - 1, bucket + SMOOTH_RADIUS_BUCKETS); b++) {
            sampleSum += acc.samples[b];
        }
        return (int) Math.min(sampleSum, 255);
    }

    /**
     * link-speed-matrix.bin（小端）：
     * header = magic "PLSP" + version u16(=1) + linkCount u32 + bucketCount u16 + bucketSeconds u16（共 14B）；
     * record × linkCount（20B/链路）= x1 i32, y1 i32, x2 i32, y2 i32（EPSG:3857 取整，
     * (x1,y1)=fromNode 即行驶起点，方向语义由记录序承载）, nameIdx u16（{@link #U16_SENTINEL}=无名）,
     * street u16（中点点面归属，{@link #U16_SENTINEL}=未命中）；
     * speeds u8 × (linkCount×bucketCount)（链路主序；km/h，0=无数据，有数据下限 1）；
     * samples u8 × (linkCount×bucketCount)（合并窗样本数，clamp 255）。
     */
    static byte[] encodeMatrix(List<Map.Entry<String, LinkAcc>> ordered,
                               Map<String, Integer> nameIdx, MatsimPopulationCache.StreetIndex streets) {
        int linkCount = ordered.size();
        ByteBuffer buffer = ByteBuffer.allocate(
                        BIN_HEADER_BYTES + BIN_BYTES_PER_LINK * linkCount + 2 * linkCount * BUCKET_COUNT)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(BIN_MAGIC);
        buffer.putShort((short) BIN_VERSION);
        buffer.putInt(linkCount);
        buffer.putShort((short) BUCKET_COUNT);
        buffer.putShort((short) BUCKET_SECONDS);
        for (Map.Entry<String, LinkAcc> entry : ordered) {
            LinkMeta meta = entry.getValue().meta;
            buffer.putInt((int) Math.round(meta.x1()));
            buffer.putInt((int) Math.round(meta.y1()));
            buffer.putInt((int) Math.round(meta.x2()));
            buffer.putInt((int) Math.round(meta.y2()));
            Integer name = meta.name() == null || meta.name().isBlank() ? null : nameIdx.get(meta.name());
            buffer.putShort((short) (name == null ? U16_SENTINEL : name.intValue()));
            int street = U16_SENTINEL;
            if (streets != null) {
                int idx = streets.locate((meta.x1() + meta.x2()) / 2.0, (meta.y1() + meta.y2()) / 2.0);
                if (idx >= 0) {
                    street = idx;
                }
            }
            buffer.putShort((short) street);
        }
        for (Map.Entry<String, LinkAcc> entry : ordered) {
            LinkAcc acc = entry.getValue();
            for (int b = 0; b < BUCKET_COUNT; b++) {
                buffer.put((byte) smoothedSpeedKmh(acc, b));
            }
        }
        for (Map.Entry<String, LinkAcc> entry : ordered) {
            LinkAcc acc = entry.getValue();
            for (int b = 0; b < BUCKET_COUNT; b++) {
                buffer.put((byte) smoothedSamples(acc, b));
            }
        }
        return buffer.array();
    }

    // ===================================================================================
    // manifest 与文件读写（模式照 MatsimCorridorCache）
    // ===================================================================================

    private static Map<String, Object> manifest(MatsimData data, boolean ready) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", ready ? "ready" : "failed");
        result.put("cacheVersion", LINK_SPEED_CACHE_VERSION);
        result.put("generatedAt", System.currentTimeMillis());
        sourceFingerprint(data, result);
        return result;
    }

    /** 源指纹：events（穿越来源）+ network（几何/freespeed）+ schedule（制式）+ 街道/路名资源。 */
    private static void sourceFingerprint(MatsimData data, Map<String, Object> result) {
        putFileFingerprint(result, "events",
                data.getOutfile() == null ? null : data.getOutfile().getEvents());
        putFileFingerprint(result, "network",
                data.getOutfile() == null ? null : data.getOutfile().getNetwork());
        putFileFingerprint(result, "schedule",
                data.getOutfile() == null ? null : data.getOutfile().getTransitSchedule());
        result.put("streetsResource", MatsimPopulationCache.STREETS_RESOURCE);
        result.put("streetsSha256", MatsimPopulationCache.streetsGeojsonTag());
        result.put("roadNamesResource", MatsimCorridorCache.ROAD_NAMES_RESOURCE);
        result.put("roadNamesSha256", MatsimCorridorCache.roadNamesTag());
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
        return MatsimCachePaths.versionDir(data, LINK_SPEED_CACHE_VERSION);
    }

    private static Path manifestPath(MatsimData data) {
        return cacheDir(data).resolve(MANIFEST_FILE);
    }

    private static Path summaryPath(MatsimData data) {
        return cacheDir(data).resolve(SUMMARY_FILE);
    }

    private static Path matrixPath(MatsimData data) {
        return cacheDir(data).resolve(MATRIX_FILE);
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
