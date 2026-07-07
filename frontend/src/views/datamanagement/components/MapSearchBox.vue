<template>
  <div
    :class="['map-search', { 'is-focused': isSearchFocused, 'is-left-collapsed': leftCollapsed }]"
    role="search"
    aria-label="搜索站点或线路"
    @click.stop
  >
    <svg class="search-icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
      <circle cx="11" cy="11" r="8"></circle>
      <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
    </svg>
    <input
      v-model="searchKeyword"
      class="search-input"
      type="search"
      :placeholder="placeholder"
      :aria-label="placeholder"
      @focus="handleSearchFocus"
      @input="handleSearchInput"
      @blur="handleSearchBlur"
      @keydown.enter.prevent="selectFirstResult"
      @keydown.esc.prevent="close"
    />
    <button v-if="searchKeyword" class="search-clear-btn" type="button" title="清空搜索" aria-label="清空搜索" @mousedown.prevent="clearSearchKeyword">
      <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
        <line x1="18" y1="6" x2="6" y2="18"></line>
        <line x1="6" y1="6" x2="18" y2="18"></line>
      </svg>
    </button>
    <Transition name="search-dropdown-fade">
      <div v-if="showSearchResults" class="search-result-list" role="listbox">
        <button
          v-for="result in searchResults"
          :key="result.key"
          class="search-result-item"
          type="button"
          role="option"
          @mousedown.prevent="selectResult(result)"
        >
          <div class="result-icon-wrapper" :class="result.type">
            <!-- Station Icon -->
            <svg v-if="result.type === 'station'" viewBox="0 0 24 24" class="type-svg" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"></path>
              <circle cx="12" cy="10" r="3"></circle>
            </svg>
            <!-- Line Icon -->
            <svg v-else-if="result.type === 'line'" viewBox="0 0 24 24" class="type-svg" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
              <rect x="3" y="4" width="18" height="12" rx="2"></rect>
              <circle cx="7" cy="10" r="1"></circle>
              <circle cx="17" cy="10" r="1"></circle>
              <path d="M6 16v2"></path>
              <path d="M18 16v2"></path>
            </svg>
            <!-- Depot Icon -->
            <svg v-else viewBox="0 0 24 24" class="type-svg" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"></path>
              <polyline points="9 22 9 12 15 12 15 22"></polyline>
            </svg>
          </div>
          <div class="result-meta-block">
            <span class="result-name">{{ result.name }}</span>
            <span class="result-type-text">{{ result.typeLabel }}</span>
          </div>
        </button>
        <p v-if="!searchResults.length && searchResultsSettled" class="search-empty">未找到匹配项</p>
      </div>
    </Transition>
  </div>
</template>

<script setup>
const props = defineProps({
  placeholder: { type: String, default: "搜索站点/线路" },
  leftCollapsed: { type: Boolean, default: false },
  // (rawKeyword: string) => 结果数组；由父组件基于当前页面模式与搜索索引实现
  searchFn: { type: Function, required: true },
});

const emit = defineEmits(["select", "focus"]);

// 关键词与防抖都收敛在本组件内：击键只重渲染搜索框，不再触发整页 patch
const searchKeyword = ref("");
const debouncedSearchKeyword = ref("");
const isSearchFocused = ref(false);
let searchDebounceTimer = 0;
let searchBlurTimer = 0;

watch(searchKeyword, (value) => {
  window.clearTimeout(searchDebounceTimer);
  if (!String(value || "").trim()) {
    debouncedSearchKeyword.value = value;
    return;
  }
  searchDebounceTimer = window.setTimeout(() => {
    debouncedSearchKeyword.value = value;
  }, 120);
});

const searchResults = computed(() => props.searchFn(debouncedSearchKeyword.value));
const showSearchResults = computed(() => isSearchFocused.value && Boolean(searchKeyword.value.trim()));
// 防抖窗口内结果尚未跟上输入值，此时不显示"未找到匹配项"空态，避免 120ms 误导性闪烁
const searchResultsSettled = computed(() => debouncedSearchKeyword.value.trim() === searchKeyword.value.trim());

function handleSearchFocus() {
  emit("focus");
  isSearchFocused.value = true;
}

function handleSearchInput() {
  isSearchFocused.value = true;
}

function handleSearchBlur() {
  window.clearTimeout(searchBlurTimer);
  searchBlurTimer = window.setTimeout(() => {
    isSearchFocused.value = false;
  }, 120);
}

function clearSearchKeyword() {
  searchKeyword.value = "";
  isSearchFocused.value = true;
}

function close() {
  isSearchFocused.value = false;
}

function selectResult(result) {
  if (!result) return;
  searchKeyword.value = result.name;
  close();
  emit("select", result);
}

function selectFirstResult() {
  // 回车时立即刷新防抖值，避免选中 120ms 前的旧结果
  window.clearTimeout(searchDebounceTimer);
  debouncedSearchKeyword.value = searchKeyword.value;
  if (searchResults.value.length) {
    selectResult(searchResults.value[0]);
  }
}

onBeforeUnmount(() => {
  window.clearTimeout(searchDebounceTimer);
  window.clearTimeout(searchBlurTimer);
});

defineExpose({ close });
</script>

<style lang="scss" scoped>
/* 样式自 index.vue 迁入（渲染边界拆分）；tokens.css 中的全局规则不受影响 */
.map-search {
  scale: var(--app-panel-scale);
}

.map-search {
  position: fixed;
  top: calc(var(--app-header-height) + 18px);
  left: 278px;
  z-index: calc(var(--z-header) + 6);
  width: 240px;
  transform-origin: top left;
  transition: filter var(--app-motion-normal) var(--app-ease-out);

  &.is-focused {
    width: 300px;
  }
}

.search-icon-svg {
  position: absolute;
  left: 12px;
  top: 50%;
  transform: translateY(-50%);
  width: 14px;
  height: 14px;
  color: rgba(21, 105, 222, 0.45);
  pointer-events: none;
  transition: color 0.25s ease, transform 0.25s ease;
  z-index: 2;
}

.map-search.is-focused .search-icon-svg {
  color: var(--app-blue);
  transform: translateY(-50%) scale(1.08);
}

.search-input {
  width: 100%;
  height: 34px;
  padding: 0 32px 0 34px;
  border: 1px solid rgba(21, 105, 222, 0.15);
  border-radius: 8px;
  /* 常驻在实时地图上的表面不用 backdrop-filter（每帧强制模糊合成），以更高不透明度保证可读性 */
  background: rgba(255, 255, 255, 0.94);
  color: #0f253e;
  font-size: 13px;
  font-weight: 600;
  outline: none;
  box-shadow: 
    0 4px 12px rgba(15, 39, 68, 0.04), 
    0 1px 2px rgba(0, 0, 0, 0.02),
    inset 0 1px 0 rgba(255, 255, 255, 0.6);
  transition:
    border-color 0.25s cubic-bezier(0.25, 1, 0.5, 1),
    box-shadow 0.25s cubic-bezier(0.25, 1, 0.5, 1),
    background-color 0.25s cubic-bezier(0.25, 1, 0.5, 1);

  &::placeholder {
    color: #94a3b8;
    font-weight: 500;
  }

  &:hover {
    background: rgba(255, 255, 255, 0.92);
    border-color: rgba(21, 105, 222, 0.3);
    box-shadow: 
      0 6px 16px rgba(15, 39, 68, 0.06), 
      0 1px 2px rgba(0, 0, 0, 0.02),
      inset 0 1px 0 rgba(255, 255, 255, 0.8);
  }

  &:focus {
    background: #ffffff;
    border-color: var(--app-blue);
    box-shadow: 
      0 0 0 3px rgba(21, 105, 222, 0.15),
      0 8px 24px rgba(21, 105, 222, 0.08),
      inset 0 1px 0 rgba(255, 255, 255, 1);
  }
}

.search-clear-btn {
  position: absolute;
  top: 50%;
  right: 9px;
  width: 16px;
  height: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: rgba(100, 116, 139, 0.08);
  color: #64748b;
  transform: translateY(-50%) scale(1);
  cursor: pointer;
  z-index: 2;
  transition: 
    transform var(--app-motion-normal) var(--app-ease-out),
    background-color 0.2s ease,
    color 0.2s ease;

  &:hover {
    background: rgba(21, 105, 222, 0.12);
    color: var(--app-blue);
    transform: translateY(-50%) rotate(90deg) scale(1.15);
  }

  &:active {
    transform: translateY(-50%) rotate(90deg) scale(0.92);
  }
}

.search-result-list {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  right: 0;
  z-index: calc(var(--z-panel) + 20);
  max-height: 320px;
  overflow-y: auto;
  padding: 6px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.94);
  backdrop-filter: blur(20px) saturate(190%);
  -webkit-backdrop-filter: blur(20px) saturate(190%);
  border: 1px solid rgba(21, 105, 222, 0.12);
  box-shadow: 
    0 12px 36px rgba(15, 39, 68, 0.12),
    0 4px 12px rgba(15, 39, 68, 0.04),
    inset 0 1px 0 rgba(255, 255, 255, 0.6);
  scrollbar-width: thin;
  scrollbar-color: rgba(21, 105, 222, 0.15) transparent;

  &::-webkit-scrollbar {
    width: 4px;
  }
  &::-webkit-scrollbar-thumb {
    background: rgba(21, 105, 222, 0.15);
    border-radius: 10px;
  }
  &::-webkit-scrollbar-track {
    background: transparent;
  }
}

.search-result-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  margin-bottom: 2px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #1e293b;
  text-align: left;
  cursor: pointer;
  transition:
    background-color 0.2s cubic-bezier(0.25, 1, 0.5, 1),
    transform 0.2s cubic-bezier(0.25, 1, 0.5, 1);

  &:last-child {
    margin-bottom: 0;
  }

  &:hover {
    background: linear-gradient(135deg, rgba(21, 105, 222, 0.06) 0%, rgba(21, 105, 222, 0.02) 100%);
    transform: translateX(4px);
  }

  &:active {
    transform: translateX(2px);
  }
}

.result-icon-wrapper {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  flex-shrink: 0;
  transition: transform 0.2s ease;

  .type-svg {
    width: 14px;
    height: 14px;
  }

  &.station {
    background: rgba(13, 148, 136, 0.1);
    color: #0d9488;
    border: 1px solid rgba(13, 148, 136, 0.12);
  }

  &.line {
    background: rgba(21, 105, 222, 0.1);
    color: var(--app-blue);
    border: 1px solid rgba(21, 105, 222, 0.12);
  }

  &.depot {
    background: rgba(124, 58, 237, 0.1);
    color: #7c3aed;
    border: 1px solid rgba(124, 58, 237, 0.12);
  }
}

.result-meta-block {
  display: flex;
  flex-direction: column;
  min-width: 0;
  gap: 2px;
}

.result-name {
  color: #1e293b;
  font-size: 13px;
  line-height: 1.3;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.result-type-text {
  color: #64748b;
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 0.02em;
}

.search-empty {
  margin: 0;
  padding: 12px 10px;
  color: #64748b;
  font-size: 12px;
  font-weight: 500;
  text-align: center;
}

.search-dropdown-fade-enter-active,
.search-dropdown-fade-leave-active {
  transition: 
    opacity var(--app-motion-normal) var(--app-ease-out),
    transform var(--app-motion-normal) var(--app-ease-out);
}

.search-dropdown-fade-enter-from,
.search-dropdown-fade-leave-to {
  opacity: 0;
  transform: translateY(-8px) scale(0.97);
}

@media (max-width: 860px) {
.map-search {
    left: 238px;
    width: min(220px, calc(100vw - 260px));
  }

.search-result-list {
    width: 100%;
  }
}

.map-search {
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

.map-search {
  scale: var(--dm-panel-scale);
}

.map-search {
  top: calc(var(--app-header-height) + 20px);
  left: 288px;
  width: 292px;
  transition:
    transform 360ms var(--dm-ease),
    filter 360ms var(--dm-ease);
}

.map-search.is-focused {
  width: 292px;
  transform: translateY(-2px);
}

.search-input {
  height: 42px;
  padding-left: 40px;
  border: 1px solid rgba(42, 59, 58, 0.12);
  border-radius: 16px;
  background: rgba(252, 250, 244, 0.92);
  color: var(--dm-ink-strong);
  font-size: 13px;
  font-weight: 600;
  box-shadow: 0 14px 32px rgba(31, 49, 50, 0.11), inset 0 1px 0 rgba(255, 255, 255, 0.72);
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
  transition:
    border-color 360ms var(--dm-ease),
    box-shadow 360ms var(--dm-ease),
    background-color 360ms var(--dm-ease),
    transform 260ms var(--dm-ease);
}

.search-input:hover,
.search-input:focus {
  border-color: var(--dm-border-strong);
  background: rgba(255, 255, 252, 0.98);
  box-shadow: 0 18px 42px rgba(31, 49, 50, 0.14), 0 0 0 4px rgba(47, 111, 115, 0.08);
}

.search-icon-svg {
  left: 15px;
  color: rgba(47, 111, 115, 0.62);
  stroke-width: 2;
}

.search-clear-btn {
  right: 12px;
  width: 20px;
  height: 20px;
  background: rgba(47, 111, 115, 0.08);
  color: var(--dm-accent);
}

.search-clear-btn:hover {
  background: var(--dm-copper-soft);
  color: #8f642b;
}

.search-result-list {
  border: 1px solid rgba(42, 59, 58, 0.14);
  border-radius: 18px;
  background: rgba(252, 250, 244, 0.97);
  box-shadow: 0 22px 54px rgba(31, 49, 50, 0.17), inset 0 1px 0 rgba(255, 255, 255, 0.72);
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}

.search-result-item {
  border-radius: 13px;
  color: var(--dm-ink);
  transition:
    background-color 320ms var(--dm-ease),
    border-color 320ms var(--dm-ease),
    box-shadow 320ms var(--dm-ease),
    transform 260ms var(--dm-ease);
}

.search-result-item:hover {
  background: rgba(47, 111, 115, 0.08);
  transform: translateX(3px);
}

.result-icon-wrapper.station,
.result-icon-wrapper.line,
.result-icon-wrapper.depot {
  border-color: rgba(47, 111, 115, 0.14);
  background: rgba(47, 111, 115, 0.09);
  color: var(--dm-accent);
}

@media (max-width: 860px) {
.map-search {
    left: 244px;
    width: min(270px, calc(100vw - 268px));
  }

.map-search.is-focused {
    width: min(270px, calc(100vw - 268px));
  }
}

@media (max-width: 720px) {
.map-search {
    left: 10px;
    right: 10px;
    width: auto;
  }
}

@media (prefers-reduced-motion: reduce) {
.map-search {
    animation: none;
  }
}

.map-search {
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

.map-search {
  left: 282px;
}

.search-input,
.search-result-list {
  background: rgba(249, 252, 253, 0.96);
  border-color: rgba(35, 50, 55, 0.12);
}

.search-clear-btn:hover {
  background: var(--dm-secondary-soft);
  border-color: rgba(49, 93, 138, 0.22);
  color: var(--dm-secondary);
}

@media (max-width: 860px) {
.map-search {
    left: 238px;
  }
}

.map-search {
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

.search-input,
.search-result-list {
  background: var(--dm-surface) !important;
  background-image: none !important;
}

.result-icon-wrapper.station,
.result-icon-wrapper.line,
.result-icon-wrapper.depot {
  color: var(--dm-accent);
  background: var(--dm-accent-soft);
  border-color: rgba(0, 113, 227, 0.12);
}

.search-input,
.search-result-list {
  border: 1px solid var(--dm-border);
  box-shadow: var(--dm-shadow-soft);
}

.search-input {
  border-radius: 12px;
  color: var(--dm-ink);
}

.search-input:hover,
.search-input:focus {
  border-color: var(--dm-border-strong);
  box-shadow: 0 0 0 4px rgba(0, 113, 227, 0.08), var(--dm-shadow-soft) !important;
}

.map-search {
  --dm-panel-scale: var(--app-layout-scale);
}

.map-search {
  top: calc(var(--app-header-height) + var(--app-scaled-20));
  left: var(--app-scaled-282);
  width: 292px;
  transform-origin: top left;
}

.map-search.is-focused {
  width: 292px;
}

.map-search {
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

.search-input {
  height: 42px;
  border: 1px solid var(--dm2-line);
  border-radius: var(--dm2-radius);
  background: var(--dm2-veil) !important;
  color: var(--dm2-ink);
  font-weight: 600;
  box-shadow: var(--dm2-shadow-pop), var(--dm2-glass-highlight);
  transition:
    background-color var(--dm2-dur) var(--dm2-ease),
    border-color var(--dm2-dur) var(--dm2-ease),
    box-shadow var(--dm2-dur) var(--dm2-ease);
}

.search-input::placeholder {
  color: var(--dm2-muted-soft);
  font-weight: 500;
}

.search-input:hover {
  border-color: var(--dm2-line-strong);
}

.search-input:focus {
  border-color: var(--dm2-accent);
  background: var(--dm2-glass-strong) !important;
  box-shadow: 0 0 0 4px var(--dm2-accent-ring), var(--dm2-shadow-pop);
}

.search-icon-svg {
  color: var(--dm2-accent);
  opacity: 0.72;
  stroke-width: 2.2;
}

.search-clear-btn {
  background: rgba(17, 32, 58, 0.06);
  color: var(--dm2-muted);
}

.search-clear-btn:hover {
  background: var(--dm2-accent-weak) !important;
  border-color: transparent !important;
  color: var(--dm2-accent) !important;
}

.search-result-list {
  border: 1px solid var(--dm2-line) !important;
  border-radius: var(--dm2-radius-lg);
  background: var(--dm2-glass-strong) !important;
  box-shadow: var(--dm2-shadow-pop), var(--dm2-glass-highlight) !important;
  -webkit-backdrop-filter: var(--dm2-glass-blur);
  backdrop-filter: var(--dm2-glass-blur);
}

.search-result-item {
  border-radius: var(--dm2-radius-sm);
  transition:
    background-color var(--dm2-dur) var(--dm2-ease),
    transform var(--dm2-dur-fast) var(--dm2-ease);
}

.search-result-item:hover {
  background: var(--dm2-accent-weak) !important;
  transform: translateX(2px);
}

.result-icon-wrapper.station,
.result-icon-wrapper.line,
.result-icon-wrapper.depot {
  border-radius: var(--dm2-radius-sm);
  color: var(--dm2-accent) !important;
  background: var(--dm2-accent-weak) !important;
  border-color: rgba(0, 113, 227, 0.14) !important;
}

.result-name {
  color: var(--dm2-ink);
}

.result-type-text,
.search-empty {
  color: var(--dm2-muted);
}

.map-search {
  position: fixed !important;
  visibility: visible !important;
  opacity: 1 !important;
  z-index: calc(var(--z-panel, 1300) + 20) !important;
}

.map-search {
  left: var(--app-scaled-282, 282px) !important;
}

.map-search {
  transition:
    left 160ms var(--dm2-ease),
    right 160ms var(--dm2-ease),
    transform 160ms var(--dm2-ease),
    opacity var(--dm2-dur) var(--dm2-ease) !important;
}

.map-search.is-left-collapsed {
  left: calc(var(--app-edge, 24px) + var(--app-scaled-70, 70px)) !important;
}

</style>
