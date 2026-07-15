package com.jts.gjcxfzksh.api.controller;

import com.jts.gjcxfzksh.api.common.AjaxResult;
import com.jts.gjcxfzksh.api.common.HttpCacheSupport;
import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.service.CorridorService;
import com.jts.gjcxfzksh.data.Datasource;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.cache.MatsimCorridorCache;
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
@RequestMapping("/pt/corridor")
@Tag(name = "客流走廊监测", description = "客流走廊监测 · 线路重复系数")
public class CorridorController {

    @Resource
    private CorridorService corridorService;

    @Operation(summary = "走廊汇总（总量指标+口径参数，未就绪返回 generating）")
    @PostMapping("/summary")
    public AjaxResult summary(@RequestBody DatasourceParam param) {
        return AjaxResult.ok(corridorService.summary(param));
    }

    @Operation(summary = "路名字典+街道district数组（未就绪返回 generating）")
    @PostMapping("/names")
    public AjaxResult names(@RequestBody DatasourceParam param) {
        return AjaxResult.ok(corridorService.names(param));
    }

    /**
     * 二进制路段表（PCRD 契约，系数升序）。写法逐行照 PopulationController.gridBinary：
     * 强校验 ETag + immutable，鉴权走 AuthInterceptor。未就绪返回 404（前端轮询 summary）。
     */
    @Operation(summary = "二进制走廊路段表(GET 可缓存)")
    @GetMapping(value = "/links.bin", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> linksBinary(
            @RequestParam("datasource") String datasource,
            // v 为前端缓存击穿参数（URL 参与浏览器缓存键），服务端不使用
            @RequestParam(value = "v", required = false) String v,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        MatsimData data = Datasource.data(datasource).matsim_data();
        String tag = MatsimCorridorCache.linksBinTag(data);
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
        byte[] body = MatsimCorridorCache.readLinksBytes(data);
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
