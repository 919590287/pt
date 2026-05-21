import request from "@/utils/request";

// 方案列表
// POST /pt/scheme/schemeList
// 接口ID：450702178
// 接口地址：https://app.apifox.com/link/project/8164431/apis/api-450702178
export function getSchemeList(data) {
  return request({
    url: `/pt/scheme/schemeList`,
    method: "POST",
    data: data,
  });
}

// 模型列表
// POST /pt/scheme/modelList
// 接口ID：450702179
// 接口地址：https://app.apifox.com/link/project/8164431/apis/api-450702179
export function getModelList(data) {
  return request({
    url: `/pt/scheme/modelList`,
    method: "POST",
    data: data,
  });
}

// 加载模型
// POST /pt/scheme/loadModel
// 接口ID：450702180
// 接口地址：https://app.apifox.com/link/project/8164431/apis/api-450702180
export function loadModel(data) {
  return request({
    url: `/pt/scheme/loadModel`,
    method: "POST",
    data: data,
  });
}
