import { describe, expect, it } from "vitest";
import { OD_STREET_UNASSIGNED, parseBusOdGrid, quantileBreaks } from "./busOdGrid.js";

/** 按 PGOD 契约手工编码（小端），与后端 MatsimTripEndsCache.encodeOdGrid 对齐。 */
function encodeOd(pairs, mercCellSize = 100) {
  const buffer = new ArrayBuffer(18 + pairs.length * 24);
  const view = new DataView(buffer);
  view.setUint8(0, "P".charCodeAt(0));
  view.setUint8(1, "G".charCodeAt(0));
  view.setUint8(2, "O".charCodeAt(0));
  view.setUint8(3, "D".charCodeAt(0));
  view.setUint16(4, 1, true);
  view.setUint32(6, pairs.length, true);
  view.setFloat64(10, mercCellSize, true);
  pairs.forEach((pair, k) => {
    const offset = 18 + k * 24;
    view.setInt32(offset, pair.iO, true);
    view.setInt32(offset + 4, pair.jO, true);
    view.setInt32(offset + 8, pair.iD, true);
    view.setInt32(offset + 12, pair.jD, true);
    view.setUint32(offset + 16, pair.n, true);
    view.setUint16(offset + 20, pair.oStreet ?? OD_STREET_UNASSIGNED, true);
    view.setUint16(offset + 22, pair.dStreet ?? OD_STREET_UNASSIGNED, true);
  });
  return buffer;
}

describe("parseBusOdGrid", () => {
  it("逐列回读，负格索引与街道哨兵不串位", () => {
    const parsed = parseBusOdGrid(encodeOd([
      { iO: -3, jO: 7, iD: 50, jD: -1, n: 42, oStreet: 12, dStreet: 170 },
      { iO: 0, jO: 0, iD: 0, jD: 0, n: 1 },
    ], 103.5));
    expect(parsed.count).toBe(2);
    expect(parsed.mercCellSize).toBe(103.5);
    expect([parsed.iO[0], parsed.jO[0], parsed.iD[0], parsed.jD[0]]).toEqual([-3, 7, 50, -1]);
    expect(parsed.n[0]).toBe(42);
    expect([parsed.oStreet[0], parsed.dStreet[0]]).toEqual([12, 170]);
    expect(parsed.oStreet[1]).toBe(OD_STREET_UNASSIGNED);
  });

  it("magic / 版本 / 长度不符时显式抛错", () => {
    const ok = encodeOd([{ iO: 0, jO: 0, iD: 1, jD: 1, n: 1 }]);
    const badMagic = ok.slice(0);
    new DataView(badMagic).setUint8(0, "X".charCodeAt(0));
    expect(() => parseBusOdGrid(badMagic)).toThrow(/magic/);
    const badVersion = ok.slice(0);
    new DataView(badVersion).setUint16(4, 9, true);
    expect(() => parseBusOdGrid(badVersion)).toThrow(/版本/);
    expect(() => parseBusOdGrid(ok.slice(0, 20))).toThrow(/长度/);
  });
});

describe("quantileBreaks", () => {
  it("长尾分布产出严格递增断点，重复分位合并", () => {
    // 90 个 1 + 少量大值：低分位全是 1，会合并成一个断点
    const values = [...Array(90).fill(1), 5, 5, 8, 20, 20, 60, 60, 60, 200, 1000];
    const breaks = quantileBreaks(values);
    expect(breaks.length).toBeGreaterThan(1);
    for (let k = 1; k < breaks.length; k++) {
      expect(breaks[k]).toBeGreaterThan(breaks[k - 1]);
    }
    expect(breaks[breaks.length - 1]).toBeLessThanOrEqual(1000);
  });

  it("空集与恒定值退化为无断点（单级）", () => {
    expect(quantileBreaks([])).toEqual([]);
    expect(quantileBreaks([7, 7, 7, 7])).toEqual([]);
  });
});
