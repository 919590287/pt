<!-- 换乘站点分析：单站点（地铁枢纽/公交站）接驳关系 / 矩阵 / 对端贡献 / 分时与时间分布 -->
<template>
  <div class="ta-section">
    <!-- 站点类型与站点搜索已迁至地图左上角搜索框（index.vue .ta-search，风格对齐运行监测） -->
    <div class="ta-filters">
      <div class="ta-filter-row">
        <span class="ta-filter-label">地铁线</span>
        <el-select v-model="metroModel" size="small" clearable placeholder="按地铁线路过滤" class="ta-filter-sel">
          <el-option v-for="m in metroChoices" :key="m.idx" :label="m.name" :value="m.idx" />
        </el-select>
      </div>
      <div v-if="ctx.selection.hubId >= 0" class="ta-filter-row">
        <span class="ta-filter-label">接驳线</span>
        <el-select v-model="busLinesModel" size="small" multiple collapse-tags :max-collapse-tags="2" clearable placeholder="全部接驳公交线" class="ta-filter-sel">
          <el-option v-for="l in feederChoices" :key="l.idx" :label="l.name" :value="l.idx" />
        </el-select>
      </div>
    </div>

    <!-- 未选中站点：展示当前口径的全网站点 Top 与换乘明细，点击即选中进入详情 -->
    <template v-if="ctx.selection.hubId < 0">
      <div class="ta-card">
        <div class="ta-card-head">
          <span class="ta-card-title">{{ busHub ? "换乘公交站" : "换乘地铁站" }} Top {{ ctx.filters.topN }}</span>
          <span class="ta-card-hint">点击选择站点</span>
        </div>
        <VChart class="ta-chart ta-chart-rank" :option="rankOpt" autoresize :update-options="UPD" @click="onRankClick" />
      </div>

      <div class="ta-card">
        <div class="ta-card-head">
          <span class="ta-card-title">{{ busHub ? "公交站换乘明细" : "地铁站换乘明细" }}</span>
          <button type="button" class="ta-export" @click="exportRankDetail">导出 CSV</button>
        </div>
        <div class="ta-table">
          <div class="ta-table-row ta-table-head">
            <span class="c-name">{{ kindWord }}</span><span class="c-num">人次</span><span class="c-num">公→地</span><span class="c-num">地→公</span><span class="c-num">均时</span><span class="c-num">P90</span>
          </div>
          <div v-for="row in rankDetailRows" :key="row.idx" class="ta-table-row ta-row-click" @click="selectStation(row.idx)">
            <span class="c-name" :title="row.name">{{ row.name }}</span>
            <span class="c-num">{{ fmtCount(row.flow) }}</span>
            <span class="c-num">{{ fmtCount(row.b2m) }}</span>
            <span class="c-num">{{ fmtCount(row.m2b) }}</span>
            <span class="c-num">{{ fmtMin(row.avgSec) }}</span>
            <span class="c-num">{{ fmtMin(row.p90Sec) }}</span>
          </div>
          <div v-if="!rankDetailRows.length" class="ta-empty-row">当前筛选下无换乘事件</div>
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
        <div class="ta-card-head"><span class="ta-card-title">接驳公交线路排名</span><span class="ta-card-hint">点击查看线路</span></div>
        <VChart class="ta-chart ta-chart-rank" :option="lineRankOpt" autoresize :update-options="UPD" @click="onLineRankClick" />
      </div>

      <div class="ta-card">
        <div class="ta-card-head"><span class="ta-card-title">{{ busHub ? "关联地铁站换乘贡献" : "接驳公交站换乘贡献" }}</span></div>
        <VChart class="ta-chart ta-chart-rank" :option="stopRankOpt" autoresize :update-options="UPD" />
      </div>

      <div class="ta-card">
        <div class="ta-card-head"><span class="ta-card-title">分时换乘量</span><span class="ta-card-hint">按后序上车时刻</span></div>
        <VChart class="ta-chart" :option="seriesOpt" autoresize :update-options="UPD" />
      </div>

      <div class="ta-card">
        <div class="ta-card-head"><span class="ta-card-title">换乘时间区间分布</span></div>
        <VChart class="ta-chart ta-chart-sm" :option="histOpt" autoresize :update-options="UPD" />
      </div>

      <div class="ta-card">
        <div class="ta-card-head">
          <span class="ta-card-title">换乘关系明细</span>
          <button type="button" class="ta-export" @click="exportDetail">导出 CSV</button>
        </div>
        <div class="ta-table">
          <div class="ta-table-row ta-table-head">
            <span class="c-name">公交线</span><span class="c-name">地铁线</span><span class="c-num">人次</span><span class="c-num">均时</span>
          </div>
          <div v-for="(row, i) in detailRows" :key="i" class="ta-table-row">
            <span class="c-name" :title="row.busName">{{ row.busName }}</span>
            <span class="c-name" :title="row.metroName">{{ row.metroName }}</span>
            <span class="c-num">{{ fmtCount(row.flow) }}</span>
            <span class="c-num">{{ fmtMin(row.avgSec) }}</span>
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

/* ---------- 站点口径（地铁站/公交站，逻辑同构） ---------- */

const busHub = computed(() => ctx.selection.hubKind === "bus");
const kindWord = computed(() => (busHub.value ? "公交站" : "地铁站"));
const selectedName = computed(() =>
  busHub.value ? ctx.busStopName(ctx.selection.hubId) : ctx.hubName(ctx.selection.hubId),
);
function selectStation(idx) {
  if (idx == null || idx < 0) return;
  ctx.selection.hubId = idx;
  ctx.selection.busLineIds = [];
}

/* ---------- 未选中态：全网站点 Top 与换乘明细（自换乘总览迁入） ---------- */

const topStations = computed(() => (agg.value?.hubs || []).slice(0, ctx.filters.topN));
const stationLabelOf = (idx) => (busHub.value ? ctx.busStopName(idx) : ctx.hubName(idx));
const rankOpt = computed(() =>
  rankBarOption(
    topStations.value.map((h) => ({ name: stationLabelOf(h.idx), value: ctx.expand(h.flow), avgSec: h.avgSec, idx: h.idx })),
    ctx.chartTheme,
    ctx.animation,
    { secondary: { key: "avgSec", label: "平均换乘时间", fmt: fmtMin } },
  ),
);
function onRankClick(p) {
  const items = topStations.value;
  const it = items[items.length - 1 - p.dataIndex];
  if (it) selectStation(it.idx);
}

const rankDetailRows = computed(() =>
  (agg.value?.hubs || []).slice(0, 15).map((h) => ({
    idx: h.idx,
    name: stationLabelOf(h.idx),
    flow: ctx.expand(h.flow),
    b2m: ctx.expand(h.b2m),
    m2b: ctx.expand(h.m2b),
    avgSec: h.avgSec,
    p90Sec: h.p90Sec,
  })),
);

function exportRankDetail() {
  const head = busHub.value ? "公交站" : "地铁站";
  const rows = [[head, "换乘人次(扩样)", "公交→地铁", "地铁→公交", "平均换乘时间(分)", "P90换乘时间(分)"]];
  (agg.value?.hubs || []).forEach((h) => {
    rows.push([
      stationLabelOf(h.idx),
      ctx.expand(h.flow),
      ctx.expand(h.b2m),
      ctx.expand(h.m2b),
      (h.avgSec / 60).toFixed(1),
      (h.p90Sec / 60).toFixed(1),
    ]);
  });
  ctx.exportCsv(rows, `换乘站点分析-${head}换乘明细.csv`);
}

/* ---------- 筛选 ---------- */

const metroModel = computed({
  get: () => (ctx.selection.metroLineId >= 0 ? ctx.selection.metroLineId : ""),
  set: (v) => {
    ctx.selection.metroLineId = v === "" || v == null ? -1 : v;
  },
});
const busLinesModel = computed({
  get: () => ctx.selection.busLineIds,
  set: (v) => {
    ctx.selection.busLineIds = Array.isArray(v) ? v : [];
  },
});

const metroChoices = computed(() => (ctx.dict.value?.metroLines || []).map((m, idx) => ({ idx, name: m.name })));

/** 接驳线多选候选：取"未套用多选"时的完整接驳线列表并缓存，避免选项随筛选塌缩 */
const feederChoiceCache = ref([]);
watch(
  () => [agg.value, ctx.selection.hubId],
  () => {
    if (ctx.selection.hubId < 0) {
      feederChoiceCache.value = [];
      return;
    }
    if (!ctx.selection.busLineIds.length) {
      const list = agg.value?.hubDetail?.busLines || [];
      if (list.length || !feederChoiceCache.value.length) {
        feederChoiceCache.value = list.map((l) => ({ idx: l.idx, name: ctx.busLineName(l.idx) }));
      }
    }
  },
  { immediate: true },
);
const feederChoices = computed(() => feederChoiceCache.value);

/* ---------- KPI ---------- */

const kpiCards = computed(() => {
  const k = agg.value?.kpi;
  const d = agg.value?.hubDetail;
  if (!k) return [];
  return [
    { label: "站点换乘人次", value: fmtCount(ctx.expand(k.events)), unit: "人次" },
    { label: "公交→地铁", value: fmtCount(ctx.expand(k.busToMetro)), unit: "人次" },
    { label: "地铁→公交", value: fmtCount(ctx.expand(k.metroToBus)), unit: "人次" },
    { label: "接驳公交线", value: fmtCount(d?.busLines?.length || 0), unit: "条" },
    { label: "关联地铁线", value: fmtCount(d?.metroLines?.length || 0), unit: "条" },
    { label: "平均 / P90", value: `${fmtMin(k.avgSec)} / ${fmtMin(k.p90Sec)}` },
  ];
});

/* ---------- 图表 ----------
 * 换乘矩阵热力图已按需求删除（2026-07-14）；线对量化信息由"换乘关系明细"表承接 */

const topLines = computed(() => (agg.value?.hubDetail?.busLines || []).slice(0, ctx.filters.topN));
const lineRankOpt = computed(() =>
  rankBarOption(
    topLines.value.map((l) => ({ name: ctx.busLineName(l.idx), value: ctx.expand(l.flow), avgSec: l.avgSec })),
    ctx.chartTheme,
    ctx.animation,
    { secondary: { key: "avgSec", label: "平均换乘时间", fmt: fmtMin } },
  ),
);
function onLineRankClick(p) {
  const items = topLines.value;
  const it = items[items.length - 1 - p.dataIndex];
  if (it) ctx.goFeeder(it.idx);
}

// 对端站点贡献：地铁口径=接驳公交站（busStops）；公交口径=关联地铁站（stopMetroLinks，
// 单一公交站过滤下每个地铁站唯一出现，口径与前者同构）
const counterpartStops = computed(() => {
  if (!busHub.value) {
    return (agg.value?.busStops || []).map((s) => ({ name: ctx.busStopName(s.idx), flow: s.flow, avgSec: s.avgSec }));
  }
  return (agg.value?.hubDetail?.stopMetroLinks || []).map((l) => ({ name: ctx.metroStopName(l.metroStop), flow: l.flow, avgSec: l.avgSec }));
});
const stopRankOpt = computed(() =>
  rankBarOption(
    counterpartStops.value.slice(0, ctx.filters.topN).map((s) => ({ name: s.name, value: ctx.expand(s.flow), avgSec: s.avgSec })),
    ctx.chartTheme,
    ctx.animation,
    { color: ctx.chartTheme.metroToBus, secondary: { key: "avgSec", label: "平均换乘时间", fmt: fmtMin } },
  ),
);

const seriesOpt = computed(() => {
  const s = agg.value?.series;
  if (!s) return {};
  return dualDirectionLineOption(
    { labels: s.labels, busToMetro: s.busToMetro.map(ctx.expand), metroToBus: s.metroToBus.map(ctx.expand) },
    ctx.chartTheme,
    ctx.animation,
  );
});

const histOpt = computed(() => {
  const hist = agg.value?.histogramMin;
  if (!hist) return {};
  return histogramBarOption(minuteHistToSegments(hist).map(ctx.expand), ctx.chartTheme, ctx.animation, { longMin: ctx.filters.longMin });
});

/* ---------- 明细 ---------- */

const detailRows = computed(() =>
  (agg.value?.pairs || []).slice(0, 20).map((p) => ({
    busName: ctx.busLineName(p.busLine),
    metroName: ctx.metroLineName(p.metroLine),
    flow: ctx.expand(p.flow),
    avgSec: p.avgSec,
  })),
);

function exportDetail() {
  const name = selectedName.value;
  const rows = [["站点", "公交线", "地铁线", "换乘人次(扩样)", "平均换乘时间(分)"]];
  (agg.value?.pairs || []).forEach((p) => {
    rows.push([name, ctx.busLineName(p.busLine), ctx.metroLineName(p.metroLine), ctx.expand(p.flow), (p.avgSec / 60).toFixed(1)]);
  });
  ctx.exportCsv(rows, `换乘站点-${name}-明细.csv`);
}
</script>
