import { describe, expect, it, vi } from "vitest";

vi.mock("@/mymap/index.js", () => ({
  Layer: class MockLayer {},
  MAP_EVENT: {},
  webMercatorToLngLat: (x, y) => [x, y],
}));
vi.mock("./deckOverlayRegistry.js", () => ({ removeSharedDeckLayer: () => {} }));
vi.mock("./VehicleModelLayer.js", () => ({ VehicleModelLayer: class MockVehicleModelLayer {} }));
import { buildBinarySecondIndex, frameTransfer, segmentFrameFromBinary } from "./vehicleTrajectory.worker.js";
import {
  VehicleTrajectoryLayer,
  parseVehicleTrajectoryBinaryChunk,
  trajectoryPrefetchBlockCount,
  trajectoryWorkerPayload,
} from "./VehicleTrajectoryLayer.js";

describe("vehicle trajectory viewport sampling", () => {
  it("keeps citywide stats but transfers only exact viewport segments at large scale", () => {
    const total = 200_000;
    const visible = 2_000;
    const segments = new Float32Array(total * 9);
    const segmentInts = new Int32Array(segments.buffer);
    for (let index = 0; index < total; index++) {
      const offset = index * 9;
      const x = index < visible ? index % 100 : 100_000 + index;
      const y = index < visible ? Math.floor(index / 100) : 100_000;
      segments[offset] = 100;
      segments[offset + 1] = 102;
      segments[offset + 2] = x;
      segments[offset + 3] = y;
      segments[offset + 4] = x + 10;
      segments[offset + 5] = y + 2;
      segments[offset + 6] = index % 3;
      segmentInts[offset + 7] = index;
      segments[offset + 8] = Math.hypot(10, 2);
    }
    const data = {
      binary: true,
      origin: [0, 0],
      chunk: { start: 0, end: 299 },
      chunkSeconds: 300,
      segments,
    };
    data.index = buildBinarySecondIndex(data);

    const result = segmentFrameFromBinary(100.5, data, 1, {
      minX: -50,
      minY: -50,
      maxX: 200,
      maxY: 100,
    });

    expect(result.stats.activeTotal).toBe(total);
    expect(result.segmentFrame.count).toBe(visible);
    expect(result.segmentFrame.startXs[1]).toBe(1);
    expect(result.segmentFrame.endXs[1]).toBe(11);
    const transferredBytes = result.transfer.reduce((sum, buffer) => sum + buffer.byteLength, 0);
    const legacyAllCityBytes = total * (7 * 4 + 1 + 4);
    expect(transferredBytes).toBe(visible * (7 * 4 + 1 + 4));
    expect(transferredBytes).toBeLessThan(legacyAllCityBytes / 50);
  });

  it("keeps a segment continuous across a 30-second chunk boundary", () => {
    const segments = new Float32Array(9);
    segments.set([29, 31, 0, 0, 20, 0, 2, 0, 20]);
    new Int32Array(segments.buffer)[7] = 42;
    const previous = {
      binary: true,
      origin: [1000, 2000],
      chunk: { start: 0, end: 29 },
      chunkSeconds: 30,
      segments: new Float32Array(segments),
    };
    const next = {
      binary: true,
      origin: [1000, 2000],
      chunk: { start: 30, end: 59 },
      chunkSeconds: 30,
      segments: new Float32Array(segments),
    };
    previous.index = buildBinarySecondIndex(previous);
    next.index = buildBinarySecondIndex(next);

    const before = segmentFrameFromBinary(29.999, previous, 1);
    const after = segmentFrameFromBinary(30, next, 1);

    expect(before.segmentFrame.count).toBe(1);
    expect(after.segmentFrame.count).toBe(1);
    expect(before.segmentFrame.ids[0]).toBe(42);
    expect(after.segmentFrame.ids[0]).toBe(42);
    expect(after.segmentFrame.startTimes[0]).toBe(29);
    expect(after.segmentFrame.endTimes[0]).toBe(31);
  });

  it("transfers the original binary ArrayBuffer without a full-block clone", () => {
    const buffer = new ArrayBuffer(64 + 9 * Float32Array.BYTES_PER_ELEMENT);
    const header = new DataView(buffer);
    new Uint8Array(buffer, 0, 4).set(["G", "J", "T", "B"].map((value) => value.charCodeAt(0)));
    header.setUint16(4, 2, true);
    header.setUint16(6, 64, true);
    header.setInt32(8, 0, true);
    header.setInt32(12, 29, true);
    header.setInt32(16, 1, true);
    header.setInt32(20, 1, true);
    header.setInt32(24, 2, true);
    header.setInt32(28, 9, true);
    header.setFloat64(32, 1000, true);
    header.setFloat64(40, 2000, true);
    header.setInt32(48, 30, true);
    new Float32Array(buffer, 64).set([0, 10, 0, 0, 10, 0, 2, 0, 10]);
    new Int32Array(buffer, 64)[7] = 7;

    const parsed = parseVehicleTrajectoryBinaryChunk(buffer, { cacheVersion: "trajectory-v10" });
    const { payload, transfer } = trajectoryWorkerPayload(parsed);
    expect(transfer).toEqual([buffer]);

    const delivered = structuredClone(payload, { transfer });
    expect(buffer.byteLength).toBe(0);
    expect(delivered.segments).toBeInstanceOf(Float32Array);
    expect(new Int32Array(
      delivered.segments.buffer,
      delivered.segments.byteOffset,
      delivered.segments.length,
    )[7]).toBe(7);
    expect(delivered.chunkSeconds).toBe(30);
  });

  it("keeps enough 30-second blocks resident for 50x playback", () => {
    expect(trajectoryPrefetchBlockCount({
      chunkSeconds: 30,
      speed: 50,
      ewmaMs: 400,
      highMs: 600,
      chunkBytes: 28 * 1024 * 1024,
      maxBytes: 128 * 1024 * 1024,
    })).toBe(3);

    expect(trajectoryPrefetchBlockCount({
      chunkSeconds: 30,
      speed: 50,
      ewmaMs: 900,
      highMs: 1200,
      chunkBytes: 28 * 1024 * 1024,
      maxBytes: 128 * 1024 * 1024,
    })).toBe(4);
  });

  it("turns a worker LRU miss into a reloadable lightweight-key miss", async () => {
    const layer = Object.create(VehicleTrajectoryLayer.prototype);
    layer.worker = {};
    layer.workerChunkKeys = new Set(["bin:30"]);
    layer.workerDataVersion = 1;
    layer.workerActiveSeq = 0;
    layer.workerTimeSeq = 0;
    layer.workerSamplingSeq = 0;
    layer.currentTime = 30;
    layer.isDisposed = false;
    layer.ensureWorker = () => layer.worker;
    layer.workerSamplingPayload = () => null;
    layer.postWorker = async () => ({ miss: true, key: "bin:30" });

    const result = await layer.activateChunkKey("bin:30", 30);

    expect(result.miss).toBe(true);
    expect(layer.workerChunkKeys.has("bin:30")).toBe(false);
  });

  it("never repeats an ArrayBuffer in vehicle-frame transfer lists", () => {
    const frame = {
      xs: new Float64Array(1),
      ys: new Float64Array(1),
      angles: new Float32Array(1),
      speeds: new Float32Array(1),
      modes: new Uint8Array(1),
      ids: new Int32Array(1),
    };
    const transfer = frameTransfer(frame);
    expect(transfer).toHaveLength(6);
    expect(new Set(transfer).size).toBe(transfer.length);
  });
});
