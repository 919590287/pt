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
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.pt.routes.TransitPassengerRoute;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 指标口径层：满载率 / 平均候车时间 / 车站300m覆盖率的唯一实现。
 * <p>
 * 口径约定（指标字典）：
 * <ul>
 *   <li>车站300m居住人口覆盖率：每人只取首个有效且非 stage 的 home/home_* 坐标，
 *       落在任一站点地面距离 300m 内的有效 home 人数 ÷ 有有效 home 坐标的人数；
 *       同时披露缺失 home 人数，缺站点或有效 home 时返回 null。</li>
 *   <li>平均候车时间：基于 plans 计划出发时间（非 events 实际时间），按候车开始时刻所在小时分桶，桶内累加求均值，单位秒。</li>
 *   <li>线路平均高峰满载率：统计早晚高峰内的道路公交班次；每班先取各站段最大
 *       “在车人数 ÷ 额定载客量”，再对全部有效班次等权平均。</li>
 *   <li>高峰/平峰发车间隔：时刻表相邻班次间隔的分窗均值，高峰窗=早高峰 7:00-9:00 + 晚高峰 17:00-19:00
 *       （行业口径），其余运营时段为平峰；间隔按中点时刻归窗，>2h 的间隔视为停开断档不计入
 *       （仅高峰运营线路的午间停开、夜班线按钟面排序产生的跨日假间隔）。</li>
 * </ul>
 * 任何口径调整必须同步升级依赖它的缓存版本（如 MatsimPrecomputedCache.VISUAL_CACHE_VERSION），否则旧值会继续下发。
 */
public final class TransitMetrics {
    public static final String BUS_NETWORK_LENGTH_POLICY =
            "unique-road-centerline-undirected-links";
    public static final String BUS_NETWORK_AREA_POLICY =
            "administrative-total-area-temporary";
    public static final String PUBLIC_TRANSPORT_SHARE_POLICY =
            "public-transport-journeys-over-motorized-journeys";
    public static final String BUS_DAILY_TRIPS_POLICY =
            "road-bus-main-mode-journeys-over-resident-home-persons";
    public static final String PEAK_AVERAGE_LOAD_RATE_POLICY =
            "mean-of-peak-departure-maximum-segment-load-rates";
    public static final String BUS_PASSENGER_STRENGTH_POLICY =
            "road-bus-boardings-over-scheduled-vehicle-kilometers";
    public static final String BUS_FLEET_POLICY =
            "unique-scheduled-road-transit-vehicles";
    public static final String BUS_STANDARD_VEHICLE_POLICY =
            "official-length-coefficients";
    public static final String BUS_CAR_SPEED_RATIO_POLICY =
            "peak-road-bus-operating-distance-over-time-divided-by-peak-car-distance-over-time";
    public static final String BUS_WAIT_TIME_POLICY =
            "road-bus-passenger-boarding-wait-sum-over-valid-boardings";
    public static final String BUS_AVERAGE_TRANSFERS_POLICY =
            "transfers-within-bus-containing-od-journeys-over-bus-containing-od-journeys";
    public static final String BUS_RAIL_FEEDER_POLICY =
            "bus-and-rail-od-journeys-over-bus-containing-od-journeys";
    public static final String BUS_NON_LINEAR_POLICY =
            "mean-of-line-coefficients-line-direction-average-length-over-terminal-straight-distance";

    private static final double COVERAGE_RADIUS_GROUND_METERS = 300.0;
    private static final double WEB_MERCATOR_RADIUS = 6378137.0;

    /** 坐标度量上下文：先显式转换到 EPSG:3857，再做地面尺度校正。 */
    public static final class MetricCoordinateContext {
        private final org.matsim.core.utils.geometry.CoordinateTransformation toWebMercator;
        private final boolean identity;
        private final String sourceCrs;

        private MetricCoordinateContext(org.matsim.core.utils.geometry.CoordinateTransformation transformation,
                                        boolean identity, String sourceCrs) {
            this.toWebMercator = transformation;
            this.identity = identity;
            this.sourceCrs = sourceCrs;
        }

        public static MetricCoordinateContext fromCrs(String crs) {
            if (crs == null || crs.isBlank()) return unsupported();
            String normalized = crs.trim();
            if (normalized.equalsIgnoreCase("EPSG:3857")
                    || normalized.equalsIgnoreCase("EPSG:900913")) {
                return new MetricCoordinateContext(null, true, normalized);
            }
            try {
                var transformation = org.matsim.core.utils.geometry.transformations.TransformationFactory
                        .getCoordinateTransformation(normalized, "EPSG:3857");
                // 构造器可能延迟失败；用一个中性坐标探测，非法 CRS 直接转 unsupported。
                transformation.transform(new Coord(0, 0));
                return new MetricCoordinateContext(transformation, false, normalized);
            } catch (RuntimeException e) {
                return unsupported();
            }
        }

        public static MetricCoordinateContext webMercator() {
            return new MetricCoordinateContext(null, true, "EPSG:3857");
        }

        public static MetricCoordinateContext unsupported() {
            return new MetricCoordinateContext(null, false, null);
        }

        public boolean isSupported() {
            return identity || toWebMercator != null;
        }

        public String sourceCrs() {
            return sourceCrs;
        }

        public Coord toWebMercator(Coord coord) {
            if (coord == null || !isSupported()) return null;
            try {
                Coord projected = identity ? coord : toWebMercator.transform(coord);
                return projected != null && Double.isFinite(projected.getX())
                        && Double.isFinite(projected.getY()) ? projected : null;
            } catch (RuntimeException e) {
                return null;
            }
        }

        public double groundDistance(Coord first, Coord second) {
            Coord projectedFirst = toWebMercator(first);
            Coord projectedSecond = toWebMercator(second);
            return projectedFirst == null || projectedSecond == null
                    ? Double.NaN : webMercatorGroundDistance(projectedFirst, projectedSecond);
        }
    }

    /** 发车间隔口径参数：早晚高峰窗（时）与停开断档阈值（秒）。 */
    private static final double PEAK_AM_START_HOUR = 7.0;
    private static final double PEAK_AM_END_HOUR = 9.0;
    private static final double PEAK_PM_START_HOUR = 17.0;
    private static final double PEAK_PM_END_HOUR = 19.0;
    private static final double HEADWAY_BREAK_SECONDS = 2 * 3600.0;

    private TransitMetrics() {
    }

    /**
     * 系统统一早晚高峰小时窗：早高峰 [7,9)，晚高峰 [17,19)。
     * 小时先折叠到 0-23，供发车间隔、线路满载率等指标共用同一配置。
     */
    public static boolean isPeakHour(int hour) {
        int clockHour = Math.floorMod(hour, 24);
        return (clockHour >= PEAK_AM_START_HOUR && clockHour < PEAK_AM_END_HOUR)
                || (clockHour >= PEAK_PM_START_HOUR && clockHour < PEAK_PM_END_HOUR);
    }

    /** 与系统统一早晚高峰窗一致的秒时刻判定；跨日时刻按 24h 折回。 */
    public static boolean isPeakTimeSeconds(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) return false;
        int hour = (int) Math.floor((seconds % 86_400.0) / 3_600.0);
        return isPeakHour(hour);
    }

    /** plans/schedule 中公共交方式的统一别名判定（不区分汽电车与轨道）。 */
    public static boolean isTransitMode(String mode) {
        if (mode == null) return false;
        return switch (mode.toLowerCase(java.util.Locale.ROOT)) {
            case Constant.ROUTE_MODE_PT, "bus", "trolleybus", "brt", "subway", "metro", "rail",
                 "train", "light_rail", "tram", "ferry" -> true;
            default -> false;
        };
    }

    /**
     * 公共交通机动化出行分担率的分母方式。步行、自行车及其接驳别名不进入机动化分母；
     * 公共汽电车、轨道、轮渡、小汽车、出租车、网约车、摩托车、通勤班车等进入。
     */
    public static boolean isMotorizedMode(String mode) {
        if (mode == null || mode.isBlank()) return false;
        return switch (mode.toLowerCase(java.util.Locale.ROOT)) {
            case "walk", "transit_walk", "access_walk", "egress_walk", "non_network_walk",
                 "bike", "bicycle", "cycle", "ebike", "e-bike", "scooter" -> false;
            default -> true;
        };
    }

    /** 公共汽电车速度口径：pt 与道路公交别名，不混入 subway/rail 速度。 */
    public static boolean isRoadPublicTransportMode(String mode) {
        if (mode == null) return false;
        return switch (mode.toLowerCase(java.util.Locale.ROOT)) {
            case Constant.ROUTE_MODE_PT, "bus", "trolleybus", "brt" -> true;
            default -> false;
        };
    }

    /** 显式公共汽电车别名；不包含无法判断制式的泛化 pt。 */
    public static boolean isExplicitRoadPublicTransportMode(String mode) {
        if (mode == null) return false;
        return switch (mode.toLowerCase(java.util.Locale.ROOT)) {
            case "bus", "trolleybus", "brt" -> true;
            default -> false;
        };
    }

    /** 时刻表 route 的公共汽电车口径：仅显式 bus/trolleybus/brt。legacy pt 请使用 RoadTransitContext。 */
    public static boolean isRoadPublicTransportRoute(
            org.matsim.pt.transitSchedule.api.TransitRoute route) {
        return route != null && isExplicitRoadPublicTransportMode(route.getTransportMode());
    }

    /**
     * 公共汽电车公式的唯一制式上下文。所有依赖 schedule 的指标都复用同一份 route 判定，
     * 避免覆盖率按 bus、车队却按全制式等口径漂移。
     *
     * <p>legacy {@code pt} 只在 route/line 的可靠元数据明确指向 bus 时纳入；无法判断的
     * route 记入 unresolved，调用方必须把受影响的评价项标成 unsupported，而不是把 0 当真。</p>
     */
    public static final class RoadTransitContext {
        private final org.matsim.pt.transitSchedule.api.TransitSchedule schedule;
        private final Map<Id<org.matsim.pt.transitSchedule.api.TransitLine>,
                Set<Id<org.matsim.pt.transitSchedule.api.TransitRoute>>> roadRoutes;
        private final int unresolvedRoutes;
        private final long coordinateTransformFailures;

        private RoadTransitContext(org.matsim.pt.transitSchedule.api.TransitSchedule schedule) {
            this.schedule = schedule;
            Map<Id<org.matsim.pt.transitSchedule.api.TransitLine>,
                    Set<Id<org.matsim.pt.transitSchedule.api.TransitRoute>>> routes = new HashMap<>();
            int unresolved = 0;
            if (schedule != null) {
                for (org.matsim.pt.transitSchedule.api.TransitLine line
                        : schedule.getTransitLines().values()) {
                    Set<Id<org.matsim.pt.transitSchedule.api.TransitRoute>> road = new java.util.HashSet<>();
                    for (org.matsim.pt.transitSchedule.api.TransitRoute route : line.getRoutes().values()) {
                        Boolean classification = classifyRoadRoute(line, route);
                        if (Boolean.TRUE.equals(classification)) {
                            road.add(route.getId());
                        } else if (classification == null) {
                            unresolved++;
                        }
                    }
                    if (!road.isEmpty()) routes.put(line.getId(), Collections.unmodifiableSet(road));
                }
            }
            this.roadRoutes = Collections.unmodifiableMap(routes);
            this.unresolvedRoutes = unresolved;
            Object failures = schedule == null ? null
                    : schedule.getAttributes().getAttribute("coordinateTransformFailures");
            this.coordinateTransformFailures = failures instanceof Number number
                    ? Math.max(0L, number.longValue()) : 0L;
        }

        public static RoadTransitContext from(
                org.matsim.pt.transitSchedule.api.TransitSchedule schedule) {
            return new RoadTransitContext(schedule);
        }

        public boolean isRoadRoute(Id<org.matsim.pt.transitSchedule.api.TransitLine> lineId,
                                   Id<org.matsim.pt.transitSchedule.api.TransitRoute> routeId) {
            if (lineId == null || routeId == null) return false;
            Set<Id<org.matsim.pt.transitSchedule.api.TransitRoute>> routes = roadRoutes.get(lineId);
            return routes != null && routes.contains(routeId);
        }

        public boolean isRoadRoute(org.matsim.pt.transitSchedule.api.TransitLine line,
                                   org.matsim.pt.transitSchedule.api.TransitRoute route) {
            return line != null && route != null && isRoadRoute(line.getId(), route.getId());
        }

        public boolean isRoadTrack(PTPersonTrack track) {
            return track != null && isRoadRoute(track.getLineId(), track.getRouteId());
        }

        public int unresolvedRoutes() {
            return unresolvedRoutes;
        }

        public boolean isComplete() {
            return unresolvedRoutes == 0;
        }

        public long coordinateTransformFailures() {
            return coordinateTransformFailures;
        }

        public Set<Coord> stopCoords() {
            Set<Coord> result = new LinkedHashSet<>();
            if (schedule == null) return result;
            for (org.matsim.pt.transitSchedule.api.TransitLine line
                    : schedule.getTransitLines().values()) {
                for (org.matsim.pt.transitSchedule.api.TransitRoute route : line.getRoutes().values()) {
                    if (!isRoadRoute(line, route)) continue;
                    for (org.matsim.pt.transitSchedule.api.TransitRouteStop stop : route.getStops()) {
                        if (stop.getStopFacility() != null && stop.getStopFacility().getCoord() != null) {
                            result.add(stop.getStopFacility().getCoord());
                        }
                    }
                }
            }
            return result;
        }

        public long departureCount() {
            long count = 0;
            if (schedule == null) return 0;
            for (org.matsim.pt.transitSchedule.api.TransitLine line
                    : schedule.getTransitLines().values()) {
                for (org.matsim.pt.transitSchedule.api.TransitRoute route : line.getRoutes().values()) {
                    if (isRoadRoute(line, route)) count += route.getDepartures().size();
                }
            }
            return count;
        }

        public int lineCount() {
            return roadRoutes.size();
        }

        public long boardingCount(Collection<PTPersonTrack> tracks) {
            if (tracks == null) return 0;
            return tracks.stream().filter(this::isRoadTrack)
                    .filter(track -> Boolean.TRUE.equals(track.getEnter())).count();
        }

        public double routeLengthMeters(org.matsim.api.core.v01.network.Network network) {
            if (schedule == null) return 0.0;
            double result = 0.0;
            for (org.matsim.pt.transitSchedule.api.TransitLine line
                    : schedule.getTransitLines().values()) {
                for (org.matsim.pt.transitSchedule.api.TransitRoute route : line.getRoutes().values()) {
                    if (isRoadRoute(line, route)) {
                        result += TransitMetrics.routeLengthMeters(route.getRoute(), network);
                    }
                }
            }
            return result;
        }

        public RoadFleetStats fleetStats() {
            return roadFleetStats(schedule, this);
        }
    }

    private static Boolean classifyRoadRoute(
            org.matsim.pt.transitSchedule.api.TransitLine line,
            org.matsim.pt.transitSchedule.api.TransitRoute route) {
        if (route == null) return false;
        String mode = normalize(route.getTransportMode());
        if (isExplicitRoadPublicTransportMode(mode)) return true;
        if (mode != null && !mode.isBlank() && !Constant.ROUTE_MODE_PT.equals(mode)) return false;

        Boolean attributes = classifyRouteMetadata(route.getAttributes(), line == null ? null : line.getAttributes());
        if (attributes != null) return attributes;
        String identity = (line == null ? "" : line.getId() + " ") + route.getId();
        String normalized = identity.toLowerCase(java.util.Locale.ROOT);
        if (normalized.matches(".*(^|[^a-z])(bus|trolleybus|brt)([^a-z]|$).*")
                || normalized.contains("公交") || normalized.contains("巴士") || normalized.contains("汽电车")) {
            return true;
        }
        if (isRailTransitMode(normalized) || normalized.contains("地铁") || normalized.contains("轨道")) {
            return false;
        }
        return null;
    }

    private static Boolean classifyRouteMetadata(org.matsim.utils.objectattributes.attributable.Attributes... sources) {
        for (org.matsim.utils.objectattributes.attributable.Attributes source : sources) {
            if (source == null) continue;
            for (String key : List.of("transportMode", "transport_mode", "mode", "routeMode", "route_mode")) {
                Object value = source.getAttribute(key);
                if (value == null) continue;
                String mode = normalize(String.valueOf(value));
                if (isExplicitRoadPublicTransportMode(mode)) return true;
                if (isTransitMode(mode) && !Constant.ROUTE_MODE_PT.equals(mode)) return false;
            }
            for (String key : List.of("gtfs_route_type", "route_type", "routeType")) {
                Object value = source.getAttribute(key);
                if (value == null) continue;
                try {
                    int type = Integer.parseInt(String.valueOf(value));
                    if (type == 3 || type == 11 || (type >= 700 && type < 800)) return true;
                    if (type == 0 || type == 1 || type == 2 || type == 4
                            || (type >= 900 && type < 1200)) return false;
                } catch (NumberFormatException ignored) {
                    // 非数值 route_type 继续按 mode 文本判断。
                    String mode = normalize(String.valueOf(value));
                    if (isExplicitRoadPublicTransportMode(mode)) return true;
                    if (isTransitMode(mode) && !Constant.ROUTE_MODE_PT.equals(mode)) return false;
                }
            }
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** 轨道制式判定，不把泛化的 legacy pt 当成轨道。 */
    public static boolean isRailTransitMode(String transportMode) {
        return transportMode != null && transportMode.toLowerCase(java.util.Locale.ROOT)
                .matches(".*(subway|metro|rail|train|tram|light.?rail|轨道|地铁|有轨).*");
    }

    /** 公共汽电车线路实际经过的站点坐标（排除 subway/rail 及无法解析的 pt）。 */
    public static Set<Coord> roadPublicTransportStopCoords(
            org.matsim.pt.transitSchedule.api.TransitSchedule schedule) {
        return RoadTransitContext.from(schedule).stopCoords();
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
            boolean inPeak = isPeakHour((int) Math.floor(midHour));
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

    /** 300m 覆盖的可对账结果；分母只包含有有效 home 坐标的人。 */
    public record CoverageStats(long coveredPersons, long validHomePersons, Double percent) {
    }

    private record CoverageStop(Coord coord, double projectedRadiusMeters) {
    }

    /**
     * EPSG:3857 中与地面距离等价的投影半径。Web Mercator 线性尺度为
     * {@code 1/cos(latitude)}，因此广州纬度下的地面 300m 约对应投影 326m。
     */
    public static double webMercatorRadiusForGroundMeters(double mercatorY, double groundMeters) {
        if (!Double.isFinite(mercatorY) || !Double.isFinite(groundMeters) || groundMeters < 0) {
            return Double.NaN;
        }
        double latitude = Math.atan(Math.sinh(mercatorY / WEB_MERCATOR_RADIUS));
        double cosine = Math.cos(latitude);
        return cosine > 0 ? groundMeters / cosine : Double.NaN;
    }

    /**
     * 车站 300m 居住覆盖率。每人只取首个有效、非 stage 的 home/home_* 活动；
     * work/shop 靠近车站不会把居民误判为已覆盖，无 home 坐标者也不进入分母。
     */
    public static CoverageStats coverage300Stats(Set<Coord> stopCoords, Population population) {
        return coverage300Stats(stopCoords, population, MetricCoordinateContext.webMercator());
    }

    public static CoverageStats coverage300Stats(Set<Coord> stopCoords, Population population,
                                                  MetricCoordinateContext coordinates) {
        if (population == null || population.getPersons().isEmpty()) {
            return new CoverageStats(0, 0, null);
        }
        if (coordinates == null || !coordinates.isSupported()) {
            return new CoverageStats(0, 0, null);
        }
        STRtree index = new STRtree();
        int indexedStops = 0;
        if (stopCoords != null) {
            for (Coord coord : stopCoords) {
                coord = coordinates.toWebMercator(coord);
                if (coord == null) continue;
                double radius = webMercatorRadiusForGroundMeters(
                        coord.getY(), COVERAGE_RADIUS_GROUND_METERS);
                if (!Double.isFinite(radius) || radius <= 0) continue;
                index.insert(new Envelope(
                        coord.getX() - radius, coord.getX() + radius,
                        coord.getY() - radius, coord.getY() + radius
                ), new CoverageStop(coord, radius));
                indexedStops++;
            }
        }
        index.build();

        long validHomes = 0;
        long covered = 0;
        for (Person person : population.getPersons().values()) {
            Coord home = firstValidHomeCoord(person);
            if (home == null) continue;
            Coord projectedHome = coordinates.toWebMercator(home);
            if (projectedHome == null) continue;
            validHomes++;
            if (indexedStops > 0 && pointInCoverage(projectedHome, index)) covered++;
        }
        Double percent = indexedStops == 0 || validHomes == 0
                ? null : covered * 100.0 / validHomes;
        return new CoverageStats(covered, validHomes, percent);
    }

    /**
     * 常住人口口径：selected plan（缺失时回退首个 plan）中存在首个有效、非 stage 的
     * {@code home/home_*} 活动坐标的人数。它不依赖公交站或坐标系识别，可直接用于
     * “常住人口密度”的分子。
     */
    public static long residentHomePersonCount(Population population) {
        if (population == null || population.getPersons().isEmpty()) return 0L;
        return population.getPersons().values().stream()
                .filter(person -> firstValidHomeCoord(person) != null)
                .count();
    }

    /** @return 0-100 的百分数（未四舍五入）；缺站点或有效 home 样本时返回 null。 */
    public static Double coverage300Percent(Set<Coord> stopCoords, Population population) {
        return coverage300Stats(stopCoords, population).percent();
    }

    public static Double coverage300Percent(Set<Coord> stopCoords, Population population,
                                            MetricCoordinateContext coordinates) {
        return coverage300Stats(stopCoords, population, coordinates).percent();
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

    private static Coord firstValidHomeCoord(Person person) {
        if (person == null) return null;
        org.matsim.api.core.v01.population.Plan plan = person.getSelectedPlan();
        if (plan == null && !person.getPlans().isEmpty()) plan = person.getPlans().getFirst();
        if (plan == null) return null;
        for (PlanElement element : plan.getPlanElements()) {
            if (!(element instanceof Activity activity) || activity.getCoord() == null) continue;
            String type = activity.getType();
            if (type == null || TripStructureUtils.isStageActivityType(type)) continue;
            String normalized = type.toLowerCase(java.util.Locale.ROOT);
            if ("home".equals(normalized) || normalized.startsWith("home_")) {
                Coord coord = activity.getCoord();
                if (Double.isFinite(coord.getX()) && Double.isFinite(coord.getY())) {
                    return coord;
                }
            }
        }
        return null;
    }

    private static boolean pointInCoverage(Coord point, STRtree index) {
        Envelope envelope = new Envelope(point.getX(), point.getX(), point.getY(), point.getY());
        @SuppressWarnings("unchecked")
        List<CoverageStop> nearStops = index.query(envelope);
        for (CoverageStop stop : nearStops) {
            double dx = stop.coord().getX() - point.getX();
            double dy = stop.coord().getY() - point.getY();
            if (dx * dx + dy * dy <= stop.projectedRadiusMeters() * stop.projectedRadiusMeters()) {
                return true;
            }
        }
        return false;
    }

    /** 单条 transit leg 的可靠候车样本（秒）。 */
    public record WaitSample(double startSeconds, double waitSeconds) {
    }

    /**
     * 路由中明确编码的候车时间。V6 的 bus/subway leg departure 是到站时刻，
     * {@link TransitPassengerRoute#getBoardingTime()} 才是上车时刻。
     */
    public static Double boardingWaitSeconds(Leg leg) {
        if (leg == null || !isTransitMode(leg.getMode()) || !leg.getDepartureTime().isDefined()
                || !(leg.getRoute() instanceof TransitPassengerRoute passengerRoute)
                || !passengerRoute.getBoardingTime().isDefined()) {
            return null;
        }
        double wait = passengerRoute.getBoardingTime().seconds() - leg.getDepartureTime().seconds();
        return Double.isFinite(wait) && wait >= 0 ? wait : null;
    }

    /**
     * transit leg 的候车样本：优先使用 boardingTime。仅在缺该字段、且前序确为
     * pt interaction 并能算出严格正值时，才用历史“leg departure - access arrival”口径兜底。
     */
    public static WaitSample waitSample(List<? extends PlanElement> elements, int legIndex) {
        if (elements == null || legIndex < 0 || legIndex >= elements.size()
                || !(elements.get(legIndex) instanceof Leg leg)
                || !isTransitMode(leg.getMode()) || !leg.getDepartureTime().isDefined()) {
            return null;
        }
        Double routeWait = boardingWaitSeconds(leg);
        if (routeWait != null) {
            return new WaitSample(leg.getDepartureTime().seconds(), routeWait);
        }
        if (legIndex < 2 || !(elements.get(legIndex - 1) instanceof Activity interaction)
                || interaction.getType() == null
                || !TripStructureUtils.isStageActivityType(interaction.getType())
                || !(elements.get(legIndex - 2) instanceof Leg access)
                || !access.getDepartureTime().isDefined() || !access.getTravelTime().isDefined()) {
            return null;
        }
        double start = access.getDepartureTime().seconds() + access.getTravelTime().seconds();
        double wait = leg.getDepartureTime().seconds() - start;
        // 缺 boardingTime 时不把结构性 0 当成真实“零候车”。
        return start >= 0 && Double.isFinite(wait) && wait > 0
                ? new WaitSample(start, wait) : null;
    }

    /**
     * 公共汽电车车内运行时间（秒）：从 leg travelTime 扣除可靠的候车时间。
     * 无 boardingTime 时不猜测，返回 null 让评价指标显式 nodata。
     */
    public static Double inVehicleTravelSeconds(Leg leg) {
        if (leg == null || !isRoadPublicTransportMode(leg.getMode())
                || !leg.getTravelTime().isDefined()) {
            return null;
        }
        Double wait = boardingWaitSeconds(leg);
        if (wait == null) return null;
        double inVehicle = leg.getTravelTime().seconds() - wait;
        return Double.isFinite(inVehicle) && inVehicle > 0 ? inVehicle : null;
    }

    /**
     * 分时平均候车时间（秒），按候车开始时刻所在小时分桶。
     * @return 长度 24 的数组，无样本的小时为 0（未四舍五入）
     */
    public static double[] avgAwaitTimeByHour(Population population) {
        double[] sum = new double[24];
        long[] count = new long[24];
        if (population != null) {
            for (Person person : population.getPersons().values()) {
                List<PlanElement> elements = person.getSelectedPlan().getPlanElements();
                for (int i = 0; i < elements.size(); i++) {
                    WaitSample sample = waitSample(elements, i);
                    if (sample == null) continue;
                    int hour = Math.floorMod((int) (sample.startSeconds() / 3600), 24);
                    sum[hour] += sample.waitSeconds();
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

    /** DB4401/T 180—2022 线路平均高峰满载率的可审计统计结果。 */
    public record PeakAverageLoadStats(
            Double percent,
            long scheduledPeakDepartures,
            long validCapacityDepartures,
            long missingCapacityDepartures) {
    }

    /**
     * 高峰班次满载率单遍聚合器：每个班次先由上下车事件还原各站段在车人数并取最大值，
     * 再对全部高峰班次的“最大站段在车人数/额定载客量”等权平均。
     */
    public static final class PeakAverageLoadAccumulator {
        private final Map<String, DeparturePeakState> departures = new HashMap<>();
        private final boolean unorderedEvents;
        private long scheduledPeakDepartures;
        private long missingCapacityDepartures;

        private PeakAverageLoadAccumulator(
                org.matsim.pt.transitSchedule.api.TransitSchedule schedule,
                org.matsim.vehicles.Vehicles vehicles,
                RoadTransitContext roadTransit,
                boolean unorderedEvents) {
            this.unorderedEvents = unorderedEvents;
            if (schedule == null || vehicles == null || roadTransit == null) return;
            for (org.matsim.pt.transitSchedule.api.TransitLine line
                    : schedule.getTransitLines().values()) {
                for (org.matsim.pt.transitSchedule.api.TransitRoute route
                        : line.getRoutes().values()) {
                    if (!roadTransit.isRoadRoute(line, route)) continue;
                    indexRoute(line.getId(), route, vehicles);
                }
            }
        }

        private PeakAverageLoadAccumulator(
                Object lineId,
                org.matsim.pt.transitSchedule.api.TransitRoute route,
                org.matsim.vehicles.Vehicles vehicles,
                boolean unorderedEvents) {
            this.unorderedEvents = unorderedEvents;
            if (route != null && vehicles != null) indexRoute(lineId, route, vehicles);
        }

        private void indexRoute(
                Object lineId,
                org.matsim.pt.transitSchedule.api.TransitRoute route,
                org.matsim.vehicles.Vehicles vehicles) {
            for (org.matsim.pt.transitSchedule.api.Departure departure
                    : route.getDepartures().values()) {
                int hour = Math.floorMod(
                        (int) Math.floor(departure.getDepartureTime() / 3600.0), 24);
                if (!isPeakHour(hour)) continue;
                scheduledPeakDepartures++;
                Vehicle vehicle = departure.getVehicleId() == null
                        ? null : vehicles.getVehicles().get(departure.getVehicleId());
                double capacity = vehicleCapacity(vehicle);
                if (!(capacity > 0)) {
                    missingCapacityDepartures++;
                    continue;
                }
                departures.put(departureKey(lineId, route.getId(), departure.getId()),
                        new DeparturePeakState(capacity, unorderedEvents));
            }
        }

        public static PeakAverageLoadAccumulator roadBus(
                org.matsim.pt.transitSchedule.api.TransitSchedule schedule,
                org.matsim.vehicles.Vehicles vehicles,
                RoadTransitContext roadTransit,
                boolean unorderedEvents) {
            return new PeakAverageLoadAccumulator(schedule, vehicles, roadTransit, unorderedEvents);
        }

        public static PeakAverageLoadAccumulator route(
                Object lineId,
                org.matsim.pt.transitSchedule.api.TransitRoute route,
                org.matsim.vehicles.Vehicles vehicles,
                boolean unorderedEvents) {
            return new PeakAverageLoadAccumulator(lineId, route, vehicles, unorderedEvents);
        }

        public void accept(PTPersonTrack track) {
            if (track == null || track.getEnter() == null) return;
            DeparturePeakState state = departures.get(departureKey(
                    track.getLineId(), track.getRouteId(), track.getDepartureId()));
            if (state == null) return;
            double time = track.getTime() == null || !Double.isFinite(track.getTime())
                    ? 0.0 : track.getTime();
            state.accept(time, Boolean.TRUE.equals(track.getEnter()) ? 1 : -1);
        }

        public PeakAverageLoadStats finish() {
            long valid = departures.size();
            if (scheduledPeakDepartures == 0) {
                return new PeakAverageLoadStats(null, 0, 0, 0);
            }
            if (missingCapacityDepartures > 0 || valid == 0) {
                return new PeakAverageLoadStats(
                        null, scheduledPeakDepartures, valid, missingCapacityDepartures);
            }
            double rateSum = departures.values().stream()
                    .mapToDouble(DeparturePeakState::rate)
                    .sum();
            return new PeakAverageLoadStats(
                    rateSum * 100.0 / valid,
                    scheduledPeakDepartures,
                    valid,
                    0);
        }
    }

    private static final class DeparturePeakState {
        private final double capacity;
        private final java.util.NavigableMap<Double, Integer> deltas;
        private double pendingTime = Double.NaN;
        private int pendingDelta;
        private int current;
        private int peak;

        private DeparturePeakState(double capacity, boolean unorderedEvents) {
            this.capacity = capacity;
            this.deltas = unorderedEvents ? new java.util.TreeMap<>() : null;
        }

        private void accept(double time, int delta) {
            if (deltas != null) {
                deltas.merge(time, delta, Integer::sum);
                return;
            }
            if (Double.isNaN(pendingTime)) {
                pendingTime = time;
                pendingDelta = delta;
                return;
            }
            if (Double.compare(time, pendingTime) == 0) {
                pendingDelta += delta;
                return;
            }
            flushPending();
            pendingTime = time;
            pendingDelta = delta;
        }

        private void flushPending() {
            if (Double.isNaN(pendingTime)) return;
            current = Math.max(0, current + pendingDelta);
            peak = Math.max(peak, current);
            pendingTime = Double.NaN;
            pendingDelta = 0;
        }

        private double rate() {
            if (deltas != null) {
                int occupancy = 0;
                int maximum = 0;
                for (int delta : deltas.values()) {
                    occupancy = Math.max(0, occupancy + delta);
                    maximum = Math.max(maximum, occupancy);
                }
                peak = maximum;
            } else {
                flushPending();
            }
            return peak / capacity;
        }
    }

    private static String departureKey(Object lineId, Object routeId, Object departureId) {
        return String.valueOf(lineId) + '\u001F'
                + String.valueOf(routeId) + '\u001F'
                + String.valueOf(departureId);
    }

    /**
     * 公共汽电车计划运营车公里：每条道路公交运行路径长度乘该路径日发班数后求和。
     * 任一有班次路径缺失有效几何时返回 null，不能以下发剩余路径的部分真值代替。
     */
    public record RoadOperatingDistanceStats(
            Double vehicleKilometers, long departures, int missingGeometryRoutes) {
    }

    public static RoadOperatingDistanceStats roadOperatingDistanceStats(
            org.matsim.pt.transitSchedule.api.TransitSchedule schedule,
            org.matsim.api.core.v01.network.Network network,
            RoadTransitContext roadTransit) {
        if (schedule == null || network == null || roadTransit == null) {
            return new RoadOperatingDistanceStats(null, 0, 0);
        }
        double vehicleMeters = 0.0;
        long departures = 0;
        int missing = 0;
        for (org.matsim.pt.transitSchedule.api.TransitLine line
                : schedule.getTransitLines().values()) {
            for (org.matsim.pt.transitSchedule.api.TransitRoute route
                    : line.getRoutes().values()) {
                if (!roadTransit.isRoadRoute(line, route) || route.getDepartures().isEmpty()) continue;
                long routeDepartures = route.getDepartures().size();
                departures += routeDepartures;
                Double lengthMeters = strictRouteLengthMeters(route.getRoute(), network);
                if (lengthMeters == null || !(lengthMeters > 0)) {
                    missing++;
                    continue;
                }
                vehicleMeters += lengthMeters * routeDepartures;
            }
        }
        Double kilometers = missing > 0 || departures == 0 || !(vehicleMeters > 0)
                ? null : vehicleMeters / 1000.0;
        return new RoadOperatingDistanceStats(kilometers, departures, missing);
    }

    /** 公共汽电车线路客流强度（人次/车公里）。 */
    public static Double busPassengerStrength(
            double dailyBoardings, RoadOperatingDistanceStats operatingDistance) {
        if (operatingDistance == null || operatingDistance.vehicleKilometers() == null
                || !(operatingDistance.vehicleKilometers() > 0)) {
            return null;
        }
        return Math.max(0.0, dailyBoardings) / operatingDistance.vehicleKilometers();
    }

    /**
     * 旧“车辆峰值满载率”，保留给不表示线路平均高峰满载率的明细监测字段。
     * 体检评估不得使用本方法。
     */
    public static double fullLoadRate(Map<VehicleId, List<PTPersonTrack>> tracksByVehicle,
                                      Map<Id<Vehicle>, Vehicle> vehicleMap) {
        if (tracksByVehicle == null) {
            return 0.0;
        }
        return fullLoadRate(tracksByVehicle.keySet(), tracksByVehicle, vehicleMap);
    }

    /**
     * @deprecated 数量口径已固定为模型原始值，sampleRate 仅为源数据元信息，不再参与任何计算。
     */
    @Deprecated
    public static double fullLoadRate(Map<VehicleId, List<PTPersonTrack>> tracksByVehicle,
                                      Map<Id<Vehicle>, Vehicle> vehicleMap,
                                      double sampleRate) {
        return fullLoadRate(tracksByVehicle, vehicleMap);
    }

    /**
     * 指定车辆集合版本（如某条线路全部班次的车辆）。车辆 ID 去重，
     * 每车按时间聚合乘客事件并还原峰值在车人数。
     *
     * @return 小数（如 0.45 表示 45%），无有效车辆返回 0
     */
    public static double fullLoadRate(Collection<VehicleId> vehicleIds,
                                      Map<VehicleId, List<PTPersonTrack>> tracksByVehicle,
                                      Map<Id<Vehicle>, Vehicle> vehicleMap) {
        if (vehicleIds == null || tracksByVehicle == null || vehicleMap == null) {
            return 0.0;
        }
        double maximumRate = 0.0;
        for (VehicleId vehId : new LinkedHashSet<>(vehicleIds)) {
            Vehicle vehicle = vehicleMap.get(vehId);
            double capacity = vehicleCapacity(vehicle);
            if (capacity <= 0) {
                continue;
            }
            int peakOccupancy = peakOccupancy(tracksByVehicle.get(vehId));
            maximumRate = Math.max(maximumRate, peakOccupancy / capacity);
        }
        return maximumRate;
    }

    /** @deprecated sampleRate 不再参与计算，仅保留签名兼容旧调用方。 */
    @Deprecated
    public static double fullLoadRate(Collection<VehicleId> vehicleIds,
                                      Map<VehicleId, List<PTPersonTrack>> tracksByVehicle,
                                      Map<Id<Vehicle>, Vehicle> vehicleMap,
                                      double sampleRate) {
        return fullLoadRate(vehicleIds, tracksByVehicle, vehicleMap);
    }

    private static int peakOccupancy(List<PTPersonTrack> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            return 0;
        }
        java.util.NavigableMap<Double, Integer> deltas = new java.util.TreeMap<>();
        for (PTPersonTrack track : tracks) {
            if (track == null || track.getEnter() == null) {
                continue;
            }
            double time = track.getTime() == null || !Double.isFinite(track.getTime()) ? 0.0 : track.getTime();
            deltas.merge(time, Boolean.TRUE.equals(track.getEnter()) ? 1 : -1, Integer::sum);
        }
        int occupancy = 0;
        int peak = 0;
        for (int delta : deltas.values()) {
            occupancy = Math.max(0, occupancy + delta);
            peak = Math.max(peak, occupancy);
        }
        return peak;
    }

    private static double vehicleCapacity(Vehicle vehicle) {
        if (vehicle == null || vehicle.getType() == null || vehicle.getType().getCapacity() == null) {
            return 0.0;
        }
        VehicleType type = vehicle.getType();
        return valueOf(type.getCapacity().getSeats()) + valueOf(type.getCapacity().getStandingRoom());
    }

    private static double valueOf(Integer value) {
        return value == null ? 0.0 : value;
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
     * MATSim NetworkRoute 运营里程（米）：严格累加 link.length，不用 EPSG:3857
     * 节点直线距离代替。startLinkId == endLinkId 时只计一次；中间路由的真实回环不去重。
     */
    public static double routeLengthMeters(NetworkRoute route,
                                           org.matsim.api.core.v01.network.Network network) {
        if (route == null || network == null) return 0.0;
        double length = linkLength(route.getStartLinkId(), network);
        for (Id<org.matsim.api.core.v01.network.Link> linkId : route.getLinkIds()) {
            length += linkLength(linkId, network);
        }
        if (route.getEndLinkId() != null && !route.getEndLinkId().equals(route.getStartLinkId())) {
            length += linkLength(route.getEndLinkId(), network);
        }
        return length;
    }

    /** 公交物理路网里程及数据完整性；任一公交 route/link 缺失时不返回部分里程。 */
    public record RoadNetworkStats(Double lengthMeters, int validRoutes, int missingGeometryRoutes) {
    }

    /**
     * JT/T 1457—2023 公共汽电车线路网密度。当前业务按用户确认，暂用模型
     * {@code desc.json.area} 的行政区总面积代替标准中的公交适设区域面积。
     *
     * @param uniqueRoadCenterlineMeters 公交经过的道路中心线全局去重长度（米）
     * @param administrativeAreaKm2      同一评价范围的行政区总面积（平方公里）
     * @return km/km²；任一输入无效时返回 null
     */
    public static Double busNetworkDensityKmPerKm2(
            Double uniqueRoadCenterlineMeters, Double administrativeAreaKm2) {
        if (uniqueRoadCenterlineMeters == null || administrativeAreaKm2 == null
                || !Double.isFinite(uniqueRoadCenterlineMeters)
                || !Double.isFinite(administrativeAreaKm2)
                || uniqueRoadCenterlineMeters < 0.0 || administrativeAreaKm2 <= 0.0) {
            return null;
        }
        return (uniqueRoadCenterlineMeters / 1000.0) / administrativeAreaKm2;
    }

    /**
     * 公交线路形态指标的统一结果。
     *
     * @param averageNonLinearCoefficient 非直线系数：非环线路线的“运营里程/首末站地面直线距离”算术平均
     * @param repetitionCoefficient        重复系数：各公交线路方向平均长度之和/公交经过的无向去重物理道路里程
     * @param validRoutes                  实际进入非直线系数平均值的非环公交线路数（兼容旧字段名）
     * @param excludedLoopRoutes           按首末同站、闭合路由或近闭合阈值排除的环线路线数
     * @param missingGeometryRoutes        缺 route/link/首末站坐标的路线数；大于 0 时非直线系数不下发部分真值
     * @param totalRouteLengthMeters        全部公交 route 静态里程；仅用于审计，不作为重复系数或客流强度分母；
     *                                      任一路线 route/link 缺失时为 null
     * @param maxNonLinearCoefficient      有效公交线路中的最大非直线系数
     * @param abnormalNonLinearLines       非直线系数大于 3.0 的异常候选线路数（仅诊断，不静默剔除）
     * @param maxNonLinearLineId           最大非直线系数对应的线路 ID
     * @param abnormalNonLinearLineIds     非直线系数大于 3.0 的线路 ID，供核查源几何
     */
    public record RouteShapeStats(Double averageNonLinearCoefficient,
                                  Double repetitionCoefficient,
                                  int validRoutes,
                                  int excludedLoopRoutes,
                                  int missingGeometryRoutes,
                                  Double totalRouteLengthMeters,
                                  Double maxNonLinearCoefficient,
                                  int abnormalNonLinearLines,
                                  String maxNonLinearLineId,
                                  List<String> abnormalNonLinearLineIds) {
    }

    public static RoadNetworkStats roadNetworkStats(
            org.matsim.pt.transitSchedule.api.TransitSchedule schedule,
            org.matsim.api.core.v01.network.Network network) {
        return roadNetworkStats(schedule, network, RoadTransitContext.from(schedule));
    }

    public static RoadNetworkStats roadNetworkStats(
            org.matsim.pt.transitSchedule.api.TransitSchedule schedule,
            org.matsim.api.core.v01.network.Network network,
            RoadTransitContext roadTransit) {
        if (schedule == null || network == null || roadTransit == null) {
            return new RoadNetworkStats(null, 0, 0);
        }
        Map<String, Double> physicalRoadLengths = new HashMap<>();
        int validRoutes = 0;
        int missingRoutes = 0;
        for (org.matsim.pt.transitSchedule.api.TransitLine line
                : schedule.getTransitLines().values()) {
            for (org.matsim.pt.transitSchedule.api.TransitRoute route : line.getRoutes().values()) {
                if (!roadTransit.isRoadRoute(line, route)) continue;
                NetworkRoute networkRoute = route.getRoute();
                List<Id<org.matsim.api.core.v01.network.Link>> linkIds = routeLinkIds(networkRoute);
                if (linkIds.isEmpty()) {
                    missingRoutes++;
                    continue;
                }
                Map<String, Double> routeRoads = new HashMap<>();
                boolean complete = true;
                for (Id<org.matsim.api.core.v01.network.Link> linkId : linkIds) {
                    org.matsim.api.core.v01.network.Link link = network.getLinks().get(linkId);
                    if (link == null || !Double.isFinite(link.getLength()) || link.getLength() <= 0) {
                        complete = false;
                        break;
                    }
                    routeRoads.merge(undirectedRoadKey(link), link.getLength(), Math::max);
                }
                if (!complete || routeRoads.isEmpty()) {
                    missingRoutes++;
                    continue;
                }
                validRoutes++;
                routeRoads.forEach((key, length) ->
                        physicalRoadLengths.merge(key, length, Math::max));
            }
        }
        Double length = missingRoutes > 0 || physicalRoadLengths.isEmpty()
                ? null : physicalRoadLengths.values().stream().mapToDouble(Double::doubleValue).sum();
        return new RoadNetworkStats(length, validRoutes, missingRoutes);
    }

    /**
     * 统一计算公交线路非直线系数和重复系数。
     * 按“单条线路”而非 TransitRoute/profile 数量等权：同一线路先选取最长的首末站服务模式，
     * 再按运行方向求平均线路长度，最后计算线路系数并对线路等权平均。环线按首末同站、
     * 路网几何闭合或近闭合判定后排除；系数大于 3.0 的非环线路保留在均值中并单独披露，
     * 不以删除异常值的方式美化结果。
     */
    public static RouteShapeStats roadRouteShapeStats(
            org.matsim.pt.transitSchedule.api.TransitSchedule schedule,
            org.matsim.api.core.v01.network.Network network,
            RoadTransitContext roadTransit,
            MetricCoordinateContext coordinates) {
        if (schedule == null || network == null || roadTransit == null) {
            return new RouteShapeStats(null, null, 0, 0, 0, null,
                    null, 0, null, List.of());
        }
        RoadNetworkStats networkStats = roadNetworkStats(schedule, network, roadTransit);
        double totalRouteLength = 0.0;
        double totalLineDirectionAverageLength = 0.0;
        double nonLinearSum = 0.0;
        double maxNonLinear = 0.0;
        String maxNonLinearLineId = null;
        List<String> abnormalLineIds = new java.util.ArrayList<>();
        int validLines = 0;
        int abnormalLines = 0;
        int excludedLoops = 0;
        int missingGeometry = 0;
        for (org.matsim.pt.transitSchedule.api.TransitLine line
                : schedule.getTransitLines().values()) {
            Map<String, Map<String, Double>> directionVariants = new HashMap<>();
            Map<String, String> directionByGeometry = new HashMap<>();
            Map<String, List<RouteShapeCandidate>> candidatesByTerminalPair = new HashMap<>();
            boolean declaredLoopLine = isDeclaredCircularLine(line);
            boolean hasRoadRoute = false;
            boolean lineHasMissingRouteGeometry = false;
            for (org.matsim.pt.transitSchedule.api.TransitRoute route : line.getRoutes().values()) {
                if (!roadTransit.isRoadRoute(line, route)) continue;
                hasRoadRoute = true;
                NetworkRoute networkRoute = route.getRoute();
                Double routeLength = strictRouteLengthMeters(networkRoute, network);
                if (routeLength == null || !(routeLength > 0)) {
                    missingGeometry++;
                    lineHasMissingRouteGeometry = true;
                    continue;
                }
                totalRouteLength += routeLength;
                List<Id<org.matsim.api.core.v01.network.Link>> linkIds = routeLinkIds(networkRoute);
                String geometryKey = directedRouteGeometryKey(linkIds);
                // 完全相同的有向几何必属同一方向，即使不同 profile 的方向属性/站点设施写法不一致。
                String directionKey = directionByGeometry.computeIfAbsent(
                        geometryKey, ignored -> routeDirectionKey(route, linkIds, network));
                directionVariants.computeIfAbsent(directionKey, ignored -> new HashMap<>())
                        .putIfAbsent(geometryKey, routeLength);

                List<org.matsim.pt.transitSchedule.api.TransitRouteStop> stops = route.getStops();
                if (stops == null || stops.size() < 2) {
                    missingGeometry++;
                    continue;
                }
                org.matsim.pt.transitSchedule.api.TransitRouteStop first = stops.get(0);
                org.matsim.pt.transitSchedule.api.TransitRouteStop last = stops.get(stops.size() - 1);
                if (first.getStopFacility() == null || last.getStopFacility() == null
                        || first.getStopFacility().getCoord() == null
                        || last.getStopFacility().getCoord() == null) {
                    missingGeometry++;
                    continue;
                }
                boolean sameFacility = first.getStopFacility().getId() != null
                        && first.getStopFacility().getId().equals(last.getStopFacility().getId());
                boolean closedNetworkRoute = isClosedNetworkGeometry(linkIds, network);
                if (declaredLoopLine || sameFacility || closedNetworkRoute) {
                    excludedLoops++;
                    continue;
                }
                if (coordinates == null || !coordinates.isSupported()) {
                    missingGeometry++;
                    continue;
                }
                double straight = coordinates.groundDistance(
                        first.getStopFacility().getCoord(), last.getStopFacility().getCoord());
                if (!Double.isFinite(straight) || straight < 0) {
                    missingGeometry++;
                    continue;
                }
                double loopThreshold = Math.max(100.0, routeLength * 0.10);
                if (straight <= loopThreshold) {
                    excludedLoops++;
                    continue;
                }
                String firstId = stopFacilityId(first);
                String lastId = stopFacilityId(last);
                String terminalPair = firstId == null || lastId == null
                        ? geometryKey
                        : firstId.compareTo(lastId) <= 0
                        ? firstId + "\u001F" + lastId : lastId + "\u001F" + firstId;
                candidatesByTerminalPair.computeIfAbsent(terminalPair, ignored -> new java.util.ArrayList<>())
                        .add(new RouteShapeCandidate(directionKey, geometryKey, routeLength, straight));
            }
            if (hasRoadRoute && !lineHasMissingRouteGeometry && !directionVariants.isEmpty()) {
                /*
                 * JT/T 1457—2023：单条线路长度取各运行方向里程的平均值。
                 * 同方向若存在分时速度 profile 等多个 TransitRoute，先按有向几何去重，
                 * 再在方向内求平均；最后对方向等权平均，避免 route/profile 数量放大分子。
                 */
                double lineDirectionSum = 0.0;
                for (Map<String, Double> variants : directionVariants.values()) {
                    lineDirectionSum += variants.values().stream()
                            .mapToDouble(Double::doubleValue).average().orElse(0.0);
                }
                totalLineDirectionAverageLength += lineDirectionSum / directionVariants.size();
            }
            if (!candidatesByTerminalPair.isEmpty()) {
                /*
                 * 同一 TransitLine 可能含短线、区间车或分时 profile。官方指标以“单条线路”
                 * 为单位，因此选取平均运营里程最长的首末站服务模式代表该线路；同方向相同
                 * 几何去重，并在方向内取最长完整路径，避免 profile 数量重复加权。
                 */
                List<RouteShapeCandidate> representative = candidatesByTerminalPair.values().stream()
                        .max(java.util.Comparator.comparingDouble(TransitMetrics::meanCandidateLength))
                        .orElse(List.of());
                Map<String, Map<String, RouteShapeCandidate>> byDirection = new HashMap<>();
                for (RouteShapeCandidate candidate : representative) {
                    byDirection.computeIfAbsent(candidate.directionKey(), ignored -> new HashMap<>())
                            .merge(candidate.geometryKey(), candidate,
                                    (oldValue, newValue) -> oldValue.routeLength() >= newValue.routeLength()
                                            ? oldValue : newValue);
                }
                double directionLengthSum = 0.0;
                double directionStraightSum = 0.0;
                int directions = 0;
                for (Map<String, RouteShapeCandidate> geometries : byDirection.values()) {
                    RouteShapeCandidate primary = geometries.values().stream()
                            .max(java.util.Comparator.comparingDouble(RouteShapeCandidate::routeLength))
                            .orElse(null);
                    if (primary == null) continue;
                    directionLengthSum += primary.routeLength();
                    directionStraightSum += primary.straightDistance();
                    directions++;
                }
                if (directions > 0 && directionStraightSum > 0) {
                    double coefficient = (directionLengthSum / directions)
                            / (directionStraightSum / directions);
                    nonLinearSum += coefficient;
                    if (coefficient > maxNonLinear) {
                        maxNonLinear = coefficient;
                        maxNonLinearLineId = line.getId().toString();
                    }
                    if (coefficient > 3.0) {
                        abnormalLines++;
                        abnormalLineIds.add(line.getId().toString());
                    }
                    validLines++;
                }
            }
        }
        Double nonLinear = missingGeometry > 0 || validLines == 0
                ? null : nonLinearSum / validLines;
        Double repetition = networkStats.lengthMeters() == null
                || !(totalLineDirectionAverageLength > 0)
                ? null : totalLineDirectionAverageLength / networkStats.lengthMeters();
        int strictMissing = Math.max(missingGeometry, networkStats.missingGeometryRoutes());
        Double strictTotalRouteLength = networkStats.missingGeometryRoutes() > 0 || !(totalRouteLength > 0)
                ? null : totalRouteLength;
        abnormalLineIds.sort(String::compareTo);
        return new RouteShapeStats(nonLinear, repetition, validLines, excludedLoops,
                strictMissing, strictTotalRouteLength,
                validLines == 0 ? null : maxNonLinear, abnormalLines,
                maxNonLinearLineId, List.copyOf(abnormalLineIds));
    }

    private record RouteShapeCandidate(String directionKey, String geometryKey,
                                       double routeLength, double straightDistance) {
    }

    private static double meanCandidateLength(List<RouteShapeCandidate> candidates) {
        return candidates == null || candidates.isEmpty() ? 0.0
                : candidates.stream().mapToDouble(RouteShapeCandidate::routeLength).average().orElse(0.0);
    }

    private static boolean isClosedNetworkGeometry(
            List<Id<org.matsim.api.core.v01.network.Link>> linkIds,
            org.matsim.api.core.v01.network.Network network) {
        if (linkIds == null || linkIds.isEmpty() || network == null) return false;
        org.matsim.api.core.v01.network.Link first = network.getLinks().get(linkIds.get(0));
        org.matsim.api.core.v01.network.Link last =
                network.getLinks().get(linkIds.get(linkIds.size() - 1));
        return first != null && last != null && first.getFromNode() != null && last.getToNode() != null
                && first.getFromNode().getId().equals(last.getToNode().getId());
    }

    /** 时刻表明确声明的环线：端点式 lineId 两端相同，或线路名称/类型含可靠环线标记。 */
    private static boolean isDeclaredCircularLine(
            org.matsim.pt.transitSchedule.api.TransitLine line) {
        if (line == null || line.getId() == null) return false;
        String id = line.getId().toString().trim();
        int separator = id.indexOf('@');
        if (separator > 0 && separator < id.length() - 1
                && id.substring(0, separator).equals(id.substring(separator + 1))) {
            return true;
        }
        for (String key : List.of("name", "lineName", "route_long_name", "routeType")) {
            Object value = line.getAttributes().getAttribute(key);
            if (value == null) continue;
            String normalized = String.valueOf(value).toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("环线") || normalized.contains("环状")
                    || normalized.contains("loop") || normalized.contains("circular")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 识别同一 TransitLine 内的运行方向。优先使用显式方向属性；普通线路使用有序首末站，
     * 环线补充起点后的首个不同站和回到起点前的最后一个不同站；缺站序时退回路网端点，
     * 最后才使用完整有向几何。这样可把同方向的时段/班次 profile 归到同一方向，同时保留反向。
     */
    private static String routeDirectionKey(
            org.matsim.pt.transitSchedule.api.TransitRoute route,
            List<Id<org.matsim.api.core.v01.network.Link>> linkIds,
            org.matsim.api.core.v01.network.Network network) {
        for (String key : List.of(
                "direction_id", "directionId", "gtfs_direction_id", "direction", "routeDirection")) {
            Object value = route.getAttributes().getAttribute(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return "attribute:" + String.valueOf(value).trim()
                        .toLowerCase(java.util.Locale.ROOT);
            }
        }

        List<org.matsim.pt.transitSchedule.api.TransitRouteStop> stops = route.getStops();
        if (stops != null && stops.size() >= 2) {
            String first = stopFacilityId(stops.get(0));
            String last = stopFacilityId(stops.get(stops.size() - 1));
            if (first != null && last != null && !first.equals(last)) {
                return "stops:" + first + "->" + last;
            }
            if (first != null && first.equals(last)) {
                String next = firstDifferentStop(stops, first, 1, 1);
                String previous = firstDifferentStop(stops, first, stops.size() - 2, -1);
                if (next != null || previous != null) {
                    return "loop:" + first + "->" + String.valueOf(next)
                            + "|" + String.valueOf(previous) + "->" + first;
                }
            }
        }

        if (network != null && linkIds != null && !linkIds.isEmpty()) {
            org.matsim.api.core.v01.network.Link firstLink = network.getLinks().get(linkIds.get(0));
            org.matsim.api.core.v01.network.Link lastLink =
                    network.getLinks().get(linkIds.get(linkIds.size() - 1));
            if (firstLink != null && lastLink != null
                    && firstLink.getFromNode() != null && lastLink.getToNode() != null) {
                String from = firstLink.getFromNode().getId().toString();
                String to = lastLink.getToNode().getId().toString();
                if (!from.equals(to)) {
                    return "nodes:" + from + "->" + to;
                }
            }
        }
        return "geometry:" + directedRouteGeometryKey(linkIds);
    }

    private static String stopFacilityId(
            org.matsim.pt.transitSchedule.api.TransitRouteStop stop) {
        return stop == null || stop.getStopFacility() == null || stop.getStopFacility().getId() == null
                ? null : stop.getStopFacility().getId().toString();
    }

    private static String firstDifferentStop(
            List<org.matsim.pt.transitSchedule.api.TransitRouteStop> stops,
            String origin, int start, int step) {
        for (int index = start; index >= 0 && index < stops.size(); index += step) {
            String candidate = stopFacilityId(stops.get(index));
            if (candidate != null && !candidate.equals(origin)) return candidate;
        }
        return null;
    }

    /** 完整有向 link 序列签名；同一路径的分时 profile 会得到同一个键。 */
    private static String directedRouteGeometryKey(
            List<Id<org.matsim.api.core.v01.network.Link>> linkIds) {
        if (linkIds == null || linkIds.isEmpty()) return "";
        StringBuilder key = new StringBuilder(linkIds.size() * 16);
        for (Id<org.matsim.api.core.v01.network.Link> linkId : linkIds) {
            if (!key.isEmpty()) key.append('\u001F');
            key.append(linkId);
        }
        return key.toString();
    }

    private static List<Id<org.matsim.api.core.v01.network.Link>> routeLinkIds(NetworkRoute route) {
        if (route == null) return List.of();
        List<Id<org.matsim.api.core.v01.network.Link>> result = new java.util.ArrayList<>();
        if (route.getStartLinkId() != null) result.add(route.getStartLinkId());
        result.addAll(route.getLinkIds());
        if (route.getEndLinkId() != null && !route.getEndLinkId().equals(route.getStartLinkId())) {
            result.add(route.getEndLinkId());
        }
        return result;
    }

    private static Double strictRouteLengthMeters(
            NetworkRoute route, org.matsim.api.core.v01.network.Network network) {
        if (route == null || network == null) return null;
        List<Id<org.matsim.api.core.v01.network.Link>> linkIds = routeLinkIds(route);
        if (linkIds.isEmpty()) return null;
        double length = 0.0;
        for (Id<org.matsim.api.core.v01.network.Link> linkId : linkIds) {
            org.matsim.api.core.v01.network.Link link = network.getLinks().get(linkId);
            if (link == null || !Double.isFinite(link.getLength()) || link.getLength() <= 0) return null;
            length += link.getLength();
        }
        return length > 0 ? length : null;
    }

    private static double linkLength(Id<org.matsim.api.core.v01.network.Link> linkId,
                                     org.matsim.api.core.v01.network.Network network) {
        if (linkId == null) return 0.0;
        org.matsim.api.core.v01.network.Link link = network.getLinks().get(linkId);
        if (link == null || !Double.isFinite(link.getLength()) || link.getLength() <= 0) return 0.0;
        return link.getLength();
    }

    /** EPSG:3857 两点的近似地面距离（米），用两点中纬度的 cos 校正线性尺度。 */
    public static double webMercatorGroundDistance(Coord first, Coord second) {
        if (first == null || second == null) return 0.0;
        double dx = first.getX() - second.getX();
        double dy = first.getY() - second.getY();
        double latitude = Math.atan(Math.sinh(((first.getY() + second.getY()) / 2.0)
                / WEB_MERCATOR_RADIUS));
        return Math.hypot(dx, dy) * Math.cos(latitude);
    }

    /**
     * 线网里程（米）：有线路经过的**道路**长度，而非各线路长度之和。
     *
     * @param excludeRail true = 只统计公共汽电车线路（"公交线网密度"口径，剔除地铁/轨道）
     */
    public static double networkLengthMeters(org.matsim.pt.transitSchedule.api.TransitSchedule schedule,
                                             org.matsim.api.core.v01.network.Network network,
                                             boolean excludeRail) {
        if (schedule == null || network == null) {
            return 0.0;
        }
        // 同一无向节点对只代表一段物理道路。取 max 消除双向 link 长度小幅不一致时的迭代顺序影响。
        Map<String, Double> physicalRoadLengths = new java.util.HashMap<>();
        RoadTransitContext roadContext = excludeRail ? RoadTransitContext.from(schedule) : null;
        for (org.matsim.pt.transitSchedule.api.TransitLine line : schedule.getTransitLines().values()) {
            for (org.matsim.pt.transitSchedule.api.TransitRoute route : line.getRoutes().values()) {
                if (excludeRail && !roadContext.isRoadRoute(line, route)) {
                    continue;
                }
                org.matsim.core.population.routes.NetworkRoute networkRoute = route.getRoute();
                if (networkRoute == null) {
                    continue;
                }
                List<Id<org.matsim.api.core.v01.network.Link>> linkIds = new java.util.ArrayList<>();
                if (networkRoute.getStartLinkId() != null) linkIds.add(networkRoute.getStartLinkId());
                linkIds.addAll(networkRoute.getLinkIds());
                if (networkRoute.getEndLinkId() != null) linkIds.add(networkRoute.getEndLinkId());
                for (Id<org.matsim.api.core.v01.network.Link> linkId : linkIds) {
                    org.matsim.api.core.v01.network.Link link = network.getLinks().get(linkId);
                    // 线路引用了路网里不存在的 link（如独立 pt 网络）时跳过，而不是 NPE 打穿接口
                    if (link == null || !Double.isFinite(link.getLength()) || link.getLength() <= 0) {
                        continue;
                    }
                    physicalRoadLengths.merge(undirectedRoadKey(link), link.getLength(), Math::max);
                }
            }
        }
        return physicalRoadLengths.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    /**
     * 出行(trip)主方式优先级。数值越大越优先；未知方式取 1（高于步行、低于机动化），
     * 避免 MATSim DefaultAnalysisMainModeIdentifier 遇到未登记方式直接抛 IllegalStateException。
     */
    public static int mainModeRank(String mode) {
        if (mode == null) {
            return 1;
        }
        if (isTransitMode(mode)) {
            return 4;
        }
        return switch (mode.toLowerCase(java.util.Locale.ROOT)) {
            case "walk", "transit_walk", "access_walk", "egress_walk", "non_network_walk" -> 0;
            case "bike" -> 2;
            case "ride", "taxi", "drt", "motorcycle", "truck", Constant.ROUTE_MODE_CAR -> 3;
            default -> 1;
        };
    }

    /**
     * 已结合 schedule 解析制式的 trip 主方式优先级。轨道及其它非道路公共交通
     * 优先于道路公交，确保
     * bus→subway 与 subway→bus 的换乘出行不会因为 leg 顺序不同而改变主方式。
     * 无法解析的 legacy pt 仍保留 transit 层级，并由上层按 unsupported 处理。
     */
    public static int resolvedMainModeRank(Leg leg, RoadTransitContext context) {
        if (leg == null) return Integer.MIN_VALUE;
        String resolved = resolvedTransitMode(leg, context);
        if (isRailTransitMode(resolved)) return 6;
        if (isExplicitRoadPublicTransportMode(resolved)) return 4;
        if (isTransitMode(resolved)) return 5;
        return mainModeRank(resolved == null || resolved.isBlank() ? leg.getMode() : resolved);
    }

    /**
     * 将 legacy {@code pt} leg 尽可能通过 TransitPassengerRoute 的 line/route 回查时刻表制式。
     * 无法解析时保留 pt，评价层可将其排除并披露完整性，不默认当成公交车。
     */
    public static String resolvedTransitMode(
            Leg leg, org.matsim.pt.transitSchedule.api.TransitSchedule schedule) {
        return resolvedTransitMode(leg, RoadTransitContext.from(schedule));
    }

    /** 预构建上下文版本，供千万级 plans 流式扫描避免每条 leg 重建 route 索引。 */
    public static String resolvedTransitMode(Leg leg, RoadTransitContext context) {
        if (leg == null || leg.getMode() == null) return null;
        String mode = leg.getMode().toLowerCase(java.util.Locale.ROOT);
        if (!Constant.ROUTE_MODE_PT.equals(mode) || context == null || context.schedule == null
                || !(leg.getRoute() instanceof TransitPassengerRoute passengerRoute)
                || passengerRoute.getLineId() == null || passengerRoute.getRouteId() == null) {
            return mode;
        }
        org.matsim.pt.transitSchedule.api.TransitLine line =
                context.schedule.getTransitLines().get(passengerRoute.getLineId());
        org.matsim.pt.transitSchedule.api.TransitRoute route = line == null
                ? null : line.getRoutes().get(passengerRoute.getRouteId());
        if (route == null) {
            return mode;
        }
        if (context.isRoadRoute(line, route)) return "bus";
        String resolved = normalize(route.getTransportMode());
        return resolved == null || resolved.isBlank() ? mode : resolved;
    }

    /** 评价口径的公共汽电车 leg；泛化 pt 必须能回查到显式 bus 制式。 */
    public static boolean isResolvedRoadPublicTransportLeg(
            Leg leg, org.matsim.pt.transitSchedule.api.TransitSchedule schedule) {
        return isExplicitRoadPublicTransportMode(resolvedTransitMode(leg, schedule));
    }

    public static boolean isResolvedRoadPublicTransportLeg(Leg leg, RoadTransitContext context) {
        return isExplicitRoadPublicTransportMode(resolvedTransitMode(leg, context));
    }

    /** 公共汽电车车内运行时间；制式必须由统一 schedule 上下文明确解析。 */
    public static Double inVehicleTravelSeconds(Leg leg, RoadTransitContext context) {
        if (!isResolvedRoadPublicTransportLeg(leg, context) || !leg.getTravelTime().isDefined()) {
            return null;
        }
        Double wait = boardingWaitSeconds(leg);
        if (wait == null) return null;
        double inVehicle = leg.getTravelTime().seconds() - wait;
        return Double.isFinite(inVehicle) && inVehicle > 0 ? inVehicle : null;
    }

    /**
     * 高峰运送速度汇总。公共汽电车采用计划班次的运营里程和首站发车至末站到达时间，
     * 时间包含沿途停站，符合“运送速度/运营速度”口径，不使用乘客 leg 的客流加权速度。
     */
    public record PeakOperatingSpeedStats(Double kmh, double distanceMeters,
                                          double travelSeconds, long samples,
                                          int missingGeometryRoutes) {
    }

    public static PeakOperatingSpeedStats roadBusPeakOperatingSpeedStats(
            org.matsim.pt.transitSchedule.api.TransitSchedule schedule,
            org.matsim.api.core.v01.network.Network network,
            RoadTransitContext context) {
        if (schedule == null || network == null || context == null) {
            return new PeakOperatingSpeedStats(null, 0.0, 0.0, 0, 0);
        }
        double distanceMeters = 0.0;
        double travelSeconds = 0.0;
        long departures = 0;
        int missing = 0;
        for (org.matsim.pt.transitSchedule.api.TransitLine line
                : schedule.getTransitLines().values()) {
            for (org.matsim.pt.transitSchedule.api.TransitRoute route : line.getRoutes().values()) {
                if (!context.isRoadRoute(line, route)) continue;
                long peakDepartures = route.getDepartures().values().stream()
                        .filter(departure -> isPeakTimeSeconds(departure.getDepartureTime()))
                        .count();
                if (peakDepartures == 0) continue;
                Double length = strictRouteLengthMeters(route.getRoute(), network);
                Double duration = routeTravelSecondsOrNull(route);
                if (length == null || !(length > 0) || duration == null || !(duration > 0)) {
                    missing++;
                    continue;
                }
                distanceMeters += length * peakDepartures;
                travelSeconds += duration * peakDepartures;
                departures += peakDepartures;
            }
        }
        Double speed = missing > 0 || departures == 0 || !(travelSeconds > 0)
                ? null : distanceMeters / travelSeconds * 3.6;
        return new PeakOperatingSpeedStats(speed, distanceMeters, travelSeconds, departures, missing);
    }

    /** 单条高峰小汽车 leg 的距离/时间样本；无效、非高峰或非 car 返回 null。 */
    public static PeakOperatingSpeedStats peakCarLegSpeedSample(Leg leg) {
        if (leg == null || !Constant.ROUTE_MODE_CAR.equalsIgnoreCase(leg.getMode())
                || !leg.getDepartureTime().isDefined()
                || !isPeakTimeSeconds(leg.getDepartureTime().seconds())
                || !leg.getTravelTime().isDefined()
                || !Double.isFinite(leg.getTravelTime().seconds())
                || !(leg.getTravelTime().seconds() > 0)
                || leg.getRoute() == null
                || !Double.isFinite(leg.getRoute().getDistance())
                || !(leg.getRoute().getDistance() > 0)) {
            return null;
        }
        return new PeakOperatingSpeedStats(
                leg.getRoute().getDistance() / leg.getTravelTime().seconds() * 3.6,
                leg.getRoute().getDistance(), leg.getTravelTime().seconds(), 1, 0);
    }

    /**
     * 一次完整 OD 出行的公交换乘观测。换乘次数等于该出行内公共交通乘坐段数减一；
     * 公交与自身、轨道及其他城市公共交通之间的换乘都计入。
     */
    public record BusServiceJourneyObservation(boolean busJourney,
                                               int transitBoardings,
                                               int transfers,
                                               boolean busRailJourney,
                                               boolean unresolvedLegacyPt) {
    }

    public static BusServiceJourneyObservation busServiceJourneyObservation(
            List<Leg> legs, RoadTransitContext context) {
        boolean hasBus = false;
        boolean hasRail = false;
        boolean unresolved = false;
        int boardings = 0;
        if (legs != null) {
            for (Leg leg : legs) {
                String resolved = resolvedTransitMode(leg, context);
                if (Constant.ROUTE_MODE_PT.equals(resolved)) {
                    unresolved = true;
                    continue;
                }
                if (!isTransitMode(resolved)) continue;
                boardings++;
                if (isExplicitRoadPublicTransportMode(resolved)) hasBus = true;
                if (isRailTransitMode(resolved)) hasRail = true;
            }
        }
        return new BusServiceJourneyObservation(
                hasBus, boardings, hasBus ? Math.max(0, boardings - 1) : 0,
                hasBus && hasRail, unresolved);
    }

    /**
     * 公交乘客完整 OD 出行的平均换乘次数及公交—轨道接驳比例。
     * 分母是全部含至少一个公共汽电车乘坐段的 OD 出行，不使用“已发生换乘”的子集作分母。
     */
    public record BusServiceJourneyStats(long busJourneys, long transitBoardings,
                                         long transfers, long busRailJourneys,
                                         long unresolvedLegacyPtJourneys) {
        public Double averageTransfers() {
            return busJourneys == 0 ? null : transfers / (double) busJourneys;
        }

        public Double busRailRatioPercent() {
            return busJourneys == 0 ? null : busRailJourneys * 100.0 / busJourneys;
        }
    }

    public static BusServiceJourneyStats busServiceJourneyStats(
            Population population, RoadTransitContext context) {
        long busJourneys = 0;
        long boardings = 0;
        long transfers = 0;
        long busRail = 0;
        long unresolved = 0;
        if (population != null) {
            for (Person person : population.getPersons().values()) {
                org.matsim.api.core.v01.population.Plan plan = person.getSelectedPlan();
                if (plan == null) continue;
                for (TripStructureUtils.Trip trip : TripStructureUtils.getTrips(plan)) {
                    BusServiceJourneyObservation observation =
                            busServiceJourneyObservation(trip.getLegsOnly(), context);
                    if (observation.unresolvedLegacyPt()) unresolved++;
                    if (!observation.busJourney()) continue;
                    busJourneys++;
                    boardings += observation.transitBoardings();
                    transfers += observation.transfers();
                    if (observation.busRailJourney()) busRail++;
                }
            }
        }
        return new BusServiceJourneyStats(
                busJourneys, boardings, transfers, busRail, unresolved);
    }

    /** 一次出行的主方式：取该次出行内优先级最高的 leg mode；公共交通统一归入 pt 展示键。 */
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
        if (mainMode == null) {
            return "walk";
        }
        // 模型可能把公交主方式写成 pt，也可能直接写 bus/metro/... 。
        // 评价指标的“公交分担率”必须聚合到同一 pt 键，不能只读到其中一种命名。
        return isTransitMode(mainMode) ? Constant.ROUTE_MODE_PT : mainMode;
    }

    /** 体检表出行分担率及口径完整性。 */
    public record BusTripShareStats(Double busPercent, Double transitPercent,
                                    Double publicTransportMotorizedPercent,
                                    long journeys, long busJourneys, long transitJourneys,
                                    long residentBusJourneys, long residentTransitJourneys,
                                    long motorizedJourneys, long unresolvedLegacyPtJourneys,
                                    long residentUnresolvedLegacyPtJourneys) {
    }

    /**
     * 按 trip 主方式统计：bus/trolleybus/brt 进入公共汽电车分子，subway/rail 不进；
     * legacy pt 仅在可由 schedule 解析为道路公交时进入。
     */
    public static BusTripShareStats busTripShareStats(
            Population population, org.matsim.pt.transitSchedule.api.TransitSchedule schedule) {
        return busTripShareStats(population, RoadTransitContext.from(schedule));
    }

    public static BusTripShareStats busTripShareStats(
            Population population, RoadTransitContext context) {
        long journeys = 0;
        long bus = 0;
        long transit = 0;
        long residentBus = 0;
        long residentTransit = 0;
        long motorized = 0;
        long unresolvedPt = 0;
        long residentUnresolvedPt = 0;
        if (population != null) {
            for (Person person : population.getPersons().values()) {
                boolean resident = firstValidHomeCoord(person) != null;
                org.matsim.api.core.v01.population.Plan plan = person.getSelectedPlan();
                if (plan == null) continue;
                for (TripStructureUtils.Trip trip : TripStructureUtils.getTrips(plan)) {
                    Leg mainLeg = null;
                    int bestRank = Integer.MIN_VALUE;
                    for (Leg leg : trip.getLegsOnly()) {
                        int rank = resolvedMainModeRank(leg, context);
                        if (rank > bestRank) {
                            bestRank = rank;
                            mainLeg = leg;
                        }
                    }
                    journeys++;
                    if (mainLeg == null) continue;
                    String resolved = resolvedTransitMode(mainLeg, context);
                    if (isMotorizedMode(resolved)) motorized++;
                    if (!isTransitMode(resolved)) continue;
                    transit++;
                    if (resident) residentTransit++;
                    if (Constant.ROUTE_MODE_PT.equals(resolved)) {
                        unresolvedPt++;
                        if (resident) residentUnresolvedPt++;
                    } else if (isExplicitRoadPublicTransportMode(resolved)) {
                        bus++;
                        if (resident) residentBus++;
                    }
                }
            }
        }
        if (journeys == 0) {
            return new BusTripShareStats(
                    null, null, null, 0, 0, 0, 0, 0, 0, unresolvedPt, residentUnresolvedPt);
        }
        return new BusTripShareStats(
                bus * 100.0 / journeys,
                transit * 100.0 / journeys,
                motorized == 0 ? null : transit * 100.0 / motorized,
                journeys,
                bus,
                transit,
                residentBus,
                residentTransit,
                motorized,
                unresolvedPt,
                residentUnresolvedPt);
    }

    /**
     * 仿真时刻表中实际被道路公交班次引用的全网车辆清单。
     * 车辆按 ID 全网去重；标台严格按交通行业通用车长系数折算，缺车辆或车长时不猜测。
     */
    public record RoadFleetInventoryStats(Long operatingVehicles, Double standardVehicles,
                                          int missingVehicles, int missingVehicleLengths) {
        public boolean hasOfficialStandardVehicles() {
            return standardVehicles != null && missingVehicles == 0 && missingVehicleLengths == 0;
        }
    }

    public static RoadFleetInventoryStats roadFleetInventory(
            org.matsim.pt.transitSchedule.api.TransitSchedule schedule,
            org.matsim.vehicles.Vehicles vehicles) {
        RoadTransitContext context = RoadTransitContext.from(schedule);
        Set<Id<Vehicle>> ids = new LinkedHashSet<>();
        if (schedule != null) {
            for (org.matsim.pt.transitSchedule.api.TransitLine line
                    : schedule.getTransitLines().values()) {
                for (org.matsim.pt.transitSchedule.api.TransitRoute route : line.getRoutes().values()) {
                    if (!context.isRoadRoute(line, route)) continue;
                    for (org.matsim.pt.transitSchedule.api.Departure departure
                            : route.getDepartures().values()) {
                        if (departure.getVehicleId() != null) ids.add(departure.getVehicleId());
                    }
                }
            }
        }
        if (ids.isEmpty()) return new RoadFleetInventoryStats(null, null, 0, 0);
        int missingVehicles = 0;
        int missingLengths = 0;
        double standardVehicles = 0.0;
        Map<Id<Vehicle>, Vehicle> vehicleMap =
                vehicles == null ? Map.of() : vehicles.getVehicles();
        for (Id<Vehicle> id : ids) {
            Vehicle vehicle = vehicleMap.get(id);
            if (vehicle == null || vehicle.getType() == null) {
                missingVehicles++;
                continue;
            }
            double length = vehicle.getType().getLength();
            Double coefficient = standardVehicleCoefficient(length);
            if (coefficient == null) {
                missingLengths++;
                continue;
            }
            standardVehicles += coefficient;
        }
        Double officialStandardVehicles = missingVehicles == 0 && missingLengths == 0
                ? standardVehicles : null;
        return new RoadFleetInventoryStats((long) ids.size(), officialStandardVehicles,
                missingVehicles, missingLengths);
    }

    /** 公共汽电车标台车长换算系数。双层车型需额外车型属性，当前仅按车长折算。 */
    public static Double standardVehicleCoefficient(double lengthMeters) {
        if (!Double.isFinite(lengthMeters) || lengthMeters <= 0) return null;
        if (lengthMeters <= 5.0) return 0.5;
        if (lengthMeters <= 7.0) return 0.7;
        if (lengthMeters <= 10.0) return 1.0;
        if (lengthMeters <= 13.0) return 1.3;
        if (lengthMeters <= 16.0) return 1.7;
        if (lengthMeters <= 18.0) return 2.0;
        return 2.5;
    }

    /** 体检评估与客流监测共用的公交运营效率公式。 */
    public record BusOperatingEfficiency(Double perVehicleDaily, Double perDeparture) {
    }

    public static BusOperatingEfficiency busOperatingEfficiency(
            double dailyBoardings, long operatingVehicles, long departures) {
        return new BusOperatingEfficiency(
                operatingVehicles > 0 ? dailyBoardings / operatingVehicles : null,
                departures > 0 ? dailyBoardings / departures : null);
    }

    /**
     * 公交人均日出行次数：以公共汽电车为主方式的完整 OD 出行数除以具有有效 home 的常住人口。
     * 地铁、铁路、轮渡不进入分子；无法判定制式的 legacy pt 会使结果不可用，避免静默低估公交出行。
     */
    public static Double busTripsPerResident(
            BusTripShareStats stats, Long residentHomePersons) {
        if (stats == null || residentHomePersons == null || residentHomePersons <= 0) return null;
        if (stats.residentUnresolvedLegacyPtJourneys() > 0) return null;
        return stats.residentBusJourneys() / (double) residentHomePersons;
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
        return peakConcurrentVehicles(schedule, (line, route) -> true);
    }

    public record RoadFleetStats(Long peakVehicles, int missingDurationRoutes) {
    }

    /** 公共汽电车高峰同时在营车辆数；任一路线缺全程时长时不猜 30 分钟，返回 null。 */
    public static RoadFleetStats roadFleetStats(
            org.matsim.pt.transitSchedule.api.TransitSchedule schedule) {
        RoadTransitContext context = RoadTransitContext.from(schedule);
        return roadFleetStats(schedule, context);
    }

    private static RoadFleetStats roadFleetStats(
            org.matsim.pt.transitSchedule.api.TransitSchedule schedule,
            RoadTransitContext context) {
        if (schedule == null) return new RoadFleetStats(null, 0);
        List<double[]> events = new java.util.ArrayList<>();
        int missing = 0;
        for (org.matsim.pt.transitSchedule.api.TransitLine line : schedule.getTransitLines().values()) {
            for (org.matsim.pt.transitSchedule.api.TransitRoute route : line.getRoutes().values()) {
                if (!context.isRoadRoute(line, route) || route.getDepartures().isEmpty()) continue;
                Double duration = routeTravelSecondsOrNull(route);
                if (duration == null) {
                    missing++;
                    continue;
                }
                for (org.matsim.pt.transitSchedule.api.Departure departure : route.getDepartures().values()) {
                    double start = departure.getDepartureTime();
                    events.add(new double[]{start, 1});
                    events.add(new double[]{start + duration, -1});
                }
            }
        }
        if (missing > 0 || events.isEmpty()) return new RoadFleetStats(null, missing);
        return new RoadFleetStats(peakFromEvents(events), 0);
    }

    /** @deprecated 评价体系应使用 {@link #roadFleetStats} 读取显式缺失态。 */
    @Deprecated
    public static long peakConcurrentRoadVehicles(
            org.matsim.pt.transitSchedule.api.TransitSchedule schedule) {
        Long peak = roadFleetStats(schedule).peakVehicles();
        return peak == null ? 0 : peak;
    }

    private static long peakConcurrentVehicles(
            org.matsim.pt.transitSchedule.api.TransitSchedule schedule,
            java.util.function.BiPredicate<org.matsim.pt.transitSchedule.api.TransitLine,
                    org.matsim.pt.transitSchedule.api.TransitRoute> routeFilter) {
        if (schedule == null) return 0;
        List<double[]> events = new java.util.ArrayList<>();
        for (org.matsim.pt.transitSchedule.api.TransitLine line : schedule.getTransitLines().values()) {
            for (org.matsim.pt.transitSchedule.api.TransitRoute route : line.getRoutes().values()) {
                if (!routeFilter.test(line, route)) continue;
                Double duration = routeTravelSecondsOrNull(route);
                if (duration == null) continue;
                for (org.matsim.pt.transitSchedule.api.Departure departure : route.getDepartures().values()) {
                    double start = departure.getDepartureTime();
                    events.add(new double[]{start, 1});
                    events.add(new double[]{start + duration, -1});
                }
            }
        }
        return peakFromEvents(events);
    }

    private static long peakFromEvents(List<double[]> events) {
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

    private static Double routeTravelSecondsOrNull(
            org.matsim.pt.transitSchedule.api.TransitRoute route) {
        List<org.matsim.pt.transitSchedule.api.TransitRouteStop> stops = route.getStops();
        if (stops == null || stops.isEmpty()) {
            return null;
        }
        org.matsim.pt.transitSchedule.api.TransitRouteStop last = stops.get(stops.size() - 1);
        double offset = last.getArrivalOffset().orElse(last.getDepartureOffset().orElse(0));
        return Double.isFinite(offset) && offset > 0 ? offset : null;
    }

    /** 全天平均候车时间（分钟）：全部可靠样本候车秒数之和 ÷ 样本数 ÷ 60。 */
    public static Double averageAwaitMinutes(Population population) {
        return averageAwaitMinutes(population, null, false);
    }

    /** 公共汽电车平均候车时间；legacy pt 仅在 schedule 明确解析为 bus 时进入。 */
    public static Double averageRoadBusAwaitMinutes(
            Population population, org.matsim.pt.transitSchedule.api.TransitSchedule schedule) {
        return averageRoadBusAwaitMinutes(population, RoadTransitContext.from(schedule));
    }

    /** 严格 bus-only 候车：只接受 boardingTime - leg.departureTime 的可靠样本。 */
    public static Double averageRoadBusAwaitMinutes(Population population, RoadTransitContext context) {
        double sum = 0;
        long count = 0;
        if (population != null) {
            for (Person person : population.getPersons().values()) {
                org.matsim.api.core.v01.population.Plan plan = person.getSelectedPlan();
                if (plan == null) continue;
                for (PlanElement element : plan.getPlanElements()) {
                    if (!(element instanceof Leg leg)
                            || !isResolvedRoadPublicTransportLeg(leg, context)) continue;
                    Double wait = boardingWaitSeconds(leg);
                    if (wait == null) continue;
                    sum += wait;
                    count++;
                }
            }
        }
        return count == 0 ? null : sum / count / 60.0;
    }

    private static Double averageAwaitMinutes(
            Population population,
            org.matsim.pt.transitSchedule.api.TransitSchedule schedule,
            boolean roadBusOnly) {
        double sum = 0;
        long count = 0;
        if (population != null) {
            for (Person person : population.getPersons().values()) {
                List<PlanElement> elements = person.getSelectedPlan().getPlanElements();
                for (int i = 0; i < elements.size(); i++) {
                    if (roadBusOnly && (!(elements.get(i) instanceof Leg leg)
                            || !isResolvedRoadPublicTransportLeg(leg, schedule))) {
                        continue;
                    }
                    WaitSample sample = waitSample(elements, i);
                    if (sample == null) continue;
                    sum += sample.waitSeconds();
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
