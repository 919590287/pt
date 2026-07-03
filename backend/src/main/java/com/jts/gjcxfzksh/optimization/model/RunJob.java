package com.jts.gjcxfzksh.optimization.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 生成+运行任务（六阶段）。状态持久化在 _staging/<jobId>/job.json，服务重启可恢复展示。
 */
@Data
public class RunJob {

    public static final String STAGE_QUEUED = "queued";
    public static final String STAGE_CUT = "cut";
    public static final String STAGE_APPLY = "apply";
    public static final String STAGE_VALIDATE = "validate";
    public static final String STAGE_RUN_BASELINE = "runBaseline";
    public static final String STAGE_RUN_VARIANT = "runVariant";
    public static final String STAGE_REGISTER = "register";
    public static final String STAGE_DONE = "done";
    public static final String STAGE_FAILED = "failed";
    public static final String STAGE_CANCELED = "canceled";

    private String jobId;
    private String username;
    private String parentModel;
    private String areaName;
    private String scope;
    private String draftId;
    private String draftName;
    private String baselineName;
    private String variantName;
    private int iterations;

    private String stage = STAGE_QUEUED;
    /** 0-100 总进度 */
    private int percent;
    private String message = "排队中";
    private String error;

    /** 运行阶段迭代进度 */
    private int iteration;
    private int lastIteration;

    private List<ValidationIssue> validationIssues = new ArrayList<>();
    private List<String> appliedSummaries = new ArrayList<>();
    private CutResult cutResult;
    private List<String> logTail = new ArrayList<>();

    /** 注册完成后的模型 key（区域/scope/名称） */
    private String baselineModelKey;
    private String variantModelKey;

    private long createdAt;
    private long updatedAt;
    private long finishedAt;

    private transient volatile boolean cancelRequested;

    public boolean terminal() {
        return STAGE_DONE.equals(stage) || STAGE_FAILED.equals(stage) || STAGE_CANCELED.equals(stage);
    }
}
