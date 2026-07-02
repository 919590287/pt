package com.jts.gjcxfzksh.utils;

import com.jts.gjcxfzksh.api.common.Constant;
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
 * </ul>
 * 任何口径调整必须同步升级依赖它的缓存版本（如 MatsimPrecomputedCache.VISUAL_CACHE_VERSION），否则旧值会继续下发。
 */
public final class TransitMetrics {

    private static final double COVERAGE_RADIUS = 300.0;

    private TransitMetrics() {
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
        long covered = population.getPersons().values().parallelStream()
                .filter(person -> personInCoverage(person, index))
                .count();
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
                    int hour = (int) (start / 3600);
                    if (hour >= 0 && hour < 24) {
                        sum[hour] += leg.getDepartureTime().seconds() - start;
                        count[hour]++;
                    }
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
        return fullLoadRate(tracksByVehicle.keySet(), tracksByVehicle, vehicleMap);
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
        return capacity <= 0 ? 0.0 : boardings / capacity;
    }

    private static double valueOf(Integer value) {
        return value == null ? 0.0 : value;
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
        double[] byHour = avgAwaitTimeByHour(population);
        // avgAwaitTimeByHour 已按桶均值输出，这里需要原始加权：重算一遍累计值
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
                    sum += leg.getDepartureTime().seconds() - start;
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
