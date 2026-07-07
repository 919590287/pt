/**
 * QGIS 风格分级色阶工具：
 * - 色系改用 d3-scale-chromatic 的 35 个标准色带，同一色系可采样任意档数（2~9 档）
 * - Mako / Rocket 不在 d3-scale-chromatic 内，内置锚点插值补齐
 * - 阈值用"占最大值的百分比"表示（升序断点数组，长度 = 档数-1）
 * - classify 将数值映射到档位，供地图着色与图例共用
 * - 每档附带线宽系数（客流越大线越粗），范围收敛避免粗细失衡
 */
import * as d3chromatic from "d3-scale-chromatic";
import { color as d3color } from "d3-color";

// 用户指定的 35 个 d3-scale-chromatic 色带（key 与 d3 interpolate<Name> 对应）
const D3_SCHEME_NAMES = [
  "Blues", "BrBG", "BuGn", "BuPu", "Cividis", "GnBu", "Greens", "Greys",
  "Inferno", "Magma", "Mako", "OrRd", "Oranges", "PRGn", "PiYG", "Plasma",
  "PuBu", "PuBuGn", "PuOr", "PuRd", "Purples", "RdBu", "RdGy", "RdPu",
  "RdYlBu", "RdYlGn", "Reds", "Rocket", "Spectral", "Turbo", "Viridis",
  "YlGn", "YlGnBu", "YlOrBr", "YlOrRd",
];

// Mako / Rocket（seaborn 色带，d3 未内置）：暗→亮锚点，线性插值补齐
const VENDOR_RAMPS = {
  Mako: ["#0b0405", "#1a1339", "#38226a", "#414082", "#3a5e9e", "#2c7fb2", "#2199b2", "#25b9a8", "#5bcfa2", "#aadbb4", "#def5e6"],
  Rocket: ["#03051a", "#241432", "#4b1d4e", "#7a1a4d", "#a71b41", "#cd4247", "#e56b4e", "#f2965a", "#f6bd85", "#f7ddba", "#faebdd"],
  // 交通语义绿→黄→红：替代 RdYlGn 反转（其正中点近白 #ffffbf，3 档时中档在浅色底图上不可见），
  // 全程高饱和，任意档数中档都是可辨的琥珀黄
  GnYlRd: ["#169a52", "#7cbe45", "#f2c037", "#ee8331", "#d7302a"],
};

// 旧的小写 key → 新 d3 key（含反向）：兼容历史持久化配置与旧调用点
const LEGACY_ALIAS = {
  ylorrd: { key: "YlOrRd" },
  gnylrd: { key: "GnYlRd" }, // 绿(低)→红(高)，内置高饱和锚点
  blues: { key: "Blues" },
  viridis: { key: "Viridis" },
  spectral: { key: "Spectral" },
  densityblue: { key: "Turbo" }, // 蓝→青→黄→红，近似人群密度专题图
};

// —— 采样域裁剪：浅色端在浅色底图上不可见，按色带类型避开近白区段 ——
// 发散型全程保留（两端都是深色语义端点）
const DIVERGING_SCHEMES = new Set(["BrBG", "PRGn", "PiYG", "PuOr", "RdBu", "RdGy", "RdYlBu", "RdYlGn", "Spectral"]);
// 感知均匀多色带：起点为深色，无需裁剪
const FULL_RANGE_SCHEMES = new Set(["Turbo", "Viridis", "Inferno", "Magma", "Plasma", "Cividis"]);
// 亮尾色带：终点近白，裁掉尾段
const PALE_END_SCHEMES = new Set(["Mako", "Rocket"]);
// ColorBrewer 顺序色带的近白起点裁剪量
const SEQUENTIAL_START = 0.14;

function schemeDomain(key) {
  if (DIVERGING_SCHEMES.has(key) || FULL_RANGE_SCHEMES.has(key)) return [0, 1];
  if (PALE_END_SCHEMES.has(key)) return [0, 0.92];
  if (typeof d3chromatic[`interpolate${key}`] === "function") return [SEQUENTIAL_START, 1];
  return [0, 1]; // 自定义锚点色带按原样采样
}

export const COLOR_SCHEMES = D3_SCHEME_NAMES.map((name) => ({ key: name, name }));

export const MIN_CLASS_COUNT = 2;
export const MAX_CLASS_COUNT = 9;

// 线宽系数区间：最低档 1.0，最高档 2.2（收敛，避免"客流越大越粗"到失衡）
const WIDTH_FACTOR_MIN = 1;
const WIDTH_FACTOR_MAX = 2.2;

function toHex(value) {
  const parsed = d3color(value);
  return parsed ? parsed.formatHex() : "#cccccc";
}

function hexToRgb(hex) {
  const value = String(hex || "").replace("#", "");
  return [
    parseInt(value.slice(0, 2), 16) || 0,
    parseInt(value.slice(2, 4), 16) || 0,
    parseInt(value.slice(4, 6), 16) || 0,
  ];
}

function rgbToHex([r, g, b]) {
  const to2 = (n) => Math.round(Math.max(0, Math.min(255, n))).toString(16).padStart(2, "0");
  return `#${to2(r)}${to2(g)}${to2(b)}`;
}

function sampleVendorRamp(anchors, t) {
  const clamped = Math.max(0, Math.min(1, t));
  const segments = anchors.length - 1;
  const pos = clamped * segments;
  const idx = Math.min(segments - 1, Math.floor(pos));
  const frac = pos - idx;
  const from = hexToRgb(anchors[idx]);
  const to = hexToRgb(anchors[idx + 1]);
  return rgbToHex([
    from[0] + (to[0] - from[0]) * frac,
    from[1] + (to[1] - from[1]) * frac,
    from[2] + (to[2] - from[2]) * frac,
  ]);
}

// 解析色系 key（兼容旧 key），返回 { interpolator, reverse }；interpolator 已按 schemeDomain 裁剪采样域
function resolveScheme(schemeKey) {
  const raw = String(schemeKey || "");
  const alias = LEGACY_ALIAS[raw.toLowerCase()];
  const key = alias?.key || raw;
  const aliasReverse = Boolean(alias?.reverse);
  const vendor = VENDOR_RAMPS[key];
  let base;
  let domainKey = key;
  if (vendor) {
    base = (t) => sampleVendorRamp(vendor, t);
  } else if (typeof d3chromatic[`interpolate${key}`] === "function") {
    base = d3chromatic[`interpolate${key}`];
  } else {
    // 兜底：黄橙红
    base = d3chromatic.interpolateYlOrRd;
    domainKey = "YlOrRd";
  }
  const [start, end] = schemeDomain(domainKey);
  const interpolator = start === 0 && end === 1 ? base : (t) => base(start + (end - start) * t);
  return { interpolator, reverse: aliasReverse };
}

function clampClassCount(classCount) {
  return Math.max(MIN_CLASS_COUNT, Math.min(MAX_CLASS_COUNT, Math.round(classCount) || MIN_CLASS_COUNT));
}

/** 从色系采样 classCount 档颜色（低→高）；reverse 反转色带方向 */
export function sampleScheme(schemeKey, classCount, reverse = false) {
  const { interpolator, reverse: aliasReverse } = resolveScheme(schemeKey);
  const flip = Boolean(reverse) !== aliasReverse;
  const count = clampClassCount(classCount);
  const colors = [];
  for (let i = 0; i < count; i++) {
    const t = count === 1 ? 0.5 : i / (count - 1);
    colors.push(toHex(interpolator(flip ? 1 - t : t)));
  }
  return colors;
}

/** 每档线宽系数（低→高，收敛区间），长度 = classCount */
export function sampleWidthFactors(classCount, min = WIDTH_FACTOR_MIN, max = WIDTH_FACTOR_MAX) {
  const count = clampClassCount(classCount);
  if (count === 1) return [min];
  const factors = [];
  for (let i = 0; i < count; i++) {
    factors.push(Math.round((min + (max - min) * (i / (count - 1))) * 1000) / 1000);
  }
  return factors;
}

/** 默认等分阈值（百分比断点，升序，长度=档数-1），如 5 档 → [20,40,60,80] */
export function defaultThresholds(classCount) {
  const count = clampClassCount(classCount);
  const thresholds = [];
  for (let i = 1; i < count; i++) {
    thresholds.push(Math.round((i * 100) / count));
  }
  return thresholds;
}

/** 规范化阈值：去重、裁剪到 (0,100)、升序 */
export function normalizeThresholds(thresholds) {
  const cleaned = [...new Set(
    (thresholds || [])
      .map((t) => Number(t))
      .filter((t) => Number.isFinite(t) && t > 0 && t < 100)
      .map((t) => Math.round(t * 10) / 10),
  )];
  cleaned.sort((a, b) => a - b);
  return cleaned;
}

/**
 * 按百分比阈值分档：value 占 maxValue 的百分比落在哪一档。
 * @returns 档位下标 0..thresholds.length（共 thresholds.length+1 档）
 */
export function classifyByPercent(value, maxValue, thresholds) {
  if (!Number.isFinite(value) || !Number.isFinite(maxValue) || maxValue <= 0) return 0;
  const percent = (value / maxValue) * 100;
  let index = 0;
  for (const threshold of thresholds) {
    if (percent > threshold) index++;
    else break;
  }
  return Math.min(index, thresholds.length);
}

/**
 * 提取正值并升序排序，供 quantileBreaks({ assumeSorted: true }) 快路径复用：
 * 调用方"排序一次、多次求分位"，色阶阈值/档数调整不再触发全量重排。
 */
export function sortFlowValues(values) {
  return (Array.isArray(values) ? values : [])
    .map(Number)
    .filter((v) => Number.isFinite(v) && v > 0)
    .sort((a, b) => a - b);
}

/**
 * 分位数（quantile）分档：由数据分布计算各分位点对应的"绝对值断点"。
 * thresholds 为分位位置（百分比，如 [20,40,60,80]），返回升序的值断点数组（长度 = thresholds.length）。
 * 只统计正值（>0），避免大量 0 把低分位压到 0。
 */
export function quantileBreaks(values, thresholds, options = {}) {
  // assumeSorted：值分布不变、仅阈值/档数变化的调用方可预排序一次，避免每次调档全量重排
  const sorted = options.assumeSorted
    ? (Array.isArray(values) ? values : [])
    : sortFlowValues(values);
  const list = Array.isArray(thresholds) ? thresholds : [];
  if (!sorted.length) return list.map(() => 0);
  const at = (percent) => {
    const p = Math.max(0, Math.min(100, Number(percent) || 0));
    const idx = (p / 100) * (sorted.length - 1);
    const lo = Math.floor(idx);
    const hi = Math.ceil(idx);
    if (lo === hi) return sorted[lo];
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (idx - lo);
  };
  // 保证非降序
  let prev = 0;
  return list.map((p) => {
    const value = Math.max(prev, at(p));
    prev = value;
    return value;
  });
}

/**
 * 按"绝对值断点"分档：breaks 升序，value 超过第 i 个断点即进入第 i+1 档。
 * @returns 档位下标 0..breaks.length
 */
export function classifyByBreaks(value, breaks) {
  const v = Number(value);
  if (!Number.isFinite(v)) return 0;
  const list = Array.isArray(breaks) ? breaks : [];
  let index = 0;
  for (const b of list) {
    if (v > b) index++;
    else break;
  }
  return Math.min(index, list.length);
}

/**
 * 分位数图例条目：bounds = [0, ...breaks, max]，标签用绝对值（人次）而非百分比。
 * @param formatValue (value)=>string，把绝对值格式化（如加"人次"）
 */
export function buildValueLegendItems(colors, breaks, maxValue, formatValue, widths = null) {
  const list = Array.isArray(breaks) ? breaks : [];
  const top = Math.max(Number(maxValue) || 0, list.length ? list[list.length - 1] : 0);
  const bounds = [0, ...list, top];
  const fmt = formatValue || ((v) => `${Math.round(Number(v) || 0).toLocaleString()}`);
  return colors.map((color, i) => ({
    color,
    width: widths ? widths[i] : null,
    label: `${fmt(bounds[i])} - ${fmt(bounds[i + 1])}`,
  }));
}

/** 由配置 + 数据分布得到分位数色阶：{ colors, thresholds, widths, breaks, max } */
export function resolveQuantileScale(config, values) {
  const base = resolveColorScale(config);
  const breaks = quantileBreaks(values, base.thresholds);
  const arr = (Array.isArray(values) ? values : []).map(Number).filter((v) => Number.isFinite(v) && v > 0);
  const max = arr.length ? Math.max(...arr) : 0;
  return { ...base, breaks, max };
}

/**
 * 生成图例条目（低→高）。
 * @param formatValue 可选，把百分比断点换算成实际数值文案（如人次）
 * @param widths 可选，每档线宽系数（用于图例条形粗细展示）
 * @returns [{ color, label, width }]
 */
export function buildLegendItems(colors, thresholds, formatValue, widths = null) {
  const bounds = [0, ...thresholds, 100];
  return colors.map((color, i) => {
    const low = bounds[i];
    const high = bounds[i + 1];
    const label = formatValue
      ? `${formatValue(low)} - ${formatValue(high)}`
      : `${low}% - ${high}%`;
    return { color, label, width: widths ? widths[i] : null };
  });
}

/** 一个可直接放入组件 state 的完整配置对象 */
export function createColorScaleConfig(schemeKey = "YlOrRd", classCount = 5, reverse = false) {
  return {
    schemeKey,
    classCount,
    reverse,
    thresholds: defaultThresholds(classCount),
  };
}

/** 由配置得到 { colors, thresholds, widths }，供 classify + 图例 + 线宽使用 */
export function resolveColorScale(config) {
  const colors = sampleScheme(config?.schemeKey, config?.classCount ?? 5, config?.reverse);
  let thresholds = normalizeThresholds(config?.thresholds);
  if (thresholds.length !== colors.length - 1) {
    thresholds = defaultThresholds(colors.length);
  }
  return { colors, thresholds, widths: sampleWidthFactors(colors.length) };
}
