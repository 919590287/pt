import maplibregl from "maplibre-gl";
import * as THREE from "three";
import { EventListener } from "./EventListener";
import {
  createMapDisplayHost,
  getMapDisplayScale,
  mapCanvasPixelRatio,
  onMapDisplayScaleChange,
} from "@/utils/mapDisplayScale.js";

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
  UPDATE_VIEW_MODE: "update:view:mode",
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
  return ["https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Light_Gray_Base/MapServer/tile/{z}/{y}/{x}"];
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
  const tiles = urlTemplateFromConfig(config);
  const source = {
    type: "raster",
    tiles,
    tileSize: config.tileSize || 256,
    attribution: config.attribution || "",
  };
  if (Number.isFinite(config.min_zoom)) source.minzoom = config.min_zoom;
  if (Number.isFinite(config.max_zoom)) source.maxzoom = config.max_zoom;
  return source;
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

    // 分辨率等比适配：地图挂在一层 CSS zoom 宿主上（布局尺寸 = 容器/S，zoom = S），
    // 720p~4K 下底图与点线面要素随视口等比缩放；pixelRatio 反向乘 S 使画布后备缓冲
    // 恒等于屏幕物理像素，渲染精度与性能都与不缩放时一致。
    this.displayScale = getMapDisplayScale();
    this._displayHost = createMapDisplayHost(this.rootDoc);
    this._offDisplayScale = onMapDisplayScaleChange((scale) => {
      this.displayScale = scale;
      this.map?.setPixelRatio?.(mapCanvasPixelRatio(scale));
      this.scheduleResize();
    });
    this._resizeFrame = 0;
    this._resizeTimer = 0;
    this._resizeObserver = null;

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
      container: this._displayHost.host,
      style: createMapStyle(),
      center: webMercatorToLngLat(center[0], center[1]),
      zoom,
      pitch: legacyPitchToMap(pitch),
      bearing: rotation,
      // 在线底图必须展示数据来源；紧凑模式不遮挡交通分析画布。
      attributionControl: { compact: true },
      interactive: !noControls,
      antialias: true,
      canvasContextAttributes: {
        antialias: true,
        powerPreference: "high-performance",
        preserveDrawingBuffer: false,
        failIfMajorPerformanceCaveat: false,
        desynchronized: true,
      },
      pixelRatio: mapCanvasPixelRatio(this.displayScale),
      preserveDrawingBuffer: false,
      minZoom: MAP_ZOOM_RANGE.MIN,
      maxZoom: MAP_ZOOM_RANGE.MAX,
      maxPitch: MAP_MAX_PITCH,
    });

    // 紧凑归属控件在首批带 attribution 的源加载后会自动展开；这里做一次性收起，
    // 右下角默认只留 ⓘ 按钮，点击才展开（与 maplibre 拖图后的收起态一致，只摘 show 类）。
    const collapseAttribution = () => {
      const attribCtrl = this._displayHost.host.querySelector(".maplibregl-ctrl-attrib.maplibregl-compact");
      if (!attribCtrl) return;
      attribCtrl.classList.remove("maplibregl-compact-show");
      this.map.off("styledata", collapseAttribution);
      this.map.off("sourcedata", collapseAttribution);
    };
    this.map.on("styledata", collapseAttribution);
    this.map.on("sourcedata", collapseAttribution);

    this.applyInteractionFlags();
    this.bindMapEvents();
    this.bindCustomInteractionEvents();
    this.installResizeObserver();
    // MapLibre measures the container during construction. The parent layout
    // and CSS zoom are finalized one or two frames later (especially after a
    // hard refresh), so always perform a post-layout measurement.
    this.scheduleResize();
    this._resizeTimer = setTimeout(() => {
      this._resizeTimer = 0;
      this.scheduleResize();
    }, 250);

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
    const next = !!enableRotate;
    if (next === this._enableRotate) return;
    this._enableRotate = next;
    this.applyInteractionFlags();
    this.emit(MAP_EVENT.UPDATE_VIEW_MODE, { is3D: next });
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

  /**
   * style 是否已经加载完成。maplibre 的 Map 对象是同步构造出来的，但在 "load" 事件之前
   * addSource / addLayer 会抛 "Style is not done loading"。调用方拿到的是裸 map
   * （MapRef.value.map），没法从它判断，所以这里给出与 whenReady 同口径的公开判断。
   */
  get styleReady() {
    return !!(this._ready && this.map?.getStyle());
  }

  whenReady(callback) {
    if (this.styleReady) {
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
      this.scheduleResize();
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

  installResizeObserver() {
    if (typeof ResizeObserver !== "function") return;
    const targets = [this.rootDoc, this._displayHost?.host].filter(Boolean);
    this._resizeObserver = new ResizeObserver(() => this.scheduleResize());
    targets.forEach((target) => this._resizeObserver.observe(target));
  }

  scheduleResize() {
    if (this.isDisposed || !this.map?.resize) return;
    if (this._resizeFrame) return;
    const resize = () => {
      this._resizeFrame = 0;
      if (this.isDisposed || !this.map?.resize) return;
      try {
        this.map.resize();
      } catch (error) {
        // A resize can race MapLibre teardown during route changes. It must
        // never break model bootstrap or leave the global gate stuck.
        if (!this.isDisposed) console.debug("[MyMap] deferred resize skipped", error);
      }
    };
    if (typeof requestAnimationFrame === "function") {
      this._resizeFrame = requestAnimationFrame(resize);
    } else {
      this._resizeFrame = setTimeout(resize, 0);
    }
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
    // 左键平移交给 maplibre 内置 dragPan（自带惯性与拖动后点击抑制），这里只接管中键旋转
    const isMiddleRotate = event.button === 1 && this._enableRotate;
    if (!isMiddleRotate) return;
    event.preventDefault();
    event.stopPropagation();
    this.customDrag = {
      mode: "rotate",
      lastX: event.clientX,
      lastY: event.clientY,
      moved: false,
    };
  }

  handleCustomMouseMove(event) {
    if (!this.customDrag) return;
    // clientX/Y 是视觉像素；地图坐标空间是宿主布局像素（视觉/displayScale），
    // 不换算会导致高分屏下中键拖动的旋转/俯仰速度偏快/偏慢
    const scale = this.displayScale || 1;
    const dx = (event.clientX - this.customDrag.lastX) / scale;
    const dy = (event.clientY - this.customDrag.lastY) / scale;
    if (Math.abs(dx) + Math.abs(dy) > 0) {
      this.customDrag.moved = true;
    }
    this.customDrag.lastX = event.clientX;
    this.customDrag.lastY = event.clientY;
    event.preventDefault();
    event.stopPropagation();

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
    if (event.button === 1) {
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
    // 左键拖动平移：直接用 maplibre 内置 dragPan（仅响应左键）
    this._enablePan ? this.map.dragPan.enable() : this.map.dragPan.disable();
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

    const centerChanged = !oldCenter
      || Math.hypot(oldCenter[0] - this.center[0], oldCenter[1] - this.center[1]) > 0.01;
    const zoomChanged = Math.abs(oldZoom - this.zoom) > 0.0001;
    const rotationChanged = Math.abs(oldPitch - this.pitch) > 0.01
      || Math.abs(oldRotation - this.rotation) > 0.01;

    if (centerChanged) {
      this.emit(MAP_EVENT.UPDATE_CENTER, this.center);
    }
    if (zoomChanged) {
      this.emit(MAP_EVENT.UPDATE_ZOOM, this.zoom);
      this.emit(MAP_EVENT.UPDATE_CAMERA_HEIGHT, this.cameraHeight);
    }
    if (rotationChanged) {
      this.emit(MAP_EVENT.UPDATE_CAMERA_ROTATE, {
        oldPitch,
        newPitch: this.pitch,
        oldRotation,
        newRotation: this.rotation,
      });
    }
    // MapLibre 可能对一次 jumpTo 同步派发多次 move。没有可观察状态变化时不再
    // 扫描全部自定义图层，避免缩放热路径中的空事件风暴。
    if (centerChanged || zoomChanged || rotationChanged) {
      this.emit(MAP_EVENT.UPDATE_CAMERA_POSITION, {
        position: [this.center[0], this.center[1], this.cameraHeight],
        webMercator: this.center,
      });
    }
  }

  eventPayload(event) {
    const webMercatorXY = lngLatToWebMercator(event.lngLat.lng, event.lngLat.lat);
    const size = this.size();
    return {
      event: event.originalEvent || event,
      windowSize: [size.width, size.height],
      windowXY: [event.point.x, event.point.y],
      canvasXY: this.WebMercatorToCanvasXY(webMercatorXY[0], webMercatorXY[1]),
      webMercatorXY,
      lngLat: [event.lngLat.lng, event.lngLat.lat],
      point: [event.point.x, event.point.y],
    };
  }

  size() {
    // 地图坐标空间 = zoom 宿主的布局尺寸（与 maplibre event.point / project 同空间）
    const host = this._displayHost?.host;
    return {
      width: host?.clientWidth || this.rootDoc.clientWidth,
      height: host?.clientHeight || this.rootDoc.clientHeight,
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
    if (!this.map) {
      this.zoom = nextZoom;
      return;
    }
    // 不提前写 this.zoom。否则 jumpTo 的 move 回调会把新值当旧值，吞掉
    // UPDATE_ZOOM，建筑/路网等自定义图层无法响应工具栏缩放。
    this.map.jumpTo({ zoom: nextZoom });
    this.syncStateFromMap();
  }

  setCenter(center) {
    if (!center || center.length < 2) return;
    const nextCenter = [Number(center[0]), Number(center[1])];
    if (!nextCenter.every(Number.isFinite)) return;
    if (!this.map) {
      this.center = nextCenter;
      return;
    }
    this.map.jumpTo({ center: webMercatorToLngLat(nextCenter[0], nextCenter[1]) });
    this.syncStateFromMap();
  }

  // 一次 jumpTo 同时更新中心与缩放：分开调用 setCenter+setZoom 会触发两次相机变更与重绘
  setCenterAndZoom(center, zoom) {
    if (!center || center.length < 2) return;
    const nextCenter = [Number(center[0]), Number(center[1])];
    if (!nextCenter.every(Number.isFinite)) return;
    const nextZoom = Math.max(MAP_ZOOM_RANGE.MIN, Math.min(MAP_ZOOM_RANGE.MAX, Number(zoom) || this.zoom));
    if (!this.map) {
      this.center = nextCenter;
      this.zoom = nextZoom;
      return;
    }
    this.map.jumpTo({
      center: webMercatorToLngLat(nextCenter[0], nextCenter[1]),
      zoom: nextZoom,
    });
    this.syncStateFromMap();
  }

  setPitchAndRotation(pitch = this.pitch, rotation = this.rotation) {
    const oldPitch = this.pitch;
    const oldRotation = this.rotation;
    const nextPitch = Math.max(LEGACY_MIN_PITCH, Math.min(90, Number(pitch) || 90));
    const nextRotation = normalizeBearing(Number(rotation) || 0);
    if (!this.map) {
      this.pitch = nextPitch;
      this.rotation = nextRotation;
      if (Math.abs(oldPitch - nextPitch) > 0.01 || Math.abs(oldRotation - nextRotation) > 0.01) {
        this.emit(MAP_EVENT.UPDATE_CAMERA_ROTATE, {
          oldPitch,
          newPitch: nextPitch,
          oldRotation,
          newRotation: nextRotation,
        });
      }
      return;
    }
    this.map.jumpTo({
      pitch: legacyPitchToMap(nextPitch),
      bearing: nextRotation,
    });
    this.syncStateFromMap();
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
    this.setCenterAndZoom(result.center, result.zoom);
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

  // 俯仰视角下 getBounds 会一直延伸到地平线附近，范围可达数百公里，直接拿去做数据
  // 请求/裁剪会拖垮请求方。这里以屏幕底边中点（俯仰下离相机最近的地面点）为锚，把
  // 范围裁到 maxDistance 半径内；anchor 一并返回，供调用方作为"就近优先"的焦点。
  getViewGroundRangeWebMercator(maxDistance = Infinity) {
    const range = this.getWindowRangeAndWebMercator();
    const canvas = this.map.getCanvas();
    const width = canvas.clientWidth || canvas.width || 1;
    const height = canvas.clientHeight || canvas.height || 1;
    const nearPoint = this.WindowXYToWebMercator(width / 2, height);
    if (!Number.isFinite(nearPoint[0]) || !Number.isFinite(nearPoint[1])) {
      return { ...range, anchor: [(range.minX + range.maxX) / 2, (range.minY + range.maxY) / 2] };
    }
    const anchorX = Math.max(range.minX, Math.min(range.maxX, nearPoint[0]));
    const anchorY = Math.max(range.minY, Math.min(range.maxY, nearPoint[1]));
    if (!Number.isFinite(maxDistance) || maxDistance <= 0) {
      return { ...range, anchor: [anchorX, anchorY] };
    }
    const minX = Math.max(range.minX, anchorX - maxDistance);
    const maxX = Math.min(range.maxX, anchorX + maxDistance);
    const minY = Math.max(range.minY, anchorY - maxDistance);
    const maxY = Math.min(range.maxY, anchorY + maxDistance);
    return {
      topLeft: [minX, maxY],
      bottomLeft: [minX, minY],
      bottomRight: [maxX, minY],
      topRight: [maxX, maxY],
      minX,
      minY,
      maxX,
      maxY,
      width: maxX - minX,
      height: maxY - minY,
      anchor: [anchorX, anchorY],
    };
  }

  CanvasXYToWebMercator(x, y, cx = this.center[0], cy = this.center[1]) {
    return [Number(x) + cx, Number(y) + cy];
  }

  WebMercatorToCanvasXY(x, y, cx = this.center[0], cy = this.center[1]) {
    return [Number(x) - cx, Number(y) - cy];
  }

  // 地图布局坐标（event.point / map.project 所在空间）→ 视口 client 坐标。
  // 显示缩放宿主使两者差一个 displayScale 倍率，定位悬浮 DOM（菜单/气泡）时必须经此换算
  mapPointToClient(x, y) {
    const host = this._displayHost?.host || this.rootDoc;
    const rect = host.getBoundingClientRect();
    const scale = this.displayScale || 1;
    return [rect.left + Number(x) * scale, rect.top + Number(y) * scale];
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
    if (this._resizeFrame) {
      if (typeof cancelAnimationFrame === "function") cancelAnimationFrame(this._resizeFrame);
      else clearTimeout(this._resizeFrame);
      this._resizeFrame = 0;
    }
    if (this._resizeTimer) {
      clearTimeout(this._resizeTimer);
      this._resizeTimer = 0;
    }
    this._resizeObserver?.disconnect?.();
    this._resizeObserver = null;
    this.unbindCustomInteractionEvents();
    for (const layer of [...this.layers]) {
      layer.dispose?.();
    }
    this.layers = [];
    this.map?.remove();
    this._offDisplayScale?.();
    this._offDisplayScale = null;
    this._displayHost?.dispose();
    this._displayHost = null;
  }

  static zoomToHeight(zoom) {
    return 400 * Math.pow(2, 18 - zoom);
  }

  static heightToZoom(height) {
    return 18 - Math.log((Number(height) || 1) / 400) / Math.log(2);
  }
}
