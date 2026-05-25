// Basemap config.
// Online default for local development. For offline servers, switch getUrl()
// to the local /tiles path after preparing tiles.
const appConfig = window.APP_CONFIG || {};
const defaultBuildingShpPath = "/Users/a../数据/四维路网数据/可视化数据20251128/建筑物-旧v2/Buildingguagnzhou84.shp";

function tileUrlFromTemplate(template, xyz) {
  return template
    .replace(/\{z\}/g, xyz.z)
    .replace(/\{x\}/g, xyz.x)
    .replace(/\{y\}/g, xyz.y);
}

window.DEFAULT_MAP_LAYER_STYLE_INDEX = 0;
window.MAP_LAYER_STYLE = [
  {
    style_name: "online-carto-light",
    background: 0xf5f5f5,
    min_zoom: 0,
    max_zoom: 18,
    getUrl() {
      if (appConfig.mapTileUrlTemplate) {
        return tileUrlFromTemplate(appConfig.mapTileUrlTemplate, {
          z: this.zoom,
          x: this.row,
          y: this.col,
        });
      }
      return `https://basemaps.cartocdn.com/light_all/${this.zoom}/${this.row}/${this.col}@2x.png`;
      // Offline tile path:
      // return `/tiles/light_all/${this.zoom}/${this.row}/${this.col}.png`;
    },
  },
];

// Guangzhou building footprint layer. The backend reads this shapefile by
// viewport and the frontend extrudes polygons by the HEIGHT field.
window.CITY_BUILDINGS_LAYER = {
  enabled: appConfig.cityBuildingsEnabled !== false,
  shpPath: appConfig.cityBuildingsShpPath || defaultBuildingShpPath,
  heightField: appConfig.cityBuildingsHeightField || "HEIGHT",
  minZoom: 12.5,
  maxFeatures: Number(appConfig.cityBuildingsMaxFeatures) || 20000,
  prefetchMeters: 900,
  maxViewDistanceScale: 0.9,
  maxViewDistanceMeters: 6000,
  color: 0xd8dde2,
  minHeight: 0.5,
};
