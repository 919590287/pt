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

export class CityBuildingsLayer extends Layer {
  name = "CityBuildingsLayer";

  constructor(opt = {}) {
    super({ ...opt, zIndex: opt.zIndex ?? 8 });
    this.minZoom = opt.minZoom ?? 12.5;
    this.prefetchMeters = opt.prefetchMeters ?? 900;
    this.updateDelay = opt.updateDelay ?? 180;
    this.maxFeatures = opt.maxFeatures ?? 20000;
    this.heightField = opt.heightField || "HEIGHT";
    this.shpPath = opt.shpPath || "";
    this.color = typeof opt.color === "string" ? opt.color : `#${Number(opt.color ?? 0xd8dde2).toString(16).padStart(6, "0")}`;
    this.outlineColor = opt.outlineColor || "rgba(82,96,112,0.38)";
    this.outlineOpacity = Number.isFinite(Number(opt.outlineOpacity)) ? Number(opt.outlineOpacity) : 0.12;
    this.minHeight = opt.minHeight ?? 0.5;
    this.sourceId = `buildings-source-${this.id}`;
    this.layerId = `buildings-extrusion-${this.id}`;
    this.outlineLayerId = `buildings-outline-${this.id}`;
    this.loadedBounds = null;
    this._loadTimer = null;
    this._controller = null;
    this._requestId = 0;
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
    if (type === "update:center" || type === "update:zoom" || type === "update:camera:rotate") {
      this.updateActivity();
    }
  }

  ensureMapLayer() {
    if (!this.map?.map || this.map.map.getSource(this.sourceId)) return;
    this.map.map.addSource(this.sourceId, {
      type: "geojson",
      data: { type: "FeatureCollection", features: [] },
    });
    this.map.map.addLayer({
      id: this.layerId,
      type: "fill-extrusion",
      source: this.sourceId,
      paint: {
        "fill-extrusion-color": this.color,
        "fill-extrusion-height": ["max", ["get", "height"], this.minHeight],
        "fill-extrusion-base": 0,
        "fill-extrusion-opacity": 0.86,
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
    this.map.buildingLayerId = this.layerId;
    this.map.layers?.forEach((layer) => {
      if (layer !== this && typeof layer.updatePaint === "function") {
        layer.updatePaint();
      }
    });
  }

  shouldShowBuildings() {
    if (!this.map) return false;
    return this.map.zoom > this.minZoom && (this.map.enableRotate || Math.abs(this.map.pitch - 90) > 0.5);
  }

  updateActivity() {
    if (!this.map?.map?.getLayer(this.layerId)) return;
    const shouldShow = this.shouldShowBuildings();
    this.map.map.setLayoutProperty(this.layerId, "visibility", shouldShow ? "visible" : "none");
    if (this.map.map.getLayer(this.outlineLayerId)) {
      this.map.map.setLayoutProperty(this.outlineLayerId, "visibility", shouldShow ? "visible" : "none");
    }
    if (shouldShow) {
      this.scheduleLoad();
    } else {
      this.cancelPendingLoad();
    }
  }

  scheduleLoad() {
    window.clearTimeout(this._loadTimer);
    this._loadTimer = window.setTimeout(() => this.loadBuildings(), this.updateDelay);
  }

  cancelPendingLoad() {
    window.clearTimeout(this._loadTimer);
    this._loadTimer = null;
    this._controller?.abort?.();
    this._controller = null;
  }

  async loadBuildings() {
    if (!this.map || !this.shouldShowBuildings()) return;
    const bounds = this.map.getWindowRangeAndWebMercator();
    const pad = this.prefetchMeters;
    const requestBounds = {
      minX: bounds.minX - pad,
      minY: bounds.minY - pad,
      maxX: bounds.maxX + pad,
      maxY: bounds.maxY + pad,
    };
    const boundsKey = JSON.stringify(requestBounds);
    if (boundsKey === this.loadedBounds) return;

    const requestId = ++this._requestId;
    this._controller?.abort?.();
    this._controller = new AbortController();

    try {
      const res = await getBuildingTile(
        {
          ...requestBounds,
          zoom: this.map.zoom,
          maxFeatures: this.maxFeatures,
          shpPath: this.shpPath,
          heightField: this.heightField,
        },
        { signal: this._controller.signal },
      );
      if (requestId !== this._requestId || this.isDisposed) return;
      this.loadedBounds = boundsKey;
      this.map.map.getSource(this.sourceId)?.setData(buildingsToFeatureCollection(res.data || {}));
    } catch (error) {
      if (error?.message !== "canceled" && error?.code !== "ERR_CANCELED") {
        console.warn("[CityBuildingsLayer] failed to load buildings:", error);
      }
    } finally {
      if (requestId === this._requestId) {
        this._controller = null;
      }
    }
  }

  dispose() {
    this.cancelPendingLoad();
    if (this.map?.map) {
      if (this.map.map.getLayer(this.outlineLayerId)) {
        this.map.map.removeLayer(this.outlineLayerId);
      }
      if (this.map.map.getLayer(this.layerId)) {
        this.map.map.removeLayer(this.layerId);
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
