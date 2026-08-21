package com.jts.gjcxfzksh.data.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jts.gjcxfzksh.data.MatsimData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatsimPersonTrackStoreTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanupProperties() {
        System.clearProperty("gjcxfzksh.person-track-partitions");
        System.clearProperty("gjcxfzksh.person-tracks.max-materialized");
        MatsimSourceFingerprint.invalidateAll();
    }

    @Test
    void diskPartitionsKeepEveryPersonsRecordsTogetherWithoutMaterializingAllTracks() throws Exception {
        System.setProperty("gjcxfzksh.person-track-partitions", "8");
        Path output = Files.createDirectories(tempDir.resolve("model/output"));
        Path cache = tempDir.resolve("cache");
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());
        Path events = output.resolve("output_events.xml.gz");
        Files.write(events, new byte[]{1, 2, 3, 4});

        MatsimData data = new MatsimData("area/public/v6", output.toString(), cache.toString(), true);
        Path trackDir = cache.resolve(MatsimAnalysisCache.PERSON_TRACK_CACHE_VERSION);
        Files.createDirectories(trackDir);
        Path tracks = MatsimAnalysisCache.personTracksPath(data);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(tracks)), StandardCharsets.UTF_8))) {
            writer.write("time\tenter\tpersonId\tlineId\trouteId\tvehicleId\tdepartureId\tfacilityId\n");
            // 故意交错两个 person，证明分组不依赖源文件排序。
            writer.write("10\ttrue\tp1\tl1\tr1\tv1\td1\ts1\n");
            writer.write("11\ttrue\tp2\tl1\tr1\tv2\td2\ts1\n");
            writer.write("20\tfalse\tp1\tl1\tr1\tv1\td1\ts2\n");
            writer.write("21\tfalse\tp2\tl1\tr1\tv2\td2\ts2\n");
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("status", "ready");
        manifest.put("cacheVersion", MatsimAnalysisCache.PERSON_TRACK_CACHE_VERSION);
        manifest.put("eventsModified", Files.getLastModifiedTime(events).toMillis());
        manifest.put("eventsSize", Files.size(events));
        manifest.put("eventsSignature", MatsimSourceFingerprint.signature(events));
        manifest.put("trackCount", 4);
        JSON.writeValue(trackDir.resolve("manifest.json").toFile(), manifest);

        assertTrue(MatsimAnalysisCache.isPersonTrackStoreReady(data));
        MatsimPersonTrackStore.preparePartitions(data);
        assertTrue(MatsimPersonTrackStore.isPartitionStoreReady(data));

        // 模拟旧版本误把超预算明细留在内存：访问层必须释放副本并仍从磁盘完整读取。
        System.setProperty("gjcxfzksh.person-tracks.max-materialized", "2");
        data.setPersonTracks(new LinkedHashSet<>(java.util.List.of(
                MatsimPersonTrackStore.parse("10\ttrue\tp1\tl1\tr1\tv1\td1\ts1"),
                MatsimPersonTrackStore.parse("11\ttrue\tp2\tl1\tr1\tv2\td2\ts1"),
                MatsimPersonTrackStore.parse("20\tfalse\tp1\tl1\tr1\tv1\td1\ts2"),
                MatsimPersonTrackStore.parse("21\tfalse\tp2\tl1\tr1\tv2\td2\ts2")
        )));

        Map<String, Integer> perPerson = new LinkedHashMap<>();
        MatsimPersonTrackStore.forEachPerson(data, (personId, personTracks) ->
                perPerson.put(personId, personTracks.size()));
        AtomicInteger flatCount = new AtomicInteger();
        MatsimPersonTrackStore.forEachTrack(data, ignored -> flatCount.incrementAndGet());

        assertEquals(Map.of("p1", 2, "p2", 2), perPerson);
        assertEquals(4, flatCount.get());
        assertTrue(data.getPersonTracks().isEmpty());
    }

    @Test
    void strongFingerprintDetectsContentReplacementWithSameSizeAndTimestamp() throws Exception {
        Path source = tempDir.resolve("source.bin");
        Files.writeString(source, "AAAA-BBBB", StandardCharsets.UTF_8);
        FileTime originalTime = Files.getLastModifiedTime(source);
        String before = MatsimSourceFingerprint.signature(source);

        Files.writeString(source, "CCCC-DDDD", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(source, originalTime);
        MatsimSourceFingerprint.invalidateAll();

        assertNotEquals(before, MatsimSourceFingerprint.signature(source));
    }
}
