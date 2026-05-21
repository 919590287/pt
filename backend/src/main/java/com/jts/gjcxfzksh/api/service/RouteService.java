package com.jts.gjcxfzksh.api.service;

import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.model.params.RouteChartParam;
import com.jts.gjcxfzksh.api.model.params.RouteInfoParam;
import com.jts.gjcxfzksh.api.model.params.RouteListParam;
import com.jts.gjcxfzksh.api.model.vo.FacilityFlowVO;
import com.jts.gjcxfzksh.api.model.vo.LineVO;
import com.jts.gjcxfzksh.api.model.vo.RouteDetailVO;
import com.jts.gjcxfzksh.api.model.vo.RouteVO;

import java.util.List;
import java.util.Map;

public interface RouteService {

    List<RouteVO> routeList(RouteListParam param);

    RouteDetailVO routeDetail(RouteInfoParam param);

    /**
     * 线路总览信息
     */
    Map<String, Object> routeInfo(RouteInfoParam param);

    List<RouteVO> routeAll(RouteInfoParam param);

    List<LineVO> lineAll(DatasourceParam param);

    List<FacilityFlowVO> routeFlow(RouteChartParam param);


}
