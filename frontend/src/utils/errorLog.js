// 前端错误环形日志：写入 localStorage（键 gj_error_log），最多保留 50 条。
// 每条形如 { time, type, message, stack(截断500字符), url }。
// 控制台执行 window.__GJ_EXPORT_ERROR_LOG__() 可导出（返回数组并 console.table 展示）。
const STORAGE_KEY = "gj_error_log";
const MAX_ENTRIES = 50;
const MAX_STACK_LENGTH = 500;

function readEntries() {
  if (typeof window === "undefined" || !window.localStorage) return [];
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    const list = raw ? JSON.parse(raw) : [];
    return Array.isArray(list) ? list : [];
  } catch {
    return [];
  }
}

export function appendErrorLog({ type = "error", message = "", stack = "", url = "" } = {}) {
  if (typeof window === "undefined" || !window.localStorage) return;
  try {
    const entries = readEntries();
    entries.push({
      time: new Date().toISOString(),
      type: String(type),
      message: String(message),
      stack: String(stack || "").slice(0, MAX_STACK_LENGTH),
      url: url || (window.location ? window.location.href : ""),
    });
    while (entries.length > MAX_ENTRIES) entries.shift();
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(entries));
  } catch {
    // localStorage 不可用或写入超限时静默降级，环形日志绝不影响主流程
  }
}

export function exportErrorLog() {
  const entries = readEntries();
  if (typeof console !== "undefined" && typeof console.table === "function") {
    console.table(entries);
  }
  return entries;
}

export function installErrorLogExport() {
  if (typeof window !== "undefined") {
    window.__GJ_EXPORT_ERROR_LOG__ = exportErrorLog;
  }
}
