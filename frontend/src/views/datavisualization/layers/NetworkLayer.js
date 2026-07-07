import { COORDINATE_SYSTEM } from "@deck.gl/core";
import { LineLayer, PathLayer } from "@deck.gl/layers";
import { Layer, MAP_EVENT, webMercatorToLngLat } from "@/mymap/index.js";
import { getTileNetwork, getTileNetworkBinary, getFullNetworkBinary } from "@/api/network.js";
import { colorToCss, lineWidthToPixels } from "./maplibreLayerUtils.js";
import { setSharedDeckLayer, removeSharedDeckLayer } from "./deckOverlayRegistry.js";
import { buildDistrictClipIndex, clipRenderableBinaryData } from "./districtClipIndex.js";

const TILE_ZOOM = 12;
const MIN_TILE_ZOOM = 8;
const FULL_MODE_MAX_ZOOM = 12.0;
const EARTH_RADIUS = 20037508.3427892;
const TILE_BUFFER = 2;
const MAX_VISIBLE_TILE_REQUESTS = 1600;
const MAX_STALE_TILES = 260;
const MAX_TILE_CACHE = 1200;
const TILE_LOAD_CONCURRENCY = 8;
const TILE_SCHEDULE_DELAY = {
  all: 0,
  overview: 260,
  city: 220,
  district: 180,
  corridor: 140,
  full: 120,
};
// debounce 的最大等待：连续相机运动期间也保证按此周期加载一次可见瓦片
const TILE_SCHEDULE_MAX_WAIT = 420;
// 瓦片密集到达期的合并刷新节流（带 trailing）：rAF 合帧之外再限最小间隔，
// 避免流式加载时逐帧触发全量 combine（每次都是 O(总链路) 的 worker 计算 + GPU 上传）
const TILE_REFRESH_THROTTLE_MS = 120;
const BINARY_MAGIC = "GJNB";
const BINARY_VERSION = 1;
const BINARY_HEADER_BYTES = 64;
const BINARY_LAYOUT_COLUMNAR = 1;
const EMPTY_FLOAT32 = new Float32Array(0);
const EMPTY_UINT32 = new Uint32Array(0);
const EMPTY_FLOAT64 = new Float64Array(0);
const FLOW_STYLE_STOPS = [
  { limit: 0.064, color: [44, 123, 182], widthStep: 0 },
  { limit: 0.216, color: [246, 196, 66], widthStep: 1 },
  { limit: 0.512, color: [253, 174, 97], widthStep: 2 },
  { limit: Infinity, color: [215, 25, 28], widthStep: 3 },
];
const DETAIL_ZOOM_STOPS = [
  { minZoom: 13.0, level: "full", z: TILE_ZOOM },
  { minZoom: 11.7, level: "corridor", z: TILE_ZOOM },
  { minZoom: 10.2, level: "district", z: 11 },
  { minZoom: 8.8, level: "city", z: 10 },
  { minZoom: -Infinity, level: "overview", z: MIN_TILE_ZOOM },
];
// 粗档位合并选项：Float32 坐标（这些档位 >36m/px，f32 经度精度 ≈0.85m 远小于 1 像素，
// deck 免 fp64 拆分、传输显存减半）+ 亚像素短链剔除（阈值按各档位最小 m/px 取约 0.5-0.8 像素）。
// 全市路网在 district 档合并近百万段，短链剔除与 f32 是把重建尖刺压回帧预算的关键。
const COARSE_DETAIL_COMBINE_OPTS = {
  district: { precision: "f32", cullLengthMeters: 30 },
  city: { precision: "f32", cullLengthMeters: 90 },
  overview: { precision: "f32", cullLengthMeters: 240 },
};

function runtimeNumber(name, fallback) {
  const value = Number(typeof window !== "undefined" ? window.APP_CONFIG?.[name] : undefined);
  return Number.isFinite(value) ? value : fallback;
}

function networkLineMinPixels() {
  return Math.max(0.22, runtimeNumber("networkLineMinPixels", 0.8));
}

function networkLineSoftEdgePixels() {
  return Math.max(0, runtimeNumber("networkLineSoftEdgePixels", 0));
}

function routePathJoinToleranceMeters() {
  return Math.max(0, runtimeNumber("routePathJoinToleranceMeters", 45));
}

function webMercatorToTile(x, y, z = TILE_ZOOM) {
  const scale = Math.pow(2, z);
  const col = Math.floor(((EARTH_RADIUS + Number(x)) * scale) / (EARTH_RADIUS * 2));
  const row = Math.floor(((EARTH_RADIUS - Number(y)) * scale) / (EARTH_RADIUS * 2));
  const max = scale - 1;
  return {
    x: Math.max(0, Math.min(max, col)),
    y: Math.max(0, Math.min(max, row)),
  };
}

function tileKey(tile) {
  return `${tile.z}_${tile.detail}_${tile.x}_${tile.y}`;
}

function fullTile() {
  return { z: 0, x: 0, y: 0, detail: "all", full: true };
}

function detailKey(detail) {
  return detail.full ? "0_all" : `${detail.z}_${detail.level}`;
}

function tileBufferForDetail(level) {
  if (level === "all") return 0;
  if (level === "overview" || level === "city") return 0;
  if (level === "district") return 1;
  return TILE_BUFFER;
}

function emptyBinaryData(version = 0) {
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

function hashString(value) {
  const text = String(value ?? "");
  let hash1 = 0;
  let hash2 = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    const code = text.charCodeAt(i);
    hash1 = ((hash1 << 5) - hash1 + code) | 0;
    hash2 ^= code;
    hash2 = Math.imul(hash2, 0x01000193);
  }
  return [hash1 >>> 0, hash2 >>> 0];
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

function attachStats(data, version = data.version || 0) {
  const stats = calcFlowStats(data.flow || EMPTY_FLOAT32);
  return {
    ...data,
    minFlow: stats.minFlow,
    maxFlow: stats.maxFlow,
    version,
  };
}

function parseBinaryTileResponse(response, version = 0) {
  const arrayBuffer = response instanceof ArrayBuffer ? response : response?.data;
  if (!(arrayBuffer instanceof ArrayBuffer) || arrayBuffer.byteLength < BINARY_HEADER_BYTES) return null;
  const view = new DataView(arrayBuffer);
  const magic = String.fromCharCode(view.getUint8(0), view.getUint8(1), view.getUint8(2), view.getUint8(3));
  const binaryVersion = view.getUint16(4, true);
  const headerBytes = view.getUint16(6, true);
  const count = view.getUint32(8, true);
  const layout = view.getUint32(12, true);
  if (magic !== BINARY_MAGIC || binaryVersion !== BINARY_VERSION || layout !== BINARY_LAYOUT_COLUMNAR) {
    return null;
  }
  const origin = [view.getFloat64(16, true), view.getFloat64(24, true)];
  const hashOffset = view.getUint32(32, true);
  const hash2Offset = view.getUint32(36, true);
  const sourceOffset = view.getUint32(40, true);
  const targetOffset = view.getUint32(44, true);
  const flowOffset = view.getUint32(48, true);
  const lengthOffset = view.getUint32(52, true);
  const lanesOffset = view.getUint32(56, true);
  if (headerBytes < BINARY_HEADER_BYTES) return null;
  return attachStats({
    binary: true,
    count,
    origin,
    hash: new Uint32Array(arrayBuffer, hashOffset, count),
    hash2: new Uint32Array(arrayBuffer, hash2Offset, count),
    source: new Float32Array(arrayBuffer, sourceOffset, count * 2),
    target: new Float32Array(arrayBuffer, targetOffset, count * 2),
    flow: new Float32Array(arrayBuffer, flowOffset, count),
    length: new Float32Array(arrayBuffer, lengthOffset, count),
    lanes: new Float32Array(arrayBuffer, lanesOffset, count),
  }, version);
}

function linksToBinaryData(links = [], version = 0) {
  const validLinks = [];
  for (const link of Array.isArray(links) ? links : []) {
    const fromX = Number(link?.from?.x);
    const fromY = Number(link?.from?.y);
    const toX = Number(link?.to?.x);
    const toY = Number(link?.to?.y);
    if (![fromX, fromY, toX, toY].every(Number.isFinite)) continue;
    validLinks.push({ link, fromX, fromY, toX, toY });
  }
  const count = validLinks.length;
  if (!count) return emptyBinaryData(version);

  const origin = [0, 0];
  const hash = new Uint32Array(count);
  const hash2 = new Uint32Array(count);
  const source = new Float64Array(count * 2);
  const target = new Float64Array(count * 2);
  const flow = new Float32Array(count);
  const length = new Float32Array(count);
  const lanes = new Float32Array(count);

  for (let i = 0; i < count; i++) {
    const item = validLinks[i];
    const id = item.link?.linkId || `${item.fromX},${item.fromY},${item.toX},${item.toY}`;
    const [hashA, hashB] = hashString(id);
    const sourceLngLat = webMercatorToLngLat(item.fromX, item.fromY);
    const targetLngLat = webMercatorToLngLat(item.toX, item.toY);
    hash[i] = hashA;
    hash2[i] = hashB;
    source[i * 2] = sourceLngLat[0];
    source[i * 2 + 1] = sourceLngLat[1];
    target[i * 2] = targetLngLat[0];
    target[i * 2 + 1] = targetLngLat[1];
    flow[i] = Number(item.link?.flow) || 0;
    length[i] = Number(item.link?.length) || 0;
    lanes[i] = Number(item.link?.lanes) || 1;
  }

  return attachStats({
    binary: true,
    count,
    origin,
    hash,
    hash2,
    source,
    target,
    flow,
    length,
    lanes,
  }, version);
}

function linkFlowValue(link) {
  const value = Number(
    link?.flow ??
    link?.trafficVolume ??
    link?.traffic_volume ??
    link?.simulatedTrafficVolume ??
    link?.simulated_traffic_volume ??
    0,
  );
  return Number.isFinite(value) ? value : 0;
}

function linkWebMercatorEndpoints(link) {
  const fromX = Number(link?.from?.x);
  const fromY = Number(link?.from?.y);
  const toX = Number(link?.to?.x);
  const toY = Number(link?.to?.y);
  if (![fromX, fromY, toX, toY].every(Number.isFinite)) return null;
  return {
    source: [fromX, fromY],
    target: [toX, toY],
  };
}

function distanceSq(a, b) {
  const dx = a[0] - b[0];
  const dy = a[1] - b[1];
  return dx * dx + dy * dy;
}

function appendPoint(path, point) {
  const last = path[path.length - 1];
  if (!last || distanceSq(last, point) > 0.01) {
    path.push(point);
  }
}

function parseTileResponse(response, version = 0) {
  const payload = response instanceof ArrayBuffer ? response : response?.data;
  if (payload instanceof ArrayBuffer) {
    const parsed = parseBinaryTileResponse(response, version);
    if (!parsed) {
      throw new Error("Invalid binary tile response");
    }
    return parsed;
  }
  return linksToBinaryData(Array.isArray(response) ? response : response?.data || [], version);
}

// (hash,hash2) 双 32 位键去重：嵌套 Map/Set 避免每趟合并产生 2×N 个临时字符串（与 worker 同款）
function makePairSeen() {
  const outer = new Map();
  return {
    addIfAbsent(a, b) {
      let inner = outer.get(a);
      if (!inner) {
        inner = new Set();
        outer.set(a, inner);
      }
      if (inner.has(b)) return false;
      inner.add(b);
      return true;
    },
    clear() {
      outer.clear();
    },
  };
}

function combineBinaryTiles(keys, tileCache, version = 0) {
  const tiles = keys
    .map((key) => tileCache.get(key))
    .filter((tile) => tile?.binary && tile.count > 0);
  if (!tiles.length) return emptyBinaryData(version);

  const seen = makePairSeen();
  let total = 0;
  for (const tile of tiles) {
    for (let i = 0; i < tile.count; i++) {
      if (!seen.addIfAbsent(tile.hash[i], tile.hash2[i])) continue;
      total++;
    }
  }
  if (!total) return emptyBinaryData(version);

  const hash = new Uint32Array(total);
  const hash2 = new Uint32Array(total);
  const source = new Float64Array(total * 2);
  const target = new Float64Array(total * 2);
  const flow = new Float32Array(total);
  const length = new Float32Array(total);
  const lanes = new Float32Array(total);

  seen.clear();
  let writeIndex = 0;
  for (const tile of tiles) {
    for (let i = 0; i < tile.count; i++) {
      if (!seen.addIfAbsent(tile.hash[i], tile.hash2[i])) continue;
      const sourceLngLat = webMercatorToLngLat(
        tile.origin[0] + tile.source[i * 2],
        tile.origin[1] + tile.source[i * 2 + 1],
      );
      const targetLngLat = webMercatorToLngLat(
        tile.origin[0] + tile.target[i * 2],
        tile.origin[1] + tile.target[i * 2 + 1],
      );
      hash[writeIndex] = tile.hash[i];
      hash2[writeIndex] = tile.hash2[i];
      source[writeIndex * 2] = sourceLngLat[0];
      source[writeIndex * 2 + 1] = sourceLngLat[1];
      target[writeIndex * 2] = targetLngLat[0];
      target[writeIndex * 2 + 1] = targetLngLat[1];
      flow[writeIndex] = tile.flow[i] || 0;
      length[writeIndex] = tile.length[i] || 0;
      lanes[writeIndex] = tile.lanes[i] || 1;
      writeIndex++;
    }
  }

  return attachStats({
    binary: true,
    count: writeIndex,
    origin: [0, 0],
    hash,
    hash2,
    source,
    target,
    flow,
    length,
    lanes,
  }, version);
}

// 行政区裁剪实现（含网格索引与分段身份哈希）统一收敛到 districtClipIndex.js，
// 主线程回退路径与 worker 共用同一实现，保证两模式裁剪结果一致

function colorToRgba(color, opacity = 1) {
  const css = colorToCss(color);
  const alpha = Math.max(0, Math.min(255, Math.round((Number(opacity) || 0) * 255)));
  if (css.startsWith("#")) {
    const hex = css.slice(1);
    const value = hex.length === 3
      ? hex.split("").map((part) => part + part).join("")
      : hex.padEnd(6, "0").slice(0, 6);
    const number = Number.parseInt(value, 16);
    if (Number.isFinite(number)) {
      return [(number >> 16) & 255, (number >> 8) & 255, number & 255, alpha];
    }
  }
  const rgb = css.match(/\d+(\.\d+)?/g)?.map(Number);
  if (rgb?.length >= 3) {
    return [rgb[0], rgb[1], rgb[2], alpha];
  }
  return [31, 120, 180, alpha];
}

function clamp01(value) {
  return Math.max(0, Math.min(1, Number(value) || 0));
}

function interpolate(value, stops) {
  if (value <= stops[0][0]) return stops[0][1];
  for (let i = 1; i < stops.length; i++) {
    const [x1, y1] = stops[i - 1];
    const [x2, y2] = stops[i];
    if (value <= x2) {
      const t = (value - x1) / (x2 - x1);
      return y1 + (y2 - y1) * t;
    }
  }
  return stops[stops.length - 1][1];
}

function dataDensityOpacity(count) {
  const total = Number(count) || 0;
  if (total <= 25000) return 1;
  if (total >= 180000) return 0.58;
  return interpolate(total, [
    [25000, 1],
    [80000, 0.78],
    [180000, 0.58],
  ]);
}

function createNetworkDataWorker() {
  if (typeof Worker === "undefined") return null;
  return new Worker(new URL("./networkData.worker.js", import.meta.url), { type: "module" });
}

// 模块级共享 worker：一个 Worker 服务全部 NetworkLayer 实例（监测页同时存在 ~8 个
// workerEnabled 图层，原先每实例一个 worker 常驻）。消息按实例的 ns 命名空间隔离，
// 引用计数归零（全部实例 dispose）时 terminate。
const sharedNetworkWorker = {
  worker: null,
  broken: false,
  refs: new Set(),
  callbacks: new Map(),
  nextRequestId: 0,
};

function rejectSharedWorkerCallbacks(error) {
  const pending = [...sharedNetworkWorker.callbacks.values()];
  sharedNetworkWorker.callbacks.clear();
  pending.forEach((callback) => callback.reject(error));
}

function acquireSharedNetworkWorker(layer) {
  if (sharedNetworkWorker.broken) return null;
  if (!sharedNetworkWorker.worker) {
    let worker = null;
    try {
      worker = createNetworkDataWorker();
    } catch (error) {
      console.warn("[NetworkLayer] shared worker init failed", error);
    }
    if (!worker) {
      sharedNetworkWorker.broken = true;
      return null;
    }
    worker.onmessage = (event) => {
      const message = event.data || {};
      const callback = sharedNetworkWorker.callbacks.get(message.id);
      if (!callback) return;
      sharedNetworkWorker.callbacks.delete(message.id);
      if (message.ok) {
        callback.resolve(message.result);
      } else {
        callback.reject(new Error(message.error || "worker error"));
      }
    };
    worker.onerror = (error) => {
      // worker 异常：terminate 并整体置为不可用（本会话内降级主线程同步路径），
      // reject 全部 pending，防止后续请求对着死 worker 永久挂起
      rejectSharedWorkerCallbacks(error instanceof Error ? error : new Error(error?.message || "worker error"));
      try {
        worker.terminate();
      } catch (terminateError) {
        void terminateError;
      }
      if (sharedNetworkWorker.worker === worker) {
        sharedNetworkWorker.worker = null;
        sharedNetworkWorker.broken = true;
      }
    };
    sharedNetworkWorker.worker = worker;
  }
  sharedNetworkWorker.refs.add(layer);
  return sharedNetworkWorker.worker;
}

function releaseSharedNetworkWorker(layer) {
  sharedNetworkWorker.refs.delete(layer);
  if (sharedNetworkWorker.refs.size || !sharedNetworkWorker.worker) return;
  rejectSharedWorkerCallbacks(new Error("worker terminated"));
  sharedNetworkWorker.worker.terminate();
  sharedNetworkWorker.worker = null;
}

export class NetworkLayer extends Layer {
  name = "NetworkLayer";

  constructor(opt = {}) {
    super(opt);
    this.lineWidth = opt.lineWidth || 20;
    this.fixedPixelWidth = opt.fixedPixelWidth === true;
    this.workerEnabled = opt.workerEnabled !== false;
    this.flowControl = opt.flowControl ?? false;
    this.flowMinWidth = opt.flowMinWidth || 1;
    this.flowMaxWidth = opt.flowMaxWidth || 40;
    this.flowWidthStep = opt.flowWidthStep || 20;
    this.widthMaxPixels = Number.isFinite(Number(opt.widthMaxPixels)) ? Number(opt.widthMaxPixels) : null;
    this.flowStyleStops = Array.isArray(opt.flowStyleStops) && opt.flowStyleStops.length
      ? opt.flowStyleStops
      : FLOW_STYLE_STOPS;
    this.color = colorToCss(opt.color ?? 0x1f78b4);
    this.opacity = opt.opacity ?? 1;
    // flowControl 图层默认随缩放/密度降透明度（全网底图防糊）；
    // 单条选中线路的断面图层应保持实色，传 zoomFadeOpacity:false 关闭衰减
    this.zoomFadeOpacity = opt.zoomFadeOpacity !== false;
    // 默认把连续链路拼成 PathLayer（更平滑）；置 false 走 GPU 实例化 LineLayer（逐链路），
    // 主线程不再遍历拼路径，配合 worker 二进制转换实现大线路断面的毫秒级上屏
    this.continuousPath = opt.continuousPath !== false;
    this.layerId = `network-line-${this.id}`;
    this.tileMode = false;
    this.tileZoom = opt.tileZoom || TILE_ZOOM;
    this.fullModeMaxZoom = Number.isFinite(Number(opt.fullModeMaxZoom)) ? Number(opt.fullModeMaxZoom) : FULL_MODE_MAX_ZOOM;
    this.tileRequest = opt.tileRequest || getTileNetworkBinary;
    this.fullRequest = opt.fullRequest || getFullNetworkBinary;
    this.jsonFallbackRequest = opt.jsonFallbackRequest || getTileNetwork;
    this.datasource = opt.datasource || "";
    this.tileExtraParams = opt.tileExtraParams || {};
    this.lineClipContext = opt.lineClipContext || null;
    // 裁剪上下文修订号：worker 端 memo 与主线程网格索引缓存的失效依据
    this.lineClipContextKey = this.lineClipContext ? 1 : 0;
    this.lineClipIndex = null;
    this.lineClipIndexKey = -1;
    this.tileCache = new Map();
    this.loadingTiles = new Set();
    this.visibleTileKeys = [];
    this.displayTileKeys = [];
    this.tileLoadToken = 0;
    this.tileAbortController = null;
    this.tileLastSeen = new Map();
    this.tileUpdateTimer = null;
    this.renderFrame = null;
    this.refreshFrame = null;
    this.refreshTimer = null;
    this.lastTileRefreshAt = 0;
    this.dataVersion = 0;
    this.deckData = emptyBinaryData(this.dataVersion);
    this.flowWidthCache = null;
    this.lineDataCache = null;
    this.pathGroupsCache = null;
    this.combinedCacheKey = "";
    this.detailKey = "";
    // 共享 worker：ns 为消息命名空间；workerAttachedTo 记录已向哪个 worker 实例声明过该 ns
    this.workerNs = String(this.id);
    this.workerAttachedTo = null;
    this.workerGeneration = 0;
    this.combineSeq = 0;
    this.combineInFlight = false;
    this.pendingCombine = null;
    this.combineFallbackWarned = false;
    // 粗档位优化默认开启；纯像素级精确场景可传 coarseCombineOptimization:false 关闭
    this.coarseCombineOptimization = opt.coarseCombineOptimization !== false;
    this.rawLinks = [];
  }

  onAdd(map) {
    super.onAdd(map);
    map.whenReady(() => {
      this.ensureDeckOverlay();
      this.renderDeckLayer();
      if (this.tileMode) {
        this.scheduleTileLoad(true);
      }
    });
  }

  ensureDeckOverlay() {
    return !!this.map?.map;
  }

  ensureWorker() {
    if (!this.workerEnabled || this.isDisposed) return null;
    const worker = acquireSharedNetworkWorker(this);
    if (!worker) return null;
    if (this.workerAttachedTo !== worker) {
      // 首次挂到该 worker（或 worker 重建后）：先声明命名空间的 generation 与裁剪上下文，
      // FIFO 保证这两条消息先于后续任何 postWorker 请求被处理
      this.workerAttachedTo = worker;
      this.postWorkerReset();
      if (this.lineClipContext) {
        this.syncWorkerClipContext();
      }
    }
    return worker;
  }

  // 当前实例是否仍挂在活跃的共享 worker 上
  workerAlive() {
    return !!this.workerAttachedTo && sharedNetworkWorker.worker === this.workerAttachedTo;
  }

  postWorker(type, payload = {}, transfer = []) {
    const worker = this.ensureWorker();
    if (!worker) return Promise.reject(new Error("worker unavailable"));
    const id = ++sharedNetworkWorker.nextRequestId;
    return new Promise((resolve, reject) => {
      sharedNetworkWorker.callbacks.set(id, { resolve, reject });
      try {
        worker.postMessage({ id, type, ns: this.workerNs, generation: this.workerGeneration, ...payload }, transfer);
      } catch (error) {
        sharedNetworkWorker.callbacks.delete(id);
        reject(error);
      }
    });
  }

  resetWorkerCache() {
    this.workerGeneration++;
    this.postWorkerReset();
  }

  postWorkerReset() {
    if (!this.workerAlive()) return;
    const id = ++sharedNetworkWorker.nextRequestId;
    try {
      this.workerAttachedTo.postMessage({ id, type: "reset", ns: this.workerNs, generation: this.workerGeneration });
    } catch (error) {
      void error; // worker 失效时静默，后续请求走同步回退
    }
  }

  // 裁剪上下文推送到 worker 常驻（#1）：仅在上下文变化或首次挂载时发送一次
  syncWorkerClipContext() {
    if (!this.workerAlive()) return;
    const id = ++sharedNetworkWorker.nextRequestId;
    try {
      this.workerAttachedTo.postMessage({
        id,
        type: "setClipContext",
        ns: this.workerNs,
        context: this.lineClipContext || null,
        contextKey: String(this.lineClipContextKey),
      });
    } catch (error) {
      void error;
    }
  }

  dropWorkerTiles(keys) {
    if (!keys?.length || !this.workerAlive()) return;
    this.postWorker("dropTiles", { keys }).catch(() => {});
  }

  setData(data) {
    const links = Array.isArray(data) ? data : [];
    // 同一数组引用重复 set（调用方常以 computed 缓存引用反复调用）直接短路：
    // 不 reset worker 缓存、不重传、不重建。注意原地 mutate 数组后重设同一引用不会生效，
    // 需要更新时传新数组。
    if (!this.tileMode && links === this.rawLinks) return;
    this.tileMode = false;
    this.tileLoadToken++;
    this.abortTileRequests();
    this.resetWorkerCache();
    this.dataVersion++;
    const version = this.dataVersion;
    this.rawLinks = links;
    this.pathGroupsCache = null;
    if (!links.length) {
      this.deckData = emptyBinaryData(version);
      this.flowWidthCache = null;
      this.combinedCacheKey = "";
      this.detailKey = "";
      this.queueDeckUpdate();
      return;
    }
    if (this.ensureWorker()) {
      this.postWorker("setLinks", { links, version })
        .then((deckData) => {
          if (this.isDisposed || this.tileMode || version !== this.dataVersion) return;
          this.deckData = deckData;
          this.flowWidthCache = null;
          this.combinedCacheKey = "";
          this.detailKey = "";
          this.queueDeckUpdate();
        })
        .catch((error) => {
          console.warn(`[${this.name}] worker link conversion failed`, error);
          if (this.isDisposed || this.tileMode || version !== this.dataVersion) return;
          this.deckData = linksToBinaryData(links, version);
          this.queueDeckUpdate();
        });
      return;
    }
    this.deckData = linksToBinaryData(links, version);
    this.flowWidthCache = null;
    this.combinedCacheKey = "";
    this.detailKey = "";
    this.queueDeckUpdate();
  }

  setTileSource(datasource, opt = {}) {
    this.tileMode = true;
    this.rawLinks = [];
    this.datasource = datasource || "";
    this.tileZoom = opt.tileZoom || this.tileZoom || TILE_ZOOM;
    this.fullModeMaxZoom = Number.isFinite(Number(opt.fullModeMaxZoom)) ? Number(opt.fullModeMaxZoom) : this.fullModeMaxZoom;
    this.tileRequest = opt.tileRequest || this.tileRequest || getTileNetworkBinary;
    this.fullRequest = opt.fullRequest || this.fullRequest || getFullNetworkBinary;
    this.jsonFallbackRequest = opt.jsonFallbackRequest || this.jsonFallbackRequest || getTileNetwork;
    this.tileExtraParams = opt.tileExtraParams || {};
    this.tileLoadToken++;
    this.abortTileRequests();
    this.resetWorkerCache();
    this.tileCache.clear();
    this.loadingTiles.clear();
    this.visibleTileKeys = [];
    this.displayTileKeys = [];
    this.tileLastSeen.clear();
    this.pathGroupsCache = null;
    this.dataVersion++;
    this.deckData = emptyBinaryData(this.dataVersion);
    this.flowWidthCache = null;
    this.combinedCacheKey = "";
    this.detailKey = "";
    this.queueDeckUpdate();
    if (this.visible !== false) {
      this.scheduleTileLoad(true);
    }
  }

  on(type, data) {
    super.on(type, data);
    // 缩放相关样式（宽度插值 / zoomFadeOpacity / softEdge 门限）依赖 map.zoom；
    // 原先只在瓦片集变化时重渲染，档位内缩放样式冻结、跨档位跳变。
    // rAF 已合帧，且 lineLayerData wrapper 引用稳定时 deck 仅做浅比较，重渲染成本低。
    if (type === MAP_EVENT.UPDATE_ZOOM && this.hasZoomDependentStyle()) {
      this.queueDeckUpdate();
    }
    if (!this.tileMode) return;
    // 隐藏期间不跟随相机加载/合并瓦片（如客流着色激活时底图瓦片层被隐藏，
    // 原先每次平移/缩放仍触发百万段级 worker 合并，纯属空转）；show() 时补载
    if (this.visible === false) return;
    if (
      type === MAP_EVENT.UPDATE_CENTER ||
      type === MAP_EVENT.UPDATE_ZOOM ||
      type === MAP_EVENT.UPDATE_RENDERER_SIZE
    ) {
      const detail = this.currentTileDetail();
      if (detail.full && this.detailKey === detailKey(detail) && this.tileCache.has(tileKey(fullTile()))) {
        return;
      }
      this.scheduleTileLoad();
    }
  }

  hasZoomDependentStyle() {
    if (this.visible === false || !this.deckData?.count) return false;
    // 非固定像素宽度：currentLineWidthPixels 按 zoom 插值，softEdge 也有 zoom<11.5 门限
    if (!this.fixedPixelWidth) return true;
    // flowControl + zoomFadeOpacity：透明度随缩放衰减
    return this.flowControl && this.zoomFadeOpacity;
  }

  scheduleTileLoad(immediate = false) {
    if (this.visible === false || !this.map || !this.datasource) return;
    const now = Date.now();
    if (this.tileUpdateTimer) {
      clearTimeout(this.tileUpdateTimer);
    } else {
      this.tileScheduleStartedAt = now; // 新一轮 debounce 周期起点
    }
    const detail = this.currentTileDetail();
    const baseDelay = TILE_SCHEDULE_DELAY[detail.level] ?? 160;
    // debounce 必须带 max-wait：跟随模式每帧 jumpTo / 长距离拖拽会持续重置定时器，
    // 无上限时瓦片加载被饿死到运动结束，路网长时间缺块。保证周期起点后 TILE_SCHEDULE_MAX_WAIT 内必触发一次。
    const elapsed = now - (this.tileScheduleStartedAt || now);
    const delay = immediate ? 0 : Math.max(0, Math.min(baseDelay, TILE_SCHEDULE_MAX_WAIT - elapsed));
    this.tileUpdateTimer = setTimeout(() => {
      this.tileUpdateTimer = null;
      this.loadVisibleTiles();
    }, delay);
  }

  visibleTiles() {
    if (!this.map?.getWindowRangeAndWebMercator) return [];
    const detail = this.currentTileDetail();
    if (detail.full) return [fullTile()];
    const z = detail.z;
    const bounds = this.map.getWindowRangeAndWebMercator();
    const nw = webMercatorToTile(bounds.minX, bounds.maxY, z);
    const se = webMercatorToTile(bounds.maxX, bounds.minY, z);
    const centerTile = webMercatorToTile(this.map.center?.[0] || 0, this.map.center?.[1] || 0, z);
    const buffer = tileBufferForDetail(detail.level);
    const minX = Math.max(0, Math.min(nw.x, se.x) - buffer);
    const maxX = Math.min(Math.pow(2, z) - 1, Math.max(nw.x, se.x) + buffer);
    const minY = Math.max(0, Math.min(nw.y, se.y) - buffer);
    const maxY = Math.min(Math.pow(2, z) - 1, Math.max(nw.y, se.y) + buffer);
    const tiles = [];
    for (let x = minX; x <= maxX; x++) {
      for (let y = minY; y <= maxY; y++) {
        tiles.push({
          z,
          x,
          y,
          detail: detail.level,
          distance: Math.abs(x - centerTile.x) + Math.abs(y - centerTile.y),
        });
      }
    }
    return tiles
      .sort((a, b) => a.distance - b.distance)
      .slice(0, MAX_VISIBLE_TILE_REQUESTS)
      .map(({ z, x, y, detail }) => ({ z, x, y, detail }));
  }

  currentTileDetail() {
    const mapZoom = Number(this.map?.zoom);
    if (!Number.isFinite(mapZoom)) {
      return { level: "overview", z: MIN_TILE_ZOOM };
    }
    return DETAIL_ZOOM_STOPS.find((stop) => mapZoom >= stop.minZoom) || DETAIL_ZOOM_STOPS[DETAIL_ZOOM_STOPS.length - 1];
  }

  async loadVisibleTiles() {
    if (this.visible === false || !this.tileMode || !this.datasource || !this.map) return;
    const token = this.tileLoadToken;
    const tiles = this.visibleTiles();
    const nextDetailKey = detailKey(this.currentTileDetail());
    if (nextDetailKey !== this.detailKey) {
      this.detailKey = nextDetailKey;
      this.displayTileKeys = [];
      this.combinedCacheKey = "";
    }
    this.visibleTileKeys = tiles.map(tileKey);
    const seenAt = Date.now();
    this.visibleTileKeys.forEach((key) => this.tileLastSeen.set(key, seenAt));
    this.scheduleRefreshVisibleTileData();

    const missing = tiles.filter((tile) => {
      const key = tileKey(tile);
      return !this.tileCache.has(key) && !this.loadingTiles.has(key);
    });
    if (!missing.length) return;

    const queue = [...missing];
    const workers = Array.from({ length: Math.min(TILE_LOAD_CONCURRENCY, queue.length) }, async () => {
      while (queue.length && !this.isDisposed && token === this.tileLoadToken) {
        const tile = queue.shift();
        await this.loadTile(tile, token);
      }
    });
    await Promise.all(workers);
    if (!this.isDisposed && token === this.tileLoadToken) {
      this.refreshVisibleTileData();
    }
  }

  async parseTileResponseAsync(response, key, version) {
    const payload = response instanceof ArrayBuffer ? response : response?.data;
    if (this.ensureWorker()) {
      if (payload instanceof ArrayBuffer) {
        return this.postWorker("setTileBinary", { key, version, buffer: payload }, [payload]);
      }
      return this.postWorker("setTileJson", {
        key,
        version,
        links: Array.isArray(response) ? response : response?.data || [],
      });
    }
    const parsed = parseTileResponse(response, version);
    return parsed;
  }

  // token 失效（切数据源/setData/dispose）时中止全部在途瓦片请求。
  // 注意：真正取消 HTTP 需要 api 层把第二参的 signal 透传给 axios config
  //（getTileNetworkBinary 等目前忽略额外参数，传入无害）；api 文件不在本图层职责内。
  abortTileRequests() {
    if (this.tileAbortController) {
      try {
        this.tileAbortController.abort();
      } catch (error) {
        void error;
      }
      this.tileAbortController = null;
    }
  }

  currentTileAbortSignal() {
    if (typeof AbortController === "undefined") return undefined;
    if (!this.tileAbortController) {
      this.tileAbortController = new AbortController();
    }
    return this.tileAbortController.signal;
  }

  async loadTile(tile, token = this.tileLoadToken) {
    const key = tileKey(tile);
    this.loadingTiles.add(key);
    try {
      const params = {
        datasource: this.datasource,
        z: tile.z,
        x: tile.x,
        y: tile.y,
        ...this.tileExtraParams,
      };
      const requestOptions = { signal: this.currentTileAbortSignal() };
      const request = tile.full ? this.fullRequest : this.tileRequest;
      let res;
      let parsed;
      try {
        res = await request(params, requestOptions);
      } catch (binaryError) {
        if (this.isDisposed || token !== this.tileLoadToken) return;
        if (tile.full || !this.jsonFallbackRequest || request === this.jsonFallbackRequest) throw binaryError;
        res = await this.jsonFallbackRequest(params, requestOptions);
      }
      if (this.isDisposed || token !== this.tileLoadToken) return;
      try {
        parsed = await this.parseTileResponseAsync(res, key, ++this.dataVersion);
      } catch (parseError) {
        if (tile.full || !this.jsonFallbackRequest || request === this.jsonFallbackRequest) throw parseError;
        const fallbackRes = await this.jsonFallbackRequest(params, requestOptions);
        if (this.isDisposed || token !== this.tileLoadToken) return;
        parsed = await this.parseTileResponseAsync(fallbackRes, key, ++this.dataVersion);
      }
      if (!this.isDisposed && token === this.tileLoadToken) {
        this.tileCache.set(key, parsed);
        this.scheduleRefreshVisibleTileData();
      }
    } catch (error) {
      // token 已失效的失败（含主动 abort）不告警不写缓存
      if (this.isDisposed || token !== this.tileLoadToken) return;
      console.warn(`[${this.name}] tile load failed`, tile, error);
      this.tileCache.set(key, emptyBinaryData(++this.dataVersion));
    } finally {
      this.loadingTiles.delete(key);
    }
  }

  refreshVisibleTileData() {
    if (!this.tileMode) return;
    this.lastTileRefreshAt = Date.now();
    if (this.refreshTimer) {
      clearTimeout(this.refreshTimer);
      this.refreshTimer = null;
    }
    const token = this.tileLoadToken;
    const visibleSet = new Set(this.visibleTileKeys);
    const hasPendingVisibleTiles = this.visibleTileKeys.some((key) => !this.tileCache.has(key));
    const loadedVisible = this.visibleTileKeys.filter((key) => this.tileCache.has(key));
    let displayKeys = loadedVisible;

    if (hasPendingVisibleTiles && this.displayTileKeys.length) {
      const staleKeys = this.displayTileKeys
        .filter((key) => !visibleSet.has(key) && this.tileCache.has(key))
        .sort((a, b) => (this.tileLastSeen.get(b) || 0) - (this.tileLastSeen.get(a) || 0))
        .slice(0, MAX_STALE_TILES);
      displayKeys = [...new Set([...loadedVisible, ...staleKeys])];
    }

    this.displayTileKeys = displayKeys;
    const nextCombinedCacheKey = displayKeys
      .map((key) => `${key}:${this.tileCache.get(key)?.version || 0}`)
      .join("|");
    if (nextCombinedCacheKey !== this.combinedCacheKey) {
      this.combinedCacheKey = nextCombinedCacheKey;
      const version = ++this.dataVersion;
      this.flowWidthCache = null;
      this.combineVisibleTileData(displayKeys, version, token);
    }
    this.pruneTileCache();
  }

  combineOptsForCurrentDetail() {
    if (!this.coarseCombineOptimization) return {};
    return COARSE_DETAIL_COMBINE_OPTS[this.currentTileDetail()?.level] || {};
  }

  async combineVisibleTileData(displayKeys, version, token) {
    if (!this.ensureWorker()) {
      this.combineSeq++;
      const combined = combineBinaryTiles(displayKeys, this.tileCache, version);
      // worker 中途损坏时主线程缓存里是 worker 返回的摘要（无 binary 字段），合并必为空；
      // 此时保留上一次成功渲染的数据（新瓦片会经主线程解析逐步恢复），避免静默清空路网
      const expectingLinks = displayKeys.some((key) => (this.tileCache.get(key)?.count || 0) > 0);
      if (!combined.count && expectingLinks) {
        this.warnCombineDegradedOnce("tile cache holds worker summaries");
        return;
      }
      this.deckData = this.applyLineClipContext(combined, version);
      this.queueDeckUpdate();
      return;
    }
    // 在途合并去抖：瓦片流式到达期间每个到达都会请求一次全量合并，百万段级合并
    // 单次超百毫秒，排队执行时前面的结果全部作废（seq 检查），worker 长时间白算。
    // 只保留一个在途合并，期间的新请求记为 pending，完成后仅补跑最新一次。
    if (this.combineInFlight) {
      this.pendingCombine = { displayKeys, version, token };
      return;
    }
    this.combineInFlight = true;
    const seq = ++this.combineSeq;
    try {
      const deckData = await this.postWorker("combine", {
        keys: displayKeys,
        version,
        // 行政区裁剪已下沉 worker：cacheKey 供 worker 端 (combinedCacheKey, contextKey) 记忆化
        cacheKey: this.lineClipContext ? this.combinedCacheKey : "",
        ...this.combineOptsForCurrentDetail(),
      });
      if (this.isDisposed || token !== this.tileLoadToken || seq !== this.combineSeq) return;
      // worker 已按 clipContext 完成裁剪，主线程不再重复裁剪
      this.deckData = deckData;
      this.queueDeckUpdate();
    } catch (error) {
      if (this.isDisposed || token !== this.tileLoadToken || seq !== this.combineSeq) return;
      // worker 模式下主线程缓存是摘要，回退 combineBinaryTiles 必得空集——
      // 保留上一次成功渲染的数据并只告警一次，而不是静默清空路网
      this.warnCombineDegradedOnce(error);
    } finally {
      this.combineInFlight = false;
      const pending = this.pendingCombine;
      this.pendingCombine = null;
      if (pending && !this.isDisposed && pending.token === this.tileLoadToken) {
        this.combineVisibleTileData(pending.displayKeys, pending.version, pending.token);
      }
    }
  }

  warnCombineDegradedOnce(error) {
    if (this.combineFallbackWarned) return;
    this.combineFallbackWarned = true;
    console.warn(`[${this.name}] worker tile combine failed; keeping last rendered data`, error);
  }

  // 非 worker 模式的主线程裁剪：网格索引按 contextKey 缓存复用
  applyLineClipContext(data, version = data?.version || 0) {
    if (!this.lineClipContext) return data || emptyBinaryData(version);
    if (!this.lineClipIndex || this.lineClipIndexKey !== this.lineClipContextKey) {
      this.lineClipIndex = buildDistrictClipIndex(this.lineClipContext);
      this.lineClipIndexKey = this.lineClipContextKey;
    }
    return clipRenderableBinaryData(data, this.lineClipIndex, version);
  }

  setLineClipContext(context = null) {
    const nextContext = context || null;
    if (this.lineClipContext === nextContext) return;
    this.lineClipContext = nextContext;
    this.lineClipContextKey++;
    this.lineClipIndex = null;
    this.flowWidthCache = null;
    this.combinedCacheKey = "";
    this.syncWorkerClipContext();
    if (this.tileMode) {
      this.refreshVisibleTileData();
      if (!this.visibleTileKeys.length) {
        this.scheduleTileLoad(true);
      }
    } else {
      this.queueDeckUpdate();
    }
  }

  scheduleRefreshVisibleTileData() {
    if (typeof requestAnimationFrame !== "function") {
      this.refreshVisibleTileData();
      return;
    }
    if (this.refreshFrame || this.refreshTimer) return;
    // rAF 合帧之外的 ~120ms 节流（带 trailing）：refreshVisibleTileData 落地时会
    // 清掉尚未触发的 trailing 定时器，loadVisibleTiles 完成后的直呼保证最终一致
    const elapsed = Date.now() - (this.lastTileRefreshAt || 0);
    if (elapsed >= TILE_REFRESH_THROTTLE_MS) {
      this.refreshFrame = requestAnimationFrame(() => {
        this.refreshFrame = null;
        this.refreshVisibleTileData();
      });
      return;
    }
    this.refreshTimer = setTimeout(() => {
      this.refreshTimer = null;
      this.refreshVisibleTileData();
    }, TILE_REFRESH_THROTTLE_MS - elapsed);
  }

  pruneTileCache() {
    if (this.tileCache.size <= MAX_TILE_CACHE) return;
    const keep = new Set([...this.visibleTileKeys, ...this.displayTileKeys, ...this.loadingTiles]);
    const deletable = [...this.tileCache.keys()]
      .filter((key) => !keep.has(key))
      .sort((a, b) => (this.tileLastSeen.get(a) || 0) - (this.tileLastSeen.get(b) || 0));
    while (this.tileCache.size > MAX_TILE_CACHE && deletable.length) {
      const key = deletable.shift();
      this.tileCache.delete(key);
      this.tileLastSeen.delete(key);
      this.dropWorkerTiles([key]);
    }
  }

  currentLineWidthPixels() {
    const baseWidth = lineWidthToPixels(this.lineWidth);
    const zoom = Number(this.map?.zoom);
    const minPixels = this.flowControl ? Math.min(networkLineMinPixels(), 0.55) : networkLineMinPixels();
    if (this.fixedPixelWidth) return Math.max(0.1, Number(this.lineWidth) / 10 || 0.1);
    if (!Number.isFinite(zoom)) return Math.max(minPixels, baseWidth);
    return Math.max(minPixels, interpolate(zoom, [
      [7, Math.max(0.28, baseWidth * 0.16)],
      [9, Math.max(0.36, baseWidth * 0.24)],
      [11, Math.max(0.58, baseWidth * 0.42)],
      [13, Math.min(baseWidth, 6.5)],
      [16, baseWidth],
    ]));
  }

  currentLineOpacity() {
    const baseOpacity = Math.max(0, Math.min(1, Number(this.opacity) || 1));
    if (!this.flowControl || !this.zoomFadeOpacity) return baseOpacity;
    const zoom = Number(this.map?.zoom);
    if (!Number.isFinite(zoom)) return baseOpacity;
    const zoomOpacity = interpolate(zoom, [
      [7, 0.3],
      [9, 0.42],
      [11, 0.62],
      [13, 0.82],
      [15, 1],
    ]);
    const densityOpacity = dataDensityOpacity(this.deckData?.count || 0);
    const flowFactor = this.flowControl ? 0.82 : 1;
    return Math.max(0.18, Math.min(baseOpacity, baseOpacity * zoomOpacity * densityOpacity * flowFactor));
  }

  flowStyleAttributes(data, opacity = this.opacity) {
    const baseWidth = Math.max(3, lineWidthToPixels(this.lineWidth));
    const stepWidth = Math.max(1, lineWidthToPixels(this.flowWidthStep || this.flowMaxWidth));
    const alpha = Math.max(0, Math.min(255, Math.round((Number(opacity) || 0) * 255)));
    const cacheKey = [
      data.version,
      data.minFlow,
      data.maxFlow,
      baseWidth,
      stepWidth,
      alpha,
    ].join(":");
    if (this.flowWidthCache?.key === cacheKey) {
      return this.flowWidthCache.attributes;
    }

    const widths = new Float32Array(data.count);
    const colors = new Uint8Array(data.count * 4);
    if (!data.count) {
      const attributes = { widths, colors };
      this.flowWidthCache = { key: cacheKey, attributes };
      return attributes;
    }

    if (data.maxFlow <= data.minFlow) {
      const value = Number(data.maxFlow) || 0;
      const style = this.flowStyleForValue(value, value > 0 ? 1 : 0);
      widths.fill(baseWidth + stepWidth * style.widthStep);
      for (let i = 0; i < data.count; i++) {
        const offset = i * 4;
        colors[offset] = style.color[0];
        colors[offset + 1] = style.color[1];
        colors[offset + 2] = style.color[2];
        colors[offset + 3] = alpha;
      }
    } else {
      const span = data.maxFlow - data.minFlow;
      for (let i = 0; i < data.count; i++) {
        const value = Math.max(data.minFlow, Number(data.flow[i]) || 0);
        const ratio = (value - data.minFlow) / span;
        const style = this.flowStyleForValue(value, ratio);
        const offset = i * 4;
        widths[i] = baseWidth + stepWidth * style.widthStep;
        colors[offset] = style.color[0];
        colors[offset + 1] = style.color[1];
        colors[offset + 2] = style.color[2];
        colors[offset + 3] = alpha;
      }
    }

    const attributes = { widths, colors };
    this.flowWidthCache = { key: cacheKey, attributes };
    return attributes;
  }

  flowStyleForValue(value, ratio) {
    const stops = Array.isArray(this.flowStyleStops) && this.flowStyleStops.length
      ? this.flowStyleStops
      : FLOW_STYLE_STOPS;
    const hasAbsoluteStops = stops.some((stop) => Number.isFinite(Number(stop.maxValue)));
    if (hasAbsoluteStops) {
      const numericValue = Number(value) || 0;
      return stops.find((stop) => numericValue <= Number(stop.maxValue)) || stops[stops.length - 1];
    }
    return this.flowStyleForRatio(ratio);
  }

  flowStyleForRatio(ratio) {
    const value = clamp01(ratio);
    const stops = Array.isArray(this.flowStyleStops) && this.flowStyleStops.length
      ? this.flowStyleStops
      : FLOW_STYLE_STOPS;
    return stops.find((stop) => value < stop.limit) || stops[stops.length - 1];
  }

  queueDeckUpdate() {
    if (this.renderFrame || typeof requestAnimationFrame !== "function") {
      if (typeof requestAnimationFrame !== "function") {
        this.renderDeckLayer();
      }
      return;
    }
    this.renderFrame = requestAnimationFrame(() => {
      this.renderFrame = null;
      this.renderDeckLayer();
    });
  }

  updateSource() {
    this.queueDeckUpdate();
  }

  updatePaint() {
    this.queueDeckUpdate();
  }

  publishDebug(data, attributes) {
    // 调试通道默认关闭：每次 deck 更新的 slice+Array.from+JSON.stringify 在生产是纯开销
    if (typeof window === "undefined" || !window.APP_CONFIG?.debug) return;
    if (typeof document === "undefined" || !document.documentElement?.dataset) return;
    if (this.name !== "NetworkLayer") return;
    const sampleColors = attributes.getColor?.value
      ? Array.from(attributes.getColor.value.slice(0, 16))
      : [];
    const sampleWidths = attributes.getWidth?.value
      ? Array.from(attributes.getWidth.value.slice(0, 8)).map((value) => Math.round(value * 100) / 100)
      : [];
    document.documentElement.dataset.gjNetworkCount = String(data?.count || 0);
    document.documentElement.dataset.gjNetworkFlowControl = this.flowControl ? "1" : "0";
    document.documentElement.dataset.gjNetworkFlowRange = `${data?.minFlow || 0}:${data?.maxFlow || 0}`;
    document.documentElement.dataset.gjNetworkFlowColors = JSON.stringify(sampleColors);
    document.documentElement.dataset.gjNetworkFlowWidths = JSON.stringify(sampleWidths);
  }

  renderDeckLayer() {
    if (!this.ensureDeckOverlay()) return;
    if (this.visible === false || !this.deckData?.count) {
      removeSharedDeckLayer(this.map, this.layerId);
      return;
    }

    const data = this.deckData;
    const baseWidth = lineWidthToPixels(this.lineWidth);
    const zoomWidth = this.currentLineWidthPixels();
    const visualOpacity = this.currentLineOpacity();
    let getWidth = zoomWidth;
    let widthScale = 1;

    const flowStyle = this.flowControl ? this.flowStyleAttributes(data, visualOpacity) : null;
    if (this.flowControl) {
      getWidth = 1;
      widthScale = baseWidth > 0 ? zoomWidth / baseWidth : 1;
    }
    // data wrapper 引用稳定（deckData 与 flowStyle 未变时复用同一对象）：
    // deck 浅比较 data 未变即跳过 attribute 重绑定/上传，缩放触发的重渲染近乎零成本
    const lineData = this.lineLayerData(data, flowStyle);
    const attributes = lineData.attributes;

    const lineColor = colorToRgba(this.color, visualOpacity);
    const softEdgePixels = this.fixedPixelWidth || this.flowControl || Number(this.map?.zoom) < 11.5 ? 0 : networkLineSoftEdgePixels();
    const widthMaxPixels = this.widthMaxPixels || (this.flowControl ? 24 : 22);
    const flowColorAccessor = (object, info = {}) => {
      const colors = attributes.getColor?.value;
      const index = Number.isFinite(Number(info.index)) ? Number(info.index) : 0;
      const offset = index * 4;
      return colors && colors.length >= offset + 4
        ? [colors[offset], colors[offset + 1], colors[offset + 2], colors[offset + 3]]
        : lineColor;
    };
    const commonProps = {
      coordinateSystem: COORDINATE_SYSTEM.LNGLAT,
      // 现状说明：本页未挂 CityBuildingsLayer 时 buildingLayerId 为 null，deck 层与其后
      // addLayer 的 maplibre 业务层的相对顺序取决于插入时序；统一 anchor 层需要动共享
      // 地图初始化（本轮范围外），deck 层之间的顺序由注册表 order（zIndex）保证
      beforeId: this.map?.buildingLayerId,
      widthScale,
      widthUnits: "pixels",
      pickable: false,
      opacity: 1,
      parameters: {
        depthTest: false,
        blend: true,
      },
      capRounded: true,
      jointRounded: true,
      miterLimit: 2,
    };
    this.publishDebug(data, attributes);
    const pathData = (this.tileMode || !this.continuousPath) ? [] : this.buildContinuousPathData(data, lineColor, zoomWidth, visualOpacity);
    if (pathData.length) {
      const layers = this.renderPathLayers(pathData, commonProps, softEdgePixels, widthMaxPixels, widthScale, zoomWidth);
      setSharedDeckLayer(this.map, this.layerId, layers, this.zIndex);
      return;
    }
    const layers = [];
    if (softEdgePixels > 0) {
      layers.push(new LineLayer({
        ...commonProps,
        id: `${this.layerId}-soft-edge`,
        data: lineData,
        opacity: 0.28,
        widthScale: this.flowControl
          ? widthScale * (1 + softEdgePixels / Math.max(1, zoomWidth))
          : widthScale,
        getColor: this.flowControl ? flowColorAccessor : lineColor,
        getWidth: this.flowControl ? getWidth : getWidth + softEdgePixels,
        widthMinPixels: networkLineMinPixels() + softEdgePixels,
        widthMaxPixels: widthMaxPixels + softEdgePixels,
      }));
    }
    const layer = new LineLayer({
      ...commonProps,
      id: this.layerId,
      data: lineData,
      getColor: this.flowControl ? flowColorAccessor : lineColor,
      getWidth,
      widthMinPixels: this.fixedPixelWidth
        ? 0.1
        : this.flowControl ? Math.min(networkLineMinPixels(), 0.55) : networkLineMinPixels(),
      widthMaxPixels,
    });
    layers.push(layer);
    setSharedDeckLayer(this.map, this.layerId, layers, this.zIndex);
  }

  // {length, attributes} 包装对象按 (deckData, flowStyle) 引用记忆化，
  // 内容未变时 deck 得到同一 data 引用即可跳过 attribute 更新
  lineLayerData(data, flowStyle = null) {
    const cached = this.lineDataCache;
    if (cached && cached.source === data && cached.flowStyle === flowStyle) {
      return cached.value;
    }
    const attributes = {
      getSourcePosition: { value: data.source, size: 2 },
      getTargetPosition: { value: data.target, size: 2 },
    };
    if (flowStyle) {
      attributes.getWidth = { value: flowStyle.widths, size: 1 };
      attributes.getColor = { value: flowStyle.colors, size: 4 };
    }
    const value = { length: data.count, attributes };
    this.lineDataCache = { source: data, flowStyle, value };
    return value;
  }

  // 连续路径几何分组：按 (rawLinks 引用, flowControl, stops 引用, data.version, tolerance) 缓存。
  // 分组与坐标重投影是路径构建的全部重活；透明度/线宽等 paint 变化只需在 buildContinuousPathData
  // 里对少量分组重套样式，不再全量重算重投影
  continuousPathGroups(data) {
    const links = Array.isArray(this.rawLinks) ? this.rawLinks : [];
    if (links.length < 2) return [];
    const stopsRef = this.flowControl ? this.flowStyleStops : null;
    const dataVersion = data?.version || 0;
    const tolerance = routePathJoinToleranceMeters();
    const cached = this.pathGroupsCache;
    if (
      cached &&
      cached.links === links &&
      cached.flowControl === this.flowControl &&
      cached.stopsRef === stopsRef &&
      cached.dataVersion === dataVersion &&
      cached.tolerance === tolerance
    ) {
      return cached.groups;
    }

    const toleranceSq = tolerance * tolerance;
    const groups = [];
    let current = null;

    const flush = () => {
      if (current?.points?.length > 1) {
        groups.push({
          stop: current.stop,
          path: current.points.map((point) => webMercatorToLngLat(point[0], point[1])),
        });
      }
      current = null;
    };

    // 分组 key 只由样式档位（颜色 + widthStep）决定，与 alpha/线宽无关，
    // 因此几何分组可跨 paint 变化复用
    const styleForLink = (link) => {
      if (!this.flowControl) {
        return { key: "default", stop: null };
      }
      const flow = linkFlowValue(link);
      let ratio = 0;
      if (data.maxFlow > data.minFlow) {
        ratio = clamp01((Math.max(data.minFlow, flow) - data.minFlow) / (data.maxFlow - data.minFlow));
      } else {
        ratio = data.maxFlow > 0 ? 1 : 0;
      }
      const stop = this.flowStyleForValue(flow, ratio);
      return { key: `${stop.color.join(",")}:${stop.widthStep}`, stop };
    };

    for (const link of links) {
      const endpoints = linkWebMercatorEndpoints(link);
      if (!endpoints) continue;

      const style = styleForLink(link);
      let start = endpoints.source;
      let end = endpoints.target;

      if (!current || current.key !== style.key) {
        flush();
        current = { key: style.key, stop: style.stop, points: [start, end] };
        continue;
      }

      const last = current.points[current.points.length - 1];
      const sourceDistance = distanceSq(last, endpoints.source);
      const targetDistance = distanceSq(last, endpoints.target);
      if (targetDistance < sourceDistance) {
        start = endpoints.target;
        end = endpoints.source;
      }
      const joinDistance = Math.min(sourceDistance, targetDistance);
      if (joinDistance > toleranceSq) {
        flush();
        current = { key: style.key, stop: style.stop, points: [start, end] };
        continue;
      }

      if (joinDistance > 0.01) {
        current.points[current.points.length - 1] = [
          (last[0] + start[0]) / 2,
          (last[1] + start[1]) / 2,
        ];
      }
      appendPoint(current.points, end);
    }

    flush();
    this.pathGroupsCache = {
      links,
      flowControl: this.flowControl,
      stopsRef,
      dataVersion,
      tolerance,
      groups,
    };
    return groups;
  }

  buildContinuousPathData(data, lineColor, zoomWidth, opacity) {
    const groups = this.continuousPathGroups(data);
    if (!groups.length) return [];
    const baseWidth = Math.max(3, lineWidthToPixels(this.lineWidth));
    const stepWidth = Math.max(1, lineWidthToPixels(this.flowWidthStep || this.flowMaxWidth));
    const alpha = Math.max(0, Math.min(255, Math.round((Number(opacity) || 0) * 255)));
    return groups.map((group) => ({
      path: group.path,
      color: group.stop
        ? [group.stop.color[0], group.stop.color[1], group.stop.color[2], alpha]
        : lineColor,
      width: group.stop
        ? baseWidth + stepWidth * group.stop.widthStep
        : zoomWidth,
    }));
  }

  renderPathLayers(pathData, commonProps, softEdgePixels, widthMaxPixels, widthScale, zoomWidth) {
    const layers = [];
    const pathProps = {
      ...commonProps,
      data: pathData,
      getPath: (item) => item.path,
      getColor: (item) => item.color,
      getWidth: (item) => item.width,
      widthScale: this.flowControl ? widthScale : 1,
      widthMaxPixels,
    };

    if (softEdgePixels > 0) {
      layers.push(new PathLayer({
        ...pathProps,
        id: `${this.layerId}-soft-edge`,
        opacity: 0.28,
        getWidth: (item) => item.width + softEdgePixels,
        widthMinPixels: networkLineMinPixels() + softEdgePixels,
        widthMaxPixels: widthMaxPixels + softEdgePixels,
      }));
    }

    layers.push(new PathLayer({
      ...pathProps,
      id: this.layerId,
      widthMinPixels: this.fixedPixelWidth
        ? 0.1
        : this.flowControl ? Math.min(networkLineMinPixels(), 0.55) : networkLineMinPixels(),
    }));
    return layers;
  }

  setLineWidth(lineWidth) {
    const nextLineWidth = Number(lineWidth);
    if (!Number.isFinite(nextLineWidth)) return;
    if (Math.abs(nextLineWidth - this.lineWidth) < 0.001) return;
    this.lineWidth = nextLineWidth;
    this.flowWidthCache = null;
    this.queueDeckUpdate();
  }

  setFlowControl(flowControl) {
    const nextFlowControl = !!flowControl;
    if (nextFlowControl === this.flowControl) return;
    this.flowControl = nextFlowControl;
    this.flowWidthCache = null;
    this.queueDeckUpdate();
  }

  setColor(color) {
    const nextColor = colorToCss(color ?? this.color);
    if (nextColor === this.color) return;
    this.color = nextColor;
    this.queueDeckUpdate();
  }

  setOpacity(opacity) {
    const nextOpacity = Math.max(0, Math.min(1, Number(opacity)));
    if (!Number.isFinite(nextOpacity)) return;
    if (Math.abs(nextOpacity - this.opacity) < 0.001) return;
    this.opacity = nextOpacity;
    this.flowWidthCache = null;
    this.queueDeckUpdate();
  }

  setFlowStyleStops(stops) {
    if (!Array.isArray(stops) || !stops.length) return;
    // 引用相等短路：调用方（index.vue 的同步 burst）常以同一 computed 缓存引用反复调用，
    // 无短路会导致断面层每轮丢 flowWidthCache 全量重绘
    if (stops === this.flowStyleStops) return;
    this.flowStyleStops = stops;
    this.flowWidthCache = null;
    this.queueDeckUpdate();
  }

  setFlowWidthStep(flowWidthStep) {
    const nextFlowWidthStep = Number(flowWidthStep);
    if (!Number.isFinite(nextFlowWidthStep)) return;
    if (Math.abs(nextFlowWidthStep - this.flowWidthStep) < 0.001) return;
    this.flowWidthStep = nextFlowWidthStep;
    this.flowWidthCache = null;
    this.queueDeckUpdate();
  }

  hide() {
    super.hide();
    if (this.tileMode) {
      this.tileLoadToken++;
      if (this.tileUpdateTimer) {
        clearTimeout(this.tileUpdateTimer);
        this.tileUpdateTimer = null;
      }
      this.abortTileRequests();
      this.loadingTiles.clear();
      this.combineSeq++;
      this.pendingCombine = null;
    }
    removeSharedDeckLayer(this.map, this.layerId);
  }

  show() {
    super.show();
    this.queueDeckUpdate();
    // 隐藏期间跳过了相机跟随，重新可见时立即按当前视野补载瓦片
    if (this.tileMode) {
      this.scheduleTileLoad(true);
    }
  }

  lowerOverlayCanvas() {
    if (typeof requestAnimationFrame !== "function") return;
    requestAnimationFrame(() => {
      const root = this.map?.rootDoc;
      if (!root) return;
      root.querySelectorAll(".deckgl-overlay, .deck-canvas").forEach((element) => {
        element.style.zIndex = "20";
        element.style.pointerEvents = "none";
      });
    });
  }

  dispose() {
    if (this.tileUpdateTimer) {
      clearTimeout(this.tileUpdateTimer);
      this.tileUpdateTimer = null;
    }
    if (this.renderFrame) {
      cancelAnimationFrame(this.renderFrame);
      this.renderFrame = null;
    }
    if (this.refreshFrame) {
      cancelAnimationFrame(this.refreshFrame);
      this.refreshFrame = null;
    }
    if (this.refreshTimer) {
      clearTimeout(this.refreshTimer);
      this.refreshTimer = null;
    }
    this.tileLoadToken++;
    this.abortTileRequests();
    this.tileCache.clear();
    this.loadingTiles.clear();
    this.flowWidthCache = null;
    this.lineDataCache = null;
    this.pathGroupsCache = null;
    this.combineSeq++;
    this.pendingCombine = null;
    // 通知 worker 释放本命名空间的瓦片/裁剪状态；引用计数归零时整体 terminate
    if (this.workerAlive()) {
      const id = ++sharedNetworkWorker.nextRequestId;
      try {
        this.workerAttachedTo.postMessage({ id, type: "dispose", ns: this.workerNs });
      } catch (error) {
        void error;
      }
    }
    this.workerAttachedTo = null;
    releaseSharedNetworkWorker(this);
    removeSharedDeckLayer(this.map, this.layerId);
    super.dispose();
  }
}
