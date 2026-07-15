<!-- 人口分布监测（公交出行监测模块首个子模块）
     地图：100m 人口栅格（deck.gl GridCellLayer，rm-population-grid）+ 街道边界/名称占比标注（maplibre，rm-population-street-*）。
     右侧：居住/就业切换 + 按街道榜单，teleport 到 index.vue 的右侧容器（同 TJFX 模式）；
     栅格密度图例浮在地图左下角（teleport 到 body，结构同客流分析地图图例）。
     口径：居住=plans 首个 home 活动、就业=首个 work 活动；一律直出模型抽样人数（不做 ÷scale 扩样）；
     密度=人口/街道辖区面积。 -->
<template>
  <teleport to="#datavisualization_index_box2" defer>
    <div class="rk-card" aria-label="人口分布监测面板">
      <div class="rk-title">
        <h2>人口分布监测</h2>
        <span class="rk-scope" :title="`显示范围：${scopeLabel}`">{{ scopeLabel }}</span>
      </div>

      <!-- 状态机：生成中 / 加载 / 失败 整块替换正文，避免状态浮在 0 值上 -->
      <div v-if="status === 'generating'" class="rk-status" role="status">
        <span class="rk-status-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="9"></circle>
            <polyline points="12 7 12 12 15.5 14"></polyline>
          </svg>
        </span>
        <p class="rk-status-title">人口分布缓存生成中</p>
        <p class="rk-status-desc">后端正在为当前模型提取居住 / 就业分布，就绪后将自动展示。</p>
      </div>

      <div v-else-if="status === 'error'" class="rk-status" role="alert">
        <span class="rk-status-icon is-error" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">
            <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path>
            <line x1="12" y1="9" x2="12" y2="13"></line>
            <line x1="12" y1="17" x2="12.01" y2="17"></line>
          </svg>
        </span>
        <p class="rk-status-title">人口分布数据加载失败</p>
        <p class="rk-status-desc">{{ errorMessage }}</p>
        <button type="button" class="rk-retry" @click="bootstrap">重新加载</button>
      </div>

      <div v-else-if="status === 'loading'" class="rk-skeleton" aria-hidden="true">
        <div class="rk-sk rk-sk-segment"></div>
        <div class="rk-sk rk-sk-hero"></div>
        <div class="rk-sk rk-sk-row" v-for="n in 6" :key="n"></div>
      </div>

      <template v-else>
        <div class="rk-metric-switch" role="group" aria-label="人口指标切换">
          <button
            v-for="option in METRIC_OPTIONS"
            :key="option.key"
            type="button"
            :class="['rk-metric-btn', { active: metric === option.key }]"
            :aria-pressed="metric === option.key"
            @click="metric = option.key"
          >
            {{ option.label }}
          </button>
        </div>

        <div class="rk-hero">
          <div class="rk-hero-head">
            <span class="rk-hero-label">{{ metricLabel }}总量</span>
          </div>
          <p class="rk-hero-value">
            <strong>{{ formatInt(scopeTotal) }}</strong>
            <em>人</em>
          </p>
        </div>

        <div v-if="!streetRows.length" class="rk-status" role="status">
          <span class="rk-status-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
              <path d="M12 21s7-5.2 7-11a7 7 0 1 0-14 0c0 5.8 7 11 7 11Z"></path>
              <circle cx="12" cy="10" r="2.5"></circle>
            </svg>
          </span>
          <p class="rk-status-title">{{ scopeLabel }}范围内暂无{{ metricLabel }}</p>
          <p class="rk-status-desc">当前模型在该范围内没有对应活动位置，可切换显示范围或指标。</p>
        </div>

        <div v-else class="rk-street-rank">
          <div class="rk-rank-head" aria-hidden="true">
            <span class="rk-rank-head-name">街道</span>
            <span class="rk-rank-head-value">{{ metricLabel }}</span>
            <span class="rk-rank-head-density">密度</span>
          </div>
          <ol class="rk-rank-list">
            <li v-for="row in visibleStreetRows" :key="row.code">
              <button
                type="button"
                class="rk-rank-row"
                :title="`${row.name}（${row.district}）：${metricLabel} ${formatInt(row.value)} 人，占${scopeLabel} ${row.shareText}，密度 ${formatInt(row.density)} 人/km²`"
                @click="focusStreet(row.code)"
              >
                <span class="rk-rank-main">
                  <span class="rk-rank-name">
                    {{ row.name }}
                    <em v-if="showDistrictInRow" class="rk-rank-district">{{ row.district }}</em>
                  </span>
                  <span class="rk-rank-value">{{ formatInt(row.value) }}<i>人</i></span>
                  <span class="rk-rank-density">{{ formatInt(row.density) }}<i>/km²</i></span>
                </span>
                <span class="rk-rank-bar" aria-hidden="true">
                  <span class="rk-rank-bar-fill" :style="{ width: row.barWidth }"></span>
                </span>
              </button>
            </li>
          </ol>
          <p v-if="streetRows.length > visibleStreetRows.length" class="rk-rank-footnote">
            按{{ metricLabel }}排序，显示前 {{ visibleStreetRows.length }} 名（共 {{ streetRows.length }} 个街道）
          </p>
        </div>
      </template>
    </div>
  </teleport>

  <!-- 栅格密度图例：地图左下角浮动（结构同客流分析地图图例）；pageActive 防止 KeepAlive 切页后残留 -->
  <teleport to="body">
    <div v-if="status === 'ready' && pageActive" class="rk-map-legend" aria-label="栅格密度图例（人/平方公里）" @click.stop>
      <div class="rk-map-legend-head">
        <span class="rk-map-legend-title">栅格密度（人/km²）</span>
      </div>
      <div v-for="item in legendItems" :key="item.label" class="rk-map-legend-item">
        <span class="rk-map-legend-swatch" :style="{ background: item.color }" aria-hidden="true"></span>
        <span class="rk-map-legend-label">{{ item.label }}</span>
      </div>
    </div>
  </teleport>
</template>

<script setup>
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, shallowRef, watch, inject, markRaw } from "vue";
import { GridCellLayer } from "@deck.gl/layers";
import { setSharedDeckLayer, removeSharedDeckLayer } from "../layers/deckOverlayRegistry.js";
import { MAP_THEME } from "@/utils/mapTheme.js";
import {
  getCachedPopulationGrid,
  getCachedPopulationStreets,
  getCachedPopulationSummary,
  getModelDerived,
} from "@/utils/modelDataCache.js";
import { fetchStreetsGeojsonOnce } from "../utils/streetsGeojson.js";
import { useDisplayRangeStore, DISPLAY_RANGE_ALL } from "@/stores/displayRange.js";
import {
  buildDensityLegendItems,
  buildGridColors,
  buildGridPositions,
  parsePopulationGrid,
} from "../utils/populationGrid.js";

const props = defineProps({
  model: String,
});

const MapRef = inject("MapRef", ref(null));

const METRIC_OPTIONS = [
  { key: "home", label: "居住人口" },
  { key: "work", label: "就业人口" },
];
const GRID_LAYER_KEY = "rm-population-grid";
const STREET_SOURCE_ID = "rm-population-streets";
const STREET_LINE_ID = "rm-population-street-line";
const STREET_LABEL_ID = "rm-population-street-label";
const RANK_ROW_LIMIT = 30;
const GENERATING_POLL_MS = 8000;

const status = ref("loading"); // loading | generating | error | ready
const errorMessage = ref("");
const metric = ref("home");
const summary = shallowRef(null);
const streetStats = shallowRef(null); // { streets:[{code,name,district,areaKm2,home,work}], totals }
const grid = shallowRef(null); // parsePopulationGrid 结果（markRaw）
const streetsGeojson = shallowRef(null); // 模型无关街道面

const displayRange = useDisplayRangeStore();
const scopeLabel = computed(() => displayRange.selected || DISPLAY_RANGE_ALL);
const metricLabel = computed(() => (metric.value === "home" ? "居住人口" : "就业人口"));

const legendItems = buildDensityLegendItems(
  MAP_THEME.population.breaks,
  MAP_THEME.population.ramp,
  (v) => (v >= 1000 ? `${v / 1000}k` : String(v)),
);

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

  getCachedPopulationSummary(model)
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
        getCachedPopulationGrid(model, version),
        getCachedPopulationStreets(model),
        fetchStreetsGeojsonOnce(),
      ]).then(([gridBuffer, streetsPayload, geojson]) => {
        if (seq !== requestSeq || props.model !== model) return null;
        if (!gridBuffer || !streetsPayload || streetsPayload.status === "generating") {
          status.value = "generating";
          schedulePoll();
          return null;
        }
        grid.value = getModelDerived(model, "populationGrid", () => markRaw(parsePopulationGrid(gridBuffer)));
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
      errorMessage.value = message || "人口分布数据加载失败";
      status.value = "error";
    });
}

// ---------------------------------------------------------------------------
// 街道榜单（随指标 / 显示范围联动；数值为模型抽样人数，不扩样）
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
    const areaKm2 = Number(street.areaKm2) > 0 ? Number(street.areaKm2) : 1;
    rows.push({
      code: street.code,
      name: street.name,
      district: street.district,
      value,
      density: value / areaKm2,
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

const visibleStreetRows = computed(() => streetRows.value.slice(0, RANK_ROW_LIMIT));
const showDistrictInRow = computed(() => scopeLabel.value === DISPLAY_RANGE_ALL);
const scopeTotal = computed(() => streetRows.value.reduce((sum, row) => sum + row.value, 0));

// ---------------------------------------------------------------------------
// 地图图层：deck 栅格 + maplibre 街道边界/标注
// ---------------------------------------------------------------------------

function gridLayerInstance() {
  const data = grid.value;
  const model = props.model;
  if (!data || !model) return null;
  const positions = getModelDerived(model, "populationGridPositions", () => markRaw(buildGridPositions(data)));
  const counts = metric.value === "home" ? data.home : data.work;
  const colors = getModelDerived(model, `populationGridColors:${metric.value}`, () =>
    markRaw(buildGridColors(counts, MAP_THEME.population)),
  );
  return new GridCellLayer({
    id: GRID_LAYER_KEY,
    beforeId: STREET_LINE_ID, // 栅格压在街道描边/标注之下
    data: {
      length: data.count,
      attributes: {
        getPosition: { value: positions, size: 2 },
        getFillColor: { value: colors, size: 4 },
      },
    },
    // ×1.02：fp32 实例定位抖动会在整齐栅格上留出发丝缝，2% 重叠肉眼不可见（试验页已验证）
    cellSize: (Number(summary.value?.cellSizeMeters) > 0 ? Number(summary.value.cellSizeMeters) : 100) * 1.02,
    extruded: false,
    pickable: false,
  });
}

// 街道 FeatureCollection 附加展示属性（label/占比/是否在显示范围内）
function decoratedStreetsGeojson() {
  const fc = streetsGeojson.value;
  if (!fc) return null;
  const scope = scopeLabel.value;
  const shareByCode = new Map(streetRows.value.map((row) => [String(row.code), row.shareText]));
  return {
    type: "FeatureCollection",
    features: fc.features.map((feature) => {
      const propsIn = feature.properties || {};
      const code = String(propsIn.code || feature.id || "");
      const inScope = scope === DISPLAY_RANGE_ALL || propsIn.district === scope;
      const share = shareByCode.get(code);
      return {
        ...feature,
        properties: {
          ...propsIn,
          inScope: inScope ? 1 : 0,
          label: inScope && share ? `${propsIn.name}\n${share}` : String(propsIn.name || ""),
        },
      };
    }),
  };
}

function ensureStreetLayers(map) {
  const theme = MAP_THEME.population;
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
    const layer = gridLayerInstance();
    if (layer) setSharedDeckLayer(wrapper, GRID_LAYER_KEY, layer, 0);
    pendingLayerRefresh = false;
  } catch (error) {
    // 地图尚未就绪（样式加载中等）时静默，数据/指标变化会再次触发
    pendingLayerRefresh = true;
    console.warn("[人口分布监测] 图层刷新失败，等待下次触发", error);
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
    console.warn("[人口分布监测] 图层清理失败", error);
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
/* teleport 节点带本组件 scope，样式自持（同 TJFX），视觉语言对齐 index.vue 的 rm- 面板体系 */
.rk-card {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  width: 100%;
  box-sizing: border-box;
}

.rk-title {
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

.rk-scope {
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
.rk-metric-switch {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  margin-top: 12px;
  padding: 3px;
  border-radius: 10px;
  background: rgba(28, 32, 36, 0.05);
}

.rk-metric-btn {
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
.rk-hero {
  margin-top: 14px;
}

.rk-hero-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.rk-hero-label {
  color: var(--dm2-muted);
  font-size: 12px;
  font-weight: 700;
}

.rk-hero-value {
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

/* —— 街道榜单 —— */
.rk-street-rank {
  display: flex;
  flex-direction: column;
  min-height: 0;
  margin-top: 14px;
}

.rk-rank-head {
  display: flex;
  align-items: baseline;
  padding: 0 2px 6px;
  border-bottom: 1px solid var(--dm2-line-faint);
  color: var(--dm2-muted);
  font-size: 10.5px;
  font-weight: 700;
}

.rk-rank-head-name {
  flex: 1;
}

.rk-rank-head-value {
  width: 86px;
  text-align: right;
}

.rk-rank-head-density {
  width: 92px;
  text-align: right;
}

.rk-rank-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.rk-rank-row {
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

.rk-rank-main {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.rk-rank-name {
  flex: 1;
  min-width: 0;
  color: var(--dm2-ink);
  font-size: 12.5px;
  font-weight: 720;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.rk-rank-district {
  margin-left: 4px;
  color: var(--dm2-muted);
  font-size: 10.5px;
  font-style: normal;
  font-weight: 650;
}

.rk-rank-value {
  flex-shrink: 0;
  width: 86px;
  text-align: right;
  color: var(--dm2-ink);
  font-size: 12.5px;
  font-weight: 760;
  font-variant-numeric: tabular-nums;

  i {
    margin-left: 2px;
    color: var(--dm2-muted);
    font-size: 10px;
    font-style: normal;
    font-weight: 650;
  }
}

.rk-rank-density {
  flex-shrink: 0;
  width: 92px;
  text-align: right;
  color: var(--dm2-muted);
  font-size: 11.5px;
  font-weight: 680;
  font-variant-numeric: tabular-nums;

  i {
    margin-left: 2px;
    font-size: 10px;
    font-style: normal;
  }
}

.rk-rank-bar {
  display: block;
  height: 4px;
  margin-top: 6px;
  border-radius: 999px;
  background: rgba(61, 110, 166, 0.12);
  overflow: hidden;
}

.rk-rank-bar-fill {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: #3d6ea6; /* mapTheme network.line 同源钢青蓝，单色编码数量 */
  transition: width 0.35s ease;
}

.rk-rank-footnote {
  margin: 8px 2px 0;
  color: var(--dm2-muted);
  font-size: 10.5px;
  line-height: 1.4;
  font-weight: 620;
}

/* —— 地图左下角密度图例（与客流分析 map-flow-legend 同结构同定位） —— */
.rk-map-legend {
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

.rk-map-legend-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 2px;
}

.rk-map-legend-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--app-ink, #344054);
}

.rk-map-legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.rk-map-legend-swatch {
  width: 22px;
  height: 6px;
  border-radius: 3px;
  flex: none;
}

.rk-map-legend-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* —— 状态与骨架 —— */
.rk-status {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 34px 12px;
  text-align: center;
}

.rk-status-icon {
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

.rk-status-title {
  margin: 0;
  color: var(--dm2-ink);
  font-size: 13.5px;
  font-weight: 760;
}

.rk-status-desc {
  margin: 0;
  max-width: 260px;
  color: var(--dm2-muted);
  font-size: 11.5px;
  line-height: 1.5;
  font-weight: 640;
}

.rk-retry {
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

.rk-skeleton {
  padding: 14px 0;
}

.rk-sk {
  border-radius: 8px;
  background: linear-gradient(90deg, rgba(28, 32, 36, 0.05) 25%, rgba(28, 32, 36, 0.1) 42%, rgba(28, 32, 36, 0.05) 60%);
  background-size: 240% 100%;
  animation: rk-shimmer 1.4s ease-in-out infinite;
}

.rk-sk-segment {
  height: 34px;
}

.rk-sk-hero {
  height: 62px;
  margin-top: 12px;
}

.rk-sk-row {
  height: 30px;
  margin-top: 9px;
}

@keyframes rk-shimmer {
  0% {
    background-position: 130% 0;
  }

  100% {
    background-position: -110% 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .rk-sk {
    animation: none;
  }

  .rk-rank-bar-fill {
    transition: none;
  }
}
</style>
