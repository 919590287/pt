package com.jts.gjcxfzksh.api.service;

import com.jts.gjcxfzksh.api.model.params.DatasourceParam;

import java.util.Map;

/** 公交出行监测 · 起终点分布监测（模式照 PopulationService）。 */
public interface TripEndsService {

    Map<String, Object> summary(DatasourceParam param);

    Map<String, Object> streets(DatasourceParam param);

    Map<String, Object> odStreets(DatasourceParam param);
}
