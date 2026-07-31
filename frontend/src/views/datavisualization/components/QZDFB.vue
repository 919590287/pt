<!-- 出行分布监测（公交出行监测模块，人口分布监测的同级子模块；原「起终点分布监测」，
     文件名/qzd- 前缀/rm-tripends-* 图层 id 为历史内部标识，保持不动）
     地图：仿真与真实模式统一使用按起终点人次加权的核密度热力图
     （maplibre，rm-tripends-heat-*），并叠加街道边界/名称占比标注（maplibre，rm-tripends-street-*）。
     右侧：起点/终点切换 + 按街道占比排序榜，teleport 到 index.vue 的右侧容器（同 RKFB 模式）；
     热力图例浮在地图左下角（teleport 到 body，结构同客流分析地图图例）。
     口径：本次活动出行的起终点——plans 中含 pt leg 的 trip，
     起点=出行前置活动位置、终点=出行后置活动位置（不再是上/下车站点）；
     一律直出已加载模型的原始人次，不做任何数量缩放；grid.bin 复用 PGRD 契约（home 列=起点、work 列=终点）。 -->
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

  <!-- 地图图例：两种数据源共用相对热度样式；pageActive 防止 KeepAlive 切页后残留 -->
  <teleport to="body">
    <div
      v-if="status === 'ready' && pageActive"
      class="qzd-map-legend"
      aria-label="出行分布热力图例"
      :title="`按${metricLabel}人次加权的相对热度`"
      @click.stop
    >
      <div class="qzd-map-legend-head">
        <span class="qzd-map-legend-title">出行分布热力（相对热度）</span>
      </div>
      <div class="qzd-heat-scale" aria-hidden="true">
        <span>低</span>
        <i :style="{ background: heatLegendGradient }"></i>
        <span>高</span>
      </div>
      <p class="qzd-heat-note">{{ heatPointCount }} 个起终点单元，按{{ metricLabel }}人次加权</p>
    </div>
  </teleport>
</template>

<script setup>
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, shallowRef, watch, inject, markRaw } from "vue";
import { removeSharedDeckLayer } from "../layers/deckOverlayRegistry.js";
import { MAP_THEME } from "@/utils/mapTheme.js";
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
} from "../utils/populationGrid.js";
import {
  buildTripEndsHeatmapFeatureCollection,
  filterTripEndsHeatmapFeatureCollection,
} from "../utils/tripEndsHeatmap.js";
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
// 保留旧 deck 图层 key 仅用于清理热更新/模式切换前可能残留的栅格层。
const LEGACY_GRID_LAYER_KEY = "rm-tripends-grid";
const HEAT_SOURCE_ID = "rm-tripends-heat-source";
const HEAT_LAYER_ID = "rm-tripends-heat-layer";
const STREET_SOURCE_ID = "rm-tripends-streets";
const STREET_LINE_ID = "rm-tripends-street-line";
const STREET_LABEL_ID = "rm-tripends-street-label";
const GENERATING_POLL_MS = 8000;
const HEAT_RAMP = MAP_THEME.tripEnds.ramp.slice(1);

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
const heatPointCount = ref(0);
const heatLegendGradient = `linear-gradient(90deg, ${HEAT_RAMP.join(", ")})`;

function formatInt(value) {
  if (!Number.isFinite(value)) return "--";
  return Math.round(value).toLocaleString("zh-CN");
}

// ---------------------------------------------------------------------------
// 数据加载：summary（generating 轮询）→ grid.bin + streets + 街道面 并行
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
        grid.value = getModelDerived(model, "tripEndsGrid", () => markRaw(parsePopulationGrid(gridBuffer)));
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
      if (/超时|网关|服务|服务器|连接|Network|timeout|temporar/i.test(message)) {
        status.value = "generating";
        schedulePoll();
        return;
      }
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
// 地图图层：两种数据源共用 maplibre 热力图 + 街道边界/标注
// ---------------------------------------------------------------------------

function heatPayload() {
  const data = grid.value;
  const model = props.model;
  if (!data || !model) return null;
  const counts = metric.value === "origin" ? data.home : data.work;
  const base = getModelDerived(model, `tripEndsHeat:v2:${metric.value}`, () =>
    markRaw(buildTripEndsHeatmapFeatureCollection(data, counts)),
  );
  const mask = scopeStreetMask.value;
  if (!mask) return base;
  return getModelDerived(model, `tripEndsHeat:v2:${metric.value}:${scopeLabel.value}`, () =>
    markRaw(filterTripEndsHeatmapFeatureCollection(base, mask)),
  );
}

function ensureHeatLayer(map) {
  if (!map.getSource(HEAT_SOURCE_ID)) {
    map.addSource(HEAT_SOURCE_ID, {
      type: "geojson",
      data: { type: "FeatureCollection", features: [] },
    });
  }
  if (!map.getLayer(HEAT_LAYER_ID)) {
    const layer = {
      id: HEAT_LAYER_ID,
      type: "heatmap",
      source: HEAT_SOURCE_ID,
      maxzoom: 24,
      paint: {
        "heatmap-weight": ["coalesce", ["get", "weight"], 0],
        "heatmap-intensity": ["interpolate", ["linear"], ["zoom"], 8, 0.82, 12, 1.05, 16, 1.28],
        // 采用约 3km 的固定地理带宽：像素半径按 zoom 指数增长，避免同一热区缩放后忽大忽小。
        // 两种数据源都使用该带宽，保持热区覆盖和色阶表达一致。
        "heatmap-radius": ["interpolate", ["exponential", 2], ["zoom"], 8, 4.8, 16, 1228.8],
        "heatmap-opacity": ["interpolate", ["linear"], ["zoom"], 8, 0.82, 14, 0.9, 18, 0.84],
        "heatmap-color": [
          "interpolate",
          ["linear"],
          ["heatmap-density"],
          0, "rgba(0,0,0,0)",
          0.08, HEAT_RAMP[0],
          0.24, HEAT_RAMP[1],
          0.42, HEAT_RAMP[2],
          0.6, HEAT_RAMP[3],
          0.78, HEAT_RAMP[4],
          0.9, HEAT_RAMP[5],
          1, HEAT_RAMP[HEAT_RAMP.length - 1],
        ],
      },
    };
    if (map.getLayer(STREET_LINE_ID)) map.addLayer(layer, STREET_LINE_ID);
    else map.addLayer(layer);
  }
}

// 街道 FeatureCollection 附加展示属性（label/占比）。
// 行政区模式：区外街道轮廓/名称整体不下发（用户定版：只显示范围内部）。
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
  const theme = MAP_THEME.tripEnds;
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
    removeSharedDeckLayer(wrapper, LEGACY_GRID_LAYER_KEY);
    ensureHeatLayer(map);
    const payload = heatPayload();
    map.getSource(HEAT_SOURCE_ID)?.setData(payload?.collection || { type: "FeatureCollection", features: [] });
    heatPointCount.value = payload?.pointCount || 0;
    pendingLayerRefresh = false;
  } catch (error) {
    // 地图尚未就绪（样式加载中等）时静默，数据/指标变化会再次触发
    pendingLayerRefresh = true;
    console.warn("[出行分布监测] 图层刷新失败，等待下次触发", error);
  }
}

function removeMapLayers() {
  const wrapper = MapRef.value;
  const map = wrapper?.map;
  removeSharedDeckLayer(wrapper, LEGACY_GRID_LAYER_KEY);
  if (!map || !map.getStyle) return;
  try {
    if (map.getLayer(STREET_LABEL_ID)) map.removeLayer(STREET_LABEL_ID);
    if (map.getLayer(STREET_LINE_ID)) map.removeLayer(STREET_LINE_ID);
    if (map.getLayer(HEAT_LAYER_ID)) map.removeLayer(HEAT_LAYER_ID);
    if (map.getSource(STREET_SOURCE_ID)) map.removeSource(STREET_SOURCE_ID);
    if (map.getSource(HEAT_SOURCE_ID)) map.removeSource(HEAT_SOURCE_ID);
    heatPointCount.value = 0;
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
  const geometry = feature.geometry || {};
  if (geometry.type === "Polygon") geometry.coordinates.forEach(scanRing);
  else if (geometry.type === "MultiPolygon") geometry.coordinates.forEach((poly) => poly.forEach(scanRing));
  const bounds = Number.isFinite(minX) ? [[minX, minY], [maxX, maxY]] : null;
  streetBoundsCache.set(code, bounds);
  return bounds;
}

function focusStreet(code) {
  const bounds = streetBounds(code);
  const map = MapRef.value?.map;
  if (!bounds || !map?.fitBounds) return;
  map.fitBounds(bounds, { padding: 90, duration: 600, maxZoom: 13.5 });
}

// ---------------------------------------------------------------------------
// 生命周期
// ---------------------------------------------------------------------------

watch(metric, () => refreshMapLayers());
watch(() => displayRange.selected, () => refreshMapLayers());

onMounted(bootstrap);

onActivated(() => {
  pageActive.value = true;
  // KeepAlive 失活时会主动从共享 overlay 注销图层，回来后用内存数据重建。
  if (status.value === "ready" || pendingLayerRefresh) refreshMapLayers();
});

onDeactivated(() => {
  pageActive.value = false;
  // Deck 图层不属于 MyMap.layers，MapLayout 的页面图层暂存无法自动摘除它。
  // 必须显式从共享 overlay 注销，否则切到数据管理/换乘分析后仍会继续渲染。
  pendingLayerRefresh = status.value === "ready";
  removeMapLayers();
});

onUnmounted(() => {
  requestSeq += 1;
  clearTimeout(pollTimer);
  removeMapLayers();
});
</script>

<style lang="scss" scoped>
/* teleport 节点带本组件 scope，样式自持（同 RKFB），视觉语言对齐 index.vue 的 rm- 面板体系 */
.qzd-card {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  width: 100%;
  box-sizing: border-box;
}

.qzd-title {
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

.qzd-scope {
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

/* —— 指标切换 —— */
.qzd-metric-switch {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  margin-top: 12px;
  padding: 3px;
  border-radius: 10px;
  background: rgba(28, 32, 36, 0.05);
}

.qzd-metric-btn {
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

/* —— 主指标 —— */
.qzd-hero {
  margin-top: 14px;
}

.qzd-hero-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.qzd-hero-label {
  color: var(--dm2-muted);
  font-size: 12px;
  font-weight: 700;
}

.qzd-hero-value {
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

/* —— 街道占比榜单 —— */
.qzd-street-rank {
  display: flex;
  flex-direction: column;
  min-height: 0;
  margin-top: 14px;
}

.qzd-rank-head {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 0 2px 6px;
  border-bottom: 1px solid var(--dm2-line-faint);
  color: var(--dm2-muted);
  font-size: 10.5px;
  font-weight: 700;
}

.qzd-rank-head-name {
  flex: 1;
  min-width: 0;
}

.qzd-rank-head-value {
  flex-shrink: 0;
  width: 96px;
  text-align: right;
}

.qzd-rank-head-share {
  flex-shrink: 0;
  width: 64px;
  text-align: right;
}

.qzd-rank-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.qzd-rank-row {
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

.qzd-rank-main {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.qzd-rank-name {
  flex: 1;
  min-width: 0;
  color: var(--dm2-ink);
  font-size: 12.5px;
  font-weight: 720;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.qzd-rank-district {
  margin-left: 4px;
  color: var(--dm2-muted);
  font-size: 10.5px;
  font-style: normal;
  font-weight: 650;
}

.qzd-rank-value {
  flex-shrink: 0;
  width: 96px;
  text-align: right;
  color: var(--dm2-ink);
  font-size: 12.5px;
  font-weight: 760;
  font-variant-numeric: tabular-nums;
}

.qzd-rank-share {
  flex-shrink: 0;
  width: 64px;
  text-align: right;
  color: var(--dm2-muted);
  font-size: 11.5px;
  font-weight: 680;
  font-variant-numeric: tabular-nums;
}

.qzd-rank-bar {
  display: block;
  height: 4px;
  margin-top: 6px;
  border-radius: 999px;
  background: rgba(61, 110, 166, 0.12);
  overflow: hidden;
}

.qzd-rank-bar-fill {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: #3d6ea6; /* mapTheme network.line 同源钢青蓝，单色编码数量 */
  transition: width 0.35s ease;
}

.qzd-rank-footnote {
  margin: 8px 2px 0;
  color: var(--dm2-muted);
  font-size: 10.5px;
  line-height: 1.4;
  font-weight: 620;
}

/* —— 地图左下角栅格客流图例（与客流分析 map-flow-legend 同结构同定位） —— */
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
  /* 拦截点击避免穿透到地图 */
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

.qzd-heat-scale {
  display: grid;
  grid-template-columns: auto minmax(112px, 1fr) auto;
  align-items: center;
  gap: 7px;
  margin-top: 2px;
  font-size: 10px;
  font-weight: 700;

  i {
    display: block;
    height: 10px;
    border-radius: 999px;
    box-shadow: inset 0 0 0 1px rgba(31, 49, 64, 0.12);
  }
}

.qzd-heat-note {
  margin: 2px 0 0;
  color: var(--dm2-muted, #667085);
  font-size: 10px;
  line-height: 1.35;
  font-weight: 620;
}

/* —— 状态与骨架 —— */
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
  /* --app-ink-soft 未定义，浅色落在 fallback #475467，暗色需显式提亮 */
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
