import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { reactive, ref, isReactive } from "vue";

vi.mock("@/api/facility.js", () => ({
  getFacilityAll: vi.fn(() => Promise.resolve({ data: [{ facilityName: "A站" }] })),
  getStationPanel: vi.fn(() => Promise.resolve({ data: { status: "ready", rows: [1, 2] } })),
}));
vi.mock("@/api/route.js", () => ({
  getLineAll: vi.fn(() => Promise.resolve({ data: [{ lineName: "1路", routes: [] }] })),
  getRoutePanel: vi.fn(() => Promise.resolve({ data: { status: "ready", routes: [] } })),
}));
vi.mock("@/api/data.js", () => ({
  dataEvaluation: vi.fn(() => Promise.resolve({
    data: { status: "ready", values: { czrkmd: 1 } },
  })),
}));
vi.mock("@/api/population.js", () => ({
  getPopulationSummary: vi.fn(() => Promise.resolve({ data: { status: "ready", cacheVersion: "population-v11" } })),
  getPopulationStreets: vi.fn(() => Promise.resolve({ data: { status: "ready", streets: [] } })),
  getPopulationGridBinary: vi.fn(() => Promise.resolve({ data: new ArrayBuffer(18) })),
}));
vi.mock("@/api/tripEnds.js", () => ({
  getTripEndsSummary: vi.fn(() => Promise.resolve({
    data: { status: "ready", cacheVersion: "trip-v3" },
  })),
  getTripEndsStreets: vi.fn(() => Promise.resolve({ data: { status: "ready", streets: [] } })),
  getTripEndsOdStreets: vi.fn(() => Promise.resolve({ data: { status: "ready", pairs: [] } })),
  getTripEndsGridBinary: vi.fn(() => Promise.resolve({ data: new ArrayBuffer(18) })),
  getTripEndsOdGridBinary: vi.fn(() => Promise.resolve({ data: new ArrayBuffer(18) })),
}));
vi.mock("@/api/corridor.js", () => ({
  getCorridorSummary: vi.fn(() => Promise.resolve({
    data: { status: "ready", cacheVersion: "corridor-v4" },
  })),
  getCorridorNames: vi.fn(() => Promise.resolve({ data: { status: "ready", names: [] } })),
  getCorridorLinksBinary: vi.fn(() => Promise.resolve({ data: new ArrayBuffer(10) })),
}));
vi.mock("@/api/transfer.js", () => ({
  getTransferSummary: vi.fn(() => Promise.resolve({ data: { status: "ready", version: "transfer-v3" } })),
  getTransferDict: vi.fn(() => Promise.resolve({ data: { status: "ready", hubs: [] } })),
  getTransferEventsBinary: vi.fn(() => Promise.resolve({ data: new ArrayBuffer(10) })),
}));

import {
  getCachedEvaluation,
  getCachedLineAll,
  getCachedRoutePanel,
  getCachedStationPanel,
  getCachedPopulationGrid,
  getCachedPopulationStreets,
  getCachedTripEndsGrid,
  getCachedCorridorLinks,
  getModelDerived,
  invalidateModelDerived,
  getModelScopedMap,
  setScopedWithLimit,
  clearModelDataCache,
  invalidateCachedPopulationBundle,
  abortOtherModelDataRequests,
  __modelCacheKeys,
  primeCachedRealPanels,
  warmModelAnalysisBinaries,
  warmModelInteractionCache,
} from "./modelDataCache.js";
import { getLineAll } from "@/api/route.js";
import { dataEvaluation } from "@/api/data.js";
import { getPopulationGridBinary, getPopulationStreets } from "@/api/population.js";
import { getTripEndsGridBinary, getTripEndsOdGridBinary } from "@/api/tripEnds.js";
import { getCorridorLinksBinary } from "@/api/corridor.js";
import { getTransferDict, getTransferEventsBinary, getTransferSummary } from "@/api/transfer.js";
import {
  configureModelBinaryStoreBackend,
  resetModelBinaryStoreBackend,
  writeModelBinary,
} from "./modelBinaryStore.js";

function memoryBinaryBackend() {
  const payloads = new Map();
  const metadata = new Map();
  return {
    payloads,
    metadata,
    async get(key) { return payloads.get(key) || null; },
    async put(record) {
      payloads.set(record.key, {
        buffer: record.buffer.slice(0),
        compression: record.compression,
        rawBytes: record.rawBytes,
      });
      const { buffer: _buffer, ...meta } = record;
      metadata.set(record.key, meta);
      return true;
    },
    async touch(key, usedAt) {
      const row = metadata.get(key);
      if (row) metadata.set(key, { ...row, usedAt });
    },
    async remove(keys) {
      keys.forEach((key) => { payloads.delete(key); metadata.delete(key); });
    },
    async listMeta() { return [...metadata.values()]; },
  };
}

beforeEach(() => {
  configureModelBinaryStoreBackend(memoryBinaryBackend());
  for (const key of __modelCacheKeys()) clearModelDataCache(key);
  vi.clearAllMocks();
});

afterEach(() => resetModelBinaryStoreBackend());

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

  it("真实数据日期面板使用小型 LRU，避免全日期常驻内存", () => {
    for (let day = 1; day <= 32; day += 1) {
      const date = `2026-03-${String(day).padStart(2, "0")}`;
      getModelDerived(`real::广州市::service-date::${date}`, "k", () => ({}));
    }
    const keys = __modelCacheKeys().filter((key) => key.startsWith("real::"));
    expect(keys.length).toBeLessThanOrEqual(6);
    expect(keys).toContain("real::广州市::service-date::2026-03-32");
  });
});

describe("modelDataCache 请求缓存 markRaw", () => {
  it("模型交互预热会提前缓存换乘整包，后续调用不重复下载", async () => {
    await warmModelInteractionCache("m1", {
      includeStationPanel: false,
      includeEvaluation: false,
    });
    await warmModelInteractionCache("m1", {
      includeStationPanel: false,
      includeEvaluation: false,
    });

    expect(getTransferSummary).toHaveBeenCalledTimes(1);
    expect(getTransferDict).toHaveBeenCalledTimes(1);
    expect(getTransferEventsBinary).toHaveBeenCalledTimes(1);
  });

  it("首次加载灌入真实线路与站点面板后直接命中，不再发请求", async () => {
    const model = "real::广州市::service-date::2026-03-10";
    const routePanel = { status: "ready", routes: { r1: {} } };
    const stationPanel = { status: "ready", stations: { s1: {} } };
    const evaluation = { status: "ready", values: { khl: 100 } };
    primeCachedRealPanels(model, { routePanel, stationPanel, evaluation });

    expect(await getCachedRoutePanel(model)).toBe(routePanel);
    expect(await getCachedStationPanel(model)).toBe(stationPanel);
    expect(await getCachedEvaluation(model, "全市")).toBe(evaluation);
    expect(dataEvaluation).not.toHaveBeenCalled();
  });

  it("真实与仿真评估均按数据源和行政区缓存", async () => {
    await getCachedEvaluation("real::广州市", "南沙区");
    await getCachedEvaluation("real::广州市", "南沙区");
    expect(dataEvaluation).toHaveBeenCalledTimes(1);

    await getCachedEvaluation("simulation-model", "南沙区");
    await getCachedEvaluation("simulation-model", "南沙区");
    expect(dataEvaluation).toHaveBeenCalledTimes(2);
  });

  it("getCachedLineAll 结果不可被深代理，且并发去重", async () => {
    const [a, b] = await Promise.all([getCachedLineAll("m1"), getCachedLineAll("m1")]);
    expect(getLineAll).toHaveBeenCalledTimes(1);
    expect(b).toBe(a);
    expect(reactive(a)).toBe(a); // markRaw 生效：reactive() 原样返回
    const holder = ref(a);
    expect(isReactive(holder.value)).toBe(false);
  });

  it("人口 streets/grid 按缓存版本隔离，不复用旧口径", async () => {
    await getCachedPopulationStreets("m1", "population-v10");
    await getCachedPopulationStreets("m1", "population-v10");
    await getCachedPopulationStreets("m1", "population-v11");
    expect(getPopulationStreets).toHaveBeenCalledTimes(2);

    await getCachedPopulationGrid("m1", "population-v10");
    await getCachedPopulationGrid("m1", "population-v10");
    await getCachedPopulationGrid("m1", "population-v11");
    expect(getPopulationGridBinary).toHaveBeenCalledTimes(2);
  });

  it("IndexedDB 命中时直接返回大二进制，刷新后不再请求服务器", async () => {
    const persisted = new Uint8Array([6, 2, 8, 4]).buffer;
    await writeModelBinary("m1", "population-grid", "population-v11", persisted);

    const result = await getCachedPopulationGrid("m1", "population-v11");

    expect([...new Uint8Array(result)]).toEqual([6, 2, 8, 4]);
    expect(getPopulationGridBinary).not.toHaveBeenCalled();
  });

  it("人口缓存契约失效后同时绕过内存与 IndexedDB", async () => {
    await writeModelBinary(
      "m1",
      "population-grid",
      "population-v11",
      new Uint8Array([1, 1]).buffer,
    );
    await getCachedPopulationGrid("m1", "population-v11");
    expect(getPopulationGridBinary).not.toHaveBeenCalled();

    invalidateCachedPopulationBundle("m1");
    const result = await getCachedPopulationGrid("m1", "population-v11");

    expect(result.byteLength).toBe(18);
    expect(getPopulationGridBinary).toHaveBeenCalledTimes(1);
  });

  it("出行网格和走廊二进制缓存按版本隔离", async () => {
    await getCachedTripEndsGrid("m1", "trip-v1");
    await getCachedTripEndsGrid("m1", "trip-v1");
    await getCachedTripEndsGrid("m1", "trip-v2");
    expect(getTripEndsGridBinary).toHaveBeenCalledTimes(2);

    await getCachedCorridorLinks("m1", "corridor-v1");
    await getCachedCorridorLinks("m1", "corridor-v1");
    await getCachedCorridorLinks("m1", "corridor-v2");
    expect(getCorridorLinksBinary).toHaveBeenCalledTimes(2);
  });

  it("人口、出行分布、公交 OD、走廊按四个 idle 批次顺序预热", async () => {
    const waitForIdle = vi.fn(() => Promise.resolve());

    await warmModelAnalysisBinaries("m1", { force: true, waitForIdle });

    expect(waitForIdle).toHaveBeenCalledTimes(4);
    const requestOrder = [
      getPopulationGridBinary.mock.invocationCallOrder[0],
      getTripEndsGridBinary.mock.invocationCallOrder[0],
      getTripEndsOdGridBinary.mock.invocationCallOrder[0],
      getCorridorLinksBinary.mock.invocationCallOrder[0],
    ];
    expect(requestOrder.every(Number.isFinite)).toBe(true);
    expect(requestOrder).toEqual([...requestOrder].sort((left, right) => left - right));
  });

  it("分析预热只落压缩工件，实际读取才展开并进入内存缓存", async () => {
    const backend = memoryBinaryBackend();
    configureModelBinaryStoreBackend(backend);
    const waitForIdle = vi.fn(() => Promise.resolve());

    await warmModelAnalysisBinaries("m1", { force: true, waitForIdle });
    const requestsAfterWarm = getPopulationGridBinary.mock.calls.length;
    expect(backend.metadata.size).toBe(4);

    const result = await getCachedPopulationGrid("m1", "population-v11");
    expect(result).toBeInstanceOf(ArrayBuffer);
    expect(result.byteLength).toBe(18);
    expect(getPopulationGridBinary).toHaveBeenCalledTimes(requestsAfterWarm);
  });

  it("旧请求结束不会移除清缓存后启动的新请求控制器", async () => {
    let resolveOld;
    let resolveFresh;
    let freshSignal;
    getLineAll
      .mockImplementationOnce(() => new Promise((resolve) => { resolveOld = resolve; }))
      .mockImplementationOnce((data, config) => {
        freshSignal = config.signal;
        return new Promise((resolve) => { resolveFresh = resolve; });
      });

    const oldRequest = getCachedLineAll("m1");
    clearModelDataCache("m1");
    const freshRequest = getCachedLineAll("m1");
    resolveOld({ data: [{ lineName: "旧数据" }] });
    await oldRequest;

    abortOtherModelDataRequests("another-model");
    expect(freshSignal.aborted).toBe(true);
    resolveFresh({ data: [{ lineName: "新数据" }] });
    await freshRequest;
  });
});
