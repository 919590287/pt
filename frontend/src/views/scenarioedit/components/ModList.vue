<template>
  <div class="mod-list">
    <div v-if="store.editCount" class="mod-head">
      <span class="save-state" :class="store.saveState">{{ saveStateText }}</span>
      <el-button v-if="store.saveState === 'error'" link type="primary" size="small" @click="retrySave">重试保存</el-button>
      <el-button link type="danger" size="small" @click="clearAll">清空</el-button>
    </div>

    <div v-if="!store.draft.edits.length" class="edit-empty">
      <span class="ee-icon">
        <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M12 20h9"></path>
          <path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4 12.5-12.5z"></path>
        </svg>
      </span>
      <strong>还没有任何修改</strong>
      <p>在左侧「线网编辑」中新增或修改线路、站点、路网，每一项都会逐条列在这里，可随时撤销。</p>
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
          <button v-if="canRevise(edit)" class="op-edit" type="button" title="编辑该项" @click.stop="$emit('revise-edit', edit)">编辑</button>
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

defineEmits(["hover-edit", "revise-edit"]);

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

function canRevise(edit) {
  return ["route.replace", "stop.add", "stop.move"].includes(edit.kind);
}

async function retrySave() {
  const saved = await store.saveDraftNow();
  if (saved) ElMessage.success("草稿已保存");
  else ElMessage.error("保存仍失败，请检查网络后重试");
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
  display: flex;
  flex-direction: column;
  align-items: center;
  border: 1px dashed var(--dm2-line-strong, rgba(17, 32, 58, 0.18));
  border-radius: var(--dm2-radius, 13px);
  background: rgba(15, 23, 42, 0.02);
  padding: 24px 18px;
  text-align: center;

  .ee-icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 42px;
    height: 42px;
    margin-bottom: var(--dm2-space-3);
    border-radius: var(--dm2-radius-pill);
    color: var(--dm2-accent, #0071e3);
    background: var(--dm2-accent-weak, rgba(0, 113, 227, 0.1));
  }

  strong {
    display: block;
    font-size: var(--dm2-text-md);
    color: var(--dm2-ink, #1c2024);
    margin-bottom: var(--dm2-space-1);
  }

  p {
    margin: 0;
    max-width: 30ch;
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
  padding: var(--dm2-space-3) 78px var(--dm2-space-3) var(--dm2-space-3);
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

  .op-edit {
    position: absolute;
    top: 8px;
    right: 34px;
    height: 22px;
    padding: 0 6px;
    border: 1px solid var(--dm2-line, rgba(17, 32, 58, 0.1));
    border-radius: 6px;
    background: var(--dm2-surface, #fbfdff);
    color: var(--dm2-accent, #0071e3);
    cursor: pointer;
    font-size: 10px;

    &:hover { border-color: rgba(0, 113, 227, 0.3); background: rgba(0, 113, 227, 0.06); }
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

/* ── 暗色模式（html.dark，跟随底图选择） ── */
html.dark .edit-empty {
  background: rgba(148, 180, 220, 0.06);
}

html.dark .edit-operation-list {
  scrollbar-color: rgba(148, 180, 220, 0.3) transparent;
}
html.dark .edit-operation-list::-webkit-scrollbar-thumb {
  background: rgba(148, 180, 220, 0.26);
}

html.dark .edit-operation-item:hover {
  background: rgba(148, 180, 220, 0.06);
}

html.dark .edit-operation-item .op-undo {
  background: #1a2431;
}
html.dark .edit-operation-item .op-undo:hover {
  border-color: rgba(255, 122, 110, 0.45);
}

html.dark .edit-operation-item .op-edit:hover {
  border-color: rgba(64, 156, 255, 0.34);
  background: rgba(64, 156, 255, 0.1);
}
</style>
