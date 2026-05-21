package com.jts.gjcxfzksh.api.controller;

import com.jts.gjcxfzksh.api.common.AjaxResult;
import com.jts.gjcxfzksh.api.model.params.TileNetworkParam;
import com.jts.gjcxfzksh.api.service.NetworkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pt/network")
@Tag(name = "路网总览", description = "路网总览")
public class NetworkController {

    @Resource
    private NetworkService networkService;

    @Operation(summary = "瓦片路网, zoom level13")
    @PostMapping("/tile")
    public AjaxResult tile(@RequestBody TileNetworkParam param) {
        return AjaxResult.ok(networkService.tile(param));
    }

}
