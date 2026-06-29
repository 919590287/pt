package com.jts.gjcxfzksh.api.model.params;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class RouteChartParam extends DatasourceParam {
    private String lineId;
    private String routeId;
    private String departureId;
    private int beginSecond;
    private int endSecond;
    private Boolean single = false;
}
