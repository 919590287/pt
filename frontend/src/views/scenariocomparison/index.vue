<!-- Scenario Comparison (场景对比) View -->
<template>
  <div ref="panelRef" :style="panelStyle" class="comparison-panel">
    <!-- Panel Header / Drag Handle (Unified MCard2 Style) -->
    <div ref="handleRef" class="panel-header">
      <div class="header-title">
        <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="18" y1="20" x2="18" y2="10"></line>
          <line x1="12" y1="20" x2="12" y2="4"></line>
          <line x1="6" y1="20" x2="6" y2="14"></line>
        </svg>
        <span>场景绩效对比 (Scenario Comparison)</span>
      </div>
      <div class="header-subtitle">多维指标看板</div>
    </div>

    <!-- Panel Scrollable Body -->
    <el-scrollbar class="panel-content">
      <div class="inner-container">
        
        <!-- STEP 1: Select Scenarios -->
        <div class="section-card">
          <div class="card-title">
            <span class="step-num">A/B</span>
            <span>选择对比方案</span>
          </div>

          <div class="selection-grid">
            <div class="sel-row">
              <span class="sel-label base-glow">基准场景 (A)</span>
              <el-select v-model="selectedBase" class="block-select" size="small">
                <el-option label="现状基准仿真场景 (福田核心区)" value="base" />
              </el-select>
            </div>
            <div class="sel-row">
              <span class="sel-label opt-glow">对比场景 (B)</span>
              <el-select v-model="selectedOpt" class="block-select" size="small" @change="handleOptChange">
                <el-option label="高峰拥堵通勤保障场景 (发车+优先道)" value="peak" />
                <el-option label="低碳绿色环保公交场景 (全电+换乘)" value="green" />
                <el-option label="车路协同自适应智能排班场景" value="smart" />
              </el-select>
            </div>
          </div>
        </div>

        <!-- STEP 2: KPI Comparison Cards -->
        <div class="section-card">
          <div class="card-title">
            <span class="step-num">KPI</span>
            <span>核心绩效对比指标</span>
          </div>

          <div class="kpi-grid">
            <!-- Avg Commute Time -->
            <div class="kpi-card">
              <div class="kpi-label">平均通勤时间 (人均)</div>
              <div class="kpi-comparison">
                <span class="val-num base-color">{{ currentKPI.commute.base }}<span class="unit">m</span></span>
                <svg class="arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                  <line x1="5" y1="12" x2="19" y2="12"></line>
                  <polyline points="12 5 19 12 12 19"></polyline>
                </svg>
                <span class="val-num opt-color">{{ currentKPI.commute.opt }}<span class="unit">m</span></span>
              </div>
              <div class="badge green-badge">
                <span>降低 {{ currentKPI.commute.pct }}%</span>
              </div>
            </div>

            <!-- Avg Wait Time -->
            <div class="kpi-card">
              <div class="kpi-label">平均高峰等车时间</div>
              <div class="kpi-comparison">
                <span class="val-num base-color">{{ currentKPI.wait.base }}<span class="unit">m</span></span>
                <svg class="arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                  <line x1="5" y1="12" x2="19" y2="12"></line>
                  <polyline points="12 5 19 12 12 19"></polyline>
                </svg>
                <span class="val-num opt-color">{{ currentKPI.wait.opt }}<span class="unit">m</span></span>
              </div>
              <div class="badge green-badge">
                <span>降低 {{ currentKPI.wait.pct }}%</span>
              </div>
            </div>

            <!-- On-time Rate -->
            <div class="kpi-card">
              <div class="kpi-label">公交准点运行率</div>
              <div class="kpi-comparison">
                <span class="val-num base-color">{{ currentKPI.ontime.base }}<span class="unit">%</span></span>
                <svg class="arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                  <line x1="5" y1="12" x2="19" y2="12"></line>
                  <polyline points="12 5 19 12 12 19"></polyline>
                </svg>
                <span class="val-num opt-color">{{ currentKPI.ontime.opt }}<span class="unit">%</span></span>
              </div>
              <div class="badge green-badge">
                <span>提升 {{ currentKPI.ontime.pct }}%</span>
              </div>
            </div>

            <!-- Carbon Savings -->
            <div class="kpi-card">
              <div class="kpi-label">全天碳减排量 (CO₂)</div>
              <div class="kpi-comparison">
                <span class="val-num base-color">{{ currentKPI.carbon.base }}<span class="unit">t</span></span>
                <svg class="arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                  <line x1="5" y1="12" x2="19" y2="12"></line>
                  <polyline points="12 5 19 12 12 19"></polyline>
                </svg>
                <span class="val-num opt-color">{{ currentKPI.carbon.opt }}<span class="unit">t</span></span>
              </div>
              <div class="badge green-badge">
                <span>减排 {{ currentKPI.carbon.pct }}%</span>
              </div>
            </div>
          </div>
        </div>

        <!-- STEP 3: Radar Chart Analysis -->
        <div class="section-card">
          <div class="card-title">
            <span class="step-num">DIA</span>
            <span>多维雷达图综合绩效评估</span>
          </div>
          <div class="chart-container">
            <el-auto-resizer>
              <template #default="{ height, width }">
                <VChart 
                  v-if="width > 0 && height > 0" 
                  class="radar-chart" 
                  :option="radarOption" 
                  autoresize 
                />
              </template>
            </el-auto-resizer>
          </div>
        </div>

        <!-- STEP 4: Flow Line Curve Chart -->
        <div class="section-card">
          <div class="card-title">
            <span class="step-num">FLO</span>
            <span>客流小时运载效率对比</span>
          </div>
          <div class="chart-container line-chart-h">
            <el-auto-resizer>
              <template #default="{ height, width }">
                <VChart 
                  v-if="width > 0 && height > 0" 
                  class="line-chart" 
                  :option="lineOption" 
                  autoresize 
                />
              </template>
            </el-auto-resizer>
          </div>
        </div>

      </div>
    </el-scrollbar>
  </div>
</template>

<script setup>
import { ref, computed, inject, onMounted, onUnmounted, watch, getCurrentInstance } from "vue";
import { useDraggable } from "@vueuse/core";

const { proxy } = getCurrentInstance();
const MapRef = inject("MapRef");

// Panel Draggability setup
const panelRef = ref(null);
const handleRef = ref(null);
const { style: panelStyle } = useDraggable(panelRef, {
  initialValue: { x: 20, y: 120 },
  handle: handleRef,
});

// Selection variables
const selectedBase = ref("base");
const selectedOpt = ref("peak");

// Preset Mock KPI Data
const mockKPIData = {
  peak: {
    commute: { base: 36.8, opt: 28.5, pct: 22.5 },
    wait: { base: 9.4, opt: 6.2, pct: 34.0 },
    ontime: { base: 81.2, opt: 95.4, pct: 17.5 },
    carbon: { base: 120.5, opt: 104.2, pct: 13.5 }
  },
  green: {
    commute: { base: 36.8, opt: 30.1, pct: 18.2 },
    wait: { base: 9.4, opt: 7.5, pct: 20.2 },
    ontime: { base: 81.2, opt: 89.2, pct: 9.8 },
    carbon: { base: 120.5, opt: 48.2, pct: 60.0 }
  },
  smart: {
    commute: { base: 36.8, opt: 26.2, pct: 28.8 },
    wait: { base: 9.4, opt: 5.1, pct: 45.7 },
    ontime: { base: 81.2, opt: 98.2, pct: 20.9 },
    carbon: { base: 120.5, opt: 96.4, pct: 20.0 }
  }
};

const currentKPI = computed(() => mockKPIData[selectedOpt.value]);

// Preset Study Area Coordinates on Map Libre (Shenzhen core)
const SOURCE_ID = "study-area-source";
const FILL_LAYER_ID = "study-area-fill";
const STROKE_LAYER_ID = "study-area-stroke";

function drawPresetStudyArea() {
  if (!MapRef.value || !MapRef.value.map) return;
  const map = MapRef.value.map;

  // Center coordinates dynamically
  const center = map.getCenter() || { lng: 113.498, lat: 23.218 };
  const radius = 1.35;
  const sides = 9;
  const points = [];
  
  for (let i = 0; i < sides; i++) {
    const angle = (i / sides) * 2 * Math.PI;
    const noise = 0.82 + Math.sin(i * 2.5) * 0.18;
    const dist = radius * noise;
    const latOffset = (dist / 111.3) * Math.sin(angle);
    const lngOffset = (dist / (111.3 * Math.cos(center.lat * Math.PI / 180))) * Math.cos(angle);
    points.push([center.lng + lngOffset, center.lat + latOffset]);
  }
  points.push(points[0]);

  const geojson = {
    type: "Feature",
    geometry: {
      type: "Polygon",
      coordinates: [points]
    },
    properties: {}
  };

  if (!map.getSource(SOURCE_ID)) {
    map.addSource(SOURCE_ID, {
      type: "geojson",
      data: geojson
    });
    map.addLayer({
      id: FILL_LAYER_ID,
      type: "fill",
      source: SOURCE_ID,
      paint: {
        "fill-color": "#000000",
        "fill-opacity": 0.08
      }
    });
    map.addLayer({
      id: STROKE_LAYER_ID,
      type: "line",
      source: SOURCE_ID,
      paint: {
        "line-color": "#0e0e0f",
        "line-width": 5.0,
        "line-dasharray": [3.5, 2.5]
      }
    });
  } else {
    map.getSource(SOURCE_ID).setData(geojson);
  }

  // Focus viewport on preset
  map.fitBounds([
    [center.lng - 0.02, center.lat - 0.02],
    [center.lng + 0.02, center.lat + 0.02]
  ], {
    padding: 100,
    duration: 1000
  });
}

function cleanUpMapLayers() {
  if (!MapRef.value || !MapRef.value.map) return;
  const map = MapRef.value.map;
  if (map.getLayer(FILL_LAYER_ID)) map.removeLayer(FILL_LAYER_ID);
  if (map.getLayer(STROKE_LAYER_ID)) map.removeLayer(STROKE_LAYER_ID);
  if (map.getSource(SOURCE_ID)) map.removeSource(SOURCE_ID);
}

// ---------------- CHART CONFIGURATIONS (ECHARTS) ----------------

// Radar Chart Options
const radarOption = computed(() => {
  let optValues = [];
  if (selectedOpt.value === "peak") {
    optValues = [95, 88, 85, 75, 92, 85]; // peak
  } else if (selectedOpt.value === "green") {
    optValues = [82, 90, 78, 98, 80, 88]; // green (eco)
  } else {
    optValues = [98, 95, 92, 82, 96, 94]; // smart (V2X)
  }

  return {
    tooltip: {
      trigger: "item",
      backgroundColor: "rgba(255, 255, 255, 0.95)",
      borderColor: "rgba(21, 105, 222, 0.2)",
      textStyle: { color: "#2c3e50", fontSize: 11 }
    },
    legend: {
      bottom: 0,
      icon: "circle",
      itemWidth: 8,
      itemHeight: 8,
      textStyle: { color: "#7f8c8d", fontSize: 11 },
      data: ["基准场景 (A)", "对比场景 (B)"]
    },
    radar: {
      center: ["50%", "45%"],
      radius: "60%",
      axisName: {
        color: "#7f8c8d",
        fontSize: 10,
        fontFamily: "sans-serif",
        fontWeight: 600
      },
      splitArea: {
        areaStyle: {
          color: ["rgba(0,0,0,0.01)", "rgba(0,0,0,0.025)"]
        }
      },
      splitLine: {
        lineStyle: { color: "rgba(0,0,0,0.05)" }
      },
      axisLine: {
        lineStyle: { color: "rgba(0,0,0,0.05)" }
      },
      indicator: [
        { name: "公交准点率", max: 100 },
        { name: "市民满意度", max: 100 },
        { name: "运营效率", max: 100 },
        { name: "全天低碳度", max: 100 },
        { name: "线网覆盖面", max: 100 },
        { name: "候车舒适度", max: 100 }
      ]
    },
    series: [
      {
        type: "radar",
        data: [
          {
            value: [78, 65, 68, 50, 80, 60],
            name: "基准场景 (A)",
            itemStyle: { color: "#a855f7" },
            lineStyle: { width: 1.5, type: "dashed" },
            areaStyle: { color: "rgba(168, 85, 247, 0.04)" }
          },
          {
            value: optValues,
            name: "对比场景 (B)",
            itemStyle: { color: "#1569de" },
            lineStyle: { width: 2 },
            areaStyle: { color: "rgba(21, 105, 222, 0.1)" }
          }
        ]
      }
    ]
  };
});

// Passenger Capacity Timeline Curves
const lineOption = computed(() => {
  let optValues = [];
  if (selectedOpt.value === "peak") {
    optValues = [42, 60, 110, 160, 120, 95, 115, 180, 130, 80]; // high peak capacity
  } else if (selectedOpt.value === "green") {
    optValues = [35, 50, 98, 140, 105, 85, 100, 155, 115, 70]; // standard green flow
  } else {
    optValues = [45, 65, 125, 185, 140, 110, 130, 205, 150, 90]; // smart dynamic scheduling
  }

  return {
    tooltip: {
      trigger: "axis",
      backgroundColor: "rgba(255, 255, 255, 0.95)",
      borderColor: "rgba(21, 105, 222, 0.2)",
      textStyle: { color: "#2c3e50", fontSize: 11 }
    },
    legend: {
      top: 0,
      right: "5%",
      icon: "circle",
      itemWidth: 8,
      itemHeight: 8,
      textStyle: { color: "#7f8c8d", fontSize: 10 },
      data: ["基准场景", "对比场景"]
    },
    grid: {
      top: "16%",
      left: "4%",
      right: "4%",
      bottom: "10%",
      containLabel: true
    },
    xAxis: {
      type: "category",
      boundaryGap: false,
      data: ["07:00", "08:00", "09:00", "10:00", "12:00", "14:00", "16:00", "18:00", "20:00", "22:00"],
      axisLabel: { color: "#7f8c8d", fontSize: 9 },
      axisLine: { lineStyle: { color: "rgba(0,0,0,0.06)" } }
    },
    yAxis: {
      type: "value",
      name: "人次/10min",
      nameTextStyle: { color: "#7f8c8d", fontSize: 9 },
      splitLine: { lineStyle: { color: "rgba(0,0,0,0.04)" } },
      axisLabel: { color: "#7f8c8d", fontSize: 9 }
    },
    series: [
      {
        name: "基准场景",
        type: "line",
        smooth: true,
        showSymbol: false,
        data: [30, 42, 85, 120, 88, 70, 85, 135, 95, 60],
        itemStyle: { color: "#a855f7" },
        lineStyle: { width: 1.5, type: "dashed" }
      },
      {
        name: "对比场景",
        type: "line",
        smooth: true,
        showSymbol: false,
        data: optValues,
        itemStyle: { color: "#1569de" },
        lineStyle: { width: 2.5 },
        areaStyle: {
          color: new proxy.$echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: "rgba(21, 105, 222, 0.2)" },
            { offset: 1, color: "rgba(21, 105, 222, 0.0)" }
          ])
        }
      }
    ]
  };
});

function handleOptChange() {
  // Option change triggers
}

// ---------------- COMPONENT CYCLE ----------------

onMounted(() => {
  if (MapRef.value && MapRef.value.map) {
    drawPresetStudyArea();
  }
});

// Track MapRef loading dynamically
watch(MapRef, (newMap) => {
  if (newMap && newMap.map) {
    drawPresetStudyArea();
  }
});

onUnmounted(() => {
  cleanUpMapLayers();
});
</script>

<style lang="scss" scoped>
/* LIGHT THEME UNIFICATION (Matching Data Visualization Panel Styles) */
.comparison-panel {
  position: fixed;
  z-index: var(--z-panel);
  width: 460px;
  max-height: calc((100vh - 132px) / var(--app-panel-scale));
  background: var(--app-panel-bg);
  border: 1px solid var(--app-border);
  box-shadow: var(--app-shadow-md);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  border-radius: var(--app-panel-radius);
  display: flex;
  flex-direction: column;
  color: var(--app-ink);
  user-select: none;
  overflow: hidden;
  scale: var(--app-panel-scale);
  transform-origin: top left;
  transition: box-shadow 0.3s ease, border-color 0.3s ease;
  
  &:hover {
    border-color: rgba(21, 105, 222, 0.35);
    box-shadow: 0 12px 35px rgba(15, 66, 125, 0.18);
  }
}

.panel-header {
  cursor: move;
  display: flex;
  padding: var(--space-xs) var(--space-md);
  gap: var(--space-sm);
  align-items: center;
  min-height: 42px;
  background: linear-gradient(to bottom, rgba(21, 105, 222, 0.12) 0%, rgba(21, 105, 222, 0.04) 100%);
  color: var(--app-blue);
  border-bottom: 1px solid rgba(21, 105, 222, 0.15);

  .header-title {
    display: flex;
    align-items: center;
    gap: var(--space-xs);
    font-size: 15px;
    font-weight: 700;
    letter-spacing: 0;
    width: 0;
    flex: 1;
    min-width: 0;

    span {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .icon {
      width: 17px;
      height: 17px;
      color: #1569de;
    }
  }

  .header-subtitle {
    font-size: 10px;
    color: var(--app-blue);
    border: 1px solid rgba(21, 105, 222, 0.25);
    padding: 1px 5px;
    border-radius: 4px;
    background: rgba(21, 105, 222, 0.08);
    font-family: "Outfit", monospace;
    font-weight: 600;
  }
}

.panel-content {
  flex: 1;
  overflow: hidden;
}

.inner-container {
  padding: var(--space-md);
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

/* Steps Cards styling */
.section-card {
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid rgba(21, 105, 222, 0.11);
  border-radius: var(--app-card-radius);
  padding: var(--space-sm);
  position: relative;
  overflow: hidden;

  .card-title {
    display: flex;
    align-items: center;
    gap: var(--space-xs);
    font-size: 13px;
    font-weight: 700;
    color: var(--app-ink);
    margin-bottom: var(--space-sm);

    .step-num {
      font-family: "Outfit", "Impact", monospace;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      min-width: 34px;
      height: 22px;
      padding: 0 var(--space-2xs);
      border-radius: 4px;
      background: rgba(21, 105, 222, 0.08);
      font-size: 12px;
      color: var(--app-blue);
      letter-spacing: 0.5px;
    }
  }
}

/* Selector Grids */
.selection-grid {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  background: rgba(21, 105, 222, 0.02);
  padding: var(--space-sm);
  border-radius: var(--app-card-radius);
  border: 1px solid rgba(21, 105, 222, 0.08);
}

.sel-row {
  display: flex;
  flex-direction: column;
  gap: var(--space-2xs);

  .sel-label {
    font-size: 10.5px;
    font-weight: bold;
    color: #7f8c8d;
  }

  .base-glow {
    color: #a855f7;
  }

  .opt-glow {
    color: #1569de;
  }

  .block-select {
    width: 100%;
  }
}

/* KPI Dashboards layout */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-xs);
}

.kpi-card {
  background: rgba(21, 105, 222, 0.03);
  border: 1px solid rgba(21, 105, 222, 0.08);
  border-radius: var(--app-card-radius);
  padding: var(--space-xs);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: var(--space-xs);
  min-height: 72px;

  .kpi-label {
    font-size: 10px;
    font-weight: 600;
    color: #7f8c8d;
    line-height: 1.35;
  }

  .kpi-comparison {
    display: flex;
    align-items: center;
    gap: var(--space-2xs);
    margin: 0;

    .arrow {
      width: 11px;
      height: 11px;
      color: #7f8c8d;
    }

    .val-num {
      font-size: 13.5px;
      font-weight: 700;
      font-family: "Outfit", monospace, sans-serif;

      .unit {
        font-size: 9px;
        font-weight: normal;
        margin-left: 1px;
      }
    }

    .base-color {
      color: #9b59b6;
    }

    .opt-color {
      color: #1569de;
    }
  }

  .badge {
    align-self: flex-start;
    display: flex;
    align-items: center;
    border-radius: 4px;
    padding: 1.5px 5px;
    font-size: 9px;
    font-weight: bold;
  }

  .green-badge {
    background: #e8f8f5;
    border: 1px solid rgba(46, 204, 113, 0.35);
    color: #27ae60;
  }
}

/* ECharts Container wrapper */
.chart-container {
  width: 100%;
  height: 188px;
  background: #ffffff;
  border-radius: var(--app-card-radius);
  border: 1px solid rgba(0, 0, 0, 0.04);
  padding: var(--space-xs);
  box-sizing: border-box;
}

.line-chart-h {
  height: 168px;
}

.radar-chart, .line-chart {
  width: 100%;
  height: 100%;
}
</style>
