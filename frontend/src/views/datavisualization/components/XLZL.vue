<!-- 线路分析 (Route Analysis) -->
<template>
  <div class="XLZL" v-bind="$attrs">
    <div v-if="loading" class="loading-container">
      <el-empty description="加载所有线路中，请稍等...." />
    </div>
    
    <div v-else class="info-container">
      <MCard class="card search-card" :open="true" title="公交线路搜索">
        <template #body>
          <!-- Search Mode Selector -->
          <div class="search-mode-container">
            <el-radio-group v-model="searchMode" size="default" class="search-mode-group">
              <el-radio-button value="line">按线路名称查找</el-radio-button>
              <el-radio-button value="station">按站点名称查找</el-radio-button>
            </el-radio-group>
          </div>

          <!-- Search Select Inputs -->
          <div class="search-input-wrapper">
            <!-- Line Search Dropdown -->
            <div v-if="searchMode === 'line'">
              <el-select-v2
                v-model="selectedLineName"
                :options="lineOptions"
                placeholder="请输入或选择公交线路"
                filterable
                clearable
                @change="handleLineChange"
                class="custom-select"
              >
                <template #prefix>
                  <el-icon class="search-icon"><Search /></el-icon>
                </template>
              </el-select-v2>
            </div>

            <!-- Station Search Dropdown -->
            <div v-else>
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
          </div>

          <!-- Mode 1: Search by Line Name - Route details -->
          <template v-if="searchMode === 'line' && selectedLineName">
            <!-- Directions Pill (if multiple routes) -->
            <div class="route-directions" v-if="selectedLineRoutes.length > 1">
              <div
                v-for="(route, index) in selectedLineRoutes"
                :key="route.routeId"
                :class="['direction-pill', activeRouteId === route.routeId ? 'active' : '']"
                @click="handleSelectRoute(route)"
              >
                {{ getRouteEndpointLabel(route, index) }}
              </div>
            </div>
          </template>

          <!-- Mode 2: Search by Station Name - Matched lines -->
          <template v-if="searchMode === 'station' && selectedStationName">
            <div class="matched-title" v-if="matchedRoutes.length > 0">
              经过该站的线路 ({{ matchedRoutes.length }})
            </div>
            
            <div class="scroll-container" v-if="matchedRoutes.length > 0">
              <div class="matched-list">
                <div
                  v-for="item in matchedRoutes"
                  :key="item.routeId"
                  :class="['matched-item', activeMatchedRouteId === item.routeId ? 'active' : '']"
                  @click="handleSelectMatchedRoute(item)"
                >
                  <div class="item-header">
                    <span class="line-badge">{{ item.lineName }}</span>
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
            
            <div v-else class="empty-matched">
              <el-empty description="没有找到经过该站点的线路" />
            </div>
          </template>
        </template>
      </MCard>
    </div>
  </div>

  <teleport v-if="!runMonitorSimplifiedRight" to="#datavisualization_index_box2" defer>
    <MCard2 v-if="!runMonitorSimplifiedRight && currentSelectedRoute" class="SJZL_right_card" :open="true">
      <template #title>
        <div class="ranking-title-container">
          <div class="header-actions-left">
            <div class="detail-tab-selector">
              <div 
                v-for="tab in [{value: 'overview', label: '线路数据总览'}, {value: 'boardingAlighting', label: '线路乘降分析'}, {value: 'segments', label: '线路断面分析'}, {value: 'transfer', label: '换乘关联分析'}]" 
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
        <!-- Tab 1: 线路数据总览 -->
        <div v-if="activeDetailTab === 'overview'" class="route-detail-panel">
          <!-- 8 Metrics Grid -->
          <div class="metrics-grid">
            <div class="metric-card">
              <span class="label">线路长度</span>
              <span class="value">{{ routeMetrics.length }}</span>
            </div>
            <div class="metric-card">
              <span class="label">首班时间</span>
              <span class="value">{{ routeMetrics.firstTime }}</span>
            </div>
            <div class="metric-card">
              <span class="label">末班时间</span>
              <span class="value">{{ routeMetrics.lastTime }}</span>
            </div>
            <div class="metric-card">
              <span class="label">直线系数</span>
              <span class="value">{{ routeMetrics.directness }}</span>
            </div>
            <div class="metric-card">
              <span class="label">站点数量</span>
              <span class="value">{{ routeMetrics.stationCount }}</span>
            </div>
            <div class="metric-card">
              <span class="label">平均站距</span>
              <span class="value">{{ routeMetrics.avgStationDistance }}</span>
            </div>
            <div class="metric-card">
              <span class="label">日均客流</span>
              <span class="value">{{ routeMetrics.passenger }}</span>
            </div>
            <div class="metric-card">
              <span class="label">满载率</span>
              <span class="value">{{ routeMetrics.loadRate }}</span>
            </div>
          </div>

          <!-- Passenger Flow Chart Section -->
          <div class="passenger-flow-section">
            <div class="section-header">
              <span class="section-title">全天客流变化</span>
              <div class="chart-type-selector">
                <div
                  v-for="type in ['line', 'bar']"
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
                    :update-options="{ notMerge: true }"
                  />
                </template>
              </el-auto-resizer>
            </div>
          </div>

          <!-- Passenger Portrait Section -->
          <div class="demographics-section">
            <div class="section-title">客流画像</div>
            <div class="demographics-content">
              <!-- Visual Demographics Row -->
              <div class="demographics-cards">
                <div class="demo-card commuter">
                  <div class="card-meta">
                    <div class="demo-icon">
                      <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                        <rect x="2" y="7" width="20" height="14" rx="2" ry="2"></rect>
                        <path d="M16 21V5a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2v16"></path>
                      </svg>
                    </div>
                    <span class="demo-label">通勤比例</span>
                    <span class="demo-value">{{ routeDemographics.commuter }}%</span>
                  </div>
                  <div class="demo-progress-wrapper">
                    <div class="demo-progress-bar commuter" :style="{ width: routeDemographics.commuter + '%' }"></div>
                  </div>
                </div>

                <div class="demo-card student">
                  <div class="card-meta">
                    <div class="demo-icon">
                      <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                        <path d="M22 10v6M2 10l10-5 10 5-10 5z"></path>
                        <path d="M6 12v5c0 2 2 3 6 3s6-1 6-3v-5"></path>
                      </svg>
                    </div>
                    <span class="demo-label">学生比例</span>
                    <span class="demo-value">{{ routeDemographics.student }}%</span>
                  </div>
                  <div class="demo-progress-wrapper">
                    <div class="demo-progress-bar student" :style="{ width: routeDemographics.student + '%' }"></div>
                  </div>
                </div>

                <div class="demo-card elderly">
                  <div class="card-meta">
                    <div class="demo-icon">
                      <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                        <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"></path>
                        <circle cx="9" cy="7" r="4"></circle>
                        <path d="M23 21v-2a4 4 0 0 0-3-3.87"></path>
                        <path d="M16 3.13a4 4 0 0 1 0 7.75"></path>
                      </svg>
                    </div>
                    <span class="demo-label">老人比例</span>
                    <span class="demo-value">{{ routeDemographics.elderly }}%</span>
                  </div>
                  <div class="demo-progress-wrapper">
                    <div class="demo-progress-bar elderly" :style="{ width: routeDemographics.elderly + '%' }"></div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <!-- Timeline Stations List -->
          <div class="stations-section">
            <div class="section-title">沿途站点 (按站序)</div>
            <div class="station-scroll-list">
              <div class="timeline-container">
                <div 
                  v-for="(fac, index) in currentSelectedRoute.facilities" 
                  :key="fac.facilityId || index"
                  class="timeline-item"
                >
                  <div :class="['timeline-dot', index === 0 ? 'first' : '', index === currentSelectedRoute.facilities.length - 1 ? 'last' : '']">
                    <div class="dot-inner"></div>
                  </div>
                  <div class="timeline-content">
                    <span class="station-name">{{ fac.facilityName }}</span>
                    <span class="station-idx">第 {{ index + 1 }} 站</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- Tab 2: 线路乘降分析 -->
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
              :max="22" 
              :step="1"
              :show-tooltip="false"
              class="time-range-slider"
            />
          </div>

          <!-- Boarding Alighting Chart Section -->
          <div class="boarding-alighting-header">
            <span class="section-title">站点乘降指标分析</span>
          </div>

          <div class="boarding-alighting-chart-wrapper">
            <el-auto-resizer class="chart_box">
              <template #default="{ height, width }">
                <VChart
                  v-if="width > 0 && height > 0"
                  class="boarding-alighting-bar-chart"
                  :option="boardingAlightingChartOption"
                  autoresize
                  :update-options="{ notMerge: true }"
                />
              </template>
            </el-auto-resizer>
          </div>

          <div class="boarding-profile-header">
            <span class="section-title">沿线乘降与车内客流</span>
          </div>

          <div class="boarding-profile-chart-wrapper">
            <el-auto-resizer class="chart_box">
              <template #default="{ height, width }">
                <VChart
                  v-if="width > 0 && height > 0"
                  class="boarding-profile-chart"
                  :option="boardingProfileChartOption"
                  autoresize
                  :update-options="{ notMerge: true }"
                />
              </template>
            </el-auto-resizer>
          </div>
        </div>

        <!-- Tab 3: 线路断面分析 -->
        <div v-else-if="activeDetailTab === 'segments'" class="route-segments-panel">
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
              :max="22" 
              :step="1"
              :show-tooltip="false"
              class="time-range-slider"
            />
          </div>

          <!-- View Mode Selector: Table vs Chart -->
          <div class="segments-header">
            <span class="section-title">断面指标分析</span>
            <el-radio-group v-model="segmentViewMode" size="small" class="view-mode-group">
              <el-radio-button value="table">数据表格</el-radio-button>
              <el-radio-button value="chart">可视化图表</el-radio-button>
            </el-radio-group>
          </div>

          <!-- View 1: Data Table -->
          <div v-if="segmentViewMode === 'table'" class="segments-scroll-wrapper">
            <div class="segments-table">
              <div class="table-header">
                <span class="col-name">断面名称</span>
                <span class="col-flow">客流量 (人次)</span>
                <span class="col-load">满载率</span>
              </div>
              <div class="table-body">
                <div 
                  v-for="(seg, idx) in routeSegments" 
                  :key="idx"
                  class="table-row"
                >
                  <span class="col-name">{{ seg.name }}</span>
                  <span class="col-flow">{{ seg.flow.toLocaleString() }}</span>
                  <span class="col-load">
                    <span :class="['load-indicator', seg.loadRate >= 70 ? 'high' : seg.loadRate >= 45 ? 'medium' : 'low']">
                      {{ seg.loadRate }}%
                    </span>
                  </span>
                </div>
              </div>
            </div>
          </div>

          <!-- View 2: ECharts Visual Representation -->
          <div v-else class="segments-chart-wrapper">
            <el-auto-resizer class="chart_box">
              <template #default="{ height, width }">
                <VChart
                  v-if="width > 0 && height > 0"
                  class="segments-bar-chart"
                  :option="segmentChartOption"
                  autoresize
                  :update-options="{ notMerge: true }"
                />
              </template>
            </el-auto-resizer>
          </div>
        </div>

        <!-- Tab 4: 换乘关联分析 -->
        <div v-else-if="activeDetailTab === 'transfer'" class="route-transfer-panel">
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
              :max="22" 
              :step="1"
              :show-tooltip="false"
              class="time-range-slider"
            />
          </div>

          <!-- View Mode Selector: Table vs Chart -->
          <div class="transfer-header">
            <span class="section-title">换乘线路关联分析</span>
            <el-radio-group v-model="transferViewMode" size="small" class="view-mode-group">
              <el-radio-button value="table">数据表格</el-radio-button>
              <el-radio-button value="chart">可视化图表</el-radio-button>
            </el-radio-group>
          </div>

          <!-- View 1: Data Table -->
          <div v-if="transferViewMode === 'table'" class="transfer-scroll-wrapper">
            <div class="transfer-table">
              <div class="table-header">
                <span class="col-name">关联换乘线路</span>
                <span class="col-station">换乘站</span>
                <span class="col-flow">换乘客流 (人次)</span>
                <span class="col-ratio">换乘占比</span>
              </div>
              <div class="table-body">
                <div 
                  v-for="(item, idx) in transferTableData" 
                  :key="idx"
                  class="table-row"
                >
                  <span class="col-name">
                    <span :class="['line-badge-icon', item.name.startsWith('地铁') ? 'metro' : 'bus']">
                      {{ item.name.startsWith('地铁') ? 'M' : 'B' }}
                    </span>
                    {{ item.name }}
                  </span>
                  <span class="col-station">{{ item.station }}</span>
                  <span class="col-flow">{{ item.flow.toLocaleString() }}</span>
                  <span class="col-ratio">
                    <span class="ratio-bar-bg">
                      <span class="ratio-bar-fill" :style="{ width: item.ratio * 2 + '%' }"></span>
                    </span>
                    <span class="ratio-text">{{ item.ratio }}%</span>
                  </span>
                </div>
              </div>
            </div>
          </div>

          <!-- View 2: ECharts Visual Representation -->
          <div v-else class="transfer-chart-wrapper">
            <el-auto-resizer class="chart_box">
              <template #default="{ height, width }">
                <VChart
                  v-if="width > 0 && height > 0"
                  class="transfer-bar-chart"
                  :option="transferChartOption"
                  autoresize
                  :update-options="{ notMerge: true }"
                />
              </template>
            </el-auto-resizer>
          </div>
        </div>
      </template>
    </MCard2>

    <div v-else class="rm-right-card rm-ranking-card">
      <div class="rm-right-card-title">
        <div class="rm-title-head">
          <p class="rm-panel-kicker">线路客流</p>
          <h2>{{ activeTransitType === 'bus' ? '公交' : '地铁' }}线路客流排行</h2>
        </div>
        <div class="rm-ranking-tools">
          <div class="rm-seg" role="group" aria-label="客流类型">
            <button
              v-for="type in ['bus', 'subway']"
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
          <span class="col-name">线路名称</span>
          <span class="col-flow">日均客流量</span>
        </div>
        <div class="ranking-scroll-list">
          <button
            v-for="(item, index) in currentLeaderboard"
            :key="index"
            class="ranking-row"
            type="button"
            @click="selectLeaderboardLine(item)"
          >
            <div class="col-rank">
              <span :class="['rank-badge', index === 0 ? 'gold' : index === 1 ? 'silver' : index === 2 ? 'bronze' : '']">
                {{ index + 1 }}
              </span>
            </div>
            <div class="col-name">
              <span class="route-name-text">{{ item.lineName }}</span>
              <span class="route-desc-text">{{ item.desc }}</span>
            </div>
            <div class="col-flow">
              <span class="flow-value">{{ item.passengerFlow.toLocaleString() }}</span>
              <span class="flow-unit">人次</span>
            </div>
          </button>
        </div>
      </div>
    </div>
  </teleport>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch, inject, computed, getCurrentInstance, nextTick } from "vue";
import { Search, Location, Timer, Connection, Download } from "@element-plus/icons-vue";
import { getLineAll, getRouteDetail, getRoutePanel, getRoutePanelDetail, getRouteTileBinary } from "@/api/route";
import MCard from "./MCard.vue";
import MCard2 from "./MCard2.vue";
import { RouteLayer } from "../layers/RouteLayer.js";
import { emptyFeatureCollection, stationsToFeatureCollection } from "../layers/maplibreLayerUtils.js";
import { injectSync } from "@/utils";

const props = defineProps({
  model: String,
});

const loading = ref(true);
const searchMode = ref("line"); // "line" | "station"
const rawLines = ref([]);
const allLinks = ref([]);
const routeDetailCache = new Map();
const routePanelDetailCache = new Map();
const routePanelDetailPromises = new Map();
const routePanelData = ref(null);
const selectedRoutePanel = ref(null);
let routePanelPromise = null;

const selectedLineName = ref("");
const selectedStationName = ref("");
const activeRouteId = ref("");
const activeMatchedRouteId = ref("");
const matchedRoutes = ref([]);
const selectedRouteDetail = ref(null);

// 注入来自 index.vue 的全局线宽配置与 MapRef
const LineWidthRef = inject("LineWidthRef", ref(100));
const MapRef = inject("MapRef", ref(null));
const BaseMapLineModeRef = inject("BaseMapLineModeRef", ref("bus-network"));

// 注入右侧面板显示控制
const rightPanelHasContent = inject("rightPanelHasContent", ref(false));
const activeDatavisualizationTab = inject("activeDatavisualizationTab", ref(""));

// 运行监测页：右侧改为简化卡片（单条线路日客流量+折线图）。
// 此处禁用本组件向右侧面板的 teleport，并把选中线路数据上抛给 index.vue。
// 客流分析页不提供这些注入，默认值保证其行为完全不变。
const runMonitorSimplifiedRight = inject("runMonitorSimplifiedRight", false);
const runMonitorSelectedLinePanel = inject("runMonitorSelectedLinePanel", null);
const runMonitorSelectedLineName = inject("runMonitorSelectedLineName", null);
const runMonitorSelectedRouteDetail = inject("runMonitorSelectedRouteDetail", null);

// 统一的当前选中路线计算属性
const currentSelectedRoute = computed(() => {
  const targetId = searchMode.value === "line" ? activeRouteId.value : activeMatchedRouteId.value;
  if (!targetId) return null;
  if (String(selectedRouteDetail.value?.routeId || "") === String(targetId)) return selectedRouteDetail.value;
  if (routeDetailCache.has(String(targetId))) return routeDetailCache.get(String(targetId));
  for (const line of rawLines.value) {
    if (line.routes) {
      const match = line.routes.find(r => String(r.routeId) === String(targetId));
      if (match) return match;
    }
  }
  return null;
});

const currentRoutePanel = computed(() => {
  const targetId = searchMode.value === "line" ? activeRouteId.value : activeMatchedRouteId.value;
  if (!targetId) return null;
  if (String(selectedRoutePanel.value?.routeId || "") === String(targetId)) {
    return selectedRoutePanel.value;
  }
  return routePanelData.value?.routes?.[targetId] || null;
});

function toFiniteNumber(value, fallback = 0) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function sumHourRange(values, startHour, endHour) {
  if (!Array.isArray(values)) return 0;
  const start = Math.max(0, Math.min(23, Number(startHour) || 0));
  const end = Math.max(start, Math.min(23, Number(endHour) || start));
  let total = 0;
  for (let hour = start; hour <= end; hour++) {
    total += toFiniteNumber(values[hour], 0);
  }
  return total;
}

function averageHourRange(values, startHour, endHour) {
  if (!Array.isArray(values)) return 0;
  const start = Math.max(0, Math.min(23, Number(startHour) || 0));
  const end = Math.max(start, Math.min(23, Number(endHour) || start));
  let total = 0;
  let count = 0;
  for (let hour = start; hour <= end; hour++) {
    const value = Number(values[hour]);
    if (Number.isFinite(value)) {
      total += value;
      count++;
    }
  }
  return count ? total / count : 0;
}

const routeMetrics = computed(() => {
  const route = currentSelectedRoute.value || {};
  const info = route.info || {};
  const panelMetrics = currentRoutePanel.value?.metrics || {};
  const length = toFiniteNumber(panelMetrics.routeDist ?? info.routeDist, 0);
  const stationCount = toFiniteNumber(panelMetrics.facNum ?? info.facNum ?? route.facilities?.length, 0);
  const avgStationDistance = toFiniteNumber(panelMetrics.facDist ?? info.facDist, 0);
  const fallbackStationDistance = stationCount > 1 && Number.isFinite(length) && length > 0
    ? length / (stationCount - 1)
    : 0;
  const directness = toFiniteNumber(panelMetrics.lc ?? info.lc, 0);
  const passenger = toFiniteNumber(panelMetrics.passenger ?? info.passenger, 0);
  const panelLoadRate = toFiniteNumber(panelMetrics.loadRate, NaN);
  const loadRate = Number.isFinite(panelLoadRate)
    ? panelLoadRate
    : toFiniteNumber(info.takeRate, 0) * 100;
  return {
    length: Number.isFinite(length) && length > 0 ? `${(length / 1000).toFixed(2)} km` : "--",
    firstTime: formatSecondsToTime(panelMetrics.firstTime ?? info.firstTime),
    lastTime: formatSecondsToTime(panelMetrics.lastTime ?? info.lastTime),
    directness: Number.isFinite(directness) && directness > 0 ? directness.toFixed(2) : "--",
    stationCount: stationCount > 0 ? `${stationCount} 个` : "--",
    avgStationDistance: Number.isFinite(avgStationDistance) && avgStationDistance > 0
      ? `${Math.round(avgStationDistance)} m`
      : fallbackStationDistance > 0 ? `${Math.round(fallbackStationDistance)} m` : "--",
    passenger: Number.isFinite(passenger) && passenger > 0 ? `${Math.round(passenger).toLocaleString()} 人次` : "--",
    loadRate: Number.isFinite(loadRate) && loadRate > 0 ? `${loadRate.toFixed(1)}%` : "--",
  };
});

const routeDemographics = computed(() => {
  const demographics = currentRoutePanel.value?.demographics || {};
  return {
    commuter: toFiniteNumber(demographics.commuter, 0),
    student: toFiniteNumber(demographics.student, 0),
    elderly: toFiniteNumber(demographics.elderly, 0)
  };
});

const boardingAlightingChartOption = computed(() => {
  const route = currentSelectedRoute.value || {};
  const facilities = route.facilities || [];
  
  const stationNames = facilities.map(f => f.facilityName || "");
  const startHour = segmentTimeRange.value[0];
  const endHour = segmentTimeRange.value[1];
  const stationFlowMap = new Map((currentRoutePanel.value?.stationFlows || [])
    .map(item => [item.facilityId, item]));
  const boardingData = facilities.map((fac) => {
    const flow = stationFlowMap.get(fac.facilityId);
    return sumHourRange(flow?.boardingByHour, startHour, endHour);
  });
  const alightingData = facilities.map((fac) => {
    const flow = stationFlowMap.get(fac.facilityId);
    return -sumHourRange(flow?.alightingByHour, startHour, endHour);
  });
  
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
        const stationName = params[0].name;
        let html = `<div style="font-weight: bold; margin-bottom: 4px;">${stationName}</div>`;
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
      bottom: "15%",
      top: "15%",
      containLabel: true
    },
    xAxis: {
      type: "category",
      data: stationNames,
      axisLine: {
        lineStyle: {
          color: "rgba(21, 105, 222, 0.15)"
        }
      },
      axisLabel: {
        color: "#64748b",
        fontSize: 9,
        interval: 0,
        rotate: 35,
        formatter: (value) => {
          if (value.length > 5) {
            return value.substring(0, 5) + "...";
          }
          return value;
        }
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

const boardingProfileChartOption = computed(() => {
  const route = currentSelectedRoute.value || {};
  const facilities = route.facilities || [];
  const startHour = segmentTimeRange.value[0];
  const endHour = segmentTimeRange.value[1];
  const stationFlowMap = new Map((currentRoutePanel.value?.stationFlows || [])
    .map(item => [item.facilityId, item]));
  const stationNames = facilities.map(f => f.facilityName || "");
  const boardingData = facilities.map((fac) => {
    const flow = stationFlowMap.get(fac.facilityId);
    return sumHourRange(flow?.boardingByHour, startHour, endHour);
  });
  const alightingData = facilities.map((fac) => {
    const flow = stationFlowMap.get(fac.facilityId);
    return sumHourRange(flow?.alightingByHour, startHour, endHour);
  });
  const onboardData = [];
  let onboard = 0;
  for (let index = 0; index < facilities.length; index++) {
    onboard = Math.max(0, onboard + boardingData[index] - alightingData[index]);
    onboardData.push(onboard);
  }

  return {
    backgroundColor: "transparent",
    tooltip: {
      trigger: "axis",
      axisPointer: { type: "cross" },
      backgroundColor: "rgba(30, 41, 59, 0.9)",
      borderColor: "rgba(255, 255, 255, 0.15)",
      textStyle: {
        color: "#ffffff",
        fontSize: 12
      }
    },
    legend: {
      data: ["上车", "下车", "车内客流"],
      top: 0,
      textStyle: {
        color: "#64748b",
        fontSize: 11
      }
    },
    grid: {
      left: "3%",
      right: "4%",
      bottom: "18%",
      top: "18%",
      containLabel: true
    },
    xAxis: {
      type: "category",
      data: stationNames,
      axisLabel: {
        color: "#64748b",
        fontSize: 9,
        interval: 0,
        rotate: 35,
        formatter: (value) => value.length > 5 ? value.substring(0, 5) + "..." : value
      },
      axisLine: {
        lineStyle: {
          color: "rgba(21, 105, 222, 0.15)"
        }
      }
    },
    yAxis: [
      {
        type: "value",
        name: "乘降",
        axisLabel: {
          color: "#64748b",
          fontSize: 10
        },
        splitLine: {
          lineStyle: {
            color: "rgba(21, 105, 222, 0.06)",
            type: "dashed"
          }
        }
      },
      {
        type: "value",
        name: "车内",
        axisLabel: {
          color: "#64748b",
          fontSize: 10
        },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: "上车",
        type: "bar",
        barWidth: "24%",
        data: boardingData,
        itemStyle: {
          color: "#0f9f6e",
          borderRadius: [3, 3, 0, 0]
        }
      },
      {
        name: "下车",
        type: "bar",
        barWidth: "24%",
        data: alightingData,
        itemStyle: {
          color: "#f43f5e",
          borderRadius: [3, 3, 0, 0]
        }
      },
      {
        name: "车内客流",
        type: "line",
        yAxisIndex: 1,
        smooth: 0.25,
        showSymbol: true,
        symbol: "circle",
        symbolSize: 5,
        data: onboardData,
        itemStyle: {
          color: "#1569de"
        },
        lineStyle: {
          color: "#1569de",
          width: 2.6
        },
        areaStyle: {
          color: "rgba(21, 105, 222, 0.08)"
        }
      }
    ]
  };
});

function buildTransferRowsForRange() {
  const startHour = segmentTimeRange.value[0];
  const endHour = segmentTimeRange.value[1];
  const rows = (currentRoutePanel.value?.transfers || []).map((item) => ({
    name: item.lineName || item.lineId || "--",
    station: item.station || "--",
    flow: sumHourRange(item.flowByHour, startHour, endHour),
    ratio: 0
  })).filter(item => item.flow > 0);
  const total = rows.reduce((sum, item) => sum + item.flow, 0);
  return rows
    .map(item => ({
      ...item,
      ratio: total > 0 ? Number(((item.flow / total) * 100).toFixed(1)) : 0
    }))
    .sort((a, b) => b.flow - a.flow);
}

const transferChartOption = computed(() => {
  const transferRows = buildTransferRowsForRange();
  const transferLines = transferRows.map(item => item.name);
  const passengerData = transferRows.map(item => item.flow);
  
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
        const item = params[0];
        return `<div style="font-weight: bold; margin-bottom: 4px;">换乘关联分析</div>
          <div style="display: flex; align-items: center; justify-content: space-between; gap: 16px;">
            <div style="display: flex; align-items: center;">
              <span style="display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: ${item.color}; margin-right: 6px;"></span>
              <span>${item.name}:</span>
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
      data: transferLines,
      axisLine: {
        lineStyle: {
          color: "rgba(21, 105, 222, 0.15)"
        }
      },
      axisLabel: {
        color: "#64748b",
        fontSize: 11
      }
    },
    series: [
      {
        name: "换乘人数",
        type: "bar",
        barWidth: "45%",
        itemStyle: {
          color: {
            type: "linear",
            x: 0,
            y: 0,
            x2: 1,
            y2: 0,
            colorStops: [
              { offset: 0, color: "#0b91b7" },
              { offset: 1, color: "#b9dcff" }
            ]
          },
          borderRadius: [0, 4, 4, 0]
        },
        data: passengerData
      }
    ]
  };
});

const transferTableData = computed(() => {
  return buildTransferRowsForRange();
});

const { proxy } = getCurrentInstance() || {};
const activeChartType = ref("line");

const passengerFlowChartOption = computed(() => {
  const isLine = activeChartType.value === "line";
  const hours = ["06:00", "07:00", "08:00", "09:00", "10:00", "11:00", "12:00", "13:00", "14:00", "15:00", "16:00", "17:00", "18:00", "19:00", "20:00", "21:00", "22:00"];
  const hourlyFlow = currentRoutePanel.value?.hourlyFlow || [];
  const data = hours.map((_, index) => toFiniteNumber(hourlyFlow[index + 6], 0));

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

// 监听当前选中的路线，控制右侧面板内容状态
watch(currentSelectedRoute, (newRoute) => {
  if (activeDatavisualizationTab.value === "线路客流监测") {
    rightPanelHasContent.value = true;
  }
}, { immediate: true });

watch(activeDatavisualizationTab, (newTab) => {
  if (newTab === "线路客流监测") {
    rightPanelHasContent.value = true;
  }
});

// 运行监测页：把当前选中线路的客流面板与名称上抛给 index.vue 的简化右侧卡片。
// 客流分析页未提供这些注入（值为 null），此处为无操作，不影响其行为。
watch(
  [currentRoutePanel, currentSelectedRoute, selectedLineName],
  () => {
    if (!runMonitorSelectedLinePanel && !runMonitorSelectedLineName) return;
    if (runMonitorSelectedLinePanel) {
      runMonitorSelectedLinePanel.value = currentRoutePanel.value || null;
    }
    if (runMonitorSelectedLineName) {
      const route = currentSelectedRoute.value || {};
      const baseName = selectedLineName.value || route.lineName || route.routeName || route.lineId || "";
      const facilities = Array.isArray(route.facilities) ? route.facilities : [];
      const startName = facilities[0]?.facilityName || "";
      const endName = facilities[facilities.length - 1]?.facilityName || "";
      runMonitorSelectedLineName.value = baseName && startName && endName
        ? `${baseName}（${startName} - ${endName}）`
        : baseName;
    }
  },
  { immediate: true },
);

watch(selectedRouteDetail, (detail) => {
  if (runMonitorSelectedRouteDetail) {
    runMonitorSelectedRouteDetail.value = detail || null;
  }
});

function handleExportLeaderboard() {
  if (proxy?.$message) {
    proxy.$message.success({
      message: "客流排行榜数据已成功导出为 Excel！",
      type: "success",
      duration: 2000
    });
  }
}

const activeTransitType = ref("bus");

const currentLeaderboard = computed(() => {
  const list = routePanelData.value?.summary?.leaderboard?.[activeTransitType.value] || [];
  return list.map(item => ({
    lineName: item.lineName || item.lineId || "--",
    desc: item.desc || "",
    passengerFlow: toFiniteNumber(item.passengerFlow, 0)
  }));
});

const activeDetailTab = ref("overview");
const segmentViewMode = ref("table");
const transferViewMode = ref("table");
const segmentTimeRange = ref([8, 18]);

function formatHourLabel(hour) {
  return `${hour.toString().padStart(2, "0")}:00`;
}

function handleExportDetail() {
  if (proxy?.$message) {
    const text = activeDetailTab.value === "overview" 
      ? "线路数据总览" 
      : activeDetailTab.value === "boardingAlighting" 
        ? "线路乘降分析" 
        : "线路断面分析";
    proxy.$message.success({
      message: `${text}数据已成功导出！`,
      type: "success",
      duration: 2000
    });
  }
}

const routeSegments = computed(() => {
  const startHour = segmentTimeRange.value[0];
  const endHour = segmentTimeRange.value[1];
  return (currentRoutePanel.value?.segments || []).map((segment) => ({
    name: segment.name,
    flow: Math.round(sumHourRange(segment.flowByHour, startHour, endHour)),
    loadRate: Number(averageHourRange(segment.loadRateByHour, startHour, endHour).toFixed(1))
  }));
});

const segmentChartOption = computed(() => {
  const linearGradient = (proxy?.$echarts?.graphic?.LinearGradient) || function() { return null; };
  const segments = routeSegments.value;
  const names = segments.map(s => s.name);
  const loadRates = segments.map(s => s.loadRate);
  
  const colors = loadRates.map(rate => {
    if (rate >= 70) {
      return new linearGradient(0, 0, 1, 0, [
        { offset: 0, color: "rgba(239, 68, 68, 0.3)" },
        { offset: 1, color: "#dc4c5d" }
      ]);
    } else if (rate >= 45) {
      return new linearGradient(0, 0, 1, 0, [
        { offset: 0, color: "rgba(245, 158, 11, 0.3)" },
        { offset: 1, color: "#d97706" }
      ]);
    } else {
      return new linearGradient(0, 0, 1, 0, [
        { offset: 0, color: "rgba(16, 185, 129, 0.3)" },
        { offset: 1, color: "#0f9f6e" }
      ]);
    }
  });

  return {
    tooltip: {
      trigger: "axis",
      appendToBody: true,
      extraCssText: "z-index: 999; border-radius: 8px; border: none; box-shadow: 0 4px 12px rgba(0,0,0,0.12);",
      backgroundColor: "rgba(255, 255, 255, 0.98)",
      textStyle: {
        color: "#2d3748",
        fontSize: 11
      },
      formatter: function(params) {
        if (!params || params.length === 0) return "";
        const idx = params[0].dataIndex;
        const seg = segments[idx];
        return `
          <div style="font-weight: 600; margin-bottom: 4px; color: #1569de;">${seg.name}</div>
          <div style="display: flex; flex-direction: column; gap: 4px;">
            <div>客流量: <strong style="color: #2d3748;">${seg.flow.toLocaleString()} 人次</strong></div>
            <div>满载率: <strong style="color: ${seg.loadRate >= 70 ? '#dc4c5d' : seg.loadRate >= 45 ? '#d97706' : '#0f9f6e'};">${seg.loadRate}%</strong></div>
          </div>
        `;
      }
    },
    legend: {
      data: ["满载率 (%)", "客流量 (人次)"],
      textStyle: {
        color: "#64748b",
        fontSize: 10
      },
      top: 0,
      right: 10
    },
    grid: {
      top: 30,
      left: 10,
      right: 25,
      bottom: 5,
      containLabel: true
    },
    xAxis: [
      {
        type: "value",
        name: "满载率",
        max: 100,
        splitLine: {
          lineStyle: {
            color: "rgba(21, 105, 222, 0.05)",
            type: "dashed"
          }
        },
        axisLabel: {
          color: "#60758e",
          fontSize: 10
        }
      },
      {
        type: "value",
        name: "客流量",
        splitLine: { show: false },
        axisLabel: {
          color: "#60758e",
          fontSize: 10
        }
      }
    ],
    yAxis: {
      type: "category",
      data: names,
      axisLine: {
        lineStyle: {
          color: "rgba(21, 105, 222, 0.15)"
        }
      },
      axisLabel: {
        color: "#2d3748",
        fontSize: 10,
        fontWeight: "600",
        width: 140,
        overflow: "truncate"
      },
      axisTick: { show: false }
    },
    series: [
      {
        name: "满载率 (%)",
        type: "bar",
        xAxisIndex: 0,
        data: loadRates,
        barWidth: "22%",
        itemStyle: {
          borderRadius: [0, 4, 4, 0],
          color: function(params) {
            return colors[params.dataIndex];
          }
        }
      },
      {
        name: "客流量 (人次)",
        type: "bar",
        xAxisIndex: 1,
        data: segments.map(s => s.flow),
        barWidth: "22%",
        itemStyle: {
          borderRadius: [0, 4, 4, 0],
          color: new linearGradient(0, 0, 1, 0, [
            { offset: 0, color: "rgba(21, 105, 222, 0.25)" },
            { offset: 1, color: "#1569de" }
          ])
        }
      }
    ]
  };
});

// 模型公交线网背景，与数据管理使用同一组青灰色和淡化透明度。
const _BgRouteLayer = new RouteLayer({
  zIndex: 998,
  lineWidth: LineWidthRef.value,
  flowControl: false,
  color: 0x2f6f73,
  opacity: 0.72,
});

// 选中/激活路线图层（与数据管理一致的橙色高亮）
const _RouteLayer = new RouteLayer({
  zIndex: 999,
  lineWidth: LineWidthRef.value * 1.8,
  flowControl: false,
  color: 0xf97316,
  opacity: 1
});

const SELECTED_ROUTE_STOPS_SOURCE_ID = "selected-route-stops-source";
const SELECTED_ROUTE_STOPS_LAYER_ID = "selected-route-stops-layer";
const ROUTE_STROKE_COLOR = "#1569de";

// 将图层添加到地图
injectSync("MapRef").then((map) => {
  if (!runMonitorSimplifiedRight) {
    map.value?.addLayer(_BgRouteLayer);
    map.value?.addLayer(_RouteLayer);
    _BgRouteLayer.setTileSource(props.model, { tileRequest: getRouteTileBinary });
    if (BaseMapLineModeRef.value === "bus-network") {
      _BgRouteLayer.hide();
    }
  }
});

// 监听线宽变化
watch(LineWidthRef, (value) => {
  _BgRouteLayer.setLineWidth(value);
  _RouteLayer.setLineWidth(value * 1.8);
});
watch(BaseMapLineModeRef, (mode) => {
  if (runMonitorSimplifiedRight) return;
  if (!currentSelectedRoute.value) {
    updateLayers(null);
  }
});

// 计算所有唯一的线路名称，并转换为 el-select-v2 需要的 options 格式
const lineOptions = computed(() => {
  const names = rawLines.value.map(line => line.lineName).filter(Boolean);
  const uniqueNames = Array.from(new Set(names)).sort((a, b) => a.localeCompare(b, "zh-CN"));
  return uniqueNames.map(name => ({ value: name, label: name }));
});

// 计算所有唯一的站点名称，并转换为 el-select-v2 需要的 options 格式
const stationOptions = computed(() => {
  const names = [];
  rawLines.value.forEach(line => {
    if (line.routes) {
      line.routes.forEach(route => {
        if (route.facilities) {
          route.facilities.forEach(fac => {
            if (fac.facilityName) {
              names.push(fac.facilityName);
            }
          });
        }
      });
    }
  });
  const uniqueNames = Array.from(new Set(names)).sort((a, b) => a.localeCompare(b, "zh-CN"));
  return uniqueNames.map(name => ({ value: name, label: name }));
});

// 将线路候选项上抛给 index.vue 的右上角搜索框
const runMonitorLineOptions = inject("runMonitorLineOptions", null);
if (runMonitorLineOptions) {
  watch(lineOptions, (options) => {
    runMonitorLineOptions.value = options || [];
  }, { immediate: true });
  onUnmounted(() => {
    runMonitorLineOptions.value = [];
  });
}

// 获取选定线路的所有行车方向/子线路
const selectedLineRoutes = computed(() => {
  if (!selectedLineName.value) return [];
  const line = rawLines.value.find(l => l.lineName === selectedLineName.value);
  return line ? line.routes || [] : [];
});

// 获取当前活动路线方向的详情
const activeRoute = computed(() => {
  if (!activeRouteId.value) return null;
  for (const line of rawLines.value) {
    if (line.routes) {
      const match = line.routes.find(r => r.routeId === activeRouteId.value);
      if (match) return match;
    }
  }
  return null;
});

function getRouteEndpointLabel(route, index) {
  const facilities = Array.isArray(route?.facilities) ? route.facilities : [];
  const startName = facilities[0]?.facilityName || "";
  const endName = facilities[facilities.length - 1]?.facilityName || "";
  if (startName && endName) return `${startName} - ${endName}`;
  return route?.routeName || `线路 ${index + 1}`;
}

// 格式化秒数为 HH:mm
function formatSecondsToTime(seconds) {
  if (seconds === undefined || seconds === null) return "--:--";
  const h = Math.floor(seconds / 3600).toString().padStart(2, "0");
  const m = Math.floor((seconds % 3600) / 60).toString().padStart(2, "0");
  return `${h}:${m}`;
}

// 更新图层状态
function updateLayers(activeLinks = null) {
  if (runMonitorSimplifiedRight) {
    _RouteLayer.setData([]);
    if (!activeLinks?.length) {
      selectedRouteDetail.value = null;
      cleanUpSelectedRouteStops();
    }
    return;
  }
  if (activeLinks?.length) {
    _BgRouteLayer.hide();
    _RouteLayer.setData(activeLinks);
  } else {
    _BgRouteLayer.hide();
    _RouteLayer.setData([]);
    selectedRouteDetail.value = null;
    cleanUpSelectedRouteStops();
  }
}

function normalizeLineSearchName(value = "") {
  return String(value || "")
    .trim()
    .replace(/\s+/g, "")
    .replace(/[（(].*?[）)]/g, "")
    .toLowerCase();
}

async function selectLineByName(lineName) {
  const target = normalizeLineSearchName(lineName);
  if (!target) return false;
  const line =
    rawLines.value.find((item) => normalizeLineSearchName(item.lineName) === target) ||
    rawLines.value.find((item) => normalizeLineSearchName(item.lineName).includes(target) || target.includes(normalizeLineSearchName(item.lineName)));
  if (!line?.lineName) return false;
  searchMode.value = "line";
  selectedStationName.value = "";
  selectedLineName.value = line.lineName;
  await nextTick();
  await handleLineChange(line.lineName);
  return true;
}

function selectLeaderboardLine(item) {
  selectLineByName(item?.lineName);
}

// 运行监测页：按地图上被点中的线路要素精确选中（含方向）。
// 先按线路名定位线路，再用要素属性里的方向线索（route_id / dir）在多条方向中精确选中对应方向。
async function selectLineByFeature(props = {}) {
  const name = props.lineName || props.line_name || props.routeName || props.route_name || props.name || "";
  const targetName = normalizeLineSearchName(name);
  if (!targetName) return false;
  const line =
    rawLines.value.find((item) => normalizeLineSearchName(item.lineName) === targetName) ||
    rawLines.value.find((item) => normalizeLineSearchName(item.lineName).includes(targetName) || targetName.includes(normalizeLineSearchName(item.lineName)));
  if (!line) return false;
  searchMode.value = "line";
  selectedStationName.value = "";
  selectedLineName.value = line.lineName;
  await nextTick();
  const routes = line.routes || [];
  let target = null;
  const routeIdHint = props.routeId ?? props.route_id;
  if (routeIdHint != null && routeIdHint !== "") {
    target = routes.find((item) => String(item.routeId) === String(routeIdHint)) || null;
  }
  if (!target) {
    const dir = Number(props.dir ?? props.direction);
    if (Number.isInteger(dir) && routes[dir]) target = routes[dir];
  }
  target ||= routes[0] || null;
  if (target) await handleSelectRoute(target);
  return true;
}

function routeStopStations(route) {
  const seen = new Set();
  return (route?.facilities || []).map((fac) => {
    const coord = fac.coord || {};
    const x = Number(coord.x);
    const y = Number(coord.y);
    if (!Number.isFinite(x) || !Number.isFinite(y)) return null;
    const key = fac.facilityId || `${x.toFixed(2)}_${y.toFixed(2)}`;
    if (seen.has(key)) return null;
    seen.add(key);
    return {
      name: fac.facilityName,
      facilityId: fac.facilityId,
      x,
      y,
      type: "route-stop",
    };
  }).filter(Boolean);
}

function updateSelectedRouteStops(route) {
  if (runMonitorSimplifiedRight) {
    cleanUpSelectedRouteStops();
    return;
  }
  const map = MapRef.value?.map;
  if (!map) return;
  const stations = routeStopStations(route);
  if (!stations.length) {
    cleanUpSelectedRouteStops();
    return;
  }
  const data = stationsToFeatureCollection(stations);
  if (!map.getSource(SELECTED_ROUTE_STOPS_SOURCE_ID)) {
    map.addSource(SELECTED_ROUTE_STOPS_SOURCE_ID, {
      type: "geojson",
      data: emptyFeatureCollection(),
    });
  }
  map.getSource(SELECTED_ROUTE_STOPS_SOURCE_ID).setData(data);
  if (!map.getLayer(SELECTED_ROUTE_STOPS_LAYER_ID)) {
    map.addLayer({
      id: SELECTED_ROUTE_STOPS_LAYER_ID,
      type: "circle",
      source: SELECTED_ROUTE_STOPS_SOURCE_ID,
      paint: {
        "circle-radius": [
          "interpolate",
          ["linear"],
          ["zoom"],
          10, 2.2,
          12, 4,
          14, 6.8,
          16, 10,
          18, 14
        ],
        "circle-color": "#ffffff",
        "circle-stroke-color": ROUTE_STROKE_COLOR,
        "circle-stroke-width": [
          "interpolate",
          ["linear"],
          ["zoom"],
          10, 1,
          13, 1.8,
          16, 3.2,
          18, 4.4
        ],
        "circle-opacity": 0.98,
        "circle-stroke-opacity": 0.98,
      }
    });
  }
}

function cleanUpSelectedRouteStops() {
  const map = MapRef.value?.map;
  if (!map) return;
  if (map.getLayer(SELECTED_ROUTE_STOPS_LAYER_ID)) map.removeLayer(SELECTED_ROUTE_STOPS_LAYER_ID);
  if (map.getSource(SELECTED_ROUTE_STOPS_SOURCE_ID)) map.removeSource(SELECTED_ROUTE_STOPS_SOURCE_ID);
}

// 根据一条线路的 links 数组计算中心点并居中地图
function centerOnRoute(links) {
  if (!links || !links.length) return;
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  const points = [];
  links.forEach(link => {
    if (link.from.x < minX) minX = link.from.x;
    if (link.from.x > maxX) maxX = link.from.x;
    if (link.from.y < minY) minY = link.from.y;
    if (link.from.y > maxY) maxY = link.from.y;
    points.push([link.from.x, link.from.y]);
    
    if (link.to.x < minX) minX = link.to.x;
    if (link.to.x > maxX) maxX = link.to.x;
    if (link.to.y < minY) minY = link.to.y;
    if (link.to.y > maxY) maxY = link.to.y;
    points.push([link.to.x, link.to.y]);
  });
  if (typeof MapRef.value?.setFitZoomAndCenterByPoints === "function") {
    MapRef.value.setFitZoomAndCenterByPoints(points);
    return;
  }
  const centerX = (minX + maxX) / 2;
  const centerY = (minY + maxY) / 2;
  MapRef.value?.setCenter([centerX, centerY]);
}

// 切换线路时
async function handleLineChange(lineName) {
  if (!lineName) {
    activeRouteId.value = "";
    selectedRouteDetail.value = null;
    selectedRoutePanel.value = null;
    updateLayers(null);
    return;
  }
  const routes = selectedLineRoutes.value;
  if (routes && routes.length > 0) {
    await handleSelectRoute(routes[0]);
  }
}

// 选择某条线路的某个方向
async function loadRouteDetail(route) {
  if (!route?.routeId) return route;
  const routeId = String(route.routeId);
  if (routeDetailCache.has(routeId)) {
    return routeDetailCache.get(routeId);
  }
  const res = await getRouteDetail({
    datasource: props.model,
    routeId: route.routeId,
  });
  const detail = {
    ...route,
    ...(res.data || {}),
    facilities: res.data?.facilities || route.facilities || [],
    info: res.data?.info || route.info || {},
  };
  routeDetailCache.set(routeId, detail);
  return detail;
}

async function loadRoutePanelDetail(routeId) {
  const key = String(routeId || "");
  if (!key) return null;
  if (routePanelDetailCache.has(key)) return routePanelDetailCache.get(key);
  if (routePanelDetailPromises.has(key)) return routePanelDetailPromises.get(key);
  const model = props.model;
  const promise = getRoutePanelDetail({ datasource: model, routeId: key }, { silentError: true })
    .then((res) => {
      const panel = res?.data && typeof res.data === "object" ? res.data : null;
      if (props.model === model && panel && Object.keys(panel).length) {
        routePanelDetailCache.set(key, panel);
        return panel;
      }
      return null;
    })
    .catch(() => null)
    .finally(() => {
      routePanelDetailPromises.delete(key);
    });
  routePanelDetailPromises.set(key, promise);
  return promise;
}

function ensureRoutePanelData() {
  if (routePanelData.value) return Promise.resolve(routePanelData.value);
  if (routePanelPromise) return routePanelPromise;
  const model = props.model;
  routePanelPromise = getRoutePanel({ datasource: model }, { silentError: true })
    .then((res) => {
      if (props.model === model) routePanelData.value = res.data || null;
      return routePanelData.value;
    })
    .catch(() => null)
    .finally(() => {
      routePanelPromise = null;
    });
  return routePanelPromise;
}

async function handleSelectRoute(route) {
  const routeId = String(route?.routeId || "");
  if (!routeId) return;
  activeRouteId.value = routeId;
  selectedRoutePanel.value = routePanelDetailCache.get(routeId) || null;
  const [detail, panel] = await Promise.all([
    loadRouteDetail(route),
    runMonitorSimplifiedRight ? loadRoutePanelDetail(routeId) : Promise.resolve(null),
  ]);
  if (String(activeRouteId.value) !== routeId) return;
  selectedRouteDetail.value = detail;
  if (runMonitorSimplifiedRight) selectedRoutePanel.value = panel;
  if (detail?.links && detail.links.length > 0) {
    updateLayers(detail.links);
    centerOnRoute(detail.links);
  }
  updateSelectedRouteStops(detail);
}

// 切换站点时
function handleStationChange(stationName) {
  if (!stationName) {
    matchedRoutes.value = [];
    activeMatchedRouteId.value = "";
    selectedRouteDetail.value = null;
    selectedRoutePanel.value = null;
    updateLayers(null);
    return;
  }

  const matches = [];
  rawLines.value.forEach(line => {
    if (line.routes) {
      line.routes.forEach(route => {
        if (route.facilities) {
          const hasFac = route.facilities.some(fac => fac.facilityName === stationName);
          if (hasFac) {
            const fac = route.facilities.find(fac => fac.facilityName === stationName);
            matches.push({
              lineId: line.lineId,
              lineName: line.lineName,
              routeId: route.routeId,
              routeName: route.routeName,
              info: route.info,
              links: route.links,
              facilities: route.facilities,
              stationCoord: fac ? fac.coord : null,
            });
          }
        }
      });
    }
  });

  matchedRoutes.value = matches;
  activeMatchedRouteId.value = "";
  selectedRouteDetail.value = null;
  selectedRoutePanel.value = null;
  updateLayers(null); // 不要自动选中第一条线路
}

// 选择途径该站点的某条线路
async function handleSelectMatchedRoute(item) {
  const routeId = String(item?.routeId || "");
  if (!routeId) return;
  activeMatchedRouteId.value = routeId;
  selectedRoutePanel.value = routePanelDetailCache.get(routeId) || null;
  const [detail, panel] = await Promise.all([
    loadRouteDetail(item),
    runMonitorSimplifiedRight ? loadRoutePanelDetail(routeId) : Promise.resolve(null),
  ]);
  if (String(activeMatchedRouteId.value) !== routeId) return;
  selectedRouteDetail.value = detail;
  if (runMonitorSimplifiedRight) selectedRoutePanel.value = panel;
  if (detail?.links) {
    updateLayers(detail.links);
    centerOnRoute(detail.links);
  }
  updateSelectedRouteStops(detail);
}

// 切换搜索模式时清空选项并还原路线
watch(searchMode, () => {
  selectedLineName.value = "";
  selectedStationName.value = "";
  activeRouteId.value = "";
  activeMatchedRouteId.value = "";
  matchedRoutes.value = [];
  selectedRouteDetail.value = null;
  selectedRoutePanel.value = null;
  updateLayers(null);
});

// 线路摘要先到先用；体积较大的客流面板异步补齐，不能再阻塞地图和搜索。
async function loadAllLines() {
  const model = props.model;
  loading.value = true;
  routePanelData.value = null;
  selectedRoutePanel.value = null;
  if (!runMonitorSimplifiedRight) ensureRoutePanelData();
  try {
    const lineRes = await getLineAll({ datasource: model });
    if (props.model !== model) return;
      const data = (lineRes.data || []).map((line) => ({
        ...line,
        lineName: line?.lineName || line?.lineId || "未命名线路",
      }));
      rawLines.value = data;
      allLinks.value = [];
      if (!runMonitorSimplifiedRight) {
        _BgRouteLayer.setTileSource(model, { tileRequest: getRouteTileBinary });
      }
      updateLayers(null);
  } catch {
    if (props.model === model) rawLines.value = [];
  } finally {
    if (props.model === model) {
      loading.value = false;
    }
  }
}

onMounted(() => {
  if (props.model) {
    loadAllLines();
  }
  if (activeDatavisualizationTab.value === "线路客流监测") {
    rightPanelHasContent.value = true;
  }
});

watch(() => props.model, (newModel) => {
  if (newModel) {
    routeDetailCache.clear();
    routePanelDetailCache.clear();
    routePanelDetailPromises.clear();
    routePanelData.value = null;
    selectedRoutePanel.value = null;
    selectedLineName.value = "";
    selectedStationName.value = "";
    activeRouteId.value = "";
    activeMatchedRouteId.value = "";
    matchedRoutes.value = [];
    selectedRouteDetail.value = null;
    loadAllLines();
  }
});

onUnmounted(() => {
  if (runMonitorSelectedRouteDetail) runMonitorSelectedRouteDetail.value = null;
  _BgRouteLayer.dispose();
  _RouteLayer.dispose();
  cleanUpSelectedRouteStops();
});

// 取消选中：清空选中线路与地图高亮（供 index.vue 点击空白处调用）
function clearSelection() {
  selectedLineName.value = "";
  selectedStationName.value = "";
  activeRouteId.value = "";
  activeMatchedRouteId.value = "";
  matchedRoutes.value = [];
  selectedRouteDetail.value = null;
  selectedRoutePanel.value = null;
  updateLayers(null);
  cleanUpSelectedRouteStops();
}

defineExpose({
  selectLineByName,
  selectLineByFeature,
  clearSelection,
});
</script>

<style lang="scss" scoped>
.XLZL {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  width: 100%;
}

.search-card {
  border: 1px solid rgba(21, 105, 222, 0.15) !important;
  box-shadow: none !important;
  border-radius: var(--app-panel-radius) !important;
  background-color: var(--app-card-bg);
  overflow: hidden;

  :deep(.MCard_title_box) {
    background-color: rgba(21, 105, 222, 0.05) !important;
    border-bottom: 1px solid rgba(21, 105, 222, 0.1) !important;
  }
}

.search-mode-container {
  display: flex;
  justify-content: center;
  margin-bottom: var(--space-sm);
  
  .search-mode-group {
    width: 100%;
    display: flex;
    :deep(.el-radio-button) {
      flex: 1;
      .el-radio-button__inner {
        width: 100%;
        border-radius: var(--app-card-radius);
        font-weight: 600;
        transition: all 0.3s ease;
      }
    }
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

.route-directions {
  display: flex;
  gap: var(--space-xs);
  margin-bottom: var(--space-sm);
  background: rgba(240, 244, 248, 0.7);
  padding: var(--space-2xs);
  border-radius: var(--app-card-radius);
  
  .direction-pill {
    flex: 1;
    text-align: center;
    padding: 6px 12px;
    font-size: 13px;
    font-weight: 600;
    color: #60758e;
    cursor: pointer;
    border-radius: 4px;
    transition: all 0.2s ease;
    
    &:hover {
      background: rgba(255, 255, 255, 0.8);
      color: #1569de;
    }
    
    &.active {
      background: var(--app-card-bg);
      color: #1569de;
    }
  }
}

.route-info-panel {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  background: rgba(21, 105, 222, 0.04);
  border: 1px solid rgba(21, 105, 222, 0.08);
  border-radius: 8px;
  padding: 10px;
  margin-bottom: 14px;
  
  .info-metric {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 4px;
    color: #2d3748;
    
    .el-icon {
      color: #1569de;
      font-size: 16px;
    }
    
    .label {
      font-size: 11px;
      color: #60758e;
    }
    
    .value {
      font-size: 12px;
      font-weight: bold;
      white-space: nowrap;
    }
  }
}

.stop-list-wrapper {
  border-top: 1px solid rgba(21, 105, 222, 0.08);
  padding-top: 12px;
  
  .stop-list-title {
    font-size: 13px;
    font-weight: bold;
    color: #12304f;
    margin-bottom: 10px;
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
    cursor: pointer;
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

.route-detail-panel {
  display: flex;
  flex-direction: column;
  padding: 8px 4px;
}

.metrics-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
  margin-bottom: 12px;
  
  .metric-card {
    background: var(--app-card-bg);
    border: 1px solid rgba(21, 105, 222, 0.12);
    border-radius: 6px;
    padding: 8px 10px;
    display: flex;
    flex-direction: column;
    gap: 2px;
    box-sizing: border-box;
    transition: border-color 0.2s ease;
    
    &:hover {
      border-color: rgba(21, 105, 222, 0.25);
    }
    
    .label {
      font-size: 11px;
      color: #60758e;
      font-weight: 600;
    }
    
    .value {
      font-size: 16px;
      font-weight: bold;
      color: #1569de;
      font-family: var(--app-font-number);
    }
  }
}

.passenger-flow-section {
  border-top: 1px solid rgba(21, 105, 222, 0.08);
  padding-top: 12px;
  margin-bottom: 12px;
  
  .section-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 10px;
    
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
      padding: 3px 8px;
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
    height: 135px;
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

.demographics-section {
  border-top: 1px solid rgba(21, 105, 222, 0.08);
  padding-top: 10px;
  margin-bottom: 10px;

  .section-title {
    font-size: 14px;
    font-weight: bold;
    color: #12304f;
    margin-bottom: 10px;
  }

  .demographics-content {
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .demographics-cards {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 8px;
  }

  .demo-card {
    display: flex;
    flex-direction: column;
    align-items: stretch;
    gap: 6px;
    padding: 8px 10px;
    border-radius: 6px;
    background: rgba(21, 105, 222, 0.02);
    border: 1px solid rgba(21, 105, 222, 0.06);
    transition: background-color 0.2s ease, border-color 0.2s ease;

    &:hover {
      background: rgba(21, 105, 222, 0.05);
    }

    .card-meta {
      display: flex;
      align-items: center;
      gap: 6px;
      width: 100%;
    }

    .demo-icon {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 22px;
      height: 22px;
      border-radius: 5px;
      flex-shrink: 0;
    }

    .demo-label {
      font-size: 10px;
      font-weight: 500;
      color: #64748b;
      white-space: nowrap;
    }

    .demo-value {
      font-size: 12px;
      font-weight: bold;
      color: #1e293b;
      margin-left: auto;
    }

    .demo-progress-wrapper {
      width: 100%;
      height: 5px;
      background: #e2e8f0;
      border-radius: 2.5px;
      overflow: hidden;
    }

    .demo-progress-bar {
      height: 100%;
      border-radius: 2.5px;
      transition: width 0.6s cubic-bezier(0.4, 0, 0.2, 1);

      &.commuter {
        background: #1569de;
      }

      &.student {
        background: #2f75d6;
      }

      &.elderly {
        background: #d97706;
      }
    }

    /* Card Themes */
    &.commuter {
      border-color: rgba(21, 105, 222, 0.28);
      background: rgba(21, 105, 222, 0.035);
      .demo-icon {
        background: rgba(21, 105, 222, 0.08);
        color: #1569de;
      }
    }

    &.student {
      border-color: rgba(59, 130, 246, 0.28);
      background: rgba(59, 130, 246, 0.035);
      .demo-icon {
        background: rgba(59, 130, 246, 0.08);
        color: #2f75d6;
      }
    }

    &.elderly {
      border-color: rgba(245, 158, 11, 0.3);
      background: rgba(245, 158, 11, 0.04);
      .demo-icon {
        background: rgba(245, 158, 11, 0.08);
        color: #d97706;
      }
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
    max-height: calc(100vh - 640px);
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
  
  .header-actions-left {
    display: flex;
    align-items: center;
  }
  
  .detail-tab-selector {
    display: flex;
    background: rgba(21, 105, 222, 0.08);
    padding: 2px;
    border-radius: 6px;
    border: 1px solid rgba(21, 105, 222, 0.15);
    gap: 2px;
    
    .tab-pill {
      padding: 3px 6px;
      font-size: 11px;
      font-weight: bold;
      color: #1569de;
      cursor: pointer;
      border-radius: 4px;
      transition: all 0.2s ease;
      user-select: none;
      white-space: nowrap;
      
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

/* Route Boarding & Alighting Analysis Panel Premium Styling */
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

  .boarding-alighting-header,
  .boarding-profile-header {
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
    height: 260px;
    width: 100%;
    position: relative;
    margin-bottom: 18px;
    
    .chart_box {
      width: 100%;
      height: 100%;
    }
    
    .boarding-alighting-bar-chart {
      width: 100%;
      height: 100%;
    }
  }

  .boarding-profile-chart-wrapper {
    height: 230px;
    width: 100%;
    position: relative;
    
    .chart_box,
    .boarding-profile-chart {
      width: 100%;
      height: 100%;
    }
  }
}

/* Route Transfer Analysis Panel Premium Styling */
.route-transfer-panel {
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

  .transfer-header {
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

  .transfer-scroll-wrapper {
    height: calc(100vh - 460px);
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

  .transfer-table {
    display: flex;
    flex-direction: column;
    width: 100%;
    
    .table-header {
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
      .col-name { flex: 1.4; }
      .col-station { flex: 1.1; }
      .col-flow { flex: 1.1; text-align: right; margin-right: 20px; }
      .col-ratio { flex: 1.3; }
    }
    
    .table-row {
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
      
      .col-name { 
        flex: 1.4; 
        display: flex; 
        align-items: center; 
        gap: 6px;
        font-weight: 600;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        
        .line-badge-icon {
          display: inline-flex;
          align-items: center;
          justify-content: center;
          width: 16px;
          height: 16px;
          border-radius: 50%;
          font-size: 9px;
          font-weight: 800;
          color: #ffffff;
          
          &.metro {
            background: #e11d48;
          }
          &.bus {
            background: #0f9f6e;
          }
        }
      }
      
      .col-station {
        flex: 1.1;
        color: #4b5563;
        font-weight: 600;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }
      
      .col-flow { 
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
          background: #0b91b7;
        }
        .ratio-text {
          font-size: 11px;
          font-weight: 600;
          color: #64748b;
          width: 38px;
          text-align: right;
        }
      }
    }
  }

  .transfer-chart-wrapper {
    height: calc(100vh - 460px);
    width: 100%;
    position: relative;
    
    .chart_box {
      width: 100%;
      height: 100%;
    }
    
    .transfer-bar-chart {
      width: 100%;
      height: 100%;
    }
  }
}

/* Route Segment Analysis Panel Premium Styling */
.route-segments-panel {
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
      margin-bottom: 8px;
      
      .title {
        font-size: 13px;
        font-weight: bold;
        color: #12304f;
      }
      
      .range-text {
        font-size: 13px;
        font-weight: bold;
        color: #1569de;
        font-family: var(--app-font-number);
      }
    }
    
    .time-range-slider {
      width: 96%;
      margin: 0 auto;
      :deep(.el-slider__bar) {
        background-color: #1569de;
      }
      :deep(.el-slider__button) {
        border-color: #1569de;
      }
    }
  }
  
  .segments-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 12px;
    
    .section-title {
      font-size: 14px;
      font-weight: bold;
      color: #12304f;
    }
    
    .view-mode-group {
      :deep(.el-radio-button__inner) {
        font-size: 11px;
        font-weight: 600;
        padding: 5px 12px;
      }
    }
  }
  
  .segments-scroll-wrapper {
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
  
  .segments-table {
    display: flex;
    flex-direction: column;
    width: 100%;
    
    .table-header {
      display: flex;
      background: rgba(21, 105, 222, 0.05);
      border-radius: 4px;
      padding: 10px;
      font-size: 12px;
      font-weight: bold;
      color: #60758e;
      border-bottom: 1.5px solid rgba(21, 105, 222, 0.15);
      margin-bottom: 6px;
    }
    
    .table-row {
      display: flex;
      align-items: center;
      padding: 12px 10px;
      border-bottom: 1px dashed rgba(21, 105, 222, 0.12);
      font-size: 13px;
      transition: all 0.2s ease;
      
      &:hover {
        background: rgba(21, 105, 222, 0.02);
      }
      
      &:last-child {
        border-bottom: none;
      }
    }
    
    .col-name {
      flex-grow: 1;
      font-weight: 600;
      color: #2d3748;
      min-width: 0;
      padding-right: 12px;
    }
    
    .col-flow {
      width: 95px;
      flex-shrink: 0;
      text-align: right;
      font-family: var(--app-font-number);
      font-weight: 600;
      color: #60758e;
      padding-right: 12px;
    }
    
    .col-load {
      width: 65px;
      flex-shrink: 0;
      text-align: right;
      
      .load-indicator {
        font-family: var(--app-font-number);
        font-weight: bold;
        padding: 2px 6px;
        border-radius: 4px;
        font-size: 11px;
        
        &.high {
          background: rgba(239, 68, 68, 0.1);
          color: #dc4c5d;
        }
        &.medium {
          background: rgba(245, 158, 11, 0.1);
          color: #d97706;
        }
        &.low {
          background: rgba(16, 185, 129, 0.1);
          color: #0f9f6e;
        }
      }
    }
  }
  
  .segments-chart-wrapper {
    height: calc(100vh - 460px);
    width: 100%;
    position: relative;
    
    .chart_box {
      width: 100%;
      height: 100%;
    }
    
    .segments-bar-chart {
      width: 100%;
      height: 100%;
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
</style>
