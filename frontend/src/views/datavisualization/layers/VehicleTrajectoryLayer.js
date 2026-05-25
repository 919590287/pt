import { Layer, MAP_EVENT, webMercatorToLngLat } from "@/mymap/index.js";
import { removeSharedDeckLayer } from "./deckOverlayRegistry.js";
import { VehicleModelLayer } from "./VehicleModelLayer.js";

export const VEHICLE_MODE_CONFIG = {
  bus: {
    label: "公交车",
    color: "#16a34a",
    rgba: [22, 163, 74, 235],
    size: 44,
  },
  subway: {
    label: "地铁",
    color: "#dc2626",
    rgba: [220, 38, 38, 235],
    size: 52,
  },
  car: {
    label: "私家车",
    color: "#2563eb",
    rgba: [37, 99, 235, 235],
    size: 30,
  },
};

const MODE_KEYS = Object.keys(VEHICLE_MODE_CONFIG);
const BINARY_MAGIC = "GJTB";
const BINARY_VERSION = 1;
const BINARY_HEADER_BYTES = 64;
const BINARY_STRIDE = 8;
const MODE_CODE_TO_KEY = ["bus", "subway", "car"];
const MODE_KEY_TO_CODE = MODE_KEYS.reduce((map, mode, index) => {
  map[mode] = index;
  return map;
}, {});
const DEFAULT_VEHICLE_MODEL_SIZE = 36;
const MIN_FRAME_CAPACITY = 1024;
const VEHICLE_VISIBILITY_MODES = ["all", "public", "private"];

function nextFrameCapacity(count) {
  let capacity = MIN_FRAME_CAPACITY;
  while (capacity < count) {
    capacity *= 2;
  }
  return capacity;
}

function createVehicleTrajectoryWorker() {
  if (typeof Worker === "undefined") return null;
  return new Worker(new URL("./vehicleTrajectory.worker.js", import.meta.url), { type: "module" });
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

function isModeVisible(mode, visibilityMode = "all") {
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

function normalizeBearing(value) {
  let next = Number(value) || 0;
  while (next <= -180) next += 360;
  while (next > 180) next -= 360;
  return next;
}

function vehicleAngleToBearing(angle) {
  return normalizeBearing(90 - (Number(angle) || 0));
}

function mapPitchFromLegacy(pitch) {
  return Math.max(0, Math.min(85, 90 - Number(pitch ?? 90)));
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

function plainChunk(chunk = {}) {
  return {
    start: Number(chunk.start) || 0,
    end: Number(chunk.end) || 0,
    vehicleCount: Number(chunk.vehicleCount) || 0,
    pointCount: Number(chunk.pointCount) || 0,
    segmentCount: Number(chunk.segmentCount) || 0,
  };
}

function cloneFloat32(values) {
  if (values instanceof Float32Array) {
    return new Float32Array(values);
  }
  if (ArrayBuffer.isView(values)) {
    return new Float32Array(values.buffer.slice(values.byteOffset, values.byteOffset + values.byteLength));
  }
  return new Float32Array(values || []);
}

function trajectoryWorkerPayload(data) {
  if (!data) return null;
  if (data.binary) {
    const segments = cloneFloat32(data.segments);
    return {
      payload: {
        binary: true,
        origin: Array.from(data.origin || [0, 0]).map((value) => Number(value) || 0),
        chunk: plainChunk(data.chunk),
        chunkSeconds: Number(data.chunkSeconds) || undefined,
        segments,
      },
      transfer: segments.buffer ? [segments.buffer] : [],
    };
  }

  return {
    payload: {
      vehicles: normalizeVehicles(data.vehicles || []),
      chunk: plainChunk(data.chunk),
    },
    transfer: [],
  };
}

export function parseVehicleTrajectoryBinaryChunk(buffer, meta = {}) {
  const arrayBuffer = buffer instanceof ArrayBuffer ? buffer : buffer?.buffer;
  const byteOffset = buffer instanceof ArrayBuffer ? 0 : buffer?.byteOffset || 0;
  const byteLength = buffer instanceof ArrayBuffer ? buffer.byteLength : buffer?.byteLength || 0;
  if (!arrayBuffer || byteLength < BINARY_HEADER_BYTES) {
    throw new Error("轨迹二进制分块为空");
  }

  const view = new DataView(arrayBuffer, byteOffset, byteLength);
  const magic = String.fromCharCode(view.getUint8(0), view.getUint8(1), view.getUint8(2), view.getUint8(3));
  const version = view.getUint16(4, true);
  const headerBytes = view.getUint16(6, true);
  const chunkStart = view.getInt32(8, true);
  const chunkEnd = view.getInt32(12, true);
  const segmentCount = view.getInt32(16, true);
  const vehicleCount = view.getInt32(20, true);
  const pointCount = view.getInt32(24, true);
  const stride = view.getInt32(28, true);
  const originX = view.getFloat64(32, true);
  const originY = view.getFloat64(40, true);
  const chunkSeconds = view.getInt32(48, true);

  if (magic !== BINARY_MAGIC || version !== BINARY_VERSION || headerBytes < BINARY_HEADER_BYTES || stride !== BINARY_STRIDE) {
    throw new Error("轨迹二进制分块格式不兼容");
  }

  const expectedBytes = headerBytes + segmentCount * stride * Float32Array.BYTES_PER_ELEMENT;
  if (segmentCount < 0 || byteLength < expectedBytes) {
    throw new Error("轨迹二进制分块长度不完整");
  }

  return {
    binary: true,
    status: "ready",
    cacheVersion: meta.cacheVersion,
    chunkSeconds: chunkSeconds || meta.chunkSeconds,
    timeRange: meta.timeRange,
    summary: meta.summary,
    meta: meta.meta,
    chunk: {
      start: chunkStart,
      end: chunkEnd,
      vehicleCount,
      pointCount,
      segmentCount,
    },
    origin: [originX, originY],
    stride,
    segmentCount,
    segments: new Float32Array(arrayBuffer, byteOffset + headerBytes, segmentCount * stride),
  };
}

export class VehicleTrajectoryLayer extends Layer {
  name = "VehicleTrajectoryLayer";

  constructor(opt = {}) {
    super({ ...opt, zIndex: opt.zIndex ?? 1200 });
    this.layerId = `vehicle-trajectory-${this.id}`;
    this.currentTime = 0;
    this.vehicleSize = Number(opt.vehicleSize) || DEFAULT_VEHICLE_MODEL_SIZE;
    this.vehicles = [];
    this.binaryChunk = null;
    this.binarySecondIndex = null;
    this.jsonSecondIndex = null;
    this.activeVehicles = [];
    this.activeFrame = null;
    this.frameBuffers = null;
    this.reusableFrame = null;
    this.worker = null;
    this.workerRequestId = 0;
    this.workerCallbacks = new Map();
    this.workerDataVersion = 0;
    this.workerTimeSeq = 0;
    this.workerTimeInFlight = false;
    this.pendingWorkerTime = null;
    this.modelLayer = null;
    this.statsCallback = null;
    this.followCallback = null;
    this.followedVehicleKey = "";
    this.followedVehicleIndex = null;
    this.vehicleMeta = {};
    this.vehicleMetaByIndex = new Map();
    this.vehicleMetaById = new Map();
    this.routeMetaById = new Map();
    this.vehicleVisibilityMode = "all";
    this.follow3DOrbitYaw = 0;
    this.isApplyingFollowCamera = false;
    this.lastFollowCameraAt = 0;
    this.deckLayerCleared = false;
    this.stats = {
      activeTotal: 0,
      activeByMode: emptyModeCount(),
      avgSpeed: 0,
      routeActive: {},
    };
  }

  onAdd(map) {
    super.onAdd(map);
    map.whenReady(() => {
      this.ensureModelLayer();
      this.renderVehicleLayer();
    });
  }

  ensureModelLayer() {
    if (!this.map?.map || this.modelLayer) return;
    this.modelLayer = new VehicleModelLayer({
      id: `${this.layerId}-models`,
      modes: MODE_KEYS,
      onReady: () => this.renderVehicleLayer(),
    });
    this.modelLayer.setMapWrapper(this.map);
    if (!this.map.map.getLayer(this.modelLayer.id)) {
      this.map.map.addLayer(this.modelLayer);
    }
    this.modelLayer.setVehicleScale(this.vehicleSize / DEFAULT_VEHICLE_MODEL_SIZE);
  }

  setStatsCallback(callback) {
    this.statsCallback = typeof callback === "function" ? callback : null;
  }

  setFollowCallback(callback) {
    this.followCallback = typeof callback === "function" ? callback : null;
  }

  setVehicleMeta(meta = {}) {
    this.vehicleMeta = meta || {};
    this.vehicleMetaByIndex = new Map();
    this.vehicleMetaById = new Map();
    this.routeMetaById = new Map(Object.entries(this.vehicleMeta.routes || {}));
    for (const vehicle of this.vehicleMeta.vehicles || []) {
      const index = Number(vehicle.index);
      if (Number.isFinite(index)) {
        this.vehicleMetaByIndex.set(index, vehicle);
      }
      if (vehicle.id) {
        this.vehicleMetaById.set(String(vehicle.id), vehicle);
      }
    }
  }

  setVehicleVisibilityMode(mode) {
    const nextMode = normalizeVisibilityMode(mode);
    if (nextMode === this.vehicleVisibilityMode) return;
    this.vehicleVisibilityMode = nextMode;
    if (this.binaryChunk || this.vehicles.length) {
      this.setTime(this.currentTime);
    } else if (this.ensureWorker()) {
      this.requestWorkerTime(this.currentTime);
    } else {
      this.renderVehicleLayer();
    }
  }

  setVehicleSize(size) {
    const nextSize = Number(size);
    if (!Number.isFinite(nextSize)) return;
    const clamped = Math.max(18, Math.min(80, nextSize));
    if (Math.abs(clamped - this.vehicleSize) < 0.01) return;
    this.vehicleSize = clamped;
    this.renderVehicleLayer();
  }

  ensureWorker() {
    if (this.worker || this.worker === false) return this.worker || null;
    try {
      this.worker = createVehicleTrajectoryWorker();
      if (!this.worker) {
        this.worker = false;
        return null;
      }
      this.worker.onmessage = (event) => {
        const message = event.data || {};
        const callback = this.workerCallbacks.get(message.id);
        if (!callback) return;
        this.workerCallbacks.delete(message.id);
        if (message.ok) {
          callback.resolve(message.result);
        } else {
          callback.reject(new Error(message.error || "worker error"));
        }
      };
      this.worker.onerror = (error) => {
        for (const callback of this.workerCallbacks.values()) {
          callback.reject(error instanceof Error ? error : new Error(error?.message || "worker error"));
        }
        this.workerCallbacks.clear();
      };
      return this.worker;
    } catch (error) {
      console.warn("[VehicleTrajectoryLayer] worker init failed", error);
      this.worker = false;
      return null;
    }
  }

  postWorker(type, payload = {}, transfer = []) {
    const worker = this.ensureWorker();
    if (!worker) return Promise.reject(new Error("worker unavailable"));
    const id = ++this.workerRequestId;
    return new Promise((resolve, reject) => {
      this.workerCallbacks.set(id, { resolve, reject });
      try {
        worker.postMessage({ id, type, ...payload }, transfer);
      } catch (error) {
        this.workerCallbacks.delete(id);
        reject(error);
      }
    });
  }

  setData(data) {
    this.workerDataVersion++;
    if (data?.meta) {
      this.setVehicleMeta(data.meta);
    }
    this.binaryChunk = null;
    this.binarySecondIndex = null;
    this.jsonSecondIndex = null;
    this.vehicles = [];
    this.activeVehicles = [];
    this.activeFrame = null;
    this.followedVehicleIndex = null;
    this.deckLayerCleared = false;
    this.stats = {
      activeTotal: 0,
      activeByMode: emptyModeCount(),
      avgSpeed: 0,
      routeActive: {},
    };

    if (!data) {
      this.followedVehicleKey = "";
      this.followCallback?.(null);
      if (this.worker && this.worker !== false) {
        this.postWorker("clear").catch(() => {});
      }
      this.renderVehicleLayer();
      this.statsCallback?.(this.getCurrentStats());
      return;
    }

    if (data?.binary) {
      this.setDataOnMainThread(data);
      this.setTime(this.currentTime);
      return;
    }

    if (this.ensureWorker()) {
      const version = this.workerDataVersion;
      const { payload, transfer } = trajectoryWorkerPayload(data);
      this.postWorker("setData", { data: payload }, transfer)
        .then((result) => {
          if (this.isDisposed || version !== this.workerDataVersion) return;
          this.requestWorkerTime(this.currentTime);
        })
        .catch((error) => {
          console.warn("[VehicleTrajectoryLayer] worker data setup failed", error);
          if (this.isDisposed || version !== this.workerDataVersion) return;
          this.setDataOnMainThread(data);
          this.setTime(this.currentTime);
        });
      return;
    }

    this.setDataOnMainThread(data);
    this.setTime(this.currentTime);
  }

  setDataOnMainThread(data) {
    if (data?.binary) {
      this.binaryChunk = data;
      this.binarySecondIndex = this.buildBinarySecondIndex(data);
    } else {
      this.vehicles = normalizeVehicles(data?.vehicles);
      this.jsonSecondIndex = this.buildJsonSecondIndex(this.vehicles, data);
    }
  }

  ensureFrameBuffers(count) {
    const capacity = nextFrameCapacity(Math.max(0, Number(count) || 0));
    if (this.frameBuffers && this.frameBuffers.capacity >= capacity) {
      return this.frameBuffers;
    }
    this.frameBuffers = {
      capacity,
      xs: new Float64Array(capacity),
      ys: new Float64Array(capacity),
      angles: new Float32Array(capacity),
      speeds: new Float32Array(capacity),
      modes: new Uint8Array(capacity),
      ids: new Int32Array(capacity),
      keys: [],
    };
    this.reusableFrame = createFrame({
      count: 0,
      xs: this.frameBuffers.xs,
      ys: this.frameBuffers.ys,
      angles: this.frameBuffers.angles,
      speeds: this.frameBuffers.speeds,
      modes: this.frameBuffers.modes,
      ids: this.frameBuffers.ids,
      keys: this.frameBuffers.keys,
    });
    return this.frameBuffers;
  }

  setReusableFrameCount(count) {
    if (!this.reusableFrame || !this.frameBuffers) return null;
    this.reusableFrame.count = count;
    this.reusableFrame.xs = this.frameBuffers.xs;
    this.reusableFrame.ys = this.frameBuffers.ys;
    this.reusableFrame.angles = this.frameBuffers.angles;
    this.reusableFrame.speeds = this.frameBuffers.speeds;
    this.reusableFrame.modes = this.frameBuffers.modes;
    this.reusableFrame.ids = this.frameBuffers.ids;
    this.reusableFrame.keys = this.frameBuffers.keys;
    return this.reusableFrame;
  }

  setTime(seconds) {
    this.currentTime = Math.max(0, Number(seconds) || 0);
    if (!this.binaryChunk && !this.vehicles.length && this.ensureWorker()) {
      this.requestWorkerTime(this.currentTime);
      return this.getCurrentStats();
    }
    if (this.binaryChunk) {
      this.updateBinaryActiveVehicles();
    } else {
      this.updateJsonActiveVehicles();
    }
    this.renderVehicleLayer();
    this.statsCallback?.(this.getCurrentStats());
    this.applyFollowCamera();
    return this.getCurrentStats();
  }

  requestWorkerTime(seconds) {
    if (!this.ensureWorker()) return;
    this.pendingWorkerTime = Math.max(0, Number(seconds) || 0);
    if (this.workerTimeInFlight) return;
    this.flushWorkerTime();
  }

  flushWorkerTime() {
    if (!this.ensureWorker() || this.pendingWorkerTime == null) return;
    const seconds = this.pendingWorkerTime;
    this.pendingWorkerTime = null;
    this.workerTimeInFlight = true;
    const seq = ++this.workerTimeSeq;
    const version = this.workerDataVersion;
    this.postWorker("setTime", { seconds, visibilityMode: this.vehicleVisibilityMode })
      .then((result) => {
        if (this.isDisposed || seq !== this.workerTimeSeq || version !== this.workerDataVersion) return;
        if (this.pendingWorkerTime != null && Math.abs(Number(result.seconds) - this.pendingWorkerTime) > 0.001) return;
        this.activeFrame = result.frame || null;
        this.activeVehicles = result.active || [];
        this.stats = result.stats || this.emptyStats();
        this.renderVehicleLayer();
        this.statsCallback?.(this.getCurrentStats());
        this.applyFollowCamera();
      })
      .catch((error) => {
        if (!this.isDisposed) {
          console.warn("[VehicleTrajectoryLayer] worker time update failed", error);
        }
      })
      .finally(() => {
        this.workerTimeInFlight = false;
        if (!this.isDisposed && this.pendingWorkerTime != null) {
          this.flushWorkerTime();
        }
      });
  }

  on(type, data) {
    super.on(type, data);
    if (
      (
        type === MAP_EVENT.UPDATE_CENTER ||
        type === MAP_EVENT.UPDATE_ZOOM ||
        type === MAP_EVENT.UPDATE_RENDERER_SIZE ||
        type === MAP_EVENT.UPDATE_CAMERA_ROTATE
      ) &&
      this.activeCount() > 0
    ) {
      if (type === MAP_EVENT.UPDATE_CAMERA_ROTATE) {
        this.syncFollowOrbitFromMap();
      }
      this.renderVehicleLayer();
    }
    if (type === MAP_EVENT.HANDLE_CLICK_LEFT && this.activeCount() > 0) {
      this.tryFollowVehicleAtPoint(data);
    }
  }

  updatePaint() {
    this.renderVehicleLayer();
  }

  updateBinaryActiveVehicles() {
    const values = this.binaryChunk?.segments;
    if (!values?.length) {
      this.activeVehicles = [];
      this.activeFrame = null;
      this.stats = this.emptyStats();
      return;
    }

    const [originX = 0, originY = 0] = this.binaryChunk.origin || [];
    const time = this.currentTime;
    const offsets = this.binaryOffsetsForTime(time, values);
    const activeByMode = emptyModeCount();
    let speedTotal = 0;
    let speedCount = 0;
    const capacity = offsets.length;
    const buffers = this.ensureFrameBuffers(capacity);
    let count = 0;

    for (const offset of offsets) {
      const startTime = values[offset];
      const endTime = values[offset + 1];
      if (time < startTime || time >= endTime) continue;

      const mode = modeKeyFromCode(values[offset + 6]);
      if (!isModeVisible(mode, this.vehicleVisibilityMode)) continue;
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
      const angle = Math.atan2(dy, dx) * 180 / Math.PI;
      const speed = (Math.sqrt(dx * dx + dy * dy) / duration) * 3.6;
      const webMercator = [originX + x, originY + y];
      const vehicleIndex = Math.round(Number(values[offset + 7]) || 0);

      buffers.xs[count] = webMercator[0];
      buffers.ys[count] = webMercator[1];
      buffers.angles[count] = angle;
      buffers.speeds[count] = speed;
      buffers.modes[count] = MODE_KEY_TO_CODE[mode] ?? 2;
      buffers.ids[count] = vehicleIndex;
      count += 1;
      activeByMode[mode] += 1;
      if (Number.isFinite(speed) && speed > 0 && speed < 180) {
        speedTotal += speed;
        speedCount += 1;
      }
    }

    this.activeVehicles = [];
    this.activeFrame = this.setReusableFrameCount(count);
    this.stats = {
      activeTotal: count,
      activeByMode,
      avgSpeed: speedCount ? Math.round((speedTotal / speedCount) * 10) / 10 : 0,
      routeActive: {},
    };
  }

  updateJsonActiveVehicles() {
    const activeByMode = emptyModeCount();
    const routeActive = {};
    const candidates = this.jsonSegmentsForTime(this.currentTime);
    const capacity = candidates.length;
    const buffers = this.ensureFrameBuffers(capacity);
    buffers.keys.length = 0;
    let count = 0;
    let speedTotal = 0;
    let speedCount = 0;

    for (const item of candidates) {
      const vehicle = item.vehicle;
      const segment = item.segment;
      if (!segment) continue;
      if (this.currentTime < segment[0] || this.currentTime >= segment[1]) continue;
      if (!isModeVisible(vehicle.mode, this.vehicleVisibilityMode)) continue;
      const duration = Math.max(segment[1] - segment[0], 0.001);
      const ratio = Math.min(1, Math.max(0, (this.currentTime - segment[0]) / duration));
      const x = segment[2] + (segment[4] - segment[2]) * ratio;
      const y = segment[3] + (segment[5] - segment[3]) * ratio;
      const dx = segment[4] - segment[2];
      const dy = segment[5] - segment[3];
      const speed = (Math.sqrt(dx * dx + dy * dy) / duration) * 3.6;
      const webMercator = [x, y];
      const key = `json:${vehicle.id || `${vehicle.mode}:${segment[0]}:${segment[1]}`}`;
      buffers.xs[count] = webMercator[0];
      buffers.ys[count] = webMercator[1];
      buffers.angles[count] = Math.atan2(dy, dx) * 180 / Math.PI;
      buffers.speeds[count] = speed;
      buffers.modes[count] = MODE_KEY_TO_CODE[vehicle.mode] ?? 2;
      buffers.ids[count] = count;
      buffers.keys[count] = key;
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

    this.activeVehicles = [];
    this.activeFrame = this.setReusableFrameCount(count);
    this.stats = {
      activeTotal: count,
      activeByMode,
      avgSpeed: speedCount ? Math.round((speedTotal / speedCount) * 10) / 10 : 0,
      routeActive,
    };
  }

  buildBinarySecondIndex(data) {
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

  binaryOffsetsForTime(time, values) {
    const index = this.binarySecondIndex;
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

  buildJsonSecondIndex(vehicles, data = {}) {
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

  jsonSegmentsForTime(time) {
    const index = this.jsonSecondIndex;
    if (!index) {
      return this.vehicles.flatMap((vehicle) => vehicle.segments.map((segment) => ({ vehicle, segment })));
    }
    const bucketIndex = Math.floor(time) - index.start;
    if (bucketIndex < 0 || bucketIndex >= index.buckets.length) {
      return [];
    }
    return index.buckets[bucketIndex];
  }

  renderVehicleLayer() {
    if (!this.map?.map) return;
    this.ensureModelLayer();
    this.clearDeckLayerOnce();
    if (this.visible === false || this.activeCount() <= 0) {
      this.modelLayer?.setVehicles([]);
      this.modelLayer?.setVisible(false);
      return;
    }
    this.modelLayer?.setVisible(true);
    this.modelLayer?.setVehicleScale(this.vehicleSize / DEFAULT_VEHICLE_MODEL_SIZE);
    this.modelLayer?.setVehicles(this.activeFrame || this.activeVehicles);
  }

  clearDeckLayerOnce() {
    if (this.deckLayerCleared) return;
    removeSharedDeckLayer(this.map, this.layerId);
    this.deckLayerCleared = true;
  }

  followVehicle(vehicle) {
    if (!vehicle?.key) return;
    const enrichedVehicle = this.enrichVehicle(vehicle);
    this.followedVehicleKey = vehicle.key;
    this.followedVehicleIndex = Number.isFinite(Number(vehicle.vehicleIndex)) ? Number(vehicle.vehicleIndex) : null;
    this.syncFollowOrbitFromVehicle(vehicle, true);
    this.followCallback?.(enrichedVehicle);
    this.applyFollowCamera(vehicle);
    this.renderVehicleLayer();
  }

  tryFollowVehicleAtPoint(eventData) {
    const point = eventData?.windowXY || eventData?.point || eventData?.canvasXY;
    if (!point || !this.map) return;
    let bestVehicle = null;
    let bestDistance = Infinity;
    const hitRadius = Math.max(18, Math.min(54, this.vehicleSize * 0.75));

    for (let index = 0, count = this.activeCount(); index < count; index++) {
      const vehicle = this.vehicleAt(index);
      if (!vehicle?.position) continue;
      const projected = this.map.map?.project?.(vehicle.position);
      const x = projected?.x ?? this.map.WebMercatorToCanvasXY?.(vehicle.webMercator?.[0], vehicle.webMercator?.[1])?.[0];
      const y = projected?.y ?? this.map.WebMercatorToCanvasXY?.(vehicle.webMercator?.[0], vehicle.webMercator?.[1])?.[1];
      if (!Number.isFinite(x) || !Number.isFinite(y)) continue;
      const distance = Math.hypot(Number(point[0]) - x, Number(point[1]) - y);
      if (distance < bestDistance) {
        bestDistance = distance;
        bestVehicle = vehicle;
      }
    }

    if (bestVehicle && bestDistance <= hitRadius) {
      this.followVehicle(bestVehicle);
    }
  }

  clearFollow() {
    this.followedVehicleKey = "";
    this.followedVehicleIndex = null;
    this.followCallback?.(null);
    this.renderVehicleLayer();
  }

  applyFollowCamera(currentVehicle = null) {
    if (!this.followedVehicleKey || !this.map?.map) return;
    const vehicle = currentVehicle || this.findFollowedVehicle();
    if (!vehicle?.position) return;
    this.followCallback?.(this.enrichVehicle(vehicle));
    const now = typeof performance !== "undefined" ? performance.now() : Date.now();
    if (now - this.lastFollowCameraAt < 14) return;
    this.lastFollowCameraAt = now;
    if (this.is3DView()) {
      this.applyFollowCamera3D(vehicle);
      return;
    }
    const currentCenter = this.map.map.getCenter();
    const currentPoint = this.map.map.project([currentCenter.lng, currentCenter.lat]);
    const vehiclePoint = this.map.map.project(vehicle.position);
    if (Math.hypot(currentPoint.x - vehiclePoint.x, currentPoint.y - vehiclePoint.y) < 0.45) return;
    this.map.map.jumpTo({ center: vehicle.position, duration: 0 });
  }

  applyFollowCamera3D(vehicle) {
    const vehicleBearing = vehicleAngleToBearing(vehicle.angle);
    const bearing = normalizeBearing(vehicleBearing + this.follow3DOrbitYaw);
    const pitch = Math.max(5, Math.min(85, Number(this.map.pitch) || 45));
    const currentCenter = this.map.map.getCenter();
    const currentPoint = this.map.map.project([currentCenter.lng, currentCenter.lat]);
    const vehiclePoint = this.map.map.project(vehicle.position);
    const needsMove = Math.hypot(currentPoint.x - vehiclePoint.x, currentPoint.y - vehiclePoint.y) >= 0.35;
    const needsCamera = Math.abs(normalizeBearing(this.map.rotation - bearing)) >= 0.05
      || Math.abs((Number(this.map.pitch) || 90) - pitch) >= 0.05;
    if (!needsMove && !needsCamera) return;
    this.isApplyingFollowCamera = true;
    this.map.map.jumpTo({
      center: vehicle.position,
      bearing,
      pitch: mapPitchFromLegacy(pitch),
      duration: 0,
    });
    this.map.syncStateFromMap?.();
    requestAnimationFrame(() => {
      this.isApplyingFollowCamera = false;
    });
  }

  syncFollowOrbitFromMap() {
    if (this.isApplyingFollowCamera || !this.followedVehicleKey || !this.is3DView()) return;
    const vehicle = this.findFollowedVehicle();
    if (!vehicle) return;
    this.syncFollowOrbitFromVehicle(vehicle, false);
  }

  syncFollowOrbitFromVehicle(vehicle, reset = false) {
    if (!vehicle || !this.is3DView()) return;
    const vehicleBearing = vehicleAngleToBearing(vehicle.angle);
    if (reset) {
      this.follow3DOrbitYaw = normalizeBearing((Number(this.map?.rotation) || vehicleBearing) - vehicleBearing);
      return;
    }
    this.follow3DOrbitYaw = normalizeBearing((Number(this.map?.rotation) || 0) - vehicleBearing);
  }

  is3DView() {
    return Boolean(this.map?.enableRotate)
      || Number(this.map?.pitch) < 89.5
      || Math.abs(Number(this.map?.rotation) || 0) > 0.01;
  }

  activeCount() {
    if (this.activeFrame?.kind === "vehicle-frame") {
      return Math.max(0, Number(this.activeFrame.count) || 0);
    }
    return this.activeVehicles.length;
  }

  vehicleAt(index) {
    const frame = this.activeFrame;
    if (frame?.kind !== "vehicle-frame") {
      return this.activeVehicles[index] || null;
    }
    if (index < 0 || index >= this.activeCount()) return null;
    const x = Number(frame.xs?.[index]);
    const y = Number(frame.ys?.[index]);
    if (!Number.isFinite(x) || !Number.isFinite(y)) return null;
    const mode = modeKeyFromCode(frame.modes?.[index]);
    const vehicleIndex = Math.round(Number(frame.ids?.[index]) || index);
    const key = frame.keys?.[index] || `binary:${vehicleIndex}`;
    const angle = Number(frame.angles?.[index]) || 0;
    const speed = Number(frame.speeds?.[index]) || 0;
    return {
      key,
      vehicleIndex,
      mode,
      position: webMercatorToLngLat(x, y),
      webMercator: [x, y],
      angle,
      speed,
    };
  }

  enrichVehicle(vehicle) {
    if (!vehicle) return null;
    const meta = this.vehicleMetaByIndex.get(Number(vehicle.vehicleIndex))
      || this.vehicleMetaById.get(String(vehicle.id || ""))
      || null;
    const route = meta?.routeId ? this.routeMetaById.get(String(meta.routeId)) : null;
    return {
      ...vehicle,
      id: meta?.id || vehicle.id || vehicle.key,
      mode: meta?.mode || vehicle.mode,
      type: meta?.mode || vehicle.mode,
      meta,
      route,
    };
  }

  findFollowedVehicle() {
    if (this.activeFrame?.kind === "vehicle-frame") {
      const count = this.activeCount();
      const ids = this.activeFrame.ids || [];
      for (let i = 0; i < count; i++) {
        const vehicle = this.vehicleAt(i);
        if (vehicle?.key === this.followedVehicleKey) return vehicle;
      }
      if (this.followedVehicleIndex != null) {
        for (let i = 0; i < count; i++) {
          if (Number(ids[i]) === this.followedVehicleIndex) {
            return this.vehicleAt(i);
          }
        }
      }
      return null;
    }
    return this.activeVehicles.find((item) => item.key === this.followedVehicleKey) || null;
  }

  emptyStats() {
    return {
      activeTotal: 0,
      activeByMode: emptyModeCount(),
      avgSpeed: 0,
      routeActive: {},
    };
  }

  getCurrentStats() {
    return {
      activeTotal: this.stats.activeTotal,
      activeByMode: { ...this.stats.activeByMode },
      avgSpeed: this.stats.avgSpeed,
      routeActive: { ...this.stats.routeActive },
    };
  }

  hide() {
    super.hide();
    removeSharedDeckLayer(this.map, this.layerId);
    this.deckLayerCleared = true;
    this.modelLayer?.setVisible(false);
  }

  show() {
    super.show();
    this.renderVehicleLayer();
  }

  dispose() {
    removeSharedDeckLayer(this.map, this.layerId);
    if (this.modelLayer) {
      if (this.map?.map?.getLayer(this.modelLayer.id)) {
        this.map.map.removeLayer(this.modelLayer.id);
      } else {
        this.modelLayer.dispose();
      }
      this.modelLayer = null;
    }
    if (this.worker && this.worker !== false) {
      this.worker.terminate();
    }
    this.worker = null;
    this.workerCallbacks.clear();
    super.dispose();
  }
}
