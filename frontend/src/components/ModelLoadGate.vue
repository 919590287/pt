<!-- 模型依赖页门禁：只等待用户当前选中的目标模型；数据管理不受影响。 -->
<template>
  <Transition name="gate-fade">
    <div v-if="gateApplies" class="global-model-gate" role="status" aria-live="polite" aria-busy="true">
      <div class="gate-card" role="dialog" aria-modal="false" aria-label="模型加载进度">
        <div class="gate-head">
          <span class="gate-spinner" :class="{ 'is-failed': progress.failed }" aria-hidden="true"></span>
          <div class="gate-head-text">
            <div class="gate-title">{{ gateTitle }}</div>
            <div class="gate-subtitle">当前页面依赖所选模型，加载完成后自动进入；数据管理仍可独立使用</div>
          </div>
        </div>

        <div v-if="hasSchemes" class="gate-selects">
          <el-select
            :model-value="runtime.gateScheme"
            size="default"
            placeholder="选择方案"
            aria-label="选择方案"
            @change="runtime.selectGateScheme"
          >
            <el-option v-for="scheme in runtime.schemes" :key="scheme" :label="scheme" :value="scheme" />
          </el-select>
          <el-select
            :model-value="runtime.gateTarget"
            size="default"
            filterable
            placeholder="选择模型"
            aria-label="选择要加载的模型"
            :disabled="!runtime.gateModels.length"
            @change="runtime.selectGateModel"
          >
            <el-option v-for="item in runtime.gateModels" :key="item.name" :label="modelLabel(item)" :value="item.name" />
          </el-select>
        </div>

        <template v-if="hasSchemes && runtime.gateModels.length">
          <el-progress
            class="gate-progress"
            :percentage="progress.percent"
            :status="progress.failed ? 'exception' : undefined"
            :stroke-width="12"
          />
          <div class="gate-message" :title="progress.message">{{ progress.message }}</div>
          <div class="gate-meta">
            <span>已用 {{ formatDuration(progress.elapsedSeconds) }}</span>
            <span>预计剩余 {{ formatDuration(progress.etaSeconds) }}</span>
          </div>
          <div v-if="runtime.gateError" class="gate-error">{{ runtime.gateError }}</div>
          <div v-if="progress.failed || runtime.gateError" class="gate-actions">
            <el-button type="primary" :loading="runtime.isSwitchingTarget" @click="runtime.retryGateLoad">重新加载</el-button>
          </div>
        </template>
        <div v-else-if="runtime.bootstrapped" class="gate-empty">
          {{ hasSchemes ? "当前方案暂无可用模型，请先导入或生成模型。" : "暂无可用方案，请先导入模型数据。" }}
        </div>
      </div>
    </div>
  </Transition>
</template>

<script setup>
import { computed } from "vue";
import { useRoute } from "vue-router";
import { useModelRuntimeStore } from "@/stores/modelRuntime.js";
import { formatDuration } from "@/utils/modelLoadProgress.js";

const runtime = useModelRuntimeStore();
const route = useRoute();
const progress = computed(() => runtime.gateProgress);
const hasSchemes = computed(() => runtime.schemes.length > 0);
const gateApplies = computed(() => route.meta?.requiresModel !== false && runtime.gateVisible);

const gateTitle = computed(() => {
  if (!runtime.bootstrapped) return "正在检查模型状态";
  if (!hasSchemes.value || !runtime.gateModels.length) return "暂无可用模型";
  return progress.value.title;
});

function modelLabel(item) {
  const text = String(item?.displayName || item?.name || "").trim();
  if (!text) return "";
  const parts = text.replace(/\\/g, "/").split("/").filter(Boolean);
  return parts[parts.length - 1] || text;
}
</script>

<style lang="scss" scoped>
.global-model-gate {
  position: fixed;
  inset: 0;
  top: var(--app-header-height, 58px);
  z-index: calc(var(--z-header, 1000) - 1);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: clamp(24px, 6vw, 72px);
  color: var(--app-ink);
  /* 底图仍然可见，仅阻断页面交互 */
  background: rgba(246, 249, 252, 0.35);
  backdrop-filter: blur(1.5px);
  pointer-events: auto;
}

.gate-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm, 12px);
  width: min(560px, calc(100vw - 32px));
  padding: 26px 30px;
  border: 1px solid var(--app-border-strong, rgba(17, 32, 58, 0.14));
  border-radius: var(--app-panel-radius, 14px);
  background: rgba(251, 253, 255, 0.97);
  box-shadow: 0 18px 48px rgba(31, 45, 61, 0.16);
}

.gate-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.gate-spinner {
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  margin-top: 3px;
  border-radius: 50%;
  border: 3px solid rgba(0, 113, 227, 0.18);
  border-top-color: var(--app-blue, #0071e3);
  animation: gate-spin 0.9s linear infinite;

  &.is-failed {
    animation: none;
    border-color: rgba(220, 76, 93, 0.35);
    border-top-color: #dc2626;
  }
}

@keyframes gate-spin {
  to {
    transform: rotate(360deg);
  }
}

.gate-head-text {
  min-width: 0;
}

.gate-title {
  color: var(--app-ink);
  font-size: 18px;
  font-weight: 760;
}

.gate-subtitle {
  margin-top: 2px;
  color: var(--app-muted);
  font-size: 12.5px;
}

.gate-selects {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 3fr);
  gap: 10px;

  .el-select {
    width: 100%;
  }
}

.gate-progress {
  width: 100%;
  margin-top: 2px;
}

.gate-message {
  color: var(--app-muted);
  font-size: 13px;
  line-height: 1.55;
  word-break: break-word;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.gate-meta {
  display: flex;
  justify-content: space-between;
  gap: var(--space-md, 16px);
  color: var(--app-muted);
  font-size: 12.5px;
}

.gate-error {
  color: var(--app-coral, #dc2626);
  font-size: 12.5px;
  font-weight: 600;
}

.gate-actions {
  display: flex;
  justify-content: flex-end;
}

.gate-empty {
  color: var(--app-muted);
  font-size: 13.5px;
  line-height: 1.6;
}

.gate-fade-enter-active,
.gate-fade-leave-active {
  transition: opacity 200ms ease;
}

.gate-fade-enter-from,
.gate-fade-leave-to {
  opacity: 0;
}

@media (max-width: 560px) {
  .gate-selects {
    grid-template-columns: 1fr;
  }
}

/* ── 暗色模式（html.dark，跟随底图选择） ── */
html.dark .global-model-gate {
  background: rgba(7, 11, 17, 0.45);
}

html.dark .gate-card {
  background: rgba(17, 23, 31, 0.97);
  box-shadow: 0 18px 48px rgba(2, 6, 12, 0.62);
}

html.dark .gate-spinner {
  border-color: rgba(90, 168, 255, 0.26);
  border-top-color: var(--app-blue, #409cff);

  &.is-failed {
    border-color: rgba(255, 122, 110, 0.4);
    border-top-color: #f87171;
  }
}
</style>
