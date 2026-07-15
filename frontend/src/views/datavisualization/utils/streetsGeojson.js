// 街道边界面（模型无关静态资源）模块级 memo：人口分布 / 起终点分布 / 公交OD 子模块共用，
// 跨模型/跨组件实例复用一份解析结果；失败不缓存，允许重试。
import { markRaw } from "vue";
import { getStreetsGeojson } from "@/api/population.js";

let streetsGeojsonPromise = null;

export function fetchStreetsGeojsonOnce() {
  if (!streetsGeojsonPromise) {
    streetsGeojsonPromise = getStreetsGeojson({ silentError: true })
      .then((res) => {
        const fc = res?.data && res.data.type === "FeatureCollection" ? res.data : res;
        if (!fc || fc.type !== "FeatureCollection") throw new Error("街道边界数据格式异常");
        return markRaw(fc);
      })
      .catch((error) => {
        streetsGeojsonPromise = null; // 失败不缓存，允许重试
        throw error;
      });
  }
  return streetsGeojsonPromise;
}

// ---------------------------------------------------------------------------
// 街道质心（OD 期望线锚点）：MultiPolygon 取面积最大子面的鞋带公式质心，
// 视觉锚点落在主体面内（对飞地街道比全要素均值更稳）。按 fc 引用 memo。
// ---------------------------------------------------------------------------

let centroidsCacheFc = null;
let centroidsCache = null;

/** 单个外环的鞋带公式面积与质心；退化环（面积≈0）回退为顶点均值。 */
function ringCentroid(ring) {
  let area2 = 0;
  let cx = 0;
  let cy = 0;
  for (let k = 0; k < ring.length - 1; k++) {
    const cross = ring[k][0] * ring[k + 1][1] - ring[k + 1][0] * ring[k][1];
    area2 += cross;
    cx += (ring[k][0] + ring[k + 1][0]) * cross;
    cy += (ring[k][1] + ring[k + 1][1]) * cross;
  }
  if (Math.abs(area2) < 1e-12) {
    let sx = 0;
    let sy = 0;
    for (const pt of ring) {
      sx += pt[0];
      sy += pt[1];
    }
    return { area: 0, lng: sx / ring.length, lat: sy / ring.length };
  }
  return { area: Math.abs(area2) / 2, lng: cx / (3 * area2), lat: cy / (3 * area2) };
}

/** code → [lng, lat]。fc 为 fetchStreetsGeojsonOnce 返回的 FeatureCollection。 */
export function streetCentroidsByCode(fc) {
  if (!fc) return new Map();
  if (centroidsCacheFc === fc && centroidsCache) return centroidsCache;
  const centroids = new Map();
  for (const feature of fc.features || []) {
    const code = String(feature.properties?.code || feature.id || "");
    const geometry = feature.geometry || {};
    const polygons = geometry.type === "Polygon"
      ? [geometry.coordinates]
      : geometry.type === "MultiPolygon" ? geometry.coordinates : [];
    let best = null;
    for (const polygon of polygons) {
      if (!polygon?.[0]?.length) continue;
      const candidate = ringCentroid(polygon[0]); // 只看外环，孔洞对锚点影响可忽略
      if (!best || candidate.area > best.area) best = candidate;
    }
    if (best) centroids.set(code, [best.lng, best.lat]);
  }
  centroidsCacheFc = fc;
  centroidsCache = centroids;
  return centroids;
}
