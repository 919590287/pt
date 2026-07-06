import { COORDINATE_SYSTEM } from "@deck.gl/core";
import { LineLayer, PathLayer } from "@deck.gl/layers";
import { Layer, MAP_EVENT, webMercatorToLngLat } from "@/mymap/index.js";
import { getTileNetwork, getTileNetworkBinary, getFullNetworkBinary } from "@/api/network.js";
import { clipSegmentToDistrictContext } from "@/utils/adminDistrictRange.js";
import { colorToCss, lineWidthToPixels } from "./maplibreLayerUtils.js";
import { setSharedDeckLayer, removeSharedDeckLayer } from "./deckOverlayRegistry.js";

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

function hashKey(hash, hash2) {
  return `${hash >>> 0}:${hash2 >>> 0}`;
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

function combineBinaryTiles(keys, tileCache, version = 0) {
  const tiles = keys
    .map((key) => tileCache.get(key))
    .filter((tile) => tile?.binary && tile.count > 0);
  if (!tiles.length) return emptyBinaryData(version);

  const seen = new Set();
  let total = 0;
  for (const tile of tiles) {
    for (let i = 0; i < tile.count; i++) {
      const key = hashKey(tile.hash[i], tile.hash2[i]);
      if (seen.has(key)) continue;
      seen.add(key);
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
      const key = hashKey(tile.hash[i], tile.hash2[i]);
      if (seen.has(key)) continue;
      seen.add(key);
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

function clipRenderableBinaryData(data, context, version = data?.version || 0) {
  if (!context || !data?.count) return data || emptyBinaryData(version);
  const sourceValues = [];
  const targetValues = [];
  const hashValues = [];
  const hash2Values = [];
  const flowValues = [];
  const lengthValues = [];
  const laneValues = [];

  for (let i = 0; i < data.count; i += 1) {
    const source = [data.source[i * 2], data.source[i * 2 + 1]];
    const target = [data.target[i * 2], data.target[i * 2 + 1]];
    const clippedSegments = clipSegmentToDistrictContext(source, target, context);
    clippedSegments.forEach(([from, to], segmentIndex) => {
      const [hashA, hashB] = hashString(`${data.hash?.[i] || 0}:${data.hash2?.[i] || 0}:${segmentIndex}:${from.join(",")}:${to.join(",")}`);
      hashValues.push(hashA);
      hash2Values.push(hashB);
      sourceValues.push(from[0], from[1]);
      targetValues.push(to[0], to[1]);
      flowValues.push(Number(data.flow?.[i]) || 0);
      lengthValues.push(Number(data.length?.[i]) || 0);
      laneValues.push(Number(data.lanes?.[i]) || 1);
    });
  }

  const count = hashValues.length;
  if (!count) return emptyBinaryData(version);
  return attachStats({
    binary: true,
    count,
    origin: [0, 0],
    hash: Uint32Array.from(hashValues),
    hash2: Uint32Array.from(hash2Values),
    source: Float64Array.from(sourceValues),
    target: Float64Array.from(targetValues),
    flow: Float32Array.from(flowValues),
    length: Float32Array.from(lengthValues),
    lanes: Float32Array.from(laneValues),
  }, version);
}

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
    this.tileCache = new Map();
    this.loadingTiles = new Set();
    this.visibleTileKeys = [];
    this.displayTileKeys = [];
    this.tileLastSeen = new Map();
    this.tileLoadToken = 0;
    this.tileUpdateTimer = null;
    this.renderFrame = null;
    this.refreshFrame = null;
    this.dataVersion = 0;
    this.deckData = emptyBinaryData(this.dataVersion);
    this.flowWidthCache = null;
    this.combinedCacheKey = "";
    this.detailKey = "";
    this.worker = null;
    this.workerRequestId = 0;
    this.workerCallbacks = new Map();
    this.workerGeneration = 0;
    this.combineSeq = 0;
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
    if (!this.workerEnabled) return null;
    if (this.worker || this.worker === false) return this.worker || null;
    try {
      this.worker = createNetworkDataWorker();
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
      this.resetWorkerCache();
      return this.worker;
    } catch (error) {
      console.warn(`[${this.name}] worker init failed`, error);
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
        worker.postMessage({ id, type, generation: this.workerGeneration, ...payload }, transfer);
      } catch (error) {
        this.workerCallbacks.delete(id);
        reject(error);
      }
    });
  }

  resetWorkerCache() {
    this.workerGeneration++;
    if (!this.worker || this.worker === false) return;
    const id = ++this.workerRequestId;
    this.worker.postMessage({ id, type: "reset", generation: this.workerGeneration });
  }

  dropWorkerTiles(keys) {
    if (!keys?.length || !this.worker || this.worker === false) return;
    this.postWorker("dropTiles", { keys }).catch(() => {});
  }

  setData(data) {
    this.tileMode = false;
    this.tileLoadToken++;
    this.resetWorkerCache();
    this.dataVersion++;
    const version = this.dataVersion;
    const links = Array.isArray(data) ? data : [];
    this.rawLinks = links;
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
    this.resetWorkerCache();
    this.tileCache.clear();
    this.loadingTiles.clear();
    this.visibleTileKeys = [];
    this.displayTileKeys = [];
    this.tileLastSeen.clear();
    this.dataVersion++;
    this.deckData = emptyBinaryData(this.dataVersion);
    this.flowWidthCache = null;
    this.combinedCacheKey = "";
    this.detailKey = "";
    this.queueDeckUpdate();
    this.scheduleTileLoad(true);
  }

  on(type, data) {
    super.on(type, data);
    if (!this.tileMode) return;
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

  scheduleTileLoad(immediate = false) {
    if (!this.map || !this.datasource) return;
    if (this.tileUpdateTimer) {
      clearTimeout(this.tileUpdateTimer);
    }
    const detail = this.currentTileDetail();
    const delay = TILE_SCHEDULE_DELAY[detail.level] ?? 160;
    this.tileUpdateTimer = setTimeout(() => {
      this.tileUpdateTimer = null;
      this.loadVisibleTiles();
    }, immediate ? 0 : delay);
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
    if (!this.tileMode || !this.datasource || !this.map) return;
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
      const request = tile.full ? this.fullRequest : this.tileRequest;
      let res;
      let parsed;
      try {
        res = await request(params);
      } catch (binaryError) {
        if (tile.full || !this.jsonFallbackRequest || request === this.jsonFallbackRequest) throw binaryError;
        res = await this.jsonFallbackRequest(params);
      }
      if (this.isDisposed || token !== this.tileLoadToken) return;
      try {
        parsed = await this.parseTileResponseAsync(res, key, ++this.dataVersion);
      } catch (parseError) {
        if (tile.full || !this.jsonFallbackRequest || request === this.jsonFallbackRequest) throw parseError;
        const fallbackRes = await this.jsonFallbackRequest(params);
        if (this.isDisposed || token !== this.tileLoadToken) return;
        parsed = await this.parseTileResponseAsync(fallbackRes, key, ++this.dataVersion);
      }
      if (!this.isDisposed && token === this.tileLoadToken) {
        this.tileCache.set(key, parsed);
        this.scheduleRefreshVisibleTileData();
      }
    } catch (error) {
      console.warn(`[${this.name}] tile load failed`, tile, error);
      if (!this.isDisposed && token === this.tileLoadToken) {
        this.tileCache.set(key, emptyBinaryData(++this.dataVersion));
      }
    } finally {
      this.loadingTiles.delete(key);
    }
  }

  refreshVisibleTileData() {
    if (!this.tileMode) return;
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

  async combineVisibleTileData(displayKeys, version, token) {
    const seq = ++this.combineSeq;
    if (!this.ensureWorker()) {
      this.deckData = this.applyLineClipContext(combineBinaryTiles(displayKeys, this.tileCache, version), version);
      this.queueDeckUpdate();
      return;
    }
    try {
      const deckData = await this.postWorker("combine", { keys: displayKeys, version });
      if (this.isDisposed || token !== this.tileLoadToken || seq !== this.combineSeq) return;
      this.deckData = this.applyLineClipContext(deckData, version);
      this.queueDeckUpdate();
    } catch (error) {
      if (this.isDisposed || token !== this.tileLoadToken || seq !== this.combineSeq) return;
      console.warn(`[${this.name}] worker tile combine failed`, error);
      this.deckData = this.applyLineClipContext(combineBinaryTiles(displayKeys, this.tileCache, version), version);
      this.queueDeckUpdate();
    }
  }

  applyLineClipContext(data, version = data?.version || 0) {
    return clipRenderableBinaryData(data, this.lineClipContext, version);
  }

  setLineClipContext(context = null) {
    const nextContext = context || null;
    if (this.lineClipContext === nextContext) return;
    this.lineClipContext = nextContext;
    this.flowWidthCache = null;
    this.combinedCacheKey = "";
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
    if (this.refreshFrame || typeof requestAnimationFrame !== "function") {
      if (typeof requestAnimationFrame !== "function") {
        this.refreshVisibleTileData();
      }
      return;
    }
    this.refreshFrame = requestAnimationFrame(() => {
      this.refreshFrame = null;
      this.refreshVisibleTileData();
    });
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
    const attributes = {
      getSourcePosition: { value: data.source, size: 2 },
      getTargetPosition: { value: data.target, size: 2 },
    };
    const baseWidth = lineWidthToPixels(this.lineWidth);
    const zoomWidth = this.currentLineWidthPixels();
    const visualOpacity = this.currentLineOpacity();
    let getWidth = zoomWidth;
    let widthScale = 1;

    if (this.flowControl) {
      const flowStyle = this.flowStyleAttributes(data, visualOpacity);
      attributes.getWidth = { value: flowStyle.widths, size: 1 };
      attributes.getColor = { value: flowStyle.colors, size: 4 };
      getWidth = 1;
      widthScale = baseWidth > 0 ? zoomWidth / baseWidth : 1;
    }

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
        data: {
          length: data.count,
          attributes,
        },
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
      data: {
        length: data.count,
        attributes,
      },
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

  buildContinuousPathData(data, lineColor, zoomWidth, opacity) {
    const links = Array.isArray(this.rawLinks) ? this.rawLinks : [];
    if (links.length < 2) return [];

    const baseWidth = Math.max(3, lineWidthToPixels(this.lineWidth));
    const stepWidth = Math.max(1, lineWidthToPixels(this.flowWidthStep || this.flowMaxWidth));
    const alpha = Math.max(0, Math.min(255, Math.round((Number(opacity) || 0) * 255)));
    const tolerance = routePathJoinToleranceMeters();
    const toleranceSq = tolerance * tolerance;
    const paths = [];
    let current = null;

    const flush = () => {
      if (current?.points?.length > 1) {
        paths.push({
          path: current.points.map((point) => webMercatorToLngLat(point[0], point[1])),
          color: current.color,
          width: current.width,
        });
      }
      current = null;
    };

    const styleForLink = (link) => {
      if (!this.flowControl) {
        return {
          key: "default",
          color: lineColor,
          width: zoomWidth,
        };
      }
      const flow = linkFlowValue(link);
      let ratio = 0;
      if (data.maxFlow > data.minFlow) {
        ratio = clamp01((Math.max(data.minFlow, flow) - data.minFlow) / (data.maxFlow - data.minFlow));
      } else {
        ratio = data.maxFlow > 0 ? 1 : 0;
      }
      const style = this.flowStyleForValue(flow, ratio);
      const color = [style.color[0], style.color[1], style.color[2], alpha];
      const width = baseWidth + stepWidth * style.widthStep;
      return {
        key: `${style.color.join(",")}:${style.widthStep}`,
        color,
        width,
      };
    };

    for (const link of links) {
      const endpoints = linkWebMercatorEndpoints(link);
      if (!endpoints) continue;

      const style = styleForLink(link);
      let start = endpoints.source;
      let end = endpoints.target;

      if (!current || current.key !== style.key) {
        flush();
        current = { key: style.key, color: style.color, width: style.width, points: [start, end] };
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
        current = { key: style.key, color: style.color, width: style.width, points: [start, end] };
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
    return paths;
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
    removeSharedDeckLayer(this.map, this.layerId);
  }

  show() {
    super.show();
    this.queueDeckUpdate();
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
    this.tileLoadToken++;
    this.tileCache.clear();
    this.loadingTiles.clear();
    this.flowWidthCache = null;
    this.combineSeq++;
    if (this.worker && this.worker !== false) {
      this.worker.terminate();
    }
    this.worker = null;
    this.workerCallbacks.clear();
    removeSharedDeckLayer(this.map, this.layerId);
    super.dispose();
  }
}
