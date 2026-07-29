package com.jts.gjcxfzksh.data.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RealPopulationCacheTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsThreeMetricGridAndReconcilesStreetTotals() throws Exception {
        Path realRoot = tempDir.resolve("真实数据");
        Path source = RealPopulationCache.sourcePath(realRoot);
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                \uFEFF百米网格坐标（WGS-84）,通勤居住人口数量,通勤就业人口数量,常住人口数量
                113.264400;23.129100,10,4,18
                113.265500;23.129100,2,7,9
                """);

        Map<String, Object> summary = RealPopulationCache.summary(source);
        assertEquals("ready", summary.get("status"));
        assertEquals(12L, ((Number) summary.get("homePersons")).longValue());
        assertEquals(11L, ((Number) summary.get("workPersons")).longValue());
        assertEquals(27L, ((Number) summary.get("residentPersons")).longValue());

        Map<String, Object> streets = RealPopulationCache.streets(source);
        @SuppressWarnings("unchecked")
        Map<String, Object> streetTotals = (Map<String, Object>) streets.get("totals");
        long unassignedHome = ((Number) summary.get("unassignedHome")).longValue();
        long unassignedWork = ((Number) summary.get("unassignedWork")).longValue();
        long unassignedResident = ((Number) summary.get("unassignedResident")).longValue();
        assertEquals(12L, ((Number) streetTotals.get("home")).longValue() + unassignedHome);
        assertEquals(11L, ((Number) streetTotals.get("work")).longValue() + unassignedWork);
        assertEquals(27L, ((Number) streetTotals.get("resident")).longValue() + unassignedResident);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) streets.get("streets");
        assertEquals(176, rows.size());
        assertTrue(rows.stream().allMatch(row -> row.containsKey("resident")));

        byte[] bytes = RealPopulationCache.gridBytes(source);
        assertNotNull(bytes);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[4];
        buffer.get(magic);
        assertArrayEquals(new byte[]{'P', 'G', 'R', 'D'}, magic);
        assertEquals(3, Short.toUnsignedInt(buffer.getShort()));
        int count = buffer.getInt();
        assertTrue(count >= 1);
        buffer.getDouble();
        long home = 0;
        long work = 0;
        long resident = 0;
        for (int i = 0; i < count; i++) {
            buffer.getInt();
            buffer.getInt();
            home += Integer.toUnsignedLong(buffer.getInt());
            work += Integer.toUnsignedLong(buffer.getInt());
            resident += Integer.toUnsignedLong(buffer.getInt());
            buffer.getShort();
        }
        assertEquals(12L, home);
        assertEquals(11L, work);
        assertEquals(27L, resident);
        assertEquals(0, buffer.remaining());
        assertNotNull(RealPopulationCache.gridTag(source));
    }

    @Test
    void guangzhouDataDiskSmokeTestWhenSourceIsProvided() {
        String configured = System.getProperty("real.population.csv", "");
        assumeTrue(!configured.isBlank(), "仅在显式传入真实数据路径时运行");
        Path source = Path.of(configured);
        assumeTrue(Files.isRegularFile(source), "真实人口 CSV 不存在");

        Map<String, Object> summary = RealPopulationCache.summary(source);
        assertEquals(273_733L, ((Number) summary.get("sourceRows")).longValue());
        assertEquals(12_139_877L, ((Number) summary.get("homePersons")).longValue());
        assertEquals(12_140_244L, ((Number) summary.get("workPersons")).longValue());
        assertEquals(22_852_869L, ((Number) summary.get("residentPersons")).longValue());

        Map<String, Object> streets = RealPopulationCache.streets(source);
        @SuppressWarnings("unchecked")
        Map<String, Object> streetTotals = (Map<String, Object>) streets.get("totals");
        assertEquals(
                12_139_877L,
                ((Number) streetTotals.get("home")).longValue()
                        + ((Number) summary.get("unassignedHome")).longValue()
        );
        assertEquals(
                12_140_244L,
                ((Number) streetTotals.get("work")).longValue()
                        + ((Number) summary.get("unassignedWork")).longValue()
        );
        assertEquals(
                22_852_869L,
                ((Number) streetTotals.get("resident")).longValue()
                        + ((Number) summary.get("unassignedResident")).longValue()
        );
        assertNotNull(RealPopulationCache.gridBytes(source));
    }
}
