package com.jts.gjcxfzksh.api.controller;

import com.jts.gjcxfzksh.api.common.AjaxResult;
import com.jts.gjcxfzksh.api.model.params.RealPassengerFlowParam;
import com.jts.gjcxfzksh.api.service.RealPassengerFlowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pt/real-data/passenger-flow")
@Tag(name = "真实客流", description = "真实刷卡与车辆运行数据的运行监测/客流分析适配接口")
public class RealPassengerFlowController {

    @Resource
    private RealPassengerFlowService service;

    @Operation(summary = "真实数据模块能力")
    @PostMapping("/capabilities")
    public AjaxResult capabilities(@RequestBody RealPassengerFlowParam param) {
        return AjaxResult.ok(service.capabilities(param.getAreaName()));
    }

    @Operation(summary = "总体客流")
    @PostMapping("/overallFlow")
    public AjaxResult overallFlow(@RequestBody RealPassengerFlowParam param) {
        return AjaxResult.ok(service.overallFlow(param.getAreaName(), param.getServiceDate()));
    }

    @Operation(summary = "线路客流索引")
    @PostMapping("/routePanel")
    public AjaxResult routePanel(@RequestBody RealPassengerFlowParam param) {
        return AjaxResult.ok(service.routePanel(param.getAreaName(), param.getServiceDate()));
    }

    @Operation(summary = "线路客流详情")
    @PostMapping("/routePanelDetail")
    public AjaxResult routePanelDetail(@RequestBody RealPassengerFlowParam param) {
        return AjaxResult.ok(service.routePanelDetail(param));
    }

    @Operation(summary = "站点客流索引")
    @PostMapping("/stationPanel")
    public AjaxResult stationPanel(@RequestBody RealPassengerFlowParam param) {
        return AjaxResult.ok(service.stationPanel(param.getAreaName(), param.getServiceDate()));
    }

    @Operation(summary = "站点客流详情")
    @PostMapping("/stationPanelDetail")
    public AjaxResult stationPanelDetail(@RequestBody RealPassengerFlowParam param) {
        return AjaxResult.ok(service.stationPanelDetail(param));
    }

    @Operation(summary = "真实客流体检指标")
    @PostMapping("/evaluation")
    public AjaxResult evaluation(@RequestBody RealPassengerFlowParam param) {
        return AjaxResult.ok(service.evaluation(
                param.getAreaName(), param.getServiceDate(), param.getDistrict()));
    }

    @Operation(summary = "真实公交线网站点中心")
    @PostMapping("/center")
    public AjaxResult center(@RequestBody RealPassengerFlowParam param) {
        return AjaxResult.ok(service.center(param.getAreaName(), param.getServiceDate()));
    }

    @Operation(summary = "真实上下车空间分布与站点 OD")
    @PostMapping("/tripEnds")
    public AjaxResult tripEnds(@RequestBody RealPassengerFlowParam param) {
        return AjaxResult.ok(service.tripEnds(param.getAreaName(), param.getServiceDate()));
    }

    @Operation(summary = "真实公交重复区间与客流走廊")
    @PostMapping("/corridor")
    public AjaxResult corridor(@RequestBody RealPassengerFlowParam param) {
        return AjaxResult.ok(service.corridor(param.getAreaName(), param.getServiceDate()));
    }

    @Operation(summary = "真实车辆运行小时状态")
    @PostMapping("/vehicle")
    public AjaxResult vehicle(@RequestBody RealPassengerFlowParam param) {
        return AjaxResult.ok(service.vehicle(param.getAreaName(), param.getServiceDate()));
    }
}
