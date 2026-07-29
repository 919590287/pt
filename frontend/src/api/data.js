import request from "@/utils/request";
import { isRealDatasource, realPassengerFlowRequest } from "@/utils/realPassengerFlow.js";

// 数据总览
// POST /pt/data/info
// 接口ID：450702186
// 接口地址：https://app.apifox.com/link/project/8164431/apis/api-450702186
export function dataInfo(data, config = {}) {
  return request({
    url: `/pt/data/info`,
    method: "POST",
    data: data,
    ...config,
  });
}


// 体检评估指标（全市/行政区缓存口径；district 默认“全市”）
// POST /pt/data/evaluation
// 返回 { status: "ready"|"generating", values: { czrkmd, gjxwmd, fgl300, wrbyl, cxfdl, cjrzkl, dbczkl,
//   rcxcs, xlfzxxs, xlcfxs, xlmzl, xlklqd, yxsdb, pjhcsj, pjhccs, gjjbbl, cjczmj } }，无法统计的指标为 null
export function dataEvaluation(data, config = {}) {
  if (isRealDatasource(data?.datasource)) return realPassengerFlowRequest("evaluation", data, config);
  return request({
    url: `/pt/data/evaluation`,
    method: "POST",
    data: data,
    ...config,
  });
}

// 中心的坐标
// POST /pt/data/center
// 接口ID：451517425
// 接口地址：https://app.apifox.com/link/project/8164431/apis/api-451517425
export function dataCenter(data, config = {}) {
  if (isRealDatasource(data?.datasource)) return realPassengerFlowRequest("center", data, config);
  return request({
    url: `/pt/data/center`,
    method: "POST",
    data: data,
    ...config,
  });
}
