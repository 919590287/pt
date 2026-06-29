package com.jts.gjcxfzksh.api.model.params;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class RouteInfoParam extends DatasourceParam {

    private String lineId;
    private String routeId;

}
