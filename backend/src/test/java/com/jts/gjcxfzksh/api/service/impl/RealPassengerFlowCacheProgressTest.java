package com.jts.gjcxfzksh.api.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * capabilities 下发的缓存进度字段：前端门槛据此判定真实数据是否就绪、显示构建进度，
 * 并用 sourceSignature 作为浏览器持久缓存的失效键。
 */
class RealPassengerFlowCacheProgressTest {

    @Test
    void cacheVersionTakesShortHashSegmentAsBrowserCacheKey() {
        assertEquals("78b33138-panel-bundle-v5-approved-c-load-num", RealPassengerFlowServiceImpl.cacheVersion(
                "78b33138:总体小时客流.csv:123:456|"));
        // 指纹缺失时给空串，前端据此退化为“只用内存缓存”，不会拿空键去读写持久缓存。
        assertEquals("", RealPassengerFlowServiceImpl.cacheVersion(""));
        assertEquals("", RealPassengerFlowServiceImpl.cacheVersion(null));
        assertEquals("", RealPassengerFlowServiceImpl.cacheVersion("没有分隔符"));
    }

    @Test
    void builtPanelDateCountReportsOnlyDatesWithArtifactOnDisk(@TempDir Path target) throws IOException {
        List<String> dates = List.of("2026-03-10", "2026-03-11", "2026-03-12");
        assertEquals(0, RealPassengerFlowServiceImpl.builtPanelDateCount(target, dates));

        writeArtifact(target, "2026-03-10");
        writeArtifact(target, "2026-03-12");
        assertEquals(2, RealPassengerFlowServiceImpl.builtPanelDateCount(target, dates));

        // 只建了目录没落文件的日期不算完成，否则门槛会提前放行到半成品缓存。
        Files.createDirectories(target.resolve("2026-03-11"));
        assertEquals(2, RealPassengerFlowServiceImpl.builtPanelDateCount(target, dates));

        writeArtifact(target, "2026-03-11");
        assertEquals(3, RealPassengerFlowServiceImpl.builtPanelDateCount(target, dates));
    }

    private void writeArtifact(Path target, String date) throws IOException {
        Path file = target.resolve(date).resolve("panel-bundle.json.gz");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
    }
}
