package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.data.MatsimData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatsimCachePathsTest {

    @Test
    void rebuildReplacesCurrentDirectoryAndRemovesOnlySameFamilyHistory(@TempDir Path tempDir)
            throws Exception {
        Path output = tempDir.resolve("output");
        Path cache = tempDir.resolve("cache");
        Files.createDirectories(output);
        Files.createDirectories(cache);
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());
        MatsimData data = new MatsimData("replace-cache-test", output.toString(), cache.toString(), false);

        Path current = cache.resolve(MatsimPopulationCache.POPULATION_CACHE_VERSION);
        Path history = cache.resolve("population-v2");
        Path unrelated = cache.resolve("transfer-v2");
        Files.createDirectories(current);
        Files.createDirectories(history);
        Files.createDirectories(unrelated);
        Files.writeString(current.resolve("stale-block.bin"), "stale");
        Files.writeString(history.resolve("old.bin"), "old");
        Files.writeString(unrelated.resolve("keep.bin"), "keep");

        MatsimCachePaths.recreateVersionDir(data, MatsimPopulationCache.POPULATION_CACHE_VERSION);
        assertTrue(Files.isDirectory(current));
        assertFalse(Files.exists(current.resolve("stale-block.bin")), "当前版本必须整体替换");
        Files.writeString(current.resolve("manifest.json"), "{}");

        MatsimCachePaths.deleteOtherVersions(
                data, "population-v", MatsimPopulationCache.POPULATION_CACHE_VERSION);
        assertFalse(Files.exists(history), "同组件历史版本不得叠加保留");
        assertTrue(Files.exists(current.resolve("manifest.json")));
        assertTrue(Files.exists(unrelated.resolve("keep.bin")), "不得误删其他组件");

        assertThrows(IllegalArgumentException.class,
                () -> MatsimCachePaths.recreateVersionDir(data, "../outside-v1"));
        assertThrows(IllegalArgumentException.class,
                () -> MatsimCachePaths.deleteOtherVersions(data, "population-v", "population-current"));
    }
}
