import { describe, expect, it } from "vitest";
import {
  realCapabilityAvailable,
  realCapabilityAvailableInAny,
} from "./realCapabilityMenu.js";

describe("real capability menu", () => {
  const modules = [
    {
      platformModule: "客流分析",
      leftPanelModule: "线路客流监测-断面客流",
      available: true,
    },
    {
      platformModule: "客流分析",
      leftPanelModule: "站点客流监测-站点乘降",
      available: true,
    },
    {
      platformModule: "运行监测",
      leftPanelModule: "线路客流监测-断面客流",
      available: false,
    },
  ];

  it("运行监测合并导航后仍复用客流分析的真实数据能力", () => {
    expect(realCapabilityAvailableInAny(modules, [
      { platform: "运行监测", panel: "线路客流监测-断面客流" },
      { platform: "客流分析", panel: "线路客流监测-断面客流" },
    ])).toBe(true);
    expect(realCapabilityAvailableInAny(modules, [
      { platform: "运行监测", panel: "站点客流监测-站点乘降" },
      { platform: "客流分析", panel: "站点客流监测-站点乘降" },
    ])).toBe(true);
  });

  it("所有候选都不可用时仍隐藏功能", () => {
    expect(realCapabilityAvailableInAny(modules, [
      { platform: "运行监测", panel: "站点客流监测-客流OD" },
      { platform: "客流分析", panel: "站点客流监测-客流OD" },
    ])).toBe(false);
  });

  it("旧能力接口未返回模块数组时不误隐藏", () => {
    expect(realCapabilityAvailable(undefined, "运行监测", "线路客流监测")).toBe(true);
    expect(realCapabilityAvailableInAny(undefined, [])).toBe(true);
  });
});
