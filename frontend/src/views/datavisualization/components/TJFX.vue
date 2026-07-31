<!-- 体检评估分析 (Transit Network Health Evaluation Analysis - Executive Full Stage View) -->
<template>
  <div class="TJFX tjfx-full-dashboard" v-bind="$attrs">
    <div class="tjfx-inner-container">
      
      <!-- Status Views: Error / Generating / Loading Skeleton -->
      <div v-if="evalStatus === 'error'" class="tjfx-state-card is-error" role="alert">
        <div class="state-icon"><el-icon><WarningFilled /></el-icon></div>
        <div class="state-content">
          <h3>体检评估数据加载失败</h3>
          <p>{{ evalError || "请稍后重试，或切换模型后重新评估。" }}</p>
          <button type="button" class="action-btn" @click="fetchEvaluation">重新加载数据</button>
        </div>
      </div>

      <div v-else-if="evalStatus === 'generating'" class="tjfx-state-card is-generating" role="status">
        <div class="state-icon"><el-icon class="is-loading"><Loading /></el-icon></div>
        <div class="state-content">
          <h3>体检指标预热生成中</h3>
          <p>后端正在随模型缓存计算体检指标，就绪后本页面将自动刷新展示。</p>
        </div>
      </div>

      <div v-else-if="evalStatus === 'loading'" class="tjfx-skeleton-stage" aria-hidden="true">
        <div class="sk-grid-row">
          <div class="sk-box sk-radar"></div>
          <div class="sk-box sk-table"></div>
        </div>
      </div>

      <!-- Main Content Stage: Directly Presenting Radar Chart & Indicator Table -->
      <template v-else>
        <section class="tjfx-grid-row">
          
          <!-- Left Column: Radar Chart & District Selector -->
          <div class="tjfx-card-panel tjfx-radar-panel">
            <div class="panel-head flex-between">
              <div class="head-title-group">
                <h2 class="panel-title">五维综合评估雷达图</h2>
              </div>

              <!-- 行政区选择器 -->
              <div class="district-select-wrapper">
                <el-select
                  v-model="selectedDistrict"
                  placeholder="选择行政区"
                  size="small"
                  clearable
                  class="tjfx-district-select"
                  @change="handleDistrictChange"
                >
                  <el-option
                    v-for="dist in DISTRICT_OPTIONS"
                    :key="dist"
                    :label="dist"
                    :value="dist"
                  />
                </el-select>
              </div>
            </div>

            <div class="radar-chart-container">
              <el-auto-resizer class="chart-box">
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

          <!-- Right Column: Benchmark Indicators Table -->
          <div class="tjfx-card-panel tjfx-table-panel">
            <div class="panel-head flex-between">
              <div class="head-title-group">
                <h2 class="panel-title">体检评估指标标准对比明细表</h2>
              </div>

              <!-- Filter Tabs: Only 5 Dimensions -->
              <div class="table-filter-tabs" role="tablist" aria-label="指标维度筛选">
                <button
                  v-for="dim in EVALUATION_DIMENSIONS"
                  :key="dim"
                  type="button"
                  role="tab"
                  :aria-selected="activeDimensionTab === dim"
                  :class="['tab-item', activeDimensionTab === dim ? 'is-active' : '']"
                  @click="activeDimensionTab = dim"
                >
                  {{ dim }}
                </button>
              </div>
            </div>

            <!-- Indicator Table Container -->
            <div class="table-scroll-wrapper">
              <table class="tjfx-indicator-table">
                <thead>
                  <tr>
                    <th class="col-name">评估指标名称 / 单位</th>
                    <th class="col-value text-right">模型统计值</th>
                    <th class="col-standard text-right">规范建议标准</th>
                    <th class="col-gz text-right">广州参考(2023)</th>
                  </tr>
                </thead>
                <tbody>
                  <template v-for="group in filteredGroups" :key="group.dimension">
                    <!-- Dimension Group Divider -->
                    <tr class="group-row" v-if="activeDimensionTab === 'ALL' || activeDimensionTab === 'FAILED'">
                      <td colspan="4">
                        <div class="group-title-tag">
                          <span class="tag-bar"></span>
                          <span class="group-name">{{ group.dimension }}</span>
                          <span class="group-count">{{ group.indicators.length }} 项指标</span>
                        </div>
                      </td>
                    </tr>

                    <!-- Indicator Data Rows -->
                    <tr
                      v-for="ind in group.indicators"
                      :key="ind.key"
                      :class="['indicator-row', ind.display.cls]"
                    >
                      <td class="col-name">
                        <div class="ind-info">
                          <span class="ind-name">{{ ind.name }}</span>
                          <span v-if="ind.unit" class="ind-unit">({{ ind.unit }})</span>
                        </div>
                      </td>
                      <td class="col-value text-right">
                        <span :class="['value-num', ind.display.cls]">{{ ind.display.text }}</span>
                      </td>
                      <td class="col-standard text-right">
                        <span class="std-text">{{ ind.standardText }}</span>
                      </td>
                      <td class="col-gz text-right">
                        <span class="gz-text">{{ ind.gzAvg != null ? formatNumber(ind.gzAvg) : '-' }}</span>
                      </td>
                    </tr>
                  </template>

                  <tr v-if="!hasFilteredIndicators" class="empty-row">
                    <td colspan="4">
                      <div class="empty-hint">
                        <el-icon><Check /></el-icon>
                        <span>当前筛选下暂无匹配指标</span>
                      </div>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

        </section>
      </template>

    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, inject, watch } from "vue";
import { Loading, WarningFilled, Check } from "@element-plus/icons-vue";
import { VChart } from "@/plugins/echarts";
import { chartInk, isDarkTheme } from "@/utils/chartInk";
import { getCachedEvaluation } from "@/utils/modelDataCache.js";
import {
  EVALUATION_DIMENSIONS,
  EVALUATION_INDICATORS,
  isBetterThanStandard,
  normalizeIndicator,
  dimensionScores,
} from "@/utils/evaluationStandards.js";

const DISTRICT_OPTIONS = [
  "全市",
  "越秀区",
  "海珠区",
  "荔湾区",
  "天河区",
  "白云区",
  "黄埔区",
  "番禺区",
  "花都区",
  "南沙区",
  "增城区",
  "从化区",
];

const props = defineProps({
  model: String,
  district: {
    type: String,
    default: "全市",
  },
});

const emit = defineEmits(["update:district"]);
const selectedDistrict = ref(props.district || "全市");

watch(() => props.district, (newDist) => {
  if (newDist && newDist !== selectedDistrict.value) {
    selectedDistrict.value = newDist;
  }
});

function handleDistrictChange(val) {
  const target = val || "全市";
  selectedDistrict.value = target;
  emit("update:district", target);
  fetchEvaluation();
}

const activeDimensionTab = ref("总体水平"); // Default to 总体水平 dimension

const rightPanelHasContent = inject("rightPanelHasContent", ref(false));
const activeDatavisualizationTab = inject("activeDatavisualizationTab", ref(""));

function updateRightPanelVisibility() {
  if (activeDatavisualizationTab.value === "体检评估分析") {
    rightPanelHasContent.value = false;
  }
}

watch(activeDatavisualizationTab, (newTab) => {
  if (newTab === "体检评估分析") {
    rightPanelHasContent.value = false;
  }
});

/******************************** 评估数据请求 ********************************/
// 'loading' | 'generating' | 'error' | 'ready'
const evalStatus = ref("loading");
const evalValues = ref(null);
const evalAvailability = ref({});
const evalError = ref("");

let evalAbortController = null;
let evalRequestSeq = 0;
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
  const district = selectedDistrict.value || props.district || "全市";

  if (evalStatus.value !== "generating") {
    evalStatus.value = "loading";
  }
  evalError.value = "";

  getCachedEvaluation(model, district)
    .then((payload = {}) => {
      if (seq !== evalRequestSeq || props.model !== model || selectedDistrict.value !== district) return;
      if (payload.status === "generating") {
        evalStatus.value = "generating";
        scheduleEvalRetry();
        return;
      }
      evalValues.value = payload.values || {};
      evalAvailability.value = payload.availability || {};
      evalStatus.value = "ready";
    })
    .catch((error) => {
      if (seq !== evalRequestSeq || props.model !== model
        || selectedDistrict.value !== district || isCanceledRequest(error)) return;
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

watch([() => props.model, () => props.district], () => {
  evalRequestSeq += 1;
  clearTimeout(evalRetryTimer);
  evalAbortController?.abort();
  evalValues.value = null;
  evalAvailability.value = {};
  evalError.value = "";
  evalStatus.value = "loading";
  fetchEvaluation();
});

onUnmounted(() => {
  evalRequestSeq += 1;
  clearTimeout(evalRetryTimer);
  evalAbortController?.abort();
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

function availabilityOf(indicator) {
  if (!indicator.modelKey) return null;
  const item = evalAvailability.value?.[indicator.modelKey];
  return item && typeof item === "object" ? item : null;
}

function formatNumber(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return String(value);
  const rounded = Math.abs(n) >= 100 ? Math.round(n) : Math.round(n * 100) / 100;
  return String(rounded);
}

function modelValueDisplay(indicator) {
  const raw = modelValueOf(indicator);
  if (raw == null || !Number.isFinite(Number(raw))) {
    const availability = availabilityOf(indicator);
    const text = availability?.status === "unsupported" ? "不支持" : "无数据";
    const reason = availability?.reason || "";
    return {
      text,
      reason,
      cls: "is-none",
      ariaLabel: `${indicator.name}：${text}${reason ? `，${reason}` : ""}`,
      statusLabel: text,
      pass: null,
    };
  }
  const formatted = formatNumber(raw);
  const better = isBetterThanStandard(raw, indicator);
  if (better === true) {
    return {
      text: formatted,
      cls: "is-better",
      ariaLabel: `${formatted}，达标`,
      statusLabel: "达标",
      pass: true,
    };
  }
  if (better === false) {
    return {
      text: formatted,
      cls: "is-worse",
      ariaLabel: `${formatted}，未达标`,
      statusLabel: "未达标",
      pass: false,
    };
  }
  return {
    text: formatted,
    cls: "is-neutral",
    ariaLabel: formatted,
    statusLabel: "参阅",
    pass: null,
  };
}

const failedIndicatorCount = computed(() => {
  let count = 0;
  EVALUATION_INDICATORS.forEach((ind) => {
    const raw = modelValueOf(ind);
    if (raw != null && isBetterThanStandard(raw, ind) === false) count += 1;
  });
  return count;
});

const indicatorGroups = computed(() => EVALUATION_DIMENSIONS.map((dimension) => ({
  dimension,
  indicators: EVALUATION_INDICATORS
    .filter((item) => item.dimension === dimension)
    .map((item) => ({ ...item, display: modelValueDisplay(item) })),
})));

const filteredGroups = computed(() => {
  const tab = activeDimensionTab.value;
  return indicatorGroups.value
    .map((group) => {
      let inds = group.indicators;
      if (tab === "FAILED") {
        inds = inds.filter((ind) => ind.display.cls === "is-worse");
      } else if (tab !== "ALL" && group.dimension !== tab) {
        inds = [];
      }
      return { dimension: group.dimension, indicators: inds };
    })
    .filter((group) => group.indicators.length > 0);
});

const hasFilteredIndicators = computed(() => filteredGroups.value.some((g) => g.indicators.length > 0));

/******************************** 维度得分 ********************************/
const modelDimScores = computed(() => dimensionScores((ind) => modelValueOf(ind)));
const gzDimScores = computed(() => dimensionScores((ind) => ind.gzAvg));

/******************************** 五维雷达图 ********************************/
const MODEL_SERIES_COLOR = "#0071e3";
const GZ_SERIES_COLOR = "#f97316";
const MODEL_SERIES_NAME = "模型统计值";
const GZ_SERIES_NAME = "广州市平均(2023)";

const modelNoDataDimensions = computed(() => new Set(
  EVALUATION_DIMENSIONS.filter((dimension) => EVALUATION_INDICATORS
    .filter((indicator) => indicator.dimension === dimension)
    .every((indicator) => normalizeIndicator(modelValueOf(indicator), indicator) == null))
));

function prefersReducedMotion() {
  return typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches === true;
}

const radarChartOption = computed(() => {
  const round3 = (n) => Math.round((n || 0) * 1000) / 1000;
  const modelData = EVALUATION_DIMENSIONS.map((dim) => round3(modelDimScores.value[dim]));
  const gzData = EVALUATION_DIMENSIONS.map((dim) => round3(gzDimScores.value[dim]));
  const noData = modelNoDataDimensions.value;
  const reduceMotion = prefersReducedMotion();
  const ink = chartInk.value;
  const dark = isDarkTheme.value;

  return {
    backgroundColor: "transparent",
    animation: !reduceMotion,
    animationDuration: reduceMotion ? 0 : 700,
    animationEasing: "cubicOut",
    tooltip: {
      trigger: "item",
      appendToBody: true,
      padding: [10, 14],
      extraCssText: `z-index:999;border-radius:12px;box-shadow:${ink.tooltipShadow};backdrop-filter:blur(8px);`,
      backgroundColor: ink.tooltipBg,
      borderColor: ink.tooltipBorder,
      borderWidth: 1,
      textStyle: { color: ink.tooltipText, fontSize: 13, fontFamily: "var(--dm2-font)" },
      formatter: (params) => {
        const isModel = params.name === MODEL_SERIES_NAME;
        const rows = EVALUATION_DIMENSIONS.map((dim, index) => {
          const value = isModel && noData.has(dim) ? "暂无数据" : (params.value?.[index] ?? "-");
          return `<div style="display:flex;justify-content:space-between;gap:16px;margin-top:4px;"><span>${dim}</span><strong>${value}</strong></div>`;
        }).join("");
        return `<div style="font-weight:700;color:${params.color};margin-bottom:6px;font-size:13px;">${params.name}</div>${rows}`;
      },
    },
    legend: {
      bottom: 6,
      icon: "circle",
      itemWidth: 10,
      itemHeight: 10,
      itemGap: 24,
      textStyle: { fontSize: 12, color: ink.text, fontWeight: 600, fontFamily: "var(--dm2-font)" },
      data: [MODEL_SERIES_NAME, GZ_SERIES_NAME],
    },
    radar: {
      indicator: EVALUATION_DIMENSIONS.map((dim) => ({
        name: noData.has(dim) ? `{name|${dim}}\n{na|暂无数据}` : `{name|${dim}}`,
        max: 1.2,
      })),
      center: ["50%", "47%"],
      radius: "64%",
      splitNumber: 4,
      axisName: {
        rich: {
          name: { color: ink.text, fontSize: 13, fontWeight: 700, fontFamily: "var(--dm2-font)" },
          na: { color: ink.textSoft, fontSize: 11, fontWeight: 500, padding: [4, 0, 0, 0] },
        },
      },
      axisLine: { lineStyle: { color: ink.axisTick } },
      splitLine: { lineStyle: { color: dark ? "rgba(148, 180, 220, 0.14)" : "rgba(17, 32, 58, 0.09)" } },
      splitArea: {
        areaStyle: {
          color: dark
            ? ["rgba(148, 180, 220, 0.02)", "rgba(148, 180, 220, 0.05)"]
            : ["rgba(17, 32, 58, 0.015)", "rgba(17, 32, 58, 0.035)"],
        },
      },
    },
    series: [
      {
        type: "radar",
        symbolSize: 5,
        data: [
          {
            value: modelData,
            name: MODEL_SERIES_NAME,
            itemStyle: { color: MODEL_SERIES_COLOR },
            lineStyle: { color: MODEL_SERIES_COLOR, width: 2.5 },
            areaStyle: { color: "rgba(0, 113, 227, 0.18)" },
          },
          {
            value: gzData,
            name: GZ_SERIES_NAME,
            itemStyle: { color: GZ_SERIES_COLOR },
            lineStyle: { color: GZ_SERIES_COLOR, width: 2, type: "dashed" },
            areaStyle: { color: "rgba(249, 115, 22, 0.12)" },
          },
        ],
      },
    ],
  };
});
</script>

<script>
export default {
  name: "TJFX",
};
</script>

<style lang="scss" scoped>
/* ── 全屏体检评估主看板 ── */
.tjfx-full-dashboard {
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  overflow-y: auto;
  padding: 20px 24px;
  background: var(--app-surface-soft, #f8fafc);
  color: var(--dm2-ink, #0f172a);
  font-family: var(--dm2-font, system-ui, -apple-system, sans-serif);
}

:global(html.dark) .tjfx-full-dashboard {
  background: #0b1120;
}

.tjfx-inner-container {
  height: 100%;
  display: flex;
  flex-direction: column;
}

/* ── Main Dashboard 2-Column Grid ── */
.tjfx-grid-row {
  display: grid;
  grid-template-columns: 420px minmax(0, 1fr);
  gap: 20px;
  flex: 1;
  min-height: 0;

  @media (max-width: 1280px) {
    grid-template-columns: 1fr;
    height: auto;
  }
}

.tjfx-card-panel {
  display: flex;
  flex-direction: column;
  padding: 20px;
  border-radius: var(--dm2-radius-lg, 16px);
  background: var(--dm2-surface, #ffffff);
  border: 1px solid var(--dm2-line-faint, rgba(0, 0, 0, 0.08));
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.03);
  min-height: 0;

  .panel-head {
    padding-bottom: 14px;
    border-bottom: 1px solid var(--dm2-line-faint, rgba(0, 0, 0, 0.06));
    flex-shrink: 0;

    &.flex-between {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      flex-wrap: wrap;
    }

    .head-title-group {
      .panel-title {
        margin: 0;
        font-size: 16px;
        font-weight: 780;
        line-height: 1.25;
        color: var(--dm2-ink, #0f172a);
      }
    }

    .district-select-wrapper {
      display: flex;
      align-items: center;

      .tjfx-district-select {
        width: 110px;

        :deep(.el-input__wrapper) {
          border-radius: var(--dm2-radius-pill, 9999px);
          background: var(--dm2-surface-sunken, #f1f5f9);
          box-shadow: none !important;
          border: 1px solid var(--dm2-line-faint, rgba(0, 0, 0, 0.08));
          font-size: 12px;
          font-weight: 600;

          &.is-focus, &:hover {
            border-color: var(--dm2-accent, #0071e3);
          }
        }

        :deep(.el-input__inner) {
          color: var(--dm2-ink, #0f172a);
          font-weight: 600;
        }
      }
    }
  }
}

/* Radar Chart Section */
.tjfx-radar-panel {
  .radar-chart-container {
    flex: 1;
    min-height: 380px;
    width: 100%;
    margin-top: 8px;

    .chart-box, .radar-chart {
      width: 100%;
      height: 100%;
    }
  }
}

/* Indicator Table Section */
.tjfx-table-panel {
  .table-filter-tabs {
    display: flex;
    align-items: center;
    gap: 6px;
    flex-wrap: wrap;

    .tab-item {
      padding: 5px 12px;
      border-radius: var(--dm2-radius-pill, 9999px);
      background: var(--dm2-surface-sunken, #f1f5f9);
      border: 1px solid transparent;
      font-size: 12px;
      font-weight: 600;
      color: var(--dm2-muted, #64748b);
      cursor: pointer;
      transition: all 0.15s ease;

      &:hover {
        color: var(--dm2-ink, #0f172a);
        background: rgba(0, 0, 0, 0.05);
      }

      &.is-active {
        background: var(--dm2-accent, #0071e3);
        color: #ffffff;
      }

      &.is-failed.is-active {
        background: #ef4444;
      }
    }
  }

  .table-scroll-wrapper {
    margin-top: 14px;
    overflow-y: auto;
    flex: 1;
    min-height: 0;
  }

  .tjfx-indicator-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 13px;

    thead {
      position: sticky;
      top: 0;
      z-index: 2;
      background: var(--dm2-surface, #ffffff);
    }

    th {
      padding: 10px 12px;
      border-bottom: 1px solid var(--dm2-line, rgba(0, 0, 0, 0.1));
      color: var(--dm2-muted, #64748b);
      font-weight: 700;
      font-size: 12px;
      text-align: left;
      white-space: nowrap;

      &.text-right { text-align: right; }
      &.text-center { text-align: center; }
    }

    .group-row {
      td {
        padding: 12px 12px 6px;
        border-top: 1px solid var(--dm2-line-faint, rgba(0, 0, 0, 0.08));
      }

      .group-title-tag {
        display: flex;
        align-items: center;
        gap: 8px;

        .tag-bar {
          width: 3px;
          height: 14px;
          border-radius: 2px;
          background: var(--dm2-accent, #0071e3);
        }

        .group-name {
          font-size: 13px;
          font-weight: 780;
          color: var(--dm2-ink, #0f172a);
        }

        .group-count {
          font-size: 11px;
          color: var(--dm2-muted-soft, #94a3b8);
        }
      }
    }

    .indicator-row {
      td {
        padding: 10px 12px;
        border-bottom: 1px solid var(--dm2-line-faint, rgba(0, 0, 0, 0.05));

        &.text-right { text-align: right; }
        &.text-center { text-align: center; }
      }

      &:hover {
        background: rgba(0, 113, 227, 0.02);
      }

      .ind-info {
        display: flex;
        align-items: baseline;
        gap: 6px;
        flex-wrap: wrap;

        .ind-name {
          font-weight: 600;
          color: var(--dm2-ink, #0f172a);
        }

        .ind-unit {
          font-size: 11px;
          color: var(--dm2-muted-soft, #94a3b8);
        }
      }

      .dim-badge {
        display: inline-block;
        padding: 2px 8px;
        border-radius: 4px;
        background: var(--dm2-surface-sunken, #f1f5f9);
        color: var(--dm2-muted, #64748b);
        font-size: 11px;
        font-weight: 600;
      }

      .value-num {
        font-family: var(--dm2-font-num, tabular-nums);
        font-weight: 750;
        font-size: 13px;

        &.is-better { color: #16a34a; }
        &.is-worse { color: #dc2626; }
        &.is-none { color: var(--dm2-muted-soft, #94a3b8); font-weight: 500; }
      }

      .std-text, .gz-text {
        font-family: var(--dm2-font-num, tabular-nums);
        color: var(--dm2-muted, #64748b);
        font-size: 12px;
        font-weight: 600;
      }

      .status-chip {
        display: inline-block;
        padding: 3px 10px;
        border-radius: var(--dm2-radius-pill, 9999px);
        font-size: 11px;
        font-weight: 700;

        &.is-better {
          background: rgba(34, 197, 94, 0.12);
          color: #16a34a;
        }

        &.is-worse {
          background: rgba(239, 68, 68, 0.12);
          color: #dc2626;
        }

        &.is-none {
          background: rgba(148, 163, 184, 0.12);
          color: #64748b;
        }

        &.is-neutral {
          background: rgba(0, 113, 227, 0.1);
          color: #0071e3;
        }
      }
    }

    .empty-row {
      td {
        padding: 40px 12px;
        text-align: center;
      }

      .empty-hint {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 8px;
        color: var(--dm2-muted, #64748b);
        font-size: 13px;

        .el-icon {
          font-size: 24px;
          color: #16a34a;
        }
      }
    }
  }

  .table-footnote {
    margin-top: 10px;
    padding-top: 8px;
    border-top: 1px solid var(--dm2-line-faint, rgba(0, 0, 0, 0.06));
    font-size: 11px;
    color: var(--dm2-muted-soft, #94a3b8);
    line-height: 1.4;
    flex-shrink: 0;
  }
}

/* ── State Views (Error / Generating / Skeleton) ── */
.tjfx-state-card {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 32px;
  border-radius: var(--dm2-radius-lg, 16px);
  background: var(--dm2-surface, #ffffff);
  border: 1px solid var(--dm2-line-faint, rgba(0, 0, 0, 0.08));

  .state-icon {
    font-size: 32px;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 56px;
    height: 56px;
    border-radius: 50%;
    background: var(--dm2-surface-sunken, #f1f5f9);
  }

  &.is-error {
    .state-icon {
      background: rgba(239, 68, 68, 0.1);
      color: #ef4444;
    }
  }

  &.is-generating {
    .state-icon {
      background: rgba(0, 113, 227, 0.1);
      color: #0071e3;
    }
  }

  .state-content {
    h3 {
      margin: 0;
      font-size: 16px;
      font-weight: 750;
      color: var(--dm2-ink, #0f172a);
    }

    p {
      margin: 6px 0 0;
      font-size: 13px;
      color: var(--dm2-muted, #64748b);
    }

    .action-btn {
      margin-top: 12px;
      padding: 6px 16px;
      border-radius: var(--dm2-radius-pill, 9999px);
      background: var(--dm2-accent, #0071e3);
      color: #ffffff;
      border: none;
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
    }
  }
}

/* Skeleton Loading Animation */
.tjfx-skeleton-stage {
  display: flex;
  flex-direction: column;
  gap: 24px;
  height: 100%;

  .sk-grid-row {
    display: grid;
    grid-template-columns: 420px 1fr;
    gap: 20px;
    flex: 1;

    .sk-box {
      border-radius: 16px;
      background: linear-gradient(90deg, rgba(0, 0, 0, 0.04) 25%, rgba(0, 0, 0, 0.08) 37%, rgba(0, 0, 0, 0.04) 63%);
      background-size: 400% 100%;
      animation: skeleton-loading 1.4s ease infinite;

      &.sk-radar { height: 100%; }
      &.sk-table { height: 100%; }
    }
  }
}

@keyframes skeleton-loading {
  0% { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}

/* Dark Mode Theme Tokens Overrides */
:global(html.dark) {
  .tjfx-card-panel, .tjfx-state-card {
    background: #1e293b;
    border-color: rgba(255, 255, 255, 0.08);
  }

  .tjfx-card-panel {
    .panel-head {
      border-bottom-color: rgba(255, 255, 255, 0.08);
      .panel-title { color: #f8fafc; }
      .panel-subtitle { color: #94a3b8; }
    }
  }

  .tjfx-radar-panel .dim-chip {
    background: rgba(255, 255, 255, 0.04);
    .chip-name { color: #e2e8f0; }
    .chip-value .score { color: #f8fafc; }

    &:hover {
      background: rgba(0, 113, 227, 0.15);
    }
  }

  .tjfx-table-panel {
    .tab-item {
      background: rgba(255, 255, 255, 0.06);
      color: #94a3b8;
      &:hover { color: #f8fafc; background: rgba(255, 255, 255, 0.1); }
    }

    .tjfx-indicator-table {
      thead { background: #1e293b; }
      th { border-bottom-color: rgba(255, 255, 255, 0.12); color: #94a3b8; }
      .group-row .group-name { color: #f8fafc; }
      .indicator-row td { border-bottom-color: rgba(255, 255, 255, 0.05); }
      .indicator-row:hover { background: rgba(255, 255, 255, 0.03); }
      .ind-name { color: #f8fafc; }
      .dim-badge { background: rgba(255, 255, 255, 0.06); color: #94a3b8; }
      .std-text, .gz-text { color: #94a3b8; }
    }
  }
}
</style>
