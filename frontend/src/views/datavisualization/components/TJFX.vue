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

  <!-- 右侧报告卡：与「总体客流变化 / 线路客流 / 站点客流」三块面板同一套外壳（无卡中卡、无蓝色标题条）。
       teleport 出去的节点带的是本组件的 scope，套不到 index.vue 的 .rm-right-card，样式在本文件内自持。 -->
  <teleport to="#datavisualization_index_box2" defer>
    <section class="eval-report-card">
      <header class="eval-card-title">
        <h2>公交网络体检评估报告</h2>
      </header>

      <!-- 右侧面板整体不滚动（box2 overflow hidden），正文在这里滚 -->
      <div class="eval-card-body">
        <div v-if="evalStatus === 'error'" class="eval-state eval-status" role="alert">
          <span class="eval-status-icon is-error" aria-hidden="true">
            <el-icon><WarningFilled /></el-icon>
          </span>
          <p class="eval-status-title">体检评估数据加载失败</p>
          <p class="eval-status-desc">{{ evalError || "请稍后重试，或切换模型后重新评估。" }}</p>
          <button type="button" class="eval-retry" @click="fetchEvaluation">重试</button>
        </div>

        <div v-else-if="evalStatus === 'generating'" class="eval-state eval-status" role="status">
          <span class="eval-status-icon" aria-hidden="true">
            <el-icon class="is-loading"><Loading /></el-icon>
          </span>
          <p class="eval-status-title">体检指标生成中</p>
          <p class="eval-status-desc">后端正在随模型缓存生成体检指标，就绪后本页会自动展示。</p>
        </div>

        <!-- 骨架按最终版式排布（主指标 → 雷达 → 表格），不用转圈 -->
        <div v-else-if="evalStatus === 'loading'" class="eval-state eval-skeleton" aria-hidden="true">
          <div class="eval-sk eval-sk-hero"></div>
          <div class="eval-sk eval-sk-radar"></div>
          <div class="eval-sk eval-sk-row" v-for="n in 4" :key="n"></div>
        </div>

        <template v-else>
          <div class="eval-hero">
            <div class="eval-hero-head">
              <span class="eval-hero-label">指标达标情况</span>
            </div>
            <p class="eval-hero-value">
              <strong>{{ complianceSummary.pass }}</strong>
              <em>/ {{ complianceSummary.judged }} 项达标</em>
            </p>
          </div>

          <section class="eval-section">
            <h3 class="eval-section-title">五维评估雷达</h3>
            <div class="radar-chart-container">
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
          </section>

          <section class="eval-section">
            <h3 class="eval-section-title">评估指标明细对比</h3>
            <div class="indicator-table">
              <div class="t-row t-head">
                <span>评估指标</span>
                <span class="col-value">模型统计值</span>
                <span class="col-standard">规范建议值</span>
              </div>
              <template v-for="group in indicatorGroups" :key="group.dimension">
                <div class="t-dim">
                  <span class="dim-accent" aria-hidden="true"></span>
                  {{ group.dimension }}
                </div>
                <div class="t-row" v-for="ind in group.indicators" :key="ind.key">
                  <span class="col-name">
                    {{ ind.name }}
                    <em v-if="ind.unit" class="unit">{{ ind.unit }}</em>
                  </span>
                  <!-- 达标与否只用红绿呈现（业务要求不加钩叉）；aria-label 里带上达标结论，
                       让读屏用户不依赖颜色也能听到 -->
                  <span :class="['col-value', ind.display.cls]" :aria-label="ind.display.ariaLabel">
                    {{ ind.display.text }}
                  </span>
                  <span class="col-standard">{{ ind.standardText }}</span>
                </div>
              </template>
            </div>
          </section>

          <p class="footnote">
            注：① 模型统计值达到规范建议值时显示绿色，未达到时显示红色；无建议值或暂无统计时中性展示。
            ② 车均场站面积暂无模型数据，“场站设施”维度模型得分按 0 计，雷达图上已标注。
            ③ 广州市平均参考值取 2023 年统计口径。
          </p>
        </template>
      </div>
    </section>
  </teleport>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, inject, watch } from "vue";
import { Opportunity, Loading, WarningFilled } from "@element-plus/icons-vue";
import { VChart } from "@/plugins/echarts";
import MCard from "./MCard.vue";
import { getCachedEvaluation } from "@/utils/modelDataCache.js";
import {
  EVALUATION_DIMENSIONS,
  EVALUATION_INDICATORS,
  isBetterThanStandard,
  normalizeIndicator,
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

// 表格“模型统计值”单元格：达标→绿色；未达标→红色；无建议值/无数据→中性。
// 视觉上只有颜色，达标结论写进 aria-label 兜底
function modelValueDisplay(indicator) {
  const raw = modelValueOf(indicator);
  if (raw == null || !Number.isFinite(Number(raw))) {
    return { text: "暂无数据", cls: "is-none", ariaLabel: `${indicator.name}：暂无数据` };
  }
  const formatted = formatNumber(raw);
  const better = isBetterThanStandard(raw, indicator);
  if (better === true) return { text: formatted, cls: "is-better", ariaLabel: `${formatted}，达标` };
  if (better === false) return { text: formatted, cls: "is-worse", ariaLabel: `${formatted}，未达标` };
  return { text: formatted, cls: "is-neutral", ariaLabel: formatted };
}

// 面板主指标：达标数 / 可评定数。可评定 = 既有模型统计值、又有规范建议值的指标；
// 无建议值的指标（平均换乘次数、公交-轨道接驳比例）不参与分母
const complianceSummary = computed(() => {
  let pass = 0;
  let judged = 0;
  EVALUATION_INDICATORS.forEach((indicator) => {
    const raw = modelValueOf(indicator);
    if (raw == null || !Number.isFinite(Number(raw))) return;
    const better = isBetterThanStandard(raw, indicator);
    if (better === null) return;
    judged += 1;
    if (better) pass += 1;
  });
  return { pass, judged };
});

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
// 模型系列用平台强调蓝（与 dm2 令牌一致），广州参考沿用橙色 —— 与本模块其他图表的
// 「主体蓝 / 参考橙」一致
const MODEL_SERIES_COLOR = "#0071e3";
const GZ_SERIES_COLOR = "#f97316";
const MODEL_SERIES_NAME = "模型统计值";
const GZ_SERIES_NAME = "广州市平均(2023)";

// dimensionScores 对"维度内没有任何可归一化指标"的情况记 0。0 分和"真的很差"在雷达图上
// 画出来一模一样，会把"测不到"读成"做得烂"。这里把这类维度单独标出来。
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
  return {
    backgroundColor: "transparent",
    animation: !reduceMotion,
    animationDuration: reduceMotion ? 0 : 700,
    animationEasing: "cubicOut",
    tooltip: {
      trigger: "item",
      appendToBody: true,
      padding: [8, 11],
      extraCssText: "z-index:999;border-radius:10px;box-shadow:0 12px 32px -14px rgba(13,38,76,0.34);",
      backgroundColor: "rgba(255, 255, 255, 0.98)",
      borderColor: "rgba(17, 32, 58, 0.1)",
      borderWidth: 1,
      textStyle: { color: "#1c2024", fontSize: 12 },
      formatter: (params) => {
        const isModel = params.name === MODEL_SERIES_NAME;
        const rows = EVALUATION_DIMENSIONS.map((dim, index) => {
          const value = isModel && noData.has(dim) ? "暂无数据" : (params.value?.[index] ?? "-");
          return `${dim}：<strong>${value}</strong>`;
        }).join("<br/>");
        return `<strong style="color:${params.color};">${params.name}</strong><br/>${rows}`;
      },
    },
    legend: {
      bottom: 0,
      icon: "circle",
      itemWidth: 9,
      itemHeight: 9,
      itemGap: 18,
      textStyle: { fontSize: 11.5, color: "#667085", fontWeight: 650 },
      data: [MODEL_SERIES_NAME, GZ_SERIES_NAME],
    },
    radar: {
      indicator: EVALUATION_DIMENSIONS.map((dim) => ({
        name: noData.has(dim) ? `{name|${dim}}\n{na|暂无模型数据}` : `{name|${dim}}`,
        max: 1.2,
      })),
      center: ["50%", "46%"],
      radius: "60%",
      splitNumber: 4,
      axisName: {
        rich: {
          name: { color: "#667085", fontSize: 12, fontWeight: 650 },
          na: { color: "#98a2b3", fontSize: 10, fontWeight: 500, padding: [3, 0, 0, 0] },
        },
      },
      axisLine: { lineStyle: { color: "rgba(17, 32, 58, 0.1)" } },
      splitLine: { lineStyle: { color: "rgba(17, 32, 58, 0.08)" } },
      splitArea: {
        areaStyle: { color: ["rgba(17, 32, 58, 0.015)", "rgba(17, 32, 58, 0.035)"] },
      },
    },
    series: [
      {
        type: "radar",
        symbolSize: 4,
        data: [
          {
            value: modelData,
            name: MODEL_SERIES_NAME,
            itemStyle: { color: MODEL_SERIES_COLOR },
            lineStyle: { color: MODEL_SERIES_COLOR, width: 2 },
            areaStyle: { color: "rgba(0, 113, 227, 0.16)" },
          },
          {
            value: gzData,
            name: GZ_SERIES_NAME,
            itemStyle: { color: GZ_SERIES_COLOR },
            lineStyle: { color: GZ_SERIES_COLOR, width: 2 },
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

/* ── 右侧报告卡：标题 → 达标主指标 → 雷达 → 指标明细 → 注释 ──
   外壳与三块客流面板同构（透明底、发丝线标题、无卡中卡） */
.eval-report-card {
  width: 100%;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  border: 0;
  background: transparent;
  font-family: var(--dm2-font);
}

.eval-card-title {
  flex: none;
  padding: 0 0 10px;
  border-bottom: 1px solid var(--dm2-line-faint);

  h2 {
    margin: 0;
    color: var(--dm2-ink);
    font-size: 20px;
    line-height: 1.18;
    font-weight: 780;
    letter-spacing: -0.01em;
  }
}

/* 右侧面板整体不滚动（box2 overflow hidden），报告正文自己滚，滚动条按令牌收细 */
.eval-card-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: rgba(17, 32, 58, 0.18) transparent;

  &::-webkit-scrollbar {
    width: 4px;
  }

  &::-webkit-scrollbar-thumb {
    border-radius: var(--dm2-radius-pill);
    background: rgba(17, 32, 58, 0.18);
  }
}

.eval-hero {
  margin-top: 14px;
  padding: 13px 15px 14px;
  border-radius: var(--dm2-radius);
  background: var(--dm2-surface-sunken);
}

.eval-hero-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}

.eval-hero-label {
  flex: none;
  color: var(--dm2-muted);
  font-size: 12px;
  font-weight: 650;
}

.eval-hero-value {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin: 5px 0 0;

  strong {
    color: var(--dm2-ink);
    font-family: var(--dm2-font-num);
    font-size: 32px;
    font-weight: 800;
    line-height: 1.05;
    letter-spacing: -0.025em;
    font-variant-numeric: tabular-nums;
  }

  em {
    color: var(--dm2-muted);
    font-size: 12px;
    font-style: normal;
    font-weight: 650;
    font-variant-numeric: tabular-nums;
  }
}

.eval-section {
  margin-top: 16px;
}

.eval-section-title {
  margin: 0 0 8px;
  color: var(--dm2-ink-soft);
  font-size: 13px;
  font-weight: 720;
}

/* 五维雷达图 */
.radar-chart-container {
  position: relative;
  height: 262px;
  width: 100%;
  padding: 6px 4px 2px;
  box-sizing: border-box;
  border: 1px solid rgba(17, 32, 58, 0.08);
  border-radius: 12px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.9), rgba(247, 250, 254, 0.78)),
    var(--dm2-surface);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.82);

  .chart_box,
  .radar-chart {
    width: 100%;
    height: 100%;
  }
}

/* 指标明细对比表：靠发丝线分组，不再每行上下都描边，也不再套一层卡片 */
.indicator-table {
  .t-row {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 86px 74px;
    align-items: center;
    gap: 8px;
    padding: 8px 2px;
    font-size: 12px;
  }

  /* 只在同组相邻行之间画线：t-dim 打断相邻选择器，组首行自然无线 */
  .t-row + .t-row {
    border-top: 1px solid var(--dm2-line-faint);
  }

  /* 表头随正文滚动区吸顶，17 行表格滚到底也知道哪列是哪列。
     背景必须完全不透明：--dm2-veil-strong 有 3% 透明度，滚到表头下面的行会透出来 */
  .t-head {
    position: sticky;
    top: 0;
    z-index: 1;
    padding-top: 6px;
    padding-bottom: 7px;
    border-bottom: 1px solid var(--dm2-line);
    background: #fcfdff;
    color: var(--dm2-muted);
    font-size: 11px;
    font-weight: 700;
  }

  .t-dim {
    display: flex;
    align-items: center;
    gap: 7px;
    margin-top: 4px;
    padding: 9px 2px 7px;
    border-top: 1px solid var(--dm2-line);
    color: var(--dm2-ink);
    font-size: 12px;
    font-weight: 760;

    .dim-accent {
      width: 3px;
      height: 12px;
      border-radius: var(--dm2-radius-pill);
      background: var(--dm2-accent);
    }
  }

  .col-name {
    min-width: 0;
    color: var(--dm2-ink);
    font-size: 12px;
    line-height: 1.4;

    /* 单位单独一行：内联时"车站300m人口覆盖率（%）"会在括号处折出孤字 */
    .unit {
      display: block;
      color: var(--dm2-muted-soft);
      font-size: 10.5px;
      font-style: normal;
      font-weight: 550;
    }
  }

  .col-value {
    color: var(--dm2-ink);
    font-family: var(--dm2-font-num);
    font-size: 12px;
    font-weight: 780;
    text-align: right;
    font-variant-numeric: tabular-nums;

    &.is-better {
      color: var(--dm2-add);
    }

    &.is-worse {
      color: var(--dm2-delete);
    }

    &.is-none {
      color: var(--dm2-muted-soft);
      font-size: 11px;
      font-weight: 550;
    }
  }

  .col-standard {
    color: var(--dm2-muted);
    font-family: var(--dm2-font-num);
    font-size: 12px;
    font-weight: 650;
    text-align: right;
    font-variant-numeric: tabular-nums;
  }

  /* 表头复用了 col-value / col-standard 做列对齐，字型要跟着表头走而不是跟着数据走 */
  .t-head .col-value,
  .t-head .col-standard {
    color: inherit;
    font-family: inherit;
    font-size: inherit;
    font-weight: inherit;
  }
}

.footnote {
  margin: 14px 0 2px;
  padding-top: 10px;
  border-top: 1px solid var(--dm2-line-faint);
  color: var(--dm2-muted-soft);
  font-size: 11px;
  line-height: 1.65;
}

/* 状态：失败 / 生成中 / 加载中 —— 整块替换正文 */
.eval-state {
  margin-top: 14px;
}

.eval-status {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  min-height: 260px;
  padding: 32px 22px;
  text-align: center;
}

.eval-status-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--dm2-surface-sunken);
  color: var(--dm2-muted-soft);
  font-size: 20px;

  &.is-error {
    background: var(--dm2-delete-weak);
    color: var(--dm2-delete);
  }
}

.eval-status-title {
  margin: 0;
  color: var(--dm2-ink);
  font-size: 14px;
  font-weight: 720;
}

.eval-status-desc {
  margin: 0;
  max-width: 280px;
  color: var(--dm2-muted);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.55;
}

.eval-retry {
  margin-top: 4px;
  padding: 7px 16px;
  border: 0;
  border-radius: var(--dm2-radius-pill);
  background: var(--dm2-accent);
  color: #ffffff;
  font: 650 12px var(--dm2-font);
  cursor: pointer;
  transition: background-color var(--dm2-dur-fast) var(--dm2-ease), transform var(--dm2-dur-fast) var(--dm2-ease);

  &:hover {
    background: var(--dm2-accent-strong);
  }

  &:active {
    transform: translateY(1px);
  }

  &:focus-visible {
    outline: 2px solid var(--dm2-accent-ring);
    outline-offset: 2px;
  }
}

/* 骨架按最终版式排布，读者一眼知道等的是什么，而不是一个转圈 */
.eval-skeleton {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.eval-sk {
  border-radius: var(--dm2-radius);
  background:
    linear-gradient(90deg, var(--dm2-surface-sunken) 25%, rgba(255, 255, 255, 0.7) 37%, var(--dm2-surface-sunken) 63%)
    0 0 / 400% 100%;
  animation: eval-sk-shimmer 1.4s ease-in-out infinite;
}

.eval-sk-hero {
  height: 78px;
}

.eval-sk-radar {
  height: 262px;
}

.eval-sk-row {
  height: 34px;
  border-radius: var(--dm2-radius-sm);
}

@keyframes eval-sk-shimmer {
  0% {
    background-position: 100% 50%;
  }

  100% {
    background-position: 0 50%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .eval-sk {
    animation: none;
    background: var(--dm2-surface-sunken);
  }

  .eval-retry {
    transition: none;
  }
}
</style>
