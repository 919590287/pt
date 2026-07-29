import request from "@/utils/request.js";
import { getStreetsGeojson } from "@/api/population.js";
import { getCachedRealData, ensureCachedRouteStops } from "@/utils/realDataCache.js";

export const REAL_DATASOURCE_PREFIX = "real::";
export const DEFAULT_REAL_AREA = "广州市";
export const REAL_AVERAGE_DATE = "average";
const REAL_DATE_SEPARATOR = "::service-date::";

const networkCache = new Map();
const analysisCache = new Map();
const streetAggregationCache = new Map();
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
  return text.replace(/[（(][^（）()]*[）)]\s*$/, "").trim() || text;
}

export function realLineGroupId(value = "") {
  return `real-line::${realLineGroupName(value)}`;
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
    return;
  }
  networkCache.delete(areaName);
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

function cachedAnalysis(datasource, endpoint) {
  const key = `${datasource}::${endpoint}`;
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

export function getRealPassengerFlowCapabilities(datasource) {
  return cachedAnalysis(datasource, "capabilities");
}

/**
 * 登录后空闲预热真实客流切换所需的最小数据集。
 * 与页面读取共用 networkCache / analysisCache，切换时不会再重复请求或重建线网。
 */
export function warmRealPassengerFlow(areaName = DEFAULT_REAL_AREA) {
  const datasource = realDatasource(areaName, REAL_AVERAGE_DATE);
  return Promise.all([
    getRealNetwork(datasource),
    getRealPassengerFlowCapabilities(datasource),
  ]).then(([network, capabilities]) => ({ network, capabilities }));
}

function writeMagic(view, magic) {
  for (let index = 0; index < magic.length; index += 1) view.setUint8(index, magic.charCodeAt(index));
}

function encodeTripEndsGrid(cells = [], cellSize = 100, cellStreetIndexes = []) {
  const buffer = new ArrayBuffer(18 + cells.length * 18);
  const view = new DataView(buffer);
  writeMagic(view, "PGRD");
  view.setUint16(4, 2, true);
  view.setUint32(6, cells.length, true);
  view.setFloat64(10, cellSize, true);
  let offset = 18;
  cells.forEach((row, index) => {
    view.setInt32(offset, Number(row?.[0]) || 0, true);
    view.setInt32(offset + 4, Number(row?.[1]) || 0, true);
    view.setUint32(offset + 8, Math.max(0, Math.round(Number(row?.[2]) || 0)), true);
    view.setUint32(offset + 12, Math.max(0, Math.round(Number(row?.[3]) || 0)), true);
    const streetIndex = Number(cellStreetIndexes[index]);
    view.setUint16(offset + 16, Number.isInteger(streetIndex) && streetIndex >= 0 ? Math.min(streetIndex, 0xfffe) : 0xffff, true);
    offset += 18;
  });
  return buffer;
}

function encodeTripEndsOdGrid(cells = [], pairs = [], cellSize = 100, cellStreetIndexes = []) {
  const rows = pairs.filter((row) => cells[row?.[0]] && cells[row?.[1]] && Number(row?.[2]) > 0);
  const buffer = new ArrayBuffer(18 + rows.length * 24);
  const view = new DataView(buffer);
  writeMagic(view, "PGOD");
  view.setUint16(4, 1, true);
  view.setUint32(6, rows.length, true);
  view.setFloat64(10, cellSize, true);
  let offset = 18;
  rows.forEach((row) => {
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
  });
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

async function realStreetAggregation(datasource) {
  if (streetAggregationCache.has(datasource)) return streetAggregationCache.get(datasource);
  const promise = Promise.all([
    cachedAnalysis(datasource, "tripEnds"),
    getStreetsGeojson({ silentError: true }),
  ]).then(([payload, streetResponse]) => {
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
    const cellStreetIndexes = cells.map((cell) => {
      const point = webMercatorToLngLat((Number(cell?.[0]) + 0.5) * cellSize, (Number(cell?.[1]) + 0.5) * cellSize);
      return streets.findIndex((street) => pointInStreet(point, street.shapes));
    });
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
  return encodeTripEndsGrid(data.payload.cells || [], Number(data.payload.cellSizeMeters) || 100, data.cellStreetIndexes);
}

export async function getRealTripEndsOdGrid(datasource) {
  const data = await realStreetAggregation(datasource);
  return encodeTripEndsOdGrid(
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
