package com.jts.gjcxfzksh.api.service.impl;

import org.geotools.api.data.SimpleFeatureStore;
import org.geotools.api.data.Transaction;
import org.geotools.api.feature.simple.SimpleFeature;
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
import org.locationtech.jts.geom.Point;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RealPassengerFlowDepotMetricsTest {

    @TempDir
    Path tempDir;

    @Test
    void sumsOnlyPositiveF004LandAreaFromRealDepotShapefile() throws Exception {
        Path folder = tempDir.resolve("公交场站");
        Files.createDirectories(folder);
        Path shapefile = folder.resolve("depots.shp");
        SimpleFeatureTypeBuilder typeBuilder = new SimpleFeatureTypeBuilder();
        typeBuilder.setName("depots");
        typeBuilder.add("the_geom", Point.class);
        typeBuilder.add("F004", String.class);
        SimpleFeatureType schema = typeBuilder.buildFeatureType();
        GeometryFactory geometryFactory = new GeometryFactory();
        List<SimpleFeature> features = List.of(
                feature(schema, geometryFactory, "18946"),
                feature(schema, geometryFactory, "8000"),
                feature(schema, geometryFactory, "/"));
        writeShapefile(shapefile, schema, features);

        assertEquals(26946.0,
                RealPassengerFlowServiceImpl.depotLandAreaSquareMeters(folder));
    }

    private static SimpleFeature feature(SimpleFeatureType schema,
                                         GeometryFactory geometryFactory,
                                         String area) {
        SimpleFeatureBuilder builder = new SimpleFeatureBuilder(schema);
        builder.add(geometryFactory.createPoint(new Coordinate(113.5, 22.8)));
        builder.add(area);
        return builder.buildFeature(null);
    }

    private static void writeShapefile(Path path,
                                       SimpleFeatureType schema,
                                       List<SimpleFeature> features) throws Exception {
        ShapefileDataStoreFactory factory = new ShapefileDataStoreFactory();
        Map<String, Serializable> params = new LinkedHashMap<>();
        params.put("url", path.toUri().toURL());
        params.put("create spatial index", Boolean.TRUE);
        ShapefileDataStore dataStore =
                (ShapefileDataStore) factory.createNewDataStore(params);
        dataStore.setCharset(StandardCharsets.UTF_8);
        dataStore.createSchema(schema);
        Transaction transaction = new DefaultTransaction("depot-area-test");
        try {
            SimpleFeatureStore store = (SimpleFeatureStore) dataStore.getFeatureSource(
                    dataStore.getTypeNames()[0]);
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
}
