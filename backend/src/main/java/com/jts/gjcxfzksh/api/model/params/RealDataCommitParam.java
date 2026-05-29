package com.jts.gjcxfzksh.api.model.params;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class RealDataCommitParam {

    private String areaName;

    private String datasetType;

    private Long baseRevision;

    private String baseVersionId;

    private String message;

    private List<Map<String, Object>> evidenceImages;

    private List<Map<String, Object>> operations;

}
