package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.api.common.Constant;
import com.jts.gjcxfzksh.api.common.DatasourceService;
import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.model.params.RouteChartParam;
import com.jts.gjcxfzksh.api.model.params.RouteInfoParam;
import com.jts.gjcxfzksh.api.model.params.RouteListParam;
import com.jts.gjcxfzksh.api.model.params.TileNetworkParam;
import com.jts.gjcxfzksh.api.model.pt.PTLink;
import com.jts.gjcxfzksh.api.model.vo.FacilityFlowVO;
import com.jts.gjcxfzksh.api.model.vo.LineVO;
import com.jts.gjcxfzksh.api.model.vo.RouteDetailVO;
import com.jts.gjcxfzksh.api.model.vo.RouteVO;
import com.jts.gjcxfzksh.api.service.RouteService;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.cache.MatsimPrecomputedCache;
import com.jts.gjcxfzksh.data.cache.MatsimRoutePanelCache;
import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import com.jts.gjcxfzksh.data.id.*;
import com.jts.gjcxfzksh.exception.BusinessException;
import com.jts.gjcxfzksh.utils.DistanceUtil;
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
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
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
        RouteDetailVO cached = MatsimPrecomputedCache.readRouteDetail(matsim_data, param.getRouteId());
        if (cached != null) {
            return cached;
        }
        Network network = network(param);
        if (param.getRouteId() == null) {
            throw new BusinessException("routeId 不能为空");
        }
        TransitRoute route = getTransitRoute(Id.create(param.getRouteId(), TransitRoute.class), param);
        if (route == null) {
            return null;
        }
        RouteDetailVO vo = new RouteDetailVO(route, network);
        // 直线系数
        vo.getInfo().setLc(routeRC(route, network));
        // 满载率
        vo.getInfo().setTakeRate(fullLoadRate(route, matsim_data));
        // 填充日均客流
        vo.getInfo().setPassenger(queryPTTrack(new RouteChartParam() {{
            setDatasource(param.getDatasource());
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
        if (param.getRouteId() == null) {
            throw new BusinessException("routeId 不能为空");
        }
        TransitRoute transitRoute = getTransitRoute(Id.create(param.getRouteId(), TransitRoute.class), param);
        if (transitRoute == null) {
            log.warn("找不到线路");
            return new HashMap<>();
        }
        Network network = matsim_data.getNetwork();
        Map<String, Object> result = new HashMap<>();
        // 日出行人次
        long rcxrc = matsim_data.getPersonTracks().stream().filter(PTPersonTrack::getEnter).count();
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
        double xlklqd = routePersonStrength(transitRoute, matsim_data);
        result.put("xlklqd", xlklqd);
        // 平均候车时间
        double pjhcsj = avgAwaitTime(transitRoute, matsim_data);
        result.put("pjhcsj", pjhcsj);
        return result;
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
            lineList.add(vo);
        }
        return lineList;
    }

    @Override
    public Map<String, Object> routePanel(DatasourceParam param) {
        return MatsimRoutePanelCache.readRoutePanel(matsim_data(param));
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

    /**
     * routeId获取TransitRoute
     */
    private TransitRoute getTransitRoute(Id<TransitRoute> routeId, DatasourceParam param) {
        TransitSchedule schedule = matsim_data(param).getSchedule();
        Map<Id<TransitLine>, TransitLine> transitLines = schedule.getTransitLines();
        for (Map.Entry<Id<TransitLine>, TransitLine> line : transitLines.entrySet()) {
            TransitLine transitLine = line.getValue();
            Map<Id<TransitRoute>, TransitRoute> routes = transitLine.getRoutes();
            for (Map.Entry<Id<TransitRoute>, TransitRoute> route : routes.entrySet()) {
                if (routeId.equals(route.getKey())) {
                    return route.getValue();
                }
            }
        }
        return null;
    }

    /**
     * 平均等待时间
     */
    private double avgAwaitTime(TransitRoute transitRoute, MatsimData matsim_data) {
        double awaitTime = 0;
        double count = 0;
        // 筛选 personid
        Set<PersonId> personIds = matsim_data.getPersonTracks().stream()
                .filter(track -> {
                    return track.getRouteId().equals(RouteId.create(transitRoute.getId()));
                })
                .map(PTPersonTrack::getPersonId)
                .collect(Collectors.toSet());
        if (personIds.isEmpty()) {
            return 0.0;
        }
        Map<Id<Person>, ? extends Person> persons = matsim_data.getPopulation().getPersons();
        for (
                PersonId personId : personIds) {
            Person person = persons.get(personId);
            List<PlanElement> elements = person.getSelectedPlan().getPlanElements();
            for (int i = 0; i < elements.size(); i++) {
                PlanElement element = elements.get(i);
                if (element instanceof Leg leg) {
                    Route route = leg.getRoute();
                    if (route instanceof TransitPassengerRoute tproute) {
                        if (tproute.getRouteId().equals(transitRoute.getId())
                                && Constant.ROUTE_MODE_PT.equals(leg.getMode())) {
                            if (i < 2 || !leg.getDepartureTime().isDefined()) {
                                continue;
                            }
                            Leg l2 = (Leg) elements.get(i - 2);
                            if (!l2.getDepartureTime().isDefined() || !l2.getTravelTime().isDefined()) {
                                continue;
                            }
                            double st = l2.getDepartureTime().seconds() + l2.getTravelTime().seconds();
                            awaitTime += leg.getDepartureTime().seconds() - st;
                            count++;
                        }
                    }
                }
            }
        }
        return awaitTime / count;
    }

    /**
     * 线路客流强度
     */
    private double routePersonStrength(TransitRoute transitRoute, MatsimData matsim_data) {
        double length = 0.;
        double personCount = 0.;
        NetworkRoute networkRoute = transitRoute.getRoute();
        length += DistanceUtil.distance(networkRoute, matsim_data.getNetwork());
        personCount += matsim_data.getPersonTracks().stream().filter(track -> {
            return track.getEnter() && track.getRouteId().equals(transitRoute.getId());
        }).count();

        return personCount / (length / 1000);
    }

    /**
     * 满载率
     */
    private double fullLoadRate(TransitRoute transitRoute, MatsimData matsim_data) {
        Map<VehicleId, List<PTPersonTrack>> person = matsim_data.getPersonTracks().stream().collect(Collectors.groupingBy(PTPersonTrack::getVehicleId));
        List<VehicleId> vehicleIds = new ArrayList<>();
        transitRoute.getDepartures().forEach((departureId, departure) -> {
            vehicleIds.add(VehicleId.create(departure.getVehicleId()));
        });
        Map<Id<Vehicle>, Vehicle> vehicleMap = matsim_data.getTv().getVehicles();
        double vehCount = 0.;
        double personCount = 0.;
        for (VehicleId vehId : vehicleIds) {
            VehicleType vehicleType = vehicleMap.get(vehId).getType();
            List<PTPersonTrack> list = person.get(vehId);
            personCount += list == null ? 0 : list.size();
            vehCount += vehicleType.getCapacity().getStandingRoom();
            vehCount += vehicleType.getCapacity().getSeats();
        }

        return personCount / vehCount;
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
            rc += NetworkUtils.getEuclideanDistance(link.getFromNode().getCoord(), link.getToNode().getCoord());
        }

        return length / rc;
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
        TransitRouteStop first = route.getStops().getFirst();
        TransitRouteStop last = route.getStops().getLast();
        // 直线距离
        double lcDistance = NetworkUtils.getEuclideanDistance(first.getStopFacility().getCoord(), last.getStopFacility().getCoord());
        if (lcDistance > 0) { // 环线距离 == 0, 不计算
            lc += (distance / lcDistance);
            routeCount++;
        }
        return lc / routeCount; // 平均值
    }

    /**
     * 公交图表出行数据查询
     */
    private List<PTPersonTrack> queryPTTrack(RouteChartParam param) {
        Set<PTPersonTrack> list = matsim_data(param).getPersonTracks();
        if (list == null || list.isEmpty()) {
            return new ArrayList<>();
        }
        return list.stream().filter(t -> {
            // 时间
            if (t.getTime() >= param.getBeginSecond() && t.getTime() <= param.getEndSecond()) {
                // 单趟
                if (param.getSingle()) {
                    if (param.getDepartureId() == null) {
                        return false;
                    }
                    return t.getDepartureId().equals(DepartureId.create(param.getDepartureId()));
                } else {
                    if (param.getRouteId() == null) {
                        return false;
                    }
                    return t.getRouteId().equals(RouteId.create(param.getRouteId()));
                }
            }
            return false;
        }).toList();
    }

}
