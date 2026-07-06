import { KIND_META } from "./utils";

/**
 * 修改清单冲突检测：在每一项修改加入右侧清单之前调用，
 * 命中冲突时返回一段"大家都能看得懂"的原因，阻止加入。
 *
 * 返回 { ok: true } 或 { ok: false, reason: "…" }。
 */

function label(kind) {
  return KIND_META[kind]?.label || kind;
}

/** 修改项是否作用于线路（route.* 与 ops.*） */
function isRouteEdit(edit) {
  return edit.kind.startsWith("route.") || edit.kind.startsWith("ops.");
}

/** 删除项的作用范围是否覆盖 lineId(+routeId) */
function deleteCovers(deleteEdit, lineId, routeId) {
  const t = deleteEdit.target || {};
  if (t.lineId !== lineId) return false;
  if (!Array.isArray(t.routeIds) || t.routeIds.length === 0) return true; // 整线删除
  return routeId ? t.routeIds.includes(routeId) : true;
}

/** 两个线路修改项的作用范围是否重叠（同线同方向，或其中一方作用于整线） */
function routeScopeOverlap(aTarget = {}, bTarget = {}) {
  if (!aTarget.lineId || aTarget.lineId !== bTarget.lineId) return false;
  const aRoutes = aTarget.routeIds || (aTarget.routeId ? [aTarget.routeId] : null);
  const bRoutes = bTarget.routeIds || (bTarget.routeId ? [bTarget.routeId] : null);
  if (!aRoutes || !bRoutes) return true; // 任一方作用整线
  return aRoutes.some((r) => bRoutes.includes(r));
}

/** 收集清单中已删除的站点 id -> 删除项 */
function deletedStops(edits) {
  const map = new Map();
  for (const e of edits) {
    if (e.kind === "stop.delete" && e.target?.stopId) map.set(e.target.stopId, e);
  }
  return map;
}

/** 收集清单中已删除的路段 id 集合 */
function deletedLinks(edits) {
  const set = new Set();
  for (const e of edits) {
    if (e.kind === "link.delete") for (const id of e.target?.linkIds || []) set.add(id);
  }
  return set;
}

/** 修改项停靠/引用的站点 id 列表 */
function stopsReferencedBy(edit) {
  if (edit.kind === "route.add" || edit.kind === "route.replace" || edit.kind === "route.modify.alignment") {
    const ids = [];
    for (const dir of edit.geometry?.directions || []) ids.push(...(dir.stops || []));
    if (Array.isArray(edit.geometry?.stops)) ids.push(...edit.geometry.stops);
    return ids;
  }
  if (edit.kind === "route.modify.stops") return edit.params?.stops || [];
  return [];
}

/** 修改项走向经过的路段 id 列表 */
function linksReferencedBy(edit) {
  if (edit.kind === "route.add" || edit.kind === "route.replace" || edit.kind === "route.modify.alignment") {
    const ids = [];
    for (const dir of edit.geometry?.directions || []) ids.push(...(dir.linkIds || []));
    if (Array.isArray(edit.geometry?.linkIds)) ids.push(...edit.geometry.linkIds);
    return ids;
  }
  return [];
}

function fail(reason) {
  return { ok: false, reason };
}

const OK = { ok: true };

/**
 * 主入口。
 * candidate: 待加入的修改（与 addEdit 参数同形：{kind,name,target,params,geometry,deps}）
 * edits: 当前清单
 * ctx: { routeIndex, stopIndex }（可选，用于把 id 翻译成名字）
 */
export function checkEditConflict(candidate, edits, ctx = {}) {
  const { stopIndex } = ctx;
  const kind = candidate.kind;
  const t = candidate.target || {};

  const stopName = (id) => stopIndex?.get?.(id)?.name || id;

  // ---------- 删除线路 ----------
  if (kind === "route.delete") {
    const dup = edits.find((e) => e.kind === "route.delete" && routeScopeOverlap(e.target, t));
    if (dup) {
      return fail(`线路「${candidate.name || t.lineId}」已经在修改清单里被删除了（见右侧「删除线路 ${dup.name}」），不需要重复删除。`);
    }
    const touching = edits.filter((e) => isRouteEdit(e) && e.kind !== "route.delete" && e.kind !== "route.add" && routeScopeOverlap(e.target, t));
    if (touching.length > 0) {
      const names = touching.map((e) => `「${label(e.kind)}」`).join("、");
      return fail(
        `不能删除线路「${candidate.name || t.lineId}」：修改清单里已有 ${touching.length} 项修改作用在这条线路上（${names}）。` +
        `线路删掉后这些修改就落空了。请先在右侧清单撤销这些修改（点 ↺），再删除线路；或者保留线路、放弃删除。`
      );
    }
    return OK;
  }

  // ---------- 修改线路（走向/站点/运营参数） ----------
  if (isRouteEdit({ kind }) && kind !== "route.add") {
    const del = edits.find((e) => e.kind === "route.delete" && deleteCovers(e, t.lineId, t.routeId));
    if (del) {
      return fail(
        `不能修改线路「${candidate.name || t.lineId}」：这条线路已经在修改清单里被删除了（见右侧「删除线路 ${del.name}」）。` +
        `已删除的线路无法再修改。如果想修改而不是删除，请先在右侧撤销那条删除记录。`
      );
    }
    const sameKind = edits.find((e) => e.kind === kind && routeScopeOverlap(e.target, t));
    if (sameKind) {
      return fail(
        `这条线路已经有一项「${label(kind)}」在修改清单里了。同一条线路重复添加同类修改，两项会互相覆盖、实际只有一项生效，容易出错。` +
        `请先在右侧撤销原来那项（点 ↺），再添加新的。`
      );
    }
    // 调整走向 与 调整停靠 都会重设停靠序列，互相覆盖
    const STOP_TOUCHING = ["route.modify.alignment", "route.modify.stops"];
    if (STOP_TOUCHING.includes(kind)) {
      const other = STOP_TOUCHING.find((k) => k !== kind);
      const clash = edits.find((e) => e.kind === other && routeScopeOverlap(e.target, t));
      if (clash) {
        return fail(
          `这条线路已经有一项「${label(clash.kind)}」在修改清单里了。「调整走向」和「调整站点」都会重新设定这条线路停靠哪些站，两项叠加会互相覆盖。` +
          `请先在右侧撤销原来那项，把想要的改动一次做完再加入。`
        );
      }
    }
    // 引用了已被删除的站点
    const deleted = deletedStops(edits);
    for (const sid of stopsReferencedBy(candidate)) {
      if (deleted.has(sid)) {
        return fail(
          `不能加入这项修改：它安排线路停靠「${stopName(sid)}」，但这个站点已经在修改清单里被删除了。` +
          `请把该站从停靠列表中去掉，或先在右侧撤销「删除站点 ${deleted.get(sid).name}」。`
        );
      }
    }
    // 走向经过已被删除的路段
    const delLinks = deletedLinks(edits);
    const hit = linksReferencedBy(candidate).find((id) => delLinks.has(id));
    if (hit) {
      return fail(
        `不能加入这项修改：新走向经过的一段道路已经在修改清单里被删除了（路段 ${hit}）。` +
        `请绕开该路段重新画走向，或先在右侧撤销对应的「删除路段」。`
      );
    }
    return OK;
  }

  // ---------- 新增线路 ----------
  if (kind === "route.add") {
    const sameName = edits.find((e) => e.kind === "route.add" && (e.params?.name || e.name) === (candidate.params?.name || candidate.name));
    if (sameName) {
      return fail(`修改清单里已经有一条同名的新增线路「${candidate.name}」。如果确实要再加一条，请换一个名称，避免生成后无法区分。`);
    }
    const deleted = deletedStops(edits);
    for (const sid of stopsReferencedBy(candidate)) {
      if (deleted.has(sid)) {
        return fail(
          `不能加入新线路「${candidate.name}」：它停靠的站点「${stopName(sid)}」已经在修改清单里被删除了。` +
          `请取消勾选该站，或先在右侧撤销「删除站点 ${deleted.get(sid).name}」。`
        );
      }
    }
    const delLinks = deletedLinks(edits);
    const hit = linksReferencedBy(candidate).find((id) => delLinks.has(id));
    if (hit) {
      return fail(
        `不能加入新线路「${candidate.name}」：它的走向经过一段已在清单里被删除的道路（路段 ${hit}）。请绕开该路段重新画走向，或撤销对应的「删除路段」。`
      );
    }
    return OK;
  }

  // ---------- 删除站点 ----------
  if (kind === "stop.delete") {
    const dup = edits.find((e) => e.kind === "stop.delete" && e.target?.stopId === t.stopId);
    if (dup) {
      return fail(`站点「${candidate.name}」已经在修改清单里被删除了，不需要重复删除。`);
    }
    const moved = edits.find((e) => e.kind === "stop.move" && e.target?.stopId === t.stopId);
    if (moved) {
      return fail(
        `不能删除站点「${candidate.name}」：清单里已有一项「修改站点」作用在它身上，删除后那项修改就落空了。请先在右侧撤销「修改站点 ${moved.name}」。`
      );
    }
    const user = edits.find((e) => stopsReferencedBy(e).includes(t.stopId));
    if (user) {
      return fail(
        `不能删除站点「${candidate.name}」：清单里的「${label(user.kind)} ${user.name}」安排了线路停靠这个站。` +
        `请先调整那条线路的停靠（或撤销那项修改），再删除站点。`
      );
    }
    return OK;
  }

  // ---------- 修改站点 ----------
  if (kind === "stop.move") {
    const del = edits.find((e) => e.kind === "stop.delete" && e.target?.stopId === t.stopId);
    if (del) {
      return fail(`不能修改站点「${candidate.name}」：它已经在修改清单里被删除了。请先在右侧撤销「删除站点 ${del.name}」。`);
    }
    const dup = edits.find((e) => e.kind === "stop.move" && e.target?.stopId === t.stopId);
    if (dup) {
      return fail(
        `站点「${candidate.name}」已经有一项「修改站点」在清单里了，重复添加会互相覆盖。请先在右侧撤销原来那项，再添加新的。`
      );
    }
    return OK;
  }

  // ---------- 删除路段 ----------
  if (kind === "link.delete") {
    const ids = t.linkIds || [];
    const already = deletedLinks(edits);
    const dup = ids.find((id) => already.has(id));
    if (dup) {
      return fail(`所选路段中有已在修改清单里删除过的路段（${dup}），不需要重复删除。请重新选择。`);
    }
    for (const e of edits) {
      if (e.kind === "link.modify") {
        const clash = (e.target?.linkIds || []).find((id) => ids.includes(id));
        if (clash) {
          return fail(
            `不能删除所选路段：其中一段（${clash}）在清单里已有「路段属性」修改，删除后那项修改就落空了。请先在右侧撤销那项修改。`
          );
        }
      }
      const used = linksReferencedBy(e).find((id) => ids.includes(id));
      if (used) {
        return fail(
          `不能删除所选路段：清单里的「${label(e.kind)} ${e.name}」的线路走向要经过其中一段（${used}）。` +
          `请先调整那条线路的走向（或撤销那项修改），再删除路段。`
        );
      }
    }
    return OK;
  }

  // ---------- 修改路段属性 ----------
  if (kind === "link.modify") {
    const ids = t.linkIds || [];
    const already = deletedLinks(edits);
    const dup = ids.find((id) => already.has(id));
    if (dup) {
      return fail(`不能修改所选路段：其中一段（${dup}）已经在修改清单里被删除了。请重新选择，或先撤销对应的「删除路段」。`);
    }
    for (const e of edits) {
      if (e.kind === "link.modify") {
        const clash = (e.target?.linkIds || []).find((id) => ids.includes(id));
        if (clash) {
          return fail(
            `所选路段中有一段（${clash}）已经有属性修改在清单里了，重复添加会互相覆盖。请先在右侧撤销原来那项，或去掉重复的路段。`
          );
        }
      }
    }
    return OK;
  }

  // stop.add / link.add 等：无阻断性冲突
  return OK;
}
