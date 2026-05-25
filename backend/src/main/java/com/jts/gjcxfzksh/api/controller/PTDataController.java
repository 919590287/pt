package com.jts.gjcxfzksh.api.controller;

import com.jts.gjcxfzksh.api.common.AjaxResult;
import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.service.PTDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @Operation(summary = "轨迹演示数据")
    @PostMapping("/trajectory")
    public AjaxResult trajectory(@RequestBody DatasourceParam param) {
        return AjaxResult.ok(service.trajectory(param));
    }

    @Operation(summary = "轨迹演示分块数据")
    @PostMapping("/trajectory/chunk")
    public AjaxResult trajectoryChunk(@RequestBody DatasourceParam param, @RequestParam(value = "start", defaultValue = "0") int start) {
        return AjaxResult.ok(service.trajectoryChunk(param, start));
    }

    @Operation(summary = "轨迹演示二进制分块数据")
    @PostMapping(value = "/trajectory/chunk.bin", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> trajectoryChunkBinary(@RequestBody DatasourceParam param, @RequestParam(value = "start", defaultValue = "0") int start) {
        byte[] chunk = service.trajectoryChunkBinary(param, start);
        if (chunk == null) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new byte[0]);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(chunk);
    }

}
