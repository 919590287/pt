import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({ overlays: [] }));

vi.mock("@deck.gl/mapbox", () => ({
  MapboxOverlay: class MapboxOverlay {
    constructor(props) {
      this.props = props;
      this.setProps = vi.fn();
      mocks.overlays.push(this);
    }
  },
}));

import {
  batchSharedDeckLayerUpdates,
  removeSharedDeckLayer,
  setSharedDeckLayer,
  setSharedDeckLayersHidden,
} from "./deckOverlayRegistry.js";

function mapWrapper() {
  return {
    map: {
      addControl: vi.fn(),
      removeControl: vi.fn(),
    },
  };
}

describe("deckOverlayRegistry batching", () => {
  beforeEach(() => {
    mocks.overlays.length = 0;
  });

  it("commits several layer changes with one ordered overlay update", () => {
    const wrapper = mapWrapper();
    const high = { id: "high" };
    const low = { id: "low" };

    batchSharedDeckLayerUpdates(() => {
      setSharedDeckLayer(wrapper, "high", high, 20);
      setSharedDeckLayer(wrapper, "low", low, 10);
    });

    const overlay = mocks.overlays[0];
    expect(wrapper.map.addControl).toHaveBeenCalledTimes(1);
    expect(overlay.setProps).toHaveBeenCalledTimes(1);
    expect(overlay.setProps).toHaveBeenLastCalledWith({ layers: [low, high] });
  });

  it("does not tear down an overlay when a layer is replaced in the same batch", () => {
    const wrapper = mapWrapper();
    const first = { id: "first" };
    const replacement = { id: "replacement" };
    setSharedDeckLayer(wrapper, "first", first, 1);
    const overlay = mocks.overlays[0];
    overlay.setProps.mockClear();

    batchSharedDeckLayerUpdates(() => {
      removeSharedDeckLayer(wrapper, "first");
      setSharedDeckLayer(wrapper, "replacement", replacement, 1);
    });

    expect(wrapper.map.removeControl).not.toHaveBeenCalled();
    expect(overlay.setProps).toHaveBeenCalledTimes(1);
    expect(overlay.setProps).toHaveBeenLastCalledWith({ layers: [replacement] });
  });
});

// deck 的 interleaved 图层是 maplibre custom layer，不出现在 getStyle().layers 里，
// MapLayout 那套按样式图层前缀隐藏的逻辑碰不到它们。页面组切换靠下面这组接口挂起。
describe("deckOverlayRegistry 页面组挂起", () => {
  beforeEach(() => {
    mocks.overlays.length = 0;
  });

  it("按前缀挂起离开页面的图层，保留其他页面的图层", () => {
    const wrapper = mapWrapper();
    const odLines = { id: "rm-busod-lines" };
    const hubFlow = { id: "ta-hub-flow-deck" };
    setSharedDeckLayer(wrapper, "rm-busod-lines", odLines, 0);
    setSharedDeckLayer(wrapper, "ta-hub-flow-deck", hubFlow, 0);
    const overlay = mocks.overlays[0];
    overlay.setProps.mockClear();

    setSharedDeckLayersHidden(wrapper, ["rm-", "pfa-"], true);

    expect(overlay.setProps).toHaveBeenLastCalledWith({ layers: [hubFlow] });
  });

  it("恢复时不重建 overlay，原图层实例直接回到画面", () => {
    const wrapper = mapWrapper();
    const odLines = { id: "rm-busod-lines" };
    setSharedDeckLayer(wrapper, "rm-busod-lines", odLines, 0);
    const overlay = mocks.overlays[0];

    setSharedDeckLayersHidden(wrapper, ["rm-"], true);
    expect(overlay.setProps).toHaveBeenLastCalledWith({ layers: [] });
    // 全部挂起也不能拆 overlay，否则每次切页面都要重建 WebGL 资源
    expect(wrapper.map.removeControl).not.toHaveBeenCalled();

    setSharedDeckLayersHidden(wrapper, ["rm-"], false);

    expect(mocks.overlays).toHaveLength(1);
    expect(overlay.setProps).toHaveBeenLastCalledWith({ layers: [odLines] });
  });

  it("挂起期间到达的数据更新不会漏画，恢复后带上最新图层", () => {
    const wrapper = mapWrapper();
    setSharedDeckLayer(wrapper, "rm-busod-lines", { id: "stale" }, 0);
    const overlay = mocks.overlays[0];
    setSharedDeckLayersHidden(wrapper, ["rm-"], true);

    const fresh = { id: "fresh" };
    setSharedDeckLayer(wrapper, "rm-busod-lines", fresh, 0);
    expect(overlay.setProps).toHaveBeenLastCalledWith({ layers: [] });

    setSharedDeckLayersHidden(wrapper, ["rm-"], false);
    expect(overlay.setProps).toHaveBeenLastCalledWith({ layers: [fresh] });
  });
});
