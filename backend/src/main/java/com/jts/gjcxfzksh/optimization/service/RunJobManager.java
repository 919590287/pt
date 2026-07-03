package com.jts.gjcxfzksh.optimization.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.jts.gjcxfzksh.config.MatsimConfig;
import com.jts.gjcxfzksh.data.entry.Scheme;
import com.jts.gjcxfzksh.exception.BusinessException;
import com.jts.gjcxfzksh.optimization.model.CutResult;
import com.jts.gjcxfzksh.optimization.model.EditItem;
import com.jts.gjcxfzksh.optimization.model.OptimizationDraft;
import com.jts.gjcxfzksh.optimization.model.OptimizationParams;
import com.jts.gjcxfzksh.optimization.model.RunJob;
import com.jts.gjcxfzksh.optimization.model.ValidationIssue;
import com.jts.gjcxfzksh.optimization.runner.MatsimScenarioRunner;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 生成+运行任务编排（六阶段：切分 -> 应用修改 -> 校验 -> 跑基线 -> 跑方案 -> 注册）。
 * 全程在 仿真数据/<scope>/_staging/<jobId>/ 工作区进行，成功后由 ModelRegistryService 原子迁移。
 * MATSim 以独立子进程运行（隔离内存/CPU），全局 FIFO 队列（默认并发1）。
 */
@Slf4j
@Service
public class RunJobManager {

    @Resource
    private MatsimConfig matsimConfig;
    @Resource
    private DraftService draftService;
    @Resource
    private ScenarioCutService scenarioCutService;
    @Resource
    private EditApplyService editApplyService;
    @Resource
    private ModelRegistryService modelRegistryService;

    @Value("${optimization.runner-xmx:8g}")
    private String runnerXmx;
    @Value("${optimization.run-concurrency:1}")
    private int runConcurrency;

    private ExecutorService executor;
    private final Map<String, RunJob> jobs = new ConcurrentHashMap<>();
    private final Map<String, OptimizationDraft> jobDrafts = new ConcurrentHashMap<>();
    private final Map<String, Process> processes = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        executor = Executors.newFixedThreadPool(Math.max(1, runConcurrency), r -> {
            Thread t = new Thread(r, "opt-run-executor");
            t.setDaemon(true);
            return t;
        });
        recoverInterrupted();
    }

    @PreDestroy
    public void destroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
        processes.values().forEach(Process::destroyForcibly);
    }

    // ==================== 提交与查询 ====================

    public RunJob submit(String username, OptimizationParams.GenerateParam param) {
        if (param.getDraftId() == null || param.getDraftId().isBlank()) {
            throw new BusinessException("缺少草稿");
        }
        String scope = param.getScope();
        if (scope == null || scope.isBlank()) {
            scope = username;
        }
        if (!MatsimConfig.PUBLIC_SCOPE.equals(scope) && !scope.equals(username)) {
            throw new BusinessException("保存位置只能是 public 或当前用户目录");
        }
        matsimConfig.requireSchemeAccess(param.getParentModel(), username);
        OptimizationDraft draft = draftService.get(username, param.getParentModel(), param.getDraftId());
        if (draft.getArea() == null || draft.getArea().getPolygon() == null || draft.getArea().getPolygon().size() < 3) {
            throw new BusinessException("草稿缺少研究区域");
        }
        String areaName = DraftService.areaOf(param.getParentModel());
        int iterations = Math.min(1000, Math.max(10, param.getIterations()));
        modelRegistryService.validateNames(areaName, scope, param.getBaselineName(), param.getVariantName());
        checkDiskSpace(areaName);

        RunJob job = new RunJob();
        job.setJobId("opt_" + Long.toString(System.currentTimeMillis(), 36) + "_" + UUID.randomUUID().toString().substring(0, 4));
        job.setUsername(username);
        job.setParentModel(param.getParentModel());
        job.setAreaName(areaName);
        job.setScope(scope);
        job.setDraftId(draft.getDraftId());
        job.setDraftName(draft.getName());
        job.setBaselineName(param.getBaselineName().trim());
        job.setVariantName(param.getVariantName().trim());
        job.setIterations(iterations);
        job.setCreatedAt(System.currentTimeMillis());
        job.setUpdatedAt(job.getCreatedAt());
        jobs.put(job.getJobId(), job);
        jobDrafts.put(job.getJobId(), draft);
        persist(job);
        executor.submit(() -> runPipeline(job));
        return job;
    }

    public List<RunJob> jobsOf(String username) {
        List<RunJob> list = new ArrayList<>();
        for (RunJob job : jobs.values()) {
            if (username.equals(job.getUsername())) {
                list.add(job);
            }
        }
        list.sort(Comparator.comparingLong(RunJob::getCreatedAt).reversed());
        return list.size() > 20 ? list.subList(0, 20) : list;
    }

    public RunJob job(String username, String jobId) {
        RunJob job = jobs.get(jobId);
        if (job == null || !username.equals(job.getUsername())) {
            throw new BusinessException("任务不存在");
        }
        return job;
    }

    public void cancel(String username, String jobId) {
        RunJob job = job(username, jobId);
        if (job.terminal()) {
            return;
        }
        job.setCancelRequested(true);
        Process process = processes.get(jobId);
        if (process != null) {
            process.destroyForcibly();
        }
        update(job, job.getStage(), job.getPercent(), "正在取消…");
    }

    public RunJob retry(String username, String jobId) {
        RunJob old = job(username, jobId);
        if (!RunJob.STAGE_FAILED.equals(old.getStage()) && !RunJob.STAGE_CANCELED.equals(old.getStage())) {
            throw new BusinessException("仅失败/已取消的任务可以重试");
        }
        OptimizationParams.GenerateParam param = new OptimizationParams.GenerateParam();
        param.setDraftId(old.getDraftId());
        param.setParentModel(old.getParentModel());
        param.setBaselineName(old.getBaselineName());
        param.setVariantName(old.getVariantName());
        param.setScope(old.getScope());
        param.setIterations(old.getIterations());
        modelRegistryService.cleanupStaging(stagingDir(old));
        jobs.remove(jobId);
        return submit(username, param);
    }

    public void cleanup(String username, String jobId) {
        RunJob job = job(username, jobId);
        if (!job.terminal()) {
            throw new BusinessException("任务仍在进行中，无法清理");
        }
        modelRegistryService.cleanupStaging(stagingDir(job));
        jobs.remove(jobId);
    }

    // ==================== 流水线 ====================

    private void runPipeline(RunJob job) {
        Path staging = stagingDir(job);
        try {
            Files.createDirectories(staging);
            OptimizationDraft draft = jobDrafts.get(job.getJobId());
            if (draft == null) {
                draft = draftService.get(job.getUsername(), job.getParentModel(), job.getDraftId());
            }
            final OptimizationDraft finalDraft = draft;
            Files.writeString(staging.resolve("draft.snapshot.json"), JSON.toJSONString(draft, JSONWriter.Feature.PrettyFormat));
            Files.writeString(staging.resolve("edits.json"), JSON.toJSONString(draft.getEdits(), JSONWriter.Feature.PrettyFormat));

            Scheme parent = matsimConfig.getSchemes().get(job.getParentModel());
            if (parent == null) {
                throw new BusinessException("母本模型不存在: " + job.getParentModel());
            }
            checkCanceled(job);

            // 1. 切分
            update(job, RunJob.STAGE_CUT, 1, "按研究区域切分母本模型");
            CutResult cutResult = scenarioCutService.cut(parent, draft.getArea(), job.getIterations(),
                    staging.resolve("baseline/input"),
                    (p, msg) -> update(job, RunJob.STAGE_CUT, 1 + p * 24 / 100, msg));
            job.setCutResult(cutResult);
            Files.writeString(staging.resolve("cutReport.json"), JSON.toJSONString(cutResult, JSONWriter.Feature.PrettyFormat));
            if (cutResult.getPersonsInternal() + cutResult.getPersonsCrossing() + cutResult.getPersonsThrough() == 0) {
                throw new BusinessException("切分结果不含任何出行者，请检查研究区域是否过小或位置有误");
            }
            checkCanceled(job);

            // 2. 应用修改
            update(job, RunJob.STAGE_APPLY, 26, "应用 " + draft.getEdits().size() + " 项线网修改");
            List<EditItem> edits = draft.getEdits() == null ? List.of() : draft.getEdits();
            EditApplyService.ApplyOutcome outcome = editApplyService.apply(
                    staging.resolve("baseline/input"), staging.resolve("variant/input"), edits);
            job.getValidationIssues().addAll(outcome.getIssues());
            job.getAppliedSummaries().addAll(outcome.getApplied());

            // 3. 校验
            update(job, RunJob.STAGE_VALIDATE, 33, "校验方案模型一致性");
            if (outcome.hasError()) {
                String first = outcome.getIssues().stream()
                        .filter(i -> ValidationIssue.ERROR.equals(i.getLevel()))
                        .map(ValidationIssue::getMessage).findFirst().orElse("校验未通过");
                throw new BusinessException("方案校验未通过：" + first);
            }
            checkCanceled(job);

            // 4/5. 顺序运行
            runMatsim(job, staging.resolve("baseline"), RunJob.STAGE_RUN_BASELINE, 35, 65, "基线模型 " + job.getBaselineName());
            checkCanceled(job);
            runMatsim(job, staging.resolve("variant"), RunJob.STAGE_RUN_VARIANT, 65, 95, "方案模型 " + job.getVariantName());
            checkCanceled(job);

            // 6. 注册
            update(job, RunJob.STAGE_REGISTER, 96, "注册模型并触发缓存构建");
            modelRegistryService.register(job, finalDraft, staging);
            modelRegistryService.cleanupStaging(staging);
            job.setFinishedAt(System.currentTimeMillis());
            update(job, RunJob.STAGE_DONE, 100, "完成：两个模型已进入模型库，缓存后台构建中");
        } catch (CanceledException e) {
            job.setFinishedAt(System.currentTimeMillis());
            update(job, RunJob.STAGE_CANCELED, job.getPercent(), "任务已取消");
        } catch (BusinessException e) {
            job.setError(e.getMessage());
            job.setFinishedAt(System.currentTimeMillis());
            update(job, RunJob.STAGE_FAILED, job.getPercent(), e.getMessage());
        } catch (Throwable e) {
            log.error("线网优化任务失败: {}", job.getJobId(), e);
            job.setError(String.valueOf(e.getMessage()));
            job.setFinishedAt(System.currentTimeMillis());
            update(job, RunJob.STAGE_FAILED, job.getPercent(), "任务失败: " + e.getMessage());
        } finally {
            jobDrafts.remove(job.getJobId());
        }
    }

    private void runMatsim(RunJob job, Path modelDir, String stage, int fromPercent, int toPercent, String label) {
        Path config = modelDir.resolve("input/config.xml");
        Path output = modelDir.resolve("output");
        Path logFile = modelDir.getParent().resolve("logs").resolve(modelDir.getFileName() + ".log");
        try {
            Files.createDirectories(logFile.getParent());
        } catch (IOException e) {
            throw new BusinessException("创建日志目录失败", e);
        }
        job.setIteration(0);
        job.setLastIteration(job.getIterations());
        update(job, stage, fromPercent, "启动 MATSim：" + label);

        List<String> cmd = buildCommand(config, output);
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(logFile.toFile());
            process = pb.start();
        } catch (IOException e) {
            throw new BusinessException("MATSim 子进程启动失败: " + e.getMessage(), e);
        }
        processes.put(job.getJobId(), process);
        try {
            while (process.isAlive()) {
                if (job.isCancelRequested()) {
                    process.destroyForcibly();
                    throw new CanceledException();
                }
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                    throw new CanceledException();
                }
                int iteration = detectIteration(output);
                if (iteration >= 0) {
                    job.setIteration(Math.min(iteration, job.getIterations()));
                }
                int span = toPercent - fromPercent;
                int percent = fromPercent + (int) Math.min(span - 1,
                        Math.round(span * (double) job.getIteration() / Math.max(1, job.getIterations())));
                job.setLogTail(tail(logFile, 30));
                update(job, stage, percent, label + "：迭代 " + job.getIteration() + "/" + job.getIterations());
            }
            int code = process.exitValue();
            job.setLogTail(tail(logFile, 40));
            if (job.isCancelRequested()) {
                throw new CanceledException();
            }
            if (code != 0) {
                throw new BusinessException(label + " 运行失败（退出码 " + code + "），日志: " + logFile);
            }
            if (!Files.exists(output.resolve("output_events.xml.gz"))) {
                throw new BusinessException(label + " 运行结束但缺少 output_events，日志: " + logFile);
            }
            update(job, stage, toPercent, label + "：运行完成");
        } finally {
            processes.remove(job.getJobId());
        }
    }

    private List<String> buildCommand(Path config, Path output) {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-Xmx" + runnerXmx);
        cmd.add("-Dmatsim.preferLocalDtds=true");
        cmd.add("-Dfile.encoding=UTF-8");
        boolean bootJar = !classpath.contains(File.pathSeparator) && classpath.endsWith(".jar")
                && !classpath.contains("classes");
        if (bootJar) {
            // Spring Boot fat-jar：用 PropertiesLauncher 调起 runner main
            cmd.add("-Dloader.main=" + MatsimScenarioRunner.class.getName());
            cmd.add("-cp");
            cmd.add(classpath);
            cmd.add("org.springframework.boot.loader.launch.PropertiesLauncher");
        } else {
            cmd.add("-cp");
            cmd.add(classpath);
            cmd.add(MatsimScenarioRunner.class.getName());
        }
        cmd.add(config.toAbsolutePath().toString());
        cmd.add(output.toAbsolutePath().toString());
        return cmd;
    }

    /** 从 output/ITERS/it.N 目录推断当前迭代 */
    private int detectIteration(Path output) {
        File iters = output.resolve("ITERS").toFile();
        File[] dirs = iters.listFiles(f -> f.isDirectory() && f.getName().startsWith("it."));
        if (dirs == null || dirs.length == 0) {
            return -1;
        }
        int max = -1;
        for (File dir : dirs) {
            try {
                max = Math.max(max, Integer.parseInt(dir.getName().substring(3)));
            } catch (NumberFormatException ignored) {
            }
        }
        return max;
    }

    private List<String> tail(Path logFile, int lines) {
        try (RandomAccessFile raf = new RandomAccessFile(logFile.toFile(), "r")) {
            long size = raf.length();
            int block = 24 * 1024;
            long from = Math.max(0, size - block);
            raf.seek(from);
            byte[] buf = new byte[(int) (size - from)];
            raf.readFully(buf);
            String text = new String(buf, StandardCharsets.UTF_8);
            String[] all = text.split("\n");
            List<String> result = new ArrayList<>();
            for (int i = Math.max(0, all.length - lines); i < all.length; i++) {
                if (!all[i].isBlank()) {
                    result.add(all[i].length() > 400 ? all[i].substring(0, 400) : all[i]);
                }
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    // ==================== 辅助 ====================

    private Path stagingDir(RunJob job) {
        return matsimConfig.simulationPath(job.getAreaName()).resolve(job.getScope()).resolve("_staging").resolve(job.getJobId());
    }

    private void checkDiskSpace(String areaName) {
        try {
            long usable = matsimConfig.simulationPath(areaName).toFile().getUsableSpace();
            if (usable > 0 && usable < 2L * 1024 * 1024 * 1024) {
                throw new BusinessException("磁盘剩余空间不足2GB，无法生成模型");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception ignored) {
        }
    }

    private void checkCanceled(RunJob job) {
        if (job.isCancelRequested()) {
            throw new CanceledException();
        }
    }

    private void update(RunJob job, String stage, int percent, String message) {
        job.setStage(stage);
        job.setPercent(Math.max(job.terminal() ? 0 : job.getPercent(), Math.min(100, percent)));
        if (job.terminal()) {
            job.setPercent(Math.min(100, percent));
        }
        job.setMessage(message);
        job.setUpdatedAt(System.currentTimeMillis());
        persist(job);
    }

    private void persist(RunJob job) {
        try {
            Path staging = stagingDir(job);
            Files.createDirectories(staging);
            Files.writeString(staging.resolve("job.json"), JSON.toJSONString(job, JSONWriter.Feature.PrettyFormat));
        } catch (Exception e) {
            log.debug("job.json 持久化失败: {}", job.getJobId());
        }
    }

    /** 服务重启后恢复展示中断任务 */
    private void recoverInterrupted() {
        try {
            for (String area : matsimConfig.areaNames()) {
                File simDir = matsimConfig.simulationPath(area).toFile();
                File[] scopes = simDir.listFiles(File::isDirectory);
                if (scopes == null) {
                    continue;
                }
                for (File scopeDir : scopes) {
                    File staging = new File(scopeDir, "_staging");
                    File[] jobDirs = staging.listFiles(File::isDirectory);
                    if (jobDirs == null) {
                        continue;
                    }
                    for (File jobDir : jobDirs) {
                        File jobFile = new File(jobDir, "job.json");
                        if (!jobFile.exists()) {
                            continue;
                        }
                        try {
                            RunJob job = JSON.parseObject(Files.readString(jobFile.toPath()), RunJob.class);
                            if (job == null || job.getJobId() == null) {
                                continue;
                            }
                            if (!job.terminal()) {
                                job.setStage(RunJob.STAGE_FAILED);
                                job.setError("服务重启导致任务中断，可点击重试");
                                job.setMessage(job.getError());
                                job.setFinishedAt(System.currentTimeMillis());
                                Files.writeString(jobFile.toPath(), JSON.toJSONString(job, JSONWriter.Feature.PrettyFormat));
                            }
                            jobs.put(job.getJobId(), job);
                        } catch (Exception e) {
                            log.warn("恢复任务失败: {}", jobFile, e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("任务恢复扫描失败", e);
        }
    }

    private static class CanceledException extends RuntimeException {
    }
}
