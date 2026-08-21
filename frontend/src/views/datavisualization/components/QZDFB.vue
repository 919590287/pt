<!-- 出行分布监测（公交出行监测模块，人口分布监测的同级子模块；原「起终点分布监测」）
     地图：复用人口分布监测相同的 100m 栅格（deck.gl GridCellLayer，rm-tripends-grid）
     + 街道边界/名称占比标注（maplibre，rm-tripends-street-*）。
     右侧：起点/终点切换 + 按街道占比排序榜，teleport 到 index.vue 的右侧容器；
     图例：栅格人次区间图例，浮在地图左下角（teleport 到 body）。
     口径：本次活动出行的起终点——plans 中含 pt leg 的 trip，
     起点=出行前置活动位置、终点=出行后置活动位置；
     grid.bin 复用 PGRD 契约（home 列=起点、work 列=终点）。 -->
<template>
  <teleport to="#datavisualization_index_box2" defer>
    <div class="qzd-card" aria-label="出行分布面板">
      <div class="qzd-title">
        <h2>出行分布</h2>
      </div>

      <!-- 状态机：生成中 / 加载 / 失败 整块替换正文，避免状态浮在 0 值上 -->
      <div v-if="status === 'generating'" class="qzd-status" role="status">
        <span class="qzd-status-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="9"></circle>
            <polyline points="12 7 12 12 15.5 14"></polyline>
          </svg>
        </span>
        <p class="qzd-status-title">出行分布缓存生成中</p>
        <p class="qzd-status-desc">后端正在为当前模型提取公交出行的活动起终点，就绪后将自动展示。</p>
      </div>

      <div v-else-if="status === 'unsupported'" class="qzd-status" role="status">
        <span class="qzd-status-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="9"></circle>
            <line x1="8" y1="12" x2="16" y2="12"></line>
          </svg>
        </span>
        <p class="qzd-status-title">当前模型不支持出行分布</p>
        <p class="qzd-status-desc">{{ errorMessage || "缺少可读取的 plans 出行链，平台不会用空分布或 0 值代替。" }}</p>
      </div>

      <div v-else-if="status === 'error'" class="qzd-status" role="alert">
        <span class="qzd-status-icon is-error" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">
            <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path>
            <line x1="12" y1="9" x2="12" y2="13"></line>
            <line x1="12" y1="17" x2="12.01" y2="17"></line>
          </svg>
        </span>
        <p class="qzd-status-title">出行分布数据加载失败</p>
        <p class="qzd-status-desc">{{ errorMessage }}</p>
        <button type="button" class="qzd-retry" @click="bootstrap">重新加载</button>
      </div>

      <div v-else-if="status === 'loading'" class="qzd-skeleton" aria-hidden="true">
        <div class="qzd-sk qzd-sk-segment"></div>
        <div class="qzd-sk qzd-sk-hero"></div>
        <div class="qzd-sk qzd-sk-row" v-for="n in 6" :key="n"></div>
      </div>

      <template v-else>
        <div class="qzd-metric-switch" role="group" aria-label="起终点指标切换">
          <button
            v-for="option in METRIC_OPTIONS"
            :key="option.key"
            type="button"
            :class="['qzd-metric-btn', { active: metric === option.key }]"
            :aria-pressed="metric === option.key"
            @click="metric = option.key"
          >
            {{ option.label }}
          </button>
        </div>

        <div class="qzd-hero">
          <p class="qzd-hero-value">
            <strong>{{ formatInt(scopeTotal) }}</strong>
            <em>人次</em>
          </p>
        </div>

        <div v-if="!streetRows.length" class="qzd-status" role="status">
          <span class="qzd-status-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 21s7-5.2 7-11a7 7 0 1 0-14 0c0 5.8 7 11 7 11Z"></path>
              <circle cx="12" cy="10" r="2.5"></circle>
            </svg>
          </span>
          <p class="qzd-status-title">{{ scopeLabel }}范围内暂无{{ metricLabel }}</p>
          <p class="qzd-status-desc">当前模型在该范围内没有对应的{{ metricLabel }}，可切换显示范围或指标。</p>
        </div>

        <div v-else class="qzd-street-rank">
          <div class="qzd-rank-head" aria-hidden="true">
            <span class="qzd-rank-head-name">{{ isRealMode ? '站点栅格' : '街道' }}</span>
            <span class="qzd-rank-head-value">人次</span>
            <span class="qzd-rank-head-share">占比</span>
          </div>
          <ol class="qzd-rank-list">
            <li v-for="row in visibleStreetRows" :key="row.code">
              <button
                type="button"
                class="qzd-rank-row"
                :title="`${row.name}（${row.district}）：${metricLabel} ${formatInt(row.value)} 人次，占${scopeLabel} ${row.shareText}`"
                @click="focusStreet(row.code)"
              >
                <span class="qzd-rank-main">
                  <span class="qzd-rank-name">
                    {{ row.name }}
                    <em v-if="showDistrictInRow" class="qzd-rank-district">{{ row.district }}</em>
                  </span>
                  <span class="qzd-rank-value">{{ formatInt(row.value) }}</span>
                  <span class="qzd-rank-share">{{ row.shareText }}</span>
                </span>
                <span class="qzd-rank-bar" aria-hidden="true">
                  <span class="qzd-rank-bar-fill" :style="{ width: row.barWidth }"></span>
                </span>
              </button>
            </li>
          </ol>
        </div>
      </template>
    </div>
  </teleport>

  <!-- 栅格分布图例：地图左下角浮动（复用人口分布 100m 栅格图例结构）；pageActive 防止 KeepAlive 切页后残留 -->
  <teleport to="body">
    <div
      v-if="status === 'ready' && pageActive"
      class="qzd-map-legend"
      aria-label="出行分布栅格图例（人次）"
      @click.stop
    >
      <div class="qzd-map-legend-head">
        <span class="qzd-map-legend-title">{{ metricLabel }}栅格分布（人次）</span>
      </div>
      <div v-for="item in legendItems" :key="item.label" class="qzd-map-legend-item">
        <span class="qzd-map-legend-swatch" :style="{ background: item.color }" aria-hidden="true"></span>
        <span class="qzd-map-legend-label">{{ item.label }}</span>
      </div>
    </div>
  </teleport>
</template>

<script setup>
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, shallowRef, watch, inject, markRaw } from "vue";
import { GridCellLayer } from "@deck.gl/layers";
import { setSharedDeckLayer, removeSharedDeckLayer } from "../layers/deckOverlayRegistry.js";
import { MAP_THEME } from "@/utils/mapTheme.js";
import { isDarkTheme } from "@/utils/uiTheme.js";
import {
  getCachedTripEndsGrid,
  getCachedTripEndsStreets,
  getCachedTripEndsSummary,
  getModelDerived,
} from "@/utils/modelDataCache.js";
import { fetchStreetsGeojsonOnce } from "../utils/streetsGeojson.js";
import { useDisplayRangeStore, DISPLAY_RANGE_ALL } from "@/stores/displayRange.js";
import {
  parsePopulationGrid,
  buildGridPositions,
  buildGridColors,
  buildGridElevations,
  buildDensityLegendItems,
  GRID_STREET_SENTINEL,
  CELL_AREA_KM2,
} from "../utils/populationGrid.js";
import { isRealDatasource } from "@/utils/realPassengerFlow.js";

const props = defineProps({
  model: String,
  threeDimensional: Boolean,
});

const MapRef = inject("MapRef", ref(null));
const rightPanelRankLimit = inject("rightPanelRankLimit", 10);
const isRealMode = computed(() => isRealDatasource(props.model));

const METRIC_OPTIONS = [
  { key: "origin", label: "出行起点" },
  { key: "destination", label: "出行终点" },
];
const GRID_LAYER_KEY = "rm-tripends-grid";
// 3D 柱高：P99 格高 1500m、最矮非零格 200m，平方根压缩高低差（见 buildGridElevations）。
const TRIPENDS_HEIGHT_REFERENCE = 1500;
const TRIPENDS_HEIGHT_MIN = 200;
const STREET_SOURCE_ID = "rm-tripends-streets";
const STREET_LINE_ID = "rm-tripends-street-line";
const STREET_LABEL_ID = "rm-tripends-street-label";
const GENERATING_POLL_MS = 8000;
const GENERATING_POLL_MAX_ATTEMPTS = 20;

const status = ref("loading"); // loading | generating | unsupported | error | ready
const errorMessage = ref("");
const metric = ref("origin");
const summary = shallowRef(null);
const streetStats = shallowRef(null); // { streets:[{code,name,district,areaKm2,origin,destination}], totals }
const grid = shallowRef(null); // parsePopulationGrid 结果（markRaw；home 列=起点、work 列=终点）
const streetsGeojson = shallowRef(null); // 模型无关街道面

const displayRange = useDisplayRangeStore();
const scopeLabel = computed(() => displayRange.selected || DISPLAY_RANGE_ALL);
const metricLabel = computed(() => (metric.value === "origin" ? "出行起点" : "出行终点"));

const legendItems = computed(() =>
  buildDensityLegendItems(
    MAP_THEME.tripEnds.breaks.map((b) => Math.round(b * CELL_AREA_KM2)),
    MAP_THEME.tripEnds.ramp,
    (v) => formatInt(v),
  ),
);

function formatInt(value) {
  if (!Number.isFinite(value)) return "--";
  return Math.round(value).toLocaleString("zh-CN");
}

// ---------------------------------------------------------------------------
// 数据加载：summary（generating 轮询）→ grid.bin + streets + 街道面 并行
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
    errorMessage.value = "出行分布缓存生成超时，请稍后手动重试";
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

  getCachedTripEndsSummary(model)
    .then((payload) => {
      if (seq !== requestSeq || props.model !== model) return null;
      if (!payload || payload.status === "generating") {
        status.value = "generating";
        schedulePoll();
        return null;
      }
      if (payload.status === "unsupported" || payload.status === "nodata") {
        summary.value = payload;
        errorMessage.value = payload.message || payload.reason || "缺少出行分布所需源数据";
        status.value = "unsupported";
        removeMapLayers();
        return null;
      }
      summary.value = payload;
      const version = String(payload.generatedAt || payload.cacheVersion || "");
      return Promise.all([
        getCachedTripEndsGrid(model, version),
        getCachedTripEndsStreets(model),
        fetchStreetsGeojsonOnce(),
      ]).then(([gridBuffer, streetsPayload, geojson]) => {
        if (seq !== requestSeq || props.model !== model) return null;
        if (!gridBuffer || !streetsPayload || streetsPayload.status === "generating") {
          status.value = "generating";
          schedulePoll();
          return null;
        }
        grid.value = getModelDerived(model, `tripEndsGrid@${version}`, () => markRaw(parsePopulationGrid(gridBuffer)));
        pollAttempt = 0;
        streetStats.value = streetsPayload;
        streetsGeojson.value = geojson;
        status.value = "ready";
        refreshMapLayers();
        return null;
      });
    })
    .catch((error) => {
      if (seq !== requestSeq || props.model !== model || isCanceledRequest(error)) return;
      const message = String(error?.message || "");
      errorMessage.value = message || "出行分布数据加载失败";
      status.value = "error";
    });
}

// ---------------------------------------------------------------------------
// 街道占比榜单（随指标 / 显示范围联动；数值为模型原始人次；占比=街道人次/范围合计）
// ---------------------------------------------------------------------------

const streetRows = computed(() => {
  const streets = streetStats.value?.streets;
  if (!Array.isArray(streets)) return [];
  const scope = scopeLabel.value;
  const key = metric.value;
  const rows = [];
  for (const street of streets) {
    if (scope !== DISPLAY_RANGE_ALL && street.district !== scope) continue;
    const value = Number(street[key]) || 0;
    if (!value) continue;
    rows.push({
      code: street.code,
      name: street.name,
      district: street.district,
      value,
    });
  }
  rows.sort((a, b) => b.value - a.value);
  const total = rows.reduce((sum, row) => sum + row.value, 0);
  const maxValue = rows.length ? rows[0].value : 0;
  for (const row of rows) {
    const share = total > 0 ? row.value / total : 0;
    row.share = share;
    row.shareText = `${(share * 100).toFixed(share >= 0.095 ? 0 : 1)}%`;
    row.barWidth = `${maxValue > 0 ? Math.max(2, (row.value / maxValue) * 100) : 0}%`;
  }
  return rows;
});

const visibleStreetRows = computed(() => streetRows.value.slice(0, rightPanelRankLimit));
const showDistrictInRow = computed(() => scopeLabel.value === DISPLAY_RANGE_ALL);
const scopeTotal = computed(() => streetRows.value.reduce((sum, row) => sum + row.value, 0));

// 行政区范围掩膜：街道要素索引（=streets 行序=grid.street 列语义）→ 是否在范围内；全市为 null
const scopeStreetMask = computed(() => {
  const scope = scopeLabel.value;
  if (scope === DISPLAY_RANGE_ALL) return null;
  const streets = streetStats.value?.streets;
  if (!Array.isArray(streets)) return null;
  const mask = new Uint8Array(streets.length);
  streets.forEach((street, idx) => {
    if (street.district === scope) mask[idx] = 1;
  });
  return mask;
});

// ---------------------------------------------------------------------------
// 地图图层：完全复用人口分布监测的 100m deck 栅格 (GridCellLayer) + maplibre 街道描边/标注
// ---------------------------------------------------------------------------

function gridLayerInstance() {
  const data = grid.value;
  const model = props.model;
  if (!data || !model) return null;
  const positions = getModelDerived(model, "tripEndsGridPositions", () => markRaw(buildGridPositions(data)));
  const counts = metric.value === "origin" ? data.home : data.work;
  if (!counts) return null;
  const baseColors = getModelDerived(model, `tripEndsGridColors:${metric.value}`, () =>
    markRaw(buildGridColors(counts, MAP_THEME.tripEnds)),
  );
  const baseElevations = getModelDerived(model, `tripEndsGridElevations:q99pow-v3:${metric.value}`, () =>
    markRaw(buildGridElevations(counts, {
      referenceHeight: TRIPENDS_HEIGHT_REFERENCE,
      minHeight: TRIPENDS_HEIGHT_MIN,
    })),
  );
  const mask = scopeStreetMask.value;
  let colors = mask || props.threeDimensional ? baseColors.slice() : baseColors;
  let elevations = baseElevations;
  if (props.threeDimensional) {
    for (let k = 0; k < data.count; k++) {
      if (colors[k * 4 + 3] > 0) colors[k * 4 + 3] = 255;
    }
  }
  if (mask) {
    elevations = baseElevations.slice();
    for (let k = 0; k < data.count; k++) {
      const streetIdx = data.street[k];
      if (streetIdx === GRID_STREET_SENTINEL || !mask[streetIdx]) {
        colors[k * 4 + 3] = 0;
        elevations[k] = 0;
      }
    }
  }
  return new GridCellLayer({
    id: GRID_LAYER_KEY,
    beforeId: STREET_LINE_ID,
    data: {
      length: data.count,
      attributes: {
        getPosition: { value: positions, size: 2 },
        getFillColor: { value: colors, size: 4 },
        getElevation: { value: elevations, size: 1 },
      },
    },
    cellSize: (Number(summary.value?.cellSizeMeters) > 0 ? Number(summary.value.cellSizeMeters) : 100) * 1.02,
    extruded: props.threeDimensional,
    elevationScale: 1,
    opacity: 1,
    pickable: false,
  });
}

// 街道 FeatureCollection 附加展示属性（label/占比）。
function decoratedStreetsGeojson() {
  const fc = streetsGeojson.value;
  if (!fc) return null;
  const scope = scopeLabel.value;
  const shareByCode = new Map(streetRows.value.map((row) => [String(row.code), row.shareText]));
  const features = [];
  for (const feature of fc.features) {
    const propsIn = feature.properties || {};
    if (scope !== DISPLAY_RANGE_ALL && propsIn.district !== scope) continue;
    const code = String(propsIn.code || feature.id || "");
    const share = shareByCode.get(code);
    features.push({
      ...feature,
      properties: {
        ...propsIn,
        inScope: 1,
        label: share ? `${propsIn.name}\n${share}` : String(propsIn.name || ""),
      },
    });
  }
  return { type: "FeatureCollection", features };
}

function ensureStreetLayers(map) {
  const isDark = isDarkTheme.value;
  const theme = MAP_THEME.tripEnds;
  const streetLineColor = isDark ? (theme.streetLineDark || "#ffffff") : theme.streetLine;
  const streetLabelColor = isDark ? (theme.streetLabelDark || "#f0f4f8") : theme.streetLabel;
  const streetLabelHaloColor = isDark ? (theme.streetLabelHaloDark || "rgba(18,22,29,0.94)") : theme.streetLabelHalo;

  if (!map.getSource(STREET_SOURCE_ID)) {
    map.addSource(STREET_SOURCE_ID, { type: "geojson", data: { type: "FeatureCollection", features: [] } });
  }
  if (!map.getLayer(STREET_LINE_ID)) {
    map.addLayer({
      id: STREET_LINE_ID,
      type: "line",
      source: STREET_SOURCE_ID,
      layout: {
        "line-join": "round",
        "line-cap": "butt",
      },
      paint: {
        "line-color": streetLineColor,
        "line-width": ["interpolate", ["linear"], ["zoom"], 9, 1, 12, 1.8],
        "line-opacity": ["case", ["==", ["get", "inScope"], 1], 0.88, 0.3],
        "line-dasharray": [1, 0],
      },
    });
  } else {
    map.setPaintProperty(STREET_LINE_ID, "line-color", streetLineColor);
    map.setPaintProperty(STREET_LINE_ID, "line-dasharray", [1, 0]);
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
        "text-color": streetLabelColor,
        "text-halo-color": streetLabelHaloColor,
        "text-halo-width": 1.4,
        "text-opacity": ["case", ["==", ["get", "inScope"], 1], 1, 0.55],
      },
    });
  } else {
    map.setPaintProperty(STREET_LABEL_ID, "text-color", streetLabelColor);
    map.setPaintProperty(STREET_LABEL_ID, "text-halo-color", streetLabelHaloColor);
  }
}

watch(isDarkTheme, () => {
  refreshMapLayers();
});

function refreshMapLayers() {
  if (status.value !== "ready") return;
  if (!pageActive.value) {
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
    const layer = gridLayerInstance();
    if (layer) setSharedDeckLayer(wrapper, GRID_LAYER_KEY, layer, 0);
    else removeSharedDeckLayer(wrapper, GRID_LAYER_KEY);
    pendingLayerRefresh = false;
  } catch (error) {
    pendingLayerRefresh = true;
    console.warn("[出行分布监测] 图层刷新失败，等待下次触发", error);
  }
}

function removeMapLayers() {
  const wrapper = MapRef.value;
  const map = wrapper?.map;
  removeSharedDeckLayer(wrapper, GRID_LAYER_KEY);
  if (!map || !map.getStyle) return;
  try {
    if (map.getLayer(STREET_LABEL_ID)) map.removeLayer(STREET_LABEL_ID);
    if (map.getLayer(STREET_LINE_ID)) map.removeLayer(STREET_LINE_ID);
    if (map.getSource(STREET_SOURCE_ID)) map.removeSource(STREET_SOURCE_ID);
  } catch (error) {
    console.warn("[出行分布监测] 图层清理失败", error);
  }
}

// 点击街道行 → 地图定位到该街道范围
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
  const scanGeom = (geom) => {
    if (!geom) return;
    if (geom.type === "Polygon") geom.coordinates.forEach(scanRing);
    else if (geom.type === "MultiPolygon") geom.coordinates.forEach((p) => p.forEach(scanRing));
  };
  scanGeom(feature.geometry);
  const bounds = Number.isFinite(minX) ? [minX, minY, maxX, maxY] : null;
  if (bounds) streetBoundsCache.set(code, bounds);
  return bounds;
}

function focusStreet(code) {
  const map = MapRef.value?.map;
  if (!map) return;
  const bounds = streetBounds(code);
  if (!bounds) return;
  map.fitBounds(bounds, { padding: 48, maxZoom: 14.5, duration: 800 });
}

// ---------------------------------------------------------------------------
// 响应式联动与生命周期
// ---------------------------------------------------------------------------

watch(() => props.model, () => {
  grid.value = null;
  summary.value = null;
  streetStats.value = null;
  streetBoundsCache.clear();
  bootstrap();
});

watch([metric, () => props.threeDimensional], () => {
  refreshMapLayers();
});

watch(scopeLabel, () => {
  refreshMapLayers();
});

onMounted(() => {
  bootstrap();
});

onActivated(() => {
  pageActive.value = true;
  if (status.value === "generating" && !pollTimer) schedulePoll();
  if (pendingLayerRefresh || status.value === "ready") {
    refreshMapLayers();
  }
});

onDeactivated(() => {
  pageActive.value = false;
  removeMapLayers();
});

onUnmounted(() => {
  pageActive.value = false;
  clearTimeout(pollTimer);
  removeMapLayers();
});
</script>

<style lang="scss" scoped>
.qzd-card {
  box-sizing: border-box;
}

.qzd-title {
  margin-bottom: 12px;

  h2 {
    margin: 0;
    font-size: 16px;
    font-weight: 780;
    color: var(--dm2-ink);
  }
}

.qzd-metric-switch {
  display: flex;
  padding: 3px;
  border-radius: 10px;
  background: var(--dm2-surface-sunken, #f1f5f9);
  margin-bottom: 12px;
}

.qzd-metric-btn {
  flex: 1;
  appearance: none;
  border: none;
  background: transparent;
  padding: 6px 10px;
  border-radius: 8px;
  color: var(--dm2-muted);
  font-size: 12.5px;
  font-weight: 680;
  cursor: pointer;
  transition: all 0.2s ease;

  &.active {
    background: #fff;
    color: var(--dm2-ink);
    box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  }
}

.qzd-hero {
  margin-bottom: 14px;
}

.qzd-hero-value {
  margin: 0;
  display: flex;
  align-items: baseline;
  gap: 4px;

  strong {
    font-size: 26px;
    font-weight: 800;
    font-family: var(--dm2-font-num);
    color: var(--dm2-ink);
    letter-spacing: -0.02em;
  }

  em {
    font-style: normal;
    font-size: 12px;
    color: var(--dm2-muted);
    font-weight: 600;
  }
}

.qzd-street-rank {
  display: flex;
  flex-direction: column;
}

.qzd-rank-head {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto 48px;
  gap: 8px;
  padding: 0 4px 6px;
  border-bottom: 1px solid var(--dm2-line-faint);
  color: var(--dm2-muted);
  font-size: 11px;
  font-weight: 680;
}

.qzd-rank-head-value {
  text-align: right;
}

.qzd-rank-head-share {
  text-align: right;
}

.qzd-rank-list {
  margin: 0;
  padding: 0;
  list-style: none;

  li {
    margin-top: 4px;
  }
}

.qzd-rank-row {
  width: 100%;
  appearance: none;
  border: none;
  background: transparent;
  padding: 6px 4px;
  border-radius: 6px;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s ease;

  &:hover {
    background: rgba(0, 113, 227, 0.05);
  }
}

.qzd-rank-main {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto 48px;
  gap: 8px;
  align-items: baseline;
}

.qzd-rank-name {
  font-size: 12.5px;
  font-weight: 680;
  color: var(--dm2-ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.qzd-rank-district {
  font-style: normal;
  font-size: 10.5px;
  color: var(--dm2-muted);
  margin-left: 4px;
  font-weight: 500;
}

.qzd-rank-value {
  font-family: var(--dm2-font-num);
  font-size: 12.5px;
  font-weight: 750;
  color: var(--dm2-ink);
  text-align: right;
}

.qzd-rank-share {
  font-family: var(--dm2-font-num);
  font-size: 11px;
  color: var(--dm2-muted);
  text-align: right;
}

.qzd-rank-bar {
  display: block;
  height: 3px;
  border-radius: 999px;
  background: rgba(0, 113, 227, 0.1);
  margin-top: 4px;
  overflow: hidden;
}

.qzd-rank-bar-fill {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: var(--dm2-accent-strong, #0071e3);
  transition: width 0.35s ease;
}

/* ── 状态与骨架 ── */
.qzd-status {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 34px 12px;
  text-align: center;
}

.qzd-status-icon {
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

.qzd-status-title {
  margin: 0;
  color: var(--dm2-ink);
  font-size: 13.5px;
  font-weight: 760;
}

.qzd-status-desc {
  margin: 0;
  max-width: 260px;
  color: var(--dm2-muted);
  font-size: 11.5px;
  line-height: 1.5;
  font-weight: 640;
}

.qzd-retry {
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

.qzd-skeleton {
  padding: 14px 0;
}

.qzd-sk {
  border-radius: 8px;
  background: linear-gradient(90deg, rgba(28, 32, 36, 0.05) 25%, rgba(28, 32, 36, 0.1) 42%, rgba(28, 32, 36, 0.05) 60%);
  background-size: 240% 100%;
  animation: qzd-shimmer 1.4s ease-in-out infinite;
}

.qzd-sk-segment {
  height: 34px;
}

.qzd-sk-hero {
  height: 62px;
  margin-top: 12px;
}

.qzd-sk-row {
  height: 30px;
  margin-top: 9px;
}

@keyframes qzd-shimmer {
  0% {
    background-position: 130% 0;
  }

  100% {
    background-position: -110% 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .qzd-sk {
    animation: none;
  }

  .qzd-rank-bar-fill {
    transition: none;
  }
}

/* —— 地图左下角密度图例（与人口分布 map-legend 同结构同定位） —— */
.qzd-map-legend {
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
  pointer-events: auto;
}

.qzd-map-legend-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 2px;
}

.qzd-map-legend-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--app-ink, #344054);
}

.qzd-map-legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.qzd-map-legend-swatch {
  width: 22px;
  height: 6px;
  border-radius: 3px;
  flex: none;
}

.qzd-map-legend-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ── 暗色模式（html.dark，跟随底图选择） ── */
html.dark .qzd-metric-switch {
  background: rgba(148, 180, 220, 0.1);
}
html.dark .qzd-metric-btn.active {
  background: #1a2431;
  box-shadow: 0 1px 4px rgba(2, 6, 12, 0.32);
}
html.dark .qzd-rank-row:hover {
  background: rgba(64, 156, 255, 0.09);
}
html.dark .qzd-rank-bar {
  background: rgba(148, 180, 220, 0.16);
}
html.dark .qzd-map-legend {
  color: #c2cddd;
}
html.dark .qzd-status-icon.is-error {
  color: #f87171;
}
html.dark .qzd-retry {
  background: #1a2431;
}
html.dark .qzd-sk {
  background: linear-gradient(90deg, rgba(148, 180, 220, 0.08) 25%, rgba(148, 180, 220, 0.14) 42%, rgba(148, 180, 220, 0.08) 60%);
  background-size: 240% 100%;
}
</style>
