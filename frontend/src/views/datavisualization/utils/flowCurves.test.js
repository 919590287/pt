import { describe, expect, it } from "vitest";
import { buildFlowCurveFeatureCollection, curvedLineCoordinates } from "./flowCurves.js";

describe("OD desire lines", () => {
  it("draws a straight line whose final coordinate is the destination", () => {
    const from = [113.52, 22.78];
    const to = [113.61, 22.75];
    const coordinates = curvedLineCoordinates(from, to, { curvature: 0, segments: 8 });

    expect(coordinates[0]).toEqual(from);
    expect(coordinates.at(-1)).toEqual(to);
    coordinates.forEach((point, index) => {
      const ratio = index / (coordinates.length - 1);
      expect(point[0]).toBeCloseTo(from[0] + (to[0] - from[0]) * ratio, 8);
      // 曲线在 Web Mercator 中计算，反投影回纬度后允许亚米级非线性误差。
      expect(point[1]).toBeCloseTo(from[1] + (to[1] - from[1]) * ratio, 5);
    });
  });

  it("preserves style properties on direct OD lines", () => {
    const result = buildFlowCurveFeatureCollection([{
      from: [113.52, 22.78],
      to: [113.61, 22.75],
      value: 42,
      properties: { color: "#f03b20", width: 3.4 },
    }], { curvature: 0 });

    expect(result.features[0].geometry.coordinates.at(-1)).toEqual([113.61, 22.75]);
    expect(result.features[0].properties).toMatchObject({
      value: 42,
      color: "#f03b20",
      width: 3.4,
    });
  });

  it("bends real OD desire lines away from the straight chord while preserving endpoints", () => {
    const from = [113.2, 23.0];
    const to = [113.4, 23.0];
    const coordinates = curvedLineCoordinates(from, to, { curvature: 0.16, segments: 20 });
    const midpoint = coordinates[Math.floor(coordinates.length / 2)];

    expect(coordinates[0]).toEqual(from);
    expect(coordinates.at(-1)).toEqual(to);
    expect(midpoint[1]).toBeGreaterThan(23.005);
  });

  it("keeps opposite-direction OD curves on the same side of the route", () => {
    const result = buildFlowCurveFeatureCollection([
      { from: [113.2, 23.0], to: [113.4, 23.0], value: 18 },
      { from: [113.4, 23.0], to: [113.2, 23.0], value: 12 },
    ], { curvature: 0.24, consistentSide: true, segments: 20 });

    const firstMidpoint = result.features[0].geometry.coordinates[10];
    const reverseMidpoint = result.features[1].geometry.coordinates[10];

    expect(firstMidpoint[1]).toBeGreaterThan(23.005);
    expect(reverseMidpoint[1]).toBeGreaterThan(23.005);
  });
});
