import { describe, expect, it } from "vitest";
import {
  buildTripEndsHeatmapFeatureCollection,
  filterTripEndsHeatmapFeatureCollection,
} from "./tripEndsHeatmap.js";
import { mercatorToLngLat } from "./populationGrid.js";

function gridFixture() {
  return {
    count: 3,
    mercCellSize: 100,
    i: Int32Array.from([10, 11, 12]),
    j: Int32Array.from([20, 21, 22]),
    street: Uint16Array.from([0, 1, 0xffff]),
  };
}

describe("buildTripEndsHeatmapFeatureCollection", () => {
  it("取单元中心坐标，并以平方根压缩后的客流归一权重生成热力点", () => {
    const result = buildTripEndsHeatmapFeatureCollection(
      gridFixture(),
      Uint32Array.from([25, 100, 0]),
    );
    expect(result.maxFlow).toBe(100);
    expect(result.pointCount).toBe(2);
    expect(result.collection.features.map((feature) => feature.properties.weight)).toEqual([0.5, 1]);
    expect(result.collection.features.map((feature) => feature.properties.flow)).toEqual([25, 100]);

    const expected = mercatorToLngLat(10 * 100 + 50, 20 * 100 + 50);
    expect(result.collection.features[0].geometry.coordinates[0]).toBeCloseTo(expected[0], 12);
    expect(result.collection.features[0].geometry.coordinates[1]).toBeCloseTo(expected[1], 12);
  });

  it("无正客流时返回空集合", () => {
    const result = buildTripEndsHeatmapFeatureCollection(
      gridFixture(),
      Uint32Array.from([0, 0, 0]),
    );
    expect(result.maxFlow).toBe(0);
    expect(result.pointCount).toBe(0);
    expect(result.collection.features).toEqual([]);
  });
});

describe("filterTripEndsHeatmapFeatureCollection", () => {
  it("按行政区街道掩膜过滤，保留全市归一分母", () => {
    const payload = buildTripEndsHeatmapFeatureCollection(
      gridFixture(),
      Uint32Array.from([25, 100, 16]),
    );
    const filtered = filterTripEndsHeatmapFeatureCollection(payload, Uint8Array.from([1, 0]));
    expect(filtered.maxFlow).toBe(100);
    expect(filtered.pointCount).toBe(1);
    expect(filtered.collection.features[0].properties.streetIndex).toBe(0);
    expect(filtered.collection.features[0].properties.weight).toBe(0.5);
  });
});
