<!-- Scenario Construction (场景搭建) View -->
<template>
  <div class="scenario-builder-wrapper">
    <div ref="panelRef" :style="panelStyle" class="scenario-panel">
      <!-- Panel Header / Drag Handle (Unified MCard2 Style) -->
      <div ref="handleRef" class="panel-header">
        <div class="header-title">
          <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"></path>
            <polyline points="3.27 6.96 12 12.01 20.73 6.96"></polyline>
            <line x1="12" y1="22.08" x2="12" y2="12"></line>
          </svg>
          <span>场景搭建器</span>
        </div>
      </div>

    <el-scrollbar class="panel-content">
      <div class="inner-container">
        
        <!-- STEP 0: Quick Search & Positioning (Typing Filterable) -->
        <div class="section-card">
          <div class="card-title">
            <span class="step-num">00</span>
            <span>线网快捷搜索与定位</span>
          </div>
          <div class="search-row">
            <el-select
              v-model="searchQuery"
              filterable
              clearable
              remote
              placeholder="输入线路或站点，如 M191、市民中心"
              class="block-select"
              @change="handleSearchLocate"
            >
              <el-option-group label="公交线路">
                <el-option
                  v-for="item in searchOptions.routes"
                  :key="item.name"
                  :label="item.label"
                  :value="item.name"
                />
              </el-option-group>
              <el-option-group label="公交站点">
                <el-option
                  v-for="item in searchOptions.stations"
                  :key="item.name"
                  :label="item.label"
                  :value="item.name"
                />
              </el-option-group>
            </el-select>
          </div>
        </div>

        <!-- STEP 1: Define Area -->
        <div class="section-card">
          <div class="card-title">
            <span class="step-num">01</span>
            <span>研究范围设定</span>
          </div>

          <!-- Mode Select -->
          <div class="mode-selector">
            <button 
              :class="['mode-btn', activeMode === 'draw' ? 'active' : '']" 
              type="button"
              :aria-pressed="activeMode === 'draw'"
              @click="setMode('draw')"
            >
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5">
                <path d="M12 20h9"></path>
                <path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"></path>
              </svg>
              <span>手绘研究区域</span>
            </button>
            <button 
              :class="['mode-btn', activeMode === 'upload' ? 'active' : '']" 
              type="button"
              :aria-pressed="activeMode === 'upload'"
              @click="setMode('upload')"
            >
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
                <polyline points="17 8 12 3 7 8"></polyline>
                <line x1="12" y1="3" x2="12" y2="15"></line>
              </svg>
              <span>上传 SHP 文件</span>
            </button>
          </div>

          <!-- DRAW PANEL -->
          <div v-if="activeMode === 'draw'" class="tab-pane">
            <div class="help-text">
              <span v-if="!isDrawing && !hasArea">在地图上连续点击，依次添加折点以形成封闭多边形。</span>
              <span v-else-if="isDrawing" class="highlight-warn">绘制中：请在地图上继续点击。已标记 {{ drawingPoints.length }} 个折点。</span>
              <span v-else class="highlight-success">范围已锁定，可在下方清除或重新绘制。</span>
            </div>
            
            <div class="btn-group">
              <button 
                v-if="!isDrawing" 
                class="action-btn primary-btn" 
                @click="startDrawing"
              >
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5">
                  <polygon points="5 3 19 12 5 21 5 3"></polygon>
                </svg>
                <span>开始手绘</span>
              </button>
              <button 
                v-else 
                class="action-btn danger-btn" 
                @click="finishDrawing"
                :disabled="drawingPoints.length < 3"
              >
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5">
                  <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
                </svg>
                <span>完成手绘</span>
              </button>

              <button 
                class="action-btn secondary-btn" 
                @click="clearArea" 
                :disabled="!hasArea && !isDrawing && drawingPoints.length === 0"
              >
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5">
                  <path d="M3 6h18"></path>
                  <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                </svg>
                <span>清除范围</span>
              </button>
            </div>
          </div>

          <!-- UPLOAD PANEL -->
          <div class="tab-pane" v-else>
            <div 
              class="upload-box" 
              :class="{ 'dragging': isDraggingFile, 'success': hasArea }"
              @dragover.prevent="isDraggingFile = true"
              @dragleave="isDraggingFile = false"
              @drop.prevent="handleFileDrop"
              @click="triggerFileInput"
              @keydown.enter.prevent="triggerFileInput"
              @keydown.space.prevent="triggerFileInput"
              role="button"
              tabindex="0"
              aria-label="上传SHP或GeoJSON研究范围文件"
            >
              <input 
                ref="fileInputRef"
                type="file" 
                accept=".shp,.json,.geojson" 
                class="hidden-input" 
                aria-label="上传研究范围文件"
                @change="handleFileSelect"
              />
              <template v-if="isUploading">
                <div class="loader-spinner"></div>
                <div class="upload-title">正在解析文件...</div>
                <div class="upload-sub">校验拓扑关系与投影坐标系</div>
              </template>
              <template v-else-if="hasArea">
                <svg class="success-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                  <polyline points="20 6 9 17 4 12"></polyline>
                </svg>
                <div class="upload-title upload-success">文件已解析</div>
                <div class="upload-sub">{{ fileName }} ({{ fileSize }})</div>
              </template>
              <template v-else>
                <svg class="upload-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
                  <polyline points="14 2 14 8 20 8"></polyline>
                  <line x1="12" y1="18" x2="12" y2="12"></line>
                  <polyline points="9 15 12 12 15 15"></polyline>
                </svg>
                <div class="upload-title">点击或将 .SHP / .GEOJSON 拖拽至此</div>
                <div class="upload-sub">支持 WGS84 经纬度及 Web Mercator 自动重投影</div>
              </template>
            </div>
            
            <div v-if="hasArea" class="btn-group upload-clear-actions">
              <button class="action-btn secondary-btn block-btn" @click="clearArea">
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5">
                  <path d="M3 6h18"></path>
                  <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                </svg>
                <span>清除数据</span>
              </button>
            </div>
          </div>

          <!-- METRICS DATA -->
          <Transition name="fade-slide">
            <div v-if="hasArea" class="area-metrics">
              <div class="metrics-grid">
                <div class="metric-item">
                  <div class="m-label">折点数量</div>
                  <div class="m-val">{{ areaMetrics.vertices }} <span class="unit">pts</span></div>
                </div>
                <div class="metric-item">
                  <div class="m-label">研究面积</div>
                  <div class="m-val">{{ areaMetrics.area }} <span class="unit">km²</span></div>
                </div>
                <div class="metric-item">
                  <div class="m-label">范围周长</div>
                  <div class="m-val">{{ areaMetrics.perimeter }} <span class="unit">km</span></div>
                </div>
                <div class="metric-item">
                  <div class="m-label">中心点经纬</div>
                  <div class="m-val compact-val">{{ areaMetrics.center[0] }}, {{ areaMetrics.center[1] }}</div>
                </div>
              </div>
            </div>
          </Transition>
        </div>

        <!-- STEP 2: Settings (QGIS Dynamic Modification Queue) -->
        <div class="section-card" :class="{ 'disabled-card': !hasArea }">
          <div class="card-title">
            <span class="step-num">02</span>
            <span>仿真场景参数配置 (修改队列)</span>
          </div>

          <!-- Active Modifications Changelog List -->
          <div class="modifications-queue">
            <div class="queue-title-row">
              <span>当前场景修改列表 ({{ activeModifications.length }})</span>
            </div>
            
            <el-scrollbar max-height="160px" class="queue-scrollbar">
              <div v-if="activeModifications.length === 0" class="empty-queue-placeholder">
                <span>暂无修改项</span>
              </div>
              <div v-else class="queue-list">
                <TransitionGroup name="fade-slide">
                  <div 
                    v-for="(mod, index) in activeModifications" 
                    :key="mod.id" 
                    class="queue-item"
                  >
                    <span :class="['mod-badge', mod.category]">{{ mod.categoryText }}</span>
                    <div class="mod-details">
                      <div class="mod-type">{{ mod.subText }}</div>
                      <div class="mod-desc">{{ mod.details }}</div>
                    </div>
                    <button class="undo-btn" @click="undoModification(index)" title="撤回当前操作">
                      <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.5">
                        <path d="M3 7v6h6"></path>
                        <path d="M21 17a9 9 0 0 0-9-9 9 9 0 0 0-6 2.3L3 13"></path>
                      </svg>
                      <span>撤回</span>
                    </button>
                  </div>
                </TransitionGroup>
              </div>
            </el-scrollbar>
          </div>

          <!-- Add Item Trigger (Dropdown Style) -->
          <el-popover
            placement="bottom-start"
            trigger="click"
            :width="170"
            :show-arrow="false"
            popper-class="mac-os-popover"
            v-model:visible="showQgisMenu"
          >
            <template #reference>
              <button 
                v-if="!showQgisParamsForm" 
                class="action-btn secondary-btn block-btn add-item-trigger"
              >
                <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5">
                  <line x1="12" y1="5" x2="12" y2="19"></line>
                  <line x1="5" y1="12" x2="19" y2="12"></line>
                </svg>
                <span>添加场景搭建配置项</span>
              </button>
            </template>

            <div class="mac-os-menu">
              <el-popover
                v-for="cat in qgisCategories" 
                :key="cat.id"
                placement="right-start"
                trigger="hover"
                :width="160"
                :show-arrow="false"
                :offset="4"
                popper-class="mac-os-popover"
              >
                <template #reference>
                  <div class="mac-menu-item">
                    <span class="menu-label">{{ cat.name }}</span>
                    <svg class="menu-arrow" viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2">
                      <polyline points="9 18 15 12 9 6"></polyline>
                    </svg>
                  </div>
                </template>
                
                <div class="mac-os-menu">
                  <div 
                    v-for="sub in qgisSubOptions[cat.id]" 
                    :key="sub.id"
                    class="mac-menu-item"
                    @click="selectQgisSub(cat.id, sub.id)"
                  >
                    <span class="menu-label">{{ sub.name }}</span>
                  </div>
                </div>
              </el-popover>
            </div>
          </el-popover>

        </div>

        <!-- STEP 3: Run -->
        <div class="section-card border-none" :class="{ 'disabled-card': !hasArea }">
          <button 
            class="build-scenario-btn" 
            :disabled="!hasArea || isBuilding"
            :aria-busy="isBuilding"
            @click="buildScenario"
          >
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.5" class="btn-icon">
              <polyline points="22 12 18 12 15 21 9 3 6 12 2 12"></polyline>
            </svg>
            <span>{{ isBuilding ? '正在生成场景...' : '生成仿真场景' }}</span>
          </button>
        </div>
      </div>
    </el-scrollbar>

    <Transition name="fade">
      <div v-if="isBuilding" class="build-overlay">
        <div class="loader-content" aria-live="polite">
          <svg v-if="buildProgress >= 100" class="complete-check" viewBox="0 0 24 24" fill="none">
            <path class="complete-check-path" d="M5 12.5l4.2 4.2L19 7" />
          </svg>
          <div v-else class="loader-spinner large"></div>
          <div class="loader-title">正在生成仿真场景</div>
          <div class="loader-status">{{ buildStatusText }}</div>
          <div class="loader-bar-bg">
            <div class="loader-bar" :style="{ width: `${buildProgress}%` }"></div>
          </div>
          <div class="progress-num">{{ buildProgress }}%</div>
        </div>
      </div>
    </Transition>
    </div>
    
    <!-- New Right Panel for Parameter Config -->
    <Transition name="fade-slide">
      <div v-if="showQgisParamsForm" class="scenario-right-panel">
        <div class="panel-header">
          <div class="header-title">
            <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path>
              <path d="M18.5 2.5a2.121 2.121 0 1 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path>
            </svg>
            <span>配置参数编辑</span>
          </div>
        </div>

        <div class="panel-content">
          <div class="inner-container">
            <div class="qgis-nested-builder params-only-builder">
              <div class="builder-title-row font-bold">
                <span>正在配置: {{ getSubName(activeQgisCat, activeQgisSub) }}</span>
              </div>
              
              <div class="qgis-right-content params-box-content">
                <!-- Contextual Sub Parameter Inputs -->
                <div class="sub-params-box">
                  <!-- Custom rendering for New Station ("新增站点") -->
                  <template v-if="activeQgisCat === 'station' && activeQgisSub === 'add'">
                    <!-- Creation Mode selection -->
                    <div class="param-row">
                      <label class="form-label">创建方式</label>
                      <el-radio-group v-model="qgisParams.creationMode" size="small" class="creation-mode-group">
                        <el-radio-button value="click" label="click">在地图上点选位置</el-radio-button>
                        <el-radio-button value="manual" label="manual">手动输入84经纬度</el-radio-button>
                      </el-radio-group>
                    </div>

                    <!-- Station Name -->
                    <div class="param-row">
                      <label class="form-label">站点名称</label>
                      <el-input v-model="qgisParams.name" placeholder="例如：科技园北公交枢纽" size="small" />
                    </div>

                    <!-- Station Type -->
                    <div class="param-row">
                      <label class="form-label">站点类型</label>
                      <el-radio-group v-model="qgisParams.stationType" size="small">
                        <el-radio value="bus" label="bus">公交站</el-radio>
                        <el-radio value="subway" label="subway">地铁站</el-radio>
                      </el-radio-group>
                    </div>

                    <!-- Longitude & Latitude -->
                    <div class="param-row coordinates-row">
                      <div class="coord-col">
                        <label class="form-label">经度 (WGS84)</label>
                        <el-input-number 
                          v-model="qgisParams.lng" 
                          :precision="6" 
                          :step="0.0001" 
                          placeholder="经度"
                          size="small" 
                          class="coord-input"
                          :controls="false"
                          :disabled="qgisParams.creationMode === 'click'"
                        />
                      </div>
                      <div class="coord-col">
                        <label class="form-label">纬度 (WGS84)</label>
                        <el-input-number 
                          v-model="qgisParams.lat" 
                          :precision="6" 
                          :step="0.0001" 
                          placeholder="纬度"
                          size="small" 
                          class="coord-input"
                          :controls="false"
                          :disabled="qgisParams.creationMode === 'click'"
                        />
                      </div>
                    </div>

                    <!-- Description/Details -->
                    <div class="param-row">
                      <label class="form-label">备注描述 (可选)</label>
                      <el-input v-model="qgisParams.desc" placeholder="选填，如：服务周边写字楼客流" size="small" />
                    </div>

                    <!-- Click guidance -->
                    <div v-if="qgisParams.creationMode === 'click'" class="click-guidance-box">
                      <span class="guidance-dot"></span>
                      <span>已启用底图点选：直接在左侧地图上鼠标点击，系统将自动捕获并填入上面的经纬度坐标。</span>
                    </div>
                  </template>

                  <!-- Otherwise (standard form fields) -->
                  <template v-else>
                    <!-- Station Name Input -->
                    <div v-if="showInputName" class="param-row">
                      <label class="form-label">站点/线路/路段名称</label>
                      <el-input v-model="qgisParams.name" placeholder="请输入名称" size="small" />
                    </div>

                    <!-- General Desc Text Input -->
                    <div v-if="showInputDesc" class="param-row">
                      <label class="form-label">操作描述/更改细节</label>
                      <el-input v-model="qgisParams.desc" placeholder="请输入修改描述..." size="small" />
                    </div>

                    <!-- Value Percentage Slider -->
                    <div v-if="showValueSlider" class="param-row">
                      <div class="slider-header-sub">
                        <span>幅度/参数调整</span>
                        <span class="slider-val">{{ qgisParams.percent > 0 ? '+' : '' }}{{ qgisParams.percent }}%</span>
                      </div>
                      <el-slider v-model="qgisParams.percent" :min="-50" :max="50" :step="5" />
                    </div>

                    <!-- Dropdown Select -->
                    <div v-if="showValueSelect" class="param-row">
                      <label class="form-label">具体类别配置</label>
                      <el-select v-model="qgisParams.selectVal" size="small" class="block-select">
                        <el-option 
                          v-for="item in currentParamSelects" 
                          :key="item"
                          :label="item"
                          :value="item"
                        />
                      </el-select>
                    </div>
                  </template>
                </div>
              </div>
              
              <!-- Action Bottom Row -->
              <div class="builder-actions">
                <button class="action-btn secondary-btn" @click="cancelQgisParams">取消</button>
                <button class="action-btn primary-btn" @click="confirmAddModification">确认添加</button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<script setup>
import { ref, computed, inject, onMounted, onUnmounted, watch, nextTick } from "vue";
import { useDraggable } from "@vueuse/core";
import { ElNotification } from "element-plus";
import { getLineAll } from "@/api/route";
import { getSchemeList, getModelList } from "@/api/scheme";
import { StationLayer } from "@/views/datavisualization/layers/StationLayer.js";
import { lngLatToWebMercator } from "@/mymap/index.js";

// Inject the map reference
const MapRef = inject("MapRef");

// Panel Dragging using VueUse
const panelRef = ref(null);
const handleRef = ref(null);
const { style: panelStyle } = useDraggable(panelRef, {
  initialValue: { x: 20, y: 120 },
  handle: handleRef,
});

// Autocomplete Search & Positioning States
const searchQuery = ref("");
const searchOptions = {
  routes: [
    { name: "M191", label: "M191路 (大梅沙总站 ⇄ 宝安客运中心)", coords: [114.058, 22.543] },
    { name: "223", label: "223路 (宁水花园总站 ⇄ 桃源村总站)", coords: [113.974, 22.537] },
    { name: "101", label: "101路 (深圳火车站 ⇄ 西丽动物园)", coords: [113.935, 22.580] },
    { name: "B601", label: "B601路 (蛇口港地铁站 ⇄ 深圳湾口岸)", coords: [113.945, 22.485] },
  ],
  stations: [
    { name: "smzx", label: "市民中心枢纽站 (地铁2/4号线)", coords: [114.058, 22.543] },
    { name: "sjzc", label: "世界之窗地铁站 (地铁1/2号线)", coords: [113.974, 22.537] },
    { name: "szbz", label: "深圳北站综合枢纽", coords: [114.029, 22.610] },
    { name: "gxy", label: "高新园地铁站 (科技园核心)", coords: [113.953, 22.539] },
  ]
};

// Component States
const activeMode = ref("draw"); // 'draw' | 'upload'
const isDrawing = ref(false);
const drawingPoints = ref([]);
const hasArea = ref(false);
const isUploading = ref(false);
const isDraggingFile = ref(false);

const fileInputRef = ref(null);
const fileName = ref("");
const fileSize = ref("");

// QGIS Dynamic Change Queue States
const activeModifications = ref([
  { id: 1, category: "route", categoryText: "线路", sub: "headway", subText: "更改发车频率", details: "高峰时段发车频率 +15%" },
  { id: 2, category: "station", categoryText: "站点", sub: "add", subText: "新增站点", details: "科技园北公交枢纽" }
]);

const showQgisMenu = ref(false);
const hoverQgisCat = ref("");
const showQgisParamsForm = ref(false);
const activeQgisCat = ref("station"); // 'station' | 'route' | 'fare' | 'mode' | 'road'
const activeQgisSub = ref("add");

// Custom input params for QGIS builder
const qgisParams = ref({
  name: "",
  desc: "",
  percent: 20,
  selectVal: "",
  creationMode: "click",
  stationType: "bus",
  lng: 114.058,
  lat: 22.543
});

// QGIS Primary Categories Predefinition
const qgisCategories = [
  { id: "station", name: "站点" },
  { id: "route", name: "线路" },
  { id: "fare", name: "票价" },
  { id: "mode", name: "创新模式" },
  { id: "road", name: "路网" }
];

// QGIS Secondary Sub-Options Predefinition
const qgisSubOptions = {
  station: [
    { id: "add", name: "新增站点" },
    { id: "delete", name: "删除站点" },
    { id: "edit", name: "更改站点" }
  ],
  route: [
    { id: "add_route", name: "新增线路" },
    { id: "edit_path", name: "修改站间路径" },
    { id: "headway", name: "更改发车频率" },
    { id: "vehicle", name: "车型" }
  ],
  fare: [
    { id: "fare_edit", name: "票价修改" },
    { id: "discount", name: "换乘优惠" }
  ],
  mode: [
    { id: "drt", name: "需求响应式公交" },
    { id: "air", name: "低空接驳" }
  ],
  road: [
    { id: "delete_link", name: "删除路段" },
    { id: "add_link", name: "新增路段" },
    { id: "capacity", name: "修改通过能力" }
  ]
};

// Parameter Input Visibility Computeds
const currentSubOptions = computed(() => qgisSubOptions[activeQgisCat.value] || []);
const showInputName = computed(() => {
  const sub = activeQgisSub.value;
  return ["add", "delete", "edit", "add_route", "delete_link", "add_link"].includes(sub);
});
const showInputDesc = computed(() => {
  const sub = activeQgisSub.value;
  return ["edit", "edit_path", "fare_edit", "drt", "air", "delete_link", "add_link"].includes(sub);
});
const showValueSlider = computed(() => {
  const sub = activeQgisSub.value;
  return ["headway", "capacity"].includes(sub);
});
const showValueSelect = computed(() => {
  const sub = activeQgisSub.value;
  return ["vehicle", "discount"].includes(sub);
});

// Options Lists for Specific Dropdowns
const currentParamSelects = computed(() => {
  if (activeQgisSub.value === "vehicle") {
    return ["12米纯电动单层", "12米双层纯电动", "8米中型纯电动支线", "18米铰接大容量"];
  }
  if (activeQgisSub.value === "discount") {
    return ["90分钟首次换乘免费", "换乘一律5折优惠", "常规公交与地铁换乘免费"];
  }
  return [];
});

// QGIS Menu Controls
function toggleQgisMenu() {
  showQgisMenu.value = !showQgisMenu.value;
  if (showQgisMenu.value) {
    hoverQgisCat.value = "";
  }
}

function closeQgisMenu() {
  showQgisMenu.value = false;
}

// Reset sub-category parameters
function resetSubParams() {
  qgisParams.value.name = "";
  qgisParams.value.desc = "";
  qgisParams.value.percent = 20;
  qgisParams.value.selectVal = currentParamSelects.value.length > 0 ? currentParamSelects.value[0] : "";
  
  // Reset station creation variables
  qgisParams.value.creationMode = "click";
  qgisParams.value.stationType = "bus";
  try {
    const center = MapRef.value?.map?.getCenter() || { lng: 114.058, lat: 22.543 };
    qgisParams.value.lng = Number(center.lng.toFixed(6));
    qgisParams.value.lat = Number(center.lat.toFixed(6));
  } catch (e) {
    console.warn("Could not get map center", e);
    qgisParams.value.lng = 114.058;
    qgisParams.value.lat = 22.543;
  }
}

function getSubName(catId, subId) {
  const subs = qgisSubOptions[catId] || [];
  const sub = subs.find((item) => item.id === subId);
  return sub ? sub.name : "配置项";
}

function cancelQgisParams() {
  showQgisParamsForm.value = false;
  disableMapCoordinatePicker();
  cleanUpNewStationTempLayers();
}

function formatStationDetails() {
  const name = qgisParams.value.name?.trim() || "未命名站点";
  const stationTypeText = qgisParams.value.stationType === "subway" ? "地铁站" : "公交站";
  const lng = Number(qgisParams.value.lng);
  const lat = Number(qgisParams.value.lat);
  const coordsText = Number.isFinite(lng) && Number.isFinite(lat)
    ? `坐标: ${lng.toFixed(6)}, ${lat.toFixed(6)}`
    : "坐标待确认";
  const desc = qgisParams.value.desc?.trim();
  return [name, stationTypeText, coordsText, desc].filter(Boolean).join(" / ");
}

function confirmAddModification() {
  if (!activeQgisSub.value) return;

  const currentCat = qgisCategories.find((item) => item.id === activeQgisCat.value);
  const currentSub = currentSubOptions.value.find((item) => item.id === activeQgisSub.value);

  let detailsText = "";
  let previewStationId = "";
  if (activeQgisCat.value === "station" && activeQgisSub.value === "add") {
    const stationPreview = buildStationPreviewFromParams();
    if (!stationPreview) {
      ElNotification({
        title: "站点坐标无效",
        message: "请先在地图上点选位置，或切换为手动输入并填写有效经纬度。",
        type: "warning",
        duration: 3000
      });
      return;
    }
    previewStationId = addStationPreviewToMap(stationPreview);
    detailsText = formatStationDetails();
  } else {
    if (showInputName.value && qgisParams.value.name) {
      detailsText += qgisParams.value.name;
    }
    if (showInputDesc.value && qgisParams.value.desc) {
      detailsText += detailsText ? ` (${qgisParams.value.desc})` : qgisParams.value.desc;
    }
    if (showValueSlider.value) {
      detailsText += ` 幅度: ${qgisParams.value.percent > 0 ? "+" : ""}${qgisParams.value.percent}%`;
    }
    if (showValueSelect.value && qgisParams.value.selectVal) {
      detailsText += ` 配置: ${qgisParams.value.selectVal}`;
    }
  }

  if (!detailsText) {
    detailsText = "默认调节参数";
  }

  activeModifications.value.push({
    id: Date.now(),
    category: activeQgisCat.value,
    categoryText: currentCat ? currentCat.name : "未知",
    sub: activeQgisSub.value,
    subText: currentSub ? currentSub.name : "配置项",
    details: detailsText,
    previewStationId
  });

  showQgisParamsForm.value = false;
  disableMapCoordinatePicker();
  cleanUpNewStationTempLayers();
  ElNotification({
    title: "修改项添加成功",
    message: `已向配置队列中追加了一行操作：[${currentSub ? currentSub.name : ""}]`,
    type: "success",
    duration: 3000
  });
}

// Select Sub-Category
function selectQgisSub(catId, subId) {
  try {
    disableMapCoordinatePicker();
    cleanUpNewStationTempLayers();
    activeQgisCat.value = catId;
    activeQgisSub.value = subId;
    resetSubParams();
    showQgisMenu.value = false;
    showQgisParamsForm.value = true;

    if (catId === 'station' && subId === 'add') {
      // Show all stations
      try {
        ensureEditStationLayer();
        loadExistingStations();
      } catch (e) {
        console.error("Error setting up station layer:", e);
      }
      
      // Auto enable map click coordinate picker
      nextTick(() => {
        enableMapCoordinatePicker();
      });
    }
  } catch (e) {
    console.error("selectQgisSub error:", e);
    showQgisMenu.value = false;
    showQgisParamsForm.value = true;
  }
}

// ---------------- ADD STATION & MAP INTERACTIVE PICKER LOGIC ----------------
let editStationLayer = null;
const existingStations = ref([]);
const addedStationPreviews = ref([]);
let mapClickPickerListener = null;

const NEW_STATION_SOURCE_ID = "new-station-temp-source";
const NEW_STATION_LAYER_ID = "new-station-temp-layer";
const NEW_STATION_INNER_LAYER_ID = "new-station-temp-inner-layer";
const ADDED_STATION_RING_SOURCE_ID = "added-station-ring-source";
const ADDED_STATION_RING_LAYER_ID = "added-station-ring-layer";

function ensureEditStationLayer() {
  if (!editStationLayer && MapRef.value) {
    editStationLayer = new StationLayer({ markerSize: 22 });
    MapRef.value.addLayer(editStationLayer);
  }
}

function syncEditStationLayerData() {
  if (editStationLayer) {
    editStationLayer.setData([...existingStations.value, ...addedStationPreviews.value]);
  }
}

async function loadExistingStations() {
  try {
    let modelName = "";
    const schemeRes = await getSchemeList();
    if (schemeRes.data && schemeRes.data.length > 0) {
      const firstScheme = schemeRes.data[0];
      const modelRes = await getModelList({ schemeName: firstScheme });
      if (modelRes.data && modelRes.data.length > 0) {
        modelName = modelRes.data[0].name;
      }
    }

    if (!modelName) {
      console.warn("No active model found, falling back to mock existing stations");
      generateMockExistingStations();
      return;
    }

    const res = await getLineAll({ datasource: modelName });
    const data = res.data || [];
    const stationsList = [];
    const coordsSet = new Set();
    const stationByCoord = new Map();
    
    data.forEach((line) => {
      if (line.routes) {
        line.routes.forEach((route) => {
          if (route.facilities) {
            route.facilities.forEach((fac) => {
              if (fac.coord && fac.facilityName && fac.coord.x && fac.coord.y) {
                const key = `${fac.coord.x.toFixed(2)}_${fac.coord.y.toFixed(2)}`;
                const text = [line.lineName, line.lineId, route.routeName, route.routeId].filter(Boolean).join(" ").toLowerCase();
                const type = /地铁|轨道|metro|subway|rail|mtr/.test(text) ? "subway" : "bus";
                
                if (!coordsSet.has(key)) {
                  coordsSet.add(key);
                  const station = {
                    name: fac.facilityName,
                    x: fac.coord.x,
                    y: fac.coord.y,
                    type,
                  };
                  stationByCoord.set(key, station);
                  stationsList.push(station);
                } else if (type === "subway") {
                  const station = stationByCoord.get(key);
                  if (station) station.type = "subway";
                }
              }
            });
          }
        });
      }
    });

    existingStations.value = stationsList;
    syncEditStationLayerData();
  } catch (e) {
    console.error("Failed to load existing stations:", e);
    generateMockExistingStations();
  }
}

function generateMockExistingStations() {
  const center = MapRef.value?.map?.getCenter() || { lng: 114.058, lat: 22.543 };
  const mockList = [];
  const names = ["市民中心站", "少年宫站", "会展中心站", "岗厦站", "华强北站", "购物公园站", "福田站", "莲花西站"];
  const types = ["subway", "subway", "bus", "bus", "subway", "bus", "subway", "bus"];
  
  names.forEach((name, idx) => {
    const offsetLng = (idx * 0.008 - 0.024) + (Math.random() - 0.5) * 0.002;
    const offsetLat = (idx * 0.004 - 0.012) + (Math.random() - 0.5) * 0.002;
    const lng = center.lng + offsetLng;
    const lat = center.lat + offsetLat;
    const mercator = lngLatToWebMercator(lng, lat);
    mockList.push({
      name,
      x: mercator[0],
      y: mercator[1],
      type: types[idx]
    });
  });
  
  existingStations.value = mockList;
  syncEditStationLayerData();
}

function buildStationPreviewFromParams() {
  const lng = Number(qgisParams.value.lng);
  const lat = Number(qgisParams.value.lat);
  if (!Number.isFinite(lng) || !Number.isFinite(lat)) return null;

  const [x, y] = lngLatToWebMercator(lng, lat);
  if (!Number.isFinite(x) || !Number.isFinite(y)) return null;

  return {
    id: `added-station-${Date.now()}-${Math.round(Math.random() * 10000)}`,
    name: qgisParams.value.name?.trim() || "未命名站点",
    x,
    y,
    lng,
    lat,
    type: qgisParams.value.stationType || "bus",
  };
}

function addedStationRingGeojson() {
  return {
    type: "FeatureCollection",
    features: addedStationPreviews.value.map((station) => ({
      type: "Feature",
      geometry: { type: "Point", coordinates: [station.lng, station.lat] },
      properties: {
        id: station.id,
        name: station.name,
      }
    }))
  };
}

function updateAddedStationRings() {
  if (!MapRef.value || !MapRef.value.map) return;
  const map = MapRef.value.map;
  const geojson = addedStationRingGeojson();

  if (!map.getSource(ADDED_STATION_RING_SOURCE_ID)) {
    map.addSource(ADDED_STATION_RING_SOURCE_ID, {
      type: "geojson",
      data: geojson
    });

    map.addLayer({
      id: ADDED_STATION_RING_LAYER_ID,
      type: "circle",
      source: ADDED_STATION_RING_SOURCE_ID,
      paint: {
        "circle-radius": [
          "interpolate",
          ["linear"],
          ["zoom"],
          10,
          2,
          12,
          5,
          14,
          10,
          16,
          17,
        ],
        "circle-color": "rgba(250, 204, 21, 0.03)",
        "circle-stroke-color": "#facc15",
        "circle-stroke-width": [
          "interpolate",
          ["linear"],
          ["zoom"],
          10,
          1.2,
          13,
          3,
          16,
          5.5,
        ],
        "circle-stroke-opacity": 0.95,
      }
    });
  } else {
    map.getSource(ADDED_STATION_RING_SOURCE_ID).setData(geojson);
  }
}

function cleanUpAddedStationRings() {
  if (!MapRef.value || !MapRef.value.map) return;
  const map = MapRef.value.map;
  if (map.getLayer(ADDED_STATION_RING_LAYER_ID)) map.removeLayer(ADDED_STATION_RING_LAYER_ID);
  if (map.getSource(ADDED_STATION_RING_SOURCE_ID)) map.removeSource(ADDED_STATION_RING_SOURCE_ID);
}

function addStationPreviewToMap(station) {
  ensureEditStationLayer();
  addedStationPreviews.value.push(station);
  syncEditStationLayerData();
  updateAddedStationRings();
  return station.id;
}

function removeStationPreviewFromMap(stationId) {
  if (!stationId) return;
  addedStationPreviews.value = addedStationPreviews.value.filter((station) => station.id !== stationId);
  syncEditStationLayerData();
  updateAddedStationRings();
}

function updateNewStationTempDot(lng, lat) {
  if (!MapRef.value || !MapRef.value.map) return;
  const map = MapRef.value.map;
  
  const geojson = {
    type: "Feature",
    geometry: { type: "Point", coordinates: [lng, lat] },
    properties: {}
  };

  if (!map.getSource(NEW_STATION_SOURCE_ID)) {
    map.addSource(NEW_STATION_SOURCE_ID, {
      type: "geojson",
      data: geojson
    });
    
    map.addLayer({
      id: NEW_STATION_LAYER_ID,
      type: "circle",
      source: NEW_STATION_SOURCE_ID,
      paint: {
        "circle-radius": 14,
        "circle-color": "#0f9f6e",
        "circle-opacity": 0.4,
        "circle-stroke-width": 2,
        "circle-stroke-color": "#ffffff"
      }
    });

    map.addLayer({
      id: NEW_STATION_INNER_LAYER_ID,
      type: "circle",
      source: NEW_STATION_SOURCE_ID,
      paint: {
        "circle-radius": 6,
        "circle-color": "#087a55",
        "circle-stroke-width": 1.5,
        "circle-stroke-color": "#ffffff"
      }
    });
  } else {
    map.getSource(NEW_STATION_SOURCE_ID).setData(geojson);
  }
}

function cleanUpNewStationTempLayers() {
  if (!MapRef.value || !MapRef.value.map) return;
  const map = MapRef.value.map;
  if (map.getLayer(NEW_STATION_LAYER_ID)) map.removeLayer(NEW_STATION_LAYER_ID);
  if (map.getLayer(NEW_STATION_INNER_LAYER_ID)) map.removeLayer(NEW_STATION_INNER_LAYER_ID);
  if (map.getSource(NEW_STATION_SOURCE_ID)) map.removeSource(NEW_STATION_SOURCE_ID);
}

function enableMapCoordinatePicker() {
  if (!MapRef.value || !MapRef.value.map) return;
  const map = MapRef.value.map;
  map.getCanvas().style.cursor = "crosshair";
  
  if (mapClickPickerListener) {
    MapRef.value.removeEventListener("handle:click", mapClickPickerListener);
  }
  
  mapClickPickerListener = MapRef.value.addEventListener("handle:click", (e) => {
    if (qgisParams.value.creationMode !== 'click') return;
    const [lng, lat] = e.data.lngLat;
    
    qgisParams.value.lng = Number(lng.toFixed(6));
    qgisParams.value.lat = Number(lat.toFixed(6));
    
    updateNewStationTempDot(lng, lat);
    
    ElNotification({
      title: "已捕获经纬度",
      message: `坐标设定为：[${lng.toFixed(6)}, ${lat.toFixed(6)}]`,
      type: "success",
      duration: 2500
    });
  });
}

function disableMapCoordinatePicker() {
  if (MapRef.value && mapClickPickerListener) {
    MapRef.value.removeEventListener("handle:click", mapClickPickerListener);
    mapClickPickerListener = null;
  }
  if (MapRef.value && MapRef.value.map) {
    MapRef.value.map.getCanvas().style.cursor = "";
  }
}



// Undo specific modification (撤回)
function undoModification(index) {
  const item = activeModifications.value[index];
  activeModifications.value.splice(index, 1);
  removeStationPreviewFromMap(item.previewStationId);
  ElNotification({
    title: "操作已撤回",
    message: `成功撤回配置项：[${item.subText}] ${item.details}`,
    type: "info",
    duration: 2500
  });
}

// Simulation Parameters placeholder (for build reference)
const scenarioConfig = ref({
  name: "福田高峰通勤保障场景",
  theme: "peak_hour",
  frequency: 15,
  priorityLane: 30,
  priceFactor: 1.0,
  multiAgent: true,
});

// Area Metrics Data
const areaMetrics = ref({
  vertices: 0,
  area: 0.0,
  perimeter: 0.0,
  center: [114.058, 22.543]
});

// Map Drawing Layer Source IDs
const SOURCE_ID = "study-area-source";
const FILL_LAYER_ID = "study-area-fill";
const STROKE_LAYER_ID = "study-area-stroke";
const DRAWING_SOURCE_ID = "drawing-temp-source";
const DRAWING_POINTS_LAYER_ID = "drawing-temp-points";
const DRAWING_LINE_LAYER_ID = "drawing-temp-line";
const LOCATOR_SOURCE_ID = "locator-temp-source";
const LOCATOR_LAYER_ID = "locator-temp-circle";

// Event Listener handles
let mapClickListener = null;
let mapMouseMoveListener = null;

// Mock Build States
const isBuilding = ref(false);
const buildProgress = ref(0);
const buildStatusText = computed(() => {
  if (buildProgress.value < 25) return "校验研究范围";
  if (buildProgress.value < 55) return "整理线路与站点数据";
  if (buildProgress.value < 85) return "应用场景参数";
  return "写入仿真配置";
});

// ---------------- QUICK POSITION SEARCH LOGIC ----------------

function handleSearchLocate(value) {
  if (!value) return;
  if (!MapRef.value || !MapRef.value.map) {
    ElNotification({
      title: "提示",
      message: "地图加载中，请稍后再试",
      type: "warning"
    });
    return;
  }

  // Find matches in either routes or stations database
  let match = searchOptions.routes.find(item => item.name === value);
  if (!match) {
    match = searchOptions.stations.find(item => item.name === value);
  }

  if (match) {
    const map = MapRef.value.map;
    const [lng, lat] = match.coords;

    // Smoothly fly to target coordinates
    map.flyTo({
      center: [lng, lat],
      zoom: 15.0,
      speed: 1.2,
      curve: 1.4,
      essential: true
    });

    // Add temporary locator circle layer
    if (map.getLayer(LOCATOR_LAYER_ID)) map.removeLayer(LOCATOR_LAYER_ID);
    if (map.getSource(LOCATOR_SOURCE_ID)) map.removeSource(LOCATOR_SOURCE_ID);

    map.addSource(LOCATOR_SOURCE_ID, {
      type: "geojson",
      data: {
        type: "Feature",
        geometry: { type: "Point", coordinates: [lng, lat] },
        properties: {}
      }
    });

    // Locator ring
    map.addLayer({
      id: LOCATOR_LAYER_ID,
      type: "circle",
      source: LOCATOR_SOURCE_ID,
      paint: {
        "circle-radius": 15,
        "circle-color": "rgba(21, 105, 222, 0.2)",
        "circle-stroke-width": 2,
        "circle-stroke-color": "#0b91b7",
        "circle-stroke-opacity": 0.8
      }
    });

    // Automatically clean up locator circle after 3 seconds
    setTimeout(() => {
      if (map && map.getStyle()) {
        if (map.getLayer(LOCATOR_LAYER_ID)) map.removeLayer(LOCATOR_LAYER_ID);
        if (map.getSource(LOCATOR_SOURCE_ID)) map.removeSource(LOCATOR_SOURCE_ID);
      }
    }, 3000);

    ElNotification({
      title: "定位聚焦成功",
      message: `已定位平滑聚焦到：${match.label} 附近。`,
      type: "success",
      duration: 3500
    });
  }
}

// Set drawing/uploading active tab
function setMode(mode) {
  if (isDrawing.value) {
    stopDrawingEvents();
  }
  activeMode.value = mode;
}

// ---------------- MAP LAYERING & RENDERING ----------------

// Clean up and reset everything
function clearArea() {
  hasArea.value = false;
  drawingPoints.value = [];
  isDrawing.value = false;
  isUploading.value = false;
  
  if (MapRef.value && MapRef.value.map) {
    MapRef.value.map.getCanvas().style.cursor = "";
  }
  
  cleanUpMapLayers();
  stopDrawingEvents();
  
  // Reset metrics
  areaMetrics.value = {
    vertices: 0,
    area: 0.0,
    perimeter: 0.0,
    center: [114.058, 22.543]
  };
}

// Clean up layers safely
function cleanUpMapLayers() {
  if (!MapRef.value || !MapRef.value.map) return;
  const map = MapRef.value.map;
  
  if (map.getLayer(FILL_LAYER_ID)) map.removeLayer(FILL_LAYER_ID);
  if (map.getLayer(STROKE_LAYER_ID)) map.removeLayer(STROKE_LAYER_ID);
  if (map.getSource(SOURCE_ID)) map.removeSource(SOURCE_ID);
  
  if (map.getLayer(DRAWING_POINTS_LAYER_ID)) map.removeLayer(DRAWING_POINTS_LAYER_ID);
  if (map.getLayer(DRAWING_LINE_LAYER_ID)) map.removeLayer(DRAWING_LINE_LAYER_ID);
  if (map.getSource(DRAWING_SOURCE_ID)) map.removeSource(DRAWING_SOURCE_ID);

  if (map.getLayer(LOCATOR_LAYER_ID)) map.removeLayer(LOCATOR_LAYER_ID);
  if (map.getSource(LOCATOR_SOURCE_ID)) map.removeSource(LOCATOR_SOURCE_ID);
}

// Render the final Study Area on the map as a thick black dashed line with translucent fill
function renderStudyArea(geojson) {
  if (!MapRef.value || !MapRef.value.map) return;
  const map = MapRef.value.map;
  
  // Remove drawing guides
  if (map.getLayer(DRAWING_POINTS_LAYER_ID)) map.removeLayer(DRAWING_POINTS_LAYER_ID);
  if (map.getLayer(DRAWING_LINE_LAYER_ID)) map.removeLayer(DRAWING_LINE_LAYER_ID);
  if (map.getSource(DRAWING_SOURCE_ID)) map.removeSource(DRAWING_SOURCE_ID);

  // Set up Study Area Source
  if (!map.getSource(SOURCE_ID)) {
    map.addSource(SOURCE_ID, {
      type: "geojson",
      data: geojson
    });
    
    // Add Fill
    map.addLayer({
      id: FILL_LAYER_ID,
      type: "fill",
      source: SOURCE_ID,
      paint: {
        "fill-color": "#000000",
        "fill-opacity": 0.08
      }
    });

    // Add Stroke (黑色粗虚线)
    map.addLayer({
      id: STROKE_LAYER_ID,
      type: "line",
      source: SOURCE_ID,
      paint: {
        "line-color": "#0e0e0f",
        "line-width": 5.0,
        "line-dasharray": [3.5, 2.5]
      }
    });
  } else {
    map.getSource(SOURCE_ID).setData(geojson);
  }

  // Auto focus map to the bounds of our polygon
  if (geojson && geojson.geometry && geojson.geometry.coordinates[0].length > 0) {
    const coords = geojson.geometry.coordinates[0];
    let minLng = Infinity, maxLng = -Infinity, minLat = Infinity, maxLat = -Infinity;
    
    coords.forEach(([lng, lat]) => {
      if (lng < minLng) minLng = lng;
      if (lng > maxLng) maxLng = lng;
      if (lat < minLat) minLat = lat;
      if (lat > maxLat) maxLat = lat;
    });

    map.fitBounds([[minLng, minLat], [maxLng, maxLat]], {
      padding: 100,
      duration: 1200
    });
  }
}

// Update the temporary drawing layers (points and line tracing to current mouse pointer)
function updateDrawingLayer(currentMouseLngLat = null) {
  if (!MapRef.value || !MapRef.value.map) return;
  const map = MapRef.value.map;

  // Compile drawing points geojson
  const pointsFeatures = drawingPoints.value.map((coord, idx) => ({
    type: "Feature",
    geometry: { type: "Point", coordinates: coord },
    properties: { index: idx, isStart: idx === 0 }
  }));

  const lineCoordinates = [...drawingPoints.value];
  if (currentMouseLngLat && drawingPoints.value.length > 0) {
    lineCoordinates.push(currentMouseLngLat);
  }

  // If closed or complete, close the geometry path
  if (hasArea.value && drawingPoints.value.length > 0) {
    lineCoordinates.push(drawingPoints.value[0]);
  }

  const drawingGeoJSON = {
    type: "FeatureCollection",
    features: [
      ...pointsFeatures,
      {
        type: "Feature",
        geometry: { type: "LineString", coordinates: lineCoordinates },
        properties: {}
      }
    ]
  };

  if (!map.getSource(DRAWING_SOURCE_ID)) {
    map.addSource(DRAWING_SOURCE_ID, {
      type: "geojson",
      data: drawingGeoJSON
    });

    // Drawing Line guide
    map.addLayer({
      id: DRAWING_LINE_LAYER_ID,
      type: "line",
      source: DRAWING_SOURCE_ID,
      paint: {
        "line-color": "#0b91b7",
        "line-width": 2,
        "line-dasharray": [2, 2]
      },
      filter: ["==", ["geometry-type"], "LineString"]
    });

    // Drawing Vertices nodes
    map.addLayer({
      id: DRAWING_POINTS_LAYER_ID,
      type: "circle",
      source: DRAWING_SOURCE_ID,
      paint: {
        "circle-radius": 6,
        "circle-color": ["case", ["get", "isStart"], "#0f9f6e", "#1569de"],
        "circle-stroke-width": 2,
        "circle-stroke-color": "#ffffff"
      },
      filter: ["==", ["geometry-type"], "Point"]
    });
  } else {
    map.getSource(DRAWING_SOURCE_ID).setData(drawingGeoJSON);
  }
}

// ---------------- INTERACTIVE DRAWING MECHANICS ----------------

function startDrawing() {
  if (!MapRef.value || !MapRef.value.map) {
    ElNotification({
      title: "提示",
      message: "地图正在载入中，请稍后再试",
      type: "warning"
    });
    return;
  }
  
  clearArea();
  isDrawing.value = true;
  drawingPoints.value = [];
  
  const map = MapRef.value.map;
  map.getCanvas().style.cursor = "crosshair";

  // Standard Mouse Click handler for adding points
  mapClickListener = MapRef.value.addEventListener("handle:click", (e) => {
    if (!isDrawing.value) return;
    const [lng, lat] = e.data.lngLat;
    
    // Check if clicked close to start point to auto-complete
    if (drawingPoints.value.length >= 3) {
      const startPt = drawingPoints.value[0];
      const distance = Math.hypot(startPt[0] - lng, startPt[1] - lat);
      
      // Around 30-50m threshold (in LngLat degrees approx 0.0004)
      if (distance < 0.00045) {
        finishDrawing();
        return;
      }
    }

    drawingPoints.value.push([lng, lat]);
    updateDrawingLayer();
  });

  // Mouse Move listener to show dynamic guidelines
  mapMouseMoveListener = MapRef.value.addEventListener("handle:mousemove", (e) => {
    if (!isDrawing.value || drawingPoints.value.length === 0) return;
    const [lng, lat] = e.data.lngLat;
    updateDrawingLayer([lng, lat]);
  });
}

function stopDrawingEvents() {
  isDrawing.value = false;
  
  if (MapRef.value) {
    const map = MapRef.value.map;
    if (map) map.getCanvas().style.cursor = "";
    
    if (mapClickListener) {
      MapRef.value.removeEventListener("handle:click", mapClickListener);
      mapClickListener = null;
    }
    if (mapMouseMoveListener) {
      MapRef.value.removeEventListener("handle:mousemove", mapMouseMoveListener);
      mapMouseMoveListener = null;
    }
  }
}

function finishDrawing() {
  if (drawingPoints.value.length < 3) return;
  stopDrawingEvents();

  const coords = [...drawingPoints.value];
  coords.push(coords[0]); // Complete polygon closure

  // Compute mock geometry stats
  calculateMetrics(coords);

  const geojson = {
    type: "Feature",
    geometry: {
      type: "Polygon",
      coordinates: [coords]
    },
    properties: {}
  };

  hasArea.value = true;
  renderStudyArea(geojson);

  ElNotification({
    title: "范围设定成功",
    message: "手绘研究区域已生成并锁定！",
    type: "success",
    duration: 3500
  });
}

// ---------------- FILE UPLOAD MOCK MECHANICS ----------------

function triggerFileInput() {
  if (isUploading.value) return;
  fileInputRef.value?.click();
}

function handleFileSelect(e) {
  const file = e.target.files?.[0];
  if (file) {
    processUploadedFile(file);
  }
}

// Process file with realistic mock load timers
function processUploadedFile(file) {
  const extension = file.name.split(".").pop()?.toLowerCase();
  if (extension !== "shp" && extension !== "geojson" && extension !== "json") {
    ElNotification({
      title: "格式错误",
      message: "仅支持上传 .shp, .json 或 .geojson 空间几何数据文件",
      type: "error"
    });
    return;
  }

  isUploading.value = true;
  clearArea();
  
  fileName.value = file.name;
  fileSize.value = (file.size / 1024).toFixed(1) + " KB";

  // Simulate parsing latency
  setTimeout(() => {
    isUploading.value = false;
    hasArea.value = true;

    // Center dynamically on Map Center
    const center = MapRef.value?.map?.getCenter() || { lng: 113.498, lat: 23.218 };
    const radius = 1.35; // standard radius km
    const sides = 9; // irregular nonagon
    
    const points = [];
    for (let i = 0; i < sides; i++) {
      const angle = (i / sides) * 2 * Math.PI;
      const noise = 0.82 + Math.sin(i * 2.5) * 0.18;
      const dist = radius * noise;
      
      const latOffset = (dist / 111.3) * Math.sin(angle);
      const lngOffset = (dist / (111.3 * Math.cos(center.lat * Math.PI / 180))) * Math.cos(angle);
      
      points.push([center.lng + lngOffset, center.lat + latOffset]);
    }
    points.push(points[0]); // Closed polygon

    calculateMetrics(points);

    const geojson = {
      type: "Feature",
      geometry: {
        type: "Polygon",
        coordinates: [points]
      },
      properties: {}
    };

    renderStudyArea(geojson);

    ElNotification({
      title: "Shapefile 解析成功",
      message: `已解析图层并定位到当前视口核心。`,
      type: "success",
      duration: 3500
    });
  }, 2000);
}

// Calculate realistic polygon metric parameters
function calculateMetrics(coords) {
  areaMetrics.value.vertices = coords.length - 1;
  
  // Mid points center calculation
  let sumLng = 0, sumLat = 0;
  coords.forEach(([lng, lat]) => {
    sumLng += lng;
    sumLat += lat;
  });
  const cLng = sumLng / coords.length;
  const cLat = sumLat / coords.length;
  areaMetrics.value.center = [cLng.toFixed(4), cLat.toFixed(4)];

  // Fake realistic area & perimeter derived mathematically from coordinates footprint
  let maxDist = 0;
  for (let i = 0; i < coords.length; i++) {
    for (let j = i + 1; j < coords.length; j++) {
      const dist = Math.hypot(coords[i][0] - coords[j][0], coords[i][1] - coords[j][1]);
      if (dist > maxDist) maxDist = dist;
    }
  }
  
  // Scale factor to convert degs to km
  const kmSpan = maxDist * 100;
  const area = Math.max(0.1, (kmSpan * kmSpan * 0.43)).toFixed(2);
  const perimeter = Math.max(0.5, (kmSpan * 3.14)).toFixed(2);

  areaMetrics.value.area = area;
  areaMetrics.value.perimeter = perimeter;
}

// ---------------- BUILD FLOW ----------------

let buildIntervalId = null;
let buildFinishTimerId = null;

function clearBuildTimers() {
  if (buildIntervalId) {
    clearInterval(buildIntervalId);
    buildIntervalId = null;
  }
  if (buildFinishTimerId) {
    clearTimeout(buildFinishTimerId);
    buildFinishTimerId = null;
  }
}

function buildScenario() {
  if (!hasArea.value || isBuilding.value) return;
  
  clearBuildTimers();
  isBuilding.value = true;
  buildProgress.value = 0;

  buildIntervalId = setInterval(() => {
    buildProgress.value += 1;

    if (buildProgress.value >= 100) {
      clearBuildTimers();
      buildFinishTimerId = setTimeout(() => {
        buildFinishTimerId = null;
        isBuilding.value = false;
        
        ElNotification({
          title: "仿真场景搭建成功",
          message: `仿真场景搭建成功，已录入 ${activeModifications.value.length} 项更改。`,
          type: "success",
          duration: 4500
        });
      }, 800);
    }
  }, 50);
}

// ---------------- COMPONENT CYCLE ----------------

onMounted(() => {
  if (MapRef.value && MapRef.value.map) {
    cleanUpMapLayers();
  }
});

// Watch MapRef loading dynamically
watch(MapRef, (newMap) => {
  if (newMap && newMap.map) {
    cleanUpMapLayers();
  }
});

onUnmounted(() => {
  clearBuildTimers();
  stopDrawingEvents();
  cleanUpMapLayers();
  
  if (editStationLayer) {
    editStationLayer.dispose();
    editStationLayer = null;
  }
  disableMapCoordinatePicker();
  cleanUpNewStationTempLayers();
  cleanUpAddedStationRings();
});
</script>

<style lang="scss" scoped>
/* LIGHT THEME UNIFICATION (Matching Data Visualization Panel Styles) */
.scenario-panel {
  position: fixed;
  z-index: var(--z-panel);
  width: min(460px, calc((100vw - 40px) / var(--app-panel-scale)));
  max-height: calc((100vh - 132px) / var(--app-panel-scale));
  background: var(--app-panel-bg);
  border: 1px solid var(--app-border);
  box-shadow: var(--app-shadow-sm);
  border-radius: var(--app-panel-radius);
  display: flex;
  flex-direction: column;
  color: var(--app-ink);
  user-select: none;
  overflow: hidden;
  scale: var(--app-panel-scale);
  transform-origin: top left;
  transition: border-color 0.2s ease;
  
  &:hover {
    border-color: rgba(21, 105, 222, 0.28);
  }
}

@media (max-width: 640px) {
  .scenario-panel {
    width: calc((100vw - 32px) / var(--app-panel-scale));
    max-height: calc((100vh - 104px) / var(--app-panel-scale));
  }
}

.panel-header {
  cursor: grab;
  display: flex;
  padding: var(--space-xs) var(--space-md);
  gap: var(--space-sm);
  align-items: center;
  min-height: 42px;
  background: rgba(21, 105, 222, 0.055);
  color: var(--app-blue);
  border-bottom: 1px solid rgba(21, 105, 222, 0.15);

  &:active {
    cursor: grabbing;
  }

  .header-title {
    display: flex;
    align-items: center;
    gap: var(--space-xs);
    font-size: 15px;
    font-weight: 700;
    letter-spacing: 0;
    width: 0;
    flex: 1;
    min-width: 0;

    span {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .icon {
      width: 17px;
      height: 17px;
      color: #1569de;
    }
  }

  .header-subtitle {
    font-size: 10px;
    color: var(--app-blue);
    border: 1px solid rgba(21, 105, 222, 0.25);
    padding: 1px 5px;
    border-radius: 4px;
    background: rgba(21, 105, 222, 0.08);
    font-family: "Outfit", monospace;
    font-weight: 600;
  }
}

.panel-content {
  flex: 1;
  overflow: hidden;
}

.inner-container {
  padding: var(--space-md);
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

/* Search positioning row */
.search-row {
  display: flex;
}

/* Steps Cards styling (Unified card layout) */
.section-card {
  background: rgba(253, 254, 255, 0.72);
  border: 1px solid rgba(21, 105, 222, 0.08);
  border-radius: var(--app-card-radius);
  padding: var(--space-sm);
  position: relative;
  overflow: hidden;

  .card-title {
    display: flex;
    align-items: center;
    gap: var(--space-xs);
    font-size: 13px;
    font-weight: 700;
    color: var(--app-ink);
    margin-bottom: var(--space-sm);

    .step-num {
      font-family: "Outfit", "Impact", monospace;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      min-width: 30px;
      height: 22px;
      padding: 0 var(--space-2xs);
      border-radius: 4px;
      background: rgba(21, 105, 222, 0.08);
      font-size: 12px;
      color: var(--app-blue);
      letter-spacing: 0.5px;
    }
  }
}

.border-none {
  border: none;
  background: transparent;
  padding: 0;
  margin-top: 4px;
}

.disabled-card {
  opacity: 0.45;
  pointer-events: none;
  filter: grayscale(60%);
}

/* Button & Tabs selectors */
.mode-selector {
  display: flex;
  background: var(--app-surface-soft);
  border-radius: var(--app-card-radius);
  padding: var(--space-2xs);
  gap: var(--space-2xs);
  margin-bottom: var(--space-sm);
  border: 1px solid rgba(0, 0, 0, 0.05);
}

.mode-btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2xs);
  background: transparent;
  border: none;
  color: var(--app-muted);
  font-size: 11px;
  font-weight: 600;
  min-height: 30px;
  padding: 0 var(--space-xs);
  border-radius: 4px;
  cursor: pointer;
  transition: background-color 0.2s ease, color 0.2s ease;

  &:hover {
    color: var(--app-ink);
    background: rgba(0, 0, 0, 0.03);
  }

  &.active {
    color: #f7fbff;
    background: var(--app-blue);
  }
}

.tab-pane {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);

  .help-text {
    font-size: 10.5px;
    color: var(--app-muted);
    line-height: 1.4;
    background: rgba(21, 105, 222, 0.04);
    padding: var(--space-xs) var(--space-sm);
    border-radius: 4px;
    border: 1px solid rgba(21, 105, 222, 0.1);

    .highlight-warn {
      color: #e67e22;
      font-weight: bold;
    }

    .highlight-success {
      color: var(--app-emerald-strong);
      font-weight: bold;
    }
  }
}

.btn-group {
  display: flex;
  gap: var(--space-xs);
  flex-wrap: wrap;
}

.upload-clear-actions {
  margin-top: var(--space-sm);
}

.block-btn {
  width: 100%;
}

.action-btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-xs);
  min-width: 0;
  min-height: 30px;
  padding: 0 var(--space-sm);
  font-size: 11.5px;
  font-weight: 600;
  border-radius: 4px;
  cursor: pointer;
  border: none;
  transition: background-color 0.2s ease, color 0.2s ease, border-color 0.2s ease;
  outline: none;

  &:disabled {
    opacity: 0.4;
    cursor: not-allowed;
  }
}

.primary-btn {
  background: var(--app-blue);
  color: #f7fbff;

  &:hover:not(:disabled) {
    background: var(--app-blue-strong);
  }
}

.danger-btn {
  background: var(--app-coral);
  color: #fff8f8;

  &:hover:not(:disabled) {
    background: #c63e4f;
  }
}

.secondary-btn {
  background: var(--app-card-bg);
  color: var(--app-muted);
  border: 1px solid rgba(21, 105, 222, 0.14);

  &:hover:not(:disabled) {
    background: var(--app-cyan-soft);
    color: var(--app-cyan-strong);
    border-color: rgba(11, 145, 183, 0.34);
  }
}

/* Simulated File Upload Dragbox */
.upload-box {
  border: 1.5px dashed rgba(21, 105, 222, 0.25);
  border-radius: var(--app-card-radius);
  padding: var(--space-lg) var(--space-md);
  text-align: center;
  cursor: pointer;
  transition: background-color 0.2s ease, border-color 0.2s ease;
  background: rgba(21, 105, 222, 0.02);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-xs);

  &:hover {
    border-color: #1569de;
    background: rgba(21, 105, 222, 0.05);
    
    .upload-icon {
      color: var(--app-blue);
    }
  }

  &.dragging {
    border-color: #0f9f6e;
    background: rgba(46, 204, 113, 0.06);
  }

  &.success {
    border-color: rgba(46, 204, 113, 0.4);
    background: rgba(46, 204, 113, 0.03);
  }

  .hidden-input {
    display: none;
  }

  .upload-icon {
    width: 26px;
    height: 26px;
    color: var(--app-muted);
    transition: color 0.2s ease;
  }

  .success-icon {
    width: 26px;
    height: 26px;
    color: #0f9f6e;

    polyline {
      stroke-dasharray: 24;
      stroke-dashoffset: 24;
      animation: check-draw var(--app-motion-slow) var(--app-ease-out) forwards;
    }
  }

  .upload-title {
    font-size: 11.5px;
    font-weight: 600;
    color: var(--app-ink);
  }

  .upload-success {
    color: var(--app-emerald-strong);
  }

  .upload-sub {
    font-size: 9.5px;
    color: #95a5a6;
    line-height: 1.3;
  }
}

/* Metrics area grids */
.area-metrics {
  background: rgba(21, 105, 222, 0.03);
  border-radius: var(--app-card-radius);
  border: 1px solid rgba(21, 105, 222, 0.08);
  padding: var(--space-xs);

  .metrics-grid {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: var(--space-xs);
  }

  .metric-item {
    background: var(--app-card-bg);
    border-radius: 4px;
    padding: var(--space-xs);
    border: 1px solid rgba(21, 105, 222, 0.1);

    .m-label {
      font-size: 9px;
      color: var(--app-muted);
      margin-bottom: 1px;
    }

    .m-val {
      font-size: 12px;
      font-weight: 700;
      color: var(--app-ink);
      font-family: "Outfit", monospace;

      .unit {
        font-size: 9px;
        color: var(--app-muted);
        font-weight: normal;
        margin-left: 1px;
      }
    }

    .compact-val {
      font-size: 10px;
      letter-spacing: -0.2px;
    }
  }
}

/* QGIS Modification Changelog Queue Style */
.modifications-queue {
  background: rgba(0, 0, 0, 0.02);
  border: 1px solid rgba(21, 105, 222, 0.1);
  border-radius: var(--app-card-radius);
  padding: var(--space-sm);
  margin-bottom: var(--space-sm);
  
  .queue-title-row {
    font-size: 10.5px;
    font-weight: bold;
    color: var(--app-muted);
    margin-bottom: var(--space-xs);
    border-bottom: 1px solid rgba(0, 0, 0, 0.05);
    padding-bottom: 4px;
  }

  .empty-queue-placeholder {
    display: flex;
    align-items: center;
    justify-content: center;
    padding: var(--space-md) 0;
    color: #95a5a6;
    font-size: 10.5px;
  }

  .queue-list {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .queue-item {
    display: flex;
    align-items: center;
    background: var(--app-card-bg);
    border: 1px solid rgba(0, 0, 0, 0.05);
    border-radius: 4px;
    padding: var(--space-xs);
    gap: var(--space-xs);

    .mod-badge {
      font-size: 9.5px;
      font-weight: bold;
      padding: 1px 5px;
      border-radius: 3px;
      flex-shrink: 0;

      &.station { background: var(--app-emerald-soft); color: var(--app-emerald-strong); border: 1px solid rgba(15, 159, 110, 0.26); }
      &.route { background: var(--app-blue-soft); color: var(--app-blue-strong); border: 1px solid rgba(21, 105, 222, 0.24); }
      &.fare { background: var(--app-cyan-soft); color: var(--app-cyan-strong); border: 1px solid rgba(11, 145, 183, 0.24); }
      &.mode { background: var(--app-amber-soft); color: var(--app-amber); border: 1px solid rgba(217,119,6,0.28); }
      &.road { background: var(--app-coral-soft); color: var(--app-coral); border: 1px solid rgba(220,76,93,0.26); }
    }

    .mod-details {
      flex: 1;
      min-width: 0;

      .mod-type {
        font-size: 10.5px;
        font-weight: bold;
        color: var(--app-ink);
      }
      
      .mod-desc {
        font-size: 9.5px;
        color: var(--app-muted);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        margin-top: 1px;
      }
    }

    .undo-btn {
      background: transparent;
      border: none;
      color: #95a5a6;
      font-size: 10px;
      cursor: pointer;
      display: flex;
      align-items: center;
      gap: 3px;
      padding: 3px 6px;
      border-radius: 3px;
      transition: background-color 0.2s ease, color 0.2s ease;
      flex-shrink: 0;

      &:hover {
        background: #fdf2f2;
        color: #dc4c5d;
      }
    }
  }
}

.add-item-trigger {
  margin-top: 6px;
  background: var(--app-surface-strong);
  border-style: dashed;
  border-width: 1.5px;
  
  &:hover:not(:disabled) {
    background: rgba(21, 105, 222, 0.04);
    border-color: var(--app-cyan);
    color: var(--app-cyan-strong);
  }
}

/* QGIS STYLE NESTED TWO-COLUMN BUILDER SYSTEM */
.qgis-nested-builder {
  background: var(--app-card-bg);
  border: 1px solid rgba(21, 105, 222, 0.12);
  border-radius: var(--app-card-radius);
  padding: var(--space-sm);
  margin-top: var(--space-sm);
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);

  .builder-title-row {
    font-size: 11px;
    font-weight: bold;
    color: var(--app-blue);
    border-bottom: 1px solid rgba(21, 105, 222, 0.15);
    padding-bottom: var(--space-xs);
  }

  .qgis-columns {
    display: flex;
    border: 1px solid rgba(21, 105, 222, 0.13);
    border-radius: 4px;
    overflow: hidden;
    height: 160px;
  }

  /* Left column: Categories selector */
  .qgis-left-col {
    width: 35%;
    background: var(--app-surface-soft);
    border-right: 1px solid rgba(21, 105, 222, 0.13);
    display: flex;
    flex-direction: column;
  }

  .qgis-cat-item {
    padding: var(--space-xs) var(--space-sm);
    font-size: 11px;
    font-weight: bold;
    color: var(--app-muted);
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: space-between;
    transition: background-color 0.2s ease, color 0.2s ease;
    border-bottom: 1px solid rgba(0,0,0,0.03);

    &:hover {
      background: rgba(21, 105, 222, 0.04);
      color: var(--app-blue);
    }

    &.active {
      background: var(--app-card-bg);
      color: var(--app-blue);
      box-shadow: inset 0 0 0 1px rgba(21, 105, 222, 0.12);
      
      .arrow {
        color: var(--app-blue);
        opacity: 1;
      }
    }

    .arrow {
      color: var(--app-muted-soft);
      opacity: 0.5;
    }
  }

  /* Right column: Options & Parameters */
  .qgis-right-col {
    width: 65%;
    background: var(--app-card-bg);
    padding: var(--space-sm);
    box-sizing: border-box;
  }

  .qgis-right-content {
    display: flex;
    flex-direction: column;
    gap: var(--space-sm);
  }

  .sub-option-select {
    display: flex;
    flex-direction: column;
    gap: 3px;
  }

  .sub-params-box {
    display: flex;
    flex-direction: column;
    gap: var(--space-xs);
    border-top: 1px solid rgba(0, 0, 0, 0.05);
    padding-top: var(--space-xs);
  }

  .param-row {
    display: flex;
    flex-direction: column;
    gap: var(--space-2xs);

    .slider-header-sub {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: 10px;
      color: #60758e;
      font-weight: bold;

      .slider-val {
        color: var(--app-blue);
        font-family: monospace;
      }
    }

    :deep(.el-slider) {
      margin: 2px 6px;
      --el-slider-main-bg-color: #1569de;
    }
  }

  .builder-actions {
    display: flex;
    justify-content: flex-end;
    gap: var(--space-xs);
    border-top: 1px solid rgba(0, 0, 0, 0.05);
    padding-top: var(--space-xs);

    .action-btn {
      flex: none;
      padding: 0 16px;
    }
  }
}

/* General Param Styles */
.font-bold {
  font-weight: bold !important;
}

.form-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 10px;

  .form-label {
    font-size: 11px;
    font-weight: 600;
    color: #60758e;
  }

  .block-select {
    width: 100%;
  }

  .slider-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-size: 11px;
    color: #60758e;
    font-weight: 600;

    .slider-val {
      color: #1569de;
      font-family: "Outfit", monospace;
      font-weight: bold;
    }
  }

  :deep(.el-slider) {
    --el-slider-main-bg-color: #1569de;
    --el-slider-runway-bg-color: #e4e7ed;
  }
}

.form-label {
  font-size: 10px;
  color: #60758e;
  font-weight: 600;
}

.switch-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: rgba(21, 105, 222, 0.03);
  border: 1px solid rgba(21, 105, 222, 0.08);
  padding: 6px 10px;
  border-radius: 6px;
  margin-top: 4px;

  .switch-col {
    display: flex;
    flex-direction: column;
    gap: 1px;
  }

  .switch-label {
    font-size: 11px;
    color: var(--app-ink);
    font-weight: bold;
  }

  .switch-desc {
    font-size: 9px;
    color: var(--app-muted);
  }

  :deep(.el-switch) {
    --el-switch-on-color: var(--app-cyan);
  }
}

/* Compile Trigger Button */
.build-scenario-btn {
  width: 100%;
  background: var(--app-emerald);
  color: #f7fffb;
  border: none;
  min-height: 40px;
  border-radius: var(--app-card-radius);
  font-size: 12.5px;
  font-weight: bold;
  cursor: pointer;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-xs);
  transition:
    background-color var(--app-motion-normal) var(--app-ease-out),
    transform var(--app-motion-fast) var(--app-ease-press);

  &:hover:not(:disabled) {
    background: var(--app-emerald-strong);

    .btn-icon {
      transform: translateX(1px);
    }
  }

  .btn-icon {
    transition: transform var(--app-motion-normal) var(--app-ease-out);
  }

  &:disabled {
    opacity: 0.5;
    background: color-mix(in oklch, var(--app-muted-soft) 70%, white);
    box-shadow: none;
    cursor: not-allowed;
  }

}

.build-overlay {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(255, 255, 255, 0.96);
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}

.loader-content {
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.loader-spinner {
  width: 18px;
  height: 18px;
  border: 2px solid #cbd5e1;
  border-radius: 50%;
  border-top-color: var(--app-cyan);
  animation: rotate-spinner 0.8s linear infinite;

  &.large {
    width: 34px;
    height: 34px;
    border-width: 3px;
    margin-bottom: 12px;
  }
}

.complete-check {
  width: 36px;
  height: 36px;
  margin-bottom: 12px;
  color: var(--app-emerald);
}

.complete-check-path {
  stroke: currentColor;
  stroke-width: 2.6;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-dasharray: 28;
  stroke-dashoffset: 28;
  animation: check-draw var(--app-motion-slow) var(--app-ease-out) forwards;
}

.loader-title {
  font-size: 15px;
  font-weight: 700;
  color: var(--app-blue);
  margin-bottom: 4px;
}

.loader-status {
  font-size: 12px;
  color: var(--app-muted);
  margin-bottom: 10px;
}

.loader-bar-bg {
  width: 80%;
  height: 5px;
  background: color-mix(in oklch, var(--app-blue-soft) 65%, white);
  border-radius: 3px;
  overflow: hidden;
  margin-bottom: 4px;
}

.loader-bar {
  height: 100%;
  background: var(--app-blue);
  border-radius: 3px;
  transition: width 0.1s linear;
}

.progress-num {
  font-family: "Outfit", monospace;
  font-size: 11px;
  font-weight: bold;
  color: var(--app-muted);
}

/* Animations */
@keyframes rotate-spinner {
  to { transform: rotate(360deg); }
}

@keyframes check-draw {
  to { stroke-dashoffset: 0; }
}

/* Transitions */
.fade-enter-active, .fade-leave-active {
  transition: opacity 0.3s ease;
}
.fade-enter-from, .fade-leave-to {
  opacity: 0;
}

.fade-slide-enter-active, .fade-slide-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}
.fade-slide-enter-from, .fade-slide-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
/* MacOS / QGIS Context Menu Style */
.mac-os-menu {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.mac-menu-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 14px;
  cursor: pointer;
  color: #333;
  font-size: 11.5px;
  border-radius: 4px;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.mac-menu-item:hover {
  background: var(--app-blue);
  color: #fff;
}

.mac-menu-item:hover .menu-arrow {
  color: #fff;
}

.menu-label {
  pointer-events: none;
}

.menu-arrow {
  color: #888;
  pointer-events: none;
}

/* Premium Right Sidebar Configuration Panel */
.scenario-right-panel {
  position: fixed;
  z-index: var(--z-panel);
  width: 400px;
  max-height: calc((100vh - 132px) / var(--app-panel-scale));
  right: var(--app-edge);
  top: 120px;
  background: var(--app-panel-bg);
  border: 1px solid var(--app-border);
  box-shadow: var(--app-shadow-sm);
  border-radius: var(--app-panel-radius);
  display: flex;
  flex-direction: column;
  color: var(--app-ink);
  user-select: none;
  overflow: hidden;
  scale: var(--app-panel-scale);
  transform-origin: top right;
  transition: border-color 0.2s ease;

  &:hover {
    border-color: rgba(21, 105, 222, 0.28);
  }

  .panel-header {
    display: flex;
    padding: var(--space-xs) var(--space-md);
    gap: var(--space-sm);
    align-items: center;
    min-height: 42px;
    background: rgba(21, 105, 222, 0.055);
    color: var(--app-blue);
    border-bottom: 1px solid rgba(21, 105, 222, 0.15);

    .header-title {
      display: flex;
      align-items: center;
      gap: var(--space-xs);
      font-size: 15px;
      font-weight: 700;
      letter-spacing: 0;
      width: 0;
      flex: 1;
      min-width: 0;

      span {
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .icon {
        width: 17px;
        height: 17px;
        color: #1569de;
      }
    }
  }

  .qgis-nested-builder {
    border: none;
    box-shadow: none;
    padding: 0;
    margin-top: 0;
    background: transparent;

    .builder-title-row {
      font-size: 12px;
      color: var(--app-blue);
      border-bottom: 1px solid rgba(21, 105, 222, 0.15);
      padding-bottom: var(--space-xs);
      margin-bottom: var(--space-xs);
    }

    .builder-actions {
      border-top: 1px solid rgba(0, 0, 0, 0.05);
      padding-top: var(--space-xs);
      margin-top: var(--space-xs);
    }
  }
}
</style>

<style>
/* Un-scoped CSS for el-popover teleported to body */
.mac-os-popover {
  padding: var(--space-2xs) !important;
  background: var(--app-card-bg) !important;
  border: 1px solid rgba(21, 105, 222, 0.12) !important;
  box-shadow: var(--app-shadow-sm) !important;
  border-radius: var(--app-card-radius) !important;
}

/* Station configuration coordinate row styling */
.coordinates-row {
  display: flex;
  gap: var(--space-sm);
  margin-top: var(--space-2xs);
}

.coordinates-row .coord-col {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--space-2xs);
}

.coordinates-row .coord-input {
  width: 100%;
}

.coordinates-row .coord-input .el-input__wrapper {
  padding-left: 6px;
  padding-right: 6px;
}

.coordinates-row .coord-input .el-input-number__increase,
.coordinates-row .coord-input .el-input-number__decrease {
  display: none;
}

.creation-mode-group {
  width: 100%;
  display: flex;
}

.creation-mode-group .el-radio-button {
  flex: 1;
}

.creation-mode-group .el-radio-button__inner {
  width: 100%;
  font-size: 10.5px;
  padding: 6px 12px;
}

/* Pulsing guidance box */
.click-guidance-box {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  background: rgba(16, 185, 129, 0.06);
  border: 1px dashed rgba(16, 185, 129, 0.25);
  border-radius: var(--app-card-radius);
  padding: var(--space-xs) var(--space-sm);
  margin-top: var(--space-2xs);
  color: #047857;
  font-size: 10.5px;
  line-height: 1.4;
}

.click-guidance-box .guidance-dot {
  width: 7px;
  height: 7px;
  background-color: #0f9f6e;
  border-radius: 50%;
  flex-shrink: 0;
}

</style>
