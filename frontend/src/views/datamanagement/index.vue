<template>
  <div class="datebase_box" role="search" aria-label="方案与模型选择">
    <label class="handle" for="datamanagement-scheme-selector">当前方案</label>
    <el-select
      id="datamanagement-scheme-selector"
      v-model="datebase.scheme"
      clearable
      filterable
      :loading="isLoadingSchemes"
      aria-label="当前方案"
    >
      <el-option v-for="item in schemeList" :key="item" :label="item" :value="item"></el-option>
    </el-select>
    <el-select
      class="model-select"
      v-model="datebase.model"
      :disabled="!datebase.scheme || isLoadingModels"
      clearable
      filterable
      :loading="isLoadingModels"
      aria-label="选择模型"
    >
      <el-option v-for="item in modelList" :key="item.name" :label="item.name" :value="item.name">
        <div class="model-option">
          <span>{{ item.name }}</span>
          <el-tag type="success" v-if="item.loadStatus">已加载</el-tag>
          <el-tag type="warning" v-else>未加载</el-tag>
        </div>
      </el-option>
    </el-select>
  </div>

  <div class="dm-sidebar">
    <!-- Logo / Title area -->
    <div class="sidebar-brand">
      <svg class="brand-icon" viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M4 20h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.93a2 2 0 0 1-1.66-.9l-.82-1.2A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13c0 1.1.9 2 2 2Z"></path>
      </svg>
      <span class="brand-text">数据管理</span>
    </div>

    <!-- Navigation -->
    <nav class="sidebar-nav" aria-label="数据管理导航">
      <div v-for="item in menuItems" :key="item.key" class="menu-group">
        <!-- Parent Item -->
        <div
          :class="[
            'nav-item',
            activeKey === item.key || (item.children && item.children.some(c => c.key === activeKey)) ? 'active' : ''
          ]"
          @click="handleItemClick(item)"
        >
          <span class="nav-icon" v-html="item.icon"></span>
          <span class="nav-label">{{ item.label }}</span>
          
          <!-- Chevron Indicator for nested items -->
          <span v-if="item.children" class="chevron-icon" :class="{ 'expanded': isExpanded(item.key) }">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <polyline points="6 9 12 15 18 9"></polyline>
            </svg>
          </span>
        </div>

        <!-- Sub Items Accordion (with premium smooth transition) -->
        <transition name="slide-fade">
          <div v-if="item.children && isExpanded(item.key)" class="sub-nav-list">
            <div
              v-for="sub in item.children"
              :key="sub.key"
              :class="['sub-nav-item', activeKey === sub.key ? 'active' : '']"
              @click.stop="activeKey = sub.key"
            >
              <span class="sub-dot"></span>
              <span class="nav-label">{{ sub.label }}</span>
            </div>
          </div>
        </transition>
      </div>
    </nav>

    <!-- Bottom spacer (keeps items at top) -->
    <div class="sidebar-footer"></div>
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from "vue";
import { getSchemeList, getModelList } from "@/api/scheme.js";

const activeKey = ref("overview");
const expandedKeys = ref(["update"]); // default expanded keys
const DEFAULT_SCHEME = "广州市";
const DEFAULT_MODEL = "广州市/广州市抽样模型";

const datebase = ref({
  scheme: DEFAULT_SCHEME,
  model: DEFAULT_MODEL,
});
const schemeList = ref([DEFAULT_SCHEME]);
const modelList = ref([{ name: DEFAULT_MODEL, loadStatus: true }]);
const isLoadingSchemes = ref(false);
const isLoadingModels = ref(false);
let schemeRequestSeq = 0;
let modelRequestSeq = 0;

const isExpanded = (key) => expandedKeys.value.includes(key);

const handleItemClick = (item) => {
  if (item.children) {
    const index = expandedKeys.value.indexOf(item.key);
    if (index > -1) {
      expandedKeys.value.splice(index, 1);
    } else {
      expandedKeys.value.push(item.key);
    }
    // Automatically activate the first sub-item if none of its sub-items are selected
    if (!item.children.some(c => c.key === activeKey.value)) {
      activeKey.value = item.children[0].key;
    }
  } else {
    activeKey.value = item.key;
  }
};

const menuItems = [
  {
    key: "overview",
    label: "数据总揽",
    icon: `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7" rx="1"></rect><rect x="14" y="3" width="7" height="7" rx="1"></rect><rect x="3" y="14" width="7" height="7" rx="1"></rect><rect x="14" y="14" width="7" height="7" rx="1"></rect></svg>`,
  },
  {
    key: "update",
    label: "数据更新",
    icon: `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"></polyline><polyline points="1 20 1 14 7 14"></polyline><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"></path></svg>`,
    children: [
      { key: "update_line", label: "线路数据更新" },
      { key: "update_station", label: "站点数据更新" },
      { key: "update_depot", label: "场站数据更新" }
    ]
  },
  {
    key: "history",
    label: "历史数据查询",
    icon: `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline></svg>`,
  },
];

watch(
  () => datebase.value.scheme,
  async (scheme) => {
    datebase.value.model = "";
    modelList.value = [];
    if (!scheme) return;
    const list = await handleGetModelList();
    if (list.length && !datebase.value.model) {
      datebase.value.model = list[0].name;
    }
  },
);

async function handleGetSchemeList(options = {}) {
  const { silent = false } = options;
  const seq = ++schemeRequestSeq;
  if (!silent) {
    isLoadingSchemes.value = true;
  }

  try {
    const res = await getSchemeList(undefined, { silentError: silent });
    if (seq !== schemeRequestSeq) return schemeList.value;

    const list = Array.isArray(res?.data) ? res.data : [];
    schemeList.value = list.length ? list : [DEFAULT_SCHEME];

    if (!datebase.value.scheme || !schemeList.value.includes(datebase.value.scheme)) {
      datebase.value.scheme = schemeList.value[0] || "";
    }

    return schemeList.value;
  } catch {
    return schemeList.value;
  } finally {
    if (seq === schemeRequestSeq && !silent) {
      isLoadingSchemes.value = false;
    }
  }
}

async function handleGetModelList(options = {}) {
  const { silent = false } = options;
  if (!datebase.value.scheme) {
    modelList.value = [];
    return [];
  }

  const seq = ++modelRequestSeq;
  if (!silent) {
    isLoadingModels.value = true;
  }

  try {
    const res = await getModelList({ schemeName: datebase.value.scheme }, { silentError: silent });
    if (seq !== modelRequestSeq) return modelList.value;

    const list = Array.isArray(res?.data) ? res.data : [];
    modelList.value = list.length ? list : [{ name: DEFAULT_MODEL, loadStatus: true }];

    if (!datebase.value.model || !modelList.value.some((item) => item.name === datebase.value.model)) {
      datebase.value.model = modelList.value[0]?.name || "";
    }

    return modelList.value;
  } catch {
    return modelList.value;
  } finally {
    if (seq === modelRequestSeq && !silent) {
      isLoadingModels.value = false;
    }
  }
}

onMounted(async () => {
  await handleGetSchemeList();
  await handleGetModelList();
});
</script>

<style lang="scss" scoped>
.datebase_box {
  position: fixed;
  top: calc(var(--app-header-height) / 2);
  right: calc(var(--app-edge) + 64px);
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  transform: translateY(-50%);
  transform-origin: right center;
  scale: var(--app-panel-scale);
  z-index: calc(var(--z-header) + 10);
  max-width: min(46vw, 520px);
  min-width: 0;

  .handle {
    cursor: default;
    font-size: 0.95rem;
    font-weight: 600;
    color: #374151;
    text-shadow: none;
    white-space: nowrap;
  }

  .model-option {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-sm);
    min-width: 0;

    span:first-child {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  .el-select {
    width: clamp(150px, 14vw, 210px);

    :deep(.el-input__wrapper) {
      background-color: rgba(251, 253, 255, 0.88) !important;
      box-shadow: 0 0 0 1px var(--app-border-strong) inset !important;
      border-radius: var(--app-card-radius);
      padding: 6px 12px;
      transition:
        background-color 0.2s ease,
        box-shadow 0.2s ease;

      &:hover {
        background-color: var(--app-card-bg) !important;
        box-shadow: 0 0 0 1px rgba(11, 145, 183, 0.45) inset !important;
      }

      &.is-focus {
        background-color: var(--app-card-bg) !important;
        box-shadow: 0 0 0 1.5px var(--app-cyan) inset, var(--app-focus-ring) !important;
      }

      .el-input__inner {
        color: var(--app-ink) !important;
        font-weight: 500;
        font-size: 0.94rem !important;

        &::placeholder {
          color: rgba(18, 48, 79, 0.5);
        }
      }

      .el-select__caret {
        color: var(--app-cyan) !important;
        font-size: 14px;
      }
    }
  }
}

.dm-sidebar {
  position: fixed;
  left: 0;
  top: var(--app-header-height);
  bottom: 0;
  width: 260px;
  background: #ffffff;
  border-right: 1px solid rgba(0, 0, 0, 0.06);
  box-shadow: 2px 0 12px rgba(0, 0, 0, 0.03);
  display: flex;
  flex-direction: column;
  z-index: var(--z-panel);
  user-select: none;
  overflow-y: auto;
  overflow-x: hidden;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }
}

/* Brand / title */
.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px 22px 16px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.05);
  margin-bottom: 8px;

  .brand-icon {
    color: var(--app-blue);
    flex-shrink: 0;
  }

  .brand-text {
    font-size: 16px;
    font-weight: 750;
    color: #1f2937;
    letter-spacing: -0.01em;
  }
}

/* Navigation items */
.sidebar-nav {
  display: flex;
  flex-direction: column;
  padding: 4px 12px;
  gap: 4px;
}

.menu-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border-radius: 8px;
  cursor: pointer;
  color: #4b5563;
  font-size: 14px;
  font-weight: 500;
  transition:
    background-color 0.2s ease,
    color 0.2s ease,
    transform 0.15s ease;

  .nav-icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 20px;
    height: 20px;
    flex-shrink: 0;
    transition: color 0.2s ease;
  }

  .nav-label {
    flex: 1;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .chevron-icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    transition: transform 0.2s ease;
    color: #9ca3af;

    &.expanded {
      transform: rotate(180deg);
    }
  }

  &:hover {
    background: rgba(21, 105, 222, 0.05);
    color: #1f2937;

    .nav-icon {
      color: var(--app-blue);
    }
    
    .chevron-icon {
      color: #4b5563;
    }
  }

  &:active {
    transform: scale(0.98);
  }

  &.active {
    background: rgba(21, 105, 222, 0.09);
    color: var(--app-blue);
    font-weight: 650;

    .nav-icon {
      color: var(--app-blue);
    }
  }
}

/* Nested level 2 navigation */
.sub-nav-list {
  padding-left: 28px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-bottom: 4px;
  overflow: hidden;
}

.sub-nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-radius: 6px;
  cursor: pointer;
  color: #6b7280;
  font-size: 13px;
  font-weight: 500;
  transition: all 0.2s ease;

  .sub-dot {
    width: 5px;
    height: 5px;
    border-radius: 50%;
    background: #d1d5db;
    transition: all 0.2s ease;
  }

  .nav-label {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &:hover {
    background: rgba(0, 0, 0, 0.03);
    color: #1f2937;

    .sub-dot {
      background: #9ca3af;
      transform: scale(1.2);
    }
  }

  &:active {
    transform: scale(0.98);
  }

  &.active {
    background: rgba(21, 105, 222, 0.05);
    color: var(--app-blue);
    font-weight: 600;

    .sub-dot {
      background: var(--app-blue);
      transform: scale(1.4);
    }
  }
}

/* Slide Fade Transition for Submenus */
.slide-fade-enter-active,
.slide-fade-leave-active {
  transition: max-height 0.3s cubic-bezier(0.4, 0, 0.2, 1), opacity 0.2s linear, transform 0.25s cubic-bezier(0.4, 0, 0.2, 1);
  max-height: 150px;
}

.slide-fade-enter-from,
.slide-fade-leave-to {
  max-height: 0;
  opacity: 0;
  transform: translateY(-8px);
}

/* Footer spacer */
.sidebar-footer {
  flex: 1;
}

/* Responsive */
@media (max-width: 860px) {
  .datebase_box {
    top: calc(var(--app-header-height) + var(--space-lg));
    right: var(--app-edge);
    max-width: calc(100vw - (var(--app-edge) * 2));
    flex-wrap: wrap;
    justify-content: flex-end;

    .handle {
      width: 100%;
      text-align: right;
    }

    .el-select {
      width: min(100%, 190px);
    }
  }

  .dm-sidebar {
    width: 220px;
  }

  .sidebar-brand {
    padding: 16px 16px 12px;
    
    .brand-text {
      font-size: 14px;
    }
  }

  .nav-item {
    padding: 10px 12px;
    font-size: 13px;
  }

  .sub-nav-list {
    padding-left: 20px;
  }

  .sub-nav-item {
    padding: 8px 12px;
    font-size: 12px;
  }
}
</style>
