import request from "@/utils/request";

// 车辆运行监测：路段公交车速汇总（口径参数/路名字典/街道 district；未就绪返回 { status: "generating" }）
// POST /pt/linkspeed/summary
export function getLinkSpeedSummary(data, config = {}) {
  return request({
    url: `/pt/linkspeed/summary`,
    method: "POST",
    data: data,
    ...config,
  });
}

// 车辆运行监测：链路车速矩阵二进制（PLSP 契约；ETag/immutable 由后端下发）
// GET /pt/linkspeed/matrix.bin?datasource=&v=
export function getLinkSpeedMatrixBinary(data, config = {}) {
  return request({
    url: `/pt/linkspeed/matrix.bin`,
    method: "GET",
    params: data,
    responseType: "arraybuffer",
    ...config,
  });
}
