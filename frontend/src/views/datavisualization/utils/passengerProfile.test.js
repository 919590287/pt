import { describe, expect, it } from "vitest";
import { buildPassengerProfileGroups } from "./passengerProfile.js";

describe("passengerProfile", () => {
  it("把 MATSim 终点活动归并为中文出行目的，home 显示为返家", () => {
    const groups = buildPassengerProfileGroups({
      riderCount: 5,
      activitySource: "trip-purpose",
      activityTypes: [
        { type: "home", count: 2 },
        { type: "work_8h", count: 1 },
        { type: "gym", count: 1 },
        { type: "custom_english_code", count: 1 },
      ],
    });

    expect(groups[0].title).toBe("出行目的");
    expect(groups[0].items.map((item) => item.label)).toEqual(expect.arrayContaining(["返家", "通勤", "休闲", "其他"]));
    expect(groups[0].items.reduce((sum, item) => sum + item.value, 0)).toBe(100);
    expect(groups[0].items.some((item) => /home|work|gym|custom/i.test(item.label))).toBe(false);
  });

  it("同义原始活动合并为一个目的类别", () => {
    const purpose = buildPassengerProfileGroups({
      riderCount: 3,
      activitySource: "trip-purpose",
      activityTypes: [
        { type: "shop", count: 1 },
        { type: "shopping", count: 2 },
      ],
    })[0];

    expect(purpose.items).toHaveLength(1);
    expect(purpose.items[0]).toMatchObject({ key: "shopping", label: "购物", count: 3, value: 100 });
  });

  it("拒绝后端以全活动冒充出行目的", () => {
    expect(() => buildPassengerProfileGroups({
      riderCount: 1,
      activitySource: "all-activities-fallback",
      activityTypes: [{ type: "home", count: 1 }],
    })).toThrow("客流画像活动口径非法");
  });

  it("缺少 riderCount 时不再用活动计数兜底", () => {
    expect(() => buildPassengerProfileGroups({
      activitySource: "trip-purpose",
      activityTypes: [{ type: "home", count: 1 }],
    })).toThrow("客流画像缺少有效的 riderCount");
  });

  it("真实刷卡画像完整展示四类票卡客群且不伪造出行目的", () => {
    const groups = buildPassengerProfileGroups({
      riderCount: 100,
      source: "real-card-type",
      activitySource: "card-type-only",
      passengerGroups: [
        { key: "student", label: "学生票卡", count: 20, ratio: 20 },
        { key: "elderly", label: "老年票卡", count: 10, ratio: 10 },
        { key: "disability_or_concession", label: "优抚/残疾票卡", count: 5, ratio: 5 },
        { key: "general_or_unknown", label: "一般/未知票卡", count: 65, ratio: 65 },
      ],
    });

    expect(groups).toHaveLength(1);
    expect(groups[0].title).toBe("票卡客群");
    expect(groups[0].items.map((item) => item.label)).toEqual([
      "学生票卡",
      "老年票卡",
      "优抚/残疾票卡",
      "一般/未知票卡",
    ]);
    expect(groups[0].items.reduce((sum, item) => sum + item.value, 0)).toBe(100);
  });
});
