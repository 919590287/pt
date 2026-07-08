import maplibregl from "maplibre-gl";
import * as THREE from "three";
import { EventListener } from "./EventListener";

export const MAP_EVENT = {
  HANDLE_NO_PICK: "handle:no:pick",
  HANDLE_PICK_LEFT: "handle:pick",
  HANDLE_PICK_RIGHT: "handle:pick:right",
  HANDLE_MOUSE_MOVE_PICK: "handle:mousemove:pick",
  HANDLE_CLICK_LEFT: "handle:click",
  HANDLE_CLICK_RIGHT: "handle:contextmenu",
  HANDLE_MOUSE_LEFT_DOWN: "handle:left:mousedown",
  HANDLE_MOUSE_LEFT_UP: "handle:left:mouseup",
  HANDLE_MOUSE_RIGHT_DOWN: "handle:right:mousedown",
  HANDLE_MOUSE_RIGHT_UP: "handle:right:mouseup",
  HANDLE_MOUSE_MOVE: "handle:mousemove",
  UPDATE_ZOOM: "update:zoom",
  UPDATE_CENTER: "update:center",
  UPDATE_CAMERA_HEIGHT: "update:camera:height",
  UPDATE_CAMERA_ROTATE: "update:camera:rotate",
  UPDATE_CAMERA_POSITION: "update:camera:position",
  UPDATE_RENDERER_SIZE: "update:renderer:size",
  LAYER_BEFORE_RENDER: "layer:before:render",
  LAYER_AFTER_RENDER: "layer:after:render",
  LAYER_LOADING: "layer:loading",
};

export const MAP_ZOOM_RANGE = {
  BASE: 18,
  MIN: 8,
  MAX: 22,
};

const EARTH_RADIUS = 6378137.0;
const MAP_MAX_PITCH = 85;
const LEGACY_MIN_PITCH = 90 - MAP_MAX_PITCH;
const MIDDLE_DRAG_ROTATE_SPEED = 0.34;
const MIDDLE_DRAG_PITCH_SPEED = 0.32;
const WASD_PAN_PIXELS_PER_SECOND = 620;

export function webMercatorToLngLat(x, y) {
  const lng = (Number(x) / EARTH_RADIUS) * (180 / Math.PI);
  const lat = (2 * Math.atan(Math.exp(Number(y) / EARTH_RADIUS)) - Math.PI / 2) * (180 / Math.PI);
  return [lng, lat];
}

export function lngLatToWebMercator(lng, lat) {
  const limitedLat = Math.max(-85.05112878, Math.min(85.05112878, Number(lat) || 0));
  const x = EARTH_RADIUS * (Number(lng) || 0) * Math.PI / 180;
  const y = EARTH_RADIUS * Math.log(Math.tan(Math.PI / 4 + limitedLat * Math.PI / 360));
  return [x, y];
}

function numberColorToHex(value, fallback = "#f5f5f5") {
  if (typeof value === "string") return value;
  if (!Number.isFinite(Number(value))) return fallback;
  return `#${Number(value).toString(16).padStart(6, "0").slice(-6)}`;
}

function mapPitchToLegacy(pitch) {
  return 90 - Number(pitch || 0);
}

function legacyPitchToMap(pitch) {
  return Math.max(0, Math.min(MAP_MAX_PITCH, 90 - Number(pitch ?? 90)));
}

function normalizeBearing(value) {
  let next = Number(value) || 0;
  while (next <= -180) next += 360;
  while (next > 180) next -= 360;
  return next;
}

function urlTemplateFromConfig(config = {}) {
  if (Array.isArray(config.tiles) && config.tiles.length) {
    return config.tiles;
  }
  if (typeof config.getUrl === "function") {
    try {
      return [
        config.getUrl.call({
          zoom: "{z}",
          row: "{x}",
          col: "{y}",
        }),
      ];
    } catch (error) {
      console.warn("[MapLibre] basemap getUrl template failed:", error);
    }
  }
  return ["https://basemaps.cartocdn.com/light_all/{z}/{x}/{y}@2x.png"];
}

function runtimeMapPixelRatio() {
  const value = Number(window.APP_CONFIG?.mapPixelRatio);
  if (!Number.isFinite(value) || value <= 0) return undefined;
  return Math.max(1, Math.min(3, value));
}

function createMapStyle() {
  if (window.MAPLIBRE_STYLE) {
    return window.MAPLIBRE_STYLE;
  }
  const styles = window.MAP_LAYER_STYLE || [{}];
  const index = window.DEFAULT_MAP_LAYER_STYLE_INDEX || 0;
  const config = styles[index] || styles[0] || {};
  const background = numberColorToHex(config.background, "#f5f5f5");
  return {
    version: 8,
    sources: {
      "base-raster": rasterSourceConfig(config),
    },
    layers: [
      {
        id: "background",
        type: "background",
        paint: {
          "background-color": background,
        },
      },
      {
        id: "base-raster",
        type: "raster",
        source: "base-raster",
        paint: {
          "raster-opacity": config.opacity ?? 1,
        },
      },
    ],
  };
}

function rasterSourceConfig(config = {}) {
  return {
    type: "raster",
    tiles: urlTemplateFromConfig(config),
    tileSize: config.tileSize || 256,
    attribution: config.attribution || "",
  };
}

export class MyMap extends EventListener {
  name = "map";
  _pickLayerColorNum = 0xffffff;

  constructor({
    rootId,
    center = [12614426, 2646623],
    zoom = 15,
    pitch = 90,
    rotation = 0,
    openGPUPick = true,
    noControls = false,
    enableRotate = false,
    enablePan = true,
    enableZoom = true,
    ...opt
  }) {
    super(opt);
    this.rootDoc = document.getElementById(rootId);
    if (!this.rootDoc) {
      throw new Error("无法获取地图根节点:" + rootId);
    }
    this.rootDoc.style.position = "relative";
    this.rootDoc.style.overflow = "hidden";
    this.rootDoc.classList.add("maplibre-root");

    this.layers = [];
    this.buildingLayerId = null;
    this.center = center;
    this.zoom = zoom;
    this.pitch = pitch;
    this.rotation = rotation;
    this.openGPUPick = openGPUPick;
    this._enableRotate = enableRotate;
    this._enablePan = enablePan;
    this._enableZoom = enableZoom;
    this._ready = false;
    this._readyCallbacks = [];
    this.customDrag = null;
    this.keysDown = new Set();
    this.keyboardFrame = null;
    this.keyboardLastAt = 0;
    this.customInteractionHandlers = null;

    this.map = new maplibregl.Map({
      container: this.rootDoc,
      style: createMapStyle(),
      center: webMercatorToLngLat(center[0], center[1]),
      zoom,
      pitch: legacyPitchToMap(pitch),
      bearing: rotation,
      attributionControl: false,
      interactive: !noControls,
      antialias: true,
      canvasContextAttributes: {
        antialias: true,
        powerPreference: "high-performance",
        preserveDrawingBuffer: false,
        failIfMajorPerformanceCaveat: false,
        desynchronized: true,
      },
      pixelRatio: runtimeMapPixelRatio(),
      preserveDrawingBuffer: false,
      minZoom: MAP_ZOOM_RANGE.MIN,
      maxZoom: MAP_ZOOM_RANGE.MAX,
      maxPitch: MAP_MAX_PITCH,
    });

    this.applyInteractionFlags();
    this.bindMapEvents();
    this.bindCustomInteractionEvents();

    if (import.meta.env.DEV) {
      window.__mymap = this;
    }
  }

  get cameraHeight() {
    return this.constructor.zoomToHeight(this.zoom);
  }

  get plottingScale() {
    return this.cameraHeight / 500;
  }

  set enableRotate(enableRotate) {
    this._enableRotate = !!enableRotate;
    this.applyInteractionFlags();
  }

  get enableRotate() {
    return this._enableRotate;
  }

  set enablePan(enablePan) {
    this._enablePan = !!enablePan;
    this.applyInteractionFlags();
  }

  get enablePan() {
    return this._enablePan;
  }

  set enableZoom(enableZoom) {
    this._enableZoom = !!enableZoom;
    this.applyInteractionFlags();
  }

  get enableZoom() {
    return this._enableZoom;
  }

  getPickLayerColor() {
    return new THREE.Color(--this._pickLayerColorNum);
  }

  whenReady(callback) {
    if (this._ready && this.map?.getStyle()) {
      callback(this);
      return;
    }
    this._readyCallbacks.push(callback);
  }

  setBaseMapStyle(config = {}) {
    this.whenReady(() => {
      const style = this.map?.getStyle?.();
      if (!style) return;
      const sourceId = "base-raster";
      const layerId = "base-raster";
      if (this.map.getLayer(layerId)) {
        this.map.removeLayer(layerId);
      }
      if (this.map.getSource(sourceId)) {
        this.map.removeSource(sourceId);
      }
      this.map.addSource(sourceId, rasterSourceConfig(config));
      const beforeId = (this.map.getStyle()?.layers || [])
        .find((layer) => layer.id !== "background" && layer.id !== layerId)?.id;
      this.map.addLayer({
        id: layerId,
        type: "raster",
        source: sourceId,
        paint: {
          "raster-opacity": config.opacity ?? 1,
        },
      }, beforeId);
      if (this.map.getLayer("background")) {
        this.map.setPaintProperty("background", "background-color", numberColorToHex(config.background, "#f5f5f5"));
      }
    });
  }

  bindMapEvents() {
    this.map.once("load", () => {
      this._ready = true;
      this._readyCallbacks.splice(0).forEach((callback) => callback(this));
      this.emit(MAP_EVENT.UPDATE_RENDERER_SIZE, this.size());
    });

    this.map.on("move", () => {
      this.syncStateFromMap();
    });

    this.map.on("resize", () => {
      this.emit(MAP_EVENT.UPDATE_RENDERER_SIZE, this.size());
    });

    this.map.on("mousemove", (event) => {
      this.emit(MAP_EVENT.HANDLE_MOUSE_MOVE, this.eventPayload(event));
    });

    this.map.on("click", (event) => {
      const payload = this.eventPayload(event);
      this.emit(MAP_EVENT.HANDLE_CLICK_LEFT, payload);
      this.emit(MAP_EVENT.HANDLE_PICK_LEFT, payload);
    });

    this.map.on("contextmenu", (event) => {
      const payload = this.eventPayload(event);
      this.emit(MAP_EVENT.HANDLE_CLICK_RIGHT, payload);
      this.emit(MAP_EVENT.HANDLE_PICK_RIGHT, payload);
    });
  }

  bindCustomInteractionEvents() {
    const canvas = this.map?.getCanvas?.();
    if (!canvas || this.customInteractionHandlers) return;
    if (!this.rootDoc.hasAttribute("tabindex")) {
      this.rootDoc.setAttribute("tabindex", "0");
    }
    const handlers = {
      mousedown: (event) => this.handleCustomMouseDown(event),
      mousemove: (event) => this.handleCustomMouseMove(event),
      mouseup: (event) => this.handleCustomMouseUp(event),
      contextmenu: (event) => {
        if (this._enablePan || this._enableRotate) {
          event.preventDefault();
        }
      },
      keydown: (event) => this.handleCustomKeyDown(event),
      keyup: (event) => this.handleCustomKeyUp(event),
      blur: () => this.clearKeyboardPan(),
    };
    canvas.addEventListener("mousedown", handlers.mousedown);
    window.addEventListener("mousemove", handlers.mousemove);
    window.addEventListener("mouseup", handlers.mouseup);
    this.rootDoc.addEventListener("contextmenu", handlers.contextmenu);
    this.rootDoc.addEventListener("keydown", handlers.keydown);
    this.rootDoc.addEventListener("keyup", handlers.keyup);
    window.addEventListener("blur", handlers.blur);
    this.customInteractionHandlers = handlers;
  }

  unbindCustomInteractionEvents() {
    const canvas = this.map?.getCanvas?.();
    const handlers = this.customInteractionHandlers;
    if (!handlers) return;
    canvas?.removeEventListener("mousedown", handlers.mousedown);
    window.removeEventListener("mousemove", handlers.mousemove);
    window.removeEventListener("mouseup", handlers.mouseup);
    this.rootDoc?.removeEventListener("contextmenu", handlers.contextmenu);
    this.rootDoc?.removeEventListener("keydown", handlers.keydown);
    this.rootDoc?.removeEventListener("keyup", handlers.keyup);
    window.removeEventListener("blur", handlers.blur);
    this.customInteractionHandlers = null;
  }

  handleCustomMouseDown(event) {
    this.rootDoc?.focus?.({ preventScroll: true });
    const isMiddleRotate = event.button === 1 && this._enableRotate;
    const isRightPan = event.button === 2 && this._enablePan;
    if (!isMiddleRotate && !isRightPan) return;
    event.preventDefault();
    event.stopPropagation();
    this.customDrag = {
      mode: isMiddleRotate ? "rotate" : "pan",
      lastX: event.clientX,
      lastY: event.clientY,
      moved: false,
    };
  }

  handleCustomMouseMove(event) {
    if (!this.customDrag) return;
    const dx = event.clientX - this.customDrag.lastX;
    const dy = event.clientY - this.customDrag.lastY;
    if (Math.abs(dx) + Math.abs(dy) > 0) {
      this.customDrag.moved = true;
    }
    this.customDrag.lastX = event.clientX;
    this.customDrag.lastY = event.clientY;
    event.preventDefault();
    event.stopPropagation();

    if (this.customDrag.mode === "pan") {
      if (this._enablePan) {
        this.map.panBy([-dx, -dy], { duration: 0 });
      }
      return;
    }

    if (!this._enableRotate) {
      this.customDrag = null;
      return;
    }

    const nextRotation = normalizeBearing(this.rotation + dx * MIDDLE_DRAG_ROTATE_SPEED);
    const nextPitch = this.pitch + dy * MIDDLE_DRAG_PITCH_SPEED;
    this.setPitchAndRotation(nextPitch, nextRotation);
  }

  handleCustomMouseUp(event) {
    if (!this.customDrag) return;
    if (event.button === 1 || event.button === 2) {
      event.preventDefault();
      event.stopPropagation();
      this.customDrag = null;
    }
  }

  handleCustomKeyDown(event) {
    if (!this._enableRotate || !this._enablePan || this.isEditableTarget(event.target)) return;
    const key = String(event.key || "").toLowerCase();
    if (!["w", "a", "s", "d"].includes(key)) return;
    event.preventDefault();
    this.rootDoc?.focus?.({ preventScroll: true });
    this.keysDown.add(key);
    this.startKeyboardPan(event.shiftKey ? 2 : 1);
  }

  handleCustomKeyUp(event) {
    const key = String(event.key || "").toLowerCase();
    if (!["w", "a", "s", "d"].includes(key)) return;
    this.keysDown.delete(key);
    if (!this.keysDown.size) {
      this.clearKeyboardPan();
    }
  }

  startKeyboardPan(multiplier = 1) {
    this.keyboardMultiplier = multiplier;
    if (this.keyboardFrame) return;
    this.keyboardLastAt = performance.now();
    const tick = (now) => {
      if (!this.keysDown.size || !this._enableRotate || !this._enablePan) {
        this.clearKeyboardPan();
        return;
      }
      const dt = Math.min(0.05, Math.max(0, (now - this.keyboardLastAt) / 1000));
      this.keyboardLastAt = now;
      let dx = 0;
      let dy = 0;
      if (this.keysDown.has("a")) dx -= 1;
      if (this.keysDown.has("d")) dx += 1;
      if (this.keysDown.has("w")) dy -= 1;
      if (this.keysDown.has("s")) dy += 1;
      if (dx || dy) {
        const length = Math.hypot(dx, dy) || 1;
        const distance = WASD_PAN_PIXELS_PER_SECOND * (this.keyboardMultiplier || 1) * dt;
        this.map.panBy([dx / length * distance, dy / length * distance], { duration: 0 });
      }
      this.keyboardFrame = requestAnimationFrame(tick);
    };
    this.keyboardFrame = requestAnimationFrame(tick);
  }

  clearKeyboardPan() {
    this.keysDown.clear();
    if (this.keyboardFrame) {
      cancelAnimationFrame(this.keyboardFrame);
      this.keyboardFrame = null;
    }
  }

  isEditableTarget(target) {
    const element = target instanceof Element ? target : null;
    if (!element) return false;
    const tagName = element.tagName?.toLowerCase();
    return element.isContentEditable || ["input", "textarea", "select"].includes(tagName);
  }

  applyInteractionFlags() {
    if (!this.map) return;
    this.map.dragRotate.disable();
    this.map.keyboard?.disable?.();
    if (this.map.touchZoomRotate) {
      this._enableRotate ? this.map.touchZoomRotate.enableRotation() : this.map.touchZoomRotate.disableRotation();
    }
    this.map.dragPan.disable();
    if (this._enableZoom) {
      this.map.scrollZoom.enable();
      this.map.doubleClickZoom.enable();
    } else {
      this.map.scrollZoom.disable();
      this.map.doubleClickZoom.disable();
    }
  }

  syncStateFromMap() {
    const oldCenter = this.center;
    const oldZoom = this.zoom;
    const oldPitch = this.pitch;
    const oldRotation = this.rotation;
    const center = this.map.getCenter();
    this.center = lngLatToWebMercator(center.lng, center.lat);
    this.zoom = this.map.getZoom();
    this.pitch = mapPitchToLegacy(this.map.getPitch());
    this.rotation = this.map.getBearing();

    if (!oldCenter || Math.hypot(oldCenter[0] - this.center[0], oldCenter[1] - this.center[1]) > 0.01) {
      this.emit(MAP_EVENT.UPDATE_CENTER, this.center);
    }
    if (Math.abs(oldZoom - this.zoom) > 0.0001) {
      this.emit(MAP_EVENT.UPDATE_ZOOM, this.zoom);
      this.emit(MAP_EVENT.UPDATE_CAMERA_HEIGHT, this.cameraHeight);
    }
    if (Math.abs(oldPitch - this.pitch) > 0.01 || Math.abs(oldRotation - this.rotation) > 0.01) {
      this.emit(MAP_EVENT.UPDATE_CAMERA_ROTATE, {
        oldPitch,
        newPitch: this.pitch,
        oldRotation,
        newRotation: this.rotation,
      });
    }
    this.emit(MAP_EVENT.UPDATE_CAMERA_POSITION, {
      position: [this.center[0], this.center[1], this.cameraHeight],
      webMercator: this.center,
    });
  }

  eventPayload(event) {
    const webMercatorXY = lngLatToWebMercator(event.lngLat.lng, event.lngLat.lat);
    return {
      event: event.originalEvent || event,
      windowSize: [this.rootDoc.clientWidth, this.rootDoc.clientHeight],
      windowXY: [event.point.x, event.point.y],
      canvasXY: this.WebMercatorToCanvasXY(webMercatorXY[0], webMercatorXY[1]),
      webMercatorXY,
      lngLat: [event.lngLat.lng, event.lngLat.lat],
      point: [event.point.x, event.point.y],
    };
  }

  size() {
    return {
      width: this.rootDoc.clientWidth,
      height: this.rootDoc.clientHeight,
    };
  }

  addLayer(layer) {
    if (!layer || layer.isDisposed) return;
    const index = this.layers.findIndex((item) => item.id === layer.id);
    if (index === -1) {
      layer.onAdd(this);
      this.layers.push(layer);
      this.layers.sort((a, b) => (a.zIndex || 0) - (b.zIndex || 0));
    }
  }

  removeLayer(layer) {
    const index = this.layers.findIndex((item) => item.id === layer?.id);
    if (index > -1) {
      this.layers.splice(index, 1);
      layer.onRemove?.();
    }
  }

  emit(type, data) {
    this.handleEventListener(type, data);
    for (const layer of [...this.layers]) {
      if (layer.isDisposed) {
        this.removeLayer(layer);
      } else {
        layer.on?.(type, data);
      }
    }
  }

  on(type, data) {
    this.emit(type, data);
  }

  setZoom(zoom) {
    const nextZoom = Math.max(MAP_ZOOM_RANGE.MIN, Math.min(MAP_ZOOM_RANGE.MAX, Number(zoom) || this.zoom));
    this.zoom = nextZoom;
    this.map?.jumpTo({ zoom: nextZoom });
  }

  setCenter(center) {
    if (!center || center.length < 2) return;
    this.center = [Number(center[0]), Number(center[1])];
    this.map?.jumpTo({ center: webMercatorToLngLat(this.center[0], this.center[1]) });
  }

  // 一次 jumpTo 同时更新中心与缩放：分开调用 setCenter+setZoom 会触发两次相机变更与重绘
  setCenterAndZoom(center, zoom) {
    if (!center || center.length < 2) return;
    this.center = [Number(center[0]), Number(center[1])];
    const nextZoom = Math.max(MAP_ZOOM_RANGE.MIN, Math.min(MAP_ZOOM_RANGE.MAX, Number(zoom) || this.zoom));
    this.zoom = nextZoom;
    this.map?.jumpTo({
      center: webMercatorToLngLat(this.center[0], this.center[1]),
      zoom: nextZoom,
    });
  }

  setPitchAndRotation(pitch = this.pitch, rotation = this.rotation) {
    const oldPitch = this.pitch;
    const oldRotation = this.rotation;
    this.pitch = Math.max(LEGACY_MIN_PITCH, Math.min(90, Number(pitch) || 90));
    this.rotation = normalizeBearing(Number(rotation) || 0);
    this.map?.jumpTo({
      pitch: legacyPitchToMap(this.pitch),
      bearing: this.rotation,
    });
    this.emit(MAP_EVENT.UPDATE_CAMERA_ROTATE, {
      oldPitch,
      newPitch: this.pitch,
      oldRotation,
      newRotation: this.rotation,
    });
  }

  getFitZoomAndCenter(list) {
    if (!list?.length) {
      return {
        height: this.cameraHeight,
        center: [...this.center],
        zoom: this.zoom,
      };
    }
    let minX = Infinity;
    let minY = Infinity;
    let maxX = -Infinity;
    let maxY = -Infinity;
    for (const point of list) {
      minX = Math.min(minX, point[0]);
      minY = Math.min(minY, point[1]);
      maxX = Math.max(maxX, point[0]);
      maxY = Math.max(maxY, point[1]);
    }
    const bounds = [
      webMercatorToLngLat(minX, minY),
      webMercatorToLngLat(maxX, maxY),
    ];
    const camera = this.map.cameraForBounds(bounds, { padding: 120 }) || {};
    return {
      height: this.constructor.zoomToHeight(camera.zoom || this.zoom),
      center: [(minX + maxX) / 2, (minY + maxY) / 2],
      zoom: camera.zoom || this.zoom,
    };
  }

  setFitZoomAndCenterByPoints(list) {
    const result = this.getFitZoomAndCenter(list);
    this.setCenter(result.center);
    this.setZoom(result.zoom);
    return result;
  }

  getWindowRangeAndWebMercator() {
    const bounds = this.map.getBounds();
    const min = lngLatToWebMercator(bounds.getWest(), bounds.getSouth());
    const max = lngLatToWebMercator(bounds.getEast(), bounds.getNorth());
    return {
      topLeft: [min[0], max[1]],
      bottomLeft: [min[0], min[1]],
      bottomRight: [max[0], min[1]],
      topRight: [max[0], max[1]],
      minX: Math.min(min[0], max[0]),
      minY: Math.min(min[1], max[1]),
      maxX: Math.max(min[0], max[0]),
      maxY: Math.max(min[1], max[1]),
      width: Math.abs(max[0] - min[0]),
      height: Math.abs(max[1] - min[1]),
    };
  }

  CanvasXYToWebMercator(x, y, cx = this.center[0], cy = this.center[1]) {
    return [Number(x) + cx, Number(y) + cy];
  }

  WebMercatorToCanvasXY(x, y, cx = this.center[0], cy = this.center[1]) {
    return [Number(x) - cx, Number(y) - cy];
  }

  WindowXYToWebMercator(x, y) {
    const lngLat = this.map.unproject([x, y]);
    return lngLatToWebMercator(lngLat.lng, lngLat.lat);
  }

  WindowXYToCanvasXY(x, y) {
    const point = this.WindowXYToWebMercator(x, y);
    return this.WebMercatorToCanvasXY(point[0], point[1]);
  }

  dispose() {
    this.isDisposed = true;
    this.clearKeyboardPan();
    this.unbindCustomInteractionEvents();
    for (const layer of [...this.layers]) {
      layer.dispose?.();
    }
    this.layers = [];
    this.map?.remove();
  }

  static zoomToHeight(zoom) {
    return 400 * Math.pow(2, 18 - zoom);
  }

  static heightToZoom(height) {
    return 18 - Math.log((Number(height) || 1) / 400) / Math.log(2);
  }
}
