package com.jts.gjcxfzksh.optimization.model;

import com.alibaba.fastjson2.JSONObject;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 线网优化修改项。kind 决定 params/geometry 的具体形状（见设计文档 §13.2）。
 *
 * P0 支持的 kind：
 *  route.add / route.modify.alignment / route.modify.stops / route.delete
 *  stop.add / stop.move / stop.delete
 *  link.add / link.modify / link.delete
 *  ops.headway / ops.serviceHours / ops.vehicleType
 */
@Data
public class EditItem {

    public static final List<String> APPLY_ORDER = List.of(
            "link.add",
            "stop.add", "stop.move", "stop.delete",
            "route.add", "route.modify.alignment", "route.modify.stops", "route.delete",
            "ops.headway", "ops.serviceHours", "ops.vehicleType",
            "link.modify",
            "link.delete");

    private String id;
    private String kind;
    /** 展示名（如线路名/站点名），仅用于报告与前端显示 */
    private String name;
    /** 目标引用：lineId / routeId / stopId / linkIds 等 */
    private JSONObject target;
    /** kind 专属参数 */
    private JSONObject params;
    /** 新增要素的几何与吸附结果（坐标一律 lngLat WGS84） */
    private JSONObject geometry;
    /** 依赖的其他修改项 id（如新增线路引用新增站点） */
    private List<String> deps = new ArrayList<>();
    private String note;
    private long createdAt;

    public int applyOrder() {
        int idx = APPLY_ORDER.indexOf(kind);
        return idx < 0 ? APPLY_ORDER.size() : idx;
    }
}
