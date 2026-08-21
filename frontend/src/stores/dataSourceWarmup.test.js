import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it, vi } from "vitest";

const real = vi.hoisted(() => ({ warmRealPassengerFlow: vi.fn() }));
vi.mock("@/utils/realPassengerFlow.js", () => ({
  DEFAULT_REAL_AREA: "广州市",
  warmRealPassengerFlow: real.warmRealPassengerFlow,
}));

import { useDataSourceWarmupStore } from "./dataSourceWarmup.js";
import { useModelSelectionStore } from "./modelSelection.js";

describe("dataSourceWarmup", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    real.warmRealPassengerFlow.mockReset();
  });

  it("warms the persisted real date once and reuses the ready result", async () => {
    useModelSelectionStore().setSelection("datavisualization", {
      sourceMode: "simulation",
      scheme: "广州",
      model: "模型",
      realServiceDate: "2026-06-14",
    });
    const payload = { serviceDate: "2026-06-14", bundle: {} };
    real.warmRealPassengerFlow.mockResolvedValue(payload);
    const store = useDataSourceWarmupStore();

    await expect(store.warm()).resolves.toBe(payload);
    await expect(store.warm()).resolves.toBe(payload);

    expect(real.warmRealPassengerFlow).toHaveBeenCalledTimes(1);
    expect(real.warmRealPassengerFlow).toHaveBeenCalledWith("广州市", "2026-06-14");
    expect(store.ready).toBe(true);
  });

  it("保留后台预取错误供真实数据页面局部提示", async () => {
    real.warmRealPassengerFlow.mockRejectedValueOnce(new Error("缓存不可用"));
    const store = useDataSourceWarmupStore();

    await expect(store.warm()).rejects.toThrow("缓存不可用");

    expect(store.ready).toBe(false);
    expect(store.status).toBe("error");
    expect(store.error).toBe("缓存不可用");
  });
});
