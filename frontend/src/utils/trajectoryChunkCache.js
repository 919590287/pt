// 轨迹二进制分块的持久缓存（IndexedDB）。
// 命中即零网络、跨会话保留——把"缓存加载"从一次次回源变成本地直读，
// 二次打开同一模型时几乎零等待。所有错误都静默降级到网络，不影响可用性。
const DB_NAME = "gjcx-trajectory";
const STORE = "chunks";
const META_STORE = "chunk-meta";
const DB_VERSION = 2;
const MAX_CACHE_ENTRIES_PER_DATASOURCE = 96;
const DEVICE_MEMORY_GB = Math.max(2, Math.min(8, Number(globalThis.navigator?.deviceMemory) || 4));
const MAX_CACHE_BYTES_PER_DATASOURCE = Math.round(
  Math.max(128, Math.min(512, DEVICE_MEMORY_GB * 64)) * 1024 * 1024,
);
const MAX_CACHE_ENTRIES_TOTAL = 192;
const MAX_CACHE_BYTES_TOTAL = Math.round(
  Math.max(256, Math.min(1024, DEVICE_MEMORY_GB * 128)) * 1024 * 1024,
);

let dbPromise = null;

function openDB() {
  if (dbPromise) return dbPromise;
  if (typeof indexedDB === "undefined") {
    dbPromise = Promise.resolve(null);
    return dbPromise;
  }
  dbPromise = new Promise((resolve) => {
    let request;
    try {
      request = indexedDB.open(DB_NAME, DB_VERSION);
    } catch {
      resolve(null);
      return;
    }
    request.onupgradeneeded = (event) => {
      const db = request.result;
      // v1 把大 ArrayBuffer 与清理元数据放在同一条记录，cursor.value
      // 会在删旧代时逐块反序列化整个轨迹。持久层只是可重建缓存，升级时
      // 直接丢弃 v1，避免首次打开执行数 GB 迁移。
      if (event.oldVersion > 0 && event.oldVersion < 2 && db.objectStoreNames.contains(STORE)) {
        db.deleteObjectStore(STORE);
      }
      if (!db.objectStoreNames.contains(STORE)) {
        db.createObjectStore(STORE, { keyPath: "k" });
      }
      if (!db.objectStoreNames.contains(META_STORE)) {
        const meta = db.createObjectStore(META_STORE, { keyPath: "k" });
        meta.createIndex("ds", "ds", { unique: false });
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => resolve(null);
    request.onblocked = () => resolve(null);
  }).catch(() => null);
  return dbPromise;
}

function withStore(db, mode) {
  const transaction = db.transaction(STORE, mode);
  return { transaction, store: transaction.objectStore(STORE) };
}

function withStores(db, mode) {
  const transaction = db.transaction([STORE, META_STORE], mode);
  return {
    transaction,
    chunks: transaction.objectStore(STORE),
    meta: transaction.objectStore(META_STORE),
  };
}

function scheduleIdle(callback) {
  if (typeof requestIdleCallback === "function") {
    requestIdleCallback(callback, { timeout: 3000 });
  } else {
    setTimeout(callback, 250);
  }
}

// 命中返回原始字节（ArrayBuffer），未命中/异常返回 null。
export async function getCachedChunk(key) {
  const db = await openDB();
  if (!db || !key) return null;
  return new Promise((resolve) => {
    try {
      const { chunks, meta } = withStores(db, "readwrite");
      const request = chunks.get(key);
      request.onsuccess = () => {
        const record = request.result;
        if (record?.buf instanceof ArrayBuffer) {
          const metaRequest = meta.get(key);
          metaRequest.onsuccess = () => {
            if (metaRequest.result) meta.put({ ...metaRequest.result, ts: Date.now() });
          };
        }
        resolve(record && record.buf instanceof ArrayBuffer ? record.buf : null);
      };
      request.onerror = () => resolve(null);
    } catch {
      resolve(null);
    }
  });
}

// 写入原始字节；配额超限或异常时静默忽略（结构化克隆，不影响已解析的视图）。
export async function putCachedChunk(key, buffer, meta = {}) {
  const db = await openDB();
  if (!db || !key || !(buffer instanceof ArrayBuffer) || buffer.byteLength === 0) return;
  await new Promise((resolve) => {
    try {
      const { transaction, chunks, meta: metaStore } = withStores(db, "readwrite");
      const now = Date.now();
      const ds = meta.ds || "";
      const ver = meta.ver || "";
      chunks.put({ k: key, buf: buffer });
      metaStore.put({ k: key, ds, ver, bytes: buffer.byteLength, ts: now });
      transaction.oncomplete = () => {
        resolve();
        if (ds) scheduleIdle(() => enforceChunkCacheBudget(ds));
      };
      transaction.onerror = () => resolve();
      transaction.onabort = () => resolve();
    } catch {
      resolve();
    }
  });
}

// events 变化后清理该模型的旧版本分块（ver 不一致即失效），避免读到过期轨迹。
export async function pruneChunkCache(datasource, keepVer) {
  const db = await openDB();
  if (!db || !datasource) return;
  await new Promise((resolve) => {
    try {
      const { transaction, chunks, meta } = withStores(db, "readwrite");
      const index = meta.index("ds");
      const request = index.openCursor(IDBKeyRange.only(datasource));
      request.onsuccess = () => {
        const cursor = request.result;
        if (!cursor) return;
        if (cursor.value?.ver !== keepVer) {
          chunks.delete(cursor.primaryKey);
          cursor.delete();
        }
        cursor.continue();
      };
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => resolve();
      transaction.onabort = () => resolve();
    } catch {
      resolve();
    }
  });
}

// 每模型的持久缓存同时受条数和总字节上限约束。遍历的只是独立小元数据表，
// 不会为了 LRU 清理把轨迹 ArrayBuffer 读回主线程。
export async function enforceChunkCacheBudget(
  datasource,
  maxBytes = MAX_CACHE_BYTES_PER_DATASOURCE,
  maxEntries = MAX_CACHE_ENTRIES_PER_DATASOURCE,
  maxTotalBytes = MAX_CACHE_BYTES_TOTAL,
  maxTotalEntries = MAX_CACHE_ENTRIES_TOTAL,
) {
  const db = await openDB();
  if (!db) return;
  await new Promise((resolve) => {
    try {
      const { transaction, chunks, meta } = withStores(db, "readwrite");
      const rows = [];
      const request = meta.openCursor();
      request.onsuccess = () => {
        const cursor = request.result;
        if (cursor) {
          rows.push({
            k: cursor.primaryKey,
            ds: String(cursor.value?.ds || ""),
            bytes: Math.max(0, Number(cursor.value?.bytes) || 0),
            ts: Number(cursor.value?.ts) || 0,
          });
          cursor.continue();
          return;
        }
        const victims = selectChunkCacheVictims(rows, {
          maxBytes,
          maxEntries,
          maxTotalBytes,
          maxTotalEntries,
        });
        for (const key of victims) {
          chunks.delete(key);
          meta.delete(key);
        }
      };
      transaction.oncomplete = () => resolve();
      transaction.onerror = () => resolve();
      transaction.onabort = () => resolve();
    } catch {
      resolve();
    }
  });
}

export function selectChunkCacheVictims(rows = [], limits = {}) {
  const maxBytes = Math.max(1, Number(limits.maxBytes) || MAX_CACHE_BYTES_PER_DATASOURCE);
  const maxEntries = Math.max(1, Number(limits.maxEntries) || MAX_CACHE_ENTRIES_PER_DATASOURCE);
  const maxTotalBytes = Math.max(1, Number(limits.maxTotalBytes) || MAX_CACHE_BYTES_TOTAL);
  const maxTotalEntries = Math.max(1, Number(limits.maxTotalEntries) || MAX_CACHE_ENTRIES_TOTAL);
  const ordered = [...rows].sort((a, b) => (Number(b.ts) || 0) - (Number(a.ts) || 0));
  const keptBytesByDatasource = new Map();
  const keptEntriesByDatasource = new Map();
  const victimSet = new Set();
  const perDatasourceSurvivors = [];
  for (const row of ordered) {
    const ds = String(row.ds || "");
    const bytes = Math.max(0, Number(row.bytes) || 0);
    const dsBytes = keptBytesByDatasource.get(ds) || 0;
    const dsEntries = keptEntriesByDatasource.get(ds) || 0;
    const keep = dsEntries < maxEntries && dsBytes + bytes <= maxBytes;
    if (keep) {
      keptEntriesByDatasource.set(ds, dsEntries + 1);
      keptBytesByDatasource.set(ds, dsBytes + bytes);
      perDatasourceSurvivors.push(row);
    } else {
      victimSet.add(row.k);
    }
  }
  let keptTotalBytes = 0;
  let keptTotalEntries = 0;
  let globalBudgetExhausted = false;
  for (const row of perDatasourceSurvivors) {
    const bytes = Math.max(0, Number(row.bytes) || 0);
    if (
      globalBudgetExhausted
      || keptTotalEntries >= maxTotalEntries
      || keptTotalBytes + bytes > maxTotalBytes
    ) {
      globalBudgetExhausted = true;
      victimSet.add(row.k);
      continue;
    }
    keptTotalEntries += 1;
    keptTotalBytes += bytes;
  }
  return ordered.filter((row) => victimSet.has(row.k)).map((row) => row.k);
}
