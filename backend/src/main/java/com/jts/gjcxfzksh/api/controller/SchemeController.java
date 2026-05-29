package com.jts.gjcxfzksh.api.controller;

import com.jts.gjcxfzksh.api.common.AjaxResult;
import com.jts.gjcxfzksh.api.common.CurrentUser;
import com.jts.gjcxfzksh.api.model.params.LoadModelParam;
import com.jts.gjcxfzksh.api.model.params.ModelListParam;
import com.jts.gjcxfzksh.api.service.SchemeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/pt/scheme")
@Tag(name = "方案模型", description = "方案模型")
public class SchemeController {

    @Resource
    private SchemeService service;

    @Operation(summary = "方案列表")
    @PostMapping("/schemeList")
    public AjaxResult schemeList() {
        return AjaxResult.ok(service.schemeList(CurrentUser.getUsername()));
    }

    @PostMapping("/modelList")
    @Operation(summary = "模型列表")
    public AjaxResult modelList(@RequestBody ModelListParam param) {
        return AjaxResult.ok(service.modelList(CurrentUser.getUsername(), param.getSchemeName()));
    }

    @PostMapping("/loadModel")
    @Operation(summary = "加载模型")
    public AjaxResult loadModel(@RequestBody LoadModelParam param) {
        return AjaxResult.okError(service.loadModel(CurrentUser.getUsername(), param.getName()));
    }

    @PostMapping("/unloadModel")
    @Operation(summary = "卸载模型")
    public AjaxResult unloadModel(@RequestBody LoadModelParam param) {
        return AjaxResult.okError(service.unloadModel(CurrentUser.getUsername(), param.getName()));
    }

    @PostMapping("/cacheStatus")
    @Operation(summary = "模型缓存状态")
    public AjaxResult cacheStatus(@RequestBody LoadModelParam param) {
        return AjaxResult.ok(service.cacheStatus(CurrentUser.getUsername(), param.getName()));
    }

    @PostMapping("/rebuildCache")
    @Operation(summary = "重建模型缓存")
    public AjaxResult rebuildCache(@RequestBody LoadModelParam param) {
        return AjaxResult.okError(service.rebuildCache(CurrentUser.getUsername(), param.getName()));
    }


}
