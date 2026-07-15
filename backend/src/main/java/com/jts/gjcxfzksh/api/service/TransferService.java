package com.jts.gjcxfzksh.api.service;

import com.jts.gjcxfzksh.api.model.params.DatasourceParam;

import java.util.Map;

/**
 * 公交—地铁换乘分析（设计文档 §9.2）：summary/dict 走 POST，events.bin 由 Controller 直读缓存走 GET+ETag。
 */
public interface TransferService {

    /** 全网换乘指标 + Top 榜（transfer-summary.json；未就绪返回 status=generating）。 */
    Map<String, Object> summary(DatasourceParam param);

    /** 换乘字典：hubs/busLines/metroLines/busStops/metroStops + scale + 生成参数（未就绪返回 status=generating）。 */
    Map<String, Object> dict(DatasourceParam param);
}
