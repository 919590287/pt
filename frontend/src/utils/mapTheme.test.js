import { describe, expect, it } from "vitest";
import {
  MAP_THEME,
  railwayCasingColor,
  railwayCasingWidth,
  railwayHatchColor,
  railwayHatchDashArray,
  railwayHatchWidthAtZoom,
  railwayLineWidth,
  railwayWidthAtZoom,
} from "./mapTheme.js";

const STOPS = [[8, 2], [11, 4], [16, 8]];

/** 递归找 ["zoom"]：MapLibre 只允许它出现在属性最外层的 interpolate/step 里 */
function hasNestedZoom(expression, depth = 0) {
  if (!Array.isArray(expression)) return false;
  if (expression[0] === "zoom" && depth > 1) return true;
  return expression.some((item) => hasNestedZoom(item, depth + 1));
}

/** ["step", ["zoom"], base, z1, v1, ...] → { base, [z1]: v1, ... } */
function stepStops(expression) {
  const out = { base: expression[2] };
  for (let i = 3; i < expression.length; i += 2) out[expression[i]] = expression[i + 1];
  return out;
}

describe("铁路制式线宽表达式", () => {
  it("宽度用 step 按整数缩放级取值，且不把 zoom 表达式套进算术表达式", () => {
    // 两条回归护栏：
    // 1) ["interpolate", ["zoom"], ...] 被套进 ["*"]/["max"]/["+"] 时 MapLibre 会
    //    静默丢弃该图层（不报错、不绘制）；
    // 2) 线宽必须是 step —— line-dasharray 的虚线纹理只在整数缩放级重烘焙，
    //    线宽若连续插值，块长会在级内被拉伸、过级时弹回。
    for (const expression of [
      railwayLineWidth(STOPS),
      railwayCasingWidth(STOPS),
    ]) {
      expect(expression[0]).toBe("step");
      expect(expression[1]).toEqual(["zoom"]);
      expect(hasNestedZoom(expression)).toBe(false);
    }
  });

  it("step 覆盖配置的整数缩放范围，每级取该级的插值宽度", () => {
    const [minZoom, maxZoom] = MAP_THEME.railway.stepZoomRange;
    const stops = stepStops(railwayLineWidth(STOPS));
    expect(stops.base).toBe(railwayWidthAtZoom(STOPS, minZoom));
    expect(stops[minZoom + 1]).toBe(railwayWidthAtZoom(STOPS, minZoom + 1));
    expect(stops[12]).toBe(railwayWidthAtZoom(STOPS, 12));
    expect(stops[maxZoom]).toBe(railwayWidthAtZoom(STOPS, maxZoom));
    expect(stops[maxZoom + 1]).toBeUndefined();
  });

  it("档位插值两端夹取，超出范围不外推", () => {
    expect(railwayWidthAtZoom(STOPS, 5)).toBe(2);
    expect(railwayWidthAtZoom(STOPS, 9.5)).toBe(3);
    expect(railwayWidthAtZoom(STOPS, 20)).toBe(8);
  });

  it("主线宽可带数据驱动系数，描边每级比主线宽出两侧 casingEdge", () => {
    const factor = ["match", ["get", "lineId"], "A", 2, 1];
    expect(stepStops(railwayLineWidth(STOPS, factor))[12]).toEqual(["*", railwayWidthAtZoom(STOPS, 12), factor]);
    const edge = MAP_THEME.railway.casingEdge * 2;
    expect(stepStops(railwayCasingWidth(STOPS))[12]).toBe(railwayWidthAtZoom(STOPS, 12) + edge);
  });

  it("嵌槽宽 = 主线宽 × hatchRatio 并夹住下限，与主线 step 取同一级的值", () => {
    const { hatchRatio, hatchMinWidth } = MAP_THEME.railway;
    expect(railwayHatchWidthAtZoom(STOPS, 16)).toBe(8 * hatchRatio);
    expect(railwayHatchWidthAtZoom(STOPS, 12)).toBe(railwayWidthAtZoom(STOPS, 12) * hatchRatio);
    // 低缩放级主线很细时，槽宽被 hatchMinWidth 兜住（否则细到看不见）
    expect(railwayHatchWidthAtZoom([[8, 0.5]], 12)).toBe(hatchMinWidth);
  });

  it("deck 的 dashArray 与线宽同为倍数语义，且返回拷贝", () => {
    expect(railwayHatchDashArray()).toEqual(MAP_THEME.railway.dash);
    expect(railwayHatchDashArray()).not.toBe(MAP_THEME.railway.dash); // 防被下游改写
  });

  it("明暗两套配色：浅色白槽、暗色黄槽，主线均为深色", () => {
    expect(railwayHatchColor(false)).toBe(MAP_THEME.railway.hatch);
    expect(railwayHatchColor(true)).toBe(MAP_THEME.railway.hatchDark);
    expect(railwayCasingColor(false)).toBe(MAP_THEME.railway.casing);
    expect(railwayCasingColor(true)).toBe(MAP_THEME.railway.casingDark);
  });
});
