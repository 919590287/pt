// 运行监测页各面板组件共享的轻量工具。
// 注意：只放无状态纯函数/单例，避免组件间隐式耦合。
import { ref, watch } from "vue";

// 中文排序用共享 Collator：逐次 localeCompare("zh-CN") 会重复构造 ICU collator，
// 数千站名排序时开销可达几十 ms；模块级单例一次构造复用。
export const zhCollator = new Intl.Collator("zh-CN");
export const compareZh = zhCollator.compare;

// 空闲期执行（浏览器不支持 requestIdleCallback 时退化为宏任务）
export function runWhenIdle(fn, timeout = 3000) {
  if (typeof window !== "undefined" && typeof window.requestIdleCallback === "function") {
    window.requestIdleCallback(fn, { timeout });
    return;
  }
  setTimeout(fn, 0);
}

// 请求取消判定（与 utils/request.js 的取消语义对齐）
export function isCanceledRequest(error) {
  return (
    error?.message === "请求已取消" ||
    error?.message === "canceled" ||
    error?.cause?.message === "canceled" ||
    error?.cause?.code === "ERR_CANCELED" ||
    error?.code === "ERR_CANCELED" ||
    error?.name === "CanceledError" ||
    error?.name === "AbortError"
  );
}

// 高频交互（滑块拖动等）派生防抖 ref：写入 source 即返回，经 delay 后同步到 debounced。
// 用法：const { debounced } = createDebouncedMirror(sourceRef, 180)
export function createDebouncedMirror(source, delay = 180) {
  const debounced = ref(source.value);
  let timer = null;
  const stop = watch(source, (val) => {
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => {
      timer = null;
      // 数组按值比较，避免同值重触发下游
      const a = debounced.value;
      const same = Array.isArray(a) && Array.isArray(val)
        ? a.length === val.length && a.every((x, i) => x === val[i])
        : a === val;
      if (!same) debounced.value = Array.isArray(val) ? val.slice() : val;
    }, delay);
  });
  const cancel = () => {
    if (timer) clearTimeout(timer);
    timer = null;
  };
  const flush = () => {
    cancel();
    debounced.value = Array.isArray(source.value) ? source.value.slice() : source.value;
  };
  return { debounced, cancel, flush, stop };
}
