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

    expect(store.getSelection("datavisualization")).toMatchObject({
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

  it("未选择真实日期时不再回退到日平均", () => {
    const store = useModelSelectionStore();
    store.setSelection("datavisualization-empty-date", { sourceMode: "real" });
    expect(store.getSelection("datavisualization-empty-date").realServiceDate).toBe("");
  });

  it("刷新时保留页签与地图相机", () => {
    const store = useModelSelectionStore();
    store.setSelection("datavisualization", {
      viewState: {
        monitorActiveTab: "车辆运行监测",
        departureMonitorSection: "segments",
      },
      mapCamera: {
        center: [12634609, 2659952],
        zoom: 12.5,
        pitch: 72,
        rotation: 18,
      },
    });

    expect(store.getSelection("datavisualization")).toMatchObject({
      viewState: {
        monitorActiveTab: "车辆运行监测",
        departureMonitorSection: "segments",
      },
      mapCamera: {
        center: [12634609, 2659952],
        zoom: 12.5,
        pitch: 72,
        rotation: 18,
      },
    });
  });
});
