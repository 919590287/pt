import request from "@/utils/request";

// 数据总览
// POST /pt/data/info
// 接口ID：450702186
// 接口地址：https://app.apifox.com/link/project/8164431/apis/api-450702186
export function dataInfo(data) {
  return request({
    url: `/pt/data/info`,
    method: "POST",
    data: data,
  });
}


// 中心的坐标
// POST /pt/data/center
// 接口ID：451517425
// 接口地址：https://app.apifox.com/link/project/8164431/apis/api-451517425
export function dataCenter(data) {
  return request({
    url: `/pt/data/center`,
    method: "POST",
    data: data,
  });
}
