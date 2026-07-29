package com.jts.gjcxfzksh.optimization.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jts.gjcxfzksh.config.MatsimConfig;
import com.jts.gjcxfzksh.data.Datasource;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.optimization.model.EditItem;
import com.jts.gjcxfzksh.optimization.model.OptimizationDraft;
import com.jts.gjcxfzksh.optimization.model.ValidationIssue;
import com.jts.gjcxfzksh.optimization.util.ScheduleTools;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 草稿级校验：生成前的快速一致性检查（基于已加载母本模型的内存数据）。
 * 全量一致性校验发生在应用阶段（EditApplyService.validateFinal）。
 */
@Slf4j
@Service
public class ScenarioValidateService {

    @Resource
    private MatsimConfig matsimConfig;

    public List<ValidationIssue> validateDraft(String username, OptimizationDraft draft) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (draft.getArea() == null || draft.getArea().getPolygon() == null || draft.getArea().getPolygon().size() < 3) {
            issues.add(ValidationIssue.error(null, "尚未圈定研究区域"));
        }
        if (draft.getEdits() == null || draft.getEdits().isEmpty()) {
            issues.add(ValidationIssue.warning(null, "修改清单为空：方案模型将与基线一致"));
        }

        MatsimData data = null;
        try {
            matsimConfig.requireSchemeAccess(draft.getParentModel(), username);
            data = Datasource.data(draft.getParentModel()).matsim_data();
        } catch (Exception e) {
            issues.add(ValidationIssue.warning(null, "母本模型未加载，部分引用检查已跳过"));
        }
        boolean fullRoadNetwork = data != null && data.hasFullRoadNetwork();
        if (data != null && !fullRoadNetwork) {
            issues.add(ValidationIssue.error(null,
                    "大模型当前仅加载公交子路网，不能用于道路优化方案"));
        }

        Set<String> editIds = new HashSet<>();
        Set<String> newStopIds = new HashSet<>();
        Set<String> newLinkPrefixes = new HashSet<>();
        Set<String> deletedLines = new HashSet<>();
        Set<String> deletedStops = new HashSet<>();
        List<EditItem> edits = draft.getEdits() == null ? List.of() : draft.getEdits();
        for (EditItem edit : edits) {
            editIds.add(edit.getId());
            if ("stop.add".equals(edit.getKind())) {
                newStopIds.add(EditApplyService.stopIdOf(edit.getId()));
            }
            if ("link.add".equals(edit.getKind())) {
                newLinkPrefixes.add("opt_l_" + edit.getId() + "_");
            }
            if ("route.delete".equals(edit.getKind()) && edit.getTarget() != null) {
                deletedLines.add(edit.getTarget().getString("lineId"));
            }
            if ("stop.delete".equals(edit.getKind()) && edit.getTarget() != null) {
                deletedStops.add(edit.getTarget().getString("stopId"));
            }
        }

        for (EditItem edit : edits) {
            // 依赖闭合
            if (edit.getDeps() != null) {
                for (String dep : edit.getDeps()) {
                    if (!editIds.contains(dep)) {
                        issues.add(ValidationIssue.error(edit.getId(), "依赖的修改项已被撤销，请一并撤销本项"));
                    }
                }
            }
            switch (edit.getKind()) {
                case "route.add", "route.replace", "route.modify.alignment" -> {
                    JSONObject geometry = edit.getGeometry();
                    JSONArray directions = geometry == null ? null
                            : geometry.containsKey("directions") ? geometry.getJSONArray("directions") : wrapSingle(geometry);
                    if (directions == null || directions.isEmpty()) {
                        issues.add(ValidationIssue.error(edit.getId(), "缺少走向数据"));
                        break;
                    }
                    for (int d = 0; d < directions.size(); d++) {
                        JSONObject dir = directions.getJSONObject(d);
                        JSONArray stops = dir.getJSONArray("stops");
                        JSONArray linkIds = dir.getJSONArray("linkIds");
                        if (stops == null || stops.size() < 2) {
                            issues.add(ValidationIssue.error(edit.getId(), "方向" + (d + 1) + "停靠站不足2个"));
                        } else {
                            checkStops(issues, edit, stops, data, newStopIds, deletedStops);
                        }
                        if (linkIds == null || linkIds.isEmpty()) {
                            issues.add(ValidationIssue.error(edit.getId(), "方向" + (d + 1) + "缺少走向路段（请沿地图重新寻径）"));
                        }
                    }
                    // route.add 与 route.replace 都是"新建/整体重建线路"，需线路名与发车时段；
                    // route.replace 还须校验被替换的原线路存在（未被删除项覆盖）
                    boolean rebuild = "route.add".equals(edit.getKind()) || "route.replace".equals(edit.getKind());
                    if (rebuild) {
                        checkSlots(issues, edit);
                        if (edit.getParams() == null || isBlank(edit.getParams().getString("name"))) {
                            issues.add(ValidationIssue.error(edit.getId(), "请填写线路名称"));
                        }
                    }
                    if ("route.replace".equals(edit.getKind())) {
                        checkRouteRef(issues, edit, data, deletedLines);
                    }
                }
                case "route.modify.stops" -> {
                    JSONArray stops = edit.getParams() == null ? null : edit.getParams().getJSONArray("stops");
                    if (stops == null || stops.size() < 2) {
                        issues.add(ValidationIssue.error(edit.getId(), "调整后停靠站不足2个"));
                    } else {
                        checkStops(issues, edit, stops, data, newStopIds, deletedStops);
                    }
                    checkRouteRef(issues, edit, data, deletedLines);
                }
                case "route.delete", "ops.vehicleType" -> checkRouteRef(issues, edit, data, deletedLines);
                case "ops.headway", "ops.serviceHours" -> {
                    checkRouteRef(issues, edit, data, deletedLines);
                    checkSlots(issues, edit);
                }
                case "stop.add" -> {
                    if (edit.getGeometry() == null || edit.getGeometry().getJSONArray("coord") == null
                            || isBlank(edit.getGeometry().getString("linkId"))) {
                        issues.add(ValidationIssue.error(edit.getId(), "新增站点缺少位置或吸附路段"));
                    }
                    if (edit.getParams() == null || isBlank(edit.getParams().getString("name"))) {
                        issues.add(ValidationIssue.warning(edit.getId(), "建议为新站点命名"));
                    }
                }
                case "stop.move", "stop.delete" -> {
                    String stopId = edit.getTarget() == null ? null : edit.getTarget().getString("stopId");
                    if (isBlank(stopId)) {
                        issues.add(ValidationIssue.error(edit.getId(), "缺少目标站点"));
                    } else if (data != null && !newStopIds.contains(stopId)
                            && !data.getSchedule().getFacilities().containsKey(Id.create(stopId, TransitStopFacility.class))) {
                        issues.add(ValidationIssue.error(edit.getId(), "目标站点不存在: " + stopId));
                    }
                }
                case "link.add" -> {
                    if (edit.getGeometry() == null || edit.getGeometry().getJSONArray("coords") == null
                            || edit.getGeometry().getJSONArray("coords").size() < 2) {
                        issues.add(ValidationIssue.error(edit.getId(), "新增路段至少需要2个坐标点"));
                    }
                }
                case "link.modify", "link.delete" -> {
                    JSONArray linkIds = edit.getTarget() == null ? null : edit.getTarget().getJSONArray("linkIds");
                    if (linkIds == null || linkIds.isEmpty()) {
                        issues.add(ValidationIssue.error(edit.getId(), "缺少目标路段"));
                        break;
                    }
                    if (data != null && fullRoadNetwork) {
                        for (int i = 0; i < linkIds.size(); i++) {
                            String lid = linkIds.getString(i);
                            boolean isNew = newLinkPrefixes.stream().anyMatch(lid::startsWith);
                            if (!isNew && !data.getNetwork().getLinks().containsKey(Id.createLinkId(lid))) {
                                issues.add(ValidationIssue.error(edit.getId(), "目标路段不存在: " + lid));
                            }
                        }
                        if ("link.delete".equals(edit.getKind())) {
                            warnTransitUsers(issues, edit, linkIds, data.getSchedule());
                        }
                    }
                }
                default -> issues.add(ValidationIssue.error(edit.getId(), "暂不支持的修改类型: " + edit.getKind()));
            }
        }
        return issues;
    }

    private JSONArray wrapSingle(JSONObject geometry) {
        JSONArray arr = new JSONArray();
        arr.add(geometry);
        return arr;
    }

    private void checkStops(List<ValidationIssue> issues, EditItem edit, JSONArray stops,
                            MatsimData data, Set<String> newStopIds, Set<String> deletedStops) {
        for (int i = 0; i < stops.size(); i++) {
            String sid = stops.getString(i);
            if (deletedStops.contains(sid)) {
                issues.add(ValidationIssue.error(edit.getId(), "停靠站 " + sid + " 已被其他修改项删除"));
            }
            if (data != null && !newStopIds.contains(sid)
                    && !data.getSchedule().getFacilities().containsKey(Id.create(sid, TransitStopFacility.class))) {
                issues.add(ValidationIssue.error(edit.getId(), "停靠站不存在: " + sid));
            }
        }
    }

    private void checkRouteRef(List<ValidationIssue> issues, EditItem edit, MatsimData data, Set<String> deletedLines) {
        String lineId = edit.getTarget() == null ? null : edit.getTarget().getString("lineId");
        if (isBlank(lineId)) {
            issues.add(ValidationIssue.error(edit.getId(), "缺少目标线路"));
            return;
        }
        if (deletedLines.contains(lineId) && !"route.delete".equals(edit.getKind())) {
            issues.add(ValidationIssue.error(edit.getId(), "目标线路已被删除修改项覆盖: " + lineId));
        }
        if (data != null && !lineId.startsWith("opt_line_")
                && !data.getSchedule().getTransitLines().containsKey(Id.create(lineId, TransitLine.class))) {
            issues.add(ValidationIssue.error(edit.getId(), "目标线路不存在: " + lineId));
        }
    }

    private void checkSlots(List<ValidationIssue> issues, EditItem edit) {
        try {
            JSONArray slots = edit.getParams() == null ? null : edit.getParams().getJSONArray("slots");
            ScheduleTools.expandDepartureTimes(slots);
        } catch (Exception e) {
            issues.add(ValidationIssue.error(edit.getId(), e.getMessage()));
        }
    }

    private void warnTransitUsers(List<ValidationIssue> issues, EditItem edit, JSONArray linkIds, TransitSchedule schedule) {
        Set<Id<Link>> targets = new HashSet<>();
        for (int i = 0; i < linkIds.size(); i++) {
            targets.add(Id.createLinkId(linkIds.getString(i)));
        }
        List<String> users = new ArrayList<>();
        outer:
        for (TransitLine line : schedule.getTransitLines().values()) {
            for (TransitRoute route : line.getRoutes().values()) {
                if (route.getRoute() == null) {
                    continue;
                }
                List<Id<Link>> ids = new ArrayList<>();
                ids.add(route.getRoute().getStartLinkId());
                ids.addAll(route.getRoute().getLinkIds());
                ids.add(route.getRoute().getEndLinkId());
                for (Id<Link> id : ids) {
                    if (targets.contains(id)) {
                        users.add(line.getName() != null ? line.getName() : line.getId().toString());
                        if (users.size() >= 8) {
                            break outer;
                        }
                        break;
                    }
                }
            }
        }
        if (!users.isEmpty()) {
            issues.add(ValidationIssue.warning(edit.getId(),
                    "删除的路段被 " + users.size() + " 条线路经过（" + String.join("、", users)
                            + "…），生成时若这些线路未改线将报错"));
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
