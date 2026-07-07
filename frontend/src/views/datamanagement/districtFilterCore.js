// 行政区裁剪的纯几何/GeoJSON 工具集。
// 同时被 index.vue（同步回退路径与各类派生计算）和 districtFilter.worker.js（后台裁剪）引用，
// 必须保持纯函数、零 DOM/Vue/地图引擎依赖，否则 worker 打包会拖入整个渲染栈。

const EARTH_RADIUS = 6378137.0;

// 与 mymap/main/MyMap.js 的 lngLatToWebMercator 同公式：裁剪只用相对距离，
// 本地实现避免 worker 依赖地图引擎入口。
export function lngLatToWebMercator(lng, lat) {
  const limitedLat = Math.max(-85.05112878, Math.min(85.05112878, Number(lat) || 0));
  const x = (EARTH_RADIUS * (Number(lng) || 0) * Math.PI) / 180;
  const y = EARTH_RADIUS * Math.log(Math.tan(Math.PI / 4 + (limitedLat * Math.PI) / 360));
  return [x, y];
}

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



export function projectWebMercatorPointToSegment(point, start, end) {
  const dx = end[0] - start[0];
  const dy = end[1] - start[1];
  const lengthSquared = dx * dx + dy * dy;
  if (!lengthSquared) {
    return {
      point: start,
      ratio: 0,
      segmentLength: 0,
    };
  }
  const ratio = Math.max(0, Math.min(1, ((point[0] - start[0]) * dx + (point[1] - start[1]) * dy) / lengthSquared));
  return {
    point: [start[0] + dx * ratio, start[1] + dy * ratio],
    ratio,
    segmentLength: Math.sqrt(lengthSquared),
  };
}

export function projectPointToLinePaths(paths, coordinate) {
  const point = validLngLat(coordinate);
  if (!point) return null;
  const projectedPoint = lngLatToWebMercator(point[0], point[1]);
  let nearest = null;
  paths.forEach((path, pathIndex) => {
    let cumulative = 0;
    for (let segmentIndex = 1; segmentIndex < path.length; segmentIndex += 1) {
      const startLngLat = path[segmentIndex - 1];
      const endLngLat = path[segmentIndex];
      const start = lngLatToWebMercator(startLngLat[0], startLngLat[1]);
      const end = lngLatToWebMercator(endLngLat[0], endLngLat[1]);
      const projection = projectWebMercatorPointToSegment(projectedPoint, start, end);
      const distance = Math.hypot(projectedPoint[0] - projection.point[0], projectedPoint[1] - projection.point[1]);
      const distanceAlong = cumulative + projection.segmentLength * projection.ratio;
      if (!nearest || distance < nearest.distance) {
        nearest = {
          distance,
          pathIndex,
          segmentIndex,
          ratio: projection.ratio,
          distanceAlong,
          coordinate: pointAlongSegment(startLngLat, endLngLat, projection.ratio),
        };
      }
      cumulative += projection.segmentLength;
    }
  });
  return nearest;
}

export function sliceLinePathBetweenProjections(path, firstProjection, secondProjection) {
  const forward = firstProjection.distanceAlong <= secondProjection.distanceAlong;
  const startProjection = forward ? firstProjection : secondProjection;
  const endProjection = forward ? secondProjection : firstProjection;
  const coordinates = [startProjection.coordinate];
  const startVertexIndex = startProjection.ratio >= 1 - 1e-9 ? startProjection.segmentIndex + 1 : startProjection.segmentIndex;
  const endVertexIndex = endProjection.ratio >= 1 - 1e-9 ? endProjection.segmentIndex : endProjection.segmentIndex - 1;
  for (let index = startVertexIndex; index <= endVertexIndex; index += 1) {
    const coordinate = path[index];
    if (coordinate && !pointsAlmostEqual(coordinates[coordinates.length - 1], coordinate)) coordinates.push(coordinate);
  }
  if (!pointsAlmostEqual(coordinates[coordinates.length - 1], endProjection.coordinate)) {
    coordinates.push(endProjection.coordinate);
  }
  return forward ? coordinates : coordinates.reverse();
}

export function lineCoordinatesBetweenStops(paths, start, end) {
  const startProjection = projectPointToLinePaths(paths, start);
  const endProjection = projectPointToLinePaths(paths, end);
  if (!startProjection || !endProjection || startProjection.pathIndex !== endProjection.pathIndex) return [];
  return sliceLinePathBetweenProjections(paths[startProjection.pathIndex], startProjection, endProjection);
}

export function trimLineFeatureToStationRuns(feature, runs = []) {
  const paths = lineCoordinatePaths(feature?.geometry)
    .map((path) => (Array.isArray(path) ? path.map(validLngLat).filter(Boolean) : []))
    .filter((path) => path.length >= 2);
  if (!paths.length) return [];
  const features = [];
  runs.forEach((run, runIndex) => {
    const coordinates = lineCoordinatesBetweenStops(paths, run.start, run.end);
    if (coordinates.length < 2) return;
    features.push({
      type: "Feature",
      id: runIndex ? `${feature?.id || feature?.properties?._lineKey || "line"}-${runIndex}` : feature?.id,
      geometry: {
        type: "LineString",
        coordinates,
      },
      properties: {
        ...(feature?.properties || {}),
      },
    });
  });
  return features;
}

export function inRangeArrivalLineRuns(stops = []) {
  // District view keeps only the first contiguous in-range span for each route.
  // Once the sequence leaves the current district, later re-entry spans are not
  // drawn, otherwise the map visually bridges across the missing out-of-range stop.
  const sortedStops = [...stops].sort((left, right) => left.sequence - right.sequence || left.sourceIndex - right.sourceIndex);
  const runs = [];
  let hasVisibleSpan = false;
  for (let index = 1; index < sortedStops.length; index += 1) {
    const previous = sortedStops[index - 1];
    const current = sortedStops[index];
    if (previous.inRange && current.inRange && !pointsAlmostEqual(previous.coordinate, current.coordinate)) {
      runs.push({
        start: previous.coordinate,
        end: current.coordinate,
      });
      hasVisibleSpan = true;
      continue;
    }
    if (previous.inRange || current.inRange) {
      hasVisibleSpan = true;
    }
    if (hasVisibleSpan && !current.inRange) {
      break;
    }
  }
  return runs;
}

export function stationRunsByRouteKey(routeStops, context) {
  const groups = new Map();
  collectionFeatures(routeStops).forEach((feature, sourceIndex) => {
    const coordinate = pointCoordinates(feature?.geometry);
    if (!coordinate) return;
    const properties = feature?.properties || {};
    const keys = routeMatchKeys(properties);
    if (!keys.length) return;
    const stop = {
      coordinate,
      inRange: pointInRangeContext(coordinate, context),
      sequence: routeStopSequence(properties),
      sourceIndex,
    };
    keys.forEach((key) => {
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(stop);
    });
  });

  const runsByRouteKey = new Map();
  groups.forEach((stops, key) => {
    const runs = inRangeArrivalLineRuns(stops);
    if (runs.length) runsByRouteKey.set(key, runs);
  });
  return runsByRouteKey;
}

export function stationRunsForLineFeature(feature, runsByRouteKey) {
  const properties = feature?.properties || {};
  for (const key of routeMatchKeys(properties)) {
    const runs = runsByRouteKey.get(key);
    if (runs?.length) return runs;
  }
  return [];
}

export function stationScopedLineFeatureCollection(collection, routeStops, context) {
  const runsByRouteKey = stationRunsByRouteKey(routeStops, context);
  const features = [];
  for (const feature of collectionFeatures(collection)) {
    const routeRuns = stationRunsForLineFeature(feature, runsByRouteKey);
    if (!routeRuns.length) continue;
    features.push(...trimLineFeatureToStationRuns(feature, routeRuns));
  }
  return featureCollectionFromFeatures(features);
}

// worker 侧一次性完成四个数据集的区划过滤（bounds/polygons 由主线程传入，boundarySegments 本地重建）
export function filterCollectionsByDistrict(collections, context) {
  const routeStopsInRange = featureCollectionFromFeatures(
    collectionFeatures(collections.routeStops).filter((feature) => pointFeatureInRange(feature, context)),
  );
  return {
    lines: stationScopedLineFeatureCollection(collections.lines, collections.routeStops, context),
    stations: featureCollectionFromFeatures(
      collectionFeatures(collections.stations).filter((feature) => pointFeatureInRange(feature, context)),
    ),
    routeStops: routeStopsInRange,
    depots: featureCollectionFromFeatures(
      collectionFeatures(collections.depots).filter((feature) => pointFeatureInRange(feature, context)),
    ),
  };
}
