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

    private static Map<String, Object> flatFingerprint(String file, long size, long modified, String signature) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventsFile", file);
        result.put("eventsModified", modified);
        result.put("eventsSize", size);
        result.put("eventsSignature", signature);
        return result;
    }
}
