// 选中线路"即时上屏"共用的纯函数：
// lineAll 摘要（后端预计算缓存 v12+）为控制体积不带 links，只带抽稀后的真实走向 geometry
// （[[x, y], ...]，模型坐标系，与 link.from/to 同一坐标空间）。选中瞬间把 geometry 转成
// 只含 from/to 的伪 links 先画线+居中，精确 links（含 linkId，供断面着色）随 routeDetail 到达后替换。

export function geometryPolylineLinks(geometry) {
  const links = [];
  if (!Array.isArray(geometry)) return links;
  for (let i = 1; i < geometry.length; i++) {
    const a = geometry[i - 1];
    const b = geometry[i];
    if (Array.isArray(a) && Array.isArray(b)) {
      links.push({ from: { x: a[0], y: a[1] }, to: { x: b[0], y: b[1] } });
    }
  }
  return links;
}

// 可立即绘制的 links：已有精确 links 优先，其次本线 geometry，
// 整线组（地铁上下行合并）则递归合并各子路线的可绘 links。
export function provisionalRouteLinks(route = {}) {
  if (Array.isArray(route?.links) && route.links.length) return route.links;
  const geometryLinks = geometryPolylineLinks(route?.geometry);
  if (geometryLinks.length) return geometryLinks;
  if (route?.lineGroup && Array.isArray(route.childRoutes)) {
    return route.childRoutes.flatMap((child) => provisionalRouteLinks(child));
  }
  return [];
}
