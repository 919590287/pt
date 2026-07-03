package com.jts.gjcxfzksh.optimization.util;

import com.jts.gjcxfzksh.exception.BusinessException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 几何工具：lngLat(WGS84) 与 EPSG:3857 / 模型坐标系之间的转换、JTS 多边形构建与缓冲。
 */
public final class GeoUtil {

    public static final double EARTH_RADIUS = 20037508.3427892;
    private static final GeometryFactory GF = new GeometryFactory();

    private GeoUtil() {
    }

    /** lngLat -> EPSG:3857（与前端 lngLatToWebMercator、TileNetwork 一致） */
    public static double[] lngLatToMercator(double lng, double lat) {
        double x = lng * EARTH_RADIUS / 180.0;
        double y = Math.log(Math.tan((90 + lat) * Math.PI / 360.0)) / (Math.PI / 180.0);
        y = y * EARTH_RADIUS / 180.0;
        return new double[]{x, y};
    }

    public static double[] mercatorToLngLat(double x, double y) {
        double lng = x / EARTH_RADIUS * 180.0;
        double lat = y / EARTH_RADIUS * 180.0;
        lat = 180.0 / Math.PI * (2 * Math.atan(Math.exp(lat * Math.PI / 180.0)) - Math.PI / 2);
        return new double[]{lng, lat};
    }

    /**
     * WGS84 -> 模型坐标系的转换器。模型 CRS 为空或本身是 WGS84 时返回 null（不转换）。
     */
    public static CoordinateTransformation wgs84To(String modelCrs) {
        if (modelCrs == null || modelCrs.isBlank()) {
            return null;
        }
        String normalized = modelCrs.trim();
        if (normalized.equalsIgnoreCase("WGS84") || normalized.equalsIgnoreCase("EPSG:4326")) {
            return null;
        }
        try {
            return TransformationFactory.getCoordinateTransformation(TransformationFactory.WGS84, normalized);
        } catch (Exception e) {
            throw new BusinessException("不支持的模型坐标系: " + modelCrs);
        }
    }

    /**
     * 把 lngLat 外环转换为目标坐标系下的 JTS Polygon（自动闭合）。ctf 为 null 表示坐标已是目标系。
     */
    public static Polygon toPolygon(List<double[]> lngLatRing, CoordinateTransformation ctf, boolean toMercatorIfNoCtf) {
        if (lngLatRing == null || lngLatRing.size() < 3) {
            throw new BusinessException("研究区域多边形至少需要3个顶点");
        }
        List<Coordinate> coords = new ArrayList<>(lngLatRing.size() + 1);
        for (double[] pt : lngLatRing) {
            coords.add(toCoordinate(pt[0], pt[1], ctf, toMercatorIfNoCtf));
        }
        Coordinate first = coords.get(0);
        Coordinate last = coords.get(coords.size() - 1);
        if (first.x != last.x || first.y != last.y) {
            coords.add(new Coordinate(first.x, first.y));
        }
        Polygon polygon = GF.createPolygon(coords.toArray(new Coordinate[0]));
        if (!polygon.isValid()) {
            // 自相交等问题，用 buffer(0) 修复；仍无效则报错
            Geometry fixed = polygon.buffer(0);
            if (fixed instanceof Polygon p && p.isValid()) {
                return p;
            }
            throw new BusinessException("研究区域多边形无效（可能自相交），请重新绘制");
        }
        return polygon;
    }

    private static Coordinate toCoordinate(double lng, double lat, CoordinateTransformation ctf, boolean toMercatorIfNoCtf) {
        if (ctf != null) {
            Coord c = ctf.transform(new Coord(lng, lat));
            return new Coordinate(c.getX(), c.getY());
        }
        if (toMercatorIfNoCtf) {
            double[] m = lngLatToMercator(lng, lat);
            return new Coordinate(m[0], m[1]);
        }
        return new Coordinate(lng, lat);
    }

    /**
     * 按"真实米"计算目标坐标系下的缓冲距离。
     * EPSG:3857 的单位随纬度膨胀（unit = meter / cos(lat)），其他投影坐标系按米处理；
     * 地理坐标系(WGS84) 按纬度换算度。
     */
    public static double bufferInCrsUnits(String crs, double centerLatDeg, double meters) {
        if (crs == null || crs.isBlank()) {
            return meters;
        }
        String c = crs.trim().toLowerCase();
        if (c.equals("epsg:3857") || c.contains("900913")) {
            return meters / Math.max(0.2, Math.cos(Math.toRadians(centerLatDeg)));
        }
        if (c.equals("wgs84") || c.equals("epsg:4326")) {
            return meters / 111_320.0;
        }
        return meters;
    }

    public static PreparedGeometry prepare(Geometry geometry) {
        return PreparedGeometryFactory.prepare(geometry);
    }

    public static boolean contains(PreparedGeometry zone, double x, double y) {
        return zone.contains(GF.createPoint(new Coordinate(x, y)));
    }

    public static boolean segmentIntersects(PreparedGeometry zone, Coord a, Coord b) {
        LineString line = GF.createLineString(new Coordinate[]{
                new Coordinate(a.getX(), a.getY()), new Coordinate(b.getX(), b.getY())});
        return zone.intersects(line);
    }

    /**
     * 线段与区域边界的首个交点（用于过界点定位）；无交点返回 null。
     */
    public static Coord firstIntersection(Geometry zone, Coord a, Coord b) {
        LineString line = GF.createLineString(new Coordinate[]{
                new Coordinate(a.getX(), a.getY()), new Coordinate(b.getX(), b.getY())});
        try {
            Geometry inter = zone.getBoundary().intersection(line);
            if (inter.isEmpty()) {
                return null;
            }
            Coordinate best = null;
            double bestDist = Double.MAX_VALUE;
            for (Coordinate c : inter.getCoordinates()) {
                double d = Math.hypot(c.x - a.getX(), c.y - a.getY());
                if (d < bestDist) {
                    bestDist = d;
                    best = c;
                }
            }
            return best == null ? null : new Coord(best.x, best.y);
        } catch (Exception e) {
            return null;
        }
    }

    /** 多边形面积（km²）。coords 为 EPSG:3857 时按纬度做面积修正。 */
    public static double areaKm2Mercator(Polygon polygon3857, double centerLatDeg) {
        double cos = Math.cos(Math.toRadians(centerLatDeg));
        return Math.abs(polygon3857.getArea()) * cos * cos / 1_000_000.0;
    }

    /** 折线长度（真实米），输入 lngLat 列表 */
    public static double lengthMeters(List<double[]> lngLats) {
        double total = 0;
        for (int i = 1; i < lngLats.size(); i++) {
            total += haversine(lngLats.get(i - 1)[1], lngLats.get(i - 1)[0], lngLats.get(i)[1], lngLats.get(i)[0]);
        }
        return total;
    }

    public static double haversine(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }
}
