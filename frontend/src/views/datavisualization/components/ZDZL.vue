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

  <teleport to="#datavisualization_index_box2" defer v-if="selectedStationName">
    <MCard2 class="SJZL_right_card" title="站点数据分析" :open="true">
      <template #body>
        <div class="route-detail-panel">
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
              <span class="value">{{ mockStationMetrics.passenger }}</span>
            </div>
            <div class="metric-card">
              <span class="label">高峰小时客流</span>
              <span class="value">{{ mockStationMetrics.peakFlow }}</span>
            </div>
            <div class="metric-card">
              <span class="label">周边人口覆盖</span>
              <span class="value">{{ mockStationMetrics.population }}</span>
            </div>
            <div class="metric-card">
              <span class="label">换乘便利度</span>
              <span class="value">{{ mockStationMetrics.transferScore }}</span>
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
      </template>
    </MCard2>
  </teleport>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch, inject, computed } from "vue";
import { Location } from "@element-plus/icons-vue";
import { getLineAll } from "@/api/route";
import MCard from "./MCard.vue";
import MCard2 from "./MCard2.vue";
import { StationLayer } from "../layers/StationLayer.js";
import { injectSync } from "@/utils";

const props = defineProps({
  model: String,
});

const loading = ref(true);
const rawLines = ref([]);

const selectedStationName = ref("");
const matchedRoutes = ref([]);

const StationSizeRef = inject("StationSizeRef", ref(40));
const MapRef = inject("MapRef", ref(null));

// 注入右侧面板显示控制
const rightPanelHasContent = inject("rightPanelHasContent", ref(false));
const activeDatavisualizationTab = inject("activeDatavisualizationTab", ref(""));

function updateRightPanelVisibility() {
  if (activeDatavisualizationTab.value === "站点分析") {
    rightPanelHasContent.value = !!selectedStationName.value;
  }
}

watch(selectedStationName, () => {
  updateRightPanelVisibility();
}, { immediate: true });

const selectedStationType = computed(() => {
  if (!selectedStationName.value) return "";
  const isSubway = matchedRoutes.value.some(r => {
    const text = [r.lineName, r.lineId, r.routeName, r.routeId].filter(Boolean).join(" ").toLowerCase();
    return /地铁|轨道|metro|subway|rail|mtr/.test(text);
  });
  return isSubway ? "地铁" : "公交";
});

const mockStationMetrics = computed(() => {
  if (!selectedStationName.value) return {};
  
  let seed = 0;
  for (let i = 0; i < selectedStationName.value.length; i++) {
    seed += selectedStationName.value.charCodeAt(i);
  }
  
  const isSubway = selectedStationType.value === "地铁";
  
  const passenger = isSubway
    ? Math.round(15000 + (seed % 10) * 4500)
    : Math.round(1200 + (seed % 10) * 350);
  
  const peakFlow = Math.round(passenger * 0.15);
  
  const population = isSubway
    ? `${((3.2 + (seed % 5) * 0.8)).toFixed(1)} 万`
    : `${((0.6 + (seed % 5) * 0.25)).toFixed(2)} 万`;
    
  const transferScore = isSubway
    ? `${((8.5 + (seed % 3) * 0.5)).toFixed(1)} / 10`
    : `${((6.0 + (seed % 5) * 0.6)).toFixed(1)} / 10`;

  return {
    passenger: `${passenger.toLocaleString()} 人次`,
    peakFlow: `${peakFlow.toLocaleString()} 人/小时`,
    population,
    transferScore
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

// 切换站点时
function handleStationChange(stationName) {
  if (!stationName) {
    matchedRoutes.value = [];
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

  // 居中到选中的站点坐标
  if (stationCoord && MapRef.value) {
    MapRef.value.setCenter([stationCoord.x, stationCoord.y]);
  }
}

// 加载所有路线并提取链接 & 站点
function loadAllData() {
  loading.value = true;
  getLineAll({ datasource: props.model })
    .then((res) => {
      const data = res.data || [];
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

onMounted(() => {
  if (props.model) {
    loadAllData();
  }
});

watch(() => props.model, (newModel) => {
  if (newModel) {
    selectedStationName.value = "";
    matchedRoutes.value = [];
    updateRightPanelVisibility();
    loadAllData();
  }
});

onUnmounted(() => {
  _StationLayer.dispose();
  rightPanelHasContent.value = false;
});
</script>

<style lang="scss" scoped>
.ZDZL {
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
      border-radius: 10px;
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
</style>
