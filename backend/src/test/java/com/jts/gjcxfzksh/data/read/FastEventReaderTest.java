package com.jts.gjcxfzksh.data.read;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FastEventReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void reportsFinalProgressForShortEventFiles() throws Exception {
        Path events = tempDir.resolve("events.xml");
        Files.writeString(events, """
                <events>
                  <event time="10.0" type="entered link" vehicle="v1" link="l1"/>
                  <event time="20.0" type="left link" vehicle="v1" link="l1"/>
                </events>
                """);
        AtomicLong handled = new AtomicLong();
        AtomicLong progressCount = new AtomicLong();
        double[] progressTime = {0.0};

        FastEventReader.read(events.toString(), (type, time, attributes) -> handled.incrementAndGet(),
                (count, time) -> {
                    progressCount.set(count);
                    progressTime[0] = time;
                });

        assertEquals(2L, handled.get());
        assertEquals(2L, progressCount.get());
        assertEquals(20.0, progressTime[0]);
    }
}
