import { describe, expect, it } from "vitest";
import {
  CELL_AREA_KM2,
  buildDensityLegendItems,
  buildGridColors,
  buildGridPositions,
  densityClassIndex,
  mercatorToLngLat,
  parsePopulationGrid,
} from "./populationGrid.js";

// 按 §3 契约手工构造一份 bin：header(18B) + n×16B 记录，小端
function makeGridBuffer(cells, { mercCellSize = 108.8, version = 1, magic = "PGRD" } = {}) {
  const buffer = new ArrayBuffer(18 + cells.length * 16);
  const view = new DataView(buffer);
  for (let k = 0; k < 4; k++) view.setUint8(k, magic.charCodeAt(k));
  view.setUint16(4, version, true);
  view.setUint32(6, cells.length, true);
  view.setFloat64(10, mercCellSize, true);
  cells.forEach((cell, k) => {
    const base = 18 + k * 16;
    view.setInt32(base, cell.i, true);
    view.setInt32(base + 4, cell.j, true);
    view.setUint32(base + 8, cell.home, true);
    view.setUint32(base + 12, cell.work, true);
  });
  return buffer;
}

describe("parsePopulationGrid", () => {
  it("按契约解析 header 与记录（含负栅格索引）并累计总量", () => {
    const buffer = makeGridBuffer([
      { i: 104000, j: 24000, home: 12, work: 3 },
      { i: -5, j: -7, home: 0, work: 9 },
    ]);
    const grid = parsePopulationGrid(buffer);
    expect(grid.count).toBe(2);
    expect(grid.mercCellSize).toBeCloseTo(108.8, 10);
    expect(Array.from(grid.i)).toEqual([104000, -5]);
    expect(Array.from(grid.j)).toEqual([24000, -7]);
    expect(Array.from(grid.home)).toEqual([12, 0]);
    expect(Array.from(grid.work)).toEqual([3, 9]);
    expect(grid.homeTotal).toBe(12);
    expect(grid.workTotal).toBe(12);
  });

  it("magic / version / 长度不符时显式抛错", () => {
    expect(() => parsePopulationGrid(makeGridBuffer([], { magic: "XXXX" }))).toThrow(/magic/);
    expect(() => parsePopulationGrid(makeGridBuffer([], { version: 2 }))).toThrow(/版本/);
    const truncated = makeGridBuffer([{ i: 0, j: 0, home: 1, work: 1 }]).slice(0, 20);
    expect(() => parsePopulationGrid(truncated)).toThrow(/长度/);
    expect(() => parsePopulationGrid(null)).toThrow();
  });
});

describe("buildGridPositions", () => {
  it("西南角 = (i*cs, j*cs) 的墨卡托反算经纬度", () => {
    const cs = 100;
    const grid = parsePopulationGrid(makeGridBuffer([{ i: 3, j: -2, home: 1, work: 0 }], { mercCellSize: cs }));
    const positions = buildGridPositions(grid);
    const [lng, lat] = mercatorToLngLat(3 * cs, -2 * cs);
    expect(positions[0]).toBeCloseTo(lng, 12);
    expect(positions[1]).toBeCloseTo(lat, 12);
  });

  it("墨卡托反算与正算常识一致（原点/赤道）", () => {
    const [lng0, lat0] = mercatorToLngLat(0, 0);
    expect(lng0).toBeCloseTo(0, 12);
    expect(lat0).toBeCloseTo(0, 12);
    const [lng180] = mercatorToLngLat(20037508.3427892, 0);
    expect(lng180).toBeCloseTo(180, 6);
  });
});

describe("densityClassIndex / buildGridColors", () => {
  const scheme = { breaks: [500, 1500], ramp: ["#eef4e4", "#1a9850", "#d73027"], alphaLow: 100, alpha: 200 };

  it("断点闭区间归级，超出全部断点归最高级", () => {
    expect(densityClassIndex(0, scheme.breaks)).toBe(0);
    expect(densityClassIndex(500, scheme.breaks)).toBe(0);
    expect(densityClassIndex(501, scheme.breaks)).toBe(1);
    expect(densityClassIndex(1500, scheme.breaks)).toBe(1);
    expect(densityClassIndex(99999, scheme.breaks)).toBe(2);
  });

  it("抽样数 ÷cell面积 得密度（不扩样）；0 值 cell 透明、低级用 alphaLow", () => {
    // count 10 → 1000 人/km²（第 1 级）；count 40 → 4000（最高级）
    const counts = Uint32Array.from([0, 10, 40]);
    const colors = buildGridColors(counts, scheme);
    expect(colors[3]).toBe(0); // count=0 不画
    expect([colors[4], colors[5], colors[6], colors[7]]).toEqual([26, 152, 80, 200]);
    expect([colors[8], colors[9], colors[10], colors[11]]).toEqual([215, 48, 39, 200]);
    // 密度检算：确保 CELL_AREA_KM2 口径未被悄悄改动，且不含任何 scale 扩样因子
    expect(10 / CELL_AREA_KM2).toBe(1000);
  });

  it("最低级（≤ 第一断点）使用 alphaLow 弱化显示", () => {
    const counts = Uint32Array.from([1]);
    const colors = buildGridColors(counts, scheme); // 1 人 → 100 人/km² → 第 0 级
    expect([colors[0], colors[1], colors[2], colors[3]]).toEqual([238, 244, 228, 100]);
  });
});

describe("buildDensityLegendItems", () => {
  it("图例区间与分级函数同一套断点，末级开口", () => {
    const items = buildDensityLegendItems([500, 1500], ["#a", "#b", "#c"], (v) => v.toLocaleString("en-US"));
    expect(items).toEqual([
      { color: "#a", label: "0 - 500" },
      { color: "#b", label: "500 - 1,500" },
      { color: "#c", label: "> 1,500" },
    ]);
  });
});
