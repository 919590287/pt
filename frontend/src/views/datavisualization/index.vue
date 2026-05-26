<!-- index -->
<template>
  <div class="datebase_box">
    <div class="handle">选择方案</div>
    <el-select v-model="datebase.scheme" clearable filterable @change="">
      <el-option v-for="item in schemeList" :key="item" :label="item" :value="item"> </el-option>
    </el-select>
    <el-select v-model="datebase.model" :disabled="!datebase.scheme" clearable filterable @change="" style="width: 200px">
      <el-option v-for="item in modelList" :key="item.name" :label="item.name" :value="item.name">
        <div style="display: flex; align-items: center; justify-content: space-between; gap: 10px">
          <span>{{ item.name }}</span>

          <el-tag type="success" v-if="item.loadStatus">已加载</el-tag>
          <el-tag type="warning" v-else>未加载</el-tag>
        </div>
      </el-option>
    </el-select>
  </div>

  <template v-if="selectModel">
    <template v-if="selectModel.loadStatus">
      <div ref="box1" :class="['model_box', 'box1', isLeftCollapsed ? 'collapsed' : '']" :style="box1Style" v-show="showSidebar">
        <!-- Collapse Button -->
        <div class="collapse-tab left-tab" @click="isLeftCollapsed = !isLeftCollapsed" :title="isLeftCollapsed ? '展开面板' : '折叠面板'">
          <svg class="chevron-icon" :class="{ 'rotated': isLeftCollapsed }" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="15 18 9 12 15 6"></polyline>
          </svg>
        </div>
        <div class="tab_list" ref="box1Handle">
          <el-button type="primary" :plain="activeTab != '数据总览'" @pointerdown.stop @click="handleSetActiveTab('数据总览')">数据总览</el-button>
          <el-button type="primary" :plain="activeTab != '公交分析'" @pointerdown.stop @click="handleSetActiveTab('公交分析')">公交分析</el-button>
          <el-button type="primary" :plain="activeTab != '轨迹演示'" @pointerdown.stop @click="handleSetActiveTab('轨迹演示')">轨迹演示</el-button>
          <el-button type="primary" :plain="activeTab != '出行者分析'" @pointerdown.stop @click="handleSetActiveTab('出行者分析')">出行者分析</el-button>
        </div>
        <Transition name="popover-fade">
          <div v-if="activeTab === '公交分析'" class="sub_tab_list_wrapper">
            <el-radio-group v-model="activeTransitSubTab" size="default" class="custom-sub-tabs">
              <el-radio-button label="线路客流监测">线路客流监测</el-radio-button>
              <el-radio-button label="站点客流监测">站点客流监测</el-radio-button>
              <el-radio-button label="体检评估分析">体检评估分析</el-radio-button>
            </el-radio-group>
          </div>
        </Transition>
        <el-scrollbar class="flex_column_scroll_box">
          <SJZL v-if="activeTab == '数据总览'" :key="`sjzl-${selectModel.name}`" :model="selectModel.name" />
          <XLZL v-else-if="isRouteAnalysisActive" :key="`xlzl-${selectModel.name}`" :model="selectModel.name" />
          <ZDZL v-else-if="isStationAnalysisActive" :key="`zdzl-${selectModel.name}`" :model="selectModel.name" />
          <TJFX v-else-if="isEvaluationAnalysisActive" :key="`tjfx-${selectModel.name}`" :model="selectModel.name" />
          <GJYS v-else-if="activeTab == '轨迹演示'" :key="`gjys-${selectModel.name}`" :model="selectModel.name" />
          <CXZFX v-else-if="activeTab == '出行者分析'" :key="`cxzfx-${selectModel.name}`" :model="selectModel.name" />
        </el-scrollbar>
      </div>
      <div :class="['box2', isRightCollapsed ? 'collapsed' : '']" v-show="isRightPanelVisible">
        <!-- Collapse Button -->
        <div class="collapse-tab right-tab" @click="isRightCollapsed = !isRightCollapsed" :title="isRightCollapsed ? '展开面板' : '折叠面板'">
          <svg class="chevron-icon" :class="{ 'rotated': isRightCollapsed }" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="9 18 15 12 9 6"></polyline>
          </svg>
        </div>
        <el-scrollbar class="flex_column_scroll_box">
          <div id="datavisualization_index_box2"></div>
        </el-scrollbar>
      </div>

      <!-- Floating Map Controls Toolbar -->
      <div :class="['map-controls-toolbar', (isRightPanelVisible && !isRightCollapsed) ? 'with-panel' : 'without-panel']">
        <!-- Block 1: Zoom & 3D & Compass -->
        <div class="control-block">
          <button class="control-btn" @click="handleZoomIn" title="放大">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
              <line x1="12" y1="5" x2="12" y2="19"></line>
              <line x1="5" y1="12" x2="19" y2="12"></line>
            </svg>
          </button>
          <button class="control-btn" @click="handleZoomOut" title="缩小">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
              <line x1="5" y1="12" x2="19" y2="12"></line>
            </svg>
          </button>
          <button :class="['control-btn', 'td-btn', is3DActive ? 'active' : '']" @click="handleToggle3D" title="3D视图">
            3D
          </button>
          <button class="control-btn compass-btn" @click="handleResetCompass" title="指北针">
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
            @click="handleToggleLineWidthPopover"
            :title="effectiveTab === '站点分析' ? '站点大小设置' : effectiveTab === '轨迹演示' ? '车辆模型设置' : '线形设置'"
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
          <div v-if="showLineWidthPopover" class="line-width-popover" @click.stop>
            <div class="popover-title">{{ effectiveTab === '站点分析' ? '站点大小设置' : effectiveTab === '轨迹演示' ? '车辆模型设置' : '线形设置' }}</div>
            <div class="popover-content">
              <div class="slider-row" v-if="effectiveTab === '站点客流监测'">
                <span class="label">
                  <span>站点大小</span>
                  <span class="val-text">{{ `${stationSize}px` }}</span>
                </span>
                <el-slider v-model="stationSize" :min="minStationSize" :max="maxStationSize" :step="1" @input="handleStationSizeChange" />
              </div>
              <div class="slider-row" v-else-if="effectiveTab === '轨迹演示'">
                <span class="label">
                  <span>车辆模型</span>
                  <span class="val-text">{{ `${vehicleSize}px` }}</span>
                </span>
                <el-slider v-model="vehicleSize" :min="minVehicleSize" :max="maxVehicleSize" :step="1" @input="handleVehicleSizeChange" />
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
              <div class="vehicle-visibility-row" v-if="effectiveTab === '轨迹演示'">
                <span>可视化范围</span>
                <el-select v-model="vehicleVisibilityMode" size="small" @change="handleVehicleVisibilityModeChange">
                  <el-option
                    v-for="item in vehicleVisibilityOptions"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
              </div>
              <div class="flow-control-row" v-else-if="effectiveTab !== '线路客流监测' && effectiveTab !== '站点客流监测' && effectiveTab !== '体检评估分析'">
                <span>按流量控制</span>
                <el-switch v-model="flowControl" @change="handleFlowControlChange" />
              </div>
            </div>
          </div>
        </Transition>

        <!-- Block 3: Highlight state visual-only toggle -->
        <div :class="['control-block', 'info-block', !isSegmentQueryActive ? 'inactive-block' : '']">
          <button :class="['control-btn', 'info-btn', isSegmentQueryActive ? 'active' : '']" @click="handleToggleSegmentQuery" title="路段信息查询">
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
            <button class="close-btn" @click.stop="selectedSegment = null">×</button>
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
import { getSchemeList, getModelList, loadModel } from "@/api/scheme.js";
import { dataCenter } from "@/api/data.js";

import SJZL from "./components/SJZL.vue";
import XLZL from "./components/XLZL.vue";
import ZDZL from "./components/ZDZL.vue";
import TJFX from "./components/TJFX.vue";
import GJYS from "./components/GJYS.vue";
import CXZFX from "./components/CXZFX.vue";

import { useDraggable } from "@vueuse/core";
import { HighlightSegmentLayer } from "./layers/HighlightSegmentLayer.js";



const LEFT_PANEL_SCALE = 0.8;
const LEFT_PANEL_MIN_TOP = 67;
const box1Ref = useTemplateRef("box1");

const { style: box1Style, y: box1Y } = useDraggable(box1Ref, {
  initialValue: { x: 16, y: 120 },
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
const schemeList = ref(null);
const modelList = ref(null);
const selectModel = computed(() => {
  const item = modelList.value?.find((item) => item.name === datebase.value.model);

  return item;
});

watch(
  () => datebase.value.scheme,
  (scheme) => {
    datebase.value.model = "";
    handleGetModelList();
  },
);
function handleGetSchemeList() {
  return getSchemeList().then((res) => {
    schemeList.value = res.data;
  });
}

watch(
  () => datebase.value.model,
  async () => {
    try {
      if (selectModel.value && !selectModel.value.loadStatus) {
        await loadModel({ name: datebase.value.model });
      }
    } catch (error) {
    } finally {
      setMapCenter();
    }
  },
);
const MapRef = inject("MapRef");
watch(MapRef, setMapCenter);
function setMapCenter() {
  if (selectModel.value && selectModel.value.name) {
    dataCenter({ datasource: selectModel.value.name }).then((res) => {
      MapRef.value?.setCenter([res.data.x, res.data.y]);
    });
  }
}

function handleGetModelList() {
  if (!datebase.value.scheme) return;
  return getModelList({ schemeName: datebase.value.scheme }).then((res) => {
    modelList.value = res.data;
  });
}

const activeTab = ref("数据总览");
const activeTransitSubTab = ref("线路客流监测");

const effectiveTab = computed(() => {
  if (activeTab.value === "公交分析") {
    return activeTransitSubTab.value;
  }
  return activeTab.value;
});

const isRouteAnalysisActive = computed(() => activeTab.value === "公交分析" && activeTransitSubTab.value === "线路客流监测");
const isStationAnalysisActive = computed(() => activeTab.value === "公交分析" && activeTransitSubTab.value === "站点客流监测");
const isEvaluationAnalysisActive = computed(() => activeTab.value === "公交分析" && activeTransitSubTab.value === "体检评估分析");

function handleSetActiveTab(tabName) {
  activeTab.value = tabName;
}
provide("activeDatavisualizationTab", effectiveTab);

const rightPanelHasContent = ref(true);
provide("rightPanelHasContent", rightPanelHasContent);

watch(effectiveTab, (tab) => {
  rightPanelHasContent.value = false;
  if (tab === "线路客流监测") {
    lineWidth.value = 42;
  } else if (tab === "数据总览") {
    rightPanelHasContent.value = true;
    showRightPanel.value = true;
    isRightCollapsed.value = false;
    lineWidth.value = 20;
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
const vehicleVisibilityMode = ref("all");
const vehicleVisibilityOptions = [
  { label: "全部车辆", value: "all" },
  { label: "仅公共交通", value: "public" },
  { label: "仅私家车", value: "private" },
];

const isRightPanelVisible = computed(() => showRightPanel.value && rightPanelHasContent.value);
const isInfoActive = computed(() => isRightPanelVisible.value);
const is3DActive = ref(false);

const isSegmentQueryActive = ref(false);
const selectedSegment = ref(null);
const popoverPosition = ref({ x: 0, y: 0 });

let highlightLayer = null;
let clickListenerId = null;

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

  // Pick distance limit (e.g. 50 meters in Web Mercator meters)
  if (closestLink && minDistance < 50) {
    selectedSegment.value = closestLink;
    if (highlightLayer) {
      highlightLayer.setData(closestLink);
    }
    
    // Position popover with screen boundary adjustments
    let popX = e.data.event.clientX;
    let popY = e.data.event.clientY;
    
    const popoverWidth = 240 * 0.8;
    const popoverHeight = 160 * 0.8;
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
const vehicleSize = ref(36);
const referenceZoom = ref(10.74);
let isZoomCaptured = false;

const minLineWidth = computed(() => {
  return effectiveTab.value === '线路客流监测' ? 18 : 3;
});

const maxLineWidth = computed(() => {
  return effectiveTab.value === '线路客流监测' ? 120 : 40;
});
const minStationSize = computed(() => 10);
const maxStationSize = computed(() => 36);
const minVehicleSize = computed(() => 20);
const maxVehicleSize = computed(() => 72);

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
watch(vehicleSize, () => {
  applyVehicleSize();
});
watch(vehicleVisibilityMode, () => {
  applyVehicleVisibilityMode();
});

let fpsFrameId = null;
let fpsLastAt = 0;
let fpsWindowStart = 0;
let fpsFrames = 0;
let lastMapMotionAt = 0;
const perfSamples = [];

function publishPerfProbe(fps = 0, now = performance.now()) {
  const moving = now - lastMapMotionAt < 220;
  const sample = {
    fps: Math.round(fps * 10) / 10,
    hz: Math.round(fps),
    samples: perfSamples.slice(-120),
    tab: effectiveTab.value,
    moving,
    timestamp: now,
  };
  window.__GJ_VIS_PERF__ = sample;
  document.documentElement.dataset.gjVisFps = String(sample.fps);
  document.documentElement.dataset.gjVisHz = String(sample.hz);
  document.documentElement.dataset.gjVisMoving = moving ? "1" : "0";
  document.documentElement.dataset.gjVisTab = effectiveTab.value;
}

function startPerfProbe() {
  if (fpsFrameId || typeof requestAnimationFrame !== "function") return;
  fpsLastAt = performance.now();
  fpsWindowStart = fpsLastAt;
  fpsFrames = 0;
  const tick = (now) => {
    const delta = now - fpsLastAt;
    fpsLastAt = now;
    fpsFrames += 1;
    if (delta > 0 && delta < 1000) {
      perfSamples.push(1000 / delta);
      if (perfSamples.length > 240) {
        perfSamples.splice(0, perfSamples.length - 240);
      }
    }
    if (now - fpsWindowStart >= 500) {
      publishPerfProbe((fpsFrames * 1000) / Math.max(1, now - fpsWindowStart), now);
      fpsWindowStart = now;
      fpsFrames = 0;
    }
    fpsFrameId = requestAnimationFrame(tick);
  };
  fpsFrameId = requestAnimationFrame(tick);
}

function stopPerfProbe() {
  if (fpsFrameId) {
    cancelAnimationFrame(fpsFrameId);
    fpsFrameId = null;
  }
}

let zoomListenerId = null;
let centerListenerId = null;
let rotateListenerId = null;

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

function handleToggleLineWidthPopover() {
  showLineWidthPopover.value = !showLineWidthPopover.value;
}

function handleToggleInfo() {
  showRightPanel.value = !showRightPanel.value;
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

function applyVehicleSize() {
  if (MapRef.value && MapRef.value.layers) {
    MapRef.value.layers.forEach((layer) => {
      if (typeof layer.setVehicleSize === "function") {
        layer.setVehicleSize(vehicleSize.value);
      }
    });
  }
}

function applyVehicleVisibilityMode() {
  if (MapRef.value && MapRef.value.layers) {
    MapRef.value.layers.forEach((layer) => {
      if (typeof layer.setVehicleVisibilityMode === "function") {
        layer.setVehicleVisibilityMode(vehicleVisibilityMode.value);
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
  applyVehicleSize();
  applyVehicleVisibilityMode();
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

function handleVehicleSizeChange(val) {
  vehicleSize.value = val;
  applyVehicleSize();
}

function handleVehicleVisibilityModeChange(val) {
  vehicleVisibilityMode.value = val;
  applyVehicleVisibilityMode();
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

watch(MapRef, (mapInstance) => {
  setMapCenter();
  
  if (mapInstance) {
    nextTick(() => {
      mapInstance.map?.resize();
    });
    setTimeout(() => {
      mapInstance.map?.resize();
    }, 450);

    mapZoom.value = mapInstance.zoom;
    if (!isZoomCaptured) {
      referenceZoom.value = mapInstance.zoom;
      isZoomCaptured = true;
    }
    mapPitch.value = mapInstance.pitch;
    mapRotation.value = mapInstance.rotation;
    is3DActive.value = mapInstance.enableRotate || mapInstance.pitch !== 90 || mapInstance.rotation !== 0;
    
    // Remove old listeners if any
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
    
    // Add new listeners
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
  if (visible && (effectiveTab.value === "数据总览" || effectiveTab.value === "轨迹演示")) {
    isRightCollapsed.value = false;
  }
});

watch(rightPanelHasContent, (hasContent) => {
  if (hasContent && effectiveTab.value === "轨迹演示") {
    showRightPanel.value = true;
    isRightCollapsed.value = false;
  }
});

// 监听标签切换和左右侧边栏折叠状态，动态触发地图重绘 resize，解决底图只渲染局部区域的经典Bug
watch(
  [effectiveTab, isLeftCollapsed, isRightCollapsed, showRightPanel, rightPanelHasContent],
  () => {
    if (MapRef.value && MapRef.value.map) {
      // 1. 立即响应状态变化，重绘视口
      nextTick(() => {
        MapRef.value?.map?.resize();
      });
      // 2. 在 400ms 的 CSS 伸缩过渡动画结束后再次重绘，确保底图瓦片完全填满全屏
      setTimeout(() => {
        MapRef.value?.map?.resize();
      }, 450);
    }
  }
);

const ins = setInterval(() => {
  handleGetSchemeList();
  handleGetModelList();
}, 1000 * 20);

handleGetSchemeList();

onMounted(() => {
  startPerfProbe();
  observeLeftPanelSize();
  window.addEventListener("resize", centerLeftPanel);
  handleGetSchemeList()
    .then(() => {
      datebase.value.scheme = schemeList.value[0];
      return handleGetModelList();
    })
    .then(() => {
      datebase.value.model = modelList.value[0].name;
      observeLeftPanelSize();
    });

  scheduleLayerSyncBurst(8);
});
onUnmounted(() => {
  stopPerfProbe();
  leftPanelResizeObserver?.disconnect();
  leftPanelResizeObserver = null;
  window.removeEventListener("resize", centerLeftPanel);
  sessionStorage.removeItem("request_params");
  clearInterval(ins);
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
  .handle {
    cursor: default;
    font-size: 16px;
    font-weight: 600;
    color: var(--app-blue);
    text-shadow: none;
    white-space: nowrap;
  }
  .el-select {
    width: clamp(150px, 14vw, 210px);
    
    :deep(.el-input__wrapper) {
      background-color: rgba(255, 255, 255, 0.8) !important;
      box-shadow: 0 0 0 1px rgba(21, 105, 222, 0.25) inset !important;
      backdrop-filter: blur(4px);
      border-radius: var(--app-card-radius);
      padding: 6px 12px;
      transition: all 0.3s ease;
      
      &:hover {
        background-color: #ffffff !important;
        box-shadow: 0 0 0 1px rgba(21, 105, 222, 0.5) inset !important;
      }
      
      &.is-focus {
        background-color: #ffffff !important;
        box-shadow: 0 0 0 1.5px rgba(21, 105, 222, 1) inset, 0 0 8px rgba(21, 105, 222, 0.15) !important;
      }
      
      .el-input__inner {
        color: #1a365d !important;
        font-weight: 500;
        font-size: 15px !important;
        &::placeholder {
          color: rgba(26, 54, 93, 0.5);
        }
      }
      
      .el-select__caret {
        color: #1569de !important;
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
  width: 430px;
  max-height: calc((100vh - 132px) / var(--app-panel-scale));
  min-width: 430px;
  min-height: 0;
  transform-origin: top left;
  transition: transform 0.4s cubic-bezier(0.4, 0, 0.2, 1);
  
  &.collapsed {
    transform: translateX(calc(-100% + 24px)) !important;
  }
  
  .handle {
    cursor: move;
  }

  .tab_list {
    display: flex;
    align-items: center;
    background-color: rgba(21, 105, 222, 0.045);
    border: 1px solid rgba(21, 105, 222, 0.09);
    border-radius: var(--app-card-radius);
    width: 100%;
    gap: var(--space-xs);
    padding: var(--space-2xs);
    .el-button {
      flex: 1;
      margin: 0;
      min-height: 32px;
      border-radius: 4px;
    }
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
          color: #7f8c8d;
          font-weight: 500;
          font-size: 13px;
          border-radius: 4px !important;
          padding: 6px 0;
          box-shadow: none !important;
          transition: all 0.25s ease;
          
          &:hover {
            color: #1569de;
          }
        }
        
        &.is-active {
          .el-radio-button__inner {
            background-color: #ffffff !important;
            color: #1569de !important;
            font-weight: bold;
            box-shadow: 0 2px 6px rgba(21, 105, 222, 0.15) !important;
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
  transition: transform 0.4s cubic-bezier(0.4, 0, 0.2, 1);
  
  &.collapsed {
    transform: translateX(calc(100% - 8px)) !important;
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
  transition: right 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  z-index: calc(var(--z-header) + 5);
  transform-origin: top right;

  &.with-panel {
    right: 456px;
  }

  &.without-panel {
    right: var(--app-edge);
  }

  .control-block {
    display: flex;
    flex-direction: column;
    background-color: #ffffff;
    border-radius: var(--app-card-radius);
    box-shadow: var(--app-shadow-sm);
    border: 1px solid rgba(21, 105, 222, 0.11);
    overflow: hidden;
    width: 36px;

    .control-btn {
      width: 36px;
      height: 36px;
      padding: 0;
      border: none;
      background: transparent;
      color: #333333;
      cursor: pointer;
      display: flex;
      align-items: center;
      justify-content: center;
      transition: background-color 0.2s ease, color 0.2s ease;
      outline: none;

      &:not(:last-child) {
        border-bottom: 1px solid #f0f0f0;
      }

      &:hover {
        background-color: rgba(21, 105, 222, 0.06);
        color: var(--app-blue);
      }

      &.td-btn {
        font-size: 11px;
        font-weight: bold;
        font-family: "Outfit", "Inter", sans-serif;
        color: #2c3e50;

        &.active {
          color: #409eff;
          background-color: #ecf5ff;
        }
      }

      &.active {
        color: #409eff;
      }

      .pitch-arrows {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 1px;
        color: #333333;

        .caret-up {
          color: #333333;
        }
        .caret-down {
          color: #999999;
        }
      }
    }

    &.info-block {
      background-color: #79a1eb;
      border: 1px solid #6b93db;
      transition: background-color 0.2s ease, border-color 0.2s ease;

      &.inactive-block {
        background-color: #bdc3c7 !important;
        border-color: #bdc3c7 !important;
        
        .info-btn {
          color: rgba(255, 255, 255, 0.75) !important;
          
          &:hover {
            background-color: rgba(0, 0, 0, 0.05) !important;
          }
        }
      }

      .info-btn {
        color: #ffffff;

        &:hover {
          background-color: #6b93db;
        }

        &.active {
          background-color: rgba(255, 255, 255, 0.15);
        }
      }
    }
  }
}

/* Line Width Popover Premium Styling */
.line-width-popover {
  position: absolute;
  right: 48px;
  top: 76px;
  width: 240px;
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: var(--app-panel-radius);
  box-shadow: var(--app-shadow-md);
  padding: var(--space-sm) var(--space-md);
  z-index: calc(var(--z-popover) - 1);
  display: flex;
  flex-direction: column;
  gap: 8px;
  box-sizing: border-box;

  .popover-title {
    font-size: 13px;
    font-weight: 600;
    color: #2c3e50;
    border-bottom: 1px solid rgba(0, 0, 0, 0.05);
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
        color: #7f8c8d;
        display: flex;
        justify-content: space-between;
        
        .val-text {
          font-family: monospace;
          color: #409eff;
          font-weight: bold;
        }
      }
      
      .el-slider {
        margin-top: 4px;
        --el-slider-main-bg-color: #409eff;
        --el-slider-runway-bg-color: #e4e7ed;
      }
    }

    .flow-control-row,
    .vehicle-visibility-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-top: 12px;
      padding-top: 10px;
      border-top: 1px solid rgba(0, 0, 0, 0.05);
      font-size: 11px;
      font-weight: 600;
      color: #7f8c8d;

      .el-select {
        width: 126px;
      }
    }
  }
}

/* Slide/fade transition for popover */
.popover-fade-enter-active,
.popover-fade-leave-active {
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
}
.popover-fade-enter-from,
.popover-fade-leave-to {
  opacity: 0;
  transform: translateX(10px);
}

/* Premium Collapsible Tabs & SVG styling */
.collapse-tab {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  width: 24px;
  height: 48px;
  background: #ffffff;
  border: 1px solid rgba(21, 105, 222, 0.15);
  box-shadow: 0 4px 12px rgba(15, 66, 125, 0.12);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  z-index: 10;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  color: #1569de;
  
  &:hover {
    background: #e8f2ff;
    color: #1050a8;
    box-shadow: 0 4px 16px rgba(15, 66, 125, 0.2);
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
  right: -24px;
  border-radius: 0 8px 8px 0;
  border-left: none;
}

.right-tab {
  left: -24px;
  border-radius: 8px 0 0 8px;
  border-right: none;
}

.segment-info-popover {
  position: fixed;
  width: 240px;
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid rgba(21, 105, 222, 0.2);
  border-radius: var(--app-panel-radius);
  box-shadow: 0 12px 30px rgba(15, 66, 125, 0.15), 0 4px 10px rgba(0, 0, 0, 0.05);
  padding: 14px 16px;
  z-index: var(--z-popover);
  pointer-events: auto;
  transform-origin: top left;
  transition: opacity 0.2s ease, transform 0.2s ease;
  scale: 0.8;

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
      color: #1569de;
      letter-spacing: 0.5px;
      font-family: "Outfit", "Inter", sans-serif;
    }

    .close-btn {
      background: none;
      border: none;
      color: #7f8c8d;
      font-size: 18px;
      cursor: pointer;
      padding: 0 4px;
      line-height: 1;
      transition: color 0.2s ease;

      &:hover {
        color: #e74c3c;
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
        color: #7f8c8d;
        font-weight: 500;
      }

      .val {
        color: #1a365d;
        font-weight: 700;
        font-family: "Outfit", "Inter", sans-serif;
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
    width: 400px;
    min-width: 400px;
  }

  .map-controls-toolbar.with-panel {
    right: 400px;
  }
}

@media (max-width: 960px) {
  .datebase_box {
    top: calc(var(--app-header-height) + var(--space-lg));
    right: var(--app-edge);
  }
}
</style>
