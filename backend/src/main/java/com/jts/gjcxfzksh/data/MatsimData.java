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

import java.util.Map;
import java.util.Set;

/**
 * matsim数据
 */
@Data
@Slf4j
public class MatsimData {

    public MatsimData(String name, String folder) {
        this.name = name;
        this.folder = folder;
        this.outfile = MatsimOutFile.reload(folder);
    }

    /**
     * 方案output目录
     */
    protected final String folder;

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
    protected double area = 1.;

    /**
     * 流量比例
     */
    protected double scale = 1.0;

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
