// 乘降热力图共用 ECharts 配置：线路客流分析与站点客流分析同款视觉
// （玻璃白 tooltip、绿→黄→红连续色带、白描边圆角格、两端深色格白字标签、右侧竖向色条）。
// 数据装配（分桶/聚合）留在宿主，本模块只负责图表的"长相"；视觉基准取自线路客流分析。

function toFiniteNumber(value, fallback = 0) {
  const num = Number(value);
  return Number.isFinite(num) ? num : fallback;
}

export function buildBoardingHeatmapOption({
  xLabels = [],
  yLabels = [],
  cells = [],
  maxCellFlow = 0,
  seriesName = "乘降热力",
  animationDuration = 280,
  labelMaxCells = 160,
  tooltipFormatter,
  labelFormatter,
}) {
  const safeMax = Math.max(1, toFiniteNumber(maxCellFlow, 0));
  const manyColumns = xLabels.length > 8;
  // 两端深色格子（深绿/深红）改白色数值，中段浅色格子用深色数值
  const data = cells.map((cell) => {
    const entry = Array.isArray(cell) ? { value: cell } : cell;
    const ratio = toFiniteNumber(entry?.value?.[2], 0) / safeMax;
    return ratio <= 0.18 || ratio >= 0.68 ? { ...entry, label: { color: "#ffffff" } } : entry;
  });
  return {
    backgroundColor: "transparent",
    animationDuration,
    animationEasing: "quarticOut",
    tooltip: {
      position: "top",
      appendToBody: true,
      backgroundColor: "rgba(255, 255, 255, 0.98)",
      borderColor: "rgba(17, 32, 58, 0.1)",
      borderWidth: 1,
      padding: [8, 11],
      extraCssText: "border-radius:10px;box-shadow:0 12px 32px -14px rgba(13,38,76,0.34);",
      textStyle: { color: "#1c2024", fontSize: 12 },
      formatter: tooltipFormatter,
    },
    // 矩阵风格：右侧竖向色条，格子间白色描边
    grid: {
      left: "2%",
      right: 58,
      top: 8,
      bottom: 8,
      containLabel: true,
    },
    xAxis: {
      type: "category",
      data: xLabels,
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: {
        color: "#667085",
        fontSize: 11,
        interval: 0,
        rotate: manyColumns ? 45 : 0,
      },
    },
    yAxis: {
      type: "category",
      data: yLabels,
      inverse: true, // 早时段在上
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: "#667085", fontSize: 11 },
    },
    visualMap: {
      type: "continuous",
      min: 0,
      max: safeMax,
      calculable: true,
      orient: "vertical",
      right: 0,
      top: "middle",
      itemWidth: 14,
      itemHeight: 220,
      inRange: {
        // 绿(少)→黄→红(多)，与满载率配色语义一致
        color: ["#1a9850", "#66bd63", "#a6d96a", "#d9ef8b", "#fee08b", "#fdae61", "#f46d43", "#d73027"],
      },
      textStyle: { color: "#667085", fontSize: 11 },
    },
    series: [
      {
        name: seriesName,
        type: "heatmap",
        data,
        label: {
          show: cells.length <= labelMaxCells,
          color: "#3f4a3f",
          fontSize: 10,
          formatter: labelFormatter || ((params) => toFiniteNumber(params?.value?.[2], 0).toLocaleString()),
        },
        itemStyle: {
          borderColor: "#ffffff",
          borderWidth: 2,
          borderRadius: 2,
        },
        emphasis: {
          itemStyle: {
            shadowBlur: 8,
            shadowColor: "rgba(15, 23, 42, 0.35)",
          },
        },
      },
    ],
  };
}
