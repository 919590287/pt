---
target: 数据管理页面的全部类型面板和页面
total_score: 22
p0_count: 0
p1_count: 3
timestamp: 2026-05-27T14-02-12Z
slug: frontend-src-views-datamanagement-index-vue
---
# 数据管理页面全类型面板评审

## Design Health Score

| # | Heuristic | Score | Key Issue |
|---|-----------|-------|-----------|
| 1 | Visibility of System Status | 3 | 有选中态、修改计数、加载/错误标签，但地图加载、历史预览和编辑模式缺少更强状态提示 |
| 2 | Match System / Real World | 3 | 公交领域术语基本自然，但 `revision`、`真实数据`、英文 tech tag 有实现感 |
| 3 | User Control and Freedom | 2 | 有取消和未保存离开保护，但删除/移动缺少撤销，多个浮层叠加后退出路径不够干净 |
| 4 | Consistency and Standards | 2 | 页面视觉系统统一，但导航用 `div`、地图操作菜单、Element 弹窗和自定义浮层交互标准不一致 |
| 5 | Error Prevention | 2 | revision 冲突保护值得保留，但删除立即加入修改队列，编辑动作缺少确认/撤销/预览 |
| 6 | Recognition Rather Than Recall | 2 | 搜索可发现，但新增、修改、删除主要藏在地图点击里，空状态没有教用户下一步 |
| 7 | Flexibility and Efficiency | 2 | 搜索和直接地图编辑有效率，但没有批量编辑、键盘路径、历史对比或快速恢复 |
| 8 | Aesthetic and Minimalist Design | 3 | 地图加右侧指标面板清爽，但 hero 指标卡、英文标签、玻璃浮层和同质卡片有模板感 |
| 9 | Error Recovery | 2 | 有加载失败和冲突提示，但错误后的恢复动作不够具体，历史预览失败只靠 toast |
| 10 | Help and Documentation | 1 | 只有历史规则，更新页没有上下文帮助或操作说明 |
| **Total** | | **22/40** | **Acceptable, significant improvements needed** |

## Anti-Patterns Verdict

Does it look AI-generated? Partly. The map-first structure and sidebar are credible, but the right-side metric panel reads like a familiar AI/SaaS dashboard pattern: big hero number, English capsule labels, soft blue gradients, repeated cards, glassy overlays. It is not sloppy, but it is immediately believable as AI-assisted.

Deterministic scan found 8 issues in `frontend/src/views/datamanagement/index.vue`: 6 `bounce-easing` warnings at lines 2875, 3025, 3201, 3204, 3265, 3288, and 2 `layout-transition` warnings at lines 2786 and 4054. These agree with the visual read: motion is doing decoration and bounce, while the product context wants calm executive confidence.

Browser overlay was attempted in a fresh tab, but script mutation was unavailable in the current browser surface, so no reliable user-visible overlay was produced. CLI scan is the deterministic evidence for this run.

## Overall Impression

The page has a strong product base: persistent map, calm left navigation, compact data panel, and a real history workflow. The biggest opportunity is to make editing self-evident. Today the update pages look empty unless the user already knows to click the map.

## What's Working

1. The overview screen gives a fast executive read: network scale, route count, stop count, density, and coverage are visible without scrolling.
2. The history page has a clear mental model: current version, timeline, readonly preview, and version rules are separated well.
3. Search is genuinely useful. It narrows to station/line/depot context and the result list has clear type labels.

## Cognitive Load

Checklist failures: 5/8, high. The main failures are single focus, one thing at a time, minimal choices, working-memory burden, and progressive disclosure.

Decision points over 4 visible options: top navigation has 6 modules, sidebar exposes 6 data-management choices when expanded, search returns 8 options, and station detail can show 20+ route rows without secondary filtering.

The largest load problem is not density itself. It is hidden causality: update pages show `暂无未提交修改`, but the user must infer that clicking map objects opens the edit menu.

## Priority Issues

**[P1] Hidden edit workflow**
Why it matters: first-time users land on `线路/站点/场站数据更新` and see a disabled submit button plus an empty state. The actual action is hidden in map clicks.
Fix: add mode-specific empty-state instruction, visible "新增" action where appropriate, cursor/mode banner, and selected-layer hints. Keep map click power use, but expose the first action.
Suggested command: `/clarify`

**[P1] Overlay and focus stack gets messy**
Why it matters: search results, style popover, map action menu, history preview, and edit dialog can remain open together. This creates visual clutter and weak keyboard/screen-reader behavior.
Fix: define one transient-surface rule. Opening search closes style popover, opening edit menu closes search, modal closes popovers. Add Esc behavior and focus return.
Suggested command: `/harden`

**[P1] Some route/station detail values can be fabricated**
Why it matters: source logic falls back to hardcoded route length/time/station names and hash-derived passenger values when data is missing. In an external demo, this can damage trust.
Fix: show `暂无` or explicitly label estimates. Never silently synthesize operational metrics.
Suggested command: `/harden`

**[P2] Metric panel has premium intent but generic dashboard grammar**
Why it matters: the page should feel like a polished digital twin platform. English tags like `NETWORK SIZE`, the hero metric card, and repeated soft cards pull it toward template dashboard.
Fix: remove decorative tech tags, tighten metric grouping, add data provenance/last update/legend information, and let map plus data relationship carry the story.
Suggested command: `/distill`

**[P2] Motion tone is too playful for governance tooling**
Why it matters: bounce easing and width animation make the surface feel less stable and can add jank around search and panel transitions.
Fix: replace spring/bounce curves with restrained ease-out, avoid animating width/max-height, use transform/opacity or grid row transitions.
Suggested command: `/animate`

## Persona Red Flags

**Alex, power user**: Search helps, but there is no bulk edit, no keyboard path, no undo queue, and station details show long route lists without quick filtering. Editing remains one-object-at-a-time.

**Sam, accessibility-dependent user**: Sidebar items are clickable `div`s rather than buttons/links, map editing is click-only, many DOM nodes are generic, and stacked popovers/dialogs risk broken focus order.

**Chen, external decision maker**: Overview looks impressive, but the update pages look empty and unexplained. If Chen sees generated fallback metrics or implementation language like `revision`, confidence drops quickly.

## Minor Observations

- `datebase_box` looks like a code typo and hints at naming drift.
- The history page is visually separate from the map pages, which is fine, but the side nav remains visible while the content becomes a full page.
- `查看该历史` could be more specific: `预览此版本` better matches the readonly behavior.
- The style popover is useful, but it overlaps search/detail workflows and has no explicit close affordance.
- The station detail ranking is useful but needs a clear sort basis if `日均客流量` is estimated or incomplete.

## Questions to Consider

1. What if every update mode started with one obvious instruction and one obvious primary action?
2. What would make the right panel feel like a transport governance instrument rather than a generic metric card stack?
3. Should historical preview be a map overlay, a page mode, or a side-by-side compare workflow?
