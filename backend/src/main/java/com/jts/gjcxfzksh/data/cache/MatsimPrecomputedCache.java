package com.jts.gjcxfzksh.data.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jts.gjcxfzksh.api.common.Constant;
import com.jts.gjcxfzksh.api.model.pt.PTCoord;
import com.jts.gjcxfzksh.api.model.pt.PTLink;
import com.jts.gjcxfzksh.api.model.vo.FacilityVO;
import com.jts.gjcxfzksh.api.model.vo.LineVO;
import com.jts.gjcxfzksh.api.model.vo.RouteDetailVO;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import com.jts.gjcxfzksh.data.entry.TileNetwork;
import com.jts.gjcxfzksh.data.id.RouteId;
import com.jts.gjcxfzksh.data.id.VehicleId;
import com.jts.gjcxfzksh.utils.DistanceUtil;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.strtree.STRtree;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Slf4j
public final class MatsimPrecomputedCache {

    public static final String VISUAL_CACHE_VERSION = "visual-v7";
    private static final int VISUAL_TILE_ZOOM = 12;
    private static final int MIN_VISUAL_TILE_ZOOM = 8;
    private static final int ROUTE_DETAIL_SHARD_COUNT = 32;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Object>> LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, RouteDetailVO>> ROUTE_DETAIL_MAP_TYPE = new TypeReference<>() {};
    private static final String INFO_FILE = "info.json";
    private static final String LINES_FILE = "lines.json.gz";
    private static final String STATIONS_FILE = "stations.json.gz";
    private static final String ROUTE_INDEX_FILE = "route-index.json";
    private static final String NETWORK_TILES_DIR = "network-tiles";
    private static final String ROUTE_TILES_DIR = "route-tiles";
    private static final String ROUTE_DETAILS_DIR = "route-details";

    private MatsimPrecomputedCache() {
    }

    public static void prepareOnModelLoad(MatsimData data) {
        try {
            ensureVisualCache(data);
        } catch (Exception e) {
            log.error("模型预计算缓存生成失败: model={}, error={}", data.getName(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    public static Map<String, Object> readInfo(MatsimData data) {
        if (!isVisualCacheReady(data)) {
            return null;
        }
        try {
            return JSON.readValue(infoPath(data).toFile(), MAP_TYPE);
        } catch (Exception e) {
            log.warn("读取数据总览预计算失败: {}", infoPath(data), e);
            return null;
        }
    }

    public static List<Object> readLines(MatsimData data) {
        if (!isVisualCacheReady(data)) {
            return null;
        }
        try {
            return readGzipJson(linesPath(data), LIST_TYPE);
        } catch (Exception e) {
            log.warn("读取线路预计算失败: {}", linesPath(data), e);
            return null;
        }
    }

    public static List<Object> readStations(MatsimData data) {
        if (!isVisualCacheReady(data)) {
            return null;
        }
        try {
            return readGzipJson(stationsPath(data), LIST_TYPE);
        } catch (Exception e) {
            log.warn("读取站点预计算失败: {}", stationsPath(data), e);
            return null;
        }
    }

    public static List<Object> readNetworkTile(MatsimData data, int z, int tileX, int tileY) {
        if (!isVisualCacheReady(data)) {
            return null;
        }
        return readTile(data, NETWORK_TILES_DIR, z, tileX, tileY);
    }

    public static List<Object> readRouteTile(MatsimData data, int z, int tileX, int tileY) {
        if (!isVisualCacheReady(data)) {
            return null;
        }
        return readTile(data, ROUTE_TILES_DIR, z, tileX, tileY);
    }

    public static RouteDetailVO readRouteDetail(MatsimData data, String routeId) {
        if (!isVisualCacheReady(data)) {
            return null;
        }
        if (routeId == null || routeId.isBlank()) {
            return null;
        }
        try {
            Map<String, String> index = JSON.readValue(routeIndexPath(data).toFile(), STRING_MAP_TYPE);
            String file = index.get(routeId);
            if (file == null || file.isBlank()) {
                return null;
            }
            Map<String, RouteDetailVO> shard = readGzipJson(routeDetailsDir(data).resolve(file), ROUTE_DETAIL_MAP_TYPE);
            return shard.get(routeId);
        } catch (Exception e) {
            log.warn("读取线路详情预计算失败: model={}, routeId={}", data.getName(), routeId, e);
            return null;
        }
    }

    private static synchronized void ensureVisualCache(MatsimData data) {
        if (isVisualCacheReady(data)) {
            return;
        }
        try {
            deleteDirectory(cacheDir(data));
            Files.createDirectories(cacheDir(data));
            Map<String, Object> info = buildInfo(data);
            List<LineVO> routeDetails = buildLines(data);
            List<LineVO> lines = buildLineSummaries(routeDetails);
            List<FacilityVO> stations = buildStations(data);
            Map<String, List<PTLink>> networkTiles = buildNetworkTiles(data);
            Map<String, List<PTLink>> routeTiles = buildRouteTiles(data);

            writeJsonAtomic(infoPath(data), info);
            writeGzipJson(linesPath(data), lines);
            writeGzipJson(stationsPath(data), stations);
            writeTileDirectory(data, NETWORK_TILES_DIR, VISUAL_TILE_ZOOM, networkTiles);
            writeTileDirectory(data, ROUTE_TILES_DIR, VISUAL_TILE_ZOOM, routeTiles);
            writeJsonAtomic(routeIndexPath(data), writeRouteDetails(data, routeDetails));
            writeJsonAtomic(manifestPath(data), manifest(data, true));

            log.info("模型可视化预计算完成: model={}, lines={}, stations={}, networkTiles={}, routeTiles={}",
                    data.getName(), lines.size(), stations.size(), networkTiles.size(), routeTiles.size());
        } catch (Exception e) {
            try {
                writeJsonAtomic(manifestPath(data), manifest(data, false));
            } catch (Exception ignored) {
            }
            throw new RuntimeException(e);
        }
    }

    public static boolean isVisualCacheReady(MatsimData data) {
        Path manifestPath = manifestPath(data);
        if (!Files.exists(manifestPath)
                || !Files.exists(infoPath(data))
                || !Files.exists(linesPath(data))
                || !Files.exists(stationsPath(data))
                || !Files.exists(routeIndexPath(data))
                || !Files.isDirectory(tileDir(data, NETWORK_TILES_DIR, VISUAL_TILE_ZOOM))
                || !Files.isDirectory(tileDir(data, ROUTE_TILES_DIR, VISUAL_TILE_ZOOM))
                || !Files.isDirectory(routeDetailsDir(data))) {
            return false;
        }
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath.toFile(), MAP_TYPE);
            return "ready".equals(manifest.get("status"))
                    && VISUAL_CACHE_VERSION.equals(manifest.get("cacheVersion"))
                    && sameSources(data, manifest);
        } catch (Exception e) {
            log.warn("可视化缓存状态读取失败: {}", manifestPath, e);
            return false;
        }
    }

    private static Map<String, Object> buildInfo(MatsimData data) {
        Map<String, Object> result = new LinkedHashMap<>();
        int area = Math.max(1, (int) data.getArea());
        int personCount = data.getPersonTracks().stream().collect(Collectors.groupingBy(PTPersonTrack::getPersonId)).size();
        result.put("czrkmd", personCount / area);

        double networkLength = ptNetworkLength(data.getSchedule(), data.getNetwork());
        result.put("gjxwmd", round2((networkLength / 1000.0) / area));

        Set<Coord> coords = data.getSchedule().getFacilities().values().stream()
                .map(item -> (Coord) item.getCoord())
                .collect(Collectors.toSet());
        result.put("fgl_300", coverage300(coords, data.getPopulation()));
        result.put("fxfdl", legTypeRate(data.getPopulation()));

        int vehNum = Math.max(1, data.getPersonTracks().stream().collect(Collectors.groupingBy(PTPersonTrack::getVehicleId)).size());
        int boardings = (int) data.getPersonTracks().stream().filter(PTPersonTrack::getEnter).count();
        int cjrzkl = data.getPersonTracks().size() / vehNum;
        result.put("cjrzkl", cjrzkl);
        result.put("dbczkl", cjrzkl);
        result.put("rcxcs", boardings);
        result.put("ylklbl", 50.0);
        result.put("xlfzxxs", routeNoLC(data));
        result.put("xlcfxs", routeRC(data));
        result.put("xlmzl", fullLoadRate(data));

        Map<String, Double> xlklqd = routePersonStrength(data);
        result.put("xlklqd", xlklqd.entrySet().stream()
                .limit(5)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (oldValue, newValue) -> oldValue,
                        LinkedHashMap::new
                )));
        result.put("xlklqd_sum", round2(xlklqd.values().stream().mapToDouble(Double::doubleValue).sum()));
        result.put("yxsdb", runSpeed(data.getPopulation()));
        result.put("pjhcsj", avgAwaitTime(data.getPopulation()));
        return result;
    }

    private static List<LineVO> buildLines(MatsimData data) {
        List<LineVO> lineList = new ArrayList<>();
        Network network = data.getNetwork();
        for (Map.Entry<Id<TransitLine>, TransitLine> line : data.getSchedule().getTransitLines().entrySet()) {
            TransitLine transitLine = line.getValue();
            LineVO vo = new LineVO();
            vo.setLineName(transitLine.getName());
            vo.setLineId(transitLine.getId().toString());
            List<RouteDetailVO> routes = new ArrayList<>();
            for (TransitRoute route : transitLine.getRoutes().values()) {
                routes.add(new RouteDetailVO(route, network));
            }
            vo.setRoutes(routes);
            vo.setMode(lineMode(routes));
            lineList.add(vo);
        }
        return lineList;
    }

    private static List<LineVO> buildLineSummaries(List<LineVO> lines) {
        List<LineVO> result = new ArrayList<>(lines.size());
        for (LineVO sourceLine : lines) {
            LineVO line = new LineVO();
            line.setLineId(sourceLine.getLineId());
            line.setLineName(sourceLine.getLineName());
            line.setMode(sourceLine.getMode());
            List<RouteDetailVO> routes = new ArrayList<>();
            if (sourceLine.getRoutes() != null) {
                for (RouteDetailVO sourceRoute : sourceLine.getRoutes()) {
                    RouteDetailVO route = new RouteDetailVO();
                    route.setRouteId(sourceRoute.getRouteId());
                    route.setRouteName(sourceRoute.getRouteName());
                    route.setTransportMode(sourceRoute.getTransportMode());
                    route.setMode(sourceRoute.getMode());
                    route.setInfo(sourceRoute.getInfo());
                    route.setFacilities(sourceRoute.getFacilities());
                    route.setDepartures(List.of());
                    route.setLinks(List.of());
                    routes.add(route);
                }
            }
            line.setRoutes(routes);
            result.add(line);
        }
        return result;
    }

    private static String lineMode(List<RouteDetailVO> routes) {
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

    private static List<FacilityVO> buildStations(MatsimData data) {
        List<FacilityVO> result = new ArrayList<>();
        data.getSchedule().getFacilities().forEach((facilityId, facility) -> {
            FacilityVO vo = new FacilityVO();
            vo.setFacilityName(facility.getName());
            vo.setFacilityId(facilityId.toString());
            vo.setCoord(new PTCoord(facility.getCoord()));
            result.add(vo);
        });
        return result;
    }

    private static Map<String, List<PTLink>> buildNetworkTiles(MatsimData data) {
        Map<String, Double> flows = readLinkFlows(data.getOutfile().getLinkstats());
        Map<String, List<PTLink>> result = new LinkedHashMap<>();
        data.getNetwork().getLinks().forEach((linkId, link) -> {
            addLinkToCoveredTiles(result, link, PTLink.base(link, flows.getOrDefault(linkId.toString(), 0D)));
        });
        return result;
    }

    private static Map<String, List<PTLink>> buildRouteTiles(MatsimData data) {
        Map<String, PTLink> uniqueLinks = new LinkedHashMap<>();
        Network network = data.getNetwork();
        data.getSchedule().getTransitLines().values().forEach(line -> line.getRoutes().values().forEach(route -> {
            NetworkRoute networkRoute = route.getRoute();
            addRouteLink(uniqueLinks, network.getLinks().get(networkRoute.getStartLinkId()));
            for (Id<Link> linkId : networkRoute.getLinkIds()) {
                addRouteLink(uniqueLinks, network.getLinks().get(linkId));
            }
            addRouteLink(uniqueLinks, network.getLinks().get(networkRoute.getEndLinkId()));
        }));

        Map<String, List<PTLink>> result = new LinkedHashMap<>();
        for (PTLink link : uniqueLinks.values()) {
            Link networkLink = network.getLinks().get(Id.createLinkId(link.getLinkId()));
            if (networkLink == null) {
                continue;
            }
            addLinkToCoveredTiles(result, networkLink, link);
        }
        return result;
    }

    private static void addLinkToCoveredTiles(Map<String, List<PTLink>> tiles, Link networkLink, PTLink payload) {
        if (networkLink == null || payload == null) {
            return;
        }
        List<Coord> coords = List.of(
                networkLink.getFromNode().getCoord(),
                networkLink.getToNode().getCoord(),
                networkLink.getCoord()
        );
        int maxTile = (1 << VISUAL_TILE_ZOOM) - 1;
        int minX = maxTile;
        int minY = maxTile;
        int maxX = 0;
        int maxY = 0;
        for (Coord coord : coords) {
            int[] tile = coordInTile(coord, VISUAL_TILE_ZOOM);
            minX = Math.min(minX, tile[0]);
            minY = Math.min(minY, tile[1]);
            maxX = Math.max(maxX, tile[0]);
            maxY = Math.max(maxY, tile[1]);
        }

        minX = Math.max(0, minX);
        minY = Math.max(0, minY);
        maxX = Math.min(maxTile, maxX);
        maxY = Math.min(maxTile, maxY);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                tiles.computeIfAbsent(tileKey(x, y), key -> new ArrayList<>()).add(payload);
            }
        }
    }

    private static void addRouteLink(Map<String, PTLink> result, Link link) {
        if (link != null) {
            result.putIfAbsent(link.getId().toString(), PTLink.base(link));
        }
    }

    private static double ptNetworkLength(TransitSchedule schedule, Network network) {
        double length = 0;
        Set<Id<Link>> links = new HashSet<>();
        schedule.getTransitLines().forEach((lineId, line) -> line.getRoutes().forEach((routeId, route) -> {
            NetworkRoute networkRoute = route.getRoute();
            links.add(networkRoute.getStartLinkId());
            links.addAll(networkRoute.getLinkIds());
            links.add(networkRoute.getEndLinkId());
        }));
        for (Id<Link> linkId : links) {
            Link link = network.getLinks().get(linkId);
            if (link != null) {
                length += link.getLength();
            }
        }
        return length;
    }

    private static Map<String, Double> runSpeed(Population population) {
        double ptTime = 0.0;
        double ptDist = 0.0;
        double carTime = 0.0;
        double carDist = 0.0;
        for (Person person : population.getPersons().values()) {
            for (PlanElement element : person.getSelectedPlan().getPlanElements()) {
                if (element instanceof Leg leg && leg.getTravelTime().isDefined() && leg.getRoute() != null) {
                    if (Constant.ROUTE_MODE_PT.equals(leg.getMode())) {
                        ptTime += leg.getTravelTime().seconds();
                        ptDist += leg.getRoute().getDistance();
                    } else if (Constant.ROUTE_MODE_CAR.equals(leg.getMode())) {
                        carTime += leg.getTravelTime().seconds();
                        carDist += leg.getRoute().getDistance();
                    }
                }
            }
        }
        Map<String, Double> result = new LinkedHashMap<>();
        result.put("ptAvg", round2(ptTime == 0 ? 0 : ptDist / ptTime * 3.6));
        result.put("carAvg", round2(carTime == 0 ? 0 : carDist / carTime * 3.6));
        return result;
    }

    private static double routeRC(MatsimData data) {
        double length = 0.0;
        Set<Id<Link>> links = new HashSet<>();
        for (TransitLine transitLine : data.getSchedule().getTransitLines().values()) {
            for (TransitRoute transitRoute : transitLine.getRoutes().values()) {
                NetworkRoute networkRoute = transitRoute.getRoute();
                length += DistanceUtil.distance(networkRoute, data.getNetwork());
                links.add(networkRoute.getStartLinkId());
                links.addAll(networkRoute.getLinkIds());
                links.add(networkRoute.getEndLinkId());
            }
        }
        double uniqueLength = 0.0;
        for (Id<Link> linkId : links) {
            Link link = data.getNetwork().getLinks().get(linkId);
            if (link != null) {
                uniqueLength += NetworkUtils.getEuclideanDistance(link.getFromNode().getCoord(), link.getToNode().getCoord());
            }
        }
        return uniqueLength == 0 ? 0 : round2(length / uniqueLength);
    }

    private static double routeNoLC(MatsimData data) {
        int routeCount = 0;
        double value = 0.0;
        for (TransitLine transitLine : data.getSchedule().getTransitLines().values()) {
            for (TransitRoute transitRoute : transitLine.getRoutes().values()) {
                NetworkRoute networkRoute = transitRoute.getRoute();
                double distance = DistanceUtil.distance(networkRoute, data.getNetwork());
                if (transitRoute.getStops().isEmpty()) {
                    continue;
                }
                TransitRouteStop first = transitRoute.getStops().getFirst();
                TransitRouteStop last = transitRoute.getStops().getLast();
                double straight = NetworkUtils.getEuclideanDistance(first.getStopFacility().getCoord(), last.getStopFacility().getCoord());
                if (straight > 0) {
                    value += distance / straight;
                    routeCount++;
                }
            }
        }
        return routeCount == 0 ? 0 : round2(value / routeCount);
    }

    private static Map<String, Double> routePersonStrength(MatsimData data) {
        Map<String, Double> result = new HashMap<>();
        Map<RouteId, List<PTPersonTrack>> routeTracks = data.getPersonTracks().stream()
                .filter(PTPersonTrack::getEnter)
                .collect(Collectors.groupingBy(PTPersonTrack::getRouteId));
        for (TransitLine transitLine : data.getSchedule().getTransitLines().values()) {
            for (Map.Entry<Id<TransitRoute>, TransitRoute> route : transitLine.getRoutes().entrySet()) {
                double distance = DistanceUtil.distance(route.getValue().getRoute(), data.getNetwork());
                List<PTPersonTrack> tracks = routeTracks.get(RouteId.create(route.getKey()));
                double passenger = tracks == null ? 0.0 : tracks.size();
                result.put(route.getKey().toString(), distance == 0 ? 0 : round2(passenger / (distance / 1000.0)));
            }
        }
        return result.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (oldValue, newValue) -> oldValue,
                        LinkedHashMap::new
                ));
    }

    private static double fullLoadRate(MatsimData data) {
        Map<VehicleId, List<PTPersonTrack>> grouped = data.getPersonTracks().stream().collect(Collectors.groupingBy(PTPersonTrack::getVehicleId));
        double capacity = 0.0;
        double passengers = 0.0;
        for (Map.Entry<VehicleId, List<PTPersonTrack>> entry : grouped.entrySet()) {
            Vehicle vehicle = data.getTv().getVehicles().get(entry.getKey());
            if (vehicle == null || vehicle.getType() == null) {
                continue;
            }
            VehicleType type = vehicle.getType();
            capacity += type.getCapacity().getSeats();
            capacity += type.getCapacity().getStandingRoom();
            passengers += entry.getValue().stream().filter(PTPersonTrack::getEnter).count();
        }
        return capacity == 0 ? 0 : round2((passengers / capacity) * 100.0);
    }

    private static double[] avgAwaitTime(Population population) {
        double[][] hours = new double[24][2];
        for (Person person : population.getPersons().values()) {
            List<PlanElement> elements = person.getSelectedPlan().getPlanElements();
            for (int i = 0; i < elements.size(); i++) {
                PlanElement element = elements.get(i);
                if (element instanceof Leg leg && Constant.ROUTE_MODE_PT.equals(leg.getMode())) {
                    if (i < 2 || !leg.getDepartureTime().isDefined()) {
                        continue;
                    }
                    PlanElement previous = elements.get(i - 2);
                    if (!(previous instanceof Leg previousLeg)
                            || !previousLeg.getDepartureTime().isDefined()
                            || !previousLeg.getTravelTime().isDefined()) {
                        continue;
                    }
                    double start = previousLeg.getDepartureTime().seconds() + previousLeg.getTravelTime().seconds();
                    int hour = (int) (start / 3600);
                    if (hour >= 0 && hour < 24) {
                        hours[hour][0] += leg.getDepartureTime().seconds() - start;
                        hours[hour][1]++;
                    }
                }
            }
        }
        double[] result = new double[24];
        for (int i = 0; i < 24; i++) {
            result[i] = hours[i][1] == 0 ? 0.0 : round2(hours[i][0] / hours[i][1]);
        }
        return result;
    }

    private static Map<String, Integer> legType(Population population) {
        Map<String, Integer> types = new HashMap<>();
        population.getPersons().values().forEach(person -> person.getSelectedPlan().getPlanElements().forEach(element -> {
            if (element instanceof Leg leg) {
                types.merge(leg.getMode(), 1, Integer::sum);
            }
        }));
        return types;
    }

    private static Map<String, Double> legTypeRate(Population population) {
        Map<String, Integer> types = legType(population);
        int count = types.values().stream().mapToInt(Integer::intValue).sum();
        Map<String, Double> result = new LinkedHashMap<>();
        if (count == 0) {
            return result;
        }
        BigDecimal total = BigDecimal.valueOf(count);
        types.forEach((mode, value) -> result.put(mode, new BigDecimal(value).divide(total, 2, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).doubleValue()));
        return result;
    }

    private static Map<String, Double> coverage300(Set<Coord> coords, Population population) {
        Map<String, Double> coverage = new LinkedHashMap<>();
        coverage.put("cover", 50.0);
        coverage.put("notcover", 50.0);
        if (coords == null || coords.isEmpty() || population == null || population.getPersons().isEmpty()) {
            return coverage;
        }
        STRtree index = new STRtree();
        for (Coord coord : coords) {
            index.insert(new Envelope(coord.getX() - 300, coord.getX() + 300, coord.getY() - 300, coord.getY() + 300), coord);
        }
        index.build();
        long total = population.getPersons().size();
        long in = population.getPersons().values().parallelStream()
                .filter(person -> personInCoverage(person, index))
                .count();
        double cover = round2((total - in) * 100.0 / total);
        coverage.put("cover", cover);
        coverage.put("notcover", round2(100.0 - cover));
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
                    if (dx * dx + dy * dy <= 300 * 300) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Map<String, Double> readLinkFlows(String linkstatsPath) {
        Map<String, Double> flows = new HashMap<>();
        if (linkstatsPath == null || linkstatsPath.isBlank()) {
            return flows;
        }
        Path path = Path.of(linkstatsPath);
        if (!Files.isRegularFile(path)) {
            return flows;
        }
        try (BufferedReader reader = openReader(path)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return flows;
            }
            char delimiter = detectDelimiter(headerLine);
            String[] headers = split(headerLine, delimiter);
            int linkIndex = findLinkIndex(headers);
            List<Integer> flowIndices = findFlowIndices(headers);
            if (linkIndex < 0 || flowIndices.isEmpty()) {
                return flows;
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] values = split(line, delimiter);
                if (linkIndex >= values.length) {
                    continue;
                }
                String linkId = clean(values[linkIndex]);
                if (linkId.isBlank()) {
                    continue;
                }
                double flow = 0.0;
                boolean hasFlow = false;
                for (Integer flowIndex : flowIndices) {
                    if (flowIndex < values.length) {
                        Double value = parseDouble(values[flowIndex]);
                        if (value != null) {
                            flow += value;
                            hasFlow = true;
                        }
                    }
                }
                if (hasFlow) {
                    flows.put(linkId, flow);
                }
            }
        } catch (Exception e) {
            log.warn("读取 linkstats 失败: {}", linkstatsPath, e);
        }
        return flows;
    }

    private static BufferedReader openReader(Path path) throws Exception {
        InputStream input = Files.newInputStream(path);
        if (path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz")) {
            input = new GZIPInputStream(input);
        }
        return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    }

    private static char detectDelimiter(String headerLine) {
        if (headerLine.indexOf('\t') >= 0) return '\t';
        if (headerLine.indexOf(';') >= 0) return ';';
        return ',';
    }

    private static String[] split(String line, char delimiter) {
        return line.split(Pattern.quote(String.valueOf(delimiter)), -1);
    }

    private static int findLinkIndex(String[] headers) {
        for (int i = 0; i < headers.length; i++) {
            String header = normalizeHeader(headers[i]);
            if (header.equals("link") || header.equals("link_id") || header.equals("linkid") || header.contains("link_id")) {
                return i;
            }
        }
        return -1;
    }

    private static List<Integer> findFlowIndices(String[] headers) {
        List<Integer> exact = new ArrayList<>();
        List<Integer> series = new ArrayList<>();
        for (int i = 0; i < headers.length; i++) {
            String header = normalizeHeader(headers[i]);
            if (header.equals("simulated_traffic_volume") || header.equals("traffic_volume") || header.equals("simulated_volume") || header.equals("flow") || header.equals("volume")) {
                exact.add(i);
            } else if (isFlowSeriesHeader(header)) {
                series.add(i);
            }
        }
        return exact.isEmpty() ? series : exact;
    }

    private static boolean isFlowSeriesHeader(String header) {
        if (header.contains("capacity") || header.contains("lane") || header.contains("speed") || header.contains("length") || header.contains("coord")) {
            return false;
        }
        return header.startsWith("vol") || header.contains("_vol") || header.contains("volume") || header.contains("traffic") || header.matches("hrs\\d+_\\d+avg");
    }

    private static String normalizeHeader(String value) {
        return clean(value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static String clean(String value) {
        if (value == null) return "";
        String result = value.replace("\uFEFF", "").trim();
        if (result.length() >= 2 && ((result.startsWith("\"") && result.endsWith("\"")) || (result.startsWith("'") && result.endsWith("'")))) {
            return result.substring(1, result.length() - 1).trim();
        }
        return result;
    }

    private static Double parseDouble(String value) {
        String text = clean(value).replace(",", "");
        if (text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, Object> manifest(MatsimData data, boolean ready) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("status", ready ? "ready" : "failed");
        manifest.put("cacheVersion", VISUAL_CACHE_VERSION);
        manifest.put("generatedAt", System.currentTimeMillis());
        sourceFingerprint(data, manifest);
        return manifest;
    }

    private static void sourceFingerprint(MatsimData data, Map<String, Object> result) {
        putFileFingerprint(result, "events", data.getOutfile().getEvents());
        putFileFingerprint(result, "network", data.getOutfile().getNetwork());
        putFileFingerprint(result, "schedule", data.getOutfile().getTransitSchedule());
        putFileFingerprint(result, "plans", data.getOutfile().getPlans());
        putFileFingerprint(result, "linkstats", data.getOutfile().getLinkstats());
    }

    private static void putFileFingerprint(Map<String, Object> result, String key, String filePath) {
        result.put(key + "File", filePath);
        result.put(key + "Modified", lastModified(filePath));
        result.put(key + "Size", fileSize(filePath));
    }

    private static boolean sameSources(MatsimData data, Map<String, Object> manifest) {
        Map<String, Object> current = new LinkedHashMap<>();
        sourceFingerprint(data, current);
        for (Map.Entry<String, Object> entry : current.entrySet()) {
            Object oldValue = manifest.get(entry.getKey());
            if (entry.getValue() instanceof Number number) {
                if (!(oldValue instanceof Number oldNumber) || oldNumber.longValue() != number.longValue()) {
                    return false;
                }
            } else if (!String.valueOf(entry.getValue()).equals(String.valueOf(oldValue))) {
                return false;
            }
        }
        return true;
    }

    private static void writeJsonAtomic(Path path, Object payload) throws Exception {
        Files.createDirectories(path.getParent());
        Path tmpPath = path.resolveSibling(path.getFileName() + ".tmp");
        JSON.writeValue(tmpPath.toFile(), payload);
        try {
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeGzipJson(Path path, Object payload) throws Exception {
        Files.createDirectories(path.getParent());
        Path tmpPath = path.resolveSibling(path.getFileName() + ".tmp");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmpPath))) {
            JSON.writeValue(out, payload);
        }
        try {
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static <T> T readGzipJson(Path path, TypeReference<T> type) throws Exception {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(path))) {
            return JSON.readValue(in, type);
        }
    }

    private static List<Object> readTile(MatsimData data, String tileDir, int z, int tileX, int tileY) {
        int zoom = normalizeTileZoom(z);
        if (tileX == 0 && tileY == 0 && data.getCenter() != null) {
            int[] centerTile = coordInTile(data.getCenter(), zoom);
            tileX = centerTile[0];
            tileY = centerTile[1];
        }
        if (zoom < VISUAL_TILE_ZOOM) {
            return readAggregatedTile(data, tileDir, zoom, tileX, tileY);
        }
        Path path = tilePath(data, tileDir, zoom, tileX, tileY);
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            return readGzipJson(path, LIST_TYPE);
        } catch (Exception e) {
            log.warn("读取瓦片预计算失败: {}", path, e);
            return null;
        }
    }

    private static List<Object> readAggregatedTile(MatsimData data, String tileDir, int z, int tileX, int tileY) {
        int factor = 1 << (VISUAL_TILE_ZOOM - z);
        int minX = tileX * factor;
        int minY = tileY * factor;
        int maxX = minX + factor - 1;
        int maxY = minY + factor - 1;
        Map<String, Object> deduped = new LinkedHashMap<>();
        List<Object> anonymous = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                Path childPath = tilePath(data, tileDir, VISUAL_TILE_ZOOM, x, y);
                if (!Files.exists(childPath)) {
                    continue;
                }
                try {
                    List<Object> child = readGzipJson(childPath, LIST_TYPE);
                    for (Object item : child) {
                        String key = linkKey(item);
                        if (key == null) {
                            anonymous.add(item);
                        } else {
                            deduped.putIfAbsent(key, item);
                        }
                    }
                } catch (Exception e) {
                    log.warn("聚合瓦片读取失败: {}", childPath, e);
                    return null;
                }
            }
        }
        if (deduped.isEmpty()) {
            return anonymous.isEmpty() ? List.of() : anonymous;
        }
        List<Object> result = new ArrayList<>(deduped.size() + anonymous.size());
        result.addAll(deduped.values());
        result.addAll(anonymous);
        return result;
    }

    private static String linkKey(Object item) {
        if (item instanceof Map<?, ?> map) {
            Object linkId = map.get("linkId");
            return linkId == null ? null : linkId.toString();
        }
        if (item instanceof PTLink link && link.getLinkId() != null) {
            return link.getLinkId();
        }
        return null;
    }

    private static void writeTileDirectory(MatsimData data, String tileDir, int z, Map<String, List<PTLink>> tiles) throws Exception {
        Path dir = tileDir(data, tileDir, z);
        deleteDirectory(dir);
        Files.createDirectories(dir);
        for (Map.Entry<String, List<PTLink>> entry : tiles.entrySet()) {
            String[] xy = entry.getKey().split(",", 2);
            if (xy.length != 2) {
                continue;
            }
            Path path = dir.resolve(xy[0] + "_" + xy[1] + ".json.gz");
            writeGzipJson(path, entry.getValue());
        }
    }

    private static Map<String, String> writeRouteDetails(MatsimData data, List<LineVO> lines) throws Exception {
        Path dir = routeDetailsDir(data);
        deleteDirectory(dir);
        Files.createDirectories(dir);
        Map<String, String> index = new LinkedHashMap<>();
        List<Map<String, RouteDetailVO>> shards = new ArrayList<>();
        for (int i = 0; i < ROUTE_DETAIL_SHARD_COUNT; i++) {
            shards.add(new LinkedHashMap<>());
        }
        for (LineVO line : lines) {
            if (line.getRoutes() == null) {
                continue;
            }
            for (RouteDetailVO route : line.getRoutes()) {
                if (route.getRouteId() == null || route.getRouteId().isBlank()) {
                    continue;
                }
                int shardIndex = Math.floorMod(route.getRouteId().hashCode(), ROUTE_DETAIL_SHARD_COUNT);
                String fileName = String.format(Locale.ROOT, "shard-%02d.json.gz", shardIndex);
                shards.get(shardIndex).put(route.getRouteId(), route);
                index.put(route.getRouteId(), fileName);
            }
        }
        for (int i = 0; i < shards.size(); i++) {
            writeGzipJson(dir.resolve(String.format(Locale.ROOT, "shard-%02d.json.gz", i)), shards.get(i));
        }
        return index;
    }

    private static void deleteDirectory(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path item : paths) {
                Files.deleteIfExists(item);
            }
        }
    }

    private static Path cacheDir(MatsimData data) {
        return MatsimCachePaths.versionDir(data, VISUAL_CACHE_VERSION);
    }

    private static Path manifestPath(MatsimData data) {
        return cacheDir(data).resolve("manifest.json");
    }

    private static Path infoPath(MatsimData data) {
        return cacheDir(data).resolve(INFO_FILE);
    }

    private static Path linesPath(MatsimData data) {
        return cacheDir(data).resolve(LINES_FILE);
    }

    private static Path stationsPath(MatsimData data) {
        return cacheDir(data).resolve(STATIONS_FILE);
    }

    private static Path routeIndexPath(MatsimData data) {
        return cacheDir(data).resolve(ROUTE_INDEX_FILE);
    }

    private static Path routeDetailsDir(MatsimData data) {
        return cacheDir(data).resolve(ROUTE_DETAILS_DIR);
    }

    private static Path tileDir(MatsimData data, String tileDir, int z) {
        return cacheDir(data).resolve(tileDir).resolve("z" + normalizeTileZoom(z));
    }

    private static Path tilePath(MatsimData data, String tileDir, int z, int tileX, int tileY) {
        return tileDir(data, tileDir, z).resolve(tileX + "_" + tileY + ".json.gz");
    }

    private static int normalizeTileZoom(int z) {
        if (z <= 0) {
            return VISUAL_TILE_ZOOM;
        }
        return Math.max(MIN_VISUAL_TILE_ZOOM, Math.min(VISUAL_TILE_ZOOM, z));
    }

    private static int[] coordInTile(Coord coord, int z) {
        int zoom = normalizeTileZoom(z);
        int col = (int) Math.floor(((TileNetwork.EARTH_RADIUS + coord.getX()) * Math.pow(2, zoom)) / (TileNetwork.EARTH_RADIUS * 2));
        int row = (int) Math.floor(((TileNetwork.EARTH_RADIUS - coord.getY()) * Math.pow(2, zoom)) / (TileNetwork.EARTH_RADIUS * 2));
        return new int[]{col, row};
    }

    private static String tileKey(int tileX, int tileY) {
        return tileX + "," + tileY;
    }

    private static long lastModified(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return 0L;
        }
        try {
            Path path = Path.of(filePath);
            return Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long fileSize(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return 0L;
        }
        try {
            Path path = Path.of(filePath);
            return Files.exists(path) ? Files.size(path) : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static double round2(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 100.0) / 100.0;
    }
}
