<!-- 换乘线路分析：以地铁线路为分析对象，查看整线接驳公交表现。 -->
<template>
  <div class="ta-section">
    <template v-if="ctx.selection.metroLineId < 0">
      <div class="ta-card">
        <div class="ta-card-head ta-rank-head">
          <span class="ta-card-title">换乘地铁线路 Top {{ ctx.filters.topN }}</span>
          <el-select v-model="rankMetric" size="small" class="ta-rank-metric" aria-label="地铁线路排名口径">
            <el-option label="换乘客流量" value="flow" />
            <el-option label="平均换乘时间" value="avgSec" />
          </el-select>
        </div>
        <VChart class="ta-chart ta-chart-rank" :option="rankOpt" autoresize :update-options="UPD" @click="onRankClick" />
      </div>

      <div class="ta-card">
        <div class="ta-card-head">
          <span class="ta-card-title">地铁线路换乘明细</span>
          <button type="button" class="ta-export" @click="exportRankDetail">导出 CSV</button>
        </div>
        <div class="ta-table">
          <div class="ta-table-row ta-table-head">
            <span class="c-name">地铁线路</span><span class="c-num">人次</span><span class="c-num">公→地</span><span class="c-num">地→公</span><span class="c-num">均时</span>
          </div>
          <button v-for="row in detailRows" :key="row.idx" type="button" class="ta-table-row ta-row-click ta-table-button" @click="selectLine(row.idx)">
            <span class="c-name" :title="row.name">{{ row.name }}</span>
            <span class="c-num">{{ fmtCount(row.flow) }}</span>
            <span class="c-num">{{ fmtCount(row.b2m) }}</span>
            <span class="c-num">{{ fmtCount(row.m2b) }}</span>
            <span class="c-num">{{ fmtMin(row.avgSec) }}</span>
          </button>
          <div v-if="!detailRows.length" class="ta-empty-row">当前时段无换乘事件</div>
        </div>
      </div>
    </template>

    <template v-else>
      <div class="ta-kpis">
        <div v-for="k in kpiCards" :key="k.label" class="ta-kpi">
          <span class="ta-kpi-label">{{ k.label }}</span>
          <span class="ta-kpi-value">{{ k.value }}<i v-if="k.unit" class="ta-kpi-unit">{{ k.unit }}</i></span>
        </div>
      </div>

      <div class="ta-card">
        <div class="ta-card-head"><span class="ta-card-title">接驳公交线路客流量排名</span></div>
        <VChart class="ta-chart ta-chart-rank" :option="busLineRankOpt" autoresize :update-options="UPD" />
      </div>

      <div class="ta-card">
        <div class="ta-card-head"><span class="ta-card-title">分时换乘量</span><span class="ta-card-hint">按后序上车时刻</span></div>
        <VChart class="ta-chart" :option="seriesOpt" autoresize :update-options="UPD" />
      </div>

      <div class="ta-card">
        <div class="ta-card-head"><span class="ta-card-title">换乘时间区间分布</span></div>
        <VChart class="ta-chart ta-chart-sm" :option="histOpt" autoresize :update-options="UPD" />
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, inject, ref } from "vue";
import { VChart } from "@/plugins/echarts";
import {
  directionStackRankOption,
  dualDirectionLineOption,
  fmtCount,
  fmtMin,
  histogramBarOption,
  minuteHistToSegments,
  rankBarOption,
} from "../chartOptions.js";

const ctx = inject("taCtx");
const UPD = { notMerge: true, lazyUpdate: true };
const agg = computed(() => ctx.agg.value);
const rankMetric = ref("flow");

function selectLine(idx) {
  if (idx == null || idx < 0) return;
  ctx.selection.metroLineId = idx;
}

const topMetroLines = computed(() =>
  (agg.value?.metroLines || [])
    .slice()
    .sort((a, b) => (rankMetric.value === "avgSec" ? b.avgSec - a.avgSec : b.flow - a.flow))
    .slice(0, ctx.filters.topN),
);
const rankOpt = computed(() =>
  directionStackRankOption(
    topMetroLines.value.map((line) => ({ ...line, name: ctx.metroLineName(line.idx), flow: ctx.expand(line.flow), b2m: ctx.expand(line.b2m), m2b: ctx.expand(line.m2b) })),
    ctx.chartTheme,
    ctx.animation,
    { metric: rankMetric.value },
  ),
);
function onRankClick(p) {
  const items = topMetroLines.value
    .slice()
    .sort((a, b) => (rankMetric.value === "avgSec" ? b.avgSec - a.avgSec : b.flow - a.flow));
  const item = items[items.length - 1 - p.dataIndex];
  if (item) selectLine(item.idx);
}

const detailRows = computed(() =>
  (agg.value?.metroLines || []).slice(0, 20).map((line) => ({
    ...line,
    name: ctx.metroLineName(line.idx),
    flow: ctx.expand(line.flow),
    b2m: ctx.expand(line.b2m),
    m2b: ctx.expand(line.m2b),
  })),
);
function exportRankDetail() {
  const rows = [["地铁线路", "换乘人次(模型原值)", "公交→地铁", "地铁→公交", "平均换乘时间(分)"]];
  (agg.value?.metroLines || []).forEach((line) => {
    rows.push([
      ctx.metroLineName(line.idx),
      ctx.expand(line.flow),
      ctx.expand(line.b2m),
      ctx.expand(line.m2b),
      (line.avgSec / 60).toFixed(1),
    ]);
  });
  ctx.exportCsv(rows, "换乘线路分析-地铁线路换乘明细.csv");
}

const kpiCards = computed(() => {
  const kpi = agg.value?.kpi;
  if (!kpi) return [];
  return [
    { label: "整线换乘人次", value: fmtCount(ctx.expand(kpi.events)), unit: "人次" },
    { label: "公交→地铁", value: fmtCount(ctx.expand(kpi.busToMetro)), unit: "人次" },
    { label: "地铁→公交", value: fmtCount(ctx.expand(kpi.metroToBus)), unit: "人次" },
    { label: "接驳公交线路", value: fmtCount(agg.value?.feederDetail?.busLines?.length || 0), unit: "条" },
    { label: "平均换乘时间", value: fmtMin(kpi.avgSec) },
  ];
});

const topBusLines = computed(() => (agg.value?.feederDetail?.busLines || []).slice(0, ctx.filters.topN));
const busLineRankOpt = computed(() =>
  rankBarOption(
    topBusLines.value.map((line) => ({ name: ctx.busLineName(line.idx), value: ctx.expand(line.flow), avgSec: line.avgSec })),
    ctx.chartTheme,
    ctx.animation,
    { secondary: { key: "avgSec", label: "平均换乘时间", fmt: fmtMin } },
  ),
);

const seriesOpt = computed(() => {
  const series = agg.value?.series;
  if (!series) return {};
  return dualDirectionLineOption(
    { labels: series.labels, busToMetro: series.busToMetro.map(ctx.expand), metroToBus: series.metroToBus.map(ctx.expand) },
    ctx.chartTheme,
    ctx.animation,
  );
});

const histOpt = computed(() => {
  const histogram = agg.value?.histogramMin;
  if (!histogram) return {};
  return histogramBarOption(minuteHistToSegments(histogram).map(ctx.expand), ctx.chartTheme, ctx.animation, { longMin: ctx.filters.longMin });
});
</script>
