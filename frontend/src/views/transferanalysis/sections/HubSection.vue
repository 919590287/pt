<!-- 换乘站点分析：未选站展示站点排名，选站后展示该枢纽接驳表现。 -->
<template>
  <div class="ta-section">
    <template v-if="ctx.selection.hubId < 0">
      <div class="ta-card">
        <div class="ta-card-head ta-rank-head">
          <span class="ta-card-title">站点排名</span>
          <div class="ta-card-actions">
            <el-select v-model="rankMetric" size="small" class="ta-rank-metric" aria-label="站点排名口径">
              <el-option label="换乘客流量" value="flow" />
              <el-option label="平均换乘时间" value="avgSec" />
            </el-select>
            <button type="button" class="ta-export" @click="exportRankDetail">导出 CSV</button>
          </div>
        </div>
        <VChart class="ta-chart ta-chart-rank" :option="rankOpt" autoresize :update-options="UPD" @click="onRankClick" />
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
        <VChart class="ta-chart ta-chart-rank" :option="lineRankOpt" autoresize :update-options="UPD" />
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

function selectStation(idx) {
  if (idx == null || idx < 0) return;
  ctx.selection.hubId = idx;
}

const topStations = computed(() =>
  (agg.value?.hubs || [])
    .slice()
    .sort((a, b) => (rankMetric.value === "avgSec" ? b.avgSec - a.avgSec : b.flow - a.flow))
    .slice(0, ctx.filters.topN),
);
const rankOpt = computed(() =>
  directionStackRankOption(
    topStations.value.map((h) => ({ ...h, name: ctx.hubName(h.idx), flow: ctx.expand(h.flow), b2m: ctx.expand(h.b2m), m2b: ctx.expand(h.m2b) })),
    ctx.chartTheme,
    ctx.animation,
    { metric: rankMetric.value },
  ),
);
function onRankClick(p) {
  const chartItems = topStations.value
    .slice()
    .sort((a, b) => (rankMetric.value === "avgSec" ? b.avgSec - a.avgSec : b.flow - a.flow));
  const item = chartItems[chartItems.length - 1 - p.dataIndex];
  if (item) selectStation(item.idx);
}

function exportRankDetail() {
  const rows = [["地铁站", "换乘人次(模型原值)", "公交→地铁", "地铁→公交", "平均换乘时间(分)"]];
  (agg.value?.hubs || []).forEach((h) => {
    rows.push([
      ctx.hubName(h.idx),
      ctx.expand(h.flow),
      ctx.expand(h.b2m),
      ctx.expand(h.m2b),
      (h.avgSec / 60).toFixed(1),
    ]);
  });
  ctx.exportCsv(rows, "换乘站点分析-站点排名.csv");
}

const kpiCards = computed(() => {
  const k = agg.value?.kpi;
  const detail = agg.value?.hubDetail;
  if (!k) return [];
  return [
    { label: "站点换乘人次", value: fmtCount(ctx.expand(k.events)), unit: "人次" },
    { label: "公交→地铁", value: fmtCount(ctx.expand(k.busToMetro)), unit: "人次" },
    { label: "地铁→公交", value: fmtCount(ctx.expand(k.metroToBus)), unit: "人次" },
    { label: "接驳公交线路", value: fmtCount(detail?.busLines?.length || 0), unit: "条" },
    { label: "关联地铁线路", value: fmtCount(detail?.metroLines?.length || 0), unit: "条" },
    { label: "平均换乘时间", value: fmtMin(k.avgSec) },
  ];
});

const topLines = computed(() => (agg.value?.hubDetail?.busLines || []).slice(0, ctx.filters.topN));
const lineRankOpt = computed(() =>
  rankBarOption(
    topLines.value.map((line) => ({ name: ctx.busLineName(line.idx), value: ctx.expand(line.flow), avgSec: line.avgSec })),
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
