import request from "@/utils/request";
// 线路列表
// POST /pt/route/routeList
// 接口ID：450702181
// 接口地址：https://app.apifox.com/link/project/8164431/apis/api-450702181
export function getRouteList(data) {
  return request({
    url: `/pt/route/routeList`,
    method: 'POST',
    data: data
  })
}

// 线路信息
// POST /pt/route/routeInfo
// 接口ID：450702182
// 接口地址：https://app.apifox.com/link/project/8164431/apis/api-450702182
export function getRouteInfo(data) {
  return request({
    url: `/pt/route/routeInfo`,
    method: 'POST',
    data: data
  })
}

// 线路详情
// POST /pt/route/routeDetail
// 接口ID：450702183
// 接口地址：https://app.apifox.com/link/project/8164431/apis/api-450702183
export function getRouteDetail(data) {
  return request({
    url: `/pt/route/routeDetail`,
    method: 'POST',
    data: data
  })
}

// 全部线路
// POST /pt/route/lineAll
// 接口ID：450702184
// 接口地址：https://app.apifox.com/link/project/8164431/apis/api-450702184
export function getLineAll(data) {
  return request({
    url: `/pt/route/lineAll`,
    method: 'POST',
    data: data
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
export function getRouteTile(data) {
  return request({
    url: `/pt/route/tile`,
    method: 'POST',
    data: data
  })
}

export function getRouteTileBinary(data) {
  return request({
    url: `/pt/route/tile.bin`,
    method: 'POST',
    data: data,
    responseType: 'arraybuffer'
  })
}

export function getRouteFullBinary(data) {
  return request({
    url: `/pt/route/full.bin`,
    method: 'POST',
    data: data,
    responseType: 'arraybuffer'
  })
}
