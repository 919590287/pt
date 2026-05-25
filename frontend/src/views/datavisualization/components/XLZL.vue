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
                {{ getDirectionLabel(index) }}
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

  <teleport to="#datavisualization_index_box2" defer v-if="currentSelectedRoute">
    <MCard2 class="SJZL_right_card" title="线路数据总览" :open="true">
      <template #body>
        <div class="route-detail-panel">
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
      </template>
    </MCard2>
  </teleport>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch, inject, computed } from "vue";
import { Search, Location, Timer, Connection } from "@element-plus/icons-vue";
import { getLineAll, getRouteDetail, getRouteTileBinary } from "@/api/route";
import MCard from "./MCard.vue";
import MCard2 from "./MCard2.vue";
import { RouteLayer } from "../layers/RouteLayer.js";
import { injectSync } from "@/utils";

const props = defineProps({
  model: String,
});

const loading = ref(true);
const searchMode = ref("line"); // "line" | "station"
const rawLines = ref([]);
const allLinks = ref([]);
const routeDetailCache = new Map();

const selectedLineName = ref("");
const selectedStationName = ref("");
const activeRouteId = ref("");
const activeMatchedRouteId = ref("");
const matchedRoutes = ref([]);
const selectedRouteDetail = ref(null);

// 注入来自 index.vue 的全局线宽配置与 MapRef
const LineWidthRef = inject("LineWidthRef", ref(100));
const MapRef = inject("MapRef", ref(null));

// 注入右侧面板显示控制
const rightPanelHasContent = inject("rightPanelHasContent", ref(false));
const activeDatavisualizationTab = inject("activeDatavisualizationTab", ref(""));

// 统一的当前选中路线计算属性
const currentSelectedRoute = computed(() => {
  const targetId = searchMode.value === "line" ? activeRouteId.value : activeMatchedRouteId.value;
  if (!targetId) return null;
  if (selectedRouteDetail.value?.routeId === targetId) return selectedRouteDetail.value;
  if (routeDetailCache.has(targetId)) return routeDetailCache.get(targetId);
  for (const line of rawLines.value) {
    if (line.routes) {
      const match = line.routes.find(r => r.routeId === targetId);
      if (match) return match;
    }
  }
  return null;
});

const routeMetrics = computed(() => {
  const route = currentSelectedRoute.value || {};
  const info = route.info || {};
  const length = Number(info.routeDist);
  const stationCount = Number(info.facNum || route.facilities?.length || 0);
  const avgStationDistance = Number(info.facDist);
  const fallbackStationDistance = stationCount > 1 && Number.isFinite(length) && length > 0
    ? length / (stationCount - 1)
    : 0;
  const directness = Number(info.lc);
  const passenger = Number(info.passenger);
  const loadRate = Number(info.takeRate);
  return {
    length: Number.isFinite(length) && length > 0 ? `${(length / 1000).toFixed(2)} km` : "--",
    firstTime: formatSecondsToTime(info.firstTime),
    lastTime: formatSecondsToTime(info.lastTime),
    directness: Number.isFinite(directness) && directness > 0 ? directness.toFixed(2) : "--",
    stationCount: stationCount > 0 ? `${stationCount} 个` : "--",
    avgStationDistance: Number.isFinite(avgStationDistance) && avgStationDistance > 0
      ? `${Math.round(avgStationDistance)} m`
      : fallbackStationDistance > 0 ? `${Math.round(fallbackStationDistance)} m` : "--",
    passenger: Number.isFinite(passenger) && passenger > 0 ? `${Math.round(passenger).toLocaleString()} 人次` : "--",
    loadRate: Number.isFinite(loadRate) && loadRate > 0 ? `${(loadRate * 100).toFixed(1)}%` : "--",
  };
});

// 监听当前选中的路线，控制右侧面板内容状态
watch(currentSelectedRoute, (newRoute) => {
  if (activeDatavisualizationTab.value === "线路分析") {
    rightPanelHasContent.value = !!newRoute;
  }
}, { immediate: true });

// 背景图层（深紫色常规线条效果）
const _BgRouteLayer = new RouteLayer({
  zIndex: 998,
  lineWidth: LineWidthRef.value,
  flowControl: false,
  color: 0x4f3db4
});

// 选中/激活路线图层（深黄高亮）
const _RouteLayer = new RouteLayer({
  zIndex: 999,
  lineWidth: LineWidthRef.value * 1.8,
  flowControl: false,
  color: 0xc87500,
  opacity: 1
});

// 将图层添加到地图
injectSync("MapRef").then((map) => {
  map.value?.addLayer(_BgRouteLayer);
  map.value?.addLayer(_RouteLayer);
  _BgRouteLayer.setTileSource(props.model, { tileRequest: getRouteTileBinary });
});

// 监听线宽变化
watch(LineWidthRef, (value) => {
  _BgRouteLayer.setLineWidth(value);
  _RouteLayer.setLineWidth(value * 1.8);
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

function getDirectionLabel(index) {
  if (index === 0) return '正向';
  if (index === 1) return '反向';
  return '支线';
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
  if (activeLinks?.length) {
    _BgRouteLayer.hide();
    _RouteLayer.setData(activeLinks);
  } else {
    _BgRouteLayer.show();
    _RouteLayer.setData([]);
    selectedRouteDetail.value = null;
  }
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
  const centerX = (minX + maxX) / 2;
  const centerY = (minY + maxY) / 2;
  MapRef.value?.setCenter([centerX, centerY]);
}

// 切换线路时
function handleLineChange(lineName) {
  if (!lineName) {
    activeRouteId.value = "";
    selectedRouteDetail.value = null;
    updateLayers(null);
    return;
  }
  const routes = selectedLineRoutes.value;
  if (routes && routes.length > 0) {
    handleSelectRoute(routes[0]);
  }
}

// 选择某条线路的某个方向
async function loadRouteDetail(route) {
  if (!route?.routeId) return route;
  if (routeDetailCache.has(route.routeId)) {
    return routeDetailCache.get(route.routeId);
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
  routeDetailCache.set(route.routeId, detail);
  return detail;
}

async function handleSelectRoute(route) {
  activeRouteId.value = route.routeId;
  const detail = await loadRouteDetail(route);
  selectedRouteDetail.value = detail;
  if (detail?.links && detail.links.length > 0) {
    updateLayers(detail.links);
    centerOnRoute(detail.links);
  }
}

// 切换站点时
function handleStationChange(stationName) {
  if (!stationName) {
    matchedRoutes.value = [];
    activeMatchedRouteId.value = "";
    selectedRouteDetail.value = null;
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
  updateLayers(null); // 不要自动选中第一条线路
}

// 选择途径该站点的某条线路
async function handleSelectMatchedRoute(item) {
  activeMatchedRouteId.value = item.routeId;
  const detail = await loadRouteDetail(item);
  selectedRouteDetail.value = detail;
  if (detail?.links) {
    updateLayers(detail.links);
    centerOnRoute(detail.links);
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
  updateLayers(null);
});

// 加载所有路线并提取链接
function loadAllLines() {
  loading.value = true;
  getLineAll({ datasource: props.model })
    .then((res) => {
      const data = res.data || [];
      rawLines.value = data;
      allLinks.value = [];
      _BgRouteLayer.setTileSource(props.model, { tileRequest: getRouteTileBinary });
      updateLayers(null);
    })
    .finally(() => {
      loading.value = false;
    });
}

onMounted(() => {
  if (props.model) {
    loadAllLines();
  }
});

watch(() => props.model, (newModel) => {
  if (newModel) {
    routeDetailCache.clear();
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
  _BgRouteLayer.dispose();
  _RouteLayer.dispose();
});
</script>

<style lang="scss" scoped>
.XLZL {
  display: flex;
  flex-direction: column;
  gap: 12px;
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

.search-mode-container {
  display: flex;
  justify-content: center;
  margin-bottom: 12px;
  
  .search-mode-group {
    width: 100%;
    display: flex;
    :deep(.el-radio-button) {
      flex: 1;
      .el-radio-button__inner {
        width: 100%;
        border-radius: 6px;
        font-weight: 600;
        transition: all 0.3s ease;
      }
    }
  }
}

.search-input-wrapper {
  margin-bottom: 14px;
  
  .custom-select {
    width: 100%;
    :deep(.el-input__wrapper) {
      box-shadow: 0 0 0 1px rgba(21, 105, 222, 0.15) inset !important;
      border-radius: 6px;
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

.route-directions {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
  background: rgba(240, 244, 248, 0.7);
  padding: 4px;
  border-radius: 6px;
  
  .direction-pill {
    flex: 1;
    text-align: center;
    padding: 6px 12px;
    font-size: 13px;
    font-weight: 600;
    color: #4a5568;
    cursor: pointer;
    border-radius: 4px;
    transition: all 0.2s ease;
    
    &:hover {
      background: rgba(255, 255, 255, 0.8);
      color: #1569de;
    }
    
    &.active {
      background: #ffffff;
      color: #1569de;
      box-shadow: 0 2px 6px rgba(21, 105, 222, 0.12);
    }
  }
}

.route-info-panel {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  background: linear-gradient(135deg, rgba(21, 105, 222, 0.03) 0%, rgba(21, 105, 222, 0.08) 100%);
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
      color: #718096;
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
    color: #1a365d;
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
    cursor: pointer;
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
  --theme-color: #4f3db4;
  width: 470px;
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
    border: 1px solid rgba(79, 61, 180, 0.12);
    border-radius: 8px;
    padding: 12px;
    display: flex;
    flex-direction: column;
    gap: 4px;
    box-sizing: border-box;
    box-shadow: 0 2px 8px rgba(79, 61, 180, 0.02);
    transition: all 0.3s ease;
    
    &:hover {
      transform: translateY(-2px);
      box-shadow: 0 6px 16px rgba(79, 61, 180, 0.08);
      border-color: rgba(79, 61, 180, 0.25);
    }
    
    .label {
      font-size: 12px;
      color: #718096;
      font-weight: 600;
    }
    
    .value {
      font-size: 18px;
      font-weight: bold;
      color: #4f3db4;
      font-family: "Outfit", "Inter", sans-serif;
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
    max-height: calc(100vh - 460px);
    overflow-y: auto;
    padding-right: 6px;
    
    &::-webkit-scrollbar {
      width: 6px;
    }
    &::-webkit-scrollbar-thumb {
      background: rgba(79, 61, 180, 0.2);
      border-radius: 3px;
    }
    &::-webkit-scrollbar-thumb:hover {
      background: rgba(79, 61, 180, 0.4);
    }
  }
}
</style>
