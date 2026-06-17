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
      <div :class="['dm-sidebar', isRunMonitorLeftCollapsed ? 'is-collapsed' : '']">
        <div class="sidebar-brand">
          <svg class="brand-icon" viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M3 12h4l3-8 4 16 3-8h4"></path>
          </svg>
          <span class="brand-text">运行监测</span>
        </div>

        <nav class="sidebar-nav" aria-label="运行监测导航">
          <div v-for="item in runMonitorMenuItems" :key="item.key" class="menu-group">
            <button
              type="button"
              :class="['nav-item', activeTab === item.key ? 'active' : '']"
              @click="handleSetActiveTab(item.key)"
            >
              <span class="nav-icon" v-html="item.icon"></span>
              <span class="nav-label">{{ item.label }}</span>
            </button>
          </div>
        </nav>

        <div v-show="activeTab === '车辆运行监测'" id="run-monitor-vehicle-controls" class="rm-vehicle-controls"></div>
        <div class="sidebar-footer"></div>
      </div>

      <button
        type="button"
        :class="['dm-panel-collapse-tab', 'dm-left-collapse-tab', isRunMonitorLeftCollapsed ? 'is-collapsed' : '']"
        :title="isRunMonitorLeftCollapsed ? '展开运行监测面板' : '收起运行监测面板'"
        :aria-label="isRunMonitorLeftCollapsed ? '展开运行监测面板' : '收起运行监测面板'"
        :aria-pressed="isRunMonitorLeftCollapsed"
        @click="isRunMonitorLeftCollapsed = !isRunMonitorLeftCollapsed"
      >
        <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <polyline points="15 18 9 12 15 6"></polyline>
        </svg>
      </button>

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
          <XLZL v-else-if="activeTab == '线路客流监测'" ref="lineMonitorRef" :key="`xlzl-${selectModel.name}`" :model="selectModel.name" />
          <ZDZL v-else-if="activeTab == '站点客流监测'" ref="stationMonitorRef" :key="`zdzl-${selectModel.name}`" :model="selectModel.name" />
          <GJYS
            v-else-if="activeTab == '轨迹演示' || activeTab == '车辆运行监测'"
            :key="`gjys-${selectModel.name}`"
            :model="selectModel.name"
            :run-monitor-panels="activeTab == '车辆运行监测'"
          />
          <CXZFX v-else-if="activeTab == '出行分析'" :key="`cxzfx-${selectModel.name}`" :model="selectModel.name" />
        </el-scrollbar>
      </div>
      <button
        v-show="isRightPanelVisible"
        type="button"
        :class="['dm-panel-collapse-tab', 'dm-right-collapse-tab', isRightCollapsed ? 'is-collapsed' : '']"
        @click="toggleRightPanel"
        :aria-label="isRightCollapsed ? '展开右侧信息面板' : '折叠右侧信息面板'"
        :aria-expanded="!isRightCollapsed"
        aria-controls="right-info-panel"
        :title="isRightCollapsed ? '展开面板' : '折叠面板'"
      >
        <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <polyline points="9 18 15 12 9 6"></polyline>
        </svg>
      </button>

      <div id="right-info-panel" :class="['dm-overview-panel', 'run-monitor-right-panel', isRightCollapsed ? 'is-collapsed' : '']" v-show="isRightPanelVisible">
        <el-scrollbar class="flex_column_scroll_box">
          <div id="datavisualization_index_box2">
            <div v-if="activeTab === '总体客流变化'" class="rm-right-card overall-flow-card">
              <div class="rm-right-card-title">
                <div>
                  <p class="rm-panel-kicker">总体客流</p>
                  <h2>一天总客流变化</h2>
                </div>
                <el-tag v-if="overallFlowLoading" type="info">加载中</el-tag>
                <el-tag v-else-if="overallFlowError" type="danger">加载失败</el-tag>
              </div>
              <div class="rm-overall-summary">
                <div class="rm-summary-item">
                  <span>总客流</span>
                  <strong>{{ formatOverallFlow(overallFlowTotal) }}</strong>
                </div>
              </div>
              <div v-if="overallFlowError" class="rm-panel-error">{{ overallFlowError }}</div>
              <template v-else>
                <div class="rm-overall-chart">
                  <el-auto-resizer class="chart_box">
                    <template #default="{ height, width }">
                      <VChart
                        v-if="width > 0 && height > 0"
                        class="rm-chart"
                        :option="overallFlowChartOption"
                        autoresize
                        :update-options="{ notMerge: true }"
                      />
                    </template>
                  </el-auto-resizer>
                </div>
                <div class="hourly-ranking-panel ranking-panel">
                  <div class="ranking-title-text">小时客流排行</div>
                  <div class="ranking-header">
                    <span class="col-rank">排序</span>
                    <span class="col-name">小时</span>
                    <span class="col-flow">客流量</span>
                  </div>
                  <div class="ranking-scroll-list">
                    <div v-for="(item, index) in overallFlowRankingRows" :key="item.hour" class="ranking-row">
                      <div class="col-rank">
                        <span :class="['rank-badge', index === 0 ? 'gold' : index === 1 ? 'silver' : index === 2 ? 'bronze' : '']">
                          {{ index + 1 }}
                        </span>
                      </div>
                      <div class="col-name">
                        <span class="route-name-text">{{ item.label }}</span>
                      </div>
                      <div class="col-flow">
                        <span class="flow-value">{{ item.valueText }}</span>
                        <span class="flow-unit">人次</span>
                      </div>
                    </div>
                  </div>
                </div>
              </template>
            </div>

            <div v-else-if="activeTab === '线路客流监测' && selectedLinePanel" class="rm-right-card line-flow-card">
              <div class="rm-right-card-title">
                <div>
                  <p class="rm-panel-kicker">线路客流</p>
                  <h2>{{ selectedLineName || '线路客流量' }}</h2>
                </div>
              </div>
              <div class="rm-overall-summary">
                <div class="rm-summary-item">
                  <span>日客流量</span>
                  <strong>{{ formatOverallFlow(lineFlowTotal) }}</strong>
                </div>
                <div class="rm-summary-item">
                  <span>峰值小时</span>
                  <strong>{{ lineFlowPeak.label }}</strong>
                </div>
              </div>
              <div class="rm-overall-chart">
                <el-auto-resizer class="chart_box">
                  <template #default="{ height, width }">
                    <VChart
                      v-if="width > 0 && height > 0"
                      class="rm-chart"
                      :option="lineFlowChartOption"
                      autoresize
                      :update-options="{ notMerge: true }"
                    />
                  </template>
                </el-auto-resizer>
              </div>
            </div>
          </div>
        </el-scrollbar>
      </div>

      <!-- Floating Map Controls Toolbar -->
      <div 
        :class="['map-controls-toolbar', (isRightPanelVisible && !isRightCollapsed) ? 'with-panel' : 'without-panel']"
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
            :title="isVehicleMonitorTab ? '车辆模型设置' : '线形设置'"
            :aria-label="isVehicleMonitorTab ? '打开车辆模型设置' : '打开线形设置'"
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
            <div class="popover-title">{{ isVehicleMonitorTab ? '车辆模型设置' : '线形设置' }}</div>
            <div class="popover-content">
              <div class="slider-row" v-if="isVehicleMonitorTab">
                <span class="label">
                  <span>车辆模型</span>
                  <span class="val-text">{{ `${vehicleSize}px` }}</span>
                </span>
                <el-slider v-model="vehicleSize" :min="minVehicleSize" :max="maxVehicleSize" :step="1" @input="handleVehicleSizeChange" />
              </div>
              <template v-else>
                <div class="layer-mode-row">
                  <span>显示图层</span>
                  <div class="layer-mode-segment" role="group" aria-label="线网显示图层">
                    <button
                      type="button"
                      :class="{ active: baseMapLineMode === 'bus-network' }"
                      @click="handleBaseMapLineModeChange('bus-network')"
                    >
                      公交线网
                    </button>
                    <button
                      type="button"
                      :class="{ active: baseMapLineMode === 'road-network' }"
                      @click="handleBaseMapLineModeChange('road-network')"
                    >
                      路网
                    </button>
                  </div>
                </div>
                <div class="slider-row">
                  <span class="label">
                    <span>线宽</span>
                    <span class="val-text">{{ `${lineWidth}px` }}</span>
                  </span>
                  <el-slider v-model="lineWidth" :min="minLineWidth" :max="maxLineWidth" :step="1" @input="handleLineWidthChange" />
                </div>
              </template>
              <div class="vehicle-visibility-row" v-if="isVehicleMonitorTab">
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
              <div class="slider-row" v-else-if="baseMapLineMode === 'bus-network'">
                <span class="label">
                  <span>站点大小</span>
                  <span class="val-text">{{ `${stationSize}px` }}</span>
                </span>
                <el-slider v-model="stationSize" :min="minStationSize" :max="maxStationSize" :step="1" @input="handleStationSizeChange" />
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
import { defineAsyncComponent, getCurrentInstance } from "vue";
import { Close, Remove, SwitchButton, VideoPlay } from "@element-plus/icons-vue";
import { ElMessage, ElMessageBox } from "@/plugins/element-plus";
import { getSchemeList, getModelList, loadModel, unloadModel } from "@/api/scheme.js";
import { dataCenter } from "@/api/data.js";
import { getRoutePanel } from "@/api/route.js";
import { getCachedRealData } from "@/utils/realDataCache.js";
import { useModelSelectionStore } from "@/stores/modelSelection.js";
import { NetworkLayer } from "./layers/NetworkLayer.js";
import busStationIconUrl from "@/assets/images/datamanagement/bus-station.svg?url";
import busStationHighlightIconUrl from "@/assets/images/datamanagement/bus-station_highlight.svg?url";
import "../datamanagement/tokens.css";

import SJZL from "./components/SJZL.vue";

import { useDraggable } from "@vueuse/core";

const GJYS = defineAsyncComponent(() => import("./components/GJYS.vue"));
const CXZFX = defineAsyncComponent(() => import("./components/CXZFX.vue"));
const XLZL = defineAsyncComponent(() => import("./components/XLZL.vue"));
const ZDZL = defineAsyncComponent(() => import("./components/ZDZL.vue"));

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

const runMonitorMenuItems = [
  {
    key: "总体客流变化",
    label: "总体客流变化",
    icon: `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 3v18h18"></path><path d="m7 15 4-4 3 3 5-7"></path></svg>`,
  },
  {
    key: "线路客流监测",
    label: "线路客流监测",
    icon: `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 17c4-5 8 5 16-1"></path><path d="M4 7c5 0 8 5 16 1"></path><circle cx="6" cy="17" r="2"></circle><circle cx="18" cy="8" r="2"></circle></svg>`,
  },
  {
    key: "站点客流监测",
    label: "站点客流监测",
    icon: `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 21s7-5.2 7-11a7 7 0 1 0-14 0c0 5.8 7 11 7 11Z"></path><circle cx="12" cy="10" r="2.5"></circle></svg>`,
  },
  {
    key: "车辆运行监测",
    label: "车辆运行监测",
    icon: `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="5" y="4" width="14" height="12" rx="2"></rect><path d="M7 16v2"></path><path d="M17 16v2"></path><circle cx="8.5" cy="11" r="1"></circle><circle cx="15.5" cy="11" r="1"></circle><path d="M8 7h8"></path></svg>`,
  },
];

const activeTab = ref("总体客流变化");
const isRunMonitorLeftCollapsed = ref(false);
const lineMonitorRef = ref(null);
const stationMonitorRef = ref(null);

const effectiveTab = computed(() => activeTab.value);
const isVehicleMonitorTab = computed(() => effectiveTab.value === "轨迹演示" || effectiveTab.value === "车辆运行监测");

function handleSetActiveTab(tabName) {
  activeTab.value = tabName;
}
provide("activeDatavisualizationTab", effectiveTab);

const rightPanelHasContent = ref(true);
provide("rightPanelHasContent", rightPanelHasContent);

function tabHasPersistentRightPanel(tab = effectiveTab.value) {
  return [
    "数据总览",
    "出行分析",
    "总体客流变化",
    "线路客流监测",
    "站点客流监测",
    "车辆运行监测",
  ].includes(tab);
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
  if (tab !== "线路客流监测") {
    setSelectedBusLine(null);
  }
  if (tab !== "站点客流监测") {
    setSelectedBusStation(null);
  }
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
const baseMapLineMode = ref("bus-network");
provide("BaseMapLineModeRef", baseMapLineMode);

const { proxy } = getCurrentInstance() || {};
const overallFlowLoading = ref(false);
const overallFlowError = ref("");
const overallFlowHourly = ref(Array.from({ length: 24 }, () => 0));
let overallFlowRequestSeq = 0;

const overallFlowTotal = computed(() => overallFlowHourly.value.reduce((sum, value) => sum + (Number(value) || 0), 0));
const overallFlowRankingRows = computed(() =>
  overallFlowHourly.value
    .map((value, hour) => {
      const hourLabel = `${String(hour).padStart(2, "0")}:00`;
      const nextHourLabel = hour === 23 ? "24:00" : `${String(hour + 1).padStart(2, "0")}:00`;
      const number = Math.max(0, Number(value) || 0);
      return {
        hour,
        label: `${hourLabel} - ${nextHourLabel}`,
        value: number,
        valueText: Math.round(number).toLocaleString("zh-CN"),
      };
    })
    .sort((a, b) => b.value - a.value || a.hour - b.hour),
);
function buildHourlyFlowChartOption(hourly = []) {
  const hours = hourly.map((_, index) => `${String(index).padStart(2, "0")}:00`);
  const LinearGradient = proxy?.$echarts?.graphic?.LinearGradient;
  const areaColor = LinearGradient
    ? new LinearGradient(0, 0, 0, 1, [
        { offset: 0, color: "rgba(0, 113, 227, 0.26)" },
        { offset: 1, color: "rgba(0, 113, 227, 0.02)" },
      ])
    : "rgba(0, 113, 227, 0.1)";
  return {
    backgroundColor: "transparent",
    tooltip: {
      trigger: "axis",
      appendToBody: true,
      backgroundColor: "rgba(255, 255, 255, 0.98)",
      borderColor: "rgba(17, 32, 58, 0.1)",
      borderWidth: 1,
      textStyle: { color: "#1c2024", fontSize: 12 },
      formatter(params = []) {
        const item = params[0];
        if (!item) return "";
        return `<strong>${item.name}</strong><br/>客流量：${Number(item.value || 0).toLocaleString("zh-CN")} 人次`;
      },
    },
    grid: { top: 28, right: 18, bottom: 18, left: 14, containLabel: true },
    xAxis: {
      type: "category",
      data: hours,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: "rgba(17, 32, 58, 0.12)" } },
      axisLabel: { color: "#667085", fontSize: 10, interval: 2 },
    },
    yAxis: {
      type: "value",
      name: "人次",
      nameTextStyle: { color: "#98a2b3", fontSize: 10, padding: [0, 8, 0, 0] },
      splitLine: { lineStyle: { color: "rgba(17, 32, 58, 0.07)", type: "dashed" } },
      axisLabel: { color: "#667085", fontSize: 10 },
    },
    series: [
      {
        name: "客流",
        type: "line",
        smooth: 0.35,
        showSymbol: false,
        symbol: "circle",
        symbolSize: 6,
        data: hourly,
        itemStyle: { color: "#0071e3" },
        lineStyle: { width: 3, color: "#0071e3", shadowBlur: 8, shadowColor: "rgba(0, 113, 227, 0.28)" },
        areaStyle: { color: areaColor },
      },
    ],
  };
}
const overallFlowChartOption = computed(() => buildHourlyFlowChartOption(overallFlowHourly.value));

// 线路客流监测：右侧简化卡片 —— 选中线路的日客流量 + 全天客流变化折线图（数据由 XLZL 上抛）
const selectedLinePanel = ref(null);
const selectedLineName = ref("");
provide("runMonitorSelectedLinePanel", selectedLinePanel);
provide("runMonitorSelectedLineName", selectedLineName);
provide("runMonitorSimplifiedRight", true);

const lineFlowHourly = computed(() => {
  const flow = selectedLinePanel.value?.hourlyFlow;
  const base = Array.from({ length: 24 }, () => 0);
  if (Array.isArray(flow)) {
    flow.forEach((value, index) => {
      if (index < base.length) base[index] = Number(value) || 0;
    });
  }
  return base;
});
const lineFlowTotal = computed(() => {
  const metricTotal = Number(selectedLinePanel.value?.metrics?.passenger);
  if (Number.isFinite(metricTotal) && metricTotal > 0) return metricTotal;
  return lineFlowHourly.value.reduce((sum, value) => sum + value, 0);
});
const lineFlowPeak = computed(() => {
  let peakIndex = 0;
  let peakValue = -Infinity;
  lineFlowHourly.value.forEach((value, index) => {
    if (value > peakValue) {
      peakValue = value;
      peakIndex = index;
    }
  });
  return {
    label: `${String(peakIndex).padStart(2, "0")}:00`,
    value: Math.max(0, peakValue),
  };
});
const lineFlowChartOption = computed(() => buildHourlyFlowChartOption(lineFlowHourly.value));

function formatOverallFlow(value) {
  const number = Number(value);
  return Number.isFinite(number) ? `${Math.round(number).toLocaleString("zh-CN")} 人次` : "暂无";
}

function routePanelToOverallHourly(panel = {}) {
  const hourly = Array.from({ length: 24 }, () => 0);
  const routes = panel?.routes && typeof panel.routes === "object" ? Object.values(panel.routes) : [];
  routes.forEach((route) => {
    const values = Array.isArray(route?.hourlyFlow) ? route.hourlyFlow : [];
    values.forEach((value, index) => {
      if (index < hourly.length) hourly[index] += Number(value) || 0;
    });
  });
  return hourly;
}

async function loadOverallFlow() {
  if (effectiveTab.value !== "总体客流变化" || !selectModel.value?.name || !isModelReady.value) return;
  const seq = ++overallFlowRequestSeq;
  overallFlowLoading.value = true;
  overallFlowError.value = "";
  try {
    const res = await getRoutePanel({ datasource: selectModel.value.name }, { silentError: true });
    if (seq !== overallFlowRequestSeq) return;
    overallFlowHourly.value = routePanelToOverallHourly(res?.data || {});
  } catch (error) {
    if (seq !== overallFlowRequestSeq) return;
    overallFlowHourly.value = Array.from({ length: 24 }, () => 0);
    overallFlowError.value = error?.message || "总体客流变化加载失败";
  } finally {
    if (seq === overallFlowRequestSeq) {
      overallFlowLoading.value = false;
    }
  }
}

watch(
  [effectiveTab, () => selectModel.value?.name, isModelReady],
  loadOverallFlow,
  { immediate: true },
);

const REAL_DATA_AREA_NAME = "广州市";
const RM_SOURCE_LINES = "rm-bus-network-lines-source";
const RM_SOURCE_STATIONS = "rm-bus-network-stations-source";
const RM_SOURCE_SELECTED_LINE = "rm-bus-network-selected-line-source";
const RM_SOURCE_SELECTED_STATION = "rm-bus-network-selected-station-source";
const RM_LAYER_LINES = "rm-bus-network-lines";
const RM_LAYER_LINE_SELECTED = "rm-bus-network-line-selected";
const RM_LAYER_LINE_SELECTED_GLOW = "rm-bus-network-line-selected-glow";
const RM_LAYER_STATIONS = "rm-bus-network-stations";
const RM_LAYER_STATION_SELECTED = "rm-bus-network-station-selected";
const RM_STATION_ICON_ID = "rm-bus-network-station-icon";
const RM_STATION_HIGHLIGHT_ICON_ID = "rm-bus-network-station-highlight-icon";
const RM_STATION_ICON_SIZE = 96;
const busNetworkLoading = ref(false);
const busNetworkError = ref("");
let busNetworkRequestSeq = 0;
let busNetworkClickListenerId = null;
let monitorRoadLayer = null;
let busNetworkSourceRefs = new Map();
let busNetworkCollections = {
  lines: emptyFeatureCollection(),
  stations: emptyFeatureCollection(),
};

const busNetworkLineWidth = computed(() => Math.max(0.8, Math.min(5.6, lineWidth.value / 11)));
const busNetworkStationIconScale = computed(() => {
  const highZoomScale = Math.max(0.12, Math.min(0.5, stationSize.value / RM_STATION_ICON_SIZE));
  return [
    "interpolate",
    ["exponential", 1.25],
    ["zoom"],
    8,
    0.026,
    10,
    highZoomScale * 0.14,
    12,
    highZoomScale * 0.34,
    14,
    highZoomScale * 0.72,
    16,
    highZoomScale * 1.08,
  ];
});

function emptyFeatureCollection() {
  return { type: "FeatureCollection", features: [] };
}

function normalizeLineFeatureCollection(collection) {
  const features = Array.isArray(collection?.features) ? collection.features : [];
  return {
    type: "FeatureCollection",
    features: features
      .map((feature, index) => normalizeBusFeature(feature, index, "line"))
      .filter((feature) => feature.geometry),
  };
}

function normalizeStationFeatureCollection(collection) {
  const features = Array.isArray(collection?.features) ? collection.features : [];
  return {
    type: "FeatureCollection",
    features: features
      .map((feature, index) => normalizeBusFeature(feature, index, "station"))
      .filter((feature) => feature.geometry),
  };
}

function normalizeBusFeature(feature, index, type) {
  const properties = { ...(feature?.properties || {}) };
  const id = String(
    properties._featureId ||
      properties._lineKey ||
      properties._stationKey ||
      feature?.id ||
      [properties.line_id, properties.dir, properties.route_id, properties.stop_id, index].filter(Boolean).join("-") ||
      `${type}-${index}`,
  );
  if (type === "line") {
    properties._lineKey = properties._lineKey || id;
  } else {
    properties._stationKey = properties._stationKey || id;
  }
  return {
    type: "Feature",
    id,
    geometry: feature?.geometry || null,
    properties,
  };
}

function setGeoJsonSourceData(sourceId, data, map = MapRef.value?.map) {
  const source = map?.getSource(sourceId);
  if (!source?.setData) return false;
  if (busNetworkSourceRefs.get(sourceId) === data) return false;
  source.setData(data);
  busNetworkSourceRefs.set(sourceId, data);
  return true;
}

function ensureBusNetworkSource(map, sourceId, data) {
  if (map.getSource(sourceId)) {
    setGeoJsonSourceData(sourceId, data, map);
    return;
  }
  map.addSource(sourceId, { type: "geojson", data });
  busNetworkSourceRefs.set(sourceId, data);
}

function addBusLayerBelowBuildings(map, layer) {
  const beforeId = MapRef.value?.buildingLayerId;
  if (beforeId && map.getLayer?.(beforeId)) {
    map.addLayer(layer, beforeId);
    return;
  }
  map.addLayer(layer);
}

async function ensureBusStationIcons(map) {
  await Promise.all([
    addMapImageOnce(map, RM_STATION_ICON_ID, busStationIconUrl, RM_STATION_ICON_SIZE),
    addMapImageOnce(map, RM_STATION_HIGHLIGHT_ICON_ID, busStationHighlightIconUrl, RM_STATION_ICON_SIZE),
  ]);
}

async function addMapImageOnce(map, imageId, imageUrl, size) {
  if (map.hasImage?.(imageId)) return;
  const image = await loadIconImageData(imageUrl, size);
  if (!map.hasImage?.(imageId)) {
    map.addImage(imageId, image);
  }
}

async function loadIconImageData(url, size) {
  const image = await loadImage(url);
  const canvas = document.createElement("canvas");
  canvas.width = size;
  canvas.height = size;
  const context = canvas.getContext("2d");
  context.clearRect(0, 0, size, size);
  context.drawImage(image, 0, 0, size, size);
  return context.getImageData(0, 0, size, size);
}

function loadImage(url) {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = reject;
    image.src = url;
  });
}

function busStationIconLayout(iconId = RM_STATION_ICON_ID, iconScale = busNetworkStationIconScale.value) {
  return {
    "icon-image": iconId,
    "icon-size": iconScale,
    "icon-anchor": "center",
    "icon-allow-overlap": true,
    "icon-ignore-placement": true,
    "icon-padding": 2,
  };
}

function ensureBusNetworkLayers(map) {
  ensureBusNetworkSource(map, RM_SOURCE_LINES, busNetworkCollections.lines);
  ensureBusNetworkSource(map, RM_SOURCE_STATIONS, busNetworkCollections.stations);
  ensureBusNetworkSource(map, RM_SOURCE_SELECTED_LINE, emptyFeatureCollection());
  ensureBusNetworkSource(map, RM_SOURCE_SELECTED_STATION, emptyFeatureCollection());

  if (!map.getLayer(RM_LAYER_LINES)) {
    addBusLayerBelowBuildings(map, {
      id: RM_LAYER_LINES,
      type: "line",
      source: RM_SOURCE_LINES,
      layout: { "line-join": "round", "line-cap": "round" },
      paint: {
        "line-color": "#2f6f73",
        "line-opacity": 0.72,
        "line-width": busNetworkLineWidth.value,
      },
    });
  }
  if (!map.getLayer(RM_LAYER_LINE_SELECTED_GLOW)) {
    addBusLayerBelowBuildings(map, {
      id: RM_LAYER_LINE_SELECTED_GLOW,
      type: "line",
      source: RM_SOURCE_SELECTED_LINE,
      layout: { "line-join": "round", "line-cap": "round" },
      paint: {
        "line-color": "#facc15",
        "line-opacity": 0.42,
        "line-width": Math.max(6, busNetworkLineWidth.value * 3.2),
      },
    });
  }
  if (!map.getLayer(RM_LAYER_LINE_SELECTED)) {
    addBusLayerBelowBuildings(map, {
      id: RM_LAYER_LINE_SELECTED,
      type: "line",
      source: RM_SOURCE_SELECTED_LINE,
      layout: { "line-join": "round", "line-cap": "round" },
      paint: {
        "line-color": "#f97316",
        "line-opacity": 0.96,
        "line-width": Math.max(3.5, busNetworkLineWidth.value + 2.4),
      },
    });
  }
  if (!map.getLayer(RM_LAYER_STATIONS)) {
    map.addLayer({
      id: RM_LAYER_STATIONS,
      type: "symbol",
      source: RM_SOURCE_STATIONS,
      layout: busStationIconLayout(),
      paint: { "icon-opacity": 0.96 },
    });
  }
  if (!map.getLayer(RM_LAYER_STATION_SELECTED)) {
    map.addLayer({
      id: RM_LAYER_STATION_SELECTED,
      type: "symbol",
      source: RM_SOURCE_SELECTED_STATION,
      layout: busStationIconLayout(RM_STATION_HIGHLIGHT_ICON_ID, [
        "interpolate",
        ["linear"],
        ["zoom"],
        8,
        0.04,
        10,
        0.09,
        12,
        0.18,
        14,
        0.34,
        16,
        0.5,
      ]),
      paint: { "icon-opacity": 1 },
    });
  }
  applyBusNetworkPaint();
  syncBaseMapLayerVisibility();
}

function setBusLayerVisibility(map, layerId, visible) {
  if (map?.getLayer?.(layerId)) {
    map.setLayoutProperty(layerId, "visibility", visible ? "visible" : "none");
  }
}

function applyBusNetworkPaint() {
  const map = MapRef.value?.map;
  if (!map) return;
  if (map.getLayer(RM_LAYER_LINES)) {
    map.setPaintProperty(RM_LAYER_LINES, "line-width", busNetworkLineWidth.value);
  }
  if (map.getLayer(RM_LAYER_LINE_SELECTED)) {
    map.setPaintProperty(RM_LAYER_LINE_SELECTED, "line-width", Math.max(3.5, busNetworkLineWidth.value + 2.4));
  }
  if (map.getLayer(RM_LAYER_LINE_SELECTED_GLOW)) {
    map.setPaintProperty(RM_LAYER_LINE_SELECTED_GLOW, "line-width", Math.max(6, busNetworkLineWidth.value * 3.2));
  }
  if (map.getLayer(RM_LAYER_STATIONS)) {
    map.setLayoutProperty(RM_LAYER_STATIONS, "icon-size", busNetworkStationIconScale.value);
  }
}

function syncBaseMapLayerVisibility() {
  const map = MapRef.value?.map;
  if (!map) return;
  const showBusNetwork = baseMapLineMode.value === "bus-network";
  [RM_LAYER_LINES, RM_LAYER_LINE_SELECTED, RM_LAYER_LINE_SELECTED_GLOW, RM_LAYER_STATIONS, RM_LAYER_STATION_SELECTED].forEach((layerId) => {
    setBusLayerVisibility(map, layerId, showBusNetwork);
  });
  if (monitorRoadLayer) {
    showBusNetwork ? monitorRoadLayer.hide() : monitorRoadLayer.show();
  }
}

async function loadBusNetwork(options = {}) {
  const { force = false } = options;
  if (!MapRef.value?.map || !isModelReady.value) return;
  const seq = ++busNetworkRequestSeq;
  busNetworkLoading.value = true;
  busNetworkError.value = "";
  try {
    const data = await getCachedRealData(REAL_DATA_AREA_NAME, { force });
    if (seq !== busNetworkRequestSeq) return;
    busNetworkCollections = {
      lines: normalizeLineFeatureCollection(data?.lines),
      stations: normalizeStationFeatureCollection(data?.stations || data?.routeStops),
    };
    const map = MapRef.value?.map;
    if (!map) return;
    ensureBusNetworkSource(map, RM_SOURCE_LINES, busNetworkCollections.lines);
    ensureBusNetworkSource(map, RM_SOURCE_STATIONS, busNetworkCollections.stations);
    await ensureBusStationIcons(map);
    ensureBusNetworkLayers(map);
  } catch (error) {
    if (seq !== busNetworkRequestSeq) return;
    busNetworkError.value = error?.message || "公交线网加载失败";
  } finally {
    if (seq === busNetworkRequestSeq) {
      busNetworkLoading.value = false;
    }
  }
}

function ensureMonitorRoadLayer() {
  if (!MapRef.value || monitorRoadLayer || !selectModel.value?.name) return;
  monitorRoadLayer = new NetworkLayer({
    zIndex: 997,
    lineWidth: computedLineWidth.value,
    flowWidthStep: computedFlowWidthStep.value,
    flowControl: flowControl.value,
  });
  MapRef.value.addLayer(monitorRoadLayer);
  monitorRoadLayer.setTileSource(selectModel.value.name);
  syncBaseMapLayerVisibility();
}

function handleBaseMapLineModeChange(mode) {
  baseMapLineMode.value = mode;
  if (mode === "road-network") {
    ensureMonitorRoadLayer();
  }
  syncBaseMapLayerVisibility();
}

function queryBoxAround(point, radius = 8) {
  return [
    [point[0] - radius, point[1] - radius],
    [point[0] + radius, point[1] + radius],
  ];
}

function busLineName(properties = {}) {
  return String(
    properties.lineName ||
      properties.line_name ||
      properties.routeName ||
      properties.route_name ||
      properties.name ||
      properties.line_id ||
      properties.route_id ||
      "",
  ).trim();
}

function busStationName(properties = {}) {
  return String(
    properties.stop_name ||
      properties.station_name ||
      properties.facilityName ||
      properties.name ||
      properties.stop_id ||
      "",
  ).trim();
}

function plainBusFeature(feature) {
  return {
    type: "Feature",
    id: feature?.id,
    geometry: feature?.geometry ? JSON.parse(JSON.stringify(feature.geometry)) : null,
    properties: { ...(feature?.properties || {}) },
  };
}

function setSelectedBusLine(feature) {
  const source = MapRef.value?.map?.getSource(RM_SOURCE_SELECTED_LINE);
  if (!source?.setData) return;
  source.setData(feature?.geometry ? { type: "FeatureCollection", features: [plainBusFeature(feature)] } : emptyFeatureCollection());
}

function setSelectedBusStation(feature) {
  const source = MapRef.value?.map?.getSource(RM_SOURCE_SELECTED_STATION);
  if (!source?.setData) return;
  source.setData(feature?.geometry ? { type: "FeatureCollection", features: [plainBusFeature(feature)] } : emptyFeatureCollection());
}

function firstRenderedBusFeature(point, layers, radius = 8) {
  const map = MapRef.value?.map;
  if (!map || !Array.isArray(point)) return null;
  const existingLayers = layers.filter((layerId) => map.getLayer?.(layerId));
  if (!existingLayers.length) return null;
  return map.queryRenderedFeatures(queryBoxAround(point, radius), { layers: existingLayers })?.[0] || null;
}

async function selectLineFromBusNetwork(feature) {
  setSelectedBusLine(feature);
  setSelectedBusStation(null);
  const name = busLineName(feature?.properties || {});
  if (!name) return;
  await nextTick();
  lineMonitorRef.value?.selectLineByName?.(name);
}

async function selectStationFromBusNetwork(feature) {
  setSelectedBusStation(feature);
  setSelectedBusLine(null);
  const name = busStationName(feature?.properties || {});
  if (!name) return;
  await nextTick();
  stationMonitorRef.value?.selectStationByName?.(name);
}

function handleBusNetworkMapClick(event) {
  if (baseMapLineMode.value !== "bus-network") return;
  if (effectiveTab.value !== "线路客流监测" && effectiveTab.value !== "站点客流监测") return;
  const point = event?.data?.point;
  if (!Array.isArray(point)) return;
  if (effectiveTab.value === "站点客流监测") {
    const stationFeature = firstRenderedBusFeature(point, [RM_LAYER_STATION_SELECTED, RM_LAYER_STATIONS], 10);
    if (stationFeature) {
      selectStationFromBusNetwork(stationFeature);
    }
    return;
  }
  const lineFeature = firstRenderedBusFeature(point, [RM_LAYER_LINE_SELECTED, RM_LAYER_LINES], 7);
  if (lineFeature) {
    selectLineFromBusNetwork(lineFeature);
  }
}

function bindBusNetworkClickListener() {
  const mapInstance = MapRef.value;
  if (!mapInstance || busNetworkClickListenerId) return;
  busNetworkClickListenerId = mapInstance.addEventListener("handle:click", handleBusNetworkMapClick);
}

function unbindBusNetworkClickListener() {
  if (MapRef.value && busNetworkClickListenerId) {
    MapRef.value.removeEventListener("handle:click", busNetworkClickListenerId);
  }
  busNetworkClickListenerId = null;
}

function clearBusNetworkLayers() {
  const map = MapRef.value?.map;
  if (!map) return;
  [
    RM_LAYER_STATION_SELECTED,
    RM_LAYER_STATIONS,
    RM_LAYER_LINE_SELECTED,
    RM_LAYER_LINE_SELECTED_GLOW,
    RM_LAYER_LINES,
  ].forEach((layerId) => {
    if (map.getLayer?.(layerId)) map.removeLayer(layerId);
  });
  [
    RM_SOURCE_SELECTED_STATION,
    RM_SOURCE_STATIONS,
    RM_SOURCE_SELECTED_LINE,
    RM_SOURCE_LINES,
  ].forEach((sourceId) => {
    if (map.getSource?.(sourceId)) map.removeSource(sourceId);
  });
  busNetworkSourceRefs = new Map();
  busNetworkCollections = {
    lines: emptyFeatureCollection(),
    stations: emptyFeatureCollection(),
  };
}

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
  monitorRoadLayer?.setLineWidth(computedLineWidth.value);
  applyBusNetworkPaint();
}

function applyFlowWidthStep() {
  if (MapRef.value && MapRef.value.layers) {
    MapRef.value.layers.forEach((layer) => {
      if (typeof layer.setFlowWidthStep === "function") {
        layer.setFlowWidthStep(computedFlowWidthStep.value);
      }
    });
  }
  monitorRoadLayer?.setFlowWidthStep(computedFlowWidthStep.value);
}

function applyStationSize() {
  if (MapRef.value && MapRef.value.layers) {
    MapRef.value.layers.forEach((layer) => {
      if (typeof layer.setMarkerSize === "function") {
        layer.setMarkerSize(stationSize.value);
      }
    });
  }
  applyBusNetworkPaint();
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
  syncBaseMapLayerVisibility();
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
  monitorRoadLayer?.setFlowControl(flowControl.value);
}

function handleFlowControlChange(val) {
  flowControl.value = val;
  applyFlowControl();
}

watch(MapRef, (mapInstance) => {
  setMapCenter();
  
  if (mapInstance) {
    bindBusNetworkClickListener();
    loadBusNetwork();
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
    syncBaseMapLayerVisibility();
  }
});

watch(baseMapLineMode, (mode) => {
  if (mode === "road-network") {
    ensureMonitorRoadLayer();
  }
  syncBaseMapLayerVisibility();
});

watch(
  [() => selectModel.value?.name, isModelReady],
  ([modelName]) => {
    if (!isModelReady.value || !modelName) return;
    loadBusNetwork();
    if (monitorRoadLayer) {
      monitorRoadLayer.setTileSource(modelName);
    } else if (baseMapLineMode.value === "road-network") {
      ensureMonitorRoadLayer();
    }
  },
);

watch(isRightPanelVisible, (visible) => {
  if (visible && (effectiveTab.value === "数据总览" || isVehicleMonitorTab.value)) {
    isRightCollapsed.value = false;
  }
});

watch(rightPanelHasContent, (hasContent) => {
  if (hasContent && isVehicleMonitorTab.value) {
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
  unbindBusNetworkClickListener();
  clearBusNetworkLayers();
  monitorRoadLayer?.dispose();
  monitorRoadLayer = null;
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
.box1 {
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

.rm-vehicle-controls {
  min-height: 0;
}

#left-analysis-panel {
  z-index: calc(var(--z-panel) + 5);
}

.run-monitor-right-panel {
  .flex_column_scroll_box {
    width: 100%;
    height: 100%;
    min-height: 0;
  }

  :deep(.el-scrollbar__wrap),
  :deep(.el-scrollbar__view) {
    height: 100%;
  }

  #datavisualization_index_box2 {
    height: 100%;
    min-height: 0;
    display: flex;
    flex-direction: column;
    align-items: stretch;
    gap: var(--dm2-space-3);
  }

  :deep(.MCard2) {
    width: 100%;
    border-color: var(--dm2-line);
    border-radius: var(--dm2-radius-lg);
    background: rgba(255, 255, 255, 0.68);
    box-shadow: var(--dm2-shadow-card);
  }

  :deep(.MCard2_title_box) {
    background: rgba(0, 113, 227, 0.07);
    color: var(--dm2-accent-strong);
  }
}

.rm-right-card {
  width: 100%;
  box-sizing: border-box;
  min-height: 0;
  display: flex;
  flex-direction: column;
  flex: 1;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  overflow: visible;
}

.rm-right-card-title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--dm2-space-3);
  padding: 0 0 14px;
  border-bottom: 1px solid var(--dm2-line-faint);
  background: transparent;

  h2 {
    margin: 4px 0 0;
    color: var(--dm2-ink);
    font-size: 19px;
    line-height: 1.25;
    font-weight: 780;
  }
}

.rm-panel-kicker {
  margin: 0;
  color: var(--dm2-accent-strong);
  font-size: 11px;
  font-weight: 760;
}

.rm-overall-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--dm2-space-3);
  padding: 14px 0 0;
}

.overall-flow-card .rm-overall-summary {
  grid-template-columns: 1fr;
}

.rm-summary-item {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 13px 14px;
  border: 1px solid rgba(0, 113, 227, 0.12);
  border-radius: 12px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.94), rgba(238, 246, 255, 0.82)),
    var(--dm2-surface-sunken);
  box-shadow: 0 8px 22px -18px rgba(13, 38, 76, 0.28), inset 0 1px 0 rgba(255, 255, 255, 0.84);

  span {
    color: var(--dm2-muted);
    font-size: 11px;
    font-weight: 650;
  }

  strong {
    color: var(--dm2-ink);
    font-family: var(--dm2-font-num);
    font-size: 21px;
    font-weight: 820;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
}

.rm-overall-chart {
  flex: 0 0 226px;
  min-height: 226px;
  margin-top: 12px;
  padding: 8px 4px 2px;
  box-sizing: border-box;
  border: 1px solid rgba(17, 32, 58, 0.08);
  border-radius: 12px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.9), rgba(247, 250, 254, 0.78)),
    var(--dm2-surface);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.82);
}

.rm-chart,
.chart_box {
  width: 100%;
  height: 100%;
}

.rm-panel-error {
  margin: var(--dm2-space-4);
  color: var(--dm2-delete);
  font-size: 13px;
  font-weight: 650;
}

.hourly-ranking-panel {
  flex: 1;
  min-height: 0;
  margin-top: 14px;
}

.ranking-title-text {
  margin: 0 0 10px;
  color: #1569de;
  font-size: 15px;
  line-height: 1.2;
  font-weight: 800;
}

.ranking-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 0 0 2px;
}

.ranking-header {
  display: flex;
  align-items: center;
  padding: 10px 16px;
  margin-bottom: 8px;
  border: 0;
  border-radius: 6px;
  background: #1569de;
  color: #ffffff;

  span {
    color: #ffffff;
    font-size: 13px;
    line-height: 1.2;
    font-weight: 800;
    letter-spacing: 0;
  }
}

.ranking-scroll-list {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 0;
  overflow-y: auto;
  padding-right: 6px;
  scrollbar-width: thin;
  scrollbar-color: rgba(21, 105, 222, 0.2) transparent;

  &::-webkit-scrollbar {
    width: 6px;
  }

  &::-webkit-scrollbar-thumb {
    background: rgba(21, 105, 222, 0.2);
    border-radius: 3px;
  }

  &::-webkit-scrollbar-thumb:hover {
    background: rgba(21, 105, 222, 0.4);
  }
}

.ranking-row {
  width: 100%;
  border: 0;
  display: flex;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px dashed rgba(21, 105, 222, 0.12);
  border-radius: 0;
  background: #ffffff;
  box-shadow: none;
  color: inherit;
  font-family: inherit;
  text-align: left;
  cursor: default;
  transition:
    background-color 0.2s ease,
    border-color 0.2s ease;

  &:hover {
    background: rgba(21, 105, 222, 0.03);
    border-bottom-color: rgba(21, 105, 222, 0.3);
  }

  &:last-child {
    border-bottom: none;
  }
}

.col-rank {
  width: 50px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: flex-start;
}

.col-name {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-right: 12px;
}

.col-flow {
  width: 110px;
  flex-shrink: 0;
  display: flex;
  align-items: baseline;
  justify-content: flex-end;
  gap: 3px;
}

.rank-badge {
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: #60758e;
  background: rgba(113, 128, 150, 0.08);
  border: 0;
  font-size: 12px;
  font-weight: 800;
  font-variant-numeric: tabular-nums;

  &.gold {
    color: #ffffff;
    background: #d97706;
    font-size: 13px;
  }

  &.silver {
    color: #ffffff;
    background: #94a3b8;
    font-size: 13px;
  }

  &.bronze {
    color: #ffffff;
    background: #ea580c;
    font-size: 13px;
  }
}

.route-name-text {
  min-width: 0;
  color: #2d3748;
  font-size: 14px;
  line-height: 1.25;
  font-weight: 800;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.route-desc-text {
  min-width: 0;
  color: #a0aec0;
  font-size: 11px;
  line-height: 1.25;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.flow-value {
  color: #0f9f6e;
  font-size: 16px;
  line-height: 1.2;
  font-weight: 800;
  font-family: var(--dm2-font-num);
  font-variant-numeric: tabular-nums;
}

.ranking-row:nth-child(-n + 3) .flow-value {
  color: #d97706;
}

.flow-unit {
  color: #60758e;
  font-size: 11px;
  font-weight: 600;
}

.layer-mode-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  color: var(--app-muted);
  font-size: 11px;
  font-weight: 600;
}

.layer-mode-segment {
  display: inline-flex;
  padding: 2px;
  border: 1px solid rgba(21, 105, 222, 0.12);
  border-radius: 8px;
  background: rgba(21, 105, 222, 0.05);

  button {
    min-width: 70px;
    height: 26px;
    border: 0;
    border-radius: 6px;
    background: transparent;
    color: var(--app-muted);
    cursor: pointer;
    font-size: 11px;
    font-weight: 700;

    &.active {
      background: #ffffff;
      color: var(--app-blue);
      box-shadow: 0 1px 4px rgba(21, 105, 222, 0.12);
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
