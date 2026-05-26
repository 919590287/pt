<!-- Passenger Flow Analysis (客流分析) -->
<template>
  <div class="datebase_box" role="search" aria-label="方案与模型选择">
    <label class="handle" for="scheme-selector">当前方案</label>
    <el-select id="scheme-selector" v-model="datebase.scheme" clearable filterable :loading="isLoadingSchemes" aria-label="当前方案">
      <el-option v-for="item in schemeList" :key="item" :label="item" :value="item"> </el-option>
    </el-select>
    <el-select class="model-select" v-model="datebase.model" :disabled="!datebase.scheme || isLoadingModels" clearable filterable :loading="isLoadingModels" aria-label="选择模型">
      <el-option v-for="item in modelList" :key="item.name" :label="item.name" :value="item.name">
        <div class="model-option">
          <span>{{ item.name }}</span>

          <el-tag type="success" v-if="item.loadStatus">已加载</el-tag>
          <el-tag type="warning" v-else>未加载</el-tag>
        </div>
      </el-option>
    </el-select>
    <span v-if="loadError" class="load-error" role="status">{{ loadError }}</span>
  </div>

  <template v-if="selectModel">
    <template v-if="selectModel.loadStatus">
      <div id="left-analysis-panel" ref="box1" :class="['model_box', 'box1', isLeftCollapsed ? 'collapsed' : '']" :style="box1Style" v-show="showSidebar">
        <!-- Collapse Button -->
        <button
          class="collapse-tab left-tab"
          type="button"
          @click="toggleLeftPanel"
          :aria-label="isLeftCollapsed ? '展开左侧分析面板' : '折叠左侧分析面板'"
          :aria-expanded="!isLeftCollapsed"
          aria-controls="left-analysis-panel"
          :title="isLeftCollapsed ? '展开面板' : '折叠面板'"
        >
          <svg class="chevron-icon" :class="{ 'rotated': isLeftCollapsed }" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="15 18 9 12 15 6"></polyline>
          </svg>
        </button>
        <div class="tab_list" ref="box1Handle" style="cursor: move; display: flex; align-items: center; justify-content: space-between; padding: 10px var(--space-md); background: rgba(21, 105, 222, 0.07); border-bottom: 1px solid rgba(21, 105, 222, 0.15); border-top-left-radius: var(--app-panel-radius); border-top-right-radius: var(--app-panel-radius);">
          <div class="header-title" style="display: flex; align-items: center; gap: var(--space-xs); font-size: 15px; font-weight: 750; color: var(--app-blue);">
            <span class="icon">📈</span>
            <span>客流分析</span>
          </div>
        </div>
        <div class="sub_tab_list_wrapper">
          <el-radio-group v-model="activeTransitSubTab" size="default" class="custom-sub-tabs">
            <el-radio-button label="线路客流监测">线路客流监测</el-radio-button>
            <el-radio-button label="站点客流监测">站点客流监测</el-radio-button>
            <el-radio-button label="体检评估分析">体检评估分析</el-radio-button>
          </el-radio-group>
        </div>
        <el-scrollbar class="flex_column_scroll_box">
          <XLZL v-if="activeTransitSubTab == '线路客流监测'" :key="`xlzl-${selectModel.name}`" :model="selectModel.name" />
          <ZDZL v-else-if="activeTransitSubTab == '站点客流监测'" :key="`zdzl-${selectModel.name}`" :model="selectModel.name" />
          <TJFX v-else-if="activeTransitSubTab == '体检评估分析'" :key="`tjfx-${selectModel.name}`" :model="selectModel.name" />
        </el-scrollbar>
      </div>
      <div id="right-info-panel" :class="['box2', isRightCollapsed ? 'collapsed' : '']" v-show="isRightPanelVisible">
        <!-- Collapse Button -->
        <button
          class="collapse-tab right-tab"
          type="button"
          @click="toggleRightPanel"
          :aria-label="isRightCollapsed ? '展开右侧信息面板' : '折叠右侧信息面板'"
          :aria-expanded="!isRightCollapsed"
          aria-controls="right-info-panel"
          :title="isRightCollapsed ? '展开面板' : '折叠面板'"
        >
          <svg class="chevron-icon" :class="{ 'rotated': isRightCollapsed }" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="9 18 15 12 9 6"></polyline>
          </svg>
        </button>
        <el-scrollbar class="flex_column_scroll_box">
          <div id="datavisualization_index_box2"></div>
        </el-scrollbar>
      </div>

      <!-- Floating Map Controls Toolbar -->
      <div 
        :class="['map-controls-toolbar', (isRightPanelVisible && !isRightCollapsed) ? 'with-panel' : 'without-panel']"
        :style="{ '--right-panel-width': `${rightPanelWidth}px` }"
      >
        <!-- Block 1: Zoom & 3D & Compass -->
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
              <svg class="caret-up" viewBox="0 0 24 24" width="10" height="10" fill="currentColor">
                <polygon points="12,4 2,18 22,18"></polygon>
              </svg>
              <svg class="caret-down" viewBox="0 0 24 24" width="10" height="10" fill="currentColor">
                <polygon points="12,20 2,6 22,6"></polygon>
              </svg>
            </div>
          </button>
        </div>

        <!-- Block 2: Line Settings Toggle & Floating Popover -->
        <div class="control-block settings-block">
          <button
            :class="['control-btn', showLineWidthPopover ? 'active' : '']"
            type="button"
            @click="handleToggleLineWidthPopover"
            :title="effectiveTab === '站点客流监测' ? '站点大小设置' : '线形设置'"
            :aria-label="effectiveTab === '站点客流监测' ? '打开站点大小设置' : '打开线形设置'"
            :aria-expanded="showLineWidthPopover"
            aria-controls="line-width-popover"
            aria-haspopup="dialog"
          >
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <line x1="4" y1="7" x2="20" y2="7"></line>
              <circle cx="15" cy="7" r="1.5" fill="currentColor"></circle>
              <line x1="4" y1="12" x2="20" y2="12"></line>
              <circle cx="17" cy="12" r="1.5" fill="currentColor"></circle>
              <line x1="4" y1="17" x2="20" y2="17"></line>
              <circle cx="9" cy="17" r="1.5" fill="currentColor"></circle>
            </svg>
          </button>
        </div>

        <!-- Floating Popover for Line Width -->
        <Transition name="popover-fade">
          <div v-if="showLineWidthPopover" id="line-width-popover" class="line-width-popover" role="dialog" aria-modal="false" @click.stop>
            <div class="popover-title">{{ effectiveTab === '站点客流监测' ? '站点大小设置' : '线形设置' }}</div>
            <div class="popover-content">
              <div class="slider-row" v-if="effectiveTab === '站点客流监测'">
                <span class="label">
                  <span>站点大小</span>
                  <span class="val-text">{{ `${stationSize}px` }}</span>
                </span>
                <el-slider v-model="stationSize" :min="minStationSize" :max="maxStationSize" :step="1" @input="handleStationSizeChange" />
              </div>
              <template v-else>
                <div class="slider-row">
                  <span class="label">
                    <span>线宽</span>
                    <span class="val-text">{{ `${lineWidth}px` }}</span>
                  </span>
                  <el-slider v-model="lineWidth" :min="minLineWidth" :max="maxLineWidth" :step="1" @input="handleLineWidthChange" />
                </div>
              </template>
              <div class="flow-control-row" v-if="effectiveTab !== '线路客流监测' && effectiveTab !== '站点客流监测' && effectiveTab !== '体检评估分析'">
                <span>按流量控制</span>
                <el-switch v-model="flowControl" @change="handleFlowControlChange" />
              </div>
            </div>
          </div>
        </Transition>

        <!-- Block 3: Highlight state visual-only toggle -->
        <div :class="['control-block', 'info-block', !isSegmentQueryActive ? 'inactive-block' : '']">
          <button :class="['control-btn', 'info-btn', isSegmentQueryActive ? 'active' : '']" type="button" @click="handleToggleSegmentQuery" title="路段信息查询" aria-label="切换路段信息查询" :aria-pressed="isSegmentQueryActive">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor">
              <circle cx="12" cy="7" r="1.5"></circle>
              <path d="M11 10h2v8h-2z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"></path>
              <path d="M9 10h3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"></path>
              <path d="M10 18h4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"></path>
            </svg>
          </button>
        </div>
      </div>

      <!-- Floating Segment Info Popover -->
      <Transition name="popover-fade">
        <div 
          v-if="isSegmentQueryActive && selectedSegment" 
          class="segment-info-popover"
          :style="{
            left: `${popoverPosition.x}px`,
            top: `${popoverPosition.y}px`
          }"
        >
          <div class="popover-header">
            <span class="title">路段详细信息</span>
            <button class="close-btn" type="button" aria-label="关闭路段详细信息" @click.stop="selectedSegment = null">×</button>
          </div>
          <div class="popover-body">
            <div class="info-row">
              <span class="label">路段 ID</span>
              <span class="val">{{ selectedSegment.linkId }}</span>
            </div>
            <div class="info-row">
              <span class="label">路段长度</span>
              <span class="val">{{ formatLength(selectedSegment.length) }}</span>
            </div>
            <div class="info-row">
              <span class="label">车道数量</span>
              <span class="val">{{ formatLanes(selectedSegment.lanes) }}</span>
            </div>
            <div class="info-row">
              <span class="label">路段流量</span>
              <span class="val">{{ formatFlow(selectedSegment.flow) }}</span>
            </div>
          </div>
        </div>
      </Transition>
    </template>
    <div v-else ref="box1" class="model_box box1" :style="box1Style">
      <el-empty description="模型加载中，请稍等...." />
    </div>
  </template>
  <div v-else ref="box1" class="model_box box1" :style="box1Style">
    <el-empty description="请选择模型" />
  </div>
</template>

<script setup>
import { ref, computed, watch, inject, provide, onMounted, onUnmounted, nextTick, defineAsyncComponent, useTemplateRef } from "vue";
import { getSchemeList, getModelList, loadModel } from "@/api/scheme.js";
import { dataCenter } from "@/api/data.js";
import { useDraggable } from "@vueuse/core";
import { HighlightSegmentLayer } from "@/views/datavisualization/layers/HighlightSegmentLayer.js";

const XLZL = defineAsyncComponent(() => import("@/views/datavisualization/components/XLZL.vue"));
const ZDZL = defineAsyncComponent(() => import("@/views/datavisualization/components/ZDZL.vue"));
const TJFX = defineAsyncComponent(() => import("@/views/datavisualization/components/TJFX.vue"));

const LEFT_PANEL_SCALE = 0.86;
const LEFT_PANEL_EDGE_X = 0;
const LEFT_PANEL_EXPANDED_X = 16;
const LEFT_PANEL_MIN_TOP = 67;
const box1Ref = useTemplateRef("box1");

const { style: box1Style, x: box1X, y: box1Y } = useDraggable(box1Ref, {
  initialValue: { x: LEFT_PANEL_EXPANDED_X, y: 120 },
  handle: useTemplateRef("box1Handle"),
});

let leftPanelResizeObserver = null;

function centerLeftPanel() {
  if (typeof window === "undefined") return;
  nextTick(() => {
    const box = box1Ref.value;
    if (!box) return;

    const maxLayoutHeight = (window.innerHeight - 150) / LEFT_PANEL_SCALE;
    const visualHeight = Math.min(box.offsetHeight, maxLayoutHeight) * LEFT_PANEL_SCALE;
    box1Y.value = Math.max(LEFT_PANEL_MIN_TOP, (window.innerHeight - visualHeight) / 2);
  });
}

function observeLeftPanelSize() {
  if (typeof window === "undefined" || typeof ResizeObserver === "undefined") return;
  nextTick(() => {
    const box = box1Ref.value;
    if (!box) return;

    leftPanelResizeObserver?.disconnect();
    leftPanelResizeObserver = new ResizeObserver(() => centerLeftPanel());
    leftPanelResizeObserver.observe(box);
    centerLeftPanel();
  });
}

const datebase = ref({
  scheme: "",
  model: "",
});
const schemeList = ref([]);
const modelList = ref([]);
const isLoadingSchemes = ref(false);
const isLoadingModels = ref(false);
const loadError = ref("");
let schemeRequestSeq = 0;
let modelRequestSeq = 0;
let centerRequestSeq = 0;
const selectModel = computed(() => {
  const item = modelList.value?.find((item) => item.name === datebase.value.model);
  return item;
});

watch(
  () => datebase.value.scheme,
  async (scheme) => {
    datebase.value.model = "";
    modelList.value = [];
    if (!scheme) return;
    const list = await handleGetModelList();
    if (list.length && !datebase.value.model) {
      datebase.value.model = list[0].name;
    }
  },
);

async function handleGetSchemeList(options = {}) {
  const { silent = false, autoSelect = false } = options;
  const seq = ++schemeRequestSeq;
  if (!silent) {
    isLoadingSchemes.value = true;
    loadError.value = "";
  }
  try {
    const res = await getSchemeList(undefined, { silentError: silent });
    if (seq !== schemeRequestSeq) return schemeList.value;
    const list = Array.isArray(res?.data) ? res.data : [];
    schemeList.value = list;
    if (autoSelect && !datebase.value.scheme && list.length) {
      datebase.value.scheme = list[0];
    } else if (datebase.value.scheme && !list.includes(datebase.value.scheme)) {
      datebase.value.scheme = list[0] || "";
    }
    if (!silent && !list.length) {
      loadError.value = "暂无可用方案";
    }
    return list;
  } catch (error) {
    if (seq === schemeRequestSeq && !silent) {
      loadError.value = error?.message || "方案列表加载失败，请检查后端服务";
    }
    return [];
  } finally {
    if (seq === schemeRequestSeq && !silent) {
      isLoadingSchemes.value = false;
    }
  }
}

watch(
  () => datebase.value.model,
  async () => {
    if (!datebase.value.model) return;
    try {
      if (selectModel.value && !selectModel.value.loadStatus) {
        await loadModel({ name: datebase.value.model });
        await handleGetModelList({ silent: true });
      }
    } catch (error) {
      loadError.value = error?.message || "模型加载失败，请稍后重试";
    } finally {
      setMapCenter();
    }
  },
);

const MapRef = inject("MapRef");
watch(MapRef, setMapCenter);

async function setMapCenter() {
  const seq = ++centerRequestSeq;
  if (selectModel.value && selectModel.value.name) {
    try {
      const res = await dataCenter({ datasource: selectModel.value.name }, { silentError: true });
      if (seq !== centerRequestSeq) return;
      const x = Number(res?.data?.x);
      const y = Number(res?.data?.y);
      if (Number.isFinite(x) && Number.isFinite(y)) {
        MapRef.value?.setCenter([x, y]);
      }
    } catch (error) {
      loadError.value = error?.message || "地图中心点加载失败";
    }
  }
}

async function handleGetModelList(options = {}) {
  const { silent = false } = options;
  if (!datebase.value.scheme) {
    modelList.value = [];
    return [];
  }
  const seq = ++modelRequestSeq;
  if (!silent) {
    isLoadingModels.value = true;
    loadError.value = "";
  }
  try {
    const res = await getModelList({ schemeName: datebase.value.scheme }, { silentError: silent });
    if (seq !== modelRequestSeq) return modelList.value;
    const list = Array.isArray(res?.data) ? res.data : [];
    modelList.value = list;
    if (datebase.value.model && !list.some((item) => item.name === datebase.value.model)) {
      datebase.value.model = "";
    }
    if (!silent && !list.length) {
      loadError.value = "当前方案暂无可用模型";
    }
    return list;
  } catch (error) {
    if (seq === modelRequestSeq && !silent) {
      loadError.value = error?.message || "模型列表加载失败，请检查后端服务";
    }
    return [];
  } finally {
    if (seq === modelRequestSeq && !silent) {
      isLoadingModels.value = false;
    }
  }
}

const activeTransitSubTab = ref("线路客流监测");

const effectiveTab = computed(() => activeTransitSubTab.value);

const rightPanelWidth = computed(() => {
  if (effectiveTab.value === '线路客流监测' || effectiveTab.value === '站点客流监测') {
    return 535;
  }
  return 470;
});

provide("activeDatavisualizationTab", effectiveTab);

const rightPanelHasContent = ref(true);
provide("rightPanelHasContent", rightPanelHasContent);

watch(effectiveTab, (tab) => {
  rightPanelHasContent.value = false;
  if (tab === "线路客流监测") {
    lineWidth.value = 42;
  } else {
    lineWidth.value = 20;
  }
  applyLineWidth();
  applyStationSize();
  scheduleLayerSyncBurst(4);
  observeLeftPanelSize();
});

// Map Controls State & Logic
const mapZoom = ref(10.74);
const mapPitch = ref(90);
const mapRotation = ref(0);

const showSidebar = ref(true);
const showRightPanel = ref(true);
const isLeftCollapsed = ref(false);
const isRightCollapsed = ref(false);
const flowControl = ref(false);

const isRightPanelVisible = computed(() => showRightPanel.value && rightPanelHasContent.value);
const isInfoActive = computed(() => isRightPanelVisible.value);
const is3DActive = ref(false);

const isSegmentQueryActive = ref(false);
const selectedSegment = ref(null);
const popoverPosition = ref({ x: 0, y: 0 });

let highlightLayer = null;
let clickListenerId = null;

function toggleLeftPanel() {
  if (isLeftCollapsed.value) {
    box1X.value = LEFT_PANEL_EXPANDED_X;
    isLeftCollapsed.value = false;
    centerLeftPanel();
    return;
  }
  box1X.value = LEFT_PANEL_EDGE_X;
  isLeftCollapsed.value = true;
}

function toggleRightPanel() {
  isRightCollapsed.value = !isRightCollapsed.value;
}

function handleToggleSegmentQuery() {
  isSegmentQueryActive.value = !isSegmentQueryActive.value;
}

function initHighlightLayer(mapInstance) {
  if (mapInstance && !highlightLayer) {
    highlightLayer = new HighlightSegmentLayer({
      lineWidth: computedLineWidth.value * 1.5
    });
    mapInstance.addLayer(highlightLayer);
  }
}

function pointToSegmentDistance(cx, cy, ax, ay, bx, by) {
  const dx = bx - ax;
  const dy = by - ay;
  const l2 = dx * dx + dy * dy;
  if (l2 === 0) return Math.sqrt((cx - ax) ** 2 + (cy - ay) ** 2);
  let t = ((cx - ax) * dx + (cy - ay) * dy) / l2;
  t = Math.max(0, Math.min(1, t));
  const projX = ax + t * dx;
  const projY = ay + t * dy;
  return Math.sqrt((cx - projX) ** 2 + (cy - projY) ** 2);
}

function handleMapClick(e) {
  if (!isSegmentQueryActive.value) return;
  if (!e.data || !e.data.webMercatorXY) return;

  const [clickX, clickY] = e.data.webMercatorXY;
  if (!MapRef.value) return;

  const networkLayer = MapRef.value.layers.find(layer => layer.constructor.name === 'NetworkLayer');
  if (!networkLayer || !networkLayer.data || !networkLayer.data.length) {
    selectedSegment.value = null;
    if (highlightLayer) highlightLayer.setData(null);
    return;
  }

  let minDistance = Infinity;
  let closestLink = null;
  const links = networkLayer.data;

  for (let i = 0; i < links.length; i++) {
    const link = links[i];
    const dist = pointToSegmentDistance(
      clickX, clickY,
      link.from.x, link.from.y,
      link.to.x, link.to.y
    );
    if (dist < minDistance) {
      minDistance = dist;
      closestLink = link;
    }
  }

  if (closestLink && minDistance < 50) {
    selectedSegment.value = closestLink;
    if (highlightLayer) {
      highlightLayer.setData(closestLink);
    }
    
    let popX = e.data.event.clientX;
    let popY = e.data.event.clientY;
    
    const popoverWidth = 240 * LEFT_PANEL_SCALE;
    const popoverHeight = 160 * LEFT_PANEL_SCALE;
    const viewportWidth = window.innerWidth;
    const viewportHeight = window.innerHeight;
    
    if (popX + popoverWidth + 20 > viewportWidth) {
      popX = popX - popoverWidth - 20;
    }
    if (popY + popoverHeight + 20 > viewportHeight) {
      popY = popY - popoverHeight - 20;
    }
    
    popoverPosition.value = { x: popX, y: popY };
  } else {
    selectedSegment.value = null;
    if (highlightLayer) {
      highlightLayer.setData(null);
    }
  }
}

function formatLength(val) {
  if (val === undefined || val === null) return "-";
  return `${(Number(val) || 0).toFixed(1)} m`;
}

function formatLanes(val) {
  if (val === undefined || val === null) return "-";
  return Math.round(Number(val) || 1).toString();
}

function formatFlow(val) {
  if (val === undefined || val === null) return "0.0";
  return (Number(val) || 0).toFixed(1);
}

watch(isSegmentQueryActive, (active) => {
  if (!active) {
    selectedSegment.value = null;
    if (highlightLayer) {
      highlightLayer.setData(null);
    }
    if (MapRef.value && clickListenerId) {
      MapRef.value.removeEventListener("handle:click", clickListenerId);
      clickListenerId = null;
    }
  } else {
    if (MapRef.value) {
      initHighlightLayer(MapRef.value);
      if (!clickListenerId) {
        clickListenerId = MapRef.value.addEventListener("handle:click", handleMapClick);
      }
    }
  }
});

watch(selectedSegment, (segment) => {
  if (!segment && highlightLayer) {
    highlightLayer.setData(null);
  }
});

const showLineWidthPopover = ref(false);
const lineWidth = ref(20);
const stationSize = ref(22);
const referenceZoom = ref(10.74);
let isZoomCaptured = false;

// trajectory Demonstration size / Fallbacks for imports
const vehicleSize = ref(36);
const vehicleVisibilityMode = ref("all");

const minLineWidth = computed(() => {
  return effectiveTab.value === '线路客流监测' ? 18 : 3;
});

const maxLineWidth = computed(() => {
  return effectiveTab.value === '线路客流监测' ? 120 : 40;
});
const minStationSize = computed(() => 10);
const maxStationSize = computed(() => 36);

const lineWidthZoomScale = computed(() => {
  const delta = mapZoom.value - referenceZoom.value;
  const scale = Math.pow(2, 0.18 * delta);
  return Math.max(0.45, Math.min(1.55, scale));
});
const computedLineWidth = computed(() => {
  return Math.max(3, lineWidth.value * lineWidthZoomScale.value);
});
const computedFlowWidthStep = computed(() => Math.max(6, Math.min(18, 14 * lineWidthZoomScale.value)));

provide("LineWidthRef", computedLineWidth);
provide("FlowWidthStepRef", computedFlowWidthStep);
provide("FlowControlRef", flowControl);
provide("StationSizeRef", stationSize);
provide("VehicleSizeRef", vehicleSize);
provide("VehicleVisibilityModeRef", vehicleVisibilityMode);

watch(computedLineWidth, (val) => {
  applyLineWidth();
  if (highlightLayer) {
    highlightLayer.setLineWidth(val * 1.5);
  }
});
watch(computedFlowWidthStep, () => {
  applyFlowWidthStep();
});
watch(stationSize, () => {
  applyStationSize();
});

function handleToggleLineWidthPopover() {
  showLineWidthPopover.value = !showLineWidthPopover.value;
}

function applyLineWidth() {
  if (MapRef.value && MapRef.value.layers) {
    MapRef.value.layers.forEach((layer) => {
      if (typeof layer.setLineWidth === "function") {
        layer.setLineWidth(computedLineWidth.value);
      }
    });
  }
}

function applyFlowWidthStep() {
  if (MapRef.value && MapRef.value.layers) {
    MapRef.value.layers.forEach((layer) => {
      if (typeof layer.setFlowWidthStep === "function") {
        layer.setFlowWidthStep(computedFlowWidthStep.value);
      }
    });
  }
}

function applyStationSize() {
  if (MapRef.value && MapRef.value.layers) {
    MapRef.value.layers.forEach((layer) => {
      if (typeof layer.setMarkerSize === "function") {
        layer.setMarkerSize(stationSize.value);
      }
    });
  }
}

let syncLayersRetryTimer = null;

function syncAllLayerSettings() {
  applyLineWidth();
  applyFlowWidthStep();
  applyFlowControl();
  applyStationSize();
}

function scheduleLayerSyncBurst(remaining = 6) {
  if (syncLayersRetryTimer) {
    clearTimeout(syncLayersRetryTimer);
    syncLayersRetryTimer = null;
  }
  const run = (left) => {
    syncAllLayerSettings();
    if (left > 1) {
      syncLayersRetryTimer = setTimeout(() => run(left - 1), 240);
    } else {
      syncLayersRetryTimer = null;
    }
  };
  run(remaining);
}

function handleLineWidthChange(val) {
  lineWidth.value = val;
  applyLineWidth();
  applyFlowWidthStep();
}

function handleStationSizeChange(val) {
  stationSize.value = val;
  applyStationSize();
}

function applyFlowControl() {
  if (MapRef.value && MapRef.value.layers) {
    MapRef.value.layers.forEach((layer) => {
      if (typeof layer.setFlowControl === "function") {
        layer.setFlowControl(flowControl.value);
      }
    });
  }
}

function handleFlowControlChange(val) {
  flowControl.value = val;
  applyFlowControl();
}

let zoomListenerId = null;
let centerListenerId = null;
let rotateListenerId = null;
let resizeTimerId = null;
let lastMapMotionAt = 0;

function scheduleMapResize(delay = 0) {
  if (!MapRef.value?.map) return;
  if (delay > 0) {
    if (resizeTimerId) {
      clearTimeout(resizeTimerId);
    }
    resizeTimerId = setTimeout(() => {
      resizeTimerId = null;
      MapRef.value?.map?.resize();
    }, delay);
    return;
  }
  nextTick(() => {
    MapRef.value?.map?.resize();
  });
}

function handleDocumentKeydown(event) {
  if (event.key !== "Escape") return;
  showLineWidthPopover.value = false;
  selectedSegment.value = null;
}

function handleZoomIn() {
  if (MapRef.value) {
    const currentZoom = MapRef.value.zoom;
    MapRef.value.setZoom(currentZoom + 1);
  }
}

function handleZoomOut() {
  if (MapRef.value) {
    const currentZoom = MapRef.value.zoom;
    MapRef.value.setZoom(currentZoom - 1);
  }
}

function handleToggle3D() {
  if (MapRef.value) {
    if (is3DActive.value) {
      MapRef.value.setPitchAndRotation(90, 0);
      MapRef.value.enableRotate = false;
      is3DActive.value = false;
    } else {
      MapRef.value.enableRotate = true;
      MapRef.value.setPitchAndRotation(45, MapRef.value.rotation);
      is3DActive.value = true;
    }
  }
}

function handleResetCompass() {
  if (MapRef.value) {
    MapRef.value.setPitchAndRotation(90, 0);
    MapRef.value.enableRotate = false;
    is3DActive.value = false;
  }
}

watch(MapRef, (mapInstance) => {
  setMapCenter();
  if (mapInstance) {
    scheduleMapResize();
    scheduleMapResize(450);

    mapZoom.value = mapInstance.zoom;
    if (!isZoomCaptured) {
      referenceZoom.value = mapInstance.zoom;
      isZoomCaptured = true;
    }
    mapPitch.value = mapInstance.pitch;
    mapRotation.value = mapInstance.rotation;
    is3DActive.value = mapInstance.enableRotate || mapInstance.pitch !== 90 || mapInstance.rotation !== 0;
    
    if (zoomListenerId) {
      mapInstance.removeEventListener("update:zoom", zoomListenerId);
    }
    if (centerListenerId) {
      mapInstance.removeEventListener("update:center", centerListenerId);
    }
    if (rotateListenerId) {
      mapInstance.removeEventListener("update:camera:rotate", rotateListenerId);
    }
    if (clickListenerId) {
      mapInstance.removeEventListener("handle:click", clickListenerId);
      clickListenerId = null;
    }
    
    zoomListenerId = mapInstance.addEventListener("update:zoom", (e) => {
      lastMapMotionAt = performance.now();
      mapZoom.value = e.data;
    });
    centerListenerId = mapInstance.addEventListener("update:center", () => {
      lastMapMotionAt = performance.now();
    });
    rotateListenerId = mapInstance.addEventListener("update:camera:rotate", (e) => {
      lastMapMotionAt = performance.now();
      mapPitch.value = e.data.newPitch;
      mapRotation.value = e.data.newRotation;
      if (e.data.newPitch !== 90 || e.data.newRotation !== 0) {
        is3DActive.value = true;
      }
    });

    if (isSegmentQueryActive.value) {
      initHighlightLayer(mapInstance);
      clickListenerId = mapInstance.addEventListener("handle:click", handleMapClick);
    }
    scheduleLayerSyncBurst(5);
  }
});

watch(isRightPanelVisible, (visible) => {
  if (visible && effectiveTab.value === "线路客流监测") {
    isRightCollapsed.value = false;
  }
});

watch(
  [effectiveTab, isLeftCollapsed, isRightCollapsed, showRightPanel, rightPanelHasContent],
  () => {
    if (MapRef.value && MapRef.value.map) {
      scheduleMapResize();
      scheduleMapResize(450);
    }
  }
);

const ins = setInterval(() => {
  handleGetSchemeList({ silent: true });
  handleGetModelList({ silent: true });
}, 1000 * 20);

onMounted(() => {
  observeLeftPanelSize();
  window.addEventListener("resize", centerLeftPanel);
  document.addEventListener("keydown", handleDocumentKeydown);
  handleGetSchemeList({ autoSelect: true }).then(() => {
    observeLeftPanelSize();
  });
  scheduleLayerSyncBurst(8);
});

onUnmounted(() => {
  leftPanelResizeObserver?.disconnect();
  leftPanelResizeObserver = null;
  window.removeEventListener("resize", centerLeftPanel);
  document.removeEventListener("keydown", handleDocumentKeydown);
  clearInterval(ins);
  if (resizeTimerId) {
    clearTimeout(resizeTimerId);
    resizeTimerId = null;
  }
  if (syncLayersRetryTimer) {
    clearTimeout(syncLayersRetryTimer);
    syncLayersRetryTimer = null;
  }
  if (MapRef.value) {
    if (zoomListenerId) {
      MapRef.value.removeEventListener("update:zoom", zoomListenerId);
    }
    if (rotateListenerId) {
      MapRef.value.removeEventListener("update:camera:rotate", rotateListenerId);
    }
    if (centerListenerId) {
      MapRef.value.removeEventListener("update:center", centerListenerId);
    }
    if (clickListenerId) {
      MapRef.value.removeEventListener("handle:click", clickListenerId);
    }
  }
  if (highlightLayer) {
    highlightLayer.dispose();
  }
});
</script>

<style lang="scss" scoped>
.datebase_box,
.box1,
.box2,
.map-controls-toolbar {
  scale: var(--app-panel-scale);
}

.datebase_box {
  position: fixed;
  top: calc(var(--app-header-height) / 2);
  right: calc(var(--app-edge) + 64px);
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  transform: translateY(-50%);
  transform-origin: right center;
  z-index: calc(var(--z-header) + 10);
  max-width: min(46vw, 520px);
  min-width: 0;
  .handle {
    cursor: default;
    font-size: 0.95rem;
    font-weight: 600;
    color: #374151;
    text-shadow: none;
    white-space: nowrap;
  }
  .model-option {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-sm);
    min-width: 0;

    span:first-child {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .load-error {
    max-width: 180px;
    color: var(--app-coral);
    font-size: 12px;
    font-weight: 600;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .el-select {
    width: clamp(150px, 14vw, 210px);
    
    :deep(.el-input__wrapper) {
      background-color: rgba(251, 253, 255, 0.88) !important;
      box-shadow: 0 0 0 1px var(--app-border-strong) inset !important;
      border-radius: var(--app-card-radius);
      padding: 6px 12px;
      transition: background-color 0.2s ease, box-shadow 0.2s ease;
      
      &:hover {
        background-color: var(--app-card-bg) !important;
        box-shadow: 0 0 0 1px rgba(11, 145, 183, 0.45) inset !important;
      }
      
      &.is-focus {
        background-color: var(--app-card-bg) !important;
        box-shadow: 0 0 0 1.5px var(--app-cyan) inset, var(--app-focus-ring) !important;
      }
      
      .el-input__inner {
        color: var(--app-ink) !important;
        font-weight: 500;
        font-size: 0.94rem !important;
        &::placeholder {
          color: rgba(18, 48, 79, 0.5);
        }
      }
      
      .el-select__caret {
        color: var(--app-cyan) !important;
        font-size: 14px;
      }
    }
  }
}
.box1 {
  box-sizing: border-box;
  padding: var(--space-sm);
  position: fixed;
  z-index: var(--z-panel);
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  width: min(430px, calc((100vw - 48px) / var(--app-panel-scale)));
  max-height: calc((100vh - 132px) / var(--app-panel-scale));
  min-width: min(430px, calc((100vw - 48px) / var(--app-panel-scale)));
  min-height: 0;
  transform-origin: top left;
  transition: transform var(--app-motion-slow) var(--app-ease-out);
  
  &.collapsed {
    transform: translateX(-100%) !important;
    pointer-events: none;

    .collapse-tab {
      pointer-events: auto;
    }
  }
  
  .handle {
    cursor: move;
  }

  .sub_tab_list_wrapper {
    display: flex;
    justify-content: center;
    width: 100%;
    
    .custom-sub-tabs {
      width: 100%;
      display: flex;
      background-color: rgba(21, 105, 222, 0.05);
      border-radius: var(--app-card-radius);
      padding: var(--space-2xs);
      border: 1px solid rgba(21, 105, 222, 0.1);
      
      :deep(.el-radio-button) {
        flex: 1;
        display: flex;
        
        .el-radio-button__inner {
          width: 100%;
          border: none !important;
          background: transparent !important;
          color: var(--app-muted);
          font-weight: 500;
          font-size: 13px;
          border-radius: 4px !important;
          padding: 6px 0;
          box-shadow: none !important;
          transition:
            background-color var(--app-motion-normal) var(--app-ease-out),
            color var(--app-motion-normal) var(--app-ease-out),
            transform var(--app-motion-fast) var(--app-ease-press);
          
          &:hover {
            color: var(--app-blue);
            transform: translateY(-1px);
          }
        }
        
        &.is-active {
          .el-radio-button__inner {
            background-color: var(--app-card-bg) !important;
            color: var(--app-blue) !important;
            font-weight: bold;
            box-shadow: none !important;
          }
        }
      }
    }
  }

  .scroll_box {
    height: 0 !important;
    flex: 1;
  }
}

.box2 {
  position: fixed;
  z-index: var(--z-panel);
  right: var(--app-edge);
  top: calc(var(--app-header-height) + var(--space-md));
  max-height: calc((100vh - var(--app-header-height) - 40px) / var(--app-panel-scale));
  display: flex;
  flex-direction: column;
  min-height: 0;
  transform-origin: top right;
  transition: transform var(--app-motion-slow) var(--app-ease-out);
  
  &.collapsed {
    transform: translateX(calc(100% + var(--app-edge) + var(--space-xs))) !important;
    pointer-events: none;

    .collapse-tab {
      pointer-events: auto;
    }
  }
  
  #datavisualization_index_box2 {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: var(--space-sm);
  }
}

.map-controls-toolbar {
  position: fixed;
  top: calc(var(--app-header-height) + var(--space-sm));
  transition: right var(--app-motion-slow) var(--app-ease-out);
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  z-index: calc(var(--z-header) + 5);
  transform-origin: top right;

  &.with-panel {
    right: calc(var(--app-edge) + var(--right-panel-width) * var(--app-panel-scale) + 12px);
  }

  &.without-panel {
    right: var(--app-edge);
  }

  .control-block {
    display: flex;
    flex-direction: column;
    background-color: var(--app-card-bg);
    border-radius: var(--app-card-radius);
    border: 1px solid rgba(21, 105, 222, 0.11);
    box-shadow: var(--app-shadow-sm);
    overflow: hidden;
    width: 44px;

    .control-btn {
      width: 44px;
      height: 44px;
      padding: 0;
      border: none;
      background: transparent;
      color: var(--app-ink);
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      transition:
        background-color var(--app-motion-normal) var(--app-ease-out),
        color var(--app-motion-normal) var(--app-ease-out),
        transform var(--app-motion-fast) var(--app-ease-press);
      outline: none;

      &:not(:last-child) {
        border-bottom: 1px solid rgba(21, 105, 222, 0.08);
      }

      &:hover {
        background-color: var(--app-cyan-soft);
        color: var(--app-cyan-strong);
      }

      &:active {
        transform: translateY(1px);
      }

      svg,
      .pitch-arrows {
        transition: transform var(--app-motion-normal) var(--app-ease-out);
      }

      &:hover svg,
      &:hover .pitch-arrows {
        transform: translateY(-1px);
      }

      &.td-btn {
        font-size: 11px;
        font-weight: bold;
        font-family: var(--app-font-number);
        color: var(--app-ink);

        &.active {
          color: var(--app-cyan-strong);
          background-color: var(--app-cyan-soft);
        }
      }

      &.active {
        color: var(--app-cyan-strong);
      }

      .pitch-arrows {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 1px;
        color: var(--app-ink);

        .caret-up {
          color: var(--app-ink);
        }
        .caret-down {
          color: var(--app-muted-soft);
        }
      }
    }

    &.info-block {
      background-color: var(--app-cyan);
      border: 1px solid rgba(11, 145, 183, 0.72);
      transition: background-color 0.2s ease, border-color 0.2s ease;

      &.inactive-block {
        background-color: color-mix(in oklch, var(--app-muted-soft) 70%, white) !important;
        border-color: color-mix(in oklch, var(--app-muted-soft) 76%, white) !important;
        
        .info-btn {
          color: rgba(247, 251, 255, 0.78) !important;
          
          &:hover {
            background-color: rgba(0, 0, 0, 0.05) !important;
          }
        }
      }

      .info-btn {
        color: #f7fbff;

        &:hover {
          background-color: var(--app-cyan-strong);
        }

        &.active {
          background-color: rgba(255, 255, 255, 0.15);
        }
      }
    }
  }
}

.line-width-popover {
  position: absolute;
  right: 48px;
  top: 76px;
  width: min(240px, calc(100vw - 96px));
  background: var(--app-panel-bg);
  border: 1px solid var(--app-border);
  border-radius: var(--app-panel-radius);
  box-shadow: var(--app-shadow-sm);
  padding: var(--space-sm) var(--space-md);
  z-index: calc(var(--z-popover) - 1);
  display: flex;
  flex-direction: column;
  gap: 8px;
  box-sizing: border-box;

  .popover-title {
    font-size: 13px;
    font-weight: 700;
    color: var(--app-ink);
    border-bottom: 1px solid rgba(21, 105, 222, 0.09);
    padding-bottom: 6px;
    margin: 0;
  }

  .popover-content {
    .slider-row {
      display: flex;
      flex-direction: column;
      gap: 4px;

      .label {
        font-size: 11px;
        color: var(--app-muted);
        display: flex;
        justify-content: space-between;
        
        .val-text {
          font-family: var(--app-font-number);
          color: var(--app-cyan);
          font-weight: bold;
        }
      }
      
      .el-slider {
        margin-top: 4px;
        --el-slider-main-bg-color: var(--app-cyan);
        --el-slider-runway-bg-color: color-mix(in oklch, var(--app-blue-soft) 70%, white);
      }
    }

    .flow-control-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-top: 12px;
      padding-top: 10px;
      border-top: 1px solid rgba(21, 105, 222, 0.09);
      font-size: 11px;
      font-weight: 600;
      color: var(--app-muted);
    }
  }
}

.popover-fade-enter-active,
.popover-fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}
.popover-fade-enter-from,
.popover-fade-leave-to {
  opacity: 0;
  transform: translateX(10px);
}

.collapse-tab {
  --tab-shift-x: 0px;
  position: absolute;
  top: 50%;
  transform: translate(var(--tab-shift-x), -50%);
  width: 44px;
  min-width: 44px;
  height: 48px;
  border: 0;
  background: var(--app-card-bg);
  border: 1px solid rgba(21, 105, 222, 0.15);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  z-index: 10;
  transition:
    background-color var(--app-motion-normal) var(--app-ease-out),
    color var(--app-motion-normal) var(--app-ease-out),
    transform var(--app-motion-normal) var(--app-ease-out);
  color: var(--app-blue);
  
  &:hover {
    background: var(--app-cyan-soft);
    color: var(--app-cyan-strong);
  }
  
  .chevron-icon {
    width: 14px;
    height: 14px;
    transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    
    &.rotated {
      transform: rotate(180deg);
    }
  }
}

.left-tab {
  right: -44px;
  border-radius: 0 8px 8px 0;
  border-left: none;

  &:hover {
    --tab-shift-x: 2px;
  }
}

.right-tab {
  left: -44px;
  border-radius: 8px 0 0 8px;
  border-right: none;

  &:hover {
    --tab-shift-x: -2px;
  }
}

.segment-info-popover {
  position: fixed;
  width: 240px;
  max-width: calc(100vw - 32px);
  background: var(--app-panel-bg);
  border: 1px solid rgba(21, 105, 222, 0.2);
  border-radius: var(--app-panel-radius);
  box-shadow: var(--app-shadow-sm);
  padding: 14px 16px;
  z-index: var(--z-popover);
  pointer-events: auto;
  transform-origin: top left;
  transition: opacity 0.2s ease, transform 0.2s ease;
  scale: var(--app-panel-scale);

  .popover-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    border-bottom: 1px solid rgba(21, 105, 222, 0.15);
    padding-bottom: 8px;
    margin-bottom: 10px;

    .title {
      font-size: 13px;
      font-weight: 700;
      color: var(--app-blue);
      letter-spacing: 0.5px;
      font-family: var(--app-font-number);
    }

    .close-btn {
      background: none;
      border: none;
      color: var(--app-muted);
      font-size: 18px;
      cursor: pointer;
      padding: 0 4px;
      line-height: 1;
      transition: color 0.2s ease;

      &:hover {
        color: #dc4c5d;
      }
    }
  }

  .popover-body {
    display: flex;
    flex-direction: column;
    gap: 8px;

    .info-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: 12px;

      .label {
        color: var(--app-muted);
        font-weight: 500;
      }

      .val {
        min-width: 0;
        max-width: 140px;
        overflow-wrap: anywhere;
        text-align: right;
        color: var(--app-ink);
        font-weight: 700;
        font-family: var(--app-font-number);
      }
    }
  }
}

@media (max-width: 1024px) {
  .datebase_box {
    right: calc(var(--app-edge) + 36px);
    max-width: 52vw;
  }

  .box1 {
    width: min(400px, calc((100vw - 48px) / var(--app-panel-scale)));
    min-width: min(400px, calc((100vw - 48px) / var(--app-panel-scale)));
  }

  .map-controls-toolbar.with-panel {
    right: calc(var(--app-edge) + var(--right-panel-width) * var(--app-panel-scale) + 12px);
  }
}

@media (max-width: 960px) {
  .datebase_box {
    top: calc(var(--app-header-height) + var(--space-lg));
    right: var(--app-edge);
    max-width: calc(100vw - (var(--app-edge) * 2));
    flex-wrap: wrap;
    justify-content: flex-end;
  }

  .map-controls-toolbar.with-panel {
    right: var(--app-edge);
  }
}

@media (max-width: 640px) {
  .datebase_box {
    left: var(--app-edge);
    transform: none;

    .handle,
    .load-error {
      width: 100%;
      text-align: right;
    }

    .el-select {
      width: min(100%, 190px);
    }
  }

  .box1 {
    width: calc((100vw - 32px) / var(--app-panel-scale));
    min-width: calc((100vw - 32px) / var(--app-panel-scale));

    .tab_list {
      flex-wrap: wrap;
    }
  }
}
</style>
