package com.jts.gjcxfzksh.data.entry;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.ConfigUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
