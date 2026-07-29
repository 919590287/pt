import { describe, expect, it, vi } from "vitest";
import { TransferLayerManager } from "./transferLayers.js";

const METRO_LAYER = "ta-metro-network-active";
const HUB_LAYER = "ta-hubs";

function createMap() {
  const handlers = new Map();
  let renderedFeatures = [];
  const canvas = { style: { cursor: "" } };
  const map = {
    getLayer: vi.fn(() => true),
    getCanvas: vi.fn(() => canvas),
    on: vi.fn((event, layerOrHandler, handler) => {
      const layer = typeof layerOrHandler === "string" ? layerOrHandler : "map";
      handlers.set(`${event}:${layer}`, handler || layerOrHandler);
    }),
    off: vi.fn(),
    queryRenderedFeatures: vi.fn(() => renderedFeatures),
  };
  return {
    map,
    canvas,
    emit(event, layer, payload) {
      handlers.get(`${event}:${layer}`)?.(payload);
    },
    setRenderedFeatures(features) {
      renderedFeatures = features;
    },
  };
}

describe("换乘分析地图线路点选", () => {
  it("点击地铁线路时回传其换乘字典索引，并提供可点击光标", () => {
    const fixture = createMap();
    const manager = new TransferLayerManager({ map: fixture.map });
    const onClick = vi.fn();
    manager.bindMetroLineClick(onClick);

    fixture.emit("mouseenter", METRO_LAYER, {});
    expect(fixture.canvas.style.cursor).toBe("pointer");

    fixture.emit("click", METRO_LAYER, {
      point: { x: 10, y: 20 },
      features: [{ properties: { lineId: "subwaygtfs_3", metroLineIdx: 4 } }],
    });
    expect(onClick).toHaveBeenCalledWith({ lineId: "subwaygtfs_3", metroLineIdx: 4 });
  });

  it("线路与站点气泡重叠时优先点选站点", () => {
    const fixture = createMap();
    fixture.setRenderedFeatures([{ layer: { id: HUB_LAYER } }]);
    const manager = new TransferLayerManager({ map: fixture.map });
    const onClick = vi.fn();
    manager.bindMetroLineClick(onClick);

    fixture.emit("click", METRO_LAYER, {
      point: { x: 10, y: 20 },
      features: [{ properties: { metroLineIdx: 4 } }],
    });
    expect(onClick).not.toHaveBeenCalled();
  });

  it("地图空白点击仅在未命中站点或线路时触发", () => {
    const fixture = createMap();
    const manager = new TransferLayerManager({ map: fixture.map });
    const onBackgroundClick = vi.fn();
    manager.bindBackgroundClick(onBackgroundClick);

    fixture.setRenderedFeatures([{ layer: { id: METRO_LAYER } }]);
    fixture.emit("click", "map", { point: { x: 1, y: 2 } });
    expect(onBackgroundClick).not.toHaveBeenCalled();

    fixture.setRenderedFeatures([]);
    fixture.emit("click", "map", { point: { x: 3, y: 4 } });
    expect(onBackgroundClick).toHaveBeenCalledTimes(1);
  });
});
