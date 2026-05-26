<!-- 数据总览 -->
<template>
  <div class="SJZL" v-bind="$attrs">
    <MCard class="card" v-for="item in list" :key="item.title">
      <template #title="attrs">
        <div :class="`title ${attrs.class}`">{{ item.title }}</div>
        <el-switch v-model="item.switch" :active-value="true" :inactive-value="false" @click.stop />
      </template>
      <template #body>
        <el-checkbox v-for="item2 in item.children" :key="item2.title" v-model="item2.check" :disabled="!item.switch || item2.disabled">{{ item2.title }}</el-checkbox>
      </template>
    </MCard>
  </div>

  <teleport to="#datavisualization_index_box2" defer>
    <MCard2 class="SJZL_right_card ztsp_card" title="总体水平" v-if="list[0]?.switch" :open="true">
      <template #body>
        <div class="SJZL_grid">
          <!-- 常住人口密度(人/km²) -->
          <div class="row1 item1" v-if="isChildrenShow(list[0]?.children?.[0])">
            <div class="title">常住人口密度</div>
            <div class="num">{{ detail?.czrkmd || 0 }}</div>
            <div class="unit">人/km²</div>
            <div class="icon-badge">
              <RKICON class="icon"></RKICON>
            </div>
            <div class="indicator-bar">
              <div class="indicator-label">密度水平</div>
              <div class="indicator-track">
                <div class="indicator-fill" style="width: 75%;"></div>
              </div>
            </div>
          </div>
          <!-- 线网密度(km/km²) -->
          <div class="row1 item1" v-if="isChildrenShow(list[0]?.children?.[1])">
            <div class="title">线网密度</div>
            <div class="num">{{ detail?.gjxwmd || 0 }}</div>
            <div class="unit">km/km²</div>
            <div class="icon-badge">
              <XLICON class="icon"></XLICON>
            </div>
            <div class="indicator-bar">
              <div class="indicator-label">覆盖效率</div>
              <div class="indicator-track">
                <div class="indicator-fill" style="width: 82%;"></div>
              </div>
            </div>
          </div>
          <!-- 车站300m人口覆盖率(%) -->
          <div class="row2 item3" v-if="isChildrenShow(list[0]?.children?.[2])">
            <div class="title">车站300m人口覆盖率</div>
            <el-auto-resizer class="chart_box">
              <template #default="{ height, width }">
                <VChart v-if="width > 0 && height > 0" class="chart" :option="ztsp_fgl_options" autoresize :update-options="{ notMerge: true }" />
              </template>
            </el-auto-resizer>
          </div>
          <!-- 万人保有量(标台/万人) -->
          <div class="row1 item3" v-if="isChildrenShow(list[0]?.children?.[3])">
            <div class="title">万人保有量</div>
            <el-auto-resizer class="chart_box">
              <template #default="{ height, width }">
                <VChart v-if="width > 0 && height > 0" class="chart" :option="ztsp_byl_options" autoresize :update-options="{ notMerge: true }" />
              </template>
            </el-auto-resizer>
          </div>
          <!-- 出行分担率(%) -->
          <div class="row2 item3" v-if="isChildrenShow(list[0]?.children?.[4])">
            <div class="title">出行分担率</div>
            <el-auto-resizer class="chart_box">
              <template #default="{ height, width }">
                <VChart v-if="width > 0 && height > 0" class="chart" :option="ztsp_fdl_options" autoresize :update-options="{ notMerge: true }" />
              </template>
            </el-auto-resizer>
          </div>
          <!-- 车均日载客量(人次) -->
          <div class="row1 item1" v-if="isChildrenShow(list[0]?.children?.[5])">
            <div class="title">车均日载客量</div>
            <div class="num">{{ detail?.cjrzkl || 0 }}</div>
            <div class="unit">人次</div>
            <div class="icon-badge">
              <BUSICON class="icon"></BUSICON>
            </div>
            <div class="indicator-bar">
              <div class="indicator-label">载客水平</div>
              <div class="indicator-track">
                <div class="indicator-fill" style="width: 68%;"></div>
              </div>
            </div>
          </div>
          <!-- 单班次载客量(人次/班) -->
          <div class="row1 item1" v-if="isChildrenShow(list[0]?.children?.[6])">
            <div class="title">单班次载客量</div>
            <div class="num">{{ detail?.dbczkl || 0 }}</div>
            <div class="unit">人次/班</div>
            <div class="icon-badge">
              <LDRKICON class="icon"></LDRKICON>
            </div>
            <div class="indicator-bar">
              <div class="indicator-label">运营效率</div>
              <div class="indicator-track">
                <div class="indicator-fill" style="width: 72%;"></div>
              </div>
            </div>
          </div>
        </div>
      </template>
    </MCard2>

    <MCard2 class="SJZL_right_card theme_9acd32" title="需求强度" v-if="list[1]?.switch" :open="true">
      <template #body>
        <div class="SJZL_grid">
          <!-- 日出行次数(次/人) -->
          <div class="row2 item1 rcxcs" v-if="isChildrenShow(list[1]?.children?.[0])">
            <div class="title">日出行次数</div>
            <div class="num">{{ detail?.rcxcs || 0 }}</div>
            <div class="unit">次/人</div>
            <LDRKICON class="icon"></LDRKICON>
          </div>
          <!--依赖客流比例(%) -->
          <div class="row1 item1" v-if="isChildrenShow(list[1]?.children?.[0])">
            <div class="title">依赖客流比例</div>
            <div class="num" style="font-size: 40px">
              <span>{{ detail?.ylklbl || 0 }}</span>
              <span class="unit">%</span>
            </div>
            <div class="icon"></div>
          </div>
        </div>
      </template>
    </MCard2>

    <MCard2 class="SJZL_right_card theme_ec7602" title="线路效益" v-if="list[2]?.switch" :open="true">
      <template #body>
        <div class="SJZL_grid">
          <!-- 线路非直线系数 -->
          <div class="row1 item1" v-if="isChildrenShow(list[2]?.children?.[0])">
            <div class="title">线路非直线系数</div>
            <div class="num" style="font-size: 40px">{{ detail?.xlfzxxs || 0 }}</div>
            <div class="icon"></div>
          </div>
          <!-- 线路重复系数 -->
          <div class="row1 item1" v-if="isChildrenShow(list[2]?.children?.[1])">
            <div class="title">线路重复系数</div>
            <div class="num" style="font-size: 40px">{{ detail?.xlcfxs || 0 }}</div>
            <div class="icon"></div>
          </div>
          <!-- 线路满载率(%) -->
          <div class="row1 item3" v-if="isChildrenShow(list[2]?.children?.[2])">
            <div class="title">线路满载率</div>
            <el-auto-resizer class="chart_box">
              <template #default="{ height, width }">
                <VChart v-if="width > 0 && height > 0" class="chart" :option="xlxy_mzl_options" autoresize :update-options="{ notMerge: true }" />
              </template>
            </el-auto-resizer>
          </div>
          <!-- 线路客流强度(人次/km) -->
          <div class="row1 item2" v-if="isChildrenShow(list[2]?.children?.[3])">
            <div class="title">线路客流强度</div>
            <div>
              <span class="num">{{ detail?.xlklqd_sum || 0 }}</span>
              <span class="unit">人次/km</span>
            </div>
            <el-auto-resizer class="chart_box">
              <template #default="{ height, width }">
                <VChart v-if="width > 0 && height > 0" class="chart" :option="xlxy_klqd_options" autoresize :update-options="{ notMerge: true }" />
              </template>
            </el-auto-resizer>
          </div>
          <!-- 车公里运营成本(元/车/km) -->
          <div class="row1 item3" v-if="isChildrenShow(list[2]?.children?.[4])">
            <div class="title">车公里运营成本</div>
            <el-auto-resizer class="chart_box">
              <template #default="{ height, width }">
                <VChart v-if="width > 0 && height > 0" class="chart" :option="xlxy_glyycb_options" autoresize :update-options="{ notMerge: true }" />
              </template>
            </el-auto-resizer>
          </div>
          <!-- 车单位人次运营成本(元/人次) -->
          <div class="row1 item2" v-if="isChildrenShow(list[2]?.children?.[5])">
            <div class="title">车单位人次运营成本</div>
            <div>
              <span class="num">1.42</span>
              <span class="unit">元/人次</span>
            </div>
            <el-auto-resizer class="chart_box">
              <template #default="{ height, width }">
                <VChart v-if="width > 0 && height > 0" class="chart" :option="xlxy_rcyycb_options" autoresize :update-options="{ notMerge: true }" />
              </template>
            </el-auto-resizer>
          </div>
        </div>
      </template>
    </MCard2>

    <MCard2 class="SJZL_right_card theme_primary_blue" title="运营服务" v-if="list[3]?.switch" :open="true">
      <template #body>
        <div class="SJZL_grid">
          <!-- 公共汽电车与小汽车运行速度比 -->
          <div class="row2 item2" v-if="isChildrenShow(list[3]?.children?.[0])">
            <BUSICON class="icon"></BUSICON>
            <CARICON class="icon2"></CARICON>
            <div class="title">公共汽电车与小汽车运行速度比</div>
            <div>
              <span class="num">{{ detail?.yxsdb?.ptAvg || 0 }} : {{ detail?.yxsdb?.carAvg || 0 }}</span>
              <span class="unit" style="margin-left: 10px">km²/h</span>
            </div>
            <el-auto-resizer class="chart_box">
              <template #default="{ height, width }">
                <VChart v-if="width > 0 && height > 0" class="chart" :option="yyfw_sdb_options" autoresize :update-options="{ notMerge: true }" />
              </template>
            </el-auto-resizer>
          </div>
        </div>
      </template>
    </MCard2>

    <MCard2 class="SJZL_right_card theme_primary_blue" title="场站设施" v-if="list[4]?.switch" :open="true">
      <template #body>
        <div class="SJZL_grid">
          <!-- 车均场站面积(m²/标台) -->
          <div class="row2 item1" v-if="isChildrenShow(list[4]?.children?.[0])">
            <div class="title">车均场站面积(m²/标台)</div>
            <div class="num">8,543</div>
            <div class="unit">人/km²</div>
            <div class="icon"></div>
          </div>
        </div>
      </template>
    </MCard2>
    <!-- <MCard2 class="card" :title="item.title" v-if="item.switch" :open="false">
      <template #body>
        <div class="SJZL_grid">
          <template v-for="(item2, index) in item.children">
            <div :class="`row${(index % 2) + 1}`" v-if="item2.check">{{ item2.title }}</div>
          </template>
        </div>
      </template>
    </MCard2> -->
  </teleport>
</template>

<script setup>
import MCard from "./MCard.vue";
import MCard2 from "./MCard2.vue";

import BUSICON from "@/assets/images/sjzl/bus.svg";
import CARICON from "@/assets/images/sjzl/car.svg";
import LDRKICON from "@/assets/images/sjzl/ldrk.svg";
import RKICON from "@/assets/images/sjzl/rk.svg";
import XLICON from "@/assets/images/sjzl/xl.svg";

import { NetworkLayer } from "../layers/NetworkLayer.js";

import { injectSync } from "@/utils";

const props = defineProps({
  model: String,
});
const { proxy } = getCurrentInstance();

const rightPanelHasContent = inject("rightPanelHasContent", ref(false));
const activeDatavisualizationTab = inject("activeDatavisualizationTab", ref("数据总览"));

function setRightPanelForOverview(visible) {
  if (activeDatavisualizationTab.value === "数据总览") {
    rightPanelHasContent.value = visible;
  }
}

setRightPanelForOverview(true);

const list = ref([
  {
    title: "总体水平",
    switch: true,
    children: [
      { title: "常住人口密度(人/km²)", check: true, disabled: false },
      { title: "线网密度(km/km²)", check: true, disabled: false },
      { title: "车站300m人口覆盖率(%)", check: true, disabled: false },
      { title: "万人保有量(标台/万人)", check: true, disabled: true },
      { title: "出行分担率(%)", check: true, disabled: false },
      { title: "车均日载客量(人次)", check: true, disabled: false },
      { title: "单班次载客量(人次/班)", check: true, disabled: false },
    ],
  },
  {
    title: "需求强度",
    switch: true,
    children: [
      { title: "日出行次数(次/人)", check: true, disabled: false },
      { title: "依赖客流比例(%)", check: true, disabled: false },
    ],
  },
  {
    title: "线路效益",
    switch: true,
    children: [
      { title: "线路非直线系数", check: true, disabled: false },
      { title: "线路重复系数", check: true, disabled: false },
      { title: "线路满载率(%)", check: true, disabled: false },
      { title: "线路客流强度(人次/km)", check: true, disabled: false },
      { title: "车公里运营成本(元/车/km)", check: true, disabled: true },
      { title: "车单位人次运营成本(元/人次)", check: true, disabled: true },
    ],
  },
  {
    title: "运营服务",
    switch: true,
    children: [{ title: "公共汽电车与小汽车运行速度比", check: true, disabled: false }],
  },
  {
    title: "场站设施",
    switch: true,
    children: [{ title: "车均场站面积(m²/标台)", check: true, disabled: true }],
  },
]);

function isChildrenShow(child) {
  return child && child.check && !child.disabled;
}
/******************************** 总体水平 ********************************/
// 车站300m覆盖率
const ztsp_fgl_options = computed(() => {
  const coverVal = detail.value?.fgl_300?.cover || 0;
  const notCoverVal = detail.value?.fgl_300?.notcover || 0;
  const total = coverVal + notCoverVal;
  const percent = total > 0 ? Math.round((coverVal / total) * 100) : 0;
  return {
    tooltip: {
      appendToBody: true,
      extraCssText: "z-index:999; border-radius: 8px;",
      trigger: "item",
      formatter: "{b}: {c}人 ({d}%)",
    },
    legend: {
      top: "center",
      right: "8%",
      orient: "vertical",
      icon: "circle",
      itemWidth: 10,
      itemHeight: 10,
      textStyle: {
        fontSize: 13,
        color: "#555",
      },
    },
    title: {
      text: `${percent}%`,
      subtext: "覆盖率",
      x: "30%",
      y: "35%",
      textAlign: "center",
      textStyle: {
        fontSize: 24,
        fontWeight: "bold",
        color: "#1569de",
      },
      subtextStyle: {
        fontSize: 12,
        color: "#60758e",
      },
    },
    series: [
      {
        name: "覆盖率",
        type: "pie",
        center: ["30%", "50%"],
        radius: ["65%", "85%"],
        avoidLabelOverlap: false,
        itemStyle: {
          borderRadius: 4,
          borderColor: "#fff",
          borderWidth: 2,
        },
        label: {
          show: false,
        },
        data: [
          {
            value: coverVal,
            name: "覆盖人口",
            itemStyle: {
              color: new proxy.$echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: "#34d399" },
                { offset: 1, color: "#0f9f6e" }
              ]),
            },
          },
          {
            value: notCoverVal,
            name: "未覆盖人口",
            itemStyle: {
              color: "#e2e8f0",
            },
          },
        ],
      },
    ],
  };
});
const ztsp_byl_options = computed(() => {
  return {
    series: [
      {
        type: "gauge",
        // --- 核心形状配置 ---

        startAngle: 200,
        endAngle: -20,
        center: ["50%", "65%"], // 仪表盘位置，稍微往下移一点以留出底部文字空间
        radius: "90%", // 大小

        // --- 进度条（绿/灰 分段） ---
        progress: {
          show: true,
          width: 5, // 圆环宽度
        },
        axisLine: {
          show: true, // 隐藏底层的轴线，因为我们用 progress 来显示颜色
          lineStyle: {
            width: 5,
          },
        },

        // --- 刻度与标签配置 ---
        axisTick: {
          show: false,
        },
        axisTick: {
          distance: 2,
          splitNumber: 2,
          length: 3, // 小刻度线长度
          lineStyle: {
            width: 1,
            color: "#999",
          },
        },
        splitLine: {
          distance: 2,
          show: true,
          length: 6, // 大刻度线长度
          lineStyle: {
            color: "#000",
            width: 1,
          },
        },
        axisLabel: {
          distance: -23,
          show: true,
          color: "#333",
          fontSize: 12,
        },
        // --- 指针配置 ---
        pointer: {
          icon: "path://M2090.36389,615.30999 L2090.36389,615.30999 C2091.48372,615.30999 2092.40383,616.194028 2092.44859,617.312956 L2096.90698,728.755929 C2097.05155,732.369577 2094.2393,735.416212 2090.62566,735.56078 C2090.53845,735.564269 2090.45117,735.566014 2090.36389,735.566014 L2090.36389,735.566014 C2086.74736,735.566014 2083.81557,732.63423 2083.81557,729.017692 C2083.81557,728.930412 2083.81732,728.84314 2083.82081,728.755929 L2088.2792,617.312956 C2088.32396,616.194028 2089.24407,615.30999 2090.36389,615.30999 Z",
          length: "75%",
          width: 6,
          offsetCenter: [0, "5%"],
          itemStyle: {
            color: "#333",
          },
        },
        itemStyle: {
          color: "#b6d634",
        },

        // --- 中间数值文本配置 ---
        title: {
          show: false, // 隐藏默认的 title（我们把它放在 detail 下方或者直接用 detail 的 rich 文本）
        },
        // --- 数据配置 ---
        min: 0,
        max: 25,
        splitNumber: 5,
        data: [{ value: 0, name: "标台 / 万人" }], // name用于底部单位显示
        detail: {
          color: "#333",
          fontSize: 20,
          offsetCenter: [0, 15],
          color: "#b6d634",
        },
        title: {
          color: "#333",
          fontSize: 8,
          offsetCenter: [0, 30],
        },
      },
    ],
  };
});
const ztsp_fdl_options = computed(() => {
  return {
    tooltip: {
      appendToBody: true,
      extraCssText: "z-index:999; border-radius: 8px;",
      trigger: "item",
      formatter: "{b}: {d}%",
    },
    legend: {
      top: "center",
      right: "8%",
      orient: "vertical",
      icon: "circle",
      itemWidth: 10,
      itemHeight: 10,
      textStyle: {
        fontSize: 13,
        color: "#555",
      },
    },
    title: {
      text: "方式分担",
      subtext: "客运交通",
      x: "30%",
      y: "35%",
      textAlign: "center",
      textStyle: {
        fontSize: 17,
        fontWeight: "bold",
        color: "#1569de",
      },
      subtextStyle: {
        fontSize: 11,
        color: "#60758e",
      },
    },
    series: [
      {
        name: "出行分担率",
        type: "pie",
        center: ["30%", "50%"],
        radius: ["60%", "82%"],
        avoidLabelOverlap: false,
        itemStyle: {
          borderRadius: 4,
          borderColor: "#fff",
          borderWidth: 2,
        },
        label: {
          show: false,
        },
        data: [
          {
            value: detail.value?.fxfdl?.pt || 0,
            name: "公交",
            itemStyle: {
              color: new proxy.$echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: "#58b8d4" },
                { offset: 1, color: "#2f75d6" }
              ]),
            },
          },
          {
            value: detail.value?.fxfdl?.car || 0,
            name: "小汽车",
            itemStyle: {
              color: new proxy.$echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: "#f87171" },
                { offset: 1, color: "#dc4c5d" }
              ]),
            },
          },
          {
            value: detail.value?.fxfdl?.subway || 0,
            name: "地铁",
            itemStyle: {
              color: new proxy.$echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: "#58b8d4" },
                { offset: 1, color: "#3aaed0" }
              ]),
            },
          },
          {
            value: detail.value?.fxfdl?.walk || 0,
            name: "步行/自行车",
            itemStyle: {
              color: new proxy.$echarts.graphic.LinearGradient(0, 0, 0, 1, [
                { offset: 0, color: "#fbbf24" },
                { offset: 1, color: "#d97706" }
              ]),
            },
          },
        ],
      },
    ],
  };
});
/******************************** 总体水平 ********************************/

/******************************** 需求强度 ********************************/

/******************************** 需求强度 ********************************/
/******************************** 线路效益 ********************************/
const xlxy_mzl_options = computed(() => {
  return {
    series: [
      {
        type: "gauge",
        // --- 核心形状配置 ---

        startAngle: 200,
        endAngle: -20,
        center: ["50%", "65%"], // 仪表盘位置，稍微往下移一点以留出底部文字空间
        radius: "90%", // 大小

        // --- 进度条（绿/灰 分段） ---
        progress: {
          show: true,
          width: 5, // 圆环宽度
        },
        axisLine: {
          show: true, // 隐藏底层的轴线，因为我们用 progress 来显示颜色
          lineStyle: {
            width: 5,
          },
        },

        // --- 刻度与标签配置 ---
        axisTick: {
          show: false,
        },
        axisTick: {
          distance: 2,
          splitNumber: 2,
          length: 3, // 小刻度线长度
          lineStyle: {
            width: 1,
            color: "#999",
          },
        },
        splitLine: {
          distance: 2,
          show: true,
          length: 6, // 大刻度线长度
          lineStyle: {
            color: "#000",
            width: 1,
          },
        },
        axisLabel: {
          distance: -23,
          show: true,
          color: "#333",
          fontSize: 12,
        },
        // --- 指针配置 ---
        pointer: {
          icon: "path://M2090.36389,615.30999 L2090.36389,615.30999 C2091.48372,615.30999 2092.40383,616.194028 2092.44859,617.312956 L2096.90698,728.755929 C2097.05155,732.369577 2094.2393,735.416212 2090.62566,735.56078 C2090.53845,735.564269 2090.45117,735.566014 2090.36389,735.566014 L2090.36389,735.566014 C2086.74736,735.566014 2083.81557,732.63423 2083.81557,729.017692 C2083.81557,728.930412 2083.81732,728.84314 2083.82081,728.755929 L2088.2792,617.312956 C2088.32396,616.194028 2089.24407,615.30999 2090.36389,615.30999 Z",
          length: "75%",
          width: 6,
          offsetCenter: [0, "5%"],
          itemStyle: {
            color: "#333",
          },
        },
        itemStyle: {
          color: "#ec7602",
        },

        // --- 中间数值文本配置 ---
        title: {
          show: false, // 隐藏默认的 title（我们把它放在 detail 下方或者直接用 detail 的 rich 文本）
        },
        // --- 数据配置 ---
        min: 0,
        max: 100,
        splitNumber: 5,
        data: [{ value: detail.value?.xlmzl || 0, name: `${detail.value?.xlmzl || 0}%` }], // name用于底部单位显示
        detail: {
          show: false,
        },
        title: {
          fontSize: 18,
          fontWeight: "bold",
          offsetCenter: [0, 25],
          color: "#ec7602",
        },
      },
    ],
  };
});
const xlxy_klqd_options = computed(() => {
  return {
    tooltip: {
      appendToBody: true,
      extraCssText: "z-index:999",
      trigger: "axis",
    },
    grid: {
      top: 10,
      left: 5,
      right: 10,
      bottom: 5,
      containLabel: true,
    },
    xAxis: {
      type: "category",
      data: Object.keys(detail.value?.xlklqd || {}),
      axisLabel: {
        show: false,
      },
    },
    yAxis: {
      type: "value",
    },
    series: [
      {
        data: Object.values(detail.value?.xlklqd || {}),
        type: "bar",
        color: new proxy.$echarts.graphic.LinearGradient(0, 0, 0, 1, [
          {
            offset: 0,
            color: "rgba(236, 118, 2, 0.5)",
          },
          {
            offset: 1,
            color: "#ec7602",
          },
        ]),
      },
    ],
  };
});
const xlxy_glyycb_options = computed(() => {
  return {
    tooltip: {
      appendToBody: true,
      extraCssText: "z-index:999",
      trigger: "axis",
    },
    grid: {
      top: 10,
      left: 5,
      right: 10,
      bottom: 5,
      containLabel: true,
    },
    xAxis: {
      type: "category",
      data: ["1", "2", "3", "4", "5", "6", "7"],
    },
    yAxis: {
      type: "value",
    },
    series: [
      {
        data: [150, 230, 224, 218, 135, 147, 260],
        type: "bar",
        color: new proxy.$echarts.graphic.LinearGradient(0, 0, 0, 1, [
          {
            offset: 0,
            color: "rgba(from #ec7602 r g b / 0.5)",
          },
          {
            offset: 1,
            color: "#ec7602",
          },
        ]),
      },
    ],
  };
});
const xlxy_rcyycb_options = computed(() => {
  return {
    tooltip: {
      appendToBody: true,
      extraCssText: "z-index:999",
      trigger: "axis",
    },
    grid: {
      top: 10,
      left: 5,
      right: 10,
      bottom: 5,
      containLabel: true,
    },
    xAxis: {
      type: "category",
      data: ["1", "2", "3", "4", "5", "6", "7"],
    },
    yAxis: {
      type: "value",
    },
    series: [
      {
        data: [150, 230, 224, 218, 135, 147, 260],
        type: "bar",
        color: new proxy.$echarts.graphic.LinearGradient(0, 0, 0, 1, [
          {
            offset: 0,
            color: "rgba(from #ec7602 r g b / 0.5)",
          },
          {
            offset: 1,
            color: "#ec7602",
          },
        ]),
      },
    ],
  };
});
/******************************** 线路效益 ********************************/
/******************************** 运营服务 ********************************/
const yyfw_sdb_options = computed(() => {
  return {
    tooltip: {
      appendToBody: true,
      extraCssText: "z-index:999",
      trigger: "axis",
    },
    grid: {
      top: 10,
      left: 5,
      right: 10,
      bottom: 5,
      containLabel: true,
    },
    xAxis: {
      type: "value",
    },
    yAxis: {
      type: "category",
      data: ["小汽车", "公交"],
    },
    series: [
      {
        data: [detail.value?.yxsdb?.ptAvg || 0, detail.value?.yxsdb?.carAvg || 0],
        type: "bar",
        color: new proxy.$echarts.graphic.LinearGradient(1, 0, 0, 0, [
          {
            offset: 0,
            color: "rgba(21, 105, 222, 0.5)",
          },
          {
            offset: 1,
            color: "#1569de",
          },
        ]),
      },
    ],
  };
});
/******************************** 运营服务 ********************************/
/******************************** 场站设施 ********************************/
/******************************** 场站设施 ********************************/

/******************************** 地图图层 ********************************/

const LineWidthRef = inject("LineWidthRef", ref(20));
const FlowWidthStepRef = inject("FlowWidthStepRef", ref(20));
const FlowControlRef = inject("FlowControlRef", ref(false));
const _NetworkLayer = new NetworkLayer({
  zIndex: 999,
  lineWidth: LineWidthRef.value,
  flowWidthStep: FlowWidthStepRef.value,
  flowControl: FlowControlRef.value,
});
injectSync("MapRef").then((MapRef) => {
  MapRef.value?.addLayer(_NetworkLayer);
  _NetworkLayer.setTileSource(props.model);
});
watch(LineWidthRef, (value) => {
  _NetworkLayer.setLineWidth(value);
});
watch(FlowWidthStepRef, (value) => {
  _NetworkLayer.setFlowWidthStep(value);
});
watch(FlowControlRef, (value) => {
  _NetworkLayer.setFlowControl(value);
});
watch(() => props.model, (model) => {
  if (model) {
    _NetworkLayer.setTileSource(model);
  }
});

import { dataInfo } from "@/api/data.js";
const detail = ref(null);
dataInfo({ datasource: props.model }).then((res) => {
  console.log(res);
  detail.value = res.data;
});

/******************************** 地图图层 ********************************/
onUnmounted(() => {
  _NetworkLayer.dispose();
  setRightPanelForOverview(false);
});
</script>

<style lang="scss" scoped>
.SJZL {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  .card {
    :deep(.MCard_body_box) {
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }
    .el-checkbox {
      --el-checkbox-height: 25px;
      margin: 0;
    }
  }
}
.SJZL_right_card {
  --theme-color: var(--el-color-primary);
  width: 470px;
  &.theme_ec7602 {
    --theme-color: #d97706;
  }
  &.theme_9acd32 {
    --theme-color: #0f9f6e;
  }
  &.theme_primary_blue {
    --theme-color: #1569de;
  }
  
  &.ztsp_card {
    .row2 {
      height: 195px;
    }
    .item1 {
      .title {
        font-size: 15px !important;
        line-height: 20px !important;
      }
      .num {
        font-size: 30px !important;
        margin-top: 6px !important;
      }
      .unit {
        font-size: 13px !important;
        margin-top: 4px !important;
      }
      .indicator-bar {
        .indicator-label {
          font-size: 10.5px !important;
        }
        .indicator-track {
          height: 6px !important;
          border-radius: 3px !important;
        }
        .indicator-fill {
          border-radius: 3px !important;
        }
      }
    }
    .item3 {
      .title {
        font-size: 15px !important;
        line-height: 20px !important;
        margin-bottom: 4px !important;
      }
    }
  }
}

.SJZL_grid {
  width: 100%;
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  grid-auto-flow: row dense;
  grid-auto-rows: minmax(155px, auto);
  gap: var(--space-sm);
  padding: var(--space-xs) var(--space-2xs);
  box-sizing: border-box;

  .row1 {
    grid-column: span 1;
    height: 155px;
  }
  .row2 {
    grid-column: span 2;
    height: 155px;
  }
}

.row1, .row2 {
  background: var(--app-card-bg-tint);
  border: 1px solid rgba(from var(--theme-color) r g b / 0.12);
  border-radius: var(--app-card-radius);
  padding: 12px 14px;
  transition: border-color 0.2s ease, background-color 0.2s ease;
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: hidden;
  box-sizing: border-box;

  &:hover {
    border-color: rgba(from var(--theme-color) r g b / 0.25);
    
    .icon-badge {
      background: rgba(from var(--theme-color) r g b / 0.1);
    }
  }
}

.item1 {
  .title {
    font-size: 13px;
    color: var(--app-muted);
    font-weight: 600;
    line-height: 18px;
  }
  .num {
    margin-top: 8px;
    font-size: 24px;
    font-weight: bold;
    color: var(--theme-color);
    text-align: left;
    font-family: "Outfit", "Inter", sans-serif;
  }
  .unit {
    font-size: 11px;
    color: var(--app-muted);
    text-align: left;
    margin-top: 2px;
    font-weight: 500;
  }
}

.icon-badge {
  position: absolute;
  top: 12px;
  right: 12px;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: rgba(from var(--theme-color) r g b / 0.06);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background-color 0.2s ease;
  
  .icon {
    width: 18px;
    height: 18px;
    color: var(--theme-color);
  }
}

.indicator-bar {
  margin-top: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
  
  .indicator-label {
    font-size: 9px;
    color: var(--app-muted-soft);
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.5px;
  }
  
  .indicator-track {
    width: 100%;
    height: 4px;
    background-color: #f1f5f9;
    border-radius: 2px;
    overflow: hidden;
  }
  
  .indicator-fill {
    height: 100%;
    border-radius: 2px;
    background: var(--theme-color);
  }
}

.item2 {
  display: flex;
  flex-direction: column;
  height: 100%;
  
  .title {
    font-size: 13px;
    color: var(--app-muted);
    font-weight: 600;
    line-height: 18px;
    margin-bottom: 4px;
  }
  
  .num {
    font-size: 24px;
    font-weight: bold;
    color: var(--theme-color);
    font-family: "Outfit", "Inter", sans-serif;
  }
  
  .unit {
    font-size: 11px;
    color: var(--app-muted);
    margin-left: 4px;
    font-weight: 500;
  }
  
  .chart_box {
    width: 100% !important;
    height: 0 !important;
    flex: 1;
    margin-top: 4px;
  }
}

.item3 {
  display: flex;
  flex-direction: column;
  height: 100%;
  
  .title {
    font-size: 13px;
    color: var(--app-muted);
    font-weight: 600;
    line-height: 18px;
    margin-bottom: 2px;
  }
  
  .chart_box {
    width: 100% !important;
    height: 0 !important;
    flex: 1;
  }
}

.rcxcs {
  .num,
  .unit {
    width: auto;
  }
  .icon-badge {
    position: absolute;
    top: 12px;
    right: 12px;
    width: 32px;
    height: 32px;
  }
}

.chart_box {
  position: relative;
  .chart {
    position: absolute;
    top: 0;
    left: 0;
    width: 100%;
    height: 100%;
    transform-origin: top left;
  }
}
</style>
