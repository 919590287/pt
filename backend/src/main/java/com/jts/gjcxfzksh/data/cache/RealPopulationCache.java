package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.exception.BusinessException;
import com.jts.gjcxfzksh.optimization.util.GeoUtil;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 真实数据模式的人口分布适配缓存。
 *
 * <p>读取 {@code 真实数据/职住人口/广州市百米网格职住人口_WGS84.csv}，一次构建人口汇总、
 * 街道聚合和前端共用的 PGRD 二进制栅格。源文件按 size + mtime 自动失效，避免每次切换
 * 指标都重新扫描外置数据盘。</p>
 */
public final class RealPopulationCache {

    public static final String POPULATION_FOLDER = "职住人口";
    public static final String POPULATION_FILE = "广州市百米网格职住人口_WGS84.csv";
    private static final String EXPECTED_HEADER =
            "百米网格坐标（WGS-84）,通勤居住人口数量,通勤就业人口数量,常住人口数量";
    private static final String CACHE_VERSION = "real-population-v1";
    private static final int BIN_VERSION = 3;
    private static final int BIN_HEADER_BYTES = 18;
    private static final int BIN_RECORD_BYTES = 22;
    private static final int CELL_SIZE_METERS = 100;

    private static final BackendMemoryCache<Path, CachedArtifacts> CACHE =
            new BackendMemoryCache<>("real-population", 128L * 1024 * 1024,
                    cached -> BackendMemoryCache.estimate(cached.artifacts.summary())
                            + BackendMemoryCache.estimate(cached.artifacts.streets())
                            + cached.artifacts.gridBytes().length);
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

    private RealPopulationCache() {
    }

    public static Path sourcePath(Path realDataRoot) {
        return realDataRoot.resolve(POPULATION_FOLDER).resolve(POPULATION_FILE)
                .toAbsolutePath().normalize();
    }

    public static boolean isAvailable(Path source) {
        return source != null && Files.isRegularFile(source);
    }

    public static Map<String, Object> summary(Path source) {
        return isAvailable(source) ? artifacts(source).summary() : unsupported(source);
    }

    public static Map<String, Object> streets(Path source) {
        return isAvailable(source) ? artifacts(source).streets() : unsupported(source);
    }

    public static byte[] gridBytes(Path source) {
        return isAvailable(source) ? artifacts(source).gridBytes() : null;
    }

    public static String gridTag(Path source) {
        return isAvailable(source) ? artifacts(source).tag() : null;
    }

    /**
     * 体检评估使用的真实常住人口、行政区面积与公交站 300m 覆盖人口。
     * 人口分子严格取职住文件“常住人口数量”，不使用通勤居住人口或刷卡人数替代。
     */
    public static EvaluationPopulationStats evaluationStats(
            Path source, String district, List<double[]> stopLngLat) {
        if (!isAvailable(source)) return null;
        StationPopulationCoverage coverage = stationPopulationCoverage(source, stopLngLat);
        String scope = district == null || district.isBlank() ? "全市" : district.trim();
        ScopePopulationCoverage scopedCoverage = "全市".equals(scope)
                ? coverage.city()
                : coverage.districts().get(scope);
        if (scopedCoverage == null) return new EvaluationPopulationStats(0, 0, 0);
        MatsimPopulationCache.StreetIndex streets = MatsimPopulationCache.streetIndex();
        boolean all = "全市".equals(scope);
        double areaKm2 = 0;
        for (int index = 0; index < streets.size(); index++) {
            MatsimPopulationCache.StreetRef street = streets.street(index);
            if (all || scope.equals(street.district())) areaKm2 += street.areaKm2();
        }

        return new EvaluationPopulationStats(
                scopedCoverage.residentPersons(), areaKm2, scopedCoverage.coveredResidents300m());
    }

    /**
     * 数据管理总览的站点人口覆盖率。分子和分母都使用与运行监测一致的
     * 真实人口百米网格“常住人口数量”，一次扫描同时生成全市和各行政区的 300m/500m 结果。
     */
    public static StationPopulationCoverage stationPopulationCoverage(
            Path source, List<double[]> stopLngLat) {
        if (!isAvailable(source)) return null;
        Artifacts artifacts = artifacts(source);
        MatsimPopulationCache.StreetIndex streets = MatsimPopulationCache.streetIndex();
        Map<Long, List<double[]>> stops300 = coverageCells(stopLngLat, 300.0);
        Map<Long, List<double[]>> stops500 = coverageCells(stopLngLat, 500.0);
        MutableCoverage city = new MutableCoverage();
        Map<String, MutableCoverage> districts = new LinkedHashMap<>();

        ByteBuffer buffer = ByteBuffer.wrap(artifacts.gridBytes()).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(4);
        buffer.getShort();
        int count = buffer.getInt();
        double mercCellSize = buffer.getDouble();
        for (int record = 0; record < count; record++) {
            int i = buffer.getInt();
            int j = buffer.getInt();
            buffer.getInt();
            buffer.getInt();
            long resident = Integer.toUnsignedLong(buffer.getInt());
            int streetIndex = Short.toUnsignedInt(buffer.getShort());
            if (resident <= 0) continue;

            double x = (i + 0.5) * mercCellSize;
            double y = (j + 0.5) * mercCellSize;
            boolean covered300 = withinCoverage(x, y, 300.0, stops300);
            boolean covered500 = withinCoverage(x, y, 500.0, stops500);
            city.add(resident, covered300, covered500);

            if (streetIndex != MatsimPopulationCache.STREET_SENTINEL && streetIndex < streets.size()) {
                String district = streets.street(streetIndex).district();
                districts.computeIfAbsent(district, ignored -> new MutableCoverage())
                        .add(resident, covered300, covered500);
            }
        }

        Map<String, ScopePopulationCoverage> districtResults = new LinkedHashMap<>();
        districts.forEach((name, value) -> districtResults.put(name, value.freeze()));
        return new StationPopulationCoverage(city.freeze(), districtResults);
    }

    private static Map<Long, List<double[]>> coverageCells(List<double[]> stopLngLat, double radius) {
        Map<Long, List<double[]>> result = new HashMap<>();
        for (double[] point : stopLngLat == null ? List.<double[]>of() : stopLngLat) {
            if (point == null || point.length < 2
                    || !Double.isFinite(point[0]) || !Double.isFinite(point[1])) continue;
            double[] mercator = GeoUtil.lngLatToMercator(point[0], point[1]);
            int i = (int) Math.floor(mercator[0] / radius);
            int j = (int) Math.floor(mercator[1] / radius);
            result.computeIfAbsent(spatialKey(i, j), ignored -> new ArrayList<>()).add(mercator);
        }
        return result;
    }

    private static boolean withinCoverage(double x, double y, double radius,
                                          Map<Long, List<double[]>> stopsByCell) {
        int centerI = (int) Math.floor(x / radius);
        int centerJ = (int) Math.floor(y / radius);
        double radiusSquared = radius * radius;
        for (int di = -1; di <= 1; di++) {
            for (int dj = -1; dj <= 1; dj++) {
                for (double[] stop : stopsByCell.getOrDefault(
                        spatialKey(centerI + di, centerJ + dj), List.of())) {
                    double dx = x - stop[0];
                    double dy = y - stop[1];
                    if (dx * dx + dy * dy <= radiusSquared) return true;
                }
            }
        }
        return false;
    }

    private static long spatialKey(int i, int j) {
        return ((long) i << 32) ^ (j & 0xffffffffL);
    }

    private static Map<String, Object> unsupported(Path source) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "unsupported");
        result.put("source", "real");
        result.put("message", "未找到真实人口数据文件：" + source);
        return result;
    }

    private static Artifacts artifacts(Path rawSource) {
        Path source = rawSource.toAbsolutePath().normalize();
        Fingerprint fingerprint = fingerprint(source);
        CachedArtifacts cached = CACHE.get(source);
        if (cached != null && cached.fingerprint().equals(fingerprint)) {
            return cached.artifacts();
        }
        synchronized (LOCKS.computeIfAbsent(source, ignored -> new Object())) {
            cached = CACHE.get(source);
            if (cached != null && cached.fingerprint().equals(fingerprint)) {
                return cached.artifacts();
            }
            Artifacts built = build(source, fingerprint);
            CACHE.put(source, new CachedArtifacts(fingerprint, built));
            return built;
        }
    }

    private static Fingerprint fingerprint(Path source) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class);
            return new Fingerprint(attributes.size(), attributes.lastModifiedTime().toMillis());
        } catch (IOException e) {
            throw new BusinessException("读取真实人口数据文件属性失败：" + source, e);
        }
    }

    private static Artifacts build(Path source, Fingerprint fingerprint) {
        ScanBounds bounds = scanBounds(source);
        double centerLat = (bounds.minLat() + bounds.maxLat()) / 2.0;
        double mercCellSize = CELL_SIZE_METERS
                / Math.max(0.2, Math.cos(Math.toRadians(centerLat)));
        MatsimPopulationCache.StreetIndex streetIndex = MatsimPopulationCache.streetIndex();
        Long2IntOpenHashMap homeCells = new Long2IntOpenHashMap();
        Long2IntOpenHashMap workCells = new Long2IntOpenHashMap();
        Long2IntOpenHashMap residentCells = new Long2IntOpenHashMap();
        long[] streetHome = new long[streetIndex.size()];
        long[] streetWork = new long[streetIndex.size()];
        long[] streetResident = new long[streetIndex.size()];
        Totals totals = new Totals();

        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            requireHeader(reader.readLine(), source);
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                CsvRow row = parseRow(line, lineNumber, source);
                double[] mercator = GeoUtil.lngLatToMercator(row.lon(), row.lat());
                long cellKey = MatsimPopulationCache.cellKey(
                        mercator[0], mercator[1], mercCellSize);
                addCount(homeCells, cellKey, row.home(), lineNumber, "通勤居住人口");
                addCount(workCells, cellKey, row.work(), lineNumber, "通勤就业人口");
                addCount(residentCells, cellKey, row.resident(), lineNumber, "常住人口");
                totals.rows++;
                totals.home += row.home();
                totals.work += row.work();
                totals.resident += row.resident();

                int street = streetIndex.locate(mercator[0], mercator[1]);
                if (street >= 0) {
                    streetHome[street] += row.home();
                    streetWork[street] += row.work();
                    streetResident[street] += row.resident();
                } else {
                    totals.unassignedHome += row.home();
                    totals.unassignedWork += row.work();
                    totals.unassignedResident += row.resident();
                }
            }
        } catch (IOException e) {
            throw new BusinessException("读取真实人口数据失败：" + source, e);
        }

        byte[] grid = encodeGrid(homeCells, workCells, residentCells, mercCellSize, streetIndex);
        int gridCells = (grid.length - BIN_HEADER_BYTES) / BIN_RECORD_BYTES;
        String tag = CACHE_VERSION + "-" + Long.toHexString(fingerprint.size())
                + "-" + Long.toHexString(fingerprint.modifiedAt());
        return new Artifacts(
                buildSummary(source, fingerprint, mercCellSize, gridCells, totals),
                buildStreets(streetIndex, streetHome, streetWork, streetResident),
                grid,
                tag
        );
    }

    private static ScanBounds scanBounds(Path source) {
        double minLat = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        int rows = 0;
        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            requireHeader(reader.readLine(), source);
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                CsvRow row = parseRow(line, lineNumber, source);
                minLat = Math.min(minLat, row.lat());
                maxLat = Math.max(maxLat, row.lat());
                rows++;
            }
        } catch (IOException e) {
            throw new BusinessException("预扫描真实人口数据失败：" + source, e);
        }
        if (rows == 0 || !Double.isFinite(minLat) || !Double.isFinite(maxLat)) {
            throw new BusinessException("真实人口数据为空：" + source);
        }
        return new ScanBounds(minLat, maxLat);
    }

    private static void requireHeader(String header, Path source) {
        String normalized = header == null ? "" : header.replace("\uFEFF", "").trim();
        if (!EXPECTED_HEADER.equals(normalized)) {
            throw new BusinessException("真实人口数据表头不符合四列契约：" + source);
        }
    }

    private static CsvRow parseRow(String line, int lineNumber, Path source) {
        String[] fields = line.split(",", -1);
        if (fields.length != 4) {
            throw new BusinessException("真实人口数据第 " + lineNumber + " 行不是四列：" + source);
        }
        String[] coordinate = fields[0].split(";", -1);
        if (coordinate.length != 2) {
            throw new BusinessException("真实人口数据第 " + lineNumber + " 行坐标格式错误：" + fields[0]);
        }
        try {
            double lon = Double.parseDouble(coordinate[0]);
            double lat = Double.parseDouble(coordinate[1]);
            if (!Double.isFinite(lon) || !Double.isFinite(lat)) {
                throw new NumberFormatException("non-finite coordinate");
            }
            return new CsvRow(
                    lon,
                    lat,
                    population(fields[1]),
                    population(fields[2]),
                    population(fields[3])
            );
        } catch (NumberFormatException e) {
            throw new BusinessException("真实人口数据第 " + lineNumber + " 行包含非法数值", e);
        }
    }

    private static int population(String value) {
        long parsed = Long.parseLong(value.trim());
        if (parsed < 0 || parsed > Integer.MAX_VALUE) {
            throw new NumberFormatException("population out of range");
        }
        return (int) parsed;
    }

    private static void addCount(Long2IntOpenHashMap target, long key, int value,
                                 int lineNumber, String field) {
        if (value == 0) return;
        long next = Integer.toUnsignedLong(target.get(key)) + value;
        if (next > 0xffffffffL) {
            throw new BusinessException("真实人口数据第 " + lineNumber + " 行" + field + "栅格累计溢出");
        }
        target.put(key, (int) next);
    }

    private static byte[] encodeGrid(Long2IntOpenHashMap homeCells,
                                     Long2IntOpenHashMap workCells,
                                     Long2IntOpenHashMap residentCells,
                                     double mercCellSize,
                                     MatsimPopulationCache.StreetIndex streets) {
        LongOpenHashSet keySet = new LongOpenHashSet(homeCells.keySet());
        keySet.addAll(workCells.keySet());
        keySet.addAll(residentCells.keySet());
        long[] keys = keySet.toLongArray();
        Arrays.sort(keys);
        ByteBuffer buffer = ByteBuffer.allocate(BIN_HEADER_BYTES + BIN_RECORD_BYTES * keys.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'P', 'G', 'R', 'D'});
        buffer.putShort((short) BIN_VERSION);
        buffer.putInt(keys.length);
        buffer.putDouble(mercCellSize);
        for (long key : keys) {
            int i = MatsimPopulationCache.cellI(key);
            int j = MatsimPopulationCache.cellJ(key);
            buffer.putInt(i);
            buffer.putInt(j);
            buffer.putInt(homeCells.get(key));
            buffer.putInt(workCells.get(key));
            buffer.putInt(residentCells.get(key));
            int street = streets.locate(
                    (i + 0.5) * mercCellSize,
                    (j + 0.5) * mercCellSize
            );
            buffer.putShort((short) (street >= 0 ? street : MatsimPopulationCache.STREET_SENTINEL));
        }
        return buffer.array();
    }

    private static Map<String, Object> buildSummary(Path source,
                                                    Fingerprint fingerprint,
                                                    double mercCellSize,
                                                    int gridCells,
                                                    Totals totals) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ready");
        result.put("source", "real");
        result.put("cacheVersion", CACHE_VERSION);
        result.put("generatedAt", fingerprint.modifiedAt());
        result.put("quantityPolicy", "real-csv-original");
        result.put("cellSizeMeters", CELL_SIZE_METERS);
        result.put("mercCellSize", mercCellSize);
        result.put("gridCells", gridCells);
        result.put("sourceRows", totals.rows);
        result.put("homePersons", totals.home);
        result.put("workPersons", totals.work);
        result.put("residentPersons", totals.resident);
        result.put("unassignedHome", totals.unassignedHome);
        result.put("unassignedWork", totals.unassignedWork);
        result.put("unassignedResident", totals.unassignedResident);
        result.put("sourceFile", source.toString());
        return result;
    }

    private static Map<String, Object> buildStreets(MatsimPopulationCache.StreetIndex index,
                                                    long[] home,
                                                    long[] work,
                                                    long[] resident) {
        List<Map<String, Object>> rows = new ArrayList<>(index.size());
        long homeTotal = 0;
        long workTotal = 0;
        long residentTotal = 0;
        for (int i = 0; i < index.size(); i++) {
            MatsimPopulationCache.StreetRef street = index.street(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", street.code());
            row.put("name", street.name());
            row.put("district", street.district());
            row.put("areaKm2", street.areaKm2());
            row.put("home", home[i]);
            row.put("work", work[i]);
            row.put("resident", resident[i]);
            rows.add(row);
            homeTotal += home[i];
            workTotal += work[i];
            residentTotal += resident[i];
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ready");
        result.put("source", "real");
        result.put("spatialUnit", "street");
        result.put("streets", rows);
        result.put("totals", Map.of(
                "home", homeTotal,
                "work", workTotal,
                "resident", residentTotal
        ));
        return result;
    }

    public record Artifacts(Map<String, Object> summary,
                            Map<String, Object> streets,
                            byte[] gridBytes,
                            String tag) {
    }

    public record EvaluationPopulationStats(long residentPersons,
                                            double areaKm2,
                                            long coveredResidentPersons) {
        public Double density() {
            return areaKm2 > 0 ? residentPersons / areaKm2 : null;
        }

        public Double coveragePercent() {
            return residentPersons > 0
                    ? coveredResidentPersons * 100.0 / residentPersons : null;
        }
    }

    public record StationPopulationCoverage(ScopePopulationCoverage city,
                                            Map<String, ScopePopulationCoverage> districts) {
    }

    public record ScopePopulationCoverage(long residentPersons,
                                          long coveredResidents300m,
                                          long coveredResidents500m) {
        public Double coverage300Percent() {
            return percent(coveredResidents300m);
        }

        public Double coverage500Percent() {
            return percent(coveredResidents500m);
        }

        private Double percent(long covered) {
            return residentPersons > 0 ? covered * 100.0 / residentPersons : null;
        }
    }

    private static final class MutableCoverage {
        private long residents;
        private long covered300;
        private long covered500;

        private void add(long resident, boolean isCovered300, boolean isCovered500) {
            residents += resident;
            if (isCovered300) covered300 += resident;
            if (isCovered500) covered500 += resident;
        }

        private ScopePopulationCoverage freeze() {
            return new ScopePopulationCoverage(residents, covered300, covered500);
        }
    }

    private record Fingerprint(long size, long modifiedAt) {
    }

    private record CachedArtifacts(Fingerprint fingerprint, Artifacts artifacts) {
    }

    private record ScanBounds(double minLat, double maxLat) {
    }

    private record CsvRow(double lon, double lat, int home, int work, int resident) {
    }

    private static final class Totals {
        private long rows;
        private long home;
        private long work;
        private long resident;
        private long unassignedHome;
        private long unassignedWork;
        private long unassignedResident;
    }
}
