<template>
  <!-- 缓存路由组件 -->
  <router-view v-slot="{ Component }">
    <transition name="fade-transform" mode="out-in">
      <keep-alive :include="isCached">
        <component :is="Component" />
      </keep-alive>
    </transition>
  </router-view>
</template>

<script setup>
import { onBeforeUnmount, onMounted } from "vue";
import { bindBrowserGestureGuard, unbindBrowserGestureGuard } from "@/utils/browserGestureGuard.js";

const isCached = ["MapLayout"];

const DESIGN_VIEWPORT_WIDTH = 1430;
const DESIGN_VIEWPORT_HEIGHT = 686;
const COMPOSITION_SCALE = 0.92;
const MIN_LAYOUT_SCALE = 0.5;
const MAX_LAYOUT_SCALE = 3;
const BASE_HEADER_HEIGHT = 58;
const BASE_EDGE = 24;
const SCALED_LENGTHS = [2, 12, 16, 18, 20, 24, 26, 70, 76, 108, 260, 282, 320, 414];

function updateLayoutScale() {
  const viewport = window.visualViewport;
  const width = viewport?.width || window.innerWidth || DESIGN_VIEWPORT_WIDTH;
  const height = viewport?.height || window.innerHeight || DESIGN_VIEWPORT_HEIGHT;
  const rawScale = Math.min(width / DESIGN_VIEWPORT_WIDTH, height / DESIGN_VIEWPORT_HEIGHT) * COMPOSITION_SCALE;
  const scale = Math.min(MAX_LAYOUT_SCALE, Math.max(MIN_LAYOUT_SCALE, rawScale || 1));
  const headerHeight = BASE_HEADER_HEIGHT * scale;
  const edge = BASE_EDGE * scale;

  document.documentElement.style.setProperty("--app-layout-scale", scale.toFixed(4));
  document.documentElement.style.setProperty("--app-viewport-width", `${Math.round(width)}px`);
  document.documentElement.style.setProperty("--app-viewport-height", `${Math.round(height)}px`);
  document.documentElement.style.setProperty("--app-unscaled-viewport-width", `${(width / scale).toFixed(2)}px`);
  document.documentElement.style.setProperty("--app-header-height", `${headerHeight.toFixed(2)}px`);
  document.documentElement.style.setProperty("--app-edge", `${edge.toFixed(2)}px`);
  document.documentElement.style.setProperty("--app-dm-sidebar-height", `${Math.max(0, (height - headerHeight) / scale).toFixed(2)}px`);
  document.documentElement.style.setProperty("--app-dm-panel-height", `${Math.max(0, (height - headerHeight - 24 * scale) / scale).toFixed(2)}px`);
  document.documentElement.style.setProperty("--app-dm-history-preview-height", `${Math.max(0, (height - headerHeight - 108 * scale) / scale).toFixed(2)}px`);
  document.documentElement.style.setProperty("--app-dm-history-side-width", `${Math.max(0, (width - 320 * scale) / scale).toFixed(2)}px`);
  SCALED_LENGTHS.forEach((value) => {
    document.documentElement.style.setProperty(`--app-scaled-${value}`, `${(value * scale).toFixed(2)}px`);
  });
}

onMounted(() => {
  updateLayoutScale();
  window.addEventListener("resize", updateLayoutScale, { passive: true });
  window.visualViewport?.addEventListener("resize", updateLayoutScale, { passive: true });
  // 全局屏蔽浏览器手势（右键拖动手势/横扫前进后退/捏合缩放页面），避免与地图右键拖动冲突
  bindBrowserGestureGuard();
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", updateLayoutScale);
  window.visualViewport?.removeEventListener("resize", updateLayoutScale);
  unbindBrowserGestureGuard();
});
</script>
