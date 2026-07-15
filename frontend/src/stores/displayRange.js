import { defineStore } from "pinia";
import { ref } from "vue";

// 行政区显示范围跨模块联动：数据管理 / 运行监测 / 客流分析 / 换乘分析共用同一选区。
// 各页面把本地 selectedDisplayRange 换成指向本 store 的可写 computed，
// KeepAlive 存活的多个页面实例经由同一 ref 自动联动。
const STORAGE_KEY = "gjcxfzksh:display-range";
// 联动改造前各页面的独立 key，仅作首次迁移读取（按运行监测 > 换乘分析优先），不再写入
const LEGACY_KEYS = ["gjcxfzksh:datavisualization:display-range", "gjcxfzksh:transferanalysis:display-range"];

export const DISPLAY_RANGE_ALL = "全市";

function readStored() {
  if (typeof localStorage === "undefined") return DISPLAY_RANGE_ALL;
  try {
    const direct = String(localStorage.getItem(STORAGE_KEY) || "").trim();
    if (direct) return direct;
    for (const key of LEGACY_KEYS) {
      const legacy = String(localStorage.getItem(key) || "").trim();
      if (legacy) return legacy;
    }
  } catch {
    /* 隐私模式等存储不可用场景按全市处理 */
  }
  return DISPLAY_RANGE_ALL;
}

export const useDisplayRangeStore = defineStore("displayRange", () => {
  const selected = ref(readStored());

  function set(name) {
    const next = String(name || "").trim() || DISPLAY_RANGE_ALL;
    if (next === selected.value) return;
    selected.value = next;
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      /* 存储失败不影响本次会话联动 */
    }
  }

  return { selected, set };
});
