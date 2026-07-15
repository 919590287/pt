package com.jts.gjcxfzksh.api.controller;

import com.jts.gjcxfzksh.api.common.AjaxResult;
import com.jts.gjcxfzksh.api.common.HttpCacheSupport;
import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.service.PopulationService;
import com.jts.gjcxfzksh.data.Datasource;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.data.cache.MatsimPopulationCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.http.HttpHeaders;
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
@RequestMapping("/pt/population")
@Tag(name = "人口分布监测", description = "公交出行监测 · 人口分布监测")
public class PopulationController {

    @Resource
    private PopulationService populationService;

    @Operation(summary = "人口分布汇总（总量指标+活动类型，未就绪返回 generating）")
    @PostMapping("/summary")
    public AjaxResult summary(@RequestBody DatasourceParam param) {
        return AjaxResult.ok(populationService.summary(param));
    }

    @Operation(summary = "街道级人口统计（176 街道全量+totals，未就绪返回 generating）")
    @PostMapping("/streets")
    public AjaxResult streets(@RequestBody DatasourceParam param) {
        return AjaxResult.ok(populationService.streets(param));
    }

    /**
     * 二进制人口栅格表（§3）。写法逐行照 TransferController.eventsBinary：强校验 ETag + immutable，
     * 鉴权同样走 AuthInterceptor 的 token/Authorization 请求头，不经 DatasourceService。
     * 缓存未就绪返回 404（前端以 summary 的 generating 态轮询，不解析 404 体）。
     */
    @Operation(summary = "二进制人口栅格表(GET 可缓存)")
    @GetMapping(value = "/grid.bin", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> gridBinary(
            @RequestParam("datasource") String datasource,
            // v 为前端缓存击穿参数（URL 参与浏览器缓存键），服务端不使用
            @RequestParam(value = "v", required = false) String v,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        MatsimData data = Datasource.data(datasource).matsim_data();
        // ETag 取 manifest 的 sourceFingerprint + 缓存版本哈希；null 即未就绪
        String tag = MatsimPopulationCache.gridBinTag(data);
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
        byte[] body = MatsimPopulationCache.readGridBytes(data);
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
     * 街道面 GeoJSON（模型无关，无 datasource 参数）：内嵌 gz 资源原字节直出，
     * 显式 Content-Encoding: gzip + Content-Type: application/json，浏览器透明解压。
     * 已带 Content-Encoding 的响应会被 Undertow 的动态压缩跳过，不会二次压缩。
     * ETag 取资源内容 sha256（资源随版本升级即失效）。
     */
    @Operation(summary = "街道面GeoJSON(模型无关, 预压缩gz直出, GET 可缓存)")
    @GetMapping(value = "/streets.geojson")
    public ResponseEntity<byte[]> streetsGeojson(
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        String etag = "\"" + MatsimPopulationCache.streetsGeojsonTag() + "\"";
        if (HttpCacheSupport.etagMatches(etag, ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(HttpCacheSupport.immutablePrivate())
                    .build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                .contentType(MediaType.APPLICATION_JSON)
                .eTag(etag)
                .cacheControl(HttpCacheSupport.immutablePrivate())
                .body(MatsimPopulationCache.streetsGeojsonGzBytes());
    }
}
