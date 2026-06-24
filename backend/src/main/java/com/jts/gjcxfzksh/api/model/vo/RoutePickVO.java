package com.jts.gjcxfzksh.api.model.vo;

import com.jts.gjcxfzksh.api.model.pt.PTCoord;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RoutePickVO {

    private String lineId;
    private String lineName;
    private String routeId;
    private String routeName;
    private String startName;
    private String endName;
    private double distanceMeters;
    private PTCoord segmentFrom;
    private PTCoord segmentTo;
}
