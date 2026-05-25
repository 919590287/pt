package com.jts.gjcxfzksh.api.controller;

import com.jts.gjcxfzksh.api.common.AjaxResult;
import com.jts.gjcxfzksh.api.model.params.BuildingQueryParam;
import com.jts.gjcxfzksh.api.service.BuildingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pt/buildings")
@Tag(name = "建筑物", description = "建筑物")
public class BuildingController {

    @Resource
    private BuildingService buildingService;

    @Operation(summary = "按视野查询建筑物")
    @PostMapping("/query")
    public AjaxResult query(@RequestBody BuildingQueryParam param) {
        return AjaxResult.ok(buildingService.query(param));
    }

}
