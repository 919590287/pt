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
    <div class="attribute-dialog-head">
      <div>
        <p>{{ model.subtitle }}</p>
        <span>共 {{ model.rows.length }} 条记录 · {{ changedCount }} 条改动</span>
      </div>
      <div class="attribute-dialog-tools">
        <el-button
          v-if="model.datasetType === 'station' && model.route"
          size="small"
          @click="$emit('toggle-route')"
        >
          {{ model.showRouteStations ? "仅编辑本站" : "编辑全线站点" }}
        </el-button>
        <el-button size="small" :disabled="!changedCount" @click="$emit('reset')">重置</el-button>
      </div>
    </div>

    <div v-if="!model.rows.length" class="attribute-records-empty">未找到可编辑的属性记录</div>
    <div v-else class="attr-table-scroll">
      <table class="attr-table">
        <thead>
          <tr>
            <th class="col-idx">#</th>
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
            v-for="(row, rowIndex) in model.rows"
            :key="row.rowId"
            :class="`state-${stateKey(row)}`"
          >
            <td class="col-idx">
              <span class="row-idx">{{ rowIndex + 1 }}</span>
              <span
                v-if="stateKey(row) !== 'normal'"
                class="row-flag"
                :class="`flag-${stateKey(row)}`"
              >{{ statusLabel(row) }}</span>
            </td>
            <td
              v-for="column in model.columns"
              :key="column.key"
              :class="{ 'col-wide': column.wide }"
            >
              <el-input
                v-model="row.properties[column.key]"
                size="small"
                :disabled="row.status === 'deleted'"
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

    <template #footer>
      <div class="attribute-footer">
        <span>{{ changedCount ? "修改会先进入右侧待提交列表" : "编辑单元格、新增或删除记录后再生成修改项" }}</span>
        <div>
          <el-button @click="$emit('update:visible', false)">关闭</el-button>
          <el-button type="primary" :disabled="!changedCount" @click="$emit('apply')">生成修改项</el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
defineProps({
  // attributeTable 响应式对象，按引用传入：子组件内对 row.properties 的修改会同步回父级
  model: { type: Object, required: true },
  changedCount: { type: Number, default: 0 },
  // 纯展示用的派生函数（依赖父级数据），以函数形式注入，子组件保持“哑”组件
  stateKey: { type: Function, required: true },
  statusLabel: { type: Function, required: true },
  recordTitle: { type: Function, required: true },
});

defineEmits(["update:visible", "toggle-route", "reset", "remove-row", "restore-row", "touch-row", "apply"]);
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

/* 表格批量编辑：双向滚动 + 表头/序号列冻结 */
.attr-table-scroll {
  max-height: calc(100vh - 320px);
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

/* 数据列：限定宽度，超长由单元格内输入框内部滚动 */
.attr-table th:not(.col-idx):not(.col-act),
.attr-table td:not(.col-idx):not(.col-act) {
  min-width: 132px;
}

.attr-table th.col-wide,
.attr-table td.col-wide {
  min-width: 240px;
}

/* 序号列：冻结在左侧 */
.attr-table .col-idx {
  position: sticky;
  left: 0;
  z-index: 1;
  width: 64px;
  min-width: 64px;
  padding: 5px 10px;
  background: var(--dm2-surface);
  border-right: 1px solid var(--dm2-line);
  text-align: left;
  white-space: nowrap;
}

.attr-table thead th.col-idx {
  z-index: 3;
  background: var(--dm2-surface-sunken);
}

.attr-table .row-idx {
  color: var(--dm2-muted);
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.attr-table .row-flag {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  font-weight: 600;

  &.flag-added {
    color: var(--dm2-add);
  }

  &.flag-modified {
    color: var(--dm2-modify);
  }

  &.flag-deleted {
    color: var(--dm2-delete);
  }
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
.attr-table tbody tr.state-added td:not(.col-idx):not(.col-act) {
  background: var(--dm2-add-weak);
}

.attr-table tbody tr.state-modified td:not(.col-idx):not(.col-act) {
  background: var(--dm2-modify-weak);
}

.attr-table tbody tr.state-deleted td:not(.col-idx):not(.col-act) {
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

.attr-table .state-deleted :deep(.el-input__inner) {
  text-decoration: line-through;
  color: var(--dm2-muted-soft);
}

.attribute-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;

  > span {
    color: var(--dm2-muted);
    font-size: 13px;
    font-weight: 500;
  }

  > div {
    display: inline-flex;
    gap: 10px;
  }
}
</style>
