// 临时验证页（配车测算 · 真实线路参数导入）：登录墙外直接跑 vehiclecalculation/index.vue 真组件。
// 网络层用 axios 适配器桩接管 /pt/real-data/busLineStation，喂一份从真实 routes.shp 摘出的线路属性
// （字段值原样照抄，含空高峰字段、24 时以上末班、单走向环线、三走向线路、站名带嵌套括号等边界）。
// 组件、模板、样式、测算逻辑全部走生产代码，只有数据是假的。
// 用法：vite dev 下访问 /harness-vehiclecalc.html
import "@/assets/styles/main.scss";
import "@/assets/styles/element.core.scss";
import axios from "axios";
import { createApp, h } from "vue";
import { initUiTheme } from "@/utils/uiTheme";
import { installElementPlus } from "@/plugins/element-plus";
import { installBusinessElementPlus } from "@/plugins/element-plus-business";

// 摘自 /Volumes/USB DISK/pt_data/广州市/真实数据/公交线路站点/线路/routes.shp
const LINE_PROPERTIES = [
  { line_id: "440100016174", name: "101路(机场路总站--海印桥总站)", company: "巴士集团", first: "06:00:00", last: "22:30:00", first_dep: "06:00", last_dep: "22:30", am_peak: "", pm_peak: "", am_gap: "", pm_gap: "", off_gap: "" },
  { line_id: "440100016173", name: "101路(海印桥总站--机场路总站)", company: "巴士集团", first: "06:00:00", last: "22:30:00", first_dep: "06:00", last_dep: "22:30", am_peak: "", pm_peak: "", am_gap: "", pm_gap: "", off_gap: "" },
  { line_id: "900000043689", name: "夜101路(沐陂村总站--沥滘总站)", company: "巴士集团", first: "22:00:00", last: "23:00:00", first_dep: "22:00", last_dep: "23:00", am_peak: "", pm_peak: "", am_gap: "", pm_gap: "", off_gap: "" },
  { line_id: "900000043688", name: "夜101路(沥滘总站--沐陂村总站)", company: "巴士集团", first: "22:00:00", last: "24:00:00", first_dep: "22:00", last_dep: "24:00", am_peak: "", pm_peak: "", am_gap: "", pm_gap: "", off_gap: "" },
  { line_id: "440100016175", name: "102路(东山总站--广钢新城(崇文二路)总站)", company: "巴士集团", first: "06:00:00", last: "22:30:00", first_dep: "06:00", last_dep: "22:30", am_peak: "", pm_peak: "", am_gap: "", pm_gap: "", off_gap: "" },
  { line_id: "440100016176", name: "102路(广钢新城总站(崇文二路)--东山总站)", company: "巴士集团", first: "06:00:00", last: "22:30:00", first_dep: "06:00", last_dep: "22:30", am_peak: "", pm_peak: "", am_gap: "", pm_gap: "", off_gap: "" },
  { line_id: "900000048713", name: "夜102路(东山总站--广州南站总站)", company: "广州第二巴士客运公司", first: "23:40:00", last: "26:10:00", first_dep: "23:40", last_dep: "26:10", am_peak: "", pm_peak: "", am_gap: "", pm_gap: "", off_gap: "" },
  { line_id: "900000048712", name: "夜102路(广州南站总站--东山总站(东华北路))", company: "广州公交集团", first: "22:30:00", last: "25:00:00", first_dep: "22:30", last_dep: "25:00", am_peak: "", pm_peak: "", am_gap: "", pm_gap: "", off_gap: "" },
  { line_id: "900000073094", name: "南沙65路(市桥汽车站东门(番禺人才市场)站--潭洲车站总站)", company: "润信", first: "06:10:00", last: "23:00:00", first_dep: "06:10", last_dep: "23:00", am_peak: "07:00-09:00", pm_peak: "17:00-19:00", am_gap: "10", pm_gap: "10", off_gap: "15" },
  { line_id: "900000073093", name: "南沙65路(潭洲车站总站--市桥汽车站西门站)", company: "润信", first: "05:00:00", last: "21:55:00", first_dep: "05:00", last_dep: "21:55", am_peak: "07:00-09:00", pm_peak: "17:00-19:00", am_gap: "10", pm_gap: "10", off_gap: "15" },
  { line_id: "900000136823", name: "南沙65路(快)(大岗公交总站--市桥汽车站西门站)", company: "润信", first: "06:20:00", last: "19:00:00", first_dep: "06:20", last_dep: "19:00", am_peak: "07:00-09:00", pm_peak: "17:00-19:00", am_gap: "15", pm_gap: "15", off_gap: "25" },
  { line_id: "900000136824", name: "南沙65路(快)(市桥汽车站东门(番禺人才市场)站--大岗公交总站)", company: "润信", first: "07:30:00", last: "20:10:00", first_dep: "07:30", last_dep: "20:10", am_peak: "07:20-09:00", pm_peak: "17:00-19:00", am_gap: "15", pm_gap: "15", off_gap: "25" },
  { line_id: "440100016186", name: "109路(中山八路总站--中山八路总站)", company: "巴士集团", first: "06:00:00", last: "22:00:00", first_dep: "06:00", last_dep: "22:00", am_peak: "", pm_peak: "", am_gap: "", pm_gap: "", off_gap: "" },
  { line_id: "440100018001", name: "南沙10路(新兴村委总站--地铁万顷沙站)", company: "南巴", first: "07:00:00", last: "22:00:00", first_dep: "07:00", last_dep: "22:00", am_peak: "07:00-08:00", pm_peak: "17:30-18:30", am_gap: "30", pm_gap: "30", off_gap: "60" },
  { line_id: "900000213430", name: "南沙10路(地铁万顷沙站--新兴村委总站)", company: "南巴", first: "06:00:00", last: "21:00:00", first_dep: "06:00", last_dep: "21:00", am_peak: "06:00-07:00", pm_peak: "", am_gap: "30", pm_gap: "", off_gap: "60" },
  { line_id: "900000213422", name: "南沙10路(新兴村委总站--地铁万顷沙站)", company: "南巴", first: "07:00:00", last: "22:00:00", first_dep: "07:00", last_dep: "22:00", am_peak: "07:00-08:00", pm_peak: "17:30-18:30", am_gap: "30", pm_gap: "30", off_gap: "60" },
  { line_id: "440100013417", name: "南沙12路(新兴村委总站--珠江电厂站)", company: "南巴", first: "06:30:00", last: "21:30:00", first_dep: "06:30", last_dep: "21:30", am_peak: "07:00-09:00", pm_peak: "17:00-19:00", am_gap: "20", pm_gap: "20", off_gap: "30" },
  { line_id: "440100013415", name: "南沙12路(珠江电厂站--新兴村委总站)", company: "南巴", first: "05:40:00", last: "21:30:00", first_dep: "05:40", last_dep: "21:30", am_peak: "07:00-08:00", pm_peak: "17:00-18:00", am_gap: "20", pm_gap: "20", off_gap: "30" },
  { line_id: "900000025677", name: "夜15路(同德围总站--广州火车东站总站)", company: "一汽巴士", first: "22:10:00", last: "29:30:00", first_dep: "22:10", last_dep: "29:30", am_peak: "", pm_peak: "", am_gap: "", pm_gap: "", off_gap: "" },
];

function busLineStationPayload() {
  return {
    versionId: "harness",
    history: { revision: 0, activeVersionId: "harness" },
    lines: {
      type: "FeatureCollection",
      featureCount: LINE_PROPERTIES.length,
      features: LINE_PROPERTIES.map((properties, index) => ({
        type: "Feature",
        id: `line.${index}`,
        geometry: null,
        properties: { ...properties, _featureId: `line.${index}` },
      })),
    },
    stations: { type: "FeatureCollection", features: [] },
    routeStops: { deferred: true },
    depots: { type: "FeatureCollection", features: [] },
  };
}

// 必须在 request.js（内部 axios.create）被 import 之前装好，实例才会继承这个适配器
axios.defaults.adapter = async (config) => {
  const url = String(config.url || "");
  console.info("[harness] %s", url);
  const data = url.includes("busLineStation")
    ? busLineStationPayload()
    : url.includes("vehicleCalculation")
      ? { versionId: "harness-saved", revision: 1, updatedFeatureCount: 2 }
      : {};
  return {
    data: { code: 200, msg: "ok", data },
    status: 200,
    statusText: "OK",
    headers: {},
    config,
    request: {},
  };
};

initUiTheme();

const { default: VehicleCalculation } = await import("@/views/vehiclecalculation/index.vue");

const app = createApp({
  setup() {
    return () => h("div", null, [
      h("div", { class: "harness-bar" }, [
        h("span", "harness · 配车测算（线路属性摘自真实 routes.shp）"),
        h("span", { class: "harness-scope" }, "南沙65路=字段齐全 / 101路=高峰全空 / 109路=单走向 / 夜15路=末班29:30"),
        h("button", {
          onClick: () => document.documentElement.classList.toggle("dark"),
        }, "切换明暗"),
      ]),
      h(VehicleCalculation),
    ]);
  },
});
installElementPlus(app);
installBusinessElementPlus(app);
app.mount("#app");
