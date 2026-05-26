import { COORDINATE_SYSTEM } from "@deck.gl/core";
import { TextLayer } from "@deck.gl/layers";
import { Layer, MAP_EVENT, webMercatorToLngLat } from "@/mymap/index.js";
import { emptyFeatureCollection, stationsToFeatureCollection } from "./maplibreLayerUtils.js";
import { setSharedDeckLayer, removeSharedDeckLayer } from "./deckOverlayRegistry.js";

const LABEL_MIN_ZOOM = 15.4;
const BASE_LABEL_CHARACTER_SET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz -_()（）./·路站总站东南西北中大一二三四五六七八九十号线";

const BUS_STOP_FALLBACK_SVG = `
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
  <g fill="none" stroke="#000" stroke-width="5" stroke-linecap="round" stroke-linejoin="round">
    <path d="M13 57V9"/>
    <path d="M13 13h30a8 8 0 0 1 8 8v18a8 8 0 0 1-8 8H13z"/>
    <path d="M23 22h17"/>
    <path d="M23 33h10"/>
    <path d="M42 47v10"/>
  </g>
</svg>`;

const SUBWAY_STATION_FALLBACK_SVG = `
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
  <path d="M9 48h46L32 9z" fill="#000"/>
  <path d="M23 36h18l-9-14z" fill="#fff"/>
  <path d="M23 53h18" stroke="#000" stroke-width="6" stroke-linecap="round"/>
</svg>`;

function svgDataUrl(svg) {
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg.trim())}`;
}

function loadSvgImage(svg) {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = reject;
    image.src = svgDataUrl(svg);
  });
}

export class StationLayer extends Layer {
  name = "StationLayer";

  constructor(opt = {}) {
    super({ ...opt, zIndex: opt.zIndex ?? 1005 });
    this.stations = [];
    this.markerSize = Number(opt.markerSize) || 26;
    this.sourceId = `station-source-${this.id}`;
    this.busIconId = `station-bus-icon-${this.id}`;
    this.subwayIconId = `station-subway-icon-${this.id}`;
    this.circleLayerId = `station-circle-${this.id}`;
    this.iconLayerId = `station-icon-${this.id}`;
    this.labelLayerId = `station-label-${this.id}`;
    this.iconLoadStarted = false;
    this.iconLayerAdded = false;
  }

  onAdd(map) {
    super.onAdd(map);
    map.whenReady(() => {
      this.ensureMapLayer();
      this.updateSource();
      this.updatePaint();
      this.renderLabelLayer();
    });
  }

  on(type) {
    if (type === MAP_EVENT.UPDATE_ZOOM || type === MAP_EVENT.UPDATE_CAMERA_ROTATE) {
      this.renderLabelLayer();
    }
  }

  ensureMapLayer() {
    if (!this.map?.map || this.map.map.getSource(this.sourceId)) return;
    this.map.map.addSource(this.sourceId, {
      type: "geojson",
      data: emptyFeatureCollection(),
    });
    this.addCircleLayer();
    this.registerIcons().then(() => {
      this.addIconLayer();
      this.updatePaint();
    });
  }

  addCircleLayer() {
    if (!this.map?.map || this.map.map.getLayer(this.circleLayerId)) return;
    this.map.map.addLayer({
      id: this.circleLayerId,
      type: "circle",
      source: this.sourceId,
      paint: {
        "circle-radius": this.circleRadius(),
        "circle-color": [
          "case",
          ["==", ["get", "type"], "subway"],
          "#dc4c5d",
          "#1569de",
        ],
        "circle-stroke-color": "rgba(255,255,255,0.9)",
        "circle-stroke-width": this.circleStrokeWidth(),
        "circle-opacity": this.circleOpacity(),
      },
    });
  }

  addIconLayer() {
    if (!this.map?.map || this.iconLayerAdded || this.map.map.getLayer(this.iconLayerId)) return;
    this.map.map.addLayer({
      id: this.iconLayerId,
      type: "symbol",
      source: this.sourceId,
      layout: {
        "icon-image": [
          "case",
          ["==", ["get", "type"], "subway"],
          this.subwayIconId,
          this.busIconId,
        ],
        "icon-size": this.iconScale(),
        "icon-allow-overlap": true,
        "icon-ignore-placement": true,
        "icon-anchor": "center",
      },
      paint: {
        "icon-color": "#ffffff",
        "icon-halo-width": 0,
        "icon-opacity": this.iconOpacity(),
      },
    });
    this.iconLayerAdded = true;
  }

  registerIcons() {
    if (!this.map?.map) return Promise.resolve();
    if (this.iconLoadStarted) return this.iconLoadPromise || Promise.resolve();
    this.iconLoadStarted = true;
    this.iconLoadPromise = Promise.all([
      loadSvgImage(BUS_STOP_FALLBACK_SVG),
      loadSvgImage(SUBWAY_STATION_FALLBACK_SVG),
    ]).then(([busImage, subwayImage]) => {
      if (!this.map?.map || this.isDisposed) return;
      if (!this.map.map.hasImage(this.busIconId)) {
        this.map.map.addImage(this.busIconId, busImage, { pixelRatio: 2, sdf: true });
      }
      if (!this.map.map.hasImage(this.subwayIconId)) {
        this.map.map.addImage(this.subwayIconId, subwayImage, { pixelRatio: 2, sdf: true });
      }
    }).catch((error) => {
      console.warn("[StationLayer] station svg icon load failed", error);
    });
    return this.iconLoadPromise;
  }

  iconScale() {
    const highZoomScale = Math.max(0.11, Math.min(0.34, this.markerSize / 132));
    const midZoomScale = Math.max(0.08, highZoomScale * 0.72);
    return [
      "interpolate",
      ["linear"],
      ["zoom"],
      10.5,
      0,
      12,
      Math.max(0.055, highZoomScale * 0.45),
      14,
      midZoomScale,
      16,
      highZoomScale,
    ];
  }

  circleRadius() {
    const highZoomRadius = Math.max(3.8, Math.min(16, this.markerSize * 0.36));
    return [
      "interpolate",
      ["linear"],
      ["zoom"],
      8,
      0.35,
      10,
      0.8,
      12,
      Math.max(1.15, highZoomRadius * 0.24),
      14,
      Math.max(2.2, highZoomRadius * 0.58),
      16,
      highZoomRadius,
    ];
  }

  circleStrokeWidth() {
    return [
      "interpolate",
      ["linear"],
      ["zoom"],
      9,
      0,
      11,
      0.15,
      13,
      0.45,
      15,
      1.05,
    ];
  }

  circleOpacity() {
    return [
      "interpolate",
      ["linear"],
      ["zoom"],
      8,
      0.05,
      10,
      0.14,
      12,
      0.38,
      14,
      0.74,
      16,
      0.92,
    ];
  }

  iconOpacity() {
    return [
      "interpolate",
      ["linear"],
      ["zoom"],
      10.8,
      0,
      12,
      0.32,
      13.5,
      0.72,
      15,
      0.94,
    ];
  }

  labelData() {
    return this.stations
      .map((station) => {
        const coords = webMercatorToLngLat(station.x, station.y);
        if (!coords.every(Number.isFinite)) return null;
        return {
          name: station.name || station.facilityName || "",
          position: coords,
          type: station.type || "bus",
        };
      })
      .filter((station) => station?.name);
  }

  labelCharacterSet() {
    const chars = new Set(BASE_LABEL_CHARACTER_SET.split(""));
    for (const station of this.stations) {
      const name = station.name || station.facilityName || "";
      for (const char of String(name)) {
        chars.add(char);
      }
    }
    return [...chars];
  }

  setData(stations = []) {
    this.stations = Array.isArray(stations) ? stations : [];
    this.updateSource();
    this.renderLabelLayer();
  }

  updateSource() {
    if (!this.map?.map?.getSource(this.sourceId)) return;
    this.map.map.getSource(this.sourceId).setData(stationsToFeatureCollection(this.stations));
  }

  updatePaint() {
    if (!this.map?.map) return;
    if (this.map.map.getLayer(this.circleLayerId)) {
      this.map.map.setPaintProperty(this.circleLayerId, "circle-radius", this.circleRadius());
      this.map.map.setPaintProperty(this.circleLayerId, "circle-stroke-width", this.circleStrokeWidth());
      this.map.map.setPaintProperty(this.circleLayerId, "circle-opacity", this.circleOpacity());
    }
    if (this.map.map.getLayer(this.iconLayerId)) {
      this.map.map.setLayoutProperty(this.iconLayerId, "icon-size", this.iconScale());
      this.map.map.setPaintProperty(this.iconLayerId, "icon-opacity", this.iconOpacity());
    }
    this.renderLabelLayer();
  }

  renderLabelLayer() {
    if (!this.map?.map || this.visible === false || Number(this.map.zoom) < LABEL_MIN_ZOOM) {
      removeSharedDeckLayer(this.map, this.labelLayerId);
      return;
    }
    const layer = new TextLayer({
      id: this.labelLayerId,
      data: this.labelData(),
      coordinateSystem: COORDINATE_SYSTEM.LNGLAT,
      getPosition: (item) => item.position,
      getText: (item) => item.name,
      getSize: 12,
      getColor: [21, 45, 79, 235],
      getPixelOffset: [0, -Math.max(20, this.markerSize * 0.82)],
      getTextAnchor: "middle",
      getAlignmentBaseline: "bottom",
      fontFamily: "system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
      fontWeight: 700,
      characterSet: this.labelCharacterSet(),
      outlineWidth: 3,
      outlineColor: [255, 255, 255, 230],
      sizeUnits: "pixels",
      billboard: true,
      pickable: false,
      parameters: {
        depthTest: false,
      },
    });
    setSharedDeckLayer(this.map, this.labelLayerId, layer);
  }

  setMarkerSize(markerSize) {
    const nextMarkerSize = Number(markerSize);
    if (!Number.isFinite(nextMarkerSize)) return;
    const clamped = Math.max(10, Math.min(42, nextMarkerSize));
    if (Math.abs(clamped - this.markerSize) < 0.01) return;
    this.markerSize = clamped;
    this.updatePaint();
  }

  hide() {
    super.hide();
    if (this.map?.map?.getLayer(this.iconLayerId)) {
      this.map.map.setLayoutProperty(this.iconLayerId, "visibility", "none");
    }
    if (this.map?.map?.getLayer(this.circleLayerId)) {
      this.map.map.setLayoutProperty(this.circleLayerId, "visibility", "none");
    }
    removeSharedDeckLayer(this.map, this.labelLayerId);
  }

  show() {
    super.show();
    if (this.map?.map?.getLayer(this.iconLayerId)) {
      this.map.map.setLayoutProperty(this.iconLayerId, "visibility", "visible");
    }
    if (this.map?.map?.getLayer(this.circleLayerId)) {
      this.map.map.setLayoutProperty(this.circleLayerId, "visibility", "visible");
    }
    this.renderLabelLayer();
  }

  dispose() {
    removeSharedDeckLayer(this.map, this.labelLayerId);
    if (this.map?.map) {
      if (this.map.map.getLayer(this.iconLayerId)) {
        this.map.map.removeLayer(this.iconLayerId);
      }
      if (this.map.map.getLayer(this.circleLayerId)) {
        this.map.map.removeLayer(this.circleLayerId);
      }
      if (this.map.map.getSource(this.sourceId)) {
        this.map.map.removeSource(this.sourceId);
      }
      [this.busIconId, this.subwayIconId].forEach((imageId) => {
        if (this.map.map.hasImage(imageId)) {
          this.map.map.removeImage(imageId);
        }
      });
    }
    super.dispose();
  }
}
