import request from "@/utils/request";

// 换乘分析：全网汇总（指标卡/首屏直出；未就绪返回 { status: "generating" }）
// POST /pt/transfer/summary
export function getTransferSummary(data, config = {}) {
  return request({
    url: `/pt/transfer/summary`,
    method: "POST",
    data: data,
    ...config,
  });
}

// 换乘分析：字典（hubs/busLines/metroLines/busStops/metroStops + scale + params）
// POST /pt/transfer/dict
export function getTransferDict(data, config = {}) {
  return request({
    url: `/pt/transfer/dict`,
    method: "POST",
    data: data,
    ...config,
  });
}

// 换乘分析：列式事件表（二进制，ETag/immutable 由后端下发，浏览器 HTTP 缓存自动 304）
// GET /pt/transfer/events.bin?datasource=&v=
export function getTransferEventsBinary(data, config = {}) {
  return request({
    url: `/pt/transfer/events.bin`,
    method: "GET",
    params: data,
    responseType: "arraybuffer",
    ...config,
  });
}
