import request from "@/utils/request";


// 瓦片路网, zoom level13
// POST /pt/network/tile
// 接口ID：450809814
// 接口地址：https://app.apifox.com/link/project/8164431/apis/api-450809814
export function getTileNetwork(data) {
  return request({
    url: `/pt/network/tile`,
    method: 'POST',
    data: data
  })
}

export function getTileNetworkBinary(data) {
  return request({
    url: `/pt/network/tile.bin`,
    method: 'POST',
    data: data,
    responseType: 'arraybuffer'
  })
}

export function getFullNetworkBinary(data) {
  return request({
    url: `/pt/network/full.bin`,
    method: 'POST',
    data: data,
    responseType: 'arraybuffer'
  })
}
