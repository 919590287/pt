package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.api.common.Constant;
import com.jts.gjcxfzksh.data.MatsimData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PTDataServiceMetricPolicyTest {

    @TempDir
    Path tempDir;

    @Test
    void missingAreaIsNoDataAndIsNotGuessedFromStops() throws Exception {
        MatsimData data = data("missing-area");
        data.setArea(0.0);
        Set<Coord> stops = Set.of(new Coord(0, 0), new Coord(10_000, 0), new Coord(0, 10_000));

        assertNull(PTDataServiceImpl.effectiveAreaKm2(data, stops));

        data.setArea(1.0);
        assertEquals(1.0, PTDataServiceImpl.effectiveAreaKm2(data, stops));
    }

    @Test
    void evaluationMemoryKeyContainsFormulaAndSourceRevision() throws Exception {
        MatsimData data = data("revision-key");
        String key = new PTDataServiceImpl().evaluationCacheKey(data);

        assertTrue(key.contains("#" + PTDataServiceImpl.EVALUATION_FORMULA_VERSION));
        assertTrue(key.contains("#visual="));
        assertTrue(key.contains("#revision="));
    }

    @Test
    void evaluationShareUsesOfficialPublicTransportMotorizedShare() {
        Object share = PTDataServiceImpl.modeShare(Map.of(
                "pt", 5.0,
                "bus", 20.0,
                "subway", 30.0,
                "car", 45.0
        ), Constant.ROUTE_MODE_PT);

        assertEquals(5.0, ((Number) share).doubleValue(), 1e-9,
                "评价层必须读取统一计算后的公共交通机动化出行分担率，不得回退为道路公交全方式占比");
    }

    private MatsimData data(String name) throws Exception {
        Path output = tempDir.resolve(name).resolve("output");
        Path cache = tempDir.resolve(name).resolve("cache");
        Files.createDirectories(output);
        Files.createDirectories(cache);
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());
        return new MatsimData(name, output.toString(), cache.toString(), false);
    }
}
