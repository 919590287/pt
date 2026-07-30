// 轨迹二进制分块的持久缓存（IndexedDB）。
// 命中即零网络、跨会话保留——把"缓存加载"从一次次回源变成本地直读，
// 二次打开同一模型时几乎零等待。持久层故障显式抛出，避免把缓存损坏伪装成普通未命中。
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
    throw new Error("当前环境不支持 IndexedDB，无法使用轨迹分块缓存");
  }
  dbPromise = new Promise((resolve, reject) => {
    let request;
    try {
      request = indexedDB.open(DB_NAME, DB_VERSION);
    } catch (error) {
      reject(error);
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
    request.onerror = () => reject(request.error || new Error("轨迹缓存数据库打开失败"));
    request.onblocked = () => reject(new Error("轨迹缓存数据库升级被其他页面阻塞"));
  });
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

// 命中返回原始字节（ArrayBuffer），仅真正未命中返回 null。
export async function getCachedChunk(key) {
  if (!key) throw new TypeError("轨迹缓存键不能为空");
  const db = await openDB();
  return new Promise((resolve, reject) => {
    try {
      const { transaction, chunks, meta } = withStores(db, "readwrite");
      let result = null;
      const request = chunks.get(key);
      request.onsuccess = () => {
        const record = request.result;
        if (record && !(record.buf instanceof ArrayBuffer)) {
          reject(new Error(`轨迹缓存记录损坏: ${key}`));
          return;
        }
        result = record?.buf || null;
        if (record?.buf instanceof ArrayBuffer) {
          const metaRequest = meta.get(key);
          metaRequest.onsuccess = () => {
            if (metaRequest.result) meta.put({ ...metaRequest.result, ts: Date.now() });
          };
        }
      };
      request.onerror = () => reject(request.error || new Error(`轨迹缓存读取失败: ${key}`));
      transaction.oncomplete = () => resolve(result);
      transaction.onerror = () => reject(transaction.error || new Error(`轨迹缓存事务失败: ${key}`));
      transaction.onabort = () => reject(transaction.error || new Error(`轨迹缓存事务已中止: ${key}`));
    } catch (error) {
      reject(error);
    }
  });
}

// 写入原始字节；配额或事务异常直接抛出。
export async function putCachedChunk(key, buffer, meta = {}) {
  if (!key) throw new TypeError("轨迹缓存键不能为空");
  if (!(buffer instanceof ArrayBuffer) || buffer.byteLength === 0) {
    throw new TypeError("轨迹缓存内容必须是非空 ArrayBuffer");
  }
  const db = await openDB();
  await new Promise((resolve, reject) => {
    try {
      const { transaction, chunks, meta: metaStore } = withStores(db, "readwrite");
      const now = Date.now();
      const ds = meta.ds || "";
      const ver = meta.ver || "";
      chunks.put({ k: key, buf: buffer });
      metaStore.put({ k: key, ds, ver, bytes: buffer.byteLength, ts: now });
      transaction.oncomplete = resolve;
      transaction.onerror = () => reject(transaction.error || new Error(`轨迹缓存写入失败: ${key}`));
      transaction.onabort = () => reject(transaction.error || new Error(`轨迹缓存写入已中止: ${key}`));
    } catch (error) {
      reject(error);
    }
  });
  if (meta.ds) await enforceChunkCacheBudget(meta.ds);
}

// events 变化后清理该模型的旧版本分块（ver 不一致即失效），避免读到过期轨迹。
export async function pruneChunkCache(datasource, keepVer) {
  if (!datasource) throw new TypeError("轨迹缓存 datasource 不能为空");
  const db = await openDB();
  await new Promise((resolve, reject) => {
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
      transaction.onerror = () => reject(transaction.error || new Error("轨迹缓存清理失败"));
      transaction.onabort = () => reject(transaction.error || new Error("轨迹缓存清理已中止"));
    } catch (error) {
      reject(error);
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
  await new Promise((resolve, reject) => {
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
      transaction.onerror = () => reject(transaction.error || new Error("轨迹缓存配额清理失败"));
      transaction.onabort = () => reject(transaction.error || new Error("轨迹缓存配额清理已中止"));
    } catch (error) {
      reject(error);
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
