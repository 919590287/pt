<!-- Header -->
<template>
  <div class="header-container">
    <div class="title-box">多智能体出行仿真可视化平台</div>
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
  --header-height: clamp(48px, 3.15vw, 58px);
  --header-padding-x: clamp(8px, 0.9vw, 18px);
  --title-width: clamp(300px, 25vw, 470px);
  --nav-gap: clamp(14px, 2.45vw, 44px);
  --nav-item-height: clamp(30px, 2.1vw, 39px);
  --nav-item-width: clamp(116px, 8.6vw, 154px);
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  display: grid;
  grid-template-columns: minmax(0, var(--title-width)) minmax(0, 1fr) auto;
  align-items: center;
  column-gap: clamp(8px, 1.2vw, 24px);
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
  z-index: 99;
  user-select: none;

  box-shadow: 0px 10px 20px 0px rgba($color: #e8f2ff, $alpha: 0.5);
}

.title-box {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding-right: clamp(8px, 1vw, 18px);
  font-family: "PangMenZhengDaoBiaoTiTi";
  font-size: 24px;
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
    font-size: 18px;
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
  justify-self: end;
  width: clamp(28px, 1.9vw, 36px);
  height: clamp(28px, 1.9vw, 36px);
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
    transform: scale(1.05);
  }
  
  .user-icon {
    width: clamp(15px, 1vw, 18px);
    height: clamp(15px, 1vw, 18px);
  }
}

@media (max-width: 1180px) {
  .header-container {
    --title-width: clamp(250px, 28vw, 330px);
    --nav-gap: 12px;
    --nav-item-width: clamp(104px, 10vw, 128px);
  }

  .title-box {
    font-size: 21px;
  }

  .nav-list .item {
    font-size: 16px;
  }
}

@media (max-width: 860px) {
  .header-container {
    --header-height: 52px;
    grid-template-columns: minmax(0, 210px) minmax(0, 1fr) auto;
    column-gap: 8px;
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
