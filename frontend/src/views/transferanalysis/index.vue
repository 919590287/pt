<!--
  换乘分析（公交—地铁）：设计方案 v2（docs/公交地铁换乘分析模块设计方案.md）P0 实现。
  架构：后端 transfer-summary/dict/events.bin 三件套 → 前端 Worker 全量交互聚合 →
  左导航 / 中地图（枢纽气泡+行政区线网，枢纽详情叠完整公交段与换乘短连线）/ 右统计（三个子模块视角）。
  口径红线：识别窗口 30min + 地面 800m 为"时间—空间规则推定"；数量按模型原值直出、
  人数按 personIndex 去重；分时按后序上车时刻；直方图 30 桶无溢出桶。
-->
<template>
  <div class="ta-wrapper">
    <!-- 顶栏右上角：数据源分段 + 方案 + 模型（与运行监测/客流分析同款，position:fixed 浮于 header） -->
    <div class="ta-datebase analysis-model-toolbar" role="search" aria-label="方案与模型选择">
      <div class="data-source-segment analysis-source-segment" role="group" aria-label="数据源类型">
        <button
          v-for="item in DATA_SOURCE_OPTIONS"
          :key="item.value"
          type="button"
          :class="{ active: dataSourceMode === item.value }"
          @click="dataSourceMode = item.value"
        >
          {{ item.label }}
        </button>
      </div>
      <label class="handle analysis-model-label" for="ta-scheme-selector">当前方案</label>
      <el-select id="ta-scheme-selector" class="analysis-scheme-select" v-model="area" clearable filterable :loading="schemesLoading" :disabled="!isSimulationMode" aria-label="当前方案" @change="onAreaChange">
        <el-option v-for="s in schemeList" :key="s" :label="s" :value="s" />
      </el-select>
      <el-select class="ta-model-select analysis-model-select" v-model="modelName" clearable filterable :disabled="!isSimulationMode || !area || modelsLoading" :loading="modelsLoading" aria-label="选择模型">
        <el-option v-for="m in models" :key="m.name" :label="modelLabel(m)" :value="m.name">
          <div class="ta-model-option">
            <span class="ta-model-option-name" :title="m.name">{{ modelLabel(m) }}</span>
            <el-tag size="small" :type="modelReadyTag(m).type">{{ modelReadyTag(m).text }}</el-tag>
          </div>
        </el-option>
      </el-select>
    </div>

    <!-- 左侧：模块导航 + 通用筛选（复用 tokens.css 的 dm-sidebar 骨架） -->
    <div :class="['dm-sidebar', 'ta-sidebar', leftCollapsed ? 'is-collapsed' : '']">
      <div class="sidebar-brand">
        <svg class="brand-icon" viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">
          <path d="M8 3h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z" />
          <path d="M6 8h12" />
          <circle cx="9" cy="13" r="1" />
          <circle cx="15" cy="13" r="1" />
          <path d="M9 16l-2 4" />
          <path d="M15 16l2 4" />
        </svg>
        <span class="brand-text">换乘分析</span>
      </div>

      <nav class="sidebar-nav" aria-label="换乘分析模块导航">
        <div v-for="item in NAV" :key="item.key" class="menu-group">
          <button
            type="button"
            :class="['nav-item', activeModule === item.key ? 'active' : '']"
            :aria-current="activeModule === item.key ? 'page' : undefined"
            @click="activeModule = item.key"
          >
            <span class="nav-icon" v-html="item.icon"></span>
            <span class="nav-label">{{ item.label }}</span>
          </button>
        </div>
      </nav>

      <div class="sidebar-footer"></div>
    </div>
    <button
      type="button"
      :class="['dm-panel-collapse-tab', 'dm-left-collapse-tab', leftCollapsed ? 'is-collapsed' : '']"
      :title="leftCollapsed ? '展开面板' : '收起面板'"
      :aria-pressed="leftCollapsed"
      @click="leftCollapsed = !leftCollapsed"
    >
      <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6" /></svg>
    </button>

    <!-- 右侧：模块统计面板 -->
    <div :class="['dm-overview-panel', 'ta-panel', rightCollapsed ? 'is-collapsed' : '']">
      <header class="ta-head">
        <div class="ta-head-row">
          <h2 class="ta-title">{{ activeNav.label }}</h2>
          <span v-if="showHeaderTimeFilters" class="ta-head-time" aria-label="统计时段">{{ filters.timeRange[0] }}:00 - {{ filters.timeRange[1] }}:00</span>
        </div>
        <!-- 通用统计筛选（原左侧栏迁入；切模块不重置） -->
        <div v-if="showHeaderTimeFilters" class="ta-head-filters">
          <el-slider
            v-model="filters.timeRange"
            range
            :min="0"
            :max="24"
            :step="1"
            :show-tooltip="false"
            class="ta-head-slider"
            aria-label="统计时段"
          />
          <el-radio-group v-model="filters.unitMin" size="small" class="ta-pills ta-head-pills" aria-label="时间粒度">
            <el-radio-button :value="15">15min</el-radio-button>
            <el-radio-button :value="30">30min</el-radio-button>
            <el-radio-button :value="60">1h</el-radio-button>
          </el-radio-group>
        </div>
      </header>

      <el-scrollbar :class="['ta-body', { 'ta-body-fill': activeModule === 'overview' && transferPhase === 'ready' }]">
        <!-- 状态门：真实数据 / 未选模型 / 模型加载 / 缓存生成 / 数据装载 / 错误 -->
        <div v-if="!isSimulationMode" class="ta-blank ta-gate">
          <p class="ta-blank-title">真实数据暂未接入</p>
          <p class="ta-blank-sub">换乘分析目前基于仿真模型的事件级上下车流水，请切换到「仿真」数据源。</p>
        </div>
        <div v-else-if="!modelName" class="ta-blank ta-gate">
          <p class="ta-blank-title">请选择方案与模型</p>
          <p class="ta-blank-sub">换乘分析基于已加载仿真模型的事件级上下车流水（数据源固定为仿真）。</p>
        </div>
        <div v-else-if="gateError" class="ta-gate ta-gate-error">
          <p class="ta-blank-title">数据未就绪</p>
          <p class="ta-blank-sub">{{ gateError }}</p>
          <button type="button" class="ta-export ta-retry" @click="startPipeline">重试</button>
        </div>
        <div v-else-if="transferPhase !== 'ready'" class="ta-gate">
          <!-- 400ms 内完成（前端缓存命中）就不闪加载门 -->
          <template v-if="gateRevealed">
            <div class="ta-gate-spinner" aria-hidden="true"></div>
            <p class="ta-blank-title">{{ gateNote }}</p>
            <p v-if="gateSub" class="ta-blank-sub">{{ gateSub }}</p>
            <el-skeleton :rows="6" animated class="ta-gate-skeleton" />
          </template>
        </div>
        <template v-else>
          <div v-if="aggBusy" class="ta-agg-busy" aria-hidden="true"></div>
          <component :is="activeSectionComp" />
        </template>
      </el-scrollbar>
    </div>
    <button
      type="button"
      :class="['dm-panel-collapse-tab', 'dm-right-collapse-tab', rightCollapsed ? 'is-collapsed' : '']"
      :title="rightCollapsed ? '展开面板' : '收起面板'"
      :aria-expanded="!rightCollapsed"
      @click="rightCollapsed = !rightCollapsed"
    >
      <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6" /></svg>
    </button>

    <!-- 地图对象搜索：站点分析搜索地铁站，线路分析搜索地铁线路 -->
    <div
      v-if="mapSearchVisible"
      :class="['ta-search', { 'is-focused': mapSearchFocused, 'is-left-collapsed': leftCollapsed }]"
      role="search"
      :aria-label="mapSearchAriaLabel"
      @click.stop
    >
      <svg class="ta-search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
        <circle cx="11" cy="11" r="8"></circle>
        <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
      </svg>
      <input
        v-model="mapSearchKeyword"
        class="ta-search-input"
        type="search"
        :placeholder="mapSearchPlaceholder"
        :aria-label="mapSearchAriaLabel"
        @focus="mapSearchFocused = true"
        @click="mapSearchFocused = true"
        @blur="mapSearchFocused = false"
        @keydown.enter.prevent="selectFirstMapSearchResult"
        @keydown.esc.prevent="$event.target.blur()"
      />
      <button v-if="mapSearchKeyword" class="ta-search-clear" type="button" title="清空并取消选中" aria-label="清空并取消选中" @mousedown.prevent="clearMapSearch">
        <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
          <line x1="18" y1="6" x2="6" y2="18"></line>
          <line x1="6" y1="6" x2="18" y2="18"></line>
        </svg>
      </button>
      <Transition name="ta-search-fade">
        <div v-if="mapSearchFocused" class="ta-search-results" role="listbox" :aria-label="mapSearchAriaLabel">
          <button
            v-for="r in mapSearchResults"
            :key="`${activeModule}-${r.idx}`"
            class="ta-search-result"
            type="button"
            role="option"
            :aria-selected="mapSearchSelectedId === r.idx"
            @mousedown.prevent="selectMapSearchResult(r)"
          >
            <span class="ta-result-icon">
              <svg v-if="activeModule === 'hub'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"></path>
                <circle cx="12" cy="10" r="3"></circle>
              </svg>
              <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M5 17 19 7"></path>
                <circle cx="7" cy="15.5" r="2.2"></circle>
                <circle cx="17" cy="8.5" r="2.2"></circle>
              </svg>
            </span>
            <span class="ta-result-meta">
              <span class="ta-result-name">{{ r.name }}</span>
              <span class="ta-result-type">{{ mapSearchResultType }} · {{ fmtCount(expand(r.flow)) }}人次</span>
            </span>
          </button>
          <p v-if="!mapSearchResults.length" class="ta-search-empty">未找到匹配项</p>
        </div>
      </Transition>
    </div>

    <!-- 地图控件工具条（与运行监测/客流分析同款；按钮样式来自 tokens.css 全局规则） -->
    <div ref="mapControlsRef" :class="['map-controls-toolbar', rightCollapsed ? 'without-panel' : 'with-panel']">
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

      <div class="control-block">
        <button
          :class="['control-btn', selectedDisplayRange !== DISPLAY_RANGE_ALL || showRangePopover ? 'active' : '']"
          type="button"
          @click="toggleRangePopover"
          :title="displayRangeButtonTitle"
          :aria-label="displayRangeButtonTitle"
          :aria-expanded="showRangePopover"
          aria-controls="ta-range-popover"
          aria-haspopup="dialog"
        >
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round">
            <path d="M3 6.5 8 4l8 2.5 5-2.5v13.5L16 20l-8-2.5-5 2.5V6.5Z"></path>
            <path d="M8 4v13.5"></path>
            <path d="M16 6.5V20"></path>
          </svg>
        </button>
      </div>

      <div class="control-block settings-block">
        <button
          :class="['control-btn', showSettingsPopover ? 'active' : '']"
          type="button"
          @click="toggleSettingsPopover"
          title="设置"
          aria-label="打开换乘分析设置"
          :aria-expanded="showSettingsPopover"
          aria-controls="ta-settings-popover"
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

      <Transition name="ta-popover-fade">
        <div
          v-if="showRangePopover"
          id="ta-range-popover"
          class="ta-controls-popover"
          role="dialog"
          aria-modal="false"
          @click.stop
          @keydown.esc.stop.prevent="showRangePopover = false"
        >
          <div class="ta-popover-title">行政区显示范围</div>
          <div v-if="isLoadingDisplayRanges" class="ta-range-state">行政区加载中</div>
          <div v-else-if="displayRangeOptions.length" class="ta-range-list" role="listbox" aria-label="行政区显示范围">
            <button
              v-for="item in displayRangeOptions"
              :key="item"
              type="button"
              role="option"
              :class="['ta-range-option', selectedDisplayRange === item ? 'active' : '']"
              :aria-selected="selectedDisplayRange === item"
              @click="selectDisplayRange(item)"
            >
              {{ item }}
            </button>
          </div>
          <p v-else class="ta-range-state">暂无行政区范围</p>
          <p v-if="displayRangeError" class="ta-range-error">{{ displayRangeError }}</p>
        </div>
      </Transition>

      <Transition name="ta-popover-fade">
        <div
          v-if="showSettingsPopover"
          id="ta-settings-popover"
          class="ta-controls-popover"
          role="dialog"
          aria-modal="false"
          @click.stop
          @keydown.esc.stop.prevent="showSettingsPopover = false"
        >
          <div class="ta-popover-title">设置</div>
          <div class="ta-mode-row">
            <span>枢纽气泡</span>
            <el-switch v-model="showHubs" size="small" aria-label="显示枢纽气泡" />
          </div>
          <div class="ta-mode-row">
            <span>流动光带</span>
            <el-switch
              v-model="showFlowMotion"
              size="small"
              aria-label="显示换乘客流流动光带"
              :disabled="activeModule !== 'hub' || selection.hubId < 0"
            />
          </div>
          <p class="ta-mode-hint">虚线为公交乘车段，实线为站间接驳段。橙色表示公交→地铁，蓝色表示地铁→公交；短光带只强调高流量链路，并在接驳公交站停顿后进入下一段。系统“减少动态效果”开启时自动停用。</p>
        </div>
      </Transition>
    </div>

    <!-- 左下角图例（外框沿用客流分析 map-legend-card；仅说明，不含色阶调节） -->
    <div v-if="legend.rows.length" class="ta-map-legend" @click.stop>
      <div class="ta-legend-card">
        <div class="ta-legend-head">
          <span class="ta-legend-title">{{ legend.title }}</span>
        </div>
        <div v-for="(row, i) in legend.rows" :key="i" class="ta-legend-row">
          <span class="ta-legend-sym" aria-hidden="true">
            <!-- 面积＝量：一大一小两个圈 -->
            <template v-if="row.kind === 'size'">
              <span class="ta-sym-dot sm" :style="{ borderColor: row.color }"></span>
              <span class="ta-sym-dot lg" :style="{ borderColor: row.color }"></span>
            </template>
            <!-- 色带：绿→红渐变条 -->
            <span v-else-if="row.kind === 'ramp'" class="ta-sym-ramp" :style="{ background: rampGradient }"></span>
            <!-- 外围公交乘车段：连续虚线，线宽仍表示人次 -->
            <span v-else-if="row.kind === 'ride'" class="ta-sym-ride" :style="{ '--ta-flow-color': row.color }"></span>
            <!-- 有向客流：光点沿线移动，静态图例用亮线+端点代替 -->
            <span v-else-if="row.kind === 'flow'" class="ta-sym-flow" :style="{ '--ta-flow-color': row.color }"></span>
            <!-- 站间接驳段：带白热内芯的短光带 -->
            <span v-else-if="row.kind === 'transfer'" class="ta-sym-transfer" :style="{ '--ta-flow-color': row.color }"></span>
          </span>
          <span class="ta-legend-label">{{ row.label }}</span>
        </div>
      </div>
    </div>

    <!-- 图表点击全屏（效仿客流分析）：单实例，各 section 经 ctx.openChartFullscreen 打开 -->
    <ChartFullscreenDialog ref="chartZoomRef" />
  </div>
</template>

<script setup>
import { computed, defineAsyncComponent, inject, nextTick, onActivated, onDeactivated, onMounted, onUnmounted, provide, reactive, ref, shallowRef, watch } from "vue";
import { saveAs } from "file-saver";
import { loadModel } from "@/api/scheme.js";
import { getTransferSummary } from "@/api/transfer.js";
import { dataCenter } from "@/api/data.js";
import { getCachedLineAll, getCachedTransferDict, getCachedTransferEvents, getCachedTransferSummary } from "@/utils/modelDataCache.js";
import { useModelSelectionStore } from "@/stores/modelSelection.js";
import { useModelRuntimeStore } from "@/stores/modelRuntime.js";
import { useDisplayRangeStore } from "@/stores/displayRange.js";
import { MAP_THEME } from "@/utils/mapTheme.js";
import { isMetroLine } from "@/utils/transitMode.js";
import { getCachedAdminDistricts } from "@/utils/realDataCache.js";
import {
  activeDistrictContext,
  districtOutlineFeatureCollection,
  districtNamesFromCollection,
  emptyFeatureCollection as emptyDistrictCollection,
  normalizeAdminDistrictCollection,
  pointInDistrictContext,
} from "@/utils/adminDistrictRange.js";
import { lngLatToWebMercator, webMercatorToLngLat } from "@/mymap/main/MyMap.js";
import { TransferLayerManager, emptyFeatureCollection, rampColorFor, timeRampColors } from "./layers/transferLayers.js";
import { HubFlowDeckLayerManager, buildHubFlowPaths } from "./layers/hubFlowDeckLayers.js";
import { clipLineFeatureToDistrict, createBoundaryIndex } from "../datamanagement/districtFilterCore.js";
import { fmtCount } from "./chartOptions.js";
import "../datamanagement/tokens.css";

const OverviewSection = defineAsyncComponent(() => import("./sections/OverviewSection.vue"));
const HubSection = defineAsyncComponent(() => import("./sections/HubSection.vue"));
const FeederSection = defineAsyncComponent(() => import("./sections/FeederSection.vue"));
const ChartFullscreenDialog = defineAsyncComponent(() => import("./ChartFullscreenDialog.vue"));

// KeepAlive 缓存名（MapLayout include）
defineOptions({
  name: "TransferAnalysis",
});

const MapRef = inject("MapRef", null);
// 全平台共享同一个"当前模型"选择（datavisualization 为既有全局键，运行监测/客流分析/场景编辑同源），
// 模块间切换保持模型不变
const MODEL_SELECTION_KEY = "datavisualization";

/* ================= 模块导航 ================= */

const NAV = [
  {
    key: "overview",
    label: "换乘总览",
    icon: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"></circle><path d="M12 3v9l6 3"></path></svg>',
  },
  {
    key: "hub",
    label: "换乘站点分析",
    icon: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3.4"></circle><path d="M12 2.5v4"></path><path d="M12 17.5v4"></path><path d="M2.5 12h4"></path><path d="M17.5 12h4"></path></svg>',
  },
  {
    key: "feeder",
    label: "换乘线路分析",
    icon: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="5" width="18" height="12" rx="2"></rect><circle cx="7.5" cy="11" r="1.1"></circle><circle cx="16.5" cy="11" r="1.1"></circle><path d="M6 17v2"></path><path d="M18 17v2"></path></svg>',
  },
];
const activeModule = ref("overview");
const activeNav = computed(() => NAV.find((n) => n.key === activeModule.value) || NAV[0]);
const SECTION_MAP = { overview: OverviewSection, hub: HubSection, feeder: FeederSection };
const activeSectionComp = computed(() => SECTION_MAP[activeModule.value] || OverviewSection);
const showHeaderTimeFilters = computed(() => {
  if (activeModule.value === "hub") return selection.hubId >= 0;
  if (activeModule.value === "feeder") return selection.metroLineId >= 0;
  return true;
});

const leftCollapsed = ref(false);
const rightCollapsed = ref(false);

/* ================= 方案 / 模型选择（共享全局当前模型键，固定仿真源） ================= */

const modelSelectionStore = useModelSelectionStore();
const modelRuntime = useModelRuntimeStore();
const restored = modelSelectionStore.getSelection(MODEL_SELECTION_KEY);

const DATA_SOURCE_OPTIONS = [
  { value: "simulation", label: "仿真" },
  { value: "real", label: "真实" },
];
// 本页固定以仿真源进入（真实数据为占位）；不跟随共享键里的 sourceMode，避免其他页面的"真实"模式串页
const dataSourceMode = ref("simulation");
const isSimulationMode = computed(() => dataSourceMode.value === "simulation");

const schemeList = ref([]);
const schemesLoading = ref(false);
const models = ref([]);
const modelsLoading = ref(false);
const area = ref(restored.scheme || "");
const modelName = ref(restored.model || "");

watch([dataSourceMode, area, modelName], () => {
  // 只在仿真模式下回写全局当前模型；本页切到"真实"（占位）不影响其他页面的模型选择
  if (!isSimulationMode.value) return;
  modelSelectionStore.setSelection(MODEL_SELECTION_KEY, { sourceMode: "simulation", scheme: area.value, model: modelName.value });
});

function modelLabel(m) {
  return m.displayName || (m.name || "").split("/").pop() || m.name;
}
// 模型选项的就绪徽标（只读展示，与运行监测口径一致）
function modelReadyTag(m) {
  if (m?.loadStatus && m?.cacheStatus === "ready") return { type: "success", text: "就绪" };
  if (m?.loadStage === "failed" || m?.cacheStatus === "failed") return { type: "danger", text: "失败" };
  if (m?.loadStatus || m?.cacheStatus === "building" || m?.cacheStatus === "queued") return { type: "warning", text: "生成中" };
  return { type: "info", text: "未加载" };
}

async function loadSchemes() {
  schemesLoading.value = true;
  try {
    schemeList.value = await modelRuntime.fetchSchemes();
    if (!area.value && schemeList.value.length) area.value = schemeList.value[0];
  } catch (error) {
    schemeList.value = [];
  } finally {
    schemesLoading.value = false;
  }
}

let modelsRequestSeq = 0;
async function loadModels() {
  const requestedArea = area.value;
  const seq = ++modelsRequestSeq;
  if (!requestedArea) return;
  modelsLoading.value = true;
  try {
    const list = await modelRuntime.fetchModels(requestedArea);
    if (seq !== modelsRequestSeq || requestedArea !== area.value) return;
    models.value = list;
    if (!modelName.value || !models.value.find((m) => m.name === modelName.value)) {
      const loaded = models.value.find((m) => m.loadStatus && m.cacheStatus === "ready");
      modelName.value = loaded?.name || models.value[0]?.name || "";
    }
  } catch (error) {
    if (seq === modelsRequestSeq) models.value = [];
  } finally {
    if (seq === modelsRequestSeq) modelsLoading.value = false;
  }
}
function onAreaChange() {
  modelName.value = "";
  models.value = [];
  loadModels();
}

/* ================= 数据管线：模型就绪 → summary → dict+bin → worker ================= */

const transferPhase = ref("idle"); // idle|model|summary|generating|bundle|worker|ready
// 加载门延迟 400ms 显现：缓存命中的快速装载不闪窗，真正的慢路径（模型加载/缓存生成）才出现
const gateRevealed = ref(false);
let gateRevealTimer = 0;
watch(
  transferPhase,
  (phase) => {
    const loading = phase !== "ready" && phase !== "idle";
    if (!loading) {
      gateRevealed.value = false;
      if (gateRevealTimer) {
        clearTimeout(gateRevealTimer);
        gateRevealTimer = 0;
      }
      return;
    }
    if (gateRevealed.value || gateRevealTimer) return;
    gateRevealTimer = setTimeout(() => {
      gateRevealTimer = 0;
      gateRevealed.value = transferPhase.value !== "ready" && transferPhase.value !== "idle";
    }, 400);
  },
  { immediate: true },
);
const gateError = ref("");
const gateProgress = ref("");
const summary = shallowRef(null);
const dict = shallowRef(null);
const metroNetwork = shallowRef(emptyFeatureCollection());

const gateNote = computed(() => {
  switch (transferPhase.value) {
    case "model":
      return "模型正在后台加载";
    case "summary":
      return "读取换乘汇总";
    case "generating":
      return "换乘缓存生成中";
    case "bundle":
      return "下载换乘事件表";
    case "worker":
      return "解码与索引事件表";
    default:
      return "准备中";
  }
});
const gateSub = computed(() => gateProgress.value);

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
function pollDelay(attempt) {
  if (attempt < 10) return 1000;
  if (attempt < 30) return 2000;
  return 5000;
}

let pipelineSeq = 0;
async function startPipeline() {
  const name = modelName.value;
  const seq = ++pipelineSeq;
  gateError.value = "";
  gateProgress.value = "";
  summary.value = null;
  dict.value = null;
  metroNetwork.value = emptyFeatureCollection();
  layerMgr?.setMetroNetwork(metroNetwork.value);
  agg.value = null;
  hubOptions.value = [];
  metroLineOptions.value = [];
  if (!isSimulationMode.value || !name) {
    transferPhase.value = "idle";
    return;
  }
  try {
    // 1) 模型加载就绪（loadStatus + cacheStatus=ready）
    transferPhase.value = "model";
    let item = models.value.find((m) => m.name === name);
    if (!item || !(item.loadStatus && item.cacheStatus === "ready")) {
      loadModel({ name }, { silentError: true }).catch(() => {});
      const POLL_BUDGET_MS = 2 * 3600 * 1000;
      const startedAt = Date.now();
      for (let attempt = 0; Date.now() - startedAt < POLL_BUDGET_MS; attempt++) {
        if (seq !== pipelineSeq) return;
        const list = await modelRuntime.fetchModels(area.value, { force: true });
        if (seq !== pipelineSeq) return;
        if (list.length) models.value = list;
        item = list.find((m) => m.name === name);
        if (!item) throw new Error("模型不存在或已被移除");
        if (item.loadStatus && item.cacheStatus === "ready") break;
        if (item.loadStage === "failed") throw new Error(item.loadMessage || "模型加载失败");
        if (item.cacheStatus === "failed") throw new Error(item.cacheMessage || "缓存生成失败");
        gateProgress.value = item.cacheProgressMessage || item.cacheMessage || item.loadMessage || "模型正在后台准备";
        await sleep(pollDelay(attempt));
      }
      if (!(item?.loadStatus && item?.cacheStatus === "ready")) throw new Error("模型缓存仍在后台生成，请稍后再试");
    }
    if (seq !== pipelineSeq) return;
    setMapCenter(name);

    // 2) 换乘汇总（未就绪 status=generating 时轮询；generating 不落前端缓存，重取即重查）
    transferPhase.value = "summary";
    gateProgress.value = "";
    let sum = await getCachedTransferSummary(name);
    if (seq !== pipelineSeq) return;
    if (sum?.status === "generating") {
      transferPhase.value = "generating";
      const GEN_BUDGET_MS = 20 * 60 * 1000;
      const startedAt = Date.now();
      while (Date.now() - startedAt < GEN_BUDGET_MS) {
        await sleep(4000);
        if (seq !== pipelineSeq) return;
        const res = await getTransferSummary({ datasource: name }, { silentError: true });
        if (seq !== pipelineSeq) return;
        sum = res?.data || null;
        if (sum && sum.status !== "generating") break;
        gateProgress.value = "后端正在识别换乘事件并构建缓存，完成后自动刷新";
      }
      if (!sum || sum.status === "generating") throw new Error("换乘缓存生成超时，请稍后重试");
    }
    if (!sum || sum.status === "error") throw new Error("换乘汇总获取失败");
    summary.value = sum;

    // 3) 字典 + 事件表 + 地铁线网几何并行。线网复用客流分析已有的 lineAll 模型缓存；
    // 线网失败不阻断换乘统计，避免纯展示工件拖垮主流程。
    transferPhase.value = "bundle";
    const [dictData, buffer, lines] = await Promise.all([
      getCachedTransferDict(name),
      getCachedTransferEvents(name, String(sum.version || "")),
      getCachedLineAll(name).catch(() => []),
    ]);
    if (seq !== pipelineSeq) return;
    if (!dictData || dictData.status === "generating") throw new Error("换乘字典未就绪，请稍后重试");
    if (!(buffer instanceof ArrayBuffer)) throw new Error("换乘事件表下载失败");
    dict.value = dictData;
    metroNetwork.value = buildMetroNetworkFeatureCollection(lines, dictData.metroLines);
    layerMgr?.setMetroNetwork(metroNetwork.value);

    // 4) Worker 解码 + 全局索引（buffer 已入模型级缓存，结构化克隆传递，不可转移所有权）
    transferPhase.value = "worker";
    const ready = await postWorker({ type: "init", buffer, dict: null });
    if (seq !== pipelineSeq) return;
    hubOptions.value = (ready.hubsSorted || []).map((h) => ({ ...h, name: hubName(h.idx) }));
    metroLineOptions.value = (ready.metroLinesSorted || []).map((line) => ({ ...line, name: metroLineName(line.idx) }));

    transferPhase.value = "ready";
    requestAggregate();
  } catch (error) {
    if (seq !== pipelineSeq) return;
    gateError.value = String(error?.message || error || "数据装载失败");
  }
}
watch([modelName, dataSourceMode], () => startPipeline());

let centerRequestSeq = 0;
async function setMapCenter(name) {
  const seq = ++centerRequestSeq;
  if (selectedDisplayRange.value !== DISPLAY_RANGE_ALL) {
    if (activeRangeContext.value) fitRangeContext();
    return;
  }
  try {
    const res = await dataCenter({ datasource: name }, { silentError: true });
    if (seq !== centerRequestSeq) return;
    const x = Number(res?.data?.x);
    const y = Number(res?.data?.y);
    if (Number.isFinite(x) && Number.isFinite(y)) MapRef?.value?.setCenter([x, y]);
  } catch (error) {
    /* 静默：地图定位失败不阻断面板 */
  }
}

/** 复用客流分析 lineAll 的真实线路走向，只保留地铁制式。 */
function buildMetroNetworkFeatureCollection(lines, metroLines = []) {
  const features = [];
  const metroLineIndexById = new Map(
    (Array.isArray(metroLines) ? metroLines : []).map((line, idx) => [String(line?.lineId ?? ""), idx]),
  );
  const appendLngLat = (coords, point) => {
    const x = Number(Array.isArray(point) ? point[0] : point?.x);
    const y = Number(Array.isArray(point) ? point[1] : point?.y);
    if (!Number.isFinite(x) || !Number.isFinite(y)) return;
    const lngLat = webMercatorToLngLat(x, y);
    if (!lngLat.every(Number.isFinite)) return;
    const previous = coords[coords.length - 1];
    if (!previous || previous[0] !== lngLat[0] || previous[1] !== lngLat[1]) coords.push(lngLat);
  };

  for (const line of Array.isArray(lines) ? lines : []) {
    if (!isMetroLine(line)) continue;
    const lineId = String(line?.lineId ?? "");
    (Array.isArray(line?.routes) ? line.routes : []).forEach((route, routeIndex) => {
      const coordinates = [];
      const geometry = Array.isArray(route?.geometry) ? route.geometry : [];
      const links = Array.isArray(route?.links) ? route.links : [];
      if (geometry.length >= 2) {
        geometry.forEach((point) => appendLngLat(coordinates, point));
      } else if (links.length) {
        appendLngLat(coordinates, links[0]?.from);
        links.forEach((link) => appendLngLat(coordinates, link?.to));
      } else {
        (Array.isArray(route?.facilities) ? route.facilities : []).forEach((facility) =>
          appendLngLat(coordinates, facility?.coord));
      }
      if (coordinates.length < 2) return;
      features.push({
        type: "Feature",
        id: `ta-metro-${lineId}-${String(route?.routeId ?? routeIndex)}`,
        geometry: { type: "LineString", coordinates },
        properties: {
          lineId,
          lineName: String(line?.lineName || lineId),
          metroLineIdx: metroLineIndexById.get(lineId) ?? -1,
        },
      });
    });
  }
  return { type: "FeatureCollection", features };
}

/* ================= Worker 客户端 ================= */

let taWorker = null;
let taWorkerBroken = false;
let nextRequestId = 0;
const workerPending = new Map();

function ensureWorker() {
  if (taWorkerBroken) return null;
  if (taWorker) return taWorker;
  try {
    taWorker = new Worker(new URL("./transferData.worker.js", import.meta.url), { type: "module" });
  } catch (error) {
    taWorkerBroken = true;
    return null;
  }
  taWorker.onmessage = (event) => {
    const msg = event.data || {};
    const pending = workerPending.get(msg.requestId);
    if (!pending) return;
    workerPending.delete(msg.requestId);
    if (msg.type === "error") pending.reject(new Error(msg.message || "worker error"));
    else pending.resolve(msg);
  };
  taWorker.onerror = () => {
    taWorkerBroken = true;
    workerPending.forEach((p) => p.reject(new Error("transfer worker failed")));
    workerPending.clear();
    taWorker?.terminate();
    taWorker = null;
    gateError.value = "换乘聚合线程异常，请重试";
  };
  return taWorker;
}

function postWorker(payload) {
  const worker = ensureWorker();
  if (!worker) return Promise.reject(new Error("transfer worker unavailable"));
  const requestId = ++nextRequestId;
  return new Promise((resolve, reject) => {
    workerPending.set(requestId, { resolve, reject });
    try {
      worker.postMessage({ ...payload, requestId });
    } catch (error) {
      workerPending.delete(requestId);
      reject(error);
    }
  });
}

/* ================= 行政区显示范围（复用平台共享 adminDistrictRange，与运行监测/数据管理同款交互） =================
   口径：把选区内的地铁枢纽索引集合并入 hubIds 过滤，全部统计与地图气泡随之过滤。 */

const DISPLAY_RANGE_ALL = "全市";
const DISPLAY_AREA_NAME = "广州市";

const showRangePopover = ref(false);
// 行政区选区跨模块联动（数据管理/运行监测/客流分析/换乘分析共用 displayRange store）
const displayRangeStore = useDisplayRangeStore();
const selectedDisplayRange = computed({
  get: () => displayRangeStore.selected,
  set: (value) => displayRangeStore.set(value),
});
const displayRangeList = ref([DISPLAY_RANGE_ALL]);
const isLoadingDisplayRanges = ref(false);
const displayRangeError = ref("");
const adminDistrictCollection = shallowRef(emptyDistrictCollection());
let displayRangeRequestSeq = 0;

const displayRangeOptions = computed(() => {
  const names = [];
  const seen = new Set();
  for (const item of displayRangeList.value) {
    const name = String(item || "").trim();
    if (!name || name === DISPLAY_RANGE_ALL || seen.has(name)) continue;
    seen.add(name);
    names.push(name);
  }
  return names;
});
const activeRangeContext = computed(() =>
  activeDistrictContext(adminDistrictCollection.value, selectedDisplayRange.value, DISPLAY_RANGE_ALL),
);
const activeMetroNetwork = computed(() => {
  const context = activeRangeContext.value;
  const collection = metroNetwork.value;
  if (!context || !collection?.features?.length) return collection;
  const boundaryIndex = createBoundaryIndex(context);
  if (!boundaryIndex) return emptyFeatureCollection();
  return {
    type: "FeatureCollection",
    features: collection.features.flatMap((feature) => clipLineFeatureToDistrict(feature, context, boundaryIndex)),
  };
});
/** 线路分析选中态使用未裁剪的原始线网，确保行政区模式下仍高亮整条地铁线路。 */
const selectedMetroNetwork = computed(() => {
  if (activeModule.value !== "feeder" || selection.metroLineId < 0) return null;
  return {
    type: "FeatureCollection",
    features: (metroNetwork.value?.features || []).filter(
      (feature) => Number(feature?.properties?.metroLineIdx) === selection.metroLineId,
    ),
  };
});
const displayRangeButtonTitle = computed(() =>
  selectedDisplayRange.value === DISPLAY_RANGE_ALL
    ? "选择行政区显示范围"
    : `显示范围：${selectedDisplayRange.value}，点击恢复全市`,
);

// 区内地铁枢纽索引集合：按 (行政区, 模型) 记忆化；站点量级数千，点在多边形内判定一次性完成
let districtSetCacheKey = "";
const districtSetCache = new Map();

function districtStationSet() {
  const context = activeRangeContext.value;
  const d = dict.value;
  if (!context || !d) return null;
  const cacheKey = `${context.name}|${modelName.value}`;
  if (districtSetCacheKey !== cacheKey) {
    districtSetCacheKey = cacheKey;
    districtSetCache.clear();
  }
  if (!districtSetCache.has("metro")) {
    const items = d.hubs;
    const set = new Set();
    (items || []).forEach((item, idx) => {
      const lngLat = webMercatorToLngLat(Number(item?.x), Number(item?.y));
      if (lngLat.every(Number.isFinite) && pointInDistrictContext(lngLat, context)) set.add(idx);
    });
    districtSetCache.set("metro", set);
  }
  return districtSetCache.get("metro");
}

function clearHubSelectionOutsideRange() {
  const rangeSet = districtStationSet();
  if (rangeSet && selection.hubId >= 0 && !rangeSet.has(selection.hubId)) selection.hubId = -1;
}

async function loadDisplayRanges(options = {}) {
  const { force = false } = options;
  const seq = ++displayRangeRequestSeq;
  isLoadingDisplayRanges.value = true;
  displayRangeError.value = "";
  try {
    const data = await getCachedAdminDistricts(DISPLAY_AREA_NAME, { force });
    if (seq !== displayRangeRequestSeq) return;
    adminDistrictCollection.value = normalizeAdminDistrictCollection(data?.collection);
    const names = Array.isArray(data?.districts)
      ? data.districts.map((item) => String(item || "").trim()).filter(Boolean)
      : districtNamesFromCollection(adminDistrictCollection.value);
    displayRangeList.value = [
      DISPLAY_RANGE_ALL,
      ...names.filter((name, index, list) => name !== DISPLAY_RANGE_ALL && list.indexOf(name) === index),
    ];
    syncDistrictOutline();
    if (!displayRangeList.value.includes(selectedDisplayRange.value)) {
      selectedDisplayRange.value = DISPLAY_RANGE_ALL;
    } else if (selectedDisplayRange.value !== DISPLAY_RANGE_ALL) {
      // 恢复的选区在行政区几何到位后需要重算站点集合、重聚合并取景
      districtSetCacheKey = "";
      districtSetCache.clear();
      clearHubSelectionOutsideRange();
      fitRangeContext();
      requestAggregate();
    }
  } catch (error) {
    if (seq !== displayRangeRequestSeq) return;
    adminDistrictCollection.value = emptyDistrictCollection();
    displayRangeList.value = [DISPLAY_RANGE_ALL];
    selectedDisplayRange.value = DISPLAY_RANGE_ALL;
    syncDistrictOutline();
    displayRangeError.value = error?.message || "行政区范围加载失败";
  } finally {
    if (seq === displayRangeRequestSeq) isLoadingDisplayRanges.value = false;
  }
}

function fitRangeContext() {
  const context = activeRangeContext.value;
  if (!context || !MapRef?.value) return;
  const [minLng, minLat, maxLng, maxLat] = context.bounds || [];
  const points = [lngLatToWebMercator(minLng, minLat), lngLatToWebMercator(maxLng, maxLat)].filter(
    (point) => Array.isArray(point) && point.every(Number.isFinite),
  );
  if (points.length < 2) return;
  MapRef.value.setFitZoomAndCenterByPoints?.(points);
}

function toggleRangePopover() {
  // 与运行监测同款交互：已选区时点按钮直接恢复全市
  if (selectedDisplayRange.value !== DISPLAY_RANGE_ALL) {
    selectedDisplayRange.value = DISPLAY_RANGE_ALL;
    showRangePopover.value = false;
    return;
  }
  if (!showRangePopover.value) {
    showSettingsPopover.value = false;
    if (!displayRangeOptions.value.length && !isLoadingDisplayRanges.value) {
      loadDisplayRanges({ force: Boolean(displayRangeError.value) });
    }
  }
  showRangePopover.value = !showRangePopover.value;
}

function selectDisplayRange(rangeName) {
  const next = String(rangeName || "").trim();
  if (next && next !== selectedDisplayRange.value) selectedDisplayRange.value = next;
  showRangePopover.value = false;
}

// 持久化由 displayRange store 统一负责；此处只处理本页联动副作用
watch(selectedDisplayRange, () => {
  // 作废可能仍在途的模型中心请求，防止行政区刚取景后又被异步拉回全市。
  centerRequestSeq += 1;
  districtSetCacheKey = "";
  districtSetCache.clear();
  clearHubSelectionOutsideRange();
  syncDistrictOutline();
  fitRangeContext();
});


/* ================= 筛选状态与聚合编排 ================= */

const filters = reactive({ dirSel: -1, timeRange: [0, 24], unitMin: 60, topN: 10, longMin: 15 });
const selection = reactive({
  hubId: -1,
  metroLineId: -1,
});

const agg = shallowRef(null);
const aggBusy = ref(false);
const hubOptions = shallowRef([]);
const metroLineOptions = shallowRef([]);

function filtersFor(module) {
  const base = {
    dirSel: filters.dirSel,
    startHour: filters.timeRange[0],
    endHour: filters.timeRange[1],
    unitMin: filters.unitMin,
    topN: filters.topN,
    longMin: filters.longMin,
    hubId: -1,
    busLineId: -1,
    routeIdx: -1,
    metroLineId: -1,
    busLineIds: null,
    hubIds: null,
  };
  if (
    (activeModule.value === "hub" && selection.hubId < 0)
    || (activeModule.value === "feeder" && selection.metroLineId < 0)
  ) {
    base.startHour = 0;
    base.endHour = 24;
    base.unitMin = 60;
  }
  if (module === "hub" && selection.hubId >= 0) base.hubId = selection.hubId;
  if (module === "feeder" && selection.metroLineId >= 0) base.metroLineId = selection.metroLineId;
  // 行政区显示范围：区内站点集合并入 hubIds（作用于当前分组键）；
  // 与接驳模块自身的枢纽多选取交集，空交集传 [-1] 保证空结果而非放开过滤
  const districtSet = districtStationSet();
  if (districtSet) {
    const merged = Array.isArray(base.hubIds) && base.hubIds.length
      ? base.hubIds.filter((id) => districtSet.has(id))
      : Array.from(districtSet);
    base.hubIds = merged.length ? merged : [-1];
  }
  return base;
}

/** 模块未选对象时降级为全网聚合（面板显示选择提示，地图仍有全网可看） */
function effectiveModule() {
  const m = activeModule.value;
  if (m === "hub" && selection.hubId < 0) return "overview";
  if (m === "feeder" && selection.metroLineId < 0) return "overview";
  return m;
}

let aggregateSeq = 0;
async function requestAggregate() {
  if (transferPhase.value !== "ready") return;
  const seq = ++aggregateSeq;
  const module = effectiveModule();
  aggBusy.value = true;
  try {
    const msg = await postWorker({ type: "aggregate", module, filters: filtersFor(module) });
    if (seq !== aggregateSeq) return;
    agg.value = msg.payload;
    updateMapLayers(msg.payload);
  } catch (error) {
    if (seq !== aggregateSeq) return;
    agg.value = null;
  } finally {
    if (seq === aggregateSeq) aggBusy.value = false;
  }
}

// 筛选/选择/模块变化 → 防抖聚合（Worker 内有记忆化，重复键零成本）
const aggregateKey = computed(() =>
  JSON.stringify([
    activeModule.value,
    selectedDisplayRange.value,
    filters.dirSel,
    filters.timeRange,
    filters.unitMin,
    filters.topN,
    filters.longMin,
    selection.hubId,
    selection.metroLineId,
  ]),
);
let aggregateTimer = null;
watch(aggregateKey, () => {
  if (aggregateTimer) clearTimeout(aggregateTimer);
  aggregateTimer = setTimeout(() => {
    aggregateTimer = null;
    requestAggregate();
  }, 180);
});
/* ================= 名称 / 原模型数量 / 导出等共享工具 ================= */

// 历史接口保留 expand 这个内部函数名，行为固定为 1:1；严禁使用 desc.scale 放大模型数量。
function expand(v) {
  return Math.round(Number(v) || 0);
}
function hubName(idx) {
  return dict.value?.hubs?.[idx]?.name || `枢纽 ${idx}`;
}
function busLineName(idx) {
  return dict.value?.busLines?.[idx]?.name || `线路 ${idx}`;
}
function metroLineName(idx) {
  return dict.value?.metroLines?.[idx]?.name || `地铁 ${idx}`;
}
function busStopName(idx) {
  return dict.value?.busStops?.[idx]?.name || `站点 ${idx}`;
}
function metroStopName(idx) {
  return dict.value?.metroStops?.[idx]?.name || `地铁站 ${idx}`;
}

function goHub(idx) {
  if (idx == null || idx < 0) return;
  selection.hubId = idx;
  if (activeModule.value !== "hub") activeModule.value = "hub";
}

function goMetroLine(idx) {
  if (idx == null || idx < 0) return;
  selection.metroLineId = idx;
  if (activeModule.value !== "feeder") activeModule.value = "feeder";
}

/* ---------- 地图左上角对象搜索：站点 / 地铁线路共用同一交互 ---------- */

const hubSearchKeyword = ref("");
const metroLineSearchKeyword = ref("");
const mapSearchFocused = ref(false);
const mapSearchVisible = computed(() =>
  transferPhase.value === "ready" && (activeModule.value === "hub" || activeModule.value === "feeder"),
);
const mapSearchKeyword = computed({
  get: () => activeModule.value === "feeder" ? metroLineSearchKeyword.value : hubSearchKeyword.value,
  set: (value) => {
    if (activeModule.value === "feeder") metroLineSearchKeyword.value = value;
    else hubSearchKeyword.value = value;
  },
});
const mapSearchPlaceholder = computed(() => activeModule.value === "feeder" ? "搜索地铁线路" : "搜索地铁站");
const mapSearchAriaLabel = computed(() => mapSearchPlaceholder.value);
const mapSearchResultType = computed(() => activeModule.value === "feeder" ? "地铁线路" : "地铁站");
const mapSearchSelectedId = computed(() =>
  activeModule.value === "feeder" ? selection.metroLineId : selection.hubId,
);

/** 候选：地铁枢纽全局按换乘量排序。 */
const hubSearchCandidates = computed(() => {
  const rangeSet = districtStationSet();
  return rangeSet ? hubOptions.value.filter((hub) => rangeSet.has(hub.idx)) : hubOptions.value;
});
const hubSearchResults = computed(() => {
  const kw = hubSearchKeyword.value.trim().toLowerCase();
  const list = kw
    ? hubSearchCandidates.value.filter((h) => (h.name || "").toLowerCase().includes(kw))
    : hubSearchCandidates.value;
  return list.slice(0, 20);
});
const metroLineSearchResults = computed(() => {
  const kw = metroLineSearchKeyword.value.trim().toLowerCase();
  const list = kw
    ? metroLineOptions.value.filter((line) => (line.name || "").toLowerCase().includes(kw))
    : metroLineOptions.value;
  return list.slice(0, 20);
});
const mapSearchResults = computed(() =>
  activeModule.value === "feeder" ? metroLineSearchResults.value : hubSearchResults.value,
);
function selectMapSearchResult(r) {
  if (!r || r.idx == null) return;
  if (activeModule.value === "feeder") selection.metroLineId = r.idx;
  else selection.hubId = r.idx;
  mapSearchFocused.value = false;
}
function selectFirstMapSearchResult() {
  selectMapSearchResult(mapSearchResults.value[0]);
}
function clearMapSearch() {
  if (activeModule.value === "feeder") {
    metroLineSearchKeyword.value = "";
    selection.metroLineId = -1;
  } else {
    hubSearchKeyword.value = "";
    selection.hubId = -1;
  }
}
// 其他入口（Top榜/地图气泡/明细行点击）选中对象时，搜索框同步显示名称；取消选中则清空
watch(
  () => selection.hubId,
  (id) => {
    hubSearchKeyword.value = id >= 0 ? hubName(id) : "";
  },
);
watch(
  () => selection.metroLineId,
  (id) => {
    metroLineSearchKeyword.value = id >= 0 ? metroLineName(id) : "";
  },
);
watch(activeModule, () => {
  mapSearchFocused.value = false;
  if (selectedDisplayRange.value !== DISPLAY_RANGE_ALL) {
    nextTick(() => setMapCenter(modelName.value));
  }
});
// CSV：UTF-8 BOM 防 Excel 中文乱码；含逗号/引号/换行按 RFC 4180 转义（平台先例）
function csvEscape(value) {
  const text = String(value ?? "");
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}
function exportCsv(rows, fileName) {
  const meta = "# 口径：换乘事件为 30min+800m 时间—空间规则推定；数量为模型原始值（不扩样）；分时按后序上车时刻\r\n";
  const content = rows.map((row) => row.map(csvEscape).join(",")).join("\r\n");
  saveAs(new Blob(["\uFEFF", meta, content], { type: "text/csv;charset=utf-8" }), fileName);
}

const prefersReducedMotion =
  typeof window !== "undefined" && typeof window.matchMedia === "function"
    ? window.matchMedia("(prefers-reduced-motion: reduce)").matches
    : false;

const chartTheme = {
  busToMetro: MAP_THEME.transfer.busToMetro,
  metroToBus: MAP_THEME.transfer.metroToBus,
  warn: MAP_THEME.transfer.warn,
  heatRamp: timeRampColors(),
  pieColors: [
    MAP_THEME.transfer.metroToBus,
    MAP_THEME.transfer.busToMetro,
    "#33608f",
    "#6366f1",
    "#2f9e6e",
    "#dc4c5d",
    "#8fa8c8",
    "#f59e0b",
  ],
  chart: {},
};

// 图表点击全屏：option 传打开时刻的快照(与 PFA 一致,关窗销毁不联动)
const chartZoomRef = ref(null);
function openChartFullscreen(payload) {
  chartZoomRef.value?.open(payload);
}

provide("taCtx", {
  dict,
  agg,
  busy: aggBusy,
  hubOptions,
  filters,
  selection,
  expand,
  hubName,
  busLineName,
  metroLineName,
  busStopName,
  metroStopName,
  chartTheme,
  animation: !prefersReducedMotion,
  goHub,
  exportCsv,
  openChartFullscreen,
});

/* ================= 地图控件（缩放 / 3D / 指北针 / 行政区 / 设置弹层） ================= */

const is3DActive = ref(false);
const showSettingsPopover = ref(false);
const mapControlsRef = ref(null);

function handleZoomIn() {
  const map = MapRef?.value;
  if (map) map.setZoom(map.zoom + 1);
}
function handleZoomOut() {
  const map = MapRef?.value;
  if (map) map.setZoom(map.zoom - 1);
}
function handleToggle3D() {
  const map = MapRef?.value;
  if (!map) return;
  if (is3DActive.value) {
    map.setPitchAndRotation(90, 0);
    map.enableRotate = false;
    is3DActive.value = false;
    return;
  }
  map.enableRotate = true;
  map.setPitchAndRotation(45, map.rotation);
  is3DActive.value = true;
}
function handleResetCompass() {
  const map = MapRef?.value;
  if (!map) return;
  map.setPitchAndRotation(90, 0);
  map.enableRotate = false;
  is3DActive.value = false;
}
/** 相机为跨页共享状态，进入/回到本页时对齐 3D 按钮点亮态 */
function syncCameraState() {
  const map = MapRef?.value;
  if (map) is3DActive.value = Boolean(map.enableRotate || map.pitch !== 90 || map.rotation !== 0);
}
function toggleSettingsPopover() {
  if (!showSettingsPopover.value) showRangePopover.value = false;
  showSettingsPopover.value = !showSettingsPopover.value;
}
function handleDocClickForPopover(event) {
  if (!showSettingsPopover.value && !showRangePopover.value) return;
  if (mapControlsRef.value?.contains?.(event.target)) return;
  showSettingsPopover.value = false;
  showRangePopover.value = false;
}

/* ================= 地图图层驱动 ================= */

const showHubs = ref(true);
const showFlowMotion = ref(true);

let layerMgr = null;
let hubFlowLayerMgr = null;
let pendingStyleHandler = null;
// 站点详情 fitBounds 去重键：`${kind}:${hubId}`，切站点或切口径都重新取景
let lastFittedHub = "";

function ensureLayerMgr(afterReady) {
  const wrapper = MapRef?.value;
  const map = wrapper?.map;
  if (!map || typeof map.addSource !== "function") return null;
  if (!layerMgr) layerMgr = new TransferLayerManager(wrapper);
  if (!hubFlowLayerMgr) hubFlowLayerMgr = new HubFlowDeckLayerManager(wrapper);
  const ready = typeof map.isStyleLoaded === "function" ? map.isStyleLoaded() : true;
  if (!ready) {
    if (!pendingStyleHandler) {
      pendingStyleHandler = () => {
        pendingStyleHandler = null;
        if (layerMgr?.ensure()) {
          layerMgr.bindHubClick(onHubFeatureClick);
          layerMgr.bindMetroLineClick(onMetroLineFeatureClick);
          layerMgr.bindBackgroundClick(onMapBackgroundClick);
          hubFlowLayerMgr?.setMotionEnabled(showFlowMotion.value);
          syncDistrictOutline(layerMgr);
          if (typeof afterReady === "function") afterReady();
        }
      };
      map.once("load", pendingStyleHandler);
    }
    return null;
  }
  if (layerMgr.ensure()) {
    layerMgr.bindHubClick(onHubFeatureClick);
    layerMgr.bindMetroLineClick(onMetroLineFeatureClick);
    layerMgr.bindBackgroundClick(onMapBackgroundClick);
    hubFlowLayerMgr?.setMotionEnabled(showFlowMotion.value);
    syncDistrictOutline(layerMgr);
  }
  return layerMgr;
}

function syncDistrictOutline(manager = null) {
  const target = manager || layerMgr || ensureLayerMgr();
  if (!target) return;
  const context = activeRangeContext.value;
  target.setDistrict(districtOutlineFeatureCollection(context));
  target.setVisibility("district", Boolean(context));
}

function onHubFeatureClick(props) {
  const idx = Number(props?.idx);
  if (!Number.isFinite(idx) || idx < 0) return;
  goHub(idx);
}

function onMetroLineFeatureClick(props) {
  const idx = Number(props?.metroLineIdx);
  if (!Number.isFinite(idx) || idx < 0) return;
  goMetroLine(idx);
}

// 对象分析：点击地图空白处（未命中气泡或地铁线）取消当前选中，退回全网概览
function onMapBackgroundClick() {
  if (activeModule.value === "hub" && selection.hubId >= 0) {
    selection.hubId = -1;
  } else if (activeModule.value === "feeder" && selection.metroLineId >= 0) {
    selection.metroLineId = -1;
  }
}

function hubLngLat(idx) {
  const h = dict.value?.hubs?.[idx];
  if (!h) return null;
  const c = webMercatorToLngLat(h.x, h.y);
  return c.every(Number.isFinite) ? c : null;
}
function busStopLngLat(idx) {
  const s = dict.value?.busStops?.[idx];
  if (!s) return null;
  const c = webMercatorToLngLat(s.x, s.y);
  return c.every(Number.isFinite) ? c : null;
}
function metroStopLngLat(idx) {
  const s = dict.value?.metroStops?.[idx];
  if (!s) return null;
  const c = webMercatorToLngLat(s.x, s.y);
  return c.every(Number.isFinite) ? c : null;
}

function quantileThresholds(values) {
  const arr = values.filter((v) => Number.isFinite(v)).sort((a, b) => a - b);
  if (!arr.length) return [0, 0, 0, 0];
  const q = (p) => arr[Math.min(arr.length - 1, Math.floor(p * arr.length))];
  return [q(0.2), q(0.4), q(0.6), q(0.8)];
}

function updateMapLayers(payload) {
  const mgr = ensureLayerMgr(() => updateMapLayers(agg.value));
  if (!mgr || !dict.value) return;
  const module = activeModule.value;
  const ramp = timeRampColors();
  const lineFocusMode = module === "feeder" && selection.metroLineId >= 0;
  mgr.setMetroNetwork(
    metroNetwork.value,
    lineFocusMode ? selectedMetroNetwork.value : activeMetroNetwork.value,
  );
  mgr.setMetroLineFocusMode(lineFocusMode);

  if (!payload) {
    mgr.setHubs(emptyFeatureCollection());
    mgr.setFlows([]);
    mgr.setHeat(emptyFeatureCollection());
    mgr.setLinks(emptyFeatureCollection());
    mgr.setOriginLinks(emptyFeatureCollection());
    mgr.setStops(emptyFeatureCollection());
    mgr.setFocusMode(false);
    hubFlowLayerMgr?.setFlows([]);
    return;
  }

  // ---- 站点气泡：大小=换乘量(sqrt)，颜色=时间/长换乘指标 5 级分位色带 ----
  const hubs = payload.hubs || [];
  const metricKey = "avgSec";
  const metricOf = (h) => (metricKey === "longShare" ? h.longShare : h[metricKey]);
  const thresholds = quantileThresholds(hubs.map(metricOf));
  const maxFlow = hubs.reduce((m, h) => Math.max(m, h.flow), 0) || 1;
  const labelTop = new Set(hubs.slice(0, 12).map((h) => h.idx));
  const focusMode = module === "hub" && selection.hubId >= 0;
  const hubFeatures = [];
  hubs.forEach((h) => {
    const coord = hubLngLat(h.idx);
    if (!coord) return;
    const selected = module === "hub" && selection.hubId === h.idx;
    const overLong = false;
    hubFeatures.push({
      type: "Feature",
      geometry: { type: "Point", coordinates: coord },
      properties: {
        idx: h.idx,
        name: hubName(h.idx),
        label: focusMode && !selected ? "" : hubName(h.idx),
        r: selected ? 6.6 : 4 + 20 * Math.sqrt(h.flow / maxFlow),
        color: selected ? MAP_THEME.transfer.stationFill : rampColorFor(metricOf(h), thresholds, ramp),
        opacity: focusMode ? (selected ? 1 : 0.1) : 0.78,
        strokeColor: selected
          ? MAP_THEME.transfer.stationStroke
          : overLong
            ? MAP_THEME.transfer.longStroke
            : MAP_THEME.transfer.hubRing,
        strokeWidth: selected ? 2.4 : overLong ? 2.2 : 1,
        selected: selected ? 1 : 0,
        // 选中枢纽置顶；方向只由线路承担，站点保持中性白心符号。
        sortKey: (selected || overLong ? 100000 : 0) + h.flow,
        labeled: selected || labelTop.has(h.idx) ? 1 : 0,
      },
    });
  });
  mgr.setHubs({ type: "FeatureCollection", features: hubFeatures });

  // ---- 换乘流线已下线：源置空、图层隐藏（infra 保留以便按需恢复） ----
  mgr.setFlows([]);

  // 换乘热力已下线：源保持置空（stops 继续供站点详情的公交段端点圈使用）
  mgr.setHeat(emptyFeatureCollection());

  // ---- 地铁枢纽详情：公交乘车段 + 公交站—地铁站接驳段，两段均保留方向与流量 ----
  if (module === "hub" && selection.hubId >= 0) {
    const links = payload.hubDetail?.stopMetroLinks || [];
    const tripLinks = payload.hubDetail?.busTripLinks || [];
    // 两段链路都交给 Deck 绘制，避免 MapLibre 短线与 Deck 光带在节点处出现双影。
    mgr.setLinks(emptyFeatureCollection());
    mgr.setOriginLinks(emptyFeatureCollection());
    const flowPaths = buildHubFlowPaths({
      tripLinks,
      transferLinks: links,
      busStopCoord: busStopLngLat,
      metroStopCoord: metroStopLngLat,
    });
    const center = hubLngLat(selection.hubId);
    hubFlowLayerMgr?.setFlows(flowPaths);

    const endpointAgg = new Map();
    const addEndpoint = (idx, flow, transfer = false) => {
      const current = endpointAgg.get(idx) || {
        idx,
        flow: 0,
        transfer: false,
      };
      current.flow += flow;
      current.transfer = current.transfer || transfer;
      endpointAgg.set(idx, current);
    };
    flowPaths.forEach((flow) => {
      if (flow.stage === "ride") {
        addEndpoint(
          flow.externalStop,
          flow.flow,
        );
      } else {
        addEndpoint(
          flow.transferStop,
          flow.flow,
          true,
        );
      }
    });
    let externalLabelRank = 0;
    const endpoints = Array.from(endpointAgg.values())
      .sort((a, b) => Number(b.transfer) - Number(a.transfer) || b.flow - a.flow)
      .map((item) => ({
        coord: busStopLngLat(item.idx),
        name: busStopName(item.idx),
        flow: item.flow,
        transfer: item.transfer,
        labeled: item.transfer || externalLabelRank++ < 8,
      }));
    mgr.setStops({
      type: "FeatureCollection",
      features: endpoints
        .map((endpoint) => {
          if (!endpoint.coord) return null;
          return {
            type: "Feature",
            geometry: { type: "Point", coordinates: endpoint.coord },
            properties: {
              name: endpoint.name,
              label: endpoint.labeled ? endpoint.name : "",
              sortKey: endpoint.flow,
              r: endpoint.transfer ? 5.1 : 3.4,
              transfer: endpoint.transfer ? 1 : 0,
              strokeWidth: endpoint.transfer ? 1.9 : 1.35,
            },
          };
        })
        .filter(Boolean),
    });
    const fitKey = `metro:${selection.hubId}`;
    if (lastFittedHub !== fitKey) {
      lastFittedHub = fitKey;
      const coords = [center, ...flowPaths.flatMap((flow) => [flow.source, flow.target])].filter(Boolean);
      mgr.fitTo(coords);
    }
  } else {
    mgr.setLinks(emptyFeatureCollection());
    mgr.setOriginLinks(emptyFeatureCollection());
    mgr.setStops(emptyFeatureCollection());
    hubFlowLayerMgr?.setFlows([]);
    lastFittedHub = "";
  }

  applyLayerVisibility();
}

function applyLayerVisibility() {
  if (!layerMgr) return;
  const module = activeModule.value;
  const focusMode = module === "hub" && selection.hubId >= 0;
  const lineFocusMode = module === "feeder" && selection.metroLineId >= 0;
  layerMgr.setMetroLineFocusMode(lineFocusMode);
  if (!lineFocusMode) layerMgr.setFocusMode(focusMode);
  layerMgr.setVisibility("hubs", showHubs.value);
  layerMgr.setVisibility("metro", !focusMode);
  layerMgr.setVisibility("flows", false);
  // 换乘热力已按需求下线（图层 infra 保留以便恢复）
  layerMgr.setVisibility("heat", false);
  layerMgr.setVisibility("links", module === "hub");
  layerMgr.setVisibility("district", Boolean(activeRangeContext.value));
  hubFlowLayerMgr?.setVisible(focusMode);
  hubFlowLayerMgr?.setMotionEnabled(showFlowMotion.value);
}
watch(showHubs, applyLayerVisibility);
watch(showFlowMotion, (enabled) => hubFlowLayerMgr?.setMotionEnabled(enabled));

/* ================= 左下角图例（仅说明，无色阶调节） ================= */

const rampGradient = computed(() => `linear-gradient(90deg, ${timeRampColors().join(", ")})`);

// 图例只留指标名（符号本身已表达"面积/色带/线宽"的含义）；同符号同名的行去重
const legend = computed(() => {
  if (transferPhase.value !== "ready" || !isSimulationMode.value) return { title: "", rows: [] };
  const module = activeModule.value;
  const t = MAP_THEME.transfer;
  const focusMode = module === "hub" && selection.hubId >= 0;
  const rows = [];
  if (showHubs.value && !focusMode) {
    rows.push({ kind: "size", color: t.hubRing, label: "换乘人次" });
    rows.push({ kind: "ramp", label: "平均换乘时间" });
  }
  if (focusMode) {
    rows.push({ kind: "flow", color: t.busToMetro, label: "公交→地铁" });
    rows.push({ kind: "flow", color: t.metroToBus, label: "地铁→公交" });
    rows.push({ kind: "ride", color: t.hubRing, label: "公交乘车段（虚线，线宽＝人次）" });
    rows.push({ kind: "transfer", color: t.hubFocus, label: "公交站—地铁站接驳段" });
  }
  const seen = new Set();
  return {
    title: "图例",
    rows: rows.filter((row) => {
      const key = `${row.kind}|${row.label}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    }),
  };
});

/* ================= 生命周期 ================= */

onMounted(async () => {
  syncCameraState();
  document.addEventListener("click", handleDocClickForPopover);
  // 恢复了非全市的行政区选区：行政区几何需就位才能过滤统计（列表在首次打开弹层时才懒加载）
  if (selectedDisplayRange.value !== DISPLAY_RANGE_ALL) {
    loadDisplayRanges().catch(() => {});
  }
  // 快路径：全局门禁打开时 modelRuntime 已拉取过方案/模型列表，直接复用即时启动管线，
  // 不再串行等两次列表接口；网络刷新放到后台校对（不阻塞、不闪加载门）
  const cachedSchemes = modelRuntime.schemes || [];
  if (cachedSchemes.length) {
    schemeList.value = [...cachedSchemes];
    if (!area.value || !schemeList.value.includes(area.value)) area.value = schemeList.value[0] || "";
    const cachedModels = modelRuntime.modelsByScheme?.[area.value];
    if (Array.isArray(cachedModels) && cachedModels.length) {
      models.value = cachedModels;
      if (!modelName.value || !models.value.find((m) => m.name === modelName.value)) {
        const loaded = models.value.find((m) => m.loadStatus && m.cacheStatus === "ready");
        modelName.value = loaded?.name || models.value[0]?.name || "";
      }
      if (modelName.value) startPipeline();
      loadSchemes().catch(() => {});
      loadModels().catch(() => {});
      return;
    }
  }
  await loadSchemes();
  await loadModels();
  if (modelName.value) startPipeline();
});

/* ================= KeepAlive 激活/失活 =================
   本页被 MapLayout KeepAlive 缓存：切走不销毁 worker/事件表/聚合结果，
   ta-* 样式图层由 MapLayout 按前缀统一隐藏与恢复。 */

let hasBeenDeactivated = false;

onActivated(() => {
  // 首次挂载走 onMounted 引导
  if (selectedDisplayRange.value !== DISPLAY_RANGE_ALL) {
    nextTick(() => setMapCenter(modelName.value));
  }
  if (!hasBeenDeactivated) return;
  syncCameraState();
  hubFlowLayerMgr?.setVisible(activeModule.value === "hub" && selection.hubId >= 0);
  // 其他页面（运行监测/客流分析）可能切换过全局模型：跟随共享选择，重跑数据管线
  const stored = modelSelectionStore.getSelection(MODEL_SELECTION_KEY);
  if (stored.sourceMode !== "simulation" || !stored.model || stored.model === modelName.value) return;
  if (stored.scheme && stored.scheme !== area.value) {
    area.value = stored.scheme;
    models.value = [];
    modelName.value = stored.model; // watch(modelName) 自动重启管线
    loadModels().catch(() => {});
  } else {
    modelName.value = stored.model;
  }
});

onDeactivated(() => {
  hasBeenDeactivated = true;
  centerRequestSeq += 1;
  showSettingsPopover.value = false;
  showRangePopover.value = false;
  // Deck overlay 不属于 MapLayout 的 ta-* 样式图层暂存，失活时显式摘除并停掉动画。
  hubFlowLayerMgr?.setVisible(false);
});

onUnmounted(() => {
  document.removeEventListener("click", handleDocClickForPopover);
  pipelineSeq++;
  aggregateSeq++;
  modelsRequestSeq++;
  centerRequestSeq++;
  if (aggregateTimer) clearTimeout(aggregateTimer);
  if (gateRevealTimer) {
    clearTimeout(gateRevealTimer);
    gateRevealTimer = 0;
  }
  workerPending.forEach((p) => p.reject(new Error("page disposed")));
  workerPending.clear();
  taWorker?.terminate();
  taWorker = null;
  if (pendingStyleHandler && MapRef?.value?.map?.off) {
    MapRef.value.map.off("load", pendingStyleHandler);
    pendingStyleHandler = null;
  }
  layerMgr?.clear();
  layerMgr = null;
  hubFlowLayerMgr?.clear();
  hubFlowLayerMgr = null;
});
</script>

<style lang="scss">
/* 换乘分析页样式：结构骨架复用 tokens.css（dm-sidebar/dm-overview-panel），
   本页新增元素统一 ta- 前缀。非 scoped 以便 sections 共用，选择器均含 ta- 防泄漏。 */

.ta-wrapper {
  position: relative;
  width: 100%;
  height: 100%;
  pointer-events: none;

  > * {
    pointer-events: auto;
  }

  .sidebar-brand .brand-icon {
    color: var(--dm2-accent);
  }
}

/* ---- 左侧模块导航（通用筛选已迁至右侧面板头部与地图设置弹层） ---- */
.ta-sidebar {
  display: flex;
  flex-direction: column;
}
.ta-pills {
  flex-wrap: wrap;
}

/* ---- 右侧面板 ---- */
.ta-panel {
  display: flex;
  flex-direction: column;
  width: 440px;
  padding: 0;
  overflow: hidden;
}
.ta-head {
  padding: 14px 16px 10px;
  border-bottom: 1px solid var(--dm2-line-faint);
  flex: 0 0 auto;
}
.ta-head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.ta-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--dm2-ink);
  white-space: nowrap;
}
.ta-head-time {
  font-size: 12px;
  color: var(--dm2-ink-soft);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.ta-head-filters {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 6px;
}
.ta-head-slider {
  flex: 1 1 auto;
  min-width: 0;
  padding: 0 6px;
}
.ta-head-pills {
  flex: 0 0 auto;
}
.ta-body {
  flex: 1 1 auto;
  min-height: 0;

  .el-scrollbar__view {
    padding: 12px 14px 20px;
  }
}

/* ---- 状态门 ---- */
.ta-gate {
  padding: 44px 18px;
  text-align: center;
}
.ta-gate-spinner {
  width: 26px;
  height: 26px;
  margin: 0 auto 14px;
  border-radius: 50%;
  border: 3px solid var(--dm2-accent-weak);
  border-top-color: var(--dm2-accent);
  animation: ta-spin 0.9s linear infinite;
}
@keyframes ta-spin {
  to {
    transform: rotate(360deg);
  }
}
@media (prefers-reduced-motion: reduce) {
  .ta-gate-spinner {
    animation: none;
  }
}
.ta-gate-skeleton {
  margin-top: 22px;
  text-align: left;
}
.ta-gate-error .ta-blank-sub {
  color: var(--dm2-delete);
}
.ta-retry {
  margin-top: 14px;
}
.ta-agg-busy {
  height: 2px;
  margin: -6px 0 6px;
  border-radius: 2px;
  background: linear-gradient(90deg, transparent, var(--dm2-accent), transparent);
  background-size: 200% 100%;
  animation: ta-busy 1.1s linear infinite;
}
@keyframes ta-busy {
  from {
    background-position: 200% 0;
  }
  to {
    background-position: -200% 0;
  }
}
@media (prefers-reduced-motion: reduce) {
  .ta-agg-busy {
    animation: none;
  }
}

/* ---- 空态 ---- */
.ta-blank {
  padding: 40px 18px;
  text-align: center;
}
.ta-blank-title {
  margin: 0 0 6px;
  font-size: 14px;
  font-weight: 600;
  color: var(--dm2-ink);
}
.ta-blank-sub {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--dm2-muted);
}

/* ---- KPI ---- */
.ta-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.ta-kpis {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}
.ta-kpi {
  background: var(--dm2-surface-sunken);
  border: 1px solid var(--dm2-line-faint);
  border-radius: var(--dm2-radius-sm);
  padding: 9px 10px 8px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}
.ta-kpi-label {
  font-size: 11px;
  color: var(--dm2-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ta-kpi-value {
  font-size: 16px;
  font-weight: 650;
  color: var(--dm2-accent-strong);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.01em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ta-kpi-unit {
  font-style: normal;
  font-size: 11px;
  font-weight: 400;
  color: var(--dm2-muted);
  margin-left: 3px;
}
.ta-note {
  margin: -2px 0 0;
  font-size: 11px;
  line-height: 1.7;
  color: var(--dm2-muted-soft);
}

/* ---- 卡片与图表 ---- */
.ta-card {
  background: var(--dm2-surface);
  border: 1px solid var(--dm2-line-faint);
  border-radius: var(--dm2-radius-sm);
  padding: 10px 12px 8px;
}
.ta-card-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}
.ta-rank-head {
  align-items: center;
}
.ta-card-actions {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  min-width: 0;
}
.ta-rank-metric {
  width: 126px;
}
.ta-card-title {
  position: relative;
  padding-left: 10px;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: -0.005em;
  color: var(--dm2-ink);
}
/* 标题前的强调竖条：全模块统一，取自唯一强调色令牌 */
.ta-card-title::before {
  content: "";
  position: absolute;
  left: 0;
  top: 0.12em;
  width: 3px;
  height: 0.92em;
  border-radius: 2px;
  background: var(--dm2-accent);
}
.ta-card-hint {
  font-size: 11px;
  color: var(--dm2-muted-soft);
  white-space: nowrap;
}
.ta-chart {
  width: 100%;
  height: 200px;
}
.ta-chart-sm {
  height: 176px;
}
.ta-chart-rank {
  height: 236px;
}
.ta-chart-box {
  height: 240px;
}

/* ---- 换乘总览：免滚动自适应布局 ----
   面板内部高度 = vh/scale − 82（16:9 全屏恒 722、1080p+浏览器栏 ~625、极端小窗 ~570）。
   结构：KPI 单行固定 + 分时/线对两张图弹性分高 + 分布条固定；
   仅在 overview 模块（.ta-body-fill）生效；Hub/Timing/Feeder 内容更长，仍走 el-scrollbar 滚动。 */
.ta-body-fill {
  .el-scrollbar__wrap {
    overflow: hidden !important;
  }
  .el-scrollbar__bar {
    display: none !important;
  }
  .el-scrollbar__view {
    height: 100%;
    display: flex;
    flex-direction: column;
    padding: 10px 14px 12px;
  }
}
.ta-overview {
  flex: 1 1 0;
  min-height: 0;
  gap: 8px;
}
.ta-kpis-row {
  flex: 0 0 auto;
  grid-template-columns: repeat(4, 1fr);
  gap: 6px;

  .ta-kpi {
    padding: 7px 9px 6px;
    gap: 2px;
  }
  .ta-kpi-value {
    font-size: 15px;
  }
}
.ta-overview .ta-card {
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 8px 10px 6px;
}
.ta-overview .ta-card-head {
  flex: 0 0 auto;
  margin-bottom: 4px;
}
/* 两张图按信息密度分高：Top10 排名图占比更大；最小高度保证轴标签不叠字 */
.ta-overview .ta-card--series {
  flex: 1 1 0;
}
.ta-overview .ta-card--rank {
  flex: 1.45 1 0;
}
.ta-overview .ta-card--dist {
  flex: 0 0 auto;
}
.ta-overview .ta-chart {
  flex: 1 1 0;
  height: auto;
  min-height: 100px;
}
.ta-overview .ta-chart-rank {
  min-height: 140px;
}

/* ---- 换乘时间分布条（总览） ---- */
.ta-dist-stats {
  font-size: 11px;
  color: var(--dm2-muted);
  white-space: nowrap;

  b {
    color: var(--dm2-ink-soft);
    font-weight: 600;
    font-variant-numeric: tabular-nums;
  }
}
.ta-dist-strip {
  display: flex;
  height: 14px;
  border-radius: 4px;
  overflow: hidden;
}
.ta-dist-seg {
  min-width: 2px;
}
.ta-dist-seg + .ta-dist-seg {
  margin-left: 1px;
}
.ta-dist-legend {
  display: grid;
  grid-template-columns: repeat(3, auto);
  justify-content: space-between;
  gap: 3px 10px;
  margin-top: 6px;
}
.ta-dist-item {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 10.5px;
  color: var(--dm2-muted);
  white-space: nowrap;

  .ta-dist-pct {
    color: var(--dm2-ink-soft);
    font-weight: 600;
    font-style: normal;
    font-variant-numeric: tabular-nums;
  }
}
.ta-dist-dot {
  width: 7px;
  height: 7px;
  border-radius: 2px;
  flex: 0 0 auto;
}

/* ---- 图表点击全屏（效仿客流分析 boarding-fullscreen-dialog） ---- */
.ta-chart-zoom {
  cursor: pointer;
  flex: 1 1 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border-radius: 6px;
  transition: background 120ms var(--dm2-ease, ease);

  &:hover {
    background: var(--dm2-surface-sunken);
  }
}
.ta-fullscreen-dialog {
  background: #f7fbff;

  .el-dialog__header {
    margin-right: 0;
    padding: 18px 24px 14px;
    border-bottom: 1px solid rgba(21, 105, 222, 0.12);
  }
  .el-dialog__headerbtn {
    top: 16px;
    right: 18px;
  }
  .el-dialog__body {
    height: calc(100vh - 78px);
    padding: 0;
  }
}
.ta-fullscreen-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  padding-right: 42px;
  font-family: var(--dm2-font);
}
.ta-fullscreen-kicker {
  margin-bottom: 4px;
  color: var(--dm2-muted);
  font-size: 12px;
  font-weight: 600;
}
.ta-fullscreen-title {
  color: var(--dm2-ink);
  font-size: 18px;
  font-weight: 600;
}
.ta-fullscreen-meta {
  flex: 0 0 auto;
  color: var(--dm2-accent);
  font-size: 13px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.ta-fullscreen-body {
  width: 100%;
  height: 100%;
  padding: 18px 24px 24px;
  box-sizing: border-box;
  display: flex;
}
.ta-fullscreen-chart {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
}

/* ---- 换乘站点分析：地图左上角站点搜索（几何/玻璃底与运行监测 .rm-search 对齐） ---- */
.ta-search {
  position: fixed;
  top: calc(var(--app-header-height, 58px) + var(--app-scaled-20, 20px));
  left: var(--app-scaled-282, 282px);
  width: 400px;
  z-index: calc(var(--z-header, 1400) + 6);
  display: flex;
  align-items: center;
  gap: 8px;
  height: 42px;
  padding: 0 12px 0 6px;
  box-sizing: border-box;
  border: 1px solid var(--dm2-line, rgba(17, 32, 58, 0.1));
  border-radius: 12px;
  background: var(--dm2-glass-strong, rgba(255, 255, 255, 0.92));
  box-shadow: var(--dm2-shadow-pop, 0 12px 30px -16px rgba(13, 38, 76, 0.3));
  -webkit-backdrop-filter: var(--dm2-glass-blur, blur(12px));
  backdrop-filter: var(--dm2-glass-blur, blur(12px));
  transform-origin: top left;
  font-family: var(--dm2-font);
  transition:
    left 160ms var(--dm2-ease, cubic-bezier(0.32, 0.72, 0, 1)),
    box-shadow 160ms var(--dm2-ease, cubic-bezier(0.32, 0.72, 0, 1)),
    border-color 160ms var(--dm2-ease, cubic-bezier(0.32, 0.72, 0, 1));
}
.ta-search.is-left-collapsed {
  left: var(--app-scaled-22, 22px);
}
.ta-search.is-focused {
  border-color: var(--dm2-accent, #0071e3);
  box-shadow: 0 0 0 3px rgba(0, 113, 227, 0.14), var(--dm2-shadow-pop, 0 12px 30px -16px rgba(13, 38, 76, 0.3));
}
.ta-search-icon {
  flex-shrink: 0;
  width: 17px;
  height: 17px;
  color: var(--dm2-muted, #667085);
}
.ta-search-input {
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
.ta-search-clear {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: var(--dm2-surface-sunken, #f0f3f8);
  color: var(--dm2-muted, #667085);
  cursor: pointer;

  &:hover {
    color: var(--dm2-ink, #1c2024);
  }
}
.ta-search-results {
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
.ta-search-result {
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
  font-family: inherit;
  transition: background 120ms ease;

  &:hover {
    background: rgba(0, 113, 227, 0.08);
  }
}
.ta-result-icon {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  color: #ffffff;
  background: linear-gradient(135deg, #0b8f74, #18b89a);

  svg {
    width: 16px;
    height: 16px;
  }
}
.ta-result-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
  gap: 1px;
}
.ta-result-name {
  overflow: hidden;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--dm2-ink, #1c2024);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ta-result-type {
  font-size: 11px;
  color: var(--dm2-muted, #667085);
  font-variant-numeric: tabular-nums;
}
.ta-search-empty {
  margin: 0;
  padding: 14px 10px;
  text-align: center;
  font-size: 12.5px;
  color: var(--dm2-muted, #667085);
}
.ta-search-fade-enter-active,
.ta-search-fade-leave-active {
  transition: opacity 140ms var(--dm2-ease, ease), transform 140ms var(--dm2-ease, ease);
}
.ta-search-fade-enter-from,
.ta-search-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

/* ---- 模块内筛选 ---- */
.ta-filters {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 12px;
  background: var(--dm2-surface-sunken);
  border: 1px solid var(--dm2-line-faint);
  border-radius: var(--dm2-radius-sm);
}
.ta-filter-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ta-filter-label {
  flex: 0 0 52px;
  font-size: 12px;
  color: var(--dm2-muted);
}
.ta-filter-sel {
  flex: 1 1 auto;
  min-width: 0;
}

/* ---- 接驳率卡（分母写进展示名） ---- */
.ta-ratio-card {
  border: 1px solid var(--dm2-line-faint);
  border-radius: var(--dm2-radius-sm);
  background: var(--dm2-surface);
  padding: 4px 12px;
}
.ta-ratio-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 0;

  & + .ta-ratio-row {
    border-top: 1px solid var(--dm2-line-faint);
  }
}
.ta-ratio-name {
  font-size: 12px;
  color: var(--dm2-ink-soft);
  display: flex;
  flex-direction: column;
  gap: 2px;

  .ta-ratio-cal {
    font-style: normal;
    font-size: 11px;
    color: var(--dm2-muted-soft);
  }
}
.ta-ratio-value {
  font-size: 16px;
  font-weight: 650;
  color: var(--dm2-accent-strong);
  font-variant-numeric: tabular-nums;
}

/* ---- 明细表 ---- */
.ta-table {
  display: flex;
  flex-direction: column;
  font-size: 12px;
}
.ta-table-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 2px;
  border-top: 1px solid var(--dm2-line-faint);
  color: var(--dm2-ink-soft);

  &.ta-table-head {
    border-top: none;
    color: var(--dm2-muted);
    font-size: 11px;
  }
  .c-name {
    flex: 1 1 0;
    min-width: 0;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .c-num {
    flex: 0 0 52px;
    text-align: right;
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
  }
}
.ta-table-button {
  width: 100%;
  border: 0;
  background: transparent;
  font: inherit;
  text-align: left;
}
.ta-row-click {
  cursor: pointer;

  &:hover {
    background: var(--dm2-accent-weak);
  }
  &:active {
    transform: translateY(1px);
  }
}
.ta-empty-row {
  padding: 16px 0;
  text-align: center;
  color: var(--dm2-muted-soft);
}

/* ---- 导出按钮 ---- */
.ta-export {
  border: 1px solid var(--dm2-line);
  background: var(--dm2-surface);
  color: var(--dm2-accent);
  border-radius: 999px;
  font-size: 11px;
  line-height: 1;
  padding: 5px 10px;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;

  &:hover {
    border-color: var(--dm2-accent);
    background: var(--dm2-accent-weak);
  }
  &:active {
    transform: translateY(1px);
  }
}

/* 顶栏右上角选择器使用 tokens.css 的 analysis-model-toolbar 共享样式。 */
.ta-model-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  min-width: 0;

  .ta-model-option-name {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

/* ---- 地图控件设置弹层（按钮/工具条样式来自 tokens.css 全局规则） ---- */
.ta-controls-popover {
  position: absolute;
  right: 52px;
  bottom: 0;
  width: min(252px, calc(100vw - 96px));
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px;
  box-sizing: border-box;
  border: 1px solid var(--app-border, rgba(21, 105, 222, 0.16));
  border-radius: 10px;
  background: var(--app-panel-bg, rgba(255, 255, 255, 0.96));
  box-shadow: var(--app-shadow-sm, 0 8px 24px rgba(13, 38, 76, 0.16));
}
.ta-popover-title {
  margin: 0;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--dm2-line-faint, rgba(21, 105, 222, 0.09));
  font-size: 13px;
  font-weight: 700;
  color: var(--app-ink, #1f2d3d);
}
.ta-mode-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 11px;
  font-weight: 600;
  color: var(--app-muted, #6b7a90);
}
.ta-mode-hint {
  margin: 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--app-muted, #8a94a6);
}
.ta-range-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 264px;
  overflow-y: auto;
}
.ta-range-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--app-ink-soft, #475467);
  font-size: 12px;
  text-align: left;
  cursor: pointer;

  &:hover {
    background: rgba(21, 105, 222, 0.06);
  }

  &.active {
    background: rgba(21, 105, 222, 0.1);
    color: var(--app-blue, #0071e3);
    font-weight: 700;
  }
}
.ta-range-state {
  margin: 0;
  font-size: 12px;
  color: var(--app-muted, #8a94a6);
}
.ta-range-error {
  margin: 0;
  font-size: 12px;
  color: var(--app-coral, #dc2626);
}
.ta-popover-fade-enter-active,
.ta-popover-fade-leave-active {
  transition: opacity 160ms ease, transform 160ms ease;
}
.ta-popover-fade-enter-from,
.ta-popover-fade-leave-to {
  opacity: 0;
  transform: translateX(6px);
}

/* ---- 左下角图例（外框沿用运行监测 map-legend-card；仅说明） ---- */
.ta-map-legend {
  position: fixed;
  left: calc(276px * var(--app-layout-scale, 1));
  bottom: 20px;
  z-index: calc(var(--z-panel, 1300) + 10);
}
.ta-legend-card {
  min-width: 168px;
  max-width: 260px;
  padding: 10px 12px;
  border: 1px solid var(--app-border, rgba(21, 105, 222, 0.16));
  border-radius: 10px;
  background: var(--app-panel-bg, rgba(255, 255, 255, 0.94));
  box-shadow: var(--app-shadow-sm, 0 8px 24px rgba(13, 38, 76, 0.16));
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 11px;
  color: var(--app-ink-soft, #475467);
}
.ta-legend-head {
  margin-bottom: 2px;
}
.ta-legend-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--app-ink, #344054);
}
.ta-legend-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ta-legend-sym {
  flex: none;
  width: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 3px;
}
.ta-sym-dot {
  border-radius: 50%;
  border: 1.5px solid var(--app-muted, #667085);
  background: transparent;

  &.sm {
    width: 7px;
    height: 7px;
  }
  &.lg {
    width: 13px;
    height: 13px;
  }
}
.ta-sym-ramp {
  width: 28px;
  height: 8px;
  border-radius: 3px;
}
.ta-sym-ride {
  --ta-flow-color: #65d8ff;
  width: 28px;
  height: 3px;
  border-radius: 999px;
  background: repeating-linear-gradient(
    90deg,
    var(--ta-flow-color) 0 6px,
    transparent 6px 10px
  );
  opacity: 0.82;
}
.ta-sym-flow {
  --ta-flow-color: #65d8ff;
  position: relative;
  width: 28px;
  height: 8px;

  &::before {
    content: "";
    position: absolute;
    left: 1px;
    right: 1px;
    top: 3px;
    height: 2px;
    border-radius: 999px;
    background: var(--ta-flow-color);
    box-shadow: 0 0 6px var(--ta-flow-color);
  }
  &::after {
    content: "";
    position: absolute;
    right: 0;
    top: 1px;
    width: 7px;
    height: 6px;
    background: var(--ta-flow-color);
    clip-path: polygon(0 0, 100% 50%, 0 100%, 28% 50%);
    box-shadow: 0 0 5px var(--ta-flow-color);
  }
}
.ta-sym-transfer {
  --ta-flow-color: #65d8ff;
  position: relative;
  width: 28px;
  height: 10px;
  border-radius: 999px;
  background: linear-gradient(
    180deg,
    transparent 0 34%,
    var(--ta-flow-color) 34% 66%,
    transparent 66% 100%
  );
  box-shadow: 0 0 7px color-mix(in srgb, var(--ta-flow-color) 72%, transparent);

  &::before,
  &::after {
    content: "";
    position: absolute;
    top: 2px;
    width: 6px;
    height: 6px;
    box-sizing: border-box;
    border: 1px solid #e4f7ff;
    border-radius: 50%;
    background: color-mix(in srgb, var(--ta-flow-color) 48%, #071a29);
  }
  &::before {
    left: 0;
  }
  &::after {
    right: 0;
  }
}
.ta-legend-label {
  min-width: 0;
  line-height: 1.35;
}

/* ── 暗色模式（html.dark，跟随底图选择） ── */
/* 仅覆盖上文写死的浅色字面量；--dm2- 与 --app- 令牌已在 tokens.css/main.scss 随主题翻转。
   数据语义色（pieColors/heatRamp/图例符号取 MAP_THEME）与实心强调底上的白字白图标保持原样。 */
html.dark .ta-fullscreen-dialog {
  background: #0d1218;
}
html.dark .ta-fullscreen-dialog .el-dialog__header {
  border-bottom-color: rgba(64, 156, 255, 0.16);
}
html.dark .ta-search.is-focused {
  box-shadow: 0 0 0 3px rgba(64, 156, 255, 0.18), var(--dm2-shadow-pop, 0 12px 30px -16px rgba(13, 38, 76, 0.3));
}
html.dark .ta-search-result:hover {
  background: rgba(64, 156, 255, 0.12);
}
html.dark .ta-range-option {
  /* --app-ink-soft 全局未定义，亮色恒走 #475467 兜底；暗色补正文色 */
  color: #c2cddd;
}
html.dark .ta-range-option:hover {
  background: rgba(64, 156, 255, 0.1);
}
html.dark .ta-range-option.active {
  background: rgba(64, 156, 255, 0.14);
  /* 上方 html.dark .ta-range-option 的正文色比原 .active 规则特异性高，需在此重申强调色 */
  color: var(--app-blue, #409cff);
}
html.dark .ta-legend-card {
  /* 同上：--app-ink-soft 未定义，兜底 #475467 在暗底不可读 */
  color: #c2cddd;
}
</style>
