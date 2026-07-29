import { describe, expect, it, vi } from "vitest";
import { getOrCreateAbortAwareRequest } from "./panelShared.js";

function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}

describe("getOrCreateAbortAwareRequest", () => {
  it("复用未取消请求，但为已取消的同 key 请求重新发起", async () => {
    const pending = new Map();
    const firstController = new AbortController();
    const secondController = new AbortController();
    const first = deferred();
    const second = deferred();
    const firstFactory = vi.fn(() => first.promise);
    const secondFactory = vi.fn(() => second.promise);

    const firstRequest = getOrCreateAbortAwareRequest(
      pending, "route-1", firstController.signal, firstFactory,
    );
    const reusedRequest = getOrCreateAbortAwareRequest(
      pending, "route-1", firstController.signal, firstFactory,
    );
    expect(reusedRequest).toBe(firstRequest);
    await Promise.resolve();
    expect(firstFactory).toHaveBeenCalledTimes(1);

    firstController.abort();
    const freshRequest = getOrCreateAbortAwareRequest(
      pending, "route-1", secondController.signal, secondFactory,
    );
    expect(freshRequest).not.toBe(firstRequest);
    await Promise.resolve();
    expect(secondFactory).toHaveBeenCalledTimes(1);

    first.resolve("old");
    await expect(firstRequest).resolves.toBe("old");
    // 旧 Promise 结束时，新请求仍必须保留在去重表中。
    expect(pending.get("route-1")?.promise).toBe(freshRequest);

    second.resolve("fresh");
    await expect(freshRequest).resolves.toBe("fresh");
    expect(pending.has("route-1")).toBe(false);
  });
});
