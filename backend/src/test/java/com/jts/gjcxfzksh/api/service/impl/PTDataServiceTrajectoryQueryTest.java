package com.jts.gjcxfzksh.api.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PTDataServiceTrajectoryQueryTest {

    private PTDataServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PTDataServiceImpl();
        service.initTrajectoryExecutor();
    }

    @AfterEach
    void tearDown() {
        service.destroyTrajectoryExecutor();
    }

    @Test
    void coalescesConcurrentRequestsForTheSameQuery() throws Exception {
        int callers = 8;
        CyclicBarrier barrier = new CyclicBarrier(callers);
        AtomicInteger executions = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<byte[]>> futures = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return service.executeTrajectoryQuery("viewport:same", "轨迹视口块", () -> {
                        executions.incrementAndGet();
                        pause(100);
                        return new byte[]{1, 2, 3};
                    });
                }));
            }
            for (Future<byte[]> future : futures) {
                assertArrayEquals(new byte[]{1, 2, 3}, future.get());
            }
            assertEquals(1, executions.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sharesOneConcurrencyBudgetAcrossViewportAndFrameQueries() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<byte[]> viewport = pool.submit(() -> {
                barrier.await();
                return service.executeTrajectoryQuery("viewport:a", "轨迹视口块",
                        () -> measuredQuery(active, maximumActive, (byte) 1));
            });
            Future<byte[]> frame = pool.submit(() -> {
                barrier.await();
                return service.executeTrajectoryQuery("frame:b", "轨迹快照",
                        () -> measuredQuery(active, maximumActive, (byte) 2));
            });

            assertArrayEquals(new byte[]{1}, viewport.get());
            assertArrayEquals(new byte[]{2}, frame.get());
            assertEquals(1, maximumActive.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private static byte[] measuredQuery(AtomicInteger active, AtomicInteger maximumActive, byte value) {
        int current = active.incrementAndGet();
        maximumActive.accumulateAndGet(current, Math::max);
        try {
            pause(100);
            return new byte[]{value};
        } finally {
            active.decrementAndGet();
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }
}
