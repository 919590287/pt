import { describe, expect, it } from "vitest";

import {
  getCachedChunk,
  pruneChunkCache,
  putCachedChunk,
  selectChunkCacheVictims,
} from "./trajectoryChunkCache.js";

describe("trajectoryChunkCache", () => {
  it("quietly degrades when IndexedDB is unavailable", async () => {
    expect(globalThis.indexedDB).toBeUndefined();

    await expect(getCachedChunk("model::0")).resolves.toBeNull();
    await expect(putCachedChunk("model::0", new ArrayBuffer(4), { ds: "model", ver: "v1" })).resolves.toBeUndefined();
    await expect(pruneChunkCache("model", "v1")).resolves.toBeUndefined();
  });

  it("ignores invalid writes without throwing", async () => {
    await expect(putCachedChunk("", new ArrayBuffer(4))).resolves.toBeUndefined();
    await expect(putCachedChunk("model::0", new Uint8Array([1, 2, 3]))).resolves.toBeUndefined();
    await expect(putCachedChunk("model::0", new ArrayBuffer(0))).resolves.toBeUndefined();
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
