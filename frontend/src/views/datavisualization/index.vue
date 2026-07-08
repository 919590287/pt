<!-- index -->
<template>
  <Transition name="model-loading-fade">
    <div v-if="modelLoadingDialogVisible" class="model-loading-gate" role="status" aria-live="polite" aria-busy="true">
      <div class="model-loading-card" role="dialog" aria-modal="false" aria-label="模型后台加载提示">
        <button class="model-loading-close" type="button" title="关闭提示" aria-label="关闭模型加载提示" @click="dismissModelLoadingDialog">
          <el-icon><Close /></el-icon>
        </button>
        <div class="model-loading-title">模型后台加载</div>
        <div class="model-loading-message">{{ modelLoadingNotice }}</div>
      </div>
    </div>
  </Transition>

  <div class="datebase_box" role="search" aria-label="方案与模型选择">
    <div class="data-source-segment" role="group" aria-label="数据源类型">
      <button
        v-for="item in DATA_SOURCE_OPTIONS"
        :key="item.value"
        type="button"
        :class="{ active: dataSourceMode === item.value }"
        @click="dataSourceMode = item.value"
      >
        {{ item.label }}
      </button>
    </div>
    <label class="handle" for="scheme-selector">当前方案</label>
    <el-select id="scheme-selector" v-model="datebase.scheme" clearable filterable :loading="isLoadingSchemes" :disabled="!isSimulationMode" aria-label="当前方案">
      <el-option v-for="item in schemeList" :key="item" :label="item" :value="item"> </el-option>
    </el-select>
    <el-select class="model-select" v-model="modelPickerValue" :disabled="!isSimulationMode || !datebase.scheme || isLoadingModels" clearable filterable :loading="isLoadingModels" aria-label="选择模型" @change="handleModelPick">
      <el-option v-for="item in modelSelectOptions" :key="item.name" :label="getModelLabel(item)" :value="item.name">
        <div class="model-option">
          <div class="model-option-main">
            <span :title="item.name">{{ getModelLabel(item) }}</span>
            <el-tag v-if="item.scopeLabel" type="info">{{ item.scopeLabel }}</el-tag>
            <el-tag :type="modelLoadTagType(item)">{{ modelLoadLabel(item) }}</el-tag>
            <el-tag v-if="item.cacheStatus" :type="modelCacheTagType(item)">{{ modelCacheLabel(item) }}</el-tag>
          </div>
          <div class="model-option-actions">
            <el-button v-if="canStartModel(item)" link size="small" type="primary" :disabled="isModelOperationBusy" @mousedown.stop.prevent @click.stop.prevent="handleBackgroundLoad(item)">
              <el-icon><VideoPlay /></el-icon>
              <span>后台加载</span>
            </el-button>
            <el-button v-if="item.loadStatus || item.loadStage === 'loading_config' || item.loadStage === 'queued'" link size="small" type="danger" :disabled="isModelOperationBusy" @mousedown.stop.prevent @click.stop.prevent="handleUnloadModel(item)">
              <el-icon><Remove /></el-icon>
              <span>卸载</span>
            </el-button>
          </div>
        </div>
      </el-option>
    </el-select>
    <span v-if="loadError" class="load-error" role="status">{{ loadError }}</span>
  </div>

  <div v-if="backgroundTaskVisible" class="model-background-status" role="status" aria-live="polite">
    <div class="model-background-main">
      <span class="model-background-dot"></span>
      <span class="model-background-title">{{ backgroundTaskTitle }}</span>
      <span class="model-background-message">{{ backgroundTaskMessage }}</span>
    </div>
    <el-progress class="model-background-progress" :percentage="modelProgressPercent(backgroundTaskModel)" :show-text="false" :stroke-width="6" />
    <el-button v-if="backgroundSwitchOnReady" link size="small" @click="cancelPendingAutoSwitch">
      <el-icon><SwitchButton /></el-icon>
      <span>取消切换</span>
    </el-button>
  </div>

  <template v-if="selectModel">
    <template v-if="isModelReady">
      <div :class="['dm-sidebar', isRunMonitorLeftCollapsed ? 'is-collapsed' : '']">
        <div class="sidebar-brand">
          <svg class="brand-icon" viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
            <path d="M3 12h4l3-8 4 16 3-8h4"></path>
          </svg>
          <span class="brand-text">{{ props.mode === 'pfa' ? '客流分析' : '运行监测' }}</span>
        </div>

        <nav class="sidebar-nav" :aria-label="props.mode === 'pfa' ? '客流分析导航' : '运行监测导航'">
          <div v-for="item in displayMenuItems" :key="item.key" class="menu-group">
            <button
              type="button"
              :class="['nav-item', isNavItemActive(item) ? 'active' : '']"
              :aria-expanded="item.children ? pfaIsExpanded(item.key) : undefined"
              @click="handleNavItemClick(item)"
            >
              <span class="nav-icon" v-html="item.icon"></span>
              <span class="nav-label">{{ item.label }}</span>
              <span v-if="item.children" class="chevron-icon" :class="{ expanded: pfaIsExpanded(item.key) }">
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <polyline points="6 9 12 15 18 9"></polyline>
                </svg>
              </span>
            </button>

            <transition name="slide-fade">
              <div v-if="item.children && pfaIsExpanded(item.key)" class="sub-nav-list">
                <button
                  v-for="sub in item.children"
                  :key="sub.key"
                  type="button"
                  :class="['sub-nav-item', isPfaSubActive(item.key, sub.key) ? 'active' : '']"
                  @click.stop="handleNavSubClick(item, sub)"
                >
                  <span class="sub-dot"></span>
                  <span class="nav-label">{{ sub.label }}</span>
                </button>
              </div>
            </transition>
          </div>
        </nav>

        <div v-show="activeTab === '车辆运行监测'" id="run-monitor-vehicle-controls" class="rm-vehicle-controls"></div>
        <div class="sidebar-footer"></div>
      </div>

      <button
        type="button"
        :class="['dm-panel-collapse-tab', 'dm-left-collapse-tab', isRunMonitorLeftCollapsed ? 'is-collapsed' : '']"
        :title="(isRunMonitorLeftCollapsed ? '展开' : '收起') + (props.mode === 'pfa' ? '客流分析面板' : '运行监测面板')"
        :aria-label="(isRunMonitorLeftCollapsed ? '展开' : '收起') + (props.mode === 'pfa' ? '客流分析面板' : '运行监测面板')"
        :aria-pressed="isRunMonitorLeftCollapsed"
        @click="isRunMonitorLeftCollapsed = !isRunMonitorLeftCollapsed"
      >
        <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <polyline points="15 18 9 12 15 6"></polyline>
        </svg>
      </button>

      <!-- 右上角搜索框：与数据管理一致，按当前监测维度搜索线路 / 站点 -->
      <div
        v-if="showRunMonitorSearch"
        :class="['rm-search', { 'is-focused': isSearchFocused, 'is-left-collapsed': isRunMonitorLeftCollapsed }]"
        role="search"
        :aria-label="runMonitorSearchPlaceholder"
        @click.stop
      >
        <svg class="rm-search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="11" cy="11" r="8"></circle>
          <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
        </svg>
        <input
          v-model="runMonitorSearchKeyword"
          class="rm-search-input"
          type="search"
          :placeholder="runMonitorSearchPlaceholder"
          :aria-label="runMonitorSearchPlaceholder"
          @focus="handleRunMonitorSearchFocus"
          @input="handleRunMonitorSearchInput"
          @blur="handleRunMonitorSearchBlur"
          @keydown.enter.prevent="selectFirstRunMonitorResult"
          @keydown.esc.prevent="closeRunMonitorSearch"
        />
        <button v-if="runMonitorSearchKeyword" class="rm-search-clear" type="button" title="清空搜索" aria-label="清空搜索" @mousedown.prevent="clearRunMonitorSearch">
          <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
            <line x1="18" y1="6" x2="6" y2="18"></line>
            <line x1="6" y1="6" x2="18" y2="18"></line>
          </svg>
        </button>
        <Transition name="rm-search-fade">
          <div v-if="showRunMonitorSearchResults" class="rm-search-results" role="listbox">
            <button
              v-for="result in runMonitorSearchResults"
              :key="result.value"
              class="rm-search-result"
              type="button"
              role="option"
              @mousedown.prevent="selectRunMonitorResult(result)"
            >
              <span class="rm-result-icon" :class="runMonitorSearchType">
                <svg v-if="runMonitorSearchType === 'station'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"></path>
                  <circle cx="12" cy="10" r="3"></circle>
                </svg>
                <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                  <rect x="3" y="4" width="18" height="12" rx="2"></rect>
                  <circle cx="7" cy="10" r="1"></circle>
                  <circle cx="17" cy="10" r="1"></circle>
                  <path d="M6 16v2"></path>
                  <path d="M18 16v2"></path>
                </svg>
              </span>
              <span class="rm-result-meta">
                <span class="rm-result-name">{{ result.label }}</span>
                <span class="rm-result-type">{{ (baseMapLineMode === 'metro-network' ? '地铁' : '公交') + (runMonitorSearchType === 'line' ? '线路' : '站点') }}</span>
              </span>
            </button>
            <p v-if="!runMonitorSearchResults.length" class="rm-search-empty">未找到匹配项</p>
          </div>
        </Transition>
      </div>

      <!-- 线路选择弹窗：复用数据管理“点击路段弹出经过该路段的线路列表”交互 -->
      <div
        v-if="lineRoutePicker.visible"
        class="line-route-picker dm-route-picker"
        :style="{ left: `${lineRoutePicker.x}px`, top: `${lineRoutePicker.y}px` }"
        role="dialog"
        aria-label="选择经过该路段的线路"
        @click.stop
        @keydown.esc.stop.prevent="closeLineRoutePicker"
      >
        <div class="picker-title">选择经过该路段的线路</div>
        <button
          v-for="route in lineRoutePicker.routes"
          :key="route.id || route.name"
          :class="['picker-route-btn', isRouteOptionActive(route) ? 'active' : '']"
          type="button"
          :aria-pressed="isRouteOptionActive(route)"
          @click="selectLineFromPicker(route)"
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

      <!-- 监测组件宿主：仅承载数据加载 / 地图图层 / 右侧 teleport，自身界面隐藏，交互通过上方搜索框与地图完成 -->
      <div class="run-monitor-mount" aria-hidden="true">
        <XLZL v-if="activeTab == '线路客流监测'" ref="lineMonitorRef" :key="`xlzl-${selectModel.name}`" :model="selectModel.name" />
        <ZDZL v-else-if="activeTab == '站点客流监测'" ref="stationMonitorRef" :key="`zdzl-${selectModel.name}`" :model="selectModel.name" />
        <GJYS
          v-else-if="activeTab == '车辆运行监测'"
          :key="`gjys-${selectModel.name}`"
          :model="selectModel.name"
          :run-monitor-panels="true"
        />
        <TJFX v-else-if="activeTab == '体检评估分析'" :key="`tjfx-${selectModel.name}`" :model="selectModel.name" />
      </div>
      <button
        v-show="isRightPanelVisible"
        type="button"
        :class="['dm-panel-collapse-tab', 'dm-right-collapse-tab', isRightCollapsed ? 'is-collapsed' : '']"
        @click="toggleRightPanel"
        :aria-label="isRightCollapsed ? '展开右侧信息面板' : '折叠右侧信息面板'"
        :aria-expanded="!isRightCollapsed"
        aria-controls="right-info-panel"
        :title="isRightCollapsed ? '展开面板' : '折叠面板'"
      >
        <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <polyline points="9 18 15 12 9 6"></polyline>
        </svg>
      </button>

      <div id="right-info-panel" :class="['dm-overview-panel', 'run-monitor-right-panel', isRightCollapsed ? 'is-collapsed' : '']" v-show="isRightPanelVisible">
        <el-scrollbar class="flex_column_scroll_box">
          <div id="datavisualization_index_box2">
            <div v-if="activeTab === '总体客流变化'" class="rm-right-card overall-flow-card rm-compact-flow-card">
              <div class="rm-right-card-title">
                <div>
                  <h2>总体客流变化</h2>
                </div>
                <el-tag v-if="overallFlowLoading" type="info">加载中</el-tag>
                <el-tag v-else-if="overallFlowError" type="danger">加载失败</el-tag>
              </div>
              <div class="rm-overall-summary">
                <div class="rm-summary-item">
                  <span>总客流</span>
                  <strong>{{ formatOverallFlow(overallFlowTotal) }}</strong>
                </div>
                <div class="rm-summary-item">
                  <span>公交客流</span>
                  <strong>{{ formatOverallFlow(overallFlowBusTotal) }}</strong>
                </div>
                <div class="rm-summary-item">
                  <span>地铁客流</span>
                  <strong>{{ formatOverallFlow(overallFlowMetroTotal) }}</strong>
                </div>
              </div>
              <div v-if="overallFlowError" class="rm-panel-error">{{ overallFlowError }}</div>
              <template v-else>
                <div
                  class="rm-overall-chart rm-clickable-chart"
                  title="点击全屏查看"
                  @click="openOverallFlowFullscreen"
                >
                  <el-auto-resizer class="chart_box">
                    <template #default="{ height, width }">
                      <VChart
                        v-if="width > 0 && height > 0"
                        class="rm-chart"
                        :option="overallFlowChartOption"
                        autoresize
                        :update-options="{ notMerge: true }"
                      />
                    </template>
                  </el-auto-resizer>
                  <span class="rm-chart-zoom-hint">点击全屏</span>
                </div>
              </template>
            </div>

            <div v-else-if="activeTab === '线路客流监测' && !(props.mode === 'pfa' && selectedLineName)" class="rm-right-card line-flow-card">
              <template v-if="selectedLinePanel && props.mode !== 'pfa'">
                <div class="rm-right-card-title">
                  <div>
                    <h2>{{ selectedLineBaseName || '线路客流' }}</h2>
                  </div>
                </div>
                <div class="rm-overall-summary">
                  <div v-for="item in lineFlowSummaryItems" :key="item.label" class="rm-summary-item">
                    <span>{{ item.label }}</span>
                    <strong>{{ formatOverallFlow(item.value) }}</strong>
                  </div>
                </div>
                <div class="rm-line-kpi-grid">
                  <div v-for="item in lineOperationStats" :key="item.label" class="rm-line-kpi-item">
                    <span>{{ item.label }}</span>
                    <strong>{{ item.value }}</strong>
                  </div>
                </div>
                <div class="rm-overall-chart">
                  <el-auto-resizer class="chart_box">
                    <template #default="{ height, width }">
                      <VChart
                        v-if="width > 0 && height > 0"
                        class="rm-chart"
                        :option="lineFlowChartOption"
                        autoresize
                        :update-options="{ notMerge: true }"
                      />
                    </template>
                  </el-auto-resizer>
                </div>
              </template>
              <div v-else class="rm-panel-empty">
                <span class="rm-empty-icon">
                  <svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M4 17c4-5 8 5 16-1"></path>
                    <path d="M4 7c5 0 8 5 16 1"></path>
                    <circle cx="6" cy="17" r="2"></circle>
                    <circle cx="18" cy="8" r="2"></circle>
                  </svg>
                </span>
                <p class="rm-empty-text">点击地图上的线路，或使用搜索框选择线路</p>
              </div>
            </div>

            <div v-else-if="activeTab === '站点客流监测' && !(props.mode === 'pfa' && selectedStationName)" class="rm-right-card station-flow-card">
              <template v-if="selectedStationName && props.mode !== 'pfa'">
                <div class="rm-right-card-title">
                  <div>
                    <h2>{{ selectedStationName || '站点客流' }}</h2>
                  </div>
                  <el-tag v-if="stationPanelTagText" :type="stationPanelTagType">{{ stationPanelTagText }}</el-tag>
                </div>
                <div v-if="stationPanelStatus === 'error'" class="rm-panel-error">{{ stationPanelError || '站点客流数据加载失败' }}</div>
                <div v-else-if="stationPanelStatus === 'generating'" class="rm-panel-empty compact">站点客流缓存生成中，请稍后刷新。</div>
                <template v-if="!stationPanelUnavailable">
                <div class="rm-overall-summary">
                  <div class="rm-summary-item">
                    <span>{{ primaryStationSideLabel }}上下车人数</span>
                    <strong>{{ formatOverallFlow(stationFlowPrimaryTotal) }}</strong>
                  </div>
                  <div class="rm-summary-item">
                    <span>{{ reverseStationSideLabel }}上下车人数</span>
                    <strong>{{ formatOverallFlow(stationFlowReverseTotal) }}</strong>
                  </div>
                </div>
                <div class="rm-overall-chart">
                  <el-auto-resizer class="chart_box">
                    <template #default="{ height, width }">
                      <VChart
                        v-if="width > 0 && height > 0"
                        class="rm-chart"
                        :option="stationFlowChartOption"
                        autoresize
                        :update-options="{ notMerge: true }"
                      />
                    </template>
                  </el-auto-resizer>
                </div>
                </template>
              </template>
              <div v-else class="rm-panel-empty">
                <span class="rm-empty-icon">
                  <svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M12 21s7-5.2 7-11a7 7 0 1 0-14 0c0 5.8 7 11 7 11Z"></path>
                    <circle cx="12" cy="10" r="2.5"></circle>
                  </svg>
                </span>
                <p class="rm-empty-text">点击地图上的站点，或使用搜索框选择站点</p>
              </div>
            </div>

            <!-- 体检评估分析：右侧内容由 TJFX 组件 teleport 到本容器（评估指标表格 + 五维雷达图） -->
          </div>
        </el-scrollbar>
      </div>

      <!-- Floating Map Controls Toolbar -->
      <div 
        :class="['map-controls-toolbar', (isRightPanelVisible && !isRightCollapsed) ? 'with-panel' : 'without-panel']"
      >
        <!-- Block 1: Zoom & 3D & Compass -->
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

        <div v-if="showDisplayRangeControl" class="control-block">
          <button
            :class="['control-btn', selectedDisplayRange !== DISPLAY_RANGE_ALL || showRangePopover ? 'active' : '']"
            type="button"
            @click="toggleRangePopover"
            :title="displayRangeButtonTitle"
            :aria-label="displayRangeButtonAriaLabel"
            :aria-expanded="showRangePopover"
            aria-controls="rm-range-popover"
            aria-haspopup="dialog"
          >
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round">
              <path d="M3 6.5 8 4l8 2.5 5-2.5v13.5L16 20l-8-2.5-5 2.5V6.5Z"></path>
              <path d="M8 4v13.5"></path>
              <path d="M16 6.5V20"></path>
            </svg>
          </button>
        </div>

        <!-- Block 2: Line Settings Toggle & Floating Popover -->
        <div class="control-block settings-block">
          <button
            :class="['control-btn', showLineWidthPopover ? 'active' : '']"
            type="button"
            @click="handleToggleLineWidthPopover"
            :title="isVehicleMonitorTab ? '车辆模型设置' : '设置'"
            :aria-label="isVehicleMonitorTab ? '打开车辆模型设置' : '打开设置'"
            :aria-expanded="showLineWidthPopover"
            aria-controls="line-width-popover"
            aria-haspopup="dialog"
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

        <!-- Floating Popover for Line Width -->
        <Transition name="popover-fade">
          <div v-if="showLineWidthPopover" id="line-width-popover" class="line-width-popover" role="dialog" aria-modal="false" @click.stop>
            <div class="popover-title">{{ isVehicleMonitorTab ? '车辆模型设置' : '设置' }}</div>
            <div class="popover-content">
              <div class="slider-row" v-if="isVehicleMonitorTab">
                <span class="label">
                  <span>车辆模型</span>
                  <span class="val-text">{{ `${vehicleSize}px` }}</span>
                </span>
                <el-slider v-model="vehicleSize" :min="minVehicleSize" :max="maxVehicleSize" :step="1" @input="handleVehicleSizeChange" />
              </div>
              <template v-else-if="props.mode === 'pfa'">
                <!-- 客流分析：公交/地铁线网互斥切换（与运行监测同一套 baseMapLineMode） -->
                <div class="layer-mode-row">
                  <span>显示图层</span>
                  <div class="layer-mode-segment" role="group" aria-label="线网显示图层">
                    <button
                      type="button"
                      :class="{ active: baseMapLineMode === 'bus-network' }"
                      @click="handleBaseMapLineModeChange('bus-network')"
                    >
                      公交线网
                    </button>
                    <button
                      type="button"
                      :class="{ active: baseMapLineMode === 'metro-network' }"
                      @click="handleBaseMapLineModeChange('metro-network')"
                    >
                      地铁线网
                    </button>
                  </div>
                </div>
                <div class="slider-row">
                  <span class="label">
                    <span>线宽</span>
                    <span class="val-text">{{ `${lineWidth}px` }}</span>
                  </span>
                  <el-slider v-model="lineWidth" :min="minLineWidth" :max="maxLineWidth" :step="0.1" @input="handleLineWidthChange" />
                </div>
                <!-- 断面客流色阶/阈值设置已移至地图左下角图例面板的齿轮弹层 -->
                <!-- 透明度只作用于断面客流图层，站点客流分析下无断面层，不显示无效控件 -->
                <div v-if="effectiveTab === '线路客流监测'" class="slider-row">
                  <span class="label">
                    <span>透明度</span>
                    <span class="val-text">{{ `${pfaSegmentOpacity}%` }}</span>
                  </span>
                  <el-slider v-model="pfaSegmentOpacity" :min="0" :max="100" :step="1" @input="handlePfaSegmentOpacityChange" />
                </div>
                <!-- 站点客流分析：站点客流热力图开关（开启时隐藏路网/公交线网/站点） -->
                <div v-if="effectiveTab === '站点客流监测'" class="layer-mode-row">
                  <span>地图热力图</span>
                  <el-switch v-model="stationHeatmapEnabled" />
                </div>
              </template>
              <template v-else>
                <div class="layer-mode-row">
                  <span>显示图层</span>
                  <div class="layer-mode-segment" role="group" aria-label="线网显示图层">
                    <button
                      type="button"
                      :class="{ active: baseMapLineMode === 'bus-network' }"
                      @click="handleBaseMapLineModeChange('bus-network')"
                    >
                      公交线网
                    </button>
                    <button
                      type="button"
                      :class="{ active: baseMapLineMode === 'metro-network' }"
                      @click="handleBaseMapLineModeChange('metro-network')"
                    >
                      地铁线网
                    </button>
                    <button
                      type="button"
                      :class="{ active: baseMapLineMode === 'road-network' }"
                      @click="handleBaseMapLineModeChange('road-network')"
                    >
                      路网
                    </button>
                  </div>
                </div>
                <div class="slider-row">
                  <span class="label">
                    <span>线宽</span>
                    <span class="val-text">{{ `${lineWidth}px` }}</span>
                  </span>
                  <el-slider v-model="lineWidth" :min="minLineWidth" :max="maxLineWidth" :step="0.1" @input="handleLineWidthChange" />
                </div>
              </template>
              <div class="vehicle-visibility-row" v-if="isVehicleMonitorTab">
                <span>可视化范围</span>
                <el-select v-model="vehicleVisibilityMode" size="small" @change="handleVehicleVisibilityModeChange">
                  <el-option
                    v-for="item in vehicleVisibilityOptions"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
              </div>
            </div>
          </div>
        </Transition>

        <Transition name="popover-fade">
          <div v-if="showRangePopover" id="rm-range-popover" class="range-popover rm-range-popover" role="dialog" aria-modal="false" @click.stop @keydown.esc.stop.prevent="closeRangePopover">
            <div class="popover-title">选择行政区</div>
            <div v-if="isLoadingDisplayRanges" class="range-state">行政区加载中</div>
            <div v-else-if="displayRangeOptions.length" class="range-list" role="listbox" aria-label="行政区显示范围">
              <button
                v-for="item in displayRangeOptions"
                :key="item"
                :class="['range-option', selectedDisplayRange === item ? 'active' : '']"
                type="button"
                role="option"
                :aria-selected="selectedDisplayRange === item"
                @click="selectDisplayRange(item)"
              >
                <span class="range-option-name">{{ item }}</span>
                <svg v-if="selectedDisplayRange === item" class="range-option-check" viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                  <polyline points="20 6 9 17 4 12"></polyline>
                </svg>
              </button>
            </div>
            <p v-else class="range-state">暂无行政区范围</p>
            <p v-if="displayRangeError" class="range-error">{{ displayRangeError }}</p>
          </div>
        </Transition>
      </div>

      <!-- 需求2/11：地图左下角浮动图例（线路客流 / 断面客流 / 站点热力 / 客流OD，互斥出现） -->
      <div v-if="showLineFlowLegend || showMetroFlowLegend || showSegmentFlowLegend || showStationHeatLegend || showOdCurveLegend" class="map-flow-legend" @click.stop>
        <Transition name="popover-fade">
          <div
            v-if="showLineFlowScalePopover && (showLineFlowLegend || showMetroFlowLegend)"
            class="map-legend-popover"
            role="dialog"
            aria-modal="false"
            aria-label="线路客流色阶设置"
          >
            <div class="map-legend-popover-title">{{ showMetroFlowLegend ? '地铁客流色阶设置' : '线路客流色阶设置' }}</div>
            <ColorScaleControl
              v-model="lineFlowScale"
              :legend-title="showMetroFlowLegend ? '地铁客流' : '线路客流'"
              :show-legend="false"
            />
          </div>
        </Transition>
        <!-- 断面客流色阶/阈值设置（原设置弹层功能移到图例面板齿轮） -->
        <Transition name="popover-fade">
          <div
            v-if="showSegmentFlowScalePopover && showSegmentFlowLegend"
            class="map-legend-popover"
            role="dialog"
            aria-modal="false"
            aria-label="断面客流色阶设置"
          >
            <div class="map-legend-popover-title">
              断面客流色阶设置
              <span v-if="pfaSegmentMaxFlow > 0" class="map-legend-popover-tail">最大断面 {{ Math.round(pfaSegmentMaxFlow).toLocaleString() }} 人次</span>
            </div>
            <ColorScaleControl
              v-model="pfaSegmentScale"
              legend-title="断面客流"
              :show-legend="false"
            />
          </div>
        </Transition>
        <!-- 站点客流热力色阶/阈值设置 -->
        <Transition name="popover-fade">
          <div
            v-if="showStationHeatScalePopover && showStationHeatLegend"
            class="map-legend-popover"
            role="dialog"
            aria-modal="false"
            aria-label="站点客流热力色阶设置"
          >
            <div class="map-legend-popover-title">
              站点客流热力色阶设置
              <span v-if="stationHeatMaxFlow > 0" class="map-legend-popover-tail">最大站点 {{ Math.round(stationHeatMaxFlow).toLocaleString() }} 人次</span>
            </div>
            <ColorScaleControl
              v-model="stationHeatScale"
              legend-title="站点客流热力"
              :show-legend="false"
            />
          </div>
        </Transition>
        <!-- 客流OD曲线色阶/阈值设置 -->
        <Transition name="popover-fade">
          <div
            v-if="showOdCurveScalePopover && showOdCurveLegend"
            class="map-legend-popover"
            role="dialog"
            aria-modal="false"
            aria-label="客流OD曲线色阶设置"
          >
            <div class="map-legend-popover-title">
              客流OD曲线色阶设置
              <span v-if="odCurveMaxFlow > 0" class="map-legend-popover-tail">最大OD {{ Math.round(odCurveMaxFlow).toLocaleString() }} 人次</span>
            </div>
            <ColorScaleControl
              v-model="odCurveScaleConfig"
              legend-title="客流OD"
              :show-legend="false"
            />
          </div>
        </Transition>
        <div class="map-legend-card">
          <div class="map-legend-head">
            <span class="map-legend-title">{{ showOdCurveLegend ? '客流OD（人次）' : showStationHeatLegend ? '站点客流热力（人次/日）' : showSegmentFlowLegend ? '断面客流（人次）' : showMetroFlowLegend ? '地铁客流（人次/日）' : '线路客流（人次/日）' }}</span>
            <button
              type="button"
              class="map-legend-gear"
              :title="showOdCurveLegend ? '设置客流OD曲线色阶与阈值' : showStationHeatLegend ? '设置站点客流热力色阶与阈值' : showSegmentFlowLegend ? '设置断面客流色阶与阈值' : '设置线路客流色阶'"
              :aria-label="showOdCurveLegend ? '设置客流OD曲线色阶与阈值' : showStationHeatLegend ? '设置站点客流热力色阶与阈值' : showSegmentFlowLegend ? '设置断面客流色阶与阈值' : '设置线路客流色阶'"
              :aria-expanded="showOdCurveLegend ? showOdCurveScalePopover : showStationHeatLegend ? showStationHeatScalePopover : showSegmentFlowLegend ? showSegmentFlowScalePopover : showLineFlowScalePopover"
              @click="showOdCurveLegend
                ? (showOdCurveScalePopover = !showOdCurveScalePopover)
                : showStationHeatLegend
                  ? (showStationHeatScalePopover = !showStationHeatScalePopover)
                  : showSegmentFlowLegend
                    ? (showSegmentFlowScalePopover = !showSegmentFlowScalePopover)
                    : (showLineFlowScalePopover = !showLineFlowScalePopover)"
            >
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <circle cx="12" cy="12" r="3"></circle>
                <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h.01a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h.01a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v.01a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
              </svg>
            </button>
          </div>
          <div v-for="(item, index) in activeMapLegendItems" :key="index" class="map-legend-item">
            <span class="map-legend-swatch" :style="{ background: item.color, height: legendSwatchHeight(item.width) }"></span>
            <span class="map-legend-label">{{ item.label }}</span>
          </div>
        </div>
      </div>
    </template>
    <div v-else ref="box1" class="model_box box1 cache-loading-panel" :style="box1Style">
      <div class="cache-loading-title">{{ cacheLoadingTitle }}</div>
      <div class="cache-loading-message">{{ cacheLoadingMessage }}</div>
      <el-progress
        class="cache-loading-progress"
        :percentage="cacheProgressPercent"
        :status="selectModel.cacheStatus === 'failed' || selectModel.loadStage === 'failed' ? 'exception' : undefined"
        :stroke-width="12"
      />
      <div class="cache-loading-meta">
        <span>已用 {{ formatDuration(cacheElapsedSeconds) }}</span>
        <span>预计剩余 {{ formatDuration(cacheEtaSeconds) }}</span>
      </div>
    </div>
  </template>
  <div v-else ref="box1" class="model_box box1" :style="box1Style">
    <el-empty :description="isSimulationMode ? '请选择模型' : '真实数据暂未接入'" />
  </div>

  <el-dialog
    v-model="overallFlowFullscreenVisible"
    class="overall-flow-fullscreen-dialog"
    fullscreen
    append-to-body
    destroy-on-close
    :lock-scroll="true"
  >
    <template #header>
      <div class="overall-flow-fullscreen-header">
        <div>
          <div class="overall-flow-fullscreen-kicker">运行监测</div>
          <div class="overall-flow-fullscreen-title">总体客流变化</div>
        </div>
        <div class="overall-flow-fullscreen-meta">
          <span>总客流 {{ formatOverallFlow(overallFlowTotal) }}</span>
          <span>公交 {{ formatOverallFlow(overallFlowBusTotal) }}</span>
          <span>地铁 {{ formatOverallFlow(overallFlowMetroTotal) }}</span>
        </div>
      </div>
    </template>
    <div class="overall-flow-fullscreen-body">
      <VChart
        class="overall-flow-fullscreen-chart"
        :option="overallFlowFullscreenChartOption"
        autoresize
        :update-options="{ notMerge: true }"
      />
    </div>
  </el-dialog>
</template>

<script setup>
import { defineAsyncComponent, getCurrentInstance } from "vue";
import { Close, Remove, SwitchButton, VideoPlay } from "@element-plus/icons-vue";
import { ElMessage, ElMessageBox } from "@/plugins/element-plus";
import { getSchemeList, getModelList, loadModel, unloadModel } from "@/api/scheme.js";
import { dataCenter } from "@/api/data.js";
import { getOverallFlow, getRouteCandidates, getRouteDetail, getRouteTileBinary } from "@/api/route.js";
import { useModelSelectionStore } from "@/stores/modelSelection.js";
import { abortOtherModelDataRequests, getCachedFacilityAll, getCachedLineAll, getCachedRoutePanel, getCachedStationPanel, peekCachedRoutePanel, warmModelInteractionCache } from "@/utils/modelDataCache.js";
import { createDebouncedMirror, runWhenIdle } from "./utils/panelShared.js";
import { lngLatToWebMercator, webMercatorToLngLat } from "@/mymap/index.js";
import { getCachedAdminDistricts } from "@/utils/realDataCache.js";
import {
  activeDistrictContext,
  clipLineStringToDistrictContext,
  districtNamesFromCollection,
  emptyFeatureCollection as emptyDistrictFeatureCollection,
  normalizeAdminDistrictCollection,
  pointInDistrictContext,
  segmentIntersectsDistrictContext,
} from "@/utils/adminDistrictRange.js";
import { NetworkLayer } from "./layers/NetworkLayer.js";
import { RouteLayer } from "./layers/RouteLayer.js";
import ColorScaleControl from "./components/ColorScaleControl.vue";
import { buildValueLegendItems, classifyByBreaks, createColorScaleConfig, quantileBreaks, resolveColorScale, sortFlowValues } from "@/utils/colorSchemes.js";
import { PURE_METRO_LINE, isMetroLine, metroLineCanonicalName } from "@/utils/transitMode.js";
import { MAP_THEME, hexNumber } from "@/utils/mapTheme.js";
import busStationIconUrl from "@/assets/images/datamanagement/bus-station.svg?url";
import metroStationIconUrl from "@/assets/images/datamanagement/metro-station.svg?url";
import busStationHighlightIconUrl from "@/assets/images/datamanagement/bus-station_highlight.svg?url";
import busStationHighlightReverseIconUrl from "@/assets/images/datamanagement/bus-station_highlight_reverse.svg?url";
import "../datamanagement/tokens.css";

import { useDraggable } from "@vueuse/core";

// mode: "monitor" = 运行监测（默认）；"pfa" = 客流分析（复用本视图）
const props = defineProps({
  mode: { type: String, default: "monitor" },
});
// 客流分析模式下，让 XLZL/ZDZL 把完整 MCard2 面板 teleport 到右侧（运行监测仍用简化卡片）
provide("pfaRightPanel", computed(() => props.mode === "pfa"));

const GJYS = defineAsyncComponent(() => import("./components/GJYS.vue"));
const XLZL = defineAsyncComponent(() => import("./components/XLZL.vue"));
const ZDZL = defineAsyncComponent(() => import("./components/ZDZL.vue"));
const TJFX = defineAsyncComponent(() => import("./components/TJFX.vue"));

const LEFT_PANEL_SCALE = 0.86;
const LEFT_PANEL_EDGE_X = 0;
const LEFT_PANEL_EXPANDED_X = 16;
const LEFT_PANEL_MIN_TOP = 67;
const box1Ref = useTemplateRef("box1");

const { style: box1Style, x: box1X, y: box1Y } = useDraggable(box1Ref, {
  initialValue: { x: LEFT_PANEL_EXPANDED_X, y: 120 },
  disabled: true,
});

let leftPanelResizeObserver = null;

function centerLeftPanel() {
  if (typeof window === "undefined") return;
  nextTick(() => {
    const box = box1Ref.value;
    if (!box) return;

    const maxLayoutHeight = (window.innerHeight - 150) / LEFT_PANEL_SCALE;
    const visualHeight = Math.min(box.offsetHeight, maxLayoutHeight) * LEFT_PANEL_SCALE;
    box1Y.value = Math.max(LEFT_PANEL_MIN_TOP, (window.innerHeight - visualHeight) / 2);
  });
}

function observeLeftPanelSize() {
  if (typeof window === "undefined" || typeof ResizeObserver === "undefined") return;
  nextTick(() => {
    const box = box1Ref.value;
    if (!box) return;

    leftPanelResizeObserver?.disconnect();
    leftPanelResizeObserver = new ResizeObserver(() => centerLeftPanel());
    leftPanelResizeObserver.observe(box);
    centerLeftPanel();
  });
}

const MODEL_SELECTION_KEY = "datavisualization";
const DATA_SOURCE_OPTIONS = [
  { value: "simulation", label: "仿真" },
  { value: "real", label: "真实" },
];
const modelSelectionStore = useModelSelectionStore();
const restoredSelection = modelSelectionStore.getSelection(MODEL_SELECTION_KEY);
let isRestoringSelection = Boolean(restoredSelection.scheme);
const dataSourceMode = ref(restoredSelection.sourceMode || "simulation");
const isSimulationMode = computed(() => dataSourceMode.value === "simulation");
const datebase = ref({
  scheme: restoredSelection.scheme,
  model: restoredSelection.model,
});
const modelPickerValue = ref(restoredSelection.model);
const schemeList = ref([]);
const modelList = ref([]);
const isLoadingSchemes = ref(false);
const isLoadingModels = ref(false);
const isModelOperationBusy = ref(false);
const loadError = ref("");
const initialModelBootstrap = ref(true);
const backgroundModelName = ref("");
const backgroundSwitchOnReady = ref(false);
const modelLoadingDismissed = ref(false);
let schemeRequestSeq = 0;
let modelRequestSeq = 0;
let centerRequestSeq = 0;
let modelLoadSeq = 0;
let backgroundTaskSeq = 0;
let interactionCacheSeq = 0;
const selectModel = computed(() => {
  if (!isSimulationMode.value) return null;
  const item = modelList.value?.find((item) => item.name === datebase.value.model);
  if (item) return item;
  if (datebase.value.model && initialModelBootstrap.value) {
    return {
      name: datebase.value.model,
      label: datebase.value.model,
      loadStatus: true,
      cacheStatus: "ready",
      __optimistic: true,
    };
  }
  return null;
});
const modelSelectOptions = computed(() => {
  const list = Array.isArray(modelList.value) ? modelList.value : [];
  const currentName = String(datebase.value.model || "");
  if (!currentName || list.some((item) => item?.name === currentName)) return list;
  return [
    {
      name: currentName,
      displayName: compactModelLabel(currentName),
      loadStatus: true,
      cacheStatus: "ready",
      __selectedFallback: true,
    },
    ...list,
  ];
});
const backgroundTaskModel = computed(() => modelList.value?.find((item) => item.name === backgroundModelName.value));
const isModelReadyForView = (item) => Boolean(item?.loadStatus && item?.cacheStatus === "ready" && !item?.__optimistic && !item?.__selectedFallback);
const isBackendModelReady = computed(() => isModelReadyForView(selectModel.value));
const interactionCacheModel = ref("");
const interactionCacheStatus = ref("idle");
const interactionCacheMessage = ref("");
const interactionCacheError = ref("");
const isInteractionCacheReady = computed(() => (
  !selectModel.value?.name
  || (interactionCacheModel.value === selectModel.value.name && interactionCacheStatus.value === "ready")
));
const isInteractionCacheLoading = computed(() => (
  isBackendModelReady.value
  && Boolean(selectModel.value?.name)
  && !isInteractionCacheReady.value
));
const isModelReady = computed(() => isBackendModelReady.value);
const backgroundTaskVisible = computed(() => Boolean(
  isSimulationMode.value
  && backgroundTaskModel.value
  && backgroundTaskModel.value.name !== datebase.value.model
  && !isModelReadyForView(backgroundTaskModel.value),
));
const fullScreenLoadingVisible = computed(() => (
  isSimulationMode.value
  && !isModelReady.value
  && (initialModelBootstrap.value || isLoadingSchemes.value || isLoadingModels.value || Boolean(selectModel.value))
));
const modelLoadingDialogVisible = computed(() => fullScreenLoadingVisible.value && !modelLoadingDismissed.value);
const modelLoadingNotice = computed(() => {
  if (!selectModel.value) return "模型状态正在检查，请稍后。";
  if (isInteractionCacheLoading.value) return `“${getModelLabel(selectModel.value)}”正在预热客流交互缓存，请稍后。`;
  return `“${getModelLabel(selectModel.value)}”开始后台加载，请稍后。`;
});
const modelLoadingKey = computed(() => `${dataSourceMode.value}:${datebase.value.scheme || ""}:${selectModel.value?.name || ""}:${selectModel.value?.loadStage || ""}:${selectModel.value?.cacheStatus || ""}`);
const cacheProgressPercent = computed(() => modelProgressPercent(selectModel.value));
const backgroundTaskTitle = computed(() => {
  const item = backgroundTaskModel.value;
  if (!item) return "";
  const prefix = backgroundSwitchOnReady.value ? "加载完成后切换" : "后台加载";
  return `${prefix}：${getModelLabel(item)}`;
});
const backgroundTaskMessage = computed(() => modelProgressMessage(backgroundTaskModel.value));

function modelProgressPercent(item) {
  if (isInteractionCacheLoading.value) return 96;
  if (isModelReadyForView(item)) return 100;
  if (item?.loadStage === "loading_config") return item?.cacheStatus === "ready" ? 35 : 12;
  if (item?.loadStage === "queued") return 8;
  const value = Number(item?.cacheProgressPercent);
  if (Number.isFinite(value)) return Math.max(0, Math.min(100, Math.round(value)));
  if (item?.cacheStatus === "ready") return 85;
  if (item?.loadStatus) return 20;
  return 5;
}

function modelProgressMessage(item) {
  return item?.cacheProgressMessage
    || item?.cacheMessage
    || item?.loadMessage
    || "模型准备中";
}
const cacheElapsedSeconds = computed(() => Math.max(0, Number(selectModel.value?.cacheElapsedSeconds) || 0));
const cacheEtaSeconds = computed(() => Number(selectModel.value?.cacheEtaSeconds ?? -1));
const cacheLoadingTitle = computed(() => {
  if (!selectModel.value) return "正在检查模型缓存";
  if (selectModel.value?.loadStage === "failed" || selectModel.value?.cacheStatus === "failed") return "加载失败";
  if (isInteractionCacheLoading.value) return "正在加载客流交互缓存";
  if (!selectModel.value?.loadStatus) return "正在加载模型基础数据";
  return "正在生成模型缓存";
});
const cacheLoadingMessage = computed(() => (
  (isInteractionCacheLoading.value ? (interactionCacheMessage.value || "正在预热线路、站点、客流与体检评估数据") : "")
  || (!selectModel.value && isLoadingSchemes.value ? "正在读取可用方案" : "")
  || (!selectModel.value && isLoadingModels.value ? "正在读取模型列表" : "")
  || interactionCacheError.value
  || selectModel.value?.cacheProgressMessage
  || selectModel.value?.cacheMessage
  || selectModel.value?.loadMessage
  || "模型准备中，请稍等"
));

function formatDuration(seconds) {
  const value = Number(seconds);
  if (!Number.isFinite(value) || value < 0) return "计算中";
  const total = Math.round(value);
  if (total < 60) return `${total} 秒`;
  const minutes = Math.floor(total / 60);
  const rest = total % 60;
  if (minutes < 60) return rest > 0 ? `${minutes} 分 ${rest} 秒` : `${minutes} 分`;
  const hours = Math.floor(minutes / 60);
  const minuteRest = minutes % 60;
  return minuteRest > 0 ? `${hours} 小时 ${minuteRest} 分` : `${hours} 小时`;
}

function getModelLabel(item) {
  return compactModelLabel(item?.displayName || item?.name || "");
}

function compactModelLabel(value = "") {
  const text = String(value || "").trim();
  if (!text) return "";
  const normalized = text.replace(/\\/g, "/");
  const parts = normalized.split("/").filter(Boolean);
  return parts[parts.length - 1] || text;
}

function modelLoadLabel(item) {
  if (item?.loadStatus) return "已加载";
  if (item?.loadStage === "queued") return "排队中";
  if (item?.loadStage === "loading_config") return "加载中";
  if (item?.loadStage === "failed") return "加载失败";
  return "未加载";
}

function modelLoadTagType(item) {
  if (item?.loadStatus) return "success";
  if (item?.loadStage === "failed") return "danger";
  if (item?.loadStage === "queued" || item?.loadStage === "loading_config") return "info";
  return "warning";
}

function modelCacheLabel(item) {
  if (item?.cacheStatus === "ready") return "缓存就绪";
  if (item?.cacheStatus === "queued") return "缓存排队";
  if (item?.cacheStatus === "building") return "缓存生成";
  if (item?.cacheStatus === "failed") return "缓存失败";
  return "缓存待生成";
}

function modelCacheTagType(item) {
  if (item?.cacheStatus === "ready") return "success";
  if (item?.cacheStatus === "failed") return "danger";
  if (item?.cacheStatus === "queued" || item?.cacheStatus === "building") return "info";
  return "warning";
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function setActiveModel(name = "") {
  datebase.value.model = name;
  modelPickerValue.value = name;
}

function dismissModelLoadingDialog() {
  modelLoadingDismissed.value = true;
}

function canStartModel(item) {
  if (!item || isModelReadyForView(item)) return false;
  return item.loadStage !== "queued" && item.loadStage !== "loading_config" && item.cacheStatus !== "queued" && item.cacheStatus !== "building";
}

async function ensureInteractionCacheForModel(modelName) {
  const key = String(modelName || "");
  if (!key) {
    interactionCacheModel.value = "";
    interactionCacheStatus.value = "idle";
    interactionCacheMessage.value = "";
    interactionCacheError.value = "";
    return;
  }
  if (interactionCacheModel.value === key && interactionCacheStatus.value === "ready") return;
  const seq = ++interactionCacheSeq;
  interactionCacheModel.value = key;
  interactionCacheStatus.value = "loading";
  interactionCacheError.value = "";
  interactionCacheMessage.value = "正在预热线路、站点、客流与体检评估数据";
  try {
    await warmModelInteractionCache(key, { includeStationPanel: true, includeEvaluation: true });
    if (seq !== interactionCacheSeq || datebase.value.model !== key) return;
    interactionCacheStatus.value = "ready";
    interactionCacheMessage.value = "客流交互缓存已就绪";
  } catch (error) {
    if (seq !== interactionCacheSeq || datebase.value.model !== key) return;
    interactionCacheStatus.value = "ready";
    interactionCacheError.value = error?.message || "客流交互缓存预热失败，已降级为按需加载";
  }
}

function pickReadyModel(list, excludedName = "") {
  return (Array.isArray(list) ? list : []).find((item) => item.name !== excludedName && isModelReadyForView(item)) || null;
}

function clearBackgroundTask(name = "") {
  if (!name || backgroundModelName.value === name) {
    backgroundModelName.value = "";
    backgroundSwitchOnReady.value = false;
  }
}

async function handleModelPick(name) {
  if (!name) {
    setActiveModel("");
    return;
  }
  const item = modelList.value.find((model) => model.name === name);
  if (!item) return;
  if (isModelReadyForView(item)) {
    setActiveModel(name);
    clearBackgroundTask(name);
    return;
  }
  modelPickerValue.value = datebase.value.model;
  await startBackgroundModelLoad(item, true);
}

async function handleBackgroundLoad(item) {
  modelPickerValue.value = datebase.value.model;
  await startBackgroundModelLoad(item, false);
}

async function startBackgroundModelLoad(item, switchOnReady) {
  if (!item?.name) return;
  if (isModelReadyForView(item)) {
    if (switchOnReady) setActiveModel(item.name);
    return;
  }
  const seq = ++backgroundTaskSeq;
  backgroundModelName.value = item.name;
  backgroundSwitchOnReady.value = switchOnReady;
  isModelOperationBusy.value = true;
  loadError.value = "";
  try {
    await loadModel({ name: item.name }, { silentError: true });
  } catch (error) {
    if (seq === backgroundTaskSeq) {
      clearBackgroundTask(item.name);
      loadError.value = error?.message || "模型后台加载失败";
    }
    return;
  } finally {
    if (seq === backgroundTaskSeq) {
      isModelOperationBusy.value = false;
    }
  }

  try {
    const readyItem = await waitForModelReady(item.name, () => seq === backgroundTaskSeq && backgroundModelName.value === item.name, { publishProgress: false });
    if (!readyItem || seq !== backgroundTaskSeq) return;
    await warmModelInteractionCache(item.name, { includeStationPanel: true, includeEvaluation: true });
    if (seq !== backgroundTaskSeq) return;
    if (backgroundSwitchOnReady.value) {
      setActiveModel(item.name);
      ElMessage.success("模型已加载完成，已切换");
    } else {
      ElMessage.success("模型已在后台加载完成");
    }
    clearBackgroundTask(item.name);
  } catch (error) {
    if (seq === backgroundTaskSeq) {
      loadError.value = error?.message || "模型后台加载失败";
    }
  }
}

function cancelPendingAutoSwitch() {
  backgroundSwitchOnReady.value = false;
}

async function handleUnloadModel(item) {
  if (!item?.name) return;
  const isActive = item.name === datebase.value.model;
  try {
    await ElMessageBox.confirm(
      isActive ? "卸载当前模型后，将自动切换到其他已就绪模型；如果没有可用模型，当前页面会进入待选择状态。" : `确定卸载“${getModelLabel(item)}”吗？`,
      "卸载模型",
      { confirmButtonText: "卸载", cancelButtonText: "取消", type: "warning" },
    );
  } catch {
    return;
  }
  isModelOperationBusy.value = true;
  try {
    await unloadModel({ name: item.name }, { silentError: true });
    const list = await handleGetModelList({ silent: true });
    if (isActive) {
      const fallback = pickReadyModel(list, item.name);
      setActiveModel(fallback?.name || "");
    }
    clearBackgroundTask(item.name);
    ElMessage.success("模型已卸载");
  } catch (error) {
    loadError.value = error?.message || "模型卸载失败";
  } finally {
    isModelOperationBusy.value = false;
  }
}

watch(
  () => datebase.value.scheme,
  async (scheme) => {
    if (!isSimulationMode.value) return;
    const restoringModel = isRestoringSelection && scheme === restoredSelection.scheme ? restoredSelection.model : "";
    setActiveModel("");
    clearBackgroundTask();
    modelList.value = [];
    if (!scheme) return;
    const list = await handleGetModelList();
    if (list.length && !datebase.value.model) {
      const preferred = list.find((item) => item.name === restoringModel) || pickReadyModel(list) || list[0];
      setActiveModel(preferred.name);
    }
    isRestoringSelection = false;
  },
);
async function handleGetSchemeList(options = {}) {
  const { silent = false, autoSelect = false } = options;
  const seq = ++schemeRequestSeq;
  if (!silent) {
    isLoadingSchemes.value = true;
    loadError.value = "";
  }
  try {
    const res = await getSchemeList(undefined, { silentError: silent });
    if (seq !== schemeRequestSeq) return schemeList.value;
    const list = Array.isArray(res?.data) ? res.data : [];
    schemeList.value = list;
    if (autoSelect && !datebase.value.scheme && list.length) {
      datebase.value.scheme = list[0];
    } else if (datebase.value.scheme && !list.includes(datebase.value.scheme)) {
      datebase.value.scheme = list[0] || "";
    }
    if (!silent && !list.length) {
      loadError.value = "暂无可用方案";
    }
    return list;
  } catch (error) {
    if (seq === schemeRequestSeq && !silent) {
      loadError.value = error?.message || "方案列表加载失败，请检查后端服务";
    }
    return [];
  } finally {
    if (seq === schemeRequestSeq && !silent) {
      isLoadingSchemes.value = false;
    }
  }
}

watch(
  () => datebase.value.model,
  ensureSelectedModelReady,
);
watch(
  [() => selectModel.value?.name, isBackendModelReady],
  ([modelName]) => {
    if (!modelName || !isBackendModelReady.value) {
      interactionCacheModel.value = modelName || "";
      interactionCacheStatus.value = modelName ? "idle" : "ready";
      interactionCacheMessage.value = "";
      interactionCacheError.value = "";
      return;
    }
    ensureInteractionCacheForModel(modelName);
  },
  { immediate: true },
);
watch(
  [dataSourceMode, () => datebase.value.scheme, () => datebase.value.model],
  () => {
    modelSelectionStore.setSelection(MODEL_SELECTION_KEY, {
      sourceMode: dataSourceMode.value,
      scheme: datebase.value.scheme,
      model: datebase.value.model,
    });
  },
);
watch(modelLoadingKey, () => {
  modelLoadingDismissed.value = false;
});

watch(dataSourceMode, async (mode) => {
  closeLineRoutePicker();
  clearLineSelection();
  clearStationSelection();
  runMonitorSearchKeyword.value = "";
  if (mode !== "simulation") {
    loadError.value = "";
    modelLoadingDismissed.value = true;
    return;
  }
  modelLoadingDismissed.value = false;
  if (!schemeList.value.length) {
    await handleGetSchemeList({ autoSelect: true });
  } else if (datebase.value.scheme && !modelList.value.length) {
    await handleGetModelList();
  }
  await ensureSelectedModelReady();
});

async function ensureSelectedModelReady() {
  if (!isSimulationMode.value) return;
  modelPickerValue.value = datebase.value.model || "";
  if (!datebase.value.model) return;
  const seq = ++modelLoadSeq;
  try {
    if (selectModel.value && !isBackendModelReady.value) {
      await loadModel({ name: datebase.value.model });
      await waitForModelReady(datebase.value.model, () => seq === modelLoadSeq && datebase.value.model === selectModel.value?.name);
    }
  } catch (error) {
    loadError.value = error?.message || "模型加载失败，请稍后重试";
  } finally {
    if (seq === modelLoadSeq && isModelReady.value) {
      setMapCenter();
    }
  }
}
const MapRef = inject("MapRef");
watch(MapRef, setMapCenter);
async function setMapCenter() {
  const seq = ++centerRequestSeq;
  if (selectModel.value && selectModel.value.name && isModelReady.value) {
    try {
      const res = await dataCenter({ datasource: selectModel.value.name }, { silentError: true });
      if (seq !== centerRequestSeq) return;
      const x = Number(res?.data?.x);
      const y = Number(res?.data?.y);
      if (Number.isFinite(x) && Number.isFinite(y)) {
        MapRef.value?.setCenter([x, y]);
      }
    } catch (error) {
      loadError.value = error?.message || "地图中心点加载失败";
    }
  }
}

// waitForModelReady 轮询去重：多个等待方（startBackgroundModelLoad / ensureSelectedModelReady）
// 可能同时各跑一条轮询循环。循环本身保留（各自的 shouldContinue 终止语义不同），
// 但 500ms 窗口内的 getModelList 请求复用同一个共享 in-flight promise，避免同秒打 2 次列表接口。
let sharedModelListPromise = null;
let sharedModelListFetchedAt = 0;
const MODEL_LIST_DEDUP_WINDOW_MS = 500;

function fetchModelListShared() {
  const now = Date.now();
  if (sharedModelListPromise && now - sharedModelListFetchedAt < MODEL_LIST_DEDUP_WINDOW_MS) {
    return sharedModelListPromise;
  }
  sharedModelListFetchedAt = now;
  sharedModelListPromise = handleGetModelList({ silent: true });
  return sharedModelListPromise;
}

// 轮询间隔按次数退避：前 10 次 1s，之后 2s，30 次后 5s。
function modelReadyPollDelay(attempt) {
  if (attempt < 10) return 1000;
  if (attempt < 30) return 2000;
  return 5000;
}

async function waitForModelReady(modelName, shouldContinue, options = {}) {
  const { publishProgress = true } = options;
  // 总等待预算与原实现一致（21600 次 × 1s = 6 小时），改为按真实耗时计。
  const POLL_BUDGET_MS = 21600 * 1000;
  const startedAt = Date.now();
  for (let attempt = 0; Date.now() - startedAt < POLL_BUDGET_MS; attempt++) {
    if (!shouldContinue()) return null;
    const list = await fetchModelListShared();
    const item = list.find((model) => model.name === modelName);
    if (!item) return null;
    if (item.loadStatus && item.cacheStatus === "ready") {
      loadError.value = "";
      return item;
    }
    if (item.loadStage === "failed") {
      throw new Error(item.loadMessage || "模型加载失败");
    }
    if (item.cacheStatus === "failed") {
      throw new Error(item.cacheMessage || "缓存生成失败");
    }
    if (publishProgress) {
      loadError.value = item.cacheProgressMessage || item.cacheMessage || item.loadMessage || "模型正在后台准备";
    }
    await sleep(modelReadyPollDelay(attempt));
  }
  throw new Error("模型缓存仍在后台生成，请稍后查看");
}

async function handleGetModelList(options = {}) {
  const { silent = false } = options;
  if (!datebase.value.scheme) {
    modelList.value = [];
    return [];
  }
  const seq = ++modelRequestSeq;
  if (!silent) {
    isLoadingModels.value = true;
    loadError.value = "";
  }
  try {
    const res = await getModelList({ schemeName: datebase.value.scheme }, { silentError: silent });
    if (seq !== modelRequestSeq) return modelList.value;
    const list = Array.isArray(res?.data) ? res.data : [];
    modelList.value = list;
    if (datebase.value.model && !list.some((item) => item.name === datebase.value.model)) {
      const fallback = pickReadyModel(list);
      setActiveModel(fallback?.name || "");
    }
    if (backgroundModelName.value && !list.some((item) => item.name === backgroundModelName.value)) {
      clearBackgroundTask(backgroundModelName.value);
    }
    if (!silent && !list.length) {
      loadError.value = "当前方案暂无可用模型";
    }
    return list;
  } catch (error) {
    if (seq === modelRequestSeq && !silent) {
      loadError.value = error?.message || "模型列表加载失败，请检查后端服务";
    }
    return [];
  } finally {
    if (seq === modelRequestSeq && !silent) {
      isLoadingModels.value = false;
    }
  }
}

const runMonitorMenuItems = [
  {
    key: "总体客流变化",
    label: "总体客流变化",
    icon: `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 3v18h18"></path><path d="m7 15 4-4 3 3 5-7"></path></svg>`,
  },
  {
    key: "线路客流监测",
    label: "线路客流监测",
    icon: `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 17c4-5 8 5 16-1"></path><path d="M4 7c5 0 8 5 16 1"></path><circle cx="6" cy="17" r="2"></circle><circle cx="18" cy="8" r="2"></circle></svg>`,
  },
  {
    key: "站点客流监测",
    label: "站点客流监测",
    icon: `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 21s7-5.2 7-11a7 7 0 1 0-14 0c0 5.8 7 11 7 11Z"></path><circle cx="12" cy="10" r="2.5"></circle></svg>`,
  },
  {
    key: "车辆运行监测",
    label: "车辆运行监测",
    icon: `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="5" y="4" width="14" height="12" rx="2"></rect><path d="M7 16v2"></path><path d="M17 16v2"></path><circle cx="8.5" cy="11" r="1"></circle><circle cx="15.5" cy="11" r="1"></circle><path d="M8 7h8"></path></svg>`,
  },
  {
    key: "体检评估分析",
    label: "体检评估分析",
    icon: `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 3h6l1 3h4v15H4V6h4l1-3Z"></path><path d="M9 14h2l2-4 2 7 2-3h2"></path></svg>`,
  },
];

const activeTab = ref(props.mode === "pfa" ? "线路客流监测" : "总体客流变化");
const isRunMonitorLeftCollapsed = ref(false);

// 客流分析：父级在左侧切换，右侧由对应组件只显示当前模块
const PFA_LINE_SECTIONS = [
  { key: "segments", label: "线路断面客流" },
  { key: "boarding", label: "站点乘降" },
  { key: "demographics", label: "客流画像" },
  { key: "transfer", label: "关联线路" },
];
const PFA_STATION_SECTIONS = [
  { key: "boarding", label: "站点乘降" },
  { key: "od", label: "客流OD" },
  { key: "demographics", label: "客流画像" },
  { key: "reachability", label: "可达性" },
];
const pfaLineSection = ref("segments");
const pfaStationSection = ref("boarding");
const pfaExpandedKeys = ref(props.mode === "pfa" ? ["线路客流监测", "站点客流监测"] : []);
provide("pfaLineSection", pfaLineSection);
provide("pfaStationSection", pfaStationSection);

// 侧栏导航：运行监测为四项；客流分析为线路/站点两组模块
const displayMenuItems = computed(() => {
  if (props.mode === "pfa") {
    return [
      { ...runMonitorMenuItems[1], label: "线路客流分析", children: PFA_LINE_SECTIONS },
      { ...runMonitorMenuItems[2], label: "站点客流分析", children: PFA_STATION_SECTIONS },
    ];
  }
  return runMonitorMenuItems;
});

const pfaIsExpanded = (key) => pfaExpandedKeys.value.includes(key);
const isNavItemActive = (item) => {
  if (props.mode === "pfa" && item?.children) return false;
  return activeTab.value === item?.key;
};
const isPfaSubActive = (itemKey, subKey) => {
  if (activeTab.value !== itemKey) return false;
  if (itemKey === "线路客流监测") return pfaLineSection.value === subKey;
  if (itemKey === "站点客流监测") return pfaStationSection.value === subKey;
  return false;
};
function pfaToggleExpand(key) {
  const i = pfaExpandedKeys.value.indexOf(key);
  if (i >= 0) pfaExpandedKeys.value.splice(i, 1);
  else pfaExpandedKeys.value.push(key);
}
function handleNavItemClick(item) {
  if (item.children) {
    if (!pfaIsExpanded(item.key)) pfaToggleExpand(item.key);
    else if (activeTab.value === item.key) pfaToggleExpand(item.key);
    if (activeTab.value !== item.key) handleSetActiveTab(item.key);
  } else {
    handleSetActiveTab(item.key);
  }
}
function handleNavSubClick(item, sub) {
  if (item.key === "线路客流监测") {
    pfaLineSection.value = sub.key;
  } else if (item.key === "站点客流监测") {
    pfaStationSection.value = sub.key;
  }
  if (activeTab.value !== item.key) handleSetActiveTab(item.key);
}
const lineMonitorRef = ref(null);
const stationMonitorRef = ref(null);
const selectedLineKey = ref("");
const selectedStationKey = ref("");
const selectedReverseStationKey = ref("");

const effectiveTab = computed(() => activeTab.value);
const isVehicleMonitorTab = computed(() => effectiveTab.value === "轨迹演示" || effectiveTab.value === "车辆运行监测");

// —— 右上角搜索框（线路 / 站点）——
// 监测组件把各自的可选项写入这两个 ref，搜索框据此提供候选并调用组件的选中方法。
// 写入方均为整数组替换、候选项本身只读，shallowRef 避免万级站点选项被逐个深代理
const runMonitorLineOptions = shallowRef([]);
const runMonitorStationOptions = shallowRef([]);
provide("runMonitorLineOptions", runMonitorLineOptions);
provide("runMonitorStationOptions", runMonitorStationOptions);
provide("runMonitorLineOptionFilter", (option) => runMonitorOptionInDisplayRange(option, "line"));
provide("runMonitorStationOptionFilter", (option) => runMonitorOptionInDisplayRange(option, "station"));

const runMonitorSearchKeyword = ref("");
const isSearchFocused = ref(false);
const runMonitorSearchType = computed(() => {
  if (effectiveTab.value === "线路客流监测") return "line";
  if (effectiveTab.value === "站点客流监测") return "station";
  return "";
});
const showRunMonitorSearch = computed(() => runMonitorSearchType.value !== "");
const runMonitorSearchPlaceholder = computed(() => {
  const prefix = baseMapLineMode.value === "metro-network" ? "地铁" : "公交";
  return runMonitorSearchType.value === "line" ? `搜索${prefix}线路` : `搜索${prefix}站点`;
});
// 地铁线网模式只搜地铁线路/站点，公交/路网模式只搜公交（选项携带 mode 字段）
const searchWantsMetro = computed(() => baseMapLineMode.value === "metro-network");
function runMonitorOptionMatchesMode(option) {
  const isMetro = String(option?.mode || "") === "metro";
  return searchWantsMetro.value ? isMetro : !isMetro;
}
// 「制式 + 显示范围」候选集单独缓存：只随选项/制式/行政区变化重算。
// 原先在结果 computed 里逐键执行，选中行政区后每敲一个字符都对全部选项（站点可达万级）
// 做一次反投影 + 点在多边形内测试，输入明显卡顿
const runMonitorSearchCandidates = computed(() => {
  const source = runMonitorSearchType.value === "line" ? runMonitorLineOptions.value : runMonitorStationOptions.value;
  return source
    .filter((item) => runMonitorOptionMatchesMode(item))
    .filter((item) => runMonitorOptionInDisplayRange(item, runMonitorSearchType.value));
});
// 键入防抖：120ms 内的连续输入只触发一次字符串过滤
const { debounced: debouncedRunMonitorSearchKeyword } = createDebouncedMirror(runMonitorSearchKeyword, 120);
const runMonitorSearchResults = computed(() => {
  const query = debouncedRunMonitorSearchKeyword.value.trim().toLowerCase();
  if (!query) return [];
  return runMonitorSearchCandidates.value
    .filter((item) => String(item.label).toLowerCase().includes(query))
    .slice(0, 50);
});
const showRunMonitorSearchResults = computed(() => isSearchFocused.value && Boolean(runMonitorSearchKeyword.value.trim()));

function handleRunMonitorSearchFocus() {
  isSearchFocused.value = true;
}
function handleRunMonitorSearchInput() {
  isSearchFocused.value = true;
}
function handleRunMonitorSearchBlur() {
  window.setTimeout(() => {
    isSearchFocused.value = false;
  }, 120);
}
function closeRunMonitorSearch() {
  isSearchFocused.value = false;
}
function clearRunMonitorSearch() {
  runMonitorSearchKeyword.value = "";
  isSearchFocused.value = true;
}
async function selectRunMonitorResult(result) {
  if (!result) return;
  runMonitorSearchKeyword.value = result.label;
  isSearchFocused.value = false;
  closeLineRoutePicker();
  if (runMonitorSearchType.value === "line") {
    let feature = modelLineFeatureByName(result.value);
    if (!feature) {
      await loadBusNetwork();
      feature = modelLineFeatureByName(result.value);
    }
    if (feature) {
      await selectLineFromBusNetwork(feature);
    } else {
      selectedLineKey.value = "";
      lineMonitorRef.value?.selectLineByName?.(result.value);
    }
  } else if (runMonitorSearchType.value === "station") {
    let feature = modelStationFeatureByName(result.value);
    if (!feature) {
      await loadBusNetwork();
      feature = modelStationFeatureByName(result.value);
    }
    if (feature) {
      await selectStationFromBusNetwork(feature);
    } else {
      selectedStationKey.value = "";
      selectedReverseStationKey.value = "";
      setSelectedBusStation(null);
      setReverseBusStation(null);
      stationMonitorRef.value?.selectStationByName?.(result.value);
    }
  }
}
function selectFirstRunMonitorResult() {
  if (runMonitorSearchResults.value.length) {
    selectRunMonitorResult(runMonitorSearchResults.value[0]);
  }
}

function handleSetActiveTab(tabName) {
  activeTab.value = tabName;
  runMonitorSearchKeyword.value = "";
  isSearchFocused.value = false;
}
provide("activeDatavisualizationTab", effectiveTab);

const rightPanelHasContent = ref(true);
provide("rightPanelHasContent", rightPanelHasContent);

function tabHasPersistentRightPanel(tab = effectiveTab.value) {
  return [
    "数据总览",
    "出行分析",
    "总体客流变化",
    "线路客流监测",
    "站点客流监测",
    "车辆运行监测",
    "体检评估分析",
  ].includes(tab);
}

function syncPersistentRightPanel(tab = effectiveTab.value) {
  if (!tabHasPersistentRightPanel(tab)) return;
  rightPanelHasContent.value = true;
  showRightPanel.value = true;
  isRightCollapsed.value = false;
}

watch(effectiveTab, (tab) => {
  rightPanelHasContent.value = tabHasPersistentRightPanel(tab);
  syncPersistentRightPanel(tab);
  closeLineRoutePicker();
  if (tab !== "线路客流监测") {
    setMonitorSelectedRouteLinks([]);
    setMonitorReverseRouteLinks([]);
    setMonitorTransferRouteLinks([]);
    selectedRouteMapLinks.value = [];
    selectedReverseRouteMapLinks.value = [];
    selectedRouteStationFlows.value = [];
    selectedRouteDetail.value = null;
    selectedReverseRouteDetail.value = null;
    selectedLinePanel.value = null;
    selectedReverseLinePanel.value = null;
    selectedLineName.value = "";
    selectedLineKey.value = "";
  }
  if (tab !== "站点客流监测") {
    setSelectedBusStation(null);
    setReverseBusStation(null);
    selectedStationPanel.value = null;
    selectedReverseStationPanel.value = null;
    selectedStationName.value = "";
    selectedReverseStationName.value = "";
    stationPanelStatus.value = "idle";
    stationPanelError.value = "";
    selectedStationKey.value = "";
    selectedReverseStationKey.value = "";
  }
  lineWidth.value = 1.2;
  stationSize.value = 32;
  applyLineWidth();
  applyStationSize();
  if (shouldLoadTransitNetworkForCurrentTab(tab)) {
    ensureTransitNetworkForCurrentTab();
    syncBusNetworkDisplayRange();
  } else {
    pauseTransitNetworkTiles();
  }
  scheduleLayerSyncBurst(4);
  observeLeftPanelSize();
});

watch(
  [() => selectModel.value?.name, isModelReady],
  () => {
    if (isModelReady.value) {
      setMapCenter();
      nextTick(() => syncPersistentRightPanel());
    }
  },
  { flush: "post" },
);

// Map Controls State & Logic
const mapZoom = ref(10.74);
const mapPitch = ref(90);
const mapRotation = ref(0);

const showRightPanel = ref(true);
const isRightCollapsed = ref(false);
const flowControl = ref(false);
const vehicleVisibilityMode = ref("all");
const vehicleVisibilityOptions = [
  { label: "全部车辆", value: "all" },
  { label: "仅公共交通", value: "public" },
  { label: "仅私家车", value: "private" },
];

const isRightPanelVisible = computed(() => showRightPanel.value && rightPanelHasContent.value);
const isInfoActive = computed(() => isRightPanelVisible.value);
const is3DActive = ref(false);

function toggleRightPanel() {
  isRightCollapsed.value = !isRightCollapsed.value;
}

const showLineWidthPopover = ref(false);
const lineWidth = ref(1.2);
const stationSize = ref(32);
const pfaSegmentOpacity = ref(100);
const vehicleSize = ref(36);
const referenceZoom = ref(10.74);
let isZoomCaptured = false;
const baseMapLineMode = ref("bus-network");
provide("BaseMapLineModeRef", baseMapLineMode);

const DISPLAY_AREA_NAME = "广州市";
const DISPLAY_RANGE_ALL = "全市";
const DISPLAY_RANGE_STORAGE_KEY = "gjcxfzksh:datavisualization:display-range";
function storedDisplayRange() {
  if (typeof window === "undefined") return DISPLAY_RANGE_ALL;
  return String(window.localStorage?.getItem(DISPLAY_RANGE_STORAGE_KEY) || DISPLAY_RANGE_ALL).trim() || DISPLAY_RANGE_ALL;
}
const showRangePopover = ref(false);
const selectedDisplayRange = ref(storedDisplayRange());
const displayRangeList = ref([DISPLAY_RANGE_ALL]);
const isLoadingDisplayRanges = ref(false);
const displayRangeError = ref("");
const adminDistrictCollection = shallowRef(emptyDistrictFeatureCollection());
let displayRangeRequestSeq = 0;

const showDisplayRangeControl = computed(() => true);
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
const selectedDisplayRangeLabel = computed(() => selectedDisplayRange.value || DISPLAY_RANGE_ALL);
const displayRangeButtonTitle = computed(() =>
  selectedDisplayRange.value === DISPLAY_RANGE_ALL
    ? "选择行政区显示范围"
    : `显示范围：${selectedDisplayRangeLabel.value}，点击恢复全市`,
);
const displayRangeButtonAriaLabel = computed(() =>
  selectedDisplayRange.value === DISPLAY_RANGE_ALL
    ? "打开行政区显示范围列表"
    : `恢复全市显示范围，当前为${selectedDisplayRangeLabel.value}`,
);
const activeDisplayRangeContext = computed(() =>
  activeDistrictContext(adminDistrictCollection.value, selectedDisplayRange.value, DISPLAY_RANGE_ALL),
);
let busNetworkRawLines = [];
let busNetworkRawFacilities = [];
const busNetworkRevision = ref(0);

// ===== 行政区计算 Worker：全网求交与线要素裁剪下沉后台线程 =====
// 原先 displayRangeSelection 是同步 computed，选区瞬间对全部线路×逐 link 做线段-多边形求交，
// 主线程冻结数百 ms~秒级。现改为：全网坐标在模型加载后的空闲期打包传入 Worker 常驻，
// 选区时仅发送 district 上下文，结果按 (model, district, revision) 记忆化；Worker 不可用时回退同步计算。
let displayRangeWorker = null;
let displayRangeWorkerBroken = false;
// 卸载后拒绝重建：慢请求的回调若在卸载后触发 postDisplayRangeWorker，
// 不加此标志会孵化一个永远无人 terminate 的新 Worker
let displayRangeWorkerDisposed = false;
let displayRangeMsgSeq = 0;
const displayRangeWorkerPending = new Map();
let displayRangeNetworkSentKey = "";
// 行政区上下文按 (名称, 版本) 只向 Worker 传一次：后续消息仅带名字与版本号，
// 免去整份行政区多边形每次 postMessage 的结构化克隆
let displayRangeContextRev = 0;
const displayRangeContextSentKeys = new Set();

function displayRangeContextPayload(context) {
  const key = `${context.name}::${displayRangeContextRev}`;
  if (displayRangeContextSentKeys.has(key)) {
    return { districtName: context.name, contextRev: displayRangeContextRev };
  }
  displayRangeContextSentKeys.add(key);
  return { districtName: context.name, contextRev: displayRangeContextRev, context };
}

function ensureDisplayRangeWorker() {
  if (displayRangeWorkerBroken || displayRangeWorkerDisposed) return null;
  if (displayRangeWorker) return displayRangeWorker;
  try {
    displayRangeWorker = new Worker(new URL("./displayRange.worker.js", import.meta.url), { type: "module" });
    // 新 Worker 端无任何缓存，已发送记录随之作废
    displayRangeContextSentKeys.clear();
  } catch (error) {
    displayRangeWorkerBroken = true;
    return null;
  }
  displayRangeWorker.onmessage = (event) => {
    const msg = event.data || {};
    const pending = displayRangeWorkerPending.get(msg.seq);
    if (!pending) return;
    displayRangeWorkerPending.delete(msg.seq);
    pending.resolve(msg);
  };
  displayRangeWorker.onerror = () => {
    displayRangeWorkerBroken = true;
    const pendings = Array.from(displayRangeWorkerPending.values());
    displayRangeWorkerPending.clear();
    pendings.forEach((pending) => pending.reject(new Error("display range worker failed")));
    displayRangeWorker?.terminate();
    displayRangeWorker = null;
  };
  return displayRangeWorker;
}

function postDisplayRangeWorker(payload, transfer = []) {
  const worker = ensureDisplayRangeWorker();
  if (!worker) return Promise.reject(new Error("display range worker unavailable"));
  return new Promise((resolve, reject) => {
    if (payload.seq != null) displayRangeWorkerPending.set(payload.seq, { resolve, reject });
    try {
      worker.postMessage(payload, transfer);
    } catch (error) {
      displayRangeWorkerPending.delete(payload.seq);
      reject(error);
      return;
    }
    if (payload.seq == null) resolve(null);
  });
}

// 全网打包为可转移的坐标缓冲：一次遍历（模型加载后空闲期执行），Worker 端零拷贝接收
function packDisplayRangeNetwork(modelName) {
  const linesMeta = [];
  const segValues = [];
  const routeFacValues = [];
  for (const line of busNetworkRawLines) {
    const lineId = String(line?.lineId || "");
    const routes = [];
    for (const route of Array.isArray(line?.routes) ? line.routes : []) {
      const routeId = String(route?.routeId || "");
      const segStart = segValues.length;
      for (const link of Array.isArray(route?.links) ? route.links : []) {
        const from = modelCoordToLngLat(link?.from);
        const to = modelCoordToLngLat(link?.to);
        if (!from || !to) continue; // 与原 modelLinkIntersectsDisplayRange 对无效坐标返回 false 等价
        segValues.push(from[0], from[1], to[0], to[1]);
      }
      const facStart = routeFacValues.length;
      for (const facility of Array.isArray(route?.facilities) ? route.facilities : []) {
        const lngLat = modelCoordToLngLat(facility?.coord || facility);
        if (!lngLat) continue;
        routeFacValues.push(lngLat[0], lngLat[1]);
      }
      routes.push({
        key: lineId && routeId ? `${lineId}::${routeId}` : routeId,
        segStart,
        segCount: (segValues.length - segStart) / 4,
        facStart,
        facCount: (routeFacValues.length - facStart) / 2,
      });
    }
    linesMeta.push({ lineName: String(line?.lineName || ""), routes });
  }
  const stationNames = [];
  const stationValues = [];
  for (const facility of busNetworkRawFacilities) {
    if (!facility?.facilityName) continue;
    const lngLat = modelCoordToLngLat(facility?.coord || facility);
    if (!lngLat) continue;
    stationNames.push(String(facility.facilityName));
    stationValues.push(lngLat[0], lngLat[1]);
  }
  const segBuf = Float64Array.from(segValues).buffer;
  const routeFacBuf = Float64Array.from(routeFacValues).buffer;
  const stationBuf = Float64Array.from(stationValues).buffer;
  return { type: "setNetwork", model: modelName, linesMeta, segBuf, routeFacBuf, stationNames, stationBuf };
}

// 网络数据与线要素集喂给 Worker（同一模型同一版本只发一次）
function warmDisplayRangeWorker(modelName) {
  if (displayRangeWorkerBroken || !busNetworkRawLines.length || !modelName) return;
  const sentKey = `${modelName}::${busNetworkRevision.value}`;
  if (displayRangeNetworkSentKey === sentKey) return;
  try {
    const payload = packDisplayRangeNetwork(modelName);
    postDisplayRangeWorker(payload, [payload.segBuf, payload.routeFacBuf, payload.stationBuf]).catch(() => {});
    postDisplayRangeWorker({
      type: "setLines",
      model: modelName,
      linesRev: busNetworkRevision.value,
      collection: busNetworkCollections.lines,
    }).catch(() => {});
    displayRangeNetworkSentKey = sentKey;
  } catch (error) {
    displayRangeWorkerBroken = true;
  }
}

// Worker 不可用时的同步回退（与原 computed 逻辑一致）
function computeDisplayRangeSelectionSync(context) {
  const routeIds = new Set();
  const lineNames = new Set();
  const stationNames = new Set();
  for (const line of busNetworkRawLines) {
    const lineId = String(line?.lineId || "");
    let lineHasRoute = false;
    for (const route of Array.isArray(line?.routes) ? line.routes : []) {
      if (routeIntersectsDisplayRange(route, context)) {
        lineHasRoute = true;
        const routeId = String(route?.routeId || "");
        routeIds.add(lineId && routeId ? `${lineId}::${routeId}` : routeId);
      }
    }
    if (lineHasRoute && line?.lineName) lineNames.add(String(line.lineName));
  }
  routeIds.delete("");
  for (const facility of busNetworkRawFacilities) {
    if (facility?.facilityName && modelCoordInDisplayRange(facility?.coord, context)) {
      stationNames.add(String(facility.facilityName));
    }
  }
  return { routeIds, lineNames, stationNames };
}

const displayRangeSelection = shallowRef(null);
const displayRangeSelectionMemo = new Map(); // `${model}::${district}::${rev}` -> { routeIds, lineNames, stationNames }
let displayRangeQueryToken = 0;

watch(
  [activeDisplayRangeContext, busNetworkRevision],
  ([context]) => {
    if (!context) {
      displayRangeQueryToken += 1;
      displayRangeSelection.value = null;
      return;
    }
    const modelName = selectModel.value?.name || "";
    const memoKey = `${modelName}::${context.name}::${busNetworkRevision.value}`;
    const cached = displayRangeSelectionMemo.get(memoKey);
    if (cached) {
      displayRangeSelection.value = cached;
      return;
    }
    const token = ++displayRangeQueryToken;
    const remember = (result) => {
      displayRangeSelectionMemo.set(memoKey, result);
      while (displayRangeSelectionMemo.size > 24) {
        displayRangeSelectionMemo.delete(displayRangeSelectionMemo.keys().next().value);
      }
      // 结果落地前保持旧值（stale-while-revalidate），避免过滤态闪跳
      displayRangeSelection.value = result;
    };
    warmDisplayRangeWorker(modelName);
    postDisplayRangeWorker({ type: "query", seq: ++displayRangeMsgSeq, model: modelName, ...displayRangeContextPayload(context) })
      .then((msg) => {
        if (token !== displayRangeQueryToken) return;
        if (!msg?.ok) throw new Error("display range worker has no network yet");
        remember({
          routeIds: new Set(msg.routeIds),
          lineNames: new Set(msg.lineNames),
          stationNames: new Set(msg.stationNames),
        });
      })
      .catch(() => {
        if (token !== displayRangeQueryToken) return;
        remember(computeDisplayRangeSelectionSync(context));
      });
  },
  { immediate: true },
);

// 选择结果异步落地后补一次重着色与显隐同步（原同步 computed 时代由读取方"读到即最新"保证）
watch(displayRangeSelection, () => {
  applyBusNetworkPaint();
  syncBaseMapLayerVisibility();
});

// 原三个名字保留为轻量派生 computed，调用方零改动
const displayRouteIdSet = computed(() => displayRangeSelection.value?.routeIds ?? null);
const displayLineNameSet = computed(() => displayRangeSelection.value?.lineNames ?? null);
const displayStationNameSet = computed(() => displayRangeSelection.value?.stationNames ?? null);

function runMonitorOptionInDisplayRange(option, type) {
  if (!activeDisplayRangeContext.value) return true;
  if (type === "station" && option?.coord) {
    return modelCoordInDisplayRange(option.coord);
  }
  const value = String(option?.value ?? option?.label ?? "");
  if (!value) return false;
  if (type === "line") return displayLineNameSet.value?.has(value) ?? true;
  if (type === "station") return displayStationNameSet.value?.has(value) ?? true;
  return true;
}

const { proxy } = getCurrentInstance() || {};
const overallFlowLoading = ref(false);
const overallFlowError = ref("");
function emptyHourlyFlow() {
  return Array.from({ length: 24 }, () => 0);
}
function hourlyIntervalLabel(hour) {
  const start = Math.max(0, Math.min(23, Number(hour) || 0));
  return `${start}:00-${start + 1}:00`;
}
function hourlyAxisLabelStyle(fullscreen = false) {
  return {
    color: "#667085",
    fontSize: fullscreen ? 11 : 10,
    interval: fullscreen ? 1 : 3,
    hideOverlap: true,
    margin: fullscreen ? 14 : 10,
  };
}
function emptyModeHourlyFlow() {
  return { bus: emptyHourlyFlow(), metro: emptyHourlyFlow() };
}
const overallFlowHourlyByMode = ref(emptyModeHourlyFlow());
let overallFlowRequestSeq = 0;
let overallFlowAbortController = null;

const overallFlowHourly = computed(() =>
  emptyHourlyFlow().map((_, index) =>
    (Number(overallFlowHourlyByMode.value.bus?.[index]) || 0)
    + (Number(overallFlowHourlyByMode.value.metro?.[index]) || 0)
  )
);
const overallFlowTotal = computed(() => overallFlowHourly.value.reduce((sum, value) => sum + (Number(value) || 0), 0));
const overallFlowBusTotal = computed(() => (overallFlowHourlyByMode.value.bus || []).reduce((sum, value) => sum + (Number(value) || 0), 0));
const overallFlowMetroTotal = computed(() => (overallFlowHourlyByMode.value.metro || []).reduce((sum, value) => sum + (Number(value) || 0), 0));
function buildHourlyRankingRows(hourly = []) {
  return hourly
    .map((value, hour) => {
      const hourLabel = `${String(hour).padStart(2, "0")}:00`;
      const nextHourLabel = hour === 23 ? "24:00" : `${String(hour + 1).padStart(2, "0")}:00`;
      const number = Math.max(0, Number(value) || 0);
      return {
        hour,
        label: `${hourLabel} - ${nextHourLabel}`,
        value: number,
        valueText: Math.round(number).toLocaleString("zh-CN"),
      };
    })
    .sort((a, b) => b.value - a.value || a.hour - b.hour);
}
const overallFlowRankingRows = computed(() => buildHourlyRankingRows(overallFlowHourly.value));
function buildHourlyFlowChartOption(hourly = []) {
  const hours = hourly.map((_, index) => hourlyIntervalLabel(index));
  const LinearGradient = proxy?.$echarts?.graphic?.LinearGradient;
  const areaColor = LinearGradient
    ? new LinearGradient(0, 0, 0, 1, [
        { offset: 0, color: "rgba(0, 113, 227, 0.26)" },
        { offset: 1, color: "rgba(0, 113, 227, 0.02)" },
      ])
    : "rgba(0, 113, 227, 0.1)";
  return {
    backgroundColor: "transparent",
    tooltip: {
      trigger: "axis",
      appendToBody: true,
      backgroundColor: "rgba(255, 255, 255, 0.98)",
      borderColor: "rgba(17, 32, 58, 0.1)",
      borderWidth: 1,
      textStyle: { color: "#1c2024", fontSize: 12 },
      formatter(params = []) {
        const item = params[0];
        if (!item) return "";
        return `<strong>${item.name}</strong><br/>客流量：${Number(item.value || 0).toLocaleString("zh-CN")} 人次`;
      },
    },
    grid: { top: 28, right: 18, bottom: 30, left: 14, containLabel: true },
    xAxis: {
      type: "category",
      data: hours,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: "rgba(17, 32, 58, 0.12)" } },
      axisLabel: hourlyAxisLabelStyle(),
    },
    yAxis: {
      type: "value",
      name: "人次",
      nameTextStyle: { color: "#98a2b3", fontSize: 10, padding: [0, 8, 0, 0] },
      splitLine: { lineStyle: { color: "rgba(17, 32, 58, 0.07)", type: "dashed" } },
      axisLabel: { color: "#667085", fontSize: 10 },
    },
    series: [
      {
        name: "客流",
        type: "line",
        smooth: 0.35,
        showSymbol: false,
        symbol: "circle",
        symbolSize: 6,
        data: hourly,
        itemStyle: { color: "#0071e3" },
        lineStyle: { width: 3, color: "#0071e3", shadowBlur: 8, shadowColor: "rgba(0, 113, 227, 0.28)" },
        areaStyle: { color: areaColor },
      },
    ],
  };
}
function buildOverallFlowChartOption(byMode = emptyModeHourlyFlow(), opt = {}) {
  const fullscreen = opt.fullscreen === true;
  const bus = Array.isArray(byMode.bus) ? byMode.bus : emptyHourlyFlow();
  const metro = Array.isArray(byMode.metro) ? byMode.metro : emptyHourlyFlow();
  const hours = emptyHourlyFlow().map((_, index) => hourlyIntervalLabel(index));
  return {
    backgroundColor: "transparent",
    color: ["#0071e3", "#16a34a"],
    tooltip: {
      trigger: "axis",
      appendToBody: true,
      backgroundColor: "rgba(255, 255, 255, 0.98)",
      borderColor: "rgba(17, 32, 58, 0.1)",
      borderWidth: 1,
      textStyle: { color: "#1c2024", fontSize: 12 },
      formatter(params = []) {
        if (!params.length) return "";
        const rows = params.map((item) =>
          `${item.marker}${item.seriesName}：${Number(item.value || 0).toLocaleString("zh-CN")} 人次`
        );
        return `<strong>${params[0].name}</strong><br/>${rows.join("<br/>")}`;
      },
    },
    legend: {
      top: 0,
      right: 8,
      itemWidth: 12,
      itemHeight: 8,
      textStyle: { color: "#667085", fontSize: fullscreen ? 13 : 11 },
      data: ["公交客流", "地铁客流"],
    },
    grid: fullscreen
      ? { top: 74, right: 46, bottom: 76, left: 58, containLabel: true }
      : { top: 32, right: 16, bottom: 26, left: 12, containLabel: true },
    xAxis: {
      type: "category",
      data: hours,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: "rgba(17, 32, 58, 0.12)" } },
      axisLabel: hourlyAxisLabelStyle(fullscreen),
    },
    yAxis: {
      type: "value",
      name: "人次",
      nameTextStyle: { color: "#98a2b3", fontSize: fullscreen ? 12 : 10, padding: [0, 8, 0, 0] },
      splitLine: { lineStyle: { color: "rgba(17, 32, 58, 0.07)", type: "dashed" } },
      axisLabel: { color: "#667085", fontSize: fullscreen ? 12 : 10 },
    },
    series: [
      {
        name: "公交客流",
        type: "line",
        smooth: 0.35,
        showSymbol: fullscreen,
        symbolSize: fullscreen ? 7 : 5,
        data: bus,
        itemStyle: { color: "#0071e3" },
        lineStyle: { width: fullscreen ? 4 : 3, color: "#0071e3", shadowBlur: 8, shadowColor: "rgba(0, 113, 227, 0.22)" },
      },
      {
        name: "地铁客流",
        type: "line",
        smooth: 0.35,
        showSymbol: fullscreen,
        symbolSize: fullscreen ? 7 : 5,
        data: metro,
        itemStyle: { color: "#16a34a" },
        lineStyle: { width: fullscreen ? 4 : 3, color: "#16a34a", shadowBlur: 8, shadowColor: "rgba(22, 163, 74, 0.2)" },
      },
    ],
  };
}
const overallFlowChartOption = computed(() => buildOverallFlowChartOption(overallFlowHourlyByMode.value));
const overallFlowFullscreenChartOption = computed(() => buildOverallFlowChartOption(overallFlowHourlyByMode.value, { fullscreen: true }));
const overallFlowFullscreenVisible = ref(false);

function openOverallFlowFullscreen() {
  if (overallFlowError.value) return;
  overallFlowFullscreenVisible.value = true;
}

// 线路客流监测：右侧简化卡片 —— 选中线路的日客流量 + 全天客流变化折线图（数据由 XLZL 上抛）
// 面板/详情/地图 link 数组体量大且只会整体替换引用（XLZL 与本文件所有赋值点均为整值赋值），
// 用 shallowRef 避免深层代理与深度遍历（同 adminDistrictCollection 的先例）
const selectedLinePanel = shallowRef(null);
const selectedLineName = ref("");
const selectedRouteDetail = shallowRef(null);
const selectedRouteMapLinks = shallowRef([]);
const selectedReverseLinePanel = shallowRef(null);
const selectedReverseRouteDetail = shallowRef(null);
const selectedReverseRouteMapLinks = shallowRef([]);
// 当前方向每站断面客流（XLZL 上抛），空心圈描边按其分档取与线一致的颜色
const selectedRouteStationFlows = shallowRef([]);
provide("runMonitorSelectedRouteStationFlows", selectedRouteStationFlows);
provide("runMonitorSelectedLinePanel", selectedLinePanel);
provide("runMonitorSelectedLineName", selectedLineName);
provide("runMonitorSelectedRouteDetail", selectedRouteDetail);
provide("runMonitorSelectedRouteMapLinks", selectedRouteMapLinks);
provide("runMonitorSelectedReverseLinePanel", selectedReverseLinePanel);
provide("runMonitorSelectedReverseRouteDetail", selectedReverseRouteDetail);
provide("runMonitorSelectedReverseRouteMapLinks", selectedReverseRouteMapLinks);
provide("runMonitorSimplifiedRight", true);

// 这些 ref 只会整体替换引用，监听引用本身即可，无需 deep 遍历大数组/大对象。
// 原先两个 watcher 源重叠（selectedRouteDetail 同时出现在两处），一次选线在同一 flush 内
// refreshMonitorSelectedRouteLinks 跑 2 遍（每遍最多 6 个 deck 层 setData）、显隐同步跑 3-4 遍；
// 合并为一个 watcher + 微任务级去重，同一批变更只执行一轮
let monitorLinksSyncScheduled = false;
function scheduleMonitorSelectionSync() {
  if (monitorLinksSyncScheduled) return;
  monitorLinksSyncScheduled = true;
  queueMicrotask(() => {
    monitorLinksSyncScheduled = false;
    refreshMonitorSelectedRouteLinks();
    syncBaseMapLayerVisibility();
  });
}

watch(
  [
    selectedRouteDetail,
    selectedRouteMapLinks,
    selectedReverseRouteMapLinks,
    selectedLinePanel,
    selectedReverseLinePanel,
    selectedReverseRouteDetail,
    pfaLineSection,
    effectiveTab,
    () => selectModel.value?.name,
  ],
  () => {
    scheduleMonitorSelectionSync();
  },
);

// 换乘关联线路的刷新保持原有触发面（面板/详情/区块/模型），不并入上面的大 watcher：
// 被 map links 更新（含时段防抖落值）连带触发会产生多余的全网共站扫描
let pfaTransferSyncScheduled = false;
// 连续处于非激活态时地图上本就没有关联线路可清，直接跳过，
// 免得每次选线/面板更新都对换乘图层做一轮空 setData
let pfaTransferWasActive = false;
watch(
  [selectedLinePanel, selectedReverseLinePanel, selectedRouteDetail, selectedReverseRouteDetail, pfaLineSection, effectiveTab, () => selectModel.value?.name],
  () => {
    if (pfaTransferSyncScheduled) return;
    pfaTransferSyncScheduled = true;
    queueMicrotask(() => {
      pfaTransferSyncScheduled = false;
      const active = isPfaTransferSectionActive() && isPfaLineSelectionActive();
      if (!active && !pfaTransferWasActive) return;
      pfaTransferWasActive = active;
      refreshPfaTransferRouteLinks();
    });
  },
);

const lineFlowHourly = computed(() => {
  const flow = selectedLinePanel.value?.hourlyFlow;
  const base = Array.from({ length: 24 }, () => 0);
  if (Array.isArray(flow)) {
    flow.forEach((value, index) => {
      if (index < base.length) base[index] = Number(value) || 0;
    });
  }
  return base;
});
const reverseLineFlowHourly = computed(() => {
  const flow = selectedReverseLinePanel.value?.hourlyFlow;
  const base = Array.from({ length: 24 }, () => 0);
  if (Array.isArray(flow)) {
    flow.forEach((value, index) => {
      if (index < base.length) base[index] = Number(value) || 0;
    });
  }
  return base;
});
const lineFlowPrimaryTotal = computed(() => {
  const metricTotal = Number(selectedLinePanel.value?.metrics?.passenger);
  if (Number.isFinite(metricTotal) && metricTotal > 0) return metricTotal;
  return lineFlowHourly.value.reduce((sum, value) => sum + value, 0);
});
const lineFlowReverseTotal = computed(() => {
  const metricTotal = Number(selectedReverseLinePanel.value?.metrics?.passenger);
  if (Number.isFinite(metricTotal) && metricTotal > 0) return metricTotal;
  return reverseLineFlowHourly.value.reduce((sum, value) => sum + value, 0);
});
function positiveMetric(panel, key) {
  const value = Number(panel?.metrics?.[key]);
  return Number.isFinite(value) && value > 0 ? value : 0;
}

function combinedMetric(key) {
  return positiveMetric(selectedLinePanel.value, key) + positiveMetric(selectedReverseLinePanel.value, key);
}

function formatLineFlowStat(value) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? `${Math.round(number).toLocaleString("zh-CN")} 人次` : "--";
}

function formatLineCountStat(value, unit) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? `${Math.round(number).toLocaleString("zh-CN")} ${unit}` : "--";
}

const lineOperationStats = computed(() => {
  const passenger = lineFlowPrimaryTotal.value + lineFlowReverseTotal.value;
  const departures = combinedMetric("departures");
  const vehicles = combinedMetric("vehicles");
  const perTrip = departures > 0 ? passenger / departures : combinedMetric("perTripFlow");
  const perVehicle = vehicles > 0 ? passenger / vehicles : combinedMetric("perVehicleFlow");
  return [
    { label: "日客流量", value: formatLineFlowStat(passenger) },
    { label: "日发车班次", value: formatLineCountStat(departures, "班") },
    { label: "车辆数", value: formatLineCountStat(vehicles, "辆") },
    { label: "单班次客流", value: formatLineFlowStat(perTrip) },
    { label: "车日均客流量", value: formatLineFlowStat(perVehicle) },
  ];
});
const selectedLineBaseName = computed(() =>
  String(selectedLineName.value || "")
    .replace(/[（(].*?[）)]/g, "")
    .trim()
);

function routeEndpointDirectionLabel(route = {}, fallback = "当前方向客流") {
  const facilities = Array.isArray(route?.facilities) ? route.facilities : [];
  const start = String(facilities[0]?.facilityName || "");
  const end = String(facilities[facilities.length - 1]?.facilityName || "");
  return start && end ? `${start}-${end}方向客流` : fallback;
}

const selectedLineIsMetro = computed(() => {
  const route = selectedRouteDetail.value || selectedLinePanel.value || {};
  return routeModeKey(route) === "metro" || /地铁|轨道|metro|subway|rail/i.test(selectedLineName.value);
});

const lineFlowSummaryItems = computed(() => {
  if (selectedLineIsMetro.value) {
    return [{ label: "全线客流", value: lineFlowPrimaryTotal.value + lineFlowReverseTotal.value }];
  }
  const items = [
    {
      label: routeEndpointDirectionLabel(selectedRouteDetail.value, "当前方向客流"),
      value: lineFlowPrimaryTotal.value,
    },
  ];
  if (selectedReverseLinePanel.value || lineFlowReverseTotal.value > 0) {
    items.push({
      label: routeEndpointDirectionLabel(selectedReverseRouteDetail.value, "反方向客流"),
      value: lineFlowReverseTotal.value,
    });
  }
  return items;
});

const lineFlowChartPrimaryHourly = computed(() => {
  if (!selectedLineIsMetro.value) return lineFlowHourly.value;
  return lineFlowHourly.value.map((value, index) => value + (Number(reverseLineFlowHourly.value[index]) || 0));
});
const lineFlowChartReverseHourly = computed(() => selectedLineIsMetro.value
  ? Array.from({ length: 24 }, () => 0)
  : reverseLineFlowHourly.value
);
const lineFlowChartSeriesNames = computed(() => selectedLineIsMetro.value
  ? ["全线客流"]
  : [
      routeEndpointDirectionLabel(selectedRouteDetail.value, "当前方向客流").replace(/客流$/, ""),
      routeEndpointDirectionLabel(selectedReverseRouteDetail.value, "反方向客流").replace(/客流$/, ""),
    ]
);

function buildDirectionalLineFlowChartOption(primaryHourly = [], reverseHourly = [], seriesNames = ["上行", "下行"]) {
  const hours = emptyHourlyFlow().map((_, index) => hourlyIntervalLabel(index));
  const primary = hours.map((_, index) => Number(primaryHourly[index]) || 0);
  const reverse = hours.map((_, index) => Number(reverseHourly[index]) || 0);
  const primaryName = seriesNames[0] || "上行";
  const reverseName = seriesNames[1] || "下行";
  const showReverse = seriesNames.length > 1;
  const LinearGradient = proxy?.$echarts?.graphic?.LinearGradient;
  const primaryAreaColor = LinearGradient
    ? new LinearGradient(0, 0, 0, 1, [
        { offset: 0, color: "rgba(249, 115, 22, 0.28)" },
        { offset: 1, color: "rgba(249, 115, 22, 0.02)" },
      ])
    : "rgba(249, 115, 22, 0.12)";
  const reverseAreaColor = LinearGradient
    ? new LinearGradient(0, 0, 0, 1, [
        { offset: 0, color: "rgba(21, 105, 222, 0.24)" },
        { offset: 1, color: "rgba(21, 105, 222, 0.015)" },
      ])
    : "rgba(21, 105, 222, 0.1)";
  return {
    backgroundColor: "transparent",
    color: ["#f97316", "#1569de"],
    animation: true,
    animationDuration: 900,
    animationEasing: "cubicOut",
    tooltip: {
      trigger: "axis",
      appendToBody: true,
      backgroundColor: "rgba(255, 255, 255, 0.98)",
      borderColor: "rgba(17, 32, 58, 0.1)",
      borderWidth: 1,
      textStyle: { color: "#1c2024", fontSize: 12 },
      formatter(params = []) {
        if (!params.length) return "";
        const rows = params.map((item) =>
          `${item.marker}${item.seriesName}：${Number(item.value || 0).toLocaleString("zh-CN")} 人次`
        );
        return `<strong>${params[0].name}</strong><br/>${rows.join("<br/>")}`;
      },
    },
    legend: {
      top: 0,
      right: 8,
      itemWidth: 12,
      itemHeight: 8,
      textStyle: { color: "#667085", fontSize: 11 },
      data: showReverse ? [primaryName, reverseName] : [primaryName],
    },
    grid: { top: 34, right: 18, bottom: 30, left: 14, containLabel: true },
    xAxis: {
      type: "category",
      data: hours,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: "rgba(17, 32, 58, 0.12)" } },
      axisLabel: hourlyAxisLabelStyle(),
    },
    yAxis: {
      type: "value",
      name: "人次",
      nameTextStyle: { color: "#98a2b3", fontSize: 10, padding: [0, 8, 0, 0] },
      splitLine: { lineStyle: { color: "rgba(17, 32, 58, 0.07)", type: "dashed" } },
      axisLabel: { color: "#667085", fontSize: 10 },
    },
    series: [
      {
        name: primaryName,
        type: "line",
        smooth: 0.35,
        showSymbol: false,
        symbol: "circle",
        symbolSize: 6,
        data: primary,
        itemStyle: { color: "#f97316" },
        lineStyle: { width: 3, color: "#f97316", shadowBlur: 8, shadowColor: "rgba(249, 115, 22, 0.24)" },
        areaStyle: { color: primaryAreaColor },
        animationDuration: 900,
        animationDelay(index) {
          return index * 12;
        },
      },
      showReverse ? {
        name: reverseName,
        type: "line",
        smooth: 0.35,
        showSymbol: false,
        symbol: "circle",
        symbolSize: 6,
        data: reverse,
        itemStyle: { color: "#1569de" },
        lineStyle: { width: 3, color: "#1569de", shadowBlur: 8, shadowColor: "rgba(21, 105, 222, 0.22)" },
        areaStyle: { color: reverseAreaColor },
        animationDuration: 900,
        animationDelay(index) {
          return index * 12 + 80;
        },
      } : null,
    ].filter(Boolean),
  };
}
const lineFlowChartOption = computed(() =>
  buildDirectionalLineFlowChartOption(
    lineFlowChartPrimaryHourly.value,
    lineFlowChartReverseHourly.value,
    lineFlowChartSeriesNames.value,
  )
);

// 站点客流监测：右侧卡片与「总体客流变化」一致 —— 站点全天上下车人数 + 上下车变化（数据由 ZDZL 上抛）
// 站点面板对象体量大且整值替换，与线路侧 selectedLinePanel 同款用 shallowRef
const selectedStationPanel = shallowRef(null);
const selectedReverseStationPanel = shallowRef(null);
const selectedStationName = ref("");
const selectedReverseStationName = ref("");
const stationPanelStatus = ref("idle");
const stationPanelError = ref("");
provide("runMonitorSelectedStationPanel", selectedStationPanel);
provide("runMonitorSelectedReverseStationPanel", selectedReverseStationPanel);
provide("runMonitorSelectedStationName", selectedStationName);
provide("runMonitorSelectedReverseStationName", selectedReverseStationName);
provide("runMonitorStationPanelStatus", stationPanelStatus);
provide("runMonitorStationPanelError", stationPanelError);

const stationPanelTagText = computed(() => {
  if (!selectedStationName.value) return "";
  if (stationPanelStatus.value === "loading") return "加载中";
  if (stationPanelStatus.value === "generating") return "生成中";
  if (stationPanelStatus.value === "error") return "加载失败";
  if (!selectedStationPanel.value) return "暂无客流数据";
  return "";
});

const stationPanelTagType = computed(() => {
  if (stationPanelStatus.value === "error") return "danger";
  if (stationPanelStatus.value === "generating") return "warning";
  return "info";
});

const stationPanelUnavailable = computed(() =>
  Boolean(selectedStationName.value)
  && ["loading", "generating", "error"].includes(stationPanelStatus.value)
);

// 选中线路/站点变化时，重新计算底图聚焦淡出。以地图选中键为准，
// 不依赖客流面板是否恰好有缓存数据。
// selectedRouteDetail 为 shallowRef 且仅整体赋值，selectedStationName 为字符串：监听引用即可；
// 合并为单一 watcher，选中变化在同一 flush 内不再让 applyBusNetworkFocus 跑两遍
watch([selectedLineKey, selectedStationKey, selectedReverseStationKey, effectiveTab, selectedRouteDetail, selectedStationName], () => {
  applyBusNetworkFocus();
  syncBaseMapLayerVisibility();
});

const stationFlowHourly = computed(() => {
  const panel = selectedStationPanel.value || {};
  const boarding = Array.isArray(panel.boardingByHour) ? panel.boardingByHour : [];
  const alighting = Array.isArray(panel.alightingByHour) ? panel.alightingByHour : [];
  const base = Array.from({ length: 24 }, () => 0);
  if (boarding.length || alighting.length) {
    for (let hour = 0; hour < 24; hour += 1) {
      base[hour] = (Number(boarding[hour]) || 0) + (Number(alighting[hour]) || 0);
    }
  } else {
    const flow = Array.isArray(panel.hourlyFlow) ? panel.hourlyFlow : [];
    flow.forEach((value, index) => {
      if (index < base.length) base[index] = Number(value) || 0;
    });
  }
  return base;
});
const reverseStationFlowHourly = computed(() => {
  const panel = selectedReverseStationPanel.value || {};
  const boarding = Array.isArray(panel.boardingByHour) ? panel.boardingByHour : [];
  const alighting = Array.isArray(panel.alightingByHour) ? panel.alightingByHour : [];
  const base = Array.from({ length: 24 }, () => 0);
  if (boarding.length || alighting.length) {
    for (let hour = 0; hour < 24; hour += 1) {
      base[hour] = (Number(boarding[hour]) || 0) + (Number(alighting[hour]) || 0);
    }
  } else {
    const flow = Array.isArray(panel.hourlyFlow) ? panel.hourlyFlow : [];
    flow.forEach((value, index) => {
      if (index < base.length) base[index] = Number(value) || 0;
    });
  }
  return base;
});
const stationFlowPrimaryTotal = computed(() => stationFlowHourly.value.reduce((sum, value) => sum + value, 0));
const stationFlowReverseTotal = computed(() => reverseStationFlowHourly.value.reduce((sum, value) => sum + value, 0));
const primaryStationSideLabel = computed(() => selectedReverseStationName.value ? "主站点" : "站点");
const reverseStationSideLabel = computed(() => selectedReverseStationName.value ? "对侧站点" : "对侧");
const stationFlowChartOption = computed(() =>
  buildDirectionalLineFlowChartOption(stationFlowHourly.value, reverseStationFlowHourly.value, ["主站点", "对侧站点"])
);

function formatOverallFlow(value) {
  const number = Number(value);
  return Number.isFinite(number) ? `${Math.round(number).toLocaleString("zh-CN")} 人次` : "暂无";
}

function routeModeKey(route = {}) {
  const text = [
    route.mode,
    route.transportMode,
    route.lineName,
    route.routeName,
    route.lineId,
  ].map((item) => String(item || "").toLowerCase()).join(" ");
  return /(metro|subway|rail|地铁|轨道)/i.test(text) ? "metro" : "bus";
}

function routePanelToOverallHourlyByMode(panel = {}, routeIds = null) {
  const hourly = emptyModeHourlyFlow();
  const routeEntries = panel?.routes && typeof panel.routes === "object" ? Object.entries(panel.routes) : [];
  routeEntries.forEach(([routeId, route]) => {
    const ids = [
      routeId,
      route?.routeId,
      route?.routeKey,
      route?.lineId && route?.routeId ? `${route.lineId}::${route.routeId}` : "",
    ].map((value) => String(value || "")).filter(Boolean);
    if (routeIds && !ids.some((id) => routeIds.has(id))) return;
    const values = Array.isArray(route?.hourlyFlow) ? route.hourlyFlow : [];
    const key = routeModeKey(route);
    values.forEach((value, index) => {
      if (index < hourly[key].length) hourly[key][index] += Number(value) || 0;
    });
  });
  return hourly;
}

// 后端 overallFlow 接口返回的 hourlyByMode 兜底为 24 小时数组，分类口径与 routeModeKey 一致
function normalizeOverallHourlyByMode(hourlyByMode = {}) {
  const result = emptyModeHourlyFlow();
  ["bus", "metro"].forEach((key) => {
    const values = Array.isArray(hourlyByMode?.[key]) ? hourlyByMode[key] : [];
    values.forEach((value, index) => {
      if (index < result[key].length) result[key][index] = Number(value) || 0;
    });
  });
  return result;
}

async function loadOverallFlow() {
  if (effectiveTab.value !== "总体客流变化" || !selectModel.value?.name || !isModelReady.value) return;
  const modelName = selectModel.value.name;
  const seq = ++overallFlowRequestSeq;
  overallFlowAbortController?.abort();
  overallFlowAbortController = typeof AbortController !== "undefined" ? new AbortController() : null;
  overallFlowLoading.value = true;
  overallFlowError.value = "";
  // 后端缓存生成中：保持“加载中”显示（沿用现有习惯，不新增轮询）
  let keepLoading = false;
  try {
    const routeIds = displayRouteIdSet.value;
    if (routeIds) {
      // 有行政区筛选：整包客流面板走共享缓存（与线路客流监测共用同一次下载），本地聚合筛选后的总体客流
      const panel = await getCachedRoutePanel(modelName);
      if (seq !== overallFlowRequestSeq) return;
      if (panel?.status === "generating") {
        keepLoading = true;
        return;
      }
      overallFlowHourlyByMode.value = routePanelToOverallHourlyByMode(panel || {}, displayRouteIdSet.value);
    } else {
      // 无行政区筛选：走轻量总体客流接口，避免下载整包 routePanel
      const res = await getOverallFlow(
        { datasource: modelName },
        { silentError: true, signal: overallFlowAbortController?.signal },
      );
      if (seq !== overallFlowRequestSeq) return;
      const data = res?.data && typeof res.data === "object" ? res.data : {};
      if (data.status === "generating" || !data.hourlyByMode) {
        keepLoading = true;
        return;
      }
      overallFlowHourlyByMode.value = normalizeOverallHourlyByMode(data.hourlyByMode);
    }
  } catch (error) {
    if (seq !== overallFlowRequestSeq) return;
    if (error?.message === "请求已取消" || error?.cause?.message === "canceled") return;
    overallFlowHourlyByMode.value = emptyModeHourlyFlow();
    overallFlowError.value = error?.message || "总体客流变化加载失败";
  } finally {
    if (seq === overallFlowRequestSeq) {
      overallFlowAbortController = null;
      if (!keepLoading) overallFlowLoading.value = false;
    }
  }
}

// 数据加载只跟随页签/模型变化；行政区筛选变化不重新下载（见下方筛选 watch）
watch(
  [effectiveTab, () => selectModel.value?.name, isModelReady],
  loadOverallFlow,
  { immediate: true },
);

// 行政区筛选变化：整包面板已缓存时纯本地重算，不发请求；
// 未缓存时才请求一次（有筛选走共享缓存整包，无筛选走轻量 overallFlow 接口）
watch([selectedDisplayRange, displayRouteIdSet], () => {
  if (effectiveTab.value !== "总体客流变化" || !selectModel.value?.name || !isModelReady.value) return;
  const cachedPanel = peekCachedRoutePanel(selectModel.value.name);
  if (cachedPanel) {
    overallFlowRequestSeq += 1;
    overallFlowAbortController?.abort();
    overallFlowAbortController = null;
    overallFlowLoading.value = false;
    overallFlowError.value = "";
    overallFlowHourlyByMode.value = routePanelToOverallHourlyByMode(cachedPanel, displayRouteIdSet.value);
    return;
  }
  loadOverallFlow();
});

const RM_SOURCE_LINES = "rm-bus-network-lines-source";
const RM_SOURCE_STATIONS = "rm-bus-network-stations-source";
const RM_SOURCE_SELECTED_STATION = "rm-bus-network-selected-station-source";
const RM_SOURCE_REVERSE_SELECTED_STATION = "rm-bus-network-reverse-selected-station-source";
const RM_SOURCE_DISPLAY_RANGE = "rm-display-range-source";
const RM_LAYER_LINES = "rm-bus-network-lines";
const RM_LAYER_STATIONS = "rm-bus-network-stations";
const RM_LAYER_STATION_LABELS = "rm-bus-network-station-labels";
const RM_LAYER_STATION_SELECTED_HALO = "rm-bus-network-station-selected-halo";
const RM_LAYER_STATION_REVERSE_SELECTED_HALO = "rm-bus-network-station-reverse-selected-halo";
const RM_LAYER_STATION_SELECTED = "rm-bus-network-station-selected";
const RM_LAYER_STATION_REVERSE_SELECTED = "rm-bus-network-station-reverse-selected";
const RM_LAYER_DISPLAY_RANGE_OUTLINE = "rm-display-range-outline";
// 断面客流/站点乘降：选中方向站点用空心圆圈+站名展示。
// 用独立数据源（由选中方向 facilities 直接生成）：共享站点源会被站点客流分析组件整包替换成
// 合并后的物理站要素，facilityId 与线路方向站点对不上，按 id 过滤会全部落空。
const RM_SOURCE_SEGMENT_STATIONS = "rm-segment-stations-source";
const RM_LAYER_STATION_SEGMENT_RING = "rm-bus-network-station-segment-ring";
const RM_LAYER_SEGMENT_STATION_LABELS = "rm-segment-station-labels";
// 关联线路模式：换乘站点（空心圈 + 站名）
const RM_SOURCE_TRANSFER_STATIONS = "rm-transfer-stations-source";
const RM_LAYER_TRANSFER_STATION_RING = "rm-transfer-station-ring";
const RM_LAYER_TRANSFER_STATION_LABELS = "rm-transfer-station-labels";
// 关联线路本体：改用 MapLibre GeoJSON 线（每条线一条 LineString，蓝色实线），
// 避免 deck RouteLayer 把多条线的链路串接产生错误连线（"线形"问题）
const RM_SOURCE_TRANSFER_LINES = "rm-transfer-lines-source";
const RM_LAYER_TRANSFER_LINES = "rm-transfer-lines";
const PFA_RELATED_LINE_COLOR = "#1569de"; // 与客流画像下行蓝线一致
// 站点客流分析：全网站点客流热力图（仿人群密度专题图，开启时隐藏路网/公交线网/站点）
const RM_SOURCE_STATION_HEAT = "rm-station-heat-source";
const RM_LAYER_STATION_HEAT = "rm-station-heat-layer";
// 热力图的全域蓝底（当前显示范围的行政区面，颜色=专题图最低档蓝）
const RM_SOURCE_STATION_HEAT_BASE = "rm-station-heat-base-source";
const RM_LAYER_STATION_HEAT_BASE = "rm-station-heat-base-layer";
// 地铁线网图层：与公交线网共用 RM_SOURCE_LINES / RM_SOURCE_STATIONS 数据源，
// 按要素 mode（线路）/ type（站点）过滤，二者随 baseMapLineMode 互斥显示。
const RM_LAYER_METRO_LINES = "rm-metro-network-lines";
// 白色描边（casing）：垫在彩色线之下、略宽，形成描边效果
const RM_LAYER_METRO_LINES_CASING = "rm-metro-network-lines-casing";
// 铁路制式线形：彩色粗线上叠一条白色短虚线，与公交细实线形成制式区分
const RM_LAYER_METRO_LINE_DASH = "rm-metro-network-lines-dash";
const METRO_LINE_FILTER = ["==", ["get", "mode"], "metro"];
const BUS_LINE_FILTER = ["!=", ["get", "mode"], "metro"];
// routePanel 未就绪时地铁线的兜底颜色（与地铁站点图标同色系）
const METRO_FALLBACK_LINE_COLOR = MAP_THEME.metro.line;
const RM_STATION_ICON_ID = "rm-bus-network-station-icon";
const RM_METRO_STATION_ICON_ID = "rm-metro-network-station-icon";
// 选中站点高亮图标（橙色靶心圈，与选中线路同色）；对向选中用蓝色靶心（与对向线路同色）
const RM_STATION_HIGHLIGHT_ICON_ID = "rm-bus-network-station-highlight-icon";
const RM_STATION_HIGHLIGHT_REVERSE_ICON_ID = "rm-bus-network-station-highlight-reverse-icon";
const RM_STATION_ICON_SIZE = 96;
const RM_BASE_LINE_OPACITY = 0.7;
// 需求11：断面客流改为 QGIS 式分级色阶（百分比阈值 = 断面流量占本线最大断面流量），
// 默认绿-黄-红 3 档对齐旧的三档语义，可在设置弹层调整色系/档数/阈值。
const pfaSegmentScale = ref(createColorScaleConfig("gnylrd", 3));
function hexToRgbColor(color, fallback) {
  const value = String(color || "").trim();
  const normalized = value.startsWith("#") ? value.slice(1) : value;
  const hex = normalized.length === 3
    ? normalized.split("").map((part) => part + part).join("")
    : normalized.slice(0, 6);
  const number = Number.parseInt(hex, 16);
  if (!Number.isFinite(number)) return fallback;
  return [(number >> 16) & 255, (number >> 8) & 255, number & 255];
}

// 同色系加深：浅色断面（如黄色）上的空心圈描边直接用原色看不清，压暗后保持色系可辨
function darkenHexColor(color, factor = 0.38) {
  const rgb = hexToRgbColor(color, null);
  if (!rgb) return "#334155";
  return `#${rgb
    .map((channel) => Math.max(0, Math.round(channel * (1 - factor))).toString(16).padStart(2, "0"))
    .join("")}`;
}
// 线路客流整包（与 XLZL 共用 routePanel 缓存）：断面/线路着色的公共数据源。
// 声明前置到此处——regionSegmentMaxByMode 等断面色阶 computed 会引用它，若仍放在后面会触发
// setup 期"Cannot access 'lineFlowPanel' before initialization"，导致整个页面白屏。
const lineFlowPanel = shallowRef(null);
let lineFlowPanelModel = "";
let lineFlowPanelSeq = 0;
// 全区域断面客流分布（按公交/地铁分别统计）：取整包各线"上下行合并组"每个断面的全天客流。
// 作为断面色阶的统一参照（分位数断点由此分布计算），使不同线路的图例保持一致口径。
const regionSegmentStats = computed(() => {
  const bus = [];
  const metro = [];
  const groups = lineFlowPanel.value?.lineGroups;
  if (groups && typeof groups === "object") {
    for (const [key, group] of Object.entries(groups)) {
      const arr = String(key).startsWith("metro::") ? metro : bus;
      for (const seg of Array.isArray(group?.segments) ? group.segments : []) {
        let total = Number(seg?.totalFlow);
        if (!Number.isFinite(total)) total = sumHourlyFlowArray(seg?.flowByHour);
        if (total > 0) arr.push(total);
      }
    }
  }
  return { bus, metro };
});

// 当前制式（线网模式）的断面客流值分布
const pfaSegmentValues = computed(() =>
  baseMapLineMode.value === "metro-network" ? regionSegmentStats.value.metro : regionSegmentStats.value.bus
);

const pfaSegmentMaxFlow = computed(() => {
  const values = pfaSegmentValues.value;
  if (values.length) return values.reduce((m, v) => Math.max(m, v), 0);
  // 回退：整包未就绪时用选中线路自身最大断面
  let max = 0;
  const scan = (links) => {
    for (const link of Array.isArray(links) ? links : []) {
      const flow = Number(link?.flow);
      if (Number.isFinite(flow) && flow > max) max = flow;
    }
  };
  scan(selectedRouteMapLinks.value);
  scan(selectedReverseRouteMapLinks.value);
  return max;
});

const pfaSegmentResolvedScale = computed(() => resolveColorScale(pfaSegmentScale.value));

// 排序只随分布本身变化重跑；调色阶（档数/阈值）时在已排序数组上直接取分位，
// 避免全域断面分布（可达数万值）在每次色阶操作时全量重排
const pfaSegmentSortedValues = computed(() => sortFlowValues(pfaSegmentValues.value));

// 断面分位数断点（绝对人次），由全区域同制式断面分布计算
const pfaSegmentBreaks = computed(() => quantileBreaks(pfaSegmentSortedValues.value, pfaSegmentResolvedScale.value.thresholds, { assumeSorted: true }));

// RouteLayer.setFlowStyleStops 走绝对阈值（maxValue）：直接用分位数断点。
// widthStep 按分档线宽系数递增（客流越大断面线越粗），收敛避免失衡。
const pfaSegmentFlowStops = computed(() => {
  const { colors, widths } = pfaSegmentResolvedScale.value;
  const breaks = pfaSegmentBreaks.value;
  return colors.map((color, index) => ({
    maxValue: index < breaks.length ? (breaks[index] || index + 1) : Infinity,
    color: hexToRgbColor(color, [22, 163, 74]),
    widthStep: (widths[index] - 1),
  }));
});

const segmentFlowLegendItems = computed(() =>
  buildValueLegendItems(pfaSegmentResolvedScale.value.colors, pfaSegmentBreaks.value, pfaSegmentMaxFlow.value, flowValueLabel, pfaSegmentResolvedScale.value.widths)
);

// 空心圈描边色：facilityId → 该站断面客流分档色的加深色（与线同色系但更深，浅色断面上也可辨），
// 无数据回退深橙色
const segmentStationStrokeExpression = computed(() => {
  const rows = selectedRouteStationFlows.value;
  const breaks = pfaSegmentBreaks.value;
  if (!Array.isArray(rows) || !rows.length || !breaks.length) return null;
  const { colors } = pfaSegmentResolvedScale.value;
  const darkColors = colors.map((color) => darkenHexColor(color));
  const seen = new Set();
  const expression = ["match", ["to-string", ["get", "facilityId"]]];
  rows.forEach(({ facilityId, flow }) => {
    const id = String(facilityId || "");
    if (!id || seen.has(id)) return;
    seen.add(id);
    expression.push(id, darkColors[classifyByBreaks(Number(flow) || 0, breaks)]);
  });
  if (expression.length <= 2) return null;
  expression.push(darkColors[0]);
  return expression;
});

const SEGMENT_RING_FALLBACK_STROKE = "#b45309";

function applySegmentStationRingStyle() {
  const map = MapRef.value?.map;
  if (!map?.getLayer(RM_LAYER_STATION_SEGMENT_RING)) return;
  map.setPaintProperty(
    RM_LAYER_STATION_SEGMENT_RING,
    "circle-stroke-color",
    segmentStationStrokeExpression.value || SEGMENT_RING_FALLBACK_STROKE,
  );
}

watch(segmentStationStrokeExpression, applySegmentStationRingStyle);

// ===== 站点客流分析：全网站点客流热力图（设置里开关，开启时隐藏路网/公交线网/站点） =====
const stationHeatmapEnabled = ref(false);
// 原始 stations 整包按模型缓存；蓝底/热力点按当前行政区显示范围重建
const stationHeatStations = shallowRef(null);
let stationHeatModel = "";
let stationHeatSeq = 0;
// 热力色阶（图例右上角齿轮可调色系/档数/阈值）。
// 默认 Mako 反转（浅青→深海军蓝）：低密度轻薄、高密度深邃，贴合平台蓝玻璃风格
const stationHeatScale = ref({ ...createColorScaleConfig("Mako", 5), reverse: true });
const showStationHeatScalePopover = ref(false);
// 最大站点全天客流 + 全网站点客流分布（分位数断点由此计算）
const stationHeatMaxFlow = ref(0);
const stationHeatValues = shallowRef([]);
const stationHeatResolvedScale = computed(() => resolveColorScale(stationHeatScale.value));

// 同断面色阶：排序只随分布变化重跑，调色阶不再全量重排全网站点客流
const stationHeatSortedValues = computed(() => sortFlowValues(stationHeatValues.value));

// 站点客流分位数断点（绝对人次）
const stationHeatBreaks = computed(() => quantileBreaks(stationHeatSortedValues.value, stationHeatResolvedScale.value.thresholds, { assumeSorted: true }));

// 分档 step 表达式：密度低于 2% 透明（避免全屏铺色），其后按"分位数断点占最大客流的比例"作为密度位置分档取色
const stationHeatColorExpression = computed(() => {
  const { colors } = stationHeatResolvedScale.value;
  const breaks = stationHeatBreaks.value;
  const max = stationHeatMaxFlow.value;
  const expression = ["step", ["heatmap-density"], "rgba(0, 0, 0, 0)", 0.02, colors[0]];
  let previous = 0.02;
  breaks.forEach((brk, index) => {
    const ratio = max > 0 ? brk / max : (index + 1) / (breaks.length + 1);
    const position = Math.min(0.999, Math.max(previous + 0.001, ratio));
    expression.push(position, colors[index + 1]);
    previous = position;
  });
  return expression;
});

function applyStationHeatColor() {
  const map = MapRef.value?.map;
  if (!map?.getLayer(RM_LAYER_STATION_HEAT)) return;
  map.setPaintProperty(RM_LAYER_STATION_HEAT, "heatmap-color", stationHeatColorExpression.value);
}

watch(stationHeatColorExpression, applyStationHeatColor);

const stationHeatLegendItems = computed(() =>
  buildValueLegendItems(stationHeatResolvedScale.value.colors, stationHeatBreaks.value, stationHeatMaxFlow.value, flowValueLabel)
);

const showStationHeatLegend = computed(() =>
  props.mode === "pfa"
  && effectiveTab.value === "站点客流监测"
  && stationHeatmapEnabled.value
  && stationHeatMaxFlow.value > 0
);

// ===== 需求：站点客流OD曲线色阶——图例移到地图左下角（复用图例卡片+齿轮），并区分线宽 =====
// 色阶配置与最大OD客流由 ZDZL（站点右侧面板）共享：ZDZL 用配置给曲线着色/定宽并回报最大客流。
const odCurveScaleConfig = ref(createColorScaleConfig("YlOrRd", 5));
const odCurveMaxFlow = ref(0);
// ZDZL 回报当前OD客流分布（值数组），供左下角图例按分位数分档
const odCurveValues = shallowRef([]);
const showOdCurveScalePopover = ref(false);
provide("odCurveScaleConfig", odCurveScaleConfig);
provide("runMonitorOdCurveMaxFlow", odCurveMaxFlow);
provide("runMonitorOdCurveValues", odCurveValues);
const odCurveResolvedScale = computed(() => resolveColorScale(odCurveScaleConfig.value));
// 同断面色阶：排序只随 OD 分布变化重跑
const odCurveSortedValues = computed(() => sortFlowValues(odCurveValues.value));
const odCurveBreaks = computed(() => quantileBreaks(odCurveSortedValues.value, odCurveResolvedScale.value.thresholds, { assumeSorted: true }));

const odCurveLegendItems = computed(() =>
  buildValueLegendItems(odCurveResolvedScale.value.colors, odCurveBreaks.value, odCurveMaxFlow.value, flowValueLabel, odCurveResolvedScale.value.widths)
);

const showOdCurveLegend = computed(() =>
  props.mode === "pfa"
  && effectiveTab.value === "站点客流监测"
  && pfaStationSection.value === "od"
  && odCurveMaxFlow.value > 0
);

function buildStationHeatFeatureCollection(stations, context) {
  const coordByName = new Map();
  const modeByName = new Map();
  for (const feature of busNetworkCollections.stations?.features || []) {
    const name = String(feature?.properties?.facilityName || feature?.properties?.name || "");
    const lngLat = feature?.geometry?.coordinates;
    if (!name || !Array.isArray(lngLat)) continue;
    if (!coordByName.has(name)) coordByName.set(name, lngLat);
    if (feature?.properties?.type === "subway") modeByName.set(name, "metro");
    else if (!modeByName.has(name)) modeByName.set(name, "bus");
  }
  const wantsMetro = baseMapLineMode.value === "metro-network";
  // 真实密度口径：权重恒按“全网最大站点客流”归一，切换行政区只筛选显示的站点、不重新标定色阶，
  // 同一站点在任何区域视图下颜色一致
  const rows = [];
  const values = [];
  let maxFlow = 0;
  Object.entries(stations || {}).forEach(([name, station]) => {
    const stationName = String(name);
    const lngLat = coordByName.get(stationName);
    if (!lngLat) return;
    const isMetroStation = modeByName.get(stationName) === "metro";
    if (isMetroStation !== wantsMetro) return;
    const flow = sumHourlyFlowArray(station?.hourlyFlow);
    if (!(flow > 0)) return;
    if (flow > maxFlow) maxFlow = flow;
    values.push(flow); // 当前制式全网分布（不受行政区筛选影响），用于分位数断点
    // 选中行政区时只保留区内站点（但 maxFlow / 分布仍按全网统计）
    if (context && !lngLatInDisplayRange(lngLat, context)) return;
    rows.push({ lngLat, flow });
  });
  return {
    maxFlow,
    values,
    collection: {
      type: "FeatureCollection",
      features: rows.map((row, index) => ({
        type: "Feature",
        id: `station-heat-${index}`,
        geometry: { type: "Point", coordinates: row.lngLat },
        // 权重取该站全天客流占全网最大站点客流的比例，保底 0.05 让低客流站也有淡色晕
        properties: { weight: maxFlow > 0 ? Math.max(0.05, row.flow / maxFlow) : 0 },
      })),
    },
  };
}

// 蓝底面：全市=全部行政区面；选中行政区=该区面
function stationHeatBaseCollection(context) {
  const features = context?.feature
    ? [context.feature]
    : (adminDistrictCollection.value?.features || []);
  return { type: "FeatureCollection", features };
}

function refreshStationHeatSources() {
  if (!stationHeatmapEnabled.value) return;
  const context = activeDisplayRangeContext.value;
  setGeoJsonSourceData(RM_SOURCE_STATION_HEAT_BASE, stationHeatBaseCollection(context));
  if (stationHeatStations.value) {
    const { maxFlow, values, collection } = buildStationHeatFeatureCollection(stationHeatStations.value, context);
    stationHeatMaxFlow.value = maxFlow;
    stationHeatValues.value = values;
    setGeoJsonSourceData(RM_SOURCE_STATION_HEAT, collection);
  }
}

function ensureStationHeatData() {
  const modelName = selectModel.value?.name;
  if (!stationHeatmapEnabled.value || !modelName || !isModelReady.value) return;
  if (stationHeatModel === modelName) {
    refreshStationHeatSources();
    return;
  }
  const seq = ++stationHeatSeq;
  getCachedStationPanel(modelName)
    .then((data) => {
      if (seq !== stationHeatSeq || selectModel.value?.name !== modelName) return;
      if (!data?.stations) return;
      stationHeatModel = modelName;
      stationHeatStations.value = data.stations;
      refreshStationHeatSources();
      syncBaseMapLayerVisibility();
    })
    .catch(() => {});
}

watch(
  [stationHeatmapEnabled, effectiveTab, baseMapLineMode, busNetworkRevision, () => selectModel.value?.name, isModelReady, selectedDisplayRange, adminDistrictCollection],
  () => {
    ensureStationHeatData();
    syncBaseMapLayerVisibility();
  },
);

// ===== 需求2：线路客流监测 · 全部线路按“该线全天客流量”着色 =====
const RM_LAYER_LINE_FLOW = "rm-bus-network-lines-flow";
// 白色描边（casing）：垫在彩色客流线之下、略宽
const RM_LAYER_LINE_FLOW_CASING = "rm-bus-network-lines-flow-casing";
const lineFlowScale = ref(createColorScaleConfig("YlOrRd", 5));
const showLineFlowScalePopover = ref(false);
const showSegmentFlowScalePopover = ref(false);

// 与 XLZL 共用 routePanel 缓存整包：模型就绪即加载，
// 保证"总体客流变化"等首个 tab 一进来就按客流着色（原先只在线路客流监测 tab 才加载，
// 导致初次进入显示纯色、切过一次线路客流监测后才带颜色的不一致）
function ensureLineFlowPanel() {
  const modelName = selectModel.value?.name;
  if (!modelName || !isModelReady.value) return;
  if (lineFlowPanel.value && lineFlowPanelModel === modelName) return;
  const seq = ++lineFlowPanelSeq;
  const cached = peekCachedRoutePanel(modelName);
  if (cached?.routes) {
    lineFlowPanel.value = cached;
    lineFlowPanelModel = modelName;
    return;
  }
  getCachedRoutePanel(modelName)
    .then((data) => {
      if (seq !== lineFlowPanelSeq || selectModel.value?.name !== modelName) return;
      if (data?.routes) {
        lineFlowPanel.value = data;
        lineFlowPanelModel = modelName;
      }
    })
    .catch(() => {});
}

function sumHourlyFlowArray(values) {
  let total = 0;
  for (const value of Array.isArray(values) ? values : []) total += Number(value) || 0;
  return total;
}

// 每条线路全天客流：优先 panel.lineGroups（公交组 "bus::"+lineId 与地铁聚合组，上下行合并口径），
// 组缺失时回退 routes 中该 lineId 各方向 hourlyFlow 求和
const lineFlowById = computed(() => {
  const flows = new Map();
  const panel = lineFlowPanel.value;
  if (!panel) return flows;
  const routeIdToLineIds = new Map();
  Object.entries(panel.routes || {}).forEach(([key, item]) => {
    const lineId = String(item?.lineId ?? "") || String(key).split("::")[0] || "";
    if (!lineId) return;
    flows.set(lineId, (flows.get(lineId) || 0) + sumHourlyFlowArray(item?.hourlyFlow));
    const routeId = String(item?.routeId ?? "");
    if (routeId) {
      if (!routeIdToLineIds.has(routeId)) routeIdToLineIds.set(routeId, []);
      routeIdToLineIds.get(routeId).push(lineId);
    }
  });
  Object.entries(panel.lineGroups || {}).forEach(([key, group]) => {
    const total = sumHourlyFlowArray(group?.hourlyFlow);
    if (!(total > 0)) return;
    const lineIds = new Set();
    if (String(key).startsWith("bus::")) lineIds.add(String(key).slice(5));
    (Array.isArray(group?.routeIds) ? group.routeIds : []).forEach((routeId) => {
      (routeIdToLineIds.get(String(routeId)) || []).forEach((lineId) => lineIds.add(lineId));
    });
    lineIds.forEach((lineId) => flows.set(lineId, total));
  });
  return flows;
});

// 地铁线路 lineId 集合：由模型线路整包按制式判别（与右侧面板 XLZL 同一套 isMetroLine 口径）。
// 公交/地铁客流量级差一个数量级，分档色阶必须各算各的，否则公交全部落在最低档。
const metroLineIdSet = computed(() => {
  busNetworkRevision.value;
  const set = new Set();
  for (const line of busNetworkRawLines) {
    const lineId = line?.lineId != null ? String(line.lineId) : "";
    if (lineId && isMetroLine(line)) set.add(lineId);
  }
  return set;
});

// 全部线路客流值（按公交/地铁分开），用于分位数断点与最大值
const lineFlowValuesByMode = computed(() => {
  const metroIds = metroLineIdSet.value;
  const bus = [];
  const metro = [];
  lineFlowById.value.forEach((flow, lineId) => {
    if (!(flow > 0)) return;
    (metroIds.has(lineId) ? metro : bus).push(flow);
  });
  return { bus, metro };
});

const busMaxLineFlow = computed(() => lineFlowValuesByMode.value.bus.reduce((m, v) => Math.max(m, v), 0));
const metroMaxLineFlow = computed(() => lineFlowValuesByMode.value.metro.reduce((m, v) => Math.max(m, v), 0));

const lineFlowResolvedScale = computed(() => resolveColorScale(lineFlowScale.value));

// 同断面色阶：排序只随各制式客流分布变化重跑
const lineFlowSortedValuesByMode = computed(() => ({
  bus: sortFlowValues(lineFlowValuesByMode.value.bus),
  metro: sortFlowValues(lineFlowValuesByMode.value.metro),
}));

// 分位数断点：由各制式的线路客流分布计算（各档线路条数大致均匀）
const busLineFlowBreaks = computed(() => quantileBreaks(lineFlowSortedValuesByMode.value.bus, lineFlowResolvedScale.value.thresholds, { assumeSorted: true }));
const metroLineFlowBreaks = computed(() => quantileBreaks(lineFlowSortedValuesByMode.value.metro, lineFlowResolvedScale.value.thresholds, { assumeSorted: true }));

function buildLineFlowMatchExpression(includeMetro, breaks) {
  const flows = lineFlowById.value;
  const metroIds = metroLineIdSet.value;
  if (!flows.size || !breaks.length) return null;
  const { colors } = lineFlowResolvedScale.value;
  const expression = ["match", ["to-string", ["get", "lineId"]]];
  flows.forEach((flow, lineId) => {
    if (!lineId || metroIds.has(lineId) !== includeMetro) return;
    expression.push(lineId, colors[classifyByBreaks(flow, breaks)]);
  });
  if (expression.length <= 2) return null;
  expression.push(colors[0]);
  return expression;
}

// 需求：每档离散色阶按客流分档区分线宽（客流越大越粗，系数收敛避免失衡）。
// lineId → 该线所在档位的线宽系数（match 表达式，供 line-width 乘算）。
function buildLineFlowWidthFactorExpression(includeMetro, breaks) {
  const flows = lineFlowById.value;
  const metroIds = metroLineIdSet.value;
  if (!flows.size || !breaks.length) return null;
  const { widths } = lineFlowResolvedScale.value;
  const expression = ["match", ["to-string", ["get", "lineId"]]];
  flows.forEach((flow, lineId) => {
    if (!lineId || metroIds.has(lineId) !== includeMetro) return;
    expression.push(lineId, widths[classifyByBreaks(flow, breaks)] || 1);
  });
  if (expression.length <= 2) return null;
  expression.push(widths[0] || 1);
  return expression;
}

// match 表达式：lineId → 分档颜色；routePanel 未就绪时为 null（保持现有颜色）
const lineFlowColorExpression = computed(() => buildLineFlowMatchExpression(false, busLineFlowBreaks.value));
const metroLineFlowColorExpression = computed(() => buildLineFlowMatchExpression(true, metroLineFlowBreaks.value));
const lineFlowWidthFactorExpression = computed(() => buildLineFlowWidthFactorExpression(false, busLineFlowBreaks.value));
const metroLineWidthFactorExpression = computed(() => buildLineFlowWidthFactorExpression(true, metroLineFlowBreaks.value));

function flowValueLabel(value) {
  return `${Math.round(Number(value) || 0).toLocaleString()} 人次`;
}

const lineFlowLegendItems = computed(() =>
  buildValueLegendItems(lineFlowResolvedScale.value.colors, busLineFlowBreaks.value, busMaxLineFlow.value, flowValueLabel, lineFlowResolvedScale.value.widths)
);

const metroFlowLegendItems = computed(() =>
  buildValueLegendItems(lineFlowResolvedScale.value.colors, metroLineFlowBreaks.value, metroMaxLineFlow.value, flowValueLabel, lineFlowResolvedScale.value.widths)
);

const lineSelectionActiveState = computed(() =>
  effectiveTab.value === "线路客流监测"
  && Boolean(
    selectedLineKey.value
    || selectedRouteDetail.value?.routeId
    || selectedLineName.value
    || selectedRouteMapLinks.value?.length
  )
);

// 总体客流变化与线路客流监测共用同一套线路客流着色，图例与色阶设置同步展示
const showLineFlowLegend = computed(() =>
  (effectiveTab.value === "线路客流监测" || effectiveTab.value === "总体客流变化")
  && baseMapLineMode.value === "bus-network"
  && !lineSelectionActiveState.value
  && Boolean(lineFlowColorExpression.value)
);

// 地铁线网模式：图例换用地铁自己的客流分档（与公交分开归一）
const showMetroFlowLegend = computed(() =>
  (effectiveTab.value === "线路客流监测" || effectiveTab.value === "总体客流变化")
  && baseMapLineMode.value === "metro-network"
  && !lineSelectionActiveState.value
  && Boolean(metroLineFlowColorExpression.value)
);

const showSegmentFlowLegend = computed(() =>
  props.mode === "pfa"
  && effectiveTab.value === "线路客流监测"
  && pfaLineSection.value === "segments"
  && lineSelectionActiveState.value
  && pfaSegmentMaxFlow.value > 0
);

const activeMapLegendItems = computed(() => {
  if (showOdCurveLegend.value) return odCurveLegendItems.value;
  if (showStationHeatLegend.value) return stationHeatLegendItems.value;
  if (showSegmentFlowLegend.value) return segmentFlowLegendItems.value;
  return showMetroFlowLegend.value ? metroFlowLegendItems.value : lineFlowLegendItems.value;
});

// 图例色块高度按该档线宽系数变化（客流越大越粗），无系数时用默认高度
function legendSwatchHeight(width) {
  if (!width) return "10px";
  return `${Math.round(6 + (Number(width) - 1) * 6)}px`;
}
const pfaSegmentLayerOpacity = computed(() =>
  Math.max(0, Math.min(1, (Number(pfaSegmentOpacity.value) || 0) / 100))
);
const busNetworkLoading = ref(false);
const busNetworkError = ref("");
let busNetworkRequestSeq = 0;
let routePickRequestSeq = 0;
let busNetworkClickListenerId = null;
let monitorRoadLayer = null;
let monitorBusRouteLayer = null;
let monitorSelectedRouteGlowLayer = null;
let monitorSelectedRouteLayer = null;
let monitorSelectedRouteSegmentLayer = null;
let monitorReverseRouteGlowLayer = null;
let monitorReverseRouteLayer = null;
// 需求11：下行（反向）断面客流图层，与上行断面层共用同一套色阶 stops
let monitorReverseRouteSegmentLayer = null;
let monitorTransferRouteGlowLayer = null;
let monitorTransferRouteLayer = null;
let pfaSegmentStyleFrameId = null;

function shouldLoadTransitNetworkForCurrentTab(tab = effectiveTab.value) {
  return tab !== "车辆运行监测"
    && tab !== "轨迹演示"
    && tab !== "体检评估分析";
}

function pauseTransitNetworkTiles() {
  monitorBusRouteLayer?.hide();
  monitorRoadLayer?.hide();
}

function ensureTransitNetworkForCurrentTab() {
  if (!shouldLoadTransitNetworkForCurrentTab()) {
    pauseTransitNetworkTiles();
    return;
  }
  ensureMonitorBusRouteLayer();
  loadBusNetwork();
  if (baseMapLineMode.value === "road-network") {
    ensureMonitorRoadLayer();
  }
}
let busNetworkSourceRefs = new Map();
let busNetworkCollections = {
  lines: emptyFeatureCollection(),
  stations: emptyFeatureCollection(),
};
let busNetworkIndexes = createEmptyBusNetworkIndexes();

const busNetworkLineWidth = computed(() => Math.max(0.1, Math.min(2, Number(lineWidth.value) || 1.2)));
// 命中层不可见（opacity 0.001）但 GPU 仍按线宽光栅化整个线网：
// 低缩放全网视图下恒定 12px 是最大的单笔填充开销（整城线网 × 12px 的无效 overdraw）。
// 点击查询自带 ±7px 盒（queryBoxAround），低缩放收窄到 2-4px 后有效命中半径仍足够。
const busNetworkHitLineWidth = computed(() => {
  const fullWidth = Math.max(12, busNetworkLineWidth.value * 4);
  return [
    "interpolate",
    ["linear"],
    ["zoom"],
    8, 2,
    11, 4,
    13, fullWidth,
  ];
});
const busNetworkStationIconScale = computed(() => {
  const highZoomScale = stationSize.value / RM_STATION_ICON_SIZE;
  return [
    "interpolate",
    ["exponential", 1.25],
    ["zoom"],
    8,
    0.06,
    10,
    Math.max(0.08, highZoomScale * 0.18),
    12,
    highZoomScale * 0.32,
    14,
    highZoomScale * 0.68,
    16,
    highZoomScale * 1.08,
  ];
});
const selectedBusStationIconScale = computed(() => {
  const scale = busNetworkStationIconScale.value;
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
});

function emptyFeatureCollection() {
  return { type: "FeatureCollection", features: [] };
}

// 清空数据源时用共享单例：引用稳定才能命中 setGeoJsonSourceData 的引用短路，
// 反复"清空已空的源"不再触发 MapLibre 的 setData
const EMPTY_FEATURE_COLLECTION = emptyFeatureCollection();

// 热力层是否已置顶（新增图层或热力关闭后复位，避免每轮 sync 都 moveLayer 标脏层序）
let stationHeatLayersOnTop = false;

function normalizeLineFeatureCollection(collection) {
  const features = Array.isArray(collection?.features) ? collection.features : [];
  return {
    type: "FeatureCollection",
    features: features
      .map((feature, index) => normalizeBusFeature(feature, index, "line"))
      .filter((feature) => feature.geometry),
  };
}

function normalizeStationFeatureCollection(collection) {
  const features = Array.isArray(collection?.features) ? collection.features : [];
  return {
    type: "FeatureCollection",
    features: features
      .map((feature, index) => normalizeBusFeature(feature, index, "station"))
      .filter((feature) => feature.geometry),
  };
}

function normalizeBusFeature(feature, index, type) {
  const properties = { ...(feature?.properties || {}) };
  const id = String(
    properties._featureId ||
      properties._lineKey ||
      properties._stationKey ||
      feature?.id ||
      [properties.line_id, properties.dir, properties.route_id, properties.stop_id, index].filter(Boolean).join("-") ||
      `${type}-${index}`,
  );
  if (type === "line") {
    properties._lineKey = properties._lineKey || id;
  } else {
    properties._stationKey = properties._stationKey || id;
  }
  return {
    type: "Feature",
    id,
    geometry: feature?.geometry || null,
    properties,
  };
}

function setGeoJsonSourceData(sourceId, data, map = MapRef.value?.map) {
  const source = map?.getSource(sourceId);
  if (!source?.setData) return false;
  if (busNetworkSourceRefs.get(sourceId) === data) return false;
  source.setData(data);
  busNetworkSourceRefs.set(sourceId, data);
  return true;
}

function ensureBusNetworkSource(map, sourceId, data) {
  if (map.getSource(sourceId)) {
    setGeoJsonSourceData(sourceId, data, map);
    return;
  }
  map.addSource(sourceId, { type: "geojson", data });
  busNetworkSourceRefs.set(sourceId, data);
}

function addBusLayerBelowBuildings(map, layer) {
  const beforeId = MapRef.value?.buildingLayerId;
  if (beforeId && map.getLayer?.(beforeId)) {
    map.addLayer(layer, beforeId);
    return;
  }
  map.addLayer(layer);
}

async function ensureBusStationIcons(map) {
  await addMapImageOnce(map, RM_STATION_ICON_ID, busStationIconUrl, RM_STATION_ICON_SIZE);
  await addMapImageOnce(map, RM_METRO_STATION_ICON_ID, metroStationIconUrl, RM_STATION_ICON_SIZE);
  await addMapImageOnce(map, RM_STATION_HIGHLIGHT_ICON_ID, busStationHighlightIconUrl, RM_STATION_ICON_SIZE);
  await addMapImageOnce(map, RM_STATION_HIGHLIGHT_REVERSE_ICON_ID, busStationHighlightReverseIconUrl, RM_STATION_ICON_SIZE);
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

function busStationIconLayout(iconId = null, iconScale = busNetworkStationIconScale.value) {
  return {
    // 底图站点层不传 iconId：按站点制式选图标（地铁红圈 / 公交青绿圈）
    "icon-image": iconId || ["case", ["==", ["get", "type"], "subway"], RM_METRO_STATION_ICON_ID, RM_STATION_ICON_ID],
    "icon-size": iconScale,
    "icon-anchor": "center",
    "icon-allow-overlap": true,
    "icon-ignore-placement": true,
    "icon-padding": 2,
  };
}

function busStationLabelLayout() {
  return {
    "text-field": ["coalesce", ["get", "stop_name"], ["get", "name"], ["get", "facilityName"], ""],
    "text-size": ["interpolate", ["linear"], ["zoom"], 9, 10, 12, 12, 15, 14],
    "text-anchor": "left",
    "text-offset": [1.05, 0],
    "text-max-width": 10,
    "text-allow-overlap": true,
    "text-ignore-placement": true,
    "text-padding": 3,
  };
}

function busStationLabelPaint() {
  return {
    "text-color": "#1f3132",
    "text-opacity": isStationFeatureSelectionActive() ? 0 : ["interpolate", ["linear"], ["zoom"], 8, 0.72, 11, 0.92, 14, 1],
    "text-halo-color": "rgba(248, 251, 252, 0.94)",
    "text-halo-width": 1.5,
    "text-halo-blur": 0.4,
  };
}

function ensureBusNetworkLayers(map) {
  ensureBusNetworkSource(map, RM_SOURCE_LINES, busNetworkCollections.lines);
  ensureBusNetworkSource(map, RM_SOURCE_STATIONS, busNetworkCollections.stations);
  ensureBusNetworkSource(map, RM_SOURCE_SELECTED_STATION, emptyFeatureCollection());
  ensureBusNetworkSource(map, RM_SOURCE_REVERSE_SELECTED_STATION, emptyFeatureCollection());
  ensureBusNetworkSource(map, RM_SOURCE_SEGMENT_STATIONS, emptyFeatureCollection());
  ensureBusNetworkSource(map, RM_SOURCE_TRANSFER_STATIONS, emptyFeatureCollection());
  ensureBusNetworkSource(map, RM_SOURCE_TRANSFER_LINES, emptyFeatureCollection());
  ensureBusNetworkSource(map, RM_SOURCE_STATION_HEAT, emptyFeatureCollection());
  ensureBusNetworkSource(map, RM_SOURCE_STATION_HEAT_BASE, emptyFeatureCollection());
  ensureDisplayRangeLayer(map);

  // 热力图全域蓝底：显示范围行政区面铺专题图最低档蓝色，叠在热力层之下
  if (!map.getLayer(RM_LAYER_STATION_HEAT_BASE)) {
    map.addLayer({
      id: RM_LAYER_STATION_HEAT_BASE,
      type: "fill",
      source: RM_SOURCE_STATION_HEAT_BASE,
      layout: { visibility: "none" },
      paint: {
        "fill-color": "#4272ae",
        "fill-opacity": 0.85,
        "fill-outline-color": "rgba(255, 255, 255, 0.5)",
      },
    });
  }
  // 站点客流热力图（仿人群密度专题图配色，分档色阶随图例齿轮设置联动）
  if (!map.getLayer(RM_LAYER_STATION_HEAT)) {
    map.addLayer({
      id: RM_LAYER_STATION_HEAT,
      type: "heatmap",
      source: RM_SOURCE_STATION_HEAT,
      layout: { visibility: "none" },
      paint: {
        "heatmap-weight": ["coalesce", ["get", "weight"], 0],
        // 真实密度口径：强度不随缩放变化；核半径按 2^zoom 精确翻倍（地面尺度恒定 ≈800m，
        // 广州纬度 z8≈1.4px → z16≈364px），缩放时热力形态不变
        "heatmap-intensity": 1,
        "heatmap-radius": ["interpolate", ["exponential", 2], ["zoom"], 8, 1.42, 16, 364],
        "heatmap-opacity": 0.82,
        "heatmap-color": stationHeatColorExpression.value,
      },
    });
  }

  if (!map.getLayer(RM_LAYER_LINES)) {
    addBusLayerBelowBuildings(map, {
      id: RM_LAYER_LINES,
      type: "line",
      source: RM_SOURCE_LINES,
      layout: { "line-join": "round", "line-cap": "round" },
      paint: {
        "line-color": MAP_THEME.network.line,
        "line-opacity": 0.001,
        "line-width": busNetworkHitLineWidth.value,
      },
    });
  }
  // 需求2：线路按全天客流着色的可见图层（与命中测试层同源；routePanel 就绪且未选中线路时显示）
  // 白色描边（casing）垫在彩色层之下、略宽，让密集线网中每条线更清晰
  if (!map.getLayer(RM_LAYER_LINE_FLOW_CASING)) {
    addBusLayerBelowBuildings(map, {
      id: RM_LAYER_LINE_FLOW_CASING,
      type: "line",
      source: RM_SOURCE_LINES,
      filter: BUS_LINE_FILTER,
      // 低缩放全网视图下线路密集重叠，白色描边只增噪声却让整网填充量翻倍，zoom≥11 才启用
      minzoom: 11,
      layout: { "line-join": "round", "line-cap": "round", visibility: "none" },
      paint: {
        "line-color": "#ffffff",
        "line-opacity": 0.55,
        "line-width": lineFlowLayerWidthExpression(null, LINE_FLOW_STROKE_PX * 2),
      },
    });
  }
  if (!map.getLayer(RM_LAYER_LINE_FLOW)) {
    addBusLayerBelowBuildings(map, {
      id: RM_LAYER_LINE_FLOW,
      type: "line",
      source: RM_SOURCE_LINES,
      filter: BUS_LINE_FILTER,
      layout: { "line-join": "round", "line-cap": "round", visibility: "none" },
      paint: {
        "line-color": MAP_THEME.network.line,
        "line-opacity": 0.9,
        "line-width": lineFlowLayerWidthExpression(),
      },
    });
  }
  // 地铁线网：白色描边 + 彩色粗线（按地铁自身客流分档着色）+ 白色短虚线叠加，铁路制式线形
  if (!map.getLayer(RM_LAYER_METRO_LINES_CASING)) {
    addBusLayerBelowBuildings(map, {
      id: RM_LAYER_METRO_LINES_CASING,
      type: "line",
      source: RM_SOURCE_LINES,
      filter: METRO_LINE_FILTER,
      layout: { "line-join": "round", "line-cap": "round", visibility: "none" },
      paint: {
        "line-color": "#ffffff",
        "line-opacity": 0.7,
        "line-width": metroLineWidthExpression(null, METRO_LINE_STROKE_PX * 2),
      },
    });
  }
  if (!map.getLayer(RM_LAYER_METRO_LINES)) {
    addBusLayerBelowBuildings(map, {
      id: RM_LAYER_METRO_LINES,
      type: "line",
      source: RM_SOURCE_LINES,
      filter: METRO_LINE_FILTER,
      layout: { "line-join": "round", "line-cap": "round", visibility: "none" },
      paint: {
        "line-color": metroLineFlowColorExpression.value || METRO_FALLBACK_LINE_COLOR,
        "line-opacity": 0.92,
        "line-width": metroLineWidthExpression(),
      },
    });
  }
  if (!map.getLayer(RM_LAYER_METRO_LINE_DASH)) {
    addBusLayerBelowBuildings(map, {
      id: RM_LAYER_METRO_LINE_DASH,
      type: "line",
      source: RM_SOURCE_LINES,
      filter: METRO_LINE_FILTER,
      layout: { "line-join": "round", visibility: "none" },
      paint: {
        "line-color": "#ffffff",
        "line-opacity": 0.9,
        "line-width": metroDashWidthExpression(),
        "line-dasharray": [2.2, 2.8],
      },
    });
  }
  if (!map.getLayer(RM_LAYER_STATIONS)) {
    map.addLayer({
      id: RM_LAYER_STATIONS,
      type: "symbol",
      source: RM_SOURCE_STATIONS,
      layout: busStationIconLayout(),
      paint: { "icon-opacity": 0.96 },
    });
  }
  if (!map.getLayer(RM_LAYER_STATION_SEGMENT_RING)) {
    // 白底空心圈：描边取该站断面客流分档加深色
    map.addLayer({
      id: RM_LAYER_STATION_SEGMENT_RING,
      type: "circle",
      source: RM_SOURCE_SEGMENT_STATIONS,
      layout: { visibility: "none" },
      paint: {
        "circle-radius": ["interpolate", ["linear"], ["zoom"], 10, 3.2, 13, 5.2, 16, 8],
        "circle-color": "#ffffff",
        "circle-opacity": 1,
        "circle-stroke-color": segmentStationStrokeExpression.value || SEGMENT_RING_FALLBACK_STROKE,
        "circle-stroke-width": 2.5,
        "circle-stroke-opacity": 1,
      },
    });
  }
  if (!map.getLayer(RM_LAYER_SEGMENT_STATION_LABELS)) {
    map.addLayer({
      id: RM_LAYER_SEGMENT_STATION_LABELS,
      type: "symbol",
      source: RM_SOURCE_SEGMENT_STATIONS,
      layout: {
        ...busStationLabelLayout(),
        visibility: "none",
        // 碰撞隐藏，避免全线站名互相压盖
        "text-allow-overlap": false,
        "text-ignore-placement": false,
      },
      paint: {
        "text-color": "#1f3132",
        "text-opacity": 1,
        "text-halo-color": "rgba(248, 251, 252, 0.94)",
        "text-halo-width": 1.5,
        "text-halo-blur": 0.4,
      },
    });
  }
  if (!map.getLayer(RM_LAYER_STATION_LABELS)) {
    map.addLayer({
      id: RM_LAYER_STATION_LABELS,
      type: "symbol",
      source: RM_SOURCE_STATIONS,
      minzoom: 14,
      layout: busStationLabelLayout(),
      paint: busStationLabelPaint(),
    });
  }
  // 关联线路本体：蓝色实线（每条线一条 LineString），叠在换乘站点圈之下
  if (!map.getLayer(RM_LAYER_TRANSFER_LINES)) {
    addBusLayerBelowBuildings(map, {
      id: RM_LAYER_TRANSFER_LINES,
      type: "line",
      source: RM_SOURCE_TRANSFER_LINES,
      layout: { "line-join": "round", "line-cap": "round", visibility: "none" },
      paint: {
        "line-color": PFA_RELATED_LINE_COLOR,
        "line-opacity": 0.82,
        "line-width": ["interpolate", ["linear"], ["zoom"], 8, 1.4, 12, 2.4, 14, 3.4, 16, 4.4],
      },
    });
  }
  // 关联线路模式的换乘站点：空心圈描边取关联线路蓝色的加深色，白色填充
  if (!map.getLayer(RM_LAYER_TRANSFER_STATION_RING)) {
    map.addLayer({
      id: RM_LAYER_TRANSFER_STATION_RING,
      type: "circle",
      source: RM_SOURCE_TRANSFER_STATIONS,
      layout: { visibility: "none" },
      paint: {
        "circle-radius": ["interpolate", ["linear"], ["zoom"], 10, 3.2, 13, 5.2, 16, 8],
        "circle-color": "#ffffff",
        "circle-opacity": 1,
        "circle-stroke-color": "#0d3f85",
        "circle-stroke-width": 2.5,
        "circle-stroke-opacity": 1,
      },
    });
  }
  if (!map.getLayer(RM_LAYER_TRANSFER_STATION_LABELS)) {
    map.addLayer({
      id: RM_LAYER_TRANSFER_STATION_LABELS,
      type: "symbol",
      source: RM_SOURCE_TRANSFER_STATIONS,
      layout: {
        ...busStationLabelLayout(),
        visibility: "none",
        "text-allow-overlap": false,
        "text-ignore-placement": false,
      },
      paint: {
        "text-color": "#1f3132",
        "text-opacity": 1,
        "text-halo-color": "rgba(248, 251, 252, 0.94)",
        "text-halo-width": 1.5,
        "text-halo-blur": 0.4,
      },
    });
  }
  if (!map.getLayer(RM_LAYER_STATION_SELECTED)) {
    map.addLayer({
      id: RM_LAYER_STATION_SELECTED,
      type: "symbol",
      source: RM_SOURCE_SELECTED_STATION,
      layout: busStationIconLayout(RM_STATION_HIGHLIGHT_ICON_ID, selectedBusStationIconScale.value),
      paint: { "icon-opacity": 1 },
    });
  }
  if (!map.getLayer(RM_LAYER_STATION_REVERSE_SELECTED)) {
    map.addLayer({
      id: RM_LAYER_STATION_REVERSE_SELECTED,
      type: "symbol",
      source: RM_SOURCE_REVERSE_SELECTED_STATION,
      layout: busStationIconLayout(RM_STATION_HIGHLIGHT_REVERSE_ICON_ID, selectedBusStationIconScale.value),
      paint: { "icon-opacity": 1 },
    });
  }
  if (!map.getLayer(RM_LAYER_STATION_SELECTED_HALO)) {
    map.addLayer({
      id: RM_LAYER_STATION_SELECTED_HALO,
      type: "circle",
      source: RM_SOURCE_SELECTED_STATION,
      paint: {
        "circle-radius": ["interpolate", ["linear"], ["zoom"], 9, 5, 12, 8, 15, 13, 17, 20],
        "circle-color": "rgba(249, 115, 22, 0)",
        "circle-stroke-color": "#f97316",
        "circle-stroke-width": ["interpolate", ["linear"], ["zoom"], 9, 1.2, 13, 2.4, 16, 4.2],
        "circle-opacity": 0.96,
        "circle-stroke-opacity": 0.96,
      },
    });
  }
  if (!map.getLayer(RM_LAYER_STATION_REVERSE_SELECTED_HALO)) {
    map.addLayer({
      id: RM_LAYER_STATION_REVERSE_SELECTED_HALO,
      type: "circle",
      source: RM_SOURCE_REVERSE_SELECTED_STATION,
      paint: {
        "circle-radius": ["interpolate", ["linear"], ["zoom"], 9, 5, 12, 8, 15, 13, 17, 20],
        "circle-color": "rgba(21, 105, 222, 0)",
        "circle-stroke-color": "#1569de",
        "circle-stroke-width": ["interpolate", ["linear"], ["zoom"], 9, 1.2, 13, 2.4, 16, 4.2],
        "circle-opacity": 0.94,
        "circle-stroke-opacity": 0.94,
      },
    });
  }
  // 本轮可能有 addLayer 追加到栈顶（会盖到热力层之上），置顶标志复位、下轮 sync 重新置顶
  stationHeatLayersOnTop = false;
  applyBusNetworkPaint();
  syncBaseMapLayerVisibility();
}

function displayRangeOutlineCollection(context = activeDisplayRangeContext.value) {
  const geometry = displayRangeOutlineGeometry(context?.feature?.geometry);
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

function displayRangeOutlineGeometry(geometry) {
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

function ensureDisplayRangeLayer(map = MapRef.value?.map) {
  if (!map) return;
  ensureBusNetworkSource(map, RM_SOURCE_DISPLAY_RANGE, displayRangeOutlineCollection());
  if (!map.getLayer(RM_LAYER_DISPLAY_RANGE_OUTLINE)) {
    map.addLayer({
      id: RM_LAYER_DISPLAY_RANGE_OUTLINE,
      type: "line",
      source: RM_SOURCE_DISPLAY_RANGE,
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
  setBusLayerVisibility(map, RM_LAYER_DISPLAY_RANGE_OUTLINE, Boolean(activeDisplayRangeContext.value));
}

function setBusLayerVisibility(map, layerId, visible) {
  if (map?.getLayer?.(layerId)) {
    map.setLayoutProperty(layerId, "visibility", visible ? "visible" : "none");
  }
}

function setBusLayerFilter(map, layerId, filter = null) {
  if (map?.getLayer?.(layerId)) {
    map.setFilter(layerId, filter);
  }
}

// 需求2：客流着色图层的线宽（随线宽设置与缩放联动）
// 描边宽度：彩色线两侧各留约这么多像素白边（casing 层比彩色层宽 2*STROKE）
const LINE_FLOW_STROKE_PX = 1.4;
const METRO_LINE_STROKE_PX = 1.8;

// 把每个 zoom 档的基础宽度按"分档线宽系数"（客流越大越粗）乘算，可选再加描边像素。
function widthStop(baseWidth, factorExpr, extra) {
  let out = factorExpr ? ["*", baseWidth, factorExpr] : baseWidth;
  if (extra) out = ["+", out, extra];
  return out;
}

function lineFlowLayerWidthExpression(factorExpr = null, extra = 0) {
  const width = busNetworkLineWidth.value;
  return [
    "interpolate",
    ["linear"],
    ["zoom"],
    8, widthStop(Math.max(0.6, width * 0.7), factorExpr, extra),
    11, widthStop(Math.max(1, width * 1.3), factorExpr, extra),
    14, widthStop(Math.max(1.8, width * 2.4), factorExpr, extra),
    16, widthStop(Math.max(2.6, width * 3.4), factorExpr, extra),
  ];
}

// 地铁线宽：明显粗于公交细线，配合白色虚线叠加构成铁路制式线形
function metroLineWidthExpression(factorExpr = null, extra = 0) {
  const width = busNetworkLineWidth.value;
  return [
    "interpolate",
    ["linear"],
    ["zoom"],
    8, widthStop(Math.max(1.5, width * 1.7), factorExpr, extra),
    11, widthStop(Math.max(2.4, width * 2.8), factorExpr, extra),
    14, widthStop(Math.max(4, width * 4.6), factorExpr, extra),
    16, widthStop(Math.max(5.6, width * 6), factorExpr, extra),
  ];
}

function metroDashWidthExpression() {
  const width = busNetworkLineWidth.value;
  return [
    "interpolate",
    ["linear"],
    ["zoom"],
    8, Math.max(0.5, width * 0.6),
    11, Math.max(0.9, width * 1),
    14, Math.max(1.4, width * 1.7),
    16, Math.max(2, width * 2.2),
  ];
}

// 把 lineId→颜色 / lineId→分档线宽系数 应用到公交/地铁客流着色图层及其白色描边层。
// 彩色线按分档系数变粗（客流越大越粗），描边层同系数但更宽以形成白边。
function applyLineFlowLayerStyles(map) {
  if (!map) return;
  const busFactor = lineFlowWidthFactorExpression.value;
  const metroFactor = metroLineWidthFactorExpression.value;
  if (map.getLayer?.(RM_LAYER_LINE_FLOW)) {
    if (lineFlowColorExpression.value) map.setPaintProperty(RM_LAYER_LINE_FLOW, "line-color", lineFlowColorExpression.value);
    map.setPaintProperty(RM_LAYER_LINE_FLOW, "line-width", lineFlowLayerWidthExpression(busFactor));
  }
  if (map.getLayer?.(RM_LAYER_LINE_FLOW_CASING)) {
    map.setPaintProperty(RM_LAYER_LINE_FLOW_CASING, "line-width", lineFlowLayerWidthExpression(busFactor, LINE_FLOW_STROKE_PX * 2));
  }
  if (map.getLayer?.(RM_LAYER_METRO_LINES)) {
    map.setPaintProperty(RM_LAYER_METRO_LINES, "line-color", metroLineFlowColorExpression.value || METRO_FALLBACK_LINE_COLOR);
    map.setPaintProperty(RM_LAYER_METRO_LINES, "line-width", metroLineWidthExpression(metroFactor));
  }
  if (map.getLayer?.(RM_LAYER_METRO_LINES_CASING)) {
    map.setPaintProperty(RM_LAYER_METRO_LINES_CASING, "line-width", metroLineWidthExpression(metroFactor, METRO_LINE_STROKE_PX * 2));
  }
  if (map.getLayer?.(RM_LAYER_METRO_LINE_DASH)) {
    map.setPaintProperty(RM_LAYER_METRO_LINE_DASH, "line-width", metroDashWidthExpression());
  }
}

// 需求2：着色表达式变化时重着色
function applyLineFlowColoring() {
  applyLineFlowLayerStyles(MapRef.value?.map);
  syncBaseMapLayerVisibility();
}

function applyBusNetworkPaint() {
  const map = MapRef.value?.map;
  if (!map) return;
  if (map.getLayer(RM_LAYER_LINES)) {
    map.setPaintProperty(RM_LAYER_LINES, "line-width", busNetworkHitLineWidth.value);
    map.setPaintProperty(RM_LAYER_LINES, "line-opacity", busLineOpacityPaint());
  }
  applyLineFlowLayerStyles(map);
  if (map.getLayer(RM_LAYER_STATIONS)) {
    map.setLayoutProperty(RM_LAYER_STATIONS, "icon-size", busNetworkStationIconScale.value);
    map.setPaintProperty(RM_LAYER_STATIONS, "icon-opacity", busStationOpacityPaint());
  }
  if (map.getLayer(RM_LAYER_STATION_LABELS)) {
    map.setPaintProperty(RM_LAYER_STATION_LABELS, "text-opacity", busStationLabelPaint()["text-opacity"]);
  }
  applySelectedLineStationFilter(map);
  if (map.getLayer(RM_LAYER_STATION_SELECTED)) {
    map.setLayoutProperty(RM_LAYER_STATION_SELECTED, "icon-image", RM_STATION_HIGHLIGHT_ICON_ID);
    map.setLayoutProperty(RM_LAYER_STATION_SELECTED, "icon-size", selectedBusStationIconScale.value);
  }
  if (map.getLayer(RM_LAYER_STATION_REVERSE_SELECTED)) {
    map.setLayoutProperty(RM_LAYER_STATION_REVERSE_SELECTED, "icon-image", RM_STATION_HIGHLIGHT_REVERSE_ICON_ID);
    map.setLayoutProperty(RM_LAYER_STATION_REVERSE_SELECTED, "icon-size", selectedBusStationIconScale.value);
  }
  syncMonitorRouteLineWidths();
  applyBusNetworkFocus();
}

function busLineOpacityPaint() {
  // 真实线路由模型二进制瓦片图层绘制；此层只负责命中测试。
  return 0.001;
}

function isLineSelectionActive() {
  return effectiveTab.value === "线路客流监测"
    && Boolean(
      selectedLineKey.value
      || selectedRouteDetail.value?.routeId
      || selectedLineName.value
      || selectedRouteMapLinks.value?.length
    );
}

function isPfaLineSelectionActive() {
  return props.mode === "pfa"
    && isLineSelectionActive();
}

// 断面客流(segments)：单方向断面着色 + 空心圈站点（站点乘降不再复用此展示）
function isPfaSegmentSectionActive() {
  return props.mode === "pfa"
    && effectiveTab.value === "线路客流监测"
    && pfaLineSection.value === "segments";
}

// 站点乘降(boarding)：选中线路画单一蓝色线（同客流画像蓝线样式）+ 站间OD曲线，不做断面着色
function isPfaBoardingSectionActive() {
  return props.mode === "pfa"
    && effectiveTab.value === "线路客流监测"
    && pfaLineSection.value === "boarding";
}

// 关联线路模式：地图只显示关联线路与换乘站点，不再绘制选中线路本体
function isPfaTransferSectionActive() {
  return props.mode === "pfa"
    && effectiveTab.value === "线路客流监测"
    && pfaLineSection.value === "transfer";
}

function selectedStationFeatureKeys() {
  return [selectedStationKey.value, selectedReverseStationKey.value]
    .map((key) => String(key || ""))
    .filter(Boolean);
}

function isStationFeatureSelectionActive() {
  return effectiveTab.value === "站点客流监测"
    && selectedStationFeatureKeys().length > 0;
}

function busStationOpacityPaint() {
  return isStationFeatureSelectionActive() ? 0 : 0.96;
}

function selectedRouteFacilities() {
  const detailFacilities = selectedRouteDetail.value?.facilities;
  if (Array.isArray(detailFacilities) && detailFacilities.length) return detailFacilities;
  const targetRouteId = String(selectedRouteDetail.value?.routeId || "");
  const targetKey = String(selectedLineKey.value || "");
  if (!targetRouteId && !targetKey) return [];
  return (targetRouteId && busNetworkIndexes.routeFacilitiesByRouteId.get(targetRouteId))
    || (targetKey && busNetworkIndexes.routeFacilitiesByKey.get(targetKey))
    || [];
}

function selectedLineStationFilterExpression() {
  if (!isLineSelectionActive()) return null;
  // 全市（无行政区上下文）时也要按选中线路过滤，否则断面模式会把全网站点都放出来
  const context = activeDisplayRangeContext.value;
  const facilities = selectedRouteFacilities()
    .filter((facility) => !context || modelCoordInDisplayRange(facility?.coord || facility, context));
  if (!facilities.length) return ["==", ["literal", 1], 0];
  const ids = Array.from(new Set(
    facilities
      .map((facility) => String(facility?.facilityId || ""))
      .filter(Boolean)
  ));
  const names = Array.from(new Set(
    facilities
      .map((facility) => String(facility?.facilityName || ""))
      .filter(Boolean)
  ));
  // 只按 facilityId 精确匹配（同名的对向站点 facilityId 不同，用名称兜底会把对向站点也放出来）；
  // 仅当该方向站点全部缺 id 时才退回名称匹配
  const filters = [];
  if (ids.length) {
    filters.push(["in", ["to-string", ["get", "facilityId"]], ["literal", ids]]);
  } else if (names.length) {
    filters.push(["in", ["to-string", ["get", "name"]], ["literal", names]]);
  }
  return filters.length ? ["any", ...filters] : ["==", ["literal", 1], 0];
}

// 底图站点的制式过滤：公交模式只显示公交站，地铁模式只显示地铁站
function stationModeFilterExpression() {
  return baseMapLineMode.value === "metro-network"
    ? ["==", ["get", "type"], "subway"]
    : ["!=", ["get", "type"], "subway"];
}

function applySelectedLineStationFilter(map = MapRef.value?.map) {
  if (!map) return;
  // 选中线路时按该线站点过滤（选中线路的站点不再分制式）；未选中时按公交/地铁模式过滤
  const filter = selectedLineStationFilterExpression() || stationModeFilterExpression();
  setBusLayerFilter(map, RM_LAYER_STATIONS, filter);
  setBusLayerFilter(map, RM_LAYER_STATION_LABELS, filter);
}

// 选中聚焦与数据管理保持一致：高亮对象由独立图层绘制，选中后隐藏其他底图对象。
function applyBusNetworkFocus() {
  const map = MapRef.value?.map;
  if (!map) return;
  if (map.getLayer(RM_LAYER_LINES)) {
    map.setPaintProperty(RM_LAYER_LINES, "line-opacity", busLineOpacityPaint());
  }
  if (map.getLayer(RM_LAYER_STATIONS)) {
    map.setPaintProperty(RM_LAYER_STATIONS, "icon-opacity", busStationOpacityPaint());
  }
  if (map.getLayer(RM_LAYER_STATION_LABELS)) {
    map.setPaintProperty(RM_LAYER_STATION_LABELS, "text-opacity", busStationLabelPaint()["text-opacity"]);
  }
  if (monitorBusRouteLayer) {
    monitorBusRouteLayer.setOpacity(
      isLineSelectionActive()
        ? 0
        : RM_BASE_LINE_OPACITY
    );
  }
}

// 全文件约 19 个调用点、多处在同一 flush 内连环触发（每轮约 25 次 getLayer/setLayoutProperty/setFilter）：
// 微任务级去重，同一批变更只真正执行一次；MapLibre 的样式应用本身是下一帧生效，延迟到微任务不可感知
let baseMapVisibilitySyncScheduled = false;
function syncBaseMapLayerVisibility() {
  if (baseMapVisibilitySyncScheduled) return;
  baseMapVisibilitySyncScheduled = true;
  queueMicrotask(() => {
    baseMapVisibilitySyncScheduled = false;
    syncBaseMapLayerVisibilityNow();
  });
}

function syncBaseMapLayerVisibilityNow() {
  const map = MapRef.value?.map;
  if (!map) return;
  const showBusNetwork = baseMapLineMode.value === "bus-network";
  const showMetroNetwork = baseMapLineMode.value === "metro-network";
  // 公交/地铁线网互斥显示，选中高亮等叠加层二者共用
  const showTransitNetwork = showBusNetwork || showMetroNetwork;
  // 线路客流监测：地图只显示线路；站点客流监测：只显示站点；总体客流变化不显示站点；车辆运行监测两者都不显示。
  const tab = effectiveTab.value;
  const isVehicleTab = tab === "车辆运行监测" || tab === "轨迹演示";
  const isHealthAssessmentTab = tab === "体检评估分析";
  const isLinesTab = !isVehicleTab && !isHealthAssessmentTab && tab !== "站点客流监测";
  const showLines = showBusNetwork && isLinesTab;
  const showMetroLines = showMetroNetwork && isLinesTab;
  // 断面客流/站点乘降：随选中线路显示当前方向的站点（空心圈）与站名，经 applySelectedLineStationFilter 过滤
  const segmentStationsActive = showTransitNetwork && isPfaSegmentSectionActive() && isPfaLineSelectionActive();
  // 关联线路模式：只显示关联线路与换乘站点
  const transferStationsActive = showTransitNetwork && isPfaTransferSectionActive() && isPfaLineSelectionActive();
  // 站点客流热力图开启时按专题图口径展示：隐藏路网/公交线网/站点
  const stationHeatActive = props.mode === "pfa" && tab === "站点客流监测" && stationHeatmapEnabled.value;
  const showStations = showTransitNetwork
    && !isVehicleTab
    && !isHealthAssessmentTab
    && tab === "站点客流监测"
    && !stationHeatActive;
  const hideBaseLines = isLineSelectionActive();
  setBusLayerVisibility(map, RM_LAYER_LINES, (showLines || showMetroLines) && !hideBaseLines);
  // 命中测试层跟随当前制式过滤，点选只命中当前显示的线网
  setBusLayerFilter(map, RM_LAYER_LINES, showMetroNetwork ? METRO_LINE_FILTER : BUS_LINE_FILTER);
  // 需求2：routePanel 就绪后底图线路改用客流着色图层。
  // 未就绪时不要显示默认蓝色瓦片线网，避免刷新首屏闪一下“未上色线网”。
  const lineFlowColoringActive = showLines && !hideBaseLines && Boolean(lineFlowColorExpression.value);
  setBusLayerVisibility(map, RM_LAYER_LINE_FLOW_CASING, lineFlowColoringActive);
  setBusLayerVisibility(map, RM_LAYER_LINE_FLOW, lineFlowColoringActive);
  // 地铁线网（几何来自模型线路整包，直接显示，不依赖瓦片）
  const metroLinesActive = showMetroLines && !hideBaseLines && Boolean(metroLineFlowColorExpression.value);
  setBusLayerVisibility(map, RM_LAYER_METRO_LINES_CASING, metroLinesActive);
  setBusLayerVisibility(map, RM_LAYER_METRO_LINES, metroLinesActive);
  setBusLayerVisibility(map, RM_LAYER_METRO_LINE_DASH, metroLinesActive);
  [RM_LAYER_STATIONS, RM_LAYER_STATION_SELECTED, RM_LAYER_STATION_REVERSE_SELECTED, RM_LAYER_STATION_SELECTED_HALO, RM_LAYER_STATION_REVERSE_SELECTED_HALO].forEach((layerId) => {
    setBusLayerVisibility(map, layerId, showStations);
  });
  setBusLayerVisibility(map, RM_LAYER_STATION_SEGMENT_RING, segmentStationsActive);
  setBusLayerVisibility(map, RM_LAYER_SEGMENT_STATION_LABELS, segmentStationsActive);
  setBusLayerVisibility(map, RM_LAYER_TRANSFER_LINES, transferStationsActive);
  setBusLayerVisibility(map, RM_LAYER_TRANSFER_STATION_RING, transferStationsActive);
  setBusLayerVisibility(map, RM_LAYER_TRANSFER_STATION_LABELS, transferStationsActive);
  setBusLayerVisibility(map, RM_LAYER_STATION_HEAT_BASE, stationHeatActive);
  setBusLayerVisibility(map, RM_LAYER_STATION_HEAT, stationHeatActive);
  // 热力图要盖在站点等所有要素之上：激活时把蓝底和热力层移到图层栈顶（顺序：蓝底在下、热力在上）。
  // moveLayer 即使位置不变也会标脏样式层序，用标志位保证激活期间只移一次
  if (stationHeatActive) {
    if (!stationHeatLayersOnTop) {
      if (map.getLayer(RM_LAYER_STATION_HEAT_BASE)) map.moveLayer(RM_LAYER_STATION_HEAT_BASE);
      if (map.getLayer(RM_LAYER_STATION_HEAT)) map.moveLayer(RM_LAYER_STATION_HEAT);
      stationHeatLayersOnTop = true;
    }
  } else {
    stationHeatLayersOnTop = false;
  }
  setBusLayerVisibility(map, RM_LAYER_STATION_LABELS, showStations);
  applySelectedLineStationFilter(map);
  if (monitorBusRouteLayer) {
    monitorBusRouteLayer.hide();
  }
  const showTransitLines = showLines || showMetroLines;
  const hideSelectedRouteBase = isPfaSegmentSectionActive() && isPfaLineSelectionActive();
  monitorSelectedRouteLayer?.setOpacity(0.95);
  monitorSelectedRouteGlowLayer?.setOpacity(0.42);
  monitorReverseRouteLayer?.setOpacity(0.88);
  monitorReverseRouteGlowLayer?.setOpacity(0.3);
  [monitorSelectedRouteGlowLayer, monitorSelectedRouteLayer, monitorReverseRouteGlowLayer, monitorReverseRouteLayer].forEach((layer) => {
    if (!layer) return;
    showTransitLines && !hideSelectedRouteBase ? layer.show() : layer.hide();
  });
  [monitorSelectedRouteSegmentLayer, monitorReverseRouteSegmentLayer].forEach((layer) => {
    if (!layer) return;
    showTransitLines && isPfaSegmentSectionActive() && isPfaLineSelectionActive()
      ? layer.show()
      : layer.hide();
  });
  // 关联线路本体改用 MapLibre GeoJSON（RM_LAYER_TRANSFER_LINES），deck 关联图层不再使用，恒隐藏
  [monitorTransferRouteGlowLayer, monitorTransferRouteLayer].forEach((layer) => layer?.hide());
  if (monitorRoadLayer) {
    showTransitNetwork || stationHeatActive ? monitorRoadLayer.hide() : monitorRoadLayer.show();
  }
}

// 模型坐标（web mercator）转 geojson 经纬度
function modelCoordToLngLat(coord) {
  const x = Number(coord?.x);
  const y = Number(coord?.y);
  if (!Number.isFinite(x) || !Number.isFinite(y)) return null;
  return webMercatorToLngLat(x, y);
}

// 由模型 getLineAll 数据构建线路 geojson：每条 route 一条 LineString，携带方向/线路标识，供点选与弹窗复用
function buildModelLineFeatureCollection(lines) {
  const features = [];
  for (const line of Array.isArray(lines) ? lines : []) {
    const lineId = line?.lineId != null ? String(line.lineId) : "";
    const transitMode = isMetroLine(line) ? "metro" : "bus";
    (line?.routes || []).forEach((route, idx) => {
      const coords = [];
      const links = route?.links || [];
      const geometry = Array.isArray(route?.geometry) ? route.geometry : [];
      if (geometry.length >= 2) {
        // 线路摘要缓存下发的抽稀真实路网走向（[x, y] Web Mercator 序列）
        for (const point of geometry) {
          const lngLat = Array.isArray(point)
            ? modelCoordToLngLat({ x: point[0], y: point[1] })
            : modelCoordToLngLat(point);
          if (lngLat) coords.push(lngLat);
        }
      } else if (links.length) {
        const first = modelCoordToLngLat(links[0]?.from);
        if (first) coords.push(first);
        for (const link of links) {
          const to = modelCoordToLngLat(link?.to);
          if (to) coords.push(to);
        }
      } else {
        // 兜底：无几何数据时退化为站点连线
        for (const facility of route?.facilities || []) {
          const point = modelCoordToLngLat(facility?.coord);
          if (point) coords.push(point);
        }
      }
      if (coords.length < 2) return;
      const routeId = route?.routeId != null ? String(route.routeId) : "";
      const lineName = String(line?.lineName || lineId || route?.routeName || routeId || "未命名线路");
      const facilities = Array.isArray(route?.facilities) ? route.facilities : [];
      const startName = facilities[0]?.facilityName || "";
      const endName = facilities[facilities.length - 1]?.facilityName || "";
      const directionName = startName && endName
        ? `${lineName}(${startName}--${endName})`
        : String(route?.routeName || lineName);
      features.push({
        type: "Feature",
        id: `${lineId}-${routeId || idx}`,
        geometry: { type: "LineString", coordinates: coords },
        properties: {
          lineName,
          lineId,
          routeId,
          routeName: route?.routeName || "",
          name: directionName,
          dir: idx,
          mode: transitMode,
          _lineKey: `${lineId}-${routeId || idx}`,
        },
      });
    });
  }
  return { type: "FeatureCollection", features };
}

// 地铁线路途经站点的 facilityId/站名集合：facilityAll 整包不带制式字段，
// 用线路整包反查，给站点要素打 type（与 ZDZL 整包替换时的 type 口径一致）。
function collectMetroFacilityKeys(lines) {
  const keys = new Set();
  for (const line of Array.isArray(lines) ? lines : []) {
    if (!isMetroLine(line)) continue;
    for (const route of Array.isArray(line?.routes) ? line.routes : []) {
      for (const facility of Array.isArray(route?.facilities) ? route.facilities : []) {
        if (facility?.facilityId != null) keys.add(String(facility.facilityId));
        else if (facility?.facilityName) keys.add(String(facility.facilityName));
      }
    }
  }
  return keys;
}

// 由模型 facilityAll 轻量缓存构建站点 geojson，避免等待整份线路详情。
function buildModelStationFeatureCollection(facilities, metroFacilityKeys = new Set()) {
  const seen = new Map();
  for (const fac of Array.isArray(facilities) ? facilities : []) {
    const name = fac?.facilityName;
    const lngLat = modelCoordToLngLat(fac?.coord);
    if (!name || !lngLat) continue;
    const key = fac?.facilityId != null ? String(fac.facilityId) : name;
    if (seen.has(key)) continue;
    seen.set(key, {
      type: "Feature",
      id: key,
      geometry: { type: "Point", coordinates: lngLat },
      properties: {
        facilityName: name,
        stop_name: name,
        name,
        facilityId: key,
        type: metroFacilityKeys.has(key) || metroFacilityKeys.has(name) ? "subway" : "bus",
        _stationKey: key,
      },
    });
  }
  return { type: "FeatureCollection", features: Array.from(seen.values()) };
}

function lngLatInDisplayRange(lngLat, context = activeDisplayRangeContext.value) {
  if (!context) return true;
  return pointInDistrictContext(lngLat, context);
}

function modelCoordInDisplayRange(coord, context = activeDisplayRangeContext.value) {
  const lngLat = modelCoordToLngLat(coord);
  return lngLat ? lngLatInDisplayRange(lngLat, context) : false;
}

function modelLinkIntersectsDisplayRange(link, context = activeDisplayRangeContext.value) {
  if (!context) return true;
  const from = modelCoordToLngLat(link?.from);
  const to = modelCoordToLngLat(link?.to);
  return from && to ? segmentIntersectsDistrictContext(from, to, context) : false;
}

function routeIntersectsDisplayRange(route, context = activeDisplayRangeContext.value) {
  if (!context) return true;
  const links = Array.isArray(route?.links) ? route.links : [];
  if (links.some((link) => modelLinkIntersectsDisplayRange(link, context))) return true;
  return (Array.isArray(route?.facilities) ? route.facilities : [])
    .some((facility) => modelCoordInDisplayRange(facility?.coord || facility, context));
}

function filterLineFeatureCollectionByDisplayRange(collection, context = activeDisplayRangeContext.value) {
  if (!context) return collection;
  const features = [];
  (collection?.features || []).forEach((feature, featureIndex) => {
    lineGeometryPaths(feature?.geometry).forEach((path, pathIndex) => {
      clipLineStringToDistrictContext(path, context).forEach((coordinates, clipIndex) => {
        if (coordinates.length < 2) return;
        features.push({
          type: "Feature",
          id: [feature?.id ?? featureIndex, pathIndex, clipIndex].join("-"),
          geometry: { type: "LineString", coordinates },
          properties: { ...(feature?.properties || {}) },
        });
      });
    });
  });
  return { type: "FeatureCollection", features };
}

function lineGeometryPaths(geometry) {
  if (!geometry) return [];
  if (geometry.type === "LineString") return [geometry.coordinates || []];
  if (geometry.type === "MultiLineString") return Array.isArray(geometry.coordinates) ? geometry.coordinates : [];
  return [];
}

function filterStationFeatureCollectionByDisplayRange(collection, context = activeDisplayRangeContext.value) {
  if (!context) return collection;
  const features = (collection?.features || []).filter((feature) => lngLatInDisplayRange(feature?.geometry?.coordinates, context));
  return { type: "FeatureCollection", features };
}

// 站点集过滤按 (模型, 行政区, 数据修订) 记忆化：全网站点逐点做多边形内测试是主线程同步开销，
// tab 切换等高频路径重复选同一行政区时直接复用同一对象引用（顺带命中 setGeoJsonSourceData 的引用短路）
const stationDisplayRangeMemo = new Map();

function stationCollectionForDisplayRange(context = activeDisplayRangeContext.value) {
  if (!context) return busNetworkCollections.stations;
  // contextRev 兜底行政区几何重载：同名行政区换了多边形时记忆必须失效
  const memoKey = `${selectModel.value?.name || ""}::${context.name}::${busNetworkRevision.value}::${displayRangeContextRev}`;
  const cached = stationDisplayRangeMemo.get(memoKey);
  if (cached) return cached;
  const result = filterStationFeatureCollectionByDisplayRange(busNetworkCollections.stations, context);
  stationDisplayRangeMemo.set(memoKey, result);
  while (stationDisplayRangeMemo.size > 12) {
    stationDisplayRangeMemo.delete(stationDisplayRangeMemo.keys().next().value);
  }
  return result;
}

let busNetworkClipToken = 0;

// 线要素裁剪下沉 Worker：主线程不再对全网线要素逐段做多边形裁剪（原选区冻结的另一半）；
// Worker 端按 (model, district, revision) 记忆化，重复切换同一行政区即刻返回
function applyBusNetworkLineClip(context) {
  const token = ++busNetworkClipToken;
  if (!context) {
    setGeoJsonSourceData(RM_SOURCE_LINES, busNetworkCollections.lines);
    return;
  }
  const modelName = selectModel.value?.name || "";
  warmDisplayRangeWorker(modelName);
  postDisplayRangeWorker({ type: "clipLines", seq: ++displayRangeMsgSeq, model: modelName, ...displayRangeContextPayload(context) })
    .then((msg) => {
      if (token !== busNetworkClipToken) return;
      if (!msg?.ok || !msg.collection) throw new Error("clip result unavailable");
      setGeoJsonSourceData(RM_SOURCE_LINES, msg.collection);
    })
    .catch(() => {
      if (token !== busNetworkClipToken) return;
      setGeoJsonSourceData(RM_SOURCE_LINES, filterLineFeatureCollectionByDisplayRange(busNetworkCollections.lines, context));
    });
}

function syncBusNetworkDisplayRange() {
  const context = activeDisplayRangeContext.value;
  ensureDisplayRangeLayer();
  setGeoJsonSourceData(RM_SOURCE_DISPLAY_RANGE, displayRangeOutlineCollection(context));
  setBusLayerVisibility(MapRef.value?.map, RM_LAYER_DISPLAY_RANGE_OUTLINE, Boolean(context));
  applyBusNetworkLineClip(context);
  setGeoJsonSourceData(RM_SOURCE_STATIONS, stationCollectionForDisplayRange(context));
  if (monitorBusRouteLayer && selectModel.value?.name && shouldLoadTransitNetworkForCurrentTab()) {
    monitorBusRouteLayer.setLineClipContext(context);
    if (!monitorBusRouteLayer.tileMode || monitorBusRouteLayer.datasource !== selectModel.value.name) {
      monitorBusRouteLayer.setTileSource(selectModel.value.name, { tileRequest: getRouteTileBinary });
    }
  } else if (!shouldLoadTransitNetworkForCurrentTab()) {
    pauseTransitNetworkTiles();
  }
  applyBusNetworkPaint();
  syncBaseMapLayerVisibility();
}

function expandLngLatBounds(value, bounds) {
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
  value.forEach((item) => expandLngLatBounds(item, bounds));
}

function featureCollectionLngLatBounds(collection) {
  const bounds = [Infinity, Infinity, -Infinity, -Infinity];
  for (const feature of collection?.features || []) {
    expandLngLatBounds(feature?.geometry?.coordinates, bounds);
  }
  return Number.isFinite(bounds[0]) ? bounds : null;
}

function displayRangeFitBounds() {
  return activeDisplayRangeContext.value?.bounds
    || featureCollectionLngLatBounds(adminDistrictCollection.value)
    || featureCollectionLngLatBounds(busNetworkCollections.lines)
    || featureCollectionLngLatBounds(busNetworkCollections.stations);
}

function fitDisplayRangeContext() {
  const bounds = displayRangeFitBounds();
  if (!bounds || !MapRef.value) return;
  const points = [
    lngLatToWebMercator(bounds[0], bounds[1]),
    lngLatToWebMercator(bounds[2], bounds[3]),
  ].filter((point) => Array.isArray(point) && point.every(Number.isFinite));
  if (points.length < 2) return;
  const result = MapRef.value.setFitZoomAndCenterByPoints?.(points);
  if (!result) {
    MapRef.value.setCenter?.(lngLatToWebMercator((bounds[0] + bounds[2]) / 2, (bounds[1] + bounds[3]) / 2));
  }
}

async function loadBusNetwork() {
  if (!MapRef.value?.map || !isModelReady.value || !selectModel.value?.name || !shouldLoadTransitNetworkForCurrentTab()) return;
  const seq = ++busNetworkRequestSeq;
  const modelName = selectModel.value.name;
  abortOtherModelDataRequests(modelName);
  busNetworkLoading.value = true;
  busNetworkError.value = "";
  try {
    // 改为使用当前模型自身的线路/站点数据（getLineAll），而非数据管理的真实底图数据
    const [lineRes, facilityRes] = await Promise.all([
      getCachedLineAll(modelName),
      getCachedFacilityAll(modelName),
    ]);
    if (seq !== busNetworkRequestSeq) return;
    const lines = Array.isArray(lineRes) ? lineRes : [];
    const facilities = Array.isArray(facilityRes) ? facilityRes : [];
    busNetworkRawLines = lines;
    busNetworkRawFacilities = facilities;
    busNetworkRevision.value += 1;
    busNetworkCollections = {
      lines: buildModelLineFeatureCollection(lines),
      stations: buildModelStationFeatureCollection(facilities, collectMetroFacilityKeys(lines)),
    };
    busNetworkIndexes = buildBusNetworkIndexes(lines, busNetworkCollections);
    // 行政区 Worker 预热：空闲期打包全网坐标常驻 Worker，用户第一次选行政区即秒回
    runWhenIdle(() => {
      if (seq === busNetworkRequestSeq) warmDisplayRangeWorker(modelName);
    });
    const map = MapRef.value?.map;
    if (!map) return;
    ensureBusNetworkSource(map, RM_SOURCE_LINES, busNetworkCollections.lines);
    ensureBusNetworkSource(map, RM_SOURCE_STATIONS, busNetworkCollections.stations);
    await ensureBusStationIcons(map);
    // 图标加载期间可能已卸载/换模型（onUnmounted 会递增 seq）：过期则不再把图层加回共享地图
    if (seq !== busNetworkRequestSeq) return;
    ensureBusNetworkLayers(map);
    syncBusNetworkDisplayRange();
  } catch (error) {
    if (seq !== busNetworkRequestSeq) return;
    busNetworkError.value = error?.message || "公交线网加载失败";
  } finally {
    if (seq === busNetworkRequestSeq) {
      busNetworkLoading.value = false;
    }
  }
}

function ensureMonitorBusRouteLayer() {
  if (!MapRef.value || monitorBusRouteLayer || !selectModel.value?.name || !shouldLoadTransitNetworkForCurrentTab()) return;
  monitorBusRouteLayer = new RouteLayer({
    zIndex: 998,
    lineWidth: busNetworkLineWidth.value * 10,
    fixedPixelWidth: true,
    flowControl: false,
    color: hexNumber(MAP_THEME.network.line),
    opacity: isLineSelectionActive() ? 0 : RM_BASE_LINE_OPACITY,
  });
  monitorSelectedRouteGlowLayer = new RouteLayer({
    zIndex: 999,
    lineWidth: Math.max(4, busNetworkLineWidth.value + 3.6) * MAP_THEME.route.haloWidthRatio * 10,
    fixedPixelWidth: true,
    workerEnabled: true,
    continuousPath: true,
    flowControl: false,
    // 同色系光晕（backlit）：替代旧黄色光晕，避免橙线泛脏
    color: hexNumber(MAP_THEME.route.upHalo),
    opacity: MAP_THEME.route.haloOpacity,
  });
  monitorSelectedRouteLayer = new RouteLayer({
    zIndex: 1000,
    lineWidth: Math.max(4, busNetworkLineWidth.value + 3.6) * 10,
    fixedPixelWidth: true,
    workerEnabled: true,
    continuousPath: true,
    flowControl: false,
    color: hexNumber(MAP_THEME.route.up),
    opacity: 0.95,
  });
  monitorReverseRouteGlowLayer = new RouteLayer({
    zIndex: 999.4,
    lineWidth: Math.max(4, busNetworkLineWidth.value + 3.6) * MAP_THEME.route.haloWidthRatio * 10,
    fixedPixelWidth: true,
    workerEnabled: true,
    continuousPath: true,
    flowControl: false,
    color: hexNumber(MAP_THEME.route.downHalo),
    opacity: MAP_THEME.route.haloOpacity,
  });
  monitorReverseRouteLayer = new RouteLayer({
    zIndex: 999.5,
    lineWidth: Math.max(4, busNetworkLineWidth.value + 3.6) * 10,
    fixedPixelWidth: true,
    workerEnabled: true,
    continuousPath: true,
    flowControl: false,
    color: hexNumber(MAP_THEME.route.down),
    opacity: 0.88,
  });
  monitorSelectedRouteSegmentLayer = new RouteLayer({
    zIndex: 1001,
    lineWidth: Math.max(6.4, Math.max(4, busNetworkLineWidth.value + 3.6) * 1.08) * 10,
    fixedPixelWidth: true,
    // 需求：地铁整线断面链路很多，主线程拼路径+转二进制会卡顿；
    // 走 worker 异步转二进制 + LineLayer 逐链路（不拼连续路径），实现毫秒级上屏，避免"站点先出、断面后卡出"
    workerEnabled: true,
    continuousPath: false,
    flowControl: true,
    flowWidthStep: 2,
    widthMaxPixels: 26,
    flowStyleStops: pfaSegmentFlowStops.value,
    opacity: pfaSegmentLayerOpacity.value,
    // 单条选中线路：关闭随缩放的透明度衰减，避免中低缩放级别下线体发虚
    zoomFadeOpacity: false,
  });
  // 需求11：下行断面客流图层（与上行同一套色阶 stops）
  monitorReverseRouteSegmentLayer = new RouteLayer({
    zIndex: 1000.8,
    lineWidth: Math.max(6.4, Math.max(4, busNetworkLineWidth.value + 3.6) * 1.08) * 10,
    fixedPixelWidth: true,
    workerEnabled: true,
    continuousPath: false,
    flowControl: true,
    flowWidthStep: 2,
    widthMaxPixels: 26,
    flowStyleStops: pfaSegmentFlowStops.value,
    opacity: pfaSegmentLayerOpacity.value,
    zoomFadeOpacity: false,
  });
  monitorTransferRouteGlowLayer = new RouteLayer({
    zIndex: 999.2,
    lineWidth: Math.max(4, busNetworkLineWidth.value + 3.6) * MAP_THEME.route.haloWidthRatio * 10,
    fixedPixelWidth: true,
    workerEnabled: false,
    flowControl: false,
    color: hexNumber(MAP_THEME.route.downHalo),
    opacity: 0.26,
  });
  monitorTransferRouteLayer = new RouteLayer({
    zIndex: 999.3,
    lineWidth: Math.max(4, busNetworkLineWidth.value + 3.6) * 10,
    fixedPixelWidth: true,
    workerEnabled: false,
    flowControl: false,
    color: hexNumber(MAP_THEME.route.transfer),
    opacity: 0.86,
  });
  MapRef.value.addLayer(monitorBusRouteLayer);
  // Deck 图层按 zIndex 绘制：背景 -> 关联线路 -> 原高亮线 -> 断面客流覆盖层。
  MapRef.value.addLayer(monitorSelectedRouteGlowLayer);
  MapRef.value.addLayer(monitorTransferRouteGlowLayer);
  MapRef.value.addLayer(monitorTransferRouteLayer);
  MapRef.value.addLayer(monitorReverseRouteGlowLayer);
  MapRef.value.addLayer(monitorReverseRouteLayer);
  MapRef.value.addLayer(monitorSelectedRouteLayer);
  MapRef.value.addLayer(monitorReverseRouteSegmentLayer);
  MapRef.value.addLayer(monitorSelectedRouteSegmentLayer);
  monitorSelectedRouteGlowLayer.setData([]);
  monitorTransferRouteGlowLayer.setData([]);
  monitorTransferRouteLayer.setData([]);
  monitorReverseRouteGlowLayer.setData([]);
  monitorReverseRouteLayer.setData([]);
  monitorSelectedRouteLayer.setData([]);
  monitorSelectedRouteSegmentLayer.setData([]);
  monitorReverseRouteSegmentLayer.setData([]);
  monitorBusRouteLayer.setTileSource(selectModel.value.name, { tileRequest: getRouteTileBinary });
  syncBusNetworkDisplayRange();
  syncBaseMapLayerVisibility();
}

function syncMonitorRouteLineWidths() {
  const baseWidth = busNetworkLineWidth.value;
  const selectedWidth = Math.max(4, baseWidth + 3.6);
  monitorBusRouteLayer?.setLineWidth(baseWidth * 10);
  monitorSelectedRouteGlowLayer?.setLineWidth(selectedWidth * 2.2 * 10);
  monitorSelectedRouteLayer?.setLineWidth(selectedWidth * 10);
  monitorSelectedRouteSegmentLayer?.setLineWidth(Math.max(6.4, selectedWidth * 1.08) * 10);
  monitorReverseRouteSegmentLayer?.setLineWidth(Math.max(6.4, selectedWidth * 1.08) * 10);
  monitorReverseRouteGlowLayer?.setLineWidth(selectedWidth * 2.2 * 10);
  monitorReverseRouteLayer?.setLineWidth(selectedWidth * 10);
  monitorTransferRouteGlowLayer?.setLineWidth(selectedWidth * 2.2 * 10);
  monitorTransferRouteLayer?.setLineWidth(selectedWidth * 10);
}

function applyPfaSegmentFlowStyleNow() {
  pfaSegmentStyleFrameId = null;
  // 分档颜色/线宽在图层渲染期按链路 flow 值即时计算，换色阶只需重设 stops 触发重绘；
  // 链路数据的层间路由由选中态 watcher 负责，这里重发 setData 会让断面层白走一遍 worker 转二进制
  monitorSelectedRouteSegmentLayer?.setFlowControl?.(true);
  monitorSelectedRouteSegmentLayer?.setFlowStyleStops(pfaSegmentFlowStops.value);
  monitorReverseRouteSegmentLayer?.setFlowControl?.(true);
  monitorReverseRouteSegmentLayer?.setFlowStyleStops(pfaSegmentFlowStops.value);
  syncBaseMapLayerVisibility();
}

function applyPfaSegmentFlowStyle() {
  if (typeof requestAnimationFrame !== "function") {
    applyPfaSegmentFlowStyleNow();
    return;
  }
  if (pfaSegmentStyleFrameId) return;
  pfaSegmentStyleFrameId = requestAnimationFrame(applyPfaSegmentFlowStyleNow);
}

function applyPfaSegmentOpacity() {
  monitorSelectedRouteSegmentLayer?.setFlowControl?.(true);
  monitorSelectedRouteSegmentLayer?.setOpacity(pfaSegmentLayerOpacity.value);
  monitorReverseRouteSegmentLayer?.setFlowControl?.(true);
  monitorReverseRouteSegmentLayer?.setOpacity(pfaSegmentLayerOpacity.value);
}

function hasSegmentFlowValue(link) {
  return link?.flow !== null
    && link?.flow !== undefined
    && Number.isFinite(Number(link.flow));
}

function setMonitorSelectedRouteLinks(links = []) {
  const data = Array.isArray(links) ? links : [];
  const isSegmentSelection = isPfaSegmentSectionActive() && isPfaLineSelectionActive();
  const segmentData = isSegmentSelection
    ? data.map((link) => (hasSegmentFlowValue(link) ? link : { ...link, flow: 0 }))
    : [];
  const selectedRouteData = isSegmentSelection ? [] : data;
  monitorSelectedRouteGlowLayer?.setData(selectedRouteData);
  monitorSelectedRouteLayer?.setData(selectedRouteData);
  monitorSelectedRouteSegmentLayer?.setData(segmentData);
  syncBaseMapLayerVisibility();
}

function setMonitorReverseRouteLinks(links = []) {
  const data = Array.isArray(links) ? links : [];
  // 需求11：断面功能激活且反向链路带断面客流时，走下行断面客流图层
  const isSegmentSelection = isPfaSegmentSectionActive()
    && isPfaLineSelectionActive()
    && data.some(hasSegmentFlowValue);
  const segmentData = isSegmentSelection
    ? data.map((link) => (hasSegmentFlowValue(link) ? link : { ...link, flow: 0 }))
    : [];
  const reverseRouteData = isSegmentSelection ? [] : data;
  monitorReverseRouteGlowLayer?.setData(reverseRouteData);
  monitorReverseRouteLayer?.setData(reverseRouteData);
  monitorReverseRouteSegmentLayer?.setData(segmentData);
  syncBaseMapLayerVisibility();
}

function setMonitorTransferRouteLinks(links = []) {
  const data = Array.isArray(links) ? links : [];
  monitorTransferRouteGlowLayer?.setData(data);
  monitorTransferRouteLayer?.setData(data);
  syncBaseMapLayerVisibility();
}

function selectedRouteLinksForMap() {
  if (props.mode === "pfa" && Array.isArray(selectedRouteMapLinks.value) && selectedRouteMapLinks.value.length) {
    return selectedRouteMapLinks.value;
  }
  return Array.isArray(selectedRouteDetail.value?.links) ? selectedRouteDetail.value.links : [];
}

function reverseRouteLinksForMap() {
  if (Array.isArray(selectedReverseRouteMapLinks.value) && selectedReverseRouteMapLinks.value.length) {
    return selectedReverseRouteMapLinks.value;
  }
  return Array.isArray(selectedReverseRouteDetail.value?.links) ? selectedReverseRouteDetail.value.links : [];
}

// 断面/乘降模式：把当前方向的站点写入独立数据源（空心圈+站名图层共用）
function refreshSegmentStationSource() {
  if (!MapRef.value?.map) return;
  if (!(isPfaSegmentSectionActive() && isPfaLineSelectionActive())) {
    setGeoJsonSourceData(RM_SOURCE_SEGMENT_STATIONS, EMPTY_FEATURE_COLLECTION);
    return;
  }
  const features = [];
  const seen = new Set();
  selectedRouteFacilities().forEach((facility, index) => {
    const lngLat = modelCoordToLngLat(facility?.coord || facility);
    if (!lngLat) return;
    const facilityId = facility?.facilityId != null ? String(facility.facilityId) : `idx-${index}`;
    if (seen.has(facilityId)) return;
    seen.add(facilityId);
    const name = String(facility?.facilityName || "");
    features.push({
      type: "Feature",
      id: `segment-station-${index}`,
      geometry: { type: "Point", coordinates: lngLat },
      properties: { facilityId, name, stop_name: name, facilityName: name },
    });
  });
  setGeoJsonSourceData(RM_SOURCE_SEGMENT_STATIONS, { type: "FeatureCollection", features });
}

function refreshMonitorSelectedRouteLinks() {
  refreshSegmentStationSource();
  if (effectiveTab.value !== "线路客流监测") {
    setMonitorSelectedRouteLinks([]);
    setMonitorReverseRouteLinks([]);
    return;
  }
  // 关联线路模式不画选中线路本体，只保留关联线路与换乘站点
  if (isPfaTransferSectionActive() && isPfaLineSelectionActive()) {
    setMonitorSelectedRouteLinks([]);
    setMonitorReverseRouteLinks([]);
    return;
  }
  const links = selectedRouteLinksForMap();
  if (!links.length) {
    setMonitorSelectedRouteLinks([]);
    setMonitorReverseRouteLinks([]);
    return;
  }
  // 站点乘降：选中线路画成单一蓝色线（复用蓝色 reverse 图层），不画橙色主线，上面叠站间OD曲线
  if (isPfaBoardingSectionActive() && isPfaLineSelectionActive()) {
    setMonitorSelectedRouteLinks([]);
    setMonitorReverseRouteLinks(links);
    return;
  }
  setMonitorSelectedRouteLinks(links);
  // 断面客流模块只绘制右侧面板当前选中方向，反向随面板“线路方向”切换后再显示
  const segmentDirectionOnly = isPfaSegmentSectionActive() && isPfaLineSelectionActive();
  setMonitorReverseRouteLinks(segmentDirectionOnly ? [] : reverseRouteLinksForMap());
}

// 关联线路展示上限：与选中线共站的线路可能很多，超过则按共站数取前 N（避免地图过载）
const PFA_TRANSFER_LINE_LIMIT = 150;

// 由模型线路数据构建 deck 链路：优先用 route.links（模型坐标），否则用抽稀 geometry 连线
function buildLineDeckLinks(line) {
  const links = [];
  for (const route of Array.isArray(line?.routes) ? line.routes : []) {
    const routeLinks = Array.isArray(route?.links) ? route.links : [];
    if (routeLinks.length) {
      for (const link of routeLinks) {
        if (link?.from && link?.to) links.push(link);
      }
      continue;
    }
    const geometry = Array.isArray(route?.geometry) ? route.geometry : [];
    for (let i = 1; i < geometry.length; i += 1) {
      const a = geometry[i - 1];
      const b = geometry[i];
      if (Array.isArray(a) && Array.isArray(b)) {
        links.push({ from: { x: a[0], y: a[1] }, to: { x: b[0], y: b[1] } });
      }
    }
  }
  return links;
}

// 选中线路的"站名集合"与"lineId 集合"（地铁整线含各分段子线路，用于排除自身）
function selectedLineTransferContext() {
  const stationNames = new Set();
  const lineIds = new Set();
  const collect = (detail) => {
    if (!detail) return;
    (Array.isArray(detail.facilities) ? detail.facilities : []).forEach((facility) => {
      const name = String(facility?.facilityName || "").trim();
      if (name) stationNames.add(name);
    });
    (Array.isArray(detail.childRoutes) ? detail.childRoutes : []).forEach((child) => {
      if (child?.lineId != null) lineIds.add(String(child.lineId));
    });
    if (detail.lineId != null) lineIds.add(String(detail.lineId));
  };
  collect(selectedRouteDetail.value);
  collect(selectedReverseRouteDetail.value);
  selectedRouteFacilities().forEach((facility) => {
    const name = String(facility?.facilityName || "").trim();
    if (name) stationNames.add(name);
  });
  return { stationNames, lineIds };
}

// 需求：关联线路显示"所有直接换乘（与选中线共站）的线路 + 换乘站点"，不再只显示有换乘客流(>0)的线路。
// 直接从已加载的模型线路数据按共站关系计算，无需逐线请求 routeDetail。
function pfaTransferData() {
  if (!(isPfaTransferSectionActive() && isPfaLineSelectionActive())) {
    return { lines: [], sharedStations: new Set() };
  }
  const { stationNames, lineIds } = selectedLineTransferContext();
  if (!stationNames.size) return { lines: [], sharedStations: new Set() };
  const sharedStations = new Set();
  const scored = [];
  for (const line of busNetworkRawLines) {
    const lineId = String(line?.lineId || "");
    if (lineId && lineIds.has(lineId)) continue; // 排除选中线路自身（地铁整线含各分段）
    let shared = 0;
    const localShared = [];
    for (const route of Array.isArray(line?.routes) ? line.routes : []) {
      for (const facility of Array.isArray(route?.facilities) ? route.facilities : []) {
        const name = String(facility?.facilityName || "").trim();
        if (name && stationNames.has(name)) {
          shared += 1;
          localShared.push(name);
        }
      }
    }
    if (shared > 0) scored.push({ line, shared, localShared });
  }
  scored.sort((a, b) => b.shared - a.shared);
  if (scored.length > PFA_TRANSFER_LINE_LIMIT) {
    console.info(`[pfa] 关联线路共 ${scored.length} 条，地图仅显示共站最多的前 ${PFA_TRANSFER_LINE_LIMIT} 条`);
    scored.length = PFA_TRANSFER_LINE_LIMIT;
  }
  scored.forEach(({ localShared }) => localShared.forEach((name) => sharedStations.add(name)));
  return { lines: scored.map((item) => item.line), sharedStations };
}

// 换乘站点：选中线与关联线的共用物理站（按站名从模型站点表取坐标）
function renderPfaTransferStations(sharedStations) {
  if (!MapRef.value?.map) return;
  if (!sharedStations || !sharedStations.size) {
    setGeoJsonSourceData(RM_SOURCE_TRANSFER_STATIONS, EMPTY_FEATURE_COLLECTION);
    return;
  }
  const features = [];
  const seen = new Set();
  for (const facility of busNetworkRawFacilities) {
    const name = String(facility?.facilityName || "");
    if (!name || !sharedStations.has(name) || seen.has(name)) continue;
    const lngLat = modelCoordToLngLat(facility?.coord);
    if (!lngLat) continue;
    seen.add(name);
    features.push({
      type: "Feature",
      id: `transfer-station-${features.length}`,
      geometry: { type: "Point", coordinates: lngLat },
      properties: { name, stop_name: name, facilityName: name },
    });
  }
  setGeoJsonSourceData(RM_SOURCE_TRANSFER_STATIONS, { type: "FeatureCollection", features });
}

function refreshPfaTransferRouteLinks() {
  // deck 关联线路图层弃用（会串接多条线的链路致线形错乱），统一清空
  setMonitorTransferRouteLinks([]);
  if (!(isPfaTransferSectionActive() && isPfaLineSelectionActive())) {
    renderPfaTransferStations(null);
    setGeoJsonSourceData(RM_SOURCE_TRANSFER_LINES, EMPTY_FEATURE_COLLECTION);
    return;
  }
  const { lines, sharedStations } = pfaTransferData();
  renderPfaTransferStations(sharedStations);
  // 每条关联线路一条 LineString（buildModelLineFeatureCollection 已按 route 拆分），蓝色实线
  setGeoJsonSourceData(RM_SOURCE_TRANSFER_LINES, buildModelLineFeatureCollection(lines));
}

function ensureMonitorRoadLayer() {
  if (!MapRef.value || monitorRoadLayer || !selectModel.value?.name || !shouldLoadTransitNetworkForCurrentTab()) return;
  monitorRoadLayer = new NetworkLayer({
    zIndex: 997,
    lineWidth: computedLineWidth.value,
    flowWidthStep: computedFlowWidthStep.value,
    flowControl: flowControl.value,
  });
  MapRef.value.addLayer(monitorRoadLayer);
  monitorRoadLayer.setTileSource(selectModel.value.name);
  syncBaseMapLayerVisibility();
}

function handleBaseMapLineModeChange(mode) {
  baseMapLineMode.value = mode;
  reconcilePfaSelectionForBaseMapMode(mode);
  if (mode === "road-network" && shouldLoadTransitNetworkForCurrentTab()) {
    ensureMonitorRoadLayer();
  }
  syncBaseMapLayerVisibility();
}

function reconcilePfaSelectionForBaseMapMode(mode = baseMapLineMode.value) {
  if (props.mode !== "pfa" || (mode !== "bus-network" && mode !== "metro-network")) return;
  closeLineRoutePicker();
  runMonitorSearchKeyword.value = "";
  isSearchFocused.value = false;
  if (effectiveTab.value === "线路客流监测" && isLineSelectionActive()) {
    clearLineSelection();
  }
  if (effectiveTab.value === "站点客流监测" && (isStationFeatureSelectionActive() || selectedStationName.value)) {
    clearStationSelection();
  }
}

function queryBoxAround(point, radius = 8) {
  return [
    [point[0] - radius, point[1] - radius],
    [point[0] + radius, point[1] + radius],
  ];
}

function busLineName(properties = {}) {
  return String(
    properties.lineName ||
      properties.line_name ||
      properties.routeName ||
      properties.route_name ||
      properties.name ||
      properties.line_id ||
      properties.route_id ||
      "",
  ).trim();
}

function busStationName(properties = {}) {
  return String(
    properties.stop_name ||
      properties.station_name ||
      properties.facilityName ||
      properties.name ||
      properties.stop_id ||
      "",
  ).trim();
}

function normalizeMonitorFeatureName(value = "") {
  return String(value || "")
    .trim()
    .replace(/\s+/g, "")
    .replace(/[（(].*?[）)]/g, "")
    .toLowerCase();
}

function createEmptyBusNetworkIndexes() {
  return {
    lineFeatureByKey: new Map(),
    lineFeatureByRouteId: new Map(),
    lineFeaturesByName: new Map(),
    stationFeatureByKey: new Map(),
    stationFeaturesByName: new Map(),
    routeFacilitiesByKey: new Map(),
    routeFacilitiesByRouteId: new Map(),
  };
}

function addIndexedList(map, key, value) {
  const text = String(key || "");
  if (!text) return;
  const list = map.get(text);
  if (list) list.push(value);
  else map.set(text, [value]);
}

function buildBusNetworkIndexes(lines = [], collections = busNetworkCollections) {
  const indexes = createEmptyBusNetworkIndexes();
  for (const feature of collections?.lines?.features || []) {
    const props = feature?.properties || {};
    const key = String(props._lineKey || feature?.id || "");
    const routeId = String(props.routeId ?? props.route_id ?? "");
    if (key) indexes.lineFeatureByKey.set(key, feature);
    if (routeId && !indexes.lineFeatureByRouteId.has(routeId)) indexes.lineFeatureByRouteId.set(routeId, feature);
    [props.lineName, props.lineId, busLineName(props)].forEach((name) => {
      addIndexedList(indexes.lineFeaturesByName, normalizeMonitorFeatureName(name), feature);
    });
  }
  for (const feature of collections?.stations?.features || []) {
    const props = feature?.properties || {};
    const key = String(props._stationKey || feature?.id || "");
    if (key) indexes.stationFeatureByKey.set(key, feature);
    addIndexedList(indexes.stationFeaturesByName, normalizeMonitorFeatureName(busStationName(props)), feature);
  }
  for (const line of Array.isArray(lines) ? lines : []) {
    const lineId = String(line?.lineId || "");
    const routes = Array.isArray(line?.routes) ? line.routes : [];
    routes.forEach((route, index) => {
      const routeId = String(route?.routeId || "");
      const routeKey = `${lineId}-${routeId || index}`;
      const facilities = Array.isArray(route?.facilities) ? route.facilities : [];
      if (routeKey) indexes.routeFacilitiesByKey.set(routeKey, facilities);
      if (routeId && !indexes.routeFacilitiesByRouteId.has(routeId)) indexes.routeFacilitiesByRouteId.set(routeId, facilities);
    });
  }
  return indexes;
}

function modelLineFeatureByName(lineName) {
  const target = normalizeMonitorFeatureName(lineName);
  if (!target) return null;
  return busNetworkIndexes.lineFeaturesByName.get(target)?.[0] || null;
}

function modelLineFeatureByRouteId(routeId) {
  const target = String(routeId ?? "");
  if (!target) return null;
  return busNetworkIndexes.lineFeatureByRouteId.get(target) || null;
}

function modelStationFeatureByName(stationName) {
  const target = normalizeMonitorFeatureName(stationName);
  if (!target) return null;
  const matches = busNetworkIndexes.stationFeaturesByName.get(target) || [];
  return matches.find((feature) => !activeDisplayRangeContext.value || lngLatInDisplayRange(feature?.geometry?.coordinates))
    || matches[0]
    || null;
}

function plainBusFeature(feature) {
  return {
    type: "Feature",
    id: feature?.id,
    geometry: feature?.geometry ? JSON.parse(JSON.stringify(feature.geometry)) : null,
    properties: { ...(feature?.properties || {}) },
  };
}

function setSelectedBusStation(feature) {
  const source = MapRef.value?.map?.getSource(RM_SOURCE_SELECTED_STATION);
  if (!source?.setData) return;
  const coordinate = feature?.geometry?.coordinates;
  const inRange = !feature || !activeDisplayRangeContext.value || lngLatInDisplayRange(coordinate);
  source.setData(feature?.geometry && inRange ? { type: "FeatureCollection", features: [plainBusFeature(feature)] } : emptyFeatureCollection());
}

function setReverseBusStation(feature) {
  const source = MapRef.value?.map?.getSource(RM_SOURCE_REVERSE_SELECTED_STATION);
  if (!source?.setData) return;
  const coordinate = feature?.geometry?.coordinates;
  const inRange = !feature || !activeDisplayRangeContext.value || lngLatInDisplayRange(coordinate);
  source.setData(feature?.geometry && inRange ? { type: "FeatureCollection", features: [plainBusFeature(feature)] } : emptyFeatureCollection());
}

function stationFeatureDistance(a, b) {
  const left = a?.geometry?.coordinates;
  const right = b?.geometry?.coordinates;
  if (!Array.isArray(left) || !Array.isArray(right)) return Number.POSITIVE_INFINITY;
  const dx = Number(left[0]) - Number(right[0]);
  const dy = Number(left[1]) - Number(right[1]);
  return Number.isFinite(dx) && Number.isFinite(dy) ? Math.hypot(dx, dy) : Number.POSITIVE_INFINITY;
}

function pairedStationFeature(feature) {
  const sourceProps = feature?.properties || {};
  const targetName = normalizeMonitorFeatureName(busStationName(sourceProps));
  const sourceKey = String(sourceProps._stationKey || feature?.id || "");
  if (!targetName) return null;
  return (busNetworkIndexes.stationFeaturesByName.get(targetName) || [])
    .filter((candidate) => {
      const props = candidate?.properties || {};
      const key = String(props._stationKey || candidate?.id || "");
      if (!key || key === sourceKey) return false;
      return !activeDisplayRangeContext.value || lngLatInDisplayRange(candidate?.geometry?.coordinates);
    })
    .sort((left, right) => stationFeatureDistance(feature, left) - stationFeatureDistance(feature, right))[0] || null;
}

function stationFeatureCoordObject(feature) {
  const coords = feature?.geometry?.coordinates;
  if (!Array.isArray(coords) || coords.length < 2) return null;
  const mercator = lngLatToWebMercator(Number(coords[0]), Number(coords[1]));
  return Array.isArray(mercator) && mercator.every(Number.isFinite)
    ? { x: mercator[0], y: mercator[1] }
    : null;
}

function firstRenderedBusFeature(point, layers, radius = 8) {
  const map = MapRef.value?.map;
  if (!map || !Array.isArray(point)) return null;
  const existingLayers = layers.filter((layerId) => map.getLayer?.(layerId));
  if (!existingLayers.length) return null;
  return map.queryRenderedFeatures(queryBoxAround(point, radius), { layers: existingLayers })?.[0] || null;
}

// —— 线路选择弹窗：复用数据管理“点击路段 → 弹出经过该路段的线路列表 → 选择”一模一样的逻辑 ——
const lineRoutePicker = reactive({
  visible: false,
  x: 0,
  y: 0,
  routes: [],
  lngLat: null,
  point: null,
});

// 与数据管理一致：把“线路名(方向描述)”拆成主名 + 方向描述
function parsePickerRoute(fullName) {
  if (!fullName) return { mainName: "未知线路", desc: "" };
  const match = String(fullName).match(/^([^(]+)\(([^)]+)\)$/);
  if (match) {
    return { mainName: match[1].trim(), desc: match[2].replace(/--/g, " - ").trim() };
  }
  return { mainName: String(fullName), desc: "" };
}

function pickerFullRouteName(properties = {}) {
  return String(
    properties.name ||
      properties.route_name ||
      properties.lineName ||
      properties.line_name ||
      properties.line_id ||
      properties.route_id ||
      "",
  ).trim();
}

function fullBusLineFeature(feature) {
  if (!feature) return null;
  const properties = feature.properties || {};
  const key = String(properties._lineKey || feature.id || "");
  if (!key) return feature;
  return busNetworkIndexes.lineFeatureByKey.get(key) || feature;
}

function routeOptionFromFeature(feature) {
  const fullFeature = fullBusLineFeature(feature) || feature;
  const properties = { ...(fullFeature?.properties || {}) };
  const fullName = pickerFullRouteName(properties);
  return {
    id: String(properties._lineKey || properties.routeId || properties.route_id || properties.lineId || properties.line_id || fullFeature?.id || fullName || "route"),
    name: fullName || busLineName(properties) || "未命名线路",
    properties,
    // feature 含完整 geometry，进入 lineRoutePicker（reactive）前 markRaw，避免整条线坐标被深代理
    feature: fullFeature ? markRaw(fullFeature) : fullFeature,
  };
}

function dedupeRouteOptions(routes) {
  const seen = new Set();
  const result = [];
  for (const route of routes) {
    const key = String(route?.id || route?.name || "");
    if (!route?.name || seen.has(key)) continue;
    seen.add(key);
    result.push(route);
  }
  return result;
}

function clampPickerPosition(value, size, maxSize) {
  const edge = 12;
  return Math.max(edge, Math.min(Number(value) + 12, Math.max(edge, Number(maxSize) - size - edge)));
}

// 平面近似的“点到线段最近距离”，仅用于对命中的多条线路按贴近点击点排序
function nearestLineSegmentDistance(geometry, lngLat) {
  if (!geometry || !Array.isArray(lngLat)) return Infinity;
  const px = Number(lngLat[0]);
  const py = Number(lngLat[1]);
  if (!Number.isFinite(px) || !Number.isFinite(py)) return Infinity;
  const paths = geometry.type === "LineString"
    ? [geometry.coordinates]
    : geometry.type === "MultiLineString"
      ? geometry.coordinates
      : [];
  let best = Infinity;
  for (const path of paths) {
    if (!Array.isArray(path)) continue;
    for (let i = 1; i < path.length; i += 1) {
      const a = path[i - 1];
      const b = path[i];
      const ax = Number(a?.[0]);
      const ay = Number(a?.[1]);
      const bx = Number(b?.[0]);
      const by = Number(b?.[1]);
      if (![ax, ay, bx, by].every(Number.isFinite)) continue;
      const dx = bx - ax;
      const dy = by - ay;
      const lenSq = dx * dx + dy * dy;
      const t = lenSq > 0 ? Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lenSq)) : 0;
      const dist = Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
      if (dist < best) best = dist;
    }
  }
  return best;
}

function isRouteOptionActive(route) {
  if (!route || !selectedLineKey.value) return false;
  return String(route.id) === String(selectedLineKey.value);
}

function closeLineRoutePicker() {
  routePickRequestSeq += 1;
  lineRoutePicker.visible = false;
  lineRoutePicker.routes = [];
  lineRoutePicker.lngLat = null;
  lineRoutePicker.point = null;
}

function routePickRadiusMeters(point, pixels = 7) {
  if (!Array.isArray(point) || !MapRef.value?.WindowXYToWebMercator) return 80;
  const center = MapRef.value.WindowXYToWebMercator(point[0], point[1]);
  const edge = MapRef.value.WindowXYToWebMercator(point[0] + pixels, point[1]);
  const radius = Math.hypot(Number(edge?.[0]) - Number(center?.[0]), Number(edge?.[1]) - Number(center?.[1]));
  return Number.isFinite(radius) ? Math.max(12, Math.min(800, radius)) : 80;
}

function routeSegmentFeature(candidate, properties = {}) {
  const from = modelCoordToLngLat(candidate?.segmentFrom);
  const to = modelCoordToLngLat(candidate?.segmentTo);
  if (!from || !to) return null;
  return {
    type: "Feature",
    id: `picked-${candidate?.routeId || "route"}`,
    geometry: { type: "LineString", coordinates: [from, to] },
    properties,
  };
}

function routeSegmentLinks(candidate) {
  const fromX = Number(candidate?.segmentFrom?.x);
  const fromY = Number(candidate?.segmentFrom?.y);
  const toX = Number(candidate?.segmentTo?.x);
  const toY = Number(candidate?.segmentTo?.y);
  if (![fromX, fromY, toX, toY].every(Number.isFinite)) return [];
  return [{
    linkId: `picked-${candidate?.routeId || "route"}`,
    from: { x: fromX, y: fromY },
    to: { x: toX, y: toY },
    length: Math.hypot(toX - fromX, toY - fromY),
    lanes: 1,
  }];
}

function routeOptionFromCandidate(candidate) {
  const fullFeature = modelLineFeatureByRouteId(candidate?.routeId);
  const lineName = String(candidate?.lineName || candidate?.lineId || "未命名线路");
  const startName = String(candidate?.startName || "");
  const endName = String(candidate?.endName || "");
  const fullName = startName && endName ? `${lineName}(${startName}--${endName})` : lineName;
  const lineKey = String(
    fullFeature?.properties?._lineKey ||
      `${candidate?.lineId || lineName}-${candidate?.routeId || "route"}`,
  );
  const properties = {
    ...(fullFeature?.properties || {}),
    lineId: candidate?.lineId || fullFeature?.properties?.lineId || "",
    lineName,
    routeId: candidate?.routeId || fullFeature?.properties?.routeId || "",
    routeName: candidate?.routeName || fullFeature?.properties?.routeName || "",
    startName,
    endName,
    name: fullName,
    _lineKey: lineKey,
  };
  const feature = fullFeature
    ? { ...fullFeature, properties }
    : routeSegmentFeature(candidate, properties);
  return {
    id: lineKey,
    name: fullName,
    properties,
    feature,
    segmentFeature: routeSegmentFeature(candidate, properties),
    segmentLinks: routeSegmentLinks(candidate),
  };
}

function fallbackRouteOptions(point, lngLat) {
  const map = MapRef.value?.map;
  const layers = [RM_LAYER_LINES].filter((layerId) => map?.getLayer?.(layerId));
  if (!map || !layers.length || !Array.isArray(point)) return { routes: [], segmentFeature: null };
  const features = map.queryRenderedFeatures(queryBoxAround(point, 7), { layers });
  const ordered = Array.isArray(lngLat)
    ? features
        .map((feature) => ({ feature, distance: nearestLineSegmentDistance(feature.geometry, lngLat) }))
        .sort((left, right) => left.distance - right.distance)
        .map((item) => item.feature)
    : features;
  return {
    routes: dedupeRouteOptions(ordered.map((feature) => routeOptionFromFeature(feature))),
    segmentFeature: ordered[0] || null,
  };
}

// 公交线网模式只列公交线路，地铁线网模式只列地铁线路（点选弹窗候选按制式过滤）
function routeOptionMatchesMode(option) {
  const lineId = String(option?.properties?.lineId || "");
  const isMetro = metroLineIdSet.value.has(lineId)
    || isMetroLine({
      lineName: option?.properties?.lineName,
      lineId,
      routes: [{ routeId: option?.properties?.routeId, routeName: option?.properties?.routeName }],
    });
  return baseMapLineMode.value === "metro-network" ? isMetro : !isMetro;
}

// 地铁不区分方向且同线分段合并：点选弹窗每条地铁线只保留一个候选（按"规范化线路名"聚合，
// 如 3号线/3号线北延段/各交路 → 一个"地铁3号线"），选中即走整线合并统计。
function metroCanonicalNameForOption(option) {
  const lineId = String(option?.properties?.lineId || "");
  const rawLine = busNetworkRawLines.find((line) => String(line?.lineId || "") === lineId);
  const canonical = rawLine
    ? metroLineCanonicalName(rawLine)
    : metroLineCanonicalName({ lineName: option?.properties?.lineName, lineId });
  return canonical || lineId || String(option?.properties?.lineName || "");
}

function collapseMetroRouteOptions(routes) {
  if (baseMapLineMode.value !== "metro-network") return routes;
  const seen = new Set();
  const result = [];
  for (const route of routes) {
    const canonical = metroCanonicalNameForOption(route);
    if (seen.has(canonical)) continue;
    seen.add(canonical);
    // 展示名统一为"地铁N号线"（纯号线）或规范化线名，去掉方向起终点后缀
    const displayName = PURE_METRO_LINE.test(canonical) ? `地铁${canonical}` : (canonical || route.name);
    result.push({ ...route, name: displayName });
  }
  return result;
}

async function openLineRoutePicker(point, webMercatorXY, lngLat, domEvent) {
  if (!Array.isArray(point) || !Array.isArray(webMercatorXY) || !selectModel.value?.name) {
    closeLineRoutePicker();
    return;
  }
  const requestSeq = ++routePickRequestSeq;
  const localHit = fallbackRouteOptions(point, lngLat);
  let routes = collapseMetroRouteOptions(localHit.routes.filter(routeOptionMatchesMode));
  let segmentLinks = [];

  if (!routes.length) {
    try {
      const res = await getRouteCandidates({
        datasource: selectModel.value.name,
        x: Number(webMercatorXY[0]),
        y: Number(webMercatorXY[1]),
        radiusMeters: routePickRadiusMeters(point),
        limit: 50,
      }, { silentError: true });
      if (requestSeq !== routePickRequestSeq) return;
      const candidates = Array.isArray(res?.data) ? res.data : [];
      routes = collapseMetroRouteOptions(
        dedupeRouteOptions(candidates.map(routeOptionFromCandidate)).filter(routeOptionMatchesMode),
      );
      segmentLinks = routes[0]?.segmentLinks || [];
    } catch {
      if (requestSeq !== routePickRequestSeq) return;
      routes = [];
      segmentLinks = [];
    }
  }
  if (!routes.length) {
    closeLineRoutePicker();
    clearLineSelection(); // 点击空白处取消选中
    return;
  }
  // 与数据管理一致：点中路段时先高亮最近路段；用户在列表选定后再高亮完整线路。
  selectedLineKey.value = "";
  selectedStationKey.value = "";
  selectedReverseStationKey.value = "";
  selectedRouteMapLinks.value = [];
  selectedReverseRouteMapLinks.value = [];
  selectedRouteDetail.value = null;
  selectedReverseRouteDetail.value = null;
  selectedLinePanel.value = null;
  selectedReverseLinePanel.value = null;
  selectedLineName.value = "";
  setSelectedBusStation(null);
  setReverseBusStation(null);
  lineMonitorRef.value?.clearSelection?.();
  setMonitorTransferRouteLinks([]);
  setMonitorReverseRouteLinks([]);
  setMonitorSelectedRouteLinks(segmentLinks);
  applyBusNetworkFocus();
  const clientX = domEvent?.clientX ?? point[0];
  const clientY = domEvent?.clientY ?? point[1];
  lineRoutePicker.x = clampPickerPosition(clientX, 292, window.innerWidth);
  lineRoutePicker.y = clampPickerPosition(clientY, 320, window.innerHeight);
  lineRoutePicker.routes = routes;
  lineRoutePicker.lngLat = lngLat;
  lineRoutePicker.point = point;
  lineRoutePicker.visible = true;
}

function selectLineFromPicker(route) {
  closeLineRoutePicker();
  if (!route?.feature) return;
  selectLineFromBusNetwork(route.feature, route.segmentLinks);
}

async function selectLineFromBusNetwork(feature, pendingLinks = []) {
  const fullFeature = fullBusLineFeature(feature) || feature;
  const props = fullFeature?.properties || {};
  selectedLineKey.value = String(props._lineKey || fullFeature?.id || "");
  selectedStationKey.value = "";
  selectedReverseStationKey.value = "";
  selectedStationPanel.value = null;
  selectedReverseStationPanel.value = null;
  selectedStationName.value = "";
  selectedReverseStationName.value = "";
  // 在真实 routeDetail 返回前只保留点中的真实路段，不画站点直连的近似线。
  setMonitorSelectedRouteLinks(pendingLinks);
  setSelectedBusStation(null);
  setReverseBusStation(null);
  const name = busLineName(props);
  if (!name) return;
  await nextTick();
  // 复用数据管理“按被点中的具体要素”选中的方式：把点中线路要素的属性（含方向 dir / route_id）交给 XLZL，按方向精确选中
  if (typeof lineMonitorRef.value?.selectLineByFeature === "function") {
    await lineMonitorRef.value.selectLineByFeature(props);
  } else {
    await lineMonitorRef.value?.selectLineByName?.(name);
  }
}

async function selectStationFromBusNetwork(feature) {
  const props = feature?.properties || {};
  const reverseFeature = pairedStationFeature(feature);
  const reverseProps = reverseFeature?.properties || {};
  selectedStationKey.value = String(props._stationKey || feature?.id || "");
  selectedReverseStationKey.value = String(reverseProps._stationKey || reverseFeature?.id || "");
  selectedLineKey.value = "";
  selectedRouteMapLinks.value = [];
  selectedReverseRouteMapLinks.value = [];
  selectedRouteDetail.value = null;
  selectedReverseRouteDetail.value = null;
  selectedLinePanel.value = null;
  selectedReverseLinePanel.value = null;
  selectedLineName.value = "";
  selectedStationPanel.value = null;
  selectedReverseStationPanel.value = null;
  selectedReverseStationName.value = "";
  setSelectedBusStation(feature);
  setReverseBusStation(reverseFeature);
  const coords = feature?.geometry?.coordinates;
  if (Array.isArray(coords) && coords.length >= 2 && MapRef.value) {
    const center = lngLatToWebMercator(Number(coords[0]), Number(coords[1]));
    if (center.every(Number.isFinite)) {
      MapRef.value.setCenter?.(center);
      MapRef.value.setZoom?.(15.5);
    }
  }
  setMonitorSelectedRouteLinks([]);
  setMonitorReverseRouteLinks([]);
  setMonitorTransferRouteLinks([]);
  const name = busStationName(props);
  if (!name) return;
  await nextTick();
  const stationPayload = {
    ...props,
    pairedFacilityId: reverseProps.facilityId || reverseProps.stop_id || reverseProps._stationKey || "",
    pairedStationName: busStationName(reverseProps),
    pairedStationKey: reverseProps._stationKey || reverseFeature?.id || "",
    pairedCoord: stationFeatureCoordObject(reverseFeature),
  };
  if (typeof stationMonitorRef.value?.selectStationByFeature === "function") {
    await stationMonitorRef.value.selectStationByFeature(stationPayload);
  } else {
    await stationMonitorRef.value?.selectStationByName?.(name);
  }
}

// 取消选中（点击地图空白处）
function clearLineSelection() {
  selectedLineKey.value = "";
  selectedRouteMapLinks.value = [];
  selectedReverseRouteMapLinks.value = [];
  selectedRouteDetail.value = null;
  selectedReverseRouteDetail.value = null;
  selectedLinePanel.value = null;
  selectedReverseLinePanel.value = null;
  selectedLineName.value = "";
  setMonitorSelectedRouteLinks([]);
  setMonitorReverseRouteLinks([]);
  setMonitorTransferRouteLinks([]);
  lineMonitorRef.value?.clearSelection?.();
}

function clearStationSelection() {
  selectedStationKey.value = "";
  selectedReverseStationKey.value = "";
  setSelectedBusStation(null);
  setReverseBusStation(null);
  selectedStationPanel.value = null;
  selectedReverseStationPanel.value = null;
  selectedStationName.value = "";
  selectedReverseStationName.value = "";
  stationMonitorRef.value?.clearSelection?.();
}

function handleBusNetworkMapClick(event) {
  closeLineRoutePicker();
  // 公交/地铁线网模式都可点选（命中层已按制式过滤）；路网模式不响应点选
  if (baseMapLineMode.value === "road-network") return;
  if (effectiveTab.value !== "线路客流监测" && effectiveTab.value !== "站点客流监测") return;
  const point = event?.data?.point;
  if (!Array.isArray(point)) return;
  if (effectiveTab.value === "站点客流监测") {
    // 热力图激活时站点图层整体隐藏，任何点击都查不到要素；
    // 此时不清选，否则误触一下就会关掉右侧已打开的站点面板
    if (props.mode === "pfa" && stationHeatmapEnabled.value) return;
    const stationFeature = firstRenderedBusFeature(point, [RM_LAYER_STATION_SELECTED, RM_LAYER_STATION_REVERSE_SELECTED, RM_LAYER_STATIONS], 10);
    if (stationFeature) {
      selectStationFromBusNetwork(stationFeature);
    } else {
      clearStationSelection(); // 点击空白处取消选中
    }
    return;
  }
  // 复用数据管理逻辑：点击路段弹出经过该路段的所有线路，由用户选择具体线路（含方向）；点击空白处取消选中
  const lineFeature = firstRenderedBusFeature(point, [
    RM_LAYER_LINES,
    RM_LAYER_LINE_FLOW,
    RM_LAYER_METRO_LINES,
    RM_LAYER_METRO_LINES_CASING,
    RM_LAYER_METRO_LINE_DASH,
  ], 7);
  if (!lineFeature) {
    clearLineSelection();
    return;
  }
  openLineRoutePicker(
    point,
    event?.data?.webMercatorXY,
    event?.data?.lngLat,
    event?.data?.event,
  );
}

function bindBusNetworkClickListener() {
  const mapInstance = MapRef.value;
  if (!mapInstance || busNetworkClickListenerId) return;
  busNetworkClickListenerId = mapInstance.addEventListener("handle:click", handleBusNetworkMapClick);
}

function unbindBusNetworkClickListener() {
  if (MapRef.value && busNetworkClickListenerId) {
    MapRef.value.removeEventListener("handle:click", busNetworkClickListenerId);
  }
  busNetworkClickListenerId = null;
}

function clearBusNetworkLayers() {
  const map = MapRef.value?.map;
  if (!map) return;
	  [
    RM_LAYER_DISPLAY_RANGE_OUTLINE,
    RM_LAYER_STATION_REVERSE_SELECTED_HALO,
    RM_LAYER_STATION_SELECTED_HALO,
    RM_LAYER_STATION_REVERSE_SELECTED,
    RM_LAYER_STATION_SELECTED,
    // 断面客流空心圈/站名、换乘站点、站点热力：不清会在切换到数据管理/线网优化后
    // 残留"选中线路的站点链/热力图"（跨模块共享同一地图实例）
    RM_LAYER_STATION_SEGMENT_RING,
    RM_LAYER_SEGMENT_STATION_LABELS,
    RM_LAYER_TRANSFER_LINES,
    RM_LAYER_TRANSFER_STATION_RING,
    RM_LAYER_TRANSFER_STATION_LABELS,
    RM_LAYER_STATION_HEAT,
    RM_LAYER_STATION_HEAT_BASE,
    RM_LAYER_STATION_LABELS,
    RM_LAYER_STATIONS,
    RM_LAYER_LINE_FLOW,
    RM_LAYER_LINE_FLOW_CASING,
    RM_LAYER_METRO_LINE_DASH,
    RM_LAYER_METRO_LINES,
    RM_LAYER_METRO_LINES_CASING,
    RM_LAYER_LINES,
  ].forEach((layerId) => {
    if (map.getLayer?.(layerId)) map.removeLayer(layerId);
  });
	  [
    RM_SOURCE_DISPLAY_RANGE,
    RM_SOURCE_REVERSE_SELECTED_STATION,
    RM_SOURCE_SELECTED_STATION,
    RM_SOURCE_SEGMENT_STATIONS,
    RM_SOURCE_TRANSFER_STATIONS,
    RM_SOURCE_TRANSFER_LINES,
    RM_SOURCE_STATION_HEAT,
    RM_SOURCE_STATION_HEAT_BASE,
    RM_SOURCE_STATIONS,
    RM_SOURCE_LINES,
  ].forEach((sourceId) => {
    if (map.getSource?.(sourceId)) map.removeSource(sourceId);
  });
  busNetworkSourceRefs = new Map();
  stationHeatLayersOnTop = false;
  busNetworkCollections = {
    lines: emptyFeatureCollection(),
    stations: emptyFeatureCollection(),
  };
  busNetworkIndexes = createEmptyBusNetworkIndexes();
  busNetworkRawLines = [];
  busNetworkRawFacilities = [];
  busNetworkRevision.value += 1;
}

const minLineWidth = computed(() => 0.1);
const maxLineWidth = computed(() => 2);
const minVehicleSize = computed(() => 20);
const maxVehicleSize = computed(() => 72);

const lineWidthZoomScale = computed(() => {
  const delta = mapZoom.value - referenceZoom.value;
  const scale = Math.pow(2, 0.18 * delta);
  const clamped = Math.max(0.45, Math.min(1.55, scale));
  // 量化到 0.05 步长：连续缩放期间该值原本每个 zoom 事件都变，
  // computedLineWidth/computedFlowWidthStep 及其 provide 链（含 XLZL 的图层线宽 watch）全程连锁执行；
  // 量化后一次缩放手势只落几档，静止时收敛值与原先偏差 ≤2.5%，视觉无感
  return Math.round(clamped * 20) / 20;
});
const computedLineWidth = computed(() => {
  // 其他运行监测图层仍使用旧的标称宽度；公交线网直接使用数据管理的像素值。
  return Math.max(3, lineWidth.value * (20 / 1.2) * lineWidthZoomScale.value);
});
const computedFlowWidthStep = computed(() => Math.max(6, Math.min(18, 14 * lineWidthZoomScale.value)));

provide("LineWidthRef", computedLineWidth);
provide("FlowWidthStepRef", computedFlowWidthStep);
provide("FlowControlRef", flowControl);
provide("StationSizeRef", stationSize);
provide("VehicleSizeRef", vehicleSize);
provide("VehicleVisibilityModeRef", vehicleVisibilityMode);

// 缩放期间 lineWidthZoomScale 每个 zoom 事件都变，两个 computed 随之连续变化：
// rAF 合帧后一帧最多应用一次，避免每个事件都全量下发图层宽度（原先是缩放掉帧的主因）
let lineWidthApplyFrame = null;
watch(computedLineWidth, () => {
  if (lineWidthApplyFrame != null) return;
  lineWidthApplyFrame = requestAnimationFrame(() => {
    lineWidthApplyFrame = null;
    applyLineWidth();
  });
});
let flowWidthStepApplyFrame = null;
watch(computedFlowWidthStep, () => {
  if (flowWidthStepApplyFrame != null) return;
  flowWidthStepApplyFrame = requestAnimationFrame(() => {
    flowWidthStepApplyFrame = null;
    applyFlowWidthStep();
  });
});
// 公交线网 MapLibre 图层的 paint 值只依赖滑块基础值（zoom 缩放由 interpolate 表达式原生处理），
// 单独监听滑块输入，不再让 applyLineWidth 在每个缩放帧连带全量 paint 重刷（约 12 次 setPaintProperty）。
// 滑块 @input 每次拖动事件都会落值：与线宽/流宽同款 rAF 合帧，拖动期间一帧至多一次全量 paint
let busNetworkPaintFrame = null;
function scheduleBusNetworkPaint() {
  if (typeof requestAnimationFrame !== "function") {
    applyBusNetworkPaint();
    return;
  }
  if (busNetworkPaintFrame != null) return;
  busNetworkPaintFrame = requestAnimationFrame(() => {
    busNetworkPaintFrame = null;
    applyBusNetworkPaint();
  });
}
watch(busNetworkHitLineWidth, scheduleBusNetworkPaint);
// 站点尺寸滑块变化：图层标记尺寸 + paint（icon-size interpolate 表达式依赖 stationSize）一并触发
watch(stationSize, () => {
  applyStationSize();
  scheduleBusNetworkPaint();
});
// pfaSegmentFlowStops 为 computed，每次整体重建数组，监听引用即可，无需 deep
watch(pfaSegmentFlowStops, applyPfaSegmentFlowStyle);
watch(pfaSegmentLayerOpacity, applyPfaSegmentOpacity);
// 需求2：着色表达式变化（routePanel 就绪 / 色阶配置调整 / 数据刷新）时实时重着色
watch([lineFlowColorExpression, metroLineFlowColorExpression, lineFlowWidthFactorExpression, metroLineWidthFactorExpression], applyLineFlowColoring);
// 模型就绪后即加载线路客流整包（共享缓存，与 XLZL 共用同一次下载），任意 tab 首屏即可着色
watch(
  [effectiveTab, () => selectModel.value?.name, isModelReady],
  () => {
    if (shouldLoadTransitNetworkForCurrentTab()) {
      ensureLineFlowPanel();
    }
  },
  { immediate: true },
);
watch(vehicleSize, () => {
  applyVehicleSize();
});
watch(vehicleVisibilityMode, () => {
  applyVehicleVisibilityMode();
});

let fpsFrameId = null;
let fpsLastAt = 0;
let fpsWindowStart = 0;
let fpsFrames = 0;
let lastMapMotionAt = 0;
const perfSamples = [];
const isPerfProbeEnabled =
  import.meta.env.DEV || (typeof window !== "undefined" && window.__GJ_ENABLE_PERF_PROBE__ === true);

function publishPerfProbe(fps = 0, now = performance.now()) {
  if (!isPerfProbeEnabled) return;
  const moving = now - lastMapMotionAt < 220;
  const sample = {
    fps: Math.round(fps * 10) / 10,
    hz: Math.round(fps),
    samples: perfSamples.slice(-120),
    tab: effectiveTab.value,
    moving,
    timestamp: now,
  };
  window.__GJ_VIS_PERF__ = sample;
  document.documentElement.dataset.gjVisFps = String(sample.fps);
  document.documentElement.dataset.gjVisHz = String(sample.hz);
  document.documentElement.dataset.gjVisMoving = moving ? "1" : "0";
  document.documentElement.dataset.gjVisTab = effectiveTab.value;
}

function startPerfProbe() {
  if (!isPerfProbeEnabled) return;
  if (fpsFrameId || typeof requestAnimationFrame !== "function") return;
  fpsLastAt = performance.now();
  fpsWindowStart = fpsLastAt;
  fpsFrames = 0;
  const tick = (now) => {
    const delta = now - fpsLastAt;
    fpsLastAt = now;
    fpsFrames += 1;
    if (delta > 0 && delta < 1000) {
      perfSamples.push(1000 / delta);
      if (perfSamples.length > 240) {
        perfSamples.splice(0, perfSamples.length - 240);
      }
    }
    if (now - fpsWindowStart >= 500) {
      publishPerfProbe((fpsFrames * 1000) / Math.max(1, now - fpsWindowStart), now);
      fpsWindowStart = now;
      fpsFrames = 0;
    }
    fpsFrameId = requestAnimationFrame(tick);
  };
  fpsFrameId = requestAnimationFrame(tick);
}

function stopPerfProbe() {
  if (fpsFrameId) {
    cancelAnimationFrame(fpsFrameId);
    fpsFrameId = null;
  }
}

let zoomListenerId = null;
let centerListenerId = null;
let rotateListenerId = null;
let resizeTimerId = null;

function scheduleMapResize(delay = 0) {
  if (!MapRef.value?.map) return;
  if (delay > 0) {
    if (resizeTimerId) {
      clearTimeout(resizeTimerId);
    }
    resizeTimerId = setTimeout(() => {
      resizeTimerId = null;
      MapRef.value?.map?.resize();
    }, delay);
    return;
  }
  nextTick(() => {
    MapRef.value?.map?.resize();
  });
}

function handleDocumentKeydown(event) {
  if (event.key !== "Escape") return;
  showLineWidthPopover.value = false;
  showRangePopover.value = false;
  showLineFlowScalePopover.value = false;
  showSegmentFlowScalePopover.value = false;
  showStationHeatScalePopover.value = false;
}

function handleZoomIn() {
  if (MapRef.value) {
    const currentZoom = MapRef.value.zoom;
    MapRef.value.setZoom(currentZoom + 1);
  }
}

function handleZoomOut() {
  if (MapRef.value) {
    const currentZoom = MapRef.value.zoom;
    MapRef.value.setZoom(currentZoom - 1);
  }
}

function handleToggle3D() {
  if (MapRef.value) {
    if (is3DActive.value) {
      MapRef.value.setPitchAndRotation(90, 0);
      MapRef.value.enableRotate = false;
      is3DActive.value = false;
    } else {
      MapRef.value.enableRotate = true;
      MapRef.value.setPitchAndRotation(45, MapRef.value.rotation);
      is3DActive.value = true;
    }
  }
}

function handleResetCompass() {
  if (MapRef.value) {
    MapRef.value.setPitchAndRotation(90, 0);
    MapRef.value.enableRotate = false;
    is3DActive.value = false;
  }
}

function handleToggleLineWidthPopover() {
  showRangePopover.value = false;
  showLineWidthPopover.value = !showLineWidthPopover.value;
}

function closeRangePopover() {
  showRangePopover.value = false;
}

async function loadDisplayRanges(options = {}) {
  const { force = false } = options;
  const seq = ++displayRangeRequestSeq;
  isLoadingDisplayRanges.value = true;
  displayRangeError.value = "";
  try {
    const data = await getCachedAdminDistricts(DISPLAY_AREA_NAME, { force });
    if (seq !== displayRangeRequestSeq) return;
    adminDistrictCollection.value = normalizeAdminDistrictCollection(data?.collection);
    // 行政区几何换代：Worker 端按 (名称, 版本) 缓存的上下文必须随之失效
    displayRangeContextRev += 1;
    displayRangeContextSentKeys.clear();
    const names = Array.isArray(data?.districts)
      ? data.districts.map((item) => String(item || "").trim()).filter(Boolean)
      : districtNamesFromCollection(adminDistrictCollection.value);
    displayRangeList.value = [
      DISPLAY_RANGE_ALL,
      ...names.filter((name, index, list) => name !== DISPLAY_RANGE_ALL && list.indexOf(name) === index),
    ];
    if (!displayRangeList.value.includes(selectedDisplayRange.value)) {
      selectedDisplayRange.value = DISPLAY_RANGE_ALL;
    }
    syncBusNetworkDisplayRange();
  } catch (error) {
    if (seq !== displayRangeRequestSeq) return;
    adminDistrictCollection.value = emptyDistrictFeatureCollection();
    displayRangeContextRev += 1;
    displayRangeContextSentKeys.clear();
    displayRangeList.value = [DISPLAY_RANGE_ALL];
    selectedDisplayRange.value = DISPLAY_RANGE_ALL;
    displayRangeError.value = error?.message || "行政区范围加载失败";
  } finally {
    if (seq === displayRangeRequestSeq) {
      isLoadingDisplayRanges.value = false;
    }
  }
}

function toggleRangePopover() {
  if (selectedDisplayRange.value !== DISPLAY_RANGE_ALL) {
    selectedDisplayRange.value = DISPLAY_RANGE_ALL;
    closeRangePopover();
    return;
  }
  if (!showRangePopover.value) {
    showLineWidthPopover.value = false;
    closeLineRoutePicker();
    if (!displayRangeOptions.value.length && !isLoadingDisplayRanges.value) {
      loadDisplayRanges({ force: Boolean(displayRangeError.value) });
    }
  }
  showRangePopover.value = !showRangePopover.value;
}

function selectDisplayRange(rangeName) {
  const nextRange = String(rangeName || "").trim();
  if (!nextRange) return;
  if (nextRange === selectedDisplayRange.value) {
    closeRangePopover();
    return;
  }
  selectedDisplayRange.value = nextRange;
  closeRangePopover();
}

function applyLineWidth() {
  if (MapRef.value && MapRef.value.layers) {
    MapRef.value.layers.forEach((layer) => {
      if (typeof layer.setLineWidth === "function") {
        layer.setLineWidth(computedLineWidth.value);
      }
    });
  }
  monitorRoadLayer?.setLineWidth(computedLineWidth.value);
  // 不再连带 applyBusNetworkPaint：其 paint 输入不依赖 zoom 缩放系数，由 busNetworkHitLineWidth watch 触发
}

function applyFlowWidthStep() {
  if (MapRef.value && MapRef.value.layers) {
    MapRef.value.layers.forEach((layer) => {
      if (typeof layer.setFlowWidthStep === "function") {
        layer.setFlowWidthStep(computedFlowWidthStep.value);
      }
    });
  }
  monitorRoadLayer?.setFlowWidthStep(computedFlowWidthStep.value);
}

function applyStationSize() {
  if (MapRef.value && MapRef.value.layers) {
    MapRef.value.layers.forEach((layer) => {
      if (typeof layer.setMarkerSize === "function") {
        layer.setMarkerSize(stationSize.value);
      }
    });
  }
  // paint 重刷由 stationSize watch / syncAllLayerSettings 统一触发，避免 burst 每轮重复两遍
}

function applyVehicleSize() {
  if (MapRef.value && MapRef.value.layers) {
    MapRef.value.layers.forEach((layer) => {
      if (typeof layer.setVehicleSize === "function") {
        layer.setVehicleSize(vehicleSize.value);
      }
    });
  }
}

function applyVehicleVisibilityMode() {
  if (MapRef.value && MapRef.value.layers) {
    MapRef.value.layers.forEach((layer) => {
      if (typeof layer.setVehicleVisibilityMode === "function") {
        layer.setVehicleVisibilityMode(vehicleVisibilityMode.value);
      }
    });
  }
}

let syncLayersRetryTimer = null;

function syncAllLayerSettings() {
  applyLineWidth();
  applyFlowWidthStep();
  applyFlowControl();
  applyPfaSegmentOpacity();
  applyStationSize();
  applyVehicleSize();
  applyVehicleVisibilityMode();
  // 全量 paint 每轮显式一次（原先经 applyLineWidth/applyStationSize 两条路径各刷一遍）
  applyBusNetworkPaint();
  syncBaseMapLayerVisibility();
}

// 兜底重试轮数收敛：各 setter 已有相等短路（宽度 epsilon / stops 引用 / 尺寸 epsilon），
// 图层异步就绪主要发生在首个 500ms 内，2-3 轮足以覆盖；原先 4-8 轮连发在切 tab 后约 1s 内反复全量重刷
function scheduleLayerSyncBurst(remaining = 3) {
  if (syncLayersRetryTimer) {
    clearTimeout(syncLayersRetryTimer);
    syncLayersRetryTimer = null;
  }
  const run = (left) => {
    syncAllLayerSettings();
    if (left > 1) {
      syncLayersRetryTimer = setTimeout(() => run(left - 1), 260);
    } else {
      syncLayersRetryTimer = null;
    }
  };
  run(Math.min(remaining, 3));
}

function handleLineWidthChange(val) {
  lineWidth.value = val;
  applyLineWidth();
  applyFlowWidthStep();
}

// 只负责 clamp 落值：图层透明度统一由 pfaSegmentLayerOpacity 的 watcher 单一数据流下发
function handlePfaSegmentOpacityChange(val) {
  pfaSegmentOpacity.value = Math.max(0, Math.min(100, Number(val) || 0));
}

function handleVehicleSizeChange(val) {
  vehicleSize.value = val;
  applyVehicleSize();
}

function handleVehicleVisibilityModeChange(val) {
  vehicleVisibilityMode.value = val;
  applyVehicleVisibilityMode();
}

function applyFlowControl() {
  if (MapRef.value && MapRef.value.layers) {
    MapRef.value.layers.forEach((layer) => {
      if (typeof layer.setFlowControl === "function") {
        if (layer === monitorSelectedRouteSegmentLayer || layer === monitorReverseRouteSegmentLayer) {
          layer.setFlowControl(true);
          return;
        }
        layer.setFlowControl(flowControl.value);
      }
    });
  }
  monitorSelectedRouteSegmentLayer?.setFlowControl?.(true);
  monitorSelectedRouteSegmentLayer?.setFlowStyleStops?.(pfaSegmentFlowStops.value);
  monitorReverseRouteSegmentLayer?.setFlowControl?.(true);
  monitorReverseRouteSegmentLayer?.setFlowStyleStops?.(pfaSegmentFlowStops.value);
  monitorRoadLayer?.setFlowControl(flowControl.value);
}

watch(MapRef, (mapInstance) => {
  setMapCenter();
  
  if (mapInstance) {
    bindBusNetworkClickListener();
    ensureTransitNetworkForCurrentTab();
    scheduleMapResize();
    scheduleMapResize(450);

    mapZoom.value = mapInstance.zoom;
    if (!isZoomCaptured) {
      referenceZoom.value = mapInstance.zoom;
      isZoomCaptured = true;
    }
    mapPitch.value = mapInstance.pitch;
    mapRotation.value = mapInstance.rotation;
    is3DActive.value = mapInstance.enableRotate || mapInstance.pitch !== 90 || mapInstance.rotation !== 0;
    
    // Remove old listeners if any
    if (zoomListenerId) {
      mapInstance.removeEventListener("update:zoom", zoomListenerId);
    }
    if (centerListenerId) {
      mapInstance.removeEventListener("update:center", centerListenerId);
    }
    if (rotateListenerId) {
      mapInstance.removeEventListener("update:camera:rotate", rotateListenerId);
    }

    // Add new listeners
    zoomListenerId = mapInstance.addEventListener("update:zoom", (e) => {
      lastMapMotionAt = performance.now();
      mapZoom.value = e.data;
    });
    centerListenerId = mapInstance.addEventListener("update:center", () => {
      lastMapMotionAt = performance.now();
    });
    rotateListenerId = mapInstance.addEventListener("update:camera:rotate", (e) => {
      lastMapMotionAt = performance.now();
      mapPitch.value = e.data.newPitch;
      mapRotation.value = e.data.newRotation;
      if (e.data.newPitch !== 90 || e.data.newRotation !== 0) {
        is3DActive.value = true;
      }
    });

    scheduleLayerSyncBurst(5);
    syncBaseMapLayerVisibility();
  }
});

watch(baseMapLineMode, (mode) => {
  reconcilePfaSelectionForBaseMapMode(mode);
  if (mode === "road-network" && shouldLoadTransitNetworkForCurrentTab()) {
    ensureMonitorRoadLayer();
  }
  syncBaseMapLayerVisibility();
});

watch(selectedDisplayRange, () => {
  if (typeof window !== "undefined") {
    window.localStorage?.setItem(DISPLAY_RANGE_STORAGE_KEY, selectedDisplayRange.value || DISPLAY_RANGE_ALL);
  }
  closeLineRoutePicker();
  clearLineSelection();
  clearStationSelection();
  // 路网瓦片不支持行政区裁剪，选行政区时退回公交线网；地铁线网走 geojson 裁剪，可保留
  if (selectedDisplayRange.value !== DISPLAY_RANGE_ALL && baseMapLineMode.value === "road-network") {
    baseMapLineMode.value = "bus-network";
  }
  syncBusNetworkDisplayRange();
  nextTick(fitDisplayRangeContext);
});

watch(
  [() => selectModel.value?.name, isModelReady],
  ([modelName]) => {
    if (!isModelReady.value || !modelName) return;
    closeLineRoutePicker();
    selectedLineKey.value = "";
    selectedStationKey.value = "";
    selectedReverseStationKey.value = "";
    selectedRouteMapLinks.value = [];
    selectedReverseRouteMapLinks.value = [];
    selectedRouteStationFlows.value = [];
    selectedRouteDetail.value = null;
    selectedReverseRouteDetail.value = null;
    selectedLinePanel.value = null;
    selectedReverseLinePanel.value = null;
    selectedLineName.value = "";
    selectedStationPanel.value = null;
    selectedReverseStationPanel.value = null;
    selectedStationName.value = "";
    selectedReverseStationName.value = "";
    // 需求2：切换模型后重置线路客流整包并按需重新加载
    lineFlowPanel.value = null;
    lineFlowPanelModel = "";
    setMonitorSelectedRouteLinks([]);
    setMonitorReverseRouteLinks([]);
    setMonitorTransferRouteLinks([]);
    setSelectedBusStation(null);
    setReverseBusStation(null);
    if (shouldLoadTransitNetworkForCurrentTab()) {
      ensureLineFlowPanel();
      if (monitorBusRouteLayer) {
        monitorBusRouteLayer.setLineClipContext(activeDisplayRangeContext.value);
        monitorBusRouteLayer.setTileSource(modelName, { tileRequest: getRouteTileBinary });
      } else {
        ensureMonitorBusRouteLayer();
      }
      loadBusNetwork();
      if (monitorRoadLayer) {
        monitorRoadLayer.setTileSource(modelName);
      } else if (baseMapLineMode.value === "road-network") {
        ensureMonitorRoadLayer();
      }
    } else {
      pauseTransitNetworkTiles();
    }
  },
);

watch(isRightPanelVisible, (visible) => {
  if (visible && (effectiveTab.value === "数据总览" || isVehicleMonitorTab.value)) {
    isRightCollapsed.value = false;
  }
});

watch(rightPanelHasContent, (hasContent) => {
  if (hasContent && isVehicleMonitorTab.value) {
    showRightPanel.value = true;
    isRightCollapsed.value = false;
  }
});

// 监听标签切换和左右侧边栏折叠状态，动态触发地图重绘 resize，解决底图只渲染局部区域的经典Bug
watch(
  [effectiveTab, isRunMonitorLeftCollapsed, isRightCollapsed, showRightPanel, rightPanelHasContent],
  () => {
    if (MapRef.value && MapRef.value.map) {
      scheduleMapResize();
      scheduleMapResize(450);
    }
  }
);

function refreshSchemeAndModelLists() {
  handleGetSchemeList({ silent: true });
  handleGetModelList({ silent: true });
}

// 仅当有模型处于过渡态（排队/加载/建缓存）时保持 20s 轮询；
// 全部稳定时降频为 100s 心跳（仅为感知他人操作），原先无条件每 20s 双接口约 360 次/小时
function hasTransitionalModels() {
  return (modelList.value || []).some((item) =>
    item?.loadStage === "queued"
    || item?.loadStage === "loading_config"
    || item?.loadStatus
    || item?.cacheStatus === "queued"
    || item?.cacheStatus === "building");
}
let schemePollTick = 0;
const ins = setInterval(() => {
  // 页面不可见时跳过本轮轮询，避免后台标签页持续请求接口
  if (document.visibilityState === "hidden") return;
  schemePollTick += 1;
  if (!hasTransitionalModels() && schemePollTick % 5 !== 0) return;
  refreshSchemeAndModelLists();
}, 1000 * 20);

function handlePollingVisibilityChange() {
  // 恢复可见时立即补一次刷新，弥补隐藏期间被跳过的轮询
  if (document.visibilityState === "visible") {
    refreshSchemeAndModelLists();
  }
}
document.addEventListener("visibilitychange", handlePollingVisibilityChange);

async function handleAuthChanged() {
  datebase.value.scheme = "";
  setActiveModel("");
  clearBackgroundTask();
  schemeList.value = [];
  modelList.value = [];
  await handleGetSchemeList({ autoSelect: true });
}

onMounted(() => {
  // 地图为跨路由共享实例（注入的 MapRef）。若挂载时地图已存在，watch(MapRef) 不会触发，
  // 需在此补做地图初始化，否则上一个页面卸载时已解绑点击/清空图层，本页将无法点选线路/站点。
  if (MapRef.value) {
    bindBusNetworkClickListener();
    ensureTransitNetworkForCurrentTab();
    scheduleMapResize();
  }
  if (showDisplayRangeControl.value) {
    loadDisplayRanges();
  }
  if (isPerfProbeEnabled) {
    startPerfProbe();
  }
  observeLeftPanelSize();
  window.addEventListener("resize", centerLeftPanel);
  window.addEventListener("auth:changed", handleAuthChanged);
  document.addEventListener("keydown", handleDocumentKeydown);
  const bootstrapSimulation = isSimulationMode.value
    ? handleGetSchemeList({ autoSelect: true }).then(async () => {
    if (datebase.value.scheme && !modelList.value.length) {
      const list = await handleGetModelList();
      if (list.length && (!datebase.value.model || !list.some((item) => item.name === datebase.value.model))) {
        const preferred = list.find((item) => item.name === restoredSelection.model) || pickReadyModel(list) || list[0];
        setActiveModel(preferred.name);
      }
      await ensureSelectedModelReady();
    }
  })
    : Promise.resolve();
  bootstrapSimulation.finally(() => {
    initialModelBootstrap.value = false;
    isRestoringSelection = false;
    observeLeftPanelSize();
  });

  scheduleLayerSyncBurst(8);
});
onUnmounted(() => {
  modelLoadSeq++;
  backgroundTaskSeq++;
  // 作废在途的线网/热力加载：地图跨模块共享，若不作废，慢请求回调会在卸载后
  // 把 rm-* 图层重新加回地图，导致数据管理/线网优化页面残留本模块的线网与站点
  busNetworkRequestSeq++;
  stationHeatSeq++;
  routePickRequestSeq++;
  // 行政区列表的慢请求同样会在回调里重建 rm-display-range 图层/Worker，必须一并作废
  displayRangeRequestSeq++;
  overallFlowAbortController?.abort();
  overallFlowAbortController = null;
  if (pfaSegmentStyleFrameId && typeof cancelAnimationFrame === "function") {
    cancelAnimationFrame(pfaSegmentStyleFrameId);
    pfaSegmentStyleFrameId = null;
  }
  if (lineWidthApplyFrame != null && typeof cancelAnimationFrame === "function") {
    cancelAnimationFrame(lineWidthApplyFrame);
    lineWidthApplyFrame = null;
  }
  if (busNetworkPaintFrame != null && typeof cancelAnimationFrame === "function") {
    cancelAnimationFrame(busNetworkPaintFrame);
    busNetworkPaintFrame = null;
  }
  displayRangeWorkerDisposed = true;
  displayRangeWorkerPending.clear();
  displayRangeWorker?.terminate();
  displayRangeWorker = null;
  displayRangeNetworkSentKey = "";
  displayRangeContextSentKeys.clear();
  if (flowWidthStepApplyFrame != null && typeof cancelAnimationFrame === "function") {
    cancelAnimationFrame(flowWidthStepApplyFrame);
    flowWidthStepApplyFrame = null;
  }
  stopPerfProbe();
  unbindBusNetworkClickListener();
  clearBusNetworkLayers();
  monitorSelectedRouteLayer?.dispose();
  monitorSelectedRouteLayer = null;
  monitorSelectedRouteGlowLayer?.dispose();
  monitorSelectedRouteGlowLayer = null;
  monitorSelectedRouteSegmentLayer?.dispose();
  monitorSelectedRouteSegmentLayer = null;
  monitorReverseRouteSegmentLayer?.dispose();
  monitorReverseRouteSegmentLayer = null;
  monitorReverseRouteLayer?.dispose();
  monitorReverseRouteLayer = null;
  monitorReverseRouteGlowLayer?.dispose();
  monitorReverseRouteGlowLayer = null;
  monitorTransferRouteLayer?.dispose();
  monitorTransferRouteLayer = null;
  monitorTransferRouteGlowLayer?.dispose();
  monitorTransferRouteGlowLayer = null;
  monitorBusRouteLayer?.dispose();
  monitorBusRouteLayer = null;
  monitorRoadLayer?.dispose();
  monitorRoadLayer = null;
  leftPanelResizeObserver?.disconnect();
  leftPanelResizeObserver = null;
  window.removeEventListener("resize", centerLeftPanel);
  window.removeEventListener("auth:changed", handleAuthChanged);
  document.removeEventListener("keydown", handleDocumentKeydown);
  document.removeEventListener("visibilitychange", handlePollingVisibilityChange);
  sessionStorage.removeItem("request_params");
  clearInterval(ins);
  if (resizeTimerId) {
    clearTimeout(resizeTimerId);
    resizeTimerId = null;
  }
  if (syncLayersRetryTimer) {
    clearTimeout(syncLayersRetryTimer);
    syncLayersRetryTimer = null;
  }
  if (MapRef.value) {
    if (zoomListenerId) {
      MapRef.value.removeEventListener("update:zoom", zoomListenerId);
    }
    if (rotateListenerId) {
      MapRef.value.removeEventListener("update:camera:rotate", rotateListenerId);
    }
    if (centerListenerId) {
      MapRef.value.removeEventListener("update:center", centerListenerId);
    }
  }
});
</script>

<style lang="scss" scoped>
.datebase_box,
.box1 {
  scale: var(--app-panel-scale);
}

/* 客流分析侧栏：线路客流分析子功能展开动效 */
.slide-fade-enter-active,
.slide-fade-leave-active {
  transition:
    opacity var(--dm2-dur, 240ms) var(--dm2-ease-out, ease),
    transform var(--dm2-dur, 240ms) var(--dm2-ease-out, ease);
}
.slide-fade-enter-from,
.slide-fade-leave-to {
  opacity: 0;
  transform: translateY(-6px);
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
  max-width: min(62vw, 680px);
  min-width: 0;

  .data-source-segment {
    display: inline-flex;
    align-items: center;
    height: 34px;
    padding: 3px;
    border: 1px solid var(--app-border-strong);
    border-radius: var(--app-card-radius);
    background: rgba(251, 253, 255, 0.9);
    box-shadow: 0 8px 20px rgba(37, 99, 235, 0.08);
    flex: 0 0 auto;

    button {
      height: 26px;
      min-width: 42px;
      padding: 0 10px;
      border: 0;
      border-radius: 7px;
      background: transparent;
      color: var(--app-muted);
      font: inherit;
      font-size: 12px;
      font-weight: 700;
      cursor: pointer;
      transition: background 160ms ease, color 160ms ease, box-shadow 160ms ease;

      &.active {
        color: var(--app-blue);
        background: rgba(21, 105, 222, 0.1);
        box-shadow: 0 0 0 1px rgba(21, 105, 222, 0.12) inset;
      }
    }
  }

  .handle {
    cursor: default;
    font-size: 0.95rem;
    font-weight: 600;
    color: #374151;
    text-shadow: none;
    white-space: nowrap;
  }
  .model-option {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-sm);
    min-width: 0;
    width: 100%;

    .model-option-main {
      display: flex;
      align-items: center;
      gap: var(--space-sm);
      min-width: 0;
      flex: 1;

      span:first-child {
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }

    .model-option-actions {
      display: flex;
      align-items: center;
      flex-shrink: 0;
      gap: 2px;
    }
  }

  .load-error {
    max-width: 180px;
    color: var(--app-coral);
    font-size: 12px;
    font-weight: 600;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .el-select {
    width: clamp(150px, 14vw, 210px);
    
    :deep(.el-input__wrapper) {
      background-color: rgba(251, 253, 255, 0.88) !important;
      box-shadow: 0 0 0 1px var(--app-border-strong) inset !important;
      border-radius: var(--app-card-radius);
      padding: 6px 12px;
      transition: background-color 0.2s ease, box-shadow 0.2s ease;
      
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
        &::placeholder {
          color: rgba(18, 48, 79, 0.5);
        }
      }
      
      .el-select__caret {
        color: var(--app-cyan) !important;
        font-size: 14px;
      }
    }
  }
}

.model-background-status {
  position: fixed;
  top: calc(var(--app-header-height) + 8px);
  right: calc(var(--app-edge) + 64px);
  z-index: calc(var(--z-header) + 9);
  display: grid;
  grid-template-columns: minmax(0, 1fr) 110px auto;
  align-items: center;
  gap: var(--space-sm);
  width: min(46vw, 520px);
  min-width: 360px;
  padding: 8px 10px;
  scale: var(--app-panel-scale);
  transform-origin: right top;
  border: 1px solid rgba(21, 105, 222, 0.16);
  border-radius: var(--app-panel-radius);
  background: rgba(251, 253, 255, 0.94);
  box-shadow: 0 10px 30px rgba(31, 45, 61, 0.12);
  color: var(--app-ink);

  .model-background-main {
    display: flex;
    align-items: center;
    gap: var(--space-xs);
    min-width: 0;
  }

  .model-background-dot {
    width: 8px;
    height: 8px;
    flex-shrink: 0;
    border-radius: 50%;
    background: var(--app-blue);
    box-shadow: 0 0 0 4px rgba(21, 105, 222, 0.12);
  }

  .model-background-title,
  .model-background-message {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .model-background-title {
    font-size: 12px;
    font-weight: 760;
  }

  .model-background-message {
    color: var(--app-muted);
    font-size: 12px;
  }

  .model-background-progress {
    width: 110px;
  }
}
.box1 {
  box-sizing: border-box;
  padding: var(--space-sm);
  position: fixed;
  z-index: var(--z-panel);
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  width: min(430px, calc((100vw - 48px) / var(--app-panel-scale)));
  max-height: calc((100vh - 132px) / var(--app-panel-scale));
  min-width: min(430px, calc((100vw - 48px) / var(--app-panel-scale)));
  min-height: 0;
  cursor: default;
  user-select: text;
  transform-origin: top left;
  transition: transform var(--app-motion-slow) var(--app-ease-out);
  
  &.collapsed {
    transform: translateX(-100%) !important;
    pointer-events: none;

    .collapse-tab {
      pointer-events: auto;
    }
  }
  
  .tab_list,
  .handle {
    cursor: grab;
    user-select: none;

    &:active {
      cursor: grabbing;
    }
  }

  &.cache-loading-panel {
    justify-content: center;
    gap: var(--space-md);
    padding: var(--space-lg);
    background: rgba(251, 253, 255, 0.94);
    border: 1px solid var(--app-border-strong);
    border-radius: var(--app-panel-radius);
    box-shadow: var(--app-shadow-panel);
  }

  .cache-loading-title {
    color: var(--app-ink);
    font-size: 18px;
    font-weight: 760;
  }

  .cache-loading-message {
    color: var(--app-muted);
    font-size: 13px;
    line-height: 1.5;
    word-break: break-word;
  }

  .cache-loading-progress {
    width: 100%;
  }

  .cache-loading-meta {
    display: flex;
    justify-content: space-between;
    gap: var(--space-md);
    color: var(--app-muted);
    font-size: 12px;
  }

  .tab_list {
    display: flex;
    align-items: center;
    background-color: rgba(21, 105, 222, 0.06);
    border: 1px solid rgba(21, 105, 222, 0.14);
    border-radius: var(--app-card-radius);
    width: 100%;
    gap: var(--space-xs);
    padding: var(--space-2xs);
    .el-button {
      flex: 1;
      margin: 0;
      min-height: 36px;
      border-radius: 4px;
      min-width: 0;
      font-weight: 700;
      white-space: normal;
    }
  }

  .sub_tab_list_wrapper {
    display: flex;
    justify-content: center;
    width: 100%;
    
    .custom-sub-tabs {
      width: 100%;
      display: flex;
      background-color: rgba(21, 105, 222, 0.05);
      border-radius: var(--app-card-radius);
      padding: var(--space-2xs);
      border: 1px solid rgba(21, 105, 222, 0.1);
      
      :deep(.el-radio-button) {
        flex: 1;
        display: flex;
        
        .el-radio-button__inner {
          width: 100%;
          border: none !important;
          background: transparent !important;
          color: var(--app-muted);
          font-weight: 500;
          font-size: 13px;
          border-radius: 4px !important;
          padding: 6px 0;
          box-shadow: none !important;
          transition:
            background-color var(--app-motion-normal) var(--app-ease-out),
            color var(--app-motion-normal) var(--app-ease-out),
            transform var(--app-motion-fast) var(--app-ease-press);
          
          &:hover {
            color: var(--app-blue);
            transform: translateY(-1px);
          }
        }
        
        &.is-active {
          .el-radio-button__inner {
            background-color: var(--app-card-bg) !important;
            color: var(--app-blue) !important;
            font-weight: bold;
            box-shadow: none !important;
          }
        }
      }
    }
  }

  .scroll_box {
    height: 0 !important;
    flex: 1;
  }
}

.line-width-popover {
  position: absolute;
  right: 48px;
  top: 76px;
  width: min(240px, calc(100vw - 96px));
  background: var(--app-panel-bg);
  border: 1px solid var(--app-border);
  border-radius: var(--app-panel-radius);
  box-shadow: var(--app-shadow-sm);
  padding: var(--space-sm) var(--space-md);
  z-index: calc(var(--z-popover) - 1);
  display: flex;
  flex-direction: column;
  gap: 8px;
  box-sizing: border-box;

  .popover-title {
    font-size: 13px;
    font-weight: 700;
    color: var(--app-ink);
    border-bottom: 1px solid rgba(21, 105, 222, 0.09);
    padding-bottom: 6px;
    margin: 0;
  }

  .popover-content {
    .slider-row {
      display: flex;
      flex-direction: column;
      gap: 4px;

      .label {
        font-size: 11px;
        color: var(--app-muted);
        display: flex;
        justify-content: space-between;
        
        .val-text {
          font-family: var(--app-font-number);
          color: var(--app-cyan);
          font-weight: bold;
        }
      }
      
      .el-slider {
        margin-top: 4px;
        --el-slider-main-bg-color: var(--app-cyan);
        --el-slider-runway-bg-color: color-mix(in oklch, var(--app-blue-soft) 70%, white);
      }
    }

    .flow-control-row,
    .vehicle-visibility-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-top: 12px;
      padding-top: 10px;
      border-top: 1px solid rgba(21, 105, 222, 0.09);
      font-size: 11px;
      font-weight: 600;
      color: var(--app-muted);

      .el-select {
        width: 126px;
      }
    }

  }
}

/* 需求2/11：地图左下角浮动图例（避开左侧栏与地图控件） */
.map-flow-legend {
  position: fixed;
  left: calc(276px * var(--app-layout-scale, 1));
  bottom: 20px;
  z-index: calc(var(--z-panel, 1300) + 10);
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}

.map-legend-card {
  min-width: 152px;
  max-width: 230px;
  padding: 10px 12px;
  border: 1px solid var(--app-border, rgba(21, 105, 222, 0.16));
  border-radius: 10px;
  background: var(--app-panel-bg, rgba(255, 255, 255, 0.94));
  box-shadow: var(--app-shadow-sm, 0 8px 24px rgba(13, 38, 76, 0.16));
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 11px;
  color: var(--app-ink-soft, #475467);
}

.map-legend-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 2px;
}

.map-legend-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--app-ink, #344054);
}

.map-legend-gear {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: var(--app-muted, #667085);
  cursor: pointer;
  transition: background-color 160ms ease, color 160ms ease, border-color 160ms ease;

  &:hover {
    color: var(--app-blue, #1569de);
    border-color: rgba(21, 105, 222, 0.24);
    background: rgba(21, 105, 222, 0.07);
  }
}

.map-legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.map-legend-swatch {
  width: 22px;
  height: 6px;
  border-radius: 3px;
  flex: none;
}

.map-legend-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.map-legend-popover {
  width: min(280px, calc(100vw - 320px));
  padding: var(--space-sm, 12px) var(--space-md, 16px);
  border: 1px solid var(--app-border, rgba(21, 105, 222, 0.16));
  border-radius: var(--app-panel-radius, 12px);
  background: var(--app-panel-bg, rgba(255, 255, 255, 0.96));
  box-shadow: var(--app-shadow-sm, 0 8px 24px rgba(13, 38, 76, 0.18));
  display: flex;
  flex-direction: column;
  gap: 8px;
  box-sizing: border-box;

  .map-legend-popover-title {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 8px;
    font-size: 13px;
    font-weight: 700;
    color: var(--app-ink, #344054);
    border-bottom: 1px solid rgba(21, 105, 222, 0.09);
    padding-bottom: 6px;
  }

  .map-legend-popover-tail {
    font-size: 11px;
    font-weight: 500;
    color: var(--app-muted-soft, #98a2b3);
    white-space: nowrap;
  }
}

.rm-range-popover {
  position: absolute;
  right: 48px;
  top: 76px;
  width: min(220px, calc(100vw - 96px));
  max-height: min(58vh, 430px);
  padding: var(--space-sm);
  z-index: calc(var(--z-popover) - 1);
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow: hidden;
  background: var(--app-panel-bg);
  border: 1px solid var(--app-border);
  border-radius: var(--app-panel-radius);
  box-shadow: var(--app-shadow-sm);

  .popover-title {
    padding: 0 2px 8px;
    margin: 0;
    border-bottom: 1px solid rgba(21, 105, 222, 0.09);
    font-size: 13px;
    font-weight: 700;
    color: var(--app-ink);
  }

  .range-list {
    display: flex;
    flex-direction: column;
    gap: 4px;
    overflow-y: auto;
    padding-right: 2px;
  }

  .range-option {
    width: 100%;
    min-height: 34px;
    padding: 0 10px;
    border: 1px solid transparent;
    border-radius: 8px;
    background: transparent;
    color: var(--app-ink-soft);
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 8px;
    font-size: 13px;
    font-weight: 700;
    text-align: left;
    cursor: pointer;
    transition:
      background-color 0.18s ease,
      border-color 0.18s ease,
      color 0.18s ease;

    &:hover,
    &.active {
      color: var(--app-cyan-strong);
      background: var(--app-cyan-soft);
      border-color: rgba(37, 167, 204, 0.22);
    }
  }

  .range-option-name {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .range-option-check {
    flex: 0 0 auto;
  }

  .range-state,
  .range-error {
    margin: 0;
    padding: 8px 4px;
    font-size: 12px;
    font-weight: 600;
    color: var(--app-muted);
  }

  .range-error {
    color: var(--app-coral);
  }
}

/* Slide/fade transition for popover */
.popover-fade-enter-active,
.popover-fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}
.popover-fade-enter-from,
.popover-fade-leave-to {
  opacity: 0;
  transform: translateX(10px);
}

.collapse-tab {
  --tab-shift-x: 0px;
  position: absolute;
  top: 50%;
  transform: translate(var(--tab-shift-x), -50%);
  width: 44px;
  min-width: 44px;
  height: 48px;
  border: 0;
  background: var(--app-card-bg);
  border: 1px solid rgba(21, 105, 222, 0.15);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  z-index: 10;
  transition:
    background-color var(--app-motion-normal) var(--app-ease-out),
    color var(--app-motion-normal) var(--app-ease-out),
    transform var(--app-motion-normal) var(--app-ease-out);
  color: var(--app-blue);
  
  &:hover {
    background: var(--app-cyan-soft);
    color: var(--app-cyan-strong);
  }
  
  .chevron-icon {
    width: 14px;
    height: 14px;
    transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    
    &.rotated {
      transform: rotate(180deg);
    }
  }
}

.left-tab {
  right: -44px;
  border-radius: 0 8px 8px 0;
  border-left: none;

  &:hover {
    --tab-shift-x: 2px;
  }
}

.rm-vehicle-controls {
  min-height: 0;
}

/* 监测组件宿主：保持挂载以驱动数据 / 地图图层 / 右侧 teleport，但自身界面移出视口隐藏 */
.run-monitor-mount {
  position: fixed;
  left: -100000px;
  top: 0;
  width: 430px;
  pointer-events: none;
  opacity: 0;
}

/* 右上角搜索框：与数据管理保持一致的玻璃质感浮层 */
.rm-search {
  position: fixed;
  top: calc(var(--app-header-height, 58px) + var(--app-scaled-20, 20px));
  left: var(--app-scaled-282, 282px);
  width: 292px;
  z-index: calc(var(--z-header, 1400) + 6);
  display: flex;
  align-items: center;
  gap: 8px;
  height: 42px;
  padding: 0 12px;
  box-sizing: border-box;
  border: 1px solid var(--dm2-line, rgba(17, 32, 58, 0.1));
  border-radius: 12px;
  background: var(--dm2-glass-strong, rgba(255, 255, 255, 0.92));
  box-shadow: var(--dm2-shadow-pop, 0 12px 30px -16px rgba(13, 38, 76, 0.3));
  -webkit-backdrop-filter: var(--dm2-glass-blur, blur(12px));
  backdrop-filter: var(--dm2-glass-blur, blur(12px));
  transform-origin: top left;
  transition:
    left 160ms var(--dm2-ease, cubic-bezier(0.32, 0.72, 0, 1)),
    box-shadow 160ms var(--dm2-ease, cubic-bezier(0.32, 0.72, 0, 1)),
    border-color 160ms var(--dm2-ease, cubic-bezier(0.32, 0.72, 0, 1));
}

.rm-search.is-left-collapsed {
  left: var(--app-scaled-22, 22px);
}

.rm-search.is-focused {
  border-color: var(--dm2-accent, #0071e3);
  box-shadow: 0 0 0 3px rgba(0, 113, 227, 0.14), var(--dm2-shadow-pop, 0 12px 30px -16px rgba(13, 38, 76, 0.3));
}

.rm-search-icon {
  flex-shrink: 0;
  width: 17px;
  height: 17px;
  color: var(--dm2-muted, #667085);
}

.rm-search-input {
  flex: 1;
  min-width: 0;
  height: 100%;
  border: 0;
  outline: 0;
  background: transparent;
  font-size: 14px;
  color: var(--dm2-ink, #1c2024);
  font-family: var(--dm2-font, inherit);
}

.rm-search-input::placeholder {
  color: var(--dm2-muted-soft, #98a2b3);
}

.rm-search-input::-webkit-search-cancel-button {
  -webkit-appearance: none;
  appearance: none;
}

.rm-search-clear {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: rgba(17, 32, 58, 0.06);
  color: var(--dm2-muted, #667085);
  cursor: pointer;
  transition: background 120ms ease, color 120ms ease;
}

.rm-search-clear:hover {
  background: rgba(17, 32, 58, 0.12);
  color: var(--dm2-ink, #1c2024);
}

.rm-search-results {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  right: 0;
  max-height: 320px;
  overflow-y: auto;
  padding: 6px;
  box-sizing: border-box;
  border: 1px solid var(--dm2-line, rgba(17, 32, 58, 0.1));
  border-radius: 12px;
  background: var(--dm2-glass-strong, rgba(255, 255, 255, 0.97));
  box-shadow: var(--dm2-shadow-panel, 0 20px 48px -24px rgba(13, 38, 76, 0.34));
  -webkit-backdrop-filter: var(--dm2-glass-blur, blur(12px));
  backdrop-filter: var(--dm2-glass-blur, blur(12px));
}

.rm-search-result {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 8px 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
  text-align: left;
  transition: background 120ms ease;
}

.rm-search-result:hover {
  background: rgba(0, 113, 227, 0.08);
}

.rm-result-icon {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  color: #ffffff;
}

.rm-result-icon svg {
  width: 16px;
  height: 16px;
}

.rm-result-icon.line {
  background: linear-gradient(135deg, #0a3f86, #0071e3);
}

.rm-result-icon.station {
  background: linear-gradient(135deg, #0b8f74, #18b89a);
}

.rm-result-meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
  gap: 1px;
}

.rm-result-name {
  overflow: hidden;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--dm2-ink, #1c2024);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rm-result-type {
  font-size: 11px;
  color: var(--dm2-muted, #667085);
}

.rm-search-empty {
  margin: 0;
  padding: 14px 10px;
  text-align: center;
  font-size: 13px;
  color: var(--dm2-muted, #667085);
}

.rm-search-fade-enter-active,
.rm-search-fade-leave-active {
  transition: opacity 160ms ease, transform 160ms ease;
}

.rm-search-fade-enter-from,
.rm-search-fade-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

/* 线路选择弹层样式由 datamanagement/tokens.css 共享。 */

.run-monitor-right-panel {
  .flex_column_scroll_box {
    width: 100%;
    height: 100%;
    min-height: 0;
  }

  :deep(.el-scrollbar__wrap),
  :deep(.el-scrollbar__view) {
    height: 100%;
  }

  :deep(.el-scrollbar__wrap) {
    overflow: hidden !important;
  }

  #datavisualization_index_box2 {
    height: 100%;
    min-height: 0;
    display: flex;
    flex-direction: column;
    align-items: stretch;
    gap: var(--dm2-space-3);
    overflow: hidden;
  }

  :deep(.MCard2) {
    width: 100%;
    border-color: var(--dm2-line);
    border-radius: var(--dm2-radius-lg);
    background: rgba(255, 255, 255, 0.68);
    box-shadow: var(--dm2-shadow-card);
  }

  :deep(.MCard2_title_box) {
    background: rgba(0, 113, 227, 0.07);
    color: var(--dm2-accent-strong);
  }
}

.rm-right-card {
  width: 100%;
  box-sizing: border-box;
  min-height: 0;
  display: flex;
  flex-direction: column;
  flex: 1;
  border: 0;
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  overflow: visible;
}

.rm-right-card-title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--dm2-space-3);
  padding: 0 0 14px;
  border-bottom: 1px solid var(--dm2-line-faint);
  background: transparent;

  h2 {
    margin: 4px 0 0;
    color: var(--dm2-ink);
    font-size: 19px;
    line-height: 1.25;
    font-weight: 780;
  }
}

.rm-right-card-subtitle {
  max-width: 360px;
  margin: 6px 0 0;
  color: var(--dm2-muted);
  font-size: 12px;
  line-height: 1.35;
  font-weight: 650;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rm-panel-kicker {
  margin: 0;
  color: var(--dm2-accent-strong);
  font-size: 11px;
  font-weight: 760;
}

.rm-overall-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--dm2-space-3);
  padding: 14px 0 0;
}

.overall-flow-card .rm-overall-summary {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.rm-line-kpi-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 1px;
  margin-top: 12px;
  border: 1px solid var(--dm2-line-faint);
  border-radius: 10px;
  background: var(--dm2-line-faint);
  overflow: hidden;
}

.rm-line-kpi-item {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 9px 10px;
  background: rgba(248, 251, 255, 0.86);

  span {
    color: var(--dm2-muted);
    font-size: 10px;
    font-weight: 650;
  }

  strong {
    color: var(--dm2-ink);
    font-family: var(--dm2-font-num);
    font-size: 13px;
    font-weight: 780;
    line-height: 1.15;
    white-space: nowrap;
  }
}

.rm-summary-item {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 13px 14px;
  border: 1px solid rgba(0, 113, 227, 0.12);
  border-radius: 12px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.94), rgba(238, 246, 255, 0.82)),
    var(--dm2-surface-sunken);
  box-shadow: 0 8px 22px -18px rgba(13, 38, 76, 0.28), inset 0 1px 0 rgba(255, 255, 255, 0.84);

  span {
    color: var(--dm2-muted);
    font-size: 11px;
    font-weight: 650;
  }

  strong {
    color: var(--dm2-ink);
    font-family: var(--dm2-font-num);
    font-size: 21px;
    font-weight: 820;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
}

.rm-compact-flow-card {
  overflow: hidden;

  .rm-right-card-title {
    padding-bottom: 10px;

    h2 {
      margin-top: 0;
      font-size: 18px;
      line-height: 1.18;
    }
  }

  .rm-overall-summary {
    gap: 8px;
    padding-top: 10px;
  }

  .rm-summary-item {
    gap: 3px;
    padding: 8px 9px;
    border-radius: 8px;

    span {
      font-size: 10px;
      line-height: 1.2;
    }

    strong {
      font-size: 15px;
      line-height: 1.18;
      letter-spacing: 0;
    }
  }

  .rm-overall-chart {
    flex: 0 0 246px;
    min-height: 246px;
    margin-top: 10px;
    padding: 8px 4px 2px;
    border-radius: 9px;
  }

  .hourly-ranking-panel {
    margin-top: 10px;
  }

  .ranking-title-text {
    margin-bottom: 7px;
    font-size: 13px;
  }

  .ranking-header {
    margin-bottom: 4px;
    padding: 7px 12px;

    span {
      font-size: 11px;
    }
  }

  .ranking-row {
    min-height: 34px;
    padding: 7px 12px;
  }

  .flow-value {
    font-size: 13px;
  }
}

.rm-overall-chart {
  position: relative;
  flex: 0 0 246px;
  min-height: 246px;
  margin-top: 12px;
  padding: 8px 4px 2px;
  box-sizing: border-box;
  border: 1px solid rgba(17, 32, 58, 0.08);
  border-radius: 12px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.9), rgba(247, 250, 254, 0.78)),
    var(--dm2-surface);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.82);
}

.rm-clickable-chart {
  cursor: zoom-in;
  transition: border-color var(--dm2-dur-fast), box-shadow var(--dm2-dur-fast);
}

.rm-clickable-chart:hover,
.rm-clickable-chart:focus-visible {
  border-color: rgba(21, 105, 222, 0.28);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.82), 0 0 0 1px rgba(21, 105, 222, 0.14);
}

.rm-chart-zoom-hint {
  position: absolute;
  right: 12px;
  bottom: 10px;
  z-index: 1;
  padding: 4px 8px;
  border: 1px solid rgba(21, 105, 222, 0.14);
  border-radius: 7px;
  background: rgba(255, 255, 255, 0.86);
  color: var(--dm2-muted, #667085);
  font-size: 11px;
  font-weight: 650;
  pointer-events: none;
}

.rm-chart,
.chart_box {
  width: 100%;
  height: 100%;
}

.rm-panel-error {
  margin: var(--dm2-space-4);
  color: var(--dm2-delete);
  font-size: 13px;
  font-weight: 650;
}

:global(.overall-flow-fullscreen-dialog) {
  background: #f7fbff;
}

:global(.overall-flow-fullscreen-dialog .el-dialog__header) {
  margin-right: 0;
  padding: 18px 24px 14px;
  border-bottom: 1px solid rgba(21, 105, 222, 0.12);
}

:global(.overall-flow-fullscreen-dialog .el-dialog__headerbtn) {
  top: 16px;
  right: 18px;
}

:global(.overall-flow-fullscreen-dialog .el-dialog__body) {
  height: calc(100vh - 78px);
  padding: 0;
}

.overall-flow-fullscreen-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
  padding-right: 42px;
}

.overall-flow-fullscreen-kicker {
  margin-bottom: 4px;
  color: var(--dm2-muted, #667085);
  font-size: 12px;
  font-weight: 700;
}

.overall-flow-fullscreen-title {
  color: var(--dm2-ink, #1c2024);
  font-size: 19px;
  line-height: 1.25;
  font-weight: 780;
}

.overall-flow-fullscreen-meta {
  display: flex;
  align-items: center;
  gap: 14px;
  color: var(--dm2-accent-strong, #1569de);
  font-size: 13px;
  font-weight: 720;
  white-space: nowrap;
}

.overall-flow-fullscreen-body {
  width: 100%;
  height: 100%;
  padding: 22px 26px 28px;
  box-sizing: border-box;
}

.overall-flow-fullscreen-chart {
  width: 100%;
  height: 100%;
}

.rm-panel-empty {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  min-height: 220px;
  padding: 32px 22px;
  text-align: center;
}

.rm-panel-empty .rm-empty-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: 16px;
  color: var(--dm2-accent, #0071e3);
  background: radial-gradient(120% 120% at 30% 20%, rgba(0, 113, 227, 0.14), rgba(0, 113, 227, 0.05));
  border: 1px solid rgba(0, 113, 227, 0.16);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.7);
}

.rm-panel-empty .rm-empty-text {
  margin: 0;
  max-width: 220px;
  color: var(--dm2-muted, #667085);
  font-size: 13.5px;
  line-height: 1.6;
}

.hourly-ranking-panel {
  flex: 1;
  min-height: 0;
  margin-top: 14px;
}

.ranking-title-text {
  margin: 0 0 10px;
  color: #1569de;
  font-size: 15px;
  line-height: 1.2;
  font-weight: 800;
}

.ranking-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 0 0 2px;
}

.ranking-header {
  display: flex;
  align-items: center;
  padding: 10px 16px;
  margin-bottom: 8px;
  border: 0;
  border-radius: 6px;
  background: #1569de;
  color: #ffffff;

  span {
    color: #ffffff;
    font-size: 13px;
    line-height: 1.2;
    font-weight: 800;
    letter-spacing: 0;
  }
}

.ranking-scroll-list {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 0;
  overflow-y: auto;
  padding-right: 6px;
  scrollbar-width: thin;
  scrollbar-color: rgba(21, 105, 222, 0.2) transparent;

  &::-webkit-scrollbar {
    width: 6px;
  }

  &::-webkit-scrollbar-thumb {
    background: rgba(21, 105, 222, 0.2);
    border-radius: 3px;
  }

  &::-webkit-scrollbar-thumb:hover {
    background: rgba(21, 105, 222, 0.4);
  }
}

.ranking-row {
  width: 100%;
  border: 0;
  display: flex;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px dashed rgba(21, 105, 222, 0.12);
  border-radius: 0;
  background: #ffffff;
  box-shadow: none;
  color: inherit;
  font-family: inherit;
  text-align: left;
  cursor: default;
  transition:
    background-color 0.2s ease,
    border-color 0.2s ease;

  &:hover {
    background: rgba(21, 105, 222, 0.03);
    border-bottom-color: rgba(21, 105, 222, 0.3);
  }

  &:last-child {
    border-bottom: none;
  }
}

.col-rank {
  width: 50px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: flex-start;
}

.col-name {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-right: 12px;
}

.col-flow {
  width: 110px;
  flex-shrink: 0;
  display: flex;
  align-items: baseline;
  justify-content: flex-end;
  gap: 3px;
}

.rank-badge {
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  color: #60758e;
  background: rgba(113, 128, 150, 0.08);
  border: 0;
  font-size: 12px;
  font-weight: 800;
  font-variant-numeric: tabular-nums;

  &.gold {
    color: #ffffff;
    background: #d97706;
    font-size: 13px;
  }

  &.silver {
    color: #ffffff;
    background: #94a3b8;
    font-size: 13px;
  }

  &.bronze {
    color: #ffffff;
    background: #ea580c;
    font-size: 13px;
  }
}

.route-name-text {
  min-width: 0;
  color: #2d3748;
  font-size: 14px;
  line-height: 1.25;
  font-weight: 800;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.route-desc-text {
  min-width: 0;
  color: #a0aec0;
  font-size: 11px;
  line-height: 1.25;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.flow-value {
  color: #0f9f6e;
  font-size: 16px;
  line-height: 1.2;
  font-weight: 800;
  font-family: var(--dm2-font-num);
  font-variant-numeric: tabular-nums;
}

.ranking-row:nth-child(-n + 3) .flow-value {
  color: #d97706;
}

.flow-unit {
  color: #60758e;
  font-size: 11px;
  font-weight: 600;
}

.layer-mode-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  color: var(--app-muted);
  font-size: 11px;
  font-weight: 600;
}

.layer-mode-segment {
  display: inline-flex;
  padding: 2px;
  border: 1px solid rgba(21, 105, 222, 0.12);
  border-radius: 8px;
  background: rgba(21, 105, 222, 0.05);

  button {
    min-width: 70px;
    height: 26px;
    border: 0;
    border-radius: 6px;
    background: transparent;
    color: var(--app-muted);
    cursor: pointer;
    font-size: 11px;
    font-weight: 700;

    &.active {
      background: #ffffff;
      color: var(--app-blue);
      box-shadow: 0 1px 4px rgba(21, 105, 222, 0.12);
    }
  }
}

@media (max-width: 1024px) {
  .datebase_box {
    right: calc(var(--app-edge) + 36px);
    max-width: 52vw;
  }

  .model-background-status {
    right: calc(var(--app-edge) + 36px);
    width: 52vw;
    min-width: 320px;
  }

  .box1 {
    width: min(400px, calc((100vw - 48px) / var(--app-panel-scale)));
    min-width: min(400px, calc((100vw - 48px) / var(--app-panel-scale)));
  }

}

@media (max-width: 960px) {
  .datebase_box {
    top: calc(var(--app-header-height) + var(--space-lg));
    right: var(--app-edge);
    max-width: calc(100vw - (var(--app-edge) * 2));
    flex-wrap: wrap;
    justify-content: flex-end;
  }

  .model-background-status {
    top: calc(var(--app-header-height) + 76px);
    right: var(--app-edge);
    grid-template-columns: minmax(0, 1fr);
    width: calc(100vw - (var(--app-edge) * 2));
    min-width: 0;
  }

}

@media (max-width: 640px) {
  .datebase_box {
    left: var(--app-edge);
    transform: none;

    .handle,
    .load-error {
      width: 100%;
      text-align: right;
    }

    .el-select {
      width: min(100%, 190px);
    }
  }

  .model-background-status {
    left: var(--app-edge);
    right: var(--app-edge);
    width: auto;
  }

  .box1 {
    width: calc((100vw - 32px) / var(--app-panel-scale));
    min-width: calc((100vw - 32px) / var(--app-panel-scale));

    .tab_list {
      flex-wrap: wrap;
    }
  }
}
</style>
