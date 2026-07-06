import request from "@/utils/request";

// ==================== 线网优化模块 /pt/optimization ====================

// 草稿
export function optDraftList(data, config = {}) {
  return request({ url: `/pt/optimization/draft/list`, method: "POST", data, ...config });
}

export function optDraftSave(data, config = {}) {
  return request({ url: `/pt/optimization/draft/save`, method: "POST", data, silentError: true, ...config });
}

export function optDraftGet(data, config = {}) {
  return request({ url: `/pt/optimization/draft/get`, method: "POST", data, ...config });
}

export function optDraftDelete(data, config = {}) {
  return request({ url: `/pt/optimization/draft/delete`, method: "POST", data, ...config });
}

export function optDraftCopy(data, config = {}) {
  return request({ url: `/pt/optimization/draft/copy`, method: "POST", data, ...config });
}

// 编辑辅助
export function optAreaStats(data, config = {}) {
  return request({ url: `/pt/optimization/areaStats`, method: "POST", data, silentError: true, ...config });
}

export function optSnapPoint(data, config = {}) {
  return request({ url: `/pt/optimization/snapPoint`, method: "POST", data, silentError: true, ...config });
}

export function optSnapRoute(data, config = {}) {
  return request({ url: `/pt/optimization/snapRoute`, method: "POST", data, silentError: true, ...config });
}

export function optRoadNetwork(data, config = {}) {
  return request({ url: `/pt/optimization/roadNetwork`, method: "POST", data, silentError: true, timeout: 1000 * 60, ...config });
}

export function optValidate(data, config = {}) {
  return request({ url: `/pt/optimization/validate`, method: "POST", data, ...config });
}

// 生成与运行
export function optGenerate(data, config = {}) {
  return request({ url: `/pt/optimization/generate`, method: "POST", data, timeout: 1000 * 120, ...config });
}

export function optJobStatus(data, config = {}) {
  return request({ url: `/pt/optimization/jobStatus`, method: "POST", data, silentError: true, ...config });
}

export function optJobCancel(data, config = {}) {
  return request({ url: `/pt/optimization/jobCancel`, method: "POST", data, ...config });
}

export function optJobRetry(data, config = {}) {
  return request({ url: `/pt/optimization/jobRetry`, method: "POST", data, ...config });
}

export function optJobCleanup(data, config = {}) {
  return request({ url: `/pt/optimization/jobCleanup`, method: "POST", data, ...config });
}
