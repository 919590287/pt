<template>
  <div :class="['map-controls-toolbar', withPanel ? 'with-panel' : 'without-panel']">
    <div class="control-block">
      <button class="control-btn" type="button" @click="$emit('zoom-in')" title="放大" aria-label="放大地图">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
          <line x1="12" y1="5" x2="12" y2="19"></line>
          <line x1="5" y1="12" x2="19" y2="12"></line>
        </svg>
      </button>
      <button class="control-btn" type="button" @click="$emit('zoom-out')" title="缩小" aria-label="缩小地图">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
          <line x1="5" y1="12" x2="19" y2="12"></line>
        </svg>
      </button>
      <button :class="['control-btn', 'td-btn', is3dActive ? 'active' : '']" type="button" @click="$emit('toggle-3d')" title="3D视图" aria-label="切换3D视图" :aria-pressed="is3dActive">
        3D
      </button>
      <button class="control-btn compass-btn" type="button" @click="$emit('reset-compass')" title="指北针" aria-label="重置地图朝北">
        <div class="pitch-arrows">
          <svg class="caret-up" viewBox="0 0 24 24" width="10" height="10" fill="currentColor">
            <polygon points="12,4 2,18 22,18"></polygon>
          </svg>
          <svg class="caret-down" viewBox="0 0 24 24" width="10" height="10" fill="currentColor">
            <polygon points="12,20 2,6 22,6"></polygon>
          </svg>
        </div>
      </button>
    </div>

    <div class="control-block settings-block">
      <button
        :class="['control-btn', selectedRange !== allRangeLabel || showRangePopover ? 'active' : '']"
        type="button"
        @click="toggleRangePopover"
        :title="rangeButtonTitle"
        :aria-label="rangeButtonAriaLabel"
        :aria-expanded="showRangePopover"
        aria-controls="dm-range-popover"
      >
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round">
          <path d="M3 6.5 8 4l8 2.5 5-2.5v13.5L16 20l-8-2.5-5 2.5V6.5Z"></path>
          <path d="M8 4v13.5"></path>
          <path d="M16 6.5V20"></path>
        </svg>
      </button>
      <button
        :class="['control-btn', showStylePopover ? 'active' : '']"
        type="button"
        @click="toggleStylePopover"
        title="线路和站点样式"
        aria-label="打开线路和站点样式"
        :aria-expanded="showStylePopover"
        aria-controls="dm-style-popover"
      >
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
          <line x1="4" y1="7" x2="20" y2="7"></line>
          <circle cx="15" cy="7" r="1.5" fill="currentColor"></circle>
          <line x1="4" y1="12" x2="20" y2="12"></line>
          <circle cx="17" cy="12" r="1.5" fill="currentColor"></circle>
          <line x1="4" y1="17" x2="20" y2="17"></line>
          <circle cx="9" cy="17" r="1.5" fill="currentColor"></circle>
        </svg>
      </button>
    </div>

    <Transition name="popover-fade">
      <div v-if="showRangePopover" id="dm-range-popover" class="range-popover" role="dialog" aria-modal="false" @click.stop @keydown.esc.stop.prevent="showRangePopover = false">
        <div class="popover-title">选择行政区</div>
        <div v-if="loadingRanges" class="range-state">行政区加载中</div>
        <div v-else-if="rangeOptions.length" class="range-list" role="listbox" aria-label="行政区显示范围">
          <button
            v-for="item in rangeOptions"
            :key="item"
            :class="['range-option', selectedRange === item ? 'active' : '']"
            type="button"
            role="option"
            :aria-selected="selectedRange === item"
            @click="selectRange(item)"
          >
            <span class="range-option-name">{{ item }}</span>
            <svg v-if="selectedRange === item" class="range-option-check" viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <polyline points="20 6 9 17 4 12"></polyline>
            </svg>
          </button>
        </div>
        <p v-else class="range-state">暂无行政区范围</p>
        <p v-if="rangeError" class="range-error">{{ rangeError }}</p>
      </div>
    </Transition>

    <Transition name="popover-fade">
      <div v-if="showStylePopover" id="dm-style-popover" class="style-popover" role="dialog" aria-modal="false" @click.stop @keydown.esc.stop.prevent="showStylePopover = false">
        <div class="popover-title">图层样式</div>
        <div class="slider-row">
          <span class="label">
            <span>线路粗细</span>
            <span class="val-text">{{ `${lineWidth}px` }}</span>
          </span>
          <el-slider v-model="lineWidth" :min="0.1" :max="2" :step="0.1" @input="$emit('paint-input')" />
        </div>
        <div class="slider-row">
          <span class="label">
            <span>站点大小</span>
            <span class="val-text">{{ `${stationSize}px` }}</span>
          </span>
          <el-slider v-model="stationSize" :min="32" :max="96" :step="1" @input="$emit('paint-input')" />
        </div>
      </div>
    </Transition>
  </div>
</template>

<script setup>
const props = defineProps({
  withPanel: { type: Boolean, default: false },
  is3dActive: { type: Boolean, default: false },
  rangeOptions: { type: Array, default: () => [] },
  selectedRange: { type: String, default: "" },
  allRangeLabel: { type: String, default: "全市" },
  loadingRanges: { type: Boolean, default: false },
  rangeError: { type: String, default: "" },
});

const emit = defineEmits(["zoom-in", "zoom-out", "toggle-3d", "reset-compass", "select-range", "before-open", "paint-input"]);

// 滑块每帧写这两个 model，popover 开合也只在本组件内 patch，与整页渲染解耦
const lineWidth = defineModel("lineWidth", { type: Number, default: 1.2 });
const stationSize = defineModel("stationSize", { type: Number, default: 32 });

const showRangePopover = ref(false);
const showStylePopover = ref(false);

const rangeButtonTitle = computed(() =>
  props.selectedRange === props.allRangeLabel
    ? "选择行政区显示范围"
    : `显示范围：${props.selectedRange || props.allRangeLabel}，点击恢复全市`,
);
const rangeButtonAriaLabel = computed(() =>
  props.selectedRange === props.allRangeLabel
    ? "打开行政区显示范围列表"
    : `恢复全市显示范围，当前为${props.selectedRange || props.allRangeLabel}`,
);

function toggleRangePopover() {
  if (props.selectedRange !== props.allRangeLabel) {
    emit("select-range", props.allRangeLabel);
    showRangePopover.value = false;
    return;
  }
  if (!showRangePopover.value) {
    showStylePopover.value = false;
    emit("before-open", "range");
  }
  showRangePopover.value = !showRangePopover.value;
}

function toggleStylePopover() {
  if (!showStylePopover.value) {
    showRangePopover.value = false;
    emit("before-open", "style");
  }
  showStylePopover.value = !showStylePopover.value;
}

function selectRange(item) {
  emit("select-range", item);
  showRangePopover.value = false;
}

function closePopovers() {
  showRangePopover.value = false;
  showStylePopover.value = false;
}

defineExpose({ closePopovers });
</script>

<style lang="scss" scoped>
/* 样式自 index.vue 迁入（渲染边界拆分）；tokens.css 中的全局规则不受影响 */
.map-controls-toolbar {
  scale: var(--app-panel-scale);
}

.map-controls-toolbar {
  position: fixed;
  top: calc(var(--app-header-height) + var(--space-sm));
  right: var(--app-edge);
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-sm);
  z-index: calc(var(--z-header) + 5);
  transform-origin: top right;

  &.with-panel {
    right: calc(var(--app-edge) + 394px);
  }
}

.control-block {
  display: flex;
  flex-direction: column;
  width: 44px;
  overflow: hidden;
  border-radius: var(--app-card-radius);
  background-color: var(--app-card-bg);
  border: 1px solid rgba(21, 105, 222, 0.11);
  box-shadow: var(--app-shadow-sm);
}

.control-btn {
  width: 44px;
  height: 44px;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: var(--app-ink);
  cursor: pointer;
  transition:
    background-color var(--app-motion-normal) var(--app-ease-out),
    color var(--app-motion-normal) var(--app-ease-out),
    transform var(--app-motion-fast) var(--app-ease-press);

  &:not(:last-child) {
    border-bottom: 1px solid rgba(21, 105, 222, 0.08);
  }

  &:hover,
  &.active {
    background-color: var(--app-cyan-soft);
    color: var(--app-cyan-strong);
  }

  &:active {
    transform: translateY(1px);
  }

  svg,
  .pitch-arrows {
    transition: transform var(--app-motion-normal) var(--app-ease-out);
  }

  &:hover svg,
  &:hover .pitch-arrows {
    transform: translateY(-1px);
  }
}

.td-btn {
  font-size: 11px;
  font-weight: 700;
}

.compass-btn .pitch-arrows {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1px;
  color: currentColor;
}

.range-popover,
.style-popover {
  position: absolute;
  right: 48px;
  width: min(240px, calc(100vw - 96px));
  padding: 14px 14px 12px;
  border-radius: 8px;
  background: rgba(251, 253, 255, 0.96);
  border: 1px solid rgba(21, 105, 222, 0.14);
  box-shadow: 0 16px 34px rgba(15, 39, 68, 0.14);
}

.range-popover {
  top: 188px;
}

.style-popover {
  top: 236px;
}

.popover-title {
  color: #12304f;
  font-size: 13px;
  font-weight: 700;
  margin-bottom: 10px;
}

.range-list {
  width: 100%;
  max-height: min(310px, calc(100vh - 260px));
  display: grid;
  gap: 6px;
  overflow-y: auto;
  padding-right: 2px;
  scrollbar-width: thin;
}

.range-option {
  width: 100%;
  min-height: 34px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: var(--app-ink);
  font-size: 13px;
  line-height: 1.25;
  font-weight: 600;
  text-align: left;
  cursor: pointer;
  transition:
    background-color var(--app-motion-normal) var(--app-ease-out),
    border-color var(--app-motion-normal) var(--app-ease-out),
    color var(--app-motion-normal) var(--app-ease-out),
    transform var(--app-motion-fast) var(--app-ease-press);
}

.range-option:hover,
.range-option:focus-visible {
  background: var(--app-cyan-soft);
  border-color: rgba(11, 145, 183, 0.2);
  color: var(--app-cyan-strong);
  outline: none;
  transform: translateX(2px);
}

.range-option.active {
  background: rgba(21, 105, 222, 0.1);
  border-color: rgba(21, 105, 222, 0.26);
  color: var(--app-blue-strong);
}

.range-option-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.range-option-check {
  flex: 0 0 auto;
}

.range-state {
  margin: 0;
  padding: 10px 8px;
  border-radius: 8px;
  background: rgba(21, 105, 222, 0.06);
  color: #64748b;
  font-size: 12px;
  line-height: 1.45;
  font-weight: 600;
  text-align: center;
}

.range-error {
  margin: 8px 0 0;
  color: #b45309;
  font-size: 12px;
  line-height: 1.45;
  font-weight: 600;
}

.slider-row {
  display: grid;
  gap: 6px;
  margin-top: 6px;

  .label {
    display: flex;
    justify-content: space-between;
    color: #38536e;
    font-size: 12px;
    font-weight: 600;
  }

  .val-text {
    color: var(--app-blue);
  }
}

.popover-fade-enter-active,
.popover-fade-leave-active {
  transition:
    opacity 0.16s ease,
    transform 0.16s ease;
}

.popover-fade-enter-from,
.popover-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@media (max-width: 860px) {
.map-controls-toolbar.with-panel {
    right: calc(var(--app-edge) + 334px);
  }
}

.map-controls-toolbar {
  --dm-panel-scale: 0.94;
  --dm-font: "Satoshi", "Aptos", "PingFang SC", "Microsoft YaHei", system-ui, sans-serif;
  --dm-number-font: "DIN Alternate", "Aptos Mono", "SF Pro Display", "PingFang SC", system-ui, sans-serif;
  --dm-ink: #1f3132;
  --dm-ink-strong: #132323;
  --dm-muted: #687877;
  --dm-muted-soft: #8b9894;
  --dm-accent: #2f6f73;
  --dm-accent-strong: #214f52;
  --dm-accent-soft: rgba(47, 111, 115, 0.11);
  --dm-copper: #b88746;
  --dm-copper-soft: rgba(184, 135, 70, 0.14);
  --dm-paper: rgba(252, 250, 244, 0.96);
  --dm-paper-soft: rgba(246, 246, 239, 0.9);
  --dm-shell: rgba(40, 56, 55, 0.1);
  --dm-border: rgba(42, 59, 58, 0.14);
  --dm-border-strong: rgba(47, 111, 115, 0.28);
  --dm-shadow: 0 26px 70px rgba(24, 44, 45, 0.18), 0 6px 18px rgba(24, 44, 45, 0.07);
  --dm-shadow-soft: 0 16px 38px rgba(31, 49, 50, 0.1);
  --dm-ease: cubic-bezier(0.32, 0.72, 0, 1);
  font-family: var(--dm-font);
  color: var(--dm-ink);
}

.map-controls-toolbar {
  scale: var(--dm-panel-scale);
}

.range-popover,
.style-popover {
  border: 1px solid rgba(42, 59, 58, 0.14);
  border-radius: 18px;
  background: rgba(252, 250, 244, 0.97);
  box-shadow: 0 22px 54px rgba(31, 49, 50, 0.17), inset 0 1px 0 rgba(255, 255, 255, 0.72);
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}

.map-controls-toolbar {
  top: calc(var(--app-header-height) + 18px);
  right: calc(var(--app-edge) + 2px);
}

.map-controls-toolbar.with-panel {
  right: calc(var(--app-edge) + 424px);
}

.control-block {
  width: 46px;
  border: 1px solid rgba(42, 59, 58, 0.12);
  border-radius: 18px;
  background: rgba(252, 250, 244, 0.94);
  box-shadow: 0 16px 34px rgba(31, 49, 50, 0.12), inset 0 1px 0 rgba(255, 255, 255, 0.72);
}

.control-btn {
  width: 46px;
  height: 46px;
  color: var(--dm-ink);
}

.control-btn:hover,
.control-btn.active {
  background-color: rgba(47, 111, 115, 0.1);
  color: var(--dm-accent-strong);
}

.range-popover {
  top: 198px;
  right: 52px;
}

.style-popover {
  top: 248px;
  right: 52px;
}

.popover-title,
.slider-row .label {
  color: var(--dm-ink);
}

.slider-row .val-text {
  color: var(--dm-accent);
}

@media (max-width: 860px) {
.map-controls-toolbar.with-panel {
    right: calc(var(--app-edge) + min(360px, calc(100vw - 250px)));
  }
}

@media (prefers-reduced-motion: reduce) {
.map-controls-toolbar {
    animation: none;
  }
}

.map-controls-toolbar {
  --dm-panel-scale: 0.92;
  --dm-ink: #223134;
  --dm-ink-strong: #142326;
  --dm-muted: #657377;
  --dm-muted-soft: #8c989b;
  --dm-accent: #2f6f73;
  --dm-accent-strong: #204f53;
  --dm-accent-soft: rgba(47, 111, 115, 0.1);
  --dm-secondary: #315d8a;
  --dm-secondary-soft: rgba(49, 93, 138, 0.1);
  --dm-copper: var(--dm-secondary);
  --dm-copper-soft: var(--dm-secondary-soft);
  --dm-paper: rgba(249, 252, 253, 0.96);
  --dm-paper-soft: rgba(242, 247, 249, 0.92);
  --dm-shell: rgba(34, 49, 52, 0.07);
  --dm-border: rgba(35, 50, 55, 0.13);
  --dm-border-strong: rgba(47, 111, 115, 0.28);
  --dm-shadow: 0 22px 60px rgba(24, 43, 50, 0.16), 0 4px 14px rgba(24, 43, 50, 0.06);
}

.range-popover,
.style-popover {
  background: rgba(249, 252, 253, 0.96);
  border-color: rgba(35, 50, 55, 0.12);
}

.slider-row .val-text {
  color: var(--dm-secondary);
}

.map-controls-toolbar.with-panel {
  right: calc(var(--app-edge) + 414px);
}

.control-block {
  background: rgba(249, 252, 253, 0.96);
}

@media (max-width: 860px) {
.map-controls-toolbar.with-panel {
    right: calc(var(--app-edge) + 344px);
  }
}

.map-controls-toolbar {
  --dm-panel-scale: 1;
  --dm-ink: #1d1d1f;
  --dm-ink-strong: #09090b;
  --dm-muted: #6e6e73;
  --dm-muted-soft: #a1a1aa;
  --dm-accent: #0071e3;
  --dm-accent-strong: #005bb5;
  --dm-accent-soft: rgba(0, 113, 227, 0.08);
  --dm-secondary: #34c759;
  --dm-secondary-soft: rgba(52, 199, 89, 0.1);
  --dm-border: rgba(0, 0, 0, 0.1);
  --dm-border-strong: rgba(0, 113, 227, 0.34);
  --dm-shadow: 0 18px 48px rgba(15, 23, 42, 0.1), 0 3px 12px rgba(15, 23, 42, 0.04);
  --dm-shadow-soft: 0 10px 28px rgba(15, 23, 42, 0.07);
  --dm-ease: cubic-bezier(0.32, 0.72, 0, 1);
  /* 统一纯白：侧栏 / 面板 / 顶栏同色（应用户要求，去掉此前的偏蓝底色） */
  --dm-surface: #ffffff;
}

.range-popover,
.style-popover,
.control-block {
  background: var(--dm-surface) !important;
  background-image: none !important;
}

.range-popover,
.style-popover,
.control-block {
  border: 1px solid var(--dm-border);
  box-shadow: var(--dm-shadow-soft);
}

.map-controls-toolbar {
  --dm-panel-scale: var(--app-layout-scale);
}

.map-controls-toolbar {
  top: calc(var(--app-header-height) + var(--app-scaled-18));
  right: calc(var(--app-edge) + var(--app-scaled-2));
  transform-origin: top right;
}

.map-controls-toolbar.with-panel {
  right: calc(var(--app-edge) + var(--app-scaled-414));
}

.map-controls-toolbar {
  --dm-ink: var(--dm2-ink);
  --dm-ink-strong: #10151b;
  --dm-muted: var(--dm2-muted);
  --dm-muted-soft: var(--dm2-muted-soft);
  --dm-accent: var(--dm2-accent);
  --dm-accent-strong: var(--dm2-accent-strong);
  --dm-accent-soft: var(--dm2-accent-weak);
  --dm-secondary: var(--dm2-accent);
  --dm-secondary-soft: var(--dm2-accent-weak);
  --dm-border: var(--dm2-line);
  --dm-border-strong: var(--dm2-line-strong);
  --dm-surface: #ffffff;
  --dm-shadow: var(--dm2-shadow-panel);
  --dm-shadow-soft: var(--dm2-shadow-pop);
  --dm-ease: var(--dm2-ease);
  font-family: var(--dm2-font);
  color: var(--dm2-ink);
}

.range-popover,
.style-popover {
  border: 1px solid var(--dm2-line) !important;
  border-radius: var(--dm2-radius-lg);
  background: var(--dm2-glass-strong) !important;
  box-shadow: var(--dm2-shadow-pop), var(--dm2-glass-highlight) !important;
  -webkit-backdrop-filter: var(--dm2-glass-blur);
  backdrop-filter: var(--dm2-glass-blur);
}

.control-block {
  width: 44px;
  border: 1px solid var(--dm2-line) !important;
  border-radius: var(--dm2-radius);
  background: var(--dm2-veil) !important;
  box-shadow: var(--dm2-shadow-pop), var(--dm2-glass-highlight) !important;
  overflow: hidden;
}

.control-btn {
  width: 44px;
  height: 42px;
  color: var(--dm2-ink-soft);
  transition:
    background-color var(--dm2-dur) var(--dm2-ease),
    color var(--dm2-dur) var(--dm2-ease);
}

.control-btn:not(:last-child) {
  border-bottom: 1px solid var(--dm2-line-faint);
}

.control-btn:hover,
.control-btn.active {
  background: var(--dm2-accent-weak);
  color: var(--dm2-accent);
}

.popover-title {
  color: var(--dm2-ink);
  font-weight: 700;
}

.slider-row .label {
  color: var(--dm2-ink-soft);
}

.slider-row .val-text {
  color: var(--dm2-accent);
}

.map-controls-toolbar {
  position: fixed !important;
  visibility: visible !important;
  opacity: 1 !important;
  z-index: calc(var(--z-panel, 1300) + 20) !important;
}

.map-controls-toolbar {
  top: calc(var(--app-header-height, 58px) + var(--app-scaled-18, 18px)) !important;
  right: calc(var(--app-edge, 24px) + var(--app-scaled-2, 2px)) !important;
}

.map-controls-toolbar.with-panel {
  right: calc(var(--app-edge, 24px) + var(--app-scaled-414, 414px)) !important;
}

@media (max-width: 860px) {
.map-controls-toolbar.with-panel {
    right: calc(var(--app-edge, 24px) + min(360px, 100vw - 250px)) !important;
  }
}

.map-controls-toolbar {
  transition:
    left 160ms var(--dm2-ease),
    right 160ms var(--dm2-ease),
    transform 160ms var(--dm2-ease),
    opacity var(--dm2-dur) var(--dm2-ease) !important;
}

.map-controls-toolbar.with-panel {
  right: calc(var(--app-edge, 24px) + var(--app-scaled-414, 414px)) !important;
}

.map-controls-toolbar.without-panel {
  right: calc(var(--app-edge, 24px) + var(--app-scaled-2, 2px)) !important;
}

@media (max-width: 720px) {
.map-controls-toolbar.with-panel {
    right: calc(var(--app-edge, 24px) + var(--app-scaled-414, 414px)) !important;
  }
}

</style>
