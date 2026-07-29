import { describe, expect, it } from "vitest";
import {
  buildTrajectoryGlobalStatsIndex,
  trajectoryDisplayStatsAt,
  trajectoryGlobalStatsAt,
} from "./trajectoryStats.js";

describe("trajectory global stats sidecar", () => {
  it("keeps citywide metrics independent from the requested viewport", () => {
    const index = buildTrajectoryGlobalStatsIndex([{
      globalStats: [[100, 20, 5, 80, 600, 150, 2400, 20, 5, 80]],
    }]);

    const beforePan = trajectoryGlobalStatsAt(index, 100.2, "all");
    const afterPan = trajectoryGlobalStatsAt(index, 100.8, "all");
    expect(afterPan).toEqual(beforePan);
    expect(beforePan).toMatchObject({ activeTotal: 105, avgSpeed: 30 });
  });

  it("applies visibility policy without changing the all-city source", () => {
    const index = buildTrajectoryGlobalStatsIndex([{
      globalStats: [[100, 20, 5, 80, 600, 150, 2400, 20, 5, 80]],
    }]);
    expect(trajectoryGlobalStatsAt(index, 100, "public")).toMatchObject({
      activeTotal: 25,
      activeByMode: { bus: 20, subway: 5, car: 0 },
      avgSpeed: 30,
    });
    expect(trajectoryGlobalStatsAt(index, 100, "private")).toMatchObject({
      activeTotal: 80,
      activeByMode: { bus: 0, subway: 0, car: 80 },
    });
    expect(trajectoryGlobalStatsAt(index, 100, ["bus", "car"])).toMatchObject({
      activeTotal: 100,
      activeByMode: { bus: 20, subway: 0, car: 80 },
    });
    expect(trajectoryGlobalStatsAt(index, 100, [])).toMatchObject({
      activeTotal: 0,
      activeByMode: { bus: 0, subway: 0, car: 0 },
      avgSpeed: 0,
    });
  });

  it("keeps a stale rendered-frame result from overwriting citywide panel totals", () => {
    const index = buildTrajectoryGlobalStatsIndex([{
      globalStats: [[3600, 120, 0, 0, 3600, 0, 0, 120, 0, 0]],
    }]);
    const staleLayerStats = {
      activeTotal: 7,
      activeByMode: { bus: 7, subway: 0, car: 0 },
      avgSpeed: 12,
      routeActive: { routeA: 3 },
    };

    expect(trajectoryDisplayStatsAt(index, 3700, "all", staleLayerStats)).toEqual({
      activeTotal: 120,
      activeByMode: { bus: 120, subway: 0, car: 0 },
      avgSpeed: 30,
      routeActive: { routeA: 3 },
    });
  });
});
