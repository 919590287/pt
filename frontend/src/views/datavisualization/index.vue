<!-- index -->
<template>
  <div class="datebase_box analysis-model-toolbar" role="search" aria-label="方案与模型选择" data-tour="model-selector">
    <div class="data-source-segment analysis-source-segment" role="group" aria-label="数据源类型">
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
    <label class="handle analysis-model-label" for="scheme-selector">{{ isSimulationMode ? "当前方案" : "当前区域" }}</label>
    <el-select v-if="isSimulationMode" id="scheme-selector" class="analysis-scheme-select" v-model="datebase.scheme" clearable filterable :loading="isLoadingSchemes" aria-label="当前方案">
      <el-option v-for="item in schemeList" :key="item" :label="item" :value="item"> </el-option>
    </el-select>
    <el-select v-else id="scheme-selector" class="analysis-scheme-select" :model-value="realAreaName" disabled aria-label="当前区域">
      <el-option :label="realAreaName" :value="realAreaName" />
    </el-select>
    <el-select v-if="isSimulationMode" class="model-select analysis-model-select" v-model="modelPickerValue" :disabled="!datebase.scheme || isLoadingModels" clearable filterable :loading="isLoadingModels" aria-label="选择模型" @change="handleModelPick">
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
    <el-select v-else class="model-select analysis-model-select real-date-select" v-model="realServiceDate" filterable aria-label="真实客流日期">
      <el-option
        v-for="item in realServiceDateOptions"
        :key="item.value"
        :label="item.label"
        :value="item.value"
      />
    </el-select>
    <span v-if="loadError" class="load-error" role="status">{{ loadError }}</span>
  </div>

  <ModelProgressBubble
    v-if="backgroundTaskVisible"
    :title="backgroundTaskTitle"
    :message="backgroundTaskProgress.message"
    :percent="backgroundTaskProgress.percent"
    :elapsed-seconds="backgroundTaskProgress.elapsedSeconds"
    :eta-seconds="backgroundTaskProgress.etaSeconds"
    :failed="backgroundTaskProgress.failed"
    :show-cancel="backgroundSwitchOnReady"
    cancel-text="取消切换"
    @cancel="cancelPendingAutoSwitch"
  />

  <template v-if="selectModel">
    <template v-if="isModelReady">
      <div :class="['dm-sidebar', isRunMonitorLeftCollapsed ? 'is-collapsed' : '']">
        <nav class="sidebar-nav" :aria-label="props.mode === 'pfa' ? '客流分析导航' : '运行监测导航'" data-tour="module-navigation">
          <div v-for="item in displayMenuItems" :key="item.key" class="menu-group">
            <button
              type="button"
              :class="['nav-item', isNavItemActive(item) ? 'active' : '']"
              :data-tour-module="item.key"
              :aria-expanded="item.children ? pfaIsExpanded(item.key) : undefined"
              @click="handleNavItemClick(item)"
            >
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
                  <span class="nav-label">{{ sub.label }}</span>
                </button>
              </div>
            </transition>
          </div>
        </nav>

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
        data-tour="object-search"
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

      <!-- 体检评估分析：舍弃地图底图与窄侧栏，展示高端全屏体检评估分析主看板 -->
      <div
        v-if="activeTab === '体检评估分析'"
        class="tjfx-full-stage-wrapper"
        :class="{ 'is-left-collapsed': isRunMonitorLeftCollapsed }"
      >
        <TJFX
          :key="`tjfx-${selectModel.name}`"
          :model="selectModel.name"
          v-model:district="selectedDisplayRange"
        />
      </div>

      <!-- 监测组件宿主：仅承载数据加载 / 地图图层 / 右侧 teleport，自身界面隐藏，交互通过上方搜索框与地图完成 -->
      <div class="run-monitor-mount" aria-hidden="true">
        <RKFB v-if="activeTab == '人口分布监测'" :key="`rkfb-${selectModel.name}`" :model="selectModel.name" :metric="populationSection" :three-dimensional="is3DActive" />
        <QZDFB v-else-if="activeTab == '公交出行监测' && busTravelSection === '出行分布监测'" :key="`qzdfb-${selectModel.name}`" :model="selectModel.name" :three-dimensional="is3DActive" />
        <GJOD v-else-if="activeTab == '公交出行监测' && busTravelSection === '公交OD监测'" :key="`gjod-${selectModel.name}`" :model="selectModel.name" />
        <CFXS v-else-if="activeTab == '客流走廊监测' && corridorSection === '线路重复系数'" :key="`cfxs-${selectModel.name}`" :model="selectModel.name" />
        <GJKL v-else-if="activeTab == '客流走廊监测' && corridorSection === '公交客流走廊'" :key="`gjkl-${selectModel.name}`" :model="selectModel.name" />
        <XLZL v-else-if="activeTab == '线路客流监测'" ref="lineMonitorRef" :key="`xlzl-${selectModel.name}`" :model="selectModel.name" />
        <ZDZL v-else-if="activeTab == '站点客流监测'" ref="stationMonitorRef" :key="`zdzl-${selectModel.name}`" :model="selectModel.name" />
        <GJYS
          v-else-if="activeTab == '车辆运行监测'"
          :key="`gjys-${selectModel.name}`"
          :model="selectModel.name"
          :run-monitor-panels="true"
        />
      </div>
      <button
        v-if="isRightPanelVisible"
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

      <div v-if="isRightPanelVisible" id="right-info-panel" :class="['dm-overview-panel', 'run-monitor-right-panel', isRightCollapsed ? 'is-collapsed' : '']" data-tour="insight-panel">
        <el-scrollbar class="flex_column_scroll_box">
          <div id="datavisualization_index_box2">
            <div v-if="activeTab === '总体客流监测'" class="rm-right-card overall-flow-card">
              <div class="rm-right-card-title">
                <div>
                  <h2>总体客流监测</h2>
                </div>
                <!-- 已有数据时的后台刷新只用轻提示，不覆盖正文 -->
                <span v-if="overallFlowRefreshing" class="rm-refresh-note" role="status">更新中</span>
              </div>

              <!-- 状态机：等待模型 / 失败 / 缓存生成 / 首次加载 / 空 —— 整块替换正文，避免状态浮在 0 值之上 -->
              <div v-if="!isModelReady" class="rm-flow-state rm-flow-status" role="status">
                <span class="rm-flow-status-icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M3 7l9-4 9 4-9 4-9-4z"></path>
                    <path d="M3 12l9 4 9-4"></path>
                    <path d="M3 17l9 4 9-4"></path>
                  </svg>
                </span>
                <p class="rm-flow-status-title">等待模型就绪</p>
                <p class="rm-flow-status-desc">选择并加载模型后，这里会显示全日客流总量与 24 小时曲线。</p>
              </div>

              <div v-else-if="overallFlowError" class="rm-flow-state rm-flow-status" role="alert">
                <span class="rm-flow-status-icon is-error" aria-hidden="true">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path>
                    <line x1="12" y1="9" x2="12" y2="13"></line>
                    <line x1="12" y1="17" x2="12.01" y2="17"></line>
                  </svg>
                </span>
                <p class="rm-flow-status-title">客流数据加载失败</p>
                <p class="rm-flow-status-desc">{{ overallFlowError }}</p>
                <button type="button" class="rm-flow-status-retry" @click="loadOverallFlow()">重新加载</button>
              </div>

              <div v-else-if="overallFlowGeneratingVisible" class="rm-flow-state rm-flow-status" role="status">
                <span class="rm-flow-status-icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
                    <circle cx="12" cy="12" r="9"></circle>
                    <polyline points="12 7 12 12 15.5 14"></polyline>
                  </svg>
                </span>
                <p class="rm-flow-status-title">客流缓存生成中</p>
                <p class="rm-flow-status-desc">后端正在为当前模型生成客流缓存，稍后重新加载即可查看。</p>
                <button type="button" class="rm-flow-status-retry" @click="loadOverallFlow()">重新加载</button>
              </div>

              <div v-else-if="overallFlowSkeletonVisible" class="rm-flow-state rm-flow-skeleton" aria-hidden="true">
                <div class="rm-sk rm-sk-hero rm-sk-shimmer"></div>
                <div class="rm-sk rm-sk-split rm-sk-shimmer"></div>
                <div class="rm-sk rm-sk-chart rm-sk-shimmer"></div>
              </div>

              <div v-else-if="!overallFlowHasData" class="rm-flow-state rm-flow-status" role="status">
                <span class="rm-flow-status-icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M4 17c4-5 8 5 16-1"></path>
                    <path d="M4 7c5 0 8 5 16 1"></path>
                  </svg>
                </span>
                <p class="rm-flow-status-title">当前范围暂无客流</p>
                <p class="rm-flow-status-desc">{{ selectedDisplayRangeLabel }}范围内没有产生客流的线路，可切换显示范围后查看。</p>
              </div>

              <template v-else>
                <div class="rm-flow-hero">
                  <p class="rm-flow-hero-value">
                    <strong>{{ formatFlowNumber(overallFlowTotal) }}</strong>
                    <em>人次/日</em>
                  </p>
                </div>

                <!-- 方式构成同时充当折线图图例：色块与 series 同色，图表内不再重复画图例 -->
                <div class="rm-mode-split">
                  <div class="rm-mode-head" aria-hidden="true">
                    <span class="rm-mode-head-name">客流类型</span>
                    <span class="rm-mode-head-value">总客流量</span>
                    <span class="rm-mode-head-share">占比</span>
                  </div>
                  <div v-for="mode in overallFlowModes" :key="mode.key" class="rm-mode-row">
                    <span class="rm-mode-dot" :style="{ background: mode.color }" aria-hidden="true"></span>
                    <span class="rm-mode-name">{{ mode.label }}</span>
                    <strong class="rm-mode-value">{{ formatFlowNumber(mode.value) }}<em class="rm-mode-unit">人次/日</em></strong>
                    <span class="rm-mode-share">{{ mode.shareText }}</span>
                  </div>
                </div>

                <div
                  class="rm-overall-chart rm-clickable-chart"
                  role="button"
                  tabindex="0"
                  title="点击图表全屏查看"
                  aria-label="放大查看总体客流监测图表"
                  @click="openOverallFlowFullscreen"
                  @keydown.enter.prevent="openOverallFlowFullscreen"
                  @keydown.space.prevent="openOverallFlowFullscreen"
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
                </div>

                <div v-if="!isSimulationMode && overallFlowDailyFlow.length" class="rm-ops-block rm-daily-flow-block">
                  <div class="rm-ops-head rm-daily-flow-head">
                    <span class="rm-ops-title">日总客流变化</span>
                    <div class="rm-daily-range" aria-label="日总客流统计日期范围">
                      <input
                        v-model="overallDailyStart"
                        type="date"
                        :min="overallDailyMin"
                        :max="overallDailyEnd || overallDailyMax"
                        aria-label="开始日期"
                      />
                      <span>至</span>
                      <input
                        v-model="overallDailyEnd"
                        type="date"
                        :min="overallDailyStart || overallDailyMin"
                        :max="overallDailyMax"
                        aria-label="结束日期"
                      />
                    </div>
                  </div>
                  <div class="rm-daily-flow-chart">
                    <VChart
                      class="rm-chart"
                      :option="overallDailyChartOption"
                      autoresize
                      :update-options="{ notMerge: true }"
                    />
                  </div>
                </div>

                <!-- 分企业运营指标：真实数据按线路 SHP 的 company 字段归属并汇总刷卡客流 -->
                <div class="rm-ops-block rm-ops-operators">
                  <div class="rm-ops-head">
                    <span class="rm-ops-title">分企业运营指标</span>
                    <span class="rm-ops-scope" title="仅统计常规公交（不含轨道）；车辆数与班次来自模型班次表，车公里 = Σ班次×线路长度">常规公交口径</span>
                  </div>
                  <table class="rm-ops-table" aria-label="各企业运营效率指标">
                    <thead>
                      <tr>
                        <th scope="col">企业</th>
                        <th scope="col" title="车均日载客量（人次/车·日）">车均日载客</th>
                        <th scope="col" title="单班次载客量（人次/班）">单班次载客</th>
                        <th scope="col" title="客流强度（人次/车公里）">客流强度</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="row in operatorOpsRows" :key="row.name">
                        <td>{{ row.name }}</td>
                        <td>
                          {{ row.perVehicle.value }}<em v-if="row.perVehicle.unit" class="rm-ops-unit">{{ row.perVehicle.unit }}</em>
                        </td>
                        <td>
                          {{ row.perTrip.value }}<em v-if="row.perTrip.unit" class="rm-ops-unit">{{ row.perTrip.unit }}</em>
                        </td>
                        <td>
                          {{ row.intensity.value }}<em v-if="row.intensity.unit" class="rm-ops-unit">{{ row.intensity.unit }}</em>
                        </td>
                      </tr>
                      <tr v-if="!operatorOpsRows.length">
                        <td class="rm-ops-empty" colspan="4">当前范围暂无可匹配企业运营数据</td>
                      </tr>
                      <tr class="rm-ops-summary-row">
                        <td><strong>{{ overallOpsRow.name }}</strong></td>
                        <td>
                          <strong>{{ overallOpsRow.perVehicle.value }}</strong><em v-if="overallOpsRow.perVehicle.unit" class="rm-ops-unit">{{ overallOpsRow.perVehicle.unit }}</em>
                        </td>
                        <td>
                          <strong>{{ overallOpsRow.perTrip.value }}</strong><em v-if="overallOpsRow.perTrip.unit" class="rm-ops-unit">{{ overallOpsRow.perTrip.unit }}</em>
                        </td>
                        <td>
                          <strong>{{ overallOpsRow.intensity.value }}</strong><em v-if="overallOpsRow.intensity.unit" class="rm-ops-unit">{{ overallOpsRow.intensity.unit }}</em>
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </template>
            </div>

            <div v-else-if="activeTab === '线路客流监测' && !(props.mode === 'pfa' && selectedLineName)" class="rm-right-card line-flow-card">
              <template v-if="selectedLinePanel && props.mode !== 'pfa'">
                <div class="rm-right-card-title">
                  <div class="rm-panel-title-main">
                    <h2>
                      <span class="rm-line-name-text">{{ selectedLineBaseName || '线路客流' }}</span>
                      <span
                        v-if="selectedLineBaseName"
                        class="rm-line-company"
                        :title="`所属企业：${selectedLineCompany}`"
                      >{{ selectedLineCompany }}</span>
                    </h2>
                    <p
                      v-if="lineEndpointPair"
                      class="rm-line-endpoints"
                      :title="`${lineEndpointPair.start} 至 ${lineEndpointPair.end}`"
                    >
                      <span>{{ lineEndpointPair.start }}</span>
                      <span class="rm-line-endpoints-sep" aria-hidden="true">⇄</span>
                      <span>{{ lineEndpointPair.end }}</span>
                    </p>
                  </div>
                </div>

                <div class="rm-flow-hero">
                  <p class="rm-flow-hero-value">
                    <strong>{{ lineFlowTotal > 0 ? formatFlowNumber(lineFlowTotal) : '--' }}</strong>
                    <em>人次/日</em>
                  </p>
                </div>

                <!-- 方向构成同时充当折线图图例：色条与 series 同色，图表内不再重复画图例；悬停某行会聚焦对应曲线 -->
                <div v-if="lineDirectionSeries.length > 1" class="rm-flow-legend">
                  <div
                    v-for="(direction, index) in lineDirectionSeries"
                    :key="direction.key"
                    class="rm-flow-legend-row"
                    :title="direction.fullLabel"
                    @mouseenter="focusLineFlowSeries(index)"
                    @mouseleave="blurLineFlowSeries()"
                  >
                    <span class="rm-flow-swatch" :style="{ background: direction.color }" aria-hidden="true"></span>
                    <span class="rm-flow-legend-name">{{ direction.label }}</span>
                    <strong class="rm-flow-legend-value">{{ formatFlowNumber(direction.value) }}</strong>
                    <span class="rm-flow-legend-share">{{ direction.shareText }}</span>
                  </div>
                </div>

                <div class="rm-overall-chart">
                  <el-auto-resizer class="chart_box">
                    <template #default="{ height, width }">
                      <VChart
                        v-if="width > 0 && height > 0"
                        ref="lineFlowChartRef"
                        class="rm-chart"
                        :option="lineFlowChartOption"
                        autoresize
                        :update-options="{ notMerge: true }"
                      />
                    </template>
                  </el-auto-resizer>
                </div>

                <div class="rm-flow-kpi-grid">
                  <div v-for="item in lineOperationStats" :key="item.label" class="rm-flow-kpi-item">
                    <span class="rm-flow-kpi-label">{{ item.label }}</span>
                    <strong class="rm-flow-kpi-value">
                      {{ item.value }}<em v-if="item.unit">{{ item.unit }}</em>
                    </strong>
                  </div>
                </div>
              </template>
              <!-- 未选中线路：展示全网线路排名 TOP10（五种口径可切换），点击排名行等价于搜索框选中该线 -->
              <div v-else class="rm-flow-rank">
                <div class="rm-right-card-title">
                  <div class="rm-panel-title-main">
                    <h2>线路客流监测</h2>
                  </div>
                </div>

                <div class="rm-rank-metric-row">
                  <span class="rm-rank-metric-label">排名依据</span>
                  <el-select
                    v-model="lineRankMetric"
                    class="rm-rank-metric-select"
                    size="small"
                    aria-label="排名依据"
                  >
                    <el-option
                      v-for="metric in LINE_RANK_METRICS"
                      :key="metric.key"
                      :value="metric.key"
                      :label="`${metric.label}（${metric.unit}）`"
                    />
                  </el-select>
                </div>

                <div v-if="!lineFlowRank" class="rm-flow-state rm-flow-skeleton" aria-hidden="true">
                  <div v-for="n in 8" :key="n" class="rm-sk rm-sk-rank-row rm-sk-shimmer"></div>
                </div>

                <template v-else-if="lineFlowRank.rows.length">
                  <ol class="rm-rank-list" aria-label="线路排名">
                    <li v-for="row in lineFlowRank.rows" :key="row.name">
                      <button type="button" class="rm-rank-row" :title="`查看 ${row.name} 客流详情`" @click="selectFlowRankRow(row)">
                        <span :class="['rm-rank-index', row.rank <= 3 ? 'is-top' : '']">{{ row.rank }}</span>
                        <span class="rm-rank-main">
                          <span class="rm-rank-head">
                            <span class="rm-rank-name">
                              {{ row.name }}
                              <span class="rm-rank-operator" :title="`所属企业：${row.operator || '-'}`">{{ row.operator || '-' }}</span>
                            </span>
                            <span class="rm-rank-value">{{ row.valueText }}<em>{{ activeLineRankMetric.unit }}</em></span>
                          </span>
                          <span class="rm-rank-bar" aria-hidden="true"><i :style="{ width: row.barWidth }"></i></span>
                        </span>
                      </button>
                    </li>
                  </ol>
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
                  <p class="rm-empty-text">当前范围暂无线路客流，可点击地图上的线路或使用搜索框查看详情</p>
                </div>
              </div>
            </div>

            <div v-else-if="activeTab === '站点客流监测' && !(props.mode === 'pfa' && selectedStationName)" class="rm-right-card station-flow-card">
              <template v-if="selectedStationName && props.mode !== 'pfa'">
                <div class="rm-right-card-title">
                  <div class="rm-panel-title-main">
                    <h2>{{ selectedStationName || '站点客流' }}</h2>
                  </div>
                </div>

                <!-- 状态机：失败 / 生成中 / 加载中 / 无客流 —— 整块替换正文，不再让状态标签浮在一片 0 值之上 -->
                <div v-if="stationPanelStatus === 'error'" class="rm-panel-error">{{ stationPanelError || '站点客流数据加载失败' }}</div>

                <div v-else-if="stationPanelStatus === 'generating'" class="rm-flow-state rm-flow-status" role="status">
                  <span class="rm-flow-status-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
                      <path d="M12 21s7-5.2 7-11a7 7 0 1 0-14 0c0 5.8 7 11 7 11Z"></path>
                      <circle cx="12" cy="10" r="2.5"></circle>
                    </svg>
                  </span>
                  <p class="rm-flow-status-title">站点客流缓存生成中</p>
                  <p class="rm-flow-status-desc">后端正在为当前模型生成站点客流缓存，稍后重新加载即可查看。</p>
                </div>

                <div v-else-if="stationPanelStatus === 'loading'" class="rm-flow-state rm-flow-skeleton" aria-hidden="true">
                  <div class="rm-sk rm-sk-hero rm-sk-shimmer"></div>
                  <div class="rm-sk rm-sk-split rm-sk-shimmer"></div>
                  <div class="rm-sk rm-sk-chart rm-sk-shimmer"></div>
                </div>

                <div v-else-if="!stationFlowHasData" class="rm-flow-state rm-flow-status" role="status">
                  <span class="rm-flow-status-icon" aria-hidden="true">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
                      <path d="M12 21s7-5.2 7-11a7 7 0 1 0-14 0c0 5.8 7 11 7 11Z"></path>
                      <circle cx="12" cy="10" r="2.5"></circle>
                    </svg>
                  </span>
                  <p class="rm-flow-status-title">该站点暂无客流</p>
                  <p class="rm-flow-status-desc">当前模型下这个站点全天没有上下车记录，可换个站点或切换模型后查看。</p>
                </div>

                <template v-else>
                  <div class="rm-flow-hero">
                    <p class="rm-flow-hero-value">
                      <strong>{{ formatFlowNumber(stationFlowTotal) }}</strong>
                      <em>人次/日</em>
                    </p>
                  </div>

                  <!-- 站点两侧构成同时充当折线图图例：色条与 series 同色，也与地图上两个高亮站点的圈色一致 -->
                  <div v-if="stationFlowLegend.length > 1" class="rm-flow-legend">
                    <div
                      v-for="(side, index) in stationFlowLegend"
                      :key="side.key"
                      class="rm-flow-legend-row"
                      @mouseenter="focusStationFlowSeries(index)"
                      @mouseleave="blurStationFlowSeries()"
                    >
                      <span class="rm-flow-swatch" :style="{ background: side.color }" aria-hidden="true"></span>
                      <span class="rm-flow-legend-name">{{ side.label }}</span>
                      <strong class="rm-flow-legend-value">{{ formatFlowNumber(side.value) }}</strong>
                      <span class="rm-flow-legend-share">{{ side.shareText }}</span>
                    </div>
                  </div>

                  <div class="rm-overall-chart">
                    <el-auto-resizer class="chart_box">
                      <template #default="{ height, width }">
                        <VChart
                          v-if="width > 0 && height > 0"
                          ref="stationFlowChartRef"
                          class="rm-chart"
                          :option="stationFlowChartOption"
                          autoresize
                          :update-options="{ notMerge: true }"
                        />
                      </template>
                    </el-auto-resizer>
                  </div>

                  <div class="rm-flow-kpi-grid">
                    <div v-for="item in stationBoardingStats" :key="item.label" class="rm-flow-kpi-item">
                      <span class="rm-flow-kpi-label">{{ item.label }}</span>
                      <strong class="rm-flow-kpi-value">
                        {{ item.value }}<em v-if="item.unit">{{ item.unit }}</em>
                      </strong>
                    </div>
                  </div>
                </template>
              </template>
              <!-- 未选中站点：展示全网站点客流排名，点击排名行等价于搜索框选中该站 -->
              <div v-else class="rm-flow-rank">
                <div class="rm-right-card-title">
                  <div class="rm-panel-title-main">
                    <h2>站点客流监测</h2>
                  </div>
                </div>

                <div v-if="!stationFlowRank" class="rm-flow-state rm-flow-skeleton" aria-hidden="true">
                  <div v-for="n in 8" :key="n" class="rm-sk rm-sk-rank-row rm-sk-shimmer"></div>
                </div>

                <template v-else-if="stationFlowRank.rows.length">
                  <ol class="rm-rank-list" aria-label="站点客流排名">
                    <li v-for="row in stationFlowRank.rows" :key="row.name">
                      <button type="button" class="rm-rank-row" :title="`查看 ${row.name} 客流详情`" @click="selectFlowRankRow(row)">
                        <span :class="['rm-rank-index', row.rank <= 3 ? 'is-top' : '']">{{ row.rank }}</span>
                        <span class="rm-rank-main">
                          <span class="rm-rank-head">
                            <span class="rm-rank-name">{{ row.name }}</span>
                            <span class="rm-rank-value">{{ formatFlowNumber(row.flow) }}<em>人次/日</em></span>
                          </span>
                          <span class="rm-rank-bar" aria-hidden="true"><i :style="{ width: row.barWidth }"></i></span>
                        </span>
                      </button>
                    </li>
                  </ol>
                </template>

                <div v-else class="rm-panel-empty">
                  <span class="rm-empty-icon">
                    <svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">
                      <path d="M12 21s7-5.2 7-11a7 7 0 1 0-14 0c0 5.8 7 11 7 11Z"></path>
                      <circle cx="12" cy="10" r="2.5"></circle>
                    </svg>
                  </span>
                  <p class="rm-empty-text">当前范围暂无站点客流，可点击地图上的站点或使用搜索框查看详情</p>
                </div>
              </div>
            </div>

            <!-- 体检评估分析：右侧内容由 TJFX 组件 teleport 到本容器（评估指标表格 + 五维雷达图） -->
          </div>
        </el-scrollbar>
      </div>

      <!-- 车辆运行监测：轨迹演示控制条。原挂在左侧导航里（宽度仅 260px，滑块被挤扁）；
           时间轴回放本质是"媒体播放器"心智，移到它所驱动的地图底部居中，让因果同处一处。
           GJYS 组件把控制内容 teleport 进来；本容器只负责定位与随左右面板收合居中。 -->
      <div
        v-show="activeTab === '车辆运行监测'"
        id="run-monitor-playback-dock"
        data-tour="vehicle-playback"
        :class="['rm-playback-dock', isRunMonitorLeftCollapsed ? 'is-left-collapsed' : '', (isRightPanelVisible && !isRightCollapsed) ? 'with-panel' : 'without-panel']"
      ></div>

      <!-- Floating Map Controls Toolbar -->
      <div
        v-if="activeTab !== '体检评估分析'"
        :class="['map-controls-toolbar', (isRightPanelVisible && !isRightCollapsed) ? 'with-panel' : 'without-panel']"
        data-tour="map-controls"
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
        <div v-if="showSettingsControl" class="control-block settings-block">
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
          <div v-if="showSettingsControl && showLineWidthPopover" id="line-width-popover" class="line-width-popover" role="dialog" aria-modal="false" @click.stop>
            <div class="popover-title">{{ isVehicleMonitorTab ? '车辆模型设置' : '设置' }}</div>
            <div class="popover-content">
              <template v-if="isVehicleMonitorTab">
                <div class="slider-row">
                  <span class="label">
                    <span>车辆模型</span>
                    <span class="val-text">{{ `${vehicleSize}px` }}</span>
                  </span>
                  <el-slider v-model="vehicleSize" :min="minVehicleSize" :max="maxVehicleSize" :step="1" @input="handleVehicleSizeChange" />
                </div>
                <!-- 路段公交车速（拥堵路况）：需求为可开关且默认关闭；速度为净行驶口径（剔除站点停靠） -->
                <div class="layer-mode-row" title="路段公交净行驶车速（剔除站点停靠，随播放时刻变化，使用模型全部已加载事件）">
                  <span>路段公交车速</span>
                  <el-switch v-model="linkSpeedEnabled" />
                </div>
                <!-- 开着开关但图没出来时给出去向：生成中轮询 / 模型无数据 -->
                <p v-if="linkSpeedEnabled && linkSpeedStatus !== 'ready'" class="layer-mode-note" role="status">
                  {{ linkSpeedStatus === 'empty'
                    ? '该模型无公交车速数据'
                    : linkSpeedStatus === 'error'
                      ? '公交车速数据加载失败'
                      : '车速缓存生成中，就绪后自动上图…' }}
                </p>
                <div v-if="linkSpeedEnabled" class="slider-row">
                  <span class="label">
                    <span>车速透明度</span>
                    <span class="val-text">{{ `${linkSpeedOpacity}%` }}</span>
                  </span>
                  <el-slider v-model="linkSpeedOpacity" :min="10" :max="100" :step="1" />
                </div>
              </template>
              <template v-else>
                <!-- 运行监测/客流分析共用同一套公交、地铁显示设置 -->
                <div class="layer-mode-row">
                  <span>显示图层</span>
                  <div
                    class="layer-mode-segment"
                    role="group"
                    :aria-label="effectiveTab === '站点客流监测' ? '站点显示类型' : '线网显示图层'"
                  >
                    <button
                      type="button"
                      :class="{ active: baseMapLineMode === 'bus-network' }"
                      @click="handleBaseMapLineModeChange('bus-network')"
                    >
                      {{ effectiveTab === '站点客流监测' ? '公交站点' : '公交线网' }}
                    </button>
                    <button
                      type="button"
                      :class="{ active: baseMapLineMode === 'metro-network' }"
                      @click="handleBaseMapLineModeChange('metro-network')"
                    >
                      {{ effectiveTab === '站点客流监测' ? '地铁站点' : '地铁线网' }}
                    </button>
                  </div>
                </div>
                <div v-if="effectiveTab === '站点客流监测'" class="slider-row">
                  <span class="label">
                    <span>站点大小</span>
                    <span class="val-text">{{ `${stationSize}px` }}</span>
                  </span>
                  <el-slider v-model="stationSize" :min="minStationSize" :max="maxStationSize" :step="1" @input="handleStationSizeChange" />
                </div>
                <div v-else class="slider-row">
                  <span class="label">
                    <span>线宽</span>
                    <span class="val-text">{{ `${lineWidth}px` }}</span>
                  </span>
                  <el-slider v-model="lineWidth" :min="minLineWidth" :max="maxLineWidth" :step="0.1" @input="handleLineWidthChange" />
                </div>
              </template>
              <div class="vehicle-visibility-row" v-if="isVehicleMonitorTab">
                <span>可视化范围</span>
                <el-checkbox-group v-model="vehicleVisibilityMode" class="vehicle-visibility-options" aria-label="车辆可视化范围">
                  <el-checkbox
                    v-for="item in vehicleVisibilityOptions"
                    :key="item.value"
                    :value="item.value"
                  >
                    {{ item.label }}
                  </el-checkbox>
                </el-checkbox-group>
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

      <!-- 需求2/11：地图左下角浮动图例（线路客流 / 断面客流 / 站点热力 / 客流OD / 关联线路，互斥出现） -->
      <div v-if="showLineFlowLegend || showMetroFlowLegend || showSegmentFlowLegend || showStationHeatLegend || showStationFlowLegend || showOdCurveLegend || showLinkSpeedLegend || showTransferRelationLegend" class="map-flow-legend" @click.stop>
        <Transition name="popover-fade">
          <div
            v-if="showLineFlowScalePopover && (showLineFlowLegend || showMetroFlowLegend) && !lineLoadRateActive"
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
        <!-- 站点客流着色色阶/阈值设置（站点客流监测未选中态） -->
        <Transition name="popover-fade">
          <div
            v-if="showStationFlowScalePopover && showStationFlowLegend"
            class="map-legend-popover"
            role="dialog"
            aria-modal="false"
            aria-label="站点客流色阶设置"
          >
            <div class="map-legend-popover-title">
              站点客流色阶设置
              <span v-if="stationFlowMaxValue > 0" class="map-legend-popover-tail">最大站点 {{ Math.round(stationFlowMaxValue).toLocaleString() }} 人次</span>
            </div>
            <ColorScaleControl
              v-model="stationFlowScale"
              legend-title="站点客流"
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
        <!-- 关联线路模式：分类图例（黄=选中线路 / 蓝=直接换乘线路 / 图标=换乘站点），无色阶齿轮 -->
        <div v-if="showTransferRelationLegend" class="map-legend-card">
          <div class="map-legend-head">
            <span class="map-legend-title">关联线路（直接换乘）</span>
          </div>
          <div class="map-legend-item">
            <span
              class="map-legend-line"
              :style="{ background: PFA_TRANSFER_SELECTED_LINE_COLOR, boxShadow: `inset 0 0 0 1px ${PFA_TRANSFER_SELECTED_CASING_COLOR}` }"
            ></span>
            <span class="map-legend-label">选中线路</span>
          </div>
          <div class="map-legend-item">
            <span class="map-legend-line" :style="{ background: PFA_RELATED_LINE_COLOR }"></span>
            <span class="map-legend-label">直接换乘线路</span>
          </div>
          <div class="map-legend-item">
            <img class="map-legend-transfer-icon" :src="transferStationIconUrl" alt="换乘站点图标" />
            <span class="map-legend-label">换乘站点</span>
          </div>
        </div>
        <div v-else class="map-legend-card">
          <div class="map-legend-head">
            <span class="map-legend-title" :title="showLinkSpeedLegend ? '公交净行驶车速：剔除站点停靠、按 15 分钟窗聚合，随播放时刻变化（模型已加载事件全量）' : undefined">{{ showLinkSpeedLegend ? '公交车速（km/h）' : showOdCurveLegend ? '客流OD（人次）' : showStationHeatLegend ? '站点客流热力（相对密度）' : showStationFlowLegend ? '站点客流（人次/日）' : showSegmentFlowLegend ? '断面客流（人次）' : lineMetricLegendTitle }}</span>
            <button
              v-if="!showLinkSpeedLegend && !(lineLoadRateActive && !showOdCurveLegend && !showStationHeatLegend && !showSegmentFlowLegend)"
              type="button"
              class="map-legend-gear"
              :title="showOdCurveLegend ? '设置客流OD曲线色阶与阈值' : showStationHeatLegend ? '设置站点客流热力色阶与阈值' : showStationFlowLegend ? '设置站点客流色阶与阈值' : showSegmentFlowLegend ? '设置断面客流色阶与阈值' : '设置线路客流色阶'"
              :aria-label="showOdCurveLegend ? '设置客流OD曲线色阶与阈值' : showStationHeatLegend ? '设置站点客流热力色阶与阈值' : showStationFlowLegend ? '设置站点客流色阶与阈值' : showSegmentFlowLegend ? '设置断面客流色阶与阈值' : '设置线路客流色阶'"
              :aria-expanded="showOdCurveLegend ? showOdCurveScalePopover : showStationHeatLegend ? showStationHeatScalePopover : showStationFlowLegend ? showStationFlowScalePopover : showSegmentFlowLegend ? showSegmentFlowScalePopover : showLineFlowScalePopover"
              @click="showOdCurveLegend
                ? (showOdCurveScalePopover = !showOdCurveScalePopover)
                : showStationHeatLegend
                  ? (showStationHeatScalePopover = !showStationHeatScalePopover)
                  : showStationFlowLegend
                    ? (showStationFlowScalePopover = !showStationFlowScalePopover)
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

      <!-- 线路/站点客流监测未选中时的中部底部悬浮提示 -->
      <Transition name="rm-toast-fade">
        <div
          v-if="unselectedBottomHintText"
          class="rm-bottom-hint-toast"
          role="status"
          aria-live="polite"
        >
          <span class="rm-hint-toast-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
              <circle cx="12" cy="12" r="10"></circle>
              <line x1="12" y1="16" x2="12" y2="12"></line>
              <line x1="12" y1="8" x2="12.01" y2="8"></line>
            </svg>
          </span>
          <span class="rm-hint-toast-text">{{ unselectedBottomHintText }}</span>
        </div>
      </Transition>
    </template>
    <!-- 选中模型未就绪：与全局门禁一致的居中单窗口（替代原先左侧面板 + 中间弹窗两个窗口） -->
    <div v-else class="model-loading-gate page-model-loading" role="status" aria-live="polite" aria-busy="true">
      <div class="model-loading-card">
        <div class="model-loading-title">{{ selectedModelProgress.title }}</div>
        <div class="model-loading-message">{{ selectedModelProgress.message }}</div>
        <el-progress
          class="model-loading-progress"
          :percentage="selectedModelProgress.percent"
          :status="selectedModelProgress.failed ? 'exception' : undefined"
          :stroke-width="12"
        />
        <div class="model-loading-meta">
          <span>已用 {{ formatDuration(selectedModelProgress.elapsedSeconds) }}</span>
          <span>预计剩余 {{ formatDuration(selectedModelProgress.etaSeconds) }}</span>
        </div>
      </div>
    </div>
  </template>
  <div v-else ref="box1" class="model_box box1" :style="box1Style">
    <el-empty :description="isSimulationMode ? '请选择模型' : '真实数据加载失败，请检查数据目录'" />
  </div>

  <RunMonitorOnboarding
    :active="runMonitorOnboardingActive"
    :preference-visible="runMonitorOnboardingPreferenceVisible"
    :steps="runMonitorOnboardingSteps"
    @step-change="handleRunMonitorOnboardingStep"
    @exit="closeRunMonitorOnboarding"
    @finish="closeRunMonitorOnboarding"
    @preference="saveRunMonitorOnboardingPreference"
  />

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
          <div class="overall-flow-fullscreen-title">总体客流监测</div>
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
import { defineAsyncComponent } from "vue";
import { graphic, VChart } from "@/plugins/echarts";
import { Remove, VideoPlay } from "@element-plus/icons-vue";
import ModelProgressBubble from "@/components/ModelProgressBubble.vue";
import RunMonitorOnboarding from "@/components/RunMonitorOnboarding.vue";
import { formatDuration, unifiedModelProgress } from "@/utils/modelLoadProgress.js";
import { ElMessage, ElMessageBox } from "element-plus";
import { loadModel, unloadModel } from "@/api/scheme.js";
import { dataCenter } from "@/api/data.js";
import { getOverallFlow, getRouteCandidates, getRouteDetail, getRouteTileBinary } from "@/api/route.js";
import { useModelSelectionStore } from "@/stores/modelSelection.js";
import { useModelRuntimeStore } from "@/stores/modelRuntime.js";
import { useDisplayRangeStore } from "@/stores/displayRange.js";
import { abortOtherModelDataRequests, getCachedFacilityAll, getCachedLineAll, getCachedRoutePanel, getCachedStationPanel, peekCachedRoutePanel, warmModelInteractionCache } from "@/utils/modelDataCache.js";
import { createDebouncedMirror, runWhenIdle } from "./utils/panelShared.js";
import { displayRangeNetworkState } from "./utils/displayRangeReadiness.js";
import { hidesTransitNetwork } from "./utils/baseMapVisibility.js";
import { lngLatToWebMercator, webMercatorToLngLat } from "@/mymap/index.js";
import { getCachedAdminDistricts } from "@/utils/realDataCache.js";
import {
  DEFAULT_REAL_AREA,
  REAL_AVERAGE_DATE,
  getRealNetwork,
  getRealPassengerFlowCapabilities,
  isRealDatasource,
  realDatasource,
} from "@/utils/realPassengerFlow.js";
import {
  activeDistrictContext,
  adminDistrictOutlineStyle,
  clipLineStringToDistrictContext,
  districtOutlineFeatureCollection,
  districtNamesFromCollection,
  emptyFeatureCollection as emptyDistrictFeatureCollection,
  normalizeAdminDistrictCollection,
  pointInDistrictContext,
  segmentIntersectsDistrictContext,
} from "@/utils/adminDistrictRange.js";
import { RouteLayer } from "./layers/RouteLayer.js";
import ColorScaleControl from "./components/ColorScaleControl.vue";
import { buildLegendItems, buildValueLegendItems, classifyByBreaks, createColorScaleConfig, quantileBreaks, resolveColorScale, sortFlowValues } from "@/utils/colorSchemes.js";
import { PURE_METRO_LINE, isMetroLine, metroLineCanonicalName } from "@/utils/transitMode.js";
import {
  LINE_RANK_METRICS,
  RIGHT_PANEL_RANK_LIMIT,
  buildLineRankEntries,
  limitRightPanelRanking,
  lineRankMetricValues,
  lineRankValueText,
} from "@/utils/rightPanelRanking.js";
import { busOperationRatios } from "@/utils/busOperationMetrics.js";
import { chartInk } from "@/utils/chartInk.js";
import { MAP_THEME, hexNumber, hexToRgba } from "@/utils/mapTheme.js";
import busStationIconUrl from "@/assets/images/datamanagement/bus-station.svg?url";
import metroStationIconUrl from "@/assets/images/datamanagement/metro-station.svg?url";
import busStationHighlightIconUrl from "@/assets/images/datamanagement/bus-station_highlight.svg?url";
import transferStationIconUrl from "@/assets/images/datamanagement/transfer-station.svg?url";
import busStationHighlightReverseIconUrl from "@/assets/images/datamanagement/bus-station_highlight_reverse.svg?url";
import busStationOutsideIconUrl from "@/assets/images/datamanagement/bus-station_outside.svg?url";
import "../datamanagement/tokens.css";

import { useDraggable } from "@vueuse/core";

// mode: "monitor" = 运行监测（默认）；"pfa" = 客流分析（复用本视图）
// KeepAlive 缓存名（MapLayout include）。运行监测 / 客流分析两条路由复用本组件的同一实例，
// mode 由路由 props 下发，切换时仅走 props.mode 的响应式更新，不重建实例。
defineOptions({
  name: "DataVisualization",
});

const props = defineProps({
  mode: { type: String, default: "monitor" },
});
provide("rightPanelRankLimit", RIGHT_PANEL_RANK_LIMIT);
// 客流分析模式下，让 XLZL/ZDZL 把完整 MCard2 面板 teleport 到右侧（运行监测仍用简化卡片）
provide("pfaRightPanel", computed(() => props.mode === "pfa"));

const GJYS = defineAsyncComponent(() => import("./components/GJYS.vue"));
const XLZL = defineAsyncComponent(() => import("./components/XLZL.vue"));
const ZDZL = defineAsyncComponent(() => import("./components/ZDZL.vue"));
const TJFX = defineAsyncComponent(() => import("./components/TJFX.vue"));
const RKFB = defineAsyncComponent(() => import("./components/RKFB.vue"));
const QZDFB = defineAsyncComponent(() => import("./components/QZDFB.vue"));
const GJOD = defineAsyncComponent(() => import("./components/GJOD.vue"));
const CFXS = defineAsyncComponent(() => import("./components/CFXS.vue"));
const GJKL = defineAsyncComponent(() => import("./components/GJKL.vue"));

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
let leftPanelCenterFrame = null;

function centerLeftPanel() {
  if (typeof window === "undefined") return;
  if (leftPanelCenterFrame != null) return;
  const measure = () => {
    leftPanelCenterFrame = null;
    nextTick(() => {
      const box = box1Ref.value;
      if (!box) return;

      const maxLayoutHeight = (window.innerHeight - 150) / LEFT_PANEL_SCALE;
      const visualHeight = Math.min(box.offsetHeight, maxLayoutHeight) * LEFT_PANEL_SCALE;
      box1Y.value = Math.max(LEFT_PANEL_MIN_TOP, (window.innerHeight - visualHeight) / 2);
    });
  };
  if (typeof requestAnimationFrame === "function") {
    leftPanelCenterFrame = requestAnimationFrame(measure);
  } else {
    measure();
  }
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
const modelRuntime = useModelRuntimeStore();
const restoredSelection = modelSelectionStore.getSelection(MODEL_SELECTION_KEY);
let isRestoringSelection = Boolean(restoredSelection.scheme);
const dataSourceMode = ref(restoredSelection.sourceMode || "simulation");
const isSimulationMode = computed(() => dataSourceMode.value === "simulation");
const realAreaName = ref(DEFAULT_REAL_AREA);
const realServiceDate = ref(restoredSelection.realServiceDate || REAL_AVERAGE_DATE);
const realDataStatus = ref("idle");
const realDataCapabilities = shallowRef(null);
const realServiceDateOptions = computed(() => {
  const dates = Array.isArray(realDataCapabilities.value?.serviceDates)
    ? realDataCapabilities.value.serviceDates
    : [];
  return [
    { value: REAL_AVERAGE_DATE, label: `日平均${dates.length ? `（${dates.length}天）` : ""}` },
    ...dates.map((date) => ({ value: date, label: date })),
  ];
});
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
let schemeRequestSeq = 0;
let modelRequestSeq = 0;
let centerRequestSeq = 0;
let modelLoadSeq = 0;
let backgroundTaskSeq = 0;
let interactionCacheSeq = 0;
const selectModel = computed(() => {
  if (!isSimulationMode.value) {
    const ready = realDataStatus.value === "ready";
    return {
      name: realDatasource(realAreaName.value, realServiceDate.value),
      label: `${realAreaName.value}真实数据·${realServiceDate.value === REAL_AVERAGE_DATE ? "日平均" : realServiceDate.value}`,
      displayName: `${realAreaName.value}真实数据·${realServiceDate.value === REAL_AVERAGE_DATE ? "日平均" : realServiceDate.value}`,
      loadStatus: ready,
      loadStage: realDataStatus.value === "error" ? "failed" : "loading_config",
      cacheStatus: ready ? "ready" : realDataStatus.value === "error" ? "failed" : "building",
    };
  }
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
  !isSimulationMode.value
  || !selectModel.value?.name
  || (interactionCacheModel.value === selectModel.value.name && interactionCacheStatus.value === "ready")
));
const isInteractionCacheLoading = computed(() => (
  isSimulationMode.value
  && isBackendModelReady.value
  && Boolean(selectModel.value?.name)
  && !isInteractionCacheReady.value
));
// 真实数据采用非阻塞切换：后台预热未结束时页面也立即挂载，各面板复用同一批
// 在途请求渐进填充；不再把真实数据准备误展示成“生成模型缓存”的全屏弹窗。
const isModelReady = computed(() => !isSimulationMode.value || isBackendModelReady.value);
const backgroundTaskVisible = computed(() => Boolean(
  isSimulationMode.value
  && backgroundTaskModel.value
  && backgroundTaskModel.value.name !== datebase.value.model
  && !isModelReadyForView(backgroundTaskModel.value),
));
const backgroundTaskTitle = computed(() => {
  const item = backgroundTaskModel.value;
  if (!item) return "";
  const prefix = backgroundSwitchOnReady.value ? "加载完成后切换" : "后台加载";
  return `${prefix}：${getModelLabel(item)}`;
});
// 统一进度口径（真实后端字段：load* + cache*），供居中加载卡片与切换气泡共用
const selectedModelProgress = computed(() => {
  const progress = unifiedModelProgress(selectModel.value?.__optimistic || selectModel.value?.__selectedFallback ? null : selectModel.value);
  if (isInteractionCacheLoading.value && progress.ready) {
    return {
      ...progress,
      title: "正在预热客流交互缓存",
      message: interactionCacheMessage.value || "正在预热线路、站点、客流与体检评估数据",
    };
  }
  return progress;
});
const backgroundTaskProgress = computed(() => unifiedModelProgress(backgroundTaskModel.value));

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
    if (seq !== interactionCacheSeq || selectModel.value?.name !== key) return;
    interactionCacheStatus.value = "ready";
    interactionCacheMessage.value = "客流交互缓存已就绪";
  } catch (error) {
    if (seq !== interactionCacheSeq || selectModel.value?.name !== key) return;
    interactionCacheStatus.value = "error";
    interactionCacheError.value = error?.message || "客流交互缓存预热失败";
  }
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
    const readyItem = await waitForModelReady(item.name, () => seq === backgroundTaskSeq && backgroundModelName.value === item.name);
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
      setActiveModel("");
      loadError.value = "当前模型已卸载，请明确选择新的模型";
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
      const restored = list.find((item) => item.name === restoringModel);
      if (restoringModel && !restored) {
        loadError.value = `原选择模型不存在或已被移除：${restoringModel}`;
      } else if (restored) {
        setActiveModel(restored.name);
      }
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
    const list = await modelRuntime.fetchSchemes();
    if (seq !== schemeRequestSeq) return schemeList.value;
    schemeList.value = list;
    if (autoSelect && !datebase.value.scheme && list.length) {
      datebase.value.scheme = list[0];
    } else if (datebase.value.scheme && !list.includes(datebase.value.scheme)) {
      const missingScheme = datebase.value.scheme;
      datebase.value.scheme = "";
      setActiveModel("");
      loadError.value = `原选择方案不存在或已被移除：${missingScheme}`;
    }
    if (!silent && !list.length) {
      loadError.value = "暂无可用方案";
    }
    return list;
  } catch (error) {
    if (seq === schemeRequestSeq && !silent) {
      loadError.value = error?.message || "方案列表加载失败，请检查后端服务";
    }
    throw error;
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
    if (!isSimulationMode.value) {
      interactionCacheModel.value = modelName || "";
      interactionCacheStatus.value = "ready";
      interactionCacheMessage.value = "";
      interactionCacheError.value = "";
      return;
    }
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
  [dataSourceMode, () => datebase.value.scheme, () => datebase.value.model, realServiceDate],
  () => {
    modelSelectionStore.setSelection(MODEL_SELECTION_KEY, {
      sourceMode: dataSourceMode.value,
      scheme: datebase.value.scheme,
      model: datebase.value.model,
      realServiceDate: realServiceDate.value,
    });
  },
);
watch(dataSourceMode, async (mode) => {
  closeLineRoutePicker();
  clearLineSelection();
  clearStationSelection();
  runMonitorSearchKeyword.value = "";
  nextTick(setMapCenter);
  if (mode !== "simulation") {
    loadError.value = "";
    await ensureRealDataReady();
    return;
  }
  if (!schemeList.value.length) {
    await handleGetSchemeList({ autoSelect: true });
  } else if (datebase.value.scheme && !modelList.value.length) {
    await handleGetModelList();
  }
  await ensureSelectedModelReady();
});

let realDataLoadSeq = 0;
let realDataLoadPromise = null;

function activateReadyRealDataView() {
  if (isSimulationMode.value || realDataStatus.value !== "ready") return;
  nextTick(() => {
    if (isSimulationMode.value || realDataStatus.value !== "ready") return;
    syncActiveMenuWithAvailability();
    setMapCenter();
    ensureTransitNetworkForCurrentTab();
  });
}

async function ensureRealDataReady() {
  if (realDataStatus.value === "ready") {
    activateReadyRealDataView();
    return realDataCapabilities.value;
  }
  if (realDataLoadPromise) return realDataLoadPromise;
  const seq = ++realDataLoadSeq;
  realDataStatus.value = "loading";
  if (!isSimulationMode.value) loadError.value = "";
  const datasource = realDatasource(realAreaName.value, REAL_AVERAGE_DATE);
  const request = Promise.all([
    getRealPassengerFlowCapabilities(datasource),
    getRealNetwork(datasource),
  ])
    .then(([capabilities]) => {
      if (seq !== realDataLoadSeq) return null;
      realDataCapabilities.value = capabilities || null;
      const dates = realDataCapabilities.value?.serviceDates || [];
      if (realServiceDate.value !== REAL_AVERAGE_DATE && !dates.includes(realServiceDate.value)) {
        realServiceDate.value = REAL_AVERAGE_DATE;
      }
      realDataStatus.value = "ready";
      activateReadyRealDataView();
      return capabilities;
    })
    .catch((error) => {
      if (seq !== realDataLoadSeq) return null;
      realDataStatus.value = "error";
      if (!isSimulationMode.value) {
        loadError.value = error?.message || "真实客流数据加载失败";
      }
      return null;
    })
    .finally(() => {
      if (realDataLoadPromise === request) realDataLoadPromise = null;
    });
  realDataLoadPromise = request;
  return request;
}

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
const pageMapLayerHost = inject("PageMapLayerHost", null);
watch(MapRef, setMapCenter);
async function setMapCenter() {
  if (!pageActive.value) return;
  const seq = ++centerRequestSeq;
  // 已选择行政区时，相机始终服从行政区；模型/真实数据中心只能用于“全市”。
  // 行政区几何尚在加载时也不请求模型中心，待 loadDisplayRanges 完成后再定位，
  // 避免异步中心请求把刚恢复的行政区视角覆盖掉。
  if (selectedDisplayRange.value !== DISPLAY_RANGE_ALL) {
    if (activeDisplayRangeContext.value) fitDisplayRangeContext();
    return;
  }
  if (selectModel.value && selectModel.value.name && isModelReady.value) {
    try {
      const res = await dataCenter({ datasource: selectModel.value.name }, { silentError: true });
      if (seq !== centerRequestSeq || !pageActive.value) return;
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

async function waitForModelReady(modelName, shouldContinue) {
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
    // 进度详情由居中加载卡片 / 切换气泡展示，这里不再把进度文案塞进 loadError
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
    const list = await modelRuntime.fetchModels(datebase.value.scheme);
    if (seq !== modelRequestSeq) return modelList.value;
    modelList.value = list;
    if (datebase.value.model && !list.some((item) => item.name === datebase.value.model)) {
      const missingModel = datebase.value.model;
      setActiveModel("");
      loadError.value = `原选择模型不存在或已被移除：${missingModel}`;
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
    throw error;
  } finally {
    if (seq === modelRequestSeq && !silent) {
      isLoadingModels.value = false;
    }
  }
}

// 人口分布监测：三种口径由左侧次级导航切换，右侧面板只展示当前口径。
const POPULATION_SECTIONS = [
  { key: "resident", label: "常住人口分布" },
  { key: "home", label: "通勤人口居住地分布" },
  { key: "work", label: "通勤人口就业地分布" },
];

// 公交出行监测：出行分布 / 客流流向，后续子模块在此追加
const BUS_TRAVEL_SECTIONS = [
  { key: "出行分布监测", label: "出行分布" },
  { key: "公交OD监测", label: "客流流向" },
];

// 客流走廊监测：供给侧走廊诊断模块（线路重复系数 / 公交客流走廊），后续子模块在此追加
const CORRIDOR_SECTIONS = [
  { key: "线路重复系数", label: "线路重复系数" },
  { key: "公交客流走廊", label: "公交客流走廊" },
];

const runMonitorMenuItems = [
  {
    key: "人口分布监测",
    label: "人口分布监测",
    icon: `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="9" cy="8" r="3.2"></circle><path d="M3.5 20c0-3 2.4-5 5.5-5s5.5 2 5.5 5"></path><circle cx="17" cy="10" r="2.4"></circle><path d="M15.4 20c.2-2.4 1.8-4 3.8-4 1 0 1.9.3 2.6 1"></path></svg>`,
    children: POPULATION_SECTIONS,
  },
  {
    key: "公交出行监测",
    label: "公交出行监测",
    icon: `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="9" cy="8" r="3.2"></circle><path d="M3.5 20c0-3 2.4-5 5.5-5s5.5 2 5.5 5"></path><circle cx="17" cy="10" r="2.4"></circle><path d="M15.4 20c.2-2.4 1.8-4 3.8-4 1 0 1.9.3 2.6 1"></path></svg>`,
    children: BUS_TRAVEL_SECTIONS,
  },
  {
    key: "总体客流监测",
    label: "总体客流监测",
    icon: `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 3v18h18"></path><path d="m7 15 4-4 3 3 5-7"></path></svg>`,
  },
  {
    key: "客流走廊监测",
    label: "客流走廊监测",
    icon: `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 20 10 4"></path><path d="M10.5 20 14.5 4"></path><path d="M15 20 21 4"></path></svg>`,
    children: CORRIDOR_SECTIONS,
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

const activeTab = ref(props.mode === "pfa" ? "线路客流监测" : "人口分布监测");
const populationSection = ref(POPULATION_SECTIONS[0].key);
// 公交出行监测的活动子模块
const busTravelSection = ref(BUS_TRAVEL_SECTIONS[0].key);
// 客流走廊监测的活动子模块（当前仅线路重复系数）
const corridorSection = ref(CORRIDOR_SECTIONS[0].key);
const isRunMonitorLeftCollapsed = ref(false);

const RUN_MONITOR_ONBOARDING_STORAGE_KEY = "gjcxfzksh:run-monitor-onboarding:v1";
const RUN_MONITOR_ONBOARDING_RESTART_EVENT = "run-monitor:onboarding:restart";
const runMonitorOnboardingActive = ref(false);
const runMonitorOnboardingPreferenceVisible = ref(false);
const runMonitorOnboardingShownThisVisit = ref(false);
const runMonitorOnboardingManualPending = ref(false);
const runMonitorOnboardingHostMounted = ref(false);

const runMonitorOnboardingSteps = [
  {
    id: "model",
    title: "先确定监测对象",
    description: "选择数据源、方案和已就绪模型。切换模型后，地图、指标与各监测模块会同步更新。",
    target: '[data-tour="model-selector"]',
    placement: "bottom",
    padding: 8,
  },
  {
    id: "modules",
    title: "八类监测模块",
    description: "人口分布独立展示常住与通勤人口空间特征；公交出行、总体客流、走廊、线路、站点用于定位问题；车辆监测回放运行过程，体检评估综合判断服务表现。",
    target: '[data-tour="module-navigation"]',
    placement: "right",
    padding: 10,
  },
  {
    id: "overview",
    title: "建立全局运行态势",
    description: "地图呈现客流空间分布，右侧汇总全日总量、方式构成、小时变化和高峰时段。面板可折叠，方便扩大地图视野。",
    target: '[data-tour="insight-panel"]',
    placement: "left",
    padding: 9,
  },
  {
    id: "line-station",
    title: "定位线路或站点",
    description: "在线路、站点模块中输入名称筛选，也可直接点击地图要素。选中后，地图高亮目标，右侧切换为对应客流详情。",
    target: '[data-tour="object-search"]',
    placement: "bottom",
    padding: 8,
  },
  {
    id: "map-controls",
    title: "调整地图观察方式",
    description: "缩放、3D与指北针为一组；行政区限定显示范围；设置中可切换公交、地铁或路网，并调整图层表现。",
    target: '[data-tour="map-controls"]',
    placement: "top",
    padding: 9,
  },
  {
    id: "vehicle",
    title: "回放车辆运行过程",
    description: "使用播放、重置、倍速和时间轴观察车辆随时刻变化；右侧同步显示在途车辆、客流与平均速度。",
    target: '[data-tour="vehicle-playback"]',
    fallbackTarget: '[data-tour-module="车辆运行监测"]',
    placement: "top",
    padding: 10,
  },
  {
    id: "health",
    title: "完成综合体检评估",
    description: "最后查看评估指标与五维雷达图，从效率、可靠性和服务质量等维度形成整体判断。之后可从顶栏帮助菜单随时重看本引导。",
    target: '[data-tour="insight-panel"]',
    placement: "left",
    padding: 9,
  },
];

function readRunMonitorOnboardingPreference() {
  try {
    return window.localStorage?.getItem(RUN_MONITOR_ONBOARDING_STORAGE_KEY) || "";
  } catch (error) {
    return "";
  }
}

function startRunMonitorOnboarding({ manual = false } = {}) {
  if (!isSimulationMode.value || props.mode === "pfa" || !pageActive.value) return;
  if (!isModelReady.value) {
    if (manual) {
      runMonitorOnboardingManualPending.value = true;
      ElMessage.info("模型就绪后将自动开始新手引导");
    }
    return;
  }
  if (!manual && readRunMonitorOnboardingPreference() === "never") return;
  if (!manual && runMonitorOnboardingShownThisVisit.value) return;
  runMonitorOnboardingManualPending.value = false;
  runMonitorOnboardingShownThisVisit.value = true;
  runMonitorOnboardingPreferenceVisible.value = false;
  runMonitorOnboardingActive.value = true;
}

function maybeStartRunMonitorOnboarding() {
  if (!isSimulationMode.value || !runMonitorOnboardingHostMounted.value || props.mode === "pfa" || !pageActive.value || !isModelReady.value) return;
  if (runMonitorOnboardingManualPending.value) {
    startRunMonitorOnboarding({ manual: true });
    return;
  }
  startRunMonitorOnboarding();
}

function closeRunMonitorOnboarding() {
  runMonitorOnboardingActive.value = false;
  runMonitorOnboardingPreferenceVisible.value = true;
}

function saveRunMonitorOnboardingPreference(value) {
  const preference = value === "never" ? "never" : "show";
  try {
    window.localStorage?.setItem(RUN_MONITOR_ONBOARDING_STORAGE_KEY, preference);
  } catch (error) {
    // Storage may be unavailable in privacy mode; the current visit still closes cleanly.
  }
  runMonitorOnboardingPreferenceVisible.value = false;
  ElMessage.success(preference === "never" ? "已关闭运行监测自动引导" : "下次进入仍会显示引导");
}

function handleRunMonitorOnboardingStep(step) {
  const tabByStep = {
    model: "人口分布监测",
    modules: "人口分布监测",
    overview: "总体客流监测",
    "line-station": "线路客流监测",
    "map-controls": "线路客流监测",
    vehicle: "车辆运行监测",
    health: "体检评估分析",
  };
  const nextTab = tabByStep[step?.id];
  if (nextTab === "公交出行监测") busTravelSection.value = BUS_TRAVEL_SECTIONS[0].key;
  if (nextTab && activeTab.value !== nextTab) handleSetActiveTab(nextTab);
  isRunMonitorLeftCollapsed.value = false;
  showLineWidthPopover.value = false;
  showRangePopover.value = false;
  syncPersistentRightPanel(nextTab);
}

function handleRunMonitorOnboardingRestart() {
  startRunMonitorOnboarding({ manual: true });
}

// 客流分析：父级在左侧切换，右侧由对应组件只显示当前模块
const PFA_LINE_SECTIONS = [
  { key: "segments", label: "断面客流" },
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
// 页签语义变更时作废旧页签发起的搜索/点选回调，避免快速切换后旧要素被画回。
let featureSwitchSeq = 0;
function invalidateFeatureInteractions() {
  featureSwitchSeq += 1;
  closeLineRoutePicker();
}
const runMonitorExpandedKeys = runMonitorMenuItems
  .filter((item) => item.children?.length)
  .map((item) => item.key);
const pfaExpandedKeys = ref(
  props.mode === "pfa"
    ? ["线路客流监测", "站点客流监测"]
    : [...runMonitorExpandedKeys],
);
provide("pfaLineSection", pfaLineSection);
provide("pfaStationSection", pfaStationSection);

// 运行监测 ↔ 客流分析 复用同一实例：mode 翻转时回到该模式的默认页签，
// 并清掉跨模式语义不同的线路/站点选中（面板结构不同，保留会串页签状态）
watch(
  () => props.mode,
  (mode) => {
    invalidateFeatureInteractions();
    activeTab.value = mode === "pfa" ? "线路客流监测" : "人口分布监测";
    if (mode !== "pfa") populationSection.value = POPULATION_SECTIONS[0].key;
    if (mode !== "pfa") busTravelSection.value = BUS_TRAVEL_SECTIONS[0].key;
    pfaExpandedKeys.value = mode === "pfa"
      ? ["线路客流监测", "站点客流监测"]
      : [...runMonitorExpandedKeys];
    clearLineSelection();
    clearStationSelection();
  },
);

// 侧栏导航：运行监测为四项；客流分析为线路/站点两组模块
const displayMenuItems = computed(() => {
  if (props.mode === "pfa") {
    // 按 key 取项（勿用下标：运行监测侧新增/调序模块不应影响客流分析菜单）
    const lineItem = runMonitorMenuItems.find((item) => item.key === "线路客流监测");
    const stationItem = runMonitorMenuItems.find((item) => item.key === "站点客流监测");
    const items = [
      { ...lineItem, label: "线路客流分析", children: PFA_LINE_SECTIONS },
      { ...stationItem, label: "站点客流分析", children: PFA_STATION_SECTIONS },
    ];
    if (isSimulationMode.value) return items;
    const childCapability = {
      "线路客流监测::segments": "线路客流监测-断面客流",
      "线路客流监测::boarding": "线路客流监测-站点乘降",
      "线路客流监测::demographics": "线路客流监测-客流画像",
      "线路客流监测::transfer": "线路客流监测-关联线路",
      "站点客流监测::boarding": "站点客流监测-站点乘降",
      "站点客流监测::od": "站点客流监测-客流OD",
      "站点客流监测::demographics": "站点客流监测-客流画像",
      "站点客流监测::reachability": "站点客流监测-可达性",
    };
    return items.map((item) => ({
      ...item,
      children: item.children.filter((child) => realCapabilityAvailable(
        "客流分析",
        childCapability[`${item.key}::${child.key}`],
      )),
    })).filter((item) => item.children.length);
  }
  if (isSimulationMode.value) return runMonitorMenuItems;
  const childCapability = {
    "人口分布监测::resident": "公交出行监测-人口分布监测",
    "人口分布监测::home": "公交出行监测-人口分布监测",
    "人口分布监测::work": "公交出行监测-人口分布监测",
    "公交出行监测::出行分布监测": "公交出行监测-站点OD监测",
    "公交出行监测::公交OD监测": "公交出行监测-公交OD监测",
    "客流走廊监测::线路重复系数": "客流走廊监测-线路重复系数",
    "客流走廊监测::公交客流走廊": "客流走廊监测-公交客流走廊",
  };
  return runMonitorMenuItems.map((item) => {
    if (item.children) {
      return {
        ...item,
        children: item.children.filter((child) => realCapabilityAvailable(
          "运行监测",
          childCapability[`${item.key}::${child.key}`],
        )),
      };
    }
    return realCapabilityAvailable("运行监测", item.key) ? item : null;
  }).filter((item) => item && (!item.children || item.children.length));
});


function realCapabilityAvailable(platform, panel) {
  if (!panel) return false;
  const modules = realDataCapabilities.value?.modules;
  if (!Array.isArray(modules)) return true;
  const match = modules.find((item) => item?.platformModule === platform && item?.leftPanelModule === panel);
  return Boolean(match?.available);
}

function syncActiveMenuWithAvailability() {
  const items = displayMenuItems.value;
  if (!items.length) return;
  let active = items.find((item) => item.key === activeTab.value);
  if (!active) {
    active = items[0];
    activeTab.value = active.key;
  }
  if (active.key === "公交出行监测" && active.children?.length
      && !active.children.some((item) => item.key === busTravelSection.value)) {
    busTravelSection.value = active.children[0].key;
  }
  if (active.key === "人口分布监测" && active.children?.length
      && !active.children.some((item) => item.key === populationSection.value)) {
    populationSection.value = active.children[0].key;
  }
  if (active.key === "客流走廊监测" && active.children?.length
      && !active.children.some((item) => item.key === corridorSection.value)) {
    corridorSection.value = active.children[0].key;
  }
  if (props.mode === "pfa" && active.key === "线路客流监测" && active.children?.length
      && !active.children.some((item) => item.key === pfaLineSection.value)) {
    pfaLineSection.value = active.children[0].key;
  }
  if (props.mode === "pfa" && active.key === "站点客流监测" && active.children?.length
      && !active.children.some((item) => item.key === pfaStationSection.value)) {
    pfaStationSection.value = active.children[0].key;
  }
}

watch(displayMenuItems, () => syncActiveMenuWithAvailability(), { flush: "post" });

const pfaIsExpanded = (key) => pfaExpandedKeys.value.includes(key);
const isNavItemActive = (item) => {
  if (props.mode === "pfa" && item?.children) return false;
  return activeTab.value === item?.key;
};
const isPfaSubActive = (itemKey, subKey) => {
  if (activeTab.value !== itemKey) return false;
  if (itemKey === "人口分布监测") return populationSection.value === subKey;
  if (itemKey === "公交出行监测") return busTravelSection.value === subKey;
  if (itemKey === "客流走廊监测") return corridorSection.value === subKey;
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
  closeLineRoutePicker();
  if (item.key === "人口分布监测") {
    populationSection.value = sub.key;
  } else if (item.key === "公交出行监测") {
    busTravelSection.value = sub.key;
  } else if (item.key === "客流走廊监测") {
    corridorSection.value = sub.key;
  } else if (item.key === "线路客流监测") {
    pfaLineSection.value = sub.key;
  } else if (item.key === "站点客流监测") {
    pfaStationSection.value = sub.key;
  }
  if (activeTab.value !== item.key) handleSetActiveTab(item.key);
  nextTick(() => {
    syncBaseMapLayerVisibilityNow();
    scheduleLayerSyncBurst(2);
  });
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
// 地铁线网模式只搜地铁线路/站点，公交线网模式只搜公交（选项携带 mode 字段）
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
  const switchSeq = featureSwitchSeq;
  const searchType = runMonitorSearchType.value;
  runMonitorSearchKeyword.value = result.label;
  isSearchFocused.value = false;
  closeLineRoutePicker();
  if (searchType === "line") {
    let feature = modelLineFeatureByName(result.value);
    if (!feature) {
      await loadBusNetwork();
      if (switchSeq !== featureSwitchSeq || runMonitorSearchType.value !== searchType) return;
      feature = modelLineFeatureByName(result.value);
    }
    if (feature) {
      await selectLineFromBusNetwork(feature);
    } else {
      selectedLineKey.value = "";
      lineMonitorRef.value?.selectLineByName?.(result.value);
    }
  } else if (searchType === "station") {
    let feature = modelStationFeatureByName(result.value);
    if (!feature) {
      await loadBusNetwork();
      if (switchSeq !== featureSwitchSeq || runMonitorSearchType.value !== searchType) return;
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
  if (activeTab.value !== tabName) invalidateFeatureInteractions();
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
    "人口分布监测",
    "公交出行监测",
    "总体客流监测",
    "客流走廊监测",
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
    // 立即作废在途线网请求；否则慢请求会在新功能上重建 rm-* 图层。
    busNetworkRequestSeq += 1;
    busNetworkLoading.value = false;
    pauseTransitNetworkTiles();
  }
  syncBaseMapLayerVisibilityNow();
  scheduleLayerSyncBurst(4);
  observeLeftPanelSize();
  nextTick(setMapCenter);
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
const vehicleVisibilityMode = ref(["bus", "subway", "car"]);
const vehicleVisibilityOptions = [
  { label: "公交", value: "bus" },
  { label: "地铁", value: "subway" },
  { label: "私家车", value: "car" },
];

const isRightPanelVisible = computed(() => showRightPanel.value && rightPanelHasContent.value && effectiveTab.value !== "体检评估分析");
const isInfoActive = computed(() => isRightPanelVisible.value);
const is3DActive = ref(false);

function toggleRightPanel() {
  isRightCollapsed.value = !isRightCollapsed.value;
}

const showLineWidthPopover = ref(false);
const SIMULATION_TABS_WITHOUT_SETTINGS = new Set([
  "公交出行监测",
  "客流走廊监测",
]);
const showSettingsControl = computed(
  () => !isSimulationMode.value || !SIMULATION_TABS_WITHOUT_SETTINGS.has(effectiveTab.value),
);
watch(showSettingsControl, (visible) => {
  if (!visible) showLineWidthPopover.value = false;
});
const lineWidth = ref(1.2);
const stationSize = ref(32);
const vehicleSize = ref(36);
// 路段公交车速（拥堵路况）图层：需求为可开关且默认关闭，透明度百分比可调；
// 状态由 GJYS 回报（idle/loading/generating/ready/empty/error），驱动左下角图例显隐
const linkSpeedEnabled = ref(false);
const linkSpeedOpacity = ref(85);
const linkSpeedStatus = ref("idle");
const referenceZoom = ref(10.74);
let isZoomCaptured = false;
const baseMapLineMode = ref("bus-network");
provide("BaseMapLineModeRef", baseMapLineMode);

const DISPLAY_AREA_NAME = "广州市";
const DISPLAY_RANGE_ALL = "全市";
const showRangePopover = ref(false);
// 行政区选区跨模块联动（数据管理/运行监测/客流分析/换乘分析共用 displayRange store）；
// 可写 computed 保持原 selectedDisplayRange 的读写语义，watch/模板零改动
const displayRangeStore = useDisplayRangeStore();
const selectedDisplayRange = computed({
  get: () => displayRangeStore.selected,
  set: (value) => displayRangeStore.set(value),
});
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
let busNetworkRawModel = "";
const busNetworkRevision = ref(0);

// ===== 行政区计算 Worker：全网求交与线要素裁剪下沉后台线程 =====
// 原先 displayRangeSelection 是同步 computed，选区瞬间对全部线路×逐 link 做线段-多边形求交，
// 主线程冻结数百 ms~秒级。现改为：全网坐标在模型加载后的空闲期打包传入 Worker 常驻，
// 选区时仅发送 district 上下文，结果按 (model, district, revision) 记忆化。
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
  if (displayRangeWorkerBroken || displayRangeWorkerDisposed) {
    throw new Error("display range worker unavailable");
  }
  if (displayRangeWorker) return displayRangeWorker;
  try {
    displayRangeWorker = new Worker(new URL("./displayRange.worker.js", import.meta.url), { type: "module" });
    // 新 Worker 端无任何缓存，已发送记录随之作废
    displayRangeContextSentKeys.clear();
  } catch (error) {
    displayRangeWorkerBroken = true;
    throw new Error("display range worker initialization failed", { cause: error });
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
async function warmDisplayRangeWorker(modelName) {
  if (displayRangeWorkerBroken) throw new Error("display range worker unavailable");
  const networkState = displayRangeNetworkState(modelName, busNetworkRawModel, busNetworkRawLines);
  if (networkState === "pending") return false;
  if (networkState === "empty") throw new Error("当前数据源没有可用于行政区筛选的线路");
  const sentKey = `${modelName}::${busNetworkRevision.value}`;
  if (displayRangeNetworkSentKey === sentKey) return true;
  const payload = packDisplayRangeNetwork(modelName);
  await Promise.all([
    postDisplayRangeWorker(payload, [payload.segBuf, payload.routeFacBuf, payload.stationBuf]),
    postDisplayRangeWorker({
      type: "setLines",
      model: modelName,
      linesRev: busNetworkRevision.value,
      collection: busNetworkCollections.lines,
    }),
  ]);
  displayRangeNetworkSentKey = sentKey;
  return true;
}

const displayRangeSelection = shallowRef(null);
const displayRangeSelectionMemo = new Map(); // `${model}::${district}::${rev}` -> { routeIds, lineNames, stationNames }
let displayRangeQueryToken = 0;
let displayRangeLoadError = "";

function reportDisplayRangeError(error, fallback) {
  displayRangeLoadError = error?.message || fallback;
  loadError.value = displayRangeLoadError;
}

function clearDisplayRangeError() {
  if (displayRangeLoadError && loadError.value === displayRangeLoadError) loadError.value = "";
  displayRangeLoadError = "";
}

watch(
  [activeDisplayRangeContext, busNetworkRevision, () => selectModel.value?.name],
  ([context, , selectedModelName]) => {
    if (!context) {
      displayRangeQueryToken += 1;
      displayRangeSelection.value = null;
      clearDisplayRangeError();
      return;
    }
    const modelName = selectedModelName || "";
    const networkState = displayRangeNetworkState(modelName, busNetworkRawModel, busNetworkRawLines);
    if (networkState === "pending") {
      // 刷新/切源时线网仍在请求中；busNetworkRevision 会在数据到达后自动重试。
      displayRangeQueryToken += 1;
      displayRangeSelection.value = null;
      clearDisplayRangeError();
      return;
    }
    if (networkState === "empty") {
      displayRangeQueryToken += 1;
      displayRangeSelection.value = {
        routeIds: new Set(),
        lineNames: new Set(),
        stationNames: new Set(),
      };
      reportDisplayRangeError(null, "当前数据源没有可用于行政区筛选的线路");
      return;
    }
    const memoKey = `${modelName}::${context.name}::${busNetworkRevision.value}`;
    const cached = displayRangeSelectionMemo.get(memoKey);
    if (cached) {
      displayRangeSelection.value = cached;
      clearDisplayRangeError();
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
      clearDisplayRangeError();
    };
    void warmDisplayRangeWorker(modelName)
      .then(() => postDisplayRangeWorker({
        type: "query",
        seq: ++displayRangeMsgSeq,
        model: modelName,
        ...displayRangeContextPayload(context),
      }))
      .then((msg) => {
        if (token !== displayRangeQueryToken) return;
        if (!msg?.ok) throw new Error("display range worker has no network yet");
        remember({
          routeIds: new Set(msg.routeIds),
          lineNames: new Set(msg.lineNames),
          stationNames: new Set(msg.stationNames),
        });
      })
      .catch((error) => {
        if (token !== displayRangeQueryToken) return;
        displayRangeSelection.value = null;
        reportDisplayRangeError(error, "行政区线网筛选失败");
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

const overallFlowLoading = ref(false);
const overallFlowError = ref("");
// 后端缓存仍在生成：独立的终态，不再让"加载中"无限转下去
const overallFlowGenerating = ref(false);
function emptyHourlyFlow() {
  return Array.from({ length: 24 }, () => 0);
}
function hourlyIntervalLabel(hour) {
  const start = Math.max(0, Math.min(23, Number(hour) || 0));
  return `${start}:00-${start + 1}:00`;
}
function hourlyAxisLabelStyle(fullscreen = false) {
  return {
    color: chartInk.value.text,
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
const overallFlowDailyFlow = ref([]);
const overallDailyStart = ref("");
const overallDailyEnd = ref("");
// 公交运营效率分母（车辆数/日班次/日运营车公里）：无筛选走轻量接口由后端聚合，
// 有行政区筛选时从整包 routePanel 本地聚合，两条路径口径一致（Σ各方向计数，仅常规公交）
const overallFlowBusOps = ref(null);
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

// 右侧面板的方式色块与折线图 series 用同一组颜色，面板即图例
const OVERALL_FLOW_MODE_COLORS = { bus: "#0071e3", metro: "#16a34a" };

function formatFlowNumber(value) {
  const number = Number(value);
  return Number.isFinite(number) ? Math.round(number).toLocaleString("zh-CN") : "暂无";
}

const overallFlowHasData = computed(() => overallFlowTotal.value > 0);
const overallFlowSkeletonVisible = computed(() => overallFlowLoading.value && !overallFlowHasData.value);
const overallFlowGeneratingVisible = computed(() => overallFlowGenerating.value && !overallFlowHasData.value);
// 有旧数据时的静默刷新/缓存生成，只在标题栏挂一个轻提示
const overallFlowRefreshing = computed(() =>
  (overallFlowLoading.value || overallFlowGenerating.value) && overallFlowHasData.value
);
const overallFlowModes = computed(() => {
  const total = overallFlowTotal.value;
  const modes = [
    { key: "bus", label: "公交客流", value: overallFlowBusTotal.value },
    { key: "metro", label: "地铁客流", value: overallFlowMetroTotal.value },
  ];
  return modes.filter((mode) => isSimulationMode.value || mode.key !== "metro").map((mode) => {
    const sharePercent = total > 0 ? (mode.value / total) * 100 : 0;
    return {
      ...mode,
      color: OVERALL_FLOW_MODE_COLORS[mode.key],
      shareText: `${sharePercent.toFixed(1)}%`,
    };
  });
});

// 公交运营效率三指标：车均日载客量 / 单班次载客量 / 客流强度（人次/车公里）。
// 仅统计常规公交（轨道车辆班次与公交不可比）；车辆数/班次与线路卡片的
// 「车辆数/日发车班次」同源；强度分母为日运营车公里 Σ(路径长度×班次)。
const overallBusOpsStats = computed(() => {
  const ops = overallFlowBusOps.value || {};
  const busTotal = overallFlowBusTotal.value;
  const vehicles = Number(ops.vehicles) || 0;
  const departures = Number(ops.departures) || 0;
  const operatedKm = Number(ops.operatedKm) || 0;
  const { perVehicle, perTrip, intensity } = busOperationRatios(busTotal, ops);
  return [
    {
      label: "车均日载客量",
      ...formatLineRatioStat(perVehicle, "人次/车·日"),
      title: `公交日客运量 ÷ 车辆数（${formatFlowNumber(vehicles)} 辆）`,
    },
    {
      label: "单班次载客量",
      ...formatLineRatioStat(perTrip, "人次/班"),
      title: `公交日客运量 ÷ 日发车班次（${formatFlowNumber(departures)} 班）`,
    },
    {
      label: "客流强度",
      ...formatLineRatioStat(intensity, "人次/车公里"),
      title: `公交日客运量 ÷ 日运营车公里（当前 ${formatFlowNumber(operatedKm)} 车公里）`,
    },
  ];
});

const overallOpsRow = computed(() => {
  const ops = overallFlowBusOps.value || {};
  const busTotal = overallFlowBusTotal.value;
  const { perVehicle, perTrip, intensity } = busOperationRatios(busTotal, ops);
  return {
    name: "总体",
    perVehicle: formatLineRatioStat(perVehicle, "人次/车·日"),
    perTrip: formatLineRatioStat(perTrip, "人次/班"),
    intensity: formatLineRatioStat(intensity, "人次/车公里"),
  };
});

// 分企业与总体指标共用 busOperationRatios，不直接采信数据源中的预计算比值，
// 防止真实模式与仿真模式在接口层各自演化出不同公式。
const operatorOpsRows = computed(() => (overallFlowBusOps.value?.operators || []).map((item) => {
  const ratios = busOperationRatios(item?.passenger, item);
  return {
    name: String(item?.name || "未知企业"),
    perVehicle: formatLineRatioStat(ratios.perVehicle, "人次/车·日"),
    perTrip: formatLineRatioStat(ratios.perTrip, "人次/班"),
    intensity: formatLineRatioStat(ratios.intensity, "人次/车公里"),
  };
}));

const filteredOverallDailyFlow = computed(() => {
  const start = overallDailyStart.value;
  const end = overallDailyEnd.value;
  return overallFlowDailyFlow.value.filter((item) => (!start || item.date >= start) && (!end || item.date <= end));
});

const overallDailyMin = computed(() => overallFlowDailyFlow.value[0]?.date || "");
const overallDailyMax = computed(() => overallFlowDailyFlow.value[overallFlowDailyFlow.value.length - 1]?.date || "");

watch(overallFlowDailyFlow, (rows) => {
  if (!rows.length) {
    overallDailyStart.value = "";
    overallDailyEnd.value = "";
    return;
  }
  const first = rows[0].date;
  const last = rows[rows.length - 1].date;
  if (!overallDailyStart.value || overallDailyStart.value < first || overallDailyStart.value > last) overallDailyStart.value = first;
  if (!overallDailyEnd.value || overallDailyEnd.value < first || overallDailyEnd.value > last) overallDailyEnd.value = last;
}, { immediate: true });
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
  const ink = chartInk.value;
  const hours = hourly.map((_, index) => hourlyIntervalLabel(index));
  const LinearGradient = graphic.LinearGradient;
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
      backgroundColor: ink.tooltipBg,
      borderColor: ink.tooltipBorder,
      borderWidth: 1,
      textStyle: { color: ink.tooltipText, fontSize: 12 },
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
      axisLine: { lineStyle: { color: ink.axisLine } },
      axisLabel: hourlyAxisLabelStyle(),
    },
    yAxis: {
      type: "value",
      name: "人次",
      nameTextStyle: { color: ink.textSoft, fontSize: 10, padding: [0, 8, 0, 0] },
      splitLine: { lineStyle: { color: ink.splitLine, type: "dashed" } },
      axisLabel: { color: ink.text, fontSize: 10 },
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
  const ink = chartInk.value;
  const fullscreen = opt.fullscreen === true;
  const includeMetro = opt.includeMetro !== false;
  const bus = Array.isArray(byMode.bus) ? byMode.bus : emptyHourlyFlow();
  const metro = Array.isArray(byMode.metro) ? byMode.metro : emptyHourlyFlow();
  const hours = emptyHourlyFlow().map((_, index) => hourlyIntervalLabel(index));
  return {
    backgroundColor: "transparent",
    color: [OVERALL_FLOW_MODE_COLORS.bus, OVERALL_FLOW_MODE_COLORS.metro],
    tooltip: {
      trigger: "axis",
      appendToBody: true,
      backgroundColor: ink.tooltipBg,
      borderColor: ink.tooltipBorder,
      borderWidth: 1,
      textStyle: { color: ink.tooltipText, fontSize: 12 },
      axisPointer: { type: "line", lineStyle: { color: ink.axisPointer, width: 1 } },
      formatter(params = []) {
        if (!params.length) return "";
        const rows = params.map((item) =>
          `${item.marker}${item.seriesName}：${Number(item.value || 0).toLocaleString("zh-CN")} 人次`
        );
        const total = params.reduce((sum, item) => sum + (Number(item.value) || 0), 0);
        rows.push(`合计：${total.toLocaleString("zh-CN")} 人次`);
        return `<strong>${params[0].name}</strong><br/>${rows.join("<br/>")}`;
      },
    },
    // 紧凑视图的图例由右侧面板的方式构成承担，图内不再重复，把高度还给曲线
    legend: {
      show: fullscreen,
      top: 0,
      right: 8,
      itemWidth: 12,
      itemHeight: 8,
      textStyle: { color: ink.text, fontSize: 13 },
      data: includeMetro ? ["公交客流", "地铁客流"] : ["公交客流"],
    },
    grid: fullscreen
      ? { top: 74, right: 46, bottom: 76, left: 58, containLabel: true }
      : { top: 14, right: 14, bottom: 26, left: 12, containLabel: true },
    xAxis: {
      type: "category",
      data: hours,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: ink.axisLine } },
      axisLabel: hourlyAxisLabelStyle(fullscreen),
    },
    yAxis: {
      type: "value",
      name: "人次",
      nameTextStyle: { color: ink.textSoft, fontSize: fullscreen ? 12 : 10, padding: [0, 8, 0, 0] },
      splitLine: { lineStyle: { color: ink.splitLine, type: "dashed" } },
      axisLabel: { color: ink.text, fontSize: fullscreen ? 12 : 10 },
    },
    series: [
      {
        name: "公交客流",
        type: "line",
        smooth: 0.35,
        showSymbol: fullscreen,
        symbolSize: fullscreen ? 7 : 5,
        data: bus,
        itemStyle: { color: OVERALL_FLOW_MODE_COLORS.bus },
        lineStyle: { width: fullscreen ? 4 : 2.6, color: OVERALL_FLOW_MODE_COLORS.bus },
        emphasis: { focus: "series" },
      },
      ...(includeMetro ? [{
        name: "地铁客流",
        type: "line",
        smooth: 0.35,
        showSymbol: fullscreen,
        symbolSize: fullscreen ? 7 : 5,
        data: metro,
        itemStyle: { color: OVERALL_FLOW_MODE_COLORS.metro },
        lineStyle: { width: fullscreen ? 4 : 2.6, color: OVERALL_FLOW_MODE_COLORS.metro },
        emphasis: { focus: "series" },
      }] : []),
    ],
  };
}
const overallFlowChartOption = computed(() => buildOverallFlowChartOption(overallFlowHourlyByMode.value, { includeMetro: isSimulationMode.value }));
const overallFlowFullscreenChartOption = computed(() => buildOverallFlowChartOption(overallFlowHourlyByMode.value, { fullscreen: true, includeMetro: isSimulationMode.value }));

const overallDailyChartOption = computed(() => {
  const ink = chartInk.value;
  return {
    backgroundColor: "transparent",
    tooltip: {
      trigger: "axis",
      appendToBody: true,
      backgroundColor: ink.tooltipBg,
      borderColor: ink.tooltipBorder,
      textStyle: { color: ink.tooltipText, fontSize: 12 },
      formatter(params = []) {
        const item = params[0];
        return item ? `<strong>${item.name}</strong><br/>总客流：${formatFlowNumber(item.value)} 人次` : "";
      },
    },
    grid: { top: 18, right: 14, bottom: 38, left: 12, containLabel: true },
    xAxis: {
      type: "category",
      data: filteredOverallDailyFlow.value.map((item) => item.date),
      axisTick: { show: false },
      axisLine: { lineStyle: { color: ink.axisLine } },
      axisLabel: { color: ink.text, fontSize: 10, rotate: filteredOverallDailyFlow.value.length > 12 ? 35 : 0 },
    },
    yAxis: {
      type: "value",
      name: "人次",
      nameTextStyle: { color: ink.textSoft, fontSize: 10 },
      splitLine: { lineStyle: { color: ink.splitLine, type: "dashed" } },
      axisLabel: { color: ink.text, fontSize: 10 },
    },
    series: [{
      name: "日总客流",
      type: "line",
      smooth: 0.2,
      showSymbol: filteredOverallDailyFlow.value.length <= 20,
      symbolSize: 6,
      data: filteredOverallDailyFlow.value.map((item) => item.flow),
      itemStyle: { color: "#0f8b6d" },
      lineStyle: { width: 2.8, color: "#0f8b6d" },
      areaStyle: { color: "rgba(15, 139, 109, 0.10)" },
    }],
  };
});
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
const selectedLinePanelIsGroup = computed(() => Boolean(selectedLinePanel.value?.lineGroup));
const reverseLineFlowHourly = computed(() => {
  if (selectedLinePanelIsGroup.value) return Array.from({ length: 24 }, () => 0);
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
  if (selectedLinePanelIsGroup.value) return 0;
  const metricTotal = Number(selectedReverseLinePanel.value?.metrics?.passenger);
  if (Number.isFinite(metricTotal) && metricTotal > 0) return metricTotal;
  return reverseLineFlowHourly.value.reduce((sum, value) => sum + value, 0);
});
function positiveMetric(panel, key) {
  const value = Number(panel?.metrics?.[key]);
  return Number.isFinite(value) && value > 0 ? value : 0;
}

function combinedMetric(key) {
  if (selectedLinePanel.value?.lineGroup) return positiveMetric(selectedLinePanel.value, key);
  return positiveMetric(selectedLinePanel.value, key) + positiveMetric(selectedReverseLinePanel.value, key);
}

// 班次/车辆是整数计数；单班次、车日均是比值 —— 比值取整会把 2.09 和 1.6 都压成同一个「2」，
// 小于 10 时保留一位小数才看得出运力差异
function formatLineCountStat(value, unit) {
  const number = Number(value);
  if (!Number.isFinite(number) || number <= 0) return { value: "--", unit: "" };
  return { value: Math.round(number).toLocaleString("zh-CN"), unit };
}

function formatLineRatioStat(value, unit) {
  const number = Number(value);
  if (!Number.isFinite(number) || number <= 0) return { value: "--", unit: "" };
  const text = number < 10 ? number.toFixed(1) : Math.round(number).toLocaleString("zh-CN");
  return { value: text, unit };
}

const lineFlowTotal = computed(() => lineFlowPrimaryTotal.value + lineFlowReverseTotal.value);

// 平均高峰满载率保留 1 位小数（与排名榜口径一致）
function formatLinePercentStat(value) {
  const number = Number(value);
  if (!Number.isFinite(number) || number <= 0) return { value: "--", unit: "" };
  return { value: number.toFixed(1), unit: "%" };
}

// 真实数据以线路 SHP 的 company 字段为准；仿真旧缓存没有企业字段时保留“-”。
const selectedLineCompany = computed(() => String(
  selectedLinePanel.value?.operator
    || selectedLinePanel.value?.metrics?.company
    || selectedReverseLinePanel.value?.operator
    || selectedReverseLinePanel.value?.metrics?.company
    || "-",
));

// 日客流量已升为面板主指标（hero），这里 8 项运营指标铺满 2×4，不出现空格子。
// 发车间隔为方向级口径（上下行间隔合并求均无意义），取选中方向、缺失时退对向；
// 客流强度/平均高峰满载率与未选中态排名榜同一算法
// （合计客流÷日运营车公里、各高峰班次最大站段满载率的班次均值）
const lineOperationStats = computed(() => {
  const passenger = lineFlowTotal.value;
  const departures = combinedMetric("departures");
  const selectedVehicleIds = new Set(
    [selectedLinePanel.value, selectedReverseLinePanel.value]
      .flatMap((panel) => Array.isArray(panel?.metrics?.vehicleIds) ? panel.metrics.vehicleIds : [])
      .map((id) => String(id || "")).filter(Boolean),
  );
  const declaredVehicles = combinedMetric("vehicles");
  if (declaredVehicles > 0 && selectedVehicleIds.size === 0) {
    throw new Error("线路运营指标声明了车辆数但缺少 vehicleIds");
  }
  const vehicles = selectedVehicleIds.size;
  const perTrip = departures > 0 ? passenger / departures : 0;
  const perVehicle = vehicles > 0 ? passenger / vehicles : 0;
  const peakHeadway = positiveMetric(selectedLinePanel.value, "peakHeadwayMin");
  const offPeakHeadway = positiveMetric(selectedLinePanel.value, "offPeakHeadwayMin");
  const routePanels = selectedLinePanelIsGroup.value
    ? [selectedLinePanel.value]
    : [selectedLinePanel.value, selectedReverseLinePanel.value].filter(Boolean);
  const weightedRouteDist = routePanels.reduce((sum, panel) =>
    sum + positiveMetric(panel, "routeDist") * positiveMetric(panel, "departures"), 0);
  const strength = weightedRouteDist > 0 ? passenger / (weightedRouteDist / 1000) : 0;
  const peakSamples = routePanels.reduce(
    (sum, panel) => sum + positiveMetric(panel, "peakDepartureSamples"), 0);
  const peakRateWeighted = routePanels.reduce(
    (sum, panel) => sum
      + positiveMetric(panel, "peakAverageLoadRate")
      * positiveMetric(panel, "peakDepartureSamples"), 0);
  const peakMissingCapacity = routePanels.reduce(
    (sum, panel) => sum + positiveMetric(panel, "peakMissingCapacityDepartures"), 0);
  const peakLoadRate = peakMissingCapacity === 0 && peakSamples > 0
    ? peakRateWeighted / peakSamples
    : 0;
  return [
    { label: "日发车班次", ...formatLineCountStat(departures, "班") },
    { label: "车辆数", ...formatLineCountStat(vehicles, "辆") },
    { label: "高峰发车间隔", ...formatLineRatioStat(peakHeadway, "分") },
    { label: "平峰发车间隔", ...formatLineRatioStat(offPeakHeadway, "分") },
    { label: "单班次客流", ...formatLineRatioStat(perTrip, "人次/班") },
    { label: "车日均客流", ...formatLineRatioStat(perVehicle, "人次/车·日") },
    { label: "客流强度", ...formatLineRatioStat(strength, "人次/车公里·日") },
    { label: "平均高峰满载率", ...formatLinePercentStat(peakLoadRate) },
  ];
});

const lineTotalHourly = computed(() =>
  lineFlowHourly.value.map((value, index) => value + (Number(reverseLineFlowHourly.value[index]) || 0))
);

const linePeakHour = computed(() => {
  const hourly = lineTotalHourly.value;
  let peakIndex = -1;
  let peakValue = 0;
  hourly.forEach((value, index) => {
    if (value > peakValue) {
      peakValue = value;
      peakIndex = index;
    }
  });
  if (peakIndex < 0) return null;
  return { label: hourlyIntervalLabel(peakIndex), value: peakValue };
});
const selectedLineBaseName = computed(() =>
  String(selectedLineName.value || "")
    .replace(/[（(].*?[）)]/g, "")
    .trim()
);

function routeEndpoints(route = {}) {
  const facilities = Array.isArray(route?.facilities) ? route.facilities : [];
  const start = String(facilities[0]?.facilityName || "");
  const end = String(facilities[facilities.length - 1]?.facilityName || "");
  return start && end ? { start, end } : null;
}

// 面板宽度只有 366px，"甲总站-乙总站方向客流"必然折行且两行高度不齐。
// 公交语义里方向 = 去哪儿，所以行内只留终点站，完整首末站放 title 与卡片副标题
function routeDirectionLabel(route, fallback) {
  const endpoints = routeEndpoints(route);
  return endpoints ? `往${endpoints.end}` : fallback;
}

function routeDirectionFullLabel(route, fallback) {
  const endpoints = routeEndpoints(route);
  return endpoints ? `${endpoints.start} 至 ${endpoints.end}` : fallback;
}

const selectedLineIsMetro = computed(() => {
  const route = selectedRouteDetail.value || selectedLinePanel.value || {};
  return routeModeKey(route) === "metro" || /地铁|轨道|metro|subway|rail/i.test(selectedLineName.value);
});

const lineEndpointPair = computed(() => routeEndpoints(selectedRouteDetail.value));

// 方向色沿用 MAP_THEME.route（上行橙 / 下行蓝），面板与地图上的选中线路同色，一眼对得上
const lineDirectionSeries = computed(() => {
  const total = lineFlowTotal.value;
  const shareText = (value) => (total > 0 ? `${Math.round((value / total) * 100)}%` : "--");
  if (selectedLineIsMetro.value) {
    return [
      {
        key: "all",
        label: "全线客流",
        fullLabel: "全线客流",
        value: total,
        shareText: shareText(total),
        color: MAP_THEME.route.up,
      },
    ];
  }
  const directions = [
    {
      key: "up",
      label: routeDirectionLabel(selectedRouteDetail.value, "当前方向"),
      fullLabel: routeDirectionFullLabel(selectedRouteDetail.value, "当前方向"),
      value: lineFlowPrimaryTotal.value,
      shareText: shareText(lineFlowPrimaryTotal.value),
      color: MAP_THEME.route.up,
    },
  ];
  if (selectedReverseLinePanel.value || lineFlowReverseTotal.value > 0) {
    directions.push({
      key: "down",
      label: routeDirectionLabel(selectedReverseRouteDetail.value, "反方向"),
      fullLabel: routeDirectionFullLabel(selectedReverseRouteDetail.value, "反方向"),
      value: lineFlowReverseTotal.value,
      shareText: shareText(lineFlowReverseTotal.value),
      color: MAP_THEME.route.down,
    });
  }
  return directions;
});

const lineFlowChartPrimaryHourly = computed(() =>
  selectedLineIsMetro.value ? lineTotalHourly.value : lineFlowHourly.value
);
const lineFlowChartReverseHourly = computed(() => selectedLineIsMetro.value
  ? emptyHourlyFlow()
  : reverseLineFlowHourly.value
);
const lineFlowChartSeriesNames = computed(() => lineDirectionSeries.value.map((item) => item.label));

// 图例行 ⇄ 曲线的双向关联：悬停某一行时另一条淡出，两条曲线在 350px 宽的图里不再互相盖住峰值。
// 注意不能走 dispatchAction("highlight", { seriesIndex })：showSymbol 为 false 的折线没有 item 图元，
// echarts 拿不到挂 focus 的元素，highlight/blur 会静默失效（实测 currentStates 始终为空）。
// 这里改为 merge 一次 opacity —— 只动样式不动数据，由 animationDurationUpdate 负责淡入淡出。
const LINE_FLOW_AREA_OPACITY = 0.7; // 与 echarts areaStyle 默认值一致，恢复时不会把面积提亮
const LINE_FLOW_DIM_LINE_OPACITY = 0.18;
const LINE_FLOW_DIM_AREA_OPACITY = 0.05;

function dimChartSeriesExcept(chartRef, seriesCount, hoveredIndex) {
  const instance = chartRef.value?.chart;
  if (!instance || seriesCount < 2) return;
  const series = Array.from({ length: seriesCount }, (_, index) => {
    const dimmed = hoveredIndex >= 0 && index !== hoveredIndex;
    return {
      lineStyle: { opacity: dimmed ? LINE_FLOW_DIM_LINE_OPACITY : 1 },
      areaStyle: { opacity: dimmed ? LINE_FLOW_DIM_AREA_OPACITY : LINE_FLOW_AREA_OPACITY },
    };
  });
  instance.setOption({ series }, { notMerge: false, lazyUpdate: false, silent: true });
}

const lineFlowChartRef = ref(null);
function focusLineFlowSeries(seriesIndex) {
  dimChartSeriesExcept(lineFlowChartRef, lineFlowChartSeriesNames.value.length, seriesIndex);
}
function blurLineFlowSeries() {
  dimChartSeriesExcept(lineFlowChartRef, lineFlowChartSeriesNames.value.length, -1);
}

function prefersReducedMotion() {
  return typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches === true;
}

function buildDirectionalLineFlowChartOption(primaryHourly = [], reverseHourly = [], seriesNames = ["上行", "下行"]) {
  const hours = emptyHourlyFlow().map((_, index) => hourlyIntervalLabel(index));
  const primary = hours.map((_, index) => Number(primaryHourly[index]) || 0);
  const reverse = hours.map((_, index) => Number(reverseHourly[index]) || 0);
  const primaryName = seriesNames[0] || "上行";
  const reverseName = seriesNames[1] || "下行";
  const showReverse = seriesNames.length > 1;
  const primaryColor = MAP_THEME.route.up;
  const reverseColor = MAP_THEME.route.down;
  const LinearGradient = graphic.LinearGradient;
  const areaFill = (color, topAlpha, bottomAlpha) => (LinearGradient
    ? new LinearGradient(0, 0, 0, 1, [
        { offset: 0, color: hexToRgba(color, topAlpha) },
        { offset: 1, color: hexToRgba(color, bottomAlpha) },
      ])
    : hexToRgba(color, topAlpha / 2));
  const reduceMotion = prefersReducedMotion();
  const lineSeries = (name, data, color, delayOffset) => ({
    name,
    type: "line",
    smooth: 0.35,
    showSymbol: false,
    symbol: "circle",
    symbolSize: 6,
    data,
    itemStyle: { color },
    lineStyle: { width: 2.6, color, opacity: 1, shadowBlur: 8, shadowColor: hexToRgba(color, 0.24) },
    areaStyle: { color: areaFill(color, 0.26, 0.02), opacity: LINE_FLOW_AREA_OPACITY },
    animationDuration: reduceMotion ? 0 : 900,
    animationDelay(index) {
      return reduceMotion ? 0 : index * 12 + delayOffset;
    },
  });
  return {
    backgroundColor: "transparent",
    color: [primaryColor, reverseColor],
    animation: !reduceMotion,
    animationDuration: reduceMotion ? 0 : 900,
    // 方向行悬停时的淡出用的是 update 动画
    animationDurationUpdate: reduceMotion ? 0 : 300,
    animationEasing: "cubicOut",
    tooltip: {
      trigger: "axis",
      appendToBody: true,
      backgroundColor: "rgba(255, 255, 255, 0.98)",
      borderColor: "rgba(17, 32, 58, 0.1)",
      borderWidth: 1,
      padding: [8, 11],
      extraCssText: "border-radius:10px;box-shadow:0 12px 32px -14px rgba(13,38,76,0.34);",
      textStyle: { color: "#1c2024", fontSize: 12 },
      axisPointer: { type: "line", lineStyle: { color: "rgba(17, 32, 58, 0.18)", width: 1, type: "solid" } },
      formatter(params = []) {
        if (!params.length) return "";
        const rows = params.map((item) =>
          `${item.marker}${item.seriesName}：${Number(item.value || 0).toLocaleString("zh-CN")} 人次`
        );
        return `<strong>${params[0].name}</strong><br/>${rows.join("<br/>")}`;
      },
    },
    // 方向构成列表即图例，图表内不再重复画一遍（原来的两行图例会吃掉近三成绘图高度）
    // top 需给 yAxis 的"人次"轴名留位：containLabel 只算刻度标签，不算轴名
    grid: { top: 26, right: 14, bottom: 8, left: 6, containLabel: true },
    xAxis: {
      type: "category",
      data: hours,
      boundaryGap: false,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: "rgba(17, 32, 58, 0.12)" } },
      // 完整区间"12:00-13:00"太长，四个标签就撞在一起；轴上只留整点，区间留给 tooltip
      axisLabel: {
        color: "#667085",
        fontSize: 10,
        interval: 3,
        hideOverlap: true,
        margin: 10,
        formatter: (value) => `${String(value).split(":")[0]}时`,
      },
    },
    yAxis: {
      type: "value",
      name: "人次",
      minInterval: 1,
      nameTextStyle: { color: "#98a2b3", fontSize: 10, padding: [0, 8, 0, 0] },
      splitLine: { lineStyle: { color: "rgba(17, 32, 58, 0.07)", type: "dashed" } },
      axisLabel: { color: "#667085", fontSize: 10 },
    },
    series: [
      lineSeries(primaryName, primary, primaryColor, 0),
      showReverse ? lineSeries(reverseName, reverse, reverseColor, 80) : null,
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

// 站点客流监测：右侧卡片与「总体客流监测」一致 —— 站点全天上下车人数 + 上下车变化（数据由 ZDZL 上抛）
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

// 对侧站点存在与否决定图表画一条还是两条曲线。原先无论如何都画两条，
// 单侧站点会多出一条恒为 0 的直线贴在 x 轴上
const stationHasReverse = computed(() =>
  Boolean(selectedReverseStationName.value) || stationFlowReverseTotal.value > 0
);
const stationFlowTotal = computed(() => stationFlowPrimaryTotal.value + stationFlowReverseTotal.value);
const stationFlowHasData = computed(() => Boolean(selectedStationPanel.value) && stationFlowTotal.value > 0);

const stationTotalHourly = computed(() =>
  stationFlowHourly.value.map((value, index) => value + (Number(reverseStationFlowHourly.value[index]) || 0))
);

const stationPeakHour = computed(() => {
  let peakIndex = -1;
  let peakValue = 0;
  stationTotalHourly.value.forEach((value, index) => {
    if (value > peakValue) {
      peakValue = value;
      peakIndex = index;
    }
  });
  if (peakIndex < 0) return null;
  return { label: hourlyIntervalLabel(peakIndex), value: peakValue };
});

// 两侧色沿用 MAP_THEME.route（主站点橙 / 对侧蓝），与地图上两个高亮站点的圈色同源
const stationFlowLegend = computed(() => {
  const total = stationFlowTotal.value;
  const shareText = (value) => (total > 0 ? `${Math.round((value / total) * 100)}%` : "--");
  // 行内不再重复"上下车"：hero 标签已经说明了口径
  const sides = [
    {
      key: "primary",
      label: primaryStationSideLabel.value,
      value: stationFlowPrimaryTotal.value,
      shareText: shareText(stationFlowPrimaryTotal.value),
      color: MAP_THEME.route.up,
    },
  ];
  if (stationHasReverse.value) {
    sides.push({
      key: "reverse",
      label: reverseStationSideLabel.value,
      value: stationFlowReverseTotal.value,
      shareText: shareText(stationFlowReverseTotal.value),
      color: MAP_THEME.route.down,
    });
  }
  return sides;
});

// hourlyFlow 把上车与下车加在了一起，但两侧面板都带着分项，拆开就是本站是「上客点」还是「下客点」
function sumStationHours(panel, key) {
  const hours = Array.isArray(panel?.[key]) ? panel[key] : [];
  return hours.reduce((sum, value) => sum + (Number(value) || 0), 0);
}
function combinedStationMetric(key) {
  return sumStationHours(selectedStationPanel.value, key)
    + sumStationHours(selectedReverseStationPanel.value, key);
}

const stationBoardingStats = computed(() => [
  { label: "上车人数", ...formatLineCountStat(combinedStationMetric("boardingByHour"), "人次/日") },
  { label: "下车人数", ...formatLineCountStat(combinedStationMetric("alightingByHour"), "人次/日") },
]);

const unselectedBottomHintText = computed(() => {
  if (activeTab.value === "线路客流监测") {
    const isSelected = props.mode === "pfa" ? Boolean(selectedLineName.value) : Boolean(selectedLinePanel.value);
    if (!isSelected) return "可点击地图中的线路或右侧排行榜选择线路，具体查看客流信息";
  } else if (activeTab.value === "站点客流监测") {
    const isSelected = Boolean(selectedStationName.value);
    if (!isSelected) return "可点击地图中的站点或右侧排行榜选择站点，具体查看客流信息";
  }
  return "";
});

const stationFlowChartSeriesNames = computed(() => stationFlowLegend.value.map((side) => side.label));
const stationFlowChartOption = computed(() =>
  buildDirectionalLineFlowChartOption(
    stationFlowHourly.value,
    stationHasReverse.value ? reverseStationFlowHourly.value : emptyHourlyFlow(),
    stationFlowChartSeriesNames.value,
  )
);

const stationFlowChartRef = ref(null);
function focusStationFlowSeries(seriesIndex) {
  dimChartSeriesExcept(stationFlowChartRef, stationFlowChartSeriesNames.value.length, seriesIndex);
}
function blurStationFlowSeries() {
  dimChartSeriesExcept(stationFlowChartRef, stationFlowChartSeriesNames.value.length, -1);
}

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

function routeMatchesDisplayIds(panelKey, route, routeIds) {
  if (!routeIds) return true;
  const ids = [
    panelKey,
    route?.routeId,
    route?.routeKey,
    route?.lineId && route?.routeId ? `${route.lineId}::${route.routeId}` : "",
  ].map((value) => String(value || "")).filter(Boolean);
  return ids.some((id) => routeIds.has(id));
}

function routePanelToOverallHourlyByMode(panel = {}, routeIds = null) {
  const hourly = emptyModeHourlyFlow();
  const routeEntries = panel?.routes && typeof panel.routes === "object" ? Object.entries(panel.routes) : [];
  routeEntries.forEach(([routeId, route]) => {
    if (!routeMatchesDisplayIds(routeId, route, routeIds)) return;
    const values = Array.isArray(route?.hourlyFlow) ? route.hourlyFlow : [];
    const key = routeModeKey(route);
    values.forEach((value, index) => {
      if (index < hourly[key].length) hourly[key][index] += Number(value) || 0;
    });
  });
  return hourly;
}

// 公交运营效率分母：与上面的小时聚合同一套 routeIds 过滤与 bus/metro 判定，
// 口径同后端 overallFlow.busOperation（仅常规公交，车公里 = Σ班次×线长）
function routePanelToOverallBusOps(panel = {}, routeIds = null) {
  const ops = { vehicles: 0, departures: 0, operatedKm: 0, operators: [] };
  const operatorMap = new Map();
  const vehicleIds = new Set();
  const routeEntries = panel?.routes && typeof panel.routes === "object" ? Object.entries(panel.routes) : [];
  routeEntries.forEach(([routeId, route]) => {
    if (routeModeKey(route) !== "bus") return;
    if (!routeMatchesDisplayIds(routeId, route, routeIds)) return;
    const metrics = route?.metrics || {};
    const departures = Number(metrics.departures) || 0;
    const passenger = sumHourlyFlowArray(route?.hourlyFlow);
    const company = String(route?.operator || metrics.company || "未知企业");
    const operatedKm = Number(metrics.operatingVehicleKm);
    if (!Number.isFinite(operatedKm) || operatedKm < 0) {
      throw new Error(`线路 ${routeId} 缺少有效 operatingVehicleKm`);
    }
    const routeVehicleIds = Array.isArray(metrics.vehicleIds)
      ? metrics.vehicleIds.map((id) => String(id || "")).filter(Boolean)
      : [];
    if (!routeVehicleIds.length && Number(metrics.vehicles) > 0) {
      throw new Error(`线路 ${routeId} 声明了车辆数但缺少 vehicleIds`);
    }
    routeVehicleIds.forEach((id) => vehicleIds.add(id));
    ops.departures += departures;
    ops.operatedKm += operatedKm;
    const operator = operatorMap.get(company)
      || { name: company, passenger: 0, vehicleIds: new Set(), departures: 0, operatedKm: 0 };
    operator.passenger += passenger;
    routeVehicleIds.forEach((id) => operator.vehicleIds.add(id));
    operator.departures += departures;
    operator.operatedKm += operatedKm;
    operatorMap.set(company, operator);
  });
  ops.vehicles = vehicleIds.size;
  ops.operators = [...operatorMap.values()].map((item) => ({
    name: item.name,
    passenger: item.passenger,
    vehicles: item.vehicleIds.size,
    departures: item.departures,
    operatedKm: item.operatedKm,
    perVehicle: item.vehicleIds.size > 0 ? item.passenger / item.vehicleIds.size : 0,
    perTrip: item.departures > 0 ? item.passenger / item.departures : 0,
    intensity: item.operatedKm > 0 ? item.passenger / item.operatedKm : 0,
  })).sort((left, right) => right.passenger - left.passenger);
  return ops;
}

function normalizeDailyFlow(rows = []) {
  return (Array.isArray(rows) ? rows : []).map((item) => ({
    date: String(item?.date || ""),
    flow: Math.max(0, Number(item?.flow) || 0),
  })).filter((item) => item.date).sort((left, right) => left.date.localeCompare(right.date));
}

function routePanelToOverallDailyFlow(panel = {}, routeIds = null) {
  const values = new Map();
  for (const [routeId, route] of Object.entries(panel?.routes || {})) {
    if (routeModeKey(route) !== "bus" || !routeMatchesDisplayIds(routeId, route, routeIds)) continue;
    for (const item of normalizeDailyFlow(route?.dailyFlow)) {
      values.set(item.date, (values.get(item.date) || 0) + item.flow);
    }
  }
  return [...values.entries()].sort(([left], [right]) => left.localeCompare(right))
    .map(([date, flow]) => ({ date, flow }));
}

// 后端 overallFlow 接口返回的 busOperation 兜底为数值（旧后端无此字段时全 0 → 指标显示 "--"）
function normalizeOverallBusOps(raw = {}, operators = []) {
  return {
    vehicles: Number(raw?.vehicles) || 0,
    departures: Number(raw?.departures) || 0,
    operatedKm: Number(raw?.operatedKm) || 0,
    operators: Array.isArray(operators) ? operators : [],
  };
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
  if (effectiveTab.value !== "总体客流监测" || !selectModel.value?.name || !isModelReady.value) return;
  const modelName = selectModel.value.name;
  const seq = ++overallFlowRequestSeq;
  overallFlowAbortController?.abort();
  overallFlowAbortController = typeof AbortController !== "undefined" ? new AbortController() : null;
  overallFlowLoading.value = true;
  overallFlowError.value = "";
  overallFlowGenerating.value = false;
  // 后端缓存生成中：落到独立的「生成中」终态并给出重试入口（不新增轮询）
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
      overallFlowBusOps.value = routePanelToOverallBusOps(panel || {}, displayRouteIdSet.value);
      overallFlowDailyFlow.value = routePanelToOverallDailyFlow(panel || {}, displayRouteIdSet.value);
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
      overallFlowBusOps.value = normalizeOverallBusOps(data.busOperation, data.operatorOperations);
      overallFlowDailyFlow.value = normalizeDailyFlow(data.dailyFlow);
    }
  } catch (error) {
    if (seq !== overallFlowRequestSeq) return;
    if (error?.message === "请求已取消" || error?.cause?.message === "canceled") return;
    overallFlowError.value = error?.message || "总体客流监测加载失败";
  } finally {
    if (seq === overallFlowRequestSeq) {
      overallFlowAbortController = null;
      overallFlowLoading.value = false;
      overallFlowGenerating.value = keepLoading;
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
  if (effectiveTab.value !== "总体客流监测" || !selectModel.value?.name || !isModelReady.value) return;
  const cachedPanel = peekCachedRoutePanel(selectModel.value.name);
  if (cachedPanel) {
    overallFlowRequestSeq += 1;
    overallFlowAbortController?.abort();
    overallFlowAbortController = null;
    overallFlowLoading.value = false;
    overallFlowGenerating.value = false;
    overallFlowError.value = "";
    overallFlowHourlyByMode.value = routePanelToOverallHourlyByMode(cachedPanel, displayRouteIdSet.value);
    overallFlowBusOps.value = routePanelToOverallBusOps(cachedPanel, displayRouteIdSet.value);
    overallFlowDailyFlow.value = routePanelToOverallDailyFlow(cachedPanel, displayRouteIdSet.value);
    return;
  }
  loadOverallFlow();
});

const RM_SOURCE_LINES = "rm-bus-network-lines-source";
const RM_SOURCE_STATIONS = "rm-bus-network-stations-source";
const RM_SOURCE_SELECTED_STATION = "rm-bus-network-selected-station-source";
const RM_SOURCE_REVERSE_SELECTED_STATION = "rm-bus-network-reverse-selected-station-source";
const RM_SOURCE_DISPLAY_RANGE = "rm-display-range-source";
// 行政区外灰色底图：触及本区线路的完整几何 + 其区外站点，铺在正常图层之下
const RM_SOURCE_BASE_LINES = "rm-bus-network-base-lines-source";
const RM_SOURCE_BASE_STATIONS = "rm-bus-network-base-stations-source";
const RM_LAYER_BASE_LINES = "rm-bus-network-base-lines";
const RM_LAYER_BASE_METRO_LINES = "rm-metro-network-base-lines";
const RM_LAYER_BASE_STATIONS = "rm-bus-network-base-stations";
const RM_BASE_NETWORK_COLOR = MAP_THEME.network.outside;
const RM_BASE_NETWORK_OPACITY = MAP_THEME.network.outsideOpacity;
const RM_LAYER_LINES = "rm-bus-network-lines";
const RM_LAYER_STATIONS = "rm-bus-network-stations";
// 站点客流着色（站点客流监测未选中态）：彩色圆点压在透明图标层之下，图标层继续承担点击命中
const RM_LAYER_STATION_FLOW = "rm-bus-network-station-flow";
const RM_STATION_FLOW_FALLBACK_COLOR = "#9ca3af";
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
// 关联线路模式：换乘站点（换乘图标 + 站名）
const RM_SOURCE_TRANSFER_STATIONS = "rm-transfer-stations-source";
const RM_LAYER_TRANSFER_STATION_ICONS = "rm-transfer-station-icons";
const RM_LAYER_TRANSFER_STATION_LABELS = "rm-transfer-station-labels";
// 关联线路本体：改用 MapLibre GeoJSON 线（每条线一条 LineString，蓝色实线），
// 避免 deck RouteLayer 把多条线的链路串接产生错误连线（"线形"问题）
const RM_SOURCE_TRANSFER_LINES = "rm-transfer-lines-source";
const RM_LAYER_TRANSFER_LINES = "rm-transfer-lines";
const PFA_RELATED_LINE_COLOR = "#1569de"; // 与客流画像下行蓝线一致
// 关联线路只作为换乘关系的辅助参照，降低透明度以让选中线路保持视觉焦点。
const PFA_RELATED_LINE_OPACITY = 0.62;
// 关联线路模式的选中线路本体：黄色高亮线（业务要求），压在蓝色关联线之上、换乘图标之下。
// 亮黄在浅色底图上轮廓发虚，主线下垫一条深琥珀描边线。
const RM_SOURCE_TRANSFER_SELECTED_LINE = "rm-transfer-selected-line-source";
const RM_LAYER_TRANSFER_SELECTED_CASING = "rm-transfer-selected-line-casing";
const RM_LAYER_TRANSFER_SELECTED_LINE = "rm-transfer-selected-line";
const PFA_TRANSFER_SELECTED_LINE_COLOR = MAP_THEME.route.transferSelected;
const PFA_TRANSFER_SELECTED_CASING_COLOR = MAP_THEME.route.transferSelectedCasing;
// 站点客流监测·站点乘降/客流画像模式：选中站点后地图加画"经过该站的全部线路"（蓝色，
// 复用关联线路蓝的视觉语言；按当前制式过滤，站点热力开启时让位专题图）
const RM_SOURCE_STATION_THROUGH_LINES = "rm-station-through-lines-source";
const RM_LAYER_STATION_THROUGH_LINES = "rm-station-through-lines";
// 站点客流分析：全网站点客流热力图（连续密度晕染，开启时隐藏公交/地铁线网与站点）
const RM_SOURCE_STATION_HEAT = "rm-station-heat-source";
const RM_LAYER_STATION_HEAT = "rm-station-heat-layer";
// 地铁线网图层：与公交线网共用 RM_SOURCE_LINES / RM_SOURCE_STATIONS 数据源，
// 按要素 mode（线路）/ type（站点）过滤，二者随 baseMapLineMode 互斥显示。
const RM_LAYER_METRO_LINES = "rm-metro-network-lines";
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
const RM_STATION_OUTSIDE_ICON_ID = "rm-bus-network-station-outside-icon";
// 关联线路模式换乘站点图标（蓝色徽章双向箭头）
const RM_TRANSFER_STATION_ICON_ID = "rm-transfer-station-icon";
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

// 供右侧断面表按地图同一套分档着色（XLZL 注入）：颜色与断点整体替换引用，监听引用即可
const pfaSegmentPanelScale = computed(() => ({
  colors: pfaSegmentResolvedScale.value.colors,
  breaks: pfaSegmentBreaks.value,
}));
provide("runMonitorPfaSegmentScale", pfaSegmentPanelScale);

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

// ===== 站点客流分析：全网站点客流热力图（设置里开关，开启时隐藏公交/地铁线网与站点） =====
const stationHeatmapEnabled = ref(false);
// 原始 stations 整包按模型缓存；热力点按当前行政区显示范围重建
const stationHeatStations = shallowRef(null);
let stationHeatModel = "";
let stationHeatSeq = 0;
// 热力色阶（图例右上角齿轮可调色系/档数/阈值）。
// 默认绿→黄→红：低密度淡绿、高密度红核，与常见人群密度热力图口径一致
const stationHeatScale = ref(createColorScaleConfig(MAP_THEME.schemes.stationHeat, 5));
const showStationHeatScalePopover = ref(false);
// 最大站点全天客流：站点权重的归一分母 + 图例显隐门槛
const stationHeatMaxFlow = ref(0);
const stationHeatResolvedScale = computed(() => resolveColorScale(stationHeatScale.value));

// 密度低于此值渐隐到全透明：底图留白，热力只在有站点的地方晕开
const STATION_HEAT_FADE_DENSITY = 0.02;
// 渐隐段末端的最低档色透明度：太高会在无客流区糊一层底色，太低则低密度晕圈看不见
const STATION_HEAT_FADE_ALPHA = 0.35;

// 连续 interpolate 表达式：密度 0 → 全透明，渐隐段淡入最低档色，
// 其后按色阶阈值（此处读作"相对密度百分比"）在各档色之间平滑过渡，最高档色钉在密度 1。
//
// 位置取密度百分比而非"客流分位断点 ÷ 最大客流"：heatmap-density 是核密度的加权累加，
// 与单站客流不同量纲。站点客流分布高度偏态，分位断点只占最大值的百分之几，
// 照此定位会把整条色带挤在 density<0.2 内，城区一律顶格成纯色（旧版整片深色的成因）。
const stationHeatColorExpression = computed(() => {
  const { colors, thresholds } = stationHeatResolvedScale.value;
  const expression = [
    "interpolate",
    ["linear"],
    ["heatmap-density"],
    0, hexToRgba(colors[0], 0),
    STATION_HEAT_FADE_DENSITY, hexToRgba(colors[0], STATION_HEAT_FADE_ALPHA),
  ];
  let previous = STATION_HEAT_FADE_DENSITY;
  thresholds.forEach((percent, index) => {
    // 上界随剩余档数收缩：阈值全贴到 100% 时后面的 stop 仍有递增余量
    // （interpolate 要求输入严格递增，重复 stop 会让整层报错不渲染）
    const ceiling = 0.999 - 0.001 * (thresholds.length - 1 - index);
    const position = Math.min(ceiling, Math.max(previous + 0.001, percent / 100));
    expression.push(position, colors[index]);
    previous = position;
  });
  expression.push(1, colors[colors.length - 1]);
  return expression;
});

function applyStationHeatColor() {
  const map = MapRef.value?.map;
  if (!map?.getLayer(RM_LAYER_STATION_HEAT)) return;
  map.setPaintProperty(RM_LAYER_STATION_HEAT, "heatmap-color", stationHeatColorExpression.value);
}

watch(stationHeatColorExpression, applyStationHeatColor);

// 图例口径同着色：分档标注的是相对密度区间（0%=无站点，100%=最密集簇心），不是单站人次
const stationHeatLegendItems = computed(() =>
  buildLegendItems(stationHeatResolvedScale.value.colors, stationHeatResolvedScale.value.thresholds)
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
    // 选中行政区时只保留区内站点（但 maxFlow 仍按全网统计）
    if (context && !lngLatInDisplayRange(lngLat, context)) return;
    rows.push({ lngLat, flow });
  });
  return {
    maxFlow,
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

function refreshStationHeatSources() {
  if (!stationHeatmapEnabled.value) return;
  const context = activeDisplayRangeContext.value;
  if (stationHeatStations.value) {
    const { maxFlow, collection } = buildStationHeatFeatureCollection(stationHeatStations.value, context);
    stationHeatMaxFlow.value = maxFlow;
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
const lineFlowScale = ref(createColorScaleConfig("YlOrRd", 5));
const showLineFlowScalePopover = ref(false);
const showSegmentFlowScalePopover = ref(false);

// 与 XLZL 共用 routePanel 缓存整包：模型就绪即加载，
// 保证"总体客流监测"等首个 tab 一进来就按客流着色（原先只在线路客流监测 tab 才加载，
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

// ===== 线网着色指标：与右侧排名面板的“排名依据”同步（不再在设置面板单独切换）=====
// 仅线路客流监测页签生效；其余页签维持客流着色（切页自动回落，无需重置状态）。
// 取值为 LINE_RANK_METRICS.key（定义见下方排名区），排名榜与线网着色共用同一状态。
const lineRankMetric = ref("flow");
const activeLineColorMetricKey = computed(() =>
  effectiveTab.value === "线路客流监测" ? lineRankMetric.value : "flow",
);
const lineLoadRateActive = computed(() => activeLineColorMetricKey.value === "peakLoadRate");

/** 后端按系统早晚高峰窗计算的“各班最大站段满载率”班次均值（%）。 */
function panelPeakAverageLoadRate(panel) {
  return Number(panel?.metrics?.peakAverageLoadRate) || 0;
}

/**
 * 每条线路平均高峰满载率（%）：lineId 归属接线与 lineFlowById 完全同构
 * （lineGroups 优先、routes 兜底 + routeId 反查）。
 */
const linePeakLoadById = computed(() => {
  const peaks = new Map();
  const panel = lineFlowPanel.value;
  if (!panel) return peaks;
  const routeIdToLineIds = new Map();
  Object.entries(panel.routes || {}).forEach(([key, item]) => {
    const lineId = String(item?.lineId ?? "") || String(key).split("::")[0] || "";
    if (!lineId) return;
    peaks.set(lineId, panelPeakAverageLoadRate(item));
    const routeId = String(item?.routeId ?? "");
    if (routeId) {
      if (!routeIdToLineIds.has(routeId)) routeIdToLineIds.set(routeId, []);
      routeIdToLineIds.get(routeId).push(lineId);
    }
  });
  Object.entries(panel.lineGroups || {}).forEach(([key, group]) => {
    const peak = panelPeakAverageLoadRate(group);
    if (!(peak > 0)) return;
    const lineIds = new Set();
    if (String(key).startsWith("bus::")) lineIds.add(String(key).slice(5));
    (Array.isArray(group?.routeIds) ? group.routeIds : []).forEach((routeId) => {
      (routeIdToLineIds.get(String(routeId)) || []).forEach((lineId) => lineIds.add(lineId));
    });
    lineIds.forEach((lineId) => peaks.set(lineId, peak));
  });
  return peaks;
});

const busMaxPeakLoad = computed(() => {
  const metroIds = metroLineIdSet.value;
  let max = 0;
  linePeakLoadById.value.forEach((value, lineId) => {
    if (!metroIds.has(lineId) && value > max) max = value;
  });
  return max;
});
const metroMaxPeakLoad = computed(() => {
  const metroIds = metroLineIdSet.value;
  let max = 0;
  linePeakLoadById.value.forEach((value, lineId) => {
    if (metroIds.has(lineId) && value > max) max = value;
  });
  return max;
});

/**
 * lineGroups.metrics 派生指标（车均日载客量/单班次载客量/客流强度）按 lineId 展开，
 * 归属接线与 lineFlowById 同构（bus:: 组直配 + routeId 反查）。旧缓存无 lineGroups 时为空 Map，
 * 线网退回基础配色，与排名榜同口径（彼时排名值也以 0 兜底）。
 */
const lineGroupMetricValuesById = computed(() => {
  const maps = { perVehicleFlow: new Map(), perTripFlow: new Map(), strength: new Map() };
  const panel = lineFlowPanel.value;
  if (!panel) return maps;
  const routeIdToLineIds = new Map();
  Object.entries(panel.routes || {}).forEach(([key, item]) => {
    const lineId = String(item?.lineId ?? "") || String(key).split("::")[0] || "";
    const routeId = String(item?.routeId ?? "");
    if (!lineId || !routeId) return;
    if (!routeIdToLineIds.has(routeId)) routeIdToLineIds.set(routeId, []);
    routeIdToLineIds.get(routeId).push(lineId);
  });
  Object.entries(panel.lineGroups || {}).forEach(([key, group]) => {
    const metrics = group?.metrics || {};
    const passenger = Number(metrics.passenger);
    const flow = Number.isFinite(passenger)
      ? passenger
      : (Array.isArray(group?.hourlyFlow)
        ? group.hourlyFlow.reduce((sum, value) => sum + (Number(value) || 0), 0)
        : 0);
    const values = lineRankMetricValues(metrics, flow);
    if (!(values.perVehicleFlow > 0) && !(values.perTripFlow > 0) && !(values.strength > 0)) return;
    const lineIds = new Set();
    if (String(key).startsWith("bus::")) lineIds.add(String(key).slice(5));
    (Array.isArray(group?.routeIds) ? group.routeIds : []).forEach((routeId) => {
      (routeIdToLineIds.get(String(routeId)) || []).forEach((lineId) => lineIds.add(lineId));
    });
    lineIds.forEach((lineId) => {
      Object.keys(maps).forEach((metricKey) => {
        const value = values[metricKey];
        if (value > 0) maps[metricKey].set(lineId, value);
      });
    });
  });
  return maps;
});

// 分位分档着色的取值来源：lineId → 当前指标值（平均高峰满载率走固定语义分档，不经此路）
const activeQuantileMetricValuesById = computed(() =>
  lineGroupMetricValuesById.value[activeLineColorMetricKey.value] || lineFlowById.value,
);

// 当前分位口径的全部线路指标值（按公交/地铁分开），用于分位数断点与最大值
const lineMetricValuesByMode = computed(() => {
  const metroIds = metroLineIdSet.value;
  const bus = [];
  const metro = [];
  activeQuantileMetricValuesById.value.forEach((value, lineId) => {
    if (!(value > 0)) return;
    (metroIds.has(lineId) ? metro : bus).push(value);
  });
  return { bus, metro };
});

const busLineMetricMax = computed(() => lineMetricValuesByMode.value.bus.reduce((m, v) => Math.max(m, v), 0));
const metroLineMetricMax = computed(() => lineMetricValuesByMode.value.metro.reduce((m, v) => Math.max(m, v), 0));

const lineFlowResolvedScale = computed(() => resolveColorScale(lineFlowScale.value));

/** 当前着色指标的取值/断点/色板：满载率用 mapTheme.lineLoadRate 固定语义分档，其余口径保持分位分档。 */
const activeLineMetricScale = computed(() => {
  if (lineLoadRateActive.value) {
    const scale = MAP_THEME.lineLoadRate;
    return { values: linePeakLoadById.value, busBreaks: scale.breaks, metroBreaks: scale.breaks, colors: scale.colors, widths: scale.widths };
  }
  const resolved = lineFlowResolvedScale.value;
  return {
    values: activeQuantileMetricValuesById.value,
    busBreaks: busLineMetricBreaks.value,
    metroBreaks: metroLineMetricBreaks.value,
    colors: resolved.colors,
    widths: resolved.widths,
  };
});

// 同断面色阶：排序只随各制式指标分布变化重跑
const lineMetricSortedValuesByMode = computed(() => ({
  bus: sortFlowValues(lineMetricValuesByMode.value.bus),
  metro: sortFlowValues(lineMetricValuesByMode.value.metro),
}));

// 分位数断点：由各制式的线路指标分布计算（各档线路条数大致均匀）
const busLineMetricBreaks = computed(() => quantileBreaks(lineMetricSortedValuesByMode.value.bus, lineFlowResolvedScale.value.thresholds, { assumeSorted: true }));
const metroLineMetricBreaks = computed(() => quantileBreaks(lineMetricSortedValuesByMode.value.metro, lineFlowResolvedScale.value.thresholds, { assumeSorted: true }));

// 以下三个构建器同时服务全部着色口径（排名依据五种）：values 为 lineId → 指标值。
function buildLineFlowMatchExpression(includeMetro, breaks, values, colors) {
  const metroIds = metroLineIdSet.value;
  if (!values.size || !breaks.length) return null;
  const expression = ["match", ["to-string", ["get", "lineId"]]];
  values.forEach((value, lineId) => {
    if (!lineId || metroIds.has(lineId) !== includeMetro) return;
    expression.push(lineId, colors[classifyByBreaks(value, breaks)]);
  });
  if (expression.length <= 2) return null;
  expression.push(colors[0]);
  return expression;
}

// 需求：每档离散色阶按指标分档区分线宽（值越大越粗，系数收敛避免失衡）。
// lineId → 该线所在档位的线宽系数（match 表达式，供 line-width 乘算）。
function buildLineFlowWidthFactorExpression(includeMetro, breaks, values, widths) {
  const metroIds = metroLineIdSet.value;
  if (!values.size || !breaks.length) return null;
  const expression = ["match", ["to-string", ["get", "lineId"]]];
  values.forEach((value, lineId) => {
    if (!lineId || metroIds.has(lineId) !== includeMetro) return;
    expression.push(lineId, widths[classifyByBreaks(value, breaks)] || 1);
  });
  if (expression.length <= 2) return null;
  expression.push(widths[0] || 1);
  return expression;
}

// 图例高档位（指标值大）线路要压在低档位之上：lineId → 档位序号（供 line-sort-key，
// MapLibre 按 sort-key 升序绘制，档位越高越后画）。
function buildLineFlowSortKeyExpression(includeMetro, breaks, values) {
  const metroIds = metroLineIdSet.value;
  if (!values.size || !breaks.length) return null;
  const expression = ["match", ["to-string", ["get", "lineId"]]];
  values.forEach((value, lineId) => {
    if (!lineId || metroIds.has(lineId) !== includeMetro) return;
    expression.push(lineId, classifyByBreaks(value, breaks));
  });
  if (expression.length <= 2) return null;
  expression.push(0);
  return expression;
}

// match 表达式：lineId → 分档颜色；routePanel 未就绪时为 null（保持现有颜色）。
// 着色指标由 activeLineMetricScale 决定（线路客流监测页签随排名依据切换，其余页签恒为客流）。
const lineFlowColorExpression = computed(() => {
  const scale = activeLineMetricScale.value;
  return buildLineFlowMatchExpression(false, scale.busBreaks, scale.values, scale.colors);
});
const metroLineFlowColorExpression = computed(() => {
  const scale = activeLineMetricScale.value;
  return buildLineFlowMatchExpression(true, scale.metroBreaks, scale.values, scale.colors);
});
const lineFlowWidthFactorExpression = computed(() => {
  const scale = activeLineMetricScale.value;
  return buildLineFlowWidthFactorExpression(false, scale.busBreaks, scale.values, scale.widths);
});
const metroLineWidthFactorExpression = computed(() => {
  const scale = activeLineMetricScale.value;
  return buildLineFlowWidthFactorExpression(true, scale.metroBreaks, scale.values, scale.widths);
});
const lineFlowSortKeyExpression = computed(() => {
  const scale = activeLineMetricScale.value;
  return buildLineFlowSortKeyExpression(false, scale.busBreaks, scale.values);
});
const metroLineFlowSortKeyExpression = computed(() => {
  const scale = activeLineMetricScale.value;
  return buildLineFlowSortKeyExpression(true, scale.metroBreaks, scale.values);
});

function flowValueLabel(value) {
  return `${Math.round(Number(value) || 0).toLocaleString()} 人次`;
}

// 分位口径图例区间标签：客流沿用“x 人次”，派生口径裸数值（单位已在图例标题里）
function lineMetricValueLabel(value) {
  return activeLineColorMetricKey.value === "flow"
    ? flowValueLabel(value)
    : Math.round(Number(value) || 0).toLocaleString();
}

const busLineMetricLegendItems = computed(() =>
  buildValueLegendItems(lineFlowResolvedScale.value.colors, busLineMetricBreaks.value, busLineMetricMax.value, lineMetricValueLabel, lineFlowResolvedScale.value.widths)
);

const metroLineMetricLegendItems = computed(() =>
  buildValueLegendItems(lineFlowResolvedScale.value.colors, metroLineMetricBreaks.value, metroLineMetricMax.value, lineMetricValueLabel, lineFlowResolvedScale.value.widths)
);

// 平均高峰满载率图例（固定语义分档，% 标注；随制式取各自峰值封顶末档标签）
function loadRateValueLabel(value) {
  return `${Math.round(Number(value) || 0)}%`;
}

const busLoadRateLegendItems = computed(() =>
  buildValueLegendItems(MAP_THEME.lineLoadRate.colors, MAP_THEME.lineLoadRate.breaks, busMaxPeakLoad.value, loadRateValueLabel, MAP_THEME.lineLoadRate.widths)
);

const metroLoadRateLegendItems = computed(() =>
  buildValueLegendItems(MAP_THEME.lineLoadRate.colors, MAP_THEME.lineLoadRate.breaks, metroMaxPeakLoad.value, loadRateValueLabel, MAP_THEME.lineLoadRate.widths)
);

// ===== 未选中线路/站点时，右侧面板显示客流排名（点击排名行等价于在搜索框选中） =====
function buildFlowRankResult(entries) {
  entries.sort((a, b) => b.flow - a.flow);
  const rows = limitRightPanelRanking(entries);
  const maxFlow = rows.length ? rows[0].flow : 0;
  return {
    total: entries.length,
    rows: rows.map((entry, index) => ({
      ...entry,
      rank: index + 1,
      barWidth: maxFlow > 0 ? `${Math.max(3, Math.round((entry.flow / maxFlow) * 100))}%` : "0%",
    })),
  };
}

// ===== 线路排名（未选中态右栏）：Top10 + 五种排名口径可切换 =====
// 指标全部来自 routePanel lineGroups 线级口径（上下行合并，与选中线路面板一致）；
// 车均、班均与客流强度只使用规范分子/分母现场计算；
// 平均高峰满载率由后端按“每班最大站段满载率”的班次均值计算。
// 排名依据 lineRankMetric 同时驱动线网着色（状态定义见上方“线网着色指标”区）。
const activeLineRankMetric = computed(
  () => LINE_RANK_METRICS.find((metric) => metric.key === lineRankMetric.value) || LINE_RANK_METRICS[0],
);

// 线路排名：与线路着色共用同一份 routePanel 整包。lineGroups 为上下行合并口径
// （与选中线路面板的“日客流量”一致），组名与搜索候选同一套命名规则，点击行可直接按名选中
const lineFlowRank = computed(() => {
  const panel = lineFlowPanel.value;
  if (!panel) return null;
  const wantMode = searchWantsMetro.value ? "subway" : "bus";
  const metric = activeLineRankMetric.value;
  const entries = buildLineRankEntries(panel, wantMode).filter((entry) => {
    return Number.isFinite(entry[metric.key])
      && runMonitorOptionInDisplayRange({ value: entry.name }, "line");
  });
  entries.sort((a, b) => (b[metric.key] - a[metric.key]) || a.name.localeCompare(b.name, "zh-CN"));
  const rows = limitRightPanelRanking(entries);
  const maxValue = rows.length ? rows[0][metric.key] : 0;
  return {
    total: entries.length,
    rows: rows.map((entry, index) => ({
      ...entry,
      rank: index + 1,
      valueText: lineRankValueText(entry[metric.key], metric.decimals),
      barWidth: maxValue > 0 ? `${Math.max(3, Math.round((entry[metric.key] / maxValue) * 100))}%` : "0%",
    })),
  };
});

// 站点排名数据：与站点热力/ZDZL 共用 stationPanel 缓存（模型加载时已预热）。
// 后端缓存生成中（无 stations）时定时重试，就绪后自动上屏
const stationRankStations = shallowRef(null);
let stationRankModel = "";
let stationRankSeq = 0;
let stationRankRetryTimer = null;

function clearStationRankRetry() {
  if (stationRankRetryTimer) {
    clearTimeout(stationRankRetryTimer);
    stationRankRetryTimer = null;
  }
}

function ensureStationRankData() {
  const modelName = selectModel.value?.name;
  if (effectiveTab.value !== "站点客流监测" || !modelName || !isModelReady.value) {
    clearStationRankRetry();
    return;
  }
  if (stationRankModel && stationRankModel !== modelName) {
    stationRankStations.value = null;
    stationRankModel = "";
  }
  if (stationRankModel === modelName && stationRankStations.value) return;
  clearStationRankRetry();
  const seq = ++stationRankSeq;
  getCachedStationPanel(modelName)
    .then((data) => {
      if (seq !== stationRankSeq || selectModel.value?.name !== modelName) return;
      if (data?.stations) {
        stationRankModel = modelName;
        stationRankStations.value = data.stations;
      } else if (effectiveTab.value === "站点客流监测") {
        stationRankRetryTimer = setTimeout(() => {
          stationRankRetryTimer = null;
          ensureStationRankData();
        }, 8000);
      }
    })
    .catch(() => {});
}

watch([effectiveTab, () => selectModel.value?.name, isModelReady], ensureStationRankData, { immediate: true });

const stationFlowRank = computed(() => {
  const stations = stationRankStations.value;
  if (!stations) return null;
  const wantMode = searchWantsMetro.value ? "subway" : "bus";
  const entries = [];
  for (const [name, station] of Object.entries(stations)) {
    if (String(station?.mode || "") !== wantMode) continue;
    // 与站点热力图同一口径：全天上下车合计
    const flow = sumHourlyFlowArray(station?.hourlyFlow);
    if (!(flow > 0)) continue;
    if (!runMonitorOptionInDisplayRange({ value: name }, "station")) continue;
    entries.push({ name, flow });
  }
  return buildFlowRankResult(entries);
});

// ===== 需求：站点客流监测未选中态，站点按客流大小分档着色 =====
// 口径与右侧排名一致：站点全天上下车合计（全部经停线路在该站客流之和，stationPanel 按站名聚合）。
// 数据复用排名的 stationRankStations 整包；断点与线网着色同款分位分档，公交/地铁分别归一。
const stationFlowScale = ref(createColorScaleConfig("YlOrRd", 5));
const showStationFlowScalePopover = ref(false);
const stationFlowResolvedScale = computed(() => resolveColorScale(stationFlowScale.value));

const stationFlowByModeName = computed(() => {
  const bus = new Map();
  const metro = new Map();
  const stations = stationRankStations.value;
  if (stations) {
    for (const [name, station] of Object.entries(stations)) {
      const flow = sumHourlyFlowArray(station?.hourlyFlow);
      if (!(flow > 0)) continue;
      (String(station?.mode || "") === "subway" ? metro : bus).set(name, flow);
    }
  }
  return { bus, metro };
});

const busStationFlowBreaks = computed(() => quantileBreaks(
  sortFlowValues(Array.from(stationFlowByModeName.value.bus.values())),
  stationFlowResolvedScale.value.thresholds,
  { assumeSorted: true },
));
const metroStationFlowBreaks = computed(() => quantileBreaks(
  sortFlowValues(Array.from(stationFlowByModeName.value.metro.values())),
  stationFlowResolvedScale.value.thresholds,
  { assumeSorted: true },
));

const stationFlowActiveMode = computed(() => (baseMapLineMode.value === "metro-network" ? "metro" : "bus"));
const activeStationFlowBreaks = computed(() => (
  stationFlowActiveMode.value === "metro" ? metroStationFlowBreaks.value : busStationFlowBreaks.value
));

// match 表达式：站名 → 分档颜色。同名对向站台共用同一站名客流（与站点面板聚合口径一致）；
// 零客流站不入表，走 fallback 最低档色
const stationFlowColorExpression = computed(() => {
  const values = stationFlowByModeName.value[stationFlowActiveMode.value];
  const breaks = activeStationFlowBreaks.value;
  if (!values.size || !breaks.length) return null;
  const { colors } = stationFlowResolvedScale.value;
  const expression = ["match", ["to-string", ["get", "name"]]];
  values.forEach((flow, name) => {
    if (!name) return;
    expression.push(name, colors[classifyByBreaks(flow, breaks)]);
  });
  if (expression.length <= 2) return null;
  expression.push(colors[0]);
  return expression;
});

const stationFlowMaxValue = computed(() => {
  let max = 0;
  stationFlowByModeName.value[stationFlowActiveMode.value].forEach((flow) => {
    if (flow > max) max = flow;
  });
  return max;
});

// 着色激活条件 = 图例显示条件：站点页签 + 未选中站点 + 数据就绪；pfa 热力开启时让位专题图
const stationFlowColoringActive = computed(() =>
  effectiveTab.value === "站点客流监测"
  && !isStationFeatureSelectionActive()
  && !(props.mode === "pfa" && stationHeatmapEnabled.value)
  && Boolean(stationFlowColorExpression.value)
);

const showStationFlowLegend = stationFlowColoringActive;

const stationFlowLegendItems = computed(() => buildValueLegendItems(
  stationFlowResolvedScale.value.colors,
  activeStationFlowBreaks.value,
  stationFlowMaxValue.value,
  flowValueLabel,
));

function applyStationFlowColor() {
  const map = MapRef.value?.map;
  if (!map?.getLayer(RM_LAYER_STATION_FLOW)) return;
  map.setPaintProperty(
    RM_LAYER_STATION_FLOW,
    "circle-color",
    stationFlowColorExpression.value || RM_STATION_FLOW_FALLBACK_COLOR,
  );
}

watch(stationFlowColorExpression, applyStationFlowColor);
// 着色激活态变化（数据就绪/选中站点/切页签/热力开关）：圆点显隐与图标透明度一起走
watch(stationFlowColoringActive, () => {
  applyBusNetworkFocus();
  syncBaseMapLayerVisibility();
});

// 点击排名行：复用搜索框选中链路（地图高亮 + 相机跳转 + 右侧详情面板）
function selectFlowRankRow(row) {
  const name = String(row?.name || "");
  if (!name) return;
  selectRunMonitorResult({ label: name, value: name });
}

const lineSelectionActiveState = computed(() =>
  effectiveTab.value === "线路客流监测"
  && Boolean(
    selectedLineKey.value
    || selectedRouteDetail.value?.routeId
    || selectedLineName.value
    || selectedRouteMapLinks.value?.length
  )
);

// 总体客流监测与线路客流监测共用同一套线网着色，图例与色阶设置同步展示
const LINE_FLOW_LEGEND_TABS = ["线路客流监测", "总体客流监测"];

const showLineFlowLegend = computed(() =>
  LINE_FLOW_LEGEND_TABS.includes(effectiveTab.value)
  && baseMapLineMode.value === "bus-network"
  && !lineSelectionActiveState.value
  && Boolean(lineFlowColorExpression.value)
);

// 地铁线网模式：图例换用地铁自己的客流分档（与公交分开归一）
const showMetroFlowLegend = computed(() =>
  LINE_FLOW_LEGEND_TABS.includes(effectiveTab.value)
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

// 关联线路模式图例（黄色选中线 / 蓝色直接换乘线 / 换乘图标站点）：
// 分类图例无色阶齿轮；与其余图例互斥（选中态下线路客流图例自动退场，其余图例属别的页签/子模块）
const showTransferRelationLegend = computed(() =>
  props.mode === "pfa"
  && effectiveTab.value === "线路客流监测"
  && pfaLineSection.value === "transfer"
  && lineSelectionActiveState.value
  && (baseMapLineMode.value === "bus-network" || baseMapLineMode.value === "metro-network")
);

// ===== 路段公交车速图例（车辆运行监测，开关开启且数据就绪时出现）=====
// 绝对分档（mapTheme.linkSpeed 固定断点），无色阶齿轮；透明度在设置面板调节。
const linkSpeedLegendItems = computed(() => {
  const theme = MAP_THEME.linkSpeed;
  const items = theme.colors
    .map((color, index) => {
      const lower = index === 0 ? null : theme.breaks[index - 1];
      const upper = index < theme.breaks.length ? theme.breaks[index] : null;
      const range = lower == null ? `<${upper}` : upper == null ? `≥${lower}` : `${lower}-${upper}`;
      return { color, label: `${theme.labels[index]} ${range} km/h` };
    })
    .reverse(); // 畅通（高速档）在上，与其余图例高值在上的惯例一致
  // 灰=当前时段无班次经过（线网轮廓保留档），排在最末
  items.push({ color: theme.noData, label: "无班次经过" });
  return items;
});

const showLinkSpeedLegend = computed(() =>
  isVehicleMonitorTab.value && linkSpeedEnabled.value && linkSpeedStatus.value === "ready"
);

const activeMapLegendItems = computed(() => {
  if (showLinkSpeedLegend.value) return linkSpeedLegendItems.value;
  if (showOdCurveLegend.value) return odCurveLegendItems.value;
  if (showStationHeatLegend.value) return stationHeatLegendItems.value;
  if (showStationFlowLegend.value) return stationFlowLegendItems.value;
  if (showSegmentFlowLegend.value) return segmentFlowLegendItems.value;
  if (lineLoadRateActive.value) {
    return showMetroFlowLegend.value ? metroLoadRateLegendItems.value : busLoadRateLegendItems.value;
  }
  return showMetroFlowLegend.value ? metroLineMetricLegendItems.value : busLineMetricLegendItems.value;
});

// 线网着色图例标题：随排名依据切换口径，前缀区分公交/地铁各自分档
const LINE_METRIC_LEGEND_TITLES = {
  flow: "客流（人次/日）",
  perVehicleFlow: "车均日载客量（人次/车·日）",
  perTripFlow: "单班次载客量（人次/班）",
  strength: "客流强度（人次/车公里）",
  peakLoadRate: "平均高峰满载率（%）",
};
const lineMetricLegendTitle = computed(() =>
  `${showMetroFlowLegend.value ? "地铁" : "线路"}${LINE_METRIC_LEGEND_TITLES[activeLineColorMetricKey.value] || LINE_METRIC_LEGEND_TITLES.flow}`,
);

// 图例色块高度按该档线宽系数变化（客流越大越粗），无系数时用默认高度
function legendSwatchHeight(width) {
  if (!width) return "10px";
  return `${Math.round(6 + (Number(width) - 1) * 6)}px`;
}
const busNetworkLoading = ref(false);
const busNetworkError = ref("");
let busNetworkRequestSeq = 0;
let routePickRequestSeq = 0;
let busNetworkClickListenerId = null;
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
  // 公交出行监测（栅格/街道/OD 专题图）与客流走廊监测（自绘重复系数线网）
  // 都不铺线网瓦片，与车辆监测同待遇
  return tab !== "车辆运行监测"
    && tab !== "轨迹演示"
    && tab !== "公交出行监测"
    && tab !== "客流走廊监测";
}

function pauseTransitNetworkTiles() {
  monitorBusRouteLayer?.hide();
}

function ensureTransitNetworkForCurrentTab() {
  if (!pageActive.value) return;
  if (!shouldLoadTransitNetworkForCurrentTab()) {
    pauseTransitNetworkTiles();
    return;
  }
  ensureMonitorBusRouteLayer();
  loadBusNetwork();
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
const stationFlowCircleRadius = computed(() => {
  const sizeScale = Math.max(0.5, Math.min(1.5, Number(stationSize.value) / 32 || 1));
  return [
    "interpolate",
    ["linear"],
    ["zoom"],
    8, 1.6 * sizeScale,
    10, 2.6 * sizeScale,
    12, 4 * sizeScale,
    14, 6 * sizeScale,
    16, 9 * sizeScale,
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
let stationHeatLayerOnTop = false;

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
  await addMapImageOnce(map, RM_STATION_OUTSIDE_ICON_ID, busStationOutsideIconUrl, RM_STATION_ICON_SIZE);
  await addMapImageOnce(map, RM_TRANSFER_STATION_ICON_ID, transferStationIconUrl, RM_STATION_ICON_SIZE);
}

async function addMapImageOnce(map, imageId, imageUrl, size) {
  if (map.hasImage?.(imageId)) return;
  // 2x 栅格化 + pixelRatio:2 注册：icon-size 语义下显示尺寸不变，
  // 高分屏/显示缩放放大时图标纹理密度翻倍不发虚
  const image = await loadIconImageData(imageUrl, size * 2);
  if (!map.hasImage?.(imageId)) {
    map.addImage(imageId, image, { pixelRatio: 2 });
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
  ensureBusNetworkSource(map, RM_SOURCE_TRANSFER_SELECTED_LINE, emptyFeatureCollection());
  ensureBusNetworkSource(map, RM_SOURCE_STATION_THROUGH_LINES, emptyFeatureCollection());
  ensureBusNetworkSource(map, RM_SOURCE_STATION_HEAT, emptyFeatureCollection());
  ensureBusNetworkSource(map, RM_SOURCE_BASE_LINES, emptyFeatureCollection());
  ensureBusNetworkSource(map, RM_SOURCE_BASE_STATIONS, emptyFeatureCollection());
  ensureDisplayRangeLayer(map);

  // 站点客流热力图（连续密度晕染，色阶随图例齿轮设置联动；低密度透明，直接压在底图上）
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
        "heatmap-opacity": 0.9,
        "heatmap-color": stationHeatColorExpression.value,
      },
    });
  }

  // 灰色底图线路（默认隐藏，仅选中行政区且未选中线路时显示）：画触及本区线路的完整几何，
  // 铺在正常图层之下 —— 正常图层画的是区内裁剪结果，恰好盖住区内部分 → 区内彩色、区外灰色。
  // 线宽表达式与对应彩色层完全一致，跨区线路在分界处不会突然变粗细。
  if (!map.getLayer(RM_LAYER_BASE_LINES)) {
    addBusLayerBelowBuildings(map, {
      id: RM_LAYER_BASE_LINES,
      type: "line",
      source: RM_SOURCE_BASE_LINES,
      filter: BUS_LINE_FILTER,
      layout: { "line-join": "round", "line-cap": "round", visibility: "none" },
      paint: {
        "line-color": RM_BASE_NETWORK_COLOR,
        "line-opacity": RM_BASE_NETWORK_OPACITY,
        "line-width": lineFlowLayerWidthExpression(),
      },
    });
  }
  if (!map.getLayer(RM_LAYER_BASE_METRO_LINES)) {
    addBusLayerBelowBuildings(map, {
      id: RM_LAYER_BASE_METRO_LINES,
      type: "line",
      source: RM_SOURCE_BASE_LINES,
      filter: METRO_LINE_FILTER,
      layout: { "line-join": "round", "line-cap": "round", visibility: "none" },
      paint: {
        "line-color": RM_BASE_NETWORK_COLOR,
        "line-opacity": RM_BASE_NETWORK_OPACITY,
        "line-width": metroLineWidthExpression(),
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
  // 需求2：线路按全天客流着色的可见图层（与命中测试层同源；routePanel 就绪且未选中线路时显示）。
  // 不做白色描边（casing）；line-sort-key 让高客流档位后绘制、盖在低档位之上
  if (!map.getLayer(RM_LAYER_LINE_FLOW)) {
    addBusLayerBelowBuildings(map, {
      id: RM_LAYER_LINE_FLOW,
      type: "line",
      source: RM_SOURCE_LINES,
      filter: BUS_LINE_FILTER,
      layout: {
        "line-join": "round",
        "line-cap": "round",
        "line-sort-key": lineFlowSortKeyExpression.value || 0,
        visibility: "none",
      },
      paint: {
        "line-color": MAP_THEME.network.line,
        "line-opacity": 0.9,
        "line-width": lineFlowLayerWidthExpression(),
      },
    });
  }
  // 地铁线网：彩色粗线（按地铁自身客流分档着色）+ 白色短虚线叠加，铁路制式线形
  if (!map.getLayer(RM_LAYER_METRO_LINES)) {
    addBusLayerBelowBuildings(map, {
      id: RM_LAYER_METRO_LINES,
      type: "line",
      source: RM_SOURCE_LINES,
      filter: METRO_LINE_FILTER,
      layout: {
        "line-join": "round",
        "line-cap": "round",
        "line-sort-key": metroLineFlowSortKeyExpression.value || 0,
        visibility: "none",
      },
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
      layout: {
        "line-join": "round",
        // 与彩色地铁层同序：低档位线的白虚线不会压在高档位彩色线之上
        "line-sort-key": metroLineFlowSortKeyExpression.value || 0,
        visibility: "none",
      },
      paint: {
        "line-color": "#ffffff",
        "line-opacity": 0.9,
        "line-width": metroDashWidthExpression(),
        "line-dasharray": [2.2, 2.8],
      },
    });
  }
  // 灰色底图站点：触及本区线路的区外站点（区内站点由正常站点层绘制，此处已排除）
  if (!map.getLayer(RM_LAYER_BASE_STATIONS)) {
    map.addLayer({
      id: RM_LAYER_BASE_STATIONS,
      type: "symbol",
      source: RM_SOURCE_BASE_STATIONS,
      layout: { ...busStationIconLayout(RM_STATION_OUTSIDE_ICON_ID), visibility: "none" },
      paint: { "icon-opacity": 0.9 },
    });
  }
  // 站点客流着色圆点：先于图标层添加保证压在图标之下；着色激活时图标透明、圆点显色
  if (!map.getLayer(RM_LAYER_STATION_FLOW)) {
    map.addLayer({
      id: RM_LAYER_STATION_FLOW,
      type: "circle",
      source: RM_SOURCE_STATIONS,
      layout: { visibility: "none" },
      paint: {
        "circle-radius": stationFlowCircleRadius.value,
        "circle-color": stationFlowColorExpression.value || RM_STATION_FLOW_FALLBACK_COLOR,
        "circle-opacity": 0.95,
        "circle-stroke-color": "#ffffff",
        "circle-stroke-width": ["interpolate", ["linear"], ["zoom"], 10, 0.6, 14, 1.2],
        "circle-stroke-opacity": 0.9,
      },
    });
  }
  if (!map.getLayer(RM_LAYER_STATIONS)) {
    map.addLayer({
      id: RM_LAYER_STATIONS,
      type: "symbol",
      source: RM_SOURCE_STATIONS,
      layout: busStationIconLayout(),
      paint: { "icon-opacity": busStationOpacityPaint() },
    });
  }
  if (!map.getLayer(RM_LAYER_STATION_SEGMENT_RING)) {
    // 白底空心圈：描边取该站断面客流分档加深色；压在断面线之上（断面 deck 层以本层为 beforeId 锚），
    // 白底遮住站点处的颜色交界，低缩放档半径不小于断面线半宽（6.4px 线宽），保证站点始终盖线
    map.addLayer({
      id: RM_LAYER_STATION_SEGMENT_RING,
      type: "circle",
      source: RM_SOURCE_SEGMENT_STATIONS,
      layout: { visibility: "none" },
      paint: {
        "circle-radius": ["interpolate", ["linear"], ["zoom"], 10, 4, 13, 5.6, 16, 8],
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
        "line-opacity": PFA_RELATED_LINE_OPACITY,
        "line-width": ["interpolate", ["linear"], ["zoom"], 8, 1.4, 12, 2.4, 14, 3.4, 16, 4.4],
      },
    });
  }
  // 站点乘降模式：经过选中站点的全部线路（蓝色实线，样式与关联线路一致）
  if (!map.getLayer(RM_LAYER_STATION_THROUGH_LINES)) {
    addBusLayerBelowBuildings(map, {
      id: RM_LAYER_STATION_THROUGH_LINES,
      type: "line",
      source: RM_SOURCE_STATION_THROUGH_LINES,
      layout: { "line-join": "round", "line-cap": "round", visibility: "none" },
      paint: {
        "line-color": PFA_RELATED_LINE_COLOR,
        "line-opacity": 0.82,
        "line-width": ["interpolate", ["linear"], ["zoom"], 8, 1.4, 12, 2.4, 14, 3.4, 16, 4.4],
      },
    });
  }
  // 关联线路模式的选中线路本体：黄色高亮线（深琥珀描边垫底 + 亮黄主线），
  // 与蓝色关联线同插到建筑层之下，后添加者压在关联线之上
  if (!map.getLayer(RM_LAYER_TRANSFER_SELECTED_CASING)) {
    addBusLayerBelowBuildings(map, {
      id: RM_LAYER_TRANSFER_SELECTED_CASING,
      type: "line",
      source: RM_SOURCE_TRANSFER_SELECTED_LINE,
      layout: { "line-join": "round", "line-cap": "round", visibility: "none" },
      paint: {
        "line-color": PFA_TRANSFER_SELECTED_CASING_COLOR,
        "line-opacity": 0.88,
        "line-width": ["interpolate", ["linear"], ["zoom"], 8, 4.6, 12, 6, 14, 7.6, 16, 9.4],
      },
    });
  }
  if (!map.getLayer(RM_LAYER_TRANSFER_SELECTED_LINE)) {
    addBusLayerBelowBuildings(map, {
      id: RM_LAYER_TRANSFER_SELECTED_LINE,
      type: "line",
      source: RM_SOURCE_TRANSFER_SELECTED_LINE,
      layout: { "line-join": "round", "line-cap": "round", visibility: "none" },
      paint: {
        "line-color": PFA_TRANSFER_SELECTED_LINE_COLOR,
        "line-opacity": 0.98,
        "line-width": ["interpolate", ["linear"], ["zoom"], 8, 2.6, 12, 3.8, 14, 5.2, 16, 6.8],
      },
    });
  }
  // 关联线路模式的换乘站点：换乘图标（蓝色徽章双向箭头，替代原空心圈）
  if (!map.getLayer(RM_LAYER_TRANSFER_STATION_ICONS)) {
    map.addLayer({
      id: RM_LAYER_TRANSFER_STATION_ICONS,
      type: "symbol",
      source: RM_SOURCE_TRANSFER_STATIONS,
      layout: {
        "icon-image": RM_TRANSFER_STATION_ICON_ID,
        // 图标注册尺寸 96px：换算后显示直径约 16→32px，随缩放与原空心圈同节奏放大
        "icon-size": ["interpolate", ["linear"], ["zoom"], 10, 0.17, 13, 0.25, 16, 0.34],
        "icon-anchor": "center",
        "icon-allow-overlap": true,
        "icon-ignore-placement": true,
        "icon-padding": 2,
        visibility: "none",
      },
      paint: { "icon-opacity": 1 },
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
        // 换乘图标比原空心圈宽（高倍缩放下半宽约 16px），站名再外移半字避免压到图标
        "text-offset": [1.5, 0],
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
        // 与右侧面板「主站点」图例行同源
        "circle-color": hexToRgba(MAP_THEME.route.up, 0),
        "circle-stroke-color": MAP_THEME.route.up,
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
        // 与右侧面板「对侧站点」图例行同源，勿改回硬编码蓝
        "circle-color": hexToRgba(MAP_THEME.route.down, 0),
        "circle-stroke-color": MAP_THEME.route.down,
        "circle-stroke-width": ["interpolate", ["linear"], ["zoom"], 9, 1.2, 13, 2.4, 16, 4.2],
        "circle-opacity": 0.94,
        "circle-stroke-opacity": 0.94,
      },
    });
  }
  // 本轮可能有 addLayer 追加到栈顶（会盖到热力层之上），置顶标志复位、下轮 sync 重新置顶
  stationHeatLayerOnTop = false;
  applyBusNetworkPaint();
  syncBaseMapLayerVisibility();
}

function ensureDisplayRangeLayer(map = MapRef.value?.map) {
  if (!map) return;
  ensureBusNetworkSource(
    map,
    RM_SOURCE_DISPLAY_RANGE,
    districtOutlineFeatureCollection(activeDisplayRangeContext.value),
  );
  if (!map.getLayer(RM_LAYER_DISPLAY_RANGE_OUTLINE)) {
    const style = adminDistrictOutlineStyle();
    map.addLayer({
      id: RM_LAYER_DISPLAY_RANGE_OUTLINE,
      type: "line",
      source: RM_SOURCE_DISPLAY_RANGE,
      layout: style.layout,
      paint: style.paint,
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
// 把每个 zoom 档的基础宽度按"分档线宽系数"（客流越大越粗）乘算。
function widthStop(baseWidth, factorExpr) {
  return factorExpr ? ["*", baseWidth, factorExpr] : baseWidth;
}

function lineFlowLayerWidthExpression(factorExpr = null) {
  const width = busNetworkLineWidth.value;
  return [
    "interpolate",
    ["linear"],
    ["zoom"],
    8, widthStop(Math.max(0.6, width * 0.7), factorExpr),
    11, widthStop(Math.max(1, width * 1.3), factorExpr),
    14, widthStop(Math.max(1.8, width * 2.4), factorExpr),
    16, widthStop(Math.max(2.6, width * 3.4), factorExpr),
  ];
}

// 地铁线宽：明显粗于公交细线，配合白色虚线叠加构成铁路制式线形
function metroLineWidthExpression(factorExpr = null) {
  const width = busNetworkLineWidth.value;
  return [
    "interpolate",
    ["linear"],
    ["zoom"],
    8, widthStop(Math.max(1.5, width * 1.7), factorExpr),
    11, widthStop(Math.max(2.4, width * 2.8), factorExpr),
    14, widthStop(Math.max(4, width * 4.6), factorExpr),
    16, widthStop(Math.max(5.6, width * 6), factorExpr),
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

// 把 lineId→颜色 / lineId→分档线宽系数 / lineId→档位 sort-key 应用到公交/地铁客流着色图层。
// 彩色线按分档系数变粗（客流越大越粗），高档位线路后绘制、盖在低档位之上。
function applyLineFlowLayerStyles(map) {
  if (!map) return;
  const busFactor = lineFlowWidthFactorExpression.value;
  const metroFactor = metroLineWidthFactorExpression.value;
  if (map.getLayer?.(RM_LAYER_LINE_FLOW)) {
    if (lineFlowColorExpression.value) map.setPaintProperty(RM_LAYER_LINE_FLOW, "line-color", lineFlowColorExpression.value);
    map.setPaintProperty(RM_LAYER_LINE_FLOW, "line-width", lineFlowLayerWidthExpression(busFactor));
    if (lineFlowSortKeyExpression.value) map.setLayoutProperty(RM_LAYER_LINE_FLOW, "line-sort-key", lineFlowSortKeyExpression.value);
  }
  if (map.getLayer?.(RM_LAYER_METRO_LINES)) {
    map.setPaintProperty(RM_LAYER_METRO_LINES, "line-color", metroLineFlowColorExpression.value || METRO_FALLBACK_LINE_COLOR);
    map.setPaintProperty(RM_LAYER_METRO_LINES, "line-width", metroLineWidthExpression(metroFactor));
    if (metroLineFlowSortKeyExpression.value) map.setLayoutProperty(RM_LAYER_METRO_LINES, "line-sort-key", metroLineFlowSortKeyExpression.value);
  }
  if (map.getLayer?.(RM_LAYER_METRO_LINE_DASH)) {
    map.setPaintProperty(RM_LAYER_METRO_LINE_DASH, "line-width", metroDashWidthExpression());
    if (metroLineFlowSortKeyExpression.value) map.setLayoutProperty(RM_LAYER_METRO_LINE_DASH, "line-sort-key", metroLineFlowSortKeyExpression.value);
  }
  // 灰色底图与对应彩色层同宽同分档系数：跨区线路在行政区边界处只换色、不换粗细
  if (map.getLayer?.(RM_LAYER_BASE_LINES)) {
    map.setPaintProperty(RM_LAYER_BASE_LINES, "line-width", lineFlowLayerWidthExpression(busFactor));
  }
  if (map.getLayer?.(RM_LAYER_BASE_METRO_LINES)) {
    map.setPaintProperty(RM_LAYER_BASE_METRO_LINES, "line-width", metroLineWidthExpression(metroFactor));
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
  if (map.getLayer(RM_LAYER_BASE_STATIONS)) {
    map.setLayoutProperty(RM_LAYER_BASE_STATIONS, "icon-size", busNetworkStationIconScale.value);
  }
  if (map.getLayer(RM_LAYER_STATION_FLOW)) {
    map.setPaintProperty(RM_LAYER_STATION_FLOW, "circle-radius", stationFlowCircleRadius.value);
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

// 关联线路模式：地图显示黄色选中线路本体 + 蓝色关联线路 + 换乘图标站点
function isPfaTransferSectionActive() {
  return props.mode === "pfa"
    && effectiveTab.value === "线路客流监测"
    && pfaLineSection.value === "transfer";
}

// 站点乘降/客流画像模式（站点客流监测子模块）：选中站点后地图加画经过该站的全部线路
const PFA_STATION_THROUGH_LINE_SECTIONS = ["boarding", "demographics"];
function isPfaStationThroughLineSectionActive() {
  return props.mode === "pfa"
    && effectiveTab.value === "站点客流监测"
    && PFA_STATION_THROUGH_LINE_SECTIONS.includes(pfaStationSection.value);
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
  // 选中站点时隐一切底图站点；客流着色激活时图标透明让位彩色圆点（图层保持可见承担点击命中）
  if (isStationFeatureSelectionActive()) return 0;
  return stationFlowColoringActive.value ? 0 : 0.96;
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
  setBusLayerFilter(map, RM_LAYER_STATION_FLOW, filter);
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
  // 线路客流监测：地图只显示线路；站点客流监测：只显示站点；总体客流监测不显示站点；车辆运行监测两者都不显示。
  const tab = effectiveTab.value;
  const isVehicleTab = tab === "车辆运行监测" || tab === "轨迹演示";
  // 人口/公交出行专题图与客流走廊自绘图层：公共公交、地铁线网及站点一并退场。
  const isThematicMapTab = hidesTransitNetwork(tab);
  // 体检评估分析与总体客流监测一样：地图给出按客流着色的线网底图（含行政区裁剪与区外灰底）
  const isLinesTab = !isVehicleTab && !isThematicMapTab && tab !== "站点客流监测";
  const showLines = showBusNetwork && isLinesTab;
  const showMetroLines = showMetroNetwork && isLinesTab;
  // 断面客流/站点乘降：随选中线路显示当前方向的站点（空心圈）与站名，经 applySelectedLineStationFilter 过滤
  const segmentStationsActive = showTransitNetwork && isPfaSegmentSectionActive() && isPfaLineSelectionActive();
  // 关联线路模式：只显示关联线路与换乘站点
  const transferStationsActive = showTransitNetwork && isPfaTransferSectionActive() && isPfaLineSelectionActive();
  // 站点客流热力图开启时按专题图口径展示：隐藏公交/地铁线网与站点
  const stationHeatActive = props.mode === "pfa" && tab === "站点客流监测" && stationHeatmapEnabled.value;
  const showStations = showTransitNetwork
    && !isVehicleTab
    && tab === "站点客流监测"
    && !stationHeatActive;
  const hideBaseLines = isLineSelectionActive();
  setBusLayerVisibility(map, RM_LAYER_LINES, (showLines || showMetroLines) && !hideBaseLines);
  // 命中测试层跟随当前制式过滤，点选只命中当前显示的线网
  setBusLayerFilter(map, RM_LAYER_LINES, showMetroNetwork ? METRO_LINE_FILTER : BUS_LINE_FILTER);
  // 需求2：routePanel 就绪后底图线路改用客流着色图层。
  // 未就绪时不要显示默认蓝色瓦片线网，避免刷新首屏闪一下“未上色线网”。
  const lineFlowColoringActive = showLines && !hideBaseLines && Boolean(lineFlowColorExpression.value);
  setBusLayerVisibility(map, RM_LAYER_LINE_FLOW, lineFlowColoringActive);
  // 地铁线网（几何来自模型线路整包，直接显示，不依赖瓦片）
  const metroLinesActive = showMetroLines && !hideBaseLines && Boolean(metroLineFlowColorExpression.value);
  setBusLayerVisibility(map, RM_LAYER_METRO_LINES, metroLinesActive);
  setBusLayerVisibility(map, RM_LAYER_METRO_LINE_DASH, metroLinesActive);
  // 区外灰底只在"选中了行政区、且当前是默认整网视图"时出现：
  // 选中线路后线网整体让位给高亮线路，灰底跟随彩色层一起隐藏（选中态显示逻辑保持原样）
  const districtActive = Boolean(activeDisplayRangeContext.value);
  setBusLayerVisibility(map, RM_LAYER_BASE_LINES, districtActive && lineFlowColoringActive);
  setBusLayerVisibility(map, RM_LAYER_BASE_METRO_LINES, districtActive && metroLinesActive);
  setBusLayerVisibility(map, RM_LAYER_BASE_STATIONS, districtActive && showStations && !isStationFeatureSelectionActive());
  setBusLayerFilter(map, RM_LAYER_BASE_STATIONS, stationModeFilterExpression());
  [RM_LAYER_STATIONS, RM_LAYER_STATION_SELECTED, RM_LAYER_STATION_REVERSE_SELECTED, RM_LAYER_STATION_SELECTED_HALO, RM_LAYER_STATION_REVERSE_SELECTED_HALO].forEach((layerId) => {
    setBusLayerVisibility(map, layerId, showStations);
  });
  // 站点客流着色圆点：仅未选中态显示（选中/热力/数据未就绪时回退原图标）
  setBusLayerVisibility(map, RM_LAYER_STATION_FLOW, showStations && stationFlowColoringActive.value);
  setBusLayerVisibility(map, RM_LAYER_STATION_SEGMENT_RING, segmentStationsActive);
  setBusLayerVisibility(map, RM_LAYER_SEGMENT_STATION_LABELS, segmentStationsActive);
  setBusLayerVisibility(map, RM_LAYER_TRANSFER_LINES, transferStationsActive);
  setBusLayerVisibility(map, RM_LAYER_TRANSFER_SELECTED_CASING, transferStationsActive);
  setBusLayerVisibility(map, RM_LAYER_TRANSFER_SELECTED_LINE, transferStationsActive);
  setBusLayerVisibility(map, RM_LAYER_TRANSFER_STATION_ICONS, transferStationsActive);
  setBusLayerVisibility(map, RM_LAYER_TRANSFER_STATION_LABELS, transferStationsActive);
  // 站点乘降/客流画像：经过选中站点的线路（选中站点 + 对应子模块 + 非热力专题时显示）
  const stationThroughLinesActive = showTransitNetwork
    && isPfaStationThroughLineSectionActive()
    && Boolean(selectedStationName.value)
    && !stationHeatActive;
  setBusLayerVisibility(map, RM_LAYER_STATION_THROUGH_LINES, stationThroughLinesActive);
  setBusLayerVisibility(map, RM_LAYER_STATION_HEAT, stationHeatActive);
  // 热力图要盖在站点等所有要素之上：激活时把热力层移到图层栈顶。
  // moveLayer 即使位置不变也会标脏样式层序，用标志位保证激活期间只移一次
  if (stationHeatActive) {
    if (!stationHeatLayerOnTop) {
      if (map.getLayer(RM_LAYER_STATION_HEAT)) map.moveLayer(RM_LAYER_STATION_HEAT);
      stationHeatLayerOnTop = true;
    }
  } else {
    stationHeatLayerOnTop = false;
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

// 区外灰底集合：触及本区的线路（完整几何）+ 这些线路的区外站点。
// 全为集合级运算——"触及"直接读裁剪结果里剩下的 _lineKey，区内/区外判定复用已算好的裁剪与站点过滤，
// 不再做任何逐点多边形测试。按 (模型, 行政区, 数据修订, 上下文版本) 记忆化。
const EMPTY_BASE_NETWORK = { lines: emptyFeatureCollection(), stations: emptyFeatureCollection() };
const baseNetworkMemo = new Map();

function baseNetworkCollectionsFor(clippedLines, context) {
  if (!context || !clippedLines) return EMPTY_BASE_NETWORK;
  const memoKey = `${selectModel.value?.name || ""}::${context.name}::${busNetworkRevision.value}::${displayRangeContextRev}`;
  const cached = baseNetworkMemo.get(memoKey);
  if (cached) return cached;

  const touchingKeys = new Set();
  for (const feature of clippedLines.features || []) {
    const key = String(feature?.properties?._lineKey || "");
    if (key) touchingKeys.add(key);
  }
  const lines = {
    type: "FeatureCollection",
    features: (busNetworkCollections.lines?.features || [])
      .filter((feature) => touchingKeys.has(String(feature?.properties?._lineKey || ""))),
  };

  // 区内站点由正常站点层绘制，灰点里排除，避免同一个站被灰点盖住
  const inRangeStationKeys = new Set(
    (stationCollectionForDisplayRange(context).features || [])
      .map((feature) => String(feature?.properties?._stationKey || "")),
  );
  const outsideStationKeys = new Set();
  for (const line of busNetworkRawLines) {
    const lineId = line?.lineId != null ? String(line.lineId) : "";
    (line?.routes || []).forEach((route, idx) => {
      // 必须与 buildModelLineFeatureCollection 的 _lineKey 逐字一致（routeId 为 0 时两边的口径不同）
      const routeId = route?.routeId != null ? String(route.routeId) : "";
      if (!touchingKeys.has(`${lineId}-${routeId || idx}`)) return;
      for (const facility of route?.facilities || []) {
        // 与 buildModelStationFeatureCollection 同一套站点键：优先 facilityId，缺失时退回站名
        const key = facility?.facilityId != null ? String(facility.facilityId) : String(facility?.facilityName || "");
        if (key && !inRangeStationKeys.has(key)) outsideStationKeys.add(key);
      }
    });
  }
  const stations = {
    type: "FeatureCollection",
    features: (busNetworkCollections.stations?.features || [])
      .filter((feature) => outsideStationKeys.has(String(feature?.properties?._stationKey || ""))),
  };

  const result = { lines, stations };
  baseNetworkMemo.set(memoKey, result);
  while (baseNetworkMemo.size > 12) {
    baseNetworkMemo.delete(baseNetworkMemo.keys().next().value);
  }
  return result;
}

function applyClippedLineCollection(collection, context) {
  setGeoJsonSourceData(RM_SOURCE_LINES, collection);
  const base = baseNetworkCollectionsFor(collection, context);
  setGeoJsonSourceData(RM_SOURCE_BASE_LINES, base.lines);
  setGeoJsonSourceData(RM_SOURCE_BASE_STATIONS, base.stations);
  syncBaseMapLayerVisibility();
}

let busNetworkClipToken = 0;

// 线要素裁剪下沉 Worker：主线程不再对全网线要素逐段做多边形裁剪（原选区冻结的另一半）；
// Worker 端按 (model, district, revision) 记忆化，重复切换同一行政区即刻返回
function applyBusNetworkLineClip(context) {
  const token = ++busNetworkClipToken;
  if (!context) {
    applyClippedLineCollection(busNetworkCollections.lines, null);
    return;
  }
  const modelName = selectModel.value?.name || "";
  const networkState = displayRangeNetworkState(modelName, busNetworkRawModel, busNetworkRawLines);
  if (networkState === "pending") return;
  if (networkState === "empty") {
    applyClippedLineCollection(emptyFeatureCollection(), context);
    reportDisplayRangeError(null, "当前数据源没有可用于行政区筛选的线路");
    return;
  }
  void warmDisplayRangeWorker(modelName)
    .then(() => postDisplayRangeWorker({
      type: "clipLines",
      seq: ++displayRangeMsgSeq,
      model: modelName,
      ...displayRangeContextPayload(context),
    }))
    .then((msg) => {
      if (token !== busNetworkClipToken) return;
      if (!msg?.ok || !msg.collection) throw new Error("clip result unavailable");
      applyClippedLineCollection(msg.collection, context);
      clearDisplayRangeError();
    })
    .catch((error) => {
      if (token !== busNetworkClipToken) return;
      reportDisplayRangeError(error, "行政区线网裁剪失败");
    });
}

function syncBusNetworkDisplayRange() {
  const context = activeDisplayRangeContext.value;
  ensureDisplayRangeLayer();
  setGeoJsonSourceData(RM_SOURCE_DISPLAY_RANGE, districtOutlineFeatureCollection(context));
  setBusLayerVisibility(MapRef.value?.map, RM_LAYER_DISPLAY_RANGE_OUTLINE, Boolean(context));
  applyBusNetworkLineClip(context);
  setGeoJsonSourceData(RM_SOURCE_STATIONS, stationCollectionForDisplayRange(context));
  if (monitorBusRouteLayer && selectModel.value?.name && shouldLoadTransitNetworkForCurrentTab()) {
    monitorBusRouteLayer.setLineClipContext(context);
    if (!isRealDatasource(selectModel.value.name)
        && (!monitorBusRouteLayer.tileMode || monitorBusRouteLayer.datasource !== selectModel.value.name)) {
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

function busNetworkLoadStillCurrent(seq, modelName) {
  return seq === busNetworkRequestSeq
    && pageActive.value
    && isModelReady.value
    && selectModel.value?.name === modelName
    && shouldLoadTransitNetworkForCurrentTab();
}

async function loadBusNetwork() {
  if (!pageActive.value || !MapRef.value?.map || !isModelReady.value || !selectModel.value?.name || !shouldLoadTransitNetworkForCurrentTab()) return;
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
    if (!busNetworkLoadStillCurrent(seq, modelName)) return;
    const lines = Array.isArray(lineRes) ? lineRes : [];
    const facilities = Array.isArray(facilityRes) ? facilityRes : [];
    busNetworkRawLines = lines;
    busNetworkRawFacilities = facilities;
    busNetworkRawModel = modelName;
    busNetworkRevision.value += 1;
    busNetworkCollections = {
      lines: buildModelLineFeatureCollection(lines),
      stations: buildModelStationFeatureCollection(facilities, collectMetroFacilityKeys(lines)),
    };
    busNetworkIndexes = buildBusNetworkIndexes(lines, busNetworkCollections);
    // 行政区 Worker 预热：空闲期打包全网坐标常驻 Worker，用户第一次选行政区即秒回
    runWhenIdle(() => {
      if (busNetworkLoadStillCurrent(seq, modelName)) {
        void warmDisplayRangeWorker(modelName)
          .then((ready) => {
            if (ready) clearDisplayRangeError();
          })
          .catch((error) => {
            reportDisplayRangeError(error, "行政区筛选线程预热失败");
          });
      }
    });
    const map = MapRef.value?.map;
    if (!map) return;
    ensureBusNetworkSource(map, RM_SOURCE_LINES, busNetworkCollections.lines);
    ensureBusNetworkSource(map, RM_SOURCE_STATIONS, busNetworkCollections.stations);
    await ensureBusStationIcons(map);
    // 图标加载期间可能已换功能/模型/页面：过期则不再把图层加回共享地图。
    if (!busNetworkLoadStillCurrent(seq, modelName)) return;
    ensureBusNetworkLayers(map);
    // 断面 deck 图层锚定在站点空心圈之下；圈层此刻可能刚（重）建，触发一次重提交完成归位
    monitorSelectedRouteSegmentLayer?.updatePaint?.();
    monitorReverseRouteSegmentLayer?.updatePaint?.();
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
    // 与主线同锚站点圈层之下：乘降模式该层复用为选中线路本体，站点珠子须盖线（见下）
    beforeId: RM_LAYER_STATION_SEGMENT_RING,
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
    // 线让位站点圈（与断面层同锚）：乘降模式本层画选中线路蓝线，XLZL 的站间 OD
    // 站点圈/曲线为 MapLibre 层且位于锚点之上；deck 层缩放换档重挂时不再顶到栈顶盖住站点
    beforeId: RM_LAYER_STATION_SEGMENT_RING,
    color: hexNumber(MAP_THEME.route.down),
    opacity: 0.88,
  });
  monitorSelectedRouteSegmentLayer = new RouteLayer({
    zIndex: 1001,
    lineWidth: selectedSegmentLineWidth(busNetworkLineWidth.value) * 10,
    fixedPixelWidth: true,
    // 二进制转换仍走 worker（deckData 就绪才渲染）；拼路径按同色档分组缓存，单线量级为毫秒开销
    workerEnabled: true,
    // 连续 PathLayer（圆角接头/端头）：逐链路 LineLayer 在拐弯处会露出豁口，整线看起来像碎段拼接；
    // 分组按分档色切断，颜色恰好在断面交界（站点）处切换，交界点由上方站点空心圈盖住
    continuousPath: true,
    // 断面线让位站点空心圈：锚定在圈层之下，保证站点盖线、白底可见
    beforeId: RM_LAYER_STATION_SEGMENT_RING,
    flowControl: true,
    flowWidthStep: 2,
    widthMaxPixels: 26,
    flowStyleStops: pfaSegmentFlowStops.value,
    opacity: 1,
    // 单条选中线路：关闭随缩放的透明度衰减，避免中低缩放级别下线体发虚
    zoomFadeOpacity: false,
  });
  // 需求11：下行断面客流图层（与上行同一套色阶 stops）
  monitorReverseRouteSegmentLayer = new RouteLayer({
    zIndex: 1000.8,
    lineWidth: selectedSegmentLineWidth(busNetworkLineWidth.value) * 10,
    fixedPixelWidth: true,
    workerEnabled: true,
    continuousPath: true,
    beforeId: RM_LAYER_STATION_SEGMENT_RING,
    flowControl: true,
    flowWidthStep: 2,
    widthMaxPixels: 26,
    flowStyleStops: pfaSegmentFlowStops.value,
    opacity: 1,
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
  addPageMapLayer(monitorBusRouteLayer);
  // Deck 图层按 zIndex 绘制：背景 -> 关联线路 -> 原高亮线 -> 断面客流覆盖层。
  addPageMapLayer(monitorSelectedRouteGlowLayer);
  addPageMapLayer(monitorTransferRouteGlowLayer);
  addPageMapLayer(monitorTransferRouteLayer);
  addPageMapLayer(monitorReverseRouteGlowLayer);
  addPageMapLayer(monitorReverseRouteLayer);
  addPageMapLayer(monitorSelectedRouteLayer);
  addPageMapLayer(monitorReverseRouteSegmentLayer);
  addPageMapLayer(monitorSelectedRouteSegmentLayer);
  monitorSelectedRouteGlowLayer.setData([]);
  monitorTransferRouteGlowLayer.setData([]);
  monitorTransferRouteLayer.setData([]);
  monitorReverseRouteGlowLayer.setData([]);
  monitorReverseRouteLayer.setData([]);
  monitorSelectedRouteLayer.setData([]);
  monitorSelectedRouteSegmentLayer.setData([]);
  monitorReverseRouteSegmentLayer.setData([]);
  if (!isRealDatasource(selectModel.value.name)) {
    monitorBusRouteLayer.setTileSource(selectModel.value.name, { tileRequest: getRouteTileBinary });
  }
  syncBusNetworkDisplayRange();
  syncBaseMapLayerVisibility();
}

function selectedSegmentLineWidth(baseWidth) {
  // 断面层必须跟随设置中的线宽连续变化。原先 Math.max(6.4, ...)
  // 在滑块 0.1–2 的整个范围内始终得到 6.4，导致控件看起来完全失效。
  // 这里保留约 4px 的可读性基线，再直接叠加滑块值，确保最小档到最大档
  // 每一步都有反馈；站点空心圈仍以 6.4px 为下限，可以继续完整盖住断面线。
  const sliderWidth = Math.max(0.1, Math.min(2, Number(baseWidth) || 1.2));
  return 4 + sliderWidth * 1.08;
}

function syncMonitorRouteLineWidths() {
  const baseWidth = busNetworkLineWidth.value;
  const selectedWidth = Math.max(4, baseWidth + 3.6);
  const segmentWidth = selectedSegmentLineWidth(baseWidth);
  monitorBusRouteLayer?.setLineWidth(baseWidth * 10);
  monitorSelectedRouteGlowLayer?.setLineWidth(selectedWidth * 2.2 * 10);
  monitorSelectedRouteLayer?.setLineWidth(selectedWidth * 10);
  monitorSelectedRouteSegmentLayer?.setLineWidth(segmentWidth * 10);
  monitorReverseRouteSegmentLayer?.setLineWidth(segmentWidth * 10);
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
  // 关联线路模式：选中线路本体改由黄色 MapLibre 高亮线（RM_LAYER_TRANSFER_SELECTED_LINE）
  // 绘制，deck 橙/蓝方向线清空退场
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
    return { lines: [], selectedLines: [], sharedStations: new Set() };
  }
  const { stationNames, lineIds } = selectedLineTransferContext();
  if (!stationNames.size) return { lines: [], selectedLines: [], sharedStations: new Set() };
  const sharedStations = new Set();
  const scored = [];
  // 选中线路自身（地铁整线含各分段子线路）：单独收集，供黄色高亮线绘制本体
  const selectedLines = [];
  for (const line of busNetworkRawLines) {
    const lineId = String(line?.lineId || "");
    if (lineId && lineIds.has(lineId)) {
      selectedLines.push(line);
      continue;
    }
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
  return { lines: scored.map((item) => item.line), selectedLines, sharedStations };
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

// 站点乘降/客流画像：经过选中站点的全部线路（按站名匹配物理站——ZDZL 上抛的选中站名即合并
// 物理站口径，含对向站点；按当前制式过滤，公交站配公交线、地铁站配地铁线）
function refreshStationThroughLines() {
  if (!MapRef.value?.map) return;
  const stationName = String(selectedStationName.value || "").trim();
  if (!(isPfaStationThroughLineSectionActive() && stationName)) {
    setGeoJsonSourceData(RM_SOURCE_STATION_THROUGH_LINES, EMPTY_FEATURE_COLLECTION);
    return;
  }
  const wantMetro = baseMapLineMode.value === "metro-network";
  const lines = busNetworkRawLines.filter((line) => {
    if (isMetroLine(line) !== wantMetro) return false;
    return (Array.isArray(line?.routes) ? line.routes : []).some((route) =>
      (Array.isArray(route?.facilities) ? route.facilities : []).some(
        (facility) => String(facility?.facilityName || "").trim() === stationName
      )
    );
  });
  setGeoJsonSourceData(RM_SOURCE_STATION_THROUGH_LINES, buildModelLineFeatureCollection(lines));
}

// 触发面：选中站名（搜索/点图两条路径都经 ZDZL 上抛）、子模块切换、制式切换、
// 线网数据到位（busNetworkRevision，选中先于数据时补画）、换模型/页签
let stationThroughSyncScheduled = false;
let stationThroughWasActive = false;
watch(
  [selectedStationName, pfaStationSection, effectiveTab, baseMapLineMode, busNetworkRevision, () => selectModel.value?.name],
  () => {
    if (stationThroughSyncScheduled) return;
    stationThroughSyncScheduled = true;
    queueMicrotask(() => {
      stationThroughSyncScheduled = false;
      const active = isPfaStationThroughLineSectionActive() && Boolean(String(selectedStationName.value || "").trim());
      if (!active && !stationThroughWasActive) return;
      stationThroughWasActive = active;
      refreshStationThroughLines();
      syncBaseMapLayerVisibility();
    });
  },
);

function refreshPfaTransferRouteLinks() {
  // deck 关联线路图层弃用（会串接多条线的链路致线形错乱），统一清空
  setMonitorTransferRouteLinks([]);
  if (!(isPfaTransferSectionActive() && isPfaLineSelectionActive())) {
    renderPfaTransferStations(null);
    setGeoJsonSourceData(RM_SOURCE_TRANSFER_LINES, EMPTY_FEATURE_COLLECTION);
    setGeoJsonSourceData(RM_SOURCE_TRANSFER_SELECTED_LINE, EMPTY_FEATURE_COLLECTION);
    return;
  }
  const { lines, selectedLines, sharedStations } = pfaTransferData();
  renderPfaTransferStations(sharedStations);
  // 每条关联线路一条 LineString（buildModelLineFeatureCollection 已按 route 拆分），蓝色实线
  setGeoJsonSourceData(RM_SOURCE_TRANSFER_LINES, buildModelLineFeatureCollection(lines));
  // 选中线路本体：黄色高亮线（同一几何管线，保证与蓝色关联线线形口径一致）
  setGeoJsonSourceData(RM_SOURCE_TRANSFER_SELECTED_LINE, buildModelLineFeatureCollection(selectedLines));
}

function handleBaseMapLineModeChange(mode) {
  const nextMode = mode === "metro-network" ? "metro-network" : "bus-network";
  if (baseMapLineMode.value === nextMode) return;
  baseMapLineMode.value = nextMode;
  reconcilePfaSelectionForBaseMapMode(nextMode);
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
  const switchSeq = featureSwitchSeq;
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
  if (switchSeq !== featureSwitchSeq || effectiveTab.value !== "线路客流监测") return;
  // 复用数据管理“按被点中的具体要素”选中的方式：把点中线路要素的属性（含方向 dir / route_id）交给 XLZL，按方向精确选中
  if (typeof lineMonitorRef.value?.selectLineByFeature === "function") {
    await lineMonitorRef.value.selectLineByFeature(props);
  } else {
    await lineMonitorRef.value?.selectLineByName?.(name);
  }
}

async function selectStationFromBusNetwork(feature) {
  const switchSeq = featureSwitchSeq;
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
  if (switchSeq !== featureSwitchSeq || effectiveTab.value !== "站点客流监测") return;
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
  if (!pageActive.value) return;
  closeLineRoutePicker();
  // 公交/地铁线网模式都可点选，命中层已按制式过滤。
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
    RM_LAYER_TRANSFER_SELECTED_CASING,
    RM_LAYER_TRANSFER_SELECTED_LINE,
    RM_LAYER_TRANSFER_STATION_ICONS,
    RM_LAYER_TRANSFER_STATION_LABELS,
    RM_LAYER_STATION_THROUGH_LINES,
    RM_LAYER_STATION_HEAT,
    RM_LAYER_STATION_LABELS,
    RM_LAYER_STATIONS,
    RM_LAYER_LINE_FLOW,
    RM_LAYER_METRO_LINE_DASH,
    RM_LAYER_METRO_LINES,
    RM_LAYER_LINES,
    RM_LAYER_BASE_STATIONS,
    RM_LAYER_BASE_METRO_LINES,
    RM_LAYER_BASE_LINES,
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
    RM_SOURCE_TRANSFER_SELECTED_LINE,
    RM_SOURCE_STATION_THROUGH_LINES,
    RM_SOURCE_STATION_HEAT,
    RM_SOURCE_STATIONS,
    RM_SOURCE_LINES,
    RM_SOURCE_BASE_STATIONS,
    RM_SOURCE_BASE_LINES,
  ].forEach((sourceId) => {
    if (map.getSource?.(sourceId)) map.removeSource(sourceId);
  });
  busNetworkSourceRefs = new Map();
  stationHeatLayerOnTop = false;
  busNetworkCollections = {
    lines: emptyFeatureCollection(),
    stations: emptyFeatureCollection(),
  };
  busNetworkIndexes = createEmptyBusNetworkIndexes();
  busNetworkRawLines = [];
  busNetworkRawFacilities = [];
  busNetworkRawModel = "";
  busNetworkRevision.value += 1;
}

const minLineWidth = computed(() => 0.1);
const maxLineWidth = computed(() => 2);
const minStationSize = computed(() => 16);
const maxStationSize = computed(() => 48);
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
provide("LinkSpeedEnabledRef", linkSpeedEnabled);
provide("LinkSpeedOpacityRef", linkSpeedOpacity);
provide("LinkSpeedStatusRef", linkSpeedStatus);

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
  if (!pageActive.value) return;
  if (event.key !== "Escape") return;
  showLineWidthPopover.value = false;
  showRangePopover.value = false;
  showLineFlowScalePopover.value = false;
  showSegmentFlowScalePopover.value = false;
  showStationHeatScalePopover.value = false;
  showStationFlowScalePopover.value = false;
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
    nextTick(setMapCenter);
  } catch (error) {
    if (seq !== displayRangeRequestSeq) return;
    displayRangeError.value = error?.message || "行政区范围加载失败";
    throw error;
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
      void loadDisplayRanges({ force: Boolean(displayRangeError.value) }).catch((error) => {
        displayRangeError.value = error?.message || "行政区范围加载失败";
      });
    }
  }
  showRangePopover.value = !showRangePopover.value;
}

function selectDisplayRange(rangeName) {
  const nextRange = String(rangeName || "").trim();
  if (!nextRange) return;
  if (nextRange === selectedDisplayRange.value && nextRange !== DISPLAY_RANGE_ALL) {
    selectedDisplayRange.value = DISPLAY_RANGE_ALL;
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
        // 运行监测/客流分析的线路图层使用 fixedPixelWidth，内部约定值为
        // “实际像素 × 10”；不能用通用的模型线宽覆盖，否则拖动时会写入错误单位。
        if (
          layer === monitorBusRouteLayer
          || layer === monitorSelectedRouteGlowLayer
          || layer === monitorSelectedRouteLayer
          || layer === monitorSelectedRouteSegmentLayer
          || layer === monitorReverseRouteSegmentLayer
          || layer === monitorReverseRouteGlowLayer
          || layer === monitorReverseRouteLayer
          || layer === monitorTransferRouteGlowLayer
          || layer === monitorTransferRouteLayer
        ) return;
        layer.setLineWidth(computedLineWidth.value);
      }
    });
  }
  syncMonitorRouteLineWidths();
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
  if (!pageActive.value) return;
  applyLineWidth();
  applyFlowWidthStep();
  applyFlowControl();
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
    if (!pageActive.value) {
      syncLayersRetryTimer = null;
      return;
    }
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

function handleStationSizeChange(val) {
  stationSize.value = val;
  applyStationSize();
}

function handleVehicleSizeChange(val) {
  vehicleSize.value = val;
  applyVehicleSize();
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
      if (!pageActive.value) return;
      lastMapMotionAt = performance.now();
      mapZoom.value = e.data;
    });
    centerListenerId = mapInstance.addEventListener("update:center", () => {
      lastMapMotionAt = performance.now();
    });
    rotateListenerId = mapInstance.addEventListener("update:camera:rotate", (e) => {
      if (!pageActive.value) return;
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
  if (mode !== "bus-network" && mode !== "metro-network") {
    baseMapLineMode.value = "bus-network";
    return;
  }
  reconcilePfaSelectionForBaseMapMode(mode);
  syncBaseMapLayerVisibility();
});

// 持久化由 displayRange store 统一负责；此处只处理本页联动副作用
watch(selectedDisplayRange, () => {
  closeLineRoutePicker();
  clearLineSelection();
  clearStationSelection();
  syncBusNetworkDisplayRange();
  nextTick(setMapCenter);
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
        if (!isRealDatasource(modelName)) {
          monitorBusRouteLayer.setTileSource(modelName, { tileRequest: getRouteTileBinary });
        }
      } else {
        ensureMonitorBusRouteLayer();
      }
      loadBusNetwork();
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
  void Promise.all([
    handleGetSchemeList({ silent: true }),
    handleGetModelList({ silent: true }),
  ]).catch((error) => {
    loadError.value = error?.message || "方案或模型列表刷新失败";
  });
}

// 仅当有模型处于过渡态（排队/加载/建缓存）时保持 20s 轮询；
// 全部稳定时降频为 100s 心跳（仅为感知他人操作），原先无条件每 20s 双接口约 360 次/小时
function hasTransitionalModels() {
  return (modelList.value || []).some((item) =>
    item?.loadStage === "queued"
    || item?.loadStage === "loading_config"
    || item?.cacheStatus === "queued"
    || item?.cacheStatus === "building");
}
let schemePollTick = 0;
const ins = setInterval(() => {
  // 页面不可见或本页失活（KeepAlive 缓存中）时跳过本轮轮询；激活时会立即补一次刷新
  if (document.visibilityState === "hidden") return;
  if (!pageActive.value) return;
  schemePollTick += 1;
  if (!hasTransitionalModels() && schemePollTick % 5 !== 0) return;
  refreshSchemeAndModelLists();
}, 1000 * 20);

function handlePollingVisibilityChange() {
  // 恢复可见时立即补一次刷新，弥补隐藏期间被跳过的轮询
  if (document.visibilityState === "visible" && pageActive.value) {
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

// ===== KeepAlive 激活/失活协同 =====
// 本页被 MapLayout KeepAlive 缓存：失活期间实例与图层均存活（图层由 MapLayout 暂存摘图），
// 但共享地图正被其他页面使用，交互回调与轮询需按激活态短路。
const pageActive = ref(true);
let hasBeenDeactivated = false;
// 失活期间异步完成的建层（如后台换模就绪）不能直接上共享地图，先入队，激活时统一补挂
const pendingLayerAdds = [];

function syncDistributionBuildingSuppression() {
  const isDistributionGrid = activeTab.value === "人口分布监测"
    || (activeTab.value === "公交出行监测" && busTravelSection.value === "出行分布监测");
  const shouldSuppress = pageActive.value && is3DActive.value && isDistributionGrid;
  const buildingLayer = MapRef.value?.layers?.find((layer) => layer?.name === "CityBuildingsLayer");
  buildingLayer?.setSuppressed?.(shouldSuppress);
}

watch(
  [pageActive, is3DActive, activeTab, busTravelSection, MapRef],
  syncDistributionBuildingSuppression,
  { immediate: true },
);

watch(
  [() => props.mode, isModelReady, pageActive, runMonitorOnboardingHostMounted],
  ([mode]) => {
    if (mode === "pfa" || !pageActive.value) {
      runMonitorOnboardingActive.value = false;
      runMonitorOnboardingPreferenceVisible.value = false;
      runMonitorOnboardingShownThisVisit.value = false;
      return;
    }
    nextTick(maybeStartRunMonitorOnboarding);
  },
  { flush: "post" },
);

function addPageMapLayer(layer) {
  if (!layer || !MapRef.value) return;
  layer.pageGroupKey = "rm";
  // 由 MapLayout 以固定 rm 归属托管；即使异步回调晚于路由切换完成，
  // 图层也只会进入运行监测暂存区，不会被当前页面误收编。
  if (pageMapLayerHost?.addLayer) {
    pageMapLayerHost.addLayer("rm", layer);
    return;
  }
  if (pageActive.value) {
    MapRef.value.addLayer(layer);
  } else if (!pendingLayerAdds.includes(layer)) {
    pendingLayerAdds.push(layer);
  }
}

// XLZL / ZDZL / GJYS 子组件也必须走同一归属入口，避免其异步建层绕过父页门禁。
provide("AddPageMapLayer", addPageMapLayer);

onActivated(() => {
  pageActive.value = true;
  nextTick(setMapCenter);
  // 首次挂载的 activated 与 mounted 同帧触发，交给 onMounted 的引导流程
  if (!hasBeenDeactivated) return;
  while (pendingLayerAdds.length) {
    const layer = pendingLayerAdds.shift();
    if (layer && !layer.isDisposed) MapRef.value?.addLayer(layer);
  }
  // 相机状态在失活期间可能被其他页面改动，重新对齐
  const mapInstance = MapRef.value;
  if (mapInstance) {
    mapZoom.value = mapInstance.zoom;
    mapPitch.value = mapInstance.pitch;
    mapRotation.value = mapInstance.rotation;
    is3DActive.value = mapInstance.enableRotate || mapInstance.pitch !== 90 || mapInstance.rotation !== 0;
  }
  if (!initialModelBootstrap.value) {
    // 其他页面（换乘分析等）可能切换过全局模型：跟随共享选择，复用既有换模流程
    const stored = modelSelectionStore.getSelection(MODEL_SELECTION_KEY);
    if (isSimulationMode.value && stored.sourceMode === "simulation" && stored.model && stored.model !== datebase.value.model) {
      if (stored.scheme && stored.scheme !== datebase.value.scheme) {
        restoredSelection.scheme = stored.scheme;
        restoredSelection.model = stored.model;
        isRestoringSelection = true;
        datebase.value.scheme = stored.scheme;
      } else {
        setActiveModel(stored.model);
      }
    }
    // 失活期间列表轮询暂停，回来补一次刷新
    refreshSchemeAndModelLists();
  }
  ensureTransitNetworkForCurrentTab();
  scheduleLayerSyncBurst(3);
});

onDeactivated(() => {
  hasBeenDeactivated = true;
  pageActive.value = false;
  syncDistributionBuildingSuppression();
  // 作废正在加载图标/线网的异步链，并停止延迟重刷；回来后从模型缓存即时补挂。
  busNetworkRequestSeq += 1;
  centerRequestSeq += 1;
  busNetworkLoading.value = false;
  if (syncLayersRetryTimer) {
    clearTimeout(syncLayersRetryTimer);
    syncLayersRetryTimer = null;
  }
  runMonitorOnboardingActive.value = false;
  runMonitorOnboardingPreferenceVisible.value = false;
  runMonitorOnboardingShownThisVisit.value = false;
});

onMounted(() => {
  runMonitorOnboardingHostMounted.value = true;
  window.addEventListener(RUN_MONITOR_ONBOARDING_RESTART_EVENT, handleRunMonitorOnboardingRestart);
  // 地图为跨路由共享实例（注入的 MapRef）。若挂载时地图已存在，watch(MapRef) 不会触发，
  // 需在此补做地图初始化，否则上一个页面卸载时已解绑点击/清空图层，本页将无法点选线路/站点。
  if (MapRef.value) {
    bindBusNetworkClickListener();
    ensureTransitNetworkForCurrentTab();
    scheduleMapResize();
  }
  if (showDisplayRangeControl.value) {
    void loadDisplayRanges().catch((error) => {
      displayRangeError.value = error?.message || "行政区范围加载失败";
    });
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
        const restored = list.find((item) => item.name === restoredSelection.model);
        if (restoredSelection.model && !restored) {
          loadError.value = `原选择模型不存在或已被移除：${restoredSelection.model}`;
        } else if (restored) {
          setActiveModel(restored.name);
        }
      }
      await ensureSelectedModelReady();
    }
  })
    : ensureRealDataReady();
  if (isSimulationMode.value) {
    // 仿真首屏稳定后真实数据已在后台预热；用户首次点击“真实”通常直接命中缓存。
    ensureRealDataReady();
  }
  bootstrapSimulation.catch((error) => {
    loadError.value = error?.message || "运行监测初始化失败";
  }).finally(() => {
    initialModelBootstrap.value = false;
    isRestoringSelection = false;
    observeLeftPanelSize();
  });

  scheduleLayerSyncBurst(8);
});
onUnmounted(() => {
  const buildingLayer = MapRef.value?.layers?.find((layer) => layer?.name === "CityBuildingsLayer");
  buildingLayer?.setSuppressed?.(false);
  modelLoadSeq++;
  backgroundTaskSeq++;
  // 作废在途的线网/热力加载：地图跨模块共享，若不作废，慢请求回调会在卸载后
  // 把 rm-* 图层重新加回地图，导致数据管理/线网优化页面残留本模块的线网与站点
  busNetworkRequestSeq++;
  stationHeatSeq++;
  stationRankSeq++;
  clearStationRankRetry();
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
  leftPanelResizeObserver?.disconnect();
  leftPanelResizeObserver = null;
  if (leftPanelCenterFrame != null && typeof cancelAnimationFrame === "function") {
    cancelAnimationFrame(leftPanelCenterFrame);
    leftPanelCenterFrame = null;
  }
  window.removeEventListener(RUN_MONITOR_ONBOARDING_RESTART_EVENT, handleRunMonitorOnboardingRestart);
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
.box1 {
  scale: var(--app-panel-scale);
}

/* 仅运行监测的顶栏多一个帮助按钮，工具条要为它额外让位 */
.analysis-model-toolbar {
  --analysis-toolbar-extra-inset: var(--app-scaled-26, 26px);
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

      .vehicle-visibility-options {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 10px;
        width: 100%;

        .el-checkbox {
          height: auto;
          margin-right: 0;
        }
      }
    }

    .vehicle-visibility-row {
      align-items: flex-start;
      flex-direction: column;
      gap: 8px;
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

/* 关联线路分类图例：线条样与地图线宽观感对齐（颜色由模板绑定 mapTheme 令牌） */
.map-legend-line {
  width: 22px;
  height: 4px;
  border-radius: 2px;
  flex: none;
}

.map-legend-transfer-icon {
  /* 与线条色块同占 22px 列宽，三行标签左缘对齐；图标自带光晕留白，视觉尺寸约 16px */
  width: 22px;
  height: 20px;
  object-fit: contain;
  flex: none;
  margin: -2px 0;
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

/* 轨迹演示控制条的定位容器：贴地图底部，在"左导航右边缘 → 右面板左边缘"这条可视地图带内居中。
   本身不吃指针事件（露出地图），只有内部的控制条吃事件；随左右面板收合自动重新居中。 */
.rm-playback-dock {
  position: fixed;
  bottom: calc(var(--app-edge, 24px) - 2px);
  left: calc(var(--app-edge, 24px) + var(--app-scaled-260, 260px));
  right: var(--app-edge, 24px);
  z-index: calc(var(--z-panel, 1300) + 15);
  display: flex;
  justify-content: center;
  pointer-events: none;
  transition: left 160ms var(--dm2-ease, cubic-bezier(0.32, 0.72, 0, 1)),
    right 160ms var(--dm2-ease, cubic-bezier(0.32, 0.72, 0, 1));
}

/* 左导航收起：控制带左界回到贴边 */
.rm-playback-dock.is-left-collapsed {
  left: var(--app-edge, 24px);
}

/* 右侧信息面板展开：避让面板宽度，控制带在剩余地图区居中 */
.rm-playback-dock.with-panel {
  right: calc(var(--app-edge, 24px) + var(--app-scaled-414, 414px));
}

.rm-playback-dock > * {
  pointer-events: auto;
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
    min-height: 100%;
  }

  :deep(.el-scrollbar__wrap) {
    overflow-x: hidden !important;
    overflow-y: auto !important;
  }

  :deep(.el-scrollbar__view) {
    height: auto;
  }

  #datavisualization_index_box2 {
    height: auto;
    min-height: 100%;
    display: flex;
    flex-direction: column;
    align-items: stretch;
    gap: var(--dm2-space-3);
    overflow: visible;
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

/* dark 底图下右侧内容面使用纯黑底，避免深色地图后仍浮着一块白色面板。 */
:global(html.dark .run-monitor-right-panel) {
  background: #000000 !important;
}

:global(html.dark .run-monitor-right-panel .MCard2) {
  background: #000000;
}

:global(html.dark .run-monitor-right-panel .rm-flow-hero),
:global(html.dark .run-monitor-right-panel .rm-flow-kpi-grid),
:global(html.dark .run-monitor-right-panel .rm-ops-table),
:global(html.dark .run-monitor-right-panel .rm-overall-chart),
:global(html.dark .run-monitor-right-panel .rm-daily-flow-chart),
:global(html.dark .run-monitor-right-panel .rm-daily-range input) {
  background: #000000;
}

:global(html.dark .run-monitor-right-panel .rm-overall-chart),
:global(html.dark .run-monitor-right-panel .rm-clickable-chart:hover) {
  border-color: var(--dm2-line);
  box-shadow: none;
}

.rm-right-card {
  width: 100%;
  box-sizing: border-box;
  min-height: 100%;
  display: flex;
  flex-direction: column;
  flex: 0 0 auto;
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

/* ── 总体客流监测：主指标 → 方式构成（兼作图例）→ 曲线 → 高峰速览 ── */
.overall-flow-card {
  overflow: visible;

  .rm-right-card-title {
    padding-bottom: 10px;

    h2 {
      margin-top: 0;
      font-size: 18px;
      line-height: 1.18;
    }
  }

  /* 面板允许纵向滚动，图表使用稳定高度，避免下方企业和日变化内容被裁掉。 */
  .rm-overall-chart {
    flex: none;
    height: 210px;
    margin-top: 12px;
  }
}

/* ── 线路 / 站点客流监测：名称 → 主指标 → 构成（兼作图例）→ 曲线 → 分项指标 ── */
.line-flow-card,
.station-flow-card {
  overflow: visible;

  .rm-right-card-title {
    padding-bottom: 10px;

    h2 {
      margin-top: 0;
      font-size: 20px;
      line-height: 1.18;
      letter-spacing: -0.01em;
    }
  }

  /* 固定图表正文高度，其余指标由右侧滚动容器完整承载。 */
  .rm-overall-chart {
    flex: none;
    height: 226px;
    margin-top: 12px;
  }
}

.rm-panel-title-main {
  flex: 1;
  min-width: 0;
}

/* 线路名 + 所属企业同行展示：名称可截断，企业名灰字不折行 */
.line-flow-card .rm-panel-title-main h2 {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
}

.rm-line-name-text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rm-line-company {
  flex: none;
  max-width: 45%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--dm2-muted);
  font-size: 12px;
  font-weight: 650;
}

/* 首末站在标题里出现一次，方向行里只留终点，不再两处各折一次行 */
.rm-line-endpoints {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 5px 0 0;
  min-width: 0;
  color: var(--dm2-muted);
  font-size: 11.5px;
  font-weight: 650;
  line-height: 1.3;
}

.rm-line-endpoints > span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rm-line-endpoints-sep {
  flex: none;
  color: var(--dm2-muted-soft);
  font-size: 12px;
}

.rm-flow-peak {
  flex: none;
  padding: 2px 8px;
  border-radius: var(--dm2-radius-pill);
  background: var(--dm2-accent-weak);
  color: var(--dm2-accent-strong);
  font-size: 11px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.rm-flow-legend {
  margin-top: 10px;
}

.rm-flow-legend-row {
  display: grid;
  grid-template-columns: 14px minmax(0, 1fr) auto 42px;
  align-items: center;
  gap: 10px;
  padding: 8px 6px;
  border-radius: var(--dm2-radius-sm);
  transition: background-color var(--dm2-dur-fast) var(--dm2-ease);

  & + .rm-flow-legend-row {
    border-top: 1px solid var(--dm2-line-faint);
  }

  &:hover {
    background: rgba(17, 32, 58, 0.04);
  }
}

/* 色条而非圆点：与折线图里的曲线是同一种形状语言 */
.rm-flow-swatch {
  width: 14px;
  height: 3px;
  border-radius: var(--dm2-radius-pill);
}

.rm-flow-legend-name {
  min-width: 0;
  overflow: hidden;
  color: var(--dm2-ink-soft);
  font-size: 12px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rm-flow-legend-value {
  color: var(--dm2-ink);
  font-family: var(--dm2-font-num);
  font-size: 15px;
  font-weight: 780;
  text-align: right;
  font-variant-numeric: tabular-nums;
  font-feature-settings: "tnum";
}

.rm-flow-legend-share {
  color: var(--dm2-muted);
  font-family: var(--dm2-font-num);
  font-size: 12px;
  font-weight: 700;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

/* 分项指标靠发丝线分隔而不是各自成卡：线路 8 项铺满 2×4，站点 2 项铺满一行 */
.rm-flow-kpi-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin-top: 12px;
  border: 1px solid var(--dm2-line-faint);
  border-radius: var(--dm2-radius-sm);
  background: var(--dm2-surface-sunken);
  overflow: hidden;
}

.rm-flow-kpi-item {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 12px;

  &:nth-child(odd) {
    border-right: 1px solid var(--dm2-line-faint);
  }

  &:nth-child(n + 3) {
    border-top: 1px solid var(--dm2-line-faint);
  }
}

.rm-flow-kpi-label {
  color: var(--dm2-muted);
  font-size: 11px;
  font-weight: 650;
}

.rm-flow-kpi-value {
  display: flex;
  align-items: baseline;
  gap: 3px;
  color: var(--dm2-ink);
  font-family: var(--dm2-font-num);
  font-size: 17px;
  font-weight: 780;
  line-height: 1.1;
  letter-spacing: -0.015em;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
  font-feature-settings: "tnum";

  em {
    color: var(--dm2-muted);
    font-size: 11px;
    font-style: normal;
    font-weight: 650;
  }
}

.rm-refresh-note {
  flex: none;
  align-self: center;
  padding: 3px 9px;
  border-radius: var(--dm2-radius-pill);
  background: var(--dm2-accent-weak);
  color: var(--dm2-accent-strong);
  font-size: 11px;
  font-weight: 650;
  white-space: nowrap;
}

.rm-flow-hero {
  margin-top: 14px;
}

.rm-flow-hero-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}

.rm-flow-hero-label {
  flex: none;
  color: var(--dm2-muted);
  font-size: 12px;
  font-weight: 650;
}

.rm-flow-hero-scope {
  min-width: 0;
  color: var(--dm2-muted);
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.rm-flow-hero-value {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin: 0;

  strong {
    color: var(--dm2-ink);
    font-family: var(--dm2-font-num);
    font-size: 32px;
    font-weight: 800;
    line-height: 1.05;
    letter-spacing: -0.025em;
    font-variant-numeric: tabular-nums;
    font-feature-settings: "tnum";
  }

  em {
    color: var(--dm2-muted);
    font-size: 12px;
    font-style: normal;
    font-weight: 650;
  }
}

.rm-mode-split {
  margin-top: 12px;
}

.rm-mode-head {
  display: grid;
  grid-template-columns: 8px auto 1fr auto;
  align-items: center;
  gap: 8px;
  padding: 0 2px 6px;
  border-bottom: 1px solid var(--dm2-line-faint);
  color: var(--dm2-muted);
  font-size: 10.5px;
  font-weight: 700;
}

.rm-mode-head-name {
  grid-column: 1 / span 2;
}

.rm-mode-head-value {
  text-align: right;
}

.rm-mode-head-share {
  min-width: 44px;
  text-align: right;
}

.rm-mode-row {
  display: grid;
  grid-template-columns: 8px auto 1fr auto;
  align-items: center;
  gap: 8px;
  padding: 7px 2px;

  & + .rm-mode-row {
    border-top: 1px solid var(--dm2-line-faint);
  }
}

.rm-mode-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.rm-mode-name {
  color: var(--dm2-ink-soft);
  font-size: 12px;
  font-weight: 650;
}

.rm-mode-value {
  color: var(--dm2-ink);
  font-family: var(--dm2-font-num);
  font-size: 14px;
  font-weight: 780;
  text-align: right;
  font-variant-numeric: tabular-nums;
  font-feature-settings: "tnum";
}

.rm-mode-unit {
  margin-left: 3px;
  font-size: 11px;
  font-weight: 600;
  font-style: normal;
  color: var(--dm2-muted);
}

.rm-mode-share {
  min-width: 44px;
  color: var(--dm2-muted);
  font-family: var(--dm2-font-num);
  font-size: 12px;
  font-weight: 700;
  text-align: right;
  font-variant-numeric: tabular-nums;
}

/* ── 总体卡：公交运营效率、日变化与分企业指标 ── */
.rm-ops-block {
  margin-top: 12px;
}

.rm-ops-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}

.rm-ops-title {
  color: var(--dm2-ink-soft);
  font-size: 12px;
  font-weight: 700;
}

.rm-ops-scope {
  color: var(--dm2-muted);
  font-size: 11px;
  font-weight: 650;
  cursor: help;
}

.rm-daily-flow-head {
  align-items: center;
  flex-wrap: wrap;
}

.rm-daily-range {
  display: flex;
  align-items: center;
  gap: 5px;
  color: var(--dm2-muted);
  font-size: 11px;
}

.rm-daily-range input {
  width: 112px;
  box-sizing: border-box;
  padding: 4px 5px;
  border: 1px solid var(--dm2-line);
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.76);
  color: var(--dm2-ink-soft);
  font: 600 10.5px/1.2 var(--dm2-font-num);
}

.rm-daily-range input:focus-visible {
  border-color: var(--dm2-accent);
  outline: 2px solid var(--dm2-accent-ring);
  outline-offset: 1px;
}

.rm-daily-flow-chart {
  width: 100%;
  height: 210px;
  border: 1px solid var(--dm2-line-faint);
  border-radius: var(--dm2-radius-sm);
  background: rgba(255, 255, 255, 0.34);
}

/* 三列变体：覆盖基础 2×2 网格的奇偶分隔线规则，改为列间竖线 */
.rm-ops-grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin-top: 0;

  .rm-flow-kpi-item {
    padding: 9px 10px;

    &:nth-child(odd) {
      border-right: none;
    }

    &:nth-child(n + 3) {
      border-top: none;
    }

    & + .rm-flow-kpi-item {
      border-left: 1px solid var(--dm2-line-faint);
    }
  }

  /* 较长单位放不下时落到下一行，不许横向溢出 */
  .rm-flow-kpi-value {
    flex-wrap: wrap;
    row-gap: 1px;
    font-size: 16px;
  }
}

.rm-ops-table {
  width: 100%;
  border: 1px solid var(--dm2-line-faint);
  border-radius: var(--dm2-radius-sm);
  border-collapse: separate;
  border-spacing: 0;
  background: var(--dm2-surface-sunken);

  th {
    padding: 7px 10px;
    border-bottom: 1px solid var(--dm2-line-faint);
    color: var(--dm2-muted);
    font-size: 11px;
    font-weight: 700;
    text-align: right;
    white-space: nowrap;

    &:first-child {
      text-align: left;
    }
  }

  td {
    padding: 8px 10px;
    color: var(--dm2-ink);
    font-family: var(--dm2-font-num);
    font-size: 12.5px;
    font-weight: 700;
    text-align: right;
    font-variant-numeric: tabular-nums;

    &:first-child {
      text-align: left;
      font-family: inherit;
    }
  }

  .rm-ops-unit {
    margin-left: 3px;
    color: var(--dm2-muted);
    font-family: inherit;
    font-size: 10.5px;
    font-style: normal;
    font-weight: 600;
  }

  .rm-ops-empty {
    padding: 14px 10px;
    color: var(--dm2-muted);
    font-family: inherit;
    font-size: 12px;
    font-weight: 600;
    text-align: center;
  }

  .rm-ops-summary-row {
    background: rgba(0, 113, 227, 0.04);

    td {
      border-top: 1px solid var(--dm2-line-faint);
      color: var(--dm2-ink);
      font-weight: 800;
    }
  }
}

/* 状态机：整块替换正文（骨架 / 失败 / 生成中 / 空），不让状态条浮在 0 值之上 */
.rm-flow-state {
  flex: 1 1 auto;
  min-height: 0;
  margin-top: 14px;
}

.rm-flow-skeleton {
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: hidden;
}

.rm-sk {
  border-radius: var(--dm2-radius);
  background: var(--dm2-surface-sunken);
}

.rm-sk-hero {
  flex: none;
  height: 84px;
}

.rm-sk-split {
  flex: none;
  height: 82px;
}

.rm-sk-chart {
  flex: 1 1 auto;
  min-height: 180px;
}

.rm-sk-shimmer {
  background:
    linear-gradient(100deg, rgba(17, 32, 58, 0.05) 8%, rgba(17, 32, 58, 0.1) 20%, rgba(17, 32, 58, 0.05) 33%),
    var(--dm2-surface-sunken);
  background-size: 220% 100%;
  animation: rmFlowShimmer 1.35s var(--dm2-ease) infinite;
}

@keyframes rmFlowShimmer {
  from {
    background-position: 180% 0;
  }

  to {
    background-position: -60% 0;
  }
}

.rm-flow-status {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 32px 22px;
  text-align: center;
}

.rm-flow-status-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 14px;
  color: var(--dm2-accent);
  background: var(--dm2-accent-weak);

  svg {
    width: 22px;
    height: 22px;
  }

  &.is-error {
    color: var(--dm2-delete);
    background: var(--dm2-delete-weak);
  }
}

.rm-flow-status-title {
  margin: 2px 0 0;
  color: var(--dm2-ink);
  font-size: 14px;
  font-weight: 700;
}

.rm-flow-status-desc {
  max-width: 260px;
  margin: 0;
  color: var(--dm2-muted);
  font-size: 12px;
  line-height: 1.6;
}

.rm-flow-status-retry {
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

  &:hover {
    background: var(--dm2-accent-strong);
  }

  &:active {
    transform: translateY(1px);
  }

  &:focus-visible {
    outline: 2px solid var(--dm2-accent-ring);
    outline-offset: 2px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .rm-sk-shimmer {
    animation: none;
    background: var(--dm2-surface-sunken);
  }

  .rm-clickable-chart,
  .rm-flow-legend-row,
  .rm-rank-row,
  .rm-rank-bar i {
    transition: none;
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

.rm-clickable-chart:hover {
  border-color: rgba(21, 105, 222, 0.28);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.82), 0 0 0 1px rgba(21, 105, 222, 0.14);
}

/* 全屏入口就是图表本身：不再叠一颗假按钮，用光标与键盘焦点环表达可点 */
.rm-clickable-chart:focus-visible {
  border-color: rgba(21, 105, 222, 0.28);
  outline: 2px solid var(--dm2-accent-ring);
  outline-offset: 2px;
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

/* ── 客流排名（未选中线路/站点时的右侧内容）：与 rm-flow-legend 同一行语言 ── */
.rm-flow-rank {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}

.rm-rank-hint {
  margin: 5px 0 0;
  color: var(--dm2-muted);
  font-size: 11.5px;
  line-height: 1.4;
  font-weight: 650;
}

/* 排名依据切换（五指标下拉，未选中线路态） */
.rm-rank-metric-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 10px 0 2px;
}

.rm-rank-metric-label {
  flex-shrink: 0;
  color: var(--dm2-muted);
  font-size: 11px;
  font-weight: 680;
}

.rm-rank-metric-select {
  flex: 1;
  min-width: 0;
  max-width: 200px;

  :deep(.el-select__wrapper) {
    min-height: 26px;
    font-size: 11.5px;
  }
}

.rm-rank-list {
  flex: 1;
  min-height: 0;
  margin: 6px 0 0;
  padding: 0;
  list-style: none;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: rgba(21, 105, 222, 0.2) transparent;

  &::-webkit-scrollbar {
    width: 6px;
  }

  &::-webkit-scrollbar-thumb {
    background: rgba(21, 105, 222, 0.2);
    border-radius: 3px;
  }

  li + li .rm-rank-row {
    border-top: 1px solid var(--dm2-line-faint);
  }
}

.rm-rank-row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 9px 6px;
  border: 0;
  border-radius: var(--dm2-radius-sm);
  background: transparent;
  color: inherit;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
  transition: background-color var(--dm2-dur-fast) var(--dm2-ease);

  &:hover {
    background: rgba(17, 32, 58, 0.04);
  }

  &:active {
    transform: translateY(1px);
  }

  &:focus-visible {
    outline: 2px solid var(--dm2-accent-ring);
    outline-offset: -2px;
  }
}

.rm-rank-index {
  flex: none;
  width: 22px;
  color: var(--dm2-muted);
  font-family: var(--dm2-font-num);
  font-size: 12px;
  font-weight: 750;
  text-align: center;
  font-variant-numeric: tabular-nums;

  &.is-top {
    color: var(--dm2-accent-strong);
  }
}

.rm-rank-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}

.rm-rank-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}

.rm-rank-name {
  min-width: 0;
  overflow: hidden;
  color: var(--dm2-ink-soft);
  font-size: 12.5px;
  font-weight: 680;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 线路名旁的运营企业备注（弱化次级信息，随名称列一起省略） */
.rm-rank-operator {
  margin-left: 4px;
  font-size: 11px;
  font-weight: 500;
  color: var(--dm2-muted-soft, #98a2b3);
}

.rm-rank-value {
  flex: none;
  color: var(--dm2-ink);
  font-family: var(--dm2-font-num);
  font-size: 13px;
  font-weight: 780;
  font-variant-numeric: tabular-nums;

  em {
    margin-left: 3px;
    color: var(--dm2-muted);
    font-size: 10.5px;
    font-style: normal;
    font-weight: 650;
  }
}

.rm-rank-bar {
  display: block;
  height: 3px;
  border-radius: var(--dm2-radius-pill);
  background: rgba(17, 32, 58, 0.06);
  overflow: hidden;

  i {
    display: block;
    height: 100%;
    border-radius: inherit;
    background: var(--dm2-accent);
    opacity: 0.85;
    transition: opacity var(--dm2-dur-fast) var(--dm2-ease);
  }
}

.rm-rank-row:hover .rm-rank-bar i {
  opacity: 1;
}

.rm-rank-footnote {
  flex: none;
  margin: 8px 0 0;
  padding-top: 8px;
  border-top: 1px solid var(--dm2-line-faint);
  color: var(--dm2-muted-soft);
  font-size: 11px;
  font-weight: 650;
  text-align: center;
}

.rm-sk-rank-row {
  flex: none;
  height: 40px;
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

/* 开关下方的状态提示行（如车速缓存生成中），紧贴所属开关 */
.layer-mode-note {
  margin: -6px 0 10px;
  color: var(--app-muted, #667085);
  font-size: 10.5px;
  font-weight: 550;
  line-height: 1.4;
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
  .box1 {
    width: min(400px, calc((100vw - 48px) / var(--app-panel-scale)));
    min-width: min(400px, calc((100vw - 48px) / var(--app-panel-scale)));
  }

}

@media (max-width: 640px) {
  .box1 {
    width: calc((100vw - 32px) / var(--app-panel-scale));
    min-width: calc((100vw - 32px) / var(--app-panel-scale));

    .tab_list {
      flex-wrap: wrap;
    }
  }
}

/* 线路/站点客流监测未选中状态：中部底部高级悬浮提示 Toast */
.rm-bottom-hint-toast {
  position: absolute;
  bottom: 24px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 100;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 18px 8px 14px;
  border-radius: 999px;
  background: #ffffff;
  border: 1px solid rgba(0, 0, 0, 0.12);
  box-shadow: 0 8px 28px -6px rgba(0, 113, 227, 0.18), 0 2px 10px rgba(0, 0, 0, 0.08);
  color: var(--dm2-ink, #1d1d1f);
  font-size: 12.5px;
  font-weight: 650;
  letter-spacing: -0.01em;
  pointer-events: auto;
  user-select: none;
}

.rm-hint-toast-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #0071e3;
}

.rm-hint-toast-text {
  white-space: nowrap;
}

:global(html.dark .rm-bottom-hint-toast) {
  background: #1c1c1e;
  border-color: rgba(255, 255, 255, 0.14);
  color: #f5f5f7;
  box-shadow: 0 8px 30px -4px rgba(0, 0, 0, 0.5);
}

.rm-toast-fade-enter-active,
.rm-toast-fade-leave-active {
  transition: opacity 0.35s cubic-bezier(0.16, 1, 0.3, 1), transform 0.35s cubic-bezier(0.16, 1, 0.3, 1);
}

.rm-toast-fade-enter-from,
.rm-toast-fade-leave-to {
  opacity: 0;
  transform: translate(-50%, 14px);
}

/* 体检评估分析：全屏主界面容器（完全填满底图面板，类似数据管理历史数据模块） */
.tjfx-full-stage-wrapper {
  position: fixed;
  top: var(--app-header-height, 58px);
  left: 260px;
  right: 0;
  bottom: 0;
  z-index: 100;
  background: var(--app-surface-soft, #f8fafc);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  transition: left 0.25s cubic-bezier(0.16, 1, 0.3, 1);

  &.is-left-collapsed {
    left: 0;
  }
}

:global(html.dark .tjfx-full-stage-wrapper) {
  background: #0b1120;
}
</style>
