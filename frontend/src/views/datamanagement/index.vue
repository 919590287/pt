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
    <div class="overview-title-row" :class="{ 'is-station-detail': selectedStation || selectedRoute }">
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
      <template v-else-if="!selectedStation && !selectedRoute">
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

    <div v-else class="overview-metric-list">
      <!-- 1. Hero Card: 线网总规模 -->
      <div class="metric-card hero-card">
        <div class="card-content">
          <div class="label-row">
            <span class="label-text">线网总规模</span>
          </div>
          <div class="value-row">
            <span class="hero-num">{{ formatUnit(overviewStats.networkScaleKm, "") }}</span>
            <span class="hero-unit">km</span>
          </div>
        </div>
      </div>

      <!-- 2. Grid Cards: 线路总数 & 站点数量 -->
      <div class="metric-grid">
        <!-- 线路总数 Card -->
        <div class="metric-card grid-card routes-card">
          <div class="card-header">
            <div class="card-icon">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/>
                <polyline points="22 4 12 14.01 9 11.01"/>
              </svg>
            </div>
            <span class="label-text">线路总数</span>
          </div>
          <strong class="grid-num">{{ formatInteger(overviewStats.lineCount) }}</strong>
          <span class="grid-unit">条</span>
        </div>

        <!-- 站点数量 Card -->
        <div class="metric-card grid-card stations-card">
          <div class="card-header">
            <div class="card-icon">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/>
                <circle cx="12" cy="10" r="3"/>
              </svg>
            </div>
            <span class="label-text">站点数量</span>
          </div>
          <strong class="grid-num">{{ formatInteger(overviewStats.stationCount) }}</strong>
          <span class="grid-unit">个</span>
        </div>
      </div>

      <!-- 3. Density Card: 线网密度 -->
      <div class="metric-card density-card">
        <div class="card-left">
          <div class="card-icon">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <rect x="3" y="3" width="18" height="18" rx="2" ry="2"/>
              <line x1="9" y1="3" x2="9" y2="21"/>
              <line x1="15" y1="3" x2="15" y2="21"/>
              <line x1="3" y1="9" x2="21" y2="9"/>
              <line x1="3" y1="15" x2="21" y2="15"/>
            </svg>
          </div>
          <span class="label-text">线网密度</span>
        </div>
        <div class="card-right">
          <strong class="num-val">{{ formatUnit(overviewStats.networkDensityKmPerKm2, "", 4) }}</strong>
          <span class="unit-val">km/km²</span>
        </div>
      </div>

      <!-- 4. Ratio Card: 站点300/500米覆盖率 (Visual Progress Bars) -->
      <div class="metric-card coverage-card">
        <div class="card-title-row">
          <span class="label-text">站点覆盖率分析</span>
        </div>
        <div class="coverage-bar-group">
          <!-- 300m Progress bar -->
          <div class="coverage-bar-item">
            <div class="bar-label-row">
              <span>公交站点300米覆盖率</span>
              <strong>{{ formatPercent(overviewStats.stationCoverage300Rate) }}</strong>
            </div>
            <div class="progress-track">
              <div class="progress-fill fill-300" :style="{ width: formatPercent(overviewStats.stationCoverage300Rate) }"></div>
            </div>
          </div>

          <!-- 500m Progress bar -->
          <div class="coverage-bar-item">
            <div class="bar-label-row">
              <span>公交站点500米覆盖率</span>
              <strong>{{ formatPercent(overviewStats.stationCoverage500Rate) }}</strong>
            </div>
            <div class="progress-track">
              <div class="progress-fill fill-500" :style="{ width: formatPercent(overviewStats.stationCoverage500Rate) }"></div>
            </div>
          </div>
        </div>
      </div>
    </div>
    <p v-if="loadError && !hasActiveDetail" class="load-error">{{ loadError }}</p>
  </div>

  <div v-if="activeEditDataset" class="dm-edit-panel">
    <div class="overview-title-row">
      <div>
        <p class="panel-kicker">{{ editDatasetKicker }}</p>
        <h2>{{ editDatasetTitle }}</h2>
      </div>
      <el-tag :type="activeEditOperations.length ? 'warning' : 'info'">{{ activeEditOperations.length }} 条修改</el-tag>
    </div>
    <div v-if="activeEditOperations.length" class="edit-operation-list">
      <div v-if="hiddenActiveEditOperationCount" class="edit-operation-summary">
        已显示前 {{ visibleActiveEditOperations.length }} 条，另有 {{ hiddenActiveEditOperationCount }} 条会一并提交
      </div>
      <div v-for="operation in visibleActiveEditOperations" :key="operation.operationId" class="edit-operation-item">
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
    <section class="history-header">
      <div>
        <p class="panel-kicker">版本管理</p>
        <h1>{{ selectedArea }} 历史数据版本</h1>
      </div>
      <div class="history-header-actions">
        <el-button :loading="isLoadingHistory" @click="loadHistoryList">刷新</el-button>
      </div>
    </section>

    <section class="history-current-version" aria-label="当前版本">
      <span>当前版本</span>
      <strong>{{ activeHistoryVersionLabel }}</strong>
    </section>

    <section class="history-content">
      <div class="history-list-panel history-timeline-panel">
        <div class="history-list-title">
          <div>
            <h2>版本时间轴</h2>
            <p>只展示可查看的数据版本；查看不会影响最新工作版本。</p>
          </div>
          <el-tag :type="historySummary.activeVersionId === '__base__' ? 'info' : 'success'">当前版本</el-tag>
        </div>

        <div v-if="historyError" class="history-error">{{ historyError }}</div>
        <div v-else-if="isLoadingHistory" class="history-loading">正在加载历史数据...</div>
        <div v-else-if="!historyVersions.length" class="history-empty">暂无历史版本</div>
        <div v-else class="history-timeline">
          <article
            v-for="record in historyVersions"
            :key="record.versionId"
            :class="['history-version-node', record.isActiveDataVersion ? 'active-data' : '']"
          >
            <div class="history-timeline-rail">
              <span class="history-timeline-dot"></span>
            </div>
            <div class="history-version-main">
              <div class="history-version-title-row">
                <div>
                  <strong>{{ historyRecordTitle(record) }}</strong>
                </div>
                <span v-if="record.isActiveDataVersion" class="history-current-tag">当前版本</span>
              </div>
              <div class="history-meta">
                <span>修改人：{{ record.username || '未知用户' }}</span>
                <span>修改时间：{{ formatHistoryTime(record.committedAt) }}</span>
              </div>
            </div>
            <div class="history-version-side">
              <el-button
                size="small"
                @click.stop="showHistoryDetails(record)"
              >
                修改明细
              </el-button>
              <el-button
                size="small"
                type="primary"
                plain
                :loading="historyPreview.loading && historyPreview.version?.versionId === record.versionId"
                @click.stop="viewHistoryVersion(record)"
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

    <aside v-if="historyDetails.visible" class="history-detail-panel" aria-label="历史修改明细">
      <div class="history-detail-head">
        <div>
          <p class="panel-kicker">修改明细</p>
          <h2>{{ historyRecordTitle(historyDetails.record) }}</h2>
          <span>修改人：{{ historyDetails.record?.username || "未知用户" }} · 修改时间：{{ formatHistoryTime(historyDetails.record?.committedAt) }}</span>
        </div>
        <button class="detail-close-btn" type="button" title="关闭明细" aria-label="关闭明细" @click="closeHistoryDetails">
          <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round">
            <line x1="6" y1="6" x2="18" y2="18"></line>
            <line x1="18" y1="6" x2="6" y2="18"></line>
          </svg>
        </button>
      </div>
      <div class="history-detail-groups">
        <section v-for="group in historyDetailGroups" :key="group.key" class="history-detail-group">
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
                    @click="previewEvidenceImage(image)"
                  >
                    <img :src="image.dataUrl" :alt="image.name || '证据图片'" />
                  </button>
                </div>
              </div>
              <div class="history-detail-row-meta">
                <span>{{ row.username }}</span>
                <time>{{ formatHistoryTime(row.committedAt) }}</time>
              </div>
            </article>
          </div>
          <p v-else class="history-preview-empty">未修改{{ group.label }}</p>
        </section>
      </div>
    </aside>
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
          <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <rect x="3" y="3" width="18" height="18" rx="2"></rect>
            <circle cx="8.5" cy="8.5" r="1.5"></circle>
            <path d="m21 15-5-5L5 21"></path>
          </svg>
          <div>
            <strong>点击上传或拖入图片</strong>
            <span>最多 6 张，会随本次提交进入历史明细</span>
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
      <div class="dm-edit-dialog-footer">
        <el-button @click="cancelCommitDialog">取消</el-button>
        <el-button type="primary" :disabled="!commitDialog.message.trim() || commitDialog.processing" :loading="commitDialog.processing" @click="confirmCommitDialog">提交</el-button>
      </div>
    </template>
  </el-dialog>

  <el-dialog
    v-model="attributeTable.visible"
    :title="attributeTable.title"
    width="min(1180px, calc(100vw - 48px))"
    append-to-body
    align-center
    class="dm-attribute-dialog"
    :close-on-click-modal="false"
    destroy-on-close
  >
    <div class="attribute-dialog-head">
      <div>
        <p>{{ attributeTable.subtitle }}</p>
        <span>筛选 {{ attributeTable.rows.length }} 行，{{ attributeTableChangedCount }} 行已修改</span>
      </div>
      <div class="attribute-dialog-tools">
        <el-button v-if="attributeTable.datasetType === 'station' && attributeTable.route" size="small" @click="toggleAttributeRouteStations">
          {{ attributeTable.showRouteStations ? "仅编辑本站" : "编辑全线站点" }}
        </el-button>
        <el-button size="small" @click="addAttributeTableRow">新增一行</el-button>
        <el-button size="small" :disabled="!attributeTableChangedCount" @click="resetAttributeTableDraft">重置</el-button>
      </div>
    </div>
    <el-table
      class="attribute-grid"
      :data="attributeTable.rows"
      :row-key="(row) => row.rowId"
      :row-class-name="attributeTableRowClassName"
      height="clamp(360px, calc(100vh - 290px), 620px)"
      size="small"
      border
      scrollbar-always-on
    >
      <el-table-column type="index" label="#" width="52" fixed />
      <el-table-column label="状态" width="82" fixed>
        <template #default="{ row }">
          <el-tag size="small" :type="attributeRowTagType(row)">{{ attributeRowStatusLabel(row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column
        v-for="column in attributeTable.columns"
        :key="column.key"
        :prop="column.key"
        :label="column.label"
        :min-width="attributeColumnWidth(column.key)"
        show-overflow-tooltip
      >
        <template #default="{ row }">
          <el-input
            v-model="row.properties[column.key]"
            size="small"
            clearable
            :disabled="row.status === 'deleted'"
            @input="markAttributeRowTouched(row)"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="96" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'deleted'" link size="small" type="primary" @click="restoreAttributeTableRow(row)">撤销</el-button>
          <el-button v-else link size="small" type="danger" @click="removeAttributeTableRow(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <template #footer>
      <div class="dm-edit-dialog-footer attribute-footer">
        <span>{{ attributeTableChangedCount ? "修改会先进入右侧待提交列表" : "编辑单元格或新增、删除行后再生成修改项" }}</span>
        <div>
          <el-button @click="attributeTable.visible = false">关闭</el-button>
          <el-button type="primary" :disabled="!attributeTableChangedCount" @click="applyAttributeTableChanges">生成修改项</el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ElMessage, ElMessageBox } from "element-plus";
import { commitRealDataEdits, compareRealDataShp } from "@/api/realData.js";
import {
  getCachedAreaList,
  getCachedRealData,
  getCachedRealDataHistory,
  invalidateCachedHistory,
  invalidateCachedRealData,
  readCachedHistory,
  readCachedRealData,
} from "@/utils/realDataCache.js";
import busStationIconUrl from "@/assets/images/datamanagement/bus-station.svg?url";
import busStationHighlightIconUrl from "@/assets/images/datamanagement/bus-station_highlight.svg?url";
import { lngLatToWebMercator } from "@/mymap/index.js";

defineOptions({
  name: "DataManagement",
});

const MapRef = inject("MapRef", ref(null));
const activeKey = ref("overview");
const expandedKeys = ref(["update"]);
const areaList = ref(["广州市"]);
const selectedArea = ref("广州市");
const isLoadingAreas = ref(false);
const isLoadingLayer = ref(false);
const isLoadingHistory = ref(false);
const loadError = ref("");
const historyError = ref("");
const realDataRevision = ref(0);
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
let layerRequestSeq = 0;
let historyRequestSeq = 0;
let restoringAreaSelection = false;
let confirmedAreaSelection = false;
let zoomListenerId = null;
let rotateListenerId = null;
let stationClickListenerId = null;
let routeSpatialIndex = [];
let stationSearchIndex = [];
let lineSearchIndex = [];
let depotSearchIndex = [];
let realDataCollections = {
  lines: emptyFeatureCollection(),
  stations: emptyFeatureCollection(),
  routeStops: emptyFeatureCollection(),
  depots: emptyFeatureCollection(),
};
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
const STATION_ICON_BASE_SIZE = 96;
const STATION_ROUTE_MATCH_METERS = 80;
const STATION_ROUTE_FALLBACK_MATCH_METERS = 140;
const MAX_RENDERED_EDIT_OPERATIONS = 200;
const LINE_ATTRIBUTE_FIELD_ORDER = [
  "route_cn",
  "route_en",
  "route_id",
  "city_code",
  "route_type",
  "company_cn",
  "company_en",
  "s_stop_cn",
  "s_stop_en",
  "e_stop_cn",
  "e_stop_en",
  "distance",
  "total_stop",
  "start_time",
  "end_time",
  "loop",
  "status",
  "basic_prc",
  "total_prc",
  "city_cn",
  "city_en",
  "type_en",
  "length",
  "interval",
];
const STATION_ATTRIBUTE_FIELD_ORDER = [
  "name_cn",
  "name_en",
  "stop_id",
  "route_cn",
  "route_en",
  "route_id",
  "city_code",
  "city_cn",
  "city_en",
  "sequence",
];

const isExpanded = (key) => expandedKeys.value.includes(key);
const showMapSearch = computed(() => isMapDataPage(activeKey.value) || historyPreview.visible);
const activeEditDataset = computed(() => editDatasetFromKey(activeKey.value));
const searchPlaceholder = computed(() => {
  if (activeKey.value === "update_station") return "搜索站点";
  if (activeKey.value === "update_line") return "搜索线路";
  if (activeKey.value === "update_depot") return "搜索场站";
  return "搜索站点/线路";
});
const hasActiveDetail = computed(() => Boolean(selectedStation.value || selectedRoute.value));
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
  const depotItems = activeKey.value === "update_depot" ? rankSearchItems(depotSearchIndex, query) : [];
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
      title: pendingAddDataset.value === "depot" ? "在地图上选择新场站位置" : "选择场站后再编辑",
      description: pendingAddDataset.value === "depot" ? "下一次点击地图空白处会打开新增场站表单。" : "可搜索场站，也可直接点击地图上的场站。",
      steps: ["点击已有场站可改名或删除", "点击地图空白处可新增场站", "所有修改会先进入待提交列表"],
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
  if (historyPreview.visible) return "历史数据预览";
  if (selectedStation.value) return "站点详情";
  if (selectedRoute.value) return "线路详情";
  return "真实数据";
});
const panelTitle = computed(() => {
  if (historyPreview.visible && selectedStation.value) return "选中站点";
  if (historyPreview.visible && selectedRoute.value) return "选中线路";
  if (historyPreview.visible) return "数据总览";
  if (selectedStation.value) return "选中站点";
  if (selectedRoute.value) return "选中线路";
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
    const lineCollection = normalizeLineFeatureCollection(data.lines);
    const stationCollection = normalizeStationFeatureCollection(data.stations);
    const routeStopCollection = normalizeRouteStopFeatureCollection(data.routeStops);
    const depotCollection = normalizeDepotFeatureCollection(data.depots);
    realDataCollections = {
      lines: lineCollection,
      stations: stationCollection,
      routeStops: routeStopCollection,
      depots: depotCollection,
    };
    routeSpatialIndex = buildRouteSpatialIndex(lineCollection);
    lineSearchIndex = buildLineSearchIndex(lineCollection);
    stationSearchIndex = buildStationSearchIndex(stationCollection);
    depotSearchIndex = buildDepotSearchIndex(depotCollection);
    ensureSourceData(map, SOURCE_LINES, lineCollection);
    ensureSourceData(map, SOURCE_SELECTED_LINE, emptyFeatureCollection());
    ensureSourceData(map, SOURCE_STATIONS, stationCollection);
    ensureSourceData(map, SOURCE_SELECTED_STATION, emptyFeatureCollection());
    ensureSourceData(map, SOURCE_SELECTED_ROUTE_STATIONS, emptyFeatureCollection());
    ensureSourceData(map, SOURCE_DEPOTS, depotCollection);
    ensureSourceData(map, SOURCE_SELECTED_DEPOT, emptyFeatureCollection());
    await ensureStationIcon(map);
    ensureRealDataLayerSet(map);
    setRealDataLayerVisibility(map, {
      lines: isOverview || isLineUpdate,
      stations: isOverview || isStationUpdate,
      stationLabels: isStationUpdate,
      depots: isDepotUpdate,
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
      type: "circle",
      source: SOURCE_DEPOTS,
      paint: depotCirclePaint(),
    });
  }
  if (!map.getLayer(LAYER_DEPOT_LABELS)) {
    map.addLayer({
      id: LAYER_DEPOT_LABELS,
      type: "symbol",
      source: SOURCE_DEPOTS,
      layout: depotLabelLayout(),
      paint: stationLabelPaint(),
    });
  }
  if (!map.getLayer(LAYER_DEPOT_SELECTED)) {
    map.addLayer({
      id: LAYER_DEPOT_SELECTED,
      type: "circle",
      source: SOURCE_SELECTED_DEPOT,
      paint: selectedDepotCirclePaint(),
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
  setLayerVisibility(map, LAYER_DEPOT_LABELS, visibility.depots ? visible : hidden);
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
    "text-field": ["coalesce", ["get", "stop_cn"], ["get", "name"], ["get", "stop_name"], ["get", "stop_en"], ""],
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
    "text-field": ["coalesce", ["get", "depot_name"], ["get", "name"], ["get", "场站名称"], ""],
    "text-size": ["interpolate", ["linear"], ["zoom"], 9, 10, 12, 12, 15, 14],
    "text-anchor": "left",
    "text-offset": [1.1, 0],
    "text-max-width": 10,
    "text-allow-overlap": true,
    "text-ignore-placement": true,
    "text-padding": 3,
  };
}

function depotCirclePaint() {
  return {
    "circle-radius": ["interpolate", ["linear"], ["zoom"], 8, 4, 12, 6, 15, 8],
    "circle-color": "#2f6f73",
    "circle-opacity": 0.88,
    "circle-stroke-color": "#f8fbfc",
    "circle-stroke-width": 1.5,
  };
}

function selectedDepotCirclePaint() {
  return {
    "circle-radius": ["interpolate", ["linear"], ["zoom"], 8, 7, 12, 10, 15, 13],
    "circle-color": "#315d8a",
    "circle-opacity": 0.96,
    "circle-stroke-color": "#1f3132",
    "circle-stroke-width": 2,
  };
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
      properties.route_id ||
      properties.id ||
      [routeName(properties), properties.s_stop_cn, properties.e_stop_cn, index].filter(Boolean).join("-") ||
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
          [properties.route_id, properties.route_cn, properties.stop_id, properties.sequence, coordinates?.[0], coordinates?.[1], index].filter(Boolean).join("-") ||
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

function stationFeatureKey(feature, index = 0) {
  const properties = feature?.properties || {};
  const coordinates = pointCoordinates(feature?.geometry);
  return String(
    properties.stop_id ||
      properties.id ||
      `${properties.stop_cn || properties.name_cn || properties.name || "station"}-${coordinates?.[0] ?? "x"}-${coordinates?.[1] ?? "y"}-${index}`,
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
    updateSelectedDepotLayer(feature);
    showEditActionMenu(event, "depot", feature, [
      { key: "rename_depot", label: "修改场站名称" },
      { key: "delete_depot", label: "删除场站" },
    ]);
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
  if (datasetType !== "line" && datasetType !== "station") {
    ElMessage.warning("当前仅支持上传标准线路或站点 SHP");
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
  if (!files.length || (datasetType !== "line" && datasetType !== "station")) return;
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
    ElMessage.success(`已从上传 SHP 识别 ${operations.length} 条${datasetTypeLabel(datasetType)}修改`);
  } finally {
    isSubmittingEdit.value = false;
    if (event?.target) event.target.value = "";
  }
}

function isAttributeEditableDataset(datasetType) {
  return datasetType === "line" || datasetType === "station";
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
  const routeLabel = route ? routeName(route.properties || {}) || route.name : "";
  const routeId = route ? routeDataId(route.properties || {}) : "";
  const routeStops = Array.isArray(realDataCollections.routeStops?.features) ? realDataCollections.routeStops.features : [];
  const matches = routeStops.filter((feature) => {
    const stopProperties = feature?.properties || {};
    const stopId = valueOrEmpty(stopProperties.stop_id || stopProperties._stationKey);
    const stationMatches = (stationId && stopId && stationId === stopId) || Boolean(name && stationName(stopProperties) === name);
    if (!stationMatches) return false;
    return route ? isRouteStopMatch(stopProperties, routeLabel, routeId) : true;
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
  const ordered = datasetType === "line" ? LINE_ATTRIBUTE_FIELD_ORDER : STATION_ATTRIBUTE_FIELD_ORDER;
  const keys = new Set();
  ordered.forEach((key) => keys.add(key));
  rows.forEach((row) => Object.keys(row.properties || {}).forEach((key) => keys.add(key)));
  return [...keys].filter(Boolean).map((key) => ({ key, label: attributeColumnLabel(key) }));
}

function attributeColumnLabel(key) {
  const labels = {
    route_cn: "线路中文名",
    route_en: "线路英文名",
    route_id: "线路ID",
    route_type: "线路类型",
    company_cn: "公司中文名",
    company_en: "公司英文名",
    s_stop_cn: "首站中文名",
    s_stop_en: "首站英文名",
    e_stop_cn: "末站中文名",
    e_stop_en: "末站英文名",
    distance: "距离",
    total_stop: "站点数",
    start_time: "首班时间",
    end_time: "末班时间",
    basic_prc: "基础票价",
    total_prc: "全程票价",
    city_cn: "城市中文名",
    city_en: "城市英文名",
    type_en: "英文类型",
    interval: "发车间隔",
    name_cn: "站点中文名",
    name_en: "站点英文名",
    stop_id: "站点ID",
    city_code: "城市编码",
    sequence: "站序",
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
    properties.route_cn = selectedRoute.value?.name || properties.route_cn || "";
  } else {
    fillRoutePropertiesForNewStationRow(properties);
    if (attributeTable.showRouteStations) {
      properties.sequence = properties.sequence || String(nextAttributeRouteSequence());
      properties.stop_id = properties.stop_id || featureId;
    } else {
      const tableStation = attributeTable.station || selectedStation.value;
      properties.name_cn = tableStation?.name || properties.name_cn || "";
      properties.stop_id = tableStation?.id || properties.stop_id || featureId;
    }
  }
  const row = {
    rowId: featureId,
    status: "added",
    targetId: featureId,
    featureId,
    geometry: defaultAttributeRowGeometry(attributeTable.datasetType),
    baseProperties: {
      _featureId: featureId,
      ...(attributeTable.datasetType === "line" ? { _lineKey: featureId } : { _stationKey: properties.stop_id || featureId, _routeStopKey: featureId }),
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
  properties.route_cn = properties.route_cn || routeName(routeProperties) || attributeTable.route.name || "";
  properties.route_en = properties.route_en || valueOrEmpty(routeProperties.route_en || routeProperties.name_en || routeProperties.route_name_en);
  properties.route_id = properties.route_id || routeDataId(routeProperties);
  properties.city_code = properties.city_code || valueOrEmpty(routeProperties.city_code);
  properties.city_cn = properties.city_cn || valueOrEmpty(routeProperties.city_cn);
  properties.city_en = properties.city_en || valueOrEmpty(routeProperties.city_en);
}

function nextAttributeRouteSequence() {
  const values = attributeTable.rows
    .map((row) => Number(firstAvailableValue(row.properties || {}, ["sequence", "seq", "stop_seq", "stop_order", "order"])))
    .filter((value) => Number.isFinite(value));
  return values.length ? Math.max(...values) + 1 : attributeTable.rows.length + 1;
}

function defaultAttributeRowGeometry(datasetType) {
  if (datasetType === "line") {
    return selectedRoute.value?.feature?.geometry ? deepClone(selectedRoute.value.feature.geometry) : null;
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

function attributeRowTagType(row) {
  if (row.status === "added") return "success";
  if (row.status === "deleted") return "danger";
  if (attributeRowChanged(row)) return "warning";
  return "info";
}

function attributeTableRowClassName({ row }) {
  if (row.status === "deleted") return "is-deleted";
  if (row.status === "added") return "is-added";
  if (attributeRowChanged(row)) return "is-modified";
  return "";
}

function attributeColumnWidth(key) {
  if (["route_cn", "route_en", "company_cn", "company_en", "name_cn", "name_en"].includes(key)) return 170;
  if (["s_stop_cn", "e_stop_cn", "s_stop_en", "e_stop_en"].includes(key)) return 180;
  return 140;
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
  if (datasetType === "line") return routeName(feature.properties) || row.properties?.route_cn || "未命名线路";
  return stationName(feature.properties) || row.properties?.name_cn || "未命名站点";
}

function attributeTargetId(datasetType, feature) {
  const properties = feature?.properties || {};
  if (datasetType === "station") {
    return feature?.id || properties._featureId || routeStopFeatureKey(properties) || properties.stop_id || properties.name_cn || "";
  }
  return feature?.id || properties._featureId || routeName(properties) || properties.route_en || properties.name || "";
}

function routeStopFeatureKey(properties = {}) {
  return [properties.route_cn || properties.route_id, properties.stop_id || properties.name_cn, properties.sequence]
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
    realDataCollections.stations = deriveStationsFromRouteStops(realDataCollections.routeStops);
  }
  refreshDatasetSource(datasetType);
}

function applyUploadOperationPreview(datasetType, operation) {
  const collection = datasetType === "station" ? realDataCollections.routeStops : collectionForDataset(datasetType);
  const features = Array.isArray(collection?.features) ? collection.features : [];
  const targetId = operation.targetId || operation.payload?.targetId;
  const feature = operation.payload?.feature;
  if (operation.type?.startsWith("add_")) {
    if (feature) features.push(feature);
    return;
  }
  const index = features.findIndex((item) => uploadPreviewFeatureKey(item, datasetType) === targetId || featureTargetId(item) === targetId);
  if (index < 0) return;
  if (operation.type?.startsWith("delete_")) {
    features.splice(index, 1);
  } else if (operation.type?.startsWith("replace_") && feature) {
    features.splice(index, 1, feature);
  }
}

function uploadPreviewFeatureKey(feature, datasetType) {
  const properties = feature?.properties || {};
  if (datasetType === "line") return routeName(properties) || properties._featureId || feature?.id || "";
  return [properties.route_cn || properties.route_id || "", properties.stop_id || properties.name_cn || "", properties.sequence || ""].filter(Boolean).join("|");
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
        stop_cn: properties.name_cn,
        stop_en: properties.name_en,
        name_cn: properties.name_cn,
        name_en: properties.name_en,
        city_cn: properties.city_cn,
        city_en: properties.city_en,
        city_code: properties.city_code,
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

function toggleStylePopover() {
  if (!showStylePopover.value) {
    closeSearchResults();
    closeEditActionMenu();
    closeLineRoutePicker();
  }
  showStylePopover.value = !showStylePopover.value;
}

function closeTransientSurfaces() {
  closeSearchResults();
  closeStylePopover();
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
  const collection = collectionForDataset(datasetType);
  const targetId = featureTargetId(target);
  collection.features = collection.features.filter((feature) => featureTargetId(feature) !== targetId);
  refreshDatasetSource(datasetType);
  clearSelection();
}

function addLocalFeature(datasetType, payload) {
  const collection = collectionForDataset(datasetType);
  const featureId = `${datasetType}_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`;
  const properties = {
    _featureId: featureId,
    [datasetType === "station" ? "_stationKey" : "_depotKey"]: featureId,
    [datasetType === "station" ? "stop_cn" : "depot_name"]: payload.name || "未命名",
  };
  collection.features.push({
    type: "Feature",
    id: featureId,
    geometry: { type: "Point", coordinates: [Number(payload.lng), Number(payload.lat)] },
    properties,
  });
  refreshDatasetSource(datasetType);
}

function updateLocalFeatureProperties(datasetType, target, updater) {
  updateLocalFeature(datasetType, target, (feature) => updater(feature.properties || (feature.properties = {})));
}

function updateLocalFeature(datasetType, target, updater) {
  const collection = collectionForDataset(datasetType);
  const targetId = featureTargetId(target);
  const feature = collection.features.find((item) => featureTargetId(item) === targetId);
  if (!feature) return;
  updater(feature);
  refreshDatasetSource(datasetType);
}

function refreshDatasetSource(datasetType) {
  const map = MapRef.value?.map;
  const collection = collectionForDataset(datasetType);
  const sourceId = datasetType === "station" ? SOURCE_STATIONS : datasetType === "line" ? SOURCE_LINES : SOURCE_DEPOTS;
  map?.getSource(sourceId)?.setData?.(collection);
  if (datasetType === "station") updateStationSelectionLayers();
  if (datasetType === "line") clearSelectedLineLayer();
  if (datasetType === "depot") updateSelectedDepotLayer(null);
}

function collectionForDataset(datasetType) {
  if (datasetType === "station") return realDataCollections.stations;
  if (datasetType === "line") return realDataCollections.lines;
  if (datasetType === "depot") return realDataCollections.depots;
  return emptyFeatureCollection();
}

function featureTargetId(feature) {
  const properties = feature?.properties || {};
  return String(properties._featureId || feature?.id || properties._stationKey || properties._lineKey || properties._depotKey || properties.stop_id || properties.route_cn || properties.name || "");
}

function editTargetName(datasetType, feature) {
  const properties = feature?.properties || {};
  if (datasetType === "line") return routeName(properties) || "未命名线路";
  if (datasetType === "depot") return depotName(properties);
  return stationName(properties);
}

function namePropertyForDataset(datasetType, properties = {}) {
  const candidates = datasetType === "line" ? ["route_cn", "name", "route_name"] : datasetType === "depot" ? ["depot_name", "name", "场站名称"] : ["stop_cn", "name", "stop_name"];
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
    updateSelectedDepotLayer(result.feature);
    focusFeature(result.feature, { pointZoom: 15 });
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
  closeLineRoutePicker();
  clearSelectedLineLayer();
  updateStationSelectionLayers();
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
  closeLineRoutePicker();
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
  return String(properties.stop_cn || properties.name_cn || properties.name || properties.stop_name || properties.name_en || properties.stop_en || "未命名站点");
}

function depotName(properties = {}) {
  return String(properties.depot_name || properties.name || properties["场站名称"] || properties.station_name || "未命名场站");
}

function routesForStation(feature) {
  const explicitRoutes = routeNamesForStation(feature);
  if (explicitRoutes.length) return explicitRoutes;
  const coordinates = pointCoordinates(feature.geometry);
  if (!coordinates) return [];
  const point = lngLatToWebMercator(coordinates[0], coordinates[1]);
  let matches = matchRoutes(point, STATION_ROUTE_MATCH_METERS);
  if (!matches.length) {
    matches = matchRoutes(point, STATION_ROUTE_FALLBACK_MATCH_METERS);
  }
  const seen = new Set();
  const routes = [];
  for (const match of matches.sort((left, right) => left.distance - right.distance)) {
    if (!match.name || seen.has(match.name)) continue;
    seen.add(match.name);
    routes.push(match.name);
  }
  const expectedCount = Number(feature.properties?.num);
  return Number.isFinite(expectedCount) && expectedCount > 0 && routes.length > expectedCount ? routes.slice(0, expectedCount) : routes;
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
    .map((stopFeature) => routeName(stopFeature.properties))
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
  const start = valueOrEmpty(properties.s_stop_cn || properties.start_stop || properties.start);
  const end = valueOrEmpty(properties.e_stop_cn || properties.end_stop || properties.end);
  if (!start && !end) return "暂无";
  return `${start || "未知"} - ${end || "未知"}`;
}

function routeServiceTime(properties = {}) {
  const start = formatRouteTime(properties.start_time);
  const end = formatRouteTime(properties.end_time);
  if (!start && !end) return "暂无";
  return `${start || "未知"} - ${end || "未知"}`;
}

function routeHeadway(properties = {}) {
  const value = firstAvailableValue(properties, ["avg_headway", "headway", "interval", "avg_interval", "平均发车间隔", "发车间隔"]);
  if (!value) return "暂无";
  return String(value).match(/[分m]/i) ? String(value) : `${value} 分钟`;
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
  const spatialStations = routeStationsFromGeometry(route);
  if (spatialStations.length) return spatialStations;
  const start = valueOrEmpty(properties.s_stop_cn || properties.start_stop || properties.start);
  const end = valueOrEmpty(properties.e_stop_cn || properties.end_stop || properties.end);
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
  const routeLabel = routeName(properties) || route?.name || "";
  const routeId = routeDataId(properties);
  return routeStops
    .map((feature, index) => {
      const stopProperties = feature.properties || {};
      if (!isRouteStopMatch(stopProperties, routeLabel, routeId)) return null;
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

function isRouteStopMatch(stopProperties = {}, routeLabel = "", routeId = "") {
  const stopRouteId = routeDataId(stopProperties);
  if (routeId && stopRouteId && routeId === stopRouteId) return true;
  const stopRouteLabel = valueOrEmpty(stopProperties.route_cn || stopProperties.route_name || stopProperties.route);
  return Boolean(routeLabel && stopRouteLabel && stopRouteLabel === routeLabel);
}

function routeStopSequence(properties = {}) {
  const value = Number(firstAvailableValue(properties, ["sequence", "seq", "stop_seq", "stop_order", "order"]));
  return Number.isFinite(value) ? value : Number.MAX_SAFE_INTEGER;
}

function routeDataId(properties = {}) {
  return valueOrEmpty(properties.route_id || properties.routeId || properties.line_id || properties.lineId);
}

function routeStationsFromGeometry(route = selectedRoute.value) {
  const paths = projectedRoutePathsForOption(route);
  if (!paths.length) return [];
  const bounds = projectedPathBounds(paths);
  const routeShape = { paths, bounds };
  const stationFeatures = Array.isArray(realDataCollections.stations?.features) ? realDataCollections.stations.features : [];
  const threshold = STATION_ROUTE_FALLBACK_MATCH_METERS;
  const candidates = [];
  stationFeatures.forEach((feature, index) => {
    const coordinates = pointCoordinates(feature.geometry);
    if (!coordinates) return;
    const point = lngLatToWebMercator(coordinates[0], coordinates[1]);
    if (!isValidPoint(point) || !containsPointWithPadding(bounds, point, threshold)) return;
    const distance = distanceToRoute(point, routeShape, threshold);
    if (distance > threshold) return;
    const projected = projectPointAlongPaths(point, paths);
    if (!projected) return;
    const stationProperties = feature.properties || {};
    candidates.push({
      facilityId: stationProperties._stationKey || stationProperties.stop_id || stationProperties.id || `${stationName(stationProperties)}-${index}`,
      facilityName: stationName(stationProperties),
      distance,
      order: projected.order,
      sourceIndex: index,
    });
  });
  return dedupeRouteStationCandidates(candidates)
    .sort((left, right) => left.order - right.order || left.distance - right.distance || left.sourceIndex - right.sourceIndex)
    .map(({ facilityId, facilityName }) => ({ facilityId, facilityName }));
}

function projectedRoutePathsForOption(route = selectedRoute.value) {
  return routeFeaturesForOption(route)
    .flatMap((feature) => lineCoordinatePaths(feature.geometry))
    .map((path) => path.map((coordinate) => lngLatToWebMercator(coordinate[0], coordinate[1])).filter(isValidPoint))
    .filter((path) => path.length > 1);
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
  const routeLabel = routeName(routeProperties);
  const featureLabel = routeName(featureProperties);
  if (!routeLabel || !featureLabel || routeLabel !== featureLabel) return false;
  const routeStart = routeStartName(routeProperties);
  const routeEnd = routeEndName(routeProperties);
  const featureStart = routeStartName(featureProperties);
  const featureEnd = routeEndName(featureProperties);
  return (!routeStart || !featureStart || routeStart === featureStart) && (!routeEnd || !featureEnd || routeEnd === featureEnd);
}

function routeStartName(properties = {}) {
  return valueOrEmpty(properties.s_stop_cn || properties.start_stop || properties.start);
}

function routeEndName(properties = {}) {
  return valueOrEmpty(properties.e_stop_cn || properties.end_stop || properties.end);
}

function projectPointAlongPaths(point, paths) {
  let best = null;
  let pathOffset = 0;
  for (const path of paths) {
    let segmentOffset = 0;
    for (let index = 1; index < path.length; index += 1) {
      const projection = projectPointToSegment(point, path[index - 1], path[index]);
      const order = pathOffset + segmentOffset + projection.along;
      if (!best || projection.distance < best.distance) {
        best = { distance: projection.distance, order };
      }
      segmentOffset += projection.segmentLength;
    }
    pathOffset += segmentOffset;
  }
  return best;
}

function projectPointToSegment(point, start, end) {
  const dx = end[0] - start[0];
  const dy = end[1] - start[1];
  const lengthSquared = dx * dx + dy * dy;
  if (!lengthSquared) {
    return {
      distance: Math.hypot(point[0] - start[0], point[1] - start[1]),
      along: 0,
      segmentLength: 0,
    };
  }
  const ratio = Math.max(0, Math.min(1, ((point[0] - start[0]) * dx + (point[1] - start[1]) * dy) / lengthSquared));
  const segmentLength = Math.sqrt(lengthSquared);
  return {
    distance: Math.hypot(point[0] - (start[0] + ratio * dx), point[1] - (start[1] + ratio * dy)),
    along: ratio * segmentLength,
    segmentLength,
  };
}

function dedupeRouteStationCandidates(candidates) {
  const seen = new Set();
  const result = [];
  for (const candidate of candidates) {
    const key = candidate.facilityId || candidate.facilityName;
    if (!key || seen.has(key)) continue;
    seen.add(key);
    result.push(candidate);
  }
  return result;
}

function getRouteLength(properties) {
  const meters = routeLengthMeters(properties);
  if (!Number.isFinite(meters) || meters <= 0) return "暂无";
  return meters >= 1000 ? `${(meters / 1000).toFixed(1)} km` : `${Math.round(meters)} m`;
}

function getRouteFirstTime(properties) {
  return formatRouteTime(properties.start_time) || "暂无";
}

function getRouteLastTime(properties) {
  return formatRouteTime(properties.end_time) || "暂无";
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

function routeLengthMeters(properties = {}) {
  const value = firstAvailableValue(properties, ["length", "distance", "routeDist", "route_len", "line_length"]);
  const number = parseFloat(value);
  if (!Number.isFinite(number) || number <= 0) return null;
  return number > 100 ? number : number * 1000;
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

function matchRoutes(point, thresholdMeters) {
  const matches = [];
  for (const route of routeSpatialIndex) {
    if (!containsPointWithPadding(route.bounds, point, thresholdMeters)) continue;
    const distance = distanceToRoute(point, route, thresholdMeters);
    if (distance <= thresholdMeters) {
      matches.push({ name: route.name, distance });
    }
  }
  return matches;
}

function buildRouteSpatialIndex(collection) {
  const features = Array.isArray(collection?.features) ? collection.features : [];
  return features
    .map((feature) => {
      const paths = lineCoordinatePaths(feature.geometry)
        .map((path) => path.map((coordinate) => lngLatToWebMercator(coordinate[0], coordinate[1])).filter(isValidPoint))
        .filter((path) => path.length > 1);
      if (!paths.length) return null;
      return {
        name: routeName(feature.properties),
        paths,
        bounds: projectedPathBounds(paths),
      };
    })
    .filter(Boolean);
}

function routeName(properties = {}) {
  return String(properties.route_cn || properties.name || properties.route_name || properties.route_en || "").trim();
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
      searchText: normalizeSearchText([name, properties.name_cn, properties.name_en, properties.stop_en, properties.stop_id, properties.route_cn].filter(Boolean).join(" ")),
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
      searchText: normalizeSearchText([name, properties.route_en, properties.s_stop_cn, properties.e_stop_cn].filter(Boolean).join(" ")),
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

function projectedPathBounds(paths) {
  const bounds = {
    minX: Infinity,
    minY: Infinity,
    maxX: -Infinity,
    maxY: -Infinity,
  };
  for (const path of paths) {
    for (const point of path) {
      bounds.minX = Math.min(bounds.minX, point[0]);
      bounds.minY = Math.min(bounds.minY, point[1]);
      bounds.maxX = Math.max(bounds.maxX, point[0]);
      bounds.maxY = Math.max(bounds.maxY, point[1]);
    }
  }
  return bounds;
}

function containsPointWithPadding(bounds, point, padding) {
  return point[0] >= bounds.minX - padding && point[0] <= bounds.maxX + padding && point[1] >= bounds.minY - padding && point[1] <= bounds.maxY + padding;
}

function distanceToRoute(point, route, earlyStopDistance = 0) {
  let minDistance = Infinity;
  for (const path of route.paths) {
    for (let index = 1; index < path.length; index += 1) {
      minDistance = Math.min(minDistance, distanceToSegment(point, path[index - 1], path[index]));
      if (earlyStopDistance && minDistance <= earlyStopDistance) {
        return minDistance;
      }
    }
  }
  return minDistance;
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

function discardActiveEdits() {
  const datasetType = activeEditDataset.value;
  if (!datasetType) return;
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
    font-weight: 750;
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
    font-weight: 650;

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
    font-weight: 780;
  }

  strong {
    color: #12304f;
    font-size: 13px;
    line-height: 1.3;
    font-weight: 800;
    word-break: break-word;
  }

  p {
    margin: 0;
    color: #64748b;
    font-size: 12px;
    line-height: 1.35;
    font-weight: 620;
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
    font-weight: 780;
  }

  p {
    margin: 0;
    font-weight: 620;
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
    font-weight: 640;

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
      font-weight: 850;
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

.history-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;

  .panel-kicker {
    margin: 0 0 6px;
    color: #64748b;
    font-size: 12px;
    font-weight: 760;
  }

  h1 {
    margin: 0;
    color: #10243f;
    font-size: 24px;
    line-height: 1.25;
    font-weight: 800;
    letter-spacing: 0;
  }
}

.history-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.history-current-version,
.history-list-panel,
.history-risk-panel {
  border: 1px solid rgba(21, 105, 222, 0.12);
  border-radius: 8px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(248, 251, 255, 0.96));
  box-shadow:
    0 14px 32px rgba(15, 66, 125, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.88);
}

.history-current-version {
  display: grid;
  gap: 6px;
  padding: 16px 18px;

  span {
    color: #64748b;
    font-size: 12px;
    font-weight: 700;
  }

  strong {
    min-width: 0;
    color: #12304f;
    font-size: 20px;
    line-height: 1.2;
    font-weight: 820;
    word-break: break-word;
  }
}

.history-content {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 16px;
  align-items: start;
}

.history-list-panel {
  min-width: 0;
  padding: 16px;
}

.history-list-title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 14px;
  border-bottom: 1px solid rgba(21, 105, 222, 0.09);
  cursor: pointer;

  h2 {
    margin: 0;
    color: #10243f;
    font-size: 18px;
    line-height: 1.25;
    font-weight: 780;
  }

  p {
    margin: 5px 0 0;
    color: #64748b;
    font-size: 12px;
    line-height: 1.45;
    font-weight: 620;
  }
}

.history-timeline-panel {
  overflow: hidden;
}

.history-timeline {
  position: relative;
  display: grid;
  gap: 0;
  margin-top: 14px;
}

.history-version-node {
  position: relative;
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr) 132px;
  gap: 14px;
  padding: 14px 0;
  border-bottom: 1px solid rgba(15, 39, 68, 0.08);

  &.active-data {
    .history-version-main {
      background: #f1fbf7;
      border-color: rgba(16, 185, 129, 0.48);
    }

    .history-timeline-dot {
      background: #10b981;
      box-shadow: 0 0 0 4px rgba(16, 185, 129, 0.14);
    }
  }
}

.history-timeline-rail {
  position: relative;
  display: flex;
  justify-content: center;

  &::before {
    content: "";
    position: absolute;
    top: 18px;
    bottom: -16px;
    width: 1px;
    background: rgba(21, 105, 222, 0.16);
  }
}

.history-version-node:last-child {
  border-bottom: 0;

  .history-timeline-rail::before {
    display: none;
  }
}

.history-timeline-dot {
  position: relative;
  z-index: 1;
  width: 10px;
  height: 10px;
  margin-top: 8px;
  border-radius: 50%;
  background: #8aa4c2;
  box-shadow: 0 0 0 4px rgba(21, 105, 222, 0.08);
}

.history-version-main {
  min-width: 0;
  padding: 12px;
  border: 1px solid rgba(21, 105, 222, 0.1);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.82);
  box-shadow: 0 4px 12px rgba(15, 66, 125, 0.035);
  transition:
    border-color var(--app-motion-normal) var(--app-ease-out),
    box-shadow var(--app-motion-normal) var(--app-ease-out),
    transform var(--app-motion-fast) var(--app-ease-press);
}

.history-version-node:hover .history-version-main {
  transform: translateY(-1px);
  border-color: rgba(21, 105, 222, 0.22);
  box-shadow: 0 10px 24px rgba(15, 66, 125, 0.08);
}

.history-version-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;

  strong {
    min-width: 0;
    color: #10243f;
    font-size: 14px;
    line-height: 1.3;
    font-weight: 800;
    word-break: break-word;
  }

  p {
    margin: 5px 0 0;
    color: #475569;
    font-size: 12px;
    line-height: 1.45;
    font-weight: 620;
    word-break: break-word;
  }
}

.history-current-tag {
  flex-shrink: 0;
  padding: 2px 7px;
  border-radius: 5px;
  background: rgba(16, 185, 129, 0.14);
  color: #047857;
  font-size: 11px;
  line-height: 1.25;
  font-weight: 780;
}

.history-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 14px;
  margin-top: 7px;
  color: #64748b;
  font-size: 12px;
  line-height: 1.35;
  font-weight: 640;
}

.history-operation-preview {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.history-operation-chip {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 3px 7px;
  border-radius: 6px;
  background: rgba(21, 105, 222, 0.08);
  color: #1556b7;
  font-size: 11px;
  line-height: 1.3;
  font-weight: 700;
}

.history-version-side {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  justify-content: space-between;
  gap: 10px;
}

.history-risk-panel {
  padding: 16px;
  color: #334155;
  border-left: 3px solid rgba(21, 105, 222, 0.7);

  h2 {
    margin: 0 0 10px;
    color: #10243f;
    font-size: 16px;
    line-height: 1.25;
    font-weight: 780;
  }

  p {
    margin: 0 0 10px;
    font-size: 12px;
    line-height: 1.6;
    font-weight: 620;
  }
}

.history-loading,
.history-empty,
.history-error {
  margin-top: 14px;
  padding: 18px;
  border-radius: 8px;
  background: #f5f8fc;
  color: #64748b;
  font-size: 13px;
  font-weight: 680;
}

.history-error {
  color: #b42318;
  background: #fff4f2;
}

.history-preview-panel {
  position: fixed;
  left: 278px;
  top: calc(var(--app-header-height) + 66px);
  z-index: calc(var(--z-panel) + 2);
  width: 440px;
  max-height: calc(100vh - var(--app-header-height) - 92px);
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 14px;
  overflow-y: auto;
  border-radius: 8px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(248, 251, 255, 0.95));
  border: 1px solid rgba(21, 105, 222, 0.15);
  box-shadow:
    0 18px 44px rgba(15, 66, 125, 0.14),
    inset 0 1px 0 rgba(255, 255, 255, 0.86);
  backdrop-filter: blur(18px) saturate(165%);
  -webkit-backdrop-filter: blur(18px) saturate(165%);
  scrollbar-width: thin;
}

.history-preview-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(21, 105, 222, 0.08);

  .panel-kicker {
    margin: 0 0 5px;
    color: #64748b;
    font-size: 12px;
    font-weight: 760;
  }

  h2 {
    margin: 0;
    color: #10243f;
    font-size: 17px;
    line-height: 1.3;
    font-weight: 800;
    letter-spacing: 0;
    word-break: break-word;
  }

  span {
    display: block;
    margin-top: 7px;
    color: #64748b;
    font-size: 12px;
    line-height: 1.35;
    font-weight: 650;
  }
}

.history-preview-groups {
  display: grid;
  gap: 12px;
}

.history-preview-group {
  display: grid;
  gap: 8px;
  padding: 10px;
  border: 1px solid rgba(21, 105, 222, 0.09);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.72);
  box-shadow: 0 4px 12px rgba(15, 66, 125, 0.035);

  :deep(.el-table) {
    --el-table-border-color: rgba(21, 105, 222, 0.09);
    --el-table-header-bg-color: rgba(21, 105, 222, 0.05);
    --el-table-row-hover-bg-color: rgba(21, 105, 222, 0.035);
    border-radius: 6px;
    overflow: hidden;
    font-size: 12px;
  }
}

.history-preview-group-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;

  h3 {
    margin: 0;
    color: #12304f;
    font-size: 13px;
    font-weight: 800;
  }

  span {
    color: #64748b;
    font-size: 12px;
    font-weight: 700;
  }
}

.history-preview-empty {
  margin: 0;
  padding: 9px 10px;
  border-radius: 7px;
  background: rgba(245, 248, 252, 0.84);
  color: #64748b;
  font-size: 12px;
  font-weight: 650;
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
    font-weight: 760;
    letter-spacing: 0.04em;
  }

  h2 {
    margin: 0;
    color: #10243f;
    font-size: 20px;
    line-height: 1.25;
    font-weight: 820;
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
      font-weight: 640;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
  }

  :deep(.el-tag) {
    --el-tag-border-radius: 6px;
    flex-shrink: 0;
    font-weight: 740;
  }
}

.overview-metric-list {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 14px;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: rgba(21, 105, 222, 0.18) transparent;
  padding-right: 2px;

  &::-webkit-scrollbar {
    width: 4px;
  }

  &::-webkit-scrollbar-thumb {
    background: rgba(21, 105, 222, 0.18);
    border-radius: 10px;
  }
}

.overview-source-note {
  margin: 0;
  padding: 2px 4px 0;
  color: #64748b;
  font-size: 11.5px;
  line-height: 1.4;
  font-weight: 620;
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
    font-weight: 720;
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

    .metric-note {
      font-size: 10px;
      font-weight: 700;
      color: rgba(21, 105, 222, 0.66);
    }
  }

  .value-row {
    display: flex;
    align-items: baseline;
    gap: 4px;

    .hero-num {
      color: var(--app-blue-strong);
      font-family: var(--app-font-number);
      font-size: 34px;
      font-weight: 900;
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

.grid-card {
  min-height: 84px;
  padding: 13px;

  .card-header {
    display: flex;
    align-items: center;
    gap: 6px;
    margin-bottom: 8px;

    .card-icon {
      width: 15px;
      height: 15px;
      display: flex;
      align-items: center;
      justify-content: center;
      transition: transform 0.3s ease;
    }
  }

  .grid-num {
    color: #1e3a8a;
    font-family: var(--app-font-number);
    font-size: 23px;
    font-weight: 850;
    line-height: 1.1;
  }

  .grid-unit {
    align-self: flex-end;
    font-size: 12.5px;
    font-weight: 600;
    color: #6b7280;
    margin-top: 2px;
  }

  &.routes-card {
    background: rgba(248, 251, 255, 0.94);
    .card-icon { color: var(--app-blue); }
  }

  &.stations-card {
    background: rgba(247, 252, 251, 0.94);
    border-color: rgba(13, 148, 136, 0.1);
    
    .card-icon { color: #0d9488; }
    .grid-num { color: #0f766e; }

    &:hover {
      border-color: rgba(13, 148, 136, 0.2);
      background: rgba(246, 251, 253, 0.99);
    }
  }

  &:hover {
    .card-icon {
      transform: scale(1.15) translateY(-1px);
    }
  }
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
      font-weight: 800;
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

    .metric-note {
      font-size: 9px;
      font-weight: 700;
      color: rgba(21, 105, 222, 0.5);
    }
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
        font-weight: 650;
      }

      strong {
        color: var(--app-blue-strong);
        font-family: var(--app-font-number);
        font-size: 16px;
        font-weight: 800;
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
  font-weight: 650;
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
  font-weight: 780;
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
    font-weight: 680;
  }

  strong {
    min-width: 0;
    color: #12304f;
    font-size: 14px;
    line-height: 1.25;
    font-weight: 820;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.detail-summary-card.single-card {
  grid-template-columns: minmax(0, 1fr);
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
  font-weight: 800;
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

.route-name-block {
  display: grid;
  gap: 7px;
  padding: 14px 16px;
  border-radius: 7px;
  background: #fffde2;
  border: 1px solid rgba(245, 233, 6, 0.36);

  span {
    color: #7c6f00;
    font-size: 13.5px;
    font-weight: 700;
  }

  strong {
    min-width: 0;
    color: #111827;
    font-size: 18px;
    line-height: 1.35;
    font-weight: 820;
    word-break: break-word;
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
      font-weight: 820;
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
    font-weight: 800;
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
        font-weight: 680;
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
  font-weight: 800;
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
  font-weight: 850;
}

.compass-btn .pitch-arrows {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1px;
  color: currentColor;
}

.style-popover {
  position: absolute;
  top: 188px;
  right: 48px;
  width: min(240px, calc(100vw - 96px));
  padding: 14px 14px 12px;
  border-radius: 8px;
  background: rgba(251, 253, 255, 0.96);
  border: 1px solid rgba(21, 105, 222, 0.14);
  box-shadow: 0 16px 34px rgba(15, 39, 68, 0.14);
}

.popover-title {
  color: #12304f;
  font-size: 13px;
  font-weight: 760;
  margin-bottom: 10px;
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
    font-weight: 650;
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
  font-weight: 780;
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
  font-weight: 680;
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
  font-weight: 650;
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
  font-weight: 760;
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
  font-weight: 650;
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
  font-weight: 780;
  letter-spacing: 0.08em;
}

.overview-title-row h2,
.overview-station-title,
.history-preview-head h2 {
  color: var(--dm-ink-strong);
  font-weight: 840;
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

.hero-card {
  min-height: 116px;
  padding: 18px;
  border-color: rgba(47, 111, 115, 0.17);
  background:
    radial-gradient(circle at 92% 8%, rgba(184, 135, 70, 0.14), transparent 38%),
    linear-gradient(145deg, rgba(47, 111, 115, 0.14), rgba(255, 255, 252, 0.82) 54%, rgba(184, 135, 70, 0.1));
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

.metric-grid {
  gap: 11px;
}

.grid-card {
  min-height: 96px;
}

.grid-card .card-header .card-icon,
.density-card .card-left .card-icon {
  color: var(--dm-accent);
}

.grid-card.stations-card {
  border-color: rgba(47, 111, 115, 0.13);
  background: rgba(247, 249, 241, 0.75);
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

.style-popover {
  top: 198px;
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

.history-header h1 {
  color: var(--dm-ink-strong);
  font-size: clamp(24px, 2.1vw, 34px);
  font-weight: 850;
  letter-spacing: -0.02em;
}

.history-current-version,
.history-list-panel,
.history-risk-panel {
  border: 1px solid rgba(42, 59, 58, 0.12);
  border-radius: 22px;
  background: rgba(255, 255, 252, 0.74);
  box-shadow: var(--dm-shadow-soft), inset 0 1px 0 rgba(255, 255, 255, 0.72);
}

.history-current-version {
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 18px;
  padding: 18px 20px;
}

.history-current-version span {
  color: var(--dm-muted);
}

.history-current-version strong {
  color: var(--dm-accent-strong);
  font-family: var(--dm-number-font);
  font-size: 23px;
}

.history-content {
  grid-template-columns: minmax(0, 1fr) minmax(280px, 340px);
  gap: 18px;
}

.history-list-title {
  border-bottom-color: rgba(42, 59, 58, 0.09);
}

.history-list-title h2,
.history-risk-panel h2 {
  color: var(--dm-ink-strong);
}

.history-list-title p,
.history-risk-panel p,
.history-meta,
.history-preview-empty {
  color: var(--dm-muted);
}

.history-version-node {
  grid-template-columns: 28px minmax(0, 1fr) 136px;
  border-bottom-color: rgba(42, 59, 58, 0.08);
}

.history-version-main {
  border: 1px solid rgba(42, 59, 58, 0.1);
  border-radius: 18px;
  background: rgba(255, 255, 252, 0.76);
  box-shadow: 0 10px 24px rgba(31, 49, 50, 0.06), inset 0 1px 0 rgba(255, 255, 255, 0.74);
}

.history-version-node:hover .history-version-main {
  transform: translateY(-2px);
  border-color: rgba(47, 111, 115, 0.24);
  box-shadow: 0 18px 36px rgba(31, 49, 50, 0.1);
}

.history-timeline-rail::before {
  background: rgba(47, 111, 115, 0.18);
}

.history-timeline-dot {
  background: var(--dm-muted-soft);
  box-shadow: 0 0 0 5px rgba(47, 111, 115, 0.1);
}

.history-version-node.active-data .history-version-main {
  background: rgba(235, 246, 239, 0.86);
  border-color: rgba(47, 111, 115, 0.36);
}

.history-version-node.active-data .history-timeline-dot,
.history-current-tag {
  background: var(--dm-accent);
}

.history-current-tag {
  color: #fffaf1;
}

.history-risk-panel {
  border-left: 0;
  background:
    linear-gradient(145deg, rgba(47, 111, 115, 0.1), rgba(255, 255, 252, 0.76));
}

.history-loading,
.history-empty,
.history-error {
  border-radius: 16px;
  background: rgba(47, 111, 115, 0.07);
  color: var(--dm-muted);
}

.history-error {
  background: rgba(184, 84, 70, 0.1);
  color: #9b463d;
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

.hero-card {
  background:
    radial-gradient(circle at 92% 8%, rgba(49, 93, 138, 0.11), transparent 38%),
    linear-gradient(145deg, rgba(47, 111, 115, 0.12), rgba(250, 253, 254, 0.86) 55%, rgba(49, 93, 138, 0.07));
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
  box-shadow: 0 0 14px rgba(49, 93, 138, 0.16);
}

.timeline-container .timeline-item .timeline-dot.last {
  border-color: var(--dm-secondary);
}

.timeline-container .timeline-item .timeline-dot.last .dot-inner {
  background: var(--dm-secondary);
}

.history-current-tag {
  color: #f8fbfc;
}

.dm-history-page {
  left: 260px;
  right: 0;
  top: var(--app-header-height);
  bottom: 0;
  border: 0;
  border-radius: 0;
  background:
    linear-gradient(180deg, rgba(248, 251, 252, 0.98), rgba(239, 246, 248, 0.96)),
    repeating-linear-gradient(135deg, rgba(35, 50, 55, 0.018) 0 1px, transparent 1px 9px);
  box-shadow: none;
}

.history-risk-panel {
  background: linear-gradient(145deg, rgba(47, 111, 115, 0.09), rgba(250, 253, 254, 0.82));
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
.style-popover,
.control-block {
  background: #ffffff !important;
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
  background: #ffffff !important;
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
  background: rgba(0, 0, 0, 0.06);
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
  border-top: 1px solid rgba(0, 0, 0, 0.08);
  background: #ffffff;
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

.history-version-node {
  grid-template-columns: 28px minmax(0, 1fr) 166px;
  cursor: default;
}

.history-version-side {
  justify-content: center;
}

.history-version-side :deep(.el-button) {
  width: 112px;
  margin-left: 0;
}

.dm-history-page {
  padding: 24px 26px;
  overflow-y: auto;
}

.history-risk-panel {
  border-left: 0;
}

.history-detail-panel {
  position: fixed;
  top: calc(var(--app-header-height) + 18px);
  right: 24px;
  bottom: 24px;
  z-index: calc(var(--z-panel) + 14);
  width: min(520px, calc(100vw - 320px));
  display: flex;
  flex-direction: column;
  padding: 16px;
  border: 1px solid var(--dm-border);
  border-radius: 18px;
  box-shadow: var(--dm-shadow);
  overflow: hidden;
}

.history-detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.08);
}

.history-detail-head h2 {
  margin: 0;
  color: var(--dm-ink-strong);
  font-size: 18px;
  line-height: 1.3;
  font-weight: 800;
}

.history-detail-head span {
  display: block;
  margin-top: 6px;
  color: var(--dm-muted);
  font-size: 12px;
  line-height: 1.4;
  font-weight: 600;
}

.history-detail-groups {
  flex: 1;
  min-height: 0;
  display: grid;
  gap: 12px;
  overflow-y: auto;
  padding-top: 12px;
}

.history-detail-group {
  display: grid;
  gap: 10px;
  padding: 12px;
}

.history-detail-group-title,
.history-detail-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.history-detail-group-title h3 {
  margin: 0;
  color: var(--dm-ink-strong);
  font-size: 14px;
  line-height: 1.3;
  font-weight: 800;
}

.history-detail-group-title span {
  color: var(--dm-muted);
  font-size: 12px;
  font-weight: 700;
}

.history-detail-list {
  display: grid;
  gap: 8px;
}

.history-detail-row {
  padding: 11px 12px;
}

.history-detail-row-main {
  min-width: 0;
  display: grid;
  gap: 5px;
}

.history-detail-action {
  width: fit-content;
  padding: 2px 7px;
  border-radius: 999px;
  background: var(--dm-accent-soft);
  color: var(--dm-accent-strong);
  font-size: 11px;
  line-height: 1.2;
  font-weight: 760;
}

.history-detail-row-main strong,
.history-detail-row-main p {
  margin: 0;
  min-width: 0;
  word-break: break-word;
}

.history-detail-row-main strong {
  color: var(--dm-ink-strong);
  font-size: 13px;
  line-height: 1.35;
}

.history-detail-row-main p,
.history-detail-row-meta {
  color: var(--dm-muted);
  font-size: 12px;
  line-height: 1.45;
  font-weight: 600;
}

.history-detail-row-meta {
  flex: 0 0 132px;
  display: grid;
  gap: 4px;
  text-align: right;
}

.history-evidence-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 4px;
}

.history-evidence-thumb {
  width: 64px;
  height: 48px;
  padding: 0;
  border: 1px solid rgba(15, 39, 68, 0.12);
  border-radius: 8px;
  background: #f8fbff;
  overflow: hidden;
  cursor: pointer;

  img {
    display: block;
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
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
  border-radius: 18px;
  background: #ffffff;
  box-shadow: 0 24px 64px rgba(15, 23, 42, 0.18);
  overflow: hidden;
}

:global(.dm-edit-dialog .el-dialog__header),
:global(.dm-commit-dialog .el-dialog__header) {
  margin: 0;
  padding: 22px 24px 8px;
}

:global(.dm-edit-dialog .el-dialog__title),
:global(.dm-commit-dialog .el-dialog__title) {
  color: #111827;
  font-size: 19px;
  line-height: 1.3;
  font-weight: 800;
}

:global(.dm-edit-dialog .el-dialog__body),
:global(.dm-commit-dialog .el-dialog__body) {
  padding: 0 24px 18px;
}

:global(.dm-edit-dialog .el-dialog__footer),
:global(.dm-commit-dialog .el-dialog__footer) {
  padding: 14px 24px 22px;
  border-top: 1px solid rgba(0, 0, 0, 0.08);
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
  color: #1f2937;
  font-size: 13px;
  line-height: 1.35;
  font-weight: 760;
}

.dm-edit-form :deep(.el-input__wrapper),
.dm-edit-form :deep(.el-textarea__inner) {
  border-radius: 10px;
  background: #ffffff !important;
  box-shadow: 0 0 0 1px rgba(0, 0, 0, 0.12) inset !important;
}

.dm-edit-form :deep(.el-input__wrapper.is-focus),
.dm-edit-form :deep(.el-textarea__inner:focus) {
  box-shadow: 0 0 0 1.5px var(--dm-accent) inset, 0 0 0 4px rgba(0, 113, 227, 0.1) !important;
}

.field-hint {
  display: block;
  margin-top: 6px;
  color: var(--dm-muted);
  font-size: 12px;
  line-height: 1.45;
  font-weight: 560;
}

.commit-form {
  gap: 16px;
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
  min-height: 112px;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px;
  border: 1px dashed rgba(21, 105, 222, 0.34);
  border-radius: 14px;
  background: rgba(237, 245, 255, 0.62);
  color: #225a92;
  cursor: pointer;
  transition: background 180ms ease, border-color 180ms ease, box-shadow 180ms ease;

  svg {
    flex: 0 0 auto;
    color: var(--dm-accent);
  }

  div {
    display: grid;
    gap: 4px;
    min-width: 0;
  }

  strong {
    color: #123458;
    font-size: 14px;
    line-height: 1.35;
    font-weight: 780;
  }

  span {
    color: #64748b;
    font-size: 12px;
    line-height: 1.45;
    font-weight: 600;
  }

  &:hover,
  &:focus-visible,
  &.is-dragging {
    border-color: rgba(21, 105, 222, 0.72);
    background: rgba(226, 239, 255, 0.86);
    box-shadow: 0 0 0 4px rgba(21, 105, 222, 0.1);
    outline: none;
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
  border: 1px solid rgba(15, 39, 68, 0.1);
  border-radius: 10px;
  background: #f8fbff;

  img {
    display: block;
    width: 100%;
    aspect-ratio: 1.35;
    object-fit: cover;
    background: #e8eef6;
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
    color: #f8fbff;
    cursor: pointer;
  }

  span {
    display: block;
    padding: 6px 8px;
    color: #334155;
    font-size: 11px;
    line-height: 1.35;
    font-weight: 650;
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

:global(.dm-attribute-dialog.el-dialog),
:global(.dm-attribute-dialog .el-dialog) {
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 48px);
  border-radius: 14px;
  background: #f8fbff;
  box-shadow: 0 26px 72px rgba(15, 23, 42, 0.22);
  overflow: hidden;
}

:global(.dm-attribute-dialog .el-dialog__header) {
  margin: 0;
  padding: 18px 22px 10px;
  border-bottom: 1px solid rgba(21, 105, 222, 0.1);
}

:global(.dm-attribute-dialog .el-dialog__title) {
  color: #0f172a;
  font-size: 18px;
  font-weight: 780;
}

:global(.dm-attribute-dialog .el-dialog__body) {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 14px 18px 0;
}

:global(.dm-attribute-dialog .el-dialog__footer) {
  flex: 0 0 auto;
  padding: 14px 18px 18px;
  border-top: 1px solid rgba(21, 105, 222, 0.1);
}

.attribute-dialog-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  flex: 0 0 auto;
  margin-bottom: 12px;

  p {
    margin: 0 0 4px;
    color: #0f253e;
    font-size: 14px;
    font-weight: 720;
  }

  span {
    color: #64748b;
    font-size: 12px;
    font-weight: 560;
  }
}

.attribute-dialog-tools {
  display: inline-flex;
  gap: 8px;
  flex-shrink: 0;
}

.attribute-grid {
  flex: 1 1 auto;
  min-height: 0;
  border-radius: 10px;
  overflow: hidden;

  :deep(.el-table__header th) {
    background: #edf5ff !important;
    color: #18314f;
    font-size: 12px;
    font-weight: 760;
  }

  :deep(.el-table__cell) {
    padding: 6px 0;
  }

  :deep(.el-input__wrapper) {
    min-height: 30px;
    border-radius: 7px;
    background: #ffffff;
    box-shadow: 0 0 0 1px rgba(15, 39, 68, 0.1) inset !important;
  }

  :deep(.el-input__wrapper.is-focus) {
    box-shadow: 0 0 0 1.5px var(--dm-accent) inset, 0 0 0 3px rgba(21, 105, 222, 0.1) !important;
  }

  :deep(.el-scrollbar__bar.is-vertical),
  :deep(.el-scrollbar__bar.is-horizontal) {
    opacity: 1;
  }

  :deep(.is-added td) {
    background: rgba(16, 185, 129, 0.06) !important;
  }

  :deep(.is-modified td) {
    background: rgba(245, 158, 11, 0.07) !important;
  }

  :deep(.is-deleted td) {
    background: rgba(239, 68, 68, 0.06) !important;
  }

  :deep(.is-deleted .el-input__inner) {
    text-decoration: line-through;
    color: #94a3b8;
  }
}

.attribute-footer {
  align-items: center;
  justify-content: space-between;

  > span {
    color: #64748b;
    font-size: 12px;
    font-weight: 560;
  }

  > div {
    display: inline-flex;
    gap: 10px;
  }
}

.dm-edit-panel .overview-title-row {
  flex-shrink: 0;
  align-items: flex-start;
  gap: 14px;
  padding-bottom: 14px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.08);
}

.dm-edit-panel .overview-title-row h2 {
  margin-top: 6px;
  font-size: 28px;
  line-height: 1.18;
  font-weight: 780;
}

.dm-edit-panel .overview-title-row :deep(.el-tag) {
  height: 34px;
  padding: 0 13px;
  border-radius: 9px;
  font-size: 14px;
  font-weight: 760;
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
  font-weight: 650;
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
  background: #ffffff !important;
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
  font-weight: 760 !important;
  white-space: nowrap;
}

.edit-operation-item strong {
  grid-area: title;
  min-width: 0;
  color: #111827;
  font-size: 15px;
  line-height: 1.45;
  font-weight: 760;
  overflow-wrap: anywhere;
}

.edit-operation-item p {
  grid-area: detail;
  min-width: 0;
  margin: 0;
  color: var(--dm-muted);
  font-size: 13px;
  line-height: 1.55;
  font-weight: 560;
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
  font-weight: 720;
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
  font-weight: 760;
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
</style>
