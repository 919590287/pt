import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({
  getSchemeList: vi.fn(),
  getModelList: vi.fn(),
  loadModel: vi.fn(),
}));
const modelCache = vi.hoisted(() => ({
  clearModelDataCache: vi.fn(),
}));

vi.mock("@/api/scheme.js", () => api);
vi.mock("@/utils/modelDataCache.js", () => modelCache);

import { useModelRuntimeStore } from "./modelRuntime.js";

describe("modelRuntime catalog scheduling", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.useFakeTimers();
    api.getSchemeList.mockReset();
    api.getModelList.mockReset();
    api.loadModel.mockReset();
    modelCache.clearModelDataCache.mockReset();
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it("coalesces concurrent scheme and model catalog requests", async () => {
    api.getSchemeList.mockResolvedValue({ data: ["广州"] });
    api.getModelList.mockResolvedValue({ data: [{ name: "广州/public/模型" }] });
    const store = useModelRuntimeStore();

    const [schemesA, schemesB] = await Promise.all([store.fetchSchemes(), store.fetchSchemes()]);
    const [modelsA, modelsB] = await Promise.all([
      store.fetchModels("广州"),
      store.fetchModels("广州", { force: true }),
    ]);

    expect(api.getSchemeList).toHaveBeenCalledTimes(1);
    expect(api.getModelList).toHaveBeenCalledTimes(1);
    expect(schemesA).toEqual(schemesB);
    expect(modelsA).toEqual(modelsB);
  });

  it("starts the target model without refreshing unrelated schemes", async () => {
    const events = [];
    api.getSchemeList.mockResolvedValue({ data: ["广州", "深圳"] });
    api.getModelList.mockImplementation(async ({ schemeName }) => {
      events.push(`models:${schemeName}`);
      return {
        data: [{
          name: `${schemeName}/public/模型`,
          cacheStatus: "ready",
          loadStatus: false,
          isDefault: true,
        }],
      };
    });
    api.loadModel.mockImplementation(async ({ name }) => {
      events.push(`load:${name}`);
      return { data: true };
    });
    const store = useModelRuntimeStore();

    await store.bootstrap();
    await Promise.resolve();

    expect(events.indexOf("models:广州")).toBeLessThan(events.indexOf("load:广州/public/模型"));
    expect(events).not.toContain("models:深圳");
  });

  it("does not open the gate because an unrelated model is ready", async () => {
    api.getSchemeList.mockResolvedValue({ data: ["广州", "深圳"] });
    api.getModelList.mockImplementation(async ({ schemeName }) => ({
      data: schemeName === "广州"
        ? [{
          name: "广州/public/v6",
          cacheStatus: "ready",
          loadStatus: false,
          isDefault: true,
        }]
        : [{
          name: "深圳/public/已就绪模型",
          cacheStatus: "ready",
          loadStatus: true,
          isDefault: true,
        }],
    }));
    api.loadModel.mockResolvedValue({ data: true });
    const store = useModelRuntimeStore();

    await store.bootstrap();
    await store.fetchModels("深圳");
    await Promise.resolve();
    await Promise.resolve();

    expect(store.anyModelReady).toBe(true);
    expect(store.gateTarget).toBe("广州/public/v6");
    expect(store.gateVisible).toBe(true);
    expect(api.loadModel).toHaveBeenCalledWith(
      { name: "广州/public/v6" },
      { silentError: true },
    );
  });

  it("stops model polling while the user is on an independent page", async () => {
    api.getSchemeList.mockResolvedValue({ data: ["广州"] });
    api.getModelList.mockResolvedValue({
      data: [{
        name: "广州/public/模型",
        cacheStatus: "ready",
        loadStatus: true,
        isDefault: true,
      }],
    });
    const store = useModelRuntimeStore();
    await store.bootstrap();
    const callsBeforePause = api.getModelList.mock.calls.length;

    store.pauseModelDemand();
    await vi.advanceTimersByTimeAsync(35_000);

    expect(api.getModelList).toHaveBeenCalledTimes(callsBeforePause);
  });

  it("clears derived browser data when the same model cache generation changes", async () => {
    api.getModelList
      .mockResolvedValueOnce({
        data: [{ name: "广州/public/v6", loadVersion: 4, cacheGeneratedAt: 100 }],
      })
      .mockResolvedValueOnce({
        data: [{ name: "广州/public/v6", loadVersion: 4, cacheGeneratedAt: 200 }],
      });
    const store = useModelRuntimeStore();

    await store.fetchModels("广州", { force: true });
    await store.fetchModels("广州", { force: true });

    expect(modelCache.clearModelDataCache).toHaveBeenCalledWith("广州/public/v6");
  });
});
