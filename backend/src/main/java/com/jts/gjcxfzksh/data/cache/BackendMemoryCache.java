package com.jts.gjcxfzksh.data.cache;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 后端统一字节治理 LRU。所有实例共用一个全局上限，同时保留分区上限；
 * 大 byte[] 或巨型 JSON 不再与小索引同按“1 条”计算。
 */
public final class BackendMemoryCache<K, V> {

    private static final Object LOCK = new Object();
    private static final long GLOBAL_MAX_BYTES = configuredGlobalMaxBytes();
    private static final LinkedHashMap<GlobalKey, Entry> GLOBAL =
            new LinkedHashMap<>(128, 0.75f, true);
    private static final AtomicLong OWNER_IDS = new AtomicLong();
    private static long globalBytes;

    private final String namespace;
    // 相同命名空间的多个 service/测试实例不能互相读到业务对象；owner 只隔离 key，
    // 所有 owner 仍由同一个 GLOBAL 字节上限统一治理。
    private final long owner = OWNER_IDS.incrementAndGet();
    private final long maxBytes;
    private final ToLongFunction<V> weigher;
    private final BiConsumer<K, V> removalListener;
    /** 只在全局 LOCK 下读写；避免每次 put 为分区限额重新扫描整个全局 LRU。 */
    private long ownedBytes;
    /** 每个 owner 自己的访问序 LRU；分区淘汰不再从全局几十万条目中查找所属项。 */
    private final LinkedHashMap<GlobalKey, Boolean> ownedLru =
            new LinkedHashMap<>(128, 0.75f, true);

    public BackendMemoryCache(String namespace, long maxBytes, ToLongFunction<V> weigher) {
        this(namespace, maxBytes, weigher, (key, value) -> { });
    }

    public BackendMemoryCache(
            String namespace,
            long maxBytes,
            ToLongFunction<V> weigher,
            BiConsumer<K, V> removalListener
    ) {
        this.namespace = Objects.requireNonNull(namespace);
        this.maxBytes = Math.max(1L, maxBytes);
        this.weigher = Objects.requireNonNull(weigher);
        this.removalListener = Objects.requireNonNull(removalListener);
    }

    public V get(K key) {
        synchronized (LOCK) {
            GlobalKey globalKey = new GlobalKey(namespace, owner, key);
            Entry entry = GLOBAL.get(globalKey);
            if (entry != null) ownedLru.get(globalKey);
            @SuppressWarnings("unchecked") V value = entry == null ? null : (V) entry.value;
            return value;
        }
    }

    public void put(K key, V value) {
        if (key == null || value == null) return;
        long bytes = Math.max(1L, weigher.applyAsLong(value));
        if (bytes > maxBytes || bytes > GLOBAL_MAX_BYTES) return;
        synchronized (LOCK) {
            GlobalKey globalKey = new GlobalKey(namespace, owner, key);
            Entry previous = GLOBAL.remove(globalKey);
            discard(previous);
            GLOBAL.put(globalKey, new Entry(value, bytes, () -> {
                ownedBytes -= bytes;
                ownedLru.remove(globalKey);
                removalListener.accept(key, value);
            }));
            ownedLru.put(globalKey, Boolean.TRUE);
            globalBytes += bytes;
            ownedBytes += bytes;
            evictNamespace();
            evictGlobal();
        }
    }

    public V computeIfAbsent(K key, Function<K, V> loader) {
        V cached = get(key);
        if (cached != null) return cached;
        V loaded = loader.apply(key);
        put(key, loaded);
        return loaded;
    }

    public V remove(K key) {
        synchronized (LOCK) {
            Entry removed = GLOBAL.remove(new GlobalKey(namespace, owner, key));
            discard(removed);
            @SuppressWarnings("unchecked") V value = removed == null ? null : (V) removed.value;
            return value;
        }
    }

    public void removeIf(Predicate<K> predicate) {
        synchronized (LOCK) {
            Iterator<Map.Entry<GlobalKey, Entry>> iterator = GLOBAL.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<GlobalKey, Entry> candidate = iterator.next();
                if (!owns(candidate.getKey())) continue;
                @SuppressWarnings("unchecked") K key = (K) candidate.getKey().key;
                if (predicate.test(key)) {
                    Entry removed = candidate.getValue();
                    iterator.remove();
                    discard(removed);
                }
            }
        }
    }

    public void clear() {
        removeIf(ignored -> true);
    }

    public int size() {
        synchronized (LOCK) {
            return ownedLru.size();
        }
    }

    public Set<K> keys() {
        synchronized (LOCK) {
            Set<K> result = new LinkedHashSet<>();
            for (GlobalKey candidate : GLOBAL.keySet()) {
                if (!owns(candidate)) continue;
                @SuppressWarnings("unchecked") K key = (K) candidate.key;
                result.add(key);
            }
            return result;
        }
    }

    public Map<K, V> snapshot() {
        synchronized (LOCK) {
            Map<K, V> result = new LinkedHashMap<>();
            for (Map.Entry<GlobalKey, Entry> candidate : GLOBAL.entrySet()) {
                if (!owns(candidate.getKey())) continue;
                @SuppressWarnings("unchecked") K key = (K) candidate.getKey().key;
                @SuppressWarnings("unchecked") V value = (V) candidate.getValue().value;
                result.put(key, value);
            }
            return result;
        }
    }

    public long bytes() {
        synchronized (LOCK) {
            return ownedBytes;
        }
    }

    public static long totalBytes() {
        synchronized (LOCK) {
            return globalBytes;
        }
    }

    public static long maxBytes() {
        return GLOBAL_MAX_BYTES;
    }

    public static long estimate(Object value) {
        return estimate(value, new IdentityHashMap<>(), 0);
    }

    private void evictNamespace() {
        while (ownedBytes > maxBytes) {
            Iterator<GlobalKey> iterator = ownedLru.keySet().iterator();
            if (!iterator.hasNext()) return;
            GlobalKey oldest = iterator.next();
            iterator.remove();
            discard(GLOBAL.remove(oldest));
        }
    }

    private static void evictGlobal() {
        Iterator<Map.Entry<GlobalKey, Entry>> iterator = GLOBAL.entrySet().iterator();
        while (globalBytes > GLOBAL_MAX_BYTES && iterator.hasNext()) {
            Map.Entry<GlobalKey, Entry> candidate = iterator.next();
            Entry removed = candidate.getValue();
            iterator.remove();
            discard(removed);
        }
    }

    private static long configuredGlobalMaxBytes() {
        long fallback = 512L * 1024 * 1024;
        String environment = System.getenv("BACKEND_MEMORY_CACHE_MAX_BYTES");
        if (environment != null && !environment.isBlank()) {
            try {
                fallback = Long.parseLong(environment.trim());
            } catch (NumberFormatException ignored) {
                // Invalid deployment input falls back to the safe default; the JVM property can still override it.
            }
        }
        return Math.max(64L * 1024 * 1024,
                Long.getLong("gjcxfzksh.backend-cache.max-bytes", fallback));
    }

    private boolean owns(GlobalKey key) {
        return namespace.equals(key.namespace) && owner == key.owner;
    }

    private static void discard(Entry entry) {
        if (entry == null) return;
        globalBytes -= entry.bytes;
        try {
            entry.removal.run();
        } catch (RuntimeException ignored) {
            // 缓存淘汰回调不能破坏业务请求或其他命名空间的全局治理。
        }
    }

    private static long estimate(Object value, IdentityHashMap<Object, Boolean> seen, int depth) {
        if (value == null || depth > 12 || seen.put(value, Boolean.TRUE) != null) return 0L;
        if (value instanceof byte[] bytes) return 16L + bytes.length;
        if (value instanceof String text) return 40L + text.length() * 2L;
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) return 24L;
        Class<?> type = value.getClass();
        if (type.isArray()) {
            int length = Array.getLength(value);
            if (type.getComponentType().isPrimitive()) {
                int width = type.getComponentType() == long.class || type.getComponentType() == double.class ? 8
                        : type.getComponentType() == int.class || type.getComponentType() == float.class ? 4
                        : type.getComponentType() == short.class || type.getComponentType() == char.class ? 2 : 1;
                return 16L + (long) length * width;
            }
            long bytes = 16L + length * 8L;
            for (int i = 0; i < length; i++) bytes += estimate(Array.get(value, i), seen, depth + 1);
            return bytes;
        }
        if (value instanceof Map<?, ?> map) {
            long bytes = 64L + map.size() * 40L;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                bytes += estimate(entry.getKey(), seen, depth + 1);
                bytes += estimate(entry.getValue(), seen, depth + 1);
            }
            return bytes;
        }
        if (value instanceof Collection<?> collection) {
            long bytes = 32L + collection.size() * 8L;
            for (Object item : collection) bytes += estimate(item, seen, depth + 1);
            return bytes;
        }
        return 64L;
    }

    private record GlobalKey(String namespace, long owner, Object key) {
    }

    private record Entry(Object value, long bytes, Runnable removal) {
    }
}
