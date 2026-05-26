<!-- 体检评估分析 (Transit Network Health Evaluation) -->
<template>
  <div class="TJFX" v-bind="$attrs">
    <div class="info-container">
      <MCard class="card search-card" :open="true" title="公交体检指标评估">
        <template #body>
          <div class="evaluation-form">
            <div class="form-row">
              <span class="label">评估方案</span>
              <el-select v-model="selectedScheme" placeholder="请选择评估方案" class="custom-select">
                <el-option label="基础方案" value="base" />
                <el-option label="线网优化方案A" value="schemeA" />
                <el-option label="线网优化方案B" value="schemeB" />
              </el-select>
            </div>
            
            <div class="form-row">
              <span class="label">分析时段</span>
              <el-select v-model="selectedPeriod" placeholder="请选择分析时段" class="custom-select">
                <el-option label="早高峰 (07:00 - 09:00)" value="morning" />
                <el-option label="晚高峰 (17:00 - 19:00)" value="evening" />
                <el-option label="全天运营时段" value="allday" />
              </el-select>
            </div>

            <div class="button-wrapper">
              <el-button 
                type="primary" 
                class="evaluate-btn"
                :loading="evaluating"
                @click="handleStartEvaluation"
              >
                <el-icon style="margin-right: 6px;"><Opportunity /></el-icon>
                开始体检评估
              </el-button>
            </div>
          </div>
        </template>
      </MCard>
    </div>
  </div>

  <teleport to="#datavisualization_index_box2" defer>
    <MCard2 class="SJZL_right_card evaluation-report-card" title="公交网络体检评估报告" :open="true">
      <template #body>
        <div class="report-panel">
          <!-- Gauge Chart Score Container -->
          <div class="score-section">
            <div class="chart-container-wrapper score-gauge-container">
              <el-auto-resizer class="chart_box">
                <template #default="{ height, width }">
                  <VChart
                    v-if="width > 0 && height > 0"
                    class="score-chart"
                    :option="healthScoreChartOption"
                    autoresize
                    :update-options="{ notMerge: true }"
                  />
                </template>
              </el-auto-resizer>
            </div>
          </div>

          <!-- 4 Metrics Scorecard Grid -->
          <div class="metrics-grid">
            <div class="metric-card">
              <span class="label">覆盖指数</span>
              <span class="value">92%</span>
              <span class="badge excel">优秀</span>
            </div>
            <div class="metric-card">
              <span class="label">快捷指数</span>
              <span class="value">78%</span>
              <span class="badge good">良好</span>
            </div>
            <div class="metric-card">
              <span class="label">可靠指数</span>
              <span class="value">88%</span>
              <span class="badge excel">优秀</span>
            </div>
            <div class="metric-card">
              <span class="label">绿色出行比例</span>
              <span class="value">64%</span>
              <span class="badge good">良好</span>
            </div>
          </div>

          <!-- Dimension Breakdown Chart -->
          <div class="dimension-section">
            <div class="section-title">评估维度得分明细</div>
            <div class="chart-container-wrapper dimension-chart-container">
              <el-auto-resizer class="chart_box">
                <template #default="{ height, width }">
                  <VChart
                    v-if="width > 0 && height > 0"
                    class="dim-chart"
                    :option="dimensionChartOption"
                    autoresize
                    :update-options="{ notMerge: true }"
                  />
                </template>
              </el-auto-resizer>
            </div>
          </div>
        </div>
      </template>
    </MCard2>
  </teleport>
</template>

<script setup>
import { ref, computed, getCurrentInstance, onMounted, onUnmounted, inject, watch } from "vue";
import { Opportunity } from "@element-plus/icons-vue";
import MCard from "./MCard.vue";
import MCard2 from "./MCard2.vue";

const props = defineProps({
  model: String,
});

const { proxy } = getCurrentInstance() || {};

const selectedScheme = ref("base");
const selectedPeriod = ref("allday");
const evaluating = ref(false);

const rightPanelHasContent = inject("rightPanelHasContent", ref(false));
const activeDatavisualizationTab = inject("activeDatavisualizationTab", ref(""));

function updateRightPanelVisibility() {
  if (activeDatavisualizationTab.value === "体检评估分析") {
    rightPanelHasContent.value = true;
  }
}

onMounted(() => {
  updateRightPanelVisibility();
});

watch(activeDatavisualizationTab, (newTab) => {
  if (newTab === "体检评估分析") {
    rightPanelHasContent.value = true;
  }
});

onUnmounted(() => {
  rightPanelHasContent.value = false;
});

function handleStartEvaluation() {
  evaluating.value = true;
  setTimeout(() => {
    evaluating.value = false;
    if (proxy?.$message) {
      proxy.$message.success({
        message: "公交网络体检评估计算完成！报告已刷新。",
        type: "success",
        duration: 2000
      });
    }
  }, 1200);
}

// 评分 ECharts 配置
const healthScoreChartOption = computed(() => {
  const linearGradient = (proxy?.$echarts?.graphic?.LinearGradient) || function() { return null; };
  return {
    series: [
      {
        type: "gauge",
        startAngle: 195,
        endAngle: -15,
        center: ["50%", "72%"],
        radius: "105%",
        min: 0,
        max: 100,
        progress: {
          show: true,
          width: 14,
          itemStyle: {
            color: new linearGradient(0, 0, 1, 0, [
              { offset: 0, color: "#10b981" },
              { offset: 1, color: "#1569de" }
            ])
          }
        },
        axisLine: {
          lineStyle: {
            width: 14,
            color: [[1, "rgba(21, 105, 222, 0.08)"]]
          }
        },
        axisTick: { show: false },
        splitLine: { show: false },
        axisLabel: { show: false },
        pointer: { show: false },
        anchor: { show: false },
        title: {
          show: true,
          offsetCenter: [0, "-22%"],
          fontSize: 13,
          color: "#718096",
          fontWeight: "bold"
        },
        detail: {
          show: true,
          offsetCenter: [0, "10%"],
          valueAnimation: true,
          formatter: function (value) {
            return value + "分";
          },
          color: "#1569de",
          fontSize: 34,
          fontWeight: "bold",
          fontFamily: "Outfit, Inter, sans-serif"
        },
        data: [{ value: 86, name: "网络健康度综合评分" }]
      }
    ]
  };
});

// 维度评估 ECharts 配置
const dimensionChartOption = computed(() => {
  const linearGradient = (proxy?.$echarts?.graphic?.LinearGradient) || function() { return null; };
  const categories = ["运行速度", "可靠度", "客流效益", "供需匹配", "行车安全", "服务指数"];
  const scores = [82, 88, 75, 91, 85, 89];
  
  return {
    tooltip: {
      trigger: "axis",
      appendToBody: true,
      extraCssText: "z-index: 999; border-radius: 8px; border: none; box-shadow: 0 4px 12px rgba(0,0,0,0.12);",
      backgroundColor: "rgba(255, 255, 255, 0.98)",
      textStyle: {
        color: "#2d3748",
        fontSize: 12
      },
      formatter: "{b}: <strong style='color:#1569de;'>{c}分</strong>"
    },
    grid: {
      top: 15,
      left: 10,
      right: 25,
      bottom: 5,
      containLabel: true
    },
    xAxis: {
      type: "value",
      max: 100,
      splitLine: {
        lineStyle: {
          color: "rgba(21, 105, 222, 0.05)",
          type: "dashed"
        }
      },
      axisLabel: {
        color: "#718096",
        fontSize: 10
      }
    },
    yAxis: {
      type: "category",
      data: categories,
      axisLine: {
        lineStyle: {
          color: "rgba(21, 105, 222, 0.15)"
        }
      },
      axisLabel: {
        color: "#2d3748",
        fontSize: 11,
        fontWeight: "600"
      },
      axisTick: { show: false }
    },
    series: [
      {
        type: "bar",
        data: scores,
        barWidth: "45%",
        itemStyle: {
          borderRadius: [0, 4, 4, 0],
          color: new linearGradient(0, 0, 1, 0, [
            { offset: 0, color: "rgba(21, 105, 222, 0.3)" },
            { offset: 1, color: "#1569de" }
          ])
        }
      }
    ]
  };
});
</script>

<script>
export default {
  name: "TJFX"
};
</script>

<style lang="scss" scoped>
.TJFX {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  width: 100%;
}

.search-card {
  border: 1px solid rgba(21, 105, 222, 0.15) !important;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.04) !important;
  border-radius: var(--app-panel-radius) !important;
  background-color: #ffffff;
  overflow: hidden;

  :deep(.MCard_title_box) {
    background-color: rgba(21, 105, 222, 0.05) !important;
    border-bottom: 1px solid rgba(21, 105, 222, 0.1) !important;
  }
}

.evaluation-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  padding: var(--space-2xs) 2px;

  .form-row {
    display: flex;
    flex-direction: column;
    gap: var(--space-xs);

    .label {
      font-size: 13px;
      font-weight: 600;
      color: #4a5568;
    }

    .custom-select {
      width: 100%;
      :deep(.el-input__wrapper) {
        box-shadow: 0 0 0 1px rgba(21, 105, 222, 0.15) inset !important;
        border-radius: var(--app-card-radius);
        padding: 6px 12px;
        
        &:hover {
          box-shadow: 0 0 0 1px rgba(21, 105, 222, 0.4) inset !important;
        }
      }
    }
  }

  .button-wrapper {
    margin-top: var(--space-xs);
    
    .evaluate-btn {
      width: 100%;
      background: linear-gradient(135deg, #1569de 0%, #1569de 100%);
      border: none;
      padding: 12px;
      border-radius: var(--app-card-radius);
      font-weight: 600;
      box-shadow: 0 4px 12px rgba(21, 105, 222, 0.2);
      transition: all 0.3s ease;

      &:hover {
        transform: translateY(-1px);
        box-shadow: 0 6px 16px rgba(21, 105, 222, 0.35);
      }
    }
  }
}

/* Evaluation Report Card Right Side */
.evaluation-report-card {
  --theme-color: #1569de;
  width: 470px;
  background-color: #ffffff;
  border-radius: var(--app-panel-radius);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
}

.report-panel {
  display: flex;
  flex-direction: column;
  padding: var(--space-xs) var(--space-2xs);
}

.score-section {
  display: flex;
  justify-content: center;
  margin-bottom: 8px;
}

.score-gauge-container {
  height: 140px;
  width: 100%;
  position: relative;
  
  .chart_box {
    width: 100%;
    height: 100%;
  }
  
  .score-chart {
    width: 100%;
    height: 100%;
  }
}

.metrics-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-sm);
  margin-bottom: var(--space-lg);
  
  .metric-card {
    background: linear-gradient(135deg, #ffffff 0%, #fcfdfe 100%);
    border: 1px solid rgba(21, 105, 222, 0.12);
    border-radius: var(--app-card-radius);
    padding: var(--space-sm);
    display: flex;
    flex-direction: column;
    gap: 4px;
    box-sizing: border-box;
    box-shadow: 0 2px 8px rgba(21, 105, 222, 0.02);
    position: relative;
    
    .label {
      font-size: 11px;
      color: #718096;
      font-weight: 600;
    }
    
    .value {
      font-size: 20px;
      font-weight: bold;
      color: #1569de;
      font-family: "Outfit", "Inter", sans-serif;
      margin-top: 2px;
    }

    .badge {
      position: absolute;
      right: 12px;
      top: 12px;
      font-size: 10px;
      font-weight: bold;
      padding: 1px 6px;
      border-radius: 4px;

      &.excel {
        background: rgba(16, 185, 129, 0.1);
        color: #10b981;
      }

      &.good {
        background: rgba(21, 105, 222, 0.1);
        color: #1569de;
      }
    }
  }
}

.dimension-section {
  border-top: 1px solid rgba(21, 105, 222, 0.08);
  padding-top: 16px;
  
  .section-title {
    font-size: 14px;
    font-weight: bold;
    color: #1a365d;
    margin-bottom: 12px;
  }
}

.dimension-chart-container {
  height: 200px;
  width: 100%;
  position: relative;
  
  .chart_box {
    width: 100%;
    height: 100%;
  }
  
  .dim-chart {
    width: 100%;
    height: 100%;
  }
}
</style>
