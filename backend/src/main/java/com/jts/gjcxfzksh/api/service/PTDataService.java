package com.jts.gjcxfzksh.api.service;

import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.model.pt.PTCoord;

import java.util.Map;

public interface PTDataService {

    Map<String, Object> info(DatasourceParam param);
    PTCoord center(DatasourceParam param);
    Map<String, Object> trajectory(DatasourceParam param);
    Map<String, Object> trajectoryChunk(DatasourceParam param, int start);
    byte[] trajectoryChunkBinary(DatasourceParam param, int start);

}
