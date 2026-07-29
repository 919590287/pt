package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.api.common.DatasourceService;
import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.service.PopulationService;
import com.jts.gjcxfzksh.config.MatsimConfig;
import com.jts.gjcxfzksh.data.Datasource;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.cache.MatsimPopulationCache;
import com.jts.gjcxfzksh.data.cache.RealPopulationCache;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;

/**
 * 人口分布监测薄服务层（模式照 TransferServiceImpl）：鉴权与取数交给
 * DatasourceService，读缓存交给 MatsimPopulationCache，本层不做任何聚合。
 */
@Service
public class PopulationServiceImpl extends DatasourceService implements PopulationService {

    private static final String REAL_PREFIX = "real::";
    private static final String REAL_DATE_SEPARATOR = "::service-date::";

    @Resource
    private MatsimConfig matsimConfig;

    @Override
    public Map<String, Object> summary(DatasourceParam param) {
        if (isReal(param.getDatasource())) {
            return RealPopulationCache.summary(realSource(param.getDatasource()));
        }
        return MatsimPopulationCache.readPopulationSummary(matsim_data(param));
    }

    @Override
    public Map<String, Object> streets(DatasourceParam param) {
        if (isReal(param.getDatasource())) {
            return RealPopulationCache.streets(realSource(param.getDatasource()));
        }
        return MatsimPopulationCache.readPopulationStreets(matsim_data(param));
    }

    @Override
    public byte[] gridBytes(String datasource) {
        if (isReal(datasource)) {
            return RealPopulationCache.gridBytes(realSource(datasource));
        }
        MatsimData data = Datasource.data(datasource).matsim_data();
        return MatsimPopulationCache.readGridBytes(data);
    }

    @Override
    public String gridTag(String datasource) {
        if (isReal(datasource)) {
            return RealPopulationCache.gridTag(realSource(datasource));
        }
        MatsimData data = Datasource.data(datasource).matsim_data();
        return MatsimPopulationCache.gridBinTag(data);
    }

    private boolean isReal(String datasource) {
        return datasource != null && datasource.startsWith(REAL_PREFIX);
    }

    private Path realSource(String datasource) {
        String encoded = datasource.substring(REAL_PREFIX.length());
        String area = encoded.split(REAL_DATE_SEPARATOR, 2)[0];
        return RealPopulationCache.sourcePath(matsimConfig.realDataPath(area));
    }
}
