package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.utils.TransitMetrics;
import lombok.extern.slf4j.Slf4j;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.type.AttributeDescriptor;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.operation.MathTransform;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.population.routes.NetworkRoute;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.geotools.data.simple.SimpleFeatureIterator;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 体检评估密度指标的行政区预计算。
 *
 * <p>行政区面积来自“真实数据/行政区范围”边界；常住人口由 population-v9 的
 * 100m home 栅格中心落区；公交线网长度将公交经过的无向去重道路 link 按边界
 * 精确裁切后，按裁切比例分摊 link.length。结果写进 visual info.json，接口请求
 * 只按行政区键读取，不即时扫描 plans/network。</p>
 */
@Slf4j
final class MatsimAdministrativeDensityMetrics {
    static final String ALL_CITY = "全市";
    static final String POLICY =
            "real-admin-boundary-area;population-v9-home-grid;clipped-unique-road-centerline";

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private MatsimAdministrativeDensityMetrics() {
    }

    static Map<String, Object> compute(
            MatsimData data,
            TransitMetrics.RoadTransitContext roadTransit,
            TransitMetrics.RoadNetworkStats roadNetwork,
            Long allResidentHomePersons) {
        Path boundaryFile = boundaryShapefile(data);
        if (boundaryFile == null || roadTransit == null || data.getNetwork() == null) {
            return Map.of();
        }
        try {
            List<District> districts = readDistricts(boundaryFile);
            if (districts.isEmpty()) return Map.of();

            Map<String, Long> residents = residentsByDistrict(data, districts);
            Map<String, Double> networkMeters = roadNetwork.lengthMeters() == null
                    ? Map.of() : networkMetersByDistrict(data, roadTransit, districts);
            Geometry cityUtm = UnaryUnionOp.union(
                    districts.stream().map(District::utm).toList());
            double cityAreaKm2 = cityUtm == null || cityUtm.isEmpty()
                    ? 0.0 : cityUtm.getArea() / 1_000_000.0;
            long residentsInsideBoundary = residents.values().stream()
                    .mapToLong(Long::longValue).sum();
            double networkInsideBoundary = networkMeters.values().stream()
                    .mapToDouble(Double::doubleValue).sum();
            Long cityResidents = residentsInsideBoundary > 0
                    ? residentsInsideBoundary : allResidentHomePersons;
            Double cityNetworkMeters = networkInsideBoundary > 0
                    ? networkInsideBoundary : roadNetwork.lengthMeters();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put(ALL_CITY, densityRow(
                    cityAreaKm2,
                    cityResidents,
                    cityNetworkMeters));
            districts.stream()
                    .sorted(Comparator.comparing(District::name))
                    .forEach(district -> result.put(district.name(), densityRow(
                            district.utm().getArea() / 1_000_000.0,
                            residents.getOrDefault(district.name(), 0L),
                            networkMeters.get(district.name()))));
            return result;
        } catch (Exception error) {
            throw new IllegalStateException("行政区密度预计算失败: model=" + data.getName()
                    + ", boundary=" + boundaryFile, error);
        }
    }

    static String boundaryFingerprint(MatsimData data) {
        Path shp = boundaryShapefile(data);
        if (shp == null) return "missing";
        String base = stripExtension(shp.getFileName().toString());
        StringBuilder fingerprint = new StringBuilder();
        for (String extension : List.of(".shp", ".shx", ".dbf", ".prj", ".cpg")) {
            Path component = shp.resolveSibling(base + extension);
            fingerprint.append(extension).append("=")
                    .append(MatsimSourceFingerprint.signature(component)).append("|");
        }
        return fingerprint.toString();
    }

    private static Map<String, Object> densityRow(
            double areaKm2, Long residents, Double networkMeters) {
        Map<String, Object> row = new LinkedHashMap<>();
        Double validArea = Double.isFinite(areaKm2) && areaKm2 > 0 ? areaKm2 : null;
        row.put("areaKm2", validArea);
        row.put("residentHomePersons", residents);
        row.put("busNetworkLengthMeters", networkMeters);
        row.put("czrkmd", validArea == null || residents == null
                ? null : Math.round(residents / validArea));
        row.put("gjxwmd", validArea == null || networkMeters == null
                ? null : round2((networkMeters / 1000.0) / validArea));
        return row;
    }

    private static List<District> readDistricts(Path shapefile) throws Exception {
        ShapefileDataStore store = new ShapefileDataStore(shapefile.toUri().toURL());
        store.setCharset(StandardCharsets.UTF_8);
        try {
            String typeName = store.getTypeNames()[0];
            CoordinateReferenceSystem source = store.getSchema(typeName).getCoordinateReferenceSystem();
            if (source == null) source = CRS.decode("EPSG:4326", true);
            MathTransform toMercator = CRS.findMathTransform(
                    source, CRS.decode("EPSG:3857", true), true);
            MathTransform toUtm = CRS.findMathTransform(
                    source, CRS.decode("EPSG:32649", true), true);
            Map<String, List<Geometry>> sourceByName = new LinkedHashMap<>();
            try (SimpleFeatureIterator iterator =
                         store.getFeatureSource(typeName).getFeatures().features()) {
                while (iterator.hasNext()) {
                    SimpleFeature feature = iterator.next();
                    if (!(feature.getDefaultGeometry() instanceof Geometry geometry)
                            || geometry.isEmpty()) continue;
                    String name = districtName(feature);
                    if (name.isBlank()) continue;
                    sourceByName.computeIfAbsent(name, ignored -> new ArrayList<>())
                            .add(geometry.copy());
                }
            }
            List<District> districts = new ArrayList<>();
            for (Map.Entry<String, List<Geometry>> entry : sourceByName.entrySet()) {
                Geometry sourceGeometry = UnaryUnionOp.union(entry.getValue());
                Geometry mercator = JTS.transform(sourceGeometry, toMercator).buffer(0);
                Geometry utm = JTS.transform(sourceGeometry, toUtm).buffer(0);
                if (!mercator.isEmpty() && !utm.isEmpty()) {
                    districts.add(new District(
                            entry.getKey(), mercator, utm,
                            PreparedGeometryFactory.prepare(mercator)));
                }
            }
            return districts;
        } finally {
            store.dispose();
        }
    }

    private static String districtName(SimpleFeature feature) {
        for (String key : List.of(
                "_districtName", "Name", "name", "NAME", "名称", "区名",
                "行政区", "行政区名", "区县", "县区", "district", "District", "AdminName")) {
            if (feature.getFeatureType().getDescriptor(key) == null) continue;
            Object value = feature.getAttribute(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        for (AttributeDescriptor descriptor : feature.getFeatureType().getAttributeDescriptors()) {
            if (Geometry.class.isAssignableFrom(descriptor.getType().getBinding())) continue;
            Object value = feature.getAttribute(descriptor.getLocalName());
            if (value != null && String.valueOf(value).trim().endsWith("区")) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private static Map<String, Long> residentsByDistrict(
            MatsimData data, List<District> districts) {
        byte[] bytes = MatsimPopulationCache.readGridBytes(data);
        if (bytes == null || bytes.length < 18) return Map.of();
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.get() != 'P' || buffer.get() != 'G'
                || buffer.get() != 'R' || buffer.get() != 'D') return Map.of();
        int version = Short.toUnsignedInt(buffer.getShort());
        int count = buffer.getInt();
        double cellSize = buffer.getDouble();
        if (version != 2 || count < 0 || !Double.isFinite(cellSize) || cellSize <= 0
                || bytes.length < 18L + count * 18L) return Map.of();

        STRtree index = districtIndex(districts);
        Map<String, Long> result = new HashMap<>();
        for (int row = 0; row < count; row++) {
            int i = buffer.getInt();
            int j = buffer.getInt();
            long home = Integer.toUnsignedLong(buffer.getInt());
            buffer.getInt(); // work
            buffer.getShort(); // street
            if (home == 0) continue;
            Point point = GEOMETRY_FACTORY.createPoint(
                    new Coordinate((i + 0.5) * cellSize, (j + 0.5) * cellSize));
            @SuppressWarnings("unchecked")
            List<District> candidates = index.query(point.getEnvelopeInternal());
            for (District district : candidates) {
                if (district.preparedMercator().covers(point)) {
                    result.merge(district.name(), home, Long::sum);
                    break;
                }
            }
        }
        return result;
    }

    private static Map<String, Double> networkMetersByDistrict(
            MatsimData data,
            TransitMetrics.RoadTransitContext roadTransit,
            List<District> districts) {
        Map<String, Link> physicalRoads = uniqueRoadLinks(
                data.getSchedule().getTransitLines(), data.getNetwork(), roadTransit);
        STRtree index = districtIndex(districts);
        Map<String, Double> result = new HashMap<>();
        for (Link link : physicalRoads.values()) {
            Coordinate from = coordinate(link.getFromNode().getCoord());
            Coordinate to = coordinate(link.getToNode().getCoord());
            LineString line = GEOMETRY_FACTORY.createLineString(new Coordinate[]{from, to});
            double projectedLength = line.getLength();
            if (!(projectedLength > 0) || !(link.getLength() > 0)) continue;
            @SuppressWarnings("unchecked")
            List<District> candidates = index.query(line.getEnvelopeInternal());
            for (District district : candidates) {
                if (!district.preparedMercator().intersects(line)) continue;
                // 绝大多数道路完全位于某一个行政区内。对这类线段直接计全长，避免让 JTS
                // 为每条道路与包含数十万顶点的区界执行 Overlay，相交裁剪仅留给跨界线段。
                if (district.preparedMercator().covers(line)) {
                    result.merge(district.name(), link.getLength(), Double::sum);
                    continue;
                }
                Geometry clipped = district.mercator().intersection(line);
                double ratio = Math.min(1.0, Math.max(0.0, clipped.getLength() / projectedLength));
                if (ratio > 0) {
                    result.merge(district.name(), link.getLength() * ratio, Double::sum);
                }
            }
        }
        return result;
    }

    private static Map<String, Link> uniqueRoadLinks(
            Map<Id<TransitLine>, TransitLine> lines,
            Network network,
            TransitMetrics.RoadTransitContext roadTransit) {
        Map<String, Link> result = new HashMap<>();
        for (TransitLine line : lines.values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                if (!roadTransit.isRoadRoute(line, route)
                        || !(route.getRoute() instanceof NetworkRoute networkRoute)) continue;
                List<Id<Link>> ids = new ArrayList<>();
                if (networkRoute.getStartLinkId() != null) ids.add(networkRoute.getStartLinkId());
                ids.addAll(networkRoute.getLinkIds());
                if (networkRoute.getEndLinkId() != null
                        && !networkRoute.getEndLinkId().equals(networkRoute.getStartLinkId())) {
                    ids.add(networkRoute.getEndLinkId());
                }
                for (Id<Link> id : ids) {
                    Link link = network.getLinks().get(id);
                    if (link == null || link.getFromNode() == null || link.getToNode() == null
                            || !Double.isFinite(link.getLength()) || link.getLength() <= 0) continue;
                    String from = link.getFromNode().getId().toString();
                    String to = link.getToNode().getId().toString();
                    String key = from.compareTo(to) <= 0 ? from + "\u001F" + to : to + "\u001F" + from;
                    result.merge(key, link,
                            (oldValue, newValue) ->
                                    oldValue.getLength() >= newValue.getLength() ? oldValue : newValue);
                }
            }
        }
        return result;
    }

    private static STRtree districtIndex(List<District> districts) {
        STRtree index = new STRtree();
        for (District district : districts) {
            index.insert(district.mercator().getEnvelopeInternal(), district);
        }
        index.build();
        return index;
    }

    private static Coordinate coordinate(org.matsim.api.core.v01.Coord coord) {
        return new Coordinate(coord.getX(), coord.getY());
    }

    private static Path boundaryShapefile(MatsimData data) {
        if (data == null || data.getFolder() == null || data.getFolder().isBlank()) return null;
        Path cursor = Path.of(data.getFolder()).toAbsolutePath().normalize();
        for (int depth = 0; cursor != null && depth < 8; depth++, cursor = cursor.getParent()) {
            Path areaRoot = "仿真数据".equals(String.valueOf(cursor.getFileName()))
                    ? cursor.getParent() : cursor;
            if (areaRoot == null) continue;
            Path folder = areaRoot.resolve("真实数据").resolve("行政区范围");
            Path shapefile = firstShapefile(folder);
            if (shapefile != null) return shapefile;
        }
        return null;
    }

    private static Path firstShapefile(Path folder) {
        if (folder == null || !Files.isDirectory(folder)) return null;
        try (var stream = Files.list(folder)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith("._"))
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".shp"))
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (Exception error) {
            throw new IllegalStateException("扫描行政区边界文件失败: " + folder, error);
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record District(String name, Geometry mercator, Geometry utm,
                            PreparedGeometry preparedMercator) {
    }
}
