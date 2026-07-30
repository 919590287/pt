// MATSim 的 activity type 是模型自定义自由文本。前端把常见中英文同义词归并成
// 中文出行目的；未识别值统一进入“其他”，不把原始英文直接混排到业务界面。
// home 表示本次行程的终点活动是回家，并非“居住”这种人口属性。
const ACTIVITY_CATEGORIES = [
  { key: "return-home", pattern: /^home(?:[_-].*)?$|residential|住宅|回家|返家|回住所|家$/i, label: "返家", color: "#0b91b7" },
  { key: "commute", pattern: /^work(?:[_-].*)?$|workplace|office|job|business|工作|上班|就业|通勤/i, label: "通勤", color: "#0071e3" },
  { key: "education", pattern: /school|educ|university|college|小学|中学|学校|教育|大学/i, label: "教育", color: "#2f75d6" },
  { key: "shopping", pattern: /shop|mall|market|购物|买|商场|市场/i, label: "购物", color: "#7c3aed" },
  { key: "leisure", pattern: /leisure|recreation|social|sport|entertain|gym|fitness|休闲|娱乐|运动|健身|社交/i, label: "休闲", color: "#1a8a3f" },
  { key: "dining", pattern: /eat|dining|restaurant|餐|饭|food/i, label: "餐饮", color: "#b06a00" },
  { key: "medical", pattern: /medical|hospital|clinic|health|医院|医疗|就医/i, label: "医疗", color: "#d12c59" },
  { key: "transfer", pattern: /airport|railway|station|transport|terminal|枢纽|机场|火车|高铁|客运/i, label: "交通接驳", color: "#0f766e" },
  { key: "escort", pattern: /pickup|dropoff|escort|接送/i, label: "接送", color: "#4f46e5" },
];
const UNKNOWN_ACTIVITY = { key: "other-purpose", label: "其他", color: "#94a3b8" };

const ATTRIBUTE_ITEMS = [
  { key: "student", label: "学生", color: "#2f75d6" },
  { key: "elderly", label: "老人", color: "#b06a00" },
];

const CARD_GROUP_META = {
  student: { label: "学生票卡", color: "#2f75d6" },
  elderly: { label: "老年票卡", color: "#b06a00" },
  disability_or_concession: { label: "优抚/残疾票卡", color: "#d12c59" },
  general_or_unknown: { label: "一般/未知票卡", color: "#64748b" },
};

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

function activityCategory(key, fallback = "") {
  const raw = normalizeText(fallback || key);
  if (!raw) return UNKNOWN_ACTIVITY;
  for (const category of ACTIVITY_CATEGORIES) {
    category.pattern.lastIndex = 0;
    if (category.pattern.test(raw)) return category;
  }
  return UNKNOWN_ACTIVITY;
}

function entryFromActivityItem(item, total) {
  if (typeof item === "string") {
    const rawKey = normalizeText(item).toLowerCase();
    const category = activityCategory(rawKey);
    return rawKey
      ? { key: category.key, label: category.label, count: 1, value: total > 0 ? 100 / total : 0, color: category.color }
      : null;
  }
  if (!item || typeof item !== "object") return null;
  const rawKey = normalizeText(item.key ?? item.type ?? item.name ?? item.label).toLowerCase();
  if (!rawKey) return null;
  const category = activityCategory(rawKey, item.label);
  const count = Number.isFinite(Number(item.count)) ? Number(item.count) : null;
  const ratioSource = item.ratio ?? item.percent ?? item.value;
  const ratio = Number.isFinite(Number(ratioSource))
    ? Number(ratioSource)
    : count !== null && total > 0 ? (count * 100) / total : 0;
  return {
    key: category.key,
    label: category.label,
    count,
    value: clampPercent(ratio),
    color: item.color || category.color,
  };
}

function activityEntriesFromObject(activityMap, total, valueMode = "count") {
  return Object.entries(activityMap || {}).map(([key, rawValue]) => {
    if (rawValue && typeof rawValue === "object") {
      return entryFromActivityItem({ key, ...rawValue }, total);
    }
    const count = Number(rawValue);
    const value = valueMode === "ratio"
      ? count
      : total > 0 && Number.isFinite(count) ? (count * 100) / total : count;
    return entryFromActivityItem({
      key,
      count: valueMode === "count" && Number.isFinite(count) ? count : null,
      ratio: value,
    }, total);
  });
}

function activityItemsFromDemographics(demo, total) {
  const source = demo.activityTypes || demo.activities;
  const ratioSource = demo.activityTypeRatios || demo.activityRatios;
  const countSource = demo.activityCounts;
  const entries = [
    ...(Array.isArray(source)
      ? source.map((item) => entryFromActivityItem(item, total))
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

function cardPassengerGroup(demographics = {}, total = 0) {
  const source = demographics?.passengerGroups;
  if (!Array.isArray(source) || !source.length) return null;
  const items = source.map((item, index) => {
    const key = normalizeText(item?.key || `card-group-${index}`);
    const meta = CARD_GROUP_META[key] || {};
    const count = Math.max(0, toFiniteNumber(item?.count, 0));
    const ratio = item?.ratio ?? item?.percent ?? item?.value;
    return {
      key,
      label: normalizeText(item?.label) || meta.label || key,
      color: item?.color || meta.color || "#94a3b8",
      count,
      value: Number.isFinite(Number(ratio)) ? clampPercent(ratio) : total > 0 ? (count * 100) / total : 0,
    };
  }).filter((item) => item.count > 0 || item.value > 0);
  if (!items.length) return null;
  return {
    key: "card-passenger-groups",
    title: "票卡客群",
    sumLabel: "合计 100%",
    items: normalizeDisplayPercents(items),
  };
}

export function passengerProfileRiderCount(demographics = {}) {
  const direct = Number(demographics?.riderCount);
  if (!Number.isFinite(direct) || direct < 0) {
    throw new Error("客流画像缺少有效的 riderCount");
  }
  return direct;
}

export function buildPassengerProfileGroups(demographics = {}) {
  const total = passengerProfileRiderCount(demographics);
  if (total <= 0) return [];

  const groups = [];
  const cardGroup = cardPassengerGroup(demographics, total);
  if (cardGroup) groups.push(cardGroup);
  // 出行目的按“各类终点活动占全部目的活动的份额”统计（优先按 count，无 count 按占比值归一化），
  // 保证各类相加恰为 100%，而不是逐类除以样本人数的“出现率”（一人多活动会使总和超 100%）
  let activityItems = activityItemsFromDemographics(demographics, total);
  if (activityItems.length) {
    if (demographics.activitySource && demographics.activitySource !== "trip-purpose") {
      throw new Error(`客流画像活动口径非法: ${demographics.activitySource}`);
    }
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
      title: "出行目的",
      sumLabel: "合计 100%",
      items: activityItems,
    });
  }

  if (!cardGroup) {
    const attribute = fixedPercentGroup(demographics, "attribute", "出行者属性", ATTRIBUTE_ITEMS);
    if (attribute) groups.push(attribute);
  }
  return groups;
}
