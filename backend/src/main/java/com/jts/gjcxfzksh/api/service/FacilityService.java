package com.jts.gjcxfzksh.api.service;

import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.model.vo.FacilityVO;

import java.util.List;

public interface FacilityService {

    List<FacilityVO> facilityAll(DatasourceParam param);

}
