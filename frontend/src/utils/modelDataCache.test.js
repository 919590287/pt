import { describe, it, expect, vi, beforeEach } from "vitest";
import { reactive, ref, isReactive } from "vue";

vi.mock("@/api/facility.js", () => ({
  getFacilityAll: vi.fn(() => Promise.resolve({ data: [{ facilityName: "A站" }] })),
  getStationPanel: vi.fn(() => Promise.resolve({ data: { status: "ready", rows: [1, 2] } })),
}));
vi.mock("@/api/route.js", () => ({
  getLineAll: vi.fn(() => Promise.resolve({ data: [{ lineName: "1路", routes: [] }] })),
  getRoutePanel: vi.fn(() => Promise.resolve({ data: { status: "ready", routes: [] } })),
}));

import {
  getCachedLineAll,
  getModelDerived,
  invalidateModelDerived,
  getModelScopedMap,
  setScopedWithLimit,
  clearModelDataCache,
  __modelCacheKeys,
} from "./modelDataCache.js";
import { getLineAll } from "@/api/route.js";

beforeEach(() => {
  for (const key of __modelCacheKeys()) clearModelDataCache(key);
  vi.clearAllMocks();
});

describe("modelDataCache 派生缓存", () => {
  it("getModelDerived 同一模型同一键只构建一次", () => {
    const builder = vi.fn(() => ({ index: new Map([["A站", 1]]) }));
    const a = getModelDerived("m1", "facIndex", builder);
    const b = getModelDerived("m1", "facIndex", builder);
    expect(builder).toHaveBeenCalledTimes(1);
    expect(b).toBe(a);
  });

  it("不同模型各自构建；invalidate 后重建", () => {
    const builder = vi.fn(() => ({}));
    getModelDerived("m1", "k", builder);
    getModelDerived("m2", "k", builder);
    expect(builder).toHaveBeenCalledTimes(2);
    invalidateModelDerived("m1", "k");
    getModelDerived("m1", "k", builder);
    expect(builder).toHaveBeenCalledTimes(3);
  });

  it("派生结果 markRaw：放进 reactive/ref 不会被深代理", () => {
    const value = getModelDerived("m1", "big", () => ({ rows: [{ v: 1 }] }));
    expect(reactive(value)).toBe(value);
    const holder = ref(null);
    holder.value = value;
    expect(isReactive(holder.value)).toBe(false);
  });
});

describe("modelDataCache 模型作用域 Map 与 LRU", () => {
  it("getModelScopedMap 跨调用返回同一 Map；setScopedWithLimit 按插入序淘汰", () => {
    const m1 = getModelScopedMap("m1", "routeDetail");
    const m2 = getModelScopedMap("m1", "routeDetail");
    expect(m2).toBe(m1);
    for (let i = 0; i < 5; i++) setScopedWithLimit(m1, `r${i}`, { id: i }, 3);
    expect(m1.size).toBe(3);
    expect(m1.has("r0")).toBe(false);
    expect(m1.has("r4")).toBe(true);
  });

  it("模型数超过上限时按 LRU 淘汰，访问旧模型可重建", () => {
    const builder = vi.fn(() => ({}));
    for (const m of ["m1", "m2", "m3", "m4", "m5"]) getModelDerived(m, "k", builder);
    const keys = __modelCacheKeys();
    expect(keys.length).toBeLessThanOrEqual(4);
    expect(keys).not.toContain("m1");
    getModelDerived("m1", "k", builder); // 重建而非报错
    expect(builder).toHaveBeenCalledTimes(6);
  });

  it("命中访问会刷新 LRU 序", () => {
    const builder = () => ({});
    for (const m of ["m1", "m2", "m3", "m4"]) getModelDerived(m, "k", builder);
    getModelDerived("m1", "k", builder); // touch m1 → 最旧变成 m2
    getModelDerived("m5", "k", builder);
    const keys = __modelCacheKeys();
    expect(keys).toContain("m1");
    expect(keys).not.toContain("m2");
  });
});

describe("modelDataCache 请求缓存 markRaw", () => {
  it("getCachedLineAll 结果不可被深代理，且并发去重", async () => {
    const [a, b] = await Promise.all([getCachedLineAll("m1"), getCachedLineAll("m1")]);
    expect(getLineAll).toHaveBeenCalledTimes(1);
    expect(b).toBe(a);
    expect(reactive(a)).toBe(a); // markRaw 生效：reactive() 原样返回
    const holder = ref(a);
    expect(isReactive(holder.value)).toBe(false);
  });
});
