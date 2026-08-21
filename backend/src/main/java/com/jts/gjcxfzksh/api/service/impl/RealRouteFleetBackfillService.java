package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.config.MatsimConfig;
import jakarta.annotation.Resource;
import lombok.extern.log4j.Log4j2;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.data.SimpleFeatureStore;
import org.geotools.api.data.Transaction;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.filter.FilterFactory;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.factory.CommonFactoryFinder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Backfills load_num using the same scheduling defaults as the vehicle-calculation page. */
@Service
@Log4j2
public class RealRouteFleetBackfillService {
    private static final String ROUTE_SHP = "公交线路站点/线路/routes.shp";
    private static final String DEPARTURES_CSV = "公交线路站点/线路/routes_departures.csv";
    static final double TURN_MINUTES = 25;
    static final double LATENESS_MINUTES = 3;
    static final double DEFAULT_DIRECTION_KM = 20;
    static final double DEFAULT_RANGE_KM = 400;

    @Resource
    private MatsimConfig matsimConfig;

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void backfillOnStartup() {
        for (String area : matsimConfig.areaNames()) {
            try {
                int count = backfillArea(area);
                if (count > 0) log.info("真实线路自动配车回填完成 area={} features={}", area, count);
            } catch (RuntimeException error) {
                log.error("真实线路自动配车回填失败 area={} error={}", area, error.getMessage(), error);
            }
        }
    }

    int backfillArea(String area) {
        Path shp = matsimConfig.realDataPath(area).resolve(ROUTE_SHP);
        Path departures = matsimConfig.realDataPath(area).resolve(DEPARTURES_CSV);
        if (!Files.isRegularFile(shp) || !Files.isRegularFile(departures)) return 0;
        Map<String, List<Integer>> departureByRoute = readDepartures(departures);
        ShapefileDataStore dataStore = null;
        Transaction transaction = null;
        try {
            dataStore = new ShapefileDataStore(shp.toUri().toURL());
            dataStore.setMemoryMapped(false);
            dataStore.setBufferCachingEnabled(false);
            dataStore.setCharset(StandardCharsets.UTF_8);
            String typeName = dataStore.getTypeNames()[0];
            SimpleFeatureSource source = dataStore.getFeatureSource(typeName);
            if (source.getSchema().getDescriptor("load_num") == null) return 0;
            Map<String, List<RouteRecord>> groups = new LinkedHashMap<>();
            try (SimpleFeatureIterator iterator = source.getFeatures().features()) {
                while (iterator.hasNext()) {
                    SimpleFeature feature = iterator.next();
                    if (!"bus".equalsIgnoreCase(text(feature, "mode"))) continue;
                    String lineId = text(feature, "line_id");
                    RouteRecord record = new RouteRecord(
                            lineId,
                            fleetFamily(text(feature, "name")),
                            parseDuration(text(feature, "op_time")),
                            departureByRoute.getOrDefault(lineId, List.of()),
                            number(feature, "load_num")
                    );
                    groups.computeIfAbsent(record.family(), ignored -> new ArrayList<>()).add(record);
                }
            }
            Map<String, Integer> updates = calculateUpdates(groups);
            if (updates.isEmpty()) return 0;
            if (!(source instanceof SimpleFeatureStore store)) {
                throw new IllegalStateException("routes.shp 不支持写入 load_num");
            }
            transaction = new DefaultTransaction("backfill-real-route-fleet");
            store.setTransaction(transaction);
            FilterFactory filters = CommonFactoryFinder.getFilterFactory();
            for (Map.Entry<String, Integer> update : updates.entrySet()) {
                store.modifyFeatures("load_num", update.getValue(), filters.equals(
                        filters.property("line_id"), filters.literal(update.getKey())));
            }
            transaction.commit();
            return updates.size();
        } catch (Exception error) {
            if (transaction != null) {
                try { transaction.rollback(); } catch (IOException ignored) { }
            }
            throw new IllegalStateException("自动回填真实线路配车数失败: " + shp, error);
        } finally {
            if (transaction != null) {
                try { transaction.close(); } catch (IOException ignored) { }
            }
            if (dataStore != null) dataStore.dispose();
        }
    }

    static Map<String, Integer> calculateUpdates(Map<String, List<RouteRecord>> groups) {
        Map<String, Integer> result = new LinkedHashMap<>();
        groups.forEach((family, records) -> {
            List<RouteRecord> source = records.size() > 2 ? records.subList(0, 2) : records;
            if (source.isEmpty() || source.stream().anyMatch(route -> route.durationMinutes() == null || route.departures().isEmpty())) return;
            int fleet = source.size() == 1
                    ? singleDirectionFleet(source.getFirst().departures(), source.getFirst().durationMinutes())
                    : pairedFleet(source.get(0).departures(), source.get(1).departures(),
                            source.get(0).durationMinutes(), source.get(1).durationMinutes());
            if (fleet < 1) return;
            records.stream().filter(route -> route.currentFleet() <= 0)
                    .forEach(route -> result.put(route.lineId(), fleet));
        });
        return result;
    }

    static int pairedFleet(List<Integer> upTimes, List<Integer> downTimes, double upDuration, double downDuration) {
        List<Task> tasks = new ArrayList<>(upTimes.size() + downTimes.size());
        upTimes.forEach(time -> tasks.add(new Task("A", "B", time, upDuration)));
        downTimes.forEach(time -> tasks.add(new Task("B", "A", time, downDuration)));
        tasks.sort(Comparator.comparingInt(Task::start));
        List<Vehicle> vehicles = new ArrayList<>();
        for (Task task : tasks) {
            int selected = -1;
            FleetCandidate best = null;
            for (int index = 0; index < vehicles.size(); index++) {
                Vehicle vehicle = vehicles.get(index);
                if (vehicle.available() > task.start() + LATENESS_MINUTES
                        || !vehicle.station().equals(task.source())
                        || vehicle.mileage() + DEFAULT_DIRECTION_KM > DEFAULT_RANGE_KM) continue;
                FleetCandidate candidate = new FleetCandidate(index, vehicle.tasks() % 2 == 1 ? 0 : 1,
                        vehicle.tasks(), Math.max(0, task.start() - vehicle.available()));
                if (best == null || candidate.compareTo(best) < 0) {
                    best = candidate;
                    selected = index;
                }
            }
            if (selected < 0) {
                vehicles.add(new Vehicle(task.target(), task.start() + task.duration() + TURN_MINUTES,
                        DEFAULT_DIRECTION_KM, 1));
            } else {
                Vehicle vehicle = vehicles.get(selected);
                double departure = Math.max(vehicle.available(), task.start());
                vehicles.set(selected, new Vehicle(task.target(), departure + task.duration() + TURN_MINUTES,
                        vehicle.mileage() + DEFAULT_DIRECTION_KM, vehicle.tasks() + 1));
            }
        }
        return vehicles.size();
    }

    static int singleDirectionFleet(List<Integer> times, double duration) {
        List<Double> available = new ArrayList<>();
        for (int time : times) {
            int selected = -1;
            double earliest = Double.POSITIVE_INFINITY;
            for (int index = 0; index < available.size(); index++) {
                if (available.get(index) <= time + LATENESS_MINUTES && available.get(index) < earliest) {
                    selected = index;
                    earliest = available.get(index);
                }
            }
            double next = time + duration + TURN_MINUTES;
            if (selected < 0) available.add(next);
            else available.set(selected, next);
        }
        return available.size();
    }

    private static Map<String, List<Integer>> readDepartures(Path file) {
        Map<String, List<Integer>> result = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) return result;
            List<String> headers = RealPassengerFlowServiceImpl.parseCsv(headerLine);
            if (!headers.isEmpty()) headers.set(0, headers.getFirst().replace("\uFEFF", ""));
            int lineIdIndex = headers.indexOf("line_id");
            int departureIndex = headers.indexOf("departures");
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> values = RealPassengerFlowServiceImpl.parseCsv(line);
                if (lineIdIndex < 0 || departureIndex < 0 || values.size() <= Math.max(lineIdIndex, departureIndex)) continue;
                result.put(values.get(lineIdIndex).trim(), parseDepartures(values.get(departureIndex)));
            }
            return result;
        } catch (IOException error) {
            throw new IllegalStateException("读取线路发车时刻失败: " + file, error);
        }
    }

    static List<Integer> parseDepartures(String value) {
        return java.util.Arrays.stream(value.split(";"))
                .map(String::trim).map(RealRouteFleetBackfillService::parseClock)
                .filter(time -> time >= 0).distinct().sorted().toList();
    }

    static int parseClock(String value) {
        String[] parts = value.split(":");
        if (parts.length < 2) return -1;
        try {
            int hour = Integer.parseInt(parts[0]);
            int minute = Integer.parseInt(parts[1]);
            return hour >= 0 && hour <= 47 && minute >= 0 && minute <= 59 ? hour * 60 + minute : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    static Double parseDuration(String value) {
        String normalized = value.trim().replace('～', '-').replace('~', '-').replace('—', '-').replace("至", "-")
                .replace("分钟", "").replace("分", "");
        if (normalized.isBlank()) return null;
        try {
            double[] values = java.util.Arrays.stream(normalized.split("-"))
                    .map(String::trim).filter(part -> !part.isBlank()).mapToDouble(Double::parseDouble).toArray();
            if (values.length == 0 || java.util.Arrays.stream(values).anyMatch(number -> number <= 0)) return null;
            return java.util.Arrays.stream(values).average().orElseThrow();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static String fleetFamily(String name) {
        String family = RealPassengerFlowServiceImpl.baseLineName(name);
        if (family.endsWith("上行") || family.endsWith("下行")) family = family.substring(0, family.length() - 2);
        return family.trim().toLowerCase(Locale.ROOT);
    }

    private static String text(SimpleFeature feature, String field) {
        Object value = feature.getAttribute(field);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static double number(SimpleFeature feature, String field) {
        Object value = feature.getAttribute(field);
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    record RouteRecord(String lineId, String family, Double durationMinutes, List<Integer> departures, double currentFleet) { }
    private record Task(String source, String target, int start, double duration) { }
    private record Vehicle(String station, double available, double mileage, int tasks) { }
    private record FleetCandidate(int index, int parity, int tasks, double idleMinutes) implements Comparable<FleetCandidate> {
        @Override public int compareTo(FleetCandidate other) {
            int value = Integer.compare(parity, other.parity);
            if (value != 0) return value;
            value = Integer.compare(tasks, other.tasks);
            return value != 0 ? value : Double.compare(idleMinutes, other.idleMinutes);
        }
    }
}
