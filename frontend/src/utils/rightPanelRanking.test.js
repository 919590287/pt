import { describe, expect, it } from "vitest";
import {
  LINE_RANK_METRICS,
  RIGHT_PANEL_RANK_LIMIT,
  buildLineRankEntries,
  limitRightPanelRanking,
  lineRankMetricValues,
} from "./rightPanelRanking.js";

describe("right panel ranking", () => {
  it("统一限制为 TOP10，且不修改完整结果", () => {
    const rows = Array.from({ length: 15 }, (_, index) => index + 1);

    expect(RIGHT_PANEL_RANK_LIMIT).toBe(10);
    expect(limitRightPanelRanking(rows)).toEqual(rows.slice(0, 10));
    expect(rows).toHaveLength(15);
  });

  it("无有效排名数组时返回空数组", () => {
    expect(limitRightPanelRanking()).toEqual([]);
    expect(limitRightPanelRanking(null)).toEqual([]);
  });

  it("统一使用后端上下行合并的线路级数据", () => {
    const panel = {
      routes: { r1: { desc: "甲站—乙站" } },
      lineGroups: {
        "bus::1": {
          lineId: "1",
          lineName: "1路",
          mode: "bus",
          routeKeys: ["r1"],
          // 故意与 passenger 不同，验证总客流以权威线级统计值为准。
          hourlyFlow: [999],
          metrics: {
            passenger: 120,
            vehicles: 4,
            departures: 10,
            operatingVehicleKm: 48,
            perVehicleFlow: 30,
            perTripFlow: 12,
            passengerStrength: 2.5,
            peakAverageLoadRate: 99.92,
          },
        },
      },
    };

    expect(LINE_RANK_METRICS.map((item) => item.key)).toEqual([
      "flow", "perVehicleFlow", "perTripFlow", "strength", "peakLoadRate",
    ]);
    expect(buildLineRankEntries(panel, "bus")).toEqual([expect.objectContaining({
      lineName: "1路",
      desc: "甲站—乙站",
      flow: 120,
      perVehicleFlow: 30,
      perTripFlow: 12,
      strength: 2.5,
      peakLoadRate: 99.92,
    })]);
  });

  it("有分子分母时按仿真规范公式重算，不采信数据源预计算比值", () => {
    const metrics = {
      passenger: 1200,
      vehicles: 20,
      departures: 80,
      operatingVehicleKm: 600,
      // 模拟真实数据接口携带的旧公式或错误预计算值。
      perVehicleFlow: 999,
      perTripFlow: 999,
      passengerStrength: 999,
      peakAverageLoadRate: 76.5,
    };

    expect(lineRankMetricValues(metrics, metrics.passenger)).toEqual({
      perVehicleFlow: 60,
      perTripFlow: 15,
      strength: 2,
      peakLoadRate: 76.5,
    });
    expect(buildLineRankEntries({
      source: "real",
      lineGroups: {
        "bus::1": {
          lineId: "1",
          lineName: "1路",
          mode: "bus",
          metrics,
        },
      },
    }, "bus")).toEqual([expect.objectContaining({
      flow: 1200,
      perVehicleFlow: 60,
      perTripFlow: 15,
      strength: 2,
    })]);
  });

  it("缺少公式分母时不读取任何已有比值", () => {
    const values = lineRankMetricValues({
      perVehicleFlow: 30,
      perTripFlow: 12,
      passengerStrength: 2.5,
    }, 120);

    expect(Number.isNaN(values.perVehicleFlow)).toBe(true);
    expect(Number.isNaN(values.perTripFlow)).toBe(true);
    expect(Number.isNaN(values.strength)).toBe(true);
  });

  it("没有规范 lineGroups 时不使用旧 routes 重建排名", () => {
    const panel = {
      routes: {
        up: {
          lineId: "1", lineName: "1路", mode: "bus",
          metrics: {
            passenger: 60, departures: 6, operatingVehicleKm: 20,
            vehicleIds: ["v1", "v2"], peakAverageLoadRate: 50,
            peakDepartureSamples: 2, peakMissingCapacityDepartures: 0,
          },
        },
        down: {
          lineId: "1", lineName: "1路", mode: "bus",
          metrics: {
            passenger: 40, departures: 4, operatingVehicleKm: 30,
            vehicleIds: ["v2", "v3"], peakAverageLoadRate: 80,
            peakDepartureSamples: 1, peakMissingCapacityDepartures: 0,
          },
        },
      },
    };

    expect(buildLineRankEntries(panel, "bus")).toEqual([]);
  });
});
