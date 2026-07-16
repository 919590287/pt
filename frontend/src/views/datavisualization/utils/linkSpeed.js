// 车辆运行监测：link-speed-matrix.bin 解析（纯函数，Worker 可复用）。
// 二进制契约（与后端 MatsimLinkSpeedCache.encodeMatrix 对齐，小端）：
//   header: magic "PLSP"(4B) + version u16(=1) + linkCount u32 + bucketCount u16 + bucketSeconds u16 = 14B
//   record × linkCount（20B/链路）: x1 i32, y1 i32, x2 i32, y2 i32（EPSG:3857 取整，
//                                   (x1,y1)=fromNode 即行驶起点，链路有向）, nameIdx u16, street u16
//   speeds  u8 × (linkCount×bucketCount)：链路主序；km/h，0=无数据（有数据下限 1，freespeed 封顶）
//   samples u8 × (linkCount×bucketCount)：±1 桶合并窗样本数（clamp 255）
// nameIdx / street 的 0xFFFF 为“无名 / 未命中街道”哨兵；
// 速度为公交运营车辆净行驶口径（扣站点停靠），模型抽样、不扩样。

const HEADER_BYTES = 14;
const RECORD_BYTES = 20;
const MATRIX_MAGIC = "PLSP";
/** nameIdx / street 的哨兵值（与后端 U16_SENTINEL 一致）。 */
export const LINK_SPEED_U16_SENTINEL = 0xffff;

/**
 * 解析 link-speed-matrix.bin。返回列式 TypedArray（调用方负责 markRaw 后入缓存）；
 * speeds/samples 为零拷贝视图（直接落在原 buffer 上）。
 * 契约不符（magic/version/长度）直接抛错——宁可显式失败也不渲染错位数据。
 */
export function parseLinkSpeedMatrix(buffer) {
  if (!(buffer instanceof ArrayBuffer) || buffer.byteLength < HEADER_BYTES) {
    throw new Error("链路车速数据为空或长度不足");
  }
  const view = new DataView(buffer);
  const magic = String.fromCharCode(view.getUint8(0), view.getUint8(1), view.getUint8(2), view.getUint8(3));
  if (magic !== MATRIX_MAGIC) {
    throw new Error(`链路车速 magic 不符: ${magic}`);
  }
  const version = view.getUint16(4, true);
  if (version !== 1) {
    throw new Error(`链路车速版本不支持: ${version}`);
  }
  const count = view.getUint32(6, true);
  const buckets = view.getUint16(10, true);
  const bucketSeconds = view.getUint16(12, true);
  const matrixBytes = count * buckets;
  const expected = HEADER_BYTES + count * RECORD_BYTES + 2 * matrixBytes;
  if (buffer.byteLength < expected) {
    throw new Error(`链路车速长度不足: 期望 ${expected}B 实际 ${buffer.byteLength}B`);
  }
  const x1 = new Int32Array(count);
  const y1 = new Int32Array(count);
  const x2 = new Int32Array(count);
  const y2 = new Int32Array(count);
  const nameIdx = new Uint16Array(count);
  const street = new Uint16Array(count);
  let offset = HEADER_BYTES;
  for (let k = 0; k < count; k++) {
    x1[k] = view.getInt32(offset, true);
    y1[k] = view.getInt32(offset + 4, true);
    x2[k] = view.getInt32(offset + 8, true);
    y2[k] = view.getInt32(offset + 12, true);
    nameIdx[k] = view.getUint16(offset + 16, true);
    street[k] = view.getUint16(offset + 18, true);
    offset += RECORD_BYTES;
  }
  const speeds = new Uint8Array(buffer, offset, matrixBytes);
  const samples = new Uint8Array(buffer, offset + matrixBytes, matrixBytes);
  return { count, buckets, bucketSeconds, x1, y1, x2, y2, nameIdx, street, speeds, samples };
}

/**
 * 播放时钟（仿真秒）→ 时间桶下标（跨日 mod 24h 折回，与后端 bucketOf 口径一致）。
 * 非法时刻返回 0。
 */
export function linkSpeedBucketOf(simSeconds, buckets, bucketSeconds) {
  const daySeconds = buckets * bucketSeconds;
  if (!Number.isFinite(simSeconds) || daySeconds <= 0) return 0;
  const folded = ((simSeconds % daySeconds) + daySeconds) % daySeconds;
  return Math.min(buckets - 1, Math.floor(folded / bucketSeconds));
}

// ===== 主要拥堵路段 TOP 榜（右侧面板，随播放时刻变化）=====
// 拥堵判定为“相对自身自由流”的口径：矩阵速度经后端 freespeed 封顶，深夜/离峰畅通桶
// 会顶到自由流，故“全天最大桶速”即该链路的自由流基准——无需后端下发 freespeed。
// “主要”按**时段累计延误**（Σ样本 ×（实际耗时 − 自由流耗时））排序，不按最低速度：
// 速度升序会被 u8 下限值 1 km/h 的病态穿越（排队进站/路口死锁等仿真伪影）并列霸榜，
// 且 3 班车爬行的小巷会压过 40 班车受阻的干道，与“主要拥堵路段”的语义相悖。

/** 拥堵准入：当前桶速度 ≤ 自由流 × 该比值（降速 ≥30% 才算拥堵，排除天然慢路）。 */
export const CONGEST_SPEED_RATIO_MAX = 0.7;
/** 自由流基准过低（全天都慢/样本失真）的链路不参与拥堵判定。 */
export const CONGEST_MIN_FREEFLOW_KMH = 12;
/** 当前桶合并窗样本数下限（排除单班车偶发慢速把调和均值拖穿）。 */
export const CONGEST_MIN_SAMPLES = 3;
/**
 * 可信速度下限：整链平均低于步行速度视为仿真异常（QSim 排队进站的等待计入行驶时长、
 * 局部死锁等），不是道路运行速度；u8 编码又把所有 ≤1.5 km/h 钳位成同一个“1”，
 * 留着只会让榜单被一簇并列的 1 占满。地图图层仍按原值着色，此下限只作用于榜单。
 */
export const CONGEST_MIN_SPEED_KMH = 3;

/** 球面墨卡托平面距 → 地面米数：比例因子 sech(y/R)（广州纬度 ≈0.92）。 */
const MERCATOR_EARTH_RADIUS_M = 6378137;

function groundLengthM(x1, y1, x2, y2) {
  const planar = Math.hypot(x2 - x1, y2 - y1);
  if (planar <= 0) return 0;
  return planar / Math.cosh((y1 + y2) / 2 / MERCATOR_EARTH_RADIUS_M);
}

/**
 * 每链路自由流基准：全天各桶速度最大值（km/h，u8）。全天无数据的链路为 0。
 * O(count×buckets) 只算一次，调用方经 getModelDerived 按模型缓存。
 */
export function buildLinkSpeedFreeflow(data) {
  const { count, buckets, speeds } = data;
  const freeflow = new Uint8Array(count);
  for (let k = 0; k < count; k++) {
    const base = k * buckets;
    let max = 0;
    for (let b = 0; b < buckets; b++) {
      const v = speeds[base + b];
      if (v > max) max = v;
    }
    freeflow[k] = max;
  }
  return freeflow;
}

/**
 * 当前桶的拥堵“路段组”前 limit 名，按**组累计延误降序**（延误 = Σ样本 ×（长度/速度 −
 * 长度/自由流），长度取端点直线地面距——弯曲链路略低估，仅影响排序权重不改语义）。
 * 有名链路按 路名×街道 合并为一组（同名路跨区不串组），无名链路各自单链成组；
 * 组代表 = 组内速度最低的链路，定位/展示速度均以代表链路为准。
 * 返回 [{ key, nameIdx(-1=无名), street, links[], repLink, speedKmh, freeflowKmh, delaySeconds }]。
 */
export function selectCongestedGroups(data, freeflow, bucket, limit = 10) {
  const { count, buckets, speeds, samples, nameIdx, street, x1, y1, x2, y2 } = data;
  if (!count || bucket < 0 || bucket >= buckets) return [];
  const groups = new Map();
  for (let k = 0; k < count; k++) {
    const cell = k * buckets + bucket;
    const speed = speeds[cell];
    if (speed < CONGEST_MIN_SPEED_KMH) continue; // 0=无数据；1-2=钳位/爬行伪影，一并出局
    const base = freeflow[k];
    if (base < CONGEST_MIN_FREEFLOW_KMH) continue;
    const sampleCount = samples[cell];
    if (sampleCount < CONGEST_MIN_SAMPLES) continue;
    if (speed > base * CONGEST_SPEED_RATIO_MAX) continue;
    // 累计延误（秒）：km/h 口径下 小时 = (L/1000)×(1/v − 1/vf)，×3600 化秒
    const delaySeconds =
      sampleCount * (groundLengthM(x1[k], y1[k], x2[k], y2[k]) / 1000) * (1 / speed - 1 / base) * 3600;
    const named = nameIdx[k] !== LINK_SPEED_U16_SENTINEL;
    const key = named ? `n:${nameIdx[k]}:${street[k]}` : `k:${k}`;
    let group = groups.get(key);
    if (!group) {
      group = {
        key,
        nameIdx: named ? nameIdx[k] : -1,
        street: street[k],
        links: [],
        repLink: k,
        speedKmh: speed,
        freeflowKmh: base,
        delaySeconds: 0,
      };
      groups.set(key, group);
    }
    group.links.push(k);
    group.delaySeconds += delaySeconds;
    if (speed < group.speedKmh) {
      group.speedKmh = speed;
      group.freeflowKmh = base;
      group.repLink = k;
    }
  }
  const list = [...groups.values()];
  // 延误大者为“主要”；同延误看更低速，终键 key 保证跨次调用确定性
  list.sort((a, b) =>
    b.delaySeconds - a.delaySeconds || a.speedKmh - b.speedKmh || (a.key < b.key ? -1 : a.key > b.key ? 1 : 0));
  return list.slice(0, Math.max(0, limit));
}
