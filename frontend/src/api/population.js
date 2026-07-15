import request from "@/utils/request";

// 人口分布监测：全网汇总（scale/栅格元信息/人数口径；未就绪返回 { status: "generating" }）
// POST /pt/population/summary
export function getPopulationSummary(data, config = {}) {
  return request({
    url: `/pt/population/summary`,
    method: "POST",
    data: data,
    ...config,
  });
}

// 人口分布监测：按街道聚合（176 街道全量抽样人数 + totals；未就绪返回 generating）
// POST /pt/population/streets
export function getPopulationStreets(data, config = {}) {
  return request({
    url: `/pt/population/streets`,
    method: "POST",
    data: data,
    ...config,
  });
}

// 人口分布监测：100m 栅格二进制表（ETag/immutable 由后端下发，浏览器 HTTP 缓存自动 304）
// GET /pt/population/grid.bin?datasource=&v=
export function getPopulationGridBinary(data, config = {}) {
  return request({
    url: `/pt/population/grid.bin`,
    method: "GET",
    params: data,
    responseType: "arraybuffer",
    ...config,
  });
}

// 街道边界面（模型无关静态资源，WGS84 FeatureCollection，后端预压缩 gzip 直出）
// GET /pt/population/streets.geojson
export function getStreetsGeojson(config = {}) {
  return request({
    url: `/pt/population/streets.geojson`,
    method: "GET",
    ...config,
  });
}
