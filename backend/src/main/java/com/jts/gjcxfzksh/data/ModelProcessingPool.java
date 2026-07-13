package com.jts.gjcxfzksh.data;

import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 模型加载/派生缓存共用的有界 CPU 池。
 *
 * <p>不能使用 parallelStream 的全局 commonPool：它既不可按部署机器独立调节，
 * 又会与 Web 请求、JSON 序列化和第三方库争抢线程。这里把模型计算限制在一个可控池内，
 * 默认保留一个逻辑核心给 Web/GC；小集合直接串行，避免任务拆分开销。</p>
 */
@Slf4j
public final class ModelProcessingPool {

    private static final Object LOCK = new Object();
    private static final AtomicInteger THREAD_INDEX = new AtomicInteger();
    private static volatile int parallelism = defaultParallelism();
    private static volatile ForkJoinPool pool = createPool(parallelism);

    private ModelProcessingPool() {
    }

    public static void configure(int requestedThreads) {
        int next = requestedThreads > 0 ? requestedThreads : defaultParallelism();
        next = Math.max(1, Math.min(64, next));
        synchronized (LOCK) {
            if (next == parallelism && pool != null && !pool.isShutdown()) {
                return;
            }
            ForkJoinPool previous = pool;
            parallelism = next;
            pool = createPool(next);
            if (previous != null) {
                previous.shutdown();
            }
        }
        log.info("模型计算线程数: {} (availableProcessors={})",
                next, Runtime.getRuntime().availableProcessors());
    }

    public static int parallelism() {
        return parallelism;
    }

    public static <T> void forEach(Collection<T> values, Consumer<T> consumer) {
        if (values == null || values.isEmpty()) {
            return;
        }
        if (parallelism <= 1 || values.size() < 1_024) {
            values.forEach(consumer);
            return;
        }
        pool.submit(() -> values.parallelStream().forEach(consumer)).join();
    }

    public static <T> long count(Collection<T> values, java.util.function.Predicate<T> predicate) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        if (parallelism <= 1 || values.size() < 1_024) {
            return values.stream().filter(predicate).count();
        }
        return pool.submit(() -> values.parallelStream().filter(predicate).count()).join();
    }

    private static int defaultParallelism() {
        int cpus = Math.max(1, Runtime.getRuntime().availableProcessors());
        return Math.max(1, Math.min(32, cpus - 1));
    }

    private static ForkJoinPool createPool(int threads) {
        return new ForkJoinPool(
                threads,
                forkJoinPool -> {
                    ForkJoinPool.ForkJoinWorkerThreadFactory factory = ForkJoinPool.defaultForkJoinWorkerThreadFactory;
                    var thread = factory.newThread(forkJoinPool);
                    thread.setName("matsim-compute-" + THREAD_INDEX.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                (thread, error) -> log.error("模型并行计算线程异常: {}", thread.getName(), error),
                true
        );
    }
}
