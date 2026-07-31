<!-- 公交客流走廊（客流走廊监测模块第二子模块）
     地图：整个公交线网的断面客流带宽图（rm-corridor-flow 双层：灰色细底网 LineLayer
     垫底 + 正流量橙色 PathLayer 压顶），断面口径与线路重复系数一致——双向路网按
     无向节点对合并的物理路段，经过同一断面的全部公交线路客流叠加；
     带宽为地理米数∝流量（圆头端帽衔接成连续流量带，不透明单色橙，
     对齐业务 Transit Flows 样张）；零流量路段只出现在灰色底网（=公交线网轮廓）。
     右侧：仿真模式展示断面客流排名前十的道路（路网名称字段）+ 最高断面客流，真实模式仅展示摘要；
     地图同步标注这些 Top10 道路名称，标注点位于各道路最高客流代表路段上，
     重叠时按名次做屏幕空间贪心避让（低名次隐藏，缩放/平移结束重算补显）；
     图例浮在地图左下角（分级宽度示意，结构同客流分析地图图例）。
     数据与线路重复系数共用 corridor 缓存工件（PCRD v2 的 flow 列，模型原始人次直出）。 -->
<template>
  <teleport to="#datavisualization_index_box2" defer>
    <div class="gkl-card" aria-label="公交客流走廊面板">
      <div class="gkl-title">
        <h2>公交客流走廊</h2>
      </div>

      <!-- 状态机：生成中 / 加载 / 失败 整块替换正文，避免状态浮在 0 值上 -->
      <div v-if="status === 'generating'" class="gkl-status" role="status">
        <span class="gkl-status-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
            <circle cx="12" cy="12" r="9"></circle>
            <polyline points="12 7 12 12 15.5 14"></polyline>
          </svg>
        </span>
        <p class="gkl-status-title">走廊分析缓存生成中</p>
        <p class="gkl-status-desc">后端正在为当前模型叠加断面客流，就绪后将自动展示。</p>
      </div>

      <div v-else-if="status === 'error'" class="gkl-status" role="alert">
        <span class="gkl-status-icon is-error" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">
            <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path>
            <line x1="12" y1="9" x2="12" y2="13"></line>
            <line x1="12" y1="17" x2="12.01" y2="17"></line>
          </svg>
        </span>
        <p class="gkl-status-title">走廊数据加载失败</p>
        <p class="gkl-status-desc">{{ errorMessage }}</p>
        <button type="button" class="gkl-retry" @click="bootstrap">重新加载</button>
      </div>

      <div v-else-if="status === 'loading'" class="gkl-skeleton" aria-hidden="true">
        <div class="gkl-sk gkl-sk-hero"></div>
        <template v-if="!isRealMode">
          <div class="gkl-sk gkl-sk-row" v-for="n in 8" :key="n"></div>
        </template>
      </div>

      <template v-else>
        <template v-if="!isRealMode">
          <div v-if="!rankRows.length" class="gkl-status" role="status">
            <span class="gkl-status-icon" aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
                <path d="M12 21s7-5.2 7-11a7 7 0 1 0-14 0c0 5.8 7 11 7 11Z"></path>
                <circle cx="12" cy="10" r="2.5"></circle>
              </svg>
            </span>
            <p class="gkl-status-title">{{ scopeLabel }}范围内暂无{{ corridorUnitLabel }}</p>
            <p class="gkl-status-desc">该范围内没有可用于排名的公交{{ corridorUnitLabel }}，可切换显示范围。</p>
          </div>

          <div v-else class="gkl-road-rank">
            <div class="gkl-rank-head" aria-hidden="true">
              <span class="gkl-rank-head-name">{{ corridorUnitLabel }}</span>
              <span class="gkl-rank-head-value">客流</span>
            </div>
            <ol class="gkl-rank-list">
              <li v-for="row in rankRows" :key="row.nameIdx">
                <button
                  type="button"
                  class="gkl-rank-row"
                  :title="`${row.name}：最高断面客流 ${formatInt(row.flow)} 人次（${scopeLabel}内 ${row.segments} 段公交路段）`"
                  @click="focusRoad(row.nameIdx)"
                >
                  <span class="gkl-rank-main">
                    <span class="gkl-rank-name">{{ row.name }}</span>
                    <span class="gkl-rank-value">{{ formatInt(row.flow) }} <em class="gkl-rank-unit">人次/日</em></span>
                  </span>
                  <span class="gkl-rank-bar" aria-hidden="true">
                    <span class="gkl-rank-bar-fill" :style="{ width: row.barWidth }"></span>
                  </span>
                </button>
              </li>
            </ol>
          </div>
        </template>
      </template>
    </div>
  </teleport>

  <!-- 断面客流图例：分级宽度示意（地图左下角浮动）；pageActive 防止 KeepAlive 切页后残留 -->
  <teleport to="body">
    <div
      v-if="status === 'ready' && pageActive && legendItems.length"
      class="gkl-map-legend"
      aria-label="断面客流图例（人次）"
      title="断面=双向合并的物理路段，客流为经过该断面全部公交线路的模型原始人次叠加"
      @click.stop
    >
      <div class="gkl-map-legend-head">
        <span class="gkl-map-legend-title">断面客流（人次）</span>
      </div>
      <div v-for="item in legendItems" :key="item.label" class="gkl-map-legend-item">
        <span
          class="gkl-map-legend-swatch"
          :style="{ background: item.color, height: `${Math.max(1.5, Math.min(22, item.width))}px` }"
          aria-hidden="true"
        ></span>
        <span class="gkl-map-legend-label">{{ item.label }}</span>
      </div>
    </div>
  </teleport>
</template>

<script setup>
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, shallowRef, watch, inject, markRaw } from "vue";
import { COORDINATE_SYSTEM } from "@deck.gl/core";
import { LineLayer, PathLayer, TextLayer } from "@deck.gl/layers";
import {
  batchSharedDeckLayerUpdates,
  setSharedDeckLayer,
  removeSharedDeckLayer,
} from "../layers/deckOverlayRegistry.js";
import { MAP_THEME } from "@/utils/mapTheme.js";
import {
  getCachedCorridorLinks,
  getCachedCorridorNames,
  getCachedCorridorSummary,
  getModelDerived,
} from "@/utils/modelDataCache.js";
import { useDisplayRangeStore, DISPLAY_RANGE_ALL } from "@/stores/displayRange.js";
import { mercatorToLngLat } from "../utils/populationGrid.js";
import {
  CORRIDOR_U16_SENTINEL,
  buildFlowPathData,
  buildFlowRoadLabelData,
  parseCorridorLinks,
  selectVisibleRoadLabels,
} from "../utils/corridorLinks.js";
import { isRealDatasource } from "@/utils/realPassengerFlow.js";

const props = defineProps({
  model: String,
});

const MapRef = inject("MapRef", ref(null));
const rightPanelRankLimit = inject("rightPanelRankLimit", 10);

const FLOW_LAYER_KEY = "rm-corridor-flow";
const ROAD_LABEL_LAYER_KEY = `${FLOW_LAYER_KEY}-road-labels`;
// 压在专题客流线层（0）之上，低于站点名称（1005）。
const ROAD_LABEL_LAYER_ORDER = 1002;
const GENERATING_POLL_MS = 8000;

const status = ref("loading"); // loading | generating | error | ready
const errorMessage = ref("");
const summary = shallowRef(null);
const namesPayload = shallowRef(null); // { names:[...], districts:[176] }
const links = shallowRef(null); // parseCorridorLinks 结果（markRaw；与线路重复系数共用缓存）

const displayRange = useDisplayRangeStore();
const scopeLabel = computed(() => displayRange.selected || DISPLAY_RANGE_ALL);
const isRealMode = computed(() => isRealDatasource(props.model));
const corridorUnitLabel = computed(() => isRealMode.value ? "站间区间" : "道路");

function formatInt(value) {
  if (!Number.isFinite(value)) return "--";
  return Math.round(value).toLocaleString("zh-CN");
}

// ---------------------------------------------------------------------------
// 数据加载：summary（generating 轮询）→ names + links.bin 并行（与 CFXS 共用缓存请求）
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
        links.value = getModelDerived(model, "corridorLinks", () => markRaw(parseCorridorLinks(linksBuffer)));
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

/** 当前范围内的段下标，按断面客流升序（绘制序：大流量后画压顶）。 */
const scopeSegmentIndexes = computed(() => {
  const data = links.value;
  if (!data) return [];
  const indexes = [];
  for (let k = 0; k < data.count; k++) {
    if (segmentInScope(k)) indexes.push(k);
  }
  indexes.sort((a, b) => data.flow[a] - data.flow[b]);
  return indexes;
});

const scopeSegmentCount = computed(() => scopeSegmentIndexes.value.length);
const scopeMaxFlow = computed(() => {
  const data = links.value;
  const indexes = scopeSegmentIndexes.value;
  // 流量升序：范围内最后一个即最大
  return indexes.length ? data.flow[indexes[indexes.length - 1]] : 0;
});

/**
 * 宽度锚定流量：当前范围内**正流量**的 refQuantile 分位（默认 P95），超过封顶最大宽度。
 * 长尾分布下用最大值做分母会让个别极端断面把其余路段全部压成发丝线（样张观感做不出来）。
 */
const scopeFlowRef = computed(() => {
  const data = links.value;
  const indexes = scopeSegmentIndexes.value;
  if (!data || !indexes.length) return 0;
  // 索引已按流量升序：先跳过零流量段，再取正流量子集的分位
  let firstPositive = -1;
  for (let i = 0; i < indexes.length; i++) {
    if (data.flow[indexes[i]] > 0) {
      firstPositive = i;
      break;
    }
  }
  if (firstPositive < 0) return 0;
  const quantile = MAP_THEME.corridor.flow.refQuantile ?? 1;
  const positiveCount = indexes.length - firstPositive;
  const at = firstPositive + Math.min(positiveCount - 1, Math.floor(quantile * (positiveCount - 1)));
  return data.flow[indexes[at]];
});

// ---------------------------------------------------------------------------
// Top10 道路榜（按道路名分组取最高断面客流；无名路段只上图不上榜）
// ---------------------------------------------------------------------------

const roadAggs = computed(() => {
  const data = links.value;
  if (!data) return new Map();
  const byName = new Map(); // nameIdx → { flow:max, segments }
  for (const k of scopeSegmentIndexes.value) {
    const nameIdx = data.nameIdx[k];
    if (nameIdx === CORRIDOR_U16_SENTINEL) continue;
    const agg = byName.get(nameIdx);
    if (agg) {
      agg.segments += 1;
      if (data.flow[k] > agg.flow) agg.flow = data.flow[k];
    } else {
      byName.set(nameIdx, { flow: data.flow[k], segments: 1 });
    }
  }
  return byName;
});

const namedRoadCount = computed(() => roadAggs.value.size);

const rankRows = computed(() => {
  const rows = [];
  for (const [nameIdx, agg] of roadAggs.value) {
    if (agg.flow <= 0) continue; // 零客流道路不上榜
    rows.push({ nameIdx, name: roadNames.value[nameIdx] || `道路${nameIdx}`, flow: agg.flow, segments: agg.segments });
  }
  rows.sort((a, b) => b.flow - a.flow || b.segments - a.segments || a.name.localeCompare(b.name, "zh-CN"));
  const top = rows.slice(0, rightPanelRankLimit);
  const maxFlow = top.length ? top[0].flow : 0;
  return top.map((row) => ({
    ...row,
    barWidth: `${maxFlow > 0 ? Math.max(2, (row.flow / maxFlow) * 100) : 0}%`,
  }));
});

/** 右侧 Top10 在地图上的一对一标注数据。 */
const roadLabelData = computed(() => buildFlowRoadLabelData(
  links.value,
  scopeSegmentIndexes.value,
  rankRows.value,
));

/** TextLayer 默认字符集只有 ASCII，必须把当前道路名的中文字形显式加入图集。 */
const roadLabelCharacterSet = computed(() => {
  const characters = new Set();
  for (const item of roadLabelData.value) {
    for (const character of item.name) characters.add(character);
  }
  return [...characters];
});

/** 标注锚点的像素上抬量（同时参与避让盒计算，改动须两处同步）。 */
const ROAD_LABEL_PIXEL_OFFSET = [0, -8];

// ---------------------------------------------------------------------------
// 地图图层：灰色细底网（LineLayer，全部公交路段）+ 正流量橙色流量带
// （PathLayer 米制宽度∝流量、圆头衔接、不透明压顶），对齐 Transit Flows 样张
// ---------------------------------------------------------------------------

function hexToRgb(hex) {
  const value = Number.parseInt(hex.replace("#", ""), 16);
  return [(value >> 16) & 255, (value >> 8) & 255, value & 255];
}

/** 图例条高（px）：带宽是随缩放变化的地理米数，图例只做同一幂映射下的相对宽度示意。 */
const LEGEND_MAX_PX = 20;

/** 分级宽度图例：锚定流量的 1 / 1/2 / 1/4 / 1/8（去重），顶档标注 ≥；相对宽度与地图同幂映射。 */
const legendItems = computed(() => {
  const refFlow = scopeFlowRef.value;
  if (!(refFlow > 0)) return [];
  const theme = MAP_THEME.corridor.flow;
  const items = [];
  const seen = new Set();
  for (const fraction of [1, 0.5, 0.25, 0.125]) {
    const value = Math.round(refFlow * fraction);
    if (value <= 0 || seen.has(value)) continue;
    seen.add(value);
    items.push({
      label: fraction === 1 ? `≥ ${formatInt(value)}` : formatInt(value),
      color: theme.color,
      width: Math.pow(fraction, theme.exponent ?? 1) * LEGEND_MAX_PX,
    });
  }
  return items;
});

function flowLayerInstance() {
  const data = links.value;
  const indexes = scopeSegmentIndexes.value;
  if (!data || !indexes.length) return null;
  const theme = MAP_THEME.corridor.flow;
  const refFlow = scopeFlowRef.value;

  // 底网：范围内全部公交路段的等宽细灰线（零流量路段只出现在这层，保住线网轮廓）
  const count = indexes.length;
  const source = new Float64Array(count * 2);
  const target = new Float64Array(count * 2);
  for (let i = 0; i < count; i++) {
    const k = indexes[i];
    const [lng1, lat1] = mercatorToLngLat(data.x1[k], data.y1[k]);
    const [lng2, lat2] = mercatorToLngLat(data.x2[k], data.y2[k]);
    source[i * 2] = lng1;
    source[i * 2 + 1] = lat1;
    target[i * 2] = lng2;
    target[i * 2 + 1] = lat2;
  }
  const baseLayer = new LineLayer({
    id: `${FLOW_LAYER_KEY}-base`,
    data: {
      length: count,
      attributes: {
        getSourcePosition: { value: source, size: 2 },
        getTargetPosition: { value: target, size: 2 },
      },
    },
    getColor: [...hexToRgb(theme.baseColor), theme.baseAlpha],
    getWidth: theme.baseWidthPx,
    widthUnits: "pixels",
    pickable: false,
  });

  // 流量带：indexes 已按流量升序 → 大流量后画压顶，节点处宽段圆头盖住窄段圆头
  const flowData = buildFlowPathData(data, indexes, {
    refFlow,
    maxWidthM: theme.maxWidthM,
    exponent: theme.exponent ?? 1,
  });
  if (!flowData.length) return [baseLayer];
  const flowLayer = new PathLayer({
    id: FLOW_LAYER_KEY,
    data: {
      length: flowData.length,
      startIndices: flowData.startIndices,
      attributes: {
        getPath: { value: flowData.positions, size: 2 },
        getWidth: { value: flowData.widths, size: 1 },
      },
    },
    _pathType: "open",
    getColor: [...hexToRgb(theme.color), 255],
    widthUnits: "meters",
    capRounded: true,
    jointRounded: true,
    widthMinPixels: theme.minWidthPx,
    widthMaxPixels: theme.maxWidthPx,
    pickable: false,
  });
  return [baseLayer, flowLayer];
}

function roadLabelLayerInstance() {
  const allLabels = roadLabelData.value;
  const map = MapRef.value?.map;
  if (!allLabels.length || typeof map?.project !== "function") return null;
  const labelTheme = MAP_THEME.corridor.flow.label;
  // 屏幕空间贪心避让（selectVisibleRoadLabels 注释里有为何不用 CollisionFilterExtension）；
  // 视野变化后由 moveend 监听重建本层补显/让位。
  const data = selectVisibleRoadLabels(allLabels, (position) => map.project(position), {
    sizePx: labelTheme.sizePx,
    pixelOffset: ROAD_LABEL_PIXEL_OFFSET,
    paddingPx: labelTheme.paddingPx,
  });
  if (!data.length) return null;
  return new TextLayer({
    id: ROAD_LABEL_LAYER_KEY,
    data,
    coordinateSystem: COORDINATE_SYSTEM.LNGLAT,
    getPosition: (item) => item.position,
    getText: (item) => item.name,
    getSize: labelTheme.sizePx,
    getColor: [...hexToRgb(labelTheme.color), 240],
    getTextAnchor: "middle",
    getAlignmentBaseline: "center",
    getPixelOffset: ROAD_LABEL_PIXEL_OFFSET,
    fontFamily: "system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', sans-serif",
    fontWeight: 600,
    characterSet: roadLabelCharacterSet.value,
    fontSettings: { sdf: true, fontSize: 64, buffer: 6, radius: 12 },
    outlineWidth: 2,
    outlineColor: [...hexToRgb(labelTheme.halo), 235],
    sizeUnits: "pixels",
    billboard: true,
    pickable: false,
    parameters: {
      // luma.gl v9 写法（等价旧 depthTest:false）：压在流量带上不受深度影响
      depthWriteEnabled: false,
      depthCompare: "always",
    },
  });
}

/** 只重建标注层（moveend 避让重算用），不动重得多的流量带层。 */
function refreshRoadLabelLayer() {
  if (status.value !== "ready" || !pageActive.value) return;
  const wrapper = MapRef.value;
  if (!wrapper?.map) return;
  const labelLayer = roadLabelLayerInstance();
  if (labelLayer) setSharedDeckLayer(wrapper, ROAD_LABEL_LAYER_KEY, labelLayer, ROAD_LABEL_LAYER_ORDER);
  else removeSharedDeckLayer(wrapper, ROAD_LABEL_LAYER_KEY);
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
    const layer = flowLayerInstance();
    const labelLayer = roadLabelLayerInstance();
    batchSharedDeckLayerUpdates(() => {
      if (layer) setSharedDeckLayer(wrapper, FLOW_LAYER_KEY, layer, 0);
      else removeSharedDeckLayer(wrapper, FLOW_LAYER_KEY);
      if (labelLayer) setSharedDeckLayer(wrapper, ROAD_LABEL_LAYER_KEY, labelLayer, ROAD_LABEL_LAYER_ORDER);
      else removeSharedDeckLayer(wrapper, ROAD_LABEL_LAYER_KEY);
    });
    attachLabelMoveListener(map);
    pendingLayerRefresh = false;
  } catch (error) {
    // 地图尚未就绪（样式加载中等）时静默，数据/范围变化会再次触发
    pendingLayerRefresh = true;
    console.warn("[公交客流走廊] 图层刷新失败，等待下次触发", error);
  }
}

// 标注避让基于屏幕坐标，缩放/平移结束后需重算（moveend 覆盖 zoom/pan 手势收尾）。
// 只在页面激活期挂监听：失活摘除，与"失活期间不动共享地图"的 KeepAlive 契约一致。
let labelMoveListenerMap = null;
function attachLabelMoveListener(map) {
  if (!map?.on || labelMoveListenerMap === map) return;
  detachLabelMoveListener();
  map.on("moveend", refreshRoadLabelLayer);
  labelMoveListenerMap = map;
}
function detachLabelMoveListener() {
  if (labelMoveListenerMap?.off) labelMoveListenerMap.off("moveend", refreshRoadLabelLayer);
  labelMoveListenerMap = null;
}

function removeMapLayers() {
  batchSharedDeckLayerUpdates(() => {
    removeSharedDeckLayer(MapRef.value, FLOW_LAYER_KEY);
    removeSharedDeckLayer(MapRef.value, ROAD_LABEL_LAYER_KEY);
  });
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
  if (pendingLayerRefresh) {
    refreshMapLayers();
  } else if (status.value === "ready") {
    // 失活期间摘掉了 moveend 监听、地图也可能被其他页面移动过：恢复监听并重算标注避让
    const map = MapRef.value?.map;
    if (map) {
      attachLabelMoveListener(map);
      refreshRoadLabelLayer();
    }
  }
});

onDeactivated(() => {
  pageActive.value = false;
  detachLabelMoveListener();
});

onUnmounted(() => {
  requestSeq += 1;
  clearTimeout(pollTimer);
  detachLabelMoveListener();
  removeMapLayers();
});
</script>

<style lang="scss" scoped>
/* teleport 节点带本组件 scope，样式自持（同 CFXS），视觉语言对齐 index.vue 的 rm- 面板体系 */
.gkl-card {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  width: 100%;
  box-sizing: border-box;
}

.gkl-title {
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

.gkl-scope {
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
.gkl-hero {
  margin-top: 14px;
}

.gkl-hero-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.gkl-hero-label {
  color: var(--dm2-muted);
  font-size: 12px;
  font-weight: 700;
}

.gkl-hero-value {
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

.gkl-hero-sub {
  margin: 5px 0 0;
  color: var(--dm2-muted);
  font-size: 11px;
  font-weight: 640;
  font-variant-numeric: tabular-nums;
}

/* —— 道路榜单 —— */
.gkl-road-rank {
  display: flex;
  flex-direction: column;
  min-height: 0;
  margin-top: 14px;
}

.gkl-rank-head {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 0 2px 6px;
  border-bottom: 1px solid var(--dm2-line-faint);
  color: var(--dm2-muted);
  font-size: 10.5px;
  font-weight: 700;
}

.gkl-rank-head-name {
  flex: 1;
  min-width: 0;
}

.gkl-rank-head-value {
  flex-shrink: 0;
  width: 116px;
  text-align: right;
}

.gkl-rank-list {
  margin: 0;
  padding: 0;
  list-style: none;
}

.gkl-rank-row {
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

.gkl-rank-main {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.gkl-rank-name {
  flex: 1;
  min-width: 0;
  color: var(--dm2-ink);
  font-size: 12.5px;
  font-weight: 720;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.gkl-rank-value {
  flex-shrink: 0;
  width: 116px;
  text-align: right;
  color: var(--dm2-ink);
  font-size: 12.5px;
  font-weight: 760;
  font-variant-numeric: tabular-nums;
}

.gkl-rank-unit {
  margin-left: 3px;
  color: var(--dm2-muted);
  font-size: 10.5px;
  font-style: normal;
  font-weight: 600;
}

.gkl-rank-bar {
  display: block;
  height: 4px;
  margin-top: 6px;
  border-radius: 999px;
  background: rgba(240, 140, 60, 0.16);
  overflow: hidden;
}

.gkl-rank-bar-fill {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: #f08c3c; /* mapTheme corridor.flow.color 同源橙，单色编码流量 */
  transition: width 0.35s ease;
}

.gkl-rank-footnote {
  margin: 8px 2px 0;
  color: var(--dm2-muted);
  font-size: 10.5px;
  line-height: 1.4;
  font-weight: 620;
}

/* —— 地图左下角断面客流图例（与客流分析 map-flow-legend 同结构同定位） —— */
.gkl-map-legend {
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
  gap: 5px;
  font-size: 11px;
  color: var(--app-ink-soft, #475467);
  /* 拦截点击避免穿透到地图 */
  pointer-events: auto;
}

.gkl-map-legend-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 2px;
}

.gkl-map-legend-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--app-ink, #344054);
}

.gkl-map-legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.gkl-map-legend-swatch {
  width: 22px;
  border-radius: 3px;
  flex: none;
}

.gkl-map-legend-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

/* —— 状态与骨架 —— */
.gkl-status {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 34px 12px;
  text-align: center;
}

.gkl-status-icon {
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

.gkl-status-title {
  margin: 0;
  color: var(--dm2-ink);
  font-size: 13.5px;
  font-weight: 760;
}

.gkl-status-desc {
  margin: 0;
  max-width: 260px;
  color: var(--dm2-muted);
  font-size: 11.5px;
  line-height: 1.5;
  font-weight: 640;
}

.gkl-retry {
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

.gkl-skeleton {
  padding: 14px 0;
}

.gkl-sk {
  border-radius: 8px;
  background: linear-gradient(90deg, rgba(28, 32, 36, 0.05) 25%, rgba(28, 32, 36, 0.1) 42%, rgba(28, 32, 36, 0.05) 60%);
  background-size: 240% 100%;
  animation: gkl-shimmer 1.4s ease-in-out infinite;
}

.gkl-sk-hero {
  height: 78px;
  margin-top: 12px;
}

.gkl-sk-row {
  height: 30px;
  margin-top: 9px;
}

@keyframes gkl-shimmer {
  0% {
    background-position: 130% 0;
  }

  100% {
    background-position: -110% 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .gkl-sk {
    animation: none;
  }

  .gkl-rank-bar-fill {
    transition: none;
  }
}

/* ── 暗色模式（html.dark，跟随底图选择） ── */
html.dark .gkl-rank-row:hover {
  background: rgba(64, 156, 255, 0.09);
}
html.dark .gkl-rank-bar {
  background: rgba(240, 140, 60, 0.2);
}
html.dark .gkl-map-legend {
  /* --app-ink-soft 未定义，浅色落在 fallback #475467，暗色需显式提亮 */
  color: #c2cddd;
}
html.dark .gkl-status-icon.is-error {
  color: #f87171;
}
html.dark .gkl-retry {
  background: #1a2431;
}
html.dark .gkl-sk {
  background: linear-gradient(90deg, rgba(148, 180, 220, 0.08) 25%, rgba(148, 180, 220, 0.14) 42%, rgba(148, 180, 220, 0.08) 60%);
  background-size: 240% 100%;
}
</style>
