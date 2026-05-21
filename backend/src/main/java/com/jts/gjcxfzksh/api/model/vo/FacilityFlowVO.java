package com.jts.gjcxfzksh.api.model.vo;

import lombok.Data;

@Data
public class FacilityFlowVO {

    private String id;
    private String name;

    private long up;
    private long down;
    private long flow;

}
