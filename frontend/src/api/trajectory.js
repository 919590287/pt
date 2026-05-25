import request from "@/utils/request";

// 轨迹演示数据
// POST /pt/data/trajectory
export function dataTrajectory(data) {
  return request({
    url: `/pt/data/trajectory`,
    method: "POST",
    data,
  });
}

// 轨迹演示分块数据
// POST /pt/data/trajectory/chunk?start=28800
export function dataTrajectoryChunk(data, start) {
  return request({
    url: `/pt/data/trajectory/chunk`,
    method: "POST",
    params: { start },
    data,
  });
}

// 轨迹演示二进制分块数据
// POST /pt/data/trajectory/chunk.bin?start=28800
export function dataTrajectoryChunkBinary(data, start) {
  return request({
    url: `/pt/data/trajectory/chunk.bin`,
    method: "POST",
    params: { start },
    data,
    responseType: "arraybuffer",
  });
}
