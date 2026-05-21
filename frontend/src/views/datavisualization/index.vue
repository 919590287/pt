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
          <el-button type="primary" :plain="activeTab != '数据总览'" @click="handleSetActiveTab('数据总览')">数据总览</el-button>
          <el-button type="primary" :plain="activeTab != '线路总览'" @click="handleSetActiveTab('线路总览')">线路总览</el-button>
          <el-button type="primary" :plain="activeTab != '站点总览'" @click="handleSetActiveTab('站点总览')">站点总览</el-button>
        </div>
        <el-scrollbar class="flex_column_scroll_box">
          <SJZL v-if="activeTab == '数据总览'" :model="selectModel.name" />
          <XLZL v-if="activeTab == '线路总览'" :model="selectModel.name" />
          <ZDZL v-if="activeTab == '站点总览'" :model="selectModel.name" />
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
          <button :class="['control-btn', showLineWidthPopover ? 'active' : '']" @click="handleToggleLineWidthPopover" title="线形设置">
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
            <div class="popover-title">线形设置</div>
            <div class="popover-content">
	              <div class="slider-row">
	                <span class="label">
	                  <span>线宽</span>
	                  <span class="val-text">{{ `${lineWidth}px` }}</span>
	                </span>
	                <el-slider v-model="lineWidth" :min="3" :max="40" :step="1" @input="handleLineWidthChange" />
	              </div>
              <div class="flow-control-row">
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
    <div v-else class="model_box box1" :style="box1Style">
      <el-empty description="模型加载中，请稍等...." />
    </div>
  </template>
  <div v-else class="model_box box1" :style="box1Style">
    <el-empty description="请选择模型" />
  </div>
</template>

<script setup>
import { getSchemeList, getModelList, loadModel } from "@/api/scheme.js";
import { dataCenter } from "@/api/data.js";

import SJZL from "./components/SJZL.vue";
import XLZL from "./components/XLZL.vue";
import ZDZL from "./components/ZDZL.vue";

import { useDraggable } from "@vueuse/core";
import { HighlightSegmentLayer } from "./layers/HighlightSegmentLayer.js";



const { style: box1Style } = useDraggable(useTemplateRef("box1"), {
  initialValue: { x: 16, y: 120 },
  handle: useTemplateRef("box1Handle"),
});

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
function handleSetActiveTab(tabName) {
  activeTab.value = tabName;
}

// Map Controls State & Logic
const mapZoom = ref(10.74);
const mapPitch = ref(90);
const mapRotation = ref(0);

const showSidebar = ref(true);
const showRightPanel = ref(true);
const isLeftCollapsed = ref(false);
const isRightCollapsed = ref(false);
const flowControl = ref(false);

const hasRightPanelContent = computed(() => activeTab.value === "数据总览");
const isRightPanelVisible = computed(() => showRightPanel.value && hasRightPanelContent.value);
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
const referenceZoom = ref(10.74);
let isZoomCaptured = false;

const lineWidthZoomScale = computed(() => Math.pow(2, 0.5 * (referenceZoom.value - mapZoom.value)));
const computedLineWidth = computed(() => {
  return Math.max(3, lineWidth.value * lineWidthZoomScale.value);
});
const computedFlowWidthStep = computed(() => 20 * lineWidthZoomScale.value);

provide("LineWidthRef", computedLineWidth);
provide("FlowWidthStepRef", computedFlowWidthStep);
provide("FlowControlRef", flowControl);

watch(computedLineWidth, (val) => {
  applyLineWidth();
  if (highlightLayer) {
    highlightLayer.setLineWidth(val * 1.5);
  }
});
watch(computedFlowWidthStep, () => {
  applyFlowWidthStep();
});

let zoomListenerId = null;
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

function handleLineWidthChange(val) {
  lineWidth.value = val;
  applyLineWidth();
  applyFlowWidthStep();
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
    if (rotateListenerId) {
      mapInstance.removeEventListener("update:camera:rotate", rotateListenerId);
    }
    if (clickListenerId) {
      mapInstance.removeEventListener("handle:click", clickListenerId);
      clickListenerId = null;
    }
    
    // Add new listeners
    zoomListenerId = mapInstance.addEventListener("update:zoom", (e) => {
      mapZoom.value = e.data;
    });
    rotateListenerId = mapInstance.addEventListener("update:camera:rotate", (e) => {
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
    
    applyLineWidth();
    applyFlowWidthStep();
    applyFlowControl();
  }
});

const ins = setInterval(() => {
  handleGetSchemeList();
  handleGetModelList();
}, 1000 * 20);

let syncLayersInterval = null;

handleGetSchemeList();

onMounted(() => {
  handleGetSchemeList()
    .then(() => {
      datebase.value.scheme = schemeList.value[0];
      return handleGetModelList();
    })
    .then(() => {
      datebase.value.model = modelList.value[0].name;
    });
    
  syncLayersInterval = setInterval(() => {
    applyLineWidth();
    applyFlowWidthStep();
    applyFlowControl();
  }, 1000);
});
onUnmounted(() => {
  sessionStorage.removeItem("request_params");
  clearInterval(ins);
  if (syncLayersInterval) {
    clearInterval(syncLayersInterval);
  }
  if (MapRef.value) {
    if (zoomListenerId) {
      MapRef.value.removeEventListener("update:zoom", zoomListenerId);
    }
    if (rotateListenerId) {
      MapRef.value.removeEventListener("update:camera:rotate", rotateListenerId);
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
  scale: 0.8;
}

.datebase_box {
  position: fixed;
  top: 25.6px;
  right: 56px;
  display: flex;
  align-items: center;
  transform: translateY(-50%);
  transform-origin: right center;
  z-index: 1000;
  .handle {
    cursor: default;
    margin-right: 10px;
    font-size: 16px;
    font-weight: 600;
    color: #1569de;
    text-shadow: none;
    white-space: nowrap;
  }
  .el-select {
    width: 155px;
    margin-right: 8px;
    
    :deep(.el-input__wrapper) {
      background-color: rgba(255, 255, 255, 0.8) !important;
      box-shadow: 0 0 0 1px rgba(21, 105, 222, 0.25) inset !important;
      backdrop-filter: blur(4px);
      border-radius: 6px;
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
  padding: 10px;
  position: fixed;
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: calc((100vh - 150px) / 0.8);
  min-width: 400px;
  transform-origin: top left;
  transition: transform 0.4s cubic-bezier(0.4, 0, 0.2, 1);
  
  &.collapsed {
    transform: translateX(calc(-100% - 30px)) !important;
  }
  
  .handle {
    cursor: move;
  }

  .tab_list {
    display: flex;
    background-color: #ffffff00;
    width: 100%;
    gap: 10px;
    .el-button {
      flex: 1;
      margin: 0;
    }
  }

  .scroll_box {
    height: 0 !important;
    flex: 1;
  }
}

.box2 {
  position: fixed;
  right: 16px;
  top: 67px;
  max-height: calc((100vh - 85px) / 0.8);
  display: flex;
  flex-direction: column;
  transform-origin: top right;
  transition: transform 0.4s cubic-bezier(0.4, 0, 0.2, 1);
  
  &.collapsed {
    transform: translateX(calc(100% + 30px)) !important;
  }
  
  #datavisualization_index_box2 {
    display: flex;
    flex-direction: column;
    align-items: flex-end;
    gap: 10px;
  }
}

.map-controls-toolbar {
  position: fixed;
  top: 67px;
  transition: right 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  display: flex;
  flex-direction: column;
  gap: 12px;
  z-index: 1000;
  transform-origin: top right;

  &.with-panel {
    right: 404px; /* (20px right + 470px panel width + 15px gap) * 0.8 */
  }

  &.without-panel {
    right: 16px;
  }

  .control-block {
    display: flex;
    flex-direction: column;
    background-color: #ffffff;
    border-radius: 8px;
    box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08), 0 2px 4px rgba(0, 0, 0, 0.04);
    border: 1px solid rgba(0, 0, 0, 0.06);
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
        background-color: #f5f7fa;
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
  right: 48px; /* 36px button + 12px gap */
  top: 76px; /* Align perfectly with Block 2 (sliders block) */
  width: 240px;
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: 12px;
  box-shadow: 0 10px 25px rgba(0, 0, 0, 0.08), 0 4px 12px rgba(0, 0, 0, 0.04);
  padding: 12px 16px;
  z-index: 1001;
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

    .flow-control-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      margin-top: 12px;
      padding-top: 10px;
      border-top: 1px solid rgba(0, 0, 0, 0.05);
      font-size: 11px;
      font-weight: 600;
      color: #7f8c8d;
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
  border-radius: 12px;
  box-shadow: 0 12px 30px rgba(15, 66, 125, 0.15), 0 4px 10px rgba(0, 0, 0, 0.05);
  padding: 14px 16px;
  z-index: 2000;
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
</style>
