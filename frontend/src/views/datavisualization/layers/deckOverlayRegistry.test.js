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
