package com.jts.gjcxfzksh.api.service;

import com.jts.gjcxfzksh.api.model.vo.SchemeVO;

import java.util.Collection;
import java.util.List;

public interface SchemeService {

    // 方案列表
    Collection<String> schemeList();

    // 模型列表
    List<SchemeVO> modelList(String schemeName);

    boolean loadModel(String name);

}
