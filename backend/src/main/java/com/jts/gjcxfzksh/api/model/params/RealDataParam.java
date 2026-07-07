package com.jts.gjcxfzksh.api.model.params;

import lombok.Data;

@Data
public class RealDataParam {

    private String areaName;

    private String datasetType;

    private String versionId;

    private Long baseRevision;

    /**
     * busLineStation 响应裁剪：null/"all" 全量（兼容旧端）；
     * "core" 剔除 routeStops 要素但保留计数（首屏轻载）；"routeStops" 仅返回 routeStops。
     */
    private String include;

}
