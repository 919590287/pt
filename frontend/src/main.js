import "./assets/styles/main.scss";

import { createApp } from "vue";
import { createPinia } from "pinia";

import App from "./App.vue";
import router from "./router";

// echarts
import VChart from "vue-echarts";
import * as echarts from "echarts";

import ElementPlus, { ElMessage, ElMessageBox } from "element-plus";
import zhCn from 'element-plus/es/locale/lang/zh-cn'
// import "element-plus/dist/index.css";
// ✅ 引入自定义的 SCSS 主题文件
import '@/assets/styles/element.scss'
// import moment from 'moment'

const app = createApp(App);

// 全局方法挂载
app.config.globalProperties.$echarts = echarts;
app.config.globalProperties.$message = ElMessage
app.config.globalProperties.$alert = ElMessageBox.alert
app.config.globalProperties.$confirm = ElMessageBox.confirm
app.config.globalProperties.$prompt = ElMessageBox.prompt
// app.config.globalProperties.$moment = moment
// 全局组件挂载
app.component("VChart", VChart);

app.use(createPinia());
app.use(router);
app.use(ElementPlus, { locale: zhCn })
app.mount("#app");
