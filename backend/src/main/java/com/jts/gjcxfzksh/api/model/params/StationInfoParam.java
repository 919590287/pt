package com.jts.gjcxfzksh.api.model.params;

import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class StationInfoParam extends DatasourceParam {

    /**
     * 站点名称（stationPanel 的 stations 以站名为键）
     */
    private String stationName;

}
