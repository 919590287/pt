/**
 * 仿真模型大二进制工件的 IndexedDB 持久缓存。
 *
 * 只保存有明确版本的 ArrayBuffer；模型、数据类型或版本任一缺失时
 * 直接退化为网络 + 内存缓存。持久层的任何失败都不得影响功能请求。
 *
 * payload 和小元数据分库表保存，LRU 清理时不会把数十/数百 MB 的
 * ArrayBuffer 重新反序列化到主线程。
 */

const DB_NAME = "bus-platform-model-binaries";
const DB_VERSION = 2;
const PAYLOAD_STORE = "payloads";
const META_STORE = "metadata";
const CACHE_SCHEMA = "model-binary-v2-compressed";

const DEVICE_MEMORY_GB = Math.max(2, Math.min(8, Number(globalThis.navigator?.deviceMemory) || 4));
export const MAX_PERSISTED_BINARY_BYTES = Math.round(
  Math.max(256, Math.min(512, DEVICE_MEMORY_GB * 96)) * 1024 * 1024,
);
export const MAX_PERSISTED_BINARY_ENTRIES = 24;

let lastStamp = 0;
function nextStamp() {
  lastStamp = Math.max(Date.now(), lastStamp + 1);
  return lastStamp;
}

export function modelBinaryKey(model, type, version) {
  return JSON.stringify([CACHE_SCHEMA, String(model || ""), String(type || ""), String(version || "")]);
}

let backend = null;
let backendResolved = false;
let writeQueue = Promise.resolve();

/** 测试可注入内存适配器，生产不调用。 */
export function configureModelBinaryStoreBackend(next) {
  backend = next || null;
  backendResolved = true;
}

export function resetModelBinaryStoreBackend() {
  backend = null;
  backendResolved = false;
}

function openDatabase() {
  return new Promise((resolve) => {
    let request;
    try {
      request = globalThis.indexedDB.open(DB_NAME, DB_VERSION);
    } catch {
      resolve(null);
      return;
    }
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(PAYLOAD_STORE)) {
        db.createObjectStore(PAYLOAD_STORE, { keyPath: "key" });
      }
      if (!db.objectStoreNames.contains(META_STORE)) {
        const meta = db.createObjectStore(META_STORE, { keyPath: "key" });
        meta.createIndex("model", "model", { unique: false });
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => resolve(null);
    request.onblocked = () => resolve(null);
  });
}

function runTransaction(mode, work) {
  return openDatabase().then((db) => {
    if (!db) return null;
    return new Promise((resolve) => {
      let result = null;
      let transaction;
      try {
        transaction = db.transaction([PAYLOAD_STORE, META_STORE], mode);
        work(
          transaction.objectStore(PAYLOAD_STORE),
          transaction.objectStore(META_STORE),
          (value) => { result = value; },
        );
      } catch {
        db.close();
        resolve(null);
        return;
      }
      transaction.oncomplete = () => { db.close(); resolve(result); };
      transaction.onerror = () => { db.close(); resolve(null); };
      transaction.onabort = () => { db.close(); resolve(null); };
    });
  });
}

function indexedDbBackend() {
  return {
    get(key) {
      return runTransaction("readonly", (payloads, _meta, done) => {
        const request = payloads.get(key);
        request.onsuccess = () => done(request.result || null);
      });
    },
    put(record) {
      return runTransaction("readwrite", (payloads, meta, done) => {
        payloads.put({
          key: record.key,
          buffer: record.buffer,
          compression: record.compression || "identity",
          rawBytes: record.rawBytes || record.buffer?.byteLength || 0,
        });
        const { buffer: _buffer, ...metadata } = record;
        meta.put(metadata);
        done(true);
      });
    },
    touch(key, usedAt) {
      return runTransaction("readwrite", (_payloads, meta, done) => {
        const request = meta.get(key);
        request.onsuccess = () => {
          if (request.result) meta.put({ ...request.result, usedAt });
          done(true);
        };
      });
    },
    remove(keys) {
      return runTransaction("readwrite", (payloads, meta, done) => {
        keys.forEach((key) => {
          payloads.delete(key);
          meta.delete(key);
        });
        done(true);
      });
    },
    listMeta() {
      return runTransaction("readonly", (_payloads, meta, done) => {
        const rows = [];
        const request = meta.openCursor();
        request.onsuccess = () => {
          const cursor = request.result;
          if (!cursor) {
            done(rows);
            return;
          }
          rows.push(cursor.value || {});
          cursor.continue();
        };
      }).then((rows) => rows || []);
    },
  };
}

function activeBackend() {
  if (!backendResolved) {
    backend = globalThis.indexedDB ? indexedDbBackend() : null;
    backendResolved = true;
  }
  return backend;
}

function validIdentity(model, type, version) {
  return Boolean(String(model || "") && String(type || "") && String(version || ""));
}

/** 按 usedAt 从旧到新选出超出条数/字节预算的记录。 */
export function selectModelBinaryVictims(rows = [], limits = {}) {
  const maxBytes = Math.max(1, Number(limits.maxBytes) || MAX_PERSISTED_BINARY_BYTES);
  const maxEntries = Math.max(1, Number(limits.maxEntries) || MAX_PERSISTED_BINARY_ENTRIES);
  const unique = new Map();
  for (const row of rows) {
    if (row?.key) unique.set(row.key, row);
  }
  const ordered = [...unique.values()].sort((left, right) => (
    (Number(left?.usedAt) || 0) - (Number(right?.usedAt) || 0)
  ));
  let bytes = ordered.reduce((sum, row) => sum + Math.max(0, Number(row?.bytes) || 0), 0);
  let entries = ordered.length;
  const victims = [];
  for (const row of ordered) {
    if (entries <= maxEntries && bytes <= maxBytes) break;
    victims.push(row.key);
    entries -= 1;
    bytes -= Math.max(0, Number(row?.bytes) || 0);
  }
  return victims;
}

/** 命中返回 ArrayBuffer，未命中/损坏/不可用均返回 null。 */
export async function readModelBinary(model, type, version) {
  const store = activeBackend();
  if (!store || !validIdentity(model, type, version)) return null;
  const key = modelBinaryKey(model, type, version);
  try {
    const stored = await store.get(key);
    const record = stored instanceof ArrayBuffer
      ? { buffer: stored, compression: "identity" }
      : stored;
    if (!(record?.buffer instanceof ArrayBuffer) || record.buffer.byteLength === 0) return null;
    const buffer = await decodeStoredBuffer(record);
    if (!(buffer instanceof ArrayBuffer) || buffer.byteLength === 0) return null;
    void Promise.resolve(store.touch?.(key, nextStamp())).catch(() => null);
    return buffer;
  } catch {
    return null;
  }
}

/**
 * 写入前先删除同模型+同类型的旧版本，再按全局 LRU 约束 256–512MB。
 * 持久化是补偿性优化，任何失败只返回 false，不向功能层抛错。
 */
export async function writeModelBinary(model, type, version, buffer, limits = {}) {
  const store = activeBackend();
  if (!store || !validIdentity(model, type, version)
      || !(buffer instanceof ArrayBuffer) || buffer.byteLength === 0) return false;
  const key = modelBinaryKey(model, type, version);
  const usedAt = nextStamp();
  const encoded = await encodeStoredBuffer(buffer);
  const candidate = {
    key,
    model: String(model),
    type: String(type),
    version: String(version),
    bytes: encoded.buffer.byteLength,
    rawBytes: buffer.byteLength,
    compression: encoded.compression,
    usedAt,
  };
  // 串行执行“读元数据→清理→写入”，避免多个 idle 任务同时命中时突破容量上限。
  const previousWrite = writeQueue;
  let releaseWrite;
  writeQueue = new Promise((resolve) => { releaseWrite = resolve; });
  await previousWrite.catch(() => null);
  try {
    const listed = await store.listMeta();
    const rows = Array.isArray(listed) ? listed : [];
    const stale = rows.filter((row) => (
      row?.model === candidate.model && row?.type === candidate.type && row?.key !== key
    ));
    const candidates = [
      ...rows.filter((row) => !stale.includes(row) && row?.key !== key),
      candidate,
    ];
    const victims = selectModelBinaryVictims(candidates, limits);
    const removable = new Set([
      ...stale.map((row) => row.key),
      ...victims.filter((victim) => victim !== key),
    ]);
    if (removable.size) await store.remove([...removable]);
    if (victims.includes(key)) return false;
    return await store.put({ ...candidate, buffer: encoded.buffer }) !== false;
  } catch {
    return false;
  } finally {
    releaseWrite();
  }
}

/** 预热只需确认压缩工件存在，不解压成主线程 ArrayBuffer。 */
export async function hasModelBinary(model, type, version) {
  const store = activeBackend();
  if (!store || !validIdentity(model, type, version)) return false;
  const key = modelBinaryKey(model, type, version);
  try {
    const rows = await store.listMeta();
    const found = (Array.isArray(rows) ? rows : []).some((row) => row?.key === key);
    if (found) void Promise.resolve(store.touch?.(key, nextStamp())).catch(() => null);
    return found;
  } catch {
    return false;
  }
}

async function encodeStoredBuffer(buffer) {
  if (typeof globalThis.CompressionStream !== "function") {
    return { buffer, compression: "identity" };
  }
  try {
    const stream = new Blob([buffer]).stream().pipeThrough(new CompressionStream("gzip"));
    const compressed = await new Response(stream).arrayBuffer();
    return compressed.byteLength < buffer.byteLength
      ? { buffer: compressed, compression: "gzip" }
      : { buffer, compression: "identity" };
  } catch {
    return { buffer, compression: "identity" };
  }
}

async function decodeStoredBuffer(record) {
  if (record.compression !== "gzip") return record.buffer;
  if (typeof globalThis.DecompressionStream !== "function") return null;
  try {
    const stream = new Blob([record.buffer]).stream().pipeThrough(new DecompressionStream("gzip"));
    const decoded = await new Response(stream).arrayBuffer();
    if (record.rawBytes && decoded.byteLength !== Number(record.rawBytes)) return null;
    return decoded;
  } catch {
    return null;
  }
}

export async function clearPersistedModelBinaries(model = "", type = "") {
  const store = activeBackend();
  if (!store) return;
  try {
    const rows = await store.listMeta();
    const keys = (Array.isArray(rows) ? rows : [])
      .filter((row) => (!model || row?.model === model) && (!type || row?.type === type))
      .map((row) => row?.key)
      .filter(Boolean);
    if (keys.length) await store.remove(keys);
  } catch {
    // 可重建缓存清理失败不影响业务。
  }
}
