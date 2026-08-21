const DISPLAY_RANGE_ALL = "全市";

export function emptyFeatureCollection() {
  return { type: "FeatureCollection", features: [] };
}

/** 全平台行政区显示范围的统一描边虚线样式（dark底图下为白色，亮色底图下为黑色）。 */
export function adminDistrictOutlineStyle(isDark = false) {
  return {
    layout: {
      "line-join": "round",
      "line-cap": "butt",
    },
    paint: {
      "line-color": isDark ? "#ffffff" : "#000000",
      "line-width": ["interpolate", ["linear"], ["zoom"], 8, 1.4, 12, 2.1, 15, 2.8],
      "line-opacity": isDark ? 0.88 : 0.86,
      "line-dasharray": [2.5, 2.5],
    },
  };
}

/** 把选中行政区面转为单独描边数据源，便于保持虚线间隔稳定。 */
export function districtOutlineFeatureCollection(context) {
  const geometry = districtOutlineGeometry(context?.feature?.geometry);
  return geometry
    ? {
        type: "FeatureCollection",
        features: [{
          type: "Feature",
          id: context?.feature?.id || "active-display-range",
          geometry,
          properties: { ...(context?.feature?.properties || {}) },
        }],
      }
    : emptyFeatureCollection();
}

export function districtOutlineGeometry(geometry) {
  if (!geometry) return null;
  if (geometry.type === "LineString" || geometry.type === "MultiLineString") return geometry;
  const rings = [];
  if (geometry.type === "Polygon") {
    (geometry.coordinates || []).forEach((ring) => {
      if (Array.isArray(ring) && ring.length >= 2) rings.push(ring);
    });
  } else if (geometry.type === "MultiPolygon") {
    (geometry.coordinates || []).forEach((polygon) => {
      (Array.isArray(polygon) ? polygon : []).forEach((ring) => {
        if (Array.isArray(ring) && ring.length >= 2) rings.push(ring);
      });
    });
  }
  if (!rings.length) return null;
  return rings.length === 1
    ? { type: "LineString", coordinates: rings[0] }
    : { type: "MultiLineString", coordinates: rings };
}

export function normalizeAdminDistrictCollection(collection) {
  const features = Array.isArray(collection?.features) ? collection.features : [];
  return {
    type: "FeatureCollection",
    features: features
      .map((feature, index) => {
        const properties = feature?.properties || {};
        const name = districtFeatureName(feature);
        return {
          type: "Feature",
          id: feature?.id || `district-${index}`,
          geometry: feature?.geometry || null,
          properties: {
            ...properties,
            _districtName: name,
          },
        };
      })
      .filter((feature) => feature.geometry && feature.properties._districtName),
  };
}

export function districtNamesFromCollection(collection) {
  const names = [];
  const seen = new Set();
  for (const feature of collection?.features || []) {
    const name = districtFeatureName(feature);
    if (!name || seen.has(name)) continue;
    seen.add(name);
    names.push(name);
  }
  return names;
}

export function districtFeatureName(feature) {
  const properties = feature?.properties || {};
  return String(
    properties._districtName ||
      properties.Name ||
      properties.name ||
      properties.NAME ||
      properties["名称"] ||
      properties["区名"] ||
      properties["行政区"] ||
      properties["行政区名"] ||
      properties["区县"] ||
      properties["县区"] ||
      properties.district ||
      properties.District ||
      properties.AdminName ||
      "",
  ).trim();
}

export function activeDistrictContext(collection, rangeName, allName = DISPLAY_RANGE_ALL) {
  if (!rangeName || rangeName === allName) return null;
  const feature = (collection?.features || []).find((item) => districtFeatureName(item) === rangeName);
  if (!feature?.geometry) return null;
  const polygons = geometryPolygonRings(feature.geometry);
  if (!polygons.length) return null;
  const bounds = geometryBounds(feature.geometry);
  if (!bounds) return null;
  return {
    name: rangeName,
    feature,
    polygons,
    bounds,
    boundarySegments: buildBoundarySegments(polygons),
  };
}

export function pointInDistrictContext(coordinate, context) {
  if (!coordinate || !context || !boundsContainPoint(context.bounds, coordinate)) return false;
  return context.polygons.some((rings) => pointInPolygonRings(coordinate, rings));
}

export function segmentIntersectsDistrictContext(start, end, context) {
  if (!start || !end || !context) return false;
  const startInside = pointInDistrictContext(start, context);
  const endInside = pointInDistrictContext(end, context);
  if (startInside || endInside) return true;
  const currentBounds = segmentBounds(start, end);
  if (!boundsIntersect(currentBounds, context.bounds)) return false;
  for (const segment of context.boundarySegments || []) {
    if (!boundsIntersect(currentBounds, segment.bounds)) continue;
    if (segmentsIntersect(start, end, segment.start, segment.end)) return true;
  }
  return false;
}

export function clipSegmentToDistrictContext(start, end, context) {
  const from = validLngLat(start);
  const to = validLngLat(end);
  if (!from || !to || pointsAlmostEqual(from, to)) return [];
  if (!context) return [[from, to]];
  return lineSegmentInsideDistrictIntervals(from, to, context)
    .map(([startT, endT]) => [pointAlongSegment(from, to, startT), pointAlongSegment(from, to, endT)])
    .filter(([left, right]) => !pointsAlmostEqual(left, right));
}

export function clipLineStringToDistrictContext(path, context) {
  const coordinates = (Array.isArray(path) ? path : []).map(validLngLat).filter(Boolean);
  if (coordinates.length < 2) return [];
  if (!context) return [coordinates];
  const clippedPaths = [];
  let currentPath = [];

  for (let index = 1; index < coordinates.length; index += 1) {
    const clippedSegments = clipSegmentToDistrictContext(coordinates[index - 1], coordinates[index], context);
    if (!clippedSegments.length) {
      if (currentPath.length >= 2) clippedPaths.push(currentPath);
      currentPath = [];
      continue;
    }

    clippedSegments.forEach(([start, end], segmentIndex) => {
      if (!currentPath.length) {
        currentPath = [start];
      } else if (!pointsAlmostEqual(currentPath[currentPath.length - 1], start)) {
        if (currentPath.length >= 2) clippedPaths.push(currentPath);
        currentPath = [start];
      }
      if (!pointsAlmostEqual(currentPath[currentPath.length - 1], end)) {
        currentPath.push(end);
      }
      if (segmentIndex < clippedSegments.length - 1) {
        if (currentPath.length >= 2) clippedPaths.push(currentPath);
        currentPath = [];
      }
    });
  }

  if (currentPath.length >= 2) clippedPaths.push(currentPath);
  return clippedPaths;
}

function lineSegmentInsideDistrictIntervals(start, end, context) {
  const startInside = pointInDistrictContext(start, context);
  const endInside = pointInDistrictContext(end, context);
  if (!startInside && !endInside && !segmentIntersectsDistrictContext(start, end, context)) return [];

  const tValues = [0, 1];
  const currentBounds = segmentBounds(start, end);
  for (const segment of context.boundarySegments || []) {
    if (!boundsIntersect(currentBounds, segment.bounds)) continue;
    tValues.push(...segmentIntersectionParameters(start, end, segment.start, segment.end));
  }

  const sorted = uniqueSortedNumbers(tValues);
  const intervals = [];
  for (let index = 0; index < sorted.length - 1; index += 1) {
    const startT = sorted[index];
    const endT = sorted[index + 1];
    if (endT - startT <= 1e-9) continue;
    const midpoint = pointAlongSegment(start, end, (startT + endT) / 2);
    if (pointInDistrictContext(midpoint, context)) {
      intervals.push([startT, endT]);
    }
  }
  return intervals;
}

function geometryPolygonRings(geometry) {
  if (!geometry) return [];
  if (geometry.type === "Polygon") return [normalizePolygonRings(geometry.coordinates)].filter((rings) => rings.length);
  if (geometry.type === "MultiPolygon") {
    return (geometry.coordinates || []).map(normalizePolygonRings).filter((rings) => rings.length);
  }
  if (geometry.type === "Feature") return geometryPolygonRings(geometry.geometry);
  if (geometry.type === "FeatureCollection") {
    return (geometry.features || []).flatMap((feature) => geometryPolygonRings(feature.geometry));
  }
  return [];
}

function normalizePolygonRings(rings) {
  return (Array.isArray(rings) ? rings : [])
    .map((ring) => (Array.isArray(ring) ? ring.map(validLngLat).filter(Boolean) : []))
    .filter((ring) => ring.length >= 3);
}

function geometryBounds(geometry) {
  const points = geometryCoordinates(geometry);
  if (!points.length) return null;
  let minLng = Infinity;
  let minLat = Infinity;
  let maxLng = -Infinity;
  let maxLat = -Infinity;
  points.forEach(([lng, lat]) => {
    minLng = Math.min(minLng, lng);
    minLat = Math.min(minLat, lat);
    maxLng = Math.max(maxLng, lng);
    maxLat = Math.max(maxLat, lat);
  });
  return [minLng, minLat, maxLng, maxLat];
}

function geometryCoordinates(geometry) {
  if (!geometry) return [];
  if (geometry.type === "Point") return [validLngLat(geometry.coordinates)].filter(Boolean);
  if (geometry.type === "LineString" || geometry.type === "MultiPoint") {
    return (geometry.coordinates || []).map(validLngLat).filter(Boolean);
  }
  if (geometry.type === "Polygon" || geometry.type === "MultiLineString") {
    return (geometry.coordinates || []).flatMap((ring) => ring.map(validLngLat).filter(Boolean));
  }
  if (geometry.type === "MultiPolygon") {
    return (geometry.coordinates || []).flatMap((polygon) => polygon.flatMap((ring) => ring.map(validLngLat).filter(Boolean)));
  }
  if (geometry.type === "Feature") return geometryCoordinates(geometry.geometry);
  if (geometry.type === "FeatureCollection") return (geometry.features || []).flatMap((feature) => geometryCoordinates(feature.geometry));
  return [];
}

function validLngLat(coordinate) {
  if (!Array.isArray(coordinate) || coordinate.length < 2) return null;
  const lng = Number(coordinate[0]);
  const lat = Number(coordinate[1]);
  if (!Number.isFinite(lng) || !Number.isFinite(lat)) return null;
  return [lng, lat];
}

function boundsContainPoint(bounds, point) {
  return Boolean(
    bounds &&
      point &&
      Number(point[0]) >= bounds[0] &&
      Number(point[0]) <= bounds[2] &&
      Number(point[1]) >= bounds[1] &&
      Number(point[1]) <= bounds[3]
  );
}

function boundsIntersect(left, right) {
  if (!left || !right) return false;
  return left[0] <= right[2] && left[2] >= right[0] && left[1] <= right[3] && left[3] >= right[1];
}

function pointInPolygonRings(point, rings) {
  if (!rings?.length || !pointInRing(point, rings[0])) return false;
  return !rings.slice(1).some((ring) => pointInRing(point, ring));
}

function pointInRing(point, ring) {
  let inside = false;
  const x = Number(point[0]);
  const y = Number(point[1]);
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i, i += 1) {
    const xi = Number(ring[i][0]);
    const yi = Number(ring[i][1]);
    const xj = Number(ring[j][0]);
    const yj = Number(ring[j][1]);
    const crosses = yi > y !== yj > y;
    if (crosses && x < ((xj - xi) * (y - yi)) / ((yj - yi) || 1e-12) + xi) {
      inside = !inside;
    }
  }
  return inside;
}

function buildBoundarySegments(polygons = []) {
  const segments = [];
  for (const rings of polygons) {
    for (const ring of rings) {
      segments.push(...polygonRingSegments(ring));
    }
  }
  return segments;
}

function polygonRingSegments(ring) {
  const coordinates = Array.isArray(ring) ? ring.map(validLngLat).filter(Boolean) : [];
  const segments = [];
  for (let index = 1; index < coordinates.length; index += 1) {
    segments.push({
      start: coordinates[index - 1],
      end: coordinates[index],
      bounds: segmentBounds(coordinates[index - 1], coordinates[index]),
    });
  }
  if (coordinates.length > 2 && !pointsAlmostEqual(coordinates[0], coordinates[coordinates.length - 1])) {
    segments.push({
      start: coordinates[coordinates.length - 1],
      end: coordinates[0],
      bounds: segmentBounds(coordinates[coordinates.length - 1], coordinates[0]),
    });
  }
  return segments;
}

function segmentBounds(start, end) {
  return [
    Math.min(Number(start[0]), Number(end[0])),
    Math.min(Number(start[1]), Number(end[1])),
    Math.max(Number(start[0]), Number(end[0])),
    Math.max(Number(start[1]), Number(end[1])),
  ];
}

function segmentsIntersect(start, end, otherStart, otherEnd) {
  return segmentIntersectionParameters(start, end, otherStart, otherEnd).length > 0;
}

function segmentIntersectionParameters(start, end, otherStart, otherEnd) {
  const rX = end[0] - start[0];
  const rY = end[1] - start[1];
  const sX = otherEnd[0] - otherStart[0];
  const sY = otherEnd[1] - otherStart[1];
  const denominator = rX * sY - rY * sX;
  const qPX = otherStart[0] - start[0];
  const qPY = otherStart[1] - start[1];
  if (Math.abs(denominator) < 1e-12) {
    const collinear = Math.abs(qPX * rY - qPY * rX) < 1e-12;
    const lengthSquared = rX * rX + rY * rY;
    if (!collinear || lengthSquared < 1e-18) return [];
    const otherStartT = ((otherStart[0] - start[0]) * rX + (otherStart[1] - start[1]) * rY) / lengthSquared;
    const otherEndT = ((otherEnd[0] - start[0]) * rX + (otherEnd[1] - start[1]) * rY) / lengthSquared;
    const overlapStart = Math.max(0, Math.min(otherStartT, otherEndT));
    const overlapEnd = Math.min(1, Math.max(otherStartT, otherEndT));
    if (overlapEnd + 1e-9 < overlapStart) return [];
    return [overlapStart, overlapEnd].map((value) => Math.min(1, Math.max(0, value)));
  }
  const t = (qPX * sY - qPY * sX) / denominator;
  const u = (qPX * rY - qPY * rX) / denominator;
  if (t < -1e-9 || t > 1 + 1e-9 || u < -1e-9 || u > 1 + 1e-9) return [];
  return [Math.min(1, Math.max(0, t))];
}

function pointAlongSegment(start, end, ratio) {
  const t = Math.max(0, Math.min(1, Number(ratio) || 0));
  return [
    Number(start[0]) + (Number(end[0]) - Number(start[0])) * t,
    Number(start[1]) + (Number(end[1]) - Number(start[1])) * t,
  ];
}

function uniqueSortedNumbers(values) {
  const sorted = (Array.isArray(values) ? values : [])
    .map((value) => Math.max(0, Math.min(1, Number(value))))
    .filter(Number.isFinite)
    .sort((left, right) => left - right);
  const unique = [];
  for (const value of sorted) {
    if (!unique.length || Math.abs(value - unique[unique.length - 1]) > 1e-9) {
      unique.push(value);
    }
  }
  return unique;
}

function pointsAlmostEqual(left, right) {
  if (!Array.isArray(left) || !Array.isArray(right)) return false;
  return Math.abs(Number(left[0]) - Number(right[0])) <= 1e-9
    && Math.abs(Number(left[1]) - Number(right[1])) <= 1e-9;
}
