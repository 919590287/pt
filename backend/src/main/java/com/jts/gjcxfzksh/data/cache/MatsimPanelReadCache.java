package com.jts.gjcxfzksh.data.cache;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jts.gjcxfzksh.data.MatsimData;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 将历史的单体 panel JSON 转为“轻量全局索引 + hash 分片详情”。
 *
 * <p>转换使用 Jackson streaming，一次只对象化一条线路/一个站点；后续全局页面只读取
 * index，点击详情只顺序扫描 1/32 分片。原始 panel 与统计口径完全不变，派生文件可删除重建。</p>
 */
@Slf4j
final class MatsimPanelReadCache {

    private static final String CACHE_VERSION = "panel-read-v2";
    private static final String INDEX_FILE = "index.json.gz";
    private static final String MANIFEST_FILE = "manifest.json";
    private static final int SHARDS = 32;
    private static final int IO_BUFFER_BYTES = 2 * 1024 * 1024;
    private static final int SHARD_BUFFER_BYTES = 64 * 1024;
    private static final long MAX_DETAIL_MEMORY_BYTES = 2L * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    // departureRoutes 与线路索引在同一次流式扫描中分片，避免模型加载时重复解压巨型 panel。
    private static final Set<String> ROUTE_SECTIONS = Set.of("routes", "lineGroups", "departureRoutes");
    private static final Set<String> STATION_SECTIONS = Set.of("stations");

    private static final BackendMemoryCache<String, Map<String, Object>> INDEX_MEMORY =
            new BackendMemoryCache<>("panel-index", 64L * 1024 * 1024, BackendMemoryCache::estimate);
    private static final BackendMemoryCache<String, Map<String, Object>> DETAIL_MEMORY =
            new BackendMemoryCache<>("panel-detail", 192L * 1024 * 1024, BackendMemoryCache::estimate);

    private MatsimPanelReadCache() {
    }

    static Map<String, Object> readRouteIndex(MatsimData data, Path panelPath) {
        Map<String, Object> combined = readIndex(data, panelPath, "route", ROUTE_SECTIONS);
        if (!combined.containsKey("departureRoutes")) return combined;
        Map<String, Object> result = new LinkedHashMap<>(combined);
        result.remove("departureRoutes");
        return result;
    }

    static Map<String, Object> readDepartureIndex(MatsimData data, Path panelPath) {
        Map<String, Object> combined = readIndex(data, panelPath, "route", ROUTE_SECTIONS);
        Map<String, Object> result = new LinkedHashMap<>();
        copy(combined, result, "status", "cacheVersion", "generatedAt", "payloadKind", "detailShards");
        result.put("routes", combined.get("departureRoutes") instanceof Map<?, ?> routes ? routes : Map.of());
        return result;
    }

    static Map<String, Object> readStationIndex(MatsimData data, Path panelPath) {
        return readIndex(data, panelPath, "station", STATION_SECTIONS);
    }

    /**
     * 兼容极少数仍需整体 panel 的内部调用：从规范分片工件按需重组，
     * 不再为此在磁盘上同时保留一份重复的单体 JSON。
     */
    static Map<String, Object> readFull(
            MatsimData data,
            Path panelPath,
            String kind
    ) {
        Set<String> sections = "route".equals(kind) ? ROUTE_SECTIONS : STATION_SECTIONS;
        try {
            Path dir = ensureBuilt(data, panelPath, kind, sections);
            Map<String, Object> result = new LinkedHashMap<>(readIndex(data, panelPath, kind, sections));
            for (String section : sections) {
                Map<String, Object> details = new LinkedHashMap<>();
                for (int shard = 0; shard < SHARDS; shard++) {
                    Path file = shardPath(dir, section, shard);
                    if (!Files.isRegularFile(file)) continue;
                    try (InputStream in = gzipInput(file)) {
                        details.putAll(JSON.readValue(in, MAP_TYPE));
                    }
                }
                result.put(section, details);
            }
            result.remove("payloadKind");
            result.remove("detailShards");
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("规范面板分片重组失败: model=" + data.getName()
                    + ", kind=" + kind, e);
        }
    }

    static boolean canonicalReady(Path panelPath, String kind) {
        return ready(derivedDir(panelPath, kind), panelPath, kind);
    }

    static void promoteCanonical(MatsimData data, Path panelPath, String kind) {
        Set<String> sections = "route".equals(kind) ? ROUTE_SECTIONS : STATION_SECTIONS;
        try {
            Path dir = ensureBuilt(data, panelPath, kind, sections);
            // 分片目录已原子发布后，单体输入只是构建中间物，删除它保证同类数据只有一份规范工件。
            Files.deleteIfExists(panelPath);
            markSourceReleased(dir);
        } catch (Exception e) {
            throw new IllegalStateException("面板规范工件发布失败: model=" + data.getName()
                    + ", kind=" + kind, e);
        }
    }

    static Map<String, Object> readDetail(
            MatsimData data,
            Path panelPath,
            String kind,
            String section,
            String key
    ) {
        if (key == null || key.isBlank()) return Map.of();
        Set<String> sections = "route".equals(kind) ? ROUTE_SECTIONS : STATION_SECTIONS;
        if (!sections.contains(section)) return Map.of();
        try {
            Path dir = ensureBuilt(data, panelPath, kind, sections);
            int shard = shard(key);
            Path file = shardPath(dir, section, shard);
            if (!Files.isRegularFile(file)) return Map.of();
            String memoryKey = file.toAbsolutePath().normalize() + "#" + key;
            Map<String, Object> cached = DETAIL_MEMORY.get(memoryKey);
            if (cached != null) return cached;
            Map<String, Object> result = readOneDetail(file, key);
            if (!result.isEmpty() && estimatedJsonBytes(result) <= MAX_DETAIL_MEMORY_BYTES) {
                DETAIL_MEMORY.put(memoryKey, result);
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("面板分片详情读取失败: model=" + data.getName()
                    + ", kind=" + kind + ", section=" + section + ", key=" + key, e);
        }
    }

    private static Map<String, Object> readIndex(
            MatsimData data,
            Path panelPath,
            String kind,
            Set<String> sections
    ) {
        try {
            Path dir = ensureBuilt(data, panelPath, kind, sections);
            Path path = dir.resolve(INDEX_FILE);
            String cacheKey = path.toAbsolutePath().normalize().toString();
            Map<String, Object> cached = INDEX_MEMORY.get(cacheKey);
            if (cached != null) return cached;
            try (InputStream in = gzipInput(path)) {
                cached = JSON.readValue(in, MAP_TYPE);
            }
            INDEX_MEMORY.put(cacheKey, cached);
            return cached;
        } catch (Exception e) {
            throw new IllegalStateException("轻量面板索引读取失败: model=" + data.getName()
                    + ", kind=" + kind + ", path=" + panelPath, e);
        }
    }

    private static Path ensureBuilt(
            MatsimData data,
            Path panelPath,
            String kind,
            Set<String> sections
    ) throws Exception {
        Path dir = derivedDir(panelPath, kind);
        if (ready(dir, panelPath, kind)) return dir;
        synchronized (ModelBuildLocks.lockFor("panel-read-" + kind, data)) {
            if (ready(dir, panelPath, kind)) return dir;
            build(panelPath, dir, kind, sections);
        }
        return dir;
    }

    private static void build(Path panelPath, Path targetDir, String kind, Set<String> sections) throws Exception {
        long started = System.currentTimeMillis();
        Path tmpDir = targetDir.resolveSibling(targetDir.getFileName()
                + ".tmp-" + Thread.currentThread().threadId() + "-" + System.nanoTime());
        Files.createDirectories(tmpDir);
        Map<String, Object> index = new LinkedHashMap<>();
        Map<String, ShardWriters> writers = new LinkedHashMap<>();
        for (String section : sections) writers.put(section, new ShardWriters(tmpDir, section));

        long entries = 0;
        try (InputStream in = gzipInput(panelPath);
             JsonParser parser = JSON.getFactory().createParser(in)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IllegalStateException("panel 顶层不是 JSON object");
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                if (sections.contains(field) && valueToken == JsonToken.START_OBJECT) {
                    Map<String, Object> summaries = new LinkedHashMap<>();
                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        String key = parser.currentName();
                        parser.nextToken();
                        Map<String, Object> detail = parser.readValueAs(MAP_TYPE);
                        summaries.put(key, summary(kind, field, detail));
                        writers.get(field).write(key, detail);
                        entries++;
                    }
                    index.put(field, summaries);
                } else if ("status".equals(field) || "cacheVersion".equals(field)
                        || "generatedAt".equals(field) || "summary".equals(field)) {
                    index.put(field, parser.readValueAs(Object.class));
                } else {
                    parser.skipChildren();
                }
            }
        } catch (Exception e) {
            closeWriters(writers);
            deleteTree(tmpDir);
            throw e;
        }
        closeWriters(writers);
        index.put("payloadKind", "index");
        index.put("detailShards", SHARDS);
        writeGzipJson(tmpDir.resolve(INDEX_FILE), index);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("status", "ready");
        manifest.put("cacheVersion", CACHE_VERSION);
        manifest.put("kind", kind);
        manifest.put("sourceSize", Files.size(panelPath));
        manifest.put("sourceModified", Files.getLastModifiedTime(panelPath).toMillis());
        manifest.put("entries", entries);
        manifest.put("canonical", true);
        // build 后源仍存在，只有 promoteCanonical 成功删除源后才改为 false。
        // 这使开发/修复流程中替换单体源仍能触发重建，又能在正式发布后忽略外部坏文件。
        manifest.put("sourceRetained", true);
        manifest.put("generatedAt", System.currentTimeMillis());
        JSON.writeValue(tmpDir.resolve(MANIFEST_FILE).toFile(), manifest);

        // 固定版本路径上原位替换：源变化不再另建一个指纹目录。
        // manifest 仍保留源 size/mtime 用于失效判定，临时目录完整写好后才替换正式目录。
        invalidateMemory(targetDir);
        deleteTreeStrict(targetDir);
        try {
            Files.move(tmpDir, targetDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(tmpDir, targetDir);
        }
        invalidateMemory(targetDir);
        deleteLegacyFingerprintDirs(targetDir, kind);
        log.info("轻量面板索引生成完成: kind={}, entries={}, index={}KB, elapsed={}ms",
                kind, entries, Files.size(targetDir.resolve(INDEX_FILE)) / 1024,
                System.currentTimeMillis() - started);
    }

    private static Map<String, Object> summary(String kind, String section, Map<String, Object> detail) {
        Map<String, Object> result = new LinkedHashMap<>();
        if ("route".equals(kind)) {
            if ("departureRoutes".equals(section)) {
                // 全模型索引只保留 route 定位字段；每条线路的时刻表随详情分片读取，
                // 避免几十万 departure 元数据再次常驻 JVM。
                copy(detail, result, "lineId", "lineName", "routeId", "routeName", "stationCount");
                result.put("_summary", true);
                return result;
            }
            copy(detail, result, "lineId", "lineName", "routeId", "routeName", "routeKey",
                    "lineGroup", "mode", "desc", "hourlyFlow", "metrics", "routeIds", "routeKeys");
            Object segmentsValue = detail.get("segments");
            if (segmentsValue instanceof List<?> segments && !segments.isEmpty()) {
                List<Map<String, Object>> slimSegments = new ArrayList<>(segments.size());
                for (Object value : segments) {
                    if (!(value instanceof Map<?, ?> segment)) continue;
                    Map<String, Object> slim = new LinkedHashMap<>();
                    double total = number(segment.get("totalFlow"));
                    if (!(total > 0)) total = sumNumbers(segment.get("flowByHour"));
                    slim.put("totalFlow", total);
                    slimSegments.add(slim);
                }
                result.put("segments", slimSegments);
            }
        } else {
            copy(detail, result, "stationName", "name", "mode", "desc", "hourlyFlow", "facilityIds");
        }
        result.put("_summary", true);
        return result;
    }

    private static void copy(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) target.put(key, source.get(key));
        }
    }

    private static double sumNumbers(Object value) {
        if (!(value instanceof List<?> values)) return 0.0;
        double result = 0;
        for (Object item : values) result += number(item);
        return result;
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private static Map<String, Object> readOneDetail(Path path, String targetKey) throws Exception {
        try (InputStream in = gzipInput(path);
             JsonParser parser = JSON.getFactory().createParser(in)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) return Map.of();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String key = parser.currentName();
                parser.nextToken();
                if (targetKey.equals(key)) return parser.readValueAs(MAP_TYPE);
                parser.skipChildren();
            }
            return Map.of();
        }
    }

    private static boolean ready(Path dir, Path source, String kind) {
        Path manifestPath = dir.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifestPath) || !Files.isRegularFile(dir.resolve(INDEX_FILE))) return false;
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath.toFile(), MAP_TYPE);
            boolean common = "ready".equals(manifest.get("status"))
                    && CACHE_VERSION.equals(manifest.get("cacheVersion"))
                    && kind.equals(manifest.get("kind"));
            if (!common) return false;
            boolean canonical = Boolean.TRUE.equals(manifest.get("canonical"));
            boolean sourceRetained = Boolean.TRUE.equals(manifest.get("sourceRetained"));
            // 正式发布且已释放源后，同路径偶然出现的单体文件不再是权威来源。
            if (canonical && !sourceRetained) return true;
            // v2 早期版本在发布时只落了 sourceRetained=false，未同步写 canonical=true。
            // false 是发布流程删除单体源后才会写入的显式状态，因此可以安全视为规范分片；
            // 否则升级后的最终校验会把完整缓存误判为缺失并诱发无意义的全量重算。
            if (Boolean.FALSE.equals(manifest.get("sourceRetained"))) return true;
            // 删除源与更新 manifest 之间即使进程退出，已完整原子发布的分片仍可恢复使用。
            if (canonical && !Files.isRegularFile(source)) return true;
            return Files.isRegularFile(source)
                    && longNumber(manifest.get("sourceSize")) == Files.size(source)
                    && longNumber(manifest.get("sourceModified")) == Files.getLastModifiedTime(source).toMillis();
        } catch (Exception e) {
            throw new IllegalStateException("面板分片缓存状态读取失败: " + manifestPath, e);
        }
    }

    private static long longNumber(Object value) {
        return value instanceof Number number ? number.longValue() : -1L;
    }

    private static void markSourceReleased(Path dir) throws Exception {
        Path manifestPath = dir.resolve(MANIFEST_FILE);
        Map<String, Object> manifest = JSON.readValue(manifestPath.toFile(), MAP_TYPE);
        if (Boolean.TRUE.equals(manifest.get("canonical"))
                && Boolean.FALSE.equals(manifest.get("sourceRetained"))) return;
        manifest.put("canonical", true);
        manifest.put("sourceRetained", false);
        Path temporary = manifestPath.resolveSibling(MANIFEST_FILE + ".tmp-" + System.nanoTime());
        JSON.writeValue(temporary.toFile(), manifest);
        try {
            Files.move(temporary, manifestPath,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception noAtomic) {
            Files.move(temporary, manifestPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static long estimatedJsonBytes(Map<String, Object> value) {
        try {
            return JSON.writeValueAsBytes(value).length;
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    private static Path derivedDir(Path panelPath, String kind) {
        return panelPath.getParent().resolve(CACHE_VERSION + "-" + kind);
    }

    private static void invalidateMemory(Path targetDir) {
        String prefix = targetDir.toAbsolutePath().normalize().toString();
        INDEX_MEMORY.removeIf(key -> key.startsWith(prefix));
        DETAIL_MEMORY.removeIf(key -> key.startsWith(prefix));
    }

    /** 清理 v2 早期“每个源指纹一个目录”遗留，只限当前 panel 目录与已校验前缀。 */
    private static void deleteLegacyFingerprintDirs(Path targetDir, String kind) throws Exception {
        Path parent = targetDir.getParent();
        if (!Files.isDirectory(parent)) return;
        String legacyPrefix = CACHE_VERSION + "-" + kind + "-";
        try (var children = Files.list(parent)) {
            for (Path child : children
                    .filter(Files::isDirectory)
                    .filter(path -> !path.equals(targetDir))
                    .filter(path -> path.getFileName().toString().startsWith(legacyPrefix))
                    .toList()) {
                deleteTreeStrict(child);
            }
        }
    }

    private static Path shardPath(Path dir, String section, int shard) {
        return dir.resolve(String.format("%s-%02d.json.gz", section, shard));
    }

    private static int shard(String key) {
        return Math.floorMod(key.hashCode(), SHARDS);
    }

    private static InputStream gzipInput(Path path) throws Exception {
        InputStream raw = new BufferedInputStream(Files.newInputStream(path), IO_BUFFER_BYTES);
        return new GZIPInputStream(raw, IO_BUFFER_BYTES);
    }

    private static void writeGzipJson(Path path, Map<String, Object> value) throws Exception {
        try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(path), IO_BUFFER_BYTES);
             OutputStream out = new GZIPOutputStream(raw, IO_BUFFER_BYTES)) {
            JSON.writeValue(out, value);
        }
    }

    private static void closeWriters(Map<String, ShardWriters> writers) throws Exception {
        Exception failure = null;
        for (ShardWriters value : writers.values()) {
            try {
                value.close();
            } catch (Exception e) {
                if (failure == null) failure = e;
            }
        }
        if (failure != null) throw failure;
    }

    private static void deleteTree(Path path) {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static void deleteTreeStrict(Path path) throws Exception {
        if (!Files.exists(path)) return;
        List<Path> entries;
        try (var stream = Files.walk(path)) {
            entries = stream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path entry : entries) Files.deleteIfExists(entry);
    }

    private static final class ShardWriters {
        private final Path dir;
        private final String section;
        private final JsonGenerator[] generators = new JsonGenerator[SHARDS];

        private ShardWriters(Path dir, String section) {
            this.dir = dir;
            this.section = section;
        }

        private void write(String key, Map<String, Object> value) throws Exception {
            int shard = shard(key);
            JsonGenerator generator = generators[shard];
            if (generator == null) {
                OutputStream raw = new BufferedOutputStream(
                        Files.newOutputStream(shardPath(dir, section, shard)), SHARD_BUFFER_BYTES);
                OutputStream gzip = new GZIPOutputStream(raw, SHARD_BUFFER_BYTES);
                generator = JSON.getFactory().createGenerator(gzip);
                generator.writeStartObject();
                generators[shard] = generator;
            }
            generator.writeFieldName(key);
            generator.writeObject(value);
        }

        private void close() throws Exception {
            Exception failure = null;
            for (JsonGenerator generator : generators) {
                if (generator == null) continue;
                try {
                    generator.writeEndObject();
                    generator.close();
                } catch (Exception e) {
                    if (failure == null) failure = e;
                }
            }
            if (failure != null) throw failure;
        }
    }
}
