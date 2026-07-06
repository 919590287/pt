import { getFacilityAll, getStationPanel } from "@/api/facility.js";
import { getLineAll, getRoutePanel } from "@/api/route.js";

const modelCache = new Map();
const pendingControllers = new Map();
const warmupPromises = new Map();

function modelKey(model) {
  return String(model || "");
}

function entryFor(model) {
  const key = modelKey(model);
  let entry = modelCache.get(key);
  if (!entry) {
    entry = {};
    modelCache.set(key, entry);
  }
  return entry;
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

  entry[promiseKey] = requestFn({ datasource: key }, { silentError: true, signal: controller?.signal })
    .then((res) => {
      const data = Array.isArray(res?.data) ? res.data : [];
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
      pendingControllers.delete(controllerKey(key, type));
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

  entry[promiseKey] = requestFn({ datasource: key }, { silentError: true, signal: controller?.signal })
    .then((res) => {
      const data = res?.data && typeof res.data === "object" ? res.data : null;
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
      pendingControllers.delete(controllerKey(key, type));
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

function runWhenIdle(fn) {
  if (typeof window !== "undefined" && typeof window.requestIdleCallback === "function") {
    window.requestIdleCallback(fn, { timeout: 3000 });
    return;
  }
  setTimeout(fn, 0);
}

// 模型进入前预热客流交互所需的前端缓存：
// - lineAll / facilityAll：地图点选、线路/站点搜索、线网 GeoJSON
// - routePanel：线路着色、选中线路右侧面板、断面客流
// stationPanel 体量可能更大，默认放到 idle 后台预热，避免阻塞线路点选首屏。
export function warmModelInteractionCache(model, options = {}) {
  const key = modelKey(model);
  if (!key) return Promise.resolve(null);
  const { includeStationPanel = false } = options;
  const warmupKey = `${key}::${includeStationPanel ? "station" : "line"}`;
  if (warmupPromises.has(warmupKey)) return warmupPromises.get(warmupKey);

  const promise = Promise.all([
    getCachedLineAll(key),
    getCachedFacilityAll(key),
    getCachedRoutePanel(key),
  ])
    .then(([lines, facilities, routePanel]) => {
      if (includeStationPanel) {
        runWhenIdle(() => {
          getCachedStationPanel(key).catch(() => {});
        });
      }
      return { lines, facilities, routePanel };
    })
    .finally(() => {
      warmupPromises.delete(warmupKey);
    });

  warmupPromises.set(warmupKey, promise);
  return promise;
}

// 同步窥视已缓存的整包线路客流面板（未缓存返回 null，不触发请求）。
// 供行政区筛选变化时判断能否纯本地重算，避免重复下载整包。
export function peekCachedRoutePanel(model) {
  return modelCache.get(modelKey(model))?.routePanelData || null;
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
