/**
 * ECharts 中性 chrome 配色（轴/网格/图例/tooltip），跟随 UI 明暗主题。
 *
 * 只覆盖「非数据语义」的中性色：轴线、刻度、分隔线、轴标签、图例文字、
 * tooltip 表面。系列色/色带/语义色（客流红绿、方向橙蓝等）是数据口径，
 * 不随主题翻转，仍取 mapTheme.js / colorSchemes.js。
 *
 * 用法：构建 option 时取 chartInk.value.*；组件另 watch(isDarkTheme)（或
 * watch(chartInk)）触发一次重建 option，canvas 文本才会换色。
 * 浅色值与既有各组件写死的字面量保持一致（#667085 / rgba(17,32,58,*) 系），
 * 浅色模式下渲染结果不变。
 */
import { computed } from "vue";
import { isDarkTheme } from "@/utils/uiTheme";

const LIGHT = Object.freeze({
  /** 轴标签/图例常规文字 */
  text: "#667085",
  /** 更弱的辅助文字（单位、角标） */
  textSoft: "#98a2b3",
  /** 标题/tooltip 主文字 */
  textStrong: "#1c2024",
  axisLine: "rgba(17, 32, 58, 0.12)",
  axisTick: "rgba(17, 32, 58, 0.1)",
  splitLine: "rgba(17, 32, 58, 0.07)",
  axisPointer: "rgba(17, 32, 58, 0.18)",
  tooltipBg: "rgba(255, 255, 255, 0.98)",
  tooltipBorder: "rgba(17, 32, 58, 0.1)",
  tooltipText: "#1c2024",
  tooltipSubText: "#667085",
  tooltipShadow: "0 12px 32px -14px rgba(13, 38, 76, 0.34)",
});

const DARK = Object.freeze({
  text: "#94a3b8",
  textSoft: "#64748b",
  textStrong: "#e7edf6",
  axisLine: "rgba(148, 180, 220, 0.2)",
  axisTick: "rgba(148, 180, 220, 0.16)",
  splitLine: "rgba(148, 180, 220, 0.12)",
  axisPointer: "rgba(148, 180, 220, 0.3)",
  tooltipBg: "rgba(17, 23, 31, 0.97)",
  tooltipBorder: "rgba(148, 180, 220, 0.2)",
  tooltipText: "#e7edf6",
  tooltipSubText: "#94a3b8",
  tooltipShadow: "0 12px 32px -14px rgba(2, 6, 12, 0.7)",
});

export const chartInk = computed(() => (isDarkTheme.value ? DARK : LIGHT));

export { isDarkTheme };
