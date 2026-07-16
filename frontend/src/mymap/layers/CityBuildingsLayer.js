import { Layer } from "../main/Layer.js";
import { webMercatorToLngLat } from "../main/MyMap.js";
import { getBuildingTile } from "@/api/buildings.js";

function closeRing(ring) {
  if (!ring.length) return ring;
  const first = ring[0];
  const last = ring[ring.length - 1];
  if (first[0] !== last[0] || first[1] !== last[1]) {
    return [...ring, first];
  }
  return ring;
}

function buildingToFeature(building, center) {
  const [centerX = 0, centerY = 0] = center || [];
  const coordinates = (building?.rings || [])
    .map((ring) => {
      const points = [];
      for (let i = 0; i < ring.length - 1; i += 2) {
        points.push(webMercatorToLngLat(centerX + Number(ring[i]), centerY + Number(ring[i + 1])));
      }
      return closeRing(points);
    })
    .filter((ring) => ring.length >= 4);

  if (!coordinates.length) return null;
  return {
    type: "Feature",
    geometry: {
      type: "Polygon",
      coordinates,
    },
    properties: {
      height: Math.max(0, Number(building.height) || 0),
    },
  };
}

function buildingsToFeatureCollection(data) {
  return {
    type: "FeatureCollection",
    features: (data?.buildings || [])
      .map((building) => buildingToFeature(building, data?.center))
      .filter(Boolean),
  };
}

function containsBounds(outer, inner, margin = 0) {
  if (!outer || !inner) return false;
  return inner.minX - margin >= outer.minX
    && inner.minY - margin >= outer.minY
    && inner.maxX + margin <= outer.maxX
    && inner.maxY + margin <= outer.maxY;
}

function expandedBounds(bounds, padding) {
  return {
    minX: bounds.minX - padding,
    minY: bounds.minY - padding,
    maxX: bounds.maxX + padding,
    maxY: bounds.maxY + padding,
  };
}

const DEFAULT_BUILDING_COLOR = [
  "interpolate",
  ["linear"],
  ["coalesce", ["get", "height"], 0],
  0,
  "#e4ebf1",
  18,
  "#c9d6e2",
  45,
  "#afc1d1",
  90,
  "#91a9bf",
];

export class CityBuildingsLayer extends Layer {
  name = "CityBuildingsLayer";

  constructor(opt = {}) {
    super({ ...opt, zIndex: opt.zIndex ?? 8 });
    this.minZoom = opt.minZoom ?? 12;
    this.prefetchMeters = opt.prefetchMeters ?? 900;
    this.updateDelay = opt.updateDelay ?? 60;
    this.maxFeatures = opt.maxFeatures ?? 20000;
    this.maxViewDistanceScale = Number.isFinite(Number(opt.maxViewDistanceScale)) ? Number(opt.maxViewDistanceScale) : 0.9;
    this.maxViewDistanceMeters = Number.isFinite(Number(opt.maxViewDistanceMeters)) ? Number(opt.maxViewDistanceMeters) : 6000;
    this.heightField = opt.heightField || "HEIGHT";
    this.shpPath = opt.shpPath || "";
    this.color = typeof opt.color === "string" ? opt.color : `#${Number(opt.color ?? 0xd8dde2).toString(16).padStart(6, "0")}`;
    this.shadeByHeight = opt.shadeByHeight !== false;
    this.outlineColor = opt.outlineColor || "rgba(55,76,98,0.58)";
    this.outlineOpacity = Number.isFinite(Number(opt.outlineOpacity)) ? Number(opt.outlineOpacity) : 0.22;
    this.shadowColor = opt.shadowColor || "#26384a";
    this.shadowOpacity = Number.isFinite(Number(opt.shadowOpacity)) ? Number(opt.shadowOpacity) : 0.14;
    this.minHeight = opt.minHeight ?? 3;
    this.sourceId = `buildings-source-${this.id}`;
    this.shadowLayerId = `buildings-shadow-${this.id}`;
    this.layerId = `buildings-extrusion-${this.id}`;
    this.outlineLayerId = `buildings-outline-${this.id}`;
    this.loadedBounds = null;
    this.loadedZoom = null;
    // 上次响应是否被后端限流（截断或像素剔除）：是的话放大跨档需要重新拉细节
    this.responseLimited = false;
    this.pendingBounds = null;
    this.pendingZoom = null;
    this.hasBuildingData = false;
    this._loadTimer = null;
    this._controller = null;
    this._requestId = 0;
    this._stackFrame = null;
    this._styleDataHandler = null;
    // 业务专题可临时独占 3D 场景；只抑制展示与请求，不卸载已有建筑数据。
    this.suppressed = false;
  }

  onAdd(map) {
    super.onAdd(map);
    map.whenReady(() => {
      this.ensureMapLayer();
      this.updateActivity();
    });
  }

  on(type) {
    if (!this.map) return;
    if (type === "update:center" || type === "update:zoom" || type === "update:camera:rotate" || type === "update:view:mode") {
      this.updateActivity();
    }
  }

  ensureMapLayer() {
    if (!this.map?.map || this.map.map.getSource(this.sourceId)) return;
    this.map.map.addSource(this.sourceId, {
      type: "geojson",
      data: { type: "FeatureCollection", features: [] },
    });
    this.applySceneLight();
    this.map.map.addLayer({
      id: this.shadowLayerId,
      type: "fill",
      source: this.sourceId,
      paint: {
        "fill-color": this.shadowColor,
        "fill-opacity": [
          "interpolate",
          ["linear"],
          ["zoom"],
          11.5,
          0,
          13,
          this.shadowOpacity * 0.62,
          16,
          this.shadowOpacity,
        ],
        "fill-translate": [5, 7],
        "fill-translate-anchor": "viewport",
      },
    });
    this.map.map.addLayer({
      id: this.layerId,
      type: "fill-extrusion",
      source: this.sourceId,
      paint: {
        "fill-extrusion-color": this.shadeByHeight ? DEFAULT_BUILDING_COLOR : this.color,
        "fill-extrusion-height": ["max", ["coalesce", ["get", "height"], 0], this.minHeight],
        "fill-extrusion-base": 0,
        "fill-extrusion-opacity": 0.96,
        "fill-extrusion-vertical-gradient": true,
      },
    });
    this.map.map.addLayer({
      id: this.outlineLayerId,
      type: "line",
      source: this.sourceId,
      paint: {
        "line-color": this.outlineColor,
        "line-opacity": [
          "interpolate",
          ["linear"],
          ["zoom"],
          12,
          0,
          14,
          this.outlineOpacity * 0.55,
          18,
          this.outlineOpacity,
        ],
        "line-width": [
          "interpolate",
          ["linear"],
          ["zoom"],
          12,
          0.35,
          16,
          0.8,
          20,
          1.15,
        ],
        "line-blur": 0.15,
      },
    });
    this._styleDataHandler = () => this.scheduleStackSync();
    this.map.map.on("styledata", this._styleDataHandler);
    this.scheduleStackSync();
    this.map.buildingLayerId = this.layerId;
    this.map.layers?.forEach((layer) => {
      if (layer !== this && typeof layer.updatePaint === "function") {
        layer.updatePaint();
      }
    });
  }

  scheduleStackSync() {
    if (this._stackFrame || !this.map?.map) return;
    this._stackFrame = window.requestAnimationFrame(() => {
      this._stackFrame = null;
      this.syncBuildingStack();
    });
  }

  syncBuildingStack() {
    const map = this.map?.map;
    if (!map) return;
    const desired = [this.shadowLayerId, this.layerId, this.outlineLayerId]
      .filter((layerId) => map.getLayer(layerId));
    if (!desired.length) return;
    const layerIds = (map.getStyle()?.layers || []).map((layer) => layer.id);
    const tail = layerIds.slice(-desired.length);
    if (desired.every((layerId, index) => tail[index] === layerId)) return;

    // 建筑作为地图场景中的实体遮挡层固定在业务图层顶部。新增线路、站点、deck 或
    // Three 自定义层后会触发 styledata，这里只在顺序确有变化时恢复，无持续重排开销。
    desired.forEach((layerId) => map.moveLayer(layerId));
  }

  applySceneLight() {
    if (!this.map?.map?.setLight) return;
    this.map.map.setLight({
      anchor: "map",
      color: "#f1f7fb",
      intensity: 0.72,
      position: [1.18, 205, 38],
    });
  }

  shouldShowBuildings() {
    if (!this.map || this.suppressed) return false;
    const is3D = this.map.enableRotate || Math.abs(this.map.pitch - 90) > 0.5;
    return is3D && this.map.zoom >= this.minZoom;
  }

  setSuppressed(suppressed) {
    const next = !!suppressed;
    if (next === this.suppressed) return;
    this.suppressed = next;
    this.updateActivity();
  }

  updateActivity() {
    if (!this.map?.map?.getLayer(this.layerId)) return;
    const shouldShow = this.shouldShowBuildings();
    this.map.map.setLayoutProperty(this.layerId, "visibility", shouldShow ? "visible" : "none");
    if (this.map.map.getLayer(this.shadowLayerId)) {
      this.map.map.setLayoutProperty(this.shadowLayerId, "visibility", shouldShow ? "visible" : "none");
    }
    if (this.map.map.getLayer(this.outlineLayerId)) {
      this.map.map.setLayoutProperty(this.outlineLayerId, "visibility", shouldShow ? "visible" : "none");
    }
    if (shouldShow) {
      this.scheduleLoad();
    } else {
      this.cancelPendingLoad();
    }
  }

  // 建筑请求所用的"视野地面范围"。2D 时等价于整屏范围；3D 俯仰时把远端裁到
  // 视距内——否则 getBounds 会延伸到地平线，后端 bbox 命中几十万栋并触发截断，
  // 返回的是 shp 文件顺序的前 N 栋（多半不在镜头前），表现为眼前没建筑、
  // 建筑"跑"到视野外的某一片。视距随相机高度自适应，并有整屏对角线兜底，
  // 保证平视/低俯仰时永远不小于实际可见范围。
  resolveViewBounds() {
    const map = this.map;
    const canvas = map.map.getCanvas();
    const width = canvas.clientWidth || canvas.width || 1;
    const height = canvas.clientHeight || canvas.height || 1;
    // 屏幕底边是俯仰下离相机最近的地面，在此实测地面分辨率；平视时即整屏分辨率
    const left = map.WindowXYToWebMercator(width / 2 - 40, height);
    const right = map.WindowXYToWebMercator(width / 2 + 40, height);
    const metersPerPixel = Math.hypot(right[0] - left[0], right[1] - left[1]) / 80;
    const flatReach = Math.hypot(width / 2, height) * metersPerPixel * 1.15;
    const base = this.maxViewDistanceMeters + this.maxViewDistanceScale * map.cameraHeight;
    const viewDistance = Number.isFinite(flatReach) ? Math.max(base, flatReach) : base;
    return map.getViewGroundRangeWebMercator(viewDistance);
  }

  // 已加载数据是被限流的（截断/像素剔除）且此后放大超过约一档：范围虽被包含，
  // 但当前档位应有更多细节（低缩放时被剔的小建筑、被截断的部分），需重新请求。
  zoomOutgrownLoaded() {
    return this.responseLimited
      && Number.isFinite(this.loadedZoom)
      && this.map.zoom > this.loadedZoom + 0.75;
  }

  zoomOutgrownPending() {
    return this.pendingBounds != null
      && Number.isFinite(this.pendingZoom)
      && this.map.zoom > this.pendingZoom + 0.75;
  }

  scheduleLoad() {
    if (!this.map || !this.shouldShowBuildings()) return;
    const visibleBounds = this.resolveViewBounds();
    const refreshMargin = Math.max(0, this.prefetchMeters * 0.35);

    // 当前相机（含 bearing/pitch 后的四角范围）仍处于已预取区域时，已有 GeoJSON 会由
    // MapLibre 在同一渲染帧内随相机移动并由 GPU 视口裁剪，不需要重新请求或 setData。
    if (!this.zoomOutgrownLoaded() && containsBounds(this.loadedBounds, visibleBounds, refreshMargin)) return;
    if (!this.zoomOutgrownPending() && containsBounds(this.pendingBounds, visibleBounds, 0)) return;

    window.clearTimeout(this._loadTimer);
    const delay = !this.hasBuildingData || !containsBounds(this.loadedBounds, visibleBounds, 0)
      ? 0
      : this.updateDelay;
    this._loadTimer = window.setTimeout(() => this.loadBuildings(), delay);
  }

  cancelPendingLoad() {
    window.clearTimeout(this._loadTimer);
    this._loadTimer = null;
    this._controller?.abort?.();
    this._controller = null;
    this.pendingBounds = null;
    this.pendingZoom = null;
  }

  async loadBuildings() {
    if (!this.map || !this.shouldShowBuildings()) return;
    const bounds = this.resolveViewBounds();
    if (!this.zoomOutgrownLoaded() && containsBounds(this.loadedBounds, bounds, Math.max(0, this.prefetchMeters * 0.35))) return;
    if (!this.zoomOutgrownPending() && containsBounds(this.pendingBounds, bounds, 0)) return;
    const requestBounds = expandedBounds(bounds, this.prefetchMeters);
    const focus = bounds.anchor || [(bounds.minX + bounds.maxX) / 2, (bounds.minY + bounds.maxY) / 2];
    const requestZoom = this.map.zoom;

    const requestId = ++this._requestId;
    this._controller?.abort?.();
    this._controller = new AbortController();
    this.pendingBounds = requestBounds;
    this.pendingZoom = requestZoom;

    try {
      const res = await getBuildingTile(
        {
          ...requestBounds,
          // 视点最近的地面点：后端截断时从它开始按网格轮询分配配额（近处先成形）
          focusX: focus[0],
          focusY: focus[1],
          zoom: requestZoom,
          maxFeatures: this.maxFeatures,
          shpPath: this.shpPath,
          heightField: this.heightField,
        },
        { signal: this._controller.signal },
      );
      if (requestId !== this._requestId || this.isDisposed) return;
      this.loadedBounds = requestBounds;
      this.loadedZoom = requestZoom;
      this.responseLimited = !!(res.data?.truncated || res.data?.culled);
      this.hasBuildingData = true;
      this.map.map.getSource(this.sourceId)?.setData(buildingsToFeatureCollection(res.data || {}));
      this.scheduleStackSync();

      // 请求期间相机可能继续移动/旋转；若新视野已越出本次预取范围，立即补下一块，
      // 不等待 moveend，避免连续拖动后建筑迟到。
      const currentBounds = this.resolveViewBounds();
      if (!containsBounds(requestBounds, currentBounds, 0)) {
        this.scheduleLoad();
      }
    } catch (error) {
      if (error?.message !== "canceled" && error?.code !== "ERR_CANCELED") {
        console.warn("[CityBuildingsLayer] failed to load buildings:", error);
      }
    } finally {
      if (requestId === this._requestId) {
        this._controller = null;
        this.pendingBounds = null;
        this.pendingZoom = null;
      }
    }
  }

  dispose() {
    this.cancelPendingLoad();
    if (this._stackFrame) {
      window.cancelAnimationFrame(this._stackFrame);
      this._stackFrame = null;
    }
    if (this.map?.map) {
      if (this._styleDataHandler) {
        this.map.map.off("styledata", this._styleDataHandler);
        this._styleDataHandler = null;
      }
      if (this.map.map.getLayer(this.outlineLayerId)) {
        this.map.map.removeLayer(this.outlineLayerId);
      }
      if (this.map.map.getLayer(this.layerId)) {
        this.map.map.removeLayer(this.layerId);
      }
      if (this.map.map.getLayer(this.shadowLayerId)) {
        this.map.map.removeLayer(this.shadowLayerId);
      }
      if (this.map.map.getSource(this.sourceId)) {
        this.map.map.removeSource(this.sourceId);
      }
      if (this.map.buildingLayerId === this.layerId) {
        this.map.buildingLayerId = null;
      }
    }
    super.dispose();
  }
}
