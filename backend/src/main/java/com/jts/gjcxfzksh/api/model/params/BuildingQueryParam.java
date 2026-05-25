package com.jts.gjcxfzksh.api.model.params;

import lombok.Data;

@Data
public class BuildingQueryParam {

    private double minX;
    private double minY;
    private double maxX;
    private double maxY;
    private double zoom;
    private int maxFeatures = 20000;
    private String shpPath;
    private String heightField = "HEIGHT";

}
