// 客流走廊监测：corridor-links.bin 解析（纯函数，Worker 可复用）。
// 二进制契约（与后端 MatsimCorridorCache.encodeLinks 对齐，小端）：
//   header: magic "PCRD"(4B) + version u16(=1) + count u32  = 10B
//   record × count（22B/段）: x1 i32, y1 i32, x2 i32, y2 i32（EPSG:3857 取整）,
//                             coeff u16, nameIdx u16, street u16
// 记录按系数升序写入（前端按写入序绘制即可让高系数走廊后画压顶）；
// nameIdx / street 的 0xFFFF 为“无名 / 未命中街道”哨兵；系数=经过的不同公交线路数，无扩样语义。

const HEADER_BYTES = 10;
const RECORD_BYTES = 22;
const LINKS_MAGIC = "PCRD";
/** nameIdx / street 的哨兵值（与后端 U16_SENTINEL 一致）。 */
export const CORRIDOR_U16_SENTINEL = 0xffff;

/**
 * 解析 corridor-links.bin。返回列式 TypedArray（调用方负责 markRaw 后入缓存）。
 * 契约不符（magic/version/长度）直接抛错——宁可显式失败也不渲染错位数据。
 */
export function parseCorridorLinks(buffer) {
  if (!(buffer instanceof ArrayBuffer) || buffer.byteLength < HEADER_BYTES) {
    throw new Error("走廊路段数据为空或长度不足");
  }
  const view = new DataView(buffer);
  const magic = String.fromCharCode(view.getUint8(0), view.getUint8(1), view.getUint8(2), view.getUint8(3));
  if (magic !== LINKS_MAGIC) {
    throw new Error(`走廊路段 magic 不符: ${magic}`);
  }
  const version = view.getUint16(4, true);
  if (version !== 1) {
    throw new Error(`走廊路段版本不支持: ${version}`);
  }
  const count = view.getUint32(6, true);
  if (buffer.byteLength < HEADER_BYTES + count * RECORD_BYTES) {
    throw new Error(`走廊路段长度不足: 期望 ${HEADER_BYTES + count * RECORD_BYTES}B 实际 ${buffer.byteLength}B`);
  }
  const x1 = new Int32Array(count);
  const y1 = new Int32Array(count);
  const x2 = new Int32Array(count);
  const y2 = new Int32Array(count);
  const coeff = new Uint16Array(count);
  const nameIdx = new Uint16Array(count);
  const street = new Uint16Array(count);
  let offset = HEADER_BYTES;
  for (let k = 0; k < count; k++) {
    x1[k] = view.getInt32(offset, true);
    y1[k] = view.getInt32(offset + 4, true);
    x2[k] = view.getInt32(offset + 8, true);
    y2[k] = view.getInt32(offset + 12, true);
    coeff[k] = view.getUint16(offset + 16, true);
    nameIdx[k] = view.getUint16(offset + 18, true);
    street[k] = view.getUint16(offset + 20, true);
    offset += RECORD_BYTES;
  }
  return { count, x1, y1, x2, y2, coeff, nameIdx, street };
}
