import { describe, expect, it } from "vitest";
import {
  adminDistrictOutlineStyle,
  districtOutlineFeatureCollection,
  districtOutlineGeometry,
} from "./adminDistrictRange.js";

describe("admin district outline", () => {
  it("全平台统一使用黑色虚线", () => {
    const style = adminDistrictOutlineStyle();
    expect(style.paint["line-color"]).toBe("#000000");
    expect(style.paint["line-dasharray"]).toEqual([3.2, 2.4]);
  });

  it("将融合后的多面行政区完整转成多线描边", () => {
    const geometry = {
      type: "MultiPolygon",
      coordinates: [
        [
          [[0, 0], [2, 0], [2, 2], [0, 0]],
          [[0.5, 0.5], [1, 0.5], [0.5, 0.5]],
        ],
        [
          [[3, 3], [4, 3], [4, 4], [3, 3]],
        ],
      ],
    };

    expect(districtOutlineGeometry(geometry)).toEqual({
      type: "MultiLineString",
      coordinates: [geometry.coordinates[0][0], geometry.coordinates[0][1], geometry.coordinates[1][0]],
    });

    const context = {
      feature: {
        id: "district-test",
        geometry,
        properties: { _districtName: "测试区" },
      },
    };
    const collection = districtOutlineFeatureCollection(context);
    expect(collection.features).toHaveLength(1);
    expect(collection.features[0].id).toBe("district-test");
    expect(collection.features[0].properties._districtName).toBe("测试区");
  });

  it("未选区时返回空集合", () => {
    expect(districtOutlineFeatureCollection(null)).toEqual({ type: "FeatureCollection", features: [] });
  });
});
