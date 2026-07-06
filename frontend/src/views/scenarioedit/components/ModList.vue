<template>
  <div class="mod-list">
    <div v-if="store.editCount" class="mod-head">
      <span class="save-state" :class="store.saveState">{{ saveStateText }}</span>
      <el-button link type="danger" size="small" @click="clearAll">清空</el-button>
    </div>

    <div v-if="!store.draft.edits.length" class="edit-empty">
      <strong>暂无修改</strong>
      <p>在左侧「线网编辑」中操作后，每一项修改都会逐条列在这里，可随时撤销。</p>
    </div>

    <div v-else class="edit-operation-list">
      <TransitionGroup name="mod-fade">
        <div
          v-for="edit in store.draft.edits"
          :key="edit.id"
          :class="['edit-operation-item', kindClass(edit)]"
          @mouseenter="$emit('hover-edit', edit)"
          @mouseleave="$emit('hover-edit', null)"
        >
          <div class="operation-labels">
            <span class="operation-dataset">{{ meta(edit).group }}</span>
            <span class="operation-type">{{ meta(edit).label }}</span>
          </div>
          <strong>{{ edit.name || meta(edit).label }}</strong>
          <div class="op-detail">
            <template v-if="odDirections(edit).length">
              <span v-for="d in odDirections(edit)" :key="d.label" class="od-line">
                {{ d.label }}：{{ d.od }} · {{ d.count }}站
              </span>
            </template>
            <span v-else>{{ summary(edit) }}</span>
          </div>
          <button class="op-undo" type="button" title="撤销该项" @click.stop="undo(edit)">↺</button>
        </div>
      </TransitionGroup>
    </div>
  </div>
</template>

<script setup>
import { computed } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { useScenarioEditStore } from "../store";
import { KIND_META, editSummary } from "../utils";

defineEmits(["hover-edit"]);

const store = useScenarioEditStore();

const saveStateText = computed(() => ({
  idle: "",
  saving: "保存中…",
  saved: "已自动保存",
  error: "保存失败",
}[store.saveState] || ""));

function meta(edit) {
  return KIND_META[edit.kind] || { label: edit.kind, group: "", icon: "·", tone: "modify" };
}

function kindClass(edit) {
  const tone = meta(edit).tone;
  if (tone === "add") return "is-add";
  if (tone === "delete") return "is-delete";
  return "is-modify";
}

function summary(edit) {
  return editSummary(edit);
}

// 新增线路：逐方向的"首发站→终点站"（正/反向都显示）
function odDirections(edit) {
  if (edit.kind !== "route.add" && edit.kind !== "route.replace") return [];
  const nameOf = (id) => store.stopIndex.get(id)?.name || id;
  return (edit.geometry?.directions || []).map((d, i) => {
    const stops = d.stops || [];
    const a = stops.length ? nameOf(stops[0]) : "?";
    const b = stops.length ? nameOf(stops[stops.length - 1]) : "?";
    return { label: i === 0 ? "正向" : "反向", od: `${a} → ${b}`, count: stops.length };
  });
}

async function undo(edit) {
  const dependents = store.findDependents(edit.id);
  if (dependents.length > 0) {
    try {
      await ElMessageBox.confirm(
        `有 ${dependents.length} 项修改依赖本项（如新线路引用了新站点），将一并撤销。`,
        "级联撤销",
        { confirmButtonText: "一并撤销", cancelButtonText: "取消", type: "warning" }
      );
    } catch {
      return;
    }
    store.removeEdits([edit.id, ...dependents.map((d) => d.id)]);
  } else {
    store.removeEdits([edit.id]);
  }
  ElMessage.info(`已撤销：${meta(edit).label} ${edit.name || ""}`);
}

async function clearAll() {
  try {
    await ElMessageBox.confirm("确定清空全部修改项？", "清空修改清单", {
      confirmButtonText: "清空",
      cancelButtonText: "取消",
      type: "warning",
    });
  } catch {
    return;
  }
  store.removeEdits(store.draft.edits.map((e) => e.id));
}
</script>

<style lang="scss" scoped>
/* 结构/样式仿数据管理"更新"面板的 edit-operation-list / edit-operation-item */
.mod-list {
  display: flex;
  flex-direction: column;
  min-height: 0;
  gap: var(--dm2-space-2);
}

.mod-head {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--dm2-space-2);

  .save-state {
    margin-right: auto;
    font-size: var(--dm2-text-xs);
    color: var(--dm2-muted-soft, #98a2b3);

    &.saved { color: var(--dm2-add, #1a8a3f); }
    &.error { color: var(--dm2-delete, #c4291c); }
  }
}

.edit-empty {
  flex-shrink: 0;
  border: 1px solid var(--dm2-line, rgba(17, 32, 58, 0.1));
  border-radius: var(--dm2-radius, 13px);
  background: rgba(15, 23, 42, 0.02);
  padding: 18px 16px;
  text-align: center;

  strong {
    display: block;
    font-size: var(--dm2-text-base);
    color: var(--dm2-ink, #1c2024);
    margin-bottom: var(--dm2-space-1);
  }

  p {
    margin: 0;
    font-size: var(--dm2-text-sm);
    line-height: 1.6;
    color: var(--dm2-muted, #667085);
  }
}

.edit-operation-list {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: var(--dm2-space-2);
  padding-right: 4px;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: thin;
  scrollbar-color: rgba(15, 23, 42, 0.2) transparent;

  &::-webkit-scrollbar { width: 5px; }
  &::-webkit-scrollbar-thumb { background: rgba(15, 23, 42, 0.16); border-radius: 999px; }
}

.edit-operation-item {
  position: relative;
  flex-shrink: 0;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  grid-template-areas:
    "type title"
    "type detail";
  align-items: start;
  gap: var(--dm2-space-1) var(--dm2-space-3);
  padding: var(--dm2-space-3) 34px var(--dm2-space-3) var(--dm2-space-3);
  border: 1px solid var(--dm2-line, rgba(17, 32, 58, 0.1));
  border-radius: var(--dm2-radius, 13px);
  background: var(--dm2-surface, #ffffff);
  --k-color: var(--dm2-accent, #0071e3);
  transition: border-color 120ms ease, background-color 120ms ease;

  &.is-add { --k-color: var(--dm2-add, #1a8a3f); }
  &.is-modify { --k-color: var(--dm2-modify, #b06a00); }
  &.is-delete { --k-color: var(--dm2-delete, #c4291c); }

  &:hover {
    border-color: var(--dm2-line-strong, rgba(17, 32, 58, 0.18));
    background: rgba(15, 23, 42, 0.02);
  }

  .operation-labels {
    grid-area: type;
    display: grid;
    gap: 3px;
    min-width: 44px;
  }

  .operation-dataset {
    color: var(--dm2-muted-soft, #98a2b3);
    font-size: var(--dm2-text-xs);
    font-weight: var(--dm2-fw-semibold);
    line-height: 1.2;
  }

  .operation-type {
    color: var(--k-color);
    font-size: var(--dm2-text-sm);
    font-weight: var(--dm2-fw-bold);
    line-height: 1.2;
  }

  strong {
    grid-area: title;
    min-width: 0;
    color: var(--dm2-ink, #1c2024);
    font-size: var(--dm2-text-md);
    line-height: 1.4;
    font-weight: var(--dm2-fw-bold);
    overflow-wrap: anywhere;
  }

  .op-detail {
    grid-area: detail;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 2px;
    color: var(--dm2-muted, #667085);
    font-size: var(--dm2-text-sm);
    line-height: 1.55;
    overflow-wrap: anywhere;

    .od-line {
      color: var(--dm2-ink-soft, #3b4452);
    }
  }

  .op-undo {
    position: absolute;
    top: 8px;
    right: 8px;
    width: 22px;
    height: 22px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    border: 1px solid var(--dm2-line, rgba(17, 32, 58, 0.1));
    border-radius: 6px;
    background: #fff;
    color: var(--dm2-muted, #667085);
    cursor: pointer;
    font-size: var(--dm2-text-base);
    line-height: 1;

    &:hover {
      color: var(--dm2-delete, #c4291c);
      border-color: rgba(196, 41, 28, 0.4);
    }
  }
}

.mod-fade-enter-active,
.mod-fade-leave-active {
  transition: all 0.2s ease;
}

.mod-fade-enter-from,
.mod-fade-leave-to {
  opacity: 0;
  transform: translateX(8px);
}
</style>
