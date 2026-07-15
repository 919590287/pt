/**
 * 换乘事件表 Worker 契约测试。
 * bin 布局与后端 MatsimTransferCache 严格一致：
 *   header = "TFEV"(4B) + version u16(=1) + count u32，列式小端无填充：
 *   personIndex u32 | tBoard u32 | transferSec u16 | dir u8 | busLine u16 |
 *   busRoute u16 | busStop u16 | metroLine u16 | metroStop u16 | hub u16（23B/事件）
 * 口径断言对应设计方案 v2 §3：30 桶直方图（无 >30min 溢出桶）、1800s 边界入桶 29、
 * end=24 视为 +∞ 收纳跨午夜事件、personIndex 去重、箱线五数为标准 min/P25/P50/P75/max。
 */
import { describe, expect, it } from "vitest";
import { __aggregate, __buildGlobalIndex, __decode } from "./transferData.worker.js";

function buildBin(events) {
  const n = events.length;
  const buf = new ArrayBuffer(10 + n * 23);
  const view = new DataView(buf);
  view.setUint8(0, 0x54); // T
  view.setUint8(1, 0x46); // F
  view.setUint8(2, 0x45); // E
  view.setUint8(3, 0x56); // V
  view.setUint16(4, 1, true);
  view.setUint32(6, n, true);
  let off = 10;
  for (const e of events) view.setUint32((off += 0), e.p, true), (off += 4);
  for (const e of events) view.setUint32(off, e.t, true), (off += 4);
  for (const e of events) view.setUint16(off, e.sec, true), (off += 2);
  for (const e of events) view.setUint8(off, e.dir), (off += 1);
  for (const e of events) view.setUint16(off, e.bl, true), (off += 2);
  for (const e of events) view.setUint16(off, e.br, true), (off += 2);
  for (const e of events) view.setUint16(off, e.bs, true), (off += 2);
  for (const e of events) view.setUint16(off, e.ml, true), (off += 2);
  for (const e of events) view.setUint16(off, e.ms, true), (off += 2);
  for (const e of events) view.setUint16(off, e.hub, true), (off += 2);
  return buf;
}

const BASE_FILTERS = {
  dirSel: -1,
  startHour: 0,
  endHour: 24,
  unitMin: 60,
  topN: 10,
  longMin: 15,
  hubId: -1,
  busLineId: -1,
  routeIdx: -1,
  metroLineId: -1,
  busLineIds: null,
  hubIds: null,
};

function ev(over = {}) {
  return { p: 0, t: 8 * 3600, sec: 300, dir: 0, bl: 0, br: 0, bs: 0, ml: 0, ms: 0, hub: 0, ...over };
}

describe("transferData.worker 契约", () => {
  it("magic 不匹配时报错", () => {
    const buf = buildBin([ev()]);
    new DataView(buf).setUint8(0, 0x58);
    expect(() => __decode(buf)).toThrow(/magic/);
  });

  it("截断的 buffer 报错（长度 = 10 + 23n 校验）", () => {
    const buf = buildBin([ev(), ev()]);
    expect(() => __decode(buf.slice(0, buf.byteLength - 1))).toThrow(/长度不足/);
  });

  it("解码 + 全量聚合：人次/方向/人数去重/均值分位", () => {
    // p0 两次换乘（人次 2、人数 1），p1 一次
    const buf = buildBin([
      ev({ p: 0, t: 7 * 3600, sec: 120, dir: 0 }),
      ev({ p: 0, t: 9 * 3600, sec: 600, dir: 1 }),
      ev({ p: 1, t: 18 * 3600, sec: 300, dir: 0, hub: 1 }),
    ]);
    __decode(buf);
    const r = __aggregate("overview", { ...BASE_FILTERS });
    expect(r.kpi.events).toBe(3);
    expect(r.kpi.persons).toBe(2); // personIndex 去重
    expect(r.kpi.busToMetro).toBe(2);
    expect(r.kpi.metroToBus).toBe(1);
    expect(r.kpi.avgSec).toBe(Math.round((120 + 600 + 300) / 3));
    expect(r.hubs.length).toBe(2);
  });

  it("直方图恒为 30 桶且无 >30min 溢出桶；1800s 边界计入桶 29", () => {
    const buf = buildBin([ev({ sec: 0 }), ev({ sec: 59 }), ev({ sec: 1800 }), ev({ sec: 1799 })]);
    __decode(buf);
    const r = __aggregate("overview", { ...BASE_FILTERS });
    expect(r.histogramMin.length).toBe(30);
    expect(r.histogramMin[0]).toBe(2); // 0s 与 59s
    expect(r.histogramMin[29]).toBe(2); // 1799s 与 1800s 边界值
    expect(r.histogramMin.reduce((a, b) => a + b, 0)).toBe(4); // 无事件落到桶外
  });

  it("时段 [start,end) 口径；end=24 视为 +∞ 收纳跨午夜事件", () => {
    const buf = buildBin([
      ev({ t: 6 * 3600 }), // 恰在 6:00，入 [6,7)
      ev({ t: 7 * 3600 - 1 }),
      ev({ t: 7 * 3600 }), // 恰在 7:00，不入 [6,7)
      ev({ t: 25 * 3600 }), // 跨午夜（MATSim 30h 制）
    ]);
    __decode(buf);
    const sliced = __aggregate("overview", { ...BASE_FILTERS, startHour: 6, endHour: 7 });
    expect(sliced.kpi.events).toBe(2);
    const fullDay = __aggregate("overview", { ...BASE_FILTERS });
    expect(fullDay.kpi.events).toBe(4); // end=24 包含 t≥86400
    // 跨午夜事件夹逼到最后一个分时桶
    expect(fullDay.series.busToMetro[23]).toBe(1);
  });

  it("方向 / 枢纽 / 线路筛选 + 筛选态人数重新去重", () => {
    const buf = buildBin([
      ev({ p: 0, dir: 0, hub: 0, bl: 0 }),
      ev({ p: 0, dir: 1, hub: 1, bl: 1 }),
      ev({ p: 1, dir: 1, hub: 1, bl: 1, sec: 900 }),
    ]);
    __decode(buf);
    const dirOnly = __aggregate("overview", { ...BASE_FILTERS, dirSel: 1 });
    expect(dirOnly.kpi.events).toBe(2);
    expect(dirOnly.kpi.persons).toBe(2); // p0 与 p1 在该方向各出现一次
    const hubOnly = __aggregate("hub", { ...BASE_FILTERS, hubId: 1 });
    expect(hubOnly.kpi.events).toBe(2);
    expect(hubOnly.hubDetail).toBeTruthy();
    expect(hubOnly.hubDetail.busLines[0].idx).toBe(1);
    const lineOnly = __aggregate("feeder", { ...BASE_FILTERS, busLineId: 1 });
    expect(lineOnly.kpi.events).toBe(2);
    expect(lineOnly.feederDetail.metroLines.length).toBe(1);
  });

  it("箱线五数为标准 min/P25/P50/P75/max，P90 单独给出", () => {
    const secs = [60, 120, 180, 240, 300, 360, 420, 480, 540, 600];
    const buf = buildBin(secs.map((sec, i) => ev({ p: i, sec, hub: 0 })));
    __decode(buf);
    const r = __aggregate("timing", { ...BASE_FILTERS });
    const box = r.timingDetail.boxplot[0];
    expect(box.five[0]).toBe(60); // min
    expect(box.five[4]).toBe(600); // max（不是 P90）
    expect(box.five[1]).toBeLessThanOrEqual(box.five[2]);
    expect(box.five[2]).toBeLessThanOrEqual(box.five[3]);
    expect(box.p90).toBe(540); // ceil(10×0.9)=第9个
    expect(box.p90).toBeLessThan(box.five[4]);
  });

  it("长换乘阈值与 5/10/15min 完成比例", () => {
    const buf = buildBin([ev({ sec: 200 }), ev({ p: 1, sec: 700 }), ev({ p: 2, sec: 1000 })]);
    __decode(buf);
    const r = __aggregate("timing", { ...BASE_FILTERS, longMin: 10 });
    expect(r.kpi.longCount).toBe(2); // 700s 与 1000s 超过 600s
    expect(r.kpi.within5Share).toBeCloseTo(1 / 3); // 仅 200s ≤ 300s
    expect(r.kpi.within10Share).toBeCloseTo(1 / 3); // 700s/1000s 均超 600s
    expect(r.kpi.within15Share).toBeCloseTo(2 / 3); // 1000s 超 900s
  });

  it("hubKind=bus：枢纽维度改按公交站分组，口径与地铁枢纽同构", () => {
    // 三个事件落在两个公交站（bs 0/7），但三个不同地铁枢纽（hub 0/1/2）
    const buf = buildBin([
      ev({ p: 0, bs: 7, hub: 0, sec: 120, dir: 0 }),
      ev({ p: 1, bs: 7, hub: 1, sec: 600, dir: 1 }),
      ev({ p: 2, bs: 0, hub: 2, sec: 300, dir: 0 }),
    ]);
    __decode(buf);
    const metro = __aggregate("overview", { ...BASE_FILTERS });
    expect(metro.hubs.length).toBe(3); // 地铁口径：三个枢纽
    const bus = __aggregate("overview", { ...BASE_FILTERS, hubKind: "bus" });
    expect(bus.hubs.length).toBe(2); // 公交口径：两个公交站
    const top = bus.hubs[0];
    expect(top.idx).toBe(7);
    expect(top.flow).toBe(2);
    expect(top.b2m).toBe(1);
    expect(top.m2b).toBe(1);
    expect(top.avgSec).toBe(Math.round((120 + 600) / 2));
    expect(top.p90Sec).toBe(600); // 分位口径与地铁枢纽一致
    // 全网 KPI 不受分组口径影响
    expect(bus.kpi.events).toBe(metro.kpi.events);
    // hubId 过滤作用于公交站分组键
    const one = __aggregate("overview", { ...BASE_FILTERS, hubKind: "bus", hubId: 7 });
    expect(one.kpi.events).toBe(2);
    // 全局索引给出公交站候选（换乘站点分析公交口径下拉），按换乘量降序
    const idx = __buildGlobalIndex();
    expect(idx.busStopsSorted.map((s) => s.idx)).toEqual([7, 0]);
    expect(idx.busStopsSorted[0].flow).toBe(2);
  });

  it("多选过滤：busLineIds / hubIds", () => {
    const buf = buildBin([ev({ bl: 0, hub: 0 }), ev({ p: 1, bl: 1, hub: 1 }), ev({ p: 2, bl: 2, hub: 2 })]);
    __decode(buf);
    const r = __aggregate("overview", { ...BASE_FILTERS, busLineIds: [0, 2] });
    expect(r.kpi.events).toBe(2);
    const r2 = __aggregate("overview", { ...BASE_FILTERS, hubIds: [1] });
    expect(r2.kpi.events).toBe(1);
  });

  it("分时标签为起止区间；末桶按 endHour 封顶不虚标", () => {
    const buf = buildBin([ev()]);
    __decode(buf);
    // 1h 粒度全天：24 桶，首桶 0:00-1:00、桶 7 为 7:00-8:00、末桶 23:00-24:00
    const hourly = __aggregate("overview", BASE_FILTERS);
    expect(hourly.series.labels.length).toBe(24);
    expect(hourly.series.labels[0]).toBe("0:00-1:00");
    expect(hourly.series.labels[7]).toBe("7:00-8:00");
    expect(hourly.series.labels[23]).toBe("23:00-24:00");
    // 15min 粒度:分钟补零；跨度非整除时末桶收在 endHour（7:00-8:30 @45min → 末桶 7:45-8:30）
    const quarter = __aggregate("overview", { ...BASE_FILTERS, startHour: 7, endHour: 9, unitMin: 15 });
    expect(quarter.series.labels[0]).toBe("7:00-7:15");
    expect(quarter.series.labels[1]).toBe("7:15-7:30");
    const ragged = __aggregate("overview", { ...BASE_FILTERS, startHour: 7, endHour: 8, unitMin: 45 });
    expect(ragged.series.labels).toEqual(["7:00-7:45", "7:45-8:00"]);
  });
});
