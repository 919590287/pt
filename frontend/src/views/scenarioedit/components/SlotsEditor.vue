<template>
  <div class="slots-editor">
    <div class="slots-head">
      <span>发车时段表</span>
      <el-button link size="small" @click="addRow">+ 加时段</el-button>
    </div>
    <div v-for="(slot, i) in model" :key="i" class="slot-row">
      <el-time-select v-model="slot.from" start="00:00" end="26:00" step="00:15" size="small" class="time" placeholder="起" />
      <span class="sep">—</span>
      <el-time-select v-model="slot.to" start="00:00" end="26:00" step="00:15" size="small" class="time" placeholder="止" />
      <el-input-number v-model="slot.headwayMin" :min="1" :max="120" size="small" class="headway" />
      <span class="unit">分/班</span>
      <el-button v-if="model.length > 1" link type="danger" size="small" @click="removeRow(i)">删</el-button>
    </div>
    <p v-for="error in errors" :key="error" class="slot-error">{{ error }}</p>
  </div>
</template>

<script setup>
import { computed } from "vue";
import { validateSlots } from "../utils";

const model = defineModel({ type: Array, default: () => [] });
const errors = computed(() => validateSlots(model.value));

function addRow() {
  const last = model.value[model.value.length - 1];
  model.value.push({ from: last?.to || "06:30", to: "22:00", headwayMin: last?.headwayMin || 10 });
}

function removeRow(i) {
  model.value.splice(i, 1);
}
</script>

<style lang="scss" scoped>
.slots-editor {
  display: flex;
  flex-direction: column;
  gap: 6px;

  .slots-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-size: 12px;
    font-weight: 700;
  }

  .slot-row {
    display: flex;
    align-items: center;
    gap: 4px;

    .time { width: 92px; }
    .sep { color: #94a3b8; }
    .headway { width: 96px; }
    .unit { font-size: 11px; color: #6b7789; white-space: nowrap; }
  }

  .slot-error {
    margin: 0;
    color: var(--dm2-delete, #c4291c);
    font-size: 11px;
    line-height: 1.45;
  }
}

/* ── 暗色模式（html.dark，跟随底图选择） ── */
html.dark .slots-editor .slot-row .unit {
  color: #94a3b8;
}
</style>
