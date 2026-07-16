package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.api.common.DatasourceService;
import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.service.LinkSpeedService;
import com.jts.gjcxfzksh.data.cache.MatsimLinkSpeedCache;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 路段公交车速薄服务层（模式照 CorridorServiceImpl）：鉴权与取数交给
 * DatasourceService，读缓存交给 MatsimLinkSpeedCache，本层不做任何聚合。
 */
@Service
public class LinkSpeedServiceImpl extends DatasourceService implements LinkSpeedService {

    @Override
    public Map<String, Object> summary(DatasourceParam param) {
        return MatsimLinkSpeedCache.readSummary(matsim_data(param));
    }
}
