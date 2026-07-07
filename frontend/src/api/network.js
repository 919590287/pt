import request from "@/utils/request";


// 瓦片路网, zoom level13
// POST /pt/network/tile
// 接口ID：450809814
// 接口地址：https://app.apifox.com/link/project/8164431/apis/api-450809814
export function getTileNetwork(data, config = {}) {
  return request({
    url: `/pt/network/tile`,
    method: 'POST',
    data: data,
    ...config
  })
}

// GET + ETag/immutable：瓦片内容对固定模型不变，二次访问命中浏览器缓存/304
export function getTileNetworkBinary(data, config = {}) {
  return request({
    url: `/pt/network/tile.bin`,
    method: 'GET',
    params: data,
    responseType: 'arraybuffer',
    ...config
  })
}

export function getFullNetworkBinary(data, config = {}) {
  return request({
    url: `/pt/network/full.bin`,
    method: 'GET',
    params: data,
    responseType: 'arraybuffer',
    ...config
  })
}
