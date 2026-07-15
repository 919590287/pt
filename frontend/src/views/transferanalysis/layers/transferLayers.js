/**
 * 换乘分析地图图层管理器（maplibre 原生，无 deck 依赖）。
 *
 * 图层栈（自下而上）：换乘热力 → 弧线白描边 → 换乘弧线 → 站间连线 →
 * 接驳公交站空心圈 → 枢纽气泡 → 枢纽名标注。全部幂等创建；clear() 先删
 * layer 再删 source（平台约定，source 被引用时删不掉）。
 *
 * 配色唯一来源 MAP_THEME.transfer（mapTheme.js），时间色带经 colorSchemes
 * 采样为 5 级；气泡半径在 JS 侧按 sqrt 预计算成 feature 属性，paint 只做
 * 缩放级别微调，避免复杂表达式。
 */
import { MAP_THEME } from "@/utils/mapTheme.js";
import { sampleScheme } from "@/utils/colorSchemes.js";
import { buildFlowCurveFeatureCollection, emptyFlowCurveCollection } from "@/views/datavisualization/utils/flowCurves.js";

const SRC_HUBS = "ta-hubs-src";
const SRC_FLOWS = "ta-flows-src";
const SRC_HEAT = "ta-heat-src";
const SRC_LINKS = "ta-links-src";
const SRC_STOPS = "ta-stops-src";

const LAYER_HEAT = "ta-heat";
const LAYER_FLOW_CASING = "ta-flow-casing";
const LAYER_FLOW = "ta-flow";
const LAYER_LINKS = "ta-links";
const LAYER_STOPS = "ta-stops";
const LAYER_STOP_LABELS = "ta-stop-labels";
const LAYER_HUBS = "ta-hubs";
const LAYER_HUB_LABELS = "ta-hub-labels";
// 站名标注全量放开的最小缩放级别：低于此级别只显示 Top 标注（防挤占），
// 达到后放开全部站名（重叠由符号碰撞检测自动隐藏，随继续放大逐步全显）
const LABEL_ALL_MINZOOM = 12;

// 删除顺序：栈顶到栈底
const ALL_LAYERS = [LAYER_STOP_LABELS, LAYER_HUB_LABELS, LAYER_HUBS, LAYER_STOPS, LAYER_LINKS, LAYER_FLOW, LAYER_FLOW_CASING, LAYER_HEAT];
const ALL_SOURCES = [SRC_HUBS, SRC_FLOWS, SRC_HEAT, SRC_LINKS, SRC_STOPS];

export function emptyFeatureCollection() {
  return { type: "FeatureCollection", features: [] };
}

/** 时间色带 5 级采样（低→高 = 绿→黄→红） */
export function timeRampColors() {
  return sampleScheme(MAP_THEME.transfer.hubScale, 5);
}

/** 按 5 级分位阈值取色（thresholds 为 4 个升序阈值） */
export function rampColorFor(value, thresholds, colors) {
  for (let i = 0; i < thresholds.length; i++) {
    if (value <= thresholds[i]) return colors[i];
  }
  return colors[colors.length - 1];
}

export class TransferLayerManager {
  constructor(mapWrapper) {
    this.mapWrapper = mapWrapper; // MapRef.value（含 .map 与 buildingLayerId）
    this.clickHandler = null;
    this.boundClick = null;
    this.boundEnter = null;
    this.boundLeave = null;
    this.sourceRefs = new Map(); // sourceId -> 上次 setData 的引用（引用相等短路）
  }

  get map() {
    return this.mapWrapper?.map || null;
  }

  /** 插到 3D 建筑层之下（平台先例），无建筑层则置顶 */
  addLayerBelowBuildings(layer) {
    const map = this.map;
    if (!map) return;
    const beforeId = this.mapWrapper?.buildingLayerId;
    if (beforeId && map.getLayer?.(beforeId)) {
      map.addLayer(layer, beforeId);
      return;
    }
    map.addLayer(layer);
  }

  ensureSource(sourceId) {
    const map = this.map;
    if (!map) return;
    if (!map.getSource(sourceId)) {
      map.addSource(sourceId, { type: "geojson", data: emptyFeatureCollection() });
    }
  }

  setSourceData(sourceId, data) {
    const map = this.map;
    const source = map?.getSource(sourceId);
    if (!source?.setData) return;
    if (this.sourceRefs.get(sourceId) === data) return;
    source.setData(data);
    this.sourceRefs.set(sourceId, data);
  }

  /** 幂等创建全部 source/layer */
  ensure() {
    const map = this.map;
    if (!map || typeof map.addSource !== "function") return false;
    ALL_SOURCES.forEach((id) => this.ensureSource(id));
    const ramp = timeRampColors();

    if (!map.getLayer(LAYER_HEAT)) {
      this.addLayerBelowBuildings({
        id: LAYER_HEAT,
        type: "heatmap",
        source: SRC_HEAT,
        layout: { visibility: "none" },
        paint: {
          "heatmap-weight": ["coalesce", ["get", "weight"], 0],
          "heatmap-intensity": 1,
          "heatmap-radius": ["interpolate", ["exponential", 2], ["zoom"], 8, 2, 16, 320],
          "heatmap-opacity": 0.85,
          "heatmap-color": [
            "interpolate",
            ["linear"],
            ["heatmap-density"],
            0,
            "rgba(0,0,0,0)",
            0.2,
            ramp[0],
            0.4,
            ramp[1],
            0.6,
            ramp[2],
            0.8,
            ramp[3],
            1,
            ramp[4],
          ],
        },
      });
    }

    if (!map.getLayer(LAYER_FLOW_CASING)) {
      // 白色描边衬底：同线网 casing 语言，让弧线在浅色底图上边缘利落
      this.addLayerBelowBuildings({
        id: LAYER_FLOW_CASING,
        type: "line",
        source: SRC_FLOWS,
        layout: { "line-join": "round", "line-cap": "round" },
        paint: {
          "line-color": "#ffffff",
          "line-width": ["interpolate", ["linear"], ["zoom"], 9, ["+", ["*", ["get", "width"], 0.7], 1.4], 13, ["+", ["get", "width"], 1.6], 16, ["+", ["*", ["get", "width"], 1.3], 1.8]],
          "line-opacity": 0.4,
        },
      });
    }
    if (!map.getLayer(LAYER_FLOW)) {
      this.addLayerBelowBuildings({
        id: LAYER_FLOW,
        type: "line",
        source: SRC_FLOWS,
        layout: { "line-join": "round", "line-cap": "round" },
        paint: {
          "line-color": ["get", "color"],
          "line-width": ["interpolate", ["linear"], ["zoom"], 9, ["*", ["get", "width"], 0.7], 13, ["get", "width"], 16, ["*", ["get", "width"], 1.3]],
          "line-opacity": ["interpolate", ["linear"], ["get", "width"], 1.2, 0.5, 6, 0.88],
        },
      });
    }

    if (!map.getLayer(LAYER_LINKS)) {
      // 枢纽详情：公交站→地铁站连线（细直线，色=平均换乘时间分级）
      this.addLayerBelowBuildings({
        id: LAYER_LINKS,
        type: "line",
        source: SRC_LINKS,
        layout: { "line-join": "round", "line-cap": "round" },
        paint: {
          "line-color": ["get", "color"],
          "line-width": ["interpolate", ["linear"], ["get", "width"], 1, 1.2, 6, 4.4],
          "line-opacity": 0.82,
          "line-dasharray": [2, 1.2],
        },
      });
    }

    if (!map.getLayer(LAYER_STOPS)) {
      // 接驳公交站空心圈（需求语言与 OD 端点一致）
      this.addLayerBelowBuildings({
        id: LAYER_STOPS,
        type: "circle",
        source: SRC_STOPS,
        paint: {
          "circle-radius": ["interpolate", ["linear"], ["zoom"], 10, ["*", ["get", "r"], 0.6], 13, ["get", "r"], 16, ["*", ["get", "r"], 1.4]],
          "circle-color": "#ffffff",
          "circle-opacity": 0,
          "circle-stroke-color": ["get", "color"],
          "circle-stroke-width": 1.8,
          "circle-stroke-opacity": 0.92,
        },
      });
    }

    if (!map.getLayer(LAYER_STOP_LABELS)) {
      // 站点详情对端站名（选中站点后连线端点的站名；量少不做缩放分级，重叠由碰撞检测隐藏）
      this.addLayerBelowBuildings({
        id: LAYER_STOP_LABELS,
        type: "symbol",
        source: SRC_STOPS,
        layout: {
          "text-field": ["get", "name"],
          "text-size": 10.5,
          "text-anchor": "top",
          "text-offset": [0, 0.9],
          "text-max-width": 12,
          "text-optional": true,
          // 碰撞时大流量对端优先（与枢纽标注同规则）
          "symbol-sort-key": ["*", ["get", "sortKey"], -1],
        },
        paint: {
          "text-color": MAP_THEME.station.label,
          "text-halo-color": MAP_THEME.station.labelHalo,
          "text-halo-width": 1.2,
        },
      });
    }

    if (!map.getLayer(LAYER_HUBS)) {
      this.addLayerBelowBuildings({
        id: LAYER_HUBS,
        type: "circle",
        source: SRC_HUBS,
        // circle-sort-key 是 layout 属性（放 paint 会导致样式校验失败）
        layout: { "circle-sort-key": ["get", "sortKey"] },
        paint: {
          "circle-radius": ["interpolate", ["linear"], ["zoom"], 9, ["*", ["get", "r"], 0.72], 13, ["get", "r"], 16, ["*", ["get", "r"], 1.35]],
          "circle-color": ["get", "color"],
          "circle-opacity": 0.78,
          "circle-stroke-color": ["get", "strokeColor"],
          "circle-stroke-width": ["get", "strokeWidth"],
          "circle-stroke-opacity": 0.95,
        },
      });
    }

    if (!map.getLayer(LAYER_HUB_LABELS)) {
      this.addLayerBelowBuildings({
        id: LAYER_HUB_LABELS,
        type: "symbol",
        source: SRC_HUBS,
        // 低倍率只显示 Top 标注（labeled=1，防挤占）；放大到 LABEL_ALL_MINZOOM 起放开全部站名，
        // 重叠由 MapLibre 符号碰撞检测自动隐藏（zoom 过滤按整数级别求值，符合阈值语义）
        filter: ["any", ["==", ["get", "labeled"], 1], [">=", ["zoom"], LABEL_ALL_MINZOOM]],
        layout: {
          "text-field": ["get", "name"],
          "text-size": 11,
          "text-anchor": "top",
          "text-offset": [0, 1.1],
          "text-max-width": 12,
          "text-optional": true,
          // 碰撞时大流量站优先（sort-key 小者先布局；sortKey 大=重要，取负转优先级）
          "symbol-sort-key": ["*", ["get", "sortKey"], -1],
        },
        paint: {
          "text-color": MAP_THEME.station.label,
          "text-halo-color": MAP_THEME.station.labelHalo,
          "text-halo-width": 1.2,
        },
      });
    }
    return true;
  }

  /** hubs: GeoJSON FeatureCollection（属性 r/color/strokeColor/strokeWidth/name/labeled/sortKey/idx） */
  setHubs(collection) {
    this.setSourceData(SRC_HUBS, collection);
  }

  /**
   * flows: [{from:[lng,lat], to:[lng,lat], value, properties:{color,width,...}}]
   * 低流量先画、高流量后画（叠在上层更醒目）
   */
  setFlows(flows) {
    if (!flows || !flows.length) {
      this.setSourceData(SRC_FLOWS, emptyFlowCurveCollection());
      return;
    }
    const inputs = flows.slice().sort((a, b) => a.value - b.value);
    const curves = buildFlowCurveFeatureCollection(inputs, { curvature: 0.22, consistentSide: true });
    this.setSourceData(SRC_FLOWS, curves);
  }

  setHeat(collection) {
    this.setSourceData(SRC_HEAT, collection);
  }

  setLinks(collection) {
    this.setSourceData(SRC_LINKS, collection);
  }

  setStops(collection) {
    this.setSourceData(SRC_STOPS, collection);
  }

  setVisibility(kind, visible) {
    const map = this.map;
    if (!map?.getLayer) return;
    const groups = {
      heat: [LAYER_HEAT],
      flows: [LAYER_FLOW_CASING, LAYER_FLOW],
      hubs: [LAYER_HUBS, LAYER_HUB_LABELS],
      links: [LAYER_LINKS, LAYER_STOPS, LAYER_STOP_LABELS],
    };
    (groups[kind] || []).forEach((layerId) => {
      if (map.getLayer(layerId)) map.setLayoutProperty(layerId, "visibility", visible ? "visible" : "none");
    });
  }

  bindHubClick(handler) {
    const map = this.map;
    if (!map?.on || this.boundClick) return;
    this.clickHandler = handler;
    this.boundClick = (e) => {
      const feature = e?.features?.[0];
      if (feature && this.clickHandler) this.clickHandler(feature.properties || {});
    };
    this.boundEnter = () => {
      map.getCanvas().style.cursor = "pointer";
    };
    this.boundLeave = () => {
      map.getCanvas().style.cursor = "";
    };
    map.on("click", LAYER_HUBS, this.boundClick);
    map.on("mouseenter", LAYER_HUBS, this.boundEnter);
    map.on("mouseleave", LAYER_HUBS, this.boundLeave);
  }

  unbindHubClick() {
    const map = this.map;
    if (map?.off && this.boundClick) {
      map.off("click", LAYER_HUBS, this.boundClick);
      map.off("mouseenter", LAYER_HUBS, this.boundEnter);
      map.off("mouseleave", LAYER_HUBS, this.boundLeave);
    }
    this.boundClick = null;
    this.boundEnter = null;
    this.boundLeave = null;
    this.clickHandler = null;
  }

  /** 空白处点击取消选中：全图 click，未命中气泡图层时才触发（命中交给 bindHubClick 选站） */
  bindBackgroundClick(handler) {
    const map = this.map;
    if (!map?.on || this.boundBgClick) return;
    this.bgClickHandler = handler;
    this.boundBgClick = (e) => {
      if (!this.bgClickHandler) return;
      const hubLayers = [LAYER_HUBS].filter((id) => map.getLayer(id));
      const hit = hubLayers.length ? map.queryRenderedFeatures(e.point, { layers: hubLayers }) : [];
      if (!hit || !hit.length) this.bgClickHandler();
    };
    map.on("click", this.boundBgClick);
  }

  unbindBackgroundClick() {
    const map = this.map;
    if (map?.off && this.boundBgClick) map.off("click", this.boundBgClick);
    this.boundBgClick = null;
    this.bgClickHandler = null;
  }

  fitTo(lngLats, { padding = 110, maxZoom = 14.5 } = {}) {
    const map = this.map;
    if (!map?.fitBounds || !lngLats?.length) return;
    let minLng = Infinity;
    let minLat = Infinity;
    let maxLng = -Infinity;
    let maxLat = -Infinity;
    lngLats.forEach(([lng, lat]) => {
      if (!Number.isFinite(lng) || !Number.isFinite(lat)) return;
      if (lng < minLng) minLng = lng;
      if (lat < minLat) minLat = lat;
      if (lng > maxLng) maxLng = lng;
      if (lat > maxLat) maxLat = lat;
    });
    if (!Number.isFinite(minLng) || !Number.isFinite(minLat)) return;
    try {
      map.fitBounds(
        [
          [minLng, minLat],
          [maxLng, maxLat],
        ],
        { padding, duration: 700, maxZoom },
      );
    } catch (error) {
      /* fitBounds 对退化 bounds 可能抛错，忽略 */
    }
  }

  /** 先删 layer 再删 source（平台约定顺序） */
  clear() {
    this.unbindHubClick();
    this.unbindBackgroundClick();
    const map = this.map;
    if (!map?.getLayer) return;
    ALL_LAYERS.forEach((layerId) => {
      if (map.getLayer(layerId)) map.removeLayer(layerId);
    });
    ALL_SOURCES.forEach((sourceId) => {
      if (map.getSource(sourceId)) map.removeSource(sourceId);
    });
    this.sourceRefs.clear();
  }
}
