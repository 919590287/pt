export const EARTH_RADIUS = 20037508.3427892;

// 高德地图瓦片坐标与Google Map、Open Street Map相同
export class OSMTileUtils {
  static getTileSize(zoom) {
    const width = Math.abs(OSMTileUtils.rowToX(1, zoom) - OSMTileUtils.rowToX(0, zoom));
    const height = Math.abs(OSMTileUtils.colToY(1, zoom) - OSMTileUtils.colToY(0, zoom));
    return [width, height];
  }

  static xToRow(x, zoom) {
    return Math.floor(((EARTH_RADIUS + x) * Math.pow(2, zoom)) / (EARTH_RADIUS * 2));
  }

  static rowToX(row, zoom) {
    return (row * (EARTH_RADIUS * 2)) / Math.pow(2, zoom) - EARTH_RADIUS;
  }

  static rowToDrawX(row = 0, zoom = 1, offset = [0, 0]) {
    return OSMTileUtils.rowToX(row + offset[0], zoom);
  }

  static yToCol(y, zoom) {
    return Math.floor(((EARTH_RADIUS - y) * Math.pow(2, zoom)) / (EARTH_RADIUS * 2));
  }

  static colToY(col, zoom) {
    return EARTH_RADIUS - (col * (EARTH_RADIUS * 2)) / Math.pow(2, zoom);
  }

  static colToDrawY(col = 0, zoom = 1, offset = [0, 0]) {
    return OSMTileUtils.colToY(col + offset[0], zoom);
  }

  static getTileRangeByZoom(zoom, center, width) {
    // const [mapCenterX, mapCenterY] = this.center;
    // const { far, fov } = this.camera;
    // const width = far / (Math.cos((Math.PI * fov) / 180) * 2);
    const [mapCenterX, mapCenterY] = center;
    const row = OSMTileUtils.xToRow(mapCenterX, zoom);
    const col = OSMTileUtils.yToCol(mapCenterY, zoom);
    const tileSize = OSMTileUtils.getTileSize(zoom);
    const radius = Math.ceil(width / tileSize[0]);

    const max_row_col = Math.pow(2, zoom);
    const min_row_col = 0;

    let rowStart = row - radius;
    if (rowStart < min_row_col) rowStart = min_row_col;
    let rowEnd = row + radius;
    if (rowEnd > max_row_col) rowEnd = max_row_col;

    let colStart = col - radius;
    if (colStart < min_row_col) colStart = min_row_col;
    let colEnd = col + radius;
    if (colEnd > max_row_col) colEnd = max_row_col;

    const x1 = OSMTileUtils.rowToX(rowStart, zoom);
    const y1 = OSMTileUtils.colToY(colStart, zoom);
    const x2 = OSMTileUtils.rowToX(rowEnd, zoom);
    const y2 = OSMTileUtils.colToY(colEnd, zoom);

    return {
      row: [rowStart, rowEnd],
      col: [colStart, colEnd],
      minX: Math.min(x1, x2),
      maxX: Math.max(x1, x2),
      minY: Math.min(y1, y2),
      maxY: Math.max(y1, y2),
      bbox: [x1, y1, x2, y2],
      center: [mapCenterX, mapCenterY],
      size: tileSize,
      zoom: zoom,
    };
  }

  static getTileList(zoom, center, width) {
    const { row, col, size } = OSMTileUtils.getTileRangeByZoom(zoom, center, width);
    const array = [];
    for (let i = row[0]; i <= row[1]; i++) {
      for (let j = col[0]; j <= col[1]; j++) {
        array.push([i, j, size]);
      }
    }
    return array;
  }
}
