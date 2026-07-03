/**
 * 线网优化编辑器的 maplibre 图层管理（纯函数式 ensure/update/clear）。
 * 图层组：
 *  - base: 母本公交线路（可点选）+ 站点（可点选）
 *  - area: 研究区域面/边界/缓冲带 + 区域外压暗遮罩
 *  - overlay: 修改清单差异叠加（新增=绿 修改=橙 删除=红虚线）
 *  - tool: 当前工具的临时预览（锚点/寻径路径/吸附点/选中路段）
 *  - highlight: 选中要素高亮
 */

const P = "opt-editor"; // 前缀，避免与其他模块冲突

export const LAYER_IDS = {
  baseLines: `${P}-base-lines`,
  baseLinesHit: `${P}-base-lines-hit`,
  baseStops: `${P}-base-stops`,
  areaFill: `${P}-area-fill`,
  areaLine: `${P}-area-line`,
  areaBuffer: `${P}-area-buffer`,
  mask: `${P}-mask`,
  overlayLines: `${P}-overlay-lines`,
  overlayPoints: `${P}-overlay-points`,
  toolLine: `${P}-tool-line`,
  toolAnchors: `${P}-tool-anchors`,
  toolPoint: `${P}-tool-point`,
  highlightLine: `${P}-highlight-line`,
  highlightStop: `${P}-highlight-stop`,
};

const SOURCES = {
  baseLines: `${P}-src-base-lines`,
  baseStops: `${P}-src-base-stops`,
  area: `${P}-src-area`,
  areaBuffer: `${P}-src-area-buffer`,
  mask: `${P}-src-mask`,
  overlay: `${P}-src-overlay`,
  tool: `${P}-src-tool`,
  highlight: `${P}-src-highlight`,
};

const EMPTY = { type: "FeatureCollection", features: [] };

function ensureSource(map, id, data = EMPTY) {
  if (!map.getSource(id)) {
    map.addSource(id, { type: "geojson", data });
  } else {
    map.getSource(id).setData(data);
  }
}

function ensureLayer(map, layer) {
  if (!map.getLayer(layer.id)) {
    map.addLayer(layer);
  }
}

// ==================== 底图：线路与站点 ====================

export function updateBaseNetwork(map, routeFeatures, stopFeatures) {
  ensureSource(map, SOURCES.baseLines, { type: "FeatureCollection", features: routeFeatures });
  ensureSource(map, SOURCES.baseStops, { type: "FeatureCollection", features: stopFeatures });

  ensureLayer(map, {
    id: LAYER_IDS.baseLines,
    type: "line",
    source: SOURCES.baseLines,
    paint: {
      "line-color": ["case", ["==", ["get", "mode"], "subway"], "#8b5cf6", "#3f82e0"],
      "line-width": ["interpolate", ["linear"], ["zoom"], 10, 0.6, 13, 1.6, 16, 3],
      "line-opacity": 0.55,
    },
  });
  // 命中放大层（透明宽线，便于点选）
  ensureLayer(map, {
    id: LAYER_IDS.baseLinesHit,
    type: "line",
    source: SOURCES.baseLines,
    paint: { "line-color": "#000", "line-opacity": 0.001, "line-width": 14 },
  });
  ensureLayer(map, {
    id: LAYER_IDS.baseStops,
    type: "circle",
    source: SOURCES.baseStops,
    minzoom: 12.5,
    paint: {
      "circle-radius": ["interpolate", ["linear"], ["zoom"], 12.5, 2, 15, 4.5, 17, 7],
      "circle-color": "#ffffff",
      "circle-stroke-color": "#2563eb",
      "circle-stroke-width": 1.6,
      "circle-opacity": 0.95,
    },
  });
}

// ==================== 研究区域 ====================

const WORLD_RING = [[-180, -85], [180, -85], [180, 85], [-180, 85], [-180, -85]];

export function updateArea(map, polygon, bufferRing) {
  if (!polygon || polygon.length < 3) {
    ensureSource(map, SOURCES.area, EMPTY);
    ensureSource(map, SOURCES.areaBuffer, EMPTY);
    ensureSource(map, SOURCES.mask, EMPTY);
    return;
  }
  const ring = closeRing(polygon);
  ensureSource(map, SOURCES.area, {
    type: "Feature",
    geometry: { type: "Polygon", coordinates: [ring] },
    properties: {},
  });
  ensureSource(map, SOURCES.areaBuffer, bufferRing && bufferRing.length > 2 ? {
    type: "Feature",
    geometry: { type: "LineString", coordinates: closeRing(bufferRing) },
    properties: {},
  } : EMPTY);
  // 区域外压暗：世界面挖洞
  ensureSource(map, SOURCES.mask, {
    type: "Feature",
    geometry: { type: "Polygon", coordinates: [WORLD_RING, ring] },
    properties: {},
  });

  ensureLayer(map, {
    id: LAYER_IDS.mask,
    type: "fill",
    source: SOURCES.mask,
    paint: { "fill-color": "#0b1526", "fill-opacity": 0.32 },
  });
  ensureLayer(map, {
    id: LAYER_IDS.areaFill,
    type: "fill",
    source: SOURCES.area,
    paint: { "fill-color": "#1569de", "fill-opacity": 0.05 },
  });
  ensureLayer(map, {
    id: LAYER_IDS.areaLine,
    type: "line",
    source: SOURCES.area,
    paint: { "line-color": "#1569de", "line-width": 3, "line-dasharray": [2.4, 1.6] },
  });
  ensureLayer(map, {
    id: LAYER_IDS.areaBuffer,
    type: "line",
    source: SOURCES.areaBuffer,
    paint: { "line-color": "#1569de", "line-width": 1.2, "line-opacity": 0.45, "line-dasharray": [1, 2] },
  });
}

function closeRing(ring) {
  const first = ring[0];
  const last = ring[ring.length - 1];
  if (first[0] !== last[0] || first[1] !== last[1]) {
    return [...ring, [...first]];
  }
  return ring;
}

/** 近似缓冲外环（仅用于显示）：按纬度把米转换为度做粗放外扩 */
export function approxBufferRing(polygon, bufferM) {
  if (!polygon || polygon.length < 3 || !bufferM) return null;
  let cx = 0;
  let cy = 0;
  for (const [lng, lat] of polygon) {
    cx += lng;
    cy += lat;
  }
  cx /= polygon.length;
  cy /= polygon.length;
  const dLat = bufferM / 111320;
  const dLng = bufferM / (111320 * Math.cos((cy * Math.PI) / 180));
  return polygon.map(([lng, lat]) => {
    const vx = lng - cx;
    const vy = lat - cy;
    const len = Math.hypot(vx / dLng, vy / dLat) || 1;
    return [lng + (vx / (len * dLng)) * dLng * 1, lat + (vy / (len * dLat)) * dLat * 1];
  }).map(([lng, lat], i) => {
    // 简化：沿质心向外平移 buffer 距离
    const vx = polygon[i][0] - cx;
    const vy = polygon[i][1] - cy;
    const norm = Math.hypot(vx, vy) || 1e-9;
    return [polygon[i][0] + (vx / norm) * dLng, polygon[i][1] + (vy / norm) * dLat];
  });
}

// ==================== 修改清单差异叠加 ====================

const KIND_STYLE = {
  add: { color: "#16a34a", dash: null },
  modify: { color: "#f59e0b", dash: null },
  delete: { color: "#dc2626", dash: [1.6, 1.4] },
};

export function styleOfKind(kind) {
  if (kind.endsWith(".add") || kind === "route.add" || kind === "link.add" || kind === "stop.add") return KIND_STYLE.add;
  if (kind.endsWith(".delete")) return KIND_STYLE.delete;
  return KIND_STYLE.modify;
}

/**
 * edits -> 差异叠加 features。routeIndex/stopIndex 用于取被修改要素的原几何。
 */
export function buildOverlayFeatures(edits, routeIndex, stopIndex) {
  const features = [];
  for (const edit of edits) {
    const style = styleOfKind(edit.kind);
    const props = { editId: edit.id, kind: edit.kind, color: style.color, dashed: style.dash ? 1 : 0 };
    const t = edit.target || {};
    const g = edit.geometry || {};
    if (edit.kind === "route.add" || edit.kind === "route.modify.alignment") {
      for (const dir of g.directions || []) {
        if (Array.isArray(dir.geometry) && dir.geometry.length > 1) {
          features.push(lineFeature(dir.geometry, props));
        }
      }
    } else if (edit.kind.startsWith("route.") || edit.kind.startsWith("ops.")) {
      const route = routeIndex.get(`${t.lineId}||${t.routeId || ""}`) || firstRouteOfLine(routeIndex, t.lineId);
      if (route?.geometry) {
        features.push(lineFeature(route.geometry, props));
      }
    } else if (edit.kind === "stop.add" && Array.isArray(g.coord)) {
      features.push(pointFeature(g.coord, props));
    } else if (edit.kind === "stop.move" && Array.isArray(g.coord)) {
      features.push(pointFeature(g.coord, props));
      const origin = stopIndex.get(t.stopId);
      if (origin) {
        features.push(lineFeature([[origin.lng, origin.lat], g.coord], { ...props, dashed: 1 }));
      }
    } else if (edit.kind === "stop.delete") {
      const origin = stopIndex.get(t.stopId);
      if (origin) {
        features.push(pointFeature([origin.lng, origin.lat], props));
      }
    } else if (edit.kind === "link.add" && Array.isArray(g.coords)) {
      features.push(lineFeature(g.coords, props));
    } else if ((edit.kind === "link.delete" || edit.kind === "link.modify") && Array.isArray(g.segments)) {
      for (const seg of g.segments) {
        if (Array.isArray(seg) && seg.length > 1) {
          features.push(lineFeature(seg, props));
        }
      }
    }
  }
  return features;
}

function firstRouteOfLine(routeIndex, lineId) {
  for (const [key, value] of routeIndex.entries()) {
    if (key.startsWith(`${lineId}||`)) return value;
  }
  return null;
}

function lineFeature(coords, properties) {
  return { type: "Feature", geometry: { type: "LineString", coordinates: coords }, properties };
}

function pointFeature(coord, properties) {
  return { type: "Feature", geometry: { type: "Point", coordinates: coord }, properties };
}

export function updateOverlay(map, features) {
  ensureSource(map, SOURCES.overlay, { type: "FeatureCollection", features });
  ensureLayer(map, {
    id: LAYER_IDS.overlayLines,
    type: "line",
    source: SOURCES.overlay,
    filter: ["==", ["geometry-type"], "LineString"],
    paint: {
      "line-color": ["get", "color"],
      "line-width": 4,
      "line-opacity": 0.9,
      "line-dasharray": ["case", ["==", ["get", "dashed"], 1], ["literal", [1.6, 1.4]], ["literal", [1, 0]]],
    },
  });
  ensureLayer(map, {
    id: LAYER_IDS.overlayPoints,
    type: "circle",
    source: SOURCES.overlay,
    filter: ["==", ["geometry-type"], "Point"],
    paint: {
      "circle-radius": 7,
      "circle-color": ["get", "color"],
      "circle-opacity": 0.85,
      "circle-stroke-color": "#ffffff",
      "circle-stroke-width": 2,
    },
  });
}

// ==================== 工具预览 ====================

export function updateToolPreview(map, { anchors = [], pathGeometry = null, cursor = null, point = null, segments = [] }) {
  const features = [];
  if (pathGeometry && pathGeometry.length > 1) {
    features.push(lineFeature(pathGeometry, { role: "path" }));
  } else if (anchors.length > 0 && cursor) {
    features.push(lineFeature([...anchors, cursor], { role: "sketch" }));
  }
  if (anchors.length > 1 && !pathGeometry) {
    features.push(lineFeature(anchors, { role: "sketch" }));
  }
  anchors.forEach((a, idx) => features.push(pointFeature(a, { role: "anchor", idx })));
  if (point) {
    features.push(pointFeature(point, { role: "snap" }));
  }
  for (const seg of segments) {
    if (Array.isArray(seg) && seg.length > 1) {
      features.push(lineFeature(seg, { role: "picked" }));
    }
  }
  ensureSource(map, SOURCES.tool, { type: "FeatureCollection", features });
  ensureLayer(map, {
    id: LAYER_IDS.toolLine,
    type: "line",
    source: SOURCES.tool,
    filter: ["==", ["geometry-type"], "LineString"],
    paint: {
      "line-color": ["match", ["get", "role"], "picked", "#dc2626", "path", "#0f9f6e", "#0b91b7"],
      "line-width": ["match", ["get", "role"], "picked", 5, 3],
      "line-dasharray": ["match", ["get", "role"], "sketch", ["literal", [2, 2]], ["literal", [1, 0]]],
    },
  });
  ensureLayer(map, {
    id: LAYER_IDS.toolAnchors,
    type: "circle",
    source: SOURCES.tool,
    filter: ["==", ["geometry-type"], "Point"],
    paint: {
      "circle-radius": ["match", ["get", "role"], "snap", 8, 5.5],
      "circle-color": ["match", ["get", "role"], "snap", "#0f9f6e", "#1569de"],
      "circle-stroke-color": "#ffffff",
      "circle-stroke-width": 2,
    },
  });
}

export function clearToolPreview(map) {
  ensureSource(map, SOURCES.tool, EMPTY);
}

// ==================== 选中高亮 ====================

export function updateHighlight(map, routeGeometry, stopPoint) {
  const features = [];
  if (routeGeometry && routeGeometry.length > 1) {
    features.push(lineFeature(routeGeometry, { role: "route" }));
  }
  if (stopPoint) {
    features.push(pointFeature(stopPoint, { role: "stop" }));
  }
  ensureSource(map, SOURCES.highlight, { type: "FeatureCollection", features });
  ensureLayer(map, {
    id: LAYER_IDS.highlightLine,
    type: "line",
    source: SOURCES.highlight,
    filter: ["==", ["geometry-type"], "LineString"],
    paint: { "line-color": "#1569de", "line-width": 6, "line-opacity": 0.85 },
  });
  ensureLayer(map, {
    id: LAYER_IDS.highlightStop,
    type: "circle",
    source: SOURCES.highlight,
    filter: ["==", ["geometry-type"], "Point"],
    paint: {
      "circle-radius": 10,
      "circle-color": "rgba(21,105,222,0.15)",
      "circle-stroke-color": "#1569de",
      "circle-stroke-width": 3,
    },
  });
}

// ==================== 清理 ====================

export function removeAllEditorLayers(map) {
  if (!map || !map.getStyle) return;
  try {
    for (const id of Object.values(LAYER_IDS)) {
      if (map.getLayer(id)) map.removeLayer(id);
    }
    for (const id of Object.values(SOURCES)) {
      if (map.getSource(id)) map.removeSource(id);
    }
  } catch (e) {
    /* 地图可能已销毁 */
  }
}
