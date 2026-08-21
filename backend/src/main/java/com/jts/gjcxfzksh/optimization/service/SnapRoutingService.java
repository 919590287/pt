package com.jts.gjcxfzksh.optimization.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.jts.gjcxfzksh.config.MatsimConfig;
import com.jts.gjcxfzksh.data.Datasource;
import com.jts.gjcxfzksh.data.MatsimData;
import com.jts.gjcxfzksh.exception.BusinessException;
import com.jts.gjcxfzksh.optimization.model.AreaSpec;
import com.jts.gjcxfzksh.optimization.model.EditItem;
import com.jts.gjcxfzksh.optimization.model.OptimizationDraft;
import com.jts.gjcxfzksh.optimization.util.GeoUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 编辑期吸附与沿路网寻径。
 * 运行在"已加载母本模型"的内存 Network（坐标系 EPSG:3857）上；
 * 草稿中已新增的路段（link.add）作为虚拟 overlay 参与吸附与寻径，
 * 使"先加路、新线走新路"的组合成为可能。
 */
@Slf4j
@Service
public class SnapRoutingService {

    /** 吸附搜索半径（米，按 3857 近似换算） */
    private static final double SNAP_RADIUS_M = 350;
    private static final Set<String> DRIVABLE = Set.of("car", "bus", "pt");

    @Resource
    private MatsimConfig matsimConfig;
    @Resource
    private DraftService draftService;

    /**
     * 点吸附：返回最近可用 link 与投影点。
     */
    public JSONObject snapPoint(String username, String parentModel, String draftId, double lng, double lat, String purpose) {
        matsimConfig.requireSchemeAccess(parentModel, username);
        Network network = network(parentModel);
        List<VirtualLink> overlay = overlayLinks(username, parentModel, draftId, network);

        double[] p = GeoUtil.lngLatToMercator(lng, lat);
        double radius = SNAP_RADIUS_M / Math.max(0.2, Math.cos(Math.toRadians(lat)));

        BestSnap best = new BestSnap();
        for (Link link : network.getLinks().values()) {
            if (!allowed(link.getAllowedModes())) {
                continue;
            }
            consider(best, link.getId().toString(),
                    link.getFromNode().getCoord(), link.getToNode().getCoord(),
                    link.getFromNode().getId().toString(), link.getToNode().getId().toString(), p, radius);
        }
        for (VirtualLink vl : overlay) {
            consider(best, vl.id, vl.from, vl.to, vl.fromNodeId, vl.toNodeId, p, radius);
        }
        if (best.linkId == null) {
            throw new BusinessException("附近" + (int) SNAP_RADIUS_M + "米内没有可用路段");
        }
        JSONObject result = new JSONObject();
        result.put("linkId", best.linkId);
        double[] lngLat = GeoUtil.mercatorToLngLat(best.px, best.py);
        result.put("point", new double[]{lngLat[0], lngLat[1]});
        result.put("distanceM", Math.round(best.dist * Math.cos(Math.toRadians(lat))));
        // link 几何（供前端高亮选中路段）与反向 link（双向操作）
        double[] fromLngLat = GeoUtil.mercatorToLngLat(best.fromX, best.fromY);
        double[] toLngLat = GeoUtil.mercatorToLngLat(best.toX, best.toY);
        result.put("linkGeometry", new double[][]{fromLngLat, toLngLat});
        result.put("reverseLinkId", findReverseLinkId(network, overlay, best));
        // 最近端点（供 link.add 端点吸附）
        double df = Math.hypot(p[0] - best.fromX, p[1] - best.fromY);
        double dt = Math.hypot(p[0] - best.toX, p[1] - best.toY);
        String nearNode = df <= dt ? best.fromNodeId : best.toNodeId;
        double nearDist = Math.min(df, dt) * Math.cos(Math.toRadians(lat));
        result.put("nearestNodeId", nearNode);
        result.put("nearestNodeDistanceM", Math.round(nearDist));
        double[] nodeLngLat = df <= dt ? GeoUtil.mercatorToLngLat(best.fromX, best.fromY) : GeoUtil.mercatorToLngLat(best.toX, best.toY);
        result.put("nearestNodePoint", nodeLngLat);
        return result;
    }

    /**
     * 沿路网寻径：锚点序列 -> 连续 link 序列 + 折线几何。
     */
    public JSONObject snapRoute(String username, String parentModel, String draftId, List<double[]> anchors) {
        matsimConfig.requireSchemeAccess(parentModel, username);
        if (anchors == null || anchors.size() < 2) {
            throw new BusinessException("寻径至少需要两个锚点");
        }
        Network network = network(parentModel);
        List<VirtualLink> overlay = overlayLinks(username, parentModel, draftId, network);
        Graph graph = Graph.build(network, overlay);

        List<String> linkIds = new ArrayList<>();
        List<double[]> geometry = new ArrayList<>();
        for (int i = 1; i < anchors.size(); i++) {
            double[] a = GeoUtil.lngLatToMercator(anchors.get(i - 1)[0], anchors.get(i - 1)[1]);
            double[] b = GeoUtil.lngLatToMercator(anchors.get(i)[0], anchors.get(i)[1]);
            List<Graph.Edge> path = graph.route(a, b);
            if (path == null) {
                throw new BusinessException("第" + i + "段锚点之间找不到连续路径，请调整锚点位置");
            }
            for (Graph.Edge edge : path) {
                if (!linkIds.isEmpty() && linkIds.get(linkIds.size() - 1).equals(edge.linkId)) {
                    continue;
                }
                linkIds.add(edge.linkId);
                if (geometry.isEmpty()) {
                    geometry.add(GeoUtil.mercatorToLngLat(edge.fromX, edge.fromY));
                }
                geometry.add(GeoUtil.mercatorToLngLat(edge.toX, edge.toY));
            }
        }
        if (linkIds.isEmpty()) {
            throw new BusinessException("寻径结果为空，请调整锚点位置");
        }
        JSONObject result = new JSONObject();
        result.put("linkIds", linkIds);
        result.put("geometry", geometry);
        return result;
    }

    /**
     * 研究区域内可行车路网（编辑期"开启路网"底图，供沿路网补画路径时参考）。
     * 双向 link 按无序节点对去重为一条显示线段；草稿新增路段一并返回。
     */
    public JSONObject roadNetwork(String username, String parentModel, String draftId, AreaSpec area) {
        matsimConfig.requireSchemeAccess(parentModel, username);
        if (area == null || area.getPolygon() == null || area.getPolygon().size() < 3) {
            throw new BusinessException("缺少研究区域");
        }
        Network network = network(parentModel);
        List<VirtualLink> overlay = overlayLinks(username, parentModel, draftId, network);

        double centerLat = RegionStatsService.centroidLat(area);
        org.locationtech.jts.geom.Polygon polygon = GeoUtil.toPolygon(area.getPolygon(), null, true);
        // 额外外扩 300m：允许贴着区域边缘画路径
        double bufferUnits = GeoUtil.bufferInCrsUnits("EPSG:3857", centerLat, area.getBufferM() + 300);
        org.locationtech.jts.geom.prep.PreparedGeometry zone =
                GeoUtil.prepare((org.locationtech.jts.geom.Polygon) polygon.buffer(Math.max(0, bufferUnits)));

        JSONArray segments = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (Link link : network.getLinks().values()) {
            if (!allowed(link.getAllowedModes())) {
                continue;
            }
            Coord f = link.getFromNode().getCoord();
            Coord t = link.getToNode().getCoord();
            if (!GeoUtil.contains(zone, (f.getX() + t.getX()) / 2, (f.getY() + t.getY()) / 2)) {
                continue;
            }
            String a = link.getFromNode().getId().toString();
            String b = link.getToNode().getId().toString();
            if (!seen.add(a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a)) {
                continue;
            }
            segments.add(segmentLngLat(f, t));
        }
        for (VirtualLink vl : overlay) {
            String a = vl.fromNodeId();
            String b = vl.toNodeId();
            if (!seen.add(a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a)) {
                continue;
            }
            segments.add(segmentLngLat(vl.from(), vl.to()));
        }
        JSONObject result = new JSONObject();
        result.put("segments", segments);
        result.put("count", segments.size());
        return result;
    }

    private static double[][] segmentLngLat(Coord from, Coord to) {
        double[] f = GeoUtil.mercatorToLngLat(from.getX(), from.getY());
        double[] t = GeoUtil.mercatorToLngLat(to.getX(), to.getY());
        return new double[][]{f, t};
    }

    private Network network(String parentModel) {
        MatsimData data = Datasource.computeData(parentModel).matsim_data();
        if (!data.hasFullRoadNetwork()) {
            throw new BusinessException("大模型当前仅加载公交子路网，不支持道路吸附与寻径");
        }
        return data.getNetwork();
    }

    private boolean allowed(Set<String> modes) {
        for (String m : modes) {
            if (DRIVABLE.contains(m)) {
                return true;
            }
        }
        return false;
    }

    private void consider(BestSnap best, String linkId, Coord from, Coord to,
                          String fromNodeId, String toNodeId, double[] p, double radius) {
        double fx = from.getX(), fy = from.getY(), tx = to.getX(), ty = to.getY();
        double dx = tx - fx, dy = ty - fy;
        double len2 = dx * dx + dy * dy;
        double t = len2 <= 0 ? 0 : Math.max(0, Math.min(1, ((p[0] - fx) * dx + (p[1] - fy) * dy) / len2));
        double px = fx + t * dx, py = fy + t * dy;
        double dist = Math.hypot(p[0] - px, p[1] - py);
        if (dist < best.dist && dist <= radius) {
            best.dist = dist;
            best.linkId = linkId;
            best.px = px;
            best.py = py;
            best.fromX = fx;
            best.fromY = fy;
            best.toX = tx;
            best.toY = ty;
            best.fromNodeId = fromNodeId;
            best.toNodeId = toNodeId;
        }
    }

    private static class BestSnap {
        String linkId;
        double dist = Double.MAX_VALUE;
        double px, py, fromX, fromY, toX, toY;
        String fromNodeId, toNodeId;
    }

    private String findReverseLinkId(Network network, List<VirtualLink> overlay, BestSnap best) {
        // 虚拟 link：按 _r 后缀约定
        if (best.linkId.startsWith("opt_l_")) {
            String candidate = best.linkId.endsWith("_r")
                    ? best.linkId.substring(0, best.linkId.length() - 2)
                    : best.linkId + "_r";
            for (VirtualLink vl : overlay) {
                if (vl.id().equals(candidate)) {
                    return candidate;
                }
            }
            return null;
        }
        Link link = network.getLinks().get(Id.createLinkId(best.linkId));
        if (link == null) {
            return null;
        }
        for (Link out : link.getToNode().getOutLinks().values()) {
            if (out.getToNode().getId().equals(link.getFromNode().getId()) && !out.getId().equals(link.getId())) {
                return out.getId().toString();
            }
        }
        return null;
    }

    /**
     * 草稿里 link.add 修改项展开为虚拟 link（含双向、逐段）。
     * id 规则与 EditApplyService 保持一致：opt_l_<editId>_<segIdx> / _r
     */
    List<VirtualLink> overlayLinks(String username, String parentModel, String draftId, Network network) {
        if (draftId == null || draftId.isBlank()) {
            return List.of();
        }
        OptimizationDraft draft;
        try {
            draft = draftService.get(username, parentModel, draftId);
        } catch (Exception e) {
            throw new BusinessException("读取草稿叠加路网失败: " + draftId, e);
        }
        List<VirtualLink> links = new ArrayList<>();
        for (EditItem edit : draft.getEdits()) {
            if (!"link.add".equals(edit.getKind()) || edit.getGeometry() == null) {
                continue;
            }
            JSONArray coords = edit.getGeometry().getJSONArray("coords");
            if (coords == null || coords.size() < 2) {
                continue;
            }
            boolean bidirectional = edit.getParams() == null || !Boolean.FALSE.equals(edit.getParams().getBoolean("bidirectional"));
            String fromNodeId = edit.getGeometry().getString("fromNodeId");
            String toNodeId = edit.getGeometry().getString("toNodeId");
            List<double[]> pts = new ArrayList<>();
            for (int i = 0; i < coords.size(); i++) {
                JSONArray c = coords.getJSONArray(i);
                pts.add(GeoUtil.lngLatToMercator(c.getDoubleValue(0), c.getDoubleValue(1)));
            }
            for (int i = 1; i < pts.size(); i++) {
                String fromId = i == 1 && fromNodeId != null ? fromNodeId : virtualNodeId(edit.getId(), i - 1);
                String toId = i == pts.size() - 1 && toNodeId != null ? toNodeId : virtualNodeId(edit.getId(), i);
                Coord from = resolveNodeCoord(network, fromId, pts.get(i - 1));
                Coord to = resolveNodeCoord(network, toId, pts.get(i));
                links.add(new VirtualLink("opt_l_" + edit.getId() + "_" + (i - 1), fromId, toId, from, to));
                if (bidirectional) {
                    links.add(new VirtualLink("opt_l_" + edit.getId() + "_" + (i - 1) + "_r", toId, fromId, to, from));
                }
            }
        }
        return links;
    }

    static String virtualNodeId(String editId, int idx) {
        return "opt_n_" + editId + "_" + idx;
    }

    private Coord resolveNodeCoord(Network network, String nodeId, double[] fallback) {
        Node node = network.getNodes().get(Id.createNodeId(nodeId));
        if (node != null) {
            return node.getCoord();
        }
        if (!nodeId.startsWith("opt_n_")) {
            throw new IllegalArgumentException("线路编辑引用了不存在的路网节点: " + nodeId);
        }
        return new Coord(fallback[0], fallback[1]);
    }

    record VirtualLink(String id, String fromNodeId, String toNodeId, Coord from, Coord to) {
    }

    /**
     * 轻量有向图 + 双向剪枝的 A*（启发式=直线距离/最大速度），针对"锚点相邻不远"的交互场景。
     */
    static class Graph {
        final Map<String, List<Edge>> outEdges = new HashMap<>();
        final Map<String, double[]> nodeCoords = new HashMap<>();

        static class Edge {
            String linkId;
            String fromNode, toNode;
            double fromX, fromY, toX, toY;
            double cost;
        }

        static Graph build(Network network, List<VirtualLink> overlay) {
            Graph g = new Graph();
            for (Link link : network.getLinks().values()) {
                boolean drivable = false;
                for (String m : link.getAllowedModes()) {
                    if (DRIVABLE.contains(m)) {
                        drivable = true;
                        break;
                    }
                }
                if (!drivable) {
                    continue;
                }
                Edge e = new Edge();
                e.linkId = link.getId().toString();
                e.fromNode = link.getFromNode().getId().toString();
                e.toNode = link.getToNode().getId().toString();
                Coord f = link.getFromNode().getCoord();
                Coord t = link.getToNode().getCoord();
                e.fromX = f.getX();
                e.fromY = f.getY();
                e.toX = t.getX();
                e.toY = t.getY();
                double geomLen = Math.hypot(e.toX - e.fromX, e.toY - e.fromY);
                e.cost = Math.max(1e-3, geomLen);
                g.addEdge(e);
            }
            for (VirtualLink vl : overlay) {
                Edge e = new Edge();
                e.linkId = vl.id();
                e.fromNode = vl.fromNodeId();
                e.toNode = vl.toNodeId();
                e.fromX = vl.from().getX();
                e.fromY = vl.from().getY();
                e.toX = vl.to().getX();
                e.toY = vl.to().getY();
                e.cost = Math.max(1e-3, Math.hypot(e.toX - e.fromX, e.toY - e.fromY));
                g.addEdge(e);
            }
            return g;
        }

        void addEdge(Edge e) {
            outEdges.computeIfAbsent(e.fromNode, k -> new ArrayList<>(2)).add(e);
            nodeCoords.putIfAbsent(e.fromNode, new double[]{e.fromX, e.fromY});
            nodeCoords.putIfAbsent(e.toNode, new double[]{e.toX, e.toY});
        }

        String nearestNode(double[] p) {
            String best = null;
            double bestDist = Double.MAX_VALUE;
            for (Map.Entry<String, double[]> entry : nodeCoords.entrySet()) {
                double d = Math.hypot(p[0] - entry.getValue()[0], p[1] - entry.getValue()[1]);
                if (d < bestDist) {
                    bestDist = d;
                    best = entry.getKey();
                }
            }
            return best;
        }

        List<Edge> route(double[] fromP, double[] toP) {
            String start = nearestNode(fromP);
            String goal = nearestNode(toP);
            if (start == null || goal == null) {
                return null;
            }
            double[] goalCoord = nodeCoords.get(goal);
            Map<String, Double> gScore = new HashMap<>();
            Map<String, Edge> cameBy = new HashMap<>();
            Set<String> closed = new HashSet<>();
            PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
            Map<Integer, String> idOf = new HashMap<>();
            Map<String, Integer> keyOf = new HashMap<>();
            int seq = 0;
            gScore.put(start, 0.);
            keyOf.put(start, seq);
            idOf.put(seq, start);
            open.add(new double[]{heuristic(start, goalCoord), 0, seq++});
            while (!open.isEmpty()) {
                double[] cur = open.poll();
                String node = idOf.get((int) cur[2]);
                if (node.equals(goal)) {
                    return reconstruct(cameBy, goal);
                }
                if (!closed.add(node)) {
                    continue;
                }
                double g = gScore.getOrDefault(node, Double.MAX_VALUE);
                for (Edge e : outEdges.getOrDefault(node, List.of())) {
                    if (closed.contains(e.toNode)) {
                        continue;
                    }
                    double ng = g + e.cost;
                    if (ng < gScore.getOrDefault(e.toNode, Double.MAX_VALUE)) {
                        gScore.put(e.toNode, ng);
                        cameBy.put(e.toNode, e);
                        Integer key = keyOf.get(e.toNode);
                        if (key == null) {
                            key = seq++;
                            keyOf.put(e.toNode, key);
                            idOf.put(key, e.toNode);
                        }
                        open.add(new double[]{ng + heuristic(e.toNode, goalCoord), ng, key});
                    }
                }
            }
            return null;
        }

        private double heuristic(String node, double[] goal) {
            double[] c = nodeCoords.get(node);
            return c == null ? 0 : Math.hypot(goal[0] - c[0], goal[1] - c[1]);
        }

        private List<Edge> reconstruct(Map<String, Edge> cameBy, String goal) {
            List<Edge> path = new ArrayList<>();
            String cur = goal;
            while (cameBy.containsKey(cur)) {
                Edge e = cameBy.get(cur);
                path.add(0, e);
                cur = e.fromNode;
                if (path.size() > 100_000) {
                    return null;
                }
            }
            return path;
        }
    }
}
