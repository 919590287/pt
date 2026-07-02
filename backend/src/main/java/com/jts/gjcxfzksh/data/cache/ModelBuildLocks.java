package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.data.MatsimData;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 缓存构建的 per-model 锁：替代各 Matsim*Cache 的类级 static synchronized 全局锁。
 * 模型 A 的缓存构建（可能持续数分钟）不再阻塞模型 B 的同类构建；
 * 同一模型同一 scope 仍然串行，保证构建幂等。
 */
final class ModelBuildLocks {

    private static final ConcurrentMap<String, Object> LOCKS = new ConcurrentHashMap<>();

    private ModelBuildLocks() {
    }

    static Object lockFor(String scope, MatsimData data) {
        return LOCKS.computeIfAbsent(scope + "|" + data.getName(), key -> new Object());
    }
}
