// 客流走廊监测：corridor-links.bin 解析（纯函数，Worker 可复用）。
// 二进制契约（与后端 MatsimCorridorCache.encodeLinks 对齐，小端）：
//   header: magic "PCRD"(4B) + version u16(=2) + count u32  = 10B
//   record × count（26B/段）: x1 i32, y1 i32, x2 i32, y2 i32（EPSG:3857 取整）,
//                             coeff u16, nameIdx u16, street u16, flow u32
// 记录按系数升序写入（重复系数子模块按写入序绘制即可压顶；客流子模块自行按 flow 排序）；
// nameIdx / street 的 0xFFFF 为“无名 / 未命中街道”哨兵；
// coeff=经过的不同公交线路数（无扩样语义），flow=断面客流（双向叠加，模型抽样人次直出）。

import { mercatorToLngLat } from "./populationGrid.js";

const HEADER_BYTES = 10;
const RECORD_BYTES = 26;
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
  if (version !== 2) {
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
  const flow = new Uint32Array(count);
  let offset = HEADER_BYTES;
  for (let k = 0; k < count; k++) {
    x1[k] = view.getInt32(offset, true);
    y1[k] = view.getInt32(offset + 4, true);
    x2[k] = view.getInt32(offset + 8, true);
    y2[k] = view.getInt32(offset + 12, true);
    coeff[k] = view.getUint16(offset + 16, true);
    nameIdx[k] = view.getUint16(offset + 18, true);
    street[k] = view.getUint16(offset + 20, true);
    flow[k] = view.getUint32(offset + 22, true);
    offset += RECORD_BYTES;
  }
  return { count, x1, y1, x2, y2, coeff, nameIdx, street, flow };
}

/**
 * 公交客流走廊：构建断面客流带宽 PathLayer 的二进制数据（仅正流量段，输入序即绘制序，
 * 调用方须先按流量升序排好让大流量压顶）。每段一条两点 path + per-vertex 宽度（米），
 * 供 deck PathLayer binary attributes 直用；配合圆头端帽，相邻路段在节点处以圆弧
 * 自然衔接成连续流量带（对齐 Transit Flows 样张，消除平头线段的楔形断口）。
 * 宽度 = (flow/refFlow)^exponent × maxWidthM，超出锚定流量按同幂外推不封顶
 * （头部层次不被压平，极端值由图层 widthMaxPixels 兜底）；refFlow 无效时宽度取 0。
 */
export function buildFlowPathData(links, indexes, { refFlow, maxWidthM, exponent = 1 }) {
  let positive = 0;
  for (const k of indexes) {
    if (links.flow[k] > 0) positive += 1;
  }
  const startIndices = new Uint32Array(positive + 1);
  const positions = new Float64Array(positive * 4);
  const widths = new Float32Array(positive * 2);
  let seg = 0;
  for (const k of indexes) {
    if (links.flow[k] <= 0) continue;
    const [lng1, lat1] = mercatorToLngLat(links.x1[k], links.y1[k]);
    const [lng2, lat2] = mercatorToLngLat(links.x2[k], links.y2[k]);
    positions[seg * 4] = lng1;
    positions[seg * 4 + 1] = lat1;
    positions[seg * 4 + 2] = lng2;
    positions[seg * 4 + 3] = lat2;
    const ratio = refFlow > 0 ? links.flow[k] / refFlow : 0;
    const width = Math.pow(ratio, exponent) * maxWidthM;
    widths[seg * 2] = width;
    widths[seg * 2 + 1] = width;
    startIndices[seg + 1] = (seg + 1) * 2;
    seg += 1;
  }
  return { length: positive, startIndices, positions, widths };
}
