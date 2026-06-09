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

    <!-- 详情：站点覆盖率（同心圆环） -->
    <div class="metric-card coverage-card">
      <div class="card-title-row">
        <span class="card-title">站点覆盖率分析</span>
      </div>
      <div class="coverage-dial-wrap">
        <div class="coverage-dial">
          <svg :viewBox="`0 0 ${dial.size} ${dial.size}`" aria-hidden="true" focusable="false">
            <circle class="dial-track" fill="none" :cx="dial.center" :cy="dial.center" :r="dial.outer.radius" :stroke-width="dial.outer.stroke" />
            <circle class="dial-track" fill="none" :cx="dial.center" :cy="dial.center" :r="dial.inner.radius" :stroke-width="dial.inner.stroke" />
            <circle
              class="dial-arc arc-500"
              fill="none"
              stroke-linecap="round"
              :cx="dial.center"
              :cy="dial.center"
              :r="dial.outer.radius"
              :stroke-width="dial.outer.stroke"
              :stroke-dasharray="`${dial.outer.dash} ${dial.outer.circumference}`"
              :transform="`rotate(-90 ${dial.center} ${dial.center})`"
            />
            <circle
              class="dial-arc arc-300"
              fill="none"
              stroke-linecap="round"
              :cx="dial.center"
              :cy="dial.center"
              :r="dial.inner.radius"
              :stroke-width="dial.inner.stroke"
              :stroke-dasharray="`${dial.inner.dash} ${dial.inner.circumference}`"
              :transform="`rotate(-90 ${dial.center} ${dial.center})`"
            />
          </svg>
        </div>
        <ul class="coverage-legend">
          <li class="legend-item">
            <span class="legend-dot dot-300"></span>
            <span class="legend-label">公交站点300米覆盖率</span>
            <strong class="legend-value">{{ fmtPct(stats.stationCoverage300Rate) }}</strong>
          </li>
          <li class="legend-item">
            <span class="legend-dot dot-500"></span>
            <span class="legend-label">公交站点500米覆盖率</span>
            <strong class="legend-value">{{ fmtPct(stats.stationCoverage500Rate) }}</strong>
          </li>
        </ul>
      </div>
    </div>

    <!-- 详情：企业线路统计 -->
    <div class="metric-card operator-table-card">
      <div class="card-title-row">
        <span class="card-title">企业线路统计</span>
      </div>
      <div class="operator-table">
        <div class="operator-table-row operator-table-head">
          <span>企业</span>
          <span>线路数量</span>
        </div>
        <div v-for="row in operatorRows" :key="row.company" class="operator-table-row">
          <span>{{ row.company }}</span>
          <strong>{{ row.lineCount }}</strong>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  stats: { type: Object, required: true }, // overviewStats
  dial: { type: Object, required: true }, // coverageDial 几何
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
  box-shadow: none;
}

/* ① 主指标 */
.hero-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 16px 18px;
  background: var(--dm2-surface-sunken);
}

.hero-label {
  color: var(--dm2-muted);
  font-size: 12.5px;
  font-weight: 600;
}

.hero-value {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.hero-num {
  font-size: 40px;
  line-height: 1;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--dm2-ink);
  font-variant-numeric: tabular-nums;
}

.hero-unit {
  font-size: 15px;
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
  font-size: 19px;
  line-height: 1;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--dm2-ink);
  font-variant-numeric: tabular-nums;
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
  color: var(--dm2-muted);
  font-size: 12.5px;
  font-weight: 600;
}

/* 覆盖率卡 + 同心圆环 */
.coverage-card {
  padding: 14px 16px;
}

.coverage-dial-wrap {
  display: flex;
  align-items: center;
  gap: 16px;
}

.coverage-dial {
  flex-shrink: 0;
  width: 88px;
  height: 88px;
}

.coverage-dial svg {
  display: block;
  width: 100%;
  height: 100%;
}

.coverage-dial .dial-track {
  stroke: rgba(15, 23, 42, 0.08);
}

.coverage-dial .dial-arc.arc-500 {
  stroke: var(--dm2-accent);
}

.coverage-dial .dial-arc.arc-300 {
  stroke: var(--dm2-add);
}

.coverage-legend {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 11px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.legend-dot {
  flex-shrink: 0;
  width: 9px;
  height: 9px;
  border-radius: 999px;
}

.legend-dot.dot-300 {
  background: var(--dm2-add);
}

.legend-dot.dot-500 {
  background: var(--dm2-accent);
}

.legend-label {
  flex: 1;
  min-width: 0;
  color: var(--dm2-ink-soft);
  font-size: 12.5px;
  font-weight: 500;
}

.legend-value {
  color: var(--dm2-ink);
  font-size: 15px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

/* 企业线路统计表 */
.operator-table-card {
  padding: 12px 14px;
}

.operator-table {
  max-height: min(172px, 26vh);
  overflow-y: auto;
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
  grid-template-columns: minmax(0, 1fr) 82px;
  min-height: 30px;
  align-items: center;
  border-bottom: 1px solid var(--dm2-line-faint);
}

.operator-table-row:last-child {
  border-bottom: none;
}

.operator-table-row span,
.operator-table-row strong {
  padding: 7px 10px;
  font-size: 12px;
  color: var(--dm2-ink-soft);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.operator-table-row strong {
  color: var(--dm2-ink);
  font-weight: 600;
  text-align: right;
  font-variant-numeric: tabular-nums;
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
  font-weight: 600;
}

.operator-table-head span:last-child {
  text-align: right;
}
</style>
