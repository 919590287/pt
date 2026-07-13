import { describe, expect, it, vi } from "vitest";
import { MAP_EVENT, MyMap } from "./MyMap.js";

function mapHarness() {
  const state = {
    center: { lng: 113.3, lat: 23.1 },
    zoom: 10,
    pitch: 0,
    bearing: 0,
  };
  const map = {
    getCenter: () => state.center,
    getZoom: () => state.zoom,
    getPitch: () => state.pitch,
    getBearing: () => state.bearing,
    jumpTo: vi.fn((options) => {
      if (options.center) state.center = { lng: options.center[0], lat: options.center[1] };
      if (options.zoom != null) state.zoom = options.zoom;
    }),
  };
  const wrapper = Object.create(MyMap.prototype);
  wrapper.map = map;
  wrapper.center = [0, 0];
  wrapper.zoom = 10;
  wrapper.pitch = 90;
  wrapper.rotation = 0;
  wrapper.emit = vi.fn();
  return { map, wrapper };
}

describe("MyMap programmatic camera updates", () => {
  it("emits zoom changes after toolbar-style programmatic zoom", () => {
    const { wrapper } = mapHarness();

    wrapper.setZoom(12);

    expect(wrapper.zoom).toBe(12);
    expect(wrapper.emit).toHaveBeenCalledWith(MAP_EVENT.UPDATE_ZOOM, 12);
    expect(wrapper.emit).toHaveBeenCalledWith(MAP_EVENT.UPDATE_CAMERA_HEIGHT, wrapper.cameraHeight);
  });

  it("fits center and zoom with one camera mutation", () => {
    const { map, wrapper } = mapHarness();
    wrapper.getFitZoomAndCenter = vi.fn(() => ({ center: [1000, 2000], zoom: 14, height: 1 }));

    wrapper.setFitZoomAndCenterByPoints([[0, 0], [1, 1]]);

    expect(map.jumpTo).toHaveBeenCalledTimes(1);
    expect(map.jumpTo).toHaveBeenCalledWith(expect.objectContaining({ zoom: 14 }));
    expect(wrapper.zoom).toBe(14);
  });

  it("does not rebroadcast camera position for a no-op sync", () => {
    const { wrapper } = mapHarness();
    wrapper.syncStateFromMap();
    wrapper.emit.mockClear();

    wrapper.syncStateFromMap();

    expect(wrapper.emit).not.toHaveBeenCalled();
  });

  it("announces 2D/3D mode changes when rotation interaction changes", () => {
    const { wrapper } = mapHarness();
    wrapper._enableRotate = false;
    wrapper.applyInteractionFlags = vi.fn();

    wrapper.enableRotate = true;

    expect(wrapper.applyInteractionFlags).toHaveBeenCalledTimes(1);
    expect(wrapper.emit).toHaveBeenCalledWith(MAP_EVENT.UPDATE_VIEW_MODE, { is3D: true });

    wrapper.emit.mockClear();
    wrapper.enableRotate = true;
    expect(wrapper.emit).not.toHaveBeenCalled();
  });
});
