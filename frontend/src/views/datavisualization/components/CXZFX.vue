<!-- 出行者分析 (Traveler Analysis Placeholder) -->
<template>
  <div class="CXZFX" v-bind="$attrs">
    <MCard class="card placeholder-card" :open="true" title="出行者控制台">
      <template #body>
        <div class="placeholder-content">
          <el-icon class="placeholder-icon"><User /></el-icon>
          <div class="placeholder-title">出行者分析 - 控制面板</div>
          <div class="placeholder-desc">这里是左侧控制面板占位，您可以后续在此添加过滤筛选器、用户特征选择等业务交互。</div>
        </div>
      </template>
    </MCard>
  </div>

  <teleport to="#datavisualization_index_box2" defer v-if="activeDatavisualizationTab === '出行者分析'">
    <MCard2 class="SJZL_right_card placeholder-right-card" title="出行者分析数据看板" :open="true">
      <template #body>
        <div class="placeholder-content-right">
          <el-icon class="placeholder-icon"><TrendCharts /></el-icon>
          <div class="placeholder-title">出行者分析 - 数据看板</div>
          <div class="placeholder-desc">这里是右侧数据看板占位，您可以在此接入出行距离分布、出行耗时、出行目的统计等图表和分析组件。</div>
        </div>
      </template>
    </MCard2>
  </teleport>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch, inject } from "vue";
import { User, TrendCharts } from "@element-plus/icons-vue";
import MCard from "./MCard.vue";
import MCard2 from "./MCard2.vue";

const props = defineProps({
  model: String,
});

const rightPanelHasContent = inject("rightPanelHasContent", ref(false));
const activeDatavisualizationTab = inject("activeDatavisualizationTab", ref(""));

function updateRightPanelVisibility() {
  if (activeDatavisualizationTab.value === "出行者分析") {
    rightPanelHasContent.value = true;
  }
}

watch(activeDatavisualizationTab, () => {
  updateRightPanelVisibility();
}, { immediate: true });

onUnmounted(() => {
  rightPanelHasContent.value = false;
});
</script>

<style lang="scss" scoped>
.CXZFX {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  width: 100%;
}

.placeholder-card {
  border: 1px solid rgba(21, 105, 222, 0.15) !important;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.04) !important;
  border-radius: var(--app-panel-radius) !important;
  background-color: #ffffff;
  overflow: hidden;

  :deep(.MCard_title_box) {
    background-color: rgba(21, 105, 222, 0.05) !important;
    border-bottom: 1px solid rgba(21, 105, 222, 0.1) !important;
  }
}

.placeholder-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--space-xl) var(--space-lg);
  text-align: center;
  min-height: 200px;
  box-sizing: border-box;

  .placeholder-icon {
    font-size: 48px;
    color: rgba(21, 105, 222, 0.6);
    margin-bottom: var(--space-md);
  }

  .placeholder-title {
    font-size: 16px;
    font-weight: 600;
    color: #1a365d;
    margin-bottom: var(--space-xs);
  }

  .placeholder-desc {
    font-size: 13px;
    color: #718096;
    line-height: 1.6;
    max-width: 280px;
  }
}

.placeholder-right-card {
  width: 470px;
  background-color: #ffffff;
  border-radius: var(--app-panel-radius);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
}

.placeholder-content-right {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--space-3xl) var(--space-xl);
  text-align: center;
  min-height: 350px;
  box-sizing: border-box;

  .placeholder-icon {
    font-size: 64px;
    color: rgba(21, 105, 222, 0.6);
    margin-bottom: var(--space-lg);
  }

  .placeholder-title {
    font-size: 18px;
    font-weight: 700;
    color: #1a365d;
    margin-bottom: var(--space-sm);
  }

  .placeholder-desc {
    font-size: 14px;
    color: #718096;
    line-height: 1.6;
    max-width: 320px;
  }
}
</style>
