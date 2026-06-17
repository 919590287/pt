const EARTH_RADIUS = 6378137.0;
const BINARY_STRIDE = 8;
const MODE_KEYS = ["bus", "subway", "car"];
const MODE_CODE_TO_KEY = ["bus", "subway", "car"];
const VEHICLE_VISIBILITY_MODES = ["all", "public", "private"];
const MIN_FRAME_CAPACITY = 1024;
const MAX_POOLED_FRAME_BYTES = 96 * 1024 * 1024;
const MODE_KEY_TO_CODE = MODE_KEYS.reduce((map, mode, index) => {
  map[mode] = index;
  return map;
}, {});

let dataVersion = 0;
let currentData = null;
let currentVisibilityMode = "all";
let pooledFrameBytes = 0;
const frameBufferPool = new Map();

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

function normalizeVisibilityMode(mode) {
  return VEHICLE_VISIBILITY_MODES.includes(mode) ? mode : "all";
}

function isModeVisible(mode, visibilityMode = currentVisibilityMode) {
  const normalized = normalizeVisibilityMode(visibilityMode);
  if (normalized === "public") return mode === "bus" || mode === "subway";
  if (normalized === "private") return mode === "car";
  return true;
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

function buildBinarySecondIndex(data) {
  const values = data?.segments;
  if (!values?.length) return null;
  const start = Math.max(0, Math.floor(Number(data.chunk?.start) || 0));
  const chunkSeconds = Math.max(1, Math.ceil(Number(data.chunkSeconds || data.chunk?.end - start + 1 || 300)));
  const buckets = Array.from({ length: chunkSeconds + 1 }, () => []);
  for (let offset = 0; offset < values.length; offset += BINARY_STRIDE) {
    const segmentStart = values[offset];
    const segmentEnd = values[offset + 1];
    if (!Number.isFinite(segmentStart) || !Number.isFinite(segmentEnd) || segmentEnd <= segmentStart) continue;
    const firstSecond = Math.max(start, Math.ceil(segmentStart));
    const lastExclusive = Math.min(start + chunkSeconds, Math.ceil(segmentEnd));
    for (let second = firstSecond; second < lastExclusive; second++) {
      const index = second - start;
      if (index >= 0 && index < buckets.length) {
        buckets[index].push(offset);
      }
    }
  }
  return { start, buckets };
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
  if (bucketIndex < 0 || bucketIndex >= index.buckets.length) {
    return [];
  }
  return index.buckets[bucketIndex];
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
    const speed = (Math.sqrt(dx * dx + dy * dy) / duration) * 3.6;
    const webMercator = [originX + x, originY + y];
    const vehicleIndex = Math.round(Number(values[offset + 7]) || 0);

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

function frameTransfer(frame) {
  return [
    frame.xs.buffer,
    frame.ys.buffer,
    frame.angles.buffer,
    frame.speeds.buffer,
    frame.modes.buffer,
    frame.ids.buffer,
  ];
}

function setData(data) {
  dataVersion += 1;
  if (data?.binary) {
    const segments = data.segments instanceof Float32Array
      ? data.segments
      : new Float32Array(data.segments || []);
    currentData = {
      kind: "binary",
      origin: data.origin || [0, 0],
      chunk: data.chunk || {},
      chunkSeconds: data.chunkSeconds,
      segments,
    };
    currentData.index = buildBinarySecondIndex(currentData);
  } else {
    const vehicles = normalizeVehicles(data?.vehicles || []);
    currentData = {
      kind: "json",
      chunk: data?.chunk || {},
      vehicles,
    };
    currentData.index = buildJsonSecondIndex(vehicles, data || {});
  }
  return dataVersion;
}

function activeAt(seconds, visibilityMode = currentVisibilityMode) {
  currentVisibilityMode = normalizeVisibilityMode(visibilityMode);
  if (!currentData) {
    return { active: [], stats: emptyStats() };
  }
  return currentData.kind === "binary"
    ? activeFromBinary(seconds, currentData)
    : activeFromJson(seconds, currentData);
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

self.onmessage = (event) => {
  const message = event.data || {};
  const { id, type } = message;
  try {
    if (type === "releaseFrame") {
      releaseBuffers(message.buffers || []);
      return;
    }

    if (type === "setData") {
      const version = setData(message.data || null);
      const seconds = Number(message.seconds);
      if (Number.isFinite(seconds)) {
        const result = activeAt(Math.max(0, seconds), message.visibilityMode);
        const transfer = result.transfer || [];
        delete result.transfer;
        respond(id, {
          version,
          seconds: Math.max(0, seconds),
          ...result,
        }, transfer);
      } else {
        respond(id, { version });
      }
      return;
    }

    if (type === "clear") {
      dataVersion += 1;
      currentData = null;
      clearBufferPool();
      respond(id, { version: dataVersion });
      return;
    }

    if (type === "setTime") {
      const seconds = Math.max(0, Number(message.seconds) || 0);
      const result = activeAt(seconds, message.visibilityMode);
      const transfer = result.transfer || [];
      delete result.transfer;
      respond(id, {
        version: dataVersion,
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
