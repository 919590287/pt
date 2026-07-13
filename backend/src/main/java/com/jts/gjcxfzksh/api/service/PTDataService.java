package com.jts.gjcxfzksh.api.service;

import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.model.pt.PTCoord;

import java.nio.file.Path;
import java.util.Map;

public interface PTDataService {

    Map<String, Object> info(DatasourceParam param);

    /**
     * 体检评估指标（全市口径，对齐评估指标表）
     */
    Map<String, Object> evaluation(DatasourceParam param);

    PTCoord center(DatasourceParam param);
    Map<String, Object> trajectory(DatasourceParam param);
    Map<String, Object> trajectoryChunk(DatasourceParam param, int start);
    byte[] trajectoryChunkBinary(DatasourceParam param, int start);
    byte[] trajectoryFrameBinary(
            DatasourceParam param,
            int time,
            int bucketSeconds,
            String visibilityMode,
            Double minX,
            Double minY,
            Double maxX,
            Double maxY
    );
    Path trajectoryChunkBinaryPath(DatasourceParam param, int start);
    String trajectoryChunkTag(DatasourceParam param, int start);

}
