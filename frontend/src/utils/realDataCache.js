import { getAdminDistricts, getBusLineStation, getRealDataAreaList, getRealDataHistory } from "@/api/realData.js";

const DEFAULT_AREA = "广州市";

let areaListCache = null;
let areaListPromise = null;
const realDataCache = new Map();
const realDataPromises = new Map();
const routeStopsPromises = new Map();
const historyCache = new Map();
const historyPromises = new Map();
const adminDistrictCache = new Map();
const adminDistrictPromises = new Map();
const realDataGenerations = new Map();
const historyGenerations = new Map();
const adminDistrictGenerations = new Map();

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
      // 最新数据轻载（routeStops 以 deferred 占位，另行懒加载）；历史版本预览保持全量。
      // 旧后端会忽略未知的 include 字段并返回全量，天然向后兼容。
      ...(versionId ? { versionId } : { include: "core" }),
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

export function isRouteStopsDeferred(data) {
  return data?.routeStops?.deferred === true;
}

// 懒加载 routeStops 并原地合并进最新数据缓存对象（引用不变，所有持有方立即可见）。
// 返回合并后的 data；合并失败（版本错配/请求失败）时 data.routeStops 仍为 deferred 占位，调用方可重试。
export function ensureCachedRouteStops(areaName = DEFAULT_AREA) {
  const area = normalizeArea(areaName);
  const existing = routeStopsPromises.get(area);
  if (existing) return existing;
  const generation = realDataGenerations.get(area) || 0;
  const promise = getCachedRealData(area)
    .then(async (data) => {
      if (!isRouteStopsDeferred(data)) return data;
      const res = await getBusLineStation({ areaName: area, include: "routeStops" }, { silentError: true });
      if ((realDataGenerations.get(area) || 0) !== generation) return data;
      const payload = res?.data || {};
      const sameVersion = String(payload.versionId || "") === String(data.versionId || "");
      if (payload.routeStops && sameVersion) {
        data.routeStops = payload.routeStops;
      }
      return data;
    })
    .finally(() => {
      routeStopsPromises.delete(area);
    });
  routeStopsPromises.set(area, promise);
  return promise;
}

export async function getCachedAdminDistricts(areaName = DEFAULT_AREA, options = {}) {
  const { force = false } = options;
  const area = normalizeArea(areaName);
  const generation = adminDistrictGenerations.get(area) || 0;
  if (!force && adminDistrictCache.has(area)) return adminDistrictCache.get(area);
  if (!force && adminDistrictPromises.has(area)) return adminDistrictPromises.get(area);

  const request = getAdminDistricts({ areaName: area }, { silentError: true })
    .then((res) => {
      const data = res?.data || {};
      if ((adminDistrictGenerations.get(area) || 0) === generation) {
        adminDistrictCache.set(area, data);
      }
      return data;
    })
    .finally(() => {
      adminDistrictPromises.delete(area);
    });

  adminDistrictPromises.set(area, request);
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
    routeStopsPromises.clear();
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
  routeStopsPromises.delete(area);
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

export function invalidateCachedAdminDistricts(areaName = "") {
  if (!areaName) {
    adminDistrictCache.clear();
    adminDistrictPromises.clear();
    return;
  }
  const area = normalizeArea(areaName);
  adminDistrictGenerations.set(area, (adminDistrictGenerations.get(area) || 0) + 1);
  adminDistrictCache.delete(area);
  adminDistrictPromises.delete(area);
}

export function warmRealData(areaName = DEFAULT_AREA) {
  getCachedAreaList().catch(() => [DEFAULT_AREA]);
  getCachedAdminDistricts(areaName).catch(() => null);
  getCachedRealData(areaName)
    .then(() => ensureCachedRouteStops(areaName))
    .catch(() => null);
}
