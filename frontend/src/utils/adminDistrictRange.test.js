import { describe, expect, it } from "vitest";
import {
  adminDistrictOutlineStyle,
  districtOutlineFeatureCollection,
  districtOutlineGeometry,
} from "./adminDistrictRange.js";

describe("admin district outline", () => {
  it("全平台统一行政区边界描边虚线，支持暗色底图下切换为白色", () => {
    const styleLight = adminDistrictOutlineStyle(false);
    expect(styleLight.paint["line-color"]).toBe("#000000");
    expect(styleLight.layout["line-cap"]).toBe("butt");
    expect(styleLight.paint["line-dasharray"]).toEqual([2.5, 2.5]);

    const styleDark = adminDistrictOutlineStyle(true);
    expect(styleDark.paint["line-color"]).toBe("#ffffff");
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
