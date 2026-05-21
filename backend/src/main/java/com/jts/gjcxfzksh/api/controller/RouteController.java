package com.jts.gjcxfzksh.api.controller;

import com.jts.gjcxfzksh.api.common.AjaxResult;
import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.model.params.RouteChartParam;
import com.jts.gjcxfzksh.api.model.params.RouteInfoParam;
import com.jts.gjcxfzksh.api.model.params.RouteListParam;
import com.jts.gjcxfzksh.api.service.RouteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pt/route")
@Tag(name = "线路总览", description = "线路总览")
public class RouteController {

    @Resource
    private RouteService routeService;

    @Operation(summary = "全部线路")
    @PostMapping("/lineAll")
    public AjaxResult lineAll(@RequestBody DatasourceParam param) {
        return AjaxResult.ok(routeService.lineAll(param));
    }

    @Operation(summary = "线路列表")
    @PostMapping("/routeList")
    public AjaxResult routeList(@RequestBody RouteListParam param) {
        return AjaxResult.ok(routeService.routeList(param));
    }

    @Operation(summary = "线路详情")
    @PostMapping("/routeDetail")
    public AjaxResult routeDetail(@RequestBody RouteInfoParam param) {
        return AjaxResult.ok(routeService.routeDetail(param));
    }

    @Operation(summary = "线路信息")
    @PostMapping("/routeInfo")
    public AjaxResult routeInfo(@RequestBody RouteInfoParam param) {
        return AjaxResult.ok(routeService.routeInfo(param));
    }

    // 图表
    @Operation(summary = "上下车客流")
    @PostMapping("/routeFlow")
    public AjaxResult routeFlow(@RequestBody RouteChartParam param) {
        return AjaxResult.ok(routeService.routeFlow(param));
    }

    // 载客量
    public AjaxResult routeCapacity(@RequestBody RouteChartParam param) {
        return null;
    }

    // 总载客量

    // 上下车站点热力图

    // 站点OD客流量

    // 发车时刻表

}
