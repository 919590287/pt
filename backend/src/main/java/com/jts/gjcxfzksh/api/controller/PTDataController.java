package com.jts.gjcxfzksh.api.controller;

import com.jts.gjcxfzksh.api.common.AjaxResult;
import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.service.PTDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

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

    @Operation(summary = "体检评估指标(全市口径)")
    @PostMapping("/evaluation")
    public AjaxResult evaluation(@RequestBody DatasourceParam param) {
        return AjaxResult.ok(service.evaluation(param));
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

    @Operation(summary = "轨迹演示二进制分块数据(GET 可缓存)")
    @GetMapping(value = "/trajectory/chunk.bin", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<?> trajectoryChunkBinaryGet(
            @RequestParam("datasource") String datasource,
            @RequestParam(value = "start", defaultValue = "0") int start,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        DatasourceParam param = new DatasourceParam();
        param.setDatasource(datasource);

        // 分块内容对固定 events 永不改变：强校验 ETag + immutable 长缓存，
        // 让浏览器/SW 在 max-age 内直接命中本地、不再回源；命中 If-None-Match 时回 304 空体。
        // cachePrivate：该资源需鉴权，禁止中间共享缓存存储；浏览器/SW/IndexedDB 缓存不受影响。
        String etag = service.trajectoryChunkTag(param, start);
        CacheControl immutableCache = CacheControl.maxAge(365, TimeUnit.DAYS).cachePrivate().immutable();
        if (etagMatches(etag, ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(immutableCache)
                    .build();
        }

        Path chunkPath = service.trajectoryChunkBinaryPath(param, start);
        if (chunkPath != null) {
            ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .cacheControl(immutableCache);
            if (etag != null) {
                builder = builder.eTag(etag);
            }
            try {
                builder = builder.contentLength(Files.size(chunkPath));
            } catch (Exception ignored) {
                // Content-Length is an optimization; streaming still works without it.
            }
            return builder.body(new FileSystemResource(chunkPath));
        }

        byte[] chunk = service.trajectoryChunkBinary(param, start);
        if (chunk == null) {
            // 缓存尚未就绪：202 触发后台构建，且不缓存，让前端稍后重试。
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .cacheControl(CacheControl.noStore())
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(new byte[0]);
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .cacheControl(immutableCache);
        if (etag != null) {
            builder = builder.eTag(etag);
        }
        return builder.body(chunk);
    }

    private static boolean etagMatches(String etag, String ifNoneMatch) {
        if (etag == null || ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String token : ifNoneMatch.split(",")) {
            String candidate = token.trim();
            if ("*".equals(candidate) || etag.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

}
