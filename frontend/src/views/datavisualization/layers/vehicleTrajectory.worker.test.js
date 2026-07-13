import { describe, expect, it } from "vitest";
import { buildBinarySecondIndex, segmentFrameFromBinary } from "./vehicleTrajectory.worker.js";

describe("vehicle trajectory viewport sampling", () => {
  it("keeps citywide stats but transfers only exact viewport segments at large scale", () => {
    const total = 200_000;
    const visible = 2_000;
    const segments = new Float32Array(total * 8);
    for (let index = 0; index < total; index++) {
      const offset = index * 8;
      const x = index < visible ? index % 100 : 100_000 + index;
      const y = index < visible ? Math.floor(index / 100) : 100_000;
      segments[offset] = 100;
      segments[offset + 1] = 102;
      segments[offset + 2] = x;
      segments[offset + 3] = y;
      segments[offset + 4] = x + 10;
      segments[offset + 5] = y + 2;
      segments[offset + 6] = index % 3;
      segments[offset + 7] = index;
    }
    const data = {
      binary: true,
      origin: [0, 0],
      chunk: { start: 0, end: 299 },
      chunkSeconds: 300,
      segments,
    };
    data.index = buildBinarySecondIndex(data);

    const result = segmentFrameFromBinary(100.5, data, 1, {
      minX: -50,
      minY: -50,
      maxX: 200,
      maxY: 100,
    });

    expect(result.stats.activeTotal).toBe(total);
    expect(result.segmentFrame.count).toBe(visible);
    expect(result.segmentFrame.startXs[1]).toBe(1);
    expect(result.segmentFrame.endXs[1]).toBe(11);
    const transferredBytes = result.transfer.reduce((sum, buffer) => sum + buffer.byteLength, 0);
    const legacyAllCityBytes = total * (6 * 4 + 1 + 4);
    expect(transferredBytes).toBe(visible * (6 * 4 + 1 + 4));
    expect(transferredBytes).toBeLessThan(legacyAllCityBytes / 50);
  });
});
