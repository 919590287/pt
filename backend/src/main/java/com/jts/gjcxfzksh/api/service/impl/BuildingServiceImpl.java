package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.api.model.params.BuildingQueryParam;
import com.jts.gjcxfzksh.api.model.vo.BuildingTileVO;
import com.jts.gjcxfzksh.api.service.BuildingService;
import com.jts.gjcxfzksh.exception.BusinessException;
import jakarta.annotation.PreDestroy;
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
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class BuildingServiceImpl implements BuildingService {

    private static final String DEFAULT_SHP_PATH =
            "/Users/a../数据/四维路网数据/可视化数据20251128/建筑物-旧v2/Buildingguagnzhou84.shp";
    private static final String DEFAULT_HEIGHT_FIELD = "HEIGHT";
    private static final int DEFAULT_MAX_FEATURES = 20000;
    private static final double EARTH_RADIUS = 6378137.0;
    private final ConcurrentMap<String, ShapefileDataStore> dataStores = new ConcurrentHashMap<>();

    @PreDestroy
    void closeDataStores() {
        dataStores.values().forEach(ShapefileDataStore::dispose);
        dataStores.clear();
    }

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

        File shpFile = new File(shpPath);
        if (!shpFile.isFile()) {
            throw new BusinessException("建筑物 shp 文件不存在: " + shpPath);
        }

        double[] wgs84Bbox = mercatorBboxToWgs84(minX, minY, maxX, maxY);

        try {
            // ShapefileDataStore 的创建会重复读取 shp/dbf/shx 元数据。建筑请求随相机高频触发，
            // 服务生命周期内复用只读 store；每次 query 仍创建独立 iterator，可并发读取。
            ShapefileDataStore dataStore = dataStores.computeIfAbsent(
                    shpFile.getAbsolutePath(),
                    ignored -> openDataStore(shpFile)
            );

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

            // 命中数超过 maxFeatures 时不能按 shp 文件顺序取前 N 条（3D 俯仰的大 bbox 下
            // 那 N 条会整体落在视野某一角）；也不能简单保留"离视点最近的 N 条"（宽视野
            // 下配额聚成锚点附近一个圆盘，其余区域全空，圆盘还随相机角度移动）。做法：
            //   1. 像素级剔除——按请求 zoom 丢掉屏幕上不足 1 像素的建筑（本就不可见）；
            //   2. 网格分层配额——把 bbox 划成格子，轮询各格按"占地 x 高度"的显著度取
            //      最显眼的建筑，直到吃满配额。全视野均匀覆盖，先保留城市天际线。
            double[] focus = resolveFocusWgs84(param, wgs84Bbox);
            double lonScale = Math.cos(Math.toRadians(focus[1]));
            double cullMercatorMeters = pixelCullMercatorMeters(param.getZoom());

            // 扫描量上限：极端超大 bbox 时兜底，避免单请求读取成本无界
            int scanLimit = 500_000;
            Query query = new Query(typeName, filter, geometryName, resolvedHeightField);
            query.setMaxFeatures(scanLimit + 1);

            List<BuildingCandidate> candidates = new ArrayList<>();
            boolean culledAny = false;
            SimpleFeatureCollection collection = source.getFeatures(query);
            int scanned = 0;
            try (SimpleFeatureIterator iterator = collection.features()) {
                while (iterator.hasNext()) {
                    SimpleFeature feature = iterator.next();
                    if (scanned >= scanLimit) {
                        result.setTruncated(true);
                        break;
                    }
                    scanned++;

                    Object geometryValue = feature.getDefaultGeometry();
                    if (!(geometryValue instanceof Geometry geometry) || geometry.isEmpty()) continue;

                    double height = parseHeight(feature.getAttribute(resolvedHeightField));
                    culledAny |= collectPolygons(candidates, geometry, height, cullMercatorMeters, lonScale);
                }
            }
            result.setCulled(culledAny);

            for (BuildingCandidate candidate : selectForViewport(candidates, maxFeatures, wgs84Bbox, focus, lonScale, result)) {
                BuildingTileVO.BuildingVO building =
                        polygonToBuilding(candidate.polygon(), candidate.height(), centerX, centerY);
                if (building != null) {
                    result.getBuildings().add(building);
                    result.setFeatureCount(result.getFeatureCount() + 1);
                }
            }
            return result;
        } catch (Exception error) {
            throw new BusinessException("读取建筑物 shp 失败: " + error.getMessage(), error);
        }
    }

    private static ShapefileDataStore openDataStore(File shpFile) {
        try {
            ShapefileDataStore dataStore = new ShapefileDataStore(shpFile.toURI().toURL());
            dataStore.setCharset(StandardCharsets.UTF_8);
            return dataStore;
        } catch (MalformedURLException error) {
            throw new BusinessException("建筑物 shp 路径无效: " + shpFile, error);
        }
    }

    private record BuildingCandidate(Polygon polygon, double height, double cx, double cy, double importance) {
    }

    /** 请求携带 focus（视点最近地面点）时以其为配额分配的起点，否则退回 bbox 中心。 */
    private static double[] resolveFocusWgs84(BuildingQueryParam param, double[] wgs84Bbox) {
        double lon = (wgs84Bbox[0] + wgs84Bbox[2]) * 0.5;
        double lat = (wgs84Bbox[1] + wgs84Bbox[3]) * 0.5;
        if (param.getFocusX() != null && param.getFocusY() != null) {
            double[] focus = mercatorToWgs84(param.getFocusX(), param.getFocusY());
            lon = Math.max(wgs84Bbox[0], Math.min(wgs84Bbox[2], focus[0]));
            lat = Math.max(wgs84Bbox[1], Math.min(wgs84Bbox[3], focus[1]));
        }
        return new double[]{lon, lat};
    }

    /** 该缩放级别下屏幕约 0.7 像素对应的墨卡托米数；小于它的建筑渲染出来不可见。 */
    private static double pixelCullMercatorMeters(double zoom) {
        if (!(zoom >= 1 && zoom <= 30)) return 0;
        double metersPerPixel = 78271.51696402048 / Math.pow(2, zoom);
        return metersPerPixel * 0.7;
    }

    /** 收集 geometry 中的多边形候选；返回是否有因低于像素阈值而被剔除的。 */
    private static boolean collectPolygons(List<BuildingCandidate> candidates, Geometry geometry,
                                           double height, double cullMercatorMeters, double lonScale) {
        boolean culled = false;
        if (geometry instanceof Polygon polygon) {
            culled = !offerCandidate(candidates, polygon, height, cullMercatorMeters, lonScale);
        } else if (geometry instanceof MultiPolygon multiPolygon) {
            for (int i = 0; i < multiPolygon.getNumGeometries(); i++) {
                if (multiPolygon.getGeometryN(i) instanceof Polygon polygon) {
                    culled |= !offerCandidate(candidates, polygon, height, cullMercatorMeters, lonScale);
                }
            }
        }
        return culled;
    }

    /** 通过像素阈值则加入候选并返回 true，被剔除返回 false。 */
    private static boolean offerCandidate(List<BuildingCandidate> candidates, Polygon polygon,
                                          double height, double cullMercatorMeters, double lonScale) {
        Envelope envelope = polygon.getEnvelopeInternal();
        double cx = (envelope.getMinX() + envelope.getMaxX()) * 0.5;
        double cy = (envelope.getMinY() + envelope.getMaxY()) * 0.5;
        if (cullMercatorMeters > 0) {
            // 包络对角线换算成墨卡托米（x 向 1 度 = 111319m；y 向按纬度放大），与前端分辨率同一空间
            double diagonal = Math.hypot(envelope.getWidth(), envelope.getHeight() / Math.max(0.2, lonScale)) * 111319.49;
            if (diagonal < cullMercatorMeters) return false;
        }
        // 显著度 = 占地包络面积 x 高度：宽视野截断时优先保住天际线和大体量建筑
        double importance = envelope.getWidth() * envelope.getHeight() * (height + 10.0);
        candidates.add(new BuildingCandidate(polygon, height, cx, cy, importance));
        return true;
    }

    /**
     * 候选超过配额时做网格分层筛选：bbox 划成 GRID_N x GRID_N 格，各格内按显著度降序，
     * 从靠近 focus 的格子开始逐轮轮询、每轮每格取一栋，直到吃满配额——保证整个视野
     * 均匀覆盖，而不是所有配额堆在某一处。
     */
    private static List<BuildingCandidate> selectForViewport(List<BuildingCandidate> candidates, int maxFeatures,
                                                             double[] wgs84Bbox, double[] focus, double lonScale,
                                                             BuildingTileVO result) {
        if (candidates.size() <= maxFeatures) return candidates;
        result.setTruncated(true);

        final int gridN = 20;
        double minLon = wgs84Bbox[0];
        double minLat = wgs84Bbox[1];
        double cellW = Math.max((wgs84Bbox[2] - minLon) / gridN, 1e-12);
        double cellH = Math.max((wgs84Bbox[3] - minLat) / gridN, 1e-12);

        Map<Integer, List<BuildingCandidate>> cells = new HashMap<>();
        for (BuildingCandidate candidate : candidates) {
            int ix = Math.min(gridN - 1, Math.max(0, (int) ((candidate.cx() - minLon) / cellW)));
            int iy = Math.min(gridN - 1, Math.max(0, (int) ((candidate.cy() - minLat) / cellH)));
            cells.computeIfAbsent(ix * gridN + iy, key -> new ArrayList<>()).add(candidate);
        }
        for (List<BuildingCandidate> cell : cells.values()) {
            cell.sort(Comparator.comparingDouble(BuildingCandidate::importance).reversed());
        }

        List<Integer> cellKeys = new ArrayList<>(cells.keySet());
        cellKeys.sort(Comparator.comparingDouble(key -> {
            double cellCx = minLon + (key / gridN + 0.5) * cellW;
            double cellCy = minLat + (key % gridN + 0.5) * cellH;
            double dx = (cellCx - focus[0]) * lonScale;
            double dy = cellCy - focus[1];
            return dx * dx + dy * dy;
        }));

        List<BuildingCandidate> selected = new ArrayList<>(maxFeatures);
        int round = 0;
        while (selected.size() < maxFeatures) {
            boolean took = false;
            for (Integer key : cellKeys) {
                List<BuildingCandidate> cell = cells.get(key);
                if (round < cell.size()) {
                    selected.add(cell.get(round));
                    took = true;
                    if (selected.size() >= maxFeatures) break;
                }
            }
            if (!took) break;
            round++;
        }
        return selected;
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
        } catch (NumberFormatException error) {
            throw new BusinessException("建筑高度字段非法: " + value, error);
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
