package com.jts.gjcxfzksh.api.service;

import com.jts.gjcxfzksh.api.model.params.DatasourceParam;

import java.util.Map;

/**
 * 公交出行监测 · 人口分布监测（设计文档 §4）：summary/streets 走 POST，
 * grid.bin / streets.geojson 由 Controller 直读缓存或内嵌资源走 GET+ETag。
 */
public interface PopulationService {

    /** 人口分布总量指标 + 活动类型集合（population-summary.json；未就绪返回 status=generating）。 */
    Map<String, Object> summary(DatasourceParam param);

    /** 176 街道全量人口统计 + totals（population-streets.json；未就绪返回 status=generating）。 */
    Map<String, Object> streets(DatasourceParam param);
}
