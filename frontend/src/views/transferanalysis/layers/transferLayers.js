/**
 * 换乘分析 MapLibre 图层管理器。选中枢纽后的长距离动态流向由同目录的 Deck 管理器负责；
 * 本类保留地铁线网、站点、换乘短连线与标签，并提供聚焦态降噪。
 *
 * 图层栈（自下而上）：换乘热力 → 地铁线网 → 行政区黑色虚线 → 弧线白描边 → 换乘弧线 →
 * 公交来向线 → 换乘站间连线 → 统一白心站点 → 枢纽站点 → 站名标注。全部幂等创建；clear() 先删
 * layer 再删 source（平台约定，source 被引用时删不掉）。
 *
 * 配色唯一来源 MAP_THEME.transfer（mapTheme.js），时间色带经 colorSchemes
 * 采样为 5 级；气泡半径在 JS 侧按 sqrt 预计算成 feature 属性，paint 只做
 * 缩放级别微调，避免复杂表达式。
 */
import { MAP_THEME, railwayCasingColor, railwayLineWidth } from "@/utils/mapTheme.js";
import { isDarkTheme } from "@/utils/uiTheme.js";
import { adminDistrictOutlineStyle } from "@/utils/adminDistrictRange.js";
import { sampleScheme } from "@/utils/colorSchemes.js";
import { buildFlowCurveFeatureCollection, emptyFlowCurveCollection } from "@/views/datavisualization/utils/flowCurves.js";
import { RailwayHatchLayerManager, metroHatchPathsFrom } from "@/views/datavisualization/layers/RailwayHatchLayer.js";

const SRC_HUBS = "ta-hubs-src";
const SRC_METRO_NETWORK = "ta-metro-network-src";
const SRC_METRO_NETWORK_ACTIVE = "ta-metro-network-active-src";
const SRC_FLOWS = "ta-flows-src";
const SRC_HEAT = "ta-heat-src";
const SRC_LINKS = "ta-links-src";
const SRC_ORIGIN_LINKS = "ta-origin-links-src";
const SRC_STOPS = "ta-stops-src";
const SRC_DISTRICT = "ta-display-range-src";

const LAYER_HEAT = "ta-heat";
const LAYER_METRO_NETWORK = "ta-metro-network";
const LAYER_METRO_NETWORK_ACTIVE = "ta-metro-network-active";
// 斑马嵌槽改由 deck.gl 画（maplibre 的 line-dasharray 小数缩放级块长会忽长忽短，
// 见 RailwayHatchLayer.js）；key 带 ta- 前缀以随页面切换挂起
const DECK_METRO_HATCH_KEY = "ta-metro-network-hatch";
const DECK_METRO_HATCH_ORDER = 200;
const LAYER_DISTRICT = "ta-display-range-outline";
const LAYER_FLOW_CASING = "ta-flow-casing";
const LAYER_FLOW = "ta-flow";
const LAYER_LINKS = "ta-links";
const LAYER_ORIGIN_LINKS = "ta-origin-links";
const LAYER_STOPS = "ta-stops";
const LAYER_STOP_LABELS = "ta-stop-labels";
const LAYER_HUBS = "ta-hubs";
const LAYER_HUB_LABELS = "ta-hub-labels";
// 站名标注全量放开的最小缩放级别：低于此级别只显示 Top 标注（防挤占），
// 达到后放开全部站名（重叠由符号碰撞检测自动隐藏，随继续放大逐步全显）
const LABEL_ALL_MINZOOM = 12;

// 地铁线（铁路制式）主线缩放档位：常态与聚焦态两套；斑马嵌槽由 deck 侧从同一份
// 档位表推导（RailwayHatchLayerManager.setWidthStops），两者必须同源。
const METRO_RAIL_STOPS = [[8, 2], [11, 3.4], [14, 5.5], [16, 7.2]];
const METRO_RAIL_STOPS_FOCUS = [[8, 4], [11, 6.4], [14, 9], [16, 11]];

// 删除顺序：栈顶到栈底
const ALL_LAYERS = [LAYER_STOP_LABELS, LAYER_HUB_LABELS, LAYER_HUBS, LAYER_STOPS, LAYER_LINKS, LAYER_ORIGIN_LINKS, LAYER_FLOW, LAYER_FLOW_CASING, LAYER_DISTRICT, LAYER_METRO_NETWORK_ACTIVE, LAYER_METRO_NETWORK, LAYER_HEAT];
const ALL_SOURCES = [SRC_HUBS, SRC_METRO_NETWORK, SRC_METRO_NETWORK_ACTIVE, SRC_FLOWS, SRC_HEAT, SRC_LINKS, SRC_ORIGIN_LINKS, SRC_STOPS, SRC_DISTRICT];

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
    this.metroLineClickHandler = null;
    this.boundMetroLineClick = null;
    this.boundMetroLineEnter = null;
    this.boundMetroLineLeave = null;
    this.sourceRefs = new Map(); // sourceId -> 上次 setData 的引用（引用相等短路）
    this.hatch = new RailwayHatchLayerManager({
      key: DECK_METRO_HATCH_KEY,
      order: DECK_METRO_HATCH_ORDER,
      beforeId: LAYER_STOPS,
    });
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

    // 行政区模式先铺全网灰线，再在其上叠区内红线；全市模式两份几何重合，灰线自然被遮住。
    if (!map.getLayer(LAYER_METRO_NETWORK)) {
      this.addLayerBelowBuildings({
        id: LAYER_METRO_NETWORK,
        type: "line",
        source: SRC_METRO_NETWORK,
        layout: { "line-join": "round", "line-cap": "round" },
        paint: {
          "line-color": MAP_THEME.network.outside,
          "line-opacity": MAP_THEME.network.outsideOpacity,
          "line-width": ["interpolate", ["linear"], ["zoom"], 8, 2, 11, 3.4, 14, 5.5, 16, 7.2],
        },
      });
    }
    // 区内地铁线：铁路制式（深色主线 + 等宽白/黄嵌槽虚线，浅色/暗色底图各一套）。
    // 嵌槽宽与虚线周期都由主线宽推导，缩放时三者等比，不会出现虚线周期与线宽脱钩。
    if (!map.getLayer(LAYER_METRO_NETWORK_ACTIVE)) {
      this.addLayerBelowBuildings({
        id: LAYER_METRO_NETWORK_ACTIVE,
        type: "line",
        source: SRC_METRO_NETWORK_ACTIVE,
        layout: { "line-join": "round", "line-cap": "round" },
        paint: {
          "line-color": railwayCasingColor(isDarkTheme.value),
          "line-opacity": 0.95,
          "line-width": railwayLineWidth(METRO_RAIL_STOPS),
        },
      });
    }
    this.hatch.attach(this.mapWrapper);
    this.hatch.setWidthStops(METRO_RAIL_STOPS);
    this.hatch.setDark(isDarkTheme.value);
    this.hatch.setVisible(true);

    const style = adminDistrictOutlineStyle(isDarkTheme.value);
    if (!map.getLayer(LAYER_DISTRICT)) {
      this.addLayerBelowBuildings({
        id: LAYER_DISTRICT,
        type: "line",
        source: SRC_DISTRICT,
        layout: { ...style.layout, visibility: "none" },
        paint: style.paint,
      });
    } else {
      map.setPaintProperty(LAYER_DISTRICT, "line-color", style.paint["line-color"]);
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

    if (!map.getLayer(LAYER_ORIGIN_LINKS)) {
      // 完整公交出行段：起点→终点统一用灰线，方向颜色只留给公交—地铁换乘短连线。
      this.addLayerBelowBuildings({
        id: LAYER_ORIGIN_LINKS,
        type: "line",
        source: SRC_ORIGIN_LINKS,
        layout: { "line-join": "round", "line-cap": "round" },
        paint: {
          "line-color": MAP_THEME.network.outside,
          "line-width": ["interpolate", ["linear"], ["get", "width"], 1, 1.2, 6, 4.4],
          "line-opacity": 0.72,
        },
      });
    }

    if (!map.getLayer(LAYER_LINKS)) {
      // 兼容层：两段式详情已迁往 Deck；保留 source/layer 供无 Deck 降级或后续静态模式复用。
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
      // 聚焦态所有公交节点共用同一站点语言：白心、深青蓝描边，仅用尺寸区分层级。
      this.addLayerBelowBuildings({
        id: LAYER_STOPS,
        type: "circle",
        source: SRC_STOPS,
        layout: { "circle-sort-key": ["get", "sortKey"] },
        paint: {
          "circle-radius": ["interpolate", ["linear"], ["zoom"], 10, ["*", ["get", "r"], 0.9], 13, ["get", "r"], 16, ["*", ["get", "r"], 1.08]],
          "circle-color": MAP_THEME.transfer.stationFill,
          "circle-opacity": 1,
          "circle-stroke-color": MAP_THEME.transfer.stationStroke,
          "circle-stroke-width": ["coalesce", ["get", "strokeWidth"], 1.5],
          "circle-stroke-opacity": 0.98,
          "circle-pitch-scale": "viewport",
          "circle-pitch-alignment": "viewport",
        },
      });
    }

    if (!map.getLayer(LAYER_STOP_LABELS)) {
      // 接驳公交站始终标注，外部端点仅标注 Top 项；空 label 不参与碰撞。
      this.addLayerBelowBuildings({
        id: LAYER_STOP_LABELS,
        type: "symbol",
        source: SRC_STOPS,
        layout: {
          "text-field": ["get", "label"],
          "text-size": 10.5,
          "text-anchor": "top",
          "text-offset": [0, 0.82],
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
          "circle-radius": ["interpolate", ["linear"], ["zoom"], 9, ["*", ["get", "r"], 0.86], 13, ["get", "r"], 16, ["*", ["get", "r"], 1.1]],
          "circle-color": ["get", "color"],
          "circle-opacity": ["coalesce", ["get", "opacity"], 0.78],
          "circle-stroke-color": ["get", "strokeColor"],
          "circle-stroke-width": ["get", "strokeWidth"],
          "circle-stroke-opacity": 0.98,
          "circle-pitch-scale": "viewport",
          "circle-pitch-alignment": "viewport",
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
          "text-field": ["get", "label"],
          "text-size": 11,
          "text-anchor": "top",
          "text-offset": [0, 0.95],
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

  setMetroNetwork(collection, activeCollection = collection) {
    this.setSourceData(SRC_METRO_NETWORK, collection);
    this.setSourceData(SRC_METRO_NETWORK_ACTIVE, activeCollection);
    // 换乘分析的 active source 已是"地铁线"专用集合，无需再按 mode 过滤
    this.hatch.setPaths(metroHatchPathsFrom(activeCollection, () => true));
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

  setOriginLinks(collection) {
    this.setSourceData(SRC_ORIGIN_LINKS, collection);
  }

  setStops(collection) {
    this.setSourceData(SRC_STOPS, collection);
  }

  setDistrict(collection) {
    this.setSourceData(SRC_DISTRICT, collection);
  }

  /** 选中枢纽时把地铁底网压到背景层，避免红白双线抢走 Deck 客流线的视觉焦点。 */
  setFocusMode(focused) {
    const map = this.map;
    if (!map?.getLayer || !map?.setPaintProperty) return;
    const detail = Boolean(focused);
    const opacityByLayer = {
      [LAYER_METRO_NETWORK]: detail ? 0.18 : MAP_THEME.network.outsideOpacity,
      [LAYER_METRO_NETWORK_ACTIVE]: detail ? 0.34 : 0.92,
    };
    Object.entries(opacityByLayer).forEach(([layerId, opacity]) => {
      if (map.getLayer(layerId)) map.setPaintProperty(layerId, "line-opacity", opacity);
    });
    this.hatch.setOpacity(detail ? 0.3 : 1);
  }

  /**
   * 换乘线路分析聚焦态：active source 已由页面收窄为选中线路的完整几何；
   * 底层全网完全隐去，主线加粗并切换为平台强调蓝，白色虚线提供内部高光。
   */
  setMetroLineFocusMode(focused) {
    const map = this.map;
    if (!map?.getLayer || !map?.setPaintProperty) return;
    const detail = Boolean(focused);
    if (map.getLayer(LAYER_METRO_NETWORK)) {
      map.setPaintProperty(
        LAYER_METRO_NETWORK,
        "line-opacity",
        detail ? 0 : MAP_THEME.network.outsideOpacity,
      );
    }
    const stops = detail ? METRO_RAIL_STOPS_FOCUS : METRO_RAIL_STOPS;
    if (map.getLayer(LAYER_METRO_NETWORK_ACTIVE)) {
      // 聚焦态换强调蓝主线并加粗，仍保留铁路嵌槽 —— 制式语言不随聚焦状态变化
      map.setPaintProperty(
        LAYER_METRO_NETWORK_ACTIVE,
        "line-color",
        detail ? MAP_THEME.route.down : railwayCasingColor(isDarkTheme.value),
      );
      map.setPaintProperty(LAYER_METRO_NETWORK_ACTIVE, "line-width", railwayLineWidth(stops));
      map.setPaintProperty(LAYER_METRO_NETWORK_ACTIVE, "line-opacity", detail ? 1 : 0.95);
    }
    this.hatch.setWidthStops(stops);
  }

  /** 底图明暗切换：style 不重建，铁路制式的主线/嵌槽色需手动跟随（聚焦态主线保持强调蓝）。 */
  applyRailwayTheme(focused = false) {
    const map = this.map;
    if (!map?.getLayer || !map?.setPaintProperty) return;
    const dark = isDarkTheme.value;
    if (map.getLayer(LAYER_METRO_NETWORK_ACTIVE) && !focused) {
      map.setPaintProperty(LAYER_METRO_NETWORK_ACTIVE, "line-color", railwayCasingColor(dark));
    }
    this.hatch.setDark(dark);
  }

  setVisibility(kind, visible) {
    const map = this.map;
    if (!map?.getLayer) return;
    const groups = {
      heat: [LAYER_HEAT],
      flows: [LAYER_FLOW_CASING, LAYER_FLOW],
      hubs: [LAYER_HUBS, LAYER_HUB_LABELS],
      metro: [LAYER_METRO_NETWORK, LAYER_METRO_NETWORK_ACTIVE],
      links: [LAYER_ORIGIN_LINKS, LAYER_LINKS, LAYER_STOPS, LAYER_STOP_LABELS],
      district: [LAYER_DISTRICT],
    };
    (groups[kind] || []).forEach((layerId) => {
      if (map.getLayer(layerId)) map.setLayoutProperty(layerId, "visibility", visible ? "visible" : "none");
    });
    // deck 斑马层不在 style.layers 里，跟着地铁分组单独开关
    if (kind === "metro") this.hatch.setVisible(visible);
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

  /** 地铁线路点击：以 active 线层作为命中面，属性中的 metroLineIdx 对齐换乘字典索引。 */
  bindMetroLineClick(handler) {
    const map = this.map;
    if (!map?.on || this.boundMetroLineClick) return;
    this.metroLineClickHandler = handler;
    this.boundMetroLineClick = (e) => {
      // 地铁线与枢纽气泡重叠时，优先保留气泡的站点选择语义。
      const hubHit = map.getLayer(LAYER_HUBS)
        ? map.queryRenderedFeatures(e.point, { layers: [LAYER_HUBS] })
        : [];
      if (hubHit?.length) return;
      const feature = e?.features?.[0];
      if (feature && this.metroLineClickHandler) this.metroLineClickHandler(feature.properties || {});
    };
    this.boundMetroLineEnter = () => {
      map.getCanvas().style.cursor = "pointer";
    };
    this.boundMetroLineLeave = () => {
      map.getCanvas().style.cursor = "";
    };
    map.on("click", LAYER_METRO_NETWORK_ACTIVE, this.boundMetroLineClick);
    map.on("mouseenter", LAYER_METRO_NETWORK_ACTIVE, this.boundMetroLineEnter);
    map.on("mouseleave", LAYER_METRO_NETWORK_ACTIVE, this.boundMetroLineLeave);
  }

  unbindMetroLineClick() {
    const map = this.map;
    if (map?.off && this.boundMetroLineClick) {
      map.off("click", LAYER_METRO_NETWORK_ACTIVE, this.boundMetroLineClick);
      map.off("mouseenter", LAYER_METRO_NETWORK_ACTIVE, this.boundMetroLineEnter);
      map.off("mouseleave", LAYER_METRO_NETWORK_ACTIVE, this.boundMetroLineLeave);
    }
    this.boundMetroLineClick = null;
    this.boundMetroLineEnter = null;
    this.boundMetroLineLeave = null;
    this.metroLineClickHandler = null;
  }

  /** 空白处点击取消选中：全图 click，未命中气泡或地铁线路时才触发。 */
  bindBackgroundClick(handler) {
    const map = this.map;
    if (!map?.on || this.boundBgClick) return;
    this.bgClickHandler = handler;
    this.boundBgClick = (e) => {
      if (!this.bgClickHandler) return;
      const interactiveLayers = [LAYER_HUBS, LAYER_METRO_NETWORK_ACTIVE].filter((id) => map.getLayer(id));
      const hit = interactiveLayers.length ? map.queryRenderedFeatures(e.point, { layers: interactiveLayers }) : [];
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
    this.hatch.dispose(); // deck 层不在 style.layers 里，ALL_LAYERS 那轮删不到
    this.unbindHubClick();
    this.unbindMetroLineClick();
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
