import { describe, expect, it } from "vitest";
import { CORRIDOR_U16_SENTINEL, parseCorridorLinks } from "./corridorLinks.js";

/** 按 PCRD 契约手工编码（小端），与后端 MatsimCorridorCache.encodeLinks 对齐。 */
function encode(segments) {
  const buffer = new ArrayBuffer(10 + segments.length * 22);
  const view = new DataView(buffer);
  view.setUint8(0, "P".charCodeAt(0));
  view.setUint8(1, "C".charCodeAt(0));
  view.setUint8(2, "R".charCodeAt(0));
  view.setUint8(3, "D".charCodeAt(0));
  view.setUint16(4, 1, true);
  view.setUint32(6, segments.length, true);
  segments.forEach((seg, k) => {
    const offset = 10 + k * 22;
    view.setInt32(offset, seg.x1, true);
    view.setInt32(offset + 4, seg.y1, true);
    view.setInt32(offset + 8, seg.x2, true);
    view.setInt32(offset + 12, seg.y2, true);
    view.setUint16(offset + 16, seg.coeff, true);
    view.setUint16(offset + 18, seg.nameIdx ?? CORRIDOR_U16_SENTINEL, true);
    view.setUint16(offset + 20, seg.street ?? CORRIDOR_U16_SENTINEL, true);
  });
  return buffer;
}

describe("parseCorridorLinks", () => {
  it("逐列回读，负坐标与哨兵列不串位", () => {
    const parsed = parseCorridorLinks(encode([
      { x1: -12616581, y1: 2644277, x2: 12616632, y2: -2644276, coeff: 18, nameIdx: 7, street: 175 },
      { x1: 0, y1: 0, x2: 1, y2: 1, coeff: 1 },
    ]));
    expect(parsed.count).toBe(2);
    expect([parsed.x1[0], parsed.y1[0], parsed.x2[0], parsed.y2[0]])
      .toEqual([-12616581, 2644277, 12616632, -2644276]);
    expect(parsed.coeff[0]).toBe(18);
    expect([parsed.nameIdx[0], parsed.street[0]]).toEqual([7, 175]);
    expect(parsed.nameIdx[1]).toBe(CORRIDOR_U16_SENTINEL);
    expect(parsed.street[1]).toBe(CORRIDOR_U16_SENTINEL);
  });

  it("magic / 版本 / 长度不符时显式抛错", () => {
    const ok = encode([{ x1: 0, y1: 0, x2: 1, y2: 1, coeff: 1 }]);
    const badMagic = ok.slice(0);
    new DataView(badMagic).setUint8(0, "X".charCodeAt(0));
    expect(() => parseCorridorLinks(badMagic)).toThrow(/magic/);
    const badVersion = ok.slice(0);
    new DataView(badVersion).setUint16(4, 9, true);
    expect(() => parseCorridorLinks(badVersion)).toThrow(/版本/);
    expect(() => parseCorridorLinks(ok.slice(0, 16))).toThrow(/长度/);
  });
});
