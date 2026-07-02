/**
 * QGIS 风格分级色阶工具（需求13）：
 * - 5 种色系（渐变 ramp），同一色系可采样任意档数（2~9 档）
 * - 阈值用"占最大值的百分比"表示（升序断点数组，长度 = 档数-1）
 * - classify 将数值映射到档位，供地图着色与图例共用
 */

export const COLOR_SCHEMES = [
  { key: "ylorrd", name: "黄-橙-红", anchors: ["#ffffb2", "#fecc5c", "#fd8d3c", "#f03b20", "#bd0026"] },
  { key: "gnylrd", name: "绿-黄-红", anchors: ["#1a9641", "#a6d96a", "#ffffbf", "#fdae61", "#d7191c"] },
  { key: "blues", name: "蓝色系", anchors: ["#deebf7", "#9ecae1", "#4292c6", "#2171b5", "#08306b"] },
  { key: "viridis", name: "翠绿光谱", anchors: ["#440154", "#3b528b", "#21918c", "#5ec962", "#fde725"] },
  { key: "spectral", name: "冷暖光谱", anchors: ["#3288bd", "#99d594", "#ffffbf", "#fc8d59", "#d53e4f"] },
  // 人群密度专题图风格：蓝（低）→灰绿→浅黄→橙→深红（高），站点客流热力图默认色系
  { key: "densityblue", name: "蓝-黄-红(密度)", anchors: ["#3f6db3", "#a9bdb7", "#f2eeb0", "#f2a05c", "#bc1a10"] },
];

export const MIN_CLASS_COUNT = 2;
export const MAX_CLASS_COUNT = 9;

function hexToRgb(hex) {
  const value = hex.replace("#", "");
  return [
    parseInt(value.slice(0, 2), 16),
    parseInt(value.slice(2, 4), 16),
    parseInt(value.slice(4, 6), 16),
  ];
}

function rgbToHex([r, g, b]) {
  const to2 = (n) => Math.round(Math.max(0, Math.min(255, n))).toString(16).padStart(2, "0");
  return `#${to2(r)}${to2(g)}${to2(b)}`;
}

/** 沿 ramp 锚点线性插值取色，t ∈ [0,1] */
export function sampleRamp(anchors, t) {
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

/** 从色系采样 classCount 档颜色（低→高） */
export function sampleScheme(schemeKey, classCount) {
  const scheme = COLOR_SCHEMES.find((item) => item.key === schemeKey) || COLOR_SCHEMES[0];
  const count = Math.max(MIN_CLASS_COUNT, Math.min(MAX_CLASS_COUNT, Math.round(classCount) || MIN_CLASS_COUNT));
  const colors = [];
  for (let i = 0; i < count; i++) {
    colors.push(sampleRamp(scheme.anchors, count === 1 ? 1 : i / (count - 1)));
  }
  return colors;
}

/** 默认等分阈值（百分比断点，升序，长度=档数-1），如 5 档 → [20,40,60,80] */
export function defaultThresholds(classCount) {
  const count = Math.max(MIN_CLASS_COUNT, Math.min(MAX_CLASS_COUNT, Math.round(classCount) || MIN_CLASS_COUNT));
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
 * 生成图例条目（低→高）。
 * @param formatValue 可选，把百分比断点换算成实际数值文案（如人次）
 * @returns [{ color, label }]
 */
export function buildLegendItems(colors, thresholds, formatValue) {
  const bounds = [0, ...thresholds, 100];
  return colors.map((color, i) => {
    const low = bounds[i];
    const high = bounds[i + 1];
    const label = formatValue
      ? `${formatValue(low)} - ${formatValue(high)}`
      : `${low}% - ${high}%`;
    return { color, label };
  });
}

/** 一个可直接放入组件 state 的完整配置对象 */
export function createColorScaleConfig(schemeKey = "ylorrd", classCount = 5) {
  return {
    schemeKey,
    classCount,
    thresholds: defaultThresholds(classCount),
  };
}

/** 由配置得到 { colors, thresholds }，供 classify + 图例使用 */
export function resolveColorScale(config) {
  const colors = sampleScheme(config?.schemeKey, config?.classCount ?? 5);
  let thresholds = normalizeThresholds(config?.thresholds);
  if (thresholds.length !== colors.length - 1) {
    thresholds = defaultThresholds(colors.length);
  }
  return { colors, thresholds };
}
