import { watch, onUnmounted } from "vue";
import { ElMessage } from "element-plus";
import { optSnapPoint, optSnapRoute } from "@/api/optimization";
import { LAYER_IDS, updateToolPreview, clearToolPreview } from "../layers/editorLayers";

/**
 * 地图交互模式调度：根据 store.activeTool 挂载/卸载地图事件，
 * 交互结果写入 store.toolDraft，供左侧表单读取与确认。
 *
 * 工具一览：
 *  area.draw   逐点画研究区域（双击/回车闭合，⌫ 退点，ESC 取消）
 *  pick.line   点选线路（命中候选多条时弹出选择）
 *  pick.stop   点选站点
 *  pick.link   点选路段（吸附最近 link，可累计多段）
 *  draw.route  沿路网锚点寻径（新增线路/改走向）
 *  draw.gapfill 补画缺失路径：起终点固定为两站（toolContext.fixedStart/fixedEnd），
 *               点击加途经点沿路网寻径；进入即先尝试两站间直接寻径
 *  draw.link   画新路段折线（端点吸附既有节点）
 *  place.stop  放置新站点（吸附最近可停靠 link）
 */
export function useMapTools({ MapRef, store, onPickRouteCandidates }) {
  let clickHandle = null;
  let moveHandle = null;
  let dblHandle = null;
  let keyHandler = null;
  let snapSeq = 0;

  const map = () => MapRef.value?.map || null;

  function setCursor(cursor) {
    const m = map();
    if (m) m.getCanvas().style.cursor = cursor;
  }

  function teardown() {
    if (MapRef.value) {
      if (clickHandle) MapRef.value.removeEventListener("handle:click", clickHandle);
      if (moveHandle) MapRef.value.removeEventListener("handle:mousemove", moveHandle);
    }
    const m = map();
    if (m && dblHandle) m.off("dblclick", dblHandle);
    if (keyHandler) window.removeEventListener("keydown", keyHandler);
    clickHandle = null;
    moveHandle = null;
    dblHandle = null;
    keyHandler = null;
    setCursor("");
    if (m) clearToolPreview(m);
  }

  /** draw.gapfill 的固定端点（两侧站点坐标） */
  function gapfillEndpoints() {
    if (store.activeTool !== "draw.gapfill") return null;
    const ctx = store.toolContext || {};
    if (!Array.isArray(ctx.fixedStart) || !Array.isArray(ctx.fixedEnd)) return null;
    return [ctx.fixedStart, ctx.fixedEnd];
  }

  function refreshPreview(cursor = null) {
    const m = map();
    if (!m) return;
    const endpoints = gapfillEndpoints();
    updateToolPreview(m, {
      anchors: store.toolDraft.anchors,
      pathGeometry: store.toolDraft.pathPreview?.geometry || null,
      cursor,
      point: store.toolDraft.placedPoint ? [store.toolDraft.placedPoint.lng, store.toolDraft.placedPoint.lat] : null,
      segments: store.toolDraft.pickedLinks.map((l) => l.geometry).filter(Boolean),
      endpoints: endpoints || [],
    });
  }

  async function requestSnapRoute() {
    // 补画模式：锚点两端拼上固定的站点坐标，用户每点一下都在两站之间加一个途经点
    const endpoints = gapfillEndpoints();
    const anchors = endpoints
      ? [endpoints[0], ...store.toolDraft.anchors, endpoints[1]]
      : store.toolDraft.anchors;
    if (anchors.length < 2) {
      store.toolDraft.pathPreview = null;
      refreshPreview();
      return;
    }
    const seq = ++snapSeq;
    store.toolDraft.snapBusy = true;
    store.toolDraft.snapError = "";
    try {
      const res = await optSnapRoute({
        parentModel: store.parentModel,
        draftId: store.draft.draftId || "",
        anchors,
      });
      if (seq !== snapSeq) return;
      store.toolDraft.pathPreview = res?.data || null;
    } catch (e) {
      if (seq !== snapSeq) return;
      store.toolDraft.pathPreview = null;
      store.toolDraft.snapError = e?.message || "寻径失败，请调整锚点";
    } finally {
      if (seq === snapSeq) store.toolDraft.snapBusy = false;
      refreshPreview();
    }
  }

  async function requestSnapPoint(lng, lat, purpose) {
    store.toolDraft.snapBusy = true;
    store.toolDraft.snapError = "";
    try {
      const res = await optSnapPoint({
        parentModel: store.parentModel,
        draftId: store.draft.draftId || "",
        lng, lat, purpose,
      });
      return res?.data || null;
    } catch (e) {
      store.toolDraft.snapError = e?.message || "附近没有可吸附的路段";
      return null;
    } finally {
      store.toolDraft.snapBusy = false;
    }
  }

  function queryFeatures(lngLat, layerId) {
    const m = map();
    if (!m || !m.getLayer(layerId)) return [];
    const p = m.project(lngLat);
    const box = [[p.x - 8, p.y - 8], [p.x + 8, p.y + 8]];
    try {
      return m.queryRenderedFeatures(box, { layers: [layerId] });
    } catch (e) {
      return [];
    }
  }

  async function handleClick(e) {
    const [lng, lat] = e.data.lngLat;
    const tool = store.activeTool;
    if (tool === "area.draw") {
      store.toolDraft.anchors.push([lng, lat]);
      refreshPreview();
      return;
    }
    if (tool === "draw.route" || tool === "draw.gapfill") {
      store.toolDraft.anchors.push([lng, lat]);
      refreshPreview();
      requestSnapRoute();
      return;
    }
    if (tool === "draw.link") {
      // 端点吸附提示：首尾点尝试吸附既有节点
      const snap = await requestSnapPoint(lng, lat, "node");
      const pt = { lng, lat, nodeId: null };
      if (snap && snap.nearestNodeDistanceM != null && snap.nearestNodeDistanceM < 60 && snap.nearestNodePoint) {
        pt.lng = snap.nearestNodePoint[0];
        pt.lat = snap.nearestNodePoint[1];
        pt.nodeId = snap.nearestNodeId;
      }
      store.toolDraft.anchors.push([pt.lng, pt.lat]);
      if (!store.toolDraft.pickedLinks) store.toolDraft.pickedLinks = [];
      // 复用 pickedLinks 字段存节点吸附信息
      store.toolDraft.pickedLinks.push({ nodeId: pt.nodeId, index: store.toolDraft.anchors.length - 1 });
      refreshPreview();
      return;
    }
    if (tool === "place.stop") {
      const snap = await requestSnapPoint(lng, lat, "stop");
      if (snap && snap.point) {
        store.toolDraft.placedPoint = {
          lng: snap.point[0],
          lat: snap.point[1],
          linkId: snap.linkId,
          distanceM: snap.distanceM,
        };
        refreshPreview();
      } else if (store.toolDraft.snapError) {
        ElMessage.warning(store.toolDraft.snapError);
      }
      return;
    }
    if (tool === "pick.link") {
      const snap = await requestSnapPoint(lng, lat, "link");
      if (snap && snap.linkId) {
        if (store.toolDraft.pickedLinks.some((l) => l.linkId === snap.linkId)) {
          store.toolDraft.pickedLinks = store.toolDraft.pickedLinks.filter((l) => l.linkId !== snap.linkId);
        } else {
          store.toolDraft.pickedLinks.push({
            linkId: snap.linkId,
            reverseLinkId: snap.reverseLinkId || null,
            geometry: snap.linkGeometry || null,
          });
        }
        refreshPreview();
      } else if (store.toolDraft.snapError) {
        ElMessage.warning(store.toolDraft.snapError);
      }
      return;
    }
    if (tool === "pick.stop") {
      const feats = queryFeatures([lng, lat], LAYER_IDS.baseStops);
      const stopId = feats.length > 0 ? feats[0].properties.stopId : null;
      const purpose = store.toolContext?.purpose;
      if (purpose === "buildLine") {
        // 新增线路建线（参考交评多点选路）：点到站点=加停靠站；点到空白/路网=加路径途经点
        if (stopId) store.appendLineStop(stopId);
        else store.appendLineRoadPoint(lng, lat);
        return;
      }
      if (!stopId) return;
      if (purpose === "insert") {
        // 调整站点面板插站：不改全局选中，结果写入 toolDraft
        store.toolDraft.pickedStopId = stopId;
      } else {
        store.selectStop(stopId);
      }
      return;
    }
    if (tool === "pick.line") {
      const feats = queryFeatures([lng, lat], LAYER_IDS.baseLinesHit);
      if (feats.length === 0) return;
      const unique = [];
      const seen = new Set();
      for (const f of feats) {
        const key = `${f.properties.lineId}||${f.properties.routeId}`;
        if (!seen.has(key)) {
          seen.add(key);
          unique.push({ ...f.properties });
        }
      }
      if (unique.length === 1) {
        store.selectRoute(unique[0].lineId, unique[0].routeId);
      } else if (onPickRouteCandidates) {
        const m = map();
        const p = m.project([lng, lat]);
        onPickRouteCandidates(unique, { x: p.x, y: p.y });
      }
    }
  }

  function handleMove(e) {
    const tool = store.activeTool;
    if (tool === "area.draw" || tool === "draw.link") {
      if (store.toolDraft.anchors.length > 0) {
        refreshPreview(e.data.lngLat);
      }
    }
  }

  function handleKeydown(ev) {
    if (ev.key === "Escape") {
      store.setTool("");
      return;
    }
    if (ev.key === "Backspace" || ev.key === "Delete") {
      const tool = store.activeTool;
      const target = ev.target;
      if (target && (target.tagName === "INPUT" || target.tagName === "TEXTAREA")) return;
      if ((tool === "area.draw" || tool === "draw.route" || tool === "draw.gapfill" || tool === "draw.link") && store.toolDraft.anchors.length > 0) {
        ev.preventDefault();
        store.toolDraft.anchors.pop();
        if (tool === "draw.route" || tool === "draw.gapfill") requestSnapRoute();
        refreshPreview();
        return;
      }
      // 点选建线：⌫ 撤销上一步（等价底部操作条按钮）
      if (tool === "pick.stop" && store.toolContext?.purpose === "buildLine") {
        ev.preventDefault();
        store.popLineAnchor();
      }
    }
  }

  function setup(tool) {
    teardown();
    if (!tool || !MapRef.value) return;
    setCursor("crosshair");
    clickHandle = MapRef.value.addEventListener("handle:click", handleClick);
    moveHandle = MapRef.value.addEventListener("handle:mousemove", handleMove);
    keyHandler = handleKeydown;
    window.addEventListener("keydown", keyHandler);
    const m = map();
    if (m && tool === "area.draw") {
      dblHandle = () => {
        // 双击闭合交由地图控件 ✏️ 的完成动作统一处理，这里只阻止误加点
      };
    }
    // 补画模式：进入即先尝试两站之间直接寻径，找得到就有初始预览
    if (tool === "draw.gapfill") {
      requestSnapRoute();
    }
    refreshPreview();
  }

  watch(() => store.activeTool, (tool) => setup(tool));

  onUnmounted(() => teardown());

  return { refreshPreview, requestSnapRoute, teardown };
}
