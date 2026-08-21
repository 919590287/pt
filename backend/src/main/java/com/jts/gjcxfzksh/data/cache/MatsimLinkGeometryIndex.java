package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.data.MatsimData;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.XMLEvent;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * 大模型轨迹构建专用的完整道路轻量索引。
 *
 * <p>运行态 {@link MatsimLargeModelNetworkCache} 只保留公交时刻表引用的子路网，不能作为
 * events 中小汽车等普通车辆的几何来源。本索引直接流式读取原始完整 network，仅保留
 * {@code linkId -> primitive 坐标/长度}；不创建 MATSim Node/Link 对象。坐标转换规则与
 * Datasource 一致：文件 CRS 优先，其次 network.inputCRS、global CRS，目标为 EPSG:3857。</p>
 */
@Slf4j
final class MatsimLinkGeometryIndex {

    private static final int IO_BUFFER_BYTES = 4 * 1024 * 1024;

    private final Object2IntOpenHashMap<String> linkIndex;
    private final float[] fromX;
    private final float[] fromY;
    private final float[] toX;
    private final float[] toY;
    private final float[] lengthMeters;
    private final double originX;
    private final double originY;
    private final String sourceCrs;

    private MatsimLinkGeometryIndex(
            Object2IntOpenHashMap<String> linkIndex,
            FloatArrayList fromX,
            FloatArrayList fromY,
            FloatArrayList toX,
            FloatArrayList toY,
            FloatArrayList lengthMeters,
            double originX,
            double originY,
            String sourceCrs
    ) {
        this.linkIndex = linkIndex;
        this.fromX = fromX.toFloatArray();
        this.fromY = fromY.toFloatArray();
        this.toX = toX.toFloatArray();
        this.toY = toY.toFloatArray();
        this.lengthMeters = lengthMeters.toFloatArray();
        this.originX = originX;
        this.originY = originY;
        this.sourceCrs = sourceCrs;
    }

    static MatsimLinkGeometryIndex load(MatsimData data) throws Exception {
        String source = data == null || data.getOutfile() == null ? null : data.getOutfile().getNetwork();
        if (source == null || source.isBlank() || !Files.isRegularFile(Path.of(source))) {
            throw new IllegalStateException("大模型轨迹缺少完整原始 network，不能生成完整车辆轨迹: model="
                    + (data == null ? "" : data.getName()));
        }
        Path path = Path.of(source);
        long started = System.currentTimeMillis();
        String fileCrs = detectNetworkCrs(path);
        String globalCrs = data.getConfig() == null ? null : data.getConfig().global().getCoordinateSystem();
        String inputCrs = data.getConfig() == null ? null : data.getConfig().network().getInputCRS();
        CoordinateTransformation transformation = coordinateTransformation(globalCrs, inputCrs, fileCrs);

        Object2IntOpenHashMap<String> nodeIndex = new Object2IntOpenHashMap<>();
        nodeIndex.defaultReturnValue(-1);
        FloatArrayList nodeX = new FloatArrayList();
        FloatArrayList nodeY = new FloatArrayList();
        double[] origin = new double[]{Double.NaN, Double.NaN};

        Object2IntOpenHashMap<String> links = new Object2IntOpenHashMap<>();
        links.defaultReturnValue(-1);
        FloatArrayList fromX = new FloatArrayList();
        FloatArrayList fromY = new FloatArrayList();
        FloatArrayList toX = new FloatArrayList();
        FloatArrayList toY = new FloatArrayList();
        FloatArrayList lengths = new FloatArrayList();

        int unresolvedLinks = 0;
        String firstUnresolved = null;
        XMLInputFactory factory = xmlInputFactory();
        try (InputStream in = openInput(path)) {
            XMLEventReader reader = factory.createXMLEventReader(in);
            while (reader.hasNext()) {
                XMLEvent event = reader.nextEvent();
                if (!event.isStartElement()) continue;
                String element = event.asStartElement().getName().getLocalPart();
                if ("node".equals(element)) {
                    String id = attribute(event, "id");
                    double x = number(attribute(event, "x"));
                    double y = number(attribute(event, "y"));
                    if (id == null || !Double.isFinite(x) || !Double.isFinite(y)) {
                        throw new IllegalStateException("完整 network 包含无效 node");
                    }
                    Coord coord = transformation == null ? new Coord(x, y) : transformation.transform(new Coord(x, y));
                    if (!Double.isFinite(coord.getX()) || !Double.isFinite(coord.getY())) {
                        throw new IllegalStateException("完整 network 坐标转换结果无效: node=" + id);
                    }
                    if (!Double.isFinite(origin[0])) {
                        origin[0] = coord.getX();
                        origin[1] = coord.getY();
                    }
                    if (nodeIndex.containsKey(id)) {
                        throw new IllegalStateException("完整 network 存在重复 node id: " + id);
                    }
                    int index = nodeX.size();
                    nodeIndex.put(id, index);
                    nodeX.add((float) (coord.getX() - origin[0]));
                    nodeY.add((float) (coord.getY() - origin[1]));
                } else if ("link".equals(element)) {
                    String id = attribute(event, "id");
                    String from = attribute(event, "from");
                    String to = attribute(event, "to");
                    int fromIndex = from == null ? -1 : nodeIndex.getInt(from);
                    int toIndex = to == null ? -1 : nodeIndex.getInt(to);
                    if (id == null || fromIndex < 0 || toIndex < 0) {
                        unresolvedLinks++;
                        if (firstUnresolved == null) firstUnresolved = id;
                        continue;
                    }
                    if (links.containsKey(id)) {
                        throw new IllegalStateException("完整 network 存在重复 link id: " + id);
                    }
                    float fx = nodeX.getFloat(fromIndex);
                    float fy = nodeY.getFloat(fromIndex);
                    float tx = nodeX.getFloat(toIndex);
                    float ty = nodeY.getFloat(toIndex);
                    double declaredLength = number(attribute(event, "length"));
                    double geometricLength = Math.hypot((double) tx - fx, (double) ty - fy);
                    double length = Double.isFinite(declaredLength) && declaredLength > 0.0
                            ? declaredLength : geometricLength;
                    int index = fromX.size();
                    links.put(id, index);
                    fromX.add(fx);
                    fromY.add(fy);
                    toX.add(tx);
                    toY.add(ty);
                    lengths.add((float) Math.max(0.0, length));
                }
            }
            reader.close();
        }
        if (unresolvedLinks > 0) {
            throw new IllegalStateException("完整 network 的 link 端点不完整: count=" + unresolvedLinks
                    + ", first=" + firstUnresolved);
        }
        if (links.isEmpty()) {
            throw new IllegalStateException("完整原始 network 不包含任何 link");
        }

        MatsimLinkGeometryIndex result = new MatsimLinkGeometryIndex(
                links, fromX, fromY, toX, toY, lengths,
                Double.isFinite(origin[0]) ? origin[0] : 0.0,
                Double.isFinite(origin[1]) ? origin[1] : 0.0,
                firstNonBlank(fileCrs, inputCrs, globalCrs, "unspecified")
        );
        log.info("完整道路轻量几何索引加载完成: model={}, links={}, crs={} -> EPSG:3857, elapsed={}ms",
                data.getName(), result.size(), result.sourceCrs(), System.currentTimeMillis() - started);
        return result;
    }

    int find(String linkId) {
        return linkId == null ? -1 : linkIndex.getInt(linkId);
    }

    int size() {
        return fromX.length;
    }

    double fromX(int index) {
        return originX + fromX[index];
    }

    double fromY(int index) {
        return originY + fromY[index];
    }

    double toX(int index) {
        return originX + toX[index];
    }

    double toY(int index) {
        return originY + toY[index];
    }

    double lengthMeters(int index) {
        return lengthMeters[index];
    }

    float relativeFromX(int index) {
        return fromX[index];
    }

    float relativeFromY(int index) {
        return fromY[index];
    }

    float relativeToX(int index) {
        return toX[index];
    }

    float relativeToY(int index) {
        return toY[index];
    }

    double originX() {
        return originX;
    }

    double originY() {
        return originY;
    }

    String sourceCrs() {
        return sourceCrs;
    }

    private static String detectNetworkCrs(Path path) throws Exception {
        XMLInputFactory factory = xmlInputFactory();
        try (InputStream in = openInput(path)) {
            XMLEventReader reader = factory.createXMLEventReader(in);
            boolean capture = false;
            StringBuilder value = new StringBuilder();
            while (reader.hasNext()) {
                XMLEvent event = reader.nextEvent();
                if (event.isStartElement()) {
                    String name = event.asStartElement().getName().getLocalPart();
                    if ("node".equals(name) || "link".equals(name)) break;
                    if ("attribute".equals(name)
                            && "coordinateReferenceSystem".equals(attribute(event, "name"))) {
                        capture = true;
                        value.setLength(0);
                    }
                } else if (capture && event.isCharacters()) {
                    value.append(event.asCharacters().getData());
                } else if (capture && event.isEndElement()
                        && "attribute".equals(event.asEndElement().getName().getLocalPart())) {
                    String result = value.toString().trim();
                    reader.close();
                    return result.isBlank() ? null : result;
                }
            }
            reader.close();
        }
        return null;
    }

    private static CoordinateTransformation coordinateTransformation(String globalCrs, String inputCrs, String fileCrs) {
        String source = firstNonBlank(fileCrs, inputCrs, globalCrs, null);
        if (source == null || source.equalsIgnoreCase("epsg:3857")) return null;
        return TransformationFactory.getCoordinateTransformation(source, "epsg:3857");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static double number(String value) {
        if (value == null || value.isBlank()) return Double.NaN;
        try {
            return Double.parseDouble(value);
        } catch (Exception ignored) {
            return Double.NaN;
        }
    }

    private static XMLInputFactory xmlInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        setIfSupported(factory, XMLInputFactory.SUPPORT_DTD, false);
        setIfSupported(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        setIfSupported(factory, XMLInputFactory.IS_COALESCING, true);
        return factory;
    }

    private static void setIfSupported(XMLInputFactory factory, String key, Object value) {
        try {
            factory.setProperty(key, value);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static InputStream openInput(Path path) throws Exception {
        InputStream raw = new BufferedInputStream(Files.newInputStream(path), IO_BUFFER_BYTES);
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz")
                ? new GZIPInputStream(raw, IO_BUFFER_BYTES) : raw;
    }

    private static String attribute(XMLEvent event, String name) {
        Attribute attribute = event.asStartElement().getAttributeByName(new QName(name));
        return attribute == null ? null : attribute.getValue();
    }
}
