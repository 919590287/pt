import { createPinia, setActivePinia } from "pinia";
import { beforeEach, describe, expect, it } from "vitest";

import { useModelSelectionStore } from "./modelSelection.js";

describe("modelSelection persistence", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it("preserves real mode and its service date", () => {
    const store = useModelSelectionStore();

    store.setSelection("datavisualization", {
      sourceMode: "real",
      scheme: "广州市",
      model: "广州市/public/V6",
      realServiceDate: "2026-03-10",
    });

    expect(store.getSelection("datavisualization")).toEqual({
      sourceMode: "real",
      scheme: "广州市",
      model: "广州市/public/V6",
      realServiceDate: "2026-03-10",
    });
  });

  it("does not reset real mode when only the simulation model metadata changes", () => {
    const store = useModelSelectionStore();
    store.setSelection("datavisualization", {
      sourceMode: "real",
      realServiceDate: "2026-03-10",
    });

    store.setSelection("datavisualization", {
      scheme: "广州市",
      model: "广州市/public/V6",
    });

    expect(store.getSelection("datavisualization")).toMatchObject({
      sourceMode: "real",
      realServiceDate: "2026-03-10",
      scheme: "广州市",
      model: "广州市/public/V6",
    });
  });
});
