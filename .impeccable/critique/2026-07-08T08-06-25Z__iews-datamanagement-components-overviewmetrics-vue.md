---
target: 数据总揽右侧面板 (OverviewMetrics)
total_score: 23
p0_count: 2
p1_count: 1
timestamp: 2026-07-08T08-06-25Z
slug: iews-datamanagement-components-overviewmetrics-vue
---
Method: dual-agent (A: acbe5c38b4fdfdd2c · B: a5b234cdd012ba12f)

# Critique — 数据总览 (Data Overview) right-side panel

Target: `frontend/src/views/datamanagement/components/OverviewMetrics.vue` + parent shell/computeds in `frontend/src/views/datamanagement/index.vue`, tokens in `tokens.css`.

## Design Health Score

| # | Heuristic | Score | Key issue |
|---|-----------|-------|-----------|
| 1 | Visibility of System Status | 2 | No skeleton; no "data as of…" freshness stamp; 加载失败 chip floats over a still-rendered zero-filled body |
| 2 | Match System / Real World | 3 | Labels are good; but 车辆数/配车占比 columns are literal "-" and the dash is painted brand-blue |
| 3 | User Control and Freedom | 3 | Read-only + collapsible; no sort/drill-down for a data panel |
| 4 | Consistency and Standards | 2 | Teal 500m bar + leftover bronze/teal title-row colors + gray/amber el-tags break the stated 单一蓝 system |
| 5 | Error Prevention | 3 | Little user input; the permanent "-" is a design defect, not a user error |
| 6 | Recognition Rather Than Recall | 3 | Labels + units always visible; sticky header/total — good |
| 7 | Flexibility and Efficiency | 2 | No sort, no drill-down, no export; ~40% of table width is dead |
| 8 | Aesthetic and Minimalist | 2 | Hero stacks 5 decorative treatments; 40% of the table is zero-information dashes |
| 9 | Error Recovery | 2 | Error chip contradicts the zeros beneath it; no retry |
| 10 | Help and Documentation | 1 | No definition of 线网密度 method, coverage radius, or why 车辆数 is empty |
| **Total** | | **23/40** | **Acceptable — significant improvements needed before it's demo-grade** |

## Anti-Patterns Verdict

**Does this look AI-generated? Partly — verdict: FINGERPRINTED, not slop.** It clears the worst tells (no identical-card-grid, real token system, tabular-nums on every number, genuinely differentiated block shapes). But three specific fingerprints give it away: (1) the hero card stacks five decorative treatments at once — border + soft shadow + sunken tint + radial "data glow" + inset highlight + a **3px gradient left side-stripe** (`.hero-card::after`), and that side-stripe is squarely on the banned side-stripe list; (2) every `.metric-card` pairs a 1px border AND a soft shadow (the ghost-card doubling — pick one); (3) the teal 500m bar is an unjustified second accent in a system whose own tokens file commits to "克制，单一蓝".

**Deterministic scan:** `detect.mjs` returned **exit 0, zero findings** on both `OverviewMetrics.vue` and `index.vue`. Verified functional (a control file with bounce-easing was correctly flagged), so the clean result is genuine — the component's easings have no overshoot. Caveat: the ruleset has no contrast/font rules, so "clean" doesn't certify contrast (checked separately below).

**Contrast (computed WCAG):** No text fails 4.5:1, but two pairs pass only on thin margins — `--dm2-accent #0071e3` on white = **4.70:1** (drives the 12px operator-share % and 14px coverage %; any surface darkening drops it below), and `--dm2-muted #667085` on the sunken hero bg = **4.63:1**. `--dm2-muted-soft #98a2b3` on white = 2.58:1 (fails) but only paints decorative icons.

**Visual overlay:** not available — the panel only renders inside the full stateful app (backend + loaded GeoJSON + right panel on the 数据总览 tab), so no standalone URL and no live overlay was produced. The user-supplied screenshot is the visual evidence of record.

## Overall Impression

The top half is confident and premium — the 44px tabular hero over the hairline-split stat strip reads the brand's "understand at a glance" promise well. Then the eye falls to the bottom card and lands on a **grid of dashes**, and in an external demo that single impression ("half-built") outweighs everything above it (peak-end). The biggest opportunity isn't more polish — it's **subtraction**: cut the two empty columns and reconcile the two conflicting line totals, and the panel jumps from "acceptable" to "demo-grade" without a redesign.

## What's Working

1. **Confident top-half type ramp.** 44px tabular-nums hero at −0.035em → 20px stat values → 12px muted labels is a legible, premium hierarchy; the brand's "lead with visual comprehension" is genuinely met above the coverage card.
2. **Differentiated block shapes, not a card-grid clone.** Sunken hero → hairline-split 3-cell strip → progress card → table is real IA intent that sidesteps the default AI "four identical cards" tell.
3. **Disciplined numeric presentation.** `tabular-nums` + `"tnum"` on every number, `coverageWidth()` clamped 0–100, and `fmtInt/fmtUnit/fmtPct` injected from the parent as a single source of truth — formatting won't drift or jitter.

## Priority Issues

**[P0] ~40% of the 企业线路统计 table is permanent dashes — and the dash is painted brand-blue.** `operatorLineRows` hardcodes `vehicleCount:"-"` and `vehicleShare:"-"` on every row and the total (`index.vue:1034-1035, 1042-1043`); the `vehicleShare` dash renders through `.operator-share { color: var(--dm2-accent) !important }`, so the placeholder is drawn in the accent color as if it were live data. *Why it matters:* it's the panel's bottom element, so the last thing a stakeholder sees is emptiness — reads as "unfinished product." *Fix:* drop to a clean 3-column table (企业 / 线路数量 / 线路占比) now; reintroduce the two columns conditionally only when a row carries a real value, or collapse them into one honest "车辆数据待接入" row. Never ship a full column of "-".

**[P0] Two conflicting "total lines" in one panel: 2,516 vs 1,604.** 线路总数 (`overviewStats.lineCount`) shows 2,516 in the strip; the 企业线路统计 总计 (`physicalLineGroups(...).length`) shows 1,604. *Why it matters:* a viewer sees both within one glance and stops trusting *all* the numbers — the fastest way to lose a demo. *Fix:* reconcile to one source, or relabel the table total to disambiguate (e.g. "有企业归属的线路 1,604") and explain the delta. Two unexplained totals must not co-exist on a client screen.

**[P1] No real loading state, and empty/error are chips floating over a live body.** `OverviewMetrics` is the `v-else` of the detail branches with no skeleton, so during load it **pops in with formatted zeros** (hero 0, total-only table). The `等待数据`/`加载失败` el-tags live only in the title-row header (`index.vue:68-71`) and the `load-error` text sits *below* the still-rendered metrics — so an error state shows an amber chip *above* a zero-filled panel. *Why it matters:* the brand explicitly requires "legible states"; contradictory states read as broken. *Fix:* add a shimmer skeleton matching hero/strip/table; a single centered empty state; and an error block with retry that **replaces** the body instead of overlaying zeros.

**[P2] Teal 500m bar breaks the single-blue system (and is colorblind-hostile).** `.fill-500` is teal while `.fill-300` is blue — but 300m and 500m are the *same metric at two radii*, not two categories, so the hue change is pure decoration and violates the tokens file's own "单一蓝" rule. *Fix:* one blue for both, differentiate by depth (300m solid `--dm2-accent`, 500m same hue at lower opacity) so the two radii read as a scale, not two things.

**[P2] Hero card over-decoration + the banned side-stripe.** border + shadow + sunken tint + radial glow + inset highlight + 3px gradient `::after` side-stripe is the over-decorated "make the number feel important" fingerprint. *Fix:* keep at most two signals — retain the sunken tint as the focus cue and delete `.hero-card::after` (and/or the radial glow). Let the 44px number carry the weight. Also: the `.metric-card` `transition` for border/shadow/transform is declared but never fires (there's no `:hover`/`:focus` rule) — dead code.

## Persona Red Flags

**Stakeholder / demo-viewer (the actual audience):**
- Wall of "-" in 车辆数/配车占比 → "half-built."
- 2,516 vs 1,604 total conflict → "the numbers don't tie out."
- No data-freshness stamp anywhere → "is this live or stale?"
- 线网密度 shown to **4 decimals** ("5.6969", `formatUnit(…,4)`) while everything else is 2-dp → false precision reads as unpolished.

**Sam (accessibility):**
- 300 vs 500 coverage is distinguished **by hue only** (blue/teal) with the track `aria-hidden` — deuteranopia can't separate the two fills; value survives only via the adjacent % text.
- Table is a `<div>` grid with **no table semantics** (no `role="table"/"row"/"columnheader"`), nothing focusable, no `:focus-visible`. Row `:hover` is a 0.045-alpha tint — barely perceptible.
- (Good news: the coverage percentages ARE exposed to screen readers — they live in a non-aria-hidden label row.)

**Alex (power user):**
- Cannot sort the operator table by line count or share; no drill-down from operator → its lines; no export/copy.
- Only ~3 rows visible before internal scroll; the two dead columns waste the horizontal space he'd want for real KPIs.
- `adminAreaKm2` is held in state but never surfaced — he can't see the density's denominator.

## Minor Observations

- **Company column is ~119px, narrower than it looks.** The grid resolves the 企业 column to ≈119px (not ~150px), so long names like "广州公交集团一汽巴士客运公司" wrap to 2 lines. Note: `min-height:38px` is a floor, not a ceiling, and there's **no `overflow:hidden`** in the cell — so wrapped names **inflate the row to ~48px** (uneven rhythm + extra scroll), they don't truly clip. If the screenshot looks clipped, it's row-height inflation, not a CSS clip.
- **Centered numeric columns defeat `tabular-nums`.** Right-align the numeric columns so magnitudes stack for comparison; left-align 企业. Full-centering throws away the whole point of tabular figures.
- **Leftover bronze/teal theme inside a "single-blue" component:** the shared title-row divider is hardcoded `rgba(42,59,58,0.09)` (teal-tinted, not `--dm2-line`), the detail kicker bg is teal, and `.detail-close-btn` is bronze `#8f642b`. Old theme living in the new system.
- **el-tag colors leak a 4th hue:** `等待数据` is default gray, `加载失败` is amber — two non-blue Element Plus defaults in a disciplined-blue panel.
- **`.coverage-title-row` is a `space-between` flex** built to hold a right-side value that doesn't exist → dangling layout.
- **Unit source-of-truth is split** for the hero: `fmtUnit(networkScaleKm, "")` plus a separate hardcoded `<span>km</span>`.

## Questions to Consider

1. If 车辆数/配车占比 have never had a data source, why does the table ship in 5-column form at all — is a roadmap promise being presented as a delivered feature, and what does a grid of "-" do to demo credibility?
2. The panel states two different totals for "lines" (2,516 and 1,604). Which is the product's *official* answer — and if the team can't say in one sentence, should either number be on a client-facing screen?
3. The brand asks the viewer to "understand the data story at a glance." What *is* the story — "the network is vast (42,159 km)," "coverage is low (20–35%)," or "巴士集团 dominates"? Right now the composition privileges none; four unrelated facts are stacked. Should the panel take a position?
