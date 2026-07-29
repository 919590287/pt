<template>
  <el-dialog
    :model-value="model.visible"
    :title="model.title"
    width="min(1100px, calc(100vw - 48px))"
    append-to-body
    align-center
    draggable
    class="dm-attribute-dialog"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="$emit('update:visible', $event)"
  >
    <div v-if="model.datasetType === 'line' && model.route" class="attribute-dialog-head">
      <div class="attribute-dialog-tools">
        <el-button
          size="small"
          @click="$emit('toggle-route')"
        >
          {{ model.showRouteStations ? "返回线路属性" : "查看全线站点" }}
        </el-button>
      </div>
    </div>

    <div v-if="!model.rows.length" class="attribute-records-empty">
      {{ isRouteStationMode ? "当前线路暂无站点" : "未找到可编辑的属性记录" }}
    </div>
    <div v-else class="attr-table-scroll">
      <table class="attr-table">
        <thead>
          <tr>
            <th
              v-for="column in model.columns"
              :key="column.key"
              :class="{ 'col-wide': column.wide }"
            >
              {{ column.label }}
            </th>
            <th class="col-act">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in model.rows"
            :key="row.rowId"
            :class="[
              `state-${stateKey(row)}`,
              {
                'is-dragging': draggingRowId === row.rowId,
                'is-drag-over-before': dragOverRowId === row.rowId && dragOverPosition === 'before',
                'is-drag-over-after': dragOverRowId === row.rowId && dragOverPosition === 'after',
              },
            ]"
            @dragover.prevent="handleRowDragOver($event, row)"
            @dragleave="handleRowDragLeave($event, row)"
            @drop.prevent="handleRowDrop($event, row)"
          >
            <td
              v-for="column in model.columns"
              :key="column.key"
              :class="{ 'col-wide': column.wide }"
            >
              <template v-if="isRouteSequenceColumn(column)">
                <div class="route-sequence-cell">
                  <button
                    type="button"
                    class="route-drag-handle"
                    :disabled="row.status === 'deleted'"
                    draggable="true"
                    :aria-label="`拖动调整${row.properties.stop_name || '站点'}站序`"
                    title="拖动调整站序，上下方向键也可调整"
                    @dragstart="handleRowDragStart($event, row)"
                    @dragend="clearDragState"
                    @keydown.up.prevent="handleRowKeyMove(row, -1)"
                    @keydown.down.prevent="handleRowKeyMove(row, 1)"
                  >
                    <svg viewBox="0 0 16 16" width="14" height="14" aria-hidden="true">
                      <circle cx="5" cy="3" r="1"></circle>
                      <circle cx="11" cy="3" r="1"></circle>
                      <circle cx="5" cy="8" r="1"></circle>
                      <circle cx="11" cy="8" r="1"></circle>
                      <circle cx="5" cy="13" r="1"></circle>
                      <circle cx="11" cy="13" r="1"></circle>
                    </svg>
                  </button>
                  <span>{{ row.properties.seq || "—" }}</span>
                </div>
              </template>
              <el-input
                v-else
                v-model="row.properties[column.key]"
                size="small"
                :disabled="row.status === 'deleted' || isReadOnlyColumn(row, column)"
                @input="$emit('touch-row', row)"
              />
            </td>
            <td class="col-act">
              <button
                type="button"
                class="record-action-btn"
                :class="{ 'is-restore': row.status === 'deleted' }"
                @click="row.status === 'deleted' ? $emit('restore-row', row) : $emit('remove-row', row)"
              >
                {{ row.status === 'deleted' ? '撤销' : '删除' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <section v-if="['station', 'line', 'depot'].includes(model.datasetType)" class="station-history" :aria-label="`${datasetLabel}历史修改记录`">
      <div class="station-history-title">
        <span>历史修改记录</span>
        <small v-if="model.historyRows.length">{{ model.historyRows.length }} 条</small>
      </div>
      <div v-if="model.historyLoading" class="station-history-state">加载中</div>
      <div v-else-if="model.historyError" class="station-history-state">{{ model.historyError }}</div>
      <div v-else-if="!model.historyRows.length" class="station-history-state">暂无修改记录</div>
      <div v-else class="history-table-scroll">
        <table class="attr-table history-table">
          <thead>
            <tr>
              <th
                v-for="column in historyColumns"
                :key="column.key"
                :class="{ 'col-wide': column.wide }"
              >
                {{ column.label }}
              </th>
              <th class="history-user-column">修改人</th>
              <th class="history-time-column">修改时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="record in model.historyRows" :key="record.key">
              <td
                v-for="column in historyColumns"
                :key="column.key"
                class="history-data-cell"
                :class="[
                  { 'col-wide': column.wide },
                  record.changedKeys.includes(column.key) ? 'is-changed' : 'is-muted',
                ]"
              >
                {{ record.values[column.key] || "—" }}
              </td>
              <td class="history-meta-cell">{{ record.username }}</td>
              <td class="history-meta-cell history-time-column">
                <time>{{ formatTime(record.committedAt) }}</time>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <template #footer>
      <div class="attribute-footer">
        <div>
          <el-button size="small" :disabled="!changedCount" @click="$emit('reset')">重置</el-button>
          <el-button @click="$emit('update:visible', false)">关闭</el-button>
          <el-button type="primary" :disabled="!changedCount" @click="$emit('apply')">生成修改项</el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, ref } from "vue";

const props = defineProps({
  // attributeTable 响应式对象，按引用传入：子组件内对 row.properties 的修改会同步回父级
  model: { type: Object, required: true },
  changedCount: { type: Number, default: 0 },
  // 纯展示用的派生函数（依赖父级数据），以函数形式注入，子组件保持“哑”组件
  stateKey: { type: Function, required: true },
  statusLabel: { type: Function, required: true },
  recordTitle: { type: Function, required: true },
  formatTime: { type: Function, required: true },
});

const emit = defineEmits([
  "update:visible",
  "toggle-route",
  "reorder-row",
  "reset",
  "remove-row",
  "restore-row",
  "touch-row",
  "apply",
]);

const isRouteStationMode = computed(() =>
  props.model.datasetType === "line" && props.model.scope === "route",
);
const historyColumns = computed(() =>
  Array.isArray(props.model.historyColumns) && props.model.historyColumns.length
    ? props.model.historyColumns
    : props.model.columns,
);
const datasetLabel = computed(() => {
  if (props.model.datasetType === "line") return "线路";
  if (props.model.datasetType === "depot") return "场站";
  return "站点";
});
const draggingRowId = ref("");
const dragOverRowId = ref("");
const dragOverPosition = ref("before");
const derivedFields = new Set(["len_km", "directness", "stop_count", "avg_stop_m", "route_cnt"]);

function isRouteSequenceColumn(column) {
  return isRouteStationMode.value && column.key === "seq";
}

function isReadOnlyColumn(row, column) {
  if (isRouteStationMode.value) {
    return true;
  }
  if (derivedFields.has(column.key)) {
    return true;
  }
  return props.model.viewDatasetType === "station" && (column.key === "lon" || column.key === "lat");
}

function handleRowDragStart(event, row) {
  if (!isRouteStationMode.value || row.status === "deleted") {
    event.preventDefault();
    return;
  }
  draggingRowId.value = row.rowId;
  event.dataTransfer.effectAllowed = "move";
  event.dataTransfer.setData("text/plain", row.rowId);
  const tableRow = event.currentTarget.closest("tr");
  if (tableRow) {
    event.dataTransfer.setDragImage(tableRow, 24, Math.min(tableRow.offsetHeight / 2, 24));
  }
}

function handleRowDragOver(event, row) {
  if (!draggingRowId.value || row.status === "deleted" || row.rowId === draggingRowId.value) {
    dragOverRowId.value = "";
    return;
  }
  const bounds = event.currentTarget.getBoundingClientRect();
  dragOverRowId.value = row.rowId;
  dragOverPosition.value = event.clientY >= bounds.top + bounds.height / 2 ? "after" : "before";
  event.dataTransfer.dropEffect = "move";
}

function handleRowDragLeave(event, row) {
  if (dragOverRowId.value !== row.rowId) return;
  const relatedTarget = event.relatedTarget;
  if (relatedTarget instanceof Node && event.currentTarget.contains(relatedTarget)) return;
  dragOverRowId.value = "";
}

function handleRowKeyMove(row, direction) {
  const activeRows = props.model.rows.filter((item) => item.status !== "deleted");
  const sourceIndex = activeRows.findIndex((item) => item.rowId === row.rowId);
  const targetRow = activeRows[sourceIndex + direction];
  if (!targetRow) return;
  emit("reorder-row", row.rowId, targetRow.rowId, direction < 0 ? "before" : "after");
}

function handleRowDrop(event, row) {
  const sourceRowId = draggingRowId.value || event.dataTransfer.getData("text/plain");
  if (sourceRowId && sourceRowId !== row.rowId && row.status !== "deleted") {
    emit("reorder-row", sourceRowId, row.rowId, dragOverPosition.value);
  }
  clearDragState();
}

function clearDragState() {
  draggingRowId.value = "";
  dragOverRowId.value = "";
  dragOverPosition.value = "before";
}
</script>

<style lang="scss" scoped>
/* 线路/站点/场站属性表弹窗 —— 冷静专业的表格式批量编辑 */
:global(.dm-attribute-dialog.el-dialog),
:global(.dm-attribute-dialog .el-dialog) {
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 64px);
  border-radius: var(--dm2-radius-xl);
  background: var(--dm2-surface);
  box-shadow: var(--dm2-shadow-dialog);
  overflow: hidden;
}

:global(.dm-attribute-dialog .el-dialog__header) {
  position: relative;
  margin: 0;
  padding: 24px 28px 18px;
  border-bottom: 1px solid var(--dm2-line-faint);
}

:global(.dm-attribute-dialog .el-dialog__title) {
  color: var(--dm2-ink);
  font-size: 19px;
  font-weight: 700;
  letter-spacing: -0.015em;
}

:global(.dm-attribute-dialog .el-dialog__headerbtn) {
  position: absolute !important;
  top: 18px !important;
  right: 20px !important;
  left: auto !important;
  width: 32px;
  height: 32px;
  margin: 0;
  border: none;
  border-radius: var(--dm2-radius-sm);
  background: transparent;
  transition: background 160ms ease;
}

:global(.dm-attribute-dialog .el-dialog__headerbtn:hover) {
  background: rgba(15, 23, 42, 0.06);
}

:global(.dm-attribute-dialog .el-dialog__close) {
  color: #6e6e73;
  font-size: 18px;
}

:global(.dm-attribute-dialog .el-dialog__body) {
  padding: 22px 28px 24px;
  overflow: visible;
}

:global(.dm-attribute-dialog .el-dialog__footer) {
  flex: 0 0 auto;
  padding: 16px 28px 22px;
  border-top: 1px solid var(--dm2-line-faint);
}

.attribute-dialog-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;

  p {
    margin: 0 0 4px;
    color: var(--dm2-ink);
    font-size: 15px;
    font-weight: 600;
    letter-spacing: -0.01em;
  }

  span {
    color: var(--dm2-muted);
    font-size: 13px;
    font-weight: 500;
  }
}

.attribute-dialog-tools {
  display: inline-flex;
  gap: 8px;
  flex-shrink: 0;
}

.attribute-records-empty {
  display: grid;
  place-items: center;
  min-height: 200px;
  color: var(--dm2-muted);
  font-size: 14px;
  font-weight: 500;
}

/* 表格批量编辑：双向滚动 */
.attr-table-scroll {
  max-height: calc(100vh - 430px);
  min-height: 160px;
  overflow: auto;
  border: 1px solid var(--dm2-line);
  border-radius: var(--dm2-radius);
  scrollbar-width: thin;
  scrollbar-color: rgba(15, 23, 42, 0.2) transparent;

  &::-webkit-scrollbar {
    width: 9px;
    height: 9px;
  }

  &::-webkit-scrollbar-thumb {
    background: rgba(15, 23, 42, 0.18);
    border-radius: 999px;
    border: 2px solid var(--dm2-surface);
  }
}

.attr-table {
  border-collapse: separate;
  border-spacing: 0;
  width: max-content;
  min-width: 100%;
  font-size: 13px;
}

/* 表头：冻结在顶部 */
.attr-table thead th {
  position: sticky;
  top: 0;
  z-index: 2;
  padding: 9px 12px;
  background: var(--dm2-surface-sunken);
  border-bottom: 1px solid var(--dm2-line);
  color: var(--dm2-muted);
  font-size: 12px;
  font-weight: 600;
  text-align: left;
  white-space: nowrap;
}

.attr-table tbody td {
  padding: 5px 8px;
  border-bottom: 1px solid var(--dm2-line-faint);
  vertical-align: middle;
}

.attr-table tbody tr:last-child td {
  border-bottom: none;
}

.attr-table tbody tr {
  position: relative;
  transition: opacity 140ms ease, background-color 140ms ease;
}

.attr-table tbody tr.is-dragging {
  opacity: 0.42;
}

.attr-table tbody tr.is-drag-over-before td {
  box-shadow: inset 0 2px 0 var(--dm2-accent);
}

.attr-table tbody tr.is-drag-over-after td {
  box-shadow: inset 0 -2px 0 var(--dm2-accent);
}

/* 数据列：限定宽度，超长由单元格内输入框内部滚动 */
.attr-table th:not(.col-act),
.attr-table td:not(.col-act) {
  min-width: 132px;
}

.attr-table th.col-wide,
.attr-table td.col-wide {
  min-width: 240px;
}

/* 操作列：冻结在右侧 */
.attr-table .col-act {
  position: sticky;
  right: 0;
  z-index: 1;
  width: 60px;
  min-width: 60px;
  padding: 5px 8px;
  text-align: center;
  background: var(--dm2-surface);
  border-left: 1px solid var(--dm2-line);
}

.attr-table thead th.col-act {
  z-index: 3;
  background: var(--dm2-surface-sunken);
  text-align: center;
}

/* 行状态：极淡底色仅作用于数据单元格；冻结列保持不透明白底以正确遮挡滚动内容 */
.attr-table tbody tr.state-added td:not(.col-act) {
  background: var(--dm2-add-weak);
}

.attr-table tbody tr.state-modified td:not(.col-act) {
  background: var(--dm2-modify-weak);
}

.attr-table tbody tr.state-deleted td:not(.col-act) {
  background: var(--dm2-delete-weak);
}

.record-action-btn {
  border: none;
  background: transparent;
  cursor: pointer;
  color: #ff3b30;
  font-size: 12.5px;
  font-weight: 500;
  padding: 4px 8px;
  border-radius: var(--dm2-radius-sm);
  transition: background 160ms ease;

  &:hover {
    background: rgba(255, 59, 48, 0.1);
  }

  &.is-restore {
    color: var(--dm2-accent);

    &:hover {
      background: var(--dm2-accent-weak);
    }
  }
}

/* 单元格输入框：填充式、紧凑，聚焦时蓝色光环 */
.attr-table :deep(.el-input__wrapper) {
  min-height: 32px;
  padding: 0 10px;
  border-radius: var(--dm2-radius-sm);
  background: var(--dm2-field);
  border: 1px solid transparent;
  box-shadow: none !important;
  transition: background 160ms ease, border-color 160ms ease, box-shadow 160ms ease;
}

.attr-table :deep(.el-input__wrapper.is-focus) {
  background: var(--dm2-surface);
  border-color: var(--dm2-accent);
  box-shadow: 0 0 0 3px var(--dm2-accent-ring) !important;
}

.attr-table :deep(.el-input__inner) {
  font-size: 13px;
  color: var(--dm2-ink);
}

.route-sequence-cell {
  display: flex;
  align-items: center;
  gap: 9px;
  min-height: 32px;
  color: var(--dm2-ink-soft);
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.route-drag-handle {
  display: inline-grid;
  place-items: center;
  width: 26px;
  height: 26px;
  padding: 0;
  border: 0;
  border-radius: var(--dm2-radius-sm);
  background: transparent;
  color: var(--dm2-muted-soft);
  cursor: grab;
  touch-action: none;
  transition: color 140ms ease, background-color 140ms ease;

  &:hover {
    background: var(--dm2-accent-weak);
    color: var(--dm2-accent);
  }

  &:active {
    cursor: grabbing;
  }

  &:focus-visible {
    outline: 2px solid var(--dm2-accent);
    outline-offset: 2px;
  }

  &:disabled {
    color: var(--dm2-line-strong);
    cursor: not-allowed;
  }

  svg {
    fill: currentColor;
  }
}

.attr-table :deep(.el-select) {
  width: 100%;
}

.attr-table :deep(.el-select__wrapper) {
  min-height: 32px;
  border-radius: var(--dm2-radius-sm);
  background: var(--dm2-field);
  box-shadow: none;
}

.attr-table .state-deleted :deep(.el-input__inner) {
  text-decoration: line-through;
  color: var(--dm2-muted-soft);
}

.attr-table :deep(.el-input.is-disabled .el-input__wrapper) {
  background: var(--dm2-surface-sunken);
  border-color: transparent;
}

.attr-table :deep(.el-input.is-disabled .el-input__inner) {
  color: var(--dm2-muted);
  -webkit-text-fill-color: var(--dm2-muted);
  cursor: default;
}

.station-history {
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid var(--dm2-line-faint);
}

.station-history-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  color: var(--dm2-ink-soft);
  font-size: 13px;
  font-weight: 600;

  small {
    color: var(--dm2-muted-soft);
    font-size: 11px;
    font-weight: 500;
  }
}

.station-history-state {
  display: grid;
  place-items: center;
  min-height: 72px;
  border: 1px solid var(--dm2-line-faint);
  border-radius: var(--dm2-radius);
  color: var(--dm2-muted-soft);
  font-size: 12px;
}

.history-table-scroll {
  max-height: 224px;
  overflow: auto;
  border: 1px solid var(--dm2-line);
  border-radius: var(--dm2-radius);
  scrollbar-width: thin;
  scrollbar-color: rgba(15, 23, 42, 0.2) transparent;
}

.history-table {
  table-layout: auto;

  tbody td {
    height: 42px;
    padding: 9px 12px;
    white-space: nowrap;
  }

  .history-data-cell {
    background: rgba(246, 248, 251, 0.7);
    color: var(--dm2-muted);
    font-size: 12.5px;
    transition: color 160ms ease, background 160ms ease;
  }

  .history-data-cell.is-muted {
    color: rgba(100, 116, 139, 0.5);
  }

  .history-data-cell.is-changed {
    background: rgba(255, 59, 48, 0.065);
    color: #d92d20;
    font-weight: 650;
  }

  .history-meta-cell {
    min-width: 104px;
    color: var(--dm2-muted-soft);
    font-size: 11.5px;
  }

  .history-user-column {
    min-width: 104px;
  }

  .history-time-column {
    min-width: 154px;
  }
}

.attribute-footer {
  display: flex;
  align-items: center;
  justify-content: flex-end;

  > div {
    display: inline-flex;
    gap: 10px;
  }
}

/* ── 暗色模式（html.dark，跟随底图选择） ── */
:global(html.dark .dm-attribute-dialog .el-dialog__headerbtn:hover) {
  background: rgba(148, 180, 220, 0.1);
}

:global(html.dark .dm-attribute-dialog .el-dialog__close) {
  color: #94a3b8;
}

html.dark .attr-table-scroll {
  scrollbar-color: rgba(148, 180, 220, 0.3) transparent;
}

html.dark .attr-table-scroll::-webkit-scrollbar-thumb {
  background: rgba(148, 180, 220, 0.28);
}

html.dark .record-action-btn {
  color: #f87171;
}

html.dark .record-action-btn:hover {
  background: rgba(248, 113, 113, 0.12);
}

html.dark .record-action-btn.is-restore {
  color: var(--dm2-accent);
}

html.dark .record-action-btn.is-restore:hover {
  background: var(--dm2-accent-weak);
}

html.dark .history-table-scroll {
  scrollbar-color: rgba(148, 180, 220, 0.3) transparent;
}

html.dark .history-table .history-data-cell {
  background: rgba(16, 22, 30, 0.7);
}

html.dark .history-table .history-data-cell.is-muted {
  color: rgba(148, 163, 184, 0.5);
}

html.dark .history-table .history-data-cell.is-changed {
  background: rgba(248, 113, 113, 0.12);
  color: #f87171;
}
</style>
