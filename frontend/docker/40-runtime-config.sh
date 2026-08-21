#!/bin/sh
# 容器启动时按环境变量生成 runtime-config.js。
#
# 为什么放在运行时而不是构建时：这样同一个镜像可以直接在测试环境和生产环境之间
# 平移，换 IP、换域名、调地图参数都只需改环境变量重启容器，不必重新构建前端。
# 官方 nginx 镜像会在启动 nginx 之前依次执行 /docker-entrypoint.d/*.sh。

set -eu

TARGET="/usr/share/nginx/html/runtime-config.js"

# apiBaseUrl 默认 "/"：前端和后端由同一个 Nginx 以同源方式提供，
# axios 的 baseURL 取 "/" 后所有请求都是相对路径，换域名零改动。
# 注意不能留空字符串——前端代码在 apiBaseUrl 为假值时会退回拼
# "当前域名:8090"（utils/request.js:13-17），那样就绕过 Nginx 了。
: "${APP_API_BASE_URL:=/}"
: "${APP_BACKEND_PORT:=8090}"
: "${APP_VEHICLE_MODELS_BASE_URL:=/models/vehicles}"

# 下面几项是后端侧的路径/参数，前端只负责随请求带给后端。
# 因此 shp 路径必须写成"在 backend 容器里看到的路径"。
: "${APP_CITY_BUILDINGS_ENABLED:=true}"
: "${APP_CITY_BUILDINGS_SHP_PATH:=}"
: "${APP_CITY_BUILDINGS_HEIGHT_FIELD:=HEIGHT}"
: "${APP_CITY_BUILDINGS_MAX_FEATURES:=20000}"

: "${APP_MAP_TILE_URL_TEMPLATE:=}"
: "${APP_MAP_BASEMAP_DEFAULT:=esri-dark}"
: "${APP_NETWORK_LINE_MIN_PIXELS:=0.8}"
: "${APP_NETWORK_LINE_SOFT_EDGE_PIXELS:=0.75}"
: "${APP_MAP_PIXEL_RATIO:=}"
: "${APP_MAP_DISPLAY_SCALE:=}"

cat > "$TARGET" <<EOF
(function () {
  window.APP_CONFIG = Object.assign({}, window.APP_CONFIG || {}, {
    backendPort: "${APP_BACKEND_PORT}",
    apiBaseUrl: "${APP_API_BASE_URL}",
    vehicleModelsBaseUrl: "${APP_VEHICLE_MODELS_BASE_URL}",
    cityBuildingsEnabled: ${APP_CITY_BUILDINGS_ENABLED},
    cityBuildingsShpPath: "${APP_CITY_BUILDINGS_SHP_PATH}",
    cityBuildingsHeightField: "${APP_CITY_BUILDINGS_HEIGHT_FIELD}",
    cityBuildingsMaxFeatures: Number("${APP_CITY_BUILDINGS_MAX_FEATURES}") || 20000,
    mapTileUrlTemplate: "${APP_MAP_TILE_URL_TEMPLATE}",
    defaultBasemapKey: "${APP_MAP_BASEMAP_DEFAULT}",
    networkLineMinPixels: Number("${APP_NETWORK_LINE_MIN_PIXELS}") || 0.8,
    networkLineSoftEdgePixels: Number("${APP_NETWORK_LINE_SOFT_EDGE_PIXELS}") || 0,
    mapPixelRatio: Number("${APP_MAP_PIXEL_RATIO}") || null,
    // 地图显示缩放：null=按视口自动适配（720p~4K 等比），设正数可固定
    mapDisplayScale: Number("${APP_MAP_DISPLAY_SCALE}") || null,
  });
})();
EOF

echo "[40-runtime-config] wrote $TARGET (apiBaseUrl=${APP_API_BASE_URL})"
