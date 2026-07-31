const TRANSIT_NETWORK_HIDDEN_TABS = new Set([
  "人口分布监测",
  "公交出行监测",
  "客流走廊监测",
]);

/** 使用独立专题图层、无需叠加公共公交/地铁线网的监测模块。 */
export function hidesTransitNetwork(tab) {
  return TRANSIT_NETWORK_HIDDEN_TABS.has(String(tab || ""));
}
