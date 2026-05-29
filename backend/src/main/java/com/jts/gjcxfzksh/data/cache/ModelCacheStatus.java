package com.jts.gjcxfzksh.data.cache;

import lombok.Data;

@Data
public class ModelCacheStatus {

    private String status = "missing";
    private String message = "缓存未生成";
    private long queuedAt;
    private long startedAt;
    private long finishedAt;
    private long generatedAt;
    private String cachePath;
    private int progressPercent;
    private String progressMessage = "等待生成缓存";
    private long elapsedSeconds;
    private long etaSeconds = -1;

    public static ModelCacheStatus missing(String cachePath) {
        ModelCacheStatus status = new ModelCacheStatus();
        status.setCachePath(cachePath);
        return status;
    }

    public ModelCacheStatus copy() {
        ModelCacheStatus copy = new ModelCacheStatus();
        copy.status = status;
        copy.message = message;
        copy.queuedAt = queuedAt;
        copy.startedAt = startedAt;
        copy.finishedAt = finishedAt;
        copy.generatedAt = generatedAt;
        copy.cachePath = cachePath;
        copy.progressPercent = progressPercent;
        copy.progressMessage = progressMessage;
        copy.elapsedSeconds = elapsedSeconds;
        copy.etaSeconds = etaSeconds;
        return copy;
    }
}
