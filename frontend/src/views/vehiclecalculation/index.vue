<template>
  <div ref="panelRef" :style="panelStyle" class="placeholder-panel">
    <div ref="handleRef" class="panel-header">
      <div class="header-title">
        <span class="icon">🚌</span>
        <span>配车测算</span>
      </div>
    </div>
    <div class="panel-content">
      <el-empty description="配车测算模块建设中，敬请期待...">
        <template #image>
          <div class="glow-box">
            <span class="emoji-glow">🚌</span>
          </div>
        </template>
      </el-empty>
    </div>
  </div>
</template>

<script setup>
import { ref } from "vue";
import { useDraggable } from "@vueuse/core";

const panelRef = ref(null);
const handleRef = ref(null);
const { style: panelStyle } = useDraggable(panelRef, {
  initialValue: { x: 20, y: 120 },
  handle: handleRef,
});
</script>

<style lang="scss" scoped>
.placeholder-panel {
  position: fixed;
  z-index: var(--z-panel);
  width: min(420px, calc((100vw - 40px) / var(--app-panel-scale)));
  background: var(--app-panel-bg);
  border: 1px solid var(--app-border);
  box-shadow: var(--app-shadow-panel);
  border-radius: var(--app-panel-radius);
  cursor: default;
  user-select: text;
  display: flex;
  flex-direction: column;
  color: var(--app-ink);
  overflow: hidden;
  scale: var(--app-panel-scale);
  transform-origin: top left;
  transition: border-color 0.2s ease;
  
  &:hover {
    border-color: rgba(21, 105, 222, 0.28);
  }
}

.panel-header {
  cursor: grab;
  user-select: none;
  display: flex;
  padding: var(--space-xs) var(--space-md);
  align-items: center;
  min-height: 42px;
  background: rgba(21, 105, 222, 0.07);
  color: var(--app-blue);
  border-bottom: 1px solid rgba(21, 105, 222, 0.15);

  &:active {
    cursor: grabbing;
  }

  .header-title {
    display: flex;
    align-items: center;
    gap: var(--space-xs);
    font-size: 15px;
    font-weight: 750;
    
    .icon {
      font-size: 16px;
    }
  }
}

.panel-content {
  padding: var(--space-xl) var(--space-md);
  display: flex;
  justify-content: center;
  align-items: center;
}

.glow-box {
  width: 80px;
  height: 80px;
  background: rgba(21, 105, 222, 0.06);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 0 20px rgba(21, 105, 222, 0.15);
  animation: pulse 2s infinite ease-in-out;
}

.emoji-glow {
  font-size: 40px;
}

@keyframes pulse {
  0% {
    transform: scale(0.95);
    box-shadow: 0 0 0 0 rgba(21, 105, 222, 0.2);
  }
  70% {
    transform: scale(1);
    box-shadow: 0 0 0 15px rgba(21, 105, 222, 0);
  }
  100% {
    transform: scale(0.95);
    box-shadow: 0 0 0 0 rgba(21, 105, 222, 0);
  }
}
</style>
