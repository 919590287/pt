package com.jts.gjcxfzksh.api.controller;

import com.jts.gjcxfzksh.api.common.AjaxResult;
import com.jts.gjcxfzksh.api.common.HttpCacheSupport;
import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.service.TripEndsService;
import com.jts.gjcxfzksh.data.Datasource;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.cache.MatsimTripEndsCache;
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

/** 街道面 GeoJSON 复用 PopulationController 的 /pt/population/streets.geojson（模型无关资源）。 */
@RestController
@RequestMapping("/pt/tripends")
@Tag(name = "出行分布监测", description = "公交出行监测 · 出行分布监测（原起终点分布监测，端点=活动出行起终点）")
public class TripEndsController {

    @Resource
    private TripEndsService tripEndsService;

    @Operation(summary = "出行分布汇总（总量指标+口径参数，未就绪返回 generating）")
    @PostMapping("/summary")
    public AjaxResult summary(@RequestBody DatasourceParam param) {
        return AjaxResult.ok(tripEndsService.summary(param));
    }

    @Operation(summary = "街道级出行起终点统计（176 街道全量+totals，未就绪返回 generating）")
    @PostMapping("/streets")
    public AjaxResult streets(@RequestBody DatasourceParam param) {
        return AjaxResult.ok(tripEndsService.streets(param));
    }

    @Operation(summary = "街道级公交出行OD对（有向，o/d=街道要素索引，按人次降序；未就绪返回 generating）")
    @PostMapping("/od/streets")
    public AjaxResult odStreets(@RequestBody DatasourceParam param) {
        return AjaxResult.ok(tripEndsService.odStreets(param));
    }

    /**
     * 二进制栅格 OD 对表（PGOD 契约，人次降序 + 截断，前端按前缀取 Top-K）。
     * 缓存策略与 grid.bin 完全一致；ETag 同源（manifest 指纹），URL 不同互不干扰。
     */
    @Operation(summary = "二进制栅格OD对表(GET 可缓存)")
    @GetMapping(value = "/od/grid.bin", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> odGridBinary(
            @RequestParam("datasource") String datasource,
            // v 为前端缓存击穿参数（URL 参与浏览器缓存键），服务端不使用
            @RequestParam(value = "v", required = false) String v,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        MatsimData data = Datasource.data(datasource).matsim_data();
        String tag = MatsimTripEndsCache.gridBinTag(data);
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
        byte[] body = MatsimTripEndsCache.readOdGridBytes(data);
        if (body == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .eTag(etag)
                .cacheControl(HttpCacheSupport.immutablePrivate())
                .body(body);
    }

    /**
     * 二进制出行起终点栅格表（PGRD 契约，home 列=起点、work 列=终点）。写法逐行照
     * PopulationController.gridBinary：强校验 ETag + immutable，鉴权走 AuthInterceptor。
     * 缓存未就绪返回 404（前端以 summary 的 generating 态轮询，不解析 404 体）。
     */
    @Operation(summary = "二进制出行起终点栅格表(GET 可缓存)")
    @GetMapping(value = "/grid.bin", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> gridBinary(
            @RequestParam("datasource") String datasource,
            // v 为前端缓存击穿参数（URL 参与浏览器缓存键），服务端不使用
            @RequestParam(value = "v", required = false) String v,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        MatsimData data = Datasource.data(datasource).matsim_data();
        String tag = MatsimTripEndsCache.gridBinTag(data);
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
        byte[] body = MatsimTripEndsCache.readGridBytes(data);
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
