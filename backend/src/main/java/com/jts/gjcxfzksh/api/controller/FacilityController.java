package com.jts.gjcxfzksh.api.controller;

import com.jts.gjcxfzksh.api.common.AjaxResult;
import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.service.FacilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pt/facility")
@Tag(name = "站点总览", description = "站点总览")
public class FacilityController {

    @Resource
    private FacilityService facilityService;

    @Operation(summary = "全部站点")
    @PostMapping("/facilityAll")
    public AjaxResult facilityAll(@RequestBody DatasourceParam param) {
        return AjaxResult.ok(facilityService.facilityAll(param));
    }

    @Operation(summary = "站点客流监测右侧面板缓存")
    @PostMapping("/stationPanel")
    public AjaxResult stationPanel(@RequestBody DatasourceParam param) {
        return AjaxResult.ok(facilityService.stationPanel(param));
    }

}
