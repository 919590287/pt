<!-- 站点总览 -->
<template>
  <div class="ZDZL" v-bind="$attrs">
    <MCard class="card" wrap-body-class="body">
      <template #title>
        <div class="title">总体水平</div>
        <el-switch v-model="open" :active-value="true" :inactive-value="false" @change=""> </el-switch>
      </template>
      <template #body>
        <el-checkbox v-model="open" label="" :indeterminate="false" @change="">常住人口密度(人/km²)</el-checkbox>
        <el-checkbox v-model="open" label="" :indeterminate="false" @change="">线网密度(km/km²)</el-checkbox>
        <el-checkbox v-model="open" label="" :indeterminate="false" @change="">车站300m人口覆盖率(%)</el-checkbox>
        <el-checkbox v-model="open" label="" :indeterminate="false" @change="">万人保有量(标台/万人)</el-checkbox>
        <el-checkbox v-model="open" label="" :indeterminate="false" @change="">出行分担率(%)</el-checkbox>
        <el-checkbox v-model="open" label="" :indeterminate="false" @change="">车均日载客量(人次)</el-checkbox>
        <el-checkbox v-model="open" label="" :indeterminate="false" @change="">单班次载客量(人次/班)</el-checkbox>
      </template>
    </MCard>
    <MCard class="card" wrap-body-class="body">
      <template #title>
        <div class="title">需求强度</div>
        <el-switch v-model="open" :active-value="true" :inactive-value="false" @change=""> </el-switch>
      </template>
      <template #body>
        <el-checkbox v-model="open" label="" :indeterminate="false" @change="">日出行次数(次/人)</el-checkbox>
        <el-checkbox v-model="open" label="" :indeterminate="false" @change="">依赖客流比例(%)</el-checkbox>
      </template>
    </MCard>
    <MCard class="card" wrap-body-class="body">
      <template #title>
        <div class="title">线路效益</div>
        <el-switch v-model="open" :active-value="true" :inactive-value="false" @change=""> </el-switch>
      </template>
      <template #body>
        <el-checkbox v-model="open" label="" :indeterminate="false" @change="">线路非直线系数</el-checkbox>
        <el-checkbox v-model="open" label="" :indeterminate="false" @change="">线路重复系数</el-checkbox>
        <el-checkbox v-model="open" label="" :indeterminate="false" @change="">线路满载率(%)</el-checkbox>
        <el-checkbox v-model="open" label="" :indeterminate="false" @change="">线路客流强度(人次/km)</el-checkbox>
        <el-checkbox v-model="open" label="" :indeterminate="false" @change="">车公里运营成本(元/车/km)</el-checkbox>
        <el-checkbox v-model="open" label="" :indeterminate="false" @change="">车单位人次运营成本(元/人次)</el-checkbox>
      </template>
    </MCard>
    <MCard class="card" wrap-body-class="body">
      <template #title>
        <div class="title">运营服务</div>
        <el-switch v-model="open" :active-value="true" :inactive-value="false" @change=""> </el-switch>
      </template>
      <template #body>
        <el-checkbox v-model="open" label="" :indeterminate="false" @change="">公共汽电车与小汽车运行速度比</el-checkbox>
      </template>
    </MCard>
    <MCard class="card" wrap-body-class="body">
      <template #title>
        <div class="title">场站设施</div>
        <el-switch v-model="open" :active-value="true" :inactive-value="false" @change=""> </el-switch>
      </template>
      <template #body>
        <el-checkbox v-model="open" label="" :indeterminate="false" @change="">车均场站面积(m²/标台)</el-checkbox>
      </template>
    </MCard>
  </div>

  <teleport to="#datavisualization_index_box2" defer >
    <div>ZDZL</div>
  </teleport>
</template>

<script setup>
import MCard from "./MCard.vue";

const itemNum = ref(10);
const open = ref(true);
</script>

<style lang="scss" scoped>
.ZDZL {
  display: flex;
  flex-direction: column;
  gap: 10px;
  .card {
    .title {
      width: 0;
      flex: 1;
      font-weight: bold;
    }
    :deep(.body) {
      display: flex;
      flex-direction: column;
    }
    .el-checkbox {
      --el-checkbox-height: 25px;
      margin: 0;
    }
  }
}
</style>
