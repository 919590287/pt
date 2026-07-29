package com.jts.gjcxfzksh.config;

import com.jts.gjcxfzksh.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void registryScanReusesOutputListingForSizeAndCuttableFlag() throws Exception {
        MatsimConfig config = new MatsimConfig();
        Path dataRoot = tempDir.resolve("pt_data");
        Path output = dataRoot.resolve("广州/仿真数据/public/基准模型/output");
        Files.createDirectories(output);
        Files.write(output.resolve("output_plans.xml.gz"), new byte[]{1, 2, 3});
        Files.write(output.resolve("output_events.xml.gz"), new byte[]{4, 5});
        setField(config, "folder", dataRoot.toString());
        setField(config, "cacheFolder", tempDir.resolve("pt_cache").toString());
        setField(config, "largeModelThresholdBytes", 4L);

        config.init();

        var scheme = config.getSchemes().get("广州/public/基准模型");
        assertEquals(5L, scheme.getOutputBytes());
        assertTrue(scheme.isLargeModel());
        assertTrue(scheme.isCuttable());
    }

    @Test
    void singleHugePlansFileEntersLargeModeEvenWhenTotalThresholdIsHigher() throws Exception {
        MatsimConfig config = new MatsimConfig();
        Path dataRoot = tempDir.resolve("component-threshold-data");
        Path output = dataRoot.resolve("广州/仿真数据/public/大plans模型/output");
        Files.createDirectories(output);
        Files.write(output.resolve("output_plans.xml.gz"), new byte[]{1, 2, 3, 4});
        setField(config, "folder", dataRoot.toString());
        setField(config, "cacheFolder", tempDir.resolve("component-threshold-cache").toString());
        setField(config, "largeModelThresholdBytes", 100L);
        setField(config, "largeModelPlansThresholdBytes", 4L);
        setField(config, "largeModelEventsThresholdBytes", 100L);

        config.init();

        assertTrue(config.getSchemes().get("广州/public/大plans模型").isLargeModel());
    }

    @Test
    void descCanExplicitlyEnableLargeModeBelowAutomaticThreshold() throws Exception {
        MatsimConfig config = new MatsimConfig();
        Path dataRoot = tempDir.resolve("override-data");
        Path model = dataRoot.resolve("广州/仿真数据/public/显式模型");
        Path output = model.resolve("output");
        Files.createDirectories(output);
        Files.write(output.resolve("output_events.xml.gz"), new byte[]{1, 2, 3, 4});
        Files.writeString(model.resolve("desc.json"), "{\"largeModel\":true}");
        setField(config, "folder", dataRoot.toString());
        setField(config, "cacheFolder", tempDir.resolve("override-cache").toString());
        setField(config, "largeModelThresholdBytes", 100L);
        setField(config, "largeModelPlansThresholdBytes", 100L);
        setField(config, "largeModelEventsThresholdBytes", 100L);

        config.init();

        assertTrue(config.getSchemes().get("广州/public/显式模型").isLargeModel());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
