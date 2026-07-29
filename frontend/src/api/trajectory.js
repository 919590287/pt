import request from "@/utils/request";
import { getRealVehicleManifest, isRealDatasource, realLocalResponse } from "@/utils/realPassengerFlow.js";

// 轨迹演示数据
// POST /pt/data/trajectory
export function dataTrajectory(data, config = {}) {
  if (isRealDatasource(data?.datasource)) return realLocalResponse(() => getRealVehicleManifest(data.datasource));
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

// 轨迹演示二进制分块数据（新缓存默认 30 秒/块，旧缓存仍由 manifest 声明块长）。
// GET /pt/data/trajectory/chunk.bin?datasource=xxx&start=28800&rev=xxx
// immutable 资源的 URL 必须携带本次缓存代际 rev；重建后 rev 改变，浏览器不会复用旧块。
export function dataTrajectoryChunkBinary(data, start, revision, config = {}) {
  // 兼容旧签名 (data, start, config)：新旧前端资源并存或测试直接调用 API
  // 时，不会把 axios 配置对象序列化成错误的 rev 查询参数。
  const legacyConfig = revision && typeof revision === "object" ? revision : null;
  const requestConfig = legacyConfig || config;
  const cacheRevision = legacyConfig ? undefined : revision;
  return request({
    url: `/pt/data/trajectory/chunk.bin`,
    method: "GET",
    params: { datasource: data?.datasource, start, rev: cacheRevision || undefined },
    responseType: "arraybuffer",
    ...requestConfig,
  });
}

// 10s × 固定空间网格的视口块（服务端底层为 30s 容器）。bounds 由 manifest.spatial.tileSizeMeters 量化，
// 因此 URL 是稳定的 immutable 资源，小幅平移可直接复用浏览器/IndexedDB 缓存。
export function dataTrajectoryViewportBinary(data, start, bounds, revision, config = {}, windowSeconds = 10) {
  return request({
    url: `/pt/data/trajectory/viewport.bin`,
    method: "GET",
    params: {
      datasource: data?.datasource,
      start,
      windowSeconds,
      visibilityMode: "all",
      minX: bounds?.minX,
      minY: bounds?.minY,
      maxX: bounds?.maxX,
      maxY: bounds?.maxY,
      rev: revision || undefined,
    },
    responseType: "arraybuffer",
    ...config,
  });
}

// 任意时刻的视口轨迹快照：随机跳转先取 1–2 秒小快照，完整时间块随后在后台接管播放。
export function dataTrajectoryFrameBinary(data, time, options = {}, config = {}) {
  return request({
    url: `/pt/data/trajectory/frame.bin`,
    method: "GET",
    params: {
      datasource: data?.datasource,
      time: Math.max(0, Math.floor(Number(time) || 0)),
      bucketSeconds: options.bucketSeconds,
      visibilityMode: options.visibilityMode,
      minX: options.bounds?.minX,
      minY: options.bounds?.minY,
      maxX: options.bounds?.maxX,
      maxY: options.bounds?.maxY,
      rev: options.revision || undefined,
    },
    responseType: "arraybuffer",
    ...config,
  });
}
