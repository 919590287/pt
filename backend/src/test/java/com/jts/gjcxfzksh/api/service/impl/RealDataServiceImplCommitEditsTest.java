package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.api.model.params.RealDataCommitParam;
import com.jts.gjcxfzksh.api.model.params.RealDataParam;
import com.jts.gjcxfzksh.api.model.vo.RealDataExportVO;
import com.jts.gjcxfzksh.config.MatsimConfig;
import com.jts.gjcxfzksh.exception.BusinessException;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.data.SimpleFeatureStore;
import org.geotools.api.data.Transaction;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealDataServiceImplCommitEditsTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    @TempDir
    Path tempDir;

    @Test
    void commitEditsWritesStationChangesIntoVersionShpWithoutDoubleApplying() throws Exception {
        Path dataRoot = tempDir.resolve("pt_data");
        Path realDataRoot = dataRoot.resolve("广州").resolve(MatsimConfig.REAL_DATA_FOLDER);
        Path lineFolder = realDataRoot.resolve("公交线路站点").resolve("线路");
        Path stationFolder = realDataRoot.resolve("公交线路站点").resolve("站点");
        Files.createDirectories(lineFolder);
        Files.createDirectories(stationFolder);
        writeLines(lineFolder.resolve("line_clean.shp"));
        writeStations(stationFolder.resolve("station_clean.shp"));

        MatsimConfig config = new MatsimConfig();
        setField(config, "folder", dataRoot.toString());
        RealDataServiceImpl service = new RealDataServiceImpl();
        setField(service, "matsimConfig", config);

        RealDataCommitParam param = new RealDataCommitParam();
        param.setAreaName("广州");
        param.setDatasetType("station");
        param.setMessage("测试新增站点");
        param.setEvidenceImages(List.of(evidenceImage()));
        param.setOperations(List.of(addStationOperation()));

        Map<String, Object> commitResult = commit(service, "tester", param);
        String versionId = String.valueOf(commitResult.get("versionId"));
        Path versionStationShp = realDataRoot.resolve("_versions").resolve(versionId)
                .resolve("公交线路站点").resolve("站点").resolve("station_clean.shp");

        List<String> physicalStationNames = stationNames(versionStationShp);
        assertEquals(3, physicalStationNames.size());
        assertTrue(physicalStationNames.contains("新增站"));

        Map<String, Object> latest = service.busLineStation("广州", null);
        Map<String, Object> routeStops = mapValue(latest.get("routeStops"));
        List<Map<String, Object>> features = mapList(routeStops.get("features"));
        assertEquals(3, features.size());
        long addedCount = features.stream()
                .map(RealDataServiceImplCommitEditsTest::properties)
                .filter(properties -> "新增站".equals(properties.get("stop_name")))
                .count();
        assertEquals(1, addedCount);

        Map<String, Object> history = service.history("广州");
        Map<String, Object> version = mapList(history.get("versions")).stream()
                .filter(item -> versionId.equals(item.get("versionId")))
                .findFirst()
                .orElseThrow();
        assertEquals(1, mapList(version.get("evidenceImages")).size());
        assertEquals(1, mapList(mapList(version.get("operations")).get(0).get("evidenceImages")).size());
    }

    @Test
    void lineCommitCanMaterializeRouteStationMembershipChangesAndPreserveOperationType() throws Exception {
        Path dataRoot = tempDir.resolve("pt_data");
        Path realDataRoot = dataRoot.resolve("广州").resolve(MatsimConfig.REAL_DATA_FOLDER);
        Path lineFolder = realDataRoot.resolve("公交线路站点").resolve("线路");
        Path stationFolder = realDataRoot.resolve("公交线路站点").resolve("站点");
        Files.createDirectories(lineFolder);
        Files.createDirectories(stationFolder);
        writeLines(lineFolder.resolve("line_clean.shp"));
        writeStations(stationFolder.resolve("station_clean.shp"));

        MatsimConfig config = new MatsimConfig();
        setField(config, "folder", dataRoot.toString());
        RealDataServiceImpl service = new RealDataServiceImpl();
        setField(service, "matsimConfig", config);

        RealDataCommitParam param = new RealDataCommitParam();
        param.setAreaName("广州");
        param.setDatasetType("line");
        param.setMessage("线路新增站点");
        param.setOperations(List.of(addStationOperation()));

        Map<String, Object> commitResult = commit(service, "tester", param);
        String versionId = String.valueOf(commitResult.get("versionId"));
        Path versionStationShp = realDataRoot.resolve("_versions").resolve(versionId)
                .resolve("公交线路站点").resolve("站点").resolve("station_clean.shp");
        assertTrue(stationNames(versionStationShp).contains("新增站"));

        Map<String, Object> history = service.history("广州");
        Map<String, Object> version = mapList(history.get("versions")).stream()
                .filter(item -> versionId.equals(item.get("versionId")))
                .findFirst()
                .orElseThrow();
        assertEquals("line", version.get("datasetType"));
        assertEquals("station", mapList(version.get("operations")).get(0).get("datasetType"));
    }

    @Test
    void lineCommitReordersMultipleStationsWithOneHistoryOperation() throws Exception {
        Path dataRoot = tempDir.resolve("pt_data");
        Path realDataRoot = dataRoot.resolve("广州").resolve(MatsimConfig.REAL_DATA_FOLDER);
        Path lineFolder = realDataRoot.resolve("公交线路站点").resolve("线路");
        Path stationFolder = realDataRoot.resolve("公交线路站点").resolve("站点");
        Files.createDirectories(lineFolder);
        Files.createDirectories(stationFolder);
        writeLines(lineFolder.resolve("line_clean.shp"));
        writeStations(stationFolder.resolve("station_clean.shp"));

        MatsimConfig config = new MatsimConfig();
        setField(config, "folder", dataRoot.toString());
        RealDataServiceImpl service = new RealDataServiceImpl();
        setField(service, "matsimConfig", config);

        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operationId", "reorder-route-stations");
        operation.put("datasetType", "station");
        operation.put("type", "reorder_line_stations");
        operation.put("targetId", "440100017000");
        operation.put("title", "728路");
        operation.put("detail", "站点二：2→1；站点一：1→2");
        operation.put("payload", Map.of(
                "lineId", "440100017000",
                "changes", List.of(
                        Map.of("targetId", "station.2", "stopId", "S2", "stopName", "站点二", "fromSeq", "2", "toSeq", "1"),
                        Map.of("targetId", "station.1", "stopId", "S1", "stopName", "站点一", "fromSeq", "1", "toSeq", "2")
                )
        ));

        RealDataCommitParam param = new RealDataCommitParam();
        param.setAreaName("广州");
        param.setDatasetType("line");
        param.setMessage("调整线路站序");
        param.setOperations(List.of(operation));

        Map<String, Object> commitResult = commit(service, "tester", param);
        String versionId = String.valueOf(commitResult.get("versionId"));
        Map<String, Object> latest = service.busLineStation("广州", null);
        List<Map<String, Object>> routeStops = mapList(mapValue(latest.get("routeStops")).get("features"));
        Map<String, Object> stationOne = routeStops.stream()
                .map(RealDataServiceImplCommitEditsTest::properties)
                .filter(item -> "S1".equals(item.get("stop_id")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> stationTwo = routeStops.stream()
                .map(RealDataServiceImplCommitEditsTest::properties)
                .filter(item -> "S2".equals(item.get("stop_id")))
                .findFirst()
                .orElseThrow();
        assertEquals("2", String.valueOf(stationOne.get("seq")));
        assertEquals("1", String.valueOf(stationTwo.get("seq")));

        Map<String, Object> history = service.history("广州");
        Map<String, Object> version = mapList(history.get("versions")).stream()
                .filter(item -> versionId.equals(item.get("versionId")))
                .findFirst()
                .orElseThrow();
        List<Map<String, Object>> operations = mapList(version.get("operations"));
        assertEquals(1, operations.size());
        assertEquals("reorder_line_stations", operations.get(0).get("type"));
    }

    @Test
    void commitEditsMaterializesDepotChangesIntoLatestVersion() throws Exception {
        Path dataRoot = tempDir.resolve("pt_data");
        Path realDataRoot = dataRoot.resolve("广州").resolve(MatsimConfig.REAL_DATA_FOLDER);
        Path lineFolder = realDataRoot.resolve("公交线路站点").resolve("线路");
        Path stationFolder = realDataRoot.resolve("公交线路站点").resolve("站点");
        Path depotFolder = realDataRoot.resolve("公交场站");
        Files.createDirectories(lineFolder);
        Files.createDirectories(stationFolder);
        Files.createDirectories(depotFolder);
        writeLines(lineFolder.resolve("line_clean.shp"));
        writeStations(stationFolder.resolve("station_clean.shp"));
        writeDepots(depotFolder.resolve("depot_clean.shp"));

        MatsimConfig config = new MatsimConfig();
        setField(config, "folder", dataRoot.toString());
        RealDataServiceImpl service = new RealDataServiceImpl();
        setField(service, "matsimConfig", config);

        RealDataCommitParam param = new RealDataCommitParam();
        param.setAreaName("广州");
        param.setDatasetType("depot");
        param.setMessage("测试新增场站");
        param.setOperations(List.of(addDepotOperation()));

        Map<String, Object> commitResult = commit(service, "tester", param);
        String versionId = String.valueOf(commitResult.get("versionId"));
        Path versionDepotShp = realDataRoot.resolve("_versions").resolve(versionId)
                .resolve("公交场站").resolve("depot_clean.shp");

        List<String> physicalDepotNames = depotNames(versionDepotShp);
        assertEquals(2, physicalDepotNames.size());
        assertTrue(physicalDepotNames.contains("新增场站"));

        Map<String, Object> latest = service.busLineStation("广州", null);
        Map<String, Object> depots = mapValue(latest.get("depots"));
        List<Map<String, Object>> features = mapList(depots.get("features"));
        assertEquals(2, features.size());
        long addedCount = features.stream()
                .map(RealDataServiceImplCommitEditsTest::properties)
                .filter(properties -> "新增场站".equals(properties.get("depot_name")))
                .count();
        assertEquals(1, addedCount);
    }

    @Test
    void combinedCommitMaterializesLineStationAndDepotChangesInOneVersion() throws Exception {
        Path dataRoot = tempDir.resolve("pt_data");
        Path realDataRoot = dataRoot.resolve("广州").resolve(MatsimConfig.REAL_DATA_FOLDER);
        Path lineFolder = realDataRoot.resolve("公交线路站点").resolve("线路");
        Path stationFolder = realDataRoot.resolve("公交线路站点").resolve("站点");
        Path depotFolder = realDataRoot.resolve("公交场站");
        Files.createDirectories(lineFolder);
        Files.createDirectories(stationFolder);
        Files.createDirectories(depotFolder);
        writeLines(lineFolder.resolve("line_clean.shp"));
        writeStations(stationFolder.resolve("station_clean.shp"));
        writeDepots(depotFolder.resolve("depot_clean.shp"));

        MatsimConfig config = new MatsimConfig();
        setField(config, "folder", dataRoot.toString());
        RealDataServiceImpl service = new RealDataServiceImpl();
        setField(service, "matsimConfig", config);

        RealDataCommitParam param = new RealDataCommitParam();
        param.setAreaName("广州");
        param.setDatasetType("all");
        param.setMessage("综合更新线路站点场站");
        param.setOperations(List.of(
                updateLineHeadwayOperation(),
                addStationOperation(),
                addDepotOperation()
        ));

        Map<String, Object> commitResult = commit(service, "tester", param);
        String versionId = String.valueOf(commitResult.get("versionId"));
        Path versionRoot = realDataRoot.resolve("_versions").resolve(versionId);

        assertEquals(List.of("8"), shpAttributeValues(
                versionRoot.resolve("公交线路站点").resolve("线路").resolve("line_clean.shp"),
                "interval"
        ));
        assertTrue(shpAttributeFields(
                versionRoot.resolve("公交线路站点").resolve("线路").resolve("line_clean.shp")
        ).containsAll(List.of("len_km", "directness", "stop_count", "avg_stop_m")));
        assertTrue(stationNames(
                versionRoot.resolve("公交线路站点").resolve("站点").resolve("station_clean.shp")
        ).contains("新增站"));
        assertTrue(shpAttributeFields(
                versionRoot.resolve("公交线路站点").resolve("站点").resolve("station_clean.shp")
        ).contains("route_cnt"));
        assertTrue(depotNames(
                versionRoot.resolve("公交场站").resolve("depot_clean.shp")
        ).contains("新增场站"));

        Map<String, Object> history = service.history("广州");
        Map<String, Object> version = mapList(history.get("versions")).stream()
                .filter(item -> versionId.equals(item.get("versionId")))
                .findFirst()
                .orElseThrow();
        assertEquals("all", version.get("datasetType"));
        List<Map<String, Object>> operations = mapList(version.get("operations"));
        assertEquals(3, operations.size());
        assertEquals(List.of("line", "station", "depot"), operations.stream()
                .map(operation -> String.valueOf(operation.get("datasetType")))
                .toList());
    }

    @Test
    void exportVersionReturnsRequestedHistoricalCsvAndCompleteShpArchive() throws Exception {
        Path dataRoot = tempDir.resolve("pt_data");
        Path realDataRoot = dataRoot.resolve("广州").resolve(MatsimConfig.REAL_DATA_FOLDER);
        Path lineFolder = realDataRoot.resolve("公交线路站点").resolve("线路");
        Path stationFolder = realDataRoot.resolve("公交线路站点").resolve("站点");
        Files.createDirectories(lineFolder);
        Files.createDirectories(stationFolder);
        writeLines(lineFolder.resolve("line_clean.shp"));
        writeStations(stationFolder.resolve("station_clean.shp"));

        MatsimConfig config = new MatsimConfig();
        setField(config, "folder", dataRoot.toString());
        RealDataServiceImpl service = new RealDataServiceImpl();
        setField(service, "matsimConfig", config);

        Map<String, Object> firstCommit = commit(service, "tester", stationCommitParam("第一次",
                addStationOperation("add-first-export", "first-export", "导出站点一", "S3", "3", 113.3, 23.3)));
        String firstVersionId = String.valueOf(firstCommit.get("versionId"));
        commit(service, "tester", stationCommitParam("第二次",
                addStationOperation("add-second-export", "second-export", "导出站点二", "S4", "4", 113.4, 23.4)));

        RealDataExportVO csv = service.exportVersion("广州", firstVersionId, "station", "csv");
        String csvText = new String(csv.content(), StandardCharsets.UTF_8);
        assertEquals("text/csv;charset=UTF-8", csv.contentType());
        assertTrue(csv.fileName().endsWith(".csv"));
        assertTrue(csvText.contains("导出站点一"));
        assertTrue(!csvText.contains("导出站点二"));
        assertTrue(csvText.contains("stop_id,stop_name,lon,lat,route_cnt"));

        RealDataExportVO shp = service.exportVersion("广州", firstVersionId, "station", "shp");
        assertEquals("application/zip", shp.contentType());
        assertTrue(shp.fileName().endsWith(".zip"));
        Set<String> entries = zipEntryNames(shp.content());
        assertTrue(entries.stream().anyMatch(name -> name.endsWith(".shp")));
        assertTrue(entries.stream().anyMatch(name -> name.endsWith(".shx")));
        assertTrue(entries.stream().anyMatch(name -> name.endsWith(".dbf")));
        Path extractedStationShp = extractFirstShp(shp.content(), tempDir.resolve("station-export"));
        assertEquals(csvHeader(csvText), shpAttributeFields(extractedStationShp));
        assertTrue(shpAttributeFields(extractedStationShp).contains("route_cnt"));
    }

    @Test
    void exportedLineCsvAndShpShareDerivedAttributeSchema() throws Exception {
        Path dataRoot = tempDir.resolve("pt_data");
        Path realDataRoot = dataRoot.resolve("广州").resolve(MatsimConfig.REAL_DATA_FOLDER);
        Path lineFolder = realDataRoot.resolve("公交线路站点").resolve("线路");
        Path stationFolder = realDataRoot.resolve("公交线路站点").resolve("站点");
        Files.createDirectories(lineFolder);
        Files.createDirectories(stationFolder);
        writeLines(lineFolder.resolve("line_clean.shp"));
        writeStations(stationFolder.resolve("station_clean.shp"));

        MatsimConfig config = new MatsimConfig();
        setField(config, "folder", dataRoot.toString());
        RealDataServiceImpl service = new RealDataServiceImpl();
        setField(service, "matsimConfig", config);

        Map<String, Object> data = service.busLineStation("广州", null);
        Map<String, Object> lineProperties = properties(mapList(mapValue(data.get("lines")).get("features")).get(0));
        assertNotNull(lineProperties.get("len_km"));
        assertNotNull(lineProperties.get("directness"));
        assertEquals(2, ((Number) lineProperties.get("stop_count")).intValue());
        assertNotNull(lineProperties.get("avg_stop_m"));

        RealDataExportVO csv = service.exportVersion("广州", null, "line", "csv");
        String csvText = new String(csv.content(), StandardCharsets.UTF_8);
        List<String> csvFields = csvHeader(csvText);
        assertTrue(csvFields.containsAll(List.of("len_km", "directness", "stop_count", "avg_stop_m")));

        RealDataExportVO shp = service.exportVersion("广州", null, "line", "shp");
        Path extractedLineShp = extractFirstShp(shp.content(), tempDir.resolve("line-export"));
        assertEquals(csvFields, shpAttributeFields(extractedLineShp));
        assertNotNull(shpFirstAttribute(extractedLineShp, "directness"));
    }

    @Test
    void uploadedLineExtraFieldsAreAcceptedAndPersistedIntoVersionShp() throws Exception {
        Path dataRoot = tempDir.resolve("pt_data");
        Path realDataRoot = dataRoot.resolve("广州").resolve(MatsimConfig.REAL_DATA_FOLDER);
        Path lineFolder = realDataRoot.resolve("公交线路站点").resolve("线路");
        Path stationFolder = realDataRoot.resolve("公交线路站点").resolve("站点");
        Files.createDirectories(lineFolder);
        Files.createDirectories(stationFolder);
        writeLines(lineFolder.resolve("line_clean.shp"));
        writeStations(stationFolder.resolve("station_clean.shp"));

        MatsimConfig config = new MatsimConfig();
        setField(config, "folder", dataRoot.toString());
        RealDataServiceImpl service = new RealDataServiceImpl();
        setField(service, "matsimConfig", config);

        Path uploadFolder = tempDir.resolve("line-upload");
        Files.createDirectories(uploadFolder);
        writeLinesWithExtraField(uploadFolder.resolve("line_clean.shp"));
        List<MultipartFile> uploadFiles = new ArrayList<>();
        try (var paths = Files.list(uploadFolder)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                uploadFiles.add(new MockMultipartFile(
                        "files",
                        path.getFileName().toString(),
                        "application/octet-stream",
                        Files.readAllBytes(path)
                ));
            }
        }

        Map<String, Object> comparison = service.compareUpload("tester", "广州", "line", uploadFiles);
        List<Map<String, Object>> operations = mapList(comparison.get("operations"));
        assertEquals(1, operations.size());

        RealDataCommitParam param = new RealDataCommitParam();
        param.setAreaName("广州");
        param.setDatasetType("line");
        param.setMessage("保留线路扩展字段");
        param.setOperations(operations);
        String versionId = String.valueOf(commit(service, "tester", param).get("versionId"));
        Path versionLineShp = realDataRoot.resolve("_versions").resolve(versionId)
                .resolve("公交线路站点").resolve("线路").resolve("line_clean.shp");

        assertTrue(shpAttributeFields(versionLineShp).contains("remark"));
        assertEquals("extra-value", shpFirstAttribute(versionLineShp, "remark"));
    }

    @Test
    void uploadedStationSequenceChangesAreUpdatesInsteadOfAddDeletePairs() throws Exception {
        Path dataRoot = tempDir.resolve("pt_data");
        Path realDataRoot = prepareStandardData(dataRoot);
        RealDataServiceImpl service = serviceFor(dataRoot);

        Path uploadFolder = tempDir.resolve("station-sequence-upload");
        Files.createDirectories(uploadFolder);
        writeStationsWithSequences(uploadFolder.resolve("station_clean.shp"), "2", "1");

        Map<String, Object> comparison = service.compareUpload(
                "tester",
                "广州",
                "station",
                uploadFiles(uploadFolder)
        );
        List<Map<String, Object>> operations = mapList(comparison.get("operations"));

        assertEquals(2, operations.size());
        assertTrue(operations.stream().allMatch(operation -> "replace_station_from_shp".equals(operation.get("type"))));
        assertTrue(operations.stream().allMatch(operation -> mapListValue(operation.get("changedFields")).equals(List.of("seq"))));
        assertTrue(operations.stream().noneMatch(operation -> String.valueOf(operation.get("type")).startsWith("add_")));
        assertTrue(operations.stream().noneMatch(operation -> String.valueOf(operation.get("type")).startsWith("delete_")));

        RealDataCommitParam param = new RealDataCommitParam();
        param.setAreaName("广州");
        param.setDatasetType("station");
        param.setMessage("上传调整站序");
        param.setOperations(operations);
        commit(service, "tester", param);

        List<Map<String, Object>> routeStops = mapList(mapValue(service.busLineStation("广州", null).get("routeStops")).get("features"));
        Map<String, Object> first = routeStops.stream().map(RealDataServiceImplCommitEditsTest::properties)
                .filter(item -> "S1".equals(item.get("stop_id"))).findFirst().orElseThrow();
        Map<String, Object> second = routeStops.stream().map(RealDataServiceImplCommitEditsTest::properties)
                .filter(item -> "S2".equals(item.get("stop_id"))).findFirst().orElseThrow();
        assertEquals("2", String.valueOf(first.get("seq")));
        assertEquals("1", String.valueOf(second.get("seq")));
        assertTrue(Files.isDirectory(realDataRoot.resolve("_versions")));
    }

    @Test
    void uploadedLinePreservesManuallyChangedFieldsAndGeometry() throws Exception {
        Path dataRoot = tempDir.resolve("pt_data");
        prepareStandardData(dataRoot);
        RealDataServiceImpl service = serviceFor(dataRoot);

        Map<String, Object> manualOperation = new LinkedHashMap<>();
        manualOperation.put("operationId", "manual-line-fields");
        manualOperation.put("datasetType", "line");
        manualOperation.put("type", "replace_line_from_table");
        manualOperation.put("targetId", "440100017000|0|440100017000");
        manualOperation.put("title", "728路");
        manualOperation.put("detail", "属性表修改：发车间隔、线路走向");
        manualOperation.put("changedFields", List.of("interval", "geometry"));
        manualOperation.put("payload", Map.of(
                "targetId", "440100017000|0|440100017000",
                "changedFields", List.of("interval", "geometry"),
                "feature", lineFeature(
                        "line-manual",
                        "8",
                        "728路",
                        new Coordinate(113.1, 23.1),
                        new Coordinate(113.25, 23.25)
                )
        ));
        RealDataCommitParam manualCommit = new RealDataCommitParam();
        manualCommit.setAreaName("广州");
        manualCommit.setDatasetType("line");
        manualCommit.setMessage("人工调整线路走向与间隔");
        manualCommit.setOperations(List.of(manualOperation));
        commit(service, "tester", manualCommit);

        Path uploadFolder = tempDir.resolve("line-smart-merge-upload");
        Files.createDirectories(uploadFolder);
        writeLine(
                uploadFolder.resolve("line_clean.shp"),
                "20",
                "728路新版",
                new Coordinate(113.1, 23.1),
                new Coordinate(113.3, 23.3)
        );

        Map<String, Object> comparison = service.compareUpload(
                "tester",
                "广州",
                "line",
                uploadFiles(uploadFolder)
        );
        List<Map<String, Object>> operations = mapList(comparison.get("operations"));
        assertEquals(1, operations.size());
        Map<String, Object> operation = operations.get(0);
        assertEquals(List.of("geometry", "interval"), mapListValue(operation.get("protectedFields")));
        assertEquals(List.of("name"), mapListValue(operation.get("changedFields")));
        Map<String, Object> mergedFeature = mapValue(mapValue(operation.get("payload")).get("feature"));
        assertEquals("8", properties(mergedFeature).get("interval"));
        assertEquals("728路新版", properties(mergedFeature).get("name"));
        assertEquals(
                List.of(List.of(List.of(113.1, 23.1), List.of(113.25, 23.25))),
                mapValue(mergedFeature.get("geometry")).get("coordinates")
        );

        RealDataCommitParam uploadCommit = new RealDataCommitParam();
        uploadCommit.setAreaName("广州");
        uploadCommit.setDatasetType("line");
        uploadCommit.setMessage("智能合并线路更新");
        uploadCommit.setOperations(operations);
        commit(service, "tester", uploadCommit);

        Map<String, Object> savedLine = mapList(mapValue(service.busLineStation("广州", null).get("lines")).get("features")).get(0);
        assertEquals("8", properties(savedLine).get("interval"));
        assertEquals("728路新版", properties(savedLine).get("name"));
        assertEquals(
                List.of(List.of(List.of(113.1, 23.1), List.of(113.25, 23.25))),
                mapValue(savedLine.get("geometry")).get("coordinates")
        );
    }

    @Test
    void shpDeletionCandidatesRequireExplicitConfirmation() throws Exception {
        Path dataRoot = tempDir.resolve("pt_data");
        prepareStandardData(dataRoot);
        RealDataServiceImpl service = serviceFor(dataRoot);

        Path uploadFolder = tempDir.resolve("empty-line-upload");
        Files.createDirectories(uploadFolder);
        writeEmptyLines(uploadFolder.resolve("line_clean.shp"));
        List<Map<String, Object>> operations = mapList(service.compareUpload(
                "tester",
                "广州",
                "line",
                uploadFiles(uploadFolder)
        ).get("operations"));

        assertEquals(1, operations.size());
        assertEquals(Boolean.TRUE, operations.get(0).get("candidateDeletion"));
        RealDataCommitParam commit = new RealDataCommitParam();
        commit.setAreaName("广州");
        commit.setDatasetType("line");
        commit.setMessage("确认删除缺失线路");
        commit.setOperations(operations);
        assertThrows(BusinessException.class, () -> commit(service, "tester", commit));

        operations.get(0).put("deletionConfirmed", true);
        commit(service, "tester", commit);
        assertEquals(0, mapValue(service.busLineStation("广州", null).get("lines")).get("featureCount"));
    }

    @Test
    void manuallyDeletedFeatureIsNotReintroducedByLaterUpload() throws Exception {
        Path dataRoot = tempDir.resolve("pt_data");
        prepareStandardData(dataRoot);
        RealDataServiceImpl service = serviceFor(dataRoot);

        Map<String, Object> delete = new LinkedHashMap<>();
        delete.put("operationId", "manual-delete-line");
        delete.put("datasetType", "line");
        delete.put("type", "delete_line_from_table");
        delete.put("targetId", "440100017000|0|440100017000");
        delete.put("title", "728路");
        delete.put("detail", "属性表删除整行");
        delete.put("changedFields", List.of("__deletion__"));
        delete.put("payload", Map.of("targetId", "440100017000|0|440100017000"));
        RealDataCommitParam deleteCommit = new RealDataCommitParam();
        deleteCommit.setAreaName("广州");
        deleteCommit.setDatasetType("line");
        deleteCommit.setMessage("人工删除线路");
        deleteCommit.setOperations(List.of(delete));
        commit(service, "tester", deleteCommit);

        Path uploadFolder = tempDir.resolve("restore-deleted-line-upload");
        Files.createDirectories(uploadFolder);
        writeLines(uploadFolder.resolve("line_clean.shp"));
        Map<String, Object> comparison = service.compareUpload(
                "tester",
                "广州",
                "line",
                uploadFiles(uploadFolder)
        );

        assertTrue(mapList(comparison.get("operations")).isEmpty());
        assertEquals(1, ((Number) comparison.get("skippedByManualDeletionCount")).intValue());
        assertEquals(0, mapValue(service.busLineStation("广州", null).get("lines")).get("featureCount"));
    }

    @Test
    void routeStationReorderOnlyChangesRequestedDirection() throws Exception {
        Path dataRoot = tempDir.resolve("pt_data");
        Path realDataRoot = prepareStandardData(dataRoot);
        writeBidirectionalStations(realDataRoot.resolve("公交线路站点").resolve("站点").resolve("station_clean.shp"));
        RealDataServiceImpl service = serviceFor(dataRoot);

        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operationId", "reorder-down-direction");
        operation.put("datasetType", "station");
        operation.put("type", "reorder_line_stations");
        operation.put("targetId", "440100017000");
        operation.put("changedFields", List.of("seq"));
        operation.put("payload", Map.of(
                "lineId", "440100017000",
                "dir", "1",
                "stationScope", "route",
                "changes", List.of(Map.of(
                        "targetId", "station.down.1",
                        "stopId", "S1",
                        "dir", "1",
                        "fromSeq", "1",
                        "toSeq", "9"
                ))
        ));
        commit(service, "tester", stationCommitParam("调整下行站序", operation));

        List<Map<String, Object>> stops = mapList(mapValue(service.busLineStation("广州", null).get("routeStops")).get("features"));
        assertEquals("1", sequenceOf(stops, "0", "S1"));
        assertEquals("9", sequenceOf(stops, "1", "S1"));
    }

    @Test
    void routeScopedStationDeleteDoesNotRemoveOtherDirections() throws Exception {
        Path dataRoot = tempDir.resolve("pt_data");
        Path realDataRoot = prepareStandardData(dataRoot);
        writeBidirectionalStations(realDataRoot.resolve("公交线路站点").resolve("站点").resolve("station_clean.shp"));
        RealDataServiceImpl service = serviceFor(dataRoot);
        List<Map<String, Object>> original = mapList(mapValue(service.busLineStation("广州", null).get("routeStops")).get("features"));
        Map<String, Object> downStop = original.stream()
                .filter(feature -> "1".equals(String.valueOf(properties(feature).get("dir"))))
                .filter(feature -> "S1".equals(properties(feature).get("stop_id")))
                .findFirst()
                .orElseThrow();

        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operationId", "delete-down-stop");
        operation.put("datasetType", "station");
        operation.put("type", "delete_station_from_table");
        operation.put("targetId", downStop.get("id"));
        operation.put("changedFields", List.of("__deletion__"));
        operation.put("payload", Map.of(
                "targetId", downStop.get("id"),
                "stationScope", "route",
                "feature", downStop
        ));
        commit(service, "tester", stationCommitParam("仅移除下行站点", operation));

        List<Map<String, Object>> stops = mapList(mapValue(service.busLineStation("广州", null).get("routeStops")).get("features"));
        assertTrue(stops.stream().anyMatch(feature ->
                "0".equals(String.valueOf(properties(feature).get("dir")))
                        && "S1".equals(properties(feature).get("stop_id"))));
        assertTrue(stops.stream().noneMatch(feature ->
                "1".equals(String.valueOf(properties(feature).get("dir")))
                        && "S1".equals(properties(feature).get("stop_id"))));
    }

    @Test
    void manuallyAddedStationIsNotOfferedAsShpDeletion() throws Exception {
        Path dataRoot = tempDir.resolve("pt_data");
        prepareStandardData(dataRoot);
        RealDataServiceImpl service = serviceFor(dataRoot);
        commit(service, "tester", stationCommitParam("人工新增站点", addStationOperation()));

        Path uploadFolder = tempDir.resolve("original-stations-upload");
        Files.createDirectories(uploadFolder);
        writeStations(uploadFolder.resolve("station_clean.shp"));
        Map<String, Object> comparison = service.compareUpload(
                "tester",
                "广州",
                "station",
                uploadFiles(uploadFolder)
        );

        assertTrue(mapList(comparison.get("operations")).isEmpty());
        assertEquals(1, ((Number) comparison.get("protectedFeatureCount")).intValue());
    }

    @Test
    void shpComparisonRejectsTamperedOrStaleOperations() throws Exception {
        Path dataRoot = tempDir.resolve("pt_data");
        prepareStandardData(dataRoot);
        RealDataServiceImpl service = serviceFor(dataRoot);
        Path uploadFolder = tempDir.resolve("tampered-line-upload");
        Files.createDirectories(uploadFolder);
        writeLine(
                uploadFolder.resolve("line_clean.shp"),
                "20",
                "728路新版",
                new Coordinate(113.1, 23.1),
                new Coordinate(113.3, 23.3)
        );

        List<Map<String, Object>> tamperedOperations = mapList(service.compareUpload(
                "tester", "广州", "line", uploadFiles(uploadFolder)
        ).get("operations"));
        tamperedOperations.get(0).put("title", "被篡改");
        RealDataCommitParam tamperedCommit = new RealDataCommitParam();
        tamperedCommit.setAreaName("广州");
        tamperedCommit.setDatasetType("line");
        tamperedCommit.setMessage("提交篡改结果");
        tamperedCommit.setOperations(tamperedOperations);
        assertThrows(BusinessException.class, () -> commit(service, "tester", tamperedCommit));

        List<Map<String, Object>> staleOperations = mapList(service.compareUpload(
                "tester", "广州", "line", uploadFiles(uploadFolder)
        ).get("operations"));
        commit(service, "tester", lineCommitParam("人工更新发车间隔", updateLineHeadwayOperation()));
        RealDataCommitParam staleCommit = lineCommitParam("提交过期比对", staleOperations.get(0));
        assertThrows(BusinessException.class, () -> commit(service, "tester", staleCommit));
    }

    @Test
    void manuallyChangedBusinessKeyStillMatchesOriginalShpFeature() throws Exception {
        Path dataRoot = tempDir.resolve("pt_data");
        prepareStandardData(dataRoot);
        RealDataServiceImpl service = serviceFor(dataRoot);
        Map<String, Object> renamedKeyFeature = lineFeature(
                "manual-line-key",
                "15",
                "728路",
                new Coordinate(113.1, 23.1),
                new Coordinate(113.2, 23.2)
        );
        properties(renamedKeyFeature).put("line_id", "MANUAL-LINE");
        properties(renamedKeyFeature).put("route_id", "MANUAL-LINE");
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operationId", "manual-line-key-change");
        operation.put("datasetType", "line");
        operation.put("type", "replace_line_from_table");
        operation.put("targetId", "440100017000|0|440100017000");
        operation.put("changedFields", List.of("line_id", "route_id"));
        operation.put("payload", Map.of(
                "targetId", "440100017000|0|440100017000",
                "changedFields", List.of("line_id", "route_id"),
                "feature", renamedKeyFeature
        ));
        commit(service, "tester", lineCommitParam("人工修改线路主键", operation));

        Path uploadFolder = tempDir.resolve("original-line-after-key-change");
        Files.createDirectories(uploadFolder);
        writeLines(uploadFolder.resolve("different_file_name.shp"));
        Map<String, Object> comparison = service.compareUpload(
                "tester", "广州", "line", uploadFiles(uploadFolder)
        );

        assertTrue(mapList(comparison.get("operations")).isEmpty());
        assertEquals(1, ((Number) comparison.get("protectedFeatureCount")).intValue());
        Map<String, Object> saved = mapList(mapValue(service.busLineStation("广州", null).get("lines")).get("features")).get(0);
        assertEquals("MANUAL-LINE", properties(saved).get("line_id"));
    }

    @Test
    void busLineStationReadsSwitchedActiveVersionInsteadOfLatestCommit() throws Exception {
        Path dataRoot = tempDir.resolve("pt_data");
        Path realDataRoot = dataRoot.resolve("广州").resolve(MatsimConfig.REAL_DATA_FOLDER);
        Path lineFolder = realDataRoot.resolve("公交线路站点").resolve("线路");
        Path stationFolder = realDataRoot.resolve("公交线路站点").resolve("站点");
        Files.createDirectories(lineFolder);
        Files.createDirectories(stationFolder);
        writeLines(lineFolder.resolve("line_clean.shp"));
        writeStations(stationFolder.resolve("station_clean.shp"));

        MatsimConfig config = new MatsimConfig();
        setField(config, "folder", dataRoot.toString());
        RealDataServiceImpl service = new RealDataServiceImpl();
        setField(service, "matsimConfig", config);

        Map<String, Object> firstCommit = commit(service, "tester", stationCommitParam("第一次", addStationOperation()));
        String firstVersionId = String.valueOf(firstCommit.get("versionId"));
        Map<String, Object> secondCommit = commit(service, "tester", stationCommitParam("第二次",
                addStationOperation("add-second-station", "new-stop-2", "新增站二", "S4", "4", 113.4, 23.4)));

        RealDataParam revertParam = new RealDataParam();
        revertParam.setAreaName("广州");
        revertParam.setVersionId(firstVersionId);
        revertParam.setBaseRevision(((Number) secondCommit.get("revision")).longValue());
        service.revertEdits("tester", revertParam);

        Map<String, Object> active = service.busLineStation("广州", null);
        Map<String, Object> routeStops = mapValue(active.get("routeStops"));
        List<String> names = mapList(routeStops.get("features")).stream()
                .map(RealDataServiceImplCommitEditsTest::properties)
                .map(properties -> String.valueOf(properties.get("stop_name")))
                .toList();
        assertTrue(names.contains("新增站"));
        assertTrue(!names.contains("新增站二"));
        assertEquals(firstVersionId, mapValue(active.get("history")).get("activeVersionId"));
    }

    @Test
    void uniqueStationFormatSynthesizesRouteStopsAndStaysInSyncAfterEdits() throws Exception {
        Path dataRoot = tempDir.resolve("pt_data");
        Path realDataRoot = dataRoot.resolve("广州").resolve(MatsimConfig.REAL_DATA_FOLDER);
        Path lineFolder = realDataRoot.resolve("公交线路站点").resolve("线路");
        Path stationFolder = realDataRoot.resolve("公交线路站点").resolve("站点");
        Files.createDirectories(lineFolder);
        Files.createDirectories(stationFolder);
        writeLines(lineFolder.resolve("line_clean.shp"));
        writeUniqueStations(stationFolder.resolve("stops.shp"));
        writeSequenceCsv(stationFolder.resolve("line_stop_sequence.csv"));

        MatsimConfig config = new MatsimConfig();
        setField(config, "folder", dataRoot.toString());
        RealDataServiceImpl service = new RealDataServiceImpl();
        setField(service, "matsimConfig", config);

        // 读取：占位集合由 SHP + CSV 合成，站点集合为物理唯一站台
        Map<String, Object> data = service.busLineStation("广州", null);
        Map<String, Object> routeStops = mapValue(data.get("routeStops"));
        List<Map<String, Object>> features = mapList(routeStops.get("features"));
        assertEquals(3, features.size());
        Map<String, Object> first = properties(features.get(0));
        assertEquals("440100017000", first.get("line_id"));
        assertEquals("PF00001", first.get("stop_id"));
        assertEquals("站点一", first.get("stop_name"));
        Map<String, Object> stations = mapValue(data.get("stations"));
        assertEquals(2, stations.get("featureCount"));

        // 编辑：物理站点属性表按 stop_id 更新全部线路占位，同时保留各自线路与站序。
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operationId", "replace-unique-station");
        operation.put("datasetType", "station");
        operation.put("type", "replace_station_from_table");
        operation.put("targetId", "PF00001");
        operation.put("title", "更新站点一");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetId", "PF00001");
        payload.put("feature", physicalStationFeature("PF00001", "站点一（更新）", 113.15, 23.15));
        operation.put("payload", payload);

        Map<String, Object> commitResult = commit(service, "tester", stationCommitParam("更新物理站点", operation));
        String versionId = String.valueOf(commitResult.get("versionId"));
        Path versionStationFolder = realDataRoot.resolve("_versions").resolve(versionId)
                .resolve("公交线路站点").resolve("站点");

        List<String> stopIds = shpAttributeValues(versionStationFolder.resolve("stops.shp"), "stop_id");
        assertEquals(2, stopIds.size());
        assertTrue(stopIds.contains("PF00001"));
        String csv = Files.readString(versionStationFolder.resolve("line_stop_sequence.csv"), StandardCharsets.UTF_8);
        assertTrue(csv.contains("line_id,dir,seq,stop_id"));
        assertTrue(csv.contains("440100017000,1,1,PF00001"));
        assertTrue(csv.contains("113.15"));
        assertTrue(csv.contains("站点一（更新）"));
        assertTrue(csv.contains("440100017000,1"));
        assertTrue(csv.contains("PF00002"));

        Map<String, Object> latest = service.busLineStation("广州", null);
        List<Map<String, Object>> latestStops = mapList(mapValue(latest.get("routeStops")).get("features"));
        assertEquals(3, latestStops.size());
        List<Map<String, Object>> updatedStops = latestStops.stream()
                .map(RealDataServiceImplCommitEditsTest::properties)
                .filter(props -> "PF00001".equals(props.get("stop_id")))
                .toList();
        assertEquals(2, updatedStops.size());
        assertTrue(updatedStops.stream().allMatch(props -> "站点一（更新）".equals(props.get("stop_name"))));
        assertTrue(updatedStops.stream().allMatch(props -> String.valueOf(props.get("lon")).startsWith("113.15")));
        assertTrue(updatedStops.stream().anyMatch(props -> "440100017000".equals(props.get("line_id"))));
        assertTrue(updatedStops.stream().anyMatch(props -> "0".equals(props.get("dir"))));
        assertTrue(updatedStops.stream().anyMatch(props -> "1".equals(props.get("dir"))));
    }

    private static Map<String, Object> addStationOperation() {
        return addStationOperation("add-test-station", "new-stop", "新增站", "S3", "3", 113.3, 23.3);
    }

    private static Map<String, Object> updateLineHeadwayOperation() {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operationId", "update-test-line-headway");
        operation.put("datasetType", "line");
        operation.put("type", "update_line_headway");
        operation.put("targetId", "440100017000");
        operation.put("title", "728路");
        operation.put("detail", "发车间隔改为：8");
        operation.put("payload", Map.of("headway", "8"));
        return operation;
    }

    private static Map<String, Object> addStationOperation(String operationId, String featureId, String name, String stopId, String sequence, double lng, double lat) {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operationId", operationId);
        operation.put("datasetType", "station");
        operation.put("type", "add_station_from_table");
        operation.put("targetId", "728|" + stopId + "|" + sequence);
        operation.put("title", name);
        operation.put("detail", "属性表新增整行");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("feature", stationFeature(featureId, name, stopId, sequence, lng, lat));
        operation.put("payload", payload);
        return operation;
    }

    private static RealDataCommitParam stationCommitParam(String message, Map<String, Object> operation) {
        RealDataCommitParam param = new RealDataCommitParam();
        param.setAreaName("广州");
        param.setDatasetType("station");
        param.setMessage(message);
        param.setOperations(List.of(operation));
        return param;
    }

    private static RealDataCommitParam lineCommitParam(String message, Map<String, Object> operation) {
        RealDataCommitParam param = new RealDataCommitParam();
        param.setAreaName("广州");
        param.setDatasetType("line");
        param.setMessage(message);
        param.setOperations(List.of(operation));
        return param;
    }

    private static Map<String, Object> evidenceImage() {
        Map<String, Object> image = new LinkedHashMap<>();
        image.put("id", "evidence-1");
        image.put("name", "现场照片.jpg");
        image.put("type", "image/jpeg");
        image.put("size", 128L);
        image.put("width", 2);
        image.put("height", 2);
        image.put("dataUrl", "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQAAAQABAAD/2w==");
        return image;
    }

    private static Map<String, Object> addDepotOperation() {
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("operationId", "add-test-depot");
        operation.put("datasetType", "depot");
        operation.put("type", "add_depot");
        operation.put("title", "新增场站");
        operation.put("detail", "新增场站");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", "新增场站");
        payload.put("lng", 113.4);
        payload.put("lat", 23.4);
        operation.put("payload", payload);
        return operation;
    }

    private static void writeLines(Path shpPath) throws Exception {
        SimpleFeatureType schema = schema("line_clean", LineString.class, List.of(
                "line_id", "dir", "route_id", "first", "last",
                "interval", "mode", "name", "price", "company"
        ));
        SimpleFeatureBuilder builder = new SimpleFeatureBuilder(schema);
        builder.add(GEOMETRY_FACTORY.createLineString(new Coordinate[]{
                new Coordinate(113.1, 23.1),
                new Coordinate(113.2, 23.2)
        }));
        for (String value : List.of(
                "440100017000", "0", "440100017000", "06:00:00", "22:00:00",
                "15", "bus", "728路", "2", "公交公司"
        )) {
            builder.add(value);
        }
        writeShp(shpPath, schema, List.of(builder.buildFeature("line.1")));
    }

    private static void writeLinesWithExtraField(Path shpPath) throws Exception {
        SimpleFeatureType schema = schema("line_clean", LineString.class, List.of(
                "line_id", "dir", "route_id", "first", "last",
                "interval", "mode", "name", "price", "company", "remark"
        ));
        SimpleFeatureBuilder builder = new SimpleFeatureBuilder(schema);
        builder.add(GEOMETRY_FACTORY.createLineString(new Coordinate[]{
                new Coordinate(113.1, 23.1),
                new Coordinate(113.2, 23.2)
        }));
        for (String value : List.of(
                "440100017000", "0", "440100017000", "06:00:00", "22:00:00",
                "15", "bus", "728路", "2", "公交公司", "extra-value"
        )) {
            builder.add(value);
        }
        writeShp(shpPath, schema, List.of(builder.buildFeature("line.1")));
    }

    private static void writeStations(Path shpPath) throws Exception {
        SimpleFeatureType schema = schema("station_clean", Point.class, List.of(
                "line_id", "dir", "stop_id", "stop_name", "seq", "lon", "lat"
        ));
        writeShp(shpPath, schema, List.of(
                stationSimpleFeature(schema, "station.1", "站点一", "S1", "1", 113.1, 23.1),
                stationSimpleFeature(schema, "station.2", "站点二", "S2", "2", 113.2, 23.2)
        ));
    }

    private static void writeUniqueStations(Path shpPath) throws Exception {
        SimpleFeatureType schema = schema("stops", Point.class, List.of(
                "stop_id", "stop_name", "lon", "lat", "n_lines"
        ));
        writeShp(shpPath, schema, List.of(
                uniqueStationSimpleFeature(schema, "stops.1", "PF00001", "站点一", 113.1, 23.1),
                uniqueStationSimpleFeature(schema, "stops.2", "PF00002", "站点二", 113.2, 23.2)
        ));
    }

    private static SimpleFeature uniqueStationSimpleFeature(SimpleFeatureType schema, String id, String stopId, String name, double lng, double lat) {
        SimpleFeatureBuilder builder = new SimpleFeatureBuilder(schema);
        builder.add(GEOMETRY_FACTORY.createPoint(new Coordinate(lng, lat)));
        for (String value : List.of(stopId, name, String.valueOf(lng), String.valueOf(lat), "1")) {
            builder.add(value);
        }
        return builder.buildFeature(id);
    }

    private static void writeSequenceCsv(Path csvPath) throws Exception {
        Files.writeString(csvPath, "﻿line_id,dir,seq,stop_id,stop_name,lon,lat\n"
                + "440100017000,0,1,PF00001,站点一,113.1,23.1\n"
                + "440100017000,1,1,PF00001,站点一,113.1,23.1\n"
                + "440100017000,0,2,PF00002,站点二,113.2,23.2\n", StandardCharsets.UTF_8);
    }

    private Path prepareStandardData(Path dataRoot) throws Exception {
        Path realDataRoot = dataRoot.resolve("广州").resolve(MatsimConfig.REAL_DATA_FOLDER);
        Path lineFolder = realDataRoot.resolve("公交线路站点").resolve("线路");
        Path stationFolder = realDataRoot.resolve("公交线路站点").resolve("站点");
        Files.createDirectories(lineFolder);
        Files.createDirectories(stationFolder);
        writeLines(lineFolder.resolve("line_clean.shp"));
        writeStations(stationFolder.resolve("station_clean.shp"));
        return realDataRoot;
    }

    private static RealDataServiceImpl serviceFor(Path dataRoot) throws Exception {
        MatsimConfig config = new MatsimConfig();
        setField(config, "folder", dataRoot.toString());
        RealDataServiceImpl service = new RealDataServiceImpl();
        setField(service, "matsimConfig", config);
        return service;
    }

    private static Map<String, Object> commit(
            RealDataServiceImpl service,
            String username,
            RealDataCommitParam param
    ) {
        Map<String, Object> history = service.history(param.getAreaName());
        param.setBaseRevision(((Number) history.getOrDefault("revision", 0)).longValue());
        param.setBaseVersionId(String.valueOf(history.getOrDefault("activeVersionId", "__base__")));
        return service.commitEdits(username, param);
    }

    private static List<MultipartFile> uploadFiles(Path folder) throws Exception {
        List<MultipartFile> uploadFiles = new ArrayList<>();
        try (var paths = Files.list(folder)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                uploadFiles.add(new MockMultipartFile(
                        "files",
                        path.getFileName().toString(),
                        "application/octet-stream",
                        Files.readAllBytes(path)
                ));
            }
        }
        return uploadFiles;
    }

    private static void writeStationsWithSequences(Path shpPath, String firstSequence, String secondSequence) throws Exception {
        SimpleFeatureType schema = schema("station_clean", Point.class, List.of(
                "line_id", "dir", "stop_id", "stop_name", "seq", "lon", "lat"
        ));
        writeShp(shpPath, schema, List.of(
                stationSimpleFeature(schema, "station.1", "站点一", "S1", firstSequence, 113.1, 23.1),
                stationSimpleFeature(schema, "station.2", "站点二", "S2", secondSequence, 113.2, 23.2)
        ));
    }

    private static void writeBidirectionalStations(Path shpPath) throws Exception {
        SimpleFeatureType schema = schema("station_clean", Point.class, List.of(
                "line_id", "dir", "stop_id", "stop_name", "seq", "lon", "lat"
        ));
        writeShp(shpPath, schema, List.of(
                stationSimpleFeature(schema, "station.up.1", "0", "站点一", "S1", "1", 113.1, 23.1),
                stationSimpleFeature(schema, "station.up.2", "0", "站点二", "S2", "2", 113.2, 23.2),
                stationSimpleFeature(schema, "station.down.1", "1", "站点一", "S1", "1", 113.1, 23.1),
                stationSimpleFeature(schema, "station.down.2", "1", "站点二", "S2", "2", 113.2, 23.2)
        ));
    }

    private static void writeEmptyLines(Path shpPath) throws Exception {
        SimpleFeatureType schema = schema("line_clean", LineString.class, List.of(
                "line_id", "dir", "route_id", "first", "last",
                "interval", "mode", "name", "price", "company"
        ));
        writeShp(shpPath, schema, List.of());
    }

    private static void writeLine(Path shpPath, String interval, String name, Coordinate... coordinates) throws Exception {
        SimpleFeatureType schema = schema("line_clean", LineString.class, List.of(
                "line_id", "dir", "route_id", "first", "last",
                "interval", "mode", "name", "price", "company"
        ));
        SimpleFeatureBuilder builder = new SimpleFeatureBuilder(schema);
        builder.add(GEOMETRY_FACTORY.createLineString(coordinates));
        for (String value : List.of(
                "440100017000", "0", "440100017000", "06:00:00", "22:00:00",
                interval, "bus", name, "2", "公交公司"
        )) {
            builder.add(value);
        }
        writeShp(shpPath, schema, List.of(builder.buildFeature("line.1")));
    }

    private static Map<String, Object> lineFeature(String id, String interval, String name, Coordinate... coordinates) {
        Map<String, Object> feature = new LinkedHashMap<>();
        feature.put("type", "Feature");
        feature.put("id", id);
        feature.put("geometry", Map.of(
                "type", "LineString",
                "coordinates", java.util.Arrays.stream(coordinates)
                        .map(coordinate -> List.of(coordinate.x, coordinate.y))
                        .toList()
        ));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("line_id", "440100017000");
        properties.put("dir", "0");
        properties.put("route_id", "440100017000");
        properties.put("first", "06:00:00");
        properties.put("last", "22:00:00");
        properties.put("interval", interval);
        properties.put("mode", "bus");
        properties.put("name", name);
        properties.put("price", "2");
        properties.put("company", "公交公司");
        feature.put("properties", properties);
        return feature;
    }

    private static List<String> mapListValue(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static void writeDepots(Path shpPath) throws Exception {
        SimpleFeatureType schema = schema("depot_clean", Point.class, List.of("depot_name", "depot_id"));
        SimpleFeatureBuilder builder = new SimpleFeatureBuilder(schema);
        builder.add(GEOMETRY_FACTORY.createPoint(new Coordinate(113.0, 23.0)));
        builder.add("原始场站");
        builder.add("D1");
        writeShp(shpPath, schema, List.of(builder.buildFeature("depot.1")));
    }

    private static SimpleFeature stationSimpleFeature(SimpleFeatureType schema, String id, String name, String stopId, String sequence, double lng, double lat) {
        return stationSimpleFeature(schema, id, "0", name, stopId, sequence, lng, lat);
    }

    private static SimpleFeature stationSimpleFeature(
            SimpleFeatureType schema,
            String id,
            String direction,
            String name,
            String stopId,
            String sequence,
            double lng,
            double lat
    ) {
        SimpleFeatureBuilder builder = new SimpleFeatureBuilder(schema);
        builder.add(GEOMETRY_FACTORY.createPoint(new Coordinate(lng, lat)));
        for (String value : List.of("440100017000", direction, stopId, name, sequence, String.valueOf(lng), String.valueOf(lat))) {
            builder.add(value);
        }
        return builder.buildFeature(id);
    }

    private static String sequenceOf(List<Map<String, Object>> features, String direction, String stopId) {
        return features.stream()
                .map(RealDataServiceImplCommitEditsTest::properties)
                .filter(properties -> direction.equals(String.valueOf(properties.get("dir"))))
                .filter(properties -> stopId.equals(properties.get("stop_id")))
                .map(properties -> String.valueOf(properties.get("seq")))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, Object> stationFeature(String id, String name, String stopId, String sequence, double lng, double lat) {
        Map<String, Object> feature = new LinkedHashMap<>();
        feature.put("type", "Feature");
        feature.put("id", id);
        feature.put("geometry", Map.of("type", "Point", "coordinates", List.of(lng, lat)));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("line_id", "440100017000");
        properties.put("dir", "0");
        properties.put("stop_id", stopId);
        properties.put("stop_name", name);
        properties.put("seq", sequence);
        properties.put("lon", lng);
        properties.put("lat", lat);
        feature.put("properties", properties);
        return feature;
    }

    private static Map<String, Object> physicalStationFeature(String stopId, String name, double lng, double lat) {
        Map<String, Object> feature = new LinkedHashMap<>();
        feature.put("type", "Feature");
        feature.put("id", stopId);
        feature.put("geometry", Map.of("type", "Point", "coordinates", List.of(lng, lat)));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("_featureId", stopId);
        properties.put("_stationKey", stopId);
        properties.put("stop_id", stopId);
        properties.put("stop_name", name);
        properties.put("lon", lng);
        properties.put("lat", lat);
        feature.put("properties", properties);
        return feature;
    }

    private static SimpleFeatureType schema(String typeName, Class<?> geometryType, List<String> fields) {
        SimpleFeatureTypeBuilder builder = new SimpleFeatureTypeBuilder();
        builder.setName(typeName);
        builder.add("the_geom", geometryType);
        fields.forEach(field -> builder.add(field, String.class));
        return builder.buildFeatureType();
    }

    private static void writeShp(Path shpPath, SimpleFeatureType schema, List<SimpleFeature> features) throws Exception {
        ShapefileDataStoreFactory factory = new ShapefileDataStoreFactory();
        Map<String, Serializable> params = new LinkedHashMap<>();
        params.put("url", shpPath.toUri().toURL());
        params.put("create spatial index", Boolean.TRUE);
        ShapefileDataStore dataStore = (ShapefileDataStore) factory.createNewDataStore(params);
        dataStore.setCharset(StandardCharsets.UTF_8);
        dataStore.createSchema(schema);
        Transaction transaction = new DefaultTransaction("create-test-shp");
        try {
            SimpleFeatureStore store = (SimpleFeatureStore) dataStore.getFeatureSource(dataStore.getTypeNames()[0]);
            DefaultFeatureCollection collection = new DefaultFeatureCollection(null, schema);
            features.forEach(collection::add);
            store.setTransaction(transaction);
            store.addFeatures(collection);
            transaction.commit();
        } finally {
            transaction.close();
            dataStore.dispose();
        }
        String fileName = shpPath.getFileName().toString();
        String baseName = fileName.substring(0, fileName.length() - ".shp".length());
        Files.writeString(shpPath.resolveSibling(baseName + ".cpg"), "UTF-8", StandardCharsets.UTF_8);
    }

    private static List<String> stationNames(Path shpPath) throws Exception {
        return shpAttributeValues(shpPath, "stop_name");
    }

    private static List<String> shpAttributeValues(Path shpPath, String attribute) throws Exception {
        ShapefileDataStore dataStore = new ShapefileDataStore(shpPath.toUri().toURL());
        dataStore.setCharset(StandardCharsets.UTF_8);
        List<String> names = new ArrayList<>();
        try (SimpleFeatureIterator iterator = dataStore.getFeatureSource(dataStore.getTypeNames()[0]).getFeatures().features()) {
            while (iterator.hasNext()) {
                names.add(String.valueOf(iterator.next().getAttribute(attribute)));
            }
        } finally {
            dataStore.dispose();
        }
        return names;
    }

    private static List<String> depotNames(Path shpPath) throws Exception {
        ShapefileDataStore dataStore = new ShapefileDataStore(shpPath.toUri().toURL());
        dataStore.setCharset(StandardCharsets.UTF_8);
        List<String> names = new ArrayList<>();
        try (SimpleFeatureIterator iterator = dataStore.getFeatureSource(dataStore.getTypeNames()[0]).getFeatures().features()) {
            while (iterator.hasNext()) {
                names.add(String.valueOf(iterator.next().getAttribute("depot_name")));
            }
        } finally {
            dataStore.dispose();
        }
        return names;
    }

    private static Set<String> zipEntryNames(byte[] content) throws Exception {
        Set<String> names = new LinkedHashSet<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private static Path extractFirstShp(byte[] content, Path targetFolder) throws Exception {
        Files.createDirectories(targetFolder);
        Path shpPath = null;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(content), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                Path output = targetFolder.resolve(entry.getName()).normalize();
                if (!output.startsWith(targetFolder)) {
                    throw new IllegalArgumentException("invalid zip entry");
                }
                Files.createDirectories(output.getParent());
                Files.copy(input, output);
                if (output.getFileName().toString().endsWith(".shp")) {
                    shpPath = output;
                }
            }
        }
        return shpPath;
    }

    private static List<String> csvHeader(String csvText) {
        String header = csvText.startsWith("\uFEFF") ? csvText.substring(1) : csvText;
        return List.of(header.lines().findFirst().orElseThrow().split(",", -1));
    }

    private static List<String> shpAttributeFields(Path shpPath) throws Exception {
        assertNotNull(shpPath);
        ShapefileDataStore dataStore = new ShapefileDataStore(shpPath.toUri().toURL());
        try {
            SimpleFeatureType schema = dataStore.getSchema(dataStore.getTypeNames()[0]);
            return schema.getAttributeDescriptors().stream()
                    .filter(descriptor -> schema.getGeometryDescriptor() == null
                            || !descriptor.getName().equals(schema.getGeometryDescriptor().getName()))
                    .map(descriptor -> descriptor.getName().getLocalPart())
                    .toList();
        } finally {
            dataStore.dispose();
        }
    }

    private static Object shpFirstAttribute(Path shpPath, String attribute) throws Exception {
        ShapefileDataStore dataStore = new ShapefileDataStore(shpPath.toUri().toURL());
        try (SimpleFeatureIterator iterator = dataStore.getFeatureSource(dataStore.getTypeNames()[0]).getFeatures().features()) {
            return iterator.hasNext() ? iterator.next().getAttribute(attribute) : null;
        } finally {
            dataStore.dispose();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> feature) {
        return (Map<String, Object>) feature.get("properties");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
