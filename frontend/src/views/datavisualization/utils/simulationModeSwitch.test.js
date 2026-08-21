import { describe, expect, it, vi } from "vitest";

import { ensureSimulationCatalog } from "./simulationModeSwitch.js";

describe("ensureSimulationCatalog", () => {
  it("loads the model catalog after loading schemes on a real-first visit", async () => {
    let schemesReady = false;
    let modelsReady = false;
    const fetchSchemes = vi.fn(async () => {
      schemesReady = true;
    });
    const fetchModels = vi.fn(async () => {
      modelsReady = true;
    });

    await ensureSimulationCatalog({
      getScheme: () => "广州市",
      hasSchemeCatalog: () => schemesReady,
      hasModelCatalog: () => modelsReady,
      fetchSchemes,
      fetchModels,
    });

    expect(fetchSchemes).toHaveBeenCalledTimes(1);
    expect(fetchModels).toHaveBeenCalledWith("广州市");
  });

  it("uses warm catalogs without issuing requests", async () => {
    const fetchSchemes = vi.fn();
    const fetchModels = vi.fn();

    const current = await ensureSimulationCatalog({
      getScheme: () => "广州市",
      hasSchemeCatalog: () => true,
      hasModelCatalog: () => true,
      fetchSchemes,
      fetchModels,
    });

    expect(current).toBe(true);
    expect(fetchSchemes).not.toHaveBeenCalled();
    expect(fetchModels).not.toHaveBeenCalled();
  });

  it("does not continue with models after a newer mode switch supersedes it", async () => {
    let current = true;
    const fetchModels = vi.fn();

    const completed = await ensureSimulationCatalog({
      getScheme: () => "广州市",
      hasSchemeCatalog: () => false,
      hasModelCatalog: () => false,
      fetchSchemes: async () => {
        current = false;
      },
      fetchModels,
      isCurrent: () => current,
    });

    expect(completed).toBe(false);
    expect(fetchModels).not.toHaveBeenCalled();
  });
});
