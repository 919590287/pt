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
import { useModelSelectionStore } from "./modelSelection.js";

describe("modelRuntime catalog scheduling", () => {
  beforeEach(() => {
    sessionStorage.clear();
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

  it("刷新恢复已加载模型时不因缓存失败再次调用 loadModel", async () => {
    api.getSchemeList.mockResolvedValue({ data: ["广州"] });
    api.getModelList.mockResolvedValue({
      data: [{
        name: "广州/public/V6",
        cacheStatus: "failed",
        cacheMessage: "某个派生缓存不可用",
        loadStatus: true,
        isDefault: true,
      }],
    });
    const selectionStore = useModelSelectionStore();
    selectionStore.setSelection("datavisualization", {
      sourceMode: "simulation",
      scheme: "广州",
      model: "广州/public/V6",
    });

    const store = useModelRuntimeStore();
    await store.bootstrap();

    expect(store.gateVisible).toBe(false);
    expect(api.loadModel).not.toHaveBeenCalled();
  });

  it("刷新恢复模型时在目录返回前保持门禁关闭", async () => {
    let resolveModels;
    api.getSchemeList.mockResolvedValue({ data: ["广州"] });
    api.getModelList.mockReturnValue(new Promise((resolve) => {
      resolveModels = resolve;
    }));
    useModelSelectionStore().setSelection("datavisualization", {
      sourceMode: "simulation",
      scheme: "广州",
      model: "广州/public/V6",
    });

    const store = useModelRuntimeStore();
    const pending = store.bootstrap();
    await Promise.resolve();
    await Promise.resolve();

    expect(store.gateTarget).toBe("广州/public/V6");
    expect(store.gateVisible).toBe(false);

    resolveModels({
      data: [{ name: "广州/public/V6", loadStatus: true, cacheStatus: "ready" }],
    });
    await pending;
    expect(store.gateVisible).toBe(false);
  });

  it("does not replace a persisted real-data mode when the simulation gate opens", async () => {
    api.getSchemeList.mockResolvedValue({ data: ["广州"] });
    api.getModelList.mockResolvedValue({
      data: [{
        name: "广州/public/V6",
        cacheStatus: "ready",
        loadStatus: true,
      }],
    });
    const selectionStore = useModelSelectionStore();
    selectionStore.setSelection("datavisualization", {
      sourceMode: "real",
      scheme: "广州",
      model: "广州/public/V6",
      realServiceDate: "2026-03-10",
    });

    await useModelRuntimeStore().bootstrap();

    expect(selectionStore.getSelection("datavisualization")).toMatchObject({
      sourceMode: "real",
      realServiceDate: "2026-03-10",
    });
    expect(useModelRuntimeStore().gateVisible).toBe(false);
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

  // 目录"还没取回"和"确实没有模型"必须区分开：混为一谈会让门禁误报"暂无可用模型"，
  // 并且 gateVisible 误判为 true 会让 MapLayout 的 RouterView v-if 把整页卸载重建。
  it("目录未取回时不亮门禁，避免整页被卸载重建", async () => {
    api.getSchemeList.mockResolvedValue({ data: ["广州"] });
    api.getModelList.mockResolvedValue({
      data: [{ name: "广州/public/v6", cacheStatus: "ready", loadStatus: true }],
    });
    const store = useModelRuntimeStore();
    await store.bootstrap();
    expect(store.gateVisible).toBe(false);

    // 模拟切到一个目录尚未取回的方案：gateModels 为空，但这只是"还不知道"
    store.gateScheme = "深圳";
    expect(store.gateCatalogKnown).toBe(false);
    expect(store.gateVisible).toBe(false);
  });

  it("目录取回后确实为空才判定为暂无模型", async () => {
    api.getSchemeList.mockResolvedValue({ data: ["广州"] });
    api.getModelList.mockResolvedValue({ data: [] });
    const store = useModelRuntimeStore();
    await store.bootstrap();

    expect(store.gateCatalogKnown).toBe(true);
    expect(store.gateModels).toEqual([]);
    expect(store.gateVisible).toBe(true);
  });

  it("门禁里换方案时先取目录再切，不留空窗", async () => {
    api.getSchemeList.mockResolvedValue({ data: ["广州", "深圳"] });
    api.getModelList.mockImplementation(async ({ schemeName }) => ({
      data: [{ name: `${schemeName}/public/v6`, cacheStatus: "ready", loadStatus: true }],
    }));
    const store = useModelRuntimeStore();
    await store.bootstrap();

    const pending = store.selectGateScheme("深圳");
    // 目录还没回来之前 gateScheme 不得先行切换，否则 gateModels 会空一拍
    expect(store.gateScheme).toBe("广州");
    await pending;
    expect(store.gateScheme).toBe("深圳");
    expect(store.gateCatalogKnown).toBe(true);
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
