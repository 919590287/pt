// 临时调参脚本（勿提交）：真实 corridor-links.bin + names.json，
// 与 GJKL.vue 同源的范围过滤 / 分位锚定 / buildFlowPathData / Top10 道路名标注
//（selectVisibleRoadLabels 屏幕空间贪心避让，moveend 重算），滑杆实时调观感。
// 渲染路径对齐应用：maplibre 白底 + MapboxOverlay(interleaved:true)，与 deckOverlayRegistry 相同。
import maplibregl from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import { MapboxOverlay } from "@deck.gl/mapbox";
import { COORDINATE_SYSTEM } from "@deck.gl/core";
import { LineLayer, PathLayer, TextLayer } from "@deck.gl/layers";
import { MAP_THEME } from "./src/utils/mapTheme.js";
import {
  CORRIDOR_U16_SENTINEL,
  buildFlowPathData,
  buildFlowRoadLabelData,
  parseCorridorLinks,
  selectVisibleRoadLabels,
} from "./src/views/datavisualization/utils/corridorLinks.js";
import { mercatorToLngLat } from "./src/views/datavisualization/utils/populationGrid.js";

const theme = MAP_THEME.corridor.flow;
const hud = document.getElementById("hud");
const info = document.getElementById("info");

function hexToRgb(hex) {
  const value = Number.parseInt(hex.replace("#", ""), 16);
  return [(value >> 16) & 255, (value >> 8) & 255, value & 255];
}

const [buffer, namesPayload] = await Promise.all([
  fetch("/harness-corridor-links.bin").then((r) => r.arrayBuffer()),
  fetch("/harness-corridor-names.json").then((r) => r.json()),
]);
const data = parseCorridorLinks(buffer);
const districts = namesPayload.districts || [];
const names = namesPayload.names || [];

// —— 可调参数（初始值 = mapTheme 当前值） ——
const params = {
  scope: "南沙区", // 用户反馈"太粗"的场景优先
  maxWidthM: theme.maxWidthM,
  exponent: theme.exponent,
  refQuantile: theme.refQuantile,
  maxWidthPx: theme.maxWidthPx,
  labels: true,
  clusterSelfTest: false, // 全市洛溪聚簇的历史锚点回放：验证贪心避让只留最高名次
};

// 复刻 GJKL：范围过滤 + 流量升序 + 正流量分位
function scopeIndexes() {
  const out = [];
  for (let k = 0; k < data.count; k++) {
    if (params.scope !== "全市") {
      const s = data.street[k];
      if (s === CORRIDOR_U16_SENTINEL || districts[s] !== params.scope) continue;
    }
    out.push(k);
  }
  out.sort((a, b) => data.flow[a] - data.flow[b]);
  return out;
}

function refFlowOf(indexes) {
  let firstPositive = -1;
  for (let i = 0; i < indexes.length; i++) {
    if (data.flow[indexes[i]] > 0) { firstPositive = i; break; }
  }
  if (firstPositive < 0) return 0;
  const positiveCount = indexes.length - firstPositive;
  const at = firstPositive + Math.min(positiveCount - 1, Math.floor(params.refQuantile * (positiveCount - 1)));
  return data.flow[indexes[at]];
}

// 复刻 GJKL roadAggs + rankRows：按道路名分组取最高断面客流，Top10
function rankRowsOf(indexes) {
  const byName = new Map();
  for (const k of indexes) {
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
  const rows = [];
  for (const [nameIdx, agg] of byName) {
    if (agg.flow <= 0) continue;
    rows.push({ nameIdx, name: names[nameIdx] || `道路${nameIdx}`, flow: agg.flow, segments: agg.segments });
  }
  rows.sort((a, b) => b.flow - a.flow || b.segments - a.segments || a.name.localeCompare(b.name, "zh-CN"));
  return rows.slice(0, 10);
}

const ROAD_LABEL_PIXEL_OFFSET = [0, -8];

// 与 GJKL.roadLabelLayerInstance 同款配置（改这里请同步改 GJKL.vue）
function roadLabelTextLayer(id, labelData, color) {
  const labelTheme = theme.label;
  const visible = selectVisibleRoadLabels(labelData, (position) => map.project(position), {
    sizePx: labelTheme.sizePx,
    pixelOffset: ROAD_LABEL_PIXEL_OFFSET,
    paddingPx: labelTheme.paddingPx,
  });
  if (!visible.length) return null;
  const characterSet = new Set();
  for (const item of visible) for (const ch of item.name) characterSet.add(ch);
  return new TextLayer({
    id,
    data: visible,
    coordinateSystem: COORDINATE_SYSTEM.LNGLAT,
    getPosition: (item) => item.position,
    getText: (item) => item.name,
    getSize: labelTheme.sizePx,
    getColor: color || [...hexToRgb(labelTheme.color), 240],
    getTextAnchor: "middle",
    getAlignmentBaseline: "center",
    getPixelOffset: ROAD_LABEL_PIXEL_OFFSET,
    fontFamily: "system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', sans-serif",
    fontWeight: 600,
    characterSet: [...characterSet],
    fontSettings: { sdf: true, fontSize: 64, buffer: 6, radius: 12 },
    outlineWidth: 2,
    outlineColor: [...hexToRgb(labelTheme.halo), 235],
    sizeUnits: "pixels",
    billboard: true,
    pickable: false,
    parameters: { depthWriteEnabled: false, depthCompare: "always" },
  });
}

function labelLayerOf(indexes) {
  const labelData = buildFlowRoadLabelData(data, indexes, rankRowsOf(indexes));
  window.__labels = labelData; // 调试观察
  if (!labelData.length) return null;
  return roadLabelTextLayer("flow-road-labels", labelData);
}

function buildLayers() {
  const indexes = scopeIndexes();
  const refFlow = refFlowOf(indexes);
  const count = indexes.length;
  const source = new Float64Array(count * 2);
  const target = new Float64Array(count * 2);
  for (let i = 0; i < count; i++) {
    const k = indexes[i];
    const [lng1, lat1] = mercatorToLngLat(data.x1[k], data.y1[k]);
    const [lng2, lat2] = mercatorToLngLat(data.x2[k], data.y2[k]);
    source[i * 2] = lng1; source[i * 2 + 1] = lat1;
    target[i * 2] = lng2; target[i * 2 + 1] = lat2;
  }
  const base = new LineLayer({
    id: "flow-base",
    data: { length: count, attributes: {
      getSourcePosition: { value: source, size: 2 },
      getTargetPosition: { value: target, size: 2 },
    } },
    getColor: [...hexToRgb(theme.baseColor), theme.baseAlpha],
    getWidth: theme.baseWidthPx,
    widthUnits: "pixels",
    pickable: false,
  });
  const flowData = buildFlowPathData(data, indexes, {
    refFlow,
    maxWidthM: params.maxWidthM,
    exponent: params.exponent,
  });
  const maxFlow = indexes.length ? data.flow[indexes[indexes.length - 1]] : 0;
  const topWidthM = refFlow > 0 ? Math.pow(maxFlow / refFlow, params.exponent) * params.maxWidthM : 0;
  info.textContent = `${params.scope} · ${count} 段 · refFlow ${refFlow} · maxFlow ${maxFlow} · 顶带 ${Math.round(topWidthM)}m`;
  const layers = [base];
  if (flowData.length) {
    layers.push(new PathLayer({
      id: "flow-band",
      data: { length: flowData.length, startIndices: flowData.startIndices, attributes: {
        getPath: { value: flowData.positions, size: 2 },
        getWidth: { value: flowData.widths, size: 1 },
      } },
      _pathType: "open",
      getColor: [...hexToRgb(theme.color), 255],
      widthUnits: "meters",
      capRounded: true,
      jointRounded: true,
      widthMinPixels: theme.minWidthPx,
      widthMaxPixels: params.maxWidthPx,
      pickable: false,
    }));
  }
  if (params.labels) {
    const labelLayer = labelLayerOf(indexes);
    if (labelLayer) layers.push(labelLayer);
  }
  if (params.clusterSelfTest) {
    const layer = roadLabelTextLayer("cluster-self-test", CLUSTER_CASE, [200, 30, 30, 255]);
    if (layer) layers.push(layer);
  }
  return layers;
}

// 聚簇自检（红字）：全市真实 Top10 洛溪聚簇的历史锚点（deck CollisionFilterExtension
// 曾在此叠字）+ 孤立对照。预期：聚簇只显示"如意一马路"，迎宾路视缩放补显，孤标签恒显。
const CLUSTER_CASE = [
  { name: "如意一马路", rank: 1, position: [113.2995, 23.0439] },
  { name: "洛浦路", rank: 2, position: [113.2957, 23.0474] },
  { name: "如意三马路", rank: 3, position: [113.2958, 23.0451] },
  { name: "吉祥北街", rank: 4, position: [113.2983, 23.0441] },
  { name: "如意路", rank: 6, position: [113.3008, 23.0459] },
  { name: "迎宾路", rank: 10, position: [113.3151, 23.0384] },
  { name: "丁孤立标签", rank: 5, position: [113.36, 23.01] },
];

const map = new maplibregl.Map({
  container: "map",
  style: {
    version: 8,
    sources: {},
    layers: [{ id: "bg", type: "background", paint: { "background-color": "#ffffff" } }],
  },
  center: [113.53, 22.77],
  zoom: 10.6,
  attributionControl: false,
});
const overlay = new MapboxOverlay({ interleaved: true, layers: [] });
map.addControl(overlay);

function refresh() {
  overlay.setProps({ layers: buildLayers() });
}

// —— HUD 控件：范围切换 + 四个滑杆 + 标注开关 ——
const controls = document.createElement("div");
controls.style.cssText = "margin-top:6px;display:grid;grid-template-columns:auto 1fr auto;gap:4px 8px;align-items:center;min-width:300px";
function addSlider(label, key, min, max, step) {
  const name = document.createElement("span");
  name.textContent = label;
  const input = document.createElement("input");
  input.type = "range";
  input.min = min; input.max = max; input.step = step;
  input.value = params[key];
  const value = document.createElement("span");
  value.textContent = params[key];
  value.style.cssText = "font-variant-numeric:tabular-nums;min-width:44px;text-align:right";
  input.oninput = () => {
    params[key] = Number(input.value);
    value.textContent = input.value;
    refresh();
  };
  controls.append(name, input, value);
}
const scopeSel = document.createElement("select");
for (const s of ["南沙区", "全市", ...new Set(districts.filter(Boolean))].filter((v, i, a) => a.indexOf(v) === i)) {
  const opt = document.createElement("option");
  opt.value = s; opt.textContent = s;
  scopeSel.append(opt);
}
scopeSel.value = params.scope;
scopeSel.onchange = () => { params.scope = scopeSel.value; refresh(); };
const scopeLabel = document.createElement("span");
scopeLabel.textContent = "范围";
const scopeCell = document.createElement("span");
scopeCell.append(scopeSel);
controls.append(scopeLabel, scopeCell, document.createElement("span"));
addSlider("maxWidthM", "maxWidthM", 100, 1600, 25);
addSlider("exponent", "exponent", 0.3, 1, 0.05);
addSlider("refQuantile", "refQuantile", 0.9, 1, 0.005);
addSlider("maxWidthPx", "maxWidthPx", 8, 80, 2);
function addToggle(label, key) {
  const toggle = document.createElement("input");
  toggle.type = "checkbox";
  toggle.checked = params[key];
  toggle.onchange = () => { params[key] = toggle.checked; refresh(); };
  const name = document.createElement("span");
  name.textContent = label;
  const cell = document.createElement("span");
  cell.append(toggle);
  controls.append(name, cell, document.createElement("span"));
}
addToggle("Top10标注", "labels");
addToggle("聚簇自检", "clusterSelfTest");
hud.append(controls);

window.__map = map;
window.__overlay = overlay;
window.__set = (patch, view) => {
  Object.assign(params, patch);
  refresh();
  if (view) map.jumpTo({ center: [view.longitude, view.latitude], zoom: view.zoom });
};
map.on("load", refresh);
// 标注避让基于屏幕坐标：缩放/平移结束后重算
map.on("moveend", refresh);
// 兜底：容器尺寸就绪竞态下 load 可能迟迟不触发（overlay.setProps 幂等，双触发无害）
refresh();
requestAnimationFrame(() => { map.resize(); refresh(); });
