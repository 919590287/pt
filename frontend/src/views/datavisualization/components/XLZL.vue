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
                  :key="routeUniqueKey(item)"
                  :class="['matched-item', activeMatchedRouteId === routeUniqueKey(item) ? 'active' : '']"
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

  <teleport v-if="!runMonitorSimplifiedRight || pfaRightPanel" to="#datavisualization_index_box2" defer>
    <MCard2 v-if="(!runMonitorSimplifiedRight || pfaRightPanel) && currentSelectedRoute" class="SJZL_right_card pfa-route-card" :open="true">
      <template #title>
        <div class="ranking-title-container">
          <div class="header-actions-left">
            <div class="pfa-route-heading">
              <span class="pfa-route-name">{{ currentSelectedRoute.lineName || currentSelectedRoute.info?.lineName || '线路客流分析' }}</span>
              <span class="pfa-route-sub">{{ routeMetrics.stationCount }} · 全长 {{ routeMetrics.length }}</span>
            </div>
          </div>
          <div class="header-actions">
            <el-button type="primary" size="small" class="export-btn" @click.stop="handleExportDetail">
              <el-icon style="margin-right: 4px;"><Download /></el-icon>
              导出
            </el-button>
          </div>
        </div>
      </template>
      <template #body>
        <div class="pfa-route-sections">
          <!-- 统计时段（仅断面 / 乘降 / 关联换乘按时段统计）-->
          <div v-if="['segments', 'boarding', 'transfer'].includes(pfaLineSection)" class="time-range-section">
            <div class="time-range-header">
              <span class="title">统计时段选择</span>
              <span class="range-text">{{ formatHourLabel(segmentTimeRange[0]) }} - {{ formatHourLabel(segmentTimeRange[1]) }}</span>
            </div>
            <el-slider v-model="segmentTimeRange" range :min="6" :max="22" :step="1" :show-tooltip="false" class="time-range-slider" />
          </div>

          <!-- ① 线路断面客流与满载率 -->
          <section v-if="pfaLineSection === 'segments'" class="pfa-section">
            <div class="section-header">
              <span class="section-title">线路断面客流与满载率</span>
            </div>
            <div class="segments-table">
              <div class="table-header">
                <span class="col-name">断面（相邻站点）</span>
                <span class="col-flow">客流量</span>
                <span class="col-load">满载率</span>
              </div>
              <div class="table-body">
                <div v-for="(seg, idx) in routeSegments" :key="idx" class="table-row">
                  <span class="col-name">{{ seg.name }}</span>
                  <span class="col-flow">{{ seg.flow.toLocaleString() }}</span>
                  <span class="col-load">
                    <span :class="['load-indicator', seg.loadRate >= 70 ? 'high' : seg.loadRate >= 45 ? 'medium' : 'low']">{{ seg.loadRate }}%</span>
                  </span>
                </div>
                <div v-if="!routeSegments.length" class="pfa-empty">暂无断面数据</div>
              </div>
            </div>
          </section>

          <!-- ② 站点分时段乘降（折线 / 柱状可切换） -->
          <section v-else-if="pfaLineSection === 'boarding'" class="pfa-section">
            <div class="section-header">
              <span class="section-title">站点乘降客流（按所选时段）</span>
              <div class="chart-type-selector">
                <div
                  v-for="type in ['line', 'bar']"
                  :key="type"
                  :class="['type-pill', boardingChartType === type ? 'active' : '']"
                  @click="boardingChartType = type"
                >
                  {{ type === 'line' ? '折线图' : '柱状图' }}
                </div>
              </div>
            </div>
            <div class="chart-container-wrapper" :class="{ 'is-split': isStationSplit }">
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

          <!-- ③ 运营效益 -->
          <section v-else-if="pfaLineSection === 'efficiency'" class="pfa-section">
            <div class="section-header">
              <span class="section-title">运营效益</span>
            </div>
            <div class="efficiency-grid">
              <div class="eff-card">
                <span class="eff-label">日客流量</span>
                <span class="eff-value">{{ operationStats.dailyFlow }}</span>
              </div>
              <div class="eff-card">
                <span class="eff-label">日发车班次</span>
                <span class="eff-value">{{ operationStats.departures }}</span>
              </div>
              <div class="eff-card">
                <span class="eff-label">车辆数</span>
                <span class="eff-value">{{ operationStats.vehicles }}</span>
              </div>
              <div class="eff-card">
                <span class="eff-label">单班次客流</span>
                <span class="eff-value">{{ operationStats.perTrip }}</span>
              </div>
              <div class="eff-card">
                <span class="eff-label">车日均客流量</span>
                <span class="eff-value">{{ operationStats.perVehicle }}</span>
              </div>
            </div>
          </section>

          <!-- ④ 客流画像 -->
          <section v-else-if="pfaLineSection === 'demographics'" class="pfa-section">
            <div class="section-header">
              <span class="section-title">客流画像</span>
              <span v-if="demographicsRiderCount" class="pfa-section-meta">样本 {{ demographicsRiderCount.toLocaleString() }} 人</span>
            </div>
            <div class="demo-groups">
              <div v-for="g in demographicsGroups" :key="g.key" class="demo-group">
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
              <div v-if="!demographicsGroups.length" class="pfa-empty">暂无客流画像数据</div>
            </div>
          </section>

          <!-- ⑤ 关联线路分析（直接换乘） -->
          <section v-else-if="pfaLineSection === 'transfer'" class="pfa-section">
            <div class="section-header">
              <span class="section-title">关联线路分析（直接换乘）</span>
            </div>
            <div class="transfer-chart-wrapper">
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
            <div v-if="!transferTableData.length" class="pfa-empty">暂无换乘关联数据</div>
          </section>
        </div>
      </template>
    </MCard2>

    <div v-else-if="!runMonitorSimplifiedRight && !pfaRightPanel" class="rm-right-card rm-ranking-card">
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
import { ref, onMounted, onUnmounted, watch, inject, computed, getCurrentInstance, nextTick, unref } from "vue";
import { Search, Location, Timer, Connection, Download } from "@element-plus/icons-vue";
import { getLineAll, getRouteDetail, getRoutePanel, getRoutePanelDetail, getRouteTileBinary } from "@/api/route";
import MCard from "./MCard.vue";
import MCard2 from "./MCard2.vue";
import { RouteLayer } from "../layers/RouteLayer.js";
import { emptyFeatureCollection, stationsToFeatureCollection } from "../layers/maplibreLayerUtils.js";
import { buildPassengerProfileGroups, passengerProfileRiderCount } from "../utils/passengerProfile.js";
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
const runMonitorSimplifiedRight = inject("runMonitorSimplifiedRight", false);
// 客流分析模式：即使简化（地图/选中复用运行监测），也渲染完整 MCard2 面板
const pfaRightPanel = inject("pfaRightPanel", ref(false));
// 客流分析：当前激活的子功能（右侧只显示对应统计）segments/boarding/efficiency/demographics/transfer
const pfaLineSection = inject("pfaLineSection", ref("segments"));
const runMonitorSelectedLinePanel = inject("runMonitorSelectedLinePanel", null);
const runMonitorSelectedLineName = inject("runMonitorSelectedLineName", null);
const runMonitorSelectedRouteDetail = inject("runMonitorSelectedRouteDetail", null);
const runMonitorSelectedRouteMapLinks = inject("runMonitorSelectedRouteMapLinks", null);
const runMonitorLineOptionFilter = inject("runMonitorLineOptionFilter", () => true);
const runMonitorStationOptionFilter = inject("runMonitorStationOptionFilter", () => true);
const shouldRenderPfaRightPanel = computed(() => Boolean(unref(pfaRightPanel)));
const shouldLoadSelectedRoutePanel = computed(() => runMonitorSimplifiedRight || shouldRenderPfaRightPanel.value);

// 统一的当前选中路线计算属性
const currentSelectedRoute = computed(() => {
  const targetId = searchMode.value === "line" ? activeRouteId.value : activeMatchedRouteId.value;
  if (!targetId) return null;
  if (selectedRouteDetail.value && routeMatchesKey(selectedRouteDetail.value, targetId)) return selectedRouteDetail.value;
  if (routeDetailCache.has(String(targetId))) return routeDetailCache.get(String(targetId));
  if (searchMode.value === "station") {
    const matched = matchedRoutes.value.find((route) => routeUniqueKey(route) === String(targetId));
    if (matched) return matched;
  }
  if (searchMode.value === "line" && selectedLineName.value) {
    const groupRoute = buildLineGroupRoute(selectedLineName.value);
    if (groupRoute && routeMatchesKey(groupRoute, targetId)) return groupRoute;
    for (const line of linesForDisplayName(selectedLineName.value)) {
      const match = (line?.routes || []).find((route) => String(route.routeId) === String(targetId));
      if (match) return withLineMeta(match, line);
    }
  }
  for (const line of rawLines.value) {
    if (line.routes) {
      const match = line.routes.find(r => String(r.routeId) === String(targetId));
      if (match) return withLineMeta(match, line);
    }
  }
  return null;
});

// 站点数超过该阈值时，乘降客流图拆成上下两行展示，避免横轴站名糊在一起
const STATION_SPLIT_THRESHOLD = 18;
const isStationSplit = computed(
  () => (currentSelectedRoute.value?.facilities?.length || 0) > STATION_SPLIT_THRESHOLD
);

const currentRoutePanel = computed(() => {
  const targetId = searchMode.value === "line" ? activeRouteId.value : activeMatchedRouteId.value;
  if (!targetId) return null;
  if (selectedRoutePanel.value && routeMatchesKey(selectedRoutePanel.value, targetId)) {
    return selectedRoutePanel.value;
  }
  const route = currentSelectedRoute.value;
  const key = route ? routeUniqueKey(route) : String(targetId);
  if (route?.lineGroup) {
    return routePanelData.value?.lineGroups?.[key]
      || routePanelData.value?.routes?.[key]
      || null;
  }
  return routePanelData.value?.routes?.[key]
    || routePanelData.value?.routes?.[targetId]
    || findRoutePanelPayload(routePanelData.value?.routes, route)
    || null;
});

function toFiniteNumber(value, fallback = 0) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function withLineMeta(route = {}, line = {}) {
  return {
    ...route,
    lineId: route.lineId || line.lineId || "",
    lineName: route.lineName || lineDisplayName(line) || line.lineName || "",
    rawLineName: route.rawLineName || line.lineName || "",
  };
}

function routeUniqueKey(route = {}) {
  if (route?.lineGroup) return String(route.routeKey || route.routeId || route.lineId || "");
  const routeId = String(route?.routeId || "");
  const lineId = String(route?.lineId || "");
  return lineId ? `${lineId}::${routeId}` : routeId;
}

function routeMatchesKey(route = {}, key = "") {
  const text = String(key || "");
  return routeUniqueKey(route) === text || String(route?.routeId || "") === text;
}

function findRoutePanelPayload(routes = {}, route = null) {
  if (!route || !routes || typeof routes !== "object") return null;
  const routeId = String(route.routeId || "");
  const lineId = String(route.lineId || "");
  return Object.values(routes).find((item) => (
    item
    && String(item.routeId || "") === routeId
    && (!lineId || String(item.lineId || "") === lineId)
  )) || null;
}

function metroLineNumber(text = "") {
  const raw = String(text || "");
  const chinese = raw.match(/(?:地铁|轨道|线路)?\s*([0-9]{1,2}|[一二三四五六七八九十]{1,4})\s*(?:号线|线)/i);
  const english = raw.match(/(?:metro|subway|mtr)(?:[-_\s]*line)?[-_\s]*([0-9]{1,2})\b|\bline[-_\s]*([0-9]{1,2})\b/i);
  const token = chinese?.[1] || english?.[1] || english?.[2] || "";
  if (!token) return "";
  if (/^\d+$/.test(token)) return String(Number(token));
  const table = {
    一: "1", 二: "2", 三: "3", 四: "4", 五: "5", 六: "6", 七: "7", 八: "8", 九: "9", 十: "10",
    十一: "11", 十二: "12", 十三: "13", 十四: "14", 十五: "15", 十六: "16", 十七: "17", 十八: "18", 十九: "19", 二十: "20",
  };
  return table[token] || "";
}

function normalizedTransitMode(text = "") {
  const value = String(text || "").toLowerCase();
  if (/subway|metro|mtr|rail|train|地铁|轨道|轻轨|有轨/.test(value)) return "subway";
  if (/bus|公交/.test(value)) return "bus";
  return "";
}

function declaredTransitMode(line = {}) {
  const ownMode = normalizedTransitMode(line.mode || line.transportMode);
  if (ownMode) return ownMode;
  const routeModes = (Array.isArray(line.routes) ? line.routes : [])
    .map((route) => normalizedTransitMode(route?.mode || route?.transportMode))
    .filter(Boolean);
  if (routeModes.includes("subway")) return "subway";
  if (routeModes.includes("bus")) return "bus";
  return "";
}

function hasMetroModeKeyword(text = "") {
  return /subway|metro|mtr|rail|train|地铁|轨道|轻轨|有轨/i.test(String(text || ""));
}

function hasRouteIdMetroKeyword(text = "") {
  return /subway|metro|mtr/i.test(String(text || ""));
}

function hasBusIdKeyword(text = "") {
  const value = String(text || "").toLowerCase();
  return value.includes("busgtfs")
    || value.includes("bus_gtfs")
    || value.startsWith("bus")
    || value.includes(" bus");
}

// 同一条地铁线的分段/支线后缀：剥离后合并（如 3号线 + 3号线北段、12号线东段 + 12号线西段、14号线 + 14号线知识城线）。
// 城市/制式前缀（佛山、南海、黄埔、海珠、有轨电车…）不在此列，确保跨系统同号线不会被错误合并。
const METRO_SEGMENT_SUFFIX = /(北延段|南延段|东延段|西延段|北延线|南延线|东延线|西延线|北段|南段|东段|西段|延长线|延长段|知识城支线|知识城线|支线|一期|二期|三期|四期|首期工程|首期|首通段|后通段)/g;
// 规范化后恰为“N号线”（阿拉伯或中文数字）才算“纯地铁线路号”，展示为“地铁N号线”。
const PURE_METRO_LINE = /^(?:[0-9]{1,2}|[一二三四五六七八九十]{1,4})号线$/;

// 规范化地铁线路名：去空白、去括号备注、剥离同线分段后缀；剥离后为空则回退原名。
function metroLineCanonicalName(line = {}) {
  const base = String(line.lineName || line.lineId || "")
    .trim()
    .replace(/\s+/g, "")
    .replace(/[（(].*?[）)]/g, "");
  const stripped = base.replace(METRO_SEGMENT_SUFFIX, "");
  return stripped || base;
}

function isMetroLine(line = {}) {
  const declaredMode = declaredTransitMode(line);
  if (declaredMode === "subway") return true;
  if (declaredMode === "bus") return false;
  const lineText = [line.lineName, line.lineId].filter(Boolean).join(" ");
  const idText = [
    line.lineId,
    ...(Array.isArray(line.routes) ? line.routes.map((route) => route?.routeId) : []),
  ].filter(Boolean).join(" ");
  if (!hasMetroModeKeyword(lineText) && hasBusIdKeyword(idText)) return false;
  if (metroLineNumber(lineText) || hasMetroModeKeyword(lineText)) return true;
  return (Array.isArray(line.routes) ? line.routes : []).some((route) => (
    metroLineNumber([route?.routeName, route?.routeId].filter(Boolean).join(" "))
    || hasRouteIdMetroKeyword(route?.routeId)
  ));
}

// 地铁线路聚合键：按“规范化线路名”聚合，而非裸线路号，避免跨系统同号线被错误合并。
function lineGroupKey(line = {}) {
  if (!isMetroLine(line)) return String(line.lineName || line.lineId || "");
  return `metro::${metroLineCanonicalName(line)}`;
}

function lineDisplayName(line = {}) {
  if (!isMetroLine(line)) return line.lineName || line.lineId || "未命名线路";
  const canonical = metroLineCanonicalName(line);
  if (PURE_METRO_LINE.test(canonical)) return `地铁${canonical}`;
  return canonical || line.lineName || line.lineId || "未命名线路";
}

function linesForDisplayName(displayName) {
  const target = normalizeLineSearchName(displayName);
  if (!target) return [];
  return rawLines.value.filter((line) => normalizeLineSearchName(lineDisplayName(line)) === target);
}

// 物理站点键：地铁整线由多种服务模式（区间车/交路）组成，同一物理站在不同模式下
// 会有不同的 facilityId（…S014.link…P00013 / …P00014…）。这里按站名归并（站名缺失时回退到
// facilityId 中 ".link" 之前的站点编码），使整线只统计真实物理站点，而非按服务模式重复计数。
function physicalStationKey(fac = {}) {
  const name = String(fac?.facilityName || "").trim();
  if (name && name !== "--") return `name:${name}`;
  const id = String(fac?.facilityId || "");
  const cut = id.indexOf(".link");
  return `id:${cut > 0 ? id.slice(0, cut) : id}`;
}

// 把（可能按服务模式重复的）站点客流按物理站点归并并逐时累加。
function stationFlowLookup(stationFlows = []) {
  const map = new Map();
  (Array.isArray(stationFlows) ? stationFlows : []).forEach((item) => {
    const key = physicalStationKey(item);
    let agg = map.get(key);
    if (!agg) {
      agg = { boardingByHour: new Array(24).fill(0), alightingByHour: new Array(24).fill(0) };
      map.set(key, agg);
    }
    const boarding = Array.isArray(item.boardingByHour) ? item.boardingByHour : [];
    const alighting = Array.isArray(item.alightingByHour) ? item.alightingByHour : [];
    for (let h = 0; h < 24; h++) {
      agg.boardingByHour[h] += Number(boarding[h]) || 0;
      agg.alightingByHour[h] += Number(alighting[h]) || 0;
    }
  });
  return map;
}

function uniqueFacilities(routes = []) {
  const seen = new Set();
  const facilities = [];
  routes.forEach((route) => {
    (Array.isArray(route?.facilities) ? route.facilities : []).forEach((fac) => {
      const key = physicalStationKey(fac);
      if (!key || seen.has(key)) return;
      seen.add(key);
      facilities.push(fac);
    });
  });
  return facilities;
}

function routeWithCachedDetail(route = {}) {
  const key = routeUniqueKey(route);
  const cached = key ? routeDetailCache.get(key) : null;
  return cached ? { ...route, ...cached } : route;
}

function buildLineGroupRoute(displayName, lines = linesForDisplayName(displayName)) {
  const routes = lines.flatMap((line) => (Array.isArray(line.routes) ? line.routes : [])
    .map((route) => routeWithCachedDetail(withLineMeta(route, line))));
  if (!routes.length) return null;
  const key = lineGroupKey(lines[0]);
  return {
    lineGroup: true,
    routeKey: key,
    lineId: key,
    lineName: displayName,
    routeId: key,
    routeName: displayName,
    links: routes.flatMap((route) => Array.isArray(route.links) ? route.links : []),
    facilities: uniqueFacilities(routes),
    childRoutes: routes,
  };
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
  // 整线（多服务模式合并）：后端 facNum 是按交路重复计数的虚高值，改用归并后的物理站点数。
  const stationCount = route.lineGroup
    ? toFiniteNumber(route.facilities?.length, 0)
    : toFiniteNumber(panelMetrics.facNum ?? info.facNum ?? route.facilities?.length, 0);
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

const demographicsGroups = computed(() => {
  return buildPassengerProfileGroups(currentRoutePanel.value?.demographics || {});
});
const demographicsRiderCount = computed(() =>
  passengerProfileRiderCount(currentRoutePanel.value?.demographics || {})
);

// 运营效益：日客流量 / 日发车班次 / 车辆数 / 单班次客流 / 车日均客流量
const operationStats = computed(() => {
  const m = currentRoutePanel.value?.metrics || {};
  const info = currentSelectedRoute.value?.info || {};
  const passenger = toFiniteNumber(m.passenger ?? info.passenger, 0);
  const departures = toFiniteNumber(m.departures, 0);
  const vehicles = toFiniteNumber(m.vehicles, 0);
  const perTrip = toFiniteNumber(m.perTripFlow, departures > 0 ? passenger / departures : 0);
  const perVehicle = toFiniteNumber(m.perVehicleFlow, vehicles > 0 ? passenger / vehicles : 0);
  const flow = (n) => (Number.isFinite(n) && n > 0 ? `${Math.round(n).toLocaleString()} 人次` : "--");
  return {
    dailyFlow: flow(passenger),
    departures: departures > 0 ? `${Math.round(departures)} 班` : "--",
    vehicles: vehicles > 0 ? `${Math.round(vehicles)} 辆` : "--",
    perTrip: flow(perTrip),
    perVehicle: flow(perVehicle),
  };
});

const boardingAlightingChartOption = computed(() => {
  const route = currentSelectedRoute.value || {};
  const facilities = route.facilities || [];

  const stationNames = facilities.map(f => f.facilityName || "");
  const startHour = segmentTimeRange.value[0];
  const endHour = segmentTimeRange.value[1];
  const stationFlowMap = stationFlowLookup(currentRoutePanel.value?.stationFlows);
  const boardingData = facilities.map((fac) => {
    const flow = stationFlowMap.get(physicalStationKey(fac));
    return sumHourRange(flow?.boardingByHour, startHour, endHour);
  });
  const alightingData = facilities.map((fac) => {
    const flow = stationFlowMap.get(physicalStationKey(fac));
    return -sumHourRange(flow?.alightingByHour, startHour, endHour);
  });

  const isBar = boardingChartType.value === "bar";

  // 站点过多时，单行旋转标签在窄面板内会相互糊成一团。
  // 超过阈值则把图表拆成上下两行（两张子图），每行各承担一半站点，
  // 单行标签数量减半、横向空间翻倍，从而互不重叠、清晰可读。
  const SPLIT_THRESHOLD = STATION_SPLIT_THRESHOLD;
  const splitRows = stationNames.length > SPLIT_THRESHOLD;

  const boardingColor = {
    type: "linear", x: 0, y: 0, x2: 0, y2: 1,
    colorStops: [{ offset: 0, color: "#0f9f6e" }, { offset: 1, color: "#087a55" }]
  };
  const alightingColor = {
    type: "linear", x: 0, y: 0, x2: 0, y2: 1,
    colorStops: [{ offset: 0, color: "#f43f5e" }, { offset: 1, color: "#e11d48" }]
  };

  const makeXAxis = (data, gridIndex, rotate) => ({
    type: "category",
    data,
    gridIndex,
    axisLine: { lineStyle: { color: "rgba(21, 105, 222, 0.15)" } },
    axisLabel: {
      color: "#64748b",
      fontSize: 10,
      interval: 0,
      rotate,
      width: 64,
      overflow: "truncate",
      ellipsis: "…",
      hideOverlap: true,
      margin: 8
    }
  });

  const makeYAxis = (gridIndex, min, max) => ({
    type: "value",
    gridIndex,
    min,
    max,
    axisLine: { show: false },
    axisTick: { show: false },
    splitLine: { lineStyle: { color: "rgba(21, 105, 222, 0.06)", type: "dashed" } },
    axisLabel: {
      color: "#64748b",
      fontSize: 10,
      formatter: (value) => Math.abs(value)
    }
  });

  const makeSeries = (name, color, data, axisIndex, stackId, radius) => ({
    name,
    type: boardingChartType.value,
    xAxisIndex: axisIndex,
    yAxisIndex: axisIndex,
    stack: isBar ? stackId : undefined,
    smooth: !isBar,
    symbol: "circle",
    symbolSize: 6,
    barWidth: "40%",
    itemStyle: { color, borderRadius: radius },
    data
  });

  const baseOption = {
    backgroundColor: "transparent",
    tooltip: {
      trigger: "axis",
      axisPointer: { type: "shadow" },
      backgroundColor: "rgba(30, 41, 59, 0.9)",
      borderColor: "rgba(255, 255, 255, 0.15)",
      textStyle: { color: "#ffffff", fontSize: 12 },
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
      textStyle: { color: "#64748b", fontSize: 11 },
      top: 0,
      icon: "rect"
    }
  };

  if (!splitRows) {
    return {
      ...baseOption,
      grid: { left: "3%", right: "4%", bottom: 8, top: "15%", containLabel: true },
      xAxis: makeXAxis(stationNames, 0, 35),
      yAxis: makeYAxis(0),
      series: [
        makeSeries("上车人数", boardingColor, boardingData, 0, "Total", [4, 4, 0, 0]),
        makeSeries("下车人数", alightingColor, alightingData, 0, "Total", [0, 0, 4, 4])
      ]
    };
  }

  // ── 两行（两张子图）布局：站点对半切分到上下两行 ──
  const mid = Math.ceil(stationNames.length / 2);
  // 两行共用同一纵轴量程，保证跨行的柱高可直接比较
  const finiteBoarding = boardingData.filter(v => Number.isFinite(v));
  const finiteAlighting = alightingData.filter(v => Number.isFinite(v));
  const yMax = Math.max(1, ...finiteBoarding);
  const yMin = Math.min(0, ...finiteAlighting);

  return {
    ...baseOption,
    grid: [
      { left: "3%", right: "4%", top: "8%", height: "32%", containLabel: true },
      { left: "3%", right: "4%", top: "58%", height: "32%", containLabel: true }
    ],
    xAxis: [
      makeXAxis(stationNames.slice(0, mid), 0, 30),
      makeXAxis(stationNames.slice(mid), 1, 30)
    ],
    yAxis: [
      makeYAxis(0, yMin, yMax),
      makeYAxis(1, yMin, yMax)
    ],
    series: [
      makeSeries("上车人数", boardingColor, boardingData.slice(0, mid), 0, "row0", [4, 4, 0, 0]),
      makeSeries("下车人数", alightingColor, alightingData.slice(0, mid), 0, "row0", [0, 0, 4, 4]),
      makeSeries("上车人数", boardingColor, boardingData.slice(mid), 1, "row1", [4, 4, 0, 0]),
      makeSeries("下车人数", alightingColor, alightingData.slice(mid), 1, "row1", [0, 0, 4, 4])
    ]
  };
});

const boardingProfileChartOption = computed(() => {
  const route = currentSelectedRoute.value || {};
  const facilities = route.facilities || [];
  const startHour = segmentTimeRange.value[0];
  const endHour = segmentTimeRange.value[1];
  const stationFlowMap = stationFlowLookup(currentRoutePanel.value?.stationFlows);
  const stationNames = facilities.map(f => f.facilityName || "");
  const boardingData = facilities.map((fac) => {
    const flow = stationFlowMap.get(physicalStationKey(fac));
    return sumHourRange(flow?.boardingByHour, startHour, endHour);
  });
  const alightingData = facilities.map((fac) => {
    const flow = stationFlowMap.get(physicalStationKey(fac));
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
// 站点乘降图：折线 / 柱状切换
const boardingChartType = ref("bar");

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
      if (route.lineGroup) {
        runMonitorSelectedLineName.value = baseName;
        return;
      }
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

// 原始断面：按服务模式（交路/区间车）逐条，保留方向与路线归属，供地图按子路线着色使用。
const rawRouteSegments = computed(() => {
  const startHour = segmentTimeRange.value[0];
  const endHour = segmentTimeRange.value[1];
  return (currentRoutePanel.value?.segments || []).map((segment) => ({
    name: segment.name,
    routeKey: String(segment.routeKey || ""),
    lineId: String(segment.lineId || ""),
    routeId: String(segment.routeId || ""),
    fromFacilityId: String(segment.fromFacilityId || ""),
    toFacilityId: String(segment.toFacilityId || ""),
    flow: Math.round(sumHourRange(segment.flowByHour, startHour, endHour)),
    loadRate: Number(averageHourRange(segment.loadRateByHour, startHour, endHour).toFixed(1))
  }));
});

// 无向站对键：把上下行同一物理区段（“A - B”与“B - A”）视为同一断面。
function stationPairKeyOf(a, b) {
  return [String(a || "").trim(), String(b || "").trim()].sort().join("");
}

// 展示用断面：整线（多服务模式合并）时，按“物理相邻站对”归并——把同一区段的各交路先按方向累加，
// 再取上下行中客流较大的方向（单向最大断面，行业惯用口径）。使断面数与站点数对应
// （34 站 → 约 33 个相邻区段），而非按交路/方向重复罗列。满载率按所选时段用整线运力重算。
const routeSegments = computed(() => {
  const panel = currentRoutePanel.value;
  const startHour = segmentTimeRange.value[0];
  const endHour = segmentTimeRange.value[1];
  if (!panel?.lineGroup) return rawRouteSegments.value;
  const capacityByHour = Array.isArray(panel.capacityByHour) ? panel.capacityByHour : [];
  const byPair = new Map();
  (panel.segments || []).forEach((segment) => {
    const name = String(segment.name || "");
    const parts = name.split(" - ");
    if (parts.length < 2) return;
    const fromName = parts[0].trim();
    const toName = parts[parts.length - 1].trim();
    const pairKey = stationPairKeyOf(fromName, toName);
    const dirKey = `${fromName}${toName}`;
    let pair = byPair.get(pairKey);
    if (!pair) {
      pair = new Map();
      byPair.set(pairKey, pair);
    }
    let dir = pair.get(dirKey);
    if (!dir) {
      dir = { name, fromName, toName, flowByHour: new Array(24).fill(0) };
      pair.set(dirKey, dir);
    }
    const flowByHour = Array.isArray(segment.flowByHour) ? segment.flowByHour : [];
    for (let h = 0; h < dir.flowByHour.length; h++) {
      dir.flowByHour[h] += Number(flowByHour[h]) || 0;
    }
  });
  const dirTotal = (dir) => dir.flowByHour.reduce((sum, v) => sum + v, 0);
  return Array.from(byPair.values()).map((pair) => {
    const dirs = Array.from(pair.values());
    const peak = dirs.reduce((best, dir) => (dirTotal(dir) > dirTotal(best) ? dir : best), dirs[0]);
    const loadByHour = peak.flowByHour.map((flow, h) => {
      const cap = Number(capacityByHour[h]) || 0;
      return cap > 0 ? Math.min(100, (flow * 100) / cap) : 0;
    });
    return {
      name: peak.name,
      fromName: peak.fromName,
      toName: peak.toName,
      flow: Math.round(sumHourRange(peak.flowByHour, startHour, endHour)),
      loadRate: Number(averageHourRange(loadByHour, startHour, endHour).toFixed(1))
    };
  });
});

function pointToLinkDistanceSq(coord, link) {
  const px = Number(coord?.x);
  const py = Number(coord?.y);
  const ax = Number(link?.from?.x);
  const ay = Number(link?.from?.y);
  const bx = Number(link?.to?.x);
  const by = Number(link?.to?.y);
  if (![px, py, ax, ay, bx, by].every(Number.isFinite)) return Number.POSITIVE_INFINITY;
  const dx = bx - ax;
  const dy = by - ay;
  const lenSq = dx * dx + dy * dy;
  const t = lenSq > 0 ? Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lenSq)) : 0;
  const nx = ax + t * dx;
  const ny = ay + t * dy;
  const ox = px - nx;
  const oy = py - ny;
  return ox * ox + oy * oy;
}

function nearestRouteLinkIndex(links, coord, startIndex = 0) {
  if (!coord || !Array.isArray(links) || !links.length) return -1;
  let bestIndex = -1;
  let bestDistance = Number.POSITIVE_INFINITY;
  const from = Math.max(0, Math.min(links.length - 1, Number(startIndex) || 0));
  for (let i = from; i < links.length; i++) {
    const distance = pointToLinkDistanceSq(coord, links[i]);
    if (distance < bestDistance) {
      bestDistance = distance;
      bestIndex = i;
    }
  }
  return bestIndex;
}

// 断面客流按“无向站对”建索引，供地图按物理区段着色（与右侧断面表使用同一客流口径）。
function segmentFlowByNamePair(segments = []) {
  const result = new Map();
  segments.forEach((segment) => {
    const parts = String(segment?.name || "").split(" - ");
    if (parts.length < 2) return;
    const key = stationPairKeyOf(parts[0], parts[parts.length - 1]);
    result.set(key, Math.max(0, Number(segment.flow) || 0));
  });
  return result;
}

function indexedSegmentFlow(segments = [], index = 0) {
  if (!segments.length) return 0;
  const safeIndex = Math.max(0, Math.min(segments.length - 1, Number(index) || 0));
  return Math.max(0, Number(segments[safeIndex]?.flow) || 0);
}

function mapSegmentFlowsByLinkOrder(links = [], segments = []) {
  if (!Array.isArray(links) || !links.length) return [];
  if (!Array.isArray(segments) || !segments.length) return links.map((link) => ({ ...link, flow: 0 }));
  return links.map((link, index) => {
    const segmentIndex = Math.min(segments.length - 1, Math.floor((index / Math.max(1, links.length)) * segments.length));
    return { ...link, flow: indexedSegmentFlow(segments, segmentIndex) };
  });
}

function buildSingleRouteFlowMapLinks(route, segments = []) {
  const links = Array.isArray(route?.links) ? route.links : [];
  if (!links.length) return [];
  const facilities = Array.isArray(route?.facilities) ? route.facilities : [];
  const flowByPair = segmentFlowByNamePair(segments);
  if (facilities.length < 2) return mapSegmentFlowsByLinkOrder(links, segments);

  const result = links.map((link) => ({ ...link, flow: 0 }));
  let mappedCount = 0;
  let cursor = 0;
  for (let i = 0; i + 1 < facilities.length; i++) {
    const fromFac = facilities[i];
    const toFac = facilities[i + 1];
    const fromCoord = fromFac?.coord || fromFac;
    const toCoord = toFac?.coord || toFac;
    const fromIndex = nearestRouteLinkIndex(links, fromCoord, cursor);
    const toIndex = nearestRouteLinkIndex(links, toCoord, Math.max(cursor, fromIndex));
    if (fromIndex < 0 || toIndex < 0) continue;
    const start = Math.min(fromIndex, toIndex);
    const end = Math.max(fromIndex, toIndex);
    // 按相邻站对（与断面表同一无向口径）取客流，保证地图配色与断面数值一致。
    const key = stationPairKeyOf(fromFac?.facilityName, toFac?.facilityName);
    const flow = flowByPair.get(key) ?? indexedSegmentFlow(segments, i);
    for (let linkIndex = start; linkIndex <= end; linkIndex++) {
      result[linkIndex].flow = flow;
    }
    mappedCount++;
    cursor = Math.max(0, end);
  }
  return mappedCount > 0 ? result : mapSegmentFlowsByLinkOrder(links, segments);
}

function buildRouteFlowMapLinks(route, segments = []) {
  // 整线：各子路线（方向/交路）都按同一份合并后的物理断面客流着色，避免按单交路客流偏小而配色失真。
  if (route?.lineGroup && Array.isArray(route.childRoutes) && route.childRoutes.length) {
    const mapped = route.childRoutes.flatMap((childRoute) => buildSingleRouteFlowMapLinks(childRoute, segments));
    if (mapped.length) return mapped;
  }
  return buildSingleRouteFlowMapLinks(route, segments);
}

watch(
  () => [
    shouldRenderPfaRightPanel.value,
    currentSelectedRoute.value?.routeId,
    currentSelectedRoute.value?.links?.length || 0,
    currentSelectedRoute.value?.facilities?.length || 0,
    selectedRouteDetail.value,
    currentRoutePanel.value,
    routeSegments.value,
    segmentTimeRange.value[0],
    segmentTimeRange.value[1],
  ],
  () => {
    if (!runMonitorSelectedRouteMapLinks) return;
    if (!shouldRenderPfaRightPanel.value || !currentSelectedRoute.value) {
      runMonitorSelectedRouteMapLinks.value = [];
      return;
    }
    // 地图与右侧断面表使用同一份合并后的物理断面客流，保证配色与数值一致。
    runMonitorSelectedRouteMapLinks.value = buildRouteFlowMapLinks(currentSelectedRoute.value, routeSegments.value);
  },
  { immediate: true, deep: true },
);

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
  const names = rawLines.value.map(line => lineDisplayName(line)).filter(Boolean);
  const uniqueNames = Array.from(new Set(names)).sort((a, b) => a.localeCompare(b, "zh-CN"));
  return uniqueNames
    .map(name => ({ value: name, label: name }))
    .filter((option) => runMonitorLineOptionFilter(option));
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
  return uniqueNames
    .map(name => ({ value: name, label: name }))
    .filter((option) => runMonitorStationOptionFilter(option));
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
  const lines = linesForDisplayName(selectedLineName.value);
  const routes = lines.flatMap((line) => (line.routes || []).map((route) => withLineMeta(route, line)));
  if (lines.some(isMetroLine)) {
    const groupRoute = buildLineGroupRoute(selectedLineName.value, lines);
    return groupRoute ? [groupRoute, ...routes] : routes;
  }
  return routes;
});

// 获取当前活动路线方向的详情
const activeRoute = computed(() => {
  if (!activeRouteId.value) return null;
  if (selectedLineName.value) {
    const groupRoute = buildLineGroupRoute(selectedLineName.value);
    if (groupRoute && routeMatchesKey(groupRoute, activeRouteId.value)) return groupRoute;
  }
  for (const line of rawLines.value) {
    if (line.routes) {
      const match = line.routes.find(r => r.routeId === activeRouteId.value);
      if (match) return withLineMeta(match, line);
    }
  }
  return null;
});

function getRouteEndpointLabel(route, index) {
  if (route?.lineGroup) return "整线";
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
    rawLines.value.find((item) => normalizeLineSearchName(lineDisplayName(item)) === target) ||
    rawLines.value.find((item) => normalizeLineSearchName(item.lineName) === target) ||
    rawLines.value.find((item) => normalizeLineSearchName(lineDisplayName(item)).includes(target) || target.includes(normalizeLineSearchName(lineDisplayName(item))));
  const displayName = line ? lineDisplayName(line) : "";
  if (!displayName) return false;
  searchMode.value = "line";
  selectedStationName.value = "";
  selectedLineName.value = displayName;
  await nextTick();
  await handleLineChange(displayName);
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
    rawLines.value.find((item) => normalizeLineSearchName(lineDisplayName(item)) === targetName) ||
    rawLines.value.find((item) => normalizeLineSearchName(item.lineName) === targetName) ||
    rawLines.value.find((item) => normalizeLineSearchName(lineDisplayName(item)).includes(targetName) || targetName.includes(normalizeLineSearchName(lineDisplayName(item))));
  if (!line) return false;
  searchMode.value = "line";
  selectedStationName.value = "";
  selectedLineName.value = lineDisplayName(line);
  await nextTick();
  const routes = selectedLineRoutes.value.filter((route) => !route.lineGroup);
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
    if (!runMonitorStationOptionFilter({
      value: fac.facilityName,
      label: fac.facilityName,
      facilityId: fac.facilityId,
      coord: fac.coord,
    })) return null;
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
  if (runMonitorSimplifiedRight && !shouldRenderPfaRightPanel.value) {
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
  if (route.lineGroup) return loadLineGroupDetail(route);
  const routeId = String(route.routeId);
  const key = routeUniqueKey(route);
  if (routeDetailCache.has(key)) {
    return routeDetailCache.get(key);
  }
  const res = await getRouteDetail({
    datasource: props.model,
    lineId: route.lineId || "",
    routeId: route.routeId,
  });
  const detail = {
    ...route,
    ...(res.data || {}),
    lineId: route.lineId || res.data?.lineId || "",
    lineName: route.lineName || res.data?.lineName || "",
    facilities: res.data?.facilities || route.facilities || [],
    info: res.data?.info || route.info || {},
  };
  routeDetailCache.set(key, detail);
  return detail;
}

async function loadLineGroupDetail(route) {
  const key = routeUniqueKey(route);
  if (routeDetailCache.has(key)) return routeDetailCache.get(key);
  const childRoutes = Array.isArray(route?.childRoutes) ? route.childRoutes : [];
  const childDetails = (await Promise.all(childRoutes.map((child) => loadRouteDetail(child))))
    .filter(Boolean);
  const detail = {
    ...route,
    links: childDetails.flatMap((child) => Array.isArray(child?.links) ? child.links : []),
    facilities: uniqueFacilities(childDetails.length ? childDetails : childRoutes),
    childRoutes: childDetails.length ? childDetails : childRoutes,
  };
  routeDetailCache.set(key, detail);
  return detail;
}

async function loadRoutePanelDetail(route) {
  const routeId = String(route?.routeId || "");
  if (!routeId) return null;
  const key = routeUniqueKey(route);
  if (route?.lineGroup) {
    const panel = await ensureRoutePanelData();
    const groupPanel = panel?.lineGroups?.[key] || null;
    if (groupPanel) routePanelDetailCache.set(key, groupPanel);
    return groupPanel;
  }
  if (routePanelDetailCache.has(key)) return routePanelDetailCache.get(key);
  if (routePanelDetailPromises.has(key)) return routePanelDetailPromises.get(key);
  const model = props.model;
  const promise = getRoutePanelDetail({
    datasource: model,
    lineId: route.lineId || "",
    routeId,
  }, { silentError: true })
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

async function loadSelectedRoutePanel(route) {
  const key = routeUniqueKey(route);
  if (!key || !shouldLoadSelectedRoutePanel.value) return null;
  const detailPanel = await loadRoutePanelDetail(route);
  if (detailPanel) return detailPanel;
  if (!shouldRenderPfaRightPanel.value) return null;
  const panel = await ensureRoutePanelData();
  const routePanel = panel?.routes?.[key]
    || panel?.routes?.[String(route?.routeId || "")]
    || findRoutePanelPayload(panel?.routes, route)
    || null;
  if (routePanel) {
    routePanelDetailCache.set(key, routePanel);
  }
  return routePanel;
}

function ensureRoutePanelData() {
  if (routePanelData.value) return Promise.resolve(routePanelData.value);
  if (routePanelPromise) return routePanelPromise;
  const model = props.model;
  routePanelPromise = getRoutePanel({ datasource: model }, { silentError: true })
    .then((res) => {
      const data = res.data || null;
      if (props.model === model && data?.routes) {
        routePanelData.value = data;
      }
      return data;
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
  const key = routeUniqueKey(route);
  activeRouteId.value = routeId;
  selectedRoutePanel.value = routePanelDetailCache.get(key) || null;
  const [detail, panel] = await Promise.all([
    loadRouteDetail(route),
    loadSelectedRoutePanel(route),
  ]);
  if (String(activeRouteId.value) !== routeId) return;
  selectedRouteDetail.value = detail;
  if (shouldLoadSelectedRoutePanel.value) selectedRoutePanel.value = panel;
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
          const hasFac = route.facilities.some((fac) => fac.facilityName === stationName && runMonitorStationOptionFilter({
            value: fac.facilityName,
            label: fac.facilityName,
            facilityId: fac.facilityId,
            coord: fac.coord,
          }));
          if (hasFac) {
            const fac = route.facilities.find((item) => item.facilityName === stationName && runMonitorStationOptionFilter({
              value: item.facilityName,
              label: item.facilityName,
              facilityId: item.facilityId,
              coord: item.coord,
            }));
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
  const key = routeUniqueKey(item);
  activeMatchedRouteId.value = key;
  selectedRoutePanel.value = routePanelDetailCache.get(key) || null;
  const [detail, panel] = await Promise.all([
    loadRouteDetail(item),
    loadSelectedRoutePanel(item),
  ]);
  if (String(activeMatchedRouteId.value) !== key) return;
  selectedRouteDetail.value = detail;
  if (shouldLoadSelectedRoutePanel.value) selectedRoutePanel.value = panel;
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
  if (!runMonitorSimplifiedRight || shouldRenderPfaRightPanel.value) ensureRoutePanelData();
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
    if (runMonitorSelectedRouteMapLinks) runMonitorSelectedRouteMapLinks.value = [];
    selectedRouteDetail.value = null;
    loadAllLines();
  }
});

onUnmounted(() => {
  if (runMonitorSelectedRouteDetail) runMonitorSelectedRouteDetail.value = null;
  if (runMonitorSelectedRouteMapLinks) runMonitorSelectedRouteMapLinks.value = [];
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
  if (runMonitorSelectedRouteMapLinks) runMonitorSelectedRouteMapLinks.value = [];
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

/* ===== 线路客流分析 · 右侧面板（统一到 dm2 蓝玻璃面板体系）===== */
/* MCard2 置于 .dm-overview-panel 玻璃面板内：去卡片化，作为内容容器，避免卡中卡 */
.SJZL_right_card.pfa-route-card {
  width: 100%;
  min-height: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  overflow: hidden;
}
.SJZL_right_card.pfa-route-card :deep(.MCard2_title_box) {
  flex: 0 0 auto;
  min-height: 0;
  padding: 0 0 12px;
  background: transparent;
  border-bottom: 1px solid var(--dm2-line);
}
.SJZL_right_card.pfa-route-card :deep(.MCard2_title_box:hover) {
  background: transparent;
}
.SJZL_right_card.pfa-route-card :deep(.MCard2_open_btn) {
  color: var(--dm2-muted-soft);
}
.SJZL_right_card.pfa-route-card :deep(.MCard2_body_box) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 0;
  border-top: 0;
}

/* 标题区：线路名 + 规模信息 + 导出 */
.SJZL_right_card.pfa-route-card :deep(.ranking-title-container) {
  flex: 1;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--dm2-space-3);
  min-width: 0;
}
.pfa-route-heading {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}
.pfa-route-name {
  font-size: var(--dm2-text-xl);
  font-weight: var(--dm2-fw-bold);
  line-height: 1.2;
  color: var(--dm2-accent-strong);
  letter-spacing: -0.01em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.pfa-route-sub {
  font-size: var(--dm2-text-xs);
  color: var(--dm2-muted);
  font-variant-numeric: tabular-nums;
}

.pfa-route-sections {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  font-family: var(--dm2-font);
  overflow: hidden;
}

/* 各分区扁平排布，发丝线分隔，避免卡中卡 */
.pfa-route-sections .pfa-section {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: var(--dm2-space-3);
  padding: var(--dm2-space-5) 0;
  border-top: 1px solid var(--dm2-line-faint);
}
.pfa-route-sections .pfa-section:first-of-type {
  padding-top: var(--dm2-space-4);
  border-top: 0;
}
.pfa-route-sections .section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dm2-space-2);
}
.pfa-route-sections .section-title {
  display: flex;
  align-items: center;
  gap: var(--dm2-space-2);
  font-size: var(--dm2-text-md);
  font-weight: var(--dm2-fw-semibold);
  color: var(--dm2-ink);
  letter-spacing: -0.01em;
}
.pfa-route-sections .section-title::before {
  content: "";
  width: 3px;
  height: 13px;
  border-radius: var(--dm2-radius-pill);
  background: var(--dm2-accent);
}

/* 统计时段：控件组，浅蓝磨砂底 */
.pfa-route-sections .time-range-section {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  gap: var(--dm2-space-2);
  padding: var(--dm2-space-3) var(--dm2-space-4);
  margin: var(--dm2-space-4) 0 0;
  border-radius: var(--dm2-radius);
  background: var(--dm2-surface-sunken);
  border: 1px solid var(--dm2-line);
}
.pfa-route-sections .time-range-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.pfa-route-sections .time-range-header .title {
  font-size: var(--dm2-text-sm);
  font-weight: var(--dm2-fw-semibold);
  color: var(--dm2-ink-soft);
}
.pfa-route-sections .time-range-header .range-text {
  font-size: var(--dm2-text-sm);
  font-weight: var(--dm2-fw-bold);
  color: var(--dm2-accent);
  font-family: var(--dm2-font-num);
  font-variant-numeric: tabular-nums;
}
.pfa-route-sections .time-range-slider {
  width: calc(100% - 8px);
  margin: 0 auto;
}
.pfa-route-sections .time-range-slider :deep(.el-slider__runway) {
  background-color: var(--dm2-line);
}
.pfa-route-sections .time-range-slider :deep(.el-slider__bar) {
  background-color: var(--dm2-accent);
}
.pfa-route-sections .time-range-slider :deep(.el-slider__button) {
  border-color: var(--dm2-accent);
  width: 14px;
  height: 14px;
}

/* ① 断面客流与满载率：扁平数据表（修复列对齐）*/
.pfa-route-sections .segments-table {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--dm2-line);
  border-radius: var(--dm2-radius-sm);
  overflow: hidden;
}
.pfa-route-sections .segments-table .table-header,
.pfa-route-sections .segments-table .table-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 64px 56px;
  align-items: center;
  gap: var(--dm2-space-3);
  padding: var(--dm2-space-2) var(--dm2-space-3);
}
.pfa-route-sections .segments-table .table-header {
  flex: 0 0 auto;
  background: var(--dm2-surface-sunken);
  border-bottom: 1px solid var(--dm2-line);
  font-size: var(--dm2-text-xs);
  font-weight: var(--dm2-fw-semibold);
  color: var(--dm2-muted);
}
.pfa-route-sections .segments-table .table-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  scrollbar-gutter: stable;
}
.pfa-route-sections .segments-table .table-row {
  font-size: var(--dm2-text-sm);
  border-bottom: 1px solid var(--dm2-line-faint);
  transition: background-color var(--dm2-dur-fast) var(--dm2-ease);
}
.pfa-route-sections .segments-table .table-row:last-child {
  border-bottom: 0;
}
.pfa-route-sections .segments-table .table-row:hover {
  background: var(--dm2-accent-weak);
}
/* 覆盖全局 .col-name / .col-flow（排行榜用的 width:108px / flex 列），改由网格轨道定宽 */
.pfa-route-sections .segments-table .col-name {
  display: block;
  width: auto;
  min-width: 0;
  font-weight: var(--dm2-fw-medium);
  color: var(--dm2-ink-soft);
  line-height: 1.35;
}
.pfa-route-sections .segments-table .col-flow {
  display: block;
  width: auto;
  text-align: right;
  white-space: nowrap;
  font-family: var(--dm2-font-num);
  font-variant-numeric: tabular-nums;
  font-weight: var(--dm2-fw-semibold);
  color: var(--dm2-ink);
}
.pfa-route-sections .segments-table .col-load {
  display: block;
  width: auto;
  text-align: right;
  white-space: nowrap;
}
.pfa-route-sections .segments-table .load-indicator {
  display: inline-block;
  min-width: 44px;
  padding: 2px 7px;
  border-radius: var(--dm2-radius-pill);
  font-family: var(--dm2-font-num);
  font-variant-numeric: tabular-nums;
  font-size: var(--dm2-text-xs);
  font-weight: var(--dm2-fw-semibold);
}
.pfa-route-sections .segments-table .load-indicator.high {
  background: var(--dm2-delete-weak);
  color: var(--dm2-delete);
}
.pfa-route-sections .segments-table .load-indicator.medium {
  background: var(--dm2-modify-weak);
  color: var(--dm2-modify);
}
.pfa-route-sections .segments-table .load-indicator.low {
  background: var(--dm2-add-weak);
  color: var(--dm2-add);
}

/* ② 站点乘降图 & ⑤ 关联线路图 */
.pfa-route-sections .chart-type-selector {
  display: flex;
  gap: 2px;
  padding: 2px;
  border-radius: var(--dm2-radius-sm);
  background: var(--dm2-surface-sunken);
  border: 1px solid var(--dm2-line);
}
.pfa-route-sections .chart-type-selector .type-pill {
  padding: 3px 10px;
  border-radius: calc(var(--dm2-radius-sm) - 3px);
  font-size: var(--dm2-text-xs);
  font-weight: var(--dm2-fw-semibold);
  color: var(--dm2-muted);
  cursor: pointer;
  user-select: none;
  transition: color var(--dm2-dur-fast) var(--dm2-ease),
    background-color var(--dm2-dur-fast) var(--dm2-ease);
}
.pfa-route-sections .chart-type-selector .type-pill:hover {
  color: var(--dm2-ink-soft);
}
.pfa-route-sections .chart-type-selector .type-pill.active {
  color: #fff;
  background: var(--dm2-accent);
  box-shadow: var(--dm2-accent-glow);
}
.pfa-route-sections .chart-container-wrapper,
.pfa-route-sections .transfer-chart-wrapper {
  height: 208px;
  width: 100%;
}
.pfa-route-sections .chart_box {
  width: 100%;
  height: 100%;
}
/* 站点过多时乘降客流图拆成上下两行，需要更高的容器承载两张子图 */
.pfa-route-sections .chart-container-wrapper.is-split {
  height: 340px;
}

/* ③ 运营效益：发丝线网格的指标表（克制，无渐变 hero）*/
.pfa-route-sections .efficiency-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1px;
  border: 1px solid var(--dm2-line);
  border-radius: var(--dm2-radius-sm);
  background: var(--dm2-line);
  overflow: hidden;
}
.pfa-route-sections .eff-card {
  display: flex;
  flex-direction: column;
  gap: var(--dm2-space-1);
  padding: var(--dm2-space-3);
  background: var(--dm2-surface);
}
.pfa-route-sections .eff-card:first-child {
  grid-column: span 2;
}
.pfa-route-sections .eff-label {
  font-size: var(--dm2-text-xs);
  font-weight: var(--dm2-fw-medium);
  color: var(--dm2-muted);
}
.pfa-route-sections .eff-value {
  font-size: var(--dm2-text-lg);
  font-weight: var(--dm2-fw-bold);
  color: var(--dm2-ink);
  font-family: var(--dm2-font-num);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.01em;
}
.pfa-route-sections .eff-card:first-child .eff-value {
  font-size: var(--dm2-text-title);
  color: var(--dm2-accent-strong);
}

/* 分区右上角的轻量元信息（样本量等）*/
.pfa-route-sections .pfa-section-meta {
  font-size: var(--dm2-text-xs);
  color: var(--dm2-muted);
  font-variant-numeric: tabular-nums;
}

/* ④ 客流画像：可扩展的占比条形列表（按类型自适应，不再横向溢出）*/
.pfa-route-sections .demo-groups {
  display: flex;
  flex-direction: column;
  gap: var(--dm2-space-5);
}
.pfa-route-sections .demo-group {
  display: flex;
  flex-direction: column;
  gap: var(--dm2-space-3);
}
.pfa-route-sections .demo-group-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--dm2-space-2);
  padding-bottom: var(--dm2-space-2);
  border-bottom: 1px solid var(--dm2-line);
}
.pfa-route-sections .demo-group-title {
  font-size: var(--dm2-text-sm);
  font-weight: var(--dm2-fw-semibold);
  color: var(--dm2-ink);
  letter-spacing: 0.02em;
}
.pfa-route-sections .demo-group-sum {
  font-size: var(--dm2-text-xs);
  color: var(--dm2-ink-soft);
  font-family: var(--dm2-font-num);
  font-variant-numeric: tabular-nums;
}
.pfa-route-sections .demo-list {
  display: flex;
  flex-direction: column;
  gap: var(--dm2-space-3);
}
.pfa-route-sections .demo-row {
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr) 50px;
  align-items: center;
  gap: var(--dm2-space-3);
}
.pfa-route-sections .demo-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--dm2-text-sm);
  color: var(--dm2-ink-soft);
  white-space: nowrap;
}
.pfa-route-sections .demo-dot {
  width: 8px;
  height: 8px;
  border-radius: 3px;
  flex-shrink: 0;
}
.pfa-route-sections .demo-track {
  height: 7px;
  border-radius: var(--dm2-radius-pill);
  background: var(--dm2-line);
  overflow: hidden;
}
.pfa-route-sections .demo-fill {
  display: block;
  height: 100%;
  border-radius: var(--dm2-radius-pill);
  transition: width var(--dm2-dur-slow) var(--dm2-ease-out);
}
.pfa-route-sections .demo-pct {
  text-align: right;
  font-size: var(--dm2-text-sm);
  font-weight: var(--dm2-fw-semibold);
  color: var(--dm2-ink);
  font-family: var(--dm2-font-num);
  font-variant-numeric: tabular-nums;
}

.pfa-route-sections .pfa-empty {
  padding: var(--dm2-space-5);
  text-align: center;
  font-size: var(--dm2-text-sm);
  color: var(--dm2-muted-soft);
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
