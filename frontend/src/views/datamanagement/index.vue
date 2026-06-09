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

  <div class="dm-sidebar">
    <div class="sidebar-brand">
      <svg class="brand-icon" viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M4 20h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.93a2 2 0 0 1-1.66-.9l-.82-1.2A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13c0 1.1.9 2 2 2Z"></path>
      </svg>
      <span class="brand-text">数据管理</span>
    </div>

    <nav class="sidebar-nav" aria-label="数据管理导航">
      <div v-for="item in menuItems" :key="item.key" class="menu-group">
        <button
          type="button"
          :class="[
            'nav-item',
            activeKey === item.key || (item.children && item.children.some((child) => child.key === activeKey)) ? 'active' : '',
          ]"
          :aria-expanded="item.children ? isExpanded(item.key) : undefined"
          @click="handleItemClick(item)"
        >
          <span class="nav-icon" v-html="item.icon"></span>
          <span class="nav-label">{{ item.label }}</span>
          <span v-if="item.children" class="chevron-icon" :class="{ expanded: isExpanded(item.key) }">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <polyline points="6 9 12 15 18 9"></polyline>
            </svg>
          </span>
        </button>

        <transition name="slide-fade">
          <div v-if="item.children && isExpanded(item.key)" class="sub-nav-list">
            <button
              v-for="sub in item.children"
              :key="sub.key"
              type="button"
              :class="['sub-nav-item', activeKey === sub.key ? 'active' : '']"
              @click.stop="setActiveKey(sub.key)"
            >
              <span class="sub-dot"></span>
              <span class="nav-label">{{ sub.label }}</span>
            </button>
          </div>
        </transition>
      </div>
    </nav>

    <div class="sidebar-footer"></div>
  </div>

  <div v-if="showMapSearch" class="map-search" :class="{ 'is-focused': isSearchFocused }" role="search" aria-label="搜索站点或线路" @click.stop>
    <svg class="search-icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
      <circle cx="11" cy="11" r="8"></circle>
      <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
    </svg>
    <input
      v-model="searchKeyword"
      class="search-input"
      type="search"
      :placeholder="searchPlaceholder"
      :aria-label="searchPlaceholder"
      @focus="handleSearchFocus"
      @input="handleSearchInput"
      @blur="handleSearchBlur"
      @keydown.enter.prevent="selectFirstSearchResult"
      @keydown.esc.prevent="closeSearchResults"
    />
    <button v-if="searchKeyword" class="search-clear-btn" type="button" title="清空搜索" aria-label="清空搜索" @mousedown.prevent="clearSearchKeyword">
      <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
        <line x1="18" y1="6" x2="6" y2="18"></line>
        <line x1="6" y1="6" x2="18" y2="18"></line>
      </svg>
    </button>
    <Transition name="search-dropdown-fade">
      <div v-if="showSearchResults" class="search-result-list" role="listbox">
        <button
          v-for="result in searchResults"
          :key="result.key"
          class="search-result-item"
          type="button"
          role="option"
          @mousedown.prevent="selectSearchResult(result)"
        >
          <div class="result-icon-wrapper" :class="result.type">
            <!-- Station Icon -->
            <svg v-if="result.type === 'station'" viewBox="0 0 24 24" class="type-svg" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"></path>
              <circle cx="12" cy="10" r="3"></circle>
            </svg>
            <!-- Line Icon -->
            <svg v-else-if="result.type === 'line'" viewBox="0 0 24 24" class="type-svg" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
              <rect x="3" y="4" width="18" height="12" rx="2"></rect>
              <circle cx="7" cy="10" r="1"></circle>
              <circle cx="17" cy="10" r="1"></circle>
              <path d="M6 16v2"></path>
              <path d="M18 16v2"></path>
            </svg>
            <!-- Depot Icon -->
            <svg v-else viewBox="0 0 24 24" class="type-svg" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"></path>
              <polyline points="9 22 9 12 15 12 15 22"></polyline>
            </svg>
          </div>
          <div class="result-meta-block">
            <span class="result-name">{{ result.name }}</span>
            <span class="result-type-text">{{ result.typeLabel }}</span>
          </div>
        </button>
        <p v-if="!searchResults.length" class="search-empty">未找到匹配项</p>
      </div>
    </Transition>
  </div>

  <div v-if="activeKey === 'overview' || historyPreview.visible" class="dm-overview-panel">
    <div class="overview-title-row" :class="{ 'is-station-detail': selectedStation || selectedRoute || selectedDepot }">
      <div v-if="selectedStation" class="detail-title-block station">
        <p class="panel-kicker">站点详情</p>
        <h2 class="overview-station-title">{{ selectedStation.name }}</h2>
        <span>{{ selectedStation.routes.length }} 条途经线路</span>
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
      <template v-else-if="!selectedStation && !selectedRoute && !selectedDepot">
        <el-tag type="warning" v-if="loadError">加载失败</el-tag>
        <el-tag v-else-if="!overviewStats.lineCount && !overviewStats.stationCount">等待数据</el-tag>
      </template>
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
            :key="index"
            type="button"
            :class="['ranking-row', route.feature ? 'is-clickable' : 'is-disabled']"
            :disabled="!route.feature"
            @click="selectRouteFromStation(route)"
          >
            <div class="col-rank">
              <span :class="['rank-badge', index === 0 ? 'gold' : index === 1 ? 'silver' : index === 2 ? 'bronze' : '']">
                {{ index + 1 }}
              </span>
            </div>
            <div class="col-name">
              <span class="route-name-text">{{ route.name }}</span>
              <span class="route-desc-text">{{ route.desc }}</span>
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
        <div>
          <span>首站</span>
          <strong>{{ routeStartName(selectedRoute.properties) || "未知" }}</strong>
        </div>
        <div>
          <span>末站</span>
          <strong>{{ routeEndName(selectedRoute.properties) || "未知" }}</strong>
        </div>
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
          <span class="label">首班时间</span>
          <span class="value">{{ getRouteFirstTime(selectedRoute.properties) }}</span>
        </div>
        <div class="metric-card">
          <span class="label">末班时间</span>
          <span class="value">{{ getRouteLastTime(selectedRoute.properties) }}</span>
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

    <OverviewMetrics
      v-else
      :stats="overviewStats"
      :dial="coverageDial"
      :operator-rows="operatorLineRows"
      :fmt-int="formatInteger"
      :fmt-unit="formatUnit"
      :fmt-pct="formatPercent"
    />
    <p v-if="loadError && !hasActiveDetail" class="load-error">{{ loadError }}</p>
  </div>

  <div v-if="activeEditDataset" class="dm-edit-panel">
    <div class="overview-title-row">
      <div>
        <p class="panel-kicker">{{ editDatasetKicker }}</p>
        <h2>{{ editDatasetTitle }}</h2>
      </div>
      <span class="edit-pending-count" :class="{ 'has-pending': activeEditOperations.length }">{{ activeEditOperations.length }} 条修改</span>
    </div>
    <div v-if="activeEditOperations.length" class="edit-operation-list">
      <div v-if="hiddenActiveEditOperationCount" class="edit-operation-summary">
        已显示前 {{ visibleActiveEditOperations.length }} 条，另有 {{ hiddenActiveEditOperationCount }} 条会一并提交
      </div>
      <div v-for="operation in visibleActiveEditOperations" :key="operation.operationId" class="edit-operation-item" :class="operationKind(operation.type)">
        <span class="operation-type">{{ operationLabel(operation.type) }}</span>
        <strong>{{ operation.title }}</strong>
        <p>{{ operation.detail }}</p>
      </div>
    </div>
    <div v-else class="edit-empty">
      <strong>{{ editModeGuide.title }}</strong>
      <p>{{ editModeGuide.description }}</p>
      <ol>
        <li v-for="step in editModeGuide.steps" :key="step">{{ step }}</li>
      </ol>
      <el-button
        v-if="editModeGuide.canStartAdd"
        size="small"
        type="primary"
        plain
        :disabled="pendingAddDataset === activeEditDataset"
        @click="beginAddFromPanel"
      >
        {{ pendingAddDataset === activeEditDataset ? "请在地图点选位置" : editModeGuide.actionLabel }}
      </el-button>
      <el-button v-if="pendingAddDataset === activeEditDataset" size="small" @click="cancelPendingAdd">取消点选</el-button>
    </div>
    <div class="edit-panel-actions">
      <input ref="shpUploadInput" class="shp-upload-input" type="file" multiple accept=".zip,.shp,.shx,.dbf,.prj,.cpg" @change="handleUploadShpFiles" />
      <el-button class="upload-shp-btn" :disabled="isSubmittingEdit" @click="handleUploadShpClick">上传 SHP</el-button>
      <el-button :disabled="!activeEditOperations.length || isSubmittingEdit" @click="discardActiveEdits">放弃修改</el-button>
      <el-button type="primary" :disabled="!activeEditOperations.length" :loading="isSubmittingEdit" @click="submitActiveEdits">提交修改</el-button>
    </div>
  </div>

  <div v-if="activeKey === 'history' && !historyPreview.visible" class="dm-history-page">
    <HistoryPanel
      :area="selectedArea"
      :loading="isLoadingHistory"
      :error="historyError"
      :versions="historyVersions"
      :active-label="activeHistoryVersionLabel"
      :preview-loading-id="historyPreview.loading ? (historyPreview.version?.versionId || '') : ''"
      :details="historyDetails"
      :detail-groups="historyDetailGroups"
      :record-title="historyRecordTitle"
      :format-time="formatHistoryTime"
      @refresh="loadHistoryList"
      @show-details="showHistoryDetails"
      @preview="viewHistoryVersion"
      @close-details="closeHistoryDetails"
      @preview-evidence="previewEvidenceImage"
    />
  </div>

  <div v-if="historyPreview.visible" class="history-preview-exit">
    <span>{{ historyRecordTitle(historyPreview.version) }}</span>
    <el-button :loading="historyPreview.loading" @click="exitHistoryPreview">退出预览</el-button>
  </div>

  <div v-if="isMapDataPage(activeKey) || historyPreview.visible" :class="['map-controls-toolbar', activeKey === 'overview' || activeEditDataset || historyPreview.visible ? 'with-panel' : '']">
    <div class="control-block">
      <button class="control-btn" type="button" @click="handleZoomIn" title="放大" aria-label="放大地图">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
          <line x1="12" y1="5" x2="12" y2="19"></line>
          <line x1="5" y1="12" x2="19" y2="12"></line>
        </svg>
      </button>
      <button class="control-btn" type="button" @click="handleZoomOut" title="缩小" aria-label="缩小地图">
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
          <line x1="5" y1="12" x2="19" y2="12"></line>
        </svg>
      </button>
      <button :class="['control-btn', 'td-btn', is3DActive ? 'active' : '']" type="button" @click="handleToggle3D" title="3D视图" aria-label="切换3D视图" :aria-pressed="is3DActive">
        3D
      </button>
      <button class="control-btn compass-btn" type="button" @click="handleResetCompass" title="指北针" aria-label="重置地图朝北">
        <div class="pitch-arrows">
          <svg class="caret-up" viewBox="0 0 24 24" width="10" height="10" fill="currentColor">
            <polygon points="12,4 2,18 22,18"></polygon>
          </svg>
          <svg class="caret-down" viewBox="0 0 24 24" width="10" height="10" fill="currentColor">
            <polygon points="12,20 2,6 22,6"></polygon>
          </svg>
        </div>
      </button>
    </div>

    <div class="control-block settings-block">
      <button
        :class="['control-btn', selectedDisplayRange !== DISPLAY_RANGE_ALL || showRangePopover ? 'active' : '']"
        type="button"
        @click="toggleRangePopover"
        :title="`显示范围：${selectedDisplayRangeLabel}`"
        aria-label="选择显示范围"
        :aria-expanded="showRangePopover"
        aria-controls="dm-range-popover"
      >
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round">
          <path d="M3 6.5 8 4l8 2.5 5-2.5v13.5L16 20l-8-2.5-5 2.5V6.5Z"></path>
          <path d="M8 4v13.5"></path>
          <path d="M16 6.5V20"></path>
        </svg>
      </button>
      <button
        :class="['control-btn', showStylePopover ? 'active' : '']"
        type="button"
        @click="toggleStylePopover"
        title="线路和站点样式"
        aria-label="打开线路和站点样式"
        :aria-expanded="showStylePopover"
        aria-controls="dm-style-popover"
      >
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
          <line x1="4" y1="7" x2="20" y2="7"></line>
          <circle cx="15" cy="7" r="1.5" fill="currentColor"></circle>
          <line x1="4" y1="12" x2="20" y2="12"></line>
          <circle cx="17" cy="12" r="1.5" fill="currentColor"></circle>
          <line x1="4" y1="17" x2="20" y2="17"></line>
          <circle cx="9" cy="17" r="1.5" fill="currentColor"></circle>
        </svg>
      </button>
    </div>

    <Transition name="popover-fade">
      <div v-if="showRangePopover" id="dm-range-popover" class="range-popover" role="dialog" aria-modal="false" @click.stop @keydown.esc.stop.prevent="closeRangePopover">
        <div class="popover-title">显示范围</div>
        <el-select
          v-model="selectedDisplayRange"
          class="range-select"
          filterable
          :loading="isLoadingDisplayRanges"
          aria-label="显示范围"
          @change="handleDisplayRangeSelect"
        >
          <el-option v-for="item in displayRangeOptions" :key="item" :label="item" :value="item"></el-option>
        </el-select>
        <p v-if="displayRangeError" class="range-error">{{ displayRangeError }}</p>
      </div>
    </Transition>

    <Transition name="popover-fade">
      <div v-if="showStylePopover" id="dm-style-popover" class="style-popover" role="dialog" aria-modal="false" @click.stop @keydown.esc.stop.prevent="closeStylePopover">
        <div class="popover-title">图层样式</div>
        <div class="slider-row">
          <span class="label">
            <span>线路粗细</span>
            <span class="val-text">{{ `${lineWidth}px` }}</span>
          </span>
          <el-slider v-model="lineWidth" :min="0.1" :max="2" :step="0.1" @input="applyLayerPaint" />
        </div>
        <div class="slider-row">
          <span class="label">
            <span>站点大小</span>
            <span class="val-text">{{ `${stationSize}px` }}</span>
          </span>
          <el-slider v-model="stationSize" :min="32" :max="96" :step="1" @input="applyLayerPaint" />
        </div>
      </div>
    </Transition>
  </div>

  <div
    v-if="lineRoutePicker.visible"
    class="line-route-picker"
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
    @update:visible="attributeTable.visible = $event"
    @toggle-route="toggleAttributeRouteStations"
    @reset="resetAttributeTableDraft"
    @remove-row="removeAttributeTableRow"
    @restore-row="restoreAttributeTableRow"
    @touch-row="markAttributeRowTouched"
    @apply="applyAttributeTableChanges"
  />
</template>

<script setup>
import { ElMessage, ElMessageBox } from "element-plus";
import { commitRealDataEdits, compareRealDataShp } from "@/api/realData.js";
import {
  getCachedAdminDistricts,
  getCachedAreaList,
  getCachedRealData,
  getCachedRealDataHistory,
  invalidateCachedHistory,
  invalidateCachedRealData,
  readCachedHistory,
  readCachedRealData,
} from "@/utils/realDataCache.js";
import "./tokens.css";
import AttributeTableDialog from "./components/AttributeTableDialog.vue";
import OverviewMetrics from "./components/OverviewMetrics.vue";
import HistoryPanel from "./components/HistoryPanel.vue";
import busStationIconUrl from "@/assets/images/datamanagement/bus-station.svg?url";
import busStationHighlightIconUrl from "@/assets/images/datamanagement/bus-station_highlight.svg?url";
import busDepotIconUrl from "@/assets/images/datamanagement/bus-depot.svg?url";
import { lngLatToWebMercator } from "@/mymap/index.js";

defineOptions({
  name: "DataManagement",
});

const MapRef = inject("MapRef", ref(null));
const activeKey = ref("overview");
const expandedKeys = ref(["update"]);
const areaList = ref(["广州市"]);
const selectedArea = ref("广州市");
const DISPLAY_RANGE_ALL = "全市";
const selectedDisplayRange = ref(DISPLAY_RANGE_ALL);
const displayRangeList = ref([DISPLAY_RANGE_ALL]);
const isLoadingDisplayRanges = ref(false);
const displayRangeError = ref("");
const showRangePopover = ref(false);
const isLoadingAreas = ref(false);
const isLoadingLayer = ref(false);
const isLoadingHistory = ref(false);
const loadError = ref("");
const historyError = ref("");
const realDataRevision = ref(0);
const realDataCollectionsRevision = ref(0);
const realDataVersionId = ref("__base__");
const historyVersions = ref([]);
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
  adminAreaKm2: null,
});
const lineWidth = ref(1.2);
const stationSize = ref(32);
const showStylePopover = ref(false);
const is3DActive = ref(false);
const selectedStation = ref(null);
const selectedRoute = ref(null);
const selectedDepot = ref(null);
const shpUploadInput = ref(null);
const evidenceImageInput = ref(null);
const searchKeyword = ref("");
const isSearchFocused = ref(false);
const isSubmittingEdit = ref(false);
const pendingAddDataset = ref("");
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
  station: null,
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
  title: "",
  subtitle: "",
  target: null,
  route: null,
  station: null,
  scope: "",
  showRouteStations: false,
  columns: [],
  rows: [],
  originalRows: [],
  viewCache: {},
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
let restoringAreaSelection = false;
let confirmedAreaSelection = false;
let zoomListenerId = null;
let rotateListenerId = null;
let stationClickListenerId = null;
let stationSearchIndex = [];
let lineSearchIndex = [];
let depotSearchIndex = [];
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
const LAYER_LINES = "dm-real-bus-lines";
const LAYER_LINE_SELECTED = "dm-real-bus-line-selected";
const LAYER_STATIONS = "dm-real-bus-stations";
const LAYER_STATION_LABELS = "dm-real-bus-station-labels";
const LAYER_STATION_SELECTED = "dm-real-bus-station-selected";
const LAYER_ROUTE_STATION_SELECTED = "dm-real-bus-route-station-selected";
const LAYER_DEPOTS = "dm-real-bus-depots";
const LAYER_DEPOT_LABELS = "dm-real-bus-depot-labels";
const LAYER_DEPOT_SELECTED = "dm-real-bus-depot-selected";
const SELECTED_LINE_COLOR = "#f97316";
const SELECTED_LINE_GLOW_COLOR = "#facc15";
const STATION_ICON_ID = "dm-real-bus-station-icon";
const STATION_HIGHLIGHT_ICON_ID = "dm-real-bus-station-highlight-icon";
const DEPOT_ICON_ID = "dm-real-bus-depot-icon";
const STATION_ICON_BASE_SIZE = 96;
const DEPOT_ICON_BASE_SIZE = 128;
const MAX_RENDERED_EDIT_OPERATIONS = 200;
const LINE_ATTRIBUTE_FIELD_ORDER = [
  "line_id",
  "dir",
  "route_id",
  "first",
  "last",
  "interval",
  "price",
  "company",
  "mode",
  "name",
];
const STATION_ATTRIBUTE_FIELD_ORDER = [
  "line_id",
  "dir",
  "stop_id",
  "stop_name",
  "seq",
  "lon",
  "lat",
];

const isExpanded = (key) => expandedKeys.value.includes(key);
const showMapSearch = computed(() => isMapDataPage(activeKey.value) || historyPreview.visible);
const activeEditDataset = computed(() => editDatasetFromKey(activeKey.value));
const displayRangeOptions = computed(() => {
  const names = displayRangeList.value.filter(Boolean);
  return names.includes(DISPLAY_RANGE_ALL) ? names : [DISPLAY_RANGE_ALL, ...names];
});
const selectedDisplayRangeLabel = computed(() => selectedDisplayRange.value || DISPLAY_RANGE_ALL);
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
const searchResults = computed(() => {
  const query = normalizeSearchText(searchKeyword.value);
  if (!query) return [];
  const isOverviewSearch = activeKey.value === "overview" || historyPreview.visible;
  const stationItems = isOverviewSearch || activeKey.value === "update_station" ? rankSearchItems(stationSearchIndex, query) : [];
  const lineItems = isOverviewSearch || activeKey.value === "update_line" ? rankSearchItems(lineSearchIndex, query) : [];
  const depotItems = isOverviewSearch || activeKey.value === "update_depot" ? rankSearchItems(depotSearchIndex, query) : [];
  return [...stationItems, ...lineItems, ...depotItems]
    .sort((left, right) => left.score - right.score || left.name.localeCompare(right.name, "zh-Hans-CN"))
    .slice(0, 8);
});
const showSearchResults = computed(() => isSearchFocused.value && Boolean(searchKeyword.value.trim()));
const activeEditOperations = computed(() => (activeEditDataset.value ? editOperations[activeEditDataset.value] : []));
const visibleActiveEditOperations = computed(() => activeEditOperations.value.slice(0, MAX_RENDERED_EDIT_OPERATIONS));
const hiddenActiveEditOperationCount = computed(() => Math.max(0, activeEditOperations.value.length - visibleActiveEditOperations.value.length));
const hasAnyUnsavedEdits = computed(() => editOperations.station.length + editOperations.line.length + editOperations.depot.length > 0);
const attributeTableChangedCount = computed(() => collectAttributeTableChangedRows().length);
const operatorLineRows = computed(() => {
  const collectionsRevision = realDataCollectionsRevision.value;
  const counts = new Map();
  const features = collectionsRevision >= 0 && Array.isArray(realDataCollections.lines?.features) ? realDataCollections.lines.features : [];
  features.forEach((feature) => {
    splitOperatorCompanies(feature?.properties?.company).forEach((company) => {
      counts.set(company, (counts.get(company) || 0) + 1);
    });
  });
  const rows = [...counts.entries()]
    .map(([company, lineCount]) => ({ company, lineCount }))
    .sort((left, right) => right.lineCount - left.lineCount || left.company.localeCompare(right.company, "zh-Hans-CN"));
  return rows.length ? rows : [{ company: "-", lineCount: "-" }];
});
const coverageDial = computed(() => {
  const size = 128;
  const ring = (rate, radius, stroke) => {
    const circumference = 2 * Math.PI * radius;
    const value = Number(rate);
    const hasValue = Number.isFinite(value);
    const fraction = hasValue ? Math.min(Math.max(value / 100, 0), 1) : 0;
    return { radius, stroke, circumference, dash: fraction * circumference, hasValue };
  };
  return {
    size,
    center: size / 2,
    outer: ring(overviewStats.stationCoverage500Rate, 52, 11),
    inner: ring(overviewStats.stationCoverage300Rate, 37, 11),
  };
});
const lineRoutePickerTitle = computed(() => {
  if (lineRoutePicker.mode === "station_edit") return "选择该站点所属线路";
  if (lineRoutePicker.mode === "edit") return "选择经过该路段的线路";
  return "选择线路";
});
const lineRoutePickerHint = computed(() => {
  if (lineRoutePicker.mode === "station_edit") return "选中线路后打开该线路的本站属性表。";
  if (lineRoutePicker.mode === "edit") return "选中线路后打开筛选后的属性表。";
  return "";
});
const editDatasetKicker = computed(() => {
  if (activeEditDataset.value === "station") return "站点编辑";
  if (activeEditDataset.value === "line") return "线路编辑";
  if (activeEditDataset.value === "depot") return "场站编辑";
  return "数据编辑";
});
const editDatasetTitle = computed(() => {
  if (activeEditDataset.value === "station") return "站点数据更新";
  if (activeEditDataset.value === "line") return "线路数据更新";
  if (activeEditDataset.value === "depot") return "场站数据更新";
  return "数据更新";
});
const editModeGuide = computed(() => {
  const guides = {
    station: {
      title: selectedStation.value ? "已打开当前站点属性" : "选择站点后自动打开属性表",
      description: selectedStation.value ? "可直接维护当前站点相关记录，也可上传完整 SHP 自动比对。" : "可搜索站点，也可直接点击地图上的站点。",
      steps: ["属性表只显示选中站点相关记录", "可编辑单元格，也可新增或删除行", "生成的修改会在此逐条核对后提交"],
      actionLabel: "",
      canStartAdd: false,
    },
    line: {
      title: selectedRoute.value ? "已打开当前线路属性" : "选择线路后自动打开属性表",
      description: selectedRoute.value ? "可直接维护当前线路记录，也可上传完整 SHP 自动比对。" : "可搜索线路，也可点击地图上的线路。多条线路重叠时先选择要编辑的线路。",
      steps: ["属性表只显示选中线路相关记录", "可编辑单元格，也可新增或删除行", "生成的修改会在此逐条核对后提交"],
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

const handleItemClick = async (item) => {
  if (item.children) {
    const index = expandedKeys.value.indexOf(item.key);
    if (index > -1) {
      expandedKeys.value.splice(index, 1);
    } else {
      expandedKeys.value.push(item.key);
    }
    if (!item.children.some((child) => child.key === activeKey.value)) {
      await setActiveKey(item.children[0].key);
    }
  } else {
    await setActiveKey(item.key);
  }
};

async function setActiveKey(key) {
  if (!key || key === activeKey.value) return;
  const canLeave = await confirmLeaveWithUnsavedEdits();
  if (!canLeave) return;
  activeKey.value = key;
}

const menuItems = [
  {
    key: "overview",
    label: "数据总览",
    icon: `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7" rx="1"></rect><rect x="14" y="3" width="7" height="7" rx="1"></rect><rect x="3" y="14" width="7" height="7" rx="1"></rect><rect x="14" y="14" width="7" height="7" rx="1"></rect></svg>`,
  },
  {
    key: "update",
    label: "数据更新",
    icon: `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="23 4 23 10 17 10"></polyline><polyline points="1 20 1 14 7 14"></polyline><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"></path></svg>`,
    children: [
      { key: "update_line", label: "线路数据更新" },
      { key: "update_station", label: "站点数据更新" },
      { key: "update_depot", label: "场站数据更新" },
    ],
  },
  {
    key: "history",
    label: "历史数据查询",
    icon: `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline></svg>`,
  },
];

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
  if (!selectedArea.value || !mode) return;
  const cachedData = readCachedRealData(selectedArea.value);
  if (!force && cachedData) {
    const data = cachedData;
    setOverviewStats(data);
    syncHistorySummary(data.history);
    renderRealDataLayers(data, mode);
    if (fit) fitBounds(data.bounds);
    return;
  }
  const seq = ++layerRequestSeq;
  isLoadingLayer.value = true;
  loadError.value = "";
  try {
    const data = await getCachedRealData(selectedArea.value, { force });
    if (seq !== layerRequestSeq) return;
    setOverviewStats(data);
    syncHistorySummary(data.history);
    renderRealDataLayers(data, mode);
    if (fit) fitBounds(data.bounds);
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

function setOverviewStats(data) {
  const overview = data?.overview || {};
  overviewStats.lineCount = Number(overview.lineCount ?? data?.lines?.featureCount ?? 0);
  overviewStats.networkScaleKm = nullableNumber(overview.networkScaleKm);
  overviewStats.networkDensityKmPerKm2 = nullableNumber(overview.networkDensityKmPerKm2);
  overviewStats.stationCount = Number(overview.stationCount ?? data?.stations?.featureCount ?? 0);
  overviewStats.stationCoverage300Rate = nullableNumber(overview.stationCoverage300Rate);
  overviewStats.stationCoverage500Rate = nullableNumber(overview.stationCoverage500Rate);
  overviewStats.adminAreaKm2 = nullableNumber(overview.adminAreaKm2);
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

function resetOverviewStats() {
  overviewStats.lineCount = 0;
  overviewStats.networkScaleKm = null;
  overviewStats.networkDensityKmPerKm2 = null;
  overviewStats.stationCount = 0;
  overviewStats.stationCoverage300Rate = null;
  overviewStats.stationCoverage500Rate = null;
  overviewStats.adminAreaKm2 = null;
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
    realDataAllCollections = {
      lines: normalizeLineFeatureCollection(data.lines),
      stations: normalizeStationFeatureCollection(data.stations),
      routeStops: normalizeRouteStopFeatureCollection(data.routeStops),
      depots: normalizeDepotFeatureCollection(data.depots),
    };
    applyDisplayRangeFilter({ updateSources: false, clearSelection: false });
    lineSearchIndex = buildLineSearchIndex(realDataCollections.lines);
    stationSearchIndex = buildStationSearchIndex(realDataCollections.stations);
    depotSearchIndex = buildDepotSearchIndex(realDataCollections.depots);
    ensureSourceData(map, SOURCE_LINES, realDataCollections.lines);
    ensureSourceData(map, SOURCE_SELECTED_LINE, emptyFeatureCollection());
    ensureSourceData(map, SOURCE_STATIONS, realDataCollections.stations);
    ensureSourceData(map, SOURCE_SELECTED_STATION, emptyFeatureCollection());
    ensureSourceData(map, SOURCE_SELECTED_ROUTE_STATIONS, emptyFeatureCollection());
    ensureSourceData(map, SOURCE_DEPOTS, realDataCollections.depots);
    ensureSourceData(map, SOURCE_SELECTED_DEPOT, emptyFeatureCollection());
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

function ensureSourceData(map, sourceId, data) {
  const source = map.getSource(sourceId);
  if (source?.setData) {
    source.setData(data);
    return;
  }
  map.addSource(sourceId, { type: "geojson", data });
}

function ensureRealDataLayerSet(map) {
  if (!map.getLayer(LAYER_LINES)) {
    addLayerBelowBuildings(map, {
      id: LAYER_LINES,
      type: "line",
      source: SOURCE_LINES,
      paint: {
        "line-color": lineColorPaint(),
        "line-opacity": lineOpacityPaint(),
        "line-width": lineWidth.value,
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
        "line-opacity": 0.42,
        "line-width": selectedLineWidth() * 2.2,
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
  if (!map.getLayer(LAYER_ROUTE_STATION_SELECTED)) {
    map.addLayer({
      id: LAYER_ROUTE_STATION_SELECTED,
      type: "symbol",
      source: SOURCE_SELECTED_ROUTE_STATIONS,
      layout: stationIconLayout(STATION_HIGHLIGHT_ICON_ID, selectedRouteStationIconScale()),
      paint: {
        "icon-opacity": 0.96,
      },
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
  setLayerVisibility(map, LAYER_DEPOTS, visibility.depots ? visible : hidden);
  setLayerVisibility(map, LAYER_DEPOT_LABELS, visibility.depotLabels ? visible : hidden);
  setLayerVisibility(map, LAYER_DEPOT_SELECTED, visibility.depots ? visible : hidden);
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
    "text-color": "#1f3132",
    "text-opacity": ["interpolate", ["linear"], ["zoom"], 8, 0.72, 11, 0.92, 14, 1],
    "text-halo-color": "rgba(248, 251, 252, 0.94)",
    "text-halo-width": 1.5,
    "text-halo-blur": 0.4,
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
  if (!map.hasImage?.(STATION_ICON_ID)) {
    const image = await loadIconImageData(busStationIconUrl, STATION_ICON_BASE_SIZE);
    map.addImage(STATION_ICON_ID, image);
  }
  if (!map.hasImage?.(STATION_HIGHLIGHT_ICON_ID)) {
    const image = await loadIconImageData(busStationHighlightIconUrl, STATION_ICON_BASE_SIZE);
    map.addImage(STATION_HIGHLIGHT_ICON_ID, image);
  }
  if (!map.hasImage?.(DEPOT_ICON_ID)) {
    const image = await loadIconImageData(busDepotIconUrl, DEPOT_ICON_BASE_SIZE);
    map.addImage(DEPOT_ICON_ID, image);
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
  return [
    "interpolate",
    ["exponential", 1.25],
    ["zoom"],
    8,
    0.024,
    10,
    highZoomScale * 0.12,
    12,
    highZoomScale * 0.32,
    14,
    highZoomScale * 0.68,
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

function lineColorPaint() {
  const keys = selectedRouteLineKeys();
  if (!keys.length) {
    const stationLineKeys = selectedStationLineKeys();
    if (stationLineKeys.length) {
      return ["match", ["to-string", ["get", "_lineKey"]], stationLineKeys, "#2f6f73", "#8ca0a4"];
    }
    return "#2f6f73";
  }
  return ["match", ["to-string", ["get", "_lineKey"]], keys, "#2f6f73", "#8ca0a4"];
}

function lineOpacityPaint() {
  const keys = selectedRouteLineKeys();
  if (!keys.length) {
    const stationLineKeys = selectedStationLineKeys();
    if (stationLineKeys.length) {
      return ["match", ["to-string", ["get", "_lineKey"]], stationLineKeys, 0.72, 0.12];
    }
    return 0.7;
  }
  return ["match", ["to-string", ["get", "_lineKey"]], keys, 0.1, 0.18];
}

function stationOpacityPaint() {
  const stationId = selectedStation.value?.id;
  if (stationId) {
    return ["case", ["==", ["get", "_stationKey"], stationId], 0, 0.24];
  }
  const routeStationKeys = selectedRouteStationKeys();
  if (routeStationKeys.length) {
    return ["match", ["to-string", ["get", "_stationKey"]], routeStationKeys, 0, 0.2];
  }
  return 0.96;
}

function normalizeLineFeatureCollection(collection) {
  const features = Array.isArray(collection?.features) ? collection.features : [];
  return {
    type: "FeatureCollection",
    features: features.map((feature, index) => normalizeLineFeature(feature, index)),
  };
}

function normalizeFeatureCollection(collection) {
  return {
    type: "FeatureCollection",
    features: Array.isArray(collection?.features) ? collection.features : [],
  };
}

function normalizeLineFeature(feature, index = 0) {
  const lineKey = lineFeatureKey(feature, index);
  return {
    ...feature,
    id: feature?.id ?? lineKey,
    properties: {
      ...(feature?.properties || {}),
      _lineKey: lineKey,
    },
  };
}

function normalizeStationFeatureCollection(collection) {
  const features = Array.isArray(collection?.features) ? collection.features : [];
  return {
    type: "FeatureCollection",
    features: features.map((feature, index) => normalizeStationFeature(feature, index)),
  };
}

function normalizeRouteStopFeatureCollection(collection) {
  const features = Array.isArray(collection?.features) ? collection.features : [];
  return {
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
    type: "FeatureCollection",
    features: features.map((feature, index) => normalizeDepotFeature(feature, index)),
  };
}

function normalizeStationFeature(feature, index = 0) {
  const properties = feature?.properties || {};
  return {
    type: "Feature",
    id: feature?.id,
    geometry: feature?.geometry || null,
    properties: {
      ...properties,
      _featureId: properties._featureId || feature?.id || properties._featureId,
      _stationKey: stationFeatureKey(feature, index),
    },
  };
}

function normalizeRouteStopFeature(feature, index = 0) {
  const properties = feature?.properties || {};
  const coordinates = pointCoordinates(feature?.geometry);
  return {
    type: "Feature",
    id: feature?.id,
    geometry: feature?.geometry || null,
    properties: {
      ...properties,
      _featureId: properties._featureId || feature?.id || properties._featureId,
      _routeStopKey: String(
        properties._routeStopKey ||
          [properties.line_id, properties.stop_id, properties.seq, coordinates?.[0], coordinates?.[1], index].filter(Boolean).join("-") ||
          `route-stop-${index}`,
      ),
    },
  };
}

function normalizeDepotFeature(feature, index = 0) {
  const properties = feature?.properties || {};
  return {
    type: "Feature",
    id: feature?.id,
    geometry: feature?.geometry || null,
    properties: {
      ...properties,
      _featureId: properties._featureId || feature?.id || properties._featureId,
      _depotKey: depotFeatureKey(feature, index),
    },
  };
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
  if (!context) {
    realDataCollections = {
      lines: realDataAllCollections.lines,
      stations: realDataAllCollections.stations,
      routeStops: realDataAllCollections.routeStops,
      depots: realDataAllCollections.depots,
    };
  } else {
    realDataCollections = {
      lines: featureCollectionFromFeatures((realDataAllCollections.lines?.features || []).filter((feature) => lineFeatureIntersectsRange(feature, context))),
      stations: featureCollectionFromFeatures((realDataAllCollections.stations?.features || []).filter((feature) => pointFeatureInRange(feature, context))),
      routeStops: featureCollectionFromFeatures((realDataAllCollections.routeStops?.features || []).filter((feature) => pointFeatureInRange(feature, context))),
      depots: featureCollectionFromFeatures((realDataAllCollections.depots?.features || []).filter((feature) => pointFeatureInRange(feature, context))),
    };
  }
  realDataCollectionsRevision.value += 1;
  lineSearchIndex = buildLineSearchIndex(realDataCollections.lines);
  stationSearchIndex = buildStationSearchIndex(realDataCollections.stations);
  depotSearchIndex = buildDepotSearchIndex(realDataCollections.depots);
  if (shouldClearSelection) {
    clearSelectionState();
  }
  if (updateSources) {
    syncRealDataSourceData();
  }
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
  };
}

function syncRealDataSourceData() {
  const map = MapRef.value?.map;
  if (!map) return;
  map.getSource(SOURCE_LINES)?.setData?.(realDataCollections.lines);
  map.getSource(SOURCE_STATIONS)?.setData?.(realDataCollections.stations);
  map.getSource(SOURCE_DEPOTS)?.setData?.(realDataCollections.depots);
  updateStationSelectionLayers();
  if (selectedRoute.value) {
    updateSelectedLineLayer(selectedRoute.value.feature);
  } else {
    clearSelectedLineLayer();
  }
  updateSelectedDepotLayer(selectedDepot.value?.feature || null);
  applyLayerPaint();
}

function featureCollectionFromFeatures(features = []) {
  return {
    type: "FeatureCollection",
    features,
    featureCount: features.length,
    bounds: featureCollectionBounds(features),
  };
}

function pointFeatureInRange(feature, context) {
  const coordinate = pointCoordinates(feature?.geometry);
  return coordinate ? pointInRangeContext(coordinate, context) : false;
}

function lineFeatureIntersectsRange(feature, context) {
  const paths = lineCoordinatePaths(feature?.geometry);
  if (!paths.length) return pointFeatureInRange(feature, context);
  for (const path of paths) {
    const coordinates = path.map(validLngLat).filter(Boolean);
    if (!coordinates.length) continue;
    if (coordinates.some((coordinate) => pointInRangeContext(coordinate, context))) {
      return true;
    }
    for (let index = 1; index < coordinates.length; index += 1) {
      if (segmentIntersectsRangeContext(coordinates[index - 1], coordinates[index], context)) {
        return true;
      }
    }
  }
  return false;
}

function pointInRangeContext(coordinate, context) {
  if (!coordinate || !boundsContainPoint(context.bounds, coordinate)) return false;
  return context.polygons.some((rings) => pointInPolygonRings(coordinate, rings));
}

function segmentIntersectsRangeContext(start, end, context) {
  if (!boundsIntersect(segmentBounds(start, end), context.bounds)) return false;
  for (const rings of context.polygons) {
    for (const ring of rings) {
      for (let index = 1; index < ring.length; index += 1) {
        if (segmentsIntersect(start, end, ring[index - 1], ring[index])) {
          return true;
        }
      }
    }
  }
  return false;
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

function pointInPolygonRings(point, rings) {
  if (!rings.length || !pointInRing(point, rings[0])) return false;
  for (let index = 1; index < rings.length; index += 1) {
    if (pointInRing(point, rings[index])) return false;
  }
  return true;
}

function pointInRing(point, ring) {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i, i += 1) {
    const current = ring[i];
    const previous = ring[j];
    if (pointOnSegment(point, previous, current)) return true;
    const intersects = current[1] > point[1] !== previous[1] > point[1]
      && point[0] < ((previous[0] - current[0]) * (point[1] - current[1])) / (previous[1] - current[1]) + current[0];
    if (intersects) inside = !inside;
  }
  return inside;
}

function segmentsIntersect(a, b, c, d) {
  const o1 = orientation(a, b, c);
  const o2 = orientation(a, b, d);
  const o3 = orientation(c, d, a);
  const o4 = orientation(c, d, b);
  if (o1 !== o2 && o3 !== o4) return true;
  return (o1 === 0 && pointOnSegment(c, a, b))
    || (o2 === 0 && pointOnSegment(d, a, b))
    || (o3 === 0 && pointOnSegment(a, c, d))
    || (o4 === 0 && pointOnSegment(b, c, d));
}

function orientation(a, b, c) {
  const value = (b[1] - a[1]) * (c[0] - b[0]) - (b[0] - a[0]) * (c[1] - b[1]);
  if (Math.abs(value) < 1e-12) return 0;
  return value > 0 ? 1 : 2;
}

function pointOnSegment(point, start, end) {
  const cross = (point[1] - start[1]) * (end[0] - start[0]) - (point[0] - start[0]) * (end[1] - start[1]);
  if (Math.abs(cross) > 1e-12) return false;
  return point[0] <= Math.max(start[0], end[0]) + 1e-12
    && point[0] + 1e-12 >= Math.min(start[0], end[0])
    && point[1] <= Math.max(start[1], end[1]) + 1e-12
    && point[1] + 1e-12 >= Math.min(start[1], end[1]);
}

function segmentBounds(start, end) {
  return [
    Math.min(start[0], end[0]),
    Math.min(start[1], end[1]),
    Math.max(start[0], end[0]),
    Math.max(start[1], end[1]),
  ];
}

function boundsContainPoint(bounds, point) {
  return Array.isArray(bounds)
    && point[0] >= bounds[0]
    && point[0] <= bounds[2]
    && point[1] >= bounds[1]
    && point[1] <= bounds[3];
}

function boundsIntersect(left, right) {
  if (!Array.isArray(left) || !Array.isArray(right)) return true;
  return left[0] <= right[2] && left[2] >= right[0] && left[1] <= right[3] && left[3] >= right[1];
}

function featureCollectionBounds(features = []) {
  const bounds = [Infinity, Infinity, -Infinity, -Infinity];
  features.forEach((feature) => expandGeometryBounds(feature?.geometry, bounds));
  return Number.isFinite(bounds[0]) ? bounds : null;
}

function geometryBounds(geometry) {
  const bounds = [Infinity, Infinity, -Infinity, -Infinity];
  expandGeometryBounds(geometry, bounds);
  return Number.isFinite(bounds[0]) ? bounds : null;
}

function expandGeometryBounds(geometry, bounds) {
  if (!geometry?.coordinates) return;
  expandCoordinateBounds(geometry.coordinates, bounds);
}

function expandCoordinateBounds(value, bounds) {
  if (!Array.isArray(value)) return;
  if (value.length >= 2 && Number.isFinite(Number(value[0])) && Number.isFinite(Number(value[1]))) {
    const lng = Number(value[0]);
    const lat = Number(value[1]);
    bounds[0] = Math.min(bounds[0], lng);
    bounds[1] = Math.min(bounds[1], lat);
    bounds[2] = Math.max(bounds[2], lng);
    bounds[3] = Math.max(bounds[3], lat);
    return;
  }
  value.forEach((item) => expandCoordinateBounds(item, bounds));
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
  if (map.getLayer(LAYER_DEPOT_SELECTED)) map.removeLayer(LAYER_DEPOT_SELECTED);
  if (map.getLayer(LAYER_DEPOT_LABELS)) map.removeLayer(LAYER_DEPOT_LABELS);
  if (map.getLayer(LAYER_DEPOTS)) map.removeLayer(LAYER_DEPOTS);
  if (map.getLayer(LAYER_ROUTE_STATION_SELECTED)) map.removeLayer(LAYER_ROUTE_STATION_SELECTED);
  if (map.getLayer(LAYER_STATION_SELECTED)) map.removeLayer(LAYER_STATION_SELECTED);
  if (map.getLayer(LAYER_STATION_LABELS)) map.removeLayer(LAYER_STATION_LABELS);
  if (map.getLayer(LAYER_STATIONS)) map.removeLayer(LAYER_STATIONS);
  if (map.getLayer(LAYER_LINE_SELECTED)) map.removeLayer(LAYER_LINE_SELECTED);
  if (map.getLayer(LAYER_LINE_SELECTED + "-glow")) map.removeLayer(LAYER_LINE_SELECTED + "-glow");
  if (map.getLayer(LAYER_LINES)) map.removeLayer(LAYER_LINES);
  if (map.getSource(SOURCE_SELECTED_DEPOT)) map.removeSource(SOURCE_SELECTED_DEPOT);
  if (map.getSource(SOURCE_DEPOTS)) map.removeSource(SOURCE_DEPOTS);
  if (map.getSource(SOURCE_SELECTED_ROUTE_STATIONS)) map.removeSource(SOURCE_SELECTED_ROUTE_STATIONS);
  if (map.getSource(SOURCE_SELECTED_STATION)) map.removeSource(SOURCE_SELECTED_STATION);
  if (map.getSource(SOURCE_STATIONS)) map.removeSource(SOURCE_STATIONS);
  if (map.getSource(SOURCE_SELECTED_LINE)) map.removeSource(SOURCE_SELECTED_LINE);
  if (map.getSource(SOURCE_LINES)) map.removeSource(SOURCE_LINES);
  clearSelectionState();
  stationSearchIndex = [];
  lineSearchIndex = [];
  depotSearchIndex = [];
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
    map.setPaintProperty(LAYER_LINES, "line-color", lineColorPaint());
    map.setPaintProperty(LAYER_LINES, "line-width", lineWidth.value);
    map.setPaintProperty(LAYER_LINES, "line-opacity", lineOpacityPaint());
  }
  if (map.getLayer(LAYER_LINE_SELECTED)) {
    map.setPaintProperty(LAYER_LINE_SELECTED, "line-color", SELECTED_LINE_COLOR);
    map.setPaintProperty(LAYER_LINE_SELECTED, "line-width", selectedLineWidth());
  }
  if (map.getLayer(LAYER_LINE_SELECTED + "-glow")) {
    map.setPaintProperty(LAYER_LINE_SELECTED + "-glow", "line-color", SELECTED_LINE_GLOW_COLOR);
    map.setPaintProperty(LAYER_LINE_SELECTED + "-glow", "line-opacity", 0.42);
    map.setPaintProperty(LAYER_LINE_SELECTED + "-glow", "line-width", selectedLineWidth() * 2.2);
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
  const queryBox = queryBoxAround(point, 8);
  const queryLayers = [LAYER_STATION_SELECTED, LAYER_STATIONS].filter((layerId) => map.getLayer(layerId));
  const features = queryLayers.length ? map.queryRenderedFeatures(queryBox, { layers: queryLayers }) : [];
  const stationFeature = features.find((item) => item.layer?.id === LAYER_STATION_SELECTED) || features.find((item) => item.layer?.id === LAYER_STATIONS);
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
  if (!Array.isArray(point)) return;
  const feature = firstRenderedFeature(point, [LAYER_STATION_SELECTED, LAYER_STATIONS]);
  if (feature) {
    selectStation(feature);
    showStationRoutePicker(event, selectedStation.value);
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
}

function handleUploadShpClick() {
  const datasetType = activeEditDataset.value;
  if (datasetType !== "line" && datasetType !== "station" && datasetType !== "depot") {
    ElMessage.warning("当前仅支持上传标准线路、站点或场站 SHP");
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
      ElMessage.success("上传 SHP 与当前数据一致，无需生成修改");
      return;
    }
    appendUploadOperations(datasetType, operations);
    ElMessage.success(`已从上传 SHP 识别 ${operations.length} 条${datasetTypeLabel(datasetType)}修改，请在右侧核对后提交`);
  } catch (error) {
    ElMessage.error(error?.message || "上传 SHP 比对失败，请检查文件格式");
  } finally {
    isSubmittingEdit.value = false;
    if (event?.target) event.target.value = "";
  }
}

function isAttributeEditableDataset(datasetType) {
  return datasetType === "line" || datasetType === "station" || datasetType === "depot";
}

function openAttributeTable(datasetType, target) {
  if (!isAttributeEditableDataset(datasetType) || !target) return;
  const initialScope = datasetType === "station" && target?.route ? "station" : datasetType;
  const view = buildAttributeTableView(datasetType, target, initialScope);
  if (!view.rows.length) {
    ElMessage.warning("未找到可编辑的属性记录");
    return;
  }
  attributeTable.datasetType = datasetType;
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
}

function buildAttributeTableView(datasetType, target, scope) {
  const rows = buildAttributeTableRows(datasetType, target, scope);
  const columns = buildAttributeTableColumns(datasetType, rows);
  const normalizedRows = rows.map((row) => ensureAttributeRowColumns(row, columns));
  return {
    scope,
    subtitle: attributeTableSubtitle(datasetType, target, scope),
    columns,
    rows: normalizedRows,
    originalRows: deepClone(normalizedRows),
  };
}

function setAttributeTableView(view) {
  attributeTable.scope = view.scope;
  attributeTable.showRouteStations = view.scope === "route";
  attributeTable.subtitle = view.subtitle;
  attributeTable.columns = deepClone(view.columns);
  attributeTable.rows = deepClone(view.rows);
  attributeTable.originalRows = deepClone(view.originalRows);
}

function captureAttributeTableView() {
  return {
    scope: attributeTable.scope,
    subtitle: attributeTable.subtitle,
    columns: deepClone(attributeTable.columns),
    rows: deepClone(attributeTable.rows),
    originalRows: deepClone(attributeTable.originalRows),
  };
}

function attributeTableSubtitle(datasetType, target, scope = attributeTable.scope) {
  if (datasetType === "line") {
    return `当前筛选：${parsePickerRoute(target.name || routeName(target.properties)).mainName}`;
  }
  if (datasetType === "depot") {
    return `当前筛选：${target?.name || depotName(target?.feature?.properties || target?.properties)}`;
  }
  const station = target.station || target;
  const route = target.route;
  const stationText = station.name || stationName(station.feature?.properties);
  const routeText = route ? parsePickerRoute(route.name || routeName(route.properties)).mainName : "";
  if (route && scope === "route") return `当前筛选：${routeText} / 全线站点（按站序）`;
  return route ? `当前筛选：${routeText} / ${stationText}` : `当前筛选：${stationText}`;
}

function attributeRouteContext(datasetType, target) {
  if (datasetType === "line") return target;
  return target?.route || null;
}

function buildAttributeTableRows(datasetType, target, scope = attributeTable.scope) {
  if (datasetType === "line") {
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
  const features = routeStopFeaturesForSelectedStation(target);
  return features.map((feature, index) => attributeRowFromFeature(datasetType, feature, index));
}

function routeStopFeaturesForSelectedStation(target) {
  const selectedStationTarget = target?.station || target;
  const selectedFeature = selectedStationTarget?.feature || selectedStationTarget;
  const route = target?.route || null;
  const properties = selectedFeature?.properties || {};
  const stationId = valueOrEmpty(selectedStationTarget?.id || properties.stop_id || properties._stationKey);
  const name = valueOrEmpty(selectedStationTarget?.name || stationName(properties));
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

function attributeRowFromFeature(datasetType, feature, index = 0) {
  const properties = { ...(feature?.properties || {}) };
  const displayProperties = displayAttributeProperties(properties);
  return {
    rowId: `${datasetType}-${featureTargetId(feature) || index}-${index}`,
    status: "existing",
    targetId: attributeTargetId(datasetType, feature),
    featureId: feature?.id || properties._featureId || "",
    geometry: feature?.geometry ? deepClone(feature.geometry) : null,
    baseProperties: properties,
    originalProperties: deepClone(displayProperties),
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

function buildAttributeTableColumns(datasetType, rows) {
  const ordered = datasetType === "line" ? LINE_ATTRIBUTE_FIELD_ORDER : datasetType === "depot" ? [] : STATION_ATTRIBUTE_FIELD_ORDER;
  const keys = new Set();
  ordered.forEach((key) => keys.add(key));
  rows.forEach((row) => Object.keys(row.properties || {}).forEach((key) => keys.add(key)));
  return [...keys].filter(Boolean).map((key) => {
    let maxLen = String(attributeColumnLabel(key)).length;
    rows.forEach((row) => {
      const value = row.properties?.[key];
      if (value != null) maxLen = Math.max(maxLen, String(value).length);
    });
    return { key, label: attributeColumnLabel(key), wide: maxLen > 16 };
  });
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

function addAttributeTableRow() {
  if (!attributeTable.datasetType) return;
  const now = Date.now();
  const featureId = `table_${attributeTable.datasetType}_${now}_${Math.random().toString(36).slice(2, 7)}`;
  const properties = {};
  attributeTable.columns.forEach((column) => {
    properties[column.key] = "";
  });
  if (attributeTable.datasetType === "line") {
    properties.name = selectedRoute.value?.name || properties.name || "";
  } else if (attributeTable.datasetType === "depot") {
    // 场站属性列来自 shp 字段，新增行保持空值由用户填写
  } else {
    fillRoutePropertiesForNewStationRow(properties);
    if (attributeTable.showRouteStations) {
      properties.seq = properties.seq || String(nextAttributeRouteSequence());
      properties.stop_id = properties.stop_id || featureId;
    } else {
      const tableStation = attributeTable.station || selectedStation.value;
      properties.stop_name = tableStation?.name || properties.stop_name || "";
      properties.stop_id = tableStation?.id || properties.stop_id || featureId;
    }
  }
  const depotKeyProps = { _depotKey: featureId };
  const stationKeyProps = { _stationKey: properties.stop_id || featureId, _routeStopKey: featureId };
  const row = {
    rowId: featureId,
    status: "added",
    targetId: featureId,
    featureId,
    geometry: defaultAttributeRowGeometry(attributeTable.datasetType),
    baseProperties: {
      _featureId: featureId,
      ...(attributeTable.datasetType === "line" ? { _lineKey: featureId } : attributeTable.datasetType === "depot" ? depotKeyProps : stationKeyProps),
    },
    originalProperties: {},
    properties,
    sourceIndex: -1,
  };
  if (attributeTable.datasetType === "station" && attributeTable.showRouteStations) {
    attributeTable.rows.push(row);
  } else {
    attributeTable.rows.unshift(row);
  }
}

function fillRoutePropertiesForNewStationRow(properties) {
  const routeProperties = attributeTable.route?.properties || {};
  if (!attributeTable.route) return;
  properties.line_id = properties.line_id || routeDataId(routeProperties);
  properties.dir = properties.dir || valueOrEmpty(routeProperties.dir);
}

function nextAttributeRouteSequence() {
  const values = attributeTable.rows
    .map((row) => Number(firstAvailableValue(row.properties || {}, ["seq"])))
    .filter((value) => Number.isFinite(value));
  return values.length ? Math.max(...values) + 1 : attributeTable.rows.length + 1;
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
    return;
  }
  row.status = "deleted";
}

function restoreAttributeTableRow(row) {
  row.status = "existing";
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
  if (row.status === "added") return "added";
  if (row.status === "deleted") return "deleted";
  if (attributeRowChanged(row)) return "modified";
  return "normal";
}

function attributeRecordTitle(row) {
  const properties = row.properties || {};
  const datasetType = attributeTable.datasetType;
  if (datasetType === "line") return routeName(properties) || properties.name || "未命名线路";
  if (datasetType === "depot") return depotName(properties) || "未命名场站";
  return stationName(properties) || properties.stop_name || "未命名站点";
}

function toggleAttributeRouteStations() {
  if (attributeTable.datasetType !== "station" || !attributeTable.route) return;
  const currentScope = attributeTable.scope || "station";
  const nextScope = currentScope === "route" ? "station" : "route";
  attributeTable.viewCache[currentScope] = captureAttributeTableView();
  const cachedView = attributeTable.viewCache[nextScope];
  if (cachedView) {
    setAttributeTableView(cachedView);
    return;
  }
  const nextView = buildAttributeTableView(attributeTable.datasetType, attributeTable.target, nextScope);
  if (!nextView.rows.length) {
    ElMessage.warning("未找到该线路的完整站点属性记录");
    return;
  }
  setAttributeTableView(nextView);
}

function applyAttributeTableChanges() {
  const datasetType = attributeTable.datasetType;
  const operations = collectAttributeTableChangedRows()
    .map((row) => attributeRowOperation(datasetType, row))
    .filter(Boolean);
  if (!operations.length) {
    attributeTable.visible = false;
    return;
  }
  appendUploadOperations(datasetType, operations);
  attributeTable.visible = false;
  ElMessage.success(`已生成 ${operations.length} 条属性表修改`);
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
  return String(row.targetId || row.featureId || row.rowId || "");
}

function attributeRowOperation(datasetType, row) {
  const feature = attributeRowFeature(row);
  const targetId = row.targetId || attributeTargetId(datasetType, feature);
  const title = attributeOperationTitle(datasetType, row, feature);
  const operationId = `${Date.now()}_${datasetType}_${row.status}_${Math.random().toString(36).slice(2, 8)}`;
  if (row.status === "added") {
    return {
      operationId,
      datasetType,
      type: `add_${datasetType}_from_table`,
      targetId,
      title,
      detail: "属性表新增整行",
      payload: { feature },
    };
  }
  if (row.status === "deleted") {
    return {
      operationId,
      datasetType,
      type: `delete_${datasetType}_from_table`,
      targetId,
      title,
      detail: "属性表删除整行",
      payload: {
        targetId,
        featureId: row.featureId || "",
        lineKey: datasetType === "line" ? row.baseProperties?._lineKey || "" : "",
        stationKey: datasetType === "station" ? row.baseProperties?._stationKey || row.properties?.stop_id || "" : "",
        depotKey: datasetType === "depot" ? row.baseProperties?._depotKey || "" : "",
      },
    };
  }
  return {
    operationId,
    datasetType,
    type: `replace_${datasetType}_from_table`,
    targetId,
    title,
    detail: `属性表修改：${changedAttributeLabels(row).join("、") || "属性"}`,
    payload: { targetId, feature },
  };
}

function attributeRowFeature(row) {
  const id = row.featureId || row.targetId || row.rowId;
  const datasetType = attributeTable.datasetType;
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
  return {
    type: "Feature",
    id,
    geometry: row.geometry ? deepClone(row.geometry) : defaultAttributeRowGeometry(attributeTable.datasetType),
    properties,
  };
}

function changedAttributeLabels(row) {
  const labels = [];
  const keys = new Set([...Object.keys(row.properties || {}), ...Object.keys(row.originalProperties || {})]);
  keys.forEach((key) => {
    const before = row.originalProperties?.[key] == null ? "" : String(row.originalProperties[key]);
    const after = row.properties?.[key] == null ? "" : String(row.properties[key]);
    if (before !== after) labels.push(attributeColumnLabel(key));
  });
  return labels.slice(0, 5);
}

function attributeOperationTitle(datasetType, row, feature) {
  if (datasetType === "line") return routeName(feature.properties) || row.properties?.name || "未命名线路";
  if (datasetType === "depot") return depotName(feature.properties) || depotName(row.properties) || "未命名场站";
  return stationName(feature.properties) || row.properties?.stop_name || "未命名站点";
}

function attributeTargetId(datasetType, feature) {
  const properties = feature?.properties || {};
  if (datasetType === "station") {
    return feature?.id || properties._featureId || routeStopFeatureKey(properties) || properties.stop_id || properties.stop_name || "";
  }
  if (datasetType === "depot") {
    return feature?.id || properties._featureId || properties._depotKey || depotName(properties) || "";
  }
  return feature?.id || properties._featureId || [properties.line_id, properties.dir, properties.route_id].filter(Boolean).join("|") || properties.name || "";
}

function routeStopFeatureKey(properties = {}) {
  return [properties.line_id, properties.stop_id, properties.seq]
    .map((value) => valueOrEmpty(value))
    .filter(Boolean)
    .join("|");
}

function deepClone(value) {
  if (value === undefined || value === null) return value;
  return JSON.parse(JSON.stringify(value));
}

function appendUploadOperations(datasetType, operations) {
  const existingIds = new Set(editOperations[datasetType].map((operation) => operation.operationId));
  for (const operation of operations) {
    if (!operation?.operationId || existingIds.has(operation.operationId)) continue;
    editOperations[datasetType].push(operation);
    existingIds.add(operation.operationId);
    applyUploadOperationPreview(datasetType, operation);
  }
  if (datasetType === "station") {
    realDataAllCollections.stations = deriveStationsFromRouteStops(realDataAllCollections.routeStops);
  }
  applyDisplayRangeFilter({ updateSources: true, clearSelection: false });
}

function applyUploadOperationPreview(datasetType, operation) {
  const collection = datasetType === "station" ? realDataAllCollections.routeStops : collectionForDataset(datasetType, "all");
  const features = Array.isArray(collection?.features) ? collection.features : [];
  const targetId = operation.targetId || operation.payload?.targetId;
  const feature = operation.payload?.feature;
  if (operation.type?.startsWith("add_")) {
    if (feature) features.push(normalizePreviewFeature(datasetType, feature, features.length));
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

function normalizePreviewFeature(datasetType, feature, index = 0) {
  if (datasetType === "line") return normalizeLineFeature(feature, index);
  if (datasetType === "depot") return normalizeDepotFeature(feature, index);
  return normalizeRouteStopFeature(feature, index);
}

function uploadPreviewFeatureKey(feature, datasetType) {
  const properties = feature?.properties || {};
  if (datasetType === "line") return [properties.line_id, properties.dir, properties.route_id].filter(Boolean).join("|") || properties._featureId || feature?.id || "";
  if (datasetType === "depot") return depotName(properties) || properties._featureId || feature?.id || properties._depotKey || "";
  return [properties.line_id || "", properties.stop_id || "", properties.seq || ""].filter(Boolean).join("|");
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
  lineRoutePicker.station = null;
}

function closeStylePopover() {
  showStylePopover.value = false;
}

function closeRangePopover() {
  showRangePopover.value = false;
}

function toggleRangePopover() {
  if (!showRangePopover.value) {
    closeSearchResults();
    closeEditActionMenu();
    closeLineRoutePicker();
    closeStylePopover();
  }
  showRangePopover.value = !showRangePopover.value;
}

function handleDisplayRangeSelect() {
  closeRangePopover();
}

function toggleStylePopover() {
  if (!showStylePopover.value) {
    closeSearchResults();
    closeEditActionMenu();
    closeLineRoutePicker();
    closeRangePopover();
  }
  showStylePopover.value = !showStylePopover.value;
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
  editOperations[datasetType].push({
    operationId,
    type,
    targetId,
    title,
    detail,
    payload: {
      ...payload,
      featureId: target?.properties?._featureId || target?.id || "",
      stationKey: target?.properties?._stationKey || "",
      lineKey: target?.properties?._lineKey || "",
      depotKey: target?.properties?._depotKey || "",
    },
  });
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
  collection.features = collection.features.filter((feature) => featureTargetId(feature) !== targetId);
  applyDisplayRangeFilter({ updateSources: true, clearSelection: true });
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
  collection.features.push({
    type: "Feature",
    id: featureId,
    geometry: { type: "Point", coordinates: [Number(payload.lng), Number(payload.lat)] },
    properties,
  });
  applyDisplayRangeFilter({ updateSources: true, clearSelection: false });
}

function updateLocalFeatureProperties(datasetType, target, updater) {
  updateLocalFeature(datasetType, target, (feature) => updater(feature.properties || (feature.properties = {})));
}

function updateLocalFeature(datasetType, target, updater) {
  const collection = collectionForDataset(datasetType, "all");
  const targetId = featureTargetId(target);
  const feature = collection.features.find((item) => featureTargetId(item) === targetId);
  if (!feature) return;
  updater(feature);
  applyDisplayRangeFilter({ updateSources: true, clearSelection: false });
}

function refreshDatasetSource(datasetType) {
  applyDisplayRangeFilter({ updateSources: true, clearSelection: false });
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
  searchKeyword.value = result.name;
  closeSearchResults();
  closeStylePopover();
  closeEditActionMenu();
  closeLineRoutePicker();
  pendingAddDataset.value = "";
  if (result.type === "station") {
    selectStation(result.feature);
    focusFeature(result.feature, { pointZoom: 15 });
    if (activeKey.value === "update_station") {
      showStationRoutePickerForSelectedStation();
    }
    return;
  }
  if (result.type === "depot") {
    selectDepot(result.feature);
    focusFeature(result.feature, { pointZoom: 15 });
    if (activeKey.value === "update_depot") {
      openAttributeTable("depot", selectedDepot.value);
    }
    return;
  }
  if (!historyPreview.visible && activeKey.value !== "update_line") {
    activeKey.value = "overview";
  }
  selectRouteFeature(result.feature);
  focusFeature(result.feature, { minZoom: 12, maxZoom: 15 });
  if (activeKey.value === "update_line") {
    openAttributeTable("line", selectedRoute.value);
  }
}

function selectFirstSearchResult() {
  if (searchResults.value.length) {
    selectSearchResult(searchResults.value[0]);
  }
}

function closeSearchResults() {
  isSearchFocused.value = false;
}

function handleSearchFocus() {
  closeStylePopover();
  closeEditActionMenu();
  closeLineRoutePicker();
  isSearchFocused.value = true;
}

function handleSearchInput() {
  isSearchFocused.value = true;
}

function clearSearchKeyword() {
  searchKeyword.value = "";
  isSearchFocused.value = true;
}

function handleSearchBlur() {
  window.setTimeout(() => {
    isSearchFocused.value = false;
  }, 120);
}

function selectStation(feature) {
  const properties = feature?.properties || {};
  const selectedFeature = {
    type: "Feature",
    geometry: feature.geometry,
    properties: {
      ...properties,
      _stationKey: String(properties._stationKey || stationFeatureKey(feature)),
    },
  };
  const routeNames = routesForStation(selectedFeature);
  const enrichedRoutes = routeNames.map((name) => {
    const matched = lineSearchIndex.find((item) => item.name === name);
    const endpoints = matched ? routeEndpoints(matched.feature.properties) : "";
    const passengerFlow = routePassengerFlowValue(matched?.feature?.properties);
    return {
      name,
      desc: endpoints || "暂无首尾站信息",
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
      const matched = lineSearchIndex.find((item) => item.name === route?.name);
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
  const routeStops = Array.isArray(realDataCollections.routeStops?.features) ? realDataCollections.routeStops.features : [];
  const options = [];
  const seen = new Set();
  for (const feature of routeStops) {
    const properties = feature?.properties || {};
    const stopId = valueOrEmpty(properties.stop_id || properties._stationKey);
    const matchesStation = (stationId && stopId && stationId === stopId) || Boolean(stationLabel && stationName(properties) === stationLabel);
    if (!matchesStation) continue;
    const routeId = routeDataId(properties);
    const routeLabel = routeName(properties);
    const key = routeId || routeLabel;
    if (!key || seen.has(key)) continue;
    seen.add(key);
    const matchedLine = lineSearchIndex.find((item) => isSameLogicalRoute(properties, item.feature?.properties || {}));
    options.push(routeOptionFromProperties(matchedLine?.feature?.properties || properties, matchedLine?.feature || null));
  }
  return options;
}

function showStationRoutePickerForSelectedStation() {
  const anchor = document.querySelector(".map-search")?.getBoundingClientRect();
  const event = {
    data: {
      point: anchor ? [anchor.left, anchor.bottom] : [280, 120],
      event: {
        clientX: anchor ? anchor.left : 280,
        clientY: anchor ? anchor.bottom : 120,
      },
    },
  };
  showStationRoutePicker(event, selectedStation.value);
}

function showStationRoutePicker(event, station) {
  if (!station) return;
  const routes = dedupeRouteOptions(stationRouteOptions(station));
  if (!routes.length) {
    ElMessage.warning("该站点未匹配到途经线路");
    return;
  }
  const point = event?.data?.point || [280, 120];
  closeSearchResults();
  closeStylePopover();
  closeEditActionMenu();
  clearSelectedLineLayer();
  lineRoutePicker.x = clampPickerPosition(event?.data?.event?.clientX ?? point[0], 240, window.innerWidth);
  lineRoutePicker.y = clampPickerPosition(event?.data?.event?.clientY ?? point[1], 300, window.innerHeight);
  lineRoutePicker.routes = routes;
  lineRoutePicker.mode = "station_edit";
  lineRoutePicker.lngLat = null;
  lineRoutePicker.point = point;
  lineRoutePicker.station = station;
  lineRoutePicker.visible = true;
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
}

function updateSelectedRouteStationsLayer() {
  const map = MapRef.value?.map;
  const source = map?.getSource(SOURCE_SELECTED_ROUTE_STATIONS);
  if (!source?.setData) return;
  if (!selectedRoute.value || activeKey.value === "update_line") {
    source.setData(emptyFeatureCollection());
    return;
  }
  source.setData({ type: "FeatureCollection", features: selectedRouteStationFeatures() });
}

function selectedRouteStationKeys() {
  return selectedRouteStationFeatures()
    .map((feature) => String(feature?.properties?._stationKey || stationFeatureKey(feature) || ""))
    .filter(Boolean);
}

function selectedRouteStationFeatures() {
  if (!selectedRoute.value || activeKey.value === "update_line") return [];
  const routeStops = routeStopFeaturesForRoute(selectedRoute.value.properties, selectedRoute.value)
    .map(({ feature }, index) => normalizeRouteStationHighlightFeature(feature, index));
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

function normalizeRouteStationHighlightFeature(feature, index = 0) {
  const properties = feature?.properties || {};
  const stationKey = properties.stop_id || properties._stationKey || `${stationName(properties)}-${index}`;
  return {
    type: "Feature",
    id: feature?.id || stationKey,
    geometry: feature?.geometry ? JSON.parse(JSON.stringify(feature.geometry)) : null,
    properties: {
      ...properties,
      _stationKey: String(stationKey),
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
  const stationContext = lineRoutePicker.station;
  selectedRoute.value = route;
  if (pickerMode !== "station_edit") {
    selectedStation.value = null;
  }
  updateStationSelectionLayers();
  if (route?.feature) {
    updateSelectedLineLayer(route.feature);
    if (pickerMode !== "edit") {
      focusFeature(route.feature, { minZoom: 12, maxZoom: 15 });
    }
  }
  const shouldOpenEditMenu = pickerMode === "edit" && route?.feature;
  closeLineRoutePicker();
  if (pickerMode === "station_edit" && route) {
    openAttributeTable("station", { station: stationContext, route });
  } else if (shouldOpenEditMenu) {
    openAttributeTable("line", route);
  }
}

function updateSelectedLineLayer(feature, options = {}) {
  const source = MapRef.value?.map?.getSource(SOURCE_SELECTED_LINE);
  if (source?.setData) {
    const features = options.full === false ? (feature?.geometry ? [feature] : []) : fullLineFeaturesFor(feature);
    source.setData(features.length ? { type: "FeatureCollection", features: features.map(plainGeoJsonFeature) } : emptyFeatureCollection());
  }
  updateBaseLineOpacity();
}

function clearSelectedLineLayer() {
  updateSelectedLineLayer(null);
}

function updateBaseLineOpacity() {
  const map = MapRef.value?.map;
  if (map?.getLayer?.(LAYER_LINES)) {
    map.setPaintProperty(LAYER_LINES, "line-color", lineColorPaint());
    map.setPaintProperty(LAYER_LINES, "line-opacity", lineOpacityPaint());
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

function fullLineFeaturesFor(feature) {
  if (!feature) return [];
  const properties = feature.properties || feature.feature?.properties || {};
  const sourceFeatures = Array.isArray(realDataCollections.lines?.features) ? realDataCollections.lines.features : [];
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
  const routeStops = Array.isArray(realDataCollections.routeStops?.features) ? realDataCollections.routeStops.features : [];
  if (!routeStops.length || (!stopId && !name)) return [];
  const seen = new Set();
  return routeStops
    .filter((stopFeature) => {
      const stopProperties = stopFeature.properties || {};
      const matchesId = stopId && valueOrEmpty(stopProperties.stop_id) === stopId;
      const matchesName = !stopId && name && stationName(stopProperties) === name;
      return matchesId || matchesName;
    })
    .sort((left, right) => routeStopSequence(left.properties) - routeStopSequence(right.properties))
    .map((stopFeature) => {
      const matchedLine = lineSearchIndex.find((item) => isSameLogicalRoute(stopFeature.properties || {}, item.feature?.properties || {}));
      return matchedLine?.name || routeName(stopFeature.properties);
    })
    .filter((name) => {
      if (!name || seen.has(name)) return false;
      seen.add(name);
      return true;
    });
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
  return firstAvailableValue(properties, ["trip_count", "trips", "departures", "班次", "发车班次数"]) || "暂无";
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
  const routeStops = Array.isArray(realDataCollections.routeStops?.features) ? realDataCollections.routeStops.features : [];
  if (!routeStops.length) return [];
  const routeId = routeDataId(properties);
  return routeStops
    .map((feature, index) => {
      const stopProperties = feature.properties || {};
      if (!isRouteStopMatch(stopProperties, routeId)) return null;
      const sequence = routeStopSequence(stopProperties);
      return { feature, sequence, sourceIndex: index };
    })
    .filter(Boolean)
    .sort((left, right) => left.sequence - right.sequence || left.sourceIndex - right.sourceIndex);
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

function routeStopSequence(properties = {}) {
  const value = Number(firstAvailableValue(properties, ["seq"]));
  return Number.isFinite(value) ? value : Number.MAX_SAFE_INTEGER;
}

function routeDataId(properties = {}) {
  return valueOrEmpty(properties.line_id || properties.lineId || properties.route_id || properties.routeId);
}

function routeFeaturesForOption(route = selectedRoute.value) {
  const routeProperties = route?.properties || route?.feature?.properties || {};
  const features = Array.isArray(realDataCollections.lines?.features) ? realDataCollections.lines.features : [];
  const matchedFeatures = features.filter((feature) => isSameLogicalRoute(routeProperties, feature.properties || {}));
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

function getRouteFirstTime(properties) {
  return formatRouteTime(properties.first) || "暂无";
}

function getRouteLastTime(properties) {
  return formatRouteTime(properties.last) || "暂无";
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
  const lengthMeters = routeLengthMeters(properties);
  const count = selectedRoute.value ? selectedRouteStations.value.length : getRouteStationCount(properties);
  if (count > 1 && Number.isFinite(lengthMeters)) {
    return `${Math.round(lengthMeters / (count - 1))} m`;
  }
  return "暂无";
}

function routeLengthMeters(properties = {}, route = selectedRoute.value) {
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

function firstAvailableValue(properties, keys) {
  for (const key of keys) {
    const value = valueOrEmpty(properties?.[key]);
    if (value) return value;
  }
  return "";
}

function valueOrEmpty(value) {
  if (value === undefined || value === null) return "";
  const text = String(value).trim();
  return text && text !== "[]" ? text : "";
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
  return features.map((feature, index) => {
    const properties = feature.properties || {};
    const name = routeName(properties) || "未命名线路";
    return {
      key: `line-${name}-${index}`,
      type: "line",
      typeLabel: "线路",
      name,
      feature,
      searchText: normalizeSearchText([name, properties.line_id, properties.route_id, properties.dir, properties.mode].filter(Boolean).join(" ")),
    };
  });
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

function rankSearchItems(items, query) {
  return items
    .map((item) => {
      const score = searchScore(item.searchText, query);
      return score >= 0 ? { ...item, score } : null;
    })
    .filter(Boolean);
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

function lineCoordinatePaths(geometry) {
  if (!geometry?.coordinates) return [];
  if (geometry.type === "LineString") return [geometry.coordinates];
  if (geometry.type === "MultiLineString") return geometry.coordinates;
  return [];
}

function distanceToSegment(point, start, end) {
  const dx = end[0] - start[0];
  const dy = end[1] - start[1];
  const lengthSquared = dx * dx + dy * dy;
  if (!lengthSquared) return Math.hypot(point[0] - start[0], point[1] - start[1]);
  const ratio = Math.max(0, Math.min(1, ((point[0] - start[0]) * dx + (point[1] - start[1]) * dy) / lengthSquared));
  return Math.hypot(point[0] - (start[0] + ratio * dx), point[1] - (start[1] + ratio * dy));
}

function pointCoordinates(geometry) {
  if (geometry?.type !== "Point" || !Array.isArray(geometry.coordinates)) return null;
  const [lng, lat] = geometry.coordinates;
  return Number.isFinite(Number(lng)) && Number.isFinite(Number(lat)) ? [Number(lng), Number(lat)] : null;
}

function validLngLat(coordinate) {
  if (!Array.isArray(coordinate) || coordinate.length < 2) return null;
  const lng = Number(coordinate[0]);
  const lat = Number(coordinate[1]);
  return Number.isFinite(lng) && Number.isFinite(lat) ? [lng, lat] : null;
}

function isValidPoint(point) {
  return Array.isArray(point) && Number.isFinite(point[0]) && Number.isFinite(point[1]);
}

async function submitActiveEdits() {
  const datasetType = activeEditDataset.value;
  return submitDatasetEdits(datasetType);
}

async function submitDatasetEdits(datasetType) {
  if (!datasetType || !editOperations[datasetType]?.length) return true;
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
      datasetType,
      baseRevision,
      baseVersionId,
      message: commitPayload.message,
      evidenceImages: commitPayload.evidenceImages,
      operations: editOperations[datasetType],
    });
    editOperations[datasetType].splice(0);
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
  clearRealDataLayers();
  unbindStationClickListener();
  loadHistoryList();
}

async function discardActiveEdits() {
  const datasetType = activeEditDataset.value;
  if (!datasetType) return;
  const pendingCount = editOperations[datasetType]?.length || 0;
  if (pendingCount) {
    try {
      await ElMessageBox.confirm(`放弃当前 ${pendingCount} 条未提交修改？放弃后无法恢复。`, "放弃修改", {
        confirmButtonText: "放弃",
        cancelButtonText: "继续编辑",
        type: "warning",
      });
    } catch {
      return;
    }
  }
  editOperations[datasetType].splice(0);
  closeTransientSurfaces();
  pendingMoveTarget.value = null;
  pendingAddDataset.value = "";
  loadOverviewLayers({ fit: false });
}

async function confirmLeaveWithUnsavedEdits() {
  const datasetTypes = unsavedEditDatasets();
  const activeDatasetType = activeEditDataset.value;
  const datasetType = activeDatasetType && editOperations[activeDatasetType]?.length ? activeDatasetType : datasetTypes[0];
  if (!datasetType) return true;
  try {
    await ElMessageBox.confirm("当前页面有未提交修改，是否提交保存后离开？", "未保存修改", {
      confirmButtonText: "提交并离开",
      cancelButtonText: "放弃修改",
      distinguishCancelAndClose: true,
      type: "warning",
    });
    const submitted = await submitDatasetEdits(datasetType);
    if (!submitted) return false;
    return unsavedEditDatasets().length ? confirmLeaveWithUnsavedEdits() : true;
  } catch (action) {
    if (action === "cancel") {
      editOperations[datasetType].splice(0);
      return unsavedEditDatasets().length ? confirmLeaveWithUnsavedEdits() : true;
    }
    return false;
  }
}

function unsavedEditDatasets() {
  return ["station", "line", "depot"].filter((datasetType) => editOperations[datasetType]?.length);
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
});
watch(activeKey, (key) => {
  closeTransientSurfaces();
  closeHistoryDetails();
  pendingAddDataset.value = "";
  pendingMoveTarget.value = null;
  if (historyPreview.visible) {
    historyPreview.visible = false;
    historyPreview.version = null;
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

onMounted(async () => {
  window.addEventListener("beforeunload", handleBeforeUnload);
  window.addEventListener("keydown", handleEscapeKey);
  await handleGetAreaList();
  await loadDisplayRanges();
  await loadOverviewLayers({ fit: true });
});

onBeforeUnmount(() => {
  window.removeEventListener("beforeunload", handleBeforeUnload);
  window.removeEventListener("keydown", handleEscapeKey);
  if (MapRef.value) {
    if (zoomListenerId) MapRef.value.removeEventListener("update:zoom", zoomListenerId);
    if (rotateListenerId) MapRef.value.removeEventListener("update:camera:rotate", rotateListenerId);
  }
  unbindStationClickListener();
  clearRealDataLayers();
});
</script>

<style lang="scss" scoped>
.datebase_box,
.dm-overview-panel,
.dm-edit-panel,
.map-controls-toolbar,
.map-search,
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

.dm-sidebar {
  position: fixed;
  left: 0;
  top: var(--app-header-height);
  bottom: 0;
  width: 260px;
  background: #ffffff;
  border-right: 1px solid rgba(0, 0, 0, 0.06);
  box-shadow: 2px 0 12px rgba(0, 0, 0, 0.03);
  display: flex;
  flex-direction: column;
  z-index: var(--z-panel);
  user-select: none;
  overflow-y: auto;
  overflow-x: hidden;
  scrollbar-width: none;

  &::-webkit-scrollbar {
    display: none;
  }
}

.sidebar-brand {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 24px 20px 10px;
  border-bottom: none !important;
  margin-bottom: 4px;

  .brand-icon {
    color: var(--app-blue);
    opacity: 0.9;
    flex-shrink: 0;
  }

  .brand-text {
    font-size: 15px;
    font-weight: 700;
    color: #111827;
    letter-spacing: 0.03em;
    text-transform: uppercase;
  }
}

.map-search {
  position: fixed;
  top: calc(var(--app-header-height) + 18px);
  left: 278px;
  z-index: calc(var(--z-header) + 6);
  width: 240px;
  transform-origin: top left;
  transition: filter var(--app-motion-normal) var(--app-ease-out);

  &.is-focused {
    width: 300px;
  }
}

.search-icon-svg {
  position: absolute;
  left: 12px;
  top: 50%;
  transform: translateY(-50%);
  width: 14px;
  height: 14px;
  color: rgba(21, 105, 222, 0.45);
  pointer-events: none;
  transition: color 0.25s ease, transform 0.25s ease;
  z-index: 2;
}

.map-search.is-focused .search-icon-svg {
  color: var(--app-blue);
  transform: translateY(-50%) scale(1.08);
}

.search-input {
  width: 100%;
  height: 34px;
  padding: 0 32px 0 34px;
  border: 1px solid rgba(21, 105, 222, 0.15);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.8);
  backdrop-filter: blur(12px) saturate(180%);
  -webkit-backdrop-filter: blur(12px) saturate(180%);
  color: #0f253e;
  font-size: 13px;
  font-weight: 600;
  outline: none;
  box-shadow: 
    0 4px 12px rgba(15, 39, 68, 0.04), 
    0 1px 2px rgba(0, 0, 0, 0.02),
    inset 0 1px 0 rgba(255, 255, 255, 0.6);
  transition:
    border-color 0.25s cubic-bezier(0.25, 1, 0.5, 1),
    box-shadow 0.25s cubic-bezier(0.25, 1, 0.5, 1),
    background-color 0.25s cubic-bezier(0.25, 1, 0.5, 1);

  &::placeholder {
    color: #94a3b8;
    font-weight: 500;
  }

  &:hover {
    background: rgba(255, 255, 255, 0.92);
    border-color: rgba(21, 105, 222, 0.3);
    box-shadow: 
      0 6px 16px rgba(15, 39, 68, 0.06), 
      0 1px 2px rgba(0, 0, 0, 0.02),
      inset 0 1px 0 rgba(255, 255, 255, 0.8);
  }

  &:focus {
    background: #ffffff;
    border-color: var(--app-blue);
    box-shadow: 
      0 0 0 3px rgba(21, 105, 222, 0.15),
      0 8px 24px rgba(21, 105, 222, 0.08),
      inset 0 1px 0 rgba(255, 255, 255, 1);
  }
}

.search-clear-btn {
  position: absolute;
  top: 50%;
  right: 9px;
  width: 16px;
  height: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: rgba(100, 116, 139, 0.08);
  color: #64748b;
  transform: translateY(-50%) scale(1);
  cursor: pointer;
  z-index: 2;
  transition: 
    transform var(--app-motion-normal) var(--app-ease-out),
    background-color 0.2s ease,
    color 0.2s ease;

  &:hover {
    background: rgba(21, 105, 222, 0.12);
    color: var(--app-blue);
    transform: translateY(-50%) rotate(90deg) scale(1.15);
  }

  &:active {
    transform: translateY(-50%) rotate(90deg) scale(0.92);
  }
}

.search-result-list {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  right: 0;
  z-index: calc(var(--z-panel) + 20);
  max-height: 320px;
  overflow-y: auto;
  padding: 6px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.94);
  backdrop-filter: blur(20px) saturate(190%);
  -webkit-backdrop-filter: blur(20px) saturate(190%);
  border: 1px solid rgba(21, 105, 222, 0.12);
  box-shadow: 
    0 12px 36px rgba(15, 39, 68, 0.12),
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

.search-result-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  margin-bottom: 2px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #1e293b;
  text-align: left;
  cursor: pointer;
  transition:
    background-color 0.2s cubic-bezier(0.25, 1, 0.5, 1),
    transform 0.2s cubic-bezier(0.25, 1, 0.5, 1);

  &:last-child {
    margin-bottom: 0;
  }

  &:hover {
    background: linear-gradient(135deg, rgba(21, 105, 222, 0.06) 0%, rgba(21, 105, 222, 0.02) 100%);
    transform: translateX(4px);
  }

  &:active {
    transform: translateX(2px);
  }
}

.result-icon-wrapper {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  flex-shrink: 0;
  transition: transform 0.2s ease;

  .type-svg {
    width: 14px;
    height: 14px;
  }

  &.station {
    background: rgba(13, 148, 136, 0.1);
    color: #0d9488;
    border: 1px solid rgba(13, 148, 136, 0.12);
  }

  &.line {
    background: rgba(21, 105, 222, 0.1);
    color: var(--app-blue);
    border: 1px solid rgba(21, 105, 222, 0.12);
  }

  &.depot {
    background: rgba(124, 58, 237, 0.1);
    color: #7c3aed;
    border: 1px solid rgba(124, 58, 237, 0.12);
  }
}

.result-meta-block {
  display: flex;
  flex-direction: column;
  min-width: 0;
  gap: 2px;
}

.result-name {
  color: #1e293b;
  font-size: 13px;
  line-height: 1.3;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.result-type-text {
  color: #64748b;
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 0.02em;
}

.search-empty {
  margin: 0;
  padding: 12px 10px;
  color: #64748b;
  font-size: 12px;
  font-weight: 500;
  text-align: center;
}

.search-dropdown-fade-enter-active,
.search-dropdown-fade-leave-active {
  transition: 
    opacity var(--app-motion-normal) var(--app-ease-out),
    transform var(--app-motion-normal) var(--app-ease-out);
}

.search-dropdown-fade-enter-from,
.search-dropdown-fade-leave-to {
  opacity: 0;
  transform: translateY(-8px) scale(0.97);
}

.sidebar-nav {
  display: flex;
  flex-direction: column;
  padding: 4px 12px;
  gap: 4px;
}

.menu-group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.nav-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
  color: #4b5563;
  font-size: 14px;
  font-weight: 500;
  font-family: inherit;
  text-align: left;
  transition:
    background-color 0.2s ease,
    color 0.2s ease,
    transform 0.15s ease;

  .nav-icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 20px;
    height: 20px;
    flex-shrink: 0;
    transition: color 0.2s ease;
  }

  .nav-label {
    flex: 1;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .chevron-icon {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    transition: transform 0.2s ease;
    color: #9ca3af;

    &.expanded {
      transform: rotate(180deg);
    }
  }

  &:hover {
    background: rgba(21, 105, 222, 0.05);
    color: #1f2937;

    .nav-icon {
      color: var(--app-blue);
    }

    .chevron-icon {
      color: #4b5563;
    }
  }

  &:active {
    transform: scale(0.98);
  }

  &.active {
    background: rgba(21, 105, 222, 0.09);
    color: var(--app-blue);
    font-weight: 600;

    .nav-icon {
      color: var(--app-blue);
    }
  }
}

.sub-nav-list {
  padding-left: 28px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-bottom: 4px;
  overflow: hidden;
}

.sub-nav-item {
  position: relative;
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px 8px 16px !important;
  border: 0;
  border-radius: 6px;
  background: transparent;
  cursor: pointer;
  color: #6b7280;
  font-size: 13px;
  font-weight: 600;
  font-family: inherit;
  text-align: left;
  transition: 
    padding-left var(--app-motion-normal) var(--app-ease-out),
    color 0.25s ease,
    background-color 0.25s ease !important;

  .sub-dot {
    display: inline-block;
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: currentColor;
    opacity: 0;
    transform: scale(0.7);
    transition:
      opacity var(--app-motion-normal) var(--app-ease-out),
      transform var(--app-motion-normal) var(--app-ease-out);
  }

  .nav-label {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  &:hover {
    background: rgba(21, 105, 222, 0.04) !important;
    color: #111827 !important;
    padding-left: 20px !important;

    .sub-dot {
      opacity: 0.5;
      transform: scale(0.9);
    }
  }

  &:active {
    transform: scale(0.98);
  }

  &.active {
    background: rgba(21, 105, 222, 0.07) !important;
    color: var(--app-blue-strong) !important;
    font-weight: 700;
    padding-left: 22px !important;

    .sub-dot {
      opacity: 1;
      transform: scale(1);
    }
  }
}

.slide-fade-enter-active {
  transition:
    opacity var(--app-motion-normal) var(--app-ease-out),
    transform var(--app-motion-normal) var(--app-ease-out);

  .sub-nav-item {
    transition: 
      transform var(--app-motion-normal) var(--app-ease-out),
      opacity var(--app-motion-normal) var(--app-ease-out),
      padding-left var(--app-motion-normal) var(--app-ease-out),
      color 0.25s ease,
      background-color 0.25s ease !important;
      
    @starting-style {
      opacity: 0;
      transform: translateY(10px);
    }
    
    &:nth-child(1) {
      transition-delay: 0.04s;
    }
    &:nth-child(2) {
      transition-delay: 0.09s;
    }
    &:nth-child(3) {
      transition-delay: 0.14s;
    }
  }
}

.slide-fade-leave-active {
  transition:
    opacity 0.2s ease-in,
    transform 0.25s cubic-bezier(0.4, 0, 1, 1);
}

.slide-fade-enter-from,
.slide-fade-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

.sidebar-footer {
  flex: 1;
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
  backdrop-filter: blur(18px) saturate(165%);
  -webkit-backdrop-filter: blur(18px) saturate(165%);
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
}.overview-title-row {
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

.route-desc-text {
  font-size: 11px;
  color: #64748b;
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

.map-controls-toolbar {
  position: fixed;
  top: calc(var(--app-header-height) + var(--space-sm));
  right: var(--app-edge);
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-sm);
  z-index: calc(var(--z-header) + 5);
  transform-origin: top right;

  &.with-panel {
    right: calc(var(--app-edge) + 394px);
  }
}

.control-block {
  display: flex;
  flex-direction: column;
  width: 44px;
  overflow: hidden;
  border-radius: var(--app-card-radius);
  background-color: var(--app-card-bg);
  border: 1px solid rgba(21, 105, 222, 0.11);
  box-shadow: var(--app-shadow-sm);
}

.control-btn {
  width: 44px;
  height: 44px;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: var(--app-ink);
  cursor: pointer;
  transition:
    background-color var(--app-motion-normal) var(--app-ease-out),
    color var(--app-motion-normal) var(--app-ease-out),
    transform var(--app-motion-fast) var(--app-ease-press);

  &:not(:last-child) {
    border-bottom: 1px solid rgba(21, 105, 222, 0.08);
  }

  &:hover,
  &.active {
    background-color: var(--app-cyan-soft);
    color: var(--app-cyan-strong);
  }

  &:active {
    transform: translateY(1px);
  }

  svg,
  .pitch-arrows {
    transition: transform var(--app-motion-normal) var(--app-ease-out);
  }

  &:hover svg,
  &:hover .pitch-arrows {
    transform: translateY(-1px);
  }
}

.td-btn {
  font-size: 11px;
  font-weight: 700;
}

.compass-btn .pitch-arrows {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1px;
  color: currentColor;
}

.range-popover,
.style-popover {
  position: absolute;
  right: 48px;
  width: min(240px, calc(100vw - 96px));
  padding: 14px 14px 12px;
  border-radius: 8px;
  background: rgba(251, 253, 255, 0.96);
  border: 1px solid rgba(21, 105, 222, 0.14);
  box-shadow: 0 16px 34px rgba(15, 39, 68, 0.14);
}

.range-popover {
  top: 188px;
}

.style-popover {
  top: 236px;
}

.popover-title {
  color: #12304f;
  font-size: 13px;
  font-weight: 700;
  margin-bottom: 10px;
}

.range-select {
  width: 100%;
}

.range-select :deep(.el-input__wrapper) {
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.96) !important;
  box-shadow: 0 0 0 1px rgba(21, 105, 222, 0.14) inset !important;
}

.range-select :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1.5px var(--app-blue) inset, 0 0 0 3px rgba(21, 105, 222, 0.12) !important;
}

.range-error {
  margin: 8px 0 0;
  color: #b45309;
  font-size: 12px;
  line-height: 1.45;
  font-weight: 600;
}

.slider-row {
  display: grid;
  gap: 6px;
  margin-top: 6px;

  .label {
    display: flex;
    justify-content: space-between;
    color: #38536e;
    font-size: 12px;
    font-weight: 600;
  }

  .val-text {
    color: var(--app-blue);
  }
}

.popover-fade-enter-active,
.popover-fade-leave-active {
  transition:
    opacity 0.16s ease,
    transform 0.16s ease;
}

.popover-fade-enter-from,
.popover-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@media (max-width: 860px) {
  .datebase_box {
    top: calc(var(--app-header-height) + var(--space-lg));
    right: var(--app-edge);
    max-width: calc(100vw - (var(--app-edge) * 2));
  }

  .dm-sidebar {
    width: 220px;
  }

  .map-search {
    left: 238px;
    width: min(220px, calc(100vw - 260px));
  }

  .search-result-list {
    width: 100%;
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

  .map-controls-toolbar.with-panel {
    right: calc(var(--app-edge) + 334px);
  }

  .sidebar-brand {
    padding: 16px 16px 12px;

    .brand-text {
      font-size: 14px;
    }
  }

  .nav-item {
    padding: 10px 12px;
    font-size: 13px;
  }

  .sub-nav-list {
    padding-left: 20px;
  }

  .sub-nav-item {
    padding: 8px 12px;
    font-size: 12px;
  }
}

/* Premium reskin for the data-management module. Kept as overrides to avoid touching behavior. */
.datebase_box,
.dm-sidebar,
.map-search,
.dm-overview-panel,
.dm-edit-panel,
.dm-history-page,
.history-preview-panel,
.map-controls-toolbar,
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
.map-controls-toolbar,
.map-search,
.edit-action-menu,
.history-preview-panel {
  scale: var(--dm-panel-scale);
}

.dm-sidebar {
  left: 16px;
  top: calc(var(--app-header-height) + 14px);
  bottom: 18px;
  width: 250px;
  padding: 6px;
  border: 1px solid var(--dm-border);
  border-radius: 24px;
  background:
    linear-gradient(145deg, rgba(255, 255, 252, 0.96), rgba(241, 243, 235, 0.91)),
    repeating-linear-gradient(135deg, rgba(31, 49, 50, 0.025) 0 1px, transparent 1px 7px);
  box-shadow: var(--dm-shadow);
  overflow: hidden auto;
}

.dm-sidebar::before {
  content: "";
  position: absolute;
  inset: 6px;
  pointer-events: none;
  border-radius: 19px;
  border: 1px solid rgba(255, 255, 255, 0.64);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.72);
}

.sidebar-brand {
  padding: 18px 16px 14px;
  margin: 0 0 6px;
  gap: 11px;
}

.sidebar-brand .brand-icon {
  width: 28px;
  height: 28px;
  padding: 5px;
  color: var(--dm-accent);
  border-radius: 10px;
  background: var(--dm-accent-soft);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.72);
}

.sidebar-brand .brand-text {
  color: var(--dm-ink-strong);
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.02em;
  text-transform: none;
}

.sidebar-nav {
  gap: 6px;
  padding: 4px 8px 16px;
}

.nav-item {
  min-height: 46px;
  padding: 12px 13px;
  border-radius: 15px;
  color: #4e5e5d;
  font-size: 13.5px;
  font-weight: 600;
  transition:
    background-color 360ms var(--dm-ease),
    color 360ms var(--dm-ease),
    box-shadow 360ms var(--dm-ease),
    transform 260ms var(--dm-ease);
}

.nav-item .nav-icon svg,
.chevron-icon svg {
  stroke-width: 1.75;
}

.nav-item:hover {
  background: rgba(255, 255, 255, 0.62);
  color: var(--dm-ink-strong);
  transform: translateX(2px);
  box-shadow: inset 0 0 0 1px rgba(47, 111, 115, 0.08);
}

.nav-item.active {
  color: var(--dm-accent-strong);
  background:
    linear-gradient(135deg, rgba(47, 111, 115, 0.14), rgba(184, 135, 70, 0.1)),
    rgba(255, 255, 255, 0.72);
  box-shadow:
    inset 0 0 0 1px rgba(47, 111, 115, 0.18),
    inset 3px 0 0 var(--dm-copper);
}

.nav-item.active .nav-icon,
.nav-item:hover .nav-icon {
  color: var(--dm-accent);
}

.sub-nav-list {
  margin: 0 0 5px 22px;
  padding-left: 11px;
  border-left: 1px solid rgba(47, 111, 115, 0.16);
}

.sub-nav-item {
  padding: 8px 12px !important;
  border-radius: 12px;
  color: #697775;
  font-size: 12.5px;
  font-weight: 600;
  transition:
    background-color 340ms var(--dm-ease),
    color 340ms var(--dm-ease),
    transform 260ms var(--dm-ease),
    padding-left 340ms var(--dm-ease) !important;
}

.sub-nav-item:hover {
  padding-left: 16px !important;
  background: rgba(255, 255, 255, 0.56) !important;
  color: var(--dm-ink-strong) !important;
}

.sub-nav-item.active {
  padding-left: 17px !important;
  color: var(--dm-accent-strong) !important;
  background: rgba(47, 111, 115, 0.11) !important;
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

.map-search {
  top: calc(var(--app-header-height) + 20px);
  left: 288px;
  width: 292px;
  transition:
    transform 360ms var(--dm-ease),
    filter 360ms var(--dm-ease);
}

.map-search.is-focused {
  width: 292px;
  transform: translateY(-2px);
}

.search-input {
  height: 42px;
  padding-left: 40px;
  border: 1px solid rgba(42, 59, 58, 0.12);
  border-radius: 16px;
  background: rgba(252, 250, 244, 0.92);
  color: var(--dm-ink-strong);
  font-size: 13px;
  font-weight: 600;
  box-shadow: 0 14px 32px rgba(31, 49, 50, 0.11), inset 0 1px 0 rgba(255, 255, 255, 0.72);
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
  transition:
    border-color 360ms var(--dm-ease),
    box-shadow 360ms var(--dm-ease),
    background-color 360ms var(--dm-ease),
    transform 260ms var(--dm-ease);
}

.search-input:hover,
.search-input:focus {
  border-color: var(--dm-border-strong);
  background: rgba(255, 255, 252, 0.98);
  box-shadow: 0 18px 42px rgba(31, 49, 50, 0.14), 0 0 0 4px rgba(47, 111, 115, 0.08);
}

.search-icon-svg {
  left: 15px;
  color: rgba(47, 111, 115, 0.62);
  stroke-width: 2;
}

.search-clear-btn {
  right: 12px;
  width: 20px;
  height: 20px;
  background: rgba(47, 111, 115, 0.08);
  color: var(--dm-accent);
}

.search-clear-btn:hover {
  background: var(--dm-copper-soft);
  color: #8f642b;
}

.search-result-list,
.line-route-picker,
.edit-action-menu,
.range-popover,
.style-popover {
  border: 1px solid rgba(42, 59, 58, 0.14);
  border-radius: 18px;
  background: rgba(252, 250, 244, 0.97);
  box-shadow: 0 22px 54px rgba(31, 49, 50, 0.17), inset 0 1px 0 rgba(255, 255, 255, 0.72);
  backdrop-filter: none;
  -webkit-backdrop-filter: none;
}

.search-result-item,
.picker-route-btn {
  border-radius: 13px;
  color: var(--dm-ink);
  transition:
    background-color 320ms var(--dm-ease),
    border-color 320ms var(--dm-ease),
    box-shadow 320ms var(--dm-ease),
    transform 260ms var(--dm-ease);
}

.search-result-item:hover,
.picker-route-btn:hover {
  background: rgba(47, 111, 115, 0.08);
  transform: translateX(3px);
}

.result-icon-wrapper.station,
.result-icon-wrapper.line,
.result-icon-wrapper.depot,
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
.route-desc-text,
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

.map-controls-toolbar {
  top: calc(var(--app-header-height) + 18px);
  right: calc(var(--app-edge) + 2px);
}

.map-controls-toolbar.with-panel {
  right: calc(var(--app-edge) + 424px);
}

.control-block {
  width: 46px;
  border: 1px solid rgba(42, 59, 58, 0.12);
  border-radius: 18px;
  background: rgba(252, 250, 244, 0.94);
  box-shadow: 0 16px 34px rgba(31, 49, 50, 0.12), inset 0 1px 0 rgba(255, 255, 255, 0.72);
}

.control-btn {
  width: 46px;
  height: 46px;
  color: var(--dm-ink);
}

.control-btn:hover,
.control-btn.active {
  background-color: rgba(47, 111, 115, 0.1);
  color: var(--dm-accent-strong);
}

.range-popover {
  top: 198px;
  right: 52px;
}

.style-popover {
  top: 248px;
  right: 52px;
}

.popover-title,
.slider-row .label,
.picker-title {
  color: var(--dm-ink);
}

.slider-row .val-text {
  color: var(--dm-accent);
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
  .dm-sidebar {
    left: 10px;
    width: 220px;
    border-radius: 20px;
  }

  .map-search {
    left: 244px;
    width: min(270px, calc(100vw - 268px));
  }

  .map-search.is-focused {
    width: min(270px, calc(100vw - 268px));
  }

  .dm-overview-panel,
  .dm-edit-panel {
    width: min(350px, calc(100vw - 260px));
  }

  .map-controls-toolbar.with-panel {
    right: calc(var(--app-edge) + min(360px, calc(100vw - 250px)));
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
  .dm-sidebar {
    right: 10px;
    bottom: auto;
    width: auto;
    max-height: 48vh;
  }

  .map-search,
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
  .history-preview-panel,
  .map-search,
  .map-controls-toolbar {
    animation: none;
  }
}

/* User-requested correction: full-height left rail and cooler unified surfaces. */
.datebase_box,
.dm-sidebar,
.map-search,
.dm-overview-panel,
.dm-edit-panel,
.dm-history-page,
.history-preview-panel,
.map-controls-toolbar,
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

.dm-sidebar {
  left: 0;
  top: var(--app-header-height);
  bottom: 0;
  width: 260px;
  padding: 8px 10px 14px;
  border-width: 0 1px 0 0;
  border-color: rgba(35, 50, 55, 0.1);
  border-radius: 0;
  background:
    linear-gradient(180deg, rgba(250, 253, 254, 0.98), rgba(241, 247, 249, 0.96)),
    repeating-linear-gradient(135deg, rgba(35, 50, 55, 0.018) 0 1px, transparent 1px 8px);
  box-shadow: 12px 0 34px rgba(24, 43, 50, 0.08);
}

.dm-sidebar::before {
  display: none;
}

.datebase_box {
  background: rgba(249, 252, 253, 0.92);
  border-color: rgba(35, 50, 55, 0.1);
  box-shadow: 0 12px 28px rgba(24, 43, 50, 0.09), inset 0 1px 0 rgba(255, 255, 255, 0.74);
}

.sidebar-brand {
  padding: 18px 12px 14px;
  border-bottom: 1px solid rgba(35, 50, 55, 0.08) !important;
}

.sidebar-brand .brand-icon {
  background: rgba(47, 111, 115, 0.08);
  color: var(--dm-accent);
}

.nav-item {
  border-radius: 10px;
  color: #4d5d61;
}

.nav-item.active {
  color: var(--dm-accent-strong);
  background: linear-gradient(90deg, rgba(47, 111, 115, 0.13), rgba(49, 93, 138, 0.07));
  box-shadow:
    inset 3px 0 0 var(--dm-accent),
    inset 0 0 0 1px rgba(47, 111, 115, 0.1);
}

.sub-nav-list {
  margin-left: 28px;
  border-left-color: rgba(47, 111, 115, 0.16);
}

.sub-nav-item.active {
  color: var(--dm-accent-strong) !important;
  background: rgba(47, 111, 115, 0.1) !important;
}

.map-search {
  left: 282px;
}

.search-input,
.search-result-list,
.line-route-picker,
.edit-action-menu,
.range-popover,
.style-popover {
  background: rgba(249, 252, 253, 0.96);
  border-color: rgba(35, 50, 55, 0.12);
}

.search-clear-btn:hover,
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
.coverage-card .card-title-row .metric-note,
.slider-row .val-text {
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

.map-controls-toolbar.with-panel {
  right: calc(var(--app-edge) + 414px);
}

.control-block {
  background: rgba(249, 252, 253, 0.96);
}

.picker-route-btn.active {
  background: linear-gradient(135deg, rgba(47, 111, 115, 0.13), rgba(49, 93, 138, 0.09));
  box-shadow: inset 3px 0 0 var(--dm-accent), 0 12px 26px rgba(24, 43, 50, 0.08);
}

@media (max-width: 860px) {
  .dm-sidebar {
    left: 0;
    width: 220px;
    border-radius: 0;
  }

  .map-search,
  .history-preview-panel {
    left: 238px;
  }

  .dm-history-page {
    left: 220px;
  }

  .map-controls-toolbar.with-panel {
    right: calc(var(--app-edge) + 344px);
  }
}

@media (max-width: 720px) {
  .dm-sidebar {
    left: 0;
    right: 0;
    width: auto;
  }
}

/* Apple-like white correction requested by the user. This final layer intentionally neutralizes the prior tinted reskins. */
.datebase_box,
.dm-sidebar,
.map-search,
.dm-overview-panel,
.dm-edit-panel,
.dm-history-page,
.history-preview-exit,
.history-detail-panel,
.map-controls-toolbar,
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

.dm-sidebar,
.dm-history-page,
.dm-overview-panel,
.dm-edit-panel,
.history-preview-exit,
.history-detail-panel,
.datebase_box,
.search-input,
.search-result-list,
.line-route-picker,
.edit-action-menu,
.range-popover,
.style-popover,
.control-block {
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

.dm-sidebar {
  border-color: rgba(0, 0, 0, 0.08);
  box-shadow: 8px 0 24px rgba(15, 23, 42, 0.05);
}

.sidebar-brand .brand-icon,
.result-icon-wrapper.station,
.result-icon-wrapper.line,
.result-icon-wrapper.depot,
.picker-route-btn .picker-icon-wrapper {
  color: var(--dm-accent);
  background: var(--dm-accent-soft);
  border-color: rgba(0, 113, 227, 0.12);
}

.nav-item,
.sub-nav-item {
  border-radius: 8px;
}

.nav-item:hover,
.sub-nav-item:hover {
  background: rgba(0, 0, 0, 0.035) !important;
  color: var(--dm-ink-strong) !important;
}

.nav-item.active,
.sub-nav-item.active {
  color: var(--dm-accent-strong) !important;
  background: var(--dm-accent-soft) !important;
  box-shadow: inset 3px 0 0 var(--dm-accent);
}

.datebase_box,
.search-input,
.search-result-list,
.line-route-picker,
.edit-action-menu,
.range-popover,
.style-popover,
.control-block {
  border: 1px solid var(--dm-border);
  box-shadow: var(--dm-shadow-soft);
}

.datebase_box {
  border-radius: 999px;
}

.search-input {
  border-radius: 12px;
  color: var(--dm-ink);
}

.search-input:hover,
.search-input:focus,
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
:global(.dm-commit-dialog .el-dialog) {
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

:global(.dm-commit-dialog.el-dialog) {
  margin: 0 auto !important;
}

:global(.dm-commit-dialog .el-dialog) {
  margin: 0 !important;
}

:global(.dm-edit-dialog .el-dialog__header),
:global(.dm-commit-dialog .el-dialog__header) {
  margin: 0;
  padding: 20px 24px 10px;
}

:global(.dm-edit-dialog .el-dialog__title),
:global(.dm-commit-dialog .el-dialog__title) {
  color: var(--dm2-ink);
  font-size: 19px;
  line-height: 1.3;
  font-weight: 700;
}

:global(.dm-edit-dialog .el-dialog__body),
:global(.dm-commit-dialog .el-dialog__body) {
  padding: 0 24px 18px;
}

:global(.dm-edit-dialog .el-dialog__footer),
:global(.dm-commit-dialog .el-dialog__footer) {
  padding: 14px 24px 20px;
  border-top: 1px solid var(--dm2-line-faint);
  background: var(--dm2-surface);
}

:global(.dm-commit-dialog .el-dialog__headerbtn) {
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
:global(.dm-commit-dialog .el-dialog__headerbtn:focus-visible) {
  background: rgba(15, 23, 42, 0.06);
  outline: none;
}

:global(.dm-commit-dialog .el-dialog__close) {
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
.dm-sidebar,
.map-search,
.dm-overview-panel,
.dm-edit-panel,
.history-preview-panel,
.history-preview-exit,
.history-detail-panel,
.map-controls-toolbar,
.line-route-picker,
.edit-action-menu {
  --dm-panel-scale: var(--app-layout-scale);
}

.dm-sidebar {
  top: var(--app-header-height);
  bottom: auto;
  left: 0;
  width: 260px;
  height: var(--app-dm-sidebar-height);
  transform-origin: left top;
  scale: var(--dm-panel-scale);
}

.datebase_box {
  top: calc(var(--app-header-height) / 2);
  right: calc(var(--app-edge) + var(--app-scaled-70));
  transform-origin: right center;
}

.map-search {
  top: calc(var(--app-header-height) + var(--app-scaled-20));
  left: var(--app-scaled-282);
  width: 292px;
  transform-origin: top left;
}

.map-search.is-focused {
  width: 292px;
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

.map-controls-toolbar {
  top: calc(var(--app-header-height) + var(--app-scaled-18));
  right: calc(var(--app-edge) + var(--app-scaled-2));
  transform-origin: top right;
}

.map-controls-toolbar.with-panel {
  right: calc(var(--app-edge) + var(--app-scaled-414));
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
</style>
