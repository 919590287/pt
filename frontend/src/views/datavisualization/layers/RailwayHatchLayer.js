// 铁路制式"斑马"嵌槽虚线（deck.gl PathLayer + PathStyleExtension）。
//
// 为什么不用 maplibre 的 line-dasharray：它把虚线烘焙成纹理，且纹理只在整数缩放级
// 重建。小数缩放级下着色器把纹理按 2^(zoom-tileZoom) 拉伸，叠加纹理本身的像素量化，
// 相邻虚线块会一长一短地跳 —— harness 实测 z13.5 时块长在 50px / 95px 之间交替，
// 根本排不成斑马。deck 的虚线是片元着色器里按路径距离取 mod 解析求值的，
// 无纹理、无量化，任意（含小数）缩放级下每一块都严格等长。
//
// 分工：实心部分（深色主线 / 客流着色主线 / 描边）仍由 maplibre line 图层画 ——
// 它们没有虚线，不受上述问题影响，且要保留点击拾取、按要素过滤、分档着色、sort-key。
// 本管理器只负责叠在最上层的那条白/黄斑马。

import { PathLayer } from "@deck.gl/layers";
import { PathStyleExtension } from "@deck.gl/extensions";
import { removeSharedDeckLayer, setSharedDeckLayer } from "./deckOverlayRegistry.js";
import {
  hexToRgbArray,
  railwayHatchColor,
  railwayHatchDashArray,
  railwayHatchWidthAtZoom,
} from "@/utils/mapTheme.js";

/** GeoJSON FeatureCollection → deck PathLayer 的路径数组（只取地铁要素） */
export function metroHatchPathsFrom(collection, isMetro = (feature) => feature?.properties?.mode === "metro") {
  const paths = [];
  for (const feature of collection?.features || []) {
    if (!isMetro(feature)) continue;
    const geometry = feature.geometry;
    if (geometry?.type === "LineString" && geometry.coordinates?.length > 1) {
      paths.push(geometry.coordinates);
    } else if (geometry?.type === "MultiLineString") {
      for (const line of geometry.coordinates || []) {
        if (line?.length > 1) paths.push(line);
      }
    }
  }
  return paths;
}

export class RailwayHatchLayerManager {
  /**
   * @param {string} key      deck 注册表 key（必须带页面前缀，否则切页不会被挂起）
   * @param {number} order    共享 overlay 内层序
   * @param {string} beforeId 插入到哪个 maplibre 图层之下（一般是站点图标层）
   */
  constructor({ key, order = 0, beforeId = undefined }) {
    this.key = key;
    this.order = order;
    this.beforeId = beforeId;
    this.mapWrapper = null;
    this.paths = null;
    this.widthStops = null;
    this.visible = false;
    this.dark = false;
    this.opacity = 1;
    // 只在整数缩放级切换时改宽度：与主线的 step 线宽同拍，级内斑马严格不动
    this.zoomBand = null;
    this.onZoom = () => this.syncZoomBand();
  }

  attach(mapWrapper) {
    if (this.mapWrapper === mapWrapper) return;
    this.detachZoomListener();
    this.mapWrapper = mapWrapper;
    this.zoomBand = null;
    mapWrapper?.map?.on?.("zoom", this.onZoom);
    this.commit();
  }

  detachZoomListener() {
    this.mapWrapper?.map?.off?.("zoom", this.onZoom);
  }

  syncZoomBand() {
    const zoom = this.mapWrapper?.map?.getZoom?.();
    if (!Number.isFinite(zoom)) return;
    const band = Math.floor(zoom);
    if (band === this.zoomBand) return;
    this.zoomBand = band;
    if (this.visible) this.commit();
  }

  setPaths(paths) {
    this.paths = paths;
    this.commit();
  }

  setWidthStops(stops) {
    this.widthStops = stops;
    this.commit();
  }

  setVisible(visible) {
    const next = Boolean(visible);
    if (next === this.visible) return;
    this.visible = next;
    this.commit();
  }

  setDark(dark) {
    const next = Boolean(dark);
    if (next === this.dark) return;
    this.dark = next;
    this.commit();
  }

  /** 聚焦态压暗（与 maplibre 主线的 line-opacity 同拍） */
  setOpacity(opacity) {
    const next = Math.max(0, Math.min(1, Number(opacity)));
    if (!Number.isFinite(next) || next === this.opacity) return;
    this.opacity = next;
    this.commit();
  }

  commit() {
    const wrapper = this.mapWrapper;
    if (!wrapper) return;
    if (!this.visible || !this.paths?.length || !this.widthStops?.length) {
      removeSharedDeckLayer(wrapper, this.key);
      return;
    }
    // 宽度取整数缩放级的值（与主线 step 线宽同一口径），级内恒定
    const band = Number.isFinite(this.zoomBand) ? this.zoomBand : Math.floor(wrapper.map?.getZoom?.() ?? 12);
    this.zoomBand = band;
    const layer = new PathLayer({
      id: this.key,
      data: this.paths,
      getPath: (path) => path,
      getColor: hexToRgbArray(railwayHatchColor(this.dark)),
      getWidth: railwayHatchWidthAtZoom(this.widthStops, band),
      opacity: this.opacity,
      widthUnits: "pixels",
      capRounded: false,
      jointRounded: false,
      // dashArray 单位同 maplibre：线宽的倍数。dashJustified 会按整条路径长度
      // 微调周期，长短不一的线路块长就不一致了 —— 全网斑马必须同尺寸，故关掉。
      getDashArray: railwayHatchDashArray(),
      dashJustified: false,
      // 地铁线是几十公里的长折线，不开高精度虚线时段间偏移会累积误差、接缝错位
      extensions: [new PathStyleExtension({ dash: true, highPrecisionDash: true })],
      beforeId: this.beforeId,
      pickable: false,
    });
    setSharedDeckLayer(wrapper, this.key, layer, this.order);
  }

  dispose() {
    this.detachZoomListener();
    if (this.mapWrapper) removeSharedDeckLayer(this.mapWrapper, this.key);
    this.mapWrapper = null;
    this.paths = null;
  }
}
