package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.api.model.params.BuildingQueryParam;
import com.jts.gjcxfzksh.api.model.vo.BuildingTileVO;
import com.jts.gjcxfzksh.api.service.BuildingService;
import com.jts.gjcxfzksh.data.cache.MatsimAnalysisCache;
import com.jts.gjcxfzksh.exception.BusinessException;
import org.geotools.api.data.Query;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.filter.Filter;
import org.geotools.api.filter.FilterFactory;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.factory.CommonFactoryFinder;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Service
public class BuildingServiceImpl implements BuildingService {

    private static final String DEFAULT_SHP_PATH =
            "/Users/a../数据/四维路网数据/可视化数据20251128/建筑物-旧v2/Buildingguagnzhou84.shp";
    private static final String DEFAULT_HEIGHT_FIELD = "HEIGHT";
    private static final int DEFAULT_MAX_FEATURES = 20000;
    private static final double EARTH_RADIUS = 6378137.0;

    @Override
    public BuildingTileVO query(BuildingQueryParam param) {
        String shpPath = valueOrDefault(param.getShpPath(), DEFAULT_SHP_PATH);
        String heightField = valueOrDefault(param.getHeightField(), DEFAULT_HEIGHT_FIELD);
        int maxFeatures = param.getMaxFeatures() > 0 ? param.getMaxFeatures() : DEFAULT_MAX_FEATURES;

        double minX = Math.min(param.getMinX(), param.getMaxX());
        double maxX = Math.max(param.getMinX(), param.getMaxX());
        double minY = Math.min(param.getMinY(), param.getMaxY());
        double maxY = Math.max(param.getMinY(), param.getMaxY());
        double centerX = (minX + maxX) * 0.5;
        double centerY = (minY + maxY) * 0.5;

        BuildingTileVO result = new BuildingTileVO();
        result.setCenter(new double[]{centerX, centerY});
        result.setBounds(new double[]{minX, minY, maxX, maxY});
        result.setHeightField(heightField);

        if (MatsimAnalysisCache.isTrajectoryBuildActive()) {
            result.setTruncated(true);
            return result;
        }

        File shpFile = new File(shpPath);
        if (!shpFile.isFile()) {
            throw new BusinessException("建筑物 shp 文件不存在: " + shpPath);
        }

        double[] wgs84Bbox = mercatorBboxToWgs84(minX, minY, maxX, maxY);

        ShapefileDataStore dataStore = null;
        try {
            dataStore = new ShapefileDataStore(shpFile.toURI().toURL());
            dataStore.setCharset(StandardCharsets.UTF_8);

            String typeName = dataStore.getTypeNames()[0];
            SimpleFeatureSource source = dataStore.getFeatureSource(typeName);
            String geometryName = source.getSchema().getGeometryDescriptor().getLocalName();
            String resolvedHeightField = resolveAttributeName(source, heightField);
            result.setHeightField(resolvedHeightField);

            FilterFactory filterFactory = CommonFactoryFinder.getFilterFactory();
            Filter filter = filterFactory.bbox(
                    geometryName,
                    wgs84Bbox[0],
                    wgs84Bbox[1],
                    wgs84Bbox[2],
                    wgs84Bbox[3],
                    "EPSG:4326"
            );

            Query query = new Query(typeName, filter, geometryName, resolvedHeightField);
            query.setMaxFeatures(maxFeatures + 1);

            SimpleFeatureCollection collection = source.getFeatures(query);
            try (SimpleFeatureIterator iterator = collection.features()) {
                while (iterator.hasNext()) {
                    if (result.getFeatureCount() >= maxFeatures) {
                        result.setTruncated(true);
                        break;
                    }

                    SimpleFeature feature = iterator.next();
                    Object geometryValue = feature.getDefaultGeometry();
                    if (!(geometryValue instanceof Geometry geometry) || geometry.isEmpty()) continue;

                    double height = parseHeight(feature.getAttribute(resolvedHeightField));
                    appendGeometry(result, geometry, height, centerX, centerY, maxFeatures);
                }
            }
            return result;
        } catch (MalformedURLException error) {
            throw new BusinessException("建筑物 shp 路径无效: " + shpPath, error);
        } catch (Exception error) {
            throw new BusinessException("读取建筑物 shp 失败: " + error.getMessage(), error);
        } finally {
            if (dataStore != null) {
                dataStore.dispose();
            }
        }
    }

    private static void appendGeometry(BuildingTileVO result, Geometry geometry, double height, double centerX, double centerY, int maxFeatures) {
        if (result.getFeatureCount() >= maxFeatures) {
            result.setTruncated(true);
            return;
        }

        if (geometry instanceof Polygon polygon) {
            BuildingTileVO.BuildingVO building = polygonToBuilding(polygon, height, centerX, centerY);
            if (building != null) {
                result.getBuildings().add(building);
                result.setFeatureCount(result.getFeatureCount() + 1);
            }
            return;
        }

        if (geometry instanceof MultiPolygon multiPolygon) {
            for (int i = 0; i < multiPolygon.getNumGeometries(); i++) {
                if (result.getFeatureCount() >= maxFeatures) {
                    result.setTruncated(true);
                    break;
                }
                Geometry child = multiPolygon.getGeometryN(i);
                if (child instanceof Polygon polygon) {
                    BuildingTileVO.BuildingVO building = polygonToBuilding(polygon, height, centerX, centerY);
                    if (building != null) {
                        result.getBuildings().add(building);
                        result.setFeatureCount(result.getFeatureCount() + 1);
                    }
                }
            }
        }
    }

    private static BuildingTileVO.BuildingVO polygonToBuilding(Polygon polygon, double height, double centerX, double centerY) {
        double[] shell = ringToLocalMercator(polygon.getExteriorRing(), centerX, centerY);
        if (shell.length < 6) return null;

        BuildingTileVO.BuildingVO building = new BuildingTileVO.BuildingVO();
        building.setHeight(height);
        building.getRings().add(shell);

        for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
            double[] hole = ringToLocalMercator(polygon.getInteriorRingN(i), centerX, centerY);
            if (hole.length >= 6) {
                building.getRings().add(hole);
            }
        }

        return building;
    }

    private static double[] ringToLocalMercator(LineString ring, double centerX, double centerY) {
        Coordinate[] coordinates = ring.getCoordinates();
        if (coordinates.length < 4) return new double[0];

        int size = coordinates.length;
        Coordinate first = coordinates[0];
        Coordinate last = coordinates[size - 1];
        if (first.x == last.x && first.y == last.y) {
            size -= 1;
        }

        if (size < 3) return new double[0];

        double[] values = new double[size * 2];
        for (int i = 0; i < size; i++) {
            double[] xy = wgs84ToMercator(coordinates[i].x, coordinates[i].y);
            values[i * 2] = round2(xy[0] - centerX);
            values[i * 2 + 1] = round2(xy[1] - centerY);
        }
        return values;
    }

    private static String resolveAttributeName(SimpleFeatureSource source, String requestedName) {
        String fallback = DEFAULT_HEIGHT_FIELD;
        String requestedUpper = requestedName.toUpperCase(Locale.ROOT);
        for (String attributeName : source.getSchema().getAttributeDescriptors().stream().map(item -> item.getLocalName()).toList()) {
            String attributeUpper = attributeName.toUpperCase(Locale.ROOT);
            if (attributeUpper.equals(requestedUpper)) return attributeName;
            if (attributeUpper.equals(DEFAULT_HEIGHT_FIELD)) fallback = attributeName;
        }
        return fallback;
    }

    private static double parseHeight(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Number number) {
            return Math.max(0.0, number.doubleValue());
        }
        String text = value.toString().trim();
        if (text.isEmpty()) return 0.0;
        try {
            return Math.max(0.0, Double.parseDouble(text));
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static double[] mercatorBboxToWgs84(double minX, double minY, double maxX, double maxY) {
        double[] bottomLeft = mercatorToWgs84(minX, minY);
        double[] topRight = mercatorToWgs84(maxX, maxY);
        return new double[]{
                Math.min(bottomLeft[0], topRight[0]),
                Math.min(bottomLeft[1], topRight[1]),
                Math.max(bottomLeft[0], topRight[0]),
                Math.max(bottomLeft[1], topRight[1])
        };
    }

    private static double[] mercatorToWgs84(double x, double y) {
        double lon = Math.toDegrees(x / EARTH_RADIUS);
        double lat = Math.toDegrees(2 * Math.atan(Math.exp(y / EARTH_RADIUS)) - Math.PI / 2);
        return new double[]{lon, lat};
    }

    private static double[] wgs84ToMercator(double lon, double lat) {
        double x = EARTH_RADIUS * Math.toRadians(lon);
        double limitedLat = Math.max(-85.05112878, Math.min(85.05112878, lat));
        double y = EARTH_RADIUS * Math.log(Math.tan(Math.PI / 4 + Math.toRadians(limitedLat) / 2));
        return new double[]{x, y};
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

}
