package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.api.common.DatasourceService;
import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.service.TransferService;
import com.jts.gjcxfzksh.data.cache.MatsimTransferCache;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 换乘分析薄服务层（模式照 FacilityServiceImpl.stationPanel）：鉴权与取数交给
 * DatasourceService，读缓存交给 MatsimTransferCache，本层不做任何聚合。
 */
@Service
public class TransferServiceImpl extends DatasourceService implements TransferService {

    @Override
    public Map<String, Object> summary(DatasourceParam param) {
        return MatsimTransferCache.readTransferSummary(matsim_data(param));
    }

    @Override
    public Map<String, Object> dict(DatasourceParam param) {
        return MatsimTransferCache.readTransferDict(matsim_data(param));
    }
}
