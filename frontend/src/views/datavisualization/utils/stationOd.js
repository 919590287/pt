const EARTH_RADIUS = 6378137;

function normalizeStationName(value) {
  return String(value || "")
    .trim()
    .replace(/\s+/g, "")
    .toLowerCase();
}

/**
 * 站点客流 OD 的展示口径是“在本站上车 → 下车站”。
 * 真实数据显式提供 out/outbound，仿真数据则以 origin 站名判断。
 */
export function isStationOutboundOdRow(item, stationNames = []) {
  if (!item) return false;
  const selectedNames = (Array.isArray(stationNames) ? stationNames : [stationNames])
    .map(normalizeStationName)
    .filter(Boolean);
  const origin = normalizeStationName(item.origin);
  if (origin && selectedNames.length) return selectedNames.includes(origin);

  const direction = String(item.direction || "").trim().toLowerCase();
  return direction === "out" || direction === "outbound";
}

export function filterStationOutboundOdRows(rows, stationNames = []) {
  return (Array.isArray(rows) ? rows : []).filter((item) =>
    isStationOutboundOdRow(item, stationNames)
  );
}

/**
 * 仿真站点面板使用经纬度；旧版真实站点面板误传 Web Mercator 米制坐标。
 * 在边界统一兼容两种格式，避免有效 OD 因非法经纬度被地图丢弃。
 */
export function stationOdCoordinateToLngLat(x, y) {
  const numberX = Number(x);
  const numberY = Number(y);
  if (!Number.isFinite(numberX) || !Number.isFinite(numberY)) return null;
  if (Math.abs(numberX) <= 180 && Math.abs(numberY) <= 90) {
    return [numberX, numberY];
  }

  const lng = (numberX / EARTH_RADIUS) * (180 / Math.PI);
  const lat = (2 * Math.atan(Math.exp(numberY / EARTH_RADIUS)) - Math.PI / 2) * (180 / Math.PI);
  return Number.isFinite(lng) && Number.isFinite(lat) && Math.abs(lng) <= 180 && Math.abs(lat) <= 90
    ? [lng, lat]
    : null;
}
