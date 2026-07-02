package com.jts.gjcxfzksh.api.controller;

import com.jts.gjcxfzksh.api.common.AjaxResult;
import com.jts.gjcxfzksh.api.common.HttpCacheSupport;
import com.jts.gjcxfzksh.api.common.TileBinaryEncoder;
import com.jts.gjcxfzksh.api.model.params.TileNetworkParam;
import com.jts.gjcxfzksh.api.service.NetworkService;
import com.jts.gjcxfzksh.data.Datasource;
import com.jts.gjcxfzksh.data.cache.MatsimPrecomputedCache;
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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/pt/network")
@Tag(name = "路网总览", description = "路网总览")
public class NetworkController {

    private static final int FULL_BINARY_CACHE_MAX_ENTRIES = 4;

    @Resource
    private NetworkService networkService;

    /**
     * 全量路网二进制缓存：key 含模型加载版本（重载后旧字节自动失效），
     * LRU 上限 {@value FULL_BINARY_CACHE_MAX_ENTRIES} 份，避免多模型场景内存无上限增长。
     */
    private final Map<String, byte[]> fullBinaryCache = Collections.synchronizedMap(
            new LinkedHashMap<>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > FULL_BINARY_CACHE_MAX_ENTRIES;
                }
            }
    );

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

    @Operation(summary = "二进制瓦片路网(GET 可缓存)")
    @GetMapping(value = "/tile.bin", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> tileBinaryGet(
            @RequestParam("datasource") String datasource,
            @RequestParam("z") int z,
            @RequestParam("x") int x,
            @RequestParam("y") int y,
            @RequestParam(value = "minFlow", required = false) Double minFlow,
            @RequestParam(value = "maxLinks", required = false) Integer maxLinks,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        TileNetworkParam param = new TileNetworkParam();
        param.setDatasource(datasource);
        param.setZ(z);
        param.setX(x);
        param.setY(y);
        param.setMinFlow(minFlow);
        param.setMaxLinks(maxLinks);
        // 瓦片内容仅取决于源文件、口径版本与过滤参数：强校验 ETag + immutable，二次访问命中 304/本地缓存
        String etag = "\"" + MatsimPrecomputedCache.visualCacheTag(Datasource.data(datasource).matsim_data())
                + "-nt" + z + "." + x + "." + y + "-" + minFlow + "-" + maxLinks + "\"";
        if (HttpCacheSupport.etagMatches(etag, ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(HttpCacheSupport.immutablePrivate())
                    .build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .eTag(etag)
                .cacheControl(HttpCacheSupport.immutablePrivate())
                .body(TileBinaryEncoder.encodeLinks(networkService.tile(param)));
    }

    @Operation(summary = "二进制全量路网")
    @PostMapping(value = "/full.bin", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> fullBinary(@RequestBody TileNetworkParam param) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(fullBinaryFor(param));
    }

    @Operation(summary = "二进制全量路网(GET 可缓存)")
    @GetMapping(value = "/full.bin", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> fullBinaryGet(
            @RequestParam("datasource") String datasource,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        TileNetworkParam param = new TileNetworkParam();
        param.setDatasource(datasource);
        String etag = "\"" + MatsimPrecomputedCache.visualCacheTag(Datasource.data(datasource).matsim_data())
                + "-nfull\"";
        if (HttpCacheSupport.etagMatches(etag, ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(HttpCacheSupport.immutablePrivate())
                    .build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .eTag(etag)
                .cacheControl(HttpCacheSupport.immutablePrivate())
                .body(fullBinaryFor(param));
    }

    private byte[] fullBinaryFor(TileNetworkParam param) {
        String datasource = String.valueOf(param.getDatasource());
        // key 带模型加载版本：unload/重载后版本递增，旧条目不再命中并被 LRU 逐出
        String cacheKey = datasource + "#v" + Datasource.currentLoadVersion(datasource);
        return fullBinaryCache.computeIfAbsent(cacheKey,
                ignored -> TileBinaryEncoder.encodeLinks(networkService.full(param)));
    }

}
