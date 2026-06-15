<!-- index -->
<template>
  <Transition name="model-loading-fade">
    <div v-if="modelLoadingDialogVisible" class="model-loading-gate" role="status" aria-live="polite" aria-busy="true">
      <div class="model-loading-card" role="dialog" aria-modal="false" aria-label="模型后台加载提示">
        <button class="model-loading-close" type="button" title="关闭提示" aria-label="关闭模型加载提示" @click="dismissModelLoadingDialog">
          <el-icon><Close /></el-icon>
        </button>
        <div class="model-loading-title">模型后台加载</div>
        <div class="model-loading-message">{{ modelLoadingNotice }}</div>
      </div>
    </div>
  </Transition>

  <div class="datebase_box" role="search" aria-label="方案与模型选择">
    <label class="handle" for="scheme-selector">当前方案</label>
    <el-select id="scheme-selector" v-model="datebase.scheme" clearable filterable :loading="isLoadingSchemes" aria-label="当前方案">
      <el-option v-for="item in schemeList" :key="item" :label="item" :value="item"> </el-option>
    </el-select>
    <el-select class="model-select" v-model="modelPickerValue" :disabled="!datebase.scheme || isLoadingModels" clearable filterable :loading="isLoadingModels" aria-label="选择模型" @change="handleModelPick">
      <el-option v-for="item in modelList" :key="item.name" :label="getModelLabel(item)" :value="item.name">
        <div class="model-option">
          <div class="model-option-main">
            <span>{{ getModelLabel(item) }}</span>
            <el-tag v-if="item.scopeLabel" type="info">{{ item.scopeLabel }}</el-tag>
            <el-tag :type="modelLoadTagType(item)">{{ modelLoadLabel(item) }}</el-tag>
            <el-tag v-if="item.cacheStatus" :type="modelCacheTagType(item)">{{ modelCacheLabel(item) }}</el-tag>
          </div>
          <div class="model-option-actions">
            <el-button v-if="canStartModel(item)" link size="small" type="primary" :disabled="isModelOperationBusy" @mousedown.stop.prevent @click.stop.prevent="handleBackgroundLoad(item)">
              <el-icon><VideoPlay /></el-icon>
              <span>后台加载</span>
            </el-button>
            <el-button v-if="item.loadStatus || item.loadStage === 'loading_config' || item.loadStage === 'queued'" link size="small" type="danger" :disabled="isModelOperationBusy" @mousedown.stop.prevent @click.stop.prevent="handleUnloadModel(item)">
              <el-icon><Remove /></el-icon>
              <span>卸载</span>
            </el-button>
          </div>
        </div>
      </el-option>
    </el-select>
    <span v-if="loadError" class="load-error" role="status">{{ loadError }}</span>
  </div>

  <div v-if="backgroundTaskVisible" class="model-background-status" role="status" aria-live="polite">
    <div class="model-background-main">
      <span class="model-background-dot"></span>
      <span class="model-background-title">{{ backgroundTaskTitle }}</span>
      <span class="model-background-message">{{ backgroundTaskMessage }}</span>
    </div>
    <el-progress class="model-background-progress" :percentage="modelProgressPercent(backgroundTaskModel)" :show-text="false" :stroke-width="6" />
    <el-button v-if="backgroundSwitchOnReady" link size="small" @click="cancelPendingAutoSwitch">
      <el-icon><SwitchButton /></el-icon>
      <span>取消切换</span>
    </el-button>
  </div>

  <template v-if="selectModel">
    <template v-if="isModelReady">
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
        <div class="tab_list" ref="box1Handle">
          <el-button type="primary" :plain="activeTab != '数据总览'" @pointerdown.stop @click="handleSetActiveTab('数据总览')">数据总览</el-button>
          <el-button type="primary" :plain="activeTab != '轨迹演示'" @pointerdown.stop @click="handleSetActiveTab('轨迹演示')">轨迹演示</el-button>
          <el-button type="primary" :plain="activeTab != '出行分析'" @pointerdown.stop @click="handleSetActiveTab('出行分析')">出行分析</el-button>
        </div>
        <el-scrollbar class="flex_column_scroll_box">
          <SJZL v-if="activeTab == '数据总览'" :key="`sjzl-${selectModel.name}`" :model="selectModel.name" />
          <GJYS v-else-if="activeTab == '轨迹演示'" :key="`gjys-${selectModel.name}`" :model="selectModel.name" />
          <CXZFX v-else-if="activeTab == '出行分析'" :key="`cxzfx-${selectModel.name}`" :model="selectModel.name" />
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
            :title="effectiveTab === '轨迹演示' ? '车辆模型设置' : '线形设置'"
            :aria-label="effectiveTab === '轨迹演示' ? '打开车辆模型设置' : '打开线形设置'"
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
            <div class="popover-title">{{ effectiveTab === '轨迹演示' ? '车辆模型设置' : '线形设置' }}</div>
            <div class="popover-content">
              <div class="slider-row" v-if="effectiveTab === '轨迹演示'">
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
              <div class="flow-control-row" v-else>
                <span>按流量控制</span>
                <el-switch v-model="flowControl" @change="handleFlowControlChange" />
              </div>
            </div>
          </div>
        </Transition>
      </div>
    </template>
    <div v-else ref="box1" class="model_box box1 cache-loading-panel" :style="box1Style">
      <div class="cache-loading-title">{{ cacheLoadingTitle }}</div>
      <div class="cache-loading-message">{{ cacheLoadingMessage }}</div>
      <el-progress
        class="cache-loading-progress"
        :percentage="cacheProgressPercent"
        :status="selectModel.cacheStatus === 'failed' || selectModel.loadStage === 'failed' ? 'exception' : undefined"
        :stroke-width="12"
      />
      <div class="cache-loading-meta">
        <span>已用 {{ formatDuration(cacheElapsedSeconds) }}</span>
        <span>预计剩余 {{ formatDuration(cacheEtaSeconds) }}</span>
      </div>
    </div>
  </template>
  <div v-else ref="box1" class="model_box box1" :style="box1Style">
    <el-empty description="请选择模型" />
  </div>
</template>

<script setup>
import { defineAsyncComponent } from "vue";
import { Close, Remove, SwitchButton, VideoPlay } from "@element-plus/icons-vue";
import { ElMessage, ElMessageBox } from "@/plugins/element-plus";
import { getSchemeList, getModelList, loadModel, unloadModel } from "@/api/scheme.js";
import { dataCenter } from "@/api/data.js";
import { useModelSelectionStore } from "@/stores/modelSelection.js";

import SJZL from "./components/SJZL.vue";

import { useDraggable } from "@vueuse/core";

const GJYS = defineAsyncComponent(() => import("./components/GJYS.vue"));
const CXZFX = defineAsyncComponent(() => import("./components/CXZFX.vue"));

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

const MODEL_SELECTION_KEY = "datavisualization";
const modelSelectionStore = useModelSelectionStore();
const restoredSelection = modelSelectionStore.getSelection(MODEL_SELECTION_KEY);
let isRestoringSelection = Boolean(restoredSelection.scheme);
const datebase = ref({
  scheme: restoredSelection.scheme,
  model: restoredSelection.model,
});
const modelPickerValue = ref(restoredSelection.model);
const schemeList = ref([]);
const modelList = ref([]);
const isLoadingSchemes = ref(false);
const isLoadingModels = ref(false);
const isModelOperationBusy = ref(false);
const loadError = ref("");
const initialModelBootstrap = ref(true);
const backgroundModelName = ref("");
const backgroundSwitchOnReady = ref(false);
const modelLoadingDismissed = ref(false);
let schemeRequestSeq = 0;
let modelRequestSeq = 0;
let centerRequestSeq = 0;
let modelLoadSeq = 0;
let backgroundTaskSeq = 0;
const selectModel = computed(() => {
  const item = modelList.value?.find((item) => item.name === datebase.value.model);

  return item;
});
const backgroundTaskModel = computed(() => modelList.value?.find((item) => item.name === backgroundModelName.value));
const isModelReadyForView = (item) => Boolean(item?.loadStatus && item?.cacheStatus === "ready");
const isModelReady = computed(() => isModelReadyForView(selectModel.value));
const backgroundTaskVisible = computed(() => Boolean(
  backgroundTaskModel.value
  && backgroundTaskModel.value.name !== datebase.value.model
  && !isModelReadyForView(backgroundTaskModel.value),
));
const fullScreenLoadingVisible = computed(() => (
  !isModelReady.value
  && (initialModelBootstrap.value || isLoadingSchemes.value || isLoadingModels.value || Boolean(selectModel.value))
));
const modelLoadingDialogVisible = computed(() => fullScreenLoadingVisible.value && !modelLoadingDismissed.value);
const modelLoadingNotice = computed(() => {
  if (!selectModel.value) return "模型状态正在检查，请稍后。";
  return `“${getModelLabel(selectModel.value)}”开始后台加载，请稍后。`;
});
const modelLoadingKey = computed(() => `${datebase.value.scheme || ""}:${selectModel.value?.name || ""}:${selectModel.value?.loadStage || ""}:${selectModel.value?.cacheStatus || ""}`);
const cacheProgressPercent = computed(() => modelProgressPercent(selectModel.value));
const backgroundTaskTitle = computed(() => {
  const item = backgroundTaskModel.value;
  if (!item) return "";
  const prefix = backgroundSwitchOnReady.value ? "加载完成后切换" : "后台加载";
  return `${prefix}：${getModelLabel(item)}`;
});
const backgroundTaskMessage = computed(() => modelProgressMessage(backgroundTaskModel.value));

function modelProgressPercent(item) {
  if (isModelReadyForView(item)) return 100;
  if (item?.loadStage === "loading_config") return item?.cacheStatus === "ready" ? 35 : 12;
  if (item?.loadStage === "queued") return 8;
  const value = Number(item?.cacheProgressPercent);
  if (Number.isFinite(value)) return Math.max(0, Math.min(100, Math.round(value)));
  if (item?.cacheStatus === "ready") return 85;
  if (item?.loadStatus) return 20;
  return 5;
}

function modelProgressMessage(item) {
  return item?.cacheProgressMessage
    || item?.cacheMessage
    || item?.loadMessage
    || "模型准备中";
}
const cacheElapsedSeconds = computed(() => Math.max(0, Number(selectModel.value?.cacheElapsedSeconds) || 0));
const cacheEtaSeconds = computed(() => Number(selectModel.value?.cacheEtaSeconds ?? -1));
const cacheLoadingTitle = computed(() => {
  if (!selectModel.value) return "正在检查模型缓存";
  if (selectModel.value?.loadStage === "failed" || selectModel.value?.cacheStatus === "failed") return "加载失败";
  if (!selectModel.value?.loadStatus) return "正在加载模型基础数据";
  return "正在生成模型缓存";
});
const cacheLoadingMessage = computed(() => (
  (!selectModel.value && isLoadingSchemes.value ? "正在读取可用方案" : "")
  || (!selectModel.value && isLoadingModels.value ? "正在读取模型列表" : "")
  || selectModel.value?.cacheProgressMessage
  || selectModel.value?.cacheMessage
  || selectModel.value?.loadMessage
  || "模型准备中，请稍等"
));

function formatDuration(seconds) {
  const value = Number(seconds);
  if (!Number.isFinite(value) || value < 0) return "计算中";
  const total = Math.round(value);
  if (total < 60) return `${total} 秒`;
  const minutes = Math.floor(total / 60);
  const rest = total % 60;
  if (minutes < 60) return rest > 0 ? `${minutes} 分 ${rest} 秒` : `${minutes} 分`;
  const hours = Math.floor(minutes / 60);
  const minuteRest = minutes % 60;
  return minuteRest > 0 ? `${hours} 小时 ${minuteRest} 分` : `${hours} 小时`;
}

function getModelLabel(item) {
  return item?.displayName || item?.name || "";
}

function modelLoadLabel(item) {
  if (item?.loadStatus) return "已加载";
  if (item?.loadStage === "queued") return "排队中";
  if (item?.loadStage === "loading_config") return "加载中";
  if (item?.loadStage === "failed") return "加载失败";
  return "未加载";
}

function modelLoadTagType(item) {
  if (item?.loadStatus) return "success";
  if (item?.loadStage === "failed") return "danger";
  if (item?.loadStage === "queued" || item?.loadStage === "loading_config") return "info";
  return "warning";
}

function modelCacheLabel(item) {
  if (item?.cacheStatus === "ready") return "缓存就绪";
  if (item?.cacheStatus === "queued") return "缓存排队";
  if (item?.cacheStatus === "building") return "缓存生成";
  if (item?.cacheStatus === "failed") return "缓存失败";
  return "缓存待生成";
}

function modelCacheTagType(item) {
  if (item?.cacheStatus === "ready") return "success";
  if (item?.cacheStatus === "failed") return "danger";
  if (item?.cacheStatus === "queued" || item?.cacheStatus === "building") return "info";
  return "warning";
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function setActiveModel(name = "") {
  datebase.value.model = name;
  modelPickerValue.value = name;
}

function dismissModelLoadingDialog() {
  modelLoadingDismissed.value = true;
}

function canStartModel(item) {
  if (!item || isModelReadyForView(item)) return false;
  return item.loadStage !== "queued" && item.loadStage !== "loading_config" && item.cacheStatus !== "queued" && item.cacheStatus !== "building";
}

function pickReadyModel(list, excludedName = "") {
  return (Array.isArray(list) ? list : []).find((item) => item.name !== excludedName && isModelReadyForView(item)) || null;
}

function clearBackgroundTask(name = "") {
  if (!name || backgroundModelName.value === name) {
    backgroundModelName.value = "";
    backgroundSwitchOnReady.value = false;
  }
}

async function handleModelPick(name) {
  if (!name) {
    setActiveModel("");
    return;
  }
  const item = modelList.value.find((model) => model.name === name);
  if (!item) return;
  if (isModelReadyForView(item)) {
    setActiveModel(name);
    clearBackgroundTask(name);
    return;
  }
  modelPickerValue.value = datebase.value.model;
  await startBackgroundModelLoad(item, true);
}

async function handleBackgroundLoad(item) {
  modelPickerValue.value = datebase.value.model;
  await startBackgroundModelLoad(item, false);
}

async function startBackgroundModelLoad(item, switchOnReady) {
  if (!item?.name) return;
  if (isModelReadyForView(item)) {
    if (switchOnReady) setActiveModel(item.name);
    return;
  }
  const seq = ++backgroundTaskSeq;
  backgroundModelName.value = item.name;
  backgroundSwitchOnReady.value = switchOnReady;
  isModelOperationBusy.value = true;
  loadError.value = "";
  try {
    await loadModel({ name: item.name }, { silentError: true });
  } catch (error) {
    if (seq === backgroundTaskSeq) {
      clearBackgroundTask(item.name);
      loadError.value = error?.message || "模型后台加载失败";
    }
    return;
  } finally {
    if (seq === backgroundTaskSeq) {
      isModelOperationBusy.value = false;
    }
  }

  try {
    const readyItem = await waitForModelReady(item.name, () => seq === backgroundTaskSeq && backgroundModelName.value === item.name, { publishProgress: false });
    if (!readyItem || seq !== backgroundTaskSeq) return;
    if (backgroundSwitchOnReady.value) {
      setActiveModel(item.name);
      ElMessage.success("模型已加载完成，已切换");
    } else {
      ElMessage.success("模型已在后台加载完成");
    }
    clearBackgroundTask(item.name);
  } catch (error) {
    if (seq === backgroundTaskSeq) {
      loadError.value = error?.message || "模型后台加载失败";
    }
  }
}

function cancelPendingAutoSwitch() {
  backgroundSwitchOnReady.value = false;
}

async function handleUnloadModel(item) {
  if (!item?.name) return;
  const isActive = item.name === datebase.value.model;
  try {
    await ElMessageBox.confirm(
      isActive ? "卸载当前模型后，将自动切换到其他已就绪模型；如果没有可用模型，当前页面会进入待选择状态。" : `确定卸载“${getModelLabel(item)}”吗？`,
      "卸载模型",
      { confirmButtonText: "卸载", cancelButtonText: "取消", type: "warning" },
    );
  } catch {
    return;
  }
  isModelOperationBusy.value = true;
  try {
    await unloadModel({ name: item.name }, { silentError: true });
    const list = await handleGetModelList({ silent: true });
    if (isActive) {
      const fallback = pickReadyModel(list, item.name);
      setActiveModel(fallback?.name || "");
    }
    clearBackgroundTask(item.name);
    ElMessage.success("模型已卸载");
  } catch (error) {
    loadError.value = error?.message || "模型卸载失败";
  } finally {
    isModelOperationBusy.value = false;
  }
}

watch(
  () => datebase.value.scheme,
  async (scheme) => {
    const restoringModel = isRestoringSelection && scheme === restoredSelection.scheme ? restoredSelection.model : "";
    setActiveModel("");
    clearBackgroundTask();
    modelList.value = [];
    if (!scheme) return;
    const list = await handleGetModelList();
    if (list.length && !datebase.value.model) {
      const preferred = list.find((item) => item.name === restoringModel) || pickReadyModel(list) || list[0];
      setActiveModel(preferred.name);
    }
    isRestoringSelection = false;
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
  ensureSelectedModelReady,
);
watch(
  [() => datebase.value.scheme, () => datebase.value.model],
  () => {
    modelSelectionStore.setSelection(MODEL_SELECTION_KEY, {
      scheme: datebase.value.scheme,
      model: datebase.value.model,
    });
  },
);
watch(modelLoadingKey, () => {
  modelLoadingDismissed.value = false;
});

async function ensureSelectedModelReady() {
  modelPickerValue.value = datebase.value.model || "";
  if (!datebase.value.model) return;
  const seq = ++modelLoadSeq;
  try {
    if (selectModel.value && !isModelReady.value) {
      await loadModel({ name: datebase.value.model });
      await waitForModelReady(datebase.value.model, () => seq === modelLoadSeq && datebase.value.model === selectModel.value?.name);
    }
  } catch (error) {
    loadError.value = error?.message || "模型加载失败，请稍后重试";
  } finally {
    if (seq === modelLoadSeq && isModelReady.value) {
      setMapCenter();
    }
  }
}
const MapRef = inject("MapRef");
watch(MapRef, setMapCenter);
async function setMapCenter() {
  const seq = ++centerRequestSeq;
  if (selectModel.value && selectModel.value.name && isModelReady.value) {
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

async function waitForModelReady(modelName, shouldContinue, options = {}) {
  const { publishProgress = true } = options;
  for (let i = 0; i < 21600; i++) {
    if (!shouldContinue()) return null;
    const list = await handleGetModelList({ silent: true });
    const item = list.find((model) => model.name === modelName);
    if (!item) return null;
    if (item.loadStatus && item.cacheStatus === "ready") {
      loadError.value = "";
      return item;
    }
    if (item.loadStage === "failed") {
      throw new Error(item.loadMessage || "模型加载失败");
    }
    if (item.cacheStatus === "failed") {
      throw new Error(item.cacheMessage || "缓存生成失败");
    }
    if (publishProgress) {
      loadError.value = item.cacheProgressMessage || item.cacheMessage || item.loadMessage || "模型正在后台准备";
    }
    await sleep(1000);
  }
  throw new Error("模型缓存仍在后台生成，请稍后查看");
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
      const fallback = pickReadyModel(list);
      setActiveModel(fallback?.name || "");
    }
    if (backgroundModelName.value && !list.some((item) => item.name === backgroundModelName.value)) {
      clearBackgroundTask(backgroundModelName.value);
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

const activeTab = ref("数据总览");

const effectiveTab = computed(() => activeTab.value);

const rightPanelWidth = 470;

function handleSetActiveTab(tabName) {
  activeTab.value = tabName;
}
provide("activeDatavisualizationTab", effectiveTab);

const rightPanelHasContent = ref(true);
provide("rightPanelHasContent", rightPanelHasContent);

function tabHasPersistentRightPanel(tab = effectiveTab.value) {
  return tab === "数据总览" || tab === "出行分析";
}

function syncPersistentRightPanel(tab = effectiveTab.value) {
  if (!tabHasPersistentRightPanel(tab)) return;
  rightPanelHasContent.value = true;
  showRightPanel.value = true;
  isRightCollapsed.value = false;
}

watch(effectiveTab, (tab) => {
  rightPanelHasContent.value = tabHasPersistentRightPanel(tab);
  syncPersistentRightPanel(tab);
  lineWidth.value = 20;
  applyLineWidth();
  applyStationSize();
  scheduleLayerSyncBurst(4);
  observeLeftPanelSize();
});

watch(
  [() => selectModel.value?.name, isModelReady],
  () => {
    if (isModelReady.value) {
      nextTick(() => syncPersistentRightPanel());
    }
  },
  { flush: "post" },
);

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

const showLineWidthPopover = ref(false);
const lineWidth = ref(20);
const stationSize = ref(22);
const vehicleSize = ref(36);
const referenceZoom = ref(10.74);
let isZoomCaptured = false;

const minLineWidth = computed(() => 3);
const maxLineWidth = computed(() => 40);
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
const isPerfProbeEnabled =
  import.meta.env.DEV || (typeof window !== "undefined" && window.__GJ_ENABLE_PERF_PROBE__ === true);

function publishPerfProbe(fps = 0, now = performance.now()) {
  if (!isPerfProbeEnabled) return;
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
  if (!isPerfProbeEnabled) return;
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
let clickListenerId = null;
let resizeTimerId = null;

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
      scheduleMapResize();
      scheduleMapResize(450);
    }
  }
);

const ins = setInterval(() => {
  handleGetSchemeList({ silent: true });
  handleGetModelList({ silent: true });
}, 1000 * 20);

async function handleAuthChanged() {
  datebase.value.scheme = "";
  setActiveModel("");
  clearBackgroundTask();
  schemeList.value = [];
  modelList.value = [];
  await handleGetSchemeList({ autoSelect: true });
}

onMounted(() => {
  if (isPerfProbeEnabled) {
    startPerfProbe();
  }
  observeLeftPanelSize();
  window.addEventListener("resize", centerLeftPanel);
  window.addEventListener("auth:changed", handleAuthChanged);
  document.addEventListener("keydown", handleDocumentKeydown);
  handleGetSchemeList({ autoSelect: true }).then(async () => {
    if (datebase.value.scheme && !modelList.value.length) {
      const list = await handleGetModelList();
      if (list.length && (!datebase.value.model || !list.some((item) => item.name === datebase.value.model))) {
        const preferred = list.find((item) => item.name === restoredSelection.model) || pickReadyModel(list) || list[0];
        setActiveModel(preferred.name);
      }
      await ensureSelectedModelReady();
    }
  }).finally(() => {
    initialModelBootstrap.value = false;
    isRestoringSelection = false;
    observeLeftPanelSize();
  });

  scheduleLayerSyncBurst(8);
});
onUnmounted(() => {
  modelLoadSeq++;
  backgroundTaskSeq++;
  stopPerfProbe();
  leftPanelResizeObserver?.disconnect();
  leftPanelResizeObserver = null;
  window.removeEventListener("resize", centerLeftPanel);
  window.removeEventListener("auth:changed", handleAuthChanged);
  document.removeEventListener("keydown", handleDocumentKeydown);
  sessionStorage.removeItem("request_params");
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
    width: 100%;

    .model-option-main {
      display: flex;
      align-items: center;
      gap: var(--space-sm);
      min-width: 0;
      flex: 1;

      span:first-child {
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }

    .model-option-actions {
      display: flex;
      align-items: center;
      flex-shrink: 0;
      gap: 2px;
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

.model-background-status {
  position: fixed;
  top: calc(var(--app-header-height) + 8px);
  right: calc(var(--app-edge) + 64px);
  z-index: calc(var(--z-header) + 9);
  display: grid;
  grid-template-columns: minmax(0, 1fr) 110px auto;
  align-items: center;
  gap: var(--space-sm);
  width: min(46vw, 520px);
  min-width: 360px;
  padding: 8px 10px;
  scale: var(--app-panel-scale);
  transform-origin: right top;
  border: 1px solid rgba(21, 105, 222, 0.16);
  border-radius: var(--app-panel-radius);
  background: rgba(251, 253, 255, 0.94);
  box-shadow: 0 10px 30px rgba(31, 45, 61, 0.12);
  color: var(--app-ink);

  .model-background-main {
    display: flex;
    align-items: center;
    gap: var(--space-xs);
    min-width: 0;
  }

  .model-background-dot {
    width: 8px;
    height: 8px;
    flex-shrink: 0;
    border-radius: 50%;
    background: var(--app-blue);
    box-shadow: 0 0 0 4px rgba(21, 105, 222, 0.12);
  }

  .model-background-title,
  .model-background-message {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .model-background-title {
    font-size: 12px;
    font-weight: 760;
  }

  .model-background-message {
    color: var(--app-muted);
    font-size: 12px;
  }

  .model-background-progress {
    width: 110px;
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
  cursor: default;
  user-select: text;
  transform-origin: top left;
  transition: transform var(--app-motion-slow) var(--app-ease-out);
  
  &.collapsed {
    transform: translateX(-100%) !important;
    pointer-events: none;

    .collapse-tab {
      pointer-events: auto;
    }
  }
  
  .tab_list,
  .handle {
    cursor: grab;
    user-select: none;

    &:active {
      cursor: grabbing;
    }
  }

  &.cache-loading-panel {
    justify-content: center;
    gap: var(--space-md);
    padding: var(--space-lg);
    background: rgba(251, 253, 255, 0.94);
    border: 1px solid var(--app-border-strong);
    border-radius: var(--app-panel-radius);
    box-shadow: var(--app-shadow-panel);
  }

  .cache-loading-title {
    color: var(--app-ink);
    font-size: 18px;
    font-weight: 760;
  }

  .cache-loading-message {
    color: var(--app-muted);
    font-size: 13px;
    line-height: 1.5;
    word-break: break-word;
  }

  .cache-loading-progress {
    width: 100%;
  }

  .cache-loading-meta {
    display: flex;
    justify-content: space-between;
    gap: var(--space-md);
    color: var(--app-muted);
    font-size: 12px;
  }

  .tab_list {
    display: flex;
    align-items: center;
    background-color: rgba(21, 105, 222, 0.06);
    border: 1px solid rgba(21, 105, 222, 0.14);
    border-radius: var(--app-card-radius);
    width: 100%;
    gap: var(--space-xs);
    padding: var(--space-2xs);
    .el-button {
      flex: 1;
      margin: 0;
      min-height: 36px;
      border-radius: 4px;
      min-width: 0;
      font-weight: 700;
      white-space: normal;
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

    .flow-control-row,
    .vehicle-visibility-row {
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

      .el-select {
        width: 126px;
      }
    }
  }
}

/* Slide/fade transition for popover */
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

  .model-background-status {
    right: calc(var(--app-edge) + 36px);
    width: 52vw;
    min-width: 320px;
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

  .model-background-status {
    top: calc(var(--app-header-height) + 76px);
    right: var(--app-edge);
    grid-template-columns: minmax(0, 1fr);
    width: calc(100vw - (var(--app-edge) * 2));
    min-width: 0;
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

  .model-background-status {
    left: var(--app-edge);
    right: var(--app-edge);
    width: auto;
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
