import { describe, expect, it } from "vitest";
import {
  EVALUATION_DIMENSIONS,
  EVALUATION_INDICATORS,
  RADAR_INDICATORS,
  RADAR_MAX_SCORE,
  RADAR_STANDARD_SCORE,
  dimensionRadarScores,
  directionInfo,
  isBetterThanStandard,
  normalizeIndicator,
} from "./evaluationStandards.js";

const byKey = (key) => EVALUATION_INDICATORS.find((item) => item.key === key);

describe("体检指标元数据", () => {
  it("每个指标的 dimension 都在五类之内（表格首列合并单元格靠它分组）", () => {
    EVALUATION_INDICATORS.forEach((item) => {
      expect(EVALUATION_DIMENSIONS).toContain(item.dimension);
    });
  });

  it("每个指标都有雷达轴用的短名", () => {
    EVALUATION_INDICATORS.forEach((item) => {
      expect(item.shortName).toBeTruthy();
      expect(item.shortName.length).toBeLessThanOrEqual(8);
    });
  });

  it("可对标指标集合等于有规范建议标准的指标", () => {
    expect(RADAR_INDICATORS.every((item) => item.standard != null)).toBe(true);
    expect(RADAR_INDICATORS).toHaveLength(
      EVALUATION_INDICATORS.filter((item) => item.standard != null).length,
    );
  });

  it("五类各自至少有一项可对标指标，五根轴才都可能出分", () => {
    EVALUATION_DIMENSIONS.forEach((dimension) => {
      expect(RADAR_INDICATORS.filter((item) => item.dimension === dimension).length)
        .toBeGreaterThan(0);
    });
  });

  it("有标准的指标必须声明正负向，否则归一化方向无从判断", () => {
    RADAR_INDICATORS.forEach((item) => {
      expect(["higher", "lower", "range"]).toContain(item.betterDirection);
      expect(directionInfo(item)).not.toBeNull();
    });
  });

  it("只有常住人口密度与线网密度是行政区口径（其余为全市，表脚注据此措辞）", () => {
    expect(EVALUATION_INDICATORS.filter((item) => item.districtScoped).map((item) => item.key))
      .toEqual(["czrkmd", "xwmd"]);
  });
});

describe("归一化把正负向统一成越靠外越优", () => {
  it("正向指标：值越大分越高，达标即 ≥ 基准环", () => {
    const fgl300 = byKey("fgl300"); // ≥50，正向
    expect(normalizeIndicator(25, fgl300)).toBeCloseTo(0.5, 5);
    expect(normalizeIndicator(50, fgl300)).toBe(RADAR_STANDARD_SCORE);
    expect(normalizeIndicator(60, fgl300)).toBeGreaterThan(RADAR_STANDARD_SCORE);
  });

  it("负向指标：值越小分越高，超标落到基准环以内", () => {
    const fzxxs = byKey("fzxxs"); // ≤1.40，负向
    expect(normalizeIndicator(1.4, fzxxs)).toBe(RADAR_STANDARD_SCORE);
    expect(normalizeIndicator(1.75, fzxxs)).toBeCloseTo(0.8, 5);
    expect(normalizeIndicator(1.0, fzxxs)).toBeGreaterThan(RADAR_STANDARD_SCORE);
  });

  it("负向的 point 型（候车时间）同样按越小越优处理", () => {
    const pjhcsj = byKey("pjhcsj"); // 10 min，负向
    expect(normalizeIndicator(10, pjhcsj)).toBe(RADAR_STANDARD_SCORE);
    expect(normalizeIndicator(20, pjhcsj)).toBeCloseTo(0.5, 5);
    expect(normalizeIndicator(5, pjhcsj)).toBeGreaterThan(RADAR_STANDARD_SCORE);
  });

  it("区间指标：落入区间记满分，两侧都往里收", () => {
    const xwmd = byKey("xwmd"); // 2.0~2.5
    expect(normalizeIndicator(2.0, xwmd)).toBe(RADAR_STANDARD_SCORE);
    expect(normalizeIndicator(2.3, xwmd)).toBe(RADAR_STANDARD_SCORE);
    expect(normalizeIndicator(1.0, xwmd)).toBeCloseTo(0.5, 5);
    expect(normalizeIndicator(5.0, xwmd)).toBeCloseTo(0.5, 5);
  });

  it("分值封顶在 1.2，单个远超标指标不会把整张雷达图压扁", () => {
    const fgl300 = byKey("fgl300");
    expect(normalizeIndicator(9999, fgl300)).toBe(RADAR_MAX_SCORE);
    expect(normalizeIndicator(0.0001, byKey("pjhcsj"))).toBe(RADAR_MAX_SCORE);
  });

  it("无标准或无统计值返回 null（该指标不上雷达图）", () => {
    expect(normalizeIndicator(3.2, byKey("wrbyl"))).toBeNull();
    expect(normalizeIndicator(null, byKey("fgl300"))).toBeNull();
    expect(normalizeIndicator(Number.NaN, byKey("fgl300"))).toBeNull();
  });
});

describe("五维雷达图逐轴得分", () => {
  it("恒定输出五根轴，顺序与指标类型一致（一项统计不到的类也保留）", () => {
    const result = dimensionRadarScores(() => null);
    expect(result.map((item) => item.dimension)).toEqual(EVALUATION_DIMENSIONS);
    expect(result.every((item) => item.score === null)).toBe(true);
  });

  it("类内取归一化后的均值，正负向指标可以放进同一个平均值", () => {
    // 线路效益：非直线系数(负向)、重复系数(区间)、高峰满载率(正向)、客流强度(正向)
    const values = { xlfzxxs: 1.4, xlcfxs: 2.0, xlmzl: 18, xlklqd: 1.5 };
    const line = dimensionRadarScores((indicator) => values[indicator.modelKey] ?? null)
      .find((item) => item.dimension === "线路效益");
    // 1（达标）+ 1（区间内）+ 0.5（满载率只到一半）+ 1（达标）→ 均值 0.875
    expect(line.score).toBeCloseTo(0.875, 5);
    expect(line.scored).toHaveLength(4);
    expect(line.comparable).toBe(4);
  });

  it("类内只统计到一部分时，均值只按统计到的算，分母仍报可对标总数", () => {
    const only = dimensionRadarScores((indicator) => (indicator.modelKey === "pjhcsj" ? 10 : null))
      .find((item) => item.dimension === "运营服务");
    expect(only.score).toBe(RADAR_STANDARD_SCORE);
    expect(only.scored).toHaveLength(1);
    expect(only.comparable).toBe(2);
  });

  it("类内一项也统计不到时记 null 而不是 0（0 是极差，不是没数据）", () => {
    const empty = dimensionRadarScores((indicator) => (indicator.dimension === "运营服务" ? null : 1))
      .find((item) => item.dimension === "运营服务");
    expect(empty.score).toBeNull();
    expect(empty.scored).toHaveLength(0);
  });

  it("类得分同样封顶在 1.2", () => {
    const huge = dimensionRadarScores(() => 1e9).find((item) => item.dimension === "需求强度");
    expect(huge.score).toBe(RADAR_MAX_SCORE);
  });
});

describe("达标判定与归一化方向一致", () => {
  it.each(RADAR_INDICATORS.map((item) => [item.name, item]))(
    "%s 的达标判定与分值≥基准环同号",
    (_name, indicator) => {
      const std = indicator.standard;
      const probes = std.kind === "range"
        ? [std.a * 0.5, (std.a + std.b) / 2, std.b * 2]
        : [std.a * 0.5, std.a, std.a * 2];
      probes.forEach((value) => {
        const pass = isBetterThanStandard(value, indicator);
        const score = normalizeIndicator(value, indicator);
        expect(score).not.toBeNull();
        expect(score >= RADAR_STANDARD_SCORE).toBe(pass);
      });
    },
  );
});
