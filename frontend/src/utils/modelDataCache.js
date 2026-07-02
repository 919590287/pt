import { getFacilityAll, getStationPanel } from "@/api/facility.js";
import { getLineAll, getRoutePanel } from "@/api/route.js";

const modelCache = new Map();
const pendingControllers = new Map();

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

// 同步窥视已缓存的整包线路客流面板（未缓存返回 null，不触发请求）。
// 供行政区筛选变化时判断能否纯本地重算，避免重复下载整包。
export function peekCachedRoutePanel(model) {
  return modelCache.get(modelKey(model))?.routePanelData || null;
}

export function clearModelDataCache(model) {
  const key = modelKey(model);
  if (!key) return;
  modelCache.delete(key);
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
