package com.jts.gjcxfzksh.api.model.vo;

import lombok.Data;

import java.util.List;

@Data
public class LineVO {

    private String lineId;
    private String lineName;
    private String mode;
    List<RouteDetailVO> routes;

}
