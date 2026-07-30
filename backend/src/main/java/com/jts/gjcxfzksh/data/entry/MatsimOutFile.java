package com.jts.gjcxfzksh.data.entry;


import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ReflectiveConfigGroup;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * matsim output文件
 */
@Slf4j
@Getter
public class MatsimOutFile {

    /**
     * matsim output目录
     */
    private String dir;
    private String cacheDir;

    /**
     * 坐标系
     */
    private String crs;

    /**
     * transitSchedule.xml.gz 绝对路径
     */
    @Setter
    private String transitSchedule;

    /**
     * transitVehicles.xml.gz 绝对路径
     */
    @Setter
    private String transitVehicles;

    /**
     * network.xml.gz 绝对路径
     */
    private String network;

    /**
     * plans.xml.gz 绝对路径
     */
    private String plans;

    /**
     * events.xml.gz 绝对路径
     */
    private String events;

    /**
     * config.xml 绝对路径
     */
    private String config;

    /**
     * output 目录中的原始配置路径。
     */
    private String sourceConfig;

    /**
     * 配置只在真正加载模型时解析。模型列表/缓存状态探测只需要文件路径，
     * 不应为每次轮询重复做 XML + DTD 解析。
     */
    private Config parsedConfig;

    /**
     * facilities 绝对路径
     */
    private String facilities;

    /**
     * vehicles.xml.gz 绝对路径
     */
    private String vehicles;

    /**
     * 公交上车数据
     */
    private String boardCounts;

    /**
     * 公交上车数据
     */
    private String alightCounts;

    /**
     * 公交乘车数据
     */
    private String occupancyCounts;
    private String linkstats;


    private

    //    @PostConstruct

    /**
     * 初始化
     */
    void init() {
        File dir = new File(this.dir);
        if (!dir.isDirectory()) {
            throw new RuntimeException(dir + " 不是一个目录");
        }
        boolean isiters = false;
        // 加载目录中必须文件
//        File[] files = dir.listFiles((dir1, name) -> name.contains(SUFFIX) || "ITERS".equals(name));
//        if (files == null || files.length == 0) {
//            throw new RuntimeException(this.dir + " dir error .");
//        }
        File[] files = dir.listFiles();
        if (files == null) {
            throw new RuntimeException("Matsim 输出目录为空");
        }
        int linkstatsPriority = 0;
        List<String> reducedConfigs = new ArrayList<>();
        List<String> fullConfigs = new ArrayList<>();
        for (File file : files) {
            String fileName = file.getName();
            if (isIgnoredOutputFile(fileName)) {
                continue;
            }
            String lowerFileName = fileName.toLowerCase(Locale.ROOT);
            if (lowerFileName.endsWith(OutFile.CONFIG_REDUCED + ".xml")) {
                reducedConfigs.add(file.getAbsolutePath());
            } else if (lowerFileName.endsWith(OutFile.CONFIG + ".xml")) {
                fullConfigs.add(file.getAbsolutePath());
            }
            int fileLinkstatsPriority = linkStatsPriority(fileName);
            if (fileName.contains(OutFile.PLANS)) {
                this.plans = file.getPath();
            } else if (fileName.contains(OutFile.EVENT)) {
                this.events = file.getPath();
            } else if (fileName.contains(OutFile.NETWORK)) {
                this.network = file.getPath();
            } else if (fileName.contains(OutFile.TRANSIT_SCHEDULE)) {
                this.transitSchedule = file.getPath();
            } else if (fileName.contains(OutFile.TRANSIT_VEHICLES)) {
                this.transitVehicles = file.getPath();
            } else if (fileLinkstatsPriority > 0) {
                if (fileLinkstatsPriority >= linkstatsPriority) {
                    linkstatsPriority = fileLinkstatsPriority;
                    this.linkstats = file.getPath();
                }
            } else if (fileName.contains(OutFile.LINKS)) {
                this.linkstats = file.getPath();
            } else if (fileName.contains(OutFile.VEHICLES)) {
                this.vehicles = file.getPath();
            } else if (fileName.contains(OutFile.FACILITIES)) {
                this.facilities = file.getPath();
            } else if (fileName.contains(OutFile.BOARD_COUNTS)) { // 公交真实上客数据
                this.boardCounts = file.getPath();
            } else if (fileName.contains(OutFile.ALIGHT_COUNTS)) { // 公交真实上客数据
                this.alightCounts = file.getPath();
            } else if (fileName.contains(OutFile.OCCUPANCY_COUNTS)) { // 公交真实上客数据
                this.occupancyCounts = file.getPath();
            }
            if (!isiters && file.getName().equals("ITERS")) {
                isiters = true;
            }
        }
        if (reducedConfigs.size() > 1 || fullConfigs.size() > 1) {
            throw new IllegalStateException("MATSim 输出目录存在多个同类 config，无法确定唯一输入: reduced="
                    + reducedConfigs + ", full=" + fullConfigs);
        }
        // 展示模型明确使用 reduced config；没有 reduced 时才使用唯一的完整 config。
        this.sourceConfig = reducedConfigs.isEmpty()
                ? (fullConfigs.isEmpty() ? null : fullConfigs.getFirst())
                : reducedConfigs.getFirst();
        this.config = this.sourceConfig;

        if (this.config == null) {
            throw new RuntimeException("没有找到config.xml或config_reduced.xml结尾的文件，请检查[" + dir.getAbsolutePath() + "]目录");
        }

        if (isiters) {
            File itersDir = new File(this.dir + "/ITERS/");
            File[] iters = itersDir.listFiles(file -> file.isDirectory() && !isIgnoredOutputFile(file.getName())); // 过滤文件夹
            if (iters == null || iters.length == 0) {
//                throw new RuntimeException("ITERS dir error .");
                return;
            }
//            Arrays.sort(iters, (a, b) -> {
//                Integer aindex = Integer.parseInt(a.getName().split("\\.")[1]);
//                Integer bindex = Integer.parseInt(b.getName().split("\\.")[1]);
//                return bindex.compareTo(aindex); // 倒序
//            });
            // 公交真实数据
//            this.alightCounts = iters[0].getPath() + "/" + (iters.length - 1) + "0.simCountCompareAlighting.txt";
//            this.boardCounts = iters[0].getPath() + "/" + (iters.length - 1) + "0.simCountCompareBoarding.txt";
//            this.occupancyCounts = iters[0].getPath() + "/" + (iters.length - 1) + "0.simCountCompareOccupancy.txt";
            for (File iter : iters) {
                File[] counts = iter.listFiles(file -> {
                    String name = file.getName();
                    return !isIgnoredOutputFile(name) && (name.contains(OutFile.ALIGHT_COUNTS) || name.contains(OutFile.BOARD_COUNTS) || name.contains(OutFile.OCCUPANCY_COUNTS));
                });
                if (counts != null) {
                    for (File count : counts) {
                        if (count.getName().contains(OutFile.ALIGHT_COUNTS)) {
                            this.alightCounts = count.getPath();
                        } else if (count.getName().contains(OutFile.BOARD_COUNTS)) {
                            this.boardCounts = count.getPath();
                        } else if (count.getName().contains(OutFile.OCCUPANCY_COUNTS)) {
                            this.occupancyCounts = count.getPath();
                        }
                    }
                }
            }
        }

        // 是否有uam模块
        File[] uamdir = dir.getParentFile().listFiles(f -> f.getName().equals("uam"));
        if (uamdir != null && uamdir.length == 1) {

        }

    }

    private static boolean isIgnoredOutputFile(String name) {
        return name.startsWith(".") || name.startsWith("._");
    }

    private static int linkStatsPriority(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("simcountcompare")) {
            return 0;
        }
        if (lower.contains("linkstats") || lower.contains("link_stats")) {
            return 3;
        }
        if (lower.endsWith("links.csv") || lower.endsWith("links.csv.gz") || lower.contains("_links.csv")) {
            return 3;
        }
        if (lower.contains(OutFile.LINK_STATS)) {
            return 1;
        }
        return 0;
    }

    /**
     * 重新加载
     *
     * @param dir matsim output目录
     */
    public static MatsimOutFile reload(String dir) {
        return reload(dir, new File(System.getProperty("java.io.tmpdir"), "gjcxfzksh-cache").getAbsolutePath());
    }

    public static MatsimOutFile reload(String dir, String cacheDir) {
        MatsimOutFile out = new MatsimOutFile();
        out.dir = dir;
        out.cacheDir = cacheDir;
        out.init();
        return out;
    }

    /**
     * 解析并缓存 MATSim 配置。目录扫描只发现路径；真正加载时若原始配置
     * 与当前 MATSim 版本不兼容，则在缓存目录生成兼容副本，绝不修改源模型。
     */
    public synchronized Config loadConfig() {
        if (parsedConfig != null) {
            return parsedConfig;
        }
        String originalConfig = sourceConfig;
        Config loaded;
        String reusableConverted = reusableConvertedConfig(originalConfig, cacheDir);
        if (reusableConverted != null) {
            try {
                loaded = ConfigUtils.loadConfig(reusableConverted);
                this.config = reusableConverted;
                this.crs = loaded.global().getCoordinateSystem();
                this.parsedConfig = loaded;
                return loaded;
            } catch (Exception e) {
                log.warn("已转换配置缓存不可用，将重新生成: {}", reusableConverted);
            }
        }
        try {
            loaded = ConfigUtils.loadConfig(originalConfig);
        } catch (Exception e) {
            log.warn("MATSim 原始配置与当前版本不兼容，尝试生成缓存兼容副本: {}", originalConfig);
            String converted = config15to2024(originalConfig, cacheDir, reusableConverted != null);
            try {
                loaded = ConfigUtils.loadConfig(converted);
            } catch (Exception convertedError) {
                throw new IllegalStateException("MATSim 配置加载失败: " + originalConfig, convertedError);
            }
            this.config = converted;
        }
        this.crs = loaded.global().getCoordinateSystem();
        this.parsedConfig = loaded;
        return loaded;
    }

    /**
     * 判断路径是当前原始配置，或是由它生成且仍未过期的兼容转换工件。
     */
    public boolean isSourceOrCompatibleConvertedConfig(String candidate) {
        if (candidate == null || candidate.isBlank() || sourceConfig == null || sourceConfig.isBlank()) {
            return false;
        }
        Path normalizedCandidate = Path.of(candidate).toAbsolutePath().normalize();
        Path normalizedSource = Path.of(sourceConfig).toAbsolutePath().normalize();
        if (normalizedSource.equals(normalizedCandidate)) {
            return true;
        }
        String converted = reusableConvertedConfig(sourceConfig, cacheDir);
        return converted != null
                && Path.of(converted).toAbsolutePath().normalize().equals(normalizedCandidate);
    }

    /**
     * 获取所有matsim运行需要的文件
     */
    public List<String> getInputFiles() {
        List<String> list = new ArrayList<>();
        list.add(this.getConfig());
        list.add(this.getNetwork());
        list.add(this.getPlans());
        list.add(this.getTransitSchedule());
        list.add(this.getTransitVehicles());
        list.add(this.getConfig());
        if (this.getFacilities() != null) {
            list.add(this.getFacilities());
        }
        if (this.getBoardCounts() != null) {
            list.add(this.getBoardCounts());
        }
        if (this.getAlightCounts() != null) {
            list.add(this.getAlightCounts());
        }
        if (this.getOccupancyCounts() != null) {
            list.add(this.getOccupancyCounts());
        }
        return list;
    }

    /**
     * 文件后缀
     */
    private static final String SUFFIX = ".xml.gz";

    /**
     * 输出文件名
     */
    public interface OutFile {

        // 公交下车数据
        String ALIGHT_COUNTS = "simCountCompareAlighting";

        // 公交上车数据
        String BOARD_COUNTS = "simCountCompareBoarding";
        String OCCUPANCY_COUNTS = "simCountCompareOccupancy";
        String LINK_STATS = "counts";
        String LINKS = "links.csv";
        String VEHICLES = "vehicles";
        String EVENT = "events";
        String PLANS = "plans";
        String NETWORK = "network";
        String TRANSIT_SCHEDULE = "transitSchedule";
        String TRANSIT_VEHICLES = "transitVehicles";
        String FACILITIES = "facilities";
        String CONFIG = "config";
        String CONFIG_REDUCED = "config_reduced";
    }

    public static String config15to2024(String filename) {
        return config15to2024(filename, null);
    }

    public static String config15to2024(String filename, String cacheDir) {
        return config15to2024(filename, cacheDir, false);
    }

    private static String config15to2024(String filename, String cacheDir, boolean force) {
        try {
            File source = new File(filename);
            String newVersion;
            File versionFile;
            if (cacheDir == null || cacheDir.isBlank()) {
                newVersion = filename;
                versionFile = new File(filename + INPLACE_VERSION_SUFFIX);
                if (versionFile.exists()) {
                    return filename;
                }
            } else {
                File generatedDir = new File(cacheDir, "generated-inputs");
                if (!generatedDir.exists() && !generatedDir.mkdirs()) {
                    throw new RuntimeException("创建缓存输入目录失败: " + generatedDir.getAbsolutePath());
                }
                newVersion = new File(generatedDir,
                        source.getName().replace(".xml", "") + CONVERTED_XML_SUFFIX).getAbsolutePath();
                versionFile = new File(newVersion + ".version");
                if (!force && isReusableConvertedConfig(source, new File(newVersion), versionFile)) {
                    return newVersion;
                }
            }
            String xmlval = Files.readString(source.toPath(), StandardCharsets.UTF_8);
            writeAtomically(new File(newVersion).toPath(), config15to2024Val(xmlval));
            if (cacheDir == null || cacheDir.isBlank()) {
                versionFile.createNewFile();
            } else {
                writeAtomically(versionFile.toPath(), conversionFingerprint(source));
            }
            return newVersion;
        } catch (Exception e) {
            log.error("config.xml版本转换出错", e);
            throw new RuntimeException("config.xml版本转换出错", e);
        }
    }

    private static String reusableConvertedConfig(String filename, String cacheDir) {
        if (filename == null || cacheDir == null || cacheDir.isBlank()) {
            return null;
        }
        File source = new File(filename);
        File converted = new File(new File(cacheDir, "generated-inputs"),
                source.getName().replace(".xml", "") + CONVERTED_XML_SUFFIX);
        File versionFile = new File(converted.getAbsolutePath() + ".version");
        return isReusableConvertedConfig(source, converted, versionFile)
                ? converted.getAbsolutePath()
                : null;
    }

    private static boolean isReusableConvertedConfig(File source, File converted, File versionFile) {
        if (!source.isFile() || !converted.isFile() || !versionFile.isFile()) {
            return false;
        }
        try {
            return conversionFingerprint(source)
                    .equals(Files.readString(versionFile.toPath(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
    }

    private static String conversionFingerprint(File source) {
        return CONVERTED_XML_SUFFIX + "|" + source.getAbsolutePath() + "|"
                + source.length() + "|" + source.lastModified();
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static String config15to2024Val(String xmlval) {
        for (Map.Entry<String, String> entry : V15_TO_CURRENT.entrySet()) {
            xmlval = xmlval.replace(entry.getKey(), entry.getValue());
        }
        xmlval = xmlval.replaceAll("<param name=\"travelTimeCalculator.+", "");
        xmlval = xmlval.replaceAll("<param name=\"networkRouteConsistencyCheck.+", "");
        return stripUnknownStrictParams(xmlval);
    }

    /**
     * 转换规则改变时递增后缀，使旧缓存兼容副本自动失效。
     */
    private static final String CONVERTED_XML_SUFFIX = ".v2025s.xml";
    private static final String INPLACE_VERSION_SUFFIX = ".2025s.version";
    private static volatile Map<String, Set<String>> strictModuleParamsCache;

    private static Map<String, Set<String>> strictModuleParams() {
        Map<String, Set<String>> cached = strictModuleParamsCache;
        if (cached != null) {
            return cached;
        }
        Map<String, Set<String>> map = new HashMap<>();
        try {
            Config probe = ConfigUtils.createConfig();
            Field storeField = ReflectiveConfigGroup.class.getDeclaredField("storeUnknownParameters");
            storeField.setAccessible(true);
            for (Map.Entry<String, ConfigGroup> entry : probe.getModules().entrySet()) {
                try {
                    ConfigGroup group = entry.getValue();
                    if (!(group instanceof ReflectiveConfigGroup) || !isStrictGroup(group, storeField)) {
                        continue;
                    }
                    map.put(entry.getKey(), new HashSet<>(group.getParams().keySet()));
                } catch (Exception perGroup) {
                    log.debug("跳过配置组[{}]的参数白名单构建: {}",
                            entry.getKey(), perGroup.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("构建严格配置组参数白名单失败，跳过未知参数清理: {}", e.getMessage());
        }
        strictModuleParamsCache = map;
        return map;
    }

    private static boolean isStrictGroup(ConfigGroup group, Field storeField) {
        boolean strict;
        try {
            strict = !storeField.getBoolean(group);
        } catch (Exception ex) {
            strict = true;
        }
        if (!strict) {
            return false;
        }
        try {
            Method handler = group.getClass()
                    .getMethod("handleAddUnknownParam", String.class, String.class);
            if (handler.getDeclaringClass() != ReflectiveConfigGroup.class) {
                return false;
            }
        } catch (Exception ignored) {
            // 无法反射时保守地按严格配置组处理。
        }
        return true;
    }

    /**
     * 只移除严格 module 的未知直接 param；parameterset 和普通扩展 module 保持不变。
     */
    static String stripUnknownStrictParams(String xmlval) {
        Map<String, Set<String>> validByModule = strictModuleParams();
        if (validByModule.isEmpty()) {
            return xmlval;
        }
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setFeature("http://xml.org/sax/features/validation", false);
            dbf.setValidating(false);
            DocumentBuilder builder = dbf.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xmlval)));

            boolean removedAny = false;
            NodeList modules = doc.getElementsByTagName("module");
            for (int i = 0; i < modules.getLength(); i++) {
                Element module = (Element) modules.item(i);
                Set<String> valid = validByModule.get(module.getAttribute("name"));
                if (valid == null) {
                    continue;
                }
                List<Element> toRemove = new ArrayList<>();
                NodeList children = module.getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    Node child = children.item(j);
                    if (child.getNodeType() == Node.ELEMENT_NODE
                            && "param".equals(child.getNodeName())) {
                        String paramName = ((Element) child).getAttribute("name");
                        if (!paramName.isEmpty() && !valid.contains(paramName)) {
                            toRemove.add((Element) child);
                        }
                    }
                }
                for (Element param : toRemove) {
                    log.warn("移除当前 MATSim 版本不支持的配置参数: module={}, param={}",
                            module.getAttribute("name"), param.getAttribute("name"));
                    module.removeChild(param);
                    removedAny = true;
                }
            }
            return removedAny ? serializeXml(doc, xmlval) : xmlval;
        } catch (Exception e) {
            log.warn("清理未知配置参数失败，使用原始配置内容: {}", e.getMessage());
            return xmlval;
        }
    }

    private static String serializeXml(Document doc, String original) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        Matcher matcher = Pattern.compile(
                "<!DOCTYPE\\s+\\S+\\s+SYSTEM\\s+\"([^\"]+)\"").matcher(original);
        if (matcher.find()) {
            transformer.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, matcher.group(1));
        }
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }

    private static final Map<String, String> V15_TO_CURRENT = Map.ofEntries(
            Map.entry("\"FastAStarLandmarks\"", "\"AStarLandmarks\""),
            Map.entry("\"ReplanningAnnealer\"", "\"replanningAnnealer\""),
            Map.entry("\"TimeAllocationMutator\"", "\"timeAllocationMutator\""),
            Map.entry("\"JDEQSim\"", "\"jdeqsim\""),
            Map.entry("\"controler\"", "\"controller\""),
            Map.entry("\"planCalcScore\"", "\"scoring\""),
            Map.entry("\"planscalcroute\"", "\"routing\""),
            Map.entry("\"strategy\"", "\"replanning\""),
            Map.entry("\"parallelEventHandling\"", "\"eventsManager\""),
            Map.entry("\"freight\"", "\"freightCarriers\""),
            Map.entry("\"BrainExpBeta\"", "\"brainExpBeta\""),
            Map.entry("\"PathSizeLogitBeta\"", "\"pathSizeLogitBeta\"")
    );

}
