package com.jts.gjcxfzksh.api.model.params;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class RoutePickParam extends DatasourceParam {

    private double x;
    private double y;
    private double radiusMeters = 80.0;
    private int limit = 30;
}
