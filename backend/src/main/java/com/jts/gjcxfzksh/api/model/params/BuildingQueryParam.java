package com.jts.gjcxfzksh.api.model.params;

import lombok.Data;

@Data
public class BuildingQueryParam {

    private double minX;
    private double minY;
    private double maxX;
    private double maxY;
    /** 视点最近的地面点（Web 墨卡托）。截断时以它为圆心就近保留，缺省用 bbox 中心。 */
    private Double focusX;
    private Double focusY;
    private double zoom;
    private int maxFeatures = 20000;
    private String shpPath;
    private String heightField = "HEIGHT";

}
