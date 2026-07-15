<!-- 接驳公交线路：单线的地铁接驳贡献 / 服务枢纽 / Route 差异 / 分时需求 -->
<template>
  <div class="ta-section">
    <div class="ta-filters">
      <div class="ta-filter-row">
        <span class="ta-filter-label">公交线</span>
        <el-select v-model="lineModel" size="small" filterable clearable placeholder="选择公交线路（按换乘量排序）" class="ta-filter-sel">
          <el-option v-for="l in ctx.lineOptions.value" :key="l.idx" :label="`${l.name}（${fmtCount(ctx.expand(l.flow))}人次）`" :value="l.idx" />
        </el-select>
      </div>
      <div v-if="ctx.selection.busLineId >= 0 && routeChoices.length > 1" class="ta-filter-row">
        <span class="ta-filter-label">方向</span>
        <el-radio-group v-model="routeModel" size="small" class="ta-pills">
          <el-radio-button :value="-1">全部</el-radio-button>
          <el-radio-button v-for="r in routeChoices" :key="r.idx" :value="r.idx">{{ r.name }}</el-radio-button>
        </el-radio-group>
      </div>
      <div v-if="ctx.selection.busLineId >= 0" class="ta-filter-row">
        <span class="ta-filter-label">枢纽</span>
        <el-select v-model="hubsModel" size="small" multiple collapse-tags :max-collapse-tags="2" clearable placeholder="全部服务枢纽" class="ta-filter-sel">
          <el-option v-for="h in hubChoices" :key="h.idx" :label="h.name" :value="h.idx" />
        </el-select>
      </div>
    </div>

    <div v-if="ctx.selection.busLineId < 0" class="ta-blank">
      <p class="ta-blank-title">请选择一条公交线路</p>
      <p class="ta-blank-sub">可在上方下拉选择，或在枢纽模块的接驳线路排名中点击进入。</p>
    </div>

    <template v-else>
      <div class="ta-kpis">
        <div v-for="k in kpiCards" :key="k.label" class="ta-kpi">
          <span class="ta-kpi-label">{{ k.label }}</span>
          <span class="ta-kpi-value">{{ k.value }}<i v-if="k.unit" class="ta-kpi-unit">{{ k.unit }}</i></span>
        </div>
      </div>

      <!-- 接驳率：分母固定并写进展示名（设计方案 v2 §6.3），分母数据缺失时显示占位 -->
      <div class="ta-ratio-card">
        <div class="ta-ratio-row">
          <span class="ta-ratio-name">公交→地铁接驳率<i class="ta-ratio-cal">占全线下车人次比例</i></span>
          <span class="ta-ratio-value">{{ feederRatioText }}</span>
        </div>
        <div class="ta-ratio-row">
          <span class="ta-ratio-name">地铁→公交承接率<i class="ta-ratio-cal">占全线上车人次比例</i></span>
          <span class="ta-ratio-value">{{ receiveRatioText }}</span>
        </div>
      </div>

      <div class="ta-card">
        <div class="ta-card-head"><span class="ta-card-title">服务枢纽换乘量排名</span><span class="ta-card-hint">点击查看枢纽</span></div>
        <VChart class="ta-chart ta-chart-rank" :option="hubRankOpt" autoresize :update-options="UPD" @click="onHubRankClick" />
      </div>

      <div class="ta-card">
        <div class="ta-card-head"><span class="ta-card-title">接驳地铁线路构成</span></div>
        <VChart class="ta-chart ta-chart-sm" :option="metroPieOpt" autoresize :update-options="UPD" />
      </div>

      <div v-if="routeChoices.length > 1" class="ta-card">
        <div class="ta-card-head"><span class="ta-card-title">Route / 方向换乘对比</span></div>
        <VChart class="ta-chart ta-chart-sm" :option="routeBarOpt" autoresize :update-options="UPD" />
      </div>

      <div class="ta-card">
        <div class="ta-card-head"><span class="ta-card-title">分时接驳需求</span><span class="ta-card-hint">按后序上车时刻</span></div>
        <VChart class="ta-chart" :option="seriesOpt" autoresize :update-options="UPD" />
      </div>

      <div class="ta-card">
        <div class="ta-card-head"><span class="ta-card-title">枢纽换乘量 vs 平均换乘时间</span></div>
        <VChart class="ta-chart ta-chart-sm" :option="scatterOpt" autoresize :update-options="UPD" />
      </div>

      <div class="ta-card">
        <div class="ta-card-head">
          <span class="ta-card-title">服务枢纽明细</span>
          <button type="button" class="ta-export" @click="exportDetail">导出 CSV</button>
        </div>
        <div class="ta-table">
          <div class="ta-table-row ta-table-head">
            <span class="c-name">枢纽</span><span class="c-num">人次</span><span class="c-num">公→地</span><span class="c-num">地→公</span><span class="c-num">均时</span><span class="c-num">P90</span>
          </div>
          <div v-for="row in detailRows" :key="row.idx" class="ta-table-row ta-row-click" @click="ctx.goHub(row.idx)">
            <span class="c-name" :title="row.name">{{ row.name }}</span>
            <span class="c-num">{{ fmtCount(row.flow) }}</span>
            <span class="c-num">{{ fmtCount(row.b2m) }}</span>
            <span class="c-num">{{ fmtCount(row.m2b) }}</span>
            <span class="c-num">{{ fmtMin(row.avgSec) }}</span>
            <span class="c-num">{{ fmtMin(row.p90Sec) }}</span>
          </div>
          <div v-if="!detailRows.length" class="ta-empty-row">当前筛选下无换乘事件</div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, inject, ref, watch } from "vue";
import { VChart } from "@/plugins/echarts";
import {
  directionPieOption,
  dualDirectionLineOption,
  fmtCount,
  fmtMin,
  hubScatterOption,
  rankBarOption,
  routeGroupBarOption,
} from "../chartOptions.js";

const ctx = inject("taCtx");
const UPD = { notMerge: true, lazyUpdate: true };
const agg = computed(() => ctx.agg.value);

/* ---------- 筛选 ---------- */

const lineModel = computed({
  get: () => (ctx.selection.busLineId >= 0 ? ctx.selection.busLineId : ""),
  set: (v) => {
    ctx.selection.busLineId = v === "" || v == null ? -1 : v;
    ctx.selection.routeIdx = -1;
    ctx.selection.hubIds = [];
  },
});
const routeModel = computed({
  get: () => ctx.selection.routeIdx,
  set: (v) => {
    ctx.selection.routeIdx = v;
  },
});
const hubsModel = computed({
  get: () => ctx.selection.hubIds,
  set: (v) => {
    ctx.selection.hubIds = Array.isArray(v) ? v : [];
  },
});

const routeChoices = computed(() => {
  const line = ctx.dict.value?.busLines?.[ctx.selection.busLineId];
  return (line?.routes || []).map((r, idx) => ({ idx, name: r.name || `Route ${idx + 1}` }));
});

/** 服务枢纽多选候选：取"未套用多选"时的完整枢纽列表并缓存 */
const hubChoiceCache = ref([]);
watch(
  () => [agg.value, ctx.selection.busLineId],
  () => {
    if (ctx.selection.busLineId < 0) {
      hubChoiceCache.value = [];
      return;
    }
    if (!ctx.selection.hubIds.length) {
      const list = agg.value?.hubs || [];
      if (list.length || !hubChoiceCache.value.length) {
        hubChoiceCache.value = list.map((h) => ({ idx: h.idx, name: ctx.hubName(h.idx) }));
      }
    }
  },
  { immediate: true },
);
const hubChoices = computed(() => hubChoiceCache.value);

/* ---------- KPI 与接驳率 ---------- */

const kpiCards = computed(() => {
  const k = agg.value?.kpi;
  const d = agg.value?.feederDetail;
  if (!k) return [];
  return [
    { label: "线路换乘人次", value: fmtCount(ctx.expand(k.events)), unit: "人次" },
    { label: "公交→地铁", value: fmtCount(ctx.expand(k.busToMetro)), unit: "人次" },
    { label: "地铁→公交", value: fmtCount(ctx.expand(k.metroToBus)), unit: "人次" },
    { label: "服务枢纽", value: fmtCount(agg.value?.hubs?.length || 0), unit: "个" },
    { label: "关联地铁线", value: fmtCount(d?.metroLines?.length || 0), unit: "条" },
    { label: "平均换乘时间", value: fmtMin(k.avgSec) },
  ];
});

// 分母 = 该线全线全日上/下车人次（抽样口径，与分子同尺度，比率无需扩样）——由 transfer-dict 下发
const lineMeta = computed(() => ctx.dict.value?.busLines?.[ctx.selection.busLineId] || null);
const feederRatioText = computed(() => {
  const k = agg.value?.kpi;
  const den = Number(lineMeta.value?.alightings);
  if (!k || !Number.isFinite(den) || den <= 0) return "—";
  return `${((k.busToMetro / den) * 100).toFixed(1)}%`;
});
const receiveRatioText = computed(() => {
  const k = agg.value?.kpi;
  const den = Number(lineMeta.value?.boardings);
  if (!k || !Number.isFinite(den) || den <= 0) return "—";
  return `${((k.metroToBus / den) * 100).toFixed(1)}%`;
});

/* ---------- 图表 ---------- */

const topHubs = computed(() => (agg.value?.hubs || []).slice(0, ctx.filters.topN));
const hubRankOpt = computed(() =>
  rankBarOption(
    topHubs.value.map((h) => ({ name: ctx.hubName(h.idx), value: ctx.expand(h.flow), avgSec: h.avgSec })),
    ctx.chartTheme,
    ctx.animation,
    { secondary: { key: "avgSec", label: "平均换乘时间", fmt: fmtMin } },
  ),
);
function onHubRankClick(p) {
  const items = topHubs.value;
  const it = items[items.length - 1 - p.dataIndex];
  if (it) ctx.goHub(it.idx);
}

const metroPieOpt = computed(() => {
  const list = agg.value?.feederDetail?.metroLines || [];
  if (!list.length) return {};
  const theme = ctx.chartTheme;
  const base = directionPieOption({ busToMetro: 0, metroToBus: 0 }, theme, ctx.animation);
  base.series[0].data = list.map((m, i) => ({
    name: ctx.metroLineName(m.idx),
    value: ctx.expand(m.flow),
    itemStyle: { color: theme.pieColors[i % theme.pieColors.length] },
  }));
  base.tooltip.formatter = (p) => `${p.marker}${p.name}：${fmtCount(p.value)}人次（${p.percent}%）`;
  return base;
});

const routeBarOpt = computed(() => {
  const routes = agg.value?.feederDetail?.routes || [];
  if (!routes.length) return {};
  return routeGroupBarOption(
    routes.map((r) => ({
      name: routeChoices.value[r.routeIdx]?.name || `Route ${r.routeIdx + 1}`,
      b2m: ctx.expand(r.b2m),
      m2b: ctx.expand(r.m2b),
    })),
    ctx.chartTheme,
    ctx.animation,
  );
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

const scatterOpt = computed(() =>
  hubScatterOption(
    (agg.value?.hubs || []).map((h) => ({ name: ctx.hubName(h.idx), flow: ctx.expand(h.flow), avgSec: h.avgSec })),
    ctx.chartTheme,
    ctx.animation,
  ),
);

/* ---------- 明细 ---------- */

const detailRows = computed(() =>
  (agg.value?.hubs || []).slice(0, 20).map((h) => ({
    idx: h.idx,
    name: ctx.hubName(h.idx),
    flow: ctx.expand(h.flow),
    b2m: ctx.expand(h.b2m),
    m2b: ctx.expand(h.m2b),
    avgSec: h.avgSec,
    p90Sec: h.p90Sec,
  })),
);

function exportDetail() {
  const lineName = ctx.busLineName(ctx.selection.busLineId);
  const rows = [["公交线", "枢纽", "换乘人次(扩样)", "公交→地铁", "地铁→公交", "平均换乘时间(分)", "P90换乘时间(分)"]];
  (agg.value?.hubs || []).forEach((h) => {
    rows.push([
      lineName,
      ctx.hubName(h.idx),
      ctx.expand(h.flow),
      ctx.expand(h.b2m),
      ctx.expand(h.m2b),
      (h.avgSec / 60).toFixed(1),
      (h.p90Sec / 60).toFixed(1),
    ]);
  });
  ctx.exportCsv(rows, `接驳线路-${lineName}-明细.csv`);
}
</script>
