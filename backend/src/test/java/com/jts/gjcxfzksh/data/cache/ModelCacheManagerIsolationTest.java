package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.config.MatsimConfig;
import com.jts.gjcxfzksh.data.entry.Scheme;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCacheManagerIsolationTest {

    @Test
    void childCommandHasIndependentHeapAndNonWebBuilderMode(@TempDir Path tempDir) throws Exception {
        MatsimConfig config = new MatsimConfig();
        set(config, "folder", tempDir.resolve("data").toString());
        set(config, "cacheFolder", tempDir.resolve("cache").toString());
        set(config, "largeModelThresholdBytes", 101L);
        set(config, "largeModelPlansThresholdBytes", 102L);
        set(config, "largeModelEventsThresholdBytes", 103L);
        set(config, "largeModelAnalysisTableThresholdBytes", 104L);
        set(config, "largeModelPersonTrackThreshold", 105L);

        ModelCacheManager manager = new ModelCacheManager();
        set(manager, "matsimConfig", config);
        set(manager, "builderXms", "192m");
        set(manager, "builderXmx", "5g");
        set(manager, "builderMaxMetaspace", "384m");
        set(manager, "processingThreads", 3);
        set(manager, "minFreeBytes", 0L);
        set(manager, "cleanOldVersions", true);
        set(manager, "contentAddressingEnabled", true);
        set(manager, "contentAddressingMinBytes", 65_536L);

        Scheme scheme = new Scheme();
        scheme.setName("area/public/model");
        List<String> command = manager.cacheBuilderCommand(scheme, tempDir.resolve("status.json"));

        assertTrue(command.contains("-Xms192m"));
        assertTrue(command.contains("-Xmx5g"));
        assertTrue(command.contains("-XX:MaxMetaspaceSize=384m"));
        assertTrue(command.contains("--matsim.cache-builder.process=true"));
        assertTrue(command.contains("--matsim.cache-builder.isolated-enabled=false"));
        assertTrue(command.contains("--matsim.processing-threads=3"));
        assertTrue(command.contains("--matsim.data=" + tempDir.resolve("data")));
        assertTrue(command.contains("--matsim.cache=" + tempDir.resolve("cache")));
        assertTrue(command.contains("--spring.main.web-application-type=none"));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
