import { describe, expect, it } from "vitest";
import { checkEditConflict } from "./conflicts.js";

describe("scenario edit conflict checks", () => {
  it("blocks duplicate new stops", () => {
    const existing = [{
      id: "s1",
      kind: "stop.add",
      name: "科技园站",
      params: { name: "科技园站" },
      geometry: { coord: [113.3, 23.1], linkId: "l1" },
    }];
    const verdict = checkEditConflict({
      kind: "stop.add",
      name: "科技园站",
      params: { name: "科技园站" },
      geometry: { coord: [113.31, 23.11], linkId: "l2" },
    }, existing);
    expect(verdict.ok).toBe(false);
  });

  it("blocks disconnected new links", () => {
    const verdict = checkEditConflict({
      kind: "link.add",
      geometry: { coords: [[113.3, 23.1], [113.4, 23.2]], fromNodeId: null, toNodeId: "n2" },
    }, []);
    expect(verdict.ok).toBe(false);
    expect(verdict.reason).toContain("首尾端");
  });
});
