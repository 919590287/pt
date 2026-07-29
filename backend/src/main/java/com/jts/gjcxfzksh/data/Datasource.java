package com.jts.gjcxfzksh.data;

import com.jts.gjcxfzksh.api.model.pt.PTCoord;
import com.jts.gjcxfzksh.data.cache.MatsimAnalysisCache;
import com.jts.gjcxfzksh.data.cache.MatsimCorridorCache;
import com.jts.gjcxfzksh.data.cache.MatsimLinkSpeedCache;
import com.jts.gjcxfzksh.data.cache.MatsimLargeModelNetworkCache;
import com.jts.gjcxfzksh.data.cache.MatsimPlansDerivedCache;
import com.jts.gjcxfzksh.data.cache.MatsimPassengerProfileCache;
import com.jts.gjcxfzksh.data.cache.MatsimPersonTrackStore;
import com.jts.gjcxfzksh.data.cache.MatsimPopulationCache;
import com.jts.gjcxfzksh.data.cache.MatsimPrecomputedCache;
import com.jts.gjcxfzksh.data.cache.MatsimRoutePanelCache;
import com.jts.gjcxfzksh.data.cache.MatsimRouteSpatialIndex;
import com.jts.gjcxfzksh.data.cache.MatsimStationPanelCache;
import com.jts.gjcxfzksh.data.cache.MatsimTransferCache;
import com.jts.gjcxfzksh.data.cache.MatsimTripEndsCache;
import com.jts.gjcxfzksh.data.entry.Database;
import com.jts.gjcxfzksh.data.entry.MatsimOutFile;
import com.jts.gjcxfzksh.data.entry.Scheme;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.Config;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.pt.transitSchedule.TransitScheduleFactoryImpl;
import org.matsim.pt.transitSchedule.api.TransitScheduleWriter;
import org.matsim.vehicles.MatsimVehicleWriter;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.Vehicles;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class Datasource {

    private static final Map<String, Database> dataMap = new ConcurrentHashMap<>();
    // 是否加载完成
    private static final Map<String, Boolean> loadStatusMap = new ConcurrentHashMap<>();
    // 是否加载中
    private static final Map<String, Boolean> loadingStatusMap = new ConcurrentHashMap<>();
    private static final Map<String, ModelLoadStatus> statusMap = new ConcurrentHashMap<>();
    private static final Set<String> retainLoadedRequests = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> loadVersionMap = new ConcurrentHashMap<>();
    private static final Map<String, Object> lifecycleLocks = new ConcurrentHashMap<>();
    private static final ExecutorService LOAD_EXECUTOR = Executors.newFixedThreadPool(1, r -> {
        Thread thread = new Thread(r, "matsim-model-loader");
        thread.setDaemon(true);
        return thread;
    });
    // 缓存预热完成后的追加预计算钩子（如体检评估指标），由业务服务在启动时注册，
    // 避免 data 包反向依赖 api.service 实现类
    private static final java.util.concurrent.CopyOnWriteArrayList<java.util.function.Consumer<MatsimData>> cacheWarmupHooks =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void registerCacheWarmupHook(java.util.function.Consumer<MatsimData> hook) {
        if (hook != null) {
            cacheWarmupHooks.addIfAbsent(hook);
        }
    }

    public static void unregisterCacheWarmupHook(java.util.function.Consumer<MatsimData> hook) {
        if (hook != null) {
            cacheWarmupHooks.remove(hook);
        }
    }

    private static void runCacheWarmupHooks(MatsimData data) {
        for (java.util.function.Consumer<MatsimData> hook : cacheWarmupHooks) {
            try {
                hook.accept(data);
            } catch (Exception e) {
                log.warn("缓存预热钩子执行失败: model={}, error={}", data.getName(), e.getMessage());
            }
        }
    }

    public static Database data(String name) {
        synchronized (lifecycleLock(name)) {
            Database data = dataMap.get(name);
            if (data == null) {
                log.error("数据[{}]未加载", name);
                throw new RuntimeException("数据[" + name + "]未加载");
            }
            // 记录真实业务读取，供运行状态和诊断使用。模型只能由手动卸载接口释放。
            retainLoadedRequests.add(name);
            data.matsim_data().setLastRequestTime(System.currentTimeMillis());
            return data;
        }
    }

    /**
     * 无副作用读取已加载模型，仅供清理/诊断任务使用；不会刷新最后请求时间。
     */
    public static Database peek(String name) {
        return dataMap.get(name);
    }

    /**
     * 获取是否加载完成
     *
     * @param name 方案
     * @return status
     */
    public static boolean loadStatus(String name) {
        Boolean b = loadStatusMap.get(name);
        if (b == null) {
            return false;
        }
        return b;
    }

    /**
     * 获取是否加载中
     *
     * @param name 方案
     * @return status
     */
    public static boolean loadingStatus(String name) {
        Boolean b = loadingStatusMap.get(name);
        if (b == null) {
            return false;
        }
        return b;
    }

    public static ModelLoadStatus loadStatusDetail(String name) {
        ModelLoadStatus status = statusMap.get(name);
        if (status == null) {
            ModelLoadStatus unloaded = ModelLoadStatus.unloaded();
            unloaded.setLoaded(loadStatus(name));
            unloaded.setLoading(loadingStatus(name));
            if (unloaded.isLoaded()) {
                unloaded.setStage("ready");
                unloaded.setMessage("模型已加载");
            }
            return unloaded;
        }
        return status.copy();
    }

    public static void remove(String name) {
        synchronized (lifecycleLock(name)) {
            // 仅供测试和内部注册表维护使用；生产卸载统一走 unload。
            loadVersionMap.merge(name, 1L, Long::sum);
            dataMap.remove(name);
            loadStatusMap.remove(name);
            statusMap.remove(name);
            retainLoadedRequests.remove(name);
        }
    }

    public static void unload(String name) {
        synchronized (lifecycleLock(name)) {
            retainLoadedRequests.remove(name);
            loadVersionMap.merge(name, 1L, Long::sum);
            dataMap.remove(name);
            loadStatusMap.remove(name);
            // 解析器不可中途强停；保留 loading 标记直到旧任务真正退出，防止同一模型并发重复解析。
            boolean canceling = loadingStatus(name);
            ModelLoadStatus status = setStatus(name, canceling ? "canceling" : "unloaded",
                    canceling ? "正在取消模型加载" : "模型已卸载", false, canceling);
            status.resetProgress(canceling ? "等待当前解析安全退出" : "模型已卸载");
        }
    }

    public static void loadAsync(Scheme scheme) {
        String name = scheme.getName();
        retainLoadedRequests.add(name);
        if (loadStatus(name)) {
            setStatus(name, "ready", "模型已加载", true, false);
            return;
        }
        Boolean previous = loadingStatusMap.putIfAbsent(name, true);
        if (Boolean.TRUE.equals(previous)) {
            return;
        }
        long loadVersion = currentLoadVersion(name);
        ModelLoadStatus queued = setStatus(name, "queued", "模型加载已进入后台队列", false, true);
        queued.resetProgress("等待后台加载队列");
        queued.setProgressPercent(2);
        LOAD_EXECUTOR.submit(() -> {
            try {
                load(scheme, loadVersion, true);
            } catch (Throwable e) {
                log.error("后台加载模型失败: model={}, error={}", name, e.getMessage(), e);
            }
        });
    }

    public static boolean retainLoadedRequested(String name) {
        return retainLoadedRequests.contains(name);
    }

    public static void load(Scheme scheme) {
        loadSynchronously(scheme);
    }

    public static void loadForCache(Scheme scheme) {
        loadSynchronously(scheme);
    }

    private static void loadSynchronously(Scheme scheme) {
        String name = scheme.getName();
        while (!loadStatus(name)) {
            if (loadingStatusMap.putIfAbsent(name, true) == null) {
                load(scheme, currentLoadVersion(name), true);
                return;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("等待模型加载被中断", e);
            }
        }
    }

    /**
     * 模型加载版本号：unload/重载时递增。
     * 供派生数据缓存（如 full.bin 二进制线网）做版本键，避免模型重载后继续下发陈旧字节。
     */
    public static long currentLoadVersion(String name) {
        return loadVersionMap.getOrDefault(name, 0L);
    }

    private static boolean isStaleLoad(String name, long expectedVersion) {
        return currentLoadVersion(name) != expectedVersion;
    }

    private static void load(Scheme scheme, long expectedVersion, boolean cancelable) {
        String name = scheme.getName();
        if (cancelable && isStaleLoad(name, expectedVersion)) {
            log.info("跳过已取消的模型加载: model={}", name);
            return;
        }
        loadingStatusMap.put(scheme.getName(), true);
        long startTime = System.currentTimeMillis();
        long estimatedTotalMs = expectedLoadMs(scheme);
        ModelLoadStatus status = setStatus(scheme.getName(), "loading_config", "正在加载基础路网和公交模型", false, true);
        status.resetProgress("正在准备加载");
        status.setStartedAt(startTime);
        status.setEstimatedTotalMs(estimatedTotalMs);
        try {
            MatsimData data = new MatsimData(scheme.getName(), scheme.getOutput(), scheme.getCache(), scheme.isLargeModel());
            data.setArea(scheme.getDesc().getArea());
            // 数量严格按当前模型文件原样计算，不对人口/客流做任何扩样。
            // setScale 保留兼容旧 desc.json，但 MatsimData 会强制归一为 1.0。
            data.setScale(1.0);
            // 加载。loadConfig 内部是一次性阻塞读取，进度按预计总时长在 3%→85% 区间做时间插值，
            // 预计总时长优先取上次成功加载的真实耗时（load-stats.json），首次按 output 体量估算。
            status.beginPhase(3, 82, Math.round(estimatedTotalMs * 0.85), "正在加载路网、公交与出行链数据");
            loadConfig(data);
            // 基础模型就绪时只建立轻量空间索引。大型面板/乘客明细按需读取，
            // 避免“后续加载”仍主动解压数十 MB JSON/TSV 并长期占用堆。
            status.beginPhase(85, 12, Math.round(estimatedTotalMs * 0.15), "正在构建线路空间索引");
            MatsimRouteSpatialIndex.prepareOnModelLoad(data);
            long endTime = System.currentTimeMillis();
            log.info("加载[{}]耗时: {}ms", scheme.getName(), endTime - startTime);
            // 即使模型加载后尚未收到业务请求，也应从加载完成时开始计算空闲时间。
            data.setLastRequestTime(endTime);
            synchronized (lifecycleLock(name)) {
                if (isStaleLoad(name, expectedVersion)) {
                    log.info("模型加载完成但请求已取消，不写入内存: model={}", name);
                    return;
                }
                dataMap.put(scheme.getName(), new Database(data));
                loadStatusMap.put(scheme.getName(), true);
            }
            status = setStatus(scheme.getName(), "ready", "模型基础数据已加载，缓存将在后台生成", true, false);
            status.setStartedAt(startTime);
            status.setFinishedAt(endTime);
            status.setProgressPercent(100);
            status.setProgressMessage("模型基础数据已加载");
            status.setEtaSeconds(0);
            status.setElapsedSeconds(Math.max(0, (endTime - startTime) / 1000));
            writeLoadStats(scheme, endTime - startTime);
        } catch (RuntimeException e) {
            if (!cancelable || !isStaleLoad(name, expectedVersion)) {
                loadStatusMap.put(scheme.getName(), false);
                status = setStatus(scheme.getName(), "failed", e.getMessage() == null ? "模型加载失败" : e.getMessage(), false, false);
                status.setStartedAt(startTime);
                status.setFinishedAt(System.currentTimeMillis());
                status.setProgressMessage(status.getMessage());
                status.setEtaSeconds(-1);
            }
            throw e;
        } finally {
            boolean stale = isStaleLoad(name, expectedVersion);
            loadingStatusMap.remove(scheme.getName(), true);
            // 用户在旧任务取消期间重新点击加载：旧任务退出后自动提交当前代际，避免请求丢失。
            if (stale && retainLoadedRequests.contains(name) && !loadStatus(name)) {
                loadAsync(scheme);
            }
        }
    }

    private static Object lifecycleLock(String name) {
        return lifecycleLocks.computeIfAbsent(name, ignored -> new Object());
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper LOAD_STATS_JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static java.nio.file.Path loadStatsPath(Scheme scheme) {
        return java.nio.file.Path.of(scheme.getCache(), "load-stats.json");
    }

    /**
     * 预计加载总耗时：优先取上次成功加载的真实耗时；首次加载按 output 关键文件体量估算
     * （经验吞吐约 15MB/s），并夹在 [20s, 2h]。
     */
    private static long expectedLoadMs(Scheme scheme) {
        try {
            java.nio.file.Path path = loadStatsPath(scheme);
            if (java.nio.file.Files.exists(path)) {
                Map<String, Object> stats = LOAD_STATS_JSON.readValue(path.toFile(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                Object last = stats.get("lastLoadMs");
                if (last instanceof Number number && number.longValue() > 1000) {
                    return number.longValue();
                }
            }
        } catch (Exception e) {
            log.debug("读取 load-stats.json 失败: model={}, error={}", scheme.getName(), e.getMessage());
        }
        // 大模型基础加载会跳过 plans/events，不能再用整个 30GB output 估算成几十分钟。
        long bytes = scheme.isLargeModel() ? largeModelBaseInputBytes(scheme) : scheme.getOutputBytes();
        bytes = Math.max(bytes, 32L * 1024 * 1024);
        long ms = bytes / (15L * 1024 * 1024) * 1000;
        return scheme.isLargeModel()
                ? Math.max(5_000L, Math.min(ms, 5L * 60 * 1000))
                : Math.max(20_000L, Math.min(ms, 2L * 3600 * 1000));
    }

    private static long largeModelBaseInputBytes(Scheme scheme) {
        java.io.File[] files = new java.io.File(scheme.getOutput()).listFiles(file -> file.isFile()
                && !file.getName().startsWith(".") && !file.getName().startsWith("._"));
        if (files == null) return 32L * 1024 * 1024;
        long total = 0L;
        for (java.io.File file : files) {
            String lower = file.getName().toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("network") || lower.contains("transitschedule")
                    || lower.contains("transitvehicles") || lower.contains("config")) {
                total = total > Long.MAX_VALUE - file.length() ? Long.MAX_VALUE : total + file.length();
            }
        }
        return total;
    }

    private static void writeLoadStats(Scheme scheme, long loadMs) {
        try {
            java.nio.file.Path path = loadStatsPath(scheme);
            java.nio.file.Files.createDirectories(path.getParent());
            Map<String, Object> stats = new java.util.LinkedHashMap<>();
            stats.put("lastLoadMs", loadMs);
            stats.put("recordedAt", System.currentTimeMillis());
            LOAD_STATS_JSON.writeValue(path.toFile(), stats);
        } catch (Exception e) {
            log.debug("写入 load-stats.json 失败: model={}, error={}", scheme.getName(), e.getMessage());
        }
    }

    public static void buildCaches(String name) {
        buildCaches(name, null);
    }

    public static void buildCaches(String name, MatsimAnalysisCache.BuildProgress progress) {
        Database database = dataMap.get(name);
        if (database == null) {
            throw new RuntimeException("数据[" + name + "]未加载");
        }
        loadEvent(database.matsim_data(), progress);
    }

    public interface CacheBuildProgress {
        void update(int percent, String phaseMessage);
    }

    public static void buildCachesWithProgress(String name, CacheBuildProgress progress) {
        Database database = dataMap.get(name);
        if (database == null) {
            throw new RuntimeException("数据[" + name + "]未加载");
        }
        loadEvent(database.matsim_data(), progress);
    }

    private static ModelLoadStatus setStatus(String name, String stage, String message, boolean loaded, boolean loading) {
        ModelLoadStatus status = statusMap.computeIfAbsent(name, ignored -> new ModelLoadStatus());
        status.setStage(stage);
        status.setMessage(message);
        status.setLoaded(loaded);
        status.setLoading(loading);
        return status;
    }

    private static void loadEvent(MatsimData data) {
        loadEvent(data, (MatsimAnalysisCache.BuildProgress) null);
    }

    private static void loadEvent(MatsimData data, MatsimAnalysisCache.BuildProgress progress) {
        loadEvent(data, progress, null);
    }

    private static void loadEvent(MatsimData data, CacheBuildProgress progress) {
        MatsimAnalysisCache.BuildProgress trajectoryProgress = progress == null ? null : (time, vehicles) -> {
            int percent = 10 + (int) Math.round(Math.min(1.0, Math.max(0.0, time / 86_400.0)) * 32.0);
            progress.update(percent, "events：已处理到 " + formatEventTime(time) + "，车辆约 " + vehicles + " 台");
        };
        loadEvent(data, trajectoryProgress, progress);
    }

    private static void loadEvent(MatsimData data, MatsimAnalysisCache.BuildProgress trajectoryProgress,
                                  CacheBuildProgress phaseProgress) {
        try {
            phase(phaseProgress, 10, "events：生成轨迹和乘客磁盘工件");
            MatsimAnalysisCache.prepareAllOnModelLoad(data, trajectoryProgress);
            if (data.isLargeModel() && (data.getPersonTracks() == null || data.getPersonTracks().isEmpty())) {
                // 真正的全冷启动链路：events 先生成磁盘轨迹和按人分区，后续缓存逐分区聚合；
                // plans 仍只流式扫描一次。整个流程不物化 2000 万条 personTracks。
                phase(phaseProgress, 45, "乘客轨迹：建立低内存按人分区");
                MatsimPersonTrackStore.preparePartitions(data);
                phase(phaseProgress, 50, "线路面板：聚合线路客流与 OD");
                MatsimRoutePanelCache.prepareOnModelLoad(data, (completed, total) ->
                        routePanelProgress(phaseProgress, completed, total));
                phase(phaseProgress, 58, "换乘分析：识别换乘事件");
                MatsimTransferCache.prepareOnModelLoad(data);
                phase(phaseProgress, 65, "plans：流式扫描人口、出行端点与画像");
                MatsimPlansDerivedCache.prepareAllOnModelLoad(data, persons ->
                        phase(phaseProgress, 65, "plans：已扫描 " + persons + " 人"));
                phase(phaseProgress, 80, "走廊分析：聚合站间断面客流");
                MatsimCorridorCache.prepareOnModelLoad(data);
                phase(phaseProgress, 85, "链路速度：流式聚合 events");
                MatsimLinkSpeedCache.prepareOnModelLoad(data, (events, time) ->
                        linkSpeedProgress(phaseProgress, events, time));
                phase(phaseProgress, 90, "站点面板：聚合站点客流与 OD");
                MatsimStationPanelCache.prepareOnModelLoad(data);
                phase(phaseProgress, 94, "可视化：生成完整道路与公交瓦片");
                MatsimPrecomputedCache.prepareOnModelLoad(data);
                phase(phaseProgress, 97, "索引：构建线路空间索引");
                MatsimRouteSpatialIndex.prepareOnModelLoad(data);
                runCacheWarmupHooks(data);
                return;
            }
            phase(phaseProgress, 50, "线路面板：聚合线路客流与 OD");
            MatsimRoutePanelCache.prepareOnModelLoad(data, (completed, total) ->
                    routePanelProgress(phaseProgress, completed, total));
            // 换乘分析缓存（transfer-v1）：只依赖 personTracks + schedule，排在 routePanel 之后（设计文档 §9.1）。
            // 不带 progress 的 loadEvent 重载委托到本重载，两条调用链同样覆盖。
            phase(phaseProgress, 58, "换乘分析：识别换乘事件");
            MatsimTransferCache.prepareOnModelLoad(data);
            // population + tripends 的活动端点同源于 plans：共享一次流式扫描，
            // 按 MATSIM_PROCESSING_THREADS 有界并行聚合；tripends OD 仍在扫描后使用 personTracks + schedule。
            phase(phaseProgress, 65, "plans：聚合人口、出行端点与画像");
            MatsimPlansDerivedCache.prepareAllOnModelLoad(data, persons ->
                    phase(phaseProgress, 65, "plans：已处理 " + persons + " 人"));
            // 走廊缓存（corridor-v1）：只依赖 schedule + network + 内嵌资源（街道面/路名边车表）。
            phase(phaseProgress, 80, "走廊分析：聚合站间断面客流");
            MatsimCorridorCache.prepareOnModelLoad(data);
            // 链路车速缓存（link-speed-v1）：独立单遍流式扫 events（不依赖 personTracks），
            // 首建约一次 events 解压/解析成本，之后 manifest 指纹命中即跳过。
            phase(phaseProgress, 85, "链路速度：流式聚合 events");
            MatsimLinkSpeedCache.prepareOnModelLoad(data, (events, time) ->
                    linkSpeedProgress(phaseProgress, events, time));
            phase(phaseProgress, 90, "站点面板：聚合站点客流与 OD");
            MatsimStationPanelCache.prepareOnModelLoad(data);
            phase(phaseProgress, 94, "可视化：生成网络与公交瓦片");
            MatsimPrecomputedCache.prepareOnModelLoad(data);
            phase(phaseProgress, 97, "索引：构建线路空间索引");
            MatsimRouteSpatialIndex.prepareOnModelLoad(data);
            // personTracks 此时已就绪（小模型来自 events 解析，大模型来自轨迹缓存），
            // 在同一后台线程里完成体检评估等追加预计算，前端进页面即可直接命中
            runCacheWarmupHooks(data);
        } catch (Exception e) {
            log.error("event加载失败: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private static void phase(CacheBuildProgress progress, int percent, String message) {
        if (progress != null) progress.update(percent, message);
    }

    private static void routePanelProgress(CacheBuildProgress progress, int completed, int total) {
        int safeTotal = Math.max(1, total);
        int percent = 50 + Math.min(7, (int) Math.floor(completed * 7.0 / safeTotal));
        phase(progress, percent, "线路面板：已处理乘客分区 " + completed + "/" + safeTotal);
    }

    private static void linkSpeedProgress(CacheBuildProgress progress, long events, double eventTime) {
        int percent = 85 + Math.min(4, (int) Math.floor(Math.max(0.0, eventTime) * 4.0 / 86_400.0));
        phase(progress, percent, "链路速度：已扫描 " + String.format(java.util.Locale.ROOT, "%,d", events)
                + " 条事件，仿真时刻 " + formatEventTime((int) Math.max(0, eventTime)));
    }

    private static String formatEventTime(int seconds) {
        int safe = Math.max(0, seconds);
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d",
                safe / 3600, (safe % 3600) / 60, safe % 60);
    }

    private static void loadConfig(MatsimData data) {
        MatsimOutFile outfile = data.getOutfile();
        Config cfg = outfile.loadConfig();
        cfg.network().setInputFile(data.isLargeModel()
                ? MatsimLargeModelNetworkCache.resolveNetworkInput(data)
                : outfile.getNetwork());
        if (data.isLargeModel()) {
            cfg.plans().setInputFile(null);
            cfg.facilities().setInputFile(null);
            cfg.vehicles().setVehiclesFile(null);
            int runtimeThreads = Math.max(1, ModelProcessingPool.parallelism());
            if (cfg.global().getNumberOfThreads() > runtimeThreads) {
                cfg.global().setNumberOfThreads(runtimeThreads);
            }
            log.info("模型[{}]进入大模型轻量加载模式，跳过 plans/facilities/vehicles eager 读取", data.getName());
        } else {
            cfg.plans().setInputFile(outfile.getPlans());
        }
        if (outfile.getTransitSchedule() == null) { // 如果没有TRANSIT_SCHEDULE创建一个空的
            String fileName = data.getCacheFolder() + "/generated-inputs/" + MatsimOutFile.OutFile.TRANSIT_SCHEDULE + ".xml.gz";
            try {
                java.nio.file.Files.createDirectories(java.nio.file.Path.of(fileName).getParent());
            } catch (Exception e) {
                throw new RuntimeException("创建缓存输入目录失败", e);
            }
            TransitScheduleWriter tsw = new TransitScheduleWriter(new TransitScheduleFactoryImpl().createTransitSchedule());
            tsw.writeFile(fileName);
            outfile.setTransitSchedule(fileName);
        }
        if (outfile.getTransitVehicles() == null) { // 如果没有TRANSIT_VEHICLES创建一个空的
            String fileName = data.getCacheFolder() + "/generated-inputs/" + MatsimOutFile.OutFile.TRANSIT_VEHICLES + ".xml.gz";
            try {
                java.nio.file.Files.createDirectories(java.nio.file.Path.of(fileName).getParent());
            } catch (Exception e) {
                throw new RuntimeException("创建缓存输入目录失败", e);
            }
            Vehicles vehicles = VehicleUtils.createVehiclesContainer();
            VehicleType bus = VehicleUtils.createVehicleType(Id.create("car", VehicleType.class));
            bus.getCapacity().setSeats(70);
            vehicles.addVehicleType(bus);
            MatsimVehicleWriter mvw = new MatsimVehicleWriter(vehicles);
            mvw.writeFile(fileName);
            outfile.setTransitVehicles(fileName);
        }
        cfg.getModules().get("transit").addParam("transitScheduleFile", outfile.getTransitSchedule());
        cfg.getModules().get("transit").addParam("vehiclesFile", outfile.getTransitVehicles());
        if (!data.isLargeModel() && outfile.getFacilities() != null) {
            cfg.getModules().get("facilities").addParam("inputFacilitiesFile", outfile.getFacilities());
        }
        if (!data.isLargeModel() && outfile.getVehicles() != null) {
            cfg.getModules().get("vehicles").addParam("vehiclesFile", outfile.getVehicles());
        }
        // 如果typicalDuration == 0 设置为1小时
        cfg.scoring().getActivityParams().forEach(activityParam -> {
            if (activityParam.getTypicalDuration().isUndefined()) {
                activityParam.setTypicalDuration(3600.);
            }
        });

        MutableScenario scenario = (MutableScenario) ScenarioUtils.loadScenario(cfg);
        data.config = cfg;
        data.scenario = scenario;

        // 路网坐标转换
        String globalCRS = cfg.global().getCoordinateSystem();
        CoordinateTransformation ctf;

        // 路网
        String inputCRS = cfg.network().getInputCRS();
        String networkCRS = (String) data.getNetwork().getAttributes().getAttribute("coordinateReferenceSystem");
        ctf = ctf(globalCRS, inputCRS, networkCRS);
        CoordinateTransformation nodectf = ctf;
        AtomicLong networkTransformFailures = new AtomicLong();
        ModelProcessingPool.forEach(data.getNetwork().getNodes().values(), node -> {
            Coord nodeCoord = node.getCoord();
            try {
                var transformedCoord = new Coord(nodeCoord.getX(), nodeCoord.getY(), nodeCoord.hasZ() ? nodeCoord.getZ() : 0);
                if (nodectf != null) {
                    transformedCoord = nodectf.transform(transformedCoord);
                }
                if (transformedCoord == null || !Double.isFinite(transformedCoord.getX())
                        || !Double.isFinite(transformedCoord.getY())) {
                    throw new IllegalArgumentException("坐标转换返回非有限值");
                }
                node.setCoord(transformedCoord);
            } catch (Exception e) {
                networkTransformFailures.incrementAndGet();
            }
        });
        if (networkTransformFailures.get() > 0) {
            throw new IllegalStateException("network.node 坐标转换失败: " + networkTransformFailures.get()
                    + " 个；拒绝发布混合坐标系模型");
        }
        if (networkCRS != null || inputCRS != null || globalCRS != null) {
            data.getNetwork().getAttributes().putAttribute("coordinateReferenceSystem", "EPSG:3857");
        }


        // 路网中心点。不能把 max 初始化为 0，否则全负坐标模型会被错误扩到本初子午线/赤道。
        NetworkBounds bounds = networkBounds(data.getNetwork().getNodes().values());
        data.center = new Coord((bounds.maxX() + bounds.minX()) / 2, (bounds.maxY() + bounds.minY()) / 2);
        data.range[0] = new PTCoord(bounds.maxX(), bounds.maxY());
        data.range[1] = new PTCoord(bounds.minX(), bounds.minY());

        // 公交
        inputCRS = data.config.transit().getInputScheduleCRS();
        String tfCRS = (String) data.getSchedule().getAttributes().getAttribute("coordinateReferenceSystem");
        ctf = ctf(globalCRS, inputCRS, tfCRS);
        if (ctf != null) {
            long failures = transformScheduleCoordinates(data.getSchedule(), ctf);
            if (failures > 0) {
                log.warn("transitSchedule.facility 坐标转换失败点已置空: model={}, count={}",
                        data.getName(), failures);
            }
        } else {
            data.getSchedule().getAttributes().putAttribute("coordinateTransformFailures", 0L);
        }
        if (tfCRS != null || inputCRS != null || globalCRS != null) {
            data.getSchedule().getAttributes().putAttribute("coordinateReferenceSystem", "EPSG:3857");
        }

        // plans。原始 output 必须保持只读，加载阶段不再回写 plans 文件。
        if (!data.isLargeModel() && data.getPopulation() != null) {
            inputCRS = cfg.plans().getInputCRS();
            String planCRS = (String) data.getPopulation().getAttributes().getAttribute("coordinateReferenceSystem");
            ctf = ctf(globalCRS, inputCRS, planCRS);
            if (ctf != null) {
                long failures = transformPopulationCoordinates(data.getPopulation(), ctf);
                if (failures > 0) {
                    log.warn("plan.activity 坐标转换失败点已置空: model={}, count={}", data.getName(), failures);
                }
            } else {
                data.getPopulation().getAttributes().putAttribute("coordinateTransformFailures", 0L);
            }
            if (planCRS != null || inputCRS != null || globalCRS != null) {
                data.getPopulation().getAttributes().putAttribute("coordinateReferenceSystem", "EPSG:3857");
            }
        }

        // facilities
        inputCRS = cfg.facilities().getInputCRS();
        String fasCRS = data.getAfs() == null ? null : (String) data.getAfs().getAttributes().getAttribute("coordinateReferenceSystem");
        ctf = ctf(globalCRS, inputCRS, fasCRS);
        if (ctf != null && data.getAfs() != null) {
            CoordinateTransformation finalCtf = ctf;
            ModelProcessingPool.forEach(data.getAfs().getFacilities().values(), af -> {
                try {
                    Coord coord = finalCtf.transform(af.getCoord());
                    af.setCoord(coord);
                } catch (Exception e) {
//                    log.warn("facilities 坐标系转换失败, {}", e.getMessage());
                }
            });
        }


//        data.getAfs().getAttributes().putAttribute("coordinateReferenceSystem", "EPSG:3857");
//        data.getTs().getAttributes().putAttribute("coordinateReferenceSystem", "EPSG:3857");
    }


    /**
     * 坐标系转换
     *
     * @param globalCRS 全局坐标系
     * @param inputCRS  模块坐标系
     * @param crs       xml文件中坐标系
     */
    private static CoordinateTransformation ctf(String globalCRS, String inputCRS, String crs) {
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
     * 把小模型内存 plans 转成 WebMercator。单点失败必须置空，不能在随后把整个
     * population 标成 EPSG:3857 后继续把原坐标当作米制坐标参与覆盖率/密度计算。
     * 返回值写入 population attributes，供评价元数据披露；有效点仍完整保留，不抽样。
     */
    static long transformPopulationCoordinates(Population population, CoordinateTransformation transformation) {
        if (population == null || transformation == null) return 0L;
        AtomicLong failures = new AtomicLong();
        ModelProcessingPool.forEach(population.getPersons().values(), person -> {
            Plan plan = person.getSelectedPlan();
            if (plan == null && !person.getPlans().isEmpty()) plan = person.getPlans().get(0);
            if (plan == null) return;
            for (var element : plan.getPlanElements()) {
                if (!(element instanceof Activity activity) || activity.getCoord() == null) continue;
                try {
                    Coord transformed = transformation.transform(activity.getCoord());
                    if (transformed == null || !Double.isFinite(transformed.getX())
                            || !Double.isFinite(transformed.getY())) {
                        throw new IllegalArgumentException("坐标转换返回非有限值");
                    }
                    activity.setCoord(transformed);
                } catch (Exception e) {
                    activity.setCoord(null);
                    failures.incrementAndGet();
                }
            }
        });
        long count = failures.get();
        population.getAttributes().putAttribute("coordinateTransformFailures", count);
        return count;
    }

    /** 转换站点坐标；失败点置空并披露，避免原始坐标被误标成 EPSG:3857。 */
    static long transformScheduleCoordinates(
            org.matsim.pt.transitSchedule.api.TransitSchedule schedule,
            CoordinateTransformation transformation) {
        if (schedule == null || transformation == null) return 0L;
        AtomicLong failures = new AtomicLong();
        ModelProcessingPool.forEach(schedule.getFacilities().values(), facility -> {
            if (facility.getCoord() == null) return;
            try {
                Coord transformed = transformation.transform(facility.getCoord());
                if (transformed == null || !Double.isFinite(transformed.getX())
                        || !Double.isFinite(transformed.getY())) {
                    throw new IllegalArgumentException("坐标转换返回非有限值");
                }
                facility.setCoord(transformed);
            } catch (Exception e) {
                facility.setCoord(null);
                failures.incrementAndGet();
            }
        });
        long count = failures.get();
        schedule.getAttributes().putAttribute("coordinateTransformFailures", count);
        return count;
    }

    record NetworkBounds(double minX, double minY, double maxX, double maxY) {
    }

    static NetworkBounds networkBounds(
            java.util.Collection<? extends org.matsim.api.core.v01.network.Node> nodes) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        if (nodes != null) {
            for (var node : nodes) {
                Coord coord = node == null ? null : node.getCoord();
                if (coord == null || !Double.isFinite(coord.getX()) || !Double.isFinite(coord.getY())) continue;
                minX = Math.min(minX, coord.getX());
                minY = Math.min(minY, coord.getY());
                maxX = Math.max(maxX, coord.getX());
                maxY = Math.max(maxY, coord.getY());
            }
        }
        if (!Double.isFinite(minX) || !Double.isFinite(minY)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY)) {
            throw new IllegalStateException("network 不含可用坐标，无法计算模型范围");
        }
        return new NetworkBounds(minX, minY, maxX, maxY);
    }

    private static void removeNoSelectPlan(Population pop, String outputPlans) {
        int[] removeCount = {0};
        ModelProcessingPool.forEach(pop.getPersons().values(), person -> {
            Plan selected = person.getSelectedPlan();
            List<? extends Plan> plans = person.getPlans();
            for (int i = 0; i < plans.size(); i++) {
                Plan plan = plans.get(i);
                if (!selected.equals(plan)) {
                    person.removePlan(plan);
                    i--;
                    removeCount[0]++;
                }
            }
        });
        if (removeCount[0] > 0) {
            try {
                PopulationUtils.writePopulation(pop, outputPlans);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
        }
    }

}
