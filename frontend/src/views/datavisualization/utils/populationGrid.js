// 人口分布监测：population-grid.bin 解析与栅格渲染数据构建（纯函数，Worker 可复用）。
// 二进制契约见 docs/公交出行监测人口分布模块设计方案.md §3 + v2 增列（与后端 MatsimPopulationCache 对齐，小端）：
//   header: magic "PGRD"(4B) + version u16 + count u32 + mercCellSize f64 = 18B
//   v2 record（历史兼容/出行端点，18B/cell）: i i32, j i32, home u32, work u32, street u16
//   v3 record（仿真与真实人口，22B/cell）: i i32, j i32, home u32, work u32, resident u32, street u16
//   —— 人数为源数据原始量，展示侧不做缩放；street 为格中心街道要素索引
//     （资源文件序，0xFFFF=未命中，行政区过滤用）。
// cell 西南角 = (i*cs, j*cs)（EPSG:3857）。出行分布监测复用本契约（home 列=出行起点、work 列=出行终点）。

/** 与后端 GeoUtil/前端 LngLatUtils 同源的 Web 墨卡托半周长 */
const EARTH_RADIUS = 20037508.3427892;
const HEADER_BYTES = 18;
const RECORD_BYTES_V2 = 18;
const RECORD_BYTES_V3 = 22;
const GRID_MAGIC = "PGRD";
/** street 列的“未命中街道”哨兵（与后端 STREET_SENTINEL 一致）。 */
export const GRID_STREET_SENTINEL = 0xffff;
/** 100m 栅格单元面积（km²），密度 = 模型原始人数 ÷ CELL_AREA_KM2 */
export const CELL_AREA_KM2 = 0.01;

export function mercatorToLngLat(x, y) {
  const lng = (x / EARTH_RADIUS) * 180;
  let lat = (y / EARTH_RADIUS) * 180;
  lat = (180 / Math.PI) * (2 * Math.atan(Math.exp((lat * Math.PI) / 180)) - Math.PI / 2);
  return [lng, lat];
}

/**
 * 解析 population-grid.bin。返回列式 TypedArray（调用方负责 markRaw 后入缓存）。
 * 契约不符（magic/version/长度）直接抛错——宁可显式失败也不渲染错位数据。
 */
export function parsePopulationGrid(buffer) {
  if (!(buffer instanceof ArrayBuffer) || buffer.byteLength < HEADER_BYTES) {
    throw new Error("人口栅格数据为空或长度不足");
  }
  const view = new DataView(buffer);
  const magic = String.fromCharCode(view.getUint8(0), view.getUint8(1), view.getUint8(2), view.getUint8(3));
  if (magic !== GRID_MAGIC) {
    throw new Error(`人口栅格 magic 不符: ${magic}`);
  }
  const version = view.getUint16(4, true);
  if (version !== 2 && version !== 3) {
    throw new Error(`人口栅格版本不支持: ${version}`);
  }
  const recordBytes = version === 3 ? RECORD_BYTES_V3 : RECORD_BYTES_V2;
  const count = view.getUint32(6, true);
  const mercCellSize = view.getFloat64(10, true);
  if (!(mercCellSize > 0)) {
    throw new Error(`人口栅格 cellSize 非法: ${mercCellSize}`);
  }
  if (buffer.byteLength < HEADER_BYTES + count * recordBytes) {
    throw new Error(`人口栅格长度不足: 期望 ${HEADER_BYTES + count * recordBytes}B 实际 ${buffer.byteLength}B`);
  }
  const i = new Int32Array(count);
  const j = new Int32Array(count);
  const home = new Uint32Array(count);
  const work = new Uint32Array(count);
  const resident = version === 3 ? new Uint32Array(count) : null;
  const street = new Uint16Array(count);
  let homeTotal = 0;
  let workTotal = 0;
  let residentTotal = 0;
  let offset = HEADER_BYTES;
  for (let k = 0; k < count; k++) {
    i[k] = view.getInt32(offset, true);
    j[k] = view.getInt32(offset + 4, true);
    home[k] = view.getUint32(offset + 8, true);
    work[k] = view.getUint32(offset + 12, true);
    if (version === 3) {
      resident[k] = view.getUint32(offset + 16, true);
      residentTotal += resident[k];
    }
    street[k] = view.getUint16(offset + (version === 3 ? 20 : 16), true);
    homeTotal += home[k];
    workTotal += work[k];
    offset += recordBytes;
  }
  return {
    version,
    count,
    mercCellSize,
    i,
    j,
    home,
    work,
    resident,
    street,
    homeTotal,
    workTotal,
    residentTotal,
  };
}

/**
 * cell 西南角经纬度（deck.gl GridCellLayer 的 getPosition 锚点），
 * 与 home/work 两指标共用，一份栅格只算一次。
 */
export function buildGridPositions(grid) {
  const { count, mercCellSize, i, j } = grid;
  const positions = new Float64Array(count * 2);
  for (let k = 0; k < count; k++) {
    const [lng, lat] = mercatorToLngLat(i[k] * mercCellSize, j[k] * mercCellSize);
    positions[k * 2] = lng;
    positions[k * 2 + 1] = lat;
  }
  return positions;
}

/** "#1a9850" -> [26,152,80] */
function hexToRgb(hex) {
  const value = Number.parseInt(String(hex).replace("#", ""), 16);
  return [(value >> 16) & 255, (value >> 8) & 255, value & 255];
}

/** 密度（人/km²）→ 分级索引：breaks 升序，值 ≤ breaks[n] 归第 n 级，超出全部断点归最高级 */
export function densityClassIndex(density, breaks) {
  for (let n = 0; n < breaks.length; n++) {
    if (density <= breaks[n]) return n;
  }
  return breaks.length;
}

/**
 * 按指标生成逐 cell RGBA（Uint8Array，deck.gl 二进制 accessor）。
 * counts 为模型原始人数，不做缩放；密度 = counts ÷ 0.01km²。
 * count=0 的 cell alpha=0（该指标下不画）。
 */
export function buildGridColors(counts, { breaks, ramp, alphaLow = 120, alpha = 205 }) {
  const rgb = ramp.map(hexToRgb);
  const colors = new Uint8Array(counts.length * 4);
  for (let k = 0; k < counts.length; k++) {
    const count = counts[k];
    if (!count) continue; // alpha 保持 0
    const density = count / CELL_AREA_KM2;
    const cls = densityClassIndex(density, breaks);
    const [r, g, b] = rgb[Math.min(cls, rgb.length - 1)];
    const base = k * 4;
    colors[base] = r;
    colors[base + 1] = g;
    colors[base + 2] = b;
    colors[base + 3] = cls === 0 ? alphaLow : alpha;
  }
  return colors;
}

/**
 * 按人口 / 客流构建 3D 柱高（米）。
 * 线性映射会被少数极高格拉爆（高格上万米、多数格贴地看不出差别），这里改为
 * 「分位归一 + 幂次压缩 + 最小高度」：
 *   高度 = minHeight + (referenceHeight - minHeight) × (值 / 参考值)^exponent
 * 参考值取非零格的 referenceQuantile 分位（默认 P99），因此对人口/出行两套量纲自适应；
 * exponent < 1 压缩高低差，minHeight 保证最小的非零格也有可见厚度。
 * 超过参考值的格子不封顶（仍按同一曲线继续升高），保留峰值可辨识度。
 */
export function buildGridElevations(
  counts,
  {
    referenceQuantile = 0.99,
    referenceHeight = 1500,
    minHeight = 200,
    exponent = 0.5,
  } = {},
) {
  const n = counts.length;
  const elevations = new Float32Array(n);
  let nonZero = 0;
  for (let k = 0; k < n; k++) if (counts[k] > 0) nonZero++;
  if (!nonZero) return elevations;

  const sorted = new Float64Array(nonZero);
  let p = 0;
  for (let k = 0; k < n; k++) {
    const value = counts[k];
    if (value > 0) sorted[p++] = value;
  }
  sorted.sort();
  const idx = Math.min(nonZero - 1, Math.max(0, Math.round(referenceQuantile * (nonZero - 1))));
  const reference = sorted[idx] || sorted[nonZero - 1];

  const span = Math.max(0, referenceHeight - minHeight);
  for (let k = 0; k < n; k++) {
    const value = Math.max(0, Number(counts[k]) || 0);
    if (value <= 0) continue;
    elevations[k] = minHeight + span * Math.pow(value / reference, exponent);
  }
  return elevations;
}

/** 图例条目：与 buildGridColors 完全同一套 breaks/ramp，避免图-例分家 */
export function buildDensityLegendItems(breaks, ramp, formatValue = (v) => String(v)) {
  const items = [];
  for (let n = 0; n < ramp.length; n++) {
    const from = n === 0 ? 0 : breaks[n - 1];
    const to = n < breaks.length ? breaks[n] : null;
    items.push({
      color: ramp[n],
      label: to == null ? `> ${formatValue(from)}` : `${formatValue(from)} - ${formatValue(to)}`,
    });
  }
  return items;
}
