<!-- 线网优化（v2 重构版）：圈区域 → 编辑线网 → 生成基线+方案双模型并运行 -->
<template>
  <div class="netopt-wrapper">
    <!-- 右上角：母本模型选择（仅仿真模型） -->
    <ModelPickerBar />

    <!-- 浮动搜索框：仅用于定位线路/站点（复用客流分析 .rm-search 结构与样式） -->
    <div
      v-if="store.parentReady"
      :class="['rm-search', { 'is-focused': isSearchFocused, 'is-left-collapsed': leftCollapsed }]"
      role="search"
      aria-label="搜索线路或站点定位"
      @click.stop
    >
      <svg class="rm-search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
        <circle cx="11" cy="11" r="8"></circle>
        <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
      </svg>
      <input
        v-model="searchKeyword"
        class="rm-search-input"
        type="search"
        placeholder="搜索线路 / 站点以定位"
        aria-label="搜索线路或站点"
        @focus="handleSearchFocus"
        @blur="handleSearchBlur"
        @keydown.enter.prevent="selectFirstSearchResult"
        @keydown.esc.prevent="closeSearchResults"
      />
      <button v-if="searchKeyword" class="rm-search-clear" type="button" title="清空搜索" aria-label="清空搜索" @mousedown.prevent="clearSearchKeyword">
        <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
          <line x1="18" y1="6" x2="6" y2="18"></line>
          <line x1="6" y1="6" x2="18" y2="18"></line>
        </svg>
      </button>
      <Transition name="rm-search-fade">
        <div v-if="showSearchResults" class="rm-search-results" role="listbox">
          <button
            v-for="result in searchResults"
            :key="result.key"
            class="rm-search-result"
            type="button"
            role="option"
            @mousedown.prevent="selectSearchResult(result)"
          >
            <span class="rm-result-icon" :class="result.type">
              <svg v-if="result.type === 'station'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"></path>
                <circle cx="12" cy="10" r="3"></circle>
              </svg>
              <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                <rect x="3" y="4" width="18" height="12" rx="2"></rect>
                <circle cx="7" cy="10" r="1"></circle>
                <circle cx="17" cy="10" r="1"></circle>
                <path d="M6 16v2"></path>
                <path d="M18 16v2"></path>
              </svg>
            </span>
            <span class="rm-result-meta">
              <span class="rm-result-name">{{ result.name }}</span>
              <span class="rm-result-type">{{ result.typeLabel }}</span>
            </span>
          </button>
          <p v-if="!searchResults.length" class="rm-search-empty">未找到匹配的线路或站点</p>
        </div>
      </Transition>
    </div>

    <!-- 地图控件工具栏（与数据管理/客流分析一致风格） -->
    <div v-if="store.parentReady" :class="['map-controls-toolbar', rightCollapsed ? 'without-panel' : 'with-panel']">
      <div class="control-block">
        <button class="control-btn" type="button" @click="handleZoomIn" title="放大" aria-label="放大地图">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
            <line x1="12" y1="5" x2="12" y2="19"></line>
            <line x1="5" y1="12" x2="19" y2="12"></line>
          </svg>
        </button>
        <button class="control-btn" type="button" @click="handleZoomOut" title="缩小" aria-label="缩小地图">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
            <line x1="5" y1="12" x2="19" y2="12"></line>
          </svg>
        </button>
        <button :class="['control-btn', 'td-btn', is3DActive ? 'active' : '']" type="button" @click="handleToggle3D" title="3D视图" aria-label="切换3D视图" :aria-pressed="is3DActive">
          3D
        </button>
        <button class="control-btn compass-btn" type="button" @click="handleResetCompass" title="指北针" aria-label="重置地图朝北">
          <div class="pitch-arrows">
            <svg class="caret-up" viewBox="0 0 24 24" width="10" height="10" fill="currentColor"><polygon points="12,4 2,18 22,18"></polygon></svg>
            <svg class="caret-down" viewBox="0 0 24 24" width="10" height="10" fill="currentColor"><polygon points="12,20 2,6 22,6"></polygon></svg>
          </div>
        </button>
      </div>

      <!-- 研究区域：手绘 / 清除 -->
      <div class="control-block area-block">
        <button
          :class="['control-btn', isDrawingArea ? 'active' : '']"
          type="button"
          @click="handleDrawAreaBtn"
          :title="drawBtnTitle"
          :aria-label="drawBtnTitle"
        >
          <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M12 20h9"></path>
            <path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4 12.5-12.5z"></path>
          </svg>
        </button>
        <button
          class="control-btn"
          type="button"
          :disabled="!isDrawingArea && !hasArea"
          @click="handleClearAreaBtn"
          :title="clearBtnTitle"
          :aria-label="clearBtnTitle"
        >
          <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="3 6 5 6 21 6"></polyline>
            <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"></path>
            <path d="M10 11v6"></path>
            <path d="M14 11v6"></path>
            <path d="M9 6V4a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2"></path>
          </svg>
        </button>
      </div>
    </div>

    <!-- 左侧操作面板（复用 tokens.css 全局 .dm-sidebar，与数据管理/客流分析一致） -->
    <div :class="['dm-sidebar', leftCollapsed ? 'is-collapsed' : '']">
      <div class="sidebar-brand">
        <svg class="brand-icon" viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z" />
        </svg>
        <span class="brand-text">线网优化</span>
      </div>

      <div class="sidebar-steps">
        <div v-if="!store.parentReady" class="guide-block">
          <div class="guide-icon">🛰</div>
          <p><b>请先在右上角选择母本仿真模型</b></p>
          <p class="sub">线网优化以一个已运行的仿真模型为底，圈定研究区域后对公交线网做修改，一键生成"基线 + 方案"两个对比模型。</p>
        </div>

        <EditToolbox v-else />
      </div>
      <div class="sidebar-footer"></div>
    </div>
    <button
      type="button"
      :class="['dm-panel-collapse-tab', 'dm-left-collapse-tab', leftCollapsed ? 'is-collapsed' : '']"
      :title="leftCollapsed ? '展开面板' : '收起面板'"
      :aria-pressed="leftCollapsed"
      @click="leftCollapsed = !leftCollapsed"
    >
      <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <polyline points="15 18 9 12 15 6"></polyline>
      </svg>
    </button>

    <!-- 右侧方案面板（结构/样式仿数据管理"更新"面板；线路表单视图与清单视图切换） -->
    <div :class="['dm-overview-panel', 'netopt-right-panel', rightCollapsed ? 'is-collapsed' : '']">
      <!-- 视图一：修改清单 + 开始仿真（默认） -->
      <div v-show="!isRouteFormOpen" class="rp-view">
        <div class="overview-title-row">
          <h2>修改清单</h2>
          <span class="edit-pending-count" :class="{ 'has-pending': store.editCount }">{{ store.editCount }} 条修改</span>
        </div>
        <ModList class="right-modlist" @hover-edit="handleHoverEdit" />
        <RunTaskList v-if="store.jobs.length" class="right-runtasks" />
        <div class="right-footer">
          <button class="generate-btn" :disabled="!canGenerate" @click="wizardVisible = true">
            <span>开始仿真</span>
            <span v-if="store.editCount" class="count">{{ store.editCount }} 项修改</span>
          </button>
          <p v-if="!canGenerate" class="gen-block-tip">{{ generateBlockReason }}</p>
        </div>
      </div>

      <!-- 视图二：新增/修改线路表单（EditToolbox 通过 Teleport 挂到这里；确认/取消切回清单） -->
      <div v-show="isRouteFormOpen" id="netopt-route-form-host" class="rp-view rp-form-host"></div>
    </div>
    <button
      type="button"
      :class="['dm-panel-collapse-tab', 'dm-right-collapse-tab', rightCollapsed ? 'is-collapsed' : '']"
      :title="rightCollapsed ? '展开面板' : '收起面板'"
      :aria-expanded="!rightCollapsed"
      @click="rightCollapsed = !rightCollapsed"
    >
      <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <polyline points="9 18 15 12 9 6"></polyline>
      </svg>
    </button>

    <!-- 建线右键菜单：站点/断面编辑 -->
    <div v-if="lineCtxMenu.visible" class="route-picker" :style="{ left: lineCtxMenu.x + 'px', top: lineCtxMenu.y + 'px' }" @click.stop>
      <div class="picker-title">{{ lineCtxMenu.title }}</div>
      <template v-if="lineCtxMenu.type === 'stop'">
        <button class="picker-item" @click="lineCtxAction('replace')">修改站点（换成其它站）</button>
        <button class="picker-item" @click="lineCtxAction('delete')">删除站点</button>
        <button class="picker-item" @click="lineCtxAction('insertBefore')">新增上一站</button>
        <button class="picker-item" @click="lineCtxAction('insertAfter')">新增下一站</button>
      </template>
      <template v-else>
        <button class="picker-item" @click="lineCtxAction('segment')">修改断面路径</button>
      </template>
      <button class="picker-cancel" @click="closeLineCtxMenu">取消</button>
    </div>

    <!-- 线路点选候选弹层 -->
    <div v-if="routePicker.visible" class="route-picker" :style="{ left: routePicker.x + 'px', top: routePicker.y + 'px' }">
      <div class="picker-title">选择线路</div>
      <button v-for="c in routePicker.candidates" :key="`${c.lineId}||${c.routeId}`" class="picker-item" @click="pickCandidate(c)">
        {{ c.lineName }} <span class="dir">{{ c.routeId }}</span>
      </button>
      <button class="picker-cancel" @click="routePicker.visible = false">取消</button>
    </div>

    <GenerateWizard v-model="wizardVisible" />

    <!-- 圈定研究区域后：居中弹窗设置缓冲距离 -->
    <el-dialog v-model="bufferDialogVisible" title="研究区域已圈定" width="440px" align-center :close-on-click-modal="false">
      <div class="buffer-dialog-body">
        <p class="hint">缓冲带（区域外扩）内的线路与站点也会纳入切分场景。可拖动调整，右侧概览实时刷新。</p>
        <div class="buffer-row">
          <span class="label">缓冲距离</span>
          <el-slider v-model="bufferDialogM" :min="0" :max="2000" :step="100" size="small" class="buffer-slider" @change="applyBufferFromDialog" />
          <span class="value">{{ bufferDialogM }}m</span>
        </div>
        <div class="stats-card">
          <div class="stats-title">
            <span>区域概览</span>
            <el-button link size="small" :loading="store.areaStatsLoading" @click="store.refreshAreaStats()">刷新</el-button>
          </div>
          <div v-if="store.areaStats" class="stats-grid">
            <div class="stat"><span class="k">面积</span><span class="v">{{ store.areaStats.areaKm2 }} km²</span></div>
            <div class="stat"><span class="k">触达线路</span><span class="v">{{ store.areaStats.lineTouchCount }} 条</span></div>
            <div class="stat"><span class="k">区域内站点</span><span class="v">{{ store.areaStats.stopCount }} 个</span></div>
            <div class="stat"><span class="k">区域内路段</span><span class="v">{{ store.areaStats.linkCount }} 条</span></div>
          </div>
          <div v-else class="stats-empty">{{ store.areaStatsLoading ? "统计中…" : "统计加载中…" }}</div>
        </div>
      </div>
      <template #footer>
        <el-button @click="redrawAreaFromDialog">重新圈定</el-button>
        <el-button type="primary" @click="bufferDialogVisible = false">确定，开始编辑</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { lngLatToWebMercator, webMercatorToLngLat } from "@/mymap/index.js";
import { optRoadNetwork } from "@/api/optimization";
import { useScenarioEditStore } from "./store";
import { useMapTools } from "./composables/useMapTools";
import ModelPickerBar from "./components/ModelPickerBar.vue";
import EditToolbox from "./components/EditToolbox.vue";
import ModList from "./components/ModList.vue";
import GenerateWizard from "./components/GenerateWizard.vue";
import RunTaskList from "./components/RunTaskList.vue";
import {
  LAYER_IDS, updateBaseNetwork, updateArea, approxBufferRing, buildOverlayFeatures, updateOverlay,
  updateHighlight, updateEditPreview, updateRoadNetwork, clearRoadNetwork,
  updateLinePicked, clearLinePicked, removeAllEditorLayers,
} from "./layers/editorLayers";
import { projectMeasureOnPath } from "./utils";
// 复用数据管理/客流分析的设计令牌与地图控件样式（--dm2-*、.map-controls-toolbar 等）
import "../datamanagement/tokens.css";

const MapRef = inject("MapRef");
const store = useScenarioEditStore();

const leftCollapsed = ref(false);
const rightCollapsed = ref(false);
const wizardVisible = ref(false);

const hasArea = computed(() => Boolean(store.draft.area?.polygon?.length >= 3));
// 新增/修改线路表单是否占用右侧面板（此时右面板显示表单而非修改清单）
const isRouteFormOpen = computed(() =>
  ["route.add", "route.edit", "stop.add", "stop.move", "stop.delete"].includes(store.activeFormKind)
);
// 打开线路表单时自动展开右侧面板
watch(isRouteFormOpen, (open) => { if (open) rightCollapsed.value = false; });

const canGenerate = computed(() => store.parentReady && hasArea.value && store.editCount > 0);
const generateBlockReason = computed(() => {
  if (!store.parentReady) return "请先选择并加载母本模型";
  if (!hasArea.value) return "请先圈定研究区域";
  if (!store.editCount) return "请至少添加一项线网修改";
  return "";
});


// ---------------- 线路候选弹层 ----------------
const routePicker = reactive({ visible: false, candidates: [], x: 0, y: 0 });

function onPickRouteCandidates(candidates, point) {
  routePicker.candidates = candidates;
  routePicker.x = Math.min(point.x, window.innerWidth - 240);
  routePicker.y = Math.min(point.y + 60, window.innerHeight - 200);
  routePicker.visible = true;
}

function pickCandidate(c) {
  store.selectRoute(c.lineId, c.routeId);
  routePicker.visible = false;
}

useMapTools({ MapRef, store, onPickRouteCandidates });

// ---------------- 浮动搜索：仅定位线路/站点 ----------------
const searchKeyword = ref("");
const isSearchFocused = ref(false);

const searchResults = computed(() => {
  const q = searchKeyword.value.trim().toLowerCase();
  if (!q) return [];
  const PER = 12; // 每类上限，保证线路与站点都能出现
  const lines = [];
  for (const [key, r] of store.routeIndex.entries()) {
    if (r.lineName?.toLowerCase().includes(q) || r.routeId?.toLowerCase().includes(q)) {
      lines.push({ key: `line:${key}`, type: "line", name: `${r.lineName}（${r.routeId}）`, typeLabel: "线路", lineId: r.lineId, routeId: r.routeId });
      if (lines.length >= PER) break;
    }
  }
  const stops = [];
  for (const s of store.stopIndex.values()) {
    if (s.name?.toLowerCase().includes(q) || s.id?.toLowerCase().includes(q)) {
      stops.push({ key: `stop:${s.id}`, type: "station", name: s.name, typeLabel: "站点", stopId: s.id });
      if (stops.length >= PER) break;
    }
  }
  // 编辑站点时站点优先；编辑线路时线路优先；否则线路在前
  const stopFirst = String(store.activeFormKind || "").startsWith("stop.");
  return stopFirst ? [...stops, ...lines] : [...lines, ...stops];
});

const showSearchResults = computed(() => isSearchFocused.value && searchKeyword.value.trim().length > 0);

function handleSearchFocus() {
  isSearchFocused.value = true;
}
function handleSearchBlur() {
  setTimeout(() => (isSearchFocused.value = false), 150);
}
function closeSearchResults() {
  isSearchFocused.value = false;
}
function clearSearchKeyword() {
  searchKeyword.value = "";
}
// 仅定位（移动视野），不改变任何选中
function locateResult(result) {
  if (result.type === "line") {
    const r = store.routeIndex.get(`${result.lineId}||${result.routeId}`);
    if (r?.geometry?.length > 1) fitToLngLatCoords(r.geometry);
  } else {
    const s = store.stopIndex.get(result.stopId);
    if (s && MapRef.value) {
      MapRef.value.setCenter?.(lngLatToWebMercator(s.lng, s.lat));
      MapRef.value.setZoom?.(Math.max(Number(MapRef.value.zoom) || 0, 15));
    }
  }
}

function selectSearchResult(result) {
  // 新增线路（建线/填表）过程中：搜索仅定位，不改变选中，避免干扰
  if (buildLineActive.value || store.activeFormKind === "route.add") {
    locateResult(result);
  } else if (result.type === "line") {
    store.selectRoute(result.lineId, result.routeId); // 触发居中+高亮
  } else {
    store.selectStop(result.stopId);
    locateResult(result);
  }
  searchKeyword.value = "";
  isSearchFocused.value = false;
}
function selectFirstSearchResult() {
  if (searchResults.value.length) selectSearchResult(searchResults.value[0]);
}

// ---------------- 地图控件：缩放 / 3D / 指北针 ----------------
const is3DActive = ref(false);
let rotateListenerId = null;

function handleZoomIn() {
  const m = MapRef.value;
  if (m) m.setZoom(m.zoom + 1);
}
function handleZoomOut() {
  const m = MapRef.value;
  if (m) m.setZoom(m.zoom - 1);
}
function handleToggle3D() {
  const m = MapRef.value;
  if (!m) return;
  if (is3DActive.value) {
    m.setPitchAndRotation(90, 0);
    m.enableRotate = false;
    is3DActive.value = false;
    return;
  }
  m.enableRotate = true;
  m.setPitchAndRotation(45, m.rotation);
  is3DActive.value = true;
}
function handleResetCompass() {
  const m = MapRef.value;
  if (!m) return;
  m.setPitchAndRotation(90, 0);
  m.enableRotate = false;
  is3DActive.value = false;
}

// ---------------- 地图控件：研究区域 手绘 / 清除 ----------------
const isDrawingArea = computed(() => store.activeTool === "area.draw");
const drawBtnTitle = computed(() => {
  if (isDrawingArea.value) return `完成手绘（已落 ${store.toolDraft.anchors.length} 点，需≥3）`;
  return hasArea.value ? "重新手绘研究区域" : "手绘研究区域";
});
const clearBtnTitle = computed(() => (isDrawingArea.value ? "取消手绘" : "清除研究区域"));

async function guardEditedArea() {
  if (store.draft.edits.length > 0) {
    await ElMessageBox.confirm("修改区域后将对全部修改项重新校验，是否继续？", "重新圈定研究区域", {
      confirmButtonText: "继续", cancelButtonText: "取消", type: "warning",
    });
  }
}

// 缓冲距离：圈定后自动弹出居中弹窗设置
const bufferDialogVisible = ref(false);
const bufferDialogM = ref(500);

function applyBufferFromDialog() {
  if (store.draft.area) {
    store.draft.area.bufferM = bufferDialogM.value;
    store.refreshAreaStats();
  }
}

async function redrawAreaFromDialog() {
  bufferDialogVisible.value = false;
  try {
    await guardEditedArea();
  } catch {
    return;
  }
  store.setTool("area.draw");
}

async function handleDrawAreaBtn() {
  if (isDrawingArea.value) {
    // 绘制中：完成
    const pts = [...store.toolDraft.anchors];
    if (pts.length < 3) {
      ElMessage.warning("请至少点击 3 个点圈出区域");
      return;
    }
    store.setTool("");
    store.setArea(pts, "draw", store.draft.area?.bufferM ?? 500);
    bufferDialogM.value = store.draft.area?.bufferM ?? 500;
    bufferDialogVisible.value = true; // 圈定后弹窗设置缓冲距离
    return;
  }
  try {
    await guardEditedArea();
  } catch {
    return;
  }
  store.setTool("area.draw");
}

async function handleClearAreaBtn() {
  if (isDrawingArea.value) {
    store.setTool(""); // 取消手绘
    return;
  }
  if (!hasArea.value) return;
  try {
    await guardEditedArea();
  } catch {
    return;
  }
  store.clearAreaOnly();
  ElMessage.success("研究区域已清除");
}

// ---------------- 地图渲染联动 ----------------
const map = () => MapRef.value?.map || null;

// 地图任何时候都不铺公交线网（线路一律通过搜索选中，避免视觉混乱）；
// 仅“点选站点”类工具激活时临时渲染站点圆点供命中。
const NEEDS_BASE_STOPS = new Set(["pick.stop"]);

function renderBaseNetwork() {
  const m = map();
  if (!m) return;
  if (!NEEDS_BASE_STOPS.has(store.activeTool) || !store.lines.length) {
    updateBaseNetwork(m, [], []); // 清空，保持地图干净
    return;
  }
  const stopFeatures = [];
  for (const s of store.stopIndex.values()) {
    stopFeatures.push({
      type: "Feature",
      geometry: { type: "Point", coordinates: [s.lng, s.lat] },
      properties: { stopId: s.id, name: s.name },
    });
  }
  updateBaseNetwork(m, [], stopFeatures);
}

function renderArea() {
  const m = map();
  if (!m) return;
  const polygon = store.draft.area?.polygon || null;
  const buffer = polygon ? approxBufferRing(polygon, store.draft.area?.bufferM || 0) : null;
  updateArea(m, polygon, buffer);
}

function renderOverlay() {
  const m = map();
  if (!m) return;
  updateOverlay(m, buildOverlayFeatures(store.draft.edits, store.routeIndex, store.stopIndex));
}

function renderHighlight() {
  const m = map();
  if (!m) return;
  // 调整站点面板激活时，编辑预览层承担线路显示，避免高亮线叠加干扰
  if (store.editPreview) {
    updateHighlight(m, null, store.selectedStop ? [store.selectedStop.lng, store.selectedStop.lat] : null, []);
    return;
  }
  const route = store.selectedRoute;
  const stop = store.selectedStop;
  const routeStops = (route?.facilities || [])
    .filter((f) => f?.coord)
    .map((f) => webMercatorToLngLat(f.coord.x, f.coord.y));
  updateHighlight(m, route?.geometry || null, stop ? [stop.lng, stop.lat] : null, routeStops);
}

function renderEditPreview() {
  const m = map();
  if (!m) return;
  updateEditPreview(m, store.editPreview || []);
  renderHighlight();
}

// 新增/修改线路：点选建线时高亮已选站序+连线；弹窗打开期间也常显（供右键编辑）
const buildLineActive = computed(
  () => store.activeTool === "pick.stop" && store.toolContext?.purpose === "buildLine"
);
const lineEditVisible = computed(
  () => buildLineActive.value || store.activeFormKind === "route.add" || store.activeFormKind === "route.edit"
);

function renderLinePicked() {
  const m = map();
  if (!m) return;
  if (!lineEditVisible.value) {
    clearLinePicked(m);
    return;
  }
  const features = [];
  const path = store.lineBuilderPath;
  const geo = path?.geometry;
  if (Array.isArray(geo) && geo.length > 1) {
    features.push({ type: "Feature", geometry: { type: "LineString", coordinates: geo }, properties: {} });
  }
  // 断开处：分两段绿线绘制，中间自然留出可见断口（等用户点选连接）
  if (Array.isArray(path?.segments)) {
    for (const seg of path.segments) {
      if (Array.isArray(seg) && seg.length > 1) {
        features.push({ type: "Feature", geometry: { type: "LineString", coordinates: seg }, properties: {} });
      }
    }
  }
  let stopSeq = 0;
  store.lineBuilder.anchors.forEach((a, idx) => {
    if (!Number.isFinite(a.lng) || !Number.isFinite(a.lat)) return;
    const isStop = a.type === "stop";
    if (isStop) stopSeq += 1;
    features.push({
      type: "Feature",
      geometry: { type: "Point", coordinates: [a.lng, a.lat] },
      properties: { kind: a.type, seq: isStop ? stopSeq : 0, anchorIdx: idx },
    });
  });
  updateLinePicked(m, features);
}

// ---------------- 建线右键菜单：站点(修改/删除/加上一站/下一站) 断面(修改路径) ----------------
const lineCtxMenu = reactive({ visible: false, x: 0, y: 0, type: "stop", anchorIdx: -1, aIdx: -1, bIdx: -1, title: "" });

function anchorStopName(idx) {
  const a = store.lineBuilder.anchors[idx];
  return a?.stopId ? (store.stopIndex.get(a.stopId)?.name || a.stopId) : "";
}

function onMapContextMenu(e) {
  if (!lineEditVisible.value || store.lineBuilder.session) return;
  const m = map();
  if (!m) return;
  const box = [[e.point.x - 8, e.point.y - 8], [e.point.x + 8, e.point.y + 8]];
  const q = (layerId) => {
    if (!m.getLayer(layerId)) return [];
    try { return m.queryRenderedFeatures(box, { layers: [layerId] }); } catch { return []; }
  };
  // 优先命中锚点（停靠站）
  const stopFeat = q(LAYER_IDS.linePickedStops).find((f) => f.properties?.kind === "stop");
  if (stopFeat) {
    e.originalEvent?.preventDefault?.();
    const idx = Number(stopFeat.properties.anchorIdx);
    lineCtxMenu.type = "stop";
    lineCtxMenu.anchorIdx = idx;
    lineCtxMenu.title = `站点：${anchorStopName(idx)}`;
    lineCtxMenu.x = Math.min(e.originalEvent?.clientX ?? e.point.x, window.innerWidth - 220);
    lineCtxMenu.y = Math.min(e.originalEvent?.clientY ?? e.point.y, window.innerHeight - 240);
    lineCtxMenu.visible = true;
    return;
  }
  // 命中连线 → 定位断面（相邻两停靠站之间）
  const geo = store.lineBuilderPath?.geometry;
  if (!q(LAYER_IDS.linePickedLine).length || !Array.isArray(geo) || geo.length < 2) return;
  const stopsArr = [];
  store.lineBuilder.anchors.forEach((a, i) => { if (a.type === "stop") stopsArr.push({ a, i }); });
  if (stopsArr.length < 2) return;
  const clickM = projectMeasureOnPath(geo, [e.lngLat.lng, e.lngLat.lat])?.measure ?? 0;
  const measures = stopsArr.map((s) => projectMeasureOnPath(geo, [s.a.lng, s.a.lat])?.measure ?? 0);
  let k = 0;
  for (let i = 0; i < measures.length - 1; i++) if (measures[i] <= clickM) k = i;
  e.originalEvent?.preventDefault?.();
  lineCtxMenu.type = "segment";
  lineCtxMenu.aIdx = stopsArr[k].i;
  lineCtxMenu.bIdx = stopsArr[k + 1].i;
  lineCtxMenu.title = `断面：${anchorStopName(stopsArr[k].i)} → ${anchorStopName(stopsArr[k + 1].i)}`;
  lineCtxMenu.x = Math.min(e.originalEvent?.clientX ?? e.point.x, window.innerWidth - 220);
  lineCtxMenu.y = Math.min(e.originalEvent?.clientY ?? e.point.y, window.innerHeight - 160);
  lineCtxMenu.visible = true;
}

function lineCtxAction(action) {
  const c = lineCtxMenu;
  c.visible = false;
  if (action === "replace") store.beginStopReplace(c.anchorIdx);
  else if (action === "delete") store.beginStopDelete(c.anchorIdx);
  else if (action === "insertBefore") store.beginInsertBefore(c.anchorIdx);
  else if (action === "insertAfter") store.beginInsertAfter(c.anchorIdx);
  else if (action === "segment") store.beginSegmentEdit(c.aIdx, c.bIdx);
}

function closeLineCtxMenu() {
  lineCtxMenu.visible = false;
}

// ---------------- 视野控制 ----------------
function fitToLngLatCoords(coords) {
  const inst = MapRef.value;
  if (!inst || !Array.isArray(coords) || coords.length === 0) return;
  const points = coords.filter((c) => Array.isArray(c)).map(([lng, lat]) => lngLatToWebMercator(lng, lat));
  if (points.length) inst.setFitZoomAndCenterByPoints?.(points);
}

// ---------------- 编辑期路网底图 ----------------
let roadNetKey = "";
let roadNetSegments = null;
let roadNetLoading = false;

const wantRoadNet = computed(
  () => store.roadNetWanted
    || ["draw.route", "draw.gapfill", "draw.link"].includes(store.activeTool)
    // 新增线路点选站点时也显示灰色路网，便于看清站点将如何沿路连接
    || (store.activeTool === "pick.stop" && store.toolContext?.purpose === "buildLine")
);

async function syncRoadNetwork() {
  const m = map();
  if (!m) return;
  if (!wantRoadNet.value || !store.draft.area?.polygon) {
    clearRoadNetwork(m);
    return;
  }
  const key = `${store.parentModel}|${store.draft.draftId}|${store.draft.area.bufferM}|${JSON.stringify(store.draft.area.polygon)}`;
  if (roadNetSegments && roadNetKey === key) {
    updateRoadNetwork(m, roadNetSegments);
    return;
  }
  if (roadNetLoading) return;
  roadNetLoading = true;
  try {
    const res = await optRoadNetwork({
      parentModel: store.parentModel,
      draftId: store.draft.draftId || "",
      area: store.draft.area,
    });
    roadNetSegments = res?.data?.segments || [];
    roadNetKey = key;
    if (wantRoadNet.value && map()) updateRoadNetwork(map(), roadNetSegments);
  } catch (e) {
    /* 路网底图加载失败不阻断绘制（寻径仍由后端完成） */
  } finally {
    roadNetLoading = false;
  }
}

// ---------------- Delete 键删除选中线路 ----------------
async function promptDeleteSelectedRoute() {
  const r = store.selectedRoute;
  if (!r) return;
  let scope = null;
  try {
    await ElMessageBox.confirm(
      `将把「${r.lineName}」加入删除清单：生成方案时该线路会被移除，加入后可随时在右侧撤销。也可只删除当前方向（${r.routeId}）。按 ESC 或右上角 × 放弃。`,
      "删除线路",
      {
        confirmButtonText: "删除整条线路",
        cancelButtonText: "仅删当前方向",
        distinguishCancelAndClose: true,
        type: "warning",
      }
    );
    scope = "line";
  } catch (action) {
    if (action === "cancel") scope = "route";
    else return; // 关闭 = 放弃
  }
  const payload = scope === "line"
    ? { kind: "route.delete", name: r.lineName, target: { lineId: r.lineId } }
    : { kind: "route.delete", name: `${r.lineName}（${r.routeId}）`, target: { lineId: r.lineId, routeIds: [r.routeId] } };
  const res = store.addEditChecked(payload);
  if (!res.ok) {
    ElMessageBox.alert(res.reason, "无法删除", { type: "warning", confirmButtonText: "知道了" });
    return;
  }
  ElMessage.success(`已加入删除：${payload.name}（可在右侧清单撤销）`);
  store.clearSelection();
}

function handleGlobalKeydown(ev) {
  if (ev.key !== "Delete" && ev.key !== "Backspace") return;
  const target = ev.target;
  if (target && (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.isContentEditable)) return;
  if (store.activeTool) return; // 绘制工具内的 ⌫ 退点由 useMapTools 处理
  if (store.activeFormKind) return; // 弹窗（新增/修改线路）打开时不误触删除，删除走弹窗内按钮
  if (store.selection.type !== "route" || !store.selectedRoute) return;
  ev.preventDefault();
  promptDeleteSelectedRoute();
}

function handleHoverEdit(edit) {
  const m = map();
  if (!m) return;
  if (!edit) {
    renderHighlight();
    return;
  }
  // 悬停修改项时借用高亮层展示其影响范围
  const features = buildOverlayFeatures([edit], store.routeIndex, store.stopIndex);
  const line = features.find((f) => f.geometry.type === "LineString");
  const point = features.find((f) => f.geometry.type === "Point");
  updateHighlight(m, line?.geometry.coordinates || null, point?.geometry.coordinates || null);
}

let stopWatchers = [];

function setupWatchers() {
  stopWatchers.push(watch([() => store.lines, () => store.activeTool], renderBaseNetwork, { deep: false }));
  stopWatchers.push(watch(() => [store.draft.area?.polygon, store.draft.area?.bufferM], renderArea, { deep: true }));
  stopWatchers.push(watch(() => store.draft.edits, renderOverlay, { deep: true }));
  stopWatchers.push(watch(() => [store.selection.lineId, store.selection.routeId, store.selection.stopId], renderHighlight));
  // 调整站点面板的编辑预览
  stopWatchers.push(watch(() => store.editPreview, renderEditPreview));
  // 新增/修改线路点选建线：站序/走向/表单开合变化即刷新
  stopWatchers.push(watch(
    [lineEditVisible, () => store.lineBuilder.anchors, () => store.lineBuilderPath],
    renderLinePicked,
    { deep: true }
  ));
  // 表单关闭/进入点选时收起右键菜单
  stopWatchers.push(watch([lineEditVisible, () => store.lineBuilder.session], closeLineCtxMenu));
  // 需要时加载/清除路网底图
  stopWatchers.push(watch([wantRoadNet, () => store.draft.area?.polygon], syncRoadNetwork));
  // 研究区域圈定/切换草稿后：地图居中到区域
  stopWatchers.push(watch(() => store.draft.area?.polygon, (poly) => {
    if (Array.isArray(poly) && poly.length >= 3) fitToLngLatCoords(poly);
  }));
  // 搜索选中线路后：地图居中到线路走向
  stopWatchers.push(watch(() => [store.selection.lineId, store.selection.routeId], () => {
    const r = store.selectedRoute;
    if (store.selection.type === "route" && r?.geometry?.length > 1) fitToLngLatCoords(r.geometry);
  }));
  // 母本就绪且尚无研究区域：自动进入手绘状态（原 AreaPanel 行为）
  stopWatchers.push(watch(() => store.parentReady, (ready) => {
    if (ready && !hasArea.value && !store.activeTool) store.setTool("area.draw");
  }, { immediate: true }));
}

// 地图可能晚于组件就绪
function whenMapReady(fn) {
  if (map()) {
    fn();
    return;
  }
  const timer = setInterval(() => {
    if (map()) {
      clearInterval(timer);
      fn();
    }
  }, 300);
  stopWatchers.push(() => clearInterval(timer));
}

// ---------------- 生命周期 ----------------
onMounted(() => {
  setupWatchers();
  whenMapReady(() => {
    renderBaseNetwork();
    renderArea();
    renderOverlay();
    // 打开已有草稿时定位到研究区域
    const poly = store.draft.area?.polygon;
    if (Array.isArray(poly) && poly.length >= 3) fitToLngLatCoords(poly);
    // 同步 3D 激活态（update:camera:rotate 是 mymap 包装器事件，绑在 MapRef.value 上）
    if (MapRef.value?.addEventListener) {
      rotateListenerId = MapRef.value.addEventListener("update:camera:rotate", (event) => {
        is3DActive.value = event.data.newPitch !== 90 || event.data.newRotation !== 0;
      });
    }
    // 建线右键菜单（maplibre contextmenu）
    const mm = map();
    if (mm?.on) mm.on("contextmenu", onMapContextMenu);
  });
  window.addEventListener("keydown", handleGlobalKeydown);
  window.addEventListener("click", closeLineCtxMenu);
  store.startJobPolling();
});

onUnmounted(() => {
  stopWatchers.forEach((s) => (typeof s === "function" ? s() : s?.stop?.()));
  stopWatchers = [];
  window.removeEventListener("keydown", handleGlobalKeydown);
  window.removeEventListener("click", closeLineCtxMenu);
  store.stopJobPolling();
  store.setTool("");
  if (rotateListenerId) MapRef.value?.removeEventListener?.("update:camera:rotate", rotateListenerId);
  const m = map();
  if (m) {
    m.off?.("contextmenu", onMapContextMenu);
    removeAllEditorLayers(m);
  }
});
</script>

<style lang="scss" scoped>
.netopt-wrapper {
  position: relative;
  width: 100%;
  height: 100%;
  pointer-events: none; // 面板自身恢复事件，让地图可交互

  > * {
    pointer-events: auto;
  }
}

/* 面板骨架（.dm-sidebar / .dm-overview-panel / .dm-panel-collapse-tab / .map-controls-toolbar）
   全部来自 tokens.css 全局样式，与数据管理、客流分析共用；这里只写本模块的内容排版。 */

.sidebar-steps {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 6px 8px 16px;
}

/* 右侧面板内容排版（骨架由全局 .dm-overview-panel 提供） */
.netopt-right-panel {
  /* 两个视图（清单/线路表单）各自充满面板，靠 v-show 切换 */
  .rp-view {
    flex: 1 1 auto;
    min-height: 0;
    display: flex;
    flex-direction: column;
  }

  .overview-title-row {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--dm2-space-3);
    padding: 0 0 var(--dm2-space-3);
    border-bottom: 1px solid var(--dm2-line-faint, rgba(17, 32, 58, 0.07));
    margin-bottom: var(--dm2-space-3);

    h2 {
      margin: 0;
      color: var(--dm2-ink, #1c2024);
      font-size: var(--dm2-text-xl);
      font-weight: var(--dm2-fw-bold);
      line-height: 1.25;
    }

    .edit-pending-count {
      flex-shrink: 0;
      padding: 3px 10px;
      border-radius: var(--dm2-radius-pill);
      background: rgba(15, 23, 42, 0.05);
      color: var(--dm2-muted, #667085);
      font-size: var(--dm2-text-sm);
      font-weight: var(--dm2-fw-semibold);
      font-variant-numeric: tabular-nums;
      white-space: nowrap;

      &.has-pending {
        background: var(--dm2-accent-weak, rgba(0, 113, 227, 0.1));
        color: var(--dm2-accent, #0071e3);
      }
    }
  }

  /* 修改清单占据主体：自动填充 + 内部滚动（逐条可见） */
  .right-modlist {
    flex: 1 1 auto;
    min-height: 0;
  }

  /* 运行任务：需要时显示，限高避免挤占清单 */
  .right-runtasks {
    flex-shrink: 0;
    max-height: 30vh;
    overflow-y: auto;
    margin-top: var(--dm2-space-3);
  }

  .right-footer {
    flex-shrink: 0;
    display: flex;
    flex-direction: column;
    gap: var(--dm2-space-2);
    margin-top: var(--dm2-space-3);
    padding-top: var(--dm2-space-3);
    border-top: 1px solid var(--dm2-line-faint, rgba(17, 32, 58, 0.07));
  }
}

.guide-block {
  text-align: center;
  padding: 36px 16px;
  color: var(--app-ink-weak, #64748b);

  .guide-icon {
    font-size: 34px;
    margin-bottom: 10px;
  }

  p {
    margin: 4px 0;
    font-size: 13px;
  }

  .sub {
    font-size: 12px;
    line-height: 1.7;
  }
}

/* 缓冲距离弹窗 */
.buffer-dialog-body {
  display: flex;
  flex-direction: column;
  gap: 12px;

  .hint {
    margin: 0;
    font-size: 12.5px;
    line-height: 1.6;
    color: var(--dm2-muted, #667085);
  }

  .buffer-row {
    display: flex;
    align-items: center;
    gap: 12px;
    font-size: 12.5px;

    .label {
      white-space: nowrap;
      color: var(--dm2-muted, #667085);
    }

    .buffer-slider {
      flex: 1;
    }

    .value {
      width: 52px;
      text-align: right;
      font-weight: 600;
      font-variant-numeric: tabular-nums;
    }
  }

  .stats-card {
    border: 1px solid var(--dm2-line, rgba(17, 32, 58, 0.1));
    border-radius: var(--dm2-radius-sm, 10px);
    padding: 10px 12px;
    background: var(--dm2-surface-sunken, #f4f7fb);

    .stats-title {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: 12px;
      font-weight: 700;
      margin-bottom: 8px;
    }

    .stats-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 6px 16px;

      .stat {
        display: flex;
        justify-content: space-between;
        font-size: 12.5px;

        .k {
          color: var(--dm2-muted, #667085);
        }

        .v {
          font-weight: 700;
          font-variant-numeric: tabular-nums;
        }
      }
    }

    .stats-empty {
      font-size: 12px;
      color: var(--dm2-muted-soft, #98a2b3);
    }
  }
}

.generate-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--dm2-space-2);
  width: 100%;
  padding: var(--dm2-space-3) 0;
  border: none;
  border-radius: var(--dm2-radius);
  background: var(--dm2-accent);
  color: #fff;
  font-size: var(--dm2-text-lg);
  font-weight: var(--dm2-fw-bold);
  cursor: pointer;
  box-shadow: var(--dm2-accent-glow);
  transition:
    background-color var(--dm2-dur) var(--dm2-ease),
    box-shadow var(--dm2-dur) var(--dm2-ease),
    transform var(--dm2-dur-fast) var(--dm2-ease);

  &:hover:not(:disabled) {
    background: var(--dm2-accent-strong);
  }

  &:active:not(:disabled) {
    transform: translateY(1px);
    box-shadow: 0 3px 10px -4px rgba(0, 113, 227, 0.45);
  }

  &:focus-visible {
    outline: none;
    box-shadow: var(--dm2-accent-glow), 0 0 0 3px var(--dm2-accent-ring);
  }

  &:disabled {
    background: rgba(17, 32, 58, 0.08);
    color: var(--dm2-muted-soft);
    box-shadow: none;
    cursor: not-allowed;
  }

  .count {
    font-size: var(--dm2-text-xs);
    font-weight: var(--dm2-fw-semibold);
    background: rgba(255, 255, 255, 0.22);
    border-radius: var(--dm2-radius-pill);
    padding: 2px var(--dm2-space-2);
  }
}

.gen-block-tip {
  margin: 0;
  font-size: var(--dm2-text-xs);
  color: var(--dm2-muted, #667085);
  text-align: center;
}


.route-picker {
  position: fixed;
  z-index: 3000;
  min-width: 200px;
  background: #fff;
  border: 1px solid var(--app-border, #dde3ec);
  border-radius: 10px;
  box-shadow: 0 10px 32px rgba(15, 35, 72, 0.18);
  padding: 6px;

  .picker-title {
    font-size: 11px;
    color: #94a3b8;
    padding: 4px 8px;
  }

  .picker-item {
    display: block;
    width: 100%;
    text-align: left;
    padding: 6px 8px;
    font-size: 13px;
    border: none;
    background: transparent;
    border-radius: 6px;
    cursor: pointer;

    &:hover {
      background: rgba(21, 105, 222, 0.08);
    }

    .dir {
      font-size: 11px;
      color: #94a3b8;
      margin-left: 6px;
    }
  }

  .picker-cancel {
    display: block;
    width: 100%;
    padding: 5px 8px;
    margin-top: 2px;
    font-size: 12px;
    color: #64748b;
    border: none;
    border-top: 1px solid var(--app-border, #eef2f7);
    background: transparent;
    cursor: pointer;
  }
}

/* ============ 地图控件：清除按钮禁用态（补全 tokens.css 未定义部分） ============ */
.control-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.control-btn:disabled:hover {
  background: transparent;
  color: var(--dm2-ink-soft, #3b4452);
}

/* ============ 浮动定位搜索框：与客流分析 .rm-search 完全同源（逐字复制其样式） ============ */
.rm-search {
  position: fixed;
  top: calc(var(--app-header-height, 58px) + var(--app-scaled-20, 20px));
  left: var(--app-scaled-282, 282px);
  width: 292px;
  z-index: calc(var(--z-header, 1400) + 6);
  display: flex;
  align-items: center;
  gap: 8px;
  height: 42px;
  padding: 0 12px;
  box-sizing: border-box;
  border: 1px solid var(--dm2-line, rgba(17, 32, 58, 0.1));
  border-radius: 12px;
  background: var(--dm2-glass-strong, rgba(255, 255, 255, 0.92));
  box-shadow: var(--dm2-shadow-pop, 0 12px 30px -16px rgba(13, 38, 76, 0.3));
  -webkit-backdrop-filter: var(--dm2-glass-blur, blur(12px));
  backdrop-filter: var(--dm2-glass-blur, blur(12px));
  transform-origin: top left;
  transition:
    left 160ms var(--dm2-ease, cubic-bezier(0.32, 0.72, 0, 1)),
    box-shadow 160ms var(--dm2-ease, cubic-bezier(0.32, 0.72, 0, 1)),
    border-color 160ms var(--dm2-ease, cubic-bezier(0.32, 0.72, 0, 1));
}

.rm-search.is-left-collapsed {
  left: var(--app-scaled-22, 22px);
}

.rm-search.is-focused {
  border-color: var(--dm2-accent, #0071e3);
  box-shadow: 0 0 0 3px rgba(0, 113, 227, 0.14), var(--dm2-shadow-pop, 0 12px 30px -16px rgba(13, 38, 76, 0.3));
}

.rm-search-icon {
  flex-shrink: 0;
  width: 17px;
  height: 17px;
  color: var(--dm2-muted, #667085);
}

.rm-search-input {
  flex: 1;
  min-width: 0;
  height: 100%;
  border: 0;
  outline: 0;
  background: transparent;
  font-size: 14px;
  color: var(--dm2-ink, #1c2024);
  font-family: var(--dm2-font, inherit);

  &::placeholder {
    color: var(--dm2-muted-soft, #98a2b3);
  }

  &::-webkit-search-cancel-button {
    -webkit-appearance: none;
    appearance: none;
  }
}

.rm-search-clear {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: rgba(17, 32, 58, 0.06);
  color: var(--dm2-muted, #667085);
  cursor: pointer;
  transition: background 120ms ease, color 120ms ease;

  &:hover {
    background: rgba(17, 32, 58, 0.12);
    color: var(--dm2-ink, #1c2024);
  }
}

.rm-search-results {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  right: 0;
  max-height: 320px;
  overflow-y: auto;
  padding: 6px;
  box-sizing: border-box;
  border: 1px solid var(--dm2-line, rgba(17, 32, 58, 0.1));
  border-radius: 12px;
  background: var(--dm2-glass-strong, rgba(255, 255, 255, 0.97));
  box-shadow: var(--dm2-shadow-panel, 0 20px 48px -24px rgba(13, 38, 76, 0.34));
  -webkit-backdrop-filter: var(--dm2-glass-blur, blur(12px));
  backdrop-filter: var(--dm2-glass-blur, blur(12px));
}

.rm-search-result {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
  text-align: left;
  transition: background 120ms ease;

  &:hover {
    background: rgba(0, 113, 227, 0.08);
  }
}

.rm-result-icon {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  color: #ffffff;

  svg {
    width: 16px;
    height: 16px;
  }

  &.line {
    background: linear-gradient(135deg, #0a3f86, #0071e3);
  }

  &.station {
    background: linear-gradient(135deg, #0b8f74, #18b89a);
  }
}

.rm-result-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
  gap: 1px;
}

.rm-result-name {
  overflow: hidden;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--dm2-ink, #1c2024);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rm-result-type {
  font-size: 11px;
  color: var(--dm2-muted, #667085);
}

.rm-search-empty {
  margin: 0;
  padding: 14px 10px;
  text-align: center;
  font-size: 13px;
  color: var(--dm2-muted, #667085);
}

.rm-search-fade-enter-active,
.rm-search-fade-leave-active {
  transition: opacity 160ms ease, transform 160ms ease;
}

.rm-search-fade-enter-from,
.rm-search-fade-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}
</style>
