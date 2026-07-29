package com.jts.gjcxfzksh.optimization.service;

import com.alibaba.fastjson2.JSONObject;
import com.jts.gjcxfzksh.config.MatsimConfig;
import com.jts.gjcxfzksh.data.Datasource;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.exception.BusinessException;
import com.jts.gjcxfzksh.optimization.model.AreaSpec;
import com.jts.gjcxfzksh.optimization.util.GeoUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.matsim.api.core.v01.network.Link;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * 区域概览统计：基于已加载母本模型的内存数据（EPSG:3857）。
 */
@Slf4j
@Service
public class RegionStatsService {

    @Resource
    private MatsimConfig matsimConfig;

    public JSONObject areaStats(String username, String parentModel, AreaSpec area) {
        matsimConfig.requireSchemeAccess(parentModel, username);
        if (area == null || area.getPolygon() == null) {
            throw new BusinessException("缺少研究区域");
        }
        MatsimData data;
        try {
            data = Datasource.data(parentModel).matsim_data();
        } catch (Exception e) {
            throw new BusinessException("母本模型未加载，请先在右上角加载模型");
        }
        if (!data.hasFullRoadNetwork()) {
            throw new BusinessException("大模型当前仅加载公交子路网，不支持道路优化区域统计");
        }

        double centerLat = centroidLat(area);
        Polygon polygon = GeoUtil.toPolygon(area.getPolygon(), null, true);
        double bufferUnits = GeoUtil.bufferInCrsUnits("EPSG:3857", centerLat, area.getBufferM());
        Polygon zonePolygon = (Polygon) polygon.buffer(Math.max(0, bufferUnits));
        PreparedGeometry zone = GeoUtil.prepare(zonePolygon);
        PreparedGeometry core = GeoUtil.prepare(polygon);

        int linkCount = 0;
        double linkKm = 0;
        for (Link link : data.getNetwork().getLinks().values()) {
            double cx = (link.getFromNode().getCoord().getX() + link.getToNode().getCoord().getX()) / 2;
            double cy = (link.getFromNode().getCoord().getY() + link.getToNode().getCoord().getY()) / 2;
            if (GeoUtil.contains(core, cx, cy)) {
                linkCount++;
                linkKm += link.getLength() / 1000.0;
            }
        }

        Set<String> stopsIn = new HashSet<>();
        for (TransitStopFacility stop : data.getSchedule().getFacilities().values()) {
            if (GeoUtil.contains(core, stop.getCoord().getX(), stop.getCoord().getY())) {
                stopsIn.add(stop.getId().toString());
            }
        }

        int linesTouching = 0;
        int linesInside = 0;
        int routesTouching = 0;
        for (TransitLine line : data.getSchedule().getTransitLines().values()) {
            boolean touch = false;
            boolean allIn = true;
            for (TransitRoute route : line.getRoutes().values()) {
                boolean routeTouch = false;
                for (TransitRouteStop stop : route.getStops()) {
                    boolean in = GeoUtil.contains(zone,
                            stop.getStopFacility().getCoord().getX(), stop.getStopFacility().getCoord().getY());
                    if (in) {
                        routeTouch = true;
                    } else {
                        allIn = false;
                    }
                }
                if (routeTouch) {
                    routesTouching++;
                    touch = true;
                }
            }
            if (touch) {
                linesTouching++;
                if (allIn) {
                    linesInside++;
                }
            }
        }

        JSONObject result = new JSONObject();
        result.put("areaKm2", round2(GeoUtil.areaKm2Mercator(polygon, centerLat)));
        result.put("bufferM", area.getBufferM());
        result.put("stopCount", stopsIn.size());
        result.put("lineTouchCount", linesTouching);
        result.put("lineInsideCount", linesInside);
        result.put("routeTouchCount", routesTouching);
        result.put("linkCount", linkCount);
        result.put("linkKm", round2(linkKm));
        // 人口/出行量在切分阶段流式统计（大模型内存中不含 plans）
        result.put("personEstimate", -1);
        return result;
    }

    public static double centroidLat(AreaSpec area) {
        double sum = 0;
        int n = 0;
        for (double[] pt : area.getPolygon()) {
            sum += pt[1];
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
