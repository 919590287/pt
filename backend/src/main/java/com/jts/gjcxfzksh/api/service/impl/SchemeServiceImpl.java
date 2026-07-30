package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.api.model.vo.SchemeVO;
import com.jts.gjcxfzksh.api.service.SchemeService;
import com.jts.gjcxfzksh.config.MatsimConfig;
import com.jts.gjcxfzksh.data.Datasource;
import com.jts.gjcxfzksh.data.ModelLoadStatus;
import com.jts.gjcxfzksh.data.cache.ModelCacheManager;
import com.jts.gjcxfzksh.data.cache.ModelCacheStatus;
import com.jts.gjcxfzksh.data.entry.Scheme;
import com.jts.gjcxfzksh.exception.BusinessException;
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
                vo.setLoadVersion(Datasource.currentLoadVersion(name));
                vo.setLoadProgressPercent(loadStatus.getProgressPercent());
                vo.setLoadProgressMessage(loadStatus.getProgressMessage());
                vo.setLoadElapsedSeconds(loadStatus.getElapsedSeconds());
                vo.setLoadEtaSeconds(loadStatus.getEtaSeconds());
                vo.setCacheStatus(cacheStatus.getStatus());
                vo.setCacheMessage(cacheStatus.getMessage());
                vo.setCacheProgressPercent(cacheStatus.getProgressPercent());
                vo.setCacheProgressMessage(cacheStatus.getProgressMessage());
                vo.setCacheElapsedSeconds(cacheStatus.getElapsedSeconds());
                vo.setCacheGeneratedAt(cacheStatus.getGeneratedAt());
                vo.setCacheEtaSeconds(cacheStatus.getEtaSeconds());
                vo.setLargeModel(scheme.isLargeModel());
                vo.setOutputBytes(scheme.getOutputBytes());
                vo.setDefault(Boolean.TRUE.equals(scheme.getDesc().get_default()));
                vo.setDetail(scheme.getDesc().getDetail());
                // 对外契约固定 1:1：模型中有多少人/人次就展示多少，不做 desc.scale 扩样。
                vo.setScale(1.0);
                vo.setCuttable(scheme.isCuttable() && !scheme.isLargeModel());
                vo.setOptimization(scheme.getDesc().getOptimization());
                modelList.add(vo);
            }
        });
        return modelList;
    }

    @Override
    public boolean loadModel(String username, String name) {
        Scheme scheme = findAccessibleScheme(username, name);
        if (scheme == null) {
            throw new BusinessException("模型不存在或无权访问: " + name);
        }
        try {
            Datasource.loadAsync(scheme);
            modelCacheManager.enqueueIfMissing(scheme);
        } catch (Exception e) {
            if (e instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException("模型加载启动失败: " + name, e);
        }
        return true;
    }

    @Override
    public boolean unloadModel(String username, String name) {
        Scheme scheme = findAccessibleScheme(username, name);
        if (scheme == null) {
            return false;
        }
        if (!canMutateRuntime(username, scheme)) {
            log.warn("用户[{}]无权卸载公共/他人模型[{}]", username, name);
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
        if (!canMutateRuntime(username, scheme)) {
            log.warn("用户[{}]无权重建公共/他人模型缓存[{}]", username, name);
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

    /** 公共模型是平台共享运行态，普通会话只能加载和读取，不能全局卸载或重建。 */
    private boolean canMutateRuntime(String username, Scheme scheme) {
        return username != null && username.equals(scheme.getScope());
    }
}
