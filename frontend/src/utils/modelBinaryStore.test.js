import { afterEach, beforeEach, describe, expect, it } from "vitest";

import {
  clearPersistedModelBinaries,
  configureModelBinaryStoreBackend,
  modelBinaryKey,
  readModelBinary,
  resetModelBinaryStoreBackend,
  selectModelBinaryVictims,
  writeModelBinary,
} from "./modelBinaryStore.js";

function memoryBackend() {
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

describe("modelBinaryStore", () => {
  let backend;

  beforeEach(() => {
    backend = memoryBackend();
    configureModelBinaryStoreBackend(backend);
  });

  afterEach(() => resetModelBinaryStoreBackend());

  it("按模型+类型+版本持久 ArrayBuffer", async () => {
    const source = new Uint8Array([1, 2, 3, 4]).buffer;
    expect(await writeModelBinary("V6", "population-grid", "v11", source)).toBe(true);
    const hit = await readModelBinary("V6", "population-grid", "v11");
    expect([...new Uint8Array(hit)]).toEqual([1, 2, 3, 4]);
    expect(backend.payloads.has(modelBinaryKey("V6", "population-grid", "v11"))).toBe(true);
  });

  it("可压缩数据只持久压缩字节并可无损恢复", async () => {
    const source = new Uint8Array(128 * 1024);
    source.fill(7);
    expect(await writeModelBinary("V6", "tripends-grid", "v3", source.buffer)).toBe(true);
    const key = modelBinaryKey("V6", "tripends-grid", "v3");
    const stored = backend.payloads.get(key);
    expect(stored.buffer.byteLength).toBeLessThan(source.byteLength);
    expect(stored.compression).toBe("gzip");
    expect(backend.metadata.get(key).bytes).toBe(stored.buffer.byteLength);
    const restored = await readModelBinary("V6", "tripends-grid", "v3");
    expect(new Uint8Array(restored)).toEqual(source);
  });

  it("缺少版本时不持久，避免永久命中无法失效的数据", async () => {
    expect(await writeModelBinary("V6", "population-grid", "", new ArrayBuffer(8))).toBe(false);
    expect(await readModelBinary("V6", "population-grid", "")).toBeNull();
    expect(backend.payloads.size).toBe(0);
  });

  it("同模型同类型只保留最新版本", async () => {
    await writeModelBinary("V6", "population-grid", "v10", new ArrayBuffer(8));
    await writeModelBinary("V6", "population-grid", "v11", new ArrayBuffer(9));
    expect(await readModelBinary("V6", "population-grid", "v10")).toBeNull();
    expect((await readModelBinary("V6", "population-grid", "v11")).byteLength).toBe(9);
    expect(backend.metadata.size).toBe(1);
  });

  it("超出容量时按 LRU 淘汰最旧记录", async () => {
    await writeModelBinary("V6", "a", "v1", new ArrayBuffer(6), { maxBytes: 10, maxEntries: 3 });
    await writeModelBinary("V6", "b", "v1", new ArrayBuffer(6), { maxBytes: 10, maxEntries: 3 });
    expect(await readModelBinary("V6", "a", "v1")).toBeNull();
    expect((await readModelBinary("V6", "b", "v1")).byteLength).toBe(6);
  });

  it("并发写入也不会突破总字节上限", async () => {
    await Promise.all([
      writeModelBinary("V6", "a", "v1", new ArrayBuffer(6), { maxBytes: 10, maxEntries: 3 }),
      writeModelBinary("V6", "b", "v1", new ArrayBuffer(6), { maxBytes: 10, maxEntries: 3 }),
    ]);
    expect([...backend.metadata.values()].reduce((sum, row) => sum + row.bytes, 0)).toBeLessThanOrEqual(10);
    expect(backend.metadata.size).toBe(1);
  });

  it("单个工件大于总预算时不写入", async () => {
    expect(await writeModelBinary("V6", "huge", "v1", new ArrayBuffer(11), {
      maxBytes: 10,
      maxEntries: 3,
    })).toBe(false);
    expect(backend.payloads.size).toBe(0);
  });

  it("配额/存储异常时静默降级", async () => {
    configureModelBinaryStoreBackend({
      async get() { throw new Error("quota"); },
      async put() { throw new Error("quota"); },
      async touch() {},
      async remove() {},
      async listMeta() { throw new Error("quota"); },
    });
    expect(await readModelBinary("V6", "population-grid", "v11")).toBeNull();
    expect(await writeModelBinary("V6", "population-grid", "v11", new ArrayBuffer(8))).toBe(false);
  });

  it("可按模型清理，不影响其他模型", async () => {
    await writeModelBinary("V6", "a", "v1", new ArrayBuffer(2));
    await writeModelBinary("other", "a", "v1", new ArrayBuffer(2));
    await clearPersistedModelBinaries("V6");
    expect(await readModelBinary("V6", "a", "v1")).toBeNull();
    expect(await readModelBinary("other", "a", "v1")).not.toBeNull();
  });

  it("可只清理某模型的指定工件类型", async () => {
    await writeModelBinary("V6", "population-grid", "v1", new ArrayBuffer(2));
    await writeModelBinary("V6", "corridor-links", "v1", new ArrayBuffer(2));
    await clearPersistedModelBinaries("V6", "population-grid");
    expect(await readModelBinary("V6", "population-grid", "v1")).toBeNull();
    expect(await readModelBinary("V6", "corridor-links", "v1")).not.toBeNull();
  });

  it("纯 LRU 选择同时满足条数与字节上限", () => {
    const victims = selectModelBinaryVictims([
      { key: "old", bytes: 5, usedAt: 1 },
      { key: "middle", bytes: 5, usedAt: 2 },
      { key: "new", bytes: 5, usedAt: 3 },
    ], { maxBytes: 10, maxEntries: 2 });
    expect(victims).toEqual(["old"]);
  });
});
