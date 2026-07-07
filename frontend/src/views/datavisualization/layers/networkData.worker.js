// 路网二进制数据 Worker（模块级共享，一个 Worker 服务全部 NetworkLayer 实例）。
// 每个图层实例以 ns（命名空间）隔离：tileCache / generation / 裁剪上下文互不干扰。
// 行政区裁剪（原主线程 P1 瓶颈）下沉至此：combine 后按 ns 的 clipContext + 网格索引顺带裁剪，
// 结果按 (combinedCacheKey, contextKey) 记忆化。
import { buildDistrictClipIndex, clipRenderableBinaryData } from "./districtClipIndex.js";

const EARTH_RADIUS = 6378137.0;
const BINARY_MAGIC = "GJNB";
const BINARY_VERSION = 1;
const BINARY_HEADER_BYTES = 64;
const BINARY_LAYOUT_COLUMNAR = 1;
const CLIP_MEMO_LIMIT = 4;
const EMPTY_FLOAT32 = new Float32Array(0);
const EMPTY_UINT32 = new Uint32Array(0);
const EMPTY_FLOAT64 = new Float64Array(0);

// ns -> { generation, tiles: Map, clip: null | { key, context, index }, clipMemo: Map }
const states = new Map();

function ensureNsState(ns) {
  const key = String(ns ?? "");
  let state = states.get(key);
  if (!state) {
    state = { generation: 0, tiles: new Map(), clip: null, clipMemo: new Map() };
    states.set(key, state);
  }
  return state;
}

function webMercatorToLngLat(x, y) {
  const lng = (Number(x) / EARTH_RADIUS) * (180 / Math.PI);
  const lat = (2 * Math.atan(Math.exp(Number(y) / EARTH_RADIUS)) - Math.PI / 2) * (180 / Math.PI);
  return [lng, lat];
}

function emptyRenderableData(version = 0) {
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

function parseBinaryTileBuffer(arrayBuffer, version = 0) {
  if (!(arrayBuffer instanceof ArrayBuffer) || arrayBuffer.byteLength < BINARY_HEADER_BYTES) {
    throw new Error("Invalid binary tile response");
  }
  const view = new DataView(arrayBuffer);
  const magic = String.fromCharCode(view.getUint8(0), view.getUint8(1), view.getUint8(2), view.getUint8(3));
  const binaryVersion = view.getUint16(4, true);
  const headerBytes = view.getUint16(6, true);
  const count = view.getUint32(8, true);
  const layout = view.getUint32(12, true);
  if (magic !== BINARY_MAGIC || binaryVersion !== BINARY_VERSION || layout !== BINARY_LAYOUT_COLUMNAR) {
    throw new Error("Invalid binary tile response");
  }
  if (headerBytes < BINARY_HEADER_BYTES) {
    throw new Error("Invalid binary tile header");
  }

  const origin = [view.getFloat64(16, true), view.getFloat64(24, true)];
  const hashOffset = view.getUint32(32, true);
  const hash2Offset = view.getUint32(36, true);
  const sourceOffset = view.getUint32(40, true);
  const targetOffset = view.getUint32(44, true);
  const flowOffset = view.getUint32(48, true);
  const lengthOffset = view.getUint32(52, true);
  const lanesOffset = view.getUint32(56, true);

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
    version,
  }, version);
}

function linksToTileData(links = [], version = 0) {
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
  if (!count) {
    return {
      ...emptyRenderableData(version),
      source: EMPTY_FLOAT32,
      target: EMPTY_FLOAT32,
    };
  }

  const origin = [validLinks[0].fromX, validLinks[0].fromY];
  const hash = new Uint32Array(count);
  const hash2 = new Uint32Array(count);
  const source = new Float32Array(count * 2);
  const target = new Float32Array(count * 2);
  const flow = new Float32Array(count);
  const length = new Float32Array(count);
  const lanes = new Float32Array(count);

  for (let i = 0; i < count; i++) {
    const item = validLinks[i];
    const id = item.link?.linkId || `${item.fromX},${item.fromY},${item.toX},${item.toY}`;
    const [hashA, hashB] = hashString(id);
    hash[i] = hashA;
    hash2[i] = hashB;
    source[i * 2] = item.fromX - origin[0];
    source[i * 2 + 1] = item.fromY - origin[1];
    target[i * 2] = item.toX - origin[0];
    target[i * 2 + 1] = item.toY - origin[1];
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
    version,
  }, version);
}

function linksToRenderableData(links = [], version = 0) {
  const tile = linksToTileData(links, version);
  return tileDataToRenderable(tile, version);
}

function tileDataToRenderable(tile, version = tile.version || 0) {
  if (!tile?.count) return emptyRenderableData(version);

  const source = new Float64Array(tile.count * 2);
  const target = new Float64Array(tile.count * 2);
  for (let i = 0; i < tile.count; i++) {
    const sourceLngLat = webMercatorToLngLat(
      tile.origin[0] + tile.source[i * 2],
      tile.origin[1] + tile.source[i * 2 + 1],
    );
    const targetLngLat = webMercatorToLngLat(
      tile.origin[0] + tile.target[i * 2],
      tile.origin[1] + tile.target[i * 2 + 1],
    );
    source[i * 2] = sourceLngLat[0];
    source[i * 2 + 1] = sourceLngLat[1];
    target[i * 2] = targetLngLat[0];
    target[i * 2 + 1] = targetLngLat[1];
  }

  return attachStats({
    binary: true,
    count: tile.count,
    origin: [0, 0],
    hash: new Uint32Array(tile.hash),
    hash2: new Uint32Array(tile.hash2),
    source,
    target,
    flow: new Float32Array(tile.flow),
    length: new Float32Array(tile.length),
    lanes: new Float32Array(tile.lanes),
    version,
  }, version);
}

// (hash,hash2) 双 32 位键去重：嵌套 Map/Set 避免 10 万级 link 每趟合并产生 2×N 个临时字符串
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

function combineTiles(state, keys = [], version = 0, options = {}) {
  // 粗档位合并选项（与 NetworkLayer.COARSE_DETAIL_COMBINE_OPTS 对应）：
  // precision:"f32" —— 粗档位 >36m/px 下 f32 经度精度 ≈1.3m 远小于 1 像素，免 fp64 拆分、传输显存减半；
  // cullLengthMeters —— 剔除亚像素短链，全市路网 district 档近百万段时是压回帧预算的关键
  const useF32 = options.precision === "f32";
  const cullLength = Math.max(0, Number(options.cullLengthMeters) || 0);
  const cullLengthSq = cullLength * cullLength;
  const tiles = keys
    .map((key) => state.tiles.get(key))
    .filter((tile) => tile?.binary && tile.count > 0);
  if (!tiles.length) return emptyRenderableData(version);

  // 计数趟与写入趟必须用同一过滤谓词，保证 (hash,hash2) 去重序列一致
  const keepLink = (tile, i) => {
    if (!cullLength) return true;
    const linkLength = tile.length[i] || 0;
    if (linkLength > 0) return linkLength >= cullLength;
    // 无长度属性时退化为端点距离（web mercator 单位近似米）
    const dx = tile.target[i * 2] - tile.source[i * 2];
    const dy = tile.target[i * 2 + 1] - tile.source[i * 2 + 1];
    return dx * dx + dy * dy >= cullLengthSq;
  };

  const seen = makePairSeen();
  let total = 0;
  for (const tile of tiles) {
    for (let i = 0; i < tile.count; i++) {
      if (!keepLink(tile, i)) continue;
      if (!seen.addIfAbsent(tile.hash[i], tile.hash2[i])) continue;
      total++;
    }
  }
  if (!total) return emptyRenderableData(version);

  const PositionArray = useF32 ? Float32Array : Float64Array;
  const hash = new Uint32Array(total);
  const hash2 = new Uint32Array(total);
  const source = new PositionArray(total * 2);
  const target = new PositionArray(total * 2);
  const flow = new Float32Array(total);
  const length = new Float32Array(total);
  const lanes = new Float32Array(total);

  seen.clear();
  let writeIndex = 0;
  for (const tile of tiles) {
    for (let i = 0; i < tile.count; i++) {
      if (!keepLink(tile, i)) continue;
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
    version,
  }, version);
}

// 应用 ns 的裁剪上下文；索引懒建，contextKey 变化时由 setClipContext 重置
function applyClip(state, data, version) {
  if (!state.clip || !data?.count) return data;
  if (!state.clip.index) {
    state.clip.index = buildDistrictClipIndex(state.clip.context);
  }
  return clipRenderableBinaryData(data, state.clip.index, version);
}

// 记忆化结果必须存克隆：respond 会 transfer（detach）发出的 buffer
function cloneRenderable(data, version = data.version || 0) {
  return {
    binary: true,
    count: data.count,
    origin: [Number(data.origin?.[0]) || 0, Number(data.origin?.[1]) || 0],
    hash: data.hash.slice(),
    hash2: data.hash2.slice(),
    source: data.source.slice(),
    target: data.target.slice(),
    flow: data.flow.slice(),
    length: data.length.slice(),
    lanes: data.lanes.slice(),
    minFlow: data.minFlow,
    maxFlow: data.maxFlow,
    version,
  };
}

function trimMemo(map, limit) {
  while (map.size > limit) {
    map.delete(map.keys().next().value);
  }
}

function assertGeneration(state, message) {
  if (message.generation !== state.generation) {
    throw new Error("stale worker generation");
  }
}

// transfer 列表必须去重（同一 buffer 重复出现直接抛 DataCloneError），
// 且过滤零长 buffer：模块级 EMPTY_* 常量的 buffer 一旦 transfer 会被永久 detach
function transferablesForData(data) {
  const buffers = [
    data.hash?.buffer,
    data.hash2?.buffer,
    data.source?.buffer,
    data.target?.buffer,
    data.flow?.buffer,
    data.length?.buffer,
    data.lanes?.buffer,
  ].filter((buffer) => buffer instanceof ArrayBuffer && buffer.byteLength > 0);
  return [...new Set(buffers)];
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
    const state = ensureNsState(message.ns);

    if (type === "reset") {
      state.generation = Number(message.generation) || 0;
      state.tiles.clear();
      state.clipMemo.clear();
      respond(id, { generation: state.generation });
      return;
    }

    if (type === "setClipContext") {
      // 与 generation 正交：裁剪上下文跨 setTileSource/reset 存续，不做断言
      const context = message.context || null;
      state.clip = context
        ? { key: String(message.contextKey ?? ""), context, index: null }
        : null;
      state.clipMemo.clear();
      respond(id, { contextKey: state.clip?.key ?? null });
      return;
    }

    if (type === "dispose") {
      states.delete(String(message.ns ?? ""));
      respond(id, { disposed: true });
      return;
    }

    assertGeneration(state, message);

    if (type === "setTileBinary") {
      const tile = parseBinaryTileBuffer(message.buffer, message.version);
      state.tiles.set(message.key, tile);
      respond(id, { key: message.key, count: tile.count, version: tile.version });
      return;
    }

    if (type === "setTileJson") {
      const tile = linksToTileData(message.links, message.version);
      state.tiles.set(message.key, tile);
      respond(id, { key: message.key, count: tile.count, version: tile.version });
      return;
    }

    if (type === "combine") {
      const combineOptions = {
        precision: message.precision,
        cullLengthMeters: message.cullLengthMeters,
      };
      const memoKey = state.clip && message.cacheKey
        ? `${message.cacheKey}::${state.clip.key}::${message.precision || "f64"}:${Number(message.cullLengthMeters) || 0}`
        : "";
      if (memoKey) {
        const cached = state.clipMemo.get(memoKey);
        if (cached) {
          const clone = cloneRenderable(cached, message.version);
          respond(id, clone, transferablesForData(clone));
          return;
        }
      }
      let data = combineTiles(state, message.keys, message.version, combineOptions);
      data = applyClip(state, data, message.version);
      if (memoKey) {
        state.clipMemo.set(memoKey, cloneRenderable(data, message.version));
        trimMemo(state.clipMemo, CLIP_MEMO_LIMIT);
      }
      respond(id, data, transferablesForData(data));
      return;
    }

    if (type === "setLinks") {
      // 与原主线程语义一致：setData 路径不做行政区裁剪
      const data = linksToRenderableData(message.links, message.version);
      respond(id, data, transferablesForData(data));
      return;
    }

    if (type === "dropTiles") {
      for (const key of message.keys || []) {
        state.tiles.delete(key);
      }
      respond(id, { dropped: message.keys?.length || 0 });
      return;
    }

    reject(id, new Error(`unknown worker message: ${type}`));
  } catch (error) {
    reject(id, error);
  }
};
