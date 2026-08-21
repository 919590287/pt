package com.jts.gjcxfzksh.data.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jts.gjcxfzksh.data.MatsimData;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import lombok.extern.slf4j.Slf4j;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLEventWriter;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.XMLEvent;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 大模型运行时使用的公交相关子路网。
 *
 * <p>城市全路网通常包含上百万条道路，但运行监测在内存中只需要 transitSchedule
 * 实际引用的路段。完整路网仍由 visual cache 的磁盘瓦片提供给地图；这里通过两次
 * StAX 顺序扫描生成一个只含公交/轨道路段及其端点的 MATSim network，避免先把完整
 * network 对象化再裁剪。构建过程的内存只与“被引用的 link id 数”相关。</p>
 */
@Slf4j
public final class MatsimLargeModelNetworkCache {

    public static final String CACHE_VERSION = "large-network-v2";
    private static final String NETWORK_FILE = "transit-network.xml.gz";
    private static final String MANIFEST_FILE = "manifest.json";
    private static final int IO_BUFFER_BYTES = 4 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private MatsimLargeModelNetworkCache() {
    }

    /**
     * 返回适合 ScenarioUtils 的 network 输入。大模型默认必须成功生成公交子路网；
     * 构建失败时显式中止，不回退物化百万道路的完整网络而将 JVM 推向 OOM。
     */
    public static String resolveNetworkInput(MatsimData data) {
        if (data == null) return null;
        String original = data.getOutfile().getNetwork();
        if (!data.isLargeModel()) {
            return original;
        }
        if (!Boolean.parseBoolean(System.getProperty(
                "gjcxfzksh.large-model.transit-network.enabled", "true"))) {
            log.warn("管理员已显式禁用大模型公交子路网，将尝试加载完整路网: model={}", data.getName());
            return original;
        }
        return resolveTransitNetworkInput(data);
    }

    /**
     * VISUAL 运行态统一使用公交子路网，无论模型总体是否超过大模型阈值。
     * 完整道路仍由 visual 二进制瓦片提供，避免缓存命中后把百万级 Link 对象重新入堆。
     */
    public static String resolveTransitNetworkInput(MatsimData data) {
        if (data == null) return null;
        String original = data.getOutfile().getNetwork();
        if (!isApplicable(data)) {
            if (!data.isLargeModel()) {
                // 无公交时刻表的纯道路模型无法裁剪公交子网；小模型
                // 可安全回退原始路网，也不将该工件纳入缓存就绪判定。
                return original;
            }
            throw new IllegalStateException("公交子路网缺少原始 network 或 transitSchedule: model="
                    + data.getName());
        }
        String schedule = data.getOutfile().getTransitSchedule();
        try {
            synchronized (ModelBuildLocks.lockFor("large-network", data)) {
                if (!isReady(data)) {
                    build(data, Path.of(original), Path.of(schedule));
                }
            }
            return isReady(data) ? networkPath(data).toString() : original;
        } catch (Exception e) {
            throw new IllegalStateException("公交子路网生成失败，已阻止完整路网入堆: model="
                    + data.getName() + ", error=" + e.getMessage(), e);
        }
    }

    public static void prepareTransitNetwork(MatsimData data) {
        // 某些只含道路或单元测试模型没有 transitSchedule。它们不需要
        // 公交子网，不应因为可选规范工件阻断其他缓存的原子发布。
        if (isApplicable(data)) resolveTransitNetworkInput(data);
    }

    public static boolean isApplicable(MatsimData data) {
        return data != null
                && regularFile(data.getOutfile().getNetwork())
                && regularFile(data.getOutfile().getTransitSchedule());
    }

    public static boolean isReady(MatsimData data) {
        Path manifestPath = manifestPath(data);
        Path networkPath = networkPath(data);
        if (!Files.isRegularFile(manifestPath) || !Files.isRegularFile(networkPath)) {
            return false;
        }
        try {
            Map<String, Object> manifest = JSON.readValue(manifestPath.toFile(), MAP_TYPE);
            return "ready".equals(manifest.get("status"))
                    && CACHE_VERSION.equals(manifest.get("cacheVersion"))
                    && sameFile(data.getOutfile().getNetwork(), manifest, "network")
                    && sameFile(data.getOutfile().getTransitSchedule(), manifest, "schedule")
                    && number(manifest.get("requiredLinks")) > 0
                    && number(manifest.get("requiredLinks")) == number(manifest.get("writtenLinks"));
        } catch (Exception e) {
            throw new IllegalStateException("大模型线网缓存状态读取失败: " + manifestPath, e);
        }
    }

    private static void build(MatsimData data, Path sourceNetwork, Path schedule) throws Exception {
        long started = System.currentTimeMillis();

        Set<String> requiredLinks = collectScheduleLinkIds(schedule);
        if (requiredLinks.isEmpty()) {
            throw new IllegalStateException("transitSchedule 未引用任何路段");
        }
        EndpointScan endpoints = collectRequiredEndpoints(sourceNetwork, requiredLinks);
        if (endpoints.foundLinks != requiredLinks.size()) {
            throw new IllegalStateException("公交线路引用的路段在 network 中不完整: required="
                    + requiredLinks.size() + ", found=" + endpoints.foundLinks);
        }

        MatsimCachePaths.recreateVersionDir(data, CACHE_VERSION);
        Path target = networkPath(data);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        FilterResult result = writeFilteredNetwork(sourceNetwork, tmp, requiredLinks, endpoints.nodeIds);
        if (result.links != requiredLinks.size()) {
            Files.deleteIfExists(tmp);
            throw new IllegalStateException("公交子路网写入不完整: expected="
                    + requiredLinks.size() + ", actual=" + result.links);
        }
        moveAtomic(tmp, target);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("status", "ready");
        manifest.put("cacheVersion", CACHE_VERSION);
        manifest.put("generatedAt", System.currentTimeMillis());
        putFile(manifest, "network", sourceNetwork);
        putFile(manifest, "schedule", schedule);
        manifest.put("requiredLinks", requiredLinks.size());
        manifest.put("writtenLinks", result.links);
        manifest.put("writtenNodes", result.nodes);
        manifest.put("sourceBytes", Files.size(sourceNetwork));
        manifest.put("derivedBytes", Files.size(target));
        writeJsonAtomic(manifestPath(data), manifest);
        MatsimCachePaths.deleteOtherVersions(data, "large-network-v", CACHE_VERSION);
        log.info("大模型公交子路网生成完成: model={}, links={}, nodes={}, size={}MB, elapsed={}ms",
                data.getName(), result.links, result.nodes,
                Math.round(Files.size(target) / 1024.0 / 1024.0), System.currentTimeMillis() - started);
    }

    private static Set<String> collectScheduleLinkIds(Path schedule) throws Exception {
        Set<String> result = new ObjectOpenHashSet<>();
        XMLInputFactory factory = xmlInputFactory();
        try (InputStream in = openInput(schedule)) {
            XMLEventReader reader = factory.createXMLEventReader(in);
            while (reader.hasNext()) {
                XMLEvent event = reader.nextEvent();
                if (!event.isStartElement()) continue;
                String name = event.asStartElement().getName().getLocalPart();
                if ("stopFacility".equals(name)) {
                    addAttribute(result, event, "linkRefId");
                } else if ("link".equals(name)) {
                    addAttribute(result, event, "refId");
                }
            }
            reader.close();
        }
        return result;
    }

    private static EndpointScan collectRequiredEndpoints(Path network, Set<String> requiredLinks) throws Exception {
        Set<String> nodeIds = new ObjectOpenHashSet<>();
        int found = 0;
        XMLInputFactory factory = xmlInputFactory();
        try (InputStream in = openInput(network)) {
            XMLEventReader reader = factory.createXMLEventReader(in);
            while (reader.hasNext()) {
                XMLEvent event = reader.nextEvent();
                if (!event.isStartElement()
                        || !"link".equals(event.asStartElement().getName().getLocalPart())) {
                    continue;
                }
                String id = attribute(event, "id");
                if (id == null || !requiredLinks.contains(id)) continue;
                found++;
                String from = attribute(event, "from");
                String to = attribute(event, "to");
                if (from != null) nodeIds.add(from);
                if (to != null) nodeIds.add(to);
            }
            reader.close();
        }
        return new EndpointScan(nodeIds, found);
    }

    private static FilterResult writeFilteredNetwork(
            Path source,
            Path target,
            Set<String> requiredLinks,
            Set<String> requiredNodes
    ) throws Exception {
        XMLInputFactory inputFactory = xmlInputFactory();
        XMLOutputFactory outputFactory = XMLOutputFactory.newFactory();
        int nodes = 0;
        int links = 0;
        try (InputStream in = openInput(source);
             OutputStream raw = new BufferedOutputStream(Files.newOutputStream(target), IO_BUFFER_BYTES);
             OutputStream out = new GZIPOutputStream(raw, IO_BUFFER_BYTES)) {
            XMLEventReader reader = inputFactory.createXMLEventReader(in);
            XMLEventWriter writer = outputFactory.createXMLEventWriter(out, "UTF-8");
            while (reader.hasNext()) {
                XMLEvent event = reader.nextEvent();
                // MATSim 通过 network_v*.dtd 选择具体 reader；DTD 只按文本原样写回，
                // XMLInputFactory 已禁用外部实体解析，不会产生网络访问/XXE。
                if (event.isStartElement()) {
                    String name = event.asStartElement().getName().getLocalPart();
                    if ("node".equals(name)) {
                        if (!requiredNodes.contains(attribute(event, "id"))) {
                            skipElement(reader);
                            continue;
                        }
                        nodes++;
                    } else if ("link".equals(name)) {
                        if (!requiredLinks.contains(attribute(event, "id"))) {
                            skipElement(reader);
                            continue;
                        }
                        links++;
                    }
                }
                writer.add(event);
            }
            writer.flush();
            writer.close();
            reader.close();
        }
        return new FilterResult(nodes, links);
    }

    private static void skipElement(XMLEventReader reader) throws Exception {
        int depth = 1;
        while (depth > 0 && reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) depth++;
            else if (event.isEndElement()) depth--;
        }
    }

    private static XMLInputFactory xmlInputFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        setIfSupported(factory, XMLInputFactory.SUPPORT_DTD, false);
        setIfSupported(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        setIfSupported(factory, XMLInputFactory.IS_COALESCING, false);
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
        return path.getFileName().toString().toLowerCase().endsWith(".gz")
                ? new GZIPInputStream(raw, IO_BUFFER_BYTES) : raw;
    }

    private static void addAttribute(Set<String> target, XMLEvent event, String name) {
        String value = attribute(event, name);
        if (value != null && !value.isBlank()) target.add(value);
    }

    private static String attribute(XMLEvent event, String name) {
        Attribute attribute = event.asStartElement().getAttributeByName(new QName(name));
        return attribute == null ? null : attribute.getValue();
    }

    private static boolean sameFile(String path, Map<String, Object> manifest, String prefix) {
        if (!regularFile(path)) return false;
        try {
            Path file = Path.of(path);
            Object oldSignature = manifest.get(prefix + "Signature");
            if (oldSignature != null) {
                return Files.size(file) == number(manifest.get(prefix + "Size"))
                        && MatsimSourceFingerprint.signature(file).equals(String.valueOf(oldSignature));
            }
            return Files.size(file) == number(manifest.get(prefix + "Size"))
                    && Files.getLastModifiedTime(file).toMillis() == number(manifest.get(prefix + "Modified"));
        } catch (Exception e) {
            throw new IllegalStateException("校验大模型线网源文件失败: " + path, e);
        }
    }

    private static void putFile(Map<String, Object> manifest, String prefix, Path file) throws Exception {
        manifest.put(prefix + "Size", Files.size(file));
        manifest.put(prefix + "Modified", Files.getLastModifiedTime(file).toMillis());
        manifest.put(prefix + "Signature", MatsimSourceFingerprint.signature(file));
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : -1L;
    }

    private static boolean regularFile(String path) {
        return path != null && !path.isBlank() && Files.isRegularFile(Path.of(path));
    }

    private static void writeJsonAtomic(Path path, Map<String, Object> value) throws Exception {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        JSON.writeValue(tmp.toFile(), value);
        moveAtomic(tmp, path);
    }

    private static void moveAtomic(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path cacheDir(MatsimData data) {
        return MatsimCachePaths.versionDir(data, CACHE_VERSION);
    }

    private static Path networkPath(MatsimData data) {
        return cacheDir(data).resolve(NETWORK_FILE);
    }

    private static Path manifestPath(MatsimData data) {
        return cacheDir(data).resolve(MANIFEST_FILE);
    }

    private record EndpointScan(Set<String> nodeIds, int foundLinks) {
    }

    private record FilterResult(int nodes, int links) {
    }
}
