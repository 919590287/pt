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

    public static ModelLoadStatus unloaded() {
        return new ModelLoadStatus();
    }

    public ModelLoadStatus copy() {
        ModelLoadStatus copy = new ModelLoadStatus();
        copy.loaded = loaded;
        copy.loading = loading;
        copy.stage = stage;
        copy.message = message;
        copy.startedAt = startedAt;
        copy.finishedAt = finishedAt;
        return copy;
    }
}
