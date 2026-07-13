<template>
  <div class="datebase_box database-box" role="search" aria-label="区域选择">
    <label class="handle" for="datamanagement-area-selector">当前区域</label>
    <el-select
      id="datamanagement-area-selector"
      v-model="selectedArea"
      filterable
      :loading="isLoadingAreas"
      aria-label="当前区域"
    >
      <el-option v-for="item in areaList" :key="item" :label="item" :value="item"></el-option>
    </el-select>
  </div>

  <DmSidebar :active-key="activeKey" :collapsed="isLeftPanelCollapsed" @select="setActiveKey" />

  <button
    type="button"
    :class="['dm-panel-collapse-tab', 'dm-left-collapse-tab', isLeftPanelCollapsed ? 'is-collapsed' : '']"
    :title="isLeftPanelCollapsed ? '展开左侧面板' : '收起左侧面板'"
    :aria-label="isLeftPanelCollapsed ? '展开左侧面板' : '收起左侧面板'"
    :aria-pressed="isLeftPanelCollapsed"
    @pointerdown="handlePanelTogglePointer($event, 'left')"
    @click="handlePanelToggleClick($event, 'left')"
  >
    <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <polyline points="15 18 9 12 15 6"></polyline>
    </svg>
  </button>

  <MapSearchBox
    v-if="showMapSearch"
    ref="searchBoxRef"
    :placeholder="searchPlaceholder"
    :left-collapsed="isLeftPanelCollapsed"
    :search-fn="searchIndexEntries"
    @focus="handleSearchBoxFocus"
    @select="selectSearchResult"
  />

  <div v-if="activeKey === 'overview' || historyPreview.visible" :class="['dm-overview-panel', isRightPanelCollapsed ? 'is-collapsed' : '']">
    <div class="overview-title-row" :class="{ 'is-station-detail': selectedStation || selectedRoute || selectedDepot }">
      <div v-if="selectedStation" class="detail-title-block station">
        <p class="panel-kicker">站点详情</p>
        <h2 class="overview-station-title">{{ selectedStation.name }}</h2>
        <span>{{ selectedStationRouteCount }} 条途经线路</span>
      </div>
      <div v-else-if="selectedRoute" class="detail-title-block route">
        <p class="panel-kicker">线路详情</p>
        <h2 class="overview-station-title">{{ parsePickerRoute(selectedRoute.name).mainName }}</h2>
        <span>{{ routeEndpoints(selectedRoute.properties) }}</span>
      </div>
      <div v-else-if="selectedDepot" class="detail-title-block depot">
        <p class="panel-kicker">场站详情</p>
        <h2 class="overview-station-title">{{ selectedDepot.name }}</h2>
        <span>{{ selectedDepotInfo.rows.length }} 项登记属性</span>
      </div>
      <div v-else>
        <p v-if="panelKicker !== '真实数据'" class="panel-kicker">{{ panelKicker }}</p>
        <h2>{{ panelTitle }}</h2>
      </div>
      <button v-if="historyPreview.visible && !hasActiveDetail" class="detail-close-btn" type="button" title="退出历史预览" aria-label="退出历史预览" @click="exitHistoryPreview">
        <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round">
          <line x1="6" y1="6" x2="18" y2="18"></line>
          <line x1="18" y1="6" x2="6" y2="18"></line>
        </svg>
      </button>
      <!-- 加载/空/错误态已下沉到面板体的状态机，标题栏不再重复浮标 -->
      <el-tag
        v-else-if="!selectedStation && !selectedRoute && !selectedDepot && isLoadingLayer && !isOverviewEmpty"
        type="info"
        size="small"
      >更新中…</el-tag>
    </div>

    <div v-if="selectedStation" class="station-detail-panel">
      <div class="ranking-panel">
        <div class="ranking-header">
          <span class="col-rank">排序</span>
          <span class="col-name">线路名称</span>
          <span v-if="selectedStationHasPassengerFlow" class="col-flow">日均客流量</span>
        </div>
        <div v-if="selectedStation.routes.length" class="ranking-scroll-list">
          <button
            v-for="(route, index) in selectedStation.routes"
            :key="`${route.name}-${index}`"
            type="button"
            :class="['ranking-row', route.feature ? 'is-clickable' : 'is-disabled']"
            :disabled="!route.feature"
            @click="selectRouteFromStation(route)"
          >
            <div class="col-rank">
              <span class="rank-badge">
                {{ index + 1 }}
              </span>
            </div>
            <div class="col-name">
              <span class="route-name-text">{{ route.name }}</span>
            </div>
            <div v-if="selectedStationHasPassengerFlow" class="col-flow">
              <span class="flow-value">{{ formatPassengerFlow(route.passengerFlow) }}</span>
              <span v-if="hasPassengerFlow(route.passengerFlow)" class="flow-unit">人次</span>
            </div>
          </button>
        </div>
        <p v-else class="station-route-empty">未匹配到途经线路</p>
      </div>
    </div>

    <div v-else-if="selectedRoute" class="route-detail-panel">
      <div class="detail-summary-card route-summary-card">
        <div class="service-time-card">
          <span>服务时段</span>
          <strong>{{ routeServiceTime(selectedRoute.properties) }}</strong>
        </div>
      </div>
      <div class="metrics-grid">
        <div class="metric-card">
          <span class="label">线路长度</span>
          <span class="value">{{ getRouteLength(selectedRoute.properties) }}</span>
        </div>
        <div class="metric-card">
          <span class="label">班次数量</span>
          <span class="value">{{ routeTripCount(selectedRoute.properties) }}</span>
        </div>
        <div class="metric-card">
          <span class="label">配车数量</span>
          <span class="value">{{ routeVehicleCount(selectedRoute.properties) }}</span>
        </div>
        <div class="metric-card">
          <span class="label">直线系数</span>
          <span class="value">{{ getRouteDirectness(selectedRoute.properties, selectedRoute) }}</span>
        </div>
        <div class="metric-card">
          <span class="label">站点数量</span>
          <span class="value">{{ selectedRouteStations.length }} 个</span>
        </div>
        <div class="metric-card">
          <span class="label">平均站距</span>
          <span class="value">{{ getRouteAvgStationDistance(selectedRoute.properties, selectedRoute.name) }}</span>
        </div>
        <div class="metric-card">
          <span class="label">发车间隔</span>
          <span class="value">{{ routeHeadway(selectedRoute.properties) }}</span>
        </div>
        <div class="metric-card">
          <span class="label">票价</span>
          <span class="value">{{ routeFare(selectedRoute.properties) }}</span>
        </div>
        <div class="metric-card">
          <span class="label">所属公司</span>
          <span class="value">{{ routeCompany(selectedRoute.properties) }}</span>
        </div>
      </div>

      <div class="stations-section">
        <div class="section-title">沿途站点 (按站序)</div>
        <div v-if="selectedRouteStations.length" class="station-scroll-list">
          <div class="timeline-container">
            <div 
              v-for="(fac, index) in selectedRouteStations" 
              :key="fac.facilityId || `${fac.facilityName}-${index}`"
              class="timeline-item"
            >
              <div :class="['timeline-dot', index === 0 ? 'first' : '', index === selectedRouteStations.length - 1 ? 'last' : '']">
                <div class="dot-inner"></div>
              </div>
              <div class="timeline-content">
                <span class="station-name">{{ fac.facilityName }}</span>
                <span class="station-idx">第 {{ index + 1 }} 站</span>
              </div>
            </div>
          </div>
        </div>
        <p v-else class="station-route-empty">暂无沿途站点明细</p>
      </div>
    </div>

    <div v-else-if="selectedDepot" class="depot-detail-panel">
      <div v-if="selectedDepotInfo.location" class="depot-locate">
        <span class="depot-locate-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"></path>
            <circle cx="12" cy="10" r="3"></circle>
          </svg>
        </span>
        <div class="depot-locate-text">
          <span class="depot-locate-label">坐标定位</span>
          <span class="depot-locate-value">{{ selectedDepotInfo.location }}</span>
        </div>
      </div>
      <div v-if="selectedDepotInfo.rows.length" class="depot-fact-grid">
        <div
          v-for="item in selectedDepotInfo.rows"
          :key="item.key"
          class="depot-fact"
          :class="{ 'is-wide': item.wide }"
        >
          <span class="depot-fact-label">{{ item.label }}</span>
          <span class="depot-fact-value">{{ item.value }}</span>
        </div>
      </div>
      <p v-else class="station-route-empty">暂无场站属性明细</p>
    </div>

    <template v-else>
      <!-- 加载态：首次拉取且暂无数据时用骨架屏占位，避免直接弹出 0 值；有旧数据的后台刷新则静默保留旧数据 -->
      <div v-if="isLoadingLayer && isOverviewEmpty" class="overview-state overview-skeleton" aria-hidden="true">
        <div class="sk-block sk-hero sk-shimmer"></div>
        <div class="sk-strip">
          <div class="sk-block sk-shimmer"></div>
          <div class="sk-block sk-shimmer"></div>
          <div class="sk-block sk-shimmer"></div>
        </div>
        <div class="sk-block sk-card sk-shimmer"></div>
        <div class="sk-table">
          <div v-for="n in 5" :key="n" class="sk-block sk-row sk-shimmer"></div>
        </div>
      </div>
      <!-- 错误态：整块替换面板体（不再让告警浮在 0 值之上），并给出重试 -->
      <div v-else-if="loadError" class="overview-state overview-status" role="alert">
        <span class="overview-status-icon is-error" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">
            <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path>
            <line x1="12" y1="9" x2="12" y2="13"></line>
            <line x1="12" y1="17" x2="12.01" y2="17"></line>
          </svg>
        </span>
        <p class="overview-status-title">数据加载失败</p>
        <p class="overview-status-desc">{{ loadError }}</p>
        <button type="button" class="overview-status-retry" @click="loadOverviewLayers({ force: true })">重新加载</button>
      </div>
      <!-- 空态：已加载但无线网数据 -->
      <div v-else-if="isOverviewEmpty" class="overview-state overview-status">
        <span class="overview-status-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
            <path d="M3 7l9-4 9 4-9 4-9-4z"></path>
            <path d="M3 12l9 4 9-4"></path>
            <path d="M3 17l9 4 9-4"></path>
          </svg>
        </span>
        <p class="overview-status-title">暂无线网数据</p>
        <p class="overview-status-desc">当前区域还没有可展示的线路与站点数据。</p>
      </div>
      <OverviewMetrics
        v-else
        :stats="overviewStats"
        :operator-rows="operatorLineRows"
        :show-vehicle-columns="overviewHasVehicleData"
        :coverage="coverageView"
        :fmt-int="formatInteger"
        :fmt-unit="formatUnit"
        :fmt-pct="formatPercent"
        @configure-coverage="openBuiltUpDialog"
      />
    </template>
  </div>

  <div v-if="activeEditDataset" :class="['dm-edit-panel', isRightPanelCollapsed ? 'is-collapsed' : '']">
    <div class="overview-title-row">
      <div>
        <p class="panel-kicker">{{ editDatasetKicker }}</p>
        <h2>{{ editDatasetTitle }}</h2>
      </div>
      <span class="edit-pending-count" :class="{ 'has-pending': activeEditOperations.length }">{{ activeEditOperations.length }} 条修改</span>
    </div>
    <div v-if="pendingEditDatasetSummary.length > 1" class="edit-cross-dataset-summary">
      <strong>本次将联合提交</strong>
      <span v-for="item in pendingEditDatasetSummary" :key="item.datasetType">
        {{ item.label }} {{ item.count }} 条
      </span>
    </div>
    <div v-if="activeEditOperations.length" class="edit-operation-list">
      <div v-for="operation in visibleActiveEditOperations" :key="operation.operationId" class="edit-operation-item" :class="operationKind(operation.type)">
        <div class="operation-labels">
          <span class="operation-dataset">{{ datasetTypeLabel(operation.datasetType) }}</span>
          <span class="operation-type">{{ operationLabel(operation.type) }}</span>
          <span
            v-if="operation.deletionConfirmed && operation.protectedFields?.length"
            class="operation-protected is-deletion"
          >
            已确认删除人工修改
          </span>
          <span v-else-if="operation.manualProtected" class="operation-protected">保留人工修改</span>
        </div>
        <strong>{{ operation.title }}</strong>
        <p>{{ operation.detail }}</p>
      </div>
      <el-button
        v-if="visibleActiveEditOperations.length < activeEditOperations.length"
        class="edit-operation-more"
        plain
        @click="editOperationRenderCount += EDIT_OPERATION_RENDER_BATCH"
      >
        继续显示其余 {{ activeEditOperations.length - visibleActiveEditOperations.length }} 条
      </el-button>
    </div>
    <div v-else class="edit-empty">
      <strong>{{ editModeGuide.title }}</strong>
      <p>{{ editModeGuide.description }}</p>
      <ol>
        <li v-for="step in editModeGuide.steps" :key="step">{{ step }}</li>
      </ol>
    </div>
    <div class="edit-panel-actions">
      <input ref="shpUploadInput" class="shp-upload-input" type="file" multiple accept=".zip,.shp,.shx,.dbf,.prj,.cpg" @change="handleUploadShpFiles" />
      <el-button
        v-if="editModeGuide.canStartAdd"
        type="primary"
        plain
        :disabled="isSubmittingEdit"
        @click="pendingAddDataset === activeEditDataset ? cancelPendingAdd() : beginAddFromPanel()"
      >
        {{ pendingAddDataset === activeEditDataset ? "取消点选" : editModeGuide.actionLabel }}
      </el-button>
      <el-button class="upload-shp-btn" :disabled="isSubmittingEdit" @click="handleUploadShpClick">上传 SHP</el-button>
      <el-button :disabled="!activeEditOperations.length || isSubmittingEdit" @click="discardActiveEdits">
        {{ pendingEditDatasetSummary.length > 1 ? "放弃全部" : "放弃修改" }}
      </el-button>
      <el-button type="primary" :disabled="!activeEditOperations.length" :loading="isSubmittingEdit" @click="submitActiveEdits">
        {{ pendingEditDatasetSummary.length > 1 ? "统一提交" : "提交修改" }}
      </el-button>
    </div>
  </div>

  <div v-if="activeKey === 'history' && !historyPreview.visible" :class="['dm-history-page', isLeftPanelCollapsed ? 'is-left-collapsed' : '']">
    <HistoryPanel
      :area="selectedArea"
      :loading="isLoadingHistory"
      :error="historyError"
      :versions="historyVersions"
      :active-label="activeHistoryVersionLabel"
      :preview-loading-id="historyPreview.loading ? (historyPreview.version?.versionId || '') : ''"
      :export-loading-key="historyExportLoadingKey"
      :details="historyDetails"
      :detail-groups="historyDetailGroups"
      :record-title="historyRecordTitle"
      :format-time="formatHistoryTime"
      @refresh="loadHistoryList"
      @show-details="showHistoryDetails"
      @export="exportHistoryVersion"
      @preview="viewHistoryVersion"
      @close-details="closeHistoryDetails"
      @preview-evidence="previewEvidenceImage"
    />
  </div>

  <div v-if="historyPreview.visible" class="history-preview-exit">
    <span>{{ historyRecordTitle(historyPreview.version) }}</span>
    <el-button :loading="historyPreview.loading" @click="exitHistoryPreview">退出预览</el-button>
  </div>

  <button
    v-if="hasRightSidePanel"
    type="button"
    :class="['dm-panel-collapse-tab', 'dm-right-collapse-tab', isRightPanelCollapsed ? 'is-collapsed' : '']"
    :title="isRightPanelCollapsed ? '展开右侧面板' : '收起右侧面板'"
    :aria-label="isRightPanelCollapsed ? '展开右侧面板' : '收起右侧面板'"
    :aria-pressed="isRightPanelCollapsed"
    @pointerdown="handlePanelTogglePointer($event, 'right')"
    @click="handlePanelToggleClick($event, 'right')"
  >
    <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <polyline points="9 18 15 12 9 6"></polyline>
    </svg>
  </button>

  <MapControlsToolbar
    v-if="isMapDataPage(activeKey) || historyPreview.visible"
    ref="mapToolbarRef"
    v-model:line-width="lineWidth"
    v-model:station-size="stationSize"
    :with-panel="hasVisibleRightSidePanel"
    :is3d-active="is3DActive"
    :range-options="displayRangeOptions"
    :selected-range="selectedDisplayRange"
    :all-range-label="DISPLAY_RANGE_ALL"
    :loading-ranges="isLoadingDisplayRanges"
    :range-error="displayRangeError"
    @zoom-in="handleZoomIn"
    @zoom-out="handleZoomOut"
    @toggle-3d="handleToggle3D"
    @reset-compass="handleResetCompass"
    @select-range="selectDisplayRange"
    @before-open="handleToolbarBeforeOpen"
    @paint-input="scheduleApplyLayerPaint"
  />

  <div
    v-if="lineRoutePicker.visible"
    class="line-route-picker dm-route-picker"
    :style="{ left: `${lineRoutePicker.x}px`, top: `${lineRoutePicker.y}px` }"
    role="dialog"
    aria-label="选择经过该线网的线路"
    @click.stop
    @keydown.esc.stop.prevent="closeLineRoutePicker"
  >
    <div class="picker-title">{{ lineRoutePickerTitle }}</div>
    <p v-if="lineRoutePickerHint" class="picker-hint">{{ lineRoutePickerHint }}</p>
    <button
      v-for="route in lineRoutePicker.routes"
      :key="route.id || route.name"
      :class="['picker-route-btn', isRouteOptionActive(route) ? 'active' : '']"
      type="button"
      :aria-pressed="isRouteOptionActive(route)"
      @click="selectRouteFromPicker(route)"
    >
      <div class="picker-icon-wrapper">
        <svg viewBox="0 0 24 24" class="type-svg" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
          <rect x="3" y="4" width="18" height="12" rx="2"></rect>
          <circle cx="7" cy="10" r="1"></circle>
          <circle cx="17" cy="10" r="1"></circle>
          <path d="M6 16v2"></path>
          <path d="M18 16v2"></path>
        </svg>
      </div>
      <div class="route-btn-meta">
        <span class="route-btn-name">{{ parsePickerRoute(route.name).mainName }}</span>
        <span v-if="parsePickerRoute(route.name).desc" class="route-btn-desc">{{ parsePickerRoute(route.name).desc }}</span>
      </div>
    </button>
    <p v-if="!lineRoutePicker.routes.length" class="picker-empty">未匹配到线路</p>
  </div>

  <div
    v-if="editActionMenu.visible"
    class="edit-action-menu"
    :style="{ left: `${editActionMenu.x}px`, top: `${editActionMenu.y}px` }"
    role="dialog"
    :aria-label="editActionMenu.title"
    @click.stop
    @keydown.esc.stop.prevent="closeEditActionMenu"
  >
    <div class="picker-title">{{ editActionMenu.title }}</div>
    <button v-for="action in editActionMenu.actions" :key="action.key" class="picker-route-btn" type="button" @click="handleEditMenuAction(action.key)">
      {{ action.label }}
    </button>
  </div>

  <el-dialog
    v-model="editDialog.visible"
    :title="editDialog.title"
    width="420px"
    append-to-body
    align-center
    class="dm-edit-dialog"
    :close-on-click-modal="false"
  >
    <p class="edit-dialog-subtitle">{{ editDialogSubtitle }}</p>
    <el-form class="dm-edit-form" :model="editDialog.form" label-position="top" @submit.prevent>
      <el-form-item v-if="editDialog.fields.includes('name')" label="名称">
        <el-input v-model.trim="editDialog.form.name" maxlength="80" clearable placeholder="请输入名称" />
        <span class="field-hint">{{ editFieldHint("name") }}</span>
      </el-form-item>
      <el-form-item v-if="editDialog.fields.includes('headway')" label="发车间隔">
        <el-input v-model.trim="editDialog.form.headway" maxlength="40" clearable placeholder="例如 8 分钟" />
        <span class="field-hint">用于线路发车频率维护，可填写分钟或文字说明。</span>
      </el-form-item>
      <el-form-item v-if="editDialog.fields.includes('stations')" label="途径站点">
        <el-input v-model.trim="editDialog.form.stations" type="textarea" :autosize="{ minRows: 5, maxRows: 9 }" maxlength="1000" placeholder="可按逗号或换行填写站点名称" />
        <span class="field-hint">一行一个站点更便于核对，提交前仍可放弃本次修改。</span>
      </el-form-item>
    </el-form>
    <template #footer>
      <div class="dm-edit-dialog-footer">
      <el-button @click="editDialog.visible = false">取消</el-button>
      <el-button type="primary" @click="confirmEditDialog">确定</el-button>
      </div>
    </template>
  </el-dialog>

  <el-dialog
    v-model="shpDeletionDialog.visible"
    title="确认疑似删除项"
    width="640px"
    append-to-body
    align-center
    class="dm-shp-deletion-dialog"
    :close-on-click-modal="false"
    @closed="resetShpDeletionDialog"
  >
    <div class="shp-deletion-summary">
      <strong>上传文件中缺少以下 {{ shpDeletionDialog.deletions.length }} 条{{ datasetTypeLabel(shpDeletionDialog.datasetType) }}数据</strong>
      <p>请逐项勾选需要删除的数据。未勾选项会保留，新增和其他字段更新仍会进入右侧面板。</p>
    </div>
    <div class="shp-deletion-toolbar">
      <span>已选择 {{ shpDeletionDialog.selectedIds.length }} 条删除</span>
      <div>
        <el-button link type="primary" @click="selectAllShpDeletionCandidates">全选</el-button>
        <el-button link @click="shpDeletionDialog.selectedIds = []">清空</el-button>
      </div>
    </div>
    <el-checkbox-group v-model="shpDeletionDialog.selectedIds" class="shp-deletion-list">
      <label
        v-for="operation in visibleShpDeletionCandidates"
        :key="operation.operationId"
        class="shp-deletion-item"
      >
        <el-checkbox :value="operation.operationId" />
        <span class="shp-deletion-copy">
          <strong>{{ operation.title || "未命名对象" }}</strong>
          <small>{{ operation.detail }}</small>
          <small v-if="operation.protectedFields?.length" class="shp-deletion-protected">
            该对象含人工修改字段：{{ operation.protectedFields.map(attributeColumnLabel).join("、") }}
          </small>
        </span>
      </label>
    </el-checkbox-group>
    <el-pagination
      v-if="shpDeletionDialog.deletions.length > SHP_DELETION_PAGE_SIZE"
      v-model:current-page="shpDeletionDialog.page"
      :page-size="SHP_DELETION_PAGE_SIZE"
      :total="shpDeletionDialog.deletions.length"
      layout="prev, pager, next"
      size="small"
      background
      class="shp-deletion-pagination"
    />
    <template #footer>
      <div class="dm-edit-dialog-footer">
        <el-button @click="cancelShpDeletionImport">取消本次导入</el-button>
        <el-button type="primary" @click="confirmShpDeletionCandidates">
          生成 {{ shpDeletionDialog.updates.length + shpDeletionDialog.selectedIds.length }} 条修改
        </el-button>
      </div>
    </template>
  </el-dialog>

  <el-dialog
    v-model="commitDialog.visible"
    title="提交修改"
    width="520px"
    append-to-body
    align-center
    class="dm-commit-dialog"
    :close-on-click-modal="false"
    @closed="handleCommitDialogClosed"
  >
    <div class="commit-dialog-summary">
      <span>{{ editDatasetTitle }}</span>
      <strong>{{ activeEditOperations.length }} 条修改待提交</strong>
    </div>
    <div v-if="pendingEditDatasetSummary.length" class="commit-dataset-summary">
      <span v-for="item in pendingEditDatasetSummary" :key="item.datasetType">
        {{ item.label }} {{ item.count }} 条
      </span>
    </div>
    <el-form class="dm-edit-form commit-form" @submit.prevent>
      <el-form-item label="修改说明">
        <el-input
          v-model.trim="commitDialog.message"
          type="textarea"
          :autosize="{ minRows: 4, maxRows: 7 }"
          maxlength="300"
          show-word-limit
          placeholder="例如：修正水均田路中站点名称"
        />
      </el-form-item>
      <el-form-item label="证据图片">
        <input ref="evidenceImageInput" class="evidence-file-input" type="file" accept="image/*" multiple @change="handleEvidenceFileInput" />
        <div
          class="evidence-dropzone"
          :class="{ 'is-dragging': commitDialog.dragging, 'has-images': commitDialog.evidenceImages.length }"
          role="button"
          tabindex="0"
          @click="openEvidenceFilePicker"
          @keydown.enter.prevent="openEvidenceFilePicker"
          @keydown.space.prevent="openEvidenceFilePicker"
          @dragover.prevent="commitDialog.dragging = true"
          @dragleave.prevent="commitDialog.dragging = false"
          @drop.prevent="handleEvidenceDrop"
        >
          <span class="evidence-dropzone-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <rect x="3" y="3" width="18" height="18" rx="2"></rect>
              <circle cx="8.5" cy="8.5" r="1.5"></circle>
              <path d="m21 15-5-5L5 21"></path>
            </svg>
          </span>
          <div>
            <strong>{{ commitDialog.evidenceImages.length ? "继续添加证据图片" : "上传证据图片" }}</strong>
            <span>可选，最多 6 张，随本次修改进入历史明细</span>
          </div>
        </div>
        <div v-if="commitDialog.evidenceImages.length" class="evidence-preview-grid">
          <article v-for="image in commitDialog.evidenceImages" :key="image.id" class="evidence-preview-item">
            <img :src="image.dataUrl" :alt="image.name" />
            <button type="button" title="移除图片" aria-label="移除图片" @click="removeEvidenceImage(image.id)">
              <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round">
                <line x1="18" y1="6" x2="6" y2="18"></line>
                <line x1="6" y1="6" x2="18" y2="18"></line>
              </svg>
            </button>
            <span>{{ image.name }}</span>
          </article>
        </div>
      </el-form-item>
    </el-form>
    <template #footer>
      <div class="dm-edit-dialog-footer commit-dialog-footer">
        <el-button @click="cancelCommitDialog">取消</el-button>
        <el-button type="primary" :disabled="!commitDialog.message.trim() || commitDialog.processing" :loading="commitDialog.processing" @click="confirmCommitDialog">提交</el-button>
      </div>
    </template>
  </el-dialog>

  <AttributeTableDialog
    :model="attributeTable"
    :changed-count="attributeTableChangedCount"
    :state-key="attributeRowStateKey"
    :status-label="attributeRowStatusLabel"
    :record-title="attributeRecordTitle"
    :format-time="formatHistoryTime"
    @update:visible="attributeTable.visible = $event"
    @toggle-route="toggleAttributeRouteStations"
    @reorder-row="reorderAttributeRouteStationRow"
    @reset="resetAttributeTableDraft"
    @remove-row="removeAttributeTableRow"
    @restore-row="restoreAttributeTableRow"
    @touch-row="markAttributeRowTouched"
    @apply="applyAttributeTableChanges"
  />

  <teleport to="body">
    <div v-if="builtUpDialog.visible" class="built-up-backdrop" @click.self="closeBuiltUpDialog">
      <div class="built-up-modal" role="dialog" aria-modal="true" aria-labelledby="built-up-title">
        <div class="built-up-head">
          <div class="built-up-head-text">
            <p class="built-up-kicker">常规公交站点覆盖率 · 分母设置</p>
            <h3 id="built-up-title">建成区面积</h3>
          </div>
          <button type="button" class="built-up-close" aria-label="关闭" @click="closeBuiltUpDialog">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><line x1="6" y1="6" x2="18" y2="18"></line><line x1="18" y1="6" x2="6" y2="18"></line></svg>
          </button>
        </div>
        <p class="built-up-desc">
          覆盖率 = 站点 300/500m 被服务面积 ÷ 建成区面积。留空则按{{ builtUpDialog.scopeLabel }}行政区面积
          <strong>{{ formatUnit(builtUpDialog.defaultAreaKm2, "km²") }}</strong> 计算。
        </p>
        <div class="built-up-scope">
          <span class="built-up-scope-tag">当前范围</span>
          <span class="built-up-scope-name">{{ builtUpDialog.scopeLabel }}</span>
        </div>
        <label class="built-up-field">
          <span class="built-up-field-label">建成区面积（km²）</span>
          <input
            ref="builtUpInputRef"
            v-model="builtUpDialog.input"
            class="built-up-input"
            type="number"
            inputmode="decimal"
            min="0"
            step="0.01"
            :placeholder="builtUpDialog.defaultAreaKm2 != null ? `留空＝行政区面积 ${formatUnit(builtUpDialog.defaultAreaKm2, 'km²')}` : '输入建成区面积'"
            @keyup.enter="saveBuiltUpDialog"
            @keydown.esc="closeBuiltUpDialog"
          />
        </label>
        <div class="built-up-preview" :class="{ 'is-missing': !builtUpPreview.available }">
          <div class="built-up-preview-item">
            <span>300 米</span>
            <strong>{{ formatPercent(builtUpPreview.rate300) }}</strong>
          </div>
          <div class="built-up-preview-divider"></div>
          <div class="built-up-preview-item">
            <span>500 米</span>
            <strong>{{ formatPercent(builtUpPreview.rate500) }}</strong>
          </div>
        </div>
        <p v-if="!builtUpPreview.available" class="built-up-warn">该范围暂无被服务面积数据，无法按建成区面积重算（请确认后端已下发分区覆盖）。</p>
        <template v-else>
          <p v-if="builtUpPreview.isCapped" class="built-up-warn">输入面积小于站点服务面积，覆盖率按 100% 封顶显示。</p>
          <p v-if="builtUpDialog.scope !== DISPLAY_RANGE_ALL" class="built-up-note">分区模式：此面积仅用于「{{ builtUpDialog.scopeLabel }}」，与其他分区分开保存。</p>
        </template>
        <div class="built-up-actions">
          <button type="button" class="built-up-btn ghost" @click="resetBuiltUpDialog">恢复默认</button>
          <span class="built-up-actions-right">
            <button type="button" class="built-up-btn" @click="closeBuiltUpDialog">取消</button>
            <button type="button" class="built-up-btn primary" @click="saveBuiltUpDialog">保存</button>
          </span>
        </div>
      </div>
    </div>
  </teleport>
</template>

<script setup>
import { ElMessage, ElMessageBox } from "element-plus";
import { commitRealDataEdits, compareRealDataShp, exportRealDataVersion } from "@/api/realData.js";
import { saveAs } from "file-saver";
import {
  ensureCachedRouteStops,
  getCachedAdminDistricts,
  getCachedAreaList,
  getCachedRealData,
  getCachedRealDataHistory,
  invalidateCachedHistory,
  invalidateCachedRealData,
  isRouteStopsDeferred,
  readCachedHistory,
  readCachedRealData,
} from "@/utils/realDataCache.js";
import "./tokens.css";
import {
  collectionFeatures,
  expandGeometryBounds,
  featureCollectionBounds,
  featureCollectionFromFeatures,
  filterCollectionsByDistrict,
  firstAvailableValue,
  lineCoordinatePaths,
  pointCoordinates,
  routeDataId,
  routeMatchKeys,
  routeStopSequence,
  validLngLat,
  valueOrEmpty,
} from "./districtFilterCore.js";
import AttributeTableDialog from "./components/AttributeTableDialog.vue";
import OverviewMetrics from "./components/OverviewMetrics.vue";
import HistoryPanel from "./components/HistoryPanel.vue";
import DmSidebar from "./components/DmSidebar.vue";
import MapSearchBox from "./components/MapSearchBox.vue";
import MapControlsToolbar from "./components/MapControlsToolbar.vue";
import { MAP_THEME } from "@/utils/mapTheme.js";
import busStationIconUrl from "@/assets/images/datamanagement/bus-station.svg?url";
import busStationHighlightIconUrl from "@/assets/images/datamanagement/bus-station_highlight.svg?url";
import busStationHighlightOutsideIconUrl from "@/assets/images/datamanagement/bus-station_highlight_outside.svg?url";
import busDepotIconUrl from "@/assets/images/datamanagement/bus-depot.svg?url";
import { lngLatToWebMercator } from "@/mymap/index.js";

defineOptions({
  name: "DataManagement",
});

const MapRef = inject("MapRef", ref(null));
const activeKey = ref("overview");
const searchBoxRef = ref(null);
const mapToolbarRef = ref(null);
const areaList = ref(["广州市"]);
const selectedArea = ref("广州市");
const DISPLAY_RANGE_ALL = "全市";
const selectedDisplayRange = ref(DISPLAY_RANGE_ALL);
const displayRangeList = ref([DISPLAY_RANGE_ALL]);
const isLoadingDisplayRanges = ref(false);
const displayRangeError = ref("");
const isLoadingAreas = ref(false);
const isLoadingLayer = ref(false);
const isLoadingHistory = ref(false);
const loadError = ref("");
const historyError = ref("");
const realDataRevision = ref(0);
const realDataCollectionsRevision = ref(0);
const realDataVersionId = ref("__base__");
const historyVersions = ref([]);
const historyExportLoadingKey = ref("");
const historyPreview = reactive({
  visible: false,
  loading: false,
  version: null,
  error: "",
});
const historyDetails = reactive({
  visible: false,
  record: null,
});
const historySummary = reactive({
  revision: 0,
  currentVersionId: "",
  activeVersionId: "__base__",
  activeDataVersionId: "__base__",
  versionCount: 0,
  operationCount: 0,
  updatedAt: 0,
  lastSwitchBy: "",
  lastSwitchAt: 0,
});
const overviewStats = reactive({
  lineCount: 0,
  networkScaleKm: null,
  networkDensityKmPerKm2: null,
  stationCount: 0,
  stationCoverage300Rate: null,
  stationCoverage500Rate: null,
  // 全市被服务面积（分子）——供按"建成区面积"重算覆盖率
  stationCoverage300Km2: null,
  stationCoverage500Km2: null,
  adminAreaKm2: null,
});
// 各行政区覆盖面积映射：{ 区名: { coverage300Km2, coverage500Km2, areaKm2, coverage300Rate, coverage500Rate } }
const overviewDistrictCoverage = ref({});
let overviewStatsBaseline = {
  lineCount: 0,
  networkScaleKm: null,
  networkDensityKmPerKm2: null,
  stationCount: 0,
  adminAreaKm2: null,
};
const lineWidth = ref(1.2);
const stationSize = ref(32);
const is3DActive = ref(false);
const isLeftPanelCollapsed = ref(false);
const isRightPanelCollapsed = ref(false);
const selectedStation = ref(null);
const selectedRoute = ref(null);
const selectedDepot = ref(null);
const shpUploadInput = ref(null);
const evidenceImageInput = ref(null);
const isSubmittingEdit = ref(false);
const pendingAddDataset = ref("");
const EDIT_OPERATION_RENDER_BATCH = 300;
const SHP_DELETION_PAGE_SIZE = 200;
const MAX_IMMEDIATE_PREVIEW_OPERATIONS = 500;
const editOperationRenderCount = ref(EDIT_OPERATION_RENDER_BATCH);
const shpDeletionDialog = reactive({
  visible: false,
  datasetType: "",
  updates: [],
  deletions: [],
  selectedIds: [],
  protectedFeatureCount: 0,
  page: 1,
});
const commitDialog = reactive({
  visible: false,
  message: "",
  evidenceImages: [],
  dragging: false,
  processing: false,
  resolver: null,
});
const lineRoutePicker = reactive({
  visible: false,
  x: 0,
  y: 0,
  routes: [],
  mode: "view",
  lngLat: null,
  point: null,
});
const editActionMenu = reactive({
  visible: false,
  x: 0,
  y: 0,
  title: "",
  datasetType: "",
  target: null,
  lngLat: null,
  actions: [],
});
const editDialog = reactive({
  visible: false,
  title: "",
  action: "",
  datasetType: "",
  target: null,
  lngLat: null,
  fields: [],
  form: {
    name: "",
    headway: "",
    stations: "",
  },
});
const attributeTable = reactive({
  visible: false,
  datasetType: "",
  viewDatasetType: "",
  title: "",
  subtitle: "",
  target: null,
  route: null,
  station: null,
  scope: "",
  showRouteStations: false,
  columns: [],
  historyColumns: [],
  rows: [],
  originalRows: [],
  viewCache: {},
  historyLoading: false,
  historyError: "",
  historyRows: [],
});
const pendingMoveTarget = ref(null);
const editOperations = reactive({
  station: [],
  line: [],
  depot: [],
});
let areaRequestSeq = 0;
let displayRangeRequestSeq = 0;
let layerRequestSeq = 0;
let historyRequestSeq = 0;
let attributeHistoryRequestSeq = 0;
let restoringAreaSelection = false;
let confirmedAreaSelection = false;
let zoomListenerId = null;
let rotateListenerId = null;
let stationClickListenerId = null;
let selectableHoverListenerId = null;
let stationSearchIndex = [];
let lineSearchIndex = [];
let depotSearchIndex = [];
let realDataRenderToken = 0;
let realDataSourceDataRefs = new Map();
let displayRangeFilterCache = new Map();
let routeStopIndexCache = { token: -1, collection: null, byRouteId: new Map() };
// 搜索索引与点选索引的重建守卫：集合引用未变且未被本地编辑弄脏时跳过全量重建
let searchIndexSource = { lines: null, stations: null, routeStops: null, depots: null };
let searchIndexesDirty = false;
// 站点点击热路径索引：stop_id/名称 -> routeStops、线路名/线路键 -> 搜索索引项（替代全量线性扫描）
let stationRouteLookup = { byStopId: new Map(), byStopKey: new Map(), byStopName: new Map() };
let lineLookup = { byName: new Map(), byEntry: new Map() };
let lineFeatureIndexCache = { token: -1, collection: null, byKey: new Map() };
// 规范化结果单槽缓存：同一份缓存数据在页面切换时跳过全量 normalize + 索引重建 + setData
let lastNormalizedData = null;
let lastNormalizedCollections = null;
// 规范化时的 data.routeStops 引用：懒加载合并会替换该引用；若合并发生在集合容器被
// clearRealDataLayers 换代之后（如停留历史页时），命中校验因引用不一致自动降级为全量重建
let lastNormalizedRouteStopsRaw = null;
let suppressNextPanelToggleClick = false;
let realDataCollections = {
  lines: emptyFeatureCollection(),
  stations: emptyFeatureCollection(),
  routeStops: emptyFeatureCollection(),
  depots: emptyFeatureCollection(),
};
let realDataAllCollections = {
  lines: emptyFeatureCollection(),
  stations: emptyFeatureCollection(),
  routeStops: emptyFeatureCollection(),
  depots: emptyFeatureCollection(),
};
let adminDistrictCollection = emptyFeatureCollection();
const SOURCE_LINES = "dm-real-bus-lines-source";
const SOURCE_STATIONS = "dm-real-bus-stations-source";
const SOURCE_DEPOTS = "dm-real-bus-depots-source";
const SOURCE_SELECTED_STATION = "dm-real-bus-selected-station-source";
const SOURCE_SELECTED_LINE = "dm-real-bus-selected-line-source";
const SOURCE_SELECTED_ROUTE_STATIONS = "dm-real-bus-selected-route-stations-source";
const SOURCE_SELECTED_DEPOT = "dm-real-bus-selected-depot-source";
const SOURCE_DISTRICT_OUTLINE = "dm-admin-district-outline-source";
// 区域外灰色底图：仅承载"触及本区的线路的完整几何"及其区外站点，铺在正常图层之下，
// 使跨区线路的区内段正常高亮、区外段灰显；与本区无关的线路/站点/场站一律不显示
const SOURCE_BASE_LINES = "dm-real-bus-base-lines-source";
const SOURCE_BASE_STATIONS = "dm-real-bus-base-stations-source";
const SOURCE_BY_DATASET = { line: SOURCE_LINES, station: SOURCE_STATIONS, depot: SOURCE_DEPOTS };
const LAYER_LINES = "dm-real-bus-lines";
const LAYER_LINE_SELECTED = "dm-real-bus-line-selected";
const LAYER_STATIONS = "dm-real-bus-stations";
const LAYER_STATION_LABELS = "dm-real-bus-station-labels";
const LAYER_STATION_SELECTED = "dm-real-bus-station-selected";
const LAYER_ROUTE_STATION_SELECTED = "dm-real-bus-route-station-selected";
const LAYER_ROUTE_STATION_OUTSIDE = "dm-real-bus-route-station-outside";
const LAYER_ROUTE_STATION_LABELS = "dm-real-bus-route-station-labels";
const LAYER_DEPOTS = "dm-real-bus-depots";
const LAYER_DEPOT_LABELS = "dm-real-bus-depot-labels";
const LAYER_DEPOT_SELECTED = "dm-real-bus-depot-selected";
const LAYER_DISTRICT_OUTLINE = "dm-admin-district-outline";
const LAYER_BASE_LINES = "dm-real-bus-base-lines";
const LAYER_BASE_STATIONS = "dm-real-bus-base-stations";
const BASE_NETWORK_COLOR = MAP_THEME.network.outside;
const BASE_NETWORK_OPACITY = MAP_THEME.network.outsideOpacity;
// 选中线路的站点按 _outside 标记分流到灰/橙两个图标图层（同源同尺寸，只差配色）
const OUTSIDE_STATION_FILTER = ["==", ["get", "_outside"], true];
const INSIDE_STATION_FILTER = ["!=", ["get", "_outside"], true];
const SELECTED_LINE_COLOR = MAP_THEME.route.up;
const SELECTED_LINE_GLOW_COLOR = MAP_THEME.route.upHalo;
const NETWORK_LINE_COLOR = MAP_THEME.network.line;
const NETWORK_LINE_DIMMED_COLOR = MAP_THEME.network.dimmed;
const STATION_ICON_ID = "dm-real-bus-station-icon";
const STATION_HIGHLIGHT_ICON_ID = "dm-real-bus-station-highlight-icon";
const STATION_OUTSIDE_ICON_ID = "dm-real-bus-station-outside-icon";
const DEPOT_ICON_ID = "dm-real-bus-depot-icon";
const STATION_ICON_BASE_SIZE = 96;
const DEPOT_ICON_BASE_SIZE = 128;
const EARTH_RADIUS_METERS = 6378137;
const LINE_ATTRIBUTE_FIELD_ORDER = [
  "line_id",
  "dir",
  "route_id",
  "first",
  "last",
  "interval",
  "mode",
  "name",
  "price",
  "company",
];
const STATION_ATTRIBUTE_FIELD_ORDER = [
  "stop_id",
  "stop_name",
  "lon",
  "lat",
];
const ROUTE_STOP_ATTRIBUTE_FIELD_ORDER = [
  "line_id",
  "dir",
  "stop_id",
  "stop_name",
  "seq",
  "lon",
  "lat",
];
const LINE_ROUTE_STATION_FIELD_ORDER = [
  "seq",
  "stop_id",
  "stop_name",
];
const LINE_STATION_ORDER_HISTORY_COLUMN = {
  key: "station_order_change",
  label: "站序变化",
  wide: true,
};
const DERIVED_ATTRIBUTE_FIELDS = new Set([
  "len_km",
  "directness",
  "stop_count",
  "avg_stop_m",
  "route_cnt",
]);
const DERIVED_ATTRIBUTE_FIELD_ORDER = {
  line: ["len_km", "directness", "stop_count", "avg_stop_m"],
  station: ["route_cnt"],
  depot: [],
};
const EDIT_DATASET_TYPES = ["line", "station", "depot"];

const showMapSearch = computed(() => isMapDataPage(activeKey.value) || historyPreview.visible);
const activeEditDataset = computed(() => editDatasetFromKey(activeKey.value));
const hasRightSidePanel = computed(() => activeKey.value === "overview" || Boolean(activeEditDataset.value) || historyPreview.visible);
const hasVisibleRightSidePanel = computed(() => hasRightSidePanel.value && !isRightPanelCollapsed.value);
const displayRangeOptions = computed(() => {
  const names = [];
  const seen = new Set();
  for (const item of displayRangeList.value) {
    const name = String(item || "").trim();
    if (!name || name === DISPLAY_RANGE_ALL || seen.has(name)) continue;
    seen.add(name);
    names.push(name);
  }
  return names;
});
const searchPlaceholder = computed(() => {
  if (activeKey.value === "update_station") return "搜索站点";
  if (activeKey.value === "update_line") return "搜索线路";
  if (activeKey.value === "update_depot") return "搜索场站";
  return "搜索站点/线路";
});
const hasActiveDetail = computed(() => Boolean(selectedStation.value || selectedRoute.value || selectedDepot.value));
const DEPOT_NAME_KEYS = ["depot_name", "name", "场站名称", "station_name", "名称", "F002"];
const DEPOT_COORD_KEYS = ["F026", "coordinates", "lonlat", "经纬度", "坐标"];
const DEPOT_PANEL_HIDDEN_KEYS = ["F009", "F001"];
const selectedDepotInfo = computed(() => {
  const properties = selectedDepot.value?.properties || {};
  let location = "";
  let locationKey = "";
  for (const key of DEPOT_COORD_KEYS) {
    const value = properties[key];
    if (value && /-?\d+(\.\d+)?\s*,\s*-?\d+(\.\d+)?/.test(String(value))) {
      location = String(value).trim();
      locationKey = key;
      break;
    }
  }
  const rows = [];
  for (const [key, value] of Object.entries(properties)) {
    if (String(key).startsWith("_")) continue;
    if (DEPOT_NAME_KEYS.includes(key) || key === locationKey || DEPOT_PANEL_HIDDEN_KEYS.includes(key)) continue;
    const text = value == null ? "" : String(value).trim();
    if (!text || text === "/" || /^\*+$/.test(text)) continue;
    rows.push({ key, label: attributeColumnLabel(key), value: text, wide: text.length > 13 });
  }
  return { location, rows };
});
const selectedRouteStations = computed(() => {
  if (!selectedRoute.value) return [];
  return getRouteStations(selectedRoute.value.properties, selectedRoute.value);
});
const selectedStationHasPassengerFlow = computed(() => {
  const routes = Array.isArray(selectedStation.value?.routes) ? selectedStation.value.routes : [];
  return routes.some((route) => hasPassengerFlow(route.passengerFlow));
});
const selectedStationRouteCount = computed(() => {
  const routes = Array.isArray(selectedStation.value?.routes) ? selectedStation.value.routes : [];
  const seen = new Set();
  let count = 0;
  for (const route of routes) {
    const key = physicalLineKey(route?.feature || route);
    if (!key || seen.has(key)) continue;
    seen.add(key);
    count += 1;
  }
  return count;
});
// MapSearchBox 的 search-fn：按当前页面模式在对应索引里评分排序。
// 组件内部对入参做了防抖，这里保持纯函数即可
function searchIndexEntries(rawKeyword) {
  const query = normalizeSearchText(rawKeyword);
  if (!query) return [];
  const isOverviewSearch = activeKey.value === "overview" || historyPreview.visible;
  const stationItems = isOverviewSearch || activeKey.value === "update_station" ? rankSearchItems(stationSearchIndex, query) : [];
  const lineItems = isOverviewSearch || activeKey.value === "update_line" ? rankSearchItems(lineSearchIndex, query) : [];
  const depotItems = isOverviewSearch || activeKey.value === "update_depot" ? rankSearchItems(depotSearchIndex, query) : [];
  // 排序在轻量 {item, score} 条目上进行，仅对最终 8 条做对象展开
  return [...stationItems, ...lineItems, ...depotItems]
    .sort((left, right) => left.score - right.score || left.item.name.localeCompare(right.item.name, "zh-Hans-CN"))
    .slice(0, 8)
    .map(({ item, score }) => ({ ...item, score }));
}
const activeEditOperations = computed(() =>
  EDIT_DATASET_TYPES.flatMap((datasetType) =>
    editOperations[datasetType].map((operation) => ({
      ...operation,
      datasetType: operation.datasetType || datasetType,
    })),
  ),
);
const visibleActiveEditOperations = computed(() =>
  activeEditOperations.value.slice(0, editOperationRenderCount.value),
);
const visibleShpDeletionCandidates = computed(() => {
  const start = (shpDeletionDialog.page - 1) * SHP_DELETION_PAGE_SIZE;
  return shpDeletionDialog.deletions.slice(start, start + SHP_DELETION_PAGE_SIZE);
});
const pendingEditDatasetSummary = computed(() =>
  EDIT_DATASET_TYPES
    .map((datasetType) => ({
      datasetType,
      label: datasetTypeLabel(datasetType),
      count: editOperations[datasetType].length,
    }))
    .filter((item) => item.count > 0),
);
const hasAnyUnsavedEdits = computed(() => editOperations.station.length + editOperations.line.length + editOperations.depot.length > 0);
const attributeTableChangedCount = computed(() => attributeTableOperationCount());
const operatorLineRows = computed(() => {
  const collectionsRevision = realDataCollectionsRevision.value;
  const counts = new Map();
  const lineGroups = collectionsRevision >= 0 ? physicalLineGroups(realDataCollections.lines) : [];
  const totalLineCount = lineGroups.length;
  lineGroups.forEach((group) => {
    const companies = new Set();
    group.features.forEach((feature) => {
      splitOperatorCompanies(feature?.properties?.company).forEach((company) => companies.add(company));
    });
    companies.forEach((company) => {
      counts.set(company, (counts.get(company) || 0) + 1);
    });
  });
  const rows = [...counts.entries()]
    .map(([company, lineCount]) => ({
      company,
      lineCount,
      lineShare: totalLineCount ? (lineCount / totalLineCount) * 100 : null,
      vehicleCount: "-",
      vehicleShare: "-",
    }))
    .sort((left, right) => right.lineCount - left.lineCount || left.company.localeCompare(right.company, "zh-Hans-CN"));
  rows.push({
    company: "总计",
    lineCount: totalLineCount,
    lineShare: totalLineCount ? 100 : 0,
    vehicleCount: "-",
    vehicleShare: "-",
    isTotal: true,
  });
  return rows;
});
// 车辆数/配车占比目前无真实数据源；仅当出现真实数值时才展示这两列，否则收敛为三列表格
const overviewHasVehicleData = computed(() =>
  operatorLineRows.value.some((row) => !row.isTotal && Number.isFinite(Number(row.vehicleCount))),
);
// 数据总览是否为空：无线路且无站点即视为空态（错误态复位后也会落到这里，故渲染顺序为 加载→错误→空→数据）
const isOverviewEmpty = computed(() => !overviewStats.lineCount && !overviewStats.stationCount);

// ── 建成区面积覆写：覆盖率默认按行政区/分区面积为分母，用户可改用实际建成区面积；按「区域 + 分区」分开持久化 ──
const BUILT_UP_STORAGE_KEY = "dm.builtUpArea.v1";
function loadBuiltUpOverrides() {
  if (typeof window === "undefined") return {};
  try {
    const parsed = JSON.parse(window.localStorage?.getItem(BUILT_UP_STORAGE_KEY) || "null");
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}
const builtUpOverrides = ref(loadBuiltUpOverrides());
function builtUpOverrideFor(area, scope) {
  const number = Number(builtUpOverrides.value?.[area]?.[scope]);
  return Number.isFinite(number) && number > 0 ? number : null;
}
function setBuiltUpOverride(area, scope, value) {
  const next = { ...builtUpOverrides.value };
  const scoped = { ...(next[area] || {}) };
  const number = Number(value);
  if (Number.isFinite(number) && number > 0) {
    scoped[scope] = Number(number.toFixed(4));
  } else {
    delete scoped[scope];
  }
  if (Object.keys(scoped).length) next[area] = scoped;
  else delete next[area];
  builtUpOverrides.value = next;
  if (typeof window !== "undefined") {
    try {
      window.localStorage?.setItem(BUILT_UP_STORAGE_KEY, JSON.stringify(builtUpOverrides.value));
    } catch {
      /* 隐私模式/配额不足时静默失败 */
    }
  }
}

// 当前范围（全市 / 某行政区）的覆盖率视图：分子取被服务面积，分母优先用建成区覆写，否则用行政区/分区面积；
// 分子缺失（如后端未下发分区覆盖）时回退后端速率，避免直接"暂无"
const coverageView = computed(() => {
  const scope = selectedDisplayRange.value || DISPLAY_RANGE_ALL;
  const isCity = scope === DISPLAY_RANGE_ALL;
  const district = isCity ? null : overviewDistrictCoverage.value?.[scope] || null;
  const covered300 = isCity ? nullableNumber(overviewStats.stationCoverage300Km2) : nullableNumber(district?.coverage300Km2);
  const covered500 = isCity ? nullableNumber(overviewStats.stationCoverage500Km2) : nullableNumber(district?.coverage500Km2);
  const defaultAreaKm2 = isCity ? nullableNumber(overviewStats.adminAreaKm2) : nullableNumber(district?.areaKm2);
  const backend300 = isCity ? nullableNumber(overviewStats.stationCoverage300Rate) : nullableNumber(district?.coverage300Rate);
  const backend500 = isCity ? nullableNumber(overviewStats.stationCoverage500Rate) : nullableNumber(district?.coverage500Rate);
  const override = builtUpOverrideFor(selectedArea.value, scope);
  const denom = override != null ? override : defaultAreaKm2;
  const rateOf = (covered) => (covered != null && denom != null && denom > 0 ? (covered / denom) * 100 : null);
  const rawRate = (covered, backend) => {
    const value = rateOf(covered);
    if (value != null) return value;
    // 有覆写却拿不到分子 → 无法计算；无覆写 → 回退后端速率
    return override != null ? null : backend;
  };
  const raw300 = rawRate(covered300, backend300);
  const raw500 = rawRate(covered500, backend500);
  // 建成区面积 < 服务footprint 时原始值可能 >100%，显示封顶到 100% 并标注
  const cap = (rate) => (rate != null && rate > 100 ? 100 : rate);
  return {
    scope,
    scopeLabel: isCity ? DISPLAY_RANGE_ALL : scope,
    isCity,
    covered300,
    covered500,
    defaultAreaKm2,
    builtUpAreaKm2: override,
    usingOverride: override != null,
    denominatorKm2: denom,
    hasCoveredArea: covered300 != null || covered500 != null,
    rate300: cap(raw300),
    rate500: cap(raw500),
    isCapped: (raw300 != null && raw300 > 100) || (raw500 != null && raw500 > 100),
  };
});

// 建成区面积设置弹窗
const builtUpInputRef = ref(null);
const builtUpDialog = reactive({
  visible: false,
  scope: DISPLAY_RANGE_ALL,
  scopeLabel: DISPLAY_RANGE_ALL,
  input: "",
  defaultAreaKm2: null,
  covered300: null,
  covered500: null,
});
// 弹窗内按当前输入实时预览覆盖率（输入非法/空则回落默认面积）
const builtUpPreview = computed(() => {
  const value = Number(builtUpDialog.input);
  const denom = Number.isFinite(value) && value > 0 ? value : builtUpDialog.defaultAreaKm2;
  const available = builtUpDialog.covered300 != null || builtUpDialog.covered500 != null;
  const rate = (covered) => (covered != null && denom != null && denom > 0 ? (covered / denom) * 100 : null);
  const raw300 = rate(builtUpDialog.covered300);
  const raw500 = rate(builtUpDialog.covered500);
  const cap = (r) => (r != null && r > 100 ? 100 : r);
  return {
    denom,
    available,
    rate300: cap(raw300),
    rate500: cap(raw500),
    isCapped: (raw300 != null && raw300 > 100) || (raw500 != null && raw500 > 100),
  };
});
function openBuiltUpDialog() {
  const view = coverageView.value;
  builtUpDialog.scope = view.scope;
  builtUpDialog.scopeLabel = view.scopeLabel;
  builtUpDialog.defaultAreaKm2 = view.defaultAreaKm2;
  builtUpDialog.covered300 = view.covered300;
  builtUpDialog.covered500 = view.covered500;
  builtUpDialog.input = view.builtUpAreaKm2 != null ? String(view.builtUpAreaKm2) : "";
  builtUpDialog.visible = true;
  nextTick(() => builtUpInputRef.value?.focus());
}
function closeBuiltUpDialog() {
  builtUpDialog.visible = false;
}
function saveBuiltUpDialog() {
  // 空或非正数等同恢复默认（清除覆写）
  setBuiltUpOverride(selectedArea.value, builtUpDialog.scope, builtUpDialog.input);
  builtUpDialog.visible = false;
}
function resetBuiltUpDialog() {
  setBuiltUpOverride(selectedArea.value, builtUpDialog.scope, null);
  builtUpDialog.input = "";
  builtUpDialog.visible = false;
}

const lineRoutePickerTitle = computed(() => {
  if (lineRoutePicker.mode === "edit") return "选择经过该路段的线路";
  return "选择线路";
});
const lineRoutePickerHint = computed(() => {
  if (lineRoutePicker.mode === "edit") return "选中线路后打开筛选后的属性表。";
  return "";
});
const editDatasetKicker = computed(() => {
  if (pendingEditDatasetSummary.value.length > 1) return "当前工具 · 跨类型联合编辑";
  if (activeEditDataset.value === "station") return "当前工具 · 站点编辑";
  if (activeEditDataset.value === "line") return "当前工具 · 线路编辑";
  if (activeEditDataset.value === "depot") return "当前工具 · 场站编辑";
  return "数据编辑";
});
const editDatasetTitle = computed(() => {
  return pendingEditDatasetSummary.value.length > 1 ? "联合数据更新" : "数据更新";
});
const editModeGuide = computed(() => {
  const guides = {
    station: {
      title: pendingAddDataset.value === "station" ? "在地图上选择新站点位置" : selectedStation.value ? "已打开当前站点属性" : "选择站点后自动打开属性表",
      description: pendingAddDataset.value === "station" ? "下一次点击地图会打开新站点属性表。" : selectedStation.value ? "可直接维护当前物理站点，也可上传完整 SHP 自动比对。" : "可搜索站点、点击已有站点，或新增站点。",
      steps: ["属性表只显示当前物理站点", "新增站点会自动写入点选经纬度", "生成的修改会在此逐条核对后提交"],
      actionLabel: "新增站点",
      canStartAdd: true,
    },
    line: {
      title: selectedRoute.value ? "已打开当前线路属性" : "选择线路后自动打开属性表",
      description: selectedRoute.value ? "可维护线路属性和站点编组，也可上传完整 SHP 自动比对。" : "可搜索线路，也可点击地图上的线路。多条线路重叠时先选择要编辑的线路。",
      steps: ["拖动站序手柄调整顺序，也可从线路中移除站点", "站点名称与位置需在站点更新中维护", "线路历史按时间倒序显示"],
      actionLabel: "",
      canStartAdd: false,
    },
    depot: {
      title: pendingAddDataset.value === "depot" ? "在地图上选择新场站位置" : selectedDepot.value ? "已打开当前场站属性" : "选择场站后自动打开属性表",
      description: pendingAddDataset.value === "depot" ? "下一次点击地图空白处会打开新增场站表单。" : "可搜索场站，也可直接点击地图上的场站。",
      steps: ["属性表只显示选中场站记录", "可编辑单元格，也可新增或删除行", "点击地图空白处可新增场站", "生成的修改会在此逐条核对后提交"],
      actionLabel: "新增场站",
      canStartAdd: true,
    },
  };
  return guides[activeEditDataset.value] || {
    title: "选择数据类型",
    description: "从左侧进入线路、站点或场站更新。",
    steps: [],
    actionLabel: "",
    canStartAdd: false,
  };
});
const panelKicker = computed(() => {
  if (historyPreview.visible && selectedStation.value) return "历史站点详情";
  if (historyPreview.visible && selectedRoute.value) return "历史线路详情";
  if (historyPreview.visible && selectedDepot.value) return "历史场站详情";
  if (historyPreview.visible) return "历史数据预览";
  if (selectedStation.value) return "站点详情";
  if (selectedRoute.value) return "线路详情";
  if (selectedDepot.value) return "场站详情";
  return "真实数据";
});
const panelTitle = computed(() => {
  if (historyPreview.visible && selectedStation.value) return "选中站点";
  if (historyPreview.visible && selectedRoute.value) return "选中线路";
  if (historyPreview.visible && selectedDepot.value) return "选中场站";
  if (historyPreview.visible) return "数据总览";
  if (selectedStation.value) return "选中站点";
  if (selectedRoute.value) return "选中线路";
  if (selectedDepot.value) return "选中场站";
  return "数据总览";
});
const activeHistoryVersionLabel = computed(() => {
  const activeId = historySummary.activeVersionId || historySummary.activeDataVersionId || "__base__";
  const active = historyVersions.value.find((item) => item.versionId === activeId);
  if (!active) return activeId === "__base__" ? "原始数据" : activeId;
  return historyRecordTitle(active);
});
const historyDetailGroups = computed(() => {
  const operations = Array.isArray(historyDetails.record?.operations) ? historyDetails.record.operations : [];
  return [
    { key: "line", label: "线路", rows: historyOperationRows(operations, "line") },
    { key: "station", label: "站点", rows: historyOperationRows(operations, "station") },
    { key: "depot", label: "场站", rows: historyOperationRows(operations, "depot") },
  ];
});
const editDialogSubtitle = computed(() => {
  if (!editDialog.action) return "";
  const targetName = editDialog.target ? editTargetName(editDialog.datasetType, editDialog.target) : "";
  if (editDialog.action.startsWith("add_")) return `新增${datasetTypeLabel(editDialog.datasetType)}会先进入右侧待提交列表。`;
  if (targetName) return `正在编辑「${targetName}」，确认后可在右侧待提交列表核对。`;
  return "确认后可在右侧待提交列表核对。";
});


let mapChromeResizeTimer = 0;
function scheduleMapChromeResize() {
  nextTick(() => {
    MapRef.value?.map?.resize?.();
  });
  // 面板开合动画时长 var(--dm2-dur)=240ms，动画结束后补一次即可，无需中间帧反复 resize
  window.clearTimeout(mapChromeResizeTimer);
  mapChromeResizeTimer = window.setTimeout(() => {
    MapRef.value?.map?.resize?.();
  }, 280);
}

function handlePanelTogglePointer(event, side) {
  event?.preventDefault?.();
  event?.stopPropagation?.();
  suppressNextPanelToggleClick = true;
  togglePanel(side);
}

function handlePanelToggleClick(event, side) {
  event?.stopPropagation?.();
  if (suppressNextPanelToggleClick) {
    suppressNextPanelToggleClick = false;
    return;
  }
  togglePanel(side);
}

function togglePanel(side) {
  if (side === "left") {
    toggleLeftPanel();
    return;
  }
  if (side === "right") {
    toggleRightPanel();
  }
}

function toggleLeftPanel() {
  isLeftPanelCollapsed.value = !isLeftPanelCollapsed.value;
  scheduleMapChromeResize();
}

function toggleRightPanel() {
  isRightPanelCollapsed.value = !isRightPanelCollapsed.value;
  closeRangePopover();
  closeStylePopover();
  scheduleMapChromeResize();
}

async function setActiveKey(key) {
  if (!key || key === activeKey.value) return;
  if (!isUpdateModeSwitch(activeKey.value, key)) {
    const canLeave = await confirmLeaveWithUnsavedEdits();
    if (!canLeave) return;
  }
  activeKey.value = key;
}

function isUpdateModeSwitch(fromKey, toKey) {
  return Boolean(editDatasetFromKey(fromKey) && editDatasetFromKey(toKey));
}


async function handleGetAreaList() {
  const seq = ++areaRequestSeq;
  isLoadingAreas.value = true;
  try {
    const list = await getCachedAreaList();
    if (seq !== areaRequestSeq) return;
    areaList.value = list.length ? list : ["广州市"];
    if (!selectedArea.value || !areaList.value.includes(selectedArea.value)) {
      selectedArea.value = areaList.value[0] || "";
    }
  } finally {
    if (seq === areaRequestSeq) {
      isLoadingAreas.value = false;
    }
  }
}

async function loadDisplayRanges(options = {}) {
  const { force = false } = options;
  const areaName = selectedArea.value;
  if (!areaName) return;
  const seq = ++displayRangeRequestSeq;
  isLoadingDisplayRanges.value = true;
  displayRangeError.value = "";
  try {
    const data = await getCachedAdminDistricts(areaName, { force });
    if (seq !== displayRangeRequestSeq || selectedArea.value !== areaName) return;
    adminDistrictCollection = normalizeAdminDistrictCollection(data?.collection);
    const names = Array.isArray(data?.districts)
      ? data.districts.map((item) => String(item || "").trim()).filter(Boolean)
      : districtNamesFromCollection(adminDistrictCollection);
    displayRangeList.value = [DISPLAY_RANGE_ALL, ...names.filter((name, index, list) => name !== DISPLAY_RANGE_ALL && list.indexOf(name) === index)];
    if (!displayRangeList.value.includes(selectedDisplayRange.value)) {
      selectedDisplayRange.value = DISPLAY_RANGE_ALL;
    }
    applyDisplayRangeFilter({ updateSources: true });
  } catch (error) {
    if (seq !== displayRangeRequestSeq) return;
    adminDistrictCollection = emptyFeatureCollection();
    displayRangeList.value = [DISPLAY_RANGE_ALL];
    selectedDisplayRange.value = DISPLAY_RANGE_ALL;
    displayRangeError.value = error?.message || "行政区范围加载失败";
  } finally {
    if (seq === displayRangeRequestSeq) {
      isLoadingDisplayRanges.value = false;
    }
  }
}

function mapDataMode(key = activeKey.value) {
  if (key === "overview") return "overview";
  if (key === "update_station") return "station_update";
  if (key === "update_line") return "line_update";
  if (key === "update_depot") return "depot_update";
  return "";
}

function isMapDataPage(key = activeKey.value) {
  return Boolean(mapDataMode(key));
}

function editDatasetFromKey(key = activeKey.value) {
  if (key === "update_station") return "station";
  if (key === "update_line") return "line";
  if (key === "update_depot") return "depot";
  return "";
}

async function loadOverviewLayers(options = {}) {
  const { force = false, fit = false } = options;
  const mode = mapDataMode();
  const areaName = selectedArea.value;
  if (!areaName || !mode) return;
  const cachedData = readCachedRealData(areaName);
  if (!force && cachedData) {
    const data = cachedData;
    setOverviewStats(data);
    syncHistorySummary(data.history);
    renderRealDataLayers(data, mode);
    if (fit) fitBounds(data.bounds);
    scheduleRouteStopsHydration(data);
    return;
  }
  const seq = ++layerRequestSeq;
  isLoadingLayer.value = true;
  loadError.value = "";
  try {
    const data = await getCachedRealData(areaName, { force });
    // 与 loadDisplayRanges 对称的双重守卫：seq 之外再校验区域未被切换（如首载并行时 areaList 纠正了 selectedArea）
    if (seq !== layerRequestSeq || selectedArea.value !== areaName) return;
    setOverviewStats(data);
    syncHistorySummary(data.history);
    renderRealDataLayers(data, mode);
    if (fit) fitBounds(data.bounds);
    scheduleRouteStopsHydration(data);
  } catch (error) {
    if (seq === layerRequestSeq) {
      loadError.value = error?.message || "真实数据加载失败";
      resetOverviewStats();
      clearRealDataLayers();
    }
  } finally {
    if (seq === layerRequestSeq) {
      isLoadingLayer.value = false;
    }
  }
}

// routeStops 懒加载水合：首屏只拉核心数据（去掉最大的一份要素），routeStops 到达后
// 原地并入同一 data 对象并重建派生索引/区划缓存。竞态由 token + 区域/数据引用三重校验兜住。
let routeStopsHydrationToken = 0;
function scheduleRouteStopsHydration(data) {
  if (!isRouteStopsDeferred(data)) return;
  const token = ++routeStopsHydrationToken;
  const areaName = selectedArea.value;
  ensureCachedRouteStops(areaName)
    .then((merged) => {
      if (token !== routeStopsHydrationToken || selectedArea.value !== areaName) return;
      if (merged !== lastNormalizedData) return;
      // 集合容器已被 clearRealDataLayers 换代（如停留历史页）：不写入废弃容器；
      // 返回地图页时命中校验会因 routeStopsRaw 引用不一致而全量重建，数据不丢
      if (realDataAllCollections !== lastNormalizedCollections) return;
      if (isRouteStopsDeferred(merged)) return;
      hydrateRouteStops(merged);
    })
    .catch(() => {});
}

function hydrateRouteStops(data) {
  lastNormalizedRouteStopsRaw = data.routeStops;
  mutateRealDataCollections(() => {
    realDataAllCollections.routeStops = normalizeFeatureCollection(data.routeStops);
    // 水合前产生的站点类本地编辑预览是打在旧（空）集合上的，重放到新集合保证预览不丢
    editOperations.station.forEach((operation) => applyUploadOperationPreview("station", operation));
    if (editOperations.station.length) {
      realDataAllCollections.stations = deriveStationsFromRouteStops(realDataAllCollections.routeStops);
    }
  }, { datasets: ["station"] });
  syncSelectedStationWithCurrentData();
}

function setOverviewStats(data) {
  const overview = data?.overview || {};
  overviewStats.lineCount = overviewCountOrFallback(overview.lineCount, data?.lines, physicalLineKey, data?.lines?.featureCount);
  overviewStatsBaseline = {
    lineCount: overviewStats.lineCount,
    networkScaleKm: nullableNumber(overview.networkScaleKm),
    networkDensityKmPerKm2: nullableNumber(overview.networkDensityKmPerKm2),
    stationCount: overviewCountOrFallback(
      overview.stationCount,
      data?.stations || data?.routeStops,
      physicalStationKey,
      data?.stations?.featureCount ?? data?.routeStops?.featureCount,
    ),
    adminAreaKm2: nullableNumber(overview.adminAreaKm2),
  };
  overviewStats.networkScaleKm = overviewStatsBaseline.networkScaleKm;
  overviewStats.networkDensityKmPerKm2 = overviewStatsBaseline.networkDensityKmPerKm2;
  overviewStats.stationCount = overviewStatsBaseline.stationCount;
  overviewStats.stationCoverage300Rate = nullableNumber(overview.stationCoverage300Rate);
  overviewStats.stationCoverage500Rate = nullableNumber(overview.stationCoverage500Rate);
  overviewStats.stationCoverage300Km2 = nullableNumber(overview.stationCoverage300Km2);
  overviewStats.stationCoverage500Km2 = nullableNumber(overview.stationCoverage500Km2);
  overviewStats.adminAreaKm2 = overviewStatsBaseline.adminAreaKm2;
  overviewDistrictCoverage.value =
    overview.districtCoverage && typeof overview.districtCoverage === "object" ? overview.districtCoverage : {};
}

function syncHistorySummary(history = {}) {
  const revision = Number(history.revision ?? 0);
  realDataRevision.value = Number.isFinite(revision) ? revision : 0;
  realDataVersionId.value = history.activeVersionId || history.activeDataVersionId || "__base__";
  historySummary.revision = realDataRevision.value;
  historySummary.currentVersionId = history.currentVersionId || "";
  historySummary.activeVersionId = realDataVersionId.value;
  historySummary.activeDataVersionId = history.activeDataVersionId || "__base__";
  historySummary.versionCount = Number(history.versionCount ?? 0);
  historySummary.operationCount = Number(history.operationCount ?? 0);
  historySummary.updatedAt = Number(history.updatedAt ?? 0);
  historySummary.lastSwitchBy = history.lastSwitchBy || "";
  historySummary.lastSwitchAt = Number(history.lastSwitchAt ?? 0);
}

async function loadHistoryList(options = {}) {
  const { force = false } = options;
  if (!selectedArea.value) return;
  const cachedHistory = readCachedHistory(selectedArea.value);
  if (!force && cachedHistory) {
    const data = cachedHistory;
    syncHistorySummary(data);
    historyVersions.value = Array.isArray(data.versions) ? data.versions : [];
    return;
  }
  const seq = ++historyRequestSeq;
  isLoadingHistory.value = true;
  historyError.value = "";
  try {
    const data = await getCachedRealDataHistory(selectedArea.value, { force });
    if (seq !== historyRequestSeq) return;
    syncHistorySummary(data);
    historyVersions.value = Array.isArray(data.versions) ? data.versions : [];
  } catch (error) {
    if (seq === historyRequestSeq) {
      historyError.value = error?.message || "历史数据加载失败";
      historyVersions.value = [];
    }
  } finally {
    if (seq === historyRequestSeq) {
      isLoadingHistory.value = false;
    }
  }
}

async function exportHistoryVersion(record, options = {}) {
  const versionId = valueOrEmpty(record?.versionId);
  const datasetType = valueOrEmpty(options.datasetType);
  const format = valueOrEmpty(options.format).toLowerCase();
  if (!versionId || !["line", "station", "depot"].includes(datasetType) || !["csv", "shp"].includes(format)) {
    return;
  }
  const loadingKey = `${versionId}:${datasetType}:${format}`;
  if (historyExportLoadingKey.value) return;
  historyExportLoadingKey.value = loadingKey;
  try {
    const response = await exportRealDataVersion(
      {
        areaName: selectedArea.value,
        versionId,
        datasetType,
        format,
      },
      { silentError: true },
    );
    const contentType = String(response?.headers?.["content-type"] || response?.data?.type || "");
    if (contentType.includes("application/json")) {
      const errorText = await response.data.text();
      const errorPayload = JSON.parse(errorText || "{}");
      throw new Error(errorPayload.msg || "导出失败");
    }
    const fallbackName = `${selectedArea.value}_${versionId}_${datasetTypeLabel(datasetType)}.${format === "shp" ? "zip" : "csv"}`;
    saveAs(response.data, responseDownloadFileName(response?.headers, fallbackName));
    ElMessage.success(`${datasetTypeLabel(datasetType)}${format.toUpperCase()} 已开始下载`);
  } catch (error) {
    ElMessage.error(error?.message || "历史版本导出失败");
  } finally {
    historyExportLoadingKey.value = "";
  }
}

function responseDownloadFileName(headers = {}, fallbackName = "历史版本导出") {
  const disposition = String(headers?.["content-disposition"] || "");
  const encodedMatch = disposition.match(/filename\*=UTF-8''([^;]+)/i);
  if (encodedMatch?.[1]) {
    try {
      return decodeURIComponent(encodedMatch[1].trim());
    } catch {
      return encodedMatch[1].trim();
    }
  }
  const plainMatch = disposition.match(/filename="?([^";]+)"?/i);
  return plainMatch?.[1]?.trim() || fallbackName;
}

function resetOverviewStats() {
  overviewStats.lineCount = 0;
  overviewStats.networkScaleKm = null;
  overviewStats.networkDensityKmPerKm2 = null;
  overviewStats.stationCount = 0;
  overviewStats.stationCoverage300Rate = null;
  overviewStats.stationCoverage500Rate = null;
  overviewStats.stationCoverage300Km2 = null;
  overviewStats.stationCoverage500Km2 = null;
  overviewStats.adminAreaKm2 = null;
  overviewDistrictCoverage.value = {};
  overviewStatsBaseline = {
    lineCount: 0,
    networkScaleKm: null,
    networkDensityKmPerKm2: null,
    stationCount: 0,
    adminAreaKm2: null,
  };
}

function overviewCountOrFallback(value, collection, keyFn, fallback = 0) {
  const number = Number(value);
  if (Number.isFinite(number)) return Math.max(0, Math.round(number));
  return physicalCountOrFallback(collection, keyFn, fallback);
}

function nullableNumber(value) {
  if (value === undefined || value === null || value === "") return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function formatInteger(value) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.round(number).toLocaleString("zh-CN") : "暂无";
}

function formatUnit(value, unit, digits = 2) {
  const number = Number(value);
  return Number.isFinite(number) ? `${number.toLocaleString("zh-CN", { maximumFractionDigits: digits })} ${unit}` : "暂无";
}

function formatPercent(value) {
  const number = Number(value);
  return Number.isFinite(number) ? `${number.toLocaleString("zh-CN", { maximumFractionDigits: 2 })}%` : "暂无";
}

function ensureMapReady(callback) {
  const mapWrapper = MapRef.value;
  if (!mapWrapper?.map) return;
  mapWrapper.whenReady?.(() => callback(mapWrapper.map));
}

function renderRealDataLayers(data, mode = "overview") {
  ensureMapReady(async (map) => {
    clearSelectionState();
    const isOverview = mode === "overview";
    const isStationUpdate = mode === "station_update";
    const isLineUpdate = mode === "line_update";
    const isDepotUpdate = mode === "depot_update";
    if (data && data === lastNormalizedData && lastNormalizedCollections && lastNormalizedRouteStopsRaw === data.routeStops) {
      // 同一份缓存数据在页面间来回切换：复用规范化结果与全部派生索引，
      // setGeoJsonSourceData 因引用未变自动短路，整条链路无重计算
      realDataAllCollections = lastNormalizedCollections;
    } else {
      realDataRenderToken += 1;
      clearDisplayRangeFilterCache();
      realDataAllCollections = {
        lines: normalizeLineFeatureCollection(data.lines),
        stations: normalizeStationFeatureCollection(data.stations),
        routeStops: normalizeFeatureCollection(data.routeStops),
        depots: normalizeDepotFeatureCollection(data.depots),
      };
      lastNormalizedData = data || null;
      lastNormalizedCollections = realDataAllCollections;
      lastNormalizedRouteStopsRaw = data?.routeStops || null;
    }
    // applyDisplayRangeFilter 内部已重建三类搜索索引，这里不再重复构建（省一次上万要素遍历）
    applyDisplayRangeFilter({ updateSources: false, clearSelection: false });
    ensureSourceData(map, SOURCE_LINES, realDataCollections.lines);
    ensureSourceData(map, SOURCE_SELECTED_LINE, emptyFeatureCollection());
    ensureSourceData(map, SOURCE_STATIONS, realDataCollections.stations);
    ensureSourceData(map, SOURCE_SELECTED_STATION, emptyFeatureCollection());
    ensureSourceData(map, SOURCE_SELECTED_ROUTE_STATIONS, emptyFeatureCollection());
    ensureSourceData(map, SOURCE_DEPOTS, realDataCollections.depots);
    ensureSourceData(map, SOURCE_SELECTED_DEPOT, emptyFeatureCollection());
    // 灰色底图：初值置空，由 updateBaseNetworkLayers 按当前区域/选中线路填充触及本区线路的几何
    ensureSourceData(map, SOURCE_BASE_LINES, emptyFeatureCollection());
    ensureSourceData(map, SOURCE_BASE_STATIONS, emptyFeatureCollection());
    await ensureStationIcon(map);
    ensureRealDataLayerSet(map);
    setRealDataLayerVisibility(map, {
      lines: isOverview || isLineUpdate,
      stations: isOverview || isStationUpdate,
      stationLabels: isStationUpdate,
      depots: isOverview || isDepotUpdate,
      depotLabels: isDepotUpdate,
    });
    applyLayerPaint();
    bindStationClickListener();
  });
}

function applyMapDataMode(key = activeKey.value) {
  const mode = mapDataMode(key);
  if (!mode) return;
  ensureMapReady((map) => {
    const isOverview = mode === "overview";
    setRealDataLayerVisibility(map, {
      lines: isOverview || mode === "line_update",
      stations: isOverview || mode === "station_update",
      stationLabels: mode === "station_update",
      depots: isOverview || mode === "depot_update",
      depotLabels: mode === "depot_update",
    });
    syncRealDataSourceData();
    bindStationClickListener();
  });
}

function ensureSourceData(map, sourceId, data) {
  const source = map.getSource(sourceId);
  if (source?.setData) {
    setGeoJsonSourceData(sourceId, data, map);
    return;
  }
  map.addSource(sourceId, { type: "geojson", data });
  realDataSourceDataRefs.set(sourceId, data);
}

function setGeoJsonSourceData(sourceId, data, map = MapRef.value?.map) {
  const source = map?.getSource(sourceId);
  if (!source?.setData) return false;
  if (realDataSourceDataRefs.get(sourceId) === data) return false;
  source.setData(data);
  realDataSourceDataRefs.set(sourceId, data);
  return true;
}

function invalidateRenderedRealDataSources(options = {}) {
  const { datasets = EDIT_DATASET_TYPES, except = [] } = options;
  datasets.forEach((datasetType) => {
    if (except.includes(datasetType)) return;
    const sourceId = SOURCE_BY_DATASET[datasetType];
    if (sourceId) realDataSourceDataRefs.delete(sourceId);
  });
  // 灰色底图集合按裁剪版本记忆化：全量集合原地突变后强制下轮重算（版本号已在 applyDisplayRangeFilter 递增）
  districtBaseSignature = "";
  baseNetworkSelSignature = "";
  // 集合内容被原地修改而引用不变，派生索引必须强制重建
  searchIndexesDirty = true;
  if (datasets.includes("line")) {
    lineFeatureIndexCache = { token: -1, collection: null, byKey: new Map() };
  }
}

// 统一集合突变入口：对 realDataAllCollections 的一切本地修改都应经由此函数，由它成对完成
// 「区划缓存失效 + 图源/索引失效 + 重新应用过滤」，新增编辑路径不再需要记住手工调用顺序。
// sourceDiffs: { datasetType: () => diff }（在 mutate 之后求值）；全市视图下尝试 MapLibre
// updateData 增量更新，成功的数据集保留图源数据引用（跳过全量 setData 重切片），失败自动回退。
function mutateRealDataCollections(mutate, options = {}) {
  const { datasets = EDIT_DATASET_TYPES, clearSelection: shouldClearSelection = false, sourceDiffs = null } = options;
  if (typeof mutate === "function") mutate();
  const diffedDatasets = [];
  if (sourceDiffs && selectedDisplayRange.value === DISPLAY_RANGE_ALL) {
    for (const [datasetType, buildDiff] of Object.entries(sourceDiffs)) {
      if (typeof buildDiff !== "function") continue;
      if (applyGeoJsonSourceDiff(datasetType, buildDiff())) diffedDatasets.push(datasetType);
    }
  }
  clearDisplayRangeFilterCache();
  invalidateRenderedRealDataSources({ datasets, except: diffedDatasets });
  applyDisplayRangeFilter({ updateSources: true, clearSelection: shouldClearSelection });
}

function applyGeoJsonSourceDiff(datasetType, diff) {
  const sourceId = SOURCE_BY_DATASET[datasetType];
  const source = sourceId ? MapRef.value?.map?.getSource?.(sourceId) : null;
  if (!source?.updateData || !diff) return false;
  try {
    source.updateData(diff);
    return true;
  } catch {
    // 源中缺 id 等情况：回退全量 setData
    return false;
  }
}

function featureUpdateDiff(feature) {
  if (feature?.id === undefined || feature?.id === null) return null;
  return {
    update: [
      {
        id: feature.id,
        ...(feature.geometry ? { newGeometry: feature.geometry } : {}),
        addOrUpdateProperties: Object.entries(feature.properties || {})
          .map(([key, value]) => ({ key, value })),
      },
    ],
  };
}

function ensureRealDataLayerSet(map) {
  // 灰色底图三层（默认隐藏，仅选中行政区时显示）：铺在对应正常图层之下，
  // 正常图层绘制的是区域内裁剪后的集合，恰好盖住区域内部分 → 区域内彩色、区域外灰色
  if (!map.getLayer(LAYER_BASE_LINES)) {
    addLayerBelowBuildings(map, {
      id: LAYER_BASE_LINES,
      type: "line",
      source: SOURCE_BASE_LINES,
      layout: { visibility: "none", "line-join": "round", "line-cap": "round" },
      paint: {
        "line-color": BASE_NETWORK_COLOR,
        "line-opacity": BASE_NETWORK_OPACITY,
        "line-width": baseNetworkLineWidth(),
      },
    });
  }
  if (!map.getLayer(LAYER_BASE_STATIONS)) {
    map.addLayer({
      id: LAYER_BASE_STATIONS,
      type: "circle",
      source: SOURCE_BASE_STATIONS,
      layout: { visibility: "none" },
      paint: {
        "circle-color": BASE_NETWORK_COLOR,
        "circle-opacity": ["interpolate", ["linear"], ["zoom"], 9, 0.5, 13, 0.68, 15, 0.8],
        "circle-radius": ["interpolate", ["linear"], ["zoom"], 9, 1.6, 13, 2.8, 16, 4],
      },
    });
  }
  if (!map.getLayer(LAYER_LINES)) {
    addLayerBelowBuildings(map, {
      id: LAYER_LINES,
      type: "line",
      source: SOURCE_LINES,
      paint: {
        "line-color": lineColorPaint(),
        "line-opacity": lineOpacityPaint(),
        "line-width": networkLineWidth(),
      },
    });
  }
  if (!map.getLayer(LAYER_LINE_SELECTED + "-glow")) {
    addLayerBelowBuildings(map, {
      id: LAYER_LINE_SELECTED + "-glow",
      type: "line",
      source: SOURCE_SELECTED_LINE,
      layout: {
        "line-join": "round",
        "line-cap": "round",
      },
      paint: {
        "line-color": SELECTED_LINE_GLOW_COLOR,
        "line-opacity": MAP_THEME.route.haloOpacity,
        "line-width": selectedLineWidth() * MAP_THEME.route.haloWidthRatio,
        "line-blur": selectedLineWidth() * 0.9,
      },
    });
  }
  if (!map.getLayer(LAYER_LINE_SELECTED)) {
    addLayerBelowBuildings(map, {
      id: LAYER_LINE_SELECTED,
      type: "line",
      source: SOURCE_SELECTED_LINE,
      layout: {
        "line-join": "round",
        "line-cap": "round",
      },
      paint: {
        "line-color": SELECTED_LINE_COLOR,
        "line-opacity": 0.95,
        "line-width": selectedLineWidth(),
      },
    });
  }
  if (!map.getLayer(LAYER_STATIONS)) {
    map.addLayer({
      id: LAYER_STATIONS,
      type: "symbol",
      source: SOURCE_STATIONS,
      layout: stationIconLayout(),
      paint: {
        "icon-opacity": stationOpacityPaint(),
      },
    });
  }
  if (!map.getLayer(LAYER_STATION_LABELS)) {
    map.addLayer({
      id: LAYER_STATION_LABELS,
      type: "symbol",
      source: SOURCE_STATIONS,
      minzoom: 14,
      layout: stationLabelLayout(),
      paint: stationLabelPaint(),
    });
  }
  if (!map.getLayer(LAYER_STATION_SELECTED)) {
    map.addLayer({
      id: LAYER_STATION_SELECTED,
      type: "symbol",
      source: SOURCE_SELECTED_STATION,
      layout: stationIconLayout(STATION_HIGHLIGHT_ICON_ID, selectedStationIconScale()),
      paint: {
        "icon-opacity": 1,
      },
    });
  }
  // 选中线路的区外站点：与区内站点同尺寸、同形状，只换成区外灰（与灰色底图线路同色）
  if (!map.getLayer(LAYER_ROUTE_STATION_OUTSIDE)) {
    map.addLayer({
      id: LAYER_ROUTE_STATION_OUTSIDE,
      type: "symbol",
      source: SOURCE_SELECTED_ROUTE_STATIONS,
      filter: OUTSIDE_STATION_FILTER,
      layout: stationIconLayout(STATION_OUTSIDE_ICON_ID, selectedRouteStationIconScale()),
      paint: {
        "icon-opacity": 0.96,
      },
    });
  }
  if (!map.getLayer(LAYER_ROUTE_STATION_SELECTED)) {
    map.addLayer({
      id: LAYER_ROUTE_STATION_SELECTED,
      type: "symbol",
      source: SOURCE_SELECTED_ROUTE_STATIONS,
      filter: INSIDE_STATION_FILTER,
      layout: stationIconLayout(STATION_HIGHLIGHT_ICON_ID, selectedRouteStationIconScale()),
      paint: {
        "icon-opacity": 0.96,
      },
    });
  }
  if (!map.getLayer(LAYER_ROUTE_STATION_LABELS)) {
    map.addLayer({
      id: LAYER_ROUTE_STATION_LABELS,
      type: "symbol",
      source: SOURCE_SELECTED_ROUTE_STATIONS,
      minzoom: 14,
      layout: stationLabelLayout(),
      paint: routeStationLabelPaint(),
    });
  }
  if (!map.getLayer(LAYER_DEPOTS)) {
    map.addLayer({
      id: LAYER_DEPOTS,
      type: "symbol",
      source: SOURCE_DEPOTS,
      layout: depotIconLayout(DEPOT_ICON_ID, depotIconScale()),
      paint: {
        "icon-opacity": 0.96,
      },
    });
  }
  if (!map.getLayer(LAYER_DEPOT_LABELS)) {
    map.addLayer({
      id: LAYER_DEPOT_LABELS,
      type: "symbol",
      source: SOURCE_DEPOTS,
      minzoom: 14,
      layout: depotLabelLayout(),
      paint: stationLabelPaint(),
    });
  }
  if (!map.getLayer(LAYER_DEPOT_SELECTED)) {
    map.addLayer({
      id: LAYER_DEPOT_SELECTED,
      type: "symbol",
      source: SOURCE_SELECTED_DEPOT,
      layout: depotIconLayout(DEPOT_ICON_ID, selectedDepotIconScale()),
      paint: {
        "icon-opacity": 1,
      },
    });
  }
  ensureDistrictOutlineLayer(map);
  updateBaseNetworkLayers(map);
}

// 区域外灰色底图：数据 = 触及本区的线路全量几何（含区外段）及其区外站点；
// 可见性 = 选中了行政区 且 对应正常图层此刻可见
function updateBaseNetworkLayers(map = MapRef.value?.map) {
  if (!map?.getLayer?.(LAYER_BASE_LINES)) return;
  const base = currentBaseNetworkCollections();
  setGeoJsonSourceData(SOURCE_BASE_LINES, base.lines, map);
  setGeoJsonSourceData(SOURCE_BASE_STATIONS, base.stations, map);
  map.setPaintProperty(LAYER_BASE_LINES, "line-width", baseNetworkLineWidth());
  const districtActive = selectedDisplayRange.value !== DISPLAY_RANGE_ALL;
  const mirror = (baseId, coloredId) => {
    const visible = districtActive && isLayerVisible(map, coloredId);
    setLayerVisibility(map, baseId, visible ? "visible" : "none");
  };
  mirror(LAYER_BASE_LINES, LAYER_LINES);
  // 灰色站点跟随线网可见性；选中线路时该线的区外站点改由灰色站点图标绘制，小圆点隐去避免重叠
  const baseStationsVisible = districtActive
    && isLayerVisible(map, LAYER_LINES)
    && !selectedRouteStationsActive();
  setLayerVisibility(map, LAYER_BASE_STATIONS, baseStationsVisible ? "visible" : "none");
}

function isLayerVisible(map, layerId) {
  return Boolean(map.getLayer(layerId)) && map.getLayoutProperty(layerId, "visibility") !== "none";
}

const EMPTY_BASE_COLLECTIONS = { lines: emptyFeatureCollection(), stations: emptyFeatureCollection() };

function selectedRouteMatchKeys() {
  const properties = selectedRoute.value?.feature?.properties || selectedRoute.value?.properties || null;
  return properties ? routeMatchKeys(properties) : null;
}

// 两级记忆化：
//  ① 区级全量底图（触及本区所有线路的完整几何 + 其区外站点）—— 遍历全量线路/站序，按区划裁剪版本缓存，每区只算一次；
//  ② 选中线路时在①之上再做一次廉价过滤（只留该线路），按选中线路键缓存。
let districtBaseSignature = "";
let districtBaseFull = EMPTY_BASE_COLLECTIONS;
let baseNetworkSelSignature = "";
let baseNetworkCollections = EMPTY_BASE_COLLECTIONS;

function currentBaseNetworkCollections() {
  if (selectedDisplayRange.value === DISPLAY_RANGE_ALL) return EMPTY_BASE_COLLECTIONS;
  const full = districtBaseFullCollections();
  const selectedKeys = selectedRoute.value ? (selectedRouteMatchKeys() || []) : null;
  const selSignature = `${districtBaseSignature}|${selectedKeys ? selectedKeys.join("~") : ""}`;
  if (selSignature === baseNetworkSelSignature) return baseNetworkCollections;
  baseNetworkSelSignature = selSignature;
  if (!selectedKeys) {
    baseNetworkCollections = full;
  } else {
    const selSet = new Set(selectedKeys);
    const isSelected = (properties) => routeMatchKeys(properties || {}).some((key) => selSet.has(key));
    baseNetworkCollections = {
      lines: featureCollectionFromFeatures(collectionFeatures(full.lines).filter((f) => isSelected(f?.properties))),
      stations: featureCollectionFromFeatures(collectionFeatures(full.stations).filter((f) => isSelected(f?.properties))),
    };
  }
  return baseNetworkCollections;
}

function districtBaseFullCollections() {
  const signature = `${realDataCollectionsRevision.value}|${selectedDisplayRange.value}`;
  if (signature === districtBaseSignature) return districtBaseFull;
  districtBaseSignature = signature;
  districtBaseFull = computeDistrictBaseFull();
  return districtBaseFull;
}

// 计算触及本区的所有线路的完整几何 + 其区外站点。全为集合级运算（无逐点多边形测试）：
// 区内/区外判定复用 worker 已产出的区内裁剪结果（realDataCollections）。
function computeDistrictBaseFull() {
  // 触及本区的线路键集：来自区内裁剪后的线要素（有区内站序才会出现在这里）
  const touchingKeys = new Set();
  for (const feature of collectionFeatures(realDataCollections.lines)) {
    for (const key of routeMatchKeys(feature?.properties || {})) touchingKeys.add(key);
  }
  const isTouching = (properties) => routeMatchKeys(properties || {}).some((key) => touchingKeys.has(key));
  const lines = featureCollectionFromFeatures(
    collectionFeatures(realDataAllCollections.lines).filter((feature) => isTouching(feature?.properties)),
  );
  // 区内实体站点键集（正常图层已绘制，灰点里排除，避免区内站点被灰点盖住）
  const inRegionStopKeys = new Set();
  for (const feature of collectionFeatures(realDataCollections.routeStops)) {
    inRegionStopKeys.add(routeStopPhysicalKey(feature));
  }
  const seenStops = new Set();
  const stopFeatures = [];
  for (const feature of collectionFeatures(realDataAllCollections.routeStops)) {
    if (!isTouching(feature?.properties)) continue;
    const key = routeStopPhysicalKey(feature);
    if (inRegionStopKeys.has(key) || seenStops.has(key)) continue; // 区内站点 / 已收录去重
    seenStops.add(key);
    stopFeatures.push(feature);
  }
  return { lines, stations: featureCollectionFromFeatures(stopFeatures) };
}

// 实体站点唯一键（同一物理站被多条线经停时只画一个灰点）
function routeStopPhysicalKey(feature) {
  const properties = feature?.properties || {};
  const coordinate = pointCoordinates(feature?.geometry);
  return String(
    properties.stop_id ||
      properties.stopId ||
      properties.stop_name ||
      properties.name ||
      `${coordinate?.[0] ?? "x"}-${coordinate?.[1] ?? "y"}`,
  );
}

// 行政区边界虚线：与运行监测等模块统一的显示范围描边（选中某行政区时高亮其边界）
function ensureDistrictOutlineLayer(map) {
  if (!map.getSource(SOURCE_DISTRICT_OUTLINE)) {
    map.addSource(SOURCE_DISTRICT_OUTLINE, { type: "geojson", data: districtOutlineCollection() });
  }
  if (!map.getLayer(LAYER_DISTRICT_OUTLINE)) {
    map.addLayer({
      id: LAYER_DISTRICT_OUTLINE,
      type: "line",
      source: SOURCE_DISTRICT_OUTLINE,
      layout: {
        "line-join": "round",
        "line-cap": "round",
      },
      paint: {
        "line-color": "#1569de",
        "line-width": ["interpolate", ["linear"], ["zoom"], 8, 1.4, 12, 2.1, 15, 2.8],
        "line-opacity": 0.86,
        "line-dasharray": [3.2, 2.4],
      },
    });
  }
  updateDistrictOutlineLayer(map);
}

function districtOutlineCollection(context = activeDisplayRangeContext()) {
  const geometry = districtOutlineGeometry(context?.feature?.geometry);
  return geometry
    ? {
        type: "FeatureCollection",
        features: [{
          type: "Feature",
          id: context?.feature?.id || "active-display-range",
          geometry,
          properties: { ...(context?.feature?.properties || {}) },
        }],
      }
    : emptyFeatureCollection();
}

function districtOutlineGeometry(geometry) {
  if (!geometry) return null;
  if (geometry.type === "LineString" || geometry.type === "MultiLineString") return geometry;
  const rings = [];
  if (geometry.type === "Polygon") {
    (geometry.coordinates || []).forEach((ring) => {
      if (Array.isArray(ring) && ring.length >= 2) rings.push(ring);
    });
  } else if (geometry.type === "MultiPolygon") {
    (geometry.coordinates || []).forEach((polygon) => {
      (Array.isArray(polygon) ? polygon : []).forEach((ring) => {
        if (Array.isArray(ring) && ring.length >= 2) rings.push(ring);
      });
    });
  }
  if (!rings.length) return null;
  return rings.length === 1
    ? { type: "LineString", coordinates: rings[0] }
    : { type: "MultiLineString", coordinates: rings };
}

function updateDistrictOutlineLayer(map = MapRef.value?.map) {
  if (!map?.getLayer?.(LAYER_DISTRICT_OUTLINE)) return;
  const context = activeDisplayRangeContext();
  setGeoJsonSourceData(SOURCE_DISTRICT_OUTLINE, districtOutlineCollection(context), map);
  setLayerVisibility(map, LAYER_DISTRICT_OUTLINE, context ? "visible" : "none");
}

function setRealDataLayerVisibility(map, visibility) {
  const visible = "visible";
  const hidden = "none";
  setLayerVisibility(map, LAYER_LINES, visibility.lines ? visible : hidden);
  setLayerVisibility(map, LAYER_LINE_SELECTED + "-glow", visibility.lines ? visible : hidden);
  setLayerVisibility(map, LAYER_LINE_SELECTED, visibility.lines ? visible : hidden);
  setLayerVisibility(map, LAYER_STATIONS, visibility.stations ? visible : hidden);
  setLayerVisibility(map, LAYER_STATION_LABELS, visibility.stationLabels ? visible : hidden);
  setLayerVisibility(map, LAYER_STATION_SELECTED, visibility.stations ? visible : hidden);
  setLayerVisibility(map, LAYER_ROUTE_STATION_SELECTED, visibility.stations ? visible : hidden);
  setLayerVisibility(map, LAYER_ROUTE_STATION_OUTSIDE, visibility.stations ? visible : hidden);
  setLayerVisibility(map, LAYER_ROUTE_STATION_LABELS, visibility.lines ? visible : hidden);
  setLayerVisibility(map, LAYER_DEPOTS, visibility.depots ? visible : hidden);
  setLayerVisibility(map, LAYER_DEPOT_LABELS, visibility.depotLabels ? visible : hidden);
  setLayerVisibility(map, LAYER_DEPOT_SELECTED, visibility.depots ? visible : hidden);
  // 灰色底图可见性跟随对应正常图层，并叠加"已选行政区"门控
  updateBaseNetworkLayers(map);
}

function setLayerVisibility(map, layerId, visibility) {
  if (map.getLayer(layerId)) {
    map.setLayoutProperty(layerId, "visibility", visibility);
  }
}

function addLayerBelowBuildings(map, layer) {
  const beforeId = MapRef.value?.buildingLayerId;
  if (beforeId && map.getLayer?.(beforeId)) {
    map.addLayer(layer, beforeId);
    return;
  }
  map.addLayer(layer);
}

function stationIconLayout(iconId = STATION_ICON_ID, iconScale = stationIconScale()) {
  return {
    "icon-image": iconId,
    "icon-size": iconScale,
    "icon-anchor": "center",
    "icon-allow-overlap": true,
    "icon-ignore-placement": true,
    "icon-padding": 2,
  };
}

function stationLabelLayout() {
  return {
    "text-field": ["coalesce", ["get", "stop_name"], ["get", "name"], ""],
    "text-size": ["interpolate", ["linear"], ["zoom"], 9, 10, 12, 12, 15, 14],
    "text-anchor": "left",
    "text-offset": [1.05, 0],
    "text-max-width": 10,
    "text-allow-overlap": true,
    "text-ignore-placement": true,
    "text-padding": 3,
  };
}

function stationLabelPaint() {
  return {
    "text-color": MAP_THEME.station.label,
    "text-opacity": ["interpolate", ["linear"], ["zoom"], 8, 0.72, 11, 0.92, 14, 1],
    "text-halo-color": MAP_THEME.station.labelHalo,
    "text-halo-width": 1.5,
    "text-halo-blur": 0.4,
  };
}

// 选中线路的站名：区外站点的站名也走区外灰，与其图标/线路保持同一套配色
function routeStationLabelPaint() {
  return {
    ...stationLabelPaint(),
    "text-color": ["case", OUTSIDE_STATION_FILTER, BASE_NETWORK_COLOR, MAP_THEME.station.label],
  };
}

function depotLabelLayout() {
  return {
    "text-field": ["coalesce", ["get", "depot_name"], ["get", "name"], ["get", "场站名称"], ["get", "F002"], ""],
    "text-size": ["interpolate", ["linear"], ["zoom"], 9, 10, 12, 12, 15, 14],
    "text-anchor": "left",
    "text-offset": [1.5, 0],
    "text-max-width": 10,
    "text-allow-overlap": true,
    "text-ignore-placement": true,
    "text-padding": 3,
  };
}

function depotIconLayout(iconId = DEPOT_ICON_ID, iconScale = depotIconScale()) {
  return {
    "icon-image": iconId,
    "icon-size": iconScale,
    "icon-anchor": "center",
    "icon-allow-overlap": true,
    "icon-ignore-placement": true,
    "icon-padding": 2,
  };
}

function depotIconScale() {
  return ["interpolate", ["exponential", 1.25], ["zoom"], 8, 0.1, 10, 0.14, 12, 0.19, 14, 0.24, 16, 0.32];
}

function selectedDepotIconScale() {
  return ["interpolate", ["exponential", 1.25], ["zoom"], 8, 0.14, 10, 0.19, 12, 0.26, 14, 0.33, 16, 0.44];
}

async function ensureStationIcon(map) {
  await Promise.all([
    addMapImageOnce(map, STATION_ICON_ID, busStationIconUrl, STATION_ICON_BASE_SIZE),
    addMapImageOnce(map, STATION_HIGHLIGHT_ICON_ID, busStationHighlightIconUrl, STATION_ICON_BASE_SIZE),
    addMapImageOnce(map, STATION_OUTSIDE_ICON_ID, busStationHighlightOutsideIconUrl, STATION_ICON_BASE_SIZE),
    addMapImageOnce(map, DEPOT_ICON_ID, busDepotIconUrl, DEPOT_ICON_BASE_SIZE),
  ]);
}

async function addMapImageOnce(map, imageId, imageUrl, size) {
  if (map.hasImage?.(imageId)) return;
  const image = await loadIconImageData(imageUrl, size);
  if (!map.hasImage?.(imageId)) {
    map.addImage(imageId, image);
  }
}

async function loadIconImageData(url, size) {
  const image = await loadImage(url);
  const canvas = document.createElement("canvas");
  canvas.width = size;
  canvas.height = size;
  const context = canvas.getContext("2d");
  context.clearRect(0, 0, size, size);
  context.drawImage(image, 0, 0, size, size);
  return context.getImageData(0, 0, size, size);
}

function loadImage(url) {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = reject;
    image.src = url;
  });
}

function stationIconScale() {
  const highZoomScale = stationSize.value / STATION_ICON_BASE_SIZE;
  // 低缩放档位刻意压小：中低缩放下站点极密，收成细粒纹理避免"泡泡纸"观感
  return [
    "interpolate",
    ["exponential", 1.25],
    ["zoom"],
    8,
    0.018,
    10,
    highZoomScale * 0.08,
    12,
    highZoomScale * 0.24,
    14,
    highZoomScale * 0.62,
    16,
    highZoomScale * 1.08,
  ];
}

function selectedStationIconScale() {
  const scale = stationIconScale();
  return [
    "interpolate",
    ["linear"],
    ["zoom"],
    8,
    0.04,
    10,
    scale[6] * 1.45,
    12,
    scale[8] * 1.32,
    14,
    scale[10] * 1.2,
    16,
    scale[12] * 1.16,
  ];
}

function selectedRouteStationIconScale() {
  const scale = stationIconScale();
  return [
    "interpolate",
    ["linear"],
    ["zoom"],
    8,
    0.035,
    10,
    scale[6] * 1.24,
    12,
    scale[8] * 1.18,
    14,
    scale[10] * 1.12,
    16,
    scale[12] * 1.08,
  ];
}

function selectedLineWidth() {
  return Math.max(4, lineWidth.value + 3.6);
}

function selectedRouteLineKeys() {
  if (!selectedRoute.value) return [];
  const features = routeFeaturesForOption(selectedRoute.value);
  const keys = features
    .map((feature) => String(feature?.properties?._lineKey || featureTargetId(feature) || ""))
    .filter(Boolean);
  if (keys.length) return [...new Set(keys)];
  const routeId = String(selectedRoute.value.id || selectedRoute.value.properties?._lineKey || "");
  return routeId ? [routeId] : [];
}

function selectedStationLineKeys() {
  if (!selectedStation.value || selectedRoute.value) return [];
  const keys = stationRouteOptions(selectedStation.value)
    .flatMap((route) => routeFeaturesForOption(route))
    .map((feature) => String(feature?.properties?._lineKey || featureTargetId(feature) || ""))
    .filter(Boolean);
  return [...new Set(keys)];
}

// 选中态涂装键只算一次：route/station 两条键路径各含一次索引聚合，调用方在同一次刷新里复用
function selectedLinePaintKeys() {
  const routeKeys = selectedRouteLineKeys();
  if (routeKeys.length) return { scope: "route", keys: routeKeys };
  const stationKeys = selectedStationLineKeys();
  if (stationKeys.length) return { scope: "station", keys: stationKeys };
  return { scope: "none", keys: [] };
}

function lineColorPaint(paintKeys = selectedLinePaintKeys()) {
  if (paintKeys.scope === "route" || paintKeys.scope === "station") {
    return ["match", ["to-string", ["get", "_lineKey"]], paintKeys.keys, NETWORK_LINE_COLOR, NETWORK_LINE_DIMMED_COLOR];
  }
  return NETWORK_LINE_COLOR;
}

function lineOpacityPaint(paintKeys = selectedLinePaintKeys()) {
  if (paintKeys.scope === "station") {
    return ["match", ["to-string", ["get", "_lineKey"]], paintKeys.keys, 0.72, 0.12];
  }
  if (paintKeys.scope === "route") {
    // 选中线路后底图线网整体隐藏：选中线路由橙色高亮图层绘制，其余线路不再淡化保留
    // （opacity=0 不影响 queryRenderedFeatures 命中测试，仍可点选切换线路）
    return 0;
  }
  return MAP_THEME.network.lineOpacity;
}

// 灰色底图宽度：选中线路时灰底只剩该线路的区外溢出，必须与橙色高亮同宽，
// 否则跨区线路在分界处会突然变细
function baseNetworkLineWidth() {
  return selectedRoute.value ? selectedLineWidth() : networkLineWidth();
}

// 线网宽度随 zoom 增长：远景收细成"电路板"底纹，近景加粗便于点选与阅读
function networkLineWidth() {
  const width = lineWidth.value;
  return [
    "interpolate",
    ["exponential", 1.4],
    ["zoom"],
    9,
    width * 0.45,
    12,
    width * 0.75,
    14,
    width * 1.25,
    16,
    width * 2.1,
  ];
}

function stationOpacityPaint() {
  const stationId = selectedStation.value?.id;
  if (stationId) {
    return ["case", ["==", ["get", "_stationKey"], stationId], 0, 0.24];
  }
  if (selectedRoute.value && activeKey.value !== "update_line") {
    // 选中线路后底图站点整体隐藏：线路自身站点由高亮图层（LAYER_ROUTE_STATION_SELECTED）绘制
    return 0;
  }
  // 中低缩放淡出为纹理，高缩放完全实体，与 stationIconScale 的收细策略配合
  return ["interpolate", ["linear"], ["zoom"], 8, 0.4, 11, 0.62, 13, 0.85, 14, 0.96];
}

function normalizeLineFeatureCollection(collection) {
  const features = Array.isArray(collection?.features) ? collection.features : [];
  return {
    ...collection,
    type: "FeatureCollection",
    features: features.map((feature, index) => normalizeLineFeature(feature, index)),
  };
}

function normalizeFeatureCollection(collection) {
  return {
    ...collection,
    type: "FeatureCollection",
    features: Array.isArray(collection?.features) ? collection.features : [],
  };
}

function normalizeLineFeature(feature, index = 0) {
  if (!feature || typeof feature !== "object") {
    const lineKey = `line-${index}`;
    return { type: "Feature", id: lineKey, geometry: null, properties: { _lineKey: lineKey } };
  }
  if (!feature.properties || typeof feature.properties !== "object") {
    feature.properties = {};
  }
  const lineKey = lineFeatureKey(feature, index);
  feature.type = feature.type || "Feature";
  feature.id = feature.id ?? lineKey;
  feature.properties._lineKey = feature.properties._lineKey || lineKey;
  return feature;
}

function normalizeStationFeatureCollection(collection) {
  const features = Array.isArray(collection?.features) ? collection.features : [];
  return {
    ...collection,
    type: "FeatureCollection",
    features: features.map((feature, index) => normalizeStationFeature(feature, index)),
  };
}

function normalizeRouteStopFeatureCollection(collection) {
  const features = Array.isArray(collection?.features) ? collection.features : [];
  return {
    ...collection,
    type: "FeatureCollection",
    features: features.map((feature, index) => normalizeRouteStopFeature(feature, index)),
  };
}

function lineFeatureKey(feature, index = 0) {
  const properties = feature?.properties || {};
  return String(
      properties._lineKey ||
      properties._featureId ||
      [properties.line_id, properties.dir, properties.route_id].filter(Boolean).join("-") ||
      properties.route_id ||
      properties.id ||
      [routeName(properties), index].filter(Boolean).join("-") ||
      `line-${index}`,
  );
}

function normalizeDepotFeatureCollection(collection) {
  const features = Array.isArray(collection?.features) ? collection.features : [];
  return {
    ...collection,
    type: "FeatureCollection",
    features: features.map((feature, index) => normalizeDepotFeature(feature, index)),
  };
}

function normalizeStationFeature(feature, index = 0) {
  if (!feature || typeof feature !== "object") {
    const stationKey = `station-${index}`;
    return { type: "Feature", id: stationKey, geometry: null, properties: { _featureId: stationKey, _stationKey: stationKey } };
  }
  if (!feature.properties || typeof feature.properties !== "object") {
    feature.properties = {};
  }
  const properties = feature.properties;
  feature.type = feature.type || "Feature";
  feature.geometry = feature.geometry || null;
  properties._featureId = properties._featureId || feature.id || properties._featureId;
  properties._stationKey = properties._stationKey || stationFeatureKey(feature, index);
  // 稳定 feature.id 是 GeoJSONSource.updateData 增量更新的前提
  if (feature.id === undefined || feature.id === null) {
    feature.id = String(properties._stationKey);
  }
  return feature;
}

function normalizeRouteStopFeature(feature, index = 0) {
  if (!feature || typeof feature !== "object") {
    const routeStopKey = `route-stop-${index}`;
    return { type: "Feature", id: routeStopKey, geometry: null, properties: { _featureId: routeStopKey, _routeStopKey: routeStopKey } };
  }
  if (!feature.properties || typeof feature.properties !== "object") {
    feature.properties = {};
  }
  const properties = feature.properties;
  const coordinates = pointCoordinates(feature?.geometry);
  feature.type = feature.type || "Feature";
  feature.geometry = feature.geometry || null;
  properties._featureId = properties._featureId || feature.id || properties._featureId;
  properties._routeStopKey = String(
    properties._routeStopKey ||
      [properties.line_id, properties.stop_id, properties.seq, coordinates?.[0], coordinates?.[1], index].filter(Boolean).join("-") ||
      `route-stop-${index}`,
  );
  return feature;
}

function normalizeDepotFeature(feature, index = 0) {
  if (!feature || typeof feature !== "object") {
    const depotKey = `depot-${index}`;
    return { type: "Feature", id: depotKey, geometry: null, properties: { _featureId: depotKey, _depotKey: depotKey } };
  }
  if (!feature.properties || typeof feature.properties !== "object") {
    feature.properties = {};
  }
  const properties = feature.properties;
  feature.type = feature.type || "Feature";
  feature.geometry = feature.geometry || null;
  properties._featureId = properties._featureId || feature.id || properties._featureId;
  properties._depotKey = properties._depotKey || depotFeatureKey(feature, index);
  if (feature.id === undefined || feature.id === null) {
    feature.id = String(properties._depotKey);
  }
  return feature;
}

function normalizeAdminDistrictCollection(collection) {
  const features = Array.isArray(collection?.features) ? collection.features : [];
  return {
    type: "FeatureCollection",
    features: features
      .map((feature, index) => {
        const properties = feature?.properties || {};
        const name = districtFeatureName(feature);
        return {
          type: "Feature",
          id: feature?.id || `district-${index}`,
          geometry: feature?.geometry || null,
          properties: {
            ...properties,
            _districtName: name,
          },
        };
      })
      .filter((feature) => feature.geometry && feature.properties._districtName),
  };
}

function districtNamesFromCollection(collection) {
  const names = [];
  const seen = new Set();
  for (const feature of collection?.features || []) {
    const name = districtFeatureName(feature);
    if (!name || seen.has(name)) continue;
    seen.add(name);
    names.push(name);
  }
  return names;
}

function districtFeatureName(feature) {
  const properties = feature?.properties || {};
  return String(
    properties._districtName ||
      properties.Name ||
      properties.name ||
      properties.NAME ||
      properties["名称"] ||
      properties["区名"] ||
      properties["行政区"] ||
      properties["行政区名"] ||
      properties["区县"] ||
      properties["县区"] ||
      properties.district ||
      properties.District ||
      properties.AdminName ||
      "",
  ).trim();
}

function applyDisplayRangeFilter(options = {}) {
  const { updateSources = true, clearSelection: shouldClearSelection = false } = options;
  const context = activeDisplayRangeContext();
  // 行政区边界只依赖选中范围本身，立即更新描边——不必等后台裁剪 worker 返回
  updateDistrictOutlineLayer();
  const cacheKey = context ? context.name : DISPLAY_RANGE_ALL;
  // 缓存条目携带来源集合引用做有效性校验，避免 clearRealDataLayers 后残留的过期条目被复用
  const cachedEntry = displayRangeFilterCache.get(cacheKey);
  let activeEntry = cachedEntry && cachedEntry.source === realDataAllCollections ? cachedEntry : null;
  if (activeEntry) {
    realDataCollections = activeEntry.collections;
  } else if (!context) {
    realDataCollections = {
      lines: realDataAllCollections.lines,
      stations: realDataAllCollections.stations,
      routeStops: realDataAllCollections.routeStops,
      depots: realDataAllCollections.depots,
    };
    activeEntry = { source: realDataAllCollections, collections: realDataCollections };
    displayRangeFilterCache.set(cacheKey, activeEntry);
  } else {
    // 未缓存的行政区：优先交给 worker 后台计算（保持当前集合渲染，结果写入缓存后重入本函数走命中路径）；
    // worker 不可用或构建失败时同步回退
    if (scheduleDistrictWorkerFilter(context, cacheKey)) {
      if (shouldClearSelection) {
        clearSelectionState();
      }
      return;
    }
    realDataCollections = filterCollectionsByDistrict(realDataAllCollections, context);
    activeEntry = { source: realDataAllCollections, collections: realDataCollections };
    displayRangeFilterCache.set(cacheKey, activeEntry);
  }
  // 区级总览统计（去重计数+线网长度积分）随缓存条目存储：区间来回切换不再全量重算
  if (!context) {
    updateOverviewCollectionCounts(null);
  } else if (activeEntry.stats) {
    overviewStats.lineCount = activeEntry.stats.lineCount;
    overviewStats.stationCount = activeEntry.stats.stationCount;
    overviewStats.networkScaleKm = activeEntry.stats.networkScaleKm;
    overviewStats.networkDensityKmPerKm2 = activeEntry.stats.networkDensityKmPerKm2;
    overviewStats.adminAreaKm2 = activeEntry.stats.adminAreaKm2;
  } else {
    updateOverviewCollectionCounts(context);
    activeEntry.stats = {
      lineCount: overviewStats.lineCount,
      stationCount: overviewStats.stationCount,
      networkScaleKm: overviewStats.networkScaleKm,
      networkDensityKmPerKm2: overviewStats.networkDensityKmPerKm2,
      adminAreaKm2: overviewStats.adminAreaKm2,
    };
  }
  realDataCollectionsRevision.value += 1;
  rebuildSearchIndexesIfNeeded();
  if (shouldClearSelection) {
    clearSelectionState();
  }
  if (updateSources) {
    syncRealDataSourceData();
  }
}

function rebuildSearchIndexesIfNeeded() {
  if (
    !searchIndexesDirty &&
    searchIndexSource.lines === realDataCollections.lines &&
    searchIndexSource.stations === realDataCollections.stations &&
    searchIndexSource.routeStops === realDataCollections.routeStops &&
    searchIndexSource.depots === realDataCollections.depots
  ) {
    return;
  }
  lineSearchIndex = buildLineSearchIndex(realDataCollections.lines);
  stationSearchIndex = buildStationSearchIndex(realDataCollections.stations);
  depotSearchIndex = buildDepotSearchIndex(realDataCollections.depots);
  lineLookup = buildLineLookup(lineSearchIndex);
  stationRouteLookup = buildStationRouteLookup(realDataCollections.routeStops);
  searchIndexSource = {
    lines: realDataCollections.lines,
    stations: realDataCollections.stations,
    routeStops: realDataCollections.routeStops,
    depots: realDataCollections.depots,
  };
  searchIndexesDirty = false;
}

function clearDisplayRangeFilterCache() {
  displayRangeFilterCache = new Map();
  routeStopIndexCache = { token: -1, collection: null, byRouteId: new Map() };
  lineFeatureIndexCache = { token: -1, collection: null, byKey: new Map() };
  // worker 持有的集合副本随之过期，下次过滤请求前需要重发 setData
  districtWorkerDataStale = true;
}

// —— 行政区裁剪后台线程管理 ——
// worker 持有集合副本（数据换代/本地编辑后按需重发），过滤请求以 token 防竞态；
// 结果到达且区选/数据仍有效时写入 displayRangeFilterCache 并重入 applyDisplayRangeFilter。
let districtWorker = null;
let districtWorkerFailed = false;
let districtWorkerDataStale = true;
let districtWorkerSource = null;
let districtWorkerRequestSeq = 0;
let activeDistrictFilterToken = 0;
const pendingDistrictFilterResolvers = new Map();

function districtFilterWorkerInstance() {
  if (districtWorkerFailed) return null;
  if (districtWorker) return districtWorker;
  try {
    districtWorker = new Worker(new URL("./districtFilter.worker.js", import.meta.url), { type: "module" });
    districtWorker.onmessage = (event) => {
      const message = event?.data || {};
      if (message.type !== "filterResult") return;
      const resolve = pendingDistrictFilterResolvers.get(message.requestId);
      if (resolve) {
        pendingDistrictFilterResolvers.delete(message.requestId);
        resolve(message.collections || null);
      }
    };
    districtWorker.onerror = () => {
      districtWorkerFailed = true;
      pendingDistrictFilterResolvers.forEach((resolve) => resolve(null));
      pendingDistrictFilterResolvers.clear();
      districtWorker?.terminate?.();
      districtWorker = null;
    };
  } catch {
    districtWorkerFailed = true;
    districtWorker = null;
  }
  return districtWorker;
}

function scheduleDistrictWorkerFilter(context, cacheKey) {
  const worker = districtFilterWorkerInstance();
  if (!worker) return false;
  try {
    if (districtWorkerSource !== realDataAllCollections || districtWorkerDataStale) {
      worker.postMessage({
        type: "setData",
        collections: {
          lines: realDataAllCollections.lines,
          stations: realDataAllCollections.stations,
          routeStops: realDataAllCollections.routeStops,
          depots: realDataAllCollections.depots,
        },
      });
      districtWorkerSource = realDataAllCollections;
      districtWorkerDataStale = false;
    }
  } catch {
    districtWorkerFailed = true;
    return false;
  }
  const requestId = ++districtWorkerRequestSeq;
  const token = ++activeDistrictFilterToken;
  const source = realDataAllCollections;
  new Promise((resolve) => {
    pendingDistrictFilterResolvers.set(requestId, resolve);
    worker.postMessage({
      type: "filter",
      requestId,
      context: { name: context.name, polygons: context.polygons, bounds: context.bounds },
    });
  }).then((collections) => {
    if (token !== activeDistrictFilterToken) return;
    if (source !== realDataAllCollections) return;
    if (activeDisplayRangeContext()?.name !== context.name) return;
    if (!collections) {
      // worker 侧异常：同步回退一次，保证区选最终可用
      displayRangeFilterCache.set(cacheKey, {
        source,
        collections: filterCollectionsByDistrict(realDataAllCollections, context),
      });
      applyDisplayRangeFilter({ updateSources: true, clearSelection: false });
      return;
    }
    displayRangeFilterCache.set(cacheKey, { source, collections });
    applyDisplayRangeFilter({ updateSources: true, clearSelection: false });
  });
  return true;
}

function activeDisplayRangeContext() {
  const rangeName = selectedDisplayRange.value;
  if (!rangeName || rangeName === DISPLAY_RANGE_ALL) return null;
  const feature = (adminDistrictCollection?.features || []).find((item) => districtFeatureName(item) === rangeName);
  if (!feature?.geometry) return null;
  const polygons = geometryPolygonRings(feature.geometry);
  if (!polygons.length) return null;
  const bounds = geometryBounds(feature.geometry);
  if (!bounds) return null;
  return {
    name: rangeName,
    feature,
    polygons,
    bounds,
    areaKm2: geometryPolygonAreaKm2(polygons),
  };
}

function displayRangeFitBounds() {
  // featureCollectionBounds 接收要素数组：此前误传集合对象导致切回全市时 fit 静默抛错失效
  return activeDisplayRangeContext()?.bounds
    || featureCollectionBounds(adminDistrictCollection?.features || [])
    || featureCollectionBounds([
      ...(realDataCollections.lines?.features || []),
      ...(realDataCollections.stations?.features || []),
      ...(realDataCollections.depots?.features || []),
    ]);
}

function fitDisplayRangeBounds() {
  fitBounds(displayRangeFitBounds());
}

function syncRealDataSourceData() {
  const map = MapRef.value?.map;
  if (!map) return;
  setGeoJsonSourceData(SOURCE_LINES, realDataCollections.lines);
  setGeoJsonSourceData(SOURCE_STATIONS, realDataCollections.stations);
  setGeoJsonSourceData(SOURCE_DEPOTS, realDataCollections.depots);
  updateStationSelectionLayers();
  if (selectedRoute.value) {
    updateSelectedLineLayer(selectedRoute.value.feature);
  } else {
    clearSelectedLineLayer();
  }
  updateSelectedDepotLayer(selectedDepot.value?.feature || null);
  updateDistrictOutlineLayer(map);
  updateBaseNetworkLayers(map);
  applyLayerPaint();
}

function featureCollectionLineLengthMeters(collection) {
  return collectionFeatures(collection).reduce((total, feature) => total + lineGeometryLengthMeters(feature?.geometry), 0);
}

function lineGeometryLengthMeters(geometry) {
  return lineCoordinatePaths(geometry).reduce((total, path) => total + coordinatePathLengthMeters(path), 0);
}

function coordinatePathLengthMeters(path = []) {
  const coordinates = Array.isArray(path) ? path.map(validLngLat).filter(Boolean) : [];
  let total = 0;
  for (let index = 1; index < coordinates.length; index += 1) {
    total += geoDistanceMeters(coordinates[index - 1], coordinates[index]);
  }
  return total;
}

function geoDistanceMeters(first, second) {
  if (!first || !second) return 0;
  const lat1 = toRadians(Number(first[1]));
  const lat2 = toRadians(Number(second[1]));
  const deltaLat = toRadians(Number(second[1]) - Number(first[1]));
  const deltaLng = toRadians(Number(second[0]) - Number(first[0]));
  const a = Math.sin(deltaLat / 2) ** 2
    + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLng / 2) ** 2;
  return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function geometryPolygonAreaKm2(polygons = []) {
  return polygons.reduce((total, rings) => total + polygonRingsAreaSquareMeters(rings), 0) / 1000000;
}

function polygonRingsAreaSquareMeters(rings = []) {
  if (!rings.length) return 0;
  const [exterior, ...holes] = rings;
  const area = ringAreaSquareMeters(exterior) - holes.reduce((sum, ring) => sum + ringAreaSquareMeters(ring), 0);
  return Math.max(area, 0);
}

function ringAreaSquareMeters(ring = []) {
  const coordinates = Array.isArray(ring) ? ring.map(validLngLat).filter(Boolean) : [];
  if (coordinates.length < 3) return 0;
  const centroid = coordinates.reduce((sum, coordinate) => [sum[0] + coordinate[0], sum[1] + coordinate[1]], [0, 0]);
  const refLng = centroid[0] / coordinates.length;
  const refLat = centroid[1] / coordinates.length;
  const cosRefLat = Math.cos(toRadians(refLat));
  let area = 0;
  for (let index = 0; index < coordinates.length; index += 1) {
    const current = coordinates[index];
    const next = coordinates[(index + 1) % coordinates.length];
    const x1 = EARTH_RADIUS_METERS * toRadians(current[0] - refLng) * cosRefLat;
    const y1 = EARTH_RADIUS_METERS * toRadians(current[1] - refLat);
    const x2 = EARTH_RADIUS_METERS * toRadians(next[0] - refLng) * cosRefLat;
    const y2 = EARTH_RADIUS_METERS * toRadians(next[1] - refLat);
    area += x1 * y2 - x2 * y1;
  }
  return Math.abs(area) / 2;
}

function toRadians(value) {
  return Number(value) * Math.PI / 180;
}

function roundNumber(value, digits = 2) {
  const number = Number(value);
  if (!Number.isFinite(number)) return null;
  const factor = 10 ** digits;
  return Math.round(number * factor) / factor;
}

function geometryPolygonRings(geometry) {
  const coordinates = geometry?.coordinates;
  if (!Array.isArray(coordinates)) return [];
  if (geometry.type === "Polygon") {
    return [normalizePolygonRings(coordinates)].filter((rings) => rings.length);
  }
  if (geometry.type === "MultiPolygon") {
    return coordinates.map(normalizePolygonRings).filter((rings) => rings.length);
  }
  return [];
}

function normalizePolygonRings(rawRings) {
  if (!Array.isArray(rawRings)) return [];
  return rawRings
    .map((ring) => (Array.isArray(ring) ? ring.map(validLngLat).filter(Boolean) : []))
    .filter((ring) => ring.length >= 3);
}

function geometryBounds(geometry) {
  const bounds = [Infinity, Infinity, -Infinity, -Infinity];
  expandGeometryBounds(geometry, bounds);
  return Number.isFinite(bounds[0]) ? bounds : null;
}

function stationFeatureKey(feature, index = 0) {
  const properties = feature?.properties || {};
  const coordinates = pointCoordinates(feature?.geometry);
  return String(
    properties.stop_id ||
      properties.stop_name ||
      properties.id ||
      `${properties.stop_name || properties.name || "station"}-${coordinates?.[0] ?? "x"}-${coordinates?.[1] ?? "y"}-${index}`,
  );
}

function countUniqueFeatures(collection, keyFn) {
  const seen = new Set();
  collectionFeatures(collection).forEach((feature, index) => {
    const key = keyFn(feature, index);
    if (key) seen.add(key);
  });
  return seen.size;
}

function physicalCountOrFallback(collection, keyFn, fallback = 0) {
  const features = collectionFeatures(collection);
  if (features.length) return countUniqueFeatures(collection, keyFn);
  const number = Number(fallback);
  return Number.isFinite(number) ? number : 0;
}

function updateOverviewCollectionCounts(context = null) {
  if (!context) {
    overviewStats.lineCount = overviewStatsBaseline.lineCount;
    overviewStats.stationCount = overviewStatsBaseline.stationCount;
    overviewStats.networkScaleKm = overviewStatsBaseline.networkScaleKm;
    overviewStats.networkDensityKmPerKm2 = overviewStatsBaseline.networkDensityKmPerKm2;
    overviewStats.adminAreaKm2 = overviewStatsBaseline.adminAreaKm2;
    return;
  }
  overviewStats.lineCount = countUniqueFeatures(realDataCollections.lines, physicalLineKey);
  overviewStats.stationCount = countUniqueFeatures(realDataCollections.routeStops, physicalStationKey);
  const rawNetworkScaleKm = featureCollectionLineLengthMeters(realDataCollections.lines) / 1000;
  const networkScaleKm = roundNumber(rawNetworkScaleKm, 2);
  const adminAreaKm2 = roundNumber(context.areaKm2, 2);
  overviewStats.networkScaleKm = Number.isFinite(networkScaleKm) ? networkScaleKm : null;
  overviewStats.adminAreaKm2 = Number.isFinite(adminAreaKm2) ? adminAreaKm2 : null;
  overviewStats.networkDensityKmPerKm2 = Number.isFinite(rawNetworkScaleKm) && Number.isFinite(context.areaKm2) && context.areaKm2 > 0
    ? roundNumber(rawNetworkScaleKm / context.areaKm2, 4)
    : null;
}

function physicalLineGroups(collection) {
  const groups = new Map();
  collectionFeatures(collection).forEach((feature, index) => {
    const key = physicalLineKey(feature, index);
    if (!key) return;
    if (!groups.has(key)) {
      groups.set(key, { key, features: [] });
    }
    groups.get(key).features.push(feature);
  });
  return [...groups.values()];
}

// 以 properties 为键做记忆化（名称参与失效判断，改名后自动重算）；总览聚合每次 revision 都要遍历全部线路
const physicalLineKeyCache = new WeakMap();
function physicalLineKey(feature, index = 0) {
  const rawProperties = feature?.properties;
  const properties = rawProperties || {};
  const name = valueOrEmpty(routeName(properties));
  const cacheable = Boolean(rawProperties && typeof rawProperties === "object");
  if (cacheable) {
    const cached = physicalLineKeyCache.get(rawProperties);
    if (cached && cached.name === name) return cached.key;
  }
  const key = computePhysicalLineKey(feature, properties, name, index);
  if (cacheable && !key.startsWith("line-index:")) {
    physicalLineKeyCache.set(rawProperties, { name, key });
  }
  return key;
}

function computePhysicalLineKey(feature, properties, name, index) {
  const familyName = name ? stripRouteEndpointSuffix(name) : "";
  if (familyName) return `line:${familyName}`;
  const routeId = valueOrEmpty(properties.route_id || properties.routeId);
  if (routeId) return `route:${routeId}`;
  const lineId = valueOrEmpty(properties.line_id || properties.lineId);
  if (lineId) return `lineid:${lineId}`;
  const fallbackKey = valueOrEmpty(properties._lineKey || properties._featureId || feature?.id);
  return fallbackKey ? `feature:${fallbackKey}` : `line-index:${index}`;
}

function stripRouteEndpointSuffix(value) {
  let text = valueOrEmpty(value).trim();
  while (text.endsWith(")") || text.endsWith("）")) {
    const closeIndex = Math.max(text.lastIndexOf(")"), text.lastIndexOf("）"));
    const openIndex = Math.max(text.lastIndexOf("(", closeIndex), text.lastIndexOf("（", closeIndex));
    if (openIndex < 0 || openIndex >= closeIndex) break;
    const inner = text.slice(openIndex + 1, closeIndex).trim();
    if (!looksLikeEndpointText(inner)) break;
    text = text.slice(0, openIndex).trim();
  }
  return text;
}

function looksLikeEndpointText(value) {
  const parts = String(value || "")
    .split(/\s*(?:--|—|－|至|到)\s*/)
    .map((part) => part.trim())
    .filter(Boolean);
  return parts.length >= 2;
}

function physicalStationKey(feature, index = 0) {
  const properties = feature?.properties || {};
  const coordinates = pointCoordinates(feature?.geometry);
  if (coordinates) {
    return `coord:${formatCoordinateKey(coordinates[0])},${formatCoordinateKey(coordinates[1])}`;
  }
  const name = normalizeStationFamilyName(properties.stop_name || properties.name || properties.station_name || properties["站点名称"]);
  if (name) return `station-name:${name}`;
  const fallbackKey = valueOrEmpty(properties._stationKey || properties._featureId || feature?.id);
  return fallbackKey ? `feature:${fallbackKey}` : `station-index:${index}`;
}

function normalizeStationFamilyName(value) {
  return valueOrEmpty(value)
    .replace(/\s*[（(][^）)]*[）)]\s*$/g, "")
    .replace(/\s*(?:站|总站)\s*$/g, "")
    .replace(/[\s_-]*[0-9]+$/g, "")
    .trim();
}

function formatCoordinateKey(value) {
  const number = Number(value);
  return Number.isFinite(number) ? number.toFixed(6) : "x";
}

function depotFeatureKey(feature, index = 0) {
  const properties = feature?.properties || {};
  const coordinates = pointCoordinates(feature?.geometry);
  return String(
    properties._featureId ||
      properties.depot_id ||
      properties.id ||
      `${depotName(properties)}-${coordinates?.[0] ?? "x"}-${coordinates?.[1] ?? "y"}-${index}`,
  );
}

function emptyFeatureCollection() {
  return {
    type: "FeatureCollection",
    features: [],
  };
}

function clearRealDataLayers() {
  const map = MapRef.value?.map;
  if (!map) return;
  if (map.getLayer(LAYER_DISTRICT_OUTLINE)) map.removeLayer(LAYER_DISTRICT_OUTLINE);
  if (map.getLayer(LAYER_BASE_STATIONS)) map.removeLayer(LAYER_BASE_STATIONS);
  if (map.getLayer(LAYER_BASE_LINES)) map.removeLayer(LAYER_BASE_LINES);
  if (map.getLayer(LAYER_DEPOT_SELECTED)) map.removeLayer(LAYER_DEPOT_SELECTED);
  if (map.getLayer(LAYER_DEPOT_LABELS)) map.removeLayer(LAYER_DEPOT_LABELS);
  if (map.getLayer(LAYER_DEPOTS)) map.removeLayer(LAYER_DEPOTS);
  if (map.getLayer(LAYER_ROUTE_STATION_SELECTED)) map.removeLayer(LAYER_ROUTE_STATION_SELECTED);
  if (map.getLayer(LAYER_ROUTE_STATION_OUTSIDE)) map.removeLayer(LAYER_ROUTE_STATION_OUTSIDE);
  if (map.getLayer(LAYER_STATION_SELECTED)) map.removeLayer(LAYER_STATION_SELECTED);
  if (map.getLayer(LAYER_STATION_LABELS)) map.removeLayer(LAYER_STATION_LABELS);
  if (map.getLayer(LAYER_STATIONS)) map.removeLayer(LAYER_STATIONS);
  if (map.getLayer(LAYER_LINE_SELECTED)) map.removeLayer(LAYER_LINE_SELECTED);
  if (map.getLayer(LAYER_LINE_SELECTED + "-glow")) map.removeLayer(LAYER_LINE_SELECTED + "-glow");
  if (map.getLayer(LAYER_LINES)) map.removeLayer(LAYER_LINES);
  if (map.getLayer(LAYER_ROUTE_STATION_LABELS)) map.removeLayer(LAYER_ROUTE_STATION_LABELS);
  if (map.getSource(SOURCE_DISTRICT_OUTLINE)) map.removeSource(SOURCE_DISTRICT_OUTLINE);
  if (map.getSource(SOURCE_BASE_STATIONS)) map.removeSource(SOURCE_BASE_STATIONS);
  if (map.getSource(SOURCE_BASE_LINES)) map.removeSource(SOURCE_BASE_LINES);
  if (map.getSource(SOURCE_SELECTED_DEPOT)) map.removeSource(SOURCE_SELECTED_DEPOT);
  if (map.getSource(SOURCE_DEPOTS)) map.removeSource(SOURCE_DEPOTS);
  if (map.getSource(SOURCE_SELECTED_ROUTE_STATIONS)) map.removeSource(SOURCE_SELECTED_ROUTE_STATIONS);
  if (map.getSource(SOURCE_SELECTED_STATION)) map.removeSource(SOURCE_SELECTED_STATION);
  if (map.getSource(SOURCE_STATIONS)) map.removeSource(SOURCE_STATIONS);
  if (map.getSource(SOURCE_SELECTED_LINE)) map.removeSource(SOURCE_SELECTED_LINE);
  if (map.getSource(SOURCE_LINES)) map.removeSource(SOURCE_LINES);
  realDataSourceDataRefs = new Map();
  clearDisplayRangeFilterCache();
  clearSelectionState();
  stationSearchIndex = [];
  lineSearchIndex = [];
  depotSearchIndex = [];
  // 索引数组已清空，来源引用必须同步作废，否则下次守卫会误判"未变化"而跳过重建
  searchIndexSource = { lines: null, stations: null, routeStops: null, depots: null };
  stationRouteLookup = { byStopId: new Map(), byStopKey: new Map(), byStopName: new Map() };
  lineLookup = { byName: new Map(), byEntry: new Map() };
  realDataCollections = {
    lines: emptyFeatureCollection(),
    stations: emptyFeatureCollection(),
    routeStops: emptyFeatureCollection(),
    depots: emptyFeatureCollection(),
  };
  realDataAllCollections = {
    lines: emptyFeatureCollection(),
    stations: emptyFeatureCollection(),
    routeStops: emptyFeatureCollection(),
    depots: emptyFeatureCollection(),
  };
  realDataCollectionsRevision.value += 1;
}

function applyLayerPaint() {
  const map = MapRef.value?.map;
  if (!map) return;
  if (map.getLayer(LAYER_LINES)) {
    const paintKeys = selectedLinePaintKeys();
    map.setPaintProperty(LAYER_LINES, "line-color", lineColorPaint(paintKeys));
    map.setPaintProperty(LAYER_LINES, "line-width", networkLineWidth());
    map.setPaintProperty(LAYER_LINES, "line-opacity", lineOpacityPaint(paintKeys));
  }
  if (map.getLayer(LAYER_BASE_LINES)) {
    map.setPaintProperty(LAYER_BASE_LINES, "line-width", baseNetworkLineWidth());
  }
  if (map.getLayer(LAYER_LINE_SELECTED)) {
    map.setPaintProperty(LAYER_LINE_SELECTED, "line-color", SELECTED_LINE_COLOR);
    map.setPaintProperty(LAYER_LINE_SELECTED, "line-width", selectedLineWidth());
  }
  if (map.getLayer(LAYER_LINE_SELECTED + "-glow")) {
    map.setPaintProperty(LAYER_LINE_SELECTED + "-glow", "line-color", SELECTED_LINE_GLOW_COLOR);
    map.setPaintProperty(LAYER_LINE_SELECTED + "-glow", "line-opacity", MAP_THEME.route.haloOpacity);
    map.setPaintProperty(LAYER_LINE_SELECTED + "-glow", "line-width", selectedLineWidth() * MAP_THEME.route.haloWidthRatio);
    map.setPaintProperty(LAYER_LINE_SELECTED + "-glow", "line-blur", selectedLineWidth() * 0.9);
  }
  if (map.getLayer(LAYER_STATIONS)) {
    map.setLayoutProperty(LAYER_STATIONS, "icon-size", stationIconScale());
  }
  if (map.getLayer(LAYER_STATION_SELECTED)) {
    map.setLayoutProperty(LAYER_STATION_SELECTED, "icon-size", selectedStationIconScale());
  }
  if (map.getLayer(LAYER_ROUTE_STATION_SELECTED)) {
    map.setLayoutProperty(LAYER_ROUTE_STATION_SELECTED, "icon-size", selectedRouteStationIconScale());
  }
  if (map.getLayer(LAYER_ROUTE_STATION_OUTSIDE)) {
    map.setLayoutProperty(LAYER_ROUTE_STATION_OUTSIDE, "icon-size", selectedRouteStationIconScale());
  }
}

let layerPaintRaf = 0;
// 滑块 @input 拖动中高频触发，用 rAF 合并到每帧一次重绘（读 .value 在执行时取最新值）
function scheduleApplyLayerPaint() {
  if (layerPaintRaf) return;
  layerPaintRaf = requestAnimationFrame(() => {
    layerPaintRaf = 0;
    applyLayerPaint();
  });
}

function fitBounds(bounds) {
  if (!Array.isArray(bounds) || bounds.length < 4 || !MapRef.value) return;
  const points = [
    lngLatToWebMercator(bounds[0], bounds[1]),
    lngLatToWebMercator(bounds[2], bounds[3]),
  ];
  MapRef.value.setFitZoomAndCenterByPoints?.(points);
}

function focusFeature(feature, options = {}) {
  const { minZoom = 14, maxZoom = 16, pointZoom = 15 } = options;
  const mapInstance = MapRef.value;
  if (!feature?.geometry || !mapInstance) return;
  if (feature.geometry.type === "Point") {
    const coordinates = pointCoordinates(feature.geometry);
    if (!coordinates) return;
    mapInstance.setCenter?.(lngLatToWebMercator(coordinates[0], coordinates[1]));
    mapInstance.setZoom?.(Math.max(Number(mapInstance.zoom) || 0, pointZoom));
    return;
  }
  const points = featureWebMercatorPoints(feature);
  if (!points.length) return;
  const result = mapInstance.setFitZoomAndCenterByPoints?.(points);
  if (result?.center) {
    const nextZoom = Math.max(minZoom, Math.min(maxZoom, Number(result.zoom) || minZoom));
    mapInstance.setZoom?.(nextZoom);
  }
}

function featureWebMercatorPoints(feature) {
  if (feature?.geometry?.type === "LineString") {
    return feature.geometry.coordinates.map((coordinate) => validLngLat(coordinate)).filter(Boolean).map((coordinate) => lngLatToWebMercator(coordinate[0], coordinate[1]));
  }
  if (feature?.geometry?.type === "MultiLineString") {
    return feature.geometry.coordinates.flatMap((path) => path.map((coordinate) => validLngLat(coordinate)).filter(Boolean).map((coordinate) => lngLatToWebMercator(coordinate[0], coordinate[1])));
  }
  return [];
}

function handleZoomIn() {
  const map = MapRef.value;
  if (map) map.setZoom(map.zoom + 1);
}

function handleZoomOut() {
  const map = MapRef.value;
  if (map) map.setZoom(map.zoom - 1);
}

function handleToggle3D() {
  const map = MapRef.value;
  if (!map) return;
  if (is3DActive.value) {
    map.setPitchAndRotation(90, 0);
    map.enableRotate = false;
    is3DActive.value = false;
    return;
  }
  map.enableRotate = true;
  map.setPitchAndRotation(45, map.rotation);
  is3DActive.value = true;
}

function handleResetCompass() {
  const map = MapRef.value;
  if (!map) return;
  map.setPitchAndRotation(90, 0);
  map.enableRotate = false;
  is3DActive.value = false;
}

function bindMapStateListeners(mapInstance) {
  if (!mapInstance) return;
  if (zoomListenerId) mapInstance.removeEventListener("update:zoom", zoomListenerId);
  if (rotateListenerId) mapInstance.removeEventListener("update:camera:rotate", rotateListenerId);
  rotateListenerId = mapInstance.addEventListener("update:camera:rotate", (event) => {
    is3DActive.value = event.data.newPitch !== 90 || event.data.newRotation !== 0;
  });
}

function bindStationClickListener() {
  const mapInstance = MapRef.value;
  if (!mapInstance || stationClickListenerId) return;
  stationClickListenerId = mapInstance.addEventListener("handle:click", handleStationMapClick);
}

function selectableMapLayerIds(map) {
  const key = activeKey.value;
  let layerIds = [];
  if (historyPreview.visible || key === "overview") {
    layerIds = [
      LAYER_STATION_SELECTED,
      LAYER_STATION_LABELS,
      LAYER_STATIONS,
      LAYER_DEPOT_SELECTED,
      LAYER_DEPOTS,
      LAYER_LINES,
    ];
  } else if (key === "update_station") {
    layerIds = [LAYER_STATION_SELECTED, LAYER_STATION_LABELS, LAYER_STATIONS];
  } else if (key === "update_line") {
    layerIds = [LAYER_LINES];
  } else if (key === "update_depot") {
    layerIds = [LAYER_DEPOT_SELECTED, LAYER_DEPOTS];
  }
  return layerIds.filter((layerId) => map?.getLayer?.(layerId));
}

let hoverCursorRaf = 0;
let hoverCursorEvent = null;

// mousemove 无节流（见 MyMap.js），这里用 rAF 把命中查询合并到每帧一次，避免每个鼠标事件都跑 queryRenderedFeatures
function updateSelectableMapCursor(event) {
  hoverCursorEvent = event;
  if (hoverCursorRaf) return;
  hoverCursorRaf = requestAnimationFrame(() => {
    hoverCursorRaf = 0;
    runSelectableCursorQuery(hoverCursorEvent);
  });
}

function runSelectableCursorQuery(event) {
  const map = MapRef.value?.map;
  const canvas = map?.getCanvas?.();
  if (!canvas) return;
  if (pendingAddDataset.value || pendingMoveTarget.value) {
    canvas.style.cursor = "crosshair";
    return;
  }
  const point = event?.data?.point;
  const layerIds = selectableMapLayerIds(map);
  if (!Array.isArray(point) || !layerIds.length) {
    canvas.style.cursor = "";
    return;
  }
  const features = map.queryRenderedFeatures(queryBoxAround(point, 8), { layers: layerIds });
  canvas.style.cursor = features.length ? "pointer" : "";
}

function bindSelectableHoverListener() {
  const mapInstance = MapRef.value;
  if (!mapInstance || selectableHoverListenerId) return;
  selectableHoverListenerId = mapInstance.addEventListener("handle:mousemove", updateSelectableMapCursor);
}

function unbindSelectableHoverListener() {
  const mapInstance = MapRef.value;
  if (mapInstance && selectableHoverListenerId) {
    mapInstance.removeEventListener("handle:mousemove", selectableHoverListenerId);
  }
  selectableHoverListenerId = null;
  if (hoverCursorRaf) {
    cancelAnimationFrame(hoverCursorRaf);
    hoverCursorRaf = 0;
  }
  hoverCursorEvent = null;
  const canvas = mapInstance?.map?.getCanvas?.();
  if (canvas) canvas.style.cursor = "";
}

function unbindStationClickListener() {
  if (!MapRef.value || !stationClickListenerId) return;
  MapRef.value.removeEventListener("handle:click", stationClickListenerId);
  stationClickListenerId = null;
}

function handleStationMapClick(event) {
  if (!isMapDataPage(activeKey.value) && !historyPreview.visible) return;
  if (historyPreview.visible) {
    handleOverviewMapClick(event);
    return;
  }
  if (activeKey.value === "update_station") {
    handleStationUpdateClick(event);
    return;
  }
  if (activeKey.value === "update_line") {
    handleLineUpdateClick(event);
    return;
  }
  if (activeKey.value === "update_depot") {
    handleDepotUpdateClick(event);
    return;
  }
  handleOverviewMapClick(event);
}

function handleOverviewMapClick(event) {
  const map = MapRef.value?.map;
  if (!map?.getLayer?.(LAYER_STATIONS) && !map?.getLayer?.(LAYER_LINES)) return;
  const point = event?.data?.point;
  if (!Array.isArray(point)) return;
  const stationFeature =
    firstRenderedFeature(point, [LAYER_STATION_SELECTED]) ||
    firstRenderedFeature(point, [LAYER_STATIONS]);
  if (stationFeature) {
    selectStation(stationFeature);
    return;
  }
  const depotFeature = firstRenderedFeature(point, [LAYER_DEPOT_SELECTED, LAYER_DEPOTS]);
  if (depotFeature) {
    selectDepot(depotFeature);
    return;
  }
  const lineFeature = selectLineNetwork(event);
  if (!lineFeature) {
    clearSelection();
  }
}

function handleStationUpdateClick(event) {
  const point = event?.data?.point;
  const lngLat = event?.data?.lngLat;
  if (!Array.isArray(point) || !Array.isArray(lngLat)) return;
  if (pendingAddDataset.value === "station") {
    openNewStationAttributeTable(lngLat);
    return;
  }
  const feature =
    firstRenderedFeature(point, [LAYER_STATION_SELECTED]) ||
    firstRenderedFeature(point, [LAYER_STATION_LABELS]) ||
    firstRenderedFeature(point, [LAYER_STATIONS]);
  if (feature) {
    selectStation(feature);
    openAttributeTable("station", selectedStation.value);
    return;
  }
  clearSelection();
}

function handleLineUpdateClick(event) {
  const point = event?.data?.point;
  if (!Array.isArray(point)) return;
  pendingAddDataset.value = "";
  const lineFeature = selectLineNetwork(event, { mode: "edit" });
  if (!lineFeature) {
    clearSelection();
    closeEditActionMenu();
  }
}

function handleDepotUpdateClick(event) {
  const point = event?.data?.point;
  const lngLat = event?.data?.lngLat;
  if (!Array.isArray(point) || !Array.isArray(lngLat)) return;
  if (pendingAddDataset.value === "depot") {
    openAddDialog("depot", lngLat);
    return;
  }
  if (pendingMoveTarget.value?.datasetType === "depot") {
    commitMoveOperation("depot", pendingMoveTarget.value.feature, lngLat);
    pendingMoveTarget.value = null;
    return;
  }
  const feature = firstRenderedFeature(point, [LAYER_DEPOT_SELECTED, LAYER_DEPOTS]);
  if (feature) {
    selectDepot(feature);
    openAttributeTable("depot", selectedDepot.value);
    return;
  }
  clearSelection();
  showEditActionMenu(event, "depot", null, [{ key: "add_depot", label: "新增场站" }]);
}

function firstRenderedFeature(point, layers, radius = 8) {
  const map = MapRef.value?.map;
  const queryLayers = layers.filter((layerId) => map?.getLayer?.(layerId));
  if (!map || !queryLayers.length) return null;
  const features = map.queryRenderedFeatures(queryBoxAround(point, radius), { layers: queryLayers });
  return features[0] || null;
}

function showEditActionMenu(event, datasetType, target, actions) {
  const point = event?.data?.point || [0, 0];
  closeSearchResults();
  closeStylePopover();
  closeLineRoutePicker();
  editActionMenu.visible = true;
  editActionMenu.datasetType = datasetType;
  editActionMenu.target = target || null;
  editActionMenu.lngLat = event?.data?.lngLat || null;
  editActionMenu.title = target ? editTargetName(datasetType, target) : "新增数据";
  editActionMenu.actions = actions;
  editActionMenu.x = clampPickerPosition(event?.data?.event?.clientX ?? point[0], 220, window.innerWidth);
  editActionMenu.y = clampPickerPosition(event?.data?.event?.clientY ?? point[1], 240, window.innerHeight);
}

function closeEditActionMenu() {
  editActionMenu.visible = false;
  editActionMenu.target = null;
  editActionMenu.lngLat = null;
  editActionMenu.actions = [];
}

async function handleEditMenuAction(action) {
  const datasetType = editActionMenu.datasetType;
  const target = editActionMenu.target;
  const lngLat = editActionMenu.lngLat;
  closeEditActionMenu();
  if (action === "move_station" || action === "move_depot") {
    pendingAddDataset.value = "";
    pendingMoveTarget.value = { datasetType, feature: target };
    ElMessage.info("请在地图上点击新的位置");
    return;
  }
  if (action === "delete_station" || action === "delete_line" || action === "delete_depot") {
    const confirmed = await confirmDeleteOperation(datasetType, target);
    if (!confirmed) return;
    addEditOperation(datasetType, action, target, {}, lngLat);
    applyLocalDelete(datasetType, target);
    return;
  }
  const dialogConfig = dialogConfigForAction(action, datasetType, target, lngLat);
  Object.assign(editDialog, dialogConfig);
  editDialog.visible = true;
}

function openAddDialog(datasetType, lngLat) {
  pendingAddDataset.value = "";
  closeTransientSurfaces();
  const action = datasetType === "depot" ? "add_depot" : "add_station";
  Object.assign(editDialog, dialogConfigForAction(action, datasetType, null, lngLat));
  editDialog.visible = true;
}

function openNewStationAttributeTable(lngLat) {
  const coordinate = validLngLat(lngLat);
  if (!coordinate) {
    ElMessage.warning("无法获取站点位置，请重新点选");
    return;
  }
  pendingAddDataset.value = "";
  clearSelection();
  const featureId = `station_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
  const feature = {
    type: "Feature",
    id: featureId,
    geometry: { type: "Point", coordinates: coordinate },
    properties: {
      _featureId: featureId,
      _stationKey: featureId,
      _routeStopKey: featureId,
      _attributeNew: true,
      stop_id: featureId,
      stop_name: "",
      lon: String(Number(coordinate[0].toFixed(7))),
      lat: String(Number(coordinate[1].toFixed(7))),
    },
  };
  openAttributeTable("station", {
    id: featureId,
    name: "",
    feature,
    isNew: true,
  });
}

async function confirmDeleteOperation(datasetType, target) {
  try {
    await ElMessageBox.confirm(`删除${datasetTypeLabel(datasetType)}「${editTargetName(datasetType, target)}」？该修改会先进入待提交列表，提交前仍可放弃。`, "确认删除", {
      confirmButtonText: "加入删除修改",
      cancelButtonText: "取消",
      type: "warning",
    });
    return true;
  } catch {
    return false;
  }
}

function beginAddFromPanel() {
  const datasetType = activeEditDataset.value;
  if (datasetType !== "station" && datasetType !== "depot") return;
  closeTransientSurfaces();
  pendingMoveTarget.value = null;
  pendingAddDataset.value = datasetType;
  ElMessage.info(`请在地图上点击新${datasetTypeLabel(datasetType)}的位置`);
}

function cancelPendingAdd() {
  pendingAddDataset.value = "";
  resetMapCanvasCursor();
}

function resetMapCanvasCursor() {
  const canvas = MapRef.value?.map?.getCanvas?.();
  if (canvas) canvas.style.cursor = "";
}

function handleUploadShpClick() {
  const datasetType = activeEditDataset.value;
  if (datasetType !== "line" && datasetType !== "station" && datasetType !== "depot") {
    ElMessage.warning("当前仅支持上传标准线路、站点或场站 SHP");
    return;
  }
  if (hasPendingUploadConflict(datasetType)) {
    ElMessage.warning("当前数据类型已有未提交修改，请先提交或放弃后再上传 SHP");
    return;
  }
  if (shpUploadInput.value) {
    shpUploadInput.value.value = "";
    shpUploadInput.value.click();
  }
}

async function handleUploadShpFiles(event) {
  const files = Array.from(event?.target?.files || []);
  const datasetType = activeEditDataset.value;
  if (!files.length || (datasetType !== "line" && datasetType !== "station" && datasetType !== "depot")) return;
  const areaName = selectedArea.value;
  const formData = new FormData();
  formData.append("areaName", areaName);
  formData.append("datasetType", datasetType);
  files.forEach((file) => formData.append("files", file));
  isSubmittingEdit.value = true;
  try {
    const res = await compareRealDataShp(formData);
    if (selectedArea.value !== areaName || activeEditDataset.value !== datasetType) {
      ElMessage.warning("区域或数据类型已切换，本次上传比对结果已忽略");
      return;
    }
    const data = res?.data || {};
    const operations = Array.isArray(data.operations) ? data.operations : [];
    if (!operations.length) {
      const protectedCount = Number(data.protectedFeatureCount || 0);
      const skippedDeletionCount = Number(data.skippedByManualDeletionCount || 0);
      if (protectedCount || skippedDeletionCount) {
        ElMessage.info(`未生成修改，已保留 ${Math.max(protectedCount, skippedDeletionCount)} 条人工修改数据`);
      } else {
        ElMessage.success("上传 SHP 与当前数据一致，无需生成修改");
      }
      return;
    }
    const deletionCandidates = operations.filter(isShpDeletionCandidate);
    const updates = operations.filter((operation) => !isShpDeletionCandidate(operation));
    if (deletionCandidates.length) {
      openShpDeletionDialog(datasetType, updates, deletionCandidates, data);
      return;
    }
    appendUploadOperations(datasetType, updates);
    showShpMergeSuccess(datasetType, updates, data);
  } catch (error) {
    ElMessage.error(error?.message || "上传 SHP 比对失败，请检查文件格式");
  } finally {
    isSubmittingEdit.value = false;
    if (event?.target) event.target.value = "";
  }
}

function hasPendingUploadConflict(datasetType) {
  if (editOperations[datasetType]?.length) return true;
  return datasetType === "line" && editOperations.station.some((operation) => operation.type === "reorder_line_stations");
}

function isShpDeletionCandidate(operation) {
  const type = String(operation?.type || "");
  return Boolean(operation?.candidateDeletion) || (type.startsWith("delete_") && type.endsWith("_from_shp"));
}

function openShpDeletionDialog(datasetType, updates, deletions, comparison = {}) {
  shpDeletionDialog.datasetType = datasetType;
  // markRaw：成千上万条带完整几何的操作无需深响应式，分页/勾选依赖的是整体替换与 page/selectedIds
  shpDeletionDialog.updates = markRaw(deepClone(updates));
  shpDeletionDialog.deletions = markRaw(deepClone(deletions));
  shpDeletionDialog.selectedIds = [];
  shpDeletionDialog.protectedFeatureCount = Number(comparison.protectedFeatureCount || 0);
  shpDeletionDialog.page = 1;
  shpDeletionDialog.visible = true;
}

function selectAllShpDeletionCandidates() {
  shpDeletionDialog.selectedIds = shpDeletionDialog.deletions.map((operation) => operation.operationId);
}

function confirmShpDeletionCandidates() {
  const selectedIds = new Set(shpDeletionDialog.selectedIds);
  const confirmedDeletions = shpDeletionDialog.deletions
    .filter((operation) => selectedIds.has(operation.operationId))
    .map((operation) => ({
      ...operation,
      deletionConfirmed: true,
    }));
  const retainedDeletions = shpDeletionDialog.deletions
    .filter((operation) => !selectedIds.has(operation.operationId));
  const operations = [...shpDeletionDialog.updates, ...confirmedDeletions];
  const datasetType = shpDeletionDialog.datasetType;
  const protectedFeatureCount = [
    ...shpDeletionDialog.updates,
    ...retainedDeletions,
  ].filter((operation) => operation.manualProtected || operation.protectedFields?.length).length;
  shpDeletionDialog.visible = false;
  appendUploadOperations(datasetType, operations);
  showShpMergeSuccess(datasetType, operations, {
    protectedFeatureCount,
    retainedDeletionCount: retainedDeletions.length,
  });
}

function cancelShpDeletionImport() {
  shpDeletionDialog.visible = false;
  ElMessage.info("已取消本次 SHP 导入，未生成修改");
}

function resetShpDeletionDialog() {
  shpDeletionDialog.datasetType = "";
  shpDeletionDialog.updates = [];
  shpDeletionDialog.deletions = [];
  shpDeletionDialog.selectedIds = [];
  shpDeletionDialog.protectedFeatureCount = 0;
  shpDeletionDialog.page = 1;
}

function showShpMergeSuccess(datasetType, operations, comparison = {}) {
  const protectedCount = Number(comparison.protectedFeatureCount || 0);
  const retainedDeletionCount = Number(comparison.retainedDeletionCount || 0);
  const notes = [];
  if (protectedCount) notes.push(`已保留 ${protectedCount} 条数据的人工修改`);
  if (retainedDeletionCount) notes.push(`保留 ${retainedDeletionCount} 条未勾选数据`);
  const suffix = notes.length ? `，${notes.join("，")}` : "";
  if (!operations.length) {
    ElMessage.info(`本次未生成修改${suffix}`);
    return;
  }
  ElMessage.success(`已生成 ${operations.length} 条${datasetTypeLabel(datasetType)}修改${suffix}，请在右侧核对后提交`);
}

function isAttributeEditableDataset(datasetType) {
  return datasetType === "line" || datasetType === "station" || datasetType === "depot";
}

function openAttributeTable(datasetType, target) {
  if (!isAttributeEditableDataset(datasetType) || !target) return;
  const initialScope = datasetType;
  const view = buildAttributeTableView(datasetType, target, initialScope);
  if (!view.rows.length) {
    ElMessage.warning("未找到可编辑的属性记录");
    return;
  }
  attributeTable.datasetType = datasetType;
  attributeTable.viewDatasetType = view.viewDatasetType;
  attributeTable.target = target;
  attributeTable.route = attributeRouteContext(datasetType, target);
  attributeTable.station = datasetType === "station" ? target.station || target : null;
  attributeTable.scope = initialScope;
  attributeTable.showRouteStations = initialScope === "route";
  attributeTable.title = `${datasetTypeLabel(datasetType)}属性表`;
  attributeTable.viewCache = {};
  setAttributeTableView(view);
  attributeTable.visible = true;
  closeTransientSurfaces();
  loadAttributeTableHistory(datasetType, target);
}

async function loadAttributeTableHistory(datasetType, target) {
  const seq = ++attributeHistoryRequestSeq;
  attributeTable.historyRows = [];
  attributeTable.historyError = "";
  const supportsHistory = ["station", "line", "depot"].includes(datasetType) && !target?.isNew;
  attributeTable.historyLoading = supportsHistory;
  if (!supportsHistory) return;
  try {
    const data = await getCachedRealDataHistory(selectedArea.value);
    if (seq !== attributeHistoryRequestSeq) return;
    if (datasetType === "line") {
      attributeTable.historyRows = lineAttributeHistoryRows(target, data?.versions, attributeTable.scope);
    } else if (datasetType === "depot") {
      attributeTable.historyRows = depotAttributeHistoryRows(target, data?.versions);
    } else {
      attributeTable.historyRows = stationAttributeHistoryRows(target, data?.versions);
    }
  } catch (error) {
    if (seq !== attributeHistoryRequestSeq) return;
    attributeTable.historyError = error?.message || "历史记录加载失败";
  } finally {
    if (seq === attributeHistoryRequestSeq) {
      attributeTable.historyLoading = false;
    }
  }
}

function stationAttributeHistoryRows(target, versions = []) {
  const identity = stationHistoryIdentity(target);
  if (!identity.ids.size && !identity.names.size) return [];
  const currentFeature = physicalStationFeatureForAttributeTable(target);
  const fallbackProperties = currentFeature?.properties || {};
  const rows = [];
  for (const version of Array.isArray(versions) ? versions : []) {
    for (const operation of Array.isArray(version?.operations) ? version.operations : []) {
      if (operation?.datasetType !== "station" || !stationHistoryOperationMatches(operation, identity)) continue;
      const values = stationHistorySnapshot(operation, fallbackProperties);
      rows.push({
        key: operation.operationId || `${version.versionId}-${rows.length}`,
        action: operationLabel(operation.type),
        detail: operation.detail || historyOperationPayloadText(operation) || version.message || "站点属性已更新",
        values,
        changedKeys: stationHistoryChangedKeys(operation),
        username: operation.username || version.username || "未知用户",
        committedAt: Number(operation.committedAt || version.committedAt || 0),
      });
    }
  }
  return rows.sort((left, right) => right.committedAt - left.committedAt);
}

function depotAttributeHistoryRows(target, versions = []) {
  const identity = depotHistoryIdentity(target);
  if (!identity.ids.size && !identity.names.size) return [];
  const fallbackProperties = target?.feature?.properties || target?.properties || {};
  const rows = [];
  for (const version of Array.isArray(versions) ? versions : []) {
    for (const operation of Array.isArray(version?.operations) ? version.operations : []) {
      if (operation?.datasetType !== "depot" || !depotHistoryOperationMatches(operation, identity)) continue;
      rows.push({
        key: operation.operationId || `${version.versionId}-${rows.length}`,
        action: operationLabel(operation.type),
        detail: operation.detail || historyOperationPayloadText(operation) || version.message || "场站属性已更新",
        values: depotHistorySnapshot(operation, fallbackProperties),
        changedKeys: depotHistoryChangedKeys(operation, fallbackProperties),
        username: operation.username || version.username || "未知用户",
        committedAt: Number(operation.committedAt || version.committedAt || 0),
      });
    }
  }
  return rows.sort((left, right) => right.committedAt - left.committedAt);
}

function depotHistoryIdentity(target) {
  const feature = target?.feature || target;
  const properties = feature?.properties || target?.properties || {};
  const ids = new Set([
    target?.id,
    feature?.id,
    properties._featureId,
    properties._depotKey,
  ].map(valueOrEmpty).filter(Boolean));
  const names = new Set([
    target?.name,
    depotName(properties),
  ].map(valueOrEmpty).filter(Boolean));
  return { ids, names };
}

function depotHistoryOperationMatches(operation, identity) {
  const payload = operation?.payload && typeof operation.payload === "object" ? operation.payload : {};
  const feature = payload.feature && typeof payload.feature === "object" ? payload.feature : {};
  const properties = feature.properties && typeof feature.properties === "object" ? feature.properties : {};
  const ids = [
    operation.targetId,
    payload.targetId,
    payload.featureId,
    payload.depotKey,
    feature.id,
    properties._featureId,
    properties._depotKey,
  ].map(valueOrEmpty).filter(Boolean);
  if (ids.some((id) => identity.ids.has(id))) return true;
  const names = [
    operation.title,
    payload.name,
    depotName(properties),
  ].map(valueOrEmpty).filter(Boolean);
  return names.some((name) => identity.names.has(name));
}

function depotHistorySnapshot(operation, fallbackProperties = {}) {
  const payload = operation?.payload && typeof operation.payload === "object" ? operation.payload : {};
  const properties = payload.feature?.properties && typeof payload.feature.properties === "object"
    ? payload.feature.properties
    : {};
  const values = displayAttributeProperties({ ...fallbackProperties, ...properties });
  if (payload.name) {
    const nameKey = depotHistoryNameKey(values);
    values[nameKey] = String(payload.name);
  }
  if (String(operation?.type || "").startsWith("move_")) {
    depotHistoryCoordinateKeys(values, "lng").forEach((key) => {
      values[key] = String(payload.lng ?? values[key] ?? "");
    });
    depotHistoryCoordinateKeys(values, "lat").forEach((key) => {
      values[key] = String(payload.lat ?? values[key] ?? "");
    });
    if (Object.prototype.hasOwnProperty.call(values, "F026")) {
      values.F026 = formatLngLat(payload.lng, payload.lat);
    }
  }
  return values;
}

function depotHistoryChangedKeys(operation, fallbackProperties = {}) {
  const type = String(operation?.type || "");
  const detail = String(operation?.detail || "");
  const payload = operation?.payload && typeof operation.payload === "object" ? operation.payload : {};
  const properties = payload.feature?.properties && typeof payload.feature.properties === "object"
    ? payload.feature.properties
    : {};
  const displayedProperties = displayAttributeProperties({ ...fallbackProperties, ...properties });
  const keys = new Set();
  if (type.startsWith("add_") || type.startsWith("delete_")) {
    Object.keys(displayedProperties).forEach((key) => keys.add(key));
  }
  if (type.startsWith("rename_")) {
    keys.add(depotHistoryNameKey(displayedProperties));
  }
  if (type.startsWith("move_")) {
    depotHistoryCoordinateKeys(displayedProperties, "lng").forEach((key) => keys.add(key));
    depotHistoryCoordinateKeys(displayedProperties, "lat").forEach((key) => keys.add(key));
    if (Object.prototype.hasOwnProperty.call(displayedProperties, "F026")) keys.add("F026");
  }
  Object.keys(displayedProperties).forEach((key) => {
    if (detail.includes(attributeColumnLabel(key))) keys.add(key);
  });
  if (!keys.size && type.startsWith("replace_")) {
    Object.keys(displayAttributeProperties(properties)).forEach((key) => keys.add(key));
  }
  return [...keys];
}

function depotHistoryNameKey(properties = {}) {
  return ["depot_name", "name", "场站名称", "station_name"]
    .find((key) => Object.prototype.hasOwnProperty.call(properties, key)) || "depot_name";
}

function depotHistoryCoordinateKeys(properties = {}, axis) {
  const candidates = axis === "lng"
    ? ["lon", "lng", "longitude", "经度"]
    : ["lat", "latitude", "纬度"];
  return candidates.filter((key) => Object.prototype.hasOwnProperty.call(properties, key));
}

function lineAttributeHistoryRows(target, versions = [], scope = "line") {
  if (scope === "route") {
    return routeStationAttributeHistoryRows(target, versions);
  }
  const identity = lineHistoryIdentity(target);
  if (!identity.ids.size && !identity.routeIds.size && !identity.names.size) return [];
  const fallbackProperties = target?.feature?.properties || target?.properties || {};
  const rows = [];
  for (const version of Array.isArray(versions) ? versions : []) {
    const legacyStationOrderOperations = [];
    for (const operation of Array.isArray(version?.operations) ? version.operations : []) {
      if (isLineStationOrderHistoryOperation(operation, identity)) {
        if (operation.type === "reorder_line_stations") {
          rows.push(lineStationOrderHistoryRow(version, [operation], fallbackProperties));
        } else {
          legacyStationOrderOperations.push(operation);
        }
        continue;
      }
      if (operation?.datasetType !== "line" || !lineHistoryOperationMatches(operation, identity)) continue;
      rows.push({
        key: operation.operationId || `${version.versionId}-${rows.length}`,
        action: operationLabel(operation.type),
        detail: operation.detail || historyOperationPayloadText(operation) || version.message || "线路属性已更新",
        values: lineHistorySnapshot(operation, fallbackProperties),
        changedKeys: lineHistoryChangedKeys(operation),
        username: operation.username || version.username || "未知用户",
        committedAt: Number(operation.committedAt || version.committedAt || 0),
      });
    }
    if (legacyStationOrderOperations.length) {
      rows.push(lineStationOrderHistoryRow(version, legacyStationOrderOperations, fallbackProperties));
    }
  }
  return rows.sort((left, right) => right.committedAt - left.committedAt);
}

function isLineStationOrderHistoryOperation(operation, identity) {
  const type = String(operation?.type || "");
  const detail = String(operation?.detail || "");
  if (type !== "reorder_line_stations" && !(type === "replace_station_from_table" && detail.includes("站序"))) {
    return false;
  }
  const payload = operation?.payload && typeof operation.payload === "object" ? operation.payload : {};
  const properties = payload.feature?.properties && typeof payload.feature.properties === "object"
    ? payload.feature.properties
    : {};
  const targetParts = valueOrEmpty(operation?.targetId || payload.targetId).split("|");
  const routeIds = [
    payload.lineId,
    payload.line_id,
    payload.route_id,
    properties.line_id,
    properties.route_id,
    targetParts.length >= 3 ? targetParts[0] : "",
  ].map(valueOrEmpty).filter(Boolean);
  return routeIds.some((routeId) => identity.routeIds.has(routeId));
}

function lineStationOrderHistoryRow(version, operations, fallbackProperties = {}) {
  const operation = operations[0] || {};
  const stationOrderText = operation.type === "reorder_line_stations"
    ? routeStationReorderHistoryText(operation)
    : legacyRouteStationReorderHistoryText(operations);
  return {
    key: operation.operationId || `${version.versionId}-station-order`,
    action: operationLabel("reorder_line_stations"),
    detail: stationOrderText || operation.detail || version.message || "线路站序已调整",
    values: {
      ...displayAttributeProperties(fallbackProperties),
      station_order_change: stationOrderText,
    },
    changedKeys: ["station_order_change"],
    username: operation.username || version.username || "未知用户",
    committedAt: Number(operation.committedAt || version.committedAt || 0),
  };
}

function legacyRouteStationReorderHistoryText(operations = []) {
  return [...operations]
    .sort((left, right) => Number(routeStationHistorySeq(left)) - Number(routeStationHistorySeq(right)))
    .map((operation) => {
      const properties = operation?.payload?.feature?.properties || {};
      const stop = valueOrEmpty(properties.stop_name || properties.name || properties.stop_id || operation?.title) || "站点";
      const fromSeq = legacyRouteStationPreviousSeq(operation);
      const toSeq = routeStationHistorySeq(operation);
      return `${stop} ${fromSeq || "—"}→${toSeq || "—"}`;
    })
    .join("；");
}

function routeStationHistorySeq(operation) {
  return valueOrEmpty(operation?.payload?.feature?.properties?.seq);
}

function legacyRouteStationPreviousSeq(operation) {
  const targetId = valueOrEmpty(operation?.targetId || operation?.payload?.targetId);
  if (targetId.includes("|")) return valueOrEmpty(targetId.split("|").at(-1));
  if (targetId.startsWith("rs.")) return valueOrEmpty(targetId.split(".").at(-1));
  return "";
}

function routeStationAttributeHistoryRows(route, versions = []) {
  const routeStops = routeStopFeaturesForRoute(route?.properties || {}, route);
  const routeId = routeDataId(route?.properties || {});
  const stationContexts = routeStops.map(({ feature }) => {
    const stationTarget = {
      id: feature?.properties?.stop_id || feature?.id,
      name: stationName(feature?.properties || {}),
      feature,
    };
    return {
      identity: stationHistoryIdentity(stationTarget),
      fallbackProperties: {
        ...(feature?.properties || {}),
        ...(physicalStationFeatureForAttributeTable(stationTarget)?.properties || {}),
      },
    };
  });
  if (!routeId) return [];
  const rows = [];
  for (const version of Array.isArray(versions) ? versions : []) {
    for (const operation of Array.isArray(version?.operations) ? version.operations : []) {
      if (operation?.datasetType !== "station") continue;
      if (operation.type === "reorder_line_stations") {
        const payload = operation?.payload && typeof operation.payload === "object" ? operation.payload : {};
        if (valueOrEmpty(payload.lineId || payload.line_id) !== routeId) continue;
        rows.push({
          key: operation.operationId || `${version.versionId}-${rows.length}`,
          action: operationLabel(operation.type),
          detail: operation.detail || "线路站序已调整",
          values: routeStationReorderHistorySnapshot(operation),
          changedKeys: ["seq"],
          username: operation.username || version.username || "未知用户",
          committedAt: Number(operation.committedAt || version.committedAt || 0),
        });
        continue;
      }
      const operationFeature = operation?.payload?.feature;
      const operationProperties = operationFeature?.properties || {};
      const operationRouteId = routeDataId(operationProperties);
      const targetParts = valueOrEmpty(operation?.targetId || operation?.payload?.targetId).split("|");
      const targetRouteId = targetParts.length >= 3 ? targetParts[0] : "";
      if (operationRouteId !== routeId && targetRouteId !== routeId) continue;
      const context = stationContexts.find((item) => stationHistoryOperationMatches(operation, item.identity));
      const fallbackProperties = {
        line_id: targetRouteId,
        stop_id: targetParts.length >= 3 ? targetParts[1] : "",
        seq: targetParts.length >= 3 ? targetParts[2] : "",
        ...(context?.fallbackProperties || {}),
        ...operationProperties,
      };
      rows.push({
        key: operation.operationId || `${version.versionId}-${rows.length}`,
        action: operationLabel(operation.type),
        detail: operation.detail || historyOperationPayloadText(operation) || version.message || "线路站点编组已更新",
        values: stationHistorySnapshot(operation, fallbackProperties),
        changedKeys: stationHistoryChangedKeys(operation),
        username: operation.username || version.username || "未知用户",
        committedAt: Number(operation.committedAt || version.committedAt || 0),
      });
    }
  }
  return rows.sort((left, right) => right.committedAt - left.committedAt);
}

function routeStationReorderHistorySnapshot(operation) {
  const changes = Array.isArray(operation?.payload?.changes) ? operation.payload.changes : [];
  const sortedChanges = [...changes].sort((left, right) => Number(left.toSeq) - Number(right.toSeq));
  return {
    seq: routeStationReorderHistoryText(operation),
    stop_id: sortedChanges.map((item) => valueOrEmpty(item.stopId)).filter(Boolean).join("；"),
    stop_name: sortedChanges.map((item) => valueOrEmpty(item.stopName)).filter(Boolean).join("；"),
  };
}

function routeStationReorderHistoryText(operation) {
  const changes = Array.isArray(operation?.payload?.changes) ? operation.payload.changes : [];
  return [...changes]
    .sort((left, right) => Number(left.toSeq) - Number(right.toSeq))
    .map((item) => `${valueOrEmpty(item.stopName || item.stopId) || "站点"} ${valueOrEmpty(item.fromSeq) || "—"}→${valueOrEmpty(item.toSeq) || "—"}`)
    .join("；");
}

function lineHistoryIdentity(target) {
  const features = routeFeaturesForOption(target);
  const ids = new Set();
  const routeIds = new Set();
  const names = new Set();
  for (const feature of features.length ? features : [target?.feature || target]) {
    const properties = feature?.properties || {};
    [
      feature?.id,
      properties._lineKey,
      properties._featureId,
    ].map(valueOrEmpty).filter(Boolean).forEach((value) => ids.add(value));
    [
      properties.line_id,
      properties.route_id,
    ].map(valueOrEmpty).filter(Boolean).forEach((value) => routeIds.add(value));
    [routeName(properties), properties.name].map(valueOrEmpty).filter(Boolean).forEach((value) => names.add(value));
  }
  [target?.id].map(valueOrEmpty).filter(Boolean).forEach((value) => ids.add(value));
  [target?.name].map(valueOrEmpty).filter(Boolean).forEach((value) => names.add(value));
  return { ids, routeIds, names };
}

function lineHistoryOperationMatches(operation, identity) {
  const payload = operation?.payload && typeof operation.payload === "object" ? operation.payload : {};
  const feature = payload.feature && typeof payload.feature === "object" ? payload.feature : {};
  const properties = feature.properties && typeof feature.properties === "object" ? feature.properties : {};
  const ids = [
    operation.targetId,
    payload.targetId,
    payload.featureId,
    payload.lineKey,
    feature.id,
    properties._lineKey,
    properties._featureId,
  ].map(valueOrEmpty).filter(Boolean);
  if (ids.some((value) => identity.ids.has(value))) return true;
  const routeIds = [
    properties.line_id,
    properties.route_id,
    payload.line_id,
    payload.route_id,
  ].map(valueOrEmpty).filter(Boolean);
  if (routeIds.some((value) => identity.routeIds.has(value))) return true;
  const names = [
    operation.title,
    payload.name,
    properties.name,
  ].map(valueOrEmpty).filter(Boolean);
  return names.some((value) => identity.names.has(value));
}

function lineHistorySnapshot(operation, fallbackProperties = {}) {
  const payload = operation?.payload && typeof operation.payload === "object" ? operation.payload : {};
  const feature = payload.feature && typeof payload.feature === "object" ? payload.feature : {};
  const properties = feature.properties && typeof feature.properties === "object" ? feature.properties : {};
  const values = displayAttributeProperties({ ...fallbackProperties, ...properties });
  if (payload.name) values.name = String(payload.name);
  if (payload.headway) values.interval = String(payload.headway);
  if (payload.stations) values.station_list_edit = String(payload.stations);
  return values;
}

function lineHistoryChangedKeys(operation) {
  const type = String(operation?.type || "");
  const detail = String(operation?.detail || "");
  const keys = new Set();
  if (type.startsWith("add_") || type.startsWith("delete_")) {
    LINE_ATTRIBUTE_FIELD_ORDER.forEach((key) => keys.add(key));
  }
  if (type === "update_line_headway") keys.add("interval");
  if (type === "update_line_stations") keys.add("station_list_edit");
  LINE_ATTRIBUTE_FIELD_ORDER.forEach((key) => {
    if (detail.includes(attributeColumnLabel(key))) keys.add(key);
  });
  if (!keys.size && type.startsWith("replace_")) {
    const payload = operation?.payload && typeof operation.payload === "object" ? operation.payload : {};
    const properties = payload.feature?.properties && typeof payload.feature.properties === "object"
      ? payload.feature.properties
      : {};
    Object.keys(displayAttributeProperties(properties)).forEach((key) => keys.add(key));
  }
  return [...keys];
}

function stationHistorySnapshot(operation, fallbackProperties = {}) {
  const payload = operation?.payload && typeof operation.payload === "object" ? operation.payload : {};
  const feature = payload.feature && typeof payload.feature === "object" ? payload.feature : {};
  const properties = feature.properties && typeof feature.properties === "object" ? feature.properties : {};
  const coordinates = pointCoordinates(feature.geometry);
  const values = displayAttributeProperties({ ...fallbackProperties, ...properties });
  values.stop_id = firstHistoryValue(
      properties.stop_id,
      payload.stop_id,
      payload.stationKey,
      operation?.targetId,
      fallbackProperties.stop_id,
    );
  values.stop_name = firstHistoryValue(
      properties.stop_name,
      properties.name,
      payload.name,
      payload.stationName,
      operation?.title,
      fallbackProperties.stop_name,
    );
  values.lon = firstHistoryValue(properties.lon, payload.lng, coordinates?.[0], fallbackProperties.lon);
  values.lat = firstHistoryValue(properties.lat, payload.lat, coordinates?.[1], fallbackProperties.lat);
  if (String(operation?.type || "").startsWith("rename_") && payload.name) {
    values.stop_name = String(payload.name);
  }
  if (String(operation?.type || "").startsWith("move_")) {
    values.lon = firstHistoryValue(payload.lng, values.lon);
    values.lat = firstHistoryValue(payload.lat, values.lat);
  }
  return values;
}

function stationHistoryChangedKeys(operation) {
  const type = String(operation?.type || "");
  const detail = String(operation?.detail || "");
  if (type === "reorder_line_stations") return ["seq"];
  const keys = new Set();
  if (type.startsWith("add_") || type.startsWith("delete_")) {
    ROUTE_STOP_ATTRIBUTE_FIELD_ORDER.forEach((key) => keys.add(key));
  }
  if (type.startsWith("rename_")) keys.add("stop_name");
  if (type.startsWith("move_")) {
    keys.add("lon");
    keys.add("lat");
  }
  ROUTE_STOP_ATTRIBUTE_FIELD_ORDER.forEach((key) => {
    if (detail.includes(attributeColumnLabel(key))) keys.add(key);
  });
  if (!keys.size && type.startsWith("replace_")) {
    const payload = operation?.payload && typeof operation.payload === "object" ? operation.payload : {};
    const properties = payload.feature?.properties && typeof payload.feature.properties === "object"
      ? payload.feature.properties
      : {};
    ROUTE_STOP_ATTRIBUTE_FIELD_ORDER.forEach((key) => {
      if (Object.prototype.hasOwnProperty.call(properties, key)) keys.add(key);
    });
  }
  return [...keys];
}

function firstHistoryValue(...values) {
  for (const value of values) {
    if (value === undefined || value === null) continue;
    const text = String(value).trim();
    if (text) return text;
  }
  return "";
}

function stationHistoryIdentity(target) {
  const station = target?.station || target;
  const feature = station?.feature || station;
  const properties = feature?.properties || {};
  const ids = new Set([
    station?.id,
    feature?.id,
    properties.stop_id,
    properties._stationKey,
    properties._featureId,
  ].map(valueOrEmpty).filter(Boolean));
  const names = new Set([
    station?.name,
    properties.stop_name,
    properties.name,
  ].map(valueOrEmpty).filter(Boolean));
  return { ids, names };
}

function stationHistoryOperationMatches(operation, identity) {
  const payload = operation?.payload && typeof operation.payload === "object" ? operation.payload : {};
  const feature = payload.feature && typeof payload.feature === "object" ? payload.feature : {};
  const properties = feature.properties && typeof feature.properties === "object" ? feature.properties : {};
  const operationIds = [
    operation.targetId,
    operation.stationKey,
    payload.targetId,
    payload.stationKey,
    payload.featureId,
    feature.id,
    properties.stop_id,
    properties._stationKey,
    properties._featureId,
  ].map(valueOrEmpty).filter(Boolean);
  const idMatched = operationIds.some((candidate) =>
    identity.ids.has(candidate) || candidate.split("|").some((part) => identity.ids.has(part)),
  );
  if (idMatched) return true;
  const operationNames = [
    operation.title,
    payload.name,
    payload.stationName,
    properties.stop_name,
    properties.name,
  ].map(valueOrEmpty).filter(Boolean);
  return operationNames.some((name) => identity.names.has(name));
}

function buildAttributeTableView(datasetType, target, scope) {
  const viewDatasetType = datasetType === "line" && scope === "route" ? "station" : datasetType;
  const rows = buildAttributeTableRows(datasetType, target, scope);
  const columns = buildAttributeTableColumns(
    viewDatasetType,
    rows,
    datasetType === "line" && scope === "route" ? LINE_ROUTE_STATION_FIELD_ORDER : null,
  );
  const normalizedRows = rows.map((row) => ensureAttributeRowColumns(row, columns));
  return {
    scope,
    viewDatasetType,
    subtitle: attributeTableSubtitle(datasetType, target, scope),
    columns,
    rows: normalizedRows,
    originalRows: deepClone(normalizedRows),
  };
}

function setAttributeTableView(view) {
  attributeTable.scope = view.scope;
  attributeTable.showRouteStations = view.scope === "route";
  attributeTable.viewDatasetType = view.viewDatasetType || attributeTable.datasetType;
  if (attributeTable.datasetType === "line") {
    attributeTable.title = view.scope === "route" ? "线路站点编组" : "线路属性表";
  }
  attributeTable.subtitle = view.subtitle;
  // 视图所有权约定：传入的 view 要么是新构建的，要么来自 viewCache（切换离开时 capture 会重新克隆快照），
  // 这里直接引用即可，省去每次打开/切换的三重深克隆
  attributeTable.columns = view.columns;
  attributeTable.historyColumns = attributeTableHistoryColumns(
    attributeTable.datasetType,
    view.scope,
    view.columns,
  );
  attributeTable.rows = view.rows;
  attributeTable.originalRows = view.originalRows;
}

function attributeTableHistoryColumns(datasetType, scope, columns = []) {
  const historyColumns = deepClone(columns);
  if (datasetType !== "line" || scope !== "line") return historyColumns;
  const routeIdIndex = historyColumns.findIndex((column) => column.key === "route_id");
  historyColumns.splice(routeIdIndex >= 0 ? routeIdIndex + 1 : 0, 0, { ...LINE_STATION_ORDER_HISTORY_COLUMN });
  return historyColumns;
}

function captureAttributeTableView() {
  return {
    scope: attributeTable.scope,
    viewDatasetType: attributeTable.viewDatasetType,
    subtitle: attributeTable.subtitle,
    columns: deepClone(attributeTable.columns),
    rows: deepClone(attributeTable.rows),
    originalRows: deepClone(attributeTable.originalRows),
  };
}

function attributeTableSubtitle(datasetType, target, scope = attributeTable.scope) {
  if (datasetType === "line") {
    if (scope === "route") return `当前线路：${parsePickerRoute(target.name || routeName(target.properties)).mainName} / 调整站序或增减站点`;
    return `当前筛选：${parsePickerRoute(target.name || routeName(target.properties)).mainName}`;
  }
  if (datasetType === "depot") {
    return `当前筛选：${target?.name || depotName(target?.feature?.properties || target?.properties)}`;
  }
  if (target?.isNew) {
    const coordinates = pointCoordinates(target?.feature?.geometry);
    return coordinates ? `新增站点位置：${coordinates[0].toFixed(7)}, ${coordinates[1].toFixed(7)}` : "新增站点";
  }
  const station = target.station || target;
  return `当前筛选：${station.name || stationName(station.feature?.properties)}`;
}

function attributeRouteContext(datasetType, target) {
  if (datasetType === "line") return target;
  return target?.route || null;
}

function buildAttributeTableRows(datasetType, target, scope = attributeTable.scope) {
  if (datasetType === "line") {
    if (scope === "route") {
      return routeStopFeaturesForRoute(target.properties || {}, target)
        .map(({ feature }, index) => {
          const row = attributeRowFromFeature("station", feature, index);
          row.targetId = feature?.id || feature?.properties?._featureId || routeStopFeatureKey(feature?.properties || {}) || row.targetId;
          row.properties = pickAttributeProperties(row.properties, LINE_ROUTE_STATION_FIELD_ORDER);
          row.originalProperties = pickAttributeProperties(row.originalProperties, LINE_ROUTE_STATION_FIELD_ORDER);
          return row;
        });
    }
    const features = routeFeaturesForOption(target);
    return features.map((feature, index) => attributeRowFromFeature(datasetType, feature, index));
  }
  if (datasetType === "depot") {
    const feature = target?.feature || target;
    return feature?.geometry || feature?.properties ? [attributeRowFromFeature(datasetType, feature, 0)] : [];
  }
  if (scope === "route" && target?.route) {
    return routeStopFeaturesForRoute(target.route.properties || {}, target.route)
      .map(({ feature }, index) => attributeRowFromFeature(datasetType, feature, index));
  }
  const feature = physicalStationFeatureForAttributeTable(target);
  return feature ? [attributeRowFromFeature(datasetType, feature, 0)] : [];
}

function routeStopFeaturesForSelectedStation(target) {
  const selectedStationTarget = target?.station || target;
  const selectedFeature = selectedStationTarget?.feature || selectedStationTarget;
  const route = target?.route || null;
  const properties = selectedFeature?.properties || {};
  const stationId = valueOrEmpty(properties.stop_id || properties._stationKey || selectedStationTarget?.id);
  const name = valueOrEmpty(properties.stop_name || properties.name || selectedStationTarget?.name);
  const routeId = route ? routeDataId(route.properties || {}) : "";
  const routeStops = Array.isArray(realDataCollections.routeStops?.features) ? realDataCollections.routeStops.features : [];
  const matches = routeStops.filter((feature) => {
    const stopProperties = feature?.properties || {};
    const stopId = valueOrEmpty(stopProperties.stop_id || stopProperties._stationKey);
    const stationMatches = (stationId && stopId && stationId === stopId) || Boolean(name && stationName(stopProperties) === name);
    if (!stationMatches) return false;
    return route ? isRouteStopMatch(stopProperties, routeId) : true;
  });
  if (matches.length) return matches;
  return selectedFeature?.geometry ? [selectedFeature] : [];
}

function physicalStationFeatureForAttributeTable(target) {
  const station = target?.station || target;
  const selectedFeature = station?.feature || station;
  if (!selectedFeature) return null;
  const routeStopFeature = routeStopFeaturesForSelectedStation(station)[0];
  const sourceFeature = routeStopFeature || selectedFeature;
  const sourceProperties = sourceFeature?.properties || {};
  const selectedProperties = selectedFeature?.properties || {};
  const stationId = valueOrEmpty(
    selectedProperties.stop_id ||
      selectedProperties._stationKey ||
      sourceProperties.stop_id ||
      sourceProperties._stationKey ||
      station?.id,
  );
  if (!stationId) return null;
  const geometry = selectedFeature.geometry || sourceFeature?.geometry || null;
  const coordinates = pointCoordinates(geometry);
  const lon = firstAvailableValue(selectedProperties, ["lon"]) ||
    firstAvailableValue(sourceProperties, ["lon"]) ||
    valueOrEmpty(coordinates?.[0]);
  const lat = firstAvailableValue(selectedProperties, ["lat"]) ||
    firstAvailableValue(sourceProperties, ["lat"]) ||
    valueOrEmpty(coordinates?.[1]);
  const stationProperties = {};
  Object.entries({ ...sourceProperties, ...selectedProperties }).forEach(([key, value]) => {
    if (!isInternalAttributeKey(key) && !["line_id", "dir", "seq"].includes(key)) {
      stationProperties[key] = value;
    }
  });
  return {
    type: "Feature",
    id: stationId,
    geometry: geometry ? deepClone(geometry) : null,
    properties: {
      ...stationProperties,
      _featureId: stationId,
      _stationKey: stationId,
      ...(selectedProperties._attributeNew === true ? { _attributeNew: true } : {}),
      stop_id: stationId,
      stop_name: selectedProperties._attributeNew === true
        ? valueOrEmpty(selectedProperties.stop_name)
        : valueOrEmpty(selectedProperties.stop_name || selectedProperties.name || sourceProperties.stop_name || sourceProperties.name || station?.name),
      lon,
      lat,
    },
  };
}

function attributeRowFromFeature(datasetType, feature, index = 0) {
  const properties = { ...(feature?.properties || {}) };
  const displayProperties = displayAttributeProperties(properties);
  const isNew = properties._attributeNew === true;
  return {
    datasetType,
    rowId: `${datasetType}-${featureTargetId(feature) || index}-${index}`,
    status: isNew ? "added" : "existing",
    targetId: attributeTargetId(datasetType, feature),
    featureId: feature?.id || properties._featureId || "",
    geometry: feature?.geometry ? deepClone(feature.geometry) : null,
    baseProperties: properties,
    originalProperties: isNew ? {} : deepClone(displayProperties),
    properties: displayProperties,
    sourceIndex: index,
  };
}

function displayAttributeProperties(properties = {}) {
  const result = {};
  for (const [key, value] of Object.entries(properties)) {
    if (isInternalAttributeKey(key)) continue;
    result[key] = value == null ? "" : String(value);
  }
  return result;
}

function isInternalAttributeKey(key) {
  return String(key || "").startsWith("_");
}

function buildAttributeTableColumns(datasetType, rows, fieldOrder = null) {
  const ordered = fieldOrder || (datasetType === "line" ? LINE_ATTRIBUTE_FIELD_ORDER : datasetType === "depot" ? [] : STATION_ATTRIBUTE_FIELD_ORDER);
  const keys = new Set();
  ordered.forEach((key) => keys.add(key));
  if (!fieldOrder) {
    rows.forEach((row) => Object.keys(row.properties || {}).forEach((key) => keys.add(key)));
    (DERIVED_ATTRIBUTE_FIELD_ORDER[datasetType] || []).forEach((key) => keys.add(key));
  }
  return [...keys].filter(Boolean).map((key) => {
    let maxLen = String(attributeColumnLabel(key)).length;
    rows.forEach((row) => {
      const value = row.properties?.[key];
      if (value != null) maxLen = Math.max(maxLen, String(value).length);
    });
    return { key, label: attributeColumnLabel(key), wide: maxLen > 16 };
  });
}

function pickAttributeProperties(properties = {}, keys = []) {
  return keys.reduce((result, key) => {
    result[key] = properties[key] == null ? "" : String(properties[key]);
    return result;
  }, {});
}

const DEPOT_FIELD_LABELS = {
  F001: "序号",
  F002: "场站名称",
  F003: "场站地点",
  F004: "用地面积(㎡)",
  F005: "建筑面积(㎡)",
  F006: "站务房面积(㎡)",
  F007: "场站类型",
  F008: "场站功能",
  F009: "服务线路及所属公司",
  F010: "服务线路总数",
  F011: "设计停车能力(辆)",
  F012: "日间运营车辆(辆)",
  F013: "夜间停放车辆(辆)",
  F014: "夜间停放合计(辆)",
  F015: "充电桩数量(个)",
  F016: "夜间空余车位",
  F017: "用地性质",
  F018: "用地权属",
  F019: "运营状态",
  F020: "启用时间",
  F021: "停用时间",
  F022: "场站管理企业",
  F023: "站长",
  F024: "联系电话",
  F025: "备注",
  F026: "经纬度坐标",
  depot_name: "场站名称",
};

function attributeColumnLabel(key) {
  const labels = {
    line_id: "线路ID",
    dir: "方向",
    route_id: "线路编号",
    first: "首班时间",
    last: "末班时间",
    interval: "发车间隔",
    price: "票价",
    company: "所属公司",
    mode: "交通方式",
    name: "名称",
    stop_name: "站点名称",
    seq: "站序",
    lon: "经度",
    lat: "纬度",
    stop_id: "站点ID",
    geometry: "线路走向/位置",
    __deletion__: "删除对象",
    len_km: "线路长度(km)",
    directness: "直线系数",
    stop_count: "站点数量",
    avg_stop_m: "平均站距(m)",
    route_cnt: "途经线路数",
    ...DEPOT_FIELD_LABELS,
  };
  return labels[key] || key;
}

function ensureAttributeRowColumns(row, columns) {
  columns.forEach((column) => {
    if (!Object.prototype.hasOwnProperty.call(row.properties, column.key)) {
      row.properties[column.key] = "";
    }
    if (row.status === "existing" && !Object.prototype.hasOwnProperty.call(row.originalProperties, column.key)) {
      row.originalProperties[column.key] = "";
    }
  });
  return row;
}

function reorderAttributeRouteStationRow(sourceRowId, targetRowId, position = "before") {
  if (attributeTable.datasetType !== "line" || attributeTable.scope !== "route") return;
  const sourceIndex = attributeTable.rows.findIndex((row) => row.rowId === sourceRowId && row.status !== "deleted");
  const initialTargetIndex = attributeTable.rows.findIndex((row) => row.rowId === targetRowId && row.status !== "deleted");
  if (sourceIndex < 0 || initialTargetIndex < 0 || sourceIndex === initialTargetIndex) return;
  const [sourceRow] = attributeTable.rows.splice(sourceIndex, 1);
  const targetIndex = attributeTable.rows.findIndex((row) => row.rowId === targetRowId);
  const insertIndex = position === "after" ? targetIndex + 1 : targetIndex;
  attributeTable.rows.splice(insertIndex, 0, sourceRow);
  resequenceAttributeRouteRows();
}

function resequenceAttributeRouteRows(rows = attributeTable.rows) {
  if (attributeTable.datasetType !== "line" || attributeTable.scope !== "route") return;
  let sequence = 1;
  rows.forEach((row) => {
    if (row.status === "deleted") return;
    row.properties.seq = String(sequence);
    sequence += 1;
  });
}

function defaultAttributeRowGeometry(datasetType) {
  if (datasetType === "line") {
    return selectedRoute.value?.feature?.geometry ? deepClone(selectedRoute.value.feature.geometry) : null;
  }
  if (datasetType === "depot") {
    const depotFeature = attributeTable.target?.feature || selectedDepot.value?.feature;
    return depotFeature?.geometry ? deepClone(depotFeature.geometry) : null;
  }
  const station = attributeTable.station || selectedStation.value;
  return station?.feature?.geometry ? deepClone(station.feature.geometry) : null;
}

function resetAttributeTableDraft() {
  attributeTable.viewCache = {};
  attributeTable.rows = deepClone(attributeTable.originalRows);
}

function markAttributeRowTouched(row) {
  if (row.status === "existing") row.touched = true;
}

function removeAttributeTableRow(row) {
  if (row.status === "added") {
    attributeTable.rows = attributeTable.rows.filter((item) => item.rowId !== row.rowId);
    resequenceAttributeRouteRows();
    return;
  }
  row.status = "deleted";
  resequenceAttributeRouteRows();
}

function restoreAttributeTableRow(row) {
  row.status = "existing";
  resequenceAttributeRouteRows();
}

function attributeRowChanged(row) {
  if (!row) return false;
  if (row.status === "added" || row.status === "deleted") return true;
  return canonicalAttributeProperties(row.properties) !== canonicalAttributeProperties(row.originalProperties);
}

function canonicalAttributeProperties(properties = {}) {
  return JSON.stringify(Object.keys(properties).sort().reduce((result, key) => {
    result[key] = properties[key] == null ? "" : String(properties[key]);
    return result;
  }, {}));
}

function attributeRowStatusLabel(row) {
  if (row.status === "added") return "新增";
  if (row.status === "deleted") return "删除";
  if (attributeRowChanged(row)) return "修改";
  return "原始";
}

function attributeRowStateKey(row) {
  if (attributeTable.target?.isNew && row.status === "added") return "normal";
  if (row.status === "added") return "added";
  if (row.status === "deleted") return "deleted";
  if (attributeRowChanged(row)) return "modified";
  return "normal";
}

function attributeRecordTitle(row) {
  const properties = row.properties || {};
  const datasetType = row.datasetType || attributeTable.viewDatasetType || attributeTable.datasetType;
  if (datasetType === "line") return routeName(properties) || properties.name || "未命名线路";
  if (datasetType === "depot") return depotName(properties) || "未命名场站";
  return stationName(properties) || properties.stop_name || "未命名站点";
}

function toggleAttributeRouteStations() {
  if (attributeTable.datasetType !== "line" || !attributeTable.route) return;
  const currentScope = attributeTable.scope || "line";
  const nextScope = currentScope === "route" ? "line" : "route";
  attributeTable.viewCache[currentScope] = captureAttributeTableView();
  const cachedView = attributeTable.viewCache[nextScope];
  if (cachedView) {
    setAttributeTableView(cachedView);
    loadAttributeTableHistory(attributeTable.datasetType, attributeTable.target);
    return;
  }
  const nextView = buildAttributeTableView(attributeTable.datasetType, attributeTable.target, nextScope);
  if (!nextView.rows.length && nextScope !== "route") {
    ElMessage.warning("未找到该线路的完整站点属性记录");
    return;
  }
  setAttributeTableView(nextView);
  loadAttributeTableHistory(attributeTable.datasetType, attributeTable.target);
}

function applyAttributeTableChanges() {
  const changedRows = collectAttributeTableChangedRows();
  if (attributeTable.datasetType === "line") {
    const routeRows = collectAttributeTableRouteRows();
    const invalidSequence = routeRows.some((row) => {
      const sequence = Number(row.properties?.seq);
      return !Number.isInteger(sequence) || sequence <= 0;
    });
    const sequenceValues = routeRows.map((row) => String(Number(row.properties.seq)));
    if (invalidSequence || new Set(sequenceValues).size !== sequenceValues.length) {
      ElMessage.warning("站序必须是互不重复的正整数");
      return;
    }
  }
  const invalidAddedRow = changedRows.find((row) => {
    if (attributeTable.datasetType === "line") return false;
    if ((row.datasetType || attributeTable.viewDatasetType) !== "station" || row.status !== "added") return false;
    const properties = row.properties || {};
    return !String(properties.stop_id || "").trim() ||
      !String(properties.stop_name || "").trim() ||
      !Number.isFinite(Number(properties.lon)) ||
      !Number.isFinite(Number(properties.lat));
  });
  if (invalidAddedRow) {
    ElMessage.warning("请完整填写新增站点的站点ID、站点名称、经度和纬度");
    return;
  }
  const operations = attributeTableOperations(changedRows);
  if (!operations.length) {
    attributeTable.visible = false;
    return;
  }
  appendUploadOperations(attributeTable.datasetType, operations);
  attributeTable.visible = false;
  ElMessage.success(`已生成 ${operations.length} 条属性表修改`);
}

function attributeTableOperationCount() {
  const changedRows = collectAttributeTableChangedRows();
  const reorderRows = changedRows.filter(isRouteSequenceOnlyChange);
  return changedRows.length - reorderRows.length + (reorderRows.length ? 1 : 0);
}

function attributeTableOperations(changedRows) {
  const reorderRows = changedRows.filter(isRouteSequenceOnlyChange);
  const operations = changedRows
    .filter((row) => !isRouteSequenceOnlyChange(row))
    .map((row) => attributeRowOperation(row.datasetType || attributeTable.viewDatasetType || attributeTable.datasetType, row))
    .filter(Boolean);
  if (reorderRows.length) {
    operations.push(routeStationReorderOperation(reorderRows));
  }
  return operations.filter(Boolean);
}

function isRouteSequenceOnlyChange(row) {
  if (attributeTable.datasetType !== "line" || row?.datasetType !== "station" || row.status !== "existing") {
    return false;
  }
  const changedKeys = attributeRowChangedKeys(row);
  return changedKeys.length === 1 && changedKeys[0] === "seq";
}

function attributeRowChangedKeys(row) {
  const keys = new Set([...Object.keys(row?.properties || {}), ...Object.keys(row?.originalProperties || {})]);
  return [...keys].filter((key) => !DERIVED_ATTRIBUTE_FIELDS.has(key)).filter((key) => {
    const before = row?.originalProperties?.[key] == null ? "" : String(row.originalProperties[key]);
    const after = row?.properties?.[key] == null ? "" : String(row.properties[key]);
    return before !== after;
  });
}

function routeStationReorderOperation(rows) {
  const routeView = attributeTableRouteView();
  const routeProperties = attributeTable.route?.properties || {};
  const lineId = routeDataId(routeProperties);
  const direction = valueOrEmpty(routeProperties.dir || routeProperties.direction);
  if (!lineId || !routeView) return null;
  const changes = rows
    .map((row) => ({
      targetId: row.targetId || row.featureId || "",
      featureId: row.featureId || row.baseProperties?._featureId || "",
      stopId: valueOrEmpty(row.properties?.stop_id || row.baseProperties?.stop_id),
      stopName: valueOrEmpty(row.properties?.stop_name || row.baseProperties?.stop_name),
      dir: valueOrEmpty(row.properties?.dir || row.baseProperties?.dir || direction),
      fromSeq: valueOrEmpty(row.originalProperties?.seq),
      toSeq: valueOrEmpty(row.properties?.seq),
    }))
    .sort((left, right) => Number(left.toSeq) - Number(right.toSeq));
  const beforeOrder = routeStationOrderSnapshot(routeView.originalRows, true);
  const afterOrder = routeStationOrderSnapshot(routeView.rows, false);
  return {
    operationId: `${Date.now()}_station_reorder_${Math.random().toString(36).slice(2, 8)}`,
    datasetType: "station",
    type: "reorder_line_stations",
    targetId: lineId,
    title: parsePickerRoute(attributeTable.route?.name || routeName(routeProperties)).mainName || lineId,
    detail: changes.map((item) => `${item.stopName || item.stopId}：${item.fromSeq}→${item.toSeq}`).join("；"),
    changedFields: ["seq"],
    payload: {
      lineId,
      dir: direction,
      stationScope: "route",
      changes,
      beforeOrder,
      afterOrder,
      changedFields: ["seq"],
    },
  };
}

function attributeTableRouteView() {
  if (attributeTable.scope === "route") {
    return {
      scope: "route",
      rows: attributeTable.rows,
      originalRows: attributeTable.originalRows,
    };
  }
  return attributeTable.viewCache?.route || null;
}

function routeStationOrderSnapshot(rows = [], useOriginal = false) {
  return rows
    .filter((row) => row.status !== "deleted")
    .map((row) => {
      const properties = useOriginal ? row.originalProperties || row.properties || {} : row.properties || {};
      return {
        targetId: row.targetId || row.featureId || "",
        stopId: valueOrEmpty(properties.stop_id || row.baseProperties?.stop_id),
        stopName: valueOrEmpty(properties.stop_name || row.baseProperties?.stop_name),
        seq: valueOrEmpty(properties.seq),
      };
    })
    .sort((left, right) => Number(left.seq) - Number(right.seq));
}

function collectAttributeTableRouteRows() {
  const routeView = attributeTableRouteView();
  return (routeView?.rows || []).filter((row) => row.status !== "deleted");
}

function collectAttributeTableChangedRows() {
  const currentScope = attributeTable.scope || "current";
  const changedByKey = new Map();
  Object.entries(attributeTable.viewCache || {}).forEach(([scope, view]) => {
    if (scope === currentScope) return;
    collectAttributeRowsFromView(view, changedByKey);
  });
  collectAttributeRowsFromView({ rows: attributeTable.rows }, changedByKey);
  return [...changedByKey.values()];
}

function collectAttributeRowsFromView(view, changedByKey) {
  const rows = Array.isArray(view?.rows) ? view.rows : [];
  rows.forEach((row) => {
    if (!attributeRowChanged(row)) return;
    changedByKey.set(attributeRowChangeKey(row), row);
  });
}

function attributeRowChangeKey(row) {
  return `${row.datasetType || attributeTable.viewDatasetType || attributeTable.datasetType}:${row.targetId || row.featureId || row.rowId || ""}`;
}

function attributeRowOperation(datasetType, row) {
  const feature = attributeRowFeature(row);
  const targetId = row.targetId || attributeTargetId(datasetType, feature);
  const title = attributeOperationTitle(datasetType, row, feature);
  const operationId = `${Date.now()}_${datasetType}_${row.status}_${Math.random().toString(36).slice(2, 8)}`;
  const isRouteMembershipOperation = attributeTable.datasetType === "line" && datasetType === "station";
  const stationScope = datasetType === "station" && isRouteMembershipOperation ? "route" : "";
  if (isRouteMembershipOperation && row.status === "added") return null;
  if (row.status === "added") {
    const changedFields = ["geometry", ...Object.keys(feature.properties || {}).filter((key) => !key.startsWith("_"))];
    return {
      operationId,
      datasetType,
      type: `add_${datasetType}_from_table`,
      targetId,
      title,
      detail: "属性表新增整行",
      changedFields,
      payload: { feature, changedFields, ...(stationScope ? { stationScope } : {}) },
    };
  }
  if (row.status === "deleted") {
    return {
      operationId,
      datasetType,
      type: `delete_${datasetType}_from_table`,
      targetId,
      title,
      detail: isRouteMembershipOperation ? "线路移除站点" : "属性表删除整行",
      changedFields: ["__deletion__"],
      payload: {
        targetId,
        feature,
        featureId: row.featureId || "",
        lineKey: datasetType === "line" ? row.baseProperties?._lineKey || "" : "",
        stationKey: datasetType === "station" ? row.baseProperties?._stationKey || row.properties?.stop_id || "" : "",
        depotKey: datasetType === "depot" ? row.baseProperties?._depotKey || "" : "",
        ...(stationScope ? { stationScope } : {}),
      },
    };
  }
  const changedFields = attributeRowChangedKeys(row);
  return {
    operationId,
    datasetType,
    type: `replace_${datasetType}_from_table`,
    targetId,
    title,
    detail: isRouteMembershipOperation
      ? `线路站点编组调整：${changedAttributeLabels(row).join("、") || "站序"}`
      : `属性表修改：${changedAttributeLabels(row).join("、") || "属性"}`,
    changedFields,
    payload: { targetId, feature, changedFields, ...(stationScope ? { stationScope } : {}) },
  };
}

function attributeRowFeature(row) {
  const id = row.featureId || row.targetId || row.rowId;
  const datasetType = row.datasetType || attributeTable.viewDatasetType || attributeTable.datasetType;
  const properties = {
    ...(row.baseProperties || {}),
    ...Object.fromEntries(Object.entries(row.properties || {}).map(([key, value]) => [key, value == null ? "" : String(value)])),
    _featureId: row.baseProperties?._featureId || id,
  };
  if (datasetType === "line") {
    properties._lineKey = properties._lineKey || row.baseProperties?._lineKey || id;
  }
  if (datasetType === "station") {
    properties._stationKey = properties._stationKey || row.baseProperties?._stationKey || properties.stop_id || id;
    properties._routeStopKey = properties._routeStopKey || routeStopFeatureKey(properties) || id;
  }
  if (datasetType === "depot") {
    properties._depotKey = properties._depotKey || row.baseProperties?._depotKey || id;
  }
  DERIVED_ATTRIBUTE_FIELDS.forEach((key) => delete properties[key]);
  let geometry = row.geometry ? deepClone(row.geometry) : defaultAttributeRowGeometry(datasetType);
  if (datasetType === "station") {
    const lon = Number(properties.lon);
    const lat = Number(properties.lat);
    if (Number.isFinite(lon) && Number.isFinite(lat)) {
      geometry = { type: "Point", coordinates: [lon, lat] };
    }
  }
  return {
    type: "Feature",
    id,
    geometry,
    properties,
  };
}

function changedAttributeLabels(row) {
  return attributeRowChangedKeys(row).map(attributeColumnLabel).slice(0, 5);
}

function attributeOperationTitle(datasetType, row, feature) {
  if (datasetType === "line") return routeName(feature.properties) || row.properties?.name || "未命名线路";
  if (datasetType === "depot") return depotName(feature.properties) || depotName(row.properties) || "未命名场站";
  return stationName(feature.properties) || row.properties?.stop_name || "未命名站点";
}

function attributeTargetId(datasetType, feature) {
  const properties = feature?.properties || {};
  if (datasetType === "station") {
    return properties.stop_id || properties._stationKey || feature?.id || properties._featureId || routeStopFeatureKey(properties) || properties.stop_name || "";
  }
  if (datasetType === "depot") {
    return feature?.id || properties._featureId || properties._depotKey || depotName(properties) || "";
  }
  return feature?.id || properties._featureId || [properties.line_id, properties.dir, properties.route_id].filter(Boolean).join("|") || properties.name || "";
}

function routeStopFeatureKey(properties = {}) {
  return [properties.line_id, properties.dir, properties.stop_id, properties.seq]
    .map((value) => valueOrEmpty(value))
    .filter(Boolean)
    .join("|");
}

function deepClone(value) {
  if (value === undefined || value === null) return value;
  try {
    // structuredClone 比 JSON 往返快且不丢 undefined；toRaw 剥掉响应式代理（代理无法结构化克隆）
    return structuredClone(toRaw(value));
  } catch {
    return JSON.parse(JSON.stringify(value));
  }
}

function appendUploadOperations(datasetType, operations) {
  const existingIds = new Set(activeEditOperations.value.map((operation) => operation.operationId));
  const acceptedOperations = [];
  let stationTouched = false;
  for (const operation of operations) {
    if (!operation?.operationId || existingIds.has(operation.operationId)) continue;
    const operationDatasetType = ["line", "station", "depot"].includes(operation.datasetType)
      ? operation.datasetType
      : datasetType;
    // 冻结后 Vue 跳过深代理：操作对象入队后只读，只有队列数组本身保持响应式
    editOperations[operationDatasetType].push(Object.freeze(operation));
    existingIds.add(operation.operationId);
    acceptedOperations.push({ datasetType: operationDatasetType, operation });
    stationTouched ||= operationDatasetType === "station";
  }
  const touchedDatasets = [...new Set(acceptedOperations.map((item) => item.datasetType))];
  mutateRealDataCollections(() => {
    if (acceptedOperations.length <= MAX_IMMEDIATE_PREVIEW_OPERATIONS) {
      acceptedOperations.forEach(({ datasetType: operationDatasetType, operation }) => {
        applyUploadOperationPreview(operationDatasetType, operation);
      });
      // routeStops 尚未水合（懒加载在途）时集合近乎为空，此刻派生会把站点图层清空；
      // 跳过派生，待 hydrateRouteStops 重放 pending 操作后统一派生
      if (stationTouched && !isRouteStopsDeferred(lastNormalizedData)) {
        realDataAllCollections.stations = deriveStationsFromRouteStops(realDataAllCollections.routeStops);
      }
    } else {
      ElMessage.info(`本次修改共 ${acceptedOperations.length} 条，数据量较大，地图将在提交后统一刷新`);
    }
  }, { datasets: touchedDatasets.length ? touchedDatasets : undefined });
  if (stationTouched) {
    syncSelectedStationWithCurrentData();
  }
}

function applyUploadOperationPreview(datasetType, operation) {
  const collection = datasetType === "station" ? realDataAllCollections.routeStops : collectionForDataset(datasetType, "all");
  const features = Array.isArray(collection?.features) ? collection.features : [];
  const targetId = operation.targetId || operation.payload?.targetId;
  const feature = operation.payload?.feature;
  const routeScopedStation = datasetType === "station" && operation.payload?.stationScope === "route";
  if (datasetType === "station" && operation.type === "reorder_line_stations") {
    for (const change of Array.isArray(operation.payload?.changes) ? operation.payload.changes : []) {
      const targetFeature = features.find((item) => stationPreviewFeatureMatches(item, change.targetId || change.featureId, true));
      if (!targetFeature) continue;
      targetFeature.properties = {
        ...(targetFeature.properties || {}),
        seq: String(change.toSeq),
      };
    }
    return;
  }
  if (operation.type?.startsWith("add_")) {
    if (feature) features.push(normalizePreviewFeature(datasetType, feature, features.length));
    return;
  }
  if (datasetType === "station") {
    const matchingIndexes = features
      .map((item, index) => (stationPreviewFeatureMatches(item, targetId, routeScopedStation) ? index : -1))
      .filter((index) => index >= 0);
    if (operation.type?.startsWith("delete_")) {
      matchingIndexes.reverse().forEach((index) => features.splice(index, 1));
    } else if (operation.type?.startsWith("replace_") && feature) {
      if (isPhysicalStationReplacement(targetId, feature)) {
        matchingIndexes.forEach((index) => applyPhysicalStationPreview(features[index], feature));
      } else if (matchingIndexes.length) {
        const index = matchingIndexes[0];
        features.splice(index, 1, normalizePreviewFeature(datasetType, feature, index));
      }
    }
    return;
  }
  const index = features.findIndex((item) => uploadPreviewFeatureKey(item, datasetType) === targetId || featureTargetId(item) === targetId);
  if (index < 0) return;
  if (operation.type?.startsWith("delete_")) {
    features.splice(index, 1);
  } else if (operation.type?.startsWith("replace_") && feature) {
    features.splice(index, 1, normalizePreviewFeature(datasetType, feature, index));
  }
}

function stationPreviewFeatureMatches(feature, targetId, routeScoped = false) {
  if (!targetId) return false;
  const properties = feature?.properties || {};
  const exactValues = [
    properties._featureId,
    properties._routeStopKey,
    feature?.id,
    uploadPreviewFeatureKey(feature, "station"),
  ];
  if (exactValues.some((value) => valueOrEmpty(value) === valueOrEmpty(targetId))) return true;
  if (routeScoped) return false;
  return [properties.stop_id, properties._stationKey]
    .some((value) => valueOrEmpty(value) === valueOrEmpty(targetId));
}

function isPhysicalStationReplacement(targetId, feature) {
  const properties = feature?.properties || {};
  const normalizedTargetId = valueOrEmpty(targetId);
  return [properties._stationKey, properties.stop_id]
    .some((value) => valueOrEmpty(value) === normalizedTargetId);
}

function applyPhysicalStationPreview(targetFeature, replacementFeature) {
  const targetProperties = targetFeature?.properties || (targetFeature.properties = {});
  const replacementProperties = replacementFeature?.properties || {};
  if (replacementFeature?.geometry) {
    targetFeature.geometry = deepClone(replacementFeature.geometry);
  }
  ["stop_id", "stop_name", "lon", "lat"].forEach((key) => {
    if (Object.prototype.hasOwnProperty.call(replacementProperties, key)) {
      targetProperties[key] = replacementProperties[key];
    }
  });
  targetProperties._stationKey = valueOrEmpty(replacementProperties.stop_id || targetProperties.stop_id || targetProperties._stationKey);
}

function normalizePreviewFeature(datasetType, feature, index = 0) {
  if (datasetType === "line") return normalizeLineFeature(feature, index);
  if (datasetType === "depot") return normalizeDepotFeature(feature, index);
  return normalizeRouteStopFeature(feature, index);
}

function uploadPreviewFeatureKey(feature, datasetType) {
  const properties = feature?.properties || {};
  if (datasetType === "line") return [properties.line_id, properties.dir, properties.route_id].filter(Boolean).join("|") || properties._featureId || feature?.id || "";
  if (datasetType === "depot") return depotName(properties) || properties._featureId || feature?.id || properties._depotKey || "";
  return [properties.line_id || "", properties.dir || "", properties.stop_id || "", properties.seq || ""].filter(Boolean).join("|");
}

function deriveStationsFromRouteStops(routeStops) {
  const stationMap = new Map();
  for (const feature of routeStops?.features || []) {
    const properties = feature.properties || {};
    const key = properties.stop_id || `${stationName(properties)}-${JSON.stringify(feature.geometry?.coordinates || [])}`;
    if (!key || stationMap.has(key)) continue;
    stationMap.set(key, normalizeStationFeature({
      type: "Feature",
      id: key,
      geometry: feature.geometry,
      properties: {
        _featureId: key,
        stop_id: properties.stop_id,
        stop_name: properties.stop_name,
        line_id: properties.line_id,
        dir: properties.dir,
      },
    }, stationMap.size));
  }
  return {
    type: "FeatureCollection",
    features: [...stationMap.values()],
  };
}

function closeLineRoutePicker() {
  lineRoutePicker.visible = false;
  lineRoutePicker.routes = [];
  lineRoutePicker.mode = "view";
  lineRoutePicker.lngLat = null;
  lineRoutePicker.point = null;
}

// popover 开合状态已内聚到 MapControlsToolbar，父级通过 ref 统一关闭
function closeStylePopover() {
  mapToolbarRef.value?.closePopovers?.();
}

function closeRangePopover() {
  mapToolbarRef.value?.closePopovers?.();
}

function handleToolbarBeforeOpen(which) {
  closeSearchResults();
  closeEditActionMenu();
  closeLineRoutePicker();
  if (which === "range" && !displayRangeOptions.value.length && !isLoadingDisplayRanges.value) {
    loadDisplayRanges({ force: Boolean(displayRangeError.value) });
  }
}

function selectDisplayRange(rangeName) {
  const nextRange = String(rangeName || "").trim();
  if (!nextRange) return;
  if (nextRange === selectedDisplayRange.value) return;
  selectedDisplayRange.value = nextRange;
}

function closeTransientSurfaces() {
  closeSearchResults();
  closeStylePopover();
  closeRangePopover();
  closeEditActionMenu();
  closeLineRoutePicker();
}

function dialogConfigForAction(action, datasetType, target, lngLat) {
  const fields = [];
  const form = { name: "", headway: "", stations: "" };
  if (action.includes("rename") || action.includes("add")) {
    fields.push("name");
    form.name = target ? editTargetName(datasetType, target) : "";
  }
  if (action === "update_line_headway") {
    fields.push("headway");
    form.headway = firstAvailableValue(target?.properties || {}, ["headway", "avg_headway", "interval", "avg_interval"]);
  }
  if (action === "update_line_stations") {
    fields.push("stations");
    form.stations = firstAvailableValue(target?.properties || {}, ["station_list_edit", "stations", "stop_names", "途径站点"]);
  }
  return {
    title: actionTitle(action),
    action,
    datasetType,
    target,
    lngLat,
    fields,
    form,
  };
}

function confirmEditDialog() {
  const payload = {};
  if (editDialog.fields.includes("name")) {
    if (!editDialog.form.name) {
      ElMessage.warning("请填写名称");
      return;
    }
    payload.name = editDialog.form.name;
  }
  if (editDialog.fields.includes("headway")) payload.headway = editDialog.form.headway;
  if (editDialog.fields.includes("stations")) payload.stations = editDialog.form.stations;
  if (Array.isArray(editDialog.lngLat)) {
    payload.lng = editDialog.lngLat[0];
    payload.lat = editDialog.lngLat[1];
  }
  addEditOperation(editDialog.datasetType, editDialog.action, editDialog.target, payload, editDialog.lngLat);
  applyLocalEdit(editDialog.datasetType, editDialog.action, editDialog.target, payload);
  editDialog.visible = false;
}

function commitMoveOperation(datasetType, target, lngLat) {
  const payload = { lng: lngLat[0], lat: lngLat[1] };
  const action = datasetType === "depot" ? "move_depot" : "move_station";
  addEditOperation(datasetType, action, target, payload, lngLat);
  applyLocalEdit(datasetType, action, target, payload);
}

function addEditOperation(datasetType, type, target, payload = {}, lngLat = null) {
  if (!datasetType || !editOperations[datasetType]) return;
  const operationId = `${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
  const targetId = featureTargetId(target);
  const title = operationTitle(datasetType, type, target, payload);
  const detail = operationDetail(type, target, payload, lngLat);
  const changedFields = changedFieldsForEditOperation(datasetType, type, target);
  editOperations[datasetType].push(Object.freeze({
    operationId,
    datasetType,
    type,
    targetId,
    title,
    detail,
    changedFields,
    payload: {
      ...payload,
      changedFields,
      featureId: target?.properties?._featureId || target?.id || "",
      stationKey: target?.properties?._stationKey || "",
      lineKey: target?.properties?._lineKey || "",
      depotKey: target?.properties?._depotKey || "",
      ...(datasetType === "station" ? { stationScope: "physical" } : {}),
    },
  }));
}

function changedFieldsForEditOperation(datasetType, type, target) {
  if (type.startsWith("add_")) return ["geometry", namePropertyForDataset(datasetType, target?.properties || {})];
  if (type.startsWith("delete_")) return ["__deletion__"];
  if (type.startsWith("rename_")) return [namePropertyForDataset(datasetType, target?.properties || {})];
  if (type.startsWith("move_")) return datasetType === "station" ? ["geometry", "lon", "lat"] : ["geometry"];
  if (type === "update_line_headway") return ["interval"];
  if (type === "update_line_stations") return ["station_list_edit"];
  return [];
}

function applyLocalEdit(datasetType, action, target, payload) {
  if (action.startsWith("add_")) {
    addLocalFeature(datasetType, payload);
    return;
  }
  if (action.startsWith("rename_")) {
    updateLocalFeatureProperties(datasetType, target, (properties) => {
      properties[namePropertyForDataset(datasetType, properties)] = payload.name;
    });
    return;
  }
  if (action.startsWith("move_")) {
    updateLocalFeature(datasetType, target, (feature) => {
      feature.geometry = { type: "Point", coordinates: [Number(payload.lng), Number(payload.lat)] };
    });
    return;
  }
  if (action === "update_line_headway") {
    updateLocalFeatureProperties(datasetType, target, (properties) => {
      properties.headway = payload.headway || "暂无";
    });
    return;
  }
  if (action === "update_line_stations") {
    updateLocalFeatureProperties(datasetType, target, (properties) => {
      properties.station_list_edit = payload.stations || "";
    });
  }
}

function applyLocalDelete(datasetType, target) {
  const collection = collectionForDataset(datasetType, "all");
  const targetId = featureTargetId(target);
  const removedFeature = collection.features.find((feature) => featureTargetId(feature) === targetId);
  mutateRealDataCollections(() => {
    collection.features = collection.features.filter((feature) => featureTargetId(feature) !== targetId);
  }, {
    datasets: [datasetType],
    clearSelection: true,
    sourceDiffs: removedFeature?.id !== undefined && removedFeature?.id !== null
      ? { [datasetType]: () => ({ remove: [removedFeature.id] }) }
      : null,
  });
  clearSelection();
}

function addLocalFeature(datasetType, payload) {
  const collection = collectionForDataset(datasetType, "all");
  const featureId = `${datasetType}_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`;
  const properties = {
    _featureId: featureId,
    [datasetType === "station" ? "_stationKey" : "_depotKey"]: featureId,
    [datasetType === "station" ? "stop_name" : "depot_name"]: payload.name || "未命名",
  };
  if (datasetType === "station") {
    properties.stop_id = featureId;
    properties.line_id = "";
    properties.dir = "";
    properties.seq = "";
    properties.lon = Number(payload.lng);
    properties.lat = Number(payload.lat);
  }
  const feature = {
    type: "Feature",
    id: featureId,
    geometry: { type: "Point", coordinates: [Number(payload.lng), Number(payload.lat)] },
    properties,
  };
  mutateRealDataCollections(() => {
    collection.features.push(feature);
  }, {
    datasets: [datasetType],
    sourceDiffs: { [datasetType]: () => ({ add: [plainGeoJsonFeature(feature)] }) },
  });
}

function updateLocalFeatureProperties(datasetType, target, updater) {
  updateLocalFeature(datasetType, target, (feature) => updater(feature.properties || (feature.properties = {})));
}

function updateLocalFeature(datasetType, target, updater) {
  const collection = collectionForDataset(datasetType, "all");
  const targetId = featureTargetId(target);
  const feature = collection.features.find((item) => featureTargetId(item) === targetId);
  if (!feature) return;
  mutateRealDataCollections(() => updater(feature), {
    datasets: [datasetType],
    sourceDiffs: { [datasetType]: () => featureUpdateDiff(feature) },
  });
}

function collectionForDataset(datasetType, scope = "visible") {
  const source = scope === "all" ? realDataAllCollections : realDataCollections;
  if (datasetType === "station") return source.stations;
  if (datasetType === "line") return source.lines;
  if (datasetType === "depot") return source.depots;
  return emptyFeatureCollection();
}

function featureTargetId(feature) {
  const properties = feature?.properties || {};
  return String(properties._featureId || feature?.id || properties._stationKey || properties._lineKey || properties._depotKey || properties.stop_id || properties.line_id || properties.name || "");
}

function editTargetName(datasetType, feature) {
  const properties = feature?.properties || {};
  if (datasetType === "line") return routeName(properties) || "未命名线路";
  if (datasetType === "depot") return depotName(properties);
  return stationName(properties);
}

function namePropertyForDataset(datasetType, properties = {}) {
  const candidates = datasetType === "line" ? ["name"] : datasetType === "depot" ? ["depot_name", "name", "场站名称"] : ["stop_name"];
  return candidates.find((key) => Object.prototype.hasOwnProperty.call(properties, key)) || candidates[0];
}

function actionTitle(action) {
  const labels = {
    add_station: "新增站点",
    rename_station: "修改站点名称",
    add_depot: "新增场站",
    rename_depot: "修改场站名称",
    rename_line: "修改线路名称",
    update_line_headway: "修改发车间隔",
    update_line_stations: "修改途径站点",
  };
  return labels[action] || "编辑数据";
}

function editFieldHint(field) {
  if (field !== "name") return "";
  if (editDialog.action?.startsWith("add_")) return `请输入新${datasetTypeLabel(editDialog.datasetType)}名称。`;
  return "当前名称会被替换为新名称。";
}

function operationLabel(type) {
  const value = String(type || "");
  if (value === "reorder_line_stations") return "调整站序";
  if (value.startsWith("add_")) return "新增";
  if (value.startsWith("rename_")) return "改名";
  if (value.startsWith("move_")) return "位置";
  if (value.startsWith("delete_")) return "删除";
  if (value.includes("headway")) return "间隔";
  if (value.includes("stations")) return "站点";
  return "修改";
}

function operationKind(type) {
  const value = String(type || "");
  if (value.startsWith("add_")) return "is-add";
  if (value.startsWith("delete_")) return "is-delete";
  return "is-modify";
}

function datasetTypeLabel(datasetType) {
  if (datasetType === "station") return "站点";
  if (datasetType === "line") return "线路";
  if (datasetType === "depot") return "场站";
  return "全部数据";
}

function historyOperationRows(operations, datasetType) {
  return operations
    .filter((operation) => operation?.datasetType === datasetType)
    .map((operation, index) => ({
      key: operation.operationId || `${datasetType}-${index}`,
      action: operationLabel(operation.type),
      target: operation.title || firstHistoryOperationText(operation, "targetName", "name", "lineName", "stationName", "depotName", "targetId") || "未命名对象",
      detail: operation.detail || historyOperationPayloadText(operation) || "已修改",
      username: operation.username || historyDetails.record?.username || "未知用户",
      committedAt: operation.committedAt || historyDetails.record?.committedAt || 0,
      evidenceImages: Array.isArray(operation.evidenceImages)
        ? operation.evidenceImages
        : Array.isArray(historyDetails.record?.evidenceImages)
          ? historyDetails.record.evidenceImages
          : [],
    }));
}

function showHistoryDetails(record) {
  historyDetails.record = record || null;
  historyDetails.visible = Boolean(record);
}

function closeHistoryDetails() {
  historyDetails.visible = false;
  historyDetails.record = null;
}

function firstHistoryOperationText(operation, ...keys) {
  const payload = operation?.payload && typeof operation.payload === "object" ? operation.payload : {};
  for (const key of keys) {
    const value = operation?.[key] ?? payload?.[key];
    if (value !== undefined && value !== null && String(value).trim()) return String(value).trim();
  }
  return "";
}

function historyOperationPayloadText(operation) {
  const payload = operation?.payload && typeof operation.payload === "object" ? operation.payload : {};
  if (operation?.type === "reorder_line_stations") {
    const changes = Array.isArray(payload.changes) ? payload.changes : [];
    return changes.map((item) => `${valueOrEmpty(item.stopName || item.stopId) || "站点"}：${valueOrEmpty(item.fromSeq) || "—"}→${valueOrEmpty(item.toSeq) || "—"}`).join("；");
  }
  if (payload.name) return `名称改为：${payload.name}`;
  if (payload.headway) return `发车间隔改为：${payload.headway}`;
  if (payload.stations) return "已更新途径站点";
  if (Number.isFinite(Number(payload.lng)) && Number.isFinite(Number(payload.lat))) return `位置：${formatLngLat(payload.lng, payload.lat)}`;
  return "";
}

function historyRecordTitle(record) {
  if (!record) return "未知版本";
  if (record.changeType === "base") return "原始 shp 数据";
  const message = String(record.message || "").trim();
  if (message) return message;
  return `${datasetTypeLabel(record.datasetType)}版本${record.revision ? ` 第 ${record.revision} 次保存` : ""}`;
}

function formatHistoryTime(value) {
  const timestamp = Number(value);
  if (!Number.isFinite(timestamp) || timestamp <= 0) return "初始版本";
  return new Date(timestamp).toLocaleString("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function operationTitle(datasetType, type, target, payload) {
  if (type.startsWith("add_")) return payload.name || "新增对象";
  return editTargetName(datasetType, target);
}

function operationDetail(type, target, payload, lngLat) {
  if (type.startsWith("add_")) return `在 ${formatLngLat(payload.lng, payload.lat)} 新增`;
  if (type.startsWith("rename_")) return `名称改为：${payload.name}`;
  if (type.startsWith("move_")) return `位置改为：${formatLngLat(payload.lng, payload.lat)}`;
  if (type.startsWith("delete_station")) return "删除站点，并记录为不再停靠该站点";
  if (type.startsWith("delete_")) return "删除当前对象";
  if (type === "update_line_headway") return `发车间隔改为：${payload.headway || "暂无"}`;
  if (type === "update_line_stations") return "已更新途径站点文本";
  return Array.isArray(lngLat) ? formatLngLat(lngLat[0], lngLat[1]) : "已修改";
}

function formatLngLat(lng, lat) {
  const x = Number(lng);
  const y = Number(lat);
  if (!Number.isFinite(x) || !Number.isFinite(y)) return "当前位置";
  return `${x.toFixed(6)}, ${y.toFixed(6)}`;
}

function selectSearchResult(result) {
  if (!result) return;
  closeStylePopover();
  closeEditActionMenu();
  closeLineRoutePicker();
  pendingAddDataset.value = "";
  resetMapCanvasCursor();
  if (result.type === "station") {
    selectStation(result.feature);
    focusFeature(result.feature, { pointZoom: 15 });
    return;
  }
  if (result.type === "depot") {
    selectDepot(result.feature);
    focusFeature(result.feature, { pointZoom: 15 });
    return;
  }
  if (!historyPreview.visible && activeKey.value !== "update_line") {
    activeKey.value = "overview";
  }
  selectRouteFeature(result.feature);
  focusFeature(result.feature, { minZoom: 12, maxZoom: 15 });
}

function closeSearchResults() {
  searchBoxRef.value?.close?.();
}

function handleSearchBoxFocus() {
  closeStylePopover();
  closeEditActionMenu();
  closeLineRoutePicker();
}

function selectStation(feature) {
  const properties = feature?.properties || {};
  const selectedFeature = {
    type: "Feature",
    id: feature?.id,
    geometry: feature?.geometry,
    properties: {
      ...properties,
      _stationKey: String(properties._stationKey || stationFeatureKey(feature)),
    },
  };
  const routeNames = routesForStation(selectedFeature);
  const enrichedRoutes = routeNames.map((name) => {
    const matched = lineLookup.byName.get(name);
    const passengerFlow = routePassengerFlowValue(matched?.feature?.properties);
    return {
      name,
      passengerFlow,
      feature: matched?.feature,
    };
  }).sort(compareStationRoutesByPassenger);
  selectedStation.value = {
    id: selectedFeature.properties._stationKey,
    name: stationName(selectedFeature.properties),
    routes: enrichedRoutes,
    feature: selectedFeature,
  };
  selectedRoute.value = null;
  selectedDepot.value = null;
  closeLineRoutePicker();
  clearSelectedLineLayer();
  updateSelectedDepotLayer(null);
  updateStationSelectionLayers();
}

function currentStationFeature(target) {
  const station = target?.station || target;
  const feature = station?.feature || station;
  const properties = feature?.properties || {};
  const stopId = valueOrEmpty(properties.stop_id || properties._stationKey || station?.id);
  const name = valueOrEmpty(properties.stop_name || properties.name || station?.name);
  const features = Array.isArray(realDataAllCollections.stations?.features)
    ? realDataAllCollections.stations.features
    : [];
  if (stopId) {
    const matches = features.filter((item) => {
      const itemProperties = item?.properties || {};
      return valueOrEmpty(itemProperties.stop_id || itemProperties._stationKey) === stopId;
    });
    if (matches.length) {
      return matches.find((item) => {
        const itemProperties = item?.properties || {};
        return name && valueOrEmpty(itemProperties.stop_name || itemProperties.name) === name;
      }) || matches[0];
    }
  }
  const identifiers = new Set([
    feature?.id,
    properties._featureId,
  ].map(valueOrEmpty).filter(Boolean));
  if (!identifiers.size || properties._attributeNew === true) return null;
  return features.find((item) => {
    const itemProperties = item?.properties || {};
    return [
      itemProperties.stop_id,
      itemProperties._stationKey,
      item?.id,
      itemProperties._featureId,
    ].map(valueOrEmpty).some((value) => value && identifiers.has(value));
  }) || null;
}

function syncSelectedStationWithCurrentData() {
  if (!selectedStation.value) return;
  const feature = currentStationFeature(selectedStation.value);
  if (feature) {
    selectStation(feature);
    return;
  }
  clearSelectedStation();
}

function selectDepot(feature) {
  if (!feature) return;
  const properties = feature.properties || {};
  const selectedFeature = {
    type: "Feature",
    id: feature.id,
    geometry: feature.geometry ? deepClone(feature.geometry) : null,
    properties: {
      ...properties,
      _depotKey: String(properties._depotKey || depotFeatureKey(feature)),
    },
  };
  selectedDepot.value = {
    id: selectedFeature.properties._depotKey,
    name: depotName(selectedFeature.properties),
    feature: selectedFeature,
    properties: selectedFeature.properties,
  };
  selectedStation.value = null;
  selectedRoute.value = null;
  closeLineRoutePicker();
  clearSelectedLineLayer();
  updateStationSelectionLayers();
  updateSelectedDepotLayer(selectedFeature);
}

function stationRouteOptions(station = selectedStation.value) {
  const directRouteOptions = stationRouteOptionsFromRouteStops(station);
  if (directRouteOptions.length) return directRouteOptions;
  const routes = Array.isArray(station?.routes) ? station.routes : [];
  return routes
    .map((route) => {
      if (route?.feature) {
        return routeOptionFromProperties(route.feature.properties || {}, route.feature);
      }
      const matched = route?.name ? lineLookup.byName.get(route.name) : null;
      return matched ? routeOptionFromProperties(matched.feature.properties || {}, matched.feature) : null;
    })
    .filter(Boolean);
}

function stationRouteOptionsFromRouteStops(station = selectedStation.value) {
  if (!station) return [];
  const stationFeature = station.feature || station;
  const stationProperties = stationFeature?.properties || {};
  const stationId = valueOrEmpty(station.id || stationProperties.stop_id || stationProperties._stationKey);
  const stationLabel = station.name || stationName(stationProperties);
  // 索引双通道命中（stop_id/_stationKey + 名称），按 sourceIndex 合并去重以保持源顺序遍历语义
  const candidateEntries = new Map();
  if (stationId) {
    for (const entry of stationRouteLookup.byStopKey.get(stationId) || []) candidateEntries.set(entry.sourceIndex, entry);
  }
  if (stationLabel) {
    for (const entry of stationRouteLookup.byStopName.get(stationLabel) || []) candidateEntries.set(entry.sourceIndex, entry);
  }
  const candidates = [...candidateEntries.values()].sort((left, right) => left.sourceIndex - right.sourceIndex);
  const options = [];
  const seen = new Set();
  for (const { feature } of candidates) {
    const properties = feature?.properties || {};
    const routeId = routeDataId(properties);
    const routeLabel = routeName(properties);
    const key = routeId || routeLabel;
    if (!key || seen.has(key)) continue;
    seen.add(key);
    const matchedLine = lineItemMatchingRoute(properties);
    options.push(routeOptionFromProperties(matchedLine?.feature?.properties || properties, matchedLine?.feature || null));
  }
  return options;
}

function selectRouteFeature(feature) {
  if (!feature) return;
  const fullFeature = fullLineFeatureFor(feature) || feature;
  selectedRoute.value = routeOptionFromProperties(fullFeature.properties, fullFeature);
  selectedStation.value = null;
  selectedDepot.value = null;
  closeLineRoutePicker();
  updateSelectedDepotLayer(null);
  updateStationSelectionLayers();
  updateSelectedLineLayer(fullFeature);
}

function selectRouteFromStation(route) {
  if (!route?.feature) return;
  selectRouteFeature(route.feature);
  focusFeature(route.feature, { minZoom: 12, maxZoom: 15 });
}

function clearSelectedStation() {
  selectedStation.value = null;
  updateStationSelectionLayers();
}

function clearSelection() {
  clearSelectionState();
  updateStationSelectionLayers();
  clearSelectedLineLayer();
  updateSelectedDepotLayer(null);
  closeEditActionMenu();
}

function clearSelectionState() {
  selectedStation.value = null;
  selectedRoute.value = null;
  selectedDepot.value = null;
  closeLineRoutePicker();
  pendingMoveTarget.value = null;
  pendingAddDataset.value = "";
  // 选中线路时底图线网/站点/站名整体隐藏，取消选中必须同步恢复
  updateStationSelectionLayers();
  updateBaseLineOpacity();
  // 灰底的内容/宽度/站点可见性都随选中态变化，清空选中同样要重算
  updateBaseNetworkLayers();
}

function updateStationSelectionLayers() {
  const map = MapRef.value?.map;
  if (!map) return;
  const source = map.getSource(SOURCE_SELECTED_STATION);
  if (source?.setData) {
    source.setData(selectedStation.value?.feature ? { type: "FeatureCollection", features: [selectedStation.value.feature] } : emptyFeatureCollection());
  }
  updateSelectedRouteStationsLayer();
  if (map.getLayer(LAYER_STATIONS)) {
    map.setPaintProperty(LAYER_STATIONS, "icon-opacity", stationOpacityPaint());
  }
  // 选中线路后：底图站名一并隐藏（线路自身站名由 LAYER_ROUTE_STATION_LABELS 高亮图层绘制）
  if (map.getLayer(LAYER_STATION_LABELS)) {
    map.setPaintProperty(
      LAYER_STATION_LABELS,
      "text-opacity",
      selectedRoute.value && activeKey.value !== "update_line"
        ? 0
        : ["interpolate", ["linear"], ["zoom"], 8, 0.72, 11, 0.92, 14, 1],
    );
  }
}

function selectedRouteStationsActive() {
  return Boolean(selectedRoute.value) && activeKey.value !== "update_line";
}

function updateSelectedRouteStationsLayer() {
  const map = MapRef.value?.map;
  const source = map?.getSource(SOURCE_SELECTED_ROUTE_STATIONS);
  if (!source?.setData) return;
  if (!selectedRouteStationsActive()) {
    source.setData(emptyFeatureCollection());
    return;
  }
  source.setData({ type: "FeatureCollection", features: selectedRouteStationFeatures() });
}

// 选中行政区时，选中线路自身的站点按物理站键分成区内（橙色高亮）与区外（灰色），
// 区内集合复用 worker 已裁剪好的 realDataCollections.routeStops，无需逐点多边形测试
let inRegionStopKeyCache = { revision: -1, keys: new Set() };

function inRegionRouteStopKeys() {
  const revision = realDataCollectionsRevision.value;
  if (inRegionStopKeyCache.revision === revision) return inRegionStopKeyCache.keys;
  const keys = new Set();
  if (selectedDisplayRange.value !== DISPLAY_RANGE_ALL) {
    for (const feature of collectionFeatures(realDataCollections.routeStops)) {
      keys.add(routeStopPhysicalKey(feature));
    }
  }
  inRegionStopKeyCache = { revision, keys };
  return keys;
}

function selectedRouteStationFeatures() {
  if (!selectedRouteStationsActive()) return [];
  const districtActive = selectedDisplayRange.value !== DISPLAY_RANGE_ALL;
  const inRegionKeys = districtActive ? inRegionRouteStopKeys() : null;
  const routeStops = routeStopFeaturesForRoute(selectedRoute.value.properties, selectedRoute.value)
    .map(({ feature }, index) => normalizeRouteStationHighlightFeature(feature, index, inRegionKeys));
  const sourceFeatures = routeStops.length ? routeStops : routeStationSourceFallbackFeatures(selectedRoute.value);
  const seen = new Set();
  const result = [];
  for (const feature of sourceFeatures) {
    const key = String(feature?.properties?._stationKey || stationFeatureKey(feature, result.length) || "");
    if (!key || seen.has(key)) continue;
    seen.add(key);
    result.push(feature);
  }
  return result;
}

function normalizeRouteStationHighlightFeature(feature, index = 0, inRegionKeys = null) {
  const properties = feature?.properties || {};
  const stationKey = properties.stop_id || properties._stationKey || `${stationName(properties)}-${index}`;
  return {
    type: "Feature",
    id: feature?.id || stationKey,
    geometry: feature?.geometry ? JSON.parse(JSON.stringify(feature.geometry)) : null,
    properties: {
      ...properties,
      _stationKey: String(stationKey),
      _outside: Boolean(inRegionKeys) && !inRegionKeys.has(routeStopPhysicalKey(feature)),
    },
  };
}

function routeStationSourceFallbackFeatures(route) {
  const stations = getRouteStations(route?.properties || {}, route);
  if (!stations.length) return [];
  const byName = new Map(
    (realDataCollections.stations?.features || []).map((feature, index) => [stationName(feature.properties || {}), normalizeRouteStationHighlightFeature(feature, index)]),
  );
  return stations.map((station) => byName.get(station.facilityName)).filter(Boolean);
}

function selectLineNetwork(event, options = {}) {
  const map = MapRef.value?.map;
  const point = event?.data?.point;
  const lngLat = event?.data?.lngLat;
  if (!map?.getLayer?.(LAYER_LINES) || !Array.isArray(point) || !Array.isArray(lngLat)) return null;
  const features = map.queryRenderedFeatures(queryBoxAround(point, 7), { layers: [LAYER_LINES] });
  if (!features.length) return null;
  const ranked = features
    .map((feature) => {
      const segment = nearestLineSegment(feature.geometry, lngLat);
      return { feature, segment };
    })
    .filter((item) => item.segment)
    .sort((left, right) => left.segment.distance - right.segment.distance);
  if (!ranked.length) return null;
  const nearest = ranked[0];
  selectedRoute.value = null;
  selectedStation.value = null;
  updateStationSelectionLayers();
  const routes = dedupeRouteOptions(ranked.map((item) => routeOptionFromProperties(item.feature.properties, item.feature)));
  closeSearchResults();
  closeStylePopover();
  closeEditActionMenu();
  lineRoutePicker.x = clampPickerPosition(event.data.event?.clientX ?? point[0], 220, window.innerWidth);
  lineRoutePicker.y = clampPickerPosition(event.data.event?.clientY ?? point[1], 280, window.innerHeight);
  lineRoutePicker.routes = routes;
  lineRoutePicker.mode = options.mode === "edit" ? "edit" : "view";
  lineRoutePicker.lngLat = lngLat;
  lineRoutePicker.point = point;
  lineRoutePicker.visible = true;
  updateSelectedLineLayer({
    ...nearest.segment.feature,
    properties: { ...(nearest.feature.properties || {}) },
  }, { full: false });
  return nearest.feature;
}

function selectRouteFromPicker(route) {
  const pickerMode = lineRoutePicker.mode;
  selectedRoute.value = route;
  selectedStation.value = null;
  updateStationSelectionLayers();
  if (route?.feature) {
    updateSelectedLineLayer(route.feature);
    if (pickerMode !== "edit") {
      focusFeature(route.feature, { minZoom: 12, maxZoom: 15 });
    }
  }
  const shouldOpenEditMenu = pickerMode === "edit" && route?.feature;
  closeLineRoutePicker();
  if (shouldOpenEditMenu) {
    openAttributeTable("line", route);
  }
}

function updateSelectedLineLayer(feature, options = {}) {
  const source = MapRef.value?.map?.getSource(SOURCE_SELECTED_LINE);
  if (source?.setData) {
    const features = options.full === false ? (feature?.geometry ? [feature] : []) : displayLineFeaturesFor(feature);
    source.setData(features.length ? { type: "FeatureCollection", features: features.map(plainGeoJsonFeature) } : emptyFeatureCollection());
  }
  updateBaseLineOpacity();
  // 选中线路变化会改变灰色底图内容（选中时只留该线路的区外溢出）
  updateBaseNetworkLayers();
}

function clearSelectedLineLayer() {
  updateSelectedLineLayer(null);
}

function updateBaseLineOpacity() {
  const map = MapRef.value?.map;
  if (map?.getLayer?.(LAYER_LINES)) {
    const paintKeys = selectedLinePaintKeys();
    map.setPaintProperty(LAYER_LINES, "line-color", lineColorPaint(paintKeys));
    map.setPaintProperty(LAYER_LINES, "line-opacity", lineOpacityPaint(paintKeys));
  }
}

function updateSelectedDepotLayer(feature) {
  const source = MapRef.value?.map?.getSource(SOURCE_SELECTED_DEPOT);
  if (source?.setData) {
    source.setData(feature ? { type: "FeatureCollection", features: [plainGeoJsonFeature(feature)] } : emptyFeatureCollection());
  }
}

function plainGeoJsonFeature(feature) {
  return {
    type: "Feature",
    id: feature?.id,
    geometry: feature?.geometry ? JSON.parse(JSON.stringify(feature.geometry)) : null,
    properties: { ...(feature?.properties || {}) },
  };
}

function fullLineFeatureFor(feature) {
  return fullLineFeaturesFor(feature)[0] || null;
}

function displayLineFeaturesFor(feature) {
  if (!feature) return [];
  const properties = feature.properties || feature.feature?.properties || {};
  const sourceFeatures = Array.isArray(realDataCollections.lines?.features) ? realDataCollections.lines.features : [];
  const matched = sourceFeatures.filter((item) => isSameLogicalRoute(properties, item.properties || {}));
  if (matched.length || selectedDisplayRange.value !== DISPLAY_RANGE_ALL) return matched;
  return fullLineFeaturesFor(feature);
}

function fullLineFeaturesFor(feature) {
  if (!feature) return [];
  const properties = feature.properties || feature.feature?.properties || {};
  const sourceFeatures = Array.isArray(realDataAllCollections.lines?.features) ? realDataAllCollections.lines.features : [];
  const exactKey = String(properties._lineKey || properties._featureId || feature?.id || "");
  const exact = exactKey
    ? sourceFeatures.filter((item) => String(item?.properties?._lineKey || item?.properties?._featureId || item?.id || "") === exactKey)
    : [];
  const matched = sourceFeatures.filter((item) => isSameLogicalRoute(properties, item.properties || {}));
  if (matched.length) return matched;
  if (exact.length) return exact;
  if (feature.feature?.geometry) return [feature.feature];
  return feature.geometry ? [feature] : [];
}

function queryBoxAround(point, radius) {
  return [
    [point[0] - radius, point[1] - radius],
    [point[0] + radius, point[1] + radius],
  ];
}

function clampPickerPosition(value, size, maxSize) {
  const edge = 12;
  return Math.max(edge, Math.min(Number(value) + 12, Math.max(edge, Number(maxSize) - size - edge)));
}

function stationName(properties = {}) {
  return String(properties.stop_name || properties.name || properties.stop_id || "未命名站点");
}

function depotName(properties = {}) {
  return String(properties.depot_name || properties.name || properties["场站名称"] || properties.station_name || properties["名称"] || properties.F002 || "未命名场站");
}

function routesForStation(feature) {
  const explicitRoutes = routeNamesForStation(feature);
  return explicitRoutes;
}

function routeNamesForStation(feature) {
  const properties = feature?.properties || {};
  const stopId = valueOrEmpty(properties.stop_id || properties._stationKey);
  const name = stationName(properties);
  if (!stopId && !name) return [];
  // 与原全量 filter 语义一致：有 stopId 时仅按 stop_id 命中，否则按名称命中
  const candidates = stopId
    ? stationRouteLookup.byStopId.get(stopId) || []
    : stationRouteLookup.byStopName.get(name) || [];
  if (!candidates.length) return [];
  const seen = new Set();
  return candidates
    .map((entry) => entry.feature)
    .sort((left, right) => routeStopSequence(left.properties) - routeStopSequence(right.properties))
    .map((stopFeature) => {
      const matchedLine = lineItemMatchingRoute(stopFeature.properties || {});
      return {
        key: routeDataId(stopFeature.properties) || routeName(stopFeature.properties),
        name: matchedLine?.name || routeName(stopFeature.properties),
      };
    })
    .filter((item) => {
      if (!item?.name || seen.has(item.key)) return false;
      seen.add(item.key);
      return true;
    })
    .map((item) => item.name);
}

function routeOptionFromProperties(properties = {}, feature = null) {
  const resolvedFeature = feature ? fullLineFeatureFor(feature) || feature : null;
  const routeProperties = resolvedFeature?.properties || properties;
  return {
    id: String(routeProperties._lineKey || featureTargetId(resolvedFeature) || routeName(routeProperties) || "route"),
    name: routeName(routeProperties) || "未命名线路",
    properties: { ...routeProperties },
    feature: resolvedFeature,
  };
}

function isRouteOptionActive(route) {
  if (!route || !selectedRoute.value) return false;
  const currentId = selectedRoute.value.id || selectedRoute.value.properties?._lineKey;
  const routeId = route.id || route.properties?._lineKey;
  if (currentId && routeId) return String(currentId) === String(routeId);
  return route.name === selectedRoute.value.name;
}

function dedupeRouteOptions(routes) {
  const seen = new Set();
  const result = [];
  for (const route of routes) {
    const key = String(route?.id || route?.properties?._lineKey || route?.name || "");
    if (!route?.name || seen.has(key)) continue;
    seen.add(key);
    result.push(route);
  }
  return result;
}

function routeEndpoints(properties = {}) {
  const [nameStart, nameEnd] = routeNameEndpoints(properties);
  const start = routeStartName(properties) || nameStart;
  const end = routeEndName(properties) || nameEnd;
  if (!start && !end) return "暂无";
  return `${start || "未知"} - ${end || "未知"}`;
}

function routeServiceTime(properties = {}) {
  const start = formatRouteTime(properties.first);
  const end = formatRouteTime(properties.last);
  if (!start && !end) return "暂无";
  return `${start || "未知"} - ${end || "未知"}`;
}

function routeHeadway(properties = {}) {
  const value = firstAvailableValue(properties, ["interval"]);
  if (!value) return "暂无";
  return String(value).match(/[分m]/i) ? String(value) : `${value} 分钟`;
}

function routeFare(properties = {}) {
  return firstAvailableValue(properties, ["price"]) || "-";
}

function routeCompany(properties = {}) {
  return firstAvailableValue(properties, ["company"]) || "-";
}

function routeTripCount(properties = {}) {
  const value = firstAvailableValue(properties, ["trip_count", "trips", "departures", "班次", "发车班次数"]);
  if (value === undefined || value === null || value === "") return "暂无";
  const text = String(value).trim();
  if (!text) return "暂无";
  if (/^\d+(\.\d+)?$/.test(text)) return `${text} 班`;
  return text;
}

function routeVehicleCount() {
  return "-";
}

function getRouteStations(properties, route = selectedRoute.value) {
  const text = firstAvailableValue(properties, ["station_list_edit", "stations", "stop_names", "途径站点"]);
  const editedStations = parseRouteStationText(text);
  if (editedStations.length) return editedStations;
  const routeStopStations = routeStopsForRoute(properties, route);
  if (routeStopStations.length) return routeStopStations;
  const [start, end] = routeNameEndpoints(properties);
  return [start, end].filter(Boolean).map((name, index) => ({ facilityId: `endpoint-${index}-${name}`, facilityName: name }));
}

function parseRouteStationText(text) {
  if (!text) return [];
  return String(text)
    .split(/[,;\n，；]/)
    .map((name) => name.trim())
    .filter(Boolean)
    .map((name, index) => ({ facilityId: `station-text-${index}-${name}`, facilityName: name }));
}

function routeStopFeaturesForRoute(properties = {}, route = selectedRoute.value) {
  const routeId = routeDataId(properties);
  if (!routeId) return [];
  return routeStopIndexByRouteId().get(routeId) || [];
}

function routeStopIndexByRouteId() {
  const collection = realDataAllCollections.routeStops;
  if (
    routeStopIndexCache.token === realDataRenderToken &&
    routeStopIndexCache.collection === collection
  ) {
    return routeStopIndexCache.byRouteId;
  }
  const byRouteId = new Map();
  const routeStops = Array.isArray(collection?.features) ? collection.features : [];
  routeStops.forEach((feature, sourceIndex) => {
    const stopProperties = feature?.properties || {};
    const routeId = routeDataId(stopProperties);
    if (!routeId) return;
    if (!byRouteId.has(routeId)) byRouteId.set(routeId, []);
    byRouteId.get(routeId).push({
      feature,
      sequence: routeStopSequence(stopProperties),
      sourceIndex,
    });
  });
  byRouteId.forEach((items) => {
    items.sort((left, right) => left.sequence - right.sequence || left.sourceIndex - right.sourceIndex);
  });
  routeStopIndexCache = {
    token: realDataRenderToken,
    collection,
    byRouteId,
  };
  return byRouteId;
}

function routeStopsForRoute(properties = {}, route = selectedRoute.value) {
  return routeStopFeaturesForRoute(properties, route)
    .map(({ feature, sequence, sourceIndex }) => {
      const stopProperties = feature.properties || {};
      return {
        facilityId: stopProperties._routeStopKey || stopProperties.stop_id || `${routeName(stopProperties) || routeName(properties)}-${sourceIndex}`,
        facilityName: stationName(stopProperties),
        sequence,
        sourceIndex,
      };
    })
    .sort((left, right) => left.sequence - right.sequence || left.sourceIndex - right.sourceIndex)
    .map(({ facilityId, facilityName }) => ({ facilityId, facilityName }));
}

function isRouteStopMatch(stopProperties = {}, routeId = "") {
  const stopRouteId = routeDataId(stopProperties);
  if (routeId && stopRouteId && routeId === stopRouteId) return true;
  return false;
}

function routeFeaturesForOption(route = selectedRoute.value) {
  const routeProperties = route?.properties || route?.feature?.properties || {};
  const matchedFeatures = linesMatchingRoute(routeProperties);
  if (matchedFeatures.length) return matchedFeatures;
  return route?.feature ? [route.feature] : [];
}

function isSameLogicalRoute(routeProperties = {}, featureProperties = {}) {
  const routeLineKey = valueOrEmpty(routeProperties._lineKey || routeProperties._featureId);
  const featureLineKey = valueOrEmpty(featureProperties._lineKey || featureProperties._featureId);
  if (routeLineKey && featureLineKey && routeLineKey === featureLineKey) return true;
  const routeId = routeDataId(routeProperties);
  const featureRouteId = routeDataId(featureProperties);
  if (routeId && featureRouteId && routeId === featureRouteId) return true;
  return false;
}

function routeStartName(properties = {}) {
  return routeNameEndpoints(properties)[0] || "";
}

function routeEndName(properties = {}) {
  return routeNameEndpoints(properties)[1] || "";
}

function getRouteLength(properties) {
  const meters = routeLengthMeters(properties);
  if (!Number.isFinite(meters) || meters <= 0) return "暂无";
  return meters >= 1000 ? `${(meters / 1000).toFixed(1)} km` : `${Math.round(meters)} m`;
}

function getRouteDirectness(properties, route = selectedRoute.value) {
  const val = firstAvailableValue(properties, ["directness", "lc", "coefficient", "straightness"]);
  if (val && !isNaN(parseFloat(val))) return parseFloat(val).toFixed(2);
  const lengthMeters = routeLengthMeters(properties);
  const straightMeters = routeStraightDistanceMeters(properties, route);
  if (Number.isFinite(lengthMeters) && lengthMeters > 0 && Number.isFinite(straightMeters) && straightMeters > 0) {
    return (lengthMeters / straightMeters).toFixed(2);
  }
  return "环线";
}

function getRouteStationCount(properties, route = selectedRoute.value) {
  return getRouteStations(properties, route).length;
}

function getRouteAvgStationDistance(properties) {
  const derivedValue = Number(firstAvailableValue(properties, ["avg_stop_m"]));
  if (Number.isFinite(derivedValue) && derivedValue >= 0) {
    return `${Math.round(derivedValue)} m`;
  }
  const lengthMeters = routeLengthMeters(properties);
  const count = selectedRoute.value ? selectedRouteStations.value.length : getRouteStationCount(properties);
  if (count > 1 && Number.isFinite(lengthMeters)) {
    return `${Math.round(lengthMeters / (count - 1))} m`;
  }
  return "暂无";
}

function routeLengthMeters(properties = {}, route = selectedRoute.value) {
  const derivedKm = Number(firstAvailableValue(properties, ["len_km"]));
  if (Number.isFinite(derivedKm) && derivedKm > 0) return derivedKm * 1000;
  const value = firstAvailableValue(properties, ["length", "distance", "routeDist", "route_len", "line_length"]);
  const number = parseFloat(value);
  if (Number.isFinite(number) && number > 0) return number > 100 ? number : number * 1000;
  return routeGeometryLengthMeters(route);
}

function routeGeometryLengthMeters(route = selectedRoute.value) {
  return routeFeaturesForOption(route)
    .flatMap((feature) => lineCoordinatePaths(feature.geometry))
    .reduce((total, path) => {
      let pathLength = 0;
      for (let index = 1; index < path.length; index += 1) {
        pathLength += lngLatDistanceMeters(path[index - 1], path[index]) || 0;
      }
      return total + pathLength;
    }, 0);
}

function routeStraightDistanceMeters(properties = {}, route = selectedRoute.value) {
  const stopDistance = routeStopEndpointDistanceMeters(properties, route);
  if (Number.isFinite(stopDistance) && stopDistance > 0) return stopDistance;
  const geometryEndpoints = routeGeometryEndpointCoordinates(route);
  if (!geometryEndpoints) return null;
  return lngLatDistanceMeters(geometryEndpoints[0], geometryEndpoints[1]);
}

function routeStopEndpointDistanceMeters(properties = {}, route = selectedRoute.value) {
  const stops = routeStopFeaturesForRoute(properties, route);
  if (stops.length < 2) return null;
  const first = pointCoordinates(stops[0].feature?.geometry);
  const last = pointCoordinates(stops[stops.length - 1].feature?.geometry);
  if (!first || !last) return null;
  return lngLatDistanceMeters(first, last);
}

function routeGeometryEndpointCoordinates(route = selectedRoute.value) {
  const coordinates = routeFeaturesForOption(route)
    .flatMap((feature) => lineCoordinatePaths(feature.geometry))
    .flat()
    .filter((coordinate) => Array.isArray(coordinate) && coordinate.length >= 2);
  if (coordinates.length < 2) return null;
  return [coordinates[0], coordinates[coordinates.length - 1]];
}

function lngLatDistanceMeters(left, right) {
  if (!left || !right) return null;
  const start = lngLatToWebMercator(Number(left[0]), Number(left[1]));
  const end = lngLatToWebMercator(Number(right[0]), Number(right[1]));
  if (!isValidPoint(start) || !isValidPoint(end)) return null;
  return Math.hypot(end[0] - start[0], end[1] - start[1]);
}

function getRoutePassenger(properties) {
  const flow = routePassengerFlowValue(properties);
  return Number.isFinite(flow) ? `${Math.round(flow).toLocaleString()} 人次` : "暂无";
}

function getRouteLoadRate(properties) {
  const rate = parseFloat(firstAvailableValue(properties, ["loadRate", "takeRate", "load_rate"]));
  if (!isNaN(rate)) return `${rate.toFixed(1)}%`;
  return "暂无";
}

function formatPassengerFlow(value) {
  if (value === undefined || value === null || value === "") return "—";
  const number = Number(value);
  return Number.isFinite(number) ? Math.round(number).toLocaleString("zh-CN") : "—";
}

function hasPassengerFlow(value) {
  if (value === undefined || value === null || value === "") return false;
  return Number.isFinite(Number(value));
}

function routePassengerFlowValue(properties = {}) {
  return nullableNumber(firstAvailableValue(properties, ["passenger", "passengerFlow", "passenger_flow", "daily_passenger", "dailyFlow", "日均客流"]));
}

function compareStationRoutesByPassenger(left, right) {
  const leftHasFlow = hasPassengerFlow(left?.passengerFlow);
  const rightHasFlow = hasPassengerFlow(right?.passengerFlow);
  if (leftHasFlow && rightHasFlow) {
    return Number(right.passengerFlow) - Number(left.passengerFlow);
  }
  if (leftHasFlow !== rightHasFlow) {
    return leftHasFlow ? -1 : 1;
  }
  return String(left?.name || "").localeCompare(String(right?.name || ""), "zh-Hans-CN");
}

function splitOperatorCompanies(value) {
  const text = valueOrEmpty(value);
  if (!text) return [];
  return text
    .split(/[、,，/／;；]+/)
    .map((item) => item.trim())
    .filter(Boolean);
}

function formatRouteTime(value) {
  const raw = valueOrEmpty(value);
  const colonTime = raw.match(/(\d{1,2})[:：](\d{2})/);
  if (colonTime) {
    return `${colonTime[1].padStart(2, "0")}:${colonTime[2]}`;
  }
  const text = raw.replace(/\D/g, "");
  if (!text) return "";
  const padded = text.length >= 4 ? text.slice(0, 4) : text.padStart(4, "0");
  return `${padded.slice(0, 2)}:${padded.slice(2)}`;
}

function routeName(properties = {}) {
  return String(properties.name || properties.line_id || properties.route_id || "").trim();
}

function routeNameEndpoints(properties = {}) {
  const parsed = parsePickerRoute(routeName(properties));
  if (!parsed.desc) return ["", ""];
  const parts = parsed.desc
    .split(/\s+-\s+|--|—|－|至|到/)
    .map((part) => part.trim())
    .filter(Boolean);
  if (parts.length < 2) return ["", ""];
  return [parts[0], parts[parts.length - 1]];
}

function buildStationSearchIndex(collection) {
  const features = Array.isArray(collection?.features) ? collection.features : [];
  return features.map((feature, index) => {
    const properties = feature.properties || {};
    const name = stationName(properties);
    return {
      key: `station-${properties._stationKey || index}`,
      type: "station",
      typeLabel: "站点",
      name,
      feature,
      searchText: normalizeSearchText([name, properties.stop_name, properties.stop_id, properties.line_id].filter(Boolean).join(" ")),
    };
  });
}

function buildLineSearchIndex(collection) {
  const features = Array.isArray(collection?.features) ? collection.features : [];
  const seen = new Set();
  const rows = [];
  features.forEach((feature, index) => {
    const properties = feature.properties || {};
    const dedupeKey = String(properties._lineKey || featureTargetId(feature) || index);
    if (!dedupeKey || seen.has(dedupeKey)) return;
    seen.add(dedupeKey);
    const name = routeName(properties) || "未命名线路";
    rows.push({
      key: `line-${dedupeKey}`,
      type: "line",
      typeLabel: "线路",
      name,
      feature,
      searchText: normalizeSearchText([name, properties.line_id, properties.route_id, properties.dir, properties.mode].filter(Boolean).join(" ")),
    });
  });
  return rows;
}

function buildDepotSearchIndex(collection) {
  const features = Array.isArray(collection?.features) ? collection.features : [];
  return features.map((feature, index) => {
    const properties = feature.properties || {};
    const name = depotName(properties);
    return {
      key: `depot-${properties._depotKey || index}`,
      type: "depot",
      typeLabel: "场站",
      name,
      feature,
      searchText: normalizeSearchText([name, properties.depot_id, properties.station_name].filter(Boolean).join(" ")),
    };
  });
}

function buildStationRouteLookup(collection) {
  const byStopId = new Map();
  const byStopKey = new Map();
  const byStopName = new Map();
  const push = (map, key, entry) => {
    if (!key) return;
    if (!map.has(key)) map.set(key, []);
    map.get(key).push(entry);
  };
  collectionFeatures(collection).forEach((feature, sourceIndex) => {
    const properties = feature?.properties || {};
    const entry = { feature, sourceIndex };
    push(byStopId, valueOrEmpty(properties.stop_id), entry);
    push(byStopKey, valueOrEmpty(properties.stop_id || properties._stationKey), entry);
    push(byStopName, stationName(properties), entry);
  });
  return { byStopId, byStopKey, byStopName };
}

function buildLineLookup(indexItems) {
  const byName = new Map();
  const byEntry = new Map();
  indexItems.forEach((item, index) => {
    if (!byName.has(item.name)) byName.set(item.name, item);
    const properties = item.feature?.properties || {};
    const featureKey = valueOrEmpty(properties._lineKey || properties._featureId);
    if (featureKey && !byEntry.has(`k:${featureKey}`)) byEntry.set(`k:${featureKey}`, { item, index });
    const routeId = routeDataId(properties);
    if (routeId && !byEntry.has(`r:${routeId}`)) byEntry.set(`r:${routeId}`, { item, index });
  });
  return { byName, byEntry };
}

// 等价于 lineSearchIndex.find((item) => isSameLogicalRoute(properties, item.feature.properties))：
// 线路键与 route_id 双通道命中后取索引序最小者，保持 find 的首个命中语义
function lineItemMatchingRoute(properties = {}) {
  const candidates = [];
  const featureKey = valueOrEmpty(properties._lineKey || properties._featureId);
  if (featureKey) {
    const entry = lineLookup.byEntry.get(`k:${featureKey}`);
    if (entry) candidates.push(entry);
  }
  const routeId = routeDataId(properties);
  if (routeId) {
    const entry = lineLookup.byEntry.get(`r:${routeId}`);
    if (entry) candidates.push(entry);
  }
  if (!candidates.length) return null;
  if (candidates.length > 1 && candidates[1].index < candidates[0].index) return candidates[1].item;
  return candidates[0].item;
}

function lineFeatureIndexByRouteKey() {
  const collection = realDataAllCollections.lines;
  if (lineFeatureIndexCache.token === realDataRenderToken && lineFeatureIndexCache.collection === collection) {
    return lineFeatureIndexCache.byKey;
  }
  const byKey = new Map();
  const push = (key, entry) => {
    if (!byKey.has(key)) byKey.set(key, []);
    byKey.get(key).push(entry);
  };
  collectionFeatures(collection).forEach((feature, index) => {
    const properties = feature?.properties || {};
    const featureKey = valueOrEmpty(properties._lineKey || properties._featureId);
    if (featureKey) push(`k:${featureKey}`, { feature, index });
    const routeId = routeDataId(properties);
    if (routeId) push(`r:${routeId}`, { feature, index });
  });
  lineFeatureIndexCache = { token: realDataRenderToken, collection, byKey };
  return byKey;
}

// 等价于 realDataAllCollections.lines.features.filter((f) => isSameLogicalRoute(properties, f.properties))，按源顺序返回
function linesMatchingRoute(properties = {}) {
  const byKey = lineFeatureIndexByRouteKey();
  const merged = new Map();
  const featureKey = valueOrEmpty(properties._lineKey || properties._featureId);
  if (featureKey) {
    for (const entry of byKey.get(`k:${featureKey}`) || []) merged.set(entry.index, entry.feature);
  }
  const routeId = routeDataId(properties);
  if (routeId) {
    for (const entry of byKey.get(`r:${routeId}`) || []) merged.set(entry.index, entry.feature);
  }
  if (!merged.size) return [];
  return [...merged.entries()].sort((left, right) => left[0] - right[0]).map(([, feature]) => feature);
}

function rankSearchItems(items, query) {
  const entries = [];
  for (const item of items) {
    const score = searchScore(item.searchText, query);
    if (score >= 0) entries.push({ item, score });
  }
  return entries;
}

function searchScore(text, query) {
  if (!text || !query) return -1;
  if (text === query) return 0;
  if (text.startsWith(query)) return 1;
  const index = text.indexOf(query);
  return index >= 0 ? 2 + index / 1000 : -1;
}

function normalizeSearchText(value) {
  return String(value || "").trim().toLowerCase().replace(/\s+/g, "");
}

function nearestLineSegment(geometry, lngLat) {
  const point = lngLatToWebMercator(lngLat[0], lngLat[1]);
  let nearest = null;
  for (const path of lineCoordinatePaths(geometry)) {
    for (let index = 1; index < path.length; index += 1) {
      const startLngLat = validLngLat(path[index - 1]);
      const endLngLat = validLngLat(path[index]);
      if (!startLngLat || !endLngLat) continue;
      const start = lngLatToWebMercator(startLngLat[0], startLngLat[1]);
      const end = lngLatToWebMercator(endLngLat[0], endLngLat[1]);
      const distance = distanceToSegment(point, start, end);
      if (!nearest || distance < nearest.distance) {
        nearest = {
          distance,
          feature: {
            type: "Feature",
            geometry: {
              type: "LineString",
              coordinates: [startLngLat, endLngLat],
            },
            properties: {},
          },
        };
      }
    }
  }
  return nearest;
}

function distanceToSegment(point, start, end) {
  const dx = end[0] - start[0];
  const dy = end[1] - start[1];
  const lengthSquared = dx * dx + dy * dy;
  if (!lengthSquared) return Math.hypot(point[0] - start[0], point[1] - start[1]);
  const ratio = Math.max(0, Math.min(1, ((point[0] - start[0]) * dx + (point[1] - start[1]) * dy) / lengthSquared));
  return Math.hypot(point[0] - (start[0] + ratio * dx), point[1] - (start[1] + ratio * dy));
}

function isValidPoint(point) {
  return Array.isArray(point) && Number.isFinite(point[0]) && Number.isFinite(point[1]);
}

async function submitActiveEdits() {
  return submitPendingEdits();
}

async function submitPendingEdits() {
  const operations = deepClone(activeEditOperations.value);
  if (!operations.length) return true;
  if (isSubmittingEdit.value) return false;
  const areaName = selectedArea.value;
  const baseRevision = realDataRevision.value;
  const baseVersionId = realDataVersionId.value;
  const commitPayload = await requestCommitPayload();
  if (!commitPayload) return false;
  isSubmittingEdit.value = true;
  try {
    const res = await commitRealDataEdits({
      areaName,
      datasetType: "all",
      baseRevision,
      baseVersionId,
      message: commitPayload.message,
      evidenceImages: commitPayload.evidenceImages,
      operations,
    });
    clearAllEditOperations();
    invalidateRealDataCache(areaName);
    invalidateHistoryCache(areaName);
    syncHistorySummary(res?.data?.history || { revision: res?.data?.revision, activeDataVersionId: res?.data?.versionId });
    ElMessage.success("修改已提交，历史版本已更新");
    if (selectedArea.value === areaName) {
      await loadOverviewLayers({ force: true, fit: false });
    }
    return true;
  } catch (error) {
    if (String(error?.message || "").includes("其他用户更新")) {
      ElMessage.warning("未提交修改已保留，请刷新数据后确认最新版本再提交");
    } else {
      ElMessage.error(error?.message || "提交失败，请稍后重试");
    }
    return false;
  } finally {
    isSubmittingEdit.value = false;
  }
}

function invalidateRealDataCache(areaName = selectedArea.value) {
  invalidateCachedRealData(areaName);
}

function invalidateHistoryCache(areaName = selectedArea.value) {
  invalidateCachedHistory(areaName);
}

function requestCommitPayload() {
  resetCommitDialog();
  commitDialog.visible = true;
  return new Promise((resolve) => {
    commitDialog.resolver = resolve;
  });
}

function resetCommitDialog() {
  commitDialog.message = "";
  commitDialog.evidenceImages = [];
  commitDialog.dragging = false;
  commitDialog.processing = false;
}

function resolveCommitDialog(value) {
  const resolver = commitDialog.resolver;
  commitDialog.resolver = null;
  if (resolver) resolver(value);
}

function confirmCommitDialog() {
  const message = commitDialog.message.trim();
  if (!message) {
    ElMessage.warning("请填写修改信息");
    return;
  }
  resolveCommitDialog({
    message,
    evidenceImages: deepClone(commitDialog.evidenceImages),
  });
  commitDialog.visible = false;
}

function cancelCommitDialog() {
  resolveCommitDialog(null);
  commitDialog.visible = false;
}

function handleCommitDialogClosed() {
  commitDialog.dragging = false;
  commitDialog.processing = false;
  if (commitDialog.resolver) {
    resolveCommitDialog(null);
  }
}

function openEvidenceFilePicker() {
  evidenceImageInput.value?.click?.();
}

async function handleEvidenceFileInput(event) {
  const files = Array.from(event?.target?.files || []);
  await addEvidenceFiles(files);
  if (event?.target) event.target.value = "";
}

async function handleEvidenceDrop(event) {
  commitDialog.dragging = false;
  const files = Array.from(event?.dataTransfer?.files || []);
  await addEvidenceFiles(files);
}

async function addEvidenceFiles(files) {
  const imageFiles = files.filter((file) => file?.type?.startsWith("image/"));
  if (!imageFiles.length) {
    ElMessage.warning("请上传图片文件");
    return;
  }
  const slots = Math.max(0, 6 - commitDialog.evidenceImages.length);
  if (!slots) {
    ElMessage.warning("最多上传 6 张证据图片");
    return;
  }
  commitDialog.processing = true;
  try {
    for (const file of imageFiles.slice(0, slots)) {
      const image = await compressEvidenceImage(file);
      commitDialog.evidenceImages.push(image);
    }
    if (imageFiles.length > slots) {
      ElMessage.warning("已达到 6 张上限，超出的图片未加入");
    }
  } catch (error) {
    ElMessage.error(error?.message || "图片处理失败");
  } finally {
    commitDialog.processing = false;
  }
}

function compressEvidenceImage(file) {
  return new Promise((resolve, reject) => {
    if (file.size > 12 * 1024 * 1024) {
      reject(new Error("单张图片不能超过 12MB"));
      return;
    }
    const reader = new FileReader();
    reader.onerror = () => reject(new Error("读取图片失败"));
    reader.onload = () => {
      const image = new Image();
      image.onerror = () => reject(new Error("图片格式无法识别"));
      image.onload = () => {
        const maxSide = 1440;
        const ratio = Math.min(1, maxSide / Math.max(image.naturalWidth || image.width, image.naturalHeight || image.height));
        const width = Math.max(1, Math.round((image.naturalWidth || image.width) * ratio));
        const height = Math.max(1, Math.round((image.naturalHeight || image.height) * ratio));
        const canvas = document.createElement("canvas");
        canvas.width = width;
        canvas.height = height;
        const context = canvas.getContext("2d");
        context.fillStyle = "#f8fbff";
        context.fillRect(0, 0, width, height);
        context.drawImage(image, 0, 0, width, height);
        const dataUrl = canvas.toDataURL("image/jpeg", 0.82);
        if (dataUrl.length > 1_900_000) {
          reject(new Error("图片仍然过大，请先裁剪或压缩后上传"));
          return;
        }
        resolve({
          id: `evidence_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
          name: file.name || "证据图片.jpg",
          type: "image/jpeg",
          size: file.size,
          width,
          height,
          dataUrl,
        });
      };
      image.src = String(reader.result || "");
    };
    reader.readAsDataURL(file);
  });
}

function removeEvidenceImage(id) {
  commitDialog.evidenceImages = commitDialog.evidenceImages.filter((image) => image.id !== id);
}

function previewEvidenceImage(image) {
  if (!image?.dataUrl) return;
  window.open(image.dataUrl, "_blank", "noopener,noreferrer");
}

async function viewHistoryVersion(record) {
  if (!record?.versionId) return;
  historyPreview.visible = true;
  historyPreview.loading = true;
  historyPreview.error = "";
  historyPreview.version = record;
  closeHistoryDetails();
  closeTransientSurfaces();
  try {
    const data = await getCachedRealData(selectedArea.value, { versionId: record.versionId });
    setOverviewStats(data);
    renderRealDataLayers(data, "overview");
    fitBounds(data.bounds);
  } catch (error) {
    historyPreview.error = error?.message || "历史版本加载失败";
    ElMessage.error(historyPreview.error);
  } finally {
    historyPreview.loading = false;
  }
}

function exitHistoryPreview() {
  historyPreview.visible = false;
  historyPreview.loading = false;
  historyPreview.error = "";
  historyPreview.version = null;
  closeTransientSurfaces();
  // 预览渲染占用了规范化缓存槽（指向历史版本数据），退出时作废，杜绝任何误命中可能
  lastNormalizedData = null;
  lastNormalizedCollections = null;
  clearRealDataLayers();
  unbindStationClickListener();
  loadHistoryList();
}

async function discardActiveEdits() {
  const pendingCount = activeEditOperations.value.length;
  if (pendingCount) {
    try {
      await ElMessageBox.confirm(`放弃线路、站点和场站共 ${pendingCount} 条未提交修改？放弃后无法恢复。`, "放弃修改", {
        confirmButtonText: "放弃",
        cancelButtonText: "继续编辑",
        type: "warning",
      });
    } catch {
      return;
    }
  }
  clearAllEditOperations();
  closeTransientSurfaces();
  pendingMoveTarget.value = null;
  pendingAddDataset.value = "";
  loadOverviewLayers({ fit: false });
}

async function confirmLeaveWithUnsavedEdits() {
  if (!hasAnyUnsavedEdits.value) return true;
  try {
    await ElMessageBox.confirm(`数据更新中有 ${activeEditOperations.value.length} 条未提交修改，是否统一提交后离开？`, "未保存修改", {
      confirmButtonText: "提交并离开",
      cancelButtonText: "全部放弃",
      distinguishCancelAndClose: true,
      type: "warning",
    });
    return submitPendingEdits();
  } catch (action) {
    if (action === "cancel") {
      clearAllEditOperations();
      return true;
    }
    return false;
  }
}

function clearAllEditOperations() {
  EDIT_DATASET_TYPES.forEach((datasetType) => {
    editOperations[datasetType].splice(0);
  });
  editOperationRenderCount.value = EDIT_OPERATION_RENDER_BATCH;
  // 放弃/提交修改后，规范化缓存里可能带着本地预览的增删结果，必须作废以便下次从原始数据重建
  lastNormalizedData = null;
  lastNormalizedCollections = null;
}

function handleBeforeUnload(event) {
  if (!hasAnyUnsavedEdits.value) return;
  event.preventDefault();
  event.returnValue = "";
}

function handleEscapeKey(event) {
  if (event?.key !== "Escape") return;
  if (editDialog.visible) return;
  if (pendingAddDataset.value) {
    cancelPendingAdd();
    return;
  }
  if (pendingMoveTarget.value) {
    pendingMoveTarget.value = null;
    return;
  }
  closeTransientSurfaces();
}

watch(selectedArea, async (nextArea, previousArea) => {
  if (restoringAreaSelection) {
    restoringAreaSelection = false;
    return;
  }
  if (!confirmedAreaSelection && previousArea && nextArea !== previousArea && hasAnyUnsavedEdits.value) {
    restoringAreaSelection = true;
    selectedArea.value = previousArea;
    const canLeave = await confirmLeaveWithUnsavedEdits();
    if (!canLeave || hasAnyUnsavedEdits.value) return;
    confirmedAreaSelection = true;
    selectedArea.value = nextArea;
    return;
  }
  if (confirmedAreaSelection) {
    confirmedAreaSelection = false;
  }
  selectedDisplayRange.value = DISPLAY_RANGE_ALL;
  adminDistrictCollection = emptyFeatureCollection();
  displayRangeList.value = [DISPLAY_RANGE_ALL];
  loadDisplayRanges();
  closeHistoryDetails();
  if (activeKey.value === "history") {
    if (historyPreview.visible) {
      exitHistoryPreview();
      return;
    }
    loadHistoryList();
    return;
  }
  loadOverviewLayers({ fit: true });
});
watch(selectedDisplayRange, () => {
  closeTransientSurfaces();
  applyDisplayRangeFilter({ updateSources: true, clearSelection: true });
  nextTick(fitDisplayRangeBounds);
});
watch(activeKey, (key, previousKey) => {
  closeTransientSurfaces();
  closeHistoryDetails();
  pendingAddDataset.value = "";
  pendingMoveTarget.value = null;
  if (historyPreview.visible) {
    historyPreview.visible = false;
    historyPreview.version = null;
  }
  if (isUpdateModeSwitch(previousKey, key)) {
    applyMapDataMode(key);
    return;
  }
  if (isMapDataPage(key)) {
    loadOverviewLayers({ fit: false });
    return;
  }
  if (key === "history") {
    clearRealDataLayers();
    unbindStationClickListener();
    loadHistoryList();
    return;
  }
  clearRealDataLayers();
  unbindStationClickListener();
});
watch(MapRef, (mapInstance) => {
  bindMapStateListeners(mapInstance);
  bindSelectableHoverListener();
  if (isMapDataPage(activeKey.value)) {
    loadOverviewLayers({ fit: true });
  }
}, { immediate: true });

function parsePickerRoute(fullName) {
  if (!fullName) return { mainName: "未知线路", desc: "" };
  const match = fullName.match(/^([^(]+)\(([^)]+)\)$/);
  if (match) {
    const mainName = match[1].trim();
    const desc = match[2].replace(/--/g, " - ").trim();
    return { mainName, desc };
  }
  return { mainName: fullName, desc: "" };
}

onMounted(() => {
  window.addEventListener("beforeunload", handleBeforeUnload);
  window.addEventListener("keydown", handleEscapeKey);
  // 三个请求互不依赖（默认区域已就位），并行发出省两个串行往返；
  // 若区域列表纠正了 selectedArea，watch(selectedArea) 会自动重载后两者
  handleGetAreaList().catch(() => {});
  loadDisplayRanges();
  loadOverviewLayers({ fit: true });
});

onBeforeUnmount(() => {
  window.removeEventListener("beforeunload", handleBeforeUnload);
  window.removeEventListener("keydown", handleEscapeKey);
  if (MapRef.value) {
    if (zoomListenerId) MapRef.value.removeEventListener("update:zoom", zoomListenerId);
    if (rotateListenerId) MapRef.value.removeEventListener("update:camera:rotate", rotateListenerId);
  }
  unbindSelectableHoverListener();
  unbindStationClickListener();
  if (layerPaintRaf) {
    cancelAnimationFrame(layerPaintRaf);
    layerPaintRaf = 0;
  }
  window.clearTimeout(mapChromeResizeTimer);
  activeDistrictFilterToken += 1;
  pendingDistrictFilterResolvers.clear();
  districtWorker?.terminate?.();
  districtWorker = null;
  districtWorkerSource = null;
  districtWorkerDataStale = true;
  clearRealDataLayers();
});
</script>

<style lang="scss" scoped>
.datebase_box,
.dm-overview-panel,
.dm-edit-panel,
.edit-action-menu,
.history-preview-panel {
  scale: var(--app-panel-scale);
}

.datebase_box {
  position: fixed;
  top: calc(var(--app-header-height) / 2);
  right: calc(var(--app-edge) + 64px);
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  transform: translateY(-50%);
  transform-origin: right center;
  z-index: calc(var(--z-header) + 10);
  max-width: min(34vw, 340px);
  min-width: 0;

  .handle {
    cursor: default;
    font-size: 0.95rem;
    font-weight: 600;
    color: #374151;
    white-space: nowrap;
  }

  .el-select {
    width: clamp(150px, 14vw, 210px);

    :deep(.el-input__wrapper) {
      background-color: rgba(251, 253, 255, 0.88) !important;
      box-shadow: 0 0 0 1px var(--app-border-strong) inset !important;
      border-radius: var(--app-card-radius);
      padding: 6px 12px;
      transition:
        background-color 0.2s ease,
        box-shadow 0.2s ease;

      &:hover {
        background-color: var(--app-card-bg) !important;
        box-shadow: 0 0 0 1px rgba(11, 145, 183, 0.45) inset !important;
      }

      &.is-focus {
        background-color: var(--app-card-bg) !important;
        box-shadow: 0 0 0 1.5px var(--app-cyan) inset, var(--app-focus-ring) !important;
      }

      .el-input__inner {
        color: var(--app-ink) !important;
        font-weight: 500;
        font-size: 0.94rem !important;
      }

      .el-select__caret {
        color: var(--app-cyan) !important;
        font-size: 14px;
      }
    }
  }
}

.dm-overview-panel,
.dm-edit-panel {
  position: fixed;
  right: var(--app-edge);
  top: calc(var(--app-header-height) + 8px);
  height: calc((100vh - var(--app-header-height) - 16px) / var(--app-panel-scale));
  transform-origin: right top;
  z-index: var(--z-panel);
  width: 380px;
  display: flex;
  flex-direction: column;
  padding: 16px;
  overflow: hidden;
  isolation: isolate;
  border-radius: var(--app-panel-radius);
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.98) 0%, rgba(247, 250, 255, 0.95) 100%);
  border: 1px solid rgba(21, 105, 222, 0.15);
  box-shadow:
    0 18px 44px rgba(15, 66, 125, 0.14),
    0 5px 14px rgba(15, 39, 68, 0.06),
    inset 0 1px 0 rgba(255, 255, 255, 0.92);
  /* 背景已近乎不透明（0.98/0.95），blur 视觉收益≈0 但每帧合成代价高，移除 */
  transition: transform var(--app-motion-slow) var(--app-ease-out), opacity var(--app-motion-normal) ease;

  &::before,
  &::after {
    content: "";
    position: absolute;
    pointer-events: none;
    z-index: -1;
  }

  &::before {
    inset: 0 0 auto;
    height: 3px;
    background: linear-gradient(90deg, var(--app-blue) 0%, var(--app-cyan) 58%, var(--app-emerald) 100%);
    opacity: 0.82;
  }

  &::after {
    inset: 3px 0 auto;
    height: 116px;
    background:
      radial-gradient(circle at 24px 18px, rgba(21, 105, 222, 0.11), transparent 36px),
      linear-gradient(180deg, rgba(21, 105, 222, 0.05), transparent);
  }

  @starting-style {
    opacity: 0;
    transform: translateX(36px);
  }
}

.edit-operation-list {
  flex: 1;
  display: grid;
  gap: 8px;
  margin-top: 14px;
  overflow-y: auto;
  padding-right: 2px;
  scrollbar-width: thin;
  scrollbar-color: rgba(21, 105, 222, 0.18) transparent;

  &::-webkit-scrollbar {
    width: 4px;
  }

  &::-webkit-scrollbar-thumb {
    background: rgba(21, 105, 222, 0.18);
    border-radius: 10px;
  }
}

.edit-operation-item {
  position: relative;
  display: grid;
  gap: 5px;
  padding: 11px 12px 11px 14px;
  overflow: hidden;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.82);
  border: 1px solid rgba(21, 105, 222, 0.11);
  box-shadow: 0 5px 14px rgba(15, 66, 125, 0.04);
  transition:
    border-color var(--app-motion-normal) var(--app-ease-out),
    box-shadow var(--app-motion-normal) var(--app-ease-out),
    transform var(--app-motion-fast) var(--app-ease-press);

  &::before {
    content: "";
    position: absolute;
    left: 0;
    top: 10px;
    bottom: 10px;
    width: 3px;
    border-radius: 0 3px 3px 0;
    background: linear-gradient(180deg, var(--app-amber), var(--app-cyan));
    opacity: 0.8;
  }

  &:hover {
    transform: translateY(-1px);
    border-color: rgba(21, 105, 222, 0.23);
    box-shadow: 0 10px 22px rgba(15, 66, 125, 0.08);
  }

  .operation-type {
    width: fit-content;
    padding: 2px 7px;
    border-radius: 6px;
    background: rgba(245, 158, 11, 0.12);
    color: #9a5a05;
    font-size: 11px;
    line-height: 1.25;
    font-weight: 700;
  }

  strong {
    color: #12304f;
    font-size: 13px;
    line-height: 1.3;
    font-weight: 700;
    word-break: break-word;
  }

  p {
    margin: 0;
    color: #64748b;
    font-size: 12px;
    line-height: 1.35;
    font-weight: 600;
    word-break: break-word;
  }
}

.edit-empty {
  flex: 1;
  display: grid;
  align-content: start;
  gap: 9px;
  margin: 14px 0 0;
  padding: 16px;
  border-radius: 8px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.76), rgba(244, 248, 255, 0.82));
  border: 1px solid rgba(21, 105, 222, 0.12);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.72);
  color: #40566f;
  font-size: 12px;
  line-height: 1.5;

  strong {
    color: #12304f;
    font-size: 13px;
    line-height: 1.35;
    font-weight: 700;
  }

  p {
    margin: 0;
    font-weight: 600;
  }

  ol {
    counter-reset: edit-guide;
    display: grid;
    gap: 7px;
    margin: 0;
    padding-left: 0;
    list-style: none;
  }

  li {
    position: relative;
    min-height: 22px;
    padding-left: 30px;
    color: #4a6078;
    font-weight: 600;

    &::before {
      counter-increment: edit-guide;
      content: counter(edit-guide);
      position: absolute;
      left: 0;
      top: 0;
      width: 20px;
      height: 20px;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border-radius: 6px;
      background: rgba(21, 105, 222, 0.09);
      color: var(--app-blue-strong);
      font-family: var(--app-font-number);
      font-size: 11px;
      font-weight: 700;
    }
  }

  .el-button {
    justify-self: start;
    margin-left: 0;
  }
}

.edit-panel-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid rgba(21, 105, 222, 0.08);
}

.dm-history-page {
  position: fixed;
  left: 260px;
  right: 0;
  top: var(--app-header-height);
  bottom: 0;
  z-index: calc(var(--z-panel) + 1);
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 22px 26px;
  overflow-y: auto;
  background: #f7f9fc;
}

.overview-title-row {
  position: relative;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 2px 0 14px;
  border-bottom: 1px solid rgba(21, 105, 222, 0.08);

  .panel-kicker {
    margin: 0 0 4px;
    color: #60758e;
    font-size: 12px;
    font-weight: 700;
    letter-spacing: 0.04em;
  }

  h2 {
    margin: 0;
    color: #10243f;
    font-size: 20px;
    line-height: 1.25;
    font-weight: 700;
    letter-spacing: 0;
    word-break: break-word;
  }

  .detail-title-block {
    min-width: 0;
    display: grid;
    gap: 3px;

    span {
      color: #6b7d90;
      font-size: 11.5px;
      line-height: 1.35;
      font-weight: 600;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  :deep(.el-tag) {
    --el-tag-border-radius: 6px;
    flex-shrink: 0;
    font-weight: 700;
  }
}

/* Base Metric Card */

.metric-card {
  position: relative;
  overflow: hidden;
  padding: 15px 16px;
  border-radius: 8px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.9), rgba(246, 249, 254, 0.9));
  border: 1px solid rgba(21, 105, 222, 0.1);
  box-shadow:
    0 5px 14px rgba(15, 66, 125, 0.04),
    inset 0 1px 0 rgba(255, 255, 255, 0.84);
  transition:
    background-color var(--app-motion-normal) var(--app-ease-out),
    border-color var(--app-motion-normal) var(--app-ease-out),
    box-shadow var(--app-motion-normal) var(--app-ease-out),
    transform var(--app-motion-fast) var(--app-ease-press);
  display: flex;
  flex-direction: column;
  justify-content: space-between;

  .label-text {
    color: #40566f;
    font-size: 13.5px;
    font-weight: 700;
    line-height: 1.2;
  }

  &:hover {
    transform: translateY(-1px);
    border-color: rgba(21, 105, 222, 0.2);
    background: rgba(255, 255, 255, 0.96);
    box-shadow: 0 10px 22px rgba(15, 66, 125, 0.08);
  }
}

/* 1. Hero Card: 线网总规模 */

.hero-card {
  min-height: 94px;
  background:
    linear-gradient(135deg, rgba(21, 105, 222, 0.09), rgba(255, 255, 255, 0.9) 48%, rgba(13, 148, 136, 0.08));
  border: 1px solid rgba(21, 105, 222, 0.14);

  .label-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 6px;
  }

  .value-row {
    display: flex;
    align-items: baseline;
    gap: 4px;

    .hero-num {
      color: var(--app-blue-strong);
      font-family: var(--app-font-number);
      font-size: 34px;
      font-weight: 700;
      line-height: 1.1;
      letter-spacing: 0;
    }

    .hero-unit {
      color: rgba(18, 48, 79, 0.7);
      font-size: 15px;
      font-weight: 700;
    }
  }

  &:hover {
    border-color: rgba(21, 105, 222, 0.3);
    background: rgba(246, 250, 255, 0.99);
  }
}

/* 2. Grid Layout */

.metric-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

/* 3. Density Card */

.density-card {
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  padding: 13px 16px;
  min-height: 48px;

  .card-left {
    display: flex;
    align-items: center;
    gap: 8px;

    .card-icon {
      width: 16px;
      height: 16px;
      color: rgba(21, 105, 222, 0.7);
      display: flex;
      align-items: center;
      justify-content: center;
    }
  }

  .card-right {
    display: flex;
    align-items: baseline;
    gap: 3px;

    .num-val {
      color: var(--app-blue-strong);
      font-family: var(--app-font-number);
      font-size: 19px;
      font-weight: 700;
    }

    .unit-val {
      color: #6b7280;
      font-size: 12.5px;
      font-weight: 700;
    }
  }
}

/* 4. Coverage Card */

.coverage-card {
  padding: 15px 16px;
  background: rgba(255, 255, 255, 0.88);

  .card-title-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 12px;
  }

  .coverage-bar-group {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .coverage-bar-item {
    display: flex;
    flex-direction: column;
    gap: 6px;

    .bar-label-row {
      display: flex;
      align-items: center;
      justify-content: space-between;

      span {
        color: #4b5563;
        font-size: 13.5px;
        font-weight: 600;
      }

      strong {
        color: var(--app-blue-strong);
        font-family: var(--app-font-number);
        font-size: 16px;
        font-weight: 700;
      }
    }

    .progress-track {
      width: 100%;
      height: 5px;
      border-radius: 999px;
      background: rgba(21, 105, 222, 0.05);
      overflow: hidden;
    }

    .progress-fill {
      height: 100%;
      border-radius: 999px;

      &.fill-300 {
        background: linear-gradient(90deg, rgba(21, 105, 222, 0.7) 0%, rgba(21, 105, 222, 0.95) 100%);
        box-shadow: 0 0 6px rgba(21, 105, 222, 0.2);
      }

      &.fill-500 {
        background: linear-gradient(90deg, rgba(13, 148, 136, 0.7) 0%, rgba(13, 148, 136, 0.95) 100%);
        box-shadow: 0 0 6px rgba(13, 148, 136, 0.2);
      }
    }
  }
}

.load-error {
  margin: 12px 0 0;
  color: var(--app-coral);
  font-size: 12px;
  font-weight: 600;
}

/* 数据总览 · 加载/空/错误状态机（下沉自标题栏浮标，整块替换面板体） */
.overview-state {
  flex: 1 1 auto;
  min-height: 0;
  margin-top: 10px;
}

.overview-skeleton {
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: hidden;
}

.sk-block {
  border-radius: var(--dm2-radius);
  background: var(--dm2-surface-sunken);
}

.sk-hero {
  height: 96px;
}

.sk-strip {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.sk-strip .sk-block {
  height: 62px;
}

.sk-card {
  height: 84px;
}

.sk-table {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 2px;
}

.sk-row {
  height: 34px;
  border-radius: 8px;
}

.sk-shimmer {
  background:
    linear-gradient(100deg, rgba(17, 32, 58, 0.05) 8%, rgba(17, 32, 58, 0.1) 20%, rgba(17, 32, 58, 0.05) 33%),
    var(--dm2-surface-sunken);
  background-size: 220% 100%;
  animation: overviewShimmer 1.35s var(--dm2-ease) infinite;
}

@keyframes overviewShimmer {
  from {
    background-position: 180% 0;
  }
  to {
    background-position: -60% 0;
  }
}

.overview-status {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 32px 22px;
  text-align: center;
}

.overview-status-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 14px;
  color: var(--dm2-accent);
  background: var(--dm2-accent-weak);
}

.overview-status-icon svg {
  width: 22px;
  height: 22px;
}

.overview-status-icon.is-error {
  color: var(--dm2-delete);
  background: var(--dm2-delete-weak);
}

.overview-status-title {
  margin: 2px 0 0;
  color: var(--dm2-ink);
  font-size: 14px;
  font-weight: 700;
}

.overview-status-desc {
  margin: 0;
  max-width: 260px;
  color: var(--dm2-muted);
  font-size: 12px;
  line-height: 1.6;
}

.overview-status-retry {
  margin-top: 6px;
  padding: 8px 20px;
  border: 0;
  border-radius: var(--dm2-radius-pill);
  background: var(--dm2-accent);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  box-shadow: var(--dm2-accent-glow);
  transition:
    background-color var(--dm2-dur) var(--dm2-ease),
    transform var(--dm2-dur-fast) var(--dm2-ease);
}

.overview-status-retry:hover {
  background: var(--dm2-accent-strong);
}

.overview-status-retry:active {
  transform: translateY(1px);
}

.overview-status-retry:focus-visible {
  outline: 2px solid var(--dm2-accent-ring);
  outline-offset: 2px;
}

@media (prefers-reduced-motion: reduce) {
  .sk-shimmer {
    animation: none;
    background: var(--dm2-surface-sunken);
  }
}

/* ── 建成区面积设置弹窗（teleport 到 body；组件 scope 属性仍随之下发，作用域样式生效） ── */
.built-up-backdrop {
  position: fixed;
  inset: 0;
  z-index: 2600;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(13, 27, 51, 0.42);
  -webkit-backdrop-filter: blur(2px);
  backdrop-filter: blur(2px);
  animation: builtUpFade 160ms var(--dm2-ease);
}

.built-up-modal {
  width: 360px;
  max-width: calc(100vw - 48px);
  box-sizing: border-box;
  padding: 20px;
  border: 1px solid var(--dm2-line);
  border-radius: var(--dm2-radius-lg);
  background: var(--dm2-surface);
  box-shadow: var(--dm2-shadow-dialog);
  color: var(--dm2-ink);
  font-family: var(--dm2-font);
  animation: builtUpPop 200ms var(--dm2-ease-out);
}

@keyframes builtUpFade {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

@keyframes builtUpPop {
  from {
    opacity: 0;
    transform: translateY(8px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.built-up-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.built-up-kicker {
  margin: 0 0 3px;
  color: var(--dm2-muted);
  font-size: 11px;
  font-weight: 600;
}

.built-up-head-text h3 {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  color: var(--dm2-ink);
}

.built-up-close {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--dm2-muted);
  cursor: pointer;
  transition:
    background-color var(--dm2-dur) var(--dm2-ease),
    color var(--dm2-dur) var(--dm2-ease);
}

.built-up-close:hover {
  background: var(--dm2-field);
  color: var(--dm2-ink);
}

.built-up-desc {
  margin: 14px 0 0;
  color: var(--dm2-ink-soft);
  font-size: 12.5px;
  line-height: 1.6;
}

.built-up-desc strong {
  color: var(--dm2-accent);
  font-family: var(--dm2-font-num);
  font-weight: 700;
}

.built-up-scope {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}

.built-up-scope-tag {
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--dm2-field);
  color: var(--dm2-muted);
  font-size: 11px;
  font-weight: 600;
}

.built-up-scope-name {
  color: var(--dm2-ink);
  font-size: 13px;
  font-weight: 700;
}

.built-up-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 14px;
}

.built-up-field-label {
  color: var(--dm2-muted);
  font-size: 12px;
  font-weight: 600;
}

.built-up-input {
  width: 100%;
  box-sizing: border-box;
  height: 40px;
  padding: 0 12px;
  border: 1px solid var(--dm2-line-strong);
  border-radius: var(--dm2-radius-sm);
  background: var(--dm2-field);
  color: var(--dm2-ink);
  font-family: var(--dm2-font-num);
  font-size: 15px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  transition:
    border-color var(--dm2-dur) var(--dm2-ease),
    box-shadow var(--dm2-dur) var(--dm2-ease),
    background-color var(--dm2-dur) var(--dm2-ease);
}

.built-up-input:focus {
  outline: none;
  border-color: var(--dm2-accent);
  background: var(--dm2-surface);
  box-shadow: 0 0 0 3px var(--dm2-accent-ring);
}

.built-up-preview {
  display: flex;
  align-items: stretch;
  margin-top: 14px;
  border: 1px solid var(--dm2-line);
  border-radius: var(--dm2-radius-sm);
  background: var(--dm2-surface-sunken);
  overflow: hidden;
}

.built-up-preview.is-missing {
  opacity: 0.6;
}

.built-up-preview-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  padding: 10px 6px;
}

.built-up-preview-item span {
  color: var(--dm2-muted);
  font-size: 11px;
  font-weight: 600;
}

.built-up-preview-item strong {
  color: var(--dm2-accent);
  font-family: var(--dm2-font-num);
  font-size: 18px;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
}

.built-up-preview-divider {
  width: 1px;
  background: var(--dm2-line-faint);
}

.built-up-warn {
  margin: 10px 0 0;
  color: var(--dm2-modify);
  font-size: 11.5px;
  line-height: 1.5;
}

.built-up-note {
  margin: 10px 0 0;
  color: var(--dm2-muted);
  font-size: 11.5px;
  line-height: 1.5;
}

.built-up-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 18px;
}

.built-up-actions-right {
  display: inline-flex;
  gap: 8px;
}

.built-up-btn {
  height: 34px;
  padding: 0 16px;
  border: 1px solid var(--dm2-line-strong);
  border-radius: var(--dm2-radius-sm);
  background: var(--dm2-surface);
  color: var(--dm2-ink-soft);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition:
    background-color var(--dm2-dur) var(--dm2-ease),
    border-color var(--dm2-dur) var(--dm2-ease),
    color var(--dm2-dur) var(--dm2-ease);
}

.built-up-btn:hover {
  background: var(--dm2-field);
}

.built-up-btn.ghost {
  border-color: transparent;
  background: transparent;
  color: var(--dm2-muted);
}

.built-up-btn.ghost:hover {
  color: var(--dm2-delete);
  background: var(--dm2-delete-weak);
}

.built-up-btn.primary {
  border-color: transparent;
  background: var(--dm2-accent);
  color: #fff;
  box-shadow: var(--dm2-accent-glow);
}

.built-up-btn.primary:hover {
  background: var(--dm2-accent-strong);
}

.built-up-btn:focus-visible {
  outline: 2px solid var(--dm2-accent-ring);
  outline-offset: 2px;
}

@media (prefers-reduced-motion: reduce) {
  .built-up-backdrop,
  .built-up-modal {
    animation: none;
  }
}

.detail-close-btn {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(245, 158, 11, 0.22);
  border-radius: 7px;
  background: rgba(255, 251, 235, 0.78);
  color: #b45309;
  cursor: pointer;
  transition:
    background-color 0.18s ease,
    border-color 0.18s ease,
    color 0.18s ease,
    transform 0.14s ease;

  &:hover {
    background: #fff7d6;
    border-color: rgba(245, 158, 11, 0.42);
    color: #92400e;
  }

  &:active {
    transform: translateY(1px);
  }
}

.station-detail-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  gap: 12px;
  margin-top: 14px;
}

.overview-station-title {
  margin: 0;
  color: #10243f;
  font-size: 20px;
  line-height: 1.3;
  font-weight: 700;
  letter-spacing: -0.01em;
}

.ranking-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.detail-summary-card {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;

  > div {
    min-width: 0;
    display: grid;
    gap: 4px;
    padding: 11px 12px;
    border-radius: 8px;
    background: rgba(255, 255, 255, 0.78);
    border: 1px solid rgba(21, 105, 222, 0.1);
    box-shadow:
      0 4px 12px rgba(15, 66, 125, 0.035),
      inset 0 1px 0 rgba(255, 255, 255, 0.76);
  }

  span {
    color: #64748b;
    font-size: 11px;
    line-height: 1.2;
    font-weight: 600;
  }

  strong {
    min-width: 0;
    color: #12304f;
    font-size: 14px;
    line-height: 1.25;
    font-weight: 700;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.ranking-header {
  display: flex;
  align-items: center;
  padding: 9px 12px;
  background: rgba(21, 105, 222, 0.06);
  border: 1px solid rgba(21, 105, 222, 0.08);
  border-radius: 8px;
  margin-bottom: 8px;

  span {
    font-size: 11px;
    font-weight: 700;
    color: #64748b;
    letter-spacing: 0.02em;
    text-transform: uppercase;
  }
}

.ranking-scroll-list {
  flex: 1;
  overflow-y: auto;
  padding-right: 2px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  scrollbar-width: thin;
  scrollbar-color: rgba(21, 105, 222, 0.15) transparent;

  &::-webkit-scrollbar {
    width: 4px;
  }
  &::-webkit-scrollbar-thumb {
    background: rgba(21, 105, 222, 0.15);
    border-radius: 10px;
  }
  &::-webkit-scrollbar-track {
    background: transparent;
  }
}

.ranking-row {
  width: 100%;
  display: flex;
  align-items: center;
  padding: 10px 12px;
  background: rgba(255, 255, 255, 0.84);
  border-radius: 8px;
  border: 1px solid rgba(21, 105, 222, 0.08);
  box-shadow: 0 3px 10px rgba(15, 66, 125, 0.03);
  color: inherit;
  font-family: inherit;
  text-align: left;
  cursor: default;
  transition:
    background-color var(--app-motion-normal) var(--app-ease-out),
    border-color var(--app-motion-normal) var(--app-ease-out),
    box-shadow var(--app-motion-normal) var(--app-ease-out),
    transform var(--app-motion-fast) var(--app-ease-press);

  &.is-clickable {
    cursor: pointer;
  }

  &.is-disabled {
    opacity: 0.72;
  }

  &.is-clickable:hover {
    transform: translateX(-1px);
    background: rgba(255, 255, 255, 0.96);
    border-color: rgba(21, 105, 222, 0.18);
    box-shadow: 0 8px 18px rgba(15, 66, 125, 0.07);
  }
}

.col-rank {
  width: 36px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: flex-start;
}

.col-name {
  flex-grow: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-right: 8px;
  min-width: 0;
}

.col-flow {
  width: 80px;
  flex-shrink: 0;
  display: flex;
  align-items: baseline;
  justify-content: flex-end;
  gap: 2px;
}

.rank-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  font-size: 11px;
  font-weight: 700;
  color: #60758e;
  background: rgba(113, 128, 150, 0.1);
  border: 1px solid rgba(113, 128, 150, 0.1);

  &.gold {
    background: #d97706;
    color: #ffffff;
    font-size: 11px;
  }

  &.silver {
    background: #94a3b8;
    color: #ffffff;
    font-size: 11px;
  }

  &.bronze {
    background: #ea580c;
    color: #ffffff;
    font-size: 11px;
  }
}

.route-name-text {
  font-size: 13.5px;
  font-weight: 700;
  color: #1e293b;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.flow-value {
  font-size: 14.5px;
  font-weight: 700;
  color: #0d9488;
  font-family: var(--app-font-number);
  
  .ranking-row:nth-child(-n+3) & {
    color: #d97706;
  }
}

.flow-unit {
  font-size: 10px;
  color: #64748b;
  font-weight: 600;
}

.station-route-empty {
  margin: 0;
  padding: 12px 14px;
  border-radius: 7px;
  background: #f8fafc;
  color: #64748b;
  font-size: 13px;
  font-weight: 500;
  text-align: center;
  border: 1px dashed rgba(21, 105, 222, 0.1);
}

.depot-detail-panel {
  flex: 1;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: rgba(21, 105, 222, 0.18) transparent;
  display: flex;
  flex-direction: column;
  min-height: 0;
  gap: 12px;
  margin-top: 14px;
  padding-right: 2px;

  &::-webkit-scrollbar {
    width: 4px;
  }

  &::-webkit-scrollbar-thumb {
    background: rgba(21, 105, 222, 0.18);
    border-radius: 10px;
  }
}

.depot-locate {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 12px 14px;
  border-radius: 11px;
  border: 1px solid rgba(21, 105, 222, 0.14);
  background:
    linear-gradient(135deg, rgba(47, 111, 255, 0.09), rgba(47, 111, 255, 0.015));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.7);
}

.depot-locate-icon {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  flex-shrink: 0;
  border-radius: 9px;
  color: #1d4ed8;
  background: rgba(255, 255, 255, 0.86);
  border: 1px solid rgba(21, 105, 222, 0.16);

  svg {
    width: 17px;
    height: 17px;
  }
}

.depot-locate-text {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.depot-locate-label {
  color: #5a76a0;
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
}

.depot-locate-value {
  color: #14336b;
  font-size: 13px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.01em;
  font-feature-settings: "tnum" 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.depot-fact-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1px;
  border-radius: 12px;
  overflow: hidden;
  background: rgba(21, 105, 222, 0.1);
  border: 1px solid rgba(21, 105, 222, 0.12);
  box-shadow: 0 8px 22px rgba(15, 66, 125, 0.05);
}

.depot-fact {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  padding: 9px 12px;
  background: #fbfdff;

  &.is-wide {
    grid-column: 1 / -1;
  }
}

.depot-fact-label {
  color: #5a76a0;
  font-size: 10.5px;
  font-weight: 600;
  line-height: 1.3;
  letter-spacing: 0.01em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.depot-fact-value {
  min-width: 0;
  color: #11305a;
  font-size: 12.5px;
  font-weight: 700;
  line-height: 1.4;
  word-break: break-word;
  font-variant-numeric: tabular-nums;
}

.depot-fact.is-wide .depot-fact-value {
  font-weight: 600;
  color: #1c3b66;
}

.route-detail-panel {
  flex: 1;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: rgba(21, 105, 222, 0.18) transparent;
  display: flex;
  flex-direction: column;
  min-height: 0;
  gap: 12px;
  margin-top: 14px;
  padding-right: 2px;

  &::-webkit-scrollbar {
    width: 4px;
  }

  &::-webkit-scrollbar-thumb {
    background: rgba(21, 105, 222, 0.18);
    border-radius: 10px;
  }
}

.metrics-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 9px;
  margin-bottom: 6px;
  
  .metric-card {
    background: rgba(255, 255, 255, 0.86);
    border: 1px solid rgba(21, 105, 222, 0.1);
    border-radius: 8px;
    padding: 11px 12px;
    display: flex;
    flex-direction: column;
    gap: 3px;
    box-sizing: border-box;
    box-shadow: 0 3px 10px rgba(15, 66, 125, 0.03);
    transition:
      border-color var(--app-motion-normal) var(--app-ease-out),
      box-shadow var(--app-motion-normal) var(--app-ease-out),
      transform var(--app-motion-fast) var(--app-ease-press);
    
    &:hover {
      transform: translateY(-1px);
      border-color: rgba(21, 105, 222, 0.22);
      box-shadow: 0 8px 18px rgba(15, 66, 125, 0.07);
    }
    
    .label {
      font-size: 11px;
      color: #64748b;
      font-weight: 600;
    }
    
    .value {
      font-size: 15.5px;
      font-weight: 700;
      color: var(--app-blue);
      font-family: var(--app-font-number);
    }
  }
}

.stations-section {
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 13px;
  border: 1px solid rgba(21, 105, 222, 0.1);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.7);
  box-shadow: 0 4px 12px rgba(15, 66, 125, 0.035);
  
  .section-title {
    font-size: 13.5px;
    font-weight: 700;
    color: #12304f;
    margin-bottom: 12px;
  }
  
  .station-scroll-list {
    max-height: 380px;
    overflow-y: auto;
    padding-right: 4px;
    scrollbar-width: thin;
    scrollbar-color: rgba(21, 105, 222, 0.15) transparent;

    &::-webkit-scrollbar {
      width: 4px;
    }
    &::-webkit-scrollbar-thumb {
      background: rgba(21, 105, 222, 0.15);
      border-radius: 10px;
    }
    &::-webkit-scrollbar-track {
      background: transparent;
    }
  }
}

.timeline-container {
  display: flex;
  flex-direction: column;
  padding-left: 6px;
  
  .timeline-item {
    display: flex;
    align-items: flex-start;
    gap: 12px;
    padding-bottom: 14px;
    position: relative;
    
    &:hover {
      .timeline-content .station-name {
        color: var(--app-blue);
      }
      .timeline-dot {
        border-color: var(--app-blue);
        background: var(--app-blue);
      }
    }
    
    &:not(:last-child)::after {
      content: "";
      position: absolute;
      left: 6px;
      top: 12px;
      bottom: -4px;
      width: 2px;
      background-color: rgba(21, 105, 222, 0.15);
    }
    
    .timeline-dot {
      width: 14px;
      height: 14px;
      border: 2px solid rgba(21, 105, 222, 0.4);
      border-radius: 50%;
      background: #ffffff;
      display: flex;
      align-items: center;
      justify-content: center;
      margin-top: 2px;
      z-index: 1;
      transition:
        background-color var(--app-motion-normal) var(--app-ease-out),
        border-color var(--app-motion-normal) var(--app-ease-out);
      
      &.first {
        border-color: #0d9488;
        .dot-inner { background: #0d9488; }
      }
      &.last {
        border-color: #dc4c5d;
        .dot-inner { background: #dc4c5d; }
      }
      
      .dot-inner {
        width: 6px;
        height: 6px;
        border-radius: 50%;
        background: transparent;
      }
    }
    
    .timeline-content {
      display: flex;
      flex-direction: column;
      gap: 1px;
      min-width: 0;
      padding: 3px 0 0;
      
      .station-name {
        font-size: 13px;
        font-weight: 600;
        color: #1e293b;
        transition: color 0.2s ease;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      
      .station-idx {
        font-size: 10px;
        color: #64748b;
      }
    }
  }
}

.line-route-picker {
  position: fixed;
  z-index: calc(var(--z-panel) + 40);
  width: 292px;
  max-height: 320px;
  overflow-y: auto;
  padding: 10px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.94);
  backdrop-filter: blur(20px) saturate(180%);
  -webkit-backdrop-filter: blur(20px) saturate(180%);
  border: 1px solid rgba(21, 105, 222, 0.12);
  box-shadow: 
    0 16px 48px rgba(15, 39, 68, 0.16),
    0 4px 12px rgba(15, 39, 68, 0.04),
    inset 0 1px 0 rgba(255, 255, 255, 0.6);
  scrollbar-width: thin;
  scrollbar-color: rgba(21, 105, 222, 0.15) transparent;

  &::-webkit-scrollbar {
    width: 4px;
  }
  &::-webkit-scrollbar-thumb {
    background: rgba(21, 105, 222, 0.15);
    border-radius: 10px;
  }
  &::-webkit-scrollbar-track {
    background: transparent;
  }
}

.edit-action-menu {
  position: fixed;
  z-index: calc(var(--z-panel) + 45);
  width: 220px;
  max-height: 260px;
  overflow-y: auto;
  padding: 10px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid rgba(21, 105, 222, 0.14);
  box-shadow: 0 14px 34px rgba(15, 39, 68, 0.16);
  scrollbar-width: thin;
}

.picker-title {
  margin-bottom: 10px;
  padding-bottom: 6px;
  color: #64748b;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  border-bottom: 1px solid rgba(21, 105, 222, 0.08);
}

.picker-route-btn {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  margin-bottom: 4px;
  border: 1px solid rgba(21, 105, 222, 0.06);
  border-radius: 8px;
  background: #ffffff;
  color: #1e293b;
  text-align: left;
  cursor: pointer;
  box-shadow: 0 1px 3px rgba(15, 39, 68, 0.01);
  transition:
    background-color var(--app-motion-normal) var(--app-ease-out),
    border-color var(--app-motion-normal) var(--app-ease-out),
    box-shadow var(--app-motion-normal) var(--app-ease-out),
    transform var(--app-motion-fast) var(--app-ease-press);

  &:last-of-type {
    margin-bottom: 0;
  }

  .picker-icon-wrapper {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 26px;
    height: 26px;
    border-radius: 6px;
    background: rgba(21, 105, 222, 0.08);
    color: var(--app-blue);
    border: 1px solid rgba(21, 105, 222, 0.1);
    flex-shrink: 0;
    transition: transform 0.2s ease;

    .type-svg {
      width: 13px;
      height: 13px;
    }
  }

  .route-btn-meta {
    display: flex;
    flex-direction: column;
    min-width: 0;
    gap: 1px;
  }

  .route-btn-name {
    font-size: 13px;
    font-weight: 700;
    color: #1e293b;
  }

  .route-btn-desc {
    font-size: 10px;
    color: #64748b;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &:hover {
    background: linear-gradient(135deg, rgba(21, 105, 222, 0.06) 0%, rgba(21, 105, 222, 0.02) 100%);
    border-color: rgba(21, 105, 222, 0.22);
    transform: translateX(4px);

    .picker-icon-wrapper {
      transform: scale(1.05);
      background: rgba(21, 105, 222, 0.12);
    }
  }

  &.active {
    background: linear-gradient(135deg, rgba(21, 105, 222, 0.12), rgba(11, 145, 183, 0.07));
    border-color: rgba(21, 105, 222, 0.34);
    box-shadow:
      0 8px 20px rgba(21, 105, 222, 0.09),
      inset 3px 0 0 var(--app-blue);

    .picker-icon-wrapper {
      background: var(--app-blue);
      border-color: var(--app-blue);
      color: #ffffff;
    }

    .route-btn-name {
      color: var(--app-blue-strong);
    }
  }

  &:active {
    transform: translateX(2px);
  }
}

.picker-empty {
  margin: 0;
  padding: 10px;
  color: #64748b;
  font-size: 12px;
  font-weight: 500;
  text-align: center;
}

@media (max-width: 860px) {
.datebase_box {
    top: calc(var(--app-header-height) + var(--space-lg));
    right: var(--app-edge);
    max-width: calc(100vw - (var(--app-edge) * 2));
  }

.dm-overview-panel {
    right: var(--app-edge);
    width: min(320px, calc(100vw - 260px));
  }

.dm-edit-panel {
    right: var(--app-edge);
    width: min(320px, calc(100vw - 260px));
  }

.dm-history-page {
    left: 220px;
    padding: 18px;
  }

.history-content {
    grid-template-columns: 1fr;
  }

.history-preview-panel {
    left: 238px;
    width: min(380px, calc(100vw - 260px));
  }

.history-version-node {
    grid-template-columns: 20px minmax(0, 1fr);
  }

.history-version-side {
    grid-column: 2;
    align-items: flex-start;
    margin-top: -8px;
  }
}

/* Premium reskin for the data-management module. Kept as overrides to avoid touching behavior. */

.datebase_box,
.dm-overview-panel,
.dm-edit-panel,
.dm-history-page,
.history-preview-panel,
.line-route-picker,
.edit-action-menu {
  --dm-panel-scale: 0.94;
  --dm-font: "Satoshi", "Aptos", "PingFang SC", "Microsoft YaHei", system-ui, sans-serif;
  --dm-number-font: "DIN Alternate", "Aptos Mono", "SF Pro Display", "PingFang SC", system-ui, sans-serif;
  --dm-ink: #1f3132;
  --dm-ink-strong: #132323;
  --dm-muted: #687877;
  --dm-muted-soft: #8b9894;
  --dm-accent: #2f6f73;
  --dm-accent-strong: #214f52;
  --dm-accent-soft: rgba(47, 111, 115, 0.11);
  --dm-copper: #b88746;
  --dm-copper-soft: rgba(184, 135, 70, 0.14);
  --dm-paper: rgba(252, 250, 244, 0.96);
  --dm-paper-soft: rgba(246, 246, 239, 0.9);
  --dm-shell: rgba(40, 56, 55, 0.1);
  --dm-border: rgba(42, 59, 58, 0.14);
  --dm-border-strong: rgba(47, 111, 115, 0.28);
  --dm-shadow: 0 26px 70px rgba(24, 44, 45, 0.18), 0 6px 18px rgba(24, 44, 45, 0.07);
  --dm-shadow-soft: 0 16px 38px rgba(31, 49, 50, 0.1);
  --dm-ease: cubic-bezier(0.32, 0.72, 0, 1);
  font-family: var(--dm-font);
  color: var(--dm-ink);
}

.datebase_box,
.dm-overview-panel,
.dm-edit-panel,
.edit-action-menu,
.history-preview-panel {
  scale: var(--dm-panel-scale);
}

.datebase_box {
  top: calc(var(--app-header-height) / 2);
  right: calc(var(--app-edge) + 70px);
  gap: 10px;
  padding: 4px 5px 4px 12px;
  border: 1px solid rgba(42, 59, 58, 0.1);
  border-radius: 999px;
  background: rgba(252, 250, 244, 0.84);
  box-shadow: 0 12px 28px rgba(31, 49, 50, 0.1), inset 0 1px 0 rgba(255, 255, 255, 0.74);
}

.datebase_box .handle {
  color: var(--dm-ink);
  font-size: 12px;
  font-weight: 700;
}

.datebase_box .el-select {
  width: clamp(148px, 13vw, 208px);
}

.datebase_box .el-select :deep(.el-input__wrapper) {
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.76) !important;
  box-shadow: inset 0 0 0 1px rgba(47, 111, 115, 0.12) !important;
}

.datebase_box .el-select :deep(.el-input__wrapper:hover),
.datebase_box .el-select :deep(.el-input__wrapper.is-focus) {
  box-shadow: inset 0 0 0 1px var(--dm-border-strong), 0 0 0 4px rgba(47, 111, 115, 0.08) !important;
}

.line-route-picker,
.edit-action-menu {
  border: 1px solid rgba(42, 59, 58, 0.14);
  border-radius: 18px;
  background: rgba(252, 250, 244, 0.97);
  box-shadow: 0 22px 54px rgba(31, 49, 50, 0.17), inset 0 1px 0 rgba(255, 255, 255, 0.72);
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}

.picker-route-btn {
  border-radius: 13px;
  color: var(--dm-ink);
  transition:
    background-color 320ms var(--dm-ease),
    border-color 320ms var(--dm-ease),
    box-shadow 320ms var(--dm-ease),
    transform 260ms var(--dm-ease);
}

.picker-route-btn:hover {
  background: rgba(47, 111, 115, 0.08);
  transform: translateX(3px);
}

.picker-route-btn .picker-icon-wrapper {
  border-color: rgba(47, 111, 115, 0.14);
  background: rgba(47, 111, 115, 0.09);
  color: var(--dm-accent);
}

.dm-overview-panel,
.dm-edit-panel {
  top: calc(var(--app-header-height) + 14px);
  right: var(--app-edge);
  width: 410px;
  height: calc((100vh - var(--app-header-height) - 30px) / var(--dm-panel-scale));
  padding: 18px;
  border: 1px solid rgba(42, 59, 58, 0.14);
  border-radius: 26px;
  background:
    linear-gradient(145deg, rgba(52, 70, 69, 0.12), rgba(255, 255, 255, 0.04)),
    rgba(252, 250, 244, 0.64);
  box-shadow: var(--dm-shadow);
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
  transition:
    transform 560ms var(--dm-ease),
    opacity 360ms var(--dm-ease),
    box-shadow 360ms var(--dm-ease);
}

.dm-overview-panel::before,
.dm-edit-panel::before {
  inset: 0;
  height: auto;
  border-radius: inherit;
  background:
    linear-gradient(155deg, rgba(255, 255, 255, 0.8), rgba(238, 239, 229, 0.76)),
    repeating-linear-gradient(135deg, rgba(31, 49, 50, 0.025) 0 1px, transparent 1px 8px);
  opacity: 1;
}

.dm-overview-panel::after,
.dm-edit-panel::after {
  inset: 6px;
  height: auto;
  border-radius: 20px;
  background: linear-gradient(180deg, rgba(255, 255, 252, 0.92), rgba(246, 247, 239, 0.92));
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.78),
    inset 0 0 0 1px rgba(42, 59, 58, 0.07);
}

.overview-title-row {
  padding: 2px 2px 16px;
  border-bottom: 1px solid rgba(42, 59, 58, 0.09);
}

.overview-title-row .panel-kicker,
.history-header .panel-kicker,
.history-preview-head .panel-kicker {
  width: fit-content;
  margin-bottom: 7px;
  padding: 3px 8px;
  border-radius: 999px;
  color: var(--dm-accent-strong);
  background: rgba(47, 111, 115, 0.09);
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.overview-title-row h2,
.overview-station-title,
.history-preview-head h2 {
  color: var(--dm-ink-strong);
  font-weight: 700;
  letter-spacing: -0.015em;
  text-wrap: balance;
}

.overview-title-row .detail-title-block span,
.history-preview-head span {
  color: var(--dm-muted);
}

.detail-close-btn {
  border: 1px solid rgba(184, 135, 70, 0.25);
  border-radius: 12px;
  background: rgba(184, 135, 70, 0.11);
  color: #8f642b;
}

.detail-close-btn:hover {
  background: rgba(184, 135, 70, 0.18);
  border-color: rgba(184, 135, 70, 0.36);
  transform: translateY(-1px);
}

.overview-metric-list,
.route-detail-panel,
.ranking-scroll-list,
.edit-operation-list {
  scrollbar-color: rgba(47, 111, 115, 0.2) transparent;
}

.metric-card,
.detail-summary-card > div,
.ranking-header,
.ranking-row,
.stations-section,
.edit-operation-item,
.edit-empty,
.history-preview-group {
  border: 1px solid rgba(42, 59, 58, 0.11);
  border-radius: 18px;
  background: rgba(255, 255, 252, 0.72);
  box-shadow:
    0 12px 28px rgba(31, 49, 50, 0.07),
    inset 0 1px 0 rgba(255, 255, 255, 0.72);
  transition:
    background-color 360ms var(--dm-ease),
    border-color 360ms var(--dm-ease),
    box-shadow 360ms var(--dm-ease),
    transform 280ms var(--dm-ease);
}

.metric-card:hover,
.ranking-row.is-clickable:hover,
.edit-operation-item:hover {
  transform: translateY(-2px);
  border-color: rgba(47, 111, 115, 0.24);
  box-shadow: 0 18px 38px rgba(31, 49, 50, 0.1), inset 0 1px 0 rgba(255, 255, 255, 0.82);
}

.metric-card .label-text,
.detail-summary-card span,
.metrics-grid .metric-card .label,
.coverage-card .bar-label-row span,
.overview-source-note,
.flow-unit,
.station-route-empty {
  color: var(--dm-muted);
}

.hero-card .label-row .metric-note,
.coverage-card .card-title-row .metric-note {
  color: rgba(184, 135, 70, 0.82);
}

.hero-card .value-row .hero-num,
.grid-card .grid-num,
.density-card .card-right .num-val,
.metrics-grid .metric-card .value {
  color: var(--dm-accent-strong);
  font-family: var(--dm-number-font);
  letter-spacing: -0.02em;
}

.hero-card .value-row .hero-num {
  font-size: 38px;
}

.hero-card .value-row .hero-unit,
.density-card .card-right .unit-val,
.grid-card .grid-unit {
  color: var(--dm-muted);
}

.grid-card .card-header .card-icon,
.density-card .card-left .card-icon {
  color: var(--dm-accent);
}

.grid-card.stations-card .grid-num,
.flow-value {
  color: var(--dm-accent);
}

.coverage-card .progress-track {
  height: 8px;
  background: rgba(47, 111, 115, 0.08);
  box-shadow: inset 0 1px 2px rgba(31, 49, 50, 0.08);
}

.coverage-card .progress-fill.fill-300 {
  background: linear-gradient(90deg, var(--dm-accent), #5e8e78);
  box-shadow: 0 0 14px rgba(47, 111, 115, 0.16);
}

.coverage-card .progress-fill.fill-500 {
  background: linear-gradient(90deg, var(--dm-copper), #c9a76b);
  box-shadow: 0 0 14px rgba(184, 135, 70, 0.16);
}

.metrics-grid {
  gap: 10px;
}

.metrics-grid .metric-card {
  border-radius: 16px;
  background: rgba(255, 255, 252, 0.68);
}

.stations-section .section-title,
.history-preview-group-title h3,
.route-name-text,
.detail-summary-card strong {
  color: var(--dm-ink-strong);
}

.timeline-container .timeline-item::after {
  background-color: rgba(47, 111, 115, 0.18) !important;
}

.timeline-container .timeline-item .timeline-dot {
  border-color: rgba(47, 111, 115, 0.38);
}

.timeline-container .timeline-item .timeline-dot.first {
  border-color: var(--dm-accent);
}

.timeline-container .timeline-item .timeline-dot.last {
  border-color: var(--dm-copper);
}

.timeline-container .timeline-item .timeline-dot.first .dot-inner {
  background: var(--dm-accent);
}

.timeline-container .timeline-item .timeline-dot.last .dot-inner {
  background: var(--dm-copper);
}

.ranking-header {
  background: rgba(47, 111, 115, 0.08);
}

.rank-badge {
  color: var(--dm-muted);
  background: rgba(47, 111, 115, 0.08);
  border-color: rgba(47, 111, 115, 0.1);
}

.rank-badge.gold,
.rank-badge.silver,
.rank-badge.bronze {
  background: var(--dm-copper);
  color: #fffaf1;
}

.edit-empty li::before {
  border-radius: 8px;
  background: rgba(47, 111, 115, 0.1);
  color: var(--dm-accent-strong);
}

.edit-operation-item::before {
  background: linear-gradient(180deg, var(--dm-copper), var(--dm-accent));
}

.edit-operation-item .operation-type {
  border-radius: 999px;
  background: var(--dm-copper-soft);
  color: #8f642b;
}

.edit-panel-actions {
  border-top-color: rgba(42, 59, 58, 0.09);
}

.picker-title {
  color: var(--dm-ink);
}

.dm-history-page {
  left: 286px;
  right: 16px;
  top: calc(var(--app-header-height) + 14px);
  bottom: 16px;
  gap: 18px;
  padding: 24px;
  border: 1px solid rgba(42, 59, 58, 0.12);
  border-radius: 26px;
  background:
    linear-gradient(135deg, rgba(255, 255, 252, 0.96), rgba(241, 243, 235, 0.94)),
    repeating-linear-gradient(135deg, rgba(31, 49, 50, 0.026) 0 1px, transparent 1px 9px);
  box-shadow: var(--dm-shadow);
}

.history-version-node.active-data .history-version-main {
  background: rgba(235, 246, 239, 0.86);
  border-color: rgba(47, 111, 115, 0.36);
}

.history-version-node.active-data .history-timeline-dot,
.history-current-tag {
  background: var(--dm-accent);
}

.history-preview-panel {
  left: 288px;
  top: calc(var(--app-header-height) + 76px);
  width: 460px;
  max-height: calc(100vh - var(--app-header-height) - 108px);
  padding: 16px;
  border: 1px solid rgba(42, 59, 58, 0.13);
  border-radius: 24px;
  background: rgba(252, 250, 244, 0.96);
  box-shadow: var(--dm-shadow);
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}

.history-preview-head {
  border-bottom-color: rgba(42, 59, 58, 0.09);
}

.history-preview-group :deep(.el-table) {
  --el-table-border-color: rgba(42, 59, 58, 0.1);
  --el-table-header-bg-color: rgba(47, 111, 115, 0.07);
  --el-table-row-hover-bg-color: rgba(47, 111, 115, 0.05);
  color: var(--dm-ink);
}

.picker-route-btn.active {
  background: linear-gradient(135deg, rgba(47, 111, 115, 0.14), rgba(184, 135, 70, 0.1));
  border-color: rgba(47, 111, 115, 0.28);
  box-shadow: inset 3px 0 0 var(--dm-copper), 0 12px 26px rgba(31, 49, 50, 0.08);
}

.picker-route-btn.active .picker-icon-wrapper {
  background: var(--dm-accent);
  border-color: var(--dm-accent);
}

@keyframes dm-panel-arrive {
  from {
    opacity: 0;
    transform: translateY(14px) scale(0.985);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

.dm-overview-panel,
.dm-edit-panel,
.dm-history-page,
.history-preview-panel,
.map-search,
.map-controls-toolbar {
  animation: dm-panel-arrive 640ms var(--dm-ease) both;
}

@media (max-width: 860px) {
.dm-overview-panel,
.dm-edit-panel {
    width: min(350px, calc(100vw - 260px));
  }

.dm-history-page {
    left: 244px;
    padding: 18px;
  }

.history-content {
    grid-template-columns: 1fr;
  }

.history-preview-panel {
    left: 244px;
    width: min(400px, calc(100vw - 268px));
  }

.history-version-node {
    grid-template-columns: 22px minmax(0, 1fr);
  }
}

@media (max-width: 720px) {
.history-preview-panel,
.dm-overview-panel,
.dm-edit-panel,
.dm-history-page {
    left: 10px;
    right: 10px;
    width: auto;
  }

.dm-overview-panel,
.dm-edit-panel {
    top: calc(var(--app-header-height) + 230px);
    height: calc(100vh - var(--app-header-height) - 250px);
  }

.dm-history-page {
    top: calc(var(--app-header-height) + 230px);
  }

.history-current-version,
.history-content {
    grid-template-columns: 1fr;
  }
}

@media (prefers-reduced-motion: reduce) {
.dm-overview-panel,
.dm-edit-panel,
.dm-history-page,
.history-preview-panel {
    animation: none;
  }
}

/* User-requested correction: full-height left rail and cooler unified surfaces. */

.datebase_box,
.dm-overview-panel,
.dm-edit-panel,
.dm-history-page,
.history-preview-panel,
.line-route-picker,
.edit-action-menu {
  --dm-panel-scale: 0.92;
  --dm-ink: #223134;
  --dm-ink-strong: #142326;
  --dm-muted: #657377;
  --dm-muted-soft: #8c989b;
  --dm-accent: #2f6f73;
  --dm-accent-strong: #204f53;
  --dm-accent-soft: rgba(47, 111, 115, 0.1);
  --dm-secondary: #315d8a;
  --dm-secondary-soft: rgba(49, 93, 138, 0.1);
  --dm-copper: var(--dm-secondary);
  --dm-copper-soft: var(--dm-secondary-soft);
  --dm-paper: rgba(249, 252, 253, 0.96);
  --dm-paper-soft: rgba(242, 247, 249, 0.92);
  --dm-shell: rgba(34, 49, 52, 0.07);
  --dm-border: rgba(35, 50, 55, 0.13);
  --dm-border-strong: rgba(47, 111, 115, 0.28);
  --dm-shadow: 0 22px 60px rgba(24, 43, 50, 0.16), 0 4px 14px rgba(24, 43, 50, 0.06);
}

.datebase_box {
  background: rgba(249, 252, 253, 0.92);
  border-color: rgba(35, 50, 55, 0.1);
  box-shadow: 0 12px 28px rgba(24, 43, 50, 0.09), inset 0 1px 0 rgba(255, 255, 255, 0.74);
}

.line-route-picker,
.edit-action-menu {
  background: rgba(249, 252, 253, 0.96);
  border-color: rgba(35, 50, 55, 0.12);
}

.detail-close-btn,
.detail-close-btn:hover,
.edit-operation-item .operation-type {
  background: var(--dm-secondary-soft);
  border-color: rgba(49, 93, 138, 0.22);
  color: var(--dm-secondary);
}

.dm-overview-panel,
.dm-edit-panel {
  width: 400px;
  border-color: rgba(35, 50, 55, 0.12);
  background:
    linear-gradient(145deg, rgba(41, 57, 61, 0.08), rgba(255, 255, 255, 0.03)),
    rgba(249, 252, 253, 0.76);
}

.dm-overview-panel::before,
.dm-edit-panel::before {
  background:
    linear-gradient(155deg, rgba(255, 255, 255, 0.92), rgba(241, 247, 249, 0.84)),
    repeating-linear-gradient(135deg, rgba(35, 50, 55, 0.018) 0 1px, transparent 1px 8px);
}

.dm-overview-panel::after,
.dm-edit-panel::after {
  background: linear-gradient(180deg, rgba(251, 253, 254, 0.94), rgba(242, 247, 249, 0.92));
}

.metric-card,
.detail-summary-card > div,
.ranking-header,
.ranking-row,
.stations-section,
.edit-operation-item,
.edit-empty,
.history-preview-group,
.history-current-version,
.history-list-panel,
.history-risk-panel {
  background: rgba(250, 253, 254, 0.78);
  border-color: rgba(35, 50, 55, 0.1);
}

.hero-card .label-row .metric-note,
.coverage-card .card-title-row .metric-note {
  color: var(--dm-secondary);
}

.coverage-card .progress-fill.fill-500,
.rank-badge.gold,
.rank-badge.silver,
.rank-badge.bronze,
.edit-operation-item::before,
.history-version-node.active-data .history-timeline-dot,
.history-current-tag,
.picker-route-btn.active .picker-icon-wrapper {
  background: var(--dm-secondary);
}

.coverage-card .progress-fill.fill-500 {
  box-shadow: none;
}

.timeline-container .timeline-item .timeline-dot.last {
  border-color: var(--dm-secondary);
}

.timeline-container .timeline-item .timeline-dot.last .dot-inner {
  background: var(--dm-secondary);
}

.dm-history-page {
  left: 260px;
  right: 0;
  top: var(--app-header-height);
  bottom: 0;
  border: 0;
  border-radius: 0;
  background: #f7f9fb;
  box-shadow: none;
}

.history-version-main,
.history-version-node.active-data .history-version-main,
.route-name-block {
  background: rgba(250, 253, 254, 0.8);
  border-color: rgba(35, 50, 55, 0.1);
}

.route-name-block span {
  color: var(--dm-muted);
}

.history-preview-panel {
  left: 282px;
  background: rgba(249, 252, 253, 0.97);
}

.picker-route-btn.active {
  background: linear-gradient(135deg, rgba(47, 111, 115, 0.13), rgba(49, 93, 138, 0.09));
  box-shadow: inset 3px 0 0 var(--dm-accent), 0 12px 26px rgba(24, 43, 50, 0.08);
}

@media (max-width: 860px) {
.history-preview-panel {
    left: 238px;
  }

.dm-history-page {
    left: 220px;
  }
}

/* Apple-like white correction requested by the user. This final layer intentionally neutralizes the prior tinted reskins. */

.datebase_box,
.dm-overview-panel,
.dm-edit-panel,
.dm-history-page,
.history-preview-exit,
.history-detail-panel,
.line-route-picker,
.edit-action-menu {
  --dm-panel-scale: 1;
  --dm-ink: #1d1d1f;
  --dm-ink-strong: #09090b;
  --dm-muted: #6e6e73;
  --dm-muted-soft: #a1a1aa;
  --dm-accent: #0071e3;
  --dm-accent-strong: #005bb5;
  --dm-accent-soft: rgba(0, 113, 227, 0.08);
  --dm-secondary: #34c759;
  --dm-secondary-soft: rgba(52, 199, 89, 0.1);
  --dm-border: rgba(0, 0, 0, 0.1);
  --dm-border-strong: rgba(0, 113, 227, 0.34);
  --dm-shadow: 0 18px 48px rgba(15, 23, 42, 0.1), 0 3px 12px rgba(15, 23, 42, 0.04);
  --dm-shadow-soft: 0 10px 28px rgba(15, 23, 42, 0.07);
  --dm-ease: cubic-bezier(0.32, 0.72, 0, 1);
  /* 统一纯白：侧栏 / 面板 / 顶栏同色（应用户要求，去掉此前的偏蓝底色） */
  --dm-surface: #ffffff;
}

.dm-history-page,
.dm-overview-panel,
.dm-edit-panel,
.history-preview-exit,
.history-detail-panel,
.datebase_box,
.line-route-picker,
.edit-action-menu {
  background: var(--dm-surface) !important;
  background-image: none !important;
}

.dm-overview-panel,
.dm-edit-panel {
  top: calc(var(--app-header-height) + 12px);
  width: 398px;
  height: calc(100vh - var(--app-header-height) - 24px);
  padding: 16px;
  border: 1px solid var(--dm-border);
  border-radius: 18px;
  box-shadow: var(--dm-shadow);
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
  animation: none;
}

.dm-overview-panel::before,
.dm-overview-panel::after,
.dm-edit-panel::before,
.dm-edit-panel::after {
  display: none;
}

.picker-route-btn .picker-icon-wrapper {
  color: var(--dm-accent);
  background: var(--dm-accent-soft);
  border-color: rgba(0, 113, 227, 0.12);
}

.datebase_box,
.line-route-picker,
.edit-action-menu {
  border: 1px solid var(--dm-border);
  box-shadow: var(--dm-shadow-soft);
}

.datebase_box {
  border-radius: 999px;
}

.datebase_box .el-select :deep(.el-input__wrapper:hover),
.datebase_box .el-select :deep(.el-input__wrapper.is-focus) {
  border-color: var(--dm-border-strong);
  box-shadow: 0 0 0 4px rgba(0, 113, 227, 0.08), var(--dm-shadow-soft) !important;
}

.metric-card,
.detail-summary-card > div,
.ranking-header,
.ranking-row,
.stations-section,
.edit-operation-item,
.edit-empty,
.history-current-version,
.history-list-panel,
.history-risk-panel,
.history-version-main,
.history-preview-group,
.history-detail-group,
.history-detail-row {
  border: 1px solid rgba(0, 0, 0, 0.08);
  border-radius: 8px;
  background: var(--dm-surface) !important;
  background-image: none !important;
  box-shadow: 0 6px 18px rgba(15, 23, 42, 0.05);
}

.metric-card:hover,
.ranking-row.is-clickable:hover,
.edit-operation-item:hover,
.history-version-node:hover .history-version-main {
  border-color: rgba(0, 113, 227, 0.22);
  box-shadow: 0 12px 26px rgba(15, 23, 42, 0.08);
  transform: translateY(-1px);
}

.hero-card .value-row .hero-num,
.grid-card .grid-num,
.density-card .card-right .num-val,
.metrics-grid .metric-card .value,
.history-current-version strong {
  color: var(--dm-accent-strong);
}

.coverage-card .progress-track {
  height: 6px;
  background: rgba(15, 39, 68, 0.06);
  box-shadow: none;
}

.coverage-card .progress-fill.fill-300 {
  background: var(--dm-accent);
  box-shadow: none;
}

.coverage-card .progress-fill.fill-500 {
  background: var(--dm-secondary);
  box-shadow: none;
}

.edit-panel-actions {
  position: sticky;
  bottom: 0;
  z-index: 1;
  flex-shrink: 0;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
  margin: 12px -2px 0;
  padding: 12px 2px 0;
  border-top: 1px solid rgba(15, 39, 68, 0.08);
  background: var(--dm-surface);
}

.edit-operation-list,
.edit-empty {
  min-height: 0;
}

.edit-empty {
  overflow-y: auto;
}

.edit-panel-actions :deep(.el-button) {
  min-width: 92px;
}

.picker-hint {
  margin: -4px 2px 10px;
  color: var(--dm-muted);
  font-size: 12px;
  line-height: 1.45;
  font-weight: 600;
}

.picker-route-btn.active {
  background: rgba(0, 113, 227, 0.08) !important;
  border-color: rgba(0, 113, 227, 0.28);
  box-shadow: inset 3px 0 0 var(--dm-accent);
}

.picker-route-btn.active .picker-icon-wrapper,
.history-current-tag,
.history-version-node.active-data .history-timeline-dot {
  background: var(--dm-accent) !important;
  border-color: var(--dm-accent);
  color: #ffffff;
}

.history-version-side :deep(.el-button) {
  width: 112px;
  margin-left: 0;
}

.dm-history-page {
  padding: 24px 26px;
  overflow-y: auto;
}

.history-preview-exit {
  position: fixed;
  top: calc(var(--app-header-height) + 16px);
  right: var(--app-edge);
  z-index: calc(var(--z-panel) + 18);
  display: flex;
  align-items: center;
  gap: 10px;
  max-width: min(460px, calc(100vw - 320px));
  padding: 8px 8px 8px 14px;
  border: 1px solid var(--dm-border);
  border-radius: 999px;
  box-shadow: var(--dm-shadow-soft);
}

.history-preview-exit span {
  min-width: 0;
  overflow: hidden;
  color: var(--dm-ink);
  font-size: 13px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:global(.dm-edit-dialog.el-dialog),
:global(.dm-edit-dialog .el-dialog),
:global(.dm-commit-dialog.el-dialog),
:global(.dm-commit-dialog .el-dialog),
:global(.dm-shp-deletion-dialog.el-dialog),
:global(.dm-shp-deletion-dialog .el-dialog) {
  border: 1px solid var(--dm2-line);
  border-radius: var(--dm2-radius-xl);
  background: var(--dm2-surface);
  box-shadow: var(--dm2-shadow-dialog);
  overflow: hidden;
}

:global(.dm-commit-dialog.el-dialog),
:global(.dm-commit-dialog .el-dialog) {
  position: relative;
  width: min(520px, calc(100vw - 32px)) !important;
}

:global(.dm-shp-deletion-dialog.el-dialog),
:global(.dm-shp-deletion-dialog .el-dialog) {
  position: relative;
  width: min(640px, calc(100vw - 32px)) !important;
}

:global(.dm-commit-dialog.el-dialog),
:global(.dm-shp-deletion-dialog.el-dialog) {
  margin: 0 auto !important;
}

:global(.dm-commit-dialog .el-dialog),
:global(.dm-shp-deletion-dialog .el-dialog) {
  margin: 0 !important;
}

:global(.dm-edit-dialog .el-dialog__header),
:global(.dm-commit-dialog .el-dialog__header),
:global(.dm-shp-deletion-dialog .el-dialog__header) {
  margin: 0;
  padding: 20px 24px 10px;
}

:global(.dm-edit-dialog .el-dialog__title),
:global(.dm-commit-dialog .el-dialog__title),
:global(.dm-shp-deletion-dialog .el-dialog__title) {
  color: var(--dm2-ink);
  font-size: 19px;
  line-height: 1.3;
  font-weight: 700;
}

:global(.dm-edit-dialog .el-dialog__body),
:global(.dm-commit-dialog .el-dialog__body),
:global(.dm-shp-deletion-dialog .el-dialog__body) {
  padding: 0 24px 18px;
}

:global(.dm-edit-dialog .el-dialog__footer),
:global(.dm-commit-dialog .el-dialog__footer),
:global(.dm-shp-deletion-dialog .el-dialog__footer) {
  padding: 14px 24px 20px;
  border-top: 1px solid var(--dm2-line-faint);
  background: var(--dm2-surface);
}

:global(.dm-commit-dialog .el-dialog__headerbtn),
:global(.dm-shp-deletion-dialog .el-dialog__headerbtn) {
  position: absolute;
  top: 14px;
  right: 14px;
  left: auto;
  width: 32px;
  height: 32px;
  display: inline-grid;
  place-items: center;
  border-radius: var(--dm2-radius-sm);
  background: transparent;
  transition: background 160ms ease;
}

:global(.dm-commit-dialog .el-dialog__headerbtn:hover),
:global(.dm-commit-dialog .el-dialog__headerbtn:focus-visible),
:global(.dm-shp-deletion-dialog .el-dialog__headerbtn:hover),
:global(.dm-shp-deletion-dialog .el-dialog__headerbtn:focus-visible) {
  background: rgba(15, 23, 42, 0.06);
  outline: none;
}

:global(.dm-commit-dialog .el-dialog__close),
:global(.dm-shp-deletion-dialog .el-dialog__close) {
  color: var(--dm2-ink-soft);
  font-size: 18px;
  font-weight: 700;
}

.edit-dialog-subtitle {
  margin: 0 0 16px;
  color: var(--dm-muted);
  font-size: 13px;
  line-height: 1.55;
  font-weight: 600;
}

.dm-edit-form {
  display: grid;
  gap: 14px;
}

.dm-edit-form :deep(.el-form-item) {
  margin: 0;
}

.dm-edit-form :deep(.el-form-item__label) {
  margin-bottom: 7px;
  color: var(--dm2-ink-soft);
  font-size: 13px;
  line-height: 1.35;
  font-weight: 700;
}

.dm-edit-form :deep(.el-input__wrapper),
.dm-edit-form :deep(.el-textarea__inner) {
  border-radius: 10px;
  background: var(--dm2-surface) !important;
  box-shadow: 0 0 0 1px var(--dm2-line-strong) inset !important;
}

.dm-edit-form :deep(.el-input__wrapper.is-focus),
.dm-edit-form :deep(.el-textarea__inner:focus) {
  box-shadow: 0 0 0 1.5px var(--dm2-accent) inset, 0 0 0 4px var(--dm2-accent-ring) !important;
}

.field-hint {
  display: block;
  margin-top: 6px;
  color: var(--dm-muted);
  font-size: 12px;
  line-height: 1.45;
  font-weight: 500;
}

.commit-form {
  gap: 15px;
}

.commit-dialog-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 38px;
  margin: 0 0 16px;
  padding: 9px 12px;
  border: 1px solid var(--dm2-line);
  border-radius: var(--dm2-radius);
  background: var(--dm2-surface-sunken);
}

.commit-dialog-summary span,
.commit-dialog-summary strong {
  min-width: 0;
  font-size: 12px;
  line-height: 1.35;
  white-space: nowrap;
}

.commit-dataset-summary,
.edit-cross-dataset-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 10px;
}

.commit-dataset-summary {
  margin: -6px 0 16px;
}

.commit-dataset-summary span,
.edit-cross-dataset-summary span {
  color: var(--dm2-ink-soft);
  font-size: 12px;
  font-weight: 650;
  font-variant-numeric: tabular-nums;
}

.shp-deletion-summary {
  margin-bottom: 14px;

  strong {
    display: block;
    color: var(--dm2-ink);
    font-size: 14px;
    line-height: 1.45;
  }

  p {
    margin: 6px 0 0;
    color: var(--dm2-muted);
    font-size: 12.5px;
    line-height: 1.6;
  }
}

.shp-deletion-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 10px;
  border: 1px solid var(--dm2-line-faint);
  border-radius: var(--dm2-radius-sm);
  background: var(--dm2-surface-sunken);
  color: var(--dm2-muted);
  font-size: 12px;
  font-weight: 600;
}

.shp-deletion-list {
  display: grid;
  gap: 8px;
  max-height: min(420px, 48vh);
  margin-top: 10px;
  padding-right: 4px;
  overflow-y: auto;
}

.shp-deletion-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 11px 12px;
  border: 1px solid var(--dm2-line);
  border-radius: var(--dm2-radius);
  background: var(--dm2-surface);
  cursor: pointer;

  &:hover {
    border-color: var(--dm2-line-strong);
    background: var(--dm2-surface-sunken);
  }

  :deep(.el-checkbox) {
    margin-top: 1px;
  }
}

.shp-deletion-copy {
  display: grid;
  gap: 3px;
  min-width: 0;

  strong,
  small {
    overflow-wrap: anywhere;
  }

  strong {
    color: var(--dm2-ink);
    font-size: 13px;
    line-height: 1.4;
  }

  small {
    color: var(--dm2-muted);
    font-size: 11.5px;
    line-height: 1.45;
  }

  .shp-deletion-protected {
    color: var(--dm2-accent);
    font-weight: 600;
  }
}

.commit-dialog-summary span {
  color: var(--dm2-muted);
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
}

.commit-dialog-summary strong {
  flex-shrink: 0;
  color: var(--dm2-accent);
  font-weight: 700;
}

.evidence-file-input {
  position: fixed;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
}

.evidence-dropzone {
  width: 100%;
  min-height: 82px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border: 1px dashed var(--dm2-line-strong);
  border-radius: var(--dm2-radius);
  background: var(--dm2-surface-sunken);
  color: var(--dm2-ink-soft);
  cursor: pointer;
  transition: background 180ms ease, border-color 180ms ease, box-shadow 180ms ease, transform 180ms ease;

  .evidence-dropzone-icon {
    flex: 0 0 auto;
    width: 38px;
    height: 38px;
    display: inline-grid;
    place-items: center;
    border: 1px solid var(--dm2-line);
    border-radius: 10px;
    background: var(--dm2-surface);
    color: var(--dm2-accent);
  }

  div {
    display: grid;
    gap: 4px;
    min-width: 0;
  }

  strong {
    color: var(--dm2-ink);
    font-size: 14px;
    line-height: 1.35;
    font-weight: 700;
  }

  span {
    color: var(--dm2-muted);
    font-size: 12px;
    line-height: 1.45;
    font-weight: 600;
  }

  &:hover,
  &:focus-visible,
  &.is-dragging {
    border-color: var(--dm2-accent);
    background: var(--dm2-surface);
    box-shadow: 0 0 0 4px var(--dm2-accent-ring);
    outline: none;
  }

  &.has-images {
    border-style: solid;
  }
}

.evidence-preview-grid {
  width: 100%;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-top: 12px;
}

.evidence-preview-item {
  position: relative;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--dm2-line);
  border-radius: var(--dm2-radius-sm);
  background: var(--dm2-surface);

  img {
    display: block;
    width: 100%;
    aspect-ratio: 1.35;
    object-fit: cover;
    background: var(--dm2-surface-sunken);
  }

  button {
    position: absolute;
    top: 6px;
    right: 6px;
    width: 24px;
    height: 24px;
    display: inline-grid;
    place-items: center;
    border: 0;
    border-radius: 50%;
    background: rgba(15, 23, 42, 0.72);
    color: var(--dm2-surface);
    cursor: pointer;
  }

  span {
    display: block;
    padding: 6px 8px;
    color: var(--dm2-ink-soft);
    font-size: 11px;
    line-height: 1.35;
    font-weight: 600;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.dm-edit-dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.commit-dialog-footer :deep(.el-button) {
  min-width: 78px;
  height: 34px;
  border-radius: var(--dm2-radius-sm);
  font-weight: 700;
}

.commit-dialog-footer :deep(.el-button--primary) {
  background: var(--dm2-accent);
  border-color: var(--dm2-accent);
}

.dm-edit-panel .overview-title-row {
  flex-shrink: 0;
  align-items: flex-start;
  gap: 14px;
  padding-bottom: 14px;
  border-bottom: 1px solid rgba(15, 39, 68, 0.08);
}

.dm-edit-panel .overview-title-row h2 {
  margin-top: 6px;
  font-size: 28px;
  line-height: 1.18;
  font-weight: 700;
}

.dm-edit-panel .overview-title-row :deep(.el-tag) {
  height: 34px;
  padding: 0 13px;
  border-radius: 9px;
  font-size: 14px;
  font-weight: 700;
}

.edit-operation-list {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  min-height: 0;
  gap: 10px;
  margin: 14px 0 0;
  padding: 0 2px 2px 0;
  overflow-y: auto;
}

.edit-operation-summary {
  padding: 10px 12px;
  border: 1px solid rgba(0, 113, 227, 0.16);
  border-radius: 8px;
  background: rgba(0, 113, 227, 0.08);
  color: var(--dm-accent-strong);
  font-size: 13px;
  line-height: 1.45;
  font-weight: 600;
}

.edit-operation-item {
  display: grid !important;
  grid-template-columns: auto minmax(0, 1fr);
  grid-template-areas:
    "type title"
    "type detail";
  align-items: start;
  min-height: 0 !important;
  gap: 6px 12px !important;
  padding: 14px 15px !important;
  overflow: visible !important;
  border-radius: 8px !important;
  background: var(--dm-surface) !important;
}

.edit-operation-item::before {
  display: none !important;
}

.edit-operation-item:hover {
  transform: translateY(-1px);
}

.edit-operation-item .operation-type {
  grid-area: type;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 42px;
  height: 24px;
  padding: 0 9px !important;
  border-radius: 999px !important;
  background: rgba(0, 113, 227, 0.1) !important;
  color: var(--dm-accent-strong) !important;
  font-size: 12px !important;
  line-height: 1 !important;
  font-weight: 700 !important;
  white-space: nowrap;
}

.edit-operation-item strong {
  grid-area: title;
  min-width: 0;
  color: #111827;
  font-size: 15px;
  line-height: 1.45;
  font-weight: 700;
  overflow-wrap: anywhere;
}

.edit-operation-item p {
  grid-area: detail;
  min-width: 0;
  margin: 0;
  color: var(--dm-muted);
  font-size: 13px;
  line-height: 1.55;
  font-weight: 500;
  overflow-wrap: anywhere;
}

.edit-panel-actions {
  min-height: 58px;
  gap: 10px;
}

.shp-upload-input {
  display: none;
}

.edit-panel-actions .upload-shp-btn {
  margin-right: auto !important;
}

.edit-panel-actions :deep(.el-button) {
  height: 40px;
  border-radius: 8px;
  font-weight: 700;
}

.datebase_box.database-box {
  gap: 8px;
  padding: 0;
  border: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
}

.datebase_box.database-box .handle {
  color: var(--dm-ink);
  font-size: 12px;
  font-weight: 700;
}

.datebase_box.database-box .el-select {
  width: clamp(156px, 13vw, 210px);
}

.route-detail-panel {
  gap: 10px;
  margin-top: 12px;
  overflow: hidden;
}

.route-detail-panel .route-summary-card,
.route-detail-panel .metrics-grid {
  flex: 0 0 auto;
}

.route-detail-panel .route-summary-card {
  grid-template-columns: 1fr;
}

.route-detail-panel .route-summary-card > div {
  padding: 9px 11px;
}

.route-detail-panel .route-summary-card .service-time-card {
  grid-column: 1 / -1;
}

.route-detail-panel .route-summary-card strong {
  font-size: 13px;
  line-height: 1.25;
  white-space: normal;
  overflow: visible;
  text-overflow: clip;
  overflow-wrap: anywhere;
}

.route-detail-panel .metrics-grid {
  grid-template-columns: repeat(auto-fit, minmax(98px, 1fr));
  gap: 8px;
  margin-bottom: 0;
}

.route-detail-panel .metrics-grid .metric-card {
  min-height: 56px;
  justify-content: center;
  gap: 2px;
  padding: 8px 10px;
}

.route-detail-panel .metrics-grid .metric-card .label {
  font-size: 10.5px;
  line-height: 1.2;
}

.route-detail-panel .metrics-grid .metric-card .value {
  font-size: 14px;
  line-height: 1.25;
}

.route-detail-panel .stations-section {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 11px 12px;
}

.route-detail-panel .stations-section .section-title {
  flex: 0 0 auto;
  margin-bottom: 8px;
}

.route-detail-panel .station-scroll-list {
  flex: 1 1 auto;
  min-height: 0;
  max-height: none;
  overflow-y: auto;
}

.route-detail-panel .timeline-container .timeline-item {
  min-height: 38px;
  gap: 10px;
  padding-bottom: 9px;
}

.route-detail-panel .timeline-container .timeline-content .station-name {
  font-size: 12.5px;
}

@media (max-width: 860px) {
.history-version-node {
    grid-template-columns: 22px minmax(0, 1fr);
  }

.history-detail-panel {
    left: 238px;
    right: 14px;
    width: auto;
  }

.history-preview-exit {
    max-width: calc(100vw - 260px);
  }
}

@media (max-width: 720px) {
.history-detail-panel,
.history-preview-exit {
    left: 12px;
    right: 12px;
    width: auto;
    max-width: none;
  }
}

/* Resolution lock: the 1430x686 desktop composition scales as a single system. */

.datebase_box,
.dm-overview-panel,
.dm-edit-panel,
.history-preview-panel,
.history-preview-exit,
.history-detail-panel,
.line-route-picker,
.edit-action-menu {
  --dm-panel-scale: var(--app-layout-scale);
}

.datebase_box {
  top: calc(var(--app-header-height) / 2);
  right: calc(var(--app-edge) + var(--app-scaled-70));
  transform-origin: right center;
}

.dm-overview-panel,
.dm-edit-panel {
  top: calc(var(--app-header-height) + var(--app-scaled-12));
  right: var(--app-edge);
  width: 398px;
  height: var(--app-dm-panel-height);
  transform-origin: right top;
}

.history-preview-panel {
  left: var(--app-scaled-282);
  top: calc(var(--app-header-height) + var(--app-scaled-76));
  max-height: var(--app-dm-history-preview-height);
  transform-origin: left top;
}

.history-preview-exit {
  top: calc(var(--app-header-height) + var(--app-scaled-16));
  right: var(--app-edge);
  max-width: min(460px, var(--app-dm-history-side-width));
  transform-origin: right top;
}

.dm-history-page {
  left: var(--app-scaled-260);
  top: var(--app-header-height);
  right: 0;
  bottom: 0;
  padding: var(--app-scaled-24) var(--app-scaled-26);
  border-radius: 0;
}

/* Adapted overview panel: keep the full enterprise table visible at locked desktop scale. */

.dm-overview-panel {
  min-height: 0;
}

.dm-overview-panel .overview-title-row {
  flex-shrink: 0;
  padding-bottom: 10px;
}

.dm-overview-panel .metric-card {
  flex-shrink: 0;
}

/* ──────────────────────────────────────────────────────────────
   线路/站点/场站数据更新面板 — 整洁化
   仅做减法：移除堆叠装饰、让列表可滚动、按动作类型克制着色。
   条目的两列栅格沿用上方“Resolution lock”定义，这里不再重排结构。
   ────────────────────────────────────────────────────────────── */

/* 1) 面板回归一块干净白卡：去掉彩条 / 光晕 / 纸纹等叠加装饰 */

.dm-edit-panel::before,
.dm-edit-panel::after {
  content: none;
  display: none;
}

.dm-edit-panel {
  padding: 18px 16px 16px;
  border-radius: var(--dm2-radius-lg);
  border: 1px solid var(--dm2-line);
  background: var(--dm2-surface);
  box-shadow: var(--dm2-shadow-panel);
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}

.dm-edit-panel .overview-title-row {
  flex-shrink: 0;
  padding: 0 2px 14px;
  border-bottom: 1px solid var(--dm2-line-faint);
}

/* 待提交计数：去掉 Element 的琥珀色告警胶囊，改为安静的中性小标 */

.dm-edit-panel .edit-pending-count {
  flex-shrink: 0;
  align-self: flex-start;
  padding: 3px 10px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.05);
  color: var(--dm2-muted);
  font-size: 12px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.dm-edit-panel .edit-pending-count.has-pending {
  background: var(--dm2-accent-weak);
  color: var(--dm2-accent);
}

.dm-edit-panel .edit-cross-dataset-summary {
  flex-shrink: 0;
  margin-top: 10px;
  padding: 9px 10px;
  border: 1px solid var(--dm2-line);
  border-radius: var(--dm2-radius-sm);
  background: var(--dm2-surface-sunken);
}

.dm-edit-panel .edit-cross-dataset-summary strong {
  color: var(--dm2-ink);
  font-size: 12px;
}

/* 2) 列表可滚动：min-height:0 让 flex 子项收缩并触发内部滚动；标题/按钮固定 */

.dm-edit-panel .edit-operation-list {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 14px;
  padding-right: 6px;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: thin;
  scrollbar-color: rgba(15, 23, 42, 0.2) transparent;
}

.dm-edit-panel .edit-operation-list::-webkit-scrollbar {
  width: 5px;
}

.dm-edit-panel .edit-operation-list::-webkit-scrollbar-thumb {
  background: rgba(15, 23, 42, 0.16);
  border-radius: 999px;
}

.dm-edit-panel .edit-operation-summary {
  flex-shrink: 0;
  padding: 7px 10px;
  border-radius: var(--dm2-radius-sm);
  background: rgba(15, 23, 42, 0.04);
  color: var(--dm2-muted);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.4;
}

/* 3) 条目：保留两列栅格，只做减法；动作色彩仅靠文字传达，不再加色块/色条 */

.dm-edit-panel .edit-operation-item {
  --k-color: var(--dm2-accent);
  flex-shrink: 0;
  border: 1px solid var(--dm2-line);
  box-shadow: none;
}

.dm-edit-panel .edit-operation-item.is-add {
  --k-color: var(--dm2-add);
}

.dm-edit-panel .edit-operation-item.is-delete {
  --k-color: var(--dm2-delete);
}

.dm-edit-panel .edit-operation-item:hover {
  transform: none;
  border-color: var(--dm2-line-strong);
  background: rgba(15, 23, 42, 0.02);
  box-shadow: none;
}

.dm-edit-panel .operation-labels {
  grid-area: type;
  display: grid;
  gap: 3px;
  min-width: 46px;
}

.dm-edit-panel .operation-protected {
  color: var(--dm2-accent);
  font-size: 10px;
  line-height: 1.25;
  font-weight: 700;
}

.dm-edit-panel .operation-protected.is-deletion {
  color: #b42318;
  background: #fef3f2;
  border-color: #fecdca;
}

.dm-edit-panel .edit-operation-more {
  width: 100%;
  flex-shrink: 0;
}

.shp-deletion-pagination {
  justify-content: center;
  margin-top: 12px;
}

.dm-edit-panel .operation-dataset {
  color: var(--dm2-muted-soft);
  font-size: 10.5px;
  font-weight: 600;
  line-height: 1.2;
}

/* 动作不再用胶囊徽标，改为安静的纯色文字（去掉 AI 感的小色块） */

.dm-edit-panel .edit-operation-item .operation-type {
  min-width: 0 !important;
  height: auto !important;
  padding: 0 !important;
  border: none !important;
  border-radius: 0 !important;
  background: transparent !important;
  color: var(--k-color) !important;
  font-size: 12px !important;
  font-weight: 600 !important;
}

/* 4) 空状态与底部操作区，安静收敛 */

.dm-edit-panel .edit-empty {
  flex-shrink: 0;
  border-radius: var(--dm2-radius);
  border: 1px solid var(--dm2-line);
  background: rgba(15, 23, 42, 0.02);
  box-shadow: none;
}

.dm-edit-panel .edit-panel-actions {
  flex-shrink: 0;
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--dm2-line-faint);
}

/* ── 数据总览面板：与编辑面板统一为干净白卡，去掉纸纹/内嵌层装饰 ── */

.dm-overview-panel::before,
.dm-overview-panel::after {
  content: none;
  display: none;
}

.dm-overview-panel {
  padding: 18px 16px 16px;
  border-radius: var(--dm2-radius-lg);
  border: 1px solid var(--dm2-line);
  background: var(--dm2-surface);
  box-shadow: var(--dm2-shadow-panel);
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}

/* 指标区样式已迁移至 OverviewMetrics.vue（自包含、全令牌）；此处只保留面板外壳。 */

/* ── Action 2 /quieter：历史页统一到令牌冷静配色（青蓝 → 单一蓝），去胶囊、收阴影 ── */

/* 「当前版本」标记：去掉实心彩色胶囊，改为安静浅色文字小标 */

.dm-history-page .history-current-tag {
  background: var(--dm2-accent-weak) !important;
  border-color: transparent !important;
  color: var(--dm2-accent) !important;
}

/* 当前版本横幅：数值改为中性墨色，不再用青色强调 */

.dm-history-page .history-current-version strong {
  color: var(--dm2-ink);
}

/* 时间轴「当前数据版本」圆点：青色 → 统一蓝 */

.dm-history-page .history-version-node.active-data .history-timeline-dot {
  background: var(--dm2-accent) !important;
  border-color: var(--dm2-accent) !important;
}

/* 修改明细的动作标记：去掉青色胶囊，改为安静纯色文字 */

.dm-history-page .history-detail-action {
  padding: 0;
  border-radius: 0;
  background: transparent;
  color: var(--dm2-accent);
  font-weight: 600;
}

/* 历史卡片：收掉偏重的投影，统一为细边 + 无影的冷静表面 */

.dm-history-page .history-current-version,
.dm-history-page .history-list-panel,
.dm-history-page .history-risk-panel,
.dm-history-page .history-version-main,
.dm-history-page .history-detail-group,
.dm-history-page .history-detail-row {
  border-color: var(--dm2-line) !important;
  box-shadow: none !important;
}

.dm-history-page .history-version-node:hover .history-version-main {
  border-color: var(--dm2-line-strong) !important;
  box-shadow: none !important;
  transform: none;
}

/* ════════════════════════════════════════════════════════════════
   PREMIUM ELEVATION · 权威收尾层（统一「高端蓝玻璃」语言）
   单一蓝强调色 + 冷中性灰 + 浮于地图之上的磨砂玻璃面板 + 分层冷调投影 +
   受控弹性动效。本层覆盖此前所有 reskin（青铜 / 纯平白），把侧栏 / 顶部选择器 /
   搜索 / 工具条 / 弹出层 / 浮层卡片与内容面板统一到 --dm2-* 系统。
   ════════════════════════════════════════════════════════════════ */

/* A. 令牌统一：历史 --dm-* 全部链接到已升级的 --dm2-*（一处改，处处生效） */

.datebase_box,
.dm-overview-panel,
.dm-edit-panel,
.dm-history-page,
.history-preview-exit,
.history-preview-panel,
.history-detail-panel,
.line-route-picker,
.edit-action-menu {
  --dm-ink: var(--dm2-ink);
  --dm-ink-strong: #10151b;
  --dm-muted: var(--dm2-muted);
  --dm-muted-soft: var(--dm2-muted-soft);
  --dm-accent: var(--dm2-accent);
  --dm-accent-strong: var(--dm2-accent-strong);
  --dm-accent-soft: var(--dm2-accent-weak);
  --dm-secondary: var(--dm2-accent);
  --dm-secondary-soft: var(--dm2-accent-weak);
  --dm-border: var(--dm2-line);
  --dm-border-strong: var(--dm2-line-strong);
  --dm-surface: #ffffff;
  --dm-shadow: var(--dm2-shadow-panel);
  --dm-shadow-soft: var(--dm2-shadow-pop);
  --dm-ease: var(--dm2-ease);
  font-family: var(--dm2-font);
  color: var(--dm2-ink);
}

/* B. 侧栏：磨砂玻璃 + 顶部高光 + 冷调外影 */

/* 主/子导航：克制胶囊 + 激活态左侧蓝条 */

/* C. 顶部区域选择器（磨砂胶囊） */

.datebase_box .handle {
  color: var(--dm2-muted);
  font-size: 12px;
  font-weight: 600;
}

.datebase_box .el-select :deep(.el-input__wrapper) {
  border-radius: var(--dm2-radius-pill);
  background: var(--dm2-veil) !important;
  box-shadow: inset 0 0 0 1px var(--dm2-line), var(--dm2-shadow-pop) !important;
  transition: box-shadow var(--dm2-dur) var(--dm2-ease);
}

.datebase_box .el-select :deep(.el-input__wrapper:hover) {
  box-shadow: inset 0 0 0 1px var(--dm2-line-strong), var(--dm2-shadow-pop) !important;
}

.datebase_box .el-select :deep(.el-input__wrapper.is-focus) {
  box-shadow: inset 0 0 0 1.5px var(--dm2-accent), 0 0 0 4px var(--dm2-accent-ring), var(--dm2-shadow-pop) !important;
}

.datebase_box .el-select :deep(.el-select__caret) {
  color: var(--dm2-accent) !important;
}

/* D. 地图搜索：磨砂玻璃药丸 */

/* E. 浮层卡片：磨砂玻璃 + 分层投影 */

.line-route-picker,
.edit-action-menu {
  border: 1px solid var(--dm2-line) !important;
  border-radius: var(--dm2-radius-lg);
  background: var(--dm2-glass-strong) !important;
  box-shadow: var(--dm2-shadow-pop), var(--dm2-glass-highlight) !important;
  -webkit-backdrop-filter: var(--dm2-glass-blur);
  backdrop-filter: var(--dm2-glass-blur);
}

.picker-route-btn {
  border-radius: var(--dm2-radius-sm);
  transition:
    background-color var(--dm2-dur) var(--dm2-ease),
    transform var(--dm2-dur-fast) var(--dm2-ease);
}

.picker-route-btn:hover {
  background: var(--dm2-accent-weak) !important;
  transform: translateX(2px);
}

.picker-route-btn .picker-icon-wrapper {
  border-radius: var(--dm2-radius-sm);
  color: var(--dm2-accent) !important;
  background: var(--dm2-accent-weak) !important;
  border-color: rgba(0, 113, 227, 0.14) !important;
}

.route-btn-name {
  color: var(--dm2-ink);
}

.route-btn-desc,
.picker-empty {
  color: var(--dm2-muted);
}

/* F. 地图控制条：常驻高不透明表面 + 蓝色激活（去 blur 省每帧合成） */

.picker-title {
  color: var(--dm2-muted);
}

/* G. 数据面板：常驻高不透明表面（保证密集数据可读）+ 分层投影 + 顶部高光 */

.dm-overview-panel,
.dm-edit-panel {
  border: 1px solid var(--dm2-line);
  border-radius: var(--dm2-radius-lg);
  background: var(--dm2-veil-strong) !important;
  box-shadow: var(--dm2-shadow-panel), var(--dm2-glass-highlight);
}

.history-preview-exit,
.history-preview-panel,
.history-detail-panel {
  border: 1px solid var(--dm2-line) !important;
  background: var(--dm2-veil-strong) !important;
  box-shadow: var(--dm2-shadow-panel), var(--dm2-glass-highlight) !important;
}

/* H. 内核卡片（详情面板）：清晰白 + 细微卡片影（托盘+面板 双层质感）*/

.detail-summary-card > div,
.ranking-row,
.stations-section,
.route-detail-panel .metrics-grid .metric-card {
  border: 1px solid var(--dm2-line) !important;
  border-radius: var(--dm2-radius) !important;
  background: #ffffff !important;
  box-shadow: var(--dm2-shadow-card) !important;
  transition:
    border-color var(--dm2-dur) var(--dm2-ease),
    box-shadow var(--dm2-dur) var(--dm2-ease),
    transform var(--dm2-dur-fast) var(--dm2-ease);
}

.ranking-row.is-clickable:hover,
.route-detail-panel .metrics-grid .metric-card:hover {
  border-color: rgba(0, 113, 227, 0.24) !important;
  box-shadow: var(--dm2-shadow-raised) !important;
  transform: translateY(-1px);
}

.ranking-header {
  border-radius: var(--dm2-radius) !important;
  background: var(--dm2-surface-sunken) !important;
  border-color: var(--dm2-line) !important;
}

.ranking-header span {
  color: var(--dm2-muted) !important;
}

/* 详情数值统一蓝；标签统一冷灰 */

.route-detail-panel .metrics-grid .metric-card .value,
.detail-summary-card strong {
  color: var(--dm2-ink) !important;
}

.route-detail-panel .metrics-grid .metric-card .label,
.detail-summary-card span {
  color: var(--dm2-muted) !important;
}

.route-name-text,
.overview-station-title,
.overview-title-row h2,
.stations-section .section-title {
  color: var(--dm2-ink) !important;
}

.flow-unit,
.station-idx {
  color: var(--dm2-muted) !important;
}

.flow-value {
  color: var(--dm2-accent) !important;
}

/* 沿途站点时间轴：起点绿 / 终点蓝，连接线冷灰 */

.timeline-container .timeline-item::after {
  background-color: var(--dm2-line) !important;
}

.timeline-container .timeline-item .timeline-dot {
  border-color: var(--dm2-line-strong) !important;
  background: #ffffff !important;
}

.timeline-container .timeline-item .timeline-dot.first {
  border-color: var(--dm2-add) !important;
}

.timeline-container .timeline-item .timeline-dot.first .dot-inner {
  background: var(--dm2-add) !important;
}

.timeline-container .timeline-item .timeline-dot.last {
  border-color: var(--dm2-accent) !important;
}

.timeline-container .timeline-item .timeline-dot.last .dot-inner {
  background: var(--dm2-accent) !important;
}

.timeline-container .timeline-item:hover .timeline-content .station-name {
  color: var(--dm2-accent) !important;
}

/* 排名奖牌：克制的单色阶（金/银/铜 → 文字色阶），去高饱和 */

.rank-badge {
  color: var(--dm2-muted) !important;
  background: rgba(17, 32, 58, 0.06) !important;
  border-color: transparent !important;
  font-variant-numeric: tabular-nums;
}

.rank-badge.gold {
  color: #fff !important;
  background: var(--dm2-accent) !important;
}

.rank-badge.silver {
  color: var(--dm2-ink-soft) !important;
  background: rgba(17, 32, 58, 0.12) !important;
}

.rank-badge.bronze {
  color: var(--dm2-ink-soft) !important;
  background: rgba(17, 32, 58, 0.08) !important;
}

/* 标题小标（kicker）：克制蓝色文字标签 */

.overview-title-row .panel-kicker {
  width: fit-content;
  margin-bottom: 6px;
  padding: 0;
  border-radius: 0;
  background: transparent;
  color: var(--dm2-accent);
  font-size: 11.5px;
  font-weight: 700;
  letter-spacing: 0.06em;
}

/* 滚动条统一为冷调细条 */

.overview-metric-list,
.route-detail-panel,
.depot-detail-panel,
.ranking-scroll-list,
.edit-operation-list,
.station-scroll-list {
  scrollbar-color: rgba(17, 32, 58, 0.18) transparent;
}

/* Visibility fallback: keep the data-management chrome above the map. */

.dm-overview-panel,
.dm-edit-panel {
  position: fixed !important;
  visibility: visible !important;
  opacity: 1 !important;
  z-index: calc(var(--z-panel, 1300) + 20) !important;
}

.datebase_box {
  position: fixed !important;
  visibility: visible !important;
  opacity: 1 !important;
  display: flex !important;
  top: calc(var(--app-header-height, 58px) / 2) !important;
  right: calc(var(--app-edge, 24px) + 70px) !important;
  z-index: calc(var(--z-header, 1500) + 20) !important;
  pointer-events: auto !important;
}

.dm-overview-panel,
.dm-edit-panel {
  display: flex !important;
  flex-direction: column;
  top: calc(var(--app-header-height, 58px) + 12px) !important;
  right: var(--app-edge, 24px) !important;
  width: 398px !important;
  height: calc((100vh - var(--app-header-height, 58px) - 24px) / var(--dm-panel-scale, 1)) !important;
}

/* 数据面板可见时，工具条让位到面板左侧（必须同为 !important 才能盖过上面的回退定位） */

/* User-requested panel behavior: side-retract both panels and keep map tools left of the right panel. */

.dm-overview-panel,
.dm-edit-panel,
.dm-history-page {
  transition:
    left 160ms var(--dm2-ease),
    right 160ms var(--dm2-ease),
    transform 160ms var(--dm2-ease),
    opacity var(--dm2-dur) var(--dm2-ease) !important;
}

.dm-overview-panel.is-collapsed,
.dm-edit-panel.is-collapsed {
  transform: translateX(calc(100% + var(--app-edge, 24px) + 12px)) !important;
  pointer-events: none;
}

.dm-history-page.is-left-collapsed {
  left: 0 !important;
}

.dm-panel-collapse-tab {
  position: fixed;
  top: calc(50% + var(--app-header-height, 58px) / 2);
  z-index: calc(var(--z-panel, 1300) + 140) !important;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 72px;
  padding: 0;
  border: 1px solid var(--dm2-line);
  background: var(--dm2-veil-strong);
  color: var(--dm2-ink-soft);
  box-shadow: var(--dm2-shadow-pop), var(--dm2-glass-highlight);
  cursor: pointer;
  touch-action: manipulation;
  user-select: none;
  scale: var(--dm-panel-scale, var(--app-layout-scale, 1));
  transform: translateY(-50%);
  transition:
    left 160ms var(--dm2-ease),
    right 160ms var(--dm2-ease),
    color 120ms var(--dm2-ease),
    background-color 120ms var(--dm2-ease),
    box-shadow 120ms var(--dm2-ease);
}

.dm-panel-collapse-tab::before {
  content: "";
  position: absolute;
  inset: -10px;
}

.dm-panel-collapse-tab:hover {
  background: var(--dm2-glass-strong);
  color: var(--dm2-accent);
  box-shadow: var(--dm2-shadow-raised), var(--dm2-glass-highlight);
}

.dm-panel-collapse-tab svg {
  transition: transform var(--dm2-dur) var(--dm2-ease);
}

.dm-left-collapse-tab {
  left: var(--app-scaled-260, 260px);
  border-left: 0;
  border-radius: 0 var(--dm2-radius) var(--dm2-radius) 0;
  transform-origin: left center;
}

.dm-left-collapse-tab.is-collapsed {
  left: 0;
}

.dm-left-collapse-tab.is-collapsed svg {
  transform: rotate(180deg);
}

.dm-right-collapse-tab {
  right: calc(var(--app-edge, 24px) + var(--app-scaled-414, 414px) - var(--app-scaled-16, 16px));
  border-right: 0;
  border-radius: var(--dm2-radius) 0 0 var(--dm2-radius);
  transform-origin: right center;
}

.dm-right-collapse-tab.is-collapsed {
  right: 0;
}

.dm-right-collapse-tab.is-collapsed svg {
  transform: rotate(180deg);
}

.rank-badge,
.rank-badge.gold,
.rank-badge.silver,
.rank-badge.bronze {
  color: #ffffff !important;
  background: var(--dm2-accent) !important;
  border-color: transparent !important;
}

@media (max-width: 720px) {
.dm-left-collapse-tab {
    left: var(--app-scaled-260, 260px);
  }

.dm-right-collapse-tab {
    right: calc(var(--app-edge, 24px) + var(--app-scaled-414, 414px) - var(--app-scaled-16, 16px));
  }
}
</style>
