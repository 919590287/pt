const ACTIVITY_COLORS = [
  "#0071e3",
  "#1a8a3f",
  "#7c3aed",
  "#b06a00",
  "#0b91b7",
  "#d12c59",
  "#475569",
  "#0f766e",
  "#4f46e5",
  "#a85512",
];

const ACTIVITY_LABELS = [
  [/^home$|residential|居住|住宅|回家|家/i, "居住"],
  [/^work$|workplace|office|job|business|工作|上班|就业|通勤/i, "工作"],
  [/school|educ|university|college|小学|中学|学校|教育|大学/i, "教育"],
  [/shop|mall|market|购物|买|商场|市场/i, "购物"],
  [/leisure|recreation|social|sport|entertain|休闲|娱乐|运动|社交/i, "休闲"],
  [/eat|dining|餐|饭|food/i, "餐饮"],
  [/medical|hospital|clinic|health|医院|医疗|就医/i, "医疗"],
  [/airport|railway|station|transport|枢纽|机场|火车|高铁|客运/i, "交通接驳"],
  [/pickup|dropoff|接送/i, "接送"],
];

const PURPOSE_ITEMS = [
  { key: "commuter", label: "通勤", color: "#0071e3" },
  { key: "shopping", label: "购物", color: "#7c3aed" },
  { key: "leisure", label: "休闲", color: "#1a8a3f" },
];

const ATTRIBUTE_ITEMS = [
  { key: "student", label: "学生", color: "#2f75d6" },
  { key: "elderly", label: "老人", color: "#b06a00" },
];

const OTHER_ITEM = { label: "其他", color: "#94a3b8" };

function toFiniteNumber(value, fallback = 0) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function clampPercent(value) {
  return Math.max(0, Math.min(100, toFiniteNumber(value, 0)));
}

function normalizeText(value) {
  return String(value ?? "").trim();
}

function displayActivityLabel(key, fallback = "") {
  const raw = normalizeText(fallback || key);
  if (!raw) return "未知活动";
  for (const [pattern, label] of ACTIVITY_LABELS) {
    pattern.lastIndex = 0;
    if (pattern.test(raw)) return label;
  }
  return raw.replace(/[_-]+/g, " ");
}

function colorForKey(key, index = 0) {
  const text = normalizeText(key);
  let hash = 0;
  for (let i = 0; i < text.length; i += 1) {
    hash = (hash * 31 + text.charCodeAt(i)) >>> 0;
  }
  return ACTIVITY_COLORS[(hash + index) % ACTIVITY_COLORS.length];
}

function entryFromActivityItem(item, index, total) {
  if (typeof item === "string") {
    const key = normalizeText(item).toLowerCase();
    return key
      ? { key, label: displayActivityLabel(key), count: 1, value: total > 0 ? 100 / total : 0, color: colorForKey(key, index) }
      : null;
  }
  if (!item || typeof item !== "object") return null;
  const key = normalizeText(item.key ?? item.type ?? item.name ?? item.label).toLowerCase();
  if (!key) return null;
  const count = Number.isFinite(Number(item.count)) ? Number(item.count) : null;
  const ratioSource = item.ratio ?? item.percent ?? item.value;
  const ratio = Number.isFinite(Number(ratioSource))
    ? Number(ratioSource)
    : count !== null && total > 0 ? (count * 100) / total : 0;
  return {
    key,
    label: normalizeText(item.label) || displayActivityLabel(key),
    count,
    value: clampPercent(ratio),
    color: item.color || colorForKey(key, index),
  };
}

function activityEntriesFromObject(activityMap, total, valueMode = "count") {
  return Object.entries(activityMap || {}).map(([key, rawValue], index) => {
    if (rawValue && typeof rawValue === "object") {
      return entryFromActivityItem({ key, ...rawValue }, index, total);
    }
    const count = Number(rawValue);
    const value = valueMode === "ratio"
      ? count
      : total > 0 && Number.isFinite(count) ? (count * 100) / total : count;
    return entryFromActivityItem({
      key,
      count: valueMode === "count" && Number.isFinite(count) ? count : null,
      ratio: value,
    }, index, total);
  });
}

function activityItemsFromDemographics(demo, total) {
  const source = demo.activityTypes || demo.activities;
  const ratioSource = demo.activityTypeRatios || demo.activityRatios;
  const countSource = demo.activityCounts;
  const entries = [
    ...(Array.isArray(source)
      ? source.map((item, index) => entryFromActivityItem(item, index, total))
      : source && typeof source === "object" ? activityEntriesFromObject(source, total, "count") : []),
    ...(ratioSource && typeof ratioSource === "object" ? activityEntriesFromObject(ratioSource, total, "ratio") : []),
    ...(countSource && typeof countSource === "object" ? activityEntriesFromObject(countSource, total, "count") : []),
  ];

  const merged = new Map();
  entries.filter(Boolean).forEach((entry) => {
    const current = merged.get(entry.key);
    if (!current) {
      merged.set(entry.key, entry);
      return;
    }
    current.count = current.count !== null || entry.count !== null
      ? toFiniteNumber(current.count, 0) + toFiniteNumber(entry.count, 0)
      : null;
    current.value = clampPercent(current.value + entry.value);
  });

  return Array.from(merged.values())
    .filter((item) => item.value > 0 || toFiniteNumber(item.count, 0) > 0)
    .sort((left, right) => {
      const countCompare = toFiniteNumber(right.count, -1) - toFiniteNumber(left.count, -1);
      return countCompare !== 0 ? countCompare : right.value - left.value || left.label.localeCompare(right.label, "zh-CN");
    });
}

function normalizeDisplayPercents(items = []) {
  if (!items.length) return [];
  const tenths = items.map((item) => Math.max(0, Math.round(clampPercent(item.value) * 10)));
  let delta = 1000 - tenths.reduce((sum, value) => sum + value, 0);
  while (delta !== 0) {
    if (delta > 0) {
      const index = tenths.reduce((best, value, current) => (value < tenths[best] ? current : best), 0);
      tenths[index] += 1;
      delta -= 1;
      continue;
    }
    const index = tenths.reduce((best, value, current) => (value > tenths[best] ? current : best), 0);
    if (tenths[index] <= 0) break;
    tenths[index] -= 1;
    delta += 1;
  }
  return items.map((item, index) => ({ ...item, value: tenths[index] / 10 }));
}

function fixedPercentGroup(demo, key, title, definitions) {
  let items = definitions
    .filter((item) => Object.prototype.hasOwnProperty.call(demo, item.key))
    .map((item) => ({ ...item, value: clampPercent(demo[item.key]) }));
  if (!items.length) return null;
  let known = items.reduce((sum, item) => sum + item.value, 0);
  if (known > 100) {
    items = items.map((item) => ({ ...item, value: (item.value * 100) / known }));
    known = 100;
  }
  items.push({ ...OTHER_ITEM, key: `${key}-other`, value: Math.max(0, 100 - known) });
  return { key, title, sumLabel: "合计 100%", items: normalizeDisplayPercents(items) };
}

// 样本量优先取 riderCount；缺失/为 0 时回退到"各活动计数之和"，
// 避免因后端某些口径未下发 riderCount 就让客流画像整体空白（原先 total<=0 直接返回空）。
function activityCountSum(demographics = {}) {
  const source = demographics?.activityTypes || demographics?.activities;
  if (Array.isArray(source)) {
    return source.reduce((sum, item) => sum + Math.max(0, toFiniteNumber(item?.count, 0)), 0);
  }
  if (source && typeof source === "object") {
    return Object.values(source).reduce((sum, value) => {
      const count = value && typeof value === "object" ? value.count : value;
      return sum + Math.max(0, toFiniteNumber(count, 0));
    }, 0);
  }
  return 0;
}

export function passengerProfileRiderCount(demographics = {}) {
  const direct = toFiniteNumber(demographics?.riderCount, 0);
  if (direct > 0) return direct;
  return activityCountSum(demographics);
}

export function buildPassengerProfileGroups(demographics = {}) {
  const total = passengerProfileRiderCount(demographics);
  if (total <= 0) return [];

  const groups = [];
  // 出行活动按“各类活动占全部活动的份额”统计（优先按 count，无 count 按占比值归一化），
  // 保证各类相加恰为 100%，而不是逐类除以样本人数的“出现率”（一人多活动会使总和超 100%）
  let activityItems = activityItemsFromDemographics(demographics, total);
  if (activityItems.length) {
    const countSum = activityItems.reduce((sum, item) => sum + Math.max(0, toFiniteNumber(item.count, 0)), 0);
    const valueSum = activityItems.reduce((sum, item) => sum + Math.max(0, toFiniteNumber(item.value, 0)), 0);
    const useCount = countSum > 0;
    const base = useCount ? countSum : valueSum;
    if (base > 0) {
      activityItems = normalizeDisplayPercents(activityItems.map((item) => ({
        ...item,
        value: (Math.max(0, toFiniteNumber(useCount ? item.count : item.value, 0)) * 100) / base,
      })));
    }
    groups.push({
      key: "activity-types",
      title: "出行活动",
      sumLabel: "合计 100%",
      items: activityItems,
    });
  } else {
    const purpose = fixedPercentGroup(demographics, "purpose", "出行目的", PURPOSE_ITEMS);
    if (purpose) groups.push(purpose);
  }

  const attribute = fixedPercentGroup(demographics, "attribute", "出行者属性", ATTRIBUTE_ITEMS);
  if (attribute) groups.push(attribute);
  return groups;
}
