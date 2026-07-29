import { describe, expect, it } from "vitest";
import { segmentDisplayName, segmentEndpointNames } from "./routeSegments.js";

describe("route segment names", () => {
  it("keeps the simulation payload name", () => {
    const segment = { name: "南沙奥园站 - 晴海岸站" };

    expect(segmentDisplayName(segment)).toBe("南沙奥园站 - 晴海岸站");
    expect(segmentEndpointNames(segment)).toEqual({
      fromName: "南沙奥园站",
      toName: "晴海岸站",
    });
  });

  it("builds a display name from the real-data endpoint fields", () => {
    const segment = {
      fromName: "南沙奥园站",
      toName: "晴海岸站",
      stationNames: ["南沙奥园站", "晴海岸站"],
    };

    expect(segmentDisplayName(segment)).toBe("南沙奥园站 - 晴海岸站");
    expect(segmentEndpointNames(segment)).toEqual({
      fromName: "南沙奥园站",
      toName: "晴海岸站",
    });
  });

  it("falls back to stationNames for older real-data payloads", () => {
    expect(segmentDisplayName({
      stationNames: ["塘坑村站", "金岭南路口站"],
    })).toBe("塘坑村站 - 金岭南路口站");
  });
});
