package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.data.MatsimData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatsimLargeModelNetworkCacheTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearProperty() {
        System.clearProperty("gjcxfzksh.large-model.transit-network.enabled");
    }

    @Test
    void keepsOnlyScheduleLinksAndTheirEndpointNodes() throws Exception {
        Path output = tempDir.resolve("output");
        Path cache = tempDir.resolve("cache");
        Files.createDirectories(output);
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());

        Path network = output.resolve("output_network.xml.gz");
        writeGzip(network, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE network SYSTEM "http://www.matsim.org/files/dtd/network_v2.dtd">
                <network>
                  <attributes><attribute name="coordinateReferenceSystem" class="java.lang.String">EPSG:3857</attribute></attributes>
                  <nodes>
                    <node id="n1" x="0" y="0"/><node id="n2" x="1" y="0"/>
                    <node id="n3" x="2" y="0"/><node id="unused" x="9" y="9"/>
                  </nodes>
                  <links capperiod="01:00:00">
                    <link id="l1" from="n1" to="n2" length="1" freespeed="1" capacity="1" permlanes="1" modes="bus"/>
                    <link id="l2" from="n2" to="n3" length="1" freespeed="1" capacity="1" permlanes="1" modes="bus"/>
                    <link id="unused-link" from="n3" to="unused" length="1" freespeed="1" capacity="1" permlanes="1" modes="car"/>
                  </links>
                </network>
                """);
        Path schedule = output.resolve("output_transitSchedule.xml.gz");
        writeGzip(schedule, """
                <?xml version="1.0" encoding="UTF-8"?>
                <transitSchedule>
                  <transitStops><stopFacility id="s1" x="0" y="0" linkRefId="l1"/></transitStops>
                  <transitLine id="L1"><transitRoute id="R1"><route><link refId="l1"/><link refId="l2"/></route></transitRoute></transitLine>
                </transitSchedule>
                """);

        MatsimData data = new MatsimData("large-network-test", output.toString(), cache.toString(), true);
        String derived = MatsimLargeModelNetworkCache.resolveNetworkInput(data);
        assertNotEquals(network.toString(), derived);
        assertTrue(Files.isRegularFile(Path.of(derived)));
        assertTrue(MatsimLargeModelNetworkCache.isReady(data));

        String xml;
        try (var in = new java.util.zip.GZIPInputStream(Files.newInputStream(Path.of(derived)))) {
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(xml.contains("id=\"l1\""));
        assertTrue(xml.contains("id=\"l2\""));
        assertTrue(xml.contains("id=\"n3\""));
        assertFalse(xml.contains("unused-link"));
        assertFalse(xml.contains("id=\"unused\""));
        var parsed = NetworkUtils.createNetwork();
        new MatsimNetworkReader(parsed).readFile(derived);
        assertEquals(2, parsed.getLinks().size());
        assertEquals(3, parsed.getNodes().size());
        assertEquals(derived, MatsimLargeModelNetworkCache.resolveNetworkInput(data));
    }

    @Test
    void refusesUnsafeFullNetworkFallbackWhenLargeModelInputsAreIncomplete() throws Exception {
        Path output = tempDir.resolve("incomplete-output");
        Files.createDirectories(output);
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());
        writeGzip(output.resolve("output_network.xml.gz"), "<network><nodes/><links/></network>");
        MatsimData data = new MatsimData(
                "incomplete-large-network", output.toString(), tempDir.resolve("incomplete-cache").toString(), true);

        assertThrows(IllegalStateException.class,
                () -> MatsimLargeModelNetworkCache.resolveNetworkInput(data));
    }

    private static void writeGzip(Path path, String value) throws Exception {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(path))) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
