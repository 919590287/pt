// 公交OD监测：tripends-od-grid.bin 解析与分级工具（纯函数，Worker 可复用）。
// 二进制契约（与后端 MatsimTripEndsCache.encodeOdGrid 对齐，小端）：
//   header: magic "PGOD"(4B) + version u16(=1) + count u32 + mercCellSize f64  = 18B
//   record × count（24B/对）: iO i32, jO i32, iD i32, jD i32, count u32, oStreet u16, dStreet u16
// 记录按人次降序写入（前端可按前缀取 Top-K）；oStreet/dStreet 为街道要素索引（资源文件序），
// 0xFFFF = 未命中街道；人次为模型抽样值，展示侧直出不扩样。

const HEADER_BYTES = 18;
const RECORD_BYTES = 24;
const OD_MAGIC = "PGOD";
/** oStreet/dStreet 的“未命中街道”哨兵（与后端 OD_STREET_UNASSIGNED 一致）。 */
export const OD_STREET_UNASSIGNED = 0xffff;

/**
 * 解析 tripends-od-grid.bin。返回列式 TypedArray（调用方负责 markRaw 后入缓存）。
 * 契约不符（magic/version/长度）直接抛错——宁可显式失败也不渲染错位数据。
 */
export function parseBusOdGrid(buffer) {
  if (!(buffer instanceof ArrayBuffer) || buffer.byteLength < HEADER_BYTES) {
    throw new Error("公交OD栅格数据为空或长度不足");
  }
  const view = new DataView(buffer);
  const magic = String.fromCharCode(view.getUint8(0), view.getUint8(1), view.getUint8(2), view.getUint8(3));
  if (magic !== OD_MAGIC) {
    throw new Error(`公交OD栅格 magic 不符: ${magic}`);
  }
  const version = view.getUint16(4, true);
  if (version !== 1) {
    throw new Error(`公交OD栅格版本不支持: ${version}`);
  }
  const count = view.getUint32(6, true);
  const mercCellSize = view.getFloat64(10, true);
  if (!(mercCellSize > 0)) {
    throw new Error(`公交OD栅格 cellSize 非法: ${mercCellSize}`);
  }
  if (buffer.byteLength < HEADER_BYTES + count * RECORD_BYTES) {
    throw new Error(`公交OD栅格长度不足: 期望 ${HEADER_BYTES + count * RECORD_BYTES}B 实际 ${buffer.byteLength}B`);
  }
  const iO = new Int32Array(count);
  const jO = new Int32Array(count);
  const iD = new Int32Array(count);
  const jD = new Int32Array(count);
  const n = new Uint32Array(count);
  const oStreet = new Uint16Array(count);
  const dStreet = new Uint16Array(count);
  let offset = HEADER_BYTES;
  for (let k = 0; k < count; k++) {
    iO[k] = view.getInt32(offset, true);
    jO[k] = view.getInt32(offset + 4, true);
    iD[k] = view.getInt32(offset + 8, true);
    jD[k] = view.getInt32(offset + 12, true);
    n[k] = view.getUint32(offset + 16, true);
    oStreet[k] = view.getUint16(offset + 20, true);
    dStreet[k] = view.getUint16(offset + 22, true);
    offset += RECORD_BYTES;
  }
  return { count, mercCellSize, iO, jO, iD, jD, n, oStreet, dStreet };
}

/** OD 线分级的默认分位（8 级 = 7 断点）：长尾流量分布下高分位密集，突出主要走廊。 */
export const OD_CLASS_FRACTIONS = [0.5, 0.7, 0.85, 0.93, 0.97, 0.99, 0.997];

/**
 * 按分位计算严格递增的分级断点：values 为当前显示的 OD 流量集合（无需有序）。
 * 重复分位值合并（数据量小/离散时自然降级为更少级数），返回可能短于 fractions 的断点数组。
 */
export function quantileBreaks(values, fractions = OD_CLASS_FRACTIONS) {
  if (!values.length) return [];
  const sorted = Array.from(values).sort((a, b) => a - b);
  const breaks = [];
  for (const fraction of fractions) {
    const value = sorted[Math.min(sorted.length - 1, Math.floor(fraction * (sorted.length - 1)))];
    if (!breaks.length || value > breaks[breaks.length - 1]) {
      breaks.push(value);
    }
  }
  // 全部值相同（断点=最大值）时无从分级，退化为单级
  if (breaks.length === 1 && breaks[0] >= sorted[sorted.length - 1]) return [];
  return breaks;
}
