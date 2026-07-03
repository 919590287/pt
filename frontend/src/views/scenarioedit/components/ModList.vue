<template>
  <div class="mod-list">
    <div class="list-head">
      <span class="title">修改清单（{{ store.editCount }}）</span>
      <span class="save-state" :class="store.saveState">{{ saveStateText }}</span>
      <el-button v-if="store.editCount" link type="danger" size="small" @click="clearAll">清空</el-button>
    </div>

    <div v-if="!store.draft.edits.length" class="empty">
      暂无修改。在左侧「线网编辑」中操作后，每一项修改都会列在这里，可随时撤销。
    </div>

    <el-scrollbar v-else max-height="320px">
      <TransitionGroup name="mod-fade">
        <div
          v-for="edit in store.draft.edits"
          :key="edit.id"
          class="mod-card"
          @mouseenter="$emit('hover-edit', edit)"
          @mouseleave="$emit('hover-edit', null)"
        >
          <span :class="['badge', meta(edit).tone]">{{ meta(edit).icon }}</span>
          <div class="body">
            <div class="kind">{{ meta(edit).label }} <span class="group">{{ meta(edit).group }}</span></div>
            <div class="summary">{{ summary(edit) }}</div>
          </div>
          <button class="undo" title="撤销该项" @click="undo(edit)">↺</button>
        </div>
      </TransitionGroup>
    </el-scrollbar>
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

function summary(edit) {
  return editSummary(edit);
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
.mod-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.list-head {
  display: flex;
  align-items: center;
  gap: 8px;

  .title {
    font-size: 13px;
    font-weight: 750;
    flex: 1;
  }

  .save-state {
    font-size: 11px;
    color: #94a3b8;

    &.saved { color: #0f9f6e; }
    &.error { color: #dc2626; }
  }
}

.empty {
  font-size: 12px;
  color: var(--app-ink-weak, #94a3b8);
  line-height: 1.6;
  padding: 12px 8px;
  text-align: center;
  border: 1px dashed var(--app-border, #e2e8f0);
  border-radius: 10px;
}

.mod-card {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px;
  margin-bottom: 6px;
  border: 1px solid var(--app-border, #e8edf5);
  border-radius: 10px;
  background: #fff;
  transition: border-color 0.15s ease;

  &:hover {
    border-color: rgba(21, 105, 222, 0.4);
  }

  .badge {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 22px;
    height: 22px;
    border-radius: 6px;
    font-size: 12px;
    font-weight: 700;
    color: #fff;
    flex-shrink: 0;

    &.add { background: #16a34a; }
    &.modify { background: #f59e0b; }
    &.delete { background: #dc2626; }
  }

  .body {
    flex: 1;
    min-width: 0;

    .kind {
      font-size: 12px;
      font-weight: 700;

      .group {
        margin-left: 6px;
        font-size: 10px;
        font-weight: 400;
        color: #94a3b8;
      }
    }

    .summary {
      font-size: 12px;
      color: var(--app-ink-weak, #64748b);
      line-height: 1.5;
      word-break: break-all;
    }
  }

  .undo {
    flex-shrink: 0;
    border: 1px solid var(--app-border, #e2e8f0);
    background: #fff;
    border-radius: 6px;
    width: 24px;
    height: 24px;
    cursor: pointer;
    color: #64748b;

    &:hover {
      color: #dc2626;
      border-color: rgba(220, 38, 38, 0.4);
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
