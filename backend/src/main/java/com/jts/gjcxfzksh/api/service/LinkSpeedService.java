package com.jts.gjcxfzksh.api.service;

import com.jts.gjcxfzksh.api.model.params.DatasourceParam;

import java.util.Map;

/** 车辆运行监测 · 路段公交车速（模式照 CorridorService）。 */
public interface LinkSpeedService {

    Map<String, Object> summary(DatasourceParam param);
}
