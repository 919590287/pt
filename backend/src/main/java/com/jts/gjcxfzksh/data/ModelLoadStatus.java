package com.jts.gjcxfzksh.data;

import lombok.Data;

@Data
public class ModelLoadStatus {

    private boolean loaded;
    private boolean loading;
    private String stage = "unloaded";
    private String message = "未加载";
    private long startedAt;
    private long finishedAt;

    // —— 真实加载进度：离散检查点 + 检查点区间内按预计耗时线性插值 ——
    private int progressPercent;
    private String progressMessage = "未加载";
    private long elapsedSeconds;
    private long etaSeconds = -1;

    // 当前检查点区间 [phaseBase, phaseBase + phaseSpan]，自 phaseStartedAt 起按 phaseEstimatedMs 插值
    private int phaseBase;
    private int phaseSpan;
    private long phaseStartedAt;
    private long phaseEstimatedMs;
    // 整体预计总耗时（上次成功加载时长，首次按 output 体量估算），用于 ETA
    private long estimatedTotalMs;

    public static ModelLoadStatus unloaded() {
        return new ModelLoadStatus();
    }

    public synchronized void resetProgress(String message) {
        progressPercent = 0;
        progressMessage = message;
        elapsedSeconds = 0;
        etaSeconds = -1;
        phaseBase = 0;
        phaseSpan = 0;
        phaseStartedAt = 0;
        phaseEstimatedMs = 0;
        startedAt = 0;
        finishedAt = 0;
    }

    /**
     * 进入一个新的进度检查点区间。percent 只增不减，避免阶段估时偏差导致进度回退。
     */
    public synchronized void beginPhase(int base, int span, long estimatedMs, String message) {
        phaseBase = Math.max(progressPercent, Math.max(0, Math.min(100, base)));
        phaseSpan = Math.max(0, Math.min(100 - phaseBase, span));
        phaseStartedAt = System.currentTimeMillis();
        phaseEstimatedMs = Math.max(1, estimatedMs);
        progressMessage = message;
        progressPercent = phaseBase;
        refreshDerived();
    }

    /**
     * 读取侧派生计算：把"当前区间的时间插值进度"和"已用/预计剩余"刷新到可序列化字段。
     */
    public synchronized void refreshDerived() {
        long now = System.currentTimeMillis();
        if (loaded) {
            progressPercent = 100;
            etaSeconds = 0;
            elapsedSeconds = startedAt > 0 && finishedAt > startedAt ? (finishedAt - startedAt) / 1000 : elapsedSeconds;
            return;
        }
        if (startedAt > 0) {
            long end = finishedAt > 0 ? finishedAt : now;
            elapsedSeconds = Math.max(0, (end - startedAt) / 1000);
        }
        if (loading && phaseStartedAt > 0 && phaseSpan > 0) {
            double fraction = Math.min(1.0, (now - phaseStartedAt) / (double) phaseEstimatedMs);
            int interpolated = phaseBase + (int) Math.floor(phaseSpan * fraction);
            progressPercent = Math.max(progressPercent, Math.min(97, interpolated));
        }
        if (!loading) {
            if (!"failed".equals(stage)) {
                etaSeconds = -1;
            }
            return;
        }
        if (estimatedTotalMs > 0 && startedAt > 0) {
            long remainMs = estimatedTotalMs - (now - startedAt);
            if (remainMs > 0) {
                etaSeconds = Math.max(1, remainMs / 1000);
                return;
            }
        }
        // 超出估计或无估计：按当前速度外推
        int percent = progressPercent;
        if (percent > 0 && percent < 100 && elapsedSeconds > 0) {
            etaSeconds = Math.max(1, Math.round(elapsedSeconds * (100.0 - percent) / percent));
        } else {
            etaSeconds = -1;
        }
    }

    public synchronized ModelLoadStatus copy() {
        refreshDerived();
        ModelLoadStatus copy = new ModelLoadStatus();
        copy.loaded = loaded;
        copy.loading = loading;
        copy.stage = stage;
        copy.message = message;
        copy.startedAt = startedAt;
        copy.finishedAt = finishedAt;
        copy.progressPercent = progressPercent;
        copy.progressMessage = progressMessage;
        copy.elapsedSeconds = elapsedSeconds;
        copy.etaSeconds = etaSeconds;
        copy.phaseBase = phaseBase;
        copy.phaseSpan = phaseSpan;
        copy.phaseStartedAt = phaseStartedAt;
        copy.phaseEstimatedMs = phaseEstimatedMs;
        copy.estimatedTotalMs = estimatedTotalMs;
        return copy;
    }
}
