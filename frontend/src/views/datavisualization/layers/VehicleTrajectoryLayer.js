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
    color: "#dc4c5d",
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
    this.activeSegmentFrame = null;
    this.segmentFrameCache = new Map();
    this.segmentFrameRequests = new Set();
    this.frameBuffers = null;
    this.reusableFrame = null;
    // 复用帧的内容版本号：每次原地重写 frameBuffers 内容时递增并盖到 __contentRev，
    // 供 VehicleModelLayer.setVehicles 区分「播放推进（内容变了）」与「相机移动重复下发（内容没变）」。
    this.frameContentRev = 0;
    // 相机事件 rAF 合帧句柄：同一动画帧内多个 center/zoom/rotate/resize 只触发一次 renderVehicleLayer。
    this.cameraRenderRafId = null;
    this.releaseWorkerFrameQueue = [];
    this.releaseWorkerFrameScheduled = false;
    this.worker = null;
    this.workerRequestId = 0;
    this.workerCallbacks = new Map();
    this.workerDataVersion = 0;
    this.workerActiveSeq = 0;
    // Worker 已常驻（建好每秒索引）的分块键集合：用于切块时走 activateChunk 秒切而非重建索引。
    this.workerChunkKeys = new Set();
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
    this.segmentBucketSeconds = 1;
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

  setSegmentBucketSeconds(seconds) {
    const next = Math.max(1, Math.min(8, Math.floor(Number(seconds) || 1)));
    if (next === this.segmentBucketSeconds) return;
    this.segmentBucketSeconds = next;
    this.segmentFrameCache.clear();
    this.segmentFrameRequests.clear();
    this.workerTimeSeq++;
    if (this.activeSegmentFrame || this.activeFrame || this.binaryChunk || this.vehicles.length) {
      this.setTime(this.currentTime);
    } else if (this.ensureWorker()) {
      this.requestWorkerTime(this.currentTime);
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

  releaseWorkerFrame(frame, force = false) {
    if (!this.worker || this.worker === false || frame?.kind !== "vehicle-frame") return;
    if (!force && frame.__fromWorker !== true) return;
    const buffers = [
      frame.xs?.buffer,
      frame.ys?.buffer,
      frame.angles?.buffer,
      frame.speeds?.buffer,
      frame.modes?.buffer,
      frame.ids?.buffer,
    ].filter((buffer) => buffer instanceof ArrayBuffer && buffer.byteLength > 0);
    if (!buffers.length) return;
    this.releaseWorkerFrameQueue.push(...buffers);
    if (this.releaseWorkerFrameScheduled) return;
    this.releaseWorkerFrameScheduled = true;
    const schedule = typeof queueMicrotask === "function"
      ? queueMicrotask
      : (callback) => setTimeout(callback, 0);
    schedule(() => {
      this.releaseWorkerFrameScheduled = false;
      if (!this.worker || this.worker === false || !this.releaseWorkerFrameQueue.length) {
        this.releaseWorkerFrameQueue = [];
        return;
      }
      const queued = this.releaseWorkerFrameQueue;
      this.releaseWorkerFrameQueue = [];
      try {
        this.worker.postMessage({ type: "releaseFrame", buffers: queued }, queued);
      } catch {
        // Returning buffers is an optimization; dropping them keeps playback correct.
      }
    });
  }

  replaceActiveFrame(frame, fromWorker = false) {
    const previous = this.activeFrame;
    if (frame && fromWorker) {
      frame.__fromWorker = true;
    }
    this.activeFrame = frame || null;
    if (previous && previous !== this.activeFrame) {
      this.releaseWorkerFrame(previous);
    }
  }

  applyWorkerFrame(frame) {
    if (frame?.kind !== "vehicle-frame") {
      this.replaceActiveFrame(null);
      this.activeVehicles = [];
      return;
    }
    const count = Math.max(0, Number(frame.count) || 0);
    this.activeVehicles = [];
    frame.count = count;
    this.replaceActiveFrame(frame, true);
    this.activeSegmentFrame = null;
  }

  // key 掺入 workerActiveSeq（块激活序号）：不同分块下同一秒的帧不可互换。
  // 否则块边界预取的旧块帧会在新块激活后被命中，整秒显示旧块的部分车辆（闪烁/瞬时减员）。
  segmentBucketStart(seconds) {
    const bucketSeconds = Math.max(1, Number(this.segmentBucketSeconds) || 1);
    return Math.floor(Math.max(0, Number(seconds) || 0) / bucketSeconds) * bucketSeconds;
  }

  segmentFrameKey(seconds, visibilityMode = this.vehicleVisibilityMode, activeSeq = this.workerActiveSeq) {
    return `${activeSeq}:${normalizeVisibilityMode(visibilityMode)}:${this.segmentBucketSeconds}:${this.segmentBucketStart(seconds)}`;
  }

  cacheSegmentFrame(frame, visibilityMode = this.vehicleVisibilityMode) {
    if (frame?.kind !== "vehicle-segment-frame") return null;
    const key = this.segmentFrameKey(frame.bucketSecond, visibilityMode);
    frame.__visibilityMode = normalizeVisibilityMode(visibilityMode);
    frame.__activeSeq = this.workerActiveSeq;
    this.segmentFrameCache.set(key, frame);
    while (this.segmentFrameCache.size > 4) {
      const firstKey = this.segmentFrameCache.keys().next().value;
      if (firstKey == null) break;
      if (firstKey === key && this.segmentFrameCache.size === 1) break;
      this.segmentFrameCache.delete(firstKey);
    }
    return key;
  }

  activateSegmentFrame(frame) {
    if (frame?.kind !== "vehicle-segment-frame") return false;
    this.releaseWorkerFrame(this.activeFrame);
    this.activeFrame = null;
    this.activeVehicles = [];
    this.activeSegmentFrame = frame;
    this.modelLayer?.setTrajectoryTime(this.currentTime);
    this.renderVehicleLayer();
    return true;
  }

  applyWorkerSegmentFrame(frame, visibilityMode = this.vehicleVisibilityMode) {
    if (frame?.kind !== "vehicle-segment-frame") {
      this.activeSegmentFrame = null;
      return false;
    }
    this.cacheSegmentFrame(frame, visibilityMode);
    return this.activateSegmentFrame(frame);
  }

  applyWorkerResult(result) {
    if (result?.segmentFrame?.kind === "vehicle-segment-frame") {
      this.applyWorkerSegmentFrame(result.segmentFrame);
    } else {
      this.activeSegmentFrame = null;
      this.applyWorkerFrame(result?.frame || null);
      this.renderVehicleLayer();
    }
    this.stats = result?.stats || this.emptyStats();
    this.statsCallback?.(this.getCurrentStats());
    this.applyFollowCamera();
  }

  setData(data) {
    if (data?.meta) {
      this.setVehicleMeta(data.meta);
    }
    this.binaryChunk = null;
    this.binarySecondIndex = null;
    this.jsonSecondIndex = null;
    this.vehicles = [];
    this.followedVehicleIndex = null;
    this.deckLayerCleared = false;

    if (!data) {
      // 数据集清空（切换模型/卸载）：递增版本并清空 Worker 多块缓存与已知键集合。
      this.workerDataVersion++;
      this.workerActiveSeq++;
      this.workerTimeSeq++;
      this.workerChunkKeys.clear();
      this.segmentFrameCache.clear();
      this.segmentFrameRequests.clear();
      this.activeVehicles = [];
      this.activeSegmentFrame = null;
      this.replaceActiveFrame(null);
      this.stats = {
        activeTotal: 0,
        activeByMode: emptyModeCount(),
        avgSpeed: 0,
        routeActive: {},
      };
      this.followedVehicleKey = "";
      this.followCallback?.(null);
      if (this.worker && this.worker !== false) {
        this.postWorker("clear").catch(() => {});
      }
      this.renderVehicleLayer();
      this.statsCallback?.(this.getCurrentStats());
      return;
    }

    // 分块走 Worker：分段索引构建与逐帧采样在 Worker 中完成。切到已常驻分块仅 activateChunk
    // （零重建，根治分块边界周期性卡顿）；否则下发整块建一次索引并常驻。Worker 不可用时回退主线程。
    if (this.ensureWorker()) {
      this.activateOrAddChunkInWorker(data, true);
      return;
    }

    this.setDataOnMainThread(data);
    this.setTime(this.currentTime);
  }

  chunkKeyOf(data) {
    const start = Number(data?.chunk?.start);
    if (!Number.isFinite(start)) return null;
    return data?.binary ? `bin:${start}` : `json:${start}`;
  }

  // 供预取调用：把相邻分块提前推给 Worker 建好索引并常驻，切块时即可秒切（双缓冲）。
  preindexChunk(data) {
    if (!data || !this.ensureWorker()) return;
    this.activateOrAddChunkInWorker(data, false);
  }

  activateOrAddChunkInWorker(data, activate) {
    const key = this.chunkKeyOf(data);
    const version = this.workerDataVersion;
    const activeSeq = activate ? ++this.workerActiveSeq : this.workerActiveSeq;
    if (activate) {
      this.workerTimeSeq++;
    }
    if (key != null && this.workerChunkKeys.has(key)) {
      if (!activate) return; // 已常驻，无需重复预建
      this.postWorker("activateChunk", {
        key,
        seconds: this.currentTime,
        visibilityMode: this.vehicleVisibilityMode,
        gpuSegments: true,
        bucketSeconds: this.segmentBucketSeconds,
      })
        .then((result) => {
          if (this.isDisposed || version !== this.workerDataVersion || activeSeq !== this.workerActiveSeq) {
            this.releaseWorkerFrame(result?.frame, true);
            return;
          }
          if (result?.miss) {
            // Worker 端已被 LRU 淘汰：重新下发整块。
            this.workerChunkKeys.delete(key);
            this.sendChunkToWorker(data, true, version, activeSeq);
            return;
          }
          if (result?.frame || result?.segmentFrame) {
            this.applyWorkerResult(result);
          } else {
            this.requestWorkerTime(this.currentTime);
          }
        })
        .catch(() => {
          if (this.isDisposed || version !== this.workerDataVersion || activeSeq !== this.workerActiveSeq) return;
          this.workerChunkKeys.delete(key);
          this.sendChunkToWorker(data, true, version, activeSeq);
        });
      return;
    }
    this.sendChunkToWorker(data, activate, version, activeSeq);
  }

  sendChunkToWorker(data, activate, version, activeSeq = this.workerActiveSeq) {
    const key = this.chunkKeyOf(data);
    // 乐观登记，避免并发预取重复下发；失败时回滚。
    if (key != null) this.workerChunkKeys.add(key);
    const { payload, transfer } = trajectoryWorkerPayload(data);
    const message = {
      data: payload,
      key,
      visibilityMode: this.vehicleVisibilityMode,
      gpuSegments: true,
      bucketSeconds: this.segmentBucketSeconds,
    };
    if (activate) message.seconds = this.currentTime;
    this.postWorker(activate ? "setData" : "addChunk", message, transfer)
      .then((result) => {
        if (this.isDisposed || version !== this.workerDataVersion || (activate && activeSeq !== this.workerActiveSeq)) {
          this.releaseWorkerFrame(result?.frame, true);
          return;
        }
        if (!activate) return;
        if (result?.frame || result?.segmentFrame) {
          this.applyWorkerResult(result);
        } else {
          this.requestWorkerTime(this.currentTime);
        }
      })
      .catch((error) => {
        if (key != null) this.workerChunkKeys.delete(key);
        if (this.isDisposed || version !== this.workerDataVersion || (activate && activeSeq !== this.workerActiveSeq)) return;
        if (activate) {
          console.warn("[VehicleTrajectoryLayer] worker data setup failed", error);
          this.setDataOnMainThread(data);
          this.setTime(this.currentTime);
        }
      });
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
    // 复用帧对象不变但内容已被重写：递增版本号，确保模型层不会误走快速路径。
    this.frameContentRev += 1;
    this.reusableFrame.__contentRev = this.frameContentRev;
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
      this.modelLayer?.setTrajectoryTime(this.currentTime);
      const key = this.segmentFrameKey(this.currentTime);
      // activeKey 用帧自身记录的块序号：换块后旧帧的 key 必然不等于新 key，自动走重新采样
      const activeKey = this.activeSegmentFrame?.kind === "vehicle-segment-frame"
        ? this.segmentFrameKey(
            this.activeSegmentFrame.bucketSecond,
            this.activeSegmentFrame.__visibilityMode,
            this.activeSegmentFrame.__activeSeq ?? this.workerActiveSeq,
          )
        : "";
      if (activeKey === key) {
        this.prefetchWorkerSegmentFrame(this.segmentBucketStart(this.currentTime) + this.segmentBucketSeconds);
        this.modelLayer?.setTrajectoryTime(this.currentTime);
        this.applyFollowCamera();
        return this.getCurrentStats();
      }
      const cached = this.segmentFrameCache.get(key);
      if (cached) {
        this.activateSegmentFrame(cached);
        this.prefetchWorkerSegmentFrame(this.segmentBucketStart(this.currentTime) + this.segmentBucketSeconds);
      } else {
        this.requestWorkerTime(this.currentTime);
      }
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

  prefetchWorkerSegmentFrame(seconds) {
    if (!this.ensureWorker()) return;
    const key = this.segmentFrameKey(seconds);
    if (this.segmentFrameCache.has(key) || this.segmentFrameRequests.has(key)) return;
    this.segmentFrameRequests.add(key);
    const version = this.workerDataVersion;
    const activeSeq = this.workerActiveSeq;
    this.postWorker("setSegmentTime", {
      seconds: Math.max(0, Number(seconds) || 0),
      visibilityMode: this.vehicleVisibilityMode,
      bucketSeconds: this.segmentBucketSeconds,
    })
      .then((result) => {
        // activeSeq 变化说明响应期间发生了换块，worker 采样的是不确定的块状态，丢弃不落缓存
        if (this.isDisposed || version !== this.workerDataVersion || activeSeq !== this.workerActiveSeq) return;
        if (result?.segmentFrame?.kind === "vehicle-segment-frame") {
          this.cacheSegmentFrame(result.segmentFrame);
        }
      })
      .catch(() => {})
      .finally(() => {
        this.segmentFrameRequests.delete(key);
      });
  }

  flushWorkerTime() {
    if (!this.ensureWorker() || this.pendingWorkerTime == null) return;
    const seconds = this.pendingWorkerTime;
    this.pendingWorkerTime = null;
    this.workerTimeInFlight = true;
    const seq = ++this.workerTimeSeq;
    const version = this.workerDataVersion;
    this.postWorker("setSegmentTime", {
      seconds,
      visibilityMode: this.vehicleVisibilityMode,
      bucketSeconds: this.segmentBucketSeconds,
    })
      .then((result) => {
        if (this.isDisposed || seq !== this.workerTimeSeq || version !== this.workerDataVersion) {
          this.releaseWorkerFrame(result?.frame, true);
          return;
        }
        this.applyWorkerResult(result);
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
      this.scheduleCameraRender();
    }
    if (type === MAP_EVENT.HANDLE_CLICK_LEFT && this.activeCount() > 0) {
      this.tryFollowVehicleAtPoint(data);
    }
  }

  // 相机事件 rAF 合帧：拖拽/缩放时 MapLibre 会在同一动画帧内连发多个相机事件，
  // 合并为下一动画帧执行一次 renderVehicleLayer，避免每个事件都触发 O(N) 车辆下发。
  scheduleCameraRender() {
    if (this.cameraRenderRafId != null) return;
    if (typeof requestAnimationFrame !== "function") {
      this.renderVehicleLayer();
      return;
    }
    this.cameraRenderRafId = requestAnimationFrame(() => {
      this.cameraRenderRafId = null;
      if (this.isDisposed) return;
      this.renderVehicleLayer();
    });
  }

  cancelCameraRender() {
    if (this.cameraRenderRafId == null) return;
    if (typeof cancelAnimationFrame === "function") {
      cancelAnimationFrame(this.cameraRenderRafId);
    }
    this.cameraRenderRafId = null;
  }

  updatePaint() {
    this.renderVehicleLayer();
  }

  updateBinaryActiveVehicles() {
    const values = this.binaryChunk?.segments;
    if (!values?.length) {
      this.activeVehicles = [];
      this.replaceActiveFrame(null);
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
    this.replaceActiveFrame(this.setReusableFrameCount(count));
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
    this.replaceActiveFrame(this.setReusableFrameCount(count));
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
      const firstSecond = Math.max(start, Math.floor(segmentStart));
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
    // 直接渲染（播放推进/数据更新）已覆盖相机合帧的待办渲染，取消挂起的 rAF 避免重复执行。
    this.cancelCameraRender();
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
    if (this.activeSegmentFrame?.kind === "vehicle-segment-frame") {
      this.modelLayer?.setTrajectoryTime(this.currentTime);
      this.modelLayer?.setVehicles(this.activeSegmentFrame);
    } else {
      this.modelLayer?.setVehicles(this.activeFrame || this.activeVehicles);
    }
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
    // 选中瞬间立即通知一次（面板即时出现），后续帧内回调由 applyFollowCamera 节流
    this.lastFollowCallbackAt = typeof performance !== "undefined" ? performance.now() : Date.now();
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
    // 信息面板回调节流：相机必须每帧锁定（见下方注释），但面板是 Vue 响应式状态，
    // 每帧 enrichVehicle（对象展开+meta 查表）+ 触发面板重渲染是纯浪费，~6Hz 足够肉眼平滑。
    const now = typeof performance !== "undefined" ? performance.now() : Date.now();
    if (this.followCallback && now - (this.lastFollowCallbackAt || 0) >= 150) {
      this.lastFollowCallbackAt = now;
      this.followCallback(this.enrichVehicle(vehicle));
    }
    if (this.is3DView()) {
      this.applyFollowCamera3D(vehicle);
      return;
    }
    // 顶视跟随：每帧都把地图中心精确锁定到车辆当前插值位置。
    // 不做时间节流/像素死区——相机一旦滞后于「每帧驱动」的模型渲染，车辆就会相对屏幕中心前后抖动。
    // 仅当车辆静止（中心已对齐）时跳过，避免无意义的重复 jumpTo。
    const center = this.map.map.getCenter();
    if (
      Math.abs(center.lng - vehicle.position[0]) < 1e-9 &&
      Math.abs(center.lat - vehicle.position[1]) < 1e-9
    ) {
      return;
    }
    this.map.map.jumpTo({ center: vehicle.position, duration: 0 });
  }

  applyFollowCamera3D(vehicle) {
    const vehicleBearing = vehicleAngleToBearing(vehicle.angle);
    const bearing = normalizeBearing(vehicleBearing + this.follow3DOrbitYaw);
    const pitch = Math.max(5, Math.min(85, Number(this.map.pitch) || 45));
    const currentCenter = this.map.map.getCenter();
    // 与 2D 一致：位置每帧精确跟随，避免死区导致的前后抖动；仅相机角度保留小阈值，防止无谓重算。
    const needsMove =
      Math.abs(currentCenter.lng - vehicle.position[0]) >= 1e-9 ||
      Math.abs(currentCenter.lat - vehicle.position[1]) >= 1e-9;
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
    if (this.activeSegmentFrame?.kind === "vehicle-segment-frame") {
      return Math.max(0, Number(this.activeSegmentFrame.count) || 0);
    }
    if (this.activeFrame?.kind === "vehicle-frame") {
      return Math.max(0, Number(this.activeFrame.count) || 0);
    }
    return this.activeVehicles.length;
  }

  vehicleAt(index) {
    const segmentFrame = this.activeSegmentFrame;
    if (segmentFrame?.kind === "vehicle-segment-frame") {
      if (index < 0 || index >= this.activeCount()) return null;
      const startTime = Number(segmentFrame.startTimes?.[index]);
      const endTime = Number(segmentFrame.endTimes?.[index]);
      if (!Number.isFinite(startTime) || !Number.isFinite(endTime) || this.currentTime < startTime || this.currentTime >= endTime) {
        return null;
      }
      const duration = Math.max(endTime - startTime, 0.001);
      const ratio = Math.min(1, Math.max(0, (this.currentTime - startTime) / duration));
      const [originX = 0, originY = 0] = segmentFrame.origin || [];
      const sx = Number(segmentFrame.startXs?.[index]);
      const sy = Number(segmentFrame.startYs?.[index]);
      const ex = Number(segmentFrame.endXs?.[index]);
      const ey = Number(segmentFrame.endYs?.[index]);
      if (![sx, sy, ex, ey].every(Number.isFinite)) return null;
      const x = Number(originX) + sx + (ex - sx) * ratio;
      const y = Number(originY) + sy + (ey - sy) * ratio;
      const mode = modeKeyFromCode(segmentFrame.modes?.[index]);
      const vehicleIndex = Math.round(Number(segmentFrame.ids?.[index]) || index);
      const angle = Math.atan2(ey - sy, ex - sx) * 180 / Math.PI;
      const speed = (Math.hypot(ex - sx, ey - sy) / duration) * 3.6;
      return {
        key: `binary:${vehicleIndex}`,
        vehicleIndex,
        mode,
        position: webMercatorToLngLat(x, y),
        webMercator: [x, y],
        angle,
        speed,
      };
    }
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

  // 跟随查找每帧执行：先扫数值 ids 定位、命中才构造车辆对象。
  // vehicleAt 每次调用都会分配对象+坐标数组+key 字符串，逐个候选调用会造成每帧 O(N) 分配与 GC 抖动。
  // 同一 id 在一帧内可能有多段（段切换跨秒），命中 id 后仍需 vehicleAt 校验时间窗，取非空的那段。
  findFollowedVehicleInFrame(ids, count) {
    // 换块时 setData 会把 followedVehicleIndex 置空、只留 key；
    // 二进制帧 key 恒为 binary:${id}，可直接解析回数值索引，避免退回逐候选构造对象的慢路径。
    if (this.followedVehicleIndex == null && typeof this.followedVehicleKey === "string" && this.followedVehicleKey.startsWith("binary:")) {
      const parsed = Number(this.followedVehicleKey.slice(7));
      if (Number.isFinite(parsed)) this.followedVehicleIndex = parsed;
    }
    if (this.followedVehicleIndex != null) {
      for (let i = 0; i < count; i++) {
        if (Number(ids[i]) === this.followedVehicleIndex) {
          const vehicle = this.vehicleAt(i);
          if (vehicle) return vehicle;
        }
      }
      return null;
    }
    // 非 binary key（旧 JSON 数据路径）兜底：命中后缓存数值索引，下一帧走快路径
    for (let i = 0; i < count; i++) {
      const vehicle = this.vehicleAt(i);
      if (vehicle?.key === this.followedVehicleKey) {
        if (Number.isFinite(Number(vehicle.vehicleIndex))) {
          this.followedVehicleIndex = Number(vehicle.vehicleIndex);
        }
        return vehicle;
      }
    }
    return null;
  }

  findFollowedVehicle() {
    if (this.activeSegmentFrame?.kind === "vehicle-segment-frame") {
      return this.findFollowedVehicleInFrame(this.activeSegmentFrame.ids || [], this.activeCount());
    }
    if (this.activeFrame?.kind === "vehicle-frame") {
      return this.findFollowedVehicleInFrame(this.activeFrame.ids || [], this.activeCount());
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
    this.cancelCameraRender();
    removeSharedDeckLayer(this.map, this.layerId);
    this.replaceActiveFrame(null);
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
