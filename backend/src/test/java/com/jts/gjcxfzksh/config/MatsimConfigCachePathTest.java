package com.jts.gjcxfzksh.config;

import com.jts.gjcxfzksh.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MatsimConfigCachePathTest {

    @TempDir
    Path tempDir;

    @Test
    void explicitCacheRootKeepsModelCacheOutsideDataOutput() throws Exception {
        MatsimConfig config = new MatsimConfig();
        Path dataRoot = tempDir.resolve("pt_data");
        Path cacheRoot = tempDir.resolve("pt_cache");
        setField(config, "folder", dataRoot.toString());
        setField(config, "cacheFolder", cacheRoot.toString());

        Path cachePath = config.cachePath("广州", "public", "基准模型A");

        assertEquals(cacheRoot.resolve("广州").resolve("public").resolve("基准模型A").normalize(), cachePath);
    }

    @Test
    void defaultCacheRootUsesSiblingPtCacheDirectory() throws Exception {
        MatsimConfig config = new MatsimConfig();
        Path dataRoot = tempDir.resolve("pt_data");
        setField(config, "folder", dataRoot.toString());
        setField(config, "cacheFolder", "");

        assertEquals(tempDir.resolve("pt_cache").normalize(), config.cacheRootPath());
    }

    @Test
    void cachePathRejectsUnsafeSegments() throws Exception {
        MatsimConfig config = new MatsimConfig();
        setField(config, "folder", tempDir.resolve("pt_data").toString());
        setField(config, "cacheFolder", tempDir.resolve("pt_cache").toString());

        assertThrows(BusinessException.class, () -> config.cachePath("广州", "public", "../模型"));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
