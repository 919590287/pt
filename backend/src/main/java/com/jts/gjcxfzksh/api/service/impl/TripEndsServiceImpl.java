package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.api.common.DatasourceService;
import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.service.TripEndsService;
import com.jts.gjcxfzksh.data.cache.MatsimTripEndsCache;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 起终点分布监测薄服务层（模式照 PopulationServiceImpl）：鉴权与取数交给
 * DatasourceService，读缓存交给 MatsimTripEndsCache，本层不做任何聚合。
 */
@Service
public class TripEndsServiceImpl extends DatasourceService implements TripEndsService {

    @Override
    public Map<String, Object> summary(DatasourceParam param) {
        return MatsimTripEndsCache.readTripEndsSummary(matsim_data(param));
    }

    @Override
    public Map<String, Object> streets(DatasourceParam param) {
        return MatsimTripEndsCache.readTripEndsStreets(matsim_data(param));
    }

    @Override
    public Map<String, Object> odStreets(DatasourceParam param) {
        return MatsimTripEndsCache.readOdStreets(matsim_data(param));
    }
}
