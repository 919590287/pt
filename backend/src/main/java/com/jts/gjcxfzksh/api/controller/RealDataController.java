package com.jts.gjcxfzksh.api.controller;

import com.jts.gjcxfzksh.api.common.AjaxResult;
import com.jts.gjcxfzksh.api.common.CurrentUser;
import com.jts.gjcxfzksh.api.model.params.RealDataCommitParam;
import com.jts.gjcxfzksh.api.model.params.RealDataParam;
import com.jts.gjcxfzksh.api.service.RealDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/pt/real-data")
@Tag(name = "真实数据", description = "真实数据")
public class RealDataController {

    @Resource
    private RealDataService realDataService;

    @Operation(summary = "区域列表")
    @PostMapping("/areaList")
    public AjaxResult areaList() {
        return AjaxResult.ok(realDataService.areaList());
    }

    @Operation(summary = "行政区范围")
    @PostMapping("/adminDistricts")
    public AjaxResult adminDistricts(@RequestBody RealDataParam param) {
        return AjaxResult.ok(realDataService.adminDistricts(param.getAreaName()));
    }

    @Operation(summary = "公交线路站点")
    @PostMapping("/busLineStation")
    public AjaxResult busLineStation(@RequestBody RealDataParam param) {
        return AjaxResult.ok(realDataService.busLineStation(param.getAreaName(), param.getVersionId()));
    }

    @Operation(summary = "真实数据历史")
    @PostMapping("/history")
    public AjaxResult history(@RequestBody RealDataParam param) {
        return AjaxResult.ok(realDataService.history(param.getAreaName()));
    }

    @Operation(summary = "提交真实数据编辑")
    @PostMapping("/commitEdits")
    public AjaxResult commitEdits(@RequestBody RealDataCommitParam param) {
        return AjaxResult.ok(realDataService.commitEdits(CurrentUser.getUsername(), param));
    }

    @Operation(summary = "切换真实数据版本")
    @PostMapping("/revert")
    public AjaxResult revert(@RequestBody RealDataParam param) {
        return AjaxResult.ok(realDataService.revertEdits(CurrentUser.getUsername(), param));
    }

    @Operation(summary = "上传真实数据 SHP 并比对")
    @PostMapping(value = "/compareUpload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AjaxResult compareUpload(
            @RequestParam("areaName") String areaName,
            @RequestParam("datasetType") String datasetType,
            @RequestParam("files") List<MultipartFile> files
    ) {
        return AjaxResult.ok(realDataService.compareUpload(CurrentUser.getUsername(), areaName, datasetType, files));
    }
}
