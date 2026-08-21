<!-- 线路重复系数（客流走廊监测模块首个子模块）
     地图：模型里被公交线路经过的路网路段（deck.gl LineLayer，rm-corridor-links），无线路经过的路段不画；
     按重复系数五级分色加粗（mapTheme.corridor 固定断点，对齐业务阻抗线样张）；
     双向路网已在后端按无向节点对合并，同一线路上下行走同一路段只计一次。
     右侧：仿真模式展示重复系数排名前十的道路（路网名称字段）+ 系数，真实模式仅展示摘要；
     图例浮在地图左下角（teleport 到 body，结构同客流分析地图图例）。
     系数=经过的不同公交线路数（仅 bus 制式），不涉及人口或客流缩放。 -->
<template>
  <teleport to="#datavisualization_index_box2" defer>
    <div class="cfx-card" aria-label="线路重复系数面板">
      <div class="cfx-title">
        <h2>线路重复系数</h2>
      </div>

      <!-- 状态机：生成中 / 加载 / 失败 整块替换正文，避免状态浮在 0 值上 -->
      <div v-if="status === 'generating'" class="cfx-status" role="status">
        <span class="cfx-status-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="9"></circle>
            <polyline points="12 7 12 12 15.5 14"></polyline>
          </svg>
        </span>
        <p class="cfx-status-title">走廊分析缓存生成中</p>
        <p class="cfx-status-desc">后端正在为当前模型统计公交线路对路网的覆盖，就绪后将自动展示。</p>
      </div>

      <div v-else-if="status === 'error'" class="cfx-status" role="alert">
        <span class="cfx-status-icon is-error" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">
            <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path>
            <line x1="12" y1="9" x2="12" y2="13"></line>
            <line x1="12" y1="17" x2="12.01" y2="17"></line>
          </svg>
        </span>
        <p class="cfx-status-title">走廊数据加载失败</p>
        <p class="cfx-status-desc">{{ errorMessage }}</p>
        <button type="button" class="cfx-retry" @click="bootstrap">重新加载</button>
      </div>

      <div v-else-if="status === 'loading'" class="cfx-skeleton" aria-hidden="true">
        <div class="cfx-sk cfx-sk-hero"></div>
        <template v-if="!isRealMode">
          <div class="cfx-sk cfx-sk-row" v-for="n in 8" :key="n"></div>
        </template>
      </div>

      <template v-else>
        <template v-if="!isRealMode">
          <div v-if="!rankRows.length" class="cfx-status" role="status">
            <span class="cfx-status-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
                <path d="M12 21s7-5.2 7-11a7 7 0 1 0-14 0c0 5.8 7 11 7 11Z"></path>
                <circle cx="12" cy="10" r="2.5"></circle>
              </svg>
            </span>
            <p class="cfx-status-title">{{ scopeLabel }}范围内暂无{{ corridorUnitLabel }}</p>
            <p class="cfx-status-desc">该范围内没有可用于排名的公交{{ corridorUnitLabel }}，可切换显示范围。</p>
          </div>

          <div v-else class="cfx-road-rank">
            <div class="cfx-rank-head" aria-hidden="true">
              <span class="cfx-rank-head-name">{{ corridorUnitLabel }}</span>
              <span class="cfx-rank-head-value">重复系数</span>
            </div>
            <ol class="cfx-rank-list">
              <li v-for="row in rankRows" :key="row.nameIdx">
                <button
                  type="button"
                  class="cfx-rank-row"
                  :title="`${row.name}：最高线路重复系数 ${row.coeff}（${scopeLabel}内 ${row.segments} 段公交路段）`"
                  @click="focusRoad(row.nameIdx)"
                >
                  <span class="cfx-rank-main">
                    <span class="cfx-rank-name">{{ row.name }}</span>
                    <span class="cfx-rank-value">{{ row.coeff }}</span>
                  </span>
                  <span class="cfx-rank-bar" aria-hidden="true">
                    <span class="cfx-rank-bar-fill" :style="{ width: row.barWidth }"></span>
                  </span>
                </button>
              </li>
            </ol>
          </div>
        </template>
      </template>
    </div>
  </teleport>

  <!-- 重复系数图例：地图左下角浮动（结构同客流分析地图图例）；pageActive 防止 KeepAlive 切页后残留 -->
  <teleport to="body">
    <div
      v-if="status === 'ready' && pageActive"
      class="cfx-map-legend"
      aria-label="线路重复系数图例"
      title="路段被多少条不同公交线路经过（双向合并，同线上下行只计一次）"
      @click.stop
    >
      <div class="cfx-map-legend-head">
        <span class="cfx-map-legend-title">线路重复系数</span>
      </div>
      <div v-for="item in legendItems" :key="item.label" class="cfx-map-legend-item">
        <span
          class="cfx-map-legend-swatch"
          :style="{ background: item.color, height: `${Math.max(2, Math.min(8, item.width))}px` }"
          aria-hidden="true"
        ></span>
        <span class="cfx-map-legend-label">{{ item.label }}</span>
      </div>
    </div>
  </teleport>
</template>

<script setup>
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, shallowRef, watch, inject, markRaw } from "vue";
import { LineLayer } from "@deck.gl/layers";
import { setSharedDeckLayer, removeSharedDeckLayer } from "../layers/deckOverlayRegistry.js";
import { MAP_THEME } from "@/utils/mapTheme.js";
import {
  getCachedCorridorLinks,
  getCachedCorridorNames,
  getCachedCorridorSummary,
  getModelDerived,
} from "@/utils/modelDataCache.js";
import { useDisplayRangeStore, DISPLAY_RANGE_ALL } from "@/stores/displayRange.js";
import { mercatorToLngLat, densityClassIndex } from "../utils/populationGrid.js";
import { CORRIDOR_U16_SENTINEL, parseCorridorLinks } from "../utils/corridorLinks.js";
import { isRealDatasource } from "@/utils/realPassengerFlow.js";

const props = defineProps({
  model: String,
});

const MapRef = inject("MapRef", ref(null));
const rightPanelRankLimit = inject("rightPanelRankLimit", 10);

const LINKS_LAYER_KEY = "rm-corridor-links";
const GENERATING_POLL_MS = 8000;
const GENERATING_POLL_MAX_ATTEMPTS = 20;

const status = ref("loading"); // loading | generating | error | ready
const errorMessage = ref("");
const summary = shallowRef(null);
const namesPayload = shallowRef(null); // { names:[...], districts:[176] }
const links = shallowRef(null); // parseCorridorLinks 结果（markRaw，系数升序）

const displayRange = useDisplayRangeStore();
const scopeLabel = computed(() => displayRange.selected || DISPLAY_RANGE_ALL);
const isRealMode = computed(() => isRealDatasource(props.model));
const corridorUnitLabel = computed(() => isRealMode.value ? "站间区间" : "道路");

function formatInt(value) {
  if (!Number.isFinite(value)) return "--";
  return Math.round(value).toLocaleString("zh-CN");
}

// ---------------------------------------------------------------------------
// 数据加载：summary（generating 轮询）→ names + links.bin 并行
// ---------------------------------------------------------------------------

let pollTimer = null;
let pollAttempt = 0;
let requestSeq = 0;
const pageActive = ref(true);
let pendingLayerRefresh = false;

function isCanceledRequest(error) {
  return error?.message === "请求已取消"
    || error?.message === "canceled"
    || error?.cause?.message === "canceled"
    || error?.cause?.code === "ERR_CANCELED";
}

function schedulePoll() {
  clearTimeout(pollTimer);
  if (pollAttempt >= GENERATING_POLL_MAX_ATTEMPTS) {
    status.value = "error";
    errorMessage.value = "走廊缓存生成超时，请稍后手动重试";
    return;
  }
  pollAttempt += 1;
  const delay = Math.min(30_000, GENERATING_POLL_MS + (pollAttempt - 1) * 1500);
  pollTimer = setTimeout(() => {
    pollTimer = null;
    if (pageActive.value) bootstrap();
  }, delay);
}

function bootstrap() {
  if (!props.model) return;
  clearTimeout(pollTimer);
  requestSeq += 1;
  const seq = requestSeq;
  const model = props.model;
  if (status.value !== "generating") {
    status.value = "loading";
    pollAttempt = 0;
  }
  errorMessage.value = "";

  getCachedCorridorSummary(model)
    .then((payload) => {
      if (seq !== requestSeq || props.model !== model) return null;
      if (!payload || payload.status === "generating") {
        status.value = "generating";
        schedulePoll();
        return null;
      }
      summary.value = payload;
      const version = String(payload.generatedAt || payload.cacheVersion || "");
      return Promise.all([
        getCachedCorridorNames(model),
        getCachedCorridorLinks(model, version),
      ]).then(([names, linksBuffer]) => {
        if (seq !== requestSeq || props.model !== model) return null;
        if (!names || names.status === "generating" || !linksBuffer) {
          status.value = "generating";
          schedulePoll();
          return null;
        }
        namesPayload.value = names;
        links.value = getModelDerived(model, `corridorLinks@${version}`, () => markRaw(parseCorridorLinks(linksBuffer)));
        pollAttempt = 0;
        status.value = "ready";
        refreshMapLayers();
        return null;
      });
    })
    .catch((error) => {
      if (seq !== requestSeq || props.model !== model || isCanceledRequest(error)) return;
      const message = String(error?.message || "");
      errorMessage.value = message || "走廊数据加载失败";
      status.value = "error";
    });
}

// ---------------------------------------------------------------------------
// 范围过滤（street 列 → districts 数组）与统计
// ---------------------------------------------------------------------------

const districts = computed(() => (Array.isArray(namesPayload.value?.districts) ? namesPayload.value.districts : []));
const roadNames = computed(() => (Array.isArray(namesPayload.value?.names) ? namesPayload.value.names : []));

function segmentInScope(k) {
  if (scopeLabel.value === DISPLAY_RANGE_ALL) return true;
  const streetIdx = links.value.street[k];
  if (streetIdx === CORRIDOR_U16_SENTINEL) return false;
  return districts.value[streetIdx] === scopeLabel.value;
}

/** 当前范围内的段下标（保持 bin 的系数升序 = 绘制序）。 */
const scopeSegmentIndexes = computed(() => {
  const data = links.value;
  if (!data) return [];
  const indexes = [];
  for (let k = 0; k < data.count; k++) {
    if (segmentInScope(k)) indexes.push(k);
  }
  return indexes;
});

const scopeSegmentCount = computed(() => scopeSegmentIndexes.value.length);
const scopeMaxCoeff = computed(() => {
  const data = links.value;
  const indexes = scopeSegmentIndexes.value;
  // bin 系数升序：范围内最后一个即最大
  return indexes.length ? data.coeff[indexes[indexes.length - 1]] : 0;
});

// ---------------------------------------------------------------------------
// Top10 道路榜（按道路名分组取最高系数；无名路段只上图不上榜）
// ---------------------------------------------------------------------------

const roadAggs = computed(() => {
  const data = links.value;
  if (!data) return new Map();
  const byName = new Map(); // nameIdx → { coeff:max, segments }
  for (const k of scopeSegmentIndexes.value) {
    const nameIdx = data.nameIdx[k];
    if (nameIdx === CORRIDOR_U16_SENTINEL) continue;
    const agg = byName.get(nameIdx);
    if (agg) {
      agg.segments += 1;
      if (data.coeff[k] > agg.coeff) agg.coeff = data.coeff[k];
    } else {
      byName.set(nameIdx, { coeff: data.coeff[k], segments: 1 });
    }
  }
  return byName;
});

const namedRoadCount = computed(() => roadAggs.value.size);

const rankRows = computed(() => {
  const rows = [];
  for (const [nameIdx, agg] of roadAggs.value) {
    rows.push({ nameIdx, name: roadNames.value[nameIdx] || `道路${nameIdx}`, coeff: agg.coeff, segments: agg.segments });
  }
  rows.sort((a, b) => b.coeff - a.coeff || b.segments - a.segments || a.name.localeCompare(b.name, "zh-CN"));
  const top = rows.slice(0, rightPanelRankLimit);
  const maxCoeff = top.length ? top[0].coeff : 0;
  return top.map((row) => ({
    ...row,
    barWidth: `${maxCoeff > 0 ? Math.max(2, (row.coeff / maxCoeff) * 100) : 0}%`,
  }));
});

// ---------------------------------------------------------------------------
// 地图图层：每个重复系数等级一个 deck LineLayer。图层按低→高注册，且关闭同平面
// 深度测试，明确使用画家顺序，保证交叉/重叠处高等级颜色始终覆盖低等级。
// ---------------------------------------------------------------------------

const legendItems = computed(() => {
  const theme = MAP_THEME.corridor;
  const labels = ["1 - 2", "3 - 5", "6 - 10", "11 - 15", "> 15"];
  return labels.map((label, index) => ({ label, color: theme.ramp[index], width: theme.widths[index] }));
});

function corridorLayerInstances() {
  const data = links.value;
  const indexes = scopeSegmentIndexes.value;
  if (!data || !indexes.length) return [];
  const theme = MAP_THEME.corridor;
  const rgb = theme.ramp.map((hex) => {
    const value = Number.parseInt(hex.replace("#", ""), 16);
    return [(value >> 16) & 255, (value >> 8) & 255, value & 255];
  });
  const classIndexes = Array.from({ length: rgb.length }, () => []);
  indexes.forEach((k) => {
    const cls = Math.min(densityClassIndex(data.coeff[k], theme.breaks), rgb.length - 1);
    classIndexes[cls].push(k);
  });

  return classIndexes.flatMap((bucket, cls) => {
    const count = bucket.length;
    if (!count) return [];
    const source = new Float64Array(count * 2);
    const target = new Float64Array(count * 2);
    const colors = new Uint8Array(count * 4);
    const widths = new Float32Array(count);
    const [r, g, b] = rgb[cls];
    for (let i = 0; i < count; i++) {
      const k = bucket[i];
      const [lng1, lat1] = mercatorToLngLat(data.x1[k], data.y1[k]);
      const [lng2, lat2] = mercatorToLngLat(data.x2[k], data.y2[k]);
      source[i * 2] = lng1;
      source[i * 2 + 1] = lat1;
      target[i * 2] = lng2;
      target[i * 2 + 1] = lat2;
      colors[i * 4] = r;
      colors[i * 4 + 1] = g;
      colors[i * 4 + 2] = b;
      colors[i * 4 + 3] = theme.alpha;
      widths[i] = theme.widths[cls];
    }
    return [new LineLayer({
      id: `${LINKS_LAYER_KEY}-${cls}`,
      data: {
        length: count,
        attributes: {
          getSourcePosition: { value: source, size: 2 },
          getTargetPosition: { value: target, size: 2 },
          getColor: { value: colors, size: 4 },
          getWidth: { value: widths, size: 1 },
        },
      },
      parameters: { depthTest: false },
      widthUnits: "pixels",
      widthMinPixels: 1,
      pickable: false,
    })];
  });
}

function refreshMapLayers() {
  if (status.value !== "ready") return;
  if (!pageActive.value) {
    // 失活期间不动共享地图，激活时补一次，避免图层漏到其他页面
    pendingLayerRefresh = true;
    return;
  }
  const wrapper = MapRef.value;
  const map = wrapper?.map;
  if (!map || !map.getStyle) return;
  try {
    const layers = corridorLayerInstances();
    if (layers.length) setSharedDeckLayer(wrapper, LINKS_LAYER_KEY, layers, 0);
    else removeSharedDeckLayer(wrapper, LINKS_LAYER_KEY);
    pendingLayerRefresh = false;
  } catch (error) {
    // 地图尚未就绪（样式加载中等）时静默，数据/范围变化会再次触发
    pendingLayerRefresh = true;
    console.warn("[线路重复系数] 图层刷新失败，等待下次触发", error);
  }
}

function removeMapLayers() {
  removeSharedDeckLayer(MapRef.value, LINKS_LAYER_KEY);
}

// 点击道路行 → 地图定位到该道路（范围内该名称全部路段的联合外接框）
function focusRoad(nameIdx) {
  const data = links.value;
  const map = MapRef.value?.map;
  if (!data || !map?.fitBounds) return;
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (const k of scopeSegmentIndexes.value) {
    if (data.nameIdx[k] !== nameIdx) continue;
    minX = Math.min(minX, data.x1[k], data.x2[k]);
    maxX = Math.max(maxX, data.x1[k], data.x2[k]);
    minY = Math.min(minY, data.y1[k], data.y2[k]);
    maxY = Math.max(maxY, data.y1[k], data.y2[k]);
  }
  if (!Number.isFinite(minX)) return;
  map.fitBounds([mercatorToLngLat(minX, minY), mercatorToLngLat(maxX, maxY)],
    { padding: 90, duration: 600, maxZoom: 14.5 });
}

// ---------------------------------------------------------------------------
// 生命周期
// ---------------------------------------------------------------------------

watch(() => displayRange.selected, () => refreshMapLayers());

onMounted(bootstrap);

onActivated(() => {
  pageActive.value = true;
  if (status.value === "generating" && !pollTimer) schedulePoll();
  if (pendingLayerRefresh) refreshMapLayers();
});

onDeactivated(() => {
  pageActive.value = false;
});

onUnmounted(() => {
  requestSeq += 1;
  clearTimeout(pollTimer);
  removeMapLayers();
});
</script>

<style lang="scss" scoped>
/* teleport 节点带本组件 scope，样式自持（同 RKFB/QZDFB/GJOD），视觉语言对齐 index.vue 的 rm- 面板体系 */
.cfx-card {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  width: 100%;
  box-sizing: border-box;
}

.cfx-title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--dm2-space-3, 12px);
  padding: 0 0 10px;
  border-bottom: 1px solid var(--dm2-line-faint);

  h2 {
    margin: 0;
    color: var(--dm2-ink);
    font-size: 18px;
    line-height: 1.18;
    font-weight: 780;
  }
}

.cfx-scope {
  flex-shrink: 0;
  max-width: 108px;
  margin-top: 2px;
  padding: 3px 9px;
  border-radius: 999px;
  border: 1px solid var(--dm2-line-faint);
  color: var(--dm2-muted);
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* —— 主指标 —— */
.cfx-hero {
  margin-top: 14px;
}

.cfx-hero-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.cfx-hero-label {
  color: var(--dm2-muted);
  font-size: 12px;
  font-weight: 700;
}

.cfx-hero-value {
  margin: 4px 0 0;

  strong {
    color: var(--dm2-ink);
    font-size: 30px;
    line-height: 1.1;
    font-weight: 800;
    letter-spacing: -0.01em;
    font-variant-numeric: tabular-nums;
  }

  em {
    margin-left: 5px;
    color: var(--dm2-muted);
    font-size: 12px;
    font-style: normal;
    font-weight: 650;
  }
}

.cfx-hero-sub {
  margin: 5px 0 0;
  color: var(--dm2-muted);
  font-size: 11px;
  font-weight: 640;
  font-variant-numeric: tabular-nums;
}

/* —— 道路榜单 —— */
.cfx-road-rank {
  display: flex;
  flex-direction: column;
  min-height: 0;
  margin-top: 14px;
}

.cfx-rank-head {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 0 2px 6px;
  border-bottom: 1px solid var(--dm2-line-faint);
  color: var(--dm2-muted);
  font-size: 10.5px;
  font-weight: 700;
}

.cfx-rank-head-name {
  flex: 1;
  min-width: 0;
}

.cfx-rank-head-value {
  flex-shrink: 0;
  width: 72px;
  text-align: right;
}

.cfx-rank-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.cfx-rank-row {
  appearance: none;
  display: block;
  width: 100%;
  border: 0;
  padding: 8px 2px 7px;
  background: transparent;
  border-bottom: 1px solid var(--dm2-line-faint);
  text-align: left;
  cursor: pointer;
  transition: background 0.15s ease;

  &:hover {
    background: rgba(0, 113, 227, 0.05);
  }

  &:active {
    transform: translateY(1px);
  }
}

.cfx-rank-main {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.cfx-rank-name {
  flex: 1;
  min-width: 0;
  color: var(--dm2-ink);
  font-size: 12.5px;
  font-weight: 720;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.cfx-rank-value {
  flex-shrink: 0;
  width: 72px;
  text-align: right;
  color: var(--dm2-ink);
  font-size: 12.5px;
  font-weight: 760;
  font-variant-numeric: tabular-nums;
}

.cfx-rank-bar {
  display: block;
  height: 4px;
  margin-top: 6px;
  border-radius: 999px;
  background: rgba(61, 110, 166, 0.12);
  overflow: hidden;
}

.cfx-rank-bar-fill {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: #3d6ea6; /* mapTheme network.line 同源钢青蓝，单色编码数量 */
  transition: width 0.35s ease;
}

.cfx-rank-footnote {
  margin: 8px 2px 0;
  color: var(--dm2-muted);
  font-size: 10.5px;
  line-height: 1.4;
  font-weight: 620;
}

/* —— 地图左下角重复系数图例（与客流分析 map-flow-legend 同结构同定位） —— */
.cfx-map-legend {
  position: fixed;
  left: calc(276px * var(--app-layout-scale, 1));
  bottom: 20px;
  z-index: calc(var(--z-panel, 1300) + 10);
  min-width: 148px;
  max-width: 220px;
  padding: 10px 12px;
  border: 1px solid var(--app-border, rgba(21, 105, 222, 0.16));
  border-radius: 10px;
  background: var(--app-panel-bg, rgba(255, 255, 255, 0.94));
  box-shadow: var(--app-shadow-sm, 0 8px 24px rgba(13, 38, 76, 0.16));
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 11px;
  color: var(--app-ink-soft, #475467);
  /* 拦截点击避免穿透到地图 */
  pointer-events: auto;
}

.cfx-map-legend-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 2px;
}

.cfx-map-legend-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--app-ink, #344054);
}

.cfx-map-legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.cfx-map-legend-swatch {
  width: 22px;
  border-radius: 3px;
  flex: none;
}

.cfx-map-legend-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* —— 状态与骨架 —— */
.cfx-status {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 34px 12px;
  text-align: center;
}

.cfx-status-icon {
  display: inline-flex;
  width: 34px;
  height: 34px;
  margin-bottom: 4px;
  color: var(--dm2-muted);

  svg {
    width: 100%;
    height: 100%;
  }

  &.is-error {
    color: #d9534f;
  }
}

.cfx-status-title {
  margin: 0;
  color: var(--dm2-ink);
  font-size: 13.5px;
  font-weight: 760;
}

.cfx-status-desc {
  margin: 0;
  max-width: 260px;
  color: var(--dm2-muted);
  font-size: 11.5px;
  line-height: 1.5;
  font-weight: 640;
}

.cfx-retry {
  appearance: none;
  margin-top: 10px;
  padding: 6px 16px;
  border: 1px solid var(--dm2-line-faint);
  border-radius: 999px;
  background: #fff;
  color: var(--dm2-accent-strong, #0071e3);
  font-size: 12px;
  font-weight: 720;
  cursor: pointer;

  &:hover {
    border-color: var(--dm2-accent-strong, #0071e3);
  }

  &:active {
    transform: translateY(1px);
  }
}

.cfx-skeleton {
  padding: 14px 0;
}

.cfx-sk {
  border-radius: 8px;
  background: linear-gradient(90deg, rgba(28, 32, 36, 0.05) 25%, rgba(28, 32, 36, 0.1) 42%, rgba(28, 32, 36, 0.05) 60%);
  background-size: 240% 100%;
  animation: cfx-shimmer 1.4s ease-in-out infinite;
}

.cfx-sk-hero {
  height: 78px;
  margin-top: 12px;
}

.cfx-sk-row {
  height: 30px;
  margin-top: 9px;
}

@keyframes cfx-shimmer {
  0% {
    background-position: 130% 0;
  }

  100% {
    background-position: -110% 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .cfx-sk {
    animation: none;
  }

  .cfx-rank-bar-fill {
    transition: none;
  }
}

/* ── 暗色模式（html.dark，跟随底图选择） ── */
html.dark .cfx-rank-row:hover {
  background: rgba(64, 156, 255, 0.09);
}
html.dark .cfx-rank-bar {
  background: rgba(148, 180, 220, 0.16);
}
html.dark .cfx-map-legend {
  /* --app-ink-soft 未定义，浅色落在 fallback #475467，暗色需显式提亮 */
  color: #c2cddd;
}
html.dark .cfx-status-icon.is-error {
  color: #f87171;
}
html.dark .cfx-retry {
  background: #1a2431;
}
html.dark .cfx-sk {
  background: linear-gradient(90deg, rgba(148, 180, 220, 0.08) 25%, rgba(148, 180, 220, 0.14) 42%, rgba(148, 180, 220, 0.08) 60%);
  background-size: 240% 100%;
}
</style>
