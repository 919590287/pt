<!-- 公交OD监测（公交出行监测模块第三子模块）
     地图：整段公交出行 OD 期望线（仿真与真实数据均使用 LineLayer 直线），可切换街道质心 / 栅格中心连线
     （栅格粒度 100m–2km 可调、间隔 100m，由 100m 格对前端聚合成超格）；
     线色/线宽按当前显示流量集合的分位分级（绿→黄→红，参考期望线制图惯例）；
     街道边界/名称标注（maplibre，rm-busod-street-*），行政区模式只显示范围内街道。
     右侧：街道级 OD 对排序榜（有向，**不含同街道内部出行**，用户定版），teleport 到 index.vue 右侧容器；
     图例浮在地图左下角（teleport 到 body，结构同客流分析地图图例）。
     口径：O=整段公交出行首次上车站、D=最终下车站（events 乘车链 30min/800m 全制式；
     与出行分布监测共用 tripends 缓存家族，但出行分布端点自 v4 起已改活动口径，本模块维持站点口径）；
     一律直出已加载模型的原始人次，不做任何数量缩放；地图线为双向合计（自环不画），榜单为有向对。 -->
<template>
  <teleport to="#datavisualization_index_box2" defer>
    <div class="gjod-card" aria-label="客流流向面板">
      <div class="gjod-title">
        <h2>客流流向</h2>
      </div>

      <!-- 状态机：生成中 / 加载 / 失败 整块替换正文，避免状态浮在 0 值上 -->
      <div v-if="status === 'generating'" class="gjod-status" role="status">
        <span class="gjod-status-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="9"></circle>
            <polyline points="12 7 12 12 15.5 14"></polyline>
          </svg>
        </span>
        <p class="gjod-status-title">客流流向缓存生成中</p>
        <p class="gjod-status-desc">后端正在为当前模型聚合整段公交出行 OD，就绪后将自动展示。</p>
      </div>

      <div v-else-if="status === 'error'" class="gjod-status" role="alert">
        <span class="gjod-status-icon is-error" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">
            <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path>
            <line x1="12" y1="9" x2="12" y2="13"></line>
            <line x1="12" y1="17" x2="12.01" y2="17"></line>
          </svg>
        </span>
        <p class="gjod-status-title">客流流向数据加载失败</p>
        <p class="gjod-status-desc">{{ errorMessage }}</p>
        <button type="button" class="gjod-retry" @click="bootstrap">重新加载</button>
      </div>

      <div v-else-if="status === 'loading'" class="gjod-skeleton" aria-hidden="true">
        <div class="gjod-sk gjod-sk-segment"></div>
        <div class="gjod-sk gjod-sk-hero"></div>
        <div class="gjod-sk gjod-sk-row" v-for="n in 6" :key="n"></div>
      </div>

      <template v-else>
        <div class="gjod-granularity-switch" role="group" aria-label="OD 连线粒度切换">
          <button
            v-for="option in GRANULARITY_OPTIONS"
            :key="option.key"
            type="button"
            :class="['gjod-granularity-btn', { active: granularity === option.key }]"
            :aria-pressed="granularity === option.key"
            @click="granularity = option.key"
          >
            {{ option.label }}
          </button>
        </div>

        <div v-if="granularity === 'grid'" class="gjod-cell-size" aria-label="栅格边长调节">
          <span class="gjod-cell-size-label">栅格边长</span>
          <el-slider
            v-model="gridCellSizeM"
            class="gjod-cell-size-slider"
            :min="100"
            :max="2000"
            :step="100"
            :format-tooltip="(v) => `${v} m`"
          />
          <span class="gjod-cell-size-value">{{ gridCellSizeM }} m</span>
        </div>

        <div class="gjod-hero">
          <p class="gjod-hero-value">
            <strong>{{ formatInt(scopeTotal) }}</strong>
            <em>人次</em>
          </p>
        </div>

        <div v-if="!rankRows.length" class="gjod-status" role="status">
          <span class="gjod-status-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 21s7-5.2 7-11a7 7 0 1 0-14 0c0 5.8 7 11 7 11Z"></path>
              <circle cx="12" cy="10" r="2.5"></circle>
            </svg>
          </span>
          <p class="gjod-status-title">{{ scopeLabel }}范围内暂无公交出行OD</p>
          <p class="gjod-status-desc">当前模型在该范围内没有两端都落在街道内的整段公交出行，可切换显示范围。</p>
        </div>

        <div v-else class="gjod-od-rank">
          <div class="gjod-rank-head" aria-hidden="true">
            <span class="gjod-rank-head-name">出行OD对（街道）</span>
            <span class="gjod-rank-head-value">人次</span>
            <span class="gjod-rank-head-share">占比</span>
          </div>
          <ol class="gjod-rank-list">
            <li v-for="row in visibleRankRows" :key="`${row.o}-${row.d}`">
              <button
                type="button"
                class="gjod-rank-row"
                :title="rowTooltip(row)"
                @click="focusPair(row)"
              >
                <span class="gjod-rank-main">
                  <span class="gjod-rank-name">
                    {{ row.oName }}<i class="gjod-rank-arrow" aria-hidden="true">→</i>{{ row.dName }}
                  </span>
                  <span class="gjod-rank-value">{{ formatInt(row.n) }}</span>
                  <span class="gjod-rank-share">{{ row.shareText }}</span>
                </span>
                <span class="gjod-rank-bar" aria-hidden="true">
                  <span class="gjod-rank-bar-fill" :style="{ width: row.barWidth }"></span>
                </span>
              </button>
            </li>
          </ol>
        </div>
      </template>
    </div>
  </teleport>

  <!-- OD 流量分级图例：地图左下角浮动（结构同客流分析地图图例）；pageActive 防止 KeepAlive 切页后残留 -->
  <teleport to="body">
    <div
      v-if="status === 'ready' && pageActive && legendItems.length"
      class="gjod-map-legend"
      aria-label="公交OD流量图例（人次）"
      :title="granularity === 'street' ? '按街道质心连线，线量为双向合计' : `按 ${gridCellSizeM}m 栅格中心连线，线量为双向合计`"
      @click.stop
    >
      <div class="gjod-map-legend-head">
        <span class="gjod-map-legend-title">公交OD（人次）</span>
      </div>
      <div v-for="item in legendItems" :key="item.label" class="gjod-map-legend-item">
        <span
          class="gjod-map-legend-swatch"
          :style="{ background: item.color, height: `${Math.max(2, Math.min(8, item.width))}px` }"
          aria-hidden="true"
        ></span>
        <span class="gjod-map-legend-label">{{ item.label }}</span>
      </div>
      <p v-if="granularity === 'grid'" class="gjod-map-legend-note">
        100m–2km 各栅格档位均按双向合计流量排序，最多显示前 {{ formatInt(GRID_RENDER_LIMIT) }} 条 OD 连线。
      </p>
    </div>
  </teleport>
</template>

<script setup>
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, shallowRef, watch, inject, markRaw } from "vue";
import { LineLayer } from "@deck.gl/layers";
import { setSharedDeckLayer, removeSharedDeckLayer } from "../layers/deckOverlayRegistry.js";
import { MAP_THEME } from "@/utils/mapTheme.js";
import {
  getCachedTripEndsOdGrid,
  getCachedTripEndsOdStreets,
  getCachedTripEndsStreets,
  getCachedTripEndsSummary,
  getModelDerived,
} from "@/utils/modelDataCache.js";
import { fetchStreetsGeojsonOnce, streetCentroidsByCode } from "../utils/streetsGeojson.js";
import { useDisplayRangeStore, DISPLAY_RANGE_ALL } from "@/stores/displayRange.js";
import { mercatorToLngLat, densityClassIndex, buildDensityLegendItems } from "../utils/populationGrid.js";
import { OD_STREET_UNASSIGNED, parseBusOdGrid, quantileBreaks } from "../utils/busOdGrid.js";

const props = defineProps({
  model: String,
});

const MapRef = inject("MapRef", ref(null));
const rightPanelRankLimit = inject("rightPanelRankLimit", 10);
const GRANULARITY_OPTIONS = [
  { key: "street", label: "街道" },
  { key: "grid", label: "栅格" },
];
const OD_LINE_LAYER_KEY = "rm-busod-lines";
const STREET_SOURCE_ID = "rm-busod-streets";
const STREET_LINE_ID = "rm-busod-street-line";
const STREET_LABEL_ID = "rm-busod-street-label";
/** 100m–2km 各栅格档位的连线渲染上限：双向合并后按流量降序取 Top 1000。 */
const GRID_RENDER_LIMIT = 1000;
const GENERATING_POLL_MS = 8000;

const status = ref("loading"); // loading | generating | error | ready
const errorMessage = ref("");
const granularity = ref("street"); // street | grid
// 栅格连线边长（米）：100m–2km、间隔 100m；由 100m 格对聚合成 N×100m 超格
const gridCellSizeM = ref(100);
const summary = shallowRef(null);
const odStreets = shallowRef(null); // { pairs:[[o,d,n]...], totals }（o/d=街道要素索引，n 为模型原始人次）
const streetStats = shallowRef(null); // tripends-streets.json（index 对齐的街道元信息来源）
const odGrid = shallowRef(null); // parseBusOdGrid 结果（markRaw）
const streetsGeojson = shallowRef(null);

const displayRange = useDisplayRangeStore();
const scopeLabel = computed(() => displayRange.selected || DISPLAY_RANGE_ALL);

function formatInt(value) {
  if (!Number.isFinite(value)) return "--";
  return Math.round(value).toLocaleString("zh-CN");
}

// ---------------------------------------------------------------------------
// 数据加载：summary（generating 轮询）→ od-streets + streets + od-grid.bin + 街道面 并行
// ---------------------------------------------------------------------------

let pollTimer = null;
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
  pollTimer = setTimeout(() => {
    if (pageActive.value) bootstrap();
    else schedulePoll(); // 页面失活期间不发请求，激活后由轮询补上
  }, GENERATING_POLL_MS);
}

function bootstrap() {
  if (!props.model) return;
  clearTimeout(pollTimer);
  requestSeq += 1;
  const seq = requestSeq;
  const model = props.model;
  if (status.value !== "generating") status.value = "loading";
  errorMessage.value = "";

  getCachedTripEndsSummary(model)
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
        getCachedTripEndsOdStreets(model),
        getCachedTripEndsStreets(model),
        getCachedTripEndsOdGrid(model, version),
        fetchStreetsGeojsonOnce(),
      ]).then(([odPayload, streetsPayload, odGridBuffer, geojson]) => {
        if (seq !== requestSeq || props.model !== model) return null;
        if (!odPayload || odPayload.status === "generating"
          || !streetsPayload || streetsPayload.status === "generating" || !odGridBuffer) {
          status.value = "generating";
          schedulePoll();
          return null;
        }
        odStreets.value = odPayload;
        streetStats.value = streetsPayload;
        odGrid.value = getModelDerived(model, "busOdGrid", () => markRaw(parseBusOdGrid(odGridBuffer)));
        streetsGeojson.value = geojson;
        status.value = "ready";
        refreshMapLayers();
        return null;
      });
    })
    .catch((error) => {
      if (seq !== requestSeq || props.model !== model || isCanceledRequest(error)) return;
      const message = String(error?.message || "");
      if (/超时|网关|服务|服务器|连接|Network|timeout|temporar/i.test(message)) {
        status.value = "generating";
        schedulePoll();
        return;
      }
      errorMessage.value = message || "公交OD数据加载失败";
      status.value = "error";
    });
}

// ---------------------------------------------------------------------------
// 街道元信息与范围过滤（o/d 均为街道要素索引 = tripends-streets.json 行序）
// ---------------------------------------------------------------------------

const streetMeta = computed(() => {
  const streets = streetStats.value?.streets;
  return Array.isArray(streets) ? streets : [];
});

function streetInScope(idx) {
  if (scopeLabel.value === DISPLAY_RANGE_ALL) return true;
  return streetMeta.value[idx]?.district === scopeLabel.value;
}

// ---------------------------------------------------------------------------
// 街道 OD 榜单（有向；**同街道内部出行 o==d 不计入**，用户定版；数值为模型原始人次）
// ---------------------------------------------------------------------------

const displayedPairs = computed(() => {
  const pairs = odStreets.value?.pairs;
  if (!Array.isArray(pairs) || !streetMeta.value.length) return [];
  // 后端已按 n 降序；范围过滤（两端都在范围内）保持有序
  const rows = [];
  for (const pair of pairs) {
    const [o, d, n] = pair;
    if (o === d) continue; // 同街道内部出行不计算、不上榜
    if (!streetInScope(o) || !streetInScope(d)) continue;
    rows.push({ o, d, n });
  }
  return rows;
});

const scopeTotal = computed(() => displayedPairs.value.reduce((sum, row) => sum + row.n, 0));

const rankRows = computed(() => {
  const total = scopeTotal.value;
  return displayedPairs.value.map((row) => {
    const share = total > 0 ? row.n / total : 0;
    return {
      ...row,
      oName: streetMeta.value[row.o]?.name || `街道${row.o}`,
      dName: streetMeta.value[row.d]?.name || `街道${row.d}`,
      share,
      shareText: `${(share * 100).toFixed(share >= 0.095 ? 0 : 1)}%`,
    };
  });
});

const visibleRankRows = computed(() => {
  const rows = rankRows.value.slice(0, rightPanelRankLimit);
  const maxValue = rows.length ? rows[0].n : 0;
  return rows.map((row) => ({
    ...row,
    barWidth: `${maxValue > 0 ? Math.max(2, (row.n / maxValue) * 100) : 0}%`,
  }));
});

function rowTooltip(row) {
  const oDistrict = streetMeta.value[row.o]?.district || "";
  const dDistrict = streetMeta.value[row.d]?.district || "";
  return `${row.oName}（${oDistrict}）→ ${row.dName}（${dDistrict}）：`
    + `${formatInt(row.n)} 人次，占${scopeLabel.value} ${row.shareText}`;
}

// ---------------------------------------------------------------------------
// OD 期望线（地图为双向合计；自环无法成线，不画，仅在榜单呈现）
// ---------------------------------------------------------------------------

const streetCentroids = computed(() => streetCentroidsByCode(streetsGeojson.value));

/** 街道模式连线：displayedPairs 双向合并（key=无序街道对），锚点取街道质心。 */
const streetLines = computed(() => {
  if (!streetMeta.value.length) return [];
  const merged = new Map();
  for (const { o, d, n } of displayedPairs.value) {
    if (o === d) continue;
    const key = o < d ? o * 4096 + d : d * 4096 + o;
    merged.set(key, (merged.get(key) || 0) + n);
  }
  const centroids = streetCentroids.value;
  const lines = [];
  for (const [key, flow] of merged) {
    const a = Math.floor(key / 4096);
    const b = key % 4096;
    const from = centroids.get(String(streetMeta.value[a]?.code));
    const to = centroids.get(String(streetMeta.value[b]?.code));
    if (!from || !to) continue;
    lines.push({ from, to, flow });
  }
  return lines;
});

/**
 * 栅格模式连线：PGOD 记录范围过滤 → 聚合到 N×100m 超格（N=gridCellSizeM/100，
 * 超格索引 = floor(格索引/N)，纯整数运算无需重新分箱）→ 双向合并 → 流量 Top-K，锚点取超格中心。
 * 同超格自环不成线（含原 100m 自环）。注意：后端 PGOD 截断保留的是 100m 粒度的流量 Top 对，
 * 粗粒度视图由其聚合而来，droppedPairs>0 时低量长尾略有低估（summary 有披露）。
 */
const gridLineState = computed(() => {
  const grid = odGrid.value;
  if (!grid || !grid.count) return { lines: [] };
  const scoped = scopeLabel.value !== DISPLAY_RANGE_ALL;
  const n = Math.max(1, Math.round(gridCellSizeM.value / 100));
  const merged = new Map();
  for (let k = 0; k < grid.count; k++) {
    if (scoped) {
      const oIdx = grid.oStreet[k];
      const dIdx = grid.dStreet[k];
      if (oIdx === OD_STREET_UNASSIGNED || dIdx === OD_STREET_UNASSIGNED) continue;
      if (!streetInScope(oIdx) || !streetInScope(dIdx)) continue;
    }
    const iO = Math.floor(grid.iO[k] / n);
    const jO = Math.floor(grid.jO[k] / n);
    const iD = Math.floor(grid.iD[k] / n);
    const jD = Math.floor(grid.jD[k] / n);
    if (iO === iD && jO === jD) continue; // 同（超）格自环不成线
    // 无序格对键：按 (i,j) 字典序取正规方向
    const forward = iO < iD || (iO === iD && jO <= jD);
    const key = forward ? `${iO},${jO},${iD},${jD}` : `${iD},${jD},${iO},${jO}`;
    const entry = merged.get(key);
    if (entry) entry.flow += grid.n[k];
    else merged.set(key, { iA: forward ? iO : iD, jA: forward ? jO : jD, iB: forward ? iD : iO, jB: forward ? jD : jO, flow: grid.n[k] });
  }
  const all = Array.from(merged.values());
  all.sort((a, b) => b.flow - a.flow);
  const cs = grid.mercCellSize * n;
  const lines = all.slice(0, GRID_RENDER_LIMIT).map((entry) => ({
    from: mercatorToLngLat((entry.iA + 0.5) * cs, (entry.jA + 0.5) * cs),
    to: mercatorToLngLat((entry.iB + 0.5) * cs, (entry.jB + 0.5) * cs),
    flow: entry.flow,
  }));
  return { lines };
});

const activeLines = computed(() => (granularity.value === "street" ? streetLines.value : gridLineState.value.lines));

/** 分位分级：断点随当前显示流量集合自适应；级数不足时取色带/线宽尾部（保留红端）。 */
const odClasses = computed(() => {
  const theme = MAP_THEME.busOd;
  const breaks = quantileBreaks(activeLines.value.map((line) => line.flow));
  const levels = breaks.length + 1;
  return {
    breaks,
    ramp: theme.ramp.slice(theme.ramp.length - levels),
    widths: theme.widths.slice(theme.widths.length - levels),
  };
});

const legendItems = computed(() => {
  if (!activeLines.value.length) return [];
  const { breaks, ramp, widths } = odClasses.value;
  if (!breaks.length) {
    return [{ color: ramp[0], width: widths[0], label: "全部流量" }];
  }
  return buildDensityLegendItems(breaks, ramp, (v) => formatInt(v))
    .map((item, index) => ({ ...item, width: widths[index] }));
});

// ---------------------------------------------------------------------------
// 地图图层：deck OD 线 + maplibre 街道边界/标注
// ---------------------------------------------------------------------------

function odLineLayerInstance() {
  const lines = activeLines.value;
  if (!lines.length) return null;
  const { breaks, ramp, widths } = odClasses.value;
  const theme = MAP_THEME.busOd;
  const rgb = ramp.map((hex) => {
    const value = Number.parseInt(hex.replace("#", ""), 16);
    return [(value >> 16) & 255, (value >> 8) & 255, value & 255];
  });
  // 流量升序绘制：主走廊后画压在细线之上（deck 按数据顺序渲染）
  const ordered = [...lines].sort((a, b) => a.flow - b.flow);

  // 仿真与真实模式共用同一组直线几何、分级色带和线宽。
  const count = ordered.length;
  const source = new Float64Array(count * 2);
  const target = new Float64Array(count * 2);
  const colors = new Uint8Array(count * 4);
  const lineWidths = new Float32Array(count);
  for (let k = 0; k < count; k++) {
    const line = ordered[k];
    source[k * 2] = line.from[0];
    source[k * 2 + 1] = line.from[1];
    target[k * 2] = line.to[0];
    target[k * 2 + 1] = line.to[1];
    const cls = densityClassIndex(line.flow, breaks);
    const [r, g, b] = rgb[Math.min(cls, rgb.length - 1)];
    colors[k * 4] = r;
    colors[k * 4 + 1] = g;
    colors[k * 4 + 2] = b;
    colors[k * 4 + 3] = theme.alpha;
    lineWidths[k] = widths[Math.min(cls, widths.length - 1)];
  }
  return new LineLayer({
    id: OD_LINE_LAYER_KEY,
    beforeId: STREET_LABEL_ID, // OD 线压在街道标注之下、边界之上
    data: {
      length: count,
      attributes: {
        getSourcePosition: { value: source, size: 2 },
        getTargetPosition: { value: target, size: 2 },
        getColor: { value: colors, size: 4 },
        getWidth: { value: lineWidths, size: 1 },
      },
    },
    widthUnits: "pixels",
    widthMinPixels: 1,
    pickable: false,
  });
}

// 街道 FeatureCollection 附加展示属性（标注仅街道名）。
// 行政区模式：区外街道轮廓/名称整体不下发（用户定版：只显示范围内部）。
function decoratedStreetsGeojson() {
  const fc = streetsGeojson.value;
  if (!fc) return null;
  const scope = scopeLabel.value;
  const features = [];
  for (const feature of fc.features) {
    const propsIn = feature.properties || {};
    if (scope !== DISPLAY_RANGE_ALL && propsIn.district !== scope) continue;
    features.push({
      ...feature,
      properties: { ...propsIn, inScope: 1, label: String(propsIn.name || "") },
    });
  }
  return { type: "FeatureCollection", features };
}

function ensureStreetLayers(map) {
  const theme = MAP_THEME.busOd;
  if (!map.getSource(STREET_SOURCE_ID)) {
    map.addSource(STREET_SOURCE_ID, { type: "geojson", data: { type: "FeatureCollection", features: [] } });
  }
  if (!map.getLayer(STREET_LINE_ID)) {
    map.addLayer({
      id: STREET_LINE_ID,
      type: "line",
      source: STREET_SOURCE_ID,
      paint: {
        "line-color": theme.streetLine,
        "line-width": ["interpolate", ["linear"], ["zoom"], 9, 1, 12, 1.8],
        "line-opacity": ["case", ["==", ["get", "inScope"], 1], 0.88, 0.3],
      },
    });
  }
  if (!map.getLayer(STREET_LABEL_ID)) {
    map.addLayer({
      id: STREET_LABEL_ID,
      type: "symbol",
      source: STREET_SOURCE_ID,
      layout: {
        "text-field": ["get", "label"],
        "text-size": ["interpolate", ["linear"], ["zoom"], 9, 11, 13, 13.5],
        "text-line-height": 1.25,
        "text-max-width": 8,
      },
      paint: {
        "text-color": theme.streetLabel,
        "text-halo-color": theme.streetLabelHalo,
        "text-halo-width": 1.4,
        "text-opacity": ["case", ["==", ["get", "inScope"], 1], 1, 0.55],
      },
    });
  }
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
  const geojson = decoratedStreetsGeojson();
  if (!geojson) return;
  try {
    ensureStreetLayers(map);
    map.getSource(STREET_SOURCE_ID)?.setData(geojson);
    const layer = odLineLayerInstance();
    if (layer) setSharedDeckLayer(wrapper, OD_LINE_LAYER_KEY, layer, 0);
    else removeSharedDeckLayer(wrapper, OD_LINE_LAYER_KEY);
    pendingLayerRefresh = false;
  } catch (error) {
    // 地图尚未就绪（样式加载中等）时静默，数据/粒度变化会再次触发
    pendingLayerRefresh = true;
    console.warn("[公交OD监测] 图层刷新失败，等待下次触发", error);
  }
}

function removeMapLayers() {
  const wrapper = MapRef.value;
  const map = wrapper?.map;
  removeSharedDeckLayer(wrapper, OD_LINE_LAYER_KEY);
  if (!map || !map.getStyle) return;
  try {
    if (map.getLayer(STREET_LABEL_ID)) map.removeLayer(STREET_LABEL_ID);
    if (map.getLayer(STREET_LINE_ID)) map.removeLayer(STREET_LINE_ID);
    if (map.getSource(STREET_SOURCE_ID)) map.removeSource(STREET_SOURCE_ID);
  } catch (error) {
    console.warn("[公交OD监测] 图层清理失败", error);
  }
}

// 点击榜单行 → 地图定位到 O/D 两街道的联合范围
const streetBoundsCache = new Map();
function streetBounds(code) {
  if (streetBoundsCache.has(code)) return streetBoundsCache.get(code);
  const feature = streetsGeojson.value?.features?.find((f) => String(f.properties?.code || f.id) === String(code));
  if (!feature) return null;
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  const scanRing = (ring) => {
    for (const pt of ring) {
      if (pt[0] < minX) minX = pt[0];
      if (pt[0] > maxX) maxX = pt[0];
      if (pt[1] < minY) minY = pt[1];
      if (pt[1] > maxY) maxY = pt[1];
    }
  };
  const geometry = feature.geometry || {};
  if (geometry.type === "Polygon") geometry.coordinates.forEach(scanRing);
  else if (geometry.type === "MultiPolygon") geometry.coordinates.forEach((poly) => poly.forEach(scanRing));
  const bounds = Number.isFinite(minX) ? [[minX, minY], [maxX, maxY]] : null;
  streetBoundsCache.set(code, bounds);
  return bounds;
}

function focusPair(row) {
  const boundsO = streetBounds(String(streetMeta.value[row.o]?.code));
  const boundsD = streetBounds(String(streetMeta.value[row.d]?.code));
  const map = MapRef.value?.map;
  if (!map?.fitBounds) return;
  const parts = [boundsO, boundsD].filter(Boolean);
  if (!parts.length) return;
  const union = parts.reduce((acc, b) => [
    [Math.min(acc[0][0], b[0][0]), Math.min(acc[0][1], b[0][1])],
    [Math.max(acc[1][0], b[1][0]), Math.max(acc[1][1], b[1][1])],
  ]);
  map.fitBounds(union, { padding: 90, duration: 600, maxZoom: 13.5 });
}

// ---------------------------------------------------------------------------
// 生命周期
// ---------------------------------------------------------------------------

watch([granularity, gridCellSizeM], () => refreshMapLayers());
watch(() => displayRange.selected, () => refreshMapLayers());

onMounted(bootstrap);

onActivated(() => {
  pageActive.value = true;
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
/* teleport 节点带本组件 scope，样式自持（同 RKFB/QZDFB），视觉语言对齐 index.vue 的 rm- 面板体系 */
.gjod-card {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  width: 100%;
  box-sizing: border-box;
}

.gjod-title {
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

.gjod-scope {
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

/* —— 粒度切换 —— */
.gjod-granularity-switch {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  margin-top: 12px;
  padding: 3px;
  border-radius: 10px;
  background: rgba(28, 32, 36, 0.05);
}

.gjod-granularity-btn {
  appearance: none;
  border: 0;
  border-radius: 8px;
  padding: 7px 0;
  background: transparent;
  color: var(--dm2-muted);
  font-size: 12.5px;
  font-weight: 720;
  cursor: pointer;
  transition: background 0.18s ease, color 0.18s ease;

  &:hover {
    color: var(--dm2-ink);
  }

  &.active {
    background: #fff;
    color: var(--dm2-accent-strong, #0071e3);
    box-shadow: 0 1px 4px rgba(28, 32, 36, 0.12);
  }

  &:active {
    transform: scale(0.98);
  }
}

/* —— 栅格边长调节（100m–2km，间隔 100m） —— */
.gjod-cell-size {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  padding: 0 2px;
}

.gjod-cell-size-label {
  flex-shrink: 0;
  color: var(--dm2-muted);
  font-size: 11px;
  font-weight: 680;
}

.gjod-cell-size-slider {
  flex: 1;
  min-width: 0;

  :deep(.el-slider__runway) {
    height: 4px;
  }

  :deep(.el-slider__bar) {
    height: 4px;
  }

  :deep(.el-slider__button) {
    width: 12px;
    height: 12px;
  }
}

.gjod-cell-size-value {
  flex-shrink: 0;
  width: 52px;
  text-align: right;
  color: var(--dm2-ink);
  font-size: 11.5px;
  font-weight: 720;
  font-variant-numeric: tabular-nums;
}

/* —— 主指标 —— */
.gjod-hero {
  margin-top: 14px;
}

.gjod-hero-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.gjod-hero-label {
  color: var(--dm2-muted);
  font-size: 12px;
  font-weight: 700;
}

.gjod-hero-value {
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

/* —— OD 排序榜 —— */
.gjod-od-rank {
  display: flex;
  flex-direction: column;
  min-height: 0;
  margin-top: 14px;
}

.gjod-rank-head {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 0 2px 6px;
  border-bottom: 1px solid var(--dm2-line-faint);
  color: var(--dm2-muted);
  font-size: 10.5px;
  font-weight: 700;
}

.gjod-rank-head-name {
  flex: 1;
  min-width: 0;
}

.gjod-rank-head-value {
  flex-shrink: 0;
  width: 84px;
  text-align: right;
}

.gjod-rank-head-share {
  flex-shrink: 0;
  width: 56px;
  text-align: right;
}

.gjod-rank-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.gjod-rank-row {
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

.gjod-rank-main {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.gjod-rank-name {
  flex: 1;
  min-width: 0;
  color: var(--dm2-ink);
  font-size: 12.5px;
  font-weight: 720;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.gjod-rank-arrow {
  margin: 0 4px;
  color: var(--dm2-muted);
  font-style: normal;
  font-weight: 650;
}

.gjod-rank-value {
  flex-shrink: 0;
  width: 84px;
  text-align: right;
  color: var(--dm2-ink);
  font-size: 12.5px;
  font-weight: 760;
  font-variant-numeric: tabular-nums;
}

.gjod-rank-share {
  flex-shrink: 0;
  width: 56px;
  text-align: right;
  color: var(--dm2-muted);
  font-size: 11.5px;
  font-weight: 680;
  font-variant-numeric: tabular-nums;
}

.gjod-rank-bar {
  display: block;
  height: 4px;
  margin-top: 6px;
  border-radius: 999px;
  background: rgba(61, 110, 166, 0.12);
  overflow: hidden;
}

.gjod-rank-bar-fill {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: #3d6ea6; /* mapTheme network.line 同源钢青蓝，单色编码数量 */
  transition: width 0.35s ease;
}

.gjod-rank-footnote {
  margin: 8px 2px 0;
  color: var(--dm2-muted);
  font-size: 10.5px;
  line-height: 1.4;
  font-weight: 620;
}

/* —— 地图左下角 OD 流量图例（与客流分析 map-flow-legend 同结构同定位） —— */
.gjod-map-legend {
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

.gjod-map-legend-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 2px;
}

.gjod-map-legend-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--app-ink, #344054);
}

.gjod-map-legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.gjod-map-legend-swatch {
  width: 22px;
  border-radius: 3px;
  flex: none;
}

.gjod-map-legend-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.gjod-map-legend-note {
  margin: 2px 0 0;
  color: var(--app-muted, #667085);
  font-size: 10px;
  line-height: 1.35;
}

/* —— 状态与骨架 —— */
.gjod-status {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 34px 12px;
  text-align: center;
}

.gjod-status-icon {
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

.gjod-status-title {
  margin: 0;
  color: var(--dm2-ink);
  font-size: 13.5px;
  font-weight: 760;
}

.gjod-status-desc {
  margin: 0;
  max-width: 260px;
  color: var(--dm2-muted);
  font-size: 11.5px;
  line-height: 1.5;
  font-weight: 640;
}

.gjod-retry {
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

.gjod-skeleton {
  padding: 14px 0;
}

.gjod-sk {
  border-radius: 8px;
  background: linear-gradient(90deg, rgba(28, 32, 36, 0.05) 25%, rgba(28, 32, 36, 0.1) 42%, rgba(28, 32, 36, 0.05) 60%);
  background-size: 240% 100%;
  animation: gjod-shimmer 1.4s ease-in-out infinite;
}

.gjod-sk-segment {
  height: 34px;
}

.gjod-sk-hero {
  height: 62px;
  margin-top: 12px;
}

.gjod-sk-row {
  height: 30px;
  margin-top: 9px;
}

@keyframes gjod-shimmer {
  0% {
    background-position: 130% 0;
  }

  100% {
    background-position: -110% 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .gjod-sk {
    animation: none;
  }

  .gjod-rank-bar-fill {
    transition: none;
  }
}

/* ── 暗色模式（html.dark，跟随底图选择） ── */
html.dark .gjod-granularity-switch {
  background: rgba(148, 180, 220, 0.1);
}
html.dark .gjod-granularity-btn.active {
  background: #1a2431;
  box-shadow: 0 1px 4px rgba(2, 6, 12, 0.32);
}
html.dark .gjod-rank-row:hover {
  background: rgba(64, 156, 255, 0.09);
}
html.dark .gjod-rank-bar {
  background: rgba(148, 180, 220, 0.16);
}
html.dark .gjod-map-legend {
  /* --app-ink-soft 未定义，浅色落在 fallback #475467，暗色需显式提亮 */
  color: #c2cddd;
}
html.dark .gjod-status-icon.is-error {
  color: #f87171;
}
html.dark .gjod-retry {
  background: #1a2431;
}
html.dark .gjod-sk {
  background: linear-gradient(90deg, rgba(148, 180, 220, 0.08) 25%, rgba(148, 180, 220, 0.14) 42%, rgba(148, 180, 220, 0.08) 60%);
  background-size: 240% 100%;
}
</style>
