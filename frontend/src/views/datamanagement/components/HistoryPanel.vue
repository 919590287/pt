<template>
  <div class="history-panel">
    <section class="history-header">
      <div>
        <p class="panel-kicker">版本管理</p>
        <h1>{{ area }} 历史数据版本</h1>
      </div>
      <el-button :loading="loading" @click="$emit('refresh')">刷新</el-button>
    </section>

    <section class="history-current-version" aria-label="当前版本">
      <span>当前版本</span>
      <strong>{{ activeLabel }}</strong>
    </section>

    <section class="history-content">
      <div class="history-list-panel">
        <div class="history-list-title">
          <div>
            <h2>版本时间轴</h2>
            <p>只展示可查看的数据版本；查看不会影响最新工作版本。</p>
          </div>
        </div>

        <div v-if="error" class="history-state history-state-error">{{ error }}</div>
        <div v-else-if="loading" class="history-state">正在加载历史数据...</div>
        <div v-else-if="!versions.length" class="history-state">暂无历史版本</div>
        <div v-else class="history-timeline">
          <article
            v-for="record in versions"
            :key="record.versionId"
            :class="['history-version-node', record.isActiveDataVersion ? 'active-data' : '']"
          >
            <div class="history-timeline-rail">
              <span class="history-timeline-dot"></span>
            </div>
            <div class="history-version-main">
              <div class="history-version-title-row">
                <strong>{{ recordTitle(record) }}</strong>
                <span v-if="record.isActiveDataVersion" class="history-current-tag">当前版本</span>
              </div>
              <div class="history-meta">
                <span>修改人：{{ record.username || '未知用户' }}</span>
                <span>修改时间：{{ formatTime(record.committedAt) }}</span>
              </div>
            </div>
            <div class="history-version-side">
              <el-button size="small" @click.stop="$emit('show-details', record)">修改明细</el-button>
              <el-button
                size="small"
                type="primary"
                plain
                :loading="previewLoadingId === record.versionId"
                @click.stop="$emit('preview', record)"
              >
                预览此版本
              </el-button>
            </div>
          </article>
        </div>
      </div>

      <aside class="history-risk-panel">
        <h2>版本规则</h2>
        <p>提交修改会产生一个新版本；历史查询只做只读查看，不会改变数据总览和数据更新的最新工作版本。</p>
        <p>提交前会校验当前工作版本。如果其他电脑已先保存，系统会保留你的修改并提示刷新，避免覆盖他人的结果。</p>
      </aside>
    </section>

    <aside v-if="details.visible" class="history-detail-panel" aria-label="历史修改明细">
      <div class="history-detail-head">
        <div>
          <p class="panel-kicker">修改明细</p>
          <h2>{{ recordTitle(details.record) }}</h2>
          <span>修改人：{{ details.record?.username || "未知用户" }} · 修改时间：{{ formatTime(details.record?.committedAt) }}</span>
        </div>
        <button class="detail-close-btn" type="button" title="关闭明细" aria-label="关闭明细" @click="$emit('close-details')">
          <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round">
            <line x1="6" y1="6" x2="18" y2="18"></line>
            <line x1="18" y1="6" x2="6" y2="18"></line>
          </svg>
        </button>
      </div>
      <div class="history-detail-groups">
        <section v-for="group in detailGroups" :key="group.key" class="history-detail-group">
          <div class="history-detail-group-title">
            <h3>{{ group.label }}</h3>
            <span>{{ group.rows.length }} 条</span>
          </div>
          <div v-if="group.rows.length" class="history-detail-list">
            <article v-for="row in group.rows" :key="row.key" class="history-detail-row">
              <div class="history-detail-row-main">
                <span class="history-detail-action">{{ row.action }}</span>
                <strong>{{ row.target }}</strong>
                <p>{{ row.detail }}</p>
                <div v-if="row.evidenceImages.length" class="history-evidence-strip" aria-label="修改证据">
                  <button
                    v-for="image in row.evidenceImages"
                    :key="image.id || image.name || image.dataUrl"
                    class="history-evidence-thumb"
                    type="button"
                    :title="image.name || '证据图片'"
                    @click="$emit('preview-evidence', image)"
                  >
                    <img :src="image.dataUrl" :alt="image.name || '证据图片'" />
                  </button>
                </div>
              </div>
              <div class="history-detail-row-meta">
                <span>{{ row.username }}</span>
                <time>{{ formatTime(row.committedAt) }}</time>
              </div>
            </article>
          </div>
          <p v-else class="history-detail-empty">未修改{{ group.label }}</p>
        </section>
      </div>
    </aside>
  </div>
</template>

<script setup>
defineProps({
  area: { type: String, default: "" },
  loading: { type: Boolean, default: false },
  error: { type: String, default: "" },
  versions: { type: Array, default: () => [] },
  activeLabel: { type: String, default: "" },
  previewLoadingId: { type: String, default: "" },
  details: { type: Object, required: true }, // { visible, record }
  detailGroups: { type: Array, default: () => [] },
  recordTitle: { type: Function, required: true },
  formatTime: { type: Function, required: true },
});

defineEmits(["refresh", "show-details", "preview", "close-details", "preview-evidence"]);
</script>

<style lang="scss" scoped>
/* 历史数据版本页 —— 自包含、全令牌、冷静专业 */
.history-panel {
  position: relative;
  flex: 1 1 auto;
  min-height: 0;
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
  overflow-y: auto;
}

.history-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;

  .panel-kicker {
    margin: 0 0 6px;
    color: var(--dm2-accent);
    font-size: var(--dm2-text-sm);
    font-weight: var(--dm2-fw-semibold);
    letter-spacing: 0.04em;
  }

  h1 {
    margin: 0;
    color: var(--dm2-ink);
    font-size: var(--dm2-text-title);
    font-weight: var(--dm2-fw-bold);
    letter-spacing: -0.015em;
  }
}

.history-current-version {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border: 1px solid var(--dm2-line);
  border-radius: var(--dm2-radius);
  background: var(--dm2-surface);

  span {
    color: var(--dm2-muted);
    font-size: var(--dm2-text-base);
    font-weight: var(--dm2-fw-semibold);
  }

  strong {
    color: var(--dm2-ink);
    font-size: var(--dm2-text-md);
    font-weight: var(--dm2-fw-bold);
  }
}

.history-content {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 16px;
  align-items: start;
}

@media (max-width: 1100px) {
  .history-content {
    grid-template-columns: minmax(0, 1fr);
  }
}

.history-list-panel,
.history-risk-panel {
  padding: 16px;
  border: 1px solid var(--dm2-line);
  border-radius: var(--dm2-radius);
  background: var(--dm2-surface);
}

.history-list-title {
  margin-bottom: 14px;

  h2 {
    margin: 0 0 4px;
    color: var(--dm2-ink);
    font-size: var(--dm2-text-lg);
    font-weight: var(--dm2-fw-bold);
  }

  p {
    margin: 0;
    color: var(--dm2-muted);
    font-size: var(--dm2-text-sm);
    font-weight: var(--dm2-fw-medium);
  }
}

.history-state {
  padding: 28px 0;
  text-align: center;
  color: var(--dm2-muted);
  font-size: var(--dm2-text-base);
  font-weight: var(--dm2-fw-medium);

  &.history-state-error {
    color: var(--dm2-delete);
  }
}

.history-timeline {
  display: flex;
  flex-direction: column;
}

.history-version-node {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr) auto;
  gap: 14px;
  padding: 14px 0;
  border-bottom: 1px solid var(--dm2-line-faint);

  &:last-child {
    border-bottom: none;
  }
}

.history-timeline-rail {
  display: flex;
  justify-content: center;
  padding-top: 4px;
}

.history-timeline-dot {
  width: 10px;
  height: 10px;
  border-radius: 999px;
  background: var(--dm2-muted-soft);
}

.history-version-node.active-data .history-timeline-dot {
  background: var(--dm2-accent);
}

.history-version-main {
  min-width: 0;
}

.history-version-title-row {
  display: flex;
  align-items: center;
  gap: 8px;

  strong {
    min-width: 0;
    color: var(--dm2-ink);
    font-size: var(--dm2-text-md);
    font-weight: var(--dm2-fw-bold);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.history-current-tag {
  flex-shrink: 0;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--dm2-accent-weak);
  color: var(--dm2-accent);
  font-size: var(--dm2-text-xs);
  font-weight: var(--dm2-fw-semibold);
}

.history-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  margin-top: 6px;
  color: var(--dm2-muted);
  font-size: var(--dm2-text-sm);
  font-weight: var(--dm2-fw-medium);
}

.history-version-side {
  display: flex;
  align-items: center;
  gap: 8px;
}

.history-risk-panel {
  h2 {
    margin: 0 0 10px;
    color: var(--dm2-ink);
    font-size: var(--dm2-text-md);
    font-weight: var(--dm2-fw-bold);
  }

  p {
    margin: 0 0 8px;
    color: var(--dm2-ink-soft);
    font-size: var(--dm2-text-base);
    font-weight: var(--dm2-fw-medium);
    line-height: 1.6;

    &:last-child {
      margin-bottom: 0;
    }
  }
}

/* 修改明细：右侧浮层 */
.history-detail-panel {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: min(520px, 60%);
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 20px;
  border: 1px solid var(--dm2-line);
  border-radius: var(--dm2-radius-lg);
  background: var(--dm2-surface);
  box-shadow: var(--dm2-shadow-panel);
  overflow-y: auto;
  z-index: 2;
}

.history-detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;

  .panel-kicker {
    margin: 0 0 6px;
    color: var(--dm2-accent);
    font-size: var(--dm2-text-sm);
    font-weight: var(--dm2-fw-semibold);
  }

  h2 {
    margin: 0 0 4px;
    color: var(--dm2-ink);
    font-size: var(--dm2-text-lg);
    font-weight: var(--dm2-fw-bold);
  }

  > div > span {
    color: var(--dm2-muted);
    font-size: var(--dm2-text-sm);
    font-weight: var(--dm2-fw-medium);
  }
}

.detail-close-btn {
  flex-shrink: 0;
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: none;
  border-radius: var(--dm2-radius-sm);
  background: transparent;
  color: var(--dm2-muted);
  cursor: pointer;
  transition: background 160ms ease;

  &:hover {
    background: rgba(15, 23, 42, 0.06);
  }
}

.history-detail-groups {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.history-detail-group-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 8px;

  h3 {
    margin: 0;
    color: var(--dm2-ink);
    font-size: var(--dm2-text-md);
    font-weight: var(--dm2-fw-bold);
  }

  span {
    color: var(--dm2-muted);
    font-size: var(--dm2-text-sm);
    font-weight: var(--dm2-fw-medium);
  }
}

.history-detail-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.history-detail-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border: 1px solid var(--dm2-line);
  border-radius: var(--dm2-radius);
  background: var(--dm2-surface);
}

.history-detail-row-main {
  min-width: 0;

  .history-detail-action {
    color: var(--dm2-accent);
    font-size: var(--dm2-text-sm);
    font-weight: var(--dm2-fw-semibold);
  }

  strong {
    display: block;
    margin: 4px 0 2px;
    color: var(--dm2-ink);
    font-size: var(--dm2-text-base);
    font-weight: var(--dm2-fw-bold);
    word-break: break-word;
  }

  p {
    margin: 0;
    color: var(--dm2-muted);
    font-size: var(--dm2-text-sm);
    font-weight: var(--dm2-fw-medium);
    word-break: break-word;
  }
}

.history-evidence-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.history-evidence-thumb {
  width: 44px;
  height: 44px;
  padding: 0;
  border: 1px solid var(--dm2-line);
  border-radius: var(--dm2-radius-sm);
  overflow: hidden;
  cursor: pointer;
  background: var(--dm2-field);
  transition: border-color 160ms ease;

  &:hover {
    border-color: var(--dm2-accent);
  }

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }
}

.history-detail-row-meta {
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  color: var(--dm2-muted);
  font-size: var(--dm2-text-xs);
  font-weight: var(--dm2-fw-medium);
}

.history-detail-empty {
  margin: 0;
  color: var(--dm2-muted-soft);
  font-size: var(--dm2-text-sm);
}
</style>
