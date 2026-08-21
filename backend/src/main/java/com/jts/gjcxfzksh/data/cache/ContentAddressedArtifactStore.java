package com.jts.gjcxfzksh.data.cache;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 不变派生工件的内容寻址库。
 *
 * <p>模型目录仍保留原有文件路径，但相同 SHA-256 的内容会被替换为指向
 * {@code cacheRoot/.cas-v1/sha256} 中同一对象的硬链接；ExFAT 等不支持硬链接的文件系统
 * 自动使用相对符号链接。这样不改读路径、不增加解码成本，同时允许跨模型物理去重。
 * 发布时全部使用原子 rename，并且不触碰原始输入目录。</p>
 */
@Slf4j
final class ContentAddressedArtifactStore {

    static final String STORE_VERSION = "cas-v1";
    private static final int HASH_BUFFER_BYTES = 4 * 1024 * 1024;

    private ContentAddressedArtifactStore() {
    }

    static Result publish(Path cacheRoot, Path modelDir, long minimumBytes) throws Exception {
        Path normalizedRoot = cacheRoot.toAbsolutePath().normalize();
        Path normalizedModel = modelDir.toAbsolutePath().normalize();
        if (!normalizedModel.startsWith(normalizedRoot) || normalizedModel.equals(normalizedRoot)) {
            throw new IllegalArgumentException("CAS 模型目录越界: " + normalizedModel);
        }
        Path objects = normalizedRoot.resolve("." + STORE_VERSION).resolve("sha256");
        Files.createDirectories(objects);

        List<Artifact> artifacts = new ArrayList<>();
        long logicalBytes = 0L;
        long linkedBytes = 0L;
        long reusedBytes = 0L;
        try (var paths = Files.walk(normalizedModel)) {
            for (Path path : paths
                    .filter(candidate -> Files.isRegularFile(candidate) || Files.isSymbolicLink(candidate))
                    .filter(ContentAddressedArtifactStore::immutableArtifact)
                    .sorted(Comparator.naturalOrder())
                    .toList()) {
                ExistingCasLink existingLink = existingCasLink(path, objects);
                if (existingLink != null) {
                    long bytes = Files.size(path);
                    if (bytes >= Math.max(1L, minimumBytes)) {
                        artifacts.add(new Artifact(relative(normalizedModel, path), existingLink.sha256(), bytes, true));
                        logicalBytes += bytes;
                        linkedBytes += bytes;
                    }
                    continue;
                }
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) continue;
                long bytes = Files.size(path);
                if (bytes < Math.max(1L, minimumBytes)) continue;
                String hash = sha256(path);
                Path object = objects.resolve(hash.substring(0, 2)).resolve(hash.substring(2));
                Files.createDirectories(object.getParent());
                boolean reused = Files.exists(object);
                boolean alreadyLinked = reused && Files.isSameFile(path, object);
                if (!reused) {
                    Path temporaryObject = object.resolveSibling(object.getFileName()
                            + ".tmp-" + UUID.randomUUID());
                    try {
                        // APFS/NTFS 从新工件直接建立硬链接；ExFAT 没有硬链接语义，
                        // 先复制一次到 CAS，随后原模型路径会被相对符号链接替换。
                        try {
                            Files.createLink(temporaryObject, path);
                        } catch (Exception noHardLink) {
                            Files.copy(path, temporaryObject, StandardCopyOption.COPY_ATTRIBUTES);
                        }
                        try {
                            Files.move(temporaryObject, object, StandardCopyOption.ATOMIC_MOVE);
                        } catch (FileAlreadyExistsException race) {
                            Files.deleteIfExists(temporaryObject);
                            reused = true;
                        } catch (Exception noAtomic) {
                            try {
                                Files.move(temporaryObject, object);
                            } catch (FileAlreadyExistsException race) {
                                Files.deleteIfExists(temporaryObject);
                                reused = true;
                            }
                        }
                    } catch (Exception unsupported) {
                        Files.deleteIfExists(temporaryObject);
                        log.warn("内容寻址对象发布失败，已保留原工件: {} error={}",
                                path, unsupported.getMessage());
                        artifacts.add(new Artifact(relative(normalizedModel, path), hash, bytes, false));
                        logicalBytes += bytes;
                        continue;
                    }
                }
                if (!Files.isSameFile(path, object)) {
                    Path replacement = path.resolveSibling(path.getFileName()
                            + ".cas-link-" + UUID.randomUUID());
                    try {
                        try {
                            Files.createLink(replacement, object);
                        } catch (Exception noHardLink) {
                            Path relativeTarget = replacement.getParent().relativize(object);
                            Files.createSymbolicLink(replacement, relativeTarget);
                        }
                        try {
                            Files.move(replacement, path,
                                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                        } catch (Exception noAtomic) {
                            Files.move(replacement, path, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (Exception noLink) {
                        Files.deleteIfExists(replacement);
                        log.warn("缓存文件系统不支持硬链接或符号链接，已保留原工件: {} error={}",
                                path, noLink.getMessage());
                        artifacts.add(new Artifact(relative(normalizedModel, path), hash, bytes, false));
                        logicalBytes += bytes;
                        continue;
                    }
                }
                artifacts.add(new Artifact(relative(normalizedModel, path), hash, bytes, true));
                logicalBytes += bytes;
                linkedBytes += bytes;
                // 只记录本次将“另一个模型文件”指向已有对象的收益。
                // 同一模型的已有硬链接在增量发布时不能重复计数。
                if (reused && !alreadyLinked) reusedBytes += bytes;
            }
        }
        return new Result(List.copyOf(artifacts), logicalBytes, linkedBytes, reusedBytes);
    }

    /**
     * 增量发布时模型目录中的 ExFAT 工件已是指向 CAS 的符号链接。
     * 该路径不应再解压/哈希，但必须重新记入规范工件账本。
     */
    private static ExistingCasLink existingCasLink(Path path, Path objects) {
        if (!Files.isSymbolicLink(path)) return null;
        try {
            Path target = path.getParent().resolve(Files.readSymbolicLink(path)).normalize();
            if (!target.startsWith(objects) || !Files.isRegularFile(target)) return null;
            Path relative = objects.relativize(target);
            if (relative.getNameCount() != 2) return null;
            String hash = relative.getName(0) + relative.getName(1).toString();
            if (!hash.matches("[0-9a-f]{64}")) return null;
            return new ExistingCasLink(hash);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean immutableArtifact(Path path) {
        String name = path.getFileName().toString();
        // ExFAT 上 macOS 会为扩展属性生成 AppleDouble `._*` 伴生文件；它不是业务工件，
        // 也不能进入跨模型内容寻址集合。
        if (name.startsWith(".")) return false;
        if (name.endsWith(".tmp") || name.contains(".building-") || name.contains(".cas-link-")) return false;
        return !"manifest.json".equals(name)
                && !"manifest-lite.json".equals(name)
                && !"repair-required.json".equals(name)
                && !"canonical-artifacts.json".equals(name);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[HASH_BUFFER_BYTES];
        try (InputStream in = Files.newInputStream(path, StandardOpenOption.READ)) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString();
    }

    record Artifact(String path, String sha256, long bytes, boolean linked) {
    }

    private record ExistingCasLink(String sha256) {
    }

    record Result(List<Artifact> artifacts, long logicalBytes, long linkedBytes, long reusedBytes) {
    }
}
