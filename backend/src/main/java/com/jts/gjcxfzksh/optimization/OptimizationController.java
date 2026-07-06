package com.jts.gjcxfzksh.optimization;

import com.jts.gjcxfzksh.api.common.AjaxResult;
import com.jts.gjcxfzksh.api.common.CurrentUser;
import com.jts.gjcxfzksh.exception.BusinessException;
import com.jts.gjcxfzksh.optimization.model.OptimizationDraft;
import com.jts.gjcxfzksh.optimization.model.OptimizationParams;
import com.jts.gjcxfzksh.optimization.model.RunJob;
import com.jts.gjcxfzksh.optimization.service.DraftService;
import com.jts.gjcxfzksh.optimization.service.RegionStatsService;
import com.jts.gjcxfzksh.optimization.service.RunJobManager;
import com.jts.gjcxfzksh.optimization.service.ScenarioValidateService;
import com.jts.gjcxfzksh.optimization.service.SnapRoutingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 线网优化模块 API。设计文档 §11.2。
 */
@Slf4j
@RestController
@RequestMapping("/pt/optimization")
@Tag(name = "线网优化", description = "研究区域切分、线网编辑、双模型生成与运行")
public class OptimizationController {

    @Resource
    private DraftService draftService;
    @Resource
    private RegionStatsService regionStatsService;
    @Resource
    private SnapRoutingService snapRoutingService;
    @Resource
    private ScenarioValidateService validateService;
    @Resource
    private RunJobManager runJobManager;

    // ==================== 草稿 ====================

    @Operation(summary = "草稿列表")
    @PostMapping("/draft/list")
    public AjaxResult draftList(@RequestBody OptimizationParams.DraftListParam param) {
        return AjaxResult.ok(draftService.list(CurrentUser.getUsername(), param.getParentModel()));
    }

    @Operation(summary = "保存草稿（自动保存）")
    @PostMapping("/draft/save")
    public AjaxResult draftSave(@RequestBody OptimizationDraft draft) {
        return AjaxResult.ok(draftService.save(CurrentUser.getUsername(), draft));
    }

    @Operation(summary = "读取草稿")
    @PostMapping("/draft/get")
    public AjaxResult draftGet(@RequestBody OptimizationParams.DraftIdParam param) {
        return AjaxResult.ok(draftService.get(CurrentUser.getUsername(), param.getParentModel(), param.getDraftId()));
    }

    @Operation(summary = "删除草稿")
    @PostMapping("/draft/delete")
    public AjaxResult draftDelete(@RequestBody OptimizationParams.DraftIdParam param) {
        draftService.delete(CurrentUser.getUsername(), param.getParentModel(), param.getDraftId());
        return AjaxResult.ok();
    }

    @Operation(summary = "复制草稿")
    @PostMapping("/draft/copy")
    public AjaxResult draftCopy(@RequestBody OptimizationParams.DraftCopyParam param) {
        return AjaxResult.ok(draftService.copy(CurrentUser.getUsername(), param.getParentModel(), param.getDraftId(), param.getNewName()));
    }

    // ==================== 编辑辅助 ====================

    @Operation(summary = "研究区域概览统计")
    @PostMapping("/areaStats")
    public AjaxResult areaStats(@RequestBody OptimizationParams.AreaStatsParam param) {
        return AjaxResult.ok(regionStatsService.areaStats(CurrentUser.getUsername(), param.getParentModel(), param.getArea()));
    }

    @Operation(summary = "点吸附（站点/路段端点）")
    @PostMapping("/snapPoint")
    public AjaxResult snapPoint(@RequestBody OptimizationParams.SnapPointParam param) {
        return AjaxResult.ok(snapRoutingService.snapPoint(CurrentUser.getUsername(), param.getParentModel(),
                param.getDraftId(), param.getLng(), param.getLat(), param.getPurpose()));
    }

    @Operation(summary = "沿路网寻径（锚点 -> link 序列）")
    @PostMapping("/snapRoute")
    public AjaxResult snapRoute(@RequestBody OptimizationParams.SnapRouteParam param) {
        return AjaxResult.ok(snapRoutingService.snapRoute(CurrentUser.getUsername(), param.getParentModel(),
                param.getDraftId(), param.getAnchors()));
    }

    @Operation(summary = "研究区域内可行车路网（编辑期路网底图）")
    @PostMapping("/roadNetwork")
    public AjaxResult roadNetwork(@RequestBody OptimizationParams.RoadNetworkParam param) {
        return AjaxResult.ok(snapRoutingService.roadNetwork(CurrentUser.getUsername(), param.getParentModel(),
                param.getDraftId(), param.getArea()));
    }

    @Operation(summary = "草稿校验")
    @PostMapping("/validate")
    public AjaxResult validate(@RequestBody OptimizationParams.DraftIdParam param) {
        OptimizationDraft draft = draftService.get(CurrentUser.getUsername(), param.getParentModel(), param.getDraftId());
        return AjaxResult.ok(validateService.validateDraft(CurrentUser.getUsername(), draft));
    }

    // ==================== 生成与运行 ====================

    @Operation(summary = "生成并运行双模型（切分->应用->校验->基线->方案->注册）")
    @PostMapping("/generate")
    public AjaxResult generate(@RequestBody OptimizationParams.GenerateParam param) {
        OptimizationDraft draft = draftService.get(CurrentUser.getUsername(), param.getParentModel(), param.getDraftId());
        var issues = validateService.validateDraft(CurrentUser.getUsername(), draft);
        boolean hasError = issues.stream().anyMatch(i -> "error".equals(i.getLevel()));
        if (hasError) {
            throw new BusinessException("草稿存在错误项，请先在校验面板处理");
        }
        RunJob job = runJobManager.submit(CurrentUser.getUsername(), param);
        return AjaxResult.ok(job);
    }

    @Operation(summary = "任务状态（jobId 为空时返回本人任务列表）")
    @PostMapping("/jobStatus")
    public AjaxResult jobStatus(@RequestBody OptimizationParams.JobParam param) {
        String username = CurrentUser.getUsername();
        if (param == null || param.getJobId() == null || param.getJobId().isBlank()) {
            return AjaxResult.ok(runJobManager.jobsOf(username));
        }
        return AjaxResult.ok(runJobManager.job(username, param.getJobId()));
    }

    @Operation(summary = "取消任务")
    @PostMapping("/jobCancel")
    public AjaxResult jobCancel(@RequestBody OptimizationParams.JobParam param) {
        runJobManager.cancel(CurrentUser.getUsername(), param.getJobId());
        return AjaxResult.ok();
    }

    @Operation(summary = "重试任务")
    @PostMapping("/jobRetry")
    public AjaxResult jobRetry(@RequestBody OptimizationParams.JobParam param) {
        return AjaxResult.ok(runJobManager.retry(CurrentUser.getUsername(), param.getJobId()));
    }

    @Operation(summary = "清理任务工作区")
    @PostMapping("/jobCleanup")
    public AjaxResult jobCleanup(@RequestBody OptimizationParams.JobParam param) {
        runJobManager.cleanup(CurrentUser.getUsername(), param.getJobId());
        return AjaxResult.ok();
    }
}
