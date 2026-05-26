<!-- Header -->
<template>
  <div class="header-container" :class="{ 'has-model-selector': route.name === 'datavisualization' }">
    <div class="title-box">多智能体出行仿真可视化平台</div>
    <div class="nav-list">
      <RouterLink v-for="item in headerMenus" :key="item.title" :to="item.to" active-class="active" class="item">{{ item.title }}</RouterLink>
    </div>
    <div class="user-profile-btn" title="用户管理">
      <svg class="user-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
        <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path>
        <circle cx="12" cy="7" r="4"></circle>
      </svg>
    </div>
  </div>
</template>

<script setup>
const route = useRoute();

const headerMenus = [
  {
    title: "数据可视化",
    to: { name: "datavisualization" },
  },
  {
    title: "场景搭建",
    to: { name: "scenarioedit" },
  },
  {
    title: "场景对比",
    to: { name: "scenariocomparison" },
  },
];
</script>

<style scoped lang="scss">
$white-color: #ffffff;

.header-container {
  --header-height: var(--app-header-height);
  --header-padding-x: clamp(12px, 1.25vw, 24px);
  --title-width: clamp(300px, 26vw, 460px);
  --nav-gap: clamp(10px, 1.7vw, 28px);
  --nav-item-height: clamp(32px, 2.1vw, 38px);
  --nav-item-width: clamp(112px, 8.2vw, 148px);
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
  background-color: #e8f2ff;
  background-image: url("@/assets/images/header/header-bg.png");
  background-position: left center;
  background-repeat: no-repeat;
  background-size: 100% 100%;
  z-index: var(--z-header);
  user-select: none;
  box-shadow: 0 10px 24px rgba($color: #8ab8ef, $alpha: 0.18);
}

.title-box {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding-right: var(--space-md);
  font-family: "PangMenZhengDaoBiaoTiTi";
  font-size: clamp(20px, 1.45vw, 25px);
  color: #fff;
  font-weight: 400;
  letter-spacing: 0;
  text-shadow: 0px 2px 4px rgba(24, 57, 96, 0.65);
}

.nav-list {
  display: flex;
  align-items: center;
  justify-content: center;
  justify-self: center;
  min-width: 0;
  max-width: 100%;
  gap: var(--nav-gap);
  padding: 0 var(--space-xs);
  overflow-x: auto;
  overflow-y: hidden;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }

  .item {
    width: var(--nav-item-width);
    min-width: var(--nav-item-width);
    height: var(--nav-item-height);
    color: #fff;
    font-size: clamp(15px, 1.05vw, 18px);
    font-weight: 600;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    white-space: nowrap;
    background-image: url("@/assets/images/header/nav-bg.png");
    background-position: center;
    background-repeat: no-repeat;
    background-size: 100% 100%;
    transition: all 0.3s ease;
    text-shadow: 0 1px 2px rgba(11, 56, 116, 0.34);

    &:hover {
      background-image: url("@/assets/images/header/nav-bg-active.png");
      color: #fff;
    }
    &.active {
      color: #fff;
      background-image: url("@/assets/images/header/nav-bg-active.png");
    }
  }
}

.header-container.has-model-selector {
  .nav-list {
    padding-right: clamp(380px, 22vw, 500px);
  }
}

.user-profile-btn {
  justify-self: end;
  width: clamp(28px, 1.9vw, 36px);
  height: clamp(28px, 1.9vw, 36px);
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(21, 105, 222, 0.25);
  border-radius: 50%;
  cursor: pointer;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  backdrop-filter: blur(4px);
  color: #1569de;
  
  &:hover {
    background: rgba(21, 105, 222, 0.18);
    border-color: rgba(21, 105, 222, 0.45);
    color: #1569de;
    box-shadow: 0 0 10px rgba(21, 105, 222, 0.15);
    transform: scale(1.05);
  }
  
  .user-icon {
    width: clamp(15px, 1vw, 18px);
    height: clamp(15px, 1vw, 18px);
  }
}

@media (max-width: 1180px) {
  .header-container {
    --title-width: clamp(220px, 24vw, 300px);
    --nav-gap: 12px;
    --nav-item-width: clamp(104px, 10vw, 128px);
    column-gap: var(--space-sm);
  }

  .title-box {
    font-size: 21px;
  }

  .nav-list .item {
    font-size: 16px;
  }

  .header-container.has-model-selector {
    .nav-list {
      justify-content: flex-start;
      padding-right: clamp(340px, 36vw, 410px);
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
    font-size: 18px;
  }

  .nav-list {
    justify-content: flex-start;
  }

  .nav-list .item {
    font-size: 14px;
  }
}
</style>
