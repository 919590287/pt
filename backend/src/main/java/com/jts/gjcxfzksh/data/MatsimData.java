package com.jts.gjcxfzksh.data;


import com.jts.gjcxfzksh.api.model.pt.PTCoord;
import com.jts.gjcxfzksh.data.entry.MatsimOutFile;
import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.Config;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.facilities.ActivityFacilities;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.vehicles.Vehicles;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * matsim数据
 */
@Data
@Slf4j
public class MatsimData {

    public MatsimData(String name, String folder) {
        this(name, folder, defaultCacheFolder(name), false);
    }

    public MatsimData(String name, String folder, String cacheFolder, boolean largeModel) {
        this.name = name;
        this.folder = folder;
        this.cacheFolder = cacheFolder;
        this.largeModel = largeModel;
        this.outfile = MatsimOutFile.reload(folder, cacheFolder);
    }

    private static String defaultCacheFolder(String name) {
        String safeName = name == null ? "default" : Integer.toHexString(name.hashCode());
        return Path.of(System.getProperty("java.io.tmpdir"), "gjcxfzksh-cache", safeName).toString();
    }

    /**
     * 方案output目录
     */
    protected final String folder;

    /**
     * 平台生成缓存目录。必须独立于原始 output 目录。
     */
    protected final String cacheFolder;

    /**
     * 大模型模式：避免 eager 读取超大 plans/events 到 JVM heap。
     */
    protected final boolean largeModel;

    /**
     * 基准/方案名
     */
    protected final String name;

    /**
     * 加载状态
     */
    protected int loadStatus = -1;

    /**
     * matsim最小时间
     */
    protected double minTime = 0.;

    /**
     * matsim最大时间
     */
    protected double maxTime = Double.MIN_VALUE;

    /**
     * matsim Config
     */
    protected Config config;

    /**
     * matsim Scenario
     */
    protected MutableScenario scenario;

    /**
     * 路网中心点
     */
    protected Coord center;

    /**
     * 面积
     */
    protected double area = 0.;

    /**
     * 数量口径固定为模型原始值。历史 desc.json 中的 scale 仅是来源元数据，
     * 平台不再用它对人口、客流或任何评价指标扩样。
     */
    protected double scale = 1.0;

    /**
     * 禁止通过模型描述改变计算数量。保留 setter 是为了兼容旧调用方和旧 desc.json，
     * 但运行态永远按“文件中有多少就计算多少”的 1:1 口径。
     */
    public void setScale(double ignoredScale) {
        this.scale = 1.0;
    }

    /**
     * 路网最大点最小点
     */
    protected PTCoord[] range = new PTCoord[2];

    /**
     * 默认zoom level
     */
    protected int defaultZoomLevel = 12;

    /**
     * 最后一次请求时间。如果超过一天没请求就移除
     */
    private long lastRequestTime = 0;

    /**
     * route
     */
    protected Map<String, TransitRoute> routes = new Object2ObjectOpenHashMap<>();

    protected Set<PTPersonTrack> personTracks = new ObjectOpenHashSet<>();

    /**
     * linkId -> simulated traffic volume
     */
    protected Map<String, Double> linkFlows;

    /**
     * 输出文件
     */
    private MatsimOutFile outfile;

    /**
     * event 是否加载完毕
     */
    protected volatile boolean eventIsLoaded = false;

    public Network getNetwork() {
        return scenario.getNetwork();
    }

    /**
     * 公交计算/展示网络。大模型下这是仅包含公交线路引用 link 的低内存网络；
     * 普通模型下与完整道路网络相同。
     */
    public Network getTransitNetwork() {
        return scenario.getNetwork();
    }

    /** 当前内存网络是否可安全用于道路统计、吸附和寻路。 */
    public boolean hasFullRoadNetwork() {
        return !largeModel;
    }

    /**
     * 道路业务必须从该入口取得网络，避免把大模型公交精简网络误当完整道路网并返回错误结果。
     */
    public Network requireFullRoadNetwork() {
        if (!hasFullRoadNetwork()) {
            throw new IllegalStateException("大模型当前仅加载公交精简网络，不支持需要完整道路网络的优化操作");
        }
        return scenario.getNetwork();
    }

    public TransitSchedule getSchedule() {
        return scenario.getTransitSchedule();
    }

    public Vehicles getTv() {
        return scenario.getTransitVehicles();
    }

    public Population getPopulation() {
        return scenario.getPopulation();
    }

    public ActivityFacilities getAfs() {
        return scenario.getActivityFacilities();
    }


    @Override
    public String toString() {
        return name + "[" +
                "Link:" + scenario.getNetwork().getLinks().size() + ", " +
                "Node:" + scenario.getNetwork().getNodes().size() + ", " +
                "StopFacilities:" + scenario.getTransitSchedule().getFacilities().size() + ", " +
                "TransitLines:" + scenario.getTransitSchedule().getTransitLines().size() + ", " +
                "Vehicles:" + scenario.getVehicles().getVehicles().size() + ", " +
                "Persons:" + scenario.getPopulation().getPersons().size() + ", " +
                "Facilities:" + scenario.getActivityFacilities().getFacilities().size() + ", " +
                "]";
    }

}
