import request from "@/utils/request";

// 轨迹演示数据
// POST /pt/data/trajectory
export function dataTrajectory(data, config = {}) {
  return request({
    url: `/pt/data/trajectory`,
    method: "POST",
    data,
    ...config,
  });
}

// 轨迹演示分块数据
// POST /pt/data/trajectory/chunk?start=28800
export function dataTrajectoryChunk(data, start, config = {}) {
  return request({
    url: `/pt/data/trajectory/chunk`,
    method: "POST",
    params: { start },
    data,
    ...config,
  });
}

// 轨迹演示二进制分块数据
// GET /pt/data/trajectory/chunk.bin?datasource=xxx&start=28800
// 用 GET + 后端 immutable 缓存，使浏览器/Service Worker 可命中本地，重复访问零回源。
export function dataTrajectoryChunkBinary(data, start, config = {}) {
  return request({
    url: `/pt/data/trajectory/chunk.bin`,
    method: "GET",
    params: { datasource: data?.datasource, start },
    responseType: "arraybuffer",
    ...config,
  });
}
