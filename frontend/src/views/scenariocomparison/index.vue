<!-- 优化评估：左侧三类评估维度（复用 dm-sidebar 骨架），右侧双模型指标对比 -->
<template>
  <div class="opteval-wrapper">
    <!-- 左侧维度导航（复用 tokens.css 的 .dm-sidebar / .sidebar-nav / .nav-item） -->
    <div :class="['dm-sidebar', leftCollapsed ? 'is-collapsed' : '']">
      <div class="sidebar-brand">
        <svg class="brand-icon" viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">
          <path d="M3 3v18h18" />
          <path d="M7 14l3-3 3 3 5-6" />
        </svg>
        <span class="brand-text">优化评估</span>
      </div>

      <nav class="sidebar-nav" aria-label="评估维度导航">
        <div v-for="item in NAV" :key="item.key" class="menu-group">
          <button
            type="button"
            :class="['nav-item', activeEval === item.key ? 'active' : '']"
            :aria-current="activeEval === item.key ? 'page' : undefined"
            @click="activeEval = item.key"
          >
            <span class="nav-icon" v-html="item.icon"></span>
            <span class="nav-label">{{ item.label }}</span>
          </button>
        </div>
      </nav>
      <div class="sidebar-footer"></div>
    </div>
    <button
      type="button"
      :class="['dm-panel-collapse-tab', 'dm-left-collapse-tab', leftCollapsed ? 'is-collapsed' : '']"
      :title="leftCollapsed ? '展开面板' : '收起面板'"
      :aria-pressed="leftCollapsed"
      @click="leftCollapsed = !leftCollapsed"
    >
      <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6" /></svg>
    </button>

    <!-- 右侧对比面板：头部=双模型选择器（右上角），主体=指标对比 -->
    <div :class="['dm-overview-panel', 'opteval-panel', rightCollapsed ? 'is-collapsed' : '']">
      <!-- 头部：标题 + 双模型选择器 -->
      <header class="oe-head">
        <div class="oe-head-row">
          <h2 class="oe-title">{{ activeNav.label }}</h2>
          <span class="oe-scheme">
            <el-select v-model="area" size="small" :loading="schemesLoading" placeholder="研究方案" class="oe-scheme-sel" @change="onAreaChange">
              <el-option v-for="s in schemeList" :key="s" :label="s" :value="s" />
            </el-select>
          </span>
        </div>

        <div class="oe-picker" role="group" aria-label="选择对比的两个模型">
          <div class="oe-pick oe-pick-a">
            <span class="oe-pick-tag">基准 A</span>
            <el-select v-model="modelA" size="small" filterable :loading="modelsLoading" placeholder="选择基准模型" class="oe-pick-sel" popper-class="oe-model-pop">
              <el-option v-for="m in modelOptions" :key="m.name" :label="modelLabel(m)" :value="m.name" :disabled="m.name === modelB">
                <span class="oe-opt"><span class="oe-opt-name">{{ modelLabel(m) }}</span><span v-if="m.tag" :class="['oe-opt-tag', m.tagKind]">{{ m.tag }}</span></span>
              </el-option>
            </el-select>
          </div>
          <button type="button" class="oe-swap" title="交换 A / B" aria-label="交换基准与对比模型" @click="swapModels">
            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="17 1 21 5 17 9" /><path d="M3 11V9a4 4 0 0 1 4-4h14" /><polyline points="7 23 3 19 7 15" /><path d="M21 13v2a4 4 0 0 1-4 4H3" /></svg>
          </button>
          <div class="oe-pick oe-pick-b">
            <span class="oe-pick-tag">对比 B</span>
            <el-select v-model="modelB" size="small" filterable :loading="modelsLoading" placeholder="选择对比模型" class="oe-pick-sel" popper-class="oe-model-pop">
              <el-option v-for="m in modelOptions" :key="m.name" :label="modelLabel(m)" :value="m.name" :disabled="m.name === modelA">
                <span class="oe-opt"><span class="oe-opt-name">{{ modelLabel(m) }}</span><span v-if="m.tag" :class="['oe-opt-tag', m.tagKind]">{{ m.tag }}</span></span>
              </el-option>
            </el-select>
          </div>
        </div>
        <p v-if="pairHint" class="oe-pair-hint">
          <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10 13a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1" /><path d="M14 11a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1" /></svg>
          {{ pairHint }}
        </p>
      </header>

      <!-- 主体 -->
      <el-scrollbar class="oe-body">
        <!-- 总体指标 / 单一线路：双模型指标对比 -->
        <template v-if="activeEval === 'overall' || activeEval === 'route'">
          <div v-if="!modelA || !modelB" class="oe-empty">
            <div class="oe-empty-ic">⇄</div>
            <p class="oe-empty-t">请选择要对比的两个模型</p>
            <p class="oe-empty-s">在上方分别选择「基准 A」与「对比 B」。优化生成的「基线 ↔ 方案」会自动配对。</p>
          </div>

          <template v-else>
            <!-- 概述条 -->
            <div class="oe-summary">
              <div class="oe-sum-cell">
                <span class="oe-sum-dot a"></span>
                <span class="oe-sum-name" :title="labelA">{{ labelA }}</span>
              </div>
              <span class="oe-sum-vs">对比</span>
              <div class="oe-sum-cell">
                <span class="oe-sum-dot b"></span>
                <span class="oe-sum-name" :title="labelB">{{ labelB }}</span>
              </div>
            </div>

            <!-- 单一线路：线路选择器 -->
            <div v-if="activeEval === 'route'" class="oe-line-pick">
              <span class="oe-line-tag">
                <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="5" width="18" height="12" rx="2" /><circle cx="7.5" cy="11" r="1" /><circle cx="16.5" cy="11" r="1" /></svg>
                评估线路
              </span>
              <el-select v-model="selectedLine" size="small" filterable :loading="lineLoading" :placeholder="lineLoading ? '载入两模型共有线路…' : '选择要评估的线路'" class="oe-line-sel" no-data-text="两模型无共有线路">
                <el-option v-for="l in lineList" :key="l.lineId" :label="l.lineName" :value="l.lineId" />
              </el-select>
            </div>

            <!-- 未就绪提示 -->
            <div v-if="notReadyNote" class="oe-note">
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9" /><path d="M12 8v5" /><path d="M12 16h.01" /></svg>
              <span>{{ notReadyNote }}</span>
            </div>

            <!-- 指标对比列表（总体/线路共用） -->
            <div v-if="showMetrics" class="oe-metrics">
              <div v-for="r in rows" :key="r.key" class="oe-metric">
                <div class="oe-metric-head">
                  <div class="oe-metric-name">{{ r.label }}<span class="oe-metric-cal">{{ r.cal }}</span></div>
                  <span v-if="busy" class="oe-delta skeleton-chip"></span>
                  <span v-else-if="r.pct != null" :class="['oe-delta', r.tone]">
                    <svg v-if="r.sign !== 0" viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round">
                      <template v-if="r.sign > 0"><line x1="12" y1="19" x2="12" y2="5" /><polyline points="6 11 12 5 18 11" /></template>
                      <template v-else><line x1="12" y1="5" x2="12" y2="19" /><polyline points="6 13 12 19 18 13" /></template>
                    </svg>
                    {{ fmtPct(r.pct) }}%
                  </span>
                  <span v-else class="oe-delta muted">—</span>
                </div>

                <div class="oe-bars">
                  <div class="oe-bar-row">
                    <span class="oe-bar-tag">A</span>
                    <div class="oe-bar-track"><div class="oe-bar-fill a" :style="{ width: (busy ? 0 : r.aPct) + '%' }"></div></div>
                    <span class="oe-bar-val">
                      <template v-if="busy"><span class="skeleton-num"></span></template>
                      <template v-else-if="r.hasA">{{ fmt(r.a, r.digits) }}<i class="oe-unit">{{ r.unit }}</i></template>
                      <template v-else>暂无</template>
                    </span>
                  </div>
                  <div class="oe-bar-row">
                    <span class="oe-bar-tag b">B</span>
                    <div class="oe-bar-track"><div class="oe-bar-fill b" :style="{ width: (busy ? 0 : r.bPct) + '%' }"></div></div>
                    <span class="oe-bar-val b">
                      <template v-if="busy"><span class="skeleton-num"></span></template>
                      <template v-else-if="r.hasB">{{ fmt(r.b, r.digits) }}<i class="oe-unit">{{ r.unit }}</i></template>
                      <template v-else>暂无</template>
                    </span>
                  </div>
                </div>
                <div v-if="r.sub && !busy" class="oe-metric-sub">{{ r.sub }}</div>
              </div>
            </div>

            <p v-if="showMetrics" class="oe-foot">{{ footNote }}</p>
          </template>
        </template>

        <!-- 出行服务评估：脚手架空态 -->
        <div v-else class="oe-empty tall">
          <div class="oe-empty-ic">{{ activeNav.emoji }}</div>
          <p class="oe-empty-t">{{ activeNav.label }}</p>
          <p class="oe-empty-s">{{ activeNav.hint }}</p>
        </div>
      </el-scrollbar>
    </div>
    <button
      type="button"
      :class="['dm-panel-collapse-tab', 'dm-right-collapse-tab', rightCollapsed ? 'is-collapsed' : '']"
      :title="rightCollapsed ? '展开面板' : '收起面板'"
      :aria-expanded="!rightCollapsed"
      @click="rightCollapsed = !rightCollapsed"
    >
      <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6" /></svg>
    </button>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, onUnmounted, ref, watch } from "vue";
import { getSchemeList, getModelList } from "@/api/scheme.js";
import { dataEvaluation } from "@/api/data.js";
import { getRoutePanel } from "@/api/route.js";
import "../datamanagement/tokens.css";

const MapRef = inject("MapRef", null);

// ---------------- 维度导航 ----------------
const NAV = [
  {
    key: "overall",
    label: "总体指标评估",
    emoji: "📊",
    hint: "",
    icon: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="12" width="4" height="8" rx="1"></rect><rect x="10" y="7" width="4" height="13" rx="1"></rect><rect x="17" y="4" width="4" height="16" rx="1"></rect></svg>',
  },
  {
    key: "route",
    label: "单一线路评估",
    emoji: "🚌",
    hint: "",
    icon: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="5" width="18" height="12" rx="2"></rect><circle cx="7.5" cy="11" r="1.1"></circle><circle cx="16.5" cy="11" r="1.1"></circle><path d="M6 17v2"></path><path d="M18 17v2"></path></svg>',
  },
  {
    key: "service",
    label: "出行服务评估",
    emoji: "🧭",
    hint: "从乘客视角对比可达性、平均候车与换乘、出行时间与公交竞争力。该维度即将上线。",
    icon: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="8" r="3.2"></circle><path d="M5.5 20a6.5 6.5 0 0 1 13 0"></path></svg>',
  },
];
const activeEval = ref("overall");
const activeNav = computed(() => NAV.find((n) => n.key === activeEval.value) || NAV[0]);

// 全网指标（总体）：客流量 / 客流强度 / 配车数 / 线网运营规模
const NET_METRICS = [
  { key: "khl", label: "客流量", cal: "日客运总量", unit: "人次", digits: 0, good: 1 },
  { key: "xlklqd", label: "客流强度", cal: "单位里程日客流", unit: "人次/km", digits: 1, good: 1 },
  { key: "pcs", label: "配车数", cal: "高峰在营标台", unit: "标台", digits: 0, good: -1 },
  { key: "yylc", label: "线网运营规模", cal: "线网运营里程", unit: "km", digits: 1, good: 0, subKey: "xlls", subUnit: "条线路" },
];
// 线路指标（单一线路）：客流量/客流强度/配车数/运营规模/运营成本/满载率
const LINE_METRICS = [
  { key: "khl", label: "客流量", cal: "线路日均客流", unit: "人次", digits: 0, good: 1 },
  { key: "xlklqd", label: "客流强度", cal: "单位里程日客流", unit: "人次/km", digits: 1, good: 1 },
  { key: "pcs", label: "配车数", cal: "高峰在营估算", unit: "标台", digits: 0, good: -1 },
  { key: "yylc", label: "线网运营规模", cal: "日运营车公里", unit: "车公里", digits: 0, good: 0 },
  { key: "cost", label: "运营成本", cal: "按 ¥12/车公里估算", unit: "元/日", digits: 0, good: -1 },
  { key: "mzl", label: "满载率", cal: "上车人次/静态容量", unit: "%", digits: 1, good: 1 },
];
const COST_PER_VEHKM = 12; // 运营成本单价（元/车公里），口径说明在页脚
const OP_SPEED_KMH = 20; // 配车数估算用平均运营速度

// ---------------- 面板折叠 ----------------
const leftCollapsed = ref(false);
const rightCollapsed = ref(false);

// ---------------- 方案 / 模型 ----------------
const schemeList = ref([]);
const schemesLoading = ref(false);
const models = ref([]);
const modelsLoading = ref(false);
const area = ref("");
const modelA = ref("");
const modelB = ref("");

const modelOptions = computed(() =>
  models.value.map((m) => {
    const kind = m.optimization?.kind;
    const tag = kind === "baseline" ? "基线" : kind === "variant" ? "方案" : m.scopeLabel || "";
    const tagKind = kind === "baseline" ? "base" : kind === "variant" ? "variant" : "scope";
    return { ...m, tag, tagKind };
  })
);
const modelMap = computed(() => new Map(models.value.map((m) => [m.name, m])));
function modelLabel(m) {
  return m.displayName || (m.name || "").split("/").pop() || m.name;
}
const labelA = computed(() => (modelMap.value.get(modelA.value) ? modelLabel(modelMap.value.get(modelA.value)) : "基准模型"));
const labelB = computed(() => (modelMap.value.get(modelB.value) ? modelLabel(modelMap.value.get(modelB.value)) : "对比模型"));

const pairHint = computed(() => {
  const a = modelMap.value.get(modelA.value)?.optimization;
  const b = modelMap.value.get(modelB.value)?.optimization;
  if (a && b && a.pairId && a.pairId === b.pairId) {
    const name = b.draftName || a.draftName;
    return `已自动配对：优化方案「${name || a.pairId}」的基线与方案`;
  }
  return "";
});

async function loadSchemes() {
  schemesLoading.value = true;
  try {
    const res = await getSchemeList(undefined, { silentError: true });
    schemeList.value = Array.isArray(res?.data) ? res.data : [];
    if (!area.value && schemeList.value.length) area.value = schemeList.value[0];
  } finally {
    schemesLoading.value = false;
  }
}
async function loadModels() {
  if (!area.value) return;
  modelsLoading.value = true;
  try {
    const res = await getModelList({ schemeName: area.value }, { silentError: true });
    models.value = Array.isArray(res?.data) ? res.data : [];
    autoPair();
  } finally {
    modelsLoading.value = false;
  }
}
function autoPair() {
  const list = models.value;
  const variants = list.filter((m) => m.optimization?.kind === "variant");
  for (const v of variants) {
    const base = list.find((m) => m.optimization?.kind === "baseline" && m.optimization?.pairId === v.optimization?.pairId);
    if (base) {
      modelA.value = base.name;
      modelB.value = v.name;
      return;
    }
  }
  const loaded = list.filter((m) => m.loadStatus);
  const pool = loaded.length >= 2 ? loaded : list;
  modelA.value = pool[0]?.name || "";
  modelB.value = pool[1]?.name || pool[0]?.name || "";
}
function onAreaChange() {
  modelA.value = "";
  modelB.value = "";
  models.value = [];
  lineList.value = [];
  selectedLine.value = "";
  loadModels();
}
function swapModels() {
  const t = modelA.value;
  modelA.value = modelB.value;
  modelB.value = t;
}

// ---------------- 全网指标（总体） ----------------
const evalA = ref(null);
const evalB = ref(null);
const netBusy = computed(() => evalA.value?.loading || evalB.value?.loading);

async function fetchEval(name, target) {
  if (!name) {
    target.value = null;
    return;
  }
  target.value = { loading: true };
  try {
    const res = await dataEvaluation({ datasource: name }, { silentError: true });
    const d = res?.data || {};
    target.value = { loading: false, status: d.status, values: d.values || null };
  } catch (e) {
    target.value = { loading: false, status: "error" };
  }
}
watch(modelA, (v) => fetchEval(v, evalA));
watch(modelB, (v) => fetchEval(v, evalB));
watch([modelA, modelB], drawRegions);

// ---------------- 线路指标（单一线路，数据源=线路客流面板缓存 routePanel） ----------------
const lineList = ref([]); // 两模型共有线路 [{lineId, lineName}]
const lineLoading = ref(false);
const selectedLine = ref("");
const panelA = ref(null); // { status, map: Map<lineId,{lineName, routes:[metrics]}> }
const panelB = ref(null);
const lineBusy = computed(() => lineLoading.value);
let lineSeq = 0;

function buildLineMap(panel) {
  const routes = Array.isArray(panel?.routes) ? panel.routes : Object.values(panel?.routes || {});
  const map = new Map();
  for (const r of routes) {
    if (!r?.lineId || !r?.metrics) continue;
    if (!map.has(r.lineId)) map.set(r.lineId, { lineId: r.lineId, lineName: r.lineName || r.lineId, routes: [] });
    map.get(r.lineId).routes.push(r.metrics);
  }
  return map;
}

async function loadPanels() {
  if (!modelA.value || !modelB.value) return;
  if (!modelMap.value.get(modelA.value)?.loadStatus || !modelMap.value.get(modelB.value)?.loadStatus) {
    panelA.value = { status: "unloaded" };
    panelB.value = { status: "unloaded" };
    lineList.value = [];
    return;
  }
  const seq = ++lineSeq;
  lineLoading.value = true;
  try {
    const [ra, rb] = await Promise.all([
      getRoutePanel({ datasource: modelA.value }, { silentError: true }),
      getRoutePanel({ datasource: modelB.value }, { silentError: true }),
    ]);
    if (seq !== lineSeq) return;
    const da = ra?.data;
    const db = rb?.data;
    panelA.value = { status: da?.status || "error", map: buildLineMap(da) };
    panelB.value = { status: db?.status || "error", map: buildLineMap(db) };
    const common = [...panelA.value.map.values()]
      .filter((l) => panelB.value.map.has(l.lineId))
      .map((l) => ({ lineId: l.lineId, lineName: l.lineName }));
    common.sort((a, b) => String(a.lineName).localeCompare(String(b.lineName), "zh"));
    lineList.value = common;
    if (!selectedLine.value || !common.find((l) => l.lineId === selectedLine.value)) {
      selectedLine.value = common[0]?.lineId || "";
    }
  } catch (e) {
    if (seq === lineSeq) {
      panelA.value = { status: "error" };
      panelB.value = { status: "error" };
      lineList.value = [];
    }
  } finally {
    if (seq === lineSeq) lineLoading.value = false;
  }
}

/** 聚合一条线路（其全部方向）：客流量/客流强度/配车数/运营里程/成本/满载率 */
function aggregateLine(panelMap, lineId) {
  const line = panelMap?.get(lineId);
  if (!line || !line.routes.length) return null;
  let khl = 0;
  let distKm = 0;
  let vehKm = 0;
  let fleet = 0;
  let deps = 0;
  let mzlWeighted = 0;
  for (const m of line.routes) {
    const dist = (Number(m.routeDist) || 0) / 1000; // m -> km
    const pax = Number(m.passenger) || 0;
    const lr = Number(m.loadRate) || 0; // 小数
    const n = Number(m.departures) || 0;
    const first = Number(m.firstTime) || 0;
    const last = Number(m.lastTime) || 0;
    // 配车数（标台）：单程时长 / 发车间隔，与总体"高峰在营"口径一致（估算）
    const headway = n > 1 ? (last - first) / (n - 1) : last - first || 3600;
    const oneWay = dist > 0 ? (dist / OP_SPEED_KMH) * 3600 : 0;
    fleet += headway > 0 ? Math.max(1, Math.ceil(oneWay / headway)) : n;
    khl += pax;
    distKm += dist;
    vehKm += dist * n;
    deps += n;
    mzlWeighted += lr * n;
  }
  return {
    khl,
    xlklqd: distKm > 0 ? khl / distKm : null,
    pcs: fleet || null,
    yylc: vehKm || null,
    cost: vehKm > 0 ? vehKm * COST_PER_VEHKM : null,
    mzl: deps > 0 ? (mzlWeighted / deps) * 100 : null,
  };
}

const lineValuesA = computed(() => (panelA.value?.map ? aggregateLine(panelA.value.map, selectedLine.value) : null));
const lineValuesB = computed(() => (panelB.value?.map ? aggregateLine(panelB.value.map, selectedLine.value) : null));

watch([activeEval, modelA, modelB], () => {
  if (activeEval.value === "route") loadPanels();
});

// ---------------- 统一对比行 ----------------
const busy = computed(() => (activeEval.value === "route" ? lineBusy.value : netBusy.value));
const valuesA = computed(() => (activeEval.value === "route" ? lineValuesA.value : evalA.value?.values) || null);
const valuesB = computed(() => (activeEval.value === "route" ? lineValuesB.value : evalB.value?.values) || null);

function buildRow(m, va, vb) {
  const a = va?.[m.key];
  const b = vb?.[m.key];
  const hasA = a != null && Number.isFinite(Number(a));
  const hasB = b != null && Number.isFinite(Number(b));
  const na = hasA ? Number(a) : null;
  const nb = hasB ? Number(b) : null;
  const pct = hasA && hasB && na !== 0 ? ((nb - na) / Math.abs(na)) * 100 : null;
  const negligible = pct != null && Math.abs(pct) < 0.05;
  const sign = hasA && hasB && !negligible ? Math.sign(nb - na) : 0;
  const tone = m.good === 0 || sign === 0 ? "neutral" : sign === m.good ? "good" : "warn";
  const max = Math.max(Math.abs(na) || 0, Math.abs(nb) || 0) || 1;
  let sub = "";
  if (m.subKey) {
    const sa = va?.[m.subKey];
    const sb = vb?.[m.subKey];
    if (sa != null || sb != null) sub = `${sa == null ? "—" : fmtInt(sa)} → ${sb == null ? "—" : fmtInt(sb)} ${m.subUnit}`;
  }
  if (m.key === "cost" && hasA && hasB && nb < na) sub = `方案较基线节省 ¥${fmtInt(na - nb)}/日`;
  return { ...m, a: na, b: nb, hasA, hasB, pct, sign, tone, sub, aPct: hasA ? (Math.abs(na) / max) * 100 : 0, bPct: hasB ? (Math.abs(nb) / max) * 100 : 0 };
}
const rows = computed(() => {
  const defs = activeEval.value === "route" ? LINE_METRICS : NET_METRICS;
  return defs.map((m) => buildRow(m, valuesA.value, valuesB.value));
});

const showMetrics = computed(() => {
  if (activeEval.value === "route") return Boolean(selectedLine.value) && !notReadyNote.value;
  return !notReadyNote.value;
});
const footNote = computed(() =>
  activeEval.value === "route"
    ? "口径：客流量/满载率来自仿真结果，配车数按 20km/h 估算高峰在营标台，运营成本按 ¥12/车公里估算（含双向合计）。"
    : "口径：客流量/客流强度/配车数取模型仿真结果全网汇总，配车数为高峰同时在营标台数估算。"
);

const notReadyNote = computed(() => {
  if (!modelA.value || !modelB.value) return "";
  if (activeEval.value === "route") {
    const chk = (name, label, panel) => {
      if (!modelMap.value.get(name)?.loadStatus) return `${label} 客流数据未加载，请先在「运行监测」后台加载该模型`;
      if (lineLoading.value) return null;
      if (panel?.status === "generating") return `${label} 客流面板缓存生成中，请稍后`;
      if (panel?.status && panel.status !== "ready") return `${label} 客流面板获取失败，请稍后重试`;
      return null;
    };
    const n = chk(modelA.value, labelA.value, panelA.value) || chk(modelB.value, labelB.value, panelB.value);
    if (n) return n;
    if (!lineLoading.value && lineList.value.length === 0) return "两个模型没有可对比的共有线路";
    return "";
  }
  const bad = (e, name, label) => {
    if (!e || e.loading) return null;
    if (e.status === "ready" && e.values) return null;
    if (!modelMap.value.get(name)?.loadStatus) return `${label} 客流数据未加载，请先在「运行监测」后台加载该模型`;
    if (e.status === "error") return `${label} 指标获取失败，请稍后重试`;
    return `${label} 客流数据仍在生成，请稍后`;
  };
  return bad(evalA.value, modelA.value, labelA.value) || bad(evalB.value, modelB.value, labelB.value) || "";
});

// ---------------- 展示格式 ----------------
function fmt(v, digits) {
  if (v == null) return "—";
  return digits === 0 ? fmtInt(v) : Number(v).toLocaleString("zh-CN", { minimumFractionDigits: digits, maximumFractionDigits: digits });
}
function fmtInt(v) {
  return Math.round(Number(v)).toLocaleString("zh-CN");
}
function fmtPct(p) {
  return Math.abs(p).toFixed(1);
}

// ---------------- 地图：勾勒对比模型的研究区域 ----------------
const SRC = "opteval-region-src";
const FILL = "opteval-region-fill";
const LINE = "opteval-region-line";
function normalizeRing(poly) {
  if (!Array.isArray(poly) || !poly.length) return null;
  let ring = poly;
  if (Array.isArray(poly[0]) && Array.isArray(poly[0][0])) ring = poly[0];
  if (!Array.isArray(ring[0]) || ring[0].length < 2) return null;
  const closed = ring.slice();
  const f = closed[0];
  const l = closed[closed.length - 1];
  if (f[0] !== l[0] || f[1] !== l[1]) closed.push(f);
  return closed.map((p) => [Number(p[0]), Number(p[1])]);
}
function drawRegions() {
  const map = MapRef?.value?.map;
  if (!map || typeof map.addSource !== "function") return;
  const poly = modelMap.value.get(modelB.value)?.optimization?.regionPolygon || modelMap.value.get(modelA.value)?.optimization?.regionPolygon;
  const ring = normalizeRing(poly);
  const run = () => {
    if (!ring) {
      cleanupRegion();
      return;
    }
    const gj = { type: "Feature", geometry: { type: "Polygon", coordinates: [ring] }, properties: {} };
    if (!map.getSource(SRC)) {
      map.addSource(SRC, { type: "geojson", data: gj });
      map.addLayer({ id: FILL, type: "fill", source: SRC, paint: { "fill-color": "#0071e3", "fill-opacity": 0.06 } });
      map.addLayer({ id: LINE, type: "line", source: SRC, paint: { "line-color": "#0071e3", "line-width": 2, "line-dasharray": [3, 2], "line-opacity": 0.7 } });
    } else {
      map.getSource(SRC).setData(gj);
    }
    try {
      const lngs = ring.map((p) => p[0]);
      const lats = ring.map((p) => p[1]);
      map.fitBounds([[Math.min(...lngs), Math.min(...lats)], [Math.max(...lngs), Math.max(...lats)]], { padding: 120, duration: 900, maxZoom: 14 });
    } catch (e) { /* ignore */ }
  };
  const ready = typeof map.isStyleLoaded === "function" ? map.isStyleLoaded() : true;
  if (ready) run();
  else map.once("load", run);
}
function cleanupRegion() {
  const map = MapRef?.value?.map;
  if (!map || typeof map.getLayer !== "function") return;
  if (map.getLayer(LINE)) map.removeLayer(LINE);
  if (map.getLayer(FILL)) map.removeLayer(FILL);
  if (map.getSource(SRC)) map.removeSource(SRC);
}

// ---------------- 生命周期 ----------------
onMounted(async () => {
  await loadSchemes();
  await loadModels();
});
onUnmounted(cleanupRegion);
</script>

<style lang="scss" scoped>
.opteval-wrapper {
  position: relative;
  width: 100%;
  height: 100%;
  pointer-events: none;

  > * { pointer-events: auto; }
}

.sidebar-brand .brand-icon { color: var(--dm2-accent); }

/* ============ 右侧对比面板 ============ */
.opteval-panel {
  display: flex;
  flex-direction: column;
  width: 420px;
  padding: 0;
  overflow: hidden;
}

.oe-head {
  flex-shrink: 0;
  padding: var(--dm2-space-4) var(--dm2-space-4) var(--dm2-space-3);
  border-bottom: 1px solid var(--dm2-line-faint);
  background: linear-gradient(180deg, rgba(0, 113, 227, 0.045), rgba(0, 113, 227, 0));
}

.oe-head-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dm2-space-3);
  margin-bottom: var(--dm2-space-3);

  .oe-title {
    margin: 0;
    font-size: var(--dm2-text-xl);
    font-weight: var(--dm2-fw-bold);
    line-height: 1.2;
    color: var(--dm2-ink);
    letter-spacing: -0.01em;
  }
  .oe-scheme-sel { width: 116px; }
}

.oe-picker {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: end;
  gap: var(--dm2-space-2);
}

.oe-pick {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;

  .oe-pick-tag {
    display: inline-flex;
    align-items: center;
    gap: 5px;
    font-size: var(--dm2-text-xs);
    font-weight: var(--dm2-fw-bold);
    letter-spacing: 0.02em;
  }
  &.oe-pick-a .oe-pick-tag { color: var(--oe-a); }
  &.oe-pick-b .oe-pick-tag { color: var(--dm2-accent); }
  .oe-pick-tag::before { content: ""; width: 8px; height: 8px; border-radius: 3px; }
  &.oe-pick-a .oe-pick-tag::before { background: var(--oe-a); }
  &.oe-pick-b .oe-pick-tag::before { background: var(--dm2-accent); }
  .oe-pick-sel { width: 100%; }
}

.oe-swap {
  flex-shrink: 0;
  width: 30px; height: 30px;
  margin-bottom: 1px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--dm2-line);
  border-radius: var(--dm2-radius-sm);
  background: var(--dm2-surface);
  color: var(--dm2-muted);
  cursor: pointer;
  transition: color var(--dm2-dur-fast) var(--dm2-ease), border-color var(--dm2-dur-fast) var(--dm2-ease), background-color var(--dm2-dur-fast) var(--dm2-ease);

  &:hover { color: var(--dm2-accent); border-color: var(--dm2-accent-ring); background: var(--dm2-accent-weak); }
  &:active { transform: scale(0.94); }
}

.oe-pair-hint {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: var(--dm2-space-2) 0 0;
  font-size: var(--dm2-text-xs);
  color: var(--dm2-accent-strong);
  svg { flex-shrink: 0; opacity: 0.8; }
}

.oe-body {
  flex: 1 1 auto;
  min-height: 0;
  :deep(.el-scrollbar__view) { padding: var(--dm2-space-4); }
}

/* 概述条 */
.oe-summary {
  display: flex;
  align-items: center;
  gap: var(--dm2-space-2);
  padding: var(--dm2-space-2) var(--dm2-space-3);
  margin-bottom: var(--dm2-space-3);
  border: 1px solid var(--dm2-line-faint);
  border-radius: var(--dm2-radius-sm);
  background: var(--dm2-surface-sunken);

  .oe-sum-cell { flex: 1; min-width: 0; display: flex; align-items: center; gap: 6px; }
  .oe-sum-dot { width: 9px; height: 9px; border-radius: 3px; flex-shrink: 0; }
  .oe-sum-dot.a { background: var(--oe-a); }
  .oe-sum-dot.b { background: var(--dm2-accent); }
  .oe-sum-name { min-width: 0; font-size: var(--dm2-text-sm); font-weight: var(--dm2-fw-semibold); color: var(--dm2-ink-soft); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .oe-sum-vs { flex-shrink: 0; font-size: var(--dm2-text-xs); color: var(--dm2-muted-soft); padding: 1px 7px; border-radius: var(--dm2-radius-pill); background: rgba(17, 32, 58, 0.05); }
}

/* 线路选择器 */
.oe-line-pick {
  display: flex;
  align-items: center;
  gap: var(--dm2-space-2);
  padding: var(--dm2-space-2) var(--dm2-space-3);
  margin-bottom: var(--dm2-space-3);
  border: 1px solid var(--dm2-accent-weak);
  border-radius: var(--dm2-radius-sm);
  background: var(--dm2-accent-weak);

  .oe-line-tag {
    flex-shrink: 0;
    display: inline-flex;
    align-items: center;
    gap: 5px;
    font-size: var(--dm2-text-sm);
    font-weight: var(--dm2-fw-bold);
    color: var(--dm2-accent-strong);
  }
  .oe-line-sel { flex: 1; min-width: 0; }
}

.oe-note {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  padding: 8px 10px;
  margin-bottom: var(--dm2-space-3);
  border-radius: var(--dm2-radius-sm);
  background: var(--dm2-modify-weak);
  color: var(--dm2-modify);
  font-size: var(--dm2-text-sm);
  line-height: 1.5;
  svg { flex-shrink: 0; margin-top: 1px; }
}

.oe-metrics { display: flex; flex-direction: column; gap: var(--dm2-space-2); }

.oe-metric {
  padding: var(--dm2-space-3);
  border: 1px solid var(--dm2-line-faint);
  border-radius: var(--dm2-radius);
  background: var(--dm2-surface);
  transition: border-color var(--dm2-dur) var(--dm2-ease), box-shadow var(--dm2-dur) var(--dm2-ease);

  &:hover { border-color: var(--dm2-line); box-shadow: var(--dm2-shadow-card); }
}

.oe-metric-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--dm2-space-2);
  margin-bottom: 10px;

  .oe-metric-name { font-size: var(--dm2-text-md); font-weight: var(--dm2-fw-bold); color: var(--dm2-ink); }
  .oe-metric-cal { margin-left: 7px; font-size: var(--dm2-text-xs); font-weight: var(--dm2-fw-medium); color: var(--dm2-muted-soft); }
}

.oe-delta {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 2px 8px 2px 6px;
  border-radius: var(--dm2-radius-pill);
  font-size: var(--dm2-text-sm);
  font-weight: var(--dm2-fw-bold);
  font-variant-numeric: tabular-nums;
  font-family: var(--dm2-font-num);

  &.good { color: var(--dm2-add); background: var(--dm2-add-weak); }
  &.warn { color: var(--dm2-modify); background: var(--dm2-modify-weak); }
  &.neutral { color: var(--dm2-accent-strong); background: var(--dm2-accent-weak); }
  &.muted { color: var(--dm2-muted-soft); background: transparent; padding: 2px 4px; }
  svg { margin-top: -1px; }
}

.oe-bars { display: flex; flex-direction: column; gap: 7px; }

.oe-bar-row {
  display: grid;
  grid-template-columns: 16px 1fr auto;
  align-items: center;
  gap: 9px;

  .oe-bar-tag { font-size: 10px; font-weight: var(--dm2-fw-bold); color: var(--oe-a); text-align: center; &.b { color: var(--dm2-accent); } }
  .oe-bar-track { height: 9px; border-radius: var(--dm2-radius-pill); background: rgba(17, 32, 58, 0.06); overflow: hidden; }
  .oe-bar-fill {
    height: 100%;
    border-radius: var(--dm2-radius-pill);
    transition: width var(--dm2-dur-slow) var(--dm2-ease-out);
    &.a { background: var(--oe-a); }
    &.b { background: var(--dm2-accent-grad); }
  }
  .oe-bar-val {
    min-width: 62px;
    text-align: right;
    font-size: var(--dm2-text-base);
    font-weight: var(--dm2-fw-bold);
    font-family: var(--dm2-font-num);
    font-variant-numeric: tabular-nums;
    color: var(--oe-a);
    &.b { color: var(--dm2-accent-strong); }
    .oe-unit { margin-left: 2px; font-size: 9px; font-weight: var(--dm2-fw-normal); font-style: normal; color: var(--dm2-muted-soft); }
  }
}

.oe-metric-sub {
  margin-top: 9px;
  padding-top: 8px;
  border-top: 1px dashed var(--dm2-line-faint);
  font-size: var(--dm2-text-xs);
  color: var(--dm2-muted);
  font-variant-numeric: tabular-nums;
}

.oe-foot {
  margin: var(--dm2-space-4) 2px 0;
  font-size: var(--dm2-text-xs);
  line-height: 1.6;
  color: var(--dm2-muted-soft);
}

.oe-empty {
  text-align: center;
  padding: 44px 24px;
  &.tall { padding: 72px 24px; }
  .oe-empty-ic { font-size: 30px; margin-bottom: var(--dm2-space-2); opacity: 0.9; }
  .oe-empty-t { margin: var(--dm2-space-2) 0 var(--dm2-space-1); font-size: var(--dm2-text-lg); font-weight: var(--dm2-fw-bold); color: var(--dm2-ink); }
  .oe-empty-s { margin: 0 auto; max-width: 30ch; font-size: var(--dm2-text-sm); line-height: 1.7; color: var(--dm2-muted); }
}

.skeleton-chip, .skeleton-num {
  display: inline-block;
  border-radius: var(--dm2-radius-pill);
  background: linear-gradient(90deg, rgba(17, 32, 58, 0.06) 25%, rgba(17, 32, 58, 0.1) 37%, rgba(17, 32, 58, 0.06) 63%);
  background-size: 400% 100%;
  animation: oe-shimmer 1.3s ease-in-out infinite;
}
.skeleton-chip { width: 52px; height: 18px; }
.skeleton-num { width: 46px; height: 13px; border-radius: 4px; }
@keyframes oe-shimmer { 0% { background-position: 100% 0; } 100% { background-position: 0 0; } }
@media (prefers-reduced-motion: reduce) {
  .skeleton-chip, .skeleton-num { animation: none; }
  .oe-bar-fill { transition: none; }
}

:global(.oe-model-pop .oe-opt) { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
:global(.oe-model-pop .oe-opt-name) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
:global(.oe-model-pop .oe-opt-tag) { flex-shrink: 0; padding: 0 6px; border-radius: 4px; font-size: 10px; font-weight: 700; }
:global(.oe-model-pop .oe-opt-tag.base) { color: var(--oe-a, #5b6bb5); background: rgba(91, 107, 181, 0.12); }
:global(.oe-model-pop .oe-opt-tag.variant) { color: #0071e3; background: rgba(0, 113, 227, 0.1); }
:global(.oe-model-pop .oe-opt-tag.scope) { color: #667085; background: rgba(17, 32, 58, 0.06); }
</style>

<style>
.opteval-wrapper { --oe-a: #5b6bb5; }
</style>
