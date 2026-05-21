<!-- 线路总览 -->
<template>
  <div class="SJZL" v-bind="$attrs">
    <div class="from_item">
      <div ref="menuHandle" class="label">选择线路</div>
      <el-select-v2 v-model="selectRouteId" filterable :options="routeList" :loading="routeLoading" :props="routeProps" />
    </div>

    <template v-if="selectRouteDetail">
      <MCard class="card" v-for="item in list">
        <template #title="attrs">
          <div :class="`title ${attrs.class}`">{{ item.title }}</div>
          <el-switch v-if="!item.hideSwitch" v-model="item.switch" :active-value="true" :inactive-value="false" @click.stop />
        </template>
        <template #body>
          <el-checkbox v-for="item2 in item.children" v-model="item2.check" :disabled="!item.switch">{{ item2.title }}</el-checkbox>
        </template>
      </MCard>
    </template>
    <el-empty v-else-if="selectRouteId" description="加载中，请稍等...." />
    <el-empty v-else description="请选择线路" />
  </div>

  <teleport to="#datavisualization_index_box2" defer>
    <div>XLZL</div>
  </teleport>
</template>

<script setup>
import { getRouteDetail, getRouteInfo, getRouteList } from "@/api/route";
import MCard from "./MCard.vue";
import { RouteLayer } from "../layers/RouteLayer.js";

const props = defineProps({
  model: String,
});
const { proxy } = getCurrentInstance();

const selectRouteId = ref(null);
const selectRouteDetail = ref(null);
const routeList = ref([]);
const routeLoading = ref(false);
const routeProps = {
  value: "routeId",
  label: "routeName",
};
function handleGetRouteList(query) {
  routeLoading.value = true;
    getRouteList({ datasource: props.model })
    .then((res) => {
      const data = res.data || [];
      data.forEach((item) => {
        if (item.links) {
          item.links = markRaw(item.links);
        }
        if (item.facilities) {
          item.facilities = markRaw(item.facilities);
        }
      });
      routeList.value = data;
    })
    .finally(() => {
      routeLoading.value = false;
    });
}
handleGetRouteList();

const list = ref([
  {
    title: "基础信息",
    switch: true,
    children: [{ title: "基础信息", check: true }],
  },
  {
    title: "线路效益",
    switch: true,
    children: [
      { title: "线路非直线系数", check: true },
      { title: "线路重复系数", check: true },
      { title: "线路满载率(%)", check: true },
      { title: "线路客流强度(人次/km)", check: true },
      { title: "车公里运营成本(元/车/km)", check: true },
      { title: "车单位人次运营成本(元/人次)", check: true },
    ],
  },
  {
    title: "线路分析",
    switch: true,
    hideSwitch: true,
    children: [
      { title: "上下车客流", check: false },
      { title: "载客量", check: false },
      { title: "总载客量", check: false },
      { title: "上下车站点热力图", check: false },
      { title: "站点od客流量", check: false },
      { title: "发车时刻表", check: false },
    ],
  },
]);
</script>

<style lang="scss" scoped>
.SJZL {
  display: flex;
  flex-direction: column;
  gap: 10px;
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
    --theme-color: #ec7602;
  }
  &.theme_9acd32 {
    --theme-color: #9acd32;
  }
  &.theme_4f3db4 {
    --theme-color: #4f3db4;
  }
}

.from_item {
  display: flex;
  align-items: center;
  color: $color-primary;
  gap: 10px;
  .label {
    flex-shrink: 0;
  }
}

.SJZL_grid {
  width: 100%;
  height: 100%;
  display: grid;
  grid-template-columns: repeat(3, 150px);
  grid-auto-flow: row dense;
  grid-auto-rows: 150px;
  overflow: hidden;

  .row1 {
    border-collapse: collapse;
    grid-column: span 1;
    grid-row: span 1;
    border-right: 1px solid var(--el-border-color);
    border-bottom: 1px solid var(--el-border-color);
    margin: -1px;
  }
  .row2 {
    grid-column: span 2;
    grid-row: span 1;
    border-right: 1px solid var(--el-border-color);
    border-bottom: 1px solid var(--el-border-color);
    margin: -1px;
  }
}

.item1 {
  position: relative;
  padding: 10px;
  .title {
    font-size: 14px;
    color: #333;
    font-weight: 500;
    line-height: 20px;
  }
  .num {
    margin-top: 15px;
    font-size: 28px;
    font-weight: bold;
    color: var(--theme-color);
    text-align: center;
  }
  .unit {
    font-size: 12px;
    color: var(--theme-color);
    text-align: center;
  }
  .icon {
    position: absolute;
    bottom: 10px;
    left: 10px;
    width: 40px;
    height: 40px;
    color: rgb(from var(--theme-color) r g b / 0.5);
    // background-color: #333;
  }
}

.item2 {
  position: relative;
  padding: 10px;
  display: flex;
  flex-direction: column;
  .title {
    font-size: 14px;
    color: #333;
    font-weight: 500;
    line-height: 20px;
  }
  .num {
    font-size: 28px;
    font-weight: bold;
    color: var(--theme-color);
  }
  .unit {
    font-size: 12px;
    color: var(--theme-color);
  }
  .icon {
    position: absolute;
    top: 10px;
    right: 10px;
    width: 40px;
    height: 40px;
    color: rgb(from var(--theme-color) r g b / 0.5);
  }
  .icon2 {
    position: absolute;
    top: 25px;
    right: 55px;
    width: 25px;
    height: 25px;
    color: rgb(from var(--theme-color) r g b / 0.5);
  }
  .chart_box {
    width: calc(100%) !important;
    height: 0 !important;
    flex: 1;
  }
}

.item3 {
  position: relative;
  padding: 10px;
  .title {
    font-size: 14px;
    color: #333;
    font-weight: 500;
    line-height: 20px;
    white-space: nowrap;
  }
  .chart_box {
    width: calc(100%) !important;
    height: calc(100% - 20px) !important;
  }
}

.rcxcs {
  .num,
  .unit {
    width: 50%;
  }
  .icon {
    position: absolute;
    left: auto;
    top: 40px;
    right: 40px;
    width: 70px;
    height: 70px;
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
