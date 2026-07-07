<!-- 站点分析 (Station Analysis) -->
<template>
  <div class="ZDZL" v-bind="$attrs">
    <div v-if="loading" class="loading-container">
      <el-empty description="加载所有站点中，请稍等...." />
    </div>

    <div v-else class="info-container">
      <MCard class="card search-card" :open="true" title="公交站点搜索">
        <template #body>
          <!-- Station Search Dropdown -->
          <div class="search-input-wrapper">
            <el-select-v2
              v-model="selectedStationName"
              :options="stationOptions"
              placeholder="请输入或选择站点名称"
              filterable
              clearable
              @change="handleStationChange"
              class="custom-select"
            >
              <template #prefix>
                <el-icon class="search-icon"><Location /></el-icon>
              </template>
            </el-select-v2>
          </div>
        </template>
      </MCard>
    </div>
  </div>

  <teleport v-if="!runMonitorSimplifiedRight || pfaRightPanel" to="#datavisualization_index_box2" defer>
    <MCard2 v-if="selectedStationName" :class="['SJZL_right_card', shouldRenderPfaRightPanel ? 'pfa-station-card' : '']" :open="true">
      <template #title>
        <div class="ranking-title-container">
          <div class="header-actions-left">
            <div v-if="shouldRenderPfaRightPanel" class="pfa-station-heading">
              <span class="pfa-station-name">{{ selectedStationName || '站点客流分析' }}</span>
              <span class="pfa-station-sub">{{ selectedStationType }}站 · 途经线路 {{ matchedRoutes.length }} 条</span>
            </div>
            <div v-else class="detail-tab-selector">
              <div
                v-for="tab in DETAIL_TABS"
                :key="tab.value"
                :class="['tab-pill', activeDetailTab === tab.value ? 'active' : '']"
                @click.stop="activeDetailTab = tab.value"
              >
                {{ tab.label }}
              </div>
            </div>
          </div>
          <div class="header-actions">
            <el-button
              type="primary"
              size="small"
              class="export-btn"
              @click.stop="handleExportDetail"
            >
              <el-icon style="margin-right: 4px;"><Download /></el-icon>
              导出
            </el-button>
          </div>
        </div>
      </template>
      <template #body>
        <div v-if="shouldRenderPfaRightPanel" class="pfa-station-sections">
          <div v-if="stationPanelUnavailable" :class="['pfa-status-card', selectedStationPanelStatus.type]">
            <span class="pfa-status-title">{{ selectedStationPanelStatus.text }}</span>
            <span v-if="selectedStationPanelStatus.type === 'generating'" class="pfa-status-sub">后端正在准备缓存，当前站点与地图选择已保留。</span>
            <el-button
              v-if="selectedStationPanelStatus.type === 'error'"
              type="primary"
              size="small"
              @click.stop="ensureStationPanelData({ force: true })"
            >
              重试
            </el-button>
          </div>
          <div v-if="!stationPanelUnavailable && PFA_TIME_RANGE_SECTIONS.includes(pfaStationSection)" class="time-range-section">
            <div class="time-range-header">
              <span class="title">统计时段选择</span>
              <span class="range-text">{{ formatHourLabel(segmentTimeRange[0]) }} - {{ formatHourLabel(segmentTimeRange[1]) }}</span>
            </div>
            <el-slider v-model="segmentTimeRange" range :min="6" :max="23" :step="1" :show-tooltip="false" class="time-range-slider" />
          </div>

          <section v-if="!stationPanelUnavailable && pfaStationSection === 'boarding'" class="pfa-section">
            <div class="section-header">
              <span class="section-title">站点乘降客流</span>
              <div class="pfa-section-actions">
                <span class="pfa-section-meta">上车 {{ stationBoardingSummary.boarding }} · 下车 {{ stationBoardingSummary.alighting }}</span>
                <el-button size="small" class="pfa-heatmap-btn" @click.stop="boardingHeatmapVisible = true">热力图</el-button>
              </div>
            </div>
            <div class="chart-container-wrapper">
              <el-auto-resizer class="chart_box">
                <template #default="{ height, width }">
                  <VChart
                    v-if="width > 0 && height > 0"
                    class="boarding-alighting-bar-chart"
                    :option="boardingAlightingChartOption"
                    autoresize
                    :update-options="{ notMerge: true, lazyUpdate: true }"
                  />
                </template>
              </el-auto-resizer>
            </div>
          </section>

          <section v-else-if="!stationPanelUnavailable && pfaStationSection === 'od'" class="pfa-section">
            <div class="section-header">
              <span class="section-title">客流OD</span>
              <div class="chart-type-selector">
                <div
                  v-for="mode in PFA_OD_VIEW_MODES"
                  :key="mode.value"
                  :class="['type-pill', odViewMode === mode.value ? 'active' : '']"
                  @click="odViewMode = mode.value"
                >
                  {{ mode.label }}
                </div>
              </div>
            </div>
            <div v-if="odViewMode === 'table'" class="od-table-wrapper pfa-od-table">
              <div class="transfer-table">
                <div class="transfer-table-header">
                  <span class="col-od-route">OD对端站点</span>
                  <span class="col-od-flow text-right">客流量</span>
                </div>
                <div class="transfer-table-body">
                  <div v-for="(item, idx) in odStationChart" :key="`${item.chartLabel}-${idx}`" class="transfer-table-row">
                    <span class="col-od-route text-ellipsis">
                      <strong>{{ item.chartLabel }}</strong>
                      <small v-if="item.routeCount > 1">{{ item.routeCount }} 条线路</small>
                    </span>
                    <span class="col-od-flow text-right bold">{{ item.flow.toLocaleString() }} <small>人次</small></span>
                  </div>
                  <div v-if="!odStationChart.length" class="pfa-empty">暂无OD客流数据</div>
                </div>
              </div>
            </div>
            <div v-else class="chart-container-wrapper" :style="{ height: odChartHeight + 'px' }">
              <el-auto-resizer class="chart_box">
                <template #default="{ height, width }">
                  <VChart
                    v-if="width > 0 && height > 0"
                    class="od-bar-chart"
                    :option="odChartOption"
                    autoresize
                    :update-options="{ notMerge: true, lazyUpdate: true }"
                  />
                </template>
              </el-auto-resizer>
            </div>
          </section>

          <section v-else-if="!stationPanelUnavailable && pfaStationSection === 'demographics'" class="pfa-section">
            <div class="section-header">
              <span class="section-title">客流画像</span>
              <span v-if="stationDemographicsRiderCount" class="pfa-section-meta">样本 {{ stationDemographicsRiderCount.toLocaleString() }} 人</span>
            </div>
            <div class="demo-groups">
              <div v-for="g in stationDemographicsGroups" :key="g.key" class="demo-group">
                <div class="demo-group-head">
                  <span class="demo-group-title">{{ g.title }}</span>
                  <span class="demo-group-sum">{{ g.sumLabel || '合计 100%' }}</span>
                </div>
                <div class="demo-list">
                  <div v-for="d in g.items" :key="d.key" class="demo-row">
                    <span class="demo-label">
                      <span class="demo-dot" :style="{ background: d.color }"></span>
                      {{ d.label }}
                    </span>
                    <span class="demo-track">
                      <span class="demo-fill" :style="{ width: Math.min(100, d.value) + '%', background: d.color }"></span>
                    </span>
                    <span class="demo-pct">{{ d.value.toFixed(1) }}%</span>
                  </div>
                </div>
              </div>
              <div v-if="!stationDemographicsGroups.length" class="pfa-empty">暂无客流画像数据</div>
            </div>
          </section>

          <section v-else-if="!stationPanelUnavailable && pfaStationSection === 'reachability'" class="pfa-section">
            <div class="section-header">
              <span class="section-title">可达性</span>
              <span class="pfa-section-meta">按直达、一次换乘、二次换乘分级</span>
            </div>
            <div class="reachability-grid">
              <div v-for="group in reachabilityGroups" :key="group.key" :class="['metric-card', group.key]">
                <span class="label">{{ group.label }}</span>
                <span class="value">{{ group.count.toLocaleString() }} <small>个</small></span>
              </div>
            </div>
            <div class="reachability-list">
              <div v-for="group in reachabilityGroups" :key="`${group.key}-stations`" class="reachability-group">
                <div class="reachability-list-head">
                  <span class="reachability-dot" :style="{ background: group.color }"></span>
                  <span>{{ group.label }}</span>
                  <strong>{{ group.count.toLocaleString() }}</strong>
                  <el-switch
                    class="reachability-level-switch"
                    size="small"
                    :model-value="reachabilityLevelVisibility[group.key] !== false"
                    @change="(value) => setReachabilityLevelVisible(group.key, value)"
                    @click.stop
                  />
                </div>
                <div class="reachability-chip-list">
                  <span v-for="station in group.stations" :key="`${group.key}-${reachabilityStationKey(station)}`" class="reachability-chip" :style="{ borderColor: group.color, color: group.color }">
                    {{ reachabilityStationLabel(station) }}
                  </span>
                  <span v-if="!group.stations.length" class="reachability-empty">暂无站点明细</span>
                </div>
              </div>
            </div>
          </section>
        </div>
        <div v-else-if="activeDetailTab === 'overview'" class="route-detail-panel">
          <!-- Station Info Header -->
          <div class="station-info-header">
            <div class="station-main">
              <div class="station-icon-wrapper" :class="selectedStationType === '地铁' ? 'subway' : 'bus'">
                <el-icon class="station-icon"><Location /></el-icon>
              </div>
              <div class="station-details">
                <span class="station-title-text">{{ selectedStationName }}</span>
                <div class="type-badge-container">
                  <el-tag
                    :type="selectedStationType === '地铁' ? 'danger' : 'success'"
                    size="small"
                    effect="light"
                    class="type-tag"
                  >
                    {{ selectedStationType }}站
                  </el-tag>
                  <span class="route-count-badge">途经线路: {{ matchedRoutes.length }} 条</span>
                </div>
              </div>
            </div>
          </div>

          <!-- 4 Metrics Grid -->
          <div class="metrics-grid">
            <div class="metric-card">
              <span class="label">站点日均客流</span>
              <span class="value">{{ stationMetrics.passenger }}</span>
            </div>
            <div class="metric-card">
              <span class="label">高峰小时客流</span>
              <span class="value">{{ stationMetrics.peakFlow }}</span>
            </div>
            <div class="metric-card">
              <span class="label">服务乘客数</span>
              <span class="value">{{ stationMetrics.population }}</span>
            </div>
            <div class="metric-card">
              <span class="label">换乘便利度</span>
              <span class="value">{{ stationMetrics.transferScore }}</span>
            </div>
          </div>

          <!-- Passenger Flow Chart Section -->
          <div class="passenger-flow-section">
            <div class="section-header">
              <span class="section-title">全天客流变化</span>
              <div class="chart-type-selector">
                <div
                  v-for="type in FLOW_CHART_TYPES"
                  :key="type"
                  :class="['type-pill', activeChartType === type ? 'active' : '']"
                  @click="activeChartType = type"
                >
                  {{ type === 'line' ? '折线图' : '柱状图' }}
                </div>
              </div>
            </div>
            <div class="chart-container-wrapper">
              <el-auto-resizer class="chart_box">
                <template #default="{ height, width }">
                  <VChart
                    v-if="width > 0 && height > 0"
                    class="flow-chart"
                    :option="passengerFlowChartOption"
                    autoresize
                    :update-options="{ notMerge: true, lazyUpdate: true }"
                  />
                </template>
              </el-auto-resizer>
            </div>
          </div>

          <!-- Route list passing through that station -->
          <div class="stations-section">
            <div class="section-title">途经线路列表</div>
            <div class="station-scroll-list">
              <div class="matched-list">
                <div
                  v-for="item in matchedRoutes"
                  :key="item.routeId"
                  class="matched-item"
                >
                  <div class="item-header">
                    <span class="line-badge" :class="item.isSubway ? 'subway-badge' : 'bus-badge'">
                      {{ item.lineName }}
                    </span>
                    <span class="item-stops">{{ item.facilities?.length || item.info?.facNum }} 站</span>
                  </div>
                  <div class="item-body">
                    <div class="item-time">
                      首末班: {{ formatSecondsToTime(item.info?.firstTime) }} - {{ formatSecondsToTime(item.info?.lastTime) }}
                    </div>
                    <div class="item-dist">
                      全长: {{ ((item.info?.routeDist || 0) / 1000).toFixed(1) }} km
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- Tab 2: 站点乘降分析 -->
        <div v-else-if="activeDetailTab === 'boardingAlighting'" class="route-boarding-alighting-panel">
          <!-- Double Dot Slider for Time Range -->
          <div class="time-range-section">
            <div class="time-range-header">
              <span class="title">统计时段选择</span>
              <span class="range-text">{{ formatHourLabel(segmentTimeRange[0]) }} - {{ formatHourLabel(segmentTimeRange[1]) }}</span>
            </div>
            <el-slider
              v-model="segmentTimeRange"
              range
              :min="6"
              :max="23"
              :step="1"
              :show-tooltip="false"
              class="time-range-slider"
            />
          </div>

          <div class="boarding-alighting-header">
            <span class="section-title">站点乘降客流分析</span>
          </div>

          <div class="boarding-alighting-chart-wrapper">
            <el-auto-resizer class="chart_box">
              <template #default="{ height, width }">
                <VChart
                  v-if="width > 0 && height > 0"
                  class="boarding-alighting-bar-chart"
                  :option="boardingAlightingChartOption"
                  autoresize
                  :update-options="{ notMerge: true, lazyUpdate: true }"
                />
              </template>
            </el-auto-resizer>
          </div>
        </div>

        <!-- Tab 3: 站点OD分析 -->
        <div v-else-if="activeDetailTab === 'od'" class="route-od-panel">
          <!-- Double Dot Slider for Time Range -->
          <div class="time-range-section">
            <div class="time-range-header">
              <span class="title">统计时段选择</span>
              <span class="range-text">{{ formatHourLabel(segmentTimeRange[0]) }} - {{ formatHourLabel(segmentTimeRange[1]) }}</span>
            </div>
            <el-slider
              v-model="segmentTimeRange"
              range
              :min="6"
              :max="23"
              :step="1"
              :show-tooltip="false"
              class="time-range-slider"
            />
          </div>

          <!-- View Mode Switcher -->
          <div class="od-header">
            <span class="section-title">OD客流排名</span>
            <div class="chart-type-selector">
              <div
                v-for="mode in OD_VIEW_MODES"
                :key="mode.value"
                :class="['type-pill', odViewMode === mode.value ? 'active' : '']"
                @click="odViewMode = mode.value"
              >
                {{ mode.label }}
              </div>
            </div>
          </div>

          <!-- Table View Mode -->
          <div v-if="odViewMode === 'table'" class="od-table-wrapper">
            <div class="transfer-table">
              <div class="transfer-table-header">
                <span class="col-od-route">线路方向</span>
                <span class="col-od-flow text-right">客流量</span>
              </div>
              <div class="transfer-table-body">
                <div v-for="(item, idx) in odTableData" :key="`${item.origin || ''}-${item.destination || ''}-${item.routeLabel || idx}`" class="transfer-table-row">
                  <span class="col-od-route text-ellipsis">
                    <strong>{{ item.lineName || '未知线路' }}</strong>
                    <small>{{ item.routeDesc || item.routeName || '方向未识别' }}</small>
                  </span>
                  <span class="col-od-flow text-right bold">{{ item.flow.toLocaleString() }} <small>人次</small></span>
                </div>
              </div>
            </div>
          </div>

          <!-- Chart View Mode -->
          <div v-else class="od-chart-wrapper">
            <el-auto-resizer class="chart_box">
              <template #default="{ height, width }">
                <VChart
                  v-if="width > 0 && height > 0"
                  class="od-bar-chart"
                  :option="odChartOption"
                  autoresize
                  :update-options="{ notMerge: true, lazyUpdate: true }"
                />
              </template>
            </el-auto-resizer>
          </div>
        </div>

        <!-- Tab 4: 站点可达分析 -->
        <div v-else-if="activeDetailTab === 'reachability'" class="route-reachability-panel">
          <!-- Metric Grid for Reachability -->
          <div class="reachability-grid">
            <div class="metric-card direct">
              <span class="label">可直达站点</span>
              <span class="value">{{ reachabilityData.direct }} <small>个</small></span>
            </div>
            <div class="metric-card transfer1">
              <span class="label">一次换乘可达</span>
              <span class="value">{{ reachabilityData.transfer1 }} <small>个</small></span>
            </div>
            <div class="metric-card transfer2">
              <span class="label">二次换乘可达</span>
              <span class="value">{{ reachabilityData.transfer2 }} <small>个</small></span>
            </div>
          </div>

          <!-- Reachability Distribution Section -->
          <div class="reachability-header">
            <span class="section-title">可达站点等级分布</span>
          </div>

          <div class="reachability-chart-wrapper">
            <el-auto-resizer class="chart_box">
              <template #default="{ height, width }">
                <VChart
                  v-if="width > 0 && height > 0"
                  class="reachability-pie-chart"
                  :option="reachabilityChartOption"
                  autoresize
                  :update-options="{ notMerge: true, lazyUpdate: true }"
                />
              </template>
            </el-auto-resizer>
          </div>
        </div>
      </template>
    </MCard2>

    <div v-else-if="!runMonitorSimplifiedRight && !pfaRightPanel" class="rm-right-card rm-ranking-card">
      <div class="rm-right-card-title">
        <div class="rm-title-head">
          <p class="rm-panel-kicker">站点客流</p>
          <h2>{{ activeTransitType === 'bus' ? '公交' : '地铁' }}站点客流排行</h2>
        </div>
        <div class="rm-ranking-tools">
          <div class="rm-seg" role="group" aria-label="客流类型">
            <button
              v-for="type in TRANSIT_TYPES"
              :key="type"
              type="button"
              :class="['rm-seg-btn', activeTransitType === type ? 'active' : '']"
              @click.stop="activeTransitType = type"
            >
              {{ type === 'bus' ? '公交' : '地铁' }}
            </button>
          </div>
          <button type="button" class="rm-export-btn" @click.stop="handleExportLeaderboard">
            <el-icon><Download /></el-icon>
            <span>导出</span>
          </button>
        </div>
      </div>
      <div class="ranking-panel">
        <div class="ranking-header">
          <span class="col-rank">排序</span>
          <span class="col-name">站点名称</span>
          <span class="col-flow">日均客流量</span>
        </div>
        <div class="ranking-scroll-list">
          <button
            v-for="(item, index) in currentLeaderboard"
            :key="item.stationName || item.name || index"
            class="ranking-row"
            type="button"
            @click="selectLeaderboardStation(item)"
          >
            <div class="col-rank">
              <span :class="['rank-badge', index === 0 ? 'gold' : index === 1 ? 'silver' : index === 2 ? 'bronze' : '']">
                {{ index + 1 }}
              </span>
            </div>
            <div class="col-name">
              <span class="route-name-text">{{ item.stationName }}</span>
              <span class="route-desc-text">{{ item.desc }}</span>
            </div>
            <div class="col-flow">
              <span class="flow-value">{{ (item.passengerFlow ?? 0).toLocaleString() }}</span>
              <span class="flow-unit">人次</span>
            </div>
          </button>
        </div>
      </div>
    </div>
  </teleport>

  <el-dialog
    v-model="boardingHeatmapVisible"
    class="station-heatmap-dialog"
    modal-class="station-heatmap-overlay"
    width="70%"
    align-center
    append-to-body
    destroy-on-close
    :lock-scroll="true"
  >
    <template #header>
      <div class="station-heatmap-header">
        <div>
          <div class="station-heatmap-kicker">站点乘降热力图</div>
          <div class="station-heatmap-title">{{ selectedStationName || '站点乘降分析' }}</div>
        </div>
        <span class="station-heatmap-meta">
          {{ formatHourLabel(segmentTimeRange[0]) }} - {{ formatHourLabel(segmentTimeRange[1]) }} · 线路 × OD对端站
        </span>
      </div>
    </template>
    <div class="station-heatmap-body">
      <VChart
        v-if="boardingHeatmapData.hasData"
        class="station-heatmap-chart"
        :option="boardingHeatmapOption"
        autoresize
        :update-options="{ notMerge: true, lazyUpdate: true }"
      />
      <el-empty v-else description="当前时段暂无线路×OD客流数据" />
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, shallowRef, onMounted, onUnmounted, watch, inject, computed, getCurrentInstance, nextTick } from "vue";
import { Location, Download } from "@element-plus/icons-vue";
import { abortOtherModelDataRequests, getCachedLineAll, getCachedStationPanel, getModelDerived } from "@/utils/modelDataCache.js";
import MCard from "./MCard.vue";
import MCard2 from "./MCard2.vue";
import { StationLayer } from "../layers/StationLayer.js";
import { buildFlowCurveFeatureCollection } from "../utils/flowCurves.js";
import { classifyByBreaks, createColorScaleConfig, quantileBreaks, resolveColorScale } from "@/utils/colorSchemes.js";
import { buildPassengerProfileGroups, passengerProfileRiderCount } from "../utils/passengerProfile.js";
import { injectSync } from "@/utils";
import { compareZh, createDebouncedMirror, isCanceledRequest, runWhenIdle } from "../utils/panelShared.js";
import { webMercatorToLngLat } from "@/mymap/index.js";

const props = defineProps({
  model: String,
});

// 模板 v-for 静态选项提为常量，避免每次渲染重建数组
const DETAIL_TABS = [
  { value: "overview", label: "站点数据分析" },
  { value: "boardingAlighting", label: "站点乘降分析" },
  { value: "od", label: "站点OD分析" },
  { value: "reachability", label: "站点可达分析" },
];
const PFA_TIME_RANGE_SECTIONS = ["boarding", "od"];
const PFA_OD_VIEW_MODES = [{ value: "table", label: "表格" }, { value: "chart", label: "图表" }];
const OD_VIEW_MODES = [{ value: "table", label: "数据表格" }, { value: "chart", label: "可视化图表" }];
const FLOW_CHART_TYPES = ["line", "bar"];
const TRANSIT_TYPES = ["bus", "subway"];

const loading = ref(true);
const rawLines = shallowRef([]);
// rawLines 当前归属的模型：派生索引以它为键，避免模型切换瞬间写错缓存
let rawLinesModel = "";
const stationPanelData = shallowRef(null);
const stationPanelStatus = ref("idle");
const stationPanelError = ref("");
let stationPanelPromise = null;
let stationPanelRetryTimer = null;
let stationPanelRetryCount = 0;
// 组件卸载后不再写状态、不再安排重试（共享缓存请求本身可继续完成以便复用）
let stationPanelDisposed = false;

const selectedStationName = ref("");
const selectedStationFacilityId = ref("");
const selectedStationCoord = ref(null);
const selectedReverseStationName = ref("");
const selectedReverseStationFacilityId = ref("");
const selectedReverseStationCoord = ref(null);
const matchedRoutes = shallowRef([]);
const allMapStations = shallowRef([]);

const StationSizeRef = inject("StationSizeRef", ref(40));
const MapRef = inject("MapRef", ref(null));
const BaseMapLineModeRef = inject("BaseMapLineModeRef", ref("bus-network"));

// 注入右侧面板显示控制
const rightPanelHasContent = inject("rightPanelHasContent", ref(false));
const activeDatavisualizationTab = inject("activeDatavisualizationTab", ref(""));
// 运行监测页改为由 index.vue 渲染「总体客流变化」样式的站点卡片，禁用本组件的右侧 teleport，避免重复。
const runMonitorSimplifiedRight = inject("runMonitorSimplifiedRight", false);
// 客流分析模式：即使简化（地图/选中复用运行监测），也渲染完整 MCard2 面板
const pfaRightPanel = inject("pfaRightPanel", ref(false));
const pfaStationSection = inject("pfaStationSection", ref("boarding"));
const runMonitorStationOptionFilter = inject("runMonitorStationOptionFilter", () => true);
const shouldRenderPfaRightPanel = computed(() => Boolean(pfaRightPanel?.value ?? pfaRightPanel));

// 全网设施索引（byName/byId → 保序候选列表）：按模型只建一次，选站查询 O(1)
function buildFacilityIndex(lines) {
  const byName = new Map();
  const byId = new Map();
  let ordinal = 0;
  (Array.isArray(lines) ? lines : []).forEach((line) => {
    line.routes?.forEach((route) => {
      route.facilities?.forEach((fac, facIndex) => {
        const entry = { line, route, fac, facIndex, ordinal: ordinal++ };
        const name = fac?.facilityName;
        if (name) {
          if (!byName.has(name)) byName.set(name, []);
          byName.get(name).push(entry);
        }
        const id = fac?.facilityId != null && fac.facilityId !== "" ? String(fac.facilityId) : "";
        if (id) {
          if (!byId.has(id)) byId.set(id, []);
          byId.get(id).push(entry);
        }
      });
    });
  });
  return { byName, byId };
}

// 监听当前选中的站点，控制右侧面板内容状态
watch(selectedStationName, (newStation) => {
  if (activeDatavisualizationTab.value === "站点客流监测") {
    rightPanelHasContent.value = true;
  }
  if (!newStation) {
    selectedReverseStationName.value = "";
    selectedReverseStationFacilityId.value = "";
    selectedReverseStationCoord.value = null;
    cleanUpSelectedStationRing();
    cleanUpReachabilityOverlay();
    cleanUpOdCurveOverlay();
    restoreReachabilityStationFilter();
  }
});

watch(activeDatavisualizationTab, (newTab) => {
  if (newTab === "站点客流监测") {
    rightPanelHasContent.value = true;
  } else {
    cleanUpSelectedStationRing();
    cleanUpReachabilityOverlay();
    cleanUpOdCurveOverlay();
    restoreReachabilityStationFilter();
  }
});

function toFiniteNumber(value, fallback = 0) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function toFiniteCoord(value) {
  if (value === null || value === undefined || value === "") return Number.NaN;
  const number = Number(value);
  return Number.isFinite(number) ? number : Number.NaN;
}

// 时段口径统一为左闭右开 [startHour, endHour)：滑块 [8,18] 表示 08:00-18:00，
// 不再包含 18 点起的桶；滑块上限相应提升到 23 以覆盖 22:00-23:00 桶
function hourSlice(values, startHour = 6, endHour = 23) {
  const source = Array.isArray(values) ? values : [];
  const result = [];
  for (let hour = startHour; hour < endHour; hour++) {
    result.push(toFiniteNumber(source[hour], 0));
  }
  return result;
}

// stations 键的归一化索引按面板数据对象缓存：未命中 fallback 不再逐键归一化全扫
const stationsNormalizedIndexCache = new WeakMap();
function stationsNormalizedIndex(stations) {
  let index = stationsNormalizedIndexCache.get(stations);
  if (!index) {
    index = new Map();
    Object.keys(stations).forEach((key) => {
      const normalized = normalizeStationSearchName(key);
      if (normalized && !index.has(normalized)) index.set(normalized, key);
    });
    stationsNormalizedIndexCache.set(stations, index);
  }
  return index;
}

function stationPanelByName(stationName) {
  const stations = stationPanelData.value?.stations;
  if (!stations || !stationName) return null;
  if (stations[stationName]) return stations[stationName];
  const target = normalizeStationSearchName(stationName);
  const matchedKey = stationsNormalizedIndex(stations).get(target);
  return matchedKey ? stations[matchedKey] : null;
}

// 同一 (站点面板, 设施面板) 的合并结果缓存：保持返回对象身份稳定，
// 避免每次重算都产出新对象、令下游 watch 在内容未变时误判变化触发地图重绘
const sidePanelMergeCache = new WeakMap();
function mergedSidePanel(stationPanel, facilityPanel, stationName) {
  let byFacility = sidePanelMergeCache.get(stationPanel);
  if (!byFacility) {
    byFacility = new Map();
    sidePanelMergeCache.set(stationPanel, byFacility);
  }
  let merged = byFacility.get(facilityPanel);
  if (!merged) {
    merged = {
      ...stationPanel,
      ...facilityPanel,
      stationName: stationPanel.stationName || stationName,
      mode: stationPanel.mode,
      desc: stationPanel.desc,
    };
    byFacility.set(facilityPanel, merged);
  }
  return merged;
}

function stationPanelForSide(stationName, facilityId = "", options = {}) {
  const { fallbackToAggregate = true } = options || {};
  const stationPanel = stationPanelByName(stationName);
  if (!stationPanel) return null;
  const id = String(facilityId || "");
  const facilityPanels = stationPanel.facilityPanels;
  if (id && facilityPanels && typeof facilityPanels === "object") {
    if (facilityPanels[id]) return mergedSidePanel(stationPanel, facilityPanels[id], stationName);
    const matched = Object.values(facilityPanels).find((panel) =>
      String(panel?.facilityId || "") === id
      || (Array.isArray(panel?.facilityIds) && panel.facilityIds.some((candidate) => String(candidate || "") === id))
    );
    if (matched) return mergedSidePanel(stationPanel, matched, stationName);
  }
  return fallbackToAggregate ? stationPanel : null;
}

const currentStationPanel = computed(() => {
  const stationName = selectedStationName.value;
  if (!stationName) return null;
  if (shouldRenderPfaRightPanel.value) {
    const aggregatePanel = stationPanelByName(stationName);
    if (aggregatePanel) return aggregatePanel;
  }
  const isDirectionalPair = Boolean(selectedReverseStationName.value || selectedReverseStationFacilityId.value);
  const sidePanel = stationPanelForSide(stationName, selectedStationFacilityId.value, {
    fallbackToAggregate: !isDirectionalPair && !selectedStationFacilityId.value,
  });
  if (sidePanel) return sidePanel;
  const stations = stationPanelData.value?.stations || {};
  const facilityId = String(selectedStationFacilityId.value || "");
  if (facilityId && !isDirectionalPair) {
    return Object.values(stations).find((station) =>
      Array.isArray(station?.facilityIds)
      && station.facilityIds.some((id) => String(id || "") === facilityId)
    ) || null;
  }
  return null;
});

const currentReverseStationPanel = computed(() => {
  if (!selectedReverseStationName.value && !selectedReverseStationFacilityId.value) return null;
  return stationPanelForSide(
    selectedReverseStationName.value || selectedStationName.value,
    selectedReverseStationFacilityId.value,
    { fallbackToAggregate: false },
  );
});

const selectedStationPanelStatus = computed(() => {
  if (!selectedStationName.value) return { type: "idle", text: "" };
  if (stationPanelStatus.value === "loading") return { type: "loading", text: "站点客流数据加载中" };
  if (stationPanelStatus.value === "generating") return { type: "generating", text: "站点客流缓存生成中，请稍后刷新" };
  if (stationPanelStatus.value === "error") return { type: "error", text: stationPanelError.value || "站点客流数据加载失败" };
  if (stationPanelStatus.value === "ready" && !currentStationPanel.value) {
    return { type: "empty", text: "该站点暂无客流数据" };
  }
  return { type: "ready", text: "" };
});

const stationPanelUnavailable = computed(() =>
  Boolean(selectedStationName.value)
  && ["loading", "generating", "error", "empty"].includes(selectedStationPanelStatus.value.type)
);

const selectedStationType = computed(() => {
  if (!selectedStationName.value) return "";
  if (currentStationPanel.value?.mode) {
    return currentStationPanel.value.mode === "subway" ? "地铁" : "公交";
  }
  const isSubway = matchedRoutes.value.some(r => {
    const text = [r.lineName, r.lineId, r.routeName, r.routeId].filter(Boolean).join(" ").toLowerCase();
    return /地铁|轨道|metro|subway|rail|mtr/.test(text);
  });
  return isSubway ? "地铁" : "公交";
});

const stationMetrics = computed(() => {
  const metrics = currentStationPanel.value?.metrics || {};
  const passenger = toFiniteNumber(metrics.passenger, 0);
  const peakFlow = toFiniteNumber(metrics.peakFlow, 0);
  const population = toFiniteNumber(metrics.population, 0);
  const transferScore = toFiniteNumber(metrics.transferScore, 0);
  return {
    passenger: `${passenger.toLocaleString()} 人次`,
    peakFlow: `${peakFlow.toLocaleString()} 人/小时`,
    population: `${population.toLocaleString()} 人`,
    transferScore: `${transferScore.toFixed(1)} / 10`
  };
});

const stationBoardingSummary = computed(() => {
  const startHour = debouncedSegmentTimeRange.value[0];
  const endHour = debouncedSegmentTimeRange.value[1];
  const boarding = hourSlice(currentStationPanel.value?.boardingByHour, startHour, endHour)
    .reduce((sum, value) => sum + value, 0);
  const alighting = hourSlice(currentStationPanel.value?.alightingByHour, startHour, endHour)
    .reduce((sum, value) => sum + value, 0);
  return {
    boarding: boarding.toLocaleString(),
    alighting: alighting.toLocaleString(),
  };
});

const stationDemographicsGroups = computed(() => {
  return buildPassengerProfileGroups(currentStationPanel.value?.demographics || {});
});
const stationDemographicsRiderCount = computed(() =>
  passengerProfileRiderCount(currentStationPanel.value?.demographics || {})
);

const { proxy } = getCurrentInstance() || {};
const activeChartType = ref("line");

const passengerFlowChartOption = computed(() => {
  const isLine = activeChartType.value === "line";
  const hours = Array.from({ length: 17 }, (_, index) => formatHourRangeLabel(index + 6));
  const data = hourSlice(currentStationPanel.value?.hourlyFlow, 6, 23);

  const linearGradient = (proxy?.$echarts?.graphic?.LinearGradient) || function() { return null; };

  return {
    tooltip: {
      trigger: "axis",
      appendToBody: true,
      extraCssText: "z-index: 999; border-radius: 8px; box-shadow: 0 4px 12px rgba(0,0,0,0.12); border: none;",
      backgroundColor: "rgba(255, 255, 255, 0.98)",
      textStyle: {
        color: "#2d3748",
        fontSize: 12
      },
      formatter: function (params) {
        if (!params || params.length === 0) return "";
        const item = params[0];
        return `
          <div style="font-weight: 600; margin-bottom: 4px; color: #1569de;">${item.name}</div>
          <div style="display: flex; align-items: center; gap: 8px;">
            <span style="display: inline-block; width: 8px; height: 8px; border-radius: 50%; background-color: #1569de;"></span>
            <span>客流量: <strong style="font-size: 13px; color: #1a202c;">${item.value.toLocaleString()} 人次</strong></span>
          </div>
        `;
      }
    },
    grid: {
      top: 25,
      left: 10,
      right: 15,
      bottom: 5,
      containLabel: true
    },
    xAxis: {
      type: "category",
      data: hours,
      axisLine: {
        lineStyle: {
          color: "rgba(21, 105, 222, 0.15)"
        }
      },
      axisLabel: {
        color: "#60758e",
        fontSize: 10,
        interval: 2
      },
      axisTick: {
        show: false
      }
    },
    yAxis: {
      type: "value",
      name: "人次",
      nameTextStyle: {
        color: "#a0aec0",
        fontSize: 10,
        align: "right",
        padding: [0, 8, 0, 0]
      },
      splitLine: {
        lineStyle: {
          color: "rgba(21, 105, 222, 0.06)",
          type: "dashed"
        }
      },
      axisLabel: {
        color: "#60758e",
        fontSize: 10
      }
    },
    series: [
      {
        name: "客流",
        type: isLine ? "line" : "bar",
        smooth: isLine ? 0.35 : false,
        symbol: "circle",
        symbolSize: isLine ? 6 : 0,
        showSymbol: false,
        barWidth: "40%",
        itemStyle: {
          color: "#1569de",
          borderRadius: isLine ? 0 : [4, 4, 0, 0]
        },
        lineStyle: {
          width: 3.5,
          color: "#1569de",
          shadowColor: "rgba(21, 105, 222, 0.3)",
          shadowBlur: 8,
          shadowOffsetY: 3
        },
        areaStyle: isLine ? {
          color: new linearGradient(0, 0, 0, 1, [
            { offset: 0, color: "rgba(21, 105, 222, 0.35)" },
            { offset: 1, color: "rgba(21, 105, 222, 0.01)" }
          ])
        } : null,
        data: data
      }
    ]
  };
});

// 所有站点图层（公交站蓝色圆形图标）
const _StationLayer = new StationLayer({
  zIndex: 1005,
  markerSize: StationSizeRef.value,
});

// 将图层添加到地图
injectSync("MapRef").then((map) => {
  // 组件可能在 MapRef 就绪前被卸载（快速切 tab）：已 dispose 的图层再挂上去会残留
  if (stationPanelDisposed) return;
  // 运行监测由 index.vue 统一绘制模型站点和数据管理同款选中图标。
  if (!runMonitorSimplifiedRight) {
    map.value?.addLayer(_StationLayer);
  }
  scheduleOverlayRefresh();
});

watch(StationSizeRef, (value) => {
  _StationLayer.setMarkerSize(value);
});
watch(BaseMapLineModeRef, () => {
  _StationLayer.hide();
}, { immediate: true });

// 计算所有唯一的站点名称，并转换为 el-select-v2 需要的 options 格式
// 携带 mode（公交/地铁）：供右上角搜索框按当前线网制式过滤候选。
// 同名站被多条线路（含地铁）共用时，只要有一条地铁经过即算地铁站。
function buildStationOptionsBase(lines) {
  const modeByName = new Map();
  (Array.isArray(lines) ? lines : []).forEach(line => {
    if (!line.routes) return;
    line.routes.forEach(route => {
      if (!route.facilities) return;
      const isSubway = inferStationType(line, route) === "subway";
      route.facilities.forEach(fac => {
        const name = fac.facilityName;
        if (!name) return;
        if (isSubway) modeByName.set(name, "metro");
        else if (!modeByName.has(name)) modeByName.set(name, "bus");
      });
    });
  });
  const uniqueNames = Array.from(modeByName.keys()).sort(compareZh);
  return uniqueNames.map(name => ({ value: name, label: name, mode: modeByName.get(name) || "bus" }));
}

// 拆两级：全量候选（扫描+排序）只依赖 rawLines 且按模型缓存，
// 显示范围变化只重跑末级 filter，不再重跑全网扫描
const stationOptions = computed(() => {
  const lines = rawLines.value;
  // 只有 rawLines 已归属该模型时才写模型级缓存，避免加载前的空数据污染缓存
  const base = rawLinesModel && lines.length
    ? getModelDerived(rawLinesModel, "zdzl:stationOptions", () => buildStationOptionsBase(lines))
    : buildStationOptionsBase(lines);
  return base.filter((option) => runMonitorStationOptionFilter(option));
});

// 将站点候选项上抛给 index.vue 的右上角搜索框
const runMonitorStationOptions = inject("runMonitorStationOptions", null);
if (runMonitorStationOptions) {
  watch(stationOptions, (options) => {
    runMonitorStationOptions.value = options || [];
  }, { immediate: true });
  onUnmounted(() => {
    runMonitorStationOptions.value = [];
  });
}

// 运行监测页：把当前选中站点的客流面板与名称上抛给 index.vue 的右侧卡片。
const runMonitorSelectedStationPanel = inject("runMonitorSelectedStationPanel", null);
const runMonitorSelectedReverseStationPanel = inject("runMonitorSelectedReverseStationPanel", null);
const runMonitorSelectedStationName = inject("runMonitorSelectedStationName", null);
const runMonitorSelectedReverseStationName = inject("runMonitorSelectedReverseStationName", null);
const runMonitorStationPanelStatus = inject("runMonitorStationPanelStatus", null);
const runMonitorStationPanelError = inject("runMonitorStationPanelError", null);
if (runMonitorSelectedStationPanel || runMonitorSelectedReverseStationPanel || runMonitorSelectedStationName || runMonitorSelectedReverseStationName || runMonitorStationPanelStatus || runMonitorStationPanelError) {
  watch([currentStationPanel, currentReverseStationPanel, selectedStationName, selectedReverseStationName], () => {
    if (runMonitorSelectedStationPanel) {
      runMonitorSelectedStationPanel.value = currentStationPanel.value || null;
    }
    if (runMonitorSelectedReverseStationPanel) {
      runMonitorSelectedReverseStationPanel.value = currentReverseStationPanel.value || null;
    }
    if (runMonitorSelectedStationName) {
      runMonitorSelectedStationName.value = selectedStationName.value || "";
    }
    if (runMonitorSelectedReverseStationName) {
      runMonitorSelectedReverseStationName.value = selectedReverseStationName.value || "";
    }
  }, { immediate: true });
  watch([selectedStationPanelStatus, stationPanelError], () => {
    if (runMonitorStationPanelStatus) {
      runMonitorStationPanelStatus.value = selectedStationPanelStatus.value.type;
    }
    if (runMonitorStationPanelError) {
      runMonitorStationPanelError.value = selectedStationPanelStatus.value.text || stationPanelError.value || "";
    }
  }, { immediate: true });
  onUnmounted(() => {
    if (runMonitorSelectedStationPanel) runMonitorSelectedStationPanel.value = null;
    if (runMonitorSelectedReverseStationPanel) runMonitorSelectedReverseStationPanel.value = null;
    if (runMonitorSelectedStationName) runMonitorSelectedStationName.value = "";
    if (runMonitorSelectedReverseStationName) runMonitorSelectedReverseStationName.value = "";
    if (runMonitorStationPanelStatus) runMonitorStationPanelStatus.value = "idle";
    if (runMonitorStationPanelError) runMonitorStationPanelError.value = "";
  });
}

// 格式化秒数为 HH:mm
function formatSecondsToTime(seconds) {
  if (seconds === undefined || seconds === null) return "--:--";
  const h = Math.floor(seconds / 3600).toString().padStart(2, "0");
  const m = Math.floor((seconds % 3600) / 60).toString().padStart(2, "0");
  return `${h}:${m}`;
}

function inferStationType(line, route) {
  const text = [
    line?.lineName,
    line?.lineId,
    route?.routeName,
    route?.routeId,
  ].filter(Boolean).join(" ").toLowerCase();
  return /地铁|轨道|metro|subway|rail|mtr/.test(text) ? "subway" : "bus";
}

function routeTopologyKey(line, route) {
  return [
    line?.lineId || line?.lineName || "line",
    route?.routeId || route?.routeName || "route",
  ].join("::");
}

const OPPOSITE_STATION_MAX_DISTANCE_METERS = 180;

function stationCoordKey(x, y) {
  return `${Number(x).toFixed(2)}_${Number(y).toFixed(2)}`;
}

function stationCoordKeyFromStation(station) {
  const x = Number(station?.x);
  const y = Number(station?.y);
  return Number.isFinite(x) && Number.isFinite(y) ? stationCoordKey(x, y) : "";
}

function normalizedStationName(value = "") {
  return normalizeStationSearchName(value);
}

function stationDistance(a, b) {
  if (!a || !b) return Number.POSITIVE_INFINITY;
  const dx = Number(a.x) - Number(b.x);
  const dy = Number(a.y) - Number(b.y);
  return Number.isFinite(dx) && Number.isFinite(dy) ? Math.hypot(dx, dy) : Number.POSITIVE_INFINITY;
}

function buildStationCoordCandidates(lines) {
  const buckets = new Map();
  (Array.isArray(lines) ? lines : []).forEach((line) => {
    (line.routes || []).forEach((route) => {
      (route.facilities || []).forEach((fac) => {
        const name = fac.facilityName;
        const x = Number(fac.coord?.x);
        const y = Number(fac.coord?.y);
        if (!name || !Number.isFinite(x) || !Number.isFinite(y)) return;
        const station = {
          name,
          facilityId: String(fac.facilityId || ""),
          x,
          y,
          type: inferStationType(line, route),
        };
        const bucket = buckets.get(name) || [];
        const coordKey = stationCoordKey(x, y);
        const existing = bucket.find((item) => item.coordKey === coordKey);
        if (existing) {
          if (station.type === "subway") existing.type = "subway";
        } else {
          bucket.push({ ...station, coordKey });
        }
        buckets.set(name, bucket);
      });
    });
  });
  buckets.forEach((stations) => {
    stations.sort((a, b) => {
      if (a.type !== b.type) return a.type === "subway" ? -1 : 1;
      return a.coordKey.localeCompare(b.coordKey);
    });
  });
  return buckets;
}

// 与 facilityIndex/mapStations 同口径按模型缓存：tab 往返重挂载不再重跑全网三层扫描
const stationCoordCandidates = computed(() => {
  const lines = rawLines.value;
  return rawLinesModel && lines.length
    ? getModelDerived(rawLinesModel, "zdzl:coordCandidates", () => buildStationCoordCandidates(lines))
    : buildStationCoordCandidates(lines);
});

const stationCoordIndex = computed(() => {
  const result = new Map();
  stationCoordCandidates.value.forEach((stations, name) => {
    if (stations.length) result.set(name, stations[0]);
  });
  return result;
});

function buildStationNetworkTopology(lines) {
  const nodes = new Map();
  const nodeToRoutes = new Map();
  const routeToNodeList = new Map();
  const nameToNodes = new Map();
  const facilityToNode = new Map();

  (Array.isArray(lines) ? lines : []).forEach((line) => {
    (line.routes || []).forEach((route) => {
      const routeKey = routeTopologyKey(line, route);
      const routeNodeList = routeToNodeList.get(routeKey) || [];
      (route.facilities || []).forEach((fac) => {
        const name = fac.facilityName;
        const x = Number(fac.coord?.x);
        const y = Number(fac.coord?.y);
        if (!name || !Number.isFinite(x) || !Number.isFinite(y)) return;

        const key = stationCoordKey(x, y);
        const facilityId = String(fac.facilityId || "");
        const type = inferStationType(line, route);
        let node = nodes.get(key);
        if (!node) {
          node = {
            key,
            coordKey: key,
            name,
            label: name,
            x,
            y,
            type,
            names: new Set(),
            facilityIds: new Set(),
          };
          nodes.set(key, node);
        }
        node.names.add(name);
        if (facilityId) node.facilityIds.add(facilityId);
        if (type === "subway") node.type = "subway";

        if (routeNodeList[routeNodeList.length - 1] !== key) {
          routeNodeList.push(key);
        }
        const routes = nodeToRoutes.get(key) || new Set();
        routes.add(routeKey);
        nodeToRoutes.set(key, routes);

        const normalized = normalizedStationName(name);
        if (normalized) {
          const sameNameNodes = nameToNodes.get(normalized) || new Set();
          sameNameNodes.add(key);
          nameToNodes.set(normalized, sameNameNodes);
        }
        if (facilityId) {
          facilityToNode.set(facilityId, key);
        }
      });
      if (routeNodeList.length) {
        routeToNodeList.set(routeKey, routeNodeList);
      }
    });
  });

  nodes.forEach((node) => {
    const sortedNames = Array.from(node.names).sort(compareZh);
    node.names = sortedNames;
    node.facilityIds = Array.from(node.facilityIds);
    node.name = sortedNames[0] || node.name;
    node.label = node.name;
  });

  return { nodes, nodeToRoutes, routeToNodeList, nameToNodes, facilityToNode };
}

// 拓扑同样按模型缓存：可达性 BFS 的输入在重挂载后直接命中
const stationNetworkTopology = computed(() => {
  const lines = rawLines.value;
  return rawLinesModel && lines.length
    ? getModelDerived(rawLinesModel, "zdzl:topology", () => buildStationNetworkTopology(lines))
    : buildStationNetworkTopology(lines);
});

function selectedReachabilityNodeKey(topology = stationNetworkTopology.value) {
  const coordKey = stationCoordKeyFromStation(selectedStationCoord.value);
  if (coordKey && topology.nodes.has(coordKey)) return coordKey;
  const facilityKey = String(selectedStationFacilityId.value || "");
  if (facilityKey && topology.facilityToNode.has(facilityKey)) return topology.facilityToNode.get(facilityKey);
  const stationName = normalizedStationName(selectedStationName.value);
  const candidates = Array.from(topology.nameToNodes.get(stationName) || []);
  if (!candidates.length) return "";
  const selectedCoord = selectedStationCoord.value;
  if (selectedCoord) {
    return candidates
      .map((key) => ({ key, distance: stationDistance(selectedCoord, topology.nodes.get(key)) }))
      .sort((a, b) => a.distance - b.distance)[0]?.key || candidates[0];
  }
  return candidates[0];
}

function routesAtNodes(nodeKeys, topology = stationNetworkTopology.value) {
  const routes = new Set();
  nodeKeys.forEach((nodeKey) => {
    (topology.nodeToRoutes.get(nodeKey) || []).forEach((routeId) => routes.add(routeId));
  });
  return routes;
}

function forwardNodesByRoutes(boardingNodeKeys, routeIds, seenNodes, topology = stationNetworkTopology.value) {
  const nodes = new Set();
  routeIds.forEach((routeId) => {
    const nodeList = topology.routeToNodeList.get(routeId) || [];
    nodeList.forEach((nodeKey, index) => {
      if (!boardingNodeKeys.has(nodeKey)) return;
      for (let nextIndex = index + 1; nextIndex < nodeList.length; nextIndex++) {
        const nextNodeKey = nodeList[nextIndex];
        if (!seenNodes.has(nextNodeKey)) {
          nodes.add(nextNodeKey);
        }
      }
    });
  });
  return nodes;
}

function transferNodeGroup(nodeKey, topology = stationNetworkTopology.value) {
  const node = topology.nodes.get(nodeKey);
  if (!node) return new Set();
  const result = new Set([nodeKey]);
  (node.names || []).forEach((name) => {
    const normalized = normalizedStationName(name);
    (topology.nameToNodes.get(normalized) || []).forEach((candidateKey) => {
      const candidate = topology.nodes.get(candidateKey);
      if (!candidate) return;
      if (candidateKey === nodeKey || stationDistance(node, candidate) <= OPPOSITE_STATION_MAX_DISTANCE_METERS) {
        result.add(candidateKey);
      }
    });
  });
  return result;
}

function transferStep(frontierNodeKeys, seenRoutes, seenNodes, topology = stationNetworkTopology.value) {
  const transferNodes = new Set();
  frontierNodeKeys.forEach((nodeKey) => {
    transferNodeGroup(nodeKey, topology).forEach((transferNode) => transferNodes.add(transferNode));
  });
  const routeIds = routesAtNodes(transferNodes, topology);
  seenRoutes.forEach((routeId) => routeIds.delete(routeId));
  return {
    routeIds,
    nodes: forwardNodesByRoutes(transferNodes, routeIds, seenNodes, topology),
  };
}

function normalizeReachabilityLevel(nodeKeys, seenNodes, topology = stationNetworkTopology.value) {
  const levelNodes = new Set();
  nodeKeys.forEach((nodeKey) => {
    transferNodeGroup(nodeKey, topology).forEach((transferNode) => {
      if (!seenNodes.has(transferNode)) {
        levelNodes.add(transferNode);
      }
    });
  });
  levelNodes.forEach((nodeKey) => seenNodes.add(nodeKey));
  return levelNodes;
}

function reachabilityNodePayloads(nodeKeys, topology = stationNetworkTopology.value, options = {}) {
  const { displayRangeOnly = false } = options;
  return Array.from(nodeKeys)
    .map((key) => topology.nodes.get(key))
    .filter(Boolean)
    .filter((node) => !displayRangeOnly || stationInDisplayRange(node))
    .map((node) => ({
      key: node.key,
      coordKey: node.coordKey,
      name: node.name,
      label: node.label || node.name,
      facilityId: Array.isArray(node.facilityIds) ? String(node.facilityIds[0] || "") : "",
      facilityIds: Array.isArray(node.facilityIds) ? node.facilityIds : [],
      x: node.x,
      y: node.y,
      type: node.type || "bus",
    }))
    .sort((a, b) => compareZh(a.label, b.label) || a.key.localeCompare(b.key));
}

const localReachabilityData = computed(() => {
  // 仅可达性区块激活时才计算：两级换乘 BFS + 数千节点过滤排序是点击路径上最大的单笔开销，
  // 依赖 pfaStationSection 保证切入该区块时自动重算
  if (pfaStationSection.value !== "reachability") {
    return {
      ready: false,
      directStations: [],
      transfer1Stations: [],
      transfer2Stations: [],
    };
  }
  const topology = stationNetworkTopology.value;
  const originKey = selectedReachabilityNodeKey(topology);
  if (!originKey || !topology.nodes.has(originKey)) {
    return {
      ready: false,
      directStations: [],
      transfer1Stations: [],
      transfer2Stations: [],
    };
  }

  const originGroup = transferNodeGroup(originKey, topology);
  const seenNodes = new Set(originGroup);
  const directRoutes = routesAtNodes(originGroup, topology);
  const direct = normalizeReachabilityLevel(
    forwardNodesByRoutes(originGroup, directRoutes, seenNodes, topology),
    seenNodes,
    topology,
  );

  const seenRoutes = new Set(directRoutes);
  const transfer1Step = transferStep(direct, seenRoutes, seenNodes, topology);
  transfer1Step.routeIds.forEach((routeId) => seenRoutes.add(routeId));
  const transfer1 = normalizeReachabilityLevel(transfer1Step.nodes, seenNodes, topology);

  const transfer2Step = transferStep(transfer1, seenRoutes, seenNodes, topology);
  const transfer2 = normalizeReachabilityLevel(transfer2Step.nodes, seenNodes, topology);

  return {
    ready: true,
    originKey,
    originStation: topology.nodes.get(originKey),
    directStations: reachabilityNodePayloads(direct, topology, { displayRangeOnly: true }),
    transfer1Stations: reachabilityNodePayloads(transfer1, topology, { displayRangeOnly: true }),
    transfer2Stations: reachabilityNodePayloads(transfer2, topology, { displayRangeOnly: true }),
  };
});

const SELECTED_STATION_RING_SOURCE_ID = "selected-station-ring-source";
const SELECTED_STATION_RING_LAYER_ID = "selected-station-ring-layer";
const REACHABILITY_SOURCE_ID = "station-reachability-source";
const REACHABILITY_LAYER_PREFIX = "station-reachability-line";
const REACHABILITY_LEVEL_KEYS = ["direct", "transfer1", "transfer2"];
// 需求8：客流OD地图曲线（贝塞尔弧线 + 起点站名标签）
const OD_CURVE_SOURCE_ID = "station-od-curve-source";
const OD_CURVE_LAYER_ID = "station-od-curve-layer";
const OD_CURVE_CASING_LAYER_ID = "station-od-curve-casing-layer";
const OD_CURVE_LABEL_SOURCE_ID = "station-od-curve-label-source";
const OD_CURVE_LABEL_LAYER_ID = "station-od-curve-label-layer";
const RM_SOURCE_STATIONS = "rm-bus-network-stations-source";
const RM_LAYER_STATIONS = "rm-bus-network-stations";

function updateSelectedStationRing(coord) {
  if (runMonitorSimplifiedRight && !shouldRenderPfaRightPanel.value) {
    cleanUpSelectedStationRing();
    return;
  }
  if (!MapRef.value || !MapRef.value.map) return;
  const map = MapRef.value.map;

  if (!coord) {
    cleanUpSelectedStationRing();
    return;
  }

  const [lng, lat] = webMercatorToLngLat(coord.x, coord.y);
  if (![lng, lat].every(Number.isFinite)) {
    cleanUpSelectedStationRing();
    return;
  }

  const geojson = {
    type: "FeatureCollection",
    features: [{
      type: "Feature",
      geometry: { type: "Point", coordinates: [lng, lat] },
      properties: {}
    }]
  };

  if (!map.getSource(SELECTED_STATION_RING_SOURCE_ID)) {
    map.addSource(SELECTED_STATION_RING_SOURCE_ID, {
      type: "geojson",
      data: geojson
    });

    map.addLayer({
      id: SELECTED_STATION_RING_LAYER_ID,
      type: "circle",
      source: SELECTED_STATION_RING_SOURCE_ID,
      paint: {
        "circle-radius": [
          "interpolate",
          ["linear"],
          ["zoom"],
          10, 2,
          12, 5,
          14, 10,
          16, 17,
          18, 25
        ],
        "circle-color": "rgba(0, 113, 227, 0.04)",
        "circle-stroke-color": "#0071e3",
        "circle-stroke-width": [
          "interpolate",
          ["linear"],
          ["zoom"],
          10, 1.2,
          13, 3,
          16, 5.5,
          18, 8
        ],
        "circle-stroke-opacity": 0.96,
        "circle-blur": 0.08
      }
    });
  } else {
    map.getSource(SELECTED_STATION_RING_SOURCE_ID).setData(geojson);
  }
}

function cleanUpSelectedStationRing() {
  if (!MapRef.value || !MapRef.value.map) return;
  const map = MapRef.value.map;
  if (map.getLayer(SELECTED_STATION_RING_LAYER_ID)) map.removeLayer(SELECTED_STATION_RING_LAYER_ID);
  if (map.getSource(SELECTED_STATION_RING_SOURCE_ID)) map.removeSource(SELECTED_STATION_RING_SOURCE_ID);
}

function emptyFeatureCollection() {
  return {
    type: "FeatureCollection",
    features: [],
  };
}

function stationLngLat(station) {
  if (!station) return null;
  const coords = webMercatorToLngLat(station.x, station.y);
  return coords.every(Number.isFinite) ? coords : null;
}

function stationCoordByName(stationName) {
  const rawName = String(stationName || "").trim();
  if (!rawName) return null;
  const direct = stationCoordIndex.value.get(rawName);
  if (direct) return direct;
  const normalized = normalizeStationSearchName(rawName);
  if (!normalized) return null;
  for (const [name, coord] of stationCoordIndex.value.entries()) {
    if (normalizeStationSearchName(name) === normalized) return coord;
  }
  return null;
}

function stationFeature(station, index = 0) {
  const coordinates = stationLngLat(station);
  if (!coordinates) return null;
  const key = station.facilityId || `${station.name}-${station.x}-${station.y}-${index}`;
  return {
    type: "Feature",
    id: key,
    geometry: { type: "Point", coordinates },
    properties: {
      facilityName: station.name,
      stop_name: station.name,
      name: station.name,
      facilityId: key,
      type: station.type || "bus",
      _stationKey: key,
    },
  };
}

function stationsFeatureCollection(stations = []) {
  return {
    type: "FeatureCollection",
    features: stations.map(stationFeature).filter(Boolean),
  };
}

function reachabilityOriginStation() {
  return localReachabilityData.value.originStation || selectedStationCoord.value || stationCoordIndex.value.get(selectedStationName.value);
}

function reachabilityStationKey(station) {
  if (!station) return "";
  if (typeof station === "string") return station;
  return station.key || station.coordKey || station.name || "";
}

function reachabilityStationLabel(station) {
  if (!station) return "";
  if (typeof station === "string") return station;
  return station.label || station.name || station.key || "";
}

function reachabilityStationCoord(station) {
  if (!station) return null;
  if (typeof station === "string") return stationCoordIndex.value.get(station) || null;
  return station;
}

// 站点 → 过滤用 option 包装的缓存：显示范围反复变化时不再为全网站点重复分配对象
const stationRangeOptionCache = new WeakMap();
function stationRangeOption(station) {
  const cacheable = station && typeof station === "object";
  if (cacheable) {
    const cached = stationRangeOptionCache.get(station);
    if (cached) return cached;
  }
  const name = station?.name || station?.label || "";
  const x = Number(station?.x ?? station?.coord?.x);
  const y = Number(station?.y ?? station?.coord?.y);
  const coord = Number.isFinite(x) && Number.isFinite(y) ? { x, y } : station?.coord || null;
  const facilityIds = Array.isArray(station?.facilityIds) ? station.facilityIds : [];
  const option = {
    value: name,
    label: name,
    facilityId: String(station?.facilityId || facilityIds[0] || ""),
    coord,
  };
  if (cacheable) stationRangeOptionCache.set(station, option);
  return option;
}

function stationInDisplayRange(station) {
  if (!station) return false;
  return runMonitorStationOptionFilter(stationRangeOption(station));
}

function filterStationsByDisplayRange(stations = []) {
  return (Array.isArray(stations) ? stations : []).filter((station) => stationInDisplayRange(station));
}

const displayRangeStations = computed(() => filterStationsByDisplayRange(allMapStations.value));

const selectedOnlyStations = computed(() => {
  if (!selectedStationName.value) return [];
  const coordKey = selectedStationCoord.value ? stationCoordKeyFromStation(selectedStationCoord.value) : "";
  const facilityId = String(selectedStationFacilityId.value || "");
  const normalizedName = normalizedStationName(selectedStationName.value);
  const sourceStations = displayRangeStations.value;
  let stations = [];
  if (coordKey) {
    stations = sourceStations.filter(
      (station) => (station.key || station.coordKey || stationCoordKeyFromStation(station)) === coordKey,
    );
  }
  if (!stations.length && facilityId) {
    stations = sourceStations.filter((station) => String(station.facilityId || "") === facilityId);
  }
  if (!stations.length && normalizedName) {
    const station = sourceStations.find((item) => normalizedStationName(item.name) === normalizedName);
    if (station) stations = [station];
  }
  if (!stations.length && selectedStationCoord.value) {
    const fallback = {
      key: coordKey || selectedStationName.value,
      coordKey: coordKey || selectedStationName.value,
      name: selectedStationName.value,
      facilityId,
      x: selectedStationCoord.value.x,
      y: selectedStationCoord.value.y,
      type: selectedStationType.value === "地铁" ? "subway" : "bus",
    };
    if (stationInDisplayRange(fallback)) stations = [fallback];
  }
  const result = stations.slice(0, 1);
  const reverseCoordKey = selectedReverseStationCoord.value ? stationCoordKeyFromStation(selectedReverseStationCoord.value) : "";
  const reverseFacilityId = String(selectedReverseStationFacilityId.value || "");
  let reverseStation = null;
  if (reverseCoordKey) {
    reverseStation = sourceStations.find(
      (station) => (station.key || station.coordKey || stationCoordKeyFromStation(station)) === reverseCoordKey,
    );
  }
  if (!reverseStation && reverseFacilityId) {
    reverseStation = sourceStations.find((station) => String(station.facilityId || "") === reverseFacilityId);
  }
  if (!reverseStation && selectedReverseStationCoord.value) {
    reverseStation = {
      key: reverseCoordKey || selectedReverseStationName.value,
      coordKey: reverseCoordKey || selectedReverseStationName.value,
      name: selectedReverseStationName.value || selectedStationName.value,
      facilityId: reverseFacilityId,
      x: selectedReverseStationCoord.value.x,
      y: selectedReverseStationCoord.value.y,
      type: selectedStationType.value === "地铁" ? "subway" : "bus",
    };
  }
  if (reverseStation && stationInDisplayRange(reverseStation)) {
    const reverseKey = reverseStation.key || reverseStation.coordKey || stationCoordKeyFromStation(reverseStation);
    if (!result.some((station) => (station.key || station.coordKey || stationCoordKeyFromStation(station)) === reverseKey)) {
      result.push(reverseStation);
    }
  }
  return result;
});

const reachabilityVisibleStations = computed(() => {
  const showReachability = shouldRenderPfaRightPanel.value && pfaStationSection.value === "reachability" && selectedStationName.value;
  if (!showReachability) {
    if (shouldRenderPfaRightPanel.value && selectedStationName.value) {
      return selectedOnlyStations.value;
    }
    return displayRangeStations.value;
  }
  // 可达性：底图只保留出发站图标，三类可达站点由彩色圆点图层绘制（避免图标与圆点重叠）
  return selectedOnlyStations.value;
});

function setRunMonitorStationSource(stations) {
  const source = MapRef.value?.map?.getSource(RM_SOURCE_STATIONS);
  if (!source?.setData) return;
  source.setData(stationsFeatureCollection(stations));
}

// 上次喂给图层的站点数组引用：reachabilityVisibleStations 是 computed，
// 依赖未变时数组身份稳定，引用相同说明图层数据已是最新，跳过重建 FeatureCollection 与 setData
let lastAppliedStationsRef = null;

function applyStationsToLayers(stations, force = false) {
  if (!force && stations === lastAppliedStationsRef) return;
  lastAppliedStationsRef = stations;
  if (!runMonitorSimplifiedRight) {
    _StationLayer.setData(stations);
  }
  setRunMonitorStationSource(stations);
}

function applyReachabilityStationFilter() {
  applyStationsToLayers(reachabilityVisibleStations.value);
}

function restoreReachabilityStationFilter() {
  const stations = reachabilityVisibleStations.value;
  if (!stations.length && !allMapStations.value.length) return;
  applyStationsToLayers(stations);
}

function addMapLayer(map, layer, beforeId = RM_LAYER_STATIONS) {
  if (map.getLayer(layer.id)) return;
  if (beforeId && map.getLayer(beforeId)) {
    map.addLayer(layer, beforeId);
  } else {
    map.addLayer(layer);
  }
}

function ensureReachabilityLayers() {
  const map = MapRef.value?.map;
  if (!map) return null;
  if (!map.getSource(REACHABILITY_SOURCE_ID)) {
    map.addSource(REACHABILITY_SOURCE_ID, {
      type: "geojson",
      data: emptyFeatureCollection(),
    });
  }
  // 三类可达站点改为彩色圆点（与右侧面板分组颜色一致），置于顶层，不再绘制曲线
  reachabilityGroups.value.forEach((group) => {
    addMapLayer(map, {
      id: `${REACHABILITY_LAYER_PREFIX}-${group.key}`,
      type: "circle",
      source: REACHABILITY_SOURCE_ID,
      filter: ["==", ["get", "level"], group.key],
      paint: {
        "circle-radius": [
          "interpolate",
          ["linear"],
          ["zoom"],
          9, 3,
          12, 4.6,
          15, 7,
        ],
        "circle-color": group.color,
        "circle-opacity": 0.95,
        "circle-stroke-color": "#ffffff",
        "circle-stroke-width": 1.4,
        "circle-stroke-opacity": 0.95,
      },
    }, null);
  });
  return map.getSource(REACHABILITY_SOURCE_ID);
}

function reachabilityFeatureCollection() {
  if (!shouldRenderPfaRightPanel.value || pfaStationSection.value !== "reachability" || !selectedStationName.value) {
    return emptyFeatureCollection();
  }
  const originStation = reachabilityOriginStation();
  const origin = stationLngLat(originStation);
  if (!origin) return emptyFeatureCollection();
  const features = [];
  const emitted = new Set();
  reachabilityGroups.value.forEach((group) => {
    const stations = Array.isArray(group.mapStations) ? group.mapStations : group.stations;
    stations.forEach((station) => {
      const stationKey = reachabilityStationKey(station);
      if (!stationKey || stationKey === localReachabilityData.value.originKey) return;
      const targetStation = reachabilityStationCoord(station);
      const target = stationLngLat(targetStation);
      if (!target) return;
      const key = `${group.key}-${stationKey}`;
      if (emitted.has(key)) return;
      emitted.add(key);
      // 三类可达站点画为圆点（不再从出发站拉曲线）
      features.push({
        type: "Feature",
        geometry: {
          type: "Point",
          coordinates: target,
        },
        properties: {
          level: group.key,
          levelLabel: group.label,
          stationName: reachabilityStationLabel(station),
        },
      });
    });
  });
  return {
    type: "FeatureCollection",
    features,
  };
}

function renderReachabilityOverlay() {
  const map = MapRef.value?.map;
  if (!map) return;
  if (!shouldRenderPfaRightPanel.value || pfaStationSection.value !== "reachability" || !selectedStationName.value) {
    cleanUpReachabilityOverlay();
    return;
  }
  const source = ensureReachabilityLayers();
  if (!source) return;
  const data = reachabilityFeatureCollection();
  source.setData(data);
  applyReachabilityLevelVisibility();
}

function cleanUpReachabilityOverlay() {
  const map = MapRef.value?.map;
  restoreReachabilityStationFilter();
  if (!map) return;
  REACHABILITY_LEVEL_KEYS.forEach((key) => {
    const layerId = `${REACHABILITY_LAYER_PREFIX}-${key}`;
    if (map.getLayer(layerId)) map.removeLayer(layerId);
  });
  if (map.getSource(REACHABILITY_SOURCE_ID)) map.removeSource(REACHABILITY_SOURCE_ID);
}

// —— 需求10：可达性分组显隐 ——
function setReachabilityLevelVisible(key, visible) {
  // 整值替换，遵循组件内 shallow 更新习惯
  reachabilityLevelVisibility.value = { ...reachabilityLevelVisibility.value, [key]: Boolean(visible) };
}

function applyReachabilityLevelVisibility() {
  const map = MapRef.value?.map;
  if (!map) return;
  REACHABILITY_LEVEL_KEYS.forEach((key) => {
    const visible = reachabilityLevelVisibility.value[key] !== false;
    const layerId = `${REACHABILITY_LAYER_PREFIX}-${key}`;
    if (map.getLayer(layerId)) {
      map.setLayoutProperty(layerId, "visibility", visible ? "visible" : "none");
    }
  });
}

// —— 需求8：客流OD地图曲线 ——
function odCurveOverlayActive() {
  return shouldRenderPfaRightPanel.value && pfaStationSection.value === "od" && Boolean(selectedStationName.value);
}

function ensureOdCurveLayers() {
  const map = MapRef.value?.map;
  if (!map) return false;
  if (!map.getSource(OD_CURVE_SOURCE_ID)) {
    map.addSource(OD_CURVE_SOURCE_ID, { type: "geojson", data: emptyFeatureCollection() });
  }
  if (!map.getSource(OD_CURVE_LABEL_SOURCE_ID)) {
    map.addSource(OD_CURVE_LABEL_SOURCE_ID, { type: "geojson", data: emptyFeatureCollection() });
  }
  // 白色描边衬底：略宽于曲线本体，密集底图上边缘更利落（与线网/期望线 casing 同一语言）
  addMapLayer(map, {
    id: OD_CURVE_CASING_LAYER_ID,
    type: "line",
    source: OD_CURVE_SOURCE_ID,
    layout: {
      "line-cap": "round",
      "line-join": "round",
    },
    paint: {
      "line-color": "#ffffff",
      "line-width": ["+", ["coalesce", ["get", "width"], 3.4], 1.8],
      "line-opacity": 0.5,
    },
  });
  addMapLayer(map, {
    id: OD_CURVE_LAYER_ID,
    type: "line",
    source: OD_CURVE_SOURCE_ID,
    layout: {
      "line-cap": "round",
      "line-join": "round",
    },
    paint: {
      "line-color": ["coalesce", ["get", "color"], "#f03b20"],
      "line-width": ["coalesce", ["get", "width"], 3.4],
      "line-opacity": 0.92,
      "line-blur": 0.2,
    },
  });
  // 站名标签置于图层栈顶（不传 beforeId），避免被站点图标压住
  addMapLayer(map, {
    id: OD_CURVE_LABEL_LAYER_ID,
    type: "symbol",
    source: OD_CURVE_LABEL_SOURCE_ID,
    layout: {
      "text-field": ["coalesce", ["get", "stationName"], ""],
      "text-size": 11,
      "text-anchor": "left",
      "text-offset": [0.55, 0],
      "text-max-width": 12,
      "text-padding": 2,
      "text-allow-overlap": true,
      "text-ignore-placement": true,
    },
    paint: {
      "text-color": "#12304f",
      "text-halo-color": "rgba(255, 255, 255, 0.95)",
      "text-halo-width": 1.4,
      "text-halo-blur": 0.3,
    },
  }, null);
  return true;
}

function odCurveOverlayData() {
  const empty = { curves: emptyFeatureCollection(), labels: emptyFeatureCollection() };
  const items = odCurveEntries.value.items;
  if (!items.length) return empty;
  const selfCoord = stationLngLat(reachabilityOriginStation());
  if (!selfCoord) return empty;
  const { colors, thresholds, widths } = resolveColorScale(odCurveScaleConfig.value);
  // 分位数断点：由当前OD客流分布计算（与左下角图例同口径）
  const breaks = quantileBreaks(items.map((item) => item.flow), thresholds);
  // 线宽按分档线宽系数（客流越大越粗）× 基础值，并给最低档兜底，保证浅黄色线在底图上可见。
  const OD_BASE_WIDTH = 2.6;
  const OD_MIN_WIDTH = 3.4;
  const flows = [];
  const labelFeatures = [];
  items.forEach((item) => {
    const remote = [item.lng, item.lat];
    // direction === "both" 固定以本站为起点，避免曲线几何方向随数据顺序漂移
    const inbound = item.direction === "inbound";
    const classIndex = classifyByBreaks(item.flow, breaks);
    const width = Math.round(Math.max(OD_MIN_WIDTH, OD_BASE_WIDTH * (widths[classIndex] || 1)) * 10) / 10;
    flows.push({
      from: inbound ? remote : selfCoord,
      to: inbound ? selfCoord : remote,
      value: item.flow,
      properties: {
        color: colors[classIndex] || colors[colors.length - 1],
        width,
        stationName: item.name,
        flow: item.flow,
        inboundFlow: item.inboundFlow,
        outboundFlow: item.outboundFlow,
        direction: item.direction,
      },
    });
    if (item.name) {
      labelFeatures.push({
        type: "Feature",
        geometry: { type: "Point", coordinates: remote },
        properties: { stationName: item.name, flow: item.flow },
      });
    }
  });
  return {
    curves: buildFlowCurveFeatureCollection(flows, { curvature: 0.22 }),
    labels: { type: "FeatureCollection", features: labelFeatures },
  };
}

function renderOdCurveOverlay() {
  const map = MapRef.value?.map;
  if (!map) return;
  if (!odCurveOverlayActive()) {
    cleanUpOdCurveOverlay();
    return;
  }
  if (!ensureOdCurveLayers()) return;
  const { curves, labels } = odCurveOverlayData();
  map.getSource(OD_CURVE_SOURCE_ID)?.setData(curves);
  map.getSource(OD_CURVE_LABEL_SOURCE_ID)?.setData(labels);
}

function cleanUpOdCurveOverlay() {
  const map = MapRef.value?.map;
  if (!map) return;
  [OD_CURVE_LABEL_LAYER_ID, OD_CURVE_LAYER_ID, OD_CURVE_CASING_LAYER_ID].forEach((layerId) => {
    if (map.getLayer(layerId)) map.removeLayer(layerId);
  });
  [OD_CURVE_LABEL_SOURCE_ID, OD_CURVE_SOURCE_ID].forEach((sourceId) => {
    if (map.getSource(sourceId)) map.removeSource(sourceId);
  });
}

// 地图 overlay（可达圆点/OD曲线/选中圈/站点过滤）刷新合并调度：
// 一次交互内 watch 与事件处理多处触发时只在同一 nextTick 里各重建一次
let overlayRefreshScheduled = false;
function scheduleOverlayRefresh() {
  if (overlayRefreshScheduled) return;
  overlayRefreshScheduled = true;
  nextTick(() => {
    overlayRefreshScheduled = false;
    if (stationPanelDisposed) return;
    renderReachabilityOverlay();
    applyReachabilityStationFilter();
    renderOdCurveOverlay();
    if (shouldRenderPfaRightPanel.value && selectedStationName.value) {
      const coord = reachabilityOriginStation();
      if (coord) updateSelectedStationRing(coord);
    } else {
      cleanUpSelectedStationRing();
    }
  });
}

function normalizeStationSearchName(value = "") {
  return String(value || "")
    .trim()
    .replace(/\s+/g, "")
    .toLowerCase();
}

function stationCoordDistance(a, b) {
  if (!a || !b) return Number.POSITIVE_INFINITY;
  const dx = Number(a.x) - Number(b.x);
  const dy = Number(a.y) - Number(b.y);
  return Number.isFinite(dx) && Number.isFinite(dy) ? Math.hypot(dx, dy) : Number.POSITIVE_INFINITY;
}

function oppositeStationCandidate(stationName, facilityId = "", coord = null) {
  const candidates = stationCoordCandidates.value.get(stationName) || [];
  if (candidates.length < 2) return null;
  const currentFacilityId = String(facilityId || "");
  const currentCoordKey = coord ? stationCoordKey(coord.x, coord.y) : "";
  return candidates
    .filter((candidate) => {
      if (currentFacilityId && String(candidate.facilityId || "") === currentFacilityId) return false;
      if (currentCoordKey && String(candidate.coordKey || "") === currentCoordKey) return false;
      return true;
    })
    .sort((left, right) => stationCoordDistance(coord, left) - stationCoordDistance(coord, right))[0] || null;
}

// 选站调用序号：nextTick 恢复后若已被更新调用超越则放弃后续步骤，
// 避免快速连选时旧调用的 handleStationChange 造成双跳/瞬态覆盖
let stationSelectSeq = 0;

async function selectStationByName(stationName) {
  const target = normalizeStationSearchName(stationName);
  if (!target) return false;
  const option =
    stationOptions.value.find((item) => normalizeStationSearchName(item.value) === target) ||
    stationOptions.value.find((item) => {
      const name = normalizeStationSearchName(item.value);
      return name.includes(target) || target.includes(name);
    });
  if (!option?.value) return false;
  const seq = ++stationSelectSeq;
  const primary = (stationCoordCandidates.value.get(option.value) || [])[0] || null;
  const reverse = oppositeStationCandidate(option.value, primary?.facilityId, primary);
  selectedStationFacilityId.value = String(primary?.facilityId || "");
  selectedStationName.value = option.value;
  await nextTick();
  if (seq !== stationSelectSeq) return true;
  handleStationChange(option.value, selectedStationFacilityId.value, reverse);
  return true;
}

async function selectStationByFeature(props = {}) {
  const stationName = props.facilityName || props.stop_name || props.station_name || props.name || "";
  if (!stationName) return false;
  const seq = ++stationSelectSeq;
  const facilityId = String(props.facilityId || props.stop_id || props._stationKey || "");
  const paired = {
    name: props.pairedStationName || stationName,
    facilityId: String(props.pairedFacilityId || ""),
    x: Number(props.pairedCoord?.x),
    y: Number(props.pairedCoord?.y),
  };
  selectedStationFacilityId.value = facilityId;
  selectedStationName.value = stationName;
  await nextTick();
  if (seq !== stationSelectSeq) return true;
  handleStationChange(
    stationName,
    facilityId,
    paired.facilityId || (Number.isFinite(paired.x) && Number.isFinite(paired.y)) ? paired : null,
  );
  return true;
}

async function selectLeaderboardStation(item) {
  const found = await selectStationByName(item?.stationName);
  if (!found) {
    proxy?.$message?.warning("该站点不在当前显示范围内，无法定位");
  }
}

// 切换站点时
function handleStationChange(stationName, facilityId = "", pairedStation = null) {
  // 站点变更/清空时关闭热力图弹窗，避免残留上一站点的弹窗
  boardingHeatmapVisible.value = false;
  if (!stationName) {
    selectedStationFacilityId.value = "";
    selectedStationCoord.value = null;
    selectedReverseStationName.value = "";
    selectedReverseStationFacilityId.value = "";
    selectedReverseStationCoord.value = null;
    matchedRoutes.value = [];
    cleanUpSelectedStationRing();
    cleanUpReachabilityOverlay();
    return;
  }

  selectedStationFacilityId.value = String(facilityId || "");
  selectedReverseStationName.value = "";
  selectedReverseStationFacilityId.value = "";
  selectedReverseStationCoord.value = null;

  const matches = [];
  let stationCoord = null;
  let exactStationCoord = null;
  const matchPhysicalStation = shouldRenderPfaRightPanel.value;

  // 每次选站不再全网三层扫描（线路×路线×设施 数万次 + 每设施一个临时对象），
  // 改为查模型级预建索引（byName/byId），只对少量候选跑范围过滤；语义与原 find 一致：
  // 每条 route 取设施序最小且通过过滤的命中，结果按原全网遍历序（ordinal）排列
  const facilityIndex = getModelDerived(rawLinesModel || props.model, "zdzl:facilityIndex", () => buildFacilityIndex(rawLines.value));
  const candidateEntries = [];
  if (selectedStationFacilityId.value) {
    candidateEntries.push(...(facilityIndex.byId.get(selectedStationFacilityId.value) || []));
  }
  if (!selectedStationFacilityId.value || matchPhysicalStation) {
    candidateEntries.push(...(facilityIndex.byName.get(stationName) || []));
  }
  const winnerByRoute = new Map();
  for (const entry of candidateEntries) {
    const fac = entry.fac;
    if (!runMonitorStationOptionFilter({
      value: fac.facilityName,
      label: fac.facilityName,
      facilityId: fac.facilityId,
      coord: fac.coord,
    })) {
      continue;
    }
    const existing = winnerByRoute.get(entry.route);
    if (!existing || entry.facIndex < existing.facIndex) {
      winnerByRoute.set(entry.route, entry);
    }
  }
  const winners = Array.from(winnerByRoute.values()).sort((a, b) => a.ordinal - b.ordinal);
  for (const { line, route, fac } of winners) {
    if (selectedStationFacilityId.value && String(fac.facilityId || "") === selectedStationFacilityId.value && fac.coord) {
      exactStationCoord = fac.coord;
    }
    if (!stationCoord && fac.coord) {
      stationCoord = fac.coord;
    }
    matches.push({
      lineId: line.lineId,
      lineName: line.lineName,
      routeId: route.routeId,
      routeName: route.routeName,
      info: route.info,
      links: route.links,
      facilities: route.facilities,
      stationCoord: fac.coord,
      // 预计算制式，模板不再每行跑正则
      isSubway: inferStationType(line, route) === "subway",
    });
  }

  matchedRoutes.value = matches;
  selectedStationCoord.value = exactStationCoord || stationCoord || stationCoordIndex.value.get(stationName) || null;
  const reverseCandidate = pairedStation
    || oppositeStationCandidate(stationName, selectedStationFacilityId.value, selectedStationCoord.value);
  if (reverseCandidate?.facilityId || reverseCandidate?.name) {
    selectedReverseStationName.value = reverseCandidate.name || stationName;
    selectedReverseStationFacilityId.value = String(reverseCandidate.facilityId || "");
    if (Number.isFinite(Number(reverseCandidate.x)) && Number.isFinite(Number(reverseCandidate.y))) {
      selectedReverseStationCoord.value = { x: Number(reverseCandidate.x), y: Number(reverseCandidate.y) };
    }
  }
  if ((runMonitorSimplifiedRight || shouldRenderPfaRightPanel.value) && !stationPanelData.value) {
    ensureStationPanelData();
  }

  const displayCoord = selectedStationCoord.value;

  if (displayCoord && MapRef.value && (!runMonitorSimplifiedRight || shouldRenderPfaRightPanel.value)) {
    // 合并为一次 jumpTo，避免两次相机变更/重绘
    MapRef.value.setCenterAndZoom([displayCoord.x, displayCoord.y], 15.5);
  }
  // 选中圈/可达 overlay/OD曲线统一走合并调度：与状态 watch 同帧去重，选站只重建一次
  scheduleOverlayRefresh();
}

function clearStationPanelRetry() {
  if (stationPanelRetryTimer) {
    clearTimeout(stationPanelRetryTimer);
    stationPanelRetryTimer = null;
  }
}

function scheduleStationPanelRetry(model) {
  if (stationPanelDisposed) return;
  if (!model || props.model !== model || stationPanelData.value || stationPanelRetryTimer) return;
  if (stationPanelRetryCount >= 120) {
    stationPanelStatus.value = "error";
    stationPanelError.value = "站点客流缓存生成超时";
    return;
  }
  stationPanelStatus.value = "generating";
  stationPanelError.value = "";
  stationPanelRetryCount += 1;
  const delay = Math.min(10_000, 2_000 + stationPanelRetryCount * 500);
  stationPanelRetryTimer = setTimeout(() => {
    stationPanelRetryTimer = null;
    if (props.model === model && !stationPanelData.value) {
      ensureStationPanelData();
    }
  }, delay);
}

function shouldRetryStationPanelError(error) {
  if (isCanceledRequest(error)) return false;
  if (stationPanelRetryCount < 8) return true;
  const message = String(error?.message || "");
  return /超时|网关|服务|服务器|连接|Network|timeout|temporar/i.test(message);
}

function ensureStationPanelData(options = {}) {
  const { force = false } = options;
  if (stationPanelData.value && !force) return Promise.resolve(stationPanelData.value);
  if (stationPanelPromise) return stationPanelPromise;
  const model = props.model;
  if (!model) return Promise.resolve(null);
  if (force) {
    stationPanelData.value = null;
    clearStationPanelRetry();
    stationPanelRetryCount = 0;
  }
  stationPanelStatus.value = "loading";
  stationPanelError.value = "";
  // 整包站点客流面板改走共享缓存：按模型键控 + 并发去重，请求中止由 modelDataCache 统一管理；
  // 后端生成中（status === "generating"）的结果不会入缓存，重试时会重新请求
  stationPanelPromise = getCachedStationPanel(model)
    .then((data) => {
      if (stationPanelDisposed) return null;
      if (props.model === model && data?.stations) {
        stationPanelData.value = data;
        stationPanelStatus.value = "ready";
        stationPanelError.value = "";
        clearStationPanelRetry();
        stationPanelRetryCount = 0;
      } else if (props.model === model) {
        stationPanelData.value = null;
        scheduleStationPanelRetry(model);
      }
      return stationPanelData.value;
    })
    .catch((error) => {
      if (isCanceledRequest(error) || stationPanelDisposed) return null;
      if (props.model === model) {
        stationPanelData.value = null;
        if (shouldRetryStationPanelError(error)) {
          stationPanelError.value = "";
          scheduleStationPanelRetry(model);
        } else {
          stationPanelStatus.value = "error";
          stationPanelError.value = error?.message || "站点客流数据加载失败";
        }
      }
      return null;
    })
    .finally(() => {
      stationPanelPromise = null;
    });
  return stationPanelPromise;
}

// 线路/站点摘要先到先用；大体积站点客流面板异步补齐。
async function loadAllData() {
  const model = props.model;
  loading.value = true;
  abortOtherModelDataRequests(model);
  stationPanelData.value = null;
  stationPanelStatus.value = "idle";
  stationPanelError.value = "";
  allMapStations.value = [];
  clearStationPanelRetry();
  stationPanelRetryCount = 0;
  // 站点面板整包预取延到空闲：保持「首次点站命中本地缓存」的体验，
  // 但不与首屏地图渲染/线网加载抢主线程（整包 JSON.parse 可达数百 ms）
  runWhenIdle(() => {
    if (!stationPanelDisposed && props.model === model) ensureStationPanelData();
  });
  try {
      const lineRes = await getCachedLineAll(model);
      if (props.model !== model) return;
      const data = Array.isArray(lineRes) ? lineRes : [];
      rawLinesModel = model;
      rawLines.value = data;

      // 站点提取（按坐标去重）按模型记忆化：重挂载/切 tab 直接命中，不再全网三层扫描
      const stationsList = getModelDerived(model, "zdzl:mapStations", () => {
        const list = [];
        const coordsSet = new Set();
        const stationByCoord = new Map();
        data.forEach((line) => {
          line.routes?.forEach((route) => {
            route.facilities?.forEach((fac) => {
              if (fac.coord && fac.facilityName && fac.coord.x && fac.coord.y) {
                const key = stationCoordKey(fac.coord.x, fac.coord.y);
                const type = inferStationType(line, route);
                if (!coordsSet.has(key)) {
                  coordsSet.add(key);
                  const station = {
                    key,
                    coordKey: key,
                    name: fac.facilityName,
                    facilityId: String(fac.facilityId || ""),
                    x: fac.coord.x,
                    y: fac.coord.y,
                    type,
                  };
                  stationByCoord.set(key, station);
                  list.push(station);
                } else if (type === "subway") {
                  const station = stationByCoord.get(key);
                  if (station) station.type = "subway";
                }
              }
            });
          });
        });
        return list;
      });

      // 选站索引提前到空闲期预建：保证「点第一个站之前」索引已就绪（用户要求：加载模型时缓存）
      runWhenIdle(() => {
        if (props.model === model) {
          getModelDerived(model, "zdzl:facilityIndex", () => buildFacilityIndex(data));
        }
      });

      allMapStations.value = stationsList;
      applyStationsToLayers(reachabilityVisibleStations.value, true);
      if (!runMonitorSimplifiedRight && BaseMapLineModeRef.value === "bus-network") {
        _StationLayer.hide();
      }
      scheduleOverlayRefresh();
  } catch (error) {
    if (props.model === model && !isCanceledRequest(error)) rawLines.value = [];
  } finally {
    if (props.model === model) {
      loading.value = false;
    }
  }
}

const activeTransitType = ref("bus");

const currentLeaderboard = computed(() => {
  return stationPanelData.value?.summary?.leaderboard?.[activeTransitType.value] || [];
});

// CSV 导出（带 BOM，Excel 直接打开中文不乱码）；项目无 xlsx 依赖，避免引新包
function downloadCsv(filename, rows) {
  const escapeCell = (cell) => {
    const text = String(cell ?? "");
    return /[",\n\r]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
  };
  const content = "\uFEFF" + rows.map((row) => row.map(escapeCell).join(",")).join("\r\n");
  const blob = new Blob([content], { type: "text/csv;charset=utf-8;" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}

function handleExportLeaderboard() {
  const rows = currentLeaderboard.value;
  if (!rows.length) {
    proxy?.$message?.warning("暂无可导出的排行数据");
    return;
  }
  const csvRows = [["排名", "站点名称", "说明", "日均客流量(人次)"]];
  rows.forEach((item, index) => {
    csvRows.push([index + 1, item.stationName || item.name || "", item.desc || "", toFiniteNumber(item.passengerFlow, 0)]);
  });
  downloadCsv(`站点客流排行_${activeTransitType.value === "bus" ? "公交" : "地铁"}.csv`, csvRows);
  proxy?.$message?.success("客流排行榜已导出 CSV 文件");
}

const activeDetailTab = ref("overview");
const segmentTimeRange = ref([8, 18]);
// 拖动时段滑块每档触发「OD 全链路重算 + notMerge 重绘 + 地图曲线层重建」级联，
// 重计算统一消费 180ms 防抖镜像；v-model 与时段文案读原值保证即时反馈
const { debounced: debouncedSegmentTimeRange, cancel: cancelSegmentTimeMirror } = createDebouncedMirror(segmentTimeRange, 180);
const odViewMode = ref("table");
// 需求8：OD曲线分级色阶配置（色阶控件与图例已移到地图左下角 index.vue），此处复用注入的共享配置；
// 无注入（独立使用）时回退本地 ref。曲线仍在本组件按该配置着色/定宽。
const injectedOdCurveScaleConfig = inject("odCurveScaleConfig", null);
const localOdCurveScaleConfig = ref(createColorScaleConfig("YlOrRd", 5));
const odCurveScaleConfig = injectedOdCurveScaleConfig || localOdCurveScaleConfig;
// 把当前OD最大客流与客流分布回报给 index.vue，供左下角图例按分位数换算人次
const runMonitorOdCurveMaxFlow = inject("runMonitorOdCurveMaxFlow", null);
const runMonitorOdCurveValues = inject("runMonitorOdCurveValues", null);
// 需求9：乘降热力图弹窗
const boardingHeatmapVisible = ref(false);
// 需求10：可达性分组显隐（整值替换更新）
const reachabilityLevelVisibility = ref({ direct: true, transfer1: true, transfer2: true });

function formatHourLabel(hour) {
  return `${hour.toString().padStart(2, "0")}:00`;
}

function formatHourRangeLabel(hour) {
  return `${formatHourLabel(hour)}-${formatHourLabel(hour + 1)}`;
}

function handleExportDetail() {
  const activeSection = shouldRenderPfaRightPanel.value ? pfaStationSection.value : activeDetailTab.value;
  const stationName = selectedStationName.value || "站点";
  const startHour = segmentTimeRange.value[0];
  const endHour = segmentTimeRange.value[1];
  let sectionLabel;
  const rows = [];
  if (activeSection === "overview") {
    sectionLabel = "站点数据分析";
    rows.push(["指标", "数值"]);
    const metrics = stationMetrics.value;
    rows.push(["站点日均客流", metrics.passenger]);
    rows.push(["高峰小时客流", metrics.peakFlow]);
    rows.push(["服务乘客数", metrics.population]);
    rows.push(["换乘便利度", metrics.transferScore]);
    rows.push([]);
    rows.push(["时段", "客流量(人次)"]);
    hourSlice(currentStationPanel.value?.hourlyFlow, 6, 23).forEach((value, index) => {
      rows.push([formatHourRangeLabel(6 + index), value]);
    });
  } else if (activeSection === "boardingAlighting" || activeSection === "boarding") {
    sectionLabel = "站点乘降分析";
    rows.push(["时段", "上车人数", "下车人数"]);
    const boardingByHour = currentStationPanel.value?.boardingByHour || [];
    const alightingByHour = currentStationPanel.value?.alightingByHour || [];
    for (let hour = startHour; hour < endHour; hour++) {
      rows.push([formatHourRangeLabel(hour), toFiniteNumber(boardingByHour[hour], 0), toFiniteNumber(alightingByHour[hour], 0)]);
    }
  } else if (activeSection === "od") {
    sectionLabel = "站点OD分析";
    rows.push(["线路", "方向", "起点", "终点", "客流量(人次)"]);
    odTableData.value.forEach((item) => {
      rows.push([item.lineName || "", item.routeDesc || item.routeName || "", item.origin || "", item.destination || "", item.flow]);
    });
  } else if (activeSection === "demographics") {
    sectionLabel = "客流画像";
    rows.push(["分组", "类别", "占比(%)"]);
    stationDemographicsGroups.value.forEach((group) => {
      (group.items || []).forEach((item) => rows.push([group.title, item.label, item.value.toFixed(1)]));
    });
  } else {
    sectionLabel = "站点可达分析";
    rows.push(["可达等级", "站点数", "站点明细"]);
    reachabilityGroups.value.forEach((group) => {
      rows.push([group.label, group.count, group.stations.map((station) => reachabilityStationLabel(station)).join(" / ")]);
    });
  }
  // 只有表头说明当前区块无数据
  if (rows.length < 2) {
    proxy?.$message?.warning(`当前${sectionLabel}暂无可导出数据`);
    return;
  }
  downloadCsv(`${stationName}_${sectionLabel}.csv`, rows);
  proxy?.$message?.success(`${sectionLabel}已导出 CSV 文件`);
}

// 站点乘降分析
const boardingAlightingChartOption = computed(() => {
  const startHour = debouncedSegmentTimeRange.value[0];
  const endHour = debouncedSegmentTimeRange.value[1];

  const hours = [];
  const boardingData = [];
  const alightingData = [];
  const boardingByHour = currentStationPanel.value?.boardingByHour || [];
  const alightingByHour = currentStationPanel.value?.alightingByHour || [];

  // 与 hourSlice 同口径：左闭右开 [startHour, endHour)
  for (let hour = startHour; hour < endHour; hour++) {
    hours.push(formatHourRangeLabel(hour));
    boardingData.push(toFiniteNumber(boardingByHour[hour], 0));
    alightingData.push(-toFiniteNumber(alightingByHour[hour], 0));
  }

  return {
    backgroundColor: "transparent",
    tooltip: {
      trigger: "axis",
      axisPointer: {
        type: "shadow"
      },
      backgroundColor: "rgba(30, 41, 59, 0.9)",
      borderColor: "rgba(255, 255, 255, 0.15)",
      textStyle: {
        color: "#ffffff",
        fontSize: 12
      },
      formatter: (params) => {
        const hourLabel = params[0].name;
        let html = `<div style="font-weight: bold; margin-bottom: 4px;">${selectedStationName.value} (${hourLabel})</div>`;
        params.forEach(p => {
          const val = Math.abs(p.value);
          html += `<div style="display: flex; align-items: center; justify-content: space-between; gap: 16px;">
            <div style="display: flex; align-items: center;">
              <span style="display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: ${p.color}; margin-right: 6px;"></span>
              <span>${p.seriesName}:</span>
            </div>
            <span style="font-weight: bold;">${val.toLocaleString()} 人次</span>
          </div>`;
        });
        return html;
      }
    },
    legend: {
      data: ["上车人数", "下车人数"],
      textStyle: {
        color: "#64748b",
        fontSize: 11
      },
      top: 0,
      icon: "rect"
    },
    grid: {
      left: "3%",
      right: "4%",
      bottom: "18%",
      top: "15%",
      containLabel: true
    },
    xAxis: {
      type: "category",
      data: hours,
      axisLine: {
        lineStyle: {
          color: "rgba(21, 105, 222, 0.15)"
        }
      },
      axisLabel: {
        color: "#64748b",
        fontSize: 10,
        interval: 0,
        rotate: 28
      }
    },
    yAxis: {
      type: "value",
      axisLine: {
        show: false
      },
      axisTick: {
        show: false
      },
      splitLine: {
        lineStyle: {
          color: "rgba(21, 105, 222, 0.06)",
          type: "dashed"
        }
      },
      axisLabel: {
        color: "#64748b",
        fontSize: 10,
        formatter: (value) => {
          return Math.abs(value);
        }
      }
    },
    series: [
      {
        name: "上车人数",
        type: "bar",
        stack: "Total",
        barWidth: "40%",
        itemStyle: {
          color: {
            type: "linear",
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: "#0f9f6e" },
              { offset: 1, color: "#087a55" }
            ]
          },
          borderRadius: [4, 4, 0, 0]
        },
        data: boardingData
      },
      {
        name: "下车人数",
        type: "bar",
        stack: "Total",
        barWidth: "40%",
        itemStyle: {
          color: {
            type: "linear",
            x: 0,
            y: 0,
            x2: 0,
            y2: 1,
            colorStops: [
              { offset: 0, color: "#f43f5e" },
              { offset: 1, color: "#e11d48" }
            ]
          },
          borderRadius: [0, 0, 4, 4]
        },
        data: alightingData
      }
    ]
  };
});

// 站点OD分析
const odTableData = computed(() => {
  const startHour = debouncedSegmentTimeRange.value[0];
  const endHour = debouncedSegmentTimeRange.value[1];

  const rows = (currentStationPanel.value?.od || []).map((item) => {
    const flow = hourSlice(item.flowByHour, startHour, endHour).reduce((sum, value) => sum + value, 0);
    const routeDesc = item.routeDesc || item.direction || "";
    const routeLabel = [item.lineName, routeDesc || item.routeName].filter(Boolean).join(" · ");
    return {
      origin: item.origin,
      destination: item.destination,
      routeId: item.routeId,
      lineName: item.lineName,
      routeName: item.routeName,
      routeDesc,
      routeLabel,
      label: `${routeLabel ? `${routeLabel} · ` : ""}${item.origin} - ${item.destination}`,
      chartLabel: `${item.counterpart || item.destination || item.origin}`,
      counterpart: item.counterpart,
      flow,
      ratio: 0
    };
  }).filter((item) => item.flow > 0)
    .sort((a, b) => b.flow - a.flow);
  const total = rows.reduce((sum, item) => sum + item.flow, 0);
  return rows.map((item) => ({
    ...item,
    ratio: total > 0 ? (item.flow * 100) / total : 0
  }));
});

// 图表：按对端站点聚合（同名站累加、只出现一次；表格仍保留按线路方向的明细）
const odStationChart = computed(() => {
  const map = new Map();
  for (const r of odTableData.value) {
    const label = String(r.chartLabel || r.counterpart || r.destination || r.origin || "未知").trim();
    const key = normalizeStationSearchName(label) || label;
    const existing = map.get(key);
    if (existing) {
      existing.flow += r.flow;
      existing.routeCount += 1;
    } else {
      map.set(key, { chartLabel: label, label, flow: r.flow, routeLabel: r.routeLabel, routeCount: 1 });
    }
  }
  const rows = Array.from(map.values()).sort((a, b) => b.flow - a.flow);
  rows.forEach((r) => { if (r.routeCount > 1) r.routeLabel = `${r.routeCount} 条线路`; });
  return rows;
});

// 图表高度随对端站点数量增长（每站约 26px），面板内滚动，保证站点全部可见、不省略
// 高度封顶：枢纽站对端可达数百个，无上限时 dpr=2 下会生成数十 MB 的超高位图，低端机可能直接崩
const OD_CHART_MAX_HEIGHT = 520;
const OD_CHART_VISIBLE_ROWS = Math.floor((OD_CHART_MAX_HEIGHT - 40) / 26);
const odChartHeight = computed(() => Math.max(260, Math.min(OD_CHART_MAX_HEIGHT, odStationChart.value.length * 26 + 40)));
const odChartNeedsZoom = computed(() => odStationChart.value.length > OD_CHART_VISIBLE_ROWS);

const odChartOption = computed(() => {
  const chartRows = odStationChart.value.slice().reverse();
  const labels = chartRows.map(d => d.chartLabel);
  const flows = chartRows.map(d => d.flow);
  // 超出可视行数时用 dataZoom 分页浏览（初始窗口停在客流最大的顶部行），画布高度不再随对端站数无限增长
  const zoomStartValue = Math.max(0, chartRows.length - OD_CHART_VISIBLE_ROWS);

  return {
    backgroundColor: "transparent",
    dataZoom: odChartNeedsZoom.value
      ? [
          {
            type: "slider",
            yAxisIndex: 0,
            width: 12,
            right: 2,
            startValue: zoomStartValue,
            endValue: chartRows.length - 1,
            zoomLock: false,
            brushSelect: false,
            showDetail: false,
            borderColor: "rgba(21, 105, 222, 0.15)",
            fillerColor: "rgba(21, 105, 222, 0.12)",
            handleSize: 14,
          },
          { type: "inside", yAxisIndex: 0, zoomOnMouseWheel: false, moveOnMouseWheel: true },
        ]
      : undefined,
    tooltip: {
      trigger: "axis",
      axisPointer: {
        type: "shadow"
      },
      backgroundColor: "rgba(30, 41, 59, 0.9)",
      borderColor: "rgba(255, 255, 255, 0.15)",
      textStyle: {
        color: "#ffffff",
        fontSize: 12
      },
      formatter: (params) => {
        const item = params[0];
        const row = chartRows[item.dataIndex] || {};
        return `<div style="font-weight: bold; margin-bottom: 4px;">OD出行客流</div>
          <div style="margin-bottom: 4px; color: rgba(255,255,255,0.76);">${row.routeLabel || "未知线路"}</div>
          <div style="display: flex; align-items: center; justify-content: space-between; gap: 16px;">
            <div style="display: flex; align-items: center;">
              <span style="display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: ${item.color}; margin-right: 6px;"></span>
              <span>${row.label || item.name}:</span>
            </div>
            <span style="font-weight: bold;">${item.value.toLocaleString()} 人次</span>
          </div>`;
      }
    },
    grid: {
      left: "3%",
      right: "6%",
      bottom: "8%",
      top: "5%",
      containLabel: true
    },
    xAxis: {
      type: "value",
      axisLine: {
        show: false
      },
      axisTick: {
        show: false
      },
      splitLine: {
        lineStyle: {
          color: "rgba(21, 105, 222, 0.06)",
          type: "dashed"
        }
      },
      axisLabel: {
        color: "#64748b",
        fontSize: 10
      }
    },
    yAxis: {
      type: "category",
      data: labels,
      axisLine: {
        lineStyle: {
          color: "rgba(21, 105, 222, 0.15)"
        }
      },
      axisLabel: {
        color: "#64748b",
        fontSize: 11,
        width: 160,
        overflow: "truncate",
        interval: 0
      }
    },
    series: [
      {
        name: "出行量",
        type: "bar",
        barWidth: "60%",
        itemStyle: {
          color: {
            type: "linear",
            x: 0,
            y: 0,
            x2: 1,
            y2: 0,
            colorStops: [
              { offset: 0, color: "#2f75d6" },
              { offset: 1, color: "#58b8d4" }
            ]
          },
          borderRadius: [0, 4, 4, 0]
        },
        data: flows
      }
    ]
  };
});

// —— 需求8：客流OD地图曲线数据 ——
// 与右侧图表同口径：同一对端站多条线路、到站/出站记录合并为一条曲线（flow 求和）。
// 坐标缺失时按站名回退到站点坐标索引，只有实在无坐标的条目才跳过。
const odCurveEntries = computed(() => {
  // 仅 OD 区块激活时计算：作为多个 watch 的源，非激活态被强制求值等于每次时段变化白跑全表聚合
  if (pfaStationSection.value !== "od") return { direction: "all", items: [] };
  const selfName = normalizeStationSearchName(selectedStationName.value);
  if (!selfName) return { direction: "all", items: [] };
  const startHour = debouncedSegmentTimeRange.value[0];
  const endHour = debouncedSegmentTimeRange.value[1];
  const odRows = Array.isArray(currentStationPanel.value?.od) ? currentStationPanel.value.od : [];
  const merged = new Map();

  odRows.forEach((item) => {
    if (!item) return;
    const originName = String(item.origin || "").trim();
    const destinationName = String(item.destination || "").trim();
    const counterpartName = String(
      item.counterpart || (normalizeStationSearchName(originName) === selfName ? destinationName : originName)
    ).trim();
    if (!counterpartName) return;

    const flow = hourSlice(item.flowByHour, startHour, endHour).reduce((sum, value) => sum + value, 0);
    if (flow <= 0) return;

    const counterpartKey = normalizeStationSearchName(counterpartName);
    const originKey = normalizeStationSearchName(originName);
    const destinationKey = normalizeStationSearchName(destinationName);
    const counterpartSide = counterpartKey === destinationKey
      ? "destination"
      : counterpartKey === originKey
        ? "origin"
        : originKey === selfName
          ? "destination"
          : "origin";
    const direction = counterpartSide === "destination" ? "outbound" : "inbound";
    let lng = toFiniteCoord(counterpartSide === "origin" ? item.originX : item.destinationX);
    let lat = toFiniteCoord(counterpartSide === "origin" ? item.originY : item.destinationY);
    if (!Number.isFinite(lng) || !Number.isFinite(lat)) {
      const coord = stationCoordByName(counterpartName);
      const ll = coord && Number.isFinite(coord.x) && Number.isFinite(coord.y) ? webMercatorToLngLat(coord.x, coord.y) : null;
      if (ll && ll.every(Number.isFinite)) {
        [lng, lat] = ll;
      } else {
        return;
      }
    }

    const key = counterpartKey || counterpartName;
    const existing = merged.get(key);
    if (existing) {
      existing.flow += flow;
      existing.inboundFlow += direction === "inbound" ? flow : 0;
      existing.outboundFlow += direction === "outbound" ? flow : 0;
      existing.direction = existing.inboundFlow > 0 && existing.outboundFlow > 0 ? "both" : direction;
    } else {
      merged.set(key, {
        name: counterpartName,
        lng,
        lat,
        flow,
        inboundFlow: direction === "inbound" ? flow : 0,
        outboundFlow: direction === "outbound" ? flow : 0,
        direction,
      });
    }
  });

  const items = Array.from(merged.values()).sort((a, b) => b.flow - a.flow);
  const hasInbound = items.some((item) => item.inboundFlow > 0);
  const hasOutbound = items.some((item) => item.outboundFlow > 0);
  const direction = hasInbound && hasOutbound ? "both" : hasOutbound ? "outbound" : "inbound";
  return { direction, items };
});

const odCurveMaxFlow = computed(() =>
  odCurveEntries.value.items.reduce((max, item) => Math.max(max, item.flow), 0)
);

// 把当前OD最大客流与客流分布回报给 index.vue（左下角图例据此按分位数换算人次）；
// 非OD子功能或无数据时归零/清空，让图例自动隐藏。
watch(
  [odCurveEntries, pfaStationSection, shouldRenderPfaRightPanel],
  () => {
    const active = shouldRenderPfaRightPanel.value && pfaStationSection.value === "od";
    const items = odCurveEntries.value.items;
    if (runMonitorOdCurveMaxFlow) runMonitorOdCurveMaxFlow.value = active ? odCurveMaxFlow.value : 0;
    if (runMonitorOdCurveValues) runMonitorOdCurveValues.value = active ? items.map((item) => item.flow) : [];
  },
  { immediate: true },
);

// —— 需求9：站点乘降热力图（线路 × OD对端站） ——
const boardingHeatmapData = computed(() => {
  const startHour = debouncedSegmentTimeRange.value[0];
  const endHour = debouncedSegmentTimeRange.value[1];
  const selfName = normalizeStationSearchName(selectedStationName.value);
  const odRows = Array.isArray(currentStationPanel.value?.od) ? currentStationPanel.value.od : [];

  const lineTotals = new Map();
  const counterpartTotals = new Map();
  // 嵌套 Map 聚合，避免站名/线路名含分隔符的拼接歧义
  const cellFlows = new Map();
  odRows.forEach((item) => {
    if (!item) return;
    const lineName = String(item.lineName || "").trim() || "未知线路";
    const counterpart = String(
      item.counterpart
      || (normalizeStationSearchName(item.destination) === selfName ? item.origin : item.destination)
      || ""
    ).trim();
    if (!counterpart) return;
    const flow = hourSlice(item.flowByHour, startHour, endHour).reduce((sum, value) => sum + value, 0);
    if (flow <= 0) return;
    lineTotals.set(lineName, (lineTotals.get(lineName) || 0) + flow);
    counterpartTotals.set(counterpart, (counterpartTotals.get(counterpart) || 0) + flow);
    if (!cellFlows.has(lineName)) cellFlows.set(lineName, new Map());
    const row = cellFlows.get(lineName);
    row.set(counterpart, (row.get(counterpart) || 0) + flow);
  });

  const lines = Array.from(lineTotals.entries()).sort((a, b) => b[1] - a[1]).map(([name]) => name);
  // OD 对端站按客流取前 20
  const counterparts = Array.from(counterpartTotals.entries())
    .sort((a, b) => b[1] - a[1])
    .slice(0, 20)
    .map(([name]) => name);
  const counterpartIndex = new Map(counterparts.map((name, index) => [name, index]));

  const cells = [];
  let maxCellFlow = 0;
  lines.forEach((lineName, xIndex) => {
    const row = cellFlows.get(lineName);
    if (!row) return;
    row.forEach((flow, counterpart) => {
      const yIndex = counterpartIndex.get(counterpart);
      if (yIndex === undefined) return;
      cells.push([xIndex, yIndex, flow]);
      maxCellFlow = Math.max(maxCellFlow, flow);
    });
  });
  return { lines, counterparts, cells, maxCellFlow, hasData: cells.length > 0 };
});

const boardingHeatmapOption = computed(() => {
  const { lines, counterparts, cells, maxCellFlow } = boardingHeatmapData.value;
  return {
    backgroundColor: "transparent",
    tooltip: {
      position: "top",
      backgroundColor: "rgba(30, 41, 59, 0.9)",
      borderColor: "rgba(255, 255, 255, 0.15)",
      textStyle: {
        color: "#ffffff",
        fontSize: 12
      },
      formatter: (params) => {
        const [xIndex, yIndex, flow] = params?.value || [];
        return `<div style="font-weight: bold; margin-bottom: 4px;">${lines[xIndex] || "未知线路"} × ${counterparts[yIndex] || "未知站点"}</div>
          <div style="text-align: right;">${toFiniteNumber(flow, 0).toLocaleString()} 人次</div>`;
      }
    },
    grid: {
      left: "3%",
      right: "6%",
      top: "4%",
      bottom: "20%",
      containLabel: true
    },
    xAxis: {
      type: "category",
      data: lines,
      splitArea: { show: true },
      axisLabel: {
        color: "#64748b",
        fontSize: 11,
        interval: 0,
        rotate: lines.length > 8 ? 32 : 0
      }
    },
    yAxis: {
      type: "category",
      data: counterparts,
      splitArea: { show: true },
      axisLabel: {
        color: "#64748b",
        fontSize: 11,
        width: 150,
        overflow: "truncate"
      }
    },
    visualMap: {
      type: "continuous",
      min: 0,
      max: Math.max(1, maxCellFlow),
      calculable: true,
      orient: "horizontal",
      left: "center",
      bottom: 4,
      itemWidth: 12,
      itemHeight: 160,
      // 白→黄→橙→深红（OrRd），对齐用户指定的经典热力图配色
      inRange: {
        color: ["#fff7ec", "#fee8c8", "#fdd49e", "#fdbb84", "#fc8d59", "#ef6548", "#c7302b"]
      },
      textStyle: {
        color: "#64748b",
        fontSize: 11
      }
    },
    series: [
      {
        name: "线路×OD客流",
        type: "heatmap",
        // 高值格子（≥55%最大值）文字用白色，低值用深棕，0 不显示数字
        data: cells.map((cell) => {
          const flow = toFiniteNumber(cell?.[2], 0);
          if (flow >= Math.max(1, maxCellFlow) * 0.55) {
            return { value: cell, label: { color: "#ffffff" } };
          }
          return cell;
        }),
        label: {
          show: cells.length <= 120,
          color: "#7a4a2b",
          fontSize: 10,
          formatter: (params) => {
            const flow = toFiniteNumber(params?.value?.[2], 0);
            return flow > 0 ? flow.toLocaleString() : "";
          }
        },
        itemStyle: {
          borderColor: "#ffffff",
          borderWidth: 2,
          borderRadius: 2
        },
        emphasis: {
          itemStyle: {
            shadowBlur: 8,
            shadowColor: "rgba(15, 23, 42, 0.35)"
          }
        }
      }
    ]
  };
});

// 可达性分析
const reachabilityData = computed(() => {
  const reachability = currentStationPanel.value?.reachability || {};
  const local = localReachabilityData.value;
  const localReady = local.ready;
  // 兜底至少 1：后端给 0 时不应清空明细列表
  const limitStations = (stations) => stations.slice(0, Math.max(1, toFiniteNumber(reachability.stationListLimit, 80)));
  return {
    direct: localReady ? local.directStations.length : toFiniteNumber(reachability.direct, 0),
    transfer1: localReady ? local.transfer1Stations.length : toFiniteNumber(reachability.transfer1, 0),
    transfer2: localReady ? local.transfer2Stations.length : toFiniteNumber(reachability.transfer2, 0),
    directStations: localReady ? limitStations(local.directStations) : (Array.isArray(reachability.directStations) ? reachability.directStations : []),
    transfer1Stations: localReady ? limitStations(local.transfer1Stations) : (Array.isArray(reachability.transfer1Stations) ? reachability.transfer1Stations : []),
    transfer2Stations: localReady ? limitStations(local.transfer2Stations) : (Array.isArray(reachability.transfer2Stations) ? reachability.transfer2Stations : []),
    directMapStations: localReady ? local.directStations : null,
    transfer1MapStations: localReady ? local.transfer1Stations : null,
    transfer2MapStations: localReady ? local.transfer2Stations : null,
  };
});

const reachabilityGroups = computed(() => {
  const data = reachabilityData.value;
  return [
    {
      key: "direct",
      label: "可直达站点",
      count: data.direct,
      stations: data.directStations,
      mapStations: data.directMapStations || data.directStations,
      color: "#1a8a3f",
    },
    {
      key: "transfer1",
      label: "一次换乘可达",
      count: data.transfer1,
      stations: data.transfer1Stations,
      mapStations: data.transfer1MapStations || data.transfer1Stations,
      color: "#0071e3",
    },
    {
      key: "transfer2",
      label: "二次换乘可达",
      count: data.transfer2,
      stations: data.transfer2Stations,
      mapStations: data.transfer2MapStations || data.transfer2Stations,
      color: "#b06a00",
    },
  ];
});

const reachabilityChartOption = computed(() => {
  const data = reachabilityData.value;

  return {
    backgroundColor: "transparent",
    tooltip: {
      trigger: "item",
      backgroundColor: "rgba(30, 41, 59, 0.9)",
      borderColor: "rgba(255, 255, 255, 0.15)",
      textStyle: {
        color: "#ffffff",
        fontSize: 12
      },
      formatter: "{b}: <strong>{c} 个站点</strong> ({d}%)"
    },
    legend: {
      orient: "horizontal",
      bottom: 0,
      left: "center",
      textStyle: {
        color: "#64748b",
        fontSize: 11
      },
      icon: "circle"
    },
    series: [
      {
        name: "可达站点分布",
        type: "pie",
        radius: ["35%", "65%"],
        center: ["50%", "45%"],
        avoidLabelOverlap: false,
        itemStyle: {
          borderRadius: 6,
          borderColor: "#ffffff",
          borderWidth: 2
        },
        label: {
          show: false
        },
        labelLine: {
          show: false
        },
        data: [
          { value: data.direct, name: "直达站点", itemStyle: { color: "#1a8a3f" } },
          { value: data.transfer1, name: "一次换乘可达", itemStyle: { color: "#0071e3" } },
          { value: data.transfer2, name: "二次换乘可达", itemStyle: { color: "#b06a00" } }
        ]
      }
    ]
  };
});

// 可达 overlay/OD曲线/选中圈/站点过滤共用一个 watch：
// 源用独立 getter 数组（逐项 Object.is 比较），值未变不触发；刷新走合并调度，一次交互每类至多重建一次
watch(
  [
    shouldRenderPfaRightPanel,
    pfaStationSection,
    selectedStationName,
    selectedStationCoord,
    currentStationPanel,
    displayRangeStations,
    stationCoordIndex,
    () => debouncedSegmentTimeRange.value[0],
    () => debouncedSegmentTimeRange.value[1],
    odCurveScaleConfig,
  ],
  () => {
    scheduleOverlayRefresh();
  },
  { immediate: false }
);

// 需求10：分组显隐开关变化时应用到地图（曲线层 + 阴影层过滤）
watch(reachabilityLevelVisibility, () => {
  nextTick(() => applyReachabilityLevelVisibility());
});

onMounted(() => {
  if (props.model) {
    loadAllData();
  }
  if (activeDatavisualizationTab.value === "站点客流监测") {
    rightPanelHasContent.value = true;
  }
});

watch(() => props.model, (newModel) => {
  if (newModel) {
    clearStationPanelRetry();
    stationPanelRetryCount = 0;
    stationPanelData.value = null;
    stationPanelStatus.value = "idle";
    stationPanelError.value = "";
    allMapStations.value = [];
    selectedStationName.value = "";
    selectedStationFacilityId.value = "";
    selectedStationCoord.value = null;
    selectedReverseStationName.value = "";
    selectedReverseStationFacilityId.value = "";
    selectedReverseStationCoord.value = null;
    matchedRoutes.value = [];
    cleanUpSelectedStationRing();
    cleanUpReachabilityOverlay();
    cleanUpOdCurveOverlay();
    loadAllData();
  }
});

onUnmounted(() => {
  stationPanelDisposed = true;
  cancelSegmentTimeMirror();
  clearStationPanelRetry();
  _StationLayer.dispose();
  cleanUpSelectedStationRing();
  cleanUpReachabilityOverlay();
  cleanUpOdCurveOverlay();
  if (runMonitorOdCurveMaxFlow) runMonitorOdCurveMaxFlow.value = 0;
  if (runMonitorOdCurveValues) runMonitorOdCurveValues.value = [];
  // 仅当当前 tab 仍属于本组件时才清面板状态：tab 切换时卸载晚于新组件挂载，
  // 无守卫会把新 tab 刚置 true 的状态清掉导致右侧面板闪空
  if (activeDatavisualizationTab.value === "站点客流监测") {
    rightPanelHasContent.value = false;
  }
});

// 取消选中：清空选中站点与地图高亮圈（供 index.vue 点击空白处调用）
function clearSelection() {
  // 关闭热力图弹窗，避免残留已取消选中站点的弹窗
  boardingHeatmapVisible.value = false;
  selectedStationName.value = "";
  selectedStationFacilityId.value = "";
  selectedStationCoord.value = null;
  selectedReverseStationName.value = "";
  selectedReverseStationFacilityId.value = "";
  selectedReverseStationCoord.value = null;
  matchedRoutes.value = [];
  cleanUpSelectedStationRing();
  cleanUpReachabilityOverlay();
  cleanUpOdCurveOverlay();
}

defineExpose({
  selectStationByName,
  selectStationByFeature,
  clearSelection,
});
</script>

<style lang="scss" scoped>
.ZDZL {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  width: 100%;
}

.search-card {
  border: 1px solid rgba(21, 105, 222, 0.15) !important;
  box-shadow: none !important;
  border-radius: 8px !important;
  background-color: var(--app-card-bg);
  overflow: hidden;

  :deep(.MCard_title_box) {
    background-color: rgba(21, 105, 222, 0.05) !important;
    border-bottom: 1px solid rgba(21, 105, 222, 0.1) !important;
  }
}

.search-input-wrapper {
  margin-bottom: var(--space-sm);

  .custom-select {
    width: 100%;
    :deep(.el-input__wrapper) {
      box-shadow: 0 0 0 1px rgba(21, 105, 222, 0.15) inset !important;
      border-radius: var(--app-card-radius);
      padding: 6px 12px;

      &:hover {
        box-shadow: 0 0 0 1px rgba(21, 105, 222, 0.4) inset !important;
      }

      &.is-focus {
        box-shadow: 0 0 0 1.5px rgba(21, 105, 222, 1) inset !important;
      }
    }
  }

  .search-icon {
    color: rgba(21, 105, 222, 0.6);
    margin-right: 4px;
  }
}

.timeline-container {
  display: flex;
  flex-direction: column;
  padding-left: 8px;

  .timeline-item {
    display: flex;
    align-items: flex-start;
    gap: 12px;
    padding-bottom: 14px;
    position: relative;
    cursor: pointer;

    &:hover {
      .timeline-content .station-name {
        color: #1569de;
      }
      .timeline-dot {
        border-color: #1569de;
        background: #1569de;
      }
    }

    &:not(:last-child)::after {
      content: "";
      position: absolute;
      left: 6px;
      top: 12px;
      bottom: -4px;
      width: 2px;
      background-color: rgba(21, 105, 222, 0.15);
    }

    .timeline-dot {
      width: 14px;
      height: 14px;
      border: 2px solid rgba(21, 105, 222, 0.4);
      border-radius: 50%;
      background: #ffffff;
      display: flex;
      align-items: center;
      justify-content: center;
      margin-top: 2px;
      z-index: 1;
      transition: all 0.2s ease;

      &.first {
        border-color: #0f9f6e;
        .dot-inner { background: #0f9f6e; }
      }
      &.last {
        border-color: #dc4c5d;
        .dot-inner { background: #dc4c5d; }
      }

      .dot-inner {
        width: 6px;
        height: 6px;
        border-radius: 50%;
        background: transparent;
      }
    }

    .timeline-content {
      display: flex;
      flex-direction: column;
      gap: 2px;

      .station-name {
        font-size: 13px;
        font-weight: 600;
        color: #2d3748;
        transition: color 0.2s ease;
      }

      .station-idx {
        font-size: 10px;
        color: #a0aec0;
      }
    }
  }
}

.matched-title {
  font-size: 13px;
  font-weight: bold;
  color: #12304f;
  margin-bottom: 10px;
  padding-bottom: 4px;
  border-bottom: 1px solid rgba(21, 105, 222, 0.08);
}

.scroll-container {
  max-height: 350px;
  overflow-y: auto;
  padding-right: 4px;
}

.scroll-container::-webkit-scrollbar {
  width: 6px;
}
.scroll-container::-webkit-scrollbar-thumb {
  background: rgba(21, 105, 222, 0.2);
  border-radius: 3px;
}
.scroll-container::-webkit-scrollbar-thumb:hover {
  background: rgba(21, 105, 222, 0.4);
}

.matched-list {
  display: flex;
  flex-direction: column;
  gap: 8px;

  .matched-item {
    background: rgba(248, 250, 252, 0.8);
    border: 1px solid rgba(226, 232, 240, 0.8);
    border-radius: 6px;
    padding: 10px;
    cursor: default;
    transition: all 0.2s ease;

    &:hover {
      border-color: rgba(21, 105, 222, 0.3);
      background: rgba(21, 105, 222, 0.02);
    }

    &.active {
      border-color: #1569de;
      background: rgba(21, 105, 222, 0.05);

      .item-header .line-badge {
        background: #1569de;
        color: #ffffff;
      }
    }

    .item-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 6px;

      .line-badge {
        background: rgba(21, 105, 222, 0.1);
        color: #1569de;
        font-size: 12px;
        font-weight: bold;
        padding: 2px 8px;
        border-radius: 4px;
        transition: all 0.2s ease;
      }

      .item-stops {
        font-size: 11px;
        color: #60758e;
      }
    }

    .item-body {
      display: flex;
      justify-content: space-between;
      font-size: 11px;
      color: #60758e;
    }
  }
}

.loading-container {
  padding: 20px 0;
}

.empty-matched {
  padding: 10px 0;
}

/* Right-side Data Overview Panel Premium Styling */
.SJZL_right_card {
  --theme-color: #1569de;
  width: 535px;
  background-color: var(--app-card-bg);
  border-radius: 8px;
  box-shadow: none;
}

.SJZL_right_card.pfa-station-card {
  width: 100%;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  overflow: visible;
}

.SJZL_right_card.pfa-station-card :deep(.MCard2_title_box) {
  min-height: 0;
  padding: 0 0 12px;
  background: transparent;
  border-bottom: 1px solid var(--dm2-line);
}

.SJZL_right_card.pfa-station-card :deep(.MCard2_title_box:hover) {
  background: transparent;
}

.SJZL_right_card.pfa-station-card :deep(.MCard2_open_btn) {
  color: var(--dm2-muted-soft);
}

.SJZL_right_card.pfa-station-card :deep(.MCard2_body_box) {
  padding: 0;
  border-top: 0;
}

.SJZL_right_card.pfa-station-card :deep(.ranking-title-container) {
  flex: 1;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--dm2-space-3);
  min-width: 0;
}

.pfa-station-heading {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.pfa-station-name {
  font-size: var(--dm2-text-xl);
  font-weight: var(--dm2-fw-bold);
  line-height: 1.2;
  color: var(--dm2-accent-strong);
  letter-spacing: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.pfa-station-sub {
  font-size: var(--dm2-text-xs);
  color: var(--dm2-muted);
  font-variant-numeric: tabular-nums;
}

.pfa-station-sections {
  display: flex;
  flex-direction: column;
  font-family: var(--dm2-font);
}

.pfa-station-sections .pfa-section {
  display: flex;
  flex-direction: column;
  gap: var(--dm2-space-3);
  padding: var(--dm2-space-5) 0;
  border-top: 1px solid var(--dm2-line-faint);
}

.pfa-station-sections .pfa-section:first-of-type {
  padding-top: var(--dm2-space-4);
  border-top: 0;
}

.pfa-status-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  padding: 18px;
  border: 1px solid rgba(21, 105, 222, 0.16);
  border-radius: 8px;
  background: rgba(248, 251, 255, 0.78);
  color: #334155;
}

.pfa-status-card.error {
  border-color: rgba(239, 68, 68, 0.28);
  background: rgba(254, 242, 242, 0.82);
}

.pfa-status-title {
  font-size: 14px;
  font-weight: 700;
}

.pfa-status-sub {
  font-size: 12px;
  line-height: 1.5;
  color: #64748b;
}

.pfa-station-sections .section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dm2-space-2);
}

.pfa-station-sections .section-title {
  display: flex;
  align-items: center;
  gap: var(--dm2-space-2);
  font-size: var(--dm2-text-md);
  font-weight: var(--dm2-fw-semibold);
  color: var(--dm2-ink);
  letter-spacing: 0;
}

.pfa-station-sections .section-title::before {
  content: "";
  width: 3px;
  height: 13px;
  border-radius: 999px;
  background: var(--dm2-accent);
}

.pfa-station-sections .time-range-section {
  display: flex;
  flex-direction: column;
  gap: var(--dm2-space-2);
  padding: var(--dm2-space-3) var(--dm2-space-4);
  margin: var(--dm2-space-4) 0 0;
  border-radius: 8px;
  background: var(--dm2-surface-sunken);
  border: 1px solid var(--dm2-line);
}

.pfa-station-sections .time-range-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.pfa-station-sections .time-range-header .title {
  font-size: var(--dm2-text-sm);
  font-weight: var(--dm2-fw-semibold);
  color: var(--dm2-ink-soft);
}

.pfa-station-sections .time-range-header .range-text {
  font-size: var(--dm2-text-sm);
  font-weight: var(--dm2-fw-bold);
  color: var(--dm2-accent);
  font-family: var(--dm2-font-num);
  font-variant-numeric: tabular-nums;
}

.pfa-station-sections .time-range-slider {
  width: calc(100% - 8px);
  margin: 0 auto;
}

.pfa-station-sections .time-range-slider :deep(.el-slider__runway) {
  background-color: var(--dm2-line);
}

.pfa-station-sections .time-range-slider :deep(.el-slider__bar) {
  background-color: var(--dm2-accent);
}

.pfa-station-sections .time-range-slider :deep(.el-slider__button) {
  width: 14px;
  height: 14px;
  border-color: var(--dm2-accent);
}

.pfa-station-sections .pfa-section-meta {
  font-size: var(--dm2-text-xs);
  color: var(--dm2-muted);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.pfa-station-sections .chart-container-wrapper {
  height: 220px;
  width: 100%;
}

.pfa-station-sections .chart_box {
  width: 100%;
  height: 100%;
}

.pfa-station-sections .chart-type-selector {
  display: flex;
  gap: 2px;
  padding: 2px;
  border-radius: 8px;
  background: var(--dm2-surface-sunken);
  border: 1px solid var(--dm2-line);
}

.pfa-station-sections .chart-type-selector .type-pill {
  padding: 3px 10px;
  border-radius: 6px;
  font-size: var(--dm2-text-xs);
  font-weight: var(--dm2-fw-semibold);
  color: var(--dm2-muted);
  cursor: pointer;
  user-select: none;
  transition: color var(--dm2-dur-fast) var(--dm2-ease),
    background-color var(--dm2-dur-fast) var(--dm2-ease);
}

.pfa-station-sections .chart-type-selector .type-pill:hover {
  color: var(--dm2-ink-soft);
}

.pfa-station-sections .chart-type-selector .type-pill.active {
  color: #f8fbff;
  background: var(--dm2-accent);
  box-shadow: var(--dm2-accent-glow);
}

.pfa-station-sections .demo-groups {
  display: flex;
  flex-direction: column;
  gap: var(--dm2-space-5);
}

.pfa-station-sections .demo-group {
  display: flex;
  flex-direction: column;
  gap: var(--dm2-space-3);
}

.pfa-station-sections .demo-group-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--dm2-space-2);
  padding-bottom: var(--dm2-space-2);
  border-bottom: 1px solid var(--dm2-line);
}

.pfa-station-sections .demo-group-title {
  font-size: var(--dm2-text-sm);
  font-weight: var(--dm2-fw-semibold);
  color: var(--dm2-ink);
  letter-spacing: 0.02em;
}

.pfa-station-sections .demo-group-sum {
  font-size: var(--dm2-text-xs);
  color: var(--dm2-ink-soft);
  font-family: var(--dm2-font-num);
  font-variant-numeric: tabular-nums;
}

.pfa-station-sections .demo-list {
  display: flex;
  flex-direction: column;
  gap: var(--dm2-space-3);
}

.pfa-station-sections .demo-row {
  display: grid;
  grid-template-columns: minmax(76px, 0.32fr) minmax(0, 1fr) 50px;
  align-items: center;
  gap: var(--dm2-space-3);
}

.pfa-station-sections .demo-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--dm2-text-sm);
  color: var(--dm2-ink-soft);
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.pfa-station-sections .demo-dot {
  width: 8px;
  height: 8px;
  border-radius: 3px;
  flex-shrink: 0;
}

.pfa-station-sections .demo-track {
  height: 7px;
  border-radius: 999px;
  background: var(--dm2-line);
  overflow: hidden;
}

.pfa-station-sections .demo-fill {
  display: block;
  height: 100%;
  border-radius: 999px;
  transition: width var(--dm2-dur-slow) var(--dm2-ease-out);
}

.pfa-station-sections .demo-pct {
  text-align: right;
  font-size: var(--dm2-text-sm);
  font-weight: var(--dm2-fw-semibold);
  color: var(--dm2-ink);
  font-family: var(--dm2-font-num);
  font-variant-numeric: tabular-nums;
}

.pfa-station-sections .pfa-empty {
  padding: var(--dm2-space-5);
  text-align: center;
  font-size: var(--dm2-text-sm);
  color: var(--dm2-muted-soft);
}

.pfa-station-sections .pfa-od-table .transfer-table {
  border: 1px solid var(--dm2-line);
  border-radius: 8px;
  overflow: hidden;
}

.pfa-station-sections .pfa-od-table .transfer-table-header,
.pfa-station-sections .pfa-od-table .transfer-table-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 82px;
  align-items: center;
  gap: var(--dm2-space-3);
  padding: var(--dm2-space-2) var(--dm2-space-3);
}

.pfa-station-sections .pfa-od-table .transfer-table-header {
  background: var(--dm2-surface-sunken);
  border-bottom: 1px solid var(--dm2-line);
  font-size: var(--dm2-text-xs);
  font-weight: var(--dm2-fw-semibold);
  color: var(--dm2-muted);
}

.pfa-station-sections .pfa-od-table .transfer-table-body {
  display: flex;
  flex-direction: column;
}

.pfa-station-sections .pfa-od-table .transfer-table-row {
  min-height: 42px;
  border-bottom: 1px solid var(--dm2-line-faint);
  font-size: var(--dm2-text-sm);
  transition: background-color var(--dm2-dur-fast) var(--dm2-ease);
}

.pfa-station-sections .pfa-od-table .transfer-table-row:hover {
  background: var(--dm2-accent-weak);
}

.pfa-station-sections .pfa-od-table .transfer-table-row:last-child {
  border-bottom: 0;
}

.pfa-station-sections .col-od-route {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.pfa-station-sections .col-od-route strong {
  font-size: var(--dm2-text-sm);
  color: var(--dm2-ink);
  line-height: 1.2;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.pfa-station-sections .col-od-route small {
  display: block;
  font-size: var(--dm2-text-xs);
  color: var(--dm2-muted-soft);
  line-height: 1.2;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.pfa-station-sections .col-od-flow {
  font-family: var(--dm2-font-num);
  font-variant-numeric: tabular-nums;
  color: var(--dm2-ink);
  white-space: nowrap;
}

.pfa-station-sections .reachability-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 1px;
  border: 1px solid var(--dm2-line);
  border-radius: 8px;
  background: var(--dm2-line);
  overflow: hidden;
}

.pfa-station-sections .reachability-grid .metric-card {
  display: flex;
  flex-direction: column;
  gap: var(--dm2-space-1);
  padding: var(--dm2-space-3);
  background: var(--dm2-surface);
}

.pfa-station-sections .reachability-grid .metric-card.direct .value {
  color: #1a8a3f;
}

.pfa-station-sections .reachability-grid .metric-card.transfer1 .value {
  color: #0071e3;
}

.pfa-station-sections .reachability-grid .metric-card.transfer2 .value {
  color: #b06a00;
}

.pfa-station-sections .reachability-grid .label {
  font-size: var(--dm2-text-xs);
  color: var(--dm2-muted);
  font-weight: var(--dm2-fw-semibold);
}

.pfa-station-sections .reachability-grid .value {
  font-size: var(--dm2-text-lg);
  font-weight: var(--dm2-fw-bold);
  font-family: var(--dm2-font-num);
  font-variant-numeric: tabular-nums;
}

.pfa-station-sections .reachability-grid small {
  font-size: var(--dm2-text-xs);
  font-weight: var(--dm2-fw-semibold);
  color: var(--dm2-muted);
}

.reachability-list {
  display: flex;
  flex-direction: column;
  gap: var(--dm2-space-3);
}

.reachability-group {
  display: flex;
  flex-direction: column;
  gap: var(--dm2-space-2);
  padding-top: var(--dm2-space-3);
  border-top: 1px solid var(--dm2-line-faint);
}

.reachability-list-head {
  display: flex;
  align-items: center;
  gap: var(--dm2-space-2);
  color: var(--dm2-ink);
  font-size: var(--dm2-text-sm);
  font-weight: var(--dm2-fw-semibold);
}

.reachability-list-head strong {
  margin-left: auto;
  font-family: var(--dm2-font-num);
  font-variant-numeric: tabular-nums;
  color: var(--dm2-muted);
}

.reachability-dot {
  width: 8px;
  height: 8px;
  border-radius: 3px;
  flex-shrink: 0;
}

.reachability-chip-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 110px;
  overflow-y: auto;
  padding-right: 2px;
}

.reachability-chip {
  max-width: 100%;
  padding: 3px 7px;
  border: 1px solid currentColor;
  border-radius: 999px;
  background: rgba(248, 251, 255, 0.62);
  font-size: var(--dm2-text-xs);
  font-weight: var(--dm2-fw-semibold);
  line-height: 1.2;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.reachability-empty {
  color: var(--dm2-muted-soft);
  font-size: var(--dm2-text-sm);
}

.route-detail-panel {
  display: flex;
  flex-direction: column;
  padding: 8px 4px;
}

.metrics-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  margin-bottom: 20px;

  .metric-card {
    background: var(--app-card-bg);
    border: 1px solid rgba(21, 105, 222, 0.12);
    border-radius: 8px;
    padding: 12px;
    display: flex;
    flex-direction: column;
    gap: 4px;
    box-sizing: border-box;
    transition: border-color 0.2s ease;

    &:hover {
      border-color: rgba(21, 105, 222, 0.25);
    }

    .label {
      font-size: 12px;
      color: #60758e;
      font-weight: 600;
    }

    .value {
      font-size: 18px;
      font-weight: bold;
      color: #1569de;
      font-family: var(--app-font-number);
    }
  }
}

.passenger-flow-section {
  border-top: 1px solid rgba(21, 105, 222, 0.08);
  padding-top: 16px;
  margin-bottom: 20px;

  .section-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 12px;

    .section-title {
      font-size: 14px;
      font-weight: bold;
      color: #12304f;
    }
  }

  .chart-type-selector {
    display: flex;
    background: rgba(21, 105, 222, 0.05);
    padding: 2px;
    border-radius: 6px;
    border: 1px solid rgba(21, 105, 222, 0.1);
    gap: 2px;

    .type-pill {
      padding: 3px 10px;
      font-size: 11px;
      font-weight: 600;
      color: #1569de;
      cursor: pointer;
      border-radius: 4px;
      transition: all 0.2s ease;
      user-select: none;

      &:hover {
        background: rgba(255, 255, 255, 0.6);
      }

      &.active {
        background: #1569de;
        color: #ffffff;
        box-shadow: 0 1px 4px rgba(21, 105, 222, 0.2);
      }
    }
  }

  .chart-container-wrapper {
    height: 180px;
    width: 100%;
    position: relative;

    .chart_box {
      width: 100%;
      height: 100%;
    }

    .flow-chart {
      width: 100%;
      height: 100%;
    }
  }
}

.stations-section {
  border-top: 1px solid rgba(21, 105, 222, 0.08);
  padding-top: 16px;

  .section-title {
    font-size: 14px;
    font-weight: bold;
    color: #12304f;
    margin-bottom: 12px;
  }

  .station-scroll-list {
    max-height: calc(100vh - 670px);
    overflow-y: auto;
    padding-right: 6px;

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
}

/* Ranking Table / Leaderboard Styling */
/* 客流排行卡片：与数据管理右侧面板一致的「贴合玻璃」整体感（去掉嵌套白卡 + 蓝标题条） */
.rm-ranking-card {
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

  .ranking-panel {
    margin-top: 12px;
  }
}

.rm-right-card-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dm2-space-3, 12px);
  padding: 0 0 14px;
  border-bottom: 1px solid var(--dm2-line-faint, rgba(17, 32, 58, 0.07));
  background: transparent;

  .rm-title-head {
    min-width: 0;
  }

  h2 {
    margin: 4px 0 0;
    color: var(--dm2-ink, #1c2024);
    font-size: 19px;
    line-height: 1.25;
    font-weight: 780;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
}

.rm-panel-kicker {
  margin: 0;
  color: var(--dm2-accent-strong, #005bb5);
  font-size: 11px;
  font-weight: 760;
  letter-spacing: 0.04em;
}

.rm-ranking-tools {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.rm-seg {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  border: 1px solid var(--dm2-line, rgba(17, 32, 58, 0.1));
  border-radius: 8px;
  background: var(--dm2-field, #f1f4f9);
}

.rm-seg-btn {
  min-width: 38px;
  height: 24px;
  padding: 0 9px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--dm2-ink-soft, #3b4452);
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;
  transition:
    background-color var(--dm2-dur-fast, 140ms) var(--dm2-ease, ease),
    color var(--dm2-dur-fast, 140ms) var(--dm2-ease, ease),
    box-shadow var(--dm2-dur-fast, 140ms) var(--dm2-ease, ease);

  &:hover {
    color: var(--dm2-accent, #0071e3);
  }

  &.active {
    background: #ffffff;
    color: var(--dm2-accent-strong, #005bb5);
    box-shadow: 0 1px 3px rgba(13, 38, 76, 0.14), inset 0 0 0 1px rgba(0, 113, 227, 0.18);
  }
}

.rm-export-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 28px;
  padding: 0 12px;
  border: 0;
  border-radius: 8px;
  background: var(--dm2-accent-grad, linear-gradient(135deg, #0a84ff 0%, #0071e3 52%, #0a63cc 100%));
  color: #ffffff;
  font-size: 11.5px;
  font-weight: 700;
  white-space: nowrap;
  cursor: pointer;
  box-shadow: var(--dm2-accent-glow, 0 6px 18px -6px rgba(0, 113, 227, 0.45));
  transition: filter var(--dm2-dur, 240ms) var(--dm2-ease, ease);

  &:hover {
    filter: brightness(1.06);
  }

  .el-icon {
    font-size: 13px;
  }
}

.ranking-title-container {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding-right: 8px;

  .MCard2_title {
    font-weight: bold;
    color: #1569de !important;
  }

  .header-actions {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .transit-type-selector {
    display: flex;
    background: rgba(21, 105, 222, 0.08);
    padding: 2px;
    border-radius: 6px;
    border: 1px solid rgba(21, 105, 222, 0.15);
    gap: 2px;

    .type-pill {
      padding: 2px 8px;
      font-size: 11px;
      font-weight: bold;
      color: #1569de;
      cursor: pointer;
      border-radius: 4px;
      transition: all 0.2s ease;
      user-select: none;

      &:hover {
        background: rgba(255, 255, 255, 0.6);
      }

      &.active {
        background: #1569de;
        color: #ffffff;
        box-shadow: 0 1px 4px rgba(21, 105, 222, 0.2);
      }
    }
  }

  .export-btn {
    background: #1569de;
    border-color: #1569de;
    font-size: 11px;
    font-weight: 600;
    border-radius: 4px;
    padding: 6px 12px;
    transition: all 0.2s ease;

    &:hover, &:focus, &:active {
      background: #0b91b7 !important;
      border-color: #0b91b7 !important;
      color: #ffffff !important;
      box-shadow: 0 2px 6px rgba(21, 105, 222, 0.3);
    }
  }
}

.ranking-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 0;
}

.ranking-header {
  display: flex;
  align-items: center;
  padding: 10px 16px;
  margin-bottom: 8px;
  border: 0;
  border-radius: 8px;
  background: var(--dm2-accent, #0071e3);
  color: #ffffff;

  span {
    color: #ffffff;
    font-size: 12.5px;
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
  padding-right: 4px;
  scrollbar-width: thin;
  scrollbar-color: rgba(0, 113, 227, 0.2) transparent;

  &::-webkit-scrollbar {
    width: 6px;
  }
  &::-webkit-scrollbar-thumb {
    background: rgba(0, 113, 227, 0.2);
    border-radius: 3px;
  }
  &::-webkit-scrollbar-thumb:hover {
    background: rgba(0, 113, 227, 0.4);
  }
}

.ranking-row {
  width: 100%;
  border: 0;
  display: flex;
  align-items: center;
  text-align: left;
  cursor: pointer;
  padding: 12px 14px;
  border-bottom: 1px dashed var(--dm2-line, rgba(17, 32, 58, 0.1));
  border-radius: var(--dm2-radius-sm, 10px);
  background: transparent;
  color: inherit;
  font: inherit;
  transition:
    background-color var(--dm2-dur, 240ms) var(--dm2-ease, ease),
    border-color var(--dm2-dur, 240ms) var(--dm2-ease, ease);

  &:hover {
    background: var(--dm2-accent-weak, rgba(0, 113, 227, 0.1));
    border-bottom-color: transparent;
  }

  &:last-child {
    border-bottom: none;
  }
}

.col-rank {
  width: 46px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: flex-start;
}

.col-name {
  flex-grow: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-right: 12px;
  min-width: 0;
}

.col-flow {
  width: 108px;
  flex-shrink: 0;
  display: flex;
  align-items: baseline;
  justify-content: flex-end;
  gap: 3px;
}

.rank-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  font-size: 12px;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
  color: var(--dm2-muted, #667085);
  background: rgba(113, 128, 150, 0.1);

  &.gold {
    background: #d97706;
    color: #ffffff;
    font-size: 13px;
  }

  &.silver {
    background: #94a3b8;
    color: #ffffff;
    font-size: 13px;
  }

  &.bronze {
    background: #ea580c;
    color: #ffffff;
    font-size: 13px;
  }
}

.route-name-text {
  font-size: 14px;
  font-weight: 800;
  color: var(--dm2-ink, #1c2024);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.route-desc-text {
  font-size: 11px;
  color: var(--dm2-muted-soft, #98a2b3);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.flow-value {
  font-size: 16px;
  font-weight: 800;
  color: var(--dm2-add, #1a8a3f);
  font-family: var(--dm2-font-num, var(--app-font-number));
  font-variant-numeric: tabular-nums;

  .ranking-row:nth-child(-n+3) & {
    color: #d97706;
  }
}

.flow-unit {
  font-size: 11px;
  color: var(--dm2-muted, #667085);
  font-weight: 600;
}

.station-info-header {
  padding: 10px 4px 18px 4px;
  border-bottom: 1px solid rgba(21, 105, 222, 0.08);
  margin-bottom: 16px;

  .station-main {
    display: flex;
    align-items: center;
    gap: 14px;

    .station-icon-wrapper {
      width: 44px;
      height: 44px;
      border-radius: var(--app-card-radius);
      display: flex;
      align-items: center;
      justify-content: center;

      &.subway {
        background: rgba(220, 76, 93, 0.08);
        border: 1px solid rgba(239, 68, 68, 0.2);
        color: #dc4c5d;
      }

      &.bus {
        background: rgba(15, 159, 110, 0.08);
        border: 1px solid rgba(16, 185, 129, 0.2);
        color: #0f9f6e;
      }

      .station-icon {
        font-size: 20px;
      }
    }

    .station-details {
      display: flex;
      flex-direction: column;
      gap: 4px;

      .station-title-text {
        font-size: 16px;
        font-weight: bold;
        color: #12304f;
        letter-spacing: 0.3px;
      }

      .type-badge-container {
        display: flex;
        align-items: center;
        gap: 8px;

        .type-tag {
          font-weight: bold;
          border-radius: 4px;
        }

        .route-count-badge {
          font-size: 11px;
          color: #60758e;
          font-weight: 600;
          background: rgba(240, 244, 248, 0.8);
          padding: 2px 6px;
          border-radius: 4px;
        }
      }
    }
  }
}

// Custom badges inside list
.line-badge {
  font-size: 12px;
  font-weight: bold;
  padding: 2px 8px;
  border-radius: 4px;

  &.subway-badge {
    background: rgba(239, 68, 68, 0.1);
    color: #dc4c5d;
  }

  &.bus-badge {
    background: rgba(16, 185, 129, 0.1);
    color: #0f9f6e;
  }
}

.matched-item {
  transition: border-color 0.2s ease, background-color 0.2s ease;
  &:hover {
    border-color: rgba(21, 105, 222, 0.3) !important;
  }
}

.ranking-title-container {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding-right: 8px;

  .header-actions-left {
    display: flex;
    align-items: center;
    flex: 1;
    min-width: 0;
  }

  .detail-tab-selector {
    display: flex;
    background: rgba(21, 105, 222, 0.05);
    padding: 2px;
    border-radius: 6px;
    border: 1px solid rgba(21, 105, 222, 0.1);
    gap: 2px;
    white-space: nowrap;

    .tab-pill {
      padding: 4px 10px;
      font-size: 11px;
      font-weight: 600;
      color: #1569de;
      cursor: pointer;
      border-radius: 4px;
      transition: all 0.2s ease;
      user-select: none;

      &:hover {
        background: rgba(255, 255, 255, 0.6);
      }

      &.active {
        background: #1569de;
        color: #ffffff;
        box-shadow: 0 1px 4px rgba(21, 105, 222, 0.2);
      }
    }
  }

  .header-actions {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .export-btn {
    background: #1569de;
    border-color: #1569de;
    font-size: 11px;
    font-weight: 600;
    border-radius: 4px;
    padding: 6px 12px;
    transition: all 0.2s ease;

    &:hover, &:focus, &:active {
      background: #0b91b7 !important;
      border-color: #0b91b7 !important;
      color: #ffffff !important;
      box-shadow: 0 2px 6px rgba(21, 105, 222, 0.3);
    }
  }
}

/* Station Boarding & Alighting Panel Premium Styling */
.route-boarding-alighting-panel {
  display: flex;
  flex-direction: column;
  padding: 8px 4px;

  .time-range-section {
    background: rgba(21, 105, 222, 0.02);
    border: 1px dashed rgba(21, 105, 222, 0.15);
    padding: 12px 16px;
    border-radius: 8px;
    margin-bottom: 20px;

    .time-range-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 6px;

      .title {
        font-size: 13px;
        font-weight: bold;
        color: #1569de;
      }

      .range-text {
        font-size: 13px;
        font-weight: bold;
        color: #12304f;
        background: rgba(21, 105, 222, 0.08);
        padding: 2px 8px;
        border-radius: 4px;
      }
    }

    .time-range-slider {
      padding: 0 8px;

      :deep(.el-slider__bar) {
        background-color: #1569de;
      }
      :deep(.el-slider__button) {
        border-color: #1569de;
        width: 14px;
        height: 14px;
      }
    }
  }

  .boarding-alighting-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 12px;

    .section-title {
      font-size: 14px;
      font-weight: bold;
      color: #12304f;
    }
  }

  .boarding-alighting-chart-wrapper {
    height: calc(100vh - 460px);
    width: 100%;
    position: relative;

    .chart_box {
      width: 100%;
      height: 100%;
    }

    .boarding-alighting-bar-chart {
      width: 100%;
      height: 100%;
    }
  }
}

/* Station OD Panel Premium Styling */
.route-od-panel {
  display: flex;
  flex-direction: column;
  padding: 8px 4px;

  .time-range-section {
    background: rgba(21, 105, 222, 0.02);
    border: 1px dashed rgba(21, 105, 222, 0.15);
    padding: 12px 16px;
    border-radius: 8px;
    margin-bottom: 20px;

    .time-range-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: 6px;

      .title {
        font-size: 13px;
        font-weight: bold;
        color: #1569de;
      }

      .range-text {
        font-size: 13px;
        font-weight: bold;
        color: #12304f;
        background: rgba(21, 105, 222, 0.08);
        padding: 2px 8px;
        border-radius: 4px;
      }
    }

    .time-range-slider {
      padding: 0 8px;

      :deep(.el-slider__bar) {
        background-color: #1569de;
      }
      :deep(.el-slider__button) {
        border-color: #1569de;
        width: 14px;
        height: 14px;
      }
    }
  }

  .od-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 12px;

    .section-title {
      font-size: 14px;
      font-weight: bold;
      color: #12304f;
    }

    .chart-type-selector {
      display: flex;
      background: rgba(21, 105, 222, 0.05);
      padding: 2px;
      border-radius: 6px;
      border: 1px solid rgba(21, 105, 222, 0.1);
      gap: 2px;

      .type-pill {
        padding: 3px 10px;
        font-size: 11px;
        font-weight: 600;
        color: #1569de;
        cursor: pointer;
        border-radius: 4px;
        transition: all 0.2s ease;
        user-select: none;

        &:hover {
          background: rgba(255, 255, 255, 0.6);
        }

        &.active {
          background: #1569de;
          color: #ffffff;
          box-shadow: 0 1px 4px rgba(21, 105, 222, 0.2);
        }
      }
    }
  }

  .od-table-wrapper {
    max-height: calc(100vh - 460px);
    overflow-y: auto;
    padding-right: 4px;

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

  .od-chart-wrapper {
    height: calc(100vh - 460px);
    width: 100%;
    position: relative;

    .chart_box {
      width: 100%;
      height: 100%;
    }

    .od-bar-chart {
      width: 100%;
      height: 100%;
    }
  }
}

/* Station Reachability Panel Premium Styling */
.route-reachability-panel {
  display: flex;
  flex-direction: column;
  padding: 8px 4px;

  .reachability-grid {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 8px;
    margin-bottom: 20px;

    .metric-card {
      background: var(--app-card-bg);
      border: 1px solid rgba(21, 105, 222, 0.12);
      border-radius: 8px;
      padding: 10px 8px;
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 4px;
      transition: border-color 0.2s ease;

      &.direct {
        border-color: rgba(16, 185, 129, 0.2);
        .value { color: #1a8a3f; }
      }
      &.transfer1 {
        border-color: rgba(59, 130, 246, 0.2);
        .value { color: #0071e3; }
      }
      &.transfer2 {
        border-color: rgba(245, 158, 11, 0.2);
        .value { color: #b06a00; }
      }

      .label {
        font-size: 11px;
        color: #60758e;
        font-weight: 600;
        text-align: center;
      }

      .value {
        font-size: 16px;
        font-weight: bold;
        font-family: var(--app-font-number);

        small {
          font-size: 10px;
          font-weight: normal;
          color: #60758e;
        }
      }
    }
  }

  .reachability-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 12px;

    .section-title {
      font-size: 14px;
      font-weight: bold;
      color: #12304f;
    }
  }

  .reachability-chart-wrapper {
    height: calc(100vh - 420px);
    width: 100%;
    position: relative;

    .chart_box {
      width: 100%;
      height: 100%;
    }

    .reachability-pie-chart {
      width: 100%;
      height: 100%;
    }
  }
}

.transfer-table {
  display: flex;
  flex-direction: column;
  width: 100%;

  .transfer-table-header {
    display: flex;
    background: rgba(21, 105, 222, 0.05);
    border-radius: 6px;
    padding: 10px 14px;
    font-weight: bold;
    color: #1569de;
    font-size: 12px;
    margin-bottom: 8px;

    span {
      display: inline-block;
    }
    .col-station { flex: 1.2; }
    .col-volume { flex: 1.1; text-align: right; margin-right: 20px; }
    .col-ratio { flex: 1.3; }

    .col-od-route { flex: 2.2; }
    .col-od-flow { flex: 1.1; text-align: right; margin-right: 10px; }
  }

  .transfer-table-body {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .transfer-table-row {
    display: flex;
    align-items: center;
    padding: 11px 14px;
    border-bottom: 1px dashed rgba(21, 105, 222, 0.1);
    font-size: 12px;
    color: #2d3748;
    transition: all 0.25s ease;

    &:hover {
      background: rgba(21, 105, 222, 0.02);
    }

    .col-station {
      flex: 1.2;
      color: #4b5563;
      font-weight: 600;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .col-volume {
      flex: 1.1;
      text-align: right;
      font-family: var(--app-font-number);
      font-weight: 700;
      color: #1569de;
      margin-right: 20px;
    }

    .col-ratio {
      flex: 1.3;
      display: flex;
      align-items: center;
      gap: 8px;

      .ratio-bar-bg {
        flex: 1;
        height: 6px;
        background: #f1f5f9;
        border-radius: 3px;
        overflow: hidden;
      }
      .ratio-bar-fill {
        display: block;
        height: 100%;
        border-radius: 3px;
        background: #2f75d6;
      }
      .ratio-text {
        font-size: 11px;
        font-weight: 600;
        color: #64748b;
        width: 38px;
        text-align: right;
      }
    }

    .col-od-route {
      display: flex;
      flex-direction: column;
      gap: 2px;
      flex: 2.2;
      color: #12304f;
      font-weight: 600;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;

      strong,
      small {
        display: block;
        min-width: 0;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }

      strong {
        color: #12304f;
        font-size: 12px;
        line-height: 1.2;
      }

      small {
        color: #60758e;
        font-size: 10px;
        line-height: 1.2;
      }
    }

    .col-od-flow {
      flex: 1.1;
      text-align: right;
      font-family: var(--app-font-number);
      font-weight: 700;
      color: #1569de;
      margin-right: 10px;

      small {
        font-size: 10px;
        font-weight: normal;
        color: #60758e;
      }
    }
  }
}

/* —— 需求9：乘降热力图入口按钮 —— */
.pfa-station-sections .pfa-section-actions {
  display: flex;
  align-items: center;
  gap: var(--dm2-space-2);
  min-width: 0;
}

.pfa-station-sections .pfa-heatmap-btn {
  height: 22px;
  padding: 0 10px;
  font-size: var(--dm2-text-xs);
  border-color: rgba(21, 105, 222, 0.35);
  color: #1569de;
  flex-shrink: 0;
}

/* —— 需求8：OD曲线控制区（色阶 + 图例 + 方向说明） —— */
.pfa-station-sections .od-curve-control {
  display: flex;
  flex-direction: column;
  gap: var(--dm2-space-2);
  margin-top: var(--dm2-space-3);
  padding: var(--dm2-space-3) var(--dm2-space-4);
  border-radius: 8px;
  background: var(--dm2-surface-sunken);
  border: 1px solid var(--dm2-line);
}

.pfa-station-sections .od-curve-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dm2-space-2);

  .od-curve-title {
    font-size: var(--dm2-text-sm);
    font-weight: var(--dm2-fw-semibold);
    color: var(--dm2-ink-soft);
    white-space: nowrap;
  }

  .pfa-section-meta {
    white-space: normal;
    text-align: right;
  }
}

/* —— 需求10：可达性分组显隐开关 —— */
.reachability-list-head .reachability-level-switch {
  flex-shrink: 0;
  margin-left: var(--dm2-space-2);
}
</style>

<style lang="scss">
/* 需求9：热力图弹窗 append-to-body，样式需全局作用域。
   注意：element.scss 按需引入时未包含 dialog.scss，el-dialog 无任何默认结构样式，
   需自带宽度 / 居中 / 背景（同 XLZL.vue boarding-heatmap-dialog）。 */
.station-heatmap-overlay .el-overlay-dialog {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: auto;
}

.station-heatmap-dialog {
  position: relative;
  width: var(--el-dialog-width, 70%);
  max-width: 1200px;
  min-width: 560px;
  margin: 0 auto;
  background: #f7fbff;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 18px 48px rgba(15, 23, 42, 0.22);
  outline: none; /* 焦点陷阱聚焦容器时不显示浏览器默认焦点环 */

  .el-dialog__header {
    margin-right: 0;
    padding: 14px 20px;
    border-bottom: 1px solid rgba(21, 105, 222, 0.12);
  }

  .el-dialog__headerbtn {
    position: absolute;
    top: 12px;
    right: 14px;
    width: 28px;
    height: 28px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    padding: 0;
    border: none;
    background: transparent;
    color: #64748b;
    font-size: 18px;
    cursor: pointer;

    &:hover {
      color: #1569de;
    }
  }

  .el-dialog__body {
    padding: 12px 20px 18px;
  }
}

.station-heatmap-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  padding-right: 28px;

  .station-heatmap-kicker {
    font-size: 12px;
    color: #667085;
    margin-bottom: 2px;
  }

  .station-heatmap-title {
    font-size: 17px;
    font-weight: 700;
    color: #12304f;
    line-height: 1.25;
  }

  .station-heatmap-meta {
    font-size: 12px;
    color: #1569de;
    font-variant-numeric: tabular-nums;
    white-space: nowrap;
  }
}

.station-heatmap-body {
  height: 62vh;
  min-height: 360px;
  display: flex;
  align-items: center;
  justify-content: center;

  .station-heatmap-chart {
    width: 100%;
    height: 100%;
  }

  .el-empty {
    margin: 0 auto;
  }
}
</style>
