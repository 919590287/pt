package com.jts.gjcxfzksh.api.service;

import com.jts.gjcxfzksh.api.model.params.DatasourceParam;

import java.util.Map;

/** 客流走廊监测 · 线路重复系数（模式照 PopulationService）。 */
public interface CorridorService {

    Map<String, Object> summary(DatasourceParam param);

    Map<String, Object> names(DatasourceParam param);
}
