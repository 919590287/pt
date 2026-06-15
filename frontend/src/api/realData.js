import request from "@/utils/request";

export function getRealDataAreaList(config = {}) {
  return request({
    url: "/pt/real-data/areaList",
    method: "POST",
    ...config,
  });
}

export function getAdminDistricts(data, config = {}) {
  return request({
    url: "/pt/real-data/adminDistricts",
    method: "POST",
    data,
    timeout: 1000 * 60 * 2,
    ...config,
  });
}

export function getBusLineStation(data, config = {}) {
  return request({
    url: "/pt/real-data/busLineStation",
    method: "POST",
    data,
    timeout: 1000 * 60 * 5,
    ...config,
  });
}

export function commitRealDataEdits(data, config = {}) {
  return request({
    url: "/pt/real-data/commitEdits",
    method: "POST",
    data,
    timeout: 1000 * 60 * 5,
    ...config,
  });
}

export function compareRealDataShp(data, config = {}) {
  // 不要手动指定 Content-Type：data 为 FormData 时必须由浏览器自动生成带 boundary 的
  // multipart/form-data 请求头，手动写死会缺少 boundary，导致后端解析 multipart 失败而报错。
  return request({
    url: "/pt/real-data/compareUpload",
    method: "POST",
    data,
    timeout: 1000 * 60 * 5,
    ...config,
  });
}

export function getRealDataHistory(data, config = {}) {
  return request({
    url: "/pt/real-data/history",
    method: "POST",
    data,
    timeout: 1000 * 60 * 5,
    ...config,
  });
}

export function exportRealDataVersion(data, config = {}) {
  return request({
    url: "/pt/real-data/export",
    method: "POST",
    data,
    responseType: "blob",
    timeout: 1000 * 60 * 5,
    ...config,
  });
}
