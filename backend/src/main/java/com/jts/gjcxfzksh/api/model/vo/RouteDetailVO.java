package com.jts.gjcxfzksh.api.model.vo;

import com.jts.gjcxfzksh.api.model.pt.PTCoord;
import com.jts.gjcxfzksh.api.model.pt.PTLink;
import com.jts.gjcxfzksh.utils.DistanceUtil;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitRoute;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class RouteDetailVO {

    private String routeId;
    private String routeName;
    private Info info;
    private List<PTLink> links = new ArrayList<>();
    private List<FacilityVO> facilities = new ArrayList<>();
    private List<DepartureVO> departures = new ArrayList<>();

    public RouteDetailVO() {
    }

    public RouteDetailVO(TransitRoute route, Network network) {
        this.routeId = route.getId().toString();
        this.routeName = this.routeId;
        NetworkRoute networkRoute = route.getRoute();
        double routeDist = 0;
        routeDist += DistanceUtil.distance(network.getLinks().get(networkRoute.getStartLinkId()));
        this.links.add(PTLink.base(network.getLinks().get(networkRoute.getStartLinkId())));
        for (Id<Link> linkId : networkRoute.getLinkIds()) {
            Link link = network.getLinks().get(linkId);
            routeDist += DistanceUtil.distance(link);
            this.links.add(PTLink.base(link));
        }
        routeDist += DistanceUtil.distance(network.getLinks().get(networkRoute.getEndLinkId()));
        this.links.add(PTLink.base(network.getLinks().get(networkRoute.getEndLinkId())));
        // 站点信息
        route.getStops().forEach(stop -> {
            FacilityVO vo = new FacilityVO();
            vo.setFacilityName(stop.getStopFacility().getName());
            vo.setFacilityId(stop.getStopFacility().getId().toString());
            vo.setCoord(new PTCoord(stop.getStopFacility().getCoord()));
            facilities.add(vo);
        });
        // 班次信息
        route.getDepartures().forEach(((departureId, departure) -> {
            departures.add(new DepartureVO(departure));
        }));

        // 追加基础信息
        Info info = new Info();
        info.routeDist = routeDist;
        appendInfo(route, info);
    }

    private void appendInfo(TransitRoute route, Info info) {
        Map<Id<Departure>, Departure> departures = route.getDepartures();
        if (!departures.isEmpty()) {
            List<Departure> ds = departures.values().stream().toList();
            info.firstTime = ds.getFirst().getDepartureTime();
            info.lastTime = ds.getLast().getDepartureTime();
        }
//        info.departureInterval = new ArrayList<>();
//        for (PtTransitDepartureRule departureRule : departureRules) {
//            info.departureInterval.add(departureRule.getBeginTime() + " - " + departureRule.getEndTime() + ", " + (departureRule.getSpaces() / 60) + "分");
//        }
        info.facNum = route.getStops().size();
        info.facDist = info.routeDist / info.facNum;
        this.info = info;
    }

    @Data
    @Schema(description = "线路基础信息")
    public static class Info {
        /**
         * 线路长度
         */
        @Schema(description = "线路长度")
        private double routeDist;
        /**
         * 首班时间
         */
        @Schema(description = "首班时间")
        private double firstTime;
        /**
         * 末班时间
         */
        @Schema(description = "末班时间")
        private double lastTime;
        /**
         * 站点数量
         */
        @Schema(description = "站点数量")
        private int facNum;
        /**
         * 满载率
         */
        @Schema(description = "满载率")
        private double takeRate;
        /**
         * 发班间隔
         */
//        @Schema(description = "发班间隔")
//        private List<String> departureInterval;
        /**
         * 直线系数
         */
        @Schema(description = "直线系数")
        private double lc;
        /**
         * 平均站距
         */
        @Schema(description = "平均站距")
        private double facDist;
        /**
         * 日均客流
         */
        @Schema(description = "日均客流")
        private double passenger;

    }

}
