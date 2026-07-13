import { describe, expect, it } from "vitest";
import { validateAreaPolygon, validateSlots } from "./utils.js";

describe("scenario edit input validation", () => {
  it("accepts ordered, non-overlapping service slots", () => {
    expect(validateSlots([
      { from: "06:30", to: "09:00", headwayMin: 8 },
      { from: "16:00", to: "22:00", headwayMin: 10 },
    ])).toEqual([]);
  });

  it("rejects reversed and overlapping service slots", () => {
    expect(validateSlots([{ from: "09:00", to: "08:00", headwayMin: 10 }])).toContain("第 1 个时段的结束时间必须晚于开始时间");
    expect(validateSlots([
      { from: "06:30", to: "10:00", headwayMin: 10 },
      { from: "09:30", to: "12:00", headwayMin: 10 },
    ]).some((message) => message.includes("重叠"))).toBe(true);
  });

  it("rejects self-intersecting research areas", () => {
    expect(validateAreaPolygon([[113, 23], [114, 24], [113, 24], [114, 23]])).toHaveLength(1);
    expect(validateAreaPolygon([[113, 23], [114, 23], [114, 24], [113, 24]])).toEqual([]);
  });
});
