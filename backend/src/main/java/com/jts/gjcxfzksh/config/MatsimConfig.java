package com.jts.gjcxfzksh.config;

import com.alibaba.fastjson2.JSON;
import com.jts.gjcxfzksh.data.entry.Scheme;
import com.jts.gjcxfzksh.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class MatsimConfig {

    public static final String PUBLIC_SCOPE = "public";
    public static final String REAL_DATA_FOLDER = "真实数据";
    public static final String SIMULATION_DATA_FOLDER = "仿真数据";

    @Getter
    @Value("${matsim.data}")
    private String folder;

    @Value("${matsim.cache:}")
    private String cacheFolder;

    @Value("${matsim.large-model-threshold-bytes:21474836480}")
    private long largeModelThresholdBytes = 20L * 1024 * 1024 * 1024;

    @Value("${matsim.large-model-plans-threshold-bytes:8589934592}")
    private long largeModelPlansThresholdBytes = 8L * 1024 * 1024 * 1024;

    @Value("${matsim.large-model-events-threshold-bytes:8589934592}")
    private long largeModelEventsThresholdBytes = 8L * 1024 * 1024 * 1024;

    /**
     * 高压缩比 CSV（legs/trips/activities/persons）虽然磁盘体积远小于 plans/events，
     * 解压后的人员、车辆和乘客事件仍可能达到千万级。单类分析表超过 1GiB 时直接使用
     * 大模型加载路径，避免仅按 output 总体积误判后把数千万对象装入堆。
     */
    @Value("${matsim.large-model-analysis-table-threshold-bytes:1073741824}")
    private long largeModelAnalysisTableThresholdBytes = 1024L * 1024 * 1024;

    @Value("${matsim.large-model-person-track-threshold:1500000}")
    private long largeModelPersonTrackThreshold = 1_500_000L;

    /**
     * 全部方案
     */
    @Getter
    private final LinkedHashMap<String, Scheme> schemes = new LinkedHashMap<>();

    /**
     * 初始化方案
     */
    @PostConstruct
    public synchronized void init() {
        schemes.clear();
        File root = new File(folder);
        if (!root.exists() && !root.mkdirs()) {
            log.warn("数据目录不存在且创建失败: {}", root.getAbsolutePath());
            return;
        }

        verifyAccessible(root, "数据目录");
        if (cacheFolder != null && !cacheFolder.isBlank()) {
            File cacheRoot = new File(cacheFolder);
            if (cacheRoot.isDirectory()) {
                verifyAccessible(cacheRoot, "缓存目录");
            }
        }

        Set<String> knownUsernames = loadKnownUsernames();
        File[] areaFolders = root.listFiles();
        if (areaFolders == null) {
            log.warn("数据目录无法列出内容，方案列表为空: {}", root.getAbsolutePath());
            return;
        }
        Arrays.sort(areaFolders, Comparator.comparing(File::getName));
        for (File areaFolder : areaFolders) {
            if (!isAreaFolder(areaFolder, knownUsernames)) {
                continue;
            }
            ensurePublicFolder(areaFolder);
            scanArea(areaFolder);
        }
        log.info("共找到{}个方案", schemes.size());
    }

    /**
     * 目录"在，但读不进去"是容器部署最容易踩的坑：宿主机目录若保留了上传方的属主
     * （从 macOS rsync 过去就是 uid 501）和 700 权限，容器里的 app 用户既列不了目录
     * 也写不了文件。而这种状态下 File#listFiles 返回 null、Files#exists 返回 false，
     * 全程不抛异常，表现为"模型列表空 + 登录报保存用户数据失败"，日志里看不出原因，
     * 更糟的是空的用户表随后会把真实的 .gjcxfzksh-users.json 覆盖掉。这里提前拦下。
     *
     * 只拦"目录在但不可访问"。目录本身缺失沿用上面 mkdirs 的告警路径，
     * 否则本机数据盘（U 盘）没插时后端会直接起不来。
     */
    private void verifyAccessible(File dir, String label) {
        Path path = dir.toPath();
        boolean readable = Files.isReadable(path);
        boolean writable = Files.isWritable(path);
        boolean traversable = Files.isExecutable(path);
        if (readable && writable && traversable) {
            return;
        }
        throw new BusinessException(label + "不可访问: " + dir.getAbsolutePath()
                + "（可读=" + readable + " 可写=" + writable + " 可进入=" + traversable
                + "，当前进程用户=" + System.getProperty("user.name") + "）。"
                + "Docker 部署请把宿主机数据目录的属主改成容器内的 app 用户，例如："
                + "chown -R 10001:10001 <PT_DATA_DIR> <PT_CACHE_DIR>");
    }

    public List<String> areaNames() {
        File root = new File(folder);
        File[] areaFolders = root.listFiles();
        if (areaFolders == null) {
            return List.of();
        }
        Set<String> knownUsernames = loadKnownUsernames();
        return Arrays.stream(areaFolders)
                .filter(file -> isAreaFolder(file, knownUsernames))
                .map(File::getName)
                .sorted()
                .toList();
    }

    public void ensureUserFolders(String username) {
        for (String areaName : areaNames()) {
            try {
                Files.createDirectories(simulationPath(areaName).resolve(username));
            } catch (IOException e) {
                throw new BusinessException("创建用户模型目录失败", e);
            }
        }
    }

    public void renameUserFolders(String oldUsername, String newUsername) {
        for (String areaName : areaNames()) {
            Path oldPath = simulationPath(areaName).resolve(oldUsername);
            Path newPath = simulationPath(areaName).resolve(newUsername);
            try {
                if (Files.exists(newPath)) {
                    throw new BusinessException("目标用户目录已存在");
                }
                if (Files.exists(oldPath)) {
                    Files.move(oldPath, newPath);
                } else {
                    Files.createDirectories(newPath);
                }
            } catch (BusinessException e) {
                throw e;
            } catch (IOException e) {
                throw new BusinessException("同步修改用户目录失败", e);
            }
        }
    }

    public Map<String, Scheme> visibleSchemes(String username) {
        LinkedHashMap<String, Scheme> visible = new LinkedHashMap<>();
        schemes.forEach((name, scheme) -> {
            if (isSchemeVisible(name, username)) {
                visible.put(name, scheme);
            }
        });
        return visible;
    }

    public boolean isSchemeVisible(String name, String username) {
        Scheme scheme = schemes.get(name);
        if (scheme == null) {
            return false;
        }
        String scope = scheme.getScope();
        return PUBLIC_SCOPE.equals(scope) || (username != null && username.equals(scope));
    }

    public void requireSchemeAccess(String name, String username) {
        if (!isSchemeVisible(name, username)) {
            throw new BusinessException("模型不存在或无权访问");
        }
    }

    public Path realDataPath(String areaName) {
        validateAreaName(areaName);
        return Path.of(folder, areaName, REAL_DATA_FOLDER).toAbsolutePath().normalize();
    }

    public Path simulationPath(String areaName) {
        validateAreaName(areaName);
        return Path.of(folder, areaName, SIMULATION_DATA_FOLDER).toAbsolutePath().normalize();
    }

    private void validateAreaName(String areaName) {
        if (areaName == null || areaName.isBlank() || areaName.contains("..") || areaName.contains("/") || areaName.contains("\\")) {
            throw new BusinessException("区域名称无效");
        }
    }

    private boolean isAreaFolder(File folder, Set<String> knownUsernames) {
        String name = folder.getName();
        return folder.isDirectory()
                && !name.equals("temp")
                && !name.equals(PUBLIC_SCOPE)
                && !knownUsernames.contains(name)
                && !name.startsWith(".")
                && !name.equals("__MACOSX");
    }

    private void ensurePublicFolder(File areaFolder) {
        try {
            Files.createDirectories(areaFolder.toPath().resolve(SIMULATION_DATA_FOLDER).resolve(PUBLIC_SCOPE));
        } catch (IOException e) {
            log.warn("公共模型目录创建失败: {}", areaFolder.toPath().resolve(SIMULATION_DATA_FOLDER).resolve(PUBLIC_SCOPE), e);
        }
    }

    private void scanArea(File areaFolder) {
        Set<String> visibleScopes = new HashSet<>();
        visibleScopes.add(PUBLIC_SCOPE);
        visibleScopes.addAll(loadKnownUsernames());

        File[] scopeFolders = areaFolder.toPath().resolve(SIMULATION_DATA_FOLDER).toFile().listFiles();
        if (scopeFolders == null) {
            return;
        }
        Arrays.sort(scopeFolders, Comparator.comparing((File file) -> !PUBLIC_SCOPE.equals(file.getName())).thenComparing(File::getName));
        List<Scheme> temp = new ArrayList<>();
        for (File scopeFolder : scopeFolders) {
            if (!isModelFolderCandidate(scopeFolder) || !visibleScopes.contains(scopeFolder.getName())) {
                continue;
            }
            File[] modelFolders = scopeFolder.listFiles();
            if (modelFolders == null) {
                continue;
            }
            Arrays.sort(modelFolders, Comparator.comparing(File::getName));
            for (File modelFolder : modelFolders) {
                if (!isModelFolderCandidate(modelFolder)) {
                    continue;
                }
                Scheme scheme = buildScheme(areaFolder.getName(), scopeFolder.getName(), modelFolder.getName(), modelFolder);
                if (scheme != null) {
                    temp.add(scheme);
                }
            }
        }
        for (Scheme scheme : temp) {
            schemes.put(scheme.getName(), scheme);
        }
    }

    private boolean isModelFolderCandidate(File folder) {
        String name = folder.getName();
        return folder.isDirectory() && !name.equals("temp") && !name.startsWith(".");
    }

    private Scheme buildScheme(String areaName, String scope, String modelName, File data) {
        String output = data.getAbsolutePath() + "/output";
        if (!new File(output).exists()) {
            return null;
        }

        String key = areaName + "/" + scope + "/" + modelName;
        Scheme scheme = new Scheme();
        scheme.setFolder(data.getAbsolutePath());
        scheme.setInput(data.getAbsolutePath() + "/input");
        scheme.setOutput(output);
        scheme.setCache(cachePath(areaName, scope, modelName).toString());
        OutputSummary outputSummary = summarizeOutput(new File(output));
        scheme.setOutputBytes(outputSummary.bytes());
        Scheme.Desc desc = readDesc(key, data);
        boolean detectedLargeModel = outputSummary.bytes() >= Math.max(1L, largeModelThresholdBytes)
                || outputSummary.plansBytes() >= Math.max(1L, largeModelPlansThresholdBytes)
                || outputSummary.eventsBytes() >= Math.max(1L, largeModelEventsThresholdBytes)
                || outputSummary.analysisTableMaxBytes() >= Math.max(1L, largeModelAnalysisTableThresholdBytes)
                || cachedPersonTrackCount(areaName, scope, modelName) >= Math.max(1L, largeModelPersonTrackThreshold);
        // events/plans 可在整套缓存成功后由管理员人工归档。重启时 output
        // 体积因此会骤降，不能把原大模型误判成小模型并改变加载策略。
        boolean cachedLargeModel = cachedReadyLargeModel(areaName, scope, modelName);
        boolean largeModel = detectedLargeModel || cachedLargeModel || Boolean.TRUE.equals(desc.getLargeModel());
        if (detectedLargeModel && Boolean.FALSE.equals(desc.getLargeModel())) {
            log.warn("[{}] desc.largeModel=false 不能降级自动识别的大模型，继续使用低内存模式", key);
        }
        scheme.setLargeModel(largeModel);
        // cuttable 保持“存在 output plans”的文件能力语义；API 展示与切分入口另行叠加 !largeModel，
        // 防止大模型公交精简网被道路优化误用。
        scheme.setCuttable(outputSummary.cuttable());
        scheme.setDesc(desc);
        scheme.setName(key);
        scheme.setScope(scope);
        scheme.setSchemeName(areaName);
        scheme.setModelName(modelName);
        scheme.setDisplayName(modelName);
        return scheme;
    }

    private boolean cachedReadyLargeModel(String areaName, String scope, String modelName) {
        Path manifest = cachePath(areaName, scope, modelName).resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) {
            return false;
        }
        try {
            Map<?, ?> cached = JSON.parseObject(Files.readString(manifest), Map.class);
            return cached != null
                    && "ready".equals(String.valueOf(cached.get("status")))
                    && Boolean.TRUE.equals(cached.get("largeModel"));
        } catch (Exception e) {
            log.warn("读取已缓存的大模型标记失败: {}", manifest, e);
            return false;
        }
    }

    public Path cacheRootPath() {
        if (cacheFolder != null && !cacheFolder.isBlank()) {
            return Path.of(cacheFolder).toAbsolutePath().normalize();
        }
        Path dataRoot = Path.of(folder).toAbsolutePath().normalize();
        Path parent = dataRoot.getParent();
        if (parent != null) {
            return parent.resolve("pt_cache").toAbsolutePath().normalize();
        }
        return dataRoot.resolve(".pt_cache").toAbsolutePath().normalize();
    }

    public long largeModelThresholdBytes() {
        return largeModelThresholdBytes;
    }

    public long largeModelPlansThresholdBytes() {
        return largeModelPlansThresholdBytes;
    }

    public long largeModelEventsThresholdBytes() {
        return largeModelEventsThresholdBytes;
    }

    public long largeModelAnalysisTableThresholdBytes() {
        return largeModelAnalysisTableThresholdBytes;
    }

    public long largeModelPersonTrackThreshold() {
        return largeModelPersonTrackThreshold;
    }

    public Path cachePath(String areaName, String scope, String modelName) {
        validateAreaName(areaName);
        validatePathSegment(scope, "模型来源无效");
        validatePathSegment(modelName, "模型名称无效");
        return cacheRootPath().resolve(areaName).resolve(scope).resolve(modelName).normalize();
    }

    private void validatePathSegment(String value, String message) {
        if (value == null || value.isBlank() || value.contains("..") || value.contains("/") || value.contains("\\")) {
            throw new BusinessException(message);
        }
    }

    private OutputSummary summarizeOutput(File output) {
        File[] files = output.listFiles(file -> file.isFile() && !file.getName().startsWith(".") && !file.getName().startsWith("._"));
        if (files == null) {
            return new OutputSummary(0L, 0L, 0L, 0L, false);
        }
        long total = 0L;
        long plansBytes = 0L;
        long eventsBytes = 0L;
        long analysisTableMaxBytes = 0L;
        boolean cuttable = false;
        for (File file : files) {
            long length = Math.max(0L, file.length());
            total = total > Long.MAX_VALUE - length ? Long.MAX_VALUE : total + length;
            String lower = file.getName().toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("plans")) {
                cuttable = true;
                plansBytes = plansBytes > Long.MAX_VALUE - length ? Long.MAX_VALUE : plansBytes + length;
            }
            if (lower.contains("events")) {
                eventsBytes = eventsBytes > Long.MAX_VALUE - length ? Long.MAX_VALUE : eventsBytes + length;
            }
            if (lower.endsWith(".csv") || lower.endsWith(".csv.gz")) {
                if (lower.contains("legs") || lower.contains("trips")
                        || lower.contains("activities") || lower.contains("persons")) {
                    analysisTableMaxBytes = Math.max(analysisTableMaxBytes, length);
                }
            }
        }
        return new OutputSummary(total, plansBytes, eventsBytes, analysisTableMaxBytes, cuttable);
    }

    private record OutputSummary(
            long bytes,
            long plansBytes,
            long eventsBytes,
            long analysisTableMaxBytes,
            boolean cuttable
    ) {
    }

    /**
     * 已生成的乘客磁盘工件是比压缩文件大小更可靠的规模信号。即使顶层 manifest
     * 因上次 OOM 为 failed，也必须沿用低内存模式，不能在下一次启动时再次 eager 加载。
     */
    private long cachedPersonTrackCount(String areaName, String scope, String modelName) {
        Path modelCache = cachePath(areaName, scope, modelName);
        if (!Files.isDirectory(modelCache)) return -1L;
        try (var children = Files.list(modelCache)) {
            for (Path dir : children
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith("pt-events-v"))
                    .toList()) {
                Path manifest = dir.resolve("manifest.json");
                if (!Files.isRegularFile(manifest)) continue;
                Map<?, ?> cached = JSON.parseObject(Files.readString(manifest), Map.class);
                Object value = cached == null ? null : cached.get("trackCount");
                if (value instanceof Number number) return number.longValue();
                if (value != null) {
                    try {
                        return Long.parseLong(String.valueOf(value));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (Exception e) {
            log.warn("读取乘客工件规模失败: model={}/{}/{}, error={}",
                    areaName, scope, modelName, e.getMessage());
        }
        return -1L;
    }

    private Set<String> loadKnownUsernames() {
        File store = new File(folder, ".gjcxfzksh-users.json");
        if (!store.exists()) {
            return Set.of();
        }
        try {
            String text = Files.readString(store.toPath());
            Map<?, ?> data = JSON.parseObject(text, Map.class);
            Object users = data == null ? null : data.get("users");
            if (users instanceof Map<?, ?> userMap) {
                Set<String> names = new HashSet<>();
                userMap.keySet().forEach(key -> {
                    if (key != null) {
                        names.add(String.valueOf(key));
                    }
                });
                return names;
            }
            throw new BusinessException("用户列表格式无效: " + store);
        } catch (Exception e) {
            if (e instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException("读取用户列表失败: " + store, e);
        }
    }

    private Scheme.Desc readDesc(String key, File data) {
        String descFile = data.getAbsolutePath() + "/desc.json";
        File desc = new File(descFile);
        if (!desc.exists()) {
            return defaultDesc();
        }
        try {
            Scheme.Desc parsed = JSON.parseObject(Files.readString(desc.toPath()), Scheme.Desc.class);
            if (parsed == null) {
                throw new BusinessException("模型描述为空: " + desc);
            }
            return parsed;
        } catch (Exception e) {
            if (e instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException("模型描述解析失败: " + key + " (" + desc + ")", e);
        }
    }

    private static Scheme.Desc defaultDesc() {
        Scheme.Desc d = new Scheme.Desc();
        d.set_default(false);
        d.setDetail("");
        d.setScale(1);
        d.setArea(0);
        return d;
    }

}
