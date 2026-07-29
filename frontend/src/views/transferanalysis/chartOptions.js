/**
 * 换乘分析 ECharts option 纯函数构建器。
 * 输入 = Worker 聚合结果（模型原始数量，不扩样）+ 主题令牌，输出 = option。
 * 口径提示（设计方案 v2）：
 *  - 分时图按"后序上车时刻（换乘完成时刻）"统计，tooltip 已注明；
 *  - 时间区间分布默认 0-5/5-10/10-15/15-20/20-30 min 五段（30 分钟识别窗口封顶，无 >30 桶）；
 *  - 箱线图五数 = 标准 min/P25/P50/P75/max，P90 以散点叠加，绝不混入五数。
 */

import { chartInk, isDarkTheme } from "@/utils/chartInk";

export const SEGMENT_LABELS = ["0-5分", "5-10分", "10-15分", "15-20分", "20-30分"];

export function minuteHistToSegments(histogramMin) {
  const seg = [0, 0, 0, 0, 0];
  for (let m = 0; m < 30; m++) {
    const v = histogramMin[m] || 0;
    if (m < 5) seg[0] += v;
    else if (m < 10) seg[1] += v;
    else if (m < 15) seg[2] += v;
    else if (m < 20) seg[3] += v;
    else seg[4] += v;
  }
  return seg;
}

export function fmtMin(sec) {
  if (sec == null) return "-";
  return `${(sec / 60).toFixed(1)}分`;
}

export function fmtCount(v) {
  if (v == null) return "-";
  if (v >= 10000) return `${(v / 10000).toFixed(1)}万`;
  return `${Math.round(v)}`;
}

// 默认文本令牌对齐平台亮色玻璃体系（datamanagement/tokens.css 的 --dm2-* 冷中性灰阶）
const BASE_TEXT = {
  axisLabel: "#667085",
  axisLine: "rgba(17,32,58,0.18)",
  splitLine: "rgba(17,32,58,0.07)",
  tooltipBg: "rgba(255,255,255,0.96)",
  tooltipBorder: "rgba(0,113,227,0.22)",
  tooltipText: "#1c2024",
  legendText: "#3b4452",
};

// 暗色中性 chrome（html.dark，跟随底图选择）：文字/分隔线/tooltip 面直取 chartInk 暗色档；
// 本文件独有的加强轴线与强调蓝 tooltip 边框按暗色取值表提亮。亮色分支原样返回 BASE_TEXT，
// 渲染结果逐像素不变；系列色/色带（theme.busToMetro/warn/heatRamp 等）为数据语义，不在此列。
// 调用方（sections 的 computed）读取 isDarkTheme/chartInk 即自动订阅主题切换并重建 option。
function baseText() {
  if (!isDarkTheme.value) return BASE_TEXT;
  const ink = chartInk.value;
  return {
    axisLabel: ink.text,
    axisLine: "rgba(148,180,220,0.28)",
    splitLine: ink.splitLine,
    tooltipBg: ink.tooltipBg,
    tooltipBorder: "rgba(64,156,255,0.26)",
    tooltipText: ink.tooltipText,
    legendText: "#c2cddd",
  };
}

function baseOption(theme, animation) {
  const t = { ...baseText(), ...(theme.chart || {}) };
  return {
    t,
    option: {
      backgroundColor: "transparent",
      animation,
      animationDuration: 360,
      tooltip: {
        trigger: "axis",
        backgroundColor: t.tooltipBg,
        borderColor: t.tooltipBorder,
        borderWidth: 1,
        textStyle: { color: t.tooltipText, fontSize: 12 },
        confine: true,
      },
      grid: { left: 8, right: 12, top: 30, bottom: 4, containLabel: true },
    },
  };
}

function catAxis(t, data, opts = {}) {
  return {
    type: "category",
    data,
    axisLabel: { color: t.axisLabel, fontSize: 11, ...opts.axisLabel },
    axisLine: { lineStyle: { color: t.axisLine } },
    axisTick: { show: false },
    ...opts,
  };
}

function valAxis(t, opts = {}) {
  return {
    type: "value",
    axisLabel: { color: t.axisLabel, fontSize: 11, formatter: opts.fmt },
    splitLine: { lineStyle: { color: t.splitLine } },
    ...opts,
  };
}

/** 双方向分时折线（tooltip 注明按后序上车时刻统计） */
export function dualDirectionLineOption({ labels, busToMetro, metroToBus }, theme, animation) {
  const { t, option } = baseOption(theme, animation);
  option.tooltip.formatter = (params) => {
    const rows = params.map((p) => `${p.marker}${p.seriesName}：${fmtCount(p.value)}人次`).join("<br/>");
    return `${params[0].axisValue}（按后序上车时刻）<br/>${rows}`;
  };
  option.legend = { top: 0, right: 0, textStyle: { color: t.legendText, fontSize: 11 }, itemWidth: 14, itemHeight: 8 };
  option.xAxis = catAxis(t, labels, { boundaryGap: false });
  option.yAxis = valAxis(t, { fmt: fmtCount });
  option.series = [
    {
      name: "公交→地铁",
      type: "line",
      data: busToMetro,
      smooth: 0.25,
      showSymbol: false,
      lineStyle: { width: 2, color: theme.busToMetro },
      itemStyle: { color: theme.busToMetro },
      areaStyle: { opacity: 0.14, color: theme.busToMetro },
    },
    {
      name: "地铁→公交",
      type: "line",
      data: metroToBus,
      smooth: 0.25,
      showSymbol: false,
      lineStyle: { width: 2, color: theme.metroToBus },
      itemStyle: { color: theme.metroToBus },
      areaStyle: { opacity: 0.14, color: theme.metroToBus },
    },
  ];
  return option;
}

/** 方向占比环形 */
export function directionPieOption({ busToMetro, metroToBus }, theme, animation) {
  const { t, option } = baseOption(theme, animation);
  option.tooltip = {
    trigger: "item",
    backgroundColor: t.tooltipBg,
    borderColor: t.tooltipBorder,
    textStyle: { color: t.tooltipText, fontSize: 12 },
    formatter: (p) => `${p.marker}${p.name}：${fmtCount(p.value)}人次（${p.percent}%）`,
  };
  option.legend = { bottom: 0, left: "center", textStyle: { color: t.legendText, fontSize: 11 }, itemWidth: 14, itemHeight: 8 };
  option.series = [
    {
      type: "pie",
      radius: ["52%", "74%"],
      center: ["50%", "44%"],
      label: { show: false },
      data: [
        { name: "公交→地铁", value: busToMetro, itemStyle: { color: theme.busToMetro } },
        { name: "地铁→公交", value: metroToBus, itemStyle: { color: theme.metroToBus } },
      ],
    },
  ];
  delete option.grid;
  return option;
}

/** Top 排名横向条形（value 为模型原始数量）；labelWidth 供全屏放宽类目名截断 */
export function rankBarOption(items, theme, animation, { color, valueLabel = "人次", secondary, labelWidth = 92 } = {}) {
  const { t, option } = baseOption(theme, animation);
  const names = items.map((it) => it.name).reverse();
  const values = items.map((it) => it.value).reverse();
  option.tooltip.trigger = "axis";
  option.tooltip.axisPointer = { type: "shadow" };
  option.tooltip.formatter = (params) => {
    const p = params[0];
    const it = items[items.length - 1 - p.dataIndex];
    let s = `${p.name}<br/>${p.marker}${valueLabel}：${fmtCount(p.value)}`;
    if (secondary && it && it[secondary.key] != null) s += `<br/>${secondary.label}：${secondary.fmt(it[secondary.key])}`;
    return s;
  };
  option.grid = { left: 8, right: 34, top: 6, bottom: 4, containLabel: true };
  option.xAxis = valAxis(t, { fmt: fmtCount });
  option.yAxis = catAxis(t, names, {
    axisLabel: {
      color: t.axisLabel,
      fontSize: 11,
      width: labelWidth,
      overflow: "truncate",
      // 排名图类目名必须全显：图表被压缩时禁止 ECharts 自动跳标签
      interval: 0,
    },
  });
  option.series = [
    {
      type: "bar",
      data: values,
      barMaxWidth: 12,
      itemStyle: { color: color || theme.busToMetro, borderRadius: [0, 3, 3, 0] },
      label: { show: true, position: "right", color: t.axisLabel, fontSize: 10, formatter: (p) => fmtCount(p.value) },
    },
  ];
  return option;
}

/**
 * 公→地 / 地→公共用一根横向堆叠柱的排名图。
 * metric=flow 时统计人次；metric=avgSec 时分别展示两个方向的平均换乘时间。
 */
export function directionStackRankOption(items, theme, animation, { metric = "flow", labelWidth = 92 } = {}) {
  const { t, option } = baseOption(theme, animation);
  const ranked = items
    .slice()
    .sort((a, b) => (metric === "avgSec" ? b.avgSec - a.avgSec : b.flow - a.flow));
  const display = ranked.slice().reverse();
  const isTime = metric === "avgSec";
  const b2m = display.map((item) => (isTime ? item.b2mAvgSec : item.b2m));
  const m2b = display.map((item) => (isTime ? item.m2bAvgSec : item.m2b));
  const fmt = isTime ? fmtMin : fmtCount;
  option.tooltip.trigger = "axis";
  option.tooltip.axisPointer = { type: "shadow" };
  option.tooltip.formatter = (params) => {
    const item = display[params[0]?.dataIndex];
    if (!item) return "";
    const unit = isTime ? "" : "人次";
    const rows = params.map((param) => `${param.marker}${param.seriesName}：${fmt(param.value)}${unit}`).join("<br/>");
    return `${item.name}<br/>${rows}`;
  };
  option.legend = { top: 0, right: 0, textStyle: { color: t.legendText, fontSize: 11 }, itemWidth: 14, itemHeight: 8 };
  option.grid = { left: 8, right: 28, top: 30, bottom: 4, containLabel: true };
  option.xAxis = valAxis(t, { fmt: isTime ? (v) => `${Math.round(v / 60)}分` : fmtCount });
  option.yAxis = catAxis(t, display.map((item) => item.name), {
    axisLabel: { color: t.axisLabel, fontSize: 11, width: labelWidth, overflow: "truncate", interval: 0 },
  });
  option.series = [
    {
      name: "公交→地铁",
      type: "bar",
      stack: "direction",
      data: b2m,
      barMaxWidth: 13,
      itemStyle: { color: theme.busToMetro, borderRadius: [3, 0, 0, 3] },
    },
    {
      name: "地铁→公交",
      type: "bar",
      stack: "direction",
      data: m2b,
      barMaxWidth: 13,
      itemStyle: { color: theme.metroToBus, borderRadius: [0, 3, 3, 0] },
    },
  ];
  return option;
}

/** 换乘时间区间分布柱状（五段口径；>longMin 的段着警示色） */
export function histogramBarOption(segments, theme, animation, { longMin = 15, minuteMode = false, histogramMin } = {}) {
  const { t, option } = baseOption(theme, animation);
  const labels = minuteMode ? Array.from({ length: 30 }, (_, i) => `${i}-${i + 1}`) : SEGMENT_LABELS;
  const data = minuteMode ? histogramMin : segments;
  const boundary = minuteMode ? longMin : [5, 10, 15, 20, 30].findIndex((b) => b > longMin);
  option.tooltip.axisPointer = { type: "shadow" };
  option.tooltip.formatter = (params) => {
    const p = params[0];
    return `${p.name}${minuteMode ? "分钟" : ""}<br/>${p.marker}人次：${fmtCount(p.value)}`;
  };
  option.xAxis = catAxis(t, labels, {
    axisLabel: { color: t.axisLabel, fontSize: minuteMode ? 9 : 11, interval: minuteMode ? 4 : 0 },
  });
  option.yAxis = valAxis(t, { fmt: fmtCount });
  option.series = [
    {
      type: "bar",
      data: data.map((v, i) => ({
        value: v,
        itemStyle: {
          color: (minuteMode ? i >= boundary : i >= boundary && boundary >= 0) ? theme.warn : theme.busToMetro,
          borderRadius: [3, 3, 0, 0],
        },
      })),
      barMaxWidth: minuteMode ? 8 : 26,
      markLine: minuteMode
        ? {
            symbol: "none",
            silent: true,
            lineStyle: { color: theme.warn, type: "dashed" },
            label: { color: theme.warn, fontSize: 10, formatter: `阈值 ${longMin}min` },
            data: [{ xAxis: longMin }],
          }
        : undefined,
    },
  ];
  return option;
}

/** 换乘时间累计分布阶梯线 */
export function cumulativeLineOption(cumulative, theme, animation, { longMin = 15 } = {}) {
  const { t, option } = baseOption(theme, animation);
  option.tooltip.formatter = (params) => {
    const p = params[0];
    return `≤${p.axisValue}分钟<br/>${p.marker}累计占比：${(p.value * 100).toFixed(1)}%`;
  };
  option.xAxis = catAxis(t, Array.from({ length: 30 }, (_, i) => `${i + 1}`), {
    axisLabel: { color: t.axisLabel, fontSize: 10, interval: 4 },
  });
  option.yAxis = valAxis(t, { max: 1, fmt: (v) => `${Math.round(v * 100)}%` });
  option.series = [
    {
      type: "line",
      step: "end",
      data: cumulative,
      showSymbol: false,
      lineStyle: { width: 2, color: theme.metroToBus },
      itemStyle: { color: theme.metroToBus },
      areaStyle: { opacity: 0.1, color: theme.metroToBus },
      markLine: {
        symbol: "none",
        silent: true,
        lineStyle: { color: theme.warn, type: "dashed" },
        label: { color: theme.warn, fontSize: 10, formatter: `${longMin}min` },
        data: [{ xAxis: String(longMin) }],
      },
    },
  ];
  return option;
}

/** Route/方向分组柱状 */
export function routeGroupBarOption(routes, theme, animation) {
  const { t, option } = baseOption(theme, animation);
  option.tooltip.axisPointer = { type: "shadow" };
  option.legend = { top: 0, right: 0, textStyle: { color: t.legendText, fontSize: 11 }, itemWidth: 14, itemHeight: 8 };
  option.xAxis = catAxis(t, routes.map((r) => r.name));
  option.yAxis = valAxis(t, { fmt: fmtCount });
  option.series = [
    { name: "公交→地铁", type: "bar", data: routes.map((r) => r.b2m), barMaxWidth: 18, itemStyle: { color: theme.busToMetro, borderRadius: [3, 3, 0, 0] } },
    { name: "地铁→公交", type: "bar", data: routes.map((r) => r.m2b), barMaxWidth: 18, itemStyle: { color: theme.metroToBus, borderRadius: [3, 3, 0, 0] } },
  ];
  return option;
}

/** 分时换乘量（柱）+ 平均换乘时间（线，右轴） */
export function volumeTimeDualAxisOption({ labels, busToMetro, metroToBus, avgSec }, theme, animation) {
  const { t, option } = baseOption(theme, animation);
  option.tooltip.formatter = (params) => {
    let s = `${params[0].axisValue}（按后序上车时刻）`;
    params.forEach((p) => {
      s += `<br/>${p.marker}${p.seriesName}：${p.seriesType === "line" ? fmtMin(p.value) : `${fmtCount(p.value)}人次`}`;
    });
    return s;
  };
  option.legend = { top: 0, right: 0, textStyle: { color: t.legendText, fontSize: 11 }, itemWidth: 14, itemHeight: 8 };
  option.xAxis = catAxis(t, labels);
  option.yAxis = [valAxis(t, { fmt: fmtCount }), valAxis(t, { fmt: (v) => `${Math.round(v / 60)}分`, splitLine: { show: false } })];
  option.series = [
    {
      name: "换乘人次",
      type: "bar",
      stack: "vol",
      data: labels.map((_, i) => busToMetro[i] + metroToBus[i]),
      barMaxWidth: 14,
      itemStyle: { color: theme.busToMetro, opacity: 0.85, borderRadius: [3, 3, 0, 0] },
    },
    {
      name: "平均换乘时间",
      type: "line",
      yAxisIndex: 1,
      data: avgSec,
      showSymbol: false,
      smooth: 0.25,
      lineStyle: { width: 2, color: theme.warn },
      itemStyle: { color: theme.warn },
    },
  ];
  return option;
}

/** 枢纽 换乘量 vs 平均换乘时间 散点 */
export function hubScatterOption(hubs, theme, animation) {
  const { t, option } = baseOption(theme, animation);
  option.tooltip = {
    trigger: "item",
    backgroundColor: t.tooltipBg,
    borderColor: t.tooltipBorder,
    textStyle: { color: t.tooltipText, fontSize: 12 },
    formatter: (p) => `${p.data.name}<br/>换乘人次：${fmtCount(p.data.value[0])}<br/>平均换乘时间：${fmtMin(p.data.value[1])}`,
  };
  option.xAxis = valAxis(t, { fmt: fmtCount, name: "人次", nameTextStyle: { color: t.axisLabel, fontSize: 10 } });
  option.yAxis = valAxis(t, { fmt: (v) => `${Math.round(v / 60)}分` });
  option.series = [
    {
      type: "scatter",
      data: hubs.map((h) => ({ name: h.name, value: [h.flow, h.avgSec] })),
      symbolSize: (v) => Math.max(6, Math.min(22, Math.sqrt(v[0]) * 0.9)),
      itemStyle: { color: theme.busToMetro, opacity: 0.75 },
    },
  ];
  return option;
}

/**
 * Top 枢纽换乘时间箱线图。
 * 五数 = 标准 min/P25/P50/P75/max；P90 以独立 scatter 叠加（ECharts 会把第 5 个数
 * 渲染为最大值，P90 绝不能混入 boxplot data —— 设计方案 v2 §6.4）。
 */
export function hubBoxplotOption(boxItems, theme, animation) {
  const { t, option } = baseOption(theme, animation);
  option.tooltip = {
    trigger: "item",
    backgroundColor: t.tooltipBg,
    borderColor: t.tooltipBorder,
    textStyle: { color: t.tooltipText, fontSize: 12 },
    formatter: (p) => {
      if (p.seriesType === "boxplot") {
        const v = p.data.slice(1);
        return `${p.name}<br/>最小 ${fmtMin(v[0])}｜P25 ${fmtMin(v[1])}<br/>中位 ${fmtMin(v[2])}｜P75 ${fmtMin(v[3])}<br/>最大 ${fmtMin(v[4])}`;
      }
      return `${p.name}<br/>P90：${fmtMin(p.value[1])}`;
    },
  };
  option.grid = { left: 8, right: 12, top: 26, bottom: 4, containLabel: true };
  option.legend = { top: 0, right: 0, textStyle: { color: t.legendText, fontSize: 11 }, itemWidth: 14, itemHeight: 8, data: ["P90"] };
  option.xAxis = catAxis(t, boxItems.map((b) => b.name), {
    axisLabel: { color: t.axisLabel, fontSize: 10, interval: 0, rotate: boxItems.length > 5 ? 30 : 0, width: 72, overflow: "truncate" },
  });
  option.yAxis = valAxis(t, { fmt: (v) => `${Math.round(v / 60)}分` });
  option.series = [
    {
      type: "boxplot",
      data: boxItems.map((b) => b.five),
      itemStyle: { color: "rgba(64,169,255,0.18)", borderColor: theme.metroToBus, borderWidth: 1.2 },
      boxWidth: [10, 26],
    },
    {
      name: "P90",
      type: "scatter",
      data: boxItems.map((b, i) => ({ name: b.name, value: [i, b.p90] })),
      symbol: "diamond",
      symbolSize: 9,
      itemStyle: { color: theme.warn },
      z: 3,
    },
  ];
  return option;
}
