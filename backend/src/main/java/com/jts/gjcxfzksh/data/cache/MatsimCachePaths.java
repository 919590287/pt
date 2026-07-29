package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.entry.Scheme;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class MatsimCachePaths {

    private MatsimCachePaths() {
    }

    public static Path modelDir(MatsimData data) {
        return Path.of(data.getCacheFolder()).toAbsolutePath().normalize();
    }

    public static Path modelDir(Scheme scheme) {
        return Path.of(scheme.getCache()).toAbsolutePath().normalize();
    }

    public static Path versionDir(MatsimData data, String version) {
        return modelDir(data).resolve(version);
    }

    public static Path manifestPath(MatsimData data) {
        return modelDir(data).resolve("manifest.json");
    }

    public static Path manifestPath(Scheme scheme) {
        return modelDir(scheme).resolve("manifest.json");
    }

    /**
     * 重建组件缓存时清空当前版本目录后原位重建，防止旧分片或临时文件与新工件并存。
     * 目标被严格限制为 modelDir 下的单个 *-vN 目录。
     */
    public static Path recreateVersionDir(MatsimData data, String version) throws Exception {
        Path root = modelDir(data);
        Path target = checkedVersionDir(root, version);
        deleteTree(target);
        Files.createDirectories(target);
        return target;
    }

    /** 成功发布新版本后删除同组件的其他版本，最终磁盘上只保留一个正式版本。 */
    public static void deleteOtherVersions(MatsimData data, String familyPrefix, String keepVersion) throws Exception {
        Path root = modelDir(data);
        if (!Files.isDirectory(root) || familyPrefix == null || !familyPrefix.matches("[a-z][a-z0-9-]*-v")) {
            return;
        }
        Path keep = checkedVersionDir(root, keepVersion);
        try (var children = Files.list(root)) {
            List<Path> obsolete = children
                    .filter(Files::isDirectory)
                    .filter(path -> !path.equals(keep))
                    .filter(path -> path.getFileName().toString().startsWith(familyPrefix))
                    .filter(path -> path.getFileName().toString().matches("[a-z][a-z0-9-]*-v\\d+"))
                    .toList();
            for (Path path : obsolete) deleteTree(path);
        }
    }

    private static Path checkedVersionDir(Path root, String version) {
        if (version == null || !version.matches("[a-z][a-z0-9-]*-v\\d+")) {
            throw new IllegalArgumentException("非法缓存版本目录: " + version);
        }
        Path target = root.resolve(version).toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("缓存版本目录越界: " + target);
        }
        return target;
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
