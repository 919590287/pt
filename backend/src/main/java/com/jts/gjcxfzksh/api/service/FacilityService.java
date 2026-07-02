package com.jts.gjcxfzksh.api.service;

import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.model.params.StationInfoParam;
import com.jts.gjcxfzksh.api.model.vo.FacilityVO;

import java.util.List;
import java.util.Map;

public interface FacilityService {

    List<FacilityVO> facilityAll(DatasourceParam param);

    Map<String, Object> stationPanel(DatasourceParam param);

    /**
     * 单站点客流面板明细（对齐 route 侧 routePanelDetail 模式）
     */
    Map<String, Object> stationPanelDetail(StationInfoParam param);

}
