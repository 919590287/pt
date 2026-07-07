<!-- 体检评估分析 (Transit Network Health Evaluation) -->
<template>
  <div class="TJFX" v-bind="$attrs">
    <div class="info-container">
      <MCard class="card search-card" :open="true" title="公交体检指标评估">
        <template #body>
          <div class="evaluation-form">
            <div class="form-row">
              <span class="label">评估方案</span>
              <!-- 评估方案暂无多方案数据支撑：仅保留占位下拉（禁用）并注明暂不生效；
                   原“分析时段”下拉已移除——指标统计仅有全天口径，保留时段选择会误导用户 -->
              <el-select v-model="selectedScheme" disabled class="custom-select">
                <el-option label="基础方案（当前模型）" value="base" />
              </el-select>
              <span class="form-hint">评估方案暂不生效：当前按所选模型全市、全天口径统计</span>
            </div>

            <div class="button-wrapper">
              <el-button
                type="primary"
                class="evaluate-btn"
                :loading="evaluating"
                @click="fetchEvaluation"
              >
                <el-icon v-if="!evaluating" style="margin-right: 6px;"><Opportunity /></el-icon>
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
          <!-- 加载中 / 生成中 / 失败 -->
          <div v-if="evalStatus !== 'ready'" class="status-box">
            <template v-if="evalStatus === 'loading'">
              <el-icon class="status-icon is-loading"><Loading /></el-icon>
              <div class="status-text">正在统计体检评估指标…</div>
            </template>
            <template v-else-if="evalStatus === 'generating'">
              <el-icon class="status-icon is-loading"><Loading /></el-icon>
              <div class="status-text">体检指标正在随模型缓存生成，就绪后将自动展示</div>
            </template>
            <template v-else>
              <el-icon class="status-icon error"><WarningFilled /></el-icon>
              <div class="status-text error">{{ evalError || "体检评估数据加载失败" }}</div>
              <el-button size="small" type="primary" plain @click="fetchEvaluation">重试</el-button>
            </template>
          </div>

          <template v-else>
            <!-- 五维评估雷达图 -->
            <div class="radar-section">
              <div class="section-title">五维评估雷达</div>
              <div class="chart-container-wrapper radar-chart-container">
                <el-auto-resizer class="chart_box">
                  <template #default="{ height, width }">
                    <VChart
                      v-if="width > 0 && height > 0"
                      class="radar-chart"
                      :option="radarChartOption"
                      autoresize
                      :update-options="{ notMerge: true }"
                    />
                  </template>
                </el-auto-resizer>
              </div>
            </div>

            <!-- 指标明细对比表 -->
            <div class="table-section">
              <div class="section-title">评估指标明细对比</div>
              <div class="indicator-table">
                <div class="t-row t-head">
                  <span class="col-name">评估指标名称</span>
                  <span class="col-value">模型统计值</span>
                  <span class="col-standard">规范建议值</span>
                </div>
                <template v-for="group in indicatorGroups" :key="group.dimension">
                  <div class="t-dim">
                    <span class="dim-accent"></span>
                    {{ group.dimension }}
                  </div>
                  <div class="t-row" v-for="ind in group.indicators" :key="ind.key">
                    <span class="col-name">
                      {{ ind.name }}
                      <em v-if="ind.unit" class="unit">({{ ind.unit }})</em>
                    </span>
                    <span :class="['col-value', ind.display.cls]">
                      {{ ind.display.text }}
                    </span>
                    <span class="col-standard">{{ ind.standardText }}</span>
                  </div>
                </template>
              </div>
            </div>

            <div class="footnote">
              注：① 模型统计值优于规范建议值时显示绿色，劣于时显示红色，无建议值或暂无统计时中性展示；
              ② 车均场站面积暂无模型数据，“场站设施”维度模型得分按 0 计；
              ③ 广州市平均参考值取 2023 年统计口径。
            </div>
          </template>
        </div>
      </template>
    </MCard2>
  </teleport>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, inject, watch } from "vue";
import { Opportunity, Loading, WarningFilled } from "@element-plus/icons-vue";
import MCard from "./MCard.vue";
import MCard2 from "./MCard2.vue";
import { getCachedEvaluation } from "@/utils/modelDataCache.js";
import {
  EVALUATION_DIMENSIONS,
  EVALUATION_INDICATORS,
  isBetterThanStandard,
  dimensionScores,
} from "@/utils/evaluationStandards.js";

const props = defineProps({
  model: String,
});

const selectedScheme = ref("base");

const rightPanelHasContent = inject("rightPanelHasContent", ref(false));
const activeDatavisualizationTab = inject("activeDatavisualizationTab", ref(""));

function updateRightPanelVisibility() {
  if (activeDatavisualizationTab.value === "体检评估分析") {
    rightPanelHasContent.value = true;
  }
}

watch(activeDatavisualizationTab, (newTab) => {
  if (newTab === "体检评估分析") {
    rightPanelHasContent.value = true;
  }
});

/******************************** 评估数据请求 ********************************/
// 'loading' | 'generating' | 'error' | 'ready'
const evalStatus = ref("loading");
const evalValues = ref(null);
const evalError = ref("");

// seq + AbortController 竞态防护（同 XLZL 的选中请求模式）：
// 新请求发起时中止旧请求；回调用 seq 校验，仅最新一次请求可写入状态
let evalAbortController = null;
let evalRequestSeq = 0;
// 后端仍在预热缓存时自动轮询，就绪后自动展示，无需用户手动重试
let evalRetryTimer = null;
const EVAL_RETRY_INTERVAL = 5000;

function scheduleEvalRetry() {
  clearTimeout(evalRetryTimer);
  evalRetryTimer = setTimeout(fetchEvaluation, EVAL_RETRY_INTERVAL);
}

function isCanceledRequest(error) {
  return error?.message === "请求已取消"
    || error?.message === "canceled"
    || error?.cause?.message === "canceled"
    || error?.cause?.code === "ERR_CANCELED";
}

function fetchEvaluation() {
  if (!props.model) return;
  clearTimeout(evalRetryTimer);
  evalAbortController?.abort();
  evalAbortController = typeof AbortController !== "undefined" ? new AbortController() : null;
  evalRequestSeq += 1;
  const seq = evalRequestSeq;
  const model = props.model;
  // 轮询期间保持 generating 展示，避免每 5 秒闪一次 loading
  if (evalStatus.value !== "generating") {
    evalStatus.value = "loading";
  }
  evalError.value = "";
  getCachedEvaluation(model)
    .then((payload = {}) => {
      if (seq !== evalRequestSeq || props.model !== model) return;
      if (payload.status === "generating") {
        evalStatus.value = "generating";
        scheduleEvalRetry();
        return;
      }
      evalValues.value = payload.values || {};
      evalStatus.value = "ready";
    })
    .catch((error) => {
      if (seq !== evalRequestSeq || props.model !== model || isCanceledRequest(error)) return;
      const message = String(error?.message || "");
      if (/超时|网关|服务|服务器|连接|Network|timeout|temporar/i.test(message)) {
        evalStatus.value = "generating";
        scheduleEvalRetry();
        return;
      }
      evalError.value = error?.message || "体检评估数据加载失败";
      evalStatus.value = "error";
    });
}

const evaluating = computed(() => evalStatus.value === "loading");

onMounted(() => {
  updateRightPanelVisibility();
  fetchEvaluation();
});

watch(() => props.model, () => {
  evalRequestSeq += 1;
  clearTimeout(evalRetryTimer);
  evalAbortController?.abort();
  evalValues.value = null;
  evalError.value = "";
  evalStatus.value = "loading";
  fetchEvaluation();
});

onUnmounted(() => {
  evalRequestSeq += 1;
  clearTimeout(evalRetryTimer);
  evalAbortController?.abort();
  // tab 切换时新组件先置 true、本组件后卸载，无守卫会把它清掉导致右侧面板闪空
  if (activeDatavisualizationTab.value === "体检评估分析") {
    rightPanelHasContent.value = false;
  }
});

/******************************** 指标取值与展示 ********************************/
function modelValueOf(indicator) {
  if (!indicator.modelKey) return null;
  const value = evalValues.value?.[indicator.modelKey];
  return value == null ? null : value;
}

function formatNumber(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return String(value);
  // 大数取整、小数保留两位，避免后端浮点长尾
  const rounded = Math.abs(n) >= 100 ? Math.round(n) : Math.round(n * 100) / 100;
  return String(rounded);
}

// 表格“模型统计值”单元格：优于建议值→绿色；劣于→红色；无建议值/无数据→中性
function modelValueDisplay(indicator) {
  const raw = modelValueOf(indicator);
  if (raw == null || !Number.isFinite(Number(raw))) {
    return { text: "暂无数据", cls: "is-none" };
  }
  const formatted = formatNumber(raw);
  const better = isBetterThanStandard(raw, indicator);
  if (better === true) return { text: formatted, cls: "is-better" };
  if (better === false) return { text: formatted, cls: "is-worse" };
  return { text: formatted, cls: "is-neutral" };
}

// 表格按 5 个评估维度分组；display 预计算（模板原先每行调用 modelValueDisplay 两次）
const indicatorGroups = computed(() => EVALUATION_DIMENSIONS.map((dimension) => ({
  dimension,
  indicators: EVALUATION_INDICATORS
    .filter((item) => item.dimension === dimension)
    .map((item) => ({ ...item, display: modelValueDisplay(item) })),
})));

/******************************** 维度得分（雷达图 / 综合评分） ********************************/
// 模型系列：normalizeIndicator 以建议值为基准归一化（上限1.2），dimensionScores 取维度内均值；
// “场站设施”维度 modelKey 均为 null → 得分 0
const modelDimScores = computed(() => dimensionScores((ind) => modelValueOf(ind)));
// 广州参考系列：同一套归一化口径，取 indicator.gzAvg（2023年值）
const gzDimScores = computed(() => dimensionScores((ind) => ind.gzAvg));

/******************************** 五维雷达图 ********************************/
const MODEL_SERIES_COLOR = "#1569de";
const GZ_SERIES_COLOR = "#f97316";

const radarChartOption = computed(() => {
  const round3 = (n) => Math.round((n || 0) * 1000) / 1000;
  const modelData = EVALUATION_DIMENSIONS.map((dim) => round3(modelDimScores.value[dim]));
  const gzData = EVALUATION_DIMENSIONS.map((dim) => round3(gzDimScores.value[dim]));
  return {
    tooltip: {
      trigger: "item",
      appendToBody: true,
      extraCssText: "z-index: 999; border-radius: 8px; border: none; box-shadow: 0 4px 12px rgba(0,0,0,0.12);",
      backgroundColor: "rgba(255, 255, 255, 0.98)",
      textStyle: { color: "#2d3748", fontSize: 12 },
      formatter: (params) => {
        const rows = EVALUATION_DIMENSIONS
          .map((dim, i) => `${dim}: <strong>${params.value?.[i] ?? "-"}</strong>`)
          .join("<br/>");
        return `<strong style="color:${params.color};">${params.name}</strong><br/>${rows}`;
      },
    },
    legend: {
      bottom: 0,
      icon: "circle",
      itemWidth: 10,
      itemHeight: 10,
      itemGap: 18,
      textStyle: { fontSize: 12, color: "#60758e" },
      data: ["模型统计值", "广州市平均(2023)"],
    },
    radar: {
      indicator: EVALUATION_DIMENSIONS.map((dim) => ({ name: dim, max: 1.2 })),
      center: ["50%", "46%"],
      radius: "62%",
      splitNumber: 4,
      axisName: { color: "#60758e", fontSize: 12, fontWeight: 600 },
      axisLine: { lineStyle: { color: "rgba(21, 105, 222, 0.15)" } },
      splitLine: { lineStyle: { color: "rgba(21, 105, 222, 0.12)" } },
      splitArea: {
        areaStyle: { color: ["rgba(21, 105, 222, 0.02)", "rgba(21, 105, 222, 0.05)"] },
      },
    },
    series: [
      {
        type: "radar",
        symbolSize: 4,
        data: [
          {
            value: modelData,
            name: "模型统计值",
            itemStyle: { color: MODEL_SERIES_COLOR },
            lineStyle: { color: MODEL_SERIES_COLOR, width: 2 },
            areaStyle: { color: "rgba(21, 105, 222, 0.18)" },
          },
          {
            value: gzData,
            name: "广州市平均(2023)",
            itemStyle: { color: GZ_SERIES_COLOR },
            lineStyle: { color: GZ_SERIES_COLOR, width: 2 },
            areaStyle: { color: "rgba(249, 115, 22, 0.14)" },
          },
        ],
      },
    ],
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
  box-shadow: none !important;
  border-radius: var(--app-panel-radius) !important;
  background: var(--app-card-bg-tint);
  overflow: hidden;

  :deep(.MCard_title_box) {
    background: rgba(21, 105, 222, 0.045) !important;
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
      color: var(--app-muted);
    }

    .custom-select {
      width: 100%;
      :deep(.el-input__wrapper) {
        box-shadow: 0 0 0 1px rgba(21, 105, 222, 0.15) inset !important;
        border-radius: var(--app-card-radius);
        padding: 6px 12px;
      }
    }

    .form-hint {
      font-size: 11px;
      line-height: 16px;
      color: var(--app-muted-soft);
    }
  }

  .button-wrapper {
    margin-top: var(--space-xs);

    .evaluate-btn {
      width: 100%;
      background: var(--app-blue);
      border: none;
      padding: 12px;
      border-radius: var(--app-card-radius);
      font-weight: 600;
      transition: background-color 0.2s ease;

      &:hover {
        background: var(--app-blue-strong);
      }
    }
  }
}

/* Evaluation Report Card Right Side */
.evaluation-report-card {
  --theme-color: #1569de;
  width: 470px;
  background: var(--app-card-bg-tint);
  border-radius: var(--app-panel-radius);
  box-shadow: none;

  /* 体检评估分析所在的 run-monitor 右侧面板不滚动（box2 overflow hidden），
     卡片需占满剩余高度并让报告内容在卡片体内滚动，否则表格底部被裁切 */
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;

  :deep(.MCard2_body_box) {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
  }
}

.report-panel {
  display: flex;
  flex-direction: column;
  padding: var(--space-xs) var(--space-2xs);
}

/* 加载/生成中/失败 状态 */
.status-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-xs);
  padding: var(--space-lg) var(--space-sm);
  min-height: 160px;

  .status-icon {
    font-size: 26px;
    color: var(--app-blue);

    &.error {
      color: var(--app-coral);
    }
  }

  .status-text {
    font-size: 13px;
    color: var(--app-muted);
    text-align: center;
    line-height: 20px;

    &.error {
      color: var(--app-coral);
    }
  }
}

.section-title {
  font-size: 14px;
  font-weight: bold;
  color: var(--app-ink);
  margin-bottom: 10px;
}

/* 五维雷达图 */
.radar-section {
  margin-bottom: var(--space-sm);
}

.radar-chart-container {
  height: 250px;
  width: 100%;
  position: relative;

  .chart_box {
    width: 100%;
    height: 100%;
  }

  .radar-chart {
    width: 100%;
    height: 100%;
  }
}

/* 指标明细对比表 */
.table-section {
  border-top: 1px solid rgba(21, 105, 222, 0.08);
  padding-top: var(--space-sm);
}

.indicator-table {
  border: 1px solid rgba(21, 105, 222, 0.12);
  border-radius: var(--app-card-radius);
  overflow: hidden;
  background: var(--app-card-bg-tint);

  .t-row {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 96px 84px;
    align-items: center;
    gap: 6px;
    padding: 7px 10px;
    font-size: 12px;
    border-top: 1px solid rgba(21, 105, 222, 0.08);
  }

  .t-head {
    border-top: none;
    background: rgba(21, 105, 222, 0.06);
    font-weight: 700;
    color: var(--app-blue);
  }

  .t-dim {
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 6px 10px;
    background: rgba(21, 105, 222, 0.035);
    border-top: 1px solid rgba(21, 105, 222, 0.08);
    font-size: 12px;
    font-weight: 700;
    color: var(--app-ink);

    .dim-accent {
      width: 3px;
      height: 12px;
      border-radius: 2px;
      background: var(--app-blue);
    }
  }

  .col-name {
    color: var(--app-ink);
    line-height: 17px;

    .unit {
      font-style: normal;
      font-size: 11px;
      color: var(--app-muted-soft);
      margin-left: 2px;
    }
  }

  .col-value {
    font-family: var(--app-font-number);
    font-weight: bold;
    text-align: right;

    &.is-better {
      color: var(--app-emerald-strong);
    }

    &.is-worse {
      color: var(--app-coral);
    }

    &.is-neutral {
      color: var(--app-ink);
    }

    &.is-none {
      color: var(--app-muted-soft);
      font-weight: 500;
      font-size: 11px;
    }
  }

  .col-standard {
    color: var(--app-muted);
    text-align: right;
    font-family: var(--app-font-number);
  }
}

.footnote {
  margin-top: var(--space-xs);
  font-size: 11px;
  line-height: 17px;
  color: var(--app-muted-soft);
}
</style>
