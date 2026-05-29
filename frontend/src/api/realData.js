import request from "@/utils/request";

export function getRealDataAreaList(config = {}) {
  return request({
    url: "/pt/real-data/areaList",
    method: "POST",
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
  return request({
    url: "/pt/real-data/compareUpload",
    method: "POST",
    data,
    timeout: 1000 * 60 * 5,
    headers: {
      "Content-Type": "multipart/form-data",
      ...(config.headers || {}),
    },
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
