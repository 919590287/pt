export const RUN_MONITOR_ONBOARDING_STORAGE_KEY = "gjcxfzksh:run-monitor-onboarding:v2";

export const RUN_MONITOR_ONBOARDING_STEPS = [
  {
    id: "intro",
    title: "从全局看懂公交运行",
    description: "运行监测包含人口分布、公交出行、总体客流、客流走廊、线路、站点、车辆运行和体检评估8大模块。左侧切换功能，中间地图定位空间对象，右侧看对应数据看板。",
    centered: true,
  },
  {
    id: "modules",
    title: "切换监测模块",
    description: "左侧集中列出8大功能模块。点击模块即可切换分析主题，地图和右侧看板会同步更新。",
    target: '[data-tour="module-navigation"]',
    placement: "right",
    padding: 10,
  },
  {
    id: "dashboard",
    title: "查看数据看板",
    description: "右侧面板汇总当前模块的核心指标、趋势和排名。选中线路、站点或车辆后，这里会自动切换为对象详情。",
    target: '[data-tour="insight-panel"]',
    placement: "left",
    padding: 9,
  },
  {
    id: "map-navigation",
    title: "浏览和点选地图",
    description: "点击＋或－、滚动鼠标中键缩放地图；按住鼠标左键拖动视野，直接点击线路、站点或车辆即可选中并查看详情。",
    target: '[data-tour="map-navigation"]',
    placement: "top",
    padding: 9,
  },
  {
    id: "map-3d",
    title: "切换3D视角",
    description: "点击3D进入倾斜视角，查看建筑、线网和空间分布的层次关系；再次点击即可返回平面视图。",
    target: '[data-tour="map-3d"]',
    placement: "top",
    padding: 7,
  },
  {
    id: "map-reset",
    title: "重置地图视角",
    description: "点击指北针可退出倾斜和旋转状态，让地图恢复正北朝上的标准视角。",
    target: '[data-tour="map-reset"]',
    placement: "top",
    padding: 7,
  },
  {
    id: "district",
    title: "按行政区聚焦",
    description: "点击行政区按钮选择范围，地图、统计和排名会一起切换到对应区域；再次点击可恢复全市。",
    target: '[data-tour="district-switch"]',
    fallbackTarget: '[data-tour="map-controls"]',
    placement: "top",
    padding: 7,
  },
  {
    id: "model",
    title: "选择区域和模型",
    description: "右上角用于确定当前区域、方案和模型。更换对象后，所有运行监测数据会同步切换。",
    target: '[data-tour="model-selector"]',
    placement: "bottom",
    padding: 8,
  },
  {
    id: "source",
    title: "切换仿真与真实数据",
    description: "仿真用于查看模型推演结果，真实用于查看已接入的运营数据。两类数据完成缓存后可直接切换。",
    target: '[data-tour="source-switch"]',
    placement: "bottom",
    padding: 7,
  },
  {
    id: "account",
    title: "管理用户和底图",
    description: "点击右上角头像打开用户管理，可修改账户名称并切换底图；新增的底图配置也会统一出现在底图列表中。",
    target: '[data-tour="user-management"]',
    placement: "bottom",
    padding: 7,
  },
  {
    id: "help",
    title: "随时重新查看引导",
    description: "本引导默认只自动显示一次。以后点击右上角问号，再选择“重新查看新手引导”即可重新打开。",
    target: '[data-tour="onboarding-help"]',
    placement: "bottom",
    padding: 7,
  },
];

export function hasSeenRunMonitorOnboarding(storage = globalThis?.localStorage) {
  try {
    return storage?.getItem(RUN_MONITOR_ONBOARDING_STORAGE_KEY) === "seen";
  } catch {
    return false;
  }
}

export function markRunMonitorOnboardingSeen(storage = globalThis?.localStorage) {
  try {
    storage?.setItem(RUN_MONITOR_ONBOARDING_STORAGE_KEY, "seen");
  } catch {
    // 隐私模式或存储受限时，由页面内的本次访问标记兜底。
  }
}
