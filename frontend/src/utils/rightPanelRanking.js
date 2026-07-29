import { busOperationRatios } from "./busOperationMetrics.js";

export const RIGHT_PANEL_RANK_LIMIT = 10;

/** 客流分析与运行监测共用的未选中线路排名指标。 */
export const LINE_RANK_METRICS = Object.freeze([
  Object.freeze({ key: "flow", label: "线路总客流量", header: "总客流量", unit: "人次", decimals: 0 }),
  Object.freeze({ key: "perVehicleFlow", label: "车均日载客量", header: "车均日载客", unit: "人次/车·日", decimals: 0 }),
  Object.freeze({ key: "perTripFlow", label: "单班次载客量", header: "单班次载客", unit: "人次/班", decimals: 0 }),
  Object.freeze({ key: "strength", label: "客流强度", header: "客流强度", unit: "人次/车公里", decimals: 2 }),
  Object.freeze({ key: "peakLoadRate", label: "平均高峰满载率", header: "平均高峰满载率", unit: "%", decimals: 2 }),
]);

/**
 * 右侧面板中的排名统一只展示 TOP10。
 * 返回新数组，避免截取展示数据时改动完整计算结果或缓存数据。
 */
export function limitRightPanelRanking(rows) {
  return Array.isArray(rows) ? rows.slice(0, RIGHT_PANEL_RANK_LIMIT) : [];
}

function numberOr(value, fallback = 0) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function modeOf(value) {
  const mode = String(value || "").toLowerCase();
  return mode.includes("subway") || mode.includes("metro") || mode.includes("rail")
    ? "subway"
    : "bus";
}

/**
 * 按统一规范公式装配线路运营效率指标。
 */
export function lineRankMetricValues(metrics = {}, passenger = 0) {
  const flow = numberOr(passenger);
  const ratios = busOperationRatios(flow, {
    vehicles: metrics?.vehicles,
    departures: metrics?.departures,
    operatedKm: metrics?.operatingVehicleKm,
  });
  return {
    perVehicleFlow: ratios.perVehicle,
    perTripFlow: ratios.perTrip,
    strength: ratios.intensity,
    peakLoadRate: metrics?.peakAverageLoadRate == null
      ? Number.NaN
      : Number(metrics.peakAverageLoadRate),
  };
}

/**
 * 统一装配未选中线路排行的基础数据。
 *
 * 只接受后端 lineGroups（上下行合并）的规范分子、分母：
 * flow=日上车人次；perVehicleFlow=日上车人次/去重运营车辆；
 * perTripFlow=日上车人次/计划班次；strength=日上车人次/日运营车公里；
 * peakLoadRate=高峰各班次最大站段满载率的班次均值。
 */
export function buildLineRankEntries(panel, wantedMode) {
  if (!panel || typeof panel !== "object") return [];
  const wanted = wantedMode === "subway" ? "subway" : "bus";
  const routes = panel.routes && typeof panel.routes === "object" ? panel.routes : {};
  const groups = panel.lineGroups && typeof panel.lineGroups === "object"
    ? Object.values(panel.lineGroups)
    : [];
  const byName = new Map();

  for (const group of groups) {
    if (modeOf(group?.mode) !== wanted) continue;
    const name = String(group?.lineName || (wanted === "bus" ? group?.lineId : "") || "").trim();
    if (!name) continue;
    const metrics = group?.metrics || {};
    const flow = Number(metrics.passenger);
    if (!(flow > 0)) continue;
    const metricValues = lineRankMetricValues(metrics, flow);
    const firstRouteKey = Array.isArray(group?.routeKeys) ? group.routeKeys[0] : "";
    const entry = {
      name,
      lineName: name,
      lineId: String(group?.lineId || ""),
      desc: String(routes[firstRouteKey]?.desc || group?.desc || ""),
      operator: String(group?.operator || metrics.company || "-"),
      mode: wanted,
      flow,
      ...metricValues,
    };
    // 同名线路在搜索框中只能对应一行；与运行监测既有规则一致，保留客流较大的组。
    if (flow > (byName.get(name)?.flow || 0)) byName.set(name, entry);
  }
  return Array.from(byName.values());
}

export function lineRankValueText(value, decimals = 0) {
  const safe = numberOr(value);
  return decimals > 0 ? safe.toFixed(decimals) : Math.round(safe).toLocaleString();
}
