// 公交/地铁制式判别工具：从运行监测 XLZL 组件抽取为共享模块，
// 供线网底图（index.vue 公交/地铁图层切分）与右侧面板（XLZL 线路聚合）使用同一套口径。

export function metroLineNumber(text = "") {
  const raw = String(text || "");
  const chinese = raw.match(/(?:地铁|轨道|线路)?\s*([0-9]{1,2}|[一二三四五六七八九十]{1,4})\s*(?:号线|线)/i);
  const english = raw.match(/(?:metro|subway|mtr)(?:[-_\s]*line)?[-_\s]*([0-9]{1,2})\b|\bline[-_\s]*([0-9]{1,2})\b/i);
  const token = chinese?.[1] || english?.[1] || english?.[2] || "";
  if (!token) return "";
  if (/^\d+$/.test(token)) return String(Number(token));
  const table = {
    一: "1", 二: "2", 三: "3", 四: "4", 五: "5", 六: "6", 七: "7", 八: "8", 九: "9", 十: "10",
    十一: "11", 十二: "12", 十三: "13", 十四: "14", 十五: "15", 十六: "16", 十七: "17", 十八: "18", 十九: "19", 二十: "20",
  };
  return table[token] || "";
}

export function normalizedTransitMode(text = "") {
  const value = String(text || "").toLowerCase();
  if (/subway|metro|mtr|rail|train|地铁|轨道|轻轨|有轨/.test(value)) return "subway";
  if (/bus|公交/.test(value)) return "bus";
  return "";
}

export function declaredTransitMode(line = {}) {
  const ownMode = normalizedTransitMode(line.mode || line.transportMode);
  if (ownMode) return ownMode;
  const routeModes = (Array.isArray(line.routes) ? line.routes : [])
    .map((route) => normalizedTransitMode(route?.mode || route?.transportMode))
    .filter(Boolean);
  if (routeModes.includes("subway")) return "subway";
  if (routeModes.includes("bus")) return "bus";
  return "";
}

export function hasMetroModeKeyword(text = "") {
  return /subway|metro|mtr|rail|train|地铁|轨道|轻轨|有轨/i.test(String(text || ""));
}

export function hasRouteIdMetroKeyword(text = "") {
  return /subway|metro|mtr/i.test(String(text || ""));
}

export function hasBusIdKeyword(text = "") {
  const value = String(text || "").toLowerCase();
  return value.includes("busgtfs")
    || value.includes("bus_gtfs")
    || value.startsWith("bus")
    || value.includes(" bus");
}

// 同一条地铁线的分段/支线后缀：剥离后合并（如 3号线 + 3号线北段、12号线东段 + 12号线西段、14号线 + 14号线知识城线）。
// 城市/制式前缀（佛山、南海、黄埔、海珠、有轨电车…）不在此列，确保跨系统同号线不会被错误合并。
export const METRO_SEGMENT_SUFFIX = /(北延段|南延段|东延段|西延段|北延线|南延线|东延线|西延线|北段|南段|东段|西段|延长线|延长段|知识城支线|知识城线|支线|一期|二期|三期|四期|首期工程|首期|首通段|后通段)/g;
// 规范化后恰为“N号线”（阿拉伯或中文数字）才算“纯地铁线路号”，展示为“地铁N号线”。
export const PURE_METRO_LINE = /^(?:[0-9]{1,2}|[一二三四五六七八九十]{1,4})号线$/;

// 规范化地铁线路名：去空白、去括号备注、剥离同线分段后缀；剥离后为空则回退原名。
export function metroLineCanonicalName(line = {}) {
  const base = String(line.lineName || line.lineId || "")
    .trim()
    .replace(/\s+/g, "")
    .replace(/[（(].*?[）)]/g, "");
  const stripped = base.replace(METRO_SEGMENT_SUFFIX, "");
  return stripped || base;
}

export function isMetroLine(line = {}) {
  const declaredMode = declaredTransitMode(line);
  if (declaredMode === "subway") return true;
  if (declaredMode === "bus") return false;
  const lineText = [line.lineName, line.lineId].filter(Boolean).join(" ");
  const idText = [
    line.lineId,
    ...(Array.isArray(line.routes) ? line.routes.map((route) => route?.routeId) : []),
  ].filter(Boolean).join(" ");
  if (!hasMetroModeKeyword(lineText) && hasBusIdKeyword(idText)) return false;
  if (metroLineNumber(lineText) || hasMetroModeKeyword(lineText)) return true;
  return (Array.isArray(line.routes) ? line.routes : []).some((route) => (
    metroLineNumber([route?.routeName, route?.routeId].filter(Boolean).join(" "))
    || hasRouteIdMetroKeyword(route?.routeId)
  ));
}
