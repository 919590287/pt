<!-- Header -->
<template>
  <header class="header-container" :class="{ 'has-model-selector': hasModelSelector }">
    <div class="title-box">
      <svg class="logo-icon" viewBox="0 0 36 36" width="30" height="30" fill="none" xmlns="http://www.w3.org/2000/svg">
        <rect width="36" height="36" rx="9" fill="url(#logo-gradient)" class="logo-bg" />
        <!-- Top Arch: Base Track + Pulse Flow -->
        <path d="M10 18 C 14 13, 22 13, 26 18" stroke="#ffffff" stroke-width="2.5" stroke-linecap="round" class="route-track" />
        <path d="M10 18 C 14 13, 22 13, 26 18" stroke="#ffffff" stroke-width="2.5" stroke-linecap="round" class="route-flow top-flow" />
        
        <!-- Bottom Arch: Base Track + Pulse Flow -->
        <path d="M10 18 C 14 23, 22 23, 26 18" stroke="#8db9ba" stroke-width="2" stroke-linecap="round" class="route-track" />
        <path d="M10 18 C 14 23, 22 23, 26 18" stroke="#8db9ba" stroke-width="2" stroke-linecap="round" class="route-flow bottom-flow" />
        
        <!-- Station Nodes -->
        <circle cx="10" cy="18" r="3.5" fill="#ffffff" class="node-point node-white" />
        <circle cx="18" cy="15" r="3" fill="#9fb6bd" class="node-point node-pink" />
        <circle cx="26" cy="18" r="3.5" fill="#8db9ba" class="node-point node-cyan" />
        <defs>
          <linearGradient id="logo-gradient" x1="0" y1="0" x2="36" y2="36" gradientUnits="userSpaceOnUse">
            <stop offset="0%" stop-color="#20353a" />
            <stop offset="48%" stop-color="#2f6f73" />
            <stop offset="100%" stop-color="#6fa1a4" />
          </linearGradient>
        </defs>
      </svg>
      <span class="title-text">公共交通数智化治理平台</span>
    </div>
    <nav class="nav-list" aria-label="主导航">
      <RouterLink
        v-for="item in headerMenus"
        :key="item.title"
        :to="item.to"
        active-class="active"
        class="item"
      >
        <span class="item-title">{{ item.title }}</span>
      </RouterLink>
    </nav>
    <el-dropdown class="user-menu" popper-class="user-dropdown-popper" transition="none" trigger="click" @command="handleUserCommand">
      <button class="user-profile-btn" type="button" :title="`用户管理：${currentUsername}`" aria-label="用户管理">
        <span class="user-initial">{{ userInitial }}</span>
      </button>
      <template #dropdown>
        <el-dropdown-menu class="user-dropdown">
          <div class="user-menu-head">
            <div class="user-avatar-badge">
              <span class="user-initial-inner">{{ userInitial }}</span>
            </div>
            <div class="user-info-text">
              <span class="user-menu-label">当前账户</span>
              <strong class="user-menu-name" :title="currentUsername">{{ currentUsername }}</strong>
            </div>
          </div>
          <el-dropdown-item command="rename" class="custom-dropdown-item">
            <svg class="item-icon" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
              <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7" />
              <path d="M18.5 2.5a2.121 2.121 0 1 1 3 3L12 15l-4 1 1-4 9.5-9.5z" />
            </svg>
            <span>修改用户名称</span>
          </el-dropdown-item>
          <el-dropdown-item command="logout" divided class="custom-dropdown-item logout-item">
            <svg class="item-icon" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
              <polyline points="16 17 21 12 16 7" />
              <line x1="21" y1="12" x2="9" y2="12" />
            </svg>
            <span>退出登录</span>
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>

    <el-dialog v-model="renameDialogVisible" title="修改用户名称" width="360px" append-to-body>
      <el-form ref="renameFormRef" :model="renameForm" :rules="renameRules" label-position="top">
        <el-form-item label="新用户名" prop="username">
          <el-input v-model.trim="renameForm.username" maxlength="32" placeholder="请输入新用户名" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="renameDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="isRenaming" @click="handleRenameUser">保存</el-button>
      </template>
    </el-dialog>
  </header>
</template>

<script setup>
import { ElMessage, ElMessageBox } from "element-plus";
import { logout, renameUser } from "@/api/auth";
import { clearAuth, getUsername, saveAuth } from "@/utils/auth";

const route = useRoute();
const router = useRouter();
const hasModelSelector = computed(() => ["datavisualization", "datamanagement"].includes(route.name));
const currentUsername = ref(getUsername() || "用户");
const renameDialogVisible = ref(false);
const renameFormRef = ref(null);
const isRenaming = ref(false);
const renameForm = reactive({
  username: currentUsername.value,
});
const renameRules = {
  username: [
    { required: true, message: "请输入新用户名", trigger: "blur" },
    { pattern: /^[\p{L}\p{N}_.-]{2,32}$/u, message: "用户名需为2-32位中文、字母、数字、点、短横线或下划线", trigger: "blur" },
  ],
};
const userInitial = computed(() => (currentUsername.value || "用").slice(0, 1).toUpperCase());

function syncCurrentUser() {
  currentUsername.value = getUsername() || "用户";
  renameForm.username = currentUsername.value;
}

function handleUserCommand(command) {
  if (command === "rename") {
    renameForm.username = currentUsername.value;
    renameDialogVisible.value = true;
    nextTick(() => renameFormRef.value?.clearValidate?.());
    return;
  }
  if (command === "logout") {
    handleLogout();
  }
}

async function handleRenameUser() {
  try {
    await renameFormRef.value?.validate?.();
  } catch (error) {
    return;
  }
  if (renameForm.username === currentUsername.value) {
    renameDialogVisible.value = false;
    return;
  }
  isRenaming.value = true;
  try {
    const res = await renameUser({ username: renameForm.username });
    saveAuth(res.data);
    syncCurrentUser();
    renameDialogVisible.value = false;
    ElMessage.success("用户名已更新，模型目录已同步修改");
  } catch (error) {
    // request.js has already shown the user-facing message.
  } finally {
    isRenaming.value = false;
  }
}

async function handleLogout() {
  try {
    await ElMessageBox.confirm("退出后需要重新登录才能进入系统。", "退出登录", {
      confirmButtonText: "退出",
      cancelButtonText: "取消",
      type: "warning",
    });
  } catch (error) {
    return;
  }
  try {
    await logout({ silentError: true });
  } finally {
    clearAuth();
    router.replace({ name: "login" });
  }
}

onMounted(() => {
  window.addEventListener("auth:changed", syncCurrentUser);
});

onBeforeUnmount(() => {
  window.removeEventListener("auth:changed", syncCurrentUser);
});

const headerMenus = [
  {
    title: "数据管理",
    to: { name: "datamanagement" },
  },
  {
    title: "运行监测",
    to: { name: "datavisualization" },
  },
  {
    title: "客流分析",
    to: { name: "passengerflowanalysis" },
  },
  {
    title: "线网优化",
    to: { name: "scenarioedit" },
  },
  {
    title: "优化评估",
    to: { name: "scenariocomparison" },
  },
  {
    title: "配车测算",
    to: { name: "vehiclecalculation" },
  },
];
</script>

<style scoped lang="scss">
.header-container {
  --header-height: var(--app-header-height);
  --header-padding-x: clamp(12px, 1.25vw, 24px);
  --title-width: clamp(260px, 20vw, 350px);
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
  cursor: pointer;

  .logo-icon {
    flex-shrink: 0;
    transition: transform var(--app-motion-normal) var(--app-ease-out);

    .logo-bg {
      transition: fill var(--app-motion-normal) var(--app-ease-out);
    }

    // Base paths representing the silent static pipeline structure
    .route-track {
      opacity: 0.22;
      transition: opacity var(--app-motion-normal) var(--app-ease-out);
    }

    // Dynamic stream flowing along the paths
    .route-flow {
      stroke-dasharray: 6 16;
      stroke-dashoffset: 22;
      animation: flowPulse 2s linear infinite;
      
      &.top-flow {
        animation-duration: 2.2s;
      }
      &.bottom-flow {
        animation-duration: 1.8s;
        animation-direction: reverse; // flows in opposite direction for dynamic balance!
      }
    }

    // Station node points with soft physical micro-behaviors
    .node-point {
      transition: all 0.4s cubic-bezier(0.25, 0.8, 0.25, 1);
      transform-origin: center;
      
      &.node-white {
        animation: nodePulseWhite 3s ease-in-out infinite;
      }
      
      &.node-pink {
        animation: nodePulsePink 3s ease-in-out infinite 0.8s;
      }
      
      &.node-cyan {
        animation: nodePulseCyan 3s ease-in-out infinite 1.6s;
      }
    }
  }

  .title-text {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    transition: 
      color var(--app-motion-normal) var(--app-ease-out),
      letter-spacing 0.5s cubic-bezier(0.25, 0.8, 0.25, 1);
  }

  // Interactivity state
  &:hover {
    .logo-icon {
      transform: scale(1.04);
      
      .route-track {
        opacity: 0.4;
      }
      
      .route-flow {
        &.top-flow {
          animation-duration: 0.9s;
          stroke-width: 3px;
        }
        &.bottom-flow {
          animation-duration: 0.75s;
          stroke-width: 2.5px;
        }
      }
      
      .node-point {
        filter: drop-shadow(0 0 2px rgba(255, 255, 255, 0.8));
        
        &.node-white {
          transform: scale(1.18);
        }
        &.node-pink {
          transform: scale(1.22) translateY(-1px);
          fill: #ffb6c1; // slightly brighter pink
        }
        &.node-cyan {
          transform: scale(1.18);
          fill: #00ffff; // bright energetic cyan
        }
      }
    }

    .title-text {
      color: var(--app-blue);
      letter-spacing: 0.02em; // premium dynamic micro-spacing expansion
    }
  }
}

// Animation keyframes for the flowing pulse
@keyframes flowPulse {
  to {
    stroke-dashoffset: 0;
  }
}

// Staggered node pulses mimicking system breath
@keyframes nodePulseWhite {
  0%, 100% {
    transform: scale(1);
    opacity: 0.95;
  }
  50% {
    transform: scale(1.12);
    opacity: 1;
  }
}

@keyframes nodePulsePink {
  0%, 100% {
    transform: scale(1);
    opacity: 0.9;
  }
  50% {
    transform: scale(1.15);
    opacity: 1;
  }
}

@keyframes nodePulseCyan {
  0%, 100% {
    transform: scale(1);
    opacity: 0.95;
  }
  50% {
    transform: scale(1.12);
    opacity: 1;
  }
}

.nav-list {
  display: flex;
  align-items: center;
  justify-content: flex-start;
  justify-self: start;
  min-width: 0;
  max-width: 100%;
  gap: clamp(4px, 0.6vw, 10px);
  margin-left: clamp(8px, 1.2vw, 20px);
  padding: 0 var(--space-xs);
  overflow-x: auto;
  overflow-y: hidden;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }

  .item {
    height: 36px;
    padding: 0 clamp(8px, 0.9vw, 14px);
    border-radius: 6px;
    color: #4b5563;
    font-size: 0.95rem;
    font-weight: 600;
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    white-space: nowrap;
    transition:
      color var(--app-motion-normal) var(--app-ease-out),
      background-color var(--app-motion-normal) var(--app-ease-out),
      transform var(--app-motion-fast) var(--app-ease-press);

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
    margin-right: clamp(540px, 29vw, 590px);
    padding-right: 0;
  }
}

.user-menu {
  justify-self: end;
}

.user-profile-btn {
  width: clamp(32px, 2.2vw, 40px);
  height: clamp(32px, 2.2vw, 40px);
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, var(--app-blue-soft) 0%, rgba(21, 105, 222, 0.12) 100%);
  border: 1.5px solid rgba(21, 105, 222, 0.2);
  border-radius: 50%;
  cursor: pointer;
  transition: all var(--app-motion-normal) var(--app-ease-out);
  color: var(--app-blue);
  box-shadow: 0 2px 6px rgba(21, 105, 222, 0.06);

  &:hover,
  &:focus-visible {
    background: linear-gradient(135deg, var(--app-blue-soft) 0%, rgba(21, 105, 222, 0.2) 100%);
    border-color: var(--app-blue);
    box-shadow: 0 4px 12px rgba(21, 105, 222, 0.16);
    transform: translateY(-1px) scale(1.05);
  }

  &:active {
    transform: translateY(1px) scale(0.98);
  }

  &:focus-visible {
    outline: none;
    box-shadow: var(--app-focus-ring);
  }

  .user-initial {
    font-size: 0.95rem;
    line-height: 1;
    font-weight: 700;
    color: var(--app-blue-strong);
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

/* Unified chrome for data-heavy platform pages. */
.header-container {
  --platform-ink: #17282c;
  --platform-muted: #5d6b70;
  --platform-accent: #2f6f73;
  --platform-accent-strong: #204f53;
  --platform-surface: rgba(249, 252, 253, 0.96);
  --platform-border: rgba(35, 50, 55, 0.1);
  --platform-ease: cubic-bezier(0.32, 0.72, 0, 1);
  height: var(--header-height);
  padding: 0 clamp(14px, 1.45vw, 26px);
  background:
    linear-gradient(180deg, rgba(252, 254, 255, 0.98), rgba(244, 249, 250, 0.96)),
    repeating-linear-gradient(135deg, rgba(35, 50, 55, 0.018) 0 1px, transparent 1px 8px);
  border-bottom: 1px solid var(--platform-border);
  box-shadow: none;
}

.title-box {
  color: var(--platform-ink);
}

.title-box .logo-icon {
  width: 32px;
  height: 32px;
  filter: drop-shadow(0 7px 14px rgba(47, 111, 115, 0.16));
}

.title-box .route-flow {
  animation-timing-function: cubic-bezier(0.32, 0.72, 0, 1);
}

.title-box .node-point.node-white,
.title-box .node-point.node-pink,
.title-box .node-point.node-cyan {
  animation-duration: 4.8s;
}

.title-box:hover .title-text {
  color: var(--platform-accent-strong);
  letter-spacing: 0;
}

.title-box:hover .logo-icon .node-point.node-pink {
  fill: #b7c8cd;
}

.title-box:hover .logo-icon .node-point.node-cyan {
  fill: #a6ccce;
}

.nav-list {
  gap: clamp(6px, 0.7vw, 12px);
}

.nav-list .item {
  height: 36px;
  padding: 0 clamp(10px, 0.95vw, 16px);
  border-radius: 11px;
  color: var(--platform-muted);
  font-size: 0.93rem;
  font-weight: 680;
  transition:
    color 360ms var(--platform-ease),
    background-color 360ms var(--platform-ease),
    box-shadow 360ms var(--platform-ease),
    transform 260ms var(--platform-ease);
}

.nav-list .item:hover {
  color: var(--platform-ink);
  background: rgba(47, 111, 115, 0.07);
  transform: translateY(-1px);
}

.nav-list .item.active {
  color: var(--platform-accent-strong);
  background: linear-gradient(90deg, rgba(47, 111, 115, 0.12), rgba(49, 93, 138, 0.07));
  box-shadow: inset 0 0 0 1px rgba(47, 111, 115, 0.12);
}

.nav-list .item.active:hover {
  background: linear-gradient(90deg, rgba(47, 111, 115, 0.15), rgba(49, 93, 138, 0.09));
}

.user-profile-btn {
  border: 1px solid rgba(47, 111, 115, 0.16);
  background: rgba(255, 255, 255, 0.74);
  color: var(--platform-accent);
  box-shadow: 0 10px 22px rgba(24, 43, 50, 0.08), inset 0 1px 0 rgba(255, 255, 255, 0.78);
  transition:
    background-color 360ms var(--platform-ease),
    border-color 360ms var(--platform-ease),
    box-shadow 360ms var(--platform-ease),
    transform 260ms var(--platform-ease);
}

.user-profile-btn:hover,
.user-profile-btn:focus-visible {
  background: rgba(47, 111, 115, 0.09);
  border-color: rgba(47, 111, 115, 0.32);
  box-shadow: 0 14px 28px rgba(24, 43, 50, 0.12), 0 0 0 4px rgba(47, 111, 115, 0.08);
  transform: translateY(-1px);
}

.user-profile-btn .user-initial {
  color: var(--platform-accent-strong);
  font-weight: 780;
}

.header-container.has-model-selector .nav-list {
  margin-right: clamp(500px, 28vw, 580px);
}

@media (max-width: 960px) {
  .header-container.has-model-selector .nav-list {
    margin-right: 0;
  }
}
</style>

<style lang="scss">
/* Global styles for the teleported user dropdown popper to ensure bulletproof rendering and gorgeous visuals */
.user-dropdown-popper {
  border-radius: 12px !important;
  border: 1px solid rgba(21, 105, 222, 0.15) !important;
  box-shadow: 0 10px 32px rgba(15, 66, 125, 0.12) !important;
  padding: 6px 0 !important;
  background: rgba(255, 255, 255, 0.98) !important;
  backdrop-filter: blur(8px);
  
  // Custom morphing and scaling entry transition
  transform-origin: top right !important;
  transition: 
    transform 0.4s cubic-bezier(0.34, 1.56, 0.64, 1),
    opacity 0.28s ease-out,
    filter 0.3s ease-out,
    border-radius 0.4s cubic-bezier(0.34, 1.56, 0.64, 1) !important;

  @starting-style {
    opacity: 0 !important;
    transform: scale(0.18) translate(76px, -52px) !important;
    filter: blur(6px) !important;
    border-radius: 50% !important;
  }

  // Hide standard list bullets leaking from global stylesheet resets
  ul.el-dropdown-menu,
  li.el-dropdown-menu__item,
  li.custom-dropdown-item {
    list-style: none !important;
    list-style-type: none !important;
    padding: 0;
    margin: 0;
    &::before {
      display: none !important;
    }
  }

  .el-dropdown-menu {
    padding: 4px 0 !important;
  }

  // Gorgeous user info head inside dropdown
  .user-menu-head {
    display: flex !important;
    align-items: center !important;
    gap: 12px !important;
    min-width: 210px !important;
    padding: 14px 16px 12px !important;
    border-bottom: 1px solid rgba(0, 0, 0, 0.05) !important;
    margin-bottom: 6px !important;
    color: #1f2937 !important;
    
    // Staggered fade and slide entry
    transition: 
      transform 0.4s cubic-bezier(0.16, 1, 0.3, 1),
      opacity 0.3s ease-out !important;
    transition-delay: 0.06s !important;

    @starting-style {
      opacity: 0 !important;
      transform: translateY(14px) !important;
    }

    .user-avatar-badge {
      width: 38px;
      height: 38px;
      border-radius: 50%;
      background: linear-gradient(135deg, var(--app-blue) 0%, var(--app-cyan) 100%);
      display: flex;
      align-items: center;
      justify-content: center;
      box-shadow: 0 2px 8px rgba(21, 105, 222, 0.2);
      flex-shrink: 0;

      .user-initial-inner {
        color: #ffffff;
        font-weight: 800;
        font-size: 1.05rem;
      }
    }

    .user-info-text {
      display: flex;
      flex-direction: column;
      gap: 2px;
      min-width: 0;
      flex: 1;

      .user-menu-label {
        color: #9ca3af;
        font-size: 11px;
        font-weight: 600;
        letter-spacing: 0.05em;
        text-transform: uppercase;
      }

      .user-menu-name {
        max-width: 130px;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        font-size: 15px;
        font-weight: 750;
        color: #111827;
      }
    }
  }

  // Modern list item design
  .el-dropdown-menu__item.custom-dropdown-item {
    display: flex !important;
    align-items: center !important;
    gap: 10px !important;
    padding: 9px 16px !important;
    font-size: 13.5px !important;
    font-weight: 600 !important;
    color: #4b5563 !important;
    line-height: 1.4 !important;
    cursor: pointer !important;
    margin: 2px 6px !important;
    border-radius: 6px !important;
    background: transparent !important;
    border: none !important;

    // Staggered fade and slide entry for menu items
    transition: 
      transform 0.4s cubic-bezier(0.16, 1, 0.3, 1),
      opacity 0.3s ease-out,
      background-color 0.2s ease,
      color 0.2s ease !important;

    &:nth-of-type(1) {
      transition-delay: 0.12s !important;
      @starting-style {
        opacity: 0 !important;
        transform: translateY(10px) !important;
      }
    }

    &:nth-of-type(2) {
      transition-delay: 0.18s !important;
      @starting-style {
        opacity: 0 !important;
        transform: translateY(10px) !important;
      }
    }

    .item-icon {
      flex-shrink: 0;
      color: #8b95a5;
      transition: color 0.2s ease;
    }

    &:hover,
    &:focus {
      background-color: rgba(21, 105, 222, 0.08) !important;
      color: var(--app-blue-strong) !important;

      .item-icon {
        color: var(--app-blue);
      }
    }

    // Logout custom danger theme
    &.logout-item {
      color: #4b5563 !important;

      .item-icon {
        color: #9ca3af;
      }

      &:hover,
      &:focus {
        background-color: rgba(220, 76, 93, 0.08) !important;
        color: #dc2626 !important;

        .item-icon {
          color: #dc2626;
        }
      }
    }
  }

  // Reset divided line style to be clean and bulletproof
  .el-dropdown-menu__item--divided {
    margin: 6px 0 0 0 !important;
    border-top: 1px solid rgba(0, 0, 0, 0.06) !important;
    padding: 0 !important;
    height: 0 !important;
    background: none !important;
    pointer-events: none !important;
    &::before {
      display: none !important;
    }
  }

  // Popper arrow adjustments
  .el-popper__arrow::before {
    background: rgba(255, 255, 255, 0.98) !important;
    border-color: rgba(21, 105, 222, 0.15) !important;
  }

  & {
    border-radius: 14px !important;
    border-color: rgba(35, 50, 55, 0.12) !important;
    background: rgba(249, 252, 253, 0.98) !important;
    box-shadow: 0 22px 52px rgba(24, 43, 50, 0.16) !important;
    backdrop-filter: none;
  }

  .user-menu-head {
    border-bottom-color: rgba(35, 50, 55, 0.08) !important;
    color: #17282c !important;
  }

  .user-menu-head .user-avatar-badge {
    background: linear-gradient(135deg, #20353a, #2f6f73) !important;
    box-shadow: 0 8px 18px rgba(47, 111, 115, 0.2) !important;
  }

  .user-menu-head .user-info-text .user-menu-label {
    color: #7b898d !important;
  }

  .user-menu-head .user-info-text .user-menu-name {
    color: #17282c !important;
  }

  .el-dropdown-menu__item.custom-dropdown-item {
    border-radius: 10px !important;
    color: #526166 !important;
  }

  .el-dropdown-menu__item.custom-dropdown-item:hover,
  .el-dropdown-menu__item.custom-dropdown-item:focus {
    background-color: rgba(47, 111, 115, 0.08) !important;
    color: #204f53 !important;
  }

  .el-dropdown-menu__item.custom-dropdown-item:hover .item-icon,
  .el-dropdown-menu__item.custom-dropdown-item:focus .item-icon {
    color: #2f6f73 !important;
  }

  .el-popper__arrow::before {
    background: rgba(249, 252, 253, 0.98) !important;
    border-color: rgba(35, 50, 55, 0.12) !important;
  }
}
</style>
