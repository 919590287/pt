// 临时调参脚本（勿提交）：真实 corridor-links.bin + names.json，
// 与 GJKL.vue 同源的范围过滤 / 分位锚定 / buildFlowPathData，滑杆实时调带宽观感。
import { Deck } from "@deck.gl/core";
import { LineLayer, PathLayer } from "@deck.gl/layers";
import { MAP_THEME } from "./src/utils/mapTheme.js";
import { CORRIDOR_U16_SENTINEL, buildFlowPathData, parseCorridorLinks } from "./src/views/datavisualization/utils/corridorLinks.js";
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

// —— 可调参数（初始值 = mapTheme 当前值） ——
const params = {
  scope: "南沙区", // 用户反馈"太粗"的场景优先
  maxWidthM: theme.maxWidthM,
  exponent: theme.exponent,
  refQuantile: theme.refQuantile,
  maxWidthPx: theme.maxWidthPx,
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
  if (!flowData.length) return [base];
  return [base, new PathLayer({
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
  })];
}

const deck = new Deck({
  canvas: "deck-canvas",
  initialViewState: { longitude: 113.53, latitude: 22.77, zoom: 10.6 },
  controller: true,
  layers: [],
});

function refresh() {
  deck.setProps({ layers: buildLayers() });
}

// —— HUD 控件：范围切换 + 四个滑杆 ——
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
hud.append(controls);

window.__deck = deck;
window.__set = (patch, view) => {
  Object.assign(params, patch);
  for (const input of controls.querySelectorAll("input")) input.dispatchEvent; // 滑杆显示不回写，以 info 为准
  refresh();
  if (view) deck.setProps({ initialViewState: null, viewState: view });
};
refresh();
