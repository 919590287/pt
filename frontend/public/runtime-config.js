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
    networkLineMinPixels: 0.8,
  });
})();
