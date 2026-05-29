import { getBusLineStation, getRealDataAreaList, getRealDataHistory } from "@/api/realData.js";

const DEFAULT_AREA = "广州市";

let areaListCache = null;
let areaListPromise = null;
const realDataCache = new Map();
const realDataPromises = new Map();
const historyCache = new Map();
const historyPromises = new Map();
const realDataGenerations = new Map();
const historyGenerations = new Map();

function normalizeArea(areaName) {
  return areaName || DEFAULT_AREA;
}

function latestKey(areaName) {
  return `${normalizeArea(areaName)}::__latest__`;
}

function versionKey(areaName, versionId = "") {
  return `${normalizeArea(areaName)}::${versionId || "__latest__"}`;
}

export function readCachedRealData(areaName = DEFAULT_AREA, versionId = "") {
  return realDataCache.get(versionKey(areaName, versionId)) || null;
}

export function readCachedHistory(areaName = DEFAULT_AREA) {
  return historyCache.get(normalizeArea(areaName)) || null;
}

export async function getCachedAreaList(options = {}) {
  const { force = false } = options;
  if (!force && areaListCache) return areaListCache;
  if (!force && areaListPromise) return areaListPromise;

  areaListPromise = getRealDataAreaList({ silentError: true })
    .then((res) => {
      const list = Array.isArray(res?.data) && res.data.length ? res.data : [DEFAULT_AREA];
      areaListCache = list;
      return list;
    })
    .finally(() => {
      areaListPromise = null;
    });

  return areaListPromise;
}

export async function getCachedRealData(areaName = DEFAULT_AREA, options = {}) {
  const { force = false, versionId = "" } = options;
  const area = normalizeArea(areaName);
  const key = versionKey(area, versionId);
  const generation = realDataGenerations.get(area) || 0;
  if (!force && realDataCache.has(key)) return realDataCache.get(key);
  if (!force && realDataPromises.has(key)) return realDataPromises.get(key);

  const request = getBusLineStation(
    {
      areaName: area,
      ...(versionId ? { versionId } : {}),
    },
    { silentError: true },
  )
    .then((res) => {
      const data = res?.data || {};
      if ((realDataGenerations.get(area) || 0) === generation) {
        realDataCache.set(key, data);
        if (!versionId) {
          realDataCache.set(latestKey(area), data);
        }
      }
      return data;
    })
    .finally(() => {
      realDataPromises.delete(key);
    });

  realDataPromises.set(key, request);
  return request;
}

export async function getCachedRealDataHistory(areaName = DEFAULT_AREA, options = {}) {
  const { force = false } = options;
  const area = normalizeArea(areaName);
  const generation = historyGenerations.get(area) || 0;
  if (!force && historyCache.has(area)) return historyCache.get(area);
  if (!force && historyPromises.has(area)) return historyPromises.get(area);

  const request = getRealDataHistory({ areaName: area }, { silentError: true })
    .then((res) => {
      const data = res?.data || {};
      if ((historyGenerations.get(area) || 0) === generation) {
        historyCache.set(area, data);
      }
      return data;
    })
    .finally(() => {
      historyPromises.delete(area);
    });

  historyPromises.set(area, request);
  return request;
}

export function invalidateCachedRealData(areaName = "") {
  if (!areaName) {
    realDataCache.clear();
    realDataPromises.clear();
    return;
  }
  const prefix = `${normalizeArea(areaName)}::`;
  const area = normalizeArea(areaName);
  realDataGenerations.set(area, (realDataGenerations.get(area) || 0) + 1);
  [...realDataCache.keys()].forEach((key) => {
    if (key.startsWith(prefix)) realDataCache.delete(key);
  });
  [...realDataPromises.keys()].forEach((key) => {
    if (key.startsWith(prefix)) realDataPromises.delete(key);
  });
}

export function invalidateCachedHistory(areaName = "") {
  if (!areaName) {
    historyCache.clear();
    historyPromises.clear();
    return;
  }
  const area = normalizeArea(areaName);
  historyGenerations.set(area, (historyGenerations.get(area) || 0) + 1);
  historyCache.delete(area);
  historyPromises.delete(area);
}

export function warmRealData(areaName = DEFAULT_AREA) {
  getCachedAreaList().catch(() => [DEFAULT_AREA]);
  getCachedRealData(areaName).catch(() => null);
}
