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
