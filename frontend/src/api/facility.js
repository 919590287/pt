import request from "@/utils/request";
import {
  getRealFacilityAll,
  isRealDatasource,
  realLocalResponse,
  realPassengerFlowRequest,
} from "@/utils/realPassengerFlow.js";

// 全部站点
// POST /pt/facility/facilityAll
// 接口ID：450702185
// 接口地址：https://app.apifox.com/link/project/8164431/apis/api-450702185
export function getFacilityAll(data, config = {}) {
  if (isRealDatasource(data?.datasource)) {
    return realLocalResponse(() => getRealFacilityAll(data.datasource));
  }
  return request({
    url: `/pt/facility/facilityAll`,
    method: 'POST',
    data: data,
    ...config
  })
}

export function getStationPanel(data, config = {}) {
  if (isRealDatasource(data?.datasource)) return realPassengerFlowRequest("stationPanel", data, config);
  return request({
    url: `/pt/facility/stationPanel`,
    method: 'POST',
    data: data,
    ...config
  })
}

// 单个站点客流面板（与 stationPanel 返回的 stations[stationName] 同构）
// POST /pt/facility/stationPanelDetail，body { datasource, stationName }
// 未就绪时 data = { status: "generating" }；找不到时 data = {}
export function getStationPanelDetail(data, config = {}) {
  if (isRealDatasource(data?.datasource)) return realPassengerFlowRequest("stationPanelDetail", data, config);
  return request({
    url: `/pt/facility/stationPanelDetail`,
    method: 'POST',
    data: data,
    ...config
  })
}
