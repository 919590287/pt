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

      <!-- Main Content Stage: 左侧逐类雷达图 + 右侧指标明细表 -->
      <template v-else>
        <section class="tjfx-grid-row">

          <!-- Left Column: 每类指标一张雷达图 + 行政区选区 -->
          <div class="tjfx-card-panel tjfx-radar-panel">
            <div class="panel-head flex-between">
              <div class="head-title-group">
                <h2 class="panel-title">分类评估雷达图</h2>
              </div>

              <!-- 行政区选择器 -->
              <div class="district-select-wrapper">
                <el-select
                  v-model="selectedDistrict"
                  placeholder="选择行政区"
                  size="small"
                  class="tjfx-district-select"
                >
                  <el-option
                    v-for="dist in districtOptions"
                    :key="dist"
                    :label="dist"
                    :value="dist"
                  />
                </el-select>
              </div>
            </div>

            <!-- 细线分格的仪表盘：五格雷达，末格向右扩充占满一格，恰好铺满 2×3，不滚动 -->
            <div class="radar-grid">
              <div v-for="card in radarCards" :key="card.dimension" class="radar-cell">
                <div class="radar-cell-head">
                  <h3 class="radar-cell-title">{{ card.dimension }}</h3>
                </div>
                <div class="radar-cell-body">
                  <el-auto-resizer class="chart-box">
                    <template #default="{ height, width }">
                      <VChart
                        v-if="width > 0 && height > 0"
                        class="radar-chart"
                        :option="card.option"
                        autoresize
                        :update-options="{ notMerge: true }"
                      />
                    </template>
                  </el-auto-resizer>
                </div>
              </div>
            </div>
          </div>

          <!-- Right Column: Benchmark Indicators Table -->
          <div class="tjfx-card-panel tjfx-table-panel">
            <div class="panel-head flex-between">
              <div class="head-title-group">
                <h2 class="panel-title">体检评估指标标准对比明细表</h2>
              </div>
            </div>

            <!-- Indicator Table Container -->
            <div class="table-scroll-wrapper">
              <table class="tjfx-indicator-table">
                <thead>
                  <tr>
                    <th class="col-dim">指标类型</th>
                    <th class="col-name">评估指标名称 / 单位</th>
                    <th class="col-value text-right">模型统计值</th>
                    <th class="col-standard text-right">规范建议标准</th>
                    <th class="col-gz text-right">广州参考(2023)</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="row in tableRows"
                    :key="row.key"
                    :class="['indicator-row', row.display.cls, row.isDimensionStart ? 'is-dim-start' : '']"
                  >
                    <td v-if="row.rowSpan" class="col-dim" :rowspan="row.rowSpan">
                      <div class="dim-cell">
                        <span class="tag-bar"></span>
                        <span class="dim-name">{{ row.dimension }}</span>
                      </div>
                    </td>
                    <td class="col-name">
                      <div class="ind-info">
                        <span class="ind-name">{{ row.name }}</span>
                        <span v-if="row.unit" class="ind-unit">({{ row.unit }})</span>
                      </div>
                    </td>
                    <td class="col-value text-right">
                      <span :class="['value-num', row.display.cls]">{{ row.display.text }}</span>
                    </td>
                    <td class="col-standard text-right">
                      <span class="std-text">{{ row.standardText }}</span>
                      <span
                        v-if="row.direction"
                        class="dir-mark"
                        :title="row.direction.label"
                      >{{ row.direction.mark }}</span>
                    </td>
                    <td class="col-gz text-right">
                      <span class="gz-text">{{ row.gzAvg != null ? formatNumber(row.gzAvg) : '-' }}</span>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

            <p v-if="!isCityScope" class="table-footnote">
              {{ districtScopedNames }} 按所选行政区（{{ activeDistrictLabel }}）统计，其余指标为全市口径，不随行政区变化。
            </p>
          </div>

        </section>
      </template>


    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, inject, watch } from "vue";
import { Loading, WarningFilled } from "@element-plus/icons-vue";
import { VChart } from "@/plugins/echarts";
import { chartInk, isDarkTheme } from "@/utils/chartInk";
import { getCachedEvaluation } from "@/utils/modelDataCache.js";
import { getCachedAdminDistricts } from "@/utils/realDataCache.js";
import {
  EVALUATION_DIMENSIONS,
  EVALUATION_INDICATORS,
  RADAR_INDICATORS,
  RADAR_MAX_SCORE,
  RADAR_STANDARD_SCORE,
  dimensionRadarScores,
  directionInfo,
  isBetterThanStandard,
} from "@/utils/evaluationStandards.js";

const DISPLAY_AREA_NAME = "广州市";
const DISTRICT_ALL = "全市";
// 行政区列表以后端 adminDistricts 为准（与地图选区、后端统计口径同名），
// 请求未回来前先用本地兜底，避免下拉短暂空白。
const FALLBACK_DISTRICT_OPTIONS = [
  DISTRICT_ALL,
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
    // defineProps 会被提升到 setup 外，默认值只能写字面量，不能引用 DISTRICT_ALL
    type: String,
    default: "全市",
  },
});

const emit = defineEmits(["update:district"]);

// 行政区只有 props.district（displayRange store）一个真值来源：下拉直接读写它，
// 不再维护本地副本。原实现里本地 ref 与 props 各触发一次请求，两条链的
// seq 守卫互相作废，选区经常停在旧数据上，看起来就是"选了没反应"。
const selectedDistrict = computed({
  get: () => props.district || DISTRICT_ALL,
  set: (value) => emit("update:district", String(value || "").trim() || DISTRICT_ALL),
});

const districtOptions = ref([...FALLBACK_DISTRICT_OPTIONS]);
const activeDistrictLabel = computed(() => props.district || DISTRICT_ALL);
const isCityScope = computed(() => activeDistrictLabel.value === DISTRICT_ALL);
const districtScopedNames = computed(() => EVALUATION_INDICATORS
  .filter((item) => item.districtScoped)
  .map((item) => item.name)
  .join("、"));

function loadDistrictOptions() {
  getCachedAdminDistricts(DISPLAY_AREA_NAME)
    .then((data) => {
      const names = Array.isArray(data?.districts)
        ? data.districts.map((item) => String(item || "").trim()).filter(Boolean)
        : [];
      if (!names.length) return;
      districtOptions.value = [
        DISTRICT_ALL,
        ...names.filter((name, index, list) => name !== DISTRICT_ALL && list.indexOf(name) === index),
      ];
    })
    .catch(() => {
      /* 行政区列表失败时保留兜底列表，体检指标本身仍可按全市展示 */
    });
}

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
let evalRetryAttempt = 0;
const EVAL_RETRY_BASE_MS = 5000;
const EVAL_RETRY_MAX_ATTEMPTS = 8;

function scheduleEvalRetry() {
  clearTimeout(evalRetryTimer);
  if (evalRetryAttempt >= EVAL_RETRY_MAX_ATTEMPTS) {
    evalStatus.value = "error";
    evalError.value = "体检指标生成超时，请稍后重新加载";
    return;
  }
  evalRetryAttempt += 1;
  const delay = Math.min(30_000, EVAL_RETRY_BASE_MS * (2 ** Math.min(3, evalRetryAttempt - 1)));
  evalRetryTimer = setTimeout(fetchEvaluation, delay);
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
  const district = props.district || DISTRICT_ALL;

  if (evalStatus.value !== "generating") {
    evalStatus.value = "loading";
  }
  evalError.value = "";

  getCachedEvaluation(model, district)
    .then((payload = {}) => {
      if (seq !== evalRequestSeq || props.model !== model || props.district !== district) return;
      if (payload.status === "generating") {
        evalStatus.value = "generating";
        scheduleEvalRetry();
        return;
      }
      evalValues.value = payload.values || {};
      evalAvailability.value = payload.availability || {};
      evalRetryAttempt = 0;
      evalStatus.value = "ready";
    })
    .catch((error) => {
      if (seq !== evalRequestSeq || props.model !== model
        || props.district !== district || isCanceledRequest(error)) return;
      evalError.value = error?.message || "体检评估数据加载失败";
      evalStatus.value = "error";
    });
}

onMounted(() => {
  updateRightPanelVisibility();
  loadDistrictOptions();
});

// 模型/行政区任一变化都从这里唯一发起请求（含首帧 immediate），
// 保证 evalRequestSeq 单调且与 props.district 一一对应。
watch([() => props.model, () => props.district], () => {
  evalRequestSeq += 1;
  clearTimeout(evalRetryTimer);
  evalAbortController?.abort();
  evalValues.value = null;
  evalAvailability.value = {};
  evalError.value = "";
  evalRetryAttempt = 0;
  evalStatus.value = "loading";
  fetchEvaluation();
}, { immediate: true });

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

/******************************** 单表 + 指标类型合并单元格 ********************************/
// 一张表铺全部指标，首列"指标类型"用 rowspan 合并同类：
// 每类首行携带 rowSpan，其余行不渲染该 td。
const tableRows = computed(() => {
  const rows = [];
  EVALUATION_DIMENSIONS.forEach((dimension) => {
    const indicators = EVALUATION_INDICATORS.filter((item) => item.dimension === dimension);
    indicators.forEach((item, index) => {
      rows.push({
        ...item,
        dimension,
        display: modelValueDisplay(item),
        direction: directionInfo(item),
        rowSpan: index === 0 ? indicators.length : 0,
        isDimensionStart: index === 0,
      });
    });
  });
  return rows;
});

/******************************** 逐类雷达图（每个指标类型一张） ********************************/
const MODEL_SERIES_COLOR = "#0071e3";
const STANDARD_SERIES_COLOR = "#f97316";
const MODEL_SERIES_NAME = "模型统计值";
const STANDARD_SERIES_NAME = "规范建议标准";

const radarDimensionScores = computed(() => dimensionRadarScores((indicator) => modelValueOf(indicator)));

function prefersReducedMotion() {
  return typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches === true;
}

function percentText(score) {
  return `${Math.round(score * 100)}%`;
}

/**
 * 单个指标类型的雷达图配置：轴 = 该类里有规范建议标准的指标。
 * 目前需求强度/场站设施各只有 1 项、运营服务 2 项，轴数不足 3 根时雷达是退化形态
 * （一根轴是一条辐条、两根轴是一条直线）——这是指标表本身还没补全，补上就自然撑开，
 * 不为此改成别的图形。
 */
function buildDimensionRadarOption(card) {
  const members = RADAR_INDICATORS.filter((item) => item.dimension === card.dimension);
  const scoreOf = (indicator) => card.scored.find((item) => item.indicator === indicator)?.score ?? null;
  // 类内全部统计到才连面：radar 没法跳过空轴，连出来的多边形必然被拉到圆心，
  // 看着就是"该项得 0 分"。缺项时只落点，点到虚线基准环的距离照样读得出达标情况。
  const isComplete = members.length > 0 && card.scored.length === members.length;
  const ink = chartInk.value;
  const dark = isDarkTheme.value;
  const reduceMotion = prefersReducedMotion();

  return {
    backgroundColor: "transparent",
    animation: !reduceMotion,
    animationDuration: reduceMotion ? 0 : 700,
    animationEasing: "cubicOut",
    tooltip: {
      show: false,
    },
    radar: {
      indicator: members.map((indicator) => ({
        name: scoreOf(indicator) == null
          ? `{name|${indicator.shortName}}\n{na|暂无数据}`
          : `{name|${indicator.shortName}}`,
        max: RADAR_MAX_SCORE,
      })),
      center: ["50%", "53%"],
      // 半径按轴数分档：3 轴以上左右两侧挂轴名，必须留出净空（42% 在 1366 宽下
      // 仍会切掉"重复系数"半个字，收到 36%）；1~2 轴是竖直单轴，没有横向轴名，
      // 放大到 48% 才不会在方格里缩成一小截。
      radius: members.length >= 3 ? "36%" : "48%",
      splitNumber: 3,
      axisName: {
        rich: {
          name: { color: ink.text, fontSize: 10, fontWeight: 700, fontFamily: "var(--dm2-font)" },
          na: { color: ink.textSoft, fontSize: 9, fontWeight: 500, padding: [2, 0, 0, 0] },
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
            value: members.map(() => RADAR_STANDARD_SCORE),
            name: STANDARD_SERIES_NAME,
            // 轴数 <3 时基准环退化成一条线甚至一个点，虚线看不出来，补个小刻度标住 100% 的位置
            symbol: members.length < 3 ? "rect" : "none",
            symbolSize: [14, 2],
            // 基准环只留虚线，不铺面：铺满 100% 的色块会把模型多边形整个吃掉
            itemStyle: { color: STANDARD_SERIES_COLOR },
            lineStyle: { color: STANDARD_SERIES_COLOR, width: 1.6, type: "dashed" },
          },
          // 一项都没统计到的类只留轴与基准环，不画任何模型图元
          ...(card.scored.length ? [{
            value: members.map(scoreOf),
            name: MODEL_SERIES_NAME,
            symbol: "circle",
            itemStyle: { color: MODEL_SERIES_COLOR },
            lineStyle: isComplete
              ? { color: MODEL_SERIES_COLOR, width: 2.2 }
              : { width: 0, opacity: 0 },
            ...(isComplete ? { areaStyle: { color: "rgba(0, 113, 227, 0.18)" } } : {}),
          }] : []),
        ],
      },
    ],
  };
}

const radarCards = computed(() => radarDimensionScores.value.map((card) => ({
  ...card,
  scoreText: card.score == null ? "暂无数据" : percentText(card.score),
  // 类得分与表内单指标同一套语义：达到基准环即达标
  scoreClass: card.score == null
    ? "is-none"
    : (card.score >= RADAR_STANDARD_SCORE ? "is-better" : "is-worse"),
  option: buildDimensionRadarOption(card),
})));
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

/* 暗色一律写成 `html.dark .x`（与 ModelLoadGate/MHeader 一致）：
   scoped 里写 :global(html.dark) 作嵌套父级，编译后后代选择器会被整段丢掉，
   规则最终落到 <html> 上，本组件一条都没生效。 */
html.dark .tjfx-full-dashboard {
  background: var(--dm2-surface-page, #0d1218);
}

.tjfx-inner-container {
  height: 100%;
  display: flex;
  flex-direction: column;
}

/* ── Main Dashboard 2-Column Grid ── */
.tjfx-grid-row {
  display: grid;
  /* 左栏要塞下 5 张小雷达（2 列），比单图版略宽一点 */
  grid-template-columns: minmax(460px, 33%) minmax(0, 1fr);
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
      /* 不换行：窄屏下让副标题省略，而不是把行政区下拉挤到第二排 */
      flex-wrap: nowrap;
    }

    .head-title-group {
      min-width: 0;

      .panel-title {
        margin: 0;
        font-size: 16px;
        font-weight: 780;
        line-height: 1.25;
        color: var(--dm2-ink, #0f172a);
      }

      /* 副标题必须单行：换行会把右侧行政区下拉挤到下一排，整个头部错位 */
      .panel-subtitle {
        margin: 4px 0 0;
        font-size: 11.5px;
        line-height: 1.4;
        color: var(--dm2-muted, #64748b);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }
    }

  }
}

/* 行政区下拉：挂在页头，不能嵌在 .tjfx-card-panel 里，否则页头那个拿不到样式（会缩成空框） */
.district-select-wrapper {
  display: flex;
  align-items: center;
  flex-shrink: 0;

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

/* ── 左栏：五格雷达，2×3 细线分格，最后一格扩满整行 ──
   不用嵌套卡片（卡中卡），改成 1px 发丝线切分的仪表盘；
   行高按 1fr 均分并 overflow:hidden，任何视口都不出现滚动条。 */
.tjfx-radar-panel {
  .radar-grid {
    flex: 1;
    min-height: 400px;
    margin-top: var(--dm2-space-3, 12px);
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    grid-template-rows: repeat(3, minmax(0, 1fr));
    /* gap 露出容器底色 = 发丝分隔线，比给每格描边少一半线宽误差 */
    gap: 1px;
    background: var(--dm2-line-faint, rgba(17, 32, 58, 0.07));
    border: 1px solid var(--dm2-line-faint, rgba(17, 32, 58, 0.07));
    border-radius: var(--dm2-radius-sm, 10px);
    overflow: hidden;
  }

  .radar-cell {
    display: flex;
    flex-direction: column;
    min-width: 0;
    min-height: 0;
    background: var(--dm2-surface, #ffffff);
    padding: var(--dm2-space-2, 8px) var(--dm2-space-2, 8px) var(--dm2-space-1, 4px);

    &:last-child {
      grid-column: span 2;
    }
  }

  .radar-cell-head {
    display: flex;
    align-items: baseline;
    gap: var(--dm2-space-1, 4px);
    flex-shrink: 0;

    .radar-cell-title {
      margin: 0;
      font-size: 12px;
      font-weight: 700;
      letter-spacing: -0.01em;
      color: var(--dm2-ink, #0f172a);
      white-space: nowrap;
    }

    .radar-cell-score {
      margin-left: auto;
      font-family: var(--dm2-font-num, tabular-nums);
      font-size: 13px;
      font-weight: 780;
      white-space: nowrap;

      &.is-better { color: #16a34a; }
      &.is-worse { color: #dc2626; }
      &.is-none { color: var(--dm2-muted, #667085); font-weight: 600; font-size: 11px; }
    }

    .radar-cell-count {
      font-size: 10px;
      /* muted-soft (#98a2b3) 在白底只有 ~2.6:1，正文级小字必须用 muted (~5:1) */
      color: var(--dm2-muted, #667085);
      white-space: nowrap;
    }
  }

  .radar-cell-body {
    flex: 1;
    min-height: 0;
    width: 100%;

    .chart-box, .radar-chart {
      width: 100%;
      height: 100%;
    }
  }
}

/* Indicator Table Section */
.tjfx-table-panel {
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

    /* 指标类型合并单元格：整类只有一格，竖直居中，右侧一条分隔线 */
    .col-dim {
      width: 104px;
      vertical-align: middle;
      border-right: 1px solid var(--dm2-line-faint, rgba(0, 0, 0, 0.07));
    }

    td.col-dim {
      background: var(--dm2-surface-sunken, #f8fafc);
    }

    .dim-cell {
      display: flex;
      align-items: center;
      gap: 8px;

      .tag-bar {
        width: 3px;
        height: 14px;
        border-radius: 2px;
        background: var(--dm2-accent, #0071e3);
        flex-shrink: 0;
      }

      .dim-name {
        font-size: 12.5px;
        font-weight: 780;
        color: var(--dm2-ink, #0f172a);
        white-space: nowrap;
      }
    }

    .indicator-row {
      td {
        /* 17 行一次铺完，行高压到 1080p 下整表免滚动 */
        padding: 8px 12px;
        border-bottom: 1px solid var(--dm2-line-faint, rgba(0, 0, 0, 0.05));

        &.text-right { text-align: right; }
        &.text-center { text-align: center; }
      }

      /* 合并单元格跨行，行 hover 底色只能落在非合并列上，否则会盖掉整类 */
      &:hover td:not(.col-dim) {
        background: rgba(0, 113, 227, 0.02);
      }

      /* 每类第一行加一条稍重的分隔线，替代原来的分组标题行 */
      &.is-dim-start td {
        border-top: 1px solid var(--dm2-line-faint, rgba(0, 0, 0, 0.08));
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

      /* 正负向角标：↑正向 / ↓负向 / ↔区间，紧跟建议值 */
      .dir-mark {
        margin-left: 5px;
        font-size: 11px;
        font-weight: 700;
        color: var(--dm2-muted-soft, #94a3b8);
        cursor: help;
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
  gap: 16px;
  height: 100%;

  .sk-box {
    border-radius: 16px;
    background: linear-gradient(90deg, rgba(0, 0, 0, 0.04) 25%, rgba(0, 0, 0, 0.08) 37%, rgba(0, 0, 0, 0.04) 63%);
    background-size: 400% 100%;
    animation: skeleton-loading 1.4s ease infinite;
  }

  /* 骨架按最终版式：左栏雷达、右栏明细表 */
  .sk-grid-row {
    display: grid;
    grid-template-columns: minmax(460px, 33%) minmax(0, 1fr);
    gap: 20px;
    flex: 1;
    min-height: 0;
  }
}

@keyframes skeleton-loading {
  0% { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}

/* ── 暗色模式（html.dark，跟随底图选择） ── */
html.dark .tjfx-card-panel,
html.dark .tjfx-state-card {
  background: linear-gradient(180deg, rgba(16, 22, 30, 0.97), rgba(13, 18, 25, 0.94));
  border-color: rgba(255, 255, 255, 0.08);
}

html.dark .tjfx-card-panel .panel-head {
  border-bottom-color: rgba(255, 255, 255, 0.08);
}

html.dark .tjfx-card-panel .panel-head .panel-title,
html.dark .tjfx-radar-panel .radar-cell-title {
  color: #f8fafc;
}

html.dark .tjfx-card-panel .panel-head .panel-subtitle,
html.dark .tjfx-radar-panel .radar-cell-count,
html.dark .tjfx-radar-panel .legend-note {
  color: #94a3b8;
}

/* 发丝分格盘：暗色下格子取面板底色，gap 与外框走暗色分隔线 */
html.dark .tjfx-radar-panel .radar-grid {
  background: rgba(148, 180, 220, 0.14);
  border-color: rgba(148, 180, 220, 0.14);
}

html.dark .tjfx-radar-panel .radar-cell {
  background: linear-gradient(180deg, rgba(16, 22, 30, 0.97), rgba(13, 18, 25, 0.94));
}

html.dark .tjfx-radar-panel .legend-row dt,
html.dark .tjfx-radar-panel .legend-row dd b {
  color: #e2e8f0;
}

html.dark .tjfx-radar-panel .legend-row dd {
  color: #94a3b8;
}

html.dark .tjfx-state-card .state-content h3 { color: #f8fafc; }
html.dark .tjfx-state-card .state-content p { color: #94a3b8; }
html.dark .tjfx-state-card .state-icon { background: rgba(255, 255, 255, 0.06); }

html.dark .tjfx-indicator-table thead { background: linear-gradient(180deg, rgba(16, 22, 30, 0.97), rgba(13, 18, 25, 0.94)); }
html.dark .tjfx-indicator-table th {
  border-bottom-color: rgba(255, 255, 255, 0.12);
  color: #94a3b8;
}
html.dark .tjfx-indicator-table .col-dim { border-right-color: rgba(255, 255, 255, 0.08); }
html.dark .tjfx-indicator-table td.col-dim { background: rgba(255, 255, 255, 0.03); }
html.dark .tjfx-indicator-table .dim-cell .dim-name { color: #f8fafc; }
html.dark .tjfx-indicator-table .indicator-row td { border-bottom-color: rgba(255, 255, 255, 0.05); }
html.dark .tjfx-indicator-table .indicator-row.is-dim-start td { border-top-color: rgba(255, 255, 255, 0.08); }
html.dark .tjfx-indicator-table .indicator-row:hover td:not(.col-dim) { background: rgba(255, 255, 255, 0.03); }
html.dark .tjfx-indicator-table .ind-name { color: #f8fafc; }
html.dark .tjfx-indicator-table .std-text,
html.dark .tjfx-indicator-table .gz-text,
html.dark .tjfx-indicator-table .dir-mark { color: #94a3b8; }

html.dark .tjfx-table-panel .table-footnote {
  border-top-color: rgba(255, 255, 255, 0.08);
  color: #94a3b8;
}

html.dark .tjfx-skeleton-stage .sk-box {
  background: linear-gradient(90deg, rgba(255, 255, 255, 0.05) 25%, rgba(255, 255, 255, 0.1) 37%, rgba(255, 255, 255, 0.05) 63%);
  background-size: 400% 100%;
}
</style>
