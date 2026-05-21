import proj4 from "proj4";

export const EARTH_RADIUS = 20037508.3427892;

// const EARTH_RADIUS = 20037508.3427892;
// const row = 1;
// const col = 1;
// const x = (row * (EARTH_RADIUS * 2)) / Math.pow(2, zoom) - EARTH_RADIUS;
// const y = EARTH_RADIUS - ((col * (EARTH_RADIUS * 2)) / Math.pow(2, zoom));
// console.log(x, y);

/**
 * EPSG:4526转Web墨卡托
 * @param {*} lng
 * @param {*} lat
 * @returns
 */
export function EPSG4526ToMercator(lng, lat) {
  return proj4("EPSG:4526", "EPSG:3857", [Number(lng), Number(lat)]).map((v) => Number(Number(v).toFixed(2)));
}

/**
 * EPSG:4526转WGS84
 * @param {*} lng
 * @param {*} lat
 * @returns
 */
export function EPSG4526ToWGS84(lng, lat) {
  return proj4("EPSG:4526", "EPSG:4326", [Number(lng), Number(lat)]).map((v) => Number(Number(v).toFixed(6)));
}

/**
 * WGS84转EPSG:4526
 * @param {*} lng
 * @param {*} lat
 * @returns
 */
export function WGS84ToEPSG4526(lng, lat) {
  return proj4("EPSG:4326", "EPSG:4526", [Number(lng), Number(lat)]).map((v) => Number(Number(v).toFixed(2)));
}

/**
 * Web墨卡托转WGS84
 * @param {*} lng
 * @param {*} lat
 * @returns
 */
export function MercatorToWGS84(lng, lat) {
  return proj4("EPSG:3857", "EPSG:4326", [Number(lng), Number(lat)]).map((v) => Number(Number(v).toFixed(6)));
}

/**
 * WGS84转Web墨卡托
 * @param {*} lng
 * @param {*} lat
 * @returns
 */
export function WGS84ToMercator(lng, lat) {
  return proj4("EPSG:4326", "EPSG:3857", [Number(lng), Number(lat)]).map((v) => Number(Number(v).toFixed(2)));
}

/**
 * WGS84转画布坐标
 * @param {*} x
 * @param {*} y
 * @returns
 */
export function WGS84ToCanvasXY(lon, lat, zoom) {
  return [lon, lat, zoom];
}
