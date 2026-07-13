import VChart from "vue-echarts";
import { graphic, use } from "echarts/core";
import { BarChart, GaugeChart, HeatmapChart, LineChart, PieChart, RadarChart } from "echarts/charts";
import {
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
  VisualMapComponent,
} from "echarts/components";
import { LabelLayout } from "echarts/features";
import { CanvasRenderer } from "echarts/renderers";

// 图表运行时只由“运行监测”路由加载，避免登录页和不含图表的页面支付 200KB+ gzip 成本。
use([
  BarChart,
  GaugeChart,
  HeatmapChart,
  LineChart,
  PieChart,
  RadarChart,
  DataZoomComponent,
  GridComponent,
  LegendComponent,
  TitleComponent,
  TooltipComponent,
  VisualMapComponent,
  LabelLayout,
  CanvasRenderer,
]);

export { graphic, VChart };
