package com.jts.gjcxfzksh.api.controller;

import com.jts.gjcxfzksh.api.common.AjaxResult;
import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.service.PTDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pt/data")
@Tag(name = "数据总览", description = "数据总览")
public class PTDataController {

    @Resource
    private PTDataService service;

    @Operation(summary = "数据总览")
    @PostMapping("/info")
    public AjaxResult info(@RequestBody DatasourceParam param) {
        return AjaxResult.ok(service.info(param));
    }

    @Operation(summary = "中心的坐标")
    @PostMapping("/center")
    public AjaxResult center(@RequestBody DatasourceParam param) {
        return AjaxResult.ok(service.center(param));
    }

}
