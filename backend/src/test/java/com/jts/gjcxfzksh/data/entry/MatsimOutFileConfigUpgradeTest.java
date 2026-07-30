package com.jts.gjcxfzksh.data.entry;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.ConfigUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatsimOutFileConfigUpgradeTest {

    @BeforeAll
    static void preferLocalDtds() {
        System.setProperty("matsim.preferLocalDtds", "true");
    }

    private static final String CONFIG_WITH_UNKNOWN_CONTROLLER_PARAM =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<!DOCTYPE config SYSTEM \"http://www.matsim.org/files/dtd/config_v2.dtd\">\n"
                    + "<config>\n"
                    + "  <module name=\"controller\">\n"
                    + "    <param name=\"outputDirectory\" value=\"./output\" />\n"
                    + "    <param name=\"lastIteration\" value=\"0\" />\n"
                    + "    <param name=\"aTotallyRemovedParam\" value=\"boom\" />\n"
                    + "  </module>\n"
                    + "</config>\n";

    private static final String VALID_CONFIG =
            CONFIG_WITH_UNKNOWN_CONTROLLER_PARAM
                    .replace("    <param name=\"aTotallyRemovedParam\" value=\"boom\" />\n", "");

    @Test
    void incompatibleConfigUsesCacheCopyWithoutChangingSource(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("output");
        Path cache = tempDir.resolve("cache");
        Files.createDirectories(output);
        Path raw = output.resolve("output_config_reduced.xml");
        Files.writeString(raw, CONFIG_WITH_UNKNOWN_CONTROLLER_PARAM, StandardCharsets.UTF_8);
        String original = Files.readString(raw, StandardCharsets.UTF_8);

        MatsimOutFile out = MatsimOutFile.reload(output.toString(), cache.toString());
        Path converted = cache.resolve("generated-inputs/output_config_reduced.v2025s.xml");

        assertDoesNotThrow(out::loadConfig);
        assertTrue(Files.exists(converted));
        assertEquals(converted.toAbsolutePath().toString(), out.getConfig());
        assertFalse(Files.readString(converted).contains("aTotallyRemovedParam"));
        assertEquals(original, Files.readString(raw), "兼容加载不得修改原始模型配置");
        assertTrue(out.isSourceOrCompatibleConvertedConfig(converted.toString()));
    }

    @Test
    void validSourceConfigLoadsOnceWithoutDerivedFallback(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("output");
        Path cache = tempDir.resolve("cache");
        Files.createDirectories(output);
        Path raw = output.resolve("output_config_reduced.xml");
        Files.writeString(raw, VALID_CONFIG, StandardCharsets.UTF_8);

        MatsimOutFile out = MatsimOutFile.reload(output.toString(), cache.toString());
        var first = out.loadConfig();

        assertSame(first, out.loadConfig());
        assertEquals(raw.toAbsolutePath().toString(), out.getConfig());
        assertEquals(raw.toAbsolutePath().toString(), out.getSourceConfig());
        assertFalse(out.isSourceOrCompatibleConvertedConfig(tempDir.resolve("converted.xml").toString()));
        assertThrows(Exception.class, () -> ConfigUtils.loadConfig(
                tempDir.resolve("missing-config.xml").toString()));
    }

    @Test
    void ignoresSimwrapperYamlWhenDiscoveringMatsimConfig(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("output");
        Path cache = tempDir.resolve("cache");
        Files.createDirectories(output);
        Path reduced = output.resolve("output_config_reduced.xml");
        Files.writeString(reduced, VALID_CONFIG, StandardCharsets.UTF_8);
        Files.writeString(output.resolve("output_config.xml"), VALID_CONFIG, StandardCharsets.UTF_8);
        Files.writeString(output.resolve("simwrapper-config.yaml"), "defaultDashboards: []\n",
                StandardCharsets.UTF_8);

        MatsimOutFile out = MatsimOutFile.reload(output.toString(), cache.toString());

        assertEquals(reduced.toAbsolutePath().toString(), out.getSourceConfig());
        assertEquals(reduced.toAbsolutePath().toString(), out.getConfig());
    }
}
