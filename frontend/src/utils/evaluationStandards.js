/**
 * 体检评估指标标准（来源：评估指标.xlsx，2026-07 业务提供）。
 * - dimension: 指标类型（表格首列，同类合并单元格）
 * - shortName: 雷达图轴名（全称过长会互相压字）
 * - standardText: 规范建议值原文（表格展示用）
 * - standard: 解析后的建议值 { kind: 'min'|'max'|'range'|'point', a, b }
 *   kind=min → 值≥a 为优；max → 值≤a 为优；range → a≤值≤b 为优；point → 值≥a 为优（建议型单值默认越高越好）
 * - betterDirection: 'higher'(正向) | 'lower'(负向) | 'range'(区间) | null（null=无建议值，不做优劣判定与归一化）
 * - gzAvg: 广州市平均参考值（取 2023 年列，仅表格展示；雷达图对标的是规范建议标准，不是广州平均）
 * - type: 建议型/目标型（原表分类，仅展示）
 * - modelKey: 后端 /pt/data/evaluation 返回的统计值字段名（null=暂无法统计，显示"暂无数据"）
 * - districtScoped: true=后端按所选行政区统计；其余指标恒为全市口径，不随行政区变化
 */

export const EVALUATION_DIMENSIONS = ["总体水平", "需求强度", "线路效益", "运营服务", "场站设施"];

export const EVALUATION_INDICATORS = [
  {
    key: "czrkmd", dimension: "总体水平", name: "常住人口密度", shortName: "人口密度", unit: "人/km²",
    standardText: "2533", standard: { kind: "point", a: 2533 }, betterDirection: "higher",
    gzAvg: 1208, gzAvg2022: 1158, type: "建议型", modelKey: "czrkmd", districtScoped: true,
  },
  {
    key: "xwmd", dimension: "总体水平", name: "线网密度", shortName: "线网密度", unit: "km/km²",
    standardText: "2.0~2.5", standard: { kind: "range", a: 2.0, b: 2.5 }, betterDirection: "range",
    gzAvg: 1.2, gzAvg2022: 1.1, type: "目标型", modelKey: "gjxwmd", districtScoped: true,
  },
  {
    key: "fgl300", dimension: "总体水平", name: "车站300m人口覆盖率", shortName: "站点覆盖率", unit: "%",
    standardText: "≥50", standard: { kind: "min", a: 50 }, betterDirection: "higher",
    gzAvg: 69, gzAvg2022: 65, type: "目标型", modelKey: "fgl300",
  },
  {
    key: "wrbyl", dimension: "总体水平", name: "万人公共交通车辆保有量", shortName: "万人车辆保有量", unit: "标台/万人",
    standardText: "—", standard: null, betterDirection: null,
    gzAvg: 9.5, gzAvg2022: 9.7, type: "待配置", modelKey: "wrbyl",
  },
  {
    key: "cxfdl", dimension: "总体水平", name: "公共交通机动化出行分担率", shortName: "机动化分担率", unit: "%",
    standardText: "—", standard: null, betterDirection: null,
    gzAvg: 4.0, gzAvg2022: 3.1, type: "待配置", modelKey: "cxfdl",
  },
  {
    key: "pjrzkl", dimension: "总体水平", name: "车均日载客量", shortName: "车均日载客量", unit: "人次/车·日",
    standardText: "—", standard: null, betterDirection: null,
    gzAvg: 82, gzAvg2022: 72, type: "待配置", modelKey: "cjrzkl",
  },
  {
    key: "dbczkl", dimension: "总体水平", name: "单班次载客量", shortName: "单班次载客量", unit: "人次/班",
    standardText: "—", standard: null, betterDirection: null,
    gzAvg: 10, gzAvg2022: 9, type: "待配置", modelKey: "dbczkl",
  },
  {
    key: "rcxcs", dimension: "需求强度", name: "公交人均日出行次数", shortName: "人均日出行", unit: "次/人·日",
    standardText: "0.159", standard: { kind: "point", a: 0.159 }, betterDirection: "higher",
    gzAvg: 0.078, gzAvg2022: 0.069, type: "建议型", modelKey: "rcxcs",
  },
  {
    key: "fzxxs", dimension: "线路效益", name: "线路非直线系数", shortName: "非直线系数", unit: "",
    standardText: "≤1.40", standard: { kind: "max", a: 1.4 }, betterDirection: "lower",
    gzAvg: 1.89, gzAvg2022: 1.85, type: "目标型", modelKey: "xlfzxxs",
  },
  {
    key: "cfxs", dimension: "线路效益", name: "线路重复系数", shortName: "重复系数", unit: "",
    standardText: "1.2~2.5", standard: { kind: "range", a: 1.2, b: 2.5 }, betterDirection: "range",
    gzAvg: 2.5, gzAvg2022: 2.7, type: "目标型", modelKey: "xlcfxs",
  },
  {
    key: "mzl", dimension: "线路效益", name: "线路平均高峰满载率", shortName: "高峰满载率", unit: "%",
    standardText: "36.0", standard: { kind: "point", a: 36.0 }, betterDirection: "higher",
    gzAvg: 25.8, gzAvg2022: 26.7, type: "建议型", modelKey: "xlmzl",
  },
  {
    key: "klqd", dimension: "线路效益", name: "线路客流强度", shortName: "客流强度", unit: "人次/车公里",
    standardText: "1.50", standard: { kind: "point", a: 1.5 }, betterDirection: "higher",
    gzAvg: 0.45, gzAvg2022: 0.46, type: "建议型", modelKey: "xlklqd",
  },
  {
    key: "yxsdb", dimension: "运营服务", name: "公共汽电车与小汽车运行速度比", shortName: "公交小汽车速度比", unit: "",
    standardText: "0.56", standard: { kind: "point", a: 0.56 }, betterDirection: "higher",
    gzAvg: 0.45, gzAvg2022: 0.44, type: "目标型", modelKey: "yxsdb",
  },
  {
    key: "pjhcsj", dimension: "运营服务", name: "平均候车时间（公交）", shortName: "平均候车时间", unit: "min",
    standardText: "10", standard: { kind: "max", a: 10 }, betterDirection: "lower",
    gzAvg: 15, gzAvg2022: 15, type: "建议型", modelKey: "pjhcsj",
  },
  {
    key: "pjhccs", dimension: "运营服务", name: "平均换乘次数（公交）", shortName: "平均换乘次数", unit: "次",
    standardText: "-", standard: null, betterDirection: null,
    gzAvg: null, gzAvg2022: null, type: "建议型", modelKey: "pjhccs",
  },
  {
    key: "gjjbbl", dimension: "运营服务", name: "公交-轨道接驳比例", shortName: "公交轨道接驳比", unit: "%",
    standardText: "-", standard: null, betterDirection: null,
    gzAvg: null, gzAvg2022: null, type: "建议型", modelKey: "gjjbbl",
  },
  {
    key: "cjczmj", dimension: "场站设施", name: "车均场站面积", shortName: "车均场站面积", unit: "m²/标台",
    standardText: "150~200", standard: { kind: "range", a: 150, b: 200 }, betterDirection: "range",
    gzAvg: 91, gzAvg2022: 94, type: "目标型", modelKey: "cjczmj",
  },
];

/**
 * 可对标指标：只有带规范建议标准的指标能与基准环对比，
 * 无建议值的指标（standardText 为 — / -）没有归一化基准，不参与维度得分。
 */
export const RADAR_INDICATORS = EVALUATION_INDICATORS.filter((item) => item.standard != null);

/** 归一化基准环：规范建议标准恒为 1.0，模型值 >1 表示优于标准。 */
export const RADAR_STANDARD_SCORE = 1;
/** 归一化上限：优于标准 20% 以上一律贴边，避免单个超标指标把整张图压扁。 */
export const RADAR_MAX_SCORE = 1.2;

const DIRECTION_TEXT = {
  higher: { mark: "↑", label: "正向指标（越大越优）" },
  lower: { mark: "↓", label: "负向指标（越小越优）" },
  range: { mark: "↔", label: "区间指标（落入区间为优）" },
};

/** 指标正负向说明（表格角标与雷达图 tooltip 共用）。无建议值返回 null。 */
export function directionInfo(indicator) {
  return DIRECTION_TEXT[indicator?.betterDirection] || null;
}

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
 * 归一化（雷达图用）：以规范建议标准为基准 1.0，输出 [0, 1.2]。
 * 正负向差异在这里统一吃掉，画到图上一律"离圆心越远越优"：
 * - higher(正向)：value/基准，值越大越靠外；
 * - lower(负向)：基准/value，值越小越靠外；
 * - range(区间)：落在区间内记 1，区间外按与最近边界的比值向内收。
 * 无建议值或无统计值 → null（该轴不画点，轴名标注"暂无数据"）。
 */
export function normalizeIndicator(value, indicator) {
  const std = indicator.standard;
  if (std == null || value == null || !Number.isFinite(Number(value))) return null;
  const v = Number(value);
  const clamp = (x) => Math.max(0, Math.min(RADAR_MAX_SCORE, x));
  switch (std.kind) {
    case "min": return clamp(v / std.a);
    case "max": return v <= 0 ? RADAR_MAX_SCORE : clamp(std.a / v);
    case "range":
      if (v >= std.a && v <= std.b) return RADAR_STANDARD_SCORE;
      return v < std.a ? clamp(v / std.a) : clamp(std.b / v);
    case "point":
      if (indicator.betterDirection === "lower") return v <= 0 ? RADAR_MAX_SCORE : clamp(std.a / v);
      return clamp(v / std.a);
    default: return null;
  }
}

/**
 * 五维雷达图逐轴得分：每个指标类型一根轴，五类恒定全画。
 * 类内先按 normalizeIndicator 抹平正负向（一律"越大越优"）再取均值，
 * 不同方向的指标才能放进同一个平均值里。
 * 类内一项也统计不到时 score = null（画成缺口 + 轴名标"暂无数据"），
 * 不记 0——0 是"极差"，与"没统计到"是两回事。
 * @param valueOf (indicator) => number|null 取该指标模型统计值
 */
export function dimensionRadarScores(valueOf) {
  return EVALUATION_DIMENSIONS.map((dimension) => {
    const members = RADAR_INDICATORS.filter((item) => item.dimension === dimension);
    const scored = members
      .map((indicator) => ({ indicator, score: normalizeIndicator(valueOf(indicator), indicator) }))
      .filter((item) => item.score != null);
    const mean = scored.length
      ? scored.reduce((sum, item) => sum + item.score, 0) / scored.length
      : null;
    return {
      dimension,
      score: mean == null ? null : Math.round(mean * 1000) / 1000,
      scored,
      // 分母是"本类有规范建议标准的指标数"，不含无建议值的指标
      comparable: members.length,
    };
  });
}
