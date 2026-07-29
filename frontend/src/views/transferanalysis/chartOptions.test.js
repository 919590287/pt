import { describe, expect, it } from "vitest";
import { directionStackRankOption } from "./chartOptions.js";

const THEME = {
  busToMetro: "#f97316",
  metroToBus: "#0071e3",
  chart: {},
};

const ITEMS = [
  { name: "一号线", flow: 30, b2m: 20, m2b: 10, avgSec: 300, b2mAvgSec: 240, m2bAvgSec: 420 },
  { name: "二号线", flow: 50, b2m: 15, m2b: 35, avgSec: 600, b2mAvgSec: 540, m2bAvgSec: 660 },
];

describe("directionStackRankOption", () => {
  it("客流口径在同一堆叠柱中分别保留两个方向", () => {
    const option = directionStackRankOption(ITEMS, THEME, false, { metric: "flow" });
    expect(option.series).toHaveLength(2);
    expect(option.series[0].stack).toBe("direction");
    expect(option.series[1].stack).toBe("direction");
    expect(option.yAxis.data).toEqual(["一号线", "二号线"]);
    expect(option.series[0].data).toEqual([20, 15]);
    expect(option.series[1].data).toEqual([10, 35]);
  });

  it("平均时间口径按总均时排名并使用方向均时", () => {
    const option = directionStackRankOption(ITEMS, THEME, false, { metric: "avgSec" });
    expect(option.yAxis.data).toEqual(["一号线", "二号线"]);
    expect(option.series[0].data).toEqual([240, 540]);
    expect(option.series[1].data).toEqual([420, 660]);
  });
});
