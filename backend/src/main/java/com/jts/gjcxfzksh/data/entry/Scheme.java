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
        private double area = 1.0;
        /**
         * 线网优化元数据（kind/pairId/parentModel/regionPolygon 等），
         * 由 ModelRegistryService 写入，非优化生成的模型为 null。
         */
        private java.util.Map<String, Object> optimization;
    }

}
