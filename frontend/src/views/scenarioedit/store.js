import { defineStore } from "pinia";
import { computed, reactive, ref, watch } from "vue";
import { getCachedLineAll, clearModelDataCache } from "@/utils/modelDataCache";
import { webMercatorToLngLat } from "@/mymap/index.js";
import { optDraftList, optDraftSave, optDraftDelete, optDraftCopy, optAreaStats, optJobStatus } from "@/api/optimization";

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
  const lines = ref([]); // lineAll 原始数据
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

  function draftPayload() {
    return {
      draftId: draft.draftId || undefined,
      name: draft.name,
      parentModel: draft.parentModel,
      area: draft.area,
      edits: draft.edits,
    };
  }

  async function saveDraftNow() {
    if (!draft.parentModel || (!draft.area && draft.edits.length === 0)) return;
    saveState.value = "saving";
    try {
      const res = await optDraftSave(draftPayload());
      if (res?.data?.draftId) {
        draft.draftId = res.data.draftId;
      }
      saveState.value = "saved";
    } catch (e) {
      saveState.value = "error";
    }
  }

  function scheduleSave() {
    if (suppressAutosave) return;
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
    suppressAutosave = true;
    draft.draftId = "";
    draft.name = "未命名方案";
    draft.parentModel = parentModel.value;
    draft.area = null;
    draft.edits = [];
    areaStats.value = null;
    clearSelection();
    setTimeout(() => (suppressAutosave = false), 0);
  }

  function openDraft(d) {
    suppressAutosave = true;
    draft.draftId = d.draftId;
    draft.name = d.name || "未命名方案";
    draft.parentModel = d.parentModel;
    draft.area = d.area || null;
    draft.edits = Array.isArray(d.edits) ? d.edits : [];
    areaStats.value = null;
    clearSelection();
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
    anchors: [], // draw.route / draw.link 的锚点 [[lng,lat]...]
    pathPreview: null, // snapRoute 结果 {linkIds, geometry}
    snapBusy: false,
    snapError: "",
    pickedLinks: [], // pick.link 累计 [{linkId, reverseLinkId, geometry:[[lng,lat],[lng,lat]]}]
    placedPoint: null, // place.stop / snapPoint 结果 {lng,lat,linkId,...}
  });

  function resetToolDraft() {
    toolDraft.anchors = [];
    toolDraft.pathPreview = null;
    toolDraft.snapBusy = false;
    toolDraft.snapError = "";
    toolDraft.pickedLinks = [];
    toolDraft.placedPoint = null;
  }

  function setTool(tool, context = null) {
    activeTool.value = tool || "";
    toolContext.value = context;
    resetToolDraft();
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
  let jobTimer = null;

  async function refreshJobs() {
    try {
      const res = await optJobStatus({});
      jobs.value = Array.isArray(res?.data) ? res.data : [];
    } catch (e) {
      /* 静默 */
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
      if (parentModel.value) clearModelDataCache(parentModel.value);
      lines.value = [];
      resetDraftLocal();
      draftList.value = [];
      setTool("");
    }
    if (parentModel.value && parentReady.value) {
      await Promise.all([loadLines(), refreshDraftList()]);
      // 默认打开最近草稿
      if (!draft.draftId && draftList.value.length > 0) {
        openDraft(draftList.value[0]);
      } else {
        draft.parentModel = parentModel.value;
      }
    }
  }

  function markParentReady() {
    parentReady.value = true;
  }

  return {
    parentModel, parentReady, setParentModel, markParentReady,
    lines, linesLoading, loadLines, stopIndex, routeIndex,
    draft, draftList, saveState, refreshDraftList, saveDraftNow, newDraft, openDraft, deleteDraft, copyDraft, resetDraftLocal,
    areaStats, areaStatsLoading, setArea, clearAreaOnly, refreshAreaStats,
    addEdit, removeEdits, updateEdit, findDependents, editCount, editedTargets,
    activeTool, toolContext, toolDraft, setTool, resetToolDraft,
    selection, selectRoute, selectStop, clearSelection, selectedRoute, selectedStop,
    jobs, refreshJobs, startJobPolling, stopJobPolling,
  };
});
