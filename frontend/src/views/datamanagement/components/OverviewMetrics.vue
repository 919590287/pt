<template>
  <div class="overview-metric-list">
    <!-- 主指标：线网总规模（唯一视觉焦点） -->
    <div class="metric-card hero-card">
      <span class="hero-label">线网总规模</span>
      <span class="hero-value">
        <strong class="hero-num">{{ fmtUnit(stats.networkScaleKm, "") }}</strong>
        <span class="hero-unit">km</span>
      </span>
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
          <span class="operator-number">车辆数</span>
          <span class="operator-number">配车占比</span>
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
          <strong class="operator-number operator-share">{{ row.vehicleShare }}</strong>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  stats: { type: Object, required: true }, // overviewStats
  operatorRows: { type: Array, default: () => [] },
  // 纯格式化函数，由父级注入以保持单一来源
  fmtInt: { type: Function, required: true },
  fmtUnit: { type: Function, required: true },
  fmtPct: { type: Function, required: true },
});
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
  transition:
    border-color var(--dm2-dur) var(--dm2-ease),
    box-shadow var(--dm2-dur) var(--dm2-ease),
    transform var(--dm2-dur-fast) var(--dm2-ease);
}

/* ① 主指标 —— 唯一视觉焦点：沉底磨砂 + 极淡蓝色数据辉光 */
.hero-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding: 18px 20px;
  overflow: hidden;
  background:
    radial-gradient(120% 140% at 100% 0%, rgba(0, 113, 227, 0.09), transparent 58%),
    var(--dm2-surface-sunken);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.7);
}

.hero-card::after {
  content: "";
  position: absolute;
  left: 0;
  top: 16px;
  bottom: 16px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--dm2-accent-grad);
  opacity: 0.9;
}

.hero-label {
  color: var(--dm2-muted);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.hero-value {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.hero-num {
  font-family: var(--dm2-font-num);
  font-size: 44px;
  line-height: 0.98;
  font-weight: 700;
  letter-spacing: -0.035em;
  color: var(--dm2-ink);
  font-variant-numeric: tabular-nums;
  font-feature-settings: "tnum" 1;
}

.hero-unit {
  font-size: 14px;
  font-weight: 600;
  color: var(--dm2-muted);
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

/* 企业线路统计表 */
.operator-table-card {
  padding: 12px 14px;
}

.operator-table {
  max-height: min(172px, 26vh);
  overflow: auto;
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
  grid-template-columns: minmax(112px, 1.7fr) repeat(4, minmax(48px, 0.75fr));
  min-height: 38px;
  align-items: center;
  border-bottom: 1px solid var(--dm2-line-faint);
  border-radius: 7px;
  transition: background-color var(--dm2-dur) var(--dm2-ease);
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

.operator-table-row .operator-company {
  padding-inline: 8px;
}

.operator-table-row .operator-number:last-child {
  padding-inline-end: 8px;
}

.operator-table-row strong {
  color: var(--dm2-ink);
  font-family: var(--dm2-font-num);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  font-feature-settings: "tnum" 1;
}

.operator-company {
  text-align: center;
}

.operator-number {
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
  font-size: 11.5px;
  font-weight: 600;
}

.operator-table-head span:last-child {
  text-align: center;
}
</style>
