package com.jts.gjcxfzksh.utils;

import com.jts.gjcxfzksh.api.common.Constant;
import com.jts.gjcxfzksh.data.ModelProcessingPool;
import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import com.jts.gjcxfzksh.data.id.VehicleId;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 指标口径层：满载率 / 平均候车时间 / 车站300m覆盖率的唯一实现。
 * <p>
 * 口径约定（指标字典）：
 * <ul>
 *   <li>车站300m覆盖率：有任一活动点落在任一站点 300m 半径内的人数 ÷ 总人数，输出 0-100 百分数；无数据返回 null。</li>
 *   <li>平均候车时间：基于 plans 计划出发时间（非 events 实际时间），按候车开始时刻所在小时分桶，桶内累加求均值，单位秒。</li>
 *   <li>满载率（日周转系数口径）：车辆集合全天累计【上车】人次 ÷ 车辆静态容量（座位+站位）合计，输出小数；
 *       与公交行业“高峰断面满载率”不同，展示侧需注明口径。</li>
 *   <li>高峰/平峰发车间隔：时刻表相邻班次间隔的分窗均值，高峰窗=早高峰 7:00-9:00 + 晚高峰 17:00-19:00
 *       （行业口径），其余运营时段为平峰；间隔按中点时刻归窗，>2h 的间隔视为停开断档不计入
 *       （仅高峰运营线路的午间停开、夜班线按钟面排序产生的跨日假间隔）。</li>
 * </ul>
 * 任何口径调整必须同步升级依赖它的缓存版本（如 MatsimPrecomputedCache.VISUAL_CACHE_VERSION），否则旧值会继续下发。
 */
public final class TransitMetrics {

    private static final double COVERAGE_RADIUS = 300.0;

    /** 发车间隔口径参数：早晚高峰窗（时）与停开断档阈值（秒）。 */
    private static final double PEAK_AM_START_HOUR = 7.0;
    private static final double PEAK_AM_END_HOUR = 9.0;
    private static final double PEAK_PM_START_HOUR = 17.0;
    private static final double PEAK_PM_END_HOUR = 19.0;
    private static final double HEADWAY_BREAK_SECONDS = 2 * 3600.0;

    private TransitMetrics() {
    }

    /**
     * 高峰/平峰发车间隔（分钟）：从单方向时刻表的发车时刻序列自动识别。
     * <p>
     * 相邻班次间隔按中点钟面时刻（mod 24h，兼容 MATSim 跨日时刻）落入早晚高峰窗（7-9、17-19）计高峰，
     * 其余计平峰；超过 2 小时的间隔视为停开断档剔除（覆盖“仅高峰运营”的午间停开与夜班线跨日假间隔）。
     * 若全部间隔都超阈值（长间隔郊区线，班距本身就大于 2h），退化为不剔除全量计入，避免有班次却无间隔。
     *
     * @param sortedDepartureTimes 升序发车时刻（秒），可为空
     * @return {高峰间隔, 平峰间隔}（分钟，未四舍五入）；对应窗内无有效间隔时为 0，调用方应显示“暂无数据”
     */
    public static double[] peakOffPeakHeadwayMinutes(double[] sortedDepartureTimes) {
        double[] result = headwayMinutesByWindow(sortedDepartureTimes, HEADWAY_BREAK_SECONDS);
        if (result[0] == 0 && result[1] == 0) {
            result = headwayMinutesByWindow(sortedDepartureTimes, Double.MAX_VALUE);
        }
        return result;
    }

    private static double[] headwayMinutesByWindow(double[] times, double breakThresholdSeconds) {
        double peakSum = 0;
        int peakCount = 0;
        double offPeakSum = 0;
        int offPeakCount = 0;
        for (int i = 0; times != null && i + 1 < times.length; i++) {
            double gap = times[i + 1] - times[i];
            if (gap <= 0 || gap > breakThresholdSeconds) {
                continue; // 同刻重复班次不构成间隔；超阈值视为停开断档
            }
            double midHour = ((times[i] + gap / 2) / 3600.0) % 24;
            boolean inPeak = (midHour >= PEAK_AM_START_HOUR && midHour < PEAK_AM_END_HOUR)
                    || (midHour >= PEAK_PM_START_HOUR && midHour < PEAK_PM_END_HOUR);
            if (inPeak) {
                peakSum += gap;
                peakCount++;
            } else {
                offPeakSum += gap;
                offPeakCount++;
            }
        }
        return new double[]{
                peakCount > 0 ? peakSum / peakCount / 60.0 : 0.0,
                offPeakCount > 0 ? offPeakSum / offPeakCount / 60.0 : 0.0,
        };
    }

    /**
     * 车站300m覆盖率（被覆盖比例）。
     *
     * @return 0-100 的百分数（未四舍五入）；站点或人口为空时返回 null，调用方应输出“暂无数据”而非占位值
     */
    public static Double coverage300Percent(Set<Coord> stopCoords, Population population) {
        if (stopCoords == null || stopCoords.isEmpty()
                || population == null || population.getPersons().isEmpty()) {
            return null;
        }
        STRtree index = new STRtree();
        for (Coord coord : stopCoords) {
            index.insert(new Envelope(
                    coord.getX() - COVERAGE_RADIUS, coord.getX() + COVERAGE_RADIUS,
                    coord.getY() - COVERAGE_RADIUS, coord.getY() + COVERAGE_RADIUS
            ), coord);
        }
        index.build();
        long total = population.getPersons().size();
        long covered = ModelProcessingPool.count(
                population.getPersons().values(),
                person -> personInCoverage(person, index)
        );
        return covered * 100.0 / total;
    }

    /**
     * 覆盖率结果转展示结构；percent 为 null 时输出显式“暂无数据”标记（nodata=true），
     * 而非旧实现的 50/50 占位值（用户会把占位值当真实指标）。
     */
    public static Map<String, Object> coverageResult(Double percent) {
        Map<String, Object> coverage = new java.util.LinkedHashMap<>();
        if (percent == null) {
            coverage.put("cover", 0.0);
            coverage.put("notcover", 0.0);
            coverage.put("nodata", true);
            return coverage;
        }
        double cover = cn.hutool.core.util.NumberUtil.round(percent, 2).doubleValue();
        coverage.put("cover", cover);
        coverage.put("notcover", cn.hutool.core.util.NumberUtil.round(100.0 - cover, 2).doubleValue());
        return coverage;
    }

    private static boolean personInCoverage(Person person, STRtree index) {
        for (PlanElement element : person.getSelectedPlan().getPlanElements()) {
            if (element instanceof Activity act && act.getCoord() != null) {
                Coord coord = act.getCoord();
                Envelope env = new Envelope(coord.getX(), coord.getX(), coord.getY(), coord.getY());
                @SuppressWarnings("unchecked")
                List<Coord> nearStops = index.query(env);
                for (Coord stop : nearStops) {
                    double dx = stop.getX() - coord.getX();
                    double dy = stop.getY() - coord.getY();
                    if (dx * dx + dy * dy <= COVERAGE_RADIUS * COVERAGE_RADIUS) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 分时平均候车时间（秒）。候车时间 = PT leg 计划出发时间 - 上一段（步行到站）到达时间，
     * 按候车开始时刻所在小时分桶，桶内【累加】后取均值。
     * 跨零点时刻（MATSim 计划时间可 >86400，如 25:30）折叠回当日小时；
     * 计划数据异常产生的负候车时间样本直接丢弃，避免拉低均值。
     *
     * @return 长度 24 的数组，无样本的小时为 0（未四舍五入）
     */
    public static double[] avgAwaitTimeByHour(Population population) {
        double[] sum = new double[24];
        long[] count = new long[24];
        if (population != null) {
            for (Person person : population.getPersons().values()) {
                List<PlanElement> elements = person.getSelectedPlan().getPlanElements();
                for (int i = 0; i < elements.size(); i++) {
                    PlanElement element = elements.get(i);
                    if (!(element instanceof Leg leg) || !Constant.ROUTE_MODE_PT.equals(leg.getMode())) {
                        continue;
                    }
                    if (i < 2 || !leg.getDepartureTime().isDefined()) {
                        continue;
                    }
                    if (!(elements.get(i - 2) instanceof Leg previousLeg)
                            || !previousLeg.getDepartureTime().isDefined()
                            || !previousLeg.getTravelTime().isDefined()) {
                        continue;
                    }
                    double start = previousLeg.getDepartureTime().seconds() + previousLeg.getTravelTime().seconds();
                    double await = leg.getDepartureTime().seconds() - start;
                    if (start < 0 || await < 0) {
                        continue;
                    }
                    int hour = ((int) (start / 3600)) % 24;
                    sum[hour] += await;
                    count[hour]++;
                }
            }
        }
        double[] result = new double[24];
        for (int i = 0; i < 24; i++) {
            result[i] = count[i] == 0 ? 0.0 : sum[i] / count[i];
        }
        return result;
    }

    /**
     * 满载率（日周转系数口径），全网版本：对出现过乘降记录的全部车辆求
     * 累计上车人次 ÷ 静态容量合计。
     *
     * @return 小数（如 0.45 表示 45%），无有效车辆返回 0
     */
    public static double fullLoadRate(Map<VehicleId, List<PTPersonTrack>> tracksByVehicle,
                                      Map<Id<Vehicle>, Vehicle> vehicleMap) {
        return fullLoadRate(tracksByVehicle.keySet(), tracksByVehicle, vehicleMap, 1.0);
    }

    /**
     * 全网满载率，按人口抽样比例把乘客记录扩样到全量口径。
     */
    public static double fullLoadRate(Map<VehicleId, List<PTPersonTrack>> tracksByVehicle,
                                      Map<Id<Vehicle>, Vehicle> vehicleMap,
                                      double sampleRate) {
        return fullLoadRate(tracksByVehicle.keySet(), tracksByVehicle, vehicleMap, sampleRate);
    }

    /**
     * 满载率（日周转系数口径），指定车辆集合版本（如某条线路全部班次的车辆）。
     * 车辆去重后每车只计一次容量与上车人次；分子只统计上车（enter=true）记录。
     *
     * @return 小数（如 0.45 表示 45%），无有效车辆返回 0
     */
    public static double fullLoadRate(Collection<VehicleId> vehicleIds,
                                      Map<VehicleId, List<PTPersonTrack>> tracksByVehicle,
                                      Map<Id<Vehicle>, Vehicle> vehicleMap) {
        return fullLoadRate(vehicleIds, tracksByVehicle, vehicleMap, 1.0);
    }

    public static double fullLoadRate(Collection<VehicleId> vehicleIds,
                                      Map<VehicleId, List<PTPersonTrack>> tracksByVehicle,
                                      Map<Id<Vehicle>, Vehicle> vehicleMap,
                                      double sampleRate) {
        double capacity = 0.0;
        double boardings = 0.0;
        for (VehicleId vehId : new LinkedHashSet<>(vehicleIds)) {
            Vehicle vehicle = vehicleMap.get(vehId);
            if (vehicle == null || vehicle.getType() == null || vehicle.getType().getCapacity() == null) {
                continue;
            }
            VehicleType type = vehicle.getType();
            capacity += valueOf(type.getCapacity().getSeats());
            capacity += valueOf(type.getCapacity().getStandingRoom());
            List<PTPersonTrack> tracks = tracksByVehicle.get(vehId);
            if (tracks != null) {
                boardings += tracks.stream().filter(track -> Boolean.TRUE.equals(track.getEnter())).count();
            }
        }
        double normalizedSampleRate = sampleRate > 0.0 && sampleRate <= 1.0 ? sampleRate : 1.0;
        return capacity <= 0 ? 0.0 : (boardings / normalizedSampleRate) / capacity;
    }

    private static double valueOf(Integer value) {
        return value == null ? 0.0 : value;
    }

    /** 轨道制式判定：与 routeModeIndex 同一套正则，供"公交线网"剔除地铁线路 */
    private static boolean isRailMode(String transportMode) {
        return transportMode != null && transportMode.toLowerCase()
                .matches(".*(subway|metro|rail|train|轨道|地铁).*");
    }

    /**
     * MATSim 把一条双向道路存成两条方向相反的 Link。按无序节点对归并，
     * 双向都有线路经过的道路只计一次里程。
     * 同一节点对之间的平行 link 也会被并成一条（与 SnapRoutingService.roadNetwork 的底图口径一致）。
     */
    private static String undirectedRoadKey(org.matsim.api.core.v01.network.Link link) {
        String from = link.getFromNode().getId().toString();
        String to = link.getToNode().getId().toString();
        return from.compareTo(to) <= 0 ? from + "|" + to : to + "|" + from;
    }

    /**
     * 线网里程（米）：有线路经过的**道路**长度，而非各线路长度之和。
     *
     * @param excludeRail true = 只统计公共汽电车线路（"公交线网密度"口径，剔除地铁/轨道）
     */
    public static double networkLengthMeters(org.matsim.pt.transitSchedule.api.TransitSchedule schedule,
                                             org.matsim.api.core.v01.network.Network network,
                                             boolean excludeRail) {
        Set<String> seenRoads = new java.util.HashSet<>();
        double length = 0;
        for (org.matsim.pt.transitSchedule.api.TransitLine line : schedule.getTransitLines().values()) {
            for (org.matsim.pt.transitSchedule.api.TransitRoute route : line.getRoutes().values()) {
                if (excludeRail && isRailMode(route.getTransportMode())) {
                    continue;
                }
                org.matsim.core.population.routes.NetworkRoute networkRoute = route.getRoute();
                if (networkRoute == null) {
                    continue;
                }
                List<Id<org.matsim.api.core.v01.network.Link>> linkIds = new java.util.ArrayList<>();
                linkIds.add(networkRoute.getStartLinkId());
                linkIds.addAll(networkRoute.getLinkIds());
                linkIds.add(networkRoute.getEndLinkId());
                for (Id<org.matsim.api.core.v01.network.Link> linkId : linkIds) {
                    org.matsim.api.core.v01.network.Link link = network.getLinks().get(linkId);
                    // 线路引用了路网里不存在的 link（如独立 pt 网络）时跳过，而不是 NPE 打穿接口
                    if (link == null || !seenRoads.add(undirectedRoadKey(link))) {
                        continue;
                    }
                    length += link.getLength();
                }
            }
        }
        return length;
    }

    /**
     * 出行(trip)主方式优先级。数值越大越优先；未知方式取 1（高于步行、低于机动化），
     * 避免 MATSim DefaultAnalysisMainModeIdentifier 遇到未登记方式直接抛 IllegalStateException。
     */
    private static int mainModeRank(String mode) {
        if (mode == null) {
            return 1;
        }
        return switch (mode) {
            case "walk", "transit_walk", "access_walk", "egress_walk", "non_network_walk" -> 0;
            case "bike" -> 2;
            case "ride", "taxi", "drt", "motorcycle", "truck", Constant.ROUTE_MODE_CAR -> 3;
            case Constant.ROUTE_MODE_PT, "train", "subway", "rail", "tram", "ferry" -> 4;
            default -> 1;
        };
    }

    /** 一次出行的主方式：取该次出行内优先级最高的 leg 的 mode（含 pt 段即计为公交出行） */
    private static String tripMainMode(org.matsim.core.router.TripStructureUtils.Trip trip) {
        String mainMode = null;
        int bestRank = Integer.MIN_VALUE;
        for (Leg leg : trip.getLegsOnly()) {
            int rank = mainModeRank(leg.getMode());
            if (rank > bestRank) {
                bestRank = rank;
                mainMode = leg.getMode();
            }
        }
        return mainMode == null ? "walk" : mainMode;
    }

    /**
     * 按出行(trip)主方式统计的方式分担率（%）。
     *
     * 不能用 leg 数占比代替：output_plans 是路径规划之后的计划，一次公交出行会展开成
     * walk → pt (→ walk → pt)* → walk，接驳/换乘步行 leg 会进分母，带换乘的出行还会在
     * 分子里贡献多条 pt leg。TripStructureUtils.getTrips 以真实活动切分出行
     * （pt interaction 等过渡活动不算终点），一次出行只计一次。
     *
     * 比例保留 4 位小数再转百分数 → 百分比精确到 0.01%。
     */
    public static Map<String, Double> tripModeSharePercent(Population population) {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        int total = 0;
        if (population != null) {
            for (Person person : population.getPersons().values()) {
                org.matsim.api.core.v01.population.Plan plan = person.getSelectedPlan();
                if (plan == null) {
                    continue;
                }
                for (org.matsim.core.router.TripStructureUtils.Trip trip
                        : org.matsim.core.router.TripStructureUtils.getTrips(plan)) {
                    counts.merge(tripMainMode(trip), 1, Integer::sum);
                    total += 1;
                }
            }
        }
        Map<String, Double> share = new java.util.LinkedHashMap<>();
        if (total == 0) {
            return share;
        }
        java.math.BigDecimal denominator = java.math.BigDecimal.valueOf(total);
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            double percent = java.math.BigDecimal.valueOf(entry.getValue())
                    .divide(denominator, 4, java.math.RoundingMode.HALF_UP)
                    .multiply(java.math.BigDecimal.valueOf(100))
                    .doubleValue();
            share.put(entry.getKey(), percent);
        }
        return share;
    }

    /**
     * 服务区面积估算（km²）：站点坐标凸包面积。
     * 用于模型 desc.json 未提供 area（默认占位 1.0）时的密度类指标分母回退，
     * 坐标按项目统一投影 EPSG:3857 处理，按中心纬度做 cos² 面积畸变校正。
     *
     * @return km²；站点不足 3 个返回 null
     */
    public static Double serviceAreaKm2(Collection<Coord> stopCoords) {
        if (stopCoords == null || stopCoords.size() < 3) {
            return null;
        }
        org.locationtech.jts.geom.GeometryFactory factory = new org.locationtech.jts.geom.GeometryFactory();
        org.locationtech.jts.geom.Coordinate[] points = stopCoords.stream()
                .map(c -> new org.locationtech.jts.geom.Coordinate(c.getX(), c.getY()))
                .toArray(org.locationtech.jts.geom.Coordinate[]::new);
        org.locationtech.jts.geom.Geometry hull = factory.createMultiPointFromCoords(points).convexHull();
        double areaM2 = hull.getArea();
        if (!(areaM2 > 0)) {
            return null;
        }
        // Web Mercator 面积畸变校正：真实面积 = 投影面积 × cos²(纬度)
        double centroidY = hull.getCentroid().getY();
        double lat = Math.atan(Math.sinh(centroidY / 6378137.0));
        double corrected = areaM2 * Math.cos(lat) * Math.cos(lat);
        return corrected / 1_000_000.0;
    }

    /**
     * 高峰同时在营车辆数：以各班次 [发车时刻, 发车时刻+全程行驶时长] 为在营区间，
     * 求全天区间重叠峰值。MATSim GTFS 转换模型常为"每班次一辆车"，
     * 直接数车辆对象会把保有量放大一个数量级，此估算才是"标台数"的合理近似。
     */
    public static long peakConcurrentVehicles(org.matsim.pt.transitSchedule.api.TransitSchedule schedule) {
        List<double[]> events = new java.util.ArrayList<>();
        for (org.matsim.pt.transitSchedule.api.TransitLine line : schedule.getTransitLines().values()) {
            for (org.matsim.pt.transitSchedule.api.TransitRoute route : line.getRoutes().values()) {
                double duration = routeTravelSeconds(route);
                for (org.matsim.pt.transitSchedule.api.Departure departure : route.getDepartures().values()) {
                    double start = departure.getDepartureTime();
                    events.add(new double[]{start, 1});
                    events.add(new double[]{start + duration, -1});
                }
            }
        }
        events.sort((a, b) -> {
            int byTime = Double.compare(a[0], b[0]);
            // 同刻先出后进，避免瞬时交接被双计
            return byTime != 0 ? byTime : Double.compare(a[1], b[1]);
        });
        long current = 0;
        long peak = 0;
        for (double[] event : events) {
            current += (long) event[1];
            peak = Math.max(peak, current);
        }
        return peak;
    }

    private static double routeTravelSeconds(org.matsim.pt.transitSchedule.api.TransitRoute route) {
        List<org.matsim.pt.transitSchedule.api.TransitRouteStop> stops = route.getStops();
        if (stops == null || stops.isEmpty()) {
            return 0;
        }
        org.matsim.pt.transitSchedule.api.TransitRouteStop last = stops.get(stops.size() - 1);
        double offset = last.getArrivalOffset().orElse(last.getDepartureOffset().orElse(0));
        // 兜底：无 offset 数据时按 30 分钟计，避免区间长度为 0 导致峰值恒等于瞬时发车数
        return offset > 0 ? offset : 1800;
    }

    /**
     * 全天平均候车时间（分钟）：全部样本候车秒数之和 ÷ 样本数 ÷ 60。
     * 口径与 {@link #avgAwaitTimeByHour} 一致（基于 plans 计划时间）。
     */
    public static Double averageAwaitMinutes(Population population) {
        double sum = 0;
        long count = 0;
        if (population != null) {
            for (Person person : population.getPersons().values()) {
                List<PlanElement> elements = person.getSelectedPlan().getPlanElements();
                for (int i = 2; i < elements.size(); i++) {
                    if (!(elements.get(i) instanceof Leg leg) || !Constant.ROUTE_MODE_PT.equals(leg.getMode())
                            || !leg.getDepartureTime().isDefined()) {
                        continue;
                    }
                    if (!(elements.get(i - 2) instanceof Leg previousLeg)
                            || !previousLeg.getDepartureTime().isDefined()
                            || !previousLeg.getTravelTime().isDefined()) {
                        continue;
                    }
                    double start = previousLeg.getDepartureTime().seconds() + previousLeg.getTravelTime().seconds();
                    double await = leg.getDepartureTime().seconds() - start;
                    if (await < 0) { // 计划数据异常的负候车时间不参与均值（与 avgAwaitTimeByHour 口径一致）
                        continue;
                    }
                    sum += await;
                    count++;
                }
            }
        }
        return count == 0 ? null : sum / count / 60.0;
    }

    /**
     * 出行链换乘统计：按人分组、上车记录按时间排序，相邻上车间隔 ≤ windowSeconds 视为同一出行链内的换乘。
     *
     * @param modeOf routeId → 交通方式（bus/subway/...），用于公交-轨道接驳判定；可返回 null
     */
    public static TransferStats transferStats(Collection<PTPersonTrack> tracks,
                                              java.util.function.Function<Object, String> modeOf,
                                              int windowSeconds) {
        Map<Object, List<PTPersonTrack>> byPerson = new java.util.HashMap<>();
        if (tracks != null) {
            for (PTPersonTrack track : tracks) {
                if (!Boolean.TRUE.equals(track.getEnter()) || track.getPersonId() == null) {
                    continue;
                }
                byPerson.computeIfAbsent(track.getPersonId(), ignored -> new java.util.ArrayList<>()).add(track);
            }
        }
        long chains = 0;
        long boardings = 0;
        long busChains = 0;
        long busRailChains = 0;
        for (List<PTPersonTrack> personTracks : byPerson.values()) {
            personTracks.sort(java.util.Comparator.comparingDouble(t -> t.getTime() == null ? 0.0 : t.getTime()));
            double lastTime = Double.NEGATIVE_INFINITY;
            boolean hasBus = false;
            boolean hasRail = false;
            int chainBoardings = 0;
            for (PTPersonTrack track : personTracks) {
                double time = track.getTime() == null ? 0.0 : track.getTime();
                if (chainBoardings > 0 && time - lastTime > windowSeconds) {
                    chains++;
                    boardings += chainBoardings;
                    if (hasBus) {
                        busChains++;
                        if (hasRail) {
                            busRailChains++;
                        }
                    }
                    chainBoardings = 0;
                    hasBus = false;
                    hasRail = false;
                }
                chainBoardings++;
                lastTime = time;
                String mode = modeOf == null || track.getRouteId() == null ? null : modeOf.apply(track.getRouteId());
                if ("subway".equalsIgnoreCase(mode) || "rail".equalsIgnoreCase(mode) || "metro".equalsIgnoreCase(mode)) {
                    hasRail = true;
                } else {
                    hasBus = true;
                }
            }
            if (chainBoardings > 0) {
                chains++;
                boardings += chainBoardings;
                if (hasBus) {
                    busChains++;
                    if (hasRail) {
                        busRailChains++;
                    }
                }
            }
        }
        return new TransferStats(chains, boardings, busChains, busRailChains);
    }

    /**
     * @param chains        出行链总数
     * @param boardings     出行链内上车总次数
     * @param busChains     含常规公交乘次的出行链数
     * @param busRailChains 同时含公交与轨道乘次的出行链数（公交-轨道接驳）
     */
    public record TransferStats(long chains, long boardings, long busChains, long busRailChains) {

        /** 平均换乘次数 = (上车总次数 - 出行链数) / 出行链数；无数据返回 null */
        public Double averageTransfers() {
            return chains == 0 ? null : (double) (boardings - chains) / chains;
        }

        /** 公交-轨道接驳比例（%）= 公交+轨道混合链 / 含公交链；无数据返回 null */
        public Double busRailRatioPercent() {
            return busChains == 0 ? null : busRailChains * 100.0 / busChains;
        }
    }
}
