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
    // 最新数据轻载：routeStops 以 include=core 剔除，另由 ensureCachedRouteStops 懒加载
    expect(api.getBusLineStation).toHaveBeenCalledWith({ areaName: "广州市", include: "core" }, { silentError: true });
    expect(cache.readCachedRealData("广州市")).toBe(first);
  });

  it("hydrates deferred routeStops in place and dedupes concurrent hydrations", async () => {
    api.getBusLineStation
      .mockResolvedValueOnce({ data: { versionId: "v1", routeStops: { deferred: true, features: [] } } })
      .mockResolvedValueOnce({ data: { versionId: "v1", routeStops: { features: [{ id: "s1" }] } } });

    const core = await cache.getCachedRealData("广州市");
    expect(cache.isRouteStopsDeferred(core)).toBe(true);

    const [merged, mergedAgain] = await Promise.all([
      cache.ensureCachedRouteStops("广州市"),
      cache.ensureCachedRouteStops("广州市"),
    ]);

    expect(merged).toBe(core);
    expect(mergedAgain).toBe(core);
    // 一次 core + 一次 routeStops，两次并发水合共用同一在途请求
    expect(api.getBusLineStation).toHaveBeenCalledTimes(2);
    expect(api.getBusLineStation).toHaveBeenLastCalledWith({ areaName: "广州市", include: "routeStops" }, { silentError: true });
    expect(core.routeStops).toEqual({ features: [{ id: "s1" }] });
    expect(cache.isRouteStopsDeferred(core)).toBe(false);
  });

  it("skips routeStops merge when the version changed between the two fetches", async () => {
    api.getBusLineStation
      .mockResolvedValueOnce({ data: { versionId: "v1", routeStops: { deferred: true, features: [] } } })
      .mockResolvedValueOnce({ data: { versionId: "v2", routeStops: { features: [{ id: "s1" }] } } });

    const core = await cache.getCachedRealData("广州市");
    const merged = await cache.ensureCachedRouteStops("广州市");

    expect(merged).toBe(core);
    expect(cache.isRouteStopsDeferred(core)).toBe(true);
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
