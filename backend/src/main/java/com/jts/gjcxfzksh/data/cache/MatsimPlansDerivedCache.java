package com.jts.gjcxfzksh.data.cache;

import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.ModelProcessingPool;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * plans 派生缓存共享扫描器。
 *
 * <p>population 与 tripends 的活动端点都来自同一份 plans。大模型冷启动时只解压、
 * 解析一次 XML，然后通过有界队列交给 {@link ModelProcessingPool#parallelism()} 个上限内的
 * worker。每个 worker 持有私有聚合器和坐标街道缓存，扫描完再确定性合并，避免高频锁竞争。</p>
 */
@Slf4j
public final class MatsimPlansDerivedCache {

    private static final String LOCK_FAMILY = "plans-derived";
    private static final int MIN_PARALLEL_PERSONS = 1_024;
    private static final int QUEUE_CAPACITY_PER_WORKER = 256;
    /** 所有 worker 合计约 32 万个坐标缓存槽，数组内存约 7MB。 */
    private static final int TOTAL_STREET_CACHE_ENTRIES = 1 << 18;
    private static final AtomicInteger THREAD_INDEX = new AtomicInteger();

    private MatsimPlansDerivedCache() {
    }

    public static void prepareAllOnModelLoad(MatsimData data) {
        prepare(data, true, true);
    }

    static void preparePopulationOnModelLoad(MatsimData data) {
        prepare(data, true, false);
    }

    static void prepareTripEndsOnModelLoad(MatsimData data) {
        prepare(data, false, true);
    }

    private static void prepare(MatsimData data, boolean requestPopulation, boolean requestTripEnds) {
        synchronized (ModelBuildLocks.lockFor(LOCK_FAMILY, data)) {
            boolean buildPopulation = requestPopulation && !MatsimPopulationCache.isReady(data);
            boolean buildTripEnds = requestTripEnds && !MatsimTripEndsCache.isReady(data);
            if (!buildPopulation && !buildTripEnds) {
                return;
            }

            MatsimPopulationCache.StreetIndex streets = MatsimPopulationCache.streetIndex();
            long startedAt = System.currentTimeMillis();
            ScanResult result;
            try {
                result = scan(data, streets, buildPopulation, buildTripEnds,
                        ModelProcessingPool.parallelism(), TOTAL_STREET_CACHE_ENTRIES);
                log.info("共享plans扫描完成: model={}, persons={}, workers={}, population={}, tripends={}, "
                                + "streetCache={}/{}({}%), 耗时={}ms",
                        data.getName(), result.stats.persons, result.stats.workers,
                        buildPopulation, buildTripEnds, result.stats.streetCacheHits,
                        result.stats.streetCacheLookups(), result.stats.streetCacheHitPercent(),
                        result.stats.elapsedMs);
            } catch (Throwable e) {
                if (buildPopulation) {
                    MatsimPopulationCache.writeFailedManifest(data);
                }
                if (buildTripEnds) {
                    MatsimTripEndsCache.writeFailedManifest(data);
                }
                if (e instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new RuntimeException("共享plans扫描失败: " + e.getMessage(), e);
            }
            // 两类工件各自原子落盘。某一类写失败不回滚/污染另一类已 ready 的 manifest。
            if (buildPopulation) {
                MatsimPopulationCache.storeBuiltAggregation(data, result.population, streets, startedAt);
            }
            if (buildTripEnds) {
                MatsimTripEndsCache.storeBuiltAggregation(data, result.tripEnds, streets, startedAt);
            }
        }
    }

    /**
     * 包级基准/测试入口。streetCacheEntries=0 可复现改造前每个点都进入 JTS 的路径。
     */
    static ScanResult scan(MatsimData data,
                           MatsimPopulationCache.StreetIndex streets,
                           boolean buildPopulation,
                           boolean buildTripEnds,
                           int requestedWorkers,
                           int streetCacheEntries) {
        long startedAt = System.currentTimeMillis();
        int workers = resolveWorkers(data, requestedWorkers);
        Map<String, double[]> coordByFacility = buildTripEnds
                ? MatsimTripEndsCache.facilityCoords(data.getScenario() == null ? null : data.getSchedule())
                : Map.of();
        ParallelPersonSink sink = new ParallelPersonSink(
                workers, MatsimPopulationCache.mercCellSize(data.getCenter()), streets, coordByFacility,
                buildPopulation, buildTripEnds, streetCacheEntries);
        try {
            if (data.isLargeModel()) {
                streamPlans(data, sink);
            } else {
                Population population = data.getScenario() == null ? null : data.getPopulation();
                if (population != null) {
                    for (Person person : population.getPersons().values()) {
                        sink.accept(person, null);
                    }
                }
            }
            sink.finish();
        } catch (Throwable e) {
            sink.abort();
            throw e;
        }

        WorkerState merged = sink.merge();
        ScanStats stats = new ScanStats(
                workers,
                merged.persons(),
                System.currentTimeMillis() - startedAt,
                sink.streetCacheHits(),
                sink.streetCacheMisses()
        );
        return new ScanResult(merged.population, merged.tripEnds, stats);
    }

    private static int resolveWorkers(MatsimData data, int requestedWorkers) {
        int workers = Math.max(1, requestedWorkers);
        if (data.isLargeModel()) {
            String plansFile = data.getOutfile() == null ? null : data.getOutfile().getPlans();
            if (plansFile == null || plansFile.isBlank() || !Files.isRegularFile(Path.of(plansFile))) {
                return 1;
            }
        }
        if (!data.isLargeModel()) {
            if (data.getScenario() == null || data.getPopulation() == null
                    || data.getPopulation().getPersons().size() < MIN_PARALLEL_PERSONS) {
                return 1;
            }
        }
        return workers;
    }

    /** 大模型只读一次 plans；坐标转换器由每个 worker 按同一 CRS 规则独立创建。 */
    private static void streamPlans(MatsimData data, ParallelPersonSink sink) {
        String plansFile = data.getOutfile() == null ? null : data.getOutfile().getPlans();
        if (plansFile == null || plansFile.isBlank() || !Files.exists(Path.of(plansFile))) {
            log.warn("共享plans扫描未找到文件，按空人口处理: model={}, plans={}", data.getName(), plansFile);
            return;
        }

        Config readCfg = ConfigUtils.createConfig();
        readCfg.global().setCoordinateSystem(null);
        Scenario readScenario = ScenarioUtils.createScenario(readCfg);
        String globalCRS = data.getConfig() == null ? null : data.getConfig().global().getCoordinateSystem();
        String inputCRS = data.getConfig() == null ? null : data.getConfig().plans().getInputCRS();
        TransformSpec[] transformSpec = new TransformSpec[1];
        boolean[] resolved = new boolean[1];
        long[] persons = new long[1];

        StreamingPopulationReader reader = new StreamingPopulationReader(readScenario);
        reader.addAlgorithm(person -> {
            if (!resolved[0]) {
                String planCRS = (String) readScenario.getPopulation().getAttributes()
                        .getAttribute("coordinateReferenceSystem");
                transformSpec[0] = new TransformSpec(globalCRS, inputCRS, planCRS);
                resolved[0] = true;
                log.info("共享plans流式读取: model={}, planCRS={}, inputCRS={}, globalCRS={}, workers={}",
                        data.getName(), planCRS, inputCRS, globalCRS, sink.workerCount());
            }
            sink.accept(person, transformSpec[0]);
            persons[0]++;
            if (persons[0] % 1_000_000 == 0) {
                log.info("共享plans流式读取进度: model={}, persons={}", data.getName(), persons[0]);
            }
        });
        reader.readFile(plansFile);
    }

    record ScanResult(MatsimPopulationCache.Aggregation population,
                      MatsimTripEndsCache.Aggregation tripEnds,
                      ScanStats stats) {
    }

    record ScanStats(int workers, long persons, long elapsedMs,
                     long streetCacheHits, long streetCacheMisses) {
        long streetCacheLookups() {
            return streetCacheHits + streetCacheMisses;
        }

        long streetCacheHitPercent() {
            long lookups = streetCacheLookups();
            return lookups == 0 ? 0 : Math.round(streetCacheHits * 100.0 / lookups);
        }
    }

    private record TransformSpec(String globalCRS, String inputCRS, String planCRS) {
    }

    private record PersonTask(Person person, TransformSpec transform, boolean poison) {
        private static PersonTask person(Person person, TransformSpec transform) {
            return new PersonTask(person, transform, false);
        }

        private static PersonTask poisonPill() {
            return new PersonTask(null, null, true);
        }
    }

    /** 单 worker 线程内所有状态私有，不需要任何聚合锁。 */
    private static final class WorkerState {
        private final MatsimPopulationCache.CoordinateStreetCache streetCache;
        private final MatsimPopulationCache.Aggregation population;
        private final MatsimTripEndsCache.Aggregation tripEnds;
        private TransformSpec transformSpec;
        private CoordinateTransformation transformation;
        private boolean transformationResolved;

        private WorkerState(double mercCellSize,
                            MatsimPopulationCache.StreetIndex streets,
                            Map<String, double[]> coordByFacility,
                            boolean buildPopulation,
                            boolean buildTripEnds,
                            int streetCacheEntries) {
            this.streetCache = streets != null && streetCacheEntries > 0
                    ? new MatsimPopulationCache.CoordinateStreetCache(streets, streetCacheEntries)
                    : null;
            MatsimPopulationCache.StreetLocator locator = streetCache == null ? streets : streetCache;
            this.population = buildPopulation
                    ? new MatsimPopulationCache.Aggregation(mercCellSize, streets, locator)
                    : null;
            this.tripEnds = buildTripEnds
                    ? new MatsimTripEndsCache.Aggregation(mercCellSize, streets, coordByFacility, locator)
                    : null;
        }

        private void accept(Person person, TransformSpec nextSpec) {
            CoordinateTransformation ctf = transformation(nextSpec);
            if (population != null) {
                population.acceptPerson(person, ctf);
            }
            if (tripEnds != null) {
                tripEnds.acceptPerson(person, ctf);
            }
        }

        private CoordinateTransformation transformation(TransformSpec nextSpec) {
            if (nextSpec == null) {
                return null;
            }
            if (!transformationResolved || !nextSpec.equals(transformSpec)) {
                transformation = MatsimPopulationCache.ctf(
                        nextSpec.globalCRS, nextSpec.inputCRS, nextSpec.planCRS);
                transformSpec = nextSpec;
                transformationResolved = true;
            }
            return transformation;
        }

        private long persons() {
            if (population != null) {
                return population.persons;
            }
            return tripEnds == null ? 0 : tripEnds.persons;
        }

        private void mergeFrom(WorkerState other) {
            if (population != null) {
                population.mergeFrom(other.population);
            }
            if (tripEnds != null) {
                tripEnds.mergeFrom(other.tripEnds);
            }
        }

        private long streetCacheHits() {
            return streetCache == null ? 0 : streetCache.hits();
        }

        private long streetCacheMisses() {
            return streetCache == null ? 0 : streetCache.misses();
        }
    }

    private static final class ParallelPersonSink {
        private final int workerCount;
        private final List<WorkerState> states;
        private final ArrayBlockingQueue<PersonTask> queue;
        private final List<Thread> threads = new ArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile boolean finished;

        private ParallelPersonSink(int workerCount,
                                   double mercCellSize,
                                   MatsimPopulationCache.StreetIndex streets,
                                   Map<String, double[]> coordByFacility,
                                   boolean buildPopulation,
                                   boolean buildTripEnds,
                                   int totalStreetCacheEntries) {
            this.workerCount = workerCount;
            this.states = new ArrayList<>(workerCount);
            this.queue = workerCount <= 1 ? null : new ArrayBlockingQueue<>(
                    Math.max(1_024, workerCount * QUEUE_CAPACITY_PER_WORKER));
            int perWorkerCacheEntries = totalStreetCacheEntries <= 0 ? 0
                    : Math.max(1 << 14, totalStreetCacheEntries / workerCount);
            for (int i = 0; i < workerCount; i++) {
                states.add(new WorkerState(mercCellSize, streets, coordByFacility,
                        buildPopulation, buildTripEnds, perWorkerCacheEntries));
            }
            if (workerCount > 1) {
                startWorkers();
            }
        }

        private void startWorkers() {
            for (int i = 0; i < workerCount; i++) {
                WorkerState state = states.get(i);
                Thread thread = new Thread(() -> runWorker(state),
                        "matsim-plans-worker-" + THREAD_INDEX.incrementAndGet());
                thread.setDaemon(true);
                threads.add(thread);
                thread.start();
            }
        }

        private int workerCount() {
            return workerCount;
        }

        private void accept(Person person, TransformSpec transform) {
            rethrowFailure();
            if (workerCount == 1) {
                states.get(0).accept(person, transform);
                return;
            }
            PersonTask task = PersonTask.person(person, transform);
            try {
                while (!queue.offer(task, 250, TimeUnit.MILLISECONDS)) {
                    rethrowFailure();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("plans扫描入队被中断", e);
            }
        }

        private void runWorker(WorkerState state) {
            try {
                while (true) {
                    PersonTask task = queue.take();
                    if (task.poison) {
                        return;
                    }
                    state.accept(task.person, task.transform);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!finished) {
                    failure.compareAndSet(null, e);
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        }

        private void finish() {
            if (finished) {
                rethrowFailure();
                return;
            }
            if (failure.get() != null) {
                abort();
                rethrowFailure();
                return;
            }
            finished = true;
            if (workerCount > 1) {
                sendPoison(false);
                if (failure.get() != null) {
                    for (Thread thread : threads) {
                        thread.interrupt();
                    }
                }
                joinWorkers();
            }
            rethrowFailure();
        }

        private void abort() {
            if (finished) {
                return;
            }
            finished = true;
            if (workerCount > 1) {
                queue.clear();
                sendPoison(true);
                for (Thread thread : threads) {
                    thread.interrupt();
                }
                joinWorkers();
            }
        }

        private void sendPoison(boolean bestEffort) {
            for (int i = 0; i < workerCount; i++) {
                try {
                    if (bestEffort) {
                        queue.offer(PersonTask.poisonPill());
                    } else {
                        while (!queue.offer(PersonTask.poisonPill(), 250, TimeUnit.MILLISECONDS)) {
                            if (failure.get() != null) {
                                return;
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failure.compareAndSet(null, e);
                    return;
                }
            }
        }

        private void joinWorkers() {
            for (Thread thread : threads) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failure.compareAndSet(null, e);
                    return;
                }
            }
        }

        private void rethrowFailure() {
            Throwable problem = failure.get();
            if (problem == null) {
                return;
            }
            if (problem instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException("plans并行聚合失败: " + problem.getMessage(), problem);
        }

        private WorkerState merge() {
            if (!finished) {
                throw new IllegalStateException("plans扫描尚未结束");
            }
            WorkerState merged = states.get(0);
            for (int i = 1; i < states.size(); i++) {
                merged.mergeFrom(states.get(i));
            }
            return merged;
        }

        private long streetCacheHits() {
            return states.stream().mapToLong(WorkerState::streetCacheHits).sum();
        }

        private long streetCacheMisses() {
            return states.stream().mapToLong(WorkerState::streetCacheMisses).sum();
        }
    }
}
