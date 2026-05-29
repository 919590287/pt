package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.api.model.params.RealDataCommitParam;
import com.jts.gjcxfzksh.api.model.params.RealDataParam;
import com.jts.gjcxfzksh.config.MatsimConfig;
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

import java.io.Serializable;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        Map<String, Object> commitResult = service.commitEdits("tester", param);
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
                .filter(properties -> "新增站".equals(properties.get("name_cn")))
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

        Map<String, Object> commitResult = service.commitEdits("tester", param);
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

        Map<String, Object> firstCommit = service.commitEdits("tester", stationCommitParam("第一次", addStationOperation()));
        String firstVersionId = String.valueOf(firstCommit.get("versionId"));
        Map<String, Object> secondCommit = service.commitEdits("tester", stationCommitParam("第二次",
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
                .map(properties -> String.valueOf(properties.get("name_cn")))
                .toList();
        assertTrue(names.contains("新增站"));
        assertTrue(!names.contains("新增站二"));
        assertEquals(firstVersionId, mapValue(active.get("history")).get("activeVersionId"));
    }

    private static Map<String, Object> addStationOperation() {
        return addStationOperation("add-test-station", "new-stop", "新增站", "S3", "3", 113.3, 23.3);
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
                "route_cn", "route_en", "city_code", "route_type", "company_cn", "company_en",
                "s_stop_cn", "s_stop_en", "e_stop_cn", "e_stop_en", "distance", "total_stop",
                "start_time", "end_time", "loop", "status", "basic_prc", "total_prc",
                "city_cn", "city_en", "type_en", "length", "interval"
        ));
        SimpleFeatureBuilder builder = new SimpleFeatureBuilder(schema);
        builder.add(GEOMETRY_FACTORY.createLineString(new Coordinate[]{
                new Coordinate(113.1, 23.1),
                new Coordinate(113.2, 23.2)
        }));
        for (String value : List.of(
                "728路", "Bus No. 728", "020", "bus", "公交公司", "Bus Company",
                "首站", "Start", "末站", "End", "1000", "2", "06:00", "22:00",
                "0", "1", "2", "2", "广州", "Guangzhou", "bus", "1000", "10"
        )) {
            builder.add(value);
        }
        writeShp(shpPath, schema, List.of(builder.buildFeature("line.1")));
    }

    private static void writeStations(Path shpPath) throws Exception {
        SimpleFeatureType schema = schema("station_clean", Point.class, List.of(
                "name_cn", "name_en", "stop_id", "route_cn", "route_en", "route_id",
                "city_code", "city_cn", "city_en", "sequence"
        ));
        writeShp(shpPath, schema, List.of(
                stationSimpleFeature(schema, "station.1", "站点一", "S1", "1", 113.1, 23.1),
                stationSimpleFeature(schema, "station.2", "站点二", "S2", "2", 113.2, 23.2)
        ));
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
        SimpleFeatureBuilder builder = new SimpleFeatureBuilder(schema);
        builder.add(GEOMETRY_FACTORY.createPoint(new Coordinate(lng, lat)));
        for (String value : List.of(name, "", stopId, "728路", "Bus No. 728", "440100017000", "020", "广州", "Guangzhou", sequence)) {
            builder.add(value);
        }
        return builder.buildFeature(id);
    }

    private static Map<String, Object> stationFeature(String id, String name, String stopId, String sequence, double lng, double lat) {
        Map<String, Object> feature = new LinkedHashMap<>();
        feature.put("type", "Feature");
        feature.put("id", id);
        feature.put("geometry", Map.of("type", "Point", "coordinates", List.of(lng, lat)));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name_cn", name);
        properties.put("name_en", "");
        properties.put("stop_id", stopId);
        properties.put("route_cn", "728路");
        properties.put("route_en", "Bus No. 728");
        properties.put("route_id", "440100017000");
        properties.put("city_code", "020");
        properties.put("city_cn", "广州");
        properties.put("city_en", "Guangzhou");
        properties.put("sequence", sequence);
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
    }

    private static List<String> stationNames(Path shpPath) throws Exception {
        ShapefileDataStore dataStore = new ShapefileDataStore(shpPath.toUri().toURL());
        dataStore.setCharset(StandardCharsets.UTF_8);
        List<String> names = new ArrayList<>();
        try (SimpleFeatureIterator iterator = dataStore.getFeatureSource(dataStore.getTypeNames()[0]).getFeatures().features()) {
            while (iterator.hasNext()) {
                names.add(String.valueOf(iterator.next().getAttribute("name_cn")));
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
