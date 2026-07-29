import {
  isVehicleModeVisible,
  normalizeVehicleVisibility,
} from "../../../utils/vehicleVisibility.js";

const EARTH_RADIUS = 6378137.0;
const BINARY_STRIDE = 9;
const MODE_KEYS = ["bus", "subway", "car"];
const MODE_CODE_TO_KEY = ["bus", "subway", "car"];
const MIN_FRAME_CAPACITY = 1024;
// 帧缓冲池上限：池只服务 vehicle-frame 路径（主用的 GPU 段帧每秒 8 个小数组、GC 可承受），
// 96MB 按 byteLength 分桶最坏可长期滞留，收敛到 32MB 足够覆盖峰值车数
const MAX_POOLED_FRAME_BYTES = 32 * 1024 * 1024;
const COMPACT_SECOND_INDEX_MAX_REFS = 8_000_000;
const CHUNK_STORE_MEMORY_GB = Math.max(4, Math.min(8, Number(globalThis.navigator?.deviceMemory) || 6));
const MAX_CHUNK_STORE_BYTES = Math.round(CHUNK_STORE_MEMORY_GB * 48 * 1024 * 1024);
const MAX_CHUNK_STORE_COUNT = 6;
const MODE_KEY_TO_CODE = MODE_KEYS.reduce((map, mode, index) => {
  map[mode] = index;
  return map;
}, {});

let currentVisibilityMode = normalizeVehicleVisibility();
let pooledFrameBytes = 0;
const frameBufferPool = new Map();

// 多块缓存（动态前瞻 / 预取）：每个分块只建一次每秒索引并常驻，切块时仅切换 activeKey。
// v14 主播放交付 10s/块，50x 会提前常驻 3–4 块；块长始终以 header 为准。
// 按 LRU + 字节预算淘汰，且永不淘汰当前活动块。
let datasetVersion = 0;
const chunkStore = new Map(); // key -> { kind, ..., index, bytes, lastUsedAt }
let activeKey = null;
let chunkStoreBytes = 0;
let chunkUseCounter = 0;
let visibleOffsetScratch = new Int32Array(0);

function ensureVisibleOffsetScratch(count) {
  const required = Math.max(0, Number(count) || 0);
  if (visibleOffsetScratch.length >= required) return visibleOffsetScratch;
  visibleOffsetScratch = new Int32Array(nextFrameCapacity(required));
  return visibleOffsetScratch;
}

function normalizeBounds(bounds) {
  if (!bounds) return null;
  const minX = Number(bounds.minX);
  const minY = Number(bounds.minY);
  const maxX = Number(bounds.maxX);
  const maxY = Number(bounds.maxY);
  if (![minX, minY, maxX, maxY].every(Number.isFinite) || maxX <= minX || maxY <= minY) {
    return null;
  }
  return { minX, minY, maxX, maxY };
}

function segmentIntersectsBounds(sx, sy, ex, ey, originX, originY, bounds) {
  if (!bounds) return true;
  const minX = Math.min(sx, ex) + originX;
  const maxX = Math.max(sx, ex) + originX;
  const minY = Math.min(sy, ey) + originY;
  const maxY = Math.max(sy, ey) + originY;
  return maxX >= bounds.minX && minX <= bounds.maxX && maxY >= bounds.minY && minY <= bounds.maxY;
}

function nextFrameCapacity(count) {
  let capacity = MIN_FRAME_CAPACITY;
  while (capacity < count) {
    capacity *= 2;
  }
  return capacity;
}

function takeBuffer(byteLength) {
  const bytes = Math.max(0, Number(byteLength) || 0);
  if (bytes <= 0) return new ArrayBuffer(0);
  const pool = frameBufferPool.get(bytes);
  if (pool?.length) {
    pooledFrameBytes -= bytes;
    return pool.pop();
  }
  return new ArrayBuffer(bytes);
}

function releaseBuffers(buffers = []) {
  for (const buffer of buffers) {
    if (!(buffer instanceof ArrayBuffer) || buffer.byteLength <= 0) continue;
    if (pooledFrameBytes + buffer.byteLength > MAX_POOLED_FRAME_BYTES) continue;
    const bytes = buffer.byteLength;
    if (!frameBufferPool.has(bytes)) {
      frameBufferPool.set(bytes, []);
    }
    frameBufferPool.get(bytes).push(buffer);
    pooledFrameBytes += bytes;
  }
}

function clearBufferPool() {
  frameBufferPool.clear();
  pooledFrameBytes = 0;
}

function createFrameBuffers(count) {
  const capacity = nextFrameCapacity(Math.max(0, Number(count) || 0));
  return {
    xs: new Float64Array(takeBuffer(capacity * Float64Array.BYTES_PER_ELEMENT)),
    ys: new Float64Array(takeBuffer(capacity * Float64Array.BYTES_PER_ELEMENT)),
    angles: new Float32Array(takeBuffer(capacity * Float32Array.BYTES_PER_ELEMENT)),
    speeds: new Float32Array(takeBuffer(capacity * Float32Array.BYTES_PER_ELEMENT)),
    modes: new Uint8Array(takeBuffer(capacity * Uint8Array.BYTES_PER_ELEMENT)),
    ids: new Int32Array(takeBuffer(capacity * Int32Array.BYTES_PER_ELEMENT)),
  };
}

function webMercatorToLngLat(x, y) {
  const lng = (Number(x) / EARTH_RADIUS) * (180 / Math.PI);
  const lat = (2 * Math.atan(Math.exp(Number(y) / EARTH_RADIUS)) - Math.PI / 2) * (180 / Math.PI);
  return [lng, lat];
}

function normalizeMode(mode) {
  return MODE_KEYS.includes(mode) ? mode : "car";
}

function modeKeyFromCode(code) {
  return MODE_CODE_TO_KEY[Math.round(Number(code) || 0)] || "car";
}

function binaryIntValues(values) {
  if (!(values instanceof Float32Array)) return null;
  return new Int32Array(values.buffer, values.byteOffset, values.length);
}

function binaryVehicleIndex(data, offset) {
  return data?.segmentInts?.[offset + 7] ?? 0;
}

function binaryDistanceMeters(values, offset) {
  const distance = Number(values?.[offset + 8]);
  return Number.isFinite(distance) && distance >= 0 ? distance : 0;
}

function isModeVisible(mode, visibilityMode = currentVisibilityMode) {
  return isVehicleModeVisible(mode, visibilityMode);
}

function emptyModeCount() {
  return MODE_KEYS.reduce((map, mode) => {
    map[mode] = 0;
    return map;
  }, {});
}

function emptyStats() {
  return {
    activeTotal: 0,
    activeByMode: emptyModeCount(),
    avgSpeed: 0,
    routeActive: {},
  };
}

function normalizeVehicles(vehicles = []) {
  return vehicles
    .map((vehicle) => {
      const segments = (vehicle.segments || [])
        .map((segment) => [
          Number(segment[0]),
          Number(segment[1]),
          Number(segment[2]),
          Number(segment[3]),
          Number(segment[4]),
          Number(segment[5]),
        ])
        .filter((segment) => segment.every(Number.isFinite) && segment[1] > segment[0])
        .sort((a, b) => a[0] - b[0]);

      return {
        id: vehicle.id,
        mode: normalizeMode(vehicle.mode),
        lineId: vehicle.lineId || "",
        routeId: vehicle.routeId || "",
        distance: Number(vehicle.distance) || 0,
        segments,
      };
    })
    .filter((vehicle) => vehicle.segments.length > 0);
}

function binarySegmentSecondRange(values, offset, start, seconds) {
  const segmentStart = values[offset];
  const segmentEnd = values[offset + 1];
  if (!Number.isFinite(segmentStart) || !Number.isFinite(segmentEnd) || segmentEnd <= segmentStart) {
    return null;
  }
  const firstSecond = Math.max(start, Math.floor(segmentStart));
  const lastExclusive = Math.min(start + seconds, Math.ceil(segmentEnd));
  if (lastExclusive <= firstSecond) return null;
  return [firstSecond - start, lastExclusive - start];
}

function buildCompactBinarySecondIndex(data, start, seconds, totalRefs) {
  const values = data?.segments;
  const bucketCounts = new Int32Array(seconds);
  for (let offset = 0; offset < values.length; offset += BINARY_STRIDE) {
    const range = binarySegmentSecondRange(values, offset, start, seconds);
    if (!range) continue;
    for (let index = range[0]; index < range[1]; index++) {
      bucketCounts[index] += 1;
    }
  }

  const bucketStarts = new Int32Array(seconds + 1);
  for (let index = 0; index < seconds; index++) {
    bucketStarts[index + 1] = bucketStarts[index] + bucketCounts[index];
  }
  const offsets = new Int32Array(totalRefs);
  const writePositions = new Int32Array(bucketStarts);
  for (let offset = 0; offset < values.length; offset += BINARY_STRIDE) {
    const range = binarySegmentSecondRange(values, offset, start, seconds);
    if (!range) continue;
    for (let index = range[0]; index < range[1]; index++) {
      offsets[writePositions[index]++] = offset;
    }
  }

  return {
    kind: "second",
    start,
    seconds,
    bucketStarts,
    offsets,
    bytes: bucketStarts.byteLength + offsets.byteLength,
  };
}

function buildVehicleCursorIndex(data) {
  const values = data?.segments;
  const byVehicle = new Map();
  for (let offset = 0; offset < values.length; offset += BINARY_STRIDE) {
    const segmentStart = values[offset];
    const segmentEnd = values[offset + 1];
    if (!Number.isFinite(segmentStart) || !Number.isFinite(segmentEnd) || segmentEnd <= segmentStart) continue;
    const vehicleIndex = binaryVehicleIndex(data, offset);
    let offsets = byVehicle.get(vehicleIndex);
    if (!offsets) {
      offsets = [];
      byVehicle.set(vehicleIndex, offsets);
    }
    offsets.push(offset);
  }

  let bytes = 0;
  const entries = [];
  for (const [vehicleIndex, offsets] of byVehicle) {
    offsets.sort((a, b) => values[a] - values[b]);
    const typedOffsets = Int32Array.from(offsets);
    bytes += typedOffsets.byteLength;
    entries.push({
      vehicleIndex,
      offsets: typedOffsets,
      cursor: 0,
    });
  }

  return {
    kind: "vehicle",
    entries,
    bytes,
  };
}

function buildBinarySecondIndex(data) {
  const values = data?.segments;
  if (!values?.length) return null;
  // 二进制 vehicleId 与 float 字段共用同一块内存；入口既可能来自 Worker 的
  // buildIndexedChunk，也可能是测试/恢复路径直接调用，统一在这里补齐 int32 视图。
  if (!data.segmentInts) data.segmentInts = binaryIntValues(values);
  const start = Math.max(0, Math.floor(Number(data.chunk?.start) || 0));
  const seconds = Math.max(1, Math.ceil(Number(data.chunkSeconds || data.chunk?.end - start + 1 || 300)));
  let totalRefs = 0;
  for (let offset = 0; offset < values.length; offset += BINARY_STRIDE) {
    const range = binarySegmentSecondRange(values, offset, start, seconds);
    if (!range) continue;
    totalRefs += range[1] - range[0];
    if (totalRefs > COMPACT_SECOND_INDEX_MAX_REFS) {
      return buildVehicleCursorIndex(data);
    }
  }
  return buildCompactBinarySecondIndex(data, start, seconds, totalRefs);
}

function buildJsonSecondIndex(vehicles, data = {}) {
  if (!vehicles?.length) return null;
  const chunkStart = Number(data?.chunk?.start);
  const start = Number.isFinite(chunkStart)
    ? Math.max(0, Math.floor(chunkStart))
    : Math.max(0, Math.floor(Math.min(...vehicles.flatMap((vehicle) => vehicle.segments.map((segment) => segment[0])))));
  const maxEnd = Math.max(...vehicles.flatMap((vehicle) => vehicle.segments.map((segment) => segment[1])));
  const seconds = Math.max(1, Math.ceil((Number(data?.chunk?.end) || maxEnd) - start + 1));
  const buckets = Array.from({ length: seconds + 1 }, () => []);
  for (const vehicle of vehicles) {
    for (const segment of vehicle.segments) {
      const firstSecond = Math.max(start, Math.ceil(segment[0]));
      const lastExclusive = Math.min(start + seconds, Math.ceil(segment[1]));
      for (let second = firstSecond; second < lastExclusive; second++) {
        const index = second - start;
        if (index >= 0 && index < buckets.length) {
          buckets[index].push({ vehicle, segment });
        }
      }
    }
  }
  return { start, buckets };
}

function binaryOffsetsForTime(time, values, index) {
  if (!index) {
    const offsets = [];
    for (let offset = 0; offset < values.length; offset += BINARY_STRIDE) {
      offsets.push(offset);
    }
    return offsets;
  }
  const bucketIndex = Math.floor(time) - index.start;
  if (index.kind === "second") {
    if (bucketIndex < 0 || bucketIndex >= index.seconds) {
      return new Int32Array(0);
    }
    return index.offsets.subarray(index.bucketStarts[bucketIndex], index.bucketStarts[bucketIndex + 1]);
  }
  if (bucketIndex < 0 || bucketIndex >= index.buckets?.length) {
    return [];
  }
  return index.buckets[bucketIndex];
}

function vehicleOffsetAtTime(entry, time, values) {
  const offsets = entry?.offsets;
  if (!offsets?.length) return -1;

  let cursor = Math.max(0, Math.min(offsets.length - 1, Number(entry.cursor) || 0));
  let offset = offsets[cursor];
  if (time >= values[offset] && time < values[offset + 1]) {
    return offset;
  }

  if (time >= values[offset + 1]) {
    while (cursor + 1 < offsets.length && time >= values[offsets[cursor] + 1]) {
      cursor += 1;
    }
    offset = offsets[cursor];
    if (time >= values[offset] && time < values[offset + 1]) {
      entry.cursor = cursor;
      return offset;
    }
  }

  let left = 0;
  let right = offsets.length - 1;
  let best = -1;
  while (left <= right) {
    const mid = (left + right) >> 1;
    if (values[offsets[mid]] <= time) {
      best = mid;
      left = mid + 1;
    } else {
      right = mid - 1;
    }
  }
  if (best < 0) {
    entry.cursor = 0;
    return -1;
  }
  offset = offsets[best];
  entry.cursor = best;
  return time < values[offset + 1] ? offset : -1;
}

function jsonSegmentsForTime(time, vehicles, index) {
  if (!index) {
    return vehicles.flatMap((vehicle) => vehicle.segments.map((segment) => ({ vehicle, segment })));
  }
  const bucketIndex = Math.floor(time) - index.start;
  if (bucketIndex < 0 || bucketIndex >= index.buckets.length) {
    return [];
  }
  return index.buckets[bucketIndex];
}

function activeFromBinary(time, data) {
  const values = data?.segments;
  if (!values?.length) {
    return { frame: emptyFrame(), stats: emptyStats(), transfer: [] };
  }

  const [originX = 0, originY = 0] = data.origin || [];
  if (data.index?.kind === "vehicle") {
    return activeFromBinaryVehicleIndex(time, data, originX, originY);
  }
  const offsets = binaryOffsetsForTime(time, values, data.index);
  const activeByMode = emptyModeCount();
  let speedTotal = 0;
  let speedCount = 0;
  const capacity = offsets.length;
  const { xs, ys, angles, speeds, modes, ids } = createFrameBuffers(capacity);
  let count = 0;

  for (const offset of offsets) {
    const startTime = values[offset];
    const endTime = values[offset + 1];
    if (time < startTime || time >= endTime) continue;

    const mode = modeKeyFromCode(values[offset + 6]);
    if (!isModeVisible(mode)) continue;
    const sx = values[offset + 2];
    const sy = values[offset + 3];
    const ex = values[offset + 4];
    const ey = values[offset + 5];
    const duration = Math.max(endTime - startTime, 0.001);
    const ratio = Math.min(1, Math.max(0, (time - startTime) / duration));
    const x = sx + (ex - sx) * ratio;
    const y = sy + (ey - sy) * ratio;
    const dx = ex - sx;
    const dy = ey - sy;
    const speed = (binaryDistanceMeters(values, offset) / duration) * 3.6;
    const webMercator = [originX + x, originY + y];
    const vehicleIndex = binaryVehicleIndex(data, offset);

    xs[count] = webMercator[0];
    ys[count] = webMercator[1];
    angles[count] = Math.atan2(dy, dx) * 180 / Math.PI;
    speeds[count] = speed;
    modes[count] = MODE_KEY_TO_CODE[mode] ?? 2;
    ids[count] = vehicleIndex;
    count += 1;
    activeByMode[mode] += 1;
    if (Number.isFinite(speed) && speed > 0 && speed < 180) {
      speedTotal += speed;
      speedCount += 1;
    }
  }

  const frame = createFrame({ count, xs, ys, angles, speeds, modes, ids });
  return {
    frame,
    stats: {
      activeTotal: count,
      activeByMode,
      avgSpeed: speedCount ? Math.round((speedTotal / speedCount) * 10) / 10 : 0,
      routeActive: {},
    },
    transfer: frameTransfer(frame),
  };
}

function activeFromBinaryVehicleIndex(time, data, originX, originY) {
  const values = data?.segments;
  const entries = data.index?.entries || [];
  const activeByMode = emptyModeCount();
  let speedTotal = 0;
  let speedCount = 0;
  const { xs, ys, angles, speeds, modes, ids } = createFrameBuffers(entries.length);
  let count = 0;

  for (const entry of entries) {
    const offset = vehicleOffsetAtTime(entry, time, values);
    if (offset < 0) continue;

    const startTime = values[offset];
    const endTime = values[offset + 1];
    if (time < startTime || time >= endTime) continue;

    const mode = modeKeyFromCode(values[offset + 6]);
    if (!isModeVisible(mode)) continue;
    const sx = values[offset + 2];
    const sy = values[offset + 3];
    const ex = values[offset + 4];
    const ey = values[offset + 5];
    const duration = Math.max(endTime - startTime, 0.001);
    const ratio = Math.min(1, Math.max(0, (time - startTime) / duration));
    const x = sx + (ex - sx) * ratio;
    const y = sy + (ey - sy) * ratio;
    const dx = ex - sx;
    const dy = ey - sy;
    const speed = (binaryDistanceMeters(values, offset) / duration) * 3.6;

    xs[count] = originX + x;
    ys[count] = originY + y;
    angles[count] = Math.atan2(dy, dx) * 180 / Math.PI;
    speeds[count] = speed;
    modes[count] = MODE_KEY_TO_CODE[mode] ?? 2;
    ids[count] = binaryVehicleIndex(data, offset);
    count += 1;
    activeByMode[mode] += 1;
    if (Number.isFinite(speed) && speed > 0 && speed < 180) {
      speedTotal += speed;
      speedCount += 1;
    }
  }

  const frame = createFrame({ count, xs, ys, angles, speeds, modes, ids });
  return {
    frame,
    stats: {
      activeTotal: count,
      activeByMode,
      avgSpeed: speedCount ? Math.round((speedTotal / speedCount) * 10) / 10 : 0,
      routeActive: {},
    },
    transfer: frameTransfer(frame),
  };
}

function binaryOffsetsForSecondWindow(second, values, index, windowSeconds = 1) {
  const windowStart = Math.floor(Number(second) || 0);
  const windowSize = Math.max(1, Math.min(8, Math.floor(Number(windowSeconds) || 1)));
  const windowEnd = windowStart + windowSize;
  if (!index) {
    const offsets = [];
    for (let offset = 0; offset < values.length; offset += BINARY_STRIDE) {
      if (values[offset] < windowEnd && values[offset + 1] > windowStart) {
        offsets.push(offset);
      }
    }
    return offsets;
  }
  if (index.kind === "second") {
    const bucketIndex = windowStart - index.start;
    if (bucketIndex < 0 || bucketIndex >= index.seconds) {
      return new Int32Array(0);
    }
    if (windowSize === 1) {
      return index.offsets.subarray(index.bucketStarts[bucketIndex], index.bucketStarts[bucketIndex + 1]);
    }
    const seen = new Set();
    const offsets = [];
    const lastBucket = Math.min(index.seconds, bucketIndex + windowSize);
    for (let bucket = bucketIndex; bucket < lastBucket; bucket++) {
      const from = index.bucketStarts[bucket];
      const to = index.bucketStarts[bucket + 1];
      for (let i = from; i < to; i++) {
        const offset = index.offsets[i];
        if (seen.has(offset)) continue;
        seen.add(offset);
        offsets.push(offset);
      }
    }
    return offsets;
  }
  if (index.kind === "vehicle") {
    const offsets = [];
    for (const entry of index.entries || []) {
      const list = entry.offsets || [];
      if (!list.length) continue;
      let cursor = Math.max(0, Math.min(list.length - 1, Number(entry.cursor) || 0));
      while (cursor > 0 && values[list[cursor]] >= windowStart) cursor -= 1;
      while (cursor < list.length && values[list[cursor] + 1] <= windowStart) cursor += 1;
      for (let i = cursor; i < list.length; i++) {
        const offset = list[i];
        if (values[offset] >= windowEnd) break;
        if (values[offset + 1] > windowStart) {
          offsets.push(offset);
        }
      }
    }
    return offsets;
  }
  return [];
}

function segmentFrameTransfer(frame) {
  return [
    frame.startXs.buffer,
    frame.startYs.buffer,
    frame.endXs.buffer,
    frame.endYs.buffer,
    frame.startTimes.buffer,
    frame.endTimes.buffer,
    frame.distances.buffer,
    frame.modes.buffer,
    frame.ids.buffer,
  ];
}

function createSegmentFrame({
  bucketSecond,
  origin,
  count,
  startXs,
  startYs,
  endXs,
  endYs,
  startTimes,
  endTimes,
  distances,
  modes,
  ids,
}) {
  return {
    kind: "vehicle-segment-frame",
    bucketSecond,
    origin,
    count,
    startXs,
    startYs,
    endXs,
    endYs,
    startTimes,
    endTimes,
    distances,
    modes,
    ids,
  };
}

function segmentFrameFromBinary(time, data, windowSeconds = 1, requestedBounds = null) {
  const values = data?.segments;
  if (!values?.length) {
    const windowSize = Math.max(1, Math.min(8, Math.floor(Number(windowSeconds) || 1)));
    const bucketSecond = Math.floor(Math.max(0, Number(time) || 0) / windowSize) * windowSize;
    const frame = createSegmentFrame({
      bucketSecond,
      origin: data?.origin || [0, 0],
      count: 0,
      startXs: new Float32Array(0),
      startYs: new Float32Array(0),
      endXs: new Float32Array(0),
      endYs: new Float32Array(0),
      startTimes: new Float32Array(0),
      endTimes: new Float32Array(0),
      distances: new Float32Array(0),
      modes: new Uint8Array(0),
      ids: new Int32Array(0),
    });
    return { segmentFrame: frame, stats: emptyStats(), transfer: segmentFrameTransfer(frame) };
  }

  const seconds = Math.max(0, Number(time) || 0);
  const windowSize = Math.max(1, Math.min(8, Math.floor(Number(windowSeconds) || 1)));
  const bucketSecond = Math.floor(seconds / windowSize) * windowSize;
  const offsets = binaryOffsetsForSecondWindow(bucketSecond, values, data.index, windowSize);
  const bounds = normalizeBounds(requestedBounds);
  const [originX = 0, originY = 0] = data.origin || [];
  // 全市候选只留在 Worker：右侧统计仍按全市计算，但只把视口附近的段传回主线程/GPU。
  const visibleOffsets = ensureVisibleOffsetScratch(offsets.length);
  const activeByMode = emptyModeCount();
  let speedTotal = 0;
  let speedCount = 0;
  let activeTotal = 0;
  let visibleCount = 0;

  for (const offset of offsets) {
    const startTime = values[offset];
    const endTime = values[offset + 1];
    if (!Number.isFinite(startTime) || !Number.isFinite(endTime) || endTime <= startTime) continue;

    const mode = modeKeyFromCode(values[offset + 6]);
    if (!isModeVisible(mode)) continue;
    const sx = values[offset + 2];
    const sy = values[offset + 3];
    const ex = values[offset + 4];
    const ey = values[offset + 5];
    const dx = ex - sx;
    const dy = ey - sy;
    const duration = Math.max(endTime - startTime, 0.001);
    const speed = (binaryDistanceMeters(values, offset) / duration) * 3.6;

    if (seconds >= startTime && seconds < endTime) {
      activeTotal += 1;
      activeByMode[mode] += 1;
      if (Number.isFinite(speed) && speed > 0 && speed < 180) {
        speedTotal += speed;
        speedCount += 1;
      }
    }
    if (segmentIntersectsBounds(sx, sy, ex, ey, originX, originY, bounds)) {
      visibleOffsets[visibleCount++] = offset;
    }
  }

  const startXs = new Float32Array(visibleCount);
  const startYs = new Float32Array(visibleCount);
  const endXs = new Float32Array(visibleCount);
  const endYs = new Float32Array(visibleCount);
  const startTimes = new Float32Array(visibleCount);
  const endTimes = new Float32Array(visibleCount);
  const distances = new Float32Array(visibleCount);
  const modes = new Uint8Array(visibleCount);
  const ids = new Int32Array(visibleCount);
  for (let index = 0; index < visibleCount; index++) {
    const offset = visibleOffsets[index];
    startTimes[index] = values[offset];
    endTimes[index] = values[offset + 1];
    distances[index] = binaryDistanceMeters(values, offset);
    startXs[index] = values[offset + 2];
    startYs[index] = values[offset + 3];
    endXs[index] = values[offset + 4];
    endYs[index] = values[offset + 5];
    modes[index] = Math.max(0, Math.min(2, Math.round(Number(values[offset + 6]) || 0)));
    ids[index] = binaryVehicleIndex(data, offset);
  }

  const frame = createSegmentFrame({
    bucketSecond,
    origin: data.origin || [0, 0],
    count: visibleCount,
    startXs,
    startYs,
    endXs,
    endYs,
    startTimes,
    endTimes,
    distances,
    modes,
    ids,
  });
  return {
    segmentFrame: frame,
    stats: {
      activeTotal,
      activeByMode,
      avgSpeed: speedCount ? Math.round((speedTotal / speedCount) * 10) / 10 : 0,
      routeActive: {},
    },
    transfer: segmentFrameTransfer(frame),
  };
}

function activeFromJson(time, data) {
  const vehicles = data?.vehicles || [];
  const activeByMode = emptyModeCount();
  const routeActive = {};
  const candidates = jsonSegmentsForTime(time, vehicles, data.index);
  const capacity = candidates.length;
  const { xs, ys, angles, speeds, modes, ids } = createFrameBuffers(capacity);
  const keys = [];
  let count = 0;
  let speedTotal = 0;
  let speedCount = 0;

  for (const item of candidates) {
    const vehicle = item.vehicle;
    const segment = item.segment;
    if (!segment) continue;
    if (time < segment[0] || time >= segment[1]) continue;
    if (!isModeVisible(vehicle.mode)) continue;
    const duration = Math.max(segment[1] - segment[0], 0.001);
    const ratio = Math.min(1, Math.max(0, (time - segment[0]) / duration));
    const x = segment[2] + (segment[4] - segment[2]) * ratio;
    const y = segment[3] + (segment[5] - segment[3]) * ratio;
    const dx = segment[4] - segment[2];
    const dy = segment[5] - segment[3];
    const speed = (Math.sqrt(dx * dx + dy * dy) / duration) * 3.6;
    const webMercator = [x, y];
    const key = `json:${vehicle.id || `${vehicle.mode}:${segment[0]}:${segment[1]}`}`;
    xs[count] = webMercator[0];
    ys[count] = webMercator[1];
    angles[count] = Math.atan2(dy, dx) * 180 / Math.PI;
    speeds[count] = speed;
    modes[count] = MODE_KEY_TO_CODE[vehicle.mode] ?? 2;
    ids[count] = count;
    keys[count] = key;
    count += 1;
    activeByMode[vehicle.mode] += 1;
    if (vehicle.routeId) {
      routeActive[vehicle.routeId] = (routeActive[vehicle.routeId] || 0) + 1;
    }
    if (Number.isFinite(speed) && speed > 0 && speed < 180) {
      speedTotal += speed;
      speedCount += 1;
    }
  }

  const frame = createFrame({ count, xs, ys, angles, speeds, modes, ids, keys });
  return {
    frame,
    stats: {
      activeTotal: count,
      activeByMode,
      avgSpeed: speedCount ? Math.round((speedTotal / speedCount) * 10) / 10 : 0,
      routeActive,
    },
    transfer: frameTransfer(frame),
  };
}

function emptyFrame() {
  return createFrame({
    count: 0,
    xs: new Float64Array(0),
    ys: new Float64Array(0),
    angles: new Float32Array(0),
    speeds: new Float32Array(0),
    modes: new Uint8Array(0),
    ids: new Int32Array(0),
  });
}

function createFrame({ count, xs, ys, angles, speeds, modes, ids, keys = null }) {
  return {
    kind: "vehicle-frame",
    count,
    xs,
    ys,
    angles,
    speeds,
    modes,
    ids,
    keys,
  };
}

export function frameTransfer(frame) {
  return [
    frame.xs.buffer,
    frame.ys.buffer,
    frame.angles.buffer,
    frame.speeds.buffer,
    frame.modes.buffer,
    frame.ids.buffer,
  ];
}

function chunkBytesOf(indexed) {
  if (indexed?.kind === "binary") {
    return (indexed.segments?.byteLength || 0) + (indexed.index?.bytes || 0);
  }
  if (indexed?.kind === "json") return (indexed.vehicles?.length || 0) * 64 + (indexed.index?.bytes || 0);
  return 0;
}

function buildIndexedChunk(data) {
  if (data?.binary) {
    const segments = data.segments instanceof Float32Array
      ? data.segments
      : new Float32Array(data.segments || []);
    const indexed = {
      kind: "binary",
      origin: data.origin || [0, 0],
      chunk: data.chunk || {},
      chunkSeconds: data.chunkSeconds,
      segments,
      segmentInts: binaryIntValues(segments),
    };
    indexed.index = buildBinarySecondIndex(indexed);
    indexed.bytes = chunkBytesOf(indexed);
    return indexed;
  }
  const vehicles = normalizeVehicles(data?.vehicles || []);
  const indexed = {
    kind: "json",
    chunk: data?.chunk || {},
    vehicles,
  };
  indexed.index = buildJsonSecondIndex(vehicles, data || {});
  indexed.bytes = chunkBytesOf(indexed);
  return indexed;
}

function storeChunk(key, indexed) {
  const existing = chunkStore.get(key);
  if (existing) chunkStoreBytes -= existing.bytes || 0;
  indexed.lastUsedAt = ++chunkUseCounter;
  chunkStore.set(key, indexed);
  chunkStoreBytes += indexed.bytes || 0;
  return evictChunks();
}

function touchChunk(key) {
  const indexed = chunkStore.get(key);
  if (indexed) indexed.lastUsedAt = ++chunkUseCounter;
  return indexed;
}

function evictChunks() {
  const evictedKeys = [];
  while (chunkStore.size > MAX_CHUNK_STORE_COUNT || chunkStoreBytes > MAX_CHUNK_STORE_BYTES) {
    let victimKey = null;
    let victimUsed = Infinity;
    for (const [key, indexed] of chunkStore) {
      if (key === activeKey) continue;
      const used = indexed.lastUsedAt || 0;
      if (used < victimUsed) {
        victimUsed = used;
        victimKey = key;
      }
    }
    if (victimKey === null) break;
    const victim = chunkStore.get(victimKey);
    chunkStore.delete(victimKey);
    chunkStoreBytes -= victim?.bytes || 0;
    evictedKeys.push(victimKey);
  }
  return evictedKeys;
}

function clearChunkStore() {
  chunkStore.clear();
  chunkStoreBytes = 0;
  activeKey = null;
}

function activeAtChunk(indexed, seconds, visibilityMode = currentVisibilityMode) {
  currentVisibilityMode = normalizeVehicleVisibility(visibilityMode);
  if (!indexed) {
    return { frame: emptyFrame(), stats: emptyStats(), transfer: [] };
  }
  return indexed.kind === "binary"
    ? activeFromBinary(seconds, indexed)
    : activeFromJson(seconds, indexed);
}

function segmentFrameAtChunk(indexed, seconds, visibilityMode = currentVisibilityMode, bucketSeconds = 1, bounds = null) {
  currentVisibilityMode = normalizeVehicleVisibility(visibilityMode);
  if (!indexed) {
    const frame = createSegmentFrame({
      bucketSecond: Math.floor(Number(seconds) || 0),
      origin: [0, 0],
      count: 0,
      startXs: new Float32Array(0),
      startYs: new Float32Array(0),
      endXs: new Float32Array(0),
      endYs: new Float32Array(0),
      startTimes: new Float32Array(0),
      endTimes: new Float32Array(0),
      distances: new Float32Array(0),
      modes: new Uint8Array(0),
      ids: new Int32Array(0),
    });
    return { segmentFrame: frame, stats: emptyStats(), transfer: segmentFrameTransfer(frame) };
  }
  if (indexed.kind === "binary") {
    return segmentFrameFromBinary(seconds, indexed, bucketSeconds, bounds);
  }
  return activeFromJson(seconds, indexed);
}

function respond(id, result, transfer = []) {
  self.postMessage({ id, ok: true, result }, transfer);
}

function reject(id, error) {
  self.postMessage({
    id,
    ok: false,
    error: error?.message || String(error || "worker error"),
  });
}

if (typeof self !== "undefined") self.onmessage = (event) => {
  const message = event.data || {};
  const { id, type } = message;
  try {
    if (type === "releaseFrame") {
      releaseBuffers(message.buffers || []);
      return;
    }

    if (type === "setData") {
      // 建索引并常驻，切为活动块，按 seconds 采样一帧返回。
      const key = message.key != null ? message.key : "__single__";
      const indexed = buildIndexedChunk(message.data || null);
      activeKey = key;
      const evictedKeys = storeChunk(key, indexed);
      const seconds = Number(message.seconds);
      if (Number.isFinite(seconds)) {
        const result = message.gpuSegments
          ? segmentFrameAtChunk(indexed, Math.max(0, seconds), message.visibilityMode, message.bucketSeconds, message.bounds)
          : activeAtChunk(indexed, Math.max(0, seconds), message.visibilityMode);
        const transfer = result.transfer || [];
        delete result.transfer;
        respond(id, {
          version: datasetVersion,
          seconds: Math.max(0, seconds),
          evictedKeys,
          ...result,
        }, transfer);
      } else {
        respond(id, { version: datasetVersion, evictedKeys });
      }
      return;
    }

    if (type === "addChunk") {
      // 预取/预建索引：只建好并常驻，不切换活动块、不采样（双缓冲的关键）。
      const key = message.key != null ? message.key : "__single__";
      let evictedKeys = [];
      if (!chunkStore.has(key)) {
        evictedKeys = storeChunk(key, buildIndexedChunk(message.data || null));
      } else {
        touchChunk(key);
      }
      respond(id, { version: datasetVersion, stored: true, key, evictedKeys });
      return;
    }

    if (type === "activateChunk") {
      // 切到已常驻分块：仅切 activeKey + 采样，零重建。未命中回报 miss，由主线程改走 setData。
      const key = message.key != null ? message.key : "__single__";
      const indexed = touchChunk(key);
      if (!indexed) {
        respond(id, { version: datasetVersion, miss: true, key });
        return;
      }
      activeKey = key;
      const seconds = Math.max(0, Number(message.seconds) || 0);
      const result = message.gpuSegments
        ? segmentFrameAtChunk(indexed, seconds, message.visibilityMode, message.bucketSeconds, message.bounds)
        : activeAtChunk(indexed, seconds, message.visibilityMode);
      const transfer = result.transfer || [];
      delete result.transfer;
      respond(id, {
        version: datasetVersion,
        seconds,
        ...result,
      }, transfer);
      return;
    }

    if (type === "clear") {
      datasetVersion += 1;
      clearChunkStore();
      clearBufferPool();
      respond(id, { version: datasetVersion });
      return;
    }

    if (type === "setSegmentTime") {
      const indexed = activeKey != null ? chunkStore.get(activeKey) : null;
      const seconds = Math.max(0, Number(message.seconds) || 0);
      const result = segmentFrameAtChunk(indexed, seconds, message.visibilityMode, message.bucketSeconds, message.bounds);
      const transfer = result.transfer || [];
      delete result.transfer;
      respond(id, {
        version: datasetVersion,
        seconds,
        ...result,
      }, transfer);
      return;
    }

    if (type === "setTime") {
      const indexed = activeKey != null ? chunkStore.get(activeKey) : null;
      const seconds = Math.max(0, Number(message.seconds) || 0);
      const result = activeAtChunk(indexed, seconds, message.visibilityMode);
      const transfer = result.transfer || [];
      delete result.transfer;
      respond(id, {
        version: datasetVersion,
        seconds,
        ...result,
      }, transfer);
      return;
    }

    reject(id, new Error(`unknown worker message: ${type}`));
  } catch (error) {
    reject(id, error);
  }
};

// 纯函数导出用于千万级合成数据回归测试；Worker 运行时仍走上面的消息协议。
export { buildBinarySecondIndex, segmentFrameFromBinary };
