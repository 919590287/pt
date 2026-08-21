// Basemap config.
// Online default for local development. For offline servers, switch getUrl()
// to the local /tiles path after preparing tiles.
const appConfig = window.APP_CONFIG || {};
const defaultBuildingShpPath = "/Users/a../数据/四维路网数据/可视化数据20251128/建筑物-旧v2/Buildingguagnzhou84.shp";
// v2 将平台默认底图迁移为 Esri 深灰；使用新键只重置一次旧默认，之后仍保留用户选择。
const DEFAULT_BASEMAP_STORAGE_KEY = "gjcxfzksh:basemap:v2";
const CARTO_LIGHT_2X = "https://a.basemaps.cartocdn.com/light_all/{z}/{x}/{y}@2x.png";
const CARTO_DARK_2X = "https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}@2x.png";
const CARTO_VOYAGER_2X = "https://a.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}@2x.png";
const ESRI_LIGHT_GRAY = "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Light_Gray_Base/MapServer/tile/{z}/{y}/{x}";
const ESRI_DARK_GRAY = "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}";
const ESRI_STREET = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}";
const ESRI_IMAGERY = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}";
const ESRI_ATTRIBUTION = 'Tiles &copy; <a href="https://www.esri.com/">Esri</a>';
const GEOAPIFY_API_KEY = String(appConfig.geoapifyApiKey || "8970dece88c04376b93a5614b52751e1").trim();
const GEOAPIFY_POSITRON_2X = `https://maps.geoapify.com/v1/tile/positron/{z}/{x}/{y}@2x.png?apiKey=${GEOAPIFY_API_KEY}`;
const GEOAPIFY_DARK_GREY_2X = `https://maps.geoapify.com/v1/tile/dark-matter-dark-grey/{z}/{x}/{y}@2x.png?apiKey=${GEOAPIFY_API_KEY}`;
const GEOAPIFY_ATTRIBUTION = 'Powered by <a href="https://www.geoapify.com/">Geoapify</a> | <a href="https://openmaptiles.org/">&copy; OpenMapTiles</a> <a href="https://www.openstreetmap.org/copyright">&copy; OpenStreetMap contributors</a>';
const THUNDERFOREST_ATLAS_2X = "https://api.thunderforest.com/atlas/{z}/{x}/{y}@2x.png?apikey=d83f80543d564391bb877538b6d9b737";
const THUNDERFOREST_ATTRIBUTION = 'Maps &copy; <a href="https://www.thunderforest.com/">Thunderforest</a>, Data &copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap contributors</a>';
const configuredTileUrl = String(appConfig.mapTileUrlTemplate || "").trim();

function tileUrlFromTemplate(template, xyz) {
  return template
    .replace(/\{z\}/g, xyz.z)
    .replace(/\{x\}/g, xyz.x)
    .replace(/\{y\}/g, xyz.y);
}

window.DEFAULT_MAP_LAYER_STYLE_INDEX = 5;
window.BASEMAP_STORAGE_KEY = appConfig.basemapStorageKey || DEFAULT_BASEMAP_STORAGE_KEY;
window.BASEMAP_OPTIONS = [
  {
    key: "configured",
    hidden: true,
    label: "部署默认",
    description: configuredTileUrl ? "服务器自定义 XYZ" : "Atlas 地图集",
    group: "推荐",
    preview: "linear-gradient(135deg, #eef3ea 0%, #cfe0d5 48%, #e6ded0 100%)",
    style_name: "configured-basemap",
    background: 0xe8eee6,
    min_zoom: 0,
    max_zoom: configuredTileUrl ? 18 : 18,
    tileSize: 256,
    tiles: [configuredTileUrl || THUNDERFOREST_ATLAS_2X],
    attribution: configuredTileUrl ? "" : THUNDERFOREST_ATTRIBUTION,
  },
  {
    key: "esri-light",
    hidden: true,
    label: "白色地图",
    description: "Esri · 浅灰道路地名",
    group: "在线",
    preview: "linear-gradient(135deg, #f1f2f2 0%, #cfd5d9 100%)",
    style_name: "esri-light-gray",
    background: 0xe9ecef,
    min_zoom: 0,
    max_zoom: 16,
    tileSize: 256,
    tiles: [ESRI_LIGHT_GRAY],
    attribution: ESRI_ATTRIBUTION,
  },
  {
    key: "esri-dark",
    label: "黑色地图",
    description: "Esri · 深灰道路地名",
    group: "在线",
    preview: "linear-gradient(135deg, #30343a 0%, #15191f 100%)",
    style_name: "esri-dark-gray",
    dark: true,
    background: 0x20242a,
    min_zoom: 0,
    max_zoom: 16,
    tileSize: 256,
    tiles: [ESRI_DARK_GRAY],
    attribution: ESRI_ATTRIBUTION,
  },
  {
    key: "esri-street",
    hidden: true,
    label: "街道",
    description: "Esri · 道路与地名",
    group: "在线",
    preview: "linear-gradient(135deg, #f3ead2 0%, #b9d5db 48%, #ddd5c2 100%)",
    style_name: "esri-world-street",
    background: 0xe8e3d6,
    min_zoom: 0,
    max_zoom: 19,
    tileSize: 256,
    tiles: [ESRI_STREET],
    attribution: ESRI_ATTRIBUTION,
  },
  {
    key: "esri-imagery",
    label: "卫星影像",
    description: "Esri · 航片与卫星",
    group: "在线",
    preview: "linear-gradient(135deg, #152d27 0%, #54705b 48%, #263a31 100%)",
    style_name: "esri-world-imagery",
    dark: true,
    background: 0x16231f,
    min_zoom: 0,
    max_zoom: 19,
    tileSize: 256,
    tiles: [ESRI_IMAGERY],
    attribution: ESRI_ATTRIBUTION,
  },
  {
    key: "thunderforest-atlas",
    label: "Atlas",
    description: "Thunderforest · 地图集风格（@2x 高清）",
    group: "在线",
    preview: "linear-gradient(135deg, #eef3ea 0%, #cfe0d5 48%, #e6ded0 100%)",
    style_name: "thunderforest-atlas-2x",
    background: 0xe8eee6,
    min_zoom: 0,
    max_zoom: 18,
    // @2x 瓦片实为 512px 图，仍按 256 的瓦片方案渲染（与 CARTO @2x 同口径）
    tileSize: 256,
    tiles: [THUNDERFOREST_ATLAS_2X],
    attribution: THUNDERFOREST_ATTRIBUTION,
  },
  {
    key: "geoapify-positron",
    label: "Geoapify 浅色",
    description: "Positron · 简洁道路地名",
    group: "在线",
    preview: "linear-gradient(135deg, #f7f7f5 0%, #e4e6e5 52%, #cfd5d2 100%)",
    style_name: "geoapify-positron-2x",
    background: 0xf3f3f1,
    min_zoom: 0,
    max_zoom: 20,
    tileSize: 256,
    // @2x 瓦片实为 512px 图，按 256px 瓦片方案渲染以获得高清显示。
    tiles: [GEOAPIFY_POSITRON_2X],
    attribution: GEOAPIFY_ATTRIBUTION,
  },
  {
    key: "geoapify-dark-grey",
    label: "Geoapify 深色",
    description: "Dark Matter · 深灰道路地名",
    group: "在线",
    preview: "linear-gradient(135deg, #343638 0%, #1f2123 52%, #101112 100%)",
    style_name: "geoapify-dark-matter-dark-grey-2x",
    dark: true,
    background: 0x1f2123,
    min_zoom: 0,
    max_zoom: 20,
    tileSize: 256,
    tiles: [GEOAPIFY_DARK_GREY_2X],
    attribution: GEOAPIFY_ATTRIBUTION,
  },
  {
    key: "carto-light",
    hidden: true,
    label: "CARTO 浅色",
    description: "境外备用",
    group: "境外备用",
    preview: "linear-gradient(135deg, #fafafa 0%, #e4e7e9 100%)",
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
    hidden: true,
    label: "CARTO 深色",
    description: "境外备用",
    group: "境外备用",
    preview: "linear-gradient(135deg, #252b34 0%, #0e1217 100%)",
    style_name: "carto-dark-2x",
    // 暗底图：UI 随之切换暗色模式（utils/uiTheme.js 消费；未标记时按 background 亮度推断）
    dark: true,
    background: 0x12161d,
    min_zoom: 0,
    max_zoom: 18,
    tileSize: 256,
    tiles: [CARTO_DARK_2X],
    attribution: '&copy; <a href="https://carto.com/attributions">CARTO</a>',
  },
  {
    key: "carto-voyager",
    hidden: true,
    label: "CARTO Voyager",
    description: "境外备用",
    group: "境外备用",
    preview: "linear-gradient(135deg, #f4efe3 0%, #c9ddd8 100%)",
    style_name: "carto-voyager-2x",
    background: 0xf3f0e8,
    min_zoom: 0,
    max_zoom: 18,
    tileSize: 256,
    tiles: [CARTO_VOYAGER_2X],
    attribution: '&copy; <a href="https://carto.com/attributions">CARTO</a>',
  },
];
window.DEFAULT_BASEMAP_KEY = appConfig.defaultBasemapKey || "esri-dark";
window.getSelectedBaseMapStyle = function getSelectedBaseMapStyle() {
  const options = window.BASEMAP_OPTIONS || [];
  const visibleOptions = options.filter((item) => !item.hidden);
  let key = window.DEFAULT_BASEMAP_KEY || "esri-dark";
  try {
    key = window.localStorage?.getItem(window.BASEMAP_STORAGE_KEY) || key;
  } catch (error) {
    // Ignore storage access failures and fall back to deployment config.
  }
  return visibleOptions.find((item) => item.key === key)
    || visibleOptions.find((item) => item.key === window.DEFAULT_BASEMAP_KEY)
    || visibleOptions[0]
    || {};
};
window.MAP_LAYER_STYLE = [
  window.getSelectedBaseMapStyle(),
  {
    style_name: "online-esri-light",
    background: 0xe9ecef,
    min_zoom: 0,
    max_zoom: 16,
    getUrl() {
      if (appConfig.mapTileUrlTemplate) {
        return tileUrlFromTemplate(appConfig.mapTileUrlTemplate, {
          z: this.zoom,
          x: this.row,
          y: this.col,
        });
      }
      return tileUrlFromTemplate(ESRI_LIGHT_GRAY, {
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
  minZoom: 12,
  maxFeatures: Number(appConfig.cityBuildingsMaxFeatures) || 20000,
  prefetchMeters: 900,
  maxViewDistanceScale: 0.9,
  maxViewDistanceMeters: 6000,
  color: 0xc9d6e2,
  minHeight: 3,
  shadeByHeight: true,
  shadowOpacity: 0.14,
};
