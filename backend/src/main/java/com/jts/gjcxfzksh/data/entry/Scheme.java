package com.jts.gjcxfzksh.data.entry;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 方案实体类
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Scheme {

    /**
     * 方案名（键）
     */
    private String name;

    /**
     * 所属范围：public 或用户名
     */
    private String scope;

    /**
     * 当前方案目录名称
     */
    private String schemeName;

    /**
     * 模型目录名称
     */
    private String modelName;

    /**
     * 展示名称
     */
    private String displayName;

    /**
     * 方案数据目录
     */
    private String folder;

    /**
     * input目录
     */
    private String input;

    /**
     * output目录
     */
    private String output;

    /**
     * 模型派生缓存目录。只保存平台生成的缓存，不写入原始 output。
     */
    private String cache;

    /**
     * output 顶层关键文件估算大小，用于自动进入轻量加载模式。
     */
    private long outputBytes;

    /**
     * 是否按大模型模式加载，避免把超大 plans/events 放入 JVM heap。
     */
    private boolean largeModel;

    /**
     * output 顶层是否包含 plans；模型目录初始化时与大小统计共用一次扫描。
     */
    private boolean cuttable;

    /**
     * json信息
     */
    private Desc desc;

    @Data
    public static class Desc {
        /**
         * 描述
         */
        private String detail;
        private Boolean _default = false;
        private double scale = 1.0;
        /** 0 表示未声明；密度类指标必须返回 nodata，禁止用占位面积计算。 */
        private double area = 0.0;
        /**
         * 可选的人工模式覆盖。true 可对高压缩率等自动阈值难以识别的数据
         * 强制启用低内存模式；false 不会降级已自动识别的大模型，避免误配导致 OOM。
         */
        private Boolean largeModel;
        /**
         * 线网优化元数据（kind/pairId/parentModel/regionPolygon 等），
         * 由 ModelRegistryService 写入，非优化生成的模型为 null。
         */
        private java.util.Map<String, Object> optimization;
    }

}
