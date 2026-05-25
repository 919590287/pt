<!-- Header -->
<template>
  <div class="header-container">
    <div class="title-box">多智能体公交出行仿真可视化平台</div>
    <div class="nav-list">
      <RouterLink v-for="(item, index) in headerMenus" :to="item.to" active-class="active" class="item"> {{ item.title }} </RouterLink>
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
  --header-scale: 0.8;
  --header-height: calc(64px * var(--header-scale));
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  display: flex;
  align-items: center;
  width: 100%;
  height: var(--header-height);
  background-color: #e8f2ff;
  background-image: url("@/assets/images/header/header-bg.png");
  background-position: left center;
  background-repeat: no-repeat;
  background-size: 100% 100%;
  z-index: 99;
  user-select: none;

  box-shadow: 0px 10px 20px 0px rgba($color: #e8f2ff, $alpha: 0.5);
}

.title-box {
  white-space: nowrap;
  padding: 0 calc(10px * var(--header-scale));
  font-family: "PangMenZhengDaoBiaoTiTi";
  font-size: calc(28px * var(--header-scale));
  color: #fff;
  font-weight: 400;
  letter-spacing: calc(3px * var(--header-scale));
  text-shadow: 0px 2px 4px rgba(24, 57, 96, 0.65);
}

.nav-list {
  display: flex;
  align-items: center;
  margin-left: 5%;
  gap: calc(52px * var(--header-scale));

  .item {
    color: #fff;
    font-size: calc(22px * var(--header-scale));
    font-weight: 600;
    padding: calc(4px * var(--header-scale)) calc(28px * var(--header-scale));
    display: flex;
    align-items: center;
    cursor: pointer;
    white-space: nowrap;
    background-image: url("@/assets/images/header/nav-bg.png");
    background-position: center;
    background-repeat: no-repeat;
    background-size: 100% 100%;
    transition: all 0.3s ease;

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

.user-profile-btn {
  position: absolute;
  right: calc(20px * var(--header-scale));
  top: 50%;
  transform: translateY(-50%);
  width: calc(36px * var(--header-scale));
  height: calc(36px * var(--header-scale));
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(21, 105, 222, 0.08);
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
    transform: translateY(-50%) scale(1.05);
  }
  
  .user-icon {
    width: calc(18px * var(--header-scale));
    height: calc(18px * var(--header-scale));
  }
}
</style>
