package com.jts.gjcxfzksh.api.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "模型方案对象")
public class SchemeVO {

    /**
     * 方案名
     */
    @Schema(description = "方案名")
    private String name;

    @Schema(description = "展示名称")
    private String displayName;

    @Schema(description = "当前方案")
    private String schemeName;

    @Schema(description = "模型来源")
    private String scope;

    @Schema(description = "模型来源名称")
    private String scopeLabel;

    /**
     * 加载状态
     */
    private boolean loadStatus;

    @Schema(description = "加载阶段")
    private String loadStage;

    @Schema(description = "加载状态说明")
    private String loadMessage;

    @Schema(description = "缓存状态")
    private String cacheStatus;

    @Schema(description = "缓存状态说明")
    private String cacheMessage;

    @Schema(description = "缓存生成进度百分比")
    private int cacheProgressPercent;

    @Schema(description = "缓存生成进度说明")
    private String cacheProgressMessage;

    @Schema(description = "缓存生成已用秒数")
    private long cacheElapsedSeconds;

    @Schema(description = "缓存生成预计剩余秒数，-1 表示未知")
    private long cacheEtaSeconds;

    @Schema(description = "是否大模型轻量加载")
    private boolean largeModel;

    @Schema(description = "output 顶层关键文件估算大小")
    private long outputBytes;

    private boolean isDefault = false;

    private String detail;

    private double scale = 1.0;

}
