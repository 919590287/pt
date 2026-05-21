package com.jts.gjcxfzksh.api.service.impl;

import com.jts.gjcxfzksh.api.model.vo.SchemeVO;
import com.jts.gjcxfzksh.api.service.SchemeService;
import com.jts.gjcxfzksh.config.MatsimConfig;
import com.jts.gjcxfzksh.data.Datasource;
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

    @Override
    public Collection<String> schemeList() {
        Set<String> schemes = new LinkedHashSet<>();
        matsimConfig.getSchemes().keySet().forEach(schemeName -> {
            schemes.add(schemeName.substring(0, schemeName.indexOf("/")));
        });
        return schemes;
    }

    @Override
    public List<SchemeVO> modelList(String schemeName) {
        Map<String, Scheme> schemes = matsimConfig.getSchemes();
        List<SchemeVO> modelList = new ArrayList<>();
        schemes.forEach((name, scheme) -> {
            if (name.startsWith(schemeName)) {
                SchemeVO vo = new SchemeVO();
                vo.setName(name);
                vo.setLoadStatus(Datasource.loadStatus(name));
                modelList.add(vo);
            }
        });
        return modelList;
    }

    @Override
    public boolean loadModel(String name) {
        Map<String, Scheme> schemes = matsimConfig.getSchemes();
        Scheme scheme = schemes.get(name);
        try {
            // 如果在加载中就等待加载
            if (Datasource.loadingStatus(name)) {
                log.info("[{}]正在加载中，等待加载完成", name);
                while (Datasource.loadingStatus(name)) {
                    Thread.sleep(1000);
                }
            } else {
                log.info("[{}]已加载，无需重复加载", name);
                if (!Datasource.loadStatus(name)) {
                    Datasource.load(scheme);
                }
            }
        } catch (Exception e) {
            log.error("加载失败{}", name, e);
            return false;
        }
        return true;
    }
}
