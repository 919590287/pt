package com.jts.gjcxfzksh.data.cache;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendMemoryCacheTest {

    @Test
    void evictsByBytesAndRefreshesLruOnRead() {
        BackendMemoryCache<String, byte[]> cache = new BackendMemoryCache<>(
                "byte-test-" + UUID.randomUUID(), 10L, value -> value.length);

        cache.put("a", new byte[5]);
        cache.put("b", new byte[5]);
        assertEquals(10L, cache.bytes());
        assertEquals(5, cache.get("a").length); // a 变为最新

        cache.put("c", new byte[5]);

        assertNull(cache.get("b"));
        assertTrue(cache.keys().containsAll(List.of("a", "c")));
        assertEquals(10L, cache.bytes());
        cache.clear();
    }

    @Test
    void rejectsSingleValueAboveNamespaceBudgetAndEstimatesNestedPayloads() {
        BackendMemoryCache<String, byte[]> cache = new BackendMemoryCache<>(
                "oversize-test-" + UUID.randomUUID(), 4L, value -> value.length);
        cache.put("too-large", new byte[5]);
        assertFalse(cache.keys().contains("too-large"));

        long estimate = BackendMemoryCache.estimate(Map.of(
                "rows", List.of(Map.of("name", "公交", "values", new int[]{1, 2, 3}))));
        assertTrue(estimate > 100L);
    }

    @Test
    void invokesResourceCleanupWhenByteEvictionOccurs() {
        AtomicInteger disposed = new AtomicInteger();
        BackendMemoryCache<String, byte[]> cache = new BackendMemoryCache<>(
                "resource-test-" + UUID.randomUUID(), 5L, value -> value.length,
                (key, value) -> disposed.incrementAndGet());

        cache.put("first", new byte[5]);
        cache.put("second", new byte[5]);

        assertEquals(1, disposed.get());
        cache.clear();
        assertEquals(2, disposed.get());
    }
}
