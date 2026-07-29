import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/utils/request", () => ({ default: vi.fn((config) => config) }));

import request from "@/utils/request";
import {
  dataTrajectoryChunkBinary,
  dataTrajectoryFrameBinary,
  dataTrajectoryViewportBinary,
} from "./trajectory.js";

describe("trajectory binary cache revision", () => {
  beforeEach(() => request.mockClear());

  it("adds cacheGeneration to immutable chunk URLs", () => {
    dataTrajectoryChunkBinary(
      { datasource: "area/public/v6" },
      28_800,
      "generation-42",
      { silentError: true },
    );

    expect(request).toHaveBeenCalledWith(expect.objectContaining({
      method: "GET",
      params: {
        datasource: "area/public/v6",
        start: 28_800,
        rev: "generation-42",
      },
      responseType: "arraybuffer",
    }));
  });

  it("adds the same revision to random-seek frame URLs", () => {
    dataTrajectoryFrameBinary(
      { datasource: "area/public/v6" },
      28_831,
      { bucketSeconds: 2, revision: "generation-42" },
    );

    expect(request).toHaveBeenCalledWith(expect.objectContaining({
      params: expect.objectContaining({
        datasource: "area/public/v6",
        time: 28_831,
        bucketSeconds: 2,
        rev: "generation-42",
      }),
    }));
  });

  it("requests an immutable time-and-space viewport block", () => {
    dataTrajectoryViewportBinary(
      { datasource: "area/public/v6" },
      28_800,
      { minX: 4096, minY: 8192, maxX: 12288, maxY: 16384 },
      "generation-42",
    );

    expect(request).toHaveBeenCalledWith(expect.objectContaining({
      url: "/pt/data/trajectory/viewport.bin",
      method: "GET",
      params: {
        datasource: "area/public/v6",
        start: 28_800,
        visibilityMode: "all",
        minX: 4096,
        minY: 8192,
        maxX: 12288,
        maxY: 16384,
        windowSeconds: 10,
        rev: "generation-42",
      },
      responseType: "arraybuffer",
    }));
  });

  it("keeps the legacy third-argument request config compatible", () => {
    const signal = { legacy: true };
    dataTrajectoryChunkBinary(
      { datasource: "area/public/v6" },
      28_800,
      { signal, silentError: true },
    );

    expect(request).toHaveBeenCalledWith(expect.objectContaining({
      params: {
        datasource: "area/public/v6",
        start: 28_800,
        rev: undefined,
      },
      signal,
      silentError: true,
    }));
  });
});
