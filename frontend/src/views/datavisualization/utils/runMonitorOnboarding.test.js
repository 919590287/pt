import { describe, expect, it } from "vitest";
import {
  RUN_MONITOR_ONBOARDING_STEPS,
  RUN_MONITOR_ONBOARDING_STORAGE_KEY,
  hasSeenRunMonitorOnboarding,
  markRunMonitorOnboardingSeen,
} from "./runMonitorOnboarding.js";

function memoryStorage() {
  const values = new Map();
  return {
    getItem: (key) => values.get(key) || null,
    setItem: (key, value) => values.set(key, value),
  };
}

describe("运行监测新手引导", () => {
  it("按总体认知、地图操作、数据入口的顺序覆盖全部11步", () => {
    expect(RUN_MONITOR_ONBOARDING_STEPS.map((step) => step.id)).toEqual([
      "intro",
      "modules",
      "dashboard",
      "map-navigation",
      "map-3d",
      "map-reset",
      "district",
      "model",
      "source",
      "account",
      "help",
    ]);
    expect(RUN_MONITOR_ONBOARDING_STEPS[0].centered).toBe(true);
    expect(RUN_MONITOR_ONBOARDING_STEPS.at(-1).target).toBe('[data-tour="onboarding-help"]');
  });

  it("首次展示后直接记为已看，不再需要结束偏好询问", () => {
    const storage = memoryStorage();
    expect(hasSeenRunMonitorOnboarding(storage)).toBe(false);

    markRunMonitorOnboardingSeen(storage);

    expect(storage.getItem(RUN_MONITOR_ONBOARDING_STORAGE_KEY)).toBe("seen");
    expect(hasSeenRunMonitorOnboarding(storage)).toBe(true);
  });

  it("存储不可用时安全降级", () => {
    const blocked = {
      getItem: () => { throw new Error("blocked"); },
      setItem: () => { throw new Error("blocked"); },
    };
    expect(hasSeenRunMonitorOnboarding(blocked)).toBe(false);
    expect(() => markRunMonitorOnboardingSeen(blocked)).not.toThrow();
  });
});
