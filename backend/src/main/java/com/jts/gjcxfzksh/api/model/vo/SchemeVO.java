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

    /**
     * 加载状态
     */
    private boolean loadStatus;

    private boolean isDefault = false;

    private String detail;

    private double scale = 1.0;

}