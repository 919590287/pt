import { lngLatToWebMercator, webMercatorToLngLat } from "@/mymap/main/MyMap.js";

/** 车型预设（用户友好，无需了解 MATSim 车型细节） */
export const VEHICLE_PRESETS = [
  { key: "mini8", name: "8米小型巴士（约50人）", seats: 20, standing: 30, lengthM: 8 },
  { key: "std10", name: "10米标准巴士（约76人）", seats: 26, standing: 50, lengthM: 10 },
  { key: "std12", name: "12米大巴（约90人）", seats: 30, standing: 60, lengthM: 12 },
  { key: "artic18", name: "18米铰接巴士（约140人）", seats: 40, standing: 100, lengthM: 18 },
];

export function presetToVehicleType(key) {
  const p = VEHICLE_PRESETS.find((v) => v.key === key) || VEHICLE_PRESETS[2];
  return { name: p.name, seats: p.seats, standing: p.standing, lengthM: p.lengthM };
}

export function secondsToHHMM(seconds) {
  if (seconds == null || !Number.isFinite(Number(seconds))) return "";
  const total = Math.round(Number(seconds));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
}

/** 从班次列表推算默认发车时段表（首末班 + 中位间隔） */
export function slotsFromDepartures(departures) {
  const times = (departures || [])
    .map((d) => Number(d.departureTime))
    .filter((t) => Number.isFinite(t))
    .sort((a, b) => a - b);
  if (times.length === 0) {
    return [{ from: "06:30", to: "22:00", headwayMin: 10 }];
  }
  let headway = 10;
  if (times.length > 1) {
    const gaps = [];
    for (let i = 1; i < times.length; i++) gaps.push(times[i] - times[i - 1]);
    gaps.sort((a, b) => a - b);
    headway = Math.max(1, Math.round(gaps[Math.floor(gaps.length / 2)] / 60));
  }
  return [{ from: secondsToHHMM(times[0]), to: secondsToHHMM(times[times.length - 1]), headwayMin: headway }];
}

function hhmmToMinutes(value) {
  const match = /^(\d{1,2}):(\d{2})$/.exec(String(value || ""));
  if (!match) return null;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  if (hour < 0 || hour > 26 || minute < 0 || minute > 59) return null;
  return hour * 60 + minute;
}

/** 发车时段前端即时校验，避免到生成阶段才发现错误。 */
export function validateSlots(slots) {
  if (!Array.isArray(slots) || slots.length === 0) return ["请至少添加一个发车时段"];
  const errors = [];
  const ranges = [];
  slots.forEach((slot, index) => {
    const from = hhmmToMinutes(slot?.from);
    const to = hhmmToMinutes(slot?.to);
    const label = `第 ${index + 1} 个时段`;
    if (from == null || to == null) {
      errors.push(`${label}的起止时间不完整`);
      return;
    }
    if (to <= from) errors.push(`${label}的结束时间必须晚于开始时间`);
    if (!Number.isFinite(Number(slot?.headwayMin)) || Number(slot.headwayMin) <= 0) {
      errors.push(`${label}的发车间隔必须大于 0`);
    }
    ranges.push({ from, to, index });
  });
  ranges.sort((a, b) => a.from - b.from);
  for (let i = 1; i < ranges.length; i += 1) {
    if (ranges[i].from < ranges[i - 1].to) {
      errors.push(`第 ${ranges[i - 1].index + 1} 与第 ${ranges[i].index + 1} 个时段存在重叠`);
    }
  }
  return [...new Set(errors)];
}

function segmentsIntersect(a, b, c, d) {
  const cross = (p, q, r) => (q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0]);
  const abC = cross(a, b, c);
  const abD = cross(a, b, d);
  const cdA = cross(c, d, a);
  const cdB = cross(c, d, b);
  return abC * abD < 0 && cdA * cdB < 0;
}

/** 研究区域的轻量前端拓扑校验。 */
export function validateAreaPolygon(points) {
  if (!Array.isArray(points) || points.length < 3) return ["请至少选择 3 个不同顶点"];
  const unique = new Set(points.map((p) => `${Number(p?.[0]).toFixed(7)},${Number(p?.[1]).toFixed(7)}`));
  if (unique.size < 3) return ["研究区域至少需要 3 个不同顶点"];
  const n = points.length;
  for (let i = 0; i < n; i += 1) {
    const a = points[i];
    const b = points[(i + 1) % n];
    for (let j = i + 1; j < n; j += 1) {
      if (Math.abs(i - j) <= 1 || (i === 0 && j === n - 1)) continue;
      const c = points[j];
      const d = points[(j + 1) % n];
      if (segmentsIntersect(a, b, c, d)) return ["研究区域边界存在交叉，请撤销后重新圈定"];
    }
  }
  return [];
}

/**
 * 沿走向折线（lngLat[]）查找附近站点并按沿线顺序排序。
 * stops: Map<facilityId, {id,name,x,y,lng,lat}>（x/y 为 mercator）
 * 返回 [{id, name, distanceM, order}]
 */
export function stopsAlongPath(geometry, stopIndex, radiusM = 80) {
  if (!geometry || geometry.length < 2) return [];
  const centerLat = geometry[0][1];
  const cos = Math.cos((centerLat * Math.PI) / 180);
  const radiusMerc = radiusM / Math.max(0.2, cos);
  const pts = geometry.map(([lng, lat]) => lngLatToWebMercator(lng, lat));
  // 折线段累计长度（用于排序）
  const cum = [0];
  for (let i = 1; i < pts.length; i++) {
    cum.push(cum[i - 1] + Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]));
  }
  const result = [];
  for (const stop of stopIndex.values()) {
    let best = Infinity;
    let bestOrder = 0;
    for (let i = 1; i < pts.length; i++) {
      const [ax, ay] = pts[i - 1];
      const [bx, by] = pts[i];
      const dx = bx - ax;
      const dy = by - ay;
      const len2 = dx * dx + dy * dy;
      const t = len2 <= 0 ? 0 : Math.max(0, Math.min(1, ((stop.x - ax) * dx + (stop.y - ay) * dy) / len2));
      const px = ax + t * dx;
      const py = ay + t * dy;
      const d = Math.hypot(stop.x - px, stop.y - py);
      if (d < best) {
        best = d;
        bestOrder = cum[i - 1] + t * Math.sqrt(len2);
      }
    }
    if (best <= radiusMerc) {
      result.push({ id: stop.id, name: stop.name, distanceM: Math.round(best * cos), order: bestOrder });
    }
  }
  result.sort((a, b) => a.order - b.order);
  return result;
}

// ==================== 折线投影与切片（调整站点用） ====================

function pathMercator(geometry) {
  const pts = geometry.map(([lng, lat]) => lngLatToWebMercator(lng, lat));
  const cum = [0];
  for (let i = 1; i < pts.length; i++) {
    cum.push(cum[i - 1] + Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]));
  }
  return { pts, cum };
}

/**
 * 点在折线上的投影里程（mercator 距离度量）。
 * coord: [lng,lat]。返回 { measure, distance }。
 */
export function projectMeasureOnPath(geometry, coord) {
  if (!geometry || geometry.length < 2) return null;
  const { pts, cum } = pathMercator(geometry);
  const [px, py] = lngLatToWebMercator(coord[0], coord[1]);
  let best = Infinity;
  let bestMeasure = 0;
  for (let i = 1; i < pts.length; i++) {
    const [ax, ay] = pts[i - 1];
    const [bx, by] = pts[i];
    const dx = bx - ax;
    const dy = by - ay;
    const len2 = dx * dx + dy * dy;
    const t = len2 <= 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
    const qx = ax + t * dx;
    const qy = ay + t * dy;
    const d = Math.hypot(px - qx, py - qy);
    if (d < best) {
      best = d;
      bestMeasure = cum[i - 1] + t * Math.sqrt(len2);
    }
  }
  return { measure: bestMeasure, distance: best };
}

/**
 * 截取折线 [m1, m2] 里程之间的子折线，返回 lngLat 坐标序列（含插值端点）。
 */
export function slicePathBetween(geometry, m1, m2) {
  if (!geometry || geometry.length < 2) return [];
  if (m2 < m1) [m1, m2] = [m2, m1];
  const { pts, cum } = pathMercator(geometry);
  const total = cum[cum.length - 1];
  const from = Math.max(0, Math.min(total, m1));
  const to = Math.max(0, Math.min(total, m2));
  const interp = (m) => {
    for (let i = 1; i < cum.length; i++) {
      if (cum[i] >= m) {
        const seg = cum[i] - cum[i - 1];
        const t = seg <= 0 ? 0 : (m - cum[i - 1]) / seg;
        return [
          pts[i - 1][0] + t * (pts[i][0] - pts[i - 1][0]),
          pts[i - 1][1] + t * (pts[i][1] - pts[i - 1][1]),
        ];
      }
    }
    return pts[pts.length - 1];
  };
  const out = [interp(from)];
  for (let i = 0; i < pts.length; i++) {
    if (cum[i] > from && cum[i] < to) out.push(pts[i]);
  }
  out.push(interp(to));
  return out.map(([x, y]) => webMercatorToLngLat(x, y));
}

/** 修改项类型 -> 展示配置 */
export const KIND_META = {
  "route.add": { label: "新增线路", group: "线路", icon: "＋", tone: "add" },
  "route.replace": { label: "修改线路", group: "线路", icon: "✎", tone: "modify" },
  "route.modify.alignment": { label: "调整走向", group: "线路", icon: "✎", tone: "modify" },
  "route.modify.stops": { label: "调整停靠", group: "线路", icon: "✎", tone: "modify" },
  "route.delete": { label: "删除线路", group: "线路", icon: "✕", tone: "delete" },
  "stop.add": { label: "新增站点", group: "站点", icon: "＋", tone: "add" },
  "stop.move": { label: "修改站点", group: "站点", icon: "✎", tone: "modify" },
  "stop.delete": { label: "删除站点", group: "站点", icon: "✕", tone: "delete" },
  "link.add": { label: "新增路段", group: "路网", icon: "＋", tone: "add" },
  "link.modify": { label: "路段属性", group: "路网", icon: "✎", tone: "modify" },
  "link.delete": { label: "删除路段", group: "路网", icon: "✕", tone: "delete" },
  "ops.headway": { label: "发车间隔", group: "运营", icon: "✎", tone: "modify" },
  "ops.serviceHours": { label: "运营时间", group: "运营", icon: "✎", tone: "modify" },
  "ops.vehicleType": { label: "更换车型", group: "运营", icon: "✎", tone: "modify" },
};

export function editSummary(edit) {
  const p = edit.params || {};
  const t = edit.target || {};
  switch (edit.kind) {
    case "route.add":
    case "route.replace":
      return `${p.name || "线路"} · ${(edit.geometry?.directions?.[0]?.stops || []).length}站 · ${p.bidirectional === false ? "单向" : "双向"}`;
    case "route.modify.alignment":
      return `${edit.name} · 新走向 ${(edit.geometry?.stops || []).length}站`;
    case "route.modify.stops":
      return `${edit.name} · 停靠调整为 ${(p.stops || []).length} 站`;
    case "route.delete":
      return `${edit.name}${t.routeIds?.length ? " · 单方向" : " · 整线"}`;
    case "stop.add":
      return `${p.name || "新站点"}`;
    case "stop.move":
      return `${edit.name}${p.name ? ` → ${p.name}` : ""}`;
    case "stop.delete":
      return `${edit.name}`;
    case "link.add":
      return `${(edit.geometry?.coords || []).length}个顶点 · ${p.bidirectional === false ? "单向" : "双向"} · ${p.lanes || 2}车道`;
    case "link.modify":
      return `${(t.linkIds || []).length}条路段属性`;
    case "link.delete":
      return `${(t.linkIds || []).length}条路段`;
    case "ops.headway":
      return `${edit.name} · ${(p.slots || []).map((s) => `${s.from}-${s.to} ${s.headwayMin}min`).join("；")}`;
    case "ops.serviceHours":
      return `${edit.name} · ${(p.slots || []).map((s) => `${s.from}-${s.to}`).join("；")}`;
    case "ops.vehicleType":
      return `${edit.name} · ${p.vehicleType?.name || p.vehicleType?.ref || "新车型"}`;
    default:
      return edit.name || edit.kind;
  }
}
