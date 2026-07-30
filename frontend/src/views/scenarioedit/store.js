import { defineStore } from "pinia";
import { computed, reactive, ref, shallowRef, watch } from "vue";
import { getCachedLineAll } from "@/utils/modelDataCache";
import { webMercatorToLngLat } from "@/mymap/index.js";
import { optDraftList, optDraftSave, optDraftDelete, optDraftCopy, optAreaStats, optJobStatus } from "@/api/optimization";
import { checkEditConflict } from "./conflicts";

let editSeq = 0;

function newEditId() {
  editSeq += 1;
  return `e_${Date.now().toString(36)}_${editSeq}`;
}

/**
 * 线网优化编辑会话状态：母本模型、草稿（区域+修改清单）、当前工具、任务列表。
 * 清单即事实：地图叠加、校验、生成都从 draft.edits 派生。
 */
export const useScenarioEditStore = defineStore("scenarioEdit", () => {
  // ---------- 母本模型 ----------
  const parentModel = ref("");
  const parentReady = ref(false); // loadStatus=true 即可编辑（无需等缓存）

  // ---------- 母本线网底图数据 ----------
  const lines = shallowRef([]); // lineAll 原始数据
  const linesLoading = ref(false);

  const stopIndex = computed(() => {
    // facilityId -> {id, name, lng, lat, x, y}
    const map = new Map();
    for (const line of lines.value) {
      for (const route of line.routes || []) {
        for (const fac of route.facilities || []) {
          if (!fac?.facilityId || !fac?.coord) continue;
          if (!map.has(fac.facilityId)) {
            const [lng, lat] = webMercatorToLngLat(fac.coord.x, fac.coord.y);
            map.set(fac.facilityId, {
              id: fac.facilityId,
              name: fac.facilityName || fac.facilityId,
              lng, lat,
              x: fac.coord.x, y: fac.coord.y,
            });
          }
        }
      }
    }
    return map;
  });

  const routeIndex = computed(() => {
    // `${lineId}||${routeId}` -> {lineId, lineName, routeId, geometryLngLat, facilities, departures, linkIds}
    const map = new Map();
    for (const line of lines.value) {
      for (const route of line.routes || []) {
        let geometry = null;
        if (Array.isArray(route.geometry) && route.geometry.length > 1) {
          geometry = route.geometry.map(([x, y]) => webMercatorToLngLat(x, y));
        } else if (Array.isArray(route.facilities) && route.facilities.length > 1) {
          geometry = route.facilities.filter((f) => f?.coord).map((f) => webMercatorToLngLat(f.coord.x, f.coord.y));
        }
        map.set(`${line.lineId}||${route.routeId}`, {
          lineId: line.lineId,
          lineName: line.lineName || line.lineId,
          mode: line.mode,
          routeId: route.routeId,
          routeName: route.routeName || route.routeId,
          geometry,
          facilities: route.facilities || [],
          departures: route.departures || [],
          linkIds: (route.links || []).map((l) => l.linkId).filter(Boolean),
        });
      }
    }
    return map;
  });

  async function loadLines() {
    if (!parentModel.value) return;
    linesLoading.value = true;
    try {
      lines.value = await getCachedLineAll(parentModel.value);
    } catch (e) {
      lines.value = [];
    } finally {
      linesLoading.value = false;
    }
  }

  // ---------- 草稿 ----------
  const draft = reactive({
    draftId: "",
    name: "未命名方案",
    parentModel: "",
    area: null, // {polygon:[[lng,lat]...], bufferM, source}
    edits: [],
  });
  const draftList = ref([]);
  const saveState = ref("idle"); // idle | saving | saved | error
  let saveTimer = null;
  let suppressAutosave = false;
  let changeRevision = 0;
  let savedRevision = 0;
  let saveQueue = Promise.resolve(true);

  const hasUnsavedChanges = computed(() => changeRevision > savedRevision || saveState.value === "error");

  function draftPayload() {
    return JSON.parse(JSON.stringify({
      draftId: draft.draftId || undefined,
      name: draft.name,
      parentModel: draft.parentModel,
      area: draft.area,
      edits: draft.edits,
    }));
  }

  function saveDraftNow() {
    if (saveTimer) clearTimeout(saveTimer);
    saveTimer = null;
    if (!draft.parentModel || (!draft.area && draft.edits.length === 0)) {
      savedRevision = changeRevision;
      saveState.value = "idle";
      return Promise.resolve(true);
    }
    const payload = draftPayload();
    const revision = changeRevision;
    const run = async () => {
      saveState.value = "saving";
      try {
        const res = await optDraftSave(payload);
        if (res?.data?.draftId && draft.parentModel === payload.parentModel) {
          draft.draftId = res.data.draftId;
        }
        savedRevision = Math.max(savedRevision, revision);
        saveState.value = changeRevision <= savedRevision ? "saved" : "idle";
        if (changeRevision > savedRevision) scheduleSave(false);
        return true;
      } catch (e) {
        saveState.value = "error";
        throw e;
      }
    };
    saveQueue = saveQueue.then(run, run);
    return saveQueue;
  }

  function scheduleSave(markChanged = true) {
    if (suppressAutosave) return;
    if (markChanged) changeRevision += 1;
    if (saveTimer) clearTimeout(saveTimer);
    saveTimer = setTimeout(() => saveDraftNow(), 1500);
  }

  watch(() => [draft.name, draft.area, draft.edits], scheduleSave, { deep: true });

  async function refreshDraftList() {
    if (!parentModel.value) {
      draftList.value = [];
      return;
    }
    try {
      const res = await optDraftList({ parentModel: parentModel.value });
      draftList.value = Array.isArray(res?.data) ? res.data : [];
    } catch (e) {
      draftList.value = [];
    }
  }

  function resetDraftLocal() {
    if (saveTimer) clearTimeout(saveTimer);
    saveTimer = null;
    suppressAutosave = true;
    draft.draftId = "";
    draft.name = "未命名方案";
    draft.parentModel = parentModel.value;
    draft.area = null;
    draft.edits = [];
    areaStats.value = null;
    clearSelection();
    setTool("");
    clearLineBuilder();
    changeRevision += 1;
    savedRevision = changeRevision;
    saveState.value = "idle";
    setTimeout(() => (suppressAutosave = false), 0);
  }

  function openDraft(d) {
    if (saveTimer) clearTimeout(saveTimer);
    saveTimer = null;
    suppressAutosave = true;
    draft.draftId = d.draftId;
    draft.name = d.name || "未命名方案";
    draft.parentModel = d.parentModel;
    draft.area = d.area || null;
    draft.edits = Array.isArray(d.edits) ? d.edits : [];
    areaStats.value = null;
    clearSelection();
    setTool("");
    clearLineBuilder();
    changeRevision += 1;
    savedRevision = changeRevision;
    saveState.value = "saved";
    setTimeout(() => (suppressAutosave = false), 0);
    if (draft.area) refreshAreaStats();
  }

  async function newDraft(name) {
    resetDraftLocal();
    if (name) draft.name = name;
  }

  async function deleteDraft(draftId) {
    await optDraftDelete({ parentModel: parentModel.value, draftId });
    if (draft.draftId === draftId) resetDraftLocal();
    await refreshDraftList();
  }

  async function copyDraft(draftId, newName) {
    const res = await optDraftCopy({ parentModel: parentModel.value, draftId, newName });
    await refreshDraftList();
    if (res?.data) openDraft(res.data);
  }

  // ---------- 研究区域 ----------
  const areaStats = ref(null);
  const areaStatsLoading = ref(false);

  function setArea(polygon, source = "draw", bufferM = null) {
    const keep = draft.area?.bufferM ?? 500;
    draft.area = {
      polygon,
      bufferM: bufferM ?? keep,
      source,
    };
    refreshAreaStats();
  }

  function clearAreaOnly() {
    draft.area = null;
    areaStats.value = null;
  }

  async function refreshAreaStats() {
    if (!draft.area || !parentReady.value) return;
    areaStatsLoading.value = true;
    try {
      const res = await optAreaStats({ parentModel: parentModel.value, area: draft.area });
      areaStats.value = res?.data || null;
    } catch (e) {
      areaStats.value = null;
    } finally {
      areaStatsLoading.value = false;
    }
  }

  // ---------- 修改清单 ----------
  function addEdit({ kind, name, target = null, params = null, geometry = null, deps = [] }) {
    const edit = {
      id: newEditId(),
      kind,
      name: name || "",
      target,
      params,
      geometry,
      deps,
      note: "",
      createdAt: Date.now(),
    };
    draft.edits.push(edit);
    return edit;
  }

  /**
   * 带冲突检测的加入清单：所有"✓ 加入修改清单"入口统一走这里。
   * 命中冲突时不加入，返回 { ok:false, reason } 供界面用通俗语言提示用户。
   */
  function addEditChecked(payload) {
    const verdict = checkEditConflict(payload, draft.edits, {
      routeIndex: routeIndex.value,
      stopIndex: stopIndex.value,
    });
    if (!verdict.ok) {
      return { ok: false, reason: verdict.reason };
    }
    return { ok: true, edit: addEdit(payload) };
  }

  function replaceEditChecked(editId, payload) {
    const index = draft.edits.findIndex((edit) => edit.id === editId);
    if (index < 0) return { ok: false, reason: "原修改项已不存在，请刷新后重试。" };
    const remaining = draft.edits.filter((edit) => edit.id !== editId);
    const verdict = checkEditConflict(payload, remaining, {
      routeIndex: routeIndex.value,
      stopIndex: stopIndex.value,
    });
    if (!verdict.ok) return { ok: false, reason: verdict.reason };
    const current = draft.edits[index];
    draft.edits[index] = {
      ...current,
      ...payload,
      id: current.id,
      note: current.note || "",
      createdAt: current.createdAt,
    };
    return { ok: true, edit: draft.edits[index] };
  }

  function findDependents(editId) {
    return draft.edits.filter((e) => Array.isArray(e.deps) && e.deps.includes(editId));
  }

  function removeEdits(ids) {
    const set = new Set(ids);
    draft.edits = draft.edits.filter((e) => !set.has(e.id));
  }

  function updateEdit(id, patch) {
    const idx = draft.edits.findIndex((e) => e.id === id);
    if (idx >= 0) {
      draft.edits[idx] = { ...draft.edits[idx], ...patch };
    }
  }

  const editCount = computed(() => draft.edits.length);

  /** 已被修改项覆盖的目标（用于地图差异染色与防重复编辑提示） */
  const editedTargets = computed(() => {
    const routes = new Map(); // `${lineId}||${routeId||''}` -> kind
    const stopsMap = new Map();
    const linksMap = new Map();
    for (const e of draft.edits) {
      const t = e.target || {};
      if (e.kind.startsWith("route.") || e.kind.startsWith("ops.")) {
        routes.set(`${t.lineId}||${t.routeId || ""}`, e.kind);
      }
      if (e.kind.startsWith("stop.") && t.stopId) {
        stopsMap.set(t.stopId, e.kind);
      }
      if (e.kind.startsWith("link.") && Array.isArray(t.linkIds)) {
        for (const id of t.linkIds) linksMap.set(id, e.kind);
      }
    }
    return { routes, stops: stopsMap, links: linksMap };
  });

  // ---------- 地图工具与选中 ----------
  const activeTool = ref(""); // '' | area.draw | pick.line | pick.stop | pick.link | draw.route | draw.link | place.stop
  const toolContext = ref(null); // 工具私有上下文（发起表单的 kind 等）
  const toolDraft = reactive({
    anchors: [], // draw.route / draw.link / draw.gapfill 的锚点 [[lng,lat]...]
    pathPreview: null, // snapRoute 结果 {linkIds, geometry}
    snapBusy: false,
    snapError: "",
    pickedLinks: [], // pick.link 累计 [{linkId, reverseLinkId, geometry:[[lng,lat],[lng,lat]]}]
    placedPoint: null, // place.stop / snapPoint 结果 {lng,lat,linkId,...}
    pickedStopId: "", // pick.stop（purpose=insert）结果：不改全局选中，供调整站点面板消费
  });

  function resetToolDraft() {
    toolDraft.anchors = [];
    toolDraft.pathPreview = null;
    toolDraft.snapBusy = false;
    toolDraft.snapError = "";
    toolDraft.pickedLinks = [];
    toolDraft.placedPoint = null;
    toolDraft.pickedStopId = "";
  }

  // ---------- 编辑期地图辅助显示 ----------
  /** 调整站点等面板请求显示路网底图（绘制类工具激活时也会自动显示） */
  const roadNetWanted = ref(false);
  /** 调整站点面板的地图预览 features（index.vue 监听渲染） */
  const editPreview = shallowRef(null);

  // ---------- 新增/修改线路：点选建线（既可点站点，也可点路网加途经点，参考交评多点选路） ----------
  /**
   * anchors：按顺序的锚点序列 {type:'stop'|'road', stopId?, lng, lat}（首发在前）。
   * session：区段编辑会话（地图右键"修改/删除站点、新增上一站/下一站、修改断面路径"），
   *          新点选插入到 insertPos，撤销只回退本会话新增的锚点；null=追加到末尾。
   */
  const lineBuilder = reactive({ anchors: [], session: null });
  const lineAnchorMode = ref("stop"); // stop | road，建线时显式区分“选站”与“加路径点”
  /** 按锚点沿路网寻径得到的走向 {linkIds, geometry}（EditToolbox 写入，index.vue 画连线） */
  const lineBuilderPath = shallowRef(null);

  function lineInsertPos() {
    return lineBuilder.session ? lineBuilder.session.insertPos : lineBuilder.anchors.length;
  }

  function bumpLineSession() {
    if (lineBuilder.session) {
      lineBuilder.session.insertPos += 1;
      lineBuilder.session.added += 1;
    }
  }

  /** 点选站点：作为停靠站锚点（插入到会话位置或末尾） */
  function appendLineStop(id) {
    if (!id) return;
    const s = stopIndex.value.get(id);
    if (!s) return;
    const pos = lineInsertPos();
    const before = lineBuilder.anchors[pos - 1];
    const after = lineBuilder.anchors[pos];
    // 防与相邻锚点重复同站
    if ((before?.type === "stop" && before.stopId === id) || (after?.type === "stop" && after.stopId === id)) return;
    lineBuilder.anchors.splice(pos, 0, { type: "stop", stopId: id, lng: s.lng, lat: s.lat });
    bumpLineSession();
  }

  /** 点选路网空白处：作为路径途经点（不停靠，仅约束走向沿路网） */
  function appendLineRoadPoint(lng, lat) {
    if (!Number.isFinite(lng) || !Number.isFinite(lat)) return;
    lineBuilder.anchors.splice(lineInsertPos(), 0, { type: "road", lng, lat });
    bumpLineSession();
  }

  function removeLineAnchorAt(i) {
    if (i < 0 || i >= lineBuilder.anchors.length) return;
    lineBuilder.anchors.splice(i, 1);
    const s = lineBuilder.session;
    if (s && i < s.insertPos) s.insertPos -= 1;
  }

  /** 撤销上一步：会话中只回退本会话新增的锚点 */
  function popLineAnchor() {
    const s = lineBuilder.session;
    if (s) {
      if (s.added > 0) {
        lineBuilder.anchors.splice(s.insertPos - 1, 1);
        s.insertPos -= 1;
        s.added -= 1;
      }
      return;
    }
    lineBuilder.anchors.pop();
  }

  function clearLineBuilder() {
    lineBuilder.anchors = [];
    lineBuilder.session = null;
    lineBuilderPath.value = null;
    lineAnchorMode.value = "stop";
  }

  function setLineAnchorMode(mode) {
    lineAnchorMode.value = mode === "road" ? "road" : "stop";
  }

  // ---- 区段编辑会话（地图右键触发；kind 供操作条提示文案） ----
  function prevStopIdxFrom(i) {
    for (let j = i - 1; j >= 0; j--) if (lineBuilder.anchors[j].type === "stop") return j;
    return -1;
  }

  function nextStopIdxFrom(i) {
    for (let j = i + 1; j < lineBuilder.anchors.length; j++) if (lineBuilder.anchors[j].type === "stop") return j;
    return lineBuilder.anchors.length;
  }

  function openLineSession(kind, insertPos, originalAnchors) {
    // leftIdx：断开处左边界锚点下标（固定，不随插入右移）；autoConnect：用户显式选择"直接最短路连接"
    lineBuilder.session = { kind, insertPos, added: 0, leftIdx: insertPos - 1, autoConnect: false, originalAnchors };
    lineAnchorMode.value = kind === "segment" || kind === "delete" ? "road" : "stop";
    setTool("pick.stop", { purpose: "buildLine", keepForm: true });
  }

  /** 会话中：用户显式选择直接沿最短路把断开处连起来（非默认自动） */
  function sessionAutoConnect() {
    if (lineBuilder.session) lineBuilder.session = { ...lineBuilder.session, autoConnect: true };
  }

  function endLineSession() {
    lineBuilder.session = null;
  }

  function cancelLineSession() {
    const original = lineBuilder.session?.originalAnchors;
    if (Array.isArray(original)) lineBuilder.anchors = original.map((item) => ({ ...item }));
    lineBuilder.session = null;
  }

  /** 修改站点：移除该站及其两侧途经点，点选替换站（可加途经点） */
  function beginStopReplace(i) {
    if (lineBuilder.anchors[i]?.type !== "stop") return;
    const original = lineBuilder.anchors.map((item) => ({ ...item }));
    const p = prevStopIdxFrom(i);
    const n = nextStopIdxFrom(i);
    lineBuilder.anchors.splice(p + 1, n - (p + 1));
    openLineSession("replace", p + 1, original);
  }

  /** 删除站点：移除该站及其两侧途经点，进入补连接点选（可直接完成走最短路） */
  function beginStopDelete(i) {
    if (lineBuilder.anchors[i]?.type !== "stop") return;
    const original = lineBuilder.anchors.map((item) => ({ ...item }));
    const p = prevStopIdxFrom(i);
    const n = nextStopIdxFrom(i);
    lineBuilder.anchors.splice(p + 1, n - (p + 1));
    openLineSession("delete", p + 1, original);
  }

  /** 新增上一站：清掉与前一站之间的途经点，在该站前插入点选 */
  function beginInsertBefore(i) {
    if (lineBuilder.anchors[i]?.type !== "stop") return;
    const original = lineBuilder.anchors.map((item) => ({ ...item }));
    const p = prevStopIdxFrom(i);
    lineBuilder.anchors.splice(p + 1, i - (p + 1));
    openLineSession("insertBefore", p + 1, original);
  }

  /** 新增下一站：清掉与后一站之间的途经点，在该站后插入点选 */
  function beginInsertAfter(i) {
    if (lineBuilder.anchors[i]?.type !== "stop") return;
    const original = lineBuilder.anchors.map((item) => ({ ...item }));
    const n = nextStopIdxFrom(i);
    lineBuilder.anchors.splice(i + 1, n - (i + 1));
    openLineSession("insertAfter", i + 1, original);
  }

  /** 修改断面路径：清掉两相邻停靠站之间的途经点，重新点选该断面路径 */
  function beginSegmentEdit(aIdx, bIdx) {
    if (lineBuilder.anchors[aIdx]?.type !== "stop" || lineBuilder.anchors[bIdx]?.type !== "stop") return;
    const original = lineBuilder.anchors.map((item) => ({ ...item }));
    lineBuilder.anchors.splice(aIdx + 1, bIdx - (aIdx + 1));
    openLineSession("segment", aIdx + 1, original);
  }

  function setTool(tool, context = null) {
    activeTool.value = tool || "";
    toolContext.value = context;
    resetToolDraft();
  }

  // 当前打开的编辑表单类型（EditToolbox 同步；index.vue 据此判断搜索仅定位/屏蔽删除键）
  const activeFormKind = ref("");
  const formRequest = reactive({ kind: "", editId: "", seq: 0 });

  function requestForm(kind, editId = "") {
    formRequest.kind = kind || "";
    formRequest.editId = editId || "";
    formRequest.seq += 1;
  }

  const selection = reactive({ type: "", lineId: "", routeId: "", stopId: "" });

  function selectRoute(lineId, routeId) {
    selection.type = "route";
    selection.lineId = lineId;
    selection.routeId = routeId;
    selection.stopId = "";
  }

  function selectStop(stopId) {
    selection.type = "stop";
    selection.stopId = stopId;
    selection.lineId = "";
    selection.routeId = "";
  }

  function clearSelection() {
    selection.type = "";
    selection.lineId = "";
    selection.routeId = "";
    selection.stopId = "";
  }

  const selectedRoute = computed(() => {
    if (selection.type !== "route") return null;
    return routeIndex.value.get(`${selection.lineId}||${selection.routeId}`) || null;
  });

  const selectedStop = computed(() => {
    if (selection.type !== "stop") return null;
    return stopIndex.value.get(selection.stopId) || null;
  });

  // ---------- 运行任务 ----------
  const jobs = ref([]);
  const jobsError = ref("");
  let jobTimer = null;

  async function refreshJobs() {
    try {
      const res = await optJobStatus({});
      jobs.value = Array.isArray(res?.data) ? res.data : [];
      jobsError.value = "";
    } catch (e) {
      jobsError.value = e?.message || "任务状态加载失败";
      throw e;
    }
    const active = jobs.value.some((j) => !["done", "failed", "canceled"].includes(j.stage));
    if (jobTimer) clearTimeout(jobTimer);
    if (active) {
      jobTimer = setTimeout(refreshJobs, 4000);
    }
  }

  function startJobPolling() {
    refreshJobs();
  }

  function stopJobPolling() {
    if (jobTimer) clearTimeout(jobTimer);
    jobTimer = null;
  }

  // ---------- 母本切换 ----------
  async function setParentModel(name, ready) {
    const changed = parentModel.value !== name;
    parentModel.value = name || "";
    parentReady.value = Boolean(ready);
    if (changed) {
      lines.value = [];
      resetDraftLocal();
      draftList.value = [];
      setTool("");
    }
    if (parentModel.value && parentReady.value) {
      await Promise.all([loadLines(), refreshDraftList()]);
      // 不再默认打开列表第一个草稿，由用户在左侧明确选择。
      draft.parentModel = parentModel.value;
    }
  }

  function markParentReady() {
    parentReady.value = true;
  }

  return {
    parentModel, parentReady, setParentModel, markParentReady,
    lines, linesLoading, loadLines, stopIndex, routeIndex,
    draft, draftList, saveState, hasUnsavedChanges, refreshDraftList, saveDraftNow, newDraft, openDraft, deleteDraft, copyDraft, resetDraftLocal,
    areaStats, areaStatsLoading, setArea, clearAreaOnly, refreshAreaStats,
    addEdit, addEditChecked, replaceEditChecked, removeEdits, updateEdit, findDependents, editCount, editedTargets,
    activeTool, toolContext, toolDraft, setTool, resetToolDraft,
    roadNetWanted, editPreview,
    lineBuilder, lineBuilderPath, lineAnchorMode, setLineAnchorMode, appendLineStop, appendLineRoadPoint, removeLineAnchorAt, popLineAnchor, clearLineBuilder,
    endLineSession, cancelLineSession, sessionAutoConnect, beginStopReplace, beginStopDelete, beginInsertBefore, beginInsertAfter, beginSegmentEdit,
    activeFormKind, formRequest, requestForm,
    selection, selectRoute, selectStop, clearSelection, selectedRoute, selectedStop,
    jobs, jobsError, refreshJobs, startJobPolling, stopJobPolling,
  };
});
