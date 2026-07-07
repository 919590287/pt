<template>
  <div :class="['dm-sidebar', collapsed ? 'is-collapsed' : '']">
    <div class="sidebar-brand">
      <svg class="brand-icon" viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M4 20h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.93a2 2 0 0 1-1.66-.9l-.82-1.2A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13c0 1.1.9 2 2 2Z"></path>
      </svg>
      <span class="brand-text">数据管理</span>
    </div>

    <nav class="sidebar-nav" aria-label="数据管理导航">
      <div v-for="item in menuItems" :key="item.key" class="menu-group">
        <button
          type="button"
          :class="[
            'nav-item',
            activeKey === item.key || (item.children && item.children.some((child) => child.key === activeKey)) ? 'active' : '',
          ]"
          :aria-expanded="item.children ? isExpanded(item.key) : undefined"
          @click="handleItemClick(item)"
        >
          <span class="nav-icon" v-html="item.icon"></span>
          <span class="nav-label">{{ item.label }}</span>
          <span v-if="item.children" class="chevron-icon" :class="{ expanded: isExpanded(item.key) }">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <polyline points="6 9 12 15 18 9"></polyline>
            </svg>
          </span>
        </button>

        <transition name="slide-fade">
          <div v-if="item.children && isExpanded(item.key)" class="sub-nav-list">
            <button
              v-for="sub in item.children"
              :key="sub.key"
              type="button"
              :class="['sub-nav-item', activeKey === sub.key ? 'active' : '']"
              @click.stop="$emit('select', sub.key)"
            >
              <span class="sub-dot"></span>
              <span class="nav-label">{{ sub.label }}</span>
            </button>
          </div>
        </transition>
      </div>
    </nav>

    <div class="sidebar-footer"></div>
  </div>
</template>

<script setup>
const props = defineProps({
  activeKey: { type: String, default: "" },
  collapsed: { type: Boolean, default: false },
});

const emit = defineEmits(["select"]);

const menuItems = [
  {
    key: "overview",
    label: "数据总览",
    icon: `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7" rx="1"></rect><rect x="14" y="3" width="7" height="7" rx="1"></rect><rect x="3" y="14" width="7" height="7" rx="1"></rect><rect x="14" y="14" width="7" height="7" rx="1"></rect></svg>`,
  },
  {
    key: "update",
    label: "数据更新",
    icon: `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"></polyline><polyline points="1 20 1 14 7 14"></polyline><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"></path></svg>`,
    children: [
      { key: "update_line", label: "线路数据更新" },
      { key: "update_station", label: "站点数据更新" },
      { key: "update_depot", label: "场站数据更新" },
    ],
  },
  {
    key: "history",
    label: "历史数据查询",
    icon: `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline></svg>`,
  },
];

const expandedKeys = ref(["update"]);

const isExpanded = (key) => expandedKeys.value.includes(key);

function handleItemClick(item) {
  if (item.children) {
    const index = expandedKeys.value.indexOf(item.key);
    if (index > -1) {
      expandedKeys.value.splice(index, 1);
    } else {
      expandedKeys.value.push(item.key);
    }
    if (!item.children.some((child) => child.key === props.activeKey)) {
      emit("select", item.children[0].key);
    }
    return;
  }
  emit("select", item.key);
}
</script>

<style lang="scss" scoped>
/* 样式自 index.vue 迁入（渲染边界拆分）；tokens.css 中的全局规则不受影响 */
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
  cursor: default;
  user-select: text;
  overflow-y: auto;
  overflow-x: hidden;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }
}

.sidebar-brand {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 24px 20px 10px;
  border-bottom: none !important;
  margin-bottom: 4px;

  .brand-icon {
    color: var(--app-blue);
    opacity: 0.9;
    flex-shrink: 0;
  }

  .brand-text {
    font-size: 15px;
    font-weight: 700;
    color: #111827;
    letter-spacing: 0.03em;
    text-transform: uppercase;
  }
}

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
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
  color: #4b5563;
  font-size: 14px;
  font-weight: 500;
  font-family: inherit;
  text-align: left;
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
    font-weight: 600;

    .nav-icon {
      color: var(--app-blue);
    }
  }
}

.sub-nav-list {
  padding-left: 28px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-bottom: 4px;
  overflow: hidden;
}

.sub-nav-item {
  position: relative;
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px 8px 16px !important;
  border: 0;
  border-radius: 6px;
  background: transparent;
  cursor: pointer;
  color: #6b7280;
  font-size: 13px;
  font-weight: 600;
  font-family: inherit;
  text-align: left;
  transition: 
    padding-left var(--app-motion-normal) var(--app-ease-out),
    color 0.25s ease,
    background-color 0.25s ease !important;

  .sub-dot {
    display: inline-block;
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: currentColor;
    opacity: 0;
    transform: scale(0.7);
    transition:
      opacity var(--app-motion-normal) var(--app-ease-out),
      transform var(--app-motion-normal) var(--app-ease-out);
  }

  .nav-label {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &:hover {
    background: rgba(21, 105, 222, 0.04) !important;
    color: #111827 !important;
    padding-left: 20px !important;

    .sub-dot {
      opacity: 0.5;
      transform: scale(0.9);
    }
  }

  &:active {
    transform: scale(0.98);
  }

  &.active {
    background: rgba(21, 105, 222, 0.07) !important;
    color: var(--app-blue-strong) !important;
    font-weight: 700;
    padding-left: 22px !important;

    .sub-dot {
      opacity: 1;
      transform: scale(1);
    }
  }
}

.slide-fade-enter-active {
  transition:
    opacity var(--app-motion-normal) var(--app-ease-out),
    transform var(--app-motion-normal) var(--app-ease-out);

  .sub-nav-item {
    transition: 
      transform var(--app-motion-normal) var(--app-ease-out),
      opacity var(--app-motion-normal) var(--app-ease-out),
      padding-left var(--app-motion-normal) var(--app-ease-out),
      color 0.25s ease,
      background-color 0.25s ease !important;
      
    @starting-style {
      opacity: 0;
      transform: translateY(10px);
    }
    
    &:nth-child(1) {
      transition-delay: 0.04s;
    }
    &:nth-child(2) {
      transition-delay: 0.09s;
    }
    &:nth-child(3) {
      transition-delay: 0.14s;
    }
  }
}

.slide-fade-leave-active {
  transition:
    opacity 0.2s ease-in,
    transform 0.25s cubic-bezier(0.4, 0, 1, 1);
}

.slide-fade-enter-from,
.slide-fade-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

.sidebar-footer {
  flex: 1;
}

@media (max-width: 860px) {
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

.dm-sidebar {
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

.dm-sidebar {
  left: 16px;
  top: calc(var(--app-header-height) + 14px);
  bottom: 18px;
  width: 250px;
  padding: 6px;
  border: 1px solid var(--dm-border);
  border-radius: 24px;
  background:
    linear-gradient(145deg, rgba(255, 255, 252, 0.96), rgba(241, 243, 235, 0.91)),
    repeating-linear-gradient(135deg, rgba(31, 49, 50, 0.025) 0 1px, transparent 1px 7px);
  box-shadow: var(--dm-shadow);
  overflow: hidden auto;
}

.dm-sidebar::before {
  content: "";
  position: absolute;
  inset: 6px;
  pointer-events: none;
  border-radius: 19px;
  border: 1px solid rgba(255, 255, 255, 0.64);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.72);
}

.sidebar-brand {
  padding: 18px 16px 14px;
  margin: 0 0 6px;
  gap: 11px;
}

.sidebar-brand .brand-icon {
  width: 28px;
  height: 28px;
  padding: 5px;
  color: var(--dm-accent);
  border-radius: 10px;
  background: var(--dm-accent-soft);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.72);
}

.sidebar-brand .brand-text {
  color: var(--dm-ink-strong);
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.02em;
  text-transform: none;
}

.sidebar-nav {
  gap: 6px;
  padding: 4px 8px 16px;
}

.nav-item {
  min-height: 46px;
  padding: 12px 13px;
  border-radius: 15px;
  color: #4e5e5d;
  font-size: 13.5px;
  font-weight: 600;
  transition:
    background-color 360ms var(--dm-ease),
    color 360ms var(--dm-ease),
    box-shadow 360ms var(--dm-ease),
    transform 260ms var(--dm-ease);
}

.nav-item .nav-icon svg,
.chevron-icon svg {
  stroke-width: 1.75;
}

.nav-item:hover {
  background: rgba(255, 255, 255, 0.62);
  color: var(--dm-ink-strong);
  transform: translateX(2px);
  box-shadow: inset 0 0 0 1px rgba(47, 111, 115, 0.08);
}

.nav-item.active {
  color: var(--dm-accent-strong);
  background:
    linear-gradient(135deg, rgba(47, 111, 115, 0.14), rgba(184, 135, 70, 0.1)),
    rgba(255, 255, 255, 0.72);
  box-shadow:
    inset 0 0 0 1px rgba(47, 111, 115, 0.18),
    inset 3px 0 0 var(--dm-copper);
}

.nav-item.active .nav-icon,
.nav-item:hover .nav-icon {
  color: var(--dm-accent);
}

.sub-nav-list {
  margin: 0 0 5px 22px;
  padding-left: 11px;
  border-left: 1px solid rgba(47, 111, 115, 0.16);
}

.sub-nav-item {
  padding: 8px 12px !important;
  border-radius: 12px;
  color: #697775;
  font-size: 12.5px;
  font-weight: 600;
  transition:
    background-color 340ms var(--dm-ease),
    color 340ms var(--dm-ease),
    transform 260ms var(--dm-ease),
    padding-left 340ms var(--dm-ease) !important;
}

.sub-nav-item:hover {
  padding-left: 16px !important;
  background: rgba(255, 255, 255, 0.56) !important;
  color: var(--dm-ink-strong) !important;
}

.sub-nav-item.active {
  padding-left: 17px !important;
  color: var(--dm-accent-strong) !important;
  background: rgba(47, 111, 115, 0.11) !important;
}

@media (max-width: 860px) {
.dm-sidebar {
    left: 10px;
    width: 220px;
    border-radius: 20px;
  }
}

@media (max-width: 720px) {
.dm-sidebar {
    right: 10px;
    bottom: auto;
    width: auto;
    max-height: 48vh;
  }
}

.dm-sidebar {
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

.dm-sidebar {
  left: 0;
  top: var(--app-header-height);
  bottom: 0;
  width: 260px;
  padding: 8px 10px 14px;
  border-width: 0 1px 0 0;
  border-color: rgba(35, 50, 55, 0.1);
  border-radius: 0;
  background:
    linear-gradient(180deg, rgba(250, 253, 254, 0.98), rgba(241, 247, 249, 0.96)),
    repeating-linear-gradient(135deg, rgba(35, 50, 55, 0.018) 0 1px, transparent 1px 8px);
  box-shadow: 12px 0 34px rgba(24, 43, 50, 0.08);
}

.dm-sidebar::before {
  display: none;
}

.sidebar-brand {
  padding: 18px 12px 14px;
  border-bottom: 1px solid rgba(35, 50, 55, 0.08) !important;
}

.sidebar-brand .brand-icon {
  background: rgba(47, 111, 115, 0.08);
  color: var(--dm-accent);
}

.nav-item {
  border-radius: 10px;
  color: #4d5d61;
}

.nav-item.active {
  color: var(--dm-accent-strong);
  background: linear-gradient(90deg, rgba(47, 111, 115, 0.13), rgba(49, 93, 138, 0.07));
  box-shadow:
    inset 3px 0 0 var(--dm-accent),
    inset 0 0 0 1px rgba(47, 111, 115, 0.1);
}

.sub-nav-list {
  margin-left: 28px;
  border-left-color: rgba(47, 111, 115, 0.16);
}

.sub-nav-item.active {
  color: var(--dm-accent-strong) !important;
  background: rgba(47, 111, 115, 0.1) !important;
}

@media (max-width: 860px) {
.dm-sidebar {
    left: 0;
    width: 220px;
    border-radius: 0;
  }
}

@media (max-width: 720px) {
.dm-sidebar {
    left: 0;
    right: 0;
    width: auto;
  }
}

.dm-sidebar {
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

.dm-sidebar {
  background: var(--dm-surface) !important;
  background-image: none !important;
}

.dm-sidebar {
  border-color: rgba(0, 0, 0, 0.08);
  box-shadow: 8px 0 24px rgba(15, 23, 42, 0.05);
}

.sidebar-brand .brand-icon {
  color: var(--dm-accent);
  background: var(--dm-accent-soft);
  border-color: rgba(0, 113, 227, 0.12);
}

.nav-item,
.sub-nav-item {
  border-radius: 8px;
}

.nav-item:hover,
.sub-nav-item:hover {
  background: rgba(0, 0, 0, 0.035) !important;
  color: var(--dm-ink-strong) !important;
}

.nav-item.active,
.sub-nav-item.active {
  color: var(--dm-accent-strong) !important;
  background: var(--dm-accent-soft) !important;
  box-shadow: inset 3px 0 0 var(--dm-accent);
}

.dm-sidebar {
  --dm-panel-scale: var(--app-layout-scale);
}

.dm-sidebar {
  top: var(--app-header-height);
  bottom: auto;
  left: 0;
  width: 260px;
  height: var(--app-dm-sidebar-height);
  transform-origin: left top;
  scale: var(--dm-panel-scale);
}

.dm-sidebar {
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

.dm-sidebar {
  border-right: 1px solid var(--dm2-line);
  /* 常驻面板去 blur：提高背景不透明度补足可读性，磨砂只保留给短暂弹层 */
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.97), rgba(247, 250, 254, 0.94)) !important;
  box-shadow: 16px 0 48px -24px rgba(13, 38, 76, 0.24), var(--dm2-glass-highlight) !important;
}

.sidebar-brand {
  padding: 20px 14px 14px;
  border-bottom: 1px solid var(--dm2-line-faint) !important;
}

.sidebar-brand .brand-icon {
  width: 30px;
  height: 30px;
  padding: 6px;
  border-radius: var(--dm2-radius-sm);
  color: #ffffff !important;
  background: var(--dm2-accent-grad) !important;
  border-color: transparent !important;
  box-shadow: var(--dm2-accent-glow), inset 0 1px 0 rgba(255, 255, 255, 0.45);
}

.sidebar-brand .brand-text {
  color: var(--dm2-ink);
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-transform: none;
}

.nav-item {
  min-height: 44px;
  border-radius: var(--dm2-radius) !important;
  color: var(--dm2-ink-soft);
  font-size: 13.5px;
  font-weight: 600;
  transition:
    background-color var(--dm2-dur) var(--dm2-ease),
    color var(--dm2-dur) var(--dm2-ease),
    box-shadow var(--dm2-dur) var(--dm2-ease),
    transform var(--dm2-dur-fast) var(--dm2-ease);
}

.nav-item .nav-icon svg,
.chevron-icon svg {
  stroke-width: 1.75;
}

.nav-item:hover,
.sub-nav-item:hover {
  background: rgba(17, 32, 58, 0.045) !important;
  color: var(--dm2-ink) !important;
}

.nav-item:hover {
  transform: translateX(2px);
}

.nav-item:hover .nav-icon {
  color: var(--dm2-accent);
}

.nav-item.active,
.sub-nav-item.active {
  color: var(--dm2-accent-strong) !important;
  background: var(--dm2-accent-weak) !important;
  box-shadow: inset 3px 0 0 var(--dm2-accent), 0 1px 2px rgba(13, 38, 76, 0.05) !important;
}

.nav-item.active .nav-icon {
  color: var(--dm2-accent);
}

.sub-nav-list {
  margin-left: 26px;
  padding-left: 12px;
  border-left: 1px solid var(--dm2-line);
}

.sub-nav-item {
  border-radius: var(--dm2-radius-sm) !important;
  color: var(--dm2-muted);
  font-weight: 600;
}

.dm-sidebar {
  scrollbar-color: rgba(17, 32, 58, 0.18) transparent;
}

.dm-sidebar {
  position: fixed !important;
  visibility: visible !important;
  opacity: 1 !important;
  z-index: calc(var(--z-panel, 1300) + 20) !important;
}

.dm-sidebar {
  display: flex !important;
  left: 0 !important;
  top: var(--app-header-height, 58px) !important;
  bottom: 0 !important;
  width: 260px !important;
  height: var(--app-dm-sidebar-height, calc(100vh - var(--app-header-height, 58px))) !important;
}

.dm-sidebar {
  transition:
    left 160ms var(--dm2-ease),
    right 160ms var(--dm2-ease),
    transform 160ms var(--dm2-ease),
    opacity var(--dm2-dur) var(--dm2-ease) !important;
}

.dm-sidebar.is-collapsed {
  transform: translateX(calc(-100% - 1px)) !important;
  pointer-events: none;
}

</style>
