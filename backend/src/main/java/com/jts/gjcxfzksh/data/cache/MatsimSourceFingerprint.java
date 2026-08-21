package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.data.MatsimData;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 源文件内容指纹。小文件计算完整 SHA-256；大文件等距取样 16 个窗口。
 * 指纹只取决于内容与大小，不包含绝对路径、mtime 或 fileKey，因此进程重启、文件触碰、
 * 外置盘重新挂载以及同内容文件迁移都不会误触发缓存重建。
 * 结果短时缓存，但 TTL 到期后即使元数据未变也会重新取样，可识别保留时间戳的原地覆盖。
 */
@Slf4j
public final class MatsimSourceFingerprint {

    public static final String SCHEMA = "sampled-content-sha256-v3";
    private static final int SAMPLE_BYTES = 256 << 10;
    private static final int SAMPLE_WINDOWS = 16;
    private static final long FULL_HASH_LIMIT = 8L << 20;
    private static final long TTL_MS = Long.getLong("gjcxfzksh.source-fingerprint-ttl-ms", 60_000L);
    private static final BackendMemoryCache<Path, Cached> CACHE =
            new BackendMemoryCache<>("source-fingerprints", 8L * 1024 * 1024, ignored -> 160L);

    private MatsimSourceFingerprint() {
    }

    public static String signature(String filePath) {
        return filePath == null || filePath.isBlank() ? "missing" : signature(Path.of(filePath));
    }

    public static String signature(Path input) {
        if (input == null) return "missing";
        Path path = input.toAbsolutePath().normalize();
        // 存在性必须在 TTL 记忆缓存之前检查。否则管理员刚归档
        // events/plans 后，已加载模型还会在最多 60 秒内拿到删除前的指纹。
        if (!Files.isRegularFile(path)) return "missing";
        long now = System.currentTimeMillis();
        Cached cached = CACHE.get(path);
        if (cached != null && cached.expiresAt > now) return cached.signature;
        try {
            long size = Files.size(path);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(SCHEMA.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(size).array());
            if (size <= FULL_HASH_LIMIT) {
                try (InputStream in = Files.newInputStream(path)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) >= 0) digest.update(buffer, 0, read);
                }
            } else {
                try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
                    long maxOffset = Math.max(0L, size - SAMPLE_BYTES);
                    for (int i = 0; i < SAMPLE_WINDOWS; i++) {
                        long offset = SAMPLE_WINDOWS == 1 ? 0L : maxOffset * i / (SAMPLE_WINDOWS - 1L);
                        sample(channel, digest, offset, size);
                    }
                }
            }
            String signature = HexFormat.of().formatHex(digest.digest());
            CACHE.put(path, new Cached(signature, now + Math.max(1_000L, TTL_MS)));
            return signature;
        } catch (Exception e) {
            throw new IllegalStateException("源文件内容指纹计算失败: " + path, e);
        }
    }

    public static String modelRevision(MatsimData data) {
        if (data == null || data.getOutfile() == null) return "missing";
        String joined = String.join("|",
                signature(data.getOutfile().getSourceConfig()),
                signature(data.getOutfile().getNetwork()),
                signature(data.getOutfile().getTransitSchedule()),
                signature(data.getOutfile().getTransitVehicles()),
                signature(data.getOutfile().getEvents()),
                signature(data.getOutfile().getPlans()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(joined.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("模型版本指纹计算失败", e);
        }
    }

    public static void invalidateAll() {
        CACHE.clear();
    }

    /**
     * 比较组件 manifest 中的扁平源指纹。以 Size + Signature 为准；File 和 Modified
     * 仅保留作诊断。缺少内容签名的旧清单直接失效并重建。
     */
    public static boolean sameFlatFingerprint(Map<String, Object> expected, Map<String, Object> actual) {
        if (expected == null || actual == null) return false;
        for (Map.Entry<String, Object> entry : expected.entrySet()) {
            String key = entry.getKey();
            String base = fingerprintBase(key);
            if (isArchivedHeavySource(base, expected, actual)) {
                // events/plans 是生成派生缓存的原始大文件。完整缓存已记录其
                // 内容指纹后，允许管理员人工将文件移到冷存储。若文件仍在，则仍
                // 严格比较 Size + Signature；若之后恢复了不同内容，也会正常失效。
                continue;
            }
            boolean hasCurrentSignature = base != null && expected.containsKey(base + "Signature");
            if (hasCurrentSignature && (key.endsWith("File") || key.endsWith("Modified"))) {
                continue;
            }
            Object stored = actual.get(key);
            if (!sameValue(entry.getValue(), stored)) return false;
        }
        return true;
    }

    /** 比较嵌套的单个源文件记录；内容签名存在时忽略路径和 mtime。 */
    public static boolean sameSourceItem(Map<?, ?> expected, Map<?, ?> actual) {
        if (expected == null || actual == null) return false;
        Object storedSignature = actual.get("signature");
        if (storedSignature == null) return false;
        return sameValue(expected.get("size"), actual.get("size"))
                && sameValue(expected.get("signature"), storedSignature);
    }

    /**
     * 比较带源名称的嵌套指纹。events/plans 在旧清单中有有效指纹、
     * 但当前已缺失时，视为管理员人工归档；其他源文件仍严格校验。
     */
    public static boolean sameSourceItem(String sourceName, Map<?, ?> expected, Map<?, ?> actual) {
        if (isHeavySource(sourceName) && isMissingItem(expected) && hasStoredContent(actual)) {
            return true;
        }
        return sameSourceItem(expected, actual);
    }

    private static boolean isArchivedHeavySource(String base, Map<String, Object> current,
                                                  Map<String, Object> stored) {
        if (!isHeavySource(base)) return false;
        Object currentSignature = current.get(base + "Signature");
        Object storedSignature = stored.get(base + "Signature");
        return "missing".equals(String.valueOf(currentSignature))
                && storedSignature != null
                && !"missing".equals(String.valueOf(storedSignature));
    }

    private static boolean isHeavySource(String sourceName) {
        return sourceName != null
                && ("events".equalsIgnoreCase(sourceName) || "plans".equalsIgnoreCase(sourceName));
    }

    private static boolean isMissingItem(Map<?, ?> item) {
        return item != null && "missing".equals(String.valueOf(item.get("signature")));
    }

    private static boolean hasStoredContent(Map<?, ?> item) {
        if (item == null) return false;
        Object signature = item.get("signature");
        return signature != null && !"missing".equals(String.valueOf(signature));
    }

    private static String fingerprintBase(String key) {
        if (key == null) return null;
        for (String suffix : new String[]{"Signature", "Modified", "Size", "File"}) {
            if (key.endsWith(suffix)) return key.substring(0, key.length() - suffix.length());
        }
        return null;
    }

    private static boolean sameValue(Object expected, Object actual) {
        if (expected instanceof Number expectedNumber && actual instanceof Number actualNumber) {
            return expectedNumber.longValue() == actualNumber.longValue();
        }
        return String.valueOf(expected).equals(String.valueOf(actual));
    }

    private static void sample(SeekableByteChannel channel, MessageDigest digest, long offset, long size) throws Exception {
        channel.position(Math.min(offset, size));
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(offset).array());
        ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(SAMPLE_BYTES, Math.max(0, size - offset)));
        while (buffer.hasRemaining() && channel.read(buffer) > 0) {
            // continue
        }
        digest.update(buffer.array(), 0, buffer.position());
    }

    private record Cached(String signature, long expiresAt) {
    }
}
