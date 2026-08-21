package com.jts.gjcxfzksh.api.model.params;

import lombok.Data;

import java.util.List;

@Data
public class VehicleCalculationSaveParam {

    private String areaName;

    private Long baseRevision;

    private String baseVersionId;

    private String routeName;

    private List<String> featureIds;

    private Integer vehicleCount;
}
