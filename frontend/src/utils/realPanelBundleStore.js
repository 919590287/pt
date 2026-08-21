/**
 * 真实客流面板 bundle 的浏览器持久缓存。
 *
 * 页面内的 analysisCache / networkCache 都是模块级 Map，刷新即清零，于是每次刷新都要
 * 把后端已经编译好的面板工件重新传一遍。这里把 bundle 落到 IndexedDB，刷新命中本地就
 * 不再发请求，真实模式因此获得与仿真一致（实际更好）的刷新体验。
 *
 * 失效完全交给后端指纹：key 里带 sourceSignature，源 CSV 或线网一变，签名变化，旧记录
 * 读不到也就自动作废，随后由 trimStore 清走。
 *
 * 只持久化最近使用的少数几个日期：33 个日期全量落盘约 80MB，写入慢且挤占配额，而刷新
 * 只需要用户当时正在看的那个日期立刻可用，其余日期由后台预取补进内存即可。
 */

const DB_NAME = "bus-platform-real-panels";
const DB_VERSION = 1;
const STORE_NAME = "bundles";
export const MAX_PERSISTED_DATES = 5;

export function panelBundleKey(areaName, signature, serviceDate) {
  return `${areaName || ""}::${signature || ""}::${serviceDate || ""}`;
}

/**
 * 严格递增的使用戳。连续几次写入很容易落在同一毫秒，一旦 usedAt 打平，LRU 排序就会退化成
 * 插入序，反而把最新的记录淘汰掉。这里保证同会话内永不相等，跨会话仍近似挂钟时间。
 */
let lastStamp = 0;
function nextStamp() {
  lastStamp = Math.max(Date.now(), lastStamp + 1);
  return lastStamp;
}

/** IndexedDB 适配层。测试通过 configureBundleStoreBackend 换成内存实现。 */
let backend = null;
let backendResolved = false;

export function configureBundleStoreBackend(next) {
  backend = next || null;
  backendResolved = true;
}

export function resetBundleStoreBackend() {
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
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        const store = db.createObjectStore(STORE_NAME, { keyPath: "key" });
        store.createIndex("area", "area", { unique: false });
      }
    };
    request.onsuccess = () => resolve(request.result);
    // 隐私模式、配额拒绝、版本冲突都只降级成“没有持久缓存”，不能影响页面进入。
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
        transaction = db.transaction(STORE_NAME, mode);
      } catch {
        db.close();
        resolve(null);
        return;
      }
      const store = transaction.objectStore(STORE_NAME);
      try {
        work(store, (value) => { result = value; });
      } catch {
        result = null;
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
      return runTransaction("readonly", (store, done) => {
        const request = store.get(key);
        request.onsuccess = () => done(request.result || null);
      });
    },
    put(record) {
      return runTransaction("readwrite", (store) => { store.put(record); });
    },
    remove(keys) {
      return runTransaction("readwrite", (store) => { keys.forEach((key) => store.delete(key)); });
    },
    listMeta() {
      return runTransaction("readonly", (store, done) => {
        const rows = [];
        const request = store.openCursor();
        request.onsuccess = () => {
          const cursor = request.result;
          if (!cursor) {
            done(rows);
            return;
          }
          const value = cursor.value || {};
          rows.push({ key: value.key, area: value.area, signature: value.signature, usedAt: value.usedAt || 0 });
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

/** 命中返回 bundle，未命中或不可用返回 null；任何失败都不抛出，调用方直接回落到网络。 */
export async function readPanelBundle(areaName, signature, serviceDate) {
  const store = activeBackend();
  if (!store || !signature) return null;
  try {
    const record = await store.get(panelBundleKey(areaName, signature, serviceDate));
    if (!record?.bundle) return null;
    // 命中即续期，保证正在使用的日期不会被 LRU 挤掉。
    void store.put({ ...record, usedAt: nextStamp() });
    return record.bundle;
  } catch {
    return null;
  }
}

/** 写入是补偿性的，调用方不应 await；失败静默丢弃。 */
export async function writePanelBundle(areaName, signature, serviceDate, bundle) {
  const store = activeBackend();
  if (!store || !signature || !bundle) return false;
  try {
    await store.put({
      key: panelBundleKey(areaName, signature, serviceDate),
      area: areaName,
      signature,
      serviceDate,
      bundle,
      usedAt: nextStamp(),
    });
    await trimStore(store, areaName, signature);
    return true;
  } catch {
    return false;
  }
}

/**
 * 同一区域内：签名不同的记录一律删除（源数据已变），当前签名的记录按 usedAt 保留最近
 * MAX_PERSISTED_DATES 条。其他区域的记录不动。
 */
async function trimStore(store, areaName, signature) {
  const rows = await store.listMeta();
  if (!Array.isArray(rows) || !rows.length) return;
  const mine = rows.filter((row) => row?.area === areaName);
  const stale = mine.filter((row) => row.signature !== signature).map((row) => row.key);
  const current = mine
    .filter((row) => row.signature === signature)
    .sort((left, right) => (right.usedAt || 0) - (left.usedAt || 0));
  const overflow = current.slice(MAX_PERSISTED_DATES).map((row) => row.key);
  const removable = [...stale, ...overflow].filter(Boolean);
  if (removable.length) await store.remove(removable);
}

/** 供测试与手动排障使用：丢弃某区域的全部持久记录。 */
export async function clearPersistedBundles(areaName = "") {
  const store = activeBackend();
  if (!store) return;
  try {
    const rows = await store.listMeta();
    const keys = (Array.isArray(rows) ? rows : [])
      .filter((row) => !areaName || row?.area === areaName)
      .map((row) => row?.key)
      .filter(Boolean);
    if (keys.length) await store.remove(keys);
  } catch {
    // 清理失败不影响任何页面行为。
  }
}
