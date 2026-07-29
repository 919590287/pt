/**
 * 换乘分析共享 Worker：transfer-events.bin 解码 + 全部交互聚合。
 *
 * 契约（与后端 MatsimTransferCache 严格一致，勿单独改动）：
 *   bin = magic "TFEV"(4B) + version u16 + count u32，随后列式小端、无对齐填充：
 *   personIndex u32 | tBoard u32 | transferSec u16 | dir u8 | busLine u16 |
 *   busRoute u16 | busStop u16 | busOriginStop u16 | busDestinationStop u16 |
 *   metroLine u16 | metroStop u16 | hub u16
 *   共 27B/事件；dir 0=公交→地铁 1=地铁→公交；busRoute 为线内局部索引。
 *
 * 口径（设计方案 v2 §3）：
 *   - 时段 [start,end) 小时；end=24 视为 +∞（收纳 tBoard≥86400 的跨午夜事件），
 *     分桶索引夹逼到最后一桶，与后端 summary 的 min(hour,23) 一致。
 *   - 人数 = personIndex 去重；人次 = 事件数；全部按模型原始数量展示，不扩样。
 *   - transferSec ≤ 1800，分位数用 1801 槽秒级计数排序精确计算。
 */

/* ---------------- 解码态 ---------------- */

let COUNT = 0;
let personIdx = null; // Uint32Array
let tBoard = null; // Uint32Array（秒）
let transferSec = null; // Uint16Array
let dir = null; // Uint8Array
let busLine = null; // Uint16Array
let busRoute = null; // Uint16Array
let busStop = null; // Uint16Array
let busOriginStop = null; // Uint16Array（整段公交乘车起点）
let busDestinationStop = null; // Uint16Array（整段公交乘车终点）
let metroLine = null; // Uint16Array
let metroStop = null; // Uint16Array
let hub = null; // Uint16Array
let DICT = null;
let personCap = 0; // maxPersonIndex + 1
const resultCache = new Map(); // key -> payload（记忆化，模型切换时清空）
const CACHE_LIMIT = 24;

function decode(buffer) {
  const view = new DataView(buffer);
  if (
    view.getUint8(0) !== 0x54 || // T
    view.getUint8(1) !== 0x46 || // F
    view.getUint8(2) !== 0x45 || // E
    view.getUint8(3) !== 0x56 // V
  ) {
    throw new Error("transfer-events.bin magic 不匹配");
  }
  const version = view.getUint16(4, true);
  if (version !== 3) {
    throw new Error(`transfer-events.bin 版本不支持: ${version}`);
  }
  const count = view.getUint32(6, true);
  const expected = 10 + count * 27;
  if (buffer.byteLength < expected) {
    throw new Error(`transfer-events.bin 长度不足: ${buffer.byteLength} < ${expected}`);
  }
  COUNT = count;
  personIdx = new Uint32Array(count);
  tBoard = new Uint32Array(count);
  transferSec = new Uint16Array(count);
  dir = new Uint8Array(count);
  busLine = new Uint16Array(count);
  busRoute = new Uint16Array(count);
  busStop = new Uint16Array(count);
  busOriginStop = new Uint16Array(count);
  busDestinationStop = new Uint16Array(count);
  metroLine = new Uint16Array(count);
  metroStop = new Uint16Array(count);
  hub = new Uint16Array(count);
  // 列起点（列式连续存放；起始偏移未按 4 字节对齐，必须走 DataView 逐个读）
  let off = 10;
  for (let i = 0; i < count; i++, off += 4) personIdx[i] = view.getUint32(off, true);
  for (let i = 0; i < count; i++, off += 4) tBoard[i] = view.getUint32(off, true);
  for (let i = 0; i < count; i++, off += 2) transferSec[i] = view.getUint16(off, true);
  for (let i = 0; i < count; i++, off += 1) dir[i] = view.getUint8(off);
  for (let i = 0; i < count; i++, off += 2) busLine[i] = view.getUint16(off, true);
  for (let i = 0; i < count; i++, off += 2) busRoute[i] = view.getUint16(off, true);
  for (let i = 0; i < count; i++, off += 2) busStop[i] = view.getUint16(off, true);
  for (let i = 0; i < count; i++, off += 2) busOriginStop[i] = view.getUint16(off, true);
  for (let i = 0; i < count; i++, off += 2) busDestinationStop[i] = view.getUint16(off, true);
  for (let i = 0; i < count; i++, off += 2) metroLine[i] = view.getUint16(off, true);
  for (let i = 0; i < count; i++, off += 2) metroStop[i] = view.getUint16(off, true);
  for (let i = 0; i < count; i++, off += 2) hub[i] = view.getUint16(off, true);
  personCap = 0;
  for (let i = 0; i < count; i++) {
    if (personIdx[i] >= personCap) personCap = personIdx[i] + 1;
  }
}

/* ---------------- 工具 ---------------- */

/** 秒级计数排序精确分位数：hist 长度 1801，total 为样本量 */
function percentileFromSecHist(hist, total, p) {
  if (!total) return 0;
  const rank = Math.max(1, Math.ceil(total * p));
  let acc = 0;
  for (let s = 0; s <= 1800; s++) {
    acc += hist[s];
    if (acc >= rank) return s;
  }
  return 1800;
}

function statsFromSecHist(hist, total, sum) {
  return {
    avgSec: total ? Math.round(sum / total) : 0,
    p50Sec: percentileFromSecHist(hist, total, 0.5),
    p90Sec: percentileFromSecHist(hist, total, 0.9),
  };
}

/** [start,end) 小时过滤；end=24 视为 +∞（跨午夜事件计入全天） */
function makeTimePredicate(startHour, endHour) {
  const s = startHour * 3600;
  if (endHour >= 24) {
    return (t) => t >= s;
  }
  const e = endHour * 3600;
  return (t) => t >= s && t < e;
}

/** 分时桶索引：夹逼到最后一桶（与后端 min(hour,23) 口径一致） */
function makeBucketIndexer(startHour, endHour, unitMin) {
  const s = startHour * 3600;
  const span = (endHour - startHour) * 60;
  const n = Math.max(1, Math.ceil(span / unitMin));
  const w = unitMin * 60;
  return {
    n,
    index(t) {
      const idx = Math.floor((t - s) / w);
      return idx < 0 ? 0 : idx >= n ? n - 1 : idx;
    },
    labels() {
      // 区间标签（分时量是桶计数，起止区间比单点时刻更准确）：如 1h 粒度 "7:00-8:00"、
      // 15min 粒度 "7:15-7:30"；末桶按 endHour 封顶（span 非整除时不虚标）
      const fmt = (m) => `${Math.floor(m / 60)}:${String(m % 60).padStart(2, "0")}`;
      const endM = endHour * 60;
      const arr = [];
      for (let i = 0; i < n; i++) {
        const m = startHour * 60 + i * unitMin;
        arr.push(`${fmt(m)}-${fmt(Math.min(m + unitMin, endM))}`);
      }
      return arr;
    },
  };
}

function topEntries(map, limit, cmp) {
  const arr = Array.from(map.values());
  arr.sort(cmp);
  return limit > 0 ? arr.slice(0, limit) : arr;
}

function cachePut(key, payload) {
  if (resultCache.size >= CACHE_LIMIT) {
    const first = resultCache.keys().next().value;
    resultCache.delete(first);
  }
  resultCache.set(key, payload);
}

/* ---------------- 主聚合 ----------------
 * filters: { dirSel: -1|0|1, startHour, endHour, unitMin, topN,
 *            longMin, hubId: -1|idx, busLineId: -1|idx, routeIdx: -1|n,
 *            metroLineId: -1|idx, busLineIds: [idx]|null, hubIds: [idx]|null }
 * module: overview | hub | feeder | timing
 */

function aggregate(module, f) {
  const inTime = makeTimePredicate(f.startHour, f.endHour);
  const bucket = makeBucketIndexer(f.startHour, f.endHour, f.unitMin);
  const wantDir = f.dirSel;
  const wantHub = f.hubId;
  const wantLine = f.busLineId;
  const wantRoute = f.routeIdx;
  const wantMetroLine = f.metroLineId;
  const wantLineSet = Array.isArray(f.busLineIds) && f.busLineIds.length ? new Set(f.busLineIds) : null;
  const wantHubSet = Array.isArray(f.hubIds) && f.hubIds.length ? new Set(f.hubIds) : null;
  const longSec = f.longMin * 60;

  // 公共累加器
  let total = 0;
  let b2m = 0;
  let sumSec = 0;
  const secHist = new Uint32Array(1801);
  const minHist = new Uint32Array(30);
  const seriesB2M = new Float64Array(bucket.n);
  const seriesM2B = new Float64Array(bucket.n);
  const seriesSumSec = new Float64Array(bucket.n);
  const personBits = new Uint8Array((personCap >> 3) + 1);
  let persons = 0;
  let longCount = 0;
  // 维度累加器（对象聚合，事件量级数万，Map 足够快）
  const byHub = new Map();
  const byPair = new Map();
  const byBusLine = new Map();
  const byBusStop = new Map();
  const byMetroLine = new Map();
  const byRoute = new Map();
  const byStopHub = new Map(); // 公交站→枢纽 连线（含方向）
  const byStopMetroStop = new Map(); // 枢纽详情：公交站→地铁站连线
  const byBusTripLink = new Map(); // 枢纽详情：整段公交起点→终点（两个方向统一）

  const hubAcc = (h) => {
    let a = byHub.get(h);
    if (!a) {
      a = { idx: h, flow: 0, b2m: 0, m2b: 0, sumSec: 0, b2mSumSec: 0, m2bSumSec: 0, longCount: 0, secHist: new Uint32Array(1801) };
      byHub.set(h, a);
    }
    return a;
  };

  for (let i = 0; i < COUNT; i++) {
    const t = tBoard[i];
    if (!inTime(t)) continue;
    const d = dir[i];
    if (wantDir >= 0 && d !== wantDir) continue;
    const h = hub[i];
    if (wantHub >= 0 && h !== wantHub) continue;
    if (wantHubSet && !wantHubSet.has(h)) continue;
    const bl = busLine[i];
    if (wantLine >= 0 && bl !== wantLine) continue;
    if (wantLineSet && !wantLineSet.has(bl)) continue;
    if (wantRoute >= 0 && busRoute[i] !== wantRoute) continue;
    const ml = metroLine[i];
    if (wantMetroLine >= 0 && ml !== wantMetroLine) continue;

    const sec = transferSec[i];
    total++;
    if (d === 0) b2m++;
    sumSec += sec;
    secHist[sec]++;
    minHist[Math.min(Math.floor(sec / 60), 29)]++;
    const bi = bucket.index(t);
    if (d === 0) seriesB2M[bi]++;
    else seriesM2B[bi]++;
    seriesSumSec[bi] += sec;
    if (sec > longSec) longCount++;
    const p = personIdx[i];
    const byteI = p >> 3;
    const bit = 1 << (p & 7);
    if (!(personBits[byteI] & bit)) {
      personBits[byteI] |= bit;
      persons++;
    }

    // hub 维度
    const ha = hubAcc(h);
    ha.flow++;
    if (d === 0) ha.b2m++;
    else ha.m2b++;
    ha.sumSec += sec;
    if (d === 0) ha.b2mSumSec += sec;
    else ha.m2bSumSec += sec;
    ha.secHist[sec]++;
    if (sec > longSec) ha.longCount++;

    // 线对维度（公交线×地铁线）
    const pairKey = bl * 65536 + ml;
    let pa = byPair.get(pairKey);
    if (!pa) {
      pa = { busLine: bl, metroLine: ml, flow: 0, sumSec: 0, longCount: 0 };
      byPair.set(pairKey, pa);
    }
    pa.flow++;
    pa.sumSec += sec;
    if (sec > longSec) pa.longCount++;

    // 公交线维度
    let la = byBusLine.get(bl);
    if (!la) {
      la = { idx: bl, flow: 0, b2m: 0, m2b: 0, sumSec: 0 };
      byBusLine.set(bl, la);
    }
    la.flow++;
    if (d === 0) la.b2m++;
    else la.m2b++;
    la.sumSec += sec;

    // 公交站维度
    let sa = byBusStop.get(busStop[i]);
    if (!sa) {
      sa = { idx: busStop[i], flow: 0, b2m: 0, m2b: 0, sumSec: 0 };
      byBusStop.set(busStop[i], sa);
    }
    sa.flow++;
    if (d === 0) sa.b2m++;
    else sa.m2b++;
    sa.sumSec += sec;

    // 地铁线维度
    let ma = byMetroLine.get(ml);
    if (!ma) {
      ma = { idx: ml, flow: 0, b2m: 0, m2b: 0, sumSec: 0, b2mSumSec: 0, m2bSumSec: 0 };
      byMetroLine.set(ml, ma);
    }
    ma.flow++;
    ma.sumSec += sec;
    if (d === 0) {
      ma.b2m++;
      ma.b2mSumSec += sec;
    } else {
      ma.m2b++;
      ma.m2bSumSec += sec;
    }

    // Route 维度（接驳线路模块用；busRoute 为线内局部索引，需带 busLine）
    const rKey = bl * 256 + busRoute[i];
    let ra = byRoute.get(rKey);
    if (!ra) {
      ra = { busLine: bl, routeIdx: busRoute[i], flow: 0, b2m: 0, m2b: 0 };
      byRoute.set(rKey, ra);
    }
    ra.flow++;
    if (d === 0) ra.b2m++;
    else ra.m2b++;

    // 公交站→枢纽 连线（方向拆分）
    const shKey = (busStop[i] * 65536 + h) * 2 + d;
    let fa = byStopHub.get(shKey);
    if (!fa) {
      fa = { busStop: busStop[i], hub: h, dir: d, flow: 0, sumSec: 0, longCount: 0 };
      byStopHub.set(shKey, fa);
    }
    fa.flow++;
    fa.sumSec += sec;
    if (sec > longSec) fa.longCount++;

    // 公交站→地铁站 连线（仅枢纽详情需要，按需累加）
    if (wantHub >= 0) {
      const smKey = busStop[i] * 65536 + metroStop[i];
      let sm = byStopMetroStop.get(smKey);
      if (!sm) {
        sm = { busStop: busStop[i], metroStop: metroStop[i], flow: 0, b2m: 0, m2b: 0, sumSec: 0 };
        byStopMetroStop.set(smKey, sm);
      }
      sm.flow++;
      if (d === 0) sm.b2m++;
      else sm.m2b++;
      sm.sumSec += sec;

      const origin = busOriginStop[i];
      const destination = busDestinationStop[i];
      const tripKey = origin * 65536 + destination;
      let trip = byBusTripLink.get(tripKey);
      if (!trip) {
        trip = { originBusStop: origin, destinationBusStop: destination, flow: 0, b2m: 0, m2b: 0 };
        byBusTripLink.set(tripKey, trip);
      }
      trip.flow++;
      if (d === 0) trip.b2m++;
      else trip.m2b++;
    }
  }

  const kpiStats = statsFromSecHist(secHist, total, sumSec);

  // 5/10/15 分钟内完成比例（衔接模块 KPI）
  let within5 = 0;
  let within10 = 0;
  let within15 = 0;
  for (let s = 0; s <= 1800; s++) {
    if (s <= 300) within5 += secHist[s];
    if (s <= 600) within10 += secHist[s];
    if (s <= 900) within15 += secHist[s];
  }

  const hubFinal = (a) => ({
    idx: a.idx,
    flow: a.flow,
    b2m: a.b2m,
    m2b: a.m2b,
    b2mAvgSec: a.b2m ? Math.round(a.b2mSumSec / a.b2m) : 0,
    m2bAvgSec: a.m2b ? Math.round(a.m2bSumSec / a.m2b) : 0,
    longCount: a.longCount,
    longShare: a.flow ? a.longCount / a.flow : 0,
    ...statsFromSecHist(a.secHist, a.flow, a.sumSec),
  });

  const payload = {
    module,
    filters: f,
    kpi: {
      events: total,
      persons,
      busToMetro: b2m,
      metroToBus: total - b2m,
      longCount,
      longShare: total ? longCount / total : 0,
      within5Share: total ? within5 / total : 0,
      within10Share: total ? within10 / total : 0,
      within15Share: total ? within15 / total : 0,
      ...kpiStats,
    },
    series: {
      labels: bucket.labels(),
      busToMetro: Array.from(seriesB2M),
      metroToBus: Array.from(seriesM2B),
      avgSec: Array.from(seriesB2M, (v, i) => {
        const n = seriesB2M[i] + seriesM2B[i];
        return n ? Math.round(seriesSumSec[i] / n) : 0;
      }),
    },
    histogramMin: Array.from(minHist),
    hubs: topEntries(byHub, 0, (a, b) => b.flow - a.flow).map(hubFinal),
    pairs: topEntries(byPair, Math.max(f.topN, 50), (a, b) => b.flow - a.flow).map((a) => ({
      busLine: a.busLine,
      metroLine: a.metroLine,
      flow: a.flow,
      longCount: a.longCount,
      avgSec: a.flow ? Math.round(a.sumSec / a.flow) : 0,
    })),
    flows: topEntries(byStopHub, 0, (a, b) => b.flow - a.flow)
      .slice(0, Math.min(Math.max(f.topN * 4, 40), 200))
      .map((a) => ({
        busStop: a.busStop,
        hub: a.hub,
        dir: a.dir,
        flow: a.flow,
        longCount: a.longCount,
        avgSec: a.flow ? Math.round(a.sumSec / a.flow) : 0,
      })),
    busStops: topEntries(byBusStop, 0, (a, b) => b.flow - a.flow).map((a) => ({
      idx: a.idx,
      flow: a.flow,
      b2m: a.b2m,
      m2b: a.m2b,
      avgSec: a.flow ? Math.round(a.sumSec / a.flow) : 0,
    })),
    metroLines: topEntries(byMetroLine, 0, (a, b) => b.flow - a.flow).map((a) => ({
      idx: a.idx,
      flow: a.flow,
      b2m: a.b2m,
      m2b: a.m2b,
      avgSec: a.flow ? Math.round(a.sumSec / a.flow) : 0,
      b2mAvgSec: a.b2m ? Math.round(a.b2mSumSec / a.b2m) : 0,
      m2bAvgSec: a.m2b ? Math.round(a.m2bSumSec / a.m2b) : 0,
    })),
  };

  if (module === "hub" && wantHub >= 0) {
    payload.hubDetail = {
      busLines: topEntries(byBusLine, 0, (a, b) => b.flow - a.flow).map((a) => ({
        idx: a.idx,
        flow: a.flow,
        b2m: a.b2m,
        m2b: a.m2b,
        avgSec: a.flow ? Math.round(a.sumSec / a.flow) : 0,
      })),
      metroLines: topEntries(byMetroLine, 0, (a, b) => b.flow - a.flow).map((a) => ({
        idx: a.idx,
        flow: a.flow,
      })),
      // 公交线×地铁线矩阵直接由 pairs 派生（枢纽过滤已生效）
      stopMetroLinks: topEntries(byStopMetroStop, 0, (a, b) => b.flow - a.flow)
        .slice(0, 200)
        .map((a) => ({
          busStop: a.busStop,
          metroStop: a.metroStop,
          flow: a.flow,
          b2m: a.b2m,
          m2b: a.m2b,
          avgSec: a.flow ? Math.round(a.sumSec / a.flow) : 0,
        })),
      busTripLinks: topEntries(byBusTripLink, 0, (a, b) => b.flow - a.flow)
        .slice(0, 200)
        .map((a) => ({
          originBusStop: a.originBusStop,
          destinationBusStop: a.destinationBusStop,
          flow: a.flow,
          b2m: a.b2m,
          m2b: a.m2b,
        })),
    };
  }

  if (module === "feeder" && wantMetroLine >= 0) {
    payload.feederDetail = {
      busLines: topEntries(byBusLine, 0, (a, b) => b.flow - a.flow).map((a) => ({
        idx: a.idx,
        flow: a.flow,
        b2m: a.b2m,
        m2b: a.m2b,
        avgSec: a.flow ? Math.round(a.sumSec / a.flow) : 0,
      })),
    };
  }

  if (module === "timing") {
    // 累计分布曲线（按分钟，0..30）
    const cum = [];
    let acc = 0;
    for (let m = 0; m < 30; m++) {
      acc += minHist[m];
      cum.push(total ? +(acc / total).toFixed(4) : 0);
    }
    // Top 枢纽箱线：标准五数 min/P25/P50/P75/max + P90 叠加点（P90 不入五数）
    const boxHubs = topEntries(byHub, Math.min(f.topN, 12), (a, b) => b.flow - a.flow);
    payload.timingDetail = {
      cumulative: cum,
      boxplot: boxHubs.map((a) => {
        const hist = a.secHist;
        const n = a.flow;
        let minV = 0;
        for (let s = 0; s <= 1800; s++) {
          if (hist[s]) {
            minV = s;
            break;
          }
        }
        let maxV = 0;
        for (let s = 1800; s >= 0; s--) {
          if (hist[s]) {
            maxV = s;
            break;
          }
        }
        return {
          idx: a.idx,
          five: [minV, percentileFromSecHist(hist, n, 0.25), percentileFromSecHist(hist, n, 0.5), percentileFromSecHist(hist, n, 0.75), maxV],
          p90: percentileFromSecHist(hist, n, 0.9),
        };
      }),
      longPairs: topEntries(byPair, Math.max(f.topN, 20), (a, b) => b.longCount - a.longCount)
        .filter((a) => a.longCount > 0)
        .map((a) => ({
          busLine: a.busLine,
          metroLine: a.metroLine,
          longCount: a.longCount,
          flow: a.flow,
        })),
    };
  }

  return payload;
}

/* ---------------- 全局索引（init 时一次算好，供下拉排序） ---------------- */

function buildGlobalIndex() {
  const hubFlow = new Map();
  const lineFlow = new Map();
  const metroLineFlow = new Map();
  for (let i = 0; i < COUNT; i++) {
    hubFlow.set(hub[i], (hubFlow.get(hub[i]) || 0) + 1);
    lineFlow.set(busLine[i], (lineFlow.get(busLine[i]) || 0) + 1);
    metroLineFlow.set(metroLine[i], (metroLineFlow.get(metroLine[i]) || 0) + 1);
  }
  const byFlowDesc = (a, b) => b.flow - a.flow;
  const hubsSorted = Array.from(hubFlow.entries())
    .map(([idx, flow]) => ({ idx, flow }))
    .sort(byFlowDesc);
  const linesSorted = Array.from(lineFlow.entries())
    .map(([idx, flow]) => ({ idx, flow }))
    .sort(byFlowDesc);
  const metroLinesSorted = Array.from(metroLineFlow.entries())
    .map(([idx, flow]) => ({ idx, flow }))
    .sort(byFlowDesc);
  return { hubsSorted, linesSorted, metroLinesSorted };
}

/* ---------------- 消息协议 ---------------- */

// Node（vitest）环境下无 self，跳过消息绑定；内部函数经文件末尾导出供契约单测
const IS_WORKER_SCOPE = typeof self !== "undefined" && typeof self.postMessage === "function";

const handleMessage = (ev) => {
  const msg = ev.data || {};
  try {
    if (msg.type === "init") {
      resultCache.clear();
      decode(msg.buffer);
      DICT = msg.dict || null;
      const idx = buildGlobalIndex();
      self.postMessage({ type: "ready", requestId: msg.requestId, count: COUNT, ...idx });
      return;
    }
    if (msg.type === "aggregate") {
      const key = `${msg.module}|${JSON.stringify(msg.filters)}`;
      let payload = resultCache.get(key);
      if (!payload) {
        payload = aggregate(msg.module, msg.filters);
        cachePut(key, payload);
      }
      self.postMessage({ type: "result", requestId: msg.requestId, payload });
      return;
    }
    if (msg.type === "reset") {
      resultCache.clear();
      COUNT = 0;
      personIdx = tBoard = transferSec = dir = null;
      busLine = busRoute = busStop = busOriginStop = busDestinationStop = metroLine = metroStop = hub = null;
      DICT = null;
      self.postMessage({ type: "resetDone", requestId: msg.requestId });
      return;
    }
    throw new Error(`未知消息类型: ${msg.type}`);
  } catch (err) {
    self.postMessage({ type: "error", requestId: msg.requestId, message: String((err && err.message) || err) });
  }
};

if (IS_WORKER_SCOPE) {
  self.onmessage = handleMessage;
}

// 仅供单测使用的内部导出（契约测试：解码布局 / 聚合口径 / 分位数 / 去重）
export { decode as __decode, aggregate as __aggregate, buildGlobalIndex as __buildGlobalIndex };
