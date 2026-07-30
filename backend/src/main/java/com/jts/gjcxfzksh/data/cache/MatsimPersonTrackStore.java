package com.jts.gjcxfzksh.data.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.entry.PTPersonTrack;
import com.jts.gjcxfzksh.data.id.DepartureId;
import com.jts.gjcxfzksh.data.id.LineId;
import com.jts.gjcxfzksh.data.id.PersonId;
import com.jts.gjcxfzksh.data.id.RouteId;
import com.jts.gjcxfzksh.data.id.StopFacilityId;
import com.jts.gjcxfzksh.data.id.VehicleId;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 大模型乘客上下车磁盘访问层。
 *
 * <p>平坦统计直接顺序扫描 {@code person-tracks.tsv.gz}；需要按人配对的统计先按 personId
 * 哈希到固定数量的 gzip 分区，再逐分区分组。这样内存上限与单个分区相关，不再与 2000 万条
 * 全量记录相关。分区是可复用的版本化工件，route/station/transfer/corridor/tripends 共用一次构建。</p>
 */
@Slf4j
public final class MatsimPersonTrackStore {

    public static final String PARTITION_CACHE_VERSION = "person-track-partitions-v1";
    private static final String MANIFEST_FILE = "manifest.json";
    private static final String BUCKET_PREFIX = "bucket-";
    private static final int DEFAULT_PARTITIONS = 64;
    private static final int IO_BUFFER_BYTES = 1 << 20;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private MatsimPersonTrackStore() {
    }

    @FunctionalInterface
    public interface PersonConsumer {
        void accept(String personId, List<PTPersonTrack> tracks) throws Exception;
    }

    public static boolean hasTracks(MatsimData data) {
        return data != null && data.getPersonTracks() != null && !data.getPersonTracks().isEmpty()
                || data != null && MatsimAnalysisCache.isPersonTrackStoreReady(data);
    }

    public static void forEachTrack(MatsimData data, Consumer<PTPersonTrack> consumer) {
        if (data == null || consumer == null) return;
        Set<PTPersonTrack> inMemory = data.getPersonTracks();
        if (inMemory != null && !inMemory.isEmpty()) {
            inMemory.forEach(consumer);
            return;
        }
        requireDiskStore(data);
        readTrackFile(MatsimAnalysisCache.personTracksPath(data), consumer);
    }

    /**
     * 按人逐组回调。回调返回后该人的记录即可被回收；一个 person 的全部记录必在同一分区。
     */
    public static void forEachPerson(MatsimData data, PersonConsumer consumer) {
        forEachPerson(data, consumer, null);
    }

    public static void forEachPerson(MatsimData data, PersonConsumer consumer,
                                     BiConsumer<Integer, Integer> partitionProgress) {
        if (data == null || consumer == null) return;
        Set<PTPersonTrack> inMemory = data.getPersonTracks();
        if (inMemory != null && !inMemory.isEmpty()) {
            Map<String, List<PTPersonTrack>> grouped = new HashMap<>();
            for (PTPersonTrack track : inMemory) {
                grouped.computeIfAbsent(personKey(track), ignored -> new ArrayList<>()).add(track);
            }
            grouped.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                    invoke(consumer, entry.getKey(), entry.getValue()));
            if (partitionProgress != null) partitionProgress.accept(1, 1);
            return;
        }

        requireDiskStore(data);
        ensurePartitions(data);
        int partitions = partitionCount();
        for (int bucket = 0; bucket < partitions; bucket++) {
            Map<String, List<PTPersonTrack>> grouped = new HashMap<>();
            readTrackFile(bucketPath(partitionDir(data), bucket), track ->
                    grouped.computeIfAbsent(personKey(track), ignored -> new ArrayList<>()).add(track));
            grouped.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                    invoke(consumer, entry.getKey(), entry.getValue()));
            if (partitionProgress != null) partitionProgress.accept(bucket + 1, partitions);
        }
    }

    public static boolean isPartitionStoreReady(MatsimData data) {
        Path dir = partitionDir(data);
        Path manifestPath = dir.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifestPath)) return false;
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath.toFile(), MAP_TYPE);
            if (!"ready".equals(manifest.get("status"))
                    || !PARTITION_CACHE_VERSION.equals(manifest.get("cacheVersion"))
                    || number(manifest.get("partitions")) != partitionCount()
                    || !sourceSignature(data).equals(String.valueOf(manifest.get("sourceSignature")))) {
                return false;
            }
            for (int i = 0; i < partitionCount(); i++) {
                if (!Files.isRegularFile(bucketPath(dir, i))) return false;
            }
            return true;
        } catch (Exception e) {
            throw new IllegalStateException("乘客明细分区缓存状态读取失败: "
                    + manifestPath, e);
        }
    }

    public static void preparePartitions(MatsimData data) {
        requireDiskStore(data);
        ensurePartitions(data);
    }

    private static void ensurePartitions(MatsimData data) {
        synchronized (ModelBuildLocks.lockFor("person-track-partitions", data)) {
            if (isPartitionStoreReady(data)) return;
            buildPartitions(data);
        }
    }

    private static void buildPartitions(MatsimData data) {
        Path finalDir = partitionDir(data);
        Path parent = finalDir.getParent();
        Path buildDir = parent.resolve(finalDir.getFileName() + ".building-" + UUID.randomUUID());
        int partitions = partitionCount();
        BufferedWriter[] writers = new BufferedWriter[partitions];
        long count = 0;
        try {
            Files.createDirectories(buildDir);
            for (int i = 0; i < partitions; i++) {
                writers[i] = new BufferedWriter(new OutputStreamWriter(
                        new GZIPOutputStream(new BufferedOutputStream(
                                Files.newOutputStream(bucketPath(buildDir, i)), IO_BUFFER_BYTES), IO_BUFFER_BYTES),
                        StandardCharsets.UTF_8), IO_BUFFER_BYTES);
            }
            Path source = MatsimAnalysisCache.personTracksPath(data);
            try (InputStream raw = new BufferedInputStream(Files.newInputStream(source), IO_BUFFER_BYTES);
                 InputStream gzip = new GZIPInputStream(raw, IO_BUFFER_BYTES);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(gzip, StandardCharsets.UTF_8), IO_BUFFER_BYTES)) {
                reader.readLine(); // header
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    String[] parts = line.split("\t", 4);
                    String personId = parts.length > 2 ? parts[2] : "";
                    int bucket = Math.floorMod(personId.hashCode(), partitions);
                    writers[bucket].write(line);
                    writers[bucket].newLine();
                    count++;
                }
            }
            closeWriters(writers);
            writers = null;

            Map<String, Object> manifest = new HashMap<>();
            manifest.put("status", "ready");
            manifest.put("cacheVersion", PARTITION_CACHE_VERSION);
            manifest.put("generatedAt", System.currentTimeMillis());
            manifest.put("partitions", partitions);
            manifest.put("trackCount", count);
            manifest.put("sourceSignature", sourceSignature(data));
            JSON.writeValue(buildDir.resolve(MANIFEST_FILE).toFile(), manifest);

            deleteTree(finalDir);
            try {
                Files.move(buildDir, finalDir, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(buildDir, finalDir, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("乘客明细按人分区完成: model={}, tracks={}, partitions={}", data.getName(), count, partitions);
        } catch (Exception e) {
            closeWriters(writers);
            deleteTree(buildDir);
            throw new RuntimeException("乘客明细按人分区失败: " + e.getMessage(), e);
        }
    }

    private static void readTrackFile(Path path, Consumer<PTPersonTrack> consumer) {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(path), IO_BUFFER_BYTES);
             InputStream gzip = new GZIPInputStream(raw, IO_BUFFER_BYTES);
             BufferedReader reader = new BufferedReader(new InputStreamReader(gzip, StandardCharsets.UTF_8), IO_BUFFER_BYTES)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("time\tenter\t")) continue;
                PTPersonTrack track = parse(line);
                if (track != null) consumer.accept(track);
            }
        } catch (Exception e) {
            throw new RuntimeException("读取乘客明细失败: " + path + ", " + e.getMessage(), e);
        }
    }

    static PTPersonTrack parse(String line) {
        String[] parts = line.split("\t", -1);
        if (parts.length < 8) {
            throw new IllegalArgumentException("乘客明细记录字段不足: expected=8, actual=" + parts.length);
        }
        PTPersonTrack track = new PTPersonTrack();
        track.setTime(parseDouble(parts[0]));
        track.setEnter(Boolean.parseBoolean(parts[1]));
        if (!parts[2].isBlank()) {
            // PersonId.create 使用进程级无界静态缓存；V6 有约两千万人，流式读取时必须用非缓存实例。
            PersonId personId = new PersonId();
            personId.setId(parts[2]);
            track.setPersonId(personId);
        }
        track.setLineId(parts[3].isBlank() ? null : LineId.create(parts[3]));
        track.setRouteId(parts[4].isBlank() ? null : RouteId.create(parts[4]));
        track.setVehicleId(parts[5].isBlank() ? null : VehicleId.create(parts[5]));
        track.setDepartureId(parts[6].isBlank() ? null : DepartureId.create(parts[6]));
        track.setFacilityId(parts[7].isBlank() ? null : StopFacilityId.create(parts[7]));
        return track;
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("乘客明细时间字段非法: " + value, error);
        }
    }

    private static String personKey(PTPersonTrack track) {
        return track == null || track.getPersonId() == null ? "" : track.getPersonId().toString();
    }

    private static void invoke(PersonConsumer consumer, String personId, List<PTPersonTrack> tracks) {
        try {
            consumer.accept(personId, tracks);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void requireDiskStore(MatsimData data) {
        if (!MatsimAnalysisCache.isPersonTrackStoreReady(data)) {
            throw new IllegalStateException("乘客明细磁盘工件未就绪: model=" + data.getName());
        }
    }

    private static int partitionCount() {
        int configured = Integer.getInteger("gjcxfzksh.person-track-partitions", DEFAULT_PARTITIONS);
        return Math.max(8, Math.min(128, configured));
    }

    private static String sourceSignature(MatsimData data) {
        return MatsimSourceFingerprint.signature(MatsimAnalysisCache.personTracksPath(data));
    }

    private static Path partitionDir(MatsimData data) {
        return MatsimCachePaths.versionDir(data, PARTITION_CACHE_VERSION);
    }

    private static Path bucketPath(Path dir, int index) {
        return dir.resolve(String.format("%s%03d.tsv.gz", BUCKET_PREFIX, index));
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : -1L;
    }

    private static void closeWriters(BufferedWriter[] writers) {
        if (writers == null) return;
        for (BufferedWriter writer : writers) {
            if (writer == null) continue;
            try {
                writer.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            throw new RuntimeException("清理分区缓存目录失败: " + root, e);
        }
    }
}
