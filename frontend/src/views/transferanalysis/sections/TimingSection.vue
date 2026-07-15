<!-- 换乘衔接：时间表现专题（阈值可调、着色指标切换、长换乘识别） -->
<template>
  <div class="ta-section">
    <div class="ta-filters">
      <div class="ta-filter-row">
        <span class="ta-filter-label">长换乘</span>
        <el-radio-group v-model="longModel" size="small" class="ta-pills">
          <el-radio-button v-for="m in [5, 10, 15, 20]" :key="m" :value="m">>{{ m }}min</el-radio-button>
        </el-radio-group>
      </div>
      <div class="ta-filter-row">
        <span class="ta-filter-label">地图着色</span>
        <el-radio-group v-model="metricModel" size="small" class="ta-pills">
          <el-radio-button value="avgSec">平均</el-radio-button>
          <el-radio-button value="p50Sec">中位</el-radio-button>
          <el-radio-button value="p90Sec">P90</el-radio-button>
          <el-radio-button value="longShare">长换乘比例</el-radio-button>
        </el-radio-group>
      </div>
    </div>

    <div class="ta-kpis">
      <div v-for="k in kpiCards" :key="k.label" class="ta-kpi">
        <span class="ta-kpi-label">{{ k.label }}</span>
        <span class="ta-kpi-value">{{ k.value }}<i v-if="k.unit" class="ta-kpi-unit">{{ k.unit }}</i></span>
      </div>
    </div>
    <p class="ta-note">换乘时间 = 后序上车时刻 − 前序下车时刻（步行 + 候车合计，首期不拆分）。</p>

    <div class="ta-card">
      <div class="ta-card-head"><span class="ta-card-title">换乘时间分钟分布</span><span class="ta-card-hint">虚线为长换乘阈值</span></div>
      <VChart class="ta-chart" :option="minuteHistOpt" autoresize :update-options="UPD" />
    </div>

    <div class="ta-card">
      <div class="ta-card-head"><span class="ta-card-title">换乘时间累计分布</span></div>
      <VChart class="ta-chart ta-chart-sm" :option="cumulativeOpt" autoresize :update-options="UPD" />
    </div>

    <div class="ta-card">
      <div class="ta-card-head"><span class="ta-card-title">长换乘枢纽排名</span><span class="ta-card-hint">>{{ ctx.filters.longMin }}min 人次</span></div>
      <VChart class="ta-chart ta-chart-rank" :option="longHubRankOpt" autoresize :update-options="UPD" @click="onLongHubClick" />
    </div>

    <div class="ta-card">
      <div class="ta-card-head"><span class="ta-card-title">长换乘 公交线—地铁线 排名</span></div>
      <VChart class="ta-chart ta-chart-rank" :option="longPairRankOpt" autoresize :update-options="UPD" />
    </div>

    <div class="ta-card">
      <div class="ta-card-head"><span class="ta-card-title">分时换乘量与平均换乘时间</span><span class="ta-card-hint">按后序上车时刻</span></div>
      <VChart class="ta-chart" :option="dualAxisOpt" autoresize :update-options="UPD" />
    </div>

    <div class="ta-card">
      <div class="ta-card-head"><span class="ta-card-title">Top 枢纽换乘时间箱线</span><span class="ta-card-hint">五数 + P90 标注</span></div>
      <VChart class="ta-chart ta-chart-box" :option="boxplotOpt" autoresize :update-options="UPD" />
    </div>

    <div class="ta-card">
      <div class="ta-card-head">
        <span class="ta-card-title">枢纽衔接明细</span>
        <button type="button" class="ta-export" @click="exportDetail">导出 CSV</button>
      </div>
      <div class="ta-table">
        <div class="ta-table-row ta-table-head">
          <span class="c-name">枢纽</span><span class="c-num">人次</span><span class="c-num">均时</span><span class="c-num">P90</span><span class="c-num">长换乘</span><span class="c-num">占比</span>
        </div>
        <div v-for="row in detailRows" :key="row.idx" class="ta-table-row ta-row-click" @click="ctx.goHub(row.idx)">
          <span class="c-name" :title="row.name">{{ row.name }}</span>
          <span class="c-num">{{ fmtCount(row.flow) }}</span>
          <span class="c-num">{{ fmtMin(row.avgSec) }}</span>
          <span class="c-num">{{ fmtMin(row.p90Sec) }}</span>
          <span class="c-num">{{ fmtCount(row.longCount) }}</span>
          <span class="c-num">{{ row.longShareText }}</span>
        </div>
        <div v-if="!detailRows.length" class="ta-empty-row">当前筛选下无换乘事件</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, inject } from "vue";
import { VChart } from "@/plugins/echarts";
import {
  cumulativeLineOption,
  fmtCount,
  fmtMin,
  histogramBarOption,
  hubBoxplotOption,
  rankBarOption,
  volumeTimeDualAxisOption,
} from "../chartOptions.js";

const ctx = inject("taCtx");
const UPD = { notMerge: true, lazyUpdate: true };
const agg = computed(() => ctx.agg.value);

const longModel = computed({
  get: () => ctx.filters.longMin,
  set: (v) => {
    ctx.filters.longMin = v;
  },
});
const metricModel = computed({
  get: () => ctx.selection.colorMetric,
  set: (v) => {
    ctx.selection.colorMetric = v;
  },
});

const kpiCards = computed(() => {
  const k = agg.value?.kpi;
  if (!k) return [];
  return [
    { label: "平均换乘时间", value: fmtMin(k.avgSec) },
    { label: "中位换乘时间", value: fmtMin(k.p50Sec) },
    { label: "P90 换乘时间", value: fmtMin(k.p90Sec) },
    { label: "15min 内完成", value: `${(k.within15Share * 100).toFixed(1)}%` },
    { label: `长换乘人次（>${ctx.filters.longMin}min）`, value: fmtCount(ctx.expand(k.longCount)), unit: "人次" },
    { label: "长换乘比例", value: `${(k.longShare * 100).toFixed(1)}%` },
  ];
});

const minuteHistOpt = computed(() => {
  const hist = agg.value?.histogramMin;
  if (!hist) return {};
  return histogramBarOption(null, ctx.chartTheme, ctx.animation, {
    longMin: ctx.filters.longMin,
    minuteMode: true,
    histogramMin: hist.map(ctx.expand),
  });
});

const cumulativeOpt = computed(() => {
  const cum = agg.value?.timingDetail?.cumulative;
  if (!cum) return {};
  return cumulativeLineOption(cum, ctx.chartTheme, ctx.animation, { longMin: ctx.filters.longMin });
});

const longHubs = computed(() =>
  (agg.value?.hubs || [])
    .filter((h) => h.longCount > 0)
    .sort((a, b) => b.longCount - a.longCount)
    .slice(0, ctx.filters.topN),
);
const longHubRankOpt = computed(() =>
  rankBarOption(
    longHubs.value.map((h) => ({ name: ctx.hubName(h.idx), value: ctx.expand(h.longCount), longShare: h.longShare })),
    ctx.chartTheme,
    ctx.animation,
    { color: ctx.chartTheme.warn, secondary: { key: "longShare", label: "长换乘占比", fmt: (v) => `${(v * 100).toFixed(1)}%` } },
  ),
);
function onLongHubClick(p) {
  const items = longHubs.value;
  const it = items[items.length - 1 - p.dataIndex];
  if (it) ctx.goHub(it.idx);
}

const longPairRankOpt = computed(() =>
  rankBarOption(
    (agg.value?.timingDetail?.longPairs || []).slice(0, ctx.filters.topN).map((pr) => ({
      name: `${ctx.busLineName(pr.busLine)} × ${ctx.metroLineName(pr.metroLine)}`,
      value: ctx.expand(pr.longCount),
      share: pr.flow ? pr.longCount / pr.flow : 0,
    })),
    ctx.chartTheme,
    ctx.animation,
    { color: ctx.chartTheme.warn, secondary: { key: "share", label: "占该关系比例", fmt: (v) => `${(v * 100).toFixed(1)}%` } },
  ),
);

const dualAxisOpt = computed(() => {
  const s = agg.value?.series;
  if (!s) return {};
  return volumeTimeDualAxisOption(
    { labels: s.labels, busToMetro: s.busToMetro.map(ctx.expand), metroToBus: s.metroToBus.map(ctx.expand), avgSec: s.avgSec },
    ctx.chartTheme,
    ctx.animation,
  );
});

const boxplotOpt = computed(() => {
  const items = agg.value?.timingDetail?.boxplot || [];
  if (!items.length) return {};
  return hubBoxplotOption(
    items.map((b) => ({ name: ctx.hubName(b.idx), five: b.five, p90: b.p90 })),
    ctx.chartTheme,
    ctx.animation,
  );
});

const detailRows = computed(() =>
  (agg.value?.hubs || []).slice(0, 20).map((h) => ({
    idx: h.idx,
    name: ctx.hubName(h.idx),
    flow: ctx.expand(h.flow),
    avgSec: h.avgSec,
    p90Sec: h.p90Sec,
    longCount: ctx.expand(h.longCount),
    longShareText: `${(h.longShare * 100).toFixed(1)}%`,
  })),
);

function exportDetail() {
  const rows = [["枢纽", "换乘人次(扩样)", "平均换乘时间(分)", "中位(分)", "P90(分)", `长换乘人次(>${ctx.filters.longMin}min)`, "长换乘比例(%)"]];
  (agg.value?.hubs || []).forEach((h) => {
    rows.push([
      ctx.hubName(h.idx),
      ctx.expand(h.flow),
      (h.avgSec / 60).toFixed(1),
      (h.p50Sec / 60).toFixed(1),
      (h.p90Sec / 60).toFixed(1),
      ctx.expand(h.longCount),
      (h.longShare * 100).toFixed(1),
    ]);
  });
  ctx.exportCsv(rows, "换乘衔接-枢纽明细.csv");
}
</script>
