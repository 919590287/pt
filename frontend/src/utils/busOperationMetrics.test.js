import { describe, expect, it } from "vitest";
import { busOperationRatios } from "./busOperationMetrics.js";

describe("busOperationRatios", () => {
  it("按仿真口径计算三项公交运营效率", () => {
    expect(busOperationRatios(1200, {
      vehicles: 20,
      departures: 80,
      operatedKm: 600,
    })).toEqual({
      perVehicle: 60,
      perTrip: 15,
      intensity: 2,
    });
  });

  it("分母缺失时不伪造 0 指标", () => {
    const ratios = busOperationRatios(1200, {});
    expect(Number.isNaN(ratios.perVehicle)).toBe(true);
    expect(Number.isNaN(ratios.perTrip)).toBe(true);
    expect(Number.isNaN(ratios.intensity)).toBe(true);
  });
});
