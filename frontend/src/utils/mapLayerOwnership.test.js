import { describe, expect, it } from "vitest";
import { quarantineInactiveStyleLayers, styleLayerOwner } from "./mapLayerOwnership.js";

const GROUPS = {
  datamanagement: { key: "dm", stylePrefixes: ["dm-"] },
  datavisualization: { key: "rm", stylePrefixes: ["rm-", "selected-route-stops"] },
};

function fakeMap(layerIds, initialVisibility = {}) {
  const visibility = new Map(layerIds.map((id) => [id, initialVisibility[id] || "visible"]));
  return {
    getStyle: () => ({ layers: layerIds.map((id) => ({ id })) }),
    getLayoutProperty: (id) => visibility.get(id),
    setLayoutProperty: (id, _property, value) => visibility.set(id, value),
    visibility,
  };
}

describe("map layer ownership", () => {
  it("resolves both prefixed and legacy owned layers", () => {
    expect(styleLayerOwner("dm-real-bus-lines", GROUPS)).toBe("dm");
    expect(styleLayerOwner("rm-bus-network-stations", GROUPS)).toBe("rm");
    expect(styleLayerOwner("selected-route-stops-layer", GROUPS)).toBe("rm");
    expect(styleLayerOwner("base-raster", GROUPS)).toBe("");
  });

  it("quarantines late layers from an inactive page and preserves visibility", () => {
    const map = fakeMap(["dm-real-bus-lines", "rm-bus-network-stations", "base-raster"]);
    const stash = new Map();

    expect(quarantineInactiveStyleLayers(map, GROUPS, "dm", stash)).toBe(1);
    expect(map.visibility.get("dm-real-bus-lines")).toBe("visible");
    expect(map.visibility.get("rm-bus-network-stations")).toBe("none");
    expect(map.visibility.get("base-raster")).toBe("visible");
    expect(stash.get("rm").get("rm-bus-network-stations")).toBe("visible");

    // MapLibre emits styledata for the visibility update; reconciliation must
    // be stable and must not overwrite the saved desired visibility with none.
    expect(quarantineInactiveStyleLayers(map, GROUPS, "dm", stash)).toBe(0);
    expect(stash.get("rm").get("rm-bus-network-stations")).toBe("visible");
  });

  it("captures a later inactive attempt to show a quarantined layer", () => {
    const map = fakeMap(["rm-bus-network-lines"]);
    const stash = new Map([["rm", new Map([["rm-bus-network-lines", "none"]])]]);
    map.visibility.set("rm-bus-network-lines", "visible");

    quarantineInactiveStyleLayers(map, GROUPS, "dm", stash);

    expect(map.visibility.get("rm-bus-network-lines")).toBe("none");
    expect(stash.get("rm").get("rm-bus-network-lines")).toBe("visible");
  });
});
