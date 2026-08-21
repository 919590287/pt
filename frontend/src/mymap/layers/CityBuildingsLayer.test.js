import { afterEach, describe, expect, it, vi } from "vitest";
import { CityBuildingsLayer } from "./CityBuildingsLayer.js";

function layerWithMap({ zoom = 15, pitch = 90, enableRotate = false } = {}) {
  const layer = Object.create(CityBuildingsLayer.prototype);
  layer.minZoom = 12;
  layer.suppressed = false;
  layer.map = { zoom, pitch, enableRotate };
  return layer;
}

describe("CityBuildingsLayer visibility", () => {
  it("hides buildings in 2D even at high zoom", () => {
    expect(layerWithMap().shouldShowBuildings()).toBe(false);
  });

  it("shows buildings in 3D at the configured zoom", () => {
    expect(layerWithMap({ enableRotate: true }).shouldShowBuildings()).toBe(true);
    expect(layerWithMap({ pitch: 45 }).shouldShowBuildings()).toBe(true);
  });

  it("keeps buildings hidden below the configured zoom in 3D", () => {
    expect(layerWithMap({ zoom: 11.9, enableRotate: true }).shouldShowBuildings()).toBe(false);
  });

  it("lets a thematic 3D layer suppress buildings until it releases the scene", () => {
    const layer = layerWithMap({ enableRotate: true });
    layer.suppressed = true;
    expect(layer.shouldShowBuildings()).toBe(false);
    layer.suppressed = false;
    expect(layer.shouldShowBuildings()).toBe(true);
  });
});

function loadableLayer() {
  const layer = layerWithMap({ enableRotate: true });
  Object.assign(layer, {
    updateDelay: 60,
    baseRetryDelay: 2000,
    maxRetryDelay: 30000,
    prefetchMeters: 900,
    hasBuildingData: false,
    responseLimited: false,
    loadedBounds: null,
    loadedZoom: null,
    pendingBounds: null,
    pendingZoom: null,
    _loadTimer: null,
    _failureCount: 0,
    _retryAt: 0,
  });
  layer.resolveViewBounds = () => ({ minX: 0, minY: 0, maxX: 100, maxY: 100 });
  return layer;
}

describe("CityBuildingsLayer failure backoff", () => {
  afterEach(() => {
    vi.useRealTimers();
    delete globalThis.window;
  });

  it("collapses camera events during the backoff window into a single retry", () => {
    vi.useFakeTimers();
    globalThis.window = globalThis;
    const layer = loadableLayer();
    const calls = [];
    layer.loadBuildings = () => calls.push(Date.now());
    layer._retryAt = Date.now() + 2000;

    // 一次缩放会连续触发多个相机事件；退避未到期前它们只能重排同一个延时任务
    layer.scheduleLoad();
    layer.scheduleLoad();
    layer.scheduleLoad();

    vi.advanceTimersByTime(1999);
    expect(calls).toHaveLength(0);
    vi.advanceTimersByTime(2);
    expect(calls).toHaveLength(1);
  });

  it("keeps the normal short delay once the backoff has expired", () => {
    vi.useFakeTimers();
    globalThis.window = globalThis;
    const layer = loadableLayer();
    const calls = [];
    layer.loadBuildings = () => calls.push(Date.now());

    layer.scheduleLoad();
    vi.advanceTimersByTime(1);
    expect(calls).toHaveLength(1);
  });
});
