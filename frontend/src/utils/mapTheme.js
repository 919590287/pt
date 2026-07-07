/**
 * 地图主题图层统一视觉令牌（与 datamanagement/tokens.css 的"高端蓝玻璃"体系对齐）。
 *
 * 平台锚点：强调蓝 #0071e3、墨色 #1c2024、CARTO Positron 亮色底图。
 * 地图配色策略：
 *  - 常态线网用低饱和钢青蓝，作为"电路板"式底纹退后于数据；
 *  - 选中/方向语义用高饱和橙（上行）与强调蓝（下行），光晕一律用同色系
 *    半透明宽线叠加（backlit 效果），不引入第三种高亮色相；
 *  - 场站徽标用靛蓝，与站点钢蓝同族但可区分。
 *
 * 消费方式：maplibre paint 取 *.css（十六进制字符串），deck.gl/THREE 取
 * hexNumber()/hexToRgbArray() 转换后的数值。
 */

// ---- 基础色 ----------------------------------------------------------------

export const MAP_THEME = {
  /** 公交线网常态（未选中） */
  network: {
    line: "#3d6ea6", // 钢青蓝：比强调蓝低饱和，退后作底纹
    lineOpacity: 0.72,
    dimmed: "#b3c2d6", // 选中场景下其余线路的淡化色（蓝灰）
    dimmedOpacity: 0.38,
    casing: "#ffffff", // 白描边，让线在浅色底图上更利落
    casingOpacity: 0.6,
  },

  /** 选中线路（方向语义） */
  route: {
    up: "#f97316", // 上行主线（橙）
    upHalo: "#fb923c", // 上行光晕：同色系浅橙，替代旧黄色 #facc15
    down: "#0071e3", // 下行主线（平台强调蓝）
    downHalo: "#54a8ff", // 下行光晕：同色系浅蓝
    transfer: "#0071e3", // 换乘线（低透明度绘制）
    haloWidthRatio: 2.3, // 光晕宽度 = 主线宽 × 该系数
    haloOpacity: 0.28,
    related: "#8fa8c8", // 底图关联线（选中场景下的联络线）
  },

  /** 站点 */
  station: {
    ring: "#33608f", // 常态站点圆环（钢蓝加深，保证小尺寸下清晰）
    disc: "#ffffff",
    selected: "#f97316", // 选中站点（与选中线路同橙）
    label: "#1f3140", // 站名文字（冷墨色）
    labelHalo: "rgba(250,252,255,0.94)",
    subway: "#dc4c5d", // 地铁制式（与 element danger 对齐）
  },

  /** 场站（车场/枢纽徽标） */
  depot: {
    from: "#6366f1", // 徽标渐变起（靛蓝）
    to: "#4338ca", // 徽标渐变止（深靛）
    stroke: "#ffffff",
  },

  /** 地铁线 */
  metro: {
    line: "#dc4c5d",
  },

  /** OD 连接线（方向与选中线路一致） */
  od: {
    up: "#f97316",
    down: "#0071e3",
  },

  /** 默认色带（colorSchemes.js 的 schemeKey） */
  schemes: {
    stationHeat: "Mako", // 站点热力：深蓝→青的科技感色带
    segmentFlow: "gnylrd", // 断面客流：绿-黄-红（沿用业务口径）
  },
};

// ---- 工具 ------------------------------------------------------------------

/** "#3d6ea6" -> 0x3d6ea6（deck.gl / THREE 数值色） */
export function hexNumber(hex) {
  return Number.parseInt(String(hex).replace("#", ""), 16);
}

/** "#3d6ea6" -> [61, 110, 166]（deck.gl RGB 数组，可附加 alpha） */
export function hexToRgbArray(hex, alpha) {
  const value = hexNumber(hex);
  const rgb = [(value >> 16) & 255, (value >> 8) & 255, value & 255];
  if (alpha != null) rgb.push(alpha);
  return rgb;
}

/** "#3d6ea6", 0.4 -> "rgba(61,110,166,0.4)"（maplibre paint 字符串） */
export function hexToRgba(hex, alpha = 1) {
  const [r, g, b] = hexToRgbArray(hex);
  return `rgba(${r},${g},${b},${alpha})`;
}
