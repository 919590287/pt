package com.jts.gjcxfzksh.api.common;

import org.springframework.http.CacheControl;

import java.util.concurrent.TimeUnit;

/**
 * 只读二进制接口（tile.bin / full.bin / chunk.bin）的 HTTP 缓存工具：
 * 强校验 ETag + immutable 长缓存 + cachePrivate（需鉴权资源禁止中间共享缓存存储）。
 */
public final class HttpCacheSupport {

    private HttpCacheSupport() {
    }

    public static CacheControl immutablePrivate() {
        return CacheControl.maxAge(365, TimeUnit.DAYS).cachePrivate().immutable();
    }

    public static boolean etagMatches(String etag, String ifNoneMatch) {
        if (etag == null || ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String token : ifNoneMatch.split(",")) {
            String candidate = token.trim();
            if ("*".equals(candidate) || etag.equals(candidate)) {
                return true;
            }
        }
        return false;
    }
}
