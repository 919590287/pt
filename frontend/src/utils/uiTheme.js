/**
 * UI 明暗主题（跟随底图选择，非独立开关）。
 *
 * 唯一信号源：当前底图选项（public/map-config.js 的 BASEMAP_OPTIONS）。
 * 选项带 `dark: true` 视为暗底图；未标记时按 background 亮度推断，
 * 私有化部署替换 BASEMAP_OPTIONS 也能得到正确的 UI 主题。
 *
 * 生效方式：html.dark class + color-scheme。CSS 侧由 main.scss（--app-*）、
 * datamanagement/tokens.css（--dm2-*）与 Element Plus dark css-vars 消费；
 * JS 侧（ECharts 等 canvas 绘制）订阅 isDarkTheme / uiThemeEpoch 重建配色。
 */
import { ref } from "vue";

/** 当前是否暗色 UI。图表等 JS 消费方 watch 此值重建中性色。 */
export const isDarkTheme = ref(false);

function rgbFromBackground(background) {
  if (typeof background === "number" && Number.isFinite(background)) {
    return [(background >> 16) & 255, (background >> 8) & 255, background & 255];
  }
  if (typeof background === "string") {
    const hex = background.trim().replace(/^#/, "");
    const full = hex.length === 3 ? hex.replace(/./g, (ch) => ch + ch) : hex;
    if (/^[0-9a-fA-F]{6}$/.test(full)) {
      const value = Number.parseInt(full, 16);
      return [(value >> 16) & 255, (value >> 8) & 255, value & 255];
    }
  }
  return null;
}

/** 底图选项是否暗色：显式 dark 标记优先，其次 background 亮度，最后 key 兜底。 */
export function basemapOptionIsDark(option) {
  if (!option) return false;
  if (typeof option.dark === "boolean") return option.dark;
  const rgb = rgbFromBackground(option.background);
  if (rgb) {
    const [r, g, b] = rgb;
    return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255 < 0.45;
  }
  return /dark/i.test(String(option.key || ""));
}

export function applyUiTheme(dark) {
  const next = Boolean(dark);
  isDarkTheme.value = next;
  if (typeof document === "undefined") return;
  const rootEl = document.documentElement;
  rootEl.classList.toggle("dark", next);
  rootEl.style.colorScheme = next ? "dark" : "light";
}

function resolveSelectedBasemapOption() {
  if (typeof window === "undefined") return null;
  if (typeof window.getSelectedBaseMapStyle === "function") {
    return window.getSelectedBaseMapStyle();
  }
  // map-config.js 未加载时的兜底：直接按存储键在选项表里找。
  const options = Array.isArray(window.BASEMAP_OPTIONS) ? window.BASEMAP_OPTIONS : [];
  if (!options.length) return null;
  let key = window.DEFAULT_BASEMAP_KEY || options[0].key;
  try {
    key = window.localStorage?.getItem(window.BASEMAP_STORAGE_KEY || "gjcxfzksh:basemap") || key;
  } catch (error) {
    // 存储不可用则用部署默认值。
  }
  return options.find((item) => item.key === key) || options[0];
}

let initialized = false;

/** 应用启动时（挂载前）调用一次：恢复持久化主题并监听底图切换。 */
export function initUiTheme() {
  if (initialized) return;
  initialized = true;
  applyUiTheme(basemapOptionIsDark(resolveSelectedBasemapOption()));
  if (typeof window === "undefined") return;
  window.addEventListener("basemap:changed", (event) => {
    applyUiTheme(basemapOptionIsDark(event?.detail));
  });
}
