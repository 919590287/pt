import { describe, expect, it } from "vitest";
import { hidesTransitNetwork } from "./baseMapVisibility.js";

describe("运行监测公共线网显隐", () => {
  it.each([
    "人口分布监测",
    "公交出行监测",
    "客流走廊监测",
  ])("%s 不显示任何公共线路", (tab) => {
    expect(hidesTransitNetwork(tab)).toBe(true);
  });

  it.each([
    "总体客流监测",
    "线路客流监测",
    "体检评估分析",
  ])("%s 保留公共线路", (tab) => {
    expect(hidesTransitNetwork(tab)).toBe(false);
  });
});
