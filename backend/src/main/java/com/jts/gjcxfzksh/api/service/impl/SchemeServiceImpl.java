package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.api.model.vo.SchemeVO;
import com.jts.gjcxfzksh.api.service.SchemeService;
import com.jts.gjcxfzksh.config.MatsimConfig;
import com.jts.gjcxfzksh.data.Datasource;
import com.jts.gjcxfzksh.data.ModelLoadStatus;
import com.jts.gjcxfzksh.data.cache.ModelCacheManager;
import com.jts.gjcxfzksh.data.cache.ModelCacheStatus;
import com.jts.gjcxfzksh.data.entry.Scheme;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class SchemeServiceImpl implements SchemeService {

    @Resource
    MatsimConfig matsimConfig;

    @Resource
    ModelCacheManager modelCacheManager;

    @Override
    public Collection<String> schemeList(String username) {
        Set<String> schemes = new LinkedHashSet<>();
        matsimConfig.visibleSchemes(username).forEach((name, scheme) -> {
            schemes.add(scheme.getSchemeName());
        });
        return schemes;
    }

    @Override
    public List<SchemeVO> modelList(String username, String schemeName) {
        Map<String, Scheme> schemes = matsimConfig.visibleSchemes(username);
        List<SchemeVO> modelList = new ArrayList<>();
        schemes.forEach((name, scheme) -> {
            if (schemeName != null && schemeName.equals(scheme.getSchemeName())) {
                SchemeVO vo = new SchemeVO();
                vo.setName(name);
                vo.setDisplayName(scheme.getDisplayName());
                vo.setSchemeName(scheme.getSchemeName());
                vo.setScope(scheme.getScope());
                vo.setScopeLabel(MatsimConfig.PUBLIC_SCOPE.equals(scheme.getScope()) ? "公共" : "我的");
                ModelLoadStatus loadStatus = Datasource.loadStatusDetail(name);
                ModelCacheStatus cacheStatus = modelCacheManager.status(scheme);
                vo.setLoadStatus(loadStatus.isLoaded());
                vo.setLoadStage(loadStatus.getStage());
                vo.setLoadMessage(loadStatus.getMessage());
                vo.setCacheStatus(cacheStatus.getStatus());
                vo.setCacheMessage(cacheStatus.getMessage());
                vo.setCacheProgressPercent(cacheStatus.getProgressPercent());
                vo.setCacheProgressMessage(cacheStatus.getProgressMessage());
                vo.setCacheElapsedSeconds(cacheStatus.getElapsedSeconds());
                vo.setCacheEtaSeconds(cacheStatus.getEtaSeconds());
                vo.setLargeModel(scheme.isLargeModel());
                vo.setOutputBytes(scheme.getOutputBytes());
                vo.setDefault(Boolean.TRUE.equals(scheme.getDesc().get_default()));
                vo.setDetail(scheme.getDesc().getDetail());
                vo.setScale(scheme.getDesc().getScale());
                modelList.add(vo);
            }
        });
        return modelList;
    }

    @Override
    public boolean loadModel(String username, String name) {
        Scheme scheme = findAccessibleScheme(username, name);
        if (scheme == null) {
            return false;
        }
        try {
            Datasource.loadAsync(scheme);
            modelCacheManager.enqueueIfMissing(scheme);
        } catch (Exception e) {
            log.error("加载失败{}", name, e);
            return false;
        }
        return true;
    }

    @Override
    public boolean unloadModel(String username, String name) {
        Scheme scheme = findAccessibleScheme(username, name);
        if (scheme == null) {
            return false;
        }
        Datasource.unload(name);
        return true;
    }

    @Override
    public ModelCacheStatus cacheStatus(String username, String name) {
        Scheme scheme = findAccessibleScheme(username, name);
        return scheme == null ? ModelCacheStatus.missing("") : modelCacheManager.status(scheme);
    }

    @Override
    public boolean rebuildCache(String username, String name) {
        Scheme scheme = findAccessibleScheme(username, name);
        if (scheme == null) {
            return false;
        }
        modelCacheManager.enqueue(scheme);
        return true;
    }

    private Scheme findAccessibleScheme(String username, String name) {
        Map<String, Scheme> schemes = matsimConfig.getSchemes();
        Scheme scheme = schemes.get(name);
        if (scheme == null || !matsimConfig.isSchemeVisible(name, username)) {
            log.warn("[{}]模型不存在或当前用户无权访问", name);
            return null;
        }
        return scheme;
    }
}
