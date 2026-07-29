import { GRID_STREET_SENTINEL, mercatorToLngLat } from "./populationGrid.js";

const EMPTY_COLLECTION = Object.freeze({
  type: "FeatureCollection",
  features: Object.freeze([]),
});

/**
 * 把真实站点起终点单元转换为核密度热力点。
 *
 * 权重使用 sqrt(flow / maxFlow)：保持真实客流排序，同时压缩长尾差异，避免真实站点较少时
 * 只有最大站点可见。经纬度取 100m 单元中心，不再使用方格西南角。
 */
export function buildTripEndsHeatmapFeatureCollection(grid, counts) {
  if (!grid || !counts || grid.count <= 0) {
    return { collection: EMPTY_COLLECTION, maxFlow: 0, pointCount: 0 };
  }

  let maxFlow = 0;
  for (let index = 0; index < grid.count; index += 1) {
    const flow = Math.max(0, Number(counts[index]) || 0);
    if (flow > maxFlow) maxFlow = flow;
  }
  if (!(maxFlow > 0)) {
    return { collection: EMPTY_COLLECTION, maxFlow: 0, pointCount: 0 };
  }

  const halfCell = grid.mercCellSize / 2;
  const features = [];
  for (let index = 0; index < grid.count; index += 1) {
    const flow = Math.max(0, Number(counts[index]) || 0);
    if (!(flow > 0)) continue;
    const [lng, lat] = mercatorToLngLat(
      grid.i[index] * grid.mercCellSize + halfCell,
      grid.j[index] * grid.mercCellSize + halfCell,
    );
    features.push({
      type: "Feature",
      id: `trip-end-heat-${index}`,
      geometry: { type: "Point", coordinates: [lng, lat] },
      properties: {
        flow,
        weight: Math.sqrt(flow / maxFlow),
        streetIndex: Number(grid.street?.[index] ?? GRID_STREET_SENTINEL),
      },
    });
  }

  return {
    maxFlow,
    pointCount: features.length,
    collection: { type: "FeatureCollection", features },
  };
}

/** 行政区切换只过滤热力点，不重新归一化权重，保证同一站点跨范围颜色口径稳定。 */
export function filterTripEndsHeatmapFeatureCollection(payload, scopeStreetMask) {
  if (!scopeStreetMask) return payload;
  const features = (payload?.collection?.features || []).filter((feature) => {
    const streetIndex = Number(feature?.properties?.streetIndex);
    return Number.isInteger(streetIndex)
      && streetIndex !== GRID_STREET_SENTINEL
      && Boolean(scopeStreetMask[streetIndex]);
  });
  return {
    maxFlow: Number(payload?.maxFlow) || 0,
    pointCount: features.length,
    collection: { type: "FeatureCollection", features },
  };
}

