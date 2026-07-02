import "./assets/styles/main.scss";
import "maplibre-gl/dist/maplibre-gl.css";

import { createApp } from "vue";
import { createPinia } from "pinia";

import App from "./App.vue";
import router from "./router";

// echarts
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

// import "element-plus/dist/index.css";
// ✅ 引入自定义的 SCSS 主题文件
import '@/assets/styles/element.scss'
import { ElMessage, ElMessageBox, installElementPlus } from "@/plugins/element-plus";
import { appendErrorLog, installErrorLogExport } from "@/utils/errorLog";
// import moment from 'moment'

const app = createApp(App);

// 全局错误收敛：Vue 组件错误 + 未处理的 Promise 拒绝统一写入 localStorage 环形日志（键 gj_error_log），
// 控制台可通过 window.__GJ_EXPORT_ERROR_LOG__() 导出排查。
app.config.errorHandler = (err, instance, info) => {
  appendErrorLog({
    type: "vue-error",
    message: `${err?.message ?? err}${info ? `（${info}）` : ""}`,
    stack: err?.stack,
  });
  // 不吞错误：记录之后继续输出原始错误，保持默认调试行为
  console.error(err, info);
};

window.addEventListener("unhandledrejection", (event) => {
  const reason = event?.reason;
  appendErrorLog({
    type: "unhandledrejection",
    message: reason?.message ?? String(reason),
    stack: reason?.stack,
  });
  console.error("[unhandledrejection]", reason);
});

installErrorLogExport();

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

// 全局方法挂载
app.config.globalProperties.$echarts = { graphic };
app.config.globalProperties.$message = ElMessage
app.config.globalProperties.$alert = ElMessageBox.alert
app.config.globalProperties.$confirm = ElMessageBox.confirm
app.config.globalProperties.$prompt = ElMessageBox.prompt
// app.config.globalProperties.$moment = moment
// 全局组件挂载
app.component("VChart", VChart);

app.use(createPinia());
app.use(router);
installElementPlus(app);
app.mount("#app");
