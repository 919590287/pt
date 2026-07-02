import { getFacilityAll } from "@/api/facility.js";
import { getLineAll } from "@/api/route.js";

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

export function getCachedLineAll(model) {
  return sharedModelRequest(model, "lineAll", getLineAll);
}

export function getCachedFacilityAll(model) {
  return sharedModelRequest(model, "facilityAll", getFacilityAll);
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
