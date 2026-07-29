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

    /** 仿真/真实模式统一的人口栅格字节；源数据不可用时返回 null。 */
    byte[] gridBytes(String datasource);

    /** 人口栅格强校验标签；源数据不可用时返回 null。 */
    String gridTag(String datasource);
}
