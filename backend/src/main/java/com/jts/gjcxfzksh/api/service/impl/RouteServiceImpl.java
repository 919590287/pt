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
import com.jts.gjcxfzksh.data.cache.MatsimPersonTrackStore;
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
        if (matsim_data.isLargeModel()) {
            // 大模型不允许回退到 2000 万级 personTracks 全表扫描。
            throw new BusinessException("大模型线路详情缓存尚未就绪");
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
        // 线路平均高峰满载率（小数）；无有效高峰班次时保留 DTO 默认值。
        Double peakAverageLoadRate = peakAverageLoadRate(route, param.getLineId(), matsim_data);
        if (peakAverageLoadRate != null) {
            vo.getInfo().setTakeRate(peakAverageLoadRate);
        }
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
        if (matsim_data.isLargeModel()) {
            Map<String, Object> detail = MatsimRoutePanelCache.readRoutePanelDetail(
                    matsim_data, lineId, transitRoute.getId().toString());
            if (detail.get("metrics") instanceof Map<?, ?> metrics) {
                Map<String, Object> result = new HashMap<>();
                result.put("rcxrc", metric(metrics, "passenger"));
                result.put("fzxxs", metric(metrics, "lc"));
                result.put("cfxs", routeRC(transitRoute, matsim_data.getTransitNetwork()));
                Object peakAverageLoadRate = metrics.get("peakAverageLoadRate");
                result.put("mzl", peakAverageLoadRate instanceof Number number
                        ? number.doubleValue() / 100.0 : null);
                result.put("xlklqd", metric(metrics, "passengerStrength"));
                // 大模型不物化 plans；无可靠线路候车时间缓存时明确返回 null，不能伪装成 0。
                result.put("pjhcsj", null);
                return result;
            }
            throw new BusinessException("大模型线路客流缓存尚未就绪");
        }
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
        Double mzl = peakAverageLoadRate(transitRoute, lineId, matsim_data);
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

    private static double metric(Map<?, ?> metrics, String key) {
        Object value = metrics.get(key);
        return value instanceof Number number ? number.doubleValue() : 0.0;
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
        return MatsimRoutePanelCache.readRoutePanelIndex(matsim_data(param));
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
        if (matsimData.isLargeModel()) {
            throw new BusinessException("大模型公交线路瓦片缓存尚未就绪，请稍后重试");
        }
        return List.of();
    }

    @Override
    public List<PTLink> routeFull(TileNetworkParam param) {
        MatsimData matsimData = matsim_data(param);
        if (matsimData.isLargeModel()) {
            throw new BusinessException("大模型不支持全量线路返回，请使用瓦片接口");
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
        MatsimData matsimData = matsim_data(param);
        if (matsimData.isLargeModel()) {
            return largeModelRouteFlow(param, matsimData);
        }
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

    /**
     * V6 断面客流直接使用 route-panel 的线路×站点×小时分片，请求成本与站数相关，
     * 不再每次解压并扫描全量乘客明细。
     */
    private List<FacilityFlowVO> largeModelRouteFlow(RouteChartParam param, MatsimData data) {
        if (Boolean.TRUE.equals(param.getSingle())) {
            throw new BusinessException("大模型暂不支持单班次断面客流，请按线路和小时查询");
        }
        if (param.getRouteId() == null || param.getRouteId().isBlank()) {
            throw new BusinessException("routeId 不能为空");
        }
        int begin = Math.max(0, param.getBeginSecond());
        int end = param.getEndSecond() <= 0 ? 24 * 3600 : Math.min(24 * 3600, param.getEndSecond());
        if (begin % 3600 != 0 || end % 3600 != 0) {
            throw new BusinessException("大模型断面客流按整小时聚合，起止时刻需对齐整点");
        }
        int fromHour = Math.min(24, begin / 3600);
        int toHour = Math.max(fromHour, Math.min(24, end / 3600));
        Map<String, Object> detail = MatsimRoutePanelCache.readRoutePanelDetail(
                data, param.getLineId(), param.getRouteId());
        if (!(detail.get("stationFlows") instanceof Iterable<?> stationFlows)) {
            throw new BusinessException("大模型线路断面客流缓存尚未就绪");
        }

        Map<String, long[]> flowByFacility = new HashMap<>();
        for (Object value : stationFlows) {
            if (!(value instanceof Map<?, ?> station)) continue;
            String facilityId = String.valueOf(station.get("facilityId"));
            long up = sumHours(station.get("boardingByHour"), fromHour, toHour);
            long down = sumHours(station.get("alightingByHour"), fromHour, toHour);
            flowByFacility.put(facilityId, new long[]{up, down});
        }
        List<FacilityFlowVO> result = facilityByRouteId(param);
        long onboard = 0;
        for (FacilityFlowVO station : result) {
            long[] values = flowByFacility.getOrDefault(station.getId(), new long[2]);
            onboard += values[0] - values[1];
            station.setUp(values[0]);
            station.setDown(values[1]);
            station.setFlow(onboard);
        }
        return result;
    }

    private static long sumHours(Object source, int fromHour, int toHour) {
        if (!(source instanceof Iterable<?> values)) return 0L;
        long result = 0L;
        int index = 0;
        for (Object value : values) {
            if (index >= toHour) break;
            if (index >= fromHour && value instanceof Number number) {
                result += number.longValue();
            }
            index++;
        }
        return result;
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

    /** 线路客流强度（人次/车公里），上车记录按 lineId+routeId 过滤。 */
    private double routePersonStrength(TransitRoute transitRoute, String lineId, MatsimData matsim_data) {
        NetworkRoute networkRoute = transitRoute.getRoute();
        double length = DistanceUtil.distance(networkRoute, matsim_data.getNetwork());
        double personCount = matsim_data.getPersonTracks().stream()
                .filter(track -> Boolean.TRUE.equals(track.getEnter())
                        && trackMatchesRoute(track, transitRoute, lineId))
                .count();
        double operatingVehicleKm = length > 0
                ? length / 1000.0 * transitRoute.getDepartures().size() : 0.0;
        return operatingVehicleKm <= 0 ? 0.0 : personCount / operatingVehicleKm;
    }

    /**
     * 平均高峰满载率：每个高峰班次先取最大站段在车人数/额定载客量，
     * 再对该运行路径全部高峰班次等权平均（输出小数）。
     */
    private Double peakAverageLoadRate(TransitRoute transitRoute, String lineId, MatsimData matsim_data) {
        TransitMetrics.PeakAverageLoadAccumulator accumulator =
                TransitMetrics.PeakAverageLoadAccumulator.route(
                        lineId, transitRoute, matsim_data.getTv(), true);
        matsim_data.getPersonTracks().stream()
                .filter(track -> trackMatchesRoute(track, transitRoute, lineId))
                .forEach(accumulator::accept);
        Double percent = accumulator.finish().percent();
        return percent == null ? null : percent / 100.0;
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
        MatsimData data = matsim_data(param);
        if (data.isLargeModel()) {
            throw new BusinessException("大模型不支持请求时扫描全量乘客明细，请使用预聚合面板接口");
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
        List<PTPersonTrack> result = new ArrayList<>();
        MatsimPersonTrackStore.forEachTrack(data, t -> {
            // 时间
            if (t.getTime() >= beginSecond && t.getTime() <= endSecond) {
                // 单趟
                if (single) {
                    if (Objects.equals(t.getDepartureId(), DepartureId.create(param.getDepartureId()))) {
                        result.add(t);
                    }
                } else {
                    if (!Objects.equals(t.getRouteId(), RouteId.create(param.getRouteId()))) {
                        return;
                    }
                    if (param.getLineId() == null
                            || param.getLineId().isBlank()
                            || String.valueOf(t.getLineId()).equals(param.getLineId())) {
                        result.add(t);
                    }
                }
            }
        });
        return result;
    }

}
