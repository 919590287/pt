<!--
  统计时段 / 统计间隔筛选条：样式对齐换乘分析面板头部（标题行 + 滑杆与分段按钮同排）。
  纯展示组件：range/unit 双 v-model 直通宿主，步进对齐、防抖镜像等口径逻辑留在宿主侧；
  不绑 unit 时只渲染滑杆（如站点面板按小时统计，无间隔档位）。
-->
<template>
  <div class="trf" role="group" :aria-label="label">
    <div class="trf-row">
      <span class="trf-label">{{ label }}</span>
      <span class="trf-time" aria-label="当前统计时段">{{ hourText(range[0]) }} - {{ hourText(range[1]) }}</span>
    </div>
    <div class="trf-filters">
      <el-slider
        :model-value="range"
        range
        :min="min"
        :max="max"
        :step="step"
        :show-tooltip="false"
        class="trf-slider"
        :aria-label="label"
        @update:model-value="$emit('update:range', $event)"
      />
      <el-radio-group
        v-if="unit != null"
        :model-value="unit"
        size="small"
        class="trf-pills"
        aria-label="统计间隔"
        @update:model-value="$emit('update:unit', $event)"
      >
        <el-radio-button v-for="opt in unitOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</el-radio-button>
      </el-radio-group>
    </div>
  </div>
</template>

<script setup>
defineProps({
  range: { type: Array, required: true },
  unit: { type: Number, default: null },
  min: { type: Number, default: 0 },
  max: { type: Number, default: 24 },
  step: { type: Number, default: 1 },
  label: { type: String, default: "统计时段" },
  unitOptions: {
    type: Array,
    default: () => [
      { value: 15, label: "15min" },
      { value: 30, label: "30min" },
      { value: 60, label: "1h" },
    ],
  },
});

defineEmits(["update:range", "update:unit"]);

// 与换乘分析头部同款"6:00 - 23:00"读数；15/30min 步进产生小数小时，分钟位补零
function hourText(hour) {
  const totalMinutes = Math.round((Number(hour) || 0) * 60);
  const safeMinutes = Math.max(0, Math.min(24 * 60, totalMinutes));
  const hours = Math.floor(safeMinutes / 60);
  const minutes = safeMinutes % 60;
  return `${hours}:${String(minutes).padStart(2, "0")}`;
}
</script>

<style lang="scss" scoped>
/* 数值逐条对齐换乘分析 index.vue 的 ta-head/ta-head-row/ta-head-filters，勿改出偏差 */
.trf {
  flex: 0 0 auto;
  padding: var(--dm2-space-2) 0 var(--dm2-space-3);
  border-bottom: 1px solid var(--dm2-line-faint);
}
.trf-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.trf-label {
  font-size: 16px;
  font-weight: 600;
  color: var(--dm2-ink);
  white-space: nowrap;
}
.trf-time {
  font-size: 12px;
  color: var(--dm2-ink-soft);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.trf-filters {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 6px;
}
.trf-slider {
  flex: 1 1 auto;
  min-width: 0;
  padding: 0 6px;
}
.trf-pills {
  flex: 0 0 auto;
}
</style>
