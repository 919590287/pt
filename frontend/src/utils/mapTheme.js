/**
 * 地图主题图层统一视觉令牌（与 datamanagement/tokens.css 的"高端蓝玻璃"体系对齐）。
 *
 * 平台锚点：强调蓝 #0071e3、墨色 #1c2024、CARTO Positron 亮色底图。
 * 地图配色策略：
 *  - 常态线网用低饱和钢青蓝，作为"电路板"式底纹退后于数据；
 *  - 选中/方向语义用高饱和橙（上行）与强调蓝（下行），光晕一律用同色系
 *    半透明宽线叠加（backlit 效果），不引入第三种高亮色相；
 *  - 场站徽标用靛蓝，与站点钢蓝同族但可区分。
 *
 * 消费方式：maplibre paint 取 *.css（十六进制字符串），deck.gl/THREE 取
 * hexNumber()/hexToRgbArray() 转换后的数值。
 */

// ---- 基础色 ----------------------------------------------------------------

export const MAP_THEME = {
  /** 公交线网常态（未选中） */
  network: {
    line: "#3d6ea6", // 钢青蓝：比强调蓝低饱和，退后作底纹
    lineOpacity: 0.72,
    dimmed: "#b3c2d6", // 选中场景下其余线路的淡化色（蓝灰）
    dimmedOpacity: 0.38,
    outside: "#8a929e", // 区域外要素的灰色：比 dimmed 更实，作可见的上下文而非隐没
    outsideOpacity: 0.7,
    casing: "#ffffff", // 白描边，让线在浅色底图上更利落
    casingOpacity: 0.6,
  },

  /** 选中线路（方向语义） */
  route: {
    up: "#f97316", // 上行主线（橙）
    upHalo: "#fb923c", // 上行光晕：同色系浅橙，替代旧黄色 #facc15
    down: "#0071e3", // 下行主线（平台强调蓝）
    downHalo: "#54a8ff", // 下行光晕：同色系浅蓝
    transfer: "#0071e3", // 换乘线（低透明度绘制）
    // 关联线路模式（客流分析·线路客流）选中线路的黄色高亮：浅底图上亮黄需深琥珀描边压住轮廓。
    // 黄色为该模式的专属焦点色（业务要求），不并入 up/down 方向语义，勿在其他场景复用。
    transferSelected: "#facc15",
    transferSelectedCasing: "#d97706",
    haloWidthRatio: 2.3, // 光晕宽度 = 主线宽 × 该系数
    haloOpacity: 0.28,
    related: "#8fa8c8", // 底图关联线（选中场景下的联络线）
  },

  /** 站点 */
  station: {
    ring: "#33608f", // 常态站点圆环（钢蓝加深，保证小尺寸下清晰）
    disc: "#ffffff",
    selected: "#f97316", // 选中站点（与选中线路同橙）
    label: "#1f3140", // 站名文字（冷墨色）
    labelHalo: "rgba(250,252,255,0.94)",
    subway: "#dc4c5d", // 地铁制式（与 element danger 对齐）
  },

  /** 场站（车场/枢纽徽标） */
  depot: {
    from: "#6366f1", // 徽标渐变起（靛蓝）
    to: "#4338ca", // 徽标渐变止（深靛）
    stroke: "#ffffff",
  },

  /** 地铁线 */
  metro: {
    line: "#dc4c5d",
  },

  /** OD 连接线（方向与选中线路一致） */
  od: {
    up: "#f97316",
    down: "#0071e3",
  },

  /** 换乘分析（公交↔地铁；方向色沿用 route.up/down 语义族） */
  transfer: {
    busToMetro: "#f97316", // 公交→地铁（橙，沿 route.up）
    metroToBus: "#0071e3", // 地铁→公交（蓝，沿 route.down）
    hubRing: "#33608f", // 枢纽气泡描边（钢蓝，与站点圆环同族）
    hubSelected: "#f97316", // 选中枢纽描边
    longStroke: "#dc4c5d", // 超长换乘枢纽静态描边（与地铁红同族，不闪烁）
    warn: "#dc4c5d", // 长换乘警示（图表着色）
    hubScale: "GnYlRd", // 枢纽时间色带（colorSchemes schemeKey，沿 stationHeat 风格）
    deltaMore: "#0071e3", // P1 方案对比：换乘量增（中性蓝）
    deltaLess: "#f97316", // P1 方案对比：换乘量减（中性橙）
    deltaBetter: "#2f9e6e", // P1 方案对比：时间类改善
    deltaWorse: "#dc4c5d", // P1 方案对比：时间类恶化
  },

  /**
   * 人口分布监测（公交出行监测模块）。
   * 九级密度分级（人/km²），对齐业务样张的绿→黄→红密度制图惯例；
   * 中段两级已按 CVD 校验微调（黄绿加深、黄提饱和，deutan 最差相邻 ΔE 1.3→8.1），
   * 类别辨识由图例区间 + 右侧街道表格作二次编码兜底。
   */
  population: {
    /** 分级断点（人/km²），9 级 = 8 断点 + 开口最高级 */
    breaks: [500, 1500, 3000, 5000, 10000, 20000, 30000, 40000],
    /** 与 breaks 对应的 9 级填充色，第 0 级（<500）刻意接近底图仅示"有人" */
    ramp: ["#eef4e4", "#1a9850", "#66bd63", "#a6d96a", "#d3e884", "#f5ce3e", "#fdae61", "#f46d43", "#d73027"],
    /** 第 0 级/其余级的填充透明度（0-255，deck.gl alpha 通道） */
    alphaLow: 120,
    alpha: 205,
    streetLine: "#33475e", // 街道边界描边（冷墨蓝，与站名文字同族）
    streetLabel: "#1f3140",
    streetLabelHalo: "rgba(250,252,255,0.94)",
  },

  /**
   * 出行分布监测（公交出行监测模块，人口分布的同级子模块；原起终点分布监测）。
   * 色带/透明度/街道 token 与 population 同值（同一模块族的视觉语言）；
   * 断点独立：按「单格人次 ×100 折算成人次/km²」定义（5/20/80/300/1k/3k/10k/30k 人次/格），
   * 图例以人次/格展示。断点定于站点端口径时代（端点集中在站点格）；tripends-v4 端点改为
   * 活动位置后分布更散、单格量级可能明显下降，见真实数据后按需下调。
   */
  tripEnds: {
    /** 分级断点（人次/km²，= 单格人次 ×100），9 级 = 8 断点 + 开口最高级 */
    breaks: [500, 2000, 8000, 30000, 100000, 300000, 1000000, 3000000],
    ramp: ["#eef4e4", "#1a9850", "#66bd63", "#a6d96a", "#d3e884", "#f5ce3e", "#fdae61", "#f46d43", "#d73027"],
    alphaLow: 120,
    alpha: 205,
    streetLine: "#33475e",
    streetLabel: "#1f3140",
    streetLabelHalo: "rgba(250,252,255,0.94)",
  },

  /**
   * 公交OD监测（公交出行监测模块第三子模块）：OD 期望线分级配色。
   * 沿密度制图惯例绿→黄→红 8 级（去掉密度带的第 0 级淡底色——细线在浅底图上需要足量对比），
   * 断点由前端按当前显示流量集合分位计算（quantileBreaks），级数随数据自适应（取 ramp 尾部）。
   * 线宽（px）与色带一一对应，低流量细、主走廊粗（参考期望线制图惯例）。
   */
  busOd: {
    ramp: ["#1a9850", "#66bd63", "#a6d96a", "#d3e884", "#f5ce3e", "#fdae61", "#f46d43", "#d73027"],
    widths: [1.2, 1.6, 2.2, 2.8, 3.6, 4.6, 5.8, 7.2],
    alpha: 200,
    streetLine: "#33475e",
    streetLabel: "#1f3140",
    streetLabelHalo: "rgba(250,252,255,0.94)",
  },

  /**
   * 客流走廊监测 · 线路重复系数：公交经过路段按重复系数五级分色（浅青→蓝→深蓝→蓝紫→紫红，
   * 对齐业务样张的阻抗线配色惯例），固定断点 1-2 / 3-5 / 6-10 / 11-15 / >15；
   * 线宽与色带一一对应，高系数走廊加粗。系数=不同公交线路数，无扩样语义。
   */
  corridor: {
    breaks: [2, 5, 10, 15],
    ramp: ["#8ed1e6", "#4f7fd9", "#2743a6", "#7a3bd0", "#c026c9"],
    widths: [1.4, 2, 2.8, 3.8, 5],
    alpha: 220,
    /**
     * 公交客流走廊：断面客流带宽图（对齐业务 Transit Flows 样张：连续实心流量带 + 灰色线网底图）。
     * 带宽为**地理米数**（widthUnits:"meters"）——随地图缩放同缩，区级/全市视野下主走廊聚成
     * 粗带、支线自然收细；像素宽度在低缩放会把密集路网糊成一坨（旧实现的"断续+毛刺"观感）。
     * 宽度 = (flow/refFlow)^exponent × maxWidthM：refFlow 取当前范围正流量的 refQuantile 分位。
     * **refQuantile 定版 1（=范围内最大值锚定，2026-07-16 用户反馈"太粗"后回调）**：顶带恒等于
     * maxWidthM，任何行政区观感可预测；分位锚定（<1）时超锚定按同幂外推，区级长尾会把
     * P99 压得很低（南沙 722 vs max 4310），外推 3× 直接爆宽——勿轻易调回。
     * exponent<1 的凹曲线拉升中段流量的可见宽度（长尾分布下线性映射会让多数干道全变
     * 发丝线，样张的"次级走廊仍醒目"靠这条曲线）；极端值由 maxWidthPx 像素封顶兜底。
     * 流量带不透明纯色——半透明会在圆头衔接处叠出深斑；零流量路段不画橙带，
     * 由 base* 灰色细底线兜底（=公交线网轮廓，对齐样张的底网细线）。
     */
    flow: {
      color: "#f08c3c",
      maxWidthM: 1000,
      exponent: 0.6,
      refQuantile: 1,
      maxWidthPx: 36, // 放大到街区级的像素封顶，防止米制粗带遮天蔽日
      minWidthPx: 0.5, // 缩小到全市时正流量支线的可见性保底（发丝线）
      baseColor: "#9aa3ad",
      baseAlpha: 135,
      baseWidthPx: 0.7,
    },
  },

  /**
   * 线网高峰满载率着色（线路客流监测页签的着色指标切换）。
   * 与客流的分位分档不同，满载率有绝对语义：<50 舒适 / 50-70 适中 / 70-85 紧张 /
   * 85-100 饱和 / >100 超载（后端 loadRate 不封顶，超载可见）——固定断点勿改分位。
   * widths 为各档线宽系数（供 line-width 乘算，与客流色阶的 widths 同语义）。
   */
  lineLoadRate: {
    breaks: [50, 70, 85, 100],
    colors: ["#2f9e6e", "#a6d96a", "#f5ce3e", "#f46d43", "#d73027"],
    widths: [1, 1.12, 1.24, 1.36, 1.5],
  },

  /**
   * 车辆运行监测 · 路段公交车速（拥堵路况图层，随播放时钟分时着色）。
   * 绝对速度分档（km/h，导航路况心智：深红=严重拥堵 → 绿=畅通），阈值为公交专用口径
   * （净行驶速度已剔除站点停靠、仍含信号延误，整体低于小汽车路况标准）——固定断点勿改分位。
   * colors/labels 按速度升序一一对应：<8 / 8-12 / 12-18 / 18-25 / ≥25。
   */
  linkSpeed: {
    breaks: [8, 12, 18, 25],
    colors: ["#a50f15", "#d73027", "#f46d43", "#f5ce3e", "#2f9e6e"],
    labels: ["严重拥堵", "中度拥堵", "轻度拥堵", "基本畅通", "畅通"],
    alpha: 235,
    widthPx: 2.4,
    /** 当前时段无班次经过的公交链路：灰色同宽保留——线网轮廓完整，灰=诚实的"无数据"而非猜测 */
    noData: "#9aa3ad",
    noDataAlpha: 130,
    /** 拥堵 TOP 榜点击定位的高亮：垫在车速线下方、宽出成描边（亮蓝与红/黄档均高对比） */
    highlight: "#2166f3",
    highlightAlpha: 235,
    highlightWidthPx: 9,
  },

  /** 默认色带（colorSchemes.js 的 schemeKey） */
  schemes: {
    stationHeat: "GnYlRd", // 站点热力：绿→黄→红连续密度色带
    segmentFlow: "gnylrd", // 断面客流：绿-黄-红（沿用业务口径）
  },
};

// ---- 工具 ------------------------------------------------------------------

/** "#3d6ea6" -> 0x3d6ea6（deck.gl / THREE 数值色） */
export function hexNumber(hex) {
  return Number.parseInt(String(hex).replace("#", ""), 16);
}

/** "#3d6ea6" -> [61, 110, 166]（deck.gl RGB 数组，可附加 alpha） */
export function hexToRgbArray(hex, alpha) {
  const value = hexNumber(hex);
  const rgb = [(value >> 16) & 255, (value >> 8) & 255, value & 255];
  if (alpha != null) rgb.push(alpha);
  return rgb;
}

/** "#3d6ea6", 0.4 -> "rgba(61,110,166,0.4)"（maplibre paint 字符串） */
export function hexToRgba(hex, alpha = 1) {
  const [r, g, b] = hexToRgbArray(hex);
  return `rgba(${r},${g},${b},${alpha})`;
}
