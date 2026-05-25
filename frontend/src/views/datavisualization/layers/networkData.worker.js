const EARTH_RADIUS = 6378137.0;
const BINARY_MAGIC = "GJNB";
const BINARY_VERSION = 1;
const BINARY_HEADER_BYTES = 64;
const BINARY_LAYOUT_COLUMNAR = 1;
const EMPTY_FLOAT32 = new Float32Array(0);
const EMPTY_UINT32 = new Uint32Array(0);
const EMPTY_FLOAT64 = new Float64Array(0);

let generation = 0;
const tileCache = new Map();

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

function hashKey(hash, hash2) {
  return `${hash >>> 0}:${hash2 >>> 0}`;
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

function combineTiles(keys = [], version = 0) {
  const tiles = keys
    .map((key) => tileCache.get(key))
    .filter((tile) => tile?.binary && tile.count > 0);
  if (!tiles.length) return emptyRenderableData(version);

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
  if (!total) return emptyRenderableData(version);

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
    version,
  }, version);
}

function assertGeneration(message) {
  if (message.generation !== generation) {
    throw new Error("stale worker generation");
  }
}

function transferablesForData(data) {
  return [
    data.hash?.buffer,
    data.hash2?.buffer,
    data.source?.buffer,
    data.target?.buffer,
    data.flow?.buffer,
    data.length?.buffer,
    data.lanes?.buffer,
  ].filter(Boolean);
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
    if (type === "reset") {
      generation = Number(message.generation) || 0;
      tileCache.clear();
      respond(id, { generation });
      return;
    }

    assertGeneration(message);

    if (type === "setTileBinary") {
      const tile = parseBinaryTileBuffer(message.buffer, message.version);
      tileCache.set(message.key, tile);
      respond(id, { key: message.key, count: tile.count, version: tile.version });
      return;
    }

    if (type === "setTileJson") {
      const tile = linksToTileData(message.links, message.version);
      tileCache.set(message.key, tile);
      respond(id, { key: message.key, count: tile.count, version: tile.version });
      return;
    }

    if (type === "combine") {
      const data = combineTiles(message.keys, message.version);
      respond(id, data, transferablesForData(data));
      return;
    }

    if (type === "setLinks") {
      const data = linksToRenderableData(message.links, message.version);
      respond(id, data, transferablesForData(data));
      return;
    }

    if (type === "dropTiles") {
      for (const key of message.keys || []) {
        tileCache.delete(key);
      }
      respond(id, { dropped: message.keys?.length || 0 });
      return;
    }

    reject(id, new Error(`unknown worker message: ${type}`));
  } catch (error) {
    reject(id, error);
  }
};
