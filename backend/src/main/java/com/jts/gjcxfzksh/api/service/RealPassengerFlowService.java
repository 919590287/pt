package com.jts.gjcxfzksh.api.service;

import com.jts.gjcxfzksh.api.model.params.RealPassengerFlowParam;

import java.util.Map;

public interface RealPassengerFlowService {

    /**
     * 生成全部区域、全部运营日可直接下发的真实客流面板工件。
     * 工件按源文件指纹持久化，重复调用只做就绪校验。
     */
    void prepareAllCaches();

    Map<String, Object> capabilities(String areaName);

    Map<String, Object> preload(String areaName, String serviceDate);

    Map<String, Object> overallFlow(String areaName, String serviceDate);

    Map<String, Object> routePanel(String areaName, String serviceDate);

    Map<String, Object> routePanelDetail(RealPassengerFlowParam param);

    Map<String, Object> departureTimetable(RealPassengerFlowParam param);

    Map<String, Object> departurePanel(RealPassengerFlowParam param);

    Map<String, Object> stationPanel(String areaName, String serviceDate);

    Map<String, Object> stationPanelDetail(RealPassengerFlowParam param);

    Map<String, Object> evaluation(String areaName, String serviceDate, String district);

    Map<String, Object> center(String areaName, String serviceDate);

    Map<String, Object> tripEnds(String areaName, String serviceDate);

    Map<String, Object> corridor(String areaName, String serviceDate);

    Map<String, Object> vehicle(String areaName, String serviceDate);
}
