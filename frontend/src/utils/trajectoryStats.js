import { isVehicleModeVisible } from "./vehicleVisibility.js";

const MODES = ["bus", "subway", "car"];

export function buildTrajectoryGlobalStatsIndex(chunks = []) {
  const index = new Map();
  for (const chunk of chunks || []) {
    for (const row of chunk?.globalStats || []) {
      const second = Math.floor(Number(row?.[0]));
      if (!Number.isFinite(second) || row.length < 10) continue;
      index.set(second, row.map((value) => Number(value) || 0));
    }
  }
  return index;
}

export function trajectoryGlobalStatsAt(index, time, visibilityMode = "all") {
  const second = Math.floor(Math.max(0, Number(time) || 0));
  // 真实车辆数据按小时聚合；仿真轨迹仍优先命中逐秒统计。
  const row = index?.get?.(second) || index?.get?.(Math.floor(second / 3600) * 3600);
  if (!row) return null;
  const activeByMode = { bus: 0, subway: 0, car: 0 };
  let activeTotal = 0;
  let speedSum = 0;
  let speedCount = 0;
  for (let mode = 0; mode < MODES.length; mode++) {
    if (!isVehicleModeVisible(MODES[mode], visibilityMode)) continue;
    const count = Math.max(0, row[1 + mode] || 0);
    activeByMode[MODES[mode]] = count;
    activeTotal += count;
    speedSum += row[4 + mode] || 0;
    speedCount += Math.max(0, row[7 + mode] || 0);
  }
  return {
    activeTotal,
    activeByMode,
    avgSpeed: speedCount ? Math.round((speedSum / speedCount) * 10) / 10 : 0,
    routeActive: {},
  };
}

/**
 * Prefer the manifest's citywide sidecar over a rendered chunk/viewport sample.
 * Worker results may arrive out of order after a seek; keeping them only for
 * routeActive prevents stale frames from making the right-panel totals oscillate.
 */
export function trajectoryDisplayStatsAt(index, time, visibilityMode, layerStats) {
  const globalStats = trajectoryGlobalStatsAt(index, time, visibilityMode);
  if (!globalStats) return layerStats;
  return {
    ...globalStats,
    routeActive: layerStats?.routeActive || {},
  };
}
