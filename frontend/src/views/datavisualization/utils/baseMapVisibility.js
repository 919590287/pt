const TRANSIT_NETWORK_HIDDEN_TABS = new Set([
  "人口分布监测",
  "公交出行监测",
  "客流走廊监测",
  // 体检评估分析是整屏看板，底图被面板完全盖住，再画线网只是白烧一遍瓦片与着色
  "体检评估分析",
]);

/** 使用独立专题图层、无需叠加公共公交/地铁线网的监测模块。 */
export function hidesTransitNetwork(tab) {
  return TRANSIT_NETWORK_HIDDEN_TABS.has(String(tab || ""));
}
