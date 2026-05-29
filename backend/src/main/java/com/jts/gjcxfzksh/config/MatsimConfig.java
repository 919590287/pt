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
import java.io.FileInputStream;
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
    private long largeModelThresholdBytes;

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

        Set<String> knownUsernames = loadKnownUsernames();
        File[] areaFolders = root.listFiles();
        if (areaFolders == null) {
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
        long outputBytes = estimateOutputBytes(new File(output));
        scheme.setOutputBytes(outputBytes);
        scheme.setLargeModel(outputBytes >= largeModelThresholdBytes);
        scheme.setDesc(readDesc(key, data));
        scheme.setName(key);
        scheme.setScope(scope);
        scheme.setSchemeName(areaName);
        scheme.setModelName(modelName);
        scheme.setDisplayName(modelName);
        return scheme;
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

    private long estimateOutputBytes(File output) {
        File[] files = output.listFiles(file -> file.isFile() && !file.getName().startsWith(".") && !file.getName().startsWith("._"));
        if (files == null) {
            return 0L;
        }
        long total = 0L;
        for (File file : files) {
            total += Math.max(0L, file.length());
        }
        return total;
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
        } catch (Exception e) {
            log.warn("读取用户列表失败，跳过用户模型扫描", e);
        }
        return Set.of();
    }

    private Scheme.Desc readDesc(String key, File data) {
        String descFile = data.getAbsolutePath() + "/desc.json";
        File desc = new File(descFile);
        if (!desc.exists()) {
            return defaultDesc();
        }
        try {
            return JSON.parseObject(new FileInputStream(desc), Scheme.Desc.class);
        } catch (Exception e) {
            log.warn("[{}]使用默认描述json", key);
            return defaultDesc();
        }
    }

    private static Scheme.Desc defaultDesc() {
        Scheme.Desc d = new Scheme.Desc();
        d.set_default(false);
        d.setDetail("");
        d.setScale(1);
        d.setArea(1);
        return d;
    }

}
