import { describe, expect, it } from "vitest";
import {
  LINK_SPEED_U16_SENTINEL,
  buildLinkSpeedFreeflow,
  linkSpeedBucketOf,
  parseLinkSpeedMatrix,
  selectCongestedGroups,
} from "./linkSpeed.js";

/** 按 PLSP 契约手工编码（小端），与后端 MatsimLinkSpeedCache.encodeMatrix 对齐。 */
function encode(links, buckets = 4, bucketSeconds = 900) {
  const count = links.length;
  const buffer = new ArrayBuffer(14 + count * 20 + 2 * count * buckets);
  const view = new DataView(buffer);
  view.setUint8(0, "P".charCodeAt(0));
  view.setUint8(1, "L".charCodeAt(0));
  view.setUint8(2, "S".charCodeAt(0));
  view.setUint8(3, "P".charCodeAt(0));
  view.setUint16(4, 1, true);
  view.setUint32(6, count, true);
  view.setUint16(10, buckets, true);
  view.setUint16(12, bucketSeconds, true);
  links.forEach((link, k) => {
    const offset = 14 + k * 20;
    view.setInt32(offset, link.x1, true);
    view.setInt32(offset + 4, link.y1, true);
    view.setInt32(offset + 8, link.x2, true);
    view.setInt32(offset + 12, link.y2, true);
    view.setUint16(offset + 16, link.nameIdx ?? LINK_SPEED_U16_SENTINEL, true);
    view.setUint16(offset + 18, link.street ?? LINK_SPEED_U16_SENTINEL, true);
  });
  const speedsBase = 14 + count * 20;
  links.forEach((link, k) => {
    (link.speeds || []).forEach((value, b) => view.setUint8(speedsBase + k * buckets + b, value));
  });
  const samplesBase = speedsBase + count * buckets;
  links.forEach((link, k) => {
    (link.samples || []).forEach((value, b) => view.setUint8(samplesBase + k * buckets + b, value));
  });
  return buffer;
}

describe("parseLinkSpeedMatrix", () => {
  it("逐列回读：负坐标/哨兵列/矩阵链路主序不串位", () => {
    const parsed = parseLinkSpeedMatrix(encode([
      { x1: -12616581, y1: 2644277, x2: 12616632, y2: -2644276, nameIdx: 7, street: 175, speeds: [36, 20, 0, 8], samples: [3, 2, 0, 1] },
      { x1: 0, y1: 0, x2: 100, y2: 0, speeds: [0, 0, 55, 0], samples: [0, 0, 9, 0] },
    ]));
    expect(parsed.count).toBe(2);
    expect(parsed.buckets).toBe(4);
    expect(parsed.bucketSeconds).toBe(900);
    expect([parsed.x1[0], parsed.y1[0], parsed.x2[0], parsed.y2[0]])
      .toEqual([-12616581, 2644277, 12616632, -2644276]);
    expect([parsed.nameIdx[0], parsed.street[0]]).toEqual([7, 175]);
    expect(parsed.nameIdx[1]).toBe(LINK_SPEED_U16_SENTINEL);
    expect(parsed.street[1]).toBe(LINK_SPEED_U16_SENTINEL);
    // 矩阵链路主序：link0 桶序、link1 桶序
    expect(Array.from(parsed.speeds.subarray(0, 4))).toEqual([36, 20, 0, 8]);
    expect(Array.from(parsed.speeds.subarray(4, 8))).toEqual([0, 0, 55, 0]);
    expect(Array.from(parsed.samples.subarray(0, 4))).toEqual([3, 2, 0, 1]);
    expect(Array.from(parsed.samples.subarray(4, 8))).toEqual([0, 0, 9, 0]);
  });

  it("契约不符显式抛错", () => {
    expect(() => parseLinkSpeedMatrix(new ArrayBuffer(4))).toThrow("长度不足");
    const bad = encode([{ x1: 0, y1: 0, x2: 1, y2: 1 }]);
    new DataView(bad).setUint8(0, "X".charCodeAt(0));
    expect(() => parseLinkSpeedMatrix(bad)).toThrow("magic");
    const badVersion = encode([{ x1: 0, y1: 0, x2: 1, y2: 1 }]);
    new DataView(badVersion).setUint16(4, 9, true);
    expect(() => parseLinkSpeedMatrix(badVersion)).toThrow("版本");
    const truncated = encode([{ x1: 0, y1: 0, x2: 1, y2: 1 }]).slice(0, 20);
    expect(() => parseLinkSpeedMatrix(truncated)).toThrow("长度不足");
  });

  it("播放时钟折桶：正常/跨日/边界", () => {
    expect(linkSpeedBucketOf(0, 96, 900)).toBe(0);
    expect(linkSpeedBucketOf(28800, 96, 900)).toBe(32); // 08:00
    expect(linkSpeedBucketOf(86399, 96, 900)).toBe(95);
    expect(linkSpeedBucketOf(86400, 96, 900)).toBe(0); // 24:00 → 0:00
    expect(linkSpeedBucketOf(24.5 * 3600, 96, 900)).toBe(2); // 24:30 → 0:30
    expect(linkSpeedBucketOf(Number.NaN, 96, 900)).toBe(0);
  });
});

describe("拥堵路段 TOP 榜", () => {
  it("自由流基线取全天最大桶速，全天无数据为 0", () => {
    const parsed = parseLinkSpeedMatrix(encode([
      { x1: 0, y1: 0, x2: 1, y2: 1, speeds: [30, 8, 45, 0], samples: [2, 3, 2, 0] },
      { x1: 2, y1: 2, x2: 3, y2: 3, speeds: [0, 0, 0, 0], samples: [0, 0, 0, 0] },
    ]));
    expect(Array.from(buildLinkSpeedFreeflow(parsed))).toEqual([45, 0]);
  });

  it("准入过滤：无数据/钳位异常/样本不足/降速不够/自由流过低一律出局", () => {
    const parsed = parseLinkSpeedMatrix(encode([
      // 拥堵：8 ≤ 40×0.7 且样本够
      { x1: 0, y1: 0, x2: 1, y2: 1, nameIdx: 0, street: 0, speeds: [8, 40, 0, 0], samples: [3, 3, 0, 0] },
      // 样本不足（2 < 3）
      { x1: 4, y1: 0, x2: 5, y2: 1, nameIdx: 1, street: 0, speeds: [8, 40, 0, 0], samples: [2, 3, 0, 0] },
      // 降速不够：30 > 40×0.7
      { x1: 8, y1: 0, x2: 9, y2: 1, nameIdx: 2, street: 0, speeds: [30, 40, 0, 0], samples: [4, 3, 0, 0] },
      // 自由流过低：全天最高 10 < 12
      { x1: 12, y1: 0, x2: 13, y2: 1, nameIdx: 3, street: 0, speeds: [5, 10, 0, 0], samples: [4, 3, 0, 0] },
      // 当前桶无数据
      { x1: 16, y1: 0, x2: 17, y2: 1, nameIdx: 4, street: 0, speeds: [0, 40, 0, 0], samples: [0, 3, 0, 0] },
      // u8 钳位/爬行伪影：1、2 km/h 低于可信下限 3
      { x1: 20, y1: 0, x2: 21, y2: 1, nameIdx: 5, street: 0, speeds: [1, 40, 0, 0], samples: [6, 3, 0, 0] },
      { x1: 24, y1: 0, x2: 25, y2: 1, nameIdx: 6, street: 0, speeds: [2, 40, 0, 0], samples: [6, 3, 0, 0] },
    ]));
    const groups = selectCongestedGroups(parsed, buildLinkSpeedFreeflow(parsed), 0);
    expect(groups.map((group) => group.nameIdx)).toEqual([0]);
    expect(groups[0]).toMatchObject({ repLink: 0, speedKmh: 8, freeflowKmh: 40 });
    expect(groups[0].delaySeconds).toBeGreaterThan(0);
  });

  it("同名同街道合并、跨街道/无名不合并；组代表取组内最低速", () => {
    const parsed = parseLinkSpeedMatrix(encode([
      { x1: 0, y1: 0, x2: 1, y2: 1, nameIdx: 5, street: 9, speeds: [12, 40], samples: [3, 3] },
      { x1: 2, y1: 0, x2: 3, y2: 1, nameIdx: 5, street: 9, speeds: [6, 50], samples: [3, 3] },
      { x1: 4, y1: 0, x2: 5, y2: 1, nameIdx: 5, street: 10, speeds: [9, 40], samples: [3, 3] },
      { x1: 6, y1: 0, x2: 7, y2: 1, speeds: [7, 40], samples: [3, 3] }, // 无名单链成组
      { x1: 8, y1: 0, x2: 9, y2: 1, speeds: [7, 40], samples: [3, 3] }, // 无名不与上行合并
    ], 2));
    const groups = selectCongestedGroups(parsed, buildLinkSpeedFreeflow(parsed), 0);
    expect(groups).toHaveLength(4);
    const merged = groups.find((group) => group.nameIdx === 5 && group.street === 9);
    expect(merged.links).toEqual([0, 1]);
    expect(merged).toMatchObject({ repLink: 1, speedKmh: 6, freeflowKmh: 50 });
    expect(groups.filter((group) => group.nameIdx === -1)).toHaveLength(2);
  });

  it("按累计延误降序（班次多的受阻干道压过班次少的爬行小巷）、limit 截断，跨调用结果确定", () => {
    // 赤道附近（y≈0，墨卡托比例因子≈1），长度即坐标差，延误可手算：
    // A：1000m、10 km/h（vf 40）、10 班 → 10×1×(1/10−1/40)×3600 = 2700s
    // B：1000m、 5 km/h（vf 40）、 3 班 →  3×1×(1/5 −1/40)×3600 = 1890s（更慢但影响更小）
    // C： 500m、20 km/h（vf 40）、 3 班 →  3×0.5×(1/20−1/40)×3600 = 135s
    const links = [
      { x1: 0, y1: 0, x2: 1000, y2: 0, nameIdx: 0, street: 0, speeds: [10, 40], samples: [10, 3] },
      { x1: 0, y1: 200, x2: 1000, y2: 200, nameIdx: 1, street: 0, speeds: [5, 40], samples: [3, 3] },
      { x1: 0, y1: 400, x2: 500, y2: 400, nameIdx: 2, street: 0, speeds: [20, 40], samples: [3, 3] },
    ];
    const parsed = parseLinkSpeedMatrix(encode(links, 2));
    const freeflow = buildLinkSpeedFreeflow(parsed);
    const groups = selectCongestedGroups(parsed, freeflow, 0);
    expect(groups.map((group) => group.nameIdx)).toEqual([0, 1, 2]);
    expect(groups.map((group) => Math.round(group.delaySeconds))).toEqual([2700, 1890, 135]);
    // limit 截断 + 跨调用确定性
    expect(selectCongestedGroups(parsed, freeflow, 0, 2).map((group) => group.nameIdx)).toEqual([0, 1]);
    expect(selectCongestedGroups(parsed, freeflow, 0)).toEqual(groups);
    // 越界桶安全返回空
    expect(selectCongestedGroups(parsed, freeflow, 99)).toEqual([]);
  });
});
