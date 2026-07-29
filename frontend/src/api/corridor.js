import request from "@/utils/request";
import {
  getRealCorridorLinks,
  getRealCorridorNames,
  getRealCorridorSummary,
  isRealDatasource,
  realLocalResponse,
} from "@/utils/realPassengerFlow.js";

// 客流走廊监测：全网汇总（口径参数/路段与线路计数；未就绪返回 { status: "generating" }）
// POST /pt/corridor/summary
export function getCorridorSummary(data, config = {}) {
  if (isRealDatasource(data?.datasource)) return realLocalResponse(() => getRealCorridorSummary(data.datasource));
  return request({
    url: `/pt/corridor/summary`,
    method: "POST",
    data: data,
    ...config,
  });
}

// 客流走廊监测：路名字典 + 街道 district 数组（nameIdx/street 列的解码表；未就绪返回 generating）
// POST /pt/corridor/names
export function getCorridorNames(data, config = {}) {
  if (isRealDatasource(data?.datasource)) return realLocalResponse(() => getRealCorridorNames(data.datasource));
  return request({
    url: `/pt/corridor/names`,
    method: "POST",
    data: data,
    ...config,
  });
}

// 客流走廊监测：路段二进制表（PCRD 契约，系数升序；ETag/immutable 由后端下发）
// GET /pt/corridor/links.bin?datasource=&v=
export function getCorridorLinksBinary(data, config = {}) {
  if (isRealDatasource(data?.datasource)) return realLocalResponse(() => getRealCorridorLinks(data.datasource));
  return request({
    url: `/pt/corridor/links.bin`,
    method: "GET",
    params: data,
    responseType: "arraybuffer",
    ...config,
  });
}
