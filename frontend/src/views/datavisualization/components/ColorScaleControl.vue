<script setup>
/**
 * QGIS 风格分级色阶控制器（需求13）：
 * - 5 种色系可选（预览色带）
 * - 同一色系内档数可调（2~9 档）
 * - 每档阈值用"占最大值的百分比"调节
 * - 底部渲染图例（可传 formatValue 把百分比换算为实际数值文案）
 * v-model 值形如 { schemeKey, classCount, thresholds }（见 utils/colorSchemes.js createColorScaleConfig）
 */
import { computed } from "vue";
import {
  COLOR_SCHEMES,
  MAX_CLASS_COUNT,
  MIN_CLASS_COUNT,
  buildLegendItems,
  defaultThresholds,
  resolveColorScale,
  sampleScheme,
} from "@/utils/colorSchemes.js";

const props = defineProps({
  modelValue: { type: Object, required: true },
  // 可选：把百分比断点换算成实际数值文案，如 (p) => `${Math.round((p / 100) * maxFlow)} 人次`
  formatValue: { type: Function, default: null },
  // 图例标题（如 "站间OD客流"、"断面客流"）
  legendTitle: { type: String, default: "" },
  showLegend: { type: Boolean, default: true },
});

const emit = defineEmits(["update:modelValue"]);

const resolved = computed(() => resolveColorScale(props.modelValue));

const legendItems = computed(() =>
  buildLegendItems(resolved.value.colors, resolved.value.thresholds, props.formatValue || undefined, resolved.value.widths),
);

// 图例条形按档位线宽系数展示粗细（低→高逐档变粗）
function legendLineHeight(width) {
  const factor = Number(width) || 1;
  return `${Math.round(3 + (factor - 1) * 5)}px`;
}

function patch(partial) {
  emit("update:modelValue", { ...props.modelValue, ...partial });
}

function handleSchemeSelect(schemeKey) {
  patch({ schemeKey });
}

function toggleReverse() {
  patch({ reverse: !props.modelValue.reverse });
}

function handleClassCountChange(count) {
  const classCount = Math.max(MIN_CLASS_COUNT, Math.min(MAX_CLASS_COUNT, Number(count) || MIN_CLASS_COUNT));
  patch({ classCount, thresholds: defaultThresholds(classCount) });
}

function handleThresholdInput(index, raw) {
  const thresholds = [...resolved.value.thresholds];
  let value = Number(raw);
  if (!Number.isFinite(value)) return;
  // 保持严格递增：夹在相邻断点之间
  const low = index === 0 ? 0.1 : thresholds[index - 1] + 0.1;
  const high = index === thresholds.length - 1 ? 99.9 : thresholds[index + 1] - 0.1;
  value = Math.max(low, Math.min(high, value));
  thresholds[index] = Math.round(value * 10) / 10;
  patch({ thresholds });
}

function schemePreview(scheme) {
  return `linear-gradient(to right, ${sampleScheme(scheme.key, 7, props.modelValue.reverse).join(",")})`;
}
</script>

<template>
  <div class="color-scale-control">
    <div class="csc-scheme-head">
      <span class="csc-label">色系</span>
      <button
        type="button"
        class="csc-reverse-btn"
        :class="{ active: modelValue.reverse }"
        title="反转色带方向"
        @click="toggleReverse"
      >
        <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <polyline points="17 1 21 5 17 9"></polyline>
          <path d="M3 11V9a4 4 0 0 1 4-4h14"></path>
          <polyline points="7 23 3 19 7 15"></polyline>
          <path d="M21 13v2a4 4 0 0 1-4 4H3"></path>
        </svg>
        反向
      </button>
    </div>
    <div class="csc-row csc-schemes">
      <button
        v-for="scheme in COLOR_SCHEMES"
        :key="scheme.key"
        type="button"
        class="csc-scheme"
        :class="{ active: modelValue.schemeKey === scheme.key }"
        :title="scheme.name"
        @click="handleSchemeSelect(scheme.key)"
      >
        <span class="csc-scheme-ramp" :style="{ background: schemePreview(scheme) }"></span>
        <span class="csc-scheme-name">{{ scheme.name }}</span>
      </button>
    </div>

    <div class="csc-row csc-count">
      <span class="csc-label">分级数</span>
      <el-slider
        :model-value="resolved.colors.length"
        :min="MIN_CLASS_COUNT"
        :max="MAX_CLASS_COUNT"
        :step="1"
        show-stops
        size="small"
        @update:model-value="handleClassCountChange"
      />
      <span class="csc-count-value">{{ resolved.colors.length }} 档</span>
    </div>

    <div class="csc-thresholds">
      <div v-for="(threshold, index) in resolved.thresholds" :key="index" class="csc-threshold-row">
        <span class="csc-threshold-swatch" :style="{ background: resolved.colors[index] }"></span>
        <span class="csc-label">档{{ index + 1 }}/档{{ index + 2 }} 分位</span>
        <el-input-number
          :model-value="threshold"
          :min="0.1"
          :max="99.9"
          :step="1"
          :precision="1"
          size="small"
          controls-position="right"
          @update:model-value="(value) => handleThresholdInput(index, value)"
        />
        <span class="csc-unit">%</span>
      </div>
    </div>

    <div v-if="showLegend" class="csc-legend">
      <div v-if="legendTitle" class="csc-legend-title">{{ legendTitle }}</div>
      <div v-for="(item, index) in legendItems" :key="index" class="csc-legend-item">
        <span class="csc-legend-line" :style="{ background: item.color, height: legendLineHeight(item.width) }"></span>
        <span class="csc-legend-label">{{ item.label }}</span>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.color-scale-control {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 12px;
  color: #475467;
}

.csc-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.csc-scheme-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.csc-reverse-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  font-size: 11px;
  color: #667085;
  border: 1px solid rgba(21, 105, 222, 0.24);
  border-radius: 999px;
  background: transparent;
  cursor: pointer;

  &.active {
    color: #1569de;
    border-color: #1569de;
    background: rgba(21, 105, 222, 0.1);
  }
}

.csc-schemes {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 4px;
  max-height: 148px;
  overflow-y: auto;
  padding: 2px;
}

.csc-scheme {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 3px 2px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  cursor: pointer;

  &.active {
    border-color: #1569de;
    background: rgba(21, 105, 222, 0.08);
  }

  .csc-scheme-ramp {
    width: 100%;
    height: 10px;
    border-radius: 3px;
    border: 1px solid rgba(0, 0, 0, 0.08);
  }

  .csc-scheme-name {
    font-size: 10px;
    line-height: 1.1;
    color: #667085;
    text-align: center;
    word-break: break-all;
  }
}

.csc-count {
  .el-slider {
    flex: 1;
    margin: 0 6px;
  }

  .csc-count-value {
    min-width: 34px;
    text-align: right;
    color: #1569de;
    font-weight: 600;
  }
}

.csc-label {
  white-space: nowrap;
  color: #667085;
}

.csc-thresholds {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 140px;
  overflow-y: auto;
}

.csc-threshold-row {
  display: flex;
  align-items: center;
  gap: 6px;

  .csc-threshold-swatch {
    width: 12px;
    height: 12px;
    border-radius: 3px;
    border: 1px solid rgba(0, 0, 0, 0.08);
    flex: none;
  }

  :deep(.el-input-number) {
    width: 92px;
  }

  .csc-unit {
    color: #98a2b3;
  }
}

.csc-legend {
  border-top: 1px dashed rgba(21, 105, 222, 0.18);
  padding-top: 6px;
  display: flex;
  flex-direction: column;
  gap: 3px;

  .csc-legend-title {
    font-weight: 600;
    color: #344054;
    margin-bottom: 2px;
  }

  .csc-legend-item {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .csc-legend-line {
    width: 22px;
    height: 6px;
    border-radius: 3px;
    flex: none;
  }

  .csc-legend-label {
    color: #475467;
  }
}
</style>
