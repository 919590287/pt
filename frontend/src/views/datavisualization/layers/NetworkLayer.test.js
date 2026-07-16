import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";

vi.mock("@/mymap/index.js", async () => {
  const { Layer } = await import("@/mymap/main/Layer.js");
  return {
    Layer,
    MAP_EVENT: {
      UPDATE_CENTER: "update:center",
      UPDATE_ZOOM: "update:zoom",
      UPDATE_RENDERER_SIZE: "update:renderer:size",
    },
    webMercatorToLngLat: (x, y) => [Number(x), Number(y)],
  };
});

const originalRequestAnimationFrame = globalThis.requestAnimationFrame;
const originalCancelAnimationFrame = globalThis.cancelAnimationFrame;
let NetworkLayer;

beforeAll(async () => {
  ({ NetworkLayer } = await import("./NetworkLayer.js"));
});

afterEach(() => {
  globalThis.requestAnimationFrame = originalRequestAnimationFrame;
  globalThis.cancelAnimationFrame = originalCancelAnimationFrame;
});

describe("NetworkLayer deck update scheduling", () => {
  it("renders multiple network layers through one shared animation frame", () => {
    const callbacks = [];
    globalThis.requestAnimationFrame = vi.fn((callback) => {
      callbacks.push(callback);
      return callbacks.length;
    });
    globalThis.cancelAnimationFrame = vi.fn();
    const baseLayer = new NetworkLayer();
    const selectedLayer = new NetworkLayer();
    baseLayer.renderDeckLayer = vi.fn();
    selectedLayer.renderDeckLayer = vi.fn();

    baseLayer.queueDeckUpdate();
    selectedLayer.queueDeckUpdate();
    baseLayer.queueDeckUpdate();

    expect(globalThis.requestAnimationFrame).toHaveBeenCalledTimes(1);
    callbacks[0](16);
    expect(baseLayer.renderDeckLayer).toHaveBeenCalledTimes(1);
    expect(selectedLayer.renderDeckLayer).toHaveBeenCalledTimes(1);
  });
});

describe("NetworkLayer continuous path grouping with flow classes", () => {
  const link = (fromX, toX, flow) => ({
    from: { x: fromX, y: 0 },
    to: { x: toX, y: 0 },
    flow,
  });

  function makeSegmentLikeLayer(links) {
    const layer = new NetworkLayer({
      workerEnabled: false,
      flowControl: true,
      continuousPath: true,
      flowStyleStops: [
        { maxValue: 200, color: [0, 255, 0], widthStep: 0 },
        { maxValue: Infinity, color: [255, 0, 0], widthStep: 1 },
      ],
    });
    layer.rawLinks = links;
    return layer;
  }

  it("splits paths exactly where the flow class changes and keeps same-class links joined", () => {
    // 4 条首尾相接的链路：前两条低档、后两条高档 → 应得到 2 条连续路径，断点在共享节点
    const links = [link(0, 10, 100), link(10, 20, 100), link(20, 30, 500), link(30, 40, 500)];
    const layer = makeSegmentLikeLayer(links);
    const groups = layer.continuousPathGroups({ version: 1, minFlow: 100, maxFlow: 500 });

    expect(groups).toHaveLength(2);
    expect(groups[0].path).toEqual([[0, 0], [10, 0], [20, 0]]);
    expect(groups[1].path).toEqual([[20, 0], [30, 0], [40, 0]]);
    expect(groups[0].stop.color).toEqual([0, 255, 0]);
    expect(groups[1].stop.color).toEqual([255, 0, 0]);
  });

  it("keeps a uniform-class route as one smooth path", () => {
    const links = [link(0, 10, 80), link(10, 20, 90), link(20, 30, 70)];
    const layer = makeSegmentLikeLayer(links);
    const groups = layer.continuousPathGroups({ version: 1, minFlow: 70, maxFlow: 90 });

    expect(groups).toHaveLength(1);
    expect(groups[0].path).toEqual([[0, 0], [10, 0], [20, 0], [30, 0]]);
  });
});

describe("NetworkLayer beforeId anchoring", () => {
  it("uses the anchor layer only when it exists on the map, falling back to buildingLayerId", () => {
    const layer = new NetworkLayer({ beforeId: "rm-ring" });
    layer.map = {
      buildingLayerId: null,
      map: { getLayer: (id) => (id === "rm-ring" ? { id } : undefined) },
    };
    expect(layer.currentBeforeId()).toBe("rm-ring");

    layer.map = {
      buildingLayerId: "buildings",
      map: { getLayer: () => undefined },
    };
    expect(layer.currentBeforeId()).toBe("buildings");
  });
});
