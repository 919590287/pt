<template>
  <div class="CXZFX" v-bind="$attrs">
    <MCard class="card empty-card" :open="true" title="出行分析">
      <template #body>
        <div class="empty-state compact">
          <div class="empty-title">当前模型暂无出行者筛选项</div>
          <div class="empty-desc">可先查看总体水平、线路或站点指标。</div>
        </div>
      </template>
    </MCard>
  </div>

  <teleport to="#datavisualization_index_box2" defer v-if="activeDatavisualizationTab === '出行分析'">
    <MCard2 class="SJZL_right_card empty-right-card" title="出行分析" :open="true">
      <template #body>
        <div class="empty-state spacious">
          <div class="empty-title">暂无出行者画像数据</div>
          <div class="empty-desc">此模型尚未加载出行距离、耗时和目的分布。</div>
        </div>
      </template>
    </MCard2>
  </teleport>
</template>

<script setup>
import { ref, onUnmounted, watch, inject } from "vue";
import MCard from "./MCard.vue";
import MCard2 from "./MCard2.vue";

const props = defineProps({
  model: String,
});

const rightPanelHasContent = inject("rightPanelHasContent", ref(false));
const activeDatavisualizationTab = inject("activeDatavisualizationTab", ref(""));

function updateRightPanelVisibility() {
  if (activeDatavisualizationTab.value === "出行分析") {
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

.empty-card {
  border: 1px solid rgba(21, 105, 222, 0.1) !important;
  box-shadow: none !important;
  border-radius: var(--app-panel-radius) !important;
  background: var(--app-card-bg);
  overflow: hidden;

  :deep(.MCard_title_box) {
    background: rgba(21, 105, 222, 0.045) !important;
    border-bottom: 1px solid rgba(21, 105, 222, 0.08) !important;
  }
}

.empty-state {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: flex-start;
  box-sizing: border-box;

  &.compact {
    min-height: 112px;
    padding: var(--space-md);
  }

  &.spacious {
    min-height: 220px;
    padding: var(--space-xl);
  }

  .empty-title {
    font-size: 14px;
    font-weight: 600;
    color: var(--app-ink);
    margin-bottom: var(--space-xs);
  }

  .empty-desc {
    font-size: 13px;
    color: var(--app-muted);
    line-height: 1.5;
    max-width: 300px;
  }
}

.empty-right-card {
  width: 470px;
  background: var(--app-card-bg);
  border-radius: var(--app-panel-radius);
  box-shadow: none;
}
</style>
