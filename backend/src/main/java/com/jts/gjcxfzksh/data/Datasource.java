package com.jts.gjcxfzksh.data;

import com.jts.gjcxfzksh.api.model.pt.PTCoord;
import com.jts.gjcxfzksh.data.cache.MatsimAnalysisCache;
import com.jts.gjcxfzksh.data.cache.MatsimPrecomputedCache;
import com.jts.gjcxfzksh.data.cache.MatsimRoutePanelCache;
import com.jts.gjcxfzksh.data.cache.MatsimRouteSpatialIndex;
import com.jts.gjcxfzksh.data.cache.MatsimStationPanelCache;
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
import org.matsim.core.config.ConfigReader;
import org.matsim.core.config.ConfigUtils;
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
    private static final ExecutorService LOAD_EXECUTOR = Executors.newFixedThreadPool(1, r -> {
        Thread thread = new Thread(r, "matsim-model-loader");
        thread.setDaemon(true);
        return thread;
    });

    public static Database data(String name) {
        Database data = dataMap.get(name);
        if (data == null) {
            log.error("数据[{}]未加载", name);
            throw new RuntimeException("数据[" + name + "]未加载");
        }
        data.matsim_data().setLastRequestTime(System.currentTimeMillis());
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
        dataMap.remove(name);
        loadStatusMap.remove(name);
        statusMap.remove(name);
        retainLoadedRequests.remove(name);
    }

    public static void unload(String name) {
        retainLoadedRequests.remove(name);
        loadVersionMap.merge(name, 1L, Long::sum);
        dataMap.remove(name);
        loadStatusMap.remove(name);
        loadingStatusMap.remove(name);
        setStatus(name, "unloaded", "模型已卸载", false, false);
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
        setStatus(name, "queued", "模型加载已进入后台队列", false, true);
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
        load(scheme, currentLoadVersion(scheme.getName()), true);
    }

    public static void loadForCache(Scheme scheme) {
        load(scheme, currentLoadVersion(scheme.getName()), false);
    }

    private static long currentLoadVersion(String name) {
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
        ModelLoadStatus status = setStatus(scheme.getName(), "loading_config", "正在加载基础路网和公交模型", false, true);
        status.setStartedAt(startTime);
        try {
            MatsimData data = new MatsimData(scheme.getName(), scheme.getOutput(), scheme.getCache(), scheme.isLargeModel());
            data.setArea(scheme.getDesc().getArea());
            // 加载
            loadConfig(data);
            // 基础模型就绪时即建立真实公交路网命中索引，并预热已存在的线路面板缓存。
            MatsimRouteSpatialIndex.prepareOnModelLoad(data);
            MatsimRoutePanelCache.preloadIfReady(data);
            if (cancelable && isStaleLoad(name, expectedVersion)) {
                log.info("模型加载完成但请求已取消，不写入内存: model={}", name);
                return;
            }
            long endTime = System.currentTimeMillis();
            log.info("加载[{}]耗时: {}ms", scheme.getName(), endTime - startTime);
            dataMap.put(scheme.getName(), new Database(data));
            loadStatusMap.put(scheme.getName(), true);
            status = setStatus(scheme.getName(), "ready", "模型基础数据已加载，缓存将在后台生成", true, false);
            status.setStartedAt(startTime);
            status.setFinishedAt(endTime);
        } catch (RuntimeException e) {
            if (!cancelable || !isStaleLoad(name, expectedVersion)) {
                loadStatusMap.put(scheme.getName(), false);
                status = setStatus(scheme.getName(), "failed", e.getMessage() == null ? "模型加载失败" : e.getMessage(), false, false);
                status.setStartedAt(startTime);
                status.setFinishedAt(System.currentTimeMillis());
            }
            throw e;
        } finally {
            loadingStatusMap.remove(scheme.getName());
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

    private static ModelLoadStatus setStatus(String name, String stage, String message, boolean loaded, boolean loading) {
        ModelLoadStatus status = statusMap.computeIfAbsent(name, ignored -> new ModelLoadStatus());
        status.setStage(stage);
        status.setMessage(message);
        status.setLoaded(loaded);
        status.setLoading(loading);
        return status;
    }

    private static void loadEvent(MatsimData data) {
        loadEvent(data, null);
    }

    private static void loadEvent(MatsimData data, MatsimAnalysisCache.BuildProgress progress) {
        try {
            MatsimAnalysisCache.prepareOnModelLoad(data);
            if (data.isLargeModel()) {
                MatsimAnalysisCache.ensureTrajectoryCache(data, progress);
                if (data.getPersonTracks() == null || data.getPersonTracks().isEmpty()) {
                    MatsimAnalysisCache.prepareOnModelLoad(data);
                }
            }
            MatsimRoutePanelCache.prepareOnModelLoad(data);
            MatsimStationPanelCache.prepareOnModelLoad(data);
            MatsimPrecomputedCache.prepareOnModelLoad(data);
            MatsimRouteSpatialIndex.prepareOnModelLoad(data);
            if (!data.isLargeModel()) {
                MatsimAnalysisCache.ensureTrajectoryCache(data, progress);
            }
        } catch (Exception e) {
            log.error("event加载失败: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private static void loadConfig(MatsimData data) {
        MatsimOutFile outfile = data.getOutfile();
        Config cfg = ConfigUtils.createConfig();
        new ConfigReader(cfg).readFile(outfile.getConfig());
        cfg.network().setInputFile(outfile.getNetwork());
        if (data.isLargeModel()) {
            cfg.plans().setInputFile(null);
            cfg.facilities().setInputFile(null);
            cfg.vehicles().setVehiclesFile(null);
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
        data.getNetwork().getNodes().values().parallelStream().forEach(node -> {
            Coord nodeCoord = node.getCoord();
            try {
                var transformedCoord = new Coord(nodeCoord.getX(), nodeCoord.getY(), nodeCoord.hasZ() ? nodeCoord.getZ() : 0);
                if (nodectf != null) {
                    transformedCoord = nodectf.transform(transformedCoord);
                }
                node.setCoord(transformedCoord);
            } catch (Exception e) {
                log.warn("network.node 坐标系系转换失败, {}", e.getMessage());
            }
        });


        // 路网中心点
        double[] maxx = new double[]{0}, minx = new double[]{Double.MAX_VALUE}, maxy = new double[]{0}, miny = new double[]{Double.MAX_VALUE};
        data.getNetwork().getNodes().values().forEach(node -> {
            if (maxx[0] < node.getCoord().getX()) {
                maxx[0] = node.getCoord().getX();
            }
            if (maxy[0] < node.getCoord().getY()) {
                maxy[0] = node.getCoord().getY();
            }
            if (minx[0] > node.getCoord().getX()) {
                minx[0] = node.getCoord().getX();
            }
            if (miny[0] > node.getCoord().getY()) {
                miny[0] = node.getCoord().getY();
            }
        });
        data.center = new Coord((maxx[0] + minx[0]) / 2, (maxy[0] + miny[0]) / 2);
        data.range[0] = new PTCoord(maxx[0], maxy[0]);
        data.range[1] = new PTCoord(minx[0], miny[0]);

        // 公交
        inputCRS = data.config.transit().getInputScheduleCRS();
        String tfCRS = (String) data.getSchedule().getAttributes().getAttribute("coordinateReferenceSystem");
        ctf = ctf(globalCRS, inputCRS, tfCRS);
        if (ctf != null) {
            CoordinateTransformation finalCtf = ctf;
            data.getSchedule().getFacilities().values().parallelStream().forEach(facility -> {
                Coord coord = facility.getCoord();
                try {
                    var transformedCoord = finalCtf.transform(coord);
                    facility.setCoord(transformedCoord);
                } catch (Exception e) {
//                    log.warn("transitSchedule.facility 坐标系转换失败, {}", e.getMessage());
                }
            });
        }

        // plans。原始 output 必须保持只读，加载阶段不再回写 plans 文件。
        if (!data.isLargeModel() && data.getPopulation() != null) {
            inputCRS = cfg.plans().getInputCRS();
            String planCRS = (String) data.getPopulation().getAttributes().getAttribute("coordinateReferenceSystem");
            ctf = ctf(globalCRS, inputCRS, planCRS);
            if (ctf != null) {
                CoordinateTransformation finalCtf = ctf;
                data.getPopulation().getPersons().values().parallelStream().forEach(person -> {
                    person.getSelectedPlan().getPlanElements().forEach(element -> {
                        if (element instanceof Activity act) {
                            if (act.getCoord() != null) {
                                Coord coord = act.getCoord();
                                try {
                                    var transformedCoord = finalCtf.transform(coord);
                                    act.setCoord(transformedCoord);
                                } catch (Exception e) {
//                                log.warn("plan.activity 坐标系转换失败, {}", e.getMessage());
                                }
                            }
                        }
                    });
                });
            }
        }

        // facilities
        inputCRS = cfg.facilities().getInputCRS();
        String fasCRS = data.getAfs() == null ? null : (String) data.getAfs().getAttributes().getAttribute("coordinateReferenceSystem");
        ctf = ctf(globalCRS, inputCRS, fasCRS);
        if (ctf != null && data.getAfs() != null) {
            CoordinateTransformation finalCtf = ctf;
            data.getAfs().getFacilities().values().parallelStream().forEach(af -> {
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

    private static void removeNoSelectPlan(Population pop, String outputPlans) {
        int[] removeCount = {0};
        pop.getPersons().values().parallelStream().forEach((person) -> {
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
