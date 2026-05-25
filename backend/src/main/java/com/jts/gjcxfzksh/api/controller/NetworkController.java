package com.jts.gjcxfzksh.api.controller;

import com.jts.gjcxfzksh.api.common.AjaxResult;
import com.jts.gjcxfzksh.api.common.TileBinaryEncoder;
import com.jts.gjcxfzksh.api.model.params.TileNetworkParam;
import com.jts.gjcxfzksh.api.service.NetworkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@RestController
@RequestMapping("/pt/network")
@Tag(name = "路网总览", description = "路网总览")
public class NetworkController {

    @Resource
    private NetworkService networkService;

    private final ConcurrentMap<String, byte[]> fullBinaryCache = new ConcurrentHashMap<>();

    @Operation(summary = "瓦片路网, zoom level13")
    @PostMapping("/tile")
    public AjaxResult tile(@RequestBody TileNetworkParam param) {
        return AjaxResult.ok(networkService.tile(param));
    }

    @Operation(summary = "二进制瓦片路网")
    @PostMapping(value = "/tile.bin", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> tileBinary(@RequestBody TileNetworkParam param) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(TileBinaryEncoder.encodeLinks(networkService.tile(param)));
    }

    @Operation(summary = "二进制全量路网")
    @PostMapping(value = "/full.bin", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> fullBinary(@RequestBody TileNetworkParam param) {
        String cacheKey = String.valueOf(param.getDatasource());
        byte[] data = fullBinaryCache.computeIfAbsent(cacheKey,
                ignored -> TileBinaryEncoder.encodeLinks(networkService.full(param)));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(data);
    }

}
