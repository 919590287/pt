<!-- 人口分布监测（运行监测独立模块）
     地图：100m 人口栅格（deck.gl GridCellLayer，rm-population-grid）+ 街道边界/名称占比标注（maplibre，rm-population-street-*）。
     右侧：按街道榜单，展示口径由左侧次级导航传入；
     栅格密度图例浮在地图左下角（teleport 到 body，结构同客流分析地图图例）。
     口径：常住人口=有 home 活动的人；通勤人口=同时有 home/work 活动的人，分别按居住地和就业地定位；
     密度=人口/街道辖区面积。 -->
<template>
  <teleport to="#datavisualization_index_box2" defer>
    <div class="rk-card" :aria-label="`${submoduleTitle}面板`">
      <div class="rk-title">
        <h2>{{ submoduleTitle }}</h2>
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

      <div v-else-if="status === 'unsupported'" class="rk-status" role="status">
        <span class="rk-status-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="9"></circle>
            <line x1="8" y1="12" x2="16" y2="12"></line>
          </svg>
        </span>
        <p class="rk-status-title">当前模型不支持人口分布</p>
        <p class="rk-status-desc">{{ errorMessage || "缺少可读取的 plans 或活动坐标，平台不会用 0 值代替。" }}</p>
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
        <div class="rk-sk rk-sk-hero"></div>
        <div class="rk-sk rk-sk-row" v-for="n in 6" :key="n"></div>
      </div>

      <template v-else>
        <div v-if="metricRequiresUpgrade" class="rk-status" role="status">
          <span class="rk-status-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
              <circle cx="12" cy="12" r="9"></circle>
              <polyline points="12 7 12 12 15.5 14"></polyline>
            </svg>
          </span>
          <p class="rk-status-title">通勤人口居住地口径生成中</p>
          <p class="rk-status-desc">旧数据的居住人口已归入常住人口；当前正按有 work 出行目的的人重新统计其居住地。</p>
        </div>

        <template v-else>
        <div class="rk-hero">
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
                :title="streetRowTitle(row)"
                @click="focusStreet(row.code)"
              >
                <span class="rk-rank-main">
                  <span class="rk-rank-name">
                    {{ row.name }}
                    <em v-if="showDistrictInRow" class="rk-rank-district">{{ row.district }}</em>
                  </span>
                  <span class="rk-rank-value">{{ formatInt(row.value) }}<i>人</i></span>
                  <span class="rk-rank-density">
                    {{ row.density == null ? "无数据" : formatInt(row.density) }}<i v-if="row.density != null">/km²</i>
                  </span>
                </span>
                <span class="rk-rank-bar" aria-hidden="true">
                  <span class="rk-rank-bar-fill" :style="{ width: row.barWidth }"></span>
                </span>
              </button>
            </li>
          </ol>
        </div>
        </template>
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
import { isDarkTheme } from "@/utils/uiTheme.js";
import {
  getCachedPopulationGrid,
  getCachedPopulationStreets,
  getCachedPopulationSummary,
  getModelDerived,
  invalidateCachedPopulationBundle,
} from "@/utils/modelDataCache.js";
import { fetchStreetsGeojsonOnce } from "../utils/streetsGeojson.js";
import { useDisplayRangeStore, DISPLAY_RANGE_ALL } from "@/stores/displayRange.js";
import {
  GRID_STREET_SENTINEL,
  buildDensityLegendItems,
  buildGridColors,
  buildGridElevations,
  buildGridPositions,
  parsePopulationGrid,
} from "../utils/populationGrid.js";

const props = defineProps({
  model: String,
  metric: {
    type: String,
    default: "resident",
  },
  threeDimensional: Boolean,
});

const MapRef = inject("MapRef", ref(null));
const rightPanelRankLimit = inject("rightPanelRankLimit", 10);

const METRIC_LABELS = {
  resident: "常住人口",
  home: "通勤人口居住地",
  work: "通勤人口就业地",
};
const SUBMODULE_NAMES = {
  resident: "常住人口分布",
  home: "通勤人口居住地分布",
  work: "通勤人口就业地分布",
};
const GRID_LAYER_KEY = "rm-population-grid";
const STREET_SOURCE_ID = "rm-population-streets";
const STREET_LINE_ID = "rm-population-street-line";
const STREET_LABEL_ID = "rm-population-street-label";
const GENERATING_POLL_MS = 8000;
const SIMULATION_POPULATION_CACHE_VERSION = "population-v11";
// 3D 柱高：P99 格高 1500m、最矮非零格 200m，平方根压缩高低差（见 buildGridElevations）。
const POPULATION_HEIGHT_REFERENCE = 1500;
const POPULATION_HEIGHT_MIN = 200;

const status = ref("loading"); // loading | generating | unsupported | error | ready
const errorMessage = ref("");
const metric = computed(() => (props.metric in METRIC_LABELS ? props.metric : "resident"));
const submoduleTitle = computed(() => SUBMODULE_NAMES[metric.value] || "人口分布监测");
const summary = shallowRef(null);
const streetStats = shallowRef(null); // { streets:[{code,name,district,areaKm2,home,work}], totals }
const grid = shallowRef(null); // parsePopulationGrid 结果（markRaw）
const streetsGeojson = shallowRef(null); // 模型无关街道面

const displayRange = useDisplayRangeStore();
const scopeLabel = computed(() => displayRange.selected || DISPLAY_RANGE_ALL);
const metricLabel = computed(() => METRIC_LABELS[metric.value] || "人口");
const isLegacySimulationPopulation = computed(() => (
  !isRealDatasourceModel(props.model)
  && summary.value?.cacheVersion !== SIMULATION_POPULATION_CACHE_VERSION
));
const metricRequiresUpgrade = computed(() => (
  isLegacySimulationPopulation.value && metric.value === "home"
));

const legendItems = buildDensityLegendItems(
  MAP_THEME.population.breaks,
  MAP_THEME.population.ramp,
  (v) => (v >= 1000 ? `${v / 1000}k` : String(v)),
);

function formatInt(value) {
  if (!Number.isFinite(value)) return "--";
  return Math.round(value).toLocaleString("zh-CN");
}

function isRealDatasourceModel(model) {
  return String(model || "").startsWith("real::");
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
  if (status.value !== "generating" && status.value !== "ready") status.value = "loading";
  errorMessage.value = "";

  getCachedPopulationSummary(model)
    .then((payload) => {
      if (seq !== requestSeq || props.model !== model) return null;
      if (!payload || payload.status === "generating") {
        status.value = "generating";
        schedulePoll();
        return null;
      }
      if (payload.status === "unsupported" || payload.status === "nodata") {
        summary.value = payload;
        errorMessage.value = payload.message || payload.reason || "缺少人口分布所需源数据";
        status.value = "unsupported";
        removeMapLayers();
        return null;
      }
      summary.value = payload;
      const version = String(payload.generatedAt || payload.cacheVersion || "");
      return Promise.all([
        getCachedPopulationGrid(model, version),
        getCachedPopulationStreets(model, version),
        fetchStreetsGeojsonOnce(),
      ]).then(([gridBuffer, streetsPayload, geojson]) => {
        if (seq !== requestSeq || props.model !== model) return null;
        if (!gridBuffer || !streetsPayload || streetsPayload.status === "generating") {
          status.value = "generating";
          schedulePoll();
          return null;
        }
        grid.value = getModelDerived(model, `populationGrid:${version}`, () => markRaw(parsePopulationGrid(gridBuffer)));
        streetStats.value = streetsPayload;
        streetsGeojson.value = geojson;
        status.value = "ready";
        refreshMapLayers();
        if (!isRealDatasourceModel(model) && payload.cacheVersion !== SIMULATION_POPULATION_CACHE_VERSION) {
          // 旧 home 仅作常住人口兼容展示；后台持续查询 v11，绝不把它冒充通勤居住地。
          invalidateCachedPopulationBundle(model);
          schedulePoll();
        }
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
// 街道榜单（随指标 / 显示范围联动；数值为模型原始人数）
// ---------------------------------------------------------------------------

const streetRows = computed(() => {
  const streets = streetStats.value?.streets;
  if (!Array.isArray(streets)) return [];
  const scope = scopeLabel.value;
  const key = metric.value;
  const rows = [];
  for (const street of streets) {
    if (scope !== DISPLAY_RANGE_ALL && street.district !== scope) continue;
    // v2 历史数据的 home=常住人口；只能作 resident 兼容，不得作通勤居住地。
    const rawValue = key === "resident" && street.resident == null
      ? street.home
      : street[key];
    const value = Number(rawValue) || 0;
    if (!value) continue;
    const areaKm2 = Number(street.areaKm2);
    rows.push({
      code: street.code,
      name: street.name,
      district: street.district,
      value,
      // 缺面积时不得默认除以 1km²，否则会生成看似正常的错误密度。
      density: Number.isFinite(areaKm2) && areaKm2 > 0 ? value / areaKm2 : null,
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

function streetRowTitle(row) {
  const prefix = `${row.name}（${row.district}）：${metricLabel.value} ${formatInt(row.value)} 人，占${scopeLabel.value} ${row.shareText}`;
  return row.density == null
    ? `${prefix}，密度无数据（缺少有效街道面积）`
    : `${prefix}，密度 ${formatInt(row.density)} 人/km²`;
}

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
// 地图图层：deck 栅格 + maplibre 街道边界/标注
// ---------------------------------------------------------------------------

function gridLayerInstance() {
  const data = grid.value;
  const model = props.model;
  if (!data || !model || metricRequiresUpgrade.value) return null;
  const positions = getModelDerived(model, "populationGridPositions", () => markRaw(buildGridPositions(data)));
  const counts = metric.value === "home"
    ? data.home
    : metric.value === "work" ? data.work : (data.resident || data.home);
  if (!counts) return null;
  const baseColors = getModelDerived(model, `populationGridColors:${metric.value}`, () =>
    markRaw(buildGridColors(counts, MAP_THEME.population)),
  );
  const baseElevations = getModelDerived(model, `populationGridElevations:q99pow-v3:${metric.value}`, () =>
    markRaw(buildGridElevations(counts, {
      referenceHeight: POPULATION_HEIGHT_REFERENCE,
      minHeight: POPULATION_HEIGHT_MIN,
    })),
  );
  // 行政区模式：区外/未命中街道的格子整体隐藏（临时副本，不入缓存避免按行政区累积内存）
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
    beforeId: STREET_LINE_ID, // 栅格压在街道描边/标注之下
    data: {
      length: data.count,
      attributes: {
        getPosition: { value: positions, size: 2 },
        getFillColor: { value: colors, size: 4 },
        getElevation: { value: elevations, size: 1 },
      },
    },
    // ×1.02：fp32 实例定位抖动会在整齐栅格上留出发丝缝，2% 重叠肉眼不可见（试验页已验证）
    cellSize: (Number(summary.value?.cellSizeMeters) > 0 ? Number(summary.value.cellSizeMeters) : 100) * 1.02,
    extruded: props.threeDimensional,
    elevationScale: 1,
    opacity: 1,
    pickable: false,
  });
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
  const isDark = isDarkTheme.value;
  const theme = MAP_THEME.population;
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
    else removeSharedDeckLayer(wrapper, GRID_LAYER_KEY);
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
watch(() => props.threeDimensional, () => refreshMapLayers());

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

/* ── 暗色模式（html.dark，跟随底图选择） ── */
html.dark .rk-rank-row:hover {
  background: rgba(64, 156, 255, 0.09);
}
html.dark .rk-rank-bar {
  background: rgba(148, 180, 220, 0.16);
}
html.dark .rk-map-legend {
  /* --app-ink-soft 未定义，浅色落在 fallback #475467，暗色需显式提亮 */
  color: #c2cddd;
}
html.dark .rk-status-icon.is-error {
  color: #f87171;
}
html.dark .rk-retry {
  background: #1a2431;
}
html.dark .rk-sk {
  background: linear-gradient(90deg, rgba(148, 180, 220, 0.08) 25%, rgba(148, 180, 220, 0.14) 42%, rgba(148, 180, 220, 0.08) 60%);
  background-size: 240% 100%;
}
</style>
