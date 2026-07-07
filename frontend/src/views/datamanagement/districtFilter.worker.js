// 行政区裁剪后台线程：持有集合副本，收到 filter 请求后完成四个数据集的过滤与线路截段，
// 把主线程从数十万次点在多边形内/投影计算中解放出来。协议：
//   { type: "setData", collections }            —— 同步数据副本（数据换代/本地编辑后由主线程重发）
//   { type: "filter", requestId, context }      —— context: { name, polygons, bounds }
// 响应：{ type: "filterResult", requestId, name, collections } 或 { ..., error }
import { filterCollectionsByDistrict } from "./districtFilterCore.js";

let collections = null;

self.onmessage = (event) => {
  const message = event?.data || {};
  if (message.type === "setData") {
    collections = message.collections || null;
    return;
  }
  if (message.type !== "filter") return;
  const { requestId, context } = message;
  if (!collections || !context) {
    self.postMessage({ type: "filterResult", requestId, name: context?.name || "", error: "no-data" });
    return;
  }
  try {
    const runtimeContext = {
      name: context.name,
      polygons: context.polygons || [],
      bounds: context.bounds || null,
    };
    const filtered = filterCollectionsByDistrict(collections, runtimeContext);
    self.postMessage({ type: "filterResult", requestId, name: context.name, collections: filtered });
  } catch (error) {
    self.postMessage({ type: "filterResult", requestId, name: context?.name || "", error: String(error?.message || error) });
  }
};
