// 配车测算的真实线路参数导入：从真实数据线路 SHP（公交线路站点/线路/routes.shp）的属性里
// 取服务时段、高峰时段与发车间隔，填进测算表单。
//
// 字段口径（与磁盘 SHP 一致）：
//   first/last      服务时段起讫，"HH:MM:SS"，末班可跨零点（24:30:00 / 29:50:00 均真实存在）；
//                   缺失时退 first_dep/last_dep（时刻表首末班，无秒）。
//   am_peak/pm_peak 早/晚高峰窗，"HH:MM-HH:MM"。
//   am_gap/pm_gap   高峰发车间隔（分钟）；off_gap 平峰发车间隔（分钟）。
//   op_time         现状单程运营时间（分钟）：单值表示上下行相同，范围按上行-下行填写。
// 高峰时段和对应间隔同时为空，表示该方向本来就没有这一高峰，按平峰计算；
// 只有两者不一致（或非空值无法解析）时才视为缺项，交由调用方提示人工填写。
//
// 上下行：线路 SHP 没有可用的方向字段（dir 曾经存在但全为 "0"），只能按线路族内的源顺序
// 定为「第一条=上行、第二条=下行」，并把两端点回显给用户，由用户按需交换。

const ENDPOINT_SEPARATOR = /\s*(?:--|—|－|至|到)\s*/;

/** 名称尾部的 "(起点--终点)" 括号，允许站名自带嵌套括号（102路(广钢新城总站(崇文二路)--东山总站)）。 */
function matchingOpenIndex(text) {
  let depth = 0;
  for (let index = text.length - 1; index >= 0; index -= 1) {
    const char = text[index];
    if (char === ")" || char === "）") depth += 1;
    else if (char === "(" || char === "（") {
      depth -= 1;
      if (depth === 0) return index;
    }
  }
  return -1;
}

function looksLikeEndpointText(value) {
  return String(value || "")
    .split(ENDPOINT_SEPARATOR)
    .map((part) => part.trim())
    .filter(Boolean)
    .length >= 2;
}

/**
 * 拆出线路族名与端点文本："101路(机场路总站--海印桥总站)" → { family: "101路", endpointText: "机场路总站--海印桥总站" }。
 * "南沙65路(快)(A--B)" 只剥端点括号，保留 "(快)" 这类走向后缀，快线与普线不会被并成一条线路。
 */
export function splitRouteName(name) {
  let text = String(name || "").trim();
  let endpointText = "";
  while (text.endsWith(")") || text.endsWith("）")) {
    const openIndex = matchingOpenIndex(text);
    if (openIndex <= 0) break;
    const inner = text.slice(openIndex + 1, text.length - 1);
    if (!looksLikeEndpointText(inner)) break;
    endpointText = inner;
    text = text.slice(0, openIndex).trim();
  }
  return { family: text, endpointText };
}

export function routeEndpoints(name) {
  const { endpointText } = splitRouteName(name);
  if (!endpointText) return [];
  return endpointText.split(ENDPOINT_SEPARATOR).map((part) => part.trim()).filter(Boolean);
}

function propertyText(properties, key) {
  const value = properties?.[key];
  if (value === undefined || value === null) return "";
  return String(value).trim();
}

function featureName(properties) {
  return propertyText(properties, "name")
    || propertyText(properties, "line_id")
    || propertyText(properties, "route_id");
}

function normalizeSearchText(value) {
  return String(value || "").toLowerCase().replace(/\s+/g, "");
}

/** 线路族聚合：同名线路的各走向归一条，源顺序即上下行默认顺序。 */
export function buildRouteOptions(collection) {
  const features = Array.isArray(collection?.features) ? collection.features : [];
  const groups = new Map();
  features.forEach((feature) => {
    const properties = feature?.properties || {};
    const name = featureName(properties);
    if (!name) return;
    const { family } = splitRouteName(name);
    const key = family || name;
    if (!groups.has(key)) groups.set(key, { key, name: key, features: [] });
    groups.get(key).features.push(feature);
  });

  return [...groups.values()]
    .map((group) => {
      const first = group.features[0]?.properties || {};
      const endpoints = routeEndpoints(featureName(first));
      const company = propertyText(first, "company");
      return {
        ...group,
        endpointsText: endpoints.length >= 2 ? `${endpoints[0]} → ${endpoints[1]}` : "",
        company,
        searchText: normalizeSearchText([
          group.name,
          ...group.features.map((feature) => featureName(feature?.properties || {})),
          propertyText(first, "line_id"),
          propertyText(first, "route_id"),
          company,
        ].filter(Boolean).join(" ")),
      };
    })
    .sort((left, right) => left.name.localeCompare(right.name, "zh-Hans-CN", { numeric: true }));
}

// 与数据管理页搜索同一评分口径：全等 < 前缀 < 包含（越靠前越优）
function searchScore(text, query) {
  if (!text || !query) return -1;
  if (text === query) return 0;
  if (text.startsWith(query)) return 1;
  const index = text.indexOf(query);
  return index >= 0 ? 2 + index / 1000 : -1;
}

export function searchRouteOptions(options, keyword, limit = 30) {
  const list = Array.isArray(options) ? options : [];
  const query = normalizeSearchText(keyword);
  if (!query) return list.slice(0, limit);
  return list
    .map((option) => ({ option, score: searchScore(option.searchText, query) }))
    .filter((entry) => entry.score >= 0)
    .sort((left, right) => left.score - right.score
      || left.option.name.localeCompare(right.option.name, "zh-Hans-CN", { numeric: true }))
    .slice(0, limit)
    .map((entry) => entry.option);
}

/**
 * "06:00:00" → { time: "06:00", nextDay: false }；"24:30:00" → { time: "00:30", nextDay: true }。
 * 24 时以上是真实数据里的夜班线写法，拆成钟面时刻 + 次日标记，才能既进 input[type=time] 又不丢跨零点信息。
 */
export function parseClockTime(text) {
  const match = String(text || "").trim().match(/^(\d{1,2}):(\d{2})(?::(\d{2}))?$/);
  if (!match) return null;
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  if (!Number.isFinite(hour) || !Number.isFinite(minute) || minute > 59 || hour > 47) return null;
  const nextDay = hour >= 24;
  const clockHour = nextDay ? hour - 24 : hour;
  return { time: `${String(clockHour).padStart(2, "0")}:${String(minute).padStart(2, "0")}`, nextDay };
}

/** "07:00-09:00" → { start: "07:00", end: "09:00" }。高峰窗不支持跨零点，24:00 收口到 23:59。 */
export function parsePeakWindow(text) {
  const parts = String(text || "").trim().split(/\s*[-~—]\s*/);
  if (parts.length !== 2) return null;
  const start = parseClockTime(parts[0]);
  const end = parseClockTime(parts[1]);
  if (!start || !end || start.nextDay) return null;
  return { start: start.time, end: end.nextDay ? "23:59" : end.time, clamped: end.nextDay };
}

export function parseIntervalMinutes(text) {
  const value = Number(String(text || "").trim());
  return Number.isFinite(value) && value >= 1 ? Math.round(value) : null;
}

/** "50" -> [50, 50]；"70-75" / "35~45" -> [70, 75]。 */
export function parseRouteDurations(text) {
  const match = String(text || "").trim().match(
    /^(\d+(?:\.\d+)?)\s*(?:(?:-|~|～|—|–|至)\s*(\d+(?:\.\d+)?))?\s*(?:分钟|分)?$/,
  );
  if (!match) return null;
  const up = Number(match[1]);
  const down = match[2] === undefined ? up : Number(match[2]);
  if (!(up > 0) || !(down > 0)) return null;
  return [up, down];
}

function directionEntry(feature) {
  if (!feature) return null;
  const properties = feature.properties || {};
  const name = featureName(properties);
  const endpoints = routeEndpoints(name);
  return {
    feature,
    properties,
    name,
    endpointsText: endpoints.length >= 2 ? `${endpoints[0]} → ${endpoints[1]}` : name,
  };
}

function serviceWindow(entry) {
  if (!entry) return { start: null, end: null };
  const start = parseClockTime(propertyText(entry.properties, "first"))
    || parseClockTime(propertyText(entry.properties, "first_dep"));
  const end = parseClockTime(propertyText(entry.properties, "last"))
    || parseClockTime(propertyText(entry.properties, "last_dep"));
  return { start, end };
}

const DIRECTION_LABELS = Object.freeze({ up: "上行", down: "下行" });

/**
 * 把线路族的属性翻成表单值。高峰时段与三档间隔都是方向级字段（同一条线上下行常常不同），
 * 逐方向各读各的，不跨方向兜底。
 * @returns {{ values: object, missing: Array<{key: string, label: string, group: string}>, notes: string[], line: object }}
 *          values 只含读到的字段，缺的键值为空串/null，由调用方原样写进表单并高亮 missing。
 */
export function extractRouteFormValues(option, { swapped = false } = {}) {
  const features = Array.isArray(option?.features) ? option.features : [];
  const ordered = swapped ? [features[1], features[0], ...features.slice(2)] : features;
  const entries = { up: directionEntry(ordered[0]), down: directionEntry(ordered[1]) };
  const missing = [];
  const notes = [];
  const values = {};
  let peakClamped = false;

  const durationText = features
    .map((feature) => propertyText(feature?.properties, "op_time"))
    .find(Boolean);
  const routeDurations = parseRouteDurations(durationText);
  const orderedDurations = routeDurations && swapped
    ? [routeDurations[1], routeDurations[0]]
    : routeDurations;
  values.upDuration = orderedDurations?.[0] ?? null;
  values.downDuration = orderedDurations?.[1] ?? null;
  if (!routeDurations) {
    missing.push({ key: "upDuration", label: "单程时间", group: "上行" });
    missing.push({ key: "downDuration", label: "单程时间", group: "下行" });
  }

  Object.entries(entries).forEach(([direction, entry]) => {
    const group = DIRECTION_LABELS[direction];
    const addMissing = (key, label) => missing.push({ key: `${direction}${key}`, label, group });
    const properties = entry?.properties;

    const window = serviceWindow(entry);
    values[`${direction}ServiceStart`] = window.start?.time || "";
    values[`${direction}ServiceEnd`] = window.end?.time || "";
    values[`${direction}ServiceEndNextDay`] = Boolean(window.end?.nextDay);
    if (!entry || !window.start || !window.end) addMissing("Service", "服务时间");

    const amPeakText = propertyText(properties, "am_peak");
    const pmPeakText = propertyText(properties, "pm_peak");
    const amGapText = propertyText(properties, "am_gap");
    const pmGapText = propertyText(properties, "pm_gap");
    const amPeak = parsePeakWindow(amPeakText);
    const pmPeak = parsePeakWindow(pmPeakText);
    values[`${direction}AmStart`] = amPeak?.start || "";
    values[`${direction}AmEnd`] = amPeak?.end || "";
    values[`${direction}PmStart`] = pmPeak?.start || "";
    values[`${direction}PmEnd`] = pmPeak?.end || "";
    peakClamped = peakClamped || Boolean(amPeak?.clamped) || Boolean(pmPeak?.clamped);

    const amGap = parseIntervalMinutes(amGapText);
    const pmGap = parseIntervalMinutes(pmGapText);
    const offGap = parseIntervalMinutes(propertyText(properties, "off_gap"));
    values[`${direction}AmInterval`] = amGap;
    values[`${direction}PmInterval`] = pmGap;
    values[`${direction}OffInterval`] = offGap;
    const amConfigured = Boolean(amPeakText || amGapText);
    const pmConfigured = Boolean(pmPeakText || pmGapText);
    if (!entry) {
      addMissing("AmPeak", "早高峰时段");
      addMissing("PmPeak", "晚高峰时段");
      addMissing("AmInterval", "早高峰间隔");
      addMissing("PmInterval", "晚高峰间隔");
    } else {
      if (amConfigured && !amPeak) addMissing("AmPeak", "早高峰时段");
      if (amConfigured && amGap === null) addMissing("AmInterval", "早高峰间隔");
      if (pmConfigured && !pmPeak) addMissing("PmPeak", "晚高峰时段");
      if (pmConfigured && pmGap === null) addMissing("PmInterval", "晚高峰间隔");
    }
    if (offGap === null) addMissing("OffInterval", "平峰间隔");

    if (entry && !amConfigured) notes.push(`${group}未设置早高峰，该时段按平峰间隔计算。`);
    if (entry && !pmConfigured) notes.push(`${group}未设置晚高峰，该时段按平峰间隔计算。`);
  });

  if (!entries.down) {
    notes.push("真实数据中该线路只有一个走向（多为环线），下行参数需人工填写。");
  }
  if (features.length > 2) {
    notes.push(`真实数据中该线路有 ${features.length} 条走向，已取前两条作为上下行。`);
  }
  if (peakClamped) {
    notes.push("高峰时段在真实数据中写到 24:00，已收口到 23:59。");
  }

  return {
    values,
    missing,
    notes,
    line: {
      name: option?.name || "",
      upLabel: entries.up?.endpointsText || "",
      downLabel: entries.down?.endpointsText || "",
      directionCount: features.length,
      company: propertyText(entries.up?.properties, "company"),
    },
  };
}
