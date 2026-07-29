<!-- 模型后台加载进度气泡：可收起为小圆钮、可整体拖动，默认停靠在右上角 -->
<template>
  <div
    ref="bubbleRef"
    class="model-progress-bubble"
    :class="{ 'is-collapsed': collapsed, 'is-dragging': dragging }"
    :style="positionStyle"
    role="status"
    aria-live="polite"
  >
    <button
      v-if="collapsed"
      class="bubble-pill"
      type="button"
      :title="`${title}：${percentText}，点击展开`"
      aria-label="展开模型加载进度"
      @pointerdown="onPointerDown"
      @click="onPillClick"
    >
      <svg class="pill-ring" viewBox="0 0 36 36" aria-hidden="true">
        <circle class="ring-bg" cx="18" cy="18" r="15.5" />
        <circle
          class="ring-val"
          :class="{ 'is-failed': failed }"
          cx="18"
          cy="18"
          r="15.5"
          :stroke-dasharray="`${ringLength} ${ringCircumference}`"
        />
      </svg>
      <span class="pill-text" :class="{ 'is-failed': failed }">{{ percentText }}</span>
    </button>

    <template v-else>
      <div class="bubble-head" @pointerdown="onPointerDown">
        <span class="bubble-dot" :class="{ 'is-failed': failed }"></span>
        <span class="bubble-title" :title="title">{{ title }}</span>
        <button
          class="bubble-icon-btn"
          type="button"
          title="收起为气泡"
          aria-label="收起模型加载进度"
          @pointerdown.stop
          @click="collapsed = true"
        >
          <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round">
            <line x1="5" y1="12" x2="19" y2="12" />
          </svg>
        </button>
      </div>
      <div class="bubble-message" :title="message">{{ message }}</div>
      <el-progress
        class="bubble-progress"
        :percentage="clampedPercent"
        :status="failed ? 'exception' : undefined"
        :stroke-width="8"
      />
      <div class="bubble-meta">
        <span>已用 {{ formatDuration(elapsedSeconds) }}</span>
        <span>预计剩余 {{ formatDuration(etaSeconds) }}</span>
      </div>
      <div v-if="showCancel" class="bubble-actions">
        <el-button link size="small" @click="$emit('cancel')">
          {{ cancelText }}
        </el-button>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, ref } from "vue";
import { formatDuration } from "@/utils/modelLoadProgress.js";

const props = defineProps({
  title: { type: String, default: "模型后台加载" },
  message: { type: String, default: "" },
  percent: { type: Number, default: 0 },
  elapsedSeconds: { type: Number, default: -1 },
  etaSeconds: { type: Number, default: -1 },
  failed: { type: Boolean, default: false },
  showCancel: { type: Boolean, default: false },
  cancelText: { type: String, default: "取消切换" },
});

defineEmits(["cancel"]);

const bubbleRef = ref(null);
const collapsed = ref(false);
const dragging = ref(false);
// null = 未拖动过，停靠默认位置（右上角）；拖动后记录 left/top 像素
const position = ref(null);

const clampedPercent = computed(() => Math.max(0, Math.min(100, Math.round(Number(props.percent) || 0))));
const percentText = computed(() => `${clampedPercent.value}%`);
const ringCircumference = 2 * Math.PI * 15.5;
const ringLength = computed(() => (clampedPercent.value / 100) * ringCircumference);

const positionStyle = computed(() => {
  if (!position.value) return undefined;
  return { left: `${position.value.x}px`, top: `${position.value.y}px`, right: "auto" };
});

let dragState = null;

function onPointerDown(event) {
  if (event.button !== 0) return;
  const el = bubbleRef.value;
  if (!el) return;
  const rect = el.getBoundingClientRect();
  dragState = {
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    originX: rect.left,
    originY: rect.top,
    moved: false,
  };
  el.setPointerCapture?.(event.pointerId);
  window.addEventListener("pointermove", onPointerMove);
  window.addEventListener("pointerup", onPointerUp);
}

function onPointerMove(event) {
  if (!dragState || event.pointerId !== dragState.pointerId) return;
  const dx = event.clientX - dragState.startX;
  const dy = event.clientY - dragState.startY;
  if (!dragState.moved && Math.hypot(dx, dy) < 4) return;
  dragState.moved = true;
  dragging.value = true;
  const el = bubbleRef.value;
  const width = el?.offsetWidth || 0;
  const height = el?.offsetHeight || 0;
  const maxX = Math.max(0, window.innerWidth - width - 4);
  const maxY = Math.max(0, window.innerHeight - height - 4);
  position.value = {
    x: Math.min(maxX, Math.max(4, dragState.originX + dx)),
    y: Math.min(maxY, Math.max(4, dragState.originY + dy)),
  };
}

function onPointerUp(event) {
  if (!dragState || event.pointerId !== dragState.pointerId) return;
  const moved = dragState.moved;
  dragState = null;
  dragging.value = false;
  window.removeEventListener("pointermove", onPointerMove);
  window.removeEventListener("pointerup", onPointerUp);
  // 记录本次是否为拖动，供收起态的 click 区分"点击展开"与"拖动"
  lastInteractionWasDrag = moved;
}

let lastInteractionWasDrag = false;

function onPillClick() {
  if (lastInteractionWasDrag) {
    lastInteractionWasDrag = false;
    return;
  }
  collapsed.value = false;
}
</script>

<style lang="scss" scoped>
.model-progress-bubble {
  position: fixed;
  top: calc(var(--app-header-height, 58px) + 12px);
  /* 右边缘与运行监测工具条对齐 */
  right: calc(var(--app-edge, 24px) + var(--app-scaled-64, 64px) + var(--app-scaled-46, 46px));
  z-index: calc(var(--z-header, 1000) + 9);
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: min(46vw, 340px);
  padding: 10px 12px;
  border: 1px solid rgba(21, 105, 222, 0.16);
  border-radius: var(--app-panel-radius, 12px);
  background: rgba(251, 253, 255, 0.96);
  box-shadow: 0 10px 30px rgba(31, 45, 61, 0.14);
  color: var(--app-ink);
  user-select: none;
  touch-action: none;

  &.is-dragging {
    box-shadow: 0 16px 40px rgba(31, 45, 61, 0.22);
    opacity: 0.94;
  }

  &.is-collapsed {
    width: auto;
    padding: 0;
    border: 0;
    background: transparent;
    box-shadow: none;
  }
}

.bubble-pill {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  padding: 0;
  border: 1px solid rgba(21, 105, 222, 0.2);
  border-radius: 50%;
  background: rgba(251, 253, 255, 0.97);
  box-shadow: 0 8px 24px rgba(31, 45, 61, 0.18);
  cursor: grab;

  &:active {
    cursor: grabbing;
  }

  .pill-ring {
    position: absolute;
    inset: 3px;
    transform: rotate(-90deg);

    .ring-bg {
      fill: none;
      stroke: rgba(21, 105, 222, 0.14);
      stroke-width: 3.4;
    }

    .ring-val {
      fill: none;
      stroke: var(--app-blue, #0071e3);
      stroke-width: 3.4;
      stroke-linecap: round;
      transition: stroke-dasharray 320ms ease;

      &.is-failed {
        stroke: #dc2626;
      }
    }
  }

  .pill-text {
    font-size: 12px;
    font-weight: 760;
    color: var(--app-blue-strong, #005bb5);

    &.is-failed {
      color: #dc2626;
    }
  }
}

.bubble-head {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  cursor: grab;

  &:active {
    cursor: grabbing;
  }
}

.bubble-dot {
  flex-shrink: 0;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--app-blue, #0071e3);
  box-shadow: 0 0 0 4px rgba(21, 105, 222, 0.12);
  animation: bubble-pulse 1.6s ease-in-out infinite;

  &.is-failed {
    background: #dc2626;
    box-shadow: 0 0 0 4px rgba(220, 38, 38, 0.12);
    animation: none;
  }
}

@keyframes bubble-pulse {
  0%,
  100% {
    box-shadow: 0 0 0 3px rgba(21, 105, 222, 0.1);
  }
  50% {
    box-shadow: 0 0 0 6px rgba(21, 105, 222, 0.16);
  }
}

.bubble-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12.5px;
  font-weight: 760;
}

.bubble-icon-btn {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--app-muted);
  cursor: pointer;

  &:hover {
    color: var(--app-ink);
    background: rgba(21, 105, 222, 0.1);
  }
}

.bubble-message {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--app-muted);
  font-size: 12px;
  user-select: text;
}

.bubble-progress {
  width: 100%;

  :deep(.el-progress__text) {
    min-width: 34px;
    font-size: 11.5px !important;
    font-weight: 700;
  }
}

.bubble-meta {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  color: var(--app-muted);
  font-size: 11.5px;
}

.bubble-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: -2px;
}

/* ── 暗色模式（html.dark，跟随底图选择） ── */
html.dark .model-progress-bubble {
  border-color: rgba(64, 156, 255, 0.28);
  background: rgba(17, 23, 31, 0.96);
  box-shadow: 0 10px 30px rgba(2, 6, 12, 0.55);

  &.is-dragging {
    box-shadow: 0 16px 40px rgba(2, 6, 12, 0.68);
  }

  &.is-collapsed {
    border: 0;
    background: transparent;
    box-shadow: none;
  }
}

html.dark .bubble-pill {
  border-color: rgba(64, 156, 255, 0.32);
  background: rgba(17, 23, 31, 0.97);
  box-shadow: 0 8px 24px rgba(2, 6, 12, 0.6);

  .pill-ring .ring-bg {
    stroke: rgba(64, 156, 255, 0.2);
  }

  .pill-ring .ring-val.is-failed {
    stroke: #f87171;
  }

  .pill-text.is-failed {
    color: #f87171;
  }
}

html.dark .bubble-dot.is-failed {
  background: #f87171;
  box-shadow: 0 0 0 4px rgba(248, 113, 113, 0.16);
}

html.dark .bubble-icon-btn:hover {
  background: rgba(64, 156, 255, 0.16);
}
</style>
