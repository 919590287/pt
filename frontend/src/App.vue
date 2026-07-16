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
import {
  MAP_DISPLAY_BASE_WIDTH as DESIGN_VIEWPORT_WIDTH,
  MAP_DISPLAY_BASE_HEIGHT as DESIGN_VIEWPORT_HEIGHT,
} from "@/utils/mapDisplayScale.js";

const isCached = ["MapLayout"];
const COMPOSITION_SCALE = 0.92;
const MIN_LAYOUT_SCALE = 0.5;
const MAX_LAYOUT_SCALE = 3;
const BASE_HEADER_HEIGHT = 58;
const BASE_EDGE = 24;
const SCALED_LENGTHS = [2, 12, 16, 18, 20, 24, 26, 46, 64, 70, 76, 108, 260, 282, 320, 414];
let layoutFrameId = 0;

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

function scheduleLayoutScale() {
  if (layoutFrameId) return;
  layoutFrameId = window.requestAnimationFrame(() => {
    layoutFrameId = 0;
    updateLayoutScale();
  });
}

onMounted(() => {
  updateLayoutScale();
  window.addEventListener("resize", scheduleLayoutScale, { passive: true });
  window.visualViewport?.addEventListener("resize", scheduleLayoutScale, { passive: true });
  // 仅在地图内屏蔽右键拖动冲突，并关闭横向回弹；保留页面捏合缩放与非地图区域右键菜单。
  bindBrowserGestureGuard();
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", scheduleLayoutScale);
  window.visualViewport?.removeEventListener("resize", scheduleLayoutScale);
  if (layoutFrameId) {
    window.cancelAnimationFrame(layoutFrameId);
    layoutFrameId = 0;
  }
  unbindBrowserGestureGuard();
});
</script>
