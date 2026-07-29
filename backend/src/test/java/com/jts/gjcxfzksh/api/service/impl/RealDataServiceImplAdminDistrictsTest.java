package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.config.MatsimConfig;
import org.geotools.api.data.SimpleFeatureStore;
import org.geotools.api.data.Transaction;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 行政区列表/边界统一读取「行政区范围」目录内唯一 SHP（广州为
 * scripts/build_admin_district_shp.py 从街道面融合生成的产物），不再运行时融合。
 */
class RealDataServiceImplAdminDistrictsTest {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    @TempDir
    Path tempDir;

    @Test
    @SuppressWarnings("unchecked")
    void adminDistrictsReadsSingleShapefileFromAdminAreaFolder() throws Exception {
        Path dataRoot = tempDir.resolve("pt_data");
        Path adminFolder = dataRoot.resolve("广州市").resolve(MatsimConfig.REAL_DATA_FOLDER).resolve("行政区范围");
        Files.createDirectories(adminFolder);
        writeDistricts(adminFolder.resolve("行政区划_街道融合.shp"), List.of("越秀区", "海珠区"));

        MatsimConfig config = new MatsimConfig();
        setField(config, "folder", dataRoot.toString());
        RealDataServiceImpl service = new RealDataServiceImpl();
        setField(service, "matsimConfig", config);

        Map<String, Object> result = service.adminDistricts("广州市");

        assertEquals(List.of("越秀区", "海珠区"), result.get("districts"));
        Map<String, Object> collection = (Map<String, Object>) result.get("collection");
        assertEquals("FeatureCollection", collection.get("type"));
        List<Map<String, Object>> features = (List<Map<String, Object>>) collection.get("features");
        assertEquals(2, features.size());
        Map<String, Object> properties = (Map<String, Object>) features.get(0).get("properties");
        assertEquals("越秀区", properties.get("_districtName"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void guangzhouRealShpYieldsElevenDistrictsInCanonicalOrder() throws Exception {
        Path dataRoot = Path.of("/Volumes/USB DISK/pt_data");
        assumeTrue(Files.isDirectory(dataRoot.resolve("广州市")), "数据盘未插入，跳过真实 SHP 校验");

        MatsimConfig config = new MatsimConfig();
        setField(config, "folder", dataRoot.toString());
        RealDataServiceImpl service = new RealDataServiceImpl();
        setField(service, "matsimConfig", config);

        Map<String, Object> result = service.adminDistricts("广州市");

        assertEquals(List.of(
                "越秀区", "海珠区", "荔湾区", "天河区", "白云区", "黄埔区",
                "番禺区", "花都区", "南沙区", "从化区", "增城区"
        ), result.get("districts"));
        Map<String, Object> collection = (Map<String, Object>) result.get("collection");
        assertEquals(11, ((List<Map<String, Object>>) collection.get("features")).size());
    }

    private static void writeDistricts(Path shpPath, List<String> names) throws Exception {
        SimpleFeatureTypeBuilder typeBuilder = new SimpleFeatureTypeBuilder();
        typeBuilder.setName("districts");
        typeBuilder.add("the_geom", MultiPolygon.class);
        typeBuilder.add("Name", String.class);
        SimpleFeatureType schema = typeBuilder.buildFeatureType();

        ShapefileDataStoreFactory factory = new ShapefileDataStoreFactory();
        Map<String, Serializable> params = new LinkedHashMap<>();
        params.put("url", shpPath.toUri().toURL());
        ShapefileDataStore dataStore = (ShapefileDataStore) factory.createNewDataStore(params);
        dataStore.setCharset(StandardCharsets.UTF_8);
        dataStore.createSchema(schema);
        Transaction transaction = new DefaultTransaction("create-admin-district-shp");
        try {
            SimpleFeatureStore store = (SimpleFeatureStore) dataStore.getFeatureSource(dataStore.getTypeNames()[0]);
            DefaultFeatureCollection collection = new DefaultFeatureCollection(null, schema);
            SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(schema);
            for (int index = 0; index < names.size(); index++) {
                double offset = index * 2.0;
                featureBuilder.add(square(113.0 + offset, 23.0));
                featureBuilder.add(names.get(index));
                collection.add(featureBuilder.buildFeature("district-" + index));
            }
            store.setTransaction(transaction);
            store.addFeatures(collection);
            transaction.commit();
        } finally {
            transaction.close();
            dataStore.dispose();
        }
        String baseName = shpPath.getFileName().toString().replaceFirst("\\.shp$", "");
        Files.writeString(shpPath.resolveSibling(baseName + ".cpg"), "UTF-8", StandardCharsets.UTF_8);
    }

    private static MultiPolygon square(double lon, double lat) {
        Polygon polygon = GEOMETRY_FACTORY.createPolygon(new Coordinate[]{
                new Coordinate(lon, lat),
                new Coordinate(lon + 1, lat),
                new Coordinate(lon + 1, lat + 1),
                new Coordinate(lon, lat + 1),
                new Coordinate(lon, lat),
        });
        return GEOMETRY_FACTORY.createMultiPolygon(new Polygon[]{polygon});
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
