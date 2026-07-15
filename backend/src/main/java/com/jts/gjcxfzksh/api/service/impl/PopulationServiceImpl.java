package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.api.common.DatasourceService;
import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.service.PopulationService;
import com.jts.gjcxfzksh.data.cache.MatsimPopulationCache;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 人口分布监测薄服务层（模式照 TransferServiceImpl）：鉴权与取数交给
 * DatasourceService，读缓存交给 MatsimPopulationCache，本层不做任何聚合。
 */
@Service
public class PopulationServiceImpl extends DatasourceService implements PopulationService {

    @Override
    public Map<String, Object> summary(DatasourceParam param) {
        return MatsimPopulationCache.readPopulationSummary(matsim_data(param));
    }

    @Override
    public Map<String, Object> streets(DatasourceParam param) {
        return MatsimPopulationCache.readPopulationStreets(matsim_data(param));
    }
}
