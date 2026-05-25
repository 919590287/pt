package com.jts.gjcxfzksh.api.model.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class BuildingTileVO {

    private double[] center;
    private double[] bounds;
    private String heightField;
    private int featureCount;
    private boolean truncated;
    private List<BuildingVO> buildings = new ArrayList<>();

    @Data
    public static class BuildingVO {
        private double height;
        private List<double[]> rings = new ArrayList<>();
    }

}
