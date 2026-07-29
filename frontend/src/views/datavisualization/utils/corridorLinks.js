// 客流走廊监测：corridor-links.bin 解析（纯函数，Worker 可复用）。
// 二进制契约（与后端 MatsimCorridorCache.encodeLinks 对齐，小端）：
//   header: magic "PCRD"(4B) + version u16(=2) + count u32  = 10B
//   record × count（26B/段）: x1 i32, y1 i32, x2 i32, y2 i32（EPSG:3857 取整）,
//                             coeff u16, nameIdx u16, street u16, flow u32
// 记录按系数升序写入（重复系数子模块按写入序绘制即可压顶；客流子模块自行按 flow 排序）；
// nameIdx / street 的 0xFFFF 为“无名 / 未命中街道”哨兵；
// coeff=经过的不同公交线路数，flow=断面客流（双向叠加，模型原始人次）。

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

/**
 * 为公交客流走廊榜单道路生成地图名称标注。
 * 每条道路只取一个代表点：优先选最高断面客流路段，客流并列时选更长的路段，
 * 然后使用该路段中点。这样标注始终落在真实道路上，且靠近使该道路上榜的主客流带。
 * 返回顺序与 rankedRoads 一致，便于保留榜单名次。
 */
export function buildFlowRoadLabelData(links, indexes, rankedRoads) {
  if (!links || !Array.isArray(indexes) || !Array.isArray(rankedRoads) || !rankedRoads.length) return [];

  const rankedByName = new Map();
  rankedRoads.forEach((road, rank) => {
    if (road && !rankedByName.has(road.nameIdx)) {
      rankedByName.set(road.nameIdx, { road, rank: rank + 1 });
    }
  });

  const bestSegmentByName = new Map();
  for (const k of indexes) {
    if (!Number.isInteger(k) || k < 0 || k >= links.count) continue;
    const nameIdx = links.nameIdx[k];
    if (!rankedByName.has(nameIdx)) continue;

    const flow = Number(links.flow[k]) || 0;
    const dx = links.x2[k] - links.x1[k];
    const dy = links.y2[k] - links.y1[k];
    const lengthSquared = dx * dx + dy * dy;
    const previous = bestSegmentByName.get(nameIdx);
    if (!previous || flow > previous.flow || (flow === previous.flow && lengthSquared > previous.lengthSquared)) {
      bestSegmentByName.set(nameIdx, { k, flow, lengthSquared });
    }
  }

  const labels = [];
  for (const { road, rank } of rankedByName.values()) {
    const candidate = bestSegmentByName.get(road.nameIdx);
    if (!candidate) continue;
    const k = candidate.k;
    labels.push({
      nameIdx: road.nameIdx,
      name: String(road.name || ""),
      rank,
      position: mercatorToLngLat(
        (links.x1[k] + links.x2[k]) / 2,
        (links.y1[k] + links.y2[k]) / 2,
      ),
    });
  }
  return labels;
}

/**
 * 道路名标注的屏幕空间避让：按名次贪心保留互不重叠的标签（低名次让位，放大后
 * 重算时自然补显）。不用 deck CollisionFilterExtension——它以字形笔画为占位几何、
 * 只在锚点小窗口采样归属，中文笔画间空隙大，近距标签互相落进对方笔画缝隙时避让
 * 失效（真实数据洛溪聚簇曾复现叠字）；≤10 个标签用矩形相交贪心既确定又可单测。
 *
 * labels：buildFlowRoadLabelData 输出（rank 越小名次越高）。
 * project：([lng,lat]) => {x,y}（调用方传地图实例的 project，屏幕 CSS 像素）。
 * 文本框估算：全角字符 1em、半角 0.6em，行高 1.2em，中心 = 锚点 + pixelOffset，
 * 四周加 paddingPx 呼吸间距。返回保留子集，顺序按名次。
 */
export function selectVisibleRoadLabels(labels, project, { sizePx = 11, pixelOffset = [0, -8], paddingPx = 6 } = {}) {
  if (!Array.isArray(labels) || !labels.length || typeof project !== "function") return [];
  const sorted = [...labels].sort((a, b) => (a.rank ?? 0) - (b.rank ?? 0));
  const keptBoxes = [];
  const kept = [];
  for (const label of sorted) {
    const point = project(label.position);
    const x = Number(point?.x);
    const y = Number(point?.y);
    if (!Number.isFinite(x) || !Number.isFinite(y)) continue;
    let widthEm = 0;
    for (const ch of String(label.name)) widthEm += ch.charCodeAt(0) > 0xff ? 1 : 0.6;
    const halfW = (widthEm * sizePx) / 2 + paddingPx;
    const halfH = (sizePx * 1.2) / 2 + paddingPx;
    const cx = x + pixelOffset[0];
    const cy = y + pixelOffset[1];
    const box = [cx - halfW, cy - halfH, cx + halfW, cy + halfH];
    const collides = keptBoxes.some((b) => box[0] < b[2] && box[2] > b[0] && box[1] < b[3] && box[3] > b[1]);
    if (collides) continue;
    keptBoxes.push(box);
    kept.push(label);
  }
  return kept;
}
