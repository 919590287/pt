// 行政区裁剪的纯几何/GeoJSON 工具集。
// 同时被 index.vue（同步回退路径与各类派生计算）和 districtFilter.worker.js（后台裁剪）引用，
// 必须保持纯函数、零 DOM/Vue/地图引擎依赖，否则 worker 打包会拖入整个渲染栈。

export function valueOrEmpty(value) {
  if (value === undefined || value === null) return "";
  const text = String(value).trim();
  return text && text !== "[]" ? text : "";
}

export function firstAvailableValue(properties, keys) {
  for (const key of keys) {
    const value = valueOrEmpty(properties?.[key]);
    if (value) return value;
  }
  return "";
}

export function routeDataId(properties = {}) {
  return valueOrEmpty(properties.line_id || properties.lineId || properties.route_id || properties.routeId);
}

export function routeStopSequence(properties = {}) {
  const value = Number(firstAvailableValue(properties, ["seq"]));
  return Number.isFinite(value) ? value : Number.MAX_SAFE_INTEGER;
}

export function routeMatchKeys(properties = {}) {
  const lineId = valueOrEmpty(properties.line_id || properties.lineId);
  const dir = valueOrEmpty(properties.dir || properties.direction || properties.Direction);
  const routeId = valueOrEmpty(properties.route_id || properties.routeId);
  const keys = [];
  if (lineId && dir && routeId) keys.push(`line-dir-route:${lineId}|${dir}|${routeId}`);
  if (lineId && dir) keys.push(`line-dir:${lineId}|${dir}`);
  if (routeId) keys.push(`route:${routeId}`);
  if (lineId) keys.push(`line:${lineId}`);
  const fallbackId = routeDataId(properties);
  if (fallbackId) keys.push(`id:${fallbackId}`);
  return [...new Set(keys)];
}

export function collectionFeatures(collection) {
  return Array.isArray(collection?.features) ? collection.features : [];
}

export function pointCoordinates(geometry) {
  if (geometry?.type !== "Point" || !Array.isArray(geometry.coordinates)) return null;
  const [lng, lat] = geometry.coordinates;
  return Number.isFinite(Number(lng)) && Number.isFinite(Number(lat)) ? [Number(lng), Number(lat)] : null;
}

export function validLngLat(coordinate) {
  if (!Array.isArray(coordinate) || coordinate.length < 2) return null;
  const lng = Number(coordinate[0]);
  const lat = Number(coordinate[1]);
  return Number.isFinite(lng) && Number.isFinite(lat) ? [lng, lat] : null;
}

export function lineCoordinatePaths(geometry) {
  if (!geometry?.coordinates) return [];
  if (geometry.type === "LineString") return [geometry.coordinates];
  if (geometry.type === "MultiLineString") return geometry.coordinates;
  return [];
}

export function pointsAlmostEqual(left, right) {
  if (!Array.isArray(left) || !Array.isArray(right)) return false;
  return Math.abs(Number(left[0]) - Number(right[0])) <= 1e-9
    && Math.abs(Number(left[1]) - Number(right[1])) <= 1e-9;
}

export function pointAlongSegment(start, end, ratio) {
  const t = Number(ratio);
  if (!Number.isFinite(t)) return [start[0], start[1]];
  return [
    start[0] + (end[0] - start[0]) * t,
    start[1] + (end[1] - start[1]) * t,
  ];
}


export function boundsContainPoint(bounds, point) {
  return Array.isArray(bounds)
    && point[0] >= bounds[0]
    && point[0] <= bounds[2]
    && point[1] >= bounds[1]
    && point[1] <= bounds[3];
}

export function expandCoordinateBounds(value, bounds) {
  if (!Array.isArray(value)) return;
  if (value.length >= 2 && Number.isFinite(Number(value[0])) && Number.isFinite(Number(value[1]))) {
    const lng = Number(value[0]);
    const lat = Number(value[1]);
    bounds[0] = Math.min(bounds[0], lng);
    bounds[1] = Math.min(bounds[1], lat);
    bounds[2] = Math.max(bounds[2], lng);
    bounds[3] = Math.max(bounds[3], lat);
    return;
  }
  value.forEach((item) => expandCoordinateBounds(item, bounds));
}

export function expandGeometryBounds(geometry, bounds) {
  if (!geometry?.coordinates) return;
  expandCoordinateBounds(geometry.coordinates, bounds);
}

export function featureCollectionBounds(features = []) {
  const bounds = [Infinity, Infinity, -Infinity, -Infinity];
  features.forEach((feature) => expandGeometryBounds(feature?.geometry, bounds));
  return Number.isFinite(bounds[0]) ? bounds : null;
}

export function featureCollectionFromFeatures(features = []) {
  return {
    type: "FeatureCollection",
    features,
    featureCount: features.length,
    bounds: featureCollectionBounds(features),
  };
}

export function pointOnSegment(point, start, end) {
  const cross = (point[1] - start[1]) * (end[0] - start[0]) - (point[0] - start[0]) * (end[1] - start[1]);
  if (Math.abs(cross) > 1e-12) return false;
  return point[0] <= Math.max(start[0], end[0]) + 1e-12
    && point[0] + 1e-12 >= Math.min(start[0], end[0])
    && point[1] <= Math.max(start[1], end[1]) + 1e-12
    && point[1] + 1e-12 >= Math.min(start[1], end[1]);
}

export function pointInRing(point, ring) {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i, i += 1) {
    const current = ring[i];
    const previous = ring[j];
    if (pointOnSegment(point, previous, current)) return true;
    const intersects = current[1] > point[1] !== previous[1] > point[1]
      && point[0] < ((previous[0] - current[0]) * (point[1] - current[1])) / (previous[1] - current[1]) + current[0];
    if (intersects) inside = !inside;
  }
  return inside;
}

export function pointInPolygonRings(point, rings) {
  if (!rings.length || !pointInRing(point, rings[0])) return false;
  for (let index = 1; index < rings.length; index += 1) {
    if (pointInRing(point, rings[index])) return false;
  }
  return true;
}

export function pointInRangeContext(coordinate, context) {
  if (!coordinate || !boundsContainPoint(context.bounds, coordinate)) return false;
  return context.polygons.some((rings) => pointInPolygonRings(coordinate, rings));
}

export function pointFeatureInRange(feature, context) {
  const coordinate = pointCoordinates(feature?.geometry);
  return coordinate ? pointInRangeContext(coordinate, context) : false;
}



// ---- 行政区边界裁剪 -------------------------------------------------------
// 线路按行政区边界几何裁剪：区内段进正常图层（彩色/橙色高亮），区外段由灰色底图透出，
// 因此配色切换点恰好落在分界线上（此前是切在"最后一个区内站点"上，颜色会提前变化）。
// 朴素做法要拿每条线段去比对整圈边界（线路点数 × 边界点数），这里先把边界边按 bbox 打进
// 均匀网格，线段只与同格候选边求交。

const RATIO_EPSILON = 1e-12;
const BOUNDARY_GRID_MAX_COLUMNS = 128;
const EMPTY_RATIOS = [];

function polygonBoundaryEdges(polygons = []) {
  const edges = [];
  for (const rings of polygons) {
    for (const ring of rings || []) {
      if (!Array.isArray(ring) || ring.length < 2) continue;
      for (let index = 1; index < ring.length; index += 1) edges.push([ring[index - 1], ring[index]]);
      if (!pointsAlmostEqual(ring[0], ring[ring.length - 1])) edges.push([ring[ring.length - 1], ring[0]]);
    }
  }
  return edges;
}

export function createBoundaryIndex(context) {
  const edges = polygonBoundaryEdges(context?.polygons || []);
  if (!edges.length) return null;
  const bounds = [Infinity, Infinity, -Infinity, -Infinity];
  for (const [start, end] of edges) {
    bounds[0] = Math.min(bounds[0], start[0], end[0]);
    bounds[1] = Math.min(bounds[1], start[1], end[1]);
    bounds[2] = Math.max(bounds[2], start[0], end[0]);
    bounds[3] = Math.max(bounds[3], start[1], end[1]);
  }
  const columns = Math.max(1, Math.min(BOUNDARY_GRID_MAX_COLUMNS, Math.round(Math.sqrt(edges.length / 4)) || 1));
  const spanX = Math.max(bounds[2] - bounds[0], 1e-9);
  const spanY = Math.max(bounds[3] - bounds[1], 1e-9);
  const columnOf = (x) => Math.max(0, Math.min(columns - 1, Math.floor(((x - bounds[0]) / spanX) * columns)));
  const rowOf = (y) => Math.max(0, Math.min(columns - 1, Math.floor(((y - bounds[1]) / spanY) * columns)));
  const cells = new Array(columns * columns).fill(null);
  edges.forEach(([start, end], edgeIndex) => {
    const minRow = rowOf(Math.min(start[1], end[1]));
    const maxRow = rowOf(Math.max(start[1], end[1]));
    const minColumn = columnOf(Math.min(start[0], end[0]));
    const maxColumn = columnOf(Math.max(start[0], end[0]));
    for (let row = minRow; row <= maxRow; row += 1) {
      for (let column = minColumn; column <= maxColumn; column += 1) {
        const cell = row * columns + column;
        if (!cells[cell]) cells[cell] = [];
        cells[cell].push(edgeIndex);
      }
    }
  });
  // visited/stamp：每次查询换一个 stamp，免去候选边去重时反复新建 Set
  return { edges, cells, columns, bounds, columnOf, rowOf, visited: new Int32Array(edges.length), stamp: 0 };
}

// 线段 start→end 与边界边的交点参数 t∈[0,1]；平行或共线时返回 -1，交由子段中点判定兜底
function boundaryCrossRatio(start, end, edgeStart, edgeEnd) {
  const rx = end[0] - start[0];
  const ry = end[1] - start[1];
  const sx = edgeEnd[0] - edgeStart[0];
  const sy = edgeEnd[1] - edgeStart[1];
  const denominator = rx * sy - ry * sx;
  if (Math.abs(denominator) < 1e-18) return -1;
  const dx = edgeStart[0] - start[0];
  const dy = edgeStart[1] - start[1];
  const t = (dx * sy - dy * sx) / denominator;
  const u = (dx * ry - dy * rx) / denominator;
  if (t < 0 || t > 1 || u < 0 || u > 1) return -1;
  return t;
}

function boundaryCrossingRatios(start, end, index) {
  const minX = Math.min(start[0], end[0]);
  const maxX = Math.max(start[0], end[0]);
  const minY = Math.min(start[1], end[1]);
  const maxY = Math.max(start[1], end[1]);
  const bounds = index.bounds;
  if (maxX < bounds[0] || minX > bounds[2] || maxY < bounds[1] || minY > bounds[3]) return EMPTY_RATIOS;
  index.stamp += 1;
  const stamp = index.stamp;
  const ratios = [];
  const maxRow = index.rowOf(maxY);
  const maxColumn = index.columnOf(maxX);
  for (let row = index.rowOf(minY); row <= maxRow; row += 1) {
    for (let column = index.columnOf(minX); column <= maxColumn; column += 1) {
      const cell = index.cells[row * index.columns + column];
      if (!cell) continue;
      for (const edgeIndex of cell) {
        if (index.visited[edgeIndex] === stamp) continue;
        index.visited[edgeIndex] = stamp;
        const ratio = boundaryCrossRatio(start, end, index.edges[edgeIndex][0], index.edges[edgeIndex][1]);
        if (ratio >= 0) ratios.push(ratio);
      }
    }
  }
  return ratios.sort((left, right) => left - right);
}

function pushCoordinate(coordinates, coordinate) {
  if (!coordinates.length || !pointsAlmostEqual(coordinates[coordinates.length - 1], coordinate)) coordinates.push(coordinate);
}

// 逐段在交点处切开，每个子段用中点做点在多边形内判定 —— 不靠"进出计数"累积状态，
// 相切、顶点穿越等退化情形不会让后续整条线的内外判定翻转。
export function clipPathToDistrict(path, context, index) {
  const spans = [];
  let current = pointInRangeContext(path[0], context) ? [path[0]] : null;
  for (let vertex = 1; vertex < path.length; vertex += 1) {
    const start = path[vertex - 1];
    const end = path[vertex];
    const ratios = boundaryCrossingRatios(start, end, index);
    if (!ratios.length) {
      if (current) pushCoordinate(current, end);
      continue;
    }
    let cursor = start;
    let cursorRatio = 0;
    for (let step = 0; step <= ratios.length; step += 1) {
      const ratio = step < ratios.length ? ratios[step] : 1;
      if (ratio - cursorRatio <= RATIO_EPSILON) continue;
      const stop = ratio >= 1 ? end : pointAlongSegment(start, end, ratio);
      const middle = pointAlongSegment(start, end, (cursorRatio + ratio) / 2);
      if (pointInRangeContext(middle, context)) {
        if (!current) current = [cursor];
        else pushCoordinate(current, cursor);
        pushCoordinate(current, stop);
      } else if (current) {
        if (current.length >= 2) spans.push(current);
        current = null;
      }
      cursor = stop;
      cursorRatio = ratio;
    }
  }
  if (current && current.length >= 2) spans.push(current);
  return spans;
}

export function clipLineFeatureToDistrict(feature, context, index) {
  const paths = lineCoordinatePaths(feature?.geometry)
    .map((path) => (Array.isArray(path) ? path.map(validLngLat).filter(Boolean) : []))
    .filter((path) => path.length >= 2);
  const features = [];
  for (const path of paths) {
    for (const coordinates of clipPathToDistrict(path, context, index)) {
      features.push({
        type: "Feature",
        id: features.length ? `${feature?.id || feature?.properties?._lineKey || "line"}-${features.length}` : feature?.id,
        geometry: {
          type: "LineString",
          coordinates,
        },
        properties: {
          ...(feature?.properties || {}),
        },
      });
    }
  }
  return features;
}

function routeKeysOfCollection(collection) {
  const keys = new Set();
  for (const feature of collectionFeatures(collection)) {
    for (const key of routeMatchKeys(feature?.properties || {})) keys.add(key);
  }
  return keys;
}

// 线网归属仍按"在本区有停靠站"判定（只过境不停靠的线路不计入本区），几何则按边界裁剪。
export function districtClippedLineFeatureCollection(collection, routeStopsInRange, context) {
  const index = createBoundaryIndex(context);
  if (!index) return featureCollectionFromFeatures([]);
  const servedKeys = routeKeysOfCollection(routeStopsInRange);
  const features = [];
  for (const feature of collectionFeatures(collection)) {
    if (!routeMatchKeys(feature?.properties || {}).some((key) => servedKeys.has(key))) continue;
    features.push(...clipLineFeatureToDistrict(feature, context, index));
  }
  return featureCollectionFromFeatures(features);
}

// worker 侧一次性完成四个数据集的区划过滤（bounds/polygons 由主线程传入，边界网格索引本地重建）
export function filterCollectionsByDistrict(collections, context) {
  const routeStopsInRange = featureCollectionFromFeatures(
    collectionFeatures(collections.routeStops).filter((feature) => pointFeatureInRange(feature, context)),
  );
  return {
    lines: districtClippedLineFeatureCollection(collections.lines, routeStopsInRange, context),
    stations: featureCollectionFromFeatures(
      collectionFeatures(collections.stations).filter((feature) => pointFeatureInRange(feature, context)),
    ),
    routeStops: routeStopsInRange,
    depots: featureCollectionFromFeatures(
      collectionFeatures(collections.depots).filter((feature) => pointFeatureInRange(feature, context)),
    ),
  };
}
