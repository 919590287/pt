package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.data.MatsimData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkFactory;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;

import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatsimAnalysisCacheLargeStreamTest {

    @TempDir
    Path tempDir;

    @Test
    void largeModelTrajectoryCacheStreamsChunksOutsideOutput() throws Exception {
        Path output = tempDir.resolve("model").resolve("output");
        Path cache = tempDir.resolve("pt_cache").resolve("area").resolve("public").resolve("model");
        Files.createDirectories(output);
        new ConfigWriter(ConfigUtils.createConfig()).write(output.resolve("output_config.xml").toString());
        writeEvents(output.resolve("output_events.xml.gz"));

        MatsimData data = new MatsimData("area/public/model", output.toString(), cache.toString(), true);
        data.setScenario(buildScenario());

        Map<String, Object> manifest = MatsimAnalysisCache.ensureTrajectoryCache(data);
        byte[] chunk = MatsimAnalysisCache.readTrajectoryBinaryChunk(data, 0);

        assertEquals("ready", manifest.get("status"));
        assertNotNull(chunk);
        assertTrue(chunk.length > 64);
        assertTrue(Files.exists(cache.resolve(MatsimAnalysisCache.TRAJECTORY_CACHE_VERSION).resolve("manifest.json")));
        assertFalse(Files.exists(output.resolve(".gjcxfzksh-cache")));
    }

    private void writeEvents(Path path) throws Exception {
        try (OutputStreamWriter writer = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(path)), StandardCharsets.UTF_8)) {
            writer.write("<events version=\"1.0\">\n");
            writer.write("<event time=\"0.0\" type=\"" + LinkEnterEvent.EVENT_TYPE + "\" vehicle=\"veh1\" link=\"l1\" />\n");
            writer.write("<event time=\"60.0\" type=\"" + LinkLeaveEvent.EVENT_TYPE + "\" vehicle=\"veh1\" link=\"l1\" />\n");
            writer.write("</events>\n");
        }
    }

    private MutableScenario buildScenario() {
        MutableScenario scenario = (MutableScenario) ScenarioUtils.createScenario(ConfigUtils.createConfig());
        Network network = scenario.getNetwork();
        NetworkFactory factory = network.getFactory();
        Node from = factory.createNode(Id.createNodeId("n1"), new Coord(0, 0));
        Node to = factory.createNode(Id.createNodeId("n2"), new Coord(100, 0));
        network.addNode(from);
        network.addNode(to);
        Link link = factory.createLink(Id.createLinkId("l1"), from, to);
        network.addLink(link);
        return scenario;
    }
}
