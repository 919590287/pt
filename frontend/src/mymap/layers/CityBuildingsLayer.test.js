import { describe, expect, it } from "vitest";
import { CityBuildingsLayer } from "./CityBuildingsLayer.js";

function layerWithMap({ zoom = 15, pitch = 90, enableRotate = false } = {}) {
  const layer = Object.create(CityBuildingsLayer.prototype);
  layer.minZoom = 12;
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
});
