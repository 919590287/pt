// 地图显示缩放（分辨率等比适配）
//
// 目标：720p / 1080p / 2K / 4K 下地图（底图 + 点线面矢量 + 文字图标）的观感
// 与设计基准视口（1430×686，即截图视口）完全等比，同时画布后备缓冲始终等于
// 屏幕物理像素（pixelRatio 反向补偿），精度与性能都不变。
//
// 用法：
// - MyMap（及个别直接 new maplibregl.Map 的页面）用 createMapDisplayHost 在
//   容器内建一层 CSS zoom 宿主，地图挂在宿主上；
// - 地图 pixelRatio = 基准像素比 × 当前缩放（见 mapCanvasPixelRatio）；
// - 缩放随窗口尺寸变化，onMapDisplayScaleChange 订阅后同步宿主样式与 pixelRatio。
//
// 数学关系：宿主布局尺寸 = 视口/S，CSS zoom = S → 视觉尺寸 = 视口；
// 画布后备缓冲 = (视口/S) × (dpr×S) = 视口×dpr = 物理像素，与不缩放时完全一致。

export const MAP_DISPLAY_BASE_WIDTH = 1430;
export const MAP_DISPLAY_BASE_HEIGHT = 686;
const MIN_SCALE = 0.5;
const MAX_SCALE = 3;

const listeners = new Set();
let currentScale = 1;
let installed = false;
let notifyTimer = 0;
let dppxQuery = null;

function runtimeFixedScale() {
  const value = Number(typeof window !== "undefined" ? window.APP_CONFIG?.mapDisplayScale : NaN);
  return Number.isFinite(value) && value > 0 ? value : null;
}

// CSS zoom 不可用（老 Firefox 等）时整体退化为 1：布局与交互保持现状，只是不再等比放大
export function mapDisplayZoomSupported() {
  if (typeof CSS === "undefined" || typeof CSS.supports !== "function") return false;
  return CSS.supports("zoom", "2");
}

// 视口相对基准的原始比例（未夹取、未乘任何构图系数），App.vue 布局缩放与地图共用
export function computeViewportScaleRaw() {
  if (typeof window === "undefined") return 1;
  const viewport = window.visualViewport;
  const width = viewport?.width || window.innerWidth || MAP_DISPLAY_BASE_WIDTH;
  const height = viewport?.height || window.innerHeight || MAP_DISPLAY_BASE_HEIGHT;
  return Math.min(width / MAP_DISPLAY_BASE_WIDTH, height / MAP_DISPLAY_BASE_HEIGHT) || 1;
}

function computeMapDisplayScale() {
  const fixed = runtimeFixedScale();
  if (fixed) return Math.min(MAX_SCALE, Math.max(MIN_SCALE, fixed));
  if (!mapDisplayZoomSupported()) return 1;
  return Math.min(MAX_SCALE, Math.max(MIN_SCALE, computeViewportScaleRaw()));
}

export function getMapDisplayScale() {
  ensureInstalled();
  return currentScale;
}

// 地图画布应使用的像素比：基准像素比（默认 devicePixelRatio，可被
// APP_CONFIG.mapPixelRatio 降级开关覆盖）× 显示缩放，保证后备缓冲 = 物理像素
export function mapCanvasPixelRatio(scale = getMapDisplayScale()) {
  const configured = Number(typeof window !== "undefined" ? window.APP_CONFIG?.mapPixelRatio : NaN);
  const base = Number.isFinite(configured) && configured > 0
    ? Math.max(1, Math.min(3, configured))
    : (typeof window !== "undefined" && window.devicePixelRatio) || 1;
  return Math.max(0.5, Math.min(6, base * scale));
}

export function onMapDisplayScaleChange(callback) {
  ensureInstalled();
  listeners.add(callback);
  return () => listeners.delete(callback);
}

function notify() {
  const next = computeMapDisplayScale();
  // devicePixelRatio 变化（跨屏拖动/浏览器缩放）时 scale 可能不变但像素比要重算，
  // 因此即使值相同也广播一次
  currentScale = next;
  if (typeof document !== "undefined") {
    document.documentElement.style.setProperty("--map-display-scale", String(next));
  }
  listeners.forEach((callback) => {
    try {
      callback(next);
    } catch (error) {
      console.warn("[mapDisplayScale] listener failed", error);
    }
  });
}

// 定时器去抖而非 rAF 合帧：后台/被节流的标签页里 rAF 可能长期不执行，
// 会把挂起标记卡死导致后续 resize 全部丢失；timer 在节流下最多延迟到 ~1s 仍会触发
function scheduleNotify() {
  if (notifyTimer) return;
  notifyTimer = setTimeout(() => {
    notifyTimer = 0;
    notify();
  }, 80);
}

function watchDppx() {
  if (typeof matchMedia !== "function") return;
  try {
    dppxQuery?.removeEventListener?.("change", onDppxChange);
  } catch (error) {
    void error;
  }
  const dpr = (typeof window !== "undefined" && window.devicePixelRatio) || 1;
  dppxQuery = matchMedia(`(resolution: ${dpr}dppx)`);
  dppxQuery.addEventListener?.("change", onDppxChange, { once: true });
}

function onDppxChange() {
  scheduleNotify();
  watchDppx();
}

function ensureInstalled() {
  if (installed || typeof window === "undefined") return;
  installed = true;
  currentScale = computeMapDisplayScale();
  if (typeof document !== "undefined") {
    document.documentElement.style.setProperty("--map-display-scale", String(currentScale));
  }
  window.addEventListener("resize", scheduleNotify, { passive: true });
  window.visualViewport?.addEventListener("resize", scheduleNotify, { passive: true });
  watchDppx();
}

// 在容器内创建 zoom 宿主并保持随缩放同步。返回 { host, dispose }。
// 宿主布局尺寸为容器的 1/S，经 zoom 放大后视觉上恰好铺满容器。
export function createMapDisplayHost(rootEl) {
  ensureInstalled();
  const host = document.createElement("div");
  host.className = "map-display-host";
  host.style.position = "absolute";
  host.style.top = "0";
  host.style.left = "0";
  // CSS zoom 会同时放大宿主的布局盒和画布。为了让放大后的视觉盒仍然
  // 恰好铺满父容器，布局尺寸必须是父容器的 1/S。部分 Chromium 版本
  // 对百分比尺寸的 zoom 处理不同，不能依赖“100% 会自动换算”的行为，
  // 否则地图会被测量成左下角的一小块（服务器环境最容易触发）。
  host.style.setProperty("--map-display-host-scale", String(currentScale));
  host.style.width = `calc(100% / var(--map-display-host-scale, 1))`;
  host.style.height = `calc(100% / var(--map-display-host-scale, 1))`;
  host.style.transformOrigin = "top left";

  const apply = (scale) => {
    host.style.setProperty("--map-display-host-scale", String(scale || 1));
    if (mapDisplayZoomSupported()) {
      host.style.zoom = String(scale || 1);
    } else {
      host.style.zoom = "1";
    }
  };
  apply(currentScale);
  rootEl.appendChild(host);
  const unsubscribe = onMapDisplayScaleChange(apply);

  return {
    host,
    dispose() {
      unsubscribe();
      host.remove();
    },
  };
}
