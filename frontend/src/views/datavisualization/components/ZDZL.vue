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
                v-for="tab in [
                  {value: 'overview', label: '站点数据分析'},
                  {value: 'boardingAlighting', label: '站点乘降分析'},
                  {value: 'od', label: '站点OD分析'},
                  {value: 'reachability', label: '站点可达分析'}
                ]"
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
          <div v-if="['boarding', 'od'].includes(pfaStationSection)" class="time-range-section">
            <div class="time-range-header">
              <span class="title">统计时段选择</span>
              <span class="range-text">{{ formatHourLabel(segmentTimeRange[0]) }} - {{ formatHourLabel(segmentTimeRange[1]) }}</span>
            </div>
            <el-slider v-model="segmentTimeRange" range :min="6" :max="22" :step="1" :show-tooltip="false" class="time-range-slider" />
          </div>

          <section v-if="pfaStationSection === 'boarding'" class="pfa-section">
            <div class="section-header">
              <span class="section-title">站点乘降客流</span>
              <span class="pfa-section-meta">上车 {{ stationBoardingSummary.boarding }} · 下车 {{ stationBoardingSummary.alighting }}</span>
            </div>
            <div class="chart-container-wrapper">
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
          </section>

          <section v-else-if="pfaStationSection === 'od'" class="pfa-section">
            <div class="section-header">
              <span class="section-title">客流OD</span>
              <div class="chart-type-selector">
                <div
                  v-for="mode in [{value: 'table', label: '表格'}, {value: 'chart', label: '图表'}]"
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
                  <span class="col-od-route">线路方向</span>
                  <span class="col-od-flow text-right">客流量</span>
                </div>
                <div class="transfer-table-body">
                  <div v-for="(item, idx) in odTableData" :key="`${item.routeId || 'route'}-${idx}`" class="transfer-table-row">
                    <span class="col-od-route text-ellipsis">
                      <strong>{{ item.lineName || '未知线路' }}</strong>
                      <small>{{ item.routeDesc || item.routeName || '方向未识别' }}</small>
                    </span>
                    <span class="col-od-flow text-right bold">{{ item.flow.toLocaleString() }} <small>人次</small></span>
                  </div>
                  <div v-if="!odTableData.length" class="pfa-empty">暂无OD客流数据</div>
                </div>
              </div>
            </div>
            <div v-else class="chart-container-wrapper">
              <el-auto-resizer class="chart_box">
                <template #default="{ height, width }">
                  <VChart
                    v-if="width > 0 && height > 0"
                    class="od-bar-chart"
                    :option="odChartOption"
                    autoresize
                    :update-options="{ notMerge: true }"
                  />
                </template>
              </el-auto-resizer>
            </div>
          </section>

          <section v-else-if="pfaStationSection === 'demographics'" class="pfa-section">
            <div class="section-header">
              <span class="section-title">客流画像</span>
              <span v-if="stationDemographicsRiderCount" class="pfa-section-meta">样本 {{ stationDemographicsRiderCount.toLocaleString() }} 人</span>
            </div>
            <div class="demo-groups">
              <div v-for="g in stationDemographicsGroups" :key="g.key" class="demo-group">
                <div class="demo-group-head">
                  <span class="demo-group-title">{{ g.title }}</span>
                  <span class="demo-group-sum">合计 100%</span>
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

          <section v-else-if="pfaStationSection === 'reachability'" class="pfa-section">
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
                    <span class="line-badge" :class="inferStationType(item, item) === 'subway' ? 'subway-badge' : 'bus-badge'">
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
              :max="22"
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
                  :update-options="{ notMerge: true }"
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
              :max="22"
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
                v-for="mode in [{value: 'table', label: '数据表格'}, {value: 'chart', label: '可视化图表'}]"
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
                <div v-for="(item, idx) in odTableData" :key="idx" class="transfer-table-row">
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
                  :update-options="{ notMerge: true }"
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
                  :update-options="{ notMerge: true }"
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
          <span class="col-name">站点名称</span>
          <span class="col-flow">日均客流量</span>
        </div>
        <div class="ranking-scroll-list">
          <button
            v-for="(item, index) in currentLeaderboard"
            :key="index"
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
import { Location, Download } from "@element-plus/icons-vue";
import { getLineAll } from "@/api/route";
import { getStationPanel } from "@/api/facility";
import MCard from "./MCard.vue";
import MCard2 from "./MCard2.vue";
import { StationLayer } from "../layers/StationLayer.js";
import { injectSync } from "@/utils";
import { webMercatorToLngLat } from "@/mymap/index.js";

const props = defineProps({
  model: String,
});

const loading = ref(true);
const rawLines = ref([]);
const stationPanelData = ref(null);
let stationPanelPromise = null;
let stationPanelRetryTimer = null;
let stationPanelRetryCount = 0;

const selectedStationName = ref("");
const selectedStationFacilityId = ref("");
const selectedStationCoord = ref(null);
const matchedRoutes = ref([]);
const allMapStations = ref([]);

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

// 监听当前选中的站点，控制右侧面板内容状态
watch(selectedStationName, (newStation) => {
  if (activeDatavisualizationTab.value === "站点客流监测") {
    rightPanelHasContent.value = true;
  }
  if (!newStation) {
    cleanUpSelectedStationRing();
    cleanUpReachabilityOverlay();
    restoreReachabilityStationFilter();
  }
});

watch(activeDatavisualizationTab, (newTab) => {
  if (newTab === "站点客流监测") {
    rightPanelHasContent.value = true;
  } else {
    cleanUpSelectedStationRing();
    cleanUpReachabilityOverlay();
    restoreReachabilityStationFilter();
  }
});

function toFiniteNumber(value, fallback = 0) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function hourSlice(values, startHour = 6, endHour = 22) {
  const source = Array.isArray(values) ? values : [];
  const result = [];
  for (let hour = startHour; hour <= endHour; hour++) {
    result.push(toFiniteNumber(source[hour], 0));
  }
  return result;
}

const currentStationPanel = computed(() => {
  const stationName = selectedStationName.value;
  const stations = stationPanelData.value?.stations || {};
  if (!stationName) return null;
  if (stations[stationName]) return stations[stationName];
  const target = normalizeStationSearchName(stationName);
  const matchedKey = Object.keys(stations).find((key) => normalizeStationSearchName(key) === target);
  if (matchedKey) return stations[matchedKey];
  const facilityId = String(selectedStationFacilityId.value || "");
  if (facilityId) {
    return Object.values(stations).find((station) =>
      Array.isArray(station?.facilityIds)
      && station.facilityIds.some((id) => String(id || "") === facilityId)
    ) || null;
  }
  return null;
});

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
  const startHour = segmentTimeRange.value[0];
  const endHour = segmentTimeRange.value[1];
  const boarding = hourSlice(currentStationPanel.value?.boardingByHour, startHour, endHour)
    .reduce((sum, value) => sum + value, 0);
  const alighting = hourSlice(currentStationPanel.value?.alightingByHour, startHour, endHour)
    .reduce((sum, value) => sum + value, 0);
  return {
    boarding: boarding.toLocaleString(),
    alighting: alighting.toLocaleString(),
  };
});

const STATION_DEMO_GROUPS = [
  {
    key: "purpose",
    title: "出行目的",
    items: [
      { key: "commuter", label: "通勤", color: "#0071e3" },
      { key: "shopping", label: "购物", color: "#7c3aed" },
      { key: "leisure", label: "休闲", color: "#1a8a3f" },
    ],
  },
  {
    key: "attribute",
    title: "出行者属性",
    items: [
      { key: "student", label: "学生", color: "#2f75d6" },
      { key: "elderly", label: "老人", color: "#b06a00" },
    ],
  },
];
const STATION_DEMO_OTHER = { label: "其他", color: "#94a3b8" };

function normalizeDisplayPercents(items) {
  const result = items.map((item) => ({
    ...item,
    value: Math.max(0, Math.min(100, toFiniteNumber(item.value, 0))),
  }));
  const tenths = result.map((item) => Math.round(item.value * 10));
  let delta = 1000 - tenths.reduce((sum, value) => sum + value, 0);
  while (delta !== 0) {
    if (delta > 0) {
      tenths[tenths.length - 1] += 1;
      delta -= 1;
      continue;
    }
    const index = tenths.reduce((best, value, current) => (value > tenths[best] ? current : best), 0);
    if (tenths[index] <= 0) break;
    tenths[index] -= 1;
    delta += 1;
  }
  return result.map((item, index) => ({ ...item, value: tenths[index] / 10 }));
}

const stationDemographicsGroups = computed(() => {
  const demo = currentStationPanel.value?.demographics || {};
  if (toFiniteNumber(demo.riderCount, 0) <= 0) return [];
  return STATION_DEMO_GROUPS.map((group) => {
    let items = group.items
      .filter((item) => Object.prototype.hasOwnProperty.call(demo, item.key))
      .map((item) => ({ ...item, value: Math.max(0, Math.min(100, toFiniteNumber(demo[item.key], 0))) }));
    let known = items.reduce((sum, item) => sum + item.value, 0);
    if (known > 100) {
      items = items.map((item) => ({ ...item, value: (item.value * 100) / known }));
      known = 100;
    }
    items.push({ ...STATION_DEMO_OTHER, key: `${group.key}-other`, value: Math.max(0, 100 - known) });
    return { key: group.key, title: group.title, items: normalizeDisplayPercents(items) };
  });
});
const stationDemographicsRiderCount = computed(() =>
  toFiniteNumber(currentStationPanel.value?.demographics?.riderCount, 0)
);

const { proxy } = getCurrentInstance() || {};
const activeChartType = ref("line");

const passengerFlowChartOption = computed(() => {
  const isLine = activeChartType.value === "line";
  const hours = Array.from({ length: 17 }, (_, index) => formatHourRangeLabel(index + 6));
  const data = hourSlice(currentStationPanel.value?.hourlyFlow, 6, 22);

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
  // 运行监测由 index.vue 统一绘制模型站点和数据管理同款选中图标。
  if (!runMonitorSimplifiedRight) {
    map.value?.addLayer(_StationLayer);
  }
  nextTick(renderReachabilityOverlay);
});

watch(StationSizeRef, (value) => {
  _StationLayer.setMarkerSize(value);
});
watch(BaseMapLineModeRef, () => {
  _StationLayer.hide();
}, { immediate: true });

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
  return uniqueNames
    .map(name => ({ value: name, label: name }))
    .filter((option) => runMonitorStationOptionFilter(option));
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
const runMonitorSelectedStationName = inject("runMonitorSelectedStationName", null);
if (runMonitorSelectedStationPanel || runMonitorSelectedStationName) {
  watch([currentStationPanel, selectedStationName], () => {
    if (runMonitorSelectedStationPanel) {
      runMonitorSelectedStationPanel.value = currentStationPanel.value || null;
    }
    if (runMonitorSelectedStationName) {
      runMonitorSelectedStationName.value = selectedStationName.value || "";
    }
  }, { immediate: true });
  onUnmounted(() => {
    if (runMonitorSelectedStationPanel) runMonitorSelectedStationPanel.value = null;
    if (runMonitorSelectedStationName) runMonitorSelectedStationName.value = "";
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

const stationCoordCandidates = computed(() => {
  const buckets = new Map();
  rawLines.value.forEach((line) => {
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
});

const stationCoordIndex = computed(() => {
  const result = new Map();
  stationCoordCandidates.value.forEach((stations, name) => {
    if (stations.length) result.set(name, stations[0]);
  });
  return result;
});

const stationNetworkTopology = computed(() => {
  const nodes = new Map();
  const nodeToRoutes = new Map();
  const routeToNodeList = new Map();
  const nameToNodes = new Map();
  const facilityToNode = new Map();

  rawLines.value.forEach((line) => {
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
    const sortedNames = Array.from(node.names).sort((a, b) => a.localeCompare(b, "zh-CN"));
    node.names = sortedNames;
    node.facilityIds = Array.from(node.facilityIds);
    node.name = sortedNames[0] || node.name;
    node.label = node.name;
  });

  return { nodes, nodeToRoutes, routeToNodeList, nameToNodes, facilityToNode };
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
    .sort((a, b) => a.label.localeCompare(b.label, "zh-CN") || a.key.localeCompare(b.key));
}

const localReachabilityData = computed(() => {
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
const REACHABILITY_SHADOW_LAYER_ID = "station-reachability-shadow";
const REACHABILITY_LAYER_PREFIX = "station-reachability-line";
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

function stationRangeOption(station) {
  const name = station?.name || station?.label || "";
  const x = Number(station?.x ?? station?.coord?.x);
  const y = Number(station?.y ?? station?.coord?.y);
  const coord = Number.isFinite(x) && Number.isFinite(y) ? { x, y } : station?.coord || null;
  const facilityIds = Array.isArray(station?.facilityIds) ? station.facilityIds : [];
  return {
    value: name,
    label: name,
    facilityId: String(station?.facilityId || facilityIds[0] || ""),
    coord,
  };
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
  return stations.slice(0, 1);
});

const reachabilityVisibleStations = computed(() => {
  const showReachability = shouldRenderPfaRightPanel.value && pfaStationSection.value === "reachability" && selectedStationName.value;
  if (!showReachability) {
    if (shouldRenderPfaRightPanel.value && selectedStationName.value) {
      return selectedOnlyStations.value;
    }
    return displayRangeStations.value;
  }
  const originKey = localReachabilityData.value.originKey || stationCoordKeyFromStation(reachabilityOriginStation());
  const visibleKeys = new Set(originKey ? [originKey] : []);
  reachabilityGroups.value.forEach((group) => {
    (Array.isArray(group.mapStations) ? group.mapStations : group.stations).forEach((station) => {
      const key = reachabilityStationKey(station);
      if (key) visibleKeys.add(key);
    });
  });
  return displayRangeStations.value.filter((station) => visibleKeys.has(station.key || station.coordKey || stationCoordKeyFromStation(station)));
});

function setRunMonitorStationSource(stations) {
  const source = MapRef.value?.map?.getSource(RM_SOURCE_STATIONS);
  if (!source?.setData) return;
  source.setData(stationsFeatureCollection(stations));
}

function applyReachabilityStationFilter() {
  const stations = reachabilityVisibleStations.value;
  if (!runMonitorSimplifiedRight) {
    _StationLayer.setData(stations);
  }
  setRunMonitorStationSource(stations);
}

function restoreReachabilityStationFilter() {
  const stations = reachabilityVisibleStations.value;
  if (!stations.length && !allMapStations.value.length) return;
  if (!runMonitorSimplifiedRight) {
    _StationLayer.setData(stations);
  }
  setRunMonitorStationSource(stations);
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
  addMapLayer(map, {
    id: REACHABILITY_SHADOW_LAYER_ID,
    type: "line",
    source: REACHABILITY_SOURCE_ID,
    paint: {
      "line-color": "rgba(248, 251, 255, 0.86)",
      "line-width": [
        "interpolate",
        ["linear"],
        ["zoom"],
        9, 1.8,
        13, 3.8,
        16, 6.2
      ],
      "line-opacity": 0.62,
      "line-blur": 1.1,
    },
    layout: {
      "line-cap": "round",
      "line-join": "round",
    },
  });
  reachabilityGroups.value.forEach((group) => {
    addMapLayer(map, {
      id: `${REACHABILITY_LAYER_PREFIX}-${group.key}`,
      type: "line",
      source: REACHABILITY_SOURCE_ID,
      filter: ["==", ["get", "level"], group.key],
      paint: {
        "line-color": group.color,
        "line-width": [
          "interpolate",
          ["linear"],
          ["zoom"],
          9, 1,
          13, 2.2,
          16, 3.6
        ],
        "line-opacity": [
          "interpolate",
          ["linear"],
          ["zoom"],
          9, 0.28,
          13, 0.58,
          16, 0.78
        ],
        "line-blur": 0.15,
      },
      layout: {
        "line-cap": "round",
        "line-join": "round",
      },
    });
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
      features.push({
        type: "Feature",
        geometry: {
          type: "LineString",
          coordinates: [origin, target],
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
}

function cleanUpReachabilityOverlay() {
  const map = MapRef.value?.map;
  restoreReachabilityStationFilter();
  if (!map) return;
  ["direct", "transfer1", "transfer2"].forEach((key) => {
    const layerId = `${REACHABILITY_LAYER_PREFIX}-${key}`;
    if (map.getLayer(layerId)) map.removeLayer(layerId);
  });
  if (map.getLayer(REACHABILITY_SHADOW_LAYER_ID)) map.removeLayer(REACHABILITY_SHADOW_LAYER_ID);
  if (map.getSource(REACHABILITY_SOURCE_ID)) map.removeSource(REACHABILITY_SOURCE_ID);
}

function normalizeStationSearchName(value = "") {
  return String(value || "")
    .trim()
    .replace(/\s+/g, "")
    .toLowerCase();
}

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
  selectedStationFacilityId.value = "";
  selectedStationName.value = option.value;
  await nextTick();
  handleStationChange(option.value);
  return true;
}

async function selectStationByFeature(props = {}) {
  const stationName = props.facilityName || props.stop_name || props.station_name || props.name || "";
  if (!stationName) return false;
  const facilityId = String(props.facilityId || props.stop_id || props._stationKey || "");
  selectedStationFacilityId.value = facilityId;
  selectedStationName.value = stationName;
  await nextTick();
  handleStationChange(stationName, facilityId);
  return true;
}

function selectLeaderboardStation(item) {
  selectStationByName(item?.stationName);
}

// 切换站点时
function handleStationChange(stationName, facilityId = "") {
  if (!stationName) {
    selectedStationFacilityId.value = "";
    selectedStationCoord.value = null;
    matchedRoutes.value = [];
    cleanUpSelectedStationRing();
    cleanUpReachabilityOverlay();
    return;
  }

  selectedStationFacilityId.value = String(facilityId || "");

  const matches = [];
  let stationCoord = null;
  let exactStationCoord = null;
  const matchPhysicalStation = shouldRenderPfaRightPanel.value;

  rawLines.value.forEach(line => {
    if (line.routes) {
      line.routes.forEach(route => {
        if (route.facilities) {
          const matchedFac = route.facilities.find((fac) => {
            if (!runMonitorStationOptionFilter({
              value: fac.facilityName,
              label: fac.facilityName,
              facilityId: fac.facilityId,
              coord: fac.coord,
            })) {
              return false;
            }
            const sameFacility = selectedStationFacilityId.value && String(fac.facilityId || "") === selectedStationFacilityId.value;
            if (sameFacility) {
              return true;
            }
            return (!selectedStationFacilityId.value || matchPhysicalStation) && fac.facilityName === stationName;
          });
          if (matchedFac) {
            if (selectedStationFacilityId.value && String(matchedFac.facilityId || "") === selectedStationFacilityId.value && matchedFac.coord) {
              exactStationCoord = matchedFac.coord;
            }
            if (!stationCoord && matchedFac.coord) {
              stationCoord = matchedFac.coord;
            }
            matches.push({
              lineId: line.lineId,
              lineName: line.lineName,
              routeId: route.routeId,
              routeName: route.routeName,
              info: route.info,
              links: route.links,
              facilities: route.facilities,
              stationCoord: matchedFac.coord,
            });
          }
        }
      });
    }
  });

  matchedRoutes.value = matches;
  selectedStationCoord.value = exactStationCoord || stationCoord || stationCoordIndex.value.get(stationName) || null;
  if ((runMonitorSimplifiedRight || shouldRenderPfaRightPanel.value) && !stationPanelData.value) {
    ensureStationPanelData();
  }

  const displayCoord = selectedStationCoord.value;

  if (displayCoord && MapRef.value && (!runMonitorSimplifiedRight || shouldRenderPfaRightPanel.value)) {
    MapRef.value.setCenter([displayCoord.x, displayCoord.y]);
    MapRef.value.setZoom(15.5);
    if (shouldRenderPfaRightPanel.value) {
      updateSelectedStationRing(displayCoord);
    } else {
      cleanUpSelectedStationRing();
    }
  }
  nextTick(() => {
    renderReachabilityOverlay();
    if (shouldRenderPfaRightPanel.value) {
      applyReachabilityStationFilter();
    }
  });
}

function clearStationPanelRetry() {
  if (stationPanelRetryTimer) {
    clearTimeout(stationPanelRetryTimer);
    stationPanelRetryTimer = null;
  }
}

function scheduleStationPanelRetry(model) {
  if (!model || props.model !== model || stationPanelData.value || stationPanelRetryTimer) return;
  if (stationPanelRetryCount >= 120) return;
  stationPanelRetryCount += 1;
  const delay = Math.min(10_000, 2_000 + stationPanelRetryCount * 500);
  stationPanelRetryTimer = setTimeout(() => {
    stationPanelRetryTimer = null;
    if (props.model === model && !stationPanelData.value) {
      ensureStationPanelData();
    }
  }, delay);
}

function ensureStationPanelData() {
  if (stationPanelData.value) return Promise.resolve(stationPanelData.value);
  if (stationPanelPromise) return stationPanelPromise;
  const model = props.model;
  stationPanelPromise = getStationPanel({ datasource: model }, { silentError: true })
    .then((res) => {
      const data = res.data || null;
      if (props.model === model && data?.stations) {
        stationPanelData.value = data;
        clearStationPanelRetry();
        stationPanelRetryCount = 0;
      } else if (props.model === model) {
        scheduleStationPanelRetry(model);
      }
      return stationPanelData.value;
    })
    .catch(() => {
      scheduleStationPanelRetry(model);
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
  stationPanelData.value = null;
  allMapStations.value = [];
  clearStationPanelRetry();
  stationPanelRetryCount = 0;
  if (!runMonitorSimplifiedRight || shouldRenderPfaRightPanel.value) ensureStationPanelData();
  try {
      const lineRes = await getLineAll({ datasource: model });
      if (props.model !== model) return;
      const data = lineRes.data || [];
      rawLines.value = data;

      // 提取唯一的站点用于地图打点渲染 (按坐标去重)
      const stationsList = [];
      const coordsSet = new Set();
      const stationByCoord = new Map();
      data.forEach((line) => {
        if (line.routes) {
          line.routes.forEach((route) => {
            if (route.facilities) {
              route.facilities.forEach((fac) => {
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
                    stationsList.push(station);
                  } else if (type === "subway") {
                    const station = stationByCoord.get(key);
                    if (station) station.type = "subway";
                  }
                }
              });
            }
          });
        }
      });

      allMapStations.value = stationsList;
      if (!runMonitorSimplifiedRight) {
        _StationLayer.setData(reachabilityVisibleStations.value);
        if (BaseMapLineModeRef.value === "bus-network") {
          _StationLayer.hide();
        }
      }
      nextTick(() => {
        renderReachabilityOverlay();
        applyReachabilityStationFilter();
      });
  } catch {
    if (props.model === model) rawLines.value = [];
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

function handleExportLeaderboard() {
  if (proxy?.$message) {
    proxy.$message.success({
      message: "客流排行榜数据已成功导出为 Excel！",
      type: "success",
      duration: 2000
    });
  }
}

const activeDetailTab = ref("overview");
const segmentTimeRange = ref([8, 18]);
const odViewMode = ref("table");

function formatHourLabel(hour) {
  return `${hour.toString().padStart(2, "0")}:00`;
}

function formatHourRangeLabel(hour) {
  return `${formatHourLabel(hour)}-${formatHourLabel(hour + 1)}`;
}

function handleExportDetail() {
  if (proxy?.$message) {
    const activeSection = shouldRenderPfaRightPanel.value ? pfaStationSection.value : activeDetailTab.value;
    const text = activeSection === "overview"
      ? "站点数据分析"
      : activeSection === "boardingAlighting" || activeSection === "boarding"
        ? "站点乘降分析"
        : activeSection === "od"
          ? "站点OD分析"
          : activeSection === "demographics"
            ? "客流画像"
            : "站点可达分析";
    proxy.$message.success({
      message: `${text}数据已成功导出！`,
      type: "success",
      duration: 2000
    });
  }
}

// 站点乘降分析
const boardingAlightingChartOption = computed(() => {
  const startHour = segmentTimeRange.value[0];
  const endHour = segmentTimeRange.value[1];

  const hours = [];
  const boardingData = [];
  const alightingData = [];
  const boardingByHour = currentStationPanel.value?.boardingByHour || [];
  const alightingByHour = currentStationPanel.value?.alightingByHour || [];

  for (let hour = startHour; hour <= endHour; hour++) {
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
  const startHour = segmentTimeRange.value[0];
  const endHour = segmentTimeRange.value[1];

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

const odChartOption = computed(() => {
  const chartRows = odTableData.value.slice().reverse();
  const labels = chartRows.map(d => d.chartLabel);
  const flows = chartRows.map(d => d.flow);

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
        overflow: "truncate"
      }
    },
    series: [
      {
        name: "出行量",
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

// 可达性分析
const reachabilityData = computed(() => {
  const reachability = currentStationPanel.value?.reachability || {};
  const local = localReachabilityData.value;
  const localReady = local.ready;
  const limitStations = (stations) => stations.slice(0, toFiniteNumber(reachability.stationListLimit, 80));
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

watch(
  () => [
    selectedStationName.value,
    pfaStationSection.value,
    currentStationPanel.value,
    localReachabilityData.value,
    stationCoordIndex.value,
    selectedStationCoord.value,
    allMapStations.value,
    displayRangeStations.value,
    shouldRenderPfaRightPanel.value,
  ],
  () => {
    nextTick(() => {
      renderReachabilityOverlay();
      applyReachabilityStationFilter();
      if (shouldRenderPfaRightPanel.value && selectedStationName.value) {
        const coord = reachabilityOriginStation();
        if (coord) updateSelectedStationRing(coord);
      } else {
        cleanUpSelectedStationRing();
      }
    });
  },
  { immediate: false }
);

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
    allMapStations.value = [];
    selectedStationName.value = "";
    selectedStationFacilityId.value = "";
    selectedStationCoord.value = null;
    matchedRoutes.value = [];
    cleanUpSelectedStationRing();
    cleanUpReachabilityOverlay();
    loadAllData();
  }
});

onUnmounted(() => {
  clearStationPanelRetry();
  _StationLayer.dispose();
  cleanUpSelectedStationRing();
  cleanUpReachabilityOverlay();
  rightPanelHasContent.value = false;
});

// 取消选中：清空选中站点与地图高亮圈（供 index.vue 点击空白处调用）
function clearSelection() {
  selectedStationName.value = "";
  selectedStationFacilityId.value = "";
  selectedStationCoord.value = null;
  matchedRoutes.value = [];
  cleanUpSelectedStationRing();
  cleanUpReachabilityOverlay();
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
</style>
