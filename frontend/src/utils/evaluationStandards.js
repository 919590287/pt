/**
 * 体检评估指标标准（来源：评估指标.xlsx，2026-07 业务提供）。
 * - dimension: 评估维度（雷达图五维）
 * - standardText: 规范建议值原文（表格展示用）
 * - standard: 解析后的建议值 { kind: 'min'|'max'|'range'|'point', a, b }
 *   kind=min → 值≥a 为优；max → 值≤a 为优；range → a≤值≤b 为优；point → 值≥a 为优（建议型单值默认越高越好）
 * - betterDirection: 'higher' | 'lower' | 'range' | null（null=无建议值，不做优劣判定与归一化）
 * - gzAvg: 广州市平均参考值（取 2023 年列）
 * - type: 建议型/目标型（原表分类，仅展示）
 * - modelKey: 后端 /pt/data/evaluation 返回的统计值字段名（null=暂无法统计，显示"暂无数据"）
 */

export const EVALUATION_DIMENSIONS = ["总体水平", "需求强度", "线路效益", "运营服务", "场站设施"];

export const EVALUATION_INDICATORS = [
  {
    key: "czrkmd", dimension: "总体水平", name: "常住人口密度", unit: "人/km²",
    standardText: "2533", standard: { kind: "point", a: 2533 }, betterDirection: "higher",
    gzAvg: 1208, gzAvg2022: 1158, type: "建议型", modelKey: "czrkmd",
  },
  {
    key: "xwmd", dimension: "总体水平", name: "线网密度", unit: "km/km²",
    standardText: "2.0~2.5", standard: { kind: "range", a: 2.0, b: 2.5 }, betterDirection: "range",
    gzAvg: 1.2, gzAvg2022: 1.1, type: "目标型", modelKey: "gjxwmd",
  },
  {
    key: "fgl300", dimension: "总体水平", name: "车站300m人口覆盖率", unit: "%",
    standardText: "≥50", standard: { kind: "min", a: 50 }, betterDirection: "higher",
    gzAvg: 69, gzAvg2022: 65, type: "目标型", modelKey: "fgl300",
  },
  {
    key: "wrbyl", dimension: "总体水平", name: "万人保有量", unit: "标台/万人",
    standardText: "≥10.0", standard: { kind: "min", a: 10.0 }, betterDirection: "higher",
    gzAvg: 9.5, gzAvg2022: 9.7, type: "目标型", modelKey: "wrbyl",
  },
  {
    key: "cxfdl", dimension: "总体水平", name: "出行分担率", unit: "%",
    standardText: "6.5", standard: { kind: "point", a: 6.5 }, betterDirection: "higher",
    gzAvg: 4.0, gzAvg2022: 3.1, type: "建议型", modelKey: "cxfdl",
  },
  {
    key: "pjrzkl", dimension: "总体水平", name: "平均日载客量", unit: "人次/d",
    standardText: "≥300", standard: { kind: "min", a: 300 }, betterDirection: "higher",
    gzAvg: 82, gzAvg2022: 72, type: "建议型", modelKey: "cjrzkl",
  },
  {
    key: "dbczkl", dimension: "总体水平", name: "单班次载客量", unit: "人次/班",
    standardText: "25", standard: { kind: "point", a: 25 }, betterDirection: "higher",
    gzAvg: 10, gzAvg2022: 9, type: "建议型", modelKey: "dbczkl",
  },
  {
    key: "rcxcs", dimension: "需求强度", name: "日出行次数", unit: "次/人/d",
    standardText: "0.159", standard: { kind: "point", a: 0.159 }, betterDirection: "higher",
    gzAvg: 0.078, gzAvg2022: 0.069, type: "建议型", modelKey: "rcxcs",
  },
  {
    key: "fzxxs", dimension: "线路效益", name: "线路非直线系数", unit: "",
    standardText: "≤1.40", standard: { kind: "max", a: 1.4 }, betterDirection: "lower",
    gzAvg: 1.89, gzAvg2022: 1.85, type: "目标型", modelKey: "xlfzxxs",
  },
  {
    key: "cfxs", dimension: "线路效益", name: "线路重复系数", unit: "",
    standardText: "1.2~2.5", standard: { kind: "range", a: 1.2, b: 2.5 }, betterDirection: "range",
    gzAvg: 2.5, gzAvg2022: 2.7, type: "目标型", modelKey: "xlcfxs",
  },
  {
    key: "mzl", dimension: "线路效益", name: "线路满载率", unit: "%",
    standardText: "36.0", standard: { kind: "point", a: 36.0 }, betterDirection: "higher",
    gzAvg: 25.8, gzAvg2022: 26.7, type: "建议型", modelKey: "xlmzl",
  },
  {
    key: "klqd", dimension: "线路效益", name: "线路客流强度", unit: "人次/km",
    standardText: "1.50", standard: { kind: "point", a: 1.5 }, betterDirection: "higher",
    gzAvg: 0.45, gzAvg2022: 0.46, type: "建议型", modelKey: "xlklqd",
  },
  {
    key: "yxsdb", dimension: "运营服务", name: "公共汽电车与小汽车运行速度比", unit: "",
    standardText: "0.56", standard: { kind: "point", a: 0.56 }, betterDirection: "higher",
    gzAvg: 0.45, gzAvg2022: 0.44, type: "目标型", modelKey: "yxsdb",
  },
  {
    key: "pjhcsj", dimension: "运营服务", name: "平均候车时间", unit: "min",
    standardText: "10", standard: { kind: "max", a: 10 }, betterDirection: "lower",
    gzAvg: 15, gzAvg2022: 15, type: "建议型", modelKey: "pjhcsj",
  },
  {
    key: "pjhccs", dimension: "运营服务", name: "平均换乘次数", unit: "次",
    standardText: "-", standard: null, betterDirection: null,
    gzAvg: null, gzAvg2022: null, type: "建议型", modelKey: "pjhccs",
  },
  {
    key: "gjjbbl", dimension: "运营服务", name: "公交-轨道接驳比例", unit: "%",
    standardText: "-", standard: null, betterDirection: null,
    gzAvg: null, gzAvg2022: null, type: "建议型", modelKey: "gjjbbl",
  },
  {
    key: "cjczmj", dimension: "场站设施", name: "车均场站面积", unit: "m²/标台",
    standardText: "150~200", standard: { kind: "range", a: 150, b: 200 }, betterDirection: "range",
    gzAvg: 91, gzAvg2022: 94, type: "目标型", modelKey: null,
  },
];

/**
 * 优劣判定：模型统计值是否优于建议值。
 * @returns true=优于(绿+) / false=劣于(红-) / null=无法判定(无建议值或无统计值)
 */
export function isBetterThanStandard(value, indicator) {
  const std = indicator.standard;
  if (std == null || value == null || !Number.isFinite(Number(value))) return null;
  const v = Number(value);
  switch (std.kind) {
    case "min": return v >= std.a;
    case "max": return v <= std.a;
    case "range": return v >= std.a && v <= std.b;
    case "point":
      return indicator.betterDirection === "lower" ? v <= std.a : v >= std.a;
    default: return null;
  }
}

/**
 * 归一化（雷达图用）：以建议值为基准 1.0，输出 [0, 1.2]。
 * - higher: value/基准；lower: 基准/value；range: 区间内=1，区间外按靠近程度衰减。
 * 无建议值或无值 → null（该指标不参与该维度均值）。
 */
export function normalizeIndicator(value, indicator) {
  const std = indicator.standard;
  if (std == null || value == null || !Number.isFinite(Number(value))) return null;
  const v = Number(value);
  const clamp = (x) => Math.max(0, Math.min(1.2, x));
  switch (std.kind) {
    case "min": return clamp(v / std.a);
    case "max": return v <= 0 ? 1.2 : clamp(std.a / v);
    case "range":
      if (v >= std.a && v <= std.b) return 1;
      return v < std.a ? clamp(v / std.a) : clamp(std.b / v);
    case "point":
      if (indicator.betterDirection === "lower") return v <= 0 ? 1.2 : clamp(std.a / v);
      return clamp(v / std.a);
    default: return null;
  }
}

/**
 * 按维度聚合归一化得分（成员指标归一化后取均值）。
 * @param valueOf (indicator) => number|null 取该指标数值的函数
 * @returns { 维度名: 0~1.2|0 } 五个维度都返回（无可用指标的维度记 0）
 */
export function dimensionScores(valueOf) {
  const result = {};
  for (const dim of EVALUATION_DIMENSIONS) {
    const scores = EVALUATION_INDICATORS
      .filter((item) => item.dimension === dim)
      .map((item) => normalizeIndicator(valueOf(item), item))
      .filter((score) => score != null);
    result[dim] = scores.length
      ? scores.reduce((sum, score) => sum + score, 0) / scores.length
      : 0;
  }
  return result;
}
