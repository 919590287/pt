import { beforeEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({
  getAdminDistricts: vi.fn(),
  getBusLineStation: vi.fn(),
  getRealDataAreaList: vi.fn(),
  getRealDataHistory: vi.fn(),
}));

vi.mock("@/api/realData.js", () => api);

describe("realDataCache", () => {
  let cache;

  beforeEach(async () => {
    vi.resetModules();
    Object.values(api).forEach((fn) => fn.mockReset());
    cache = await import("./realDataCache.js");
  });

  it("deduplicates concurrent latest real-data requests per area", async () => {
    api.getBusLineStation.mockResolvedValue({ data: { routes: [{ id: "r1" }] } });

    const [first, second] = await Promise.all([
      cache.getCachedRealData("广州市"),
      cache.getCachedRealData("广州市"),
    ]);

    expect(first).toEqual({ routes: [{ id: "r1" }] });
    expect(second).toBe(first);
    expect(api.getBusLineStation).toHaveBeenCalledTimes(1);
    expect(api.getBusLineStation).toHaveBeenCalledWith({ areaName: "广州市" }, { silentError: true });
    expect(cache.readCachedRealData("广州市")).toBe(first);
  });

  it("keeps versioned real-data entries separate and invalidates an area prefix", async () => {
    api.getBusLineStation
      .mockResolvedValueOnce({ data: { version: "latest" } })
      .mockResolvedValueOnce({ data: { version: "history-1" } });

    await cache.getCachedRealData("深圳市");
    await cache.getCachedRealData("深圳市", { versionId: "history-1" });

    expect(cache.readCachedRealData("深圳市")).toEqual({ version: "latest" });
    expect(cache.readCachedRealData("深圳市", "history-1")).toEqual({ version: "history-1" });

    cache.invalidateCachedRealData("深圳市");

    expect(cache.readCachedRealData("深圳市")).toBeNull();
    expect(cache.readCachedRealData("深圳市", "history-1")).toBeNull();
  });

  it("falls back to the default area list when the API returns an empty payload", async () => {
    api.getRealDataAreaList.mockResolvedValue({ data: [] });

    await expect(cache.getCachedAreaList()).resolves.toEqual(["广州市"]);
    expect(api.getRealDataAreaList).toHaveBeenCalledWith({ silentError: true });
  });
});
