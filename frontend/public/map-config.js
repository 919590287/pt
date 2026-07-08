// Basemap config.
// Online default for local development. For offline servers, switch getUrl()
// to the local /tiles path after preparing tiles.
const appConfig = window.APP_CONFIG || {};
const defaultBuildingShpPath = "/Users/a../数据/四维路网数据/可视化数据20251128/建筑物-旧v2/Buildingguagnzhou84.shp";
const DEFAULT_BASEMAP_STORAGE_KEY = "gjcxfzksh:basemap";
const CARTO_LIGHT_2X = "https://a.basemaps.cartocdn.com/light_all/{z}/{x}/{y}@2x.png";
const CARTO_DARK_2X = "https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}@2x.png";
const CARTO_VOYAGER_2X = "https://a.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}@2x.png";

function tileUrlFromTemplate(template, xyz) {
  return template
    .replace(/\{z\}/g, xyz.z)
    .replace(/\{x\}/g, xyz.x)
    .replace(/\{y\}/g, xyz.y);
}

window.DEFAULT_MAP_LAYER_STYLE_INDEX = 0;
window.BASEMAP_STORAGE_KEY = appConfig.basemapStorageKey || DEFAULT_BASEMAP_STORAGE_KEY;
window.BASEMAP_OPTIONS = [
  {
    key: "configured",
    label: "默认",
    description: "默认",
    style_name: "configured-basemap",
    background: 0xf5f5f5,
    min_zoom: 0,
    max_zoom: 18,
    tileSize: 256,
    tiles: [appConfig.mapTileUrlTemplate || CARTO_LIGHT_2X],
    attribution: '&copy; <a href="https://carto.com/attributions">CARTO</a>',
  },
  {
    key: "carto-light",
    label: "Light",
    description: "Light",
    style_name: "carto-light-2x",
    background: 0xf5f5f5,
    min_zoom: 0,
    max_zoom: 18,
    tileSize: 256,
    tiles: [CARTO_LIGHT_2X],
    attribution: '&copy; <a href="https://carto.com/attributions">CARTO</a>',
  },
  {
    key: "carto-dark",
    label: "Dark",
    description: "Dark",
    style_name: "carto-dark-2x",
    background: 0x12161d,
    min_zoom: 0,
    max_zoom: 18,
    tileSize: 256,
    tiles: [CARTO_DARK_2X],
    attribution: '&copy; <a href="https://carto.com/attributions">CARTO</a>',
  },
  {
    key: "carto-voyager",
    label: "Voyager",
    description: "Voyager",
    style_name: "carto-voyager-2x",
    background: 0xf3f0e8,
    min_zoom: 0,
    max_zoom: 18,
    tileSize: 256,
    tiles: [CARTO_VOYAGER_2X],
    attribution: '&copy; <a href="https://carto.com/attributions">CARTO</a>',
  },
];
window.DEFAULT_BASEMAP_KEY = appConfig.defaultBasemapKey || "configured";
window.getSelectedBaseMapStyle = function getSelectedBaseMapStyle() {
  const options = window.BASEMAP_OPTIONS || [];
  let key = window.DEFAULT_BASEMAP_KEY || "configured";
  try {
    key = window.localStorage?.getItem(window.BASEMAP_STORAGE_KEY) || key;
  } catch (error) {
    // Ignore storage access failures and fall back to deployment config.
  }
  return options.find((item) => item.key === key) || options.find((item) => item.key === window.DEFAULT_BASEMAP_KEY) || options[0] || {};
};
window.MAP_LAYER_STYLE = [
  window.getSelectedBaseMapStyle(),
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
      return tileUrlFromTemplate(CARTO_LIGHT_2X, {
        z: this.zoom,
        x: this.row,
        y: this.col,
      });
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
