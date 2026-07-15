<!--
  换乘分析:图表点击全屏弹窗(交互效仿客流分析 XLZL 的 boarding-fullscreen-dialog)。
  单实例挂在 index.vue,各 section 经 taCtx.openChartFullscreen({kicker,title,meta,option}) 打开;
  option 为打开时刻的快照(destroy-on-close,关掉即销毁,不跟随筛选联动——与 PFA 行为一致)。
  样式 .ta-fullscreen-* 在 index.vue 全局 style 中。
-->
<template>
  <el-dialog v-model="visible" class="ta-fullscreen-dialog" fullscreen append-to-body destroy-on-close :lock-scroll="true">
    <template #header>
      <div class="ta-fullscreen-header">
        <div>
          <div class="ta-fullscreen-kicker">{{ state.kicker }}</div>
          <div class="ta-fullscreen-title">{{ state.title }}</div>
        </div>
        <span v-if="state.meta" class="ta-fullscreen-meta">{{ state.meta }}</span>
      </div>
    </template>
    <div class="ta-fullscreen-body">
      <VChart v-if="visible && state.option" class="ta-fullscreen-chart" :option="state.option" autoresize :update-options="UPD" />
    </div>
  </el-dialog>
</template>

<script setup>
import { reactive, ref } from "vue";
import { VChart } from "@/plugins/echarts";

const UPD = { notMerge: true, lazyUpdate: true };
const visible = ref(false);
const state = reactive({ kicker: "", title: "", meta: "", option: null });

function open({ kicker = "换乘分析", title = "", meta = "", option = null } = {}) {
  if (!option) return;
  state.kicker = kicker;
  state.title = title;
  state.meta = meta;
  state.option = option;
  visible.value = true;
}

defineExpose({ open });
</script>
