package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.api.common.Constant;
import com.jts.gjcxfzksh.api.common.DatasourceService;
import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.model.params.RouteChartParam;
import com.jts.gjcxfzksh.api.model.params.RouteInfoParam;
import com.jts.gjcxfzksh.api.model.params.RoutePickParam;
import com.jts.gjcxfzksh.api.model.params.RouteListParam;
import com.jts.gjcxfzksh.api.model.params.TileNetworkParam;
import com.jts.gjcxfzksh.api.model.pt.PTLink;
import com.jts.gjcxfzksh.api.model.vo.FacilityFlowVO;
import com.jts.gjcxfzksh.api.model.vo.LineVO;
import com.jts.gjcxfzksh.api.model.vo.RouteDetailVO;
import com.jts.gjcxfzksh.api.model.vo.RouteVO;
import com.jts.gjcxfzksh.api.model.vo.RoutePickVO;
import com.jts.gjcxfzksh.api.service.RouteService;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.cache.MatsimPrecomputedCache;
import com.jts.gjcxfzksh.data.cache.MatsimRoutePanelCache;
import com.jts.gjcxfzksh.data.cache.MatsimRouteSpatialIndex;
import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import com.jts.gjcxfzksh.data.id.*;
import com.jts.gjcxfzksh.exception.BusinessException;
import com.jts.gjcxfzksh.utils.DistanceUtil;
import com.jts.gjcxfzksh.utils.TransitMetrics;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Route;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.pt.transitSchedule.api.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RouteServiceImpl extends DatasourceService implements RouteService {


    @Override
    public List<RouteVO> routeList(RouteListParam param) {
        List<RouteVO> routeList = new ArrayList<>();
        MatsimData matsim_data = matsim_data(param);
        Map<Id<TransitLine>, TransitLine> transitLines = matsim_data.getSchedule().getTransitLines();
        for (Map.Entry<Id<TransitLine>, TransitLine> line : transitLines.entrySet()) {
            TransitLine transitLine = line.getValue();
            Map<Id<TransitRoute>, TransitRoute> transitRoutes = transitLine.getRoutes();
            for (Map.Entry<Id<TransitRoute>, TransitRoute> route : transitRoutes.entrySet()) {
                RouteVO vo = new RouteVO();
                vo.setRouteId(route.getKey().toString());
                vo.setRouteName(route.getKey().toString());
                if (param.getRouteName() == null || vo.getRouteName().contains(param.getRouteName())) {
                    routeList.add(vo);
                }
            }
        }
        return routeList;
    }

    @Override
    public RouteDetailVO routeDetail(RouteInfoParam param) {
        MatsimData matsim_data = matsim_data(param);
        RouteDetailVO cached = MatsimPrecomputedCache.readRouteDetail(matsim_data, param.getLineId(), param.getRouteId());
        if (cached != null) {
            return cached;
        }
        Network network = network(param);
        if (param.getRouteId() == null || param.getRouteId().isBlank()) {
            throw new BusinessException("routeId 不能为空");
        }
        TransitRoute route = getTransitRoute(Id.create(param.getRouteId(), TransitRoute.class), param);
        if (route == null) {
            return null;
        }
        RouteDetailVO vo = new RouteDetailVO(route, network);
        // 非直线系数（线路长度/首末站直线距离）。原实现误用重复系数 routeRC，
        // 单条 route 的重复系数恒≈1，导致该指标失去意义；与 routePanel 的 metrics.lc 口径对齐。
        vo.getInfo().setLc(routeNoLC(route, network));
        // 满载率
        vo.getInfo().setTakeRate(fullLoadRate(route, matsim_data));
        // 填充日均客流
        vo.getInfo().setPassenger(queryPTTrack(new RouteChartParam() {{
            setDatasource(param.getDatasource());
            setLineId(param.getLineId());
            setRouteId(param.getRouteId());
            setBeginSecond(0);
            setEndSecond(Integer.MAX_VALUE);
            setSingle(false);
        }}).stream().filter(PTPersonTrack::getEnter).count());
        return vo;
    }

    @Override
    public Map<String, Object> routeInfo(RouteInfoParam param) {
        MatsimData matsim_data = matsim_data(param);
        if (param.getRouteId() == null || param.getRouteId().isBlank()) {
            throw new BusinessException("routeId 不能为空");
        }
        LineRoute lineRoute = getLineRoute(Id.create(param.getRouteId(), TransitRoute.class), param);
        if (lineRoute == null) {
            log.warn("找不到线路");
            return new HashMap<>();
        }
        TransitRoute transitRoute = lineRoute.route();
        String lineId = lineRoute.lineId();
        Network network = matsim_data.getNetwork();
        Map<String, Object> result = new HashMap<>();
        // 日出行人次（仅统计该线路的上车人次；routeId 跨线路可重复，须带 lineId 过滤）
        long rcxrc = matsim_data.getPersonTracks().stream()
                .filter(track -> Boolean.TRUE.equals(track.getEnter())
                        && trackMatchesRoute(track, transitRoute, lineId))
                .count();
        result.put("rcxrc", rcxrc);
        // 非直线系数
        double fzxxs = routeNoLC(transitRoute, network);
        result.put("fzxxs", fzxxs);
        // 重复系数
        double cfxs = routeRC(transitRoute, network);
        result.put("cfxs", cfxs);
        // 满载率
        double mzl = fullLoadRate(transitRoute, matsim_data);
        result.put("mzl", mzl);
        // 线路客流强度
        double xlklqd = routePersonStrength(transitRoute, lineId, matsim_data);
        result.put("xlklqd", xlklqd);
        // 平均候车时间
        double pjhcsj = avgAwaitTime(transitRoute, lineId, matsim_data);
        result.put("pjhcsj", pjhcsj);
        return result;
    }

    /** track 是否属于该线路：routeId 相等且（track 未记录 lineId 或 lineId 一致）。 */
    private static boolean trackMatchesRoute(PTPersonTrack track, TransitRoute transitRoute, String lineId) {
        if (track.getRouteId() == null || !track.getRouteId().equals(transitRoute.getId())) {
            return false;
        }
        return track.getLineId() == null || lineId == null
                || lineId.equals(track.getLineId().toString());
    }

    @Override
    public List<RouteVO> routeAll(RouteInfoParam param) {

        return List.of();
    }

    @Override
    public List<LineVO> lineAll(DatasourceParam param) {
        MatsimData matsim_data = matsim_data(param);
        List<Object> cached = MatsimPrecomputedCache.readLines(matsim_data);
        if (cached != null) {
            return (List<LineVO>) (List<?>) cached;
        }
        List<LineVO> lineList = new ArrayList<>();
        Network network = matsim_data.getNetwork();
        Map<Id<TransitLine>, TransitLine> transitLines = schedule(param).getTransitLines();
        for (Map.Entry<Id<TransitLine>, TransitLine> line : transitLines.entrySet()) {
            TransitLine transitLine = line.getValue();
            LineVO vo = new LineVO();
            vo.setLineName(transitLine.getName());
            vo.setLineId(transitLine.getId().toString());
            List<RouteDetailVO> list = new ArrayList<>();
            Map<Id<TransitRoute>, TransitRoute> routes = transitLine.getRoutes();
            for (Map.Entry<Id<TransitRoute>, TransitRoute> route : routes.entrySet()) {
                RouteDetailVO rdv = new RouteDetailVO(route.getValue(), network);
                list.add(rdv);
            }
            vo.setRoutes(list);
            vo.setMode(lineMode(list));
            lineList.add(vo);
        }
        return lineList;
    }

    private String lineMode(List<RouteDetailVO> routes) {
        if (routes == null || routes.isEmpty()) {
            return "";
        }
        if (routes.stream().anyMatch(route -> "subway".equals(route.getMode()))) {
            return "subway";
        }
        if (routes.stream().anyMatch(route -> "bus".equals(route.getMode()))) {
            return "bus";
        }
        return routes.getFirst().getMode();
    }

    @Override
    public Map<String, Object> routePanel(DatasourceParam param) {
        return MatsimRoutePanelCache.readRoutePanel(matsim_data(param));
    }

    @Override
    public Map<String, Object> routePanelDetail(RouteInfoParam param) {
        return MatsimRoutePanelCache.readRoutePanelDetail(matsim_data(param), param.getLineId(), param.getRouteId());
    }

    @Override
    public Map<String, Object> overallFlow(DatasourceParam param) {
        return MatsimRoutePanelCache.readOverallFlow(matsim_data(param));
    }

    @Override
    public List<RoutePickVO> routeCandidates(RoutePickParam param) {
        return MatsimRouteSpatialIndex.query(
                matsim_data(param),
                param.getX(),
                param.getY(),
                param.getRadiusMeters(),
                param.getLimit()
        );
    }

    @Override
    public List<PTLink> routeTile(TileNetworkParam param) {
        MatsimData matsimData = matsim_data(param);
        List<Object> cached = MatsimPrecomputedCache.readRouteTile(matsimData, param.getZ(), param.getX(), param.getY());
        if (cached != null) {
            return (List<PTLink>) (List<?>) cached;
        }
        return List.of();
    }

    @Override
    public List<PTLink> routeFull(TileNetworkParam param) {
        MatsimData matsimData = matsim_data(param);
        if (matsimData.isLargeModel()) {
            log.warn("大模型禁止请求全量线路，请使用瓦片接口: datasource={}", param.getDatasource());
            return List.of();
        }
        Network network = matsimData.getNetwork();
        Set<Id<Link>> routeLinkIds = new LinkedHashSet<>();
        for (TransitLine line : matsimData.getSchedule().getTransitLines().values()) {
            for (TransitRoute transitRoute : line.getRoutes().values()) {
                Route route = transitRoute.getRoute();
                if (route instanceof NetworkRoute networkRoute) {
                    addRouteLink(routeLinkIds, networkRoute.getStartLinkId());
                    routeLinkIds.addAll(networkRoute.getLinkIds());
                    addRouteLink(routeLinkIds, networkRoute.getEndLinkId());
                }
            }
        }
        List<PTLink> result = new ArrayList<>(routeLinkIds.size());
        for (Id<Link> linkId : routeLinkIds) {
            Link link = network.getLinks().get(linkId);
            if (link != null) {
                result.add(PTLink.base(link, 0D));
            }
        }
        return result;
    }

    private void addRouteLink(Set<Id<Link>> routeLinkIds, Id<Link> linkId) {
        if (linkId != null) {
            routeLinkIds.add(linkId);
        }
    }

    @Override
    public List<FacilityFlowVO> routeFlow(RouteChartParam param) {
        List<PTPersonTrack> data = queryPTTrack(param);
        List<FacilityFlowVO> voList = facilityByRouteId(param);
        // 填充客流
        long flow = 0;
        Map<StopFacilityId, List<PTPersonTrack>> map = data.stream().collect(Collectors.groupingBy(PTPersonTrack::getFacilityId));
        for (FacilityFlowVO vo : voList) {
            List<PTPersonTrack> ptt = map.get(StopFacilityId.create(vo.getId()));
            long up = 0, down = 0;
            if (ptt != null) {
                up = ptt.stream().filter(PTPersonTrack::getEnter).count();
                down = ptt.stream().filter(t -> !t.getEnter()).count();
            }
            flow += (up - down);
            vo.setUp(up);
            vo.setDown(down);
            vo.setFlow(flow);
        }
        return voList;
    }


    private List<FacilityFlowVO> facilityByRouteId(RouteChartParam param) {
        List<FacilityFlowVO> facilityList = new ArrayList<>();
        if (param.getRouteId() == null || param.getRouteId().isBlank()) {
            return facilityList;
        }
        TransitRoute route = getTransitRoute(Id.create(param.getRouteId(), TransitRoute.class), param);
        if (route != null) {
            route.getStops().forEach(stop -> {
                FacilityFlowVO vo = new FacilityFlowVO();
                TransitStopFacility tsf = stop.getStopFacility();
                vo.setId(tsf.getId().toString());
                vo.setName(tsf.getName());
                facilityList.add(vo);
            });
        }
        return facilityList;
    }

    /** 解析结果：TransitRoute 及其所属 lineId（客流过滤需要复合键）。 */
    private record LineRoute(String lineId, TransitRoute route) {
    }

    /**
     * routeId获取TransitRoute
     */
    private TransitRoute getTransitRoute(Id<TransitRoute> routeId, DatasourceParam param) {
        LineRoute lineRoute = getLineRoute(routeId, param);
        return lineRoute == null ? null : lineRoute.route();
    }

    private LineRoute getLineRoute(Id<TransitRoute> routeId, DatasourceParam param) {
        TransitSchedule schedule = matsim_data(param).getSchedule();
        String lineId = routeLineId(param);
        if (lineId != null && !lineId.isBlank()) {
            TransitLine line = schedule.getTransitLines().get(Id.create(lineId, TransitLine.class));
            if (line != null) {
                TransitRoute route = line.getRoutes().get(routeId);
                if (route != null) {
                    return new LineRoute(lineId, route);
                }
            }
        }
        Map<Id<TransitLine>, TransitLine> transitLines = schedule.getTransitLines();
        for (Map.Entry<Id<TransitLine>, TransitLine> line : transitLines.entrySet()) {
            TransitLine transitLine = line.getValue();
            Map<Id<TransitRoute>, TransitRoute> routes = transitLine.getRoutes();
            for (Map.Entry<Id<TransitRoute>, TransitRoute> route : routes.entrySet()) {
                if (routeId.equals(route.getKey())) {
                    return new LineRoute(line.getKey().toString(), route.getValue());
                }
            }
        }
        return null;
    }

    private String routeLineId(DatasourceParam param) {
        if (param instanceof RouteInfoParam routeInfoParam) {
            return routeInfoParam.getLineId();
        }
        if (param instanceof RouteChartParam routeChartParam) {
            return routeChartParam.getLineId();
        }
        return null;
    }

    /**
     * 平均等待时间（秒）。乘客筛选与 plan leg 匹配都带 lineId，
     * 避免跨线路同名 routeId 的乘客/行程被混入。
     */
    private double avgAwaitTime(TransitRoute transitRoute, String lineId, MatsimData matsim_data) {
        if (matsim_data.getPopulation() == null) {
            return 0.0; // 大模型不加载 plans，无法基于计划时间统计
        }
        double awaitTime = 0;
        double count = 0;
        // 筛选 personid
        Set<PersonId> personIds = matsim_data.getPersonTracks().stream()
                .filter(track -> trackMatchesRoute(track, transitRoute, lineId))
                .map(PTPersonTrack::getPersonId)
                .collect(Collectors.toSet());
        if (personIds.isEmpty()) {
            return 0.0;
        }
        Map<Id<Person>, ? extends Person> persons = matsim_data.getPopulation().getPersons();
        for (PersonId personId : personIds) {
            Person person = persons.get(personId);
            if (person == null || person.getSelectedPlan() == null) {
                continue;
            }
            List<PlanElement> elements = person.getSelectedPlan().getPlanElements();
            for (int i = 0; i < elements.size(); i++) {
                PlanElement element = elements.get(i);
                if (element instanceof Leg leg) {
                    Route route = leg.getRoute();
                    if (route instanceof TransitPassengerRoute tproute) {
                        if (tproute.getRouteId().equals(transitRoute.getId())
                                && (tproute.getLineId() == null || lineId == null
                                        || lineId.equals(tproute.getLineId().toString()))
                                && Constant.ROUTE_MODE_PT.equals(leg.getMode())) {
                            if (i < 2 || !leg.getDepartureTime().isDefined()) {
                                continue;
                            }
                            if (!(elements.get(i - 2) instanceof Leg l2)) {
                                continue;
                            }
                            if (!l2.getDepartureTime().isDefined() || !l2.getTravelTime().isDefined()) {
                                continue;
                            }
                            double st = l2.getDepartureTime().seconds() + l2.getTravelTime().seconds();
                            double await = leg.getDepartureTime().seconds() - st;
                            if (await < 0) { // 计划数据异常的负候车样本丢弃，与 TransitMetrics 口径一致
                                continue;
                            }
                            awaitTime += await;
                            count++;
                        }
                    }
                }
            }
        }
        return count == 0 ? 0.0 : awaitTime / count;
    }

    /**
     * 线路客流强度（人次/km），上车记录按 lineId+routeId 过滤。
     */
    private double routePersonStrength(TransitRoute transitRoute, String lineId, MatsimData matsim_data) {
        NetworkRoute networkRoute = transitRoute.getRoute();
        double length = DistanceUtil.distance(networkRoute, matsim_data.getNetwork());
        double personCount = matsim_data.getPersonTracks().stream()
                .filter(track -> Boolean.TRUE.equals(track.getEnter())
                        && trackMatchesRoute(track, transitRoute, lineId))
                .count();
        return length <= 0 ? 0.0 : personCount / (length / 1000);
    }

    /**
     * 满载率：统一走指标口径层（上车人次/静态容量，输出小数）。
     * 原实现分子未过滤上车记录（上下车双计，人数约翻倍），已修正。
     */
    private double fullLoadRate(TransitRoute transitRoute, MatsimData matsim_data) {
        Map<VehicleId, List<PTPersonTrack>> tracksByVehicle = matsim_data.getPersonTracks().stream()
                .collect(Collectors.groupingBy(PTPersonTrack::getVehicleId));
        List<VehicleId> vehicleIds = new ArrayList<>();
        transitRoute.getDepartures().forEach((departureId, departure) ->
                vehicleIds.add(VehicleId.create(departure.getVehicleId())));
        return TransitMetrics.fullLoadRate(vehicleIds, tracksByVehicle, matsim_data.getTv().getVehicles());
    }

    /**
     * 重复系数
     */
    public double routeRC(TransitRoute route, Network network) {
        // 线路总长度
        double length = 0.;
        // 非重复路段
        Set<Id<Link>> links = new HashSet<>();
        NetworkRoute networkRoute = route.getRoute();
        // 距离
        double distance = DistanceUtil.distance(networkRoute, network);
        length += distance;
        links.add(networkRoute.getStartLinkId());
        links.addAll(networkRoute.getLinkIds());
        links.add(networkRoute.getEndLinkId());

        // 非重复路段长度
        double rc = 0.;
        Map<Id<Link>, ? extends Link> linkMap = network.getLinks();
        for (Id<Link> linkId : links) {
            Link link = linkMap.get(linkId);
            if (link == null) {
                continue;
            }
            rc += NetworkUtils.getEuclideanDistance(link.getFromNode().getCoord(), link.getToNode().getCoord());
        }

        return rc <= 0 ? 0.0 : length / rc;
    }

    /**
     * 线路非直线系数
     */
    public double routeNoLC(TransitRoute route, Network network) {
        int routeCount = 0;
        double lc = 0.;
        NetworkRoute networkRoute = route.getRoute();
        // 距离
        double distance = DistanceUtil.distance(networkRoute, network);
        if (route.getStops().size() < 2) {
            return 0.0;
        }
        TransitRouteStop first = route.getStops().getFirst();
        TransitRouteStop last = route.getStops().getLast();
        // 直线距离
        double lcDistance = NetworkUtils.getEuclideanDistance(first.getStopFacility().getCoord(), last.getStopFacility().getCoord());
        if (lcDistance > 0) { // 环线距离 == 0, 不计算
            lc += (distance / lcDistance);
            routeCount++;
        }
        return routeCount == 0 ? 0.0 : lc / routeCount; // 平均值
    }

    /**
     * 公交图表出行数据查询
     */
    private List<PTPersonTrack> queryPTTrack(RouteChartParam param) {
        Set<PTPersonTrack> list = matsim_data(param).getPersonTracks();
        if (list == null || list.isEmpty()) {
            return new ArrayList<>();
        }
        boolean single = Boolean.TRUE.equals(param.getSingle());
        if (single) {
            if (param.getDepartureId() == null || param.getDepartureId().isBlank()) {
                throw new BusinessException("departureId 不能为空");
            }
        } else if (param.getRouteId() == null || param.getRouteId().isBlank()) {
            throw new BusinessException("routeId 不能为空");
        }
        int beginSecond = Math.max(0, param.getBeginSecond());
        int endSecond = param.getEndSecond() <= 0 ? Integer.MAX_VALUE : param.getEndSecond();
        return list.stream().filter(t -> {
            // 时间
            if (t.getTime() >= beginSecond && t.getTime() <= endSecond) {
                // 单趟
                if (single) {
                    return t.getDepartureId().equals(DepartureId.create(param.getDepartureId()));
                } else {
                    if (!t.getRouteId().equals(RouteId.create(param.getRouteId()))) {
                        return false;
                    }
                    return param.getLineId() == null
                            || param.getLineId().isBlank()
                            || String.valueOf(t.getLineId()).equals(param.getLineId());
                }
            }
            return false;
        }).toList();
    }

}
