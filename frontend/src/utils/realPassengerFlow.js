import request from "@/utils/request.js";
import { getStreetsGeojson } from "@/api/population.js";
import { getCachedRealData, ensureCachedRouteStops } from "@/utils/realDataCache.js";
import { readPanelBundle, writePanelBundle } from "@/utils/realPanelBundleStore.js";

export const REAL_DATASOURCE_PREFIX = "real::";
export const DEFAULT_REAL_AREA = "广州市";
export const REAL_AVERAGE_DATE = "average";
const REAL_DATE_SEPARATOR = "::service-date::";

const networkCache = new Map();
const analysisCache = new Map();
const streetAggregationCache = new Map();
const realWarmupPromises = new Map();
const WEB_MERCATOR_RADIUS = 6378137;

function lngLatToWebMercator(lng, lat) {
  const safeLat = Math.max(-85.05112878, Math.min(85.05112878, Number(lat) || 0));
  return [
    WEB_MERCATOR_RADIUS * (Number(lng) || 0) * Math.PI / 180,
    WEB_MERCATOR_RADIUS * Math.log(Math.tan(Math.PI / 4 + safeLat * Math.PI / 360)),
  ];
}

function webMercatorToLngLat(x, y) {
  return [
    (Number(x) / WEB_MERCATOR_RADIUS) * (180 / Math.PI),
    (2 * Math.atan(Math.exp(Number(y) / WEB_MERCATOR_RADIUS)) - Math.PI / 2) * (180 / Math.PI),
  ];
}

export function realDatasource(areaName = DEFAULT_REAL_AREA, serviceDate = REAL_AVERAGE_DATE) {
  const area = areaName || DEFAULT_REAL_AREA;
  const date = String(serviceDate || REAL_AVERAGE_DATE);
  return date === REAL_AVERAGE_DATE
    ? `${REAL_DATASOURCE_PREFIX}${area}`
    : `${REAL_DATASOURCE_PREFIX}${area}${REAL_DATE_SEPARATOR}${date}`;
}

export function isRealDatasource(value) {
  return String(value || "").startsWith(REAL_DATASOURCE_PREFIX);
}

export function realAreaFromDatasource(value) {
  if (!isRealDatasource(value)) return "";
  return String(value).slice(REAL_DATASOURCE_PREFIX.length).split(REAL_DATE_SEPARATOR)[0] || DEFAULT_REAL_AREA;
}

export function realServiceDateFromDatasource(value) {
  if (!isRealDatasource(value)) return REAL_AVERAGE_DATE;
  const parts = String(value).slice(REAL_DATASOURCE_PREFIX.length).split(REAL_DATE_SEPARATOR);
  return parts[1] || REAL_AVERAGE_DATE;
}

export function realLineGroupName(value = "") {
  const text = String(value || "").trim();
  let depth = 0;
  let outerStart = -1;
  for (let index = 0; index < text.length; index += 1) {
    const current = text[index];
    if (current === "(" || current === "（") {
      if (depth === 0) outerStart = index;
      depth += 1;
    } else if ((current === ")" || current === "）") && depth > 0) {
      depth -= 1;
      if (depth === 0 && outerStart >= 0) {
        const content = text.slice(outerStart + 1, index);
        if (/(?:--|—|－|→|至)/.test(content)) {
          return normalizeNanshaLinePrefix(text.slice(0, outerStart).trim());
        }
      }
    }
  }
  return normalizeNanshaLinePrefix(text);
}

export function realLineGroupId(value = "") {
  return `real-line::${realLineGroupName(value)}`;
}

export function authorityDirectionKey(route = {}, fallback = "") {
  const authorityLineId = firstText(route, "authorityLineId", "authority_line_id");
  if (authorityLineId) return `authority:${authorityLineId}`;
  const routeId = firstText(route, "routeId", "route_id");
  const lineId = firstText(route, "lineId", "line_id");
  return routeId ? `${lineId}::${routeId}` : String(fallback || "");
}

export function uniqueAuthorityDirectionRoutes(routes = [], activeKey = "") {
  const byDirection = new Map();
  for (const [index, route] of (Array.isArray(routes) ? routes : []).entries()) {
    if (!route || route.lineGroup) continue;
    const key = authorityDirectionKey(route, index);
    if (!key) continue;
    const existing = byDirection.get(key);
    const routeKey = authorityDirectionKey(route);
    if (!existing || routeKey === activeKey || String(route.routeId || "") === String(activeKey || "")) {
      byDirection.set(key, route);
    }
  }
  return [...byDirection.values()];
}

function normalizeNanshaLinePrefix(value = "") {
  const text = String(value || "").trim();
  const slashAlias = text.match(/^(\d+)路?\/南(?:沙)?(\d+)路?$/);
  if (slashAlias && slashAlias[1] === slashAlias[2]) return `南沙${slashAlias[1]}路`;
  if (text.startsWith("南沙")) return text;
  return /^南(?=\d|[GKWT夜学旅游])/.test(text) ? `南沙${text.slice(1)}` : text;
}

function featureProperties(feature) {
  return feature?.properties && typeof feature.properties === "object" ? feature.properties : {};
}

function firstText(source, ...keys) {
  for (const key of keys) {
    const value = source?.[key];
    if (value !== null && value !== undefined && String(value).trim()) return String(value).trim();
  }
  return "";
}

function longestLineString(geometry) {
  const coordinates = geometry?.coordinates;
  if (!Array.isArray(coordinates)) return [];
  if (geometry?.type === "LineString") return coordinates;
  if (geometry?.type === "MultiLineString") {
    return coordinates.reduce((best, item) => (Array.isArray(item) && item.length > best.length ? item : best), []);
  }
  return [];
}

function mercatorPoint(lon, lat) {
  const point = lngLatToWebMercator(Number(lon), Number(lat));
  return Array.isArray(point) && point.every(Number.isFinite) ? point : null;
}

function routeGeometry(feature, facilities) {
  const geometry = longestLineString(feature?.geometry)
    .map((point) => mercatorPoint(point?.[0], point?.[1]))
    .filter(Boolean);
  if (geometry.length >= 2) return geometry;
  return facilities.map((item) => [item.coord.x, item.coord.y]).filter((item) => item.every(Number.isFinite));
}

function geometryLinks(geometry, routeId) {
  const links = [];
  for (let index = 1; index < geometry.length; index += 1) {
    links.push({
      linkId: `real-${routeId}-${index}`,
      from: { x: geometry[index - 1][0], y: geometry[index - 1][1] },
      to: { x: geometry[index][0], y: geometry[index][1] },
    });
  }
  return links;
}

function geometryDistance(geometry) {
  let distance = 0;
  for (let index = 1; index < geometry.length; index += 1) {
    distance += Math.hypot(
      geometry[index][0] - geometry[index - 1][0],
      geometry[index][1] - geometry[index - 1][1],
    );
  }
  return distance;
}

export function buildRealTransitNetwork(data) {
  const routeStopsByLine = new Map();
  for (const feature of data?.routeStops?.features || []) {
    const properties = featureProperties(feature);
    const authorityLineId = firstText(properties, "line_id", "route_id");
    if (!authorityLineId) continue;
    const coordinates = feature?.geometry?.coordinates || [];
    const point = mercatorPoint(
      properties.lon ?? coordinates[0],
      properties.lat ?? coordinates[1],
    );
    if (!point) continue;
    const facility = {
      facilityId: firstText(properties, "stop_id", "station_id", "_featureId"),
      facilityName: firstText(properties, "stop_name", "name", "stop_id") || "未命名站点",
      coord: { x: point[0], y: point[1] },
      seq: Number(properties.seq) || 0,
    };
    const list = routeStopsByLine.get(authorityLineId) || [];
    list.push(facility);
    routeStopsByLine.set(authorityLineId, list);
  }
  routeStopsByLine.forEach((items) => items.sort((a, b) => a.seq - b.seq));

  const groups = new Map();
  for (const feature of data?.lines?.features || []) {
    const properties = featureProperties(feature);
    const authorityLineId = firstText(properties, "line_id", "route_id", "_featureId");
    if (!authorityLineId) continue;
    const routeName = firstText(properties, "name", "route_name", "line_name", "line_id") || authorityLineId;
    const lineName = realLineGroupName(routeName);
    const lineId = realLineGroupId(lineName);
    const price = firstText(properties, "price", "fare");
    const interval = firstText(properties, "interval", "headway");
    const facilities = (routeStopsByLine.get(authorityLineId) || []).map(({ seq, ...item }) => item);
    const geometry = routeGeometry(feature, facilities);
    const routeDist = geometryDistance(geometry);
    const route = {
      lineId,
      lineName,
      authorityLineId,
      routeId: authorityLineId,
      routeName,
      transportMode: "bus",
      mode: "bus",
      price,
      fare: price,
      interval,
      headway: interval,
      geometry,
      links: geometryLinks(geometry, authorityLineId),
      facilities,
      departures: [],
      info: {
        lineName,
        price,
        fare: price,
        interval,
        headway: interval,
        routeDist,
        firstTime: 0,
        lastTime: 0,
        facNum: facilities.length,
        facDist: facilities.length > 1 ? routeDist / (facilities.length - 1) : 0,
        passenger: 0,
      },
    };
    let group = groups.get(lineId);
    if (!group) {
      group = { lineId, lineName, mode: "bus", routes: [] };
      groups.set(lineId, group);
    }
    group.routes.push(route);
  }

  const facilities = [];
  const seenFacilities = new Set();
  for (const feature of data?.stations?.features || []) {
    const properties = featureProperties(feature);
    const coordinates = feature?.geometry?.coordinates || [];
    const point = mercatorPoint(properties.lon ?? coordinates[0], properties.lat ?? coordinates[1]);
    const facilityId = firstText(properties, "stop_id", "station_id", "_featureId");
    const facilityName = firstText(properties, "stop_name", "name", "stop_id") || "未命名站点";
    const key = facilityId || `${facilityName}:${point?.join(":")}`;
    if (!point || seenFacilities.has(key)) continue;
    seenFacilities.add(key);
    facilities.push({ facilityId: facilityId || key, facilityName, coord: { x: point[0], y: point[1] } });
  }
  if (!facilities.length) {
    for (const items of routeStopsByLine.values()) {
      for (const { seq, ...facility } of items) {
        const key = facility.facilityId || `${facility.facilityName}:${facility.coord.x}:${facility.coord.y}`;
        if (seenFacilities.has(key)) continue;
        seenFacilities.add(key);
        facilities.push(facility);
      }
    }
  }
  return { lines: [...groups.values()], facilities, bounds: data?.bounds || null };
}

export function clearRealPassengerFlowCache(datasource = "") {
  const areaName = realAreaFromDatasource(datasource) || String(datasource || "").trim();
  if (!areaName) {
    networkCache.clear();
    analysisCache.clear();
    streetAggregationCache.clear();
    realWarmupPromises.clear();
    return;
  }
  networkCache.delete(areaName);
  [...realWarmupPromises.keys()].forEach((key) => {
    if (key.startsWith(`${areaName}::`)) realWarmupPromises.delete(key);
  });
  const prefix = `${REAL_DATASOURCE_PREFIX}${areaName}`;
  [...analysisCache.keys()].forEach((key) => {
    if (key.startsWith(prefix)) analysisCache.delete(key);
  });
  [...streetAggregationCache.keys()].forEach((key) => {
    if (key.startsWith(prefix)) streetAggregationCache.delete(key);
  });
}

export async function getRealNetwork(datasource, options = {}) {
  const areaName = realAreaFromDatasource(datasource) || DEFAULT_REAL_AREA;
  if (options.force) await getCachedRealData(areaName, { force: true });
  const data = await ensureCachedRouteStops(areaName);
  const cached = networkCache.get(areaName);
  if (cached?.source === data) return cached.network;
  const network = buildRealTransitNetwork(data);
  networkCache.set(areaName, { source: data, network });
  return network;
}

export async function getRealLineAll(datasource) {
  return (await getRealNetwork(datasource)).lines;
}

export async function getRealFacilityAll(datasource) {
  return (await getRealNetwork(datasource)).facilities;
}

export async function getRealRouteDetail(datasource, lineId, routeId) {
  const network = await getRealNetwork(datasource);
  for (const line of network.lines) {
    const route = (line.routes || []).find((item) => String(item.routeId) === String(routeId));
    if (route && (!lineId || String(route.lineId) === String(lineId))) return route;
  }
  return {};
}

export function realPassengerFlowRequest(endpoint, data = {}, config = {}) {
  const areaName = realAreaFromDatasource(data?.datasource) || data?.areaName || DEFAULT_REAL_AREA;
  const serviceDate = data?.serviceDate || realServiceDateFromDatasource(data?.datasource);
  const payload = { ...data, areaName, serviceDate };
  delete payload.datasource;
  return request({
    url: `/pt/real-data/passenger-flow/${endpoint}`,
    method: "POST",
    data: payload,
    timeout: 180_000,
    ...config,
  });
}

function cachedAnalysis(datasource, endpoint, options = {}) {
  const key = `${datasource}::${endpoint}`;
  if (options.force) analysisCache.delete(key);
  if (analysisCache.has(key)) return analysisCache.get(key);
  const promise = realPassengerFlowRequest(endpoint, { datasource }, { silentError: true })
    .then((res) => res?.data || {})
    .catch((error) => {
      analysisCache.delete(key);
      throw error;
    });
  analysisCache.set(key, promise);
  return promise;
}

/** force 用于门槛轮询后端缓存构建进度，绕开会话内缓存拿最新状态位。 */
export function getRealPassengerFlowCapabilities(datasource, options = {}) {
  return cachedAnalysis(datasource, "capabilities", options);
}

export function getRealPassengerFlowPreload(datasource) {
  return cachedAnalysis(datasource, "preload");
}

/**
 * capabilities 同时描述原始聚合与面板工件。面板工件本来就由 preload 负责生成，
 * 因此只有原始聚合尚未产出服务日时才需要等待，不能拿 panelCacheStatus 阻断 preload。
 */
export function isRealPassengerAggregatePending(capabilities = {}) {
  const status = String(capabilities?.status || "").toLowerCase();
  const dates = Array.isArray(capabilities?.serviceDates) ? capabilities.serviceDates : [];
  return (status === "pending" || status === "building") && dates.length === 0;
}

export function realPassengerCapabilityError(capabilities = {}) {
  const status = String(capabilities?.status || "").toLowerCase();
  if (status !== "failed") return "";
  return String(
    capabilities?.aggregationMessage
      || capabilities?.buildMessage
      || capabilities?.message
      || capabilities?.progressMessage
      || "真实客流原始数据聚合失败",
  );
}

export function getRealOverallFlow(datasource) {
  return cachedAnalysis(datasource, "overallFlow");
}

/** 把某个服务日的面板 bundle 写入与页面读取共用的会话缓存。 */
export function primeRealPanelBundle(areaName, serviceDate, bundle) {
  if (!bundle?.overallFlow) return bundle;
  const datasource = realDatasource(areaName, serviceDate);
  analysisCache.set(`${datasource}::overallFlow`, Promise.resolve(bundle.overallFlow));
  return bundle;
}

/** 把首次预加载返回的各日期轻量数据写入与页面请求共用的缓存。 */
export function primeRealPassengerFlowDates(areaName, payload = {}) {
  const dates = payload?.dates && typeof payload.dates === "object" ? payload.dates : {};
  Object.entries(dates).forEach(([serviceDate, bundle]) => primeRealPanelBundle(areaName, serviceDate, bundle));
  return dates;
}

/**
 * 取单个服务日的面板 bundle：先读浏览器持久缓存，未命中才请求后端并回写。
 * signature 为后端 capabilities 下发的源指纹，源数据一变旧记录自然失效。
 *
 * persist=false 用于后台预取：那些日期只需进内存，落盘反而会把用户正在看的日期挤出
 * LRU，并为了最终只保留几条而白写几十兆。
 */
export async function getRealPanelBundle(areaName, signature, serviceDate, options = {}) {
  const persisted = await readPanelBundle(areaName, signature, serviceDate);
  if (persisted) return persisted;
  const preload = await getRealPassengerFlowPreload(realDatasource(areaName, serviceDate));
  const dates = preload?.dates && typeof preload.dates === "object" ? preload.dates : {};
  const bundle = dates[serviceDate] || dates[preload?.selectedServiceDate] || null;
  // 回写是补偿动作，不阻塞调用方；写失败只是下次刷新回落到网络。
  if (bundle && options.persist !== false) void writePanelBundle(areaName, signature, serviceDate, bundle);
  return bundle;
}

/**
 * 网站入口并行预热真实模式的完整首屏：线网、能力声明、选中服务日 bundle，
 * 以及线路/站点/评价面板缓存。页面挂载后只切引用，不再进行第二轮冷加载。
 */
export function warmRealPassengerFlow(
  areaName = DEFAULT_REAL_AREA,
  preferredServiceDate = "",
) {
  const area = areaName || DEFAULT_REAL_AREA;
  const requestedDate = String(preferredServiceDate || "");
  const key = `${area}::${requestedDate || "latest"}`;
  if (realWarmupPromises.has(key)) return realWarmupPromises.get(key);

  const request = (async () => {
    const datasource = realDatasource(area, REAL_AVERAGE_DATE);
    const networkPromise = getRealNetwork(datasource);
    let capabilities;
    for (;;) {
      capabilities = await getRealPassengerFlowCapabilities(datasource, { force: Boolean(capabilities) });
      const capabilityError = realPassengerCapabilityError(capabilities);
      if (capabilityError) throw new Error(capabilityError);
      if (!isRealPassengerAggregatePending(capabilities)) break;
      const attempt = Number(capabilities?.aggregationProgressPercent) || 0;
      await new Promise((resolve) => setTimeout(resolve, attempt > 0 ? 5000 : 3000));
    }

    const dates = Array.isArray(capabilities?.serviceDates) ? capabilities.serviceDates : [];
    if (!dates.length) throw new Error("真实客流数据中没有可用日期");
    const serviceDate = dates.includes(requestedDate) ? requestedDate : dates.at(-1);
    const signature = String(capabilities?.sourceSignature || "");
    const [network, bundle] = await Promise.all([
      networkPromise,
      getRealPanelBundle(area, signature, serviceDate),
    ]);
    if (!bundle) throw new Error(`真实客流日期 ${serviceDate} 的面板缓存不可用`);

    primeRealPanelBundle(area, serviceDate, bundle);
    // 动态导入避免 modelDataCache -> realPassengerFlow 的静态循环；模块在应用启动
    // 后已完成初始化，这里只把完整 bundle 灌入两边共同读取的模型缓存。
    const { primeCachedRealPanels } = await import("@/utils/modelDataCache.js");
    primeCachedRealPanels(realDatasource(area, serviceDate), bundle);
    return { areaName: area, serviceDate, network, capabilities, bundle };
  })().catch((error) => {
    realWarmupPromises.delete(key);
    throw error;
  });
  realWarmupPromises.set(key, request);
  return request;
}

function writeMagic(view, magic) {
  for (let index = 0; index < magic.length; index += 1) view.setUint8(index, magic.charCodeAt(index));
}

const MAIN_THREAD_CHUNK_SIZE = 1024;
const STREET_INDEX_BUCKET_DEGREES = 0.02;

function yieldToMainThread() {
  if (globalThis.scheduler?.yield) return globalThis.scheduler.yield();
  return new Promise((resolve) => setTimeout(resolve, 0));
}

async function encodeTripEndsGrid(cells = [], cellSize = 100, cellStreetIndexes = []) {
  const buffer = new ArrayBuffer(18 + cells.length * 18);
  const view = new DataView(buffer);
  writeMagic(view, "PGRD");
  view.setUint16(4, 2, true);
  view.setUint32(6, cells.length, true);
  view.setFloat64(10, cellSize, true);
  let offset = 18;
  for (let index = 0; index < cells.length; index += 1) {
    const row = cells[index];
    view.setInt32(offset, Number(row?.[0]) || 0, true);
    view.setInt32(offset + 4, Number(row?.[1]) || 0, true);
    view.setUint32(offset + 8, Math.max(0, Math.round(Number(row?.[2]) || 0)), true);
    view.setUint32(offset + 12, Math.max(0, Math.round(Number(row?.[3]) || 0)), true);
    const streetIndex = Number(cellStreetIndexes[index]);
    view.setUint16(offset + 16, Number.isInteger(streetIndex) && streetIndex >= 0 ? Math.min(streetIndex, 0xfffe) : 0xffff, true);
    offset += 18;
    if (index > 0 && index % MAIN_THREAD_CHUNK_SIZE === 0) await yieldToMainThread();
  }
  return buffer;
}

async function encodeTripEndsOdGrid(cells = [], pairs = [], cellSize = 100, cellStreetIndexes = []) {
  const rows = [];
  for (let index = 0; index < pairs.length; index += 1) {
    const row = pairs[index];
    if (cells[row?.[0]] && cells[row?.[1]] && Number(row?.[2]) > 0) rows.push(row);
    if (index > 0 && index % MAIN_THREAD_CHUNK_SIZE === 0) await yieldToMainThread();
  }
  const buffer = new ArrayBuffer(18 + rows.length * 24);
  const view = new DataView(buffer);
  writeMagic(view, "PGOD");
  view.setUint16(4, 1, true);
  view.setUint32(6, rows.length, true);
  view.setFloat64(10, cellSize, true);
  let offset = 18;
  for (let index = 0; index < rows.length; index += 1) {
    const row = rows[index];
    const origin = cells[row[0]];
    const destination = cells[row[1]];
    view.setInt32(offset, Number(origin[0]) || 0, true);
    view.setInt32(offset + 4, Number(origin[1]) || 0, true);
    view.setInt32(offset + 8, Number(destination[0]) || 0, true);
    view.setInt32(offset + 12, Number(destination[1]) || 0, true);
    view.setUint32(offset + 16, Math.max(0, Math.round(Number(row[2]) || 0)), true);
    const originStreet = Number(cellStreetIndexes[Number(row[0])]);
    const destinationStreet = Number(cellStreetIndexes[Number(row[1])]);
    view.setUint16(offset + 20, Number.isInteger(originStreet) && originStreet >= 0 ? Math.min(originStreet, 0xfffe) : 0xffff, true);
    view.setUint16(offset + 22, Number.isInteger(destinationStreet) && destinationStreet >= 0 ? Math.min(destinationStreet, 0xfffe) : 0xffff, true);
    offset += 24;
    if (index > 0 && index % MAIN_THREAD_CHUNK_SIZE === 0) await yieldToMainThread();
  }
  return buffer;
}

function featureText(properties, ...keys) {
  for (const key of keys) {
    const value = properties?.[key];
    if (value !== null && value !== undefined && String(value).trim()) return String(value).trim();
  }
  return "";
}

function ringBounds(ring = []) {
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (const point of ring) {
    const x = Number(point?.[0]);
    const y = Number(point?.[1]);
    if (!Number.isFinite(x) || !Number.isFinite(y)) continue;
    minX = Math.min(minX, x);
    minY = Math.min(minY, y);
    maxX = Math.max(maxX, x);
    maxY = Math.max(maxY, y);
  }
  return Number.isFinite(minX) ? [minX, minY, maxX, maxY] : null;
}

function pointInRing(point, ring = []) {
  let inside = false;
  const x = Number(point?.[0]);
  const y = Number(point?.[1]);
  for (let index = 0, previous = ring.length - 1; index < ring.length; previous = index, index += 1) {
    const xi = Number(ring[index]?.[0]);
    const yi = Number(ring[index]?.[1]);
    const xj = Number(ring[previous]?.[0]);
    const yj = Number(ring[previous]?.[1]);
    if ((yi > y) !== (yj > y) && x < ((xj - xi) * (y - yi)) / ((yj - yi) || 1e-12) + xi) inside = !inside;
  }
  return inside;
}

function streetShapes(feature) {
  const geometry = feature?.geometry || {};
  const polygons = geometry.type === "Polygon"
    ? [geometry.coordinates]
    : geometry.type === "MultiPolygon" ? geometry.coordinates : [];
  return polygons.filter((polygon) => polygon?.[0]?.length).map((rings) => ({
    rings,
    bounds: ringBounds(rings[0]),
  }));
}

function pointInStreet(point, shapes = []) {
  return shapes.some(({ rings, bounds }) => {
    if (!bounds || point[0] < bounds[0] || point[0] > bounds[2] || point[1] < bounds[1] || point[1] > bounds[3]) return false;
    return pointInRing(point, rings[0]) && !rings.slice(1).some((ring) => pointInRing(point, ring));
  });
}

function streetBucketKey(x, y) {
  return `${Math.floor(x / STREET_INDEX_BUCKET_DEGREES)}:${Math.floor(y / STREET_INDEX_BUCKET_DEGREES)}`;
}

function buildStreetSpatialIndex(streets) {
  const buckets = new Map();
  const globalCandidates = new Set();
  streets.forEach((street, streetIndex) => {
    street.shapes.forEach(({ bounds }) => {
      if (!bounds) return;
      const minX = Math.floor(bounds[0] / STREET_INDEX_BUCKET_DEGREES);
      const maxX = Math.floor(bounds[2] / STREET_INDEX_BUCKET_DEGREES);
      const minY = Math.floor(bounds[1] / STREET_INDEX_BUCKET_DEGREES);
      const maxY = Math.floor(bounds[3] / STREET_INDEX_BUCKET_DEGREES);
      if ((maxX - minX + 1) * (maxY - minY + 1) > 4096) {
        globalCandidates.add(streetIndex);
        return;
      }
      for (let x = minX; x <= maxX; x += 1) {
        for (let y = minY; y <= maxY; y += 1) {
          const key = `${x}:${y}`;
          let candidates = buckets.get(key);
          if (!candidates) {
            candidates = new Set();
            buckets.set(key, candidates);
          }
          candidates.add(streetIndex);
        }
      }
    });
  });
  return { buckets, globalCandidates };
}

async function matchCellsToStreets(cells, cellSize, streets) {
  const { buckets, globalCandidates } = buildStreetSpatialIndex(streets);
  const indexes = new Int32Array(cells.length);
  indexes.fill(-1);
  for (let index = 0; index < cells.length; index += 1) {
    const cell = cells[index];
    const point = webMercatorToLngLat(
      (Number(cell?.[0]) + 0.5) * cellSize,
      (Number(cell?.[1]) + 0.5) * cellSize,
    );
    const localCandidates = buckets.get(streetBucketKey(point[0], point[1]));
    if (localCandidates || globalCandidates.size) {
      const candidates = globalCandidates.size
        ? new Set([...(localCandidates || []), ...globalCandidates])
        : localCandidates;
      for (const streetIndex of candidates) {
        if (pointInStreet(point, streets[streetIndex].shapes)) {
          indexes[index] = streetIndex;
          break;
        }
      }
    }
    if (index > 0 && index % MAIN_THREAD_CHUNK_SIZE === 0) await yieldToMainThread();
  }
  return indexes;
}

async function realStreetAggregation(datasource) {
  if (streetAggregationCache.has(datasource)) return streetAggregationCache.get(datasource);
  const promise = Promise.all([
    cachedAnalysis(datasource, "tripEnds"),
    getStreetsGeojson({ silentError: true }),
  ]).then(async ([payload, streetResponse]) => {
    const streetCollection = streetResponse?.data?.type === "FeatureCollection" ? streetResponse.data : streetResponse;
    const features = Array.isArray(streetCollection?.features) ? streetCollection.features : [];
    const streets = features.map((feature, index) => {
      const properties = feature?.properties || {};
      return {
        code: featureText(properties, "code", "CODE") || String(feature?.id ?? index),
        name: featureText(properties, "name", "Name", "NAME", "街道") || `街道${index + 1}`,
        district: featureText(properties, "district", "District", "行政区", "区县"),
        areaKm2: Number(properties.areaKm2 || properties.area_km2) || 0,
        origin: 0,
        destination: 0,
        shapes: streetShapes(feature),
      };
    });
    const cellSize = Number(payload.cellSizeMeters) || 100;
    const cells = payload.cells || [];
    const cellStreetIndexes = await matchCellsToStreets(cells, cellSize, streets);
    cells.forEach((cell, index) => {
      const street = streets[cellStreetIndexes[index]];
      if (!street) return;
      street.origin += Number(cell?.[2]) || 0;
      street.destination += Number(cell?.[3]) || 0;
    });
    const pairCounts = new Map();
    for (const pair of payload.pairs || []) {
      const origin = cellStreetIndexes[Number(pair?.[0])];
      const destination = cellStreetIndexes[Number(pair?.[1])];
      const count = Number(pair?.[2]) || 0;
      if (origin < 0 || destination < 0 || count <= 0) continue;
      const key = `${origin}:${destination}`;
      pairCounts.set(key, (pairCounts.get(key) || 0) + count);
    }
    const pairs = [...pairCounts.entries()].map(([key, count]) => {
      const [origin, destination] = key.split(":").map(Number);
      return [origin, destination, Math.round(count)];
    }).sort((left, right) => right[2] - left[2]);
    return {
      payload,
      cellStreetIndexes,
      pairs,
      streets: streets.map(({ shapes, ...street }) => street),
    };
  }).catch((error) => {
    streetAggregationCache.delete(datasource);
    throw error;
  });
  streetAggregationCache.set(datasource, promise);
  return promise;
}

export async function getRealTripEndsSummary(datasource) {
  const payload = await cachedAnalysis(datasource, "tripEnds");
  return {
    status: "ready",
    source: "real",
    generatedAt: `real:${payload.serviceDays || 0}`,
    cellSizeMeters: Number(payload.cellSizeMeters) || 100,
    cellCount: payload.cells?.length || 0,
    odPairCount: payload.pairs?.length || 0,
    serviceDays: payload.serviceDays || 0,
  };
}

export async function getRealTripEndsStreets(datasource) {
  const data = await realStreetAggregation(datasource);
  return {
    status: "ready",
    source: "real",
    spatialUnit: "street",
    streets: data.streets,
    totals: {
      origin: data.streets.reduce((sum, item) => sum + item.origin, 0),
      destination: data.streets.reduce((sum, item) => sum + item.destination, 0),
    },
  };
}

export async function getRealTripEndsOdStreets(datasource) {
  const data = await realStreetAggregation(datasource);
  return { status: "ready", source: "real", pairs: data.pairs };
}

export async function getRealTripEndsGrid(datasource) {
  const data = await realStreetAggregation(datasource);
  return await encodeTripEndsGrid(
    data.payload.cells || [],
    Number(data.payload.cellSizeMeters) || 100,
    data.cellStreetIndexes,
  );
}

export async function getRealTripEndsOdGrid(datasource) {
  const data = await realStreetAggregation(datasource);
  return await encodeTripEndsOdGrid(
    data.payload.cells || [],
    data.payload.pairs || [],
    Number(data.payload.cellSizeMeters) || 100,
    data.cellStreetIndexes,
  );
}

export function encodeCorridorLinks(segments = []) {
  const buffer = new ArrayBuffer(10 + segments.length * 26);
  const view = new DataView(buffer);
  writeMagic(view, "PCRD");
  view.setUint16(4, 2, true);
  view.setUint32(6, segments.length, true);
  let offset = 10;
  segments.forEach((row) => {
    view.setInt32(offset, Number(row?.[0]) || 0, true);
    view.setInt32(offset + 4, Number(row?.[1]) || 0, true);
    view.setInt32(offset + 8, Number(row?.[2]) || 0, true);
    view.setInt32(offset + 12, Number(row?.[3]) || 0, true);
    view.setUint16(offset + 16, Math.min(0xfffe, Math.max(0, Number(row?.[4]) || 0)), true);
    view.setUint16(offset + 18, Math.min(0xffff, Math.max(0, Number(row?.[5]) || 0)), true);
    view.setUint16(offset + 20, Math.min(0xffff, Math.max(0, Number(row?.[6]) || 0)), true);
    view.setUint32(offset + 22, Math.max(0, Math.round(Number(row?.[7]) || 0)), true);
    offset += 26;
  });
  return buffer;
}

export async function getRealCorridorSummary(datasource) {
  const payload = await cachedAnalysis(datasource, "corridor");
  return {
    status: "ready",
    source: "real",
    generatedAt: `${payload.cacheVersion || "real-corridor"}:${payload.serviceDays || 0}`,
    cacheVersion: payload.cacheVersion,
    segmentCount: payload.segments?.length || 0,
    lineCount: Number(payload.busLines) || 0,
    maxCoeff: Number(payload.maxCoeff) || 0,
    params: payload.params || {},
  };
}

export async function getRealCorridorNames(datasource) {
  const payload = await cachedAnalysis(datasource, "corridor");
  return { status: "ready", source: "real", names: payload.names || [], districts: payload.districts || [] };
}

export async function getRealCorridorLinks(datasource) {
  const payload = await cachedAnalysis(datasource, "corridor");
  return encodeCorridorLinks(payload.segments || []);
}

export async function getRealVehicleManifest(datasource) {
  return cachedAnalysis(datasource, "vehicle");
}

export async function realLocalResponse(loader) {
  return { data: await loader() };
}
