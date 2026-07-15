/**
 * 线网运营里程聚合契约测试（数据总览「线网总运营里程 / 企业运营里程」列的口径基础）。
 * 口径：日运营里程 = Σ(方向级 dep_count × 几何长度)；dep_count 缺失（旧 schema）不计入并回报缺失数，
 * 全部缺失时里程为 null；多企业共营线路（"、/／"分隔）各计全额（与线路数量列的双计口径一致）。
 */
import { describe, expect, it } from "vitest";
import { collectionOperationMetrics } from "./districtFilterCore.js";

const splitCompanies = (value) =>
  String(value || "")
    .split(/[、,，/／;；]+/)
    .map((item) => item.trim())
    .filter(Boolean);

function feature(dep, company, lengthKm) {
  return {
    type: "Feature",
    geometry: { type: "LineString", coordinates: [] },
    properties: { dep_count: dep, company },
    _lenKm: lengthKm,
  };
}

const lengthOf = (f) => (f._lenKm || 0) * 1000;

function collect(features) {
  return { type: "FeatureCollection", features };
}

describe("collectionOperationMetrics", () => {
  it("日运营里程 = Σ(班次×长度)，按企业分组累计", () => {
    const result = collectionOperationMetrics(
      collect([feature("60", "巴士集团", 20), feature("40", "第一巴士", 10), feature("10", "巴士集团", 5)]),
      lengthOf,
      splitCompanies,
    );
    expect(result.mileageKmPerDay).toBeCloseTo(60 * 20 + 40 * 10 + 10 * 5);
    expect(result.companyMileageKm.get("巴士集团")).toBeCloseTo(1250);
    expect(result.companyMileageKm.get("第一巴士")).toBeCloseTo(400);
    expect(result.missingDepCount).toBe(0);
  });

  it("dep_count 缺失/非法不计入并回报缺失数；全部缺失时里程为 null", () => {
    const partial = collectionOperationMetrics(
      collect([feature("60", "巴士集团", 20), feature("", "巴士集团", 10), feature(null, "第一巴士", 8)]),
      lengthOf,
      splitCompanies,
    );
    expect(partial.mileageKmPerDay).toBeCloseTo(1200);
    expect(partial.missingDepCount).toBe(2);

    const none = collectionOperationMetrics(
      collect([feature("", "巴士集团", 10), feature(undefined, "第一巴士", 8)]),
      lengthOf,
      splitCompanies,
    );
    expect(none.mileageKmPerDay).toBeNull();
    expect(none.companyMileageKm.size).toBe(0);
  });

  it("共营线路对每家企业各计全额（与线路数量列口径一致）；零长度要素跳过", () => {
    const result = collectionOperationMetrics(
      collect([feature("30", "南巴、润信", 10), feature("50", "润信", 0)]),
      lengthOf,
      splitCompanies,
    );
    expect(result.mileageKmPerDay).toBeCloseTo(300);
    expect(result.companyMileageKm.get("南巴")).toBeCloseTo(300);
    expect(result.companyMileageKm.get("润信")).toBeCloseTo(300);
  });
});
