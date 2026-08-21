import { describe, expect, it } from "vitest";
import { quantizeTrajectoryViewport, supportsTrajectorySpatialChunks } from "./trajectorySpatial.js";

describe("trajectory spatial windows", () => {
  const manifest = { spatial: { layout: "indexed-container-midpoint-envelope-v2", tileSizeMeters: 4096 } };

  it("quantizes expanded viewport bounds into a stable cache window", () => {
    const first = quantizeTrajectoryViewport({ minX: 4100, minY: -10, maxX: 9000, maxY: 5000 }, manifest);
    const second = quantizeTrajectoryViewport({ minX: 4200, minY: -20, maxX: 8900, maxY: 4900 }, manifest);

    expect(first).toMatchObject({ minX: 4096, minY: -4096, maxX: 12288, maxY: 8192 });
    expect(first.key).toBe(second.key);
  });

  it("detects only the spatial cache protocol", () => {
    expect(supportsTrajectorySpatialChunks(manifest)).toBe(true);
    expect(supportsTrajectorySpatialChunks({
      spatial: {
        layout: "indexed-zstd-spatial-blocks-v3",
        tileSizeMeters: 4096,
        independentBlocks: true,
      },
    })).toBe(true);
    expect(supportsTrajectorySpatialChunks({ chunkSeconds: 30 })).toBe(false);
    expect(supportsTrajectorySpatialChunks({
      spatial: { layout: "unknown-spatial-v99", tileSizeMeters: 4096 },
    })).toBe(false);
  });

  it("treats exact positive and negative max bounds as exclusive tile edges", () => {
    expect(quantizeTrajectoryViewport(
      { minX: 4096, minY: 8192, maxX: 12288, maxY: 16384 },
      manifest,
    )).toMatchObject({ tileMinX: 1, tileMaxX: 2, tileMinY: 2, tileMaxY: 3 });

    expect(quantizeTrajectoryViewport(
      { minX: -12288, minY: -8192, maxX: -4096, maxY: 0 },
      manifest,
    )).toMatchObject({ tileMinX: -3, tileMaxX: -2, tileMinY: -2, tileMaxY: -1 });
  });
});
