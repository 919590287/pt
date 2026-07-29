const DEFAULT_TILE_METERS = 4096;

export function supportsTrajectorySpatialChunks(manifest = {}) {
  const spatial = manifest?.spatial || {};
  return spatial.layout === "indexed-container-midpoint-envelope-v2"
    && Number(spatial.tileSizeMeters) > 0;
}

// 把图层已扩大的采样视口对齐到固定 WebMercator 网格。同一网格窗口内的
// 小幅平移复用 HTTP/IndexedDB 块，只在跨格时刷新，避免拖图请求风暴。
export function quantizeTrajectoryViewport(bounds, manifest = {}) {
  if (!bounds) return null;
  const minX = Number(bounds.minX);
  const minY = Number(bounds.minY);
  const maxX = Number(bounds.maxX);
  const maxY = Number(bounds.maxY);
  if (![minX, minY, maxX, maxY].every(Number.isFinite) || maxX <= minX || maxY <= minY) {
    return null;
  }
  const configured = Number(manifest?.spatial?.tileSizeMeters);
  const tileSize = Number.isFinite(configured) && configured > 0
    ? configured
    : DEFAULT_TILE_METERS;
  const tileMinX = Math.floor(minX / tileSize);
  const tileMinY = Math.floor(minY / tileSize);
  // max 为开区间边界。Number.EPSILON 在 WebMercator 1e7 量级会被舍入掉，
  // 用 ceil(...)-1 才能在正/负坐标和精确 tile 边界上都不多取一列/行。
  const tileMaxX = Math.ceil(maxX / tileSize) - 1;
  const tileMaxY = Math.ceil(maxY / tileSize) - 1;
  return {
    minX: tileMinX * tileSize,
    minY: tileMinY * tileSize,
    maxX: (tileMaxX + 1) * tileSize,
    maxY: (tileMaxY + 1) * tileSize,
    tileMinX,
    tileMinY,
    tileMaxX,
    tileMaxY,
    tileSize,
    key: `${tileSize}:${tileMinX}:${tileMinY}:${tileMaxX}:${tileMaxY}`,
  };
}
