package com.jts.gjcxfzksh.api.service;

import com.jts.gjcxfzksh.api.model.params.RealPassengerFlowParam;

import java.util.Map;

public interface RealPassengerFlowService {

    Map<String, Object> capabilities(String areaName);

    Map<String, Object> overallFlow(String areaName, String serviceDate);

    Map<String, Object> routePanel(String areaName, String serviceDate);

    Map<String, Object> routePanelDetail(RealPassengerFlowParam param);

    Map<String, Object> stationPanel(String areaName, String serviceDate);

    Map<String, Object> stationPanelDetail(RealPassengerFlowParam param);

    Map<String, Object> evaluation(String areaName, String serviceDate, String district);

    Map<String, Object> center(String areaName, String serviceDate);

    Map<String, Object> tripEnds(String areaName, String serviceDate);

    Map<String, Object> corridor(String areaName, String serviceDate);

    Map<String, Object> vehicle(String areaName, String serviceDate);
}
