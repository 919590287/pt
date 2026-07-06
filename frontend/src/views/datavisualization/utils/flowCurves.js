/**
 * 地图客流曲线工具（需求7/8/10）：把 OD 对生成美观的二次贝塞尔弧线 GeoJSON，
 * 供 站间OD曲线 / 站点OD来源曲线 / 可达性曲线 复用。
 */

const DEG = Math.PI / 180;
const MAX_MERCATOR_LAT = 85;

function lngLatToMercatorUnit(lng, lat) {
  const clampedLat = Math.max(-MAX_MERCATOR_LAT, Math.min(MAX_MERCATOR_LAT, Number(lat) || 0));
  return [(Number(lng) || 0) * DEG, Math.log(Math.tan(Math.PI / 4 + (clampedLat * DEG) / 2))];
}

function mercatorUnitToLngLat(x, y) {
  return [x / DEG, (2 * Math.atan(Math.exp(y)) - Math.PI / 2) / DEG];
}

/**
 * 一组墨卡托点的主轴方向（PCA 最大特征向量），用于把所有弧线统一偏向线路的同一侧。
 * @param points Array<[x, y]>（墨卡托单位）
 * @returns [ux, uy] 单位向量
 */
function principalAxisUnit(points) {
  const n = points.length;
  if (n < 2) return [1, 0];
  let mx = 0;
  let my = 0;
  for (const [x, y] of points) { mx += x; my += y; }
  mx /= n;
  my /= n;
  let sxx = 0;
  let sxy = 0;
  let syy = 0;
  for (const [x, y] of points) {
    const dx = x - mx;
    const dy = y - my;
    sxx += dx * dx;
    sxy += dx * dy;
    syy += dy * dy;
  }
  const theta = 0.5 * Math.atan2(2 * sxy, sxx - syy);
  return [Math.cos(theta), Math.sin(theta)];
}

/**
 * 生成两点间的弧线坐标（经纬度）。
 * 在 Web Mercator 投影空间取中点沿垂直方向偏移 curvature × 距离作控制点、采样二次贝塞尔后再反投影——
 * 直接在经纬度度数空间算“垂直”会因经/纬度比例不一致导致屏幕上弧线歪斜走形。
 * @param from [lng, lat]
 * @param to [lng, lat]
 * @param options { curvature?: number (默认0.22), segments?: number (默认32), side?: 1|-1 }
 * @returns Array<[lng, lat]>
 */
export function curvedLineCoordinates(from, to, options = {}) {
  const curvature = Number.isFinite(options.curvature) ? options.curvature : 0.22;
  const segments = Math.max(4, Math.round(options.segments ?? 32));
  const side = options.side === -1 ? -1 : 1;

  const [x1, y1] = lngLatToMercatorUnit(from[0], from[1]);
  const [x2, y2] = lngLatToMercatorUnit(to[0], to[1]);
  const dx = x2 - x1;
  const dy = y2 - y1;
  const distance = Math.hypot(dx, dy);
  if (!Number.isFinite(distance) || distance === 0) {
    return [from, to];
  }
  const midX = (x1 + x2) / 2;
  const midY = (y1 + y2) / 2;
  const normX = (-dy / distance) * side;
  const normY = (dx / distance) * side;
  const offset = distance * curvature;
  const controlX = midX + normX * offset;
  const controlY = midY + normY * offset;

  const coordinates = [];
  for (let i = 0; i <= segments; i++) {
    const t = i / segments;
    const oneMinus = 1 - t;
    coordinates.push(mercatorUnitToLngLat(
      oneMinus * oneMinus * x1 + 2 * oneMinus * t * controlX + t * t * x2,
      oneMinus * oneMinus * y1 + 2 * oneMinus * t * controlY + t * t * y2,
    ));
  }
  // 端点用原始坐标，避免投影往返的浮点误差
  coordinates[0] = [from[0], from[1]];
  coordinates[segments] = [to[0], to[1]];
  return coordinates;
}

/**
 * 由 OD 流量数组生成曲线 FeatureCollection。
 * @param flows Array<{ from:[lng,lat], to:[lng,lat], value:number, properties?:object }>
 * @param options 透传 curvedLineCoordinates 的 options
 * @returns GeoJSON FeatureCollection，feature.properties 含 value 与调用方自定义属性
 */
export function buildFlowCurveFeatureCollection(flows, options = {}) {
  const list = (flows || []).filter((flow) => Array.isArray(flow?.from) && Array.isArray(flow?.to));

  // consistentSide：把所有弧线统一偏到线路主轴的同一侧（期望线图风格），
  // 与行驶方向无关——避免上/下行弧线各偏一侧造成两侧交织的凌乱观感。
  let sideRef = null;
  if (options.consistentSide && list.length > 0) {
    const pts = [];
    for (const flow of list) {
      pts.push(lngLatToMercatorUnit(flow.from[0], flow.from[1]));
      pts.push(lngLatToMercatorUnit(flow.to[0], flow.to[1]));
    }
    const [ax, ay] = principalAxisUnit(pts);
    sideRef = [-ay, ax]; // 主轴的法向 = 统一偏移的目标侧
  }

  const features = [];
  for (const flow of list) {
    let opts = options;
    if (sideRef) {
      const [x1, y1] = lngLatToMercatorUnit(flow.from[0], flow.from[1]);
      const [x2, y2] = lngLatToMercatorUnit(flow.to[0], flow.to[1]);
      // 段的基准法向 (-dy, dx)；取 side 让其始终指向 sideRef 一侧
      const dot = -(y2 - y1) * sideRef[0] + (x2 - x1) * sideRef[1];
      opts = { ...options, side: dot >= 0 ? 1 : -1 };
    }
    const value = Number(flow.value);
    features.push({
      type: "Feature",
      geometry: {
        type: "LineString",
        coordinates: curvedLineCoordinates(flow.from, flow.to, opts),
      },
      properties: {
        value: Number.isFinite(value) ? value : 0,
        ...(flow.properties || {}),
      },
    });
  }
  return { type: "FeatureCollection", features };
}

/** 空集合，供图层初始化/清空 */
export function emptyFlowCurveCollection() {
  return { type: "FeatureCollection", features: [] };
}
