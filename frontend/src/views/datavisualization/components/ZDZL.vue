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

  <teleport to="#datavisualization_index_box2" defer>
    <MCard2 v-if="selectedStationName" class="SJZL_right_card" :open="true">
      <template #title>
        <div class="ranking-title-container">
          <div class="header-actions-left">
            <div class="detail-tab-selector">
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
        <!-- Tab 1: 站点数据分析 -->
        <div v-if="activeDetailTab === 'overview'" class="route-detail-panel">
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
                <span class="col-od-stations">起讫站点 (OD区间)</span>
                <span class="col-od-flow text-right">OD客流量</span>
              </div>
              <div class="transfer-table-body">
                <div v-for="(item, idx) in odTableData" :key="idx" class="transfer-table-row">
                  <span class="col-od-stations text-ellipsis">{{ item.origin }} - {{ item.destination }}</span>
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

    <MCard2 v-else class="SJZL_right_card ranking-card" :open="true">
      <template #title>
        <div class="ranking-title-container">
          <span class="MCard2_title">{{ activeTransitType === 'bus' ? '公交' : '地铁' }}站点客流排行</span>
          <div class="header-actions">
            <div class="transit-type-selector">
              <div 
                v-for="type in ['bus', 'subway']" 
                :key="type"
                :class="['type-pill', activeTransitType === type ? 'active' : '']"
                @click.stop="activeTransitType = type"
              >
                {{ type === 'bus' ? '公交' : '地铁' }}
              </div>
            </div>
            <el-button 
              type="primary" 
              size="small" 
              class="export-btn"
              @click.stop="handleExportLeaderboard"
            >
              <el-icon style="margin-right: 4px;"><Download /></el-icon>
              导出排行
            </el-button>
          </div>
        </div>
      </template>
      <template #body>
        <div class="ranking-panel">
          <div class="ranking-header">
            <span class="col-rank">排序</span>
            <span class="col-name">站点名称</span>
            <span class="col-flow">日均客流量</span>
          </div>
          <div class="ranking-scroll-list">
            <div 
              v-for="(item, index) in currentLeaderboard" 
              :key="index"
              class="ranking-row"
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
            </div>
          </div>
        </div>
      </template>
    </MCard2>
  </teleport>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch, inject, computed, getCurrentInstance } from "vue";
import { Location, Download } from "@element-plus/icons-vue";
import { getLineAll } from "@/api/route";
import { getStationPanel } from "@/api/facility";
import MCard from "./MCard.vue";
import MCard2 from "./MCard2.vue";
import { StationLayer } from "../layers/StationLayer.js";
import { injectSync } from "@/utils";

const props = defineProps({
  model: String,
});

const loading = ref(true);
const rawLines = ref([]);
const stationPanelData = ref(null);

const selectedStationName = ref("");
const matchedRoutes = ref([]);

const StationSizeRef = inject("StationSizeRef", ref(40));
const MapRef = inject("MapRef", ref(null));

// 注入右侧面板显示控制
const rightPanelHasContent = inject("rightPanelHasContent", ref(false));
const activeDatavisualizationTab = inject("activeDatavisualizationTab", ref(""));

// 监听当前选中的站点，控制右侧面板内容状态
watch(selectedStationName, (newStation) => {
  if (activeDatavisualizationTab.value === "站点客流监测") {
    rightPanelHasContent.value = true;
  }
  if (!newStation) {
    cleanUpSelectedStationRing();
  }
}, { immediate: true });

watch(activeDatavisualizationTab, (newTab) => {
  if (newTab === "站点客流监测") {
    rightPanelHasContent.value = true;
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
  return stationName ? stationPanelData.value?.stations?.[stationName] || null : null;
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

const { proxy } = getCurrentInstance() || {};
const activeChartType = ref("line");

const passengerFlowChartOption = computed(() => {
  const isLine = activeChartType.value === "line";
  const hours = ["06:00", "07:00", "08:00", "09:00", "10:00", "11:00", "12:00", "13:00", "14:00", "15:00", "16:00", "17:00", "18:00", "19:00", "20:00", "21:00", "22:00"];
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
        color: "#718096",
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
        color: "#718096",
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
  map.value?.addLayer(_StationLayer);
});

watch(StationSizeRef, (value) => {
  _StationLayer.setMarkerSize(value);
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

const SELECTED_STATION_RING_SOURCE_ID = "selected-station-ring-source";
const SELECTED_STATION_RING_LAYER_ID = "selected-station-ring-layer";

function updateSelectedStationRing(coord) {
  if (!MapRef.value || !MapRef.value.map) return;
  const map = MapRef.value.map;
  
  if (!coord) {
    cleanUpSelectedStationRing();
    return;
  }
  
  // Convert Web Mercator coord to LngLat
  const EARTH_RADIUS = 6378137.0;
  const lng = (Number(coord.x) / EARTH_RADIUS) * (180 / Math.PI);
  const lat = (2 * Math.atan(Math.exp(Number(coord.y) / EARTH_RADIUS)) - Math.PI / 2) * (180 / Math.PI);
  
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
        "circle-color": "rgba(250, 204, 21, 0.03)",
        "circle-stroke-color": "#facc15",
        "circle-stroke-width": [
          "interpolate",
          ["linear"],
          ["zoom"],
          10, 1.2,
          13, 3,
          16, 5.5,
          18, 8
        ],
        "circle-stroke-opacity": 0.95
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

// 切换站点时
function handleStationChange(stationName) {
  if (!stationName) {
    matchedRoutes.value = [];
    cleanUpSelectedStationRing();
    return;
  }

  const matches = [];
  let stationCoord = null;

  rawLines.value.forEach(line => {
    if (line.routes) {
      line.routes.forEach(route => {
        if (route.facilities) {
          const matchedFac = route.facilities.find(fac => fac.facilityName === stationName);
          if (matchedFac) {
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

  // 居中并适当放大z轴，用黄色厚圆圈圈住选中站点
  if (stationCoord && MapRef.value) {
    MapRef.value.setCenter([stationCoord.x, stationCoord.y]);
    MapRef.value.setZoom(15.5);
    updateSelectedStationRing(stationCoord);
  }
}

// 加载所有路线并提取链接 & 站点
function loadAllData() {
  loading.value = true;
  stationPanelData.value = null;
  Promise.all([
    getLineAll({ datasource: props.model }),
    getStationPanel({ datasource: props.model }).catch(() => ({ data: null }))
  ])
    .then(([lineRes, panelRes]) => {
      const data = lineRes.data || [];
      rawLines.value = data;
      stationPanelData.value = panelRes.data || null;

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
                  const key = `${fac.coord.x.toFixed(2)}_${fac.coord.y.toFixed(2)}`;
                  const type = inferStationType(line, route);
                  if (!coordsSet.has(key)) {
                    coordsSet.add(key);
                    const station = {
                      name: fac.facilityName,
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

      _StationLayer.setData(stationsList);
    })
    .finally(() => {
      loading.value = false;
    });
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

function handleExportDetail() {
  if (proxy?.$message) {
    const text = activeDetailTab.value === "overview" 
      ? "站点数据分析" 
      : activeDetailTab.value === "boardingAlighting" 
        ? "站点乘降分析" 
        : activeDetailTab.value === "od"
          ? "站点OD分析"
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
    hours.push(`${hour.toString().padStart(2, "0")}:00`);
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
      bottom: "8%",
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
        fontSize: 10
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
              { offset: 0, color: "#10b981" },
              { offset: 1, color: "#059669" }
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

  return (currentStationPanel.value?.od || []).map((item) => {
    const flow = hourSlice(item.flowByHour, startHour, endHour).reduce((sum, value) => sum + value, 0);
    return {
      origin: item.origin,
      destination: item.destination,
      label: `${item.origin} - ${item.destination}`,
      counterpart: item.counterpart,
      flow,
      ratio: item.ratio
    };
  }).filter((item) => item.flow > 0)
    .sort((a, b) => b.flow - a.flow);
});

const odChartOption = computed(() => {
  const chartRows = odTableData.value.slice().reverse();
  const labels = chartRows.map(d => d.label);
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
              { offset: 0, color: "#3b82f6" },
              { offset: 1, color: "#60a5fa" }
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
  return {
    direct: toFiniteNumber(reachability.direct, 0),
    transfer1: toFiniteNumber(reachability.transfer1, 0),
    transfer2: toFiniteNumber(reachability.transfer2, 0)
  };
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
          { value: data.direct, name: "直达站点", itemStyle: { color: "#10b981" } },
          { value: data.transfer1, name: "一次换乘可达", itemStyle: { color: "#3b82f6" } },
          { value: data.transfer2, name: "二次换乘可达", itemStyle: { color: "#f59e0b" } }
        ]
      }
    ]
  };
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
    stationPanelData.value = null;
    selectedStationName.value = "";
    matchedRoutes.value = [];
    cleanUpSelectedStationRing();
    loadAllData();
  }
});

onUnmounted(() => {
  _StationLayer.dispose();
  cleanUpSelectedStationRing();
  rightPanelHasContent.value = false;
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
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.04) !important;
  border-radius: 8px !important;
  background-color: #ffffff;
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
        box-shadow: 0 0 0 1.5px rgba(21, 105, 222, 1) inset, 0 0 8px rgba(21, 105, 222, 0.15) !important;
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
        border-color: #10b981;
        .dot-inner { background: #10b981; }
      }
      &.last {
        border-color: #ef4444;
        .dot-inner { background: #ef4444; }
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
  color: #1a365d;
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
      box-shadow: 0 2px 6px rgba(21, 105, 222, 0.08);
      
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
        color: #718096;
      }
    }
    
    .item-body {
      display: flex;
      justify-content: space-between;
      font-size: 11px;
      color: #4a5568;
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
  background-color: #ffffff;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
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
    background: linear-gradient(135deg, #ffffff 0%, #fcfdfe 100%);
    border: 1px solid rgba(21, 105, 222, 0.12);
    border-radius: 8px;
    padding: 12px;
    display: flex;
    flex-direction: column;
    gap: 4px;
    box-sizing: border-box;
    box-shadow: 0 2px 8px rgba(21, 105, 222, 0.02);
    transition: all 0.3s ease;
    
    &:hover {
      transform: translateY(-2px);
      box-shadow: 0 6px 16px rgba(21, 105, 222, 0.08);
      border-color: rgba(21, 105, 222, 0.25);
    }
    
    .label {
      font-size: 12px;
      color: #718096;
      font-weight: 600;
    }
    
    .value {
      font-size: 18px;
      font-weight: bold;
      color: #1569de;
      font-family: "Outfit", "Inter", sans-serif;
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
      color: #1a365d;
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
    color: #1a365d;
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
.ranking-card {
  --theme-color: #1569de;
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
      background: #2f80ed !important;
      border-color: #2f80ed !important;
      color: #ffffff !important;
      box-shadow: 0 2px 6px rgba(21, 105, 222, 0.3);
    }
  }
}

.ranking-panel {
  display: flex;
  flex-direction: column;
  padding: 8px 4px;
}

.ranking-header {
  display: flex;
  background: linear-gradient(135deg, #1569de 0%, #2f80ed 100%);
  border-radius: 6px;
  padding: 10px 16px;
  color: #ffffff;
  font-size: 13px;
  font-weight: bold;
  margin-bottom: 8px;
  box-shadow: 0 4px 10px rgba(21, 105, 222, 0.15);
}

.ranking-scroll-list {
  max-height: calc(100vh - 280px);
  overflow-y: auto;
  padding-right: 6px;
  display: flex;
  flex-direction: column;
  gap: 6px;

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
  display: flex;
  align-items: center;
  padding: 12px 16px;
  background: #ffffff;
  border-bottom: 1px dashed rgba(21, 105, 222, 0.12);
  transition: all 0.25s ease;

  &:hover {
    background: rgba(21, 105, 222, 0.03);
    transform: translateX(4px);
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
  flex-grow: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-right: 12px;
  min-width: 0;
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
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  font-size: 12px;
  font-weight: 800;
  color: #718096;
  background: rgba(113, 128, 150, 0.08);

  &.gold {
    background: linear-gradient(135deg, #ffe066 0%, #f59e0b 100%);
    color: #ffffff;
    box-shadow: 0 2px 6px rgba(245, 158, 11, 0.3);
    font-size: 13px;
  }

  &.silver {
    background: linear-gradient(135deg, #f1f5f9 0%, #94a3b8 100%);
    color: #ffffff;
    box-shadow: 0 2px 6px rgba(148, 163, 184, 0.3);
    font-size: 13px;
  }

  &.bronze {
    background: linear-gradient(135deg, #ffedd5 0%, #ea580c 100%);
    color: #ffffff;
    box-shadow: 0 2px 6px rgba(234, 88, 12, 0.3);
    font-size: 13px;
  }
}

.route-name-text {
  font-size: 14px;
  font-weight: bold;
  color: #2d3748;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.route-desc-text {
  font-size: 11px;
  color: #a0aec0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.flow-value {
  font-size: 16px;
  font-weight: bold;
  color: #10b981;
  font-family: "Outfit", "Inter", sans-serif;
  
  .ranking-row:nth-child(-n+3) & {
    color: #f59e0b;
  }
}

.flow-unit {
  font-size: 11px;
  color: #718096;
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
      box-shadow: 0 4px 10px rgba(0, 0, 0, 0.05);
      
      &.subway {
        background: linear-gradient(135deg, rgba(239, 68, 68, 0.15) 0%, rgba(239, 68, 68, 0.05) 100%);
        border: 1px solid rgba(239, 68, 68, 0.2);
        color: #ef4444;
      }
      
      &.bus {
        background: linear-gradient(135deg, rgba(16, 185, 129, 0.15) 0%, rgba(16, 185, 129, 0.05) 100%);
        border: 1px solid rgba(16, 185, 129, 0.2);
        color: #10b981;
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
        color: #1a365d;
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
          color: #718096;
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
    color: #ef4444;
  }
  
  &.bus-badge {
    background: rgba(16, 185, 129, 0.1);
    color: #10b981;
  }
}

.matched-item {
  transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);
  &:hover {
    transform: translateY(-2px);
    box-shadow: 0 4px 12px rgba(21, 105, 222, 0.06);
    border-color: rgba(21, 105, 222, 0.3) !important;
  }
}

/* Tab header premium switcher */
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
      background: #2f80ed !important;
      border-color: #2f80ed !important;
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
        color: #1a365d;
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
      color: #1a365d;
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
        color: #1a365d;
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
      color: #1a365d;
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
      background: linear-gradient(135deg, #ffffff 0%, #fcfdfe 100%);
      border: 1px solid rgba(21, 105, 222, 0.12);
      border-radius: 8px;
      padding: 10px 8px;
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: 4px;
      box-shadow: 0 2px 8px rgba(21, 105, 222, 0.02);
      transition: all 0.3s ease;
      
      &:hover {
        transform: translateY(-2px);
        box-shadow: 0 6px 16px rgba(21, 105, 222, 0.08);
      }
      
      &.direct {
        border-color: rgba(16, 185, 129, 0.2);
        .value { color: #10b981; }
      }
      &.transfer1 {
        border-color: rgba(59, 130, 246, 0.2);
        .value { color: #3b82f6; }
      }
      &.transfer2 {
        border-color: rgba(245, 158, 11, 0.2);
        .value { color: #f59e0b; }
      }
      
      .label {
        font-size: 11px;
        color: #718096;
        font-weight: 600;
        text-align: center;
      }
      
      .value {
        font-size: 16px;
        font-weight: bold;
        font-family: "Outfit", "Inter", sans-serif;
        
        small {
          font-size: 10px;
          font-weight: normal;
          color: #718096;
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
      color: #1a365d;
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
    
    .col-od-stations { flex: 2.2; }
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
      font-family: "Outfit", "Inter", sans-serif;
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
        background: linear-gradient(90deg, #3b82f6, #60a5fa);
      }
      .ratio-text {
        font-size: 11px;
        font-weight: 600;
        color: #64748b;
        width: 38px;
        text-align: right;
      }
    }
    
    .col-od-stations {
      flex: 2.2;
      color: #1a365d;
      font-weight: 600;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    
    .col-od-flow {
      flex: 1.1;
      text-align: right;
      font-family: "Outfit", "Inter", sans-serif;
      font-weight: 700;
      color: #1569de;
      margin-right: 10px;
      
      small {
        font-size: 10px;
        font-weight: normal;
        color: #718096;
      }
    }
  }
}
</style>
