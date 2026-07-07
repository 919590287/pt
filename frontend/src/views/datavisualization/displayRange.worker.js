// 行政区显示范围的后台计算 Worker：
// 1) query —— 全网「线段-多边形求交」得到范围内的 routeIds/lineNames/stationNames（原 displayRangeSelection 主线程冻结点）
// 2) clipLines —— 公交线网 GeoJSON 线要素按行政区多边形裁剪（原 filterLineFeatureCollectionByDisplayRange）
// 网络数据按模型常驻（主线程打包为可转移的 Float64Array，一次传输零拷贝）；
// 结果按 (model, district) 记忆化，重复切换同一行政区不再重算。
// 仅依赖 @/utils/adminDistrictRange.js 的纯几何函数，禁止引入 DOM/Vue/地图引擎。
import {
  pointInDistrictContext,
  segmentIntersectsDistrictContext,
  clipLineStringToDistrictContext,
} from "@/utils/adminDistrictRange.js";

const networkByModel = new Map(); // model -> { linesMeta, seg, routeFac, stationNames, stationCoords }
const linesFcByModel = new Map(); // model -> { rev, collection }
const selectionMemo = new Map(); // `${model}::${district}::${contextRev}` -> { ok, routeIds, lineNames, stationNames }
const clipMemo = new Map(); // `${model}::${district}::${rev}::${contextRev}` -> FeatureCollection
// 行政区上下文按 (名称, 版本) 常驻：主线程首发完整 context 后，
// 同一行政区的后续消息只带名字与版本号，免去整份多边形每次 postMessage 的结构化克隆
const contextCache = new Map(); // `${districtName}::${contextRev}` -> context

function trimMemo(map, limit) {
  while (map.size > limit) {
    map.delete(map.keys().next().value);
  }
}

function resolveContext(msg) {
  const key = `${msg.districtName}::${msg.contextRev ?? 0}`;
  if (msg.context) {
    contextCache.set(key, msg.context);
    trimMemo(contextCache, 24);
    return msg.context;
  }
  return contextCache.get(key) || null;
}

function computeSelection(network, context) {
  if (!network || !context) {
    return { ok: false, routeIds: [], lineNames: [], stationNames: [] };
  }
  const seg = network.seg;
  const routeFac = network.routeFac;
  const routeIds = [];
  const lineNames = [];
  for (const line of network.linesMeta) {
    let lineHit = false;
    for (const route of line.routes) {
      let hit = false;
      const segEnd = route.segStart + route.segCount * 4;
      for (let i = route.segStart; i < segEnd; i += 4) {
        if (segmentIntersectsDistrictContext([seg[i], seg[i + 1]], [seg[i + 2], seg[i + 3]], context)) {
          hit = true;
          break;
        }
      }
      if (!hit) {
        // 与主线程原语义一致：links 不命中时回退设施点在多边形内测试
        const facEnd = route.facStart + route.facCount * 2;
        for (let i = route.facStart; i < facEnd; i += 2) {
          if (pointInDistrictContext([routeFac[i], routeFac[i + 1]], context)) {
            hit = true;
            break;
          }
        }
      }
      if (hit) {
        lineHit = true;
        if (route.key) routeIds.push(route.key);
      }
    }
    if (lineHit && line.lineName) lineNames.push(line.lineName);
  }
  const stationNames = [];
  const coords = network.stationCoords;
  for (let i = 0; i < network.stationNames.length; i += 1) {
    if (pointInDistrictContext([coords[i * 2], coords[i * 2 + 1]], context)) {
      stationNames.push(network.stationNames[i]);
    }
  }
  return { ok: true, routeIds, lineNames, stationNames };
}

function clipCollection(collection, context) {
  if (!context) return collection;
  const features = [];
  (collection?.features || []).forEach((feature, featureIndex) => {
    const geometry = feature?.geometry;
    const paths = !geometry
      ? []
      : geometry.type === "LineString"
        ? [geometry.coordinates || []]
        : geometry.type === "MultiLineString"
          ? (Array.isArray(geometry.coordinates) ? geometry.coordinates : [])
          : [];
    paths.forEach((path, pathIndex) => {
      clipLineStringToDistrictContext(path, context).forEach((coordinates, clipIndex) => {
        if (coordinates.length < 2) return;
        features.push({
          type: "Feature",
          id: [feature?.id ?? featureIndex, pathIndex, clipIndex].join("-"),
          geometry: { type: "LineString", coordinates },
          properties: { ...(feature?.properties || {}) },
        });
      });
    });
  });
  return { type: "FeatureCollection", features };
}

self.onmessage = (event) => {
  const msg = event.data || {};
  if (msg.type === "setNetwork") {
    // 单模型常驻即可：切模型即整体替换，旧模型记忆一并失效
    networkByModel.clear();
    selectionMemo.clear();
    networkByModel.set(String(msg.model || ""), {
      linesMeta: Array.isArray(msg.linesMeta) ? msg.linesMeta : [],
      seg: msg.segBuf ? new Float64Array(msg.segBuf) : new Float64Array(0),
      routeFac: msg.routeFacBuf ? new Float64Array(msg.routeFacBuf) : new Float64Array(0),
      stationNames: Array.isArray(msg.stationNames) ? msg.stationNames : [],
      stationCoords: msg.stationBuf ? new Float64Array(msg.stationBuf) : new Float64Array(0),
    });
    return;
  }
  if (msg.type === "setLines") {
    linesFcByModel.clear();
    clipMemo.clear();
    linesFcByModel.set(String(msg.model || ""), {
      rev: Number(msg.linesRev) || 0,
      collection: msg.collection || null,
    });
    return;
  }
  if (msg.type === "query") {
    const memoKey = `${msg.model}::${msg.districtName}::${msg.contextRev ?? 0}`;
    let result = selectionMemo.get(memoKey);
    if (!result) {
      result = computeSelection(networkByModel.get(String(msg.model || "")), resolveContext(msg));
      if (result.ok) {
        selectionMemo.set(memoKey, result);
        trimMemo(selectionMemo, 24);
      }
    }
    self.postMessage({ type: "queryResult", seq: msg.seq, ...result });
    return;
  }
  if (msg.type === "clipLines") {
    const entry = linesFcByModel.get(String(msg.model || ""));
    const memoKey = `${msg.model}::${msg.districtName}::${entry?.rev ?? -1}::${msg.contextRev ?? 0}`;
    let collection = clipMemo.get(memoKey);
    let ok = Boolean(entry?.collection);
    if (!collection && ok) {
      const context = resolveContext(msg);
      // 上下文未命中缓存（如 Worker 重建后首条消息未带 context）：回主线程同步兜底，绝不能按"无范围"整包返回
      if (!context) {
        ok = false;
      } else {
        collection = clipCollection(entry.collection, context);
        clipMemo.set(memoKey, collection);
        trimMemo(clipMemo, 12);
      }
    }
    self.postMessage({ type: "clipResult", seq: msg.seq, ok: ok && Boolean(collection), collection: collection || null });
  }
};
