package com.jts.gjcxfzksh.api.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RealPassengerFlowGpsTrajectoryTest {

    @TempDir
    Path tempDir;

    @Test
    void usesRequestedGpsDateAndFiltersDuplicateDeviceReports() throws Exception {
        Path gps = writeGps();
        RealPassengerFlowServiceImpl service = new RealPassengerFlowServiceImpl();

        RealPassengerFlowServiceImpl.VehicleEvents loaded =
                service.loadGpsVehicleEvents("2026-07-02", gps, "sig");

        assertEquals("2026-07-02", loaded.serviceDate());
        assertEquals("gps", loaded.source());
        assertEquals(1, loaded.events().size());
        assertEquals("粤A00002D", loaded.events().getFirst().get(1));
        assertEquals(113.61, (Double) loaded.events().getFirst().get(3), 1e-9);
    }

    @Test
    void fallsBackToFirstGpsDateWhenPassengerDateHasNoGpsSnapshot() throws Exception {
        Path gps = writeGps();
        RealPassengerFlowServiceImpl service = new RealPassengerFlowServiceImpl();

        RealPassengerFlowServiceImpl.VehicleEvents loaded =
                service.loadGpsVehicleEvents("2026-03-10", gps, "sig");

        assertEquals("2026-07-01", loaded.serviceDate());
        assertEquals(2, loaded.events().size());
        assertEquals(List.of(0, 6), loaded.events().stream()
                .map(row -> ((Number) row.getFirst()).intValue()).toList());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> vehicles = (List<Map<String, Object>>) loaded.meta().get("vehicles");
        assertEquals(1, vehicles.size());
        assertEquals("南沙48路", vehicles.getFirst().get("lineName"));
    }

    private Path writeGps() throws Exception {
        Path file = tempDir.resolve("GPS.csv");
        String content = "\uFEFF\"LOCATION_TIME\",\"LONGITUDE\",\"LATITUDE\",\"R_LONGITUDE\",\"R_LATITUDE\","
                + "\"PLATE_NUMBER\",\"ROUTE_CODE\",\"STATION_NAME\",\"SPEED\",\"LOCATION_SPEED\"\n"
                + "\"2026-07-01 00:00:00\",\"113.50\",\"22.70\",\"113.49\",\"22.71\",\"粤A00001D\",\"南沙48路\",\"大岗站\",\"20\",\"20\"\n"
                + "\"2026-07-01 00:00:02\",\"113.51\",\"22.71\",\"113.50\",\"22.72\",\"粤A00001D\",\"南沙48路\",\"大岗站\",\"21\",\"21\"\n"
                + "\"2026-07-01 00:00:06\",\"113.52\",\"22.72\",\"113.51\",\"22.73\",\"粤A00001D\",\"南沙48路\",\"大岗站\",\"22\",\"22\"\n"
                + "\"2026-07-02 00:00:10\",\"113.60\",\"22.80\",\"113.61\",\"22.81\",\"粤A00002D\",\"南沙20路\",\"蕉门站\",\"30\",\"30\"\n";
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
