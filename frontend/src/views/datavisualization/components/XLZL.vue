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
            <!-- Directions Pill (if multiple routes)；地铁只按整线统计，不显示方向切换 -->
            <div class="route-directions" v-if="selectedLineRoutes.length > 1 && !isMetroSelection">
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
              <span class="pfa-route-name">{{ pfaRouteTitle }}</span>
              <span class="pfa-route-sub">
                {{ routeMetrics.stationCount }} · 全长 {{ routeMetrics.length }}
              </span>
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
          <!-- 方向切换：两个方向分开统计与绘图，避免上下行混在一起
               （客流画像为上下行合并统计、关联线路的同站换乘天然含对向站，均不提供切换）
               地铁只按整线统计，不区分方向，故隐藏 -->
          <div v-if="panelDirectionRoutes.length > 1 && !isMetroSelection && !['demographics', 'transfer'].includes(pfaLineSection)" class="panel-direction-section">
            <span class="panel-direction-label">线路方向</span>
            <div class="panel-direction-pills">
              <button
                v-for="(route, index) in panelDirectionRoutes"
                :key="routeUniqueKey(route)"
                type="button"
                :class="['panel-direction-pill', isPanelDirectionActive(route) ? 'active' : '']"
                @click="handlePanelDirectionSwitch(route)"
              >
                {{ getRouteEndpointLabel(route, index) }}
              </button>
            </div>
          </div>

          <!-- 统计时段（仅断面 / 乘降 / 关联换乘按时段统计）-->
          <div v-if="['segments', 'boarding', 'transfer'].includes(pfaLineSection)" class="time-range-section">
            <div class="time-unit-row">
              <span class="time-unit-label">统计时间单位</span>
              <div class="time-unit-selector" role="group" aria-label="统计时间单位">
                <button
                  v-for="option in timeUnitOptions"
                  :key="option.value"
                  type="button"
                  :class="['time-unit-btn', segmentTimeUnit === option.value ? 'active' : '']"
                  @click="segmentTimeUnit = option.value"
                >
                  {{ option.label }}
                </button>
              </div>
            </div>
            <div class="time-range-header">
              <span class="title">统计时段选择</span>
              <span class="range-text">{{ formatHourLabel(segmentTimeRange[0]) }} - {{ formatHourLabel(segmentTimeRange[1]) }}</span>
            </div>
            <el-slider
              v-model="segmentTimeRange"
              range
              :min="6"
              :max="23"
              :step="segmentTimeStep"
              :show-tooltip="false"
              class="time-range-slider"
            />
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
                <!-- 环线/往返可能出现重名断面：组合键保证唯一稳定 -->
                <!-- 行背景条 = 客流量占峰值比例，让整张表读作沿线断面客流剖面；峰值断面高亮 -->
                <div
                  v-for="(seg, idx) in routeSegments"
                  :key="`${seg.routeKey || ''}-${seg.fromFacilityId || seg.fromName || ''}-${seg.toFacilityId || seg.toName || ''}-${idx}`"
                  :class="['table-row', segmentsMaxFlow > 0 && seg.flow === segmentsMaxFlow ? 'is-peak' : '']"
                  :style="{ '--seg-bar-w': segmentBarPercent(seg.flow) }"
                >
                  <span class="col-name">
                    <span v-if="segmentsMaxFlow > 0 && seg.flow === segmentsMaxFlow" class="peak-tag">峰值</span>
                    {{ seg.name }}
                  </span>
                  <span class="col-flow">{{ seg.flow.toLocaleString() }}</span>
                  <span class="col-load">
                    <span :class="['load-indicator', seg.loadRate >= 70 ? 'high' : seg.loadRate >= 45 ? 'medium' : 'low']">{{ seg.loadRate }}%</span>
                  </span>
                </div>
                <div v-if="!routeSegments.length" class="pfa-empty">暂无断面数据</div>
              </div>
            </div>
          </section>

          <!-- ② 站点分时段乘降（折线 / 柱状可切换；图表点击即全屏） -->
          <section v-else-if="pfaLineSection === 'boarding'" class="pfa-section">
            <div class="section-header boarding-section-header">
              <span class="section-title">站点乘降客流（按所选时段）</span>
              <div class="section-actions chart-mode-actions">
                <div class="chart-type-selector" role="group" aria-label="站点乘降客流图表类型">
                  <button
                    v-for="type in ['line', 'bar', 'heatmap']"
                    :key="type"
                    type="button"
                    :class="['type-pill', boardingChartType === type ? 'active' : '']"
                    @click="boardingChartType = type"
                  >
                    {{ type === 'line' ? '折线图' : type === 'bar' ? '柱状图' : '热力图' }}
                  </button>
                </div>
              </div>
            </div>
            <div class="boarding-chart-stack">
              <div class="boarding-chart-panel">
                <div class="boarding-chart-title">
                  <span class="boarding-chart-hint">点击图表全屏</span>
                </div>
                <!-- 热力图并入图表类型切换：占用更高的容器避免被裁切 -->
                <template v-if="boardingChartType === 'heatmap'">
                  <div
                    v-if="boardingHeatmapData.hasData"
                    class="chart-container-wrapper boarding-heatmap-wrapper boarding-clickable-chart"
                    :style="{ height: `${boardingHeatmapPanelHeight}px` }"
                    title="点击全屏查看"
                    @click="openBoardingHeatmapFullscreen"
                  >
                    <el-auto-resizer class="chart_box">
                      <template #default="{ height, width }">
                        <VChart
                          v-if="width > 0 && height > 0"
                          class="boarding-heatmap-panel-chart"
                          :option="boardingHeatmapOption"
                          autoresize
                          :update-options="{ replaceMerge: ['series'], lazyUpdate: true }"
                        />
                      </template>
                    </el-auto-resizer>
                  </div>
                  <div v-else class="pfa-empty">当前时段暂无乘降数据</div>
                </template>
                <div
                  v-else
                  class="chart-container-wrapper boarding-clickable-chart"
                  title="点击全屏查看"
                  @click="openBoardingFullscreen"
                >
                  <el-auto-resizer class="chart_box">
                    <template #default="{ height, width }">
                      <VChart
                        v-if="width > 0 && height > 0"
                        class="boarding-alighting-bar-chart"
                        :option="boardingAlightingChartOption"
                        autoresize
                        :update-options="{ replaceMerge: ['series'], lazyUpdate: true }"
                      />
                    </template>
                  </el-auto-resizer>
                </div>
              </div>
            </div>
          </section>

          <!-- ④ 客流画像 -->
          <section v-else-if="pfaLineSection === 'demographics'" class="pfa-section">
            <div class="section-header">
              <span class="section-title">客流画像</span>
              <span v-if="demographicsRiderCount" class="pfa-section-meta">
                <span v-if="demographicsRiderCount">样本 {{ demographicsRiderCount.toLocaleString() }} 人</span>
              </span>
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
            <div
              class="transfer-analysis-layout"
              :style="{ '--transfer-chart-height': `${transferChartHeight}px` }"
            >
              <div class="transfer-chart-wrapper">
                <el-auto-resizer class="chart_box">
                  <template #default="{ height, width }">
                    <VChart
                      v-if="width > 0 && height > 0"
                      class="transfer-bar-chart"
                      :option="transferChartOption"
                      autoresize
                      :update-options="{ replaceMerge: ['series'], lazyUpdate: true }"
                    />
                  </template>
                </el-auto-resizer>
              </div>
              <div v-if="transferTableData.length" class="transfer-meta-list">
                <div class="transfer-meta-header">
                  <span>票价</span>
                  <span>发车间隔</span>
                </div>
                <div
                  v-for="item in transferTableData"
                  :key="item.key"
                  class="transfer-meta-row"
                >
                  <span>{{ item.fare }}</span>
                  <span>{{ item.headway }}</span>
                </div>
              </div>
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
            :key="item.lineName"
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

  <!-- 需求7：站间OD客流地图浮动图例（左下角，右上角齿轮可调色阶与阈值） -->
  <teleport to="body">
    <div v-if="showOdMapLegend" class="pfa-od-map-legend" aria-label="站间OD客流图例" @click.stop>
      <Transition name="popover-fade">
        <div
          v-if="showOdScalePopover"
          class="pfa-od-legend-popover"
          role="dialog"
          aria-modal="false"
          aria-label="站间OD客流色阶设置"
          @click.stop
        >
          <div class="pfa-od-legend-popover-title">站间OD客流色阶设置</div>
          <ColorScaleControl
            v-model="odFlowScale"
            legend-title="站间OD客流"
            :show-legend="false"
          />
        </div>
      </Transition>
      <div class="pfa-od-legend-head">
        <div class="pfa-od-legend-title">站间OD客流（人次）</div>
        <button
          type="button"
          class="pfa-od-legend-gear"
          title="设置站间OD客流色阶"
          aria-label="设置站间OD客流色阶"
          :aria-expanded="showOdScalePopover"
          @click.stop="showOdScalePopover = !showOdScalePopover"
        >
          <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="3"></circle>
            <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h.01a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h.01a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v.01a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
          </svg>
        </button>
      </div>
      <div v-for="(item, index) in odLegendItems" :key="index" class="pfa-od-legend-item">
        <span class="pfa-od-legend-swatch" :style="{ background: item.color }"></span>
        <span class="pfa-od-legend-label">{{ item.label }}</span>
      </div>
      <div class="pfa-od-legend-dirs">
        <span><i class="pfa-od-dir-dot up"></i>上行站点</span>
        <span><i class="pfa-od-dir-dot down"></i>下行站点</span>
      </div>
    </div>
  </teleport>

  <el-dialog
    v-model="boardingFullscreenVisible"
    class="boarding-fullscreen-dialog"
    fullscreen
    append-to-body
    destroy-on-close
    :lock-scroll="true"
  >
    <template #header>
      <div class="boarding-fullscreen-header">
        <div>
          <div class="boarding-fullscreen-kicker">站点乘降客流</div>
          <div class="boarding-fullscreen-title">
            {{ pfaRouteTitle }}
          </div>
        </div>
        <span class="boarding-fullscreen-meta">{{ formatHourLabel(segmentTimeRange[0]) }}-{{ formatHourLabel(segmentTimeRange[1]) }} · {{ segmentTimeUnit }}min</span>
      </div>
    </template>
    <div class="boarding-fullscreen-body">
      <section class="boarding-fullscreen-panel">
        <VChart
          class="boarding-fullscreen-chart"
          :option="boardingFullscreenChartOption"
          autoresize
          :update-options="{ replaceMerge: ['series'], lazyUpdate: true }"
        />
      </section>
    </div>
  </el-dialog>

  <el-dialog
    v-model="boardingHeatmapVisible"
    class="boarding-heatmap-dialog"
    modal-class="boarding-heatmap-overlay"
    fullscreen
    append-to-body
    destroy-on-close
    :lock-scroll="true"
  >
    <template #header>
      <div class="boarding-heatmap-header">
        <div>
          <div class="boarding-heatmap-kicker">乘降热力图</div>
          <div class="boarding-heatmap-title">{{ pfaRouteTitle }}</div>
        </div>
        <span class="boarding-heatmap-meta">
          {{ formatHourLabel(segmentTimeRange[0]) }} - {{ formatHourLabel(segmentTimeRange[1]) }} · {{ segmentTimeUnit }}min · 站点 × 时段（乘+降）
        </span>
      </div>
    </template>
    <div class="boarding-heatmap-body">
      <VChart
        v-if="boardingHeatmapData.hasData"
        class="boarding-heatmap-chart"
        :option="boardingHeatmapOption"
        autoresize
        :update-options="{ replaceMerge: ['series'], lazyUpdate: true }"
      />
      <el-empty v-else description="当前时段暂无乘降数据" />
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, shallowRef, onMounted, onUnmounted, watch, inject, computed, getCurrentInstance, nextTick, unref, markRaw } from "vue";
import { VChart } from "@/plugins/echarts";
import { Search, Location, Download } from "@element-plus/icons-vue";
import { saveAs } from "file-saver";
import { getRouteDetail, getRoutePanelDetail, getRouteTileBinary } from "@/api/route";
import { abortOtherModelDataRequests, getCachedLineAll, getCachedRoutePanel, getModelDerived, getModelScopedMap, setScopedWithLimit } from "@/utils/modelDataCache.js";
import MCard from "./MCard.vue";
import MCard2 from "./MCard2.vue";
import ColorScaleControl from "./ColorScaleControl.vue";
import { RouteLayer } from "../layers/RouteLayer.js";
import { emptyFeatureCollection, stationsToFeatureCollection } from "../layers/maplibreLayerUtils.js";
import { buildPassengerProfileGroups, passengerProfileRiderCount } from "../utils/passengerProfile.js";
import { buildFlowCurveFeatureCollection, emptyFlowCurveCollection } from "../utils/flowCurves.js";
import { compareZh, createDebouncedMirror, isCanceledRequest } from "../utils/panelShared.js";
import { provisionalRouteLinks } from "../utils/routeGeometry.js";
import { buildValueLegendItems, classifyByBreaks, createColorScaleConfig, quantileBreaks, resolveColorScale } from "@/utils/colorSchemes.js";
import { MAP_THEME, hexNumber } from "@/utils/mapTheme.js";
import { PURE_METRO_LINE, isMetroLine, metroLineCanonicalName, metroLineNumber } from "@/utils/transitMode.js";
import { webMercatorToLngLat } from "@/mymap/index.js";
import { injectSync } from "@/utils";

const props = defineProps({
  model: String,
});

const loading = ref(true);
const searchMode = ref("line"); // "line" | "station"
// 全量线路数据体量大且所有赋值点均为整值替换，shallowRef 避免深层代理开销
const rawLines = shallowRef([]);
// rawLines 当前归属的模型：模型切换瞬间 rawLines 仍是旧数据，
// 派生缓存以它为键，避免把旧模型的选项写进新模型的缓存
let rawLinesModel = "";
// 线路详情缓存下沉到模型级作用域：重挂载/切 tab 后仍命中，随模型 LRU 淘汰且模型间天然隔离
const routeDetailCache = computed(() => getModelScopedMap(props.model, "xlzl:routeDetail"));
// routeDetailCache 是非响应式 Map：写入处 bump 版本号、读取它的 computed 显式依赖版本号，
// 使缓存写入能直接触发派生重算，不再依赖 selectedRouteDetail 赋值的旁路兜底
const routeDetailCacheVersion = shallowRef(0);
function writeRouteDetailCache(key, detail) {
  setScopedWithLimit(routeDetailCache.value, key, detail, 80);
  routeDetailCacheVersion.value += 1;
  return detail;
}
const routePanelDetailCache = new Map();
// 缓存值 markRaw：面板是只读大对象，防止后续被响应式 ref 深层代理
function cachePanelDetail(key, panel) {
  if (panel) routePanelDetailCache.set(key, markRaw(panel));
  return panel;
}
const routePanelDetailPromises = new Map();
const routePanelData = shallowRef(null);
// 面板含 stationFlows/segments 等大数组且所有赋值点均为整值替换：深层 ref 会让
// 时段聚合热循环里的每次数组访问都经 Proxy，用 shallowRef 消除代理开销
const selectedRoutePanel = shallowRef(null);
const selectedReverseRouteDetail = shallowRef(null);
const selectedReverseRoutePanel = shallowRef(null);
let routePanelPromise = null;
let selectionAbortController = null;
let selectionRequestSeq = 0;
const routeFlowMapCache = new Map();
const ROUTE_FLOW_MAP_CACHE_LIMIT = 24;
// 卸载标志：injectSync 等异步回调在组件销毁后不得再挂图层
let isComponentUnmounted = false;

const selectedLineName = ref("");
const selectedStationName = ref("");
const activeRouteId = ref("");
const activeMatchedRouteId = ref("");
const matchedRoutes = shallowRef([]);
// 线路详情（含全部 links）体量大且所有赋值点均为整值替换，用 shallowRef 避免深层代理
const selectedRouteDetail = shallowRef(null);

function nextSelectionSignal() {
  selectionAbortController?.abort();
  selectionAbortController = typeof AbortController !== "undefined" ? new AbortController() : null;
  selectionRequestSeq += 1;
  return { seq: selectionRequestSeq, signal: selectionAbortController?.signal };
}

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
// 客流分析：当前激活的子功能（右侧只显示对应统计）segments/boarding/demographics/transfer
const pfaLineSection = inject("pfaLineSection", ref("segments"));
const runMonitorSelectedLinePanel = inject("runMonitorSelectedLinePanel", null);
const runMonitorSelectedLineName = inject("runMonitorSelectedLineName", null);
const runMonitorSelectedRouteDetail = inject("runMonitorSelectedRouteDetail", null);
const runMonitorSelectedRouteMapLinks = inject("runMonitorSelectedRouteMapLinks", null);
// 当前方向每个站点的断面客流（取相邻断面的较大值），供地图空心圈描边取与线一致的分档色
const runMonitorSelectedRouteStationFlows = inject("runMonitorSelectedRouteStationFlows", null);
const runMonitorSelectedReverseLinePanel = inject("runMonitorSelectedReverseLinePanel", null);
const runMonitorSelectedReverseRouteDetail = inject("runMonitorSelectedReverseRouteDetail", null);
const runMonitorSelectedReverseRouteMapLinks = inject("runMonitorSelectedReverseRouteMapLinks", null);
const runMonitorLineOptionFilter = inject("runMonitorLineOptionFilter", () => true);
const runMonitorStationOptionFilter = inject("runMonitorStationOptionFilter", () => true);
const shouldRenderPfaRightPanel = computed(() => Boolean(unref(pfaRightPanel)));
const shouldLoadSelectedRoutePanel = computed(() => runMonitorSimplifiedRight || shouldRenderPfaRightPanel.value);

// 统一的当前选中路线计算属性
const currentSelectedRoute = computed(() => {
  // 显式依赖缓存版本：routeDetailCache（非响应式 Map）写入后本 computed 才能重算
  routeDetailCacheVersion.value;
  const targetId = searchMode.value === "line" ? activeRouteId.value : activeMatchedRouteId.value;
  if (!targetId) return null;
  if (selectedRouteDetail.value && routeMatchesKey(selectedRouteDetail.value, targetId)) return selectedRouteDetail.value;
  if (routeDetailCache.value.has(String(targetId))) return routeDetailCache.value.get(String(targetId));
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
  return rawLineIndexes.value.routeByKey.get(String(targetId))
    || rawLineIndexes.value.routeById.get(String(targetId))
    || null;
});

// 右侧窄面板最多显示约这么多站名，其余站点仍参与图表统计。
const BOARDING_LABEL_TARGET = 10;

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
  return routePanelFromPayload(routePanelData.value?.routes, route);
});

// 需求12：单方向线路选中时，从 panel.lineGroups 找该线路的上下行合并组
// （公交组 "bus::"+lineId；地铁方向回退到既有地铁聚合组键）。找不到则为 null，统计回退单方向 panel。
const mergedLineGroupPanel = computed(() => {
  const route = currentSelectedRoute.value;
  if (!route || route.lineGroup) return null;
  const groups = routePanelData.value?.lineGroups;
  if (!groups || typeof groups !== "object") return null;
  const lineId = String(route.lineId || "");
  const busPanel = lineId ? groups[`bus::${lineId}`] : null;
  if (busPanel) return busPanel;
  const line = (lineId ? rawLineIndexes.value.lineById.get(lineId) : null)
    || linesForDisplayName(selectedLineName.value || route.lineName)[0];
  if (line && isMetroLine(line)) {
    return groups[lineGroupKey(line)] || null;
  }
  return null;
});

// 统计类内容（指标 metrics / 客流画像 / 日客流量 / 关联线路）优先用全线合并口径；
// 方向敏感内容（站点乘降、断面、站间OD）仍用 currentRoutePanel（单方向）。
const statsPanel = computed(() => mergedLineGroupPanel.value || currentRoutePanel.value);
// 需求1：右侧面板标题只显示纯线路名（lineName 为空才回退 lineId）。
const pfaRouteTitle = computed(() => {
  const route = currentSelectedRoute.value || {};
  const name = String(
    route.lineName
    || route.info?.lineName
    || route.rawLineName
    || selectedLineName.value
    || ""
  ).replace(/[（(][^（）()]*[）)]\s*$/, "").trim();
  if (name) return name;
  const lineId = String(route.lineId || "").trim();
  return lineId || "线路客流分析";
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

// 整包 routes 的 routeId→panel 索引：按模型只建一次，兜底查找从 O(全网路线数) 降为 O(1)。
// routePanelData 仅在 props.model 匹配时写入（见 ensureRoutePanelData），索引与模型键天然一致。
function panelRouteIdIndex(routes) {
  return getModelDerived(props.model, "xlzl:panelRouteIndex", () => {
    const index = new Map();
    Object.values(routes || {}).forEach((item) => {
      const routeId = String(item?.routeId || "");
      if (!routeId) return;
      const list = index.get(routeId);
      if (list) list.push(item);
      else index.set(routeId, [item]);
    });
    return index;
  });
}

function findRoutePanelPayload(routes = {}, route = null) {
  if (!route || !routes || typeof routes !== "object") return null;
  const routeId = String(route.routeId || "");
  if (!routeId) return null;
  const lineId = String(route.lineId || "");
  // 只对整包 payload 建模型级索引；其余对象（含数据未就绪的空默认值）走原线性查找，
  // 避免把空索引写进模型缓存造成后续永久 miss
  const candidates = routes === routePanelData.value?.routes
    ? (panelRouteIdIndex(routes).get(routeId) || [])
    : Object.values(routes);
  return candidates.find((item) => (
    item
    && String(item.routeId || "") === routeId
    && (!lineId || String(item.lineId || "") === lineId)
  )) || null;
}

function routePanelFromPayload(routes = {}, route = null) {
  if (!route || !routes || typeof routes !== "object") return null;
  const key = routeUniqueKey(route);
  const routeId = String(route.routeId || "");
  const lineId = String(route.lineId || "");
  return routes[key]
    || findRoutePanelPayload(routes, route)
    || (!lineId && routeId ? routes[routeId] : null)
    || null;
}

// 地铁/公交制式判别已抽取到 @/utils/transitMode.js（与线网底图共用同一套口径）

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

function addLineIndex(map, key, line) {
  const text = normalizeLineSearchName(key);
  if (!text) return;
  const list = map.get(text);
  if (list) list.push(line);
  else map.set(text, [line]);
}

function buildRawLineIndexes(lines = []) {
  const linesByName = new Map();
  const lineById = new Map();
  const routeByKey = new Map();
  const routeById = new Map();
  for (const line of Array.isArray(lines) ? lines : []) {
    const lineId = String(line?.lineId || "");
    if (lineId && !lineById.has(lineId)) lineById.set(lineId, line);
    addLineIndex(linesByName, lineDisplayName(line), line);
    addLineIndex(linesByName, line.lineName, line);
    addLineIndex(linesByName, line.lineId, line);
    for (const route of Array.isArray(line?.routes) ? line.routes : []) {
      const item = withLineMeta(route, line);
      const key = routeUniqueKey(item);
      if (key) routeByKey.set(key, item);
      const routeId = String(item.routeId || "");
      if (routeId && !routeById.has(routeId)) routeById.set(routeId, item);
    }
  }
  return { linesByName, lineById, routeByKey, routeById };
}

const rawLineIndexes = computed(() => {
  const lines = rawLines.value;
  // 索引只依赖模型线路摘要，按模型缓存后 tab 往返/组件重挂载无需重复构建。
  return rawLinesModel && lines.length
    ? getModelDerived(rawLinesModel, "xlzl:rawLineIndexes", () => buildRawLineIndexes(lines))
    : buildRawLineIndexes(lines);
});

function linesForDisplayName(displayName) {
  const target = normalizeLineSearchName(displayName);
  if (!target) return [];
  return rawLineIndexes.value.linesByName.get(target) || [];
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

// 乘降图/全屏图/热力图/导出共享同一份物理站点客流索引，避免各消费点重复重建 Map
const currentStationFlowLookup = computed(() => stationFlowLookup(currentRoutePanel.value?.stationFlows));

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

function routeLinkKey(link = {}) {
  const id = String(link?.linkId || link?.id || "");
  if (id) return id;
  const from = link?.from || {};
  const to = link?.to || {};
  return [
    Number(from.x).toFixed(2),
    Number(from.y).toFixed(2),
    Number(to.x).toFixed(2),
    Number(to.y).toFixed(2),
  ].join(":");
}

function uniqueRouteLinks(routes = []) {
  const seen = new Set();
  const links = [];
  routes.forEach((route) => {
    (Array.isArray(route?.links) ? route.links : []).forEach((link) => {
      if (!link?.from || !link?.to) return;
      const key = routeLinkKey(link);
      if (!key || seen.has(key)) return;
      seen.add(key);
      links.push(link);
    });
  });
  return links;
}

function routeWithCachedDetail(route = {}) {
  // 显式依赖缓存版本：经由本函数读缓存的 computed（整线组等）随写入自动失效
  routeDetailCacheVersion.value;
  const key = routeUniqueKey(route);
  const cached = key ? routeDetailCache.value.get(key) : null;
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
    links: uniqueRouteLinks(routes),
    facilities: uniqueFacilities(routes),
    childRoutes: routes,
  };
}

// 小时数据按重叠比例分摊到子小时时段，[startHour, endHour) 左闭右开。
// 全组件统一此口径（与热力图一致）：滑块 [8,18] 即统计 08:00–18:00，
// 15/30min 单位下的亚小时边界真实生效
function sumHourRangeProportional(values, startHour, endHour) {
  if (!Array.isArray(values)) return 0;
  const t0 = Math.max(0, Number(startHour) || 0);
  const t1 = Math.min(24, Number(endHour) || 0);
  let total = 0;
  for (let hour = Math.floor(t0); hour < t1; hour++) {
    const overlap = Math.min(hour + 1, t1) - Math.max(hour, t0);
    if (overlap > 0) total += toFiniteNumber(values[hour], 0) * overlap;
  }
  return total;
}

// 按重叠比例加权的小时均值（满载率等率值用），与 sumHourRangeProportional 同一 [start, end) 口径
function averageHourRangeProportional(values, startHour, endHour) {
  if (!Array.isArray(values)) return 0;
  const t0 = Math.max(0, Number(startHour) || 0);
  const t1 = Math.min(24, Number(endHour) || 0);
  let total = 0;
  let weight = 0;
  for (let hour = Math.floor(t0); hour < t1; hour++) {
    const overlap = Math.min(hour + 1, t1) - Math.max(hour, t0);
    if (overlap <= 0) continue;
    const value = Number(values[hour]);
    if (Number.isFinite(value)) {
      total += value * overlap;
      weight += overlap;
    }
  }
  return weight > 0 ? total / weight : 0;
}

const routeMetrics = computed(() => {
  const route = currentSelectedRoute.value || {};
  const info = route.info || {};
  // 需求12：指标优先取全线（上下行合并）组的 metrics
  const panelMetrics = statsPanel.value?.metrics || {};
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

// 需求12：客流画像优先取全线（上下行合并）组的 demographics
const demographicsGroups = computed(() => {
  return buildPassengerProfileGroups(statsPanel.value?.demographics || {});
});
const demographicsRiderCount = computed(() =>
  passengerProfileRiderCount(statsPanel.value?.demographics || {})
);

function verticalStationLabel(value) {
  return Array.from(String(value || "")).join("\n");
}

function stationLabelInterval(total, showAll, target = BOARDING_LABEL_TARGET) {
  if (showAll || total <= target) return 0;
  const step = Math.max(1, Math.ceil(total / target));
  return (index) => index === 0 || index === total - 1 || index % step === 0;
}

function buildBoardingAlightingChartOption({ route = null, panel = null, fullscreen = false, compact = false } = {}) {
  const targetRoute = route || currentSelectedRoute.value || {};
  const targetPanel = panel || currentRoutePanel.value || {};
  const facilities = targetRoute.facilities || [];

  const stationNames = facilities.map(f => f.facilityName || "");
  const startHour = debouncedSegmentTimeRange.value[0];
  const endHour = debouncedSegmentTimeRange.value[1];
  const stationFlowMap = targetPanel === currentRoutePanel.value
    ? currentStationFlowLookup.value
    : stationFlowLookup(targetPanel?.stationFlows);
  const boardingData = facilities.map((fac) => {
    const flow = stationFlowMap.get(physicalStationKey(fac));
    return Math.round(sumHourRangeProportional(flow?.boardingByHour, startHour, endHour));
  });
  const alightingData = facilities.map((fac) => {
    const flow = stationFlowMap.get(physicalStationKey(fac));
    return -Math.round(sumHourRangeProportional(flow?.alightingByHour, startHour, endHour));
  });

  const isBar = boardingChartType.value === "bar";
  const finiteBoarding = boardingData.filter(v => Number.isFinite(v));
  const finiteAlighting = alightingData.filter(v => Number.isFinite(v));
  const yMax = Math.max(1, ...finiteBoarding);
  const yMin = Math.min(0, ...finiteAlighting);

  // 上车=增(绿) / 下车=离(红)：锁到全局 add/delete 语义色（与满载率徽标、其余面板同源），
  // 渐变顶部略提亮、底部落在 token 本色，保留立体感又不偏离主色板
  const boardingColor = {
    type: "linear", x: 0, y: 0, x2: 0, y2: 1,
    colorStops: [{ offset: 0, color: "#25a453" }, { offset: 1, color: "#1a8a3f" }]
  };
  const alightingColor = {
    type: "linear", x: 0, y: 0, x2: 0, y2: 1,
    colorStops: [{ offset: 0, color: "#d83a2c" }, { offset: 1, color: "#c4291c" }]
  };

  const makeXAxis = (data) => ({
    type: "category",
    data,
    axisLine: { lineStyle: { color: "rgba(17, 32, 58, 0.12)" } },
    axisTick: { alignWithLabel: true, lineStyle: { color: "rgba(17, 32, 58, 0.1)" } },
    axisLabel: {
      color: "#667085",
      fontSize: fullscreen ? 11 : compact ? 9 : 10,
      lineHeight: fullscreen ? 13 : compact ? 10 : 12,
      interval: stationLabelInterval(data.length, fullscreen, compact ? 7 : BOARDING_LABEL_TARGET),
      rotate: 0,
      formatter: verticalStationLabel,
      hideOverlap: false,
      margin: fullscreen ? 12 : compact ? 6 : 9
    }
  });

  const makeYAxis = () => ({
    type: "value",
    min: yMin,
    max: yMax,
    minInterval: 1,
    axisLine: { show: false },
    axisTick: { show: false },
    splitLine: { lineStyle: { color: "rgba(17, 32, 58, 0.07)", type: "dashed" } },
    axisLabel: {
      color: "#667085",
      fontSize: 10,
      formatter: (value) => Math.round(Math.abs(Number(value) || 0))
    }
  });

  const makeSeries = (name, color, data, stackId, radius) => ({
    // id 绑定图表类型：replaceMerge 按 id 映射，折线/柱状切换时旧 series 整体删除重建，
    // 避免 merge 残留旧 stack/lineStyle
    id: `${name}-${boardingChartType.value}`,
    name,
    type: boardingChartType.value,
    stack: isBar ? stackId : undefined,
    smooth: !isBar,
    symbol: "circle",
    symbolSize: fullscreen ? 7 : 6,
    barWidth: fullscreen ? "42%" : "40%",
    itemStyle: { color, borderRadius: radius },
    lineStyle: !isBar ? { width: fullscreen ? 3 : 2.4 } : undefined,
    emphasis: { focus: "series" },
    data
  });

  return {
    backgroundColor: "transparent",
    // 拖动时段滑块期间关闭入场动画：防抖镜像连续落值时动画反复重启是主要绘制开销
    animationDuration: segmentTimeRangeDragging.value ? 0 : (fullscreen ? 420 : 280),
    animationEasing: "quarticOut",
    // 浅色磨砂 tooltip：与总体客流 / 断面 / 体检等其余图表统一（原为深色 slate，是全局唯一的异类）
    tooltip: {
      trigger: "axis",
      appendToBody: true,
      axisPointer: { type: isBar ? "shadow" : "line", lineStyle: { color: "rgba(17, 32, 58, 0.18)", width: 1 } },
      backgroundColor: "rgba(255, 255, 255, 0.98)",
      borderColor: "rgba(17, 32, 58, 0.1)",
      borderWidth: 1,
      padding: [8, 11],
      extraCssText: "border-radius:10px;box-shadow:0 12px 32px -14px rgba(13,38,76,0.34);",
      textStyle: { color: "#1c2024", fontSize: 12 },
      formatter: (params) => {
        const stationName = params[0].name;
        let html = `<div style="font-weight: 700; margin-bottom: 4px;">${stationName}</div>`;
        params.forEach(p => {
          const val = Math.round(Math.abs(Number(p.value) || 0));
          html += `<div style="display: flex; align-items: center; justify-content: space-between; gap: 16px;">
            <div style="display: flex; align-items: center;">
              <span style="display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: ${p.color}; margin-right: 6px;"></span>
              <span style="color:#667085;">${p.seriesName}</span>
            </div>
            <span style="font-weight: 700; font-variant-numeric: tabular-nums;">${val.toLocaleString()} 人次</span>
          </div>`;
        });
        return html;
      }
    },
    legend: {
      data: ["上车人数", "下车人数"],
      textStyle: { color: "#667085", fontSize: 11 },
      top: fullscreen ? 18 : 0,
      itemWidth: 11,
      itemHeight: 11,
      icon: "roundRect"
    },
    grid: {
      left: fullscreen ? 56 : "3%",
      right: fullscreen ? 36 : "4%",
      top: fullscreen ? 68 : compact ? 28 : 36,
      bottom: fullscreen ? 150 : compact ? 76 : 112,
      containLabel: false
    },
    xAxis: makeXAxis(stationNames),
    yAxis: makeYAxis(),
    series: [
      makeSeries("上车人数", boardingColor, boardingData, "Total", [4, 4, 0, 0]),
      makeSeries("下车人数", alightingColor, alightingData, "Total", [0, 0, 4, 4])
    ]
  };
}

function routeDirectionText(route = {}) {
  if (route?.lineGroup || isMetroSelection.value) return "全线客流";
  const facilities = Array.isArray(route?.facilities) ? route.facilities : [];
  const start = facilities[0]?.facilityName || "";
  const end = facilities[facilities.length - 1]?.facilityName || "";
  return start && end ? `${start}-${end}方向客流` : route?.routeName || route?.lineName || "";
}

// 乘降图表只画当前方向（方向切换在右侧面板顶部），点击图表进入全屏
const boardingAlightingChartOption = computed(() => buildBoardingAlightingChartOption({
  route: currentSelectedRoute.value,
  panel: currentRoutePanel.value,
}));

const boardingFullscreenChartOption = computed(() => buildBoardingAlightingChartOption({
  route: currentSelectedRoute.value,
  panel: currentRoutePanel.value,
  fullscreen: true,
}));

function openBoardingFullscreen() {
  if (!currentSelectedRoute.value?.facilities?.length) return;
  boardingFullscreenVisible.value = true;
}

// —— 站点乘降热力图（图表类型切换项之一，跟随当前方向；点击图表全屏）——
// 横轴=该方向线路站点（按站序），纵轴=统计时段（按统计时间单位分桶），格值=乘+降人数。
const boardingHeatmapVisible = ref(false);

function openBoardingHeatmapFullscreen() {
  if (!boardingHeatmapData.value.hasData) return;
  boardingHeatmapVisible.value = true;
}

// 内嵌热力图高度随时段桶数自适应（每行约 24px + 轴/色条边距），保证图不被裁切
const boardingHeatmapPanelHeight = computed(() => {
  const rows = boardingHeatmapData.value.bucketLabels?.length || 0;
  return Math.max(280, Math.min(680, rows * 24 + 130));
});

// 热力图矩阵：x=站点，y=统计时段（按统计时间单位分桶），值=乘+降
const boardingHeatmapData = computed(() => {
  const route = currentSelectedRoute.value || {};
  const panel = currentRoutePanel.value;
  const facilities = Array.isArray(route.facilities) ? route.facilities : [];
  const stationNames = facilities.map((fac, index) => fac?.facilityName || `站点${index + 1}`);
  // 与其他重计算一致消费 180ms 防抖镜像：拖动滑块期间不逐档重建热力矩阵
  const [startHour, endHour] = debouncedSegmentTimeRange.value;
  const unitHours = Math.max(0.25, (Number(segmentTimeUnit.value) || 60) / 60);
  const stationFlowMap = currentStationFlowLookup.value;

  const buckets = [];
  for (let t = startHour; t < endHour - 1e-9; t += unitHours) {
    const tEnd = Math.min(endHour, t + unitHours);
    buckets.push({ start: t, end: tEnd, label: `${formatHourLabel(t)}-${formatHourLabel(tEnd)}` });
  }

  const cells = [];
  let maxCellFlow = 0;
  let totalFlow = 0;
  facilities.forEach((fac, xIndex) => {
    const flow = stationFlowMap.get(physicalStationKey(fac));
    buckets.forEach((bucket, yIndex) => {
      const boarding = Math.round(sumHourRangeProportional(flow?.boardingByHour, bucket.start, bucket.end));
      const alighting = Math.round(sumHourRangeProportional(flow?.alightingByHour, bucket.start, bucket.end));
      const total = boarding + alighting;
      cells.push({ value: [xIndex, yIndex, total], boarding, alighting });
      maxCellFlow = Math.max(maxCellFlow, total);
      totalFlow += total;
    });
  });
  return {
    stationNames,
    bucketLabels: buckets.map((bucket) => bucket.label),
    cells,
    maxCellFlow,
    directionText: routeDirectionText(route),
    hasData: Boolean(panel) && cells.length > 0 && totalFlow > 0,
  };
});

const boardingHeatmapOption = computed(() => {
  const { stationNames, bucketLabels, cells, maxCellFlow } = boardingHeatmapData.value;
  const manyStations = stationNames.length > 8;
  const safeMax = Math.max(1, maxCellFlow);
  return {
    backgroundColor: "transparent",
    // 拖动时段滑块期间关闭动画，避免连续 setOption 反复重启入场动画（同乘降图）
    animationDuration: segmentTimeRangeDragging.value ? 0 : 280,
    animationEasing: "quarticOut",
    tooltip: {
      position: "top",
      appendToBody: true,
      backgroundColor: "rgba(255, 255, 255, 0.98)",
      borderColor: "rgba(17, 32, 58, 0.1)",
      borderWidth: 1,
      padding: [8, 11],
      extraCssText: "border-radius:10px;box-shadow:0 12px 32px -14px rgba(13,38,76,0.34);",
      textStyle: { color: "#1c2024", fontSize: 12 },
      formatter: (params) => {
        const [xIndex, yIndex, flow] = params?.value || [];
        const boarding = toFiniteNumber(params?.data?.boarding, 0);
        const alighting = toFiniteNumber(params?.data?.alighting, 0);
        return `<div style="font-weight: 700; margin-bottom: 4px;">${stationNames[xIndex] || "未知站点"} · ${bucketLabels[yIndex] || ""}</div>
          <div style="color:#667085;">乘 ${boarding.toLocaleString()} · 降 ${alighting.toLocaleString()}</div>
          <div style="text-align: right; font-weight: 700; font-variant-numeric: tabular-nums;">合计 ${toFiniteNumber(flow, 0).toLocaleString()} 人次</div>`;
      }
    },
    // 矩阵风格：右侧竖向色条，格子间白色描边
    grid: {
      left: "2%",
      right: 58,
      top: 8,
      bottom: 8,
      containLabel: true
    },
    xAxis: {
      type: "category",
      data: stationNames,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: {
        color: "#667085",
        fontSize: 11,
        interval: 0,
        rotate: manyStations ? 45 : 0
      }
    },
    yAxis: {
      type: "category",
      data: bucketLabels,
      inverse: true, // 早时段在上
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: "#667085", fontSize: 11 }
    },
    visualMap: {
      type: "continuous",
      min: 0,
      max: safeMax,
      calculable: true,
      orient: "vertical",
      right: 0,
      top: "middle",
      itemWidth: 14,
      itemHeight: 220,
      inRange: {
        // 绿(少)→黄→红(多)，与满载率配色语义一致
        color: ["#1a9850", "#66bd63", "#a6d96a", "#d9ef8b", "#fee08b", "#fdae61", "#f46d43", "#d73027"]
      },
      textStyle: { color: "#667085", fontSize: 11 }
    },
    series: [
      {
        name: "站点乘降热力",
        type: "heatmap",
        // 两端深色格子（深绿/深红）改白色数值，中段浅色格子用深色数值
        data: cells.map((cell) => {
          const ratio = cell.value[2] / safeMax;
          return ratio <= 0.18 || ratio >= 0.68
            ? { ...cell, label: { color: "#ffffff" } }
            : cell;
        }),
        label: {
          show: cells.length <= 160,
          color: "#3f4a3f",
          fontSize: 10,
          formatter: (params) => toFiniteNumber(params?.value?.[2], 0).toLocaleString()
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

function transferLineKey(name = "") {
  return normalizeLineSearchName(name) || String(name || "").trim();
}

function routeTransferStationNames() {
  const names = new Set();
  const addRoute = (route) => {
    (Array.isArray(route?.facilities) ? route.facilities : []).forEach((fac) => {
      const name = String(fac?.facilityName || "").trim();
      if (name) names.add(name);
    });
  };
  addRoute(currentSelectedRoute.value);
  addRoute(selectedReverseRouteDetail.value || findReverseRoute(currentSelectedRoute.value));
  return names;
}

function lineTouchesTransferStations(line = {}, stationNames = new Set()) {
  if (!stationNames.size) return false;
  return (Array.isArray(line?.routes) ? line.routes : []).some((route) =>
    (Array.isArray(route?.facilities) ? route.facilities : []).some((fac) =>
      stationNames.has(String(fac?.facilityName || "").trim())
    )
  );
}

// 基础行集只依赖选中线路与线网数据（与统计时段无关）：独立 computed 缓存全网扫描结果，
// 拖动时段滑块时不再逐次重扫 rawLines × routes × facilities
const transferBaseLineRows = computed(() => {
  const stationNames = routeTransferStationNames();
  const currentLineKey = transferLineKey(currentSelectedRoute.value?.lineName || selectedLineName.value);
  const rows = [];
  const seen = new Set();
  rawLines.value.forEach((line) => {
    if (!lineTouchesTransferStations(line, stationNames)) return;
    const name = lineDisplayName(line);
    const key = transferLineKey(name);
    if (!key || key === currentLineKey || seen.has(key)) return;
    seen.add(key);
    rows.push({ key, name });
  });
  return rows;
});

function addTransferPanelRows(rows, panel) {
  const startHour = debouncedSegmentTimeRange.value[0];
  const endHour = debouncedSegmentTimeRange.value[1];
  const currentLineKey = transferLineKey(currentSelectedRoute.value?.lineName || selectedLineName.value);
  (Array.isArray(panel?.transfers) ? panel.transfers : []).forEach((item) => {
    const name = item.lineName || item.lineId || "--";
    const key = transferLineKey(name);
    if (!key || key === currentLineKey) return;
    if (!rows.has(key)) {
      rows.set(key, {
        key,
        name,
        fare: "-",
        headway: "-",
        flow: 0,
        ratio: 0,
      });
    }
    const row = rows.get(key);
    row.flow += Math.round(sumHourRangeProportional(item.flowByHour, startHour, endHour));
  });
}

function buildTransferRowsForRange() {
  // 每次重建行 Map：基础行集是缓存的 computed，直接向其累加 flow 会污染缓存值
  const rows = new Map();
  transferBaseLineRows.value.forEach(({ key, name }) => {
    rows.set(key, { key, name, fare: "-", headway: "-", flow: 0, ratio: 0 });
  });
  // 需求12：关联线路优先用全线合并组的 transfers（已含上下行），无组时回退双方向累加
  const merged = mergedLineGroupPanel.value;
  if (merged && Array.isArray(merged.transfers) && merged.transfers.length) {
    addTransferPanelRows(rows, merged);
  } else {
    addTransferPanelRows(rows, currentRoutePanel.value);
    addTransferPanelRows(rows, selectedReverseRoutePanel.value);
  }
  const list = Array.from(rows.values());
  const total = list.reduce((sum, item) => sum + item.flow, 0);
  return list
    .map(item => ({
      ...item,
      ratio: total > 0 ? Number(((item.flow / total) * 100).toFixed(1)) : 0
    }))
    .sort((a, b) => b.flow - a.flow || compareZh(a.name, b.name));
}

// 换乘行数据收敛为单一 computed：图表/表格/高度共享一次全网扫描（原先 chart 与 table 各扫一遍）
const transferRows = computed(() => buildTransferRowsForRange());

const transferChartOption = computed(() => {
  const rows = transferRows.value;
  const transferLines = rows.map(item => item.name);
  const passengerData = rows.map(item => item.flow);
  
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
    // 像素级网格：顶部 32px 对齐右侧表头高度，每个类目带 28px 对齐表格行高，
    // 线路名列固定 90px 且文本左对齐到面板最左边，消除左侧留白
    grid: {
      left: 90,
      right: 12,
      top: 32,
      bottom: 0,
      containLabel: false
    },
    xAxis: {
      type: "value",
      position: "top",
      minInterval: 1,
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
        margin: 6,
        formatter: (value) => Math.round(Number(value) || 0)
      }
    },
    yAxis: {
      type: "category",
      data: transferLines,
      inverse: true,
      axisLine: {
        lineStyle: {
          color: "rgba(21, 105, 222, 0.15)"
        }
      },
      axisTick: {
        show: false
      },
      axisLabel: {
        color: "#64748b",
        fontSize: 11,
        // 锚点移到 x=0（margin 抵消 grid.left）且左对齐：线路名从面板最左边排起
        align: "left",
        margin: 90,
        width: 84,
        overflow: "truncate"
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

const transferTableData = computed(() => transferRows.value);

// 32px 表头带 + 每行 28px，与右侧票价/发车间隔表逐行对齐（同 transferChartOption 的像素网格）
const transferChartHeight = computed(() => {
  const rows = Math.max(transferTableData.value.length, 1);
  return rows * 28 + 32;
});

const { proxy } = getCurrentInstance() || {};
// 站点乘降图：折线 / 柱状切换
const boardingChartType = ref("bar");
const boardingFullscreenVisible = ref(false);

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

// 把当前选中线路的客流面板与名称上抛给 index.vue（运行监测简化卡片与客流分析地图/统计共用；
// 宿主未提供这些注入时值为 null，此处安全跳过）。
watch(
  [currentRoutePanel, currentSelectedRoute, selectedLineName],
  () => {
    if (!runMonitorSelectedLinePanel && !runMonitorSelectedLineName) return;
    if (runMonitorSelectedLinePanel) {
      runMonitorSelectedLinePanel.value = currentRoutePanel.value || null;
    }
    if (runMonitorSelectedLineName) {
      // 需求1：只上抛纯线路名（lineName 为空才回退 lineId），不再拼接（起点 - 终点）后缀
      const route = currentSelectedRoute.value || {};
      runMonitorSelectedLineName.value = String(
        selectedLineName.value
        || route.lineName
        || route.info?.lineName
        || route.rawLineName
        || route.lineId
        || ""
      ).replace(/[（(][^（）()]*[）)]\s*$/, "").trim();
    }
  },
  { immediate: true },
);

watch(selectedRouteDetail, (detail) => {
  if (runMonitorSelectedRouteDetail) {
    runMonitorSelectedRouteDetail.value = detail || null;
  }
});

// 反向详情/面板统一经 watch 回写父级：线路搜索与站点搜索两条选中路径口径一致
// （原先仅 handleSelectRoute 手工回写，站点搜索模式下父级的双向合计拿不到反向数据）
watch([selectedReverseRouteDetail, selectedReverseRoutePanel], ([detail, panel]) => {
  if (runMonitorSelectedReverseRouteDetail) runMonitorSelectedReverseRouteDetail.value = detail || null;
  if (runMonitorSelectedReverseLinePanel) runMonitorSelectedReverseLinePanel.value = panel || null;
});

// —— CSV 导出（项目无 xlsx 依赖，走已有的 file-saver）——
// 前置 UTF-8 BOM 保证 Excel 打开中文不乱码；含逗号/引号/换行的字段按 RFC 4180 转义
function csvEscape(value) {
  const text = String(value ?? "");
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

function exportCsvFile(rows, fileName) {
  const content = rows.map((row) => row.map(csvEscape).join(",")).join("\r\n");
  saveAs(new Blob(["\uFEFF", content], { type: "text/csv;charset=utf-8" }), fileName);
}

function handleExportLeaderboard() {
  const list = currentLeaderboard.value;
  if (!list.length) {
    proxy?.$message?.warning("暂无排行数据可导出");
    return;
  }
  const typeText = activeTransitType.value === "bus" ? "公交" : "地铁";
  exportCsvFile(
    [
      ["排名", "线路名称", "说明", "日均客流量(人次)"],
      ...list.map((item, index) => [index + 1, item.lineName, item.desc, item.passengerFlow]),
    ],
    `${typeText}线路客流排行.csv`,
  );
  proxy?.$message?.success(`${typeText}线路客流排行已导出 CSV 文件`);
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

const timeUnitOptions = [
  { label: "15min", value: 15 },
  { label: "30min", value: 30 },
  { label: "60min", value: 60 },
];
const segmentTimeUnit = ref(60);
const segmentTimeStep = computed(() => segmentTimeUnit.value / 60);
const segmentTimeRange = ref([8, 18]);
// 拖动时段滑块每跨一档都会触发「断面聚合→热力矩阵→换乘扫描→地图 links 重建→图表重绘」级联，
// 重计算统一消费 180ms 防抖镜像；滑块 v-model 与头部时段文案仍读原值保证即时反馈
const { debounced: debouncedSegmentTimeRange, cancel: cancelSegmentTimeMirror } = createDebouncedMirror(segmentTimeRange, 180);
// 拖动进行中（原值与镜像不一致）临时关闭图表动画，避免连环动画重启
const segmentTimeRangeDragging = computed(
  () => segmentTimeRange.value[0] !== debouncedSegmentTimeRange.value[0]
    || segmentTimeRange.value[1] !== debouncedSegmentTimeRange.value[1],
);

function formatHourLabel(hour) {
  const totalMinutes = Math.round((Number(hour) || 0) * 60);
  const safeMinutes = Math.max(0, Math.min(24 * 60 - 1, totalMinutes));
  const hours = Math.floor(safeMinutes / 60);
  const minutes = safeMinutes % 60;
  return `${String(hours).padStart(2, "0")}:${String(minutes).padStart(2, "0")}`;
}

function alignHourToUnit(hour, unitMinutes = segmentTimeUnit.value) {
  const stepMinutes = Math.max(15, Number(unitMinutes) || 60);
  const totalMinutes = Math.round((Number(hour) || 0) * 60);
  const alignedMinutes = Math.round(totalMinutes / stepMinutes) * stepMinutes;
  return Number((alignedMinutes / 60).toFixed(4));
}

watch(segmentTimeUnit, (unit) => {
  const minHour = 6;
  // 上限 23：[start, end) 口径下选到 23 才覆盖 22:00–23:00 的最后一个小时桶
  const maxHour = 23;
  const nextRange = segmentTimeRange.value.map((value) =>
    Math.max(minHour, Math.min(maxHour, alignHourToUnit(value, unit)))
  );
  if (nextRange[1] < nextRange[0]) nextRange[1] = nextRange[0];
  segmentTimeRange.value = nextRange;
});

// 按当前激活子节导出对应面板明细为 CSV
function handleExportDetail() {
  const section = pfaLineSection.value;
  const routeTitle = pfaRouteTitle.value || "线路";
  const startHour = segmentTimeRange.value[0];
  const endHour = segmentTimeRange.value[1];
  let label = "";
  let rows = [];
  if (section === "segments") {
    label = "断面客流";
    rows = [
      ["断面（相邻站点）", "客流量(人次)", "满载率(%)"],
      ...routeSegments.value.map((seg) => [seg.name, seg.flow, seg.loadRate]),
    ];
  } else if (section === "boarding") {
    label = "站点乘降";
    const facilities = Array.isArray(currentSelectedRoute.value?.facilities)
      ? currentSelectedRoute.value.facilities
      : [];
    const stationFlowMap = currentStationFlowLookup.value;
    rows = [
      ["站点", "上车人数(人次)", "下车人数(人次)"],
      ...facilities.map((fac) => {
        const flow = stationFlowMap.get(physicalStationKey(fac));
        return [
          fac?.facilityName || "",
          Math.round(sumHourRangeProportional(flow?.boardingByHour, startHour, endHour)),
          Math.round(sumHourRangeProportional(flow?.alightingByHour, startHour, endHour)),
        ];
      }),
    ];
  } else if (section === "demographics") {
    label = "客流画像";
    rows = [["分组", "项目", "占比(%)"]];
    demographicsGroups.value.forEach((group) => {
      group.items.forEach((item) => rows.push([group.title, item.label, item.value]));
    });
  } else {
    label = "关联换乘";
    rows = [
      ["关联线路", "换乘人数(人次)", "占比(%)", "票价", "发车间隔"],
      ...transferRows.value.map((item) => [item.name, item.flow, item.ratio, item.fare, item.headway]),
    ];
  }
  if (rows.length <= 1) {
    proxy?.$message?.warning("当前面板暂无数据可导出");
    return;
  }
  // 时段相关面板的文件名带统计时段（去掉冒号，避免非法文件名字符）
  const rangeText = `${formatHourLabel(startHour)}-${formatHourLabel(endHour)}`.replace(/:/g, "");
  const fileName = ["segments", "boarding", "transfer"].includes(section)
    ? `${routeTitle}_${label}_${rangeText}.csv`
    : `${routeTitle}_${label}.csv`;
  exportCsvFile(rows, fileName);
  proxy?.$message?.success(`${label}数据已导出 CSV 文件`);
}

// 原始断面：按服务模式（交路/区间车）逐条，保留方向与路线归属，供地图按子路线着色使用。
const rawRouteSegments = computed(() => {
  const startHour = debouncedSegmentTimeRange.value[0];
  const endHour = debouncedSegmentTimeRange.value[1];
  return (currentRoutePanel.value?.segments || []).map((segment) => ({
    name: segment.name,
    routeKey: String(segment.routeKey || ""),
    lineId: String(segment.lineId || ""),
    routeId: String(segment.routeId || ""),
    fromFacilityId: String(segment.fromFacilityId || ""),
    toFacilityId: String(segment.toFacilityId || ""),
    flow: Math.round(sumHourRangeProportional(segment.flowByHour, startHour, endHour)),
    loadRate: Number(averageHourRangeProportional(segment.loadRateByHour, startHour, endHour).toFixed(1))
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
  const startHour = debouncedSegmentTimeRange.value[0];
  const endHour = debouncedSegmentTimeRange.value[1];
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
      flow: Math.round(sumHourRangeProportional(peak.flowByHour, startHour, endHour)),
      loadRate: Number(averageHourRangeProportional(loadByHour, startHour, endHour).toFixed(1))
    };
  });
});

// 断面客流最大值：把断面表当成一张"断面客流断面图"，每行按该值归一化画条，
// 一眼看出峰值断面落在线路哪一段（断面客流分析的核心诉求）
const segmentsMaxFlow = computed(() =>
  routeSegments.value.reduce((max, seg) => (seg.flow > max ? seg.flow : max), 0)
);
function segmentBarPercent(flow) {
  const max = segmentsMaxFlow.value;
  return max > 0 ? `${Math.max(0, (flow / max) * 100)}%` : "0%";
}

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

function coordIndexKey(coord) {
  const x = Number(coord?.x);
  const y = Number(coord?.y);
  if (!Number.isFinite(x) || !Number.isFinite(y)) return "";
  return `${Math.round(x * 100)}:${Math.round(y * 100)}`;
}

function addEndpointIndex(index, coord, linkIndex) {
  const key = coordIndexKey(coord);
  if (!key) return;
  const list = index.get(key);
  if (list) list.push(linkIndex);
  else index.set(key, [linkIndex]);
}

function buildRouteLinkEndpointIndex(links = []) {
  const index = new Map();
  links.forEach((link, linkIndex) => {
    addEndpointIndex(index, link?.from, linkIndex);
    addEndpointIndex(index, link?.to, linkIndex);
  });
  return index;
}

function endpointRouteLinkIndex(endpointIndex, coord, startIndex = 0) {
  const list = endpointIndex.get(coordIndexKey(coord));
  if (!list?.length) return -1;
  const start = Math.max(0, Number(startIndex) || 0);
  return list.find((index) => index >= start) ?? list[list.length - 1];
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
  const endpointIndex = buildRouteLinkEndpointIndex(links);
  let mappedCount = 0;
  let cursor = 0;
  for (let i = 0; i + 1 < facilities.length; i++) {
    const fromFac = facilities[i];
    const toFac = facilities[i + 1];
    const fromCoord = fromFac?.coord || fromFac;
    const toCoord = toFac?.coord || toFac;
    let fromIndex = endpointRouteLinkIndex(endpointIndex, fromCoord, cursor);
    if (fromIndex < 0) fromIndex = nearestRouteLinkIndex(links, fromCoord, cursor);
    let toIndex = endpointRouteLinkIndex(endpointIndex, toCoord, Math.max(cursor, fromIndex));
    if (toIndex < 0) toIndex = nearestRouteLinkIndex(links, toCoord, Math.max(cursor, fromIndex));
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

function routeFlowMapCacheKey(route, segments = []) {
  const segmentList = Array.isArray(segments) ? segments : [];
  const routeKey = routeUniqueKey(route);
  let linkCount = Array.isArray(route?.links) ? route.links.length : 0;
  let facilityCount = Array.isArray(route?.facilities) ? route.facilities.length : 0;
  if (route?.lineGroup && Array.isArray(route.childRoutes)) {
    linkCount = 0;
    facilityCount = 0;
    route.childRoutes.forEach((child) => {
      linkCount += Array.isArray(child?.links) ? child.links.length : 0;
      facilityCount += Array.isArray(child?.facilities) ? child.facilities.length : 0;
    });
  }
  let flowChecksum = 0;
  for (const segment of segmentList) {
    flowChecksum += Math.round(Number(segment?.flow) || 0);
  }
  return [
    routeKey,
    linkCount,
    facilityCount,
    segmentList.length,
    debouncedSegmentTimeRange.value[0],
    debouncedSegmentTimeRange.value[1],
    flowChecksum,
  ].join(":");
}

function rememberRouteFlowMap(key, links) {
  if (!key) return links;
  // LRU 逐条淘汰：整体 clear 会让后续全部 miss 重算（拖动时段时明显抖动）
  if (routeFlowMapCache.has(key)) routeFlowMapCache.delete(key);
  routeFlowMapCache.set(key, links);
  while (routeFlowMapCache.size > ROUTE_FLOW_MAP_CACHE_LIMIT) {
    routeFlowMapCache.delete(routeFlowMapCache.keys().next().value);
  }
  return links;
}

function buildRouteFlowMapLinks(route, segments = []) {
  const cacheKey = routeFlowMapCacheKey(route, segments);
  if (routeFlowMapCache.has(cacheKey)) return routeFlowMapCache.get(cacheKey);
  // 整线：各子路线（方向/交路）都按同一份合并后的物理断面客流着色，避免按单交路客流偏小而配色失真。
  if (route?.lineGroup && Array.isArray(route.childRoutes) && route.childRoutes.length) {
    const mapped = route.childRoutes.flatMap((childRoute) => buildSingleRouteFlowMapLinks(childRoute, segments));
    if (mapped.length) return rememberRouteFlowMap(cacheKey, mapped);
  }
  return rememberRouteFlowMap(cacheKey, buildSingleRouteFlowMapLinks(route, segments));
}

// 每个站点的断面客流 = 相邻两个断面的较大值（首末站取唯一相邻断面），
// 与地图断面着色同一份数据，保证站点圈描边分档色与线一致
function buildRouteStationFlows(route, segments = []) {
  const facilities = Array.isArray(route?.facilities) ? route.facilities : [];
  if (!facilities.length) return [];
  const flowByPair = segmentFlowByNamePair(segments);
  const pairFlow = (fromIndex, toIndex, fallbackIndex) => {
    const key = stationPairKeyOf(facilities[fromIndex]?.facilityName, facilities[toIndex]?.facilityName);
    const flow = flowByPair.get(key);
    return flow !== undefined ? flow : indexedSegmentFlow(segments, fallbackIndex);
  };
  return facilities.map((fac, index) => {
    const prev = index > 0 ? pairFlow(index - 1, index, index - 1) : 0;
    const next = index + 1 < facilities.length ? pairFlow(index, index + 1, index) : 0;
    return {
      facilityId: fac?.facilityId != null ? String(fac.facilityId) : "",
      flow: Math.max(prev, next),
    };
  });
}

// selectedRouteDetail/currentRoutePanel/routeSegments 都只会整体替换引用（computed 重算或整值赋值），
// 监听引用与标量键即可，去掉 deep 避免每次触发都遍历整条线路的 links/panel 大对象
watch(
  () => [
    shouldRenderPfaRightPanel.value,
    currentSelectedRoute.value?.routeId,
    currentSelectedRoute.value?.links?.length || 0,
    currentSelectedRoute.value?.facilities?.length || 0,
    selectedRouteDetail.value,
    currentRoutePanel.value,
    routeSegments.value,
    debouncedSegmentTimeRange.value[0],
    debouncedSegmentTimeRange.value[1],
  ],
  () => {
    if (!runMonitorSelectedRouteMapLinks) return;
    if (!shouldRenderPfaRightPanel.value || !currentSelectedRoute.value) {
      runMonitorSelectedRouteMapLinks.value = [];
      if (runMonitorSelectedRouteStationFlows) runMonitorSelectedRouteStationFlows.value = [];
      return;
    }
    // 地图与右侧断面表使用同一份合并后的物理断面客流，保证配色与数值一致。
    runMonitorSelectedRouteMapLinks.value = buildRouteFlowMapLinks(currentSelectedRoute.value, routeSegments.value);
    if (runMonitorSelectedRouteStationFlows) {
      runMonitorSelectedRouteStationFlows.value = buildRouteStationFlows(currentSelectedRoute.value, routeSegments.value);
    }
  },
  { immediate: true },
);

// —— 需求11配套：反向（下行）线路断面客流，供地图下行断面图层与上行同一套色阶着色 ——
const reverseRouteSegments = computed(() => {
  const panel = selectedReverseRoutePanel.value;
  if (!Array.isArray(panel?.segments) || !panel.segments.length) return [];
  const startHour = debouncedSegmentTimeRange.value[0];
  const endHour = debouncedSegmentTimeRange.value[1];
  return panel.segments.map((segment) => ({
    name: segment.name,
    flow: Math.round(sumHourRangeProportional(segment.flowByHour, startHour, endHour)),
  }));
});

watch(
  () => [
    shouldRenderPfaRightPanel.value,
    selectedReverseRouteDetail.value,
    reverseRouteSegments.value,
  ],
  () => {
    if (!runMonitorSelectedReverseRouteMapLinks) return;
    const detail = selectedReverseRouteDetail.value;
    const links = Array.isArray(detail?.links) ? detail.links : [];
    if (!shouldRenderPfaRightPanel.value || !links.length || !reverseRouteSegments.value.length) {
      runMonitorSelectedReverseRouteMapLinks.value = links;
      return;
    }
    runMonitorSelectedReverseRouteMapLinks.value = buildRouteFlowMapLinks(detail, reverseRouteSegments.value);
  },
  { immediate: true },
);

// ===== 需求7：站点乘降 · 站间OD客流曲线（上行 + 下行同一色阶，黄→红弧线） =====
const PFA_OD_CURVE_SOURCE_ID = "pfa-station-od-curve-source";
const PFA_OD_CURVE_LAYER_ID = "pfa-station-od-curve-layer";
const PFA_OD_CURVE_CASING_LAYER_ID = "pfa-station-od-curve-casing-layer";
const PFA_OD_STATION_SOURCE_ID = "pfa-station-od-station-source";
const PFA_OD_STATION_LAYER_ID = "pfa-station-od-station-layer";
const PFA_OD_STATION_LABEL_LAYER_ID = "pfa-station-od-station-label-layer";
const PFA_OD_PRIMARY_COLOR = MAP_THEME.od.up; // 与选中线路主线（上行）颜色一致
const PFA_OD_REVERSE_COLOR = MAP_THEME.od.down; // 与反向线路（下行）颜色一致
// 期望线风格：整体细线，仅按流量档位做轻微加粗（参考站间OD期望线图，避免高流量线路糊成一团）
const PFA_OD_MIN_WIDTH = 0.6;
const PFA_OD_MAX_WIDTH = 2.8;

// 站间OD色阶配置（独立于断面/线路色阶），调节入口在地图图例右上角齿轮
const odFlowScale = ref(createColorScaleConfig("ylorrd", 5));
const showOdScalePopover = ref(false);

// 后端契约为经纬度；若拿到疑似 web mercator 坐标则兜底转换
function odPointToLngLat(x, y) {
  const numX = Number(x);
  const numY = Number(y);
  if (!Number.isFinite(numX) || !Number.isFinite(numY)) return null;
  if (Math.abs(numX) <= 180 && Math.abs(numY) <= 90) return [numX, numY];
  const converted = webMercatorToLngLat(numX, numY);
  return Array.isArray(converted) && converted.length >= 2 && converted.every(Number.isFinite)
    ? [converted[0], converted[1]]
    : null;
}

// 站点乘降：地图上叠选中线路各站点的站间OD曲线（谁上/谁下），配左下角色阶图例
const PFA_OD_MAP_ENABLED = true;

const stationOdFlows = computed(() => {
  if (!PFA_OD_MAP_ENABLED || !shouldRenderPfaRightPanel.value || pfaLineSection.value !== "boarding" || !currentSelectedRoute.value) {
    return [];
  }
  const flows = [];
  const push = (panel, direction) => {
    (Array.isArray(panel?.stationOd) ? panel.stationOd : []).forEach((od) => {
      const from = odPointToLngLat(od?.fromX, od?.fromY);
      const to = odPointToLngLat(od?.toX, od?.toY);
      const value = Number(od?.flow);
      if (!from || !to || !(value > 0)) return;
      flows.push({
        from,
        to,
        value,
        direction,
        fromName: String(od?.fromName || "").trim(),
        toName: String(od?.toName || "").trim(),
      });
    });
  };
  push(currentRoutePanel.value, "up");
  push(selectedReverseRoutePanel.value, "down");
  return flows;
});

const maxStationOdFlow = computed(() =>
  stationOdFlows.value.reduce((max, item) => Math.max(max, item.value), 0)
);

function odFlowValueLabel(value) {
  return `${Math.round(Number(value) || 0).toLocaleString()} 人次`;
}

const odResolvedScale = computed(() => resolveColorScale(odFlowScale.value));
// 站间OD分位数断点，由当前OD客流分布计算
const odFlowBreaks = computed(() => quantileBreaks(stationOdFlows.value.map((flow) => flow.value), odResolvedScale.value.thresholds));
const odLegendItems = computed(() =>
  buildValueLegendItems(odResolvedScale.value.colors, odFlowBreaks.value, maxStationOdFlow.value, odFlowValueLabel, odResolvedScale.value.widths)
);
const showOdMapLegend = computed(() => stationOdFlows.value.length > 0);

// 曲线 + 端点站 FeatureCollection：颜色/线宽按 flow 的分位数分档
const stationOdRender = computed(() => {
  const flows = stationOdFlows.value;
  if (!flows.length) {
    return { curves: emptyFlowCurveCollection(), stations: emptyFeatureCollection() };
  }
  const { colors } = odResolvedScale.value;
  const breaks = odFlowBreaks.value;
  const widthForClass = (index) => (colors.length > 1
    ? PFA_OD_MIN_WIDTH + ((PFA_OD_MAX_WIDTH - PFA_OD_MIN_WIDTH) * index) / (colors.length - 1)
    : (PFA_OD_MIN_WIDTH + PFA_OD_MAX_WIDTH) / 2);
  const curveInputs = flows.map((flow) => {
    const classIndex = classifyByBreaks(flow.value, breaks);
    return {
      from: flow.from,
      to: flow.to,
      value: flow.value,
      properties: {
        color: colors[classIndex] || colors[colors.length - 1],
        width: Number(widthForClass(classIndex).toFixed(2)),
        direction: flow.direction,
        fromName: flow.fromName,
        toName: flow.toName,
      },
    };
  });
  // 低流量先画、高流量后画（叠在上层更醒目）
  curveInputs.sort((a, b) => a.value - b.value);
  // 适度弧度呈期望线弧形；consistentSide 让所有弧线统一偏向线路同一侧（复刻期望线图，避免上下行两侧交织）
  const curves = buildFlowCurveFeatureCollection(curveInputs, { curvature: 0.24, consistentSide: true });
  const stationFeatures = new Map();
  flows.forEach((flow) => {
    [[flow.from, flow.fromName], [flow.to, flow.toName]].forEach(([coord, name]) => {
      const label = String(name || "").trim();
      if (!label || !Array.isArray(coord) || stationFeatures.has(label)) return;
      stationFeatures.set(label, {
        type: "Feature",
        id: `od-station-${stationFeatures.size}`,
        geometry: { type: "Point", coordinates: coord },
        properties: {
          name: label,
          color: flow.direction === "down" ? PFA_OD_REVERSE_COLOR : PFA_OD_PRIMARY_COLOR,
        },
      });
    });
  });
  return { curves, stations: { type: "FeatureCollection", features: Array.from(stationFeatures.values()) } };
});

// 幂等创建曲线/端点/站名图层
function ensureStationOdLayers(map) {
  if (!map.getSource(PFA_OD_CURVE_SOURCE_ID)) {
    map.addSource(PFA_OD_CURVE_SOURCE_ID, { type: "geojson", data: emptyFlowCurveCollection() });
  }
  if (!map.getSource(PFA_OD_STATION_SOURCE_ID)) {
    map.addSource(PFA_OD_STATION_SOURCE_ID, { type: "geojson", data: emptyFeatureCollection() });
  }
  if (!map.getLayer(PFA_OD_CURVE_CASING_LAYER_ID)) {
    // 白色描边衬底：略宽于曲线本体，让期望线在密集底图上边缘利落（同线网 casing 语言）
    map.addLayer({
      id: PFA_OD_CURVE_CASING_LAYER_ID,
      type: "line",
      source: PFA_OD_CURVE_SOURCE_ID,
      layout: { "line-join": "round", "line-cap": "round" },
      paint: {
        "line-color": "#ffffff",
        "line-width": ["interpolate", ["linear"], ["zoom"], 9, ["+", ["*", ["get", "width"], 0.7], 1.6], 13, ["+", ["get", "width"], 1.8], 16, ["+", ["*", ["get", "width"], 1.35], 2]],
        "line-opacity": 0.45,
      },
    });
  }
  if (!map.getLayer(PFA_OD_CURVE_LAYER_ID)) {
    map.addLayer({
      id: PFA_OD_CURVE_LAYER_ID,
      type: "line",
      source: PFA_OD_CURVE_SOURCE_ID,
      layout: { "line-join": "round", "line-cap": "round" },
      paint: {
        "line-color": ["get", "color"],
        // 细线按缩放轻微自适应，放大时略粗、缩小时更细，保持期望线的通透感
        "line-width": ["interpolate", ["linear"], ["zoom"], 9, ["*", ["get", "width"], 0.7], 13, ["get", "width"], 16, ["*", ["get", "width"], 1.35]],
        // 高流量曲线更实、低流量更透，形成期望线的主次层次
        "line-opacity": [
          "interpolate",
          ["linear"],
          ["get", "width"],
          PFA_OD_MIN_WIDTH,
          0.52,
          PFA_OD_MAX_WIDTH,
          0.9,
        ],
      },
    });
  }
  if (!map.getLayer(PFA_OD_STATION_LAYER_ID)) {
    // 需求6同款空心圈：填充透明，描边取所在方向线路颜色
    map.addLayer({
      id: PFA_OD_STATION_LAYER_ID,
      type: "circle",
      source: PFA_OD_STATION_SOURCE_ID,
      paint: {
        "circle-radius": ["interpolate", ["linear"], ["zoom"], 10, 2.6, 13, 4.6, 16, 7.5],
        "circle-color": "#ffffff",
        "circle-opacity": 0,
        "circle-stroke-color": ["get", "color"],
        "circle-stroke-width": 2,
        "circle-stroke-opacity": 0.96,
      },
    });
  }
  if (!map.getLayer(PFA_OD_STATION_LABEL_LAYER_ID)) {
    map.addLayer({
      id: PFA_OD_STATION_LABEL_LAYER_ID,
      type: "symbol",
      source: PFA_OD_STATION_SOURCE_ID,
      layout: {
        "text-field": ["get", "name"],
        "text-size": 11,
        "text-anchor": "top",
        "text-offset": [0, 0.7],
        "text-max-width": 12,
        "text-padding": 2,
        "text-allow-overlap": false,
      },
      paint: {
        "text-color": "#1f2937",
        "text-halo-color": "rgba(255, 255, 255, 0.95)",
        "text-halo-width": 1.4,
        "text-halo-blur": 0.3,
      },
    });
  }
}

function cleanUpStationOdLayers() {
  const map = MapRef.value?.map;
  if (!map) return;
  [PFA_OD_STATION_LABEL_LAYER_ID, PFA_OD_STATION_LAYER_ID, PFA_OD_CURVE_LAYER_ID, PFA_OD_CURVE_CASING_LAYER_ID].forEach((layerId) => {
    if (map.getLayer(layerId)) map.removeLayer(layerId);
  });
  [PFA_OD_STATION_SOURCE_ID, PFA_OD_CURVE_SOURCE_ID].forEach((sourceId) => {
    if (map.getSource(sourceId)) map.removeSource(sourceId);
  });
}

function updateStationOdMap() {
  const map = MapRef.value?.map;
  if (!map) return;
  const { curves, stations } = stationOdRender.value;
  // section 切走 / 换线路 / 数据缺失时清理曲线图层
  if (!curves.features.length) {
    cleanUpStationOdLayers();
    return;
  }
  ensureStationOdLayers(map);
  map.getSource(PFA_OD_CURVE_SOURCE_ID)?.setData(curves);
  map.getSource(PFA_OD_STATION_SOURCE_ID)?.setData(stations);
}

// stationOdRender 为整体替换的 computed 引用，无需 deep
watch([stationOdRender, () => MapRef.value?.map], () => {
  updateStationOdMap();
});

// 简化右侧模式（运行监测/客流分析宿主）下这两个图层永不挂载：跳过构造，避免无谓资源占用
// 模型公交线网背景，与数据管理使用同一组青灰色和淡化透明度。
const _BgRouteLayer = runMonitorSimplifiedRight ? null : new RouteLayer({
  zIndex: 998,
  lineWidth: LineWidthRef.value,
  flowControl: false,
  color: hexNumber(MAP_THEME.network.line),
  opacity: MAP_THEME.network.lineOpacity,
});

// 选中/激活路线图层（与数据管理一致的橙色高亮）
const _RouteLayer = runMonitorSimplifiedRight ? null : new RouteLayer({
  zIndex: 999,
  lineWidth: LineWidthRef.value * 1.8,
  flowControl: false,
  color: hexNumber(MAP_THEME.route.up),
  opacity: 1
});

const SELECTED_ROUTE_STOPS_SOURCE_ID = "selected-route-stops-source";
const SELECTED_ROUTE_STOPS_LAYER_ID = "selected-route-stops-layer";
// 需求6：选中线路站点空心圈描边取当前方向线路颜色（上行主线橙，与 _RouteLayer 一致）
const ROUTE_STROKE_COLOR = MAP_THEME.route.up;

// 将图层添加到地图
injectSync("MapRef").then((map) => {
  // 组件可能在 MapRef 就绪前被卸载（快速切 tab）：已 dispose 的图层再 addLayer 会残留在地图上无人清理
  if (isComponentUnmounted) return;
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
  _BgRouteLayer?.setLineWidth(value);
  _RouteLayer?.setLineWidth(value * 1.8);
});
watch(BaseMapLineModeRef, (mode) => {
  if (runMonitorSimplifiedRight) return;
  if (!currentSelectedRoute.value) {
    updateLayers(null);
  }
});

// 计算所有唯一的线路名称，并转换为 el-select-v2 需要的 options 格式
// 携带 mode（公交/地铁）：供右上角搜索框按当前线网制式过滤候选。
const lineOptions = computed(() => {
  if (!rawLines.value.length) return [];
  // 纯模型部分（收集+中文排序）按模型只算一次；制式过滤随开关变化在此轻量执行
  const allOptions = getModelDerived(rawLinesModel || props.model, "xlzl:lineOptionsAll", () => {
    const modeByName = new Map();
    rawLines.value.forEach((line) => {
      const name = lineDisplayName(line);
      if (!name) return;
      if (isMetroLine(line)) modeByName.set(name, "metro");
      else if (!modeByName.has(name)) modeByName.set(name, "bus");
    });
    const uniqueNames = Array.from(modeByName.keys()).sort(compareZh);
    return uniqueNames.map(name => ({ value: name, label: name, mode: modeByName.get(name) || "bus" }));
  });
  return allOptions.filter((option) => runMonitorLineOptionFilter(option));
});

// 计算所有唯一的站点名称，并转换为 el-select-v2 需要的 options 格式
const stationOptions = computed(() => {
  if (!rawLines.value.length) return [];
  const allOptions = getModelDerived(rawLinesModel || props.model, "xlzl:stationOptionsAll", () => {
    const names = new Set();
    rawLines.value.forEach(line => {
      line.routes?.forEach(route => {
        route.facilities?.forEach(fac => {
          if (fac.facilityName) names.add(fac.facilityName);
        });
      });
    });
    return Array.from(names).sort(compareZh).map(name => ({ value: name, label: name }));
  });
  return allOptions.filter((option) => runMonitorStationOptionFilter(option));
});

// 站点名 -> 经过该站的候选线路设施，按模型缓存。
// 选站时只在候选集合里做显示范围过滤，避免每次都全网扫描线路×方向×站点。
function buildStationRouteIndex(lines = []) {
  const byName = new Map();
  let routeOrdinal = 0;
  (Array.isArray(lines) ? lines : []).forEach((line) => {
    (Array.isArray(line?.routes) ? line.routes : []).forEach((route) => {
      const currentRouteOrdinal = routeOrdinal++;
      (Array.isArray(route?.facilities) ? route.facilities : []).forEach((fac, facIndex) => {
        const name = fac?.facilityName;
        if (!name) return;
        const entry = { line, route, fac, facIndex, routeOrdinal: currentRouteOrdinal };
        const list = byName.get(name);
        if (list) list.push(entry);
        else byName.set(name, [entry]);
      });
    });
  });
  return { byName };
}

function stationRouteIndex() {
  const lines = rawLines.value;
  return rawLinesModel && lines.length
    ? getModelDerived(rawLinesModel, "xlzl:stationRouteIndex", () => buildStationRouteIndex(lines))
    : buildStationRouteIndex(lines);
}

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

// 当前选中是否为地铁线路：地铁只按整线统计，不区分上下行方向（隐藏方向切换）
const isMetroSelection = computed(() => {
  if (currentSelectedRoute.value?.lineGroup) return true;
  if (selectedLineName.value) {
    const lines = linesForDisplayName(selectedLineName.value);
    if (lines.length) return lines.some(isMetroLine);
  }
  const route = currentSelectedRoute.value;
  if (!route) return false;
  const line = rawLineIndexes.value.lineById.get(String(route.lineId || ""));
  return line
    ? isMetroLine(line)
    : isMetroLine({ lineName: route.lineName, lineId: route.lineId, routes: [route] });
});

// 获取当前活动路线方向的详情
const activeRoute = computed(() => {
  if (!activeRouteId.value) return null;
  if (selectedLineName.value) {
    const groupRoute = buildLineGroupRoute(selectedLineName.value);
    if (groupRoute && routeMatchesKey(groupRoute, activeRouteId.value)) return groupRoute;
  }
  return rawLineIndexes.value.routeById.get(String(activeRouteId.value)) || null;
});

function getRouteEndpointLabel(route, index) {
  if (route?.lineGroup) return "整线";
  const facilities = Array.isArray(route?.facilities) ? route.facilities : [];
  const startName = facilities[0]?.facilityName || "";
  const endName = facilities[facilities.length - 1]?.facilityName || "";
  if (startName && endName) return `${startName} - ${endName}`;
  return route?.routeName || `线路 ${index + 1}`;
}

function routeEndpointNames(route = {}) {
  const facilities = Array.isArray(route?.facilities) ? route.facilities : [];
  return {
    start: String(facilities[0]?.facilityName || ""),
    end: String(facilities[facilities.length - 1]?.facilityName || ""),
  };
}

function routeDirectionDedupeKey(route = {}, index = 0) {
  if (route?.lineGroup) return "";
  const { start, end } = routeEndpointNames(route);
  if (start && end) {
    return `${normalizeLineSearchName(start)}->${normalizeLineSearchName(end)}`;
  }
  return routeUniqueKey(route) || String(route?.routeName || route?.lineName || index);
}

function uniquePhysicalDirectionRoutes(routes = []) {
  const activeKey = searchMode.value === "line" ? activeRouteId.value : activeMatchedRouteId.value;
  const byDirection = new Map();
  routes.forEach((route, index) => {
    if (!route || route.lineGroup) return;
    const directionKey = routeDirectionDedupeKey(route, index);
    if (!directionKey) return;
    const existing = byDirection.get(directionKey);
    if (!existing || routeMatchesKey(route, activeKey)) {
      byDirection.set(directionKey, route);
    }
  });
  return Array.from(byDirection.values());
}

function findReverseRoute(route = {}) {
  if (!route || route.lineGroup) return null;
  const currentKey = routeUniqueKey(route);
  const displayName = selectedLineName.value || route.lineName || route.rawLineName || "";
  const sourceRoutes = selectedLineName.value
    ? selectedLineRoutes.value
    : linesForDisplayName(displayName).flatMap((line) =>
        (Array.isArray(line?.routes) ? line.routes : []).map((item) => withLineMeta(item, line))
      );
  const routes = sourceRoutes.filter((item) => !item.lineGroup && routeUniqueKey(item) !== currentKey);
  if (!routes.length) return null;
  const currentEndpoints = routeEndpointNames(route);
  if (currentEndpoints.start && currentEndpoints.end) {
    const reversed = routes.find((item) => {
      const endpoints = routeEndpointNames(item);
      return endpoints.start === currentEndpoints.end && endpoints.end === currentEndpoints.start;
    });
    if (reversed) return reversed;
  }
  return routes[0];
}

function clearReverseSelectionOutputs() {
  selectedReverseRouteDetail.value = null;
  selectedReverseRoutePanel.value = null;
  if (runMonitorSelectedReverseLinePanel) runMonitorSelectedReverseLinePanel.value = null;
  if (runMonitorSelectedReverseRouteDetail) runMonitorSelectedReverseRouteDetail.value = null;
  if (runMonitorSelectedReverseRouteMapLinks) runMonitorSelectedReverseRouteMapLinks.value = [];
}

function applyReverseRoutePreview(reverseRoute) {
  if (!reverseRoute) return;
  const reverseKey = routeUniqueKey(reverseRoute);
  selectedReverseRoutePanel.value = reverseKey
    ? routePanelDetailCache.get(reverseKey)
      || routePanelFromPayload(routePanelData.value?.routes, reverseRoute)
      || null
    : null;
  const reverseProvisional = provisionalRouteDetail(reverseRoute);
  if (reverseProvisional) {
    selectedReverseRouteDetail.value = reverseProvisional;
    if (runMonitorSelectedReverseRouteMapLinks) {
      runMonitorSelectedReverseRouteMapLinks.value = reverseProvisional.links;
    }
  }
}

// —— 右侧面板方向切换：两个方向分开统计与绘图 ——
// 线路搜索模式复用左侧的方向列表；站点搜索模式由当前方向 + 对向方向组成。
const panelDirectionRoutes = computed(() => {
  if (searchMode.value === "line") {
    return uniquePhysicalDirectionRoutes(selectedLineRoutes.value);
  }
  const current = currentSelectedRoute.value;
  if (!current || current.lineGroup) return [];
  const reverse = findReverseRoute(current);
  return uniquePhysicalDirectionRoutes(reverse ? [current, reverse] : [current]);
});

function isPanelDirectionActive(route) {
  const targetId = searchMode.value === "line" ? activeRouteId.value : activeMatchedRouteId.value;
  return routeMatchesKey(route, targetId);
}

function handlePanelDirectionSwitch(route) {
  if (!route || isPanelDirectionActive(route)) return;
  if (searchMode.value === "line") {
    handleSelectRoute(route);
  } else {
    handleSelectMatchedRoute(route);
  }
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
    // 简化右侧模式下未构造 _RouteLayer，无图层需要清理
    if (!activeLinks?.length) {
      selectedRouteDetail.value = null;
      cleanUpSelectedRouteStops();
    }
    return;
  }
  if (activeLinks?.length) {
    _BgRouteLayer?.hide();
    _RouteLayer?.setData(activeLinks);
  } else {
    _BgRouteLayer?.hide();
    _RouteLayer?.setData([]);
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

function findLineBySearchName(target) {
  if (!target) return null;
  const exact = rawLineIndexes.value.linesByName.get(target)?.[0];
  if (exact) return exact;
  for (const item of rawLines.value) {
    const rawName = normalizeLineSearchName(item.lineName);
    if (rawName === target) return item;
    const displayName = normalizeLineSearchName(lineDisplayName(item));
    if (displayName.includes(target) || target.includes(displayName)) return item;
  }
  return null;
}

async function selectLineByName(lineName) {
  const target = normalizeLineSearchName(lineName);
  if (!target) return false;
  const line = findLineBySearchName(target);
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
  const line = findLineBySearchName(targetName);
  if (!line) return false;
  searchMode.value = "line";
  selectedStationName.value = "";
  selectedLineName.value = lineDisplayName(line);
  await nextTick();
  // 地铁：只选整线（上下行合并统计），不按方向选中
  if (isMetroLine(line)) {
    const groupRoute = selectedLineRoutes.value.find((route) => route.lineGroup);
    if (groupRoute) {
      await handleSelectRoute(groupRoute);
      return true;
    }
  }
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
        // 需求6：空心圆圈——填充透明，仅保留与线路同色的描边
        "circle-color": "#ffffff",
        "circle-opacity": 0,
        "circle-stroke-color": ROUTE_STROKE_COLOR,
        "circle-stroke-width": 2,
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
  links.forEach(link => {
    if (link.from.x < minX) minX = link.from.x;
    if (link.from.x > maxX) maxX = link.from.x;
    if (link.from.y < minY) minY = link.from.y;
    if (link.from.y > maxY) maxY = link.from.y;

    if (link.to.x < minX) minX = link.to.x;
    if (link.to.x > maxX) maxX = link.to.x;
    if (link.to.y < minY) minY = link.to.y;
    if (link.to.y > maxY) maxY = link.to.y;
  });
  if (![minX, minY, maxX, maxY].every(Number.isFinite)) return;
  if (typeof MapRef.value?.setFitZoomAndCenterByPoints === "function") {
    MapRef.value.setFitZoomAndCenterByPoints([
      [minX, minY],
      [minX, maxY],
      [maxX, minY],
      [maxX, maxY],
    ]);
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
    clearReverseSelectionOutputs();
    updateLayers(null);
    return;
  }
  const routes = selectedLineRoutes.value;
  if (routes && routes.length > 0) {
    await handleSelectRoute(routes[0]);
  }
}

// 选择某条线路的某个方向
function hasUsableRouteDetail(route = {}) {
  return Array.isArray(route?.links)
    && route.links.length > 0
    && Array.isArray(route?.facilities)
    && route.facilities.length > 0;
}

// —— 选中即时上屏：不等 routeDetail 网络返回，先用本地已有数据把线画出来 ——
// geometry→伪 links 的纯转换在 utils/routeGeometry.js（含单测）。
// 返回可立即上屏的线路详情：详情缓存命中给精确版，否则给摘要过渡版（provisionalDetail 标记）。
// 过渡版只写 selectedRouteDetail 等展示状态，绝不写入 routeDetailCache。
function provisionalRouteDetail(route) {
  if (!route?.routeId) return null;
  const key = routeUniqueKey(route);
  if (routeDetailCache.value.has(key)) return routeDetailCache.value.get(key);
  const links = provisionalRouteLinks(route);
  if (!links.length) return null;
  return {
    ...route,
    links,
    facilities: Array.isArray(route.facilities) ? route.facilities : [],
    info: route.info || {},
    provisionalDetail: true,
  };
}

async function loadRouteDetail(route, config = {}) {
  if (!route?.routeId) return route;
  if (route.lineGroup) return loadLineGroupDetail(route, config);
  const routeId = String(route.routeId);
  const key = routeUniqueKey(route);
  if (routeDetailCache.value.has(key)) {
    return routeDetailCache.value.get(key);
  }
  if (hasUsableRouteDetail(route)) {
    const detail = {
      ...route,
      facilities: route.facilities || [],
      info: route.info || {},
    };
    writeRouteDetailCache(key, detail);
    return detail;
  }
  const res = await getRouteDetail(
    {
      datasource: props.model,
      lineId: route.lineId || "",
      routeId: route.routeId,
    },
    { silentError: true, ...config },
  );
  const detail = {
    ...route,
    ...(res.data || {}),
    lineId: route.lineId || res.data?.lineId || "",
    lineName: route.lineName || res.data?.lineName || "",
    facilities: res.data?.facilities || route.facilities || [],
    info: res.data?.info || route.info || {},
  };
  writeRouteDetailCache(key, detail);
  return detail;
}

async function loadLineGroupDetail(route, config = {}) {
  const key = routeUniqueKey(route);
  if (routeDetailCache.value.has(key)) return routeDetailCache.value.get(key);
  const childRoutes = Array.isArray(route?.childRoutes) ? route.childRoutes : [];
  const childDetails = (await Promise.all(childRoutes.map((child) => loadRouteDetail(child, config))))
    .filter(Boolean);
  const detailRoutes = childDetails.length ? childDetails : childRoutes;
  const detail = {
    ...route,
    links: uniqueRouteLinks(detailRoutes),
    facilities: uniqueFacilities(detailRoutes),
    childRoutes: detailRoutes,
  };
  writeRouteDetailCache(key, detail);
  return detail;
}

async function loadRoutePanelDetail(route, config = {}) {
  const routeId = String(route?.routeId || "");
  if (!routeId) return null;
  const key = routeUniqueKey(route);
  if (route?.lineGroup) {
    const panel = await ensureRoutePanelData();
    const groupPanel = panel?.lineGroups?.[key] || null;
    cachePanelDetail(key, groupPanel);
    return groupPanel;
  }
  if (routePanelDetailCache.has(key)) return routePanelDetailCache.get(key);
  // 整包 routePanel（线路着色已预取，含每条 route 的全量面板数据）就绪时直接本地取数，
  // 右侧面板即点即出，不再为每次选中发 routePanelDetail 请求
  const localPanel = routePanelFromPayload(routePanelData.value?.routes, route);
  if (localPanel) {
    cachePanelDetail(key, localPanel);
    return localPanel;
  }
  if (routePanelDetailPromises.has(key)) return routePanelDetailPromises.get(key);
  const model = props.model;
  const promise = getRoutePanelDetail({
    datasource: model,
    lineId: route.lineId || "",
    routeId,
  }, { silentError: true, ...config })
    .then((res) => {
      const panel = res?.data && typeof res.data === "object" ? res.data : null;
      if (props.model === model && panel && Object.keys(panel).length) {
        cachePanelDetail(key, panel);
        return panel;
      }
      return null;
    })
    .catch((error) => {
      if (isCanceledRequest(error)) return null;
      return null;
    })
    .finally(() => {
      routePanelDetailPromises.delete(key);
    });
  routePanelDetailPromises.set(key, promise);
  return promise;
}

async function loadSelectedRoutePanel(route, config = {}) {
  const key = routeUniqueKey(route);
  if (!key || !shouldLoadSelectedRoutePanel.value) return null;
  const cachedPanel = routePanelDetailCache.get(key)
    || routePanelFromPayload(routePanelData.value?.routes, route);
  if (cachedPanel) return cachedPanel;
  const panel = await ensureRoutePanelData();
  const routePanel = routePanelFromPayload(panel?.routes, route);
  if (routePanel) {
    cachePanelDetail(key, routePanel);
    return routePanel;
  }
  const detailPanel = await loadRoutePanelDetail(route, config);
  if (detailPanel) return detailPanel;
  return null;
}

function ensureRoutePanelData() {
  if (routePanelData.value) return Promise.resolve(routePanelData.value);
  if (routePanelPromise) return routePanelPromise;
  const model = props.model;
  // 整包客流面板改走共享缓存：与 index.vue 总体客流等消费点共用同一次下载，
  // 请求中止由 modelDataCache 的 abortOtherModelDataRequests 统一管理
  routePanelPromise = getCachedRoutePanel(model)
    .then((data) => {
      if (props.model === model && data?.routes) {
        routePanelData.value = data;
      }
      return data;
    })
    .catch((error) => {
      if (isCanceledRequest(error)) return null;
      return null;
    })
    .finally(() => {
      routePanelPromise = null;
    });
  return routePanelPromise;
}

async function handleSelectRoute(route) {
  const routeId = String(route?.routeId || "");
  if (!routeId) return;
  const key = routeUniqueKey(route);
  const reverseRoute = findReverseRoute(route);
  const request = nextSelectionSignal();
  activeRouteId.value = routeId;
  // 即时回显：命中本地缓存/整包时右侧面板同步上屏，不等待网络
  selectedRoutePanel.value = routePanelDetailCache.get(key)
    || routePanelFromPayload(routePanelData.value?.routes, route)
    || null;
  clearReverseSelectionOutputs();
  applyReverseRoutePreview(reverseRoute);

  // 选中即刻画线+居中（摘要 geometry 或缓存详情），不等 routeDetail 网络返回
  let provisionalPainted = false;
  const provisional = provisionalRouteDetail(route);
  if (provisional) {
    selectedRouteDetail.value = provisional;
    updateLayers(provisional.links);
    centerOnRoute(provisional.links);
    updateSelectedRouteStops(provisional);
    provisionalPainted = true;
  }
  const detailPromise = loadRouteDetail(route, { signal: request.signal });
  const panelPromise = loadSelectedRoutePanel(route, { signal: request.signal });
  const reverseDetailPromise = reverseRoute
    ? loadRouteDetail(reverseRoute, { signal: request.signal })
    : Promise.resolve(null);
  const reversePanelPromise = reverseRoute
    ? loadSelectedRoutePanel(reverseRoute, { signal: request.signal })
    : Promise.resolve(null);

  panelPromise.then((panel) => {
    if (selectionRequestSeq !== request.seq || String(activeRouteId.value) !== routeId) return;
    if (shouldLoadSelectedRoutePanel.value) selectedRoutePanel.value = panel;
  }).catch(() => {});

  reversePanelPromise.then((reversePanel) => {
    if (selectionRequestSeq !== request.seq || String(activeRouteId.value) !== routeId) return;
    selectedReverseRoutePanel.value = reversePanel || null;
  }).catch(() => {});

  reverseDetailPromise
    .then((reverseDetail) => {
      if (selectionRequestSeq !== request.seq || String(activeRouteId.value) !== routeId) return;
      selectedReverseRouteDetail.value = reverseDetail || null;
      if (runMonitorSelectedReverseRouteMapLinks) {
        runMonitorSelectedReverseRouteMapLinks.value = Array.isArray(reverseDetail?.links) ? reverseDetail.links : [];
      }
    })
    .catch(() => {});

  try {
    const detail = await detailPromise;
    if (selectionRequestSeq !== request.seq || String(activeRouteId.value) !== routeId) return;
    selectedRouteDetail.value = detail;
    if (detail?.links && detail.links.length > 0) {
      updateLayers(detail.links);
      // 摘要走向已居中过（与精确 links 外接框最多差抽稀容差 8m），不再二次跳相机
      if (!provisionalPainted) centerOnRoute(detail.links);
    }
    updateSelectedRouteStops(detail);
  } catch (error) {
    // 过期请求的失败不能清掉新选中线路的状态；已有摘要过渡显示时保留，不闪空
    if (!isCanceledRequest(error) && selectionRequestSeq === request.seq && !provisionalPainted) {
      selectedRouteDetail.value = null;
      updateLayers(null);
    }
  }
}

// 切换站点时
function handleStationChange(stationName) {
  if (!stationName) {
    matchedRoutes.value = [];
    activeMatchedRouteId.value = "";
    selectedRouteDetail.value = null;
    selectedRoutePanel.value = null;
    clearReverseSelectionOutputs();
    updateLayers(null);
    return;
  }

  const matches = [];
  const winnerByRoute = new Map();
  for (const entry of stationRouteIndex().byName.get(stationName) || []) {
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
  const winners = Array.from(winnerByRoute.values()).sort((a, b) => a.routeOrdinal - b.routeOrdinal);
  for (const { line, route, fac } of winners) {
    matches.push({
      lineId: line.lineId,
      lineName: line.lineName,
      routeId: route.routeId,
      routeName: route.routeName,
      info: route.info,
      links: route.links,
      geometry: route.geometry,
      facilities: route.facilities,
      stationCoord: fac.coord || null,
    });
  }

  matchedRoutes.value = matches;
  activeMatchedRouteId.value = "";
  selectedRouteDetail.value = null;
  selectedRoutePanel.value = null;
  clearReverseSelectionOutputs();
  updateLayers(null); // 不要自动选中第一条线路
}

// 选择途径该站点的某条线路
async function handleSelectMatchedRoute(item) {
  const routeId = String(item?.routeId || "");
  if (!routeId) return;
  const key = routeUniqueKey(item);
  const reverseRoute = findReverseRoute(item);
  const request = nextSelectionSignal();
  activeMatchedRouteId.value = key;
  // 即时回显：命中本地缓存/整包时右侧面板同步上屏，不等待网络
  selectedRoutePanel.value = routePanelDetailCache.get(key)
    || routePanelFromPayload(routePanelData.value?.routes, item)
    || null;
  clearReverseSelectionOutputs();
  applyReverseRoutePreview(reverseRoute);

  // 选中即刻画线+居中（摘要 geometry 或缓存详情），不等 routeDetail 网络返回
  let provisionalPainted = false;
  const provisional = provisionalRouteDetail(item);
  if (provisional) {
    selectedRouteDetail.value = provisional;
    updateLayers(provisional.links);
    centerOnRoute(provisional.links);
    updateSelectedRouteStops(provisional);
    provisionalPainted = true;
  }
  const detailPromise = loadRouteDetail(item, { signal: request.signal });
  const panelPromise = loadSelectedRoutePanel(item, { signal: request.signal });
  const reverseDetailPromise = reverseRoute
    ? loadRouteDetail(reverseRoute, { signal: request.signal })
    : Promise.resolve(null);
  const reversePanelPromise = reverseRoute
    ? loadSelectedRoutePanel(reverseRoute, { signal: request.signal })
    : Promise.resolve(null);

  panelPromise.then((panel) => {
    if (selectionRequestSeq !== request.seq || String(activeMatchedRouteId.value) !== key) return;
    if (shouldLoadSelectedRoutePanel.value) selectedRoutePanel.value = panel;
  }).catch(() => {});

  reversePanelPromise.then((reversePanel) => {
    if (selectionRequestSeq !== request.seq || String(activeMatchedRouteId.value) !== key) return;
    selectedReverseRoutePanel.value = reversePanel || null;
  }).catch(() => {});

  reverseDetailPromise
    .then((reverseDetail) => {
      if (selectionRequestSeq !== request.seq || String(activeMatchedRouteId.value) !== key) return;
      selectedReverseRouteDetail.value = reverseDetail || null;
    })
    .catch(() => {});

  try {
    const detail = await detailPromise;
    if (selectionRequestSeq !== request.seq || String(activeMatchedRouteId.value) !== key) return;
    selectedRouteDetail.value = detail;
    // 与 handleSelectRoute 对齐：空 links 不触发 updateLayers([])，避免误清刚设置的选中详情
    if (detail?.links && detail.links.length > 0) {
      updateLayers(detail.links);
      // 摘要走向已居中过（与精确 links 外接框最多差抽稀容差 8m），不再二次跳相机
      if (!provisionalPainted) centerOnRoute(detail.links);
    }
    updateSelectedRouteStops(detail);
  } catch (error) {
    // 过期请求的失败不能清掉新选中线路的状态；已有摘要过渡显示时保留，不闪空
    if (!isCanceledRequest(error) && selectionRequestSeq === request.seq && !provisionalPainted) {
      selectedRouteDetail.value = null;
      updateLayers(null);
    }
  }
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
  clearReverseSelectionOutputs();
  updateLayers(null);
});

// 线路摘要先到先用；体积较大的客流面板异步补齐，不能再阻塞地图和搜索。
async function loadAllLines() {
  const model = props.model;
  loading.value = true;
  routeFlowMapCache.clear();
  abortOtherModelDataRequests(model);
  routePanelData.value = null;
  selectedRoutePanel.value = null;
  // 整包 routePanel 无条件预取（与 index.vue 线路着色共用同一次下载）：
  // 选中线路时右侧面板直接本地取数，不再等 routePanelDetail 网络请求
  ensureRoutePanelData();
  try {
    const lineRes = await getCachedLineAll(model);
    if (props.model !== model) return;
      // 规范化结果按模型记忆化：重挂载/切 tab 直接命中同一份 markRaw 数组，不再整表重克隆
      const data = getModelDerived(model, "xlzl:normalizedLines", () =>
        (Array.isArray(lineRes) ? lineRes : []).map((line) => ({
          ...line,
          lineName: line?.lineName || line?.lineId || "未命名线路",
        })));
      rawLinesModel = model;
      rawLines.value = data;
      if (!runMonitorSimplifiedRight) {
        _BgRouteLayer.setTileSource(model, { tileRequest: getRouteTileBinary });
      }
      updateLayers(null);
  } catch (error) {
    if (props.model === model && !isCanceledRequest(error)) rawLines.value = [];
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
    selectionAbortController?.abort();
    routeFlowMapCache.clear();
    // routeDetailCache 为模型作用域 Map，模型切换后天然指向新 Map，旧模型缓存随 LRU 淘汰即可
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
    clearReverseSelectionOutputs();
    selectedRouteDetail.value = null;
    loadAllLines();
  }
});

onUnmounted(() => {
  isComponentUnmounted = true;
  cancelSegmentTimeMirror();
  selectionAbortController?.abort();
  if (runMonitorSelectedRouteDetail) runMonitorSelectedRouteDetail.value = null;
  if (runMonitorSelectedRouteMapLinks) runMonitorSelectedRouteMapLinks.value = [];
  clearReverseSelectionOutputs();
  _BgRouteLayer?.dispose();
  _RouteLayer?.dispose();
  cleanUpSelectedRouteStops();
  cleanUpStationOdLayers();
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
  clearReverseSelectionOutputs();
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
.pfa-route-sections .time-unit-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dm2-space-3);
}
.pfa-route-sections .time-unit-label {
  color: var(--dm2-muted);
  font-size: var(--dm2-text-xs);
  font-weight: var(--dm2-fw-semibold);
}
.pfa-route-sections .time-unit-selector {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  border: 1px solid var(--dm2-line);
  border-radius: var(--dm2-radius-sm);
  background: var(--dm2-surface);
}
.pfa-route-sections .time-unit-btn {
  height: 24px;
  padding: 0 8px;
  border: 0;
  border-radius: calc(var(--dm2-radius-sm) - 3px);
  background: transparent;
  color: var(--dm2-muted);
  font-family: var(--dm2-font-num);
  font-size: var(--dm2-text-xs);
  font-weight: var(--dm2-fw-semibold);
  cursor: pointer;
  transition: color var(--dm2-dur-fast) var(--dm2-ease),
    background-color var(--dm2-dur-fast) var(--dm2-ease);
}
.pfa-route-sections .time-unit-btn:hover {
  color: var(--dm2-ink-soft);
}
.pfa-route-sections .time-unit-btn.active {
  background: var(--dm2-accent);
  color: #fff;
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
  position: relative;
  font-size: var(--dm2-text-sm);
  border-bottom: 1px solid var(--dm2-line-faint);
  transition: background-color var(--dm2-dur-fast) var(--dm2-ease);
}
/* 断面客流量条：左起按占峰值比例填充，行尾一条竖线标出条头；各行条头连起来即沿线断面客流剖面 */
.pfa-route-sections .segments-table .table-row::before {
  content: "";
  position: absolute;
  inset: 0 auto 0 0;
  width: var(--seg-bar-w, 0);
  background: var(--dm2-accent-weak);
  transition: width var(--dm2-dur) var(--dm2-ease);
  pointer-events: none;
  z-index: 0;
}
.pfa-route-sections .segments-table .table-row::after {
  content: "";
  position: absolute;
  top: 0;
  bottom: 0;
  left: var(--seg-bar-w, 0);
  width: 2px;
  margin-left: -2px;
  background: var(--dm2-accent);
  opacity: 0.45;
  transition: left var(--dm2-dur) var(--dm2-ease);
  pointer-events: none;
  z-index: 0;
}
.pfa-route-sections .segments-table .table-row > span {
  position: relative;
  z-index: 1;
}
.pfa-route-sections .segments-table .table-row:last-child {
  border-bottom: 0;
}
/* hover 用中性提亮，别用 accent；否则与蓝色断面条撞色、条头看不出来 */
.pfa-route-sections .segments-table .table-row:hover {
  background: rgba(17, 32, 58, 0.035);
}
/* 峰值断面：条头竖线加实、客流量数值取深蓝，直接答"最大断面在哪一段" */
.pfa-route-sections .segments-table .table-row.is-peak::after {
  opacity: 0.9;
}
.pfa-route-sections .segments-table .table-row.is-peak .col-flow {
  color: var(--dm2-accent-strong);
}
.pfa-route-sections .segments-table .peak-tag {
  display: inline-block;
  margin-right: 5px;
  padding: 1px 6px;
  border-radius: var(--dm2-radius-pill);
  background: var(--dm2-accent);
  color: #ffffff;
  font-family: var(--dm2-font);
  font-size: 10px;
  font-weight: var(--dm2-fw-bold);
  line-height: 1.5;
  vertical-align: 1px;
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
/* 断面条随时段变化补间是数据反馈动效；无障碍偏好下改为瞬时 */
@media (prefers-reduced-motion: reduce) {
  .pfa-route-sections .segments-table .table-row::before,
  .pfa-route-sections .segments-table .table-row::after {
    transition: none;
  }
}

/* ② 站点乘降图 & ⑤ 关联线路图 */
.pfa-route-sections .section-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--dm2-space-2);
  min-width: 0;
  flex-wrap: wrap;
}
.pfa-route-sections .boarding-section-header {
  align-items: stretch;
  flex-direction: column;
  gap: var(--dm2-space-2);
}
.pfa-route-sections .chart-mode-actions {
  width: 100%;
  justify-content: stretch;
}
.pfa-route-sections .chart-type-selector {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 2px;
  width: 100%;
  padding: 3px;
  border-radius: var(--dm2-radius-sm);
  background: var(--dm2-surface-sunken);
  border: 1px solid var(--dm2-line);
}
.pfa-route-sections .chart-type-selector .type-pill {
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 0;
  padding: 0 8px;
  border: 0;
  border-radius: calc(var(--dm2-radius-sm) - 3px);
  background: transparent;
  font-family: var(--dm2-font);
  font-size: var(--dm2-text-xs);
  font-weight: var(--dm2-fw-semibold);
  line-height: 1;
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
.pfa-route-sections .chart-fullscreen-btn {
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 0 9px;
  border: 1px solid var(--dm2-line-strong);
  border-radius: var(--dm2-radius-sm);
  background: var(--dm2-surface);
  color: var(--dm2-ink-soft);
  font-size: var(--dm2-text-xs);
  font-weight: var(--dm2-fw-semibold);
  line-height: 1;
  cursor: pointer;
  transition: border-color var(--dm2-dur-fast) var(--dm2-ease),
    color var(--dm2-dur-fast) var(--dm2-ease),
    background-color var(--dm2-dur-fast) var(--dm2-ease);
}
.pfa-route-sections .chart-fullscreen-btn:hover,
.pfa-route-sections .chart-fullscreen-btn:focus-visible {
  border-color: rgba(21, 105, 222, 0.38);
  background: rgba(21, 105, 222, 0.06);
  color: var(--dm2-accent);
}
.pfa-route-sections .chart-fullscreen-btn:disabled {
  cursor: not-allowed;
  opacity: 0.45;
  background: var(--dm2-surface-sunken);
}
.pfa-route-sections .boarding-chart-stack {
  display: flex;
  flex-direction: column;
  gap: var(--dm2-space-4);
}
.pfa-route-sections .boarding-chart-panel {
  min-height: 0;
}
.pfa-route-sections .boarding-chart-title {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--dm2-space-3);
  margin-bottom: var(--dm2-space-2);
  color: var(--dm2-muted);
  font-size: var(--dm2-text-xs);
  font-weight: var(--dm2-fw-semibold);
}
.pfa-route-sections .boarding-chart-hint {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: var(--dm2-fw-medium);
}
.pfa-route-sections .chart-container-wrapper {
  height: 248px;
  width: 100%;
}
/* 需求4：第二列 112px 过窄导致错位，放宽为 minmax(110px,140px) 并按顶部对齐 */
.pfa-route-sections .transfer-analysis-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(110px, 140px);
  gap: var(--dm2-space-3);
  align-items: start;
}
.pfa-route-sections .boarding-chart-stack.has-dual {
  gap: var(--dm2-space-3);
}
.pfa-route-sections .boarding-chart-stack.has-dual .chart-container-wrapper {
  height: 158px;
}
/* 高度完全由行数驱动（32px 表头 + 每行 28px），不再设 220px 下限，避免两列被拉高后行错位 */
.pfa-route-sections .transfer-chart-wrapper {
  height: var(--transfer-chart-height, 220px);
  width: 100%;
}
.pfa-route-sections .transfer-meta-list {
  min-width: 0;
  height: var(--transfer-chart-height, 220px);
  display: flex;
  flex-direction: column;
  border: 1px solid var(--dm2-line);
  border-radius: var(--dm2-radius-sm);
  background: var(--dm2-surface);
  overflow: hidden;
}
.pfa-route-sections .transfer-meta-header,
.pfa-route-sections .transfer-meta-row {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  align-items: center;
  gap: 6px;
  min-height: 28px;
  padding: 0 8px;
  border-bottom: 1px solid var(--dm2-line-faint);
  color: var(--dm2-ink-soft);
  font-size: var(--dm2-text-xs);
}
.pfa-route-sections .transfer-meta-header {
  flex: 0 0 32px;
  color: var(--dm2-muted);
  font-weight: var(--dm2-fw-semibold);
  background: var(--dm2-surface-sunken);
}
.pfa-route-sections .transfer-meta-row {
  flex: 0 0 28px;
  font-family: var(--dm2-font-num);
  font-weight: var(--dm2-fw-medium);
}
.pfa-route-sections .transfer-meta-row:last-child {
  border-bottom: 0;
}
/* 需求4：单元格防溢出，长文本省略而不是撑破列宽 */
.pfa-route-sections .transfer-meta-header span,
.pfa-route-sections .transfer-meta-row span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pfa-route-sections .chart_box {
  width: 100%;
  height: 100%;
}
.pfa-route-sections .boarding-alighting-bar-chart {
  width: 100%;
  height: 100%;
}

/* 右侧面板方向切换 */
.pfa-route-sections .panel-direction-section {
  display: flex;
  flex-direction: column;
  gap: var(--dm2-space-2);
  padding-bottom: var(--dm2-space-2);
  border-bottom: 1px dashed var(--dm2-line);
}
.pfa-route-sections .panel-direction-label {
  color: var(--dm2-muted);
  font-size: var(--dm2-text-xs);
  font-weight: var(--dm2-fw-semibold);
}
.pfa-route-sections .panel-direction-pills {
  display: flex;
  flex-wrap: wrap;
  gap: var(--dm2-space-2);
}
.pfa-route-sections .panel-direction-pill {
  flex: 1 1 auto;
  min-width: 0;
  max-width: 100%;
  padding: 6px 10px;
  border: 1px solid var(--dm2-line-strong);
  border-radius: var(--dm2-radius-sm);
  background: var(--dm2-surface);
  color: var(--dm2-ink-soft);
  font-size: var(--dm2-text-xs);
  font-weight: var(--dm2-fw-medium);
  line-height: 1.2;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: border-color var(--dm2-dur-fast) var(--dm2-ease),
    color var(--dm2-dur-fast) var(--dm2-ease),
    background-color var(--dm2-dur-fast) var(--dm2-ease);
}
.pfa-route-sections .panel-direction-pill:hover {
  border-color: rgba(21, 105, 222, 0.38);
  color: var(--dm2-accent);
}
.pfa-route-sections .panel-direction-pill.active {
  color: #fff;
  background: var(--dm2-accent);
  border-color: var(--dm2-accent);
  box-shadow: var(--dm2-accent-glow);
}

/* 点击全屏的图表容器 */
.pfa-route-sections .boarding-clickable-chart {
  cursor: zoom-in;
  border-radius: var(--dm2-radius-sm);
  transition: box-shadow var(--dm2-dur-fast) var(--dm2-ease);
}
.pfa-route-sections .boarding-clickable-chart:hover {
  box-shadow: 0 0 0 1px rgba(21, 105, 222, 0.28);
}
.pfa-route-sections .boarding-chart-hint {
  color: var(--dm2-muted);
  font-size: var(--dm2-text-xs);
  font-weight: var(--dm2-fw-medium);
}

/* 图表类型切到“热力图”时的容器：比折线/柱状更高，容纳倾斜站名 + visualMap，不再被裁切 */
.pfa-route-sections .boarding-heatmap-wrapper {
  height: 330px;
}
.pfa-route-sections .boarding-heatmap-panel-chart {
  width: 100%;
  height: 100%;
}

/* 需求7：站间OD地图浮动图例（左下角，避开左侧栏与地图控件） */
.pfa-od-map-legend {
  position: fixed;
  left: calc(276px * var(--app-layout-scale, 1));
  bottom: 20px;
  z-index: calc(var(--z-panel, 1300) + 10);
  min-width: 148px;
  max-width: 220px;
  padding: 10px 12px;
  border: 1px solid var(--dm2-line, rgba(21, 105, 222, 0.16));
  border-radius: 10px;
  background: var(--app-panel-bg, rgba(255, 255, 255, 0.94));
  box-shadow: 0 8px 24px rgba(13, 38, 76, 0.16);
  font-size: 11px;
  color: #475467;
  display: flex;
  flex-direction: column;
  gap: 4px;
  /* 图例可交互（齿轮调节色阶），并拦截点击避免穿透到地图触发选线跳转 */
  pointer-events: auto;
}
.pfa-od-legend-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 2px;
}
.pfa-od-legend-title {
  font-size: 12px;
  font-weight: 700;
  color: #344054;
}
.pfa-od-legend-gear {
  flex: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: 1px solid rgba(21, 105, 222, 0.22);
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.9);
  color: #475467;
  cursor: pointer;
  transition: color 0.15s ease, border-color 0.15s ease, background-color 0.15s ease;
}
.pfa-od-legend-gear:hover,
.pfa-od-legend-gear:focus-visible {
  color: #1569de;
  border-color: rgba(21, 105, 222, 0.45);
  background: rgba(21, 105, 222, 0.08);
}
.pfa-od-legend-popover {
  position: absolute;
  left: 0;
  bottom: calc(100% + 8px);
  width: 264px;
  max-height: 60vh;
  overflow-y: auto;
  padding: 12px;
  border: 1px solid rgba(21, 105, 222, 0.18);
  border-radius: 10px;
  background: var(--app-panel-bg, rgba(255, 255, 255, 0.97));
  box-shadow: 0 12px 32px rgba(13, 38, 76, 0.22);
}
.pfa-od-legend-popover-title {
  font-size: 12px;
  font-weight: 700;
  color: #344054;
  margin-bottom: 8px;
}
.popover-fade-enter-active,
.popover-fade-leave-active {
  transition: opacity 0.16s ease, transform 0.16s ease;
}
.popover-fade-enter-from,
.popover-fade-leave-to {
  opacity: 0;
  transform: translateY(4px);
}
.pfa-od-legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
}
.pfa-od-legend-swatch {
  width: 22px;
  height: 6px;
  border-radius: 3px;
  flex: none;
}
.pfa-od-legend-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pfa-od-legend-dirs {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
  padding-top: 5px;
  border-top: 1px dashed rgba(21, 105, 222, 0.2);
  color: #667085;
}
.pfa-od-legend-dirs span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.pfa-od-dir-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  border: 2px solid transparent;
  background: transparent;
  box-sizing: border-box;
}
.pfa-od-dir-dot.up {
  border-color: #f97316;
}
.pfa-od-dir-dot.down {
  border-color: #1569de;
}

:global(.boarding-fullscreen-dialog) {
  background: #f7fbff;
}
:global(.boarding-fullscreen-dialog .el-dialog__header) {
  margin-right: 0;
  padding: 18px 24px 14px;
  border-bottom: 1px solid rgba(21, 105, 222, 0.12);
}
:global(.boarding-fullscreen-dialog .el-dialog__headerbtn) {
  top: 16px;
  right: 18px;
}
:global(.boarding-fullscreen-dialog .el-dialog__body) {
  height: calc(100vh - 78px);
  padding: 0;
}
.boarding-fullscreen-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  padding-right: 42px;
}
.boarding-fullscreen-kicker {
  margin-bottom: 4px;
  color: var(--dm2-muted);
  font-size: var(--dm2-text-xs);
  font-weight: var(--dm2-fw-semibold);
}
.boarding-fullscreen-title {
  color: var(--dm2-ink);
  font-size: 18px;
  font-weight: var(--dm2-fw-semibold);
}
.boarding-fullscreen-meta {
  flex: 0 0 auto;
  color: var(--dm2-accent);
  font-size: var(--dm2-text-sm);
  font-weight: var(--dm2-fw-semibold);
}
.boarding-fullscreen-body {
  width: 100%;
  height: 100%;
  padding: 18px 24px 24px;
  box-sizing: border-box;
  display: grid;
  grid-template-rows: minmax(0, 1fr);
  gap: 18px;
  overflow-y: auto;
}
.boarding-fullscreen-body.has-dual {
  grid-template-rows: minmax(420px, 1fr) minmax(420px, 1fr);
}
.boarding-fullscreen-panel {
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.boarding-fullscreen-chart {
  width: 100%;
  flex: 1 1 auto;
  min-height: 0;
}

/* 站点乘降热力图弹窗（append-to-body，Element 内部结构用 :global）。
   注意：element.scss 按需引入时未包含 dialog.scss，el-dialog 无任何默认结构样式，
   需自带宽度 / 居中 / 背景（同 boarding-fullscreen-dialog 自带背景的原因）。 */
:global(.boarding-heatmap-overlay .el-overlay-dialog) {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: auto;
}
:global(.boarding-heatmap-dialog) {
  position: relative;
  width: 100vw;
  height: 100vh;
  margin: 0;
  background: #f7fbff;
  border-radius: 0;
  overflow: hidden;
  outline: none; /* 焦点陷阱聚焦容器时不显示浏览器默认焦点环 */
}
:global(.boarding-heatmap-dialog .el-dialog__header) {
  margin-right: 0;
  padding: 14px 20px;
  border-bottom: 1px solid rgba(21, 105, 222, 0.12);
}
:global(.boarding-heatmap-dialog .el-dialog__headerbtn) {
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
}
:global(.boarding-heatmap-dialog .el-dialog__headerbtn:hover) {
  color: #1569de;
}
:global(.boarding-heatmap-dialog .el-dialog__body) {
  padding: 12px 20px 18px;
}
.boarding-heatmap-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  padding-right: 28px;
}
.boarding-heatmap-kicker {
  margin-bottom: 2px;
  color: var(--dm2-muted);
  font-size: var(--dm2-text-xs);
  font-weight: var(--dm2-fw-semibold);
}
.boarding-heatmap-title {
  color: var(--dm2-ink);
  font-size: 17px;
  font-weight: 700;
  line-height: 1.25;
}
.boarding-heatmap-meta {
  flex: 0 0 auto;
  color: var(--dm2-accent);
  font-size: var(--dm2-text-xs);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.boarding-heatmap-body {
  height: calc(100vh - 110px);
  min-height: 340px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.boarding-heatmap-body .boarding-heatmap-chart {
  width: 100%;
  height: 100%;
}
.boarding-heatmap-body .el-empty {
  margin: 0 auto;
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
