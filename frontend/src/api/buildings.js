import request from "@/utils/request";

// 按当前视野查询建筑物
// POST /pt/buildings/query
export function getBuildingTile(data, config = {}) {
  return request({
    url: `/pt/buildings/query`,
    method: "POST",
    data,
    ...config,
  });
}
