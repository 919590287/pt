(function () {
  var backendPort = "8090";

  window.APP_CONFIG = Object.assign({}, window.APP_CONFIG || {}, {
    backendPort: backendPort,
    apiBaseUrl: window.location.protocol + "//" + window.location.hostname + ":" + backendPort,
    vehicleModelsBaseUrl: "/models/vehicles",
    cityBuildingsEnabled: true,
    cityBuildingsShpPath: "",
    cityBuildingsHeightField: "HEIGHT",
    cityBuildingsMaxFeatures: 20000,
    mapTileUrlTemplate: "",
    defaultBasemapKey: "esri-dark",
    networkLineMinPixels: 0.8,
    networkLineSoftEdgePixels: 0,
    mapPixelRatio: null,
    // 地图显示缩放：null=按视口自动（基准 1430x686，720p~4K 等比适配）；
    // 设为正数可固定缩放（1 表示关闭适配，保持旧行为）
    mapDisplayScale: null,
  });
})();
