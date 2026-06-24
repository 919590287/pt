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
    <label class="handle" for="scheme-selector">当前方案</label>
    <el-select id="scheme-selector" v-model="datebase.scheme" clearable filterable :loading="isLoadingSchemes" aria-label="当前方案">
      <el-option v-for="item in schemeList" :key="item" :label="item" :value="item"> </el-option>
    </el-select>
    <el-select class="model-select" v-model="modelPickerValue" :disabled="!datebase.scheme || isLoadingModels" clearable filterable :loading="isLoadingModels" aria-label="选择模型" @change="handleModelPick">
      <el-option v-for="item in modelList" :key="item.name" :label="getModelLabel(item)" :value="item.name">
        <div class="model-option">
          <div class="model-option-main">
            <span>{{ getModelLabel(item) }}</span>
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
          <span class="brand-text">运行监测</span>
        </div>

        <nav class="sidebar-nav" aria-label="运行监测导航">
          <div v-for="item in runMonitorMenuItems" :key="item.key" class="menu-group">
            <button
              type="button"
              :class="['nav-item', activeTab === item.key ? 'active' : '']"
              @click="handleSetActiveTab(item.key)"
            >
              <span class="nav-icon" v-html="item.icon"></span>
              <span class="nav-label">{{ item.label }}</span>
            </button>
          </div>
        </nav>

        <div v-show="activeTab === '车辆运行监测'" id="run-monitor-vehicle-controls" class="rm-vehicle-controls"></div>
        <div class="sidebar-footer"></div>
      </div>

      <button
        type="button"
        :class="['dm-panel-collapse-tab', 'dm-left-collapse-tab', isRunMonitorLeftCollapsed ? 'is-collapsed' : '']"
        :title="isRunMonitorLeftCollapsed ? '展开运行监测面板' : '收起运行监测面板'"
        :aria-label="isRunMonitorLeftCollapsed ? '展开运行监测面板' : '收起运行监测面板'"
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
                <span class="rm-result-type">{{ runMonitorSearchType === 'line' ? '公交线路' : '公交站点' }}</span>
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
            <div v-if="activeTab === '总体客流变化'" class="rm-right-card overall-flow-card">
              <div class="rm-right-card-title">
                <div>
                  <p class="rm-panel-kicker">总体客流</p>
                  <h2>一天总客流变化</h2>
                </div>
                <el-tag v-if="overallFlowLoading" type="info">加载中</el-tag>
                <el-tag v-else-if="overallFlowError" type="danger">加载失败</el-tag>
              </div>
              <div class="rm-overall-summary">
                <div class="rm-summary-item">
                  <span>总客流</span>
                  <strong>{{ formatOverallFlow(overallFlowTotal) }}</strong>
                </div>
              </div>
              <div v-if="overallFlowError" class="rm-panel-error">{{ overallFlowError }}</div>
              <template v-else>
                <div class="rm-overall-chart">
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
                </div>
                <div class="hourly-ranking-panel ranking-panel">
                  <div class="ranking-title-text">小时客流排行</div>
                  <div class="ranking-header">
                    <span class="col-rank">排序</span>
                    <span class="col-name">小时</span>
                    <span class="col-flow">客流量</span>
                  </div>
                  <div class="ranking-scroll-list">
                    <div v-for="(item, index) in overallFlowRankingRows" :key="item.hour" class="ranking-row">
                      <div class="col-rank">
                        <span :class="['rank-badge', index === 0 ? 'gold' : index === 1 ? 'silver' : index === 2 ? 'bronze' : '']">
                          {{ index + 1 }}
                        </span>
                      </div>
                      <div class="col-name">
                        <span class="route-name-text">{{ item.label }}</span>
                      </div>
                      <div class="col-flow">
                        <span class="flow-value">{{ item.valueText }}</span>
                        <span class="flow-unit">人次</span>
                      </div>
                    </div>
                  </div>
                </div>
              </template>
            </div>

            <div v-else-if="activeTab === '线路客流监测'" class="rm-right-card line-flow-card">
              <template v-if="selectedLinePanel">
                <div class="rm-right-card-title">
                  <div>
                    <p class="rm-panel-kicker">线路客流</p>
                    <h2>{{ selectedLineName || '线路客流量' }}</h2>
                  </div>
                </div>
                <div class="rm-overall-summary">
                  <div class="rm-summary-item">
                    <span>一天总客流</span>
                    <strong>{{ formatOverallFlow(lineFlowTotal) }}</strong>
                  </div>
                  <div class="rm-summary-item">
                    <span>峰值小时</span>
                    <strong>{{ lineFlowPeak.label }}</strong>
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
                <div class="hourly-ranking-panel ranking-panel">
                  <div class="ranking-title-text">小时客流排行</div>
                  <div class="ranking-header">
                    <span class="col-rank">排序</span>
                    <span class="col-name">小时</span>
                    <span class="col-flow">客流量</span>
                  </div>
                  <div class="ranking-scroll-list">
                    <div v-for="(item, index) in lineFlowRankingRows" :key="item.hour" class="ranking-row">
                      <div class="col-rank">
                        <span :class="['rank-badge', index === 0 ? 'gold' : index === 1 ? 'silver' : index === 2 ? 'bronze' : '']">
                          {{ index + 1 }}
                        </span>
                      </div>
                      <div class="col-name">
                        <span class="route-name-text">{{ item.label }}</span>
                      </div>
                      <div class="col-flow">
                        <span class="flow-value">{{ item.valueText }}</span>
                        <span class="flow-unit">人次</span>
                      </div>
                    </div>
                  </div>
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

            <div v-else-if="activeTab === '站点客流监测'" class="rm-right-card station-flow-card">
              <template v-if="selectedStationPanel">
                <div class="rm-right-card-title">
                  <div>
                    <p class="rm-panel-kicker">站点客流</p>
                    <h2>{{ selectedStationName || '站点客流量' }}</h2>
                  </div>
                </div>
                <div class="rm-overall-summary">
                  <div class="rm-summary-item">
                    <span>全天上下车人数</span>
                    <strong>{{ formatOverallFlow(stationFlowTotal) }}</strong>
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
                <div class="hourly-ranking-panel ranking-panel">
                  <div class="ranking-title-text">小时上下车排行</div>
                  <div class="ranking-header">
                    <span class="col-rank">排序</span>
                    <span class="col-name">小时</span>
                    <span class="col-flow">上下车人数</span>
                  </div>
                  <div class="ranking-scroll-list">
                    <div v-for="(item, index) in stationFlowRankingRows" :key="item.hour" class="ranking-row">
                      <div class="col-rank">
                        <span :class="['rank-badge', index === 0 ? 'gold' : index === 1 ? 'silver' : index === 2 ? 'bronze' : '']">
                          {{ index + 1 }}
                        </span>
                      </div>
                      <div class="col-name">
                        <span class="route-name-text">{{ item.label }}</span>
                      </div>
                      <div class="col-flow">
                        <span class="flow-value">{{ item.valueText }}</span>
                        <span class="flow-unit">人次</span>
                      </div>
                    </div>
                  </div>
                </div>
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

        <!-- Block 2: Line Settings Toggle & Floating Popover -->
        <div class="control-block settings-block">
          <button
            :class="['control-btn', showLineWidthPopover ? 'active' : '']"
            type="button"
            @click="handleToggleLineWidthPopover"
            :title="isVehicleMonitorTab ? '车辆模型设置' : '线形设置'"
            :aria-label="isVehicleMonitorTab ? '打开车辆模型设置' : '打开线形设置'"
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
            <div class="popover-title">{{ isVehicleMonitorTab ? '车辆模型设置' : '线形设置' }}</div>
            <div class="popover-content">
              <div class="slider-row" v-if="isVehicleMonitorTab">
                <span class="label">
                  <span>车辆模型</span>
                  <span class="val-text">{{ `${vehicleSize}px` }}</span>
                </span>
                <el-slider v-model="vehicleSize" :min="minVehicleSize" :max="maxVehicleSize" :step="1" @input="handleVehicleSizeChange" />
              </div>
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
              <div class="slider-row" v-else-if="baseMapLineMode === 'bus-network'">
                <span class="label">
                  <span>站点大小</span>
                  <span class="val-text">{{ `${stationSize}px` }}</span>
                </span>
                <el-slider v-model="stationSize" :min="minStationSize" :max="maxStationSize" :step="1" @input="handleStationSizeChange" />
              </div>
              <div class="flow-control-row" v-else>
                <span>按流量控制</span>
                <el-switch v-model="flowControl" @change="handleFlowControlChange" />
              </div>
            </div>
          </div>
        </Transition>
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
    <el-empty description="请选择模型" />
  </div>
</template>

<script setup>
import { defineAsyncComponent, getCurrentInstance } from "vue";
import { Close, Remove, SwitchButton, VideoPlay } from "@element-plus/icons-vue";
import { ElMessage, ElMessageBox } from "@/plugins/element-plus";
import { getSchemeList, getModelList, loadModel, unloadModel } from "@/api/scheme.js";
import { dataCenter } from "@/api/data.js";
import { getLineAll, getRouteCandidates, getRoutePanel, getRouteTileBinary } from "@/api/route.js";
import { getFacilityAll } from "@/api/facility.js";
import { useModelSelectionStore } from "@/stores/modelSelection.js";
import { webMercatorToLngLat } from "@/mymap/index.js";
import { NetworkLayer } from "./layers/NetworkLayer.js";
import { RouteLayer } from "./layers/RouteLayer.js";
import busStationIconUrl from "@/assets/images/datamanagement/bus-station.svg?url";
import busStationHighlightIconUrl from "@/assets/images/datamanagement/bus-station_highlight.svg?url";
import "../datamanagement/tokens.css";

import { useDraggable } from "@vueuse/core";

const GJYS = defineAsyncComponent(() => import("./components/GJYS.vue"));
const XLZL = defineAsyncComponent(() => import("./components/XLZL.vue"));
const ZDZL = defineAsyncComponent(() => import("./components/ZDZL.vue"));

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
const modelSelectionStore = useModelSelectionStore();
const restoredSelection = modelSelectionStore.getSelection(MODEL_SELECTION_KEY);
let isRestoringSelection = Boolean(restoredSelection.scheme);
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
const selectModel = computed(() => {
  const item = modelList.value?.find((item) => item.name === datebase.value.model);

  return item;
});
const backgroundTaskModel = computed(() => modelList.value?.find((item) => item.name === backgroundModelName.value));
const isModelReadyForView = (item) => Boolean(item?.loadStatus && item?.cacheStatus === "ready");
const isModelReady = computed(() => isModelReadyForView(selectModel.value));
const backgroundTaskVisible = computed(() => Boolean(
  backgroundTaskModel.value
  && backgroundTaskModel.value.name !== datebase.value.model
  && !isModelReadyForView(backgroundTaskModel.value),
));
const fullScreenLoadingVisible = computed(() => (
  !isModelReady.value
  && (initialModelBootstrap.value || isLoadingSchemes.value || isLoadingModels.value || Boolean(selectModel.value))
));
const modelLoadingDialogVisible = computed(() => fullScreenLoadingVisible.value && !modelLoadingDismissed.value);
const modelLoadingNotice = computed(() => {
  if (!selectModel.value) return "模型状态正在检查，请稍后。";
  return `“${getModelLabel(selectModel.value)}”开始后台加载，请稍后。`;
});
const modelLoadingKey = computed(() => `${datebase.value.scheme || ""}:${selectModel.value?.name || ""}:${selectModel.value?.loadStage || ""}:${selectModel.value?.cacheStatus || ""}`);
const cacheProgressPercent = computed(() => modelProgressPercent(selectModel.value));
const backgroundTaskTitle = computed(() => {
  const item = backgroundTaskModel.value;
  if (!item) return "";
  const prefix = backgroundSwitchOnReady.value ? "加载完成后切换" : "后台加载";
  return `${prefix}：${getModelLabel(item)}`;
});
const backgroundTaskMessage = computed(() => modelProgressMessage(backgroundTaskModel.value));

function modelProgressPercent(item) {
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
  if (!selectModel.value?.loadStatus) return "正在加载模型基础数据";
  return "正在生成模型缓存";
});
const cacheLoadingMessage = computed(() => (
  (!selectModel.value && isLoadingSchemes.value ? "正在读取可用方案" : "")
  || (!selectModel.value && isLoadingModels.value ? "正在读取模型列表" : "")
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
  return item?.displayName || item?.name || "";
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
  [() => datebase.value.scheme, () => datebase.value.model],
  () => {
    modelSelectionStore.setSelection(MODEL_SELECTION_KEY, {
      scheme: datebase.value.scheme,
      model: datebase.value.model,
    });
  },
);
watch(modelLoadingKey, () => {
  modelLoadingDismissed.value = false;
});

async function ensureSelectedModelReady() {
  modelPickerValue.value = datebase.value.model || "";
  if (!datebase.value.model) return;
  const seq = ++modelLoadSeq;
  try {
    if (selectModel.value && !isModelReady.value) {
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

async function waitForModelReady(modelName, shouldContinue, options = {}) {
  const { publishProgress = true } = options;
  for (let i = 0; i < 21600; i++) {
    if (!shouldContinue()) return null;
    const list = await handleGetModelList({ silent: true });
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
    await sleep(1000);
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
];

const activeTab = ref("总体客流变化");
const isRunMonitorLeftCollapsed = ref(false);
const lineMonitorRef = ref(null);
const stationMonitorRef = ref(null);
const selectedLineKey = ref("");
const selectedStationKey = ref("");

const effectiveTab = computed(() => activeTab.value);
const isVehicleMonitorTab = computed(() => effectiveTab.value === "轨迹演示" || effectiveTab.value === "车辆运行监测");

// —— 右上角搜索框（线路 / 站点）——
// 监测组件把各自的可选项写入这两个 ref，搜索框据此提供候选并调用组件的选中方法。
const runMonitorLineOptions = ref([]);
const runMonitorStationOptions = ref([]);
provide("runMonitorLineOptions", runMonitorLineOptions);
provide("runMonitorStationOptions", runMonitorStationOptions);

const runMonitorSearchKeyword = ref("");
const isSearchFocused = ref(false);
const runMonitorSearchType = computed(() => {
  if (effectiveTab.value === "线路客流监测") return "line";
  if (effectiveTab.value === "站点客流监测") return "station";
  return "";
});
const showRunMonitorSearch = computed(() => runMonitorSearchType.value !== "");
const runMonitorSearchPlaceholder = computed(() =>
  runMonitorSearchType.value === "line" ? "搜索公交线路" : "搜索公交站点",
);
const runMonitorSearchResults = computed(() => {
  const query = runMonitorSearchKeyword.value.trim().toLowerCase();
  if (!query) return [];
  const source = runMonitorSearchType.value === "line" ? runMonitorLineOptions.value : runMonitorStationOptions.value;
  return source.filter((item) => String(item.label).toLowerCase().includes(query)).slice(0, 50);
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
    selectedLinePanel.value = null;
    selectedLineName.value = "";
    selectedLineKey.value = "";
  }
  if (tab !== "站点客流监测") {
    setSelectedBusStation(null);
    selectedStationPanel.value = null;
    selectedStationName.value = "";
    selectedStationKey.value = "";
  }
  lineWidth.value = 1.2;
  stationSize.value = 32;
  applyLineWidth();
  applyStationSize();
  scheduleLayerSyncBurst(4);
  observeLeftPanelSize();
});

watch(
  [() => selectModel.value?.name, isModelReady],
  () => {
    if (isModelReady.value) {
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
const vehicleSize = ref(36);
const referenceZoom = ref(10.74);
let isZoomCaptured = false;
const baseMapLineMode = ref("bus-network");
provide("BaseMapLineModeRef", baseMapLineMode);

const { proxy } = getCurrentInstance() || {};
const overallFlowLoading = ref(false);
const overallFlowError = ref("");
const overallFlowHourly = ref(Array.from({ length: 24 }, () => 0));
let overallFlowRequestSeq = 0;

const overallFlowTotal = computed(() => overallFlowHourly.value.reduce((sum, value) => sum + (Number(value) || 0), 0));
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
  const hours = hourly.map((_, index) => `${String(index).padStart(2, "0")}:00`);
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
    grid: { top: 28, right: 18, bottom: 18, left: 14, containLabel: true },
    xAxis: {
      type: "category",
      data: hours,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: "rgba(17, 32, 58, 0.12)" } },
      axisLabel: { color: "#667085", fontSize: 10, interval: 2 },
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
const overallFlowChartOption = computed(() => buildHourlyFlowChartOption(overallFlowHourly.value));

// 线路客流监测：右侧简化卡片 —— 选中线路的日客流量 + 全天客流变化折线图（数据由 XLZL 上抛）
const selectedLinePanel = ref(null);
const selectedLineName = ref("");
const selectedRouteDetail = ref(null);
provide("runMonitorSelectedLinePanel", selectedLinePanel);
provide("runMonitorSelectedLineName", selectedLineName);
provide("runMonitorSelectedRouteDetail", selectedRouteDetail);
provide("runMonitorSimplifiedRight", true);

watch(selectedRouteDetail, (detail) => {
  if (!selectedLineKey.value || !detail?.links?.length) return;
  setMonitorSelectedRouteLinks(detail.links);
});

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
const lineFlowTotal = computed(() => {
  const metricTotal = Number(selectedLinePanel.value?.metrics?.passenger);
  if (Number.isFinite(metricTotal) && metricTotal > 0) return metricTotal;
  return lineFlowHourly.value.reduce((sum, value) => sum + value, 0);
});
const lineFlowPeak = computed(() => {
  let peakIndex = 0;
  let peakValue = -Infinity;
  lineFlowHourly.value.forEach((value, index) => {
    if (value > peakValue) {
      peakValue = value;
      peakIndex = index;
    }
  });
  return {
    label: `${String(peakIndex).padStart(2, "0")}:00`,
    value: Math.max(0, peakValue),
  };
});
const lineFlowChartOption = computed(() => buildHourlyFlowChartOption(lineFlowHourly.value));
const lineFlowRankingRows = computed(() => buildHourlyRankingRows(lineFlowHourly.value));

// 站点客流监测：右侧卡片与「总体客流变化」一致 —— 站点全天上下车人数 + 上下车变化（数据由 ZDZL 上抛）
const selectedStationPanel = ref(null);
const selectedStationName = ref("");
provide("runMonitorSelectedStationPanel", selectedStationPanel);
provide("runMonitorSelectedStationName", selectedStationName);

// 选中线路/站点变化时，重新计算底图聚焦淡出。以地图选中键为准，
// 不依赖客流面板是否恰好有缓存数据。
watch([selectedLineKey, selectedStationKey, effectiveTab], () => {
  applyBusNetworkFocus();
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
const stationFlowTotal = computed(() => stationFlowHourly.value.reduce((sum, value) => sum + value, 0));
const stationFlowChartOption = computed(() => buildHourlyFlowChartOption(stationFlowHourly.value));
const stationFlowRankingRows = computed(() => buildHourlyRankingRows(stationFlowHourly.value));

function formatOverallFlow(value) {
  const number = Number(value);
  return Number.isFinite(number) ? `${Math.round(number).toLocaleString("zh-CN")} 人次` : "暂无";
}

function routePanelToOverallHourly(panel = {}) {
  const hourly = Array.from({ length: 24 }, () => 0);
  const routes = panel?.routes && typeof panel.routes === "object" ? Object.values(panel.routes) : [];
  routes.forEach((route) => {
    const values = Array.isArray(route?.hourlyFlow) ? route.hourlyFlow : [];
    values.forEach((value, index) => {
      if (index < hourly.length) hourly[index] += Number(value) || 0;
    });
  });
  return hourly;
}

async function loadOverallFlow() {
  if (effectiveTab.value !== "总体客流变化" || !selectModel.value?.name || !isModelReady.value) return;
  const seq = ++overallFlowRequestSeq;
  overallFlowLoading.value = true;
  overallFlowError.value = "";
  try {
    const res = await getRoutePanel({ datasource: selectModel.value.name }, { silentError: true });
    if (seq !== overallFlowRequestSeq) return;
    overallFlowHourly.value = routePanelToOverallHourly(res?.data || {});
  } catch (error) {
    if (seq !== overallFlowRequestSeq) return;
    overallFlowHourly.value = Array.from({ length: 24 }, () => 0);
    overallFlowError.value = error?.message || "总体客流变化加载失败";
  } finally {
    if (seq === overallFlowRequestSeq) {
      overallFlowLoading.value = false;
    }
  }
}

watch(
  [effectiveTab, () => selectModel.value?.name, isModelReady],
  loadOverallFlow,
  { immediate: true },
);

const RM_SOURCE_LINES = "rm-bus-network-lines-source";
const RM_SOURCE_STATIONS = "rm-bus-network-stations-source";
const RM_SOURCE_SELECTED_STATION = "rm-bus-network-selected-station-source";
const RM_LAYER_LINES = "rm-bus-network-lines";
const RM_LAYER_STATIONS = "rm-bus-network-stations";
const RM_LAYER_STATION_SELECTED = "rm-bus-network-station-selected";
const RM_STATION_ICON_ID = "rm-bus-network-station-icon";
const RM_STATION_HIGHLIGHT_ICON_ID = "rm-bus-network-station-highlight-icon";
const RM_STATION_ICON_SIZE = 96;
const RM_BASE_LINE_OPACITY = 0.7;
const RM_DIMMED_LINE_OPACITY = 0.18;
const busNetworkLoading = ref(false);
const busNetworkError = ref("");
let busNetworkRequestSeq = 0;
let routePickRequestSeq = 0;
let busNetworkClickListenerId = null;
let monitorRoadLayer = null;
let monitorBusRouteLayer = null;
let monitorSelectedRouteGlowLayer = null;
let monitorSelectedRouteLayer = null;
let busNetworkSourceRefs = new Map();
let busNetworkCollections = {
  lines: emptyFeatureCollection(),
  stations: emptyFeatureCollection(),
};

const busNetworkLineWidth = computed(() => Math.max(0.1, Math.min(2, Number(lineWidth.value) || 1.2)));
const busNetworkHitLineWidth = computed(() => Math.max(12, busNetworkLineWidth.value * 4));
const busNetworkStationIconScale = computed(() => {
  const highZoomScale = stationSize.value / RM_STATION_ICON_SIZE;
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
  await Promise.all([
    addMapImageOnce(map, RM_STATION_ICON_ID, busStationIconUrl, RM_STATION_ICON_SIZE),
    addMapImageOnce(map, RM_STATION_HIGHLIGHT_ICON_ID, busStationHighlightIconUrl, RM_STATION_ICON_SIZE),
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

function busStationIconLayout(iconId = RM_STATION_ICON_ID, iconScale = busNetworkStationIconScale.value) {
  return {
    "icon-image": iconId,
    "icon-size": iconScale,
    "icon-anchor": "center",
    "icon-allow-overlap": true,
    "icon-ignore-placement": true,
    "icon-padding": 2,
  };
}

function ensureBusNetworkLayers(map) {
  ensureBusNetworkSource(map, RM_SOURCE_LINES, busNetworkCollections.lines);
  ensureBusNetworkSource(map, RM_SOURCE_STATIONS, busNetworkCollections.stations);
  ensureBusNetworkSource(map, RM_SOURCE_SELECTED_STATION, emptyFeatureCollection());

  if (!map.getLayer(RM_LAYER_LINES)) {
    addBusLayerBelowBuildings(map, {
      id: RM_LAYER_LINES,
      type: "line",
      source: RM_SOURCE_LINES,
      layout: { "line-join": "round", "line-cap": "round" },
      paint: {
        "line-color": "#2f6f73",
        "line-opacity": 0.001,
        "line-width": busNetworkHitLineWidth.value,
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
  if (!map.getLayer(RM_LAYER_STATION_SELECTED)) {
    map.addLayer({
      id: RM_LAYER_STATION_SELECTED,
      type: "symbol",
      source: RM_SOURCE_SELECTED_STATION,
      layout: busStationIconLayout(RM_STATION_HIGHLIGHT_ICON_ID, selectedBusStationIconScale.value),
      paint: { "icon-opacity": 1 },
    });
  }
  applyBusNetworkPaint();
  syncBaseMapLayerVisibility();
}

function setBusLayerVisibility(map, layerId, visible) {
  if (map?.getLayer?.(layerId)) {
    map.setLayoutProperty(layerId, "visibility", visible ? "visible" : "none");
  }
}

function applyBusNetworkPaint() {
  const map = MapRef.value?.map;
  if (!map) return;
  if (map.getLayer(RM_LAYER_LINES)) {
    map.setPaintProperty(RM_LAYER_LINES, "line-width", busNetworkHitLineWidth.value);
  }
  if (map.getLayer(RM_LAYER_STATIONS)) {
    map.setLayoutProperty(RM_LAYER_STATIONS, "icon-size", busNetworkStationIconScale.value);
  }
  if (map.getLayer(RM_LAYER_STATION_SELECTED)) {
    map.setLayoutProperty(RM_LAYER_STATION_SELECTED, "icon-size", selectedBusStationIconScale.value);
  }
  syncMonitorRouteLineWidths();
  applyBusNetworkFocus();
}

function busLineOpacityPaint() {
  // 真实线路由模型二进制瓦片图层绘制；此层只负责命中测试。
  return 0.001;
}

function busStationOpacityPaint() {
  if (!selectedStationKey.value) return 0.96;
  return [
    "case",
    ["==", ["to-string", ["get", "_stationKey"]], String(selectedStationKey.value)],
    0,
    0.24,
  ];
}

// 选中聚焦与数据管理保持一致：高亮对象由独立图层绘制，底图对象降低透明度。
function applyBusNetworkFocus() {
  const map = MapRef.value?.map;
  if (!map) return;
  if (map.getLayer(RM_LAYER_LINES)) {
    map.setPaintProperty(RM_LAYER_LINES, "line-opacity", busLineOpacityPaint());
  }
  if (map.getLayer(RM_LAYER_STATIONS)) {
    map.setPaintProperty(RM_LAYER_STATIONS, "icon-opacity", busStationOpacityPaint());
  }
  if (monitorBusRouteLayer) {
    monitorBusRouteLayer.opacity = selectedLineKey.value ? RM_DIMMED_LINE_OPACITY : RM_BASE_LINE_OPACITY;
    monitorBusRouteLayer.updatePaint();
  }
}

function syncBaseMapLayerVisibility() {
  const map = MapRef.value?.map;
  if (!map) return;
  const showBusNetwork = baseMapLineMode.value === "bus-network";
  // 线路客流监测：地图只显示线路；站点客流监测：只显示站点；车辆运行监测：两者都不显示；其余标签两者皆显示。
  const tab = effectiveTab.value;
  const isVehicleTab = tab === "车辆运行监测";
  const showLines = showBusNetwork && !isVehicleTab && tab !== "站点客流监测";
  const showStations = showBusNetwork && !isVehicleTab && tab !== "线路客流监测";
  setBusLayerVisibility(map, RM_LAYER_LINES, showLines);
  [RM_LAYER_STATIONS, RM_LAYER_STATION_SELECTED].forEach((layerId) => {
    setBusLayerVisibility(map, layerId, showStations);
  });
  if (monitorBusRouteLayer) {
    showLines ? monitorBusRouteLayer.show() : monitorBusRouteLayer.hide();
  }
  [monitorSelectedRouteGlowLayer, monitorSelectedRouteLayer].forEach((layer) => {
    if (!layer) return;
    showLines ? layer.show() : layer.hide();
  });
  if (monitorRoadLayer) {
    showBusNetwork ? monitorRoadLayer.hide() : monitorRoadLayer.show();
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
    (line?.routes || []).forEach((route, idx) => {
      const coords = [];
      const links = route?.links || [];
      if (links.length) {
        const first = modelCoordToLngLat(links[0]?.from);
        if (first) coords.push(first);
        for (const link of links) {
          const to = modelCoordToLngLat(link?.to);
          if (to) coords.push(to);
        }
      } else {
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
          _lineKey: `${lineId}-${routeId || idx}`,
        },
      });
    });
  }
  return { type: "FeatureCollection", features };
}

// 由模型 facilityAll 轻量缓存构建站点 geojson，避免等待整份线路详情。
function buildModelStationFeatureCollection(facilities) {
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
        _stationKey: key,
      },
    });
  }
  return { type: "FeatureCollection", features: Array.from(seen.values()) };
}

async function loadBusNetwork() {
  if (!MapRef.value?.map || !isModelReady.value || !selectModel.value?.name) return;
  const seq = ++busNetworkRequestSeq;
  busNetworkLoading.value = true;
  busNetworkError.value = "";
  try {
    // 改为使用当前模型自身的线路/站点数据（getLineAll），而非数据管理的真实底图数据
    const [lineRes, facilityRes] = await Promise.all([
      getLineAll({ datasource: selectModel.value.name }),
      getFacilityAll({ datasource: selectModel.value.name }),
    ]);
    if (seq !== busNetworkRequestSeq) return;
    const lines = lineRes?.data || [];
    const facilities = facilityRes?.data || [];
    busNetworkCollections = {
      lines: buildModelLineFeatureCollection(lines),
      stations: buildModelStationFeatureCollection(facilities),
    };
    const map = MapRef.value?.map;
    if (!map) return;
    ensureBusNetworkSource(map, RM_SOURCE_LINES, busNetworkCollections.lines);
    ensureBusNetworkSource(map, RM_SOURCE_STATIONS, busNetworkCollections.stations);
    await ensureBusStationIcons(map);
    ensureBusNetworkLayers(map);
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
  if (!MapRef.value || monitorBusRouteLayer || !selectModel.value?.name) return;
  monitorBusRouteLayer = new RouteLayer({
    zIndex: 998,
    lineWidth: busNetworkLineWidth.value * 10,
    fixedPixelWidth: true,
    flowControl: false,
    color: 0x2f6f73,
    opacity: selectedLineKey.value ? RM_DIMMED_LINE_OPACITY : RM_BASE_LINE_OPACITY,
  });
  monitorSelectedRouteGlowLayer = new RouteLayer({
    zIndex: 999,
    lineWidth: Math.max(4, busNetworkLineWidth.value + 3.6) * 2.2 * 10,
    fixedPixelWidth: true,
    workerEnabled: false,
    flowControl: false,
    color: 0xfacc15,
    opacity: 0.42,
  });
  monitorSelectedRouteLayer = new RouteLayer({
    zIndex: 1000,
    lineWidth: Math.max(4, busNetworkLineWidth.value + 3.6) * 10,
    fixedPixelWidth: true,
    workerEnabled: false,
    flowControl: false,
    color: 0xf97316,
    opacity: 0.95,
  });
  MapRef.value.addLayer(monitorBusRouteLayer);
  // Deck 图层按加入顺序绘制：背景 -> 光晕 -> 选中线，确保高亮永远在线网上方。
  MapRef.value.addLayer(monitorSelectedRouteGlowLayer);
  MapRef.value.addLayer(monitorSelectedRouteLayer);
  monitorSelectedRouteGlowLayer.setData([]);
  monitorSelectedRouteLayer.setData([]);
  monitorBusRouteLayer.setTileSource(selectModel.value.name, { tileRequest: getRouteTileBinary });
  syncBaseMapLayerVisibility();
}

function syncMonitorRouteLineWidths() {
  const baseWidth = busNetworkLineWidth.value;
  const selectedWidth = Math.max(4, baseWidth + 3.6);
  monitorBusRouteLayer?.setLineWidth(baseWidth * 10);
  monitorSelectedRouteGlowLayer?.setLineWidth(selectedWidth * 2.2 * 10);
  monitorSelectedRouteLayer?.setLineWidth(selectedWidth * 10);
}

function setMonitorSelectedRouteLinks(links = []) {
  const data = Array.isArray(links) ? links : [];
  monitorSelectedRouteGlowLayer?.setData(data);
  monitorSelectedRouteLayer?.setData(data);
}

function ensureMonitorRoadLayer() {
  if (!MapRef.value || monitorRoadLayer || !selectModel.value?.name) return;
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
  if (mode === "road-network") {
    ensureMonitorRoadLayer();
  }
  syncBaseMapLayerVisibility();
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

function modelLineFeatureByName(lineName) {
  const target = normalizeMonitorFeatureName(lineName);
  if (!target) return null;
  const features = busNetworkCollections.lines?.features || [];
  return features.find((feature) => {
    const properties = feature?.properties || {};
    return [properties.lineName, properties.lineId, busLineName(properties)]
      .some((value) => normalizeMonitorFeatureName(value) === target);
  }) || null;
}

function modelLineFeatureByRouteId(routeId) {
  const target = String(routeId ?? "");
  if (!target) return null;
  return (busNetworkCollections.lines?.features || []).find((feature) => {
    const properties = feature?.properties || {};
    return String(properties.routeId ?? properties.route_id ?? "") === target;
  }) || null;
}

function modelStationFeatureByName(stationName) {
  const target = normalizeMonitorFeatureName(stationName);
  if (!target) return null;
  return (busNetworkCollections.stations?.features || []).find((feature) => (
    normalizeMonitorFeatureName(busStationName(feature?.properties || {})) === target
  )) || null;
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
  source.setData(feature?.geometry ? { type: "FeatureCollection", features: [plainBusFeature(feature)] } : emptyFeatureCollection());
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
  return busNetworkCollections.lines.features.find((item) => {
    const itemKey = String(item?.properties?._lineKey || item?.id || "");
    return itemKey === key;
  }) || feature;
}

function routeOptionFromFeature(feature) {
  const fullFeature = fullBusLineFeature(feature) || feature;
  const properties = { ...(fullFeature?.properties || {}) };
  const fullName = pickerFullRouteName(properties);
  return {
    id: String(properties._lineKey || properties.routeId || properties.route_id || properties.lineId || properties.line_id || fullFeature?.id || fullName || "route"),
    name: fullName || busLineName(properties) || "未命名线路",
    properties,
    feature: fullFeature,
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
    flow: 0,
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

async function openLineRoutePicker(point, webMercatorXY, lngLat, domEvent) {
  if (!Array.isArray(point) || !Array.isArray(webMercatorXY) || !selectModel.value?.name) {
    closeLineRoutePicker();
    return;
  }
  const requestSeq = ++routePickRequestSeq;
  let routes = [];
  let segmentLinks = [];
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
    routes = dedupeRouteOptions(candidates.map(routeOptionFromCandidate));
    segmentLinks = routes[0]?.segmentLinks || [];
  } catch {
    if (requestSeq !== routePickRequestSeq) return;
    const fallback = fallbackRouteOptions(point, lngLat);
    routes = fallback.routes;
    segmentLinks = [];
  }
  if (!routes.length) {
    closeLineRoutePicker();
    clearLineSelection(); // 点击空白处取消选中
    return;
  }
  // 与数据管理一致：点中路段时先高亮最近路段；用户在列表选定后再高亮完整线路。
  selectedLineKey.value = "";
  selectedStationKey.value = "";
  setSelectedBusStation(null);
  lineMonitorRef.value?.clearSelection?.();
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
  // 在真实 routeDetail 返回前只保留点中的真实路段，不画站点直连的近似线。
  setMonitorSelectedRouteLinks(pendingLinks);
  setSelectedBusStation(null);
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
  selectedStationKey.value = String(props._stationKey || feature?.id || "");
  selectedLineKey.value = "";
  setSelectedBusStation(feature);
  setMonitorSelectedRouteLinks([]);
  const name = busStationName(props);
  if (!name) return;
  await nextTick();
  if (typeof stationMonitorRef.value?.selectStationByFeature === "function") {
    await stationMonitorRef.value.selectStationByFeature(props);
  } else {
    await stationMonitorRef.value?.selectStationByName?.(name);
  }
}

// 取消选中（点击地图空白处）
function clearLineSelection() {
  selectedLineKey.value = "";
  setMonitorSelectedRouteLinks([]);
  lineMonitorRef.value?.clearSelection?.();
}

function clearStationSelection() {
  selectedStationKey.value = "";
  setSelectedBusStation(null);
  stationMonitorRef.value?.clearSelection?.();
}

function handleBusNetworkMapClick(event) {
  closeLineRoutePicker();
  if (baseMapLineMode.value !== "bus-network") return;
  if (effectiveTab.value !== "线路客流监测" && effectiveTab.value !== "站点客流监测") return;
  const point = event?.data?.point;
  if (!Array.isArray(point)) return;
  if (effectiveTab.value === "站点客流监测") {
    const stationFeature = firstRenderedBusFeature(point, [RM_LAYER_STATION_SELECTED, RM_LAYER_STATIONS], 10);
    if (stationFeature) {
      selectStationFromBusNetwork(stationFeature);
    } else {
      clearStationSelection(); // 点击空白处取消选中
    }
    return;
  }
  // 复用数据管理逻辑：点击路段弹出经过该路段的所有线路，由用户选择具体线路（含方向）；点击空白处取消选中
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
    RM_LAYER_STATION_SELECTED,
    RM_LAYER_STATIONS,
    RM_LAYER_LINES,
  ].forEach((layerId) => {
    if (map.getLayer?.(layerId)) map.removeLayer(layerId);
  });
  [
    RM_SOURCE_SELECTED_STATION,
    RM_SOURCE_STATIONS,
    RM_SOURCE_LINES,
  ].forEach((sourceId) => {
    if (map.getSource?.(sourceId)) map.removeSource(sourceId);
  });
  busNetworkSourceRefs = new Map();
  busNetworkCollections = {
    lines: emptyFeatureCollection(),
    stations: emptyFeatureCollection(),
  };
}

const minLineWidth = computed(() => 0.1);
const maxLineWidth = computed(() => 2);
const minStationSize = computed(() => 32);
const maxStationSize = computed(() => 96);
const minVehicleSize = computed(() => 20);
const maxVehicleSize = computed(() => 72);

const lineWidthZoomScale = computed(() => {
  const delta = mapZoom.value - referenceZoom.value;
  const scale = Math.pow(2, 0.18 * delta);
  return Math.max(0.45, Math.min(1.55, scale));
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

watch(computedLineWidth, (val) => {
  applyLineWidth();
});
watch(computedFlowWidthStep, () => {
  applyFlowWidthStep();
});
watch(stationSize, () => {
  applyStationSize();
});
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
let clickListenerId = null;
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
  selectedSegment.value = null;
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
  showLineWidthPopover.value = !showLineWidthPopover.value;
}

function handleToggleInfo() {
  showRightPanel.value = !showRightPanel.value;
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
  applyBusNetworkPaint();
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
  applyBusNetworkPaint();
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
  applyStationSize();
  applyVehicleSize();
  applyVehicleVisibilityMode();
  syncBaseMapLayerVisibility();
}

function scheduleLayerSyncBurst(remaining = 6) {
  if (syncLayersRetryTimer) {
    clearTimeout(syncLayersRetryTimer);
    syncLayersRetryTimer = null;
  }
  const run = (left) => {
    syncAllLayerSettings();
    if (left > 1) {
      syncLayersRetryTimer = setTimeout(() => run(left - 1), 240);
    } else {
      syncLayersRetryTimer = null;
    }
  };
  run(remaining);
}

function handleLineWidthChange(val) {
  lineWidth.value = val;
  applyLineWidth();
  applyFlowWidthStep();
}

function handleStationSizeChange(val) {
  stationSize.value = val;
  applyStationSize();
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
        layer.setFlowControl(flowControl.value);
      }
    });
  }
  monitorRoadLayer?.setFlowControl(flowControl.value);
}

function handleFlowControlChange(val) {
  flowControl.value = val;
  applyFlowControl();
}

watch(MapRef, (mapInstance) => {
  setMapCenter();
  
  if (mapInstance) {
    bindBusNetworkClickListener();
    ensureMonitorBusRouteLayer();
    loadBusNetwork();
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
    if (clickListenerId) {
      mapInstance.removeEventListener("handle:click", clickListenerId);
      clickListenerId = null;
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
  if (mode === "road-network") {
    ensureMonitorRoadLayer();
  }
  syncBaseMapLayerVisibility();
});

watch(
  [() => selectModel.value?.name, isModelReady],
  ([modelName]) => {
    if (!isModelReady.value || !modelName) return;
    closeLineRoutePicker();
    selectedLineKey.value = "";
    selectedStationKey.value = "";
    setMonitorSelectedRouteLinks([]);
    setSelectedBusStation(null);
    if (monitorBusRouteLayer) {
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

const ins = setInterval(() => {
  handleGetSchemeList({ silent: true });
  handleGetModelList({ silent: true });
}, 1000 * 20);

async function handleAuthChanged() {
  datebase.value.scheme = "";
  setActiveModel("");
  clearBackgroundTask();
  schemeList.value = [];
  modelList.value = [];
  await handleGetSchemeList({ autoSelect: true });
}

onMounted(() => {
  if (isPerfProbeEnabled) {
    startPerfProbe();
  }
  observeLeftPanelSize();
  window.addEventListener("resize", centerLeftPanel);
  window.addEventListener("auth:changed", handleAuthChanged);
  document.addEventListener("keydown", handleDocumentKeydown);
  handleGetSchemeList({ autoSelect: true }).then(async () => {
    if (datebase.value.scheme && !modelList.value.length) {
      const list = await handleGetModelList();
      if (list.length && (!datebase.value.model || !list.some((item) => item.name === datebase.value.model))) {
        const preferred = list.find((item) => item.name === restoredSelection.model) || pickReadyModel(list) || list[0];
        setActiveModel(preferred.name);
      }
      await ensureSelectedModelReady();
    }
  }).finally(() => {
    initialModelBootstrap.value = false;
    isRestoringSelection = false;
    observeLeftPanelSize();
  });

  scheduleLayerSyncBurst(8);
});
onUnmounted(() => {
  modelLoadSeq++;
  backgroundTaskSeq++;
  stopPerfProbe();
  unbindBusNetworkClickListener();
  clearBusNetworkLayers();
  monitorSelectedRouteLayer?.dispose();
  monitorSelectedRouteLayer = null;
  monitorSelectedRouteGlowLayer?.dispose();
  monitorSelectedRouteGlowLayer = null;
  monitorBusRouteLayer?.dispose();
  monitorBusRouteLayer = null;
  monitorRoadLayer?.dispose();
  monitorRoadLayer = null;
  leftPanelResizeObserver?.disconnect();
  leftPanelResizeObserver = null;
  window.removeEventListener("resize", centerLeftPanel);
  window.removeEventListener("auth:changed", handleAuthChanged);
  document.removeEventListener("keydown", handleDocumentKeydown);
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
  max-width: min(46vw, 520px);
  min-width: 0;
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

.segment-info-popover {
  position: fixed;
  width: 240px;
  max-width: calc(100vw - 32px);
  background: var(--app-panel-bg);
  border: 1px solid rgba(21, 105, 222, 0.2);
  border-radius: var(--app-panel-radius);
  box-shadow: var(--app-shadow-sm);
  padding: 14px 16px;
  z-index: var(--z-popover);
  pointer-events: auto;
  transform-origin: top left;
  transition: opacity 0.2s ease, transform 0.2s ease;
  scale: var(--app-panel-scale);

  .popover-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    border-bottom: 1px solid rgba(21, 105, 222, 0.15);
    padding-bottom: 8px;
    margin-bottom: 10px;

    .title {
      font-size: 13px;
      font-weight: 700;
      color: var(--app-blue);
      letter-spacing: 0.5px;
      font-family: var(--app-font-number);
    }

    .close-btn {
      background: none;
      border: none;
      color: var(--app-muted);
      font-size: 18px;
      cursor: pointer;
      padding: 0 4px;
      line-height: 1;
      transition: color 0.2s ease;

      &:hover {
        color: #dc4c5d;
      }
    }
  }

  .popover-body {
    display: flex;
    flex-direction: column;
    gap: 8px;

    .info-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: 12px;

      .label {
        color: var(--app-muted);
        font-weight: 500;
      }

      .val {
        min-width: 0;
        max-width: 140px;
        overflow-wrap: anywhere;
        text-align: right;
        color: var(--app-ink);
        font-weight: 700;
        font-family: var(--app-font-number);
      }
    }
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

  #datavisualization_index_box2 {
    height: 100%;
    min-height: 0;
    display: flex;
    flex-direction: column;
    align-items: stretch;
    gap: var(--dm2-space-3);
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
  grid-template-columns: 1fr;
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

.rm-overall-chart {
  flex: 0 0 226px;
  min-height: 226px;
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
