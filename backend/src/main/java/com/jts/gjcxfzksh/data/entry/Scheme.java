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
    }

}
