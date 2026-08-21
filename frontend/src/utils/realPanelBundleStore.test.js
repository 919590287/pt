import { afterEach, beforeEach, describe, expect, it } from "vitest";

import {
  MAX_PERSISTED_DATES,
  clearPersistedBundles,
  configureBundleStoreBackend,
  panelBundleKey,
  readPanelBundle,
  resetBundleStoreBackend,
  writePanelBundle,
} from "./realPanelBundleStore.js";

/** 内存版后端：vitest 跑在 node 环境没有 indexedDB，这里替掉适配层验证策略本身。 */
function memoryBackend() {
  const rows = new Map();
  return {
    rows,
    async get(key) {
      return rows.get(key) || null;
    },
    async put(record) {
      rows.set(record.key, record);
    },
    async remove(keys) {
      keys.forEach((key) => rows.delete(key));
    },
    async listMeta() {
      return [...rows.values()].map(({ key, area, signature, usedAt }) => ({ key, area, signature, usedAt }));
    },
  };
}

describe("realPanelBundleStore", () => {
  let backend;

  beforeEach(() => {
    backend = memoryBackend();
    configureBundleStoreBackend(backend);
  });

  afterEach(() => {
    resetBundleStoreBackend();
  });

  it("写入后可按区域+指纹+日期读回", async () => {
    await writePanelBundle("广州市", "78b33138", "2026-03-10", { overallFlow: { status: "ready" } });
    const hit = await readPanelBundle("广州市", "78b33138", "2026-03-10");
    expect(hit).toEqual({ overallFlow: { status: "ready" } });
    expect(backend.rows.has(panelBundleKey("广州市", "78b33138", "2026-03-10"))).toBe(true);
  });

  it("指纹变化后旧记录读不到，并在下次写入时清走", async () => {
    await writePanelBundle("广州市", "78b33138", "2026-03-10", { overallFlow: {} });
    expect(await readPanelBundle("广州市", "新指纹", "2026-03-10")).toBeNull();

    await writePanelBundle("广州市", "新指纹", "2026-03-10", { overallFlow: {} });
    const signatures = [...backend.rows.values()].map((row) => row.signature);
    expect(signatures).toEqual(["新指纹"]);
  });

  it("同区域只保留最近使用的若干日期，其他区域不受影响", async () => {
    await writePanelBundle("南沙区", "78b33138", "2026-03-01", { overallFlow: {} });
    for (let index = 0; index < MAX_PERSISTED_DATES + 3; index += 1) {
      const date = `2026-03-${String(index + 10).padStart(2, "0")}`;
      await writePanelBundle("广州市", "78b33138", date, { overallFlow: {} });
    }
    const guangzhou = [...backend.rows.values()].filter((row) => row.area === "广州市");
    expect(guangzhou).toHaveLength(MAX_PERSISTED_DATES);
    // 最早写入的几个日期应当被挤出，最后写入的一定还在。
    expect(guangzhou.some((row) => row.serviceDate === "2026-03-10")).toBe(false);
    expect(guangzhou.some((row) => row.serviceDate === `2026-03-${MAX_PERSISTED_DATES + 12}`)).toBe(true);
    expect(backend.rows.has(panelBundleKey("南沙区", "78b33138", "2026-03-01"))).toBe(true);
  });

  it("命中会续期，避免正在用的日期被后续写入挤掉", async () => {
    await writePanelBundle("广州市", "78b33138", "2026-03-10", { overallFlow: {} });
    const before = backend.rows.get(panelBundleKey("广州市", "78b33138", "2026-03-10")).usedAt;
    await new Promise((resolve) => setTimeout(resolve, 2));
    await readPanelBundle("广州市", "78b33138", "2026-03-10");
    const after = backend.rows.get(panelBundleKey("广州市", "78b33138", "2026-03-10")).usedAt;
    expect(after).toBeGreaterThan(before);
  });

  it("缺少指纹时不读不写，退化为纯内存缓存", async () => {
    expect(await writePanelBundle("广州市", "", "2026-03-10", { overallFlow: {} })).toBe(false);
    expect(await readPanelBundle("广州市", "", "2026-03-10")).toBeNull();
    expect(backend.rows.size).toBe(0);
  });

  it("没有可用存储后端时全部静默降级", async () => {
    configureBundleStoreBackend(null);
    expect(await writePanelBundle("广州市", "78b33138", "2026-03-10", { overallFlow: {} })).toBe(false);
    expect(await readPanelBundle("广州市", "78b33138", "2026-03-10")).toBeNull();
  });

  it("后端抛错不冒泡，调用方可直接回落到网络", async () => {
    configureBundleStoreBackend({
      async get() { throw new Error("quota"); },
      async put() { throw new Error("quota"); },
      async remove() {},
      async listMeta() { return []; },
    });
    expect(await readPanelBundle("广州市", "78b33138", "2026-03-10")).toBeNull();
    expect(await writePanelBundle("广州市", "78b33138", "2026-03-10", { overallFlow: {} })).toBe(false);
  });

  it("clearPersistedBundles 按区域清理", async () => {
    await writePanelBundle("广州市", "78b33138", "2026-03-10", { overallFlow: {} });
    await writePanelBundle("南沙区", "78b33138", "2026-03-10", { overallFlow: {} });
    await clearPersistedBundles("广州市");
    const areas = [...backend.rows.values()].map((row) => row.area);
    expect(areas).toEqual(["南沙区"]);
  });
});
