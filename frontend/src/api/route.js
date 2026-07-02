import request from "@/utils/request";
// 线路列表
// POST /pt/route/routeList
// 接口ID：450702181
// 接口地址：https://app.apifox.com/link/project/8164431/apis/api-450702181
export function getRouteList(data, config = {}) {
  return request({
    url: `/pt/route/routeList`,
    method: 'POST',
    data: data,
    ...config
  })
}

// 线路信息
// POST /pt/route/routeInfo
// 接口ID：450702182
// 接口地址：https://app.apifox.com/link/project/8164431/apis/api-450702182
export function getRouteInfo(data, config = {}) {
  return request({
    url: `/pt/route/routeInfo`,
    method: 'POST',
    data: data,
    ...config
  })
}

// 线路详情
// POST /pt/route/routeDetail
// 接口ID：450702183
// 接口地址：https://app.apifox.com/link/project/8164431/apis/api-450702183
export function getRouteDetail(data, config = {}) {
  return request({
    url: `/pt/route/routeDetail`,
    method: 'POST',
    data: data,
    ...config
  })
}

// 全部线路
// POST /pt/route/lineAll
// 接口ID：450702184
// 接口地址：https://app.apifox.com/link/project/8164431/apis/api-450702184
export function getLineAll(data, config = {}) {
  return request({
    url: `/pt/route/lineAll`,
    method: 'POST',
    data: data,
    ...config
  })
}

export function getRoutePanel(data, config = {}) {
  return request({
    url: `/pt/route/routePanel`,
    method: 'POST',
    data: data,
    ...config
  })
}

// 总体客流变化（按 bus/metro 聚合的 24 小时客流，轻量接口，替代整包 routePanel）
// POST /pt/route/overallFlow
// data = { status: "ready"|"generating", hourlyByMode: { bus: number[24], metro: number[24] } }
export function getOverallFlow(data, config = {}) {
  return request({
    url: `/pt/route/overallFlow`,
    method: 'POST',
    data: data,
    ...config
  })
}

export function getRoutePanelDetail(data, config = {}) {
  return request({
    url: `/pt/route/routePanelDetail`,
    method: 'POST',
    data: data,
    ...config
  })
}

export function getRouteCandidates(data, config = {}) {
  return request({
    url: `/pt/route/routeCandidates`,
    method: 'POST',
    data: data,
    ...config
  })
}

// 线路瓦片
// POST /pt/route/tile
export function getRouteTile(data, config = {}) {
  return request({
    url: `/pt/route/tile`,
    method: 'POST',
    data: data,
    ...config
  })
}

// GET + ETag/immutable：瓦片内容对固定模型不变，二次访问命中浏览器缓存/304
export function getRouteTileBinary(data, config = {}) {
  return request({
    url: `/pt/route/tile.bin`,
    method: 'GET',
    params: data,
    responseType: 'arraybuffer',
    ...config
  })
}

export function getRouteFullBinary(data, config = {}) {
  return request({
    url: `/pt/route/full.bin`,
    method: 'GET',
    params: data,
    responseType: 'arraybuffer',
    ...config
  })
}
