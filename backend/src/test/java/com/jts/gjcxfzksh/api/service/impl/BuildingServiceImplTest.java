package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.api.model.params.BuildingQueryParam;
import com.jts.gjcxfzksh.api.model.vo.BuildingTileVO;
import org.geotools.api.data.SimpleFeatureStore;
import org.geotools.api.data.Transaction;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

import java.io.File;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildingServiceImplTest {

    private static final double EARTH_RADIUS = 6378137.0;
    private static final double BASE_LON = 113.30;
    private static final double BASE_LAT = 23.10;
    private static final int GRID = 40; // 40x40 = 1600 栋普通建筑
    private static final double STEP_DEG = 0.0008;
    private static final double SIZE_DEG = 0.0002;    // 普通建筑 ~22m 见方
    private static final double TOWER_SIZE_DEG = 0.0008; // 地标 ~89m 见方
    private static final double TOWER_HEIGHT = 150.0;
    private static final int TOWER_COUNT = 5;

    @TempDir
    static Path tempDir;

    private static String shpPath;

    @BeforeAll
    static void buildSyntheticShapefile() throws Exception {
        File shpFile = tempDir.resolve("buildings-test.shp").toFile();
        shpPath = shpFile.getAbsolutePath();

        SimpleFeatureTypeBuilder typeBuilder = new SimpleFeatureTypeBuilder();
        typeBuilder.setName("buildings");
        typeBuilder.setCRS(DefaultGeographicCRS.WGS84);
        typeBuilder.add("the_geom", Polygon.class);
        typeBuilder.add("HEIGHT", Double.class);
        SimpleFeatureType featureType = typeBuilder.buildFeatureType();

        Map<String, Serializable> params = new HashMap<>();
        params.put("url", shpFile.toURI().toURL());
        ShapefileDataStore store = (ShapefileDataStore) new ShapefileDataStoreFactory().createNewDataStore(params);
        store.createSchema(featureType);

        GeometryFactory geometryFactory = new GeometryFactory();
        DefaultFeatureCollection collection = new DefaultFeatureCollection();
        SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(store.getSchema(store.getTypeNames()[0]));

        // 均匀网格的普通建筑
        for (int i = 0; i < GRID; i++) {
            for (int j = 0; j < GRID; j++) {
                double lon = BASE_LON + i * STEP_DEG;
                double lat = BASE_LAT + j * STEP_DEG;
                featureBuilder.add(square(geometryFactory, lon, lat, SIZE_DEG));
                featureBuilder.add(10.0 + (i + j) % 30);
                collection.add(featureBuilder.buildFeature(null));
            }
        }
        // 分散的高大地标建筑
        for (int t = 0; t < TOWER_COUNT; t++) {
            double lon = BASE_LON + (5 + t * 7) * STEP_DEG + STEP_DEG * 0.2;
            double lat = BASE_LAT + (3 + t * 8) * STEP_DEG + STEP_DEG * 0.2;
            featureBuilder.add(square(geometryFactory, lon, lat, TOWER_SIZE_DEG));
            featureBuilder.add(TOWER_HEIGHT);
            collection.add(featureBuilder.buildFeature(null));
        }

        SimpleFeatureStore featureStore = (SimpleFeatureStore) store.getFeatureSource(store.getTypeNames()[0]);
        try (Transaction transaction = new DefaultTransaction("create")) {
            featureStore.setTransaction(transaction);
            featureStore.addFeatures(collection);
            transaction.commit();
        }
        store.dispose();
    }

    private static Polygon square(GeometryFactory factory, double lon, double lat, double size) {
        return factory.createPolygon(new Coordinate[]{
                new Coordinate(lon, lat),
                new Coordinate(lon + size, lat),
                new Coordinate(lon + size, lat + size),
                new Coordinate(lon, lat + size),
                new Coordinate(lon, lat),
        });
    }

    private static double[] lngLatToMercator(double lon, double lat) {
        double x = EARTH_RADIUS * Math.toRadians(lon);
        double y = EARTH_RADIUS * Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2));
        return new double[]{x, y};
    }

    private static BuildingQueryParam fullExtentParam(int maxFeatures) {
        double[] min = lngLatToMercator(BASE_LON - 0.002, BASE_LAT - 0.002);
        double[] max = lngLatToMercator(BASE_LON + GRID * STEP_DEG + 0.002, BASE_LAT + GRID * STEP_DEG + 0.002);
        BuildingQueryParam param = new BuildingQueryParam();
        param.setMinX(min[0]);
        param.setMinY(min[1]);
        param.setMaxX(max[0]);
        param.setMaxY(max[1]);
        param.setMaxFeatures(maxFeatures);
        param.setShpPath(shpPath);
        param.setHeightField("HEIGHT");
        return param;
    }

    /** 把返回的相对坐标环还原为建筑质心（Web 墨卡托）。 */
    private static double[] centerOf(BuildingTileVO result, BuildingTileVO.BuildingVO building) {
        double[] ring = building.getRings().get(0);
        double sx = 0;
        double sy = 0;
        int n = ring.length / 2;
        for (int k = 0; k < n; k++) {
            sx += ring[k * 2];
            sy += ring[k * 2 + 1];
        }
        return new double[]{result.getCenter()[0] + sx / n, result.getCenter()[1] + sy / n};
    }

    @Test
    void returnsAllBuildingsWhenUnderLimit() {
        BuildingServiceImpl service = new BuildingServiceImpl();
        BuildingTileVO result = service.query(fullExtentParam(5000));
        assertEquals(GRID * GRID + TOWER_COUNT, result.getFeatureCount());
        assertFalse(result.isTruncated());
        assertFalse(result.isCulled());
    }

    @Test
    void truncationCoversWholeViewportEvenly() {
        BuildingServiceImpl service = new BuildingServiceImpl();
        int maxFeatures = 200;
        BuildingTileVO result = service.query(fullExtentParam(maxFeatures));
        assertTrue(result.isTruncated());
        assertEquals(maxFeatures, result.getFeatureCount());

        // 视野四个象限都应分到建筑，而不是配额聚在一处
        double[] bounds = result.getBounds();
        double midX = (bounds[0] + bounds[2]) / 2;
        double midY = (bounds[1] + bounds[3]) / 2;
        int[] quadrants = new int[4];
        for (BuildingTileVO.BuildingVO building : result.getBuildings()) {
            double[] center = centerOf(result, building);
            int q = (center[0] >= midX ? 1 : 0) + (center[1] >= midY ? 2 : 0);
            quadrants[q]++;
        }
        for (int q = 0; q < 4; q++) {
            assertTrue(quadrants[q] >= maxFeatures / 8,
                    "象限 " + q + " 建筑过少: " + quadrants[q] + "/" + maxFeatures);
        }
    }

    @Test
    void truncationKeepsLandmarkBuildings() {
        BuildingServiceImpl service = new BuildingServiceImpl();
        // 预算需 >= 网格格数（真实配置 20000 对 400 格），每格至少轮到一次
        BuildingTileVO result = service.query(fullExtentParam(600));
        assertTrue(result.isTruncated());
        long towers = result.getBuildings().stream()
                .filter(building -> building.getHeight() == TOWER_HEIGHT)
                .count();
        assertEquals(TOWER_COUNT, towers, "截断时高大地标建筑必须优先保留");
    }

    @Test
    void focusBiasesQuotaTowardsViewpoint() {
        BuildingServiceImpl service = new BuildingServiceImpl();
        int maxFeatures = 200;
        BuildingQueryParam param = fullExtentParam(maxFeatures);
        // 焦点放在左下角：模拟 3D 俯仰下"离相机最近的地面点"
        double[] focus = lngLatToMercator(BASE_LON, BASE_LAT);
        param.setFocusX(focus[0]);
        param.setFocusY(focus[1]);

        BuildingTileVO result = service.query(param);
        assertTrue(result.isTruncated());

        double[] bounds = result.getBounds();
        double midX = (bounds[0] + bounds[2]) / 2;
        double midY = (bounds[1] + bounds[3]) / 2;
        int nearFocus = 0;
        int farCorner = 0;
        for (BuildingTileVO.BuildingVO building : result.getBuildings()) {
            double[] center = centerOf(result, building);
            if (center[0] < midX && center[1] < midY) nearFocus++;
            if (center[0] >= midX && center[1] >= midY) farCorner++;
        }
        assertTrue(nearFocus > farCorner, "配额应向视点一侧倾斜: near=" + nearFocus + " far=" + farCorner);
    }

    @Test
    void lowZoomCullsSubPixelBuildings() {
        BuildingServiceImpl service = new BuildingServiceImpl();
        BuildingQueryParam param = fullExtentParam(5000);
        param.setZoom(10); // 该级别下 ~22m 建筑不足 0.7 像素，应被剔除；~89m 地标保留
        BuildingTileVO result = service.query(param);
        assertTrue(result.isCulled());
        assertFalse(result.isTruncated());
        assertEquals(TOWER_COUNT, result.getFeatureCount());
        for (BuildingTileVO.BuildingVO building : result.getBuildings()) {
            assertEquals(TOWER_HEIGHT, building.getHeight());
        }
    }

    @Test
    void highZoomKeepsEverything() {
        BuildingServiceImpl service = new BuildingServiceImpl();
        BuildingQueryParam param = fullExtentParam(5000);
        param.setZoom(17);
        BuildingTileVO result = service.query(param);
        assertFalse(result.isCulled());
        assertEquals(GRID * GRID + TOWER_COUNT, result.getFeatureCount());
    }
}
