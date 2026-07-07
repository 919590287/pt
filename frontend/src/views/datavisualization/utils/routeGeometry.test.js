import { describe, expect, it } from "vitest";
import { geometryPolylineLinks, provisionalRouteLinks } from "./routeGeometry.js";

describe("geometryPolylineLinks", () => {
  it("把抽稀走向折线转成 from/to 伪 links", () => {
    const links = geometryPolylineLinks([
      [0, 0],
      [10, 0],
      [10, 5],
    ]);
    expect(links).toEqual([
      { from: { x: 0, y: 0 }, to: { x: 10, y: 0 } },
      { from: { x: 10, y: 0 }, to: { x: 10, y: 5 } },
    ]);
  });

  it("空/非法输入返回空数组", () => {
    expect(geometryPolylineLinks(null)).toEqual([]);
    expect(geometryPolylineLinks([])).toEqual([]);
    expect(geometryPolylineLinks([[0, 0]])).toEqual([]);
  });

  it("跳过非数组的坏点，不产生半截 link", () => {
    const links = geometryPolylineLinks([[0, 0], null, [5, 5]]);
    expect(links).toEqual([]);
  });
});

describe("provisionalRouteLinks", () => {
  it("已有精确 links 时原样返回（不用 geometry）", () => {
    const links = [{ from: { x: 1, y: 1 }, to: { x: 2, y: 2 }, linkId: "a" }];
    const route = { links, geometry: [[0, 0], [9, 9]] };
    expect(provisionalRouteLinks(route)).toBe(links);
  });

  it("links 为空（lineAll 摘要瘦身）时回退 geometry", () => {
    const route = { links: [], geometry: [[0, 0], [10, 0]] };
    expect(provisionalRouteLinks(route)).toEqual([
      { from: { x: 0, y: 0 }, to: { x: 10, y: 0 } },
    ]);
  });

  it("整线组递归合并子路线的可绘 links", () => {
    const group = {
      lineGroup: true,
      links: [],
      childRoutes: [
        { links: [], geometry: [[0, 0], [1, 0]] },
        { links: [{ from: { x: 9, y: 9 }, to: { x: 8, y: 8 } }] },
      ],
    };
    expect(provisionalRouteLinks(group)).toEqual([
      { from: { x: 0, y: 0 }, to: { x: 1, y: 0 } },
      { from: { x: 9, y: 9 }, to: { x: 8, y: 8 } },
    ]);
  });

  it("无 links 也无 geometry 时返回空数组（走原网络等待路径）", () => {
    expect(provisionalRouteLinks({ links: [], geometry: [] })).toEqual([]);
    expect(provisionalRouteLinks({})).toEqual([]);
  });
});
