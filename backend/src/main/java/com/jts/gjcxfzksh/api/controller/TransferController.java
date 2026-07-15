package com.jts.gjcxfzksh.api.controller;

import com.jts.gjcxfzksh.api.common.AjaxResult;
import com.jts.gjcxfzksh.api.common.HttpCacheSupport;
import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.service.TransferService;
import com.jts.gjcxfzksh.data.Datasource;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.cache.MatsimTransferCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
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

@RestController
@RequestMapping("/pt/transfer")
@Tag(name = "换乘分析", description = "公交—地铁换乘分析")
public class TransferController {

    @Resource
    private TransferService transferService;

    @Operation(summary = "全网换乘汇总（指标卡+Top榜，未就绪返回 generating）")
    @PostMapping("/summary")
    public AjaxResult summary(@RequestBody DatasourceParam param) {
        return AjaxResult.ok(transferService.summary(param));
    }

    @Operation(summary = "换乘字典（枢纽/线路/站点+scale+生成参数）")
    @PostMapping("/dict")
    public AjaxResult dict(@RequestBody DatasourceParam param) {
        return AjaxResult.ok(transferService.dict(param));
    }

    /**
     * 列式换乘事件表（§11.2）。写法照 RouteController 的 GET .bin：强校验 ETag + immutable，
     * 鉴权同样走 AuthInterceptor 的 token/Authorization 请求头，不经 DatasourceService。
     * 缓存未就绪时无既有 .bin 先例可循，返回 404（前端以 summary 的 generating 态轮询，不解析 404 体）。
     */
    @Operation(summary = "二进制换乘事件表(GET 可缓存)")
    @GetMapping(value = "/events.bin", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> eventsBinary(
            @RequestParam("datasource") String datasource,
            // v 为前端缓存击穿参数（URL 参与浏览器缓存键），服务端不使用
            @RequestParam(value = "v", required = false) String v,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        MatsimData data = Datasource.data(datasource).matsim_data();
        // ETag 取 manifest 的 sourceFingerprint + 缓存版本哈希（§9.2）；null 即未就绪
        String tag = MatsimTransferCache.eventsBinTag(data);
        if (tag == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        String etag = "\"" + tag + "\"";
        if (HttpCacheSupport.etagMatches(etag, ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(HttpCacheSupport.immutablePrivate())
                    .build();
        }
        byte[] body = MatsimTransferCache.readEventsBytes(data);
        if (body == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .eTag(etag)
                .cacheControl(HttpCacheSupport.immutablePrivate())
                .body(body);
    }
}
