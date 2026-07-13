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

    <!-- 覆盖率：常规公交站点服务范围 -->
    <div class="metric-card coverage-card">
      <div class="card-title-row coverage-title-row">
        <span class="card-title">常规公交站点覆盖率</span>
        <button
          type="button"
          class="coverage-config-btn"
          :class="{ 'is-active': coverage.usingOverride }"
          :aria-label="coverage.usingOverride ? '已按建成区面积计算，点击调整' : '设置建成区面积'"
          :title="coverage.usingOverride
            ? `按建成区面积 ${fmtUnit(coverage.builtUpAreaKm2, 'km²')} 计算，点击调整`
            : '覆盖率默认按行政区面积计算，点击设为建成区面积'"
          @click="emit('configure-coverage')"
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <circle cx="12" cy="12" r="9.2"></circle>
            <path d="M9.6 9.4a2.4 2.4 0 1 1 3.3 2.2c-.7.3-1 .8-1 1.6v.3"></path>
            <line x1="11.9" y1="16.6" x2="11.91" y2="16.6"></line>
          </svg>
        </button>
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
      <p v-if="coverage.isCapped" class="coverage-capped">服务面积已超建成区，按 100% 封顶显示</p>
    </div>

    <!-- 详情：企业线路统计 -->
    <div class="metric-card operator-table-card">
      <div class="card-title-row">
        <span class="card-title">企业线路统计</span>
      </div>
      <div class="operator-table" :class="{ 'cols-3': !showVehicleColumns }">
        <div class="operator-table-row operator-table-head">
          <span class="operator-company">企业</span>
          <span class="operator-number">线路数量</span>
          <span class="operator-number">线路占比</span>
          <span v-if="showVehicleColumns" class="operator-number">车辆数</span>
          <span v-if="showVehicleColumns" class="operator-number">配车占比</span>
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
          <strong v-if="showVehicleColumns" class="operator-number">{{ row.vehicleCount }}</strong>
          <strong v-if="showVehicleColumns" class="operator-number operator-share">{{ row.vehicleShare }}</strong>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
defineProps({
  stats: { type: Object, required: true }, // overviewStats
  operatorRows: { type: Array, default: () => [] },
  // 车辆数/配车占比两列仅在存在真实配车数据时展示；无数据时收敛为三列，避免整列占位横杠
  showVehicleColumns: { type: Boolean, default: false },
  // 覆盖率视图（当前范围的速率/分母/是否建成区覆写等），由父级计算注入
  coverage: { type: Object, required: true },
  // 纯格式化函数，由父级注入以保持单一来源
  fmtInt: { type: Function, required: true },
  fmtUnit: { type: Function, required: true },
  fmtPct: { type: Function, required: true },
});
const emit = defineEmits(["configure-coverage"]);

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

/* ① 主指标 —— 唯一视觉焦点：沉底磨砂 + 极淡蓝色数据辉光 */
.hero-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding: 18px 20px;
  overflow: hidden;
  /* 柔光锚在数字左侧后方、随内容延展，取代此前浮在右上角、与内容脱节的色块 */
  background:
    radial-gradient(90% 140% at 2% 50%, rgba(0, 113, 227, 0.07), transparent 60%),
    var(--dm2-surface-sunken);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.7);
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

/* 常规公交站点覆盖率 */
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

/* 建成区面积设置入口：极简圆形图标按钮，覆写生效时点亮为强调蓝 */
.coverage-config-btn {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--dm2-muted-soft);
  cursor: pointer;
  transition:
    color var(--dm2-dur) var(--dm2-ease),
    background-color var(--dm2-dur) var(--dm2-ease);
}

.coverage-config-btn svg {
  width: 15px;
  height: 15px;
}

.coverage-config-btn:hover {
  color: var(--dm2-accent);
  background: var(--dm2-accent-weak);
}

.coverage-config-btn:focus-visible {
  outline: 2px solid var(--dm2-accent-ring);
  outline-offset: 1px;
}

.coverage-config-btn.is-active {
  color: var(--dm2-accent);
  background: var(--dm2-accent-weak);
}

.coverage-basis {
  margin: -2px 0 0;
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--dm2-muted);
  font-size: 11px;
  font-weight: 500;
}

.coverage-basis-label {
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--dm2-field);
  color: var(--dm2-muted);
  font-size: 10px;
  font-weight: 600;
}

.coverage-capped {
  margin: 8px 0 0;
  color: var(--dm2-modify);
  font-size: 11px;
  font-weight: 500;
  line-height: 1.4;
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
  overflow: auto;
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
  grid-template-columns: minmax(112px, 1.7fr) repeat(4, minmax(48px, 0.75fr));
  min-height: 38px;
  align-items: center;
  border-bottom: 1px solid var(--dm2-line-faint);
  border-radius: 7px;
  transition: background-color var(--dm2-dur) var(--dm2-ease);
}

/* 无配车数据时收敛为三列，企业列吃掉多余宽度 */
.operator-table.cols-3 .operator-table-row {
  grid-template-columns: minmax(120px, 1.9fr) repeat(2, minmax(60px, 1fr));
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

/* 企业名左对齐便于扫读，数值右对齐让 tabular-nums 按位对齐利于比较。
   选择器带 .operator-table-row 提高特异性，压过基础规则的 justify-content: center */
.operator-table-row .operator-company {
  justify-content: flex-start;
  text-align: left;
}

.operator-table-row .operator-number {
  justify-content: flex-end;
  text-align: right;
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
</style>
