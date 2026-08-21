<template>
  <div class="overview-metric-list">
    <!-- 主指标：线网总长度 + 计划运营里程（随行政区切换） -->
    <div class="metric-card hero-card">
      <div class="hero-metric">
        <span class="hero-label">线网总长度</span>
        <span class="hero-value">
          <strong class="hero-num">{{ fmtUnit(stats.networkScaleKm, "") }}</strong>
          <span v-if="hasNumber(stats.networkScaleKm)" class="hero-unit">km</span>
        </span>
      </div>
      <div class="hero-metric" title="Σ 方向计划日班次 × 方向线路长度；选定行政区时按区内段里程统计">
        <span class="hero-label">计划运营里程</span>
        <span class="hero-value">
          <!-- fmtUnit 对 null 会经 Number(null)=0 误显示 0，此处显式判空 -->
          <strong class="hero-num">{{ hasNumber(stats.dailyMileageWanKm) ? fmtUnit(stats.dailyMileageWanKm, "", 1) : "暂无" }}</strong>
          <span v-if="hasNumber(stats.dailyMileageWanKm)" class="hero-unit">万车公里/日</span>
        </span>
      </div>
    </div>

    <!-- 次级：三联规模指标 -->
    <div class="overview-stat-strip">
      <div class="stat-cell">
        <span class="stat-label">线路总数</span>
        <span class="stat-value"><strong>{{ fmtInt(stats.lineCount) }}</strong><em>条</em></span>
      </div>
      <div class="stat-cell">
        <span class="stat-label">站点数量</span>
        <span class="stat-value"><strong>{{ fmtInt(stats.stationCount) }}</strong><em>个</em></span>
      </div>
      <div class="stat-cell">
        <span class="stat-label">线网密度</span>
        <span class="stat-value"><strong>{{ fmtUnit(stats.networkDensityKmPerKm2, "", 4) }}</strong><em>km/km²</em></span>
      </div>
    </div>

    <!-- 覆盖率：真实常住人口中位于公交站点服务范围内的比例 -->
    <div class="metric-card coverage-card">
      <div class="card-title-row coverage-title-row">
        <span class="card-title">公交站点人口覆盖率</span>
      </div>
      <div class="coverage-metrics">
        <div class="coverage-item">
          <div class="coverage-label-row">
            <span>300 米</span>
            <strong>{{ fmtPct(coverage.rate300) }}</strong>
          </div>
          <div class="coverage-track" aria-hidden="true">
            <span class="coverage-fill fill-300" :style="{ width: coverageWidth(coverage.rate300) }"></span>
          </div>
        </div>
        <div class="coverage-item">
          <div class="coverage-label-row">
            <span>500 米</span>
            <strong>{{ fmtPct(coverage.rate500) }}</strong>
          </div>
          <div class="coverage-track" aria-hidden="true">
            <span class="coverage-fill fill-500" :style="{ width: coverageWidth(coverage.rate500) }"></span>
          </div>
        </div>
      </div>
    </div>

    <!-- 详情：企业线路统计 -->
    <div class="metric-card operator-table-card">
      <div class="card-title-row">
        <span class="card-title">企业线路统计</span>
      </div>
      <div class="operator-table">
        <div class="operator-table-row operator-table-head">
          <span class="operator-company">企业</span>
          <span class="operator-number">线路数量</span>
          <span class="operator-number">线路占比</span>
          <span class="operator-number" title="真实线网暂无配车数据源，待业务配车表接入">配车数</span>
          <span class="operator-number operator-th-stack" title="Σ 方向计划日班次 × 方向线路长度；选定行政区时按区内段里程统计">计划运营里程<i class="operator-col-unit">万车公里/日</i></span>
        </div>
        <div
          v-for="row in operatorRows"
          :key="row.company"
          class="operator-table-row"
          :class="{ 'operator-table-total': row.isTotal }"
        >
          <span class="operator-company" :title="row.company">{{ row.company }}</span>
          <strong class="operator-number">{{ row.lineCount }}</strong>
          <strong class="operator-number operator-share">{{ fmtPct(row.lineShare) }}</strong>
          <strong class="operator-number">{{ row.vehicleCount }}</strong>
          <strong class="operator-number">{{ row.mileageText }}</strong>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  stats: { type: Object, required: true }, // overviewDisplayStats（含 dailyMileageWanKm，均已随行政区切换）
  operatorRows: { type: Array, default: () => [] },
  // 覆盖率视图（当前范围的速率/分母/是否建成区覆写等），由父级计算注入
  coverage: { type: Object, required: true },
  // 纯格式化函数，由父级注入以保持单一来源
  fmtInt: { type: Function, required: true },
  fmtUnit: { type: Function, required: true },
  fmtPct: { type: Function, required: true },
});
function hasNumber(value) {
  return Number.isFinite(Number(value)) && value !== null && value !== "";
}

function coverageWidth(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) return "0%";
  return `${Math.max(0, Math.min(100, number))}%`;
}
</script>

<style lang="scss" scoped>
/* 数据总览 · 指标区 —— 自包含、全令牌、清晰层级（hero > 规模三联 > 明细卡） */
.overview-metric-list {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 10px;
  padding-right: 4px;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: thin;
  scrollbar-color: rgba(15, 23, 42, 0.18) transparent;

  &::-webkit-scrollbar {
    width: 5px;
  }

  &::-webkit-scrollbar-thumb {
    background: rgba(15, 23, 42, 0.16);
    border-radius: 999px;
  }
}

.metric-card {
  flex-shrink: 0;
  border: 1px solid var(--dm2-line);
  border-radius: var(--dm2-radius);
  background: var(--dm2-surface);
  box-shadow: var(--dm2-shadow-card);
}

/* ① 主指标：线网总长度 / 计划运营里程 */
.hero-card {
  position: relative;
  display: flex;
  align-items: stretch;
  padding: 16px 18px;
  overflow: hidden;
  /* 柔光锚在数字左侧后方、随内容延展，取代此前浮在右上角、与内容脱节的色块 */
  background:
    radial-gradient(90% 140% at 2% 50%, rgba(0, 113, 227, 0.07), transparent 60%),
    var(--dm2-surface-sunken);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.7);
}

.hero-metric {
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.hero-metric + .hero-metric {
  margin-left: 16px;
  padding-left: 16px;
  border-left: 1px solid var(--dm2-line-faint);
}

.hero-label {
  color: var(--dm2-muted);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.02em;
  white-space: nowrap;
}

.hero-value {
  display: flex;
  align-items: baseline;
  gap: 5px;
  min-width: 0;
}

.hero-num {
  font-family: var(--dm2-font-num);
  font-size: 27px;
  line-height: 1;
  font-weight: 700;
  letter-spacing: -0.03em;
  color: var(--dm2-ink);
  font-variant-numeric: tabular-nums;
  font-feature-settings: "tnum" 1;
  white-space: nowrap;
}

.hero-unit {
  font-size: 12px;
  font-weight: 600;
  color: var(--dm2-muted);
  white-space: nowrap;
}

/* ② 规模三联 */
.overview-stat-strip {
  flex-shrink: 0;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  border: 1px solid var(--dm2-line);
  border-radius: var(--dm2-radius);
  background: var(--dm2-surface);
  box-shadow: var(--dm2-shadow-card);
  overflow: hidden;
}

.stat-cell {
  display: flex;
  flex-direction: column;
  gap: 7px;
  min-width: 0;
  padding: 12px 14px;
}

.stat-cell + .stat-cell {
  border-left: 1px solid var(--dm2-line-faint);
}

.stat-label {
  color: var(--dm2-muted);
  font-size: 12px;
  font-weight: 600;
}

.stat-value {
  display: flex;
  align-items: baseline;
  gap: 3px;
  min-width: 0;
}

.stat-value strong {
  font-family: var(--dm2-font-num);
  font-size: 20px;
  line-height: 1;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--dm2-ink);
  font-variant-numeric: tabular-nums;
  font-feature-settings: "tnum" 1;
}

.stat-value em {
  font-style: normal;
  font-size: 11px;
  font-weight: 600;
  color: var(--dm2-muted);
  white-space: nowrap;
}

/* ③ 明细卡通用标题 */
.card-title-row {
  margin-bottom: 10px;
}

.card-title {
  color: var(--dm2-ink-soft);
  font-size: 12.5px;
  font-weight: 600;
  letter-spacing: 0.01em;
}

/* 公交站点人口覆盖率 */
.coverage-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 14px;
}

.coverage-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 0;
}

.coverage-metrics {
  display: grid;
  gap: 9px;
}

.coverage-item {
  display: grid;
  gap: 6px;
}

.coverage-label-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  color: var(--dm2-muted);
  font-size: 11.5px;
  font-weight: 600;
}

.coverage-label-row strong {
  color: var(--dm2-accent);
  font-family: var(--dm2-font-num);
  font-size: 14px;
  line-height: 1;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
  font-feature-settings: "tnum" 1;
}

.coverage-track {
  position: relative;
  height: 7px;
  overflow: hidden;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.07);
}

.coverage-fill {
  position: absolute;
  inset: 0 auto 0 0;
  min-width: 2px;
  border-radius: inherit;
}

/* 300/500 是同一指标的两个半径，用单一蓝的深浅区分（而非换色相），
   既守住「克制·单一蓝」体系，也对色觉障碍更友好 */
.fill-300 {
  background: linear-gradient(90deg, rgba(0, 113, 227, 0.9), rgba(45, 140, 255, 0.98));
}

.fill-500 {
  background: linear-gradient(90deg, rgba(0, 113, 227, 0.42), rgba(45, 140, 255, 0.52));
}

/* 企业线路统计表 */
.operator-table-card {
  flex: 1 1 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 12px 14px;
}

.operator-table {
  flex: 1 1 auto;
  min-height: 0;
  max-height: none;
  overflow-x: hidden;
  overflow-y: auto;
  padding-bottom: 1px;
  scrollbar-width: thin;
  scrollbar-color: rgba(15, 23, 42, 0.18) transparent;

  &::-webkit-scrollbar {
    width: 4px;
  }

  &::-webkit-scrollbar-thumb {
    background: rgba(15, 23, 42, 0.16);
    border-radius: 999px;
  }
}

.operator-table-row {
  display: grid;
  width: 100%;
  box-sizing: border-box;
  grid-template-columns:
    minmax(72px, 1.2fr)
    minmax(52px, 0.82fr)
    minmax(52px, 0.82fr)
    minmax(44px, 0.7fr)
    minmax(86px, 1.3fr);
  min-height: 38px;
  align-items: center;
  border-bottom: 1px solid var(--dm2-line-faint);
  border-radius: 7px;
  transition: background-color var(--dm2-dur) var(--dm2-ease);
}

/* 表头列内单位（计划运营里程，万车公里/日） */
.operator-table-row .operator-th-stack {
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1px;
  text-align: center;
}

.operator-col-unit {
  font-style: normal;
  font-size: 9.5px;
  font-weight: 500;
  color: var(--dm2-muted-soft);
  line-height: 1.2;
}

.operator-table-row:not(.operator-table-head):hover {
  background: rgba(0, 113, 227, 0.045);
}

.operator-table-total {
  position: sticky;
  bottom: 0;
  z-index: 1;
  border-top: 1px solid var(--dm2-line-strong);
  background: var(--dm2-surface);
}

.operator-table-total span,
.operator-table-total strong {
  color: var(--dm2-ink);
  font-weight: 800;
}

.operator-table-row:last-child {
  border-bottom: none;
}

.operator-table-row span,
.operator-table-row strong {
  display: flex;
  align-items: center;
  justify-content: center;
  align-self: stretch;
  padding: 8px 4px;
  font-size: 12px;
  line-height: 1.35;
  text-align: center;
  color: var(--dm2-ink-soft);
  min-width: 0;
  white-space: normal;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.operator-table-row strong {
  color: var(--dm2-ink);
  font-family: var(--dm2-font-num);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  font-feature-settings: "tnum" 1;
}

/* 表头和数据共用同一网格轨道，并统一居中，避免列内对齐规则互相覆盖。 */
.operator-table-row .operator-company {
  justify-content: center;
  text-align: center;
}

.operator-table-row .operator-number {
  justify-content: center;
  text-align: center;
}

.operator-share {
  color: var(--dm2-accent) !important;
}

.operator-table-head {
  position: sticky;
  top: 0;
  z-index: 1;
  background: var(--dm2-surface);
  border-bottom: 1px solid var(--dm2-line);
}

.operator-table-head span {
  color: var(--dm2-muted);
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
}

/* ── 暗色模式（html.dark，跟随底图选择） ── */
html.dark .overview-metric-list {
  scrollbar-color: rgba(148, 180, 220, 0.28) transparent;
}

html.dark .overview-metric-list::-webkit-scrollbar-thumb {
  background: rgba(148, 180, 220, 0.28);
}

html.dark .hero-card {
  background:
    radial-gradient(90% 140% at 2% 50%, rgba(64, 156, 255, 0.11), transparent 60%),
    var(--dm2-surface-sunken);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.06);
}

html.dark .coverage-track {
  background: rgba(148, 180, 220, 0.12);
}

html.dark .fill-300 {
  background: linear-gradient(90deg, rgba(64, 156, 255, 0.9), rgba(96, 175, 255, 0.98));
}

html.dark .fill-500 {
  background: linear-gradient(90deg, rgba(64, 156, 255, 0.42), rgba(96, 175, 255, 0.52));
}

html.dark .operator-table {
  scrollbar-color: rgba(148, 180, 220, 0.28) transparent;
}

html.dark .operator-table::-webkit-scrollbar-thumb {
  background: rgba(148, 180, 220, 0.28);
}

html.dark .operator-table-row:not(.operator-table-head):hover {
  background: rgba(64, 156, 255, 0.09);
}
</style>
