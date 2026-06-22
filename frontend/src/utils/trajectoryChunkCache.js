// 轨迹二进制分块的持久缓存（IndexedDB）。
// 命中即零网络、跨会话保留——把"缓存加载"从一次次回源变成本地直读，
// 二次打开同一模型时几乎零等待。所有错误都静默降级到网络，不影响可用性。
const DB_NAME = "gjcx-trajectory";
const STORE = "chunks";
const DB_VERSION = 1;

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
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE)) {
        const store = db.createObjectStore(STORE, { keyPath: "k" });
        store.createIndex("ds", "ds", { unique: false });
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

// 命中返回原始字节（ArrayBuffer），未命中/异常返回 null。
export async function getCachedChunk(key) {
  const db = await openDB();
  if (!db || !key) return null;
  return new Promise((resolve) => {
    try {
      const { store } = withStore(db, "readonly");
      const request = store.get(key);
      request.onsuccess = () => {
        const record = request.result;
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
      const { transaction, store } = withStore(db, "readwrite");
      store.put({ k: key, ds: meta.ds || "", ver: meta.ver || "", buf: buffer, ts: Date.now() });
      transaction.oncomplete = () => resolve();
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
      const { transaction, store } = withStore(db, "readwrite");
      const index = store.index("ds");
      const request = index.openCursor(IDBKeyRange.only(datasource));
      request.onsuccess = () => {
        const cursor = request.result;
        if (!cursor) return;
        if (cursor.value?.ver !== keepVer) {
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
