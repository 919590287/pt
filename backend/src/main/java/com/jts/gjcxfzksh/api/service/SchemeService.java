package com.jts.gjcxfzksh.api.service;

import com.jts.gjcxfzksh.api.model.vo.SchemeVO;
import com.jts.gjcxfzksh.data.cache.ModelCacheStatus;

import java.util.Collection;
import java.util.List;

public interface SchemeService {

    // 方案列表
    Collection<String> schemeList(String username);

    // 模型列表
    List<SchemeVO> modelList(String username, String schemeName);

    boolean loadModel(String username, String name);

    boolean unloadModel(String username, String name);

    ModelCacheStatus cacheStatus(String username, String name);

    boolean rebuildCache(String username, String name);

}
