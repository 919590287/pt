import VChart from "vue-echarts";
import { graphic, use } from "echarts/core";
import { BarChart, BoxplotChart, GaugeChart, HeatmapChart, LineChart, PieChart, RadarChart, ScatterChart } from "echarts/charts";
import {
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  MarkLineComponent,
  TitleComponent,
  TooltipComponent,
  VisualMapComponent,
} from "echarts/components";
// LegacyGridContainLabel:v6 起 grid.containLabel 需显式注册,否则被静默忽略,
// 轴标签会溢出绘图区被裁(本平台所有 option 均依赖 containLabel)
import { LabelLayout, LegacyGridContainLabel } from "echarts/features";
import { CanvasRenderer } from "echarts/renderers";

// 图表运行时只由“运行监测”路由加载，避免登录页和不含图表的页面支付 200KB+ gzip 成本。
// Scatter/Boxplot/MarkLine 为换乘分析页新增（散点、箱线五数+P90 叠加、阈值线）。
use([
  BarChart,
  BoxplotChart,
  GaugeChart,
  HeatmapChart,
  LineChart,
  PieChart,
  RadarChart,
  ScatterChart,
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  MarkLineComponent,
  TitleComponent,
  TooltipComponent,
  VisualMapComponent,
  LabelLayout,
  LegacyGridContainLabel,
  CanvasRenderer,
]);

export { graphic, VChart };
