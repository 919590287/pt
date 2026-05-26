<!-- Header -->
<template>
  <header class="header-container" :class="{ 'has-model-selector': hasModelSelector }">
    <div class="title-box">
      <svg class="logo-icon" viewBox="0 0 36 36" width="30" height="30" fill="none" xmlns="http://www.w3.org/2000/svg">
        <rect width="36" height="36" rx="9" fill="url(#logo-gradient)" />
        <path d="M10 18 C 14 13, 22 13, 26 18" stroke="#ffffff" stroke-width="2.5" stroke-linecap="round" opacity="0.85" />
        <path d="M10 18 C 14 23, 22 23, 26 18" stroke="#00f2fe" stroke-width="2" stroke-linecap="round" opacity="0.95" />
        <circle cx="10" cy="18" r="3.5" fill="#ffffff" />
        <circle cx="18" cy="15" r="3" fill="#f093fb" />
        <circle cx="26" cy="18" r="3.5" fill="#00f2fe" />
        <defs>
          <linearGradient id="logo-gradient" x1="0" y1="0" x2="36" y2="36" gradientUnits="userSpaceOnUse">
            <stop offset="0%" stop-color="#1569de" />
            <stop offset="50%" stop-color="#0b91b7" />
            <stop offset="100%" stop-color="#00f2fe" />
          </linearGradient>
        </defs>
      </svg>
      <span class="title-text">多智能体出行仿真可视化平台</span>
    </div>
    <nav class="nav-list" aria-label="主导航">
      <RouterLink
        v-for="item in headerMenus"
        :key="item.title"
        :to="item.to"
        active-class="active"
        class="item"
      >
        <span class="item-icon">{{ item.icon }}</span>
        <span class="item-title">{{ item.title }}</span>
      </RouterLink>
    </nav>
    <button class="user-profile-btn" type="button" title="用户管理" aria-label="用户管理" disabled>
      <svg class="user-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
        <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path>
        <circle cx="12" cy="7" r="4"></circle>
      </svg>
    </button>
  </header>
</template>

<script setup>
const route = useRoute();
const hasModelSelector = computed(() => ["datavisualization", "datamanagement"].includes(route.name));

const headerMenus = [
  {
    title: "数据管理",
    icon: "🗂️",
    to: { name: "datamanagement" },
  },
  {
    title: "运行监测",
    icon: "📊",
    to: { name: "datavisualization" },
  },
  {
    title: "客流分析",
    icon: "📈",
    to: { name: "passengerflowanalysis" },
  },
  {
    title: "线网优化",
    icon: "🏗️",
    to: { name: "scenarioedit" },
  },
  {
    title: "优化评估",
    icon: "🔄",
    to: { name: "scenariocomparison" },
  },
  {
    title: "配车测算",
    icon: "🚌",
    to: { name: "vehiclecalculation" },
  },
];
</script>

<style scoped lang="scss">
.header-container {
  --header-height: var(--app-header-height);
  --header-padding-x: clamp(12px, 1.25vw, 24px);
  --title-width: clamp(300px, 26vw, 460px);
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  display: grid;
  grid-template-columns: minmax(240px, var(--title-width)) minmax(360px, 1fr) auto;
  align-items: center;
  column-gap: var(--space-lg);
  width: 100%;
  height: var(--header-height);
  min-width: 0;
  padding: 0 var(--header-padding-x);
  box-sizing: border-box;
  background-color: #ffffff;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
  z-index: var(--z-header);
  user-select: none;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.02);
}

.title-box {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  padding-right: var(--space-md);
  font-family: var(--app-font-display);
  font-size: 1.25rem;
  color: #1f2937;
  font-weight: 750;
  letter-spacing: -0.01em;
  text-shadow: none;

  .logo-icon {
    flex-shrink: 0;
  }

  .title-text {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.nav-list {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  justify-self: start;
  min-width: 0;
  max-width: 100%;
  gap: clamp(6px, 0.8vw, 12px);
  margin-left: clamp(12px, 2vw, 28px);
  padding: 0 var(--space-xs);
  overflow-x: auto;
  overflow-y: hidden;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }

  .item {
    height: 36px;
    padding: 0 clamp(10px, 1.1vw, 15px);
    border-radius: 6px;
    color: #4b5563;
    font-size: 0.95rem;
    font-weight: 600;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 6px;
    cursor: pointer;
    white-space: nowrap;
    transition:
      color var(--app-motion-normal) var(--app-ease-out),
      background-color var(--app-motion-normal) var(--app-ease-out),
      transform var(--app-motion-fast) var(--app-ease-press);

    .item-icon {
      font-size: 1.1rem;
      display: inline-flex;
      align-items: center;
    }

    &:hover {
      background-color: rgba(0, 0, 0, 0.04);
      color: #111827;
      transform: translateY(-1px);
    }

    &.active {
      color: var(--app-blue);
      background-color: rgba(21, 105, 222, 0.08);
      font-weight: 700;

      &:hover {
        background-color: rgba(21, 105, 222, 0.12);
      }
    }

    &:active {
      transform: translateY(0) scale(0.98);
    }
  }
}

.header-container.has-model-selector {
  .nav-list {
    margin-right: clamp(580px, 31vw, 630px);
    padding-right: 0;
  }
}

.user-profile-btn {
  justify-self: end;
  width: clamp(28px, 1.9vw, 36px);
  height: clamp(28px, 1.9vw, 36px);
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #ffffff;
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: 50%;
  cursor: default;
  transition:
    background-color var(--app-motion-normal) var(--app-ease-out),
    border-color var(--app-motion-normal) var(--app-ease-out),
    color var(--app-motion-normal) var(--app-ease-out);
  color: #4b5563;
  opacity: 0.85;

  &:not(:disabled):hover {
    background: rgba(0, 0, 0, 0.04);
    border-color: rgba(0, 0, 0, 0.15);
    color: #111827;
  }

  .user-icon {
    width: clamp(15px, 1vw, 18px);
    height: clamp(15px, 1vw, 18px);
    transition: transform var(--app-motion-normal) var(--app-ease-out);
  }

  &:not(:disabled):hover .user-icon {
    transform: translateY(-1px);
  }
}

@media (max-width: 1180px) {
  .header-container {
    --title-width: clamp(220px, 24vw, 300px);
    column-gap: var(--space-sm);
  }

  .title-box {
    font-size: 1.15rem;
    gap: 6px;

    .logo-icon {
      width: 26px;
      height: 26px;
    }
  }

  .nav-list {
    margin-left: 16px;
    gap: 6px;

    .item {
      font-size: 0.92rem;
      padding: 0 10px;
      height: 32px;
    }
  }

  .header-container.has-model-selector {
    .nav-list {
      justify-content: flex-start;
      margin-right: clamp(480px, 42vw, 540px);
      padding-right: 0;
    }
  }
}

@media (max-width: 860px) {
  .header-container {
    --header-height: 52px;
    grid-template-columns: minmax(0, 210px) minmax(0, 1fr) auto;
    column-gap: var(--space-xs);
  }

  .title-box {
    font-size: 1.05rem;

    .logo-icon {
      display: none;
    }
  }

  .nav-list {
    justify-content: flex-start;
    margin-left: 8px;
    gap: 4px;

    .item {
      font-size: 0.85rem;
      padding: 0 6px;
      height: 30px;

      .item-icon {
        font-size: 0.95rem;
      }
    }
  }
}
@media (max-width: 960px) {
  .header-container.has-model-selector {
    .nav-list {
      margin-right: 0;
    }
  }
}
</style>
