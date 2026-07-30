import { describe, expect, it } from "vitest";

import { displayRangeNetworkState } from "./displayRangeReadiness.js";

describe("displayRangeNetworkState", () => {
  it("keeps administrative filtering pending until the selected model network arrives", () => {
    expect(displayRangeNetworkState("real::广州市", "", [])).toBe("pending");
    expect(displayRangeNetworkState("real::广州市", "simulation/V6", [{}])).toBe("pending");
  });

  it("distinguishes a loaded empty network from a ready network", () => {
    expect(displayRangeNetworkState("real::广州市", "real::广州市", [])).toBe("empty");
    expect(displayRangeNetworkState("real::广州市", "real::广州市", [{}])).toBe("ready");
  });
});
