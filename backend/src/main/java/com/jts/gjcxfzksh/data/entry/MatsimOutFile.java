package com.jts.gjcxfzksh.data.entry;


import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;

import java.io.*;
import java.util.*;

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
                versionFile = new File(filename + ".2025.version");
                if (versionFile.exists()) {
                    return filename;
                }
            } else {
                File generatedDir = new File(cacheDir, "generated-inputs");
                if (!generatedDir.exists() && !generatedDir.mkdirs()) {
                    throw new RuntimeException("创建缓存输入目录失败: " + generatedDir.getAbsolutePath());
                }
                newVersion = new File(generatedDir, source.getName().replace(".xml", "") + ".v2025.xml").getAbsolutePath();
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
        return xmlval;
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
