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
        for (File file : files) {
            String fileName = file.getName();
            if (isIgnoredOutputFile(fileName)) {
                continue;
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
            } else if (fileName.endsWith(OutFile.CONFIG_REDUCED + ".xml")) {
//                String newConfigFile = config15to2024(file.getAbsolutePath());
                Config config;
                try {
                    config = ConfigUtils.loadConfig(file.getAbsolutePath());
                    this.config = file.getAbsolutePath();
                } catch (Exception e) {
                    log.warn("config.xml版本不兼容，尝试转换");
                    String newConfig = config15to2024(file.getAbsolutePath(), cacheDir);
                    config = ConfigUtils.loadConfig(newConfig);
                    this.config = newConfig;
                }
                this.crs = config.global().getCoordinateSystem();
            }
            if (!isiters && file.getName().equals("ITERS")) {
                isiters = true;
            }
        }
        // 没有config_reduced.xml使用config.xml
        if (this.config == null) {
            Arrays.stream(files).filter(file -> !isIgnoredOutputFile(file.getName()) && file.getName().contains(OutFile.CONFIG)).forEach(file -> {
                Config config;
                try {
                    config = ConfigUtils.loadConfig(file.getAbsolutePath());
                    this.config = file.getAbsolutePath();
                } catch (Exception e) {
                    log.warn("config.xml版本不兼容，尝试转换");
                    String newConfig = config15to2024(file.getAbsolutePath(), cacheDir);
                    config = ConfigUtils.loadConfig(newConfig);
                    this.config = newConfig;
                }
                this.crs = config.global().getCoordinateSystem();
            });
        }

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
                newVersion = new File(generatedDir, source.getName().replace(".xml", "") + CONVERTED_XML_SUFFIX).getAbsolutePath();
                versionFile = new File(newVersion + ".version");
                if (versionFile.exists() && new File(newVersion).exists()) {
                    return newVersion;
                }
            }
            File v2024config = new File(newVersion);
            BufferedReader raf = new BufferedReader(new FileReader(filename));
            StringBuilder xmlval = new StringBuilder(10000);
            raf.lines().forEach(line -> {
                xmlval.append(line + "\n");
            });
            String newXmlval = config15to2024Val(xmlval.toString());
            OutputStream out = new FileOutputStream(v2024config);
            out.write(newXmlval.getBytes());
            out.flush();
            out.close();
            raf.close();
            versionFile.createNewFile();
            return newVersion;
        } catch (Exception e) {
            log.error("config.xml版本转换出错", e);
            throw new RuntimeException("config.xml版本转换出错", e);
        }
    }

    public static String config15to2024Val(String xmlval) {
        for (Map.Entry<String, String> entry : v15to2024.entrySet()) {
            xmlval = xmlval.replace(entry.getKey(), entry.getValue());
        }
        xmlval = xmlval.replaceAll("<param name=\"travelTimeCalculator.+", "");
        xmlval = xmlval.replaceAll("<param name=\"networkRouteConsistencyCheck.+", "");
        // 删除当前 MATSim 版本严格配置组（如 controller）无法识别的参数，
        // 否则会抛出 "Module ... doesn't accept unknown parameters" / SAXParseException 导致整模型加载失败。
        xmlval = stripUnknownStrictParams(xmlval);
        return xmlval;
    }

    /**
     * 转换后配置文件名后缀。一旦清理逻辑发生变化，请递增其中的版本标识（v2025 -> v2025s ...），
     * 以便使旧的缓存转换结果失效并重新生成。
     */
    private static final String CONVERTED_XML_SUFFIX = ".v2025s.xml";
    private static final String INPLACE_VERSION_SUFFIX = ".2025s.version";

    /**
     * 当前 MATSim 运行时中“严格”配置组（不接受未知参数）允许的参数名白名单。
     * key = module name，value = 该 module 合法的直接参数名集合。基于实际加载的 MATSim 版本反射构建，
     * 因此随依赖版本自动保持正确，无需硬编码各版本的参数差异。
     */
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
                // 单个配置组取参数失败（例如某些已废弃的组）不应影响其余组（如 controller）的白名单构建
                try {
                    ConfigGroup group = entry.getValue();
                    if (!(group instanceof ReflectiveConfigGroup)) {
                        continue; // 普通 ConfigGroup 默认接受并存储未知参数，不会报错，无需清理
                    }
                    if (!isStrictGroup(group, storeField)) {
                        continue;
                    }
                    map.put(entry.getKey(), new HashSet<>(group.getParams().keySet()));
                } catch (Exception perGroup) {
                    log.debug("跳过配置组[{}]的参数白名单构建: {}", entry.getKey(), perGroup.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("构建严格配置组参数白名单失败，跳过未知参数清理: {}", e.getMessage());
        }
        strictModuleParamsCache = map;
        return map;
    }

    /**
     * 判断配置组是否会因未知参数抛错：storeUnknownParameters=false 且未自定义 handleAddUnknownParam。
     */
    private static boolean isStrictGroup(ConfigGroup group, Field storeField) {
        boolean strict;
        try {
            strict = !storeField.getBoolean(group);
        } catch (Exception ex) {
            strict = true; // ReflectiveConfigGroup 单参构造默认即严格
        }
        if (!strict) {
            return false;
        }
        try {
            // 若子类重写了 handleAddUnknownParam（自行消化未知参数），则实际不会抛错，无需清理
            Method handler = group.getClass().getMethod("handleAddUnknownParam", String.class, String.class);
            if (handler.getDeclaringClass() != ReflectiveConfigGroup.class) {
                return false;
            }
        } catch (Exception ex) {
            // 取不到方法时保守认为严格
        }
        return true;
    }

    /**
     * 移除当前 MATSim 版本严格配置组无法识别的参数。不同 MATSim 版本（如广州模型V5）导出的 config
     * 可能携带本版本已删除/改名的参数，而 controller 等严格配置组会直接抛出
     * SAXParseException 使整模型加载失败。这里仅删除严格 module 下的“直接 param 子节点”，
     * 保留 parameterset 内部参数与普通模块参数；被删除的参数将回退为 MATSim 默认值（仅用于结果展示，安全）。
     */
    static String stripUnknownStrictParams(String xmlval) {
        Map<String, Set<String>> validByModule = strictModuleParams();
        if (validByModule.isEmpty()) {
            return xmlval;
        }
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // 离线环境下禁止加载外部 DTD，避免联网拉取造成卡顿
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
                    if (child.getNodeType() == Node.ELEMENT_NODE && "param".equals(child.getNodeName())) {
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
            if (!removedAny) {
                return xmlval;
            }
            return serializeXml(doc, xmlval);
        } catch (Exception e) {
            log.warn("清理未知配置参数失败，使用原始配置内容: {}", e.getMessage());
            return xmlval;
        }
    }

    private static String serializeXml(Document doc, String original) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        // 保留原始 DOCTYPE，使 MATSim 仍按 config_v2 解析
        Matcher m = Pattern.compile("<!DOCTYPE\\s+\\S+\\s+SYSTEM\\s+\"([^\"]+)\"").matcher(original);
        if (m.find()) {
            transformer.setOutputProperty(OutputKeys.DOCTYPE_SYSTEM, m.group(1));
        }
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }

    static Map<String, String> v15to2024 = new HashMap<>();

    static {
        v15to2024.put("\"FastAStarLandmarks\"", "\"AStarLandmarks\"");
        v15to2024.put("\"ReplanningAnnealer\"", "\"replanningAnnealer\"");
        v15to2024.put("\"TimeAllocationMutator\"", "\"timeAllocationMutator\"");
        v15to2024.put("\"JDEQSim\"", "\"jdeqsim\"");
        v15to2024.put("\"controler\"", "\"controller\"");
        v15to2024.put("\"planCalcScore\"", "\"scoring\"");
        v15to2024.put("\"planscalcroute\"", "\"routing\"");
        v15to2024.put("\"strategy\"", "\"replanning\"");
        v15to2024.put("\"parallelEventHandling\"", "\"eventsManager\"");
        v15to2024.put("\"freight\"", "\"freightCarriers\"");
        v15to2024.put("\"BrainExpBeta\"", "\"brainExpBeta\"");
        v15to2024.put("\"PathSizeLogitBeta\"", "\"pathSizeLogitBeta\"");
    }

}
