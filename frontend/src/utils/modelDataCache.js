import { markRaw } from "vue";
import { dataEvaluation } from "@/api/data.js";
import { getFacilityAll, getStationPanel } from "@/api/facility.js";
import { getLineAll, getRoutePanel } from "@/api/route.js";
import { getTransferDict, getTransferEventsBinary, getTransferSummary } from "@/api/transfer.js";
import { getPopulationGridBinary, getPopulationStreets, getPopulationSummary } from "@/api/population.js";
import {
  getTripEndsGridBinary,
  getTripEndsOdGridBinary,
  getTripEndsOdStreets,
  getTripEndsStreets,
  getTripEndsSummary,
} from "@/api/tripEnds.js";
import { getCorridorLinksBinary, getCorridorNames, getCorridorSummary } from "@/api/corridor.js";
import { getLinkSpeedMatrixBinary, getLinkSpeedSummary } from "@/api/linkspeed.js";

// 缓存的模型数上限（LRU）：监测页当前模型 + 方案编辑父模型 + 少量历史，超出淘汰最久未用的
const MAX_CACHED_MODELS = 4;
// lineAll / routePanel / stationPanel 都是模型级大 payload。
// 首次读取在机械硬盘或冷缓存下经常接近 60s，不能沿用全局接口超时。
const HEAVY_MODEL_REQUEST_TIMEOUT_MS = 180_000;

const modelCache = new Map();
const pendingControllers = new Map();
const warmupPromises = new Map();
const warmupRetryTimers = new Map();

function modelKey(model) {
  return String(model || "");
}

function hasPendingFor(key) {
  const prefix = `${key}::`;
  for (const pendingKey of pendingControllers.keys()) {
    if (pendingKey.startsWith(prefix)) return true;
  }
  return false;
}

function evictStaleModels() {
  while (modelCache.size > MAX_CACHED_MODELS) {
    // 跳过在途模型，但继续寻找其后的最旧可淘汰项，避免一个慢请求让 LRU 整体失去上限。
    const oldestKey = Array.from(modelCache.keys()).find((key) => !hasPendingFor(key));
    if (oldestKey == null) break;
    modelCache.delete(oldestKey);
  }
}

function entryFor(model) {
  const key = modelKey(model);
  let entry = modelCache.get(key);
  if (!entry) {
    entry = {};
  } else {
    modelCache.delete(key); // Map 插入序即 LRU 序：命中时重插到队尾
  }
  modelCache.set(key, entry);
  evictStaleModels();
  return entry;
}

// 大 payload 入缓存前统一 markRaw：任何组件即使把它放进深层 ref/reactive，
// Vue 也会跳过代理（__v_skip），从源头消除全量数据的深响应式开销。
// 约定：缓存数据只读、只做整值替换（scenarioedit 的编辑走独立 draft.edits，不改此数据）。
function markRawDeepEnough(data) {
  if (data && typeof data === "object") return markRaw(data);
  return data;
}

function controllerKey(model, type) {
  return `${modelKey(model)}::${type}`;
}

function isCanceled(error) {
  return error?.message === "请求已取消"
    || error?.message === "canceled"
    || error?.cause?.message === "canceled"
    || error?.cause?.code === "ERR_CANCELED";
}

function isPanelReady(data, type) {
  if (!data || data.status === "generating") return false;
  if (type === "routePanel") return Boolean(data.routes);
  if (type === "stationPanel") return Boolean(data.stations);
  if (type === "evaluation") return Boolean(data.values);
  return true;
}

function sharedModelRequest(model, type, requestFn) {
  const key = modelKey(model);
  if (!key) return Promise.resolve([]);
  const entry = entryFor(key);
  const dataKey = `${type}Data`;
  const promiseKey = `${type}Promise`;
  if (entry[dataKey]) return Promise.resolve(entry[dataKey]);
  if (entry[promiseKey]) return entry[promiseKey];

  const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
  if (controller) pendingControllers.set(controllerKey(key, type), controller);

  entry[promiseKey] = requestFn(
    { datasource: key },
    { silentError: true, signal: controller?.signal, timeout: HEAVY_MODEL_REQUEST_TIMEOUT_MS },
  )
    .then((res) => {
      const data = markRawDeepEnough(Array.isArray(res?.data) ? res.data : []);
      entry[dataKey] = data;
      return data;
    })
    .catch((error) => {
      delete entry[dataKey];
      if (!isCanceled(error)) {
        delete entry[promiseKey];
      }
      throw error;
    })
    .finally(() => {
      if (entry[promiseKey]) delete entry[promiseKey];
      const pendingKey = controllerKey(key, type);
      if (pendingControllers.get(pendingKey) === controller) pendingControllers.delete(pendingKey);
      evictStaleModels();
    });

  return entry[promiseKey];
}

// 对象型面板数据（routePanel/stationPanel）：按模型键控缓存 + 并发去重，中止复用同一套 pendingControllers。
// 关键：后端缓存生成中（data.status === "generating"）时不落缓存，直接透传结果，下次调用重新请求。
function sharedModelPanelRequest(model, type, requestFn) {
  const key = modelKey(model);
  if (!key) return Promise.resolve(null);
  const entry = entryFor(key);
  const dataKey = `${type}Data`;
  const promiseKey = `${type}Promise`;
  if (entry[dataKey]) return Promise.resolve(entry[dataKey]);
  if (entry[promiseKey]) return entry[promiseKey];

  const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
  if (controller) pendingControllers.set(controllerKey(key, type), controller);

  entry[promiseKey] = requestFn(
    { datasource: key },
    { silentError: true, signal: controller?.signal, timeout: HEAVY_MODEL_REQUEST_TIMEOUT_MS },
  )
    .then((res) => {
      const raw = res?.data && typeof res.data === "object" ? res.data : null;
      const data = raw ? markRawDeepEnough(raw) : raw;
      if (data && data.status !== "generating") {
        entry[dataKey] = data;
      }
      return data;
    })
    .catch((error) => {
      delete entry[dataKey];
      if (!isCanceled(error)) {
        delete entry[promiseKey];
      }
      throw error;
    })
    .finally(() => {
      if (entry[promiseKey]) delete entry[promiseKey];
      const pendingKey = controllerKey(key, type);
      if (pendingControllers.get(pendingKey) === controller) pendingControllers.delete(pendingKey);
      evictStaleModels();
    });

  return entry[promiseKey];
}

export function getCachedLineAll(model) {
  return sharedModelRequest(model, "lineAll", getLineAll);
}

export function getCachedFacilityAll(model) {
  return sharedModelRequest(model, "facilityAll", getFacilityAll);
}

export function getCachedRoutePanel(model) {
  return sharedModelPanelRequest(model, "routePanel", getRoutePanel);
}

export function getCachedStationPanel(model) {
  return sharedModelPanelRequest(model, "stationPanel", getStationPanel);
}

export function getCachedEvaluation(model) {
  return sharedModelPanelRequest(model, "evaluation", dataEvaluation);
}

export function getCachedTransferSummary(model) {
  return sharedModelPanelRequest(model, "transferSummary", getTransferSummary);
}

export function getCachedTransferDict(model) {
  return sharedModelPanelRequest(model, "transferDict", getTransferDict);
}

export function getCachedPopulationSummary(model) {
  return sharedModelPanelRequest(model, "populationSummary", getPopulationSummary);
}

export function getCachedPopulationStreets(model) {
  return sharedModelPanelRequest(model, "populationStreets", getPopulationStreets);
}

// 人口栅格二进制：按模型键控缓存 ArrayBuffer + 并发去重（与换乘事件表同构）。
export function getCachedPopulationGrid(model, version = "") {
  const key = modelKey(model);
  if (!key) return Promise.resolve(null);
  const entry = entryFor(key);
  const dataKey = "populationGridData";
  const promiseKey = "populationGridPromise";
  if (entry[dataKey]) return Promise.resolve(entry[dataKey]);
  if (entry[promiseKey]) return entry[promiseKey];

  const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
  if (controller) pendingControllers.set(controllerKey(key, "populationGrid"), controller);

  entry[promiseKey] = getPopulationGridBinary(
    { datasource: key, v: version },
    { silentError: true, signal: controller?.signal, timeout: HEAVY_MODEL_REQUEST_TIMEOUT_MS },
  )
    .then((response) => {
      const buffer = response instanceof ArrayBuffer ? response : response?.data;
      if (!(buffer instanceof ArrayBuffer)) return null;
      entry[dataKey] = buffer;
      return buffer;
    })
    .catch((error) => {
      delete entry[dataKey];
      if (!isCanceled(error)) {
        delete entry[promiseKey];
      }
      throw error;
    })
    .finally(() => {
      if (entry[promiseKey]) delete entry[promiseKey];
      const pendingKey = controllerKey(key, "populationGrid");
      if (pendingControllers.get(pendingKey) === controller) pendingControllers.delete(pendingKey);
      evictStaleModels();
    });

  return entry[promiseKey];
}

export function getCachedTripEndsSummary(model) {
  return sharedModelPanelRequest(model, "tripEndsSummary", getTripEndsSummary);
}

export function getCachedTripEndsStreets(model) {
  return sharedModelPanelRequest(model, "tripEndsStreets", getTripEndsStreets);
}

// 出行分布栅格二进制：按模型键控缓存 ArrayBuffer + 并发去重（与人口栅格同构）。
export function getCachedTripEndsGrid(model, version = "") {
  const key = modelKey(model);
  if (!key) return Promise.resolve(null);
  const entry = entryFor(key);
  const dataKey = "tripEndsGridData";
  const promiseKey = "tripEndsGridPromise";
  if (entry[dataKey]) return Promise.resolve(entry[dataKey]);
  if (entry[promiseKey]) return entry[promiseKey];

  const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
  if (controller) pendingControllers.set(controllerKey(key, "tripEndsGrid"), controller);

  entry[promiseKey] = getTripEndsGridBinary(
    { datasource: key, v: version },
    { silentError: true, signal: controller?.signal, timeout: HEAVY_MODEL_REQUEST_TIMEOUT_MS },
  )
    .then((response) => {
      const buffer = response instanceof ArrayBuffer ? response : response?.data;
      if (!(buffer instanceof ArrayBuffer)) return null;
      entry[dataKey] = buffer;
      return buffer;
    })
    .catch((error) => {
      delete entry[dataKey];
      if (!isCanceled(error)) {
        delete entry[promiseKey];
      }
      throw error;
    })
    .finally(() => {
      if (entry[promiseKey]) delete entry[promiseKey];
      const pendingKey = controllerKey(key, "tripEndsGrid");
      if (pendingControllers.get(pendingKey) === controller) pendingControllers.delete(pendingKey);
      evictStaleModels();
    });

  return entry[promiseKey];
}

export function getCachedTripEndsOdStreets(model) {
  return sharedModelPanelRequest(model, "tripEndsOdStreets", getTripEndsOdStreets);
}

// 公交OD栅格对二进制：按模型键控缓存 ArrayBuffer + 并发去重（与人口/出行分布栅格同构）。
export function getCachedTripEndsOdGrid(model, version = "") {
  const key = modelKey(model);
  if (!key) return Promise.resolve(null);
  const entry = entryFor(key);
  const dataKey = "tripEndsOdGridData";
  const promiseKey = "tripEndsOdGridPromise";
  if (entry[dataKey]) return Promise.resolve(entry[dataKey]);
  if (entry[promiseKey]) return entry[promiseKey];

  const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
  if (controller) pendingControllers.set(controllerKey(key, "tripEndsOdGrid"), controller);

  entry[promiseKey] = getTripEndsOdGridBinary(
    { datasource: key, v: version },
    { silentError: true, signal: controller?.signal, timeout: HEAVY_MODEL_REQUEST_TIMEOUT_MS },
  )
    .then((response) => {
      const buffer = response instanceof ArrayBuffer ? response : response?.data;
      if (!(buffer instanceof ArrayBuffer)) return null;
      entry[dataKey] = buffer;
      return buffer;
    })
    .catch((error) => {
      delete entry[dataKey];
      if (!isCanceled(error)) {
        delete entry[promiseKey];
      }
      throw error;
    })
    .finally(() => {
      if (entry[promiseKey]) delete entry[promiseKey];
      const pendingKey = controllerKey(key, "tripEndsOdGrid");
      if (pendingControllers.get(pendingKey) === controller) pendingControllers.delete(pendingKey);
      evictStaleModels();
    });

  return entry[promiseKey];
}

export function getCachedCorridorSummary(model) {
  return sharedModelPanelRequest(model, "corridorSummary", getCorridorSummary);
}

export function getCachedCorridorNames(model) {
  return sharedModelPanelRequest(model, "corridorNames", getCorridorNames);
}

// 走廊路段二进制：按模型键控缓存 ArrayBuffer + 并发去重（与人口栅格同构）。
export function getCachedCorridorLinks(model, version = "") {
  const key = modelKey(model);
  if (!key) return Promise.resolve(null);
  const entry = entryFor(key);
  const dataKey = "corridorLinksData";
  const promiseKey = "corridorLinksPromise";
  if (entry[dataKey]) return Promise.resolve(entry[dataKey]);
  if (entry[promiseKey]) return entry[promiseKey];

  const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
  if (controller) pendingControllers.set(controllerKey(key, "corridorLinks"), controller);

  entry[promiseKey] = getCorridorLinksBinary(
    { datasource: key, v: version },
    { silentError: true, signal: controller?.signal, timeout: HEAVY_MODEL_REQUEST_TIMEOUT_MS },
  )
    .then((response) => {
      const buffer = response instanceof ArrayBuffer ? response : response?.data;
      if (!(buffer instanceof ArrayBuffer)) return null;
      entry[dataKey] = buffer;
      return buffer;
    })
    .catch((error) => {
      delete entry[dataKey];
      if (!isCanceled(error)) {
        delete entry[promiseKey];
      }
      throw error;
    })
    .finally(() => {
      if (entry[promiseKey]) delete entry[promiseKey];
      const pendingKey = controllerKey(key, "corridorLinks");
      if (pendingControllers.get(pendingKey) === controller) pendingControllers.delete(pendingKey);
      evictStaleModels();
    });

  return entry[promiseKey];
}

export function getCachedLinkSpeedSummary(model) {
  return sharedModelPanelRequest(model, "linkSpeedSummary", getLinkSpeedSummary);
}

// 链路车速矩阵二进制：按模型键控缓存 ArrayBuffer + 并发去重（与走廊路段表同构）。
export function getCachedLinkSpeedMatrix(model, version = "") {
  const key = modelKey(model);
  if (!key) return Promise.resolve(null);
  const entry = entryFor(key);
  const dataKey = "linkSpeedMatrixData";
  const promiseKey = "linkSpeedMatrixPromise";
  if (entry[dataKey]) return Promise.resolve(entry[dataKey]);
  if (entry[promiseKey]) return entry[promiseKey];

  const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
  if (controller) pendingControllers.set(controllerKey(key, "linkSpeedMatrix"), controller);

  entry[promiseKey] = getLinkSpeedMatrixBinary(
    { datasource: key, v: version },
    { silentError: true, signal: controller?.signal, timeout: HEAVY_MODEL_REQUEST_TIMEOUT_MS },
  )
    .then((response) => {
      const buffer = response instanceof ArrayBuffer ? response : response?.data;
      if (!(buffer instanceof ArrayBuffer)) return null;
      entry[dataKey] = buffer;
      return buffer;
    })
    .catch((error) => {
      delete entry[dataKey];
      if (!isCanceled(error)) {
        delete entry[promiseKey];
      }
      throw error;
    })
    .finally(() => {
      if (entry[promiseKey]) delete entry[promiseKey];
      const pendingKey = controllerKey(key, "linkSpeedMatrix");
      if (pendingControllers.get(pendingKey) === controller) pendingControllers.delete(pendingKey);
      evictStaleModels();
    });

  return entry[promiseKey];
}

// 换乘事件表二进制：按模型键控缓存 ArrayBuffer + 并发去重。
// HTTP 层 ETag/immutable 由后端下发，浏览器缓存自动 304；内存层沿用 LRU entry。
export function getCachedTransferEvents(model, version = "") {
  const key = modelKey(model);
  if (!key) return Promise.resolve(null);
  const entry = entryFor(key);
  const dataKey = "transferEventsData";
  const promiseKey = "transferEventsPromise";
  if (entry[dataKey]) return Promise.resolve(entry[dataKey]);
  if (entry[promiseKey]) return entry[promiseKey];

  const controller = typeof AbortController !== "undefined" ? new AbortController() : null;
  if (controller) pendingControllers.set(controllerKey(key, "transferEvents"), controller);

  entry[promiseKey] = getTransferEventsBinary(
    { datasource: key, v: version },
    { silentError: true, signal: controller?.signal, timeout: HEAVY_MODEL_REQUEST_TIMEOUT_MS },
  )
    .then((response) => {
      const buffer = response instanceof ArrayBuffer ? response : response?.data;
      if (!(buffer instanceof ArrayBuffer)) return null;
      entry[dataKey] = buffer;
      return buffer;
    })
    .catch((error) => {
      delete entry[dataKey];
      if (!isCanceled(error)) {
        delete entry[promiseKey];
      }
      throw error;
    })
    .finally(() => {
      if (entry[promiseKey]) delete entry[promiseKey];
      const pendingKey = controllerKey(key, "transferEvents");
      if (pendingControllers.get(pendingKey) === controller) pendingControllers.delete(pendingKey);
      evictStaleModels();
    });

  return entry[promiseKey];
}

function runWhenIdle(fn) {
  if (typeof window !== "undefined" && typeof window.requestIdleCallback === "function") {
    window.requestIdleCallback(fn, { timeout: 3000 });
    return;
  }
  setTimeout(fn, 0);
}

function clearWarmupRetry(key) {
  const timer = warmupRetryTimers.get(key);
  if (timer) {
    clearTimeout(timer);
    warmupRetryTimers.delete(key);
  }
}

function scheduleWarmPanelRetry(model, type, loader, attempt = 0) {
  const key = `${modelKey(model)}::${type}::retry`;
  if (!modelKey(model) || warmupRetryTimers.has(key) || attempt >= 90) return;
  const delay = Math.min(15_000, 2_000 + attempt * 750);
  const timer = setTimeout(() => {
    warmupRetryTimers.delete(key);
    loader(model)
      .then((data) => {
        if (!isPanelReady(data, type)) {
          scheduleWarmPanelRetry(model, type, loader, attempt + 1);
        }
      })
      .catch((error) => {
        if (!isCanceled(error)) {
          scheduleWarmPanelRetry(model, type, loader, attempt + 1);
        }
      });
  }, delay);
  warmupRetryTimers.set(key, timer);
}

function warmPanel(model, type, loader) {
  return loader(model)
    .then((data) => {
      if (isPanelReady(data, type)) {
        clearWarmupRetry(`${modelKey(model)}::${type}::retry`);
      } else {
        scheduleWarmPanelRetry(model, type, loader);
      }
      return data;
    })
    .catch((error) => {
      if (!isCanceled(error)) {
        scheduleWarmPanelRetry(model, type, loader);
      }
      return null;
    });
}

// 链路车速包（summary→matrix 连锁）：返回 summary 供 warmPanel 的 generating 重试判定，
// 就绪时顺带把矩阵二进制拉进模型缓存——车辆运行监测开"路段公交车速"开关即出图，不再现场等待
function loadLinkSpeedBundle(model) {
  return getCachedLinkSpeedSummary(model).then((summary) => {
    if (summary && summary.status !== "generating") {
      const version = String(summary.generatedAt || summary.cacheVersion || "");
      getCachedLinkSpeedMatrix(model, version).catch(() => null);
    }
    return summary;
  });
}

// 模型进入前预热客流交互所需的前端缓存：
// - lineAll / facilityAll：地图点选、线路/站点搜索、线网 GeoJSON
// - routePanel：线路着色、选中线路右侧面板、断面客流
// stationPanel 体量可能更大，默认放到 idle 后台预热，避免阻塞线路点选首屏；
// 链路车速包（summary+矩阵）同批 idle 预热，与模型缓存一起就位。
export function warmModelInteractionCache(model, options = {}) {
  const key = modelKey(model);
  if (!key) return Promise.resolve(null);
  const { includeStationPanel = true, includeEvaluation = true, waitForHeavy = false } = options;
  const warmupKey = `${key}::${includeStationPanel ? "station" : "line"}::${includeEvaluation ? "eval" : "noeval"}::${waitForHeavy ? "wait" : "idle"}`;
  if (warmupPromises.has(warmupKey)) return warmupPromises.get(warmupKey);

  const warmHeavyPanels = () => Promise.allSettled([
    includeStationPanel ? warmPanel(key, "stationPanel", getCachedStationPanel) : Promise.resolve(null),
    includeEvaluation ? warmPanel(key, "evaluation", getCachedEvaluation) : Promise.resolve(null),
    warmPanel(key, "linkSpeed", loadLinkSpeedBundle),
  ]);

  const promise = Promise.all([
    getCachedLineAll(key),
    getCachedFacilityAll(key),
    getCachedRoutePanel(key),
  ])
    .then(([lines, facilities, routePanel]) => {
      if (includeStationPanel || includeEvaluation) {
        if (waitForHeavy) {
          return warmHeavyPanels().then(() => ({ lines, facilities, routePanel }));
        }
        runWhenIdle(warmHeavyPanels);
      }
      return { lines, facilities, routePanel };
    })
    .finally(() => {
      if (warmupPromises.get(warmupKey) === promise) warmupPromises.delete(warmupKey);
    });

  warmupPromises.set(warmupKey, promise);
  return promise;
}

// 同步窥视已缓存的整包线路客流面板（未缓存返回 null，不触发请求）。
// 供行政区筛选变化时判断能否纯本地重算，避免重复下载整包。
export function peekCachedRoutePanel(model) {
  return modelCache.get(modelKey(model))?.routePanelData || null;
}

// ---------- 模型级派生数据缓存 ----------
// 目标：把"从整包数据构建索引/排序选项/拓扑"这类只依赖模型的重计算，
// 前移到模型加载后只算一次，组件重挂载/tab 往返时直接命中，不再逐次重建。
// builder 同步执行、结果 markRaw 后随模型 entry 一起 LRU 淘汰。
export function getModelDerived(model, derivedKey, builder) {
  const key = modelKey(model);
  if (!key) return builder();
  const entry = entryFor(key);
  if (!entry.derived) entry.derived = new Map();
  if (entry.derived.has(derivedKey)) return entry.derived.get(derivedKey);
  const value = markRawDeepEnough(builder());
  entry.derived.set(derivedKey, value);
  return value;
}

// 使某个派生键失效（如显示范围等额外输入变化时由调用方主动失效）
export function invalidateModelDerived(model, derivedKey) {
  const entry = modelCache.get(modelKey(model));
  entry?.derived?.delete(derivedKey);
}

// 模型作用域的通用 Map（如线路详情缓存）：随模型 entry 生命周期存活与淘汰，
// 跨组件重挂载共享。limit 超出时按插入序淘汰最旧条目。
export function getModelScopedMap(model, namespace) {
  const key = modelKey(model);
  const entry = entryFor(key);
  if (!entry.scopedMaps) entry.scopedMaps = new Map();
  let map = entry.scopedMaps.get(namespace);
  if (!map) {
    map = new Map();
    entry.scopedMaps.set(namespace, map);
  }
  return map;
}

export function setScopedWithLimit(map, key, value, limit = 80) {
  if (map.has(key)) map.delete(key);
  map.set(key, markRawDeepEnough(value));
  while (map.size > limit) {
    map.delete(map.keys().next().value);
  }
  return value;
}

// 测试与诊断用：当前缓存的模型键（按 LRU 序，队尾最新）
export function __modelCacheKeys() {
  return Array.from(modelCache.keys());
}

export function clearModelDataCache(model) {
  const key = modelKey(model);
  if (!key) return;
  modelCache.delete(key);
  for (const pendingKey of Array.from(warmupPromises.keys())) {
    if (pendingKey.startsWith(`${key}::`)) {
      warmupPromises.delete(pendingKey);
    }
  }
  for (const pendingKey of Array.from(warmupRetryTimers.keys())) {
    if (pendingKey.startsWith(`${key}::`)) {
      clearWarmupRetry(pendingKey);
    }
  }
  for (const [pendingKey, controller] of pendingControllers.entries()) {
    if (pendingKey.startsWith(`${key}::`)) {
      controller.abort();
      pendingControllers.delete(pendingKey);
    }
  }
}

export function abortOtherModelDataRequests(activeModel) {
  const activeKey = modelKey(activeModel);
  for (const [pendingKey, controller] of pendingControllers.entries()) {
    if (!pendingKey.startsWith(`${activeKey}::`)) {
      controller.abort();
      pendingControllers.delete(pendingKey);
    }
  }
}
