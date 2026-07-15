package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.api.common.DatasourceService;
import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.service.CorridorService;
import com.jts.gjcxfzksh.data.cache.MatsimCorridorCache;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 走廊监测薄服务层（模式照 PopulationServiceImpl）：鉴权与取数交给
 * DatasourceService，读缓存交给 MatsimCorridorCache，本层不做任何聚合。
 */
@Service
public class CorridorServiceImpl extends DatasourceService implements CorridorService {

    @Override
    public Map<String, Object> summary(DatasourceParam param) {
        return MatsimCorridorCache.readCorridorSummary(matsim_data(param));
    }

    @Override
    public Map<String, Object> names(DatasourceParam param) {
        return MatsimCorridorCache.readCorridorNames(matsim_data(param));
    }
}
