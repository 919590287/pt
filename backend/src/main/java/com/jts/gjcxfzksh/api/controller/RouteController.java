package com.jts.gjcxfzksh.api.controller;

import com.jts.gjcxfzksh.api.common.AjaxResult;
import com.jts.gjcxfzksh.api.common.HttpCacheSupport;
import com.jts.gjcxfzksh.api.common.TileBinaryEncoder;
import com.jts.gjcxfzksh.api.model.params.DatasourceParam;
import com.jts.gjcxfzksh.api.model.params.RouteChartParam;
import com.jts.gjcxfzksh.api.model.params.RouteInfoParam;
import com.jts.gjcxfzksh.api.model.params.RoutePickParam;
import com.jts.gjcxfzksh.api.model.params.RouteListParam;
import com.jts.gjcxfzksh.api.model.params.TileNetworkParam;
import com.jts.gjcxfzksh.api.service.RouteService;
import com.jts.gjcxfzksh.data.Datasource;
import com.jts.gjcxfzksh.data.cache.MatsimPrecomputedCache;
import com.jts.gjcxfzksh.data.cache.BackendMemoryCache;
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
@RequestMapping("/pt/route")
@Tag(name = "线路总览", description = "线路总览")
public class RouteController {

    @Resource
    private RouteService routeService;

    /**
     * 全量线网二进制缓存：key 含模型加载版本（重载后旧字节自动失效），
     * 与全平台后端缓存共用字节预算。
     */
    private final BackendMemoryCache<String, byte[]> fullBinaryCache =
            new BackendMemoryCache<>("route-full-binary", 128L * 1024 * 1024, bytes -> bytes.length);

    @Operation(summary = "全部线路")
    @PostMapping("/lineAll")
    public AjaxResult lineAll(@RequestBody DatasourceParam param) {
        return AjaxResult.ok(routeService.lineAll(param));
    }

    @Operation(summary = "线路客流监测右侧面板缓存")
    @PostMapping("/routePanel")
    public AjaxResult routePanel(@RequestBody DatasourceParam param) {
        return AjaxResult.ok(routeService.routePanel(param));
    }

    @Operation(summary = "单条线路客流监测面板缓存")
    @PostMapping("/routePanelDetail")
    public AjaxResult routePanelDetail(@RequestBody RouteInfoParam param) {
        return AjaxResult.ok(routeService.routePanelDetail(param));
    }

    @Operation(summary = "仿真线路班次时刻表")
    @PostMapping("/departureTimetable")
    public AjaxResult departureTimetable(@RequestBody RouteInfoParam param) {
        return AjaxResult.ok(routeService.departureTimetable(param));
    }

    @Operation(summary = "仿真班次客流模型级缓存")
    @PostMapping("/departureBundle")
    public AjaxResult departureBundle(@RequestBody DatasourceParam param) {
        return AjaxResult.ok(routeService.departureBundle(param));
    }

    @Operation(summary = "仿真单班次客流面板")
    @PostMapping("/departurePanel")
    public AjaxResult departurePanel(@RequestBody RouteChartParam param) {
        return AjaxResult.ok(routeService.departurePanel(param));
    }

    @Operation(summary = "总体客流变化(24小时×交通方式)服务端聚合")
    @PostMapping("/overallFlow")
    public AjaxResult overallFlow(@RequestBody DatasourceParam param) {
        return AjaxResult.ok(routeService.overallFlow(param));
    }

    @Operation(summary = "按模型路段坐标匹配经过线路")
    @PostMapping("/routeCandidates")
    public AjaxResult routeCandidates(@RequestBody RoutePickParam param) {
        return AjaxResult.ok(routeService.routeCandidates(param));
    }

    @Operation(summary = "线路瓦片, zoom level12")
    @PostMapping("/tile")
    public AjaxResult routeTile(@RequestBody TileNetworkParam param) {
        return AjaxResult.ok(routeService.routeTile(param));
    }

    @Operation(summary = "二进制线路瓦片")
    @PostMapping(value = "/tile.bin", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> routeTileBinary(@RequestBody TileNetworkParam param) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(TileBinaryEncoder.encodeLinks(routeService.routeTile(param)));
    }

    @Operation(summary = "二进制线路瓦片(GET 可缓存)")
    @GetMapping(value = "/tile.bin", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> routeTileBinaryGet(
            @RequestParam("datasource") String datasource,
            @RequestParam("z") int z,
            @RequestParam("x") int x,
            @RequestParam("y") int y,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        TileNetworkParam param = new TileNetworkParam();
        param.setDatasource(datasource);
        param.setZ(z);
        param.setX(x);
        param.setY(y);
        // 瓦片内容仅取决于源文件与口径版本：强校验 ETag + immutable，二次访问命中 304/本地缓存
        String etag = "\"" + MatsimPrecomputedCache.visualCacheTag(Datasource.data(datasource).matsim_data())
                + "-rt" + z + "." + x + "." + y + "\"";
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
                .body(TileBinaryEncoder.encodeLinks(routeService.routeTile(param)));
    }

    @Operation(summary = "二进制全量线路")
    @PostMapping(value = "/full.bin", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> routeFullBinary(@RequestBody TileNetworkParam param) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(fullBinaryFor(param));
    }

    @Operation(summary = "二进制全量线路(GET 可缓存)")
    @GetMapping(value = "/full.bin", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> routeFullBinaryGet(
            @RequestParam("datasource") String datasource,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        TileNetworkParam param = new TileNetworkParam();
        param.setDatasource(datasource);
        String etag = "\"" + MatsimPrecomputedCache.visualCacheTag(Datasource.data(datasource).matsim_data())
                + "-rfull\"";
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
        byte[] cached = fullBinaryCache.get(cacheKey);
        if (cached != null) return cached;
        cached = TileBinaryEncoder.encodeLinks(routeService.routeFull(param));
        fullBinaryCache.put(cacheKey, cached);
        return cached;
    }

    @Operation(summary = "线路列表")
    @PostMapping("/routeList")
    public AjaxResult routeList(@RequestBody RouteListParam param) {
        return AjaxResult.ok(routeService.routeList(param));
    }

    @Operation(summary = "线路详情")
    @PostMapping("/routeDetail")
    public AjaxResult routeDetail(@RequestBody RouteInfoParam param) {
        return AjaxResult.ok(routeService.routeDetail(param));
    }

    @Operation(summary = "线路信息")
    @PostMapping("/routeInfo")
    public AjaxResult routeInfo(@RequestBody RouteInfoParam param) {
        return AjaxResult.ok(routeService.routeInfo(param));
    }

    // 图表
    @Operation(summary = "上下车客流")
    @PostMapping("/routeFlow")
    public AjaxResult routeFlow(@RequestBody RouteChartParam param) {
        return AjaxResult.ok(routeService.routeFlow(param));
    }

    // 载客量
    public AjaxResult routeCapacity(@RequestBody RouteChartParam param) {
        return null;
    }

    // 总载客量

    // 上下车站点热力图

    // 站点OD客流量

    // 发车时刻表

}
