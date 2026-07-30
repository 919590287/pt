import { describe, expect, it } from "vitest";

import {
  getCachedChunk,
  pruneChunkCache,
  putCachedChunk,
  selectChunkCacheVictims,
} from "./trajectoryChunkCache.js";

describe("trajectoryChunkCache", () => {
  it("rejects when IndexedDB is unavailable", async () => {
    expect(globalThis.indexedDB).toBeUndefined();

    await expect(getCachedChunk("model::0")).rejects.toThrow("不支持 IndexedDB");
    await expect(putCachedChunk("model::0", new ArrayBuffer(4), { ds: "model", ver: "v1" })).rejects.toThrow("不支持 IndexedDB");
    await expect(pruneChunkCache("model", "v1")).rejects.toThrow("不支持 IndexedDB");
  });

  it("rejects invalid writes", async () => {
    await expect(putCachedChunk("", new ArrayBuffer(4))).rejects.toThrow("缓存键不能为空");
    await expect(putCachedChunk("model::0", new Uint8Array([1, 2, 3]))).rejects.toThrow("非空 ArrayBuffer");
    await expect(putCachedChunk("model::0", new ArrayBuffer(0))).rejects.toThrow("非空 ArrayBuffer");
  });

  it("enforces a global byte LRU across datasources, not only a per-model cap", () => {
    const victims = selectChunkCacheVictims([
      { k: "a-new", ds: "a", bytes: 60, ts: 30 },
      { k: "b-new", ds: "b", bytes: 60, ts: 20 },
      { k: "a-old", ds: "a", bytes: 40, ts: 10 },
    ], {
      maxBytes: 100,
      maxEntries: 10,
      maxTotalBytes: 100,
      maxTotalEntries: 10,
    });

    expect(victims).toEqual(["b-new", "a-old"]);
  });
});
