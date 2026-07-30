import { describe, expect, it } from "vitest";
import {
  filterStationOutboundOdRows,
  stationOdCoordinateToLngLat,
} from "./stationOd.js";

describe("station OD display data", () => {
  it("keeps only trips boarding at the selected station", () => {
    const rows = [
      { origin: "黄阁镇政府站", destination: "番禺广场", direction: "out" },
      { origin: "万顷沙站", destination: "黄阁镇政府站", direction: "in" },
      { origin: "", destination: "灵山岛站", direction: "outbound" },
    ];

    expect(filterStationOutboundOdRows(rows, ["黄阁镇政府站"])).toEqual([
      rows[0],
      rows[2],
    ]);
  });

  it("accepts longitude/latitude coordinates unchanged", () => {
    expect(stationOdCoordinateToLngLat(113.509, 22.824)).toEqual([113.509, 22.824]);
  });

  it("converts legacy real-mode Web Mercator coordinates", () => {
    const result = stationOdCoordinateToLngLat(12635819.427, 2611302.798);

    expect(result?.[0]).toBeCloseTo(113.5095, 3);
    expect(result?.[1]).toBeCloseTo(22.8286, 3);
  });
});
