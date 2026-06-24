import request from "@/utils/request";

// 全部站点
// POST /pt/facility/facilityAll
// 接口ID：450702185
// 接口地址：https://app.apifox.com/link/project/8164431/apis/api-450702185
export function getFacilityAll(data) {
  return request({
    url: `/pt/facility/facilityAll`,
    method: 'POST',
    data: data
  })
}

export function getStationPanel(data, config = {}) {
  return request({
    url: `/pt/facility/stationPanel`,
    method: 'POST',
    data: data,
    ...config
  })
}
