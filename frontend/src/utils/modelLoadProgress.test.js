import { describe, expect, it } from "vitest";
import { formatDuration, isModelRuntimeReady, isModelUsable, unifiedModelProgress } from "./modelLoadProgress.js";

describe("unifiedModelProgress", () => {
  it("空模型返回检查态", () => {
    const progress = unifiedModelProgress(null);
    expect(progress.state).toBe("checking");
    expect(progress.percent).toBe(0);
    expect(progress.ready).toBe(false);
  });

  it("加载 + 缓存均就绪 → 100%", () => {
    const progress = unifiedModelProgress({ loadStatus: true, cacheStatus: "ready", loadElapsedSeconds: 42 });
    expect(progress.ready).toBe(true);
    expect(progress.percent).toBe(100);
    expect(progress.etaSeconds).toBe(0);
    expect(progress.elapsedSeconds).toBe(42);
  });

  it("缓存已就绪、模型本体加载中 → 直接采用后端 load* 真实进度", () => {
    const progress = unifiedModelProgress({
      loadStatus: false,
      loadStage: "loading_config",
      cacheStatus: "ready",
      loadProgressPercent: 63,
      loadProgressMessage: "正在加载路网、公交与出行链数据",
      loadElapsedSeconds: 120,
      loadEtaSeconds: 70,
    });
    expect(progress.state).toBe("loading");
    expect(progress.percent).toBe(63);
    expect(progress.message).toContain("路网");
    expect(progress.elapsedSeconds).toBe(120);
    expect(progress.etaSeconds).toBe(70);
  });

  it("缓存生成中 → 采用 cache* 真实进度与预计剩余", () => {
    const progress = unifiedModelProgress({
      loadStatus: true,
      loadStage: "ready",
      cacheStatus: "building",
      cacheProgressPercent: 58,
      cacheProgressMessage: "正在流式解析 events，已处理到 12:30:00，车辆约 2100 台",
      cacheElapsedSeconds: 300,
      cacheEtaSeconds: 220,
    });
    expect(progress.state).toBe("building");
    expect(progress.percent).toBe(58);
    expect(progress.message).toContain("events");
    expect(progress.etaSeconds).toBe(220);
  });

  it("缓存流水线等待模型加载段（≤25%）用 load* 细化到 8–25", () => {
    const progress = unifiedModelProgress({
      loadStatus: false,
      loadStage: "loading_config",
      cacheStatus: "building",
      cacheProgressPercent: 8,
      loadProgressPercent: 50,
      loadProgressMessage: "正在加载路网、公交与出行链数据",
      loadElapsedSeconds: 60,
      loadEtaSeconds: 60,
    });
    expect(progress.percent).toBeGreaterThan(8);
    expect(progress.percent).toBeLessThanOrEqual(25);
    expect(progress.message).toContain("路网");
  });

  it("失败态透出失败原因", () => {
    const progress = unifiedModelProgress({
      loadStatus: false,
      loadStage: "failed",
      loadMessage: "内存不足",
      cacheStatus: "missing",
    });
    expect(progress.failed).toBe(true);
    expect(progress.message).toContain("内存不足");
  });

  it("老后端缺少 load* 字段时兜底且不伪造时间", () => {
    const progress = unifiedModelProgress({
      loadStatus: false,
      loadStage: "loading_config",
      cacheStatus: "ready",
    });
    expect(progress.percent).toBeGreaterThan(0);
    expect(progress.elapsedSeconds).toBe(-1);
    expect(progress.etaSeconds).toBe(-1);
  });
});

describe("isModelUsable / formatDuration", () => {
  it("仅当加载与缓存同时就绪才可用", () => {
    expect(isModelUsable({ loadStatus: true, cacheStatus: "ready" })).toBe(true);
    expect(isModelUsable({ loadStatus: true, cacheStatus: "building" })).toBe(false);
    expect(isModelUsable({ loadStatus: false, cacheStatus: "ready" })).toBe(false);
  });

  it("运行态已加载时不受派生缓存失败影响", () => {
    expect(isModelRuntimeReady({ loadStatus: true, cacheStatus: "failed" })).toBe(true);
    expect(isModelRuntimeReady({ loadStatus: true, cacheStatus: "building" })).toBe(true);
    expect(isModelRuntimeReady({ loadStatus: false, cacheStatus: "ready" })).toBe(false);
  });

  it("时长格式化", () => {
    expect(formatDuration(-1)).toBe("计算中");
    expect(formatDuration(45)).toBe("45 秒");
    expect(formatDuration(150)).toBe("2 分 30 秒");
    expect(formatDuration(3720)).toBe("1 小时 2 分");
  });
});
