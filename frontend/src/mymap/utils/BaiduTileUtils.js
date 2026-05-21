import proj4 from "proj4";

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

const x_pi = (3.14159265358979324 * 3000.0) / 180.0;
const pi = 3.1415926535897932384626; // π
const a = 6378245.0; // 长半轴
const ee = 0.00669342162296594323; // 扁率
// 百度墨卡托投影纠正矩阵
const LLBAND = [75, 60, 45, 30, 15, 0];
const LL2MC = [
  [-0.0015702102444, 111320.7020616939, 1704480524535203, -10338987376042340, 26112667856603880, -35149669176653700, 26595700718403920, -10725012454188240, 1800819912950474, 82.5],
  [0.0008277824516172526, 111320.7020463578, 647795574.6671607, -4082003173.641316, 10774905663.51142, -15171875531.51559, 12053065338.62167, -5124939663.577472, 913311935.9512032, 67.5],
  [0.00337398766765, 111320.7020202162, 4481351.045890365, -23393751.19931662, 79682215.47186455, -115964993.2797253, 97236711.15602145, -43661946.33752821, 8477230.501135234, 52.5],
  [0.00220636496208, 111320.7020209128, 51751.86112841131, 3796837.749470245, 992013.7397791013, -1221952.21711287, 1340652.697009075, -620943.6990984312, 144416.9293806241, 37.5],
  [-0.0003441963504368392, 111320.7020576856, 278.2353980772752, 2485758.690035394, 6070.750963243378, 54821.18345352118, 9540.606633304236, -2710.55326746645, 1405.483844121726, 22.5],
  [-0.0003218135878613132, 111320.7020701615, 0.00369383431289, 823725.6402795718, 0.46104986909093, 2351.343141331292, 1.58060784298199, 8.77738589078284, 0.37238884252424, 7.45],
];
// 百度墨卡托转回到百度经纬度纠正矩阵
const MCBAND = [12890594.86, 8362377.87, 5591021, 3481989.83, 1678043.12, 0];
const MC2LL = [
  [1.410526172116255e-8, 0.00000898305509648872, -1.9939833816331, 200.9824383106796, -187.2403703815547, 91.6087516669843, -23.38765649603339, 2.57121317296198, -0.03801003308653, 17337981.2],
  [-7.435856389565537e-9, 0.000008983055097726239, -0.78625201886289, 96.32687599759846, -1.85204757529826, -59.36935905485877, 47.40033549296737, -16.50741931063887, 2.28786674699375, 10260144.86],
  [-3.030883460898826e-8, 0.00000898305509983578, 0.30071316287616, 59.74293618442277, 7.357984074871, -25.38371002664745, 13.45380521110908, -3.29883767235584, 0.32710905363475, 6856817.37],
  [-1.981981304930552e-8, 0.000008983055099779535, 0.03278182852591, 40.31678527705744, 0.65659298677277, -4.44255534477492, 0.85341911805263, 0.12923347998204, -0.04625736007561, 4482777.06],
  [3.09191371068437e-9, 0.000008983055096812155, 0.00006995724062, 23.10934304144901, -0.00023663490511, -0.6321817810242, -0.00663494467273, 0.03430082397953, -0.00466043876332, 2555164.4],
  [2.890871144776878e-9, 0.000008983055095805407, -3.068298e-8, 7.47137025468032, -0.00000353937994, -0.02145144861037, -0.00001234426596, 0.00010322952773, -0.00000323890364, 826088.5],
];

export function LLT(x, y) {
  return { x, y };
}

/**
 * 判断是否在国内，不在国内不做偏移
 * @param {*} lng 火星坐标经度
 * @param {*} lat 火星坐标纬度
 * @returns
 */
export function out_of_china(lng, lat) {
  if (lng < 72.004 || lng > 137.8347) {
    return true;
  }
  if (lat < 0.8293 || lat > 55.8271) {
    return true;
  }
  return false;
}

/**
 * 火星坐标系(GCJ02)转百度坐标系(BD09)
 * @param {*} lng 火星坐标经度
 * @param {*} lat 火星坐标纬度
 * @returns
 */
export function gcj02tobd09(lng, lat) {
  let z = Math.sqrt(lng * lng + lat * lat) + 0.00002 * Math.sin(lat * x_pi);
  let theta = Math.atan2(lat, lng) + 0.000003 * Math.cos(lng * x_pi);
  let bd_lng = z * Math.cos(theta) + 0.0065;
  let bd_lat = z * Math.sin(theta) + 0.006;
  return [bd_lng, bd_lat];
}

/**
 * 百度坐标系(BD09)转火星坐标系(GCJ02)
 * @param {*} bd_lng 百度坐标经度
 * @param {*} bd_lat 百度坐标纬度
 * @returns 转换后的坐标列表形式
 */
export function bd09togcj02(bd_lon, bd_lat) {
  let x = bd_lon - 0.0065;
  let y = bd_lat - 0.006;
  let z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * x_pi);
  let theta = Math.atan2(y, x) - 0.000003 * Math.cos(x * x_pi);
  let gg_lng = z * Math.cos(theta);
  let gg_lat = z * Math.sin(theta);
  return [gg_lng, gg_lat];
}

/**
 * WGS84转GCJ02(火星坐标系)
 * @param {*} lng WGS84坐标系的经度
 * @param {*} lat WGS84坐标系的纬度
 * @returns
 */
export function wgs84togcj02(lng, lat) {
  if (out_of_china(lng, lat)) return [lng, lat]; // 判断是否在国内
  let dlat = transformlat(lng - 105.0, lat - 35.0);
  let dlng = transformlng(lng - 105.0, lat - 35.0);
  let radlat = (lat / 180.0) * pi;
  let magic = Math.sin(radlat);
  magic = 1 - ee * magic * magic;
  let sqrtmagic = Math.sqrt(magic);
  dlat = (dlat * 180.0) / (((a * (1 - ee)) / (magic * sqrtmagic)) * pi);
  dlng = (dlng * 180.0) / ((a / sqrtmagic) * Math.cos(radlat) * pi);
  let mglat = lat + dlat;
  let mglng = lng + dlng;
  return [mglng, mglat];
}

/**
 * GCJ02(火星坐标系)转GPS84
 * @param {*} lng 火星坐标系的经度
 * @param {*} lat 火星坐标系纬度
 * @returns
 */
export function gcj02towgs84(lng, lat) {
  if (out_of_china(lng, lat)) return [lng, lat]; // 判断是否在国内
  let dlat = transformlat(lng - 105.0, lat - 35.0);
  let dlng = transformlng(lng - 105.0, lat - 35.0);
  let radlat = (lat / 180.0) * pi;
  let magic = Math.sin(radlat);
  magic = 1 - ee * magic * magic;
  let sqrtmagic = Math.sqrt(magic);
  dlat = (dlat * 180.0) / (((a * (1 - ee)) / (magic * sqrtmagic)) * pi);
  dlng = (dlng * 180.0) / ((a / sqrtmagic) * Math.cos(radlat) * pi);
  let mglat = lat + dlat;
  let mglng = lng + dlng;
  return [lng * 2 - mglng, lat * 2 - mglat];
}

export function transformlat(lng, lat) {
  let ret = -100.0 + 2.0 * lng + 3.0 * lat + 0.2 * lat * lat + 0.1 * lng * lat + 0.2 * Math.sqrt(Math.abs(lng));
  ret += ((20.0 * Math.sin(6.0 * lng * pi) + 20.0 * Math.sin(2.0 * lng * pi)) * 2.0) / 3.0;
  ret += ((20.0 * Math.sin(lat * pi) + 40.0 * Math.sin((lat / 3.0) * pi)) * 2.0) / 3.0;
  ret += ((160.0 * Math.sin((lat / 12.0) * pi) + 320 * Math.sin((lat * pi) / 30.0)) * 2.0) / 3.0;
  return ret;
}

export function transformlng(lng, lat) {
  let ret = 300.0 + lng + 2.0 * lat + 0.1 * lng * lng + 0.1 * lng * lat + 0.1 * Math.sqrt(Math.abs(lng));
  ret += ((20.0 * Math.sin(6.0 * lng * pi) + 20.0 * Math.sin(2.0 * lng * pi)) * 2.0) / 3.0;
  ret += ((20.0 * Math.sin(lng * pi) + 40.0 * Math.sin((lng / 3.0) * pi)) * 2.0) / 3.0;
  ret += ((150.0 * Math.sin((lng / 12.0) * pi) + 300.0 * Math.sin((lng / 30.0) * pi)) * 2.0) / 3.0;
  return ret;
}

export function getRange(cC, cB, t) {
  if (cB !== null) {
    cC = Math.max(cC, cB);
  }
  if (t !== null) {
    cC = Math.min(cC, t);
  }
  return cC;
}

export function getLoop(cC, cB, t) {
  while (cC > t) {
    cC -= t - cB;
  }
  while (cC < cB) {
    cC += t - cB;
  }
  return cC;
}

export function convertor(cC, cD) {
  if (cC === null || cD === null) return null;
  let t = cD[0] + cD[1] * Math.abs(cC.x);
  let cB = Math.abs(cC.y) / cD[9];
  let cE = cD[2] + cD[3] * cB + cD[4] * cB * cB + cD[5] * cB * cB * cB + cD[6] * cB * cB * cB * cB + cD[7] * cB * cB * cB * cB * cB + cD[8] * cB * cB * cB * cB * cB * cB;
  if (cC.x < 0) {
    t = t * -1;
  } else {
    t = t;
  }
  if (cC.y < 0) {
    cE = cE * -1;
  } else {
    cE = cE;
  }
  return [t, cE];
}

export function convertLL2MC(t) {
  let cD = null;
  t.x = getLoop(t.x, -180, 180);
  t.y = getRange(t.y, -74, 74);
  let cB = t;
  for (let cC = 0; cC < LLBAND.length; cC++) {
    if (cB.y >= LLBAND[cC]) {
      cD = LL2MC[cC];
      break;
    }
  }
  if (cD === null) {
    for (let cC = LLBAND.length - 1; cC >= 0; cC--) {
      if (cB.y <= -LLBAND[cC]) {
        cD = LL2MC[cC];
        break;
      }
    }
  }
  let cE = convertor(t, cD);
  return cE;
}

export function convertMC2LL(cB) {
  let cC = LLT(Math.abs(cB.x), Math.abs(cB.y));
  let cE = null;
  for (let cD = 0; cD < MCBAND.length; cD++) {
    if (cC.y >= MCBAND[cD]) {
      cE = MC2LL[cD];
      break;
    }
  }
  let t = convertor(cB, cE);
  return t;
}

/**
 * bd09投影到百度墨卡托
 * @param {*} lng
 * @param {*} lat
 * @returns
 */
export function bd09tomercator(lng, lat) {
  let baidut = LLT(lng, lat);
  return convertLL2MC(baidut);
}

/**
 * 墨卡托投影坐标转回bd09
 * @param {*} x
 * @param {*} y
 * @returns
 */
export function mercatortobd09(x, y) {
  let baidut = LLT(x, y);
  return convertMC2LL(baidut);
}

//  百度地图18级时的像素分辨率为1m/pixel
export function getResolution(level) {
  return Math.pow(2, level - 18);
}
export function getResolutionLat(lat, level) {
  return Math.pow(2, 18 - level) * Math.cos(lat);
}
export function lngToTileX(lng, level) {
  let point = bd09tomercator(lng, 0);
  return Math.floor((point[0] * getResolution(level)) / 256);
}
export function latToTileY(lat, level) {
  let point = bd09tomercator(0, lat);
  return Math.floor((point[1] * getResolution(level)) / 256);
}

// 经纬度转瓦片坐标
export function lnglatToTile(lng, lat, level) {
  let tileX = lngToTileX(lng, level);
  let tileY = latToTileY(lat, level);
  return [tileX, tileY];
}
export function lngToPixelX(lng, level) {
  let tileX = lngToTileX(lng, level);
  let point = bd09tomercator(lng, 0);
  return Math.floor(point[0] * getResolution(level) - tileX * 256);
}
export function latToPixelY(lat, level) {
  let tileY = latToTileY(lat, level);
  let point = bd09tomercator(0, lat);
  return Math.floor(point[1] * getResolution(level) - tileY * 256);
}

// 经纬度转像素坐标
export function lnglatToPixel(lng, lat, level) {
  let pixelX = lngToPixelX(lng, level);
  let pixelY = latToPixelY(lat, level);
  return pixelX, pixelY;
}
export function pixelXToLng(pixelX, tileX, level) {
  let pointX = (tileX * 256 + pixelX) / getResolution(level);
  let lnglat = mercatortobd09(pointX, 0);
  return lnglat[0];
}
export function pixelYToLat(pixelY, tileY, level) {
  let pointY = (tileY * 256 + pixelY) / getResolution(level);
  let lnglat = mercatortobd09(0, pointY);
  return lnglat[1];
}

// 像素坐标和瓦片坐标转经纬度
export function pixelToLnglat(pixelX, pixelY, tileX, tileY, level) {
  let pointX = (tileX * 256 + pixelX) / getResolution(level);
  let pointY = (tileY * 256 + pixelY) / getResolution(level);
  return mercatortobd09(pointX, pointY);
}

export class BaiduTileUtils {
  static getTileSize(zoom) {
    const width = Math.abs(BaiduTileUtils.rowToX(1, zoom) - BaiduTileUtils.rowToX(0, zoom));
    const height = Math.abs(BaiduTileUtils.colToY(1, zoom) - BaiduTileUtils.colToY(0, zoom));
    return [width, height];
  }

  static xToRow(x, zoom) {
    const [lng, lat] = MercatorToWGS84(x + 600, 0);
    const [lng2, lat2] = wgs84togcj02(lng, lat);
    const [lng3, lat3] = gcj02tobd09(lng2, lat2);
    return lngToTileX(lng3, zoom);
  }

  static rowToX(row = 0, zoom = 1) {
    const lng = pixelXToLng(0, row, zoom);
    const [lng2, lat2] = bd09togcj02(lng, 0);
    const [lng3, lat3] = gcj02towgs84(lng2, lat2);
    const [x, y] = WGS84ToMercator(lng3, 0);
    return Math.floor(x - 600);
  }

  static rowToDrawX(row = 0, zoom = 1, offset = [0, 0]) {
    return BaiduTileUtils.rowToX(row + offset[0], zoom);
  }

  static yToCol(y, zoom) {
    const [lng, lat] = MercatorToWGS84(0, y - 350);
    const [lng2, lat2] = wgs84togcj02(lng, lat);
    const [lng3, lat3] = gcj02tobd09(lng2, lat2);
    return latToTileY(lat3, zoom);
  }

  static colToY(col = 0, zoom = 1) {
    const lat = pixelYToLat(0, col, zoom);
    const [lng2, lat2] = bd09togcj02(0, lat);
    const [lng3, lat3] = gcj02towgs84(lng2, lat2);
    const [x, y] = WGS84ToMercator(0, lat3);
    return Math.floor(y + 350);
  }

  static colToDrawY(col = 0, zoom = 1, offset = [0, 0]) {
    return BaiduTileUtils.colToY(col + 1 - offset[1], zoom);
  }

  static getTileRangeByZoom(zoom, center, width) {
    const row = BaiduTileUtils.xToRow(center[0], zoom);
    const col = BaiduTileUtils.yToCol(center[1], zoom);
    const tileSize = BaiduTileUtils.getTileSize(zoom);
    const radius = Math.ceil(width / tileSize[0]);

    const max_row_col = Math.pow(2, zoom - 1);
    const min_row_col = Math.pow(2, zoom - 1);

    let rowStart = row - radius;
    // if (rowStart < min_row_col) rowStart = min_row_col;
    let rowEnd = row + radius;
    // if (rowEnd > max_row_col) rowEnd = max_row_col;

    let colStart = col - radius;
    // if (colStart > max_row_col) colStart = max_row_col;
    let colEnd = col + radius;
    // if (colEnd < min_row_col) colEnd = min_row_col;

    const x1 = BaiduTileUtils.rowToX(rowStart, zoom);
    const y1 = BaiduTileUtils.colToY(colStart, zoom);
    const x2 = BaiduTileUtils.rowToX(rowEnd, zoom);
    const y2 = BaiduTileUtils.colToY(colEnd, zoom);

    return {
      row: [rowStart, rowEnd],
      col: [colStart, colEnd],
      minX: Math.min(x1, x2),
      maxX: Math.max(x1, x2),
      minY: Math.min(y1, y2),
      maxY: Math.max(y1, y2),
      bbox: [x1, y1, x2, y2],
      center: center,
      size: tileSize,
      zoom: zoom,
    };
  }
  static getTileList(zoom, center, width) {
    const { row, col, size } = BaiduTileUtils.getTileRangeByZoom(zoom, center, width);
    const array = [];
    for (let i = row[0]; i <= row[1]; i++) {
      for (let j = col[0]; j <= col[1]; j++) {
        array.push([i, j, size]);
      }
    }
    return array;
  }
}
