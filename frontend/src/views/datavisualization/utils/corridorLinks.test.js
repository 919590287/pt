import { describe, expect, it } from "vitest";
import {
  CORRIDOR_U16_SENTINEL,
  buildFlowPathData,
  buildFlowRoadLabelData,
  parseCorridorLinks,
  selectVisibleRoadLabels,
} from "./corridorLinks.js";
import { mercatorToLngLat } from "./populationGrid.js";

/** 按 PCRD 契约手工编码（小端），与后端 MatsimCorridorCache.encodeLinks 对齐。 */
function encode(segments) {
  const buffer = new ArrayBuffer(10 + segments.length * 26);
  const view = new DataView(buffer);
  view.setUint8(0, "P".charCodeAt(0));
  view.setUint8(1, "C".charCodeAt(0));
  view.setUint8(2, "R".charCodeAt(0));
  view.setUint8(3, "D".charCodeAt(0));
  view.setUint16(4, 2, true);
  view.setUint32(6, segments.length, true);
  segments.forEach((seg, k) => {
    const offset = 10 + k * 26;
    view.setInt32(offset, seg.x1, true);
    view.setInt32(offset + 4, seg.y1, true);
    view.setInt32(offset + 8, seg.x2, true);
    view.setInt32(offset + 12, seg.y2, true);
    view.setUint16(offset + 16, seg.coeff, true);
    view.setUint16(offset + 18, seg.nameIdx ?? CORRIDOR_U16_SENTINEL, true);
    view.setUint16(offset + 20, seg.street ?? CORRIDOR_U16_SENTINEL, true);
    view.setUint32(offset + 22, seg.flow ?? 0, true);
  });
  return buffer;
}

describe("parseCorridorLinks", () => {
  it("逐列回读，负坐标/哨兵列/大流量不串位", () => {
    const parsed = parseCorridorLinks(encode([
      { x1: -12616581, y1: 2644277, x2: 12616632, y2: -2644276, coeff: 18, nameIdx: 7, street: 175, flow: 4000000000 },
      { x1: 0, y1: 0, x2: 1, y2: 1, coeff: 1 },
    ]));
    expect(parsed.count).toBe(2);
    expect([parsed.x1[0], parsed.y1[0], parsed.x2[0], parsed.y2[0]])
      .toEqual([-12616581, 2644277, 12616632, -2644276]);
    expect(parsed.coeff[0]).toBe(18);
    expect([parsed.nameIdx[0], parsed.street[0]]).toEqual([7, 175]);
    expect(parsed.flow[0]).toBe(4000000000);
    expect(parsed.nameIdx[1]).toBe(CORRIDOR_U16_SENTINEL);
    expect(parsed.street[1]).toBe(CORRIDOR_U16_SENTINEL);
    expect(parsed.flow[1]).toBe(0);
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

describe("buildFlowPathData", () => {
  const links = parseCorridorLinks(encode([
    { x1: 100, y1: 200, x2: 300, y2: 400, coeff: 1, flow: 0 }, // 零流量：不进流量带
    { x1: -500, y1: 600, x2: 700, y2: -800, coeff: 2, flow: 250 },
    { x1: 900, y1: 1000, x2: 1100, y2: 1200, coeff: 3, flow: 2000 }, // 超过 refFlow：同幂外推不封顶
  ]));

  it("跳过零流量段，宽度按幂映射（超锚定外推），坐标与墨卡托转换一致", () => {
    const built = buildFlowPathData(links, [0, 1, 2], { refFlow: 1000, maxWidthM: 900, exponent: 1 });
    expect(built.length).toBe(2);
    expect(Array.from(built.startIndices)).toEqual([0, 2, 4]);
    // 第一条正流量段（原下标 1）的两端点
    const [lng1, lat1] = mercatorToLngLat(-500, 600);
    const [lng2, lat2] = mercatorToLngLat(700, -800);
    expect(Array.from(built.positions.slice(0, 4))).toEqual([lng1, lat1, lng2, lat2]);
    // 宽度 per-vertex 重复两次：250/1000×900=225；2000 超锚定 → 同幂外推 1800
    expect(Array.from(built.widths)).toEqual([225, 225, 1800, 1800]);
  });

  it("输入序即输出序（绘制序压顶交给调用方），refFlow 无效时宽度取 0", () => {
    const built = buildFlowPathData(links, [2, 1], { refFlow: 0, maxWidthM: 900, exponent: 0.9 });
    expect(built.length).toBe(2);
    // 下标 2 在前：坐标顺序跟随输入
    expect(built.positions[0]).toBe(mercatorToLngLat(900, 1000)[0]);
    expect(Array.from(built.widths)).toEqual([0, 0, 0, 0]);
  });

  it("全零流量返回空数据", () => {
    const built = buildFlowPathData(links, [0], { refFlow: 1000, maxWidthM: 900 });
    expect(built.length).toBe(0);
    expect(built.positions.length).toBe(0);
    expect(Array.from(built.startIndices)).toEqual([0]);
  });
});

describe("buildFlowRoadLabelData", () => {
  const links = parseCorridorLinks(encode([
    { x1: 0, y1: 0, x2: 100, y2: 0, coeff: 1, nameIdx: 7, flow: 500 },
    { x1: 1000, y1: 0, x2: 1300, y2: 0, coeff: 1, nameIdx: 7, flow: 500 },
    { x1: 0, y1: 1000, x2: 100, y2: 1000, coeff: 1, nameIdx: 8, flow: 700 },
    { x1: 0, y1: 2000, x2: 1000, y2: 2000, coeff: 1, nameIdx: 9, flow: 900 },
  ]));

  it("只为当前范围的上榜道路生成一个标注，并保持榜单顺序", () => {
    const labels = buildFlowRoadLabelData(links, [0, 1, 2], [
      { nameIdx: 8, name: "乙路" },
      { nameIdx: 7, name: "甲路" },
      { nameIdx: 9, name: "范围外道路" },
    ]);

    expect(labels.map((item) => [item.rank, item.nameIdx, item.name])).toEqual([
      [1, 8, "乙路"],
      [2, 7, "甲路"],
    ]);
  });

  it("客流并列时选更长路段的中点，保证名称落在道路上", () => {
    const [label] = buildFlowRoadLabelData(links, [0, 1], [{ nameIdx: 7, name: "甲路" }]);
    expect(label.position).toEqual(mercatorToLngLat(1150, 0));
  });
});

describe("selectVisibleRoadLabels", () => {
  // 测试用 project 直接把 position 当屏幕像素（[x,y] → {x,y}）
  const project = ([x, y]) => ({ x, y });

  it("重叠时低名次让位、互不重叠全保留，返回序按名次", () => {
    const labels = [
      { rank: 2, name: "乙路", position: [10, 0] }, // 与甲路水平相距 10px < 两框半宽和 → 冲突
      { rank: 1, name: "甲路", position: [0, 0] },
      { rank: 3, name: "丙路", position: [500, 0] }, // 远离：保留
    ];
    const kept = selectVisibleRoadLabels(labels, project, { sizePx: 11, pixelOffset: [0, -8], paddingPx: 6 });
    expect(kept.map((item) => item.name)).toEqual(["甲路", "丙路"]);
  });

  it("同点聚簇只留最高名次（洛溪聚簇回归）", () => {
    const cluster = [1, 2, 3, 4, 6].map((rank) => ({ rank, name: `道路${rank}号`, position: [rank * 2, rank * 2] }));
    const kept = selectVisibleRoadLabels(cluster, project, {});
    expect(kept.map((item) => item.rank)).toEqual([1]);
  });

  it("宽度按全角 1em/半角 0.6em 估算：长名占位更宽", () => {
    // 甲路(2 全角=22px 半宽 11+6=17)与 x=40 的丙路不冲突；
    // 换成 4 全角长名（44px 半宽 22+6=28）后 28+17=45 > 40 → 冲突
    const short = [
      { rank: 1, name: "甲路", position: [0, 0] },
      { rank: 2, name: "丙路", position: [40, 0] },
    ];
    expect(selectVisibleRoadLabels(short, project, {}).length).toBe(2);
    const long = [
      { rank: 1, name: "环市东路", position: [0, 0] },
      { rank: 2, name: "丙路", position: [40, 0] },
    ];
    expect(selectVisibleRoadLabels(long, project, {}).map((item) => item.name)).toEqual(["环市东路"]);
  });

  it("投影失效的标签跳过，不占用避让空间", () => {
    const labels = [
      { rank: 1, name: "甲路", position: [0, 0] },
      { rank: 2, name: "乙路", position: [0, 0] },
    ];
    const brokenProject = (p) => (p === labels[0].position ? null : { x: 0, y: 0 });
    expect(selectVisibleRoadLabels(labels, brokenProject, {}).map((item) => item.name)).toEqual(["乙路"]);
    expect(selectVisibleRoadLabels(labels, null, {})).toEqual([]);
  });
});
