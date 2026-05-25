<!-- Scenario Construction (场景搭建) View -->
<template>
  <div ref="panelRef" :style="panelStyle" class="scenario-panel">
    <!-- Panel Header / Drag Handle (Unified MCard2 Style) -->
    <div ref="handleRef" class="panel-header">
      <div class="header-title">
        <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"></path>
          <polyline points="3.27 6.96 12 12.01 20.73 6.96"></polyline>
          <line x1="12" y1="22.08" x2="12" y2="12"></line>
        </svg>
        <span>场景搭建器 (Scenario Builder)</span>
      </div>
      <div class="header-subtitle">纯前端控制台</div>
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
              placeholder="🔍 输入关键字打字搜线路或站点，如 M191、市民中心"
              class="block-select"
              @change="handleSearchLocate"
            >
              <el-option-group label="公交线路 (打字搜索)">
                <el-option
                  v-for="item in searchOptions.routes"
                  :key="item.name"
                  :label="item.label"
                  :value="item.name"
                />
              </el-option-group>
              <el-option-group label="公交站点 (打字搜索)">
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
              <span v-else class="highlight-success">范围已锁定！可在下方清除或重新绘制。</span>
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
                <span>完成手绘 (需 ≥3 点)</span>
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
            >
              <input 
                ref="fileInputRef"
                type="file" 
                accept=".shp,.json,.geojson" 
                class="hidden-input" 
                @change="handleFileSelect"
              />
              <template v-if="isUploading">
                <div class="loader-spinner"></div>
                <div class="upload-title text-pulse">正在解析 SHP 二进制数据...</div>
                <div class="upload-sub">校验拓扑关系与投影坐标系</div>
              </template>
              <template v-else-if="hasArea">
                <svg class="success-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                  <polyline points="20 6 9 17 4 12"></polyline>
                </svg>
                <div class="upload-title glow-green">文件解析完成！</div>
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
            
            <div v-if="hasArea" class="btn-group" style="margin-top: 10px;">
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
                <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="#bdc3c7" stroke-width="2">
                  <circle cx="12" cy="12" r="10"></circle>
                  <line x1="12" y1="8" x2="12" y2="12"></line>
                  <line x1="12" y1="16" x2="12.01" y2="16"></line>
                </svg>
                <span>无修改配置项，请在下方点击添加</span>
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

          <!-- The parameter form that appears AFTER selecting a sub-option from the menu -->
          <Transition name="fade-slide">
            <div v-if="showQgisParamsForm" class="qgis-nested-builder params-only-builder">
              <div class="builder-title-row">
                <span>配置: {{ getSubName(activeQgisCat, activeQgisSub) }}</span>
              </div>
              
              <div class="qgis-right-content params-box-content">
                <!-- Contextual Sub Parameter Inputs -->
                <div class="sub-params-box">
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
                </div>
              </div>
              
              <!-- Action Bottom Row -->
              <div class="builder-actions">
                <button class="action-btn secondary-btn" @click="cancelQgisParams">取消</button>
                <button class="action-btn primary-btn" @click="confirmAddModification">确认添加</button>
              </div>
            </div>
          </Transition>
        </div>

        <!-- STEP 3: Run -->
        <div class="section-card border-none" :class="{ 'disabled-card': !hasArea }">
          <button 
            class="build-scenario-btn" 
            :disabled="!hasArea || isBuilding"
            @click="buildScenario"
          >
            <span class="pulse-ring" v-if="hasArea && !isBuilding"></span>
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.5" class="btn-icon">
              <polyline points="22 12 18 12 15 21 9 3 6 12 2 12"></polyline>
            </svg>
            <span>{{ isBuilding ? '正在构建微观场景...' : '生成并锁定仿真场景' }}</span>
          </button>
        </div>
      </div>
    </el-scrollbar>

    <!-- HIGH-TECH LOADER OVERLAY (Light Theme Unification) -->
    <Transition name="fade">
      <div v-if="isBuilding" class="build-overlay">
        <div class="loader-content">
          <div class="tech-scanner"></div>
          <div class="loader-title text-pulse">SCENARIO GENERATING</div>
          <div class="loader-bar-bg">
            <div class="loader-bar" :style="{ width: `${buildProgress}%` }"></div>
          </div>
          <div class="progress-num">{{ buildProgress }}%</div>
          
          <!-- Terminal Logs -->
          <div class="terminal-logs" ref="terminalRef">
            <div v-for="(log, idx) in visibleLogs" :key="idx" :class="['log-line', log.type]">
              <span class="timestamp">[{{ log.time }}]</span>
              <span class="message">{{ log.msg }}</span>
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
  selectVal: ""
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

function selectQgisSub(catId, subId) {
  activeQgisCat.value = catId;
  activeQgisSub.value = subId;
  resetSubParams();
  showQgisMenu.value = false;
  showQgisParamsForm.value = true;
}

function getSubName(catId, subId) {
  const subs = qgisSubOptions[catId] || [];
  const sub = subs.find(s => s.id === subId);
  return sub ? sub.name : "配置项";
}

function resetSubParams() {
  qgisParams.value.name = "";
  qgisParams.value.desc = "";
  qgisParams.value.percent = 20;
  qgisParams.value.selectVal = currentParamSelects.value.length > 0 ? currentParamSelects.value[0] : "";
}

function cancelQgisParams() {
  showQgisParamsForm.value = false;
}

function confirmAddModification() {
  if (!activeQgisSub.value) return;

  const currentCat = qgisCategories.find(c => c.id === activeQgisCat.value);
  const currentSub = currentSubOptions.value.find(s => s.id === activeQgisSub.value);

  // Compile detailed text based on selected inputs
  let detailsText = "";
  if (showInputName.value && qgisParams.value.name) {
    detailsText += qgisParams.value.name;
  }
  if (showInputDesc.value && qgisParams.value.desc) {
    detailsText += detailsText ? ` (${qgisParams.value.desc})` : qgisParams.value.desc;
  }
  if (showValueSlider.value) {
    detailsText += ` 幅度: ${qgisParams.value.percent > 0 ? '+' : ''}${qgisParams.value.percent}%`;
  }
  if (showValueSelect.value && qgisParams.value.selectVal) {
    detailsText += ` 配置: ${qgisParams.value.selectVal}`;
  }

  // Fallback defaults
  if (!detailsText) {
    detailsText = "默认调节参数";
  }

  activeModifications.value.push({
    id: Date.now(),
    category: activeQgisCat.value,
    categoryText: currentCat ? currentCat.name : "未知",
    sub: activeQgisSub.value,
    subText: currentSub ? currentSub.name : "配置项",
    details: detailsText
  });

  showQgisParamsForm.value = false;
  ElNotification({
    title: "修改项添加成功",
    message: `已向配置队列中追加了一行操作：[${currentSub ? currentSub.name : ''}]`,
    type: "success",
    duration: 3000
  });
}

// Undo specific modification (撤回)
function undoModification(index) {
  const item = activeModifications.value[index];
  activeModifications.value.splice(index, 1);
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
const visibleLogs = ref([]);
const terminalRef = ref(null);

const terminalLogsPool = [
  { time: "00:01.02", msg: "正在校验研究区域范围拓扑关系...", type: "info" },
  { time: "00:01.85", msg: "边界闭合验证成功: 空间拓扑结构合规", type: "success" },
  { time: "00:02.40", msg: "正在识别区域内公交基础设施... 发现 32 条公交线路", type: "info" },
  { time: "00:03.10", msg: "正在提取区域内公交场站空间数据... 锁定 84 个站点", type: "info" },
  { time: "00:03.95", msg: "正在加载福田核心区出行 OD 矩阵，编译 15,480 个多智能体...", type: "info" },
  { time: "00:04.60", msg: "多智能体微观出行选择模型搭载完毕 (自适应通勤决策激活)", type: "success" },
  { time: "00:05.10", msg: "正在写入场景策略控制队列中录入的各项参数修改...", type: "info" },
  { time: "00:05.80", msg: "自适应网络交通拥堵状态参数计算中...", type: "info" },
  { time: "00:06.40", msg: "正在生成仿真场景编译最终状态...", type: "info" },
  { time: "00:06.90", msg: "场景控制边界锁定，底图渲染层 [study-area-stroke] 加载", type: "success" },
  { time: "00:07.20", msg: "纯前端微观场景仿真搭建成功，准备就绪。", type: "success" }
];

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

    // Add temporary high-tech locator pulse circle layer
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

    // Blue glowing locator ring
    map.addLayer({
      id: LOCATOR_LAYER_ID,
      type: "circle",
      source: LOCATOR_SOURCE_ID,
      paint: {
        "circle-radius": 15,
        "circle-color": "rgba(21, 105, 222, 0.2)",
        "circle-stroke-width": 2,
        "circle-stroke-color": "#1569de",
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
        "line-color": "#409eff", // Blue active line
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
        "circle-color": ["case", ["get", "isStart"], "#10b981", "#1569de"],
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

// ---------------- SCENARIO GENERATION SIMULATION ----------------

function buildScenario() {
  if (!hasArea.value) return;
  
  isBuilding.value = true;
  buildProgress.value = 0;
  visibleLogs.value = [];

  // Sequential simulated build progress
  const interval = setInterval(() => {
    buildProgress.value += 1;
    
    // Add logs based on progress threshold
    const logIndex = Math.floor((buildProgress.value / 100) * terminalLogsPool.length);
    if (logIndex > visibleLogs.value.length && logIndex <= terminalLogsPool.length) {
      visibleLogs.value.push(terminalLogsPool[visibleLogs.value.length]);
      
      // Auto scroll terminal log window
      nextTick(() => {
        if (terminalRef.value) {
          terminalRef.value.scrollTop = terminalRef.value.scrollHeight;
        }
      });
    }

    if (buildProgress.value >= 100) {
      clearInterval(interval);
      setTimeout(() => {
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
  stopDrawingEvents();
  cleanUpMapLayers();
});
</script>

<style lang="scss" scoped>
/* LIGHT THEME UNIFICATION (Matching Data Visualization Panel Styles) */
.scenario-panel {
  position: fixed;
  z-index: 1300;
  width: 400px;
  max-height: calc((100vh - 150px) / 0.8);
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid rgba(21, 105, 222, 0.18);
  box-shadow: 0 10px 30px rgba(15, 66, 125, 0.12);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  color: #2c3e50;
  font-family: "PingFang SC", "Microsoft YaHei", sans-serif;
  user-select: none;
  overflow: hidden;
  scale: 0.8;
  transform-origin: top left;
  transition: box-shadow 0.3s ease, border-color 0.3s ease;
  
  &:hover {
    border-color: rgba(21, 105, 222, 0.35);
    box-shadow: 0 12px 35px rgba(15, 66, 125, 0.18);
  }
}

.panel-header {
  cursor: move;
  display: flex;
  padding: 5px 12px;
  gap: 10px;
  align-items: center;
  line-height: 32px;
  background: linear-gradient(to bottom, rgba(21, 105, 222, 0.12) 0%, rgba(21, 105, 222, 0.04) 100%);
  color: #1569de;
  border-bottom: 1px solid rgba(21, 105, 222, 0.15);

  &::before {
    content: "";
    display: block;
    width: 3px;
    height: 16px;
    border-radius: 2px;
    background-color: #1569de;
  }

  .header-title {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 15px;
    font-weight: 700;
    letter-spacing: 0.5px;
    width: 0;
    flex: 1;

    .icon {
      width: 17px;
      height: 17px;
      color: #1569de;
    }
  }

  .header-subtitle {
    font-size: 10px;
    color: #1569de;
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
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* Search positioning row */
.search-row {
  margin: 2px 0;
}

/* Steps Cards styling (Unified card layout) */
.section-card {
  background: #ffffff;
  border: 1px solid #d9d9d9;
  border-radius: 6px;
  padding: 12px;
  transition: all 0.3s ease;
  position: relative;
  overflow: hidden;

  .card-title {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 13px;
    font-weight: bold;
    color: #1a365d;
    margin-bottom: 10px;

    .step-num {
      font-family: "Outfit", "Impact", monospace;
      font-size: 14px;
      color: #1569de;
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
  background: #f4f6f8;
  border-radius: 6px;
  padding: 2.5px;
  gap: 2px;
  margin-bottom: 10px;
  border: 1px solid rgba(0, 0, 0, 0.05);
}

.mode-btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  background: transparent;
  border: none;
  color: #7f8c8d;
  font-size: 11px;
  font-weight: 600;
  padding: 5px 0;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.25s ease;

  &:hover {
    color: #1a365d;
    background: rgba(0, 0, 0, 0.03);
  }

  &.active {
    color: #ffffff;
    background: #1569de;
    box-shadow: 0 2px 6px rgba(21, 105, 222, 0.2);
  }
}

.tab-pane {
  display: flex;
  flex-direction: column;
  gap: 8px;

  .help-text {
    font-size: 10.5px;
    color: #7f8c8d;
    line-height: 1.4;
    background: rgba(21, 105, 222, 0.04);
    padding: 6px 10px;
    border-radius: 4px;
    border-left: 2.5px solid rgba(21, 105, 222, 0.4);

    .highlight-warn {
      color: #e67e22;
      font-weight: bold;
    }

    .highlight-success {
      color: #27ae60;
      font-weight: bold;
    }
  }
}

.btn-group {
  display: flex;
  gap: 8px;
}

.block-btn {
  width: 100%;
}

.action-btn {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 28px;
  font-size: 11.5px;
  font-weight: 600;
  border-radius: 4px;
  cursor: pointer;
  border: none;
  transition: all 0.2s ease;
  outline: none;

  &:disabled {
    opacity: 0.4;
    cursor: not-allowed;
  }
}

.primary-btn {
  background: #1569de;
  color: #fff;
  box-shadow: 0 2px 6px rgba(21, 105, 222, 0.2);

  &:hover:not(:disabled) {
    background: #2b7de9;
    transform: translateY(-0.5px);
  }
}

.danger-btn {
  background: #e74c3c;
  color: #fff;
  box-shadow: 0 2px 6px rgba(231, 76, 60, 0.2);

  &:hover:not(:disabled) {
    background: #eb6e60;
    transform: translateY(-0.5px);
  }
}

.secondary-btn {
  background: #ffffff;
  color: #555555;
  border: 1px solid #d9d9d9;

  &:hover:not(:disabled) {
    background: #f5f7fa;
    color: #333333;
    border-color: #c0c0c0;
  }
}

/* Simulated File Upload Dragbox */
.upload-box {
  border: 1.5px dashed rgba(21, 105, 222, 0.25);
  border-radius: 6px;
  padding: 20px 14px;
  text-align: center;
  cursor: pointer;
  transition: all 0.25s ease;
  background: rgba(21, 105, 222, 0.02);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;

  &:hover {
    border-color: #1569de;
    background: rgba(21, 105, 222, 0.05);
    
    .upload-icon {
      color: #1569de;
      transform: translateY(-1.5px);
    }
  }

  &.dragging {
    border-color: #2ecc71;
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
    color: #7f8c8d;
    transition: all 0.25s ease;
  }

  .success-icon {
    width: 26px;
    height: 26px;
    color: #2ecc71;
  }

  .upload-title {
    font-size: 11.5px;
    font-weight: 600;
    color: #2c3e50;
  }

  .glow-green {
    color: #27ae60;
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
  border-radius: 6px;
  border: 1px solid rgba(21, 105, 222, 0.08);
  padding: 8px;

  .metrics-grid {
    display: grid;
    grid-template-columns: repeat(2, 1fr);
    gap: 6px;
  }

  .metric-item {
    background: #ffffff;
    border-radius: 4px;
    padding: 5px 8px;
    border-left: 2.5px solid #1569de;
    border-top: 1px solid rgba(0, 0, 0, 0.03);
    border-right: 1px solid rgba(0, 0, 0, 0.03);
    border-bottom: 1px solid rgba(0, 0, 0, 0.03);

    .m-label {
      font-size: 9px;
      color: #7f8c8d;
      margin-bottom: 1px;
    }

    .m-val {
      font-size: 12px;
      font-weight: 700;
      color: #1a365d;
      font-family: "Outfit", monospace;

      .unit {
        font-size: 9px;
        color: #7f8c8d;
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
  border: 1px solid #d9d9d9;
  border-radius: 6px;
  padding: 8px;
  margin-bottom: 10px;
  
  .queue-title-row {
    font-size: 10.5px;
    font-weight: bold;
    color: #7f8c8d;
    margin-bottom: 6px;
    border-bottom: 1px solid rgba(0, 0, 0, 0.05);
    padding-bottom: 4px;
  }

  .empty-queue-placeholder {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 24px 0;
    gap: 6px;
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
    background: #ffffff;
    border: 1px solid rgba(0, 0, 0, 0.05);
    border-radius: 4px;
    padding: 6px;
    gap: 8px;
    box-shadow: 0 1px 3px rgba(0,0,0,0.02);

    .mod-badge {
      font-size: 9.5px;
      font-weight: bold;
      padding: 1px 5px;
      border-radius: 3px;
      flex-shrink: 0;

      &.station { background: #e8f8f5; color: #1abc9c; border: 1px solid rgba(26,188,156,0.3); }
      &.route { background: #eaf2f8; color: #2980b9; border: 1px solid rgba(41,128,185,0.3); }
      &.fare { background: #f5eef8; color: #9b59b6; border: 1px solid rgba(155,89,182,0.3); }
      &.mode { background: #fdf2e9; color: #e67e22; border: 1px solid rgba(230,126,34,0.3); }
      &.road { background: #fce4d6; color: #c55a11; border: 1px solid rgba(197,90,17,0.3); }
    }

    .mod-details {
      flex: 1;
      min-width: 0;

      .mod-type {
        font-size: 10.5px;
        font-weight: bold;
        color: #2c3e50;
      }
      
      .mod-desc {
        font-size: 9.5px;
        color: #7f8c8d;
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
      transition: all 0.2s ease;
      flex-shrink: 0;

      &:hover {
        background: #fdf2f2;
        color: #e74c3c;
      }
    }
  }
}

.add-item-trigger {
  margin-top: 6px;
  background: #f8f9fa;
  border-style: dashed;
  border-width: 1.5px;
  
  &:hover:not(:disabled) {
    background: rgba(21, 105, 222, 0.04);
    border-color: #1569de;
    color: #1569de;
  }
}

/* QGIS STYLE NESTED TWO-COLUMN BUILDER SYSTEM */
.qgis-nested-builder {
  background: #ffffff;
  border: 1px solid rgba(21, 105, 222, 0.25);
  border-radius: 6px;
  padding: 8px;
  margin-top: 8px;
  box-shadow: 0 4px 12px rgba(15, 66, 125, 0.08);
  display: flex;
  flex-direction: column;
  gap: 8px;

  .builder-title-row {
    font-size: 11px;
    font-weight: bold;
    color: #1569de;
    border-bottom: 1px solid rgba(21, 105, 222, 0.15);
    padding-bottom: 4px;
  }

  .qgis-columns {
    display: flex;
    border: 1px solid #d9d9d9;
    border-radius: 4px;
    overflow: hidden;
    height: 160px;
  }

  /* Left column: Categories selector */
  .qgis-left-col {
    width: 35%;
    background: #f4f6f8;
    border-right: 1px solid #d9d9d9;
    display: flex;
    flex-direction: column;
  }

  .qgis-cat-item {
    padding: 7px 10px;
    font-size: 11px;
    font-weight: bold;
    color: #555555;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: space-between;
    transition: all 0.2s ease;
    border-bottom: 1px solid rgba(0,0,0,0.03);

    &:hover {
      background: rgba(21, 105, 222, 0.04);
      color: #1569de;
    }

    &.active {
      background: #ffffff;
      color: #1569de;
      position: relative;

      &::before {
        content: "";
        position: absolute;
        left: 0;
        top: 0;
        width: 3px;
        height: 100%;
        background-color: #1569de;
      }
      
      .arrow {
        color: #1569de;
        opacity: 1;
      }
    }

    .arrow {
      color: #bdc3c7;
      opacity: 0.5;
    }
  }

  /* Right column: Options & Parameters */
  .qgis-right-col {
    width: 65%;
    background: #ffffff;
    padding: 8px;
    box-sizing: border-box;
  }

  .qgis-right-content {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .sub-option-select {
    display: flex;
    flex-direction: column;
    gap: 3px;
  }

  .sub-params-box {
    display: flex;
    flex-direction: column;
    gap: 6px;
    border-top: 1px solid rgba(0, 0, 0, 0.05);
    padding-top: 6px;
  }

  .param-row {
    display: flex;
    flex-direction: column;
    gap: 3px;

    .slider-header-sub {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: 10px;
      color: #7f8c8d;
      font-weight: bold;

      .slider-val {
        color: #1569de;
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
    gap: 8px;
    border-top: 1px solid rgba(0, 0, 0, 0.05);
    padding-top: 6px;

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
    color: #7f8c8d;
  }

  .block-select {
    width: 100%;
  }

  .slider-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    font-size: 11px;
    color: #7f8c8d;
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
  color: #7f8c8d;
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
    color: #1a365d;
    font-weight: bold;
  }

  .switch-desc {
    font-size: 9px;
    color: #7f8c8d;
  }

  :deep(.el-switch) {
    --el-switch-on-color: #1569de;
  }
}

/* Compile Trigger Button */
.build-scenario-btn {
  width: 100%;
  background: linear-gradient(135deg, #10b981 0%, #059669 100%);
  color: #fff;
  border: none;
  height: 36px;
  border-radius: 6px;
  font-size: 12.5px;
  font-weight: bold;
  cursor: pointer;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  box-shadow: 0 3px 8px rgba(16, 185, 129, 0.25);
  overflow: hidden;
  transition: all 0.2s ease;

  &::after {
    content: "";
    position: absolute;
    top: 0;
    left: -50%;
    width: 200%;
    height: 100%;
    background: linear-gradient(to right, rgba(255,255,255,0) 0%, rgba(255,255,255,0.15) 50%, rgba(255,255,255,0) 100%);
    transform: skewX(-25deg);
    transition: 0.75s;
  }

  &:hover:not(:disabled) {
    box-shadow: 0 4px 12px rgba(16, 185, 129, 0.35);
    transform: translateY(-0.5px);

    &::after {
      left: 125%;
    }
  }

  &:disabled {
    opacity: 0.5;
    background: #bdc3c7;
    box-shadow: none;
    cursor: not-allowed;
  }

  .pulse-ring {
    position: absolute;
    width: 100%;
    height: 100%;
    border-radius: 6px;
    border: 2px solid #10b981;
    animation: btn-ripple 2.5s infinite;
    pointer-events: none;
  }
}

/* HIGH TECH TERMINAL LOADER OVERLAY (Light Theme Premium Unification) */
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

.tech-scanner {
  width: 44px;
  height: 44px;
  border: 2.5px solid #1569de;
  border-radius: 50%;
  border-top-color: transparent;
  border-bottom-color: transparent;
  animation: rotate-spinner 1.2s linear infinite;
  margin-bottom: 12px;
}

.loader-spinner {
  width: 18px;
  height: 18px;
  border: 2px solid #cbd5e1;
  border-radius: 50%;
  border-top-color: #1569de;
  animation: rotate-spinner 0.8s linear infinite;
}

.loader-title {
  font-family: "Outfit", sans-serif;
  font-size: 15px;
  font-weight: 800;
  letter-spacing: 1.5px;
  color: #1569de;
  margin-bottom: 10px;
}

.loader-bar-bg {
  width: 80%;
  height: 5px;
  background: #e2e8f0;
  border-radius: 3px;
  overflow: hidden;
  margin-bottom: 4px;
}

.loader-bar {
  height: 100%;
  background: linear-gradient(90deg, #1569de 0%, #10b981 100%);
  border-radius: 3px;
  transition: width 0.1s linear;
}

.progress-num {
  font-family: "Outfit", monospace;
  font-size: 11px;
  font-weight: bold;
  color: #7f8c8d;
  margin-bottom: 12px;
}

.terminal-logs {
  width: 100%;
  height: 140px;
  background: rgba(21, 105, 222, 0.03);
  border: 1px solid rgba(21, 105, 222, 0.15);
  border-radius: 6px;
  padding: 8px;
  font-family: "Consolas", "Courier New", monospace;
  font-size: 9.5px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 5px;
  scroll-behavior: smooth;
  box-sizing: border-box;

  &::-webkit-scrollbar {
    width: 4px;
  }
  &::-webkit-scrollbar-thumb {
    background: rgba(21, 105, 222, 0.15);
    border-radius: 2px;
  }

  .log-line {
    line-height: 1.35;
    display: flex;
    gap: 6px;

    .timestamp {
      color: #95a5a6;
      flex-shrink: 0;
    }

    .message {
      word-break: break-all;
    }

    &.info {
      color: #2c3e50;
    }

    &.success {
      color: #27ae60;
      font-weight: bold;
    }

    &.error {
      color: #c0392b;
    }
  }
}

/* Animations */
@keyframes rotate-spinner {
  to { transform: rotate(360deg); }
}

@keyframes btn-ripple {
  0% { transform: scale(0.96); opacity: 0.8; }
  50% { transform: scale(1.04); opacity: 0; }
  100% { transform: scale(1.04); opacity: 0; }
}

.text-pulse {
  animation: pulse-opacity 1.5s infinite;
}

@keyframes pulse-opacity {
  0% { opacity: 0.6; }
  50% { opacity: 1; }
  100% { opacity: 0.6; }
}

/* Transitions */
.fade-enter-active, .fade-leave-active {
  transition: opacity 0.3s ease;
}
.fade-enter-from, .fade-leave-to {
  opacity: 0;
}

.fade-slide-enter-active, .fade-slide-leave-active {
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
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
  transition: all 0.15s ease;
}

.mac-menu-item:hover {
  background: #0060df;
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
</style>

<style>
/* Un-scoped CSS for el-popover teleported to body */
.mac-os-popover {
  padding: 4px !important;
  background: rgba(255, 255, 255, 0.96) !important;
  backdrop-filter: blur(20px) !important;
  border: 1px solid rgba(0, 0, 0, 0.1) !important;
  box-shadow: 0 5px 15px rgba(0, 0, 0, 0.15), 0 15px 35px rgba(0, 0, 0, 0.1) !important;
  border-radius: 6px !important;
}
</style>
