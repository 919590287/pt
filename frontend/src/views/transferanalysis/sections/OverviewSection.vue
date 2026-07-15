<!--
  换乘总览:全网规模 / 分时 / 线对 Top / 时间分布(站点 Top 与换乘明细在换乘站点分析模块)。
  免滚动约束:面板内部高度典型 570-730px(vh/scale-82),布局为
  "KPI 单行固定 + 两张图弹性分高 + 分布条固定",由 index.vue 的 .ta-overview 规则驱动。
  平均/P90 换乘时间并入分布条卡头(同一时间域,省一行 KPI);
  时间区间分布用堆叠条替代柱状图(5 段数据条形图信息密度过低,条带更紧凑),
  配色取 chartTheme.heatRamp(绿→黄→红时间色带,与地图枢纽着色同源)。
-->
<template>
  <div class="ta-section ta-overview">
    <div class="ta-kpis ta-kpis-row">
      <div v-for="k in kpiCards" :key="k.label" class="ta-kpi" :title="k.tip">
        <span class="ta-kpi-label">{{ k.label }}</span>
        <span class="ta-kpi-value">{{ k.value }}</span>
      </div>
    </div>

    <div class="ta-card ta-card--series">
      <div class="ta-card-head"><span class="ta-card-title">分时换乘量</span><span class="ta-card-hint">按后序上车时刻 · 点击全屏</span></div>
      <div class="ta-chart-zoom" title="点击全屏查看" @click="zoomSeries">
        <VChart class="ta-chart" :option="seriesOpt" autoresize :update-options="UPD" />
      </div>
    </div>

    <div class="ta-card ta-card--rank">
      <div class="ta-card-head"><span class="ta-card-title">公交线—地铁线换乘关系 Top {{ ctx.filters.topN }}</span><span class="ta-card-hint">点击全屏</span></div>
      <div class="ta-chart-zoom" title="点击全屏查看" @click="zoomRank">
        <VChart class="ta-chart ta-chart-rank" :option="pairRankOpt" autoresize :update-options="UPD" />
      </div>
    </div>

    <div class="ta-card ta-card--dist">
      <div class="ta-card-head">
        <span class="ta-card-title">换乘时间区间分布</span>
        <span v-if="timeStats" class="ta-dist-stats">平均 <b>{{ timeStats.avg }}</b> · P90 <b>{{ timeStats.p90 }}</b></span>
      </div>
      <div class="ta-dist-strip" role="img" :aria-label="distAria">
        <div
          v-for="s in distribution"
          :key="s.label"
          class="ta-dist-seg"
          :style="{ flexGrow: s.value || 0, background: s.color }"
          :title="`${s.label}：${fmtCount(s.value)}人次（${s.pctText}）`"
        ></div>
      </div>
      <div class="ta-dist-legend">
        <span v-for="s in distribution" :key="s.label" class="ta-dist-item">
          <i class="ta-dist-dot" :style="{ background: s.color }"></i>{{ s.label }} <b class="ta-dist-pct">{{ s.pctText }}</b>
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, inject } from "vue";
import { VChart } from "@/plugins/echarts";
import {
  SEGMENT_LABELS,
  dualDirectionLineOption,
  fmtCount,
  fmtMin,
  minuteHistToSegments,
  rankBarOption,
} from "../chartOptions.js";

const ctx = inject("taCtx");
const UPD = { notMerge: true, lazyUpdate: true };

const agg = computed(() => ctx.agg.value);

// 单行 4 卡:数值不带单位后缀(标签即单位语义,口径进 title 提示),平均/P90 移入分布卡头
const kpiCards = computed(() => {
  const k = agg.value?.kpi;
  if (!k) return [];
  return [
    { label: "换乘人次", value: fmtCount(ctx.expand(k.events)), tip: "单位：人次。基于 30min+800m 时间—空间规则推定，已扩样" },
    { label: "公交→地铁", value: fmtCount(ctx.expand(k.busToMetro)), tip: "单位：人次（已扩样）" },
    { label: "地铁→公交", value: fmtCount(ctx.expand(k.metroToBus)), tip: "单位：人次（已扩样）" },
    { label: "换乘人数", value: fmtCount(ctx.expand(k.persons)), tip: "单位：人。去重 Agent 数（一人多次换乘只计一次）" },
  ];
});

const timeStats = computed(() => {
  const k = agg.value?.kpi;
  if (!k) return null;
  return { avg: fmtMin(k.avgSec), p90: fmtMin(k.p90Sec) };
});

const seriesOpt = computed(() => {
  const s = agg.value?.series;
  if (!s) return {};
  return dualDirectionLineOption(
    { labels: s.labels, busToMetro: s.busToMetro.map(ctx.expand), metroToBus: s.metroToBus.map(ctx.expand) },
    ctx.chartTheme,
    ctx.animation,
  );
});

const pairRankOpt = computed(() =>
  rankBarOption(
    (agg.value?.pairs || []).slice(0, ctx.filters.topN).map((pr) => ({
      name: `${ctx.busLineName(pr.busLine)} × ${ctx.metroLineName(pr.metroLine)}`,
      value: ctx.expand(pr.flow),
      avgSec: pr.avgSec,
    })),
    ctx.chartTheme,
    ctx.animation,
    { color: ctx.chartTheme.metroToBus, secondary: { key: "avgSec", label: "平均换乘时间", fmt: fmtMin } },
  ),
);

// 五段分布(0-5/5-10/10-15/15-20/20-30min):值扩样,占比对全量,色带低→高=绿→黄→红
const distribution = computed(() => {
  const hist = agg.value?.histogramMin;
  if (!hist) return [];
  const seg = minuteHistToSegments(hist).map(ctx.expand);
  const total = seg.reduce((a, b) => a + b, 0) || 1;
  const colors = ctx.chartTheme.heatRamp || [];
  return SEGMENT_LABELS.map((label, i) => ({
    label,
    value: seg[i],
    color: colors[i] || colors[colors.length - 1],
    pctText: `${((seg[i] / total) * 100).toFixed(1)}%`,
  }));
});

const distAria = computed(() => distribution.value.map((s) => `${s.label} ${s.pctText}`).join(","));

/* ---------- 图表点击全屏(效仿客流分析;option 传打开时刻快照) ---------- */

const zoomMeta = () => {
  const [s, e] = ctx.filters.timeRange || [0, 24];
  return `${s}:00-${e}:00 · ${ctx.filters.unitMin}min`;
};
function zoomSeries() {
  ctx.openChartFullscreen?.({ kicker: "换乘总览", title: "分时换乘量（按后序上车时刻）", meta: zoomMeta(), option: seriesOpt.value });
}
function zoomRank() {
  // 全屏版放宽类目名截断(labelWidth 92→260),线对全名可见
  const option = rankBarOption(
    (agg.value?.pairs || []).slice(0, ctx.filters.topN).map((pr) => ({
      name: `${ctx.busLineName(pr.busLine)} × ${ctx.metroLineName(pr.metroLine)}`,
      value: ctx.expand(pr.flow),
      avgSec: pr.avgSec,
    })),
    ctx.chartTheme,
    ctx.animation,
    { color: ctx.chartTheme.metroToBus, secondary: { key: "avgSec", label: "平均换乘时间", fmt: fmtMin }, labelWidth: 260 },
  );
  ctx.openChartFullscreen?.({ kicker: "换乘总览", title: `公交线—地铁线换乘关系 Top ${ctx.filters.topN}`, meta: zoomMeta(), option });
}
</script>
