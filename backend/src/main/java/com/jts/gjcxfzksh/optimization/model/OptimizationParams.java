package com.jts.gjcxfzksh.optimization.model;

import lombok.Data;

import java.util.List;

/**
 * /pt/optimization/* 各端点的请求参数集合。
 */
public final class OptimizationParams {

    private OptimizationParams() {
    }

    @Data
    public static class DraftListParam {
        /** 母本模型 key，可为空（列出当前用户全部草稿） */
        private String parentModel;
    }

    @Data
    public static class DraftIdParam {
        private String draftId;
        /** 草稿所在区域（模型 key 前缀），用于定位目录 */
        private String parentModel;
    }

    @Data
    public static class DraftCopyParam {
        private String draftId;
        private String parentModel;
        private String newName;
    }

    @Data
    public static class AreaStatsParam {
        private String parentModel;
        private AreaSpec area;
    }

    @Data
    public static class SnapPointParam {
        private String parentModel;
        /** 草稿 id：吸附时考虑草稿中已新增的路段 */
        private String draftId;
        private double lng;
        private double lat;
        /** 用途：stop（须公交可停靠）| node（路段端点） */
        private String purpose = "stop";
    }

    @Data
    public static class SnapRouteParam {
        private String parentModel;
        private String draftId;
        /** 锚点序列 [[lng,lat], ...]，至少两个 */
        private List<double[]> anchors;
    }

    @Data
    public static class RoadNetworkParam {
        private String parentModel;
        /** 草稿 id：包含草稿中已新增的路段 */
        private String draftId;
        /** 研究区域（返回区域∪缓冲带附近的可行车路段） */
        private AreaSpec area;
    }

    @Data
    public static class GenerateParam {
        private String draftId;
        private String parentModel;
        private String baselineName;
        private String variantName;
        /** public 或当前用户名 */
        private String scope;
        private int iterations = 100;
        /** 生成后是否立即开始运行（P0 一律 true，向导里点开始运行才提交） */
        private boolean autoRun = true;
    }

    @Data
    public static class JobParam {
        private String jobId;
    }
}
