package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.api.model.vo.AuthVO;
import com.jts.gjcxfzksh.config.MatsimConfig;
import com.jts.gjcxfzksh.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发登录契约：PBKDF2 校验/重哈希必须在 writeLock 外执行（否则并发登录在全局锁上串行排队，
 * 表现为登录接口"挂死"），入锁后二次核对哈希未被并发修改。
 */
class AuthServiceImplConcurrencyTest {

    @TempDir
    Path tempDir;

    private <T extends AuthServiceImpl> T wire(T service) throws Exception {
        MatsimConfig config = new MatsimConfig();
        setField(config, MatsimConfig.class, "folder", tempDir.toString());
        setField(config, MatsimConfig.class, "cacheFolder", "");
        setField(service, AuthServiceImpl.class, "matsimConfig", config);
        service.loadStoreIntoMemory();
        return service;
    }

    private static void setField(Object target, Class<?> declaringClass, String name, Object value) throws Exception {
        Field field = declaringClass.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** 统计 verifyPassword 的最大并发进入数；旧实现（校验在锁内）恒为 1。 */
    static class InstrumentedAuthService extends AuthServiceImpl {
        final AtomicInteger current = new AtomicInteger();
        final AtomicInteger maxConcurrent = new AtomicInteger();

        @Override
        boolean verifyPassword(String password, String storedHash) {
            int now = current.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(200);
                return super.verifyPassword(password, storedHash);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                current.decrementAndGet();
            }
        }
    }

    static class CountingMatsimConfig extends MatsimConfig {
        final AtomicInteger scans = new AtomicInteger();

        @Override
        public synchronized void init() {
            scans.incrementAndGet();
            super.init();
        }
    }

    @Test
    void authHotPathsDoNotRescanTheWholeModelRegistry() throws Exception {
        AuthServiceImpl service = new AuthServiceImpl();
        CountingMatsimConfig config = new CountingMatsimConfig();
        setField(config, MatsimConfig.class, "folder", tempDir.toString());
        setField(config, MatsimConfig.class, "cacheFolder", "");
        setField(service, AuthServiceImpl.class, "matsimConfig", config);
        service.loadStoreIntoMemory();

        service.register("registry-user", "password123");
        service.login("registry-user", "password123");
        service.resetPassword("registry-user", "password123", "password456");

        assertEquals(0, config.scans.get(), "认证只创建空用户目录，不应重扫全部模型 output");
    }

    @Test
    void concurrentLoginsVerifyPasswordOutsideLock() throws Exception {
        InstrumentedAuthService service = wire(new InstrumentedAuthService());
        service.register("alice", "password123");

        int threads = 10;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<AuthVO>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return service.login("alice", "password123");
                }));
            }
            Set<String> tokens = new HashSet<>();
            for (Future<AuthVO> future : futures) {
                AuthVO auth = future.get(30, TimeUnit.SECONDS);
                tokens.add(auth.getToken());
            }
            assertEquals(threads, tokens.size(), "每次登录应各自拿到独立会话");
        } finally {
            pool.shutdownNow();
        }
        assertTrue(service.maxConcurrent.get() >= 5,
                "密码校验应在锁外并发执行，实测最大并发=" + service.maxConcurrent.get());
    }

    @Test
    void concurrentLoginWallClockFasterThanSerial() throws Exception {
        AuthServiceImpl service = wire(new AuthServiceImpl());
        service.register("bob", "password123");

        // 预热并测单次登录耗时（含一次 PBKDF2 校验）
        long singleStart = System.nanoTime();
        service.login("bob", "password123");
        long singleMillis = Math.max(1, (System.nanoTime() - singleStart) / 1_000_000);

        int threads = 10;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long elapsedMillis;
        try {
            List<Future<AuthVO>> futures = new ArrayList<>();
            long start = System.nanoTime();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return service.login("bob", "password123");
                }));
            }
            for (Future<AuthVO> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
            elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        } finally {
            pool.shutdownNow();
        }
        System.out.printf("单次登录 %d ms，%d 并发登录总耗时 %d ms（串行下限约 %d ms）%n",
                singleMillis, threads, elapsedMillis, singleMillis * threads);
        // PBKDF2 是 CPU 密集型，仅在多核机器上断言并发快于串行；留足余量避免 CI 抖动
        if (Runtime.getRuntime().availableProcessors() >= 4) {
            assertTrue(elapsedMillis < singleMillis * threads * 0.8,
                    "并发登录应快于串行执行: 并发=" + elapsedMillis + "ms, 串行估计=" + singleMillis * threads + "ms");
        }
    }

    /** 首次 verifyPassword 进入后阻塞，直到测试放行——用于制造"校验期间密码被改"的窗口。 */
    static class GatedAuthService extends AuthServiceImpl {
        final CountDownLatch verifyEntered = new CountDownLatch(1);
        final CountDownLatch resumeVerify = new CountDownLatch(1);
        final AtomicBoolean gateUsed = new AtomicBoolean();

        @Override
        boolean verifyPassword(String password, String storedHash) {
            if (gateUsed.compareAndSet(false, true)) {
                verifyEntered.countDown();
                try {
                    if (!resumeVerify.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("gate 未放行");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return super.verifyPassword(password, storedHash);
        }
    }

    @Test
    void passwordResetDuringVerificationIsDetectedByRecheck() throws Exception {
        GatedAuthService service = wire(new GatedAuthService());
        service.register("carol", "old-password");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<AuthVO> pending = pool.submit(() -> service.login("carol", "old-password"));
            assertTrue(service.verifyEntered.await(10, TimeUnit.SECONDS), "登录线程应已进入校验");
            // 旧密码校验被 gate 挡住期间修改密码
            service.resetPassword("carol", "old-password", "new-password");
            service.resumeVerify.countDown();

            // 旧密码首轮校验通过，但入锁后发现哈希已变，重试后对新哈希校验失败
            Exception e = assertThrows(Exception.class, () -> pending.get(30, TimeUnit.SECONDS));
            Throwable cause = e.getCause() == null ? e : e.getCause();
            assertTrue(cause instanceof BusinessException, "应抛业务异常，实际: " + cause);
            assertTrue(cause.getMessage().contains("用户名或密码错误"), "实际消息: " + cause.getMessage());
        } finally {
            pool.shutdownNow();
        }
        // 新密码可正常登录，存储未被并发写坏
        AuthVO auth = service.login("carol", "new-password");
        assertEquals("carol", auth.getUsername());
    }

    @Test
    void passwordResetRequiresCurrentPasswordAndRevokesOldSessions() throws Exception {
        AuthServiceImpl service = wire(new AuthServiceImpl());
        AuthVO oldSession = service.register("erin", "old-password");
        AuthVO secondOldSession = service.login("erin", "old-password");

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.resetPassword("erin", "wrong-password", "new-password"));
        assertTrue(error.getMessage().contains("用户名或原密码错误"));
        assertEquals("erin", service.profile(oldSession.getToken()).getUsername());

        AuthVO newSession = service.resetPassword("erin", "old-password", "new-password");
        assertThrows(BusinessException.class, () -> service.profile(oldSession.getToken()));
        assertThrows(BusinessException.class, () -> service.profile(secondOldSession.getToken()));
        assertEquals("erin", service.profile(newSession.getToken()).getUsername());
        assertThrows(BusinessException.class, () -> service.login("erin", "old-password"));
        assertEquals("erin", service.login("erin", "new-password").getUsername());
    }

    /** 两个线程都完成锁外哈希计算后才允许进锁，制造注册同名竞争。 */
    static class BarrierHashAuthService extends AuthServiceImpl {
        final CyclicBarrier hashBarrier = new CyclicBarrier(2);

        @Override
        String createPasswordHash(String password) {
            String hash = super.createPasswordHash(password);
            try {
                hashBarrier.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return hash;
        }
    }

    @Test
    void concurrentRegisterSameUsernameOnlyOneWins() throws Exception {
        BarrierHashAuthService service = wire(new BarrierHashAuthService());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Object> task = () -> service.register("dave", "password123");
            Future<Object> first = pool.submit(task);
            Future<Object> second = pool.submit(task);
            int success = 0;
            int duplicate = 0;
            for (Future<Object> future : List.of(first, second)) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                    success++;
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    assertTrue(cause instanceof BusinessException, "应抛业务异常，实际: " + cause);
                    assertTrue(cause.getMessage().contains("用户名已存在"), "实际消息: " + cause.getMessage());
                    duplicate++;
                }
            }
            assertEquals(1, success, "同名并发注册应恰有一个成功");
            assertEquals(1, duplicate, "另一个应收到用户名已存在");
        } finally {
            pool.shutdownNow();
        }
        assertEquals("dave", service.login("dave", "password123").getUsername());
    }
}
