// 临时验证页（体检评估分析）：登录墙外直接看 TJFX 真实组件的排版。
// 网络层用 axios 适配器桩接管，喂一份"广州市 / 真实数据"口径的样例响应，
// 组件、模板、样式、雷达图全部走生产代码，只有数据是假的。
// 用法：vite dev 下访问 /harness-tjfx.html
import "@/assets/styles/main.scss";
import "@/assets/styles/element.core.scss";
// index.vue 在页面级引入的 --dm2-* 令牌，harness 也要带上，否则明暗配色不对
import "@/views/datamanagement/tokens.css";
import axios from "axios";
import { createApp, h, ref } from "vue";
import { initUiTheme } from "@/utils/uiTheme";
import { installElementPlus } from "@/plugins/element-plus";
import { installBusinessElementPlus } from "@/plugins/element-plus-business";

const DISTRICTS = [
  "越秀区", "海珠区", "荔湾区", "天河区", "白云区", "黄埔区",
  "番禺区", "花都区", "南沙区", "增城区", "从化区",
  // 仅 harness 用：验证"可对标指标不足，暂不出图"空态
  "空数据样例",
];

// 真实刷卡口径下大量指标为 null（后端明确标 unsupported），样例保留这些洞，
// 用来验证雷达轴"暂无数据"与表格"不支持"两种缺失态的排版。
const VALUES_BY_DISTRICT = {
  全市: {
    czrkmd: 2210, gjxwmd: 2.14, fgl300: 73.04, wrbyl: null, cxfdl: null,
    cjrzkl: 118.4, dbczkl: 9.66, rcxcs: 0.112, xlfzxxs: 1.72, xlcfxs: 2.86,
    xlmzl: null, xlklqd: 0.92, yxsdb: null, pjhcsj: null, pjhccs: 0.2141,
    gjjbbl: null, cjczmj: 118.7,
  },
  南沙区: {
    czrkmd: 1196, gjxwmd: 1.9, fgl300: 73.04, wrbyl: null, cxfdl: null,
    cjrzkl: 93.25, dbczkl: 7.21, rcxcs: 0.112, xlfzxxs: 1.68, xlcfxs: 1.94,
    xlmzl: null, xlklqd: 0.61, yxsdb: null, pjhcsj: null, pjhccs: 0.2141,
    gjjbbl: null, cjczmj: 176.3,
  },
  空数据样例: {
    czrkmd: null, gjxwmd: null, fgl300: null, wrbyl: null, cxfdl: null,
    cjrzkl: null, dbczkl: null, rcxcs: null, xlfzxxs: null, xlcfxs: null,
    xlmzl: null, xlklqd: null, yxsdb: null, pjhcsj: null, pjhccs: null,
    gjjbbl: null, cjczmj: null,
  },
};

const UNSUPPORTED = {
  wrbyl: "真实车辆数据缺少车长/车型，不能按官方车长系数折算标台",
  cxfdl: "缺少全部机动化方式完整出行分母",
  xlmzl: "缺少逐班车辆额定容量",
  yxsdb: "缺少同一高峰窗小汽车运行里程与时间",
  pjhcsj: "缺少乘客到站时间",
  gjjbbl: "缺少完整公交与轨道乘坐链",
};

function evaluationPayload(district) {
  const values = VALUES_BY_DISTRICT[district] || VALUES_BY_DISTRICT.全市;
  const availability = {};
  Object.entries(UNSUPPORTED).forEach(([key, reason]) => {
    if (values[key] == null) availability[key] = { status: "unsupported", reason };
  });
  return { status: "ready", source: "real", values, availability };
}

// 必须在 request.js（内部 axios.create）被 import 之前装好，实例才会继承这个适配器
axios.defaults.adapter = async (config) => {
  const url = String(config.url || "");
  const body = config.data ? JSON.parse(config.data) : {};
  let data = {};
  if (url.includes("evaluation")) {
    data = evaluationPayload(String(body.district || "全市"));
  } else if (url.includes("adminDistricts")) {
    data = { districts: DISTRICTS, collection: { type: "FeatureCollection", features: [] } };
  }
  console.info("[harness] %s district=%s", url, body.district ?? "-");
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

const { default: TJFX } = await import("@/views/datavisualization/components/TJFX.vue");

// 页面壳模拟 index.vue 的 tjfx-full-stage-wrapper：整屏、无底图。
const district = ref("全市");

const app = createApp({
  setup() {
    return () => h("div", { class: "harness-stage" }, [
      h("div", { class: "harness-bar" }, [
        h("span", "harness · 体检评估分析（样例数据）"),
        h("span", { class: "harness-scope" }, `district prop = ${district.value}`),
      ]),
      h("div", { class: "harness-body" }, [
        h(TJFX, {
          model: "广州市@2026-04-06",
          district: district.value,
          "onUpdate:district": (value) => { district.value = value; },
        }),
      ]),
    ]);
  },
});
installElementPlus(app);
installBusinessElementPlus(app);
app.mount("#app");
