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

    /**
     * 站台 ID。地图点选的显示名可能与站点面板的物理站名不完全一致，
     * 此时用 facilityId 反查 stationPanel 的归并站点。
     */
    private String facilityId;

}
