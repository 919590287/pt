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
  roadLines: `${P}-road-lines`,
  baseLines: `${P}-base-lines`,
  baseLinesHit: `${P}-base-lines-hit`,
  baseStops: `${P}-base-stops`,
  baseStopLabels: `${P}-base-stop-labels`,
  linePickedLine: `${P}-line-picked-line`,
  linePickedStops: `${P}-line-picked-stops`,
  linePickedSeq: `${P}-line-picked-seq`,
  areaFill: `${P}-area-fill`,
  areaLine: `${P}-area-line`,
  areaBuffer: `${P}-area-buffer`,
  mask: `${P}-mask`,
  overlayLines: `${P}-overlay-lines`,
  overlayPoints: `${P}-overlay-points`,
  editPreviewLines: `${P}-edit-preview-lines`,
  editPreviewStops: `${P}-edit-preview-stops`,
  toolLine: `${P}-tool-line`,
  toolAnchors: `${P}-tool-anchors`,
  toolPoint: `${P}-tool-point`,
  highlightLine: `${P}-highlight-line`,
  highlightStop: `${P}-highlight-stop`,
};

const SOURCES = {
  road: `${P}-src-road`,
  baseLines: `${P}-src-base-lines`,
  baseStops: `${P}-src-base-stops`,
  area: `${P}-src-area`,
  areaBuffer: `${P}-src-area-buffer`,
  mask: `${P}-src-mask`,
  overlay: `${P}-src-overlay`,
  editPreview: `${P}-src-edit-preview`,
  linePicked: `${P}-src-line-picked`,
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

// ==================== 编辑期路网底图 ====================

/**
 * 研究区域内可行车路网（灰色细线）。绘制/补画路径时开启，供沿路网点选参考。
 * segments: [[[lng,lat],[lng,lat]], ...]
 */
export function updateRoadNetwork(map, segments) {
  const data = segments && segments.length
    ? { type: "Feature", geometry: { type: "MultiLineString", coordinates: segments }, properties: {} }
    : EMPTY;
  ensureSource(map, SOURCES.road, data);
  if (!map.getLayer(LAYER_IDS.roadLines)) {
    // 保持在其它编辑图层之下
    const before = [
      LAYER_IDS.baseLines, LAYER_IDS.overlayLines, LAYER_IDS.editPreviewLines,
      LAYER_IDS.toolLine, LAYER_IDS.highlightLine,
    ].find((id) => map.getLayer(id));
    map.addLayer({
      id: LAYER_IDS.roadLines,
      type: "line",
      source: SOURCES.road,
      paint: {
        "line-color": "#94a3b8",
        "line-width": ["interpolate", ["linear"], ["zoom"], 11, 0.4, 14, 1.1, 17, 2.2],
        "line-opacity": 0.65,
      },
    }, before);
  }
}

export function clearRoadNetwork(map) {
  ensureSource(map, SOURCES.road, EMPTY);
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
      "line-color": ["case", ["==", ["get", "mode"], "subway"], "#8b5cf6", "#3d6ea6"],
      "line-width": ["interpolate", ["linear"], ["zoom"], 10, 0.5, 13, 1.2, 16, 2.4],
      "line-opacity": 0.3,
    },
  });
  // 命中放大层（透明宽线，便于点选）
  ensureLayer(map, {
    id: LAYER_IDS.baseLinesHit,
    type: "line",
    source: SOURCES.baseLines,
    paint: { "line-color": "#0b1526", "line-opacity": 0.001, "line-width": 18 },
  });
  ensureLayer(map, {
    id: LAYER_IDS.baseStops,
    type: "circle",
    source: SOURCES.baseStops,
    minzoom: 12.5,
    paint: {
      "circle-radius": ["interpolate", ["linear"], ["zoom"], 12.5, 2, 15, 4.5, 17, 7],
      "circle-color": "#ffffff",
      "circle-stroke-color": "#33608f",
      "circle-stroke-width": 1.6,
      "circle-opacity": 0.95,
    },
  });
  // 站点名称标注（建线点选时显示"全部站点和名称"）
  ensureLayer(map, {
    id: LAYER_IDS.baseStopLabels,
    type: "symbol",
    source: SOURCES.baseStops,
    minzoom: 13,
    layout: {
      "text-field": ["coalesce", ["get", "name"], ""],
      "text-size": ["interpolate", ["linear"], ["zoom"], 13, 10, 16, 13],
      "text-anchor": "left",
      "text-offset": [0.7, 0],
      "text-max-width": 8,
      "text-optional": true,
      "text-padding": 2,
    },
    paint: {
      "text-color": "#1f3140",
      "text-halo-color": "rgba(248, 251, 252, 0.94)",
      "text-halo-width": 1.5,
      "text-halo-blur": 0.4,
    },
  });
}

// ==================== 新增线路：已选站序高亮 + 沿路径连线 ====================

/**
 * features: 停靠站点（Point，properties.kind='stop', seq 序号）、路径途经点（kind='road'）
 * 与沿路网连线（LineString）。
 */
export function updateLinePicked(map, features) {
  ensureSource(map, SOURCES.linePicked, { type: "FeatureCollection", features: features || [] });
  ensureLayer(map, {
    id: LAYER_IDS.linePickedLine,
    type: "line",
    source: SOURCES.linePicked,
    filter: ["==", ["geometry-type"], "LineString"],
    paint: { "line-color": "#16a34a", "line-width": 4, "line-opacity": 0.9 },
  });
  ensureLayer(map, {
    id: LAYER_IDS.linePickedStops,
    type: "circle",
    source: SOURCES.linePicked,
    filter: ["==", ["geometry-type"], "Point"],
    paint: {
      // 停靠站=大绿点；路径途经点=小灰点
      "circle-radius": ["match", ["get", "kind"], "road", 4.5, 11],
      "circle-color": ["match", ["get", "kind"], "road", "#ffffff", "#16a34a"],
      "circle-stroke-color": ["match", ["get", "kind"], "road", "#94a3b8", "#ffffff"],
      "circle-stroke-width": 2,
    },
  });
  ensureLayer(map, {
    id: LAYER_IDS.linePickedSeq,
    type: "symbol",
    source: SOURCES.linePicked,
    filter: ["==", ["geometry-type"], "Point"],
    layout: {
      // 仅停靠站显示序号
      "text-field": ["case", ["==", ["get", "kind"], "stop"], ["to-string", ["get", "seq"]], ""],
      "text-size": 12,
      "text-allow-overlap": true,
      "text-ignore-placement": true,
    },
    paint: { "text-color": "#ffffff" },
  });
}

export function clearLinePicked(map) {
  ensureSource(map, SOURCES.linePicked, EMPTY);
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
    paint: { "fill-color": "#0071e3", "fill-opacity": 0.05 },
  });
  ensureLayer(map, {
    id: LAYER_IDS.areaLine,
    type: "line",
    source: SOURCES.area,
    paint: { "line-color": "#0071e3", "line-width": 3, "line-dasharray": [2.4, 1.6] },
  });
  ensureLayer(map, {
    id: LAYER_IDS.areaBuffer,
    type: "line",
    source: SOURCES.areaBuffer,
    paint: { "line-color": "#0071e3", "line-width": 1.2, "line-opacity": 0.45, "line-dasharray": [1, 2] },
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

// ==================== 调整站点编辑预览 ====================

/**
 * 调整站点面板的地图预览：分段线（沿用/已补画/缺失）+ 站点序列。
 * features 由面板组装，线 props: {state: 'ok'|'drawn'|'gap'}；
 * 点 props: {ptState: 'stop'|'new'|'hover', seq}
 */
export function updateEditPreview(map, features) {
  ensureSource(map, SOURCES.editPreview, { type: "FeatureCollection", features: features || [] });
  ensureLayer(map, {
    id: LAYER_IDS.editPreviewLines,
    type: "line",
    source: SOURCES.editPreview,
    filter: ["==", ["geometry-type"], "LineString"],
    paint: {
      "line-color": ["match", ["get", "state"], "drawn", "#0f9f6e", "gap", "#dc2626", "#3d6ea6"],
      "line-width": ["match", ["get", "state"], "gap", 3, 4.5],
      "line-opacity": 0.9,
      "line-dasharray": ["match", ["get", "state"], "gap", ["literal", [1.4, 1.2]], ["literal", [1, 0]]],
    },
  });
  ensureLayer(map, {
    id: LAYER_IDS.editPreviewStops,
    type: "circle",
    source: SOURCES.editPreview,
    filter: ["==", ["geometry-type"], "Point"],
    paint: {
      "circle-radius": ["match", ["get", "ptState"], "hover", 9, "new", 6.5, 5],
      "circle-color": ["match", ["get", "ptState"], "hover", "#f97316", "new", "#16a34a", "#ffffff"],
      "circle-stroke-color": ["match", ["get", "ptState"], "hover", "#ffffff", "new", "#ffffff", "#0071e3"],
      "circle-stroke-width": 2,
    },
  });
}

export function clearEditPreview(map) {
  ensureSource(map, SOURCES.editPreview, EMPTY);
}

// ==================== 工具预览 ====================

export function updateToolPreview(map, { anchors = [], pathGeometry = null, cursor = null, point = null, segments = [], endpoints = [] }) {
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
  // 固定起终点（补画路径模式）：绿色大圆点，提示"从站点开始/到站点结束"
  for (const ep of endpoints) {
    if (Array.isArray(ep)) features.push(pointFeature(ep, { role: "endpoint" }));
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
      "circle-radius": ["match", ["get", "role"], "snap", 8, "endpoint", 9, 5.5],
      "circle-color": ["match", ["get", "role"], "snap", "#0f9f6e", "endpoint", "#16a34a", "#0071e3"],
      "circle-stroke-color": "#ffffff",
      "circle-stroke-width": 2,
    },
  });
}

export function clearToolPreview(map) {
  ensureSource(map, SOURCES.tool, EMPTY);
}

// ==================== 选中高亮 ====================

export function updateHighlight(map, routeGeometry, stopPoint, routeStops = []) {
  const features = [];
  if (routeGeometry && routeGeometry.length > 1) {
    features.push(lineFeature(routeGeometry, { role: "route" }));
  }
  if (stopPoint) {
    features.push(pointFeature(stopPoint, { role: "stop" }));
  }
  // 选中线路沿线站点（搜索选线后随线一起显示）
  for (const s of routeStops) {
    if (Array.isArray(s)) features.push(pointFeature(s, { role: "route-stop" }));
  }
  ensureSource(map, SOURCES.highlight, { type: "FeatureCollection", features });
  ensureLayer(map, {
    id: LAYER_IDS.highlightLine,
    type: "line",
    source: SOURCES.highlight,
    filter: ["==", ["geometry-type"], "LineString"],
    paint: { "line-color": "#0071e3", "line-width": 6, "line-opacity": 0.85 },
  });
  ensureLayer(map, {
    id: LAYER_IDS.highlightStop,
    type: "circle",
    source: SOURCES.highlight,
    filter: ["==", ["geometry-type"], "Point"],
    paint: {
      "circle-radius": ["match", ["get", "role"], "route-stop", 4.5, 10],
      "circle-color": ["match", ["get", "role"], "route-stop", "#ffffff", "rgba(21,105,222,0.15)"],
      "circle-stroke-color": "#0071e3",
      "circle-stroke-width": ["match", ["get", "role"], "route-stop", 2, 3],
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
