package com.jts.gjcxfzksh.data.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentAddressedArtifactStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void hardLinksIdenticalArtifactsAcrossModelsWithoutTouchingRawInputs() throws Exception {
        Path cacheRoot = Files.createDirectories(tempDir.resolve("cache"));
        Path firstModel = Files.createDirectories(cacheRoot.resolve("model-a"));
        Path secondModel = Files.createDirectories(cacheRoot.resolve("model-b"));
        Path rawInput = tempDir.resolve("raw-table.xlsx");
        byte[] content = new byte[128 * 1024];
        Arrays.fill(content, (byte) 37);
        Files.write(rawInput, new byte[]{1, 2, 3});
        Path first = firstModel.resolve("artifact.zst");
        Path second = secondModel.resolve("artifact.zst");
        Files.write(first, content);
        Files.write(second, content);

        ContentAddressedArtifactStore.Result firstResult =
                ContentAddressedArtifactStore.publish(cacheRoot, firstModel, 1L);
        ContentAddressedArtifactStore.Result secondResult =
                ContentAddressedArtifactStore.publish(cacheRoot, secondModel, 1L);
        ContentAddressedArtifactStore.Result repeatedFirstResult =
                ContentAddressedArtifactStore.publish(cacheRoot, firstModel, 1L);

        assertEquals(content.length, firstResult.linkedBytes());
        assertEquals(content.length, secondResult.reusedBytes());
        assertEquals(0L, repeatedFirstResult.reusedBytes());
        assertTrue(Files.isSameFile(first, second));
        // macOS APFS 会返回 >=3（CAS object + 两个模型）；某些测试文件系统不提供精确值。
        Object links = Files.getAttribute(first, "unix:nlink");
        assertTrue(((Number) links).longValue() >= 3L);
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(rawInput));
    }

    @Test
    void excludesMutableManifestsAndRejectsModelOutsideCacheRoot() throws Exception {
        Path cacheRoot = Files.createDirectories(tempDir.resolve("cache-boundary"));
        Path model = Files.createDirectories(cacheRoot.resolve("model"));
        Path manifest = model.resolve("manifest.json");
        Files.write(manifest, new byte[1024]);

        ContentAddressedArtifactStore.Result result =
                ContentAddressedArtifactStore.publish(cacheRoot, model, 1L);

        assertTrue(result.artifacts().isEmpty());
        assertFalse(Files.exists(cacheRoot.resolve(".cas-v1/sha256/manifest.json")));
        assertThrows(IllegalArgumentException.class, () ->
                ContentAddressedArtifactStore.publish(cacheRoot, tempDir.resolve("outside"), 1L));
    }

    @Test
    void exfatFallsBackToTransparentRelativeSymlinks() throws Exception {
        String configured = System.getProperty("cas.exfat.root", "");
        Assumptions.assumeTrue(!configured.isBlank(), "未指定 ExFAT 验收目录");
        Path root = Files.createDirectories(Path.of(configured)
                .resolve(".cas-exfat-test-" + UUID.randomUUID()));
        try {
            Path model = Files.createDirectories(root.resolve("model"));
            Path artifact = model.resolve("artifact.zst");
            byte[] expected = new byte[128 * 1024];
            Arrays.fill(expected, (byte) 19);
            Files.write(artifact, expected);

            ContentAddressedArtifactStore.Result result =
                    ContentAddressedArtifactStore.publish(root, model, 1L);

            assertEquals(expected.length, result.linkedBytes());
            assertTrue(Files.isSymbolicLink(artifact));
            assertArrayEquals(expected, Files.readAllBytes(artifact));
        } finally {
            if (Files.exists(root)) {
                try (var paths = Files.walk(root)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
    }

    @Test
    void incrementalPublishKeepsExistingCasSymlinksInRegistry() throws Exception {
        Path cacheRoot = Files.createDirectories(tempDir.resolve("cache-symlink-registry"));
        Path model = Files.createDirectories(cacheRoot.resolve("model"));
        String hash = "ab" + "c".repeat(62);
        Path object = cacheRoot.resolve(".cas-v1/sha256/ab/" + "c".repeat(62));
        Files.createDirectories(object.getParent());
        byte[] content = new byte[128 * 1024];
        Arrays.fill(content, (byte) 71);
        Files.write(object, content);
        Path artifact = model.resolve("artifact.zst");
        Files.createSymbolicLink(artifact, artifact.getParent().relativize(object));

        ContentAddressedArtifactStore.Result result =
                ContentAddressedArtifactStore.publish(cacheRoot, model, 1L);

        assertEquals(content.length, result.logicalBytes());
        assertEquals(content.length, result.linkedBytes());
        assertEquals(1, result.artifacts().size());
        assertEquals(hash, result.artifacts().getFirst().sha256());
        assertTrue(result.artifacts().getFirst().linked());
    }
}
