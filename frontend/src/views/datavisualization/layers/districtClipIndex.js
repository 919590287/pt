/**
 * 行政区线段裁剪 + 边界段网格索引。
 *
 * 供 NetworkLayer.js（非 worker 回退路径）与 networkData.worker.js（worker 模式）共用，
 * 保证两条路径的裁剪结果完全一致。
 *
 * 算法语义与 @/utils/adminDistrictRange.js 的 clipSegmentToDistrictContext 等价：
 * 对每条链路段求其与行政区边界段的全部交点参数 t，按 t 切分后用中点内含测试保留区间。
 * 差异仅在于候选边界段来自均匀网格索引（原实现线性扫描全部 boundarySegments），
 * 网格命中集合是 bbox 相交集合的超集，且保留逐段 bounds 预判，因此 t 集合一致
 * （重复 t 由 uniqueSortedNumbers 以 1e-9 容差去重），裁剪输出一致。
 *
 * 注意：本文件不修改 adminDistrictRange.js；下列私有几何工具是其内部实现的逐行等价复刻
 * （容差常数必须保持一致），仅 pointInDistrictContext 直接复用其导出。
 */
import { pointInDistrictContext } from "@/utils/adminDistrictRange.js";

const EMPTY_FLOAT32 = new Float32Array(0);
const EMPTY_UINT32 = new Uint32Array(0);
const EMPTY_FLOAT64 = new Float64Array(0);

// ---------- 与 adminDistrictRange.js 内部实现等价的几何工具（勿改容差） ----------

function validLngLat(coordinate) {
  if (!Array.isArray(coordinate) || coordinate.length < 2) return null;
  const lng = Number(coordinate[0]);
  const lat = Number(coordinate[1]);
  if (!Number.isFinite(lng) || !Number.isFinite(lat)) return null;
  return [lng, lat];
}

function pointsAlmostEqual(left, right) {
  if (!Array.isArray(left) || !Array.isArray(right)) return false;
  return Math.abs(Number(left[0]) - Number(right[0])) <= 1e-9
    && Math.abs(Number(left[1]) - Number(right[1])) <= 1e-9;
}

function pointAlongSegment(start, end, ratio) {
  const t = Math.max(0, Math.min(1, Number(ratio) || 0));
  return [
    Number(start[0]) + (Number(end[0]) - Number(start[0])) * t,
    Number(start[1]) + (Number(end[1]) - Number(start[1])) * t,
  ];
}

function boundsIntersect(left, right) {
  if (!left || !right) return false;
  return left[0] <= right[2] && left[2] >= right[0] && left[1] <= right[3] && left[3] >= right[1];
}

function segmentIntersectionParameters(start, end, otherStart, otherEnd) {
  const rX = end[0] - start[0];
  const rY = end[1] - start[1];
  const sX = otherEnd[0] - otherStart[0];
  const sY = otherEnd[1] - otherStart[1];
  const denominator = rX * sY - rY * sX;
  const qPX = otherStart[0] - start[0];
  const qPY = otherStart[1] - start[1];
  if (Math.abs(denominator) < 1e-12) {
    const collinear = Math.abs(qPX * rY - qPY * rX) < 1e-12;
    const lengthSquared = rX * rX + rY * rY;
    if (!collinear || lengthSquared < 1e-18) return [];
    const otherStartT = ((otherStart[0] - start[0]) * rX + (otherStart[1] - start[1]) * rY) / lengthSquared;
    const otherEndT = ((otherEnd[0] - start[0]) * rX + (otherEnd[1] - start[1]) * rY) / lengthSquared;
    const overlapStart = Math.max(0, Math.min(otherStartT, otherEndT));
    const overlapEnd = Math.min(1, Math.max(otherStartT, otherEndT));
    if (overlapEnd + 1e-9 < overlapStart) return [];
    return [overlapStart, overlapEnd].map((value) => Math.min(1, Math.max(0, value)));
  }
  const t = (qPX * sY - qPY * sX) / denominator;
  const u = (qPX * rY - qPY * rX) / denominator;
  if (t < -1e-9 || t > 1 + 1e-9 || u < -1e-9 || u > 1 + 1e-9) return [];
  return [Math.min(1, Math.max(0, t))];
}

function uniqueSortedNumbers(values) {
  const sorted = (Array.isArray(values) ? values : [])
    .map((value) => Math.max(0, Math.min(1, Number(value))))
    .filter(Number.isFinite)
    .sort((left, right) => left - right);
  const unique = [];
  for (const value of sorted) {
    if (!unique.length || Math.abs(value - unique[unique.length - 1]) > 1e-9) {
      unique.push(value);
    }
  }
  return unique;
}

// ---------- 网格索引 ----------

/**
 * 对 context.boundarySegments 建均匀网格索引。
 * district 级边界通常数千段，网格把每条链路段的候选集从 O(B) 降到近 O(1)。
 * @returns null（无 context）或 { context, minX, minY, invCellW, invCellH, cols, rows, cells, entries }
 */
export function buildDistrictClipIndex(context) {
  if (!context) return null;
  const segments = Array.isArray(context.boundarySegments) ? context.boundarySegments : [];
  const bounds = context.bounds;
  const index = {
    context,
    segments,
    minX: 0,
    minY: 0,
    invCellW: 0,
    invCellH: 0,
    cols: 0,
    rows: 0,
    cells: null,
    entries: null,
    queryStamp: 0,
  };
  if (!segments.length || !Array.isArray(bounds) || bounds.length < 4) return index;

  const width = Math.max(bounds[2] - bounds[0], 1e-9);
  const height = Math.max(bounds[3] - bounds[1], 1e-9);
  // 目标：平均每格个位数段；上限防止超大多边形撑爆内存
  const side = Math.max(8, Math.min(96, Math.ceil(Math.sqrt(segments.length))));
  const cols = side;
  const rows = side;
  const cellW = width / cols;
  const cellH = height / rows;

  const cells = new Array(cols * rows).fill(null);
  // entries 与 segments 平行：stamp 用于单次查询内去重（一段可跨多格）
  const stamps = new Int32Array(segments.length);

  for (let i = 0; i < segments.length; i++) {
    const segBounds = segments[i]?.bounds;
    if (!segBounds) continue;
    const minCol = clampCell(Math.floor((segBounds[0] - bounds[0]) / cellW), cols);
    const maxCol = clampCell(Math.floor((segBounds[2] - bounds[0]) / cellW), cols);
    const minRow = clampCell(Math.floor((segBounds[1] - bounds[1]) / cellH), rows);
    const maxRow = clampCell(Math.floor((segBounds[3] - bounds[1]) / cellH), rows);
    for (let row = minRow; row <= maxRow; row++) {
      for (let col = minCol; col <= maxCol; col++) {
        const cellIndex = row * cols + col;
        if (!cells[cellIndex]) cells[cellIndex] = [];
        cells[cellIndex].push(i);
      }
    }
  }

  index.minX = bounds[0];
  index.minY = bounds[1];
  index.invCellW = 1 / cellW;
  index.invCellH = 1 / cellH;
  index.cols = cols;
  index.rows = rows;
  index.cells = cells;
  index.stamps = stamps;
  // 均匀格分类（0=未分类 1=内 2=外，惰性求值）：无边界段穿过的格子整格同性，
  // 点内含测试从 O(环顶点数) 降为 O(1)——这是 district 级裁剪的主耗时来源
  index.cellClass = new Uint8Array(cols * rows);
  return index;
}

function clampCell(value, max) {
  return Math.max(0, Math.min(max - 1, value));
}

// 点内含测试（结果与 pointInDistrictContext 完全一致）：
// 均匀格 O(1) 命中缓存分类；含边界段的格子回退精确判定
function pointInsideIndexed(index, point) {
  const context = index.context;
  if (!index.cells) return pointInDistrictContext(point, context);
  const bounds = context.bounds;
  const x = point[0];
  const y = point[1];
  if (!(x >= bounds[0] && x <= bounds[2] && y >= bounds[1] && y <= bounds[3])) return false;
  const col = clampCell(Math.floor((x - index.minX) * index.invCellW), index.cols);
  const row = clampCell(Math.floor((y - index.minY) * index.invCellH), index.rows);
  const cellIndex = row * index.cols + col;
  if (index.cells[cellIndex]) {
    // 边界格：格内点内外不一，逐点精确判定
    return pointInDistrictContext(point, context);
  }
  let cellClass = index.cellClass[cellIndex];
  if (cellClass === 0) {
    // 无边界段穿过 → 整格同性，用格心一次精确判定后缓存
    const center = [
      index.minX + (col + 0.5) / index.invCellW,
      index.minY + (row + 0.5) / index.invCellH,
    ];
    cellClass = pointInDistrictContext(center, context) ? 1 : 2;
    index.cellClass[cellIndex] = cellClass;
  }
  return cellClass === 1;
}

/**
 * 裁剪一条线段到行政区内部区间。
 * 输出与 adminDistrictRange.js 的 clipSegmentToDistrictContext(start, end, context) 一致。
 * @returns Array<[[lng,lat],[lng,lat]]>
 */
export function clipSegmentWithIndex(index, start, end) {
  const from = validLngLat(start);
  const to = validLngLat(end);
  if (!from || !to || pointsAlmostEqual(from, to)) return [];
  if (!index?.context) return [[from, to]];
  const context = index.context;

  // 与原实现等价的交点参数收集：候选段来自网格（bbox 相交集合的超集），
  // 逐段 bounds 预判保留，t 值集合与线性扫描一致
  const tValues = [0, 1];
  const qMinX = Math.min(from[0], to[0]);
  const qMinY = Math.min(from[1], to[1]);
  const qMaxX = Math.max(from[0], to[0]);
  const qMaxY = Math.max(from[1], to[1]);

  if (index.cells) {
    const contextBounds = context.bounds;
    if (
      qMinX <= contextBounds[2] && qMaxX >= contextBounds[0] &&
      qMinY <= contextBounds[3] && qMaxY >= contextBounds[1]
    ) {
      const stamp = ++index.queryStamp;
      const minCol = clampCell(Math.floor((qMinX - index.minX) * index.invCellW), index.cols);
      const maxCol = clampCell(Math.floor((qMaxX - index.minX) * index.invCellW), index.cols);
      const minRow = clampCell(Math.floor((qMinY - index.minY) * index.invCellH), index.rows);
      const maxRow = clampCell(Math.floor((qMaxY - index.minY) * index.invCellH), index.rows);
      const currentBounds = [qMinX, qMinY, qMaxX, qMaxY];
      for (let row = minRow; row <= maxRow; row++) {
        for (let col = minCol; col <= maxCol; col++) {
          const bucket = index.cells[row * index.cols + col];
          if (!bucket) continue;
          for (const segIndex of bucket) {
            if (index.stamps[segIndex] === stamp) continue;
            index.stamps[segIndex] = stamp;
            const segment = index.segments[segIndex];
            if (!boundsIntersect(currentBounds, segment.bounds)) continue;
            const params = segmentIntersectionParameters(from, to, segment.start, segment.end);
            for (let p = 0; p < params.length; p++) tValues.push(params[p]);
          }
        }
      }
    }
  }

  // 区间中点内含测试（与原实现一致；无交点且两端在外时天然得空集）
  const sorted = uniqueSortedNumbers(tValues);
  const result = [];
  for (let i = 0; i < sorted.length - 1; i++) {
    const startT = sorted[i];
    const endT = sorted[i + 1];
    if (endT - startT <= 1e-9) continue;
    const midpoint = pointAlongSegment(from, to, (startT + endT) / 2);
    if (!pointInsideIndexed(index, midpoint)) continue;
    const left = pointAlongSegment(from, to, startT);
    const right = pointAlongSegment(from, to, endT);
    if (pointsAlmostEqual(left, right)) continue;
    result.push([left, right]);
  }
  return result;
}

// ---------- 二进制可渲染数据整体裁剪 ----------

// 32 位数值混合哈希：裁剪分段身份只需 (hash,hash2) 对内唯一且确定
function mixHash32(hash, value) {
  let mixed = (hash ^ (value >>> 0)) >>> 0;
  mixed = Math.imul(mixed, 2654435761) >>> 0;
  return (mixed ^ (mixed >>> 15)) >>> 0;
}

// 坐标量化到 1/16 米：仅用于分段身份哈希
function quantCoord(value) {
  return Math.round(Number(value) * 16) >>> 0;
}

function calcFlowStats(flow) {
  let minFlow = Infinity;
  let maxFlow = -Infinity;
  for (let i = 0; i < flow.length; i++) {
    const value = Number(flow[i]) || 0;
    if (value <= 0) continue;
    minFlow = Math.min(minFlow, value);
    maxFlow = Math.max(maxFlow, value);
  }
  if (!Number.isFinite(minFlow) || !Number.isFinite(maxFlow)) {
    return { minFlow: 0, maxFlow: 0 };
  }
  return { minFlow, maxFlow };
}

function emptyClippedData(version) {
  return {
    binary: true,
    count: 0,
    origin: [0, 0],
    hash: EMPTY_UINT32,
    hash2: EMPTY_UINT32,
    source: EMPTY_FLOAT64,
    target: EMPTY_FLOAT64,
    flow: EMPTY_FLOAT32,
    length: EMPTY_FLOAT32,
    lanes: EMPTY_FLOAT32,
    minFlow: 0,
    maxFlow: 0,
    version,
  };
}

/**
 * 按行政区索引裁剪二进制路网数据（主线程回退与 worker 共用）。
 * 单趟裁剪同时写入自增长 TypedArray（容量翻倍），避免普通数组 push + TypedArray.from 的双份拷贝。
 */
export function clipRenderableBinaryData(data, index, version = data?.version || 0) {
  if (!index?.context || !data?.count) return data || emptyClippedData(version);

  // 坐标数组保持与输入同精度（粗档位 f32 合并结果裁剪后不膨胀回 f64）
  const PositionArray = data.source instanceof Float32Array ? Float32Array : Float64Array;
  let capacity = Math.max(64, data.count);
  let hash = new Uint32Array(capacity);
  let hash2 = new Uint32Array(capacity);
  let source = new PositionArray(capacity * 2);
  let target = new PositionArray(capacity * 2);
  let flow = new Float32Array(capacity);
  let length = new Float32Array(capacity);
  let lanes = new Float32Array(capacity);
  let count = 0;

  const grow = () => {
    capacity *= 2;
    const nextHash = new Uint32Array(capacity); nextHash.set(hash); hash = nextHash;
    const nextHash2 = new Uint32Array(capacity); nextHash2.set(hash2); hash2 = nextHash2;
    const nextSource = new PositionArray(capacity * 2); nextSource.set(source); source = nextSource;
    const nextTarget = new PositionArray(capacity * 2); nextTarget.set(target); target = nextTarget;
    const nextFlow = new Float32Array(capacity); nextFlow.set(flow); flow = nextFlow;
    const nextLength = new Float32Array(capacity); nextLength.set(length); length = nextLength;
    const nextLanes = new Float32Array(capacity); nextLanes.set(lanes); lanes = nextLanes;
  };

  const segStart = [0, 0];
  const segEnd = [0, 0];
  for (let i = 0; i < data.count; i++) {
    segStart[0] = data.source[i * 2];
    segStart[1] = data.source[i * 2 + 1];
    segEnd[0] = data.target[i * 2];
    segEnd[1] = data.target[i * 2 + 1];
    const clippedSegments = clipSegmentWithIndex(index, segStart, segEnd);
    for (let segmentIndex = 0; segmentIndex < clippedSegments.length; segmentIndex++) {
      const fromPoint = clippedSegments[segmentIndex][0];
      const toPoint = clippedSegments[segmentIndex][1];
      if (count >= capacity) grow();
      let hashA = mixHash32((data.hash?.[i] || 0) >>> 0, segmentIndex + 1);
      hashA = mixHash32(hashA, quantCoord(fromPoint[0]));
      hashA = mixHash32(hashA, quantCoord(toPoint[1]));
      let hashB = mixHash32((data.hash2?.[i] || 0) >>> 0, segmentIndex + 1);
      hashB = mixHash32(hashB, quantCoord(fromPoint[1]));
      hashB = mixHash32(hashB, quantCoord(toPoint[0]));
      hash[count] = hashA;
      hash2[count] = hashB;
      source[count * 2] = fromPoint[0];
      source[count * 2 + 1] = fromPoint[1];
      target[count * 2] = toPoint[0];
      target[count * 2 + 1] = toPoint[1];
      flow[count] = Number(data.flow?.[i]) || 0;
      length[count] = Number(data.length?.[i]) || 0;
      lanes[count] = Number(data.lanes?.[i]) || 1;
      count++;
    }
  }

  if (!count) return emptyClippedData(version);
  const flowOut = flow.slice(0, count);
  const stats = calcFlowStats(flowOut);
  return {
    binary: true,
    count,
    origin: [0, 0],
    hash: hash.slice(0, count),
    hash2: hash2.slice(0, count),
    source: source.slice(0, count * 2),
    target: target.slice(0, count * 2),
    flow: flowOut,
    length: length.slice(0, count),
    lanes: lanes.slice(0, count),
    minFlow: stats.minFlow,
    maxFlow: stats.maxFlow,
    version,
  };
}
