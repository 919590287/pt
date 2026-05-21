package com.jts.gjcxfzksh.api.model.params;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class RouteListParam extends DatasourceParam {

    private String routeName;

}
