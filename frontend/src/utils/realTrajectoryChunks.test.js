import { describe, expect, it } from "vitest";
import { buildRealTrajectoryChunkSource, realTrajectoryChunkAt } from "./realTrajectoryChunks.js";

describe("real trajectory hourly chunk cache", () => {
  it("groups observations once and reuses the same cached chunk payload", () => {
    const source = buildRealTrajectoryChunkSource([
      [3590, "粤A1", "1", 113.2, 23.1],
      [3610, "粤A1", "1", 113.21, 23.11],
    ], { min: 0, max: 86399 }, 3600);

    const before = realTrajectoryChunkAt(source, 3595);
    const after = realTrajectoryChunkAt(source, 3610);

    expect(before.chunk.start).toBe(0);
    expect(after.chunk.start).toBe(3600);
    expect(before.vehicles[0].segments).toHaveLength(1);
    expect(after.vehicles[0].segments.length).toBeGreaterThanOrEqual(1);
    expect(realTrajectoryChunkAt(source, 3599)).toBe(before);
  });

  it("keeps a single observation visible for twenty minutes without creating an all-day chunk", () => {
    const source = buildRealTrajectoryChunkSource([
      [8 * 3600, "粤A2", "2", 113.2, 23.1],
    ], { min: 0, max: 86399 }, 3600);

    expect(realTrajectoryChunkAt(source, 8 * 3600).vehicles).toHaveLength(1);
    expect(realTrajectoryChunkAt(source, 9 * 3600).vehicles).toHaveLength(0);
    expect(source.chunks.size).toBe(1);
  });

  it("drops invalid observations and gaps longer than thirty minutes", () => {
    const source = buildRealTrajectoryChunkSource([
      [0, "", "1", 113.2, 23.1],
      [100, "粤A3", "3", 113.2, 23.1],
      [4000, "粤A3", "3", 113.3, 23.2],
    ], { min: 0, max: 86399 }, 3600);

    expect(source.vehicleCount).toBe(1);
    expect(source.pointCount).toBe(2);
    // Only the final-position hold remains; the 3900-second interpolation is rejected.
    expect(source.segmentCount).toBe(1);
  });
});
