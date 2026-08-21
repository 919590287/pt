package com.jts.gjcxfzksh.data.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatsimSourceFingerprintTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearMemoizedFingerprints() {
        MatsimSourceFingerprint.invalidateAll();
    }

    @Test
    void signatureIsStableAcrossPathAndModifiedTime() throws Exception {
        byte[] content = "same MATSim model result".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path first = tempDir.resolve("disk-a/events.xml.gz");
        Path second = tempDir.resolve("disk-b/copied-events.xml.gz");
        Files.createDirectories(first.getParent());
        Files.createDirectories(second.getParent());
        Files.write(first, content);
        Files.write(second, content);
        Files.setLastModifiedTime(first, FileTime.fromMillis(1_000L));
        Files.setLastModifiedTime(second, FileTime.fromMillis(9_000L));

        assertEquals(MatsimSourceFingerprint.signature(first), MatsimSourceFingerprint.signature(second));
    }

    @Test
    void signatureChangesWhenContentChangesEvenWithRestoredTimestamp() throws Exception {
        Path source = tempDir.resolve("events.xml");
        Files.writeString(source, "version-a");
        FileTime timestamp = FileTime.fromMillis(12_345L);
        Files.setLastModifiedTime(source, timestamp);
        String before = MatsimSourceFingerprint.signature(source);

        Files.writeString(source, "version-b");
        Files.setLastModifiedTime(source, timestamp);
        MatsimSourceFingerprint.invalidateAll();

        assertNotEquals(before, MatsimSourceFingerprint.signature(source));
    }

    @Test
    void deletionImmediatelyOverridesMemoizedSignature() throws Exception {
        Path source = tempDir.resolve("events-to-archive.xml.gz");
        Files.writeString(source, "events");
        assertNotEquals("missing", MatsimSourceFingerprint.signature(source));

        Files.delete(source);

        assertEquals("missing", MatsimSourceFingerprint.signature(source));
    }

    @Test
    void componentFingerprintIgnoresPathAndMtimeButDetectsDependencyChanges() {
        Map<String, Object> expected = flatFingerprint("/new-mount/events.xml", 200L, 20L, "content-a");
        expected.put("featurePolicy", "v2");
        Map<String, Object> stored = flatFingerprint("/old-mount/events.xml", 200L, 10L, "content-a");
        stored.put("featurePolicy", "v2");

        assertTrue(MatsimSourceFingerprint.sameFlatFingerprint(expected, stored));

        stored.put("eventsSignature", "content-b");
        assertFalse(MatsimSourceFingerprint.sameFlatFingerprint(expected, stored));

        stored.put("eventsSignature", "content-a");
        stored.put("featurePolicy", "v1");
        assertFalse(MatsimSourceFingerprint.sameFlatFingerprint(expected, stored));
    }

    @Test
    void legacyManifestWithoutSignatureIsRejected() {
        Map<String, Object> expected = flatFingerprint("/model/events.xml", 200L, 20L, "content-a");
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("eventsFile", "/model/events.xml");
        stored.put("eventsModified", 20L);
        stored.put("eventsSize", 200L);

        assertFalse(MatsimSourceFingerprint.sameFlatFingerprint(expected, stored));
    }

    @Test
    void archivedEventsAndPlansRemainValidButOtherMissingSourcesDoNot() {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.putAll(flatFingerprint("/model/events.xml", 200L, 20L, "events-content"));
        stored.putAll(flatFingerprint("plans", "/model/plans.xml", 300L, 30L, "plans-content"));
        stored.putAll(flatFingerprint("network", "/model/network.xml", 400L, 40L, "network-content"));

        Map<String, Object> current = new LinkedHashMap<>();
        current.putAll(flatFingerprint(null, 0L, 0L, "missing"));
        current.putAll(flatFingerprint("plans", null, 0L, 0L, "missing"));
        current.putAll(flatFingerprint("network", "/model/network.xml", 400L, 40L, "network-content"));

        assertTrue(MatsimSourceFingerprint.sameFlatFingerprint(current, stored));

        current.putAll(flatFingerprint("network", null, 0L, 0L, "missing"));
        assertFalse(MatsimSourceFingerprint.sameFlatFingerprint(current, stored));
    }

    @Test
    void restoredHeavySourceMustStillMatchOriginalContent() {
        Map<String, Object> stored = flatFingerprint("/model/events.xml", 200L, 20L, "content-a");

        assertTrue(MatsimSourceFingerprint.sameFlatFingerprint(
                flatFingerprint(null, 0L, 0L, "missing"), stored));
        assertFalse(MatsimSourceFingerprint.sameFlatFingerprint(
                flatFingerprint("/model/events.xml", 200L, 30L, "content-b"), stored));
        assertTrue(MatsimSourceFingerprint.sameFlatFingerprint(
                flatFingerprint("/model/events.xml", 200L, 30L, "content-a"), stored));
    }

    @Test
    void nestedFingerprintAllowsOnlyArchivedEventsOrPlans() {
        Map<String, Object> missing = sourceItem(0L, "missing");
        Map<String, Object> stored = sourceItem(200L, "content-a");

        assertTrue(MatsimSourceFingerprint.sameSourceItem("events", missing, stored));
        assertTrue(MatsimSourceFingerprint.sameSourceItem("plans", missing, stored));
        assertFalse(MatsimSourceFingerprint.sameSourceItem("network", missing, stored));
        assertFalse(MatsimSourceFingerprint.sameSourceItem("events", sourceItem(200L, "content-b"), stored));
    }

    private static Map<String, Object> flatFingerprint(String file, long size, long modified, String signature) {
        return flatFingerprint("events", file, size, modified, signature);
    }

    private static Map<String, Object> flatFingerprint(String base, String file, long size, long modified,
                                                        String signature) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(base + "File", file);
        result.put(base + "Modified", modified);
        result.put(base + "Size", size);
        result.put(base + "Signature", signature);
        return result;
    }

    private static Map<String, Object> sourceItem(long size, String signature) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("size", size);
        result.put("signature", signature);
        return result;
    }
}
