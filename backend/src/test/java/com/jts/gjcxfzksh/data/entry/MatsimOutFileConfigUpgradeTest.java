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

/**
 * 复现并验证“较大/异版本模型加载报 SAXParseException: Module controller doesn't accept unknown parameters”的修复。
 */
class MatsimOutFileConfigUpgradeTest {

    @BeforeAll
    static void preferLocalDtds() {
        // 与生产环境一致：离线优先本地 DTD，避免测试联网拉取 DTD 卡顿
        System.setProperty("matsim.preferLocalDtds", "true");
    }

    /**
     * controller 模块带有当前 MATSim 版本已不存在的参数（模拟广州模型V5导出的 config）。
     */
    private static final String CONFIG_WITH_UNKNOWN_CONTROLLER_PARAM =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<!DOCTYPE config SYSTEM \"http://www.matsim.org/files/dtd/config_v2.dtd\">\n" +
            "<config>\n" +
            "    <module name=\"controller\" >\n" +
            "        <param name=\"outputDirectory\" value=\"./output\" />\n" +
            "        <param name=\"lastIteration\" value=\"0\" />\n" +
            "        <param name=\"aTotallyRemovedParam\" value=\"boom\" />\n" +
            "    </module>\n" +
            "    <module name=\"scoring\" >\n" +
            "        <parameterset type=\"scoringParameters\" >\n" +
            "            <parameterset type=\"activityParams\" >\n" +
            "                <param name=\"activityType\" value=\"home\" />\n" +
            "                <param name=\"typicalDuration\" value=\"12:00:00\" />\n" +
            "            </parameterset>\n" +
            "        </parameterset>\n" +
            "    </module>\n" +
            "</config>\n";

    @Test
    void unknownStrictParamCrashesRawConfig(@TempDir Path tempDir) throws Exception {
        Path raw = tempDir.resolve("output_config_reduced.xml");
        Files.writeString(raw, CONFIG_WITH_UNKNOWN_CONTROLLER_PARAM, StandardCharsets.UTF_8);
        // 未清理前：严格的 controller 配置组会拒绝未知参数 -> 加载失败（即用户遇到的报错）
        assertThrows(Exception.class, () -> ConfigUtils.loadConfig(raw.toString()));
    }

    @Test
    void sanitizationDropsUnknownParamButKeepsValidAndParameterSet(@TempDir Path tempDir) throws Exception {
        String upgraded = MatsimOutFile.config15to2024Val(CONFIG_WITH_UNKNOWN_CONTROLLER_PARAM);

        // 未知参数被删除，合法参数与 parameterset 内参数保留
        assertFalse(upgraded.contains("aTotallyRemovedParam"), "未知严格参数应被删除");
        assertTrue(upgraded.contains("outputDirectory"), "合法 controller 参数应保留");
        assertTrue(upgraded.contains("lastIteration"), "合法 controller 参数应保留");
        assertTrue(upgraded.contains("activityType"), "parameterset 内参数应保留");
        assertTrue(upgraded.contains("config_v2.dtd"), "应保留 DOCTYPE 以便按 config_v2 解析");

        // 清理后可被 MATSim 正常加载
        Path fixed = tempDir.resolve("output_config_reduced.v2025s.xml");
        Files.writeString(fixed, upgraded, StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> ConfigUtils.loadConfig(fixed.toString()));
    }

    @Test
    void outputScanDefersConfigParsingAndCachesTheParsedConfig(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("output");
        Path cache = tempDir.resolve("cache");
        Files.createDirectories(output);
        Path raw = output.resolve("output_config_reduced.xml");
        Files.writeString(raw, CONFIG_WITH_UNKNOWN_CONTROLLER_PARAM, StandardCharsets.UTF_8);

        MatsimOutFile out = MatsimOutFile.reload(output.toString(), cache.toString());
        Path converted = cache.resolve("generated-inputs/output_config_reduced.v2025s.xml");

        assertEquals(raw.toAbsolutePath().toString(), out.getConfig());
        assertEquals(raw.toAbsolutePath().toString(), out.getSourceConfig());
        assertFalse(Files.exists(converted), "目录扫描不应解析或转换配置");

        var first = assertDoesNotThrow(out::loadConfig);
        assertTrue(Files.exists(converted), "真正加载模型时仍应执行旧配置兼容转换");
        assertEquals(converted.toAbsolutePath().toString(), out.getConfig());
        assertEquals(raw.toAbsolutePath().toString(), out.getSourceConfig(), "源指纹不得切换到派生配置");
        assertTrue(out.isSourceOrCompatibleConvertedConfig(converted.toString()));
        assertSame(first, out.loadConfig(), "同一输出实例不应重复解析配置");
    }

    @Test
    void convertedConfigIsReusableAcrossInstancesAndTracksSourceChanges(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("output");
        Path cache = tempDir.resolve("cache");
        Files.createDirectories(output);
        Path raw = output.resolve("output_config_reduced.xml");
        Files.writeString(raw, CONFIG_WITH_UNKNOWN_CONTROLLER_PARAM, StandardCharsets.UTF_8);

        MatsimOutFile first = MatsimOutFile.reload(output.toString(), cache.toString());
        assertDoesNotThrow(first::loadConfig);
        Path converted = cache.resolve("generated-inputs/output_config_reduced.v2025s.xml");
        Path version = Path.of(converted + ".version");
        String initialFingerprint = Files.readString(version, StandardCharsets.UTF_8);
        assertFalse(initialFingerprint.isBlank(), "转换缓存必须记录源文件指纹");

        MatsimOutFile second = MatsimOutFile.reload(output.toString(), cache.toString());
        assertDoesNotThrow(second::loadConfig);
        assertEquals(converted.toAbsolutePath().toString(), second.getConfig());
        assertEquals(initialFingerprint, Files.readString(version, StandardCharsets.UTF_8));

        Files.writeString(raw, CONFIG_WITH_UNKNOWN_CONTROLLER_PARAM + "\n", StandardCharsets.UTF_8);
        assertFalse(first.isSourceOrCompatibleConvertedConfig(converted.toString()),
                "原始配置变化后不得继续接受旧转换工件");
        MatsimOutFile afterSourceChange = MatsimOutFile.reload(output.toString(), cache.toString());
        assertDoesNotThrow(afterSourceChange::loadConfig);
        assertFalse(initialFingerprint.equals(Files.readString(version, StandardCharsets.UTF_8)),
                "原始配置变化后必须重新生成转换缓存");
    }

    @Test
    void corruptConvertedConfigIsRebuiltAtomically(@TempDir Path tempDir) throws Exception {
        Path output = tempDir.resolve("output");
        Path cache = tempDir.resolve("cache");
        Files.createDirectories(output);
        Files.writeString(output.resolve("output_config_reduced.xml"),
                CONFIG_WITH_UNKNOWN_CONTROLLER_PARAM, StandardCharsets.UTF_8);

        MatsimOutFile first = MatsimOutFile.reload(output.toString(), cache.toString());
        assertDoesNotThrow(first::loadConfig);
        Path converted = cache.resolve("generated-inputs/output_config_reduced.v2025s.xml");
        Files.writeString(converted, "<broken>", StandardCharsets.UTF_8);

        MatsimOutFile recovered = MatsimOutFile.reload(output.toString(), cache.toString());
        assertDoesNotThrow(recovered::loadConfig);
        String rebuilt = Files.readString(converted, StandardCharsets.UTF_8);
        assertTrue(rebuilt.contains("outputDirectory"));
        assertFalse(rebuilt.contains("aTotallyRemovedParam"));
    }
}
