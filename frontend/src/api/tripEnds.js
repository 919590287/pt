import request from "@/utils/request";

// 起终点分布监测：全网汇总（scale/栅格元信息/出行与端点口径；未就绪返回 { status: "generating" }）
// POST /pt/tripends/summary
export function getTripEndsSummary(data, config = {}) {
  return request({
    url: `/pt/tripends/summary`,
    method: "POST",
    data: data,
    ...config,
  });
}

// 起终点分布监测：按街道聚合（176 街道全量抽样人次 origin/destination + totals；未就绪返回 generating）
// POST /pt/tripends/streets
export function getTripEndsStreets(data, config = {}) {
  return request({
    url: `/pt/tripends/streets`,
    method: "POST",
    data: data,
    ...config,
  });
}

// 起终点分布监测：100m 栅格二进制表（PGRD 契约，home 列=起点、work 列=终点；
// ETag/immutable 由后端下发，浏览器 HTTP 缓存自动 304）
// GET /pt/tripends/grid.bin?datasource=&v=
export function getTripEndsGridBinary(data, config = {}) {
  return request({
    url: `/pt/tripends/grid.bin`,
    method: "GET",
    params: data,
    responseType: "arraybuffer",
    ...config,
  });
}

// 公交OD监测：街道级 OD 对（有向，pairs=[[o,d,n]]，o/d 为街道要素索引=资源文件序，
// 按人次降序；未就绪返回 generating）
// POST /pt/tripends/od/streets
export function getTripEndsOdStreets(data, config = {}) {
  return request({
    url: `/pt/tripends/od/streets`,
    method: "POST",
    data: data,
    ...config,
  });
}

// 公交OD监测：栅格级 OD 对二进制表（PGOD 契约，人次降序 + 20 万对截断，前端按前缀取 Top-K）
// GET /pt/tripends/od/grid.bin?datasource=&v=
export function getTripEndsOdGridBinary(data, config = {}) {
  return request({
    url: `/pt/tripends/od/grid.bin`,
    method: "GET",
    params: data,
    responseType: "arraybuffer",
    ...config,
  });
}
