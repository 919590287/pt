package com.jts.gjcxfzksh.api.service;

import com.jts.gjcxfzksh.api.model.params.BuildingQueryParam;
import com.jts.gjcxfzksh.api.model.vo.BuildingTileVO;

public interface BuildingService {

    BuildingTileVO query(BuildingQueryParam param);

}
