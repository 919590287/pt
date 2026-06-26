package com.jts.gjcxfzksh.data.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import com.jts.gjcxfzksh.utils.DistanceUtil;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Slf4j
public final class MatsimRoutePanelCache {

    // v4: 运营效益指标(班次/车辆/单班次/车日均) + 客流画像新增 购物/休闲/其他 类型，需重算缓存
    // v5: 客流画像按维度互斥单选(出行者属性: 老人/学生; 出行目的: 通勤/购物/休闲/其他)，各维度合计≤100%，需重算缓存
    public static final String ROUTE_PANEL_CACHE_VERSION = "route-panel-v5";

    private static final String PANEL_FILE = "route-panel.json.gz";
    private static final String MANIFEST_FILE = "manifest.json";
    private static final int HOURS = 24;
    private static final int TRANSFER_WINDOW_SECONDS = 1800;
    private static final int LEADERBOARD_LIMIT = 50;
    private static final int TRANSFER_LIMIT = 12;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Map<String, Map<String, Object>> MEMORY_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                    return size() > 2;
                }
            }
    );

    private MatsimRoutePanelCache() {
    }

    public static void prepareOnModelLoad(MatsimData data) {
        ensureRoutePanelCache(data);
        loadPanel(data);
    }

    /** Load an existing panel into memory while the model starts, without generating an incomplete cache. */
    public static void preloadIfReady(MatsimData data) {
        if (isReady(data)) {
            try {
                loadPanel(data);
            } catch (RuntimeException e) {
                log.warn("预热线路客流面板缓存失败，将在首次请求时重试: model={}", data.getName(), e);
            }
        }
    }

    public static Map<String, Object> readRoutePanel(MatsimData data) {
        if (!isReady(data)) {
            return Map.of(
                    "status", "generating",
                    "cacheVersion", ROUTE_PANEL_CACHE_VERSION,
                    "message", "线路客流缓存正在后台生成"
            );
        }
        try {
            return loadPanel(data);
        } catch (Exception e) {
            log.warn("读取线路客流面板缓存失败: model={}, path={}", data.getName(), panelPath(data), e);
            return Map.of();
        }
    }

    public static Map<String, Object> readRoutePanelDetail(MatsimData data, String routeId) {
        if (routeId == null || routeId.isBlank()) return Map.of();
        Map<String, Object> panel = readRoutePanel(data);
        Object routesValue = panel.get("routes");
        if (!(routesValue instanceof Map<?, ?> routes)) return Map.of();
        Object routeValue = routes.get(routeId);
        if (!(routeValue instanceof Map<?, ?> route)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        route.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static Map<String, Object> loadPanel(MatsimData data) {
        String cacheKey = panelPath(data).toAbsolutePath().normalize().toString();
        Map<String, Object> cached = MEMORY_CACHE.get(cacheKey);
        if (cached != null) return cached;
        synchronized (MEMORY_CACHE) {
            cached = MEMORY_CACHE.get(cacheKey);
            if (cached != null) return cached;
            try {
                cached = readGzipJson(panelPath(data));
                MEMORY_CACHE.put(cacheKey, cached);
                return cached;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static synchronized void ensureRoutePanelCache(MatsimData data) {
        if (isReady(data)) {
            return;
        }
        try {
            Files.createDirectories(cacheDir(data));
            Map<String, Object> payload = buildPanel(data);
            writeGzipJson(panelPath(data), payload);
            writeJsonAtomic(manifestPath(data), manifest(data, true));
            log.info("线路客流面板缓存生成完成: model={}, routes={}",
                    data.getName(), ((Map<?, ?>) payload.getOrDefault("routes", Map.of())).size());
        } catch (Exception e) {
            try {
                Files.createDirectories(cacheDir(data));
                writeJsonAtomic(manifestPath(data), manifest(data, false));
            } catch (Exception ignored) {
            }
            throw new RuntimeException("线路客流面板缓存生成失败: " + e.getMessage(), e);
        }
    }

    public static boolean isReady(MatsimData data) {
        if (!Files.exists(manifestPath(data)) || !Files.exists(panelPath(data))) {
            return false;
        }
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath(data).toFile(), MAP_TYPE);
            return "ready".equals(manifest.get("status"))
                    && ROUTE_PANEL_CACHE_VERSION.equals(manifest.get("cacheVersion"))
                    && sameSources(data, manifest);
        } catch (Exception e) {
            log.warn("线路客流面板缓存状态读取失败: {}", manifestPath(data), e);
            return false;
        }
    }

    private static Map<String, Object> buildPanel(MatsimData data) {
        Map<String, RoutePanelAccumulator> routes = buildRouteAccumulators(data);
        indexPassengerTracks(data.getPersonTracks(), routes);
        indexTransfers(data.getPersonTracks(), routes);
        Population population = data.getPopulation();

        Map<String, Object> routePayloads = new LinkedHashMap<>();
        for (RoutePanelAccumulator route : routes.values()) {
            route.finish(population);
            routePayloads.put(route.routeId, route.toPayload());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ready");
        result.put("cacheVersion", ROUTE_PANEL_CACHE_VERSION);
        result.put("generatedAt", System.currentTimeMillis());
        result.put("summary", buildSummary(routes.values()));
        result.put("routes", routePayloads);
        return result;
    }

    private static Map<String, RoutePanelAccumulator> buildRouteAccumulators(MatsimData data) {
        Map<String, RoutePanelAccumulator> result = new LinkedHashMap<>();
        Network network = data.getNetwork();
        for (Map.Entry<Id<TransitLine>, TransitLine> lineEntry : data.getSchedule().getTransitLines().entrySet()) {
            String lineId = lineEntry.getKey().toString();
            TransitLine line = lineEntry.getValue();
            String lineName = nonBlank(line.getName(), lineId);
            for (Map.Entry<Id<TransitRoute>, TransitRoute> routeEntry : line.getRoutes().entrySet()) {
                String routeId = routeEntry.getKey().toString();
                TransitRoute route = routeEntry.getValue();
                result.put(routeId, new RoutePanelAccumulator(data, network, lineId, lineName, routeId, route));
            }
        }
        return result;
    }

    private static void indexPassengerTracks(Collection<PTPersonTrack> tracks, Map<String, RoutePanelAccumulator> routes) {
        if (tracks == null || tracks.isEmpty()) {
            return;
        }
        for (PTPersonTrack track : tracks) {
            RoutePanelAccumulator route = routes.get(idString(track.getRouteId()));
            if (route != null) {
                route.addTrack(track);
            }
        }
    }

    private static void indexTransfers(Collection<PTPersonTrack> tracks, Map<String, RoutePanelAccumulator> routes) {
        if (tracks == null || tracks.isEmpty()) {
            return;
        }
        Map<String, List<PTPersonTrack>> byPerson = new HashMap<>();
        for (PTPersonTrack track : tracks) {
            String personId = idString(track.getPersonId());
            if (personId != null) {
                byPerson.computeIfAbsent(personId, ignored -> new ArrayList<>()).add(track);
            }
        }

        byPerson.values().parallelStream().forEach(personTracks -> {
            personTracks.sort(Comparator.comparingDouble(track -> track.getTime() == null ? 0.0 : track.getTime()));
            for (int i = 0; i + 1 < personTracks.size(); i++) {
                PTPersonTrack leave = personTracks.get(i);
                PTPersonTrack enter = personTracks.get(i + 1);
                if (Boolean.TRUE.equals(leave.getEnter()) || !Boolean.TRUE.equals(enter.getEnter())) {
                    continue;
                }
                double delta = safeTime(enter) - safeTime(leave);
                if (delta < 0 || delta > TRANSFER_WINDOW_SECONDS) {
                    continue;
                }
                RoutePanelAccumulator fromRoute = routes.get(idString(leave.getRouteId()));
                RoutePanelAccumulator toRoute = routes.get(idString(enter.getRouteId()));
                if (fromRoute == null || toRoute == null || fromRoute.lineId.equals(toRoute.lineId)) {
                    continue;
                }
                if (!sameTransferStation(fromRoute, leave, toRoute, enter)) {
                    continue;
                }
                int hour = hourOf(safeTime(enter));
                fromRoute.addTransfer(toRoute, idString(leave.getFacilityId()), hour);
                toRoute.addTransfer(fromRoute, idString(enter.getFacilityId()), hour);
            }
        });
    }

    private static boolean sameTransferStation(
            RoutePanelAccumulator fromRoute,
            PTPersonTrack leave,
            RoutePanelAccumulator toRoute,
            PTPersonTrack enter
    ) {
        String leaveFacilityId = idString(leave.getFacilityId());
        String enterFacilityId = idString(enter.getFacilityId());
        if (leaveFacilityId != null && leaveFacilityId.equals(enterFacilityId)) {
            return true;
        }
        String leaveStation = fromRoute.stationName(leaveFacilityId);
        String enterStation = toRoute.stationName(enterFacilityId);
        return !leaveStation.isBlank()
                && !"--".equals(leaveStation)
                && leaveStation.equals(enterStation);
    }

    private static Map<String, Object> buildSummary(Collection<RoutePanelAccumulator> routes) {
        Map<String, LineLeaderboardAccumulator> lineStats = new LinkedHashMap<>();
        long totalBoardings = 0;
        long totalAlightings = 0;
        for (RoutePanelAccumulator route : routes) {
            totalBoardings += route.totalBoardings;
            totalAlightings += route.totalAlightings;
            LineLeaderboardAccumulator line = lineStats.computeIfAbsent(route.lineId,
                    ignored -> new LineLeaderboardAccumulator(route.lineId, route.lineName, route.mode, route.desc));
            line.add(route);
        }

        Map<String, Object> leaderboard = new LinkedHashMap<>();
        leaderboard.put("bus", leaderboard(lineStats.values(), "bus"));
        leaderboard.put("subway", leaderboard(lineStats.values(), "subway"));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("routeCount", routes.size());
        summary.put("lineCount", lineStats.size());
        summary.put("totalBoardings", totalBoardings);
        summary.put("totalAlightings", totalAlightings);
        summary.put("leaderboard", leaderboard);
        return summary;
    }

    private static List<Map<String, Object>> leaderboard(Collection<LineLeaderboardAccumulator> lines, String mode) {
        return lines.stream()
                .filter(line -> mode.equals(line.mode))
                .sorted(Comparator.comparingLong(LineLeaderboardAccumulator::passengerFlow).reversed())
                .limit(LEADERBOARD_LIMIT)
                .map(LineLeaderboardAccumulator::toPayload)
                .toList();
    }

    private static Map<String, Object> manifest(MatsimData data, boolean ready) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", ready ? "ready" : "failed");
        result.put("cacheVersion", ROUTE_PANEL_CACHE_VERSION);
        result.put("generatedAt", System.currentTimeMillis());
        sourceFingerprint(data, result);
        return result;
    }

    private static void sourceFingerprint(MatsimData data, Map<String, Object> result) {
        putFileFingerprint(result, "events", data.getOutfile().getEvents());
        putFileFingerprint(result, "network", data.getOutfile().getNetwork());
        putFileFingerprint(result, "schedule", data.getOutfile().getTransitSchedule());
        putFileFingerprint(result, "vehicles", data.getOutfile().getTransitVehicles());
        putFileFingerprint(result, "plans", data.getOutfile().getPlans());
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

    private static void writeJsonAtomic(Path path, Map<String, Object> payload) throws Exception {
        Files.createDirectories(path.getParent());
        Path tmpPath = path.resolveSibling(path.getFileName() + ".tmp");
        JSON.writeValue(tmpPath.toFile(), payload);
        try {
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tmpPath, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeGzipJson(Path path, Map<String, Object> payload) throws Exception {
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

    private static Map<String, Object> readGzipJson(Path path) throws Exception {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(path))) {
            return JSON.readValue(in, MAP_TYPE);
        }
    }

    private static Path cacheDir(MatsimData data) {
        return MatsimCachePaths.versionDir(data, ROUTE_PANEL_CACHE_VERSION);
    }

    private static Path manifestPath(MatsimData data) {
        return cacheDir(data).resolve(MANIFEST_FILE);
    }

    private static Path panelPath(MatsimData data) {
        return cacheDir(data).resolve(PANEL_FILE);
    }

    private static long lastModified(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return 0L;
        }
        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                return 0L;
            }
            return Files.getLastModifiedTime(path).toMillis();
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
            if (!Files.exists(path)) {
                return 0L;
            }
            return Files.size(path);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static int hourOf(double seconds) {
        if (Double.isNaN(seconds) || Double.isInfinite(seconds)) {
            return 0;
        }
        return Math.max(0, Math.min(HOURS - 1, (int) Math.floor(Math.max(0, seconds) / 3600.0)));
    }

    private static double safeTime(PTPersonTrack track) {
        Double time = track.getTime();
        if (time == null || Double.isNaN(time) || Double.isInfinite(time)) {
            return 0.0;
        }
        return time;
    }

    private static String idString(Object value) {
        return value == null ? null : value.toString();
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double percent(double numerator, double denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return round2(Math.min(100.0, numerator * 100.0 / denominator));
    }

    private static int intSum(int[] values) {
        int result = 0;
        for (int value : values) {
            result += value;
        }
        return result;
    }

    private static String normalizeVehicleMode(String rawMode, boolean transitVehicle) {
        String text = rawMode == null ? "" : rawMode.toLowerCase(Locale.ROOT);
        if (text.contains("subway") || text.contains("metro") || text.contains("rail") || text.contains("train")) {
            return "subway";
        }
        if (transitVehicle || text.contains("bus") || text.contains("pt")) {
            return "bus";
        }
        return "car";
    }

    private static int vehicleCapacity(MatsimData data, Id<Vehicle> vehicleId) {
        if (vehicleId == null) {
            return 0;
        }
        Vehicle vehicle = data.getTv().getVehicles().get(vehicleId);
        if (vehicle == null && data.getScenario() != null && data.getScenario().getVehicles() != null) {
            vehicle = data.getScenario().getVehicles().getVehicles().get(vehicleId);
        }
        VehicleType type = vehicle == null ? null : vehicle.getType();
        if (type == null || type.getCapacity() == null) {
            return 0;
        }
        double seats = type.getCapacity().getSeats() == null ? 0.0 : type.getCapacity().getSeats();
        double standingRoom = type.getCapacity().getStandingRoom() == null ? 0.0 : type.getCapacity().getStandingRoom();
        return Math.max(0, (int) Math.round(seats + standingRoom));
    }

    private static String firstText(Person person, String... keys) {
        if (person == null || person.getAttributes() == null) {
            return "";
        }
        for (String key : keys) {
            Object value = person.getAttributes().getAttribute(key);
            if (value != null) {
                return value.toString();
            }
        }
        return "";
    }

    private static String allAttributeText(Person person) {
        if (person == null || person.getAttributes() == null || person.getAttributes().isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        person.getAttributes().getAsMap().forEach((key, value) -> {
            builder.append(key).append('=');
            if (value != null) {
                builder.append(value);
            }
            builder.append(';');
        });
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private static Set<String> activityTypes(Person person) {
        Set<String> result = new LinkedHashSet<>();
        if (person == null || person.getSelectedPlan() == null) {
            return result;
        }
        for (PlanElement element : person.getSelectedPlan().getPlanElements()) {
            if (element instanceof Activity activity && activity.getType() != null) {
                result.add(activity.getType().toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    private static Integer age(Person person) {
        String raw = firstText(person, "age", "Age", "AGE", "年龄");
        if (raw.isBlank()) {
            return null;
        }
        try {
            return (int) Math.floor(Double.parseDouble(raw.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean hasToken(String text, String... tokens) {
        for (String token : tokens) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasActivity(Set<String> types, String... tokens) {
        for (String type : types) {
            for (String token : tokens) {
                if (type.contains(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final class RoutePanelAccumulator {
        private final String lineId;
        private final String lineName;
        private final String routeId;
        private final String routeName;
        private final String mode;
        private final String desc;
        private final double routeDistance;
        private final double directness;
        private final double firstTime;
        private final double lastTime;
        private final List<StopMeta> stops = new ArrayList<>();
        private final Map<String, StationFlowAccumulator> stationFlows = new LinkedHashMap<>();
        private final Map<String, TransferAccumulator> transfers = new HashMap<>();
        private final Set<String> riderIds = new LinkedHashSet<>();
        private final int[] hourlyBoardings = new int[HOURS];
        private final int[] hourlyAlightings = new int[HOURS];
        private final int[] capacityByHour = new int[HOURS];
        private final List<SegmentFlowAccumulator> segments = new ArrayList<>();
        private double capacityTotal = 0.0;
        private long totalBoardings = 0;
        private long totalAlightings = 0;
        private int departureCount = 0;
        private final Set<String> vehicleIds = new LinkedHashSet<>();
        private Map<String, Object> demographics = Map.of("commuter", 0, "student", 0, "elderly", 0);

        private RoutePanelAccumulator(
                MatsimData data,
                Network network,
                String lineId,
                String lineName,
                String routeId,
                TransitRoute route
        ) {
            this.lineId = lineId;
            this.lineName = lineName;
            this.routeId = routeId;
            this.routeName = nonBlank(route.getDescription(), routeId);
            this.mode = normalizeVehicleMode(route.getTransportMode(), true);
            this.routeDistance = routeDistance(route, network);
            this.directness = directness(route, routeDistance);
            this.firstTime = route.getDepartures().values().stream()
                    .mapToDouble(Departure::getDepartureTime)
                    .min()
                    .orElse(0.0);
            this.lastTime = route.getDepartures().values().stream()
                    .mapToDouble(Departure::getDepartureTime)
                    .max()
                    .orElse(0.0);
            int index = 0;
            for (TransitRouteStop stop : route.getStops()) {
                String facilityId = stop.getStopFacility().getId().toString();
                String facilityName = nonBlank(stop.getStopFacility().getName(), facilityId);
                stops.add(new StopMeta(index++, facilityId, facilityName));
                stationFlows.putIfAbsent(facilityId, new StationFlowAccumulator(facilityId, facilityName));
            }
            this.desc = routeDesc(stops);
            indexDepartures(data, route.getDepartures().values());
        }

        private void addTrack(PTPersonTrack track) {
            int hour = hourOf(safeTime(track));
            String facilityId = idString(track.getFacilityId());
            StationFlowAccumulator station = stationFlows.computeIfAbsent(
                    nonBlank(facilityId, "unknown"),
                    id -> new StationFlowAccumulator(id, id)
            );
            if (Boolean.TRUE.equals(track.getEnter())) {
                hourlyBoardings[hour]++;
                station.boardingByHour[hour]++;
                totalBoardings++;
                String personId = idString(track.getPersonId());
                if (personId != null) {
                    riderIds.add(personId);
                }
            } else {
                hourlyAlightings[hour]++;
                station.alightingByHour[hour]++;
                totalAlightings++;
            }
        }

        private synchronized void addTransfer(RoutePanelAccumulator otherRoute, String facilityId, int hour) {
            String stationName = stationName(facilityId);
            String key = otherRoute.lineId + "::" + stationName;
            TransferAccumulator transfer = transfers.computeIfAbsent(key,
                    ignored -> new TransferAccumulator(otherRoute.lineId, otherRoute.lineName, otherRoute.routeId, otherRoute.routeName, stationName));
            transfer.flowByHour[hour]++;
        }

        private void finish(Population population) {
            buildSegments();
            buildDemographics(population);
        }

        private void buildSegments() {
            segments.clear();
            for (int i = 0; i + 1 < stops.size(); i++) {
                StopMeta from = stops.get(i);
                StopMeta to = stops.get(i + 1);
                segments.add(new SegmentFlowAccumulator(from, to));
            }
            if (segments.isEmpty()) {
                return;
            }
            for (int hour = 0; hour < HOURS; hour++) {
                int onboard = 0;
                for (int i = 0; i + 1 < stops.size(); i++) {
                    StopMeta stop = stops.get(i);
                    StationFlowAccumulator station = stationFlows.get(stop.facilityId);
                    if (station != null) {
                        onboard += station.boardingByHour[hour] - station.alightingByHour[hour];
                    }
                    int flow = Math.max(0, onboard);
                    SegmentFlowAccumulator segment = segments.get(i);
                    segment.flowByHour[hour] = flow;
                    segment.loadRateByHour[hour] = percent(flow, capacityByHour[hour]);
                }
            }
            for (SegmentFlowAccumulator segment : segments) {
                segment.finish(capacityTotal);
            }
        }

        private void buildDemographics(Population population) {
            if (population == null || riderIds.isEmpty()) {
                demographics = demographicsPayload(0, 0, 0, 0, 0, 0, 0);
                return;
            }
            int total = 0;
            int commuter = 0;
            int student = 0;
            int elderly = 0;
            int shopping = 0;
            int leisure = 0;
            int other = 0;
            for (String riderId : riderIds) {
                Person person = population.getPersons().get(Id.create(riderId, Person.class));
                if (person == null) {
                    continue;
                }
                total++;
                Set<String> activities = activityTypes(person);
                String attributes = allAttributeText(person);
                Integer personAge = age(person);
                boolean isCommuter = hasActivity(activities, "home") && hasActivity(activities, "work")
                        || hasToken(attributes, "worker", "employee", "employed", "commuter", "通勤", "工作");
                boolean isStudent = hasActivity(activities, "school", "educ", "university", "college", "小学", "中学", "学校", "教育")
                        || hasToken(attributes, "student", "school", "university", "学生");
                boolean isElderly = personAge != null && personAge >= 60
                        || hasToken(attributes, "elderly", "retired", "senior", "老人", "退休");
                boolean isShopping = hasActivity(activities, "shop", "mall", "market", "购物", "买")
                        || hasToken(attributes, "shopping", "购物");
                boolean isLeisure = hasActivity(activities, "leisure", "recreation", "social", "sport", "entertain", "eat", "dining", "休闲", "娱乐", "餐", "运动", "社交")
                        || hasToken(attributes, "leisure", "休闲", "娱乐");
                // 客流画像分两个维度，各维度内互斥单选，保证每个维度的占比合计 ≤ 100%（前端用“其他”补足到 100%）。
                // —— 维度一·出行者属性：老人 > 学生 > 成年(其他)。老人/学生互斥，剩余成年人由前端归入“其他”。
                if (isElderly) {
                    elderly++;
                } else if (isStudent) {
                    student++;
                }
                // —— 维度二·出行目的（取主要目的）：通勤 > 购物 > 休闲 > 其他。四类互斥，合计恰为 100%。
                if (isCommuter) {
                    commuter++;
                } else if (isShopping) {
                    shopping++;
                } else if (isLeisure) {
                    leisure++;
                } else {
                    other++;
                }
            }
            demographics = demographicsPayload(total, commuter, student, elderly, shopping, leisure, other);
        }

        private Map<String, Object> demographicsPayload(int total, int commuter, int student, int elderly,
                int shopping, int leisure, int other) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("riderCount", total);
            payload.put("commuter", percent(commuter, total));
            payload.put("student", percent(student, total));
            payload.put("elderly", percent(elderly, total));
            payload.put("shopping", percent(shopping, total));
            payload.put("leisure", percent(leisure, total));
            payload.put("other", percent(other, total));
            payload.put("source", "population-attributes-and-activities");
            return payload;
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("lineId", lineId);
            payload.put("lineName", lineName);
            payload.put("routeId", routeId);
            payload.put("routeName", routeName);
            payload.put("mode", mode);
            payload.put("desc", desc);
            payload.put("hours", Stream.iterate(0, i -> i + 1).limit(HOURS).toList());
            payload.put("hourlyFlow", hourlyBoardings);
            payload.put("boardingByHour", hourlyBoardings);
            payload.put("alightingByHour", hourlyAlightings);
            payload.put("capacityByHour", capacityByHour);
            payload.put("stationFlows", stationFlows.values().stream().map(StationFlowAccumulator::toPayload).toList());
            payload.put("segments", segments.stream().map(SegmentFlowAccumulator::toPayload).toList());
            payload.put("transfers", transferPayloads());
            payload.put("demographics", demographics);

            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("routeDist", round2(routeDistance));
            metrics.put("firstTime", firstTime);
            metrics.put("lastTime", lastTime);
            metrics.put("facNum", stops.size());
            metrics.put("facDist", stops.size() > 1 ? round2(routeDistance / (stops.size() - 1)) : 0.0);
            metrics.put("lc", round2(directness));
            metrics.put("passenger", totalBoardings);
            metrics.put("loadRate", percent(totalBoardings, capacityTotal));
            metrics.put("passengerStrength", routeDistance <= 0 ? 0.0 : round2(totalBoardings / (routeDistance / 1000.0)));
            int vehicles = vehicleIds.size();
            metrics.put("departures", departureCount);
            metrics.put("vehicles", vehicles);
            metrics.put("perTripFlow", departureCount > 0 ? round2(totalBoardings / (double) departureCount) : 0.0);
            metrics.put("perVehicleFlow", vehicles > 0 ? round2(totalBoardings / (double) vehicles) : 0.0);
            payload.put("metrics", metrics);
            return payload;
        }

        private List<Map<String, Object>> transferPayloads() {
            int totalTransfer = transfers.values().stream().mapToInt(TransferAccumulator::flow).sum();
            return transfers.values().stream()
                    .sorted(Comparator.comparingInt(TransferAccumulator::flow).reversed())
                    .limit(TRANSFER_LIMIT)
                    .map(item -> item.toPayload(totalTransfer))
                    .toList();
        }

        private void indexDepartures(MatsimData data, Collection<Departure> departures) {
            for (Departure departure : departures) {
                departureCount++;
                if (departure.getVehicleId() != null) {
                    vehicleIds.add(departure.getVehicleId().toString());
                }
                int capacity = vehicleCapacity(data, departure.getVehicleId());
                capacityTotal += capacity;
                capacityByHour[hourOf(departure.getDepartureTime())] += capacity;
            }
        }

        private String stationName(String facilityId) {
            if (facilityId == null || facilityId.isBlank()) {
                return "--";
            }
            StationFlowAccumulator station = stationFlows.get(facilityId);
            return station == null ? facilityId : station.facilityName;
        }

        private static double routeDistance(TransitRoute route, Network network) {
            try {
                return DistanceUtil.distance(route.getRoute(), network);
            } catch (Exception e) {
                return 0.0;
            }
        }

        private static double directness(TransitRoute route, double routeDistance) {
            if (route.getStops().size() < 2) {
                return 0.0;
            }
            Coord first = route.getStops().getFirst().getStopFacility().getCoord();
            Coord last = route.getStops().getLast().getStopFacility().getCoord();
            if (first == null || last == null) {
                return 0.0;
            }
            double dx = first.getX() - last.getX();
            double dy = first.getY() - last.getY();
            double straight = Math.sqrt(dx * dx + dy * dy);
            return straight <= 0 ? 0.0 : routeDistance / straight;
        }

        private static String routeDesc(List<StopMeta> stops) {
            if (stops.size() < 2) {
                return "";
            }
            return stops.getFirst().facilityName + " - " + stops.getLast().facilityName;
        }
    }

    private record StopMeta(int index, String facilityId, String facilityName) {
    }

    private static final class StationFlowAccumulator {
        private final String facilityId;
        private final String facilityName;
        private final int[] boardingByHour = new int[HOURS];
        private final int[] alightingByHour = new int[HOURS];

        private StationFlowAccumulator(String facilityId, String facilityName) {
            this.facilityId = facilityId;
            this.facilityName = facilityName;
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("facilityId", facilityId);
            payload.put("facilityName", facilityName);
            payload.put("boardingByHour", boardingByHour);
            payload.put("alightingByHour", alightingByHour);
            payload.put("totalBoarding", intSum(boardingByHour));
            payload.put("totalAlighting", intSum(alightingByHour));
            return payload;
        }
    }

    private static final class SegmentFlowAccumulator {
        private final String name;
        private final String fromFacilityId;
        private final String toFacilityId;
        private final int[] flowByHour = new int[HOURS];
        private final double[] loadRateByHour = new double[HOURS];
        private int totalFlow = 0;
        private double loadRate = 0.0;

        private SegmentFlowAccumulator(StopMeta from, StopMeta to) {
            this.fromFacilityId = from.facilityId();
            this.toFacilityId = to.facilityId();
            this.name = from.facilityName() + " - " + to.facilityName();
        }

        private void finish(double capacityTotal) {
            totalFlow = intSum(flowByHour);
            loadRate = percent(totalFlow, capacityTotal);
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("name", name);
            payload.put("fromFacilityId", fromFacilityId);
            payload.put("toFacilityId", toFacilityId);
            payload.put("flowByHour", flowByHour);
            payload.put("loadRateByHour", loadRateByHour);
            payload.put("totalFlow", totalFlow);
            payload.put("loadRate", loadRate);
            return payload;
        }
    }

    private static final class TransferAccumulator {
        private final String lineId;
        private final String lineName;
        private final String routeId;
        private final String routeName;
        private final String station;
        private final int[] flowByHour = new int[HOURS];

        private TransferAccumulator(String lineId, String lineName, String routeId, String routeName, String station) {
            this.lineId = lineId;
            this.lineName = lineName;
            this.routeId = routeId;
            this.routeName = routeName;
            this.station = station;
        }

        private int flow() {
            return intSum(flowByHour);
        }

        private Map<String, Object> toPayload(int totalTransfer) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("lineId", lineId);
            payload.put("lineName", lineName);
            payload.put("routeId", routeId);
            payload.put("routeName", routeName);
            payload.put("station", station);
            payload.put("flowByHour", flowByHour);
            payload.put("flow", flow());
            payload.put("ratio", totalTransfer == 0 ? 0.0 : round2(flow() * 100.0 / totalTransfer));
            return payload;
        }
    }

    private static final class LineLeaderboardAccumulator {
        private final String lineId;
        private final String lineName;
        private String mode;
        private String desc;
        private long passengerFlow = 0;

        private LineLeaderboardAccumulator(String lineId, String lineName, String mode, String desc) {
            this.lineId = lineId;
            this.lineName = lineName;
            this.mode = mode;
            this.desc = desc;
        }

        private void add(RoutePanelAccumulator route) {
            passengerFlow += route.totalBoardings;
            if (desc == null || desc.isBlank()) {
                desc = route.desc;
            }
            if ("subway".equals(route.mode)) {
                mode = "subway";
            }
        }

        private long passengerFlow() {
            return passengerFlow;
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("lineId", lineId);
            payload.put("lineName", lineName);
            payload.put("desc", desc);
            payload.put("mode", mode);
            payload.put("passengerFlow", passengerFlow);
            return payload;
        }
    }
}
