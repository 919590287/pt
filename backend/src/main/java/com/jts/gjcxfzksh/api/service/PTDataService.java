package com.jts.gjcxfzksh.api.service;

import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.model.pt.PTCoord;

import java.util.Map;

public interface PTDataService {

    Map<String, Object> info(DatasourceParam param);
    PTCoord center(DatasourceParam param);

}
